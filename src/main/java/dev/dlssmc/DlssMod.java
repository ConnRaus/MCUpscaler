package dev.dlssmc;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DlssMod implements ClientModInitializer {
	public static final String MOD_ID = "dlssmc";
	public static final Logger LOGGER = LoggerFactory.getLogger("DLSS");

	private static KeyMapping toggleKey;
	private static KeyMapping cycleQualityKey;
	private static KeyMapping cyclePresetKey;
	private static KeyMapping frameGenKey;

	@Override
	public void onInitializeClient() {
		DlssConfig.load();

		// Unbound by default: the settings live in Video Settings; these are for quick comparisons.
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, MOD_ID));
		toggleKey = registerUnbound("key.dlssmc.toggle", category);
		cycleQualityKey = registerUnbound("key.dlssmc.cycle_quality", category);
		cyclePresetKey = registerUnbound("key.dlssmc.cycle_preset", category);
		frameGenKey = registerUnbound("key.dlssmc.frame_generation", category);

		DlssDebugEntry.register();
		ClientTickEvents.END_CLIENT_TICK.register(DlssMod::onTick);
	}

	private static KeyMapping registerUnbound(String name, KeyMapping.Category category) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping(name, InputConstants.UNKNOWN.getValue(), category));
	}

	private static void onTick(Minecraft minecraft) {
		VitrailCompat.tick();
		DlssDebugEntry.enableOnce(minecraft);
		boolean changed = false;
		while (toggleKey.consumeClick()) {
			DlssConfig.enabled = !DlssConfig.enabled;
			changed = true;
		}
		while (cycleQualityKey.consumeClick()) {
			DlssConfig.Quality[] all = DlssConfig.Quality.values();
			DlssConfig.quality = all[(DlssConfig.quality.ordinal() + 1) % all.length];
			changed = true;
		}
		while (cyclePresetKey.consumeClick()) {
			DlssConfig.Preset[] all = DlssConfig.Preset.values();
			DlssConfig.preset = all[(DlssConfig.preset.ordinal() + 1) % all.length];
			changed = true;
		}
		while (frameGenKey.consumeClick()) {
			// Skips frame generators this GPU can't run.
			DlssConfig.FrameGeneration[] all = DlssConfig.FrameGeneration.values();
			DlssConfig.FrameGeneration next = DlssConfig.frameGeneration;
			do {
				next = all[(next.ordinal() + 1) % all.length];
			} while (next != DlssConfig.FrameGeneration.OFF && !WorldUpscaler.isFrameGenAvailable(next));
			DlssConfig.frameGeneration = next;
			changed = true;
		}
		if (changed) {
			DlssConfig.save();
			if (minecraft.player != null) {
				minecraft.player.sendOverlayMessage(Component.literal(statusLine()));
			}
		}
	}

	/** The overlay message after a key changed a setting: the upscaling mode and whether frame generation is on. */
	public static String statusLine() {
		if (WorldUpscaler.needsVulkan()) {
			return "DLSS: needs the Vulkan graphics backend";
		}
		return upscalingStatus() + " | Frame Generation: " + frameGenStatus();
	}

	private static String frameGenStatus() {
		DlssConfig.FrameGeneration backend = DlssConfig.frameGeneration;
		if (backend == DlssConfig.FrameGeneration.OFF) {
			return "off";
		}
		if (!WorldUpscaler.isFrameGenAvailable(backend)) {
			return backend.displayName() + " unavailable";
		}
		return WorldUpscaler.hasFrameGenInputs() ? backend.displayName() : "off (not with Bilinear)";
	}

	private static String upscalingStatus() {
		if (!DlssConfig.enabled) {
			return "Upscaling: off (native resolution)";
		}
		DlssConfig.Upscaler upscaler = DlssConfig.upscaler;
		String mode = !WorldUpscaler.isUpscalerReady(upscaler)
			? "Bilinear (" + upscaler.displayName() + " unavailable: " + WorldUpscaler.unavailableReason(upscaler) + ")"
			: upscaler.displayName();
		String quality = DlssConfig.qualityName();
		String preset = DlssConfig.upscaler == DlssConfig.Upscaler.DLSS ? ", preset " + WorldUpscaler.presetName() : "";
		return "Upscaling: " + mode + " " + quality + preset;
	}
}
