package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.LightBeam;
import dev.morphreborn.paper.arm.rules.LightBeam.Kind;
import dev.morphreborn.paper.arm.rules.LightBeam.Light;
import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The lantern's light round its holder over a map of blocks: only air, cave air and water sources take light, never
 * lava or solids; one cell at the eyes up to 14 blocks, a world-aligned grid of cells past that.
 */
class LightBeamTest {
	/** Blocks by position over a default kind. */
	private static final class Blocks implements LightBeam.Grid {
		final Map<Pos, Kind> cells = new HashMap<>();
		final Kind otherwise;

		Blocks(Kind otherwise) {
			this.otherwise = otherwise;
		}

		Blocks set(Kind k, int x, int y, int z) {
			cells.put(new Pos(x, y, z), k);
			return this;
		}

		@Override
		public Kind kind(int x, int y, int z) {
			return cells.getOrDefault(new Pos(x, y, z), otherwise);
		}
	}

	// ------------------------------------------------------------------ what takes light

	@Test
	void onlyAirCaveAirAndWaterSourcesTakeLight() {
		assertEquals(Kind.AIR, LightBeam.classify("minecraft:air", 0));
		assertEquals(Kind.AIR, LightBeam.classify("minecraft:cave_air", 0));
		assertEquals(Kind.AIR, LightBeam.classify("cave_air", 0));
		assertEquals(Kind.WATER, LightBeam.classify("minecraft:water", 0));
		for (int level = 1; level <= 8; level++) {
			assertEquals(Kind.BLOCKED, LightBeam.classify("minecraft:water", level), "flowing or falling water, level " + level);
		}
		for (String id : new String[] {"minecraft:lava", "minecraft:stone", "minecraft:glass", "minecraft:void_air", "minecraft:light", "minecraft:kelp",
				"minecraft:seagrass", "minecraft:bubble_column", "minecraft:oak_slab", "minecraft:water_cauldron", "minecraft:fire", "minecraft:powder_snow"}) {
			assertEquals(Kind.BLOCKED, LightBeam.classify(id, 0), id);
		}
		assertEquals(Kind.BLOCKED, LightBeam.classify("minecraft:lava", 0), "a lava source is not a water source");
		assertEquals(Kind.BLOCKED, LightBeam.classify(null, 0));
	}

	@Test
	void everyCellLitIsAirOrAWaterSourceWhateverTheWorld() {
		Random rnd = new Random(7);
		Kind[] kinds = Kind.values();
		for (int round = 0; round < 300; round++) {
			Blocks g = new Blocks(kinds[rnd.nextInt(kinds.length)]);
			for (int i = 0; i < 400; i++) g.set(kinds[rnd.nextInt(kinds.length)], rnd.nextInt(60) - 30, 60 + rnd.nextInt(10), rnd.nextInt(60) - 30);
			int reach = 1 + rnd.nextInt(30);
			for (Light l : LightBeam.halo(g, 0, 65, 64, 0, reach)) {
				Kind k = g.kind(l.at().x(), l.at().y(), l.at().z());
				assertTrue(k != Kind.BLOCKED, "lit a blocked cell " + l);
				assertEquals(k == Kind.WATER, l.waterlogged(), "waterlogged exactly in water " + l);
				assertTrue(l.level() >= 1 && l.level() <= 15, "a real light level " + l);
			}
		}
	}

	// ------------------------------------------------------------------ the light round the holder

	/** The lantern's light round its holder: one cell at the eyes, else the body's, else the feet's; water waterlogged. */
	@Test
	void haloSitsAtTheEyesOrTheNearestFreeCellBelow() {
		Blocks air = new Blocks(Kind.AIR);
		List<Light> one = LightBeam.halo(air, 3, 65, 64, 7, 11);
		assertEquals(1, one.size(), "up to 14 blocks: one cell");
		assertEquals(new Pos(3, 65, 7), one.get(0).at());
		assertEquals(12, one.get(0).level(), "reach 11: level 12");
		Blocks low = new Blocks(Kind.AIR).set(Kind.BLOCKED, 3, 65, 7);
		assertEquals(new Pos(3, 64, 7), LightBeam.halo(low, 3, 65, 64, 7, 11).get(0).at(), "eyes in a block: the body's cell");
		assertTrue(LightBeam.halo(new Blocks(Kind.WATER), 0, 1, 0, 0, 14).get(0).waterlogged(), "under water: a waterlogged light");
		assertTrue(LightBeam.halo(new Blocks(Kind.BLOCKED), 0, 1, 0, 0, 30).isEmpty(), "buried: no light");
	}

