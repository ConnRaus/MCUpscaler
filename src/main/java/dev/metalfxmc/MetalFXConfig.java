package dev.metalfxmc;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

public final class MetalFXConfig {
	/** Which frame generator makes the in-between frames. */
	public enum FrameGenBackend {
		/** AMD FidelityFX FSR 3.1 frame generation (optical flow + frame interpolation, ported to Metal). macOS 14+. */
		FSR("AMD FSR 3"),
		/** Apple's MetalFX frame interpolator. macOS 26+. */
		METALFX("MetalFX");

		private final String displayName;

		FrameGenBackend(String displayName) {
			this.displayName = displayName;
		}

		public String displayName() {
			return displayName;
		}
	}

	public enum Upscaler {
		/** AMD FidelityFX Super Resolution 3.1 temporal upscaler (ported to Metal), with RCAS sharpening. */
		FSR,
		/** MetalFX temporal scaler: jittered camera + depth + motion vectors. */
		TEMPORAL,
		/** MetalFX spatial scaler (single frame). */
		SPATIAL,
		/** Plain bilinear stretch, for comparisons. */
		BILINEAR;

		public String displayName() {
			return switch (this) {
				case FSR -> "AMD FSR 3.1";
				case TEMPORAL -> "MetalFX Temporal";
				case SPATIAL -> "MetalFX Spatial";
				case BILINEAR -> "Bilinear";
			};
		}

		public String description() {
			return switch (this) {
				case FSR -> "AMD FSR 3.1. Rebuilds a full-resolution image from several frames rendered at the lower resolution.";
				case TEMPORAL -> "Apple MetalFX Temporal. Rebuilds a full-resolution image from several frames rendered at the lower "
					+ "resolution. Upscales at most 3x, so very low render scales are raised to fit.";
				case SPATIAL -> "Apple MetalFX Spatial. Upscales each frame on its own, without earlier frames. Edges look harder "
					+ "and fine detail can look blobby.";
				case BILINEAR -> "A plain stretch with no reconstruction. Blurry; useful for comparison.";
			};
		}
	}

	public static final float[] SCALE_PRESETS = {0.5F, 0.59F, 0.67F, 0.77F, 1.0F};

	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("metalfx.properties");

	public static boolean enabled = true;
	/** Fraction of the window resolution (per axis) the world is rendered at. */
	public static float renderScale = 0.5F;
	public static Upscaler upscaler = Upscaler.TEMPORAL;

	/** RCAS sharpening after the MetalFX/FSR upscalers (0 = off, 1 = full strength). */
	public static float sharpness = 1.0F;
	public static final float MAX_SHARPNESS = 1.0F;
	/**
	 * Frame generation (AMD FSR 3 or MetalFX, see frameGenBackend): show a generated frame between every two rendered frames.
	 * Independent of the upscaler: also works at native resolution.
	 */
	public static boolean frameGeneration = false;
	/** Multiplier for the motion vectors handed to the frame interpolator (debugging). */
	public static float frameGenMotionScale = 1.0F;
	public static FrameGenBackend frameGenBackend = FrameGenBackend.METALFX;
	/** The F3 section was switched on once (after that, the player decides in the F3 debug options). */
	public static boolean debugEntryShown = false;
	/** Sample textures at output-resolution detail while upscaling temporally (negative LOD bias). */
	public static boolean textureLodCorrection = true;
	/** Switch off shader packs' waving plants/leaves/water while upscaling temporally (they have no motion vectors). */
	public static boolean stillPackFoliage = false;

	// Advanced / debugging knobs for the temporal scaler (only in the properties file).
	/** Sign applied to the jitter handed to MetalFX, per axis (+1 or -1). */
	public static int jitterSignX = 1;
	public static int jitterSignY = 1;
	/** Texture row 0 is the top of the clip space (NDC y = +1) instead of the bottom. */
	public static boolean flipY = false;
	/** Show motion vectors instead of the upscaled image. */
	public static boolean debugMotion = false;
	/** Multiplier for the motion vectors handed to MetalFX, per axis (debugging). */
	public static float motionScaleX = 1.0F;
	public static float motionScaleY = 1.0F;
	/** Profiling switches (not persisted). */
	public static boolean debugSkipScaler = false;
	public static boolean debugNoJitter = false;

	private MetalFXConfig() {
	}

