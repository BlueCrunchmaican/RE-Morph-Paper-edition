package dev.morphreborn.paper.arm.rules;

import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fell's plan (spec 3.9): which logs of a tree the axe arm brings down, and in what order, and the dice for the axe's
 * extra log. Only real trees fall. A log building, a pile of logs or a wall of them has no canopy, so the plan refuses
 * it: at least {@code minLeaves} natural leaf blocks (on a huge fungus, wart blocks or shroomlights) must touch the logs
 * of the tree, which blocks a player puts down never do (a placed leaf is persistent, and the grid says so).
 * <p>
 * The walk is Vein Rip's: breadth first over all 26 neighbours, within the log's family (oak with stripped oak and oak
 * wood, never birch next to it), inside a box of {@code radius} blocks sideways and {@code height} blocks up and down
 * from the log looked at. The canopy is counted over the whole connected tree (up to {@link #SCAN_CAP} logs), not just
 * the logs the cap keeps, so a tall trunk that only grows leaves near its top still counts as a tree when the cap holds
 * back its upper part. The logs that fall are the nearest {@code maxLogs} of the walk, and they fall from the top down.
 * <p>
 * Pure: it asks a {@link TreeGrid}, so it is unit tested over a map.
 */
public final class FellPlanner {
	/** The most logs one walk looks at, so a forest of one family can't make F expensive. */
	public static final int SCAN_CAP = 2048;
	/** A log is cracked this many ticks before it breaks. */
	public static final int CRACK_LEAD = 1;
	/** The first log breaks this many ticks after F (the crack shows the tick before). */
	public static final int FIRST_BREAK_TICK = 2;

	private FellPlanner() {}

	/** What the walk saw. {@code group} on the grid is the log's family ("oak"), null for anything that is not a log. */
	public interface TreeGrid extends GridView {
		/** A block that counts as the tree's crown: a natural leaf block, or a wart block or shroomlight. */
		boolean canopy(int x, int y, int z);
	}

	public enum Outcome {
		/** There are logs to bring down. */
		OK,
		/** The block looked at is no log. */
		NOT_A_LOG,
		/** Logs, but not a tree: too little canopy touches them. */
		NOT_A_TREE,
		/** A tree, but the arm can't break its logs. */
		UNBREAKABLE
	}

	/**
	 * @param logs the logs to bring down, in the order they fall (the top first)
	 * @param canopy the crown blocks that touch the tree's logs
	 * @param scanned the logs the walk found, before the cap
	 */
	public record Plan(Outcome outcome, List<Pos> logs, int canopy, int scanned) {
		public Plan {
			logs = List.copyOf(logs);
		}

		public boolean ok() {
			return outcome == Outcome.OK;
		}
	}

	/** Top first, then by x and z, so the same tree always falls the same way whatever order the world reports blocks in. */
	private static final Comparator<Pos> FALL_ORDER = Comparator.comparingInt((Pos p) -> -p.y()).thenComparingInt(Pos::x).thenComparingInt(Pos::z);

	/** The six faces: a crown block counts when it shares a face with a log, as leaves hug a trunk. */
	private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

	/**
	 * @param maxLogs the most logs that fall
	 * @param radius how far from the start the walk goes sideways
	 * @param height how far from the start it goes up and down
	 * @param minLeaves the canopy a tree needs
	 */
	public static Plan plan(TreeGrid grid, Pos start, int maxLogs, int radius, int height, int minLeaves) {
		String family = grid.group(start.x(), start.y(), start.z());
		if (family == null) return new Plan(Outcome.NOT_A_LOG, List.of(), 0, 0);
		List<Pos> walk = new ArrayList<>();
		Set<Pos> seen = new HashSet<>();
		seen.add(start);
		List<Pos> step = List.of(start);
		while (!step.isEmpty() && walk.size() < SCAN_CAP) {
			List<Pos> next = new ArrayList<>();
			for (Pos p : step) {
				if (walk.size() >= SCAN_CAP) break;
				if (!grid.breakable(p.x(), p.y(), p.z())) continue;
				walk.add(p);
				for (int dx = -1; dx <= 1; dx++) {
					for (int dy = -1; dy <= 1; dy++) {
						for (int dz = -1; dz <= 1; dz++) {
							if (dx == 0 && dy == 0 && dz == 0) continue;
							Pos n = new Pos(p.x() + dx, p.y() + dy, p.z() + dz);
							if (Math.abs(n.x() - start.x()) > radius || Math.abs(n.z() - start.z()) > radius || Math.abs(n.y() - start.y()) > height) continue;
							if (!seen.add(n)) continue;
							if (family.equals(grid.group(n.x(), n.y(), n.z()))) next.add(n);
						}
					}
				}
			}
			next.sort(FALL_ORDER);
			step = next;
		}
		if (walk.isEmpty() || maxLogs <= 0) return new Plan(Outcome.UNBREAKABLE, List.of(), 0, walk.size());
		// the crown, over every log found: distinct blocks that share a face with one
		Set<Pos> crown = new HashSet<>();
		for (Pos p : walk) {
			for (int[] f : FACES) {
				Pos n = new Pos(p.x() + f[0], p.y() + f[1], p.z() + f[2]);
				if (!crown.contains(n) && grid.canopy(n.x(), n.y(), n.z())) crown.add(n);
			}
		}
		if (crown.size() < minLeaves) return new Plan(Outcome.NOT_A_TREE, List.of(), crown.size(), walk.size());
		List<Pos> fall = new ArrayList<>(walk.subList(0, Math.min(maxLogs, walk.size())));
		fall.sort(FALL_ORDER);
		return new Plan(Outcome.OK, fall, crown.size(), walk.size());
	}

	/** The tick after F that log {@code index} of a plan breaks: {@code perTick} to a tick, from tick 2. */
	public static int breakTick(int index, int perTick) {
		return FIRST_BREAK_TICK + index / Math.max(1, perTick);
	}

	/**
	 * The axe's extra log: whether the log broken at this block, on this server tick, gives one more. The dice are
	 * seeded from the block and the tick (spec 3.9), so the same break rolls the same way; each seed is mixed through
	 * a 64-bit finaliser, so neighbouring blocks and ticks are as independent as a good generator's draws.
	 */
	public static boolean extraLog(double chance, int x, int y, int z, long tick) {
		if (!(chance > 0)) return false;
		if (chance >= 1) return true;
		return roll(x, y, z, tick) < chance;
	}

	/** A number in [0, 1) from a block and a tick. */
	public static double roll(int x, int y, int z, long tick) {
		long h = x * 0x9E3779B97F4A7C15L;
		h = mix(h ^ (y * 0xC2B2AE3D27D4EB4FL));
		h = mix(h ^ (z * 0x165667B19E3779F9L));
		h = mix(h ^ tick);
		return (h >>> 11) * 0x1.0p-53;
	}

	private static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}
}
