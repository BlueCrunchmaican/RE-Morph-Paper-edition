package dev.morphreborn.client.data;

import dev.morphreborn.client.net.MorphRef;
import org.jspecify.annotations.Nullable;

/**
 * Client copy of a player's morph state; same timeline maths as the original MorphInfo.
 * Ticked locally between server updates and replaced whenever the server sends a new state.
 */
public final class MorphState {
	public @Nullable MorphRef prev;
	public @Nullable MorphRef next;
	public int time;
	public int duration;
	/** Client tick at which the last server update arrived (for debugging drift). */
	public long receivedAt;

	public boolean isMorphed() {
		return next != null && !(next.isSelf() && prev == null);
	}

	public boolean isTransitioning() {
		return prev != null && next != null && duration > 0 && time < duration;
	}

	/** Advance one client tick; returns false once the state has ended (back to own form). */
	public boolean tick() {
		if (isTransitioning()) {
			time++;
			if (time >= duration) {
				prev = null;
				if (next != null && next.isSelf()) next = null;
			}
		}
		return next != null;
	}

	public float morphProgress(float partialTick) {
		if (prev == null || next == null || duration <= 0) return 1F;
		return clamp((time + partialTick) / duration);
	}

	public float transitionLinear(float partialTick) {
		return clamp((morphProgress(partialTick) - 0.125F) / 0.75F);
	}

	public float transitionSine(float partialTick) {
		return sineify(transitionLinear(partialTick));
	}

	/** Morph skin opacity: fades in over the old body, full during the box transition, fades off the new one. */
	public float skinAlpha(float partialTick) {
		float p = morphProgress(partialTick);
		if (p >= 1F) return 0F;
		float t = transitionSine(partialTick);
		if (t <= 0F) return sineify(p / 0.125F);
		if (t >= 1F) return 1F - sineify((p - 0.875F) / 0.125F);
		return 1F;
	}

	/** Smallest size of the body in the vanilla-style transformation, as the server's VANILLA_MIN_DISGUISE_SCALE. */
	private static final float VANILLA_MIN_SCALE = 0.15F;

	/**
	 * Body size in the vanilla-style transformation (what players without the mod see, MorphManager.tickTransition):
	 * the old form shrinks to 15% by the midpoint, then the new form grows back to full size.
	 */
	public float vanillaScale(float partialTick) {
		float p = morphProgress(partialTick);
		if (p < 0.5F) return 1F + (VANILLA_MIN_SCALE - 1F) * sineify((p - 0.125F) / 0.375F);
		return VANILLA_MIN_SCALE + (1F - VANILLA_MIN_SCALE) * sineify((p - 0.5F) / 0.375F);
	}

	public static float sineify(float progress) {
		return (float) Math.sin(Math.toRadians(-90F + 180F * clamp(progress))) / 2F + 0.5F;
	}

	public static float clamp(float v) {
		return v < 0F ? 0F : Math.min(1F, v);
	}
}
