package dev.metalfxmc;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.metalfxmc.mixin.FrontendGpuDeviceAccessor;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.KHRSynchronization2;

/**
 * Renders the world into a reduced-resolution target and upscales it into Minecraft's main target
 * right before the GUI is drawn, so the HUD stays at native resolution.
 * <p>
 * While the world is being drawn ("world phase"), every lookup of the main render target is
 * redirected to {@link #worldTarget}. This covers vanilla and any mod that goes through
 * {@code GameRenderer.mainRenderTarget()} (Sodium, Distant Horizons, ...).
 */
public final class WorldUpscaler {
	private static final long ALL_COMMANDS = KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR;
	private static final int MAX_LOGGED_FAILURES = 5;
	private static boolean loggedMissingInputs;

	private enum State {
		UNINITIALIZED,
		READY,
		UNAVAILABLE
	}

	private static State state = State.UNINITIALIZED;
	private static boolean metalFxReady;
	private static boolean otherBackend;
	@Nullable
	private static VulkanDevice vulkanDevice;
	private static long timelineSemaphore;
	private static long sharedEvent;
	private static long timelineValue;
	private static int loggedFailures;
	/** Smallest render scale MetalFX temporal accepts (it has a maximum upscale factor, 3x on Apple silicon). */
	private static float temporalMinScale;
	/** Mode + scale that MetalFX was disabled for after repeated failures; another choice gets a fresh try. */
	private static String failedSetting;
	private static int seenGpuErrors;

	@Nullable
	private static TextureTarget worldTarget;
	@Nullable
	private static RenderTarget realMainTarget;
	private static boolean inWorldPhase;

	// ---- temporal state
	private static final int PARAMS_SIZE = 320;
	private static final MemorySegment params = Arena.global().allocate(PARAMS_SIZE, 16);
	private static final float[] matrixScratch = new float[16];
	private static boolean temporalSupported;
	private static boolean temporalFrame;
	private static boolean temporalHistoryValid;
	private static int frameIndex;
	private static float jitterX;
	private static float jitterY;
	@Nullable
	private static CameraRenderState jitteredCamera;
	private static final Matrix4f savedProjection = new Matrix4f();
	private static final Matrix4f levelProjection = new Matrix4f();
	private static boolean levelProjectionCaptured;
	private static long lastTemporalNanos;
	private static final Matrix4f prevViewProj = new Matrix4f();
	private static Vec3 prevCameraPos = Vec3.ZERO;
	@Nullable
	private static ClientLevel prevLevel;
	private static int prevWidth;
	private static int prevHeight;
	/** Copy of the world depth taken before the first-person hand pass clears it. */
	@Nullable
	private static GpuTexture sceneDepth;
	/**
	 * Depth texture the hand was drawn into: only hand/screen-effect depth (vanilla), or with a shader pack, a copy of the
	 * world depth including the hand (equal to {@link #sceneDepth} wherever there is no hand).
	 */
	@Nullable
	private static GpuTexture handDepth;
	/** This frame's world depth was captured before a shader pack drew the hand into it (see {@link #beforePackHand}). */
	private static boolean packHandFrame;
	@Nullable
	private static GpuTexture packHandDepth;
	/** World depth right after a shader pack drew the hand (water, the block outline etc. come later). */
	@Nullable
	private static GpuTexture packAfterHandDepth;
	private static boolean packAfterHandCaptured;
	/** World depth right before and after a shader pack drew the translucent half of the hand (e.g. cut-out block items). */
	@Nullable
	private static GpuTexture packPreTlHandDepth, packPostTlHandDepth;
	private static boolean packPreTlCaptured, packPostTlCaptured;
	private static boolean loggedMergeFailure;

	/** This frame captures depth + the unjittered projection (for the temporal upscalers and/or frame generation). */
	private static boolean captureFrame;

