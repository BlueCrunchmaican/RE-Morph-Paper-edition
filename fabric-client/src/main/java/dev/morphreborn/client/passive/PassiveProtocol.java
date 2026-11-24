package dev.morphreborn.client.passive;

import dev.morphreborn.client.SemVer;
import dev.morphreborn.client.net.Wire;

/**
 * The passives' share of the companion protocol (docs/PROTOCOL.md, "Passive toggles"): C_PASSIVE (this client's switch) and S_PASSIVES (the
 * server's states). The plugin has the same numbers in its own PassiveProtocol, so neither side may renumber a state; new ones are only added.
 * <p>
 * Gated by version, not by the protocol number (which stays 3): a server older than {@link #SINCE} never sends an S_PASSIVES, so the tree
 * shows no toggles, and this client never sends a C_PASSIVE to a server that has not sent one (2026-11-16). No Minecraft classes here.
 */
public final class PassiveProtocol {
	/** The first version on both sides with the passive toggles (S_PASSIVES, C_PASSIVE). */
	public static final String SINCE = "8.0.0-beta.2";

	// S_PASSIVES states: written out, so nothing ever renumbers them.
	public static final int STATE_NOT_BOUGHT = 0;
	public static final int STATE_OFF = 1;
	public static final int STATE_RUNNING = 2;
	public static final int STATE_IDLE = 3;
	public static final int STATE_BROKE = 4;

	/** The longest passive id C_PASSIVE carries (the server reads it with that cap). */
	public static final int MAX_ID = 64;
	/** The most passives an S_PASSIVES may list (the plugin lists at most the eight there are). */
	public static final int MAX_LISTED = 64;
	/** The client sends at most one C_PASSIVE this often (the server drops one inside 2 ticks). */
	public static final int CLIENT_THROTTLE_TICKS = 4;

	private PassiveProtocol() {}

	/** What a passive is doing, as the server's Passives.State and the chest menu's panes tell it; {@code colour} is the toggle's ARGB. */
	public enum State {
		NOT_BOUGHT(0xFF404040, "not_bought"),
		OFF(0xFF8A8A8A, "off"),
		RUNNING(0xFF4CAF50, "running"),
		IDLE(0xFFFFD54F, "idle"),
		BROKE(0xFFE53935, "broke");

		private final int colour;
		private final String key;

		State(int colour, String key) {
			this.colour = colour;
			this.key = key;
		}

		public int colour() {
			return colour;
		}

		/** The lang key of the state's tooltip line: {@code morphreborn.passive.state.<key>}. */
		public String langKey() {
			return "morphreborn.passive.state." + key;
		}

		/** On in any of the three ways (running, idle, no biomass): the switch is to the right, and a click turns it off. */
		public boolean on() {
			return this == RUNNING || this == IDLE || this == BROKE;
		}
	}

	public static int code(State state) {
		return switch (state) {
			case NOT_BOUGHT -> STATE_NOT_BOUGHT;
			case OFF -> STATE_OFF;
			case RUNNING -> STATE_RUNNING;
			case IDLE -> STATE_IDLE;
			case BROKE -> STATE_BROKE;
		};
	}

	/** The state a code stands for; one this build doesn't know (a newer server's) counts as idle, the neutral "on". */
	public static State state(int code) {
		return switch (code) {
			case STATE_NOT_BOUGHT -> State.NOT_BOUGHT;
			case STATE_OFF -> State.OFF;
			case STATE_RUNNING -> State.RUNNING;
			case STATE_BROKE -> State.BROKE;
			default -> State.IDLE;
		};
	}

	/** True when a server plugin of this version ("" when it didn't say: servers before 6.6.0) has the toggles. */
	public static boolean serverKnows(String serverVersion) {
		return !serverVersion.isEmpty() && SemVer.compare(serverVersion, SINCE) >= 0;
	}

	/** C_PASSIVE: string id, bool on. */
	public static Wire.Out request(String id, boolean on) {
		return new Wire.Out(Wire.C_PASSIVE).string(id).bool(on);
	}
}
