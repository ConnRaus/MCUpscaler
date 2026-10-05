package dev.mcupscaler;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A pack's vignette darkens the screen edges in its final pass, before DLSS: DLSS then moves that darkening with the
 * world when the camera turns (a smeared frame around the screen). It is switched off in the pack and the same vignette
 * is drawn after upscaling instead (the post pass in {@link Shaders}).
 */
final class PackVignette {
	/** Vignette shapes the post pass knows. */
	static final int NONE = 0, BSL = 1, ITERATION_T = 2, PHOTON = 3, COMPLEMENTARY = 4;
	private static final float BSL_DEFAULT_STRENGTH = 1.06F;

	/** "#define VIGNETTE" (BSL, IterationT, Photon) or "#define VIGNETTE_R" (Complementary), as the pack's settings left it. */
	private static final Pattern DEFINE = Pattern.compile("^\\s*#\\s*define\\s+(VIGNETTE|VIGNETTE_R)\\s*(//.*)?$");
	/** The packs' vignette strength settings. */
	private static final Pattern VALUE = Pattern.compile("^\\s*#\\s*define\\s+VIGNETTE_(STRENGTH|FALLOFF|ROUNDNESS|INTENSITY)\\s+([-+0-9.eE]+)\\s*(//.*)?$");

	/** The pack's vignette was taken out of its sources. */
	private volatile boolean removed;
	private volatile boolean complementary;
	/** The shape its settings say (NONE = none seen: BSL's), and its parameters. */
	private volatile int shape;
	private volatile float a = BSL_DEFAULT_STRENGTH, b;

	/** A new pack is being read. */
	void reset() {
		removed = false;
		complementary = false;
		shape = NONE;
		a = BSL_DEFAULT_STRENGTH;
		b = 0.0F;
	}

	/** Reads the vignette's settings from a source line and, if {@code remove}, comments out the vignette's switch. */
	String rewrite(String line, boolean remove) {
		Matcher value = VALUE.matcher(line);
		if (value.matches()) {
			readValue(value.group(1), value.group(2));
			return line;
		}
		Matcher define = DEFINE.matcher(line);
		if (!define.matches() || !remove) {
			return line;
		}
		if (define.group(1).equals("VIGNETTE_R")) {
			complementary = true;
		}
		if (!removed) {
			removed = true;
			UpscalerMod.LOGGER.info("The shader pack's vignette ({}) is drawn after upscaling", define.group(1));
		}
		return "// " + line.trim() + " // DLSS: drawn after upscaling instead";
	}

	private void readValue(String setting, String number) {
		float v;
		try {
			v = Float.parseFloat(number);
		} catch (NumberFormatException e) {
			return;
		}
		switch (setting) {
			case "STRENGTH" -> {
				shape = BSL;
				a = v;
			}
			case "FALLOFF" -> {
				shape = ITERATION_T;
				a = v;
			}
			case "INTENSITY" -> {
				shape = PHOTON;
				a = v;
			}
			case "ROUNDNESS" -> b = v;
			default -> {
			}
		}
	}

	/** {shape, a, b} for the post pass into {@code out}; shape NONE unless the pack's own vignette is {@code off}. */
	void write(float[] out, boolean off) {
		int drawn = !(removed && off) ? NONE : complementary ? COMPLEMENTARY : shape == NONE ? BSL : shape;
		out[0] = drawn;
		out[1] = drawn == BSL && shape == NONE ? BSL_DEFAULT_STRENGTH : a;
		out[2] = b;
	}
}
