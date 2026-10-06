package dev.mcupscaler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text rewrites of shader pack sources (see {@link VitrailCompat} for when each one applies). Each takes a file's lines
 * and returns them unchanged (the same list) or a patched copy.
 */
final class PackSourcePatches {
	/** A pack's own anti-aliasing switches: "#define TAA" / "#define FXAA" (BSL, Bliss, Complementary). */
	static final Pattern AA_DEFINE = define("TAA|FXAA");
	/** FXAA and Photon's own upscaling (TAAU), for packs whose TAA stays on to carry DLSS's jitter. */
	static final Pattern AA_DEFINE_BUT_TAA = define("FXAA|TAAU");
	/** Waving plants, leaves and water (vertex animation) in BSL, Bliss and Complementary. */
	static final Pattern WAVING_DEFINE = define("WAVY_PLANTS|WAVING_(?:PLANT|GRASS|TALL_PLANT|CROP|LEAF|VINE|WATER|FOLIAGE|LEAVES|WATER_VERTEX)");
	/** "//#define TAA" as the pack's settings leave it when the player switched TAA off. */
	static final Pattern TAA_DEFINE_OFF = Pattern.compile("^\\s*//+\\s*#define\\s+TAA\\s*(//.*)?$");

	/** "#ifdef TAA" / "#if defined TAA" on its own. */
	private static final Pattern TAA_IF = Pattern.compile("^\\s*#\\s*(?:ifdef\\s+TAA|if\\s+defined\\s*\\(?\\s*TAA\\s*\\)?)\\s*(//.*)?$");
	/** "#if CAS_SHARPNESS > 0 && defined TAA": sharpening that comes with the pack's TAA. */
	private static final Pattern TAA_SHARPEN_IF = Pattern.compile("^\\s*#\\s*if\\b.*\\bCAS\\w*.*\\bTAA\\b.*$");
	/** "Bayer8(gl_FragCoord.xy)": an ordered dither tied to the screen pixel grid. */
	private static final Pattern PIXEL_DITHER = Pattern.compile("\\b((?:Bayer|BayerCloud)\\d+)\\s*\\(\\s*gl_FragCoord\\.xy\\s*\\)");
	/** "#define projMAD(m, v) (diagonal3(m) * (v) + (m)[3].xyz)": Chocapic's projection shortcut (Bliss). */
	private static final Pattern PROJ_MAD = Pattern.compile("^(\\s*)#define\\s+projMAD\\s*\\(\\s*(\\w+)\\s*,\\s*(\\w+)\\s*\\).*diagonal.*$");

	private PackSourcePatches() {
	}

	private static Pattern define(String names) {
		return Pattern.compile("^(\\s*)#define\\s+(" + names + ")\\s*(//.*)?$");
	}

	/**
	 * Comments out the {@code define}s, and undefines them too: the pack's settings (TAA=true in shaderpacks/<pack>.txt)
	 * switch a commented define back on.
	 */
	static List<String> commentOut(List<String> lines, Pattern define, String why) {
		List<String> out = null;
		for (int i = 0; i < lines.size(); i++) {
			Matcher m = define.matcher(lines.get(i));
			if (!m.matches()) {
				if (out != null) {
					out.add(lines.get(i));
				}
				continue;
			}
			if (out == null) {
				out = new ArrayList<>(lines.subList(0, i));
			}
			out.add(m.group(1) + "//#define " + m.group(2) + " // " + why);
			out.add(m.group(1) + "#undef " + m.group(2));
		}
		return out != null ? out : lines;
	}

	/**
	 * Chocapic-style packs (Bliss) project with only the matrix's diagonal and translation, which drops the jitter (it sits
	 * in the third column): their terrain is drawn unjittered and the upscaler can't add detail. The full 3x3 part keeps it.
	 */
	static List<String> fullProjMad(List<String> lines) {
		List<String> out = null;
		for (int i = 0; i < lines.size(); i++) {
			Matcher m = PROJ_MAD.matcher(lines.get(i));
			if (m.matches()) {
				out = replace(out, lines, i, m.group(1) + "#define projMAD(" + m.group(2) + ", " + m.group(3) + ") (mat3(" + m.group(2) + ") * ("
					+ m.group(3) + ") + (" + m.group(2) + ")[3].xyz) // Upscaler: keeps the jitter");
			}
		}
		return out != null ? out : lines;
	}

