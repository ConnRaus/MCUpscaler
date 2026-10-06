package dev.mcupscaler;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpscalerMod implements ClientModInitializer {
	public static final String MOD_ID = "mcupscaler";
	public static final Logger LOGGER = LoggerFactory.getLogger("MCUpscaler");

	private static KeyMapping toggleKey;
	private static KeyMapping cycleUpscalerKey;
	private static KeyMapping cycleQualityKey;
	/** Windows only (DLSS presets), else null. */
	private static KeyMapping cyclePresetKey;
	private static KeyMapping frameGenKey;
	/** The player last told in chat that the mod needs Vulkan: once per world joined. */
	private static LocalPlayer warnedPlayer;

	@Override
	public void onInitializeClient() {
		UpscalerConfig.load();

		// Unbound by default: the settings live in Video Settings; these are for quick comparisons.
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, MOD_ID));
		toggleKey = registerUnbound("key.mcupscaler.toggle", category);
		cycleUpscalerKey = registerUnbound("key.mcupscaler.cycle_upscaler", category);
		cycleQualityKey = registerUnbound("key.mcupscaler.cycle_quality", category);
		cyclePresetKey = Platform.WINDOWS ? registerUnbound("key.mcupscaler.cycle_preset", category) : null;
		frameGenKey = registerUnbound("key.mcupscaler.frame_generation", category);

		UpscalerDebugEntry.register();
		ClientTickEvents.END_CLIENT_TICK.register(UpscalerMod::onTick);
	}

	private static KeyMapping registerUnbound(String name, KeyMapping.Category category) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping(name, InputConstants.UNKNOWN.getValue(), category));
	}

	private static void onTick(Minecraft minecraft) {
		VitrailCompat.tick();
		UpscalerDebugEntry.enableOnce(minecraft);
		warnIfNotVulkan(minecraft);
		boolean changed = false;
		while (toggleKey.consumeClick()) {
			UpscalerConfig.enabled = !UpscalerConfig.enabled;
			changed = true;
		}
		while (cycleUpscalerKey.consumeClick()) {
			// This platform's upscalers that this GPU can run.
			UpscalerConfig.Upscaler[] all = UpscalerConfig.Upscaler.values();
			UpscalerConfig.Upscaler next = UpscalerConfig.upscaler;
			do {
				next = all[(next.ordinal() + 1) % all.length];
			} while (next != UpscalerConfig.upscaler && (!next.onThisPlatform() || !WorldUpscaler.isUpscalerSupported(next)));
			UpscalerConfig.upscaler = next;
			changed = true;
		}
		while (cycleQualityKey.consumeClick()) {
			UpscalerConfig.Quality[] all = UpscalerConfig.Quality.values();
			UpscalerConfig.quality = all[(UpscalerConfig.quality.ordinal() + 1) % all.length];
			changed = true;
		}
		while (cyclePresetKey != null && cyclePresetKey.consumeClick()) {
			UpscalerConfig.Preset[] all = UpscalerConfig.Preset.values();
			UpscalerConfig.preset = all[(UpscalerConfig.preset.ordinal() + 1) % all.length];
			changed = true;
		}
		while (frameGenKey.consumeClick()) {
			// Skips frame generators this GPU can't run.
			UpscalerConfig.FrameGeneration[] all = UpscalerConfig.FrameGeneration.values();
			UpscalerConfig.FrameGeneration next = UpscalerConfig.frameGeneration;
			do {
				next = all[(next.ordinal() + 1) % all.length];
			} while (next != UpscalerConfig.FrameGeneration.OFF && !WorldUpscaler.isFrameGenAvailable(next));
			UpscalerConfig.frameGeneration = next;
			changed = true;
		}
		if (changed) {
			UpscalerConfig.save();
			if (minecraft.player != null) {
				minecraft.player.sendOverlayMessage(Component.literal(statusLine()));
			}
		}
	}

	/** On OpenGL the mod does nothing: says so in chat on joining a world. */
	private static void warnIfNotVulkan(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || player == warnedPlayer || !WorldUpscaler.needsVulkan()) {
			return;
		}
		warnedPlayer = player;
		player.sendSystemMessage(Component.literal("MCUpscaler requires Vulkan! Set Graphics API to \"Prefer Vulkan (Experimental)\" under Options -> Video Settings and restart the game.")
			.withStyle(ChatFormatting.RED));
	}

	/** The overlay message after a key changed a setting: the upscaling mode and whether frame generation is on. */
	public static String statusLine() {
		if (WorldUpscaler.needsVulkan()) {
			return "Upscaling: needs the Vulkan graphics backend";
		}
		return upscalingStatus() + " | Frame Generation: " + frameGenStatus();
	}

	private static String frameGenStatus() {
		UpscalerConfig.FrameGeneration backend = UpscalerConfig.frameGeneration;
		if (backend == UpscalerConfig.FrameGeneration.OFF) {
			return "off";
		}
		if (!WorldUpscaler.isFrameGenAvailable(backend)) {
			return backend.displayName() + " unavailable";
		}
		return WorldUpscaler.hasFrameGenInputs() ? backend.displayName() : "off (not with Bilinear)";
	}

	private static String upscalingStatus() {
		if (!UpscalerConfig.enabled) {
			return "Upscaling: off (native resolution)";
		}
		UpscalerConfig.Upscaler upscaler = UpscalerConfig.upscaler;
		String mode = !WorldUpscaler.isUpscalerReady(upscaler)
			? "Bilinear (" + upscaler.displayName() + " unavailable: " + WorldUpscaler.unavailableReason(upscaler) + ")"
			: upscaler.displayName();
		String quality = UpscalerConfig.qualityName();
		String preset = UpscalerConfig.upscaler == UpscalerConfig.Upscaler.DLSS ? ", preset " + WorldUpscaler.presetName() : "";
		return "Upscaling: " + mode + " " + quality + preset;
	}
}
