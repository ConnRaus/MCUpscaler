package dev.metalfxmc;

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

/** F3 screen section: upscaler, render and output resolution, sharpness and frame generation. */
public class MetalFXDebugEntry implements DebugScreenEntry {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(MetalFXMod.MOD_ID, "status");

	private long rateStartNanos;
	private long lastDisplayNanos;
	private long rateStartGenerated;
	private long rateStartRendered;
	private int generatedFps = -1;
	private int renderedFps;

	public static void register() {
		DebugScreenEntries.register(ID, new MetalFXDebugEntry());
	}

	/** New F3 entries start hidden: show this one once, then leave it to the player (F3 debug options). */
	public static void enableOnce(Minecraft minecraft) {
		if (MetalFXConfig.debugEntryShown || minecraft.debugEntries == null) {
			return;
		}
		if (minecraft.debugEntries.getStatus(ID) == DebugScreenEntryStatus.NEVER) {
			minecraft.debugEntries.setStatus(ID, DebugScreenEntryStatus.IN_OVERLAY);
			minecraft.debugEntries.save();
		}
		MetalFXConfig.debugEntryShown = true;
		MetalFXConfig.save();
	}

	@Override
	public void display(DebugScreenDisplayer displayer, @Nullable Level level, @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
		Minecraft minecraft = Minecraft.getInstance();
		List<String> lines = new ArrayList<>();
		if (WorldUpscaler.needsVulkan()) {
			lines.add("MetalFX: off, needs the Vulkan graphics backend");
			displayer.addToGroup(ID, lines);
			return;
		}
		var main = minecraft.gameRenderer.mainRenderTarget();
		int outW = main.width, outH = main.height;
		int[] world = WorldUpscaler.worldRenderSize();
		if (world == null) {
			lines.add("MetalFX upscaling: off (native)");
			lines.add(String.format(Locale.ROOT, "Render resolution: %dx%d (native)", outW, outH));
		} else {
			String upscaler = MetalFXConfig.upscaler == MetalFXConfig.Upscaler.BILINEAR || WorldUpscaler.isMetalFxReady()
				? MetalFXConfig.upscaler.displayName() : "Bilinear (MetalFX unavailable)";
			lines.add("MetalFX upscaling: " + upscaler);
			lines.add(String.format(Locale.ROOT, "Render resolution: %dx%d -> %dx%d (%d%%)", world[0], world[1], outW, outH,
				Math.round(100.0F * world[0] / Math.max(1, outW))));
			if (MetalFXConfig.upscaler != MetalFXConfig.Upscaler.BILINEAR) {
				lines.add(String.format(Locale.ROOT, "Sharpness: %d%%, texture LOD correction: %s", Math.round(MetalFXConfig.sharpness * 100.0F),
					MetalFXConfig.textureLodCorrection ? "on" : "off"));
			}
		}
		if (!MetalFXConfig.frameGeneration) {
			lines.add("Frame generation: off");
		} else if (!WorldUpscaler.isFrameGenActive()) {
			lines.add(WorldUpscaler.isFrameGenSupported() ? "Frame generation: unavailable" : "Frame generation: needs macOS 14");
		} else {
			lines.add("Frame generation: " + WorldUpscaler.frameGenBackend().displayName()
				+ (WorldUpscaler.isPresenterLive() ? "" : " (starting)"));
			updateRate();
			if (generatedFps >= 0) {
				lines.add(String.format(Locale.ROOT, "FG: %dr/%dg/%dfps", renderedFps, generatedFps,
					renderedFps + generatedFps));
			}
		}
		displayer.addToGroup(ID, lines);
	}

	/** Rendered and generated frames per second, over the same one-second windows. */
	private void updateRate() {
		long now = System.nanoTime();
		boolean stale = now - lastDisplayNanos > 500_000_000L; // F3 was closed: start over
		lastDisplayNanos = now;
		if (rateStartNanos == 0L || stale) {
			rateStartNanos = now;
			rateStartGenerated = WorldUpscaler.generatedShown;
			rateStartRendered = WorldUpscaler.renderedFrames;
			generatedFps = -1;
		} else if (now - rateStartNanos >= 1_000_000_000L) {
			double seconds = (now - rateStartNanos) / 1.0e9;
			generatedFps = (int)Math.round((WorldUpscaler.generatedShown - rateStartGenerated) / seconds);
			renderedFps = (int)Math.round((WorldUpscaler.renderedFrames - rateStartRendered) / seconds);
			rateStartNanos = now;
			rateStartGenerated = WorldUpscaler.generatedShown;
			rateStartRendered = WorldUpscaler.renderedFrames;
		}
	}
}
