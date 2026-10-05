package dev.mcupscaler;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.KHRSynchronization2;

/**
 * The macOS side: MetalFX (temporal and spatial), AMD FSR 3.1 ported to Metal, and frame generation (MetalFX or FSR) with
 * its own presenter, all in the native bridge (src/native/mac/metalfx_bridge.m).
 * <p>
 * Minecraft runs on MoltenVK, so its Vulkan images and semaphores are Metal objects underneath (VK_EXT_metal_objects).
 * Each native pass is encoded on a Metal queue and ordered against Minecraft's commands with a shared timeline semaphore:
 * Vulkan signals {@code wait}, Metal waits for it, does its work and signals {@code done}, which Vulkan waits for.
 */
final class MetalBackend {
	private static final long ALL_COMMANDS = KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR;
	private static final int MAX_LOGGED_FAILURES = 5;
	/** A camera jump farther than this (squared) resets frame generation's history. */
	private static final double TELEPORT_SQR = 64.0;

	/** Offsets in MfxTemporalParams. */
	static final int TEMPORAL_PARAMS_SIZE = 320;
	static final long T_INV_VIEW_PROJ = 0, T_PREV_VIEW_PROJ = 64, T_CAM_DELTA = 128, T_JITTER = 144, T_RESET = 152, T_Z_ZERO_TO_ONE = 156;
	static final long T_MOTION_SCALE = 168, T_SHARPNESS = 180, T_KIND = 184, T_M22 = 240, T_M32 = 244, T_INV_M00 = 248, T_INV_M11 = 252;
	static final long T_FRAME_TIME = 256, T_PLAYER_BOX = 272;
	/** MfxTemporalParams kind: MetalFX temporal or FSR 3.1. */
	static final int KIND_METALFX = 0, KIND_FSR = 4;
	private static final int FG_PARAMS_SIZE = 256;

	static final MemorySegment temporalParams = Arena.global().allocate(TEMPORAL_PARAMS_SIZE, 16);
	private static final MemorySegment fgParams = Arena.global().allocate(FG_PARAMS_SIZE, 16);
	private static final float[] matrixScratch = new float[16];

	private static boolean ready;
	private static boolean temporalSupported;
	private static float temporalMaxScale;
	private static boolean frameGenSupported;
	private static boolean metalFxFrameGenSupported;
	@Nullable
	private static VulkanDevice device;
	private static long timelineSemaphore;
	private static long sharedEvent;
	private static long timelineValue;
	// DepthCapture's merges are kept natively until the temporal upscale (or flushMerges) runs them.
	private static boolean mergesPending;
	private static int seenGpuErrors;
	@Nullable
	private static String error;

	// ---- frame generation
	private static final MotionBoxes fgBoxes = new MotionBoxes();
	/** This frame's world image, depth and camera were captured, so a frame can be generated before it. */
	private static boolean fgCaptured;
	private static boolean fgHistoryValid;
	private static final Matrix4f fgPrevViewProj = new Matrix4f();
	private static Vec3 fgPrevCameraPos = Vec3.ZERO;
	@Nullable
	private static ClientLevel fgPrevLevel;
	private static int fgPrevWidth, fgPrevHeight;
	private static long fgLastNanos;
	/** Copies of this frame's world image (before the HUD), hand depth and scene depth (the next frame overwrites those). */
	@Nullable
	private static GpuTexture fgWorld, fgHand, fgDepth;
	/** The native presenter was asked to attach (and must be detached when frame generation turns off). */
	private static boolean presenterRequested;
	/** The native presenter shows the frames: Minecraft doesn't acquire or present swapchain images. */
	private static boolean presenterLive;
	private static boolean loggedPresenterFailure;
	private static int fgFailures;

	private MetalBackend() {
	}

