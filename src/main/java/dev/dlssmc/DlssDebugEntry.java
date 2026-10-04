package dev.dlssmc;

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

/** F3 screen section: upscaler, quality mode, preset, render and output resolution, frame generation. */
public class DlssDebugEntry implements DebugScreenEntry {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(DlssMod.MOD_ID, "status");

	public static void register() {
		DebugScreenEntries.register(ID, new DlssDebugEntry());
	}

	/** New F3 entries start hidden: show this one once, then leave it to the player (F3 debug options). */
	public static void enableOnce(Minecraft minecraft) {
		if (DlssConfig.debugEntryShown || minecraft.debugEntries == null) {
			return;
		}
		if (minecraft.debugEntries.getStatus(ID) == DebugScreenEntryStatus.NEVER) {
			minecraft.debugEntries.setStatus(ID, DebugScreenEntryStatus.IN_OVERLAY);
			minecraft.debugEntries.save();
		}
		DlssConfig.debugEntryShown = true;
		DlssConfig.save();
	}

	@Override
	public void display(DebugScreenDisplayer displayer, @Nullable Level level, @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
		Minecraft minecraft = Minecraft.getInstance();
		List<String> lines = new ArrayList<>();
		if (WorldUpscaler.needsVulkan()) {
			lines.add("DLSS: off, needs the Vulkan graphics backend");
			lines.add(Reflex.statusLine());
			displayer.addToGroup(ID, lines);
			return;
		}
		var main = minecraft.gameRenderer.mainRenderTarget();
		int outW = main.width, outH = main.height;
		int[] world = WorldUpscaler.worldRenderSize();
		if (world == null) {
			lines.add("DLSS: off (native)" + (DlssConfig.enabled && !WorldUpscaler.isDlssReady() ? ", " + WorldUpscaler.unavailableReason() : ""));
		} else {
			boolean dlss = DlssConfig.upscaler == DlssConfig.Upscaler.DLSS && WorldUpscaler.isDlssReady();
			lines.add("Upscaler: " + (dlss ? "DLSS " + DlssConfig.quality.displayName().replaceAll(" \\(.*", "") + ", preset "
				+ DlssConfig.preset.displayName() : DlssConfig.upscaler == DlssConfig.Upscaler.DLSS ? "Bilinear (DLSS unavailable)" : "Bilinear"));
			lines.add(String.format(Locale.ROOT, "Render resolution: %dx%d -> %dx%d (%d%%)", world[0], world[1], outW, outH,
				Math.round(100.0F * world[0] / Math.max(1, outW))));
			float[] gpu = dlss ? DlssNative.gpuTimes() : null;
			if (gpu != null && gpu[1] > 0.0F) {
				lines.add(String.format(Locale.ROOT, "DLSS GPU time: %.2f ms (motion vectors %.2f, DLSS %.2f, copy %.2f)",
					gpu[0] + gpu[1] + gpu[2], gpu[0], gpu[1], gpu[2]));
			}
		}
		String frameGen = FrameGen.statusLine();
		lines.add(frameGen != null ? frameGen : "Frame generation: " + (!DlssConfig.frameGeneration ? "off"
			: !WorldUpscaler.isFrameGenAvailable() ? "unavailable"
			: !WorldUpscaler.hasFrameGenInputs() ? "off (no motion vectors with the Bilinear upscaler)" : "starting"));
		lines.add(Reflex.statusLine());
		displayer.addToGroup(ID, lines);
	}
}
