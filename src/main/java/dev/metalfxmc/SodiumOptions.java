package dev.metalfxmc;

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

/** MetalFX page in Sodium's Video Settings (only loaded when Sodium is installed). */
public final class SodiumOptions implements ConfigEntryPoint {
	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MetalFXMod.MOD_ID, path);
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
		OptionGroupBuilder upscaling = builder.createOptionGroup()
			.setName(Component.literal("Upscaling"))
			.addOption(builder.createBooleanOption(id("enabled"))
				.setName(Component.literal("Enable Upscaling"))
				.setTooltip(tip("Renders the world at a lower resolution and upscales it to fit your screen, for higher frame rates. "
					+ "The HUD and menus always stay at full resolution. Off = the world renders at full resolution."))
				.setDefaultValue(true)
				.setBinding(v -> MetalFXConfig.enabled = v, () -> MetalFXConfig.enabled)
				.setImpact(OptionImpact.HIGH)
				.setStorageHandler(MetalFXConfig::save)
				.setEnabled(vulkan))
			.addOption(builder.createEnumOption(id("upscaler"), MetalFXConfig.Upscaler.class)
				.setName(Component.literal("Upscaler"))
				.setTooltip(mode -> tip(mode.description()))
				.setElementNameProvider(mode -> Component.literal(mode.displayName()))
				.setDefaultValue(MetalFXConfig.Upscaler.TEMPORAL)
				.setBinding(v -> MetalFXConfig.upscaler = v, () -> MetalFXConfig.upscaler)
				.setImpact(OptionImpact.MEDIUM)
				.setStorageHandler(MetalFXConfig::save)
				.setEnabled(vulkan))
			.addOption(builder.createIntegerOption(id("render_scale"))
				.setName(Component.literal("Render Scale"))
				.setTooltip(tip("How sharp the world is rendered before upscaling. Lower = faster but softer. 50% renders a quarter of "
					+ "the pixels and is a good starting point on Retina displays; try higher on smaller screens."))
				.setDefaultValue(50)
				.setRange(new Range(25, 100, 1))
				.setValueFormatter(v -> Component.literal(v + "%"))
				.setBinding(v -> MetalFXConfig.renderScale = MetalFXConfig.clampScale(v / 100.0F),
					() -> Math.round(MetalFXConfig.renderScale * 100.0F))
				.setImpact(OptionImpact.HIGH)
				.setStorageHandler(MetalFXConfig::save)
				.setEnabled(vulkan))
			.addOption(builder.createIntegerOption(id("sharpness"))
				.setName(Component.literal("Sharpness"))
				.setTooltip(tip("Sharpens the upscaled image. Lower it if edges look too crunchy. 0% turns sharpening off."))
				.setDefaultValue(100)
				.setRange(new Range(0, Math.round(MetalFXConfig.MAX_SHARPNESS * 100.0F), 5))
				.setValueFormatter(v -> Component.literal(v + "%"))
				.setBinding(v -> MetalFXConfig.sharpness = v / 100.0F, () -> Math.round(MetalFXConfig.sharpness * 100.0F))
				.setImpact(OptionImpact.LOW)
				.setStorageHandler(MetalFXConfig::save)
				.setEnabled(vulkan));

		OptionGroupBuilder frameGen = builder.createOptionGroup()
			.setName(Component.literal("Frame Generation"))
			.addOption(builder.createBooleanOption(id("frame_generation"))
				.setName(Component.literal("Frame Generation"))
				.setTooltip(tip("Creates an extra frame between every two rendered frames, so motion looks up to twice as smooth. "
					+ "Works with or without upscaling. The game itself then renders at up to half your display's refresh rate, "
					+ "and controls feel slightly less immediate. Works best when the game already runs at 40 fps or more."))
				.setDefaultValue(false)
				.setBinding(v -> MetalFXConfig.frameGeneration = v, () -> MetalFXConfig.frameGeneration)
				.setImpact(OptionImpact.HIGH)
				.setStorageHandler(MetalFXConfig::save)
				.setEnabled(vulkan))
			.addOption(builder.createEnumOption(id("frame_gen_backend"), MetalFXConfig.FrameGenBackend.class)
				.setName(Component.literal("Frame Generator"))
				.setTooltip(backend -> tip(backend == MetalFXConfig.FrameGenBackend.FSR
					? "AMD FSR 3. Follows both the camera's movement and things that move on their own, like mobs and water. "
						+ "Needs macOS 14 or newer."
					: "Apple MetalFX. Follows the camera's movement. Needs macOS 26 or newer (uses AMD FSR 3 otherwise)."))
				.setElementNameProvider(backend -> Component.literal(backend.displayName()))
				.setDefaultValue(MetalFXConfig.FrameGenBackend.METALFX)
				.setBinding(v -> MetalFXConfig.frameGenBackend = v, () -> MetalFXConfig.frameGenBackend)
				.setImpact(OptionImpact.MEDIUM)
				.setStorageHandler(MetalFXConfig::save)
				.setEnabled(vulkan));

		OptionGroupBuilder advanced = builder.createOptionGroup()
			.setName(Component.literal("Advanced"))
			.addOption(builder.createBooleanOption(id("texture_lod_correction"))
				.setName(Component.literal("Texture LOD Correction"))
				.setTooltip(tip("Keeps block textures as detailed as at full resolution when using FSR or MetalFX Temporal. "
					+ "Turn off only if textures shimmer. Changing it briefly reloads the shader pack."))
				.setDefaultValue(true)
				.setBinding(v -> MetalFXConfig.textureLodCorrection = v, () -> MetalFXConfig.textureLodCorrection)
				.setStorageHandler(MetalFXConfig::save)
				.setEnabled(vulkan))
			.addOption(builder.createBooleanOption(id("still_pack_foliage"))
				.setName(Component.literal("Still Shader Pack Foliage"))
				.setTooltip(tip("Stops shader packs' waving plants, leaves and water when using FSR or MetalFX Temporal. The "
					+ "upscalers can't follow that movement, so waving foliage can smear. Changing it briefly reloads the shader pack."))
				.setDefaultValue(false)
				.setBinding(v -> MetalFXConfig.stillPackFoliage = v, () -> MetalFXConfig.stillPackFoliage)
				.setStorageHandler(MetalFXConfig::save)
				.setEnabled(vulkan));

		OptionPageBuilder page = builder.createOptionPage().setName(Component.literal("Upscaling"));
		if (!vulkan) {
			page.addOptionGroup(builder.createOptionGroup()
				.setName(Component.literal("Graphics Backend"))
				.addOption(builder.createExternalButtonOption(id("switch_to_vulkan"))
					.setName(Component.literal("Switch to Vulkan"))
					.setTooltip(Component.literal("Upscaling and frame generation only work with Minecraft's Vulkan graphics backend, and the "
						+ "game is running on OpenGL. Click to switch to Vulkan; the game closes so it can start with Vulkan."))
					.setScreenConsumer(SodiumOptions::confirmSwitchToVulkan)));
		}
		builder.registerOwnModOptions()
			.setNonTintedIcon(id("textures/gui/config-icon.png"))
			.setColorTheme(builder.createColorTheme().setBaseThemeRGB(0x3A8DDE))
			.addPage(page
				.addOptionGroup(upscaling)
				.addOptionGroup(frameGen)
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
			Component.literal("MetalFX cannot run when using OpenGL. Would you like to switch to Vulkan?\nThis will close your game."),
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
