package dev.morphreborn.paper.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.survival.SurvivalMenuLayout.Section;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

/**
 * The chest menu's slots for the bundled tree: every upgrade has its own, Close sits top right, Arm Shaping and the door to the
 * Arsenal are on the bottom row, and the Arsenal's twelve tools and four accessories are a page of their own.
 */
class SurvivalMenuLayoutTest {
	/** The bundled upgrades, id to parent, in file order. */
	private static Map<String, String> bundledTree() throws Exception {
		YamlConfiguration yaml = new YamlConfiguration();
		try (Reader r = new InputStreamReader(Objects.requireNonNull(SurvivalMenuLayoutTest.class.getResourceAsStream("/survival.yml")), StandardCharsets.UTF_8)) {
			yaml.load(r);
		}
		ConfigurationSection u = Objects.requireNonNull(yaml.getConfigurationSection("upgrades"));
		Map<String, String> tree = new LinkedHashMap<>();
		for (String id : u.getKeys(false)) tree.put(id, u.getString(id + ".parent", "root"));
		return tree;
	}

	private static Map<String, Integer> assign(Map<String, String> tree) {
		return SurvivalMenuLayout.assign(List.copyOf(tree.keySet()), tree::get);
	}

	private static Map<String, Integer> assignArsenal(Map<String, String> tree) {
		return SurvivalMenuLayout.assignArsenal(List.copyOf(tree.keySet()), tree::get);
	}

	private static Map<String, Integer> assignPassives(Map<String, String> tree) {
		return SurvivalMenuLayout.assignPassives(List.copyOf(tree.keySet()), tree::get);
	}

	/** Slots that hold something other than an upgrade. */
	private static Set<Integer> reserved() {
		Set<Integer> s = new HashSet<>(Set.of(SurvivalMenuLayout.STATUS_SLOT, SurvivalMenuLayout.CLOSE_SLOT, SurvivalMenuLayout.BIOMASS_LABEL,
				SurvivalMenuLayout.MORPH_LABEL, SurvivalMenuLayout.ABILITIES_LABEL, SurvivalMenuLayout.ABILITIES_LABEL_2));
		for (int f : SurvivalMenuLayout.FRAME) s.add(f);
		return s;
	}

	@Test
	void everyBundledUpgradeGetsASlotOfItsOwnOnItsPage() throws Exception {
		Map<String, String> tree = bundledTree();
		Map<String, Integer> slots = assign(tree);
		Map<String, Integer> arsenal = assignArsenal(tree);
		Map<String, Integer> passives = assignPassives(tree);
		List<String> missing = new ArrayList<>(tree.keySet());
		missing.removeAll(slots.keySet());
		missing.removeAll(arsenal.keySet());
		missing.removeAll(passives.keySet());
		assertTrue(missing.isEmpty(), "no room for " + missing);
		assertEquals(slots.size(), new HashSet<>(slots.values()).size(), "two upgrades share a slot: " + slots);
		assertEquals(arsenal.size(), new HashSet<>(arsenal.values()).size(), "two Arsenal forms share a slot: " + arsenal);
		for (Map.Entry<String, Integer> e : slots.entrySet()) {
			assertTrue(e.getValue() >= 0 && e.getValue() < SurvivalMenuLayout.SIZE, e.toString());
			assertFalse(reserved().contains(e.getValue()), e + " is on a label, the frame, the status or Close");
			assertFalse(arsenal.containsKey(e.getKey()), e + " is on both pages");
			assertFalse(passives.containsKey(e.getKey()), e + " is on both pages");
		}
		for (Map.Entry<String, Integer> e : arsenal.entrySet()) {
			assertTrue(e.getValue() >= 0 && e.getValue() < SurvivalMenuLayout.SIZE, e.toString());
			assertFalse(arsenalReserved().contains(e.getValue()), e + " is on a label, Back, the status or Close");
		}
	}

