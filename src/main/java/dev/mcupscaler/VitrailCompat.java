package dev.mcupscaler;

import com.mojang.renderpearl.api.textures.GpuTexture;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;

/**
 * Vitrail shader pack support. While DLSS runs, the pack's sources are patched as they load (see
 * {@link PackSourcePatches}): textures get a LOD bias for the render scale (as {@link ShaderPatches} does for vanilla and
 * Sodium), the pack's own anti-aliasing and vignette are switched off, and optionally its waving foliage. The patches are
 * baked into the sources, so the pack reloads when they need to change.
 */
public final class VitrailCompat {
	private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("vitrail");
	private static final long PACK_JITTER_TIMEOUT_NANOS = 500_000_000L;

	// What was baked into the pack the last time it was read; bias NaN = no pack read yet.
	private static volatile float bakedBias = Float.NaN;
	private static volatile boolean bakedAaOff;
	private static volatile boolean bakedStill;
	private static boolean reloadFailed;

	private static final PackVignette vignette = new PackVignette();

	/**
	 * Packs whose TAA jitters through a uniform from shaders.properties: its name, and the factor between it and the
	 * clip-space offset the pack's vertex shaders apply (IterationT: taaJitter * w; Photon: taa_offset * w * 0.66).
	 * <p>
	 * Such packs leave the jitter out of their matrices and out of their quick screen/view conversions, and add it back
	 * with that uniform only where their TAA is on (IterationT's screen-space shadows, for one). With the TAA switched
	 * off and the jitter in the matrices instead, those effects are off by the jitter every frame and whole areas of
	 * distant terrain flip between shadowed and lit. So for these packs the TAA stays on, its uniform carries DLSS's
	 * jitter ({@link #overridePackJitter}) with unjittered matrices, and only its resolve pass and sharpening are off.
	 */
	private static final Map<String, Float> PACK_JITTER_UNIFORMS = Map.of("taaJitter", 1.0F, "taa_offset", 0.66F);
	@Nullable
	private static volatile String packJitterName;
	private static volatile float packJitterScale = 1.0F;
	/** The pack's sources were patched for that mode. */
	private static volatile boolean bakedPackJitter;
	private static volatile long lastPackJitterNanos;
	@Nullable
	private static Field uniformValue;

	private VitrailCompat() {
	}

	// ------------------------------------------------------------------ what the settings want

	/** The texture LOD bias the current settings want for shader packs (0 = none). */
	private static float wantedBias() {
		if (!UpscalerConfig.enabled || !UpscalerConfig.upscaler.temporal() || !UpscalerConfig.textureLodCorrection || UpscalerConfig.renderScale() >= 0.999F) {
			return 0.0F;
		}
		// Rounded so tiny float differences don't trigger reloads.
		return Math.round((float)(Math.log(UpscalerConfig.renderScale()) / Math.log(2.0)) * 100.0F) / 100.0F;
	}

	/**
	 * DLSS anti-aliases the image itself (that is what its jitter is for), so the pack's own TAA and FXAA only blur and
	 * ghost on top of it: they are switched off while it is active.
	 */
	private static boolean wantedAaOff() {
		return UpscalerConfig.enabled && UpscalerConfig.upscaler.temporal() && WorldUpscaler.isTemporalKnownReady();
	}

	/**
	 * Waving foliage moves its vertices in the shader, which the depth-based motion vectors can't see: DLSS ghosts and
	 * smears it. Switched off while DLSS is active (an option).
	 */
	private static boolean wantedStill() {
		return wantedAaOff() && UpscalerConfig.stillPackFoliage;
	}

	// ------------------------------------------------------------------ source patching (Vitrail's loader threads)

	/** Vitrail starts reading a pack: forget what the last one said. */
	public static void packOpening() {
		vignette.reset();
		packJitterName = null;
		bakedPackJitter = false;
	}

	/** Called for every file Vitrail reads from a shader pack. */
	public static List<String> patchLines(Path path, List<String> lines) {
		Path fileName = path.getFileName();
		if (fileName == null || lines == null) {
			return lines;
		}
		String name = fileName.toString();
		if (name.equals("shaders.properties")) {
			readPackJitterUniform(lines);
			return lines;
		}
		if (!isShaderSource(name)) {
			return lines;
		}
		boolean aaOff = wantedAaOff();
		boolean still = wantedStill();
		float bias = wantedBias();
		bakedAaOff = aaOff;
		bakedStill = still;
		if (aaOff) {
			lines = disableAa(lines, name);
		}
		if (still) {
			lines = PackSourcePatches.commentOut(lines, PackSourcePatches.WAVING_DEFINE, "off: no motion vectors for the temporal upscaler");
		}
		boolean geometryFragment = name.endsWith(".fsh") && (name.startsWith("gbuffers_") || name.startsWith("dh_"));
		if (geometryFragment) {
			bakedBias = bias;
		}
		if (bias == 0.0F) {
			return lines;
		}
		// Included files and other programs only get the biased texture2D calls rewritten.
		lines = PackSourcePatches.rewriteBiasCalls(lines);
		return geometryFragment ? PackSourcePatches.scaleTextureLod(lines, (float)Math.pow(2.0, bias)) : lines;
	}

	private static boolean isShaderSource(String name) {
		return name.endsWith(".fsh") || name.endsWith(".vsh") || name.endsWith(".gsh") || name.endsWith(".csh") || name.endsWith(".glsl")
			|| name.endsWith(".inc");
	}

