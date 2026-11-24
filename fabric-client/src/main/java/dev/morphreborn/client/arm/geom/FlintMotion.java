package dev.morphreborn.client.arm.geom;

import dev.morphreborn.client.arm.render.ArmMath;

/**
 * The flint-and-steel accessory's timelines and the little per-player memory its look needs, kept apart from the geometry so the
 * numbers can be tested without drawing anything (2026-11-16: a sparking tendril over the arm, with two hardened knuckles
 * that strike sparks now and then).
 * <ul>
 *   <li>{@link #built}: how much of the striker exists, from the accessory's draw age (the way the lure's age reads: ticks since it
 *       came on, negative while it draws back, so the build plays backwards).</li>
 *   <li>{@link #idleAge}: the knuckles strike on their own every so often (nobody sends it; it is the look's idle life), at
 *       moments that are each player's own and not on a metronome.</li>
 *   <li>{@link #struck}, {@link #forcedAge}, {@link #forcedPower}: a strike the server asked for (FX_ACCESSORY FLINT_SPARK when the
 *       accessory is switched on, FLINT_IGNITE when a hit sets something alight): the same strike, harder for an ignite.</li>
 *   <li>{@link #clack}: how far the knuckles are shut at a strike's age; {@link #spark}: how far along a spark of a strike is.</li>
 * </ul>
 * Everything is keyed by the player's entity id (a draw's seed). Render thread only. Nothing here allocates per frame: the table is a
 * fixed array and the oldest entry is the one reused.
 */
public final class FlintMotion {
	/** Ticks the striker takes to grow out when switched on, and to draw back in when switched off (ArmState.ACCESSORY_OUT_TICKS). */
	public static final float BUILD_TICKS = 16F, OUT_TICKS = 20F;
	/** How long the knuckles take to shut and to open again after a strike, and so how long a strike's clack lasts. */
	public static final float SHUT_TICKS = 1.2F, OPEN_TICKS = 3.3F, CLACK_TICKS = SHUT_TICKS + OPEN_TICKS;
	/** How long a strike's sparks live (a spark's own life is a share of it), and the table's memory of a server strike. */
	public static final float SPARK_TICKS = 10F, FORGET_TICKS = 24F;
	/** The idle strikes: one chance every {@link #SLOT_TICKS}, taken with this chance, at an offset leaving room for the sparks to die. */
	public static final float SLOT_TICKS = 60F, IDLE_CHANCE = 0.7F;
	/** The strike strength of an idle one, of a switch-on's spark and of an ignite (the number of sparks and how far they fly scale with it). */
	public static final float POWER_IDLE = 0.7F, POWER_SPARK = 1F, POWER_IGNITE = 1.6F;
	/** The arm clock's wrap, as ArmDrawFactory.CLOCK_WRAP (kept here so this class needs nothing of the model package). */
	private static final long CLOCK_WRAP = 72000L;
	private static final int SLOTS = 32;

	private FlintMotion() {}

	// ------------------------------------------------------------------ built

	/**
	 * How much of the striker is out, 0..1, at draw age {@code age}: growing over {@link #BUILD_TICKS} from switch-on, and while it
	 * draws back ({@code age} below 0: {@code -1 - ticks since it went out}) the same growth played backwards over {@link #OUT_TICKS}.
	 */
	public static float built(float age) {
		if (age >= 0F) return ArmMath.sm(age / BUILD_TICKS);
		float out = ArmMath.clamp01((-age - 1F) / OUT_TICKS);
		return 1F - ArmMath.sm(out);
	}

	// ------------------------------------------------------------------ idle strikes

	/** A fixed number in 0..1 for {@code a} and {@code b} (lowbias32 over both), the same every frame. */
	static float hash(int a, int b) {
		int x = a * 0x27D4EB2D + b * 0x165667B1;
		x ^= x >>> 16;
		x *= 0x7feb352d;
		x ^= x >>> 15;
		x *= 0x846ca68b;
		x ^= x >>> 16;
		return (x >>> 8) / 16777216F;
	}

	/** Ticks since the idle strike of slot {@code k} began, or {@link ArmDraw#NEVER} when that slot has none or it is not yet its moment. */
	private static float slotAge(int seed, long k, float time) {
		if (hash(seed, (int) k) >= IDLE_CHANCE) return ArmDraw.NEVER;
		float start = k * SLOT_TICKS + hash(seed + 91, (int) k) * (SLOT_TICKS - SPARK_TICKS);
		float age = time - start;
		return age < 0F ? ArmDraw.NEVER : age;
	}

	/**
	 * Ticks since {@code seed}'s striker last struck on its own at {@code time} (the draw's clock), or {@link ArmDraw#NEVER} when it has
	 * not within the last {@link #SPARK_TICKS}. Every player has their own beat; the same time gives the same answer, so a draw's three
	 * layers agree; and a clock that wraps back to near 0 simply starts a new run.
	 */
	public static float idleAge(int seed, float time) {
		if (!(time >= 0F)) return ArmDraw.NEVER;
		// a strike starts early enough in its slot for its sparks to die before the slot ends, so one slot is all there is to look at
		float age = slotAge(seed, (long) Math.floor(time / SLOT_TICKS), time);
		return age > SPARK_TICKS ? ArmDraw.NEVER : age;
	}

