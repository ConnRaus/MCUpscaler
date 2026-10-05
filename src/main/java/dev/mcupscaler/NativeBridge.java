package dev.mcupscaler;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Java (FFM) bindings for src/native/mac/metalfx_bridge.m. Pointers are passed as plain 64-bit integers.
 */
public final class NativeBridge {
	private static MethodHandle init;
	private static MethodHandle upscale;
	private static MethodHandle upscaleTemporal;
	private static MethodHandle mergePackDepth;
	private static MethodHandle mergeDistantDepth;
	private static MethodHandle temporalSupported;
	private static MethodHandle temporalMaxScale;
	private static MethodHandle frameInterpolationSupported;
	private static MethodHandle metalFxFrameInterpolationSupported;
	private static MethodHandle fgSubmit;
	private static MethodHandle fgAttach;
	private static MethodHandle fgDetach;
	private static MethodHandle fgReserve;
	private static MethodHandle fgTakeGenerated;
	private static MethodHandle fgDump;
	private static MethodHandle lastError;
	private static MethodHandle gpuErrorCount;
	private static MethodHandle takeGpuMicros;
	private static MethodHandle takeStageMicros;
	private static boolean loaded;

	private NativeBridge() {
	}

	public static synchronized boolean load() {
		if (loaded) {
			return true;
		}
		try {
			Path lib = extract();
			Linker linker = Linker.nativeLinker();
			SymbolLookup lookup = SymbolLookup.libraryLookup(lib, Arena.global());
			init = linker.downcallHandle(lookup.findOrThrow("mfx_init"), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
			upscale = linker.downcallHandle(
				lookup.findOrThrow("mfx_upscale"),
				FunctionDescriptor.of(
					ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
					ValueLayout.JAVA_FLOAT
				)
			);
			upscaleTemporal = linker.downcallHandle(
				lookup.findOrThrow("mfx_upscale_temporal"),
				FunctionDescriptor.of(
					ValueLayout.JAVA_INT,
					ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
					ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG
				)
			);
			mergePackDepth = linker.downcallHandle(
				lookup.findOrThrow("mfx_merge_pack_depth"),
				FunctionDescriptor.of(
					ValueLayout.JAVA_INT,
					ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
					ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG
				)
			);
			mergeDistantDepth = linker.downcallHandle(
				lookup.findOrThrow("mfx_merge_distant_depth"),
				FunctionDescriptor.of(
					ValueLayout.JAVA_INT,
					ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
					ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG
				)
			);
			temporalSupported = linker.downcallHandle(lookup.findOrThrow("mfx_temporal_supported"), FunctionDescriptor.of(ValueLayout.JAVA_INT));
			temporalMaxScale = linker.downcallHandle(lookup.findOrThrow("mfx_temporal_max_scale"), FunctionDescriptor.of(ValueLayout.JAVA_FLOAT));
			frameInterpolationSupported = linker.downcallHandle(
				lookup.findOrThrow("mfx_frame_interpolation_supported"), FunctionDescriptor.of(ValueLayout.JAVA_INT)
			);
			metalFxFrameInterpolationSupported = linker.downcallHandle(
				lookup.findOrThrow("mfx_metalfx_frame_interpolation_supported"), FunctionDescriptor.of(ValueLayout.JAVA_INT)
			);
			fgSubmit = linker.downcallHandle(
				lookup.findOrThrow("mfx_fg_submit"),
				FunctionDescriptor.of(
					ValueLayout.JAVA_INT,
					ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
					ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG
				)
			);
			fgAttach = linker.downcallHandle(lookup.findOrThrow("mfx_fg_attach"), FunctionDescriptor.of(ValueLayout.JAVA_INT));
			fgDetach = linker.downcallHandle(lookup.findOrThrow("mfx_fg_detach"), FunctionDescriptor.ofVoid());
			fgReserve = linker.downcallHandle(lookup.findOrThrow("mfx_fg_reserve"), FunctionDescriptor.of(ValueLayout.JAVA_INT));
			fgTakeGenerated = linker.downcallHandle(lookup.findOrThrow("mfx_fg_take_generated"), FunctionDescriptor.of(ValueLayout.JAVA_INT));
			fgDump = linker.downcallHandle(lookup.findOrThrow("mfx_fg_dump"), FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT));
			lastError = linker.downcallHandle(
				lookup.findOrThrow("mfx_last_error"), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
			);
			gpuErrorCount = linker.downcallHandle(lookup.findOrThrow("mfx_gpu_error_count"), FunctionDescriptor.of(ValueLayout.JAVA_INT));
			takeGpuMicros = linker.downcallHandle(lookup.findOrThrow("mfx_take_gpu_micros"), FunctionDescriptor.of(ValueLayout.JAVA_INT));
			takeStageMicros = linker.downcallHandle(
				lookup.findOrThrow("mfx_take_stage_micros"), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
			);
			loaded = true;
		} catch (Throwable t) {
			UpscalerMod.LOGGER.error("Failed to load MetalFX native bridge", t);
		}
		return loaded;
	}

