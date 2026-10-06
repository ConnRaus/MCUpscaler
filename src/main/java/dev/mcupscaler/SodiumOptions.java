package dev.mcupscaler;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Upscaling page in Sodium's Video Settings (only loaded when Sodium is installed). */
public final class SodiumOptions implements ConfigEntryPoint {
	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(UpscalerMod.MOD_ID, path);
	}

	/** Sodium builds this page once the game has loaded, so the graphics backend is known by then. */
	private boolean vulkan;

	/** Option tooltip, with a note when the options are grayed out because the game runs on OpenGL. */
	private Component tip(String text) {
		return Component.literal(vulkan ? text : text + "\n\nREQUIRES VULKAN");
	}

	@Override
	public void registerConfigLate(ConfigBuilder builder) {
		vulkan = !WorldUpscaler.needsVulkan();
		// Only the choices this machine can run are listed: no DLSS on a Mac or an AMD card, no MetalFX on Windows. (On
		// OpenGL the GPU isn't known yet: this platform's choices, grayed out.) The saved choice stays listed even if it
		// can't run, crossed out, so the page never holds a value it doesn't offer.
		Set<UpscalerConfig.Upscaler> upscalers = EnumSet.noneOf(UpscalerConfig.Upscaler.class);
		for (UpscalerConfig.Upscaler upscaler : UpscalerConfig.Upscaler.values()) {
			if (upscaler.onThisPlatform() && (!vulkan || WorldUpscaler.isUpscalerSupported(upscaler))) {
				upscalers.add(upscaler);
			}
		}
		Set<UpscalerConfig.Upscaler> listedUpscalers = EnumSet.copyOf(upscalers);
		if (UpscalerConfig.upscaler.onThisPlatform()) {
			listedUpscalers.add(UpscalerConfig.upscaler);
		}
		UpscalerConfig.Upscaler preferred = UpscalerConfig.defaultUpscaler();
		UpscalerConfig.Upscaler defaultUpscaler = upscalers.contains(preferred) ? preferred
			: upscalers.contains(UpscalerConfig.Upscaler.FSR) ? UpscalerConfig.Upscaler.FSR : UpscalerConfig.Upscaler.BILINEAR;
		Set<UpscalerConfig.FrameGeneration> generators = EnumSet.noneOf(UpscalerConfig.FrameGeneration.class);
		for (UpscalerConfig.FrameGeneration backend : UpscalerConfig.FrameGeneration.values()) {
			if (backend.onThisPlatform() && (backend == UpscalerConfig.FrameGeneration.OFF || !vulkan || WorldUpscaler.isFrameGenAvailable(backend))) {
				generators.add(backend);
			}
		}
		Set<UpscalerConfig.FrameGeneration> listedGenerators = EnumSet.copyOf(generators);
		if (UpscalerConfig.frameGeneration.onThisPlatform()) {
			listedGenerators.add(UpscalerConfig.frameGeneration);
		}
		boolean showDlssPreset = Platform.WINDOWS && (!vulkan || WorldUpscaler.isUpscalerSupported(UpscalerConfig.Upscaler.DLSS));
		// Only Off left: no frame generation option at all.
		boolean showFrameGen = listedGenerators.size() > 1;
		// Reflex support is known once the swapchain exists, before this page is built.
		boolean showReflex = Platform.WINDOWS && (!vulkan || Reflex.isSupported());
		OptionGroupBuilder upscaling = builder.createOptionGroup()
			.setName(Component.literal("Upscaling"))
			.addOption(builder.createBooleanOption(id("enabled"))
				.setName(Component.literal("Enable Upscaling"))
				.setTooltip(tip("Renders the world at a lower resolution and upscales it for higher FPS."))
				.setDefaultValue(true)
				.setBinding(v -> UpscalerConfig.enabled = v, () -> UpscalerConfig.enabled)
				.setImpact(OptionImpact.HIGH)
				.setStorageHandler(UpscalerConfig::save)
				.setEnabled(vulkan))
			.addOption(builder.createEnumOption(id("upscaler"), UpscalerConfig.Upscaler.class)
				.setName(Component.literal("Upscaler"))
				.setTooltip(mode -> tip(mode.description() + (upscalers.contains(mode) ? ""
					: "\n\nNot available: " + WorldUpscaler.unavailableReason(mode) + ".")))
				.setElementNameProvider(mode -> crossedOutUnless(upscalers.contains(mode), mode.displayName()))
				.setAllowedValues(listedUpscalers)
				.setDefaultValue(defaultUpscaler)
				.setBinding(v -> UpscalerConfig.upscaler = v, () -> UpscalerConfig.upscaler)
				.setImpact(OptionImpact.MEDIUM)
				.setStorageHandler(UpscalerConfig::save)
				.setEnabled(vulkan))
			.addOption(builder.createEnumOption(id("quality"), UpscalerConfig.Quality.class)
				.setName(Component.literal("Quality Mode"))
				.setTooltip(mode -> tip(switch (mode) {
					case AUTO -> "Picks the mode for your screen: Quality at 1080p, Balanced at 1440p, Performance at 4K.";
					case DLAA -> "100% resolution, used only for anti-aliasing.";
					case CUSTOM -> "Uses the Custom Render Scale below.";
					default -> Math.round(mode.scale * 100) + "% resolution.";
				} + (mode == UpscalerConfig.Quality.CUSTOM ? ""
					: "\n(" + renderResolution(mode == UpscalerConfig.Quality.AUTO ? UpscalerConfig.autoQuality().scale : mode.scale) + ")")))
				.setElementNameProvider(mode -> Component.literal(mode == UpscalerConfig.Quality.AUTO
					? "Auto (" + UpscalerConfig.autoQuality().displayName().replaceAll(" \\(.*", "") + ")" : mode.displayName()))
				.setDefaultValue(UpscalerConfig.Quality.AUTO)
				.setBinding(v -> UpscalerConfig.quality = v, () -> UpscalerConfig.quality)
				.setImpact(OptionImpact.HIGH)
				.setStorageHandler(UpscalerConfig::save)
				.setEnabled(vulkan))
			.addOption(builder.createIntegerOption(id("custom_scale"))
				.setName(Component.literal("Custom Render Scale"))
				.setTooltip(v -> tip("Render resolution when Quality Mode is Custom.\n(" + renderResolution(v / 100.0F) + ")"))
				.setDefaultValue(75)
				// DLSS: 50-100%, the others: 33-100% (UpscalerConfig.minCustomScale; MetalFX Temporal may clamp it higher).
				.setRangeProvider(state -> new Range(upscalerIs(state, UpscalerConfig.Upscaler.DLSS) ? 50 : 33, 100, 1), id("upscaler"))
				.setValueFormatter(v -> Component.literal(v + "%"))
				.setBinding(v -> UpscalerConfig.customScale = UpscalerConfig.clampScale(v / 100.0F),
					() -> Math.round(Math.max(UpscalerConfig.customScale, UpscalerConfig.minCustomScale(UpscalerConfig.upscaler)) * 100.0F))
				.setImpact(OptionImpact.HIGH)
				.setStorageHandler(UpscalerConfig::save)
				.setEnabledProvider(state -> vulkan && state.readEnumOption(id("quality"), UpscalerConfig.Quality.class) == UpscalerConfig.Quality.CUSTOM,
					id("quality")))
			.addOption(builder.createIntegerOption(id("sharpness"))
				.setName(Component.literal("Sharpness"))
				.setTooltip(tip("Sharpens the upscaled image. Lower it if edges look harsh."))
				.setDefaultValue(50)
				.setRange(new Range(0, 100, 5))
				.setValueFormatter(v -> Component.literal(v == 0 ? "Off" : v + "%"))
				.setBinding(v -> UpscalerConfig.sharpness = v / 100.0F, () -> Math.round(UpscalerConfig.sharpness * 100.0F))
				.setImpact(OptionImpact.LOW)
				.setStorageHandler(UpscalerConfig::save)
				.setEnabledProvider(state -> vulkan && !upscalerIs(state, UpscalerConfig.Upscaler.DLSS)
					&& !upscalerIs(state, UpscalerConfig.Upscaler.BILINEAR), id("upscaler")));
		if (showDlssPreset) {
			upscaling.addOption(builder.createEnumOption(id("preset"), UpscalerConfig.Preset.class)
				.setName(Component.literal("DLSS Preset"))
				.setTooltip(preset -> tip(preset.description()))
				.setElementNameProvider(preset -> Component.literal(preset == UpscalerConfig.Preset.AUTO && vulkan
					&& WorldUpscaler.isUpscalerSupported(UpscalerConfig.Upscaler.DLSS) ? "Auto (" + WorldUpscaler.autoPreset().name() + ")"
					: preset.displayName()))
				.setDefaultValue(UpscalerConfig.Preset.AUTO)
				.setBinding(v -> UpscalerConfig.preset = v, () -> UpscalerConfig.preset)
				.setImpact(OptionImpact.LOW)
				.setStorageHandler(UpscalerConfig::save)
				.setEnabledProvider(state -> vulkan && upscalerIs(state, UpscalerConfig.Upscaler.DLSS), id("upscaler")));
		}

		// Independent of upscaling: frame generation makes its own motion vectors when no temporal upscaler runs.
		OptionGroupBuilder frames = builder.createOptionGroup()
			.setName(Component.literal(showFrameGen && showReflex ? "Frame Generation & Latency" : showReflex ? "Latency" : "Frame Generation"));
		if (showFrameGen) {
			frames.addOption(builder.createEnumOption(id("frame_generation"), UpscalerConfig.FrameGeneration.class)
				.setName(Component.literal("Frame Generation"))
				.setTooltip(backend -> tip(backend.description() + (backend == UpscalerConfig.FrameGeneration.OFF ? ""
					: "\n\nAdds a generated frame between real ones for smoother motion. Adds a little input lag"
						+ (showReflex && vulkan ? "; use with Reflex." : ". Best above 40 FPS."))
					+ (generators.contains(backend) ? "" : "\n\nNot available: " + WorldUpscaler.frameGenUnavailableReason(backend) + ".")))
				.setElementNameProvider(backend -> crossedOutUnless(generators.contains(backend), backend.displayName()))
				.setAllowedValues(listedGenerators)
				.setDefaultValue(UpscalerConfig.FrameGeneration.OFF)
				.setBinding(v -> UpscalerConfig.frameGeneration = v, () -> UpscalerConfig.frameGeneration)
				.setImpact(OptionImpact.MEDIUM)
				.setStorageHandler(UpscalerConfig::save)
				.setEnabled(vulkan));
		}
		if (showReflex) {
			frames.addOption(builder.createEnumOption(id("reflex"), UpscalerConfig.Reflex.class)
				.setName(Component.literal("NVIDIA Reflex"))
				.setTooltip(mode -> tip(mode.description()))
				.setElementNameProvider(mode -> Component.literal(mode.displayName()))
				.setDefaultValue(UpscalerConfig.Reflex.ON)
				.setBinding(v -> {
					UpscalerConfig.reflex = v;
					Reflex.settingsChanged();
				}, () -> UpscalerConfig.reflex)
				.setImpact(OptionImpact.LOW)
				.setStorageHandler(UpscalerConfig::save)
				.setEnabled(vulkan));
		}

		OptionGroupBuilder advanced = builder.createOptionGroup()
			.setName(Component.literal("Advanced"))
			.addOption(builder.createBooleanOption(id("texture_lod_correction"))
				.setName(Component.literal("Texture LOD Correction"))
				.setTooltip(tip("Keeps textures sharp while upscaling. Turn off if textures shimmer."))
				.setDefaultValue(true)
				.setBinding(v -> UpscalerConfig.textureLodCorrection = v, () -> UpscalerConfig.textureLodCorrection)
				.setStorageHandler(UpscalerConfig::save)
				.setEnabled(vulkan))
			.addOption(builder.createBooleanOption(id("still_pack_foliage"))
				.setName(Component.literal("Still Shader Pack Foliage"))
				.setTooltip(tip("Stops shader pack plants and water from waving, which can smear when upscaling."))
				.setDefaultValue(false)
				.setBinding(v -> UpscalerConfig.stillPackFoliage = v, () -> UpscalerConfig.stillPackFoliage)
				.setStorageHandler(UpscalerConfig::save)
				.setEnabled(vulkan));
		if (Platform.WINDOWS) {
			advanced.addOption(builder.createBooleanOption(id("ngx_logging"))
				.setName(Component.literal("Upscaler Logs"))
				.setTooltip(tip("Saves upscaler logs to the mcupscaler folder, for troubleshooting. Slightly slower. Takes effect after a restart."))
				.setDefaultValue(false)
				.setBinding(v -> UpscalerConfig.ngxLogging = v, () -> UpscalerConfig.ngxLogging)
				.setStorageHandler(UpscalerConfig::save)
				.setEnabled(vulkan));
		}

		OptionPageBuilder page = builder.createOptionPage().setName(Component.literal("Upscaling"));
		if (!vulkan) {
			page.addOptionGroup(builder.createOptionGroup()
				.setName(Component.literal("Graphics Backend"))
				.addOption(builder.createExternalButtonOption(id("switch_to_vulkan"))
					.setName(Component.literal("Switch to Vulkan"))
					.setTooltip(Component.literal("Upscaling needs Vulkan. Click to switch; the game will close."))
					.setScreenConsumer(SodiumOptions::confirmSwitchToVulkan)));
		}
		page.addOptionGroup(upscaling);
		if (showFrameGen || showReflex) {
			page.addOptionGroup(frames);
		}
		page.addOptionGroup(advanced);
		builder.registerOwnModOptions()
			.setNonTintedIcon(id("textures/gui/config-icon.png"))
			.setColorTheme(builder.createColorTheme().setBaseThemeRGB(themeColor()))
			.addPage(page);
	}

	/** The page's colour: the GPU vendor's (NVIDIA green, AMD red, Intel or Apple blue). */
	private static int themeColor() {
		String gpu;
		try {
			var info = RenderSystem.getDevice().getDeviceInfo();
			gpu = (info.vendorName() + " " + info.name()).toLowerCase(Locale.ROOT);
		} catch (RuntimeException e) {
			gpu = "";
		}
		if (gpu.contains("nvidia")) {
			return 0x76B900;
		}
		if (gpu.contains("amd") || gpu.contains("radeon") || gpu.contains("ati technologies")) {
			return 0xED1C24;
		}
		if (gpu.contains("intel")) {
			return 0x0071C5;
		}
		return 0x3A8DDE;
	}

	/** The world's render resolution at this scale, e.g. "2293 x 933" (same rounding as WorldUpscaler.scaled). */
	private static String renderResolution(float scale) {
		var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		return Math.max(1, Math.round(target.width * scale)) + " x " + Math.max(1, Math.round(target.height * scale));
	}

	private static boolean upscalerIs(ConfigState state, UpscalerConfig.Upscaler upscaler) {
		return state.readEnumOption(id("upscaler"), UpscalerConfig.Upscaler.class) == upscaler;
	}

	/** A choice this GPU can't run: crossed out and grayed (still selectable; it then reports itself unavailable). */
	private static Component crossedOutUnless(boolean available, String name) {
		return available ? Component.literal(name) : Component.literal(name).withStyle(ChatFormatting.STRIKETHROUGH, ChatFormatting.DARK_GRAY);
	}

	/** Offers to set the game's Graphics API option to Vulkan and close the game, since it only applies on the next start. */
	private static void confirmSwitchToVulkan(Screen parent) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
			if (confirmed) {
				switchToVulkanAndQuit(minecraft);
			} else {
				minecraft.gui.setScreen(parent);
			}
		}, Component.literal("Switch to Vulkan?"),
			Component.literal("Upscaling needs Vulkan. Switch to Vulkan?\nThis will close your game."),
			Component.literal("Switch"), CommonComponents.GUI_BACK));
	}

	private static void switchToVulkanAndQuit(Minecraft minecraft) {
		minecraft.options.preferredGraphicsBackend().set(PreferredGraphicsApi.VULKAN);
		minecraft.options.save();
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (minecraft.isLocalServer() && server != null) {
			server.halt(true);
		}
		minecraft.disconnectWithSavingScreen();
		minecraft.stop();
	}
}
