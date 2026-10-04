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
			lines.add("Upscaler: off (native)" + (DlssConfig.enabled && !WorldUpscaler.isUpscalerReady(DlssConfig.upscaler)
				? ", " + DlssConfig.upscaler.displayName() + " " + WorldUpscaler.unavailableReason(DlssConfig.upscaler) : ""));
		} else {
			DlssConfig.Upscaler upscaler = DlssConfig.upscaler;
			boolean temporal = upscaler.temporal() && WorldUpscaler.isUpscalerReady(upscaler);
			String quality = DlssConfig.quality.displayName().replaceAll(" \\(.*", "");
			String name = upscaler == DlssConfig.Upscaler.FSR ? fsrName() : "DLSS";
			lines.add("Upscaler: " + (!temporal ? upscaler.temporal() ? "Bilinear (" + upscaler.displayName() + " unavailable)" : "Bilinear"
				: upscaler == DlssConfig.Upscaler.DLSS ? name + " " + quality + ", preset " + WorldUpscaler.presetName()
				: name + " " + quality + (DlssConfig.fsrSharpness > 0.0F
					? String.format(Locale.ROOT, ", sharpness %d%%", Math.round(DlssConfig.fsrSharpness * 100)) : "")));
			lines.add(String.format(Locale.ROOT, "Render resolution: %dx%d -> %dx%d (%d%%)", world[0], world[1], outW, outH,
				Math.round(100.0F * world[0] / Math.max(1, outW))));
			float[] gpu = temporal ? DlssNative.gpuTimes() : null;
			if (gpu != null && gpu[1] > 0.0F) {
				lines.add(String.format(Locale.ROOT, "Upscale GPU: %.2f ms (MV %.2f / %s %.2f / copy %.2f)",
					gpu[0] + gpu[1] + gpu[2], gpu[0], upscaler.name(), gpu[1], gpu[2]));
			}
		}
		String frameGen = FrameGen.statusLine();
		DlssConfig.FrameGeneration backend = DlssConfig.frameGeneration;
		lines.add(frameGen != null ? frameGen : backend == DlssConfig.FrameGeneration.OFF ? "Frame gen: off"
			: "Frame gen (" + backend.name() + "): " + (!WorldUpscaler.isFrameGenAvailable(backend) ? "unavailable"
			: !WorldUpscaler.hasFrameGenInputs() ? "off (not with Bilinear)" : "starting"));
		lines.add(Reflex.statusLine());
		displayer.addToGroup(ID, lines);
	}

	private static String fsrName() {
		String version = DlssNative.fsrVersion();
		return version.isEmpty() ? "FSR" : "FSR " + version;
	}
}
