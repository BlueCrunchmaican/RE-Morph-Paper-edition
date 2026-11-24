package dev.morphreborn.paper.arm.rules;

/**
 * The whip's numbers (spec section 9), pure: how hard the line drags you to a block, and how much harder than vanilla
 * it drags a creature to you. The module turns a hook into the gaps given here and applies the
 * velocities that come back; nothing in this class knows about the server, so every rule is unit tested.
 * <p>
 * All velocities are blocks per tick, as an entity's. Vanilla's own pull on a hooked creature adds a tenth of the gap
 * between the owner and the hook to the creature's velocity every reel-in; the yank is a multiple of that.
 */
public final class WhipMath {
	/** Vanilla's pull on a hooked creature: this share of the gap from the hook to its owner, added to the creature's velocity. */
	public static final double VANILLA_PULL = 0.1;
	/** The longest extra velocity a yank may add, so a creature at the end of a long line isn't thrown across the map. */
	public static final double YANK_CAP = 2.0;
	/** A hook closer than this to your body doesn't pull you: there is nowhere to go. */
	public static final double MIN_GRAPPLE_DISTANCE = 1.5;
	/** A pull along the ground lifts you at least this fast, so the ground's friction doesn't eat it. */
	public static final double GROUND_LIFT = 0.1;

	private WhipMath() {}

	/** The pull's speed at a distance: {@code speedPerBlock} a block, never under the slowest or over the level's fastest. */
	public static double grappleSpeed(double dist, int level, WhipSettings s) {
		return Math.clamp(s.speedPerBlock() * Math.max(0, dist), s.minSpeed(), s.speedCap(level));
	}

	/** The hook is near enough for the line to hold; further and it would have broken. */
	public static boolean inRange(double dist, int level, WhipSettings s) {
		return dist <= s.range(level);
	}

	/** The hook is far enough from the body to pull anywhere. */
	public static boolean farEnough(double dist) {
		return dist >= MIN_GRAPPLE_DISTANCE;
	}

	/**
	 * The velocity that drags you toward a point: the direction of the gap (from the middle of your body to the point)
	 * at {@link #grappleSpeed}, plus the lift when the point is above your eyes. On the ground, a pull that wouldn't lift
	 * you at all lifts you {@link #GROUND_LIFT}. A zero gap gives no velocity.
	 *
	 * @return {x, y, z}
	 */
	public static double[] grapple(double dx, double dy, double dz, boolean aboveEyes, boolean onGround, int level, WhipSettings s) {
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (!(dist > 1e-6)) return new double[] {0, 0, 0};
		double speed = grappleSpeed(dist, level, s);
		double vy = dy / dist * speed;
		if (aboveEyes) vy += s.lift();
		else if (onGround && vy < GROUND_LIFT) vy = GROUND_LIFT;
		return new double[] {dx / dist * speed, vy, dz / dist * speed};
	}

	/**
	 * What a yank adds to a creature's velocity.
	 *
	 * @param gx the gap from the hook to the owner's feet, as vanilla measures its pull
	 * @param scale 1 for a creature, less for a player (arms.lesser-rate)
	 * @param vanillaPulls the reel-in goes on to add vanilla's own pull: only the part beyond it is added (false: all
	 *            of it, since nothing else pulls)
	 * @return {x, y, z}, no longer than {@link #YANK_CAP}
	 */
	public static double[] yank(double gx, double gy, double gz, int level, double scale, boolean vanillaPulls, WhipSettings s) {
		double factor = s.factor(level) - (vanillaPulls ? 1 : 0);
		double k = Math.max(0, factor) * VANILLA_PULL * Math.max(0, scale);
		double x = gx * k, y = gy * k, z = gz * k;
		double len = Math.sqrt(x * x + y * y + z * z);
		if (len > YANK_CAP) {
			double f = YANK_CAP / len;
			x *= f;
			y *= f;
			z *= f;
		}
		return new double[] {x, y, z};
	}

	/** A creature this light is pulled to you; a heavier one pulls you to it instead. */
	public static boolean light(double massKg, WhipSettings s) {
		return massKg < s.maxMass();
	}
}