	private static List<String> disableAa(List<String> lines, String fileName) {
		if (packJitterName != null) {
			bakedPackJitter = true;
			lines = PackSourcePatches.commentOut(lines, PackSourcePatches.AA_DEFINE_BUT_TAA, "off: DLSS anti-aliases");
			return PackSourcePatches.disableTaaResolve(lines, fileName);
		}
		lines = PackSourcePatches.commentOut(lines, PackSourcePatches.AA_DEFINE, "off: DLSS anti-aliases");
		return PackSourcePatches.animateDither(PackSourcePatches.keepTaaNoise(lines));
	}

	/**
	 * A pack source line after Vitrail applied the pack's settings to it: the pack's vignette comes out (see
	 * {@link PackVignette}), and a pack-jitter pack's TAA stays on.
	 */
	public static String rewrittenLine(String line) {
		if (line == null) {
			return null;
		}
		if (packJitterName != null && line.contains("TAA") && PackSourcePatches.TAA_DEFINE_OFF.matcher(line).matches() && wantedAaOff()) {
			return "#define TAA // Upscaler: the pack's jitter carries the upscaler's (its resolve pass is off)";
		}
		// Only the Windows upscale pass redraws the vignette after upscaling; on macOS the pack keeps its own.
		return line.contains("VIGNETTE") ? vignette.rewrite(line, wantedAaOff() && Platform.WINDOWS) : line;
	}

	/** The vignette to draw after upscaling: {shape, a, b} into {@code out}, shape 0 = none. */
	public static void vignette(float[] out) {
		vignette.write(out, bakedAaOff);
	}

	// ------------------------------------------------------------------ the pack's own jitter uniform

	private static void readPackJitterUniform(List<String> lines) {
		for (String line : lines) {
			String t = line.trim();
			if (!t.startsWith("uniform.vec2.")) {
				continue;
			}
			int equals = t.indexOf('=');
			String name = equals < 0 ? "" : t.substring("uniform.vec2.".length(), equals).trim();
			Float scale = PACK_JITTER_UNIFORMS.get(name);
			if (scale != null) {
				packJitterName = name;
				packJitterScale = scale;
				UpscalerMod.LOGGER.info("The shader pack jitters through its '{}' uniform: DLSS's jitter goes through it", name);
				return;
			}
		}
	}

	/** Whether DLSS's jitter currently goes through the pack's uniform: then the world's matrices stay unjittered. */
	public static boolean packJitter() {
		return bakedPackJitter && bakedAaOff && System.nanoTime() - lastPackJitterNanos < PACK_JITTER_TIMEOUT_NANOS;
	}

	/** After Vitrail evaluated the pack's custom uniforms for the frame: its jitter uniform gets DLSS's jitter. */
	public static void overridePackJitter(@Nullable Map<String, ?> declared) {
		String name = packJitterName;
		Object node = name == null || !bakedPackJitter || declared == null ? null : declared.get(name);
		if (node == null) {
			return;
		}
		try {
			Field field = uniformValue != null ? uniformValue : findField(node.getClass(), "object");
			if (field == null) {
				packJitterName = null;
				return;
			}
			uniformValue = field;
			if (field.get(node) instanceof Vector2f value) {
				value.set(WorldUpscaler.jitterNdcX() / packJitterScale, WorldUpscaler.jitterNdcY() / packJitterScale);
				lastPackJitterNanos = System.nanoTime();
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			packJitterName = null;
			UpscalerMod.LOGGER.warn("Could not set the shader pack's jitter uniform", e);
		}
	}

	@Nullable
	private static Field findField(Class<?> type, String name) {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			try {
				Field field = c.getDeclaredField(name);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ reloading and Distant Horizons depth

	/** Reloads the shader pack if what was baked into it no longer matches the settings. Call between frames. */
	public static void tick() {
		if (!PRESENT || reloadFailed || Float.isNaN(bakedBias)) {
			return;
		}
		float bias = wantedBias();
		boolean aaOff = wantedAaOff();
		boolean still = wantedStill();
		if (bias == bakedBias && aaOff == bakedAaOff && still == bakedStill) {
			return;
		}
		bakedBias = bias;
		bakedAaOff = aaOff;
		bakedStill = still;
		try {
			Method forget = Class.forName("dev.vitrail.pack.source.KeptPack").getDeclaredMethod("forget");
			forget.setAccessible(true);
			forget.invoke(null);
			Class.forName("dev.vitrail.render.PackChoice").getMethod("reload", Path.class).invoke(null, FabricLoader.getInstance().getGameDir());
			UpscalerMod.LOGGER.info("Reloaded the shader pack with texture LOD bias {}, own anti-aliasing {}, waving foliage {}", bias,
				aaOff ? "off" : "on", still ? "off" : "on");
		} catch (ReflectiveOperationException | RuntimeException e) {
			reloadFailed = true;
			UpscalerMod.LOGGER.warn("Could not reload the Vitrail shader pack for texture LOD correction", e);
		}
	}

	/** Called right after Vitrail drew the far terrain. */
	public static void captureDistantDepth() {
		VitrailDistantDepth.capture();
	}

	/** This frame's Distant Horizons depth, or null; see {@link VitrailDistantDepth#take}. */
	@Nullable
	static GpuTexture distantDepth(Vector2f pair) {
		return VitrailDistantDepth.take(pair);
	}
}
