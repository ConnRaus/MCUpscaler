package dev.dlssmc;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
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
		String unavailable = vulkan && !WorldUpscaler.isDlssReady() ? "\n\nDLSS is unavailable: " + WorldUpscaler.unavailableReason() : "";
		OptionGroupBuilder upscaling = builder.createOptionGroup()
			.setName(Component.literal("Upscaling"))
			.addOption(builder.createBooleanOption(id("enabled"))
				.setName(Component.literal("Enable DLSS"))
				.setTooltip(tip("Renders the world at a lower resolution and rebuilds it at your screen's resolution with NVIDIA DLSS, "
					+ "for higher frame rates and anti-aliasing. The HUD and menus always stay at full resolution." + unavailable))
				.setDefaultValue(true)
				.setBinding(v -> DlssConfig.enabled = v, () -> DlssConfig.enabled)
				.setImpact(OptionImpact.HIGH)
				.setStorageHandler(DlssConfig::save)
				.setEnabled(vulkan))
			.addOption(builder.createEnumOption(id("upscaler"), DlssConfig.Upscaler.class)
				.setName(Component.literal("Upscaler"))
				.setTooltip(mode -> tip(mode.description()))
				.setElementNameProvider(mode -> Component.literal(mode.displayName()))
				.setDefaultValue(DlssConfig.Upscaler.DLSS)
				.setBinding(v -> DlssConfig.upscaler = v, () -> DlssConfig.upscaler)
				.setImpact(OptionImpact.MEDIUM)
				.setStorageHandler(DlssConfig::save)
				.setEnabled(vulkan))
			.addOption(builder.createEnumOption(id("quality"), DlssConfig.Quality.class)
				.setName(Component.literal("Quality Mode"))
				.setTooltip(mode -> tip(switch (mode) {
					case DLAA -> "Renders at full resolution and uses DLSS only as anti-aliasing. Best image, no speed-up.";
					case QUALITY -> "Renders at 67% resolution per axis. Close to native image quality at a good speed-up.";
					case BALANCED -> "Renders at 58% resolution per axis.";
					case PERFORMANCE -> "Renders at 50% resolution per axis (a quarter of the pixels). Recommended for 4K screens.";
					case ULTRA_PERFORMANCE -> "Renders at 33% resolution per axis. For 8K screens or very slow frame rates; visibly softer.";
					case CUSTOM -> "Renders at the Custom Render Scale below.";
				}))
				.setElementNameProvider(mode -> Component.literal(mode.displayName()))
				.setDefaultValue(DlssConfig.Quality.QUALITY)
				.setBinding(v -> DlssConfig.quality = v, () -> DlssConfig.quality)
				.setImpact(OptionImpact.HIGH)
				.setStorageHandler(DlssConfig::save)
				.setEnabled(vulkan))
			.addOption(builder.createIntegerOption(id("custom_scale"))
				.setName(Component.literal("Custom Render Scale"))
				.setTooltip(tip("Render scale per axis when Quality Mode is Custom (50% to 100%)."))
				.setDefaultValue(75)
				.setRange(new Range(50, 100, 1))
				.setValueFormatter(v -> Component.literal(v + "%"))
				.setBinding(v -> DlssConfig.customScale = DlssConfig.clampScale(v / 100.0F), () -> Math.round(DlssConfig.customScale * 100.0F))
				.setImpact(OptionImpact.HIGH)
				.setStorageHandler(DlssConfig::save)
				.setEnabled(vulkan))
			.addOption(builder.createEnumOption(id("preset"), DlssConfig.Preset.class)
				.setName(Component.literal("DLSS Preset"))
				.setTooltip(preset -> tip(preset.description()))
				.setElementNameProvider(preset -> Component.literal(preset.displayName()))
				.setDefaultValue(DlssConfig.Preset.AUTO)
				.setBinding(v -> DlssConfig.preset = v, () -> DlssConfig.preset)
				.setImpact(OptionImpact.LOW)
				.setStorageHandler(DlssConfig::save)
				.setEnabled(vulkan));

		// Independent of upscaling: frame generation makes its own motion vectors when DLSS is off.
		OptionGroupBuilder frames = builder.createOptionGroup()
			.setName(Component.literal("Frame Generation & Latency"))
			.addOption(builder.createBooleanOption(id("frame_generation"))
				.setName(Component.literal("Frame Generation"))
				.setTooltip(tip("NVIDIA DLSS Frame Generation: shows an AI-generated frame between every two rendered frames, "
					+ "nearly doubling the frame rate you see (adds a little input latency). Needs an RTX 40 or 50 series GPU. Works "
					+ "with DLSS upscaling on or off (not with the Bilinear upscaler). Works best without VSync or with a frame "
					+ "rate limit at half your refresh rate. Pair it with Reflex to keep the input latency down."
					+ (vulkan && !WorldUpscaler.isFrameGenAvailable() ? "\n\nUnavailable on this GPU or driver." : "")))
				.setDefaultValue(false)
				.setBinding(v -> DlssConfig.frameGeneration = v, () -> DlssConfig.frameGeneration)
				.setImpact(OptionImpact.MEDIUM)
				.setStorageHandler(DlssConfig::save)
				.setEnabled(vulkan && WorldUpscaler.isFrameGenAvailable()))
			.addOption(builder.createEnumOption(id("reflex"), DlssConfig.Reflex.class)
				.setName(Component.literal("NVIDIA Reflex"))
				.setTooltip(mode -> tip(mode.description() + (vulkan && !Reflex.isSupported()
					? "\n\nUnavailable: the graphics driver doesn't offer VK_NV_low_latency2." : "")))
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
				.setTooltip(tip("Keeps block textures as detailed as at full resolution while DLSS upscales. "
					+ "Turn off only if textures shimmer. Changing it briefly reloads the shader pack."))
				.setDefaultValue(true)
				.setBinding(v -> DlssConfig.textureLodCorrection = v, () -> DlssConfig.textureLodCorrection)
				.setStorageHandler(DlssConfig::save)
				.setEnabled(vulkan))
			.addOption(builder.createBooleanOption(id("still_pack_foliage"))
				.setName(Component.literal("Still Shader Pack Foliage"))
				.setTooltip(tip("Stops shader packs' waving plants, leaves and water while DLSS runs. DLSS can't follow that "
					+ "movement, so waving foliage can smear. Changing it briefly reloads the shader pack."))
				.setDefaultValue(false)
				.setBinding(v -> DlssConfig.stillPackFoliage = v, () -> DlssConfig.stillPackFoliage)
				.setStorageHandler(DlssConfig::save)
				.setEnabled(vulkan))
			.addOption(builder.createBooleanOption(id("ngx_logging"))
				.setName(Component.literal("NVIDIA NGX Logs"))
				.setTooltip(tip("Writes NVIDIA's DLSS logs to the dlssmc folder in the game directory, for troubleshooting. "
					+ "Takes effect after a restart."))
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
					.setTooltip(Component.literal("DLSS only works with Minecraft's Vulkan graphics backend, and the game is running "
						+ "on OpenGL. Click to switch to Vulkan; the game closes so it can start with Vulkan."))
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
			Component.literal("DLSS cannot run when using OpenGL. Would you like to switch to Vulkan?\nThis will close your game."),
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
