package dev.morphreborn.client.gui;

/**
 * Tells the answer to the open tree's own refresh request (C_OPEN_UPGRADES, always answered with S_UPGRADES open=true) from the answer to a key
 * press. The screen asks every few seconds, so when the player closes it before such an answer arrives (a lag spike), the late answer would open
 * the tree again over the world, over chat or over another screen, with the scroll lost. The server's replies come back in the order the
 * requests went, so counting the refresh requests that are still unanswered is enough: while the screen is not showing, the next
 * answers belong to them and are swallowed. No game classes, so it is tested without a game (TreeAnswerGateTest).
 *
 * <p>A request can go unanswered (the mode changed to Classic, which answers with a chat line; the connection dropped), and a count left over
 * would swallow the answer to the player's next key press. So the count is forgotten {@link #WINDOW} ticks after the last request, and the
 * network layer resets it on a new connection and a new hello.
 */
public final class TreeAnswerGate {
	/** The most ticks a refresh request stays awaited (10 seconds: far longer than any answer that is still coming). */
	public static final int WINDOW = 200;

	private int outstanding;
	private long lastSent;

	/** The screen sent a refresh request at tick {@code now}. */
	public void sent(long now) {
		forgetStale(now);
		outstanding++;
		lastSent = now;
	}

	/**
	 * An S_UPGRADES with open=true arrived at tick {@code now}; {@code treeShowing}: the tree screen is the current one. True when it is to be
	 * handled (opened or applied), false when it only answers a refresh request of a screen that is gone.
	 */
	public boolean accept(long now, boolean treeShowing) {
		forgetStale(now);
		if (outstanding == 0) return true;
		outstanding--; // this answer is the oldest request's, whether the screen is still there to use it or not
		return treeShowing;
	}

	/** A new connection or a mode change: nothing is awaited any more. */
	public void reset() {
		outstanding = 0;
	}

	private void forgetStale(long now) {
		if (outstanding > 0 && now - lastSent > WINDOW) outstanding = 0;
	}
}
