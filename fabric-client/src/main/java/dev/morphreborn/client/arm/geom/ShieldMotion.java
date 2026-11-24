package dev.morphreborn.client.arm.geom;

import dev.morphreborn.client.arm.render.ArmMath;

/**
 * The shield's timelines and the little per-player memory they need (the raise easing and the last block), kept apart from the geometry so the numbers can be
 * tested without drawing anything (2026-11-16: a tendril shield on the off arm, raised by holding right-click since 2026-11-17: it was sneak first).
 * <ul>
 *   <li>{@link #built}: how much of the shield exists, from the accessory's draw age (the way the lure's age reads: ticks since it
 *       came on, negative while it draws back, so the build plays backwards).</li>
 *   <li>{@link #raise}: how far up the shield is, 0 lowered (drawn in to a knot at the wrist) to 1 raised (held out in front). The server only
 *       says raised or not (the accessory's arg), so each viewer eases it: up in 4 ticks, down in 6.</li>
 *   <li>{@link #blocked}, {@link #blockAge}, {@link #recoil}, {@link #ripple}: a blocked hit or a reflected shot, as the server
 *       sends it (FX_ACCESSORY): the shield kicks back and a ripple runs out over the weave from where it was struck.</li>
 * </ul>
 * Everything is keyed by the player's entity id (a draw's seed). Render thread only. Nothing here allocates per frame: the
 * tables are fixed arrays and the oldest entry is the one reused.
 */
public final class ShieldMotion {
	/** Ticks the shield takes to grow out when switched on, and to draw back in when switched off (ArmState.ACCESSORY_OUT_TICKS). */
	public static final float BUILD_TICKS = 18F, OUT_TICKS = 20F;
	/** Ticks the raise takes, and the lowering: blocking in vanilla comes up in 5, so a little under it. */
	public static final float RAISE_TICKS = 4F, LOWER_TICKS = 6F;
	/** How long a block's recoil and ripple show (ticks), and how long one is remembered. */
	public static final float BLOCK_TICKS = 14F, BLOCK_FORGET = 40F;
	/** The arm clock's wrap, as ArmDrawFactory.CLOCK_WRAP (kept here so this class needs nothing of the model package). */
	private static final long CLOCK_WRAP = 72000L;
	private static final int SLOTS = 32;

	private ShieldMotion() {}

	// ------------------------------------------------------------------ built

	/**
	 * How much of the shield is out, 0..1, at draw age {@code age}: growing over {@link #BUILD_TICKS} from switch-on, and while it
	 * draws back ({@code age} below 0: {@code -1 - ticks since it went out}) the same growth played backwards over
	 * {@link #OUT_TICKS}.
	 */
	public static float built(float age) {
		if (age >= 0F) return ArmMath.sm(age / BUILD_TICKS);
		float out = ArmMath.clamp01((-age - 1F) / OUT_TICKS);
		return 1F - ArmMath.sm(out);
	}

	// ------------------------------------------------------------------ raise

	private static final boolean[] RUSED = new boolean[SLOTS];
	private static final int[] RID = new int[SLOTS];
	private static final float[] RFROM = new float[SLOTS], RTO = new float[SLOTS], RSINCE = new float[SLOTS], RLAST = new float[SLOTS];

	private static int raiseSlot(int id, float time) {
		int free = -1, oldest = 0;
		float oldestAt = Float.MAX_VALUE;
		for (int i = 0; i < SLOTS; i++) {
			if (RUSED[i] && RID[i] == id) {
				// the clock wraps hourly: a time far behind the last one is a new hour, not the past
				if (time < RLAST[i] - 2000F) RSINCE[i] = time;
				return i;
			}
			if (!RUSED[i]) {
				if (free < 0) free = i;
			} else if (RLAST[i] < oldestAt) {
				oldestAt = RLAST[i];
				oldest = i;
			}
		}
		return free >= 0 ? free : oldest;
	}

	/**
	 * The raise, 0..1, of {@code id}'s shield at {@code time} (the draw's clock) given the arg the server last sent ({@code arg}
	 * non-zero: raised). A first sighting is simply at rest in whatever the arg says. Asking again for the same time gives the same
	 * answer, so the pose mixin and the renderer agree within a frame.
	 */
	public static float raise(int id, float time, int arg) {
		int i = raiseSlot(id, time);
		float to = arg != 0 ? 1F : 0F;
		if (!RUSED[i] || RID[i] != id) {
			RUSED[i] = true;
			RID[i] = id;
			RFROM[i] = to;
			RTO[i] = to;
			RSINCE[i] = time - 1000F;
		}
		RLAST[i] = time;
		if (RTO[i] != to) {
			RFROM[i] = raiseValue(i, time);
			RTO[i] = to;
			RSINCE[i] = time;
		}
		return raiseValue(i, time);
	}

