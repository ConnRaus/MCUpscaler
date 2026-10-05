package dev.mcupscaler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * F3 screen section: upscaler, quality mode, preset, render and output resolution, frame generation. The same lines on
 * every platform (the backends only supply the numbers); Reflex only where it is supported.
 */
public class UpscalerDebugEntry implements DebugScreenEntry {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(UpscalerMod.MOD_ID, "status");

	public static void register() {
		DebugScreenEntries.register(ID, new UpscalerDebugEntry());
	}

	/** New F3 entries start hidden: show this one once, then leave it to the player (F3 debug options). */
	public static void enableOnce(Minecraft minecraft) {
		if (UpscalerConfig.debugEntryShown || minecraft.debugEntries == null) {
			return;
		}
		if (minecraft.debugEntries.getStatus(ID) == DebugScreenEntryStatus.NEVER) {
			minecraft.debugEntries.setStatus(ID, DebugScreenEntryStatus.IN_OVERLAY);
			minecraft.debugEntries.save();
		}
		UpscalerConfig.debugEntryShown = true;
		UpscalerConfig.save();
	}

	@Override
	public void display(DebugScreenDisplayer displayer, @Nullable Level level, @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
		Minecraft minecraft = Minecraft.getInstance();
		List<String> lines = new ArrayList<>();
		if (WorldUpscaler.needsVulkan()) {
			lines.add("Upscaler: off, needs the Vulkan graphics backend");
			displayer.addToGroup(ID, lines);
			return;
		}
		var main = minecraft.gameRenderer.mainRenderTarget();
		int outW = main.width, outH = main.height;
		int[] world = WorldUpscaler.worldRenderSize();
		if (world == null) {
			lines.add("Upscaler: off (native)" + (UpscalerConfig.enabled && !WorldUpscaler.isUpscalerReady(UpscalerConfig.upscaler)
				? ", " + UpscalerConfig.upscaler.displayName() + " " + WorldUpscaler.unavailableReason(UpscalerConfig.upscaler) : ""));
		} else {
			UpscalerConfig.Upscaler upscaler = UpscalerConfig.upscaler;
			boolean ready = upscaler != UpscalerConfig.Upscaler.BILINEAR && WorldUpscaler.isUpscalerReady(upscaler);
			String quality = UpscalerConfig.qualityName().replaceAll(" \\(\\d+%\\)", "");
			String name = upscaler == UpscalerConfig.Upscaler.FSR ? fsrName() : upscaler.displayName();
			lines.add("Upscaler: " + (!ready ? upscaler != UpscalerConfig.Upscaler.BILINEAR ? "Bilinear (" + upscaler.displayName() + " unavailable)" : "Bilinear"
				: upscaler == UpscalerConfig.Upscaler.DLSS ? name + " " + quality + ", preset " + WorldUpscaler.presetName()
				: name + " " + quality + (UpscalerConfig.sharpness > 0.0F
					? String.format(Locale.ROOT, ", sharpness %d%%", Math.round(UpscalerConfig.sharpness * 100)) : "")));
			lines.add(String.format(Locale.ROOT, "Render resolution: %dx%d -> %dx%d (%d%%)", world[0], world[1], outW, outH,
				Math.round(100.0F * world[0] / Math.max(1, outW))));
			float gpu = ready ? WorldUpscaler.upscaleGpuMillis() : -1.0F;
			if (gpu > 0.0F) {
				lines.add(String.format(Locale.ROOT, "Upscale GPU: %.2f ms", gpu));
			}
		}
		lines.add(frameGenLine());
		if (Platform.WINDOWS && Reflex.isSupported()) {
			lines.add(Reflex.statusLine());
		}
		displayer.addToGroup(ID, lines);
	}

	/** For example "Frame gen (DLSS): 60r/60g/120t" (rendered, generated, total FPS). */
	public static String frameGenLine() {
		UpscalerConfig.FrameGeneration running = WorldUpscaler.runningFrameGen();
		UpscalerConfig.FrameGeneration chosen = UpscalerConfig.frameGeneration;
		if (running != null) {
			String rates = FrameGenStats.rates();
			return "Frame gen (" + running.name() + "): " + (rates != null ? rates : "starting");
		}
		return chosen == UpscalerConfig.FrameGeneration.OFF ? "Frame gen: off"
			: "Frame gen (" + chosen.name() + "): " + (!WorldUpscaler.isFrameGenAvailable(chosen) ? "unavailable"
			: !WorldUpscaler.hasFrameGenInputs() ? "off (not with Bilinear)" : "starting");
	}

	private static String fsrName() {
		if (Platform.MAC) {
			return "FSR 3.1";
		}
		String version = DlssNative.fsrVersion();
		return version.isEmpty() ? "FSR" : "FSR " + version;
	}
}
