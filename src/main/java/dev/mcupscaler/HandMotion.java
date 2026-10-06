package dev.mcupscaler;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The first-person hands' own motion. The hands are drawn with their own projection and a pose that bobs, sways (lagging
 * behind the camera's turning), swings, lowers while switching items and so on, independently of the world, so the
 * camera's motion vectors don't fit them. Each frame the matrices each arm is drawn with (projection * model-view * pose)
 * are kept; the motion pass takes each hand pixel back through this frame's matrices and projects it with the previous
 * frame's. The right arm draws on the right half of the screen, the left arm on the left.
 */
final class HandMotion {
	/** Arm bits: which arm's matrices a pose is for. */
	static final int RIGHT = 1, LEFT = 2, BOTH = RIGHT | LEFT;

	private final Matrix4f projection = new Matrix4f();
	private boolean projectionSet;
	private final Matrix4f[] current = {new Matrix4f(), new Matrix4f()};
	private final Matrix4f[] previous = {new Matrix4f(), new Matrix4f()};
	private final Matrix4f scratch = new Matrix4f();
	private final float[] floats = new float[16];
	/** Arm bits: captured this frame, captured with the arm's own pose this frame, with matrices last frame. */
	private int captured, ownPose, previousValid;

	void beginFrame() {
		int arms = arms();
		for (int i = 0; i < 2; i++) {
			if ((arms & 1 << i) != 0) previous[i].set(current[i]);
		}
		previousValid = arms;
		projectionSet = false;
		captured = 0;
		ownPose = 0;
	}

	/** Arm bits with matrices this frame: only the arms drawn (an empty off hand isn't; its half is the other arm's). */
	private int arms() {
		return ownPose != 0 ? ownPose : captured;
	}

	/** The projection the hands are about to be drawn with (unjittered, as the world's motion vectors use). */
	void projection(Matrix4fc matrix) {
		projection.set(matrix);
		projectionSet = true;
	}

	/**
	 * The {@code arms} are being submitted with {@code pose} under {@code modelView}: the pose shared by both arms (BOTH),
	 * or one arm's final pose. Once an arm has its own, the shared one is only a fallback that isn't used.
	 */
	void submitted(int arms, Matrix4fc pose, Matrix4fc modelView) {
		if (!projectionSet) return;
		if (arms == BOTH) arms &= ~ownPose;
		else ownPose |= arms;
		for (int i = 0; i < 2; i++) {
			if ((arms & 1 << i) != 0) {
				projection.mul(modelView, current[i]).mul(pose);
				captured |= 1 << i;
			}
		}
	}

	/**
	 * Writes this frame's hand matrices: arm bits (int) at {@code bitsOffset}, per arm (right, left) the inverse of this
	 * frame's matrices at {@code matricesOffset} and the previous frame's 128 bytes after; {@code reset}: no history.
	 */
	void write(MemorySegment segment, long bitsOffset, long matricesOffset, boolean reset) {
		int arms = arms();
		segment.set(ValueLayout.JAVA_INT, bitsOffset, arms);
		for (int i = 0; i < 2; i++) {
			if ((arms & 1 << i) != 0) {
				put(segment, matricesOffset + i * 64L, current[i].invert(scratch));
				// Without last frame's matrices: this frame's, which is no motion.
				boolean history = (previousValid & 1 << i) != 0 && !reset;
				put(segment, matricesOffset + 128 + i * 64L, history ? previous[i] : current[i]);
			}
		}
	}

	private void put(MemorySegment frame, long offset, Matrix4fc matrix) {
		matrix.get(floats);
		MemorySegment.copy(floats, 0, frame, ValueLayout.JAVA_FLOAT, offset, floats.length);
	}
}
