package dev.dlssmc;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.dlssmc.mixin.FrontendGpuDeviceAccessor;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkDevice;

/**
 * Renders the world into a reduced-resolution target and upscales it with DLSS into Minecraft's main target right before
 * the GUI is drawn, so the HUD stays at native resolution.
 * <p>
 * While the world is being drawn ("world phase"), every lookup of the main render target is redirected to
 * {@link #worldTarget}. This covers vanilla and any mod that goes through {@code GameRenderer.mainRenderTarget()}
 * (Sodium, Distant Horizons, ...). DLSS and the passes that prepare its inputs are recorded into the command buffer
 * Minecraft is recording, between its own commands.
 * <p>
 * Frame generation needs the same motion vectors and depth. Without upscaling they are made from the native-resolution
 * world (no redirect, no jitter) while it runs.
 */
public final class WorldUpscaler {
	private static final int MAX_LOGGED_FAILURES = 5;
	/** Failed frames after which DLSS is switched off until its settings change. */
	private static final int FAILURES_BEFORE_DISABLE = 30;
	/** A camera jump farther than this (squared) resets DLSS's history. */
	private static final double TELEPORT_SQR = 64.0;

	private enum State {
		UNINITIALIZED,
		READY,
		UNAVAILABLE
	}

	private static State state = State.UNINITIALIZED;
	/** NGX is up, DLSS Super Resolution is available and the compute passes are loaded. */
	private static boolean dlssReady;
	/** The compute passes that make motion vectors and depth are loaded (stays true when DLSS itself is given up on). */
	private static boolean passesReady;
	private static boolean frameGenAvailable;
	private static boolean otherBackend;
	private static String unavailableReason = "";
	private static int loggedFailures;
	/** Setting that DLSS was disabled for after repeated failures; another choice gets a fresh try. */
	@Nullable
	private static String failedSetting;
	private static boolean loggedMissingInputs;

	@Nullable
	private static TextureTarget worldTarget;
	@Nullable
	private static RenderTarget realMainTarget;
	private static boolean inWorldPhase;

	// ---- temporal state
	private static final Jitter jitter = new Jitter();
	private static final DepthCapture depth = new DepthCapture();
	private static final MotionBoxes motionBoxes = new MotionBoxes();
	private static final MemorySegment frame = Arena.global().allocate(DlssNative.FRAME_SIZE, 16);
	/** The shader pack's vignette, drawn after upscaling (VitrailCompat#vignette). */
	private static final float[] vignette = new float[3];
	private static final float[] matrixScratch = new float[16];
	/** This frame is jittered and upscaled with DLSS. */
	private static boolean temporalFrame;
	/** This frame's depth and unjittered projection are captured for motion vectors (DLSS, or frame generation alone). */
	private static boolean captureFrame;
	private static boolean temporalHistoryValid;
	private static boolean prevUpscaled;
	/** This frame's jitter goes through the shader pack's own uniform: the projection matrices stay unjittered. */
	private static boolean packJitterFrame;
	@Nullable
	private static CameraRenderState jitteredCamera;
	private static final Matrix4f savedProjection = new Matrix4f();
	/** The world's final projection (with view bobbing), without jitter. */
	private static final Matrix4f levelProjection = new Matrix4f();
	private static boolean levelProjectionCaptured;
	private static final Matrix4f viewProj = new Matrix4f();
	private static final Matrix4f invViewProj = new Matrix4f();
	private static final Matrix4f prevViewProj = new Matrix4f();
	private static Vec3 prevCameraPos = Vec3.ZERO;
	@Nullable
	private static ClientLevel prevLevel;
	private static int prevWidth, prevHeight, prevOutWidth;
	private static long lastTemporalNanos;

	// Last arguments GameRenderer passed to GlobalSettingsUniform.update, so ScreenSize can be swapped
	// to the world resolution during the world phase.
	@Nullable
	private static GlobalSettingsUniform uniform;
	private static double uGlint;
	private static long uGameTime;
	private static float uPartialTicks;
	private static int uBlur;
	private static Vec3 uCameraPos = Vec3.ZERO;
	private static boolean uRgss;

