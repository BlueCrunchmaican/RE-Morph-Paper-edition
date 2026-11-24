package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.morphreborn.paper.arm.rules.VeinSummary;
import org.junit.jupiter.api.Test;

/** The action-bar line a Vein Rip ends with (2026-11-16: no more silent rips). */
class VeinSummaryTest {
	@Test
	void aWholeRipSaysHowManyCameOut() {
		assertEquals("Vein Rip: tore out 7 ores of iron", VeinSummary.of("Vein Rip", "iron", 7, 7, 0, 0, true, false));
		assertEquals("Vein Rip: tore out 1 ore of iron", VeinSummary.of("Vein Rip", "iron", 1, 1, 0, 0, true, false));
	}

	@Test
	void whatWasSkippedIsNamedByReason() {
		assertEquals("Vein Rip: tore out 5 ores of diamond, 2 out of reach", VeinSummary.of("Vein Rip", "diamond", 7, 5, 2, 0, true, false));
		assertEquals("Vein Rip: tore out 5 ores of diamond, 1 out of reach, 1 left in the rock", VeinSummary.of("Vein Rip", "diamond", 7, 5, 1, 1, true, false));
	}

	@Test
	void anEmptyRipIsExplainedNotSilent() {
		assertEquals("Vein Rip: none of the 3 ores of coal came out, 3 left in the rock", VeinSummary.of("Vein Rip", "coal", 3, 0, 0, 3, true, true));
	}

	@Test
	void creativeSaysItsOresDoNotDrop() {
		assertEquals("Vein Rip: tore out 4 ores of nether quartz (creative: ores don't drop)", VeinSummary.of("Vein Rip", "nether_quartz", 4, 4, 0, 0, true, true));
	}

	@Test
	void aRipStoppedEarlySaysSo() {
		assertEquals("Vein Rip cut short: 3 of 16 ores of copper torn out", VeinSummary.of("Vein Rip", "copper", 16, 3, 0, 0, false, false));
		assertEquals("Vein Rip cut short: 0 of 8 ores of gold torn out", VeinSummary.of("Vein Rip", "gold", 8, 0, 0, 0, false, false));
	}
}
