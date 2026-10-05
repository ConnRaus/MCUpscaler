package dev.mcupscaler;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * Java (FFM) bindings for the native bridge (src/native, see bridge.h). Handles and pointers are passed as plain 64-bit integers.
 * The bridge and NVIDIA's DLSS libraries are bundled in the jar under /natives/windows-x64 and extracted to
 * {@code <game dir>/mcupscaler/natives} on first use.
 */
public final class DlssNative {
	/** Bits of {@link #init}'s result. */
	public static final int INIT_NGX = 1, INIT_SUPER_RESOLUTION = 2, INIT_FRAME_GENERATION = 4, INIT_FSR = 8;
	/** Frame generators for {@link #fgRecord} (FgBackend in the bridge). */
	public static final int FG_BACKEND_NONE = 0, FG_BACKEND_DLSS = 1, FG_BACKEND_FSR = 2;
	/** Size of a texture description (struct Tex in the bridge). */
	public static final long TEX_SIZE = 32;
	/** Offsets in struct Frame. */
	public static final long FRAME_SIZE = 392;
	public static final long FRAME_COLOR = 0, FRAME_DEPTH = 32, FRAME_HAND = 64, FRAME_OUTPUT = 96;
	public static final long FRAME_INV_VIEW_PROJ = 128, FRAME_PREV_VIEW_PROJ = 192, FRAME_CAM_DELTA = 256;
	public static final long FRAME_OBJ_MIN = 272, FRAME_OBJ_MAX = 288, FRAME_OBJ_DELTA = 304;
	public static final long FRAME_JITTER = 320, FRAME_RESET = 328, FRAME_QUALITY = 332, FRAME_PRESET = 336;
	public static final long FRAME_Z_ZERO_TO_ONE = 340, FRAME_FRAME_TIME = 344, FRAME_UPSCALER = 348;
	public static final long FRAME_BOXES = 352, FRAME_BOX_COUNT = 360, FRAME_VIGNETTE = 364;
	public static final long FRAME_SHARPNESS = 376, FRAME_FOV = 380, FRAME_NEAR = 384;
	/** Moving entities the motion vectors know about: {min, max, delta} float4s each. */
	public static final int MAX_BOXES = 64, BOX_BYTES = 48;
	/** Offsets in struct FgCamera (frame generation). */
	public static final long FG_CAMERA_SIZE = 352;
	public static final long FG_VIEW_TO_CLIP = 0, FG_CLIP_TO_VIEW = 64, FG_CLIP_TO_PREV_CLIP = 128, FG_PREV_CLIP_TO_CLIP = 192;
	public static final long FG_POS = 256, FG_UP = 272, FG_RIGHT = 288, FG_FWD = 304, FG_NEAR = 320, FG_FAR = 324, FG_FOV = 328;
	public static final long FG_ASPECT = 332, FG_JITTER = 336, FG_RESET = 344;
	/** Offsets in struct FgSubmit. */
	public static final long FG_SUBMIT_SIZE = 40;
	public static final long FG_READY_SEMAPHORE = 0, FG_READY_VALUE = 8, FG_PRESENTED_SEMAPHORE = 16, FG_PRESENTED_VALUE = 24, FG_INTERPOLATED = 32;

	private static final String[] LIBRARIES = {"dlss_bridge.dll", "nvngx_dlss.dll", "nvngx_dlssg.dll", "amd_fidelityfx_vk.dll"};

	private static MethodHandle init;
	private static MethodHandle loadShaders;
	private static MethodHandle upscale;
	private static MethodHandle mergePackDepth;
	private static MethodHandle mergeDistantDepth;
	private static MethodHandle frameGenMaxMultiFrame;
	private static MethodHandle lastError;
	private static MethodHandle gpuTimes;
	private static MethodHandle shutdown;
	private static MethodHandle queueLock;
	private static MethodHandle queueUnlock;
	private static MethodHandle fgStart;
	private static MethodHandle fgStop;
	private static MethodHandle fgSurfaceState;
	private static MethodHandle fgCamera;
	private static MethodHandle fgRecord;
	private static MethodHandle fgQueue;
	private static MethodHandle fsrVersion;
	private static MethodHandle optimalSettings;
	private static boolean loaded;
	private static boolean failed;
	private static Path nativeDir;