	/** Sets up the bridge on Minecraft's device; returns null when it works, else why not. */
	@Nullable
	static String init(VulkanDevice vk) {
		device = vk;
		if (!VulkanMetalInterop.isAvailable(vk.vkDevice())) {
			UpscalerMod.LOGGER.warn("{} is not enabled on the Vulkan device (not running on MoltenVK?); MetalFX unavailable", VulkanMetalInterop.EXTENSION);
			return "needs MoltenVK's Metal interop";
		}
		if (!NativeBridge.load()) {
			return "native bridge failed to load";
		}
		int result = NativeBridge.init(VulkanMetalInterop.mtlDevice(vk.vkDevice()));
		if (result != 1) {
			String why = NativeBridge.lastError();
			UpscalerMod.LOGGER.warn("MetalFX unavailable: {}", why);
			return why.isEmpty() ? "MetalFX is not supported on this Mac" : why;
		}
		timelineSemaphore = VulkanMetalInterop.createSharedTimelineSemaphore(vk.vkDevice());
		sharedEvent = VulkanMetalInterop.mtlSharedEvent(vk.vkDevice(), timelineSemaphore);
		if (sharedEvent == 0L) {
			UpscalerMod.LOGGER.warn("Could not export MTLSharedEvent from timeline semaphore; MetalFX unavailable");
			return "could not share a semaphore with Metal";
		}
		ready = true;
		temporalSupported = NativeBridge.temporalSupported();
		temporalMaxScale = temporalSupported ? Math.max(1.0F, NativeBridge.temporalMaxScale()) : 0.0F;
		frameGenSupported = NativeBridge.frameInterpolationSupported();
		metalFxFrameGenSupported = frameGenSupported && NativeBridge.metalFxFrameInterpolationSupported();
		UpscalerMod.LOGGER.info("MetalFX ready on {} (spatial: yes, temporal: {}, FSR 3.1: yes, frame generation: {})", vk.getDeviceInfo().name(),
			temporalSupported ? "yes, up to " + temporalMaxScale + "x" : "no",
			!frameGenSupported ? "no" : metalFxFrameGenSupported ? "FSR 3.1 + MetalFX" : "FSR 3.1");
		return null;
	}

	static boolean isReady() {
		return ready;
	}

	static boolean temporalSupported() {
		return ready && temporalSupported;
	}

	/** Smallest render scale MetalFX Temporal accepts, or 0. */
	static float temporalMinScale() {
		return temporalSupported ? 1.0F / temporalMaxScale : 0.0F;
	}

	static boolean frameGenSupported() {
		return ready && frameGenSupported;
	}

	static boolean metalFxFrameGenSupported() {
		return ready && metalFxFrameGenSupported;
	}

	static void shutdown() {
		if (presenterRequested) {
			NativeBridge.fgDetach();
			presenterRequested = presenterLive = false;
		}
		ready = false;
	}

	static String lastError() {
		return error != null ? error : NativeBridge.lastError();
	}

	/** A GPU error the bridge saw since the last call (Metal command buffers fail asynchronously), or null. */
	@Nullable
	static String newGpuError() {
		if (!ready) {
			return null;
		}
		int errors = NativeBridge.gpuErrorCount();
		if (errors == seenGpuErrors) {
			return null;
		}
		seenGpuErrors = errors;
		return NativeBridge.lastError();
	}

	/** Average GPU time of the bridge's work since the last call, in ms, or -1. */
	static float takeGpuMillis() {
		int micros = ready ? NativeBridge.takeGpuMicros() : -1;
		return micros < 0 ? -1.0F : micros / 1000.0F;
	}

	// ------------------------------------------------------------------ native passes

	private interface MetalPass {
		int encode(long sharedEvent, long waitValue, long doneValue);
	}

	/**
	 * Runs a native pass between Minecraft's commands. The pass returns 1 on success, 0 on a failure that still
	 * signalled {@code done}, and below 0 when it encoded nothing (except {@code fgSubmit}: only -2).
	 */
	private static int run(MetalPass pass, boolean frameGen) {
		error = null;
		VulkanCommandEncoder vkEncoder = McCompat.vulkanEncoder(false);
		if (vkEncoder == null) {
			error = "no Vulkan command encoder";
			return -1;
		}
		long waitValue = timelineValue + 1;
		long doneValue = timelineValue + 2;
		int result = pass.encode(sharedEvent, waitValue, doneValue);
		if (frameGen ? result == -2 : result < 0) {
			return result;
		}
		timelineValue = doneValue;
		vkEncoder.signalSemaphore(timelineSemaphore, waitValue, ALL_COMMANDS);
		vkEncoder.waitSemaphore(timelineSemaphore, doneValue, ALL_COMMANDS);
		return result;
	}

