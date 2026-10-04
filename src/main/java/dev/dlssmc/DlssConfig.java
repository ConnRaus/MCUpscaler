package dev.dlssmc;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

public final class DlssConfig {
	public enum Upscaler {
		/** NVIDIA DLSS Super Resolution (DLSS 4 transformer models): jittered camera + depth + motion vectors. RTX GPUs. */
		DLSS,
		/** Plain bilinear stretch, for comparisons. */
		BILINEAR;

		public String displayName() {
			return switch (this) {
				case DLSS -> "NVIDIA DLSS";
				case BILINEAR -> "Bilinear";
			};
		}

		public String description() {
			return switch (this) {
				case DLSS -> "NVIDIA DLSS Super Resolution (transformer model). Rebuilds a full-resolution image from several frames "
					+ "rendered at the lower resolution, and anti-aliases it. Needs an NVIDIA RTX graphics card.";
				case BILINEAR -> "A plain stretch with no reconstruction. Blurry; useful for comparison.";
			};
		}

		public boolean temporal() {
			return this == DLSS;
		}
	}

	/** DLSS quality modes, with NVIDIA's render scales. */
	public enum Quality {
		DLAA(1.0F, 5),
		QUALITY(2.0F / 3.0F, 2),
		BALANCED(0.58F, 1),
		PERFORMANCE(0.5F, 0),
		ULTRA_PERFORMANCE(1.0F / 3.0F, 3),
		CUSTOM(0.0F, 2);

		/** Per-axis render scale (0 for CUSTOM: {@link #customScale}). */
		public final float scale;
		/** NVSDK_NGX_PerfQuality_Value. */
		public final int ngxValue;

		Quality(float scale, int ngxValue) {
			this.scale = scale;
			this.ngxValue = ngxValue;
		}

		public String displayName() {
			return switch (this) {
				case DLAA -> "DLAA (100%)";
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
		AUTO(0, "Auto", "NVIDIA's pick for the quality mode: K for DLAA, Quality and Balanced, M for Performance, L for Ultra "
			+ "Performance. Also lets the NVIDIA app's DLSS override choose."),
		K(11, "K (transformer)", "DLSS 4 transformer model. Best quality for DLAA, Quality and Balanced."),
		M(13, "M (transformer 2)", "DLSS 4.5 second-generation transformer, tuned for Performance mode."),
		L(12, "L (transformer 2)", "DLSS 4.5 second-generation transformer, tuned for Ultra Performance mode."),
		J(10, "J (transformer)", "Like K, with slightly less ghosting but a bit more flicker."),
		E(5, "E (CNN, fastest)", "The older DLSS 3 convolutional model: about a third of the transformer's GPU cost, but softer "
			+ "and more ghosting. For high frame rates where the transformer costs more than the lower resolution saves.");

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

	/** NVIDIA Reflex (VK_NV_low_latency2): the driver delays the start of each frame so frames don't queue up. */
	public enum Reflex {
		OFF("Off", "No latency reduction."),
		ON("On", "Starts each frame just in time for the GPU, so frames don't wait in a queue: less input lag at almost the "
			+ "same frame rate."),
		BOOST("On + Boost", "Like On, and keeps the GPU clocks up even when it isn't fully busy. Slightly lower latency, "
			+ "a little more power.");

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
	public static Quality quality = Quality.QUALITY;
	/** Per-axis render scale for {@link Quality#CUSTOM}. */
	public static float customScale = 0.75F;
	public static Preset preset = Preset.AUTO;
	/**
	 * DLSS Frame Generation: one generated frame between every two rendered frames (RTX 40 series and newer). Works with
	 * or without upscaling.
	 */
	public static boolean frameGeneration = false;
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
		return quality == Quality.CUSTOM ? customScale : quality.scale;
	}

	/** NGX quality value for the current settings (Custom: the mode whose defaults fit the scale best). */
	public static int ngxQuality() {
		if (quality != Quality.CUSTOM) {
			return quality.ngxValue;
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
			quality = parseEnum(Quality.class, props.getProperty("quality"), Quality.QUALITY);
			customScale = clampScale(Float.parseFloat(props.getProperty("customScale", "0.75")));
			preset = parseEnum(Preset.class, props.getProperty("preset"), Preset.AUTO);
			frameGeneration = Boolean.parseBoolean(props.getProperty("frameGeneration", "false"));
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
		props.setProperty("frameGeneration", Boolean.toString(frameGeneration));
		props.setProperty("textureLodCorrection", Boolean.toString(textureLodCorrection));
		props.setProperty("stillPackFoliage", Boolean.toString(stillPackFoliage));
		props.setProperty("reflex", reflex.name());
		props.setProperty("ngxLogging", Boolean.toString(ngxLogging));
		props.setProperty("debugEntryShown", Boolean.toString(debugEntryShown));
		try (Writer writer = Files.newBufferedWriter(FILE)) {
			props.store(writer, "DLSS for Minecraft. quality: DLAA, QUALITY, BALANCED, PERFORMANCE, ULTRA_PERFORMANCE or CUSTOM (customScale per axis)");
		} catch (IOException e) {
			DlssMod.LOGGER.warn("Failed to write {}", FILE, e);
		}
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

	/** DLSS accepts 50% to 100% render scale outside its fixed Ultra Performance mode. */
	public static float clampScale(float scale) {
		return Math.max(0.5F, Math.min(1.0F, scale));
	}
}
