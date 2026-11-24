package dev.morphreborn.client.arm.geom;

/**
 * Makes room in the vanilla first-person overlay (2026-11-24): the accessories (shield, spyglass, flint and steel, the lantern) are drawn at
 * fixed spots of the screen, and they passed through the item in hand (an arm form or a vanilla item) and through each other when several were
 * out. This is the arithmetic that moves them out of the way, with no game types so it can be tested over every case.
 * <p>
 * Everything is in the hand pass's view space (px, x right, y up, z away). A thing is a chain of spheres ({@link Body}), each with a weight
 * (0 at its root on the screen's edge, 1 at its free end: how much of the shift it takes). The solver moves only the free end and keeps the
 * root where it is (the stalk leans and bends round the item instead of sliding), the smallest shift that clears every obstacle sphere by
 * {@link #MARGIN}, kept on the screen; if no shift is enough it shrinks the free part toward its root (never below the body's own
 * {@code minScale}), and as a last resort tucks it back toward the edge. It never hides a body and never refuses one. Held things are
 * obstacles and never move; who yields to whom is the caller's order of solving (each placed body becomes an obstacle for the next).
 */
public final class FpClearance {
	private FpClearance() {
	}

	/** Gap kept between a moved accessory and what it makes room for (px). */
	public static final float MARGIN = 0.3F;
	/** The hand pass's lens: 70 degrees tall at 16:9, so |y| &lt; 0.70 depth and |x| &lt; 1.245 depth. */
	public static final float LENS_Y = 0.7002F, LENS_X = 1.2447F;
	/** The most a free end is moved (px). */
	public static final float MAX_SHIFT = 10F;
	/** Ticks a relaxing push takes to ease back (a push is applied at once, never late). */
	public static final float RELAX_TICKS = 5F;
	private static final int MAX = 16, MAX_OBSTACLES = 640;

	/** A chain of spheres: its home layout, its weights, the point it shrinks toward and the least it may shrink to. */
	public static final class Body {
		final float[] x = new float[MAX], y = new float[MAX], z = new float[MAX], r = new float[MAX], w = new float[MAX];
		final boolean[] fixed = new boolean[MAX];
		/** The sphere whose displacement a follower takes (its chain hangs from it), or -1. */
		final int[] anchor = new int[MAX];
		int n;
		float rootX, rootY, rootZ;
		float minScale = 0.5F;

		public Body clear(float rx, float ry, float rz, float least) {
			n = 0;
			rootX = rx;
			rootY = ry;
			rootZ = rz;
			minScale = least;
			return this;
		}

		/** Adds a sphere at ({@code px}, {@code py}, {@code pz}) of radius {@code pr} that follows the free end by {@code weight} (0 root, 1 free). */
		public Body add(float px, float py, float pz, float pr, float weight) {
			if (n < MAX) {
				fixed[n] = false;
				anchor[n] = -1;
				x[n] = px;
				y[n] = py;
				z[n] = pz;
				r[n] = pr;
				w[n] = weight;
				n++;
			}
			return this;
		}

		/** As {@link #add}, for a sphere whose radius does not shrink when the free part does (a stalk's thickness, the lantern's lure: they are drawn at full size whatever the scale). */
		public Body addFixed(float px, float py, float pz, float pr, float weight) {
			add(px, py, pz, pr, weight);
			if (n > 0 && n <= MAX && x[n - 1] == px && y[n - 1] == py && z[n - 1] == pz) fixed[n - 1] = true;
			return this;
		}

		/**
		 * Adds a sphere that hangs from sphere {@code from} (a lantern's chain and lure under its hook): it takes whatever displacement {@code from} gets, the
		 * shrink toward the root included, but is itself neither scaled about the root nor shrunk, as drawn (the chain's links keep their length whatever the
		 * stalk does, so the lure stays a chain's length under the hook).
		 */
		public Body addFollow(float px, float py, float pz, float pr, int from) {
			if (from < 0 || from >= n) return this;
			add(px, py, pz, pr, w[from]);
			fixed[n - 1] = true;
			anchor[n - 1] = from;
			return this;
		}

		float radius(int i, float scale) {
			return fixed[i] ? r[i] : r[i] * scale;
		}

		public int count() {
			return n;
		}
	}

	/** What to keep clear of: spheres in the same space. */
	public static final class Obstacles {
		final float[] x = new float[MAX_OBSTACLES], y = new float[MAX_OBSTACLES], z = new float[MAX_OBSTACLES], r = new float[MAX_OBSTACLES];
		int n;

