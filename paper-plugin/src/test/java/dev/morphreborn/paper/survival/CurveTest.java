package dev.morphreborn.paper.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** iChun's five upgrade formulas, and the numbers survival.yml's comments promise. */
class CurveTest {
	private static final double EPS = 1e-6;

	@Test
	void levelZeroIsAlwaysZero() {
		assertEquals(0, new Curve(5, 5, 1, 1).at(0), EPS);
		assertEquals(0, new Curve(100, 0, 0, 1).at(-3), EPS);
	}

	@Test
	void biomassCapacityDoubles() {
		Curve capacity = new Curve(0, 100, 2, 3);
		assertEquals(100, capacity.at(1), EPS);
		assertEquals(200, capacity.at(2), EPS);
		assertEquals(51_200, capacity.at(10), EPS);
		Curve cost = new Curve(0, 40, 2, 3);
		assertEquals(80, cost.at(2), EPS);
		assertEquals(20_480, cost.at(10), EPS);
	}

	@Test
	void efficiencyIsLinearPercent() {
		Curve efficiency = new Curve(5, 5, 1, 1);
		assertEquals(10, efficiency.at(1), EPS);
		assertEquals(55, efficiency.at(10), EPS);
	}

	@Test
	void absorbableMassCoversTheDragonOnlyAtTheTop() {
		Curve mass = new Curve(0, 1200, 2.5, 3);
		assertEquals(1200, mass.at(1), EPS);
		assertEquals(3000, mass.at(2), EPS);
		double dragon = 16 * 16 * 8 * 1000.0;
		assertEquals(true, mass.at(9) < dragon);
		assertEquals(true, mass.at(10) > dragon);
	}

	@Test
	void otherEquations() {
		assertEquals(50 + 50 * Math.pow(2, 1.5), new Curve(50, 50, 1.5, 2).at(3), EPS);
		assertEquals(10 + 4 * Math.log10(3 * 2), new Curve(10, 4, 2, 4).at(3), EPS);
		assertEquals(1 + 3 * Math.sqrt(2 * 2), new Curve(1, 3, 2, 5).at(3), EPS);
	}

	@Test
	void listsGiveOneNumberPerLevel() {
		Curve reach = Curve.of(3, 8, 16, 32, 64, 150);
		assertEquals(0, reach.at(0), EPS);
		assertEquals(3, reach.at(1), EPS);
		assertEquals(64, reach.at(5), EPS);
		assertEquals(150, reach.at(6), EPS);
		assertEquals(150, reach.at(9), EPS); // past the end keeps the last number
		assertEquals(-1, Curve.of(1200, -1).at(2), EPS); // -1 is "no limit" for capacities
	}

	@Test
	void zombieGivesTwentyOneBiomassAtTheStart() {
		double zombieMass = 0.6 * 0.6 * 1.95 * 1000; // 702 kg
		assertEquals(21.06, zombieMass * 0.3 * new Curve(5, 5, 1, 1).at(1) / 100, 1e-9);
	}
}
