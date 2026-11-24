package dev.morphreborn.paper.arm.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Vein Rip's plan: which ores of a vein the pickaxe arm tears out, and in what order. A breadth-first walk from the ore
 * looked at, over all 26 neighbours (diagonals too, as real veins grow), staying in the ore's group (deepslate diamond
 * with diamond, never coal next to it) and inside a box of {@code maxOffset} blocks each way from the start.
 * <p>
 * The order is fixed: by distance in steps from the start, then higher blocks first, then by x, then by z. So the same
 * vein always gives the same plan whatever order the world reports blocks in, the tendril network the clients draw
 * (rebuilt from this list with {@link #parents}) matches on every screen, and a capped plan keeps the nearest ores.
 * <p>
 * A block the arm can't break (wrong tool tier, unbreakable) is skipped, and the walk doesn't go on through it: every
 * ore in the plan touches an earlier one, so every tendril of the network has a block to grow from. Pure, so it is unit
 * tested over a map.
 */
public final class VeinPlanner {
	private VeinPlanner() {}

	/** A block position. {@link #asLong} packs it as the game's BlockPos.asLong does, for S_ARM_FX. */
	public record Pos(int x, int y, int z) {
		private static final int HORIZONTAL_BITS = 26, Y_BITS = 12;
		private static final long HORIZONTAL_MASK = (1L << HORIZONTAL_BITS) - 1, Y_MASK = (1L << Y_BITS) - 1;
		private static final int Z_SHIFT = Y_BITS, X_SHIFT = Y_BITS + HORIZONTAL_BITS;

		public long asLong() {
			return ((x & HORIZONTAL_MASK) << X_SHIFT) | (y & Y_MASK) | ((z & HORIZONTAL_MASK) << Z_SHIFT);
		}

		public static Pos fromLong(long packed) {
			int x = (int) (packed << (64 - X_SHIFT - HORIZONTAL_BITS) >> (64 - HORIZONTAL_BITS));
			int y = (int) (packed << (64 - Y_BITS) >> (64 - Y_BITS));
			int z = (int) (packed << (64 - Z_SHIFT - HORIZONTAL_BITS) >> (64 - HORIZONTAL_BITS));
			return new Pos(x, y, z);
		}

		/** Squared distance between block centres. */
		public int distanceSquared(Pos o) {
			int dx = x - o.x, dy = y - o.y, dz = z - o.z;
			return dx * dx + dy * dy + dz * dz;
		}

		/** One of the 26 blocks around this one (sharing a face, an edge or a corner). */
		public boolean touches(Pos o) {
			return !equals(o) && Math.abs(x - o.x) <= 1 && Math.abs(y - o.y) <= 1 && Math.abs(z - o.z) <= 1;
		}
	}

	/**
	 * How far from the owner's eyes (to the block's centre) an ore may be and still be planned, and how far when its turn comes: a
	 * little more, for the owner walking on in the half second a rip takes. The plan applies the first, so the tendrils drawn are
	 * the ores that will really come out (2026-11-16; before, the plan reached 13 blocks and the break refused what was
	 * past 8, silently).
	 */
	public static final double PLAN_REACH = 8, TEAR_REACH = 9;

	/** The block at x, y, z has its centre within {@code reach} of the eyes at (ex, ey, ez). */
	public static boolean withinReach(double ex, double ey, double ez, int x, int y, int z, double reach) {
		double dx = x + 0.5 - ex, dy = y + 0.5 - ey, dz = z + 0.5 - ez;
		return dx * dx + dy * dy + dz * dz <= reach * reach;
	}

	/** Within one step of the walk: higher first, then x, then z. */
	private static final Comparator<Pos> ORDER = Comparator.comparingInt((Pos p) -> -p.y()).thenComparingInt(Pos::x).thenComparingInt(Pos::z);

	/**
	 * The ores to break, in order, the start first. Empty when the start is in no group or can't be broken.
	 *
	 * @param maxBlocks the most ores in the plan
	 * @param maxOffset how far the plan may reach from the start along each axis
	 * @param visitCap the most blocks of the group the walk looks at, broken or skipped (bounds the work in a huge
	 *            vein of blocks the arm can't break)
	 */
	public static List<Pos> plan(GridView grid, Pos start, int maxBlocks, int maxOffset, int visitCap) {
		if (maxBlocks <= 0 || visitCap <= 0) return List.of();
		String group = grid.group(start.x(), start.y(), start.z());
		if (group == null) return List.of();
		List<Pos> out = new ArrayList<>();
		Set<Pos> seen = new HashSet<>();
		seen.add(start);
		List<Pos> step = List.of(start);
		int visits = 0;
		while (!step.isEmpty()) {
			List<Pos> next = new ArrayList<>();
			for (Pos p : step) {
				if (out.size() >= maxBlocks || visits >= visitCap) return List.copyOf(out);
				visits++;
				if (!grid.breakable(p.x(), p.y(), p.z())) continue;
				out.add(p);
				for (int dx = -1; dx <= 1; dx++) {
					for (int dy = -1; dy <= 1; dy++) {
						for (int dz = -1; dz <= 1; dz++) {
							if (dx == 0 && dy == 0 && dz == 0) continue;
							Pos n = new Pos(p.x() + dx, p.y() + dy, p.z() + dz);
							if (Math.abs(n.x() - start.x()) > maxOffset || Math.abs(n.y() - start.y()) > maxOffset || Math.abs(n.z() - start.z()) > maxOffset) continue;
							if (!seen.add(n)) continue;
							if (group.equals(grid.group(n.x(), n.y(), n.z()))) next.add(n);
						}
					}
				}
			}
			next.sort(ORDER);
			step = next;
		}
		return List.copyOf(out);
	}

	/**
	 * The network's tree: for each block, the index of the block its tendril grows from, -1 for the first (fed from the
	 * pick itself). It is the nearest earlier block among the 26 around it (a shared face before an edge before a
	 * corner), the earliest of equally near ones; -1 when no earlier block touches it. The client rebuilds the same tree
	 * from the same list, so this rule is part of the protocol.
	 */
	public static int[] parents(List<Pos> ordered) {
		int[] out = new int[ordered.size()];
		for (int i = 0; i < out.length; i++) {
			Pos p = ordered.get(i);
			int best = -1, bestDistance = Integer.MAX_VALUE;
			for (int j = 0; j < i; j++) {
				Pos q = ordered.get(j);
				if (!p.touches(q)) continue;
				int d = p.distanceSquared(q);
				if (d < bestDistance) {
					best = j;
					bestDistance = d;
				}
			}
			out[i] = best;
		}
		return out;
	}
}
