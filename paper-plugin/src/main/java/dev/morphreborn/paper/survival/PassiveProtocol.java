package dev.morphreborn.paper.survival;

import dev.morphreborn.paper.SemVer;
import dev.morphreborn.paper.net.Wire;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The passives' share of the companion protocol (docs/PROTOCOL.md, "Passive toggles"): C_PASSIVE (the client's switch) and S_PASSIVES (the
 * states). The client mod has the same numbers in its own PassiveProtocol, so neither side may renumber a state; new ones are only added.
 * <p>
 * Who gets it is decided by version ({@link #SINCE}), not by the protocol number, which stays 3: an older client never gets an S_PASSIVES
 * and never sends a C_PASSIVE, and an older server never sends one, so the tree simply has no toggles there (2026-11-16: the
 * toggles belong in the upgrades menu, and for players with the mod that is the client's tree). No Bukkit here, so it is tested without a server.
 */
public final class PassiveProtocol {
	/** The first version on both sides with the passive toggles (S_PASSIVES, C_PASSIVE). */
	public static final String SINCE = "8.0.0-beta.2";

	// S_PASSIVES states. They follow Passives.State's order, but are written out so reordering that enum can never renumber the wire.
	/** Never listed (a passive that is not bought is left out); a reader treats an id it was not told about as this. */
	public static final int STATE_NOT_BOUGHT = 0;
	public static final int STATE_OFF = 1;
	public static final int STATE_RUNNING = 2;
	public static final int STATE_IDLE = 3;
	public static final int STATE_BROKE = 4;

	/** The longest passive id C_PASSIVE may carry (the server reads it with that cap). */
	public static final int MAX_ID = 64;
	/** The most passives an S_PASSIVES may list (the reader's cap; there are eight). */
	public static final int MAX_LISTED = 64;
	/** The server drops a C_PASSIVE sooner than this after the last one it acted on (and answers with the real states). */
	public static final int SERVER_THROTTLE_TICKS = 2;
	/** The client sends at most one C_PASSIVE this often. */
	public static final int CLIENT_THROTTLE_TICKS = 4;
	/** A changed state waits at least this long after the last S_PASSIVES (5 ticks: at most four a second, the changes coalesced into one). */
	public static final int SEND_MIN_TICKS = 5;

	private PassiveProtocol() {}

	/** True when a client mod of this version (what C_HELLO said; null or "?" when it didn't) knows the toggles. */
	public static boolean knows(@Nullable String clientVersion) {
		return clientVersion != null && !clientVersion.equals("?") && SemVer.compare(clientVersion, SINCE) >= 0;
	}

	public static int code(Passives.State state) {
		return switch (state) {
			case NOT_BOUGHT -> STATE_NOT_BOUGHT;
			case OFF -> STATE_OFF;
			case RUNNING -> STATE_RUNNING;
			case IDLE -> STATE_IDLE;
			case BROKE -> STATE_BROKE;
		};
	}

	/** The state a code stands for; one this build doesn't know (a newer peer's) counts as idle, the neutral "on" colour. */
	public static Passives.State state(int code) {
		return switch (code) {
			case STATE_NOT_BOUGHT -> Passives.State.NOT_BOUGHT;
			case STATE_OFF -> Passives.State.OFF;
			case STATE_RUNNING -> Passives.State.RUNNING;
			case STATE_BROKE -> Passives.State.BROKE;
			default -> Passives.State.IDLE;
		};
	}

	/**
	 * S_PASSIVES: varInt count, then per bought passive string id and varInt state. {@code states} lists the passives in the order to
	 * send; one that is not bought is left out, so the client takes a missing id as "not bought".
	 */
	public static byte[] statesPacket(Map<String, Passives.State> states) {
		Wire.Out out = new Wire.Out(Wire.S_PASSIVES);
		int count = 0;
		for (Passives.State s : states.values()) if (s != Passives.State.NOT_BOUGHT) count++;
		out.varInt(count);
		for (Map.Entry<String, Passives.State> e : states.entrySet()) {
			if (e.getValue() != Passives.State.NOT_BOUGHT) out.string(e.getKey()).varInt(code(e.getValue()));
		}
		return out.bytes();
	}

	/** C_PASSIVE's fields. */
	public record Request(String id, boolean on) {
		/** Reads the fields after the packet id; a short or oversized packet throws {@link Wire.MalformedPacketException}. */
		public static Request read(Wire.In in) {
			return new Request(in.string(MAX_ID), in.bool());
		}
	}

	/** C_PASSIVE as a client writes it (the tests', and a reference for the client's own). */
	public static byte[] requestPacket(String id, boolean on) {
		return new Wire.Out(Wire.C_PASSIVE).string(id).bool(on).bytes();
	}
}
