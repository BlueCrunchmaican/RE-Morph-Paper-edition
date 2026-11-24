package dev.morphreborn.paper.arm.rules;

/**
 * One player's crossbow arm and the bolt in it (my design note, 2026-11-16): it is formed loaded, a click fires the bolt, and then
 * the bolt grows back out of the weapon over the time the game takes to charge a crossbow. No Bukkit here, so it is unit tested; CrossbowArm
 * reads it every tick and writes the item (its charged state) to match.
 * <p>
 * Three phases. LOADED: the bolt is whole and the next click fires it. GROWING: the bolt is regrowing and a click does nothing; it is whole
 * again at {@link #readyAt()}. BURSTING: Spine Burst has emptied the crossbow and its spines are still leaving; the regrow starts when the
 * last has gone ({@link #endBurst}). Times are the server's own tick count, so the state keeps counting while the arm is tucked or the player is
 * elsewhere, and a bolt that finished growing meanwhile is whole when the hand comes back.
 * <p>
 * A state belongs to the player, not to a session of the arm (2026-11-16): a tuck and untuck, a switch to another form and back, a
 * re-tier all give the arm a new nonce, and a state keyed to it started loaded again, so any such cycle skipped the regrow, which is the only
 * rate limit on plain bolts. The ticks are absolute, so a bolt that finished growing while the arm was away is whole when it comes back, and one
 * still growing carries on. The state is formed loaded only for a player who has none (their first form-up, or one with nothing left to keep).
 */
public final class CrossbowBolt {
	public enum Phase { LOADED, GROWING, BURSTING }

	/** How many ticks apart a growing bolt's state is sent again, so a viewer who comes into range mid-growth catches up. */
	public static final int RESEND_TICKS = 10;
	/** The vanilla charge sounds, by how far through the charge they play (CrossbowItem.START_SOUND_PERCENT and MID_SOUND_PERCENT). */
	public static final float START_SOUND_AT = 0.2F, MID_SOUND_AT = 0.5F;
	/** What {@link #soundDue} says: nothing, the start of the draw, the middle of it. The end sound plays when the bolt is whole. */
	public static final int SOUND_NONE = 0, SOUND_START = 1, SOUND_MID = 2;

	/** Mixes into every regrow's seed, so two players' (or two server runs') first regrows never share one. */
	private final int salt;
	private Phase phase = Phase.LOADED;
	private int grownFrom, readyAt, cycle, lastSent = Integer.MIN_VALUE, burstGrow;
	private int soundsPlayed;
	private boolean readyPending;

	/** A state formed loaded; {@code salt} (any number) makes its regrow seeds its own. */
	public CrossbowBolt(int salt) {
		this.salt = salt;
	}

	/**
	 * The seed every FX_CROSSBOW REGROW message of the current regrow carries: the same on all its messages, different for the next regrow
	 * (and for another player's), because a client keeps a regrow it already has when the seed is the same. Never 0 or negative.
	 */
	public int regrowSeed() {
		return (salt + cycle * 0x9E3779B1) & 0x3FFFFFFF | 1;
	}

	/** How many ticks the current (or last) regrow takes in all. */
	public int totalTicks() {
		return Math.max(1, readyAt - grownFrom);
	}

	public Phase phase() {
		return phase;
	}

	/** The bolt is whole: the next click fires it. */
	public boolean loaded() {
		return phase == Phase.LOADED;
	}

	/** Which regrow this is, counted from 1 (0: none has started); a client tells one growth from the next by it. */
	public int cycle() {
		return cycle;
	}

	/** The tick the bolt is whole again (meaningful while GROWING). */
	public int readyAt() {
		return readyAt;
	}

	/**
	 * Time passes. A growing bolt whose time is up becomes whole, and is then "just whole" until {@link #takeReady} says so (whichever
	 * caller noticed it first, the one that tells the clients still hears of it exactly once).
	 */
	public void advance(int now) {
		if (phase == Phase.GROWING && now >= readyAt) {
			phase = Phase.LOADED;
			readyPending = true;
		}
	}

	/** True once after a regrow finished: the ready message is due (and the last charge sound). */
	public boolean takeReady() {
		boolean was = readyPending;
		readyPending = false;
		return was;
	}

	/**
	 * A click fired the bolt: it grows back over {@code growTicks} from {@code now}. Returns false (and changes nothing) when there was no
	 * whole bolt to fire.
	 */
	public boolean fired(int now, int growTicks) {
		if (phase != Phase.LOADED) return false;
		startGrowing(now, growTicks);
		return true;
	}

	/** Spine Burst takes the whole bolt: the crossbow is empty and nothing regrows until {@link #endBurst}. False when there was none to take. */
	public boolean beginBurst() {
		return beginBurst(0);
	}

