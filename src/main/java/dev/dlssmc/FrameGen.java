package dev.dlssmc;

import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Frame generation (NVIDIA DLSS-G or AMD FSR 3.1). While it runs, the native present thread owns Minecraft's swapchain:
 * Minecraft's own acquire, blit and present are replaced (see VulkanGpuSurfaceMixin) by copying the finished frame into the
 * native ring, where the selected generator makes the frame between it and the previous one, and handing both to the
 * present thread.
 */
public final class FrameGen {
	/** VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT. */
	private static final long ALL_COMMANDS = 0x10000L;

	/** What frame generation needs from Minecraft's Vulkan surface (implemented by VulkanGpuSurfaceMixin). */
	public interface Surface {
		long dlssmc$swapchain();

		long[] dlssmc$swapchainImages();

		int dlssmc$width();

		int dlssmc$height();

		/** The queue to present on: Minecraft's (unused) compute queue if it can present, else the graphics queue. */
		long dlssmc$presentQueue();

		int dlssmc$presentQueueFamily();

		int dlssmc$graphicsQueueFamily();

		int dlssmc$swapchainFormat();

		boolean dlssmc$hasOwnPresentQueue();

		boolean dlssmc$fifo();

		void dlssmc$markSuboptimal();
	}

	private static final MemorySegment finalTex = Arena.global().allocate(DlssNative.TEX_SIZE, 8);
	private static final MemorySegment submit = Arena.global().allocate(DlssNative.FG_SUBMIT_SIZE, 8);
	private static final MemorySegment camera = Arena.global().allocate(DlssNative.FG_CAMERA_SIZE, 16);
	private static final float[] matrix = new float[16];
	private static final Matrix4f viewToClip = new Matrix4f(), clipToPrevClip = new Matrix4f(), temp = new Matrix4f(), depthInverse = new Matrix4f();
	private static final Vector3f axis = new Vector3f();
	/** Minecraft's near plane. */
	private static final float NEAR = 0.05F;
	/** The depth handed over has no far plane (near / view depth, see the motion shader), so the far plane is beyond any terrain. */
	private static final float FAR = 1.0e7F;

	// The extra graphics-family queue created for presenting (VulkanBackendMixin), or -1.
	private static int presentQueueFamily = -1;
	private static int presentQueueIndex = -1;

	private static boolean running;
	/** The generator that failed to start (not retried until another one is picked), or null. */
	private static DlssConfig.@Nullable FrameGeneration startFailed;
	private static long pendingFrame;
	private static boolean pendingInterpolated;
	private static int loggedErrors;
	// Frames shown in the last second (real + generated), for the F3 screen.
	private static int realFrames, generatedFrames, shownRealFps, shownGeneratedFps;
	private static long fpsWindowStart;

	private FrameGen() {
	}

	public static void presentQueueCreated(int family, int index) {
		presentQueueFamily = family;
		presentQueueIndex = index;
		DlssMod.LOGGER.info("Created an extra queue for DLSS Frame Generation (family {}, index {})", family, index);
	}

	/** The extra queue created for presenting: {family, index}, or null. */
	public static int @Nullable [] presentQueue() {
		return presentQueueIndex < 0 ? null : new int[] {presentQueueFamily, presentQueueIndex};
	}

	public static boolean isRunning() {
		return running;
	}

	/** Independent of upscaling: without it, the motion vectors and depth are made at the native resolution. */
	private static boolean wanted() {
		DlssConfig.FrameGeneration backend = DlssConfig.frameGeneration;
		return backend != DlssConfig.FrameGeneration.OFF && WorldUpscaler.isFrameGenAvailable(backend) && WorldUpscaler.hasFrameGenInputs()
			&& startFailed != backend;
	}

	/**
	 * Start of a frame, instead of acquiring a swapchain image. Starts or stops the present thread when the setting
	 * changed. Returns true if frame generation handles this frame (Minecraft must not acquire).
	 */
	public static boolean beginFrame(Surface surface) throws SurfaceException {
		boolean wanted = wanted();
		if (running && !wanted) {
			stop();
			return false;
		}
		if (!running && wanted) {
			start(surface);
		}
		if (!running) {
			return false;
		}
		int state = DlssNative.fgSurfaceState();
		if (state == 2) {
			// Minecraft configures the surface again next frame (which restarts the present thread).
			throw new SurfaceException("swapchain out of date (frame generation)");
		}
		if (state == 1) {
			surface.dlssmc$markSuboptimal();
		}
		return true;
	}

