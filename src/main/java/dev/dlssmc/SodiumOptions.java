package dev.dlssmc;

import java.util.EnumSet;
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

/** DLSS page in Sodium's Video Settings (only loaded when Sodium is installed). */
public final class SodiumOptions implements ConfigEntryPoint {
	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(DlssMod.MOD_ID, path);
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
		// Upscalers and frame generators this GPU can't run stay listed, crossed out, with the reason in the tooltip.
		Set<DlssConfig.Upscaler> upscalers = EnumSet.noneOf(DlssConfig.Upscaler.class);
		for (DlssConfig.Upscaler upscaler : DlssConfig.Upscaler.values()) {
			if (!vulkan || WorldUpscaler.isUpscalerSupported(upscaler)) {
				upscalers.add(upscaler);
			}
		}
		DlssConfig.Upscaler defaultUpscaler = upscalers.contains(DlssConfig.Upscaler.DLSS) ? DlssConfig.Upscaler.DLSS
			: upscalers.contains(DlssConfig.Upscaler.FSR) ? DlssConfig.Upscaler.FSR : DlssConfig.Upscaler.BILINEAR;
		Set<DlssConfig.FrameGeneration> generators = EnumSet.noneOf(DlssConfig.FrameGeneration.class);
		for (DlssConfig.FrameGeneration backend : DlssConfig.FrameGeneration.values()) {
			if (backend == DlssConfig.FrameGeneration.OFF || !vulkan || WorldUpscaler.isFrameGenAvailable(backend)) {
				generators.add(backend);
			}
		}
		OptionGroupBuilder upscaling = builder.createOptionGroup()
			.setName(Component.literal("Upscaling"))
			.addOption(builder.createBooleanOption(id("enabled"))
				.setName(Component.literal("Enable Upscaling"))
				.setTooltip(tip("Renders the world at a lower resolution and upscales it, for higher FPS. The HUD stays sharp."))
				.setDefaultValue(true)
				.setBinding(v -> DlssConfig.enabled = v, () -> DlssConfig.enabled)
				.setImpact(OptionImpact.HIGH)
				.setStorageHandler(DlssConfig::save)
				.setEnabled(vulkan))
			.addOption(builder.createEnumOption(id("upscaler"), DlssConfig.Upscaler.class)
				.setName(Component.literal("Upscaler"))
				.setTooltip(mode -> tip(mode.description() + (upscalers.contains(mode) ? ""
					: "\n\nNot available: " + WorldUpscaler.unavailableReason(mode) + ".")))
				.setElementNameProvider(mode -> crossedOutUnless(upscalers.contains(mode), mode.displayName()))
				.setDefaultValue(defaultUpscaler)
				.setBinding(v -> DlssConfig.upscaler = v, () -> DlssConfig.upscaler)
				.setImpact(OptionImpact.MEDIUM)
				.setStorageHandler(DlssConfig::save)
				.setEnabled(vulkan))
			.addOption(builder.createEnumOption(id("quality"), DlssConfig.Quality.class)
				.setName(Component.literal("Quality Mode"))
				.setTooltip(mode -> tip(switch (mode) {
					case DLAA -> "100% resolution, used only for anti-aliasing.";
					case CUSTOM -> "Uses the Custom Render Scale below.";
					default -> Math.round(mode.scale * 100) + "% resolution.";
				} + (mode == DlssConfig.Quality.CUSTOM ? "" : "\n(" + renderResolution(mode.scale) + ")")))
				.setElementNameProvider(mode -> Component.literal(mode.displayName()))
				.setDefaultValue(DlssConfig.Quality.QUALITY)
				.setBinding(v -> DlssConfig.quality = v, () -> DlssConfig.quality)
				.setImpact(OptionImpact.HIGH)
				.setStorageHandler(DlssConfig::save)
				.setEnabled(vulkan))
			.addOption(builder.createIntegerOption(id("custom_scale"))
				.setName(Component.literal("Custom Render Scale"))
				.setTooltip(v -> tip("Render resolution when Quality Mode is Custom.\n(" + renderResolution(v / 100.0F) + ")"))
				.setDefaultValue(75)
				// DLSS: 50-100%, FSR and Bilinear: 33-100% (DlssConfig.minCustomScale).
				.setRangeProvider(state -> new Range(upscalerIs(state, DlssConfig.Upscaler.DLSS) ? 50 : 33, 100, 1), id("upscaler"))
				.setValueFormatter(v -> Component.literal(v + "%"))
				.setBinding(v -> DlssConfig.customScale = DlssConfig.clampScale(v / 100.0F),
					() -> Math.round(Math.max(DlssConfig.customScale, DlssConfig.minCustomScale(DlssConfig.upscaler)) * 100.0F))
				.setImpact(OptionImpact.HIGH)
				.setStorageHandler(DlssConfig::save)
				.setEnabledProvider(state -> vulkan && state.readEnumOption(id("quality"), DlssConfig.Quality.class) == DlssConfig.Quality.CUSTOM,
					id("quality")))
			.addOption(builder.createEnumOption(id("preset"), DlssConfig.Preset.class)
				.setName(Component.literal("DLSS Preset"))
				.setTooltip(preset -> tip(preset.description()))
				.setElementNameProvider(preset -> Component.literal(preset == DlssConfig.Preset.AUTO && vulkan
					&& WorldUpscaler.isUpscalerSupported(DlssConfig.Upscaler.DLSS) ? "Auto (" + WorldUpscaler.autoPreset().name() + ")"
					: preset.displayName()))
				.setDefaultValue(DlssConfig.Preset.AUTO)
				.setBinding(v -> DlssConfig.preset = v, () -> DlssConfig.preset)
				.setImpact(OptionImpact.LOW)
				.setStorageHandler(DlssConfig::save)
				.setEnabledProvider(state -> vulkan && upscalerIs(state, DlssConfig.Upscaler.DLSS), id("upscaler")))
			.addOption(builder.createIntegerOption(id("fsr_sharpness"))
				.setName(Component.literal("FSR Sharpness"))
				.setTooltip(tip("Sharpens FSR's image. Lower it if edges look harsh."))
				.setDefaultValue(100)
				.setRange(new Range(0, 100, 5))
				.setValueFormatter(v -> Component.literal(v == 0 ? "Off" : v + "%"))
				.setBinding(v -> DlssConfig.fsrSharpness = v / 100.0F, () -> Math.round(DlssConfig.fsrSharpness * 100.0F))
				.setImpact(OptionImpact.LOW)
				.setStorageHandler(DlssConfig::save)
				.setEnabledProvider(state -> vulkan && upscalerIs(state, DlssConfig.Upscaler.FSR), id("upscaler")));

