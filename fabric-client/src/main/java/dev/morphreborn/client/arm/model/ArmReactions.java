package dev.morphreborn.client.arm.model;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmState;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.geom.ArmDraw;
import dev.morphreborn.client.arm.render.Pulse;
import dev.morphreborn.client.net.ClientNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * What the arm in hand does when something happens to it (sections 2.4, 2.5, 2.7): a hit flares the hamon and sends a
 * SPEND pulse up the tool, a broken block makes the pick recoil, a special's start winds the tool up, every heal that
 * arrives runs a FEED pulse down the blade and flicks the tail, a biomass gain surges. The world effects (tendrils,
 * the swipe arc, the vein) are ArmFx's; this only keeps, per player, the moments the in-hand look reacts to, which
 * {@link ArmDrawFactory} turns into the ages and pulses of an {@link ArmDraw}.
 * <p>
 * It listens to every arm effect (S_ARM_FX, by its origin player) and, each tick, to this player's own biomass, and it
 * notices arms that vanish without their retract (a forced retract: the sheath recedes over 4 ticks). Times are on a
 * clock of the owner's choosing: the world's reactions are on {@link Arms#TICK}, the preview screen's on its own clock,
 * so the same {@link Reaction} works in both.
 * <p>
 * Client thread only (effects, ticks and extraction all run there).
 */
public final class ArmReactions {
	/** A time long before anything: every age from it is past every reaction's end. */
	static final double LONG_AGO = -1.0E9;
	/** Pulse events kept per player; older ones fall off (a pulse lives about 10 ticks on a katana). */
	static final int MAX_PULSES = 12;
	/**
	 * Ticks a pulse event is kept after it starts: longer than any pulse's run along any form at the slowest pulse speed a
	 * player may choose (a pulse runs 1 / speed times as long in real ticks; 2026-11-16).
	 */
	private static final double PULSE_KEEP = 40 / (double) Pulse.MIN_SPEED;
	/** Draining Cut's hold on the hand: the 2-tick wind-up and the swing, at least. */
	static final int DRAIN_CUT_TICKS = 6;
	/** How long a one-off flare of a new form's special holds the hand (a skewer landing, a mace's landing, a recall: 2026-11-16). */
	static final int FLARE_TICKS = 6;
	/** Rake's length when its message gives none: three slashes of ClawMath.GAP (3) ticks each. */
	static final int CLAWS_RAKE_TICKS = 9;
	/**
	 * The new forms' FX {@code arg} moves as the server sends them (SpearImpale, MaceLeap, TridentArm, CrossbowArm, ClawMath; docs/PROTOCOL.md):
	 * the client keeps its own copy, as the wire documents say, and neither side may renumber.
	 */
	static final int SPEAR_LUNGE = 1, SPEAR_PIN = 2, MACE_LEAP = 1, MACE_LANDING = 2, TRIDENT_THROW = 1, TRIDENT_RECALL = 3, TRIDENT_CATCH = 4,
			CROSSBOW_BURST = 2, CLAWS_RAKE = 1, CLAWS_MOVE_MASK = 0xF;
	/**
	 * The accessories' FX_ACCESSORY moves (the low byte of {@code arg}; the place is in bits 8-11, ArmProtocol.accessoryPlace): the shield's
	 * (ShieldAccessory.FX_*) and flint's (FlintRules.FX_*), copied here as the wire documents say; neither side may renumber.
	 */
	static final int SHIELD_BLOCK = 1, SHIELD_REFLECT = 2, SHIELD_ABSORB = 3, FLINT_IGNITE = 1, FLINT_LIGHT = 2;
	/** A player's reactions are forgotten once nothing has happened to them for this long. */
	private static final long FORGET_TICKS = 600;
	/** How long the sheath takes to recede after a forced retract (section 2.6 "Instant (forced) retract"). */
	public static final int RECEDE_TICKS = 12;

	/** One player's recent reactions, on one clock. Mutable; only its owner (the world here, or the preview) writes it. */
	public static final class Reaction {
		public double hitAt = LONG_AGO, breakAt = LONG_AGO, surgeAt = LONG_AGO;
		/** The special in hand: its effect kind (ArmDraw.NO_SPECIAL: none), when it began and how long it holds the hand. */
		public int specialKind = ArmDraw.NO_SPECIAL;
		public double specialAt = LONG_AGO;
		public double specialLength;
		/** Pulse events: kind (a Pulse.Kind ordinal) and start, oldest first. */
		final int[] pulseKind = new int[MAX_PULSES];
		final double[] pulseAt = new double[MAX_PULSES];
		int pulseCount;
		/** The latest heal arrival (a FEED from a PULSE event): the tail flicks then. */
		public double flickAt = LONG_AGO;
		/** When the arm last vanished without its retract, and which form it was. */
		public double poppedAt = LONG_AGO;
		public String poppedForm = "";
		public int poppedTier = 1;
		long lastTouched;
		/** The trident throw last heard of (its seed, and when): its resends every 20 ticks must not wind the hand again. */
		int throwSeed;
		double throwSeenAt = LONG_AGO;

		public void hit(double now) {
			hitAt = now;
			pulse(Pulse.Kind.SPEND, now);
		}

		public void breakBlock(double now) {
			breakAt = now;
			pulse(Pulse.Kind.SPEND, now);
		}

		/** A special began: {@code kind} is the effect that starts it (FX_SWIPE Draining Cut, FX_VEIN Vein Rip). */
		public void special(int kind, double now, double length) {
			specialKind = kind;
			specialAt = now;
			specialLength = Math.max(1, length);
			pulse(Pulse.Kind.SPEND, now);
		}

		/** The server stopped a special of {@code kind} early (an empty FX_VEIN): it ends now. */
		public void endSpecial(int kind, double now) {
			if (specialKind == kind && now < specialAt + specialLength) specialLength = Math.max(1, now - specialAt);
		}

		/** A heal lands at {@code at} (it may be ahead: the pulse travels to the blade first). */
		public void heal(double at) {
			pulse(Pulse.Kind.FEED, at);
			flickAt = Math.max(flickAt, at);
		}

		/** Biomass came in (a kill, a gain): a FEED pulse and the 30-tick surge. */
		public void gain(double now) {
			surgeAt = now;
			pulse(Pulse.Kind.FEED, now);
		}

		public void pulse(Pulse.Kind kind, double at) {
			if (pulseCount == MAX_PULSES) {
				System.arraycopy(pulseKind, 1, pulseKind, 0, MAX_PULSES - 1);
				System.arraycopy(pulseAt, 1, pulseAt, 0, MAX_PULSES - 1);
				pulseCount--;
			}
			// kept in start order, so a pulse due later (a heal on its way) never pushes out one already running
			int i = pulseCount;
			while (i > 0 && pulseAt[i - 1] > at) {
				pulseKind[i] = pulseKind[i - 1];
				pulseAt[i] = pulseAt[i - 1];
				i--;
			}
			pulseKind[i] = kind.ordinal();
			pulseAt[i] = at;
			pulseCount++;
		}

		/** Drops pulses that have run their course by {@code now}. */
		void prune(double now) {
			int keep = 0;
			for (int i = 0; i < pulseCount; i++) {
				if (now - pulseAt[i] <= PULSE_KEEP) {
					pulseKind[keep] = pulseKind[i];
					pulseAt[keep] = pulseAt[i];
					keep++;
				}
			}
			pulseCount = keep;
			if (specialKind != ArmDraw.NO_SPECIAL && now - specialAt > specialLength + 40) specialKind = ArmDraw.NO_SPECIAL;
		}

		public void clear() {
			hitAt = breakAt = surgeAt = specialAt = flickAt = poppedAt = LONG_AGO;
			specialKind = ArmDraw.NO_SPECIAL;
			pulseCount = 0;
			poppedForm = "";
			throwSeed = 0;
			throwSeenAt = LONG_AGO;
		}
	}

	/** What the last tick saw of a player's arm, to notice one that vanished without its timeline. */
	private record Seen(String form, int tier, int state, long startTick, int duration) {}

	private static final Map<Integer, Reaction> BY_ENTITY = new HashMap<>();
	private static final Map<Integer, Seen> SEEN = new HashMap<>();
	/** This player's gain clock as last seen ({@code ClientNet.ticksSinceGain}), to notice a new gain. */
	private static long lastSinceGain = Long.MAX_VALUE;

	private ArmReactions() {}

	/** {@code entityId}'s reactions, or null when nothing has happened to that arm lately. */
	public static @Nullable Reaction get(int entityId) {
		return BY_ENTITY.get(entityId);
	}

	private static Reaction touch(int entityId) {
		Reaction r = BY_ENTITY.computeIfAbsent(entityId, id -> new Reaction());
		r.lastTouched = Arms.TICK;
		return r;
	}

	/** Every arm effect from the server (Arms' listener): the in-hand half of it. */
	public static void onFx(ArmFxEvent e) {
		double now = e.receivedTick();
		switch (e.kind()) {
			case ArmProtocol.FX_HIT -> touch(e.origin()).hit(now);
			case ArmProtocol.FX_BREAK -> touch(e.origin()).breakBlock(now);
			case ArmProtocol.FX_SWIPE -> touch(e.origin()).special(ArmProtocol.FX_SWIPE, now, Math.max(DRAIN_CUT_TICKS, e.ticks()));
			case ArmProtocol.FX_VEIN -> {
				if (e.positions().length == 0) touch(e.origin()).endSpecial(ArmProtocol.FX_VEIN, now);
				else touch(e.origin()).special(ArmProtocol.FX_VEIN, now, Math.max(4, e.ticks()));
			}
			// a healing pulse sets off from the creature now and reaches the blade after its travel time, with a sculk
			// charge as it lands (spec 2.7)
			case ArmProtocol.FX_PULSE -> {
				double at = now + Math.max(0, e.ticks());
				touch(e.origin()).heal(at);
				if (PENDING_SOUNDS.size() < MAX_PENDING_SOUNDS) PENDING_SOUNDS.add(new PendingSound(e.origin(), at));
			}
			// a bolt that left (ids = [the arrow]) is a spend; the bow's ARMED and TIP notices carry no ids and are not a shot, so they
			// spend nothing
			case ArmProtocol.FX_BOLT -> {
				if (e.ids().length > 0) touch(e.origin()).pulse(Pulse.Kind.SPEND, now);
			}
			// The new forms' specials (2026-11-16) wind the tool in hand for a short, fixed time, so a geometry reads
			// d.specialWeight(ArmProtocol.FX_SPEAR) (and so on) without a case of its own here. Each kind multiplexes its moves on arg
			// (the server's numbering, docs/PROTOCOL.md); only the moves that are the hand's own moment wind it, and never for as long
			// as a state lasts (a pin, a throw, a regrow, a bleed belong to the world effects, and are resent while they hold).
			case ArmProtocol.FX_SPEAR -> {
				switch (e.arg()) {
					// the lunge's own length (1 to 6 ticks), long enough to see; the skewer landing is a flare, not the 30 to 50 ticks
					// the spikes hold; a release (arg 3) is the creature's business
					case SPEAR_LUNGE -> touch(e.origin()).special(e.kind(), now, Math.clamp(e.ticks(), 4, 12));
					case SPEAR_PIN -> touch(e.origin()).special(e.kind(), now, FLARE_TICKS);
					default -> { }
				}
			}
			case ArmProtocol.FX_MACE -> {
				switch (e.arg()) {
					// the rise to the apex winds the head up; the landing's ticks are the fall in tenths of a block (0..1000, the
					// shockwave's size), never a duration: a short fixed flare
					case MACE_LEAP -> touch(e.origin()).special(e.kind(), now, Math.clamp(e.ticks(), 4, 24));
					case MACE_LANDING -> touch(e.origin()).special(e.kind(), now, FLARE_TICKS);
					default -> { }
				}
			}
			case ArmProtocol.FX_TRIDENT -> {
				switch (e.arg()) {
					// a throw is resent every 20 ticks while it holds (with the same seed), and only its first message is the hand's
					// moment; a recall and a catch are one-off; the reel, a strike and the end are the tendril's and the world's
					// (a THROW that carries a position is the one sent to a viewer who came into range mid-flight: not the hand's moment)
					case TRIDENT_THROW -> {
						Reaction r = touch(e.origin());
						if (e.positions().length == 0 && (r.throwSeed != e.seed() || now - r.throwSeenAt > FORGET_TICKS)) r.special(e.kind(), now, FLARE_TICKS);
						r.throwSeed = e.seed();
						r.throwSeenAt = now;
					}
					case TRIDENT_RECALL, TRIDENT_CATCH -> touch(e.origin()).special(e.kind(), now, FLARE_TICKS);
					default -> { }
				}
			}
			// Spine Burst (arg 2: ticks = the gap between spines, ids = every spine) winds the crossbow for the whole burst, first spine
			// to last, plus the recoil; a bolt and a regrow (resent while it grows) are the geometry's own, not the hand's moment
			case ArmProtocol.FX_CROSSBOW -> {
				if (e.arg() == CROSSBOW_BURST) {
					int span = Math.max(e.ids().length - 1, 0) * Math.max(e.ticks(), 1);
					touch(e.origin()).special(e.kind(), now, Math.clamp(span + 4, 4, 40));
				}
			}
			// Rake winds the talons for its slashes (ClawMath.totalTicks, 3 ticks a slash: the Rake message's ticks, 9 by default). The
			// slashes and every bleed message (whose ticks are how long a bleed lasts, 3 to 5 s) are the target's and the world's, so
			// they never wind the hand again
			case ArmProtocol.FX_CLAWS -> {
				if ((e.arg() & CLAWS_MOVE_MASK) == CLAWS_RAKE) touch(e.origin()).special(e.kind(), now, e.ticks() > 0 ? Math.min(e.ticks(), 24) : CLAWS_RAKE_TICKS);
			}
			// The accessories (decided 2026-11-16: arg = place << 8 | move, the owner the origin) give the tool in hand a short
			// pulse where the accessory spent something or took force: a block, a reflect, an absorb, an ignition, a lit block. They never
			// wind a special (that slot belongs to the tool's own move, and an accessory landing mid-Rake must not cut it short), and the
			// spyglass's marks, resent every 20 ticks to the marker alone and shown on the HUD, are not the hand's moment at all.
			case ArmProtocol.FX_ACCESSORY -> {
				int move = ArmProtocol.accessoryMove(e.arg());
				switch (ArmProtocol.accessoryPlace(e.arg())) {
					case ArmProtocol.ACCESSORY_PLACE_SHIELD -> {
						if (move == SHIELD_BLOCK || move == SHIELD_REFLECT || move == SHIELD_ABSORB) touch(e.origin()).pulse(Pulse.Kind.SPEND, now);
					}
					case ArmProtocol.ACCESSORY_PLACE_FLINT -> {
						if (move == FLINT_IGNITE || move == FLINT_LIGHT) touch(e.origin()).pulse(Pulse.Kind.SPEND, now);
					}
					default -> { }
				}
			}
			default -> {
				// the stateful tendrils and the other forms' specials are world effects only
			}
		}
	}

	/** Once a tick (ArmRender's module tick): this player's gains, vanished arms, and forgetting the stale. */
	public static void tick(Minecraft mc) {
		long now = Arms.TICK;
		if (mc.player != null) {
			long since = ClientNet.ticksSinceGain();
			if (since < lastSinceGain && ClientNet.lastGain() > 0 && since < 5) touch(mc.player.getId()).gain(now - since);
			lastSinceGain = since;
		}
		watchStates(now);
		playDueSounds(mc, now);
		for (Iterator<Map.Entry<Integer, Reaction>> it = BY_ENTITY.entrySet().iterator(); it.hasNext(); ) {
			Reaction r = it.next().getValue();
			r.prune(now);
			if (now - r.lastTouched > FORGET_TICKS) it.remove();
		}
	}

	/**
	 * Notices an arm that went away without running its retract or melt to the end: the server took it at once (a slot
	 * change, a death, a morph, a command). The sheath then recedes for {@link #RECEDE_TICKS} on its own.
	 */
	private static void watchStates(long now) {
		Map<Integer, Seen> current = new HashMap<>();
		for (ArmState s : Arms.states()) {
			if (s.armed()) current.put(s.entityId, new Seen(s.heldForm(), Math.max(1, s.level), s.state, s.startTick, s.duration));
		}
		for (Map.Entry<Integer, Seen> e : SEEN.entrySet()) {
			if (current.containsKey(e.getKey())) continue;
			Seen was = e.getValue();
			boolean ranOut = (was.state == ArmProtocol.STATE_RETRACTING || was.state == ArmProtocol.STATE_MELTING)
					&& now - was.startTick >= was.duration - 2;
			// a death takes the arm with the body (no receding sheath), as ArmFx's puffs treat it
			ArmState after = Arms.stateByEntity(e.getKey());
			boolean died = after != null && after.why == ArmProtocol.WHY_DEATH;
			if (!ranOut && !died) {
				Reaction r = touch(e.getKey());
				r.poppedAt = now;
				r.poppedForm = was.form;
				r.poppedTier = was.tier;
			}
		}
		SEEN.clear();
		SEEN.putAll(current);
	}

	/** A sound due at a tick, from an entity's arm. */
	private record PendingSound(int origin, double at) {}

	private static final List<PendingSound> PENDING_SOUNDS = new ArrayList<>();
	private static final int MAX_PENDING_SOUNDS = 32;

	/** The PULSE arrivals due by now: block.sculk.charge p1.8 v0.4 at the striker's arm (a gone striker plays nothing). */
	private static void playDueSounds(Minecraft mc, long now) {
		if (PENDING_SOUNDS.isEmpty()) return;
		for (Iterator<PendingSound> it = PENDING_SOUNDS.iterator(); it.hasNext(); ) {
			PendingSound s = it.next();
			if (s.at() > now) continue;
			it.remove();
			if (mc.level == null || now - s.at() > 10) continue; // a hitch: too late to sound right
			Entity striker = mc.level.getEntity(s.origin());
			if (striker == null) continue;
			mc.level.playLocalSound(striker.getX(), striker.getEyeY() - 0.4, striker.getZ(), SoundEvents.SCULK_BLOCK_CHARGE, SoundSource.PLAYERS, 0.4F, 1.8F,
					false);
		}
	}

	/** Leaving a server or a world: nothing carries over. */
	public static void clear() {
		BY_ENTITY.clear();
		SEEN.clear();
		PENDING_SOUNDS.clear();
		lastSinceGain = Long.MAX_VALUE;
	}
}