	private static void start(Surface surface) {
		long swapchain = surface.dlssmc$swapchain();
		if (swapchain == 0L) {
			return;
		}
		int presentFamily = surface.dlssmc$presentQueueFamily();
		int graphicsFamily = surface.dlssmc$graphicsQueueFamily();
		running = DlssNative.fgStart(surface.dlssmc$presentQueue(), presentFamily, graphicsFamily, swapchain, surface.dlssmc$swapchainFormat(),
			surface.dlssmc$swapchainImages(), surface.dlssmc$width(), surface.dlssmc$height());
		if (running) {
			DlssMod.LOGGER.info("{} Frame Generation on ({}x{}, {}, presenting on {} queue)", DlssConfig.frameGeneration.displayName(),
				surface.dlssmc$width(), surface.dlssmc$height(), surface.dlssmc$fifo() ? "vsync" : "no vsync",
				surface.dlssmc$hasOwnPresentQueue() ? "its own" : "Minecraft's");
		} else {
			startFailed = DlssConfig.frameGeneration;
			DlssMod.LOGGER.error("{} Frame Generation could not start: {}", DlssConfig.frameGeneration.displayName(), DlssNative.lastError());
		}
	}

	/** Stops the present thread (before Minecraft destroys or recreates the swapchain, or when switched off). */
	public static void stop() {
		if (!running) {
			return;
		}
		DlssNative.fgStop();
		running = false;
		pendingFrame = 0L;
	}

	/** After Minecraft configured the swapchain: picks it up straight away. */
	public static void surfaceConfigured(Surface surface) {
		if (wanted()) {
			start(surface);
		}
	}

	/** Instead of Minecraft's blit to the swapchain: records the copy into the ring and the frame generator. */
	public static void blit(VulkanCommandEncoder encoder, GpuTextureView view) {
		pendingFrame = 0L;
		if (!DlssNative.writeTex(finalTex, 0, view.texture(), view)) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		boolean notGame = mc.gui.screen() != null || mc.level == null || mc.isPaused();
		VkCommandBuffer cb = encoder.allocateAndBeginTransientCommandBuffer();
		boolean ok = DlssNative.fgRecord(cb.address(), finalTex, DlssConfig.frameGeneration.nativeCode(), notGame, submit);
		VK12.vkEndCommandBuffer(cb);
		if (!ok) {
			if (loggedErrors++ < 10) {
				DlssMod.LOGGER.warn("{} Frame Generation skipped a frame: {}", DlssConfig.frameGeneration.displayName(), DlssNative.lastError());
			}
			encoder.execute(cb);
			return;
		}
		long presented = submit.get(ValueLayout.JAVA_LONG, DlssNative.FG_PRESENTED_VALUE);
		if (presented > 0L) {
			encoder.waitSemaphore(submit.get(ValueLayout.JAVA_LONG, DlssNative.FG_PRESENTED_SEMAPHORE), presented, ALL_COMMANDS);
		}
		encoder.execute(cb);
		long frame = submit.get(ValueLayout.JAVA_LONG, DlssNative.FG_READY_VALUE);
		encoder.signalSemaphore(submit.get(ValueLayout.JAVA_LONG, DlssNative.FG_READY_SEMAPHORE), frame, ALL_COMMANDS);
		pendingFrame = frame;
		pendingInterpolated = submit.get(ValueLayout.JAVA_INT, DlssNative.FG_INTERPOLATED) != 0;
	}

	/** Instead of Minecraft's present (after the frame was submitted): hands the frame to the present thread. */
	public static void present() {
		if (pendingFrame == 0L) {
			return;
		}
		DlssNative.fgQueue(pendingFrame, pendingInterpolated, Reflex.takePresentId());
		realFrames++;
		if (pendingInterpolated) {
			generatedFrames++;
		}
		pendingFrame = 0L;
		long now = System.nanoTime();
		if (now - fpsWindowStart >= 1_000_000_000L) {
			shownRealFps = realFrames;
			shownGeneratedFps = generatedFrames;
			realFrames = generatedFrames = 0;
			fpsWindowStart = now;
		}
	}

