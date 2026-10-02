package dev.metalfxmc;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Vitrail shader pack support. While a temporal upscaler runs, the pack's sources are patched as they load: textures get
 * a LOD bias for the render scale (as {@link ShaderPatches} does for vanilla and Sodium), the pack's own anti-aliasing
 * is switched off, and optionally its waving foliage. The patches are baked into the sources, so the pack reloads when
 * they need to change. Also reads Vitrail's Distant Horizons depth, which the motion vectors need.
 */
public final class VitrailCompat {
	private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("vitrail");

	/** Bias baked into the pack the last time its geometry shaders were read; NaN = no pack read yet. */
	private static volatile float bakedBias = Float.NaN;
	/** Whether the pack's own anti-aliasing was switched off the last time it was read. */
	private static volatile boolean bakedAaOff;
	/** A pack's own anti-aliasing switches: "#define TAA" / "#define FXAA" (BSL, Bliss, Complementary). */
	private static final java.util.regex.Pattern AA_DEFINE = java.util.regex.Pattern.compile("^(\\s*)#define\\s+(TAA|FXAA)\\s*(//.*)?$");
	/** Whether the pack's waving foliage was switched off the last time it was read. */
	private static volatile boolean bakedStill;
	/** Waving plants, leaves and water (vertex animation) in BSL, Bliss and Complementary. */
	private static final java.util.regex.Pattern WAVING_DEFINE = java.util.regex.Pattern.compile(
		"^(\\s*)#define\\s+(WAVY_PLANTS|WAVING_(?:PLANT|GRASS|TALL_PLANT|CROP|LEAF|VINE|WATER|FOLIAGE|LEAVES|WATER_VERTEX))\\s*(//.*)?$"
	);
	/** "#ifdef TAA" / "#if defined TAA" on its own. */
	private static final java.util.regex.Pattern TAA_IF = java.util.regex.Pattern.compile(
		"^\\s*#\\s*(?:ifdef\\s+TAA|if\\s+defined\\s*\\(?\\s*TAA\\s*\\)?)\\s*(//.*)?$"
	);
	private static boolean reloadFailed;

	private VitrailCompat() {
	}

	/** The texture LOD bias the current settings want for shader packs (0 = none). */
	public static float wantedBias() {
		boolean temporal = MetalFXConfig.upscaler == MetalFXConfig.Upscaler.TEMPORAL
			|| MetalFXConfig.upscaler == MetalFXConfig.Upscaler.FSR;
		if (!MetalFXConfig.enabled || !temporal || !MetalFXConfig.textureLodCorrection || MetalFXConfig.renderScale >= 0.999F) {
			return 0.0F;
		}
		// Rounded so tiny float differences don't trigger reloads.
		return Math.round((float) (Math.log(WorldUpscaler.effectiveScale()) / Math.log(2.0)) * 100.0F) / 100.0F;
	}

	/**
	 * The temporal upscalers anti-alias the image themselves (that is what their jitter is for), so the pack's own TAA
	 * and FXAA only blur and ghost on top of it: they are switched off while one is active.
	 */
	public static boolean wantedAaOff() {
		return MetalFXConfig.enabled && (MetalFXConfig.upscaler == MetalFXConfig.Upscaler.TEMPORAL || MetalFXConfig.upscaler == MetalFXConfig.Upscaler.FSR);
	}

	/**
	 * Waving foliage moves its vertices in the shader, which the depth-based motion vectors can't see: the temporal
	 * upscalers ghost and smear it. Switched off while one is active (an option).
	 */
	public static boolean wantedStill() {
		return wantedAaOff() && MetalFXConfig.stillPackFoliage;
	}

	private static List<String> disableAa(List<String> lines) {
		return keepTaaNoise(commentOut(lines, AA_DEFINE, "off: MetalFX's temporal upscaler anti-aliases"));
	}

	/**
	 * Packs only animate their dither noise when their TAA is on ("#ifdef TAA" around a frameCounter offset). Static
	 * noise is kept by the temporal upscalers as if it were detail (a fixed checker pattern), so those small blocks,
	 * and the TAA-only uniform declarations they need, stay enabled. The pack's jitter and its resolve pass stay off.
	 */
	private static List<String> keepTaaNoise(List<String> lines) {
		List<String> out = null;
		for (int i = 0; i < lines.size(); i++) {
			if (!TAA_IF.matcher(lines.get(i)).matches()) {
				continue;
			}
			List<String> body = new ArrayList<>();
			boolean simple = true;
			int j = i + 1;
			for (; j < lines.size(); j++) {
				String t = lines.get(j).trim();
				if (t.startsWith("#")) {
					String d = t.substring(1).trim();
					simple = d.startsWith("else") || d.startsWith("elif") || d.startsWith("endif");
					break;
				}
				if (!t.isEmpty()) {
					body.add(t);
				}
			}
			if (!simple || j >= lines.size() || body.isEmpty() || body.size() > 4) {
				continue;
			}
			boolean uniforms = body.stream().allMatch(t -> t.startsWith("uniform "));
			boolean noise = body.stream().anyMatch(t -> t.contains("frameCounter"))
				&& body.stream().noneMatch(t -> t.contains("gl_Position") || t.startsWith("uniform ") || t.startsWith("#"));
			if (uniforms || noise) {
				if (out == null) {
					out = new ArrayList<>(lines);
				}
				out.set(i, "#if 1 // MetalFX: the pack's TAA is off, but its animated noise is kept for the temporal upscaler");
			}
		}
		return out != null ? out : lines;
	}