	/**
	 * As {@link #beginBurst()}, remembering the regrow length the burst will end with (the held crossbow's, Quick Charge counted), so a burst
	 * whose end is missed while the crossbow is tucked, with no item to read the enchantment from, still regrows as long as it would in hand.
	 */
	public boolean beginBurst(int growTicks) {
		if (phase != Phase.LOADED) return false;
		phase = Phase.BURSTING;
		burstGrow = Math.max(0, growTicks);
		return true;
	}

	/**
	 * A burst is marked as running but nothing is running it (it was stopped without its end noticed): it ends now, so the crossbow does not
	 * stay empty for good. Uses the length the burst began with, else {@code fallbackGrowTicks}. True when it did end one.
	 */
	public boolean recoverOrphanedBurst(int now, int fallbackGrowTicks) {
		if (phase != Phase.BURSTING) return false;
		endBurst(now, burstGrow > 0 ? burstGrow : fallbackGrowTicks);
		return true;
	}

	/**
	 * Whether a tucked crossbow's stand-in (shown {@code shownCharged}) no longer matches the bolt, so the session's snapshot must be
	 * refreshed ({@code ArmContext#tuckedLoaded}). The decision of rig bug B1 (2026-11-16), kept here so a test pins which side it reads.
	 */
	public boolean standInStale(boolean shownCharged) {
		return shownCharged != loaded();
	}

	/** The last spine has left (or the burst was stopped): the bolt starts to grow back over {@code growTicks} from {@code now}. */
	public void endBurst(int now, int growTicks) {
		if (phase != Phase.BURSTING) return;
		startGrowing(now, growTicks);
	}

	private void startGrowing(int now, int growTicks) {
		phase = Phase.GROWING;
		grownFrom = now;
		readyAt = now + Math.max(1, growTicks);
		cycle++;
		readyPending = false;
		lastSent = now;
		soundsPlayed = 0;
	}

	/** How many ticks are left before the bolt is whole (0 unless GROWING). */
	public int ticksLeft(int now) {
		return phase == Phase.GROWING ? Math.max(0, readyAt - now) : 0;
	}

	/** Whether the growing bolt's state is due to be sent again (and, when it is, counts as sent now). */
	public boolean resendDue(int now) {
		if (phase != Phase.GROWING || now - lastSent < RESEND_TICKS) return false;
		lastSent = now;
		return true;
	}

	/**
	 * The vanilla charge sound that is due now while growing: the start of the draw a fifth of the way in, the middle halfway (each once per
	 * regrow), as the game plays them while a real crossbow charges. {@link #SOUND_NONE} otherwise.
	 */
	public int soundDue(int now) {
		if (phase != Phase.GROWING) return SOUND_NONE;
		float done = (float) (now - grownFrom) / Math.max(1, readyAt - grownFrom);
		if (soundsPlayed < SOUND_MID && done >= MID_SOUND_AT) {
			soundsPlayed = SOUND_MID;
			return SOUND_MID;
		}
		if (soundsPlayed < SOUND_START && done >= START_SOUND_AT) {
			soundsPlayed = SOUND_START;
			return SOUND_START;
		}
		return SOUND_NONE;
	}

	// ------------------------------------------------------------------ the numbers

	/**
	 * How many ticks a crossbow takes to charge: the game's own (CrossbowItem.getChargeDuration), {@code floor((base - 0.25 * quickCharge) * 20)},
	 * 25 ticks for the game's 1.25 seconds and 5 fewer for each level of Quick Charge. At least 1: a bolt always takes a moment to grow.
	 */
	public static int growTicks(double baseSeconds, int quickCharge) {
		double seconds = Math.max(0, baseSeconds - 0.25 * Math.max(0, quickCharge));
		return Math.max(1, (int) Math.floor(seconds * 20.0 + 1e-6));
	}

	/** The ticks at which a burst of {@code count} spines {@code gap} apart sends each one, counted from the burst's start: 0, gap, 2 gap ... */
	public static int[] spineTicks(int count, int gap) {
		int n = Math.max(1, count), g = Math.max(1, gap);
		int[] out = new int[n];
		for (int i = 0; i < n; i++) out[i] = i * g;
		return out;
	}

	// ------------------------------------------------------------------ the mark on a bolt or a spine in the world

	/** A bolt a click fired (what a CrossbowArm projectile's mark says it is). */
	public static final int KIND_BOLT = 1;
	/** A spine of Spine Burst. */
	public static final int KIND_SPINE = 2;

	/** The one number kept on a flying bolt or spine: its kind and the tier it was shot at. */
	public static int mark(int kind, int level) {
		return (kind << 4) | Math.clamp(level, 1, 3);
	}

	/** The kind a mark says ({@link #KIND_BOLT} or {@link #KIND_SPINE}), 0 for anything else. */
	public static int kindOf(int mark) {
		int kind = mark >> 4;
		return kind == KIND_BOLT || kind == KIND_SPINE ? kind : 0;
	}

	/** The tier a mark says, 1 to 3. */
	public static int levelOf(int mark) {
		return Math.clamp(mark & 15, 1, 3);
	}
}