	/**
	 * Past 14 blocks the light tapers (2026-11-16: brightest at the player, fading smoothly, no dead spots): a
	 * lattice of cells whose level falls with distance, so the light's edge (block light above 0, fading a level a block
	 * along x, y and z as the game's does) is about the reach out on average, wherever the holder stands against the
	 * lattice, and nothing inside is dark.
	 */
	@Test
	void haloTapersOutToTheReachWithACellLatticeAndNoDarkPatches() {
		Blocks air = new Blocks(Kind.AIR);
		for (int reach : new int[] {20, 30}) {
			double sum = 0;
			int n = 0, worst = Integer.MAX_VALUE, most = 0;
			for (int ox = 0; ox < LightBeam.TAPER_GRID; ox++) {
				for (int oz = 0; oz < LightBeam.TAPER_GRID; oz += 2) {
					List<Light> cells = LightBeam.halo(air, ox, 70, 69, oz, reach);
					most = Math.max(most, cells.size());
					assertEquals(15, cells.get(0).level(), "brightest at the holder");
					for (Light l : cells.subList(1, cells.size())) {
						assertEquals(0, Math.floorMod(l.at().x(), LightBeam.TAPER_GRID), "on the world lattice");
						assertEquals(0, Math.floorMod(l.at().z(), LightBeam.TAPER_GRID));
						assertEquals(70, l.at().y(), "at the eyes' height in open air");
						double d = Math.hypot(l.at().x() - ox, l.at().z() - oz);
						assertEquals(LightBeam.taperLevel(d, reach), l.level(), "its level is the taper's at " + d);
						assertTrue(l.level() < 15 || d < 8, "full strength only beside the holder: " + l);
					}
					for (int a = 0; a < 360; a += 5) {
						double ca = Math.cos(Math.toRadians(a)), sa = Math.sin(Math.toRadians(a));
						int edge = 0;
						for (int d = 1; d < 60 && lightAt(cells, (int) Math.round(ox + ca * d), 69, (int) Math.round(oz + sa * d)) > 0; d++) edge = d;
						sum += edge;
						n++;
						worst = Math.min(worst, edge);
					}
					// no dark patch inside: every block within 0.8 of the reach is lit, and not far under the straight taper
					for (int dx = -reach; dx <= reach; dx++) {
						for (int dz = -reach; dz <= reach; dz++) {
							double d = Math.hypot(dx, dz);
							if (d > 0.8 * reach) continue;
							int lit = lightAt(cells, ox + dx, 69, oz + dz);
							double line = 15.0 * (1.0 - d / reach) - 1; // a block lower than the eyes
							assertTrue(lit > 0 && lit >= line - 6.5, "reach " + reach + ": dark at " + dx + "," + dz + " (" + lit + " vs " + line + ")");
						}
					}
				}
			}
			double mean = sum / n;
			assertTrue(mean >= reach - 2 && mean <= reach + 2, "reach " + reach + ": the light's edge is " + mean + " out on average");
			assertTrue(worst >= reach - 6, "reach " + reach + ": nowhere much short of it (" + worst + ")");
			assertTrue(most <= LightBeam.TAPER_MAX_CELLS + 1, "a taper of a hundred or so cells, not hundreds: " + most);
		}
		// brightness 4 (20) lights clearly further than 3 (14) along the axes too: the rig found them equal at reach - 8
		List<Light> fourteen = LightBeam.halo(air, 4, 70, 69, 4, 14), twenty = LightBeam.halo(air, 4, 70, 69, 4, 20);
		assertTrue(lightAt(twenty, 4 + 17, 69, 4) > 0 && lightAt(fourteen, 4 + 17, 69, 4) == 0, "17 blocks along +x");
	}

	/**
	 * A column's cell depends only on the blocks round that column: solid rock near the holder (which used to spend a shared
	 * probe budget and starve the far columns, so the far edge flickered as the holder walked) leaves the far cells as they are.
	 */
	@Test
	void aColumnsCellDoesNotDependOnTheRockElsewhere() {
		Blocks open = new Blocks(Kind.AIR);
		Blocks rocky = new Blocks(Kind.AIR);
		// a slab of rock round the holder, thick enough to fail every near column's probes
		for (int dx = -9; dx <= 9; dx++) {
			for (int dz = -9; dz <= 9; dz++) {
				for (int y = 58; y <= 82; y++) {
					if (Math.abs(dx) > 2 || Math.abs(dz) > 2) rocky.set(Kind.BLOCKED, dx, y, dz);
				}
			}
		}
		List<Light> a = LightBeam.halo(open, 0, 70, 69, 0, 30), b = LightBeam.halo(rocky, 0, 70, 69, 0, 30);
		assertEquals(a, LightBeam.halo(open, 0, 70, 69, 0, 30), "the same blocks give the same cells in the same order");
		Map<Pos, Light> bFar = new HashMap<>();
		for (Light l : b) bFar.put(l.at(), l);
		for (Light l : a) {
			if (Math.abs(l.at().x()) <= 12 && Math.abs(l.at().z()) <= 12) continue;
			assertEquals(l, bFar.get(l.at()), "a far cell is the same with rock near the holder: " + l);
		}
	}

	/** The taper's level never rises with distance, is 15 at the holder and nothing past the reach. */
	@Test
	void taperLevelFallsWithDistance() {
		for (int reach : new int[] {15, 20, 30, 64}) {
			assertEquals(15, LightBeam.taperLevel(0, reach));
			int last = 15;
			for (double d = 0; d < reach + 30; d += 0.5) {
				int level = LightBeam.taperLevel(d, reach);
				assertTrue(level <= last && level >= 0 && level <= 15, "reach " + reach + " d " + d + " level " + level);
				last = level;
			}
			assertEquals(0, LightBeam.taperLevel(reach + 20, reach), "dark well past the reach");
		}
	}

