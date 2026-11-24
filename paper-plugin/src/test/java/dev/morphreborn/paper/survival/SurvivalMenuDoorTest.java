package dev.morphreborn.paper.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The Arsenal door's lore names only the forms this build has (2026-11-16). */
class SurvivalMenuDoorTest {
	@Test
	void listsOnlyWhatIsGiven() {
		List<String> lines = SurvivalMenu.arsenalDoorLines(List.of("katana", "pickaxe"), List.of("lantern"));
		String all = String.join(" ", lines);
		assertTrue(all.contains("Tools: katana, pickaxe."), all);
		assertTrue(all.contains("Accessories: lantern."), all);
		for (String unbuilt : List.of("spear", "mace", "trident", "crossbow", "claws", "shield", "spyglass", "flint")) assertFalse(all.contains(unbuilt), unbuilt);
	}

	@Test
	void anEmptyListLeavesItsLineOut() {
		assertEquals(List.of("Tools: katana."), SurvivalMenu.arsenalDoorLines(List.of("katana"), List.of()));
		assertEquals(List.of("Accessories: lantern."), SurvivalMenu.arsenalDoorLines(List.of(), List.of("lantern")));
		assertEquals(List.of(), SurvivalMenu.arsenalDoorLines(List.of(), List.of()));
	}

	@Test
	void longListsWrap() {
		List<String> lines = SurvivalMenu.arsenalDoorLines(List.of("katana", "pickaxe", "axe", "shovel", "sickle", "bow", "whip", "spear", "mace"), List.of());
		assertTrue(lines.size() > 1);
		for (String l : lines) assertTrue(l.length() <= 40, l);
	}
}
