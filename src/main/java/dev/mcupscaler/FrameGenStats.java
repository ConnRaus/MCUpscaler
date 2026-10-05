package dev.mcupscaler;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Rendered and generated frames per second while frame generation runs, over one-second windows (F3 screen). */
final class FrameGenStats {
	private static long windowStart;
	private static int rendered, generated;
	private static int renderedFps = -1, generatedFps;

	private FrameGenStats() {
	}

	/** Once per Minecraft frame, with the generated frames shown since the last one. */
	static void frame(boolean running, int generatedFrames) {
		if (!running) {
			windowStart = 0L;
			renderedFps = -1;
			return;
		}
		long now = System.nanoTime();
		if (windowStart == 0L) {
			windowStart = now;
			rendered = generated = 0;
		}
		rendered++;
		generated += generatedFrames;
		if (now - windowStart >= 1_000_000_000L) {
			double seconds = (now - windowStart) / 1.0e9;
			renderedFps = (int)Math.round(rendered / seconds);
			generatedFps = (int)Math.round(generated / seconds);
			windowStart = now;
			rendered = generated = 0;
		}
	}

	/** Rendered, generated and total frames per second, for example "60r/60g/120t", or null during the first second. */
	@Nullable
	static String rates() {
		return renderedFps < 0 ? null : String.format(Locale.ROOT, "%dr/%dg/%dt", renderedFps, generatedFps, renderedFps + generatedFps);
	}
}
