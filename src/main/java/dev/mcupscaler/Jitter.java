package dev.mcupscaler;

import org.joml.Matrix4f;

/**
 * DLSS's sub-pixel camera jitter: a Halton(2,3) sequence with NVIDIA's recommended 8 * (output/input)^2 phases. The
 * offset is applied to the projection as a clip-space translation (or, for packs that jitter through their own uniform,
 * through that uniform: see {@link VitrailCompat#overridePackJitter}).
 */
final class Jitter {
	private int frameIndex;
	/** Offset in render-resolution pixels, in [-0.5, 0.5). */
	private float pixelX, pixelY;
	/** The same offset in clip space (NDC). */
	private float ndcX, ndcY;

	/** Moves on to the next phase for a {@code width} x {@code height} render at {@code outputRatio} upscaling. */
	void advance(int width, int height, double outputRatio) {
		float ratio = (float)outputRatio;
		int phases = Math.max(8, Math.min(128, Math.round(8.0F * ratio * ratio)));
		int phase = frameIndex++ % phases + 1;
		pixelX = halton(phase, 2) - 0.5F;
		pixelY = halton(phase, 3) - 0.5F;
		// Texture rows grow with NDC y.
		ndcX = 2.0F * pixelX / width;
		ndcY = 2.0F * pixelY / height;
	}

	/** Premultiplies {@code projection} by this frame's clip-space shift (sign -1 takes it back out). */
	Matrix4f apply(Matrix4f projection, float sign) {
		return projection.translateLocal(sign * ndcX, sign * ndcY, 0.0F);
	}

	float pixelX() {
		return pixelX;
	}

	float pixelY() {
		return pixelY;
	}

	float ndcX() {
		return ndcX;
	}

	float ndcY() {
		return ndcY;
	}

	private static float halton(int index, int base) {
		float result = 0.0F;
		float fraction = 1.0F;
		while (index > 0) {
			fraction /= base;
			result += fraction * (index % base);
			index /= base;
		}
		return result;
	}
}
