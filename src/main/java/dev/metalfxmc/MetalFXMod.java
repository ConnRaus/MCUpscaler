package dev.metalfxmc;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Locale;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MetalFXMod implements ClientModInitializer {
	public static final String MOD_ID = "metalfx";
	public static final Logger LOGGER = LoggerFactory.getLogger("MetalFX");

	private static KeyMapping toggleKey;
	private static KeyMapping cycleScaleKey;
	private static KeyMapping cycleModeKey;
	private static KeyMapping frameGenKey;

	@Override
	public void onInitializeClient() {
		MetalFXConfig.load();

		// Unbound by default: the settings live in Video Settings; these are for quick comparisons.
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, MOD_ID));
		toggleKey = registerUnbound("key.metalfx.toggle", category);
		cycleScaleKey = registerUnbound("key.metalfx.cycle_scale", category);
		cycleModeKey = registerUnbound("key.metalfx.cycle_mode", category);
		frameGenKey = registerUnbound("key.metalfx.frame_generation", category);

		MetalFXDebugEntry.register();
		ClientTickEvents.END_CLIENT_TICK.register(MetalFXMod::onTick);
	}

	private static KeyMapping registerUnbound(String name, KeyMapping.Category category) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping(name, InputConstants.UNKNOWN.getValue(), category));
	}

	private static void onTick(Minecraft minecraft) {
		VitrailCompat.tick();
		MetalFXDebugEntry.enableOnce(minecraft);
		boolean changed = false;
		while (toggleKey.consumeClick()) {
			MetalFXConfig.enabled = !MetalFXConfig.enabled;
			changed = true;
		}
		while (cycleScaleKey.consumeClick()) {
			MetalFXConfig.renderScale = nextPreset(MetalFXConfig.renderScale);
			changed = true;
		}
		while (cycleModeKey.consumeClick()) {
			MetalFXConfig.Upscaler[] all = MetalFXConfig.Upscaler.values();
			MetalFXConfig.upscaler = all[(MetalFXConfig.upscaler.ordinal() + 1) % all.length];
			changed = true;
		}
		while (frameGenKey.consumeClick()) {
			MetalFXConfig.frameGeneration = !MetalFXConfig.frameGeneration;
			changed = true;
		}
		if (changed) {
			MetalFXConfig.save();
			if (minecraft.player != null) {
				minecraft.player.sendOverlayMessage(Component.literal(statusLine()));
			}
		}
	}

	private static float nextPreset(float current) {
		for (float preset : MetalFXConfig.SCALE_PRESETS) {
			if (preset > current + 0.001F) {
				return preset;
			}
		}
		return MetalFXConfig.SCALE_PRESETS[0];
	}

	public static String statusLine() {
		if (WorldUpscaler.needsVulkan()) {
			return "MetalFX: needs the Vulkan graphics backend";
		}
		String frameGen = !MetalFXConfig.frameGeneration ? ""
			: WorldUpscaler.isFrameGenActive()
				? " + " + WorldUpscaler.frameGenBackend().displayName() + " frame generation"
			: WorldUpscaler.isFrameGenSupported() ? " (frame generation unavailable)"
			: " (frame generation needs macOS 14)";
		if (!MetalFXConfig.enabled || MetalFXConfig.renderScale >= 0.999F) {
			return "MetalFX: native resolution" + frameGen;
		}
		String mode = MetalFXConfig.upscaler == MetalFXConfig.Upscaler.BILINEAR || WorldUpscaler.isMetalFxReady()
			? MetalFXConfig.upscaler.displayName()
			: "Bilinear (MetalFX unavailable)";
		return String.format(Locale.ROOT, "MetalFX: %s @ %d%% render scale%s", mode, Math.round(WorldUpscaler.effectiveScale() * 100), frameGen);
	}
}