	/**
	 * The frame's camera for frame generation, after its motion vectors were made: projection without jitter, view rotation, the previous
	 * frame's projection * rotation, and the camera movement since then (matrices relative to the camera position).
	 */
	public static void writeCamera(Matrix4fc projection, Matrix4fc viewRotation, Matrix4fc viewProj, Matrix4fc prevViewProj, Vec3 camDelta,
		Vec3 cameraPos, float jitterX, float jitterY, boolean reset) {
		if (!running) {
			return;
		}
		// DLSS's depth is near / view depth (the motion shader), not Minecraft's: the matrices get the matching depth row.
		toDlssDepth(viewProj, temp).invert();
		toDlssDepth(prevViewProj, clipToPrevClip).translate((float)camDelta.x, (float)camDelta.y, (float)camDelta.z).mul(temp);
		toDlssDepth(projection, viewToClip);
		put(DlssNative.FG_VIEW_TO_CLIP, viewToClip);
		put(DlssNative.FG_CLIP_TO_VIEW, viewToClip.invert(temp));
		put(DlssNative.FG_CLIP_TO_PREV_CLIP, clipToPrevClip);
		put(DlssNative.FG_PREV_CLIP_TO_CLIP, clipToPrevClip.invert(temp));
		Matrix4f inverseRotation = viewRotation.invert(temp);
		putVec(DlssNative.FG_POS, axis.set((float)cameraPos.x, (float)cameraPos.y, (float)cameraPos.z));
		putVec(DlssNative.FG_UP, inverseRotation.transformDirection(axis.set(0.0F, 1.0F, 0.0F)));
		putVec(DlssNative.FG_RIGHT, inverseRotation.transformDirection(axis.set(1.0F, 0.0F, 0.0F)));
		putVec(DlssNative.FG_FWD, inverseRotation.transformDirection(axis.set(0.0F, 0.0F, -1.0F)));
		camera.set(ValueLayout.JAVA_FLOAT, DlssNative.FG_NEAR, NEAR);
		camera.set(ValueLayout.JAVA_FLOAT, DlssNative.FG_FAR, FAR);
		camera.set(ValueLayout.JAVA_FLOAT, DlssNative.FG_FOV, (float)(2.0 * Math.atan(1.0 / Math.abs(projection.m11()))));
		camera.set(ValueLayout.JAVA_FLOAT, DlssNative.FG_ASPECT, Math.abs(projection.m11() / projection.m00()));
		camera.set(ValueLayout.JAVA_FLOAT, DlssNative.FG_JITTER, jitterX);
		camera.set(ValueLayout.JAVA_FLOAT, DlssNative.FG_JITTER + 4, jitterY);
		camera.set(ValueLayout.JAVA_INT, DlssNative.FG_RESET, reset ? 1 : 0);
		DlssNative.fgCamera(camera);
	}

	/**
	 * The projection with its depth row replaced so clip z / clip w = near / view depth: reversed-Z with an infinite far
	 * plane, as the motion shader writes DLSS's depth. Clip w is the view depth, and the point's homogeneous coordinate
	 * (always 1) is the last row of the inverse projection applied to clip space, so depth row = near * that row.
	 */
	private static Matrix4f toDlssDepth(Matrix4fc projection, Matrix4f dest) {
		Matrix4f inverse = projection.invert(depthInverse);
		return dest.identity()
			.m02(NEAR * inverse.m03()).m12(NEAR * inverse.m13()).m22(NEAR * inverse.m23()).m32(NEAR * inverse.m33())
			.mul(projection);
	}

	private static void put(long offset, Matrix4fc m) {
		m.get(matrix);
		MemorySegment.copy(matrix, 0, camera, ValueLayout.JAVA_FLOAT, offset, matrix.length);
	}

	private static void putVec(long offset, Vector3f v) {
		camera.set(ValueLayout.JAVA_FLOAT, offset, v.x);
		camera.set(ValueLayout.JAVA_FLOAT, offset + 4, v.y);
		camera.set(ValueLayout.JAVA_FLOAT, offset + 8, v.z);
		camera.set(ValueLayout.JAVA_FLOAT, offset + 12, 0.0F);
	}

	/** F3 screen line, or null when off. */
	public static String statusLine() {
		if (!running) {
			return null;
		}
		float[] times = DlssNative.fgPresentTimes();
		// r = rendered fps, g = generated fps, t = total shown; fi = smoothed interval between rendered frames.
		return String.format(Locale.ROOT, "Frame gen (%s): %dr/%dg/%dt (fi %.1f ms)", DlssConfig.frameGeneration.name(), shownRealFps,
			shownGeneratedFps, shownRealFps + shownGeneratedFps, times == null ? 0.0F : times[2]);
	}
}