		public Obstacles clear() {
			n = 0;
			return this;
		}

		public Obstacles add(float px, float py, float pz, float pr) {
			if (n < MAX_OBSTACLES) {
				x[n] = px;
				y[n] = py;
				z[n] = pz;
				r[n] = pr;
				n++;
			}
			return this;
		}

		/** Adds every sphere of {@code b} as it stands once placed by {@code p}. */
		/** Adds sphere {@code i} of {@code other}. */
		public Obstacles copyFrom(Obstacles other, int i) {
			return add(other.x[i], other.y[i], other.z[i], other.r[i]);
		}

		public Obstacles addPlaced(Body b, Pose p) {
			for (int i = 0; i < b.n; i++) add(px(b, p, i), py(b, p, i), pz(b, p, i), b.radius(i, p.scale));
			return this;
		}

		/** Sphere {@code j} as {x, y, z, r} (for the tests). */
		public float[] sphere(int j) {
			return new float[] {x[j], y[j], z[j], r[j]};
		}

		public int count() {
			return n;
		}
	}

	/** How a body is placed: its free end shifted, its free part scaled about its root, how it ended. */
	public static final class Pose {
		public float sx, sy, sz, scale = 1F;
		/** The deepest sphere overlap left once placed (px, 0 or less: none). */
		public float worst;
		public boolean tucked;

		public Pose set(float x, float y, float z, float k) {
			sx = x;
			sy = y;
			sz = z;
			scale = k;
			return this;
		}

		public Pose copy(Pose o) {
			sx = o.sx;
			sy = o.sy;
			sz = o.sz;
			scale = o.scale;
			worst = o.worst;
			tucked = o.tucked;
			return this;
		}

		public float length() {
			return (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
		}

		public boolean isHome() {
			return sx == 0F && sy == 0F && sz == 0F && scale == 1F;
		}
	}

	static float px(Body b, Pose p, int i) {
		int a = b.anchor[i];
		if (a >= 0) return b.x[i] + (px(b, p, a) - b.x[a]);
		return b.rootX + (b.x[i] - b.rootX) * p.scale + b.w[i] * p.sx;
	}

	static float py(Body b, Pose p, int i) {
		int a = b.anchor[i];
		if (a >= 0) return b.y[i] + (py(b, p, a) - b.y[a]);
		return b.rootY + (b.y[i] - b.rootY) * p.scale + b.w[i] * p.sy;
	}

	static float pz(Body b, Pose p, int i) {
		int a = b.anchor[i];
		if (a >= 0) return b.z[i] + (pz(b, p, a) - b.z[a]);
		return b.rootZ + (b.z[i] - b.rootZ) * p.scale + b.w[i] * p.sz;
	}

	/** The deepest overlap (px) of {@code b} placed by {@code p} with the obstacles, each kept {@code margin} clear; 0 or less when clear. */
	public static float worst(Body b, Pose p, Obstacles o, float margin) {
		float worst = -1e9F;
		for (int i = 0; i < b.n; i++) {
			float cx = px(b, p, i), cy = py(b, p, i), cz = pz(b, p, i), cr = b.radius(i, p.scale);
			for (int j = 0; j < o.n; j++) {
				float dx = cx - o.x[j], dy = cy - o.y[j], dz = cz - o.z[j];
				float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
				float pen = cr + o.r[j] + margin - d;
				if (pen > 0F && !seen(cx, cy, cz, o.x[j], o.y[j], o.z[j])) continue;
				worst = Math.max(worst, pen);
			}
		}
		return worst;
	}

	/**
	 * Whether an overlap of two spheres could be seen: its middle is in the lens (or within a couple of px of its edge, for the thickness). Two
	 * things passing through each other below the bottom edge of the screen, where the stalks start, show nothing.
	 */
	static boolean seen(float ax, float ay, float az, float bx, float by, float bz) {
		return onScreen((ax + bx) / 2F, (ay + by) / 2F, (az + bz) / 2F, -2.5F);
	}

	/** Whether a point at (x, y, z) is inside the lens, {@code inset} px in from its edges. */
	public static boolean onScreen(float x, float y, float z, float inset) {
		float depth = -z;
		return depth > 1F && Math.abs(y) <= LENS_Y * depth - inset && Math.abs(x) <= LENS_X * depth - inset;
	}

