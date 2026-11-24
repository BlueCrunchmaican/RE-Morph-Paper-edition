package dev.morphreborn.client.arm.lantern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The lantern's coloured light (2026-11-16) as pure geometry: the flood through the open blocks collects
 * the solid faces the light could reach and none behind a wall, every face is wound to be seen from its open side, and the wash
 * is brightest at the lantern, never rises with distance, is gone at the radius and is continuous across faces.
 */
class GlowFieldTest {
	/** A world of solid blocks with air carved out of it. */
	private static final class World implements GlowField.Grid {
		final Set<Long> air = new HashSet<>();
		final int otherwise;

		World(int otherwise) {
			this.otherwise = otherwise;
		}

		static long key(int x, int y, int z) {
			return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
		}

		World air(int x0, int y0, int z0, int x1, int y1, int z1) {
			for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) air.add(key(x, y, z));
			return this;
		}

		World solid(int x0, int y0, int z0, int x1, int y1, int z1) {
			for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) air.remove(key(x, y, z));
			return this;
		}

		@Override
		public int kind(int x, int y, int z) {
			return air.contains(key(x, y, z)) ? GlowField.OPEN : otherwise;
		}
	}

	private static Set<Long> faceKeys(GlowField f) {
		Set<Long> out = new HashSet<>();
		for (int i = 0; i < f.count(); i++) out.add(World.key(f.cellX(i), f.cellY(i), f.cellZ(i)) * 8 + f.dir(i));
		return out;
	}

	// ------------------------------------------------------------------ the flood

	/** A closed room 9 x 4 x 9 of air: every face of its floor, ceiling and walls is found, once. */
	@Test
	void aRoomGetsEveryFaceOfItsSurfaceOnce() {
		World w = new World(GlowField.SOLID).air(0, 0, 0, 8, 3, 8);
		GlowField f = GlowField.build(w, 4, 1, 4, 20);
		int surface = 2 * 9 * 9 + 4 * 9 * 4;
		assertEquals(surface, f.count(), "floor, ceiling and four walls");
		assertEquals(surface, faceKeys(f).size(), "no face twice");
		assertFalse(f.truncated);
	}

	/** Light does not go through walls: a second room behind a solid wall gets no faces; with a door it does, a little dimmer. */
	@Test
	void theWashStopsAtAWallAndFollowsADoor() {
		World shut = new World(GlowField.SOLID).air(0, 0, 0, 6, 3, 6).air(10, 0, 0, 16, 3, 6);
		GlowField f = GlowField.build(shut, 3, 1, 3, 20);
		for (int i = 0; i < f.count(); i++) assertTrue(f.cellX(i) <= 6, "a face in the other room: " + f.cellX(i));
		World door = new World(GlowField.SOLID).air(0, 0, 0, 6, 3, 6).air(10, 0, 0, 16, 3, 6).air(7, 0, 3, 9, 1, 3);
		GlowField g = GlowField.build(door, 3, 1, 3, 20);
		int beyond = 0;
		for (int i = 0; i < g.count(); i++) if (g.cellX(i) >= 10) beyond++;
		assertTrue(beyond > 20, "the room through the door is lit: " + beyond);
	}

	/** A face reached the long way round counts the extra blocks of the way: 0 straight, and 0 down an L (same manhattan length). */
	@Test
	void roundAnObstacleCountsTheDetour() {
		World l = new World(GlowField.SOLID).air(0, 0, 0, 10, 0, 0).air(10, 0, 1, 10, 0, 10);
		GlowField f = GlowField.build(l, 0, 0, 0, 20);
		float straight = -1, bent = -1;
		for (int i = 0; i < f.count(); i++) {
			if (f.cellX(i) == 5 && f.cellZ(i) == 0) straight = f.detour(i);
			if (f.cellX(i) == 10 && f.cellZ(i) == 5) bent = f.detour(i);
		}
		assertEquals(0F, straight, "straight down the corridor");
		assertEquals(0F, bent, "an L has the same manhattan way: nothing extra");
		// a sheet of air with a wall across it from z=0 to 8: to reach (6,0,0) the way goes out to z=9 and back, 24 steps for 6
		World sheet = new World(GlowField.SOLID).air(0, 0, 0, 10, 0, 10).solid(5, 0, 0, 5, 0, 8);
		GlowField g = GlowField.build(sheet, 0, 0, 0, 20);
		float round = -1;
		for (int i = 0; i < g.count(); i++) if (g.cellX(i) == 6 && g.cellZ(i) == 0) round = g.detour(i);
		assertEquals(18F, round, "24 steps for 6 blocks as the crow flies");
		// and it shows: that face is dimmer than the same face with no wall
		World open = new World(GlowField.SOLID).air(0, 0, 0, 10, 0, 10);
		GlowField h = GlowField.build(open, 0, 0, 0, 20);
		float[] c = new float[12];
		double behindWall = -1, free = -1;
		for (int i = 0; i < g.count(); i++) {
			if (g.cellX(i) == 6 && g.cellZ(i) == 0 && g.dir(i) == 2) {
				g.corners(i, c);
				behindWall = g.wash(i, c, 0, 0.5, 0.5, 0.5);
			}
		}
		for (int i = 0; i < h.count(); i++) {
			if (h.cellX(i) == 6 && h.cellZ(i) == 0 && h.dir(i) == 2) {
				h.corners(i, c);
				free = h.wash(i, c, 0, 0.5, 0.5, 0.5);
			}
		}
		assertTrue(behindWall < free, behindWall + " vs " + free);
	}

	/** The radius bounds the flood as the crow flies; far faces are not collected. */
	@Test
	void nothingFartherThanTheRadiusIsKept() {
		World w = new World(GlowField.SOLID).air(-40, 0, -40, 40, 3, 40);
		GlowField f = GlowField.build(w, 0, 1, 0, 10);
		assertTrue(f.count() > 100);
		for (int i = 0; i < f.count(); i++) {
			double d = Math.sqrt(Math.pow(f.cellX(i), 2) + Math.pow(f.cellY(i) - 1, 2) + Math.pow(f.cellZ(i), 2));
			assertTrue(d <= 12.0 + 1e-9, "a face " + d + " out for radius 10");
		}
	}

	/** In the open, with a cap, the near faces are the ones kept (the flood goes nearest first). */
	@Test
	void theCapsKeepTheNearFaces() {
		// a forest of pillars: far more faces than the cap within 20 blocks
		World w = new World(GlowField.SOLID).air(-60, 0, -60, 60, 1, 60);
		for (int x = -59; x < 60; x += 2) for (int z = -59; z < 60; z += 2) w.solid(x, 0, z, x, 1, z);
		GlowField f = GlowField.build(w, 0, 0, 0, 20);
		assertEquals(GlowField.MAX_FACES, f.count());
		assertTrue(f.truncated, "more than the cap's faces round here");
		double nearest = Double.MAX_VALUE, farthest = 0;
		for (int i = 0; i < f.count(); i++) {
			double d = Math.hypot(f.cellX(i), f.cellZ(i));
			farthest = Math.max(farthest, d);
			nearest = Math.min(nearest, d);
		}
		assertTrue(nearest <= 1.0, "the faces by the lantern are there");
		assertTrue(farthest > 8, "and the cap did not just keep a handful");
	}

	@Test
	void aLanternInsideSolidRockHasNoField() {
		World w = new World(GlowField.SOLID);
		assertEquals(0, GlowField.build(w, 0, 0, 0, 10).count());
		World edge = new World(GlowField.BLOCKED).air(0, 0, 0, 0, 0, 0);
		assertEquals(0, GlowField.build(edge, 0, 0, 0, 10).count(), "unloaded all round: nothing to wash");
		World unloaded = new World(GlowField.BLOCKED).air(-3, 0, -3, 3, 1, 3);
		assertEquals(0, GlowField.build(unloaded, 0, 0, 0, 10).count(), "an unloaded neighbour takes no face");
	}

	/** The scratch space survives a big build then a small one then a big one: no stale visits leak between them. */
	@Test
	void buildsDoNotLeakIntoEachOther() {
		World room = new World(GlowField.SOLID).air(0, 0, 0, 4, 2, 4);
		GlowField first = GlowField.build(room, 2, 1, 2, 20);
		GlowField small = GlowField.build(room, 2, 1, 2, 3);
		GlowField second = GlowField.build(room, 2, 1, 2, 20);
		assertEquals(faceKeys(first), faceKeys(second));
		assertTrue(small.count() <= first.count());
	}

	// ------------------------------------------------------------------ the quads

	/** The lift off the block grows with the camera's distance (the depth buffer's steps do), from LIFT up to a cap, and the corners follow it. */
	@Test
	void theLiftGrowsWithDistanceFromTheCamera() {
		assertEquals(GlowField.LIFT, GlowField.liftAt(0), 1e-9F);
		assertTrue(GlowField.liftAt(100 * 100) > GlowField.liftAt(30 * 30));
		assertEquals(GlowField.LIFT_MAX, GlowField.liftAt(1e9), 1e-9F);
		float[] c = new float[12];
		GlowField.corners(0, 0, 0, 3, c, 0.05F); // the solid block is above: the face is 0.05 under its underside
		assertEquals(1F - 0.05F, c[1], 1e-6F);
	}

	/** Each of the six directions: the corners wind counter-clockwise seen from the open cell (normal out of the solid block). */
	@Test
	void everyFaceIsWoundToBeSeenFromTheOpenSide() {
		double[] normal = new double[3];
		float[] c = new float[12];
		for (int dir = 0; dir < 6; dir++) {
			GlowField.corners(7, -3, 11, dir, c);
			double[] e1 = {c[3] - c[0], c[4] - c[1], c[5] - c[2]}, e2 = {c[9] - c[0], c[10] - c[1], c[11] - c[2]};
			double[] cross = {e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]};
			GlowField.normal(dir, normal);
			for (int k = 0; k < 3; k++) assertEquals(normal[k], cross[k], 1e-6, "dir " + dir + " axis " + k);
			// a unit quad: opposite corners sum equal
			for (int k = 0; k < 3; k++) assertEquals(c[k] + c[6 + k], c[3 + k] + c[9 + k], 1e-6);
			// it lies on the open cell's side of the block face (by the lift), not inside the solid block
			int axis = dir >> 1;
			float cell = new float[] {7, -3, 11}[axis];
			boolean solidAbove = (dir & 1) == 1; // direction + : the solid block is above the cell on that axis
			float coord = c[axis];
			if (solidAbove) assertEquals(cell + 1 - GlowField.LIFT, coord, 1e-5);
			else assertEquals(cell + GlowField.LIFT, coord, 1e-5);
		}
	}

	/** A face of the field, as the field gives it, is relative to the field's own cell. */
	@Test
	void aFieldsFacesAreRelativeToItsCell() {
		World w = new World(GlowField.SOLID).air(1000000, 64, -1000000, 1000002, 66, -999998);
		GlowField f = GlowField.build(w, 1000001, 65, -999999, 5);
		float[] c = new float[12];
		assertTrue(f.count() > 0);
		for (int i = 0; i < f.count(); i++) {
			f.corners(i, c);
			for (int k = 0; k < 12; k++) assertTrue(Math.abs(c[k]) < 8, "small coordinates: " + c[k]);
		}
	}

	// ------------------------------------------------------------------ the wash

	@Test
	void theTaperIsFullAtTheLanternNothingAtTheRadiusAndNeverRises() {
		for (int radius : new int[] {7, 13, 20}) {
			assertEquals(1.0, GlowField.taper(0, 0, radius), 1e-12);
			assertEquals(0.0, GlowField.taper(radius, 0, radius), 1e-12);
			assertEquals(0.0, GlowField.taper(radius + 5, 0, radius), 1e-12);
			double last = 1.0;
			for (double d = 0; d <= radius + 2; d += 0.25) {
				double t = GlowField.taper(d, 0, radius);
				assertTrue(t <= last + 1e-12 && t >= 0 && t <= 1, "d " + d + " t " + t);
				last = t;
			}
			assertTrue(GlowField.taper(5, 3, radius) < GlowField.taper(5, 0, radius), "a longer way is dimmer");
		}
	}

	/** On a floor lit from above the wash is brightest under the lantern, falls off with distance and is gone at the radius. */
	@Test
	void aFloorIsBrightestUnderTheLanternAndTapersAway() {
		World w = new World(GlowField.SOLID).air(-30, 0, -30, 30, 6, 30);
		int radius = 13;
		GlowField f = GlowField.build(w, 0, 2, 0, radius);
		double lx = 0.5, ly = 0.5, lz = 0.5; // the lantern in the middle of the field's own cell
		float[] c = new float[12];
		double under = 0, far = 0;
		boolean sawFar = false;
		for (int i = 0; i < f.count(); i++) {
			if (f.dir(i) != 2 || f.cellY(i) != 0) continue; // the floor: the solid block is below the open cell
			double dist = Math.hypot(f.cellX(i), f.cellZ(i));
			double centre = floorWash(f, i, c, lx, ly, lz);
			if (f.cellX(i) == 0 && f.cellZ(i) == 0) under = centre;
			if (dist >= radius - 1) {
				sawFar = true;
				far = Math.max(far, centre);
			}
		}
		assertTrue(under > 0.3, "bright under the lantern: " + under);
		assertTrue(sawFar);
		assertTrue(far < 0.05, "all but gone at the radius: " + far);
		// along the floor in a line, the wash never rises moving away
		double last = Double.MAX_VALUE;
		for (int x = 0; x <= radius; x++) {
			for (int i = 0; i < f.count(); i++) {
				if (f.dir(i) == 2 && f.cellY(i) == 0 && f.cellX(i) == x && f.cellZ(i) == 0) {
					double centre = floorWash(f, i, c, lx, ly, lz);
					assertTrue(centre <= last + 1e-9, "x " + x + ": " + centre + " after " + last);
					last = centre;
				}
			}
		}
	}

	/** The mean of a face's four corners' wash. */
	private static double floorWash(GlowField f, int i, float[] c, double lx, double ly, double lz) {
		f.corners(i, c);
		double sum = 0;
		for (int k = 0; k < 4; k++) sum += f.wash(i, c, k, lx, ly, lz) / 4.0;
		return sum;
	}

	/** The wash is per corner, so two faces that share an edge agree along it: no seams, no tiles. */
	@Test
	void theWashIsContinuousAcrossNeighbouringFaces() {
		World w = new World(GlowField.SOLID).air(-10, 0, -10, 10, 5, 10);
		GlowField f = GlowField.build(w, 0, 2, 0, 12);
		double lx = 0.5, ly = 0.5, lz = 0.5;
		float[] a = new float[12], b = new float[12];
		int tested = 0;
		for (int i = 0; i < f.count(); i++) {
			if (f.dir(i) != 2 || f.cellY(i) != 0 || f.cellZ(i) != 3 || f.cellX(i) < -4 || f.cellX(i) > 4) continue;
			for (int j = 0; j < f.count(); j++) {
				if (f.dir(j) != 2 || f.cellY(j) != 0 || f.cellZ(j) != 3 || f.cellX(j) != f.cellX(i) + 1) continue;
				f.corners(i, a);
				f.corners(j, b);
				int shared = 0;
				for (int p = 0; p < 4; p++) {
					for (int q = 0; q < 4; q++) {
						if (Math.abs(a[3 * p] - b[3 * q]) < 1e-6 && Math.abs(a[3 * p + 1] - b[3 * q + 1]) < 1e-6 && Math.abs(a[3 * p + 2] - b[3 * q + 2]) < 1e-6) {
							assertEquals(f.wash(i, a, p, lx, ly, lz), f.wash(j, b, q, lx, ly, lz), 1e-9, "a shared corner reads the same");
							shared++;
						}
					}
				}
				assertEquals(2, shared, "two neighbouring floor faces share an edge");
				tested++;
			}
		}
		assertTrue(tested >= 8, "enough neighbours looked at: " + tested);
	}

	/** A face looking away from the light gets the ambient share of what one looking at it gets from the same distance: dim, not dark. */
	@Test
	void aFaceLookingAwayIsDimButNotDark() {
		World w = new World(GlowField.SOLID).air(-6, -6, -6, 6, 6, 6);
		GlowField f = GlowField.build(w, 0, 0, 0, 16);
		float[] c = new float[12];
		double in = -1, behind = -1;
		for (int i = 0; i < f.count(); i++) {
			// the wall on the -x side of the room, its faces looking +x; corner 0 is at about x = -6
			if (f.dir(i) == 0 && f.cellX(i) == -6 && f.cellY(i) == 0 && f.cellZ(i) == 0) {
				f.corners(i, c);
				double x = c[0];
				in = f.wash(i, c, 0, x + 3.0, c[1], c[2]);
				behind = f.wash(i, c, 0, x - 3.0, c[1], c[2]);
			}
		}
		assertTrue(in > 0 && behind > 0, "in " + in + " behind " + behind);
		assertEquals(GlowField.AMBIENT, behind / in, 1e-9);
	}

	@Test
	void theWashFadesInAndOutInTicks() {
		float v = 0F;
		for (int i = 0; i < 8; i++) v = GlowField.fade(v, true, 8);
		assertEquals(1F, v, 1e-6);
		assertEquals(1F, GlowField.fade(v, true, 8), "no further than full");
		for (int i = 0; i < 3; i++) v = GlowField.fade(v, false, 8);
		assertEquals(1F - 3F / 8F, v, 1e-6);
		for (int i = 0; i < 20; i++) v = GlowField.fade(v, false, 8);
		assertEquals(0F, v);
		assertEquals(1F, GlowField.fade(0.5F, true, 1), "a one-tick fade is instant");
	}
}
