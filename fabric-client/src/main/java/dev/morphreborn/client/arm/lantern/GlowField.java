package dev.morphreborn.client.arm.lantern;

import java.util.Arrays;

/**
 * The coloured light of a lit lantern (2026-11-16: "coloured lantern light... if it's blue your lighting will
 * be blue and if it's red, red", brightest at the player and tapering off, no dead spots). Vanilla light has no colour,
 * so the server's light blocks stay what they are (white, the light the game itself lights things with) and this adds
 * the colour on top, client side, for every modded viewer: a soft additive wash on the faces of the blocks the lantern
 * lights, in its owner's arm colour, fading smoothly from the lantern out to a radius.
 * <p>
 * This class is the geometry only, pure and with no Minecraft types so every rule is tested: a flood fill from the
 * lantern through the open blocks round it (so the wash goes where light would go, round corners and not through walls)
 * that collects the solid faces it reaches, and the fall-off of the wash over each face's corners. The wash is
 * per corner, not per face, so it is continuous over a floor, up a wall and round an edge: there are no tiles and no
 * dark gaps between them. {@code LanternGlowFx} draws it.
 * <p>
 * A field is an immutable snapshot of the faces around one cell; what moves every frame is the light's own position,
 * which only the corners' strengths depend on. Render thread only (the scratch arrays are shared).
 */
public final class GlowField {
	/** What a block is to the wash: air and the like it flows through; a solid block takes a face of it; the rest stops it and takes nothing. */
	public static final int OPEN = 0, SOLID = 1, BLOCKED = 2;
	/** The farthest a wash reaches (blocks), the most open blocks one flood visits and the most faces one field keeps. */
	public static final int MAX_RADIUS = 20, MAX_CELLS = 30_000, MAX_FACES = 4096;
	/** A face stands at least this far off its block, toward the light, so it never fights the block's own face for depth. */
	public static final float LIFT = 0.006F;
	/**
	 * The lift grows with the square of the distance from the camera, {@code LIFT_PER_SQ} a block squared: the depth buffer's
	 * steps grow about as the square of the distance, so a fixed lift that is plenty at 10 blocks is lost in them at 100 and
	 * the wash flickers against the block it lies on. Capped at {@link #LIFT_MAX}, far under what the eye can place.
	 */
	public static final float LIFT_PER_SQ = 2.5e-6F, LIFT_MAX = 0.06F;
	/**
	 * A face the flood reached by a longer way than straight (round a corner, through a door) is as dim as if it were this
	 * much further for each extra block of the way.
	 */
	static final double DETOUR = 0.8;
	/** The wash's fall-off exponent: {@code (1 - d/R)^1.5}, strong near the lantern, gone gently at the radius (worked as {@code t * sqrt(t)}, which is it exactly and costs no {@code pow}). */
	static final double FALLOFF = 1.5;
	/** A face turned away from the light still gets this share of the wash (light flows round to it); facing it gets all. */
	static final double AMBIENT = 0.3;

	/** The six directions a flood steps in: x-, x+, y-, y+, z-, z+. */
	private static final int[][] DIRS = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

	/** The blocks the flood looks at: the game's world answers from the loaded chunks, tests from a map. */
	public interface Grid {
		/** {@link #OPEN}, {@link #SOLID} or {@link #BLOCKED}. */
		int kind(int x, int y, int z);
	}

	/** The cell the flood started in, and the radius it covered. */
	public final int cx, cy, cz, radius;
	private final int count;
	/** Per face: the open cell it faces ({@code 3 * i}), the direction from that cell to the solid block, and the extra blocks of the way there. */
	private final int[] cells;
	private final byte[] dirs;
	private final float[] extra;
	/** The flood stopped at {@link #MAX_CELLS} or the faces at {@link #MAX_FACES}: some of the far part is missing. */
	public final boolean truncated;

	private GlowField(int cx, int cy, int cz, int radius, int count, int[] cells, byte[] dirs, float[] extra, boolean truncated) {
		this.cx = cx;
		this.cy = cy;
		this.cz = cz;
		this.radius = radius;
		this.count = count;
		this.cells = cells;
		this.dirs = dirs;
		this.extra = extra;
		this.truncated = truncated;
	}

	/** A field with nothing in it (the lantern's cell is solid, or nothing is round it). */
	public static GlowField empty(int cx, int cy, int cz, int radius) {
		return new GlowField(cx, cy, cz, radius, 0, new int[0], new byte[0], new float[0], false);
	}

	public int count() {
		return count;
	}

	// ------------------------------------------------------------------ the flood

