package dev.morphreborn.client.gui;

/**
 * When the open upgrade tree asks the server for a fresh copy of itself (C_OPEN_UPGRADES, answered with S_UPGRADES, which the open screen takes in
 * place). The server pushes the tree only after a purchase, so an admin's level change or a new balance would leave the open tree showing stale
 * levels (the rig, 2026-11-16). Two triggers, both paced so a busy server is never flooded:
 * <ul>
 *   <li>a change the client saw (S_PASSIVES with a different list, S_SURVIVAL with a new balance, capacity or unlock): one request, but not
 *       sooner than {@link #MIN_GAP} ticks after the last, so a balance that moves every 10 ticks (a formed arm's drain) costs one a second;</li>
 *   <li>a slow poll, {@link #POLL} ticks after the last, because an admin's change comes with no packet at all.</li>
 * </ul>
 * The server's forced S_PASSIVES with each answer is the same list, so it is not a change and does not make the next request. No game classes, so
 * it is tested without a game (TreeRefreshPacerTest).
 */
public final class TreeRefreshPacer {
	/** The least ticks between two requests (a second). */
	public static final int MIN_GAP = 20;
	/** The most ticks the tree goes without one (5 seconds). */
	public static final int POLL = 100;

	private long last;
	private boolean pending;

	/** Starts at tick {@code now}: the tree that opened the screen is fresh. */
	public TreeRefreshPacer(long now) {
		last = now;
	}

	/** Something the tree shows may have changed on the server. */
	public void changed() {
		pending = true;
	}

	/** A tree arrived at tick {@code now} (opened, a purchase's answer or ours): it has everything seen so far, so nothing waits and the poll restarts. */
	public void received(long now) {
		last = now;
		pending = false;
	}

	/** True when a request should go at tick {@code now}; it is then counted as sent. */
	public boolean due(long now) {
		long since = now - last;
		if (since < 0) { // the clock went back (a new screen's ticks): start over rather than wait for ever
			last = now;
			return false;
		}
		if (!(pending && since >= MIN_GAP) && since < POLL) return false;
		last = now;
		pending = false;
		return true;
	}
}