	/**
	 * Places {@code b} clear of {@code o} into {@code out}: home if nothing is in the way, else the smallest shift of its free end (up to
	 * {@link #MAX_SHIFT}, its free end kept on the screen), then shrinking, then tucked. {@code out.worst} says what overlap is left (0 or less when none).
	 */
	public static void solve(Body b, Obstacles o, float margin, Pose out) {
		solveFrom(b, o, margin, out, null);
	}

	private static final Obstacles NEAR = new Obstacles();

	/**
	 * The obstacles that {@code b} could touch wherever it is placed (home, shifted by at most {@link #MAX_SHIFT}, shrunk toward its root): the rest cannot matter,
	 * and a held tool is a hundred spheres, so the solver loops over these alone. Render thread only (one scratch set).
	 */
	private static Obstacles nearTo(Body b, Obstacles all, float margin) {
		float reach = 0F;
		for (int i = 0; i < b.n; i++) {
			float d = (float) Math.sqrt(sq(b.x[i] - b.rootX) + sq(b.y[i] - b.rootY) + sq(b.z[i] - b.rootZ));
			if (b.anchor[i] >= 0) {
				int a = b.anchor[i];
				d += (float) Math.sqrt(sq(b.x[a] - b.rootX) + sq(b.y[a] - b.rootY) + sq(b.z[a] - b.rootZ));
			}
			reach = Math.max(reach, d + b.r[i]);
		}
		reach += MAX_SHIFT + margin + 1F;
		NEAR.clear();
		for (int j = 0; j < all.n; j++) {
			if (sq(all.x[j] - b.rootX) + sq(all.y[j] - b.rootY) + sq(all.z[j] - b.rootZ) <= sq(reach + all.r[j])) NEAR.copyFrom(all, j);
		}
		return NEAR;
	}

	private static float sq(float v) {
		return v * v;
	}

	/** As {@link #solve} but starting from the placement {@code start} (the one shown last frame) instead of home, so a held item moving a little moves the answer a little. */
	public static void solveFrom(Body b, Obstacles all, float margin, Pose out, Pose start) {
		Obstacles o = nearTo(b, all, margin);
		if (start == null) out.set(0F, 0F, 0F, 1F);
		else out.set(start.sx, start.sy, start.sz, start.scale);
		out.tucked = false;
		out.worst = worst(b, out, o, margin);
		if (out.worst <= 0F || b.n == 0) return;
		float scale = out.scale;
		// a free end that starts in view is kept in view; one that starts off screen (the lowered shield's body is mostly below the lens) is not dragged on
		int free0 = freeSphere(b);
		boolean clamp = onScreen(b.x[free0], b.y[free0], b.z[free0], 0F);
		for (int round = 0; round < 6; round++) {
			for (int pass = 0; pass < 8; pass++) {
				if (!push(b, o, margin, out, scale, clamp)) break;
			}
			out.scale = scale;
			out.worst = worst(b, out, o, margin);
			if (out.worst <= 0F) return;
			if (scale <= b.minScale + 1e-4F) break;
			scale = Math.max(b.minScale, scale * 0.85F);
			out.scale = scale;
		}
		// last resort: the free part pulled back toward its edge, as small as it may be, so something of it still shows and it is never hidden
		int free = freeSphere(b);
		float k = b.minScale;
		out.scale = k;
		out.sx = (b.rootX - b.x[free]) * 0.65F;
		out.sy = (b.rootY - b.y[free]) * 0.65F;
		out.sz = (b.rootZ - b.z[free]) * 0.65F;
		clampShift(out);
		out.tucked = true;
		out.worst = worst(b, out, o, margin);
	}