	// ------------------------------------------------------------------ strikes the server asked for

	private static final boolean[] USED = new boolean[SLOTS];
	private static final int[] ID = new int[SLOTS], SEED = new int[SLOTS];
	private static final long[] TICK = new long[SLOTS];
	private static final float[] POWER = new float[SLOTS];

	/**
	 * {@code id}'s striker struck at {@code tick} (the arm clock; a tick still to come sets the strike for then) with {@code power}
	 * ({@link #POWER_SPARK} or {@link #POWER_IGNITE}): its knuckles clack and its sparks fly, in the pattern {@code seed} picks. A later
	 * strike replaces an earlier one.
	 */
	public static void struck(int id, long tick, float power, int seed) {
		int slot = -1, oldest = 0;
		long oldestAt = Long.MAX_VALUE;
		for (int i = 0; i < SLOTS; i++) {
			if (USED[i] && ID[i] == id) {
				slot = i;
				break;
			}
			if (!USED[i]) {
				if (slot < 0) slot = i;
			} else if (TICK[i] < oldestAt) {
				oldestAt = TICK[i];
				oldest = i;
			}
		}
		if (slot < 0) slot = oldest;
		USED[slot] = true;
		ID[slot] = id;
		TICK[slot] = tick;
		POWER[slot] = power;
		SEED[slot] = seed;
	}

	private static int slotOf(int id) {
		for (int i = 0; i < SLOTS; i++) {
			if (USED[i] && ID[i] == id) return i;
		}
		return -1;
	}

	/** Ticks since the last strike the server asked for on {@code id}'s striker at the clock {@code time}, or {@link ArmDraw#NEVER} once it is forgotten. */
	public static float forcedAge(int id, float time) {
		int i = slotOf(id);
		if (i < 0) return ArmDraw.NEVER;
		float age = time - (float) (TICK[i] % CLOCK_WRAP);
		if (age < -CLOCK_WRAP / 2F) age += CLOCK_WRAP;
		else if (age > CLOCK_WRAP / 2F) age -= CLOCK_WRAP;
		// a strike set for later (a switch-on's spark waits for the tendril to grow out) has not begun: no age yet, and the idle strikes carry on
		return age >= FORGET_TICKS || age < -1F ? ArmDraw.NEVER : Math.max(0F, age);
	}

	/** How hard the last strike the server asked for was, 0 for none remembered. */
	public static float forcedPower(int id) {
		int i = slotOf(id);
		return i < 0 ? 0F : POWER[i];
	}

	/** The pick that sets the last forced strike's sparks (see {@link #struck}); 0 for none remembered. */
	public static int forcedSeed(int id) {
		int i = slotOf(id);
		return i < 0 ? 0 : SEED[i];
	}

	/** Forgets every remembered strike (a world change, leaving a server). */
	public static void clear() {
		java.util.Arrays.fill(USED, false);
	}

	// ------------------------------------------------------------------ one strike

	/**
	 * How far the knuckles are shut {@code age} ticks after a strike began: 0 open, 1 shut. They slam together in
	 * {@link #SHUT_TICKS} (slow to start, then fast, as something swung hard does), then spring open again over {@link #OPEN_TICKS}
	 * (fast at first, then settling: a hard thing rebounding), so a strike reads as a clack and not a blink.
	 */
	public static float clack(float age) {
		if (!(age >= 0F) || age >= CLACK_TICKS) return 0F;
		if (age < SHUT_TICKS) {
			float u = age / SHUT_TICKS;
			return u * u;
		}
		float u = (age - SHUT_TICKS) / OPEN_TICKS;
		return (1F - u) * (1F - u);
	}

	/**
	 * How hot the knuckles' striking faces are {@code age} ticks after a strike: up at once, then cooling over a few ticks to the idle
	 * glow ({@code idle}, 0..1), which is what a viewer sees between strikes.
	 */
	public static float heat(float age, float idle) {
		if (!(age >= 0F) || age >= SPARK_TICKS) return idle;
		return ArmMath.lerp(idle, 1F, (float) Math.exp(-0.45F * age));
	}

	/**
	 * How far along its life spark {@code i} of a strike is {@code age} ticks after it began: 0 at the strike, 1 when it has gone out,
	 * and above 1 when it has. Sparks are not alike: each lives for a different share of {@link #SPARK_TICKS}.
	 */
	public static float spark(float age, int i, int seed) {
		if (!(age >= 0F)) return 2F;
		float life = SPARK_TICKS * (0.5F + 0.5F * hash(seed + 7, i));
		return age / life;
	}
}
