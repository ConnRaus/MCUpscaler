package dev.mcupscaler;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;

/**
 * The two depth images the motion vectors need: the world without the first-person hand ({@link #sceneDepth}) and the
 * hand ({@link #handDepth}).
 * <p>
 * Vanilla clears the depth before drawing the hand, so the world depth is copied right before that. Shader packs
 * (through Vitrail) draw the hand into the world depth instead, followed by water, the block outline and more: the depth
 * is copied around the hand passes and merged so that only the hand differs between the two. Distant Horizons terrain
 * drawn by a pack has its own depth buffer, which is merged into the scene depth as well.
 */
final class DepthCapture {
	private static final int COPY_USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING;
	private static final long TEX = DlssNative.TEX_SIZE;

	/** The world target while this frame captures depth, else null. */
	@Nullable
	private RenderTarget scene;
	@Nullable
	private GpuTexture sceneDepth;
	/**
	 * Depth texture the hand was drawn into: only hand/screen-effect depth (vanilla), or with a shader pack, a copy of the
	 * world depth including the hand (equal to {@link #sceneDepth} wherever there is no hand).
	 */
	@Nullable
	private GpuTexture handDepth;

	/** This frame's world depth was captured before a shader pack drew the hand into it (see {@link #beforePackHand}). */
	private boolean packHandFrame;
	@Nullable
	private GpuTexture packHandDepth;
	/** World depth right after a shader pack drew the hand (water, the block outline etc. come later). */
	@Nullable
	private GpuTexture packAfterHandDepth;
	private boolean packAfterHandCaptured;
	/** World depth right before and after a shader pack drew the translucent half of the hand (e.g. cut-out block items). */
	@Nullable
	private GpuTexture packPreTlHandDepth, packPostTlHandDepth;
	private boolean packPreTlCaptured, packPostTlCaptured;

	private final MemorySegment texScratch = Arena.global().allocate(TEX * 5, 16);
	private final Vector2f distantPair = new Vector2f();
	private boolean loggedMergeFailure, loggedDistantFailure, loggedDistantMerge;

	/** Start of a frame; {@code target} is the target the world is drawn into if this frame captures depth, else null. */
	void beginFrame(@Nullable RenderTarget target) {
		scene = target;
		handDepth = null;
		packHandFrame = false;
		packAfterHandCaptured = false;
		packPreTlCaptured = packPostTlCaptured = false;
		if (target != null) {
			sceneDepth = ensureCopy(sceneDepth, "Upscaler Scene Depth", GpuFormat.D32_FLOAT, target.width, target.height);
		}
	}

	void endFrame() {
		scene = null;
	}

	/** Both depth images of this frame, or null if they were not captured. */
	@Nullable
	GpuTexture sceneDepth() {
		return handDepth == null ? null : sceneDepth;
	}

	@Nullable
	GpuTexture handDepth() {
		return handDepth;
	}

	/** Right before render3dHud clears {@code texture} for the hand pass. */
	void beforeHandDepthClear(GpuTexture texture) {
		GpuTexture worldDepth = worldDepth();
		if (worldDepth == null) {
			packHandFrame = false;
			return;
		}
		if (packHandFrame) {
			// The pack already drew the hand into the world depth (sceneDepth holds the depth from before it).
			packHandFrame = false;
			packHandDepth = copy(worldDepth, packHandDepth, "Upscaler Pack Hand Depth");
			handDepth = packHandDepth;
			if (packAfterHandCaptured) {
				packAfterHandCaptured = false;
				mergePackDepth();
			}
		} else {
			copy(worldDepth, sceneDepth, null);
			handDepth = texture;
		}
		mergeDistantDepth();
	}

	/** Vitrail is about to draw the first-person hand into the world (its replacement of the hand pass). */
	void beforePackHand() {
		GpuTexture worldDepth = worldDepth();
		if (worldDepth != null && !packHandFrame) {
			copy(worldDepth, sceneDepth, null);
			packHandFrame = true;
		}
	}

	void afterPackHand() {
		GpuTexture worldDepth = worldDepth();
		if (worldDepth != null && packHandFrame) {
			packAfterHandDepth = copy(worldDepth, packAfterHandDepth, "Upscaler Pack After-Hand Depth");
			packAfterHandCaptured = true;
		}
	}

	void beforePackTranslucentHand() {
		GpuTexture worldDepth = worldDepth();
		if (worldDepth != null && packHandFrame) {
			packPreTlHandDepth = copy(worldDepth, packPreTlHandDepth, "Upscaler Pack Pre-Translucent-Hand Depth");
			packPreTlCaptured = true;
			packPostTlCaptured = false;
		}
	}

	void afterPackTranslucentHand() {
		GpuTexture worldDepth = worldDepth();
		if (worldDepth != null && packHandFrame && packPreTlCaptured) {
			packPostTlHandDepth = copy(worldDepth, packPostTlHandDepth, "Upscaler Pack Post-Translucent-Hand Depth");
			packPostTlCaptured = true;
		}
	}