	public static void load() {
		if (!Files.exists(FILE)) {
			save();
			return;
		}
		Properties props = new Properties();
		try (Reader reader = Files.newBufferedReader(FILE)) {
			props.load(reader);
			enabled = Boolean.parseBoolean(props.getProperty("enabled", "true"));
			renderScale = clampScale(Float.parseFloat(props.getProperty("renderScale", "0.5")));
			upscaler = parseUpscaler(props.getProperty("upscaler", "TEMPORAL"));
			// Sharpness was 0.5 by default before full strength became the default; move those configs to the new default.
			int version = Integer.parseInt(props.getProperty("configVersion", "1"));
			sharpness = version < 2 && "0.5".equals(props.getProperty("sharpness")) ? 1.0F
				: Math.max(0.0F, Math.min(MAX_SHARPNESS, Float.parseFloat(props.getProperty("sharpness", "1.0"))));
			textureLodCorrection = Boolean.parseBoolean(props.getProperty("textureLodCorrection", "true"));
			stillPackFoliage = Boolean.parseBoolean(props.getProperty("stillPackFoliage", "false"));
			frameGeneration = Boolean.parseBoolean(props.getProperty("frameGeneration", "false"));
			frameGenMotionScale = Float.parseFloat(props.getProperty("frameGenMotionScale", "1.0"));
			debugEntryShown = Boolean.parseBoolean(props.getProperty("debugEntryShown", "false"));
			try {
				frameGenBackend = FrameGenBackend.valueOf(props.getProperty("frameGenBackend", "METALFX"));
			} catch (IllegalArgumentException e) {
				frameGenBackend = FrameGenBackend.METALFX;
			}
			jitterSignX = Integer.parseInt(props.getProperty("jitterSignX", "1")) < 0 ? -1 : 1;
			jitterSignY = Integer.parseInt(props.getProperty("jitterSignY", "1")) < 0 ? -1 : 1;
			flipY = Boolean.parseBoolean(props.getProperty("flipY", "false"));
			debugMotion = Boolean.parseBoolean(props.getProperty("debugMotion", "false"));
		} catch (IOException | IllegalArgumentException e) {
			MetalFXMod.LOGGER.warn("Failed to read {}, using defaults", FILE, e);
		}
	}

	public static void save() {
		Properties props = new Properties();
		props.setProperty("enabled", Boolean.toString(enabled));
		props.setProperty("renderScale", Float.toString(renderScale));
		props.setProperty("upscaler", upscaler.name());
		props.setProperty("sharpness", Float.toString(sharpness));
		props.setProperty("textureLodCorrection", Boolean.toString(textureLodCorrection));
		props.setProperty("stillPackFoliage", Boolean.toString(stillPackFoliage));
		props.setProperty("configVersion", "2");
		props.setProperty("frameGeneration", Boolean.toString(frameGeneration));
		props.setProperty("frameGenBackend", frameGenBackend.name());
		props.setProperty("debugEntryShown", Boolean.toString(debugEntryShown));
		// Debugging knobs: only kept in the file once someone changed them.
		if (frameGenMotionScale != 1.0F) {
			props.setProperty("frameGenMotionScale", Float.toString(frameGenMotionScale));
		}
		if (jitterSignX != 1 || jitterSignY != 1) {
			props.setProperty("jitterSignX", Integer.toString(jitterSignX));
			props.setProperty("jitterSignY", Integer.toString(jitterSignY));
		}
		if (flipY) {
			props.setProperty("flipY", "true");
		}
		if (debugMotion) {
			props.setProperty("debugMotion", "true");
		}
		try (Writer writer = Files.newBufferedWriter(FILE)) {
			props.store(writer, "MetalFX upscaling. renderScale is per axis (0.5 = quarter of the pixels). upscaler: FSR, TEMPORAL (MetalFX), SPATIAL or BILINEAR");
		} catch (IOException e) {
			MetalFXMod.LOGGER.warn("Failed to write {}", FILE, e);
		}
	}

	/** Upscaler by name; removed modes (SHARP, SHARP_SPATIAL, FAST_TEMPORAL) map to their closest remaining one. */
	private static Upscaler parseUpscaler(String value) {
		String name = value.trim().toUpperCase(Locale.ROOT);
		return switch (name) {
			case "METALFX" -> Upscaler.TEMPORAL;
			case "FAST_TEMPORAL", "SHARP" -> Upscaler.FSR;
			case "SHARP_SPATIAL" -> Upscaler.SPATIAL;
			default -> Upscaler.valueOf(name);
		};
	}

	public static float clampScale(float scale) {
		return Math.max(0.25F, Math.min(1.0F, scale));
	}
}