	/** The Passives page is its own: Back, the status, Close, the row labels, and each passive over its switch over its buy button. */
	@Test
	void thePassivesHaveAPageOfTheirOwn() throws Exception {
		Map<String, String> tree = bundledTree();
		Map<String, Integer> main = assign(tree);
		Map<String, Integer> passives = assignPassives(tree);
		assertEquals(8, passives.size(), "the eight bought passives");
		assertEquals(PassiveSettings.ALL, List.copyOf(passives.keySet()), "in the order the command lists them");
		assertTrue(main.keySet().stream().noneMatch(id -> id.startsWith("passive_")), "assign no longer places passive_ on the main page");
		Set<Integer> reserved = Set.of(SurvivalMenuLayout.STATUS_SLOT, SurvivalMenuLayout.CLOSE_SLOT, SurvivalMenuLayout.PASSIVES_BACK_SLOT,
				SurvivalMenuLayout.PASSIVES_LABEL, SurvivalMenuLayout.SWITCH_LABEL, SurvivalMenuLayout.BUY_LABEL);
		Set<Integer> used = new HashSet<>(reserved);
		for (Map.Entry<String, Integer> e : passives.entrySet()) {
			int item = e.getValue();
			assertTrue(item >= 10 && item <= 17, e.toString());
			int toggle = SurvivalMenuLayout.switchSlot(item), buy = SurvivalMenuLayout.buySlot(item);
			assertEquals(item + 9, toggle, "the switch is directly under the passive");
			assertEquals(item + 18, buy, "the buy button under that");
			assertTrue(used.add(item), e + " overlaps");
			assertTrue(used.add(toggle), e + " switch overlaps");
			assertTrue(used.add(buy), e + " buy button overlaps");
			assertTrue(buy < SurvivalMenuLayout.SIZE);
			assertEquals(item, SurvivalMenuLayout.passiveItemSlot(toggle));
			assertEquals(item, SurvivalMenuLayout.passiveItemSlot(buy));
			assertEquals(SurvivalMenuLayout.PassiveRole.ITEM, SurvivalMenuLayout.passiveRole(item));
			assertEquals(SurvivalMenuLayout.PassiveRole.SWITCH, SurvivalMenuLayout.passiveRole(toggle));
			assertEquals(SurvivalMenuLayout.PassiveRole.BUY, SurvivalMenuLayout.passiveRole(buy));
		}
		for (int r : reserved) assertNull(SurvivalMenuLayout.passiveRole(r), r + " is not a passive slot");
		assertEquals(0, SurvivalMenuLayout.PASSIVES_BACK_SLOT);
	}

	/** The door sits beside the Arsenal's on the bottom row and nothing else is there. */
	@Test
	void thePassivesDoorIsFreeOnTheMainPage() throws Exception {
		assertEquals(47, SurvivalMenuLayout.PASSIVES_DOOR_SLOT);
		assertEquals(SurvivalMenuLayout.ARSENAL_DOOR_SLOT + 1, SurvivalMenuLayout.PASSIVES_DOOR_SLOT);
		Map<String, Integer> main = assign(bundledTree());
		assertFalse(main.containsValue(SurvivalMenuLayout.PASSIVES_DOOR_SLOT));
		assertFalse(reserved().contains(SurvivalMenuLayout.PASSIVES_DOOR_SLOT));
		// the stand-in slots the passives used to take are filler again
		for (int slot : new int[] {0, 1, 2, 3, 5, 6, 26, 35, 44}) assertFalse(main.containsValue(slot), "slot " + slot);
	}

	@Test
	void aClickDoesWhatTheOwnerAsked() {
		SurvivalMenuLayout.PassiveRole item = SurvivalMenuLayout.PassiveRole.ITEM, sw = SurvivalMenuLayout.PassiveRole.SWITCH, buy = SurvivalMenuLayout.PassiveRole.BUY;
		// left click on a bought one switches it (all a Bedrock player has); on one not bought it buys
		assertEquals(SurvivalMenuLayout.PassiveAction.TOGGLE, SurvivalMenuLayout.passiveAction(item, true, false));
		assertEquals(SurvivalMenuLayout.PassiveAction.BUY, SurvivalMenuLayout.passiveAction(item, false, false));
		// right or shift click buys the next level
		assertEquals(SurvivalMenuLayout.PassiveAction.BUY, SurvivalMenuLayout.passiveAction(item, true, true));
		// the pane under it switches a bought one and leaves one not bought alone
		assertEquals(SurvivalMenuLayout.PassiveAction.TOGGLE, SurvivalMenuLayout.passiveAction(sw, true, false));
		assertEquals(SurvivalMenuLayout.PassiveAction.TOGGLE, SurvivalMenuLayout.passiveAction(sw, true, true));
		assertEquals(SurvivalMenuLayout.PassiveAction.NONE, SurvivalMenuLayout.passiveAction(sw, false, false));
		// the buy button always buys, so a player with no right click can still level Scavenger up
		assertEquals(SurvivalMenuLayout.PassiveAction.BUY, SurvivalMenuLayout.passiveAction(buy, true, false));
		assertEquals(SurvivalMenuLayout.PassiveAction.BUY, SurvivalMenuLayout.passiveAction(buy, false, false));
	}

	@Test
	void onlyTheMouseButtonsDoAnythingOnThePassivesPage() {
		for (ClickType t : new ClickType[] {ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT}) assertTrue(SurvivalMenu.passiveClick(t), t.name());
		// a double click would switch twice, a number key or a drop is not a deliberate click
		for (ClickType t : new ClickType[] {ClickType.DOUBLE_CLICK, ClickType.NUMBER_KEY, ClickType.DROP, ClickType.CONTROL_DROP, ClickType.SWAP_OFFHAND, ClickType.MIDDLE}) {
			assertFalse(SurvivalMenu.passiveClick(t), t.name());
		}
	}

