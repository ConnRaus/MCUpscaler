package dev.mcupscaler;

import java.nio.ByteBuffer;
import org.lwjgl.util.shaderc.Shaderc;

/**
 * GLSL compute shaders run by the native bridge, compiled to SPIR-V with the shaderc library that ships with
 * Minecraft. Order matches PassId in src/native/bridge.h.
 */
public final class Shaders {
	private Shaders() {
	}

	/**
	 * Camera motion vectors from depth: each pixel is reconstructed relative to the camera, moved by the camera's movement
	 * (and the player's, inside the third-person player box) and projected with the previous frame's matrices. Also
	 * writes the depth DLSS and Frame Generation see: the scene depth with the first-person hand on top.
	 * <p>
	 * The first-person hand is drawn with its own projection and pose (bobbing, swaying behind the camera): its pixels are
	 * taken back through this frame's hand matrices and projected with the previous frame's (see HandMotion).
	 */
	private static final String MOTION = """
		#version 450
		layout(local_size_x = 16, local_size_y = 16) in;
		layout(binding = 0) uniform sampler2D sceneDepth;
		layout(binding = 1) uniform sampler2D handDepth;
		layout(binding = 2, rg16f) uniform writeonly image2D motion;
		layout(binding = 3, r32f) uniform writeonly image2D depthOut;
		layout(binding = 4, std430) readonly buffer Boxes {
			mat4 handClipToLocal[2];     // per arm (right, left) in p.handMotion: inverse(projection * model-view * pose)
			mat4 prevHandLocalToClip[2]; // the previous frame's projection * model-view * pose
			vec4 boxes[];             // per entity: min, max, (delta, current-colour bias)
		};
		layout(binding = 5, r8) uniform writeonly image2D biasMask;
		layout(push_constant, std430) uniform Params {
			mat4 invViewProj;
			mat4 prevViewProj;
			vec4 camDelta;
			vec4 objMin;
			vec4 objMax;
			vec4 objDelta;
			uint zZeroToOne;
			uint boxCount;
			uint handMotion;
		} p;
		const float NEAR = 0.05; // Minecraft's near plane
		void main() {
			ivec2 size = imageSize(motion);
			ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
			if (gid.x >= size.x || gid.y >= size.y) return;
			float d = texelFetch(sceneDepth, gid, 0).r;
			float hd = texelFetch(handDepth, gid, 0).r;
			// Hand depth is the hand alone (vanilla: 0 elsewhere) or the scene with the hand (shader packs draw it into the
			// world's depth: equal to the scene depth elsewhere).
			bool hand = hd > 0.0 && hd != d;
			vec2 uv = (vec2(gid) + 0.5) / vec2(size);
			vec2 ndc = uv * 2.0 - 1.0;
			float dd = hand ? max(hd, d) : d;
			float z = p.zZeroToOne != 0u ? dd : dd * 2.0 - 1.0;
			vec4 rel = p.invViewProj * vec4(ndc, z, 1.0);
			// Depth for DLSS: reversed-Z with an infinite far plane, near / view depth (rel.w = 1 / clip w = 1 / view depth).
			// Close to Minecraft's own depth, but stays positive and smooth beyond the far plane, where Distant Horizons'
			// terrain has negative depth after the merge. Frame generation gets matching matrices (FrameGen.writeCamera).
			imageStore(depthOut, gid, vec4(dd == 0.0 ? 0.0 : min(NEAR * abs(rel.w), 1.0)));
			vec4 prevClip = vec4(0.0);
			float bias = 0.0;
			if (hand) {
				// The point of the hand drawn here, and where the previous frame drew it. Without the matrices: no motion.
				if (p.handMotion != 0u) {
					// The right arm draws on the right half of the screen, the left arm on the left.
					uint arm = ndc.x >= 0.0 ? 0u : 1u;
					if ((p.handMotion & (1u << arm)) == 0u) arm ^= 1u;
					float hz = p.zZeroToOne != 0u ? hd : hd * 2.0 - 1.0;
					prevClip = prevHandLocalToClip[arm] * (handClipToLocal[arm] * vec4(ndc, hz, 1.0));
				}
			} else {
				rel /= rel.w;
				vec3 prev = rel.xyz + p.camDelta.xyz;
				if (all(greaterThanEqual(rel.xyz, p.objMin.xyz)) && all(lessThanEqual(rel.xyz, p.objMax.xyz))) prev -= p.objDelta.xyz;
				for (uint i = 0u; i < p.boxCount; i++) {
					if (all(greaterThanEqual(rel.xyz, boxes[i * 3u].xyz)) && all(lessThanEqual(rel.xyz, boxes[i * 3u + 1u].xyz))) {
						prev -= boxes[i * 3u + 2u].xyz;
						bias = boxes[i * 3u + 2u].w;
						break;
					}
				}
				prevClip = p.prevViewProj * vec4(prev, 1.0);
			}
			imageStore(biasMask, gid, vec4(bias));
			vec2 mv = vec2(0.0);
			if (prevClip.w > 0.0) {
				vec2 prevUv = prevClip.xy / prevClip.w * 0.5 + 0.5;
				mv = (prevUv - uv) * vec2(size);
			}
			imageStore(motion, gid, vec4(mv, 0.0, 0.0));
		}
		""";

