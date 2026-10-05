package dev.mcupscaler;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

public final class UpscalerConfig {
	public enum Upscaler {
		/** NVIDIA DLSS Super Resolution (DLSS 4 transformer models): jittered camera + depth + motion vectors. RTX GPUs, Windows. */
		DLSS,
		/** AMD FSR 3.1 upscaling: the same inputs as DLSS, any GPU (the AMD library on Windows, its Metal port on macOS). */
		FSR,
		/** Apple MetalFX temporal scaler: the same inputs as DLSS. macOS. */
		METALFX,
		/** Apple MetalFX spatial scaler (single frame). macOS. */
		METALFX_SPATIAL,
		/** Plain bilinear stretch, for comparisons. */
		BILINEAR;

		public String displayName() {
			return switch (this) {
				case DLSS -> "NVIDIA DLSS";
				case FSR -> "AMD FSR 3.1";
				case METALFX -> "MetalFX Temporal";
				case METALFX_SPATIAL -> "MetalFX Spatial";
				case BILINEAR -> "Bilinear";
			};
		}

		public String description() {
			return switch (this) {
				case DLSS -> "NVIDIA's AI upscaler. Builds an upscaled image from detail in recent frames.";
				case FSR -> "AMD's upscaler. Builds an upscaled image from detail in recent frames.";
				case METALFX -> "Apple's upscaler. Builds an upscaled image from detail in recent frames.";
				case METALFX_SPATIAL -> "Apple's lightweight upscaler. Upscales each frame on its own, so fine details can shimmer.";
				case BILINEAR -> "A plain stretch to full resolution with no added detail. Looks blurry.";
			};
		}

		/** Builds on earlier frames: needs the jittered camera, depth and motion vectors. */
		public boolean temporal() {
			return this == DLSS || this == FSR || this == METALFX;
		}

		/** Exists on this operating system at all (the others are hidden from the settings). */
		public boolean onThisPlatform() {
			return switch (this) {
				case DLSS -> !Platform.MAC;
				case METALFX, METALFX_SPATIAL -> Platform.MAC;
				case FSR, BILINEAR -> true;
			};
		}

		/** Upscaler code in the Windows native frame description (struct Frame upscaler). */
		public int nativeCode() {
			return switch (this) {
				case DLSS -> 1;
				case FSR -> 2;
				default -> 0;
			};
		}
	}

	/** Quality modes, with NVIDIA's (and AMD's) render scales. */
	public enum Quality {
		/** One of the modes below, picked from the screen's height ({@link #autoQuality}). */
		AUTO(0.0F, 2),
		DLAA(1.0F, 5),
		QUALITY(2.0F / 3.0F, 2),
		BALANCED(0.58F, 1),
		PERFORMANCE(0.5F, 0),
		ULTRA_PERFORMANCE(1.0F / 3.0F, 3),
		CUSTOM(0.0F, 2);

		/** Per-axis render scale (0 for AUTO and CUSTOM: {@link #renderScale}). */
		public final float scale;
		/** NVSDK_NGX_PerfQuality_Value. */
		public final int ngxValue;

		Quality(float scale, int ngxValue) {
			this.scale = scale;
			this.ngxValue = ngxValue;
		}

		public String displayName() {
			return switch (this) {
				case AUTO -> "Auto";
				case DLAA -> upscaler == Upscaler.DLSS ? "DLAA (100%)" : "Native AA (100%)";
				case QUALITY -> "Quality (67%)";
				case BALANCED -> "Balanced (58%)";
				case PERFORMANCE -> "Performance (50%)";
				case ULTRA_PERFORMANCE -> "Ultra Performance (33%)";
				case CUSTOM -> "Custom";
			};
		}
	}

	/** DLSS model presets (NVSDK_NGX_DLSS_Hint_Render_Preset). */
	public enum Preset {
		AUTO(0, "Auto", "Picks the best model for your card: M on RTX 40 and 50 series, K on RTX 20 and 30 series."),
		E(5, "E (CNN, fastest)", "DLSS 3 CNN. The fastest, but softer, with more ghosting."),
		J(10, "J (transformer)", "DLSS 4 transformer. Great quality, moderate cost."),
		K(11, "K (transformer)", "DLSS 4 transformer. Great quality, moderate cost."),
		L(12, "L (transformer 2)", "DLSS 4.5 transformer. Best quality, but the slowest."),
		M(13, "M (transformer 2)", "DLSS 4.5 transformer. Better quality than K, slightly more cost.");

		public final int ngxValue;
		private final String displayName;
		private final String description;

		Preset(int ngxValue, String displayName, String description) {
			this.ngxValue = ngxValue;
			this.displayName = displayName;
			this.description = description;
		}

		public String displayName() {
			return displayName;
		}

		public String description() {
			return description;
		}
	}

