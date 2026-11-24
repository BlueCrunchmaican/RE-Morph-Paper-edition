package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.morphreborn.paper.arm.rules.ArmTiers;
import org.junit.jupiter.api.Test;

/** An arm's level follows the biomass bar by thirds (ArmTiers.biomassBand), with a little hysteresis going back up. */
class BiomassBandTest {
	private static final long CAP = 90_000; // 90 biomass: thirds at 30 and 60, margin 1.8

	@Test
	void thirdsOfTheBar() {
		assertEquals(3, ArmTiers.biomassBand(90_000, CAP, 0), "full");
		assertEquals(3, ArmTiers.biomassBand(60_000, CAP, 0), "exactly two thirds is still the top band");
		assertEquals(2, ArmTiers.biomassBand(59_999, CAP, 0));
		assertEquals(2, ArmTiers.biomassBand(30_000, CAP, 0));
		assertEquals(1, ArmTiers.biomassBand(29_999, CAP, 0));
		assertEquals(1, ArmTiers.biomassBand(0, CAP, 0), "empty is iron (the melt is the manager's)");
	}

	@Test
	void steppingDownIsImmediate() {
		assertEquals(2, ArmTiers.biomassBand(59_000, CAP, 3));
		assertEquals(1, ArmTiers.biomassBand(10_000, CAP, 3), "a big spend can drop two levels at once");
	}

	@Test
	void steppingUpNeedsTheMargin() {
		assertEquals(2, ArmTiers.biomassBand(60_500, CAP, 2), "just over the line: stays");
		assertEquals(3, ArmTiers.biomassBand(61_800, CAP, 2), "past the line by 2% of the capacity: up");
		assertEquals(1, ArmTiers.biomassBand(31_000, CAP, 1));
		assertEquals(3, ArmTiers.biomassBand(80_000, CAP, 1), "a big gain can climb two levels");
	}

	@Test
	void noCapacityAllowsEverything() {
		assertEquals(3, ArmTiers.biomassBand(0, 0, 0));
	}
}
