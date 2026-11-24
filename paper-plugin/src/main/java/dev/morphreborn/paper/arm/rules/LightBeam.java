package dev.morphreborn.paper.arm.rules;

import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Where the lantern's light goes (spec 3.10), as a pure function over a {@link Grid} of blocks so every rule is tested
 * without a server: the light round its holder ({@link #halo}), a few fake light blocks the server shows to the players
 * around, never placed in the world.
 * <p>
 * A cell takes light only where it is air or cave air (a plain light block) or a water source (a light block that is
 * waterlogged, so the water stays water). Never lava, never anything solid, never flowing water, never a waterlogged
 * block.
 * <p>
 * Until 8.0.0 the light was a beam where the player looked (the lure, mid cells and an end cell, with hysteresis) and
 * Sneak+F threw a Flare; I replaced both with this light round the player and a brightness to choose.
 */
public final class LightBeam {
	/**
	 * Past 14 blocks the light is a taper: cells stand on a world-aligned lattice this many blocks apart, each one at a
	 * level that falls with its distance from the holder, so the light fades smoothly out to the reach instead of
	 * standing at full strength in patches with dark between (2026-11-16: "smooth tapering light without
	 * dead spots"). Light spreads a level a block along x, y and z, so cells {@code TAPER_GRID} apart leave the light
	 * between them at most about {@code TAPER_GRID} levels under a cell's own; 5 keeps that ripple to about 5 levels
	 * out to 30 blocks for about 150 cells (the old grid, 8 apart, left 8 and had every outer cell at full strength).
	 */
	public static final int TAPER_GRID = 5;
	/**
	 * A cell at distance {@code d} stands at {@code round(15 * (1 - d / (reach + TAPER_SPREAD)) + TAPER_LIFT)}. The spread
	 * and the lift are fitted so the light's edge is {@code reach} out on average (measured over lattice offsets and
	 * bearings, see LightBeamTest) and the mean level is on the straight line from 15 at the holder to nothing at the
	 * reach: without them the last cells' own fall-off leaves the edge short, and the ripple's dips the mean low.
	 */
	public static final int TAPER_SPREAD = 6;
	public static final double TAPER_LIFT = 1.5;
	/** Cells nearer the holder than this many blocks are left to the cell at the eyes (its own level is 15 there). */
	private static final double TAPER_NEAR = 3.0;
	/** The most taper cells ever made (a reach of 64 would make 600): the nearest ones win. */
	public static final int TAPER_MAX_CELLS = 200;
	/**
	 * A lattice column with no air at its own spot looks this far either way for one that has (a one-block tunnel the
	 * lattice would otherwise step over: the "dead spots"). Each column has its own fixed set of blocks to try
	 * ({@link #TAPER_YS} at its own spot, then {@link #NEAR_YS} at each jittered one), so a column's answer depends only on
	 * the blocks round it and never on how many probes the columns before it used. (A shared probe budget, as this once had,
	 * ran out at a different column as the holder walked, and the taper's far edge in rock flickered with it.) The cost of a
	 * halo in solid rock, where every column fails, is bounded instead by how many columns are looked at,
	 * {@link #TAPER_MAX_COLUMNS}, nearest first (which depends on where the holder stands and nothing else).
	 */
	private static final int TAPER_JITTER = 2;
	public static final int TAPER_MAX_COLUMNS = 220;
	/** Heights tried for a lattice cell, nearest the eyes first; the jittered columns try only the first {@link #NEAR_YS}. */
	private static final int[] TAPER_YS = {0, -1, 1, -2, 2, -3, 3, -4, 4, -5, 5};
	private static final int NEAR_YS = 7;
	/** The columns tried at each jitter: none, then one block either way along x or z, then two. */
	private static final int[][][] OFFSETS = {
			{{0, 0}},
			{{1, 0}, {-1, 0}, {0, 1}, {0, -1}},
			{{2, 0}, {-2, 0}, {0, 2}, {0, -2}}};

	private LightBeam() {}

	/** What a block is to the light. */
	public enum Kind {
		/** Air or cave air: takes a plain light block. */
		AIR,
		/** A water source: takes a waterlogged light block. */
		WATER,
		/** Anything else, an unloaded block and the outside of the world included: takes nothing. */
		BLOCKED
	}

	/** The blocks the light looks at: the server side answers from the world, tests from a map. */
	public interface Grid {
		Kind kind(int x, int y, int z);
	}

	/** A cell that gets light: the level, and whether it is the waterlogged kind. */
	public record Light(Pos at, int level, boolean waterlogged) {}

	/**
	 * The lantern's light round its holder (2026-11-15: light the area round the player all at once, like an
	 * invisible glowstone, rather than a beam where they look, as far as 20 or 30 blocks): one light cell where the eyes
	 * are (or failing that the body's cell, then the feet's) at {@code reach + 1}, at most 15, which lights about
	 * {@code reach} blocks round it along the axes (less on the diagonals: light fades block by block along x, y and z)
	 * and fades by a level a block, smoothly.
	 * <p>
	 * Past 14 blocks one cell cannot reach, so the light tapers (2026-11-16: brightest at the player, fading
	 * smoothly with distance, no dead spots): more cells on a world-aligned lattice {@link #TAPER_GRID} apart, each
	 * a few blocks either side of the eyes' height where the air is, at a level that falls with its distance from the
	 * holder ({@link #taperLevel}). A lattice column that is solid at that height tries the columns a block or two either
	 * way, so a narrow tunnel is lit all along its length. The lattice is fixed to the world, so walking changes only
	 * the levels of the cells whose distance changed and the cells at the edge. The same blocks and the same holder
	 * give the same cells, in the same order (nothing here is budgeted across columns).
	 */
	public static List<Light> halo(Grid grid, int x, int eyeY, int feetY, int z, int reach) {
		List<Light> out = new ArrayList<>();
		int r = Math.max(0, reach);
		Light centre = cellNear(grid, x, z, new int[] {eyeY, eyeY - 1, feetY}, Math.min(15, r + 1));
		if (centre != null) out.add(centre);
		if (r <= 14) return out;
		int span = r + TAPER_SPREAD + 2, g = TAPER_GRID;
		// the lattice columns that could carry a cell, nearest the holder first (the nearest TAPER_MAX_COLUMNS are looked at)
		List<int[]> columns = new ArrayList<>();
		for (int gx = Math.floorDiv(x - span, g) * g; gx <= x + span; gx += g) {
			for (int gz = Math.floorDiv(z - span, g) * g; gz <= z + span; gz += g) {
				long dx = gx - x, dz = gz - z;
				long d2 = dx * dx + dz * dz;
				if (d2 > (long) span * span) continue;
				columns.add(new int[] {gx, gz, (int) d2});
			}
		}
		columns.sort((a, b) -> a[2] != b[2] ? Integer.compare(a[2], b[2]) : a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
		Set<Pos> seen = new HashSet<>();
		int looked = 0;
		for (int[] col : columns) {
			if (out.size() > TAPER_MAX_CELLS || looked++ >= TAPER_MAX_COLUMNS) break;
			Light l = taperCell(grid, col[0], col[1], x, eyeY, z, r);
			if (l != null && seen.add(l.at())) out.add(l);
		}
		return out;
	}

	/** The cell for the lattice column (gx, gz): its own if it has air near the eyes' height, else a neighbour's; null if none, or none wanted there. */
	private static @Nullable Light taperCell(Grid grid, int gx, int gz, int x, int eyeY, int z, int reach) {
		for (int j = 0; j <= TAPER_JITTER; j++) {
			for (int[] o : OFFSETS[j]) {
				int cx = gx + o[0], cz = gz + o[1];
				int tries = j == 0 ? TAPER_YS.length : NEAR_YS;
				for (int i = 0; i < tries; i++) {
					int y = eyeY + TAPER_YS[i];
					Kind k = grid.kind(cx, y, cz);
					if (k == Kind.BLOCKED) continue;
					long dx = cx - x, dy = y - eyeY, dz = cz - z;
					double d = Math.sqrt((double) (dx * dx + dy * dy + dz * dz));
					if (d < TAPER_NEAR) return null;
					int level = taperLevel(d, reach);
					return level < 1 ? null : new Light(new Pos(cx, y, cz), level, k == Kind.WATER);
				}
			}
		}
		return null;
	}

	/**
	 * The level of a taper cell {@code d} blocks from the holder, for a {@code reach}: 15 at the holder, falling in a
	 * straight line to nothing a little past the reach (see {@link #TAPER_SPREAD}); under 1 means no cell.
	 */
	public static int taperLevel(double d, int reach) {
		double v = 15.0 * (1.0 - d / (reach + TAPER_SPREAD)) + TAPER_LIFT;
		return (int) Math.clamp(Math.round(v), 0L, 15L);
	}

	/**
	 * Steadies the light as its holder walks: a cell the viewers already show whose wanted level is within one of what
	 * they have keeps what they have, so a level that wavers across a rounding edge as the holder moves is not sent
	 * (each send makes every client relight and rebuild the chunks round it). A cell that is new, gone, changed by more,
	 * changed in kind (water to air) or at full strength is as wanted.
	 */
	public static Map<Long, Light> steady(Map<Long, Light> shown, Map<Long, Light> wanted) {
		Map<Long, Light> out = new LinkedHashMap<>(wanted.size() * 2);
		for (Map.Entry<Long, Light> e : wanted.entrySet()) {
			Light have = shown.get(e.getKey());
			Light want = e.getValue();
			boolean keep = have != null && have.waterlogged() == want.waterlogged() && Math.abs(have.level() - want.level()) <= 1
					&& want.level() < 15 && have.level() < 15;
			out.put(e.getKey(), keep ? have : want);
		}
		return out;
	}

	/** A light cell of {@code level} in the first of {@code ys} at (x, z) that can take one (air, or a water source), or null. */
	private static @Nullable Light cellNear(Grid grid, int x, int z, int[] ys, int level) {
		int lv = Math.clamp(level, 0, 15);
		for (int y : ys) {
			Kind k = grid.kind(x, y, z);
			if (k == Kind.AIR) return new Light(new Pos(x, y, z), lv, false);
			if (k == Kind.WATER) return new Light(new Pos(x, y, z), lv, true);
		}
		return null;
	}

	/**
	 * A block's kind from its id (with or without {@code minecraft:}) and, for water, its level (0 is a source, 1 to 7
	 * flowing, 8 and up falling). This is the whole rule for what takes light; the server's grid calls it.
	 */
	public static Kind classify(@Nullable String blockId, int waterLevel) {
		if (blockId == null) return Kind.BLOCKED;
		String id = blockId.startsWith("minecraft:") ? blockId.substring("minecraft:".length()) : blockId;
		return switch (id) {
			case "air", "cave_air" -> Kind.AIR;
			case "water" -> waterLevel == 0 ? Kind.WATER : Kind.BLOCKED;
			default -> Kind.BLOCKED;
		};
	}
}