	/** The pack's TAA resolve pass (its gate in a file named like TAA.glsl or c4_taa_exposure.fsh) passes the image through. */
	static List<String> disableTaaResolve(List<String> lines, String fileName) {
		boolean taaFile = fileName.toLowerCase(Locale.ROOT).contains("taa");
		List<String> out = null;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if (taaFile && TAA_IF.matcher(line).matches() || TAA_SHARPEN_IF.matcher(line).matches()) {
				out = replace(out, lines, i, "#if 0 // DLSS: " + line.trim());
			}
		}
		return out != null ? out : lines;
	}

	/**
	 * Packs fade between the normal terrain and Distant Horizons (and some other effects) with an ordered dither that is
	 * the same every frame. At a lower render resolution the pattern gets coarser, and DLSS keeps a static pattern as if it
	 * were detail: a checkerboard. Offsetting the dither by the golden ratio every frame (what the packs do for their own
	 * TAA) lets DLSS average it into a smooth fade. Only in files that declare the frameCounter uniform.
	 */
	static List<String> animateDither(List<String> lines) {
		if (lines.stream().noneMatch(l -> l.contains("uniform int frameCounter"))) {
			return lines;
		}
		List<String> out = null;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if (line.trim().startsWith("#")) {
				continue;
			}
			Matcher m = PIXEL_DITHER.matcher(line);
			if (m.find()) {
				out = replace(out, lines, i, m.replaceAll("fract($1(gl_FragCoord.xy) + 0.61803399 * float(frameCounter % 256))"));
			}
		}
		return out != null ? out : lines;
	}

	/**
	 * Packs only animate their dither noise when their TAA is on ("#ifdef TAA" around a frameCounter offset). Static
	 * noise is kept by the temporal upscalers as if it were detail (a fixed checker pattern), so those small blocks,
	 * and the TAA-only uniform declarations they need, stay enabled. The pack's jitter and its resolve pass stay off.
	 */
	static List<String> keepTaaNoise(List<String> lines) {
		List<String> out = null;
		for (int i = 0; i < lines.size(); i++) {
			if (TAA_IF.matcher(lines.get(i)).matches() && isNoiseBlock(lines, i + 1)) {
				out = replace(out, lines, i, "#if 1 // DLSS: the pack's TAA is off, but its animated noise is kept for the temporal upscaler");
			}
		}
		return out != null ? out : lines;
	}

	/** A short block from {@code start} up to a plain #else/#elif/#endif: only uniforms, or frameCounter noise. */
	private static boolean isNoiseBlock(List<String> lines, int start) {
		List<String> body = new ArrayList<>();
		for (int j = start; j < lines.size(); j++) {
			String t = lines.get(j).trim();
			if (t.startsWith("#")) {
				String directive = t.substring(1).trim();
				if (!directive.startsWith("else") && !directive.startsWith("elif") && !directive.startsWith("endif") || body.isEmpty() || body.size() > 4) {
					return false;
				}
				boolean uniforms = body.stream().allMatch(b -> b.startsWith("uniform "));
				boolean noise = body.stream().anyMatch(b -> b.contains("frameCounter"))
					&& body.stream().noneMatch(b -> b.contains("gl_Position") || b.startsWith("uniform "));
				return uniforms || noise;
			}
			if (!t.isEmpty()) {
				body.add(t);
			}
		}
		return false;
	}

	/**
	 * Geometry fragment shaders sample with the screen-space derivatives scaled by the render scale, i.e. as if rendering
	 * at the output resolution (a texture2D bias argument would do the same, but Vitrail's translator rejects it).
	 */
	static List<String> scaleTextureLod(List<String> lines, float scale) {
		int insertAt = afterVersion(lines);
		List<String> patched = new ArrayList<>(lines.size() + 3);
		patched.addAll(lines.subList(0, insertAt));
		patched.add("#extension GL_ARB_shader_texture_lod : enable");
		patched.add(String.format(Locale.ROOT, "#define MCUPSCALER_LOD_SCALE %.4f", scale));
		patched.add(
			"#define texture2D(mcupscalerS, mcupscalerUv) texture2DGradARB(mcupscalerS, mcupscalerUv, dFdx(mcupscalerUv) * MCUPSCALER_LOD_SCALE, dFdy(mcupscalerUv) * MCUPSCALER_LOD_SCALE)"
		);
		patched.addAll(lines.subList(insertAt, lines.size()));
		return patched;
	}

	/**
	 * GLSL macros can't be overloaded, so the two-argument texture2D macro of {@link #scaleTextureLod} breaks the pack's
	 * three-argument (bias) calls, e.g. Bliss's {@code texture2D(tex, uv, Texture_MipMap_Bias)}. Those calls become
	 * {@code mcupscalerTexture2DBias}, which is the gradient lookup (bias folded into the derivatives) where the LOD macro is
	 * active and the plain biased lookup elsewhere, so the same rewritten include works in every program that uses it.
	 */
	static List<String> rewriteBiasCalls(List<String> lines) {
		String text = String.join("\n", lines);
		String rewritten = rewriteBiasCalls(text);
		if (rewritten.equals(text)) {
			return lines;
		}
		List<String> rewrittenLines = List.of(rewritten.split("\n", -1));
		int insertAt = afterVersion(rewrittenLines);
		List<String> out = new ArrayList<>(rewrittenLines.size() + 8);
		out.addAll(rewrittenLines.subList(0, insertAt));
		out.add("#ifndef MCUPSCALER_TEXTURE2D_BIAS");
		out.add("#define MCUPSCALER_TEXTURE2D_BIAS");
		out.add("#ifdef MCUPSCALER_LOD_SCALE");
		out.add("#define mcupscalerTexture2DBias(mcupscalerS, mcupscalerUv, mcupscalerB) texture2DGradARB(mcupscalerS, mcupscalerUv, "
			+ "dFdx(mcupscalerUv) * (MCUPSCALER_LOD_SCALE * exp2(mcupscalerB)), dFdy(mcupscalerUv) * (MCUPSCALER_LOD_SCALE * exp2(mcupscalerB)))");
		out.add("#else");
		out.add("#define mcupscalerTexture2DBias(mcupscalerS, mcupscalerUv, mcupscalerB) texture2D(mcupscalerS, mcupscalerUv, mcupscalerB)");
		out.add("#endif");
		out.add("#endif");
		out.addAll(rewrittenLines.subList(insertAt, rewrittenLines.size()));
		return out;
	}

	private static String rewriteBiasCalls(String text) {
		StringBuilder out = new StringBuilder(text.length() + 64);
		int i = 0;
		while (true) {
			int at = text.indexOf("texture2D", i);
			if (at < 0) {
				out.append(text, i, text.length());
				return out.toString();
			}
			int end = at + "texture2D".length();
			boolean word = (at == 0 || !isIdentifierChar(text.charAt(at - 1))) && (end >= text.length() || !isIdentifierChar(text.charAt(end)));
			int open = end;
			while (open < text.length() && (text.charAt(open) == ' ' || text.charAt(open) == '\t')) {
				open++;
			}
			if (!word || open >= text.length() || text.charAt(open) != '(' || isMacroName(text, at)) {
				out.append(text, i, end);
				i = end;
				continue;
			}
			// Top-level commas up to the matching parenthesis.
			int depth = 0, commas = 0, close = -1;
			for (int j = open; j < text.length() && close < 0; j++) {
				char c = text.charAt(j);
				if (c == '(' || c == '[') {
					depth++;
				} else if (c == ')' || c == ']') {
					if (--depth == 0) {
						close = j;
					}
				} else if (c == ',' && depth == 1) {
					commas++;
				}
			}
			out.append(text, i, at);
			if (close >= 0 && commas == 2) {
				out.append("mcupscalerTexture2DBias(").append(rewriteBiasCalls(text.substring(open + 1, close))).append(')');
				i = close + 1;
			} else {
				out.append("texture2D");
				i = end;
			}
		}
	}

	/** True for the name in "#define texture2D(...)": the pack's own macro definitions are left alone. */
	private static boolean isMacroName(String text, int at) {
		int lineStart = text.lastIndexOf('\n', at) + 1;
		String before = text.substring(lineStart, at).trim();
		return before.startsWith("#") && before.replace(" ", "").replace("\t", "").equals("#define");
	}

	private static boolean isIdentifierChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	/** Index of the line after "#version" (0 without one): where defines and extensions can go. */
	private static int afterVersion(List<String> lines) {
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).trim().startsWith("#version")) {
				return i + 1;
			}
		}
		return 0;
	}

	private static List<String> replace(List<String> out, List<String> lines, int index, String line) {
		List<String> copy = out != null ? out : new ArrayList<>(lines);
		copy.set(index, line);
		return copy;
	}
}
