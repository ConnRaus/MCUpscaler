package dev.dlssmc;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The motion vectors are computed from depth and only know the camera. Things that move on their own get boxes
 * (relative to the camera) with their movement since the previous frame: the third-person player and the nearest
 * moving or animated entities. Inside an entity box the shader also writes DLSS's "bias current colour" mask.
 */
final class MotionBoxes {
	private static final double ENTITY_RANGE = 128.0;
	/** Range for the current-colour bias: farther away, model animation is a few pixels at most. */
	private static final double ANIMATED_RANGE = 48.0;
	/** Strength of the current-colour bias on animated entities: enough to stop trails, short of losing all smoothing. */
	private static final float ANIMATED_BIAS = 0.75F;
	/** Farther than this (squared) in one frame is a teleport, not movement. */
	private static final double MAX_STEP_SQR = 64.0;
	private static final float FAR = 1.0e9F;

	final MemorySegment entityBoxes = Arena.global().allocate((long)DlssNative.MAX_BOXES * DlssNative.BOX_BYTES, 16);
	private Int2ObjectOpenHashMap<Vec3> positions = new Int2ObjectOpenHashMap<>();
	private Int2ObjectOpenHashMap<Vec3> previousPositions = new Int2ObjectOpenHashMap<>();
	private final List<Entity> candidates = new ArrayList<>();
	@Nullable
	private Vec3 previousPlayerPos;

	/**
	 * Writes the player's box (a little larger than its hitbox, for the held item and swinging limbs) and its movement as
	 * min, max, delta float4s at {@code offset}. An empty box unless the camera follows the player in third person.
	 */
	void writePlayerBox(MemorySegment frame, long offset, Vec3 cameraPos, boolean reset) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		Vec3 pos = player == null ? null : player.getPosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
		Vec3 prev = reset ? null : previousPlayerPos;
		previousPlayerPos = pos;
		if (pos == null || mc.getCameraEntity() != player || mc.options.getCameraType().isFirstPerson()) {
			putVec(frame, offset, FAR, FAR, FAR);
			putVec(frame, offset + 16, -FAR, -FAR, -FAR);
			putVec(frame, offset + 32, 0.0, 0.0, 0.0);
			return;
		}
		double half = player.getBbWidth() * 0.5 + 0.6;
		putVec(frame, offset, pos.x - half - cameraPos.x, pos.y - 0.3 - cameraPos.y, pos.z - half - cameraPos.z);
		putVec(frame, offset + 16, pos.x + half - cameraPos.x, pos.y + player.getBbHeight() + 0.5 - cameraPos.y, pos.z + half - cameraPos.z);
		if (prev != null && pos.distanceToSqr(prev) < MAX_STEP_SQR) {
			putVec(frame, offset + 32, pos.x - prev.x, pos.y - prev.y, pos.z - prev.z);
		} else {
			putVec(frame, offset + 32, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * Boxes of the nearest entities that moved since the previous frame or animate in place, into {@link #entityBoxes}
	 * (min, max, (delta, bias) float4s). Returns the box count.
	 */
	int writeEntityBoxes(@Nullable ClientLevel level, Vec3 cameraPos, boolean reset) {
		Int2ObjectOpenHashMap<Vec3> previous = positions;
		positions = previousPositions;
		previousPositions = previous;
		positions.clear();
		if (reset) {
			previous.clear();
		}
		candidates.clear();
		if (level == null) {
			return 0;
		}
		Minecraft mc = Minecraft.getInstance();
		Entity cameraEntity = mc.getCameraEntity();
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		for (Entity entity : level.entitiesForRendering()) {
			if (entity == cameraEntity || entity.distanceToSqr(cameraPos) > ENTITY_RANGE * ENTITY_RANGE) {
				continue;
			}
			Vec3 pos = entity.getPosition(partialTick);
			positions.put(entity.getId(), pos);
			Vec3 prev = previous.get(entity.getId());
			double step = prev == null ? 0.0 : pos.distanceToSqr(prev);
			// Mobs also animate in place (legs, heads, turning), which no box can describe: those get the bias mask.
			if (step > 1.0e-8 && step < MAX_STEP_SQR || animates(entity, cameraPos)) {
				candidates.add(entity);
			}
		}
		if (candidates.size() > DlssNative.MAX_BOXES) {
			candidates.sort(Comparator.comparingDouble(e -> e.distanceToSqr(cameraPos)));
		}
		int count = Math.min(candidates.size(), DlssNative.MAX_BOXES);
		for (int i = 0; i < count; i++) {
			Entity entity = candidates.get(i);
			Vec3 pos = positions.get(entity.getId());
			Vec3 prev = previous.get(entity.getId());
			if (prev == null || pos.distanceToSqr(prev) >= MAX_STEP_SQR) {
				prev = pos;
			}
			// Larger than the hitbox (models' heads, tails, swinging legs and held items stick out of it), but starting just
			// above the feet so that the ground under the entity keeps its own motion.
			double half = entity.getBbWidth() * 0.5 + 0.45;
			long o = (long)i * DlssNative.BOX_BYTES;
			putVec(entityBoxes, o, pos.x - half - cameraPos.x, pos.y + 0.02 - cameraPos.y, pos.z - half - cameraPos.z);
			putVec(entityBoxes, o + 16, pos.x + half - cameraPos.x, pos.y + entity.getBbHeight() + 0.3 - cameraPos.y, pos.z + half - cameraPos.z);
			putVec(entityBoxes, o + 32, pos.x - prev.x, pos.y - prev.y, pos.z - prev.z);
			entityBoxes.set(ValueLayout.JAVA_FLOAT, o + 44, animates(entity, cameraPos) ? ANIMATED_BIAS : 0.0F);
		}
		candidates.clear();
		return count;
	}

	/** Living things animate their models (walking, heads, arms) beyond what their box movement says. */
	private static boolean animates(Entity entity, Vec3 cameraPos) {
		return entity instanceof LivingEntity && entity.distanceToSqr(cameraPos) < ANIMATED_RANGE * ANIMATED_RANGE;
	}

	private static void putVec(MemorySegment segment, long offset, double x, double y, double z) {
		segment.set(ValueLayout.JAVA_FLOAT, offset, (float)x);
		segment.set(ValueLayout.JAVA_FLOAT, offset + 4, (float)y);
		segment.set(ValueLayout.JAVA_FLOAT, offset + 8, (float)z);
		segment.set(ValueLayout.JAVA_FLOAT, offset + 12, 0.0F);
	}
}