	private static int[] queue = new int[0];
	private static short[] seen = new short[0];
	private static final int[] FACE_CELLS = new int[3 * MAX_FACES];
	private static final byte[] FACE_DIRS = new byte[MAX_FACES];
	private static final float[] FACE_EXTRA = new float[MAX_FACES];

	/**
	 * Floods from the open cell ({@code cx, cy, cz}) outward through open blocks, one step at a time, up to {@code radius}
	 * blocks away (as the crow flies), and keeps every solid face it finds on an open block it reached, nearest first.
	 * Nearest-first matters when a cap stops the flood: the near faces, which are the bright ones, are always there. A start
	 * that is not open gives an empty field.
	 */
	public static GlowField build(Grid grid, int cx, int cy, int cz, int radius) {
		int r = Math.clamp(radius, 1, MAX_RADIUS);
		if (grid.kind(cx, cy, cz) != OPEN) return empty(cx, cy, cz, r);
		int reach = r + 2; // faces whose cell is a little past the radius still fade in from nothing
		int side = 2 * reach + 1;
		int boxSize = side * side * side;
		if (seen.length < boxSize) {
			seen = new short[boxSize];
			queue = new int[boxSize];
		} else {
			Arrays.fill(seen, 0, boxSize, (short) 0);
		}
		long limit = (long) reach * reach;
		int head = 0, tail = 0, faces = 0, visited = 0;
		boolean truncated = false;
		int start = (reach * side + reach) * side + reach;
		seen[start] = 1; // steps taken + 1, so 0 is unseen
		queue[tail++] = start;
		while (head < tail) {
			int at = queue[head++];
			int lz = at % side, ly = (at / side) % side, lx = at / (side * side);
			int dx = lx - reach, dy = ly - reach, dz = lz - reach;
			int steps = seen[at] - 1;
			// how many steps beyond the straight way (the sum of |dx|, |dy|, |dz|) this took: round a corner, through a door
			int bend = steps - (Math.abs(dx) + Math.abs(dy) + Math.abs(dz));
			if (++visited > MAX_CELLS) {
				truncated = true;
				break;
			}
			for (int d = 0; d < 6; d++) {
				int nx = dx + DIRS[d][0], ny = dy + DIRS[d][1], nz = dz + DIRS[d][2];
				if ((long) nx * nx + (long) ny * ny + (long) nz * nz > limit) continue;
				int k = grid.kind(cx + nx, cy + ny, cz + nz);
				if (k == SOLID) {
					if (faces < MAX_FACES) {
						FACE_CELLS[3 * faces] = cx + dx;
						FACE_CELLS[3 * faces + 1] = cy + dy;
						FACE_CELLS[3 * faces + 2] = cz + dz;
						FACE_DIRS[faces] = (byte) d;
						FACE_EXTRA[faces] = bend;
						faces++;
					} else {
						truncated = true;
					}
				} else if (k == OPEN) {
					int ni = ((nx + reach) * side + (ny + reach)) * side + (nz + reach);
					if (seen[ni] == 0) {
						seen[ni] = (short) (steps + 2);
						queue[tail++] = ni;
					}
				}
			}
		}
		return new GlowField(cx, cy, cz, r, faces, Arrays.copyOf(FACE_CELLS, 3 * faces), Arrays.copyOf(FACE_DIRS, faces), Arrays.copyOf(FACE_EXTRA, faces), truncated);
	}

	// ------------------------------------------------------------------ the faces

	/** Face {@code i}'s direction index (0..5, from its open cell toward the solid block: x-, x+, y-, y+, z-, z+). */
	public int dir(int i) {
		return dirs[i];
	}

	/** The block coordinates of the open cell face {@code i} looks into. */
	public int cellX(int i) {
		return cells[3 * i];
	}

	public int cellY(int i) {
		return cells[3 * i + 1];
	}

	public int cellZ(int i) {
		return cells[3 * i + 2];
	}

	/** Extra blocks of the way to face {@code i} beyond the straight way (0 in the open). */
	public float detour(int i) {
		return extra[i];
	}

	/** The unit normal of a face found in direction {@code dir}, pointing out of the solid block into the open cell: minus the direction. */
	public static void normal(int dir, double[] out) {
		out[0] = -DIRS[dir][0];
		out[1] = -DIRS[dir][1];
		out[2] = -DIRS[dir][2];
	}

	/**
	 * The four corners of face {@code i} into {@code out[0..11]} (x, y, z each), wound counter-clockwise seen from the
	 * open side so the face is visible from the cell it looks into and culled from behind, lifted {@link #LIFT} off the
	 * solid block. They are relative to the field's own cell ({@link #cx}, {@link #cy}, {@link #cz}), so they stay exact
	 * as floats however far from the world's origin the lantern is; {@link #wash} wants the light in the same terms.
	 */
	public void corners(int i, float[] out) {
		corners(i, out, LIFT);
	}

