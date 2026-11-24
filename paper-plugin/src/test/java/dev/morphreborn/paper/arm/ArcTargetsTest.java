package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.ArcTargets;
import dev.morphreborn.paper.arm.rules.ArcTargets.Candidate;
import dev.morphreborn.paper.arm.rules.ArcTargets.Hit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Draining Cut's arc: who it reaches and the order the blade passes through them. */
class ArcTargetsTest {
	private static final double EY = 1.62;

	/** A creature centred at x, y, z with a cube box {@code half} wide each way. */
	private static Candidate at(int id, double x, double y, double z, double half) {
		return new Candidate(id, x, y, z, x - half, y - half, z - half, x + half, y + half, z + half);
	}

	/** {@code deg} degrees to the player's right (negative: left) at {@code dist}, looking along +z (yaw 0). */
	private static Candidate around(int id, double deg, double dist) {
		double r = Math.toRadians(deg);
		// yaw 0 looks along +z; the player's right is -x
		return at(id, -Math.sin(r) * dist, EY, Math.cos(r) * dist, 0.3);
	}

	private static List<Integer> ids(List<Hit> hits) {
		List<Integer> out = new ArrayList<>();
		for (Hit h : hits) out.add(h.id());
		return out;
	}

	private static List<Hit> select(double reach, double arc, int max, boolean left, Candidate... cs) {
		return ArcTargets.select(0, EY, 0, 0, reach, arc, 2.5, max, left, List.of(cs));
	}

	@Test
	void coneEdges() {
		assertEquals(List.of(1, 2), ids(select(4.5, 120, 8, false, around(1, 59, 3), around(2, -59, 3), around(3, 61, 3), around(4, -61, 3))));
	}

	@Test
	void reachIsToTheNearestPointOfTheBox() {
		Candidate big = at(1, 0, EY, 5.4, 1.0); // its near side is 4.4 away
		Candidate small = at(2, 0.5, EY, 5.4, 0.3); // 5.1 away
		List<Hit> hits = select(4.5, 120, 8, false, big, small);
		assertEquals(List.of(1), ids(hits));
		assertEquals(4.4, hits.get(0).dist(), 1e-9);
	}

	@Test
	void verticalBand() {
		Candidate high = at(1, 0, EY + 2.6, 2, 0.3);
		Candidate inBand = at(2, 0.2, EY + 2.4, 2, 0.3);
		Candidate low = at(3, -0.2, EY - 2.6, 2, 0.3);
		assertEquals(List.of(2), ids(select(4.5, 120, 8, false, high, inBand, low)));
	}

	@Test
	void capKeepsTheNearest() {
		List<Hit> hits = select(5, 120, 4, false, around(1, 50, 4.5), around(2, 20, 1.5), around(3, 0, 2), around(4, -20, 2.5), around(5, -50, 3),
				around(6, 10, 4.6));
		assertEquals(4, hits.size());
		assertEquals(List.of(2, 3, 4, 5), ids(hits), "the four nearest, in swing order");
	}

	@Test
	void swingOrderRightToLeftMirroredForLeftHanded() {
		Candidate right = around(1, 40, 3), centre = around(2, 0, 3), left = around(3, -40, 3);
		List<Hit> rightHanded = select(4.5, 120, 8, false, centre, left, right);
		assertEquals(List.of(1, 2, 3), ids(rightHanded));
		assertEquals((60 - 40) / 120.0, rightHanded.get(0).angleNorm(), 1e-9);
		List<Hit> leftHanded = select(4.5, 120, 8, true, centre, left, right);
		assertEquals(List.of(3, 2, 1), ids(leftHanded));
		assertEquals((60 - 40) / 120.0, leftHanded.get(0).angleNorm(), 1e-9);
	}

	@Test
	void tiesGoToTheNearer() {
		assertEquals(List.of(2, 1), ids(select(4.5, 120, 8, false, around(1, 0, 3.5), around(2, 0, 2))));
	}

	@Test
	void yawTurnsTheArc() {
		// yaw 90 looks along -x: a creature at -x is ahead, one at +z (the old ahead) is now 90 degrees to the left
		List<Hit> hits = ArcTargets.select(0, EY, 0, 90, 4.5, 120, 2.5, 8, false, List.of(at(1, -3, EY, 0, 0.3), at(2, 0, EY, 3, 0.3)));
		assertEquals(List.of(1), ids(hits));
	}

	@Test
	void hitsSpreadOverTicksOneToThree() {
		assertEquals(1, ArcTargets.hitTick(0));
		assertEquals(1, ArcTargets.hitTick(0.49));
		assertEquals(2, ArcTargets.hitTick(0.5));
		assertEquals(3, ArcTargets.hitTick(1));
		for (Hit h : select(4.5, 150, 8, false, around(1, 74, 3), around(2, -74, 3))) assertTrue(h.angleNorm() >= 0 && h.angleNorm() <= 1);
	}
}