	/** An upgrade with a page of its own is not warned about on the main page (the 16 WARNs of the first menu open); one with no place anywhere still is. */
	@Test
	void theMainPageDoesNotWarnAboutWhatLivesOnAnotherPage() throws Exception {
		Map<String, String> tree = bundledTree();
		tree.put("arm_custom", SurvivalConfig.ARM_KATANA);
		tree.put("passive_custom", SurvivalConfig.BIOMASS_CAPACITY);
		Function<String, String> parentOf = tree::get;
		for (String id : SurvivalMenuLayout.ARSENAL_SLOTS.keySet()) assertTrue(SurvivalMenuLayout.onOtherPage(id, parentOf), id);
		for (String id : SurvivalMenuLayout.PASSIVE_SLOTS.keySet()) assertTrue(SurvivalMenuLayout.onOtherPage(id, parentOf), id);
		assertFalse(SurvivalMenuLayout.onOtherPage("arm_custom", parentOf), "no slot anywhere: still news");
		assertFalse(SurvivalMenuLayout.onOtherPage("passive_custom", parentOf), "no slot anywhere: still news");
		assertFalse(SurvivalMenuLayout.onOtherPage(SurvivalConfig.BIOMASS_CAPACITY, parentOf));
		// across the whole bundled tree: everything the main page leaves out lives on another page
		Map<String, String> bundled = bundledTree();
		Map<String, Integer> main = assign(bundled);
		for (String id : bundled.keySet()) {
			if (!main.containsKey(id)) assertTrue(SurvivalMenuLayout.onOtherPage(id, bundled::get), id + " has no slot on the main page and none elsewhere");
		}
	}

	/** Slots on the Arsenal page that hold something other than a form. */
	private static Set<Integer> arsenalReserved() {
		return Set.of(SurvivalMenuLayout.STATUS_SLOT, SurvivalMenuLayout.CLOSE_SLOT, SurvivalMenuLayout.ARSENAL_BACK_SLOT, SurvivalMenuLayout.TOOLS_LABEL,
				SurvivalMenuLayout.ACCESSORIES_LABEL);
	}

	@Test
	void closeSitsTopRightAndTheArsenalHasAPageOfItsOwn() throws Exception {
		assertEquals(8, SurvivalMenuLayout.CLOSE_SLOT);
		Map<String, String> tree = bundledTree();
		Map<String, Integer> slots = assign(tree);
		assertEquals(16, slots.get(SurvivalConfig.MORPH_ABILITY));
		assertEquals(45, slots.get(SurvivalConfig.ARM_SHAPING));
		assertEquals(46, SurvivalMenuLayout.ARSENAL_DOOR_SLOT, "the door sits beside Arm Shaping");
		// the main page's bottom row is only Arm Shaping (and the door), and the Arsenal's forms are not on it
		for (Map.Entry<String, Integer> e : slots.entrySet()) {
			assertEquals(e.getKey().equals(SurvivalConfig.ARM_SHAPING), e.getValue() >= 45, e.toString());
		}
		assertTrue(slots.keySet().stream().noneMatch(id -> SurvivalMenuLayout.ARSENAL_SLOTS.containsKey(id)));
		// the Arsenal page: the old seven tools where they were listed, then the five new ones, then the four accessories
		Map<String, Integer> arsenal = assignArsenal(tree);
		assertEquals(16, arsenal.size(), "twelve tools and four accessories");
		assertEquals(List.of(10, 11, 12, 13, 14, 15, 16), List.of(arsenal.get(SurvivalConfig.ARM_KATANA), arsenal.get(SurvivalConfig.ARM_PICKAXE),
				arsenal.get(SurvivalConfig.ARM_AXE), arsenal.get(SurvivalConfig.ARM_SHOVEL), arsenal.get(SurvivalConfig.ARM_SICKLE),
				arsenal.get(SurvivalConfig.ARM_BOW), arsenal.get(SurvivalConfig.ARM_WHIP)), "katana to whip, as survival.yml lists them");
		assertEquals(List.of(19, 20, 21, 22, 23), List.of(arsenal.get(SurvivalConfig.ARM_SPEAR), arsenal.get(SurvivalConfig.ARM_MACE),
				arsenal.get(SurvivalConfig.ARM_TRIDENT), arsenal.get(SurvivalConfig.ARM_CROSSBOW), arsenal.get(SurvivalConfig.ARM_CLAWS)), "the new tools' row");
		assertEquals(List.of(28, 29, 30, 31), List.of(arsenal.get(SurvivalConfig.ARM_LANTERN), arsenal.get(SurvivalConfig.ARM_SHIELD),
				arsenal.get(SurvivalConfig.ARM_SPYGLASS), arsenal.get(SurvivalConfig.ARM_FLINT)), "the accessories' row, the lantern first");
		assertEquals(0, SurvivalMenuLayout.ARSENAL_BACK_SLOT);
		// the rows that existed before keep their slots
		assertEquals(10, slots.get(SurvivalConfig.BIOMASS_CAPACITY));
		assertEquals(19, slots.get(SurvivalConfig.MORPH_CAPACITY));
		assertEquals(24, slots.get(SurvivalConfig.RAGE));
	}

