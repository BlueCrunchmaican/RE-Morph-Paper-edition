package dev.morphreborn.client.morph;

import dev.morphreborn.client.MorphClient;
import dev.morphreborn.client.data.MorphState;
import dev.morphreborn.client.net.MorphRef;
import java.util.Objects;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/**
 * The camera height of a transforming player follows the transformation instead of the hitbox.
 *
 * <p>The server changes the physical body (hitbox, eye height) in one step half-way through a transformation, because
 * easing it every tick made movement jitter (see the server's MorphManager). Vanilla's camera would then rise or drop
 * within a few ticks. With the mod, the camera instead eases from the old eye height to the new one over the whole
 * transformation, on the same curve as the animation; the body underneath still swaps at the half-way point.
 */
public final class CameraHeight {
	/** config.yml's default appearance.min-scale and max-scale; only used to estimate the size before the swap. */
	private static final float MIN_SCALE = 0.2F, MAX_SCALE = 7.0F;
	private static final float PLAYER_HEIGHT = 1.8F;

	private static @Nullable MorphRef trackedPrev, trackedNext;
	private static boolean tracking;
	private static float from = 1F, to = 1F;

	private CameraHeight() {}

	/** The eye height the camera should follow for the local player this tick, or NaN to leave vanilla's alone. */
	public static float eyeHeight(LocalPlayer player) {
		MorphState s = MorphClient.STATES.get(player.getUUID());
		if (s == null || !s.isTransitioning()) {
			tracking = false;
			return Float.NaN;
		}
		float physical = player.getScale();
		if (physical <= 0F) return Float.NaN;
		if (!tracking || !Objects.equals(trackedPrev, s.prev) || !Objects.equals(trackedNext, s.next)) {
			// A transformation began: the body is still the old one.
			tracking = true;
			trackedPrev = s.prev;
			trackedNext = s.next;
			from = physical;
			to = estimatedScale(player, s.next);
		}
		// After the swap the server has set the real size; use it rather than the estimate.
		if (Math.abs(physical - from) > 1e-3F) to = physical;
		float visual = from + (to - from) * s.transitionSine(0F);
		// The eye height at scale 1, from the dimensions, which read the scale attribute just like getScale() does. The
		// entity's stored eye height (getEyeHeight()) only catches up on its next tick after the server changes the
		// scale, so dividing it by the new scale dropped the camera for one tick at the swap.
		float unscaledEye = player.getDimensions(player.getPose()).eyeHeight() / physical;
		return unscaledEye * visual;
	}

	/** The scale the server gives this form (its height relative to a player's, clamped as by default). */
	private static float estimatedScale(LocalPlayer player, @Nullable MorphRef ref) {
		if (ref == null || ref.isSelf() || ref.isPlayer()) return 1F;
		LivingEntity entity = MorphClient.ENTITIES.forWorld(player.getUUID(), ref);
		if (entity == null) return 1F;
		return Math.clamp(entity.getBbHeight() / PLAYER_HEIGHT, MIN_SCALE, MAX_SCALE);
	}
}
