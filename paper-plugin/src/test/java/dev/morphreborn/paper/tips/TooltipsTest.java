package dev.morphreborn.paper.tips;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.ArmItemSpec;
import dev.morphreborn.paper.arm.rules.ArmItemSpecs;
import dev.morphreborn.paper.arm.rules.BowSettings;
import dev.morphreborn.paper.arm.rules.BowTips;
import dev.morphreborn.paper.arm.rules.ClawSettings;
import dev.morphreborn.paper.arm.rules.CrossbowSettings;
import dev.morphreborn.paper.arm.rules.FlintSettings;
import dev.morphreborn.paper.arm.rules.MaceSettings;
import dev.morphreborn.paper.arm.rules.ShieldSettings;
import dev.morphreborn.paper.arm.rules.SpearSettings;
import dev.morphreborn.paper.arm.rules.SpyglassSettings;
import dev.morphreborn.paper.arm.rules.TridentSettings;
import dev.morphreborn.paper.arm.rules.WhipMoves;
import dev.morphreborn.paper.net.Wire;
import dev.morphreborn.paper.survival.PassiveSettings;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Tooltips setting (decided 2026-11-17): the typed pieces of a description and the filter that leaves the explanation out. Every check is
 * made with the setting on and off, and what must always stay (names, levels, costs and rates) is checked by value.
 */
class TooltipsTest {
	// ------------------------------------------------------------------ the builder and the filter

	@Test
	void aClauseIsKeptOrDroppedByItsKind() {
		Lines l = Lines.builder().always("Level 2").tip("Click to buy").always("Cost 5 biomass").build();
		assertEquals(List.of("Level 2", "Click to buy", "Cost 5 biomass"), l.clauses(true));
		assertEquals(List.of("Level 2", "Cost 5 biomass"), l.clauses(false));
		assertEquals("Level 2 · Click to buy · Cost 5 biomass", l.text(true));
		assertEquals("Level 2 · Cost 5 biomass", l.text(false), "the separator of a dropped clause goes with it");
	}

	@Test
	void aSentenceWithARateAndAnExplanationIsSplitSoTheRateStays() {
		// "Reap: harvests and replants ripe crops within 5 blocks"
		Lines l = Lines.builder().always("Reap:").tipMore(" harvests and replants ripe crops within").alwaysMore(" 5 blocks").build();
		assertEquals("Reap: harvests and replants ripe crops within 5 blocks", l.text(true));
		assertEquals("Reap: 5 blocks", l.text(false));
	}

	@Test
	void aClauseThatIsAllExplanationLeavesNothingBehind() {
		Lines l = Lines.tip("Hold right-click");
		assertEquals("Hold right-click", l.text(true));
		assertEquals("", l.text(false));
		assertTrue(l.any(true));
		assertFalse(l.any(false));
		assertTrue(Lines.EMPTY.isEmpty());
		assertEquals("", Lines.EMPTY.text(true));
	}

	@Test
	void aContinuationWhoseClauseWentStartsItsOwnText() {
		Lines l = Lines.builder().tip("Costs").alwaysMore(" 6 biomass a minute").build();
		assertEquals("6 biomass a minute", l.text(false), "no leading space is left");
	}

	@Test
	void emptyTextAddsNothingAndLinesJoin() {
		Lines l = Lines.builder().always("").tip("").always("x").build();
		assertEquals(List.of("x"), l.clauses(true));
		Lines joined = Lines.always("a").then(Lines.tip("b"));
		assertEquals("a · b", joined.text(true));
		assertEquals("a", joined.text(false));
		assertEquals("a · b", joined.toString());
	}

	@Test
	void taggedLoreKeepsAlwaysLinesAndTidiesTheBlanks() {
		List<Tagged<String>> lore = List.of(Tagged.tip("What it does"), Tagged.always(""), Tagged.always("Level 1"), Tagged.always("Cost: 5 biomass"), Tagged.always(""),
				Tagged.tip("Click to buy"));
		assertEquals(List.of("What it does", "", "Level 1", "Cost: 5 biomass", "", "Click to buy"), Tagged.filter(lore, true, String::isEmpty));
		assertEquals(List.of("Level 1", "Cost: 5 biomass"), Tagged.filter(lore, false, String::isEmpty), "no gap where the tips were, none at either end");
		assertEquals(List.of("", "Level 1", "Cost: 5 biomass", ""), Tagged.filter(lore, false), "without the tidy a blank stays where it was");
	}