	/** The Metal texture behind a Vulkan texture, or 0. */
	private static long mtl(@Nullable GpuTexture texture) {
		return device != null && texture instanceof VulkanGpuTexture vk ? VulkanMetalInterop.mtlTexture(device.vkDevice(), vk.vkImage()) : 0L;
	}

	private static int missingTextures() {
		error = "could not export MTLTextures from Vulkan images";
		return -1;
	}

	/** MetalFX spatial upscale of {@code src} into {@code dst}. */
	static int upscaleSpatial(GpuTexture src, GpuTexture dst, float sharpness) {
		long in = mtl(src), out = mtl(dst);
		if (in == 0L || out == 0L) {
			return missingTextures();
		}
		return run((event, wait, done) -> NativeBridge.upscale(in, out, event, wait, done, sharpness), false);
	}

	/** MetalFX temporal or FSR upscale; {@link #temporalParams} must be written first. */
	static int upscaleTemporal(GpuTexture src, GpuTexture sceneDepth, GpuTexture handDepth, GpuTexture dst) {
		long in = mtl(src), depth = mtl(sceneDepth), hand = mtl(handDepth), out = mtl(dst);
		if (in == 0L || depth == 0L || hand == 0L || out == 0L) {
			return missingTextures();
		}
		// The kept depth merges run at the start of this command buffer.
		mergesPending = false;
		return run((event, wait, done) -> NativeBridge.upscaleTemporal(in, depth, hand, out, event, wait, done, temporalParams.address()), false);
	}

	/** See DepthCapture#mergePackDepth: rewrites {@code pre} in place. */
	static int mergePackDepth(GpuTexture pre, GpuTexture post, GpuTexture fin, @Nullable GpuTexture preTl, @Nullable GpuTexture postTl) {
		long a = mtl(pre), b = mtl(post), c = mtl(fin);
		if (a == 0L || b == 0L || c == 0L) {
			return missingTextures();
		}
		long d = mtl(preTl), e = mtl(postTl);
		long tlPre = d == 0L || e == 0L ? 0L : d, tlPost = d == 0L || e == 0L ? 0L : e;
		// Kept for the next temporal upscale (or flushMerges): no Vulkan <-> Metal round trip of its own.
		int result = NativeBridge.mergePackDepth(a, b, c, tlPre, tlPost);
		mergesPending |= result > 0;
		return result;
	}

	/** See DepthCapture#mergeDistantDepth: rewrites {@code scene} in place. */
	static int mergeDistantDepth(GpuTexture scene, GpuTexture distant, float pairA, float pairB) {
		long a = mtl(scene), b = mtl(distant);
		if (a == 0L || b == 0L) {
			return missingTextures();
		}
		int result = NativeBridge.mergeDistantDepth(a, b, pairA, pairB);
		mergesPending |= result > 0;
		return result;
	}

	static void writeMatrix(MemorySegment segment, long offset, Matrix4f matrix) {
		matrix.get(matrixScratch);
		MemorySegment.copy(matrixScratch, 0, segment, ValueLayout.JAVA_FLOAT, offset, matrixScratch.length);
	}

	// ------------------------------------------------------------------ frame generation

	/** The generator in use: MetalFX if picked and available (macOS 26), otherwise FSR. */
	static UpscalerConfig.FrameGeneration frameGenerator() {
		return useMetalFxFrameGen() ? UpscalerConfig.FrameGeneration.METALFX : UpscalerConfig.FrameGeneration.FSR;
	}

	private static boolean useMetalFxFrameGen() {
		return UpscalerConfig.frameGeneration == UpscalerConfig.FrameGeneration.METALFX && metalFxFrameGenSupported;
	}

