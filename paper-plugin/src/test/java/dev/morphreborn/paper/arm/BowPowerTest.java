package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.special.BowArm;
import org.junit.jupiter.api.Test;

/** The bow arm's draw is the game's own: the same power for every draw time, and the same cut-off. */
class BowPowerTest {
	/** The game's BowItem.getPowerForTime, written out as it is there. */
	private static float vanilla(int charge) {
		float f = charge / 20.0F;
		f = (f * f + f * 2.0F) / 3.0F;
		if (f > 1.0F) f = 1.0F;
		return f;
	}

	@Test
	void equalsVanillaForEveryDrawFromZeroToThirty() {
		for (int t = 0; t <= 30; t++) assertEquals(vanilla(t), BowArm.power(t), 0.0F, "power at " + t + " ticks");
	}

	@Test
	void knownValues() {
		assertEquals(0.0F, BowArm.power(0), 0.0F);
		assertEquals(0.1075F, BowArm.power(3), 1e-6F); // (0.15^2 + 0.3) / 3
		assertEquals(1.0F, BowArm.power(20), 0.0F); // a full draw
		assertEquals(1.0F, BowArm.power(30), 0.0F); // never more than 1
		assertEquals(1.0F, BowArm.power(10_000), 0.0F);
		// a negative draw (a bad event) counts as none
		assertEquals(0.0F, BowArm.power(-5), 0.0F);
	}

	@Test
	void growsWithTheDrawAndStopsAtOne() {
		float last = -1;
		for (int t = 0; t <= 30; t++) {
			float p = BowArm.power(t);
			assertTrue(p >= last, "never weaker for a longer draw (" + t + ")");
			assertTrue(p >= 0 && p <= 1.0F);
			last = p;
		}
	}

	@Test
	void nothingBelowATenthOfThePower() {
		// the first draw that looses is 3 ticks, as for a real bow
		for (int t = 0; t <= 2; t++) assertFalse(BowArm.looses(BowArm.power(t)), "no bolt at " + t);
		for (int t = 3; t <= 30; t++) assertTrue(BowArm.looses(BowArm.power(t)), "a bolt at " + t);
		for (int t = 0; t <= 30; t++) assertEquals(!((double) vanilla(t) < 0.1), BowArm.looses(BowArm.power(t)), "the game's own cut-off at " + t);
	}

	@Test
	void theShootCommandsDrawGivesAtLeastThePowerAsked() {
		// /morph arm <p> shoot [power] solves for the ticks; the bolt must be at least that strong
		for (int i = 10; i <= 100; i++) {
			double asked = i / 100.0;
			int ticks = ArmCommand.drawTicks(asked);
			assertTrue(BowArm.power(ticks) >= asked - 1e-6, "power " + asked + " needs " + ticks + " ticks");
			assertTrue(BowArm.looses(BowArm.power(ticks)), "and it must shoot at " + asked);
		}
	}

	@Test
	void aHitThatTakesTheLastHealthIsLethalSoItCarriesNoTip() {
		// the damage event is read before the damage lands (2026-11-16 review), so the arithmetic stands in for isDead()
		assertTrue(BowArm.lethal(1.0, 2.0), "a 1-hp zombie hit for 2");
		assertTrue(BowArm.lethal(2.0, 2.0), "exactly the last health");
		assertFalse(BowArm.lethal(2.5, 2.0), "half a heart left");
		assertFalse(BowArm.lethal(20.0, 0.0), "no damage is no kill");
	}
}
