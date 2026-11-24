package dev.morphreborn.paper.arm;

import dev.morphreborn.paper.arm.api.ArmView;
import dev.morphreborn.paper.arm.rules.SessionRecord;
import java.util.UUID;

/**
 * One formed arm: which form at which level, the hotbar slot it took (home), the session's nonce (also on the arm
 * item, so an arm stack with any other nonce is a stray), the item it replaced (the stash, as bytes), and where it is
 * on its timeline. The saved half of this is the player's PDC record ({@link #record()}, written by ArmStash); the
 * rest lives only while the player is online.
 * <p>
 * The session exists from the first tick of forming until the end of its retract or melt: the arm is in the home slot
 * all that time (decision D26), and the session ending is what gives the stash back.
 */
final class ArmSession {
	final UUID player;
	final int slot;
	/**
	 * The stashed item, as ItemStack.serializeAsBytes; empty for an empty hand. Changed only by a tuck (it becomes empty,
	 * because the item is back in the slot) and the untuck (it is whatever the slot holds then).
	 */
	byte[] stash;
	/**
	 * Tucked (2026-11-16): a container screen is open on the owner's client, so the held slot holds its real item
	 * and there is no arm item anywhere, but the session is still formed and drawn. Its stash is empty meanwhile, so every
	 * way the session can end (a retract, a melt, death, quitting, a crash and the join repair) gives back nothing a
	 * second time: the real item is already where it belongs.
	 */
	boolean tucked;
	/**
	 * The arm item's biomass bar as it was when it was tucked, and as the owner's biomass moves it while tucked (0 and 0 when
	 * not tucked): the stub other players are shown for the tucked arm carries it (ArmView), as the item itself no longer exists.
	 */
	int tuckMaxDamage;
	int tuckDamage;
	/** The crossbow's bolt as it was when tucked (false when not tucked, and for every other form): the stub others are shown is charged to match (ArmView). */
	boolean tuckCharged;
	/** Epoch millis when it first formed (kept through reshapes, as in the PDC record). */
	final long formedAt;

	String form;
	int level;
	long nonce;
	/** ArmProtocol.STATE_*, the server tick it began, and its length (0 for FORMED). */
	int state;
	int stateTick;
	int duration;
	/** The form a RESHAPING started from ("" otherwise). */
	String fromForm = "";
	/** Why a RETRACTING or MELTING will finish (ArmProtocol.WHY_*). */
	int endWhy;
	/** The level the player owns for this form (or an admin gave): biomass can draw the arm below it, never above. */
	int cap;
	/** The biomass band the arm is in (ArmTiers.biomassBand), 0 before the first reading. */
	int band;
	/** An admin's {@code free}: nothing this arm does costs biomass, whatever the mode. An admin's later reshape sets it again. */
	boolean free;

	ArmSession(UUID player, String form, int level, int slot, long nonce, byte[] stash, long formedAt, boolean free) {
		this.player = player;
		this.form = form;
		this.level = level;
		this.cap = level;
		this.slot = slot;
		this.nonce = nonce;
		this.stash = stash;
		this.formedAt = formedAt;
		this.free = free;
	}

	void enter(int state, int tick) {
		this.state = state;
		this.stateTick = tick;
		this.duration = ArmProtocol.duration(state);
		if (state != ArmProtocol.STATE_RESHAPING) fromForm = "";
	}

	int elapsed(int tick) {
		return Math.max(0, tick - stateTick);
	}

	/** It digs and hits: forming, formed, reshaping or quickforming (not retracting or melting, and not tucked: there is no arm in the hand). */
	boolean usable() {
		return !tucked && ArmProtocol.usable(state);
	}

	/** Retracting or melting: the arm is still in the slot, but nothing can be done with it. */
	boolean ending() {
		return state == ArmProtocol.STATE_RETRACTING || state == ArmProtocol.STATE_MELTING;
	}

	SessionRecord record() {
		return new SessionRecord(SessionRecord.VERSION, form, level, slot, nonce, stash, formedAt);
	}

	/**
	 * The tucked crossbow's bolt changed (it finished regrowing, or was found stale, while the screen was open): the stand-in's charged flag
	 * follows. False, and nothing changed, when the arm isn't tucked, isn't the crossbow, or already shows it.
	 */
	boolean refreshTuckCharged(boolean loaded) {
		if (!tucked || !"crossbow".equals(form) || tuckCharged == loaded) return false;
		tuckCharged = loaded;
		return true;
	}

	ArmView view() {
		return new ArmView(form, level, state, stateTick, duration, tucked, tucked ? tuckMaxDamage : 0, tucked ? tuckDamage : 0, tucked && tuckCharged);
	}
}
