package dev.mcupscaler;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkDevice;

/**
 * Renders the world into a reduced-resolution target and upscales it (DLSS or FSR on Windows, MetalFX or FSR on macOS) into
 * Minecraft's main target right before the GUI is drawn, so the HUD stays at native resolution.
 * <p>
 * While the world is being drawn ("world phase"), every lookup of the main render target is redirected to
 * {@link #worldTarget}. This covers vanilla and any mod that goes through {@code GameRenderer.mainRenderTarget()}
 * (Sodium, Distant Horizons, ...). On Windows, DLSS/FSR and the passes that prepare their inputs are recorded into the
 * command buffer Minecraft is recording, between its own commands; on macOS they run on a Metal queue in between, ordered
 * with a shared semaphore (see {@link MetalBackend}).
 * <p>
 * Frame generation needs the same motion vectors and depth. Without upscaling they are made from the native-resolution
 * world (no redirect, no jitter) while it runs.
 */
public final class WorldUpscaler {
	private static final int MAX_LOGGED_FAILURES = 5;
	/** Failed frames after which the upscaler is switched off until its settings change. */
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
	private static boolean dlssSupported;
	/** AMD FSR loaded and the compute passes are loaded (Windows), or the Metal bridge is up (macOS). */
	private static boolean fsrSupported;
	/** macOS: MetalFX Temporal and MetalFX Spatial can run. */
	private static boolean metalFxSupported, metalFxSpatialSupported;
	/** The upscaler given up on after repeated failures (until its settings change), or null. */
	private static UpscalerConfig.@Nullable Upscaler failedUpscaler;
	/** The compute passes that make motion vectors and depth are loaded (stays true when an upscaler itself is given up on). */
	private static boolean passesReady;
	private static boolean dlssFrameGenAvailable;
	/** RTX 40 series or newer (Ada, Blackwell): runs DLSS's second-generation transformer (preset M) at full speed. */
	private static boolean adaOrNewer;
	private static boolean otherBackend;
	/** Why DLSS can't run. */
	private static String unavailableReason = "";
	/** Why FSR can't run. */
	private static String fsrUnavailableReason = "";
	/** Why MetalFX can't run. */
	private static String metalFxUnavailableReason = "needs a Mac";
	private static int loggedFailures;
	/** Setting that the upscaler was disabled for after repeated failures; another choice gets a fresh try. */
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
	static final HandMotion handMotion = new HandMotion();
	/** Vitrail is drawing the first-person hand (a shader pack is on): the projection it uploads is the hand's. */
	private static boolean packHandDrawing;
	private static final MemorySegment frame = Arena.global().allocate(DlssNative.FRAME_SIZE, 16);
	/** The shader pack's vignette, drawn after upscaling (VitrailCompat#vignette). */
	private static final float[] vignette = new float[3];
	private static boolean vignetteFailureLogged;
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

