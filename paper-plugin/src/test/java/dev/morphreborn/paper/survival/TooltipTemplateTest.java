package dev.morphreborn.paper.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.morphreborn.paper.tips.Lines;
import org.junit.jupiter.api.Test;

/** survival.yml's {arm} and {frenzy} values keep their numbers and lose their explanation with Tooltips off (2026-11-17). */
class TooltipTemplateTest {
	@Test
	void aPlaceholderAloneIsTheTypedPiecesAsTheyAre() {
		Lines arm = Lines.builder().always("Iron strength").tip("Hold right-click").always("cuts blows by 50%").build();
		Lines l = SurvivalMode.around("{arm}", "{arm}", arm);
		assertEquals("Iron strength · Hold right-click · cuts blows by 50%", l.text(true));
		assertEquals("Iron strength · cuts blows by 50%", l.text(false));
	}

	@Test
	void theFormatsOwnWordsAroundItStay() {
		Lines l = SurvivalMode.around("up to {arm} each", "{arm}", Lines.builder().always("3 hits").tip("in a fan").build());
		assertEquals("up to · 3 hits · in a fan · each", l.text(true));
		assertEquals("up to · 3 hits · each", l.text(false), "the words are the level's and are not dropped");
	}

	@Test
	void noPlaceholderIsJustTheText() {
		assertEquals("12 blocks", SurvivalMode.around("12 blocks", "{arm}", Lines.tip("x")).text(false));
	}
}