	/**
	 * Ticks since {@code id}'s shield finished rising at the clock {@code time} (0 and up once it has), or {@link ArmDraw#NEVER} while it is
	 * lowered, still rising, or was first seen already up: the overlay's one flash on reaching the raised pose.
	 */
	public static float raisedAge(int id, float time) {
		for (int i = 0; i < SLOTS; i++) {
			if (RUSED[i] && RID[i] == id) {
				if (RTO[i] < 1F) return ArmDraw.NEVER;
				float age = time - RSINCE[i] - RAISE_TICKS;
				return age >= 0F ? age : ArmDraw.NEVER;
			}
		}
		return ArmDraw.NEVER;
	}

	private static float raiseValue(int i, float time) {
		float len = RTO[i] > RFROM[i] ? RAISE_TICKS : LOWER_TICKS;
		float u = ArmMath.clamp01((time - RSINCE[i]) / len);
		return ArmMath.lerp(RFROM[i], RTO[i], ArmMath.sm(u));
	}

	// ------------------------------------------------------------------ blocks

	private static final boolean[] BUSED = new boolean[SLOTS];
	private static final int[] BID = new int[SLOTS], BSEED = new int[SLOTS];
	private static final long[] BTICK = new long[SLOTS];
	private static final float[] BPOWER = new float[SLOTS];

	/**
	 * A hit was blocked, or a shot sent back ({@code power} 1 and 1.5): {@code id}'s shield kicks at {@code tick} (the arm clock) and
	 * rings from a point that {@code seed} picks on its face (the server names the world point, which can't be turned into the
	 * shield's own space here, so the ripple's centre is a fixed pick for the blow).
	 */
	public static void blocked(int id, long tick, float power, int seed) {
		int slot = -1, oldest = 0;
		long oldestAt = Long.MAX_VALUE;
		for (int i = 0; i < SLOTS; i++) {
			if (BUSED[i] && BID[i] == id) {
				slot = i;
				break;
			}
			if (!BUSED[i]) {
				if (slot < 0) slot = i;
			} else if (BTICK[i] < oldestAt) {
				oldestAt = BTICK[i];
				oldest = i;
			}
		}
		if (slot < 0) slot = oldest;
		BUSED[slot] = true;
		BID[slot] = id;
		BTICK[slot] = tick;
		BPOWER[slot] = power;
		BSEED[slot] = seed;
	}

	private static int blockSlot(int id) {
		for (int i = 0; i < SLOTS; i++) {
			if (BUSED[i] && BID[i] == id) return i;
		}
		return -1;
	}

	/** Ticks since {@code id}'s shield was last struck at the clock {@code time}, or {@link ArmDraw#NEVER} once it is forgotten. */
	public static float blockAge(int id, float time) {
		int i = blockSlot(id);
		if (i < 0) return ArmDraw.NEVER;
		float age = time - (float) (BTICK[i] % CLOCK_WRAP);
		if (age < -CLOCK_WRAP / 2F) age += CLOCK_WRAP;
		else if (age > CLOCK_WRAP / 2F) age -= CLOCK_WRAP;
		return age >= BLOCK_FORGET ? ArmDraw.NEVER : Math.max(0F, age);
	}

	/** How hard the last blow was (1 a block, 1.5 a reflect), 0 for none remembered. */
	public static float blockPower(int id) {
		int i = blockSlot(id);
		return i < 0 ? 0F : BPOWER[i];
	}

	/** The pick that places the last blow's ripple (see {@link #blocked}); 0 for none remembered. */
	public static int blockSeed(int id) {
		int i = blockSlot(id);
		return i < 0 ? 0 : BSEED[i];
	}

	/** How far back the shield is knocked {@code age} ticks after a blow, 0..about 1 (multiply by the power and the kick's size): quick in, slow out. */
	public static float recoil(float age) {
		if (!(age >= 0F) || age > BLOCK_TICKS) return 0F;
		return (1F - (float) Math.exp(-6F * age)) * (float) Math.exp(-0.5F * age);
	}

	/**
	 * The weave's displacement (px, along the shield's normal) at {@code dist} px from where it was struck, {@code age} ticks after the
	 * blow: a ring spreading outward at about 3 px a tick, thinning as it goes and dying away over the blow's ticks.
	 */
	public static float ripple(float dist, float age) {
		if (!(age >= 0F) || age > BLOCK_TICKS || !(dist >= 0F)) return 0F;
		float front = age * 3.2F;
		// a ring, not a ripple everywhere: strongest at the front, dying behind it and ahead of it
		float band = (float) Math.exp(-((dist - front) * (dist - front)) / 9F);
		float wave = (float) Math.sin(ArmMath.TAU * (dist / 5.5F - age / 2.2F));
		return 0.9F * band * wave * (float) Math.exp(-0.18F * age) * ArmMath.sm(age * 3F);
	}

	/** Everything kept: joining or leaving a server, or a new world. */
	public static void clear() {
		java.util.Arrays.fill(RUSED, false);
		java.util.Arrays.fill(BUSED, false);
	}
}
