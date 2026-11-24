package dev.morphreborn.paper.arm.rules;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * "Said once" for the refusals nobody asked for (2026-11-16): an arm that forms by itself (scrolling onto a
 * bound slot, closing a screen, a join) and is refused says why once, then stays quiet until the reason changes or an
 * arm forms. Without it a morphed player would hear "turn back first" every time they closed a chest.
 * <p>
 * One memory per player: the last refusal said. Only requests the player made themselves (the wheel, J, the command)
 * don't use it, so they are told every time.
 *
 * @param <K> who it is kept for (a player's UUID)
 */
public final class SaidOnce<K> {
	private final Map<K, String> last = new HashMap<>();

	/** Whether {@code reason} should be said to {@code who} now; true also remembers it, so the same one is not said again. */
	public boolean shouldSay(K who, @Nullable String reason) {
		if (reason == null || reason.isEmpty()) return false;
		return !reason.equals(last.put(who, reason));
	}

	/** An arm formed (or the player left): whatever refusal comes next is news again. */
	public void clear(K who) {
		last.remove(who);
	}

	/** The refusal last said to {@code who}, or null. */
	public @Nullable String lastSaid(K who) {
		return last.get(who);
	}
}