	/**
	 * Packs draw water, the block outline and more after the hand. Without this they would only be in the hand depth and
	 * count as hand (no motion: they smear). Puts everything but the hand into {@link #sceneDepth}.
	 */
	private void mergePackDepth() {
		boolean translucent = packPreTlCaptured && packPostTlCaptured;
		packPreTlCaptured = packPostTlCaptured = false;
		int result = Platform.MAC
			? MetalBackend.mergePackDepth(sceneDepth, packAfterHandDepth, packHandDepth, translucent ? packPreTlHandDepth : null,
				translucent ? packPostTlHandDepth : null)
			: mergePackDepthVulkan(translucent);
		if (result <= 0 && !loggedMergeFailure) {
			loggedMergeFailure = true;
			UpscalerMod.LOGGER.warn("Could not merge the shader pack's depth: {}", lastError());
		}
	}

	private int mergePackDepthVulkan(boolean translucent) {
		long commandBuffer = Recording.commandBuffer();
		if (commandBuffer == 0L || !Recording.writeTex(texScratch, 0, sceneDepth) || !Recording.writeTex(texScratch, TEX, packAfterHandDepth)
			|| !Recording.writeTex(texScratch, 2 * TEX, packHandDepth)) {
			return -1;
		}
		translucent = translucent && Recording.writeTex(texScratch, 3 * TEX, packPreTlHandDepth)
			&& Recording.writeTex(texScratch, 4 * TEX, packPostTlHandDepth);
		if (!translucent) {
			// No translucent hand pass captured: compare a texture with itself (never a difference).
			texScratch.asSlice(3 * TEX, TEX).copyFrom(texScratch.asSlice(TEX, TEX));
			texScratch.asSlice(4 * TEX, TEX).copyFrom(texScratch.asSlice(TEX, TEX));
		}
		return DlssNative.mergePackDepth(commandBuffer, texScratch, texScratch.asSlice(TEX), texScratch.asSlice(2 * TEX),
			texScratch.asSlice(3 * TEX), texScratch.asSlice(4 * TEX));
	}

	/**
	 * Distant Horizons terrain (drawn by a shader pack, or by Distant Horizons itself) has its own depth buffer and leaves
	 * the world depth at sky: without this it gets camera rotation but no camera movement in its motion vectors (floats and
	 * smears while moving).
	 */
	private void mergeDistantDepth() {
		GpuTexture packDistant = VitrailCompat.distantDepth(distantPair);
		GpuTexture distant = packDistant != null ? packDistant : DistantHorizonsDepth.take(distantPair);
		if (distant == null) {
			return;
		}
		int result;
		if (Platform.MAC) {
			result = MetalBackend.mergeDistantDepth(sceneDepth, distant, distantPair.x, distantPair.y);
		} else {
			long commandBuffer = Recording.commandBuffer();
			if (commandBuffer == 0L || !Recording.writeTex(texScratch, 0, sceneDepth) || !Recording.writeTex(texScratch, TEX, distant)) {
				return;
			}
			result = DlssNative.mergeDistantDepth(commandBuffer, texScratch, texScratch.asSlice(TEX), distantPair.x, distantPair.y);
		}
		if (result <= 0 && !loggedDistantFailure) {
			loggedDistantFailure = true;
			UpscalerMod.LOGGER.warn("Could not merge the Distant Horizons depth: {}", lastError());
		} else if (result == 1 && !loggedDistantMerge) {
			loggedDistantMerge = true;
			UpscalerMod.LOGGER.info("Merging the {} Distant Horizons depth (far = {} * world + {})", packDistant != null ? "shader pack's" : "plain",
				distantPair.x, distantPair.y);
		}
	}

	private static String lastError() {
		return Platform.MAC ? MetalBackend.lastError() : DlssNative.lastError();
	}

	/** The world's depth texture, if this frame captures depth and it matches {@link #sceneDepth}. */
	@Nullable
	private GpuTexture worldDepth() {
		GpuTexture depth = scene == null || sceneDepth == null ? null : scene.getDepthTexture();
		if (depth == null || depth.getWidth(0) != sceneDepth.getWidth(0) || depth.getHeight(0) != sceneDepth.getHeight(0)) {
			return null;
		}
		return depth;
	}

	/** Copies {@code source} into {@code target}, first (re)creating it as {@code name} unless that is null. */
	private static GpuTexture copy(GpuTexture source, @Nullable GpuTexture target, @Nullable String name) {
		int width = source.getWidth(0), height = source.getHeight(0);
		if (name != null) {
			target = ensureCopy(target, name, source.getFormat(), width, height);
		}
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(source, target, 0, 0, 0, 0, 0, width, height);
		return target;
	}

	private static GpuTexture ensureCopy(@Nullable GpuTexture texture, String name, GpuFormat format, int width, int height) {
		if (texture != null && texture.getWidth(0) == width && texture.getHeight(0) == height && texture.getFormat() == format) {
			return texture;
		}
		if (texture != null) {
			texture.close();
		}
		return RenderSystem.getDevice().createTexture(name, COPY_USAGE, format, width, height, 1, 1);
	}
}
