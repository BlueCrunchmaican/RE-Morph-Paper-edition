package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.BurrowPlane;
import dev.morphreborn.paper.arm.rules.BurrowPlane.Axis;
import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Which blocks Burrow takes with the one dug: the ring across the face, and which neighbours go. */
class BurrowPlaneTest {
	@Test
	void theFaceSaysTheAxis() {
		assertEquals(Axis.Y, Axis.ofNormal(0, 1, 0), "top");
		assertEquals(Axis.Y, Axis.ofNormal(0, -1, 0), "bottom");
		assertEquals(Axis.X, Axis.ofNormal(1, 0, 0), "east");
		assertEquals(Axis.X, Axis.ofNormal(-1, 0, 0), "west");
		assertEquals(Axis.Z, Axis.ofNormal(0, 0, 1), "south");
		assertEquals(Axis.Z, Axis.ofNormal(0, 0, -1), "north");
		assertNull(Axis.ofNormal(0, 0, 0));
		assertNull(Axis.ofNormal(1, 1, 0), "an edge is no face");
	}

	@Test
	void aTopFaceGivesTheFlatRing() {
		List<Pos> ring = BurrowPlane.ring(Axis.Y, 10, 64, -3);
		assertEquals(8, ring.size());
		for (Pos p : ring) assertEquals(64, p.y(), p + " is level with the dug block");
		assertEquals(expectedPlane(Axis.Y, 10, 64, -3), new HashSet<>(ring));
	}

	@Test
	void aSideFaceGivesTheUprightRing() {
		List<Pos> east = BurrowPlane.ring(Axis.X, 10, 64, -3);
		for (Pos p : east) assertEquals(10, p.x(), "across an east or west face the ring keeps x");
		assertEquals(expectedPlane(Axis.X, 10, 64, -3), new HashSet<>(east));
		List<Pos> south = BurrowPlane.ring(Axis.Z, 10, 64, -3);
		for (Pos p : south) assertEquals(-3, p.z(), "across a north or south face it keeps z");
		assertEquals(expectedPlane(Axis.Z, 10, 64, -3), new HashSet<>(south));
	}

	@Test
	void everyRingIsTheThreeByThreeWithoutItsCentre() {
		for (Axis axis : Axis.values()) {
			List<Pos> ring = BurrowPlane.ring(axis, 0, 0, 0);
			Set<Pos> set = new HashSet<>(ring);
			assertEquals(8, set.size(), axis + ": eight different blocks");
			assertFalse(set.contains(new Pos(0, 0, 0)), axis + ": never the dug block");
			for (Pos p : ring) {
				assertTrue(Math.abs(p.x()) <= 1 && Math.abs(p.y()) <= 1 && Math.abs(p.z()) <= 1, axis + ": " + p + " touches the dug block");
				int off = axis == Axis.X ? p.x() : axis == Axis.Y ? p.y() : p.z();
				assertEquals(0, off, axis + ": " + p + " lies in the plane across the face");
			}
		}
	}

	@Test
	void theRingGoesRoundSoNeighboursTouch() {
		for (Axis axis : Axis.values()) {
			List<Pos> ring = BurrowPlane.ring(axis, 5, 5, 5);
			for (int i = 0; i < ring.size(); i++) {
				Pos a = ring.get(i), b = ring.get((i + 1) % ring.size());
				int a1 = Math.abs(a.x() - b.x()), a2 = Math.abs(a.y() - b.y()), a3 = Math.abs(a.z() - b.z());
				assertTrue(a1 <= 1 && a2 <= 1 && a3 <= 1 && a1 + a2 + a3 >= 1, axis + ": " + a + " to " + b + " is one step round the ring");
			}
		}
	}

	@Test
	void aFaceByItsOffset() {
		assertEquals(BurrowPlane.ring(Axis.Y, 1, 2, 3), BurrowPlane.ring(0, 1, 0, 1, 2, 3));
		assertEquals(BurrowPlane.ring(Axis.X, 1, 2, 3), BurrowPlane.ring(-1, 0, 0, 1, 2, 3));
		assertEquals(BurrowPlane.ring(Axis.Z, 1, 2, 3), BurrowPlane.ring(0, 0, -1, 1, 2, 3));
		assertTrue(BurrowPlane.ring(0, 0, 0, 1, 2, 3).isEmpty());
		assertTrue(BurrowPlane.ring(1, 1, 0, 1, 2, 3).isEmpty());
	}

	@Test
	void theTwoSidesOfAFaceGiveTheSameRing() {
		assertEquals(BurrowPlane.ring(0, 1, 0, 4, 4, 4), BurrowPlane.ring(0, -1, 0, 4, 4, 4));
	}

	@Test
	void dirtTakesSandGravelAndClay() {
		double dirt = 0.5;
		assertTrue(BurrowPlane.accepts(dirt, 0.5, true, false, false), "sand");
		assertTrue(BurrowPlane.accepts(dirt, 0.6, true, false, false), "gravel or clay: 0.6 is within the half point");
		assertTrue(BurrowPlane.accepts(dirt, 1.0, true, false, false), "exactly the centre plus 0.5");
		assertFalse(BurrowPlane.accepts(dirt, 1.01, true, false, false), "just too hard");
		assertFalse(BurrowPlane.accepts(dirt, 1.5, true, false, false), "stone");
	}

	@Test
	void softerBlocksAlwaysGoIfTheyAreShovelBlocks() {
		assertTrue(BurrowPlane.accepts(0.6, 0.1, true, false, false), "snow beside gravel");
		assertTrue(BurrowPlane.accepts(0.6, 0, true, false, false));
	}

	@Test
	void notAShovelBlockStays() {
		assertFalse(BurrowPlane.accepts(0.5, 0.5, false, false, false), "a block the arm isn't the right tool for, however soft");
	}

	@Test
	void tileEntitiesAirAndUnbreakablesStay() {
		assertFalse(BurrowPlane.accepts(0.5, 0.5, true, true, false), "a chest or a furnace");
		assertFalse(BurrowPlane.accepts(0.5, 0, true, false, true), "air");
		assertFalse(BurrowPlane.accepts(0.5, -1, true, false, false), "bedrock");
	}

	@Test
	void onlyAShovelBlockStartsABurrow() {
		assertTrue(BurrowPlane.startsBurrow(true, 0.5));
		assertTrue(BurrowPlane.startsBurrow(true, 0));
		assertFalse(BurrowPlane.startsBurrow(false, 1.5), "stone dug with a spade");
		assertFalse(BurrowPlane.startsBurrow(true, -1));
	}

	/** The 3 by 3 around the centre in the plane across {@code axis}, written out directly. */
	private static Set<Pos> expectedPlane(Axis axis, int x, int y, int z) {
		Set<Pos> out = new HashSet<>();
		for (int a = -1; a <= 1; a++) {
			for (int b = -1; b <= 1; b++) {
				if (a == 0 && b == 0) continue;
				out.add(switch (axis) {
					case Y -> new Pos(x + a, y, z + b);
					case X -> new Pos(x, y + a, z + b);
					case Z -> new Pos(x + a, y + b, z);
				});
			}
		}
		return out;
	}
}