	// GameRenderer's GlobalSettingsUniform.update with this frame's arguments but the screen size, so ScreenSize can be
	// swapped to the world resolution during the world phase.
	@Nullable
	private static ScreenSizeUpdate uniformUpdate;

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
		VulkanDevice vk = McCompat.vulkanDevice(device);
		if (vk == null) {
			UpscalerMod.LOGGER.warn("Graphics backend is not Vulkan; upscaling is off");
			state = State.UNAVAILABLE;
			otherBackend = true;
			unavailableReason = fsrUnavailableReason = metalFxUnavailableReason = "needs the Vulkan graphics backend";
			return;
		}
		state = State.READY;
		if (Platform.MAC) {
			unavailableReason = "needs Windows and an NVIDIA RTX card";
			initMetal(vk);
			return;
		}
		metalFxUnavailableReason = "needs a Mac";
		if (Boolean.getBoolean("mcupscaler.skipNgx")) {
			unavailableReason = fsrUnavailableReason = "disabled by -Dmcupscaler.skipNgx";
			return;
		}
		if (!DlssNative.load()) {
			unavailableReason = fsrUnavailableReason = "native bridge failed to load";
			return;
		}
		try {
			initDlss(vk);
		} catch (RuntimeException e) {
			unavailableReason = fsrUnavailableReason = e.toString();
			UpscalerMod.LOGGER.error("DLSS/FSR initialisation failed", e);
		}
	}

	private static void initDlss(VulkanDevice vk) {
		VkDevice vkDevice = vk.vkDevice();
		long gipa = VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr");
		long gdpa = vkDevice.getCapabilities().vkGetDeviceProcAddr;
		long start = System.nanoTime();
		int result = DlssNative.init(vk.instance().vkInstance().address(), vkDevice.getPhysicalDevice().address(), vkDevice.address(),
			gipa, gdpa, UpscalerConfig.ngxLogging || Boolean.getBoolean("mcupscaler.ngxLogging"));
		if (result < 0) {
			unavailableReason = fsrUnavailableReason = DlssNative.lastError();
			UpscalerMod.LOGGER.warn("DLSS and FSR unavailable on {}: {}", vk.getDeviceInfo().name(), unavailableReason);
			return;
		}
		// NGX failing (not an NVIDIA RTX card) leaves its reason as the last error; FSR doesn't need NGX.
		boolean dlss = (result & DlssNative.INIT_SUPER_RESOLUTION) != 0;
		boolean fsr = (result & DlssNative.INIT_FSR) != 0;
		String gpu = vk.getDeviceInfo().name();
		if (!dlss) {
			String error = DlssNative.lastError();
			UpscalerMod.LOGGER.warn("DLSS unavailable on {}: {}", gpu, error);
			unavailableReason = !gpu.contains("NVIDIA") || !gpu.contains("RTX") ? "needs an NVIDIA RTX card" : error;
		}
		if (!fsr) {
			fsrUnavailableReason = "FSR failed to load";
			UpscalerMod.LOGGER.warn("AMD FSR unavailable: {}", fsrUnavailableReason);
		}
		if (!dlss && !fsr) {
			return;
		}
		if (!DlssNative.loadShaders(Shaders.compileAll())) {
			unavailableReason = fsrUnavailableReason = DlssNative.lastError();
			UpscalerMod.LOGGER.error("Compute passes failed to load: {}", unavailableReason);
			return;
		}
		passesReady = true;
		dlssSupported = dlss;
		fsrSupported = fsr;
		dlssFrameGenAvailable = dlss && (result & DlssNative.INIT_FRAME_GENERATION) != 0;
		// DLSS Frame Generation needs Ada or newer; without it (e.g. Windows' hardware-accelerated GPU scheduling off), the name.
		adaOrNewer = dlssFrameGenAvailable || NEWER_RTX.matcher(gpu).find();
		pickSupportedDefaults();
		UpscalerMod.LOGGER.info("Upscalers ready on {} in {} ms (DLSS Super Resolution: {}, DLSS Frame Generation: {}, AMD FSR: {})",
			vk.getDeviceInfo().name(), (System.nanoTime() - start) / 1_000_000, dlss ? "yes" : "no",
			dlssFrameGenAvailable ? "yes, up to " + (DlssNative.frameGenMaxMultiFrame() + 1) + "x" : "no", fsr ? "yes" : "no");
	}

	private static void initMetal(VulkanDevice vk) {
		String failure;
		try {
			failure = MetalBackend.init(vk);
		} catch (RuntimeException | LinkageError e) {
			UpscalerMod.LOGGER.error("MetalFX initialisation failed", e);
			failure = e.toString();
		}
		if (failure != null) {
			fsrUnavailableReason = metalFxUnavailableReason = failure;
			return;
		}
		passesReady = true;
		fsrSupported = metalFxSpatialSupported = true;
		metalFxSupported = MetalBackend.temporalSupported();
		if (!metalFxSupported) {
			metalFxUnavailableReason = "not supported on this Mac";
		}
		UpscalerConfig.metalFxMinScale = MetalBackend.temporalMinScale();
		pickSupportedDefaults();
	}

	private static final java.util.regex.Pattern NEWER_RTX = java.util.regex.Pattern.compile("RTX\\s*(PRO\\b|[4-9]0\\d0|\\d+\\s*Ada)");

	/**
	 * Replaces an upscaler or frame generator this GPU can't run with the best one it can (DLSS by default on an AMD or
	 * Intel card, DLSS Frame Generation on an RTX 30 series, MetalFX on an older Mac), so the defaults work anywhere.
	 */
	private static void pickSupportedDefaults() {
		boolean changed = false;
		UpscalerConfig.Upscaler upscaler = UpscalerConfig.upscaler;
		if ((upscaler == UpscalerConfig.Upscaler.DLSS || upscaler == UpscalerConfig.Upscaler.METALFX) && !supported(upscaler) && fsrSupported) {
			UpscalerMod.LOGGER.info("{} can't run here; using AMD FSR 3.1", upscaler.displayName());
			UpscalerConfig.upscaler = UpscalerConfig.Upscaler.FSR;
			changed = true;
		}
		UpscalerConfig.FrameGeneration frameGen = UpscalerConfig.frameGeneration;
		if ((frameGen == UpscalerConfig.FrameGeneration.DLSS || frameGen == UpscalerConfig.FrameGeneration.METALFX) && !frameGenSupported(frameGen)) {
			UpscalerConfig.frameGeneration = frameGenSupported(UpscalerConfig.FrameGeneration.FSR) ? UpscalerConfig.FrameGeneration.FSR
				: UpscalerConfig.FrameGeneration.OFF;
			UpscalerMod.LOGGER.info("{} can't run here; frame generation set to {}", frameGen.displayName(),
				UpscalerConfig.frameGeneration.displayName());
			changed = true;
		}
		if (changed) {
			UpscalerConfig.save();
		}
	}

	/** The DLSS preset to use: Auto is M on RTX 40 series and newer, K on older cards. */
	public static UpscalerConfig.Preset resolvedPreset() {
		return UpscalerConfig.preset != UpscalerConfig.Preset.AUTO ? UpscalerConfig.preset : autoPreset();
	}

	/** What Auto means on this GPU. */
	public static UpscalerConfig.Preset autoPreset() {
		ensureInit();
		return adaOrNewer ? UpscalerConfig.Preset.M : UpscalerConfig.Preset.K;
	}

	/** Preset for status lines: "Auto (M)", or the chosen one. */
	public static String presetName() {
		return UpscalerConfig.preset == UpscalerConfig.Preset.AUTO ? "Auto (" + resolvedPreset().name() + ")" : UpscalerConfig.preset.displayName();
	}

	/** Called right before Minecraft destroys its Vulkan device (game exit). */
	public static void shutdown() {
		if (state == State.READY) {
			if (Platform.MAC) {
				MetalBackend.shutdown();
			} else {
				DlssNative.shutdown();
			}
			dlssSupported = false;
			fsrSupported = metalFxSupported = metalFxSpatialSupported = false;
			passesReady = false;
			dlssFrameGenAvailable = false;
			state = State.UNAVAILABLE;
			unavailableReason = fsrUnavailableReason = metalFxUnavailableReason = "shut down";
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

	/** Why an upscaler can't run. */
	public static String unavailableReason(UpscalerConfig.Upscaler upscaler) {
		return switch (upscaler) {
			case DLSS -> unavailableReason;
			case FSR -> fsrUnavailableReason;
			case METALFX, METALFX_SPATIAL -> metalFxUnavailableReason;
			case BILINEAR -> "";
		};
	}

	/** Why a frame generator can't run. */
	public static String frameGenUnavailableReason(UpscalerConfig.FrameGeneration backend) {
		if (Platform.MAC) {
			return !fsrSupported ? fsrUnavailableReason : !MetalBackend.frameGenSupported() ? "needs macOS 14 or newer"
				: backend == UpscalerConfig.FrameGeneration.METALFX && !MetalBackend.metalFxFrameGenSupported() ? "needs macOS 26 or newer" : "";
		}
		if (backend == UpscalerConfig.FrameGeneration.FSR) {
			return !fsrSupported ? fsrUnavailableReason : "";
		}
		return !dlssSupported ? unavailableReason : !dlssFrameGenAvailable ? "needs an RTX 40 or 50 series card" : "";
	}

	/** Upscaling (or DLAA) runs: the world renders into {@link #worldTarget}. */
	public static boolean isActive() {
		ensureInit();
		if (state != State.READY || !UpscalerConfig.enabled) {
			return false;
		}
		// DLAA (DLSS or FSR at 100%) still anti-aliases; a plain stretch at 100% would do nothing.
		return UpscalerConfig.renderScale() < 0.999F || isTemporalUpscalerReady();
	}

	/** The selected upscaler is temporal (DLSS, FSR, MetalFX Temporal) and can run. */
	private static boolean isTemporalUpscalerReady() {
		return UpscalerConfig.upscaler.temporal() && isUpscalerReady(UpscalerConfig.upscaler);
	}

	private static String currentSetting() {
		return UpscalerConfig.upscaler + "@" + UpscalerConfig.quality + "/" + UpscalerConfig.renderScale() + "/" + UpscalerConfig.preset;
	}

	public static boolean isUpscalerReady(UpscalerConfig.Upscaler upscaler) {
		ensureInit();
		if (failedSetting != null && !failedSetting.equals(currentSetting())) {
			// The user picked another mode since the upscaler was disabled: try again.
			failedSetting = null;
			failedUpscaler = null;
			loggedFailures = 0;
		}
		return supported(upscaler) && upscaler != failedUpscaler;
	}

	/** The upscaler can run on this machine at all (settings screen). */
	public static boolean isUpscalerSupported(UpscalerConfig.Upscaler upscaler) {
		ensureInit();
		return supported(upscaler);
	}

	private static boolean supported(UpscalerConfig.Upscaler upscaler) {
		return switch (upscaler) {
			case DLSS -> dlssSupported;
			case FSR -> fsrSupported;
			case METALFX -> metalFxSupported;
			case METALFX_SPATIAL -> metalFxSpatialSupported;
			case BILINEAR -> true;
		};
	}

	/** The selected temporal upscaler was set up and works, without setting it up (safe off the render thread). */
	public static boolean isTemporalKnownReady() {
		UpscalerConfig.Upscaler upscaler = UpscalerConfig.upscaler;
		return upscaler.temporal() && supported(upscaler) && upscaler != failedUpscaler;
	}

	/** macOS frame generation is set up for the selected backend, without setting it up (safe off the render thread). */
	public static boolean isMetalFrameGenKnownReady() {
		UpscalerConfig.FrameGeneration backend = UpscalerConfig.frameGeneration;
		return Platform.MAC && state == State.READY && backend != UpscalerConfig.FrameGeneration.OFF && frameGenSupported(backend);
	}

	public static boolean isFrameGenAvailable(UpscalerConfig.FrameGeneration backend) {
		ensureInit();
		return frameGenSupported(backend);
	}

	private static boolean frameGenSupported(UpscalerConfig.FrameGeneration backend) {
		return switch (backend) {
			case OFF -> false;
			case DLSS -> dlssFrameGenAvailable;
			case FSR -> Platform.MAC ? fsrSupported && MetalBackend.frameGenSupported() : fsrSupported;
			case METALFX -> Platform.MAC && MetalBackend.metalFxFrameGenSupported();
		};
	}

	/** macOS: frame generation runs this frame (it captures its own inputs, so it works with any upscaler). */
	private static boolean metalFrameGen() {
		UpscalerConfig.FrameGeneration backend = UpscalerConfig.frameGeneration;
		return Platform.MAC && state == State.READY && backend != UpscalerConfig.FrameGeneration.OFF && frameGenSupported(backend) && passesReady;
	}

	/**
	 * Frame generation can get motion vectors and depth with the current settings: at the native resolution when not
	 * upscaling, from DLSS or FSR when upscaling with them (a plain bilinear stretch has none). On macOS frame generation
	 * makes its own from the depth and camera, with any upscaler.
	 */
	public static boolean hasFrameGenInputs() {
		return passesReady && (Platform.MAC || !isActive() || isTemporalUpscalerReady());
	}

	/** World render size while upscaling, or null (F3 screen). */
	public static int @Nullable [] worldRenderSize() {
		return isActive() && worldTarget != null ? new int[] {worldTarget.width, worldTarget.height} : null;
	}

	// ------------------------------------------------------------------ presenting (macOS frame generation, FPS counter)

	/** Where Minecraft acquires a swapchain image: false while the macOS frame generation presenter shows the frames. */
	public static boolean shouldAcquireSwapchain() {
		return !Platform.MAC || state != State.READY || MetalBackend.shouldAcquireSwapchain(metalFrameGen());
	}

	/** Where Minecraft would blit its final image to the swapchain. */
	public static void beforeSwapchainBlit(Minecraft minecraft) {
		if (Platform.MAC && state == State.READY) {
			MetalBackend.beforeSwapchainBlit(minecraft);
		}
	}

	/** Where Minecraft presents. */
	public static void beforePresent() {
		if (Platform.MAC && state == State.READY) {
			MetalBackend.beforePresent();
		}
	}

	/** Frames frame generation showed since the last call, added to Minecraft's FPS counter. */
	public static int takeGeneratedFrames() {
		int frames = state != State.READY ? 0 : Platform.MAC ? MetalBackend.takeGeneratedFrames() : FrameGen.takeGeneratedFrames();
		FrameGenStats.frame(runningFrameGen() != null, frames);
		return frames;
	}

	/** GPU milliseconds of the upscaling passes (smoothed), or below 0 when not measured (F3 screen). */
	public static float upscaleGpuMillis() {
		if (state != State.READY) {
			return -1.0F;
		}
		if (Platform.MAC) {
			return MetalBackend.takeGpuMillis();
		}
		float[] gpu = DlssNative.gpuTimes();
		return gpu == null || gpu[1] <= 0.0F ? -1.0F : gpu[0] + gpu[1] + gpu[2];
	}

	/** The frame generator showing frames right now, or null (F3 screen). */
	public static UpscalerConfig.@Nullable FrameGeneration runningFrameGen() {
		if (state != State.READY) {
			return null;
		}
		if (Platform.MAC) {
			return MetalBackend.isPresenterLive() ? MetalBackend.frameGenerator() : null;
		}
		return FrameGen.isRunning() ? UpscalerConfig.frameGeneration : null;
	}

	public static int scaled(int fullSize) {
		return isActive() ? Math.max(1, Math.round(fullSize * UpscalerConfig.renderScale())) : fullSize;
	}

	// ------------------------------------------------------------------ sizing

	/** True if the targets don't match the current config and GameRenderer.resize should be re-run. */
	/** {@code hud3dTarget}: the first-person hand's target (null before 26.3, where the hand draws into the world). */
	public static boolean needsResize(RenderTarget mainTarget, @Nullable RenderTarget hud3dTarget) {
		int sw = scaled(mainTarget.width);
		int sh = scaled(mainTarget.height);
		if (hud3dTarget != null && (hud3dTarget.width != sw || hud3dTarget.height != sh)) {
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
			worldTarget = McCompat.colorDepthTarget("Upscaler World", sw, sh);
		} else if (worldTarget.width != sw || worldTarget.height != sh) {
			worldTarget.resize(sw, sh);
		}
		UpscalerMod.LOGGER.info("World render resolution {}x{} -> output {}x{}", sw, sh, width, height);
	}

	// ------------------------------------------------------------------ world phase

	public static RenderTarget redirect(RenderTarget target) {
		return inWorldPhase && target == realMainTarget && worldTarget != null ? worldTarget : target;
	}

	static boolean inWorldPhase() {
		return inWorldPhase;
	}

	/** GlobalSettingsUniform.update with the frame's other arguments filled in. */
	@FunctionalInterface
	public interface ScreenSizeUpdate {
		void update(int width, int height);
	}

	public static void captureUniformUpdate(ScreenSizeUpdate update) {
		uniformUpdate = update;
	}

	private static void updateScreenSize(int width, int height) {
		if (uniformUpdate != null) {
			uniformUpdate.update(width, height);
		}
	}

	public static void beginWorld(RenderTarget mainTarget) {
		if (frameHook != null) {
			frameHook.run();
		}
		TextureViews.prune();
		levelProjectionCaptured = false;
		boolean metalFrameGen = metalFrameGen();
		if (Platform.MAC) {
			MetalBackend.beginFrame();
		}
		TextureTarget world = worldTarget;
		boolean upscale = isActive() && world != null && world.width <= mainTarget.width
			&& world.width == scaled(mainTarget.width) && world.height == scaled(mainTarget.height);
		if (!upscale) {
			// Frame generation alone: motion vectors and depth from the world as drawn into the main target.
			captureFrame = Platform.MAC ? metalFrameGen : passesReady && FrameGen.isRunning();
			realMainTarget = captureFrame ? mainTarget : null;
			depth.beginFrame(realMainTarget);
			handMotion.beginFrame();
			return;
		}
		realMainTarget = mainTarget;
		inWorldPhase = true;
		temporalFrame = isTemporalUpscalerReady();
		captureFrame = temporalFrame || metalFrameGen;
		if (temporalFrame) {
			jitterCamera(world.width, world.height, (double)mainTarget.width / world.width);
		}
		depth.beginFrame(captureFrame ? world : null);
		handMotion.beginFrame();
		// After the jitter: the globals also carry the texture LOD correction (see lodScaleCode).
		updateScreenSize(world.width, world.height);
	}

	/**
	 * Render scale for the terrain shaders' texture LOD/edge correction, as round(scale * 1024), or 0 for none.
	 * Only used with the temporal upscalers, which can reconstruct the extra detail (see {@link ShaderPatches}).
	 */
	public static int lodScaleCode() {
		if (!inWorldPhase || !temporalFrame || worldTarget == null || realMainTarget == null || !UpscalerConfig.textureLodCorrection) {
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
		if (Platform.MAC) {
			endWorldMetal(main, upscale, temporal, capture);
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

	private static void endWorldMetal(RenderTarget main, boolean upscale, boolean temporal, boolean capture) {
		String gpuError = MetalBackend.newGpuError();
		if (gpuError != null) {
			logFailure(upscale, gpuError);
		}
		if (upscale) {
			updateScreenSize(main.width, main.height);
			if (!temporal || !upscaleTemporalMetal(worldTarget, main)) {
				temporalHistoryValid = false;
				if (!upscaleSpatialMetal(worldTarget, main)) {
					upscaleBilinear(worldTarget, main);
				}
			}
		}
		if (capture && levelProjectionCaptured && metalFrameGen()) {
			MetalBackend.captureForFrameGen(main, depth.sceneDepth(), depth.handDepth(), levelProjection, cameraState());
		}
		// After the capture: frame generation interpolates the world without the vignette, which stays put on screen.
		VitrailCompat.vignette(vignette);
		if (vignette[0] != PackVignette.NONE && MetalBackend.drawVignette(main.getColorTexture(), vignette) < 0 && !vignetteFailureLogged) {
			vignetteFailureLogged = true;
			UpscalerMod.LOGGER.warn("Could not draw the shader pack's vignette: {}", MetalBackend.lastError());
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
		handMotion.projection(matrix);
		return temporalFrame && !packJitterFrame ? jitter.apply(matrix, 1.0F) : matrix;
	}

	/** A projection matrix is being uploaded; while Vitrail draws the hand, it is the hand's. */
	public static void projectionUploaded(Matrix4f projection) {
		if (packHandDrawing) {
			handMotion.projection(projection);
		}
	}

	/** First-person arms are being submitted with {@code pose} under {@code modelView} (see HandMotion). */
	public static void handsSubmitted(Matrix4fc pose, Matrix4fc modelView) {
		armSubmitted(HandMotion.BOTH, pose, modelView);
	}

	/** One first-person arm ({@code right} or left) is being drawn with its final {@code pose}. */
	public static void armSubmitted(boolean right, Matrix4fc pose, Matrix4fc modelView) {
		armSubmitted(right ? HandMotion.RIGHT : HandMotion.LEFT, pose, modelView);
	}

	private static void armSubmitted(int arms, Matrix4fc pose, Matrix4fc modelView) {
		if (captureFrame) {
			handMotion.submitted(arms, pose, modelView);
		}
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
		packHandDrawing = true;
	}

	/** Called by Vitrail right after it drew the first-person hand into the world. */
	public static void afterPackHand() {
		depth.afterPackHand();
		packHandDrawing = false;
	}

	/** Called by Vitrail right before it draws the translucent half of the hand. */
	public static void beforePackTranslucentHand() {
		depth.beforePackTranslucentHand();
		packHandDrawing = true;
	}

	/** Called by Vitrail right after it drew the translucent half of the hand. */
	public static void afterPackTranslucentHand() {
		depth.afterPackTranslucentHand();
		packHandDrawing = false;
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
				UpscalerMod.LOGGER.info("Motion vector inputs missing this frame (projection {}, hand depth {}); skipping such frames",
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
				disableUpscaler();
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

	/** macOS: MetalFX Temporal or FSR, with the same camera history as {@link #recordFrame}. */
	private static boolean upscaleTemporalMetal(RenderTarget source, RenderTarget destination) {
		GpuTexture src = source.getColorTexture();
		GpuTexture dst = destination.getColorTexture();
		GpuTexture sceneDepth = depth.sceneDepth();
		GpuTexture handDepth = depth.handDepth();
		if (!levelProjectionCaptured || sceneDepth == null || handDepth == null || src == null || dst == null) {
			if (!loggedMissingInputs) {
				loggedMissingInputs = true;
				UpscalerMod.LOGGER.info("Motion vector inputs missing this frame (projection {}, hand depth {}); skipping such frames",
					levelProjectionCaptured, handDepth != null);
			}
			return false;
		}
		CameraRenderState camera = cameraState();
		levelProjection.mul(camera.viewRotationMatrix, viewProj);
		Vec3 cameraPos = camera.pos;
		ClientLevel level = Minecraft.getInstance().level;
		boolean reset = !temporalHistoryValid
			|| level != prevLevel
			|| source.width != prevWidth
			|| source.height != prevHeight
			|| destination.width != prevOutWidth
			|| cameraPos.distanceToSqr(prevCameraPos) > TELEPORT_SQR;
		if (reset) {
			prevViewProj.set(viewProj);
			prevCameraPos = cameraPos;
		}
		MemorySegment params = MetalBackend.temporalParams;
		MetalBackend.writeMatrix(params, MetalBackend.T_INV_VIEW_PROJ, viewProj.invert(invViewProj));
		MetalBackend.writeMatrix(params, MetalBackend.T_PREV_VIEW_PROJ, prevViewProj);
		params.set(ValueLayout.JAVA_FLOAT, MetalBackend.T_CAM_DELTA, (float)(cameraPos.x - prevCameraPos.x));
		params.set(ValueLayout.JAVA_FLOAT, MetalBackend.T_CAM_DELTA + 4, (float)(cameraPos.y - prevCameraPos.y));
		params.set(ValueLayout.JAVA_FLOAT, MetalBackend.T_CAM_DELTA + 8, (float)(cameraPos.z - prevCameraPos.z));
		params.set(ValueLayout.JAVA_FLOAT, MetalBackend.T_CAM_DELTA + 12, 0.0F);
		params.set(ValueLayout.JAVA_FLOAT, MetalBackend.T_JITTER, jitter.pixelX());
		params.set(ValueLayout.JAVA_FLOAT, MetalBackend.T_JITTER + 4, jitter.pixelY());
		params.set(ValueLayout.JAVA_INT, MetalBackend.T_RESET, reset ? 1 : 0);
		params.set(ValueLayout.JAVA_INT, MetalBackend.T_Z_ZERO_TO_ONE, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1 : 0);
		params.set(ValueLayout.JAVA_INT, MetalBackend.T_Z_ZERO_TO_ONE + 4, 0); // flipY
		params.set(ValueLayout.JAVA_INT, MetalBackend.T_Z_ZERO_TO_ONE + 8, 0); // debug motion view
		params.set(ValueLayout.JAVA_FLOAT, MetalBackend.T_MOTION_SCALE, 1.0F);
		params.set(ValueLayout.JAVA_FLOAT, MetalBackend.T_MOTION_SCALE + 4, 1.0F);
		params.set(ValueLayout.JAVA_INT, MetalBackend.T_MOTION_SCALE + 8, 0); // skip the scaler (debug)
		params.set(ValueLayout.JAVA_FLOAT, MetalBackend.T_SHARPNESS, UpscalerConfig.sharpness);
		params.set(ValueLayout.JAVA_INT, MetalBackend.T_KIND,
			UpscalerConfig.upscaler == UpscalerConfig.Upscaler.FSR ? MetalBackend.KIND_FSR : MetalBackend.KIND_METALFX);
		params.set(ValueLayout.JAVA_FLOAT, MetalBackend.T_M22, levelProjection.m22());
		params.set(ValueLayout.JAVA_FLOAT, MetalBackend.T_M32, levelProjection.m32());
		params.set(ValueLayout.JAVA_FLOAT, MetalBackend.T_INV_M00, 1.0F / levelProjection.m00());
		params.set(ValueLayout.JAVA_FLOAT, MetalBackend.T_INV_M11, 1.0F / levelProjection.m11());
		long now = System.nanoTime();
		params.set(ValueLayout.JAVA_FLOAT, MetalBackend.T_FRAME_TIME, lastTemporalNanos == 0L ? 16.7F : (now - lastTemporalNanos) / 1.0e6F);
		lastTemporalNanos = now;
		motionBoxes.writePlayerBox(params, MetalBackend.T_PLAYER_BOX, cameraPos, reset);
		handMotion.write(params, MetalBackend.T_HAND_MOTION, MetalBackend.T_HAND_MOTION + 16, reset);

		int result = MetalBackend.upscaleTemporal(src, sceneDepth, handDepth, dst);
		if (result <= 0) {
			logFailure(true, MetalBackend.lastError());
			return false;
		}
		prevViewProj.set(viewProj);
		prevCameraPos = cameraPos;
		prevLevel = level;
		prevWidth = source.width;
		prevHeight = source.height;
		prevOutWidth = destination.width;
		temporalHistoryValid = true;
		return true;
	}

	/** macOS: MetalFX Spatial, when it is the selected upscaler. */
	private static boolean upscaleSpatialMetal(RenderTarget source, RenderTarget destination) {
		GpuTexture src = source.getColorTexture();
		GpuTexture dst = destination.getColorTexture();
		if (UpscalerConfig.upscaler != UpscalerConfig.Upscaler.METALFX_SPATIAL || !isUpscalerReady(UpscalerConfig.Upscaler.METALFX_SPATIAL)
			|| src == null || dst == null) {
			return false;
		}
		int result = MetalBackend.upscaleSpatial(src, dst, UpscalerConfig.sharpness);
		if (result <= 0) {
			logFailure(true, MetalBackend.lastError());
			return false;
		}
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
		frame.set(ValueLayout.JAVA_INT, DlssNative.FRAME_QUALITY, UpscalerConfig.ngxQuality());
		frame.set(ValueLayout.JAVA_INT, DlssNative.FRAME_PRESET, resolvedPreset().ngxValue);
		frame.set(ValueLayout.JAVA_INT, DlssNative.FRAME_Z_ZERO_TO_ONE, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1 : 0);
		long now = System.nanoTime();
		frame.set(ValueLayout.JAVA_FLOAT, DlssNative.FRAME_FRAME_TIME, lastTemporalNanos == 0L ? 16.7F : (now - lastTemporalNanos) / 1.0e6F);
		lastTemporalNanos = now;
		frame.set(ValueLayout.JAVA_INT, DlssNative.FRAME_UPSCALER, upscale ? UpscalerConfig.upscaler.nativeCode() : 0);
		frame.set(ValueLayout.JAVA_FLOAT, DlssNative.FRAME_SHARPNESS, UpscalerConfig.sharpness);
		frame.set(ValueLayout.JAVA_FLOAT, DlssNative.FRAME_FOV, (float)(2.0 * Math.atan(1.0 / Math.abs(levelProjection.m11()))));
		frame.set(ValueLayout.JAVA_FLOAT, DlssNative.FRAME_NEAR, 0.05F);
		handMotion.write(frame, DlssNative.FRAME_HAND_MOTION, DlssNative.FRAME_HAND_CLIP_TO_LOCAL, reset);
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
			.createRenderPass(() -> "Upscaler bilinear upscale", destination.getColorTextureView(), Optional.empty())) {
			McCompat.setupBlit(pass, source.getColorTextureView());
			pass.draw(3, 1, 0, 0);
		}
	}

	private static void disableUpscaler() {
		String name = UpscalerConfig.upscaler.displayName();
		UpscalerMod.LOGGER.error("{} failed with {}; using bilinear until the upscaling settings change", name, currentSetting());
		failedUpscaler = UpscalerConfig.upscaler;
		failedSetting = currentSetting();
	}

	private static void logFailure(boolean upscale, String message) {
		loggedFailures++;
		if (loggedFailures <= MAX_LOGGED_FAILURES) {
			UpscalerMod.LOGGER.warn(upscale ? UpscalerConfig.upscaler.displayName() + " failed, using bilinear this frame: {}"
				: "No motion vectors for frame generation this frame: {}", message);
		}
		if (upscale && loggedFailures == FAILURES_BEFORE_DISABLE && UpscalerConfig.upscaler != UpscalerConfig.Upscaler.BILINEAR
			&& UpscalerConfig.upscaler != failedUpscaler) {
			disableUpscaler();
		}
	}
}