	private static Path extract() throws IOException {
		try (InputStream in = NativeBridge.class.getResourceAsStream("/natives/libmetalfx_bridge.dylib")) {
			if (in == null) {
				throw new IOException("libmetalfx_bridge.dylib missing from mod jar");
			}
			Path tmp = Files.createTempFile("metalfx_bridge", ".dylib");
			tmp.toFile().deleteOnExit();
			Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
			return tmp;
		}
	}

	/** @return 1 supported, 0 unsupported, -1 error */
	public static int init(long mtlDevice) {
		try {
			return (int)init.invokeExact(mtlDevice);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/** See mfx_upscale in metalfx_bridge.m for the return contract. */
	public static int upscale(long inTexture, long dstTexture, long sharedEvent, long waitValue, long signalValue, float sharpness) {
		try {
			return (int)upscale.invokeExact(inTexture, dstTexture, sharedEvent, waitValue, signalValue, sharpness);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/** Same return contract as {@link #upscale}; {@code params} points to a MfxTemporalParams struct. */
	public static int upscaleTemporal(
		long inTexture, long sceneDepth, long handDepth, long dstTexture, long sharedEvent, long waitValue, long signalValue, long params
	) {
		try {
			return (int)upscaleTemporal.invokeExact(inTexture, sceneDepth, handDepth, dstTexture, sharedEvent, waitValue, signalValue, params);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/** Same return contract as {@link #upscale}; rewrites {@code preHand} in place (see mfx_merge_pack_depth). */
	public static int mergePackDepth(
		long preHand, long postHand, long finalDepth, long preTranslucentHand, long postTranslucentHand, long sharedEvent, long waitValue, long signalValue
	) {
		try {
			return (int)mergePackDepth.invokeExact(preHand, postHand, finalDepth, preTranslucentHand, postTranslucentHand, sharedEvent, waitValue, signalValue);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/** Same return contract as {@link #upscale}; rewrites {@code sceneDepth} in place (see mfx_merge_distant_depth). */
	public static int mergeDistantDepth(long sceneDepth, long distantDepth, float pairA, float pairB, long sharedEvent, long waitValue, long signalValue) {
		try {
			return (int)mergeDistantDepth.invokeExact(sceneDepth, distantDepth, pairA, pairB, sharedEvent, waitValue, signalValue);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	public static boolean temporalSupported() {
		try {
			return (int)temporalSupported.invokeExact() == 1;
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/** Largest per-axis upscale factor MetalFX temporal accepts on this device. */
	public static float temporalMaxScale() {
		try {
			return (float)temporalMaxScale.invokeExact();
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/** The MetalFX frame-generation backend is available (macOS 26+). */
	public static boolean metalFxFrameInterpolationSupported() {
		try {
			return (int)metalFxFrameInterpolationSupported.invokeExact() == 1;
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	public static boolean frameInterpolationSupported() {
		try {
			return (int)frameInterpolationSupported.invokeExact() == 1;
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/**
	 * Frame generation: queues this frame for the presenter. {@code finalTexture} is Minecraft's finished frame;
	 * {@code worldTexture} the world image before the HUD (0 = show only the final frame), {@code depthTexture} /
	 * {@code handTexture} the scene and hand depth, {@code params} a MfxFrameGenParams struct. Returns 1 if a generated frame
	 * was queued as well, 0 if only the real one, -1 on error (see {@link #lastError}; the semaphore is still signalled),
	 * -2 if nothing was submitted (don't wait).
	 */
	public static int fgSubmit(
		long worldTexture, long finalTexture, long depthTexture, long handTexture, long sharedEvent, long waitValue, long signalValue, long params
	) {
		try {
			return (int)fgSubmit.invokeExact(worldTexture, finalTexture, depthTexture, handTexture, sharedEvent, waitValue, signalValue, params);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/** Shows the frame generation presenter over the game: 1 once it is live, 0 while it is being set up, -1 if it can't be. */
	public static int fgAttach() {
		try {
			return (int)fgAttach.invokeExact();
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	public static void fgDetach() {
		try {
			fgDetach.invokeExact();
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/** Waits (briefly) until the presenter can take another frame; this paces the game to the display. */
	public static int fgReserve() {
		try {
			return (int)fgReserve.invokeExact();
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/** Generated frames the presenter showed since the last call. */
	/** Debugging: saves the next {@code frames} presented frames as PNGs in $MFX_FG_DUMP_DIR (no-op without it). */
	public static void fgDump(int frames) {
		try {
			fgDump.invokeExact(frames);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	public static int fgTakeGenerated() {
		try {
			return (int)fgTakeGenerated.invokeExact();
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	public static int gpuErrorCount() {
		try {
			return (int)gpuErrorCount.invokeExact();
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/** Average GPU time (microseconds) of the MetalFX command buffers since the last call, or -1. */
	public static int takeGpuMicros() {
		try {
			return (int)takeGpuMicros.invokeExact();
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/** Profiling (MFX_PROFILE=1): average GPU microseconds of a temporal stage (0 motion, 1 scaler, 2 output), or -1. */
	public static int takeStageMicros(int stage) {
		try {
			return (int)takeStageMicros.invokeExact(stage);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	public static String lastError() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment buf = arena.allocate(1024);
			int unused = (int)lastError.invokeExact(buf, 1024);
			return buf.getString(0);
		} catch (Throwable t) {
			return "<unavailable: " + t + ">";
		}
	}
}