	/**
	 * A one-block tunnel the lattice would step over (the "dead spots"): wherever the tunnel runs against the
	 * lattice, every few blocks of it still gets a cell, so it is lit all along its length.
	 */
	@Test
	void aNarrowTunnelIsLitAlongItsWholeLength() {
		for (int tz = 0; tz < LightBeam.TAPER_GRID; tz++) {
			Blocks rock = new Blocks(Kind.BLOCKED);
			for (int x = -40; x <= 40; x++) rock.set(Kind.AIR, x, 70, tz);
			List<Light> cells = LightBeam.halo(rock, 0, 70, 70, tz, 30);
			assertTrue(cells.size() > 8, "cells along the tunnel, tunnel at z=" + tz + ": " + cells.size());
			for (Light l : cells) {
				assertEquals(tz, l.at().z(), "only in the tunnel");
				assertEquals(70, l.at().y());
			}
			for (int x = -24; x <= 24; x++) {
				assertTrue(lightAt(cells, x, 70, tz) > 0, "tunnel at z=" + tz + " dark at x=" + x);
			}
			// and it tapers: brighter at the holder than at 20 blocks out
			assertTrue(lightAt(cells, 0, 70, tz) > lightAt(cells, 20, 70, tz) && lightAt(cells, 20, 70, tz) >= 1);
		}
	}

	/** Uneven ground: the taper follows the height of the air, a few blocks either way of the eyes. */
	@Test
	void taperCellsFollowTheGroundUpAndDown() {
		// a slope: air from y = 66 + x/5 upward, solid below
		LightBeam.Grid slope = (x, y, z) -> y < 66 + Math.floorDiv(x, 5) ? Kind.BLOCKED : Kind.AIR;
		List<Light> cells = LightBeam.halo(slope, 0, 70, 69, 0, 30);
		assertTrue(cells.size() > 60, "a taper on the slope too: " + cells.size());
		for (Light l : cells) assertTrue(slope.kind(l.at().x(), l.at().y(), l.at().z()) != Kind.BLOCKED);
	}

	/** In solid rock the halo is at most TAPER_MAX_COLUMNS columns of a fixed few dozen reads each (about 15 thousand), whatever the reach: it can't cost a tick. */
	@Test
	void haloInSolidRockIsBoundedWork() {
		int[] reads = {0};
		LightBeam.Grid counting = (x, y, z) -> {
			reads[0]++;
			return x == 0 && y == 70 && z == 0 ? Kind.AIR : Kind.BLOCKED;
		};
		List<Light> cells = LightBeam.halo(counting, 0, 70, 69, 0, 64);
		assertEquals(1, cells.size(), "just the holder's own cell");
		assertTrue(reads[0] <= LightBeam.TAPER_MAX_COLUMNS * 70, "reads " + reads[0]);
	}

	/** Steadying: a level within one of what is shown is kept, anything else is as wanted. */
	@Test
	void steadyKeepsLevelsThatOnlyWaver() {
		Map<Long, Light> shown = new HashMap<>(), wanted = new HashMap<>();
		Light have = new Light(new Pos(5, 70, 5), 8, false);
		shown.put(1L, have);
		wanted.put(1L, new Light(new Pos(5, 70, 5), 9, false)); // a rounding edge: kept
		shown.put(2L, new Light(new Pos(10, 70, 5), 8, false));
		wanted.put(2L, new Light(new Pos(10, 70, 5), 10, false)); // moved on: as wanted
		shown.put(3L, new Light(new Pos(15, 70, 5), 8, false));
		wanted.put(3L, new Light(new Pos(15, 70, 5), 8, true)); // air to water: as wanted
		wanted.put(4L, new Light(new Pos(20, 70, 5), 7, false)); // new: as wanted
		shown.put(5L, new Light(new Pos(25, 70, 5), 15, false));
		wanted.put(5L, new Light(new Pos(25, 70, 5), 14, false)); // full strength is never held
		Map<Long, Light> out = LightBeam.steady(shown, wanted);
		assertEquals(have, out.get(1L));
		assertEquals(10, out.get(2L).level());
		assertTrue(out.get(3L).waterlogged());
		assertEquals(7, out.get(4L).level());
		assertEquals(14, out.get(5L).level());
		assertEquals(5, out.size());
		assertTrue(LightBeam.steady(shown, Map.of()).isEmpty(), "cells no longer wanted are gone");
	}

	/** Block light at a cell from these light cells, as the game spreads it: a level a block along x, y and z. */
	private static int lightAt(List<Light> cells, int x, int y, int z) {
		int best = 0;
		for (Light l : cells) {
			best = Math.max(best, l.level() - Math.abs(l.at().x() - x) - Math.abs(l.at().y() - y) - Math.abs(l.at().z() - z));
		}
		return best;
	}
}
