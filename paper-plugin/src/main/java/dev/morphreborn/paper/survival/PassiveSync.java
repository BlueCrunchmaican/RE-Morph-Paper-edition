package dev.morphreborn.paper.survival;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What each modded player's client was last told about their passives (S_PASSIVES), and when, so a state that flips back and forth
 * is coalesced into a few packets a second at most, and C_PASSIVE's per-player throttle. The clock is the server tick; nothing here
 * touches Bukkit, so it is tested without a server.
 * <p>
 * The tick that notices a change asks {@link #shouldSend}; the answer is compared against what was last SENT, not against the
 * last state seen, so a change that has to wait (the window after the last packet is not over) is simply asked again on the next
 * run and goes then, with whatever it has become by then. A flip that undoes itself inside the window sends nothing at all.
 */
public final class PassiveSync {
	private record Sent(Map<String, Passives.State> states, long tick) {}

	private final Map<UUID, Sent> sent = new HashMap<>();
	private final Map<UUID, Long> lastToggle = new HashMap<>();

	/**
	 * Whether {@code states} (the bought passives, id to state) should go to {@code player} now, and if so, remembers that it did.
	 * {@code force}: the tree opened, the handshake finished or C_PASSIVE came in: the client must have the list, changed or not,
	 * whatever the window. Otherwise it goes when it differs from what was last sent and the window since then has passed;
	 * a player who was never sent one gets it at once.
	 */
	public boolean shouldSend(UUID player, Map<String, Passives.State> states, long now, boolean force) {
		Map<String, Passives.State> bought = bought(states);
		Sent last = sent.get(player);
		if (!force && last != null) {
			if (last.states().equals(bought)) return false;
			if (now >= last.tick() && now - last.tick() < PassiveProtocol.SEND_MIN_TICKS) return false;
		}
		sent.put(player, new Sent(bought, now));
		return true;
	}

	/**
	 * Whether a C_PASSIVE at server tick {@code now} is acted on: not when one was acted on fewer than
	 * {@link PassiveProtocol#SERVER_THROTTLE_TICKS} ticks ago. A dropped one does not move the window, so a client that floods is
	 * answered at the rate of the throttle, not never.
	 */
	public boolean allowToggle(UUID player, long now) {
		Long last = lastToggle.get(player);
		if (last != null && now >= last && now - last < PassiveProtocol.SERVER_THROTTLE_TICKS) return false;
		lastToggle.put(player, now);
		return true;
	}

	/** The client left or was told the settings again (the mode changed): the next state goes out whatever it is. */
	public void forget(UUID player) {
		sent.remove(player);
		lastToggle.remove(player);
	}

	private static Map<String, Passives.State> bought(Map<String, Passives.State> states) {
		Map<String, Passives.State> out = new HashMap<>();
		for (Map.Entry<String, Passives.State> e : states.entrySet()) {
			if (e.getValue() != Passives.State.NOT_BOUGHT) out.put(e.getKey(), e.getValue());
		}
		return out;
	}
}
