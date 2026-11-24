package dev.morphreborn.paper.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** When Night Eyes counts as being in the dark: hysteresis at 6 and 9, a hold of 5 seconds, night and storms off the raw sky light. */
class DarkRuleTest {
	@Test
	void theNightIsTakenOffTheRawSkyLight() {
		assertEquals(15, DarkRule.effectiveLight(0, 15, false, false));
		assertEquals(4, DarkRule.effectiveLight(0, 15, true, false), "a field under the moon reads 15 raw");
		assertEquals(10, DarkRule.effectiveLight(0, 15, false, true), "a thunderstorm by day");
		assertEquals(14, DarkRule.effectiveLight(14, 15, true, false), "a torch still counts at night");
		assertEquals(0, DarkRule.effectiveLight(0, 3, true, false), "never below 0");
	}

	@Test
	void darkAtSixOrLessAndLightAgainAtNine() {
		DarkRule d = new DarkRule();
		assertTrue(d.update(0, 6, 0, false, false, 6), "6 is dark");
		assertTrue(d.update(1000, 8, 0, false, false, 6), "8 is still dark: between 6 and 9 it keeps its answer");
		assertFalse(d.update(2000, 9, 0, false, false, 6), "9 is light");
		assertFalse(d.update(3000, 7, 0, false, false, 6), "7 is still light: back to dark only at 6");
		assertTrue(d.update(4000, 6, 0, false, false, 6));
	}

	@Test
	void anAnswerIsHeldForFiveSeconds() {
		DarkRule d = new DarkRule();
		assertTrue(d.update(0, 0, 0, false, false, 6));
		assertTrue(d.update(50, 15, 0, false, false, 6), "a torch's edge: still dark for 100 ticks");
		assertTrue(d.update(99, 15, 0, false, false, 6));
		assertFalse(d.update(100, 15, 0, false, false, 6));
		assertFalse(d.update(150, 0, 0, false, false, 6), "and held light for as long");
		assertTrue(d.update(200, 0, 0, false, false, 6));
	}

	@Test
	void theFirstReadingAnswersAtOnce() {
		DarkRule d = new DarkRule();
		assertTrue(d.update(5, 0, 0, false, false, 6));
		DarkRule lit = new DarkRule();
		assertFalse(lit.update(5, 14, 15, false, false, 6));
	}

	@Test
	void aNightInTheOpenIsDark() {
		DarkRule d = new DarkRule();
		assertTrue(d.update(0, 0, 15, true, false, 6));
		DarkRule day = new DarkRule();
		assertFalse(day.update(0, 0, 15, false, false, 6));
	}

	@Test
	void resetForgetsTheHold() {
		DarkRule d = new DarkRule();
		assertTrue(d.update(0, 0, 0, false, false, 6));
		d.reset();
		assertFalse(d.dark());
		assertFalse(d.update(1, 15, 15, false, false, 6), "answers at once after a reset, with no hold");
	}
}
