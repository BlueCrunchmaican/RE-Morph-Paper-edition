package dev.morphreborn.paper.arm.rules;

import java.util.HashMap;
import java.util.Map;

/**
 * Notices a held slot that moved without the held event (2026-11-16): a middle-click pick-block, or a plugin
 * calling setHeldItemSlot, lands on a bound slot with nothing to form the arm. Each tick the manager asks {@link #moved}
 * about every player; the event path and the deploys {@link #note} where the selection is, so that only a change nobody
 * handled counts, and a refused deploy is not tried again every tick (the slot has to move, or be moved again).
 *
 * @param <K> who it is kept for (a player's UUID)
 */
public final class HeldSlotWatch<K> {
	private final Map<K, Integer> seen = new HashMap<>();

	/**
	 * Records {@code held} and says whether it differs from the slot last seen. A player seen for the first time is not a
	 * change: a join is dealt with by the join, not by this.
	 */
	public boolean moved(K who, int held) {
		Integer before = seen.put(who, held);
		return before != null && before != held;
	}

	/** The selection is at {@code held} and whatever it needed was done: the next {@link #moved} compares with this. */
	public void note(K who, int held) {
		seen.put(who, held);
	}

	/** Forget {@code who} (left, or joined again): their next sighting is a first. */
	public void forget(K who) {
		seen.remove(who);
	}
}