	// ------------------------------------------------------------------ the setting and its command

	@Test
	void theSettingIsOnUnlessStoredOff() {
		assertTrue(TooltipPrefs.on(false), "a file from before the setting reads false, so on");
		assertFalse(TooltipPrefs.on(true));
	}

	@Test
	void theCommandWordsAreReadLoosely() {
		assertEquals(Boolean.TRUE, TooltipPrefs.parse("on"));
		assertEquals(Boolean.TRUE, TooltipPrefs.parse(" ON "));
		assertEquals(Boolean.FALSE, TooltipPrefs.parse("off"));
		assertEquals(Boolean.FALSE, TooltipPrefs.parse("Hide"));
		assertNull(TooltipPrefs.parse("maybe"));
		assertNull(TooltipPrefs.parse(null));
		assertEquals(List.of("on", "off"), TooltipPrefs.WORDS, "tab completion offers both");
	}

	@Test
	void thePacketIsTheAgreedBytes() {
		// the client's TooltipsTest reads the same fixture with its own codec
		assertEquals(12, Wire.C_TOOLTIPS);
		assertEquals(3, Wire.PROTOCOL_VERSION, "everything is additive");
		assertArrayEquals(HexFormat.ofDelimiter(" ").parseHex("0C 01"), new Wire.Out(Wire.C_TOOLTIPS).bool(true).bytes(), "id 12, bool on");
		Wire.In in = new Wire.In(HexFormat.ofDelimiter(" ").parseHex("0C 00"));
		assertEquals(Wire.C_TOOLTIPS, in.varInt());
		assertFalse(in.bool());
	}

	@Test
	void onlyAClientFromTheVersionThatHasTheSettingIsHeardOnIt() {
		assertEquals("8.0.0-beta.2", TooltipPrefs.SINCE);
		assertTrue(TooltipPrefs.clientKnows("8.0.0-beta.2"));
		assertTrue(TooltipPrefs.clientKnows("8.0.0"));
		assertFalse(TooltipPrefs.clientKnows("7.6.0"), "an older client stays on the default");
		assertFalse(TooltipPrefs.clientKnows("8.0.0-beta.1"));
		assertFalse(TooltipPrefs.clientKnows("?"));
		assertFalse(TooltipPrefs.clientKnows(null));
	}

	// ------------------------------------------------------------------ the descriptions

	private static void assertStays(Lines l, String... mustStay) {
		String off = l.text(false);
		for (String s : mustStay) assertTrue(off.contains(s), "'" + s + "' must stay with tooltips off: " + off);
	}

	private static void assertGone(Lines l, String... explanation) {
		String off = l.text(false);
		for (String s : explanation) assertFalse(off.contains(s), "'" + s + "' must go with tooltips off: " + off);
		for (String s : explanation) assertTrue(l.text(true).contains(s), "'" + s + "' is there with tooltips on");
	}

	@Test
	void theShieldKeepsItsNumbersAndLosesTheClick() {
		Lines l = ShieldSettings.DEFAULTS.lines(2, "1 biomass");
		assertStays(l, "cuts frontal blows by 60%", "1 biomass per blocked hit");
		assertGone(l, "Hold right-click", "off hand is locked", "fly back at their shooter");
		assertEquals("cuts frontal blows by 60% · 1 biomass per blocked hit", l.text(false));
	}

	@Test
	void theSpyglassKeepsHowManyAndHowFarAndLosesTheKey() {
		Lines l = SpyglassSettings.DEFAULTS.lines(3);
		assertStays(l, "marks up to 3 creatures within 128 blocks");
		assertGone(l, "Hold the zoom key (Z)", "glow for you alone");
		assertEquals("marks up to 3 creatures within 128 blocks", l.text(false));
	}

	@Test
	void theFlintKeepsTheFireAndTheirPriceAndLosesWhatLightsWhat() {
		Lines l = FlintSettings.DEFAULTS.lines(2, "0.5 biomass");
		assertStays(l, "fire lasts 4 s", "0.5 biomass per ignition");
		assertGone(l, "every melee hit you land", "bow and crossbow shots burn", "right-click a campfire");
		assertEquals("fire lasts 4 s · 0.5 biomass per ignition", l.text(false));
		assertEquals("fire lasts 3 s", FlintSettings.DEFAULTS.lines(1, "").text(false), "free: no price clause");
	}

