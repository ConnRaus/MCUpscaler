package dev.dlssmc;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;

/**
 * Vitrail's depth of the Distant Horizons terrain drawn by a shader pack, read through reflection (Vitrail has no API
 * for it). Taken right after Vitrail drew the far terrain: its served flag is gone again by the hand pass.
 */
final class VitrailDistantDepth {
	private static boolean failed;
	private static Method distant, served, world, depthPair;
	private static Field values;

	@Nullable
	private static GpuTexture captured;
	private static final Vector2f capturedPair = new Vector2f();

	private VitrailDistantDepth() {
	}

	static void capture() {
		captured = read(capturedPair);
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
	private static GpuTexture read(Vector2f pair) {
		if (failed) {
			return null;
		}
		try {
			if (distant == null) {
				lookUp();
			}
			Object draw = distant.invoke(null);
			if (draw == null) {
				return null;
			}
			Object view = served.invoke(draw);
			Object packValues = values.get(draw);
			if (!(view instanceof GpuTextureView depthView) || packValues == null) {
				return null;
			}
			Object worldState = world.invoke(packValues);
			if (worldState == null || !(boolean)depthPair.invoke(worldState, pair)) {
				return null;
			}
			return depthView.texture();
		} catch (ReflectiveOperationException | RuntimeException e) {
			failed = true;
			DlssMod.LOGGER.warn("Could not read Vitrail's Distant Horizons depth; far terrain gets no motion vectors", e);
			return null;
		}
	}

	private static void lookUp() throws ReflectiveOperationException {
		Method distantMethod = Class.forName("dev.vitrail.render.PackChain").getDeclaredMethod("distant");
		distantMethod.setAccessible(true);
		Class<?> draw = Class.forName("dev.vitrail.render.DistantDraw");
		served = draw.getDeclaredMethod("served");
		served.setAccessible(true);
		values = draw.getDeclaredField("values");
		values.setAccessible(true);
		world = Class.forName("dev.vitrail.render.PackValues").getMethod("world");
		depthPair = Class.forName("dev.vitrail.uniform.WorldState").getMethod("distantDepthPair", Vector2f.class);
		distant = distantMethod;
	}
}