	// ---- frame generation state
	private static final int FG_PARAMS_SIZE = 256;
	/** Debugging/tests: no player box in the motion vectors. */
	public static boolean debugNoPlayerBox;
	@Nullable
	private static Vec3 prevPlayerPos;
	@Nullable
	private static Vec3 fgPrevPlayerPos;
	private static final org.joml.Vector2f distantPair = new org.joml.Vector2f();
	private static boolean loggedDistantFailure;
	private static boolean loggedDistantMerge;
	private static final MemorySegment fgParams = Arena.global().allocate(FG_PARAMS_SIZE, 16);
	private static boolean frameGenSupported;
	private static boolean metalFxFrameGenSupported;
	/** This frame's world image, depth and camera were captured, so a frame can be generated before it. */
	private static boolean fgCaptured;
	private static boolean fgHistoryValid;
	private static final Matrix4f fgPrevViewProj = new Matrix4f();
	private static Vec3 fgPrevCameraPos = Vec3.ZERO;
	@Nullable
	private static ClientLevel fgPrevLevel;
	private static int fgPrevWidth;
	private static int fgPrevHeight;
	private static long fgLastNanos;
	/** Copies of this frame's world image (before the HUD) and first-person hand depth. */
	@Nullable
	private static GpuTexture fgWorld;
	@Nullable
	private static GpuTexture fgHand;
	/** Copy of this frame's scene depth (the next frame overwrites sceneDepth while frame generation may still read it). */
	@Nullable
	private static GpuTexture fgDepth;
	/** Debugging: also copy the generated frame into the main target (for screenshots). */
	public static boolean debugShowInterpolated;
	/** The native presenter was asked to attach (and must be detached when frame generation turns off). */
	private static boolean presenterRequested;
	/** The native presenter shows the frames: Minecraft doesn't acquire or present swapchain images. */
	private static boolean presenterLive;
	private static boolean loggedPresenterFailure;
	private static int fgFailures;

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
			|| !(((FrontendGpuDeviceAccessor)frontend).metalfx$getBackend() instanceof VulkanDevice vk)) {
			MetalFXMod.LOGGER.warn("Graphics backend is not Vulkan; MetalFX upscaling and frame generation are off");
			state = State.UNAVAILABLE;
			otherBackend = true;
			return;
		}
		state = State.READY;
		vulkanDevice = vk;
		if (!VulkanMetalInterop.isAvailable(vk.vkDevice())) {
			MetalFXMod.LOGGER.warn("{} is not enabled on the Vulkan device (not running on MoltenVK?); MetalFX unavailable", VulkanMetalInterop.EXTENSION);
			return;
		}
		if (!NativeBridge.load()) {
			return;
		}
		try {
			long mtlDevice = VulkanMetalInterop.mtlDevice(vk.vkDevice());
			int result = NativeBridge.init(mtlDevice);
			if (result != 1) {
				MetalFXMod.LOGGER.warn("MetalFX unavailable: {}", NativeBridge.lastError());
				return;
			}
			timelineSemaphore = VulkanMetalInterop.createSharedTimelineSemaphore(vk.vkDevice());
			sharedEvent = VulkanMetalInterop.mtlSharedEvent(vk.vkDevice(), timelineSemaphore);
			if (sharedEvent == 0L) {
				MetalFXMod.LOGGER.warn("Could not export MTLSharedEvent from timeline semaphore; MetalFX unavailable");
				return;
			}
			metalFxReady = true;
			temporalSupported = NativeBridge.temporalSupported();
			temporalMinScale = temporalSupported ? 1.0F / Math.max(1.0F, NativeBridge.temporalMaxScale()) : 0.0F;
			frameGenSupported = NativeBridge.frameInterpolationSupported();
			metalFxFrameGenSupported = frameGenSupported && NativeBridge.metalFxFrameInterpolationSupported();
			MetalFXMod.LOGGER.info(
				"MetalFX ready on {} (spatial: yes, temporal: {}, frame generation: {})", vk.getDeviceInfo().name(),
				temporalSupported ? "yes" : "no", !frameGenSupported ? "no" : metalFxFrameGenSupported ? "FSR 3 + MetalFX" : "FSR 3"
			);
		} catch (RuntimeException e) {
			MetalFXMod.LOGGER.error("MetalFX initialisation failed", e);
		}
	}

	/** The game runs on another graphics backend than Vulkan (OpenGL), so the mod does nothing. */
	public static boolean needsVulkan() {
		ensureInit();
		return otherBackend;
	}

	public static boolean isActive() {
		ensureInit();
		return state == State.READY && MetalFXConfig.enabled && MetalFXConfig.renderScale < 0.999F;
	}

	/** Render scale actually used: the configured one, raised to what the selected upscaler supports. */
	public static float effectiveScale() {
		ensureInit();
		float scale = MetalFXConfig.renderScale;
		if (MetalFXConfig.upscaler == MetalFXConfig.Upscaler.TEMPORAL && temporalMinScale > 0.0F) {
			// Round up so integer sizes stay within the limit.
			scale = Math.max(scale, (float)Math.ceil(temporalMinScale * 100.0F) / 100.0F);
		}
		return scale;
	}

	private static String currentSetting() {
		return MetalFXConfig.upscaler + "@" + effectiveScale();
	}

	public static boolean isMetalFxReady() {
		ensureInit();
		if (!metalFxReady && failedSetting != null && !failedSetting.equals(currentSetting())) {
			// The user picked another mode or scale since MetalFX was disabled: try again.
			failedSetting = null;
			loggedFailures = 0;
			metalFxReady = true;
		}
		return metalFxReady;
	}

	/** The frame generator in use: MetalFX if picked and available (macOS 26+), otherwise FSR 3. */
	public static MetalFXConfig.FrameGenBackend frameGenBackend() {
		ensureInit();
		return MetalFXConfig.frameGenBackend == MetalFXConfig.FrameGenBackend.METALFX && metalFxFrameGenSupported
			? MetalFXConfig.FrameGenBackend.METALFX : MetalFXConfig.FrameGenBackend.FSR;
	}

	public static boolean isFrameGenSupported() {
		ensureInit();
		return frameGenSupported;
	}

	/** Frame generation is switched on and can run (independent of the upscaler and of upscaling being on). */
	public static boolean isFrameGenActive() {
		return MetalFXConfig.frameGeneration && state == State.READY && isFrameGenSupported() && isMetalFxReady();
	}

	/**
	 * Called where Minecraft acquires a swapchain image each frame. While frame generation runs, the native presenter (its
	 * own layer over the game, paced by the display) shows the frames, so Minecraft must not acquire: without an acquired
	 * image it also skips its blit and present. Returns whether Minecraft should acquire.
	 */
	public static boolean shouldAcquireSwapchain() {
		if (!isFrameGenActive()) {
			if (presenterRequested) {
				NativeBridge.fgDetach();
				presenterRequested = false;
			}
			presenterLive = false;
			return true;
		}
		int result = NativeBridge.fgAttach();
		presenterRequested = true;
		if (result < 0 && !loggedPresenterFailure) {
			loggedPresenterFailure = true;
			MetalFXMod.LOGGER.warn("Frame generation unavailable: {}", NativeBridge.lastError());
		}
		presenterLive = result == 1;
		return !presenterLive;
	}

	/** The frame-generation presenter is showing the frames (F3 screen). */
	public static boolean isPresenterLive() {
		return presenterLive;
	}

	/** World render size while upscaling, or null (F3 screen). */
	public static int @Nullable [] worldRenderSize() {
		return isActive() && worldTarget != null ? new int[] {worldTarget.width, worldTarget.height} : null;
	}

	/** Frames Minecraft rendered in total (F3 screen). */
	public static long renderedFrames;

	/** Generated frames the presenter showed in total (for tests and the F3 screen). */
	public static long generatedShown;

	/** Generated frames the presenter showed since the last call (added to Minecraft's FPS counter). */
	public static int takeGeneratedFrames() {
		renderedFrames++;
		int frames = presenterRequested ? NativeBridge.fgTakeGenerated() : 0;
		generatedShown += frames;
		return frames;
	}

	public static int scaled(int fullSize) {
		return isActive() ? Math.max(1, Math.round(fullSize * effectiveScale())) : fullSize;
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
			worldTarget = new TextureTarget("MetalFX World", sw, sh, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
		} else if (worldTarget.width != sw || worldTarget.height != sh) {
			worldTarget.resize(sw, sh);
		}
		MetalFXMod.LOGGER.info("World render resolution {}x{} -> output {}x{}", sw, sh, width, height);
	}

	// ------------------------------------------------------------------ world phase

	public static RenderTarget redirect(RenderTarget target) {
		return inWorldPhase && target == realMainTarget && worldTarget != null ? worldTarget : target;
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

	/** Test hook: runs once per rendered frame before the world is drawn (used by the client gametest). */
	@Nullable
	public static Runnable frameHook;

	public static void beginWorld(RenderTarget mainTarget) {
		if (frameHook != null) {
			frameHook.run();
		}
		fgCaptured = false;
		levelProjectionCaptured = false;
		handDepth = null;
		packHandFrame = false;
		packAfterHandCaptured = false;
		packPreTlCaptured = packPostTlCaptured = false;
		boolean upscale = isActive() && worldTarget != null && worldTarget.width < mainTarget.width;
		boolean frameGen = isFrameGenActive();
		if (!upscale && !frameGen) {
			return;
		}
		realMainTarget = mainTarget;
		if (upscale) {
			inWorldPhase = true;
			beginTemporal(worldTarget.width, worldTarget.height);
		}
		captureFrame = temporalFrame || frameGen;
		if (captureFrame) {
			RenderTarget scene = upscale ? worldTarget : mainTarget;
			ensureSceneDepth(scene.width, scene.height);
		}
		if (upscale) {
			// After beginTemporal: the globals also carry the texture LOD correction (see lodScaleCode).
			updateScreenSize(worldTarget.width, worldTarget.height);
		}
	}

	/**
	 * Render scale for the terrain shaders' texture LOD/edge correction, as round(scale * 1024), or 0 for none.
	 * Only used with the temporal upscalers, which can reconstruct the extra detail (see {@link ShaderPatches}).
	 */
	public static int lodScaleCode() {
		if (!inWorldPhase || !temporalFrame || worldTarget == null || realMainTarget == null || !MetalFXConfig.textureLodCorrection) {
			return 0;
		}
		return Math.max(1, Math.min(1024, Math.round(1024.0F * worldTarget.width / realMainTarget.width)));
	}

	public static void endWorld() {
		RenderTarget main = realMainTarget;
		boolean upscale = inWorldPhase;
		boolean capture = captureFrame;
		boolean temporal = temporalFrame;
		realMainTarget = null;
		inWorldPhase = false;
		captureFrame = false;
		temporalFrame = false;
		restoreCameraProjection();
		if (main == null) {
			return;
		}
		if (upscale && worldTarget != null) {
			updateScreenSize(main.width, main.height);
			checkGpuErrors();
			upscale(worldTarget, main, temporal);
		}
		if (capture && isFrameGenActive()) {
			captureForFrameGen(main);
		}
	}

	private static void upscale(RenderTarget source, RenderTarget destination, boolean temporal) {
		if (temporal) {
			if (upscaleTemporal(source, destination)) {
				return;
			}
			temporalHistoryValid = false;
		} else {
			temporalHistoryValid = false;
			if (MetalFXConfig.upscaler == MetalFXConfig.Upscaler.SPATIAL && isMetalFxReady() && upscaleMetalFx(source, destination)) {
				return;
			}
		}
		upscaleBilinear(source, destination);
	}

	// ------------------------------------------------------------------ temporal: jitter, matrices, depth

	private static void beginTemporal(int width, int height) {
		boolean temporalMode = MetalFXConfig.upscaler == MetalFXConfig.Upscaler.TEMPORAL && temporalSupported
			|| MetalFXConfig.upscaler == MetalFXConfig.Upscaler.FSR;
		if (!temporalMode || !isMetalFxReady()) {
			return;
		}
		CameraRenderState camera = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
		temporalFrame = true;

		// Halton(2,3) sub-pixel jitter; MetalFX recommends >= 8 * (output/input)^2 phases.
		float ratio = (float)prevWidthRatio();
		int phases = Math.max(8, Math.min(64, Math.round(8.0F * ratio * ratio)));
		int index = frameIndex++ % phases + 1;
		boolean noJitter = MetalFXConfig.debugNoJitter;
		jitterX = noJitter ? 0.0F : halton(index, 2) - 0.5F;
		jitterY = noJitter ? 0.0F : halton(index, 3) - 0.5F;

		// Shift the whole projection by the jitter (in pixels) in clip space. Texture rows grow with NDC y unless flipY.
		float ndcX = 2.0F * jitterX / width;
		float ndcY = (MetalFXConfig.flipY ? -2.0F : 2.0F) * jitterY / height;
		savedProjection.set(camera.projectionMatrix);
		camera.projectionMatrix.set(new Matrix4f().translation(ndcX, ndcY, 0.0F).mul(savedProjection));
		jitteredCamera = camera;
	}

	/**
	 * Motion vectors only know the camera, but in third person the player moves along with it: writes the player's box
	 * (relative to the camera, a little larger for the held item and swinging limbs) and its movement since
	 * {@code prevPos} at {@code offset} (min, max, delta as float4s). An empty box in first person. Returns the player's
	 * position this frame.
	 */
	@Nullable
	private static Vec3 writePlayerBox(MemorySegment segment, long offset, Vec3 cameraPos, @Nullable Vec3 prevPos) {
		Minecraft mc = Minecraft.getInstance();
		var player = mc.player;
		boolean thirdPerson = player != null && mc.getCameraEntity() == player && !mc.options.getCameraType().isFirstPerson()
			&& !debugNoPlayerBox;
		Vec3 pos = player == null ? null : player.getPosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
		float[] box = {1.0e9F, 1.0e9F, 1.0e9F, 0.0F, -1.0e9F, -1.0e9F, -1.0e9F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F};
		if (thirdPerson && pos != null) {
			double half = player.getBbWidth() * 0.5 + 0.6;
			box[0] = (float)(pos.x - half - cameraPos.x);
			box[1] = (float)(pos.y - 0.3 - cameraPos.y);
			box[2] = (float)(pos.z - half - cameraPos.z);
			box[4] = (float)(pos.x + half - cameraPos.x);
			box[5] = (float)(pos.y + player.getBbHeight() + 0.5 - cameraPos.y);
			box[6] = (float)(pos.z + half - cameraPos.z);
			if (prevPos != null && pos.distanceToSqr(prevPos) < 64.0) {
				box[8] = (float)(pos.x - prevPos.x);
				box[9] = (float)(pos.y - prevPos.y);
				box[10] = (float)(pos.z - prevPos.z);
			}
		}
		for (int i = 0; i < box.length; i++) {
			segment.set(ValueLayout.JAVA_FLOAT, offset + 4L * i, box[i]);
		}
		return pos;
	}

	/** A menu with the blurred world behind it (the world image no longer shows through as it is). */
	private static boolean blurredMenuOpen() {
		Minecraft mc = Minecraft.getInstance();
		var screen = mc.gui.screen();
		return screen != null && !(screen instanceof net.minecraft.client.gui.screens.ChatScreen)
			&& mc.options.getMenuBackgroundBlurriness() > 0;
	}

	private static double prevWidthRatio() {
		RenderTarget main = realMainTarget;
		return main != null && worldTarget != null ? (double)main.width / worldTarget.width : 2.0;
	}

	private static void restoreCameraProjection() {
		if (jitteredCamera != null) {
			jitteredCamera.projectionMatrix.set(savedProjection);
			jitteredCamera = null;
		}
	}

	private static float halton(int index, int base) {
		float result = 0.0F;
		float fraction = 1.0F;
		while (index > 0) {
			fraction /= base;
			result += fraction * (index % base);
			index /= base;
		}
		return result;
	}

	/** GameRenderer.renderLevel's final (jittered, bobbed) projection, as uploaded for the world. */
	public static void captureLevelProjection(Matrix4f projection) {
		if (temporalFrame && worldTarget != null) {
			float ndcX = 2.0F * jitterX / worldTarget.width;
			float ndcY = (MetalFXConfig.flipY ? -2.0F : 2.0F) * jitterY / worldTarget.height;
			levelProjection.set(new Matrix4f().translation(-ndcX, -ndcY, 0.0F).mul(projection));
			levelProjectionCaptured = true;
		} else if (captureFrame) {
			levelProjection.set(projection);
			levelProjectionCaptured = true;
		}
	}

	/** Jitters the first-person hand projection the same way as the world (so the upscaler sees consistent jitter). */
	public static Matrix4f jitterHandProjection(Matrix4f projection) {
		if (temporalFrame && worldTarget != null) {
			float ndcX = 2.0F * jitterX / worldTarget.width;
			float ndcY = (MetalFXConfig.flipY ? -2.0F : 2.0F) * jitterY / worldTarget.height;
			return new Matrix4f().translation(ndcX, ndcY, 0.0F).mul(projection);
		}
		return projection;
	}

	public static boolean isTemporalFrame() {
		return temporalFrame;
	}

	/** Called right before render3dHud clears {@code texture} for the hand pass. */
	public static void beforeHandDepthClear(GpuTexture texture) {
		GpuTexture worldDepth = captureWorldDepth();
		if (worldDepth == null) {
			packHandFrame = false;
			return;
		}
		if (packHandFrame) {
			// The pack already drew the hand into the world depth (sceneDepth holds the depth from before it).
			packHandFrame = false;
			packHandDepth = ensureDepthCopy(packHandDepth, "MetalFX Pack Hand Depth", worldDepth);
			RenderSystem.getDevice()
				.createCommandEncoder()
				.copyTextureToTexture(worldDepth, packHandDepth, 0, 0, 0, 0, 0, worldDepth.getWidth(0), worldDepth.getHeight(0));
			handDepth = packHandDepth;
			if (packAfterHandCaptured) {
				packAfterHandCaptured = false;
				mergePackDepth();
			}
			mergeDistantDepth();
			return;
		}
		RenderSystem.getDevice()
			.createCommandEncoder()
			.copyTextureToTexture(worldDepth, sceneDepth, 0, 0, 0, 0, 0, sceneDepth.getWidth(0), sceneDepth.getHeight(0));
		handDepth = texture;
		mergeDistantDepth();
	}

	/**
	 * Distant Horizons terrain drawn by a shader pack has its own depth buffer and leaves the world depth at sky: without
	 * this it gets camera rotation but no camera movement in its motion vectors (smears while flying).
	 */
	private static void mergeDistantDepth() {
		GpuTexture distant = VitrailCompat.distantDepth(distantPair);
		if (distant == null || vulkanDevice == null || !(sceneDepth instanceof VulkanGpuTexture vkScene) || !(distant instanceof VulkanGpuTexture vkDistant)) {
			return;
		}
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		if (!(encoder instanceof FrontendCommandEncoder frontend) || !(frontend.backend() instanceof VulkanCommandEncoder vkEncoder)) {
			return;
		}
		var device = vulkanDevice.vkDevice();
		long scene = VulkanMetalInterop.mtlTexture(device, vkScene.vkImage());
		long far = VulkanMetalInterop.mtlTexture(device, vkDistant.vkImage());
		if (scene == 0L || far == 0L) {
			return;
		}
		long waitValue = timelineValue + 1;
		long doneValue = timelineValue + 2;
		int result = NativeBridge.mergeDistantDepth(scene, far, distantPair.x, distantPair.y, sharedEvent, waitValue, doneValue);
		if (result < 0) {
			logFailure(NativeBridge.lastError());
			return;
		}
		timelineValue = doneValue;
		vkEncoder.signalSemaphore(timelineSemaphore, waitValue, ALL_COMMANDS);
		vkEncoder.waitSemaphore(timelineSemaphore, doneValue, ALL_COMMANDS);
		if (result == 0 && !loggedDistantFailure) {
			loggedDistantFailure = true;
			MetalFXMod.LOGGER.warn("Could not merge the Distant Horizons depth: {}", NativeBridge.lastError());
		} else if (result == 1 && !loggedDistantMerge) {
			loggedDistantMerge = true;
			MetalFXMod.LOGGER.info("Merging the shader pack's Distant Horizons depth (far = {} * world + {})", distantPair.x, distantPair.y);
		}
	}

	/** Called by Vitrail right before it draws the first-person hand into the world (its replacement of the hand pass). */
	public static void beforePackHand() {
		GpuTexture worldDepth = captureWorldDepth();
		if (worldDepth == null || packHandFrame) {
			return;
		}
		RenderSystem.getDevice()
			.createCommandEncoder()
			.copyTextureToTexture(worldDepth, sceneDepth, 0, 0, 0, 0, 0, sceneDepth.getWidth(0), sceneDepth.getHeight(0));
		packHandFrame = true;
	}

	/** Called by Vitrail right after it drew the first-person hand into the world. */
	public static void afterPackHand() {
		GpuTexture worldDepth = captureWorldDepth();
		if (worldDepth == null || !packHandFrame) {
			return;
		}
		packAfterHandDepth = ensureDepthCopy(packAfterHandDepth, "MetalFX Pack After-Hand Depth", worldDepth);
		RenderSystem.getDevice()
			.createCommandEncoder()
			.copyTextureToTexture(worldDepth, packAfterHandDepth, 0, 0, 0, 0, 0, worldDepth.getWidth(0), worldDepth.getHeight(0));
		packAfterHandCaptured = true;
	}

	/** Called by Vitrail right before it draws the translucent half of the hand. */
	public static void beforePackTranslucentHand() {
		GpuTexture worldDepth = captureWorldDepth();
		if (worldDepth == null || !packHandFrame) {
			return;
		}
		packPreTlHandDepth = ensureDepthCopy(packPreTlHandDepth, "MetalFX Pack Pre-Translucent-Hand Depth", worldDepth);
		RenderSystem.getDevice()
			.createCommandEncoder()
			.copyTextureToTexture(worldDepth, packPreTlHandDepth, 0, 0, 0, 0, 0, worldDepth.getWidth(0), worldDepth.getHeight(0));
		packPreTlCaptured = true;
		packPostTlCaptured = false;
	}

	/** Called by Vitrail right after it drew the translucent half of the hand. */
	public static void afterPackTranslucentHand() {
		GpuTexture worldDepth = captureWorldDepth();
		if (worldDepth == null || !packHandFrame || !packPreTlCaptured) {
			return;
		}
		packPostTlHandDepth = ensureDepthCopy(packPostTlHandDepth, "MetalFX Pack Post-Translucent-Hand Depth", worldDepth);
		RenderSystem.getDevice()
			.createCommandEncoder()
			.copyTextureToTexture(worldDepth, packPostTlHandDepth, 0, 0, 0, 0, 0, worldDepth.getWidth(0), worldDepth.getHeight(0));
		packPostTlCaptured = true;
	}

	/**
	 * Packs draw water, the block outline and more after the hand. Without this they would only be in the hand depth and
	 * count as hand (no motion: they smear). Puts everything but the hand into {@link #sceneDepth}.
	 */
	private static void mergePackDepth() {
		if (vulkanDevice == null || packAfterHandDepth == null || !(sceneDepth instanceof VulkanGpuTexture vkPre)
			|| !(packAfterHandDepth instanceof VulkanGpuTexture vkPost) || !(packHandDepth instanceof VulkanGpuTexture vkFinal)) {
			return;
		}
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		if (!(encoder instanceof FrontendCommandEncoder frontend) || !(frontend.backend() instanceof VulkanCommandEncoder vkEncoder)) {
			return;
		}
		var device = vulkanDevice.vkDevice();
		long pre = VulkanMetalInterop.mtlTexture(device, vkPre.vkImage());
		long post = VulkanMetalInterop.mtlTexture(device, vkPost.vkImage());
		long fin = VulkanMetalInterop.mtlTexture(device, vkFinal.vkImage());
		if (pre == 0L || post == 0L || fin == 0L) {
			return;
		}
		long waitValue = timelineValue + 1;
		long doneValue = timelineValue + 2;
		long preTl = 0L, postTl = 0L;
		if (packPreTlCaptured && packPostTlCaptured && packPreTlHandDepth instanceof VulkanGpuTexture vkPreTl
			&& packPostTlHandDepth instanceof VulkanGpuTexture vkPostTl) {
			preTl = VulkanMetalInterop.mtlTexture(device, vkPreTl.vkImage());
			postTl = VulkanMetalInterop.mtlTexture(device, vkPostTl.vkImage());
			if (preTl == 0L || postTl == 0L) {
				preTl = postTl = 0L;
			}
		}
		packPreTlCaptured = packPostTlCaptured = false;
		int result = NativeBridge.mergePackDepth(pre, post, fin, preTl, postTl, sharedEvent, waitValue, doneValue);
		if (result < 0) {
			logFailure(NativeBridge.lastError());
			return;
		}
		timelineValue = doneValue;
		vkEncoder.signalSemaphore(timelineSemaphore, waitValue, ALL_COMMANDS);
		vkEncoder.waitSemaphore(timelineSemaphore, doneValue, ALL_COMMANDS);
		if (result == 0 && !loggedMergeFailure) {
			loggedMergeFailure = true;
			MetalFXMod.LOGGER.warn("Could not merge the shader pack's depth: {}", NativeBridge.lastError());
		}
	}

	private static GpuTexture ensureDepthCopy(@Nullable GpuTexture copy, String name, GpuTexture source) {
		if (copy != null && copy.getWidth(0) == source.getWidth(0) && copy.getHeight(0) == source.getHeight(0) && copy.getFormat() == source.getFormat()) {
			return copy;
		}
		if (copy != null) {
			copy.close();
		}
		return RenderSystem.getDevice().createTexture(
			name, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING,
			source.getFormat(), source.getWidth(0), source.getHeight(0), 1, 1
		);
	}

	/** The world's depth texture, if this frame captures depth and it matches {@link #sceneDepth}. */
	@Nullable
	private static GpuTexture captureWorldDepth() {
		RenderTarget scene = inWorldPhase ? worldTarget : realMainTarget;
		if (!captureFrame || scene == null || sceneDepth == null) {
			return null;
		}
		GpuTexture worldDepth = scene.getDepthTexture();
		if (worldDepth == null || worldDepth.getWidth(0) != sceneDepth.getWidth(0) || worldDepth.getHeight(0) != sceneDepth.getHeight(0)) {
			return null;
		}
		return worldDepth;
	}

	private static void ensureSceneDepth(int width, int height) {
		if (sceneDepth != null && sceneDepth.getWidth(0) == width && sceneDepth.getHeight(0) == height) {
			return;
		}
		if (sceneDepth != null) {
			sceneDepth.close();
		}
		sceneDepth = RenderSystem.getDevice()
			.createTexture(
				"MetalFX Scene Depth", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.D32_FLOAT, width, height, 1, 1
			);
	}

	private static boolean upscaleTemporal(RenderTarget source, RenderTarget destination) {
		GpuTexture src = source.getColorTexture();
		GpuTexture dst = destination.getColorTexture();
		if (!levelProjectionCaptured || handDepth == null || sceneDepth == null || vulkanDevice == null
			|| !(src instanceof VulkanGpuTexture vkSrc) || !(dst instanceof VulkanGpuTexture vkDst)
			|| !(sceneDepth instanceof VulkanGpuTexture vkDepth) || !(handDepth instanceof VulkanGpuTexture vkHand)) {
			// Happens on frames that skip the level pass (loading screens, shader pack reloads): not a MetalFX failure.
			if (!loggedMissingInputs) {
				loggedMissingInputs = true;
				MetalFXMod.LOGGER.info("Temporal inputs missing this frame (projection {}, hand depth {}); using bilinear for such frames", levelProjectionCaptured, handDepth != null);
			}
			return false;
		}
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		if (!(encoder instanceof FrontendCommandEncoder frontend) || !(frontend.backend() instanceof VulkanCommandEncoder vkEncoder)) {
			return false;
		}
		var device = vulkanDevice.vkDevice();
		long inTexture = VulkanMetalInterop.mtlTexture(device, vkSrc.vkImage());
		long outTexture = VulkanMetalInterop.mtlTexture(device, vkDst.vkImage());
		long depthTexture = VulkanMetalInterop.mtlTexture(device, vkDepth.vkImage());
		long handTexture = VulkanMetalInterop.mtlTexture(device, vkHand.vkImage());
		if (inTexture == 0L || outTexture == 0L || depthTexture == 0L || handTexture == 0L) {
			logFailure("could not export MTLTextures from Vulkan images");
			return false;
		}

		CameraRenderState camera = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
		Matrix4f viewProj = new Matrix4f(levelProjection).mul(camera.viewRotationMatrix);
		Vec3 cameraPos = camera.pos;
		ClientLevel level = Minecraft.getInstance().level;
		boolean reset = !temporalHistoryValid
			|| level != prevLevel
			|| source.width != prevWidth
			|| source.height != prevHeight
			|| cameraPos.distanceToSqr(prevCameraPos) > 64.0;
		if (reset) {
			prevViewProj.set(viewProj);
			prevCameraPos = cameraPos;
		}

		writeMatrix(params, 0, new Matrix4f(viewProj).invert());
		writeMatrix(params, 64, prevViewProj);
		params.set(ValueLayout.JAVA_FLOAT, 128, (float)(cameraPos.x - prevCameraPos.x));
		params.set(ValueLayout.JAVA_FLOAT, 132, (float)(cameraPos.y - prevCameraPos.y));
		params.set(ValueLayout.JAVA_FLOAT, 136, (float)(cameraPos.z - prevCameraPos.z));
		params.set(ValueLayout.JAVA_FLOAT, 140, 0.0F);
		params.set(ValueLayout.JAVA_FLOAT, 144, MetalFXConfig.jitterSignX * jitterX);
		params.set(ValueLayout.JAVA_FLOAT, 148, MetalFXConfig.jitterSignY * jitterY);
		params.set(ValueLayout.JAVA_INT, 152, reset ? 1 : 0);
		params.set(ValueLayout.JAVA_INT, 156, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1 : 0);
		params.set(ValueLayout.JAVA_INT, 160, MetalFXConfig.flipY ? 1 : 0);
		params.set(ValueLayout.JAVA_INT, 164, MetalFXConfig.debugMotion ? 1 : 0);
		params.set(ValueLayout.JAVA_FLOAT, 168, MetalFXConfig.motionScaleX);
		params.set(ValueLayout.JAVA_FLOAT, 172, MetalFXConfig.motionScaleY);
		params.set(ValueLayout.JAVA_INT, 176, MetalFXConfig.debugSkipScaler ? 1 : 0);
		params.set(ValueLayout.JAVA_FLOAT, 180, MetalFXConfig.debugMotion ? 0.0F : MetalFXConfig.sharpness);
		params.set(ValueLayout.JAVA_INT, 184, MetalFXConfig.upscaler == MetalFXConfig.Upscaler.FSR ? 4 : 0);
		params.set(ValueLayout.JAVA_FLOAT, 240, levelProjection.m22());
		params.set(ValueLayout.JAVA_FLOAT, 244, levelProjection.m32());
		params.set(ValueLayout.JAVA_FLOAT, 248, 1.0F / levelProjection.m00());
		params.set(ValueLayout.JAVA_FLOAT, 252, 1.0F / levelProjection.m11());
		prevPlayerPos = writePlayerBox(params, 272, cameraPos, reset ? null : prevPlayerPos);
		long now = System.nanoTime();
		params.set(ValueLayout.JAVA_FLOAT, 256, lastTemporalNanos == 0L ? 16.7F : (now - lastTemporalNanos) / 1.0e6F);
		lastTemporalNanos = now;

		long waitValue = timelineValue + 1;
		long doneValue = timelineValue + 2;
		int result = NativeBridge.upscaleTemporal(inTexture, depthTexture, handTexture, outTexture, sharedEvent, waitValue, doneValue, params.address());
		if (result < 0) {
			logFailure(NativeBridge.lastError());
			return false;
		}
		timelineValue = doneValue;
		vkEncoder.signalSemaphore(timelineSemaphore, waitValue, ALL_COMMANDS);
		vkEncoder.waitSemaphore(timelineSemaphore, doneValue, ALL_COMMANDS);
		if (result == 0) {
			logFailure(NativeBridge.lastError());
			return false;
		}
		prevViewProj.set(viewProj);
		prevCameraPos = cameraPos;
		prevLevel = level;
		prevWidth = source.width;
		prevHeight = source.height;
		temporalHistoryValid = true;
		return true;
	}

	// ------------------------------------------------------------------ F3 axis crosshair

	@Nullable
	private static CameraRenderState deferredCrosshairCamera;
	private static int deferredCrosshairScale;
	@Nullable
	private static GpuTexture crosshairDepth;
	private static com.mojang.renderpearl.api.textures.@Nullable GpuTextureView crosshairDepthView;
	private static net.minecraft.client.renderer.@Nullable ProjectionMatrixBuffer crosshairProjection;

	/**
	 * While the world renders at a lower resolution, the F3 axis crosshair (a screen-space gizmo drawn into the world
	 * image) would be jittered, upscaled with the scene's motion vectors and interpolated by frame generation: it is
	 * drawn after upscaling instead, at the output resolution. Returns true if it was deferred.
	 */
	public static boolean deferDebugCrosshair(CameraRenderState camera, int guiScale) {
		if (!inWorldPhase) {
			return false;
		}
		deferredCrosshairCamera = camera;
		deferredCrosshairScale = guiScale;
		return true;
	}

	public static void drawDeferredDebugCrosshair(net.minecraft.client.renderer.DebugCrosshairRenderer renderer, net.minecraft.client.renderer.Projection hud) {
		// Drawn at most once per frame, and only for a frame that deferred it.
		CameraRenderState camera = deferredCrosshairCamera;
		deferredCrosshairCamera = null;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		if (camera == null || main == null || main.getColorTextureView() == null) {
			return;
		}
		var device = RenderSystem.getDevice();
		if (crosshairDepth == null || crosshairDepth.getWidth(0) != main.width || crosshairDepth.getHeight(0) != main.height) {
			if (crosshairDepthView != null) {
				crosshairDepthView.close();
			}
			if (crosshairDepth != null) {
				crosshairDepth.close();
			}
			crosshairDepth = device.createTexture("MetalFX Debug Crosshair Depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, GpuFormat.D32_FLOAT, main.width, main.height, 1, 1);
			crosshairDepthView = device.createTextureView(crosshairDepth);
		}
		if (crosshairProjection == null) {
			crosshairProjection = new net.minecraft.client.renderer.ProjectionMatrixBuffer("MetalFX debug crosshair");
		}
		device.createCommandEncoder().clearDepthTexture(crosshairDepth, 0.0);
		RenderSystem.backupProjectionMatrix();
		RenderSystem.setProjectionMatrix(crosshairProjection.getBuffer(hud), com.mojang.blaze3d.ProjectionType.PERSPECTIVE);
		renderer.render(camera, deferredCrosshairScale, main.getColorTextureView(), crosshairDepthView);
		RenderSystem.restoreProjectionMatrix();
	}

	// ------------------------------------------------------------------ frame generation

	/**
	 * Half size (fractions of the image) of a centred box around the F3 axis crosshair, or zeros when it isn't drawn.
	 * It is a 3D gizmo 0.01 * guiScale long at distance 1 in the hand projection (see DebugCrosshairRenderer).
	 */
	private static float[] debugCrosshairBox(CameraRenderState camera) {
		Minecraft mc = Minecraft.getInstance();
		var state = mc.gameRenderer.gameRenderState();
		if (!state.levelRenderState.render3dCrosshair || state.guiRenderState.isHudHidden || !mc.options.getCameraType().isFirstPerson()) {
			return new float[2];
		}
		var window = state.windowRenderState;
		float tanHalf = (float)Math.tan(Math.toRadians(camera.hudFov) * 0.5);
		float ndcY = 0.01F * window.guiScale / tanHalf;
		float aspect = (float)window.width / Math.max(1, window.height);
		// Margin for the line width and the box's mask texels.
		float padX = 12.0F / Math.max(1, window.width), padY = 12.0F / Math.max(1, window.height);
		return new float[] {0.5F * ndcY / aspect * 1.2F + padX, 0.5F * ndcY * 1.2F + padY};
	}

	/**
	 * Keeps what frame generation needs from this frame: the world image (Minecraft's target before the HUD, upscaled or
	 * native), the first-person hand depth (the scene depth is already our own copy) and the camera.
	 */
	private static void captureForFrameGen(RenderTarget main) {
		GpuTexture color = main.getColorTexture();
		if (!levelProjectionCaptured || handDepth == null || sceneDepth == null || color == null) {
			fgHistoryValid = false;
			return;
		}
		fgWorld = ensureCopy(fgWorld, "MetalFX Frame Gen World", color);
		fgHand = ensureCopy(fgHand, "MetalFX Frame Gen Hand Depth", handDepth);
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		encoder.copyTextureToTexture(color, fgWorld, 0, 0, 0, 0, 0, color.getWidth(0), color.getHeight(0));
		encoder.copyTextureToTexture(handDepth, fgHand, 0, 0, 0, 0, 0, handDepth.getWidth(0), handDepth.getHeight(0));
		fgDepth = ensureCopy(fgDepth, "MetalFX Frame Gen Scene Depth", sceneDepth);
		encoder.copyTextureToTexture(sceneDepth, fgDepth, 0, 0, 0, 0, 0, sceneDepth.getWidth(0), sceneDepth.getHeight(0));

		CameraRenderState camera = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
		Matrix4f viewProj = new Matrix4f(levelProjection).mul(camera.viewRotationMatrix);
		Vec3 cameraPos = camera.pos;
		ClientLevel level = Minecraft.getInstance().level;
		int width = sceneDepth.getWidth(0), height = sceneDepth.getHeight(0);
		boolean reset = !fgHistoryValid || level != fgPrevLevel || width != fgPrevWidth || height != fgPrevHeight
			|| cameraPos.distanceToSqr(fgPrevCameraPos) > 64.0;
		if (reset) {
			fgPrevViewProj.set(viewProj);
			fgPrevCameraPos = cameraPos;
		}
		long now = System.nanoTime();
		float frameTimeMs = fgLastNanos == 0L ? 16.7F : Math.max(1.0F, Math.min(250.0F, (now - fgLastNanos) / 1.0e6F));
		fgLastNanos = now;
		// Minecraft's reversed-Z projection: view depth = m32 / (device depth + m22).
		float m22 = levelProjection.m22(), m32 = levelProjection.m32();
		writeMatrix(fgParams, 0, new Matrix4f(viewProj).invert());
		writeMatrix(fgParams, 64, fgPrevViewProj);
		fgParams.set(ValueLayout.JAVA_FLOAT, 128, (float)(cameraPos.x - fgPrevCameraPos.x));
		fgParams.set(ValueLayout.JAVA_FLOAT, 132, (float)(cameraPos.y - fgPrevCameraPos.y));
		fgParams.set(ValueLayout.JAVA_FLOAT, 136, (float)(cameraPos.z - fgPrevCameraPos.z));
		fgParams.set(ValueLayout.JAVA_FLOAT, 140, 0.0F);
		fgParams.set(ValueLayout.JAVA_INT, 144, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1 : 0);
		fgParams.set(ValueLayout.JAVA_INT, 148, MetalFXConfig.flipY ? 1 : 0);
		fgParams.set(ValueLayout.JAVA_INT, 152, reset ? 1 : 0);
		fgParams.set(ValueLayout.JAVA_INT, 156, 0);
		fgParams.set(ValueLayout.JAVA_FLOAT, 160, Math.abs(m32 / (1.0F + m22)));
		fgParams.set(ValueLayout.JAVA_FLOAT, 164, Math.abs(m22) > 1.0e-6F ? Math.abs(m32 / m22) : 100000.0F);
		fgParams.set(ValueLayout.JAVA_FLOAT, 168, 1.0F / levelProjection.m00());
		fgParams.set(ValueLayout.JAVA_FLOAT, 172, 1.0F / levelProjection.m11());
		fgParams.set(ValueLayout.JAVA_FLOAT, 176, MetalFXConfig.motionScaleX * MetalFXConfig.frameGenMotionScale);
		fgParams.set(ValueLayout.JAVA_FLOAT, 180, MetalFXConfig.motionScaleY * MetalFXConfig.frameGenMotionScale);
		fgParams.set(ValueLayout.JAVA_FLOAT, 184, frameTimeMs);
		fgParams.set(ValueLayout.JAVA_INT, 188, frameGenBackend() == MetalFXConfig.FrameGenBackend.METALFX ? 1 : 0);
		float[] cross = debugCrosshairBox(camera);
		fgParams.set(ValueLayout.JAVA_FLOAT, 192, cross[0]);
		fgParams.set(ValueLayout.JAVA_FLOAT, 196, cross[1]);
		fgParams.set(ValueLayout.JAVA_INT, 200, blurredMenuOpen() ? 1 : 0);
		fgParams.set(ValueLayout.JAVA_INT, 204, 0);
		fgPrevPlayerPos = writePlayerBox(fgParams, 208, cameraPos, reset ? null : fgPrevPlayerPos);
		fgPrevViewProj.set(viewProj);
		fgPrevCameraPos = cameraPos;
		fgPrevLevel = level;
		fgPrevWidth = width;
		fgPrevHeight = height;
		fgHistoryValid = true;
		fgCaptured = true;
	}

	/** {@code existing} if it matches {@code like}'s size and format, otherwise a new copy target shaped like it. */
	private static GpuTexture ensureCopy(@Nullable GpuTexture existing, String label, GpuTexture like) {
		if (existing != null && existing.getWidth(0) == like.getWidth(0) && existing.getHeight(0) == like.getHeight(0)
			&& existing.getFormat() == like.getFormat()) {
			return existing;
		}
		if (existing != null) {
			existing.close();
		}
		return RenderSystem.getDevice().createTexture(
			label, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, like.getFormat(), like.getWidth(0), like.getHeight(0), 1, 1
		);
	}

	/**
	 * Called where Minecraft would blit its final image to the swapchain. While the presenter is live, hands the frame to it:
	 * the final image, and (if this frame was captured) the world image, depth and camera to generate the frame between the
	 * previous one and this one.
	 */
	public static void beforeSwapchainBlit(Minecraft minecraft) {
		boolean captured = fgCaptured;
		fgCaptured = false;
		if (!presenterLive || vulkanDevice == null) {
			return;
		}
		GpuTexture mainColor = minecraft.gameRenderer.mainRenderTarget().getColorTexture();
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		if (!(mainColor instanceof VulkanGpuTexture vkMain) || !(encoder instanceof FrontendCommandEncoder frontend)
			|| !(frontend.backend() instanceof VulkanCommandEncoder vkEncoder)) {
			return;
		}
		var vk = vulkanDevice.vkDevice();
		long finalTexture = VulkanMetalInterop.mtlTexture(vk, vkMain.vkImage());
		if (finalTexture == 0L) {
			return;
		}
		long worldTexture = 0L, depthTexture = 0L, handTexture = 0L;
		if (captured && fgWorld instanceof VulkanGpuTexture vkWorld && fgHand instanceof VulkanGpuTexture vkHand
			&& fgDepth instanceof VulkanGpuTexture vkDepth && fgWorld.getWidth(0) == mainColor.getWidth(0)
			&& fgWorld.getHeight(0) == mainColor.getHeight(0)) {
			worldTexture = VulkanMetalInterop.mtlTexture(vk, vkWorld.vkImage());
			depthTexture = VulkanMetalInterop.mtlTexture(vk, vkDepth.vkImage());
			handTexture = VulkanMetalInterop.mtlTexture(vk, vkHand.vkImage());
			if (depthTexture == 0L || handTexture == 0L) {
				worldTexture = 0L;
			}
		}
		if (worldTexture == 0L) {
			fgHistoryValid = false;
		}
		fgParams.set(ValueLayout.JAVA_INT, 156, debugShowInterpolated ? 1 : 0);
		long waitValue = timelineValue + 1;
		long doneValue = timelineValue + 2;
		int result = NativeBridge.fgSubmit(
			worldTexture, finalTexture, depthTexture, handTexture, sharedEvent, waitValue, doneValue, worldTexture != 0L ? fgParams.address() : 0L
		);
		if (result == -2) {
			logFrameGenFailure(NativeBridge.lastError());
			return;
		}
		timelineValue = doneValue;
		vkEncoder.signalSemaphore(timelineSemaphore, waitValue, ALL_COMMANDS);
		vkEncoder.waitSemaphore(timelineSemaphore, doneValue, ALL_COMMANDS);
		if (result < 0) {
			logFrameGenFailure(NativeBridge.lastError());
		}
	}

	/**
	 * Called after Minecraft submitted the frame, where it would present: waits until the presenter can take another frame.
	 * This is what paces the game while frame generation runs (Minecraft no longer waits for VSync).
	 */
	public static void beforePresent() {
		if (presenterLive) {
			NativeBridge.fgReserve();
		}
	}

	private static void logFrameGenFailure(String message) {
		fgFailures++;
		if (fgFailures <= MAX_LOGGED_FAILURES) {
			MetalFXMod.LOGGER.warn("Frame generation failed this frame: {}", message);
		}
	}

	private static void writeMatrix(MemorySegment segment, long offset, Matrix4f matrix) {
		matrix.get(matrixScratch);
		for (int i = 0; i < 16; i++) {
			segment.set(ValueLayout.JAVA_FLOAT, offset + i * 4L, matrixScratch[i]);
		}
	}

	// ------------------------------------------------------------------ upscalers

	private static boolean upscaleMetalFx(RenderTarget source, RenderTarget destination) {
		GpuTexture src = source.getColorTexture();
		GpuTexture dst = destination.getColorTexture();
		if (!(src instanceof VulkanGpuTexture vkSrc) || !(dst instanceof VulkanGpuTexture vkDst) || vulkanDevice == null) {
			return false;
		}
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		if (!(encoder instanceof FrontendCommandEncoder frontend) || !(frontend.backend() instanceof VulkanCommandEncoder vkEncoder)) {
			return false;
		}
		long inTexture = VulkanMetalInterop.mtlTexture(vulkanDevice.vkDevice(), vkSrc.vkImage());
		long outTexture = VulkanMetalInterop.mtlTexture(vulkanDevice.vkDevice(), vkDst.vkImage());
		if (inTexture == 0L || outTexture == 0L) {
			logFailure("could not export MTLTextures from Vulkan images");
			return false;
		}

		// GPU timeline: [Vulkan world pass] -signal(wait)-> [Metal: MetalFX + blit] -signal(done)-> [Vulkan GUI pass]
		long waitValue = timelineValue + 1;
		long doneValue = timelineValue + 2;
		int result = NativeBridge.upscale(inTexture, outTexture, sharedEvent, waitValue, doneValue, MetalFXConfig.sharpness);
		if (result < 0) {
			logFailure(NativeBridge.lastError());
			return false;
		}
		timelineValue = doneValue;
		vkEncoder.signalSemaphore(timelineSemaphore, waitValue, ALL_COMMANDS);
		vkEncoder.waitSemaphore(timelineSemaphore, doneValue, ALL_COMMANDS);
		if (result == 0) {
			logFailure(NativeBridge.lastError());
			return false;
		}
		return true;
	}

	private static void upscaleBilinear(RenderTarget source, RenderTarget destination) {
		try (RenderPass pass = RenderSystem.getDevice()
			.createCommandEncoder()
			.createRenderPass(() -> "MetalFX bilinear upscale", destination.getColorTextureView(), Optional.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("InSampler", source.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.draw(3, 1, 0, 0);
		}
	}

	private static void checkGpuErrors() {
		if (!metalFxReady) {
			return;
		}
		int errors = NativeBridge.gpuErrorCount();
		if (errors != seenGpuErrors) {
			seenGpuErrors = errors;
			logFailure(NativeBridge.lastError());
		}
	}

	private static void logFailure(String message) {
		loggedFailures++;
		if (loggedFailures <= MAX_LOGGED_FAILURES) {
			MetalFXMod.LOGGER.warn("MetalFX upscale failed, using bilinear this frame: {}", message);
		}
		if (loggedFailures == 30) {
			MetalFXMod.LOGGER.error("MetalFX failing repeatedly with {}; using bilinear until the upscaler or render scale changes", currentSetting());
			metalFxReady = false;
			failedSetting = currentSetting();
		}
	}
}