	/** One pass of pushing: moves the shift by what every overlapping sphere asks for; false when nothing overlaps (or nothing more can be asked). */
	private static boolean push(Body b, Obstacles o, float margin, Pose p, float scale, boolean clamp) {
		p.scale = scale;
		float ax = 0F, ay = 0F, az = 0F;
		boolean any = false;
		for (int i = 0; i < b.n; i++) {
			if (b.w[i] < 0.2F) continue; // the root's end cannot be pushed by shifting the free end: shrinking deals with it
			float cx = px(b, p, i), cy = py(b, p, i), cz = pz(b, p, i), cr = b.radius(i, scale);
			for (int j = 0; j < o.n; j++) {
				float dx = cx - o.x[j], dy = cy - o.y[j], dz = cz - o.z[j];
				float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
				float pen = cr + o.r[j] + margin - d;
				if (pen <= 0F || !seen(cx, cy, cz, o.x[j], o.y[j], o.z[j])) continue;
				any = true;
				if (d < 1e-3F) {
					dx = 0F;
					dy = 1F;
					dz = 0F;
					d = 1F;
				}
				// the shift that moves this sphere out: its weight takes a share of it
				float k = pen / b.w[i] / d;
				ax += dx * k;
				ay += dy * k;
				az += dz * k;
			}
		}
		if (!any) return false;
		float len = (float) Math.sqrt(ax * ax + ay * ay + az * az);
		if (len > MAX_SHIFT) {
			ax *= MAX_SHIFT / len;
			ay *= MAX_SHIFT / len;
			az *= MAX_SHIFT / len;
		}
		p.sx += ax;
		p.sy += ay;
		p.sz += az;
		clampShift(p);
		// keep the free end on the screen
		int free = freeSphere(b);
		float fx = px(b, p, free), fy = py(b, p, free), fz = pz(b, p, free);
		float depth = Math.max(-fz, 4F), inset = 1F + 0.5F * b.r[free] * scale;
		float lx = LENS_X * depth - inset, ly = LENS_Y * depth - inset;
		if (clamp && Math.abs(fy) > ly) p.sy -= fy - Math.signum(fy) * ly;
		if (clamp && Math.abs(fx) > lx) p.sx -= fx - Math.signum(fx) * lx;
		clampShift(p);
		return true;
	}

	private static void clampShift(Pose p) {
		float len = p.length();
		if (len > MAX_SHIFT) {
			float k = MAX_SHIFT / len;
			p.sx *= k;
			p.sy *= k;
			p.sz *= k;
		}
	}

	private static int freeSphere(Body b) {
		int best = 0;
		for (int i = 1; i < b.n; i++) {
			if (b.w[i] > b.w[best]) best = i;
		}
		return best;
	}

	// ------------------------------------------------------------------ no pops

	/** The eased placement of the four accessories (lantern, shield, spyglass, flint), as the draw was last given. */
	private static final Pose[] SHOWN = {new Pose(), new Pose(), new Pose(), new Pose()};
	private static final float[] LAST = new float[4];
	private static final boolean[] SEEN = new boolean[4];

	/**
	 * Eases {@code target} into the placement shown for accessory {@code index} at the clock {@code time}: a bigger push applies at once (so
	 * nothing is ever drawn through what it should have made room for), a smaller one relaxes back over {@link #RELAX_TICKS}. Writes the result
	 * into {@code target}.
	 */
	public static void ease(int index, Pose target, float time) {
		Pose s = SHOWN[index];
		float dt = SEEN[index] ? Math.clamp(time - LAST[index], 0F, 20F) : 20F;
		LAST[index] = time;
		SEEN[index] = true;
		if (time < LAST[index] - 36000F) dt = 20F; // the clock wrapped
		float pushed = target.length(), shown = s.length();
		boolean more = pushed >= shown - 1e-3F && target.scale <= s.scale + 1e-4F;
		if (more) {
			s.copy(target);
		} else {
			float k = 1F - (float) Math.exp(-dt / RELAX_TICKS);
			// the caller checks the eased placement against the obstacles as they stand now and falls back on the target if it would touch
			s.set(s.sx + (target.sx - s.sx) * k, s.sy + (target.sy - s.sy) * k, s.sz + (target.sz - s.sz) * k, s.scale + (target.scale - s.scale) * k);
			s.worst = target.worst;
			s.tucked = target.tucked;
		}
		target.copy(s);
	}

	/** The placement shown for accessory {@code index} last frame, or null if it has not been shown since the last {@link #forget}. */
	public static Pose shownPose(int index) {
		return SEEN[index] ? SHOWN[index] : null;
	}

	/** Makes {@code p} what accessory {@code index} shows now (the caller found the eased placement would touch what it must clear). */
	public static void show(int index, Pose p) {
		SHOWN[index].copy(p);
	}

	/** Forgets what was shown (a new world, the hand pass not running). */
	public static void forget() {
		for (int i = 0; i < SHOWN.length; i++) {
			SHOWN[i].set(0F, 0F, 0F, 1F);
			SEEN[i] = false;
		}
	}
}