	@Test
	void everyArmFormKeepsItsNumbersWithTooltipsOff() {
		assertStays(BowSettings.DEFAULTS.lines(2), "bolts hit for 2.5", "tips last 4 s (bleed)");
		assertGone(BowSettings.DEFAULTS.lines(2), "15° apart on a full draw");
		assertStays(BowSettings.DEFAULTS.lines(2), "Triple Arrow: three bolts");
		assertEquals("3 slashes · 2 damage each · bleeds 4 s", ClawSettings.DEFAULTS.lines(2).text(false));
		assertStays(CrossbowSettings.DEFAULTS.lines(2), "spines hit for 4", "bolts hit for 8");
		assertGone(CrossbowSettings.DEFAULTS.lines(2), "fires 3 spines in a line");
		assertStays(CrossbowSettings.DEFAULTS.lines(2), "Spine Burst 3 spines, each piercing up to 3 creatures");
		assertEquals("Leaps 8 blocks · the fall adds 22 damage to the smash", MaceSettings.DEFAULTS.lines(2).text(false));
		assertEquals("Lunges 4 blocks · pins 2 s · 5 damage", SpearSettings.DEFAULTS.lines(2).text(false));
		assertStays(TridentSettings.DEFAULTS.lines(2), "Recall hits for 5", "thrown for 9");
		assertGone(TridentSettings.DEFAULTS.lines(2), "snaps the trident back at once");
	}

	@Test
	void theLoreLineKeepsTheNameAndTheCostAndLosesTheKeys() {
		Lines bow = BowTips.loreLines("Triple Arrow", 6_000, true);
		assertEquals("Triple Arrow 6 biomass · Sneak+F: arrow tip", bow.text(true));
		assertEquals("Triple Arrow 6 biomass", bow.text(false));
		Lines whip = WhipMoves.loreLines(6_000, 4_000, 6_000, 10_000, true);
		assertEquals("Snare 6 · Disarm 4 · Crack 6 · Whirl (sneak) 10 biomass", whip.text(true));
		assertEquals("Snare 6 · Disarm 4 · Crack 6 · Whirl 10 biomass", whip.text(false), "the costs stay, only the key goes");
		assertEquals("Snare · Disarm · Crack · Whirl", WhipMoves.loreLines(0, 0, 0, 0, true).text(false));
	}

	@Test
	void anArmItemsLoreFollowsTheSetting() {
		Lines special = Lines.builder().always("Draining Cut").always("15 biomass").build();
		ArmItemSpec on = ArmItemSpecs.of("katana", 2, true, 40_000, 100_000, special, 0, 0, true);
		ArmItemSpec off = ArmItemSpecs.of("katana", 2, true, 40_000, 100_000, special, 0, 0, false);
		assertEquals(List.of("Your own arm, reshaped.", "F: Draining Cut · 15 biomass"), on.lore());
		assertEquals(List.of("Draining Cut · 15 biomass"), off.lore(), "the name and the cost stay");
		ArmItemSpec pick = ArmItemSpecs.of("pickaxe", 3, true, 0, 0, Lines.always("Vein Rip"), 3, 3, false);
		assertTrue(pick.lore().contains("Fortune III") && pick.lore().contains("Efficiency III"), "the enchantments are the item's own and stay: " + pick.lore());
		assertEquals(ArmItemSpecs.of("katana", 2, true, 40_000, 100_000, 15_000, "Draining Cut").lore(), on.lore(), "the older entry point is the setting on");
	}

	@Test
	void aPassiveKeepsWhatItCostsAndLosesWhenItIsBilled() {
		Lines eyes = PassiveSettings.of(PassiveSettings.NIGHT_EYES, null).costLines();
		assertEquals("Costs 1.5 biomass a minute, only in the dark.", eyes.text(true));
		assertEquals("Costs 1.5 biomass a minute", eyes.text(false));
		for (String id : PassiveSettings.ALL) {
			Lines l = PassiveSettings.of(id, null).costLines();
			assertTrue(l.text(false).startsWith("Costs "), id + " always says what it costs");
			assertTrue(l.text(false).contains("biomass"), id);
			assertEquals(PassiveSettings.of(id, null).costLine(), l.text(true), id + ": the old one-line text is the setting on");
		}
		assertEquals("Costs 0.05 biomass for each item it fetches.", PassiveSettings.of(PassiveSettings.SCAVENGER, null).costLines().text(false));
	}
}
