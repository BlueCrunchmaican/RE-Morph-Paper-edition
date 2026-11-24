package dev.morphreborn.paper.arm.api;

import org.jspecify.annotations.Nullable;

/**
 * How a special's attempt went: it fired (and was paid for), found nothing to act on (no cost, no cooldown; the
 * manager adds a short lockout so F can't be spammed), or was refused (no cost, no cooldown). {@code message} is what the
 * player is shown, if anything.
 */
public record SpecialResult(Outcome outcome, @Nullable String message) {
	public enum Outcome { FIRED, NO_TARGET, REFUSED }

	public static final SpecialResult FIRED = new SpecialResult(Outcome.FIRED, null);

	public static SpecialResult noTarget(String m) {
		return new SpecialResult(Outcome.NO_TARGET, m);
	}

	public static SpecialResult refused(String m) {
		return new SpecialResult(Outcome.REFUSED, m);
	}

	public boolean fired() {
		return outcome == Outcome.FIRED;
	}
}