	/**
	 * After upscaling: the shader pack's vignette, which was taken out of the pack (see VitrailCompat#rewrittenLine) so DLSS
	 * doesn't move it with the world. kind = a PackVignette shape, a and b its settings. Packs darken before their
	 * tonemapping; here the image is already display-encoded, so the darkening is applied to linear light, which comes
	 * close (Complementary's is applied to the display values, as the pack does).
	 */
	private static final String VIGNETTE = """
		layout(push_constant, std430) uniform Params { int kind; float a; float b; } p;
		vec3 packVignette(vec3 c, ivec2 gid, ivec2 size) {
			if (p.kind == 0) return c;
			vec2 uv = (vec2(gid) + 0.5) / vec2(size);
			vec2 d = uv - 0.5;
			float v = 1.0;
			bool linear = true;
			if (p.kind == 1) { // BSL
				float s = length(d);
				s *= s * 0.3535 + 0.75;
				v = 1.0 - s * p.a;
			} else if (p.kind == 2) { // IterationT
				vec2 q = d * 2.0;
				q.x *= mix(1.0, float(size.x) / float(size.y), p.b);
				float rf = dot(q, q) * p.a * p.a + 1.0;
				v = 1.0 / (rf * rf);
			} else if (p.kind == 3) { // Photon
				v = pow(max(16.0 * uv.x * uv.y * (1.0 - uv.x) * (1.0 - uv.y), 0.0), 0.08 * p.a);
			} else if (p.kind == 4) { // Complementary
				linear = false;
				float lum = dot(c, vec3(0.2125, 0.7154, 0.0721));
				v = 1.0 - dot(d, d) * (1.0 - lum);
			}
			v = clamp(v, 0.0, 1.0);
			vec3 rgb = linear ? pow(pow(max(c, vec3(0.0)), vec3(2.2)) * v, vec3(1.0 / 2.2)) : c * v;
			return clamp(rgb, 0.0, 1.0);
		}
		""";

	private static final String POST = """
		#version 450
		layout(local_size_x = 16, local_size_y = 16) in;
		layout(binding = 0) uniform sampler2D inImage;
		layout(binding = 1, rgba8) uniform writeonly image2D outImage;
		""" + VIGNETTE + """
		void main() {
			ivec2 size = imageSize(outImage);
			ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
			if (gid.x >= size.x || gid.y >= size.y) return;
			vec4 c = texelFetch(inImage, gid, 0);
			imageStore(outImage, gid, vec4(packVignette(c.rgb, gid, size), c.a));
		}
		""";

	/**
	 * After frame generation, which interpolates the world without the HUD: the generated frame gets this frame's HUD
	 * (final - world; pixels that differ a lot are taken from the real frame) and the shader pack's vignette, so both stay
	 * put on screen. The same as fg_fsr_composite in metalfx_bridge.m.
	 */
	private static final String FG_COMPOSITE = """
		#version 450
		layout(local_size_x = 16, local_size_y = 16) in;
		layout(binding = 0, rgba8) uniform image2D generated;
		layout(binding = 1) uniform sampler2D world;
		layout(binding = 2) uniform sampler2D finalImage;
		""" + VIGNETTE + """
		void main() {
			ivec2 size = imageSize(generated);
			ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
			if (gid.x >= size.x || gid.y >= size.y) return;
			vec3 f = texelFetch(finalImage, gid, 0).rgb;
			vec3 g = packVignette(imageLoad(generated, gid).rgb, gid, size);
			vec3 delta = f - packVignette(texelFetch(world, gid, 0).rgb, gid, size);
			float keep = smoothstep(0.08, 0.3, max(max(abs(delta.r), abs(delta.g)), abs(delta.b)));
			imageStore(generated, gid, vec4(mix(clamp(g + delta, 0.0, 1.0), f, keep), 1.0));
		}
		""";