	/**
	 * Called where Minecraft acquires a swapchain image each frame. While frame generation runs, the native presenter (its
	 * own layer over the game, paced by the display) shows the frames, so Minecraft must not acquire: without an acquired
	 * image it also skips its blit and present. Returns whether Minecraft should acquire.
	 */
	static boolean shouldAcquireSwapchain(boolean frameGenActive) {
		if (!frameGenActive) {
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
			UpscalerMod.LOGGER.warn("Frame generation unavailable: {}", NativeBridge.lastError());
		}
		presenterLive = result == 1;
		return !presenterLive;
	}

	static boolean isPresenterLive() {
		return presenterLive;
	}

	/** Generated frames the presenter showed since the last call (added to Minecraft's FPS counter). */
	static int takeGeneratedFrames() {
		return presenterRequested ? NativeBridge.fgTakeGenerated() : 0;
	}

	/** Runs the kept depth merges now, if the frame had no temporal upscale to run them. */
	private static void flushMerges() {
		if (mergesPending) {
			mergesPending = false;
			run(NativeBridge::mergesFlush, false);
		}
	}

	static void beginFrame() {
		if (mergesPending) {
			mergesPending = false;
			NativeBridge.mergesDiscard();
		}
		fgCaptured = false;
	}

	/**
	 * Keeps what frame generation needs from this frame: the world image (Minecraft's target before the HUD, upscaled or
	 * native), the depth images and the camera ({@code projection} without jitter).
	 */
	static void captureForFrameGen(RenderTarget main, @Nullable GpuTexture sceneDepth, @Nullable GpuTexture handDepth, Matrix4f projection,
		CameraRenderState camera) {
		GpuTexture color = main.getColorTexture();
		if (sceneDepth == null || handDepth == null || color == null) {
			fgHistoryValid = false;
			return;
		}
		flushMerges();
		var encoder = RenderSystem.getDevice().createCommandEncoder();
		fgWorld = ensureCopy(fgWorld, "Upscaler Frame Gen World", color);
		fgHand = ensureCopy(fgHand, "Upscaler Frame Gen Hand Depth", handDepth);
		fgDepth = ensureCopy(fgDepth, "Upscaler Frame Gen Scene Depth", sceneDepth);
		encoder.copyTextureToTexture(color, fgWorld, 0, 0, 0, 0, 0, color.getWidth(0), color.getHeight(0));
		encoder.copyTextureToTexture(handDepth, fgHand, 0, 0, 0, 0, 0, handDepth.getWidth(0), handDepth.getHeight(0));
		encoder.copyTextureToTexture(sceneDepth, fgDepth, 0, 0, 0, 0, 0, sceneDepth.getWidth(0), sceneDepth.getHeight(0));

		Matrix4f viewProj = new Matrix4f(projection).mul(camera.viewRotationMatrix);
		Vec3 cameraPos = camera.pos;
		ClientLevel level = Minecraft.getInstance().level;
		int width = sceneDepth.getWidth(0), height = sceneDepth.getHeight(0);
		boolean reset = !fgHistoryValid || level != fgPrevLevel || width != fgPrevWidth || height != fgPrevHeight
			|| cameraPos.distanceToSqr(fgPrevCameraPos) > TELEPORT_SQR;
		if (reset) {
			fgPrevViewProj.set(viewProj);
			fgPrevCameraPos = cameraPos;
		}
		long now = System.nanoTime();
		float frameTimeMs = fgLastNanos == 0L ? 16.7F : Math.max(1.0F, Math.min(250.0F, (now - fgLastNanos) / 1.0e6F));
		fgLastNanos = now;
		// Minecraft's reversed-Z projection: view depth = m32 / (device depth + m22).
		float m22 = projection.m22(), m32 = projection.m32();
		writeMatrix(fgParams, 0, new Matrix4f(viewProj).invert());
		writeMatrix(fgParams, 64, fgPrevViewProj);
		fgParams.set(ValueLayout.JAVA_FLOAT, 128, (float)(cameraPos.x - fgPrevCameraPos.x));
		fgParams.set(ValueLayout.JAVA_FLOAT, 132, (float)(cameraPos.y - fgPrevCameraPos.y));
		fgParams.set(ValueLayout.JAVA_FLOAT, 136, (float)(cameraPos.z - fgPrevCameraPos.z));
		fgParams.set(ValueLayout.JAVA_FLOAT, 140, 0.0F);
		fgParams.set(ValueLayout.JAVA_INT, 144, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1 : 0);
		fgParams.set(ValueLayout.JAVA_INT, 148, 0); // flipY
		fgParams.set(ValueLayout.JAVA_INT, 152, reset ? 1 : 0);
		fgParams.set(ValueLayout.JAVA_INT, 156, 0); // debugShowInterpolated
		fgParams.set(ValueLayout.JAVA_FLOAT, 160, Math.abs(m32 / (1.0F + m22)));
		fgParams.set(ValueLayout.JAVA_FLOAT, 164, Math.abs(m22) > 1.0e-6F ? Math.abs(m32 / m22) : 100000.0F);
		fgParams.set(ValueLayout.JAVA_FLOAT, 168, 1.0F / projection.m00());
		fgParams.set(ValueLayout.JAVA_FLOAT, 172, 1.0F / projection.m11());
		fgParams.set(ValueLayout.JAVA_FLOAT, 176, 1.0F); // motion scale x, y
		fgParams.set(ValueLayout.JAVA_FLOAT, 180, 1.0F);
		fgParams.set(ValueLayout.JAVA_FLOAT, 184, frameTimeMs);
		fgParams.set(ValueLayout.JAVA_INT, 188, useMetalFxFrameGen() ? 1 : 0);
		float[] cross = debugCrosshairBox(camera);
		fgParams.set(ValueLayout.JAVA_FLOAT, 192, cross[0]);
		fgParams.set(ValueLayout.JAVA_FLOAT, 196, cross[1]);
		fgParams.set(ValueLayout.JAVA_INT, 200, blurredMenuOpen() ? 1 : 0);
		fgParams.set(ValueLayout.JAVA_INT, 204, 0);
		fgBoxes.writePlayerBox(fgParams, 208, cameraPos, reset);
		fgPrevViewProj.set(viewProj);
		fgPrevCameraPos = cameraPos;
		fgPrevLevel = level;
		fgPrevWidth = width;
		fgPrevHeight = height;
		fgHistoryValid = true;
		fgCaptured = true;
	}

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