	@Test
	void anUnbuiltFormLeavesItsSlotEmpty() throws Exception {
		Map<String, String> tree = bundledTree();
		tree.remove(SurvivalConfig.ARM_AXE); // not offered: its code isn't in this build
		tree.remove(SurvivalConfig.ARM_SHOVEL);
		tree.remove(SurvivalConfig.ARM_SPYGLASS); // nor is a new accessory's, which is how the new forms are hidden until they are built
		tree.remove(SurvivalConfig.ARM_TRIDENT);
		Map<String, Integer> slots = assignArsenal(tree);
		assertNull(slots.get(SurvivalConfig.ARM_AXE));
		assertNull(slots.get(SurvivalConfig.ARM_SPYGLASS));
		assertFalse(slots.containsValue(12));
		assertFalse(slots.containsValue(13));
		assertFalse(slots.containsValue(21));
		assertFalse(slots.containsValue(30));
		assertEquals(14, slots.get(SurvivalConfig.ARM_SICKLE), "the others don't move along");
		assertEquals(22, slots.get(SurvivalConfig.ARM_CROSSBOW));
		assertEquals(28, slots.get(SurvivalConfig.ARM_LANTERN));
		assertEquals(31, slots.get(SurvivalConfig.ARM_FLINT), "flint keeps its slot with the spyglass gone");
	}

	@Test
	void sectionsGoByAncestry() throws Exception {
		Map<String, String> tree = bundledTree();
		tree.put("arm_custom", SurvivalConfig.ARM_KATANA); // an admin's own upgrade under a form
		Function<String, String> parentOf = tree::get;
		assertEquals(Section.MORPH_ABILITY, SurvivalMenuLayout.section(SurvivalConfig.MORPH_ABILITY, parentOf));
		assertEquals(Section.ARM_SHAPING, SurvivalMenuLayout.section(SurvivalConfig.ARM_SHAPING, parentOf), "not Morph, though it hangs off Morph Ability");
		assertEquals(Section.ARSENAL, SurvivalMenuLayout.section(SurvivalConfig.ARM_KATANA, parentOf));
		assertEquals(Section.ARSENAL, SurvivalMenuLayout.section(SurvivalConfig.ARM_LANTERN, parentOf));
		assertEquals(Section.ARSENAL, SurvivalMenuLayout.section(SurvivalConfig.ARM_SPEAR, parentOf));
		assertEquals(Section.ARSENAL, SurvivalMenuLayout.section(SurvivalConfig.ARM_FLINT, parentOf));
		assertEquals(Section.ARSENAL, SurvivalMenuLayout.section("arm_custom", parentOf), "anything below Arm Shaping");
		assertEquals(Section.MORPH, SurvivalMenuLayout.section(SurvivalConfig.RAGE, parentOf));
		assertEquals(Section.MORPH, SurvivalMenuLayout.section(SurvivalConfig.MORPH_VARIANT_CAPACITY, parentOf));
		assertEquals(Section.ABILITIES, SurvivalMenuLayout.section(SurvivalConfig.MORPH_FLIGHT, parentOf));
		assertEquals(Section.BIOMASS, SurvivalMenuLayout.section(SurvivalConfig.BIOMASS_REACH, parentOf));
		assertEquals(Section.BIOMASS, SurvivalMenuLayout.section("not_in_the_tree", parentOf));
		// an Arsenal upgrade without a slot of its own has no room, on either page
		assertNull(SurvivalMenuLayout.assign(List.copyOf(tree.keySet()), parentOf).get("arm_custom"));
		assertNull(SurvivalMenuLayout.assignArsenal(List.copyOf(tree.keySet()), parentOf).get("arm_custom"));
	}

	@Test
	void aFullRowLeavesTheRestOut() {
		Map<String, String> tree = new LinkedHashMap<>();
		for (int i = 0; i < 7; i++) tree.put("store_" + i, "root");
		Map<String, Integer> slots = assign(tree);
		assertEquals(List.of(10, 11, 12, 13, 14), List.copyOf(slots.values()), "five Biomass slots, in file order");
		assertFalse(slots.containsKey("store_5"));
		assertFalse(slots.containsKey("store_6"));
	}
}
