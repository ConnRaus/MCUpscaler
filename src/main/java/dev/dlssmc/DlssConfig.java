package dev.dlssmc;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

public final class DlssConfig {
	public enum Upscaler {
		/** NVIDIA DLSS Super Resolution (DLSS 4 transformer models): jittered camera + depth + motion vectors. RTX GPUs. */
		DLSS,
		/** AMD FSR 3.1 upscaling: the same inputs as DLSS, any Vulkan GPU. */
		FSR,
		/** Plain bilinear stretch, for comparisons. */
		BILINEAR;

		public String displayName() {
			return switch (this) {
				case DLSS -> "NVIDIA DLSS";
				case FSR -> "AMD FSR 3.1";
				case BILINEAR -> "Bilinear";
			};
		}

		public String description() {
			return switch (this) {
				case DLSS -> "Best image quality. Needs an NVIDIA RTX graphics card.";
				case FSR -> "Works on any graphics card. A bit softer than DLSS.";
				case BILINEAR -> "A simple stretch. Blurry; for comparison only.";
			};
		}

		public boolean temporal() {
			return this != BILINEAR;
		}

		/** Upscaler code in the native frame description (struct Frame upscaler). */
		public int nativeCode() {
			return switch (this) {
				case DLSS -> 1;
				case FSR -> 2;
				case BILINEAR -> 0;
			};
		}
	}

	/** DLSS quality modes, with NVIDIA's render scales. */
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
				case DLAA -> upscaler == Upscaler.FSR ? "Native AA (100%)" : "DLAA (100%)";
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

	/** Frame generation: one generated frame between every two rendered frames, by NVIDIA DLSS-G or AMD FSR. */
	public enum FrameGeneration {
		OFF("Off", "No frame generation."),
		DLSS("NVIDIA DLSS", "Best quality. Needs an RTX 40 or 50 series graphics card."),
		FSR("AMD FSR 3.1", "Works on any graphics card.");

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

		/** Generator code for the native bridge (FgBackend). */
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

	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("dlssmc.properties");

	public static boolean enabled = true;
	public static Upscaler upscaler = Upscaler.DLSS;
	public static Quality quality = Quality.AUTO;
	/** Per-axis render scale for {@link Quality#CUSTOM}. */
	public static float customScale = 0.75F;
	public static Preset preset = Preset.AUTO;
	/** Frame generation (DLSS-G or FSR). Works with or without upscaling. */
	public static FrameGeneration frameGeneration = FrameGeneration.OFF;
	/** FSR's sharpening (RCAS), 0 to 1; 0 is off. DLSS has none. */
	public static float fsrSharpness = 1.0F;
	/** The F3 section was switched on once (after that, the player decides in the F3 debug options). */
	public static boolean debugEntryShown = false;
	/** Sample textures at output-resolution detail while upscaling temporally (negative LOD bias). */
	public static boolean textureLodCorrection = true;
	/** Switch off shader packs' waving plants/leaves/water while upscaling temporally (they have no motion vectors). */
	public static boolean stillPackFoliage = false;
	public static Reflex reflex = Reflex.ON;
	/** Write NVIDIA NGX logs to <game dir>/dlssmc/ (troubleshooting). */
	public static boolean ngxLogging = false;

	private DlssConfig() {
	}

	/** Per-axis render scale for the current quality mode. */
	public static float renderScale() {
		Quality mode = effectiveQuality();
		return mode == Quality.CUSTOM ? Math.max(customScale, minCustomScale(upscaler)) : mode.scale;
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
	 * goes lower, fixed at 33%); FSR and Bilinear take any size, so they go down to FSR's Ultra Performance.
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
			upscaler = parseEnum(Upscaler.class, props.getProperty("upscaler"), Upscaler.DLSS);
			quality = parseEnum(Quality.class, props.getProperty("quality"), Quality.AUTO);
			customScale = clampScale(Float.parseFloat(props.getProperty("customScale", "0.75")));
			preset = parseEnum(Preset.class, props.getProperty("preset"), Preset.AUTO);
			frameGeneration = parseFrameGeneration(props.getProperty("frameGeneration"));
			fsrSharpness = Math.max(0.0F, Math.min(1.0F, Float.parseFloat(props.getProperty("fsrSharpness", "1"))));
			textureLodCorrection = Boolean.parseBoolean(props.getProperty("textureLodCorrection", "true"));
			stillPackFoliage = Boolean.parseBoolean(props.getProperty("stillPackFoliage", "false"));
			reflex = parseEnum(Reflex.class, props.getProperty("reflex"), Reflex.ON);
			ngxLogging = Boolean.parseBoolean(props.getProperty("ngxLogging", "false"));
			debugEntryShown = Boolean.parseBoolean(props.getProperty("debugEntryShown", "false"));
		} catch (IOException | IllegalArgumentException e) {
			DlssMod.LOGGER.warn("Failed to read {}, using defaults", FILE, e);
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
		props.setProperty("fsrSharpness", Float.toString(fsrSharpness));
		props.setProperty("textureLodCorrection", Boolean.toString(textureLodCorrection));
		props.setProperty("stillPackFoliage", Boolean.toString(stillPackFoliage));
		props.setProperty("reflex", reflex.name());
		props.setProperty("ngxLogging", Boolean.toString(ngxLogging));
		props.setProperty("debugEntryShown", Boolean.toString(debugEntryShown));
		try (Writer writer = Files.newBufferedWriter(FILE)) {
			props.store(writer, "DLSS for Minecraft. upscaler: DLSS, FSR or BILINEAR. frameGeneration: OFF, DLSS or FSR. quality: AUTO, DLAA, QUALITY, BALANCED, PERFORMANCE, ULTRA_PERFORMANCE or CUSTOM (customScale per axis)");
		} catch (IOException e) {
			DlssMod.LOGGER.warn("Failed to write {}", FILE, e);
		}
	}

	/** Older configs stored frame generation as true/false, which meant DLSS-G. */
	private static FrameGeneration parseFrameGeneration(String value) {
		if ("true".equalsIgnoreCase(value)) {
			return FrameGeneration.DLSS;
		}
		return parseEnum(FrameGeneration.class, value, FrameGeneration.OFF);
	}

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

	/** The widest Custom range (FSR's); {@link #renderScale} applies the selected upscaler's own minimum. */
	public static float clampScale(float scale) {
		return Math.max(0.33F, Math.min(1.0F, scale));
	}
}
