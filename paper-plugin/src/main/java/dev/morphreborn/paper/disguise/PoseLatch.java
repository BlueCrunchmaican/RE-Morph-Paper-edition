package dev.morphreborn.paper.disguise;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A yes/no that only flips once the raw value has held for a while. Pose.SWIMMING flaps at a water's surface and on every
 * sprint in and out of a pool; a lent head that went away and came back with it would respawn its whole display set (and
 * a modded client could do it twenty times a second). So a pose has to last {@link #HOLD} ticks before it counts, and
 * a flap shorter than that changes nothing. Bukkit-free so it can be tested.
 */
final class PoseLatch {
	/** Ticks the raw value must hold before the latched one follows (half a second). */
	static final int HOLD = 10;

	private static final class State {
		boolean on;
		int streak;
	}

	private final Map<UUID, State> states = new HashMap<>();

	/** The latched value for {@code id} after this tick's raw value; at most one flip per {@link #HOLD} ticks. */
	boolean tick(UUID id, boolean raw) {
		State s = states.get(id);
		if (s == null) {
			if (!raw) return false; // nothing to remember about a player who was never in the pose
			s = new State();
			states.put(id, s);
		}
		if (raw == s.on) {
			s.streak = 0;
			if (!s.on) states.remove(id);
			return s.on;
		}
		if (++s.streak >= HOLD) {
			s.on = raw;
			s.streak = 0;
			if (!s.on) states.remove(id);
		}
		return s.on;
	}

	/** Forget everyone not in {@code keep} (a player who lost the combination or left). */
	void retainAll(Set<UUID> keep) {
		states.keySet().retainAll(keep);
	}
}
