package dev.morphreborn.paper.net;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How many client packets each player may send (lying-client audit, 2026-11-16). The mod never sends anywhere near these numbers, so an
 * honest client cannot reach a limit; a modified one that floods is dropped, packet for packet and without an answer (an answer per packet
 * would hand it an amplifier: C_FAVOURITE and C_REQUEST_SYNC each end in a whole collection or a full sync going back).
 * <p>
 * One token bucket per {@link Kind} per player, plus the {@link Kind#ANY} bucket every packet draws on, so no mix of kinds gets past the
 * total. A bucket starts full and refills with the server tick; the clock only has to be monotonic (a tick that goes backwards counts as no
 * time passing). The per-packet throttles that already exist (C_ARM every 2 ticks, C_ARM_COLOUR, C_PASSIVE) stay as they are and still draw on
 * ANY. Nothing here touches Bukkit, so it is tested without a server.
 */
public final class PacketBudget {
	/** The packet kinds with a limit of their own: how many may come in a burst, and how many ticks it takes to earn one back. */
	public enum Kind {
		/** Every packet of a modded player, drawn from first: 100 in a burst, then 20 a second. */
		ANY(100, 1),
		/** C_HELLO: the client sends one a connection. A second answers a settings change at most; the rest are floods. */
		HELLO(2, 100),
		/** C_REQUEST_SYNC (unused by the mod): each one is a collection and a full sync of the world going back. */
		SYNC(2, 40),
		/** C_SELECT, C_FAVOURITE, C_DELETE: each ends in a refusal or a whole collection going back. About 5 a second is a very busy selector. */
		LIST(12, 3),
		/** C_OPEN_UPGRADES, C_BUY_UPGRADE: each answers with the whole upgrade tree. */
		UPGRADES(10, 3),
		/** C_ABILITY, C_RAGE: a key press; the abilities have cooldowns of their own, this only bounds the refusals. */
		ABILITY(10, 2),
		/**
		 * C_ARM's screen signals, which skip the 2-tick throttle on purpose (a dropped CLOSED would leave the arm tucked): each can tuck,
		 * untuck or form an arm, so a flood of them is bounded here instead. 20 a second is a player mashing E.
		 */
		SCREEN(24, 1),
		/**
		 * C_ARM's accessory hold PRESSES (the spyglass's zoom key, the shield's use key). A bucket of their own, so a run of right-clicks with the
		 * shield on (two packets a click) cannot use up the SCREEN signals. A release is never drawn from it: it only lowers something, and a
		 * dropped one left the shield up for 15 seconds.
		 */
		HOLD(40, 1);

		final int capacity;
		final int ticksPerToken;

		Kind(int capacity, int ticksPerToken) {
			this.capacity = capacity;
			this.ticksPerToken = ticksPerToken;
		}
	}

	/** A warning about the same player is logged at most this often (30 seconds). */
	public static final int LOG_WINDOW_TICKS = 600;

	private static final class State {
		/** Credit in ticks, per kind: a packet costs the kind's ticksPerToken, the most that can be held is capacity times that. */
		final long[] level = new long[Kind.values().length];
		long lastTick;
		long lastLog = Long.MIN_VALUE;
		int suppressed;

		State(long now) {
			for (Kind k : Kind.values()) level[k.ordinal()] = (long) k.capacity * k.ticksPerToken;
			lastTick = now;
		}
	}

	private final Map<UUID, State> states = new ConcurrentHashMap<>();

	/** Whether {@code player} may send one more packet of {@code kind} at server tick {@code now}; a yes spends one token. */
	public boolean allow(UUID player, Kind kind, long now) {
		State s = states.computeIfAbsent(player, k -> new State(now));
		synchronized (s) {
			long passed = now >= s.lastTick ? now - s.lastTick : 0;
			s.lastTick = now;
			if (passed > 0) {
				for (Kind k : Kind.values()) {
					long cap = (long) k.capacity * k.ticksPerToken;
					s.level[k.ordinal()] = Math.min(cap, s.level[k.ordinal()] + passed);
				}
			}
			long cost = kind.ticksPerToken;
			if (s.level[kind.ordinal()] < cost) return false;
			s.level[kind.ordinal()] -= cost;
			return true;
		}
	}

	/** {@link #allow} for a kind and the total at once: both must have a token. The total is drawn first, so a packet the kind refuses still counts against it (a flood of refused ones is still a flood). */
	public boolean allowWithTotal(UUID player, Kind kind, long now) {
		if (!allow(player, Kind.ANY, now)) return false;
		return kind == Kind.ANY || allow(player, kind, now);
	}

	/**
	 * Whether to write a log line about {@code player} now: at most one per {@link #LOG_WINDOW_TICKS}, so a client that sends garbage
	 * all day makes one line every half minute, not one per packet. Returns -1 to stay quiet, otherwise how many were kept quiet since the last
	 * line (0 for the first).
	 */
	public int shouldLog(UUID player, long now) {
		State s = states.computeIfAbsent(player, k -> new State(now));
		synchronized (s) {
			if (s.lastLog == Long.MIN_VALUE || now < s.lastLog || now - s.lastLog >= LOG_WINDOW_TICKS) {
				int quiet = s.suppressed;
				s.suppressed = 0;
				s.lastLog = now;
				return quiet;
			}
			s.suppressed++;
			return -1;
		}
	}

	/** The player left: their buckets go. */
	public void forget(UUID player) {
		states.remove(player);
	}

	/** For tests: how many players have state. */
	int tracked() {
		return states.size();
	}
}