		// Independent of upscaling: frame generation makes its own motion vectors when DLSS is off.
		OptionGroupBuilder frames = builder.createOptionGroup()
			.setName(Component.literal("Frame Generation & Latency"))
			.addOption(builder.createEnumOption(id("frame_generation"), DlssConfig.FrameGeneration.class)
				.setName(Component.literal("Frame Generation"))
				.setTooltip(backend -> tip(backend.description() + (backend == DlssConfig.FrameGeneration.OFF ? ""
					: "\n\nAdds a generated frame between real ones for smoother motion. Adds a little input lag; use with Reflex.")
					+ (generators.contains(backend) ? "" : "\n\nNot available: " + WorldUpscaler.frameGenUnavailableReason(backend) + ".")))
				.setElementNameProvider(backend -> crossedOutUnless(generators.contains(backend), backend.displayName()))
				.setDefaultValue(DlssConfig.FrameGeneration.OFF)
				.setBinding(v -> DlssConfig.frameGeneration = v, () -> DlssConfig.frameGeneration)
				.setImpact(OptionImpact.MEDIUM)
				.setStorageHandler(DlssConfig::save)
				.setEnabled(vulkan))
			.addOption(builder.createEnumOption(id("reflex"), DlssConfig.Reflex.class)
				.setName(Component.literal("NVIDIA Reflex"))
				.setTooltip(mode -> tip(mode.description() + (vulkan && !Reflex.isSupported()
					? "\n\nNot available: not supported by your graphics driver." : "")))
				.setElementNameProvider(mode -> Component.literal(mode.displayName()))
				.setDefaultValue(DlssConfig.Reflex.ON)
				.setBinding(v -> {
					DlssConfig.reflex = v;
					Reflex.settingsChanged();
				}, () -> DlssConfig.reflex)
				.setImpact(OptionImpact.LOW)
				.setStorageHandler(DlssConfig::save)
				.setEnabled(vulkan && Reflex.isSupported()));