	/** A menu with the blurred world behind it (the world image no longer shows through as it is). */
	private static boolean blurredMenuOpen() {
		Minecraft mc = Minecraft.getInstance();
		var screen = mc.gui.screen();
		return screen != null && !(screen instanceof ChatScreen) && mc.options.getMenuBackgroundBlurriness() > 0;
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
	static void beforeSwapchainBlit(Minecraft minecraft) {
		boolean captured = fgCaptured;
		fgCaptured = false;
		if (!presenterLive || device == null) {
			return;
		}
		GpuTexture mainColor = minecraft.gameRenderer.mainRenderTarget().getColorTexture();
		long finalTexture = mtl(mainColor);
		if (finalTexture == 0L) {
			return;
		}
		long world = 0L, depth = 0L, hand = 0L;
		if (captured && fgWorld != null && fgWorld.getWidth(0) == mainColor.getWidth(0) && fgWorld.getHeight(0) == mainColor.getHeight(0)) {
			world = mtl(fgWorld);
			depth = mtl(fgDepth);
			hand = mtl(fgHand);
			if (depth == 0L || hand == 0L) {
				world = 0L;
			}
		}
		if (world == 0L) {
			fgHistoryValid = false;
		}
		long worldTexture = world, depthTexture = depth, handTexture = hand;
		long params = worldTexture != 0L ? fgParams.address() : 0L;
		int result = run((event, wait, done) -> NativeBridge.fgSubmit(worldTexture, finalTexture, depthTexture, handTexture, event, wait, done, params),
			true);
		if (result < 0) {
			fgFailures++;
			if (fgFailures <= MAX_LOGGED_FAILURES) {
				UpscalerMod.LOGGER.warn("Frame generation failed this frame: {}", lastError());
			}
		}
	}

	/**
	 * Called after Minecraft submitted the frame, where it would present: waits until the presenter can take another frame.
	 * This is what paces the game while frame generation runs (Minecraft no longer waits for VSync).
	 */
	static void beforePresent() {
		if (presenterLive) {
			NativeBridge.fgReserve();
		}
	}
}