	/** Frame generation: one generated frame between every two rendered frames. */
	public enum FrameGeneration {
		OFF("Off", "No frame generation."),
		DLSS("NVIDIA DLSS", "NVIDIA's AI frame generation."),
		FSR("AMD FSR 3.1", "AMD's frame generation."),
		METALFX("MetalFX", "Apple's frame generation.");

		private final String displayName;
		private final String description;

		FrameGeneration(String displayName, String description) {
			this.displayName = displayName;
			this.description = description;
		}

		public String displayName() {
			return displayName;
		}

		public String description() {
			return description;
		}

		public boolean onThisPlatform() {
			return switch (this) {
				case DLSS -> !Platform.MAC;
				case METALFX -> Platform.MAC;
				case OFF, FSR -> true;
			};
		}

		/** Generator code for the Windows native bridge (FgBackend). */
		public int nativeCode() {
			return ordinal();
		}
	}

	/** NVIDIA Reflex (VK_NV_low_latency2): the driver delays the start of each frame so frames don't queue up. */
	public enum Reflex {
		OFF("Off", "No latency reduction."),
		ON("On", "Reduces input lag."),
		BOOST("On + Boost", "Reduces input lag a little more, using slightly more power.");

		private final String displayName;
		private final String description;

		Reflex(String displayName, String description) {
			this.displayName = displayName;
			this.description = description;
		}

		public String displayName() {
			return displayName;
		}

		public String description() {
			return description;
		}
	}

	private static final Path DIR = FabricLoader.getInstance().getConfigDir();
	private static final Path FILE = DIR.resolve("mcupscaler.properties");

	public static boolean enabled = true;
	public static Upscaler upscaler = defaultUpscaler();
	public static Quality quality = Quality.AUTO;
	/** Per-axis render scale for {@link Quality#CUSTOM}. */
	public static float customScale = 0.75F;
	public static Preset preset = Preset.AUTO;
	/** Frame generation. Works with or without upscaling. */
	public static FrameGeneration frameGeneration = FrameGeneration.OFF;
	/** Sharpening after FSR and MetalFX (RCAS), 0 to 1; 0 is off. DLSS has none. */
	public static float sharpness = 1.0F;
	/** The F3 section was switched on once (after that, the player decides in the F3 debug options). */
	public static boolean debugEntryShown = false;
	/** Sample textures at output-resolution detail while upscaling temporally (negative LOD bias). */
	public static boolean textureLodCorrection = true;
	/** Switch off shader packs' waving plants/leaves/water while upscaling temporally (they have no motion vectors). */
	public static boolean stillPackFoliage = false;
	public static Reflex reflex = Reflex.ON;
	/** Write NVIDIA NGX logs to <game dir>/mcupscaler/ (troubleshooting). */
	public static boolean ngxLogging = false;

	/** Smallest render scale MetalFX Temporal accepts (it upscales at most about 3x), or 0 if unknown. Set by WorldUpscaler. */
	static volatile float metalFxMinScale;

	private UpscalerConfig() {
	}

	public static Upscaler defaultUpscaler() {
		return Platform.MAC ? Upscaler.METALFX : Upscaler.DLSS;
	}

	/** Per-axis render scale for the current quality mode. */
	public static float renderScale() {
		Quality mode = effectiveQuality();
		float scale = mode == Quality.CUSTOM ? Math.max(customScale, minCustomScale(upscaler)) : mode.scale;
		if (upscaler == Upscaler.METALFX && metalFxMinScale > 0.0F) {
			// Rounded up so integer sizes stay within the limit.
			scale = Math.max(scale, (float)Math.ceil(metalFxMinScale * 100.0F) / 100.0F);
		}
		return scale;
	}

	/** The quality mode in use: Auto resolved for the current screen. */
	public static Quality effectiveQuality() {
		return quality == Quality.AUTO ? autoQuality() : quality;
	}

	/**
	 * Auto: NVIDIA's recommended mode for the output height (ultrawides go by their height): Quality up to 1080p,
	 * Balanced at 1440p, Performance at 4K, Ultra Performance at 8K.
	 */
	public static Quality autoQuality() {
		Minecraft minecraft = Minecraft.getInstance();
		int height = minecraft == null || minecraft.getWindow() == null ? 1080 : minecraft.getWindow().getHeight();
		return height < 1300 ? Quality.QUALITY : height < 1900 ? Quality.BALANCED : height < 3000 ? Quality.PERFORMANCE
			: Quality.ULTRA_PERFORMANCE;
	}

	/** Quality mode for status lines: "Auto (Balanced)", "Custom 75%" or the mode's name. */
	public static String qualityName() {
		return switch (quality) {
			case AUTO -> "Auto (" + autoQuality().displayName().replaceAll(" \\(.*", "") + ")";
			case CUSTOM -> String.format(Locale.ROOT, "Custom %d%%", Math.round(renderScale() * 100));
			default -> quality.displayName();
		};
	}