	/** Test hook: runs once per rendered frame before the world is drawn. */
	@Nullable
	public static Runnable frameHook;

	private WorldUpscaler() {
	}

	// ------------------------------------------------------------------ setup

	private static void ensureInit() {
		if (state != State.UNINITIALIZED) {
			return;
		}
		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return;
		}
		if (!(device instanceof FrontendGpuDevice frontend)
			|| !(((FrontendGpuDeviceAccessor)frontend).dlssmc$getBackend() instanceof VulkanDevice vk)) {
			DlssMod.LOGGER.warn("Graphics backend is not Vulkan; DLSS is off");
			state = State.UNAVAILABLE;
			otherBackend = true;
			unavailableReason = "needs the Vulkan graphics backend";
			return;
		}
		state = State.READY;
		if (Boolean.getBoolean("dlssmc.skipNgx")) {
			unavailableReason = "disabled by -Ddlssmc.skipNgx";
			return;
		}
		if (!DlssNative.load()) {
			unavailableReason = "native bridge failed to load";
			return;
		}
		try {
			initDlss(vk);
		} catch (RuntimeException e) {
			unavailableReason = e.toString();
			DlssMod.LOGGER.error("DLSS initialisation failed", e);
		}
	}

	private static void initDlss(VulkanDevice vk) {
		VkDevice vkDevice = vk.vkDevice();
		long gipa = VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr");
		long gdpa = vkDevice.getCapabilities().vkGetDeviceProcAddr;
		long start = System.nanoTime();
		int result = DlssNative.init(vk.instance().vkInstance().address(), vkDevice.getPhysicalDevice().address(), vkDevice.address(),
			gipa, gdpa, DlssConfig.ngxLogging || Boolean.getBoolean("dlssmc.ngxLogging"));
		if (result < 0 || (result & DlssNative.INIT_SUPER_RESOLUTION) == 0) {
			unavailableReason = DlssNative.lastError();
			DlssMod.LOGGER.warn("DLSS unavailable on {}: {}", vk.getDeviceInfo().name(), unavailableReason);
			return;
		}
		if (!DlssNative.loadShaders(Shaders.compileAll())) {
			unavailableReason = DlssNative.lastError();
			DlssMod.LOGGER.error("DLSS compute passes failed to load: {}", unavailableReason);
			return;
		}
		dlssReady = true;
		passesReady = true;
		frameGenAvailable = (result & DlssNative.INIT_FRAME_GENERATION) != 0;
		DlssMod.LOGGER.info("NVIDIA DLSS ready on {} in {} ms (Super Resolution: yes, Frame Generation: {})", vk.getDeviceInfo().name(),
			(System.nanoTime() - start) / 1_000_000, frameGenAvailable ? "yes, up to " + (DlssNative.frameGenMaxMultiFrame() + 1) + "x" : "no");
	}

	/** Called right before Minecraft destroys its Vulkan device (game exit). */
	public static void shutdown() {
		if (state == State.READY) {
			DlssNative.shutdown();
			dlssReady = false;
			passesReady = false;
			frameGenAvailable = false;
			state = State.UNAVAILABLE;
			unavailableReason = "shut down";
		}
	}

	/** The game runs on another graphics backend than Vulkan (OpenGL), so the mod does nothing. */
	public static boolean needsVulkan() {
		ensureInit();
		return otherBackend;
	}

	/** Why DLSS can't run (F3 screen, status messages). */
	public static String unavailableReason() {
		return unavailableReason;
	}

	/** Upscaling (or DLAA) runs: the world renders into {@link #worldTarget}. */
	public static boolean isActive() {
		ensureInit();
		if (state != State.READY || !DlssConfig.enabled) {
			return false;
		}
		// DLAA (DLSS at 100%) still anti-aliases; a plain stretch at 100% would do nothing.
		return DlssConfig.renderScale() < 0.999F || DlssConfig.upscaler == DlssConfig.Upscaler.DLSS && isDlssReady();
	}

	private static String currentSetting() {
		return DlssConfig.upscaler + "@" + DlssConfig.quality + "/" + DlssConfig.renderScale() + "/" + DlssConfig.preset;
	}

	public static boolean isDlssReady() {
		ensureInit();
		if (!dlssReady && failedSetting != null && !failedSetting.equals(currentSetting())) {
			// The user picked another mode since DLSS was disabled: try again.
			failedSetting = null;
			loggedFailures = 0;
			dlssReady = true;
		}
		return dlssReady;
	}

	/** DLSS was set up and works, without setting it up (safe off the render thread). */
	public static boolean isDlssKnownReady() {
		return dlssReady;
	}

	public static boolean isFrameGenAvailable() {
		ensureInit();
		return frameGenAvailable;
	}

	/**
	 * Frame generation can get motion vectors and depth with the current settings: at the native resolution when not
	 * upscaling, from DLSS when upscaling with it (a plain bilinear stretch has none).
	 */
	public static boolean hasFrameGenInputs() {
		return passesReady && (!isActive() || DlssConfig.upscaler == DlssConfig.Upscaler.DLSS && isDlssReady());
	}

	/** World render size while upscaling, or null (F3 screen). */
	public static int @Nullable [] worldRenderSize() {
		return isActive() && worldTarget != null ? new int[] {worldTarget.width, worldTarget.height} : null;
	}

	public static int scaled(int fullSize) {
		return isActive() ? Math.max(1, Math.round(fullSize * DlssConfig.renderScale())) : fullSize;
	}

	// ------------------------------------------------------------------ sizing

	/** True if the targets don't match the current config and GameRenderer.resize should be re-run. */
	public static boolean needsResize(RenderTarget mainTarget, RenderTarget hud3dTarget) {
		int sw = scaled(mainTarget.width);
		int sh = scaled(mainTarget.height);
		if (hud3dTarget.width != sw || hud3dTarget.height != sh) {
			return true;
		}
		return isActive() && (worldTarget == null || worldTarget.width != sw || worldTarget.height != sh);
	}

	/**
	 * Called at the end of GameRenderer.resize. The world target is kept alive (never destroyed) once created,
	 * because other renderers may have cached a reference to it during a world phase.
	 */
	public static void onResize(int width, int height) {
		if (!isActive()) {
			return;
		}
		int sw = scaled(width);
		int sh = scaled(height);
		if (worldTarget == null) {
			worldTarget = new TextureTarget("DLSS World", sw, sh, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
		} else if (worldTarget.width != sw || worldTarget.height != sh) {
			worldTarget.resize(sw, sh);
		}
		DlssMod.LOGGER.info("World render resolution {}x{} -> output {}x{}", sw, sh, width, height);
	}

	// ------------------------------------------------------------------ world phase

	public static RenderTarget redirect(RenderTarget target) {
		return inWorldPhase && target == realMainTarget && worldTarget != null ? worldTarget : target;
	}

	static boolean inWorldPhase() {
		return inWorldPhase;
	}

	public static void captureUniformArgs(
		GlobalSettingsUniform instance, double glint, long gameTime, float partialTicks, int blur, Vec3 cameraPos, boolean rgss
	) {
		uniform = instance;
		uGlint = glint;
		uGameTime = gameTime;
		uPartialTicks = partialTicks;
		uBlur = blur;
		uCameraPos = cameraPos;
		uRgss = rgss;
	}

	private static void updateScreenSize(int width, int height) {
		if (uniform != null) {
			uniform.update(width, height, uGlint, uGameTime, uPartialTicks, uBlur, uCameraPos, uRgss);
		}
	}

	public static void beginWorld(RenderTarget mainTarget) {
		if (frameHook != null) {
			frameHook.run();
		}
		TextureViews.prune();
		levelProjectionCaptured = false;
		TextureTarget world = worldTarget;
		boolean upscale = isActive() && world != null && world.width <= mainTarget.width
			&& world.width == scaled(mainTarget.width) && world.height == scaled(mainTarget.height);
		if (!upscale) {
			// Frame generation alone: motion vectors and depth from the world as drawn into the main target.
			captureFrame = passesReady && FrameGen.isRunning();
			realMainTarget = captureFrame ? mainTarget : null;
			depth.beginFrame(realMainTarget);
			return;
		}
		realMainTarget = mainTarget;
		inWorldPhase = true;
		temporalFrame = DlssConfig.upscaler == DlssConfig.Upscaler.DLSS && isDlssReady();
		captureFrame = temporalFrame;
		if (temporalFrame) {
			jitterCamera(world.width, world.height, (double)mainTarget.width / world.width);
		}
		depth.beginFrame(temporalFrame ? world : null);
		// After the jitter: the globals also carry the texture LOD correction (see lodScaleCode).
		updateScreenSize(world.width, world.height);
	}

	/**
	 * Render scale for the terrain shaders' texture LOD/edge correction, as round(scale * 1024), or 0 for none.
	 * Only used with DLSS, which can reconstruct the extra detail (see {@link ShaderPatches}).
	 */
	public static int lodScaleCode() {
		if (!inWorldPhase || !temporalFrame || worldTarget == null || realMainTarget == null || !DlssConfig.textureLodCorrection) {
			return 0;
		}
		return Math.max(1, Math.min(1024, Math.round(1024.0F * worldTarget.width / realMainTarget.width)));
	}

	public static void endWorld() {
		RenderTarget main = realMainTarget;
		boolean upscale = inWorldPhase;
		boolean temporal = temporalFrame;
		boolean capture = captureFrame;
		realMainTarget = null;
		inWorldPhase = false;
		temporalFrame = false;
		captureFrame = false;
		depth.endFrame();
		restoreCameraProjection();
		if (main == null || upscale && worldTarget == null) {
			temporalHistoryValid = false;
			return;
		}
		if (!upscale) {
			if (!capture || !recordFrame(main, main, false)) {
				temporalHistoryValid = false;
			}
			return;
		}
		updateScreenSize(main.width, main.height);
		if (!temporal || !recordFrame(worldTarget, main, true)) {
			temporalHistoryValid = false;
			upscaleBilinear(worldTarget, main);
		}
	}

	// ------------------------------------------------------------------ jitter and projections

	private static void jitterCamera(int width, int height, double outputRatio) {
		jitter.advance(width, height, outputRatio);
		// Shader packs that jitter through their own uniform get it there instead (VitrailCompat.overridePackJitter).
		packJitterFrame = VitrailCompat.packJitter();
		if (packJitterFrame) {
			return;
		}
		CameraRenderState camera = cameraState();
		savedProjection.set(camera.projectionMatrix);
		jitter.apply(camera.projectionMatrix, 1.0F);
		jitteredCamera = camera;
	}

	private static void restoreCameraProjection() {
		if (jitteredCamera != null) {
			jitteredCamera.projectionMatrix.set(savedProjection);
			jitteredCamera = null;
		}
	}

	/** This frame's jitter as a clip-space (NDC) offset; 0 when not jittering. */
	public static float jitterNdcX() {
		return temporalFrame ? jitter.ndcX() : 0.0F;
	}

	public static float jitterNdcY() {
		return temporalFrame ? jitter.ndcY() : 0.0F;
	}

	/** GameRenderer.renderLevel's final (jittered, bobbed) projection, as uploaded for the world. */
	public static void captureLevelProjection(Matrix4f projection) {
		if (!captureFrame) {
			return;
		}
		levelProjection.set(projection);
		if (temporalFrame && !packJitterFrame) {
			jitter.apply(levelProjection, -1.0F);
		}
		levelProjectionCaptured = true;
	}

	public static boolean isTemporalFrame() {
		return temporalFrame;
	}

	/** The first-person hand's projection, jittered the same way as the world (so DLSS sees consistent jitter). */
	public static Matrix4f handProjection(Projection projection) {
		Matrix4f matrix = projection.getMatrix(new Matrix4f());
		return temporalFrame && !packJitterFrame ? jitter.apply(matrix, 1.0F) : matrix;
	}

	private static CameraRenderState cameraState() {
		return Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
	}

	// ------------------------------------------------------------------ depth capture hooks

	/** Called right before render3dHud clears {@code texture} for the hand pass. */
	public static void beforeHandDepthClear(GpuTexture texture) {
		depth.beforeHandDepthClear(texture);
	}

	/** Called by Vitrail right before it draws the first-person hand into the world (its replacement of the hand pass). */
	public static void beforePackHand() {
		depth.beforePackHand();
	}

	/** Called by Vitrail right after it drew the first-person hand into the world. */
	public static void afterPackHand() {
		depth.afterPackHand();
	}

	/** Called by Vitrail right before it draws the translucent half of the hand. */
	public static void beforePackTranslucentHand() {
		depth.beforePackTranslucentHand();
	}

	/** Called by Vitrail right after it drew the translucent half of the hand. */
	public static void afterPackTranslucentHand() {
		depth.afterPackTranslucentHand();
	}

	// ------------------------------------------------------------------ upscaling

	/**
	 * Makes this frame's motion vectors and depth and, if {@code upscale}, upscales {@code source} into {@code destination}
	 * with DLSS (without upscaling both are the main target). Hands the camera to frame generation.
	 */
	private static boolean recordFrame(RenderTarget source, RenderTarget destination, boolean upscale) {
		GpuTexture src = source.getColorTexture();
		GpuTexture dst = destination.getColorTexture();
		GpuTexture sceneDepth = depth.sceneDepth();
		if (!levelProjectionCaptured || sceneDepth == null || src == null || dst == null) {
			// Happens on frames that skip the level pass (loading screens, shader pack reloads): not a DLSS failure.
			if (!loggedMissingInputs) {
				loggedMissingInputs = true;
				DlssMod.LOGGER.info("Motion vector inputs missing this frame (projection {}, hand depth {}); skipping such frames",
					levelProjectionCaptured, depth.handDepth() != null);
			}
			return false;
		}
		long commandBuffer = Recording.commandBuffer();
		if (commandBuffer == 0L || !DlssNative.writeTex(frame, DlssNative.FRAME_COLOR, src, source.getColorTextureView())
			|| !Recording.writeTex(frame, DlssNative.FRAME_DEPTH, sceneDepth) || !Recording.writeTex(frame, DlssNative.FRAME_HAND, depth.handDepth())
			|| !DlssNative.writeTex(frame, DlssNative.FRAME_OUTPUT, dst, destination.getColorTextureView())) {
			logFailure(upscale, "could not reach the Vulkan images or command buffer");
			return false;
		}

		CameraRenderState camera = cameraState();
		levelProjection.mul(camera.viewRotationMatrix, viewProj);
		Vec3 cameraPos = camera.pos;
		ClientLevel level = Minecraft.getInstance().level;
		boolean reset = !temporalHistoryValid
			|| upscale != prevUpscaled
			|| level != prevLevel
			|| source.width != prevWidth
			|| source.height != prevHeight
			|| destination.width != prevOutWidth
			|| cameraPos.distanceToSqr(prevCameraPos) > TELEPORT_SQR;
		if (reset) {
			prevViewProj.set(viewProj);
			prevCameraPos = cameraPos;
		}
		Vec3 camDelta = cameraPos.subtract(prevCameraPos);
		float jitterX = upscale ? jitter.pixelX() : 0.0F;
		float jitterY = upscale ? jitter.pixelY() : 0.0F;
		writeFrame(cameraPos, camDelta, level, jitterX, jitterY, reset, upscale);

		int result = DlssNative.upscale(commandBuffer, frame);
		if (result <= 0) {
			logFailure(upscale, DlssNative.lastError());
			if (result < 0 && upscale) {
				disableDlss();
			}
			return false;
		}
		FrameGen.writeCamera(levelProjection, camera.viewRotationMatrix, viewProj, prevViewProj, camDelta, cameraPos, jitterX, jitterY, reset);
		prevViewProj.set(viewProj);
		prevCameraPos = cameraPos;
		prevLevel = level;
		prevWidth = source.width;
		prevHeight = source.height;
		prevOutWidth = destination.width;
		prevUpscaled = upscale;
		temporalHistoryValid = true;
		return true;
	}

	/** Everything in struct Frame besides the textures. */
	private static void writeFrame(Vec3 cameraPos, Vec3 camDelta, @Nullable ClientLevel level, float jitterX, float jitterY, boolean reset,
		boolean upscale) {
		writeMatrix(DlssNative.FRAME_INV_VIEW_PROJ, viewProj.invert(invViewProj));
		writeMatrix(DlssNative.FRAME_PREV_VIEW_PROJ, prevViewProj);
		putFloats(DlssNative.FRAME_CAM_DELTA, (float)camDelta.x, (float)camDelta.y);
		putFloats(DlssNative.FRAME_CAM_DELTA + 8, (float)camDelta.z, 0.0F);
		motionBoxes.writePlayerBox(frame, DlssNative.FRAME_OBJ_MIN, cameraPos, reset);
		frame.set(ValueLayout.JAVA_LONG, DlssNative.FRAME_BOXES, motionBoxes.entityBoxes.address());
		frame.set(ValueLayout.JAVA_INT, DlssNative.FRAME_BOX_COUNT, motionBoxes.writeEntityBoxes(level, cameraPos, reset));
		VitrailCompat.vignette(vignette);
		frame.set(ValueLayout.JAVA_INT, DlssNative.FRAME_VIGNETTE, (int)vignette[0]);
		putFloats(DlssNative.FRAME_VIGNETTE + 4, vignette[1], vignette[2]);
		putFloats(DlssNative.FRAME_JITTER, jitterX, jitterY);
		frame.set(ValueLayout.JAVA_INT, DlssNative.FRAME_RESET, reset ? 1 : 0);
		frame.set(ValueLayout.JAVA_INT, DlssNative.FRAME_QUALITY, DlssConfig.ngxQuality());
		frame.set(ValueLayout.JAVA_INT, DlssNative.FRAME_PRESET, DlssConfig.preset.ngxValue);
		frame.set(ValueLayout.JAVA_INT, DlssNative.FRAME_Z_ZERO_TO_ONE, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1 : 0);
		long now = System.nanoTime();
		frame.set(ValueLayout.JAVA_FLOAT, DlssNative.FRAME_FRAME_TIME, lastTemporalNanos == 0L ? 16.7F : (now - lastTemporalNanos) / 1.0e6F);
		lastTemporalNanos = now;
		frame.set(ValueLayout.JAVA_INT, DlssNative.FRAME_UPSCALE, upscale ? 1 : 0);
	}

	private static void writeMatrix(long offset, Matrix4f matrix) {
		matrix.get(matrixScratch);
		MemorySegment.copy(matrixScratch, 0, frame, ValueLayout.JAVA_FLOAT, offset, matrixScratch.length);
	}

	private static void putFloats(long offset, float x, float y) {
		frame.set(ValueLayout.JAVA_FLOAT, offset, x);
		frame.set(ValueLayout.JAVA_FLOAT, offset + 4, y);
	}

	private static void upscaleBilinear(RenderTarget source, RenderTarget destination) {
		try (RenderPass pass = RenderSystem.getDevice()
			.createCommandEncoder()
			.createRenderPass(() -> "DLSS bilinear upscale", destination.getColorTextureView(), Optional.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("InSampler", source.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.draw(3, 1, 0, 0);
		}
	}

	private static void disableDlss() {
		DlssMod.LOGGER.error("DLSS failed with {}; using bilinear until the DLSS settings change", currentSetting());
		dlssReady = false;
		failedSetting = currentSetting();
	}

	private static void logFailure(boolean upscale, String message) {
		loggedFailures++;
		if (loggedFailures <= MAX_LOGGED_FAILURES) {
			DlssMod.LOGGER.warn(upscale ? "DLSS failed, using bilinear this frame: {}" : "No motion vectors for frame generation this frame: {}", message);
		}
		if (upscale && loggedFailures == FAILURES_BEFORE_DISABLE && dlssReady) {
			disableDlss();
		}
	}
}