	private static final Arena ARENA = Arena.global();
	private static final MemorySegment errorBuffer = ARENA.allocate(1024);
	private static final MemorySegment scratch = ARENA.allocate(32, 8);

	private DlssNative() {
	}

	public static synchronized boolean load() {
		if (loaded || failed) {
			return loaded;
		}
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
			|| !"amd64".equals(System.getProperty("os.arch"))) {
			failed = true;
			UpscalerMod.LOGGER.warn("DLSS needs 64-bit Windows; the DLSS mod is inactive");
			return false;
		}
		try {
			nativeDir = FabricLoader.getInstance().getGameDir().resolve("mcupscaler").resolve("natives");
			Files.createDirectories(nativeDir);
			for (String name : LIBRARIES) {
				extract(name);
			}
			Linker linker = Linker.nativeLinker();
			SymbolLookup lookup = SymbolLookup.libraryLookup(nativeDir.resolve("dlss_bridge.dll"), ARENA);
			ValueLayout J = ValueLayout.JAVA_LONG, I = ValueLayout.JAVA_INT, F = ValueLayout.JAVA_FLOAT;
			init = linker.downcallHandle(lookup.findOrThrow("dlss_init"), FunctionDescriptor.of(I, J, J, J, J, J, J, J, I));
			loadShaders = linker.downcallHandle(lookup.findOrThrow("dlss_load_shaders"), FunctionDescriptor.of(I, J, I, J, I, J, I, J, I));
			upscale = linker.downcallHandle(lookup.findOrThrow("dlss_upscale"), FunctionDescriptor.of(I, J, J));
			mergePackDepth = linker.downcallHandle(lookup.findOrThrow("dlss_merge_pack_depth"), FunctionDescriptor.of(I, J, J, J, J, J, J));
			mergeDistantDepth = linker.downcallHandle(lookup.findOrThrow("dlss_merge_distant_depth"), FunctionDescriptor.of(I, J, J, J, F, F));
			frameGenMaxMultiFrame = linker.downcallHandle(lookup.findOrThrow("dlss_frame_gen_max_multi_frame"), FunctionDescriptor.of(I));
			gpuTimes = linker.downcallHandle(lookup.findOrThrow("dlss_gpu_times"), FunctionDescriptor.of(I, J));
			lastError = linker.downcallHandle(lookup.findOrThrow("dlss_last_error"), FunctionDescriptor.of(I, J, I));
			shutdown = linker.downcallHandle(lookup.findOrThrow("dlss_shutdown"), FunctionDescriptor.ofVoid());
			queueLock = linker.downcallHandle(lookup.findOrThrow("dlss_queue_lock"), FunctionDescriptor.ofVoid());
			queueUnlock = linker.downcallHandle(lookup.findOrThrow("dlss_queue_unlock"), FunctionDescriptor.ofVoid());
			fgStart = linker.downcallHandle(lookup.findOrThrow("dlss_fg_start"), FunctionDescriptor.of(I, J, I, I, J, I, J, I, I, I));
			fgStop = linker.downcallHandle(lookup.findOrThrow("dlss_fg_stop"), FunctionDescriptor.ofVoid());
			fgSurfaceState = linker.downcallHandle(lookup.findOrThrow("dlss_fg_surface_state"), FunctionDescriptor.of(I));
			fgCamera = linker.downcallHandle(lookup.findOrThrow("dlss_fg_camera"), FunctionDescriptor.ofVoid(J));
			fgRecord = linker.downcallHandle(lookup.findOrThrow("dlss_fg_record"), FunctionDescriptor.of(I, J, J, I, I, J));
			fgQueue = linker.downcallHandle(lookup.findOrThrow("dlss_fg_queue"), FunctionDescriptor.ofVoid(J, I, J));
			fsrVersion = linker.downcallHandle(lookup.findOrThrow("dlss_fsr_version"), FunctionDescriptor.of(I, J, I));
			optimalSettings = linker.downcallHandle(lookup.findOrThrow("dlss_optimal_settings"), FunctionDescriptor.of(I, I, I, I, J));
			loaded = true;
			return true;
		} catch (IOException | RuntimeException e) {
			failed = true;
			UpscalerMod.LOGGER.error("Could not load the DLSS native bridge", e);
			return false;
		}
	}

	/** Copies a bundled library out of the jar unless an identical copy is already there. */
	private static void extract(String name) throws IOException {
		String resource = "/natives/windows-x64/" + name;
		Path target = nativeDir.resolve(name);
		try (InputStream in = DlssNative.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IOException("missing " + resource + " in the mod jar");
			}
			byte[] bytes = in.readAllBytes();
			if (Files.exists(target) && Files.size(target) == bytes.length && Arrays.equals(Files.readAllBytes(target), bytes)) {
				return;
			}
			Path temp = Files.createTempFile(nativeDir, name, ".tmp");
			Files.write(temp, bytes);
			try {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				// In use by another running instance: that copy is the same bundle version or it would not be loaded.
				Files.deleteIfExists(temp);
				if (!Files.exists(target)) {
					throw e;
				}
			}
		}
	}

	private static MemorySegment wide(String s) {
		return ARENA.allocateFrom(s + "\0", StandardCharsets.UTF_16LE);
	}

	public static boolean isLoaded() {
		return loaded;
	}

	/** Every use of Minecraft's Vulkan queue holds this lock while frame generation's present thread may use it too. */
	public static void queueLock() {
		try {
			queueLock.invokeExact();
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	public static void queueUnlock() {
		try {
			queueUnlock.invokeExact();
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/**
	 * Starts frame generation's present thread on a swapchain. queue: where frames are presented (best not the graphics
	 * queue, which delays them behind rendering).
	 */
	public static boolean fgStart(long queue, int queueFamily, int graphicsFamily, long swapchain, int swapFormat, long[] images, int width,
		int height) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment list = arena.allocateFrom(ValueLayout.JAVA_LONG, images);
			return (int)fgStart.invokeExact(queue, queueFamily, graphicsFamily, swapchain, swapFormat, list.address(), images.length, width, height) == 1;
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	public static void fgStop() {
		try {
			fgStop.invokeExact();
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/** 0 fine, 1 suboptimal, 2 out of date. */
	public static int fgSurfaceState() {
		try {
			return (int)fgSurfaceState.invokeExact();
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	public static void fgCamera(MemorySegment camera) {
		try {
			fgCamera.invokeExact(camera.address());
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/** backend: FG_BACKEND_*, NONE = present the real frame only. */
	public static boolean fgRecord(long commandBuffer, MemorySegment finalTex, int backend, boolean notGame, MemorySegment out) {
		try {
			return (int)fgRecord.invokeExact(commandBuffer, finalTex.address(), backend, notGame ? 1 : 0, out.address()) == 1;
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/** interpolated: a generated frame goes before the real one; presentId: Reflex's, 0 = none. */
	public static void fgQueue(long frameId, boolean interpolated, long presentId) {
		try {
			fgQueue.invokeExact(frameId, interpolated ? 1 : 0, presentId);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/** DLSS's render size range for an output size and NGX quality: {optimal w, h, max w, h, min w, h}, or null. */
	public static int @Nullable [] optimalSettings(int quality, int outW, int outH) {
		try {
			if (!loaded || (int)optimalSettings.invokeExact(quality, outW, outH, scratch.address()) != 1) {
				return null;
			}
			return scratch.asSlice(0, 24).toArray(ValueLayout.JAVA_INT);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/** FSR's version ("3.1.4"), known once FSR upscaling has run; "" before. */
	public static String fsrVersion() {
		try {
			if (!loaded) {
				return "";
			}
			MemorySegment buf = errorBuffer;
			int len = (int)fsrVersion.invokeExact(buf.address(), (int)buf.byteSize());
			return len <= 0 ? "" : new String(buf.asSlice(0, len).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/**
	 * Bitmask: 1 = NGX initialised, 2 = DLSS Super Resolution available, 4 = DLSS Frame Generation available, 8 = AMD FSR
	 * loaded; -1 = failure.
	 */
	public static int init(long instance, long physicalDevice, long device, long gipa, long gdpa, boolean logging) {
		try {
			Path dataDir = nativeDir.getParent();
			return (int)init.invokeExact(instance, physicalDevice, device, gipa, gdpa, wide(nativeDir.toString()).address(),
				wide(dataDir.toString()).address(), logging ? 1 : 0);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	public static boolean loadShaders(byte[][] spirv) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment[] code = new MemorySegment[spirv.length];
			for (int i = 0; i < spirv.length; i++) {
				code[i] = arena.allocate(spirv[i].length, 4);
				code[i].copyFrom(MemorySegment.ofArray(spirv[i]));
			}
			return (int)loadShaders.invokeExact(code[0].address(), spirv[0].length, code[1].address(), spirv[1].length, code[2].address(),
				spirv[2].length, code[3].address(), spirv[3].length) == 1;
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/** Motion vectors and depth, and DLSS or FSR if the frame says so (FRAME_UPSCALER). 1 = done, 0 = failed this frame, -1 = unusable. */
	public static int upscale(long commandBuffer, MemorySegment frame) {
		try {
			return (int)upscale.invokeExact(commandBuffer, frame.address());
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	public static int mergePackDepth(long commandBuffer, MemorySegment pre, MemorySegment post, MemorySegment fin, MemorySegment preTl, MemorySegment postTl) {
		try {
			return (int)mergePackDepth.invokeExact(commandBuffer, pre.address(), post.address(), fin.address(), preTl.address(), postTl.address());
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	public static int mergeDistantDepth(long commandBuffer, MemorySegment scene, MemorySegment distant, float pairA, float pairB) {
		try {
			return (int)mergeDistantDepth.invokeExact(commandBuffer, scene.address(), distant.address(), pairA, pairB);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	public static int frameGenMaxMultiFrame() {
		try {
			return (int)frameGenMaxMultiFrame.invokeExact();
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/** Smoothed GPU milliseconds {motion vectors, DLSS, copy}, or null when not measured. */
	public static float[] gpuTimes() {
		if (!loaded) {
			return null;
		}
		try {
			if ((int)gpuTimes.invokeExact(scratch.address()) != 1) {
				return null;
			}
			return scratch.asSlice(0, 12).toArray(ValueLayout.JAVA_FLOAT);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	public static String lastError() {
		if (!loaded) {
			return "native bridge not loaded";
		}
		try {
			int length = (int)lastError.invokeExact(errorBuffer.address(), (int)errorBuffer.byteSize());
			return new String(errorBuffer.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
		} catch (Throwable t) {
			return t.toString();
		}
	}

	public static void shutdown() {
		if (!loaded) {
			return;
		}
		try {
			shutdown.invokeExact();
		} catch (Throwable t) {
			UpscalerMod.LOGGER.warn("DLSS shutdown failed", t);
		}
	}

	/**
	 * Writes a texture description (struct Tex) at {@code offset}: image, view, VkFormat, size. Returns false if the
	 * texture isn't a Vulkan one.
	 */
	public static boolean writeTex(MemorySegment segment, long offset, GpuTexture texture, GpuTextureView view) {
		if (!(texture instanceof VulkanGpuTexture vk) || !(view instanceof VulkanGpuTextureView vkView) || vk.isClosed()) {
			return false;
		}
		segment.set(ValueLayout.JAVA_LONG, offset, vk.vkImage());
		segment.set(ValueLayout.JAVA_LONG, offset + 8, vkView.vkImageView());
		segment.set(ValueLayout.JAVA_INT, offset + 16, VulkanConst.toVk(texture.getFormat()));
		segment.set(ValueLayout.JAVA_INT, offset + 20, texture.getWidth(0));
		segment.set(ValueLayout.JAVA_INT, offset + 24, texture.getHeight(0));
		segment.set(ValueLayout.JAVA_INT, offset + 28, 0);
		return true;
	}
}
