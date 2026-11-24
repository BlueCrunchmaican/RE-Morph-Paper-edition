package dev.morphreborn.paper.morph;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The saved morph a joining player gets back a few ticks after they join (MorphManager.RESTORE_DELAY_TICKS). A player who
 * leaves inside that window has no morph state yet, so what they quit with must not overwrite what was saved; and a
 * restore scheduled by an earlier join must not fire for a later one. Bukkit-free so it can be tested.
 */
final class PendingRestores {
	private final Map<UUID, Integer> pending = new HashMap<>();
	private int next;

	/** A restore is scheduled for {@code id}: the ticket its task must present. */
	int begin(UUID id) {
		int ticket = ++next;
		pending.put(id, ticket);
		return ticket;
	}

	/** The scheduled task runs: true when it is still the player's current restore (and it is then spent). */
	boolean claim(UUID id, int ticket) {
		Integer current = pending.get(id);
		if (current == null || current != ticket) return false;
		pending.remove(id);
		return true;
	}

	/** The player quits: true when their restore had not run yet, so the saved morph is still the truth. */
	boolean cancel(UUID id) {
		return pending.remove(id) != null;
	}
}
