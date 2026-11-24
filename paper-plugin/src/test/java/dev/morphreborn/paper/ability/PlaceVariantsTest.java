package dev.morphreborn.paper.ability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Same ability on two forms: a water/land variant follows the place (2026-11-17), anything else keeps the main form's. */
class PlaceVariantsTest {
	private static final Map<String, String> ABILITY = Map.of(
			"camel", "dash", "camel_husk", "dash", "dolphin", "dash", "nautilus", "dash",
			"skeleton", "arrow", "bogged", "arrow", "guardian", "beam", "ram", "ram");
	private static final Map<String, Set<String>> WATER = Map.of("dash", Set.of("dolphin", "nautilus"));

	private static String pick(boolean inWater, String... forms) {
		return PlaceVariants.pick(List.of(forms), ABILITY::get, WATER, inWater);
	}

	@Test
	void camelAndDolphinDashByPlaceWhicheverIsMain() {
		assertEquals("camel", pick(false, "camel", "dolphin"));
		assertEquals("dolphin", pick(true, "camel", "dolphin"));
		assertEquals("camel", pick(false, "dolphin", "camel"));
		assertEquals("dolphin", pick(true, "dolphin", "camel"));
	}

	@Test
	void huskAndNautilusLikewise() {
		assertEquals("camel_husk", pick(false, "nautilus", "camel_husk"));
		assertEquals("nautilus", pick(true, "camel_husk", "nautilus"));
	}

	@Test
	void twoLandOrTwoWaterVariantsKeepTheMainForm() {
		assertEquals("camel", pick(false, "camel", "camel_husk"));
		assertEquals("camel", pick(true, "camel", "camel_husk")); // no water dash among them: the land one as for a lone camel
		assertEquals("camel_husk", pick(true, "camel_husk", "camel"));
		assertEquals("dolphin", pick(true, "dolphin", "nautilus"));
		assertEquals("nautilus", pick(false, "nautilus", "dolphin")); // on land neither works: main form, which refuses as before
	}

	@Test
	void aLoneFormIsItself() {
		assertEquals("dolphin", pick(false, "dolphin"));
		assertEquals("camel", pick(true, "camel"));
	}

	@Test
	void placeIndependentSameNamePairsKeepTheMainFormFirst() {
		assertEquals("skeleton", pick(true, "skeleton", "bogged"));
		assertEquals("bogged", pick(false, "bogged", "skeleton"));
		assertEquals("guardian", pick(true, "guardian", "guardian"));
	}

	@Test
	void aFormWithoutTheAbilityIsSkippedAndNoneIsNull() {
		assertEquals("dolphin", pick(true, "zombie", "dolphin"));
		assertEquals("camel", pick(false, "zombie", "camel", "dolphin"));
		assertNull(pick(false, "zombie", "cow"));
	}

	@Test
	void anotherAbilityOnTheMainFormIsNotSwappedForADash() {
		// a main form with its own ability keeps it: the place only chooses among variants of the same one
		assertEquals("skeleton", pick(true, "skeleton", "dolphin"));
		assertEquals("ram", pick(false, "ram", "camel"));
	}
}