	/** {@link #corners(int, float[])} with the face lifted {@code lift} off its block instead of {@link #LIFT} (see {@link #liftAt}). */
	public void corners(int i, float[] out, float lift) {
		corners(cells[3 * i] - cx, cells[3 * i + 1] - cy, cells[3 * i + 2] - cz, dirs[i], out, lift);
	}

	/** {@link #corners(int, float[])} for a face given directly: the open cell and the direction to the solid block. */
	public static void corners(int x, int y, int z, int dir, float[] out) {
		corners(x, y, z, dir, out, LIFT);
	}

	/** The lift for a face {@code distSq} (blocks squared) from the camera: {@link #LIFT} near, growing with the depth buffer's steps, up to {@link #LIFT_MAX}. */
	public static float liftAt(double distSq) {
		return (float) Math.min(LIFT_MAX, LIFT + LIFT_PER_SQ * distSq);
	}

	/** The corners with an explicit lift. Allocates nothing: it is called for every face every frame. */
	public static void corners(int x, int y, int z, int dir, float[] out, float lift) {
		int axis = dir >> 1;
		int sign = DIRS[dir][axis]; // +1 or -1: which side of the open cell the solid block is on
		int u = (axis + 1) % 3, v = (axis + 2) % 3;
		// u x v = +axis for (axis+1, axis+2); the normal is -sign along the axis, so a normal of +axis (sign -1) keeps the order
		if (sign > 0) {
			int t = u;
			u = v;
			v = t;
		}
		float plane = at(x, y, z, axis) + (sign > 0 ? 1F : 0F) - sign * lift;
		int cu = at(x, y, z, u), cv = at(x, y, z, v);
		for (int c = 0; c < 4; c++) {
			// (0,0) (1,0) (1,1) (0,1)
			int du = (c == 1 || c == 2) ? 1 : 0, dv = (c >= 2) ? 1 : 0;
			out[3 * c + axis] = plane;
			out[3 * c + u] = cu + du;
			out[3 * c + v] = cv + dv;
		}
	}

	/** Component {@code i} (0 x, 1 y, 2 z) of a cell, so the corners need no array to index it. */
	private static int at(int x, int y, int z, int i) {
		return i == 0 ? x : i == 1 ? y : z;
	}

	// ------------------------------------------------------------------ the wash

	/**
	 * How strong the wash is {@code dist} blocks from the lantern ({@code detour} extra blocks of the way counted in), 0 to
	 * 1: {@code (1 - d/R)^1.5}, 1 at the lantern and 0 at the radius. Never rises with distance.
	 */
	public static double taper(double dist, double detour, double radius) {
		double t = 1.0 - (dist + DETOUR * detour) / radius;
		return t <= 0 ? 0 : t * Math.sqrt(t); // t^FALLOFF with FALLOFF 1.5: drawn per corner per frame, so no pow
	}

	/**
	 * The wash on corner {@code c} (0..3) of face {@code i}, 0 to 1, with the light at ({@code lx, ly, lz}): the taper at
	 * the corner's distance, times a share for how squarely the face looks at the light ({@link #AMBIENT} for one looking
	 * away). {@code corner} holds the face's corners as {@link #corners} wrote them, and the light is relative to the field's cell like them.
	 */
	public double wash(int i, float[] corner, int c, double lx, double ly, double lz) {
		double dx = lx - corner[3 * c], dy = ly - corner[3 * c + 1], dz = lz - corner[3 * c + 2];
		double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
		double s = taper(d, extra[i], radius);
		if (s <= 0) return 0;
		if (d < 1e-4) return s;
		int dir = dirs[i];
		int axis = dir >> 1;
		double along = (axis == 0 ? dx : axis == 1 ? dy : dz) * -DIRS[dir][axis]; // toward the light, out of the face
		double facing = Math.max(0.0, along / d);
		return s * (AMBIENT + (1.0 - AMBIENT) * facing);
	}

	// ------------------------------------------------------------------ fading in and out

	/**
	 * The wash's strength as it fades in and out, a tick at a time: up while the lantern is lit, down after. Takes
	 * {@code ticks} ticks to go all the way.
	 */
	public static float fade(float now, boolean lit, int ticks) {
		float step = 1F / Math.max(1, ticks);
		return lit ? Math.min(1F, now + step) : Math.max(0F, now - step);
	}
}