	private static List<String> commentOut(List<String> lines, java.util.regex.Pattern define, String why) {
		List<String> out = null;
		for (int i = 0; i < lines.size(); i++) {
			java.util.regex.Matcher m = define.matcher(lines.get(i));
			if (m.matches()) {
				if (out == null) {
					out = new ArrayList<>(lines);
				}
				out.set(i, m.group(1) + "//#define " + m.group(2) + " // " + why);
			}
		}
		return out != null ? out : lines;
	}

	/** Called for every file Vitrail reads from a shader pack. */
	public static List<String> patchLines(Path path, List<String> lines) {
		Path fileName = path.getFileName();
		if (fileName == null || lines == null) {
			return lines;
		}
		String name = fileName.toString();
		boolean geometryFragment = name.endsWith(".fsh") && (name.startsWith("gbuffers_") || name.startsWith("dh_"));
		boolean shaderSource = name.endsWith(".fsh") || name.endsWith(".vsh") || name.endsWith(".gsh") || name.endsWith(".csh")
			|| name.endsWith(".glsl") || name.endsWith(".inc");
		if (!shaderSource) {
			return lines;
		}
		boolean aaOff = wantedAaOff();
		bakedAaOff = aaOff;
		if (aaOff) {
			lines = disableAa(lines);
		}
		boolean still = wantedStill();
		bakedStill = still;
		if (still) {
			lines = commentOut(lines, WAVING_DEFINE, "off: no motion vectors for the temporal upscaler");
		}
		if (!geometryFragment) {
			// Included files and other programs: only the biased texture2D calls are rewritten (see rewriteBiasCalls).
			return wantedBias() == 0.0F ? lines : rewriteBiasCalls(lines);
		}
		float bias = wantedBias();
		bakedBias = bias;
		if (bias == 0.0F) {
			return lines;
		}
		lines = rewriteBiasCalls(lines);
		int insertAt = 0;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).trim().startsWith("#version")) {
				insertAt = i + 1;
				break;
			}
		}
		List<String> patched = new ArrayList<>(lines.size() + 2);
		patched.addAll(lines.subList(0, insertAt));
		// Sample with the screen-space derivatives scaled by the render scale, i.e. as if rendering at the output
		// resolution (a texture2D bias argument would do the same, but Vitrail's translator rejects it).
		float scale = (float) Math.pow(2.0, bias);
		patched.add("#extension GL_ARB_shader_texture_lod : enable");
		patched.add(String.format(Locale.ROOT, "#define METALFX_LOD_SCALE %.4f", scale));
		patched.add(
			"#define texture2D(metalfxS, metalfxUv) texture2DGradARB(metalfxS, metalfxUv, dFdx(metalfxUv) * METALFX_LOD_SCALE, dFdy(metalfxUv) * METALFX_LOD_SCALE)"
		);
		patched.addAll(lines.subList(insertAt, lines.size()));
		return patched;
	}

	/**
	 * GLSL macros can't be overloaded, so the two-argument texture2D macro breaks the pack's three-argument (bias)
	 * calls, e.g. Bliss's {@code texture2D(tex, uv, Texture_MipMap_Bias)}. Those calls become {@code metalfxTexture2DBias},
	 * which is the gradient lookup (bias folded into the derivatives) where the LOD macro is active and the plain biased
	 * lookup elsewhere, so the same rewritten include works in every program that uses it.
	 */
	private static List<String> rewriteBiasCalls(List<String> lines) {
		String text = String.join("\n", lines);
		String rewritten = rewriteBiasCalls(text);
		if (rewritten.equals(text)) {
			return lines;
		}
		List<String> out = new ArrayList<>(lines.size() + 8);
		int insertAt = 0;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).trim().startsWith("#version")) {
				insertAt = i + 1;
				break;
			}
		}
		List<String> rewrittenLines = List.of(rewritten.split("\n", -1));
		out.addAll(rewrittenLines.subList(0, insertAt));
		out.add("#ifndef METALFX_TEXTURE2D_BIAS");
		out.add("#define METALFX_TEXTURE2D_BIAS");
		out.add("#ifdef METALFX_LOD_SCALE");
		out.add("#define metalfxTexture2DBias(metalfxS, metalfxUv, metalfxB) texture2DGradARB(metalfxS, metalfxUv, "
			+ "dFdx(metalfxUv) * (METALFX_LOD_SCALE * exp2(metalfxB)), dFdy(metalfxUv) * (METALFX_LOD_SCALE * exp2(metalfxB)))");
		out.add("#else");
		out.add("#define metalfxTexture2DBias(metalfxS, metalfxUv, metalfxB) texture2D(metalfxS, metalfxUv, metalfxB)");
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
				out.append("metalfxTexture2DBias(").append(rewriteBiasCalls(text.substring(open + 1, close))).append(')');
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

	/** Reloads the shader pack if the baked-in bias no longer matches the settings. Call between frames. */
	public static void tick() {
		if (!PRESENT || reloadFailed || Float.isNaN(bakedBias)) {
			return;
		}
		float wanted = wantedBias();
		boolean aaOff = wantedAaOff();
		boolean still = wantedStill();
		if (wanted == bakedBias && aaOff == bakedAaOff && still == bakedStill) {
			return;
		}
		bakedBias = wanted;
		bakedAaOff = aaOff;
		bakedStill = still;
		try {
			Class<?> kept = Class.forName("dev.vitrail.pack.source.KeptPack");
			Method forget = kept.getDeclaredMethod("forget");
			forget.setAccessible(true);
			forget.invoke(null);
			Class<?> choice = Class.forName("dev.vitrail.render.PackChoice");
			choice.getMethod("reload", Path.class).invoke(null, FabricLoader.getInstance().getGameDir());
			MetalFXMod.LOGGER.info("Reloaded the shader pack with texture LOD bias {}, own anti-aliasing {}, waving foliage {}", wanted,
				aaOff ? "off" : "on", still ? "off" : "on");
		} catch (ReflectiveOperationException | RuntimeException e) {
			reloadFailed = true;
			MetalFXMod.LOGGER.warn("Could not reload the Vitrail shader pack for texture LOD correction", e);
		}
	}

	private static Method distantMethod;
	private static Method servedMethod;
	private static java.lang.reflect.Field valuesField;
	private static Method worldMethod;
	private static Method depthPairMethod;
	private static boolean distantFailed;

	private static com.mojang.renderpearl.api.textures.@org.jspecify.annotations.Nullable GpuTexture capturedDistant;
	private static final org.joml.Vector2f capturedPair = new org.joml.Vector2f();

	/** Called right after Vitrail took the far terrain's depth (its served flag is gone again by the hand pass). */
	public static void captureDistantDepth() {
		capturedDistant = readDistantDepth(capturedPair);
	}

	/**
	 * Vitrail's depth of the Distant Horizons terrain drawn this frame, or null (taken once per frame). {@code pair}
	 * receives (a, b) with far depth = a * world depth + b (both reversed-Z).
	 */
	public static com.mojang.renderpearl.api.textures.@org.jspecify.annotations.Nullable GpuTexture distantDepth(org.joml.Vector2f pair) {
		var texture = capturedDistant;
		capturedDistant = null;
		pair.set(capturedPair);
		return texture;
	}

	private static com.mojang.renderpearl.api.textures.@org.jspecify.annotations.Nullable GpuTexture readDistantDepth(org.joml.Vector2f pair) {
		if (!PRESENT || distantFailed) {
			return null;
		}
		try {
			if (distantMethod == null) {
				distantMethod = Class.forName("dev.vitrail.render.PackChain").getDeclaredMethod("distant");
				distantMethod.setAccessible(true);
				Class<?> draw = Class.forName("dev.vitrail.render.DistantDraw");
				servedMethod = draw.getDeclaredMethod("served");
				servedMethod.setAccessible(true);
				valuesField = draw.getDeclaredField("values");
				valuesField.setAccessible(true);
				worldMethod = Class.forName("dev.vitrail.render.PackValues").getMethod("world");
				depthPairMethod = Class.forName("dev.vitrail.uniform.WorldState").getMethod("distantDepthPair", org.joml.Vector2f.class);
			}
			Object distant = distantMethod.invoke(null);
			if (distant == null) {
				return null;
			}
			Object view = servedMethod.invoke(distant);
			Object values = valuesField.get(distant);
			if (!(view instanceof com.mojang.renderpearl.api.textures.GpuTextureView depthView) || values == null) {
				return null;
			}
			Object world = worldMethod.invoke(values);
			if (world == null || !(boolean)depthPairMethod.invoke(world, pair)) {
				return null;
			}
			return depthView.texture();
		} catch (ReflectiveOperationException | RuntimeException e) {
			distantFailed = true;
			MetalFXMod.LOGGER.warn("Could not read Vitrail's Distant Horizons depth; far terrain gets no motion vectors", e);
			return null;
		}
	}
}
