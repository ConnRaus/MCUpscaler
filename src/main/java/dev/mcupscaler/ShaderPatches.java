package dev.mcupscaler;

import net.minecraft.resources.Identifier;

/**
 * Source patches for the terrain shaders (vanilla and Sodium), applied as they are loaded.
 * <p>
 * Minecraft's terrain shaders pick the mip level and the width of the anti-aliased texel edges from screen-space
 * derivatives. At a reduced render resolution those derivatives are larger, so textures come out a mip level
 * blurrier and texel edges get smeared over more output pixels. The temporal upscaler can only reconstruct
 * detail that was sampled, so while it runs the derivatives are scaled by the render scale ("negative LOD bias"),
 * making the shaders sample textures as if rendering at the output resolution.
 * <p>
 * The scale reaches the shader through the existing RGSS flag (vanilla {@code UseRgss}, Sodium {@code u_UseRGSS}):
 * bit 0 stays the RGSS flag, the bits above hold {@code round(scale * 1024)} (0 = no scaling).
 */
public final class ShaderPatches {
	private static final String SCALE_FROM_VANILLA = "((UseRgss >> 1) > 0 ? float(UseRgss >> 1) / 1024.0 : 1.0)";
	private static final String SCALE_FROM_SODIUM = "((u_UseRGSS >> 1) > 0 ? float(u_UseRGSS >> 1) / 1024.0 : 1.0)";

	private ShaderPatches() {
	}

	/** Encodes the RGSS flag together with the current LOD scale. */
	public static int encodeRgss(int rgssFlag) {
		return (rgssFlag & 1) | (WorldUpscaler.lodScaleCode() << 1);
	}

	public static String patch(Identifier location, String source) {
		String path = location.getNamespace() + ":" + location.getPath();
		String patched = switch (path) {
			case "minecraft:shaders/include/texture_sampling.glsl" -> patchSamplingInclude(source);
			case "minecraft:shaders/core/terrain.fsh" -> patchVanillaTerrain(source);
			case "sodium:shaders/include/globals.glsl" -> require(source, "bool u_UseRGSS;", "int u_UseRGSS;");
			case "sodium:shaders/blocks/block_layer_opaque.fsh" -> patchSodiumTerrain(source);
			default -> null;
		};
		if (patched == null) {
			return source;
		}
		if (patched.equals(source)) {
			UpscalerMod.LOGGER.warn("Could not patch shader {} (unexpected source); texture LOD correction disabled for it", path);
			return source;
		}
		UpscalerMod.LOGGER.info("Patched shader {} for render-scale aware texture sampling", path);
		return patched;
	}

	private static String scaleDerivatives(String source) {
		return source.replace("vec2 du = dFdx(uv);", "vec2 du = dFdx(uv) * MCUPSCALER_LOD_SCALE;")
			.replace("vec2 dv = dFdy(uv);", "vec2 dv = dFdy(uv) * MCUPSCALER_LOD_SCALE;");
	}

	private static String patchSamplingInclude(String source) {
		String withDefault = require(
			source,
			"#define MINECRAFT_TEXTURE_SAMPLING_GLSL",
			"#define MINECRAFT_TEXTURE_SAMPLING_GLSL\n#ifndef MCUPSCALER_LOD_SCALE\n#define MCUPSCALER_LOD_SCALE 1.0\n#endif"
		);
		return withDefault == source ? source : scaleDerivatives(withDefault);
	}

	private static String patchVanillaTerrain(String source) {
		String s = require(source, "UseRgss == 1", "(UseRgss & 1) == 1");
		return require(
			s,
			"#include <minecraft:texture_sampling.glsl>",
			"#define MCUPSCALER_LOD_SCALE " + SCALE_FROM_VANILLA + "\n#include <minecraft:texture_sampling.glsl>"
		);
	}

	private static String patchSodiumTerrain(String source) {
		String s = require(source, "u_UseRGSS ?", "(u_UseRGSS & 1) != 0 ?");
		s = require(s, "#include <sodium:globals.glsl>", "#include <sodium:globals.glsl>\n#define MCUPSCALER_LOD_SCALE " + SCALE_FROM_SODIUM);
		return s == source ? source : scaleDerivatives(s);
	}

	/** Replaces every occurrence, or returns the input unchanged if the pattern is missing. */
	private static String require(String source, String pattern, String replacement) {
		return source.contains(pattern) ? source.replace(pattern, replacement) : source;
	}
}
