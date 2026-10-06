package dev.mcupscaler;

import com.mojang.renderpearl.api.textures.GpuTexture;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;

/**
 * The depth of the Distant Horizons terrain when Distant Horizons draws it itself (no shader pack): it goes into its own
 * depth texture, in its own projection, and only its colour is copied into the world. Read through reflection when
 * Distant Horizons copies it (see the DistantHorizonsMetaRendererMixin).
 */
public final class DistantHorizonsDepth {
	private static boolean failed, loggedForwardZ;
	private static Field depthWrapper, clearDepth, dhProjection, mcProjection, m22, m23, m32, m33;
	private static Method texture;

	@Nullable
	private static GpuTexture captured;
	private static final Vector2f capturedPair = new Vector2f();

	private DistantHorizonsDepth() {
	}

	/** {@code metaRenderer} = BlazeDhMetaRenderer, {@code params} = its RenderParams. */
	public static void capture(Object metaRenderer, Object params) {
		captured = read(metaRenderer, params, capturedPair);
	}

	/** This frame's far terrain depth, or null (taken once per frame); {@code pair} receives (a, b) as in {@link #read}. */
	@Nullable
	static GpuTexture take(Vector2f pair) {
		GpuTexture texture = captured;
		captured = null;
		pair.set(capturedPair);
		return texture;
	}

	/** The depth texture, or null; {@code pair} receives (a, b) with far depth = a * world depth + b (both reversed-Z). */
	@Nullable
	private static GpuTexture read(Object metaRenderer, Object params, Vector2f pair) {
		if (failed) {
			return null;
		}
		try {
			if (texture == null) {
				lookUp(metaRenderer.getClass(), params.getClass());
			}
			if (clearDepth.getFloat(metaRenderer) != 0.0F) {
				// Forward Z: the merge (and its sky test) expects reversed Z like Minecraft's.
				if (!loggedForwardZ) {
					loggedForwardZ = true;
					UpscalerMod.LOGGER.warn("Distant Horizons renders with forward Z; far terrain gets no motion vectors");
				}
				return null;
			}
			Object wrapper = depthWrapper.get(metaRenderer);
			Object dh = dhProjection.get(params), mc = mcProjection.get(params);
			if (wrapper == null || dh == null || mc == null || !(texture.invoke(wrapper) instanceof GpuTexture depth)) {
				return null;
			}
			// Both are 0..1 reversed-Z perspective projections of the same view, so one depth is linear in the other: fit
			// it through two distances.
			float g1 = depthAt(mc, -32.0F), g2 = depthAt(mc, -512.0F);
			float h1 = depthAt(dh, -32.0F), h2 = depthAt(dh, -512.0F);
			float a = (h1 - h2) / (g1 - g2);
			if (!Float.isFinite(a) || a <= 0.0F) {
				return null;
			}
			pair.set(a, h1 - a * g1);
			return depth;
		} catch (ReflectiveOperationException | RuntimeException e) {
			failed = true;
			UpscalerMod.LOGGER.warn("Could not read Distant Horizons' depth; far terrain gets no motion vectors", e);
			return null;
		}
	}

	/** Depth of view-space z (negative = in front) through a DhApiMat4f (fields named m<row><column>). */
	private static float depthAt(Object matrix, float z) throws IllegalAccessException {
		return (m22.getFloat(matrix) * z + m23.getFloat(matrix)) / (m32.getFloat(matrix) * z + m33.getFloat(matrix));
	}

	private static void lookUp(Class<?> metaRenderer, Class<?> params) throws ReflectiveOperationException {
		depthWrapper = metaRenderer.getField("dhDepthTextureWrapper");
		clearDepth = metaRenderer.getDeclaredField("clearDepth");
		clearDepth.setAccessible(true);
		dhProjection = params.getField("dhProjectionMatrix");
		mcProjection = params.getField("mcProjectionMatrix");
		Class<?> matrix = dhProjection.getType();
		m22 = matrix.getField("m22");
		m23 = matrix.getField("m23");
		m32 = matrix.getField("m32");
		m33 = matrix.getField("m33");
		texture = depthWrapper.getType().getMethod("getTexture");
	}
}