	/**
	 * Shader packs draw the hand into the world depth before water, the block outline and the rest. pre = depth before the
	 * hand, post = right after it, fin = final: writes fin everywhere except the hand (pre there), so that the scene depth
	 * keeps translucents and differs from fin exactly where the hand is.
	 */
	private static final String PACK_MERGE = """
		#version 450
		layout(local_size_x = 16, local_size_y = 16) in;
		layout(binding = 0) uniform sampler2D pre;
		layout(binding = 1) uniform sampler2D post;
		layout(binding = 2) uniform sampler2D fin;
		layout(binding = 3) uniform sampler2D preTl;
		layout(binding = 4) uniform sampler2D postTl;
		layout(binding = 5, std430) writeonly buffer Out { float depth[]; } outBuf;
		void main() {
			ivec2 size = textureSize(pre, 0);
			ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
			if (gid.x >= size.x || gid.y >= size.y) return;
			float a = texelFetch(pre, gid, 0).r;
			float p = texelFetch(post, gid, 0).r;
			// Hand: changed by the solid hand pass, or by the translucent one (preTl/postTl, the depth around it).
			float t = texelFetch(preTl, gid, 0).r;
			bool hand = p != a || texelFetch(postTl, gid, 0).r != t;
			outBuf.depth[gid.y * size.x + gid.x] = hand ? (p != a ? a : t) : texelFetch(fin, gid, 0).r;
		}
		""";

	/**
	 * Distant Horizons terrain has its own depth buffer (its own projection) and leaves the world depth at 0 (sky). Puts
	 * it into the scene depth, in the game's projection (depth = (dh - b) / a; past the game's far plane that is below 0).
	 */
	private static final String DISTANT_MERGE = """
		#version 450
		layout(local_size_x = 16, local_size_y = 16) in;
		layout(binding = 0) uniform sampler2D scene;
		layout(binding = 1) uniform sampler2D dh;
		layout(binding = 2, std430) writeonly buffer Out { float depth[]; } outBuf;
		layout(push_constant, std430) uniform Params { uint w; uint h; float a; float b; } p;
		void main() {
			ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
			if (gid.x >= int(p.w) || gid.y >= int(p.h)) return;
			float d = texelFetch(scene, gid, 0).r;
			float f = texelFetch(dh, gid, 0).r;
			outBuf.depth[gid.y * int(p.w) + gid.x] = (d <= 0.0 && f > 0.0) ? (f - p.b) / p.a : d;
		}
		""";

	/** SPIR-V for all passes, in PassId order. */
	public static byte[][] compileAll() {
		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		try {
			Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
			Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
			return new byte[][] {
				compile(compiler, options, MOTION, "mcupscaler_motion.comp"),
				compile(compiler, options, PACK_MERGE, "mcupscaler_pack_merge.comp"),
				compile(compiler, options, DISTANT_MERGE, "mcupscaler_distant_merge.comp"),
				compile(compiler, options, POST, "mcupscaler_post.comp"),
				compile(compiler, options, FG_COMPOSITE, "mcupscaler_fg_composite.comp")
			};
		} finally {
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
		}
	}

	private static byte[] compile(long compiler, long options, String source, String name) {
		long result = Shaderc.shaderc_compile_into_spv(compiler, source, Shaderc.shaderc_compute_shader, name, "main", options);
		try {
			if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
				throw new IllegalStateException("Could not compile " + name + ": " + Shaderc.shaderc_result_get_error_message(result));
			}
			ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
			byte[] out = new byte[bytes.remaining()];
			bytes.get(out);
			return out;
		} finally {
			Shaderc.shaderc_result_release(result);
		}
	}
}