		OptionGroupBuilder advanced = builder.createOptionGroup()
			.setName(Component.literal("Advanced"))
			.addOption(builder.createBooleanOption(id("texture_lod_correction"))
				.setName(Component.literal("Texture LOD Correction"))
				.setTooltip(tip("Keeps textures sharp while upscaling. Turn off if textures shimmer."))
				.setDefaultValue(true)
				.setBinding(v -> DlssConfig.textureLodCorrection = v, () -> DlssConfig.textureLodCorrection)
				.setStorageHandler(DlssConfig::save)
				.setEnabled(vulkan))
			.addOption(builder.createBooleanOption(id("still_pack_foliage"))
				.setName(Component.literal("Still Shader Pack Foliage"))
				.setTooltip(tip("Stops shader pack plants and water from waving, which can smear when upscaling."))
				.setDefaultValue(false)
				.setBinding(v -> DlssConfig.stillPackFoliage = v, () -> DlssConfig.stillPackFoliage)
				.setStorageHandler(DlssConfig::save)
				.setEnabled(vulkan))
			.addOption(builder.createBooleanOption(id("ngx_logging"))
				.setName(Component.literal("Upscaler Logs"))
				.setTooltip(tip("Saves upscaler logs to the dlssmc folder, for troubleshooting. Slightly slower. Takes effect after a restart."))
				.setDefaultValue(false)
				.setBinding(v -> DlssConfig.ngxLogging = v, () -> DlssConfig.ngxLogging)
				.setStorageHandler(DlssConfig::save)
				.setEnabled(vulkan));

		OptionPageBuilder page = builder.createOptionPage().setName(Component.literal("DLSS"));
		if (!vulkan) {
			page.addOptionGroup(builder.createOptionGroup()
				.setName(Component.literal("Graphics Backend"))
				.addOption(builder.createExternalButtonOption(id("switch_to_vulkan"))
					.setName(Component.literal("Switch to Vulkan"))
					.setTooltip(Component.literal("Upscaling needs Vulkan. Click to switch; the game will close."))
					.setScreenConsumer(SodiumOptions::confirmSwitchToVulkan)));
		}
		builder.registerOwnModOptions()
			.setNonTintedIcon(id("textures/gui/config-icon.png"))
			.setColorTheme(builder.createColorTheme().setBaseThemeRGB(0x76B900))
			.addPage(page
				.addOptionGroup(upscaling)
				.addOptionGroup(frames)
				.addOptionGroup(advanced));
	}

	/** The world's render resolution at this scale, e.g. "2293 x 933" (same rounding as WorldUpscaler.scaled). */
	private static String renderResolution(float scale) {
		var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		return Math.max(1, Math.round(target.width * scale)) + " x " + Math.max(1, Math.round(target.height * scale));
	}

	private static boolean upscalerIs(ConfigState state, DlssConfig.Upscaler upscaler) {
		return state.readEnumOption(id("upscaler"), DlssConfig.Upscaler.class) == upscaler;
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
