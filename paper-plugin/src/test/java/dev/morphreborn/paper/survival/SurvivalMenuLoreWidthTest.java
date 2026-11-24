package dev.morphreborn.paper.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The chest menus' lore width (2026-11-18): the lines are wrapped to {@link SurvivalMenu#LORE_WIDTH} characters, about 200 px in the default
 * font, so a 320-wide screen shows them wherever the mouse is; and the longest line the shipped survival.yml can produce still wraps to it.
 */
class SurvivalMenuLoreWidthTest {
	@Test
	void everyWrappedLineIsAtMostTheLoreWidthExceptAWordLongerThanIt() {
		String text = "Every melee hit you land sets the target alight; your bow and crossbow shots burn. Right-click a campfire, candle, TNT or portal frame"
				+ " with an arm out, if the server allows it.";
		List<String> lines = SurvivalMenu.wrap(text, SurvivalMenu.LORE_WIDTH);
		assertTrue(lines.size() >= 4, "long enough to need several lines");
		for (String line : lines) assertTrue(line.length() <= SurvivalMenu.LORE_WIDTH, "'" + line + "' is " + line.length());
		assertEquals(text, String.join(" ", lines), "nothing lost or reordered");
	}

	@Test
	void aShortLineStaysOne() {
		assertEquals(List.of("Click to buy"), SurvivalMenu.wrap("Click to buy", SurvivalMenu.LORE_WIDTH));
	}

	@Test
	void theLoreCapKeepsTheTooltipUnderTheScreensHeight() {
		// ten pixels a line: twenty lines and the name are 210 px, inside a 240 px scaled screen with its margins
		assertTrue((SurvivalMenu.MAX_LORE_LINES + 1) * 10 <= 240 - 20);
	}
}
