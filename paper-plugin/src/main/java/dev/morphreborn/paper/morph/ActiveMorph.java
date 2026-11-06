package dev.morphreborn.paper.morph;

import dev.morphreborn.paper.data.MorphRef;
import org.jspecify.annotations.Nullable;

/**
 * Server-side morph state of one player: the original's {@code MorphInfo} (prevState/nextState/morphTime/morphingTime).
 * {@code next == null} means the player is in their own form and not transforming.
 */
public final class ActiveMorph {
	public @Nullable MorphRef prev;
	public @Nullable MorphRef next;
	public int time;
	public int duration;

	/** Vanilla-viewer transition bookkeeping. */
	public boolean swappedDisguise;
	public boolean soundPlayed;
	/** Live appearance on top of the variant (e.g. an enderman's carried block, a primed creeper), as SNBT; empty = none. */
	public String overlay = "";

	public boolean isMorphed() {
		return next != null;
	}

	public boolean isTransitioning() {
		return prev != null && next != null && duration > 0 && time < duration;
	}

	/** The form that counts for gameplay right now: whichever side of the transition is dominant. */
	public @Nullable MorphRef activeForm() {
		if (!isTransitioning()) return next;
		return morphProgress() < 0.5F ? prev : next;
	}

	public float morphProgress() {
		if (prev == null || next == null || duration <= 0) return 1F;
		return clamp((float) time / duration);
	}

	/** 10% fade - 75% transition - 10% fade, exactly as MorphInfo.getTransitionProgressLinear. */
	public float transitionLinear() {
		return clamp((morphProgress() - 0.125F) / 0.75F);
	}

	public float transitionSine() {
		return sineify(transitionLinear());
	}

	public static float sineify(float progress) {
		return (float) Math.sin(Math.toRadians(-90F + 180F * progress)) / 2F + 0.5F;
	}

	public static float clamp(float v) {
		return v < 0F ? 0F : Math.min(1F, v);
	}
}
