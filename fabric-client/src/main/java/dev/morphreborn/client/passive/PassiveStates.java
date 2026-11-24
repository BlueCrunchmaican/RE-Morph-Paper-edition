package dev.morphreborn.client.passive;

import dev.morphreborn.client.net.Wire;
import java.util.HashMap;
import java.util.Map;

/**
 * The player's biomass passives as the server last told them (S_PASSIVES) for the upgrade tree's toggles, and the throttle of the client's own
 * C_PASSIVE. The server decides everything: this never predicts a switch, the tree shows the next S_PASSIVES (which the server sends at once
 * after a C_PASSIVE). No Minecraft classes, so it is tested without a game.
 * <p>
 * Before the first S_PASSIVES of a connection (and for ever on a server older than {@link PassiveProtocol#SINCE}, which never sends one),
 * {@link #canToggle} is false for everything and the tree draws no toggles.
 */
public final class PassiveStates {
	private Map<String, PassiveProtocol.State> states = Map.of();
	private boolean received;
	private long lastSentAt = Long.MIN_VALUE / 2;
	private String checkedVersion; // null until the first canToggle
	private boolean checkedKnows;

	/** The connection ended, or a repeated S_HELLO announced a new mode: forget everything (the server sends the list again when it has one). */
	public void clear() {
		states = Map.of();
		received = false;
		lastSentAt = Long.MIN_VALUE / 2;
	}

	/**
	 * Reads S_PASSIVES' fields (after the packet id) and replaces the list; an id not in it is not bought. True when that changed what the tree shows
	 * (a different list, or the first one): the open tree then asks for a fresh copy, and does not for the forced repeat that answers its own request.
	 */
	public boolean read(Wire.In in) {
		int count = in.count();
		if (count > PassiveProtocol.MAX_LISTED) throw new Wire.MalformedPacketException("bad count " + count);
		Map<String, PassiveProtocol.State> next = new HashMap<>();
		for (int i = 0; i < count; i++) {
			String id = in.string();
			PassiveProtocol.State state = PassiveProtocol.state(in.varInt());
			if (state != PassiveProtocol.State.NOT_BOUGHT) next.put(id, state);
		}
		Map<String, PassiveProtocol.State> copy = Map.copyOf(next);
		boolean changed = !received || !copy.equals(states);
		states = copy;
		received = true;
		return changed;
	}

	/** True once the server has sent a list this connection: it has the toggles. */
	public boolean received() {
		return received;
	}

	/** What {@code id} is doing; NOT_BOUGHT for one the server did not list (and for everything before it sent a list). */
	public PassiveProtocol.State state(String id) {
		return states.getOrDefault(id, PassiveProtocol.State.NOT_BOUGHT);
	}

	/**
	 * Whether the tree draws a toggle on {@code id} and sends C_PASSIVE for it: the server said it has them (a list arrived, and its plugin
	 * is at {@link PassiveProtocol#SINCE}, which any server that sends one is) and {@code id} is bought.
	 */
	public boolean canToggle(String id, String serverVersion) {
		return received && knows(serverVersion) && state(id) != PassiveProtocol.State.NOT_BOUGHT;
	}

	/**
	 * {@link PassiveProtocol#serverKnows} for the version last asked about, kept: the tree asks per node per frame, and the version only changes
	 * at an S_HELLO, while the comparison splits and matches strings.
	 */
	private boolean knows(String serverVersion) {
		if (!serverVersion.equals(checkedVersion)) {
			checkedKnows = PassiveProtocol.serverKnows(serverVersion);
			checkedVersion = serverVersion;
		}
		return checkedKnows;
	}

	/** What a click on {@code id}'s switch asks for: off when it is on in any way, on when it is off. */
	public boolean wantOn(String id) {
		return !state(id).on();
	}

	/**
	 * Whether a C_PASSIVE may go at client tick {@code tick}: not within {@link PassiveProtocol#CLIENT_THROTTLE_TICKS} of the last that did.
	 * Records that it did.
	 */
	public boolean takeSendSlot(long tick) {
		if (tick - lastSentAt < PassiveProtocol.CLIENT_THROTTLE_TICKS && tick >= lastSentAt) return false;
		lastSentAt = tick;
		return true;
	}
}