	/**
	 * Lowest Custom render scale per upscaler. DLSS reports 50% to 100% for its adjustable modes (only Ultra Performance
	 * goes lower, fixed at 33%); the others take any size, so they go down to Ultra Performance (MetalFX Temporal is raised
	 * further to what it supports, see {@link #renderScale}).
	 */
	public static float minCustomScale(Upscaler upscaler) {
		return upscaler == Upscaler.DLSS ? 0.5F : 0.33F;
	}

	/** NGX quality value for the current settings (Custom: the mode whose defaults fit the scale best). */
	public static int ngxQuality() {
		Quality mode = effectiveQuality();
		if (mode != Quality.CUSTOM) {
			return mode.ngxValue;
		}
		float s = customScale;
		return s >= 0.99F ? Quality.DLAA.ngxValue : s >= 0.62F ? Quality.QUALITY.ngxValue : s >= 0.54F ? Quality.BALANCED.ngxValue : Quality.PERFORMANCE.ngxValue;
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
			upscaler = parseEnum(Upscaler.class, props.getProperty("upscaler"), defaultUpscaler());
			quality = parseEnum(Quality.class, props.getProperty("quality"), Quality.AUTO);
			customScale = clampScale(Float.parseFloat(props.getProperty("customScale", "0.75")));
			preset = parseEnum(Preset.class, props.getProperty("preset"), Preset.AUTO);
			frameGeneration = parseEnum(FrameGeneration.class, props.getProperty("frameGeneration"), FrameGeneration.OFF);
			sharpness = clamp01(Float.parseFloat(props.getProperty("sharpness", "1")));
			textureLodCorrection = Boolean.parseBoolean(props.getProperty("textureLodCorrection", "true"));
			stillPackFoliage = Boolean.parseBoolean(props.getProperty("stillPackFoliage", "false"));
			reflex = parseEnum(Reflex.class, props.getProperty("reflex"), Reflex.ON);
			ngxLogging = Boolean.parseBoolean(props.getProperty("ngxLogging", "false"));
			debugEntryShown = Boolean.parseBoolean(props.getProperty("debugEntryShown", "false"));
		} catch (IOException | IllegalArgumentException e) {
			UpscalerMod.LOGGER.warn("Failed to read {}, using defaults", FILE, e);
		}
		fitToPlatform();
	}

	/** Swaps choices that only exist on the other operating system for this one's counterparts (a config copied over). */
	private static void fitToPlatform() {
		if (!upscaler.onThisPlatform()) {
			upscaler = upscaler == Upscaler.METALFX_SPATIAL ? Upscaler.FSR : defaultUpscaler();
		}
		if (!frameGeneration.onThisPlatform()) {
			frameGeneration = Platform.MAC ? FrameGeneration.METALFX : FrameGeneration.DLSS;
		}
	}

	public static void save() {
		Properties props = new Properties();
		props.setProperty("enabled", Boolean.toString(enabled));
		props.setProperty("upscaler", upscaler.name());
		props.setProperty("quality", quality.name());
		props.setProperty("customScale", Float.toString(customScale));
		props.setProperty("preset", preset.name());
		props.setProperty("frameGeneration", frameGeneration.name());
		props.setProperty("sharpness", Float.toString(sharpness));
		props.setProperty("textureLodCorrection", Boolean.toString(textureLodCorrection));
		props.setProperty("stillPackFoliage", Boolean.toString(stillPackFoliage));
		props.setProperty("reflex", reflex.name());
		props.setProperty("ngxLogging", Boolean.toString(ngxLogging));
		props.setProperty("debugEntryShown", Boolean.toString(debugEntryShown));
		try (Writer writer = Files.newBufferedWriter(FILE)) {
			props.store(writer, "Upscaler. upscaler: DLSS, FSR, METALFX, METALFX_SPATIAL or BILINEAR. frameGeneration: OFF, DLSS, FSR or METALFX. "
				+ "quality: AUTO, DLAA, QUALITY, BALANCED, PERFORMANCE, ULTRA_PERFORMANCE or CUSTOM (customScale per axis)");
		} catch (IOException e) {
			UpscalerMod.LOGGER.warn("Failed to write {}", FILE, e);
		}
	}

	/** Older configs stored frame generation as true/false, which meant DLSS-G. */
	private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	private static float clamp01(float value) {
		return Math.max(0.0F, Math.min(1.0F, value));
	}

	/** The widest Custom range; {@link #renderScale} applies the selected upscaler's own minimum. */
	public static float clampScale(float scale) {
		return Math.max(0.33F, Math.min(1.0F, scale));
	}
}
