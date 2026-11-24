package dev.morphreborn.client.arm.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.gui.UpgradeTree;
import dev.morphreborn.client.gui.UpgradeTreeScreen.Panel;
import dev.morphreborn.client.gui.UpgradeTreeScreen.Section;
import dev.morphreborn.client.gui.UpgradeTreeScreen.TreeLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The upgrade tree's layout with the Arsenal (section 5.3 of the arm forms' spec): Arm Shaping and the Arsenal grid in
 * the upper row, its span, and the Abilities beside it in a short window or below it in a tall one, on the tree
 * survival.yml ships with. Also the section rule the server's chest menu shares, and that a tree without arm forms
 * (an older server) is laid out exactly as before.
 */
class ArsenalLayoutTest {
	/** 1080p at GUI scale 3 (the window keeps an 8 px margin, 2026-11-18), and a window tall enough for the Abilities below. */
	private static final int SHORT_W = 640, SHORT_H = 360;
	private static final int TALL_W = 640, TALL_H = 480;

	/** The bundled survival.yml's upgrades, in its order: id and parent. */
	private static final String[][] BUNDLED = {
			{"biomass_capacity", "root"}, {"biomass_efficiency", "biomass_capacity"}, {"biomass_reach", "biomass_capacity"},
			{"biomass_max_mass_absorbable", "biomass_capacity"},
			{"morph_ability", "root"}, {"morph_capacity", "morph_ability"}, {"morph_variant_capacity", "morph_capacity"},
			{"morph_efficiency", "morph_ability"}, {"morph_abilities", "morph_ability"}, {"multimorph", "morph_ability"}, {"rage", "morph_ability"},
			{"ability_night_vision", "morph_abilities"}, {"ability_water_breathing", "morph_abilities"}, {"ability_swift_swimming", "morph_abilities"},
			{"ability_fire_immunity", "morph_abilities"}, {"ability_soft_landing", "morph_abilities"}, {"morph_flight", "morph_abilities"},
			{"ability_climbing", "morph_abilities"}, {"ability_leaping", "morph_abilities"}, {"ability_mob_strength", "morph_abilities"},
			{"ability_sure_footing", "morph_abilities"}, {"ability_defences", "morph_abilities"}, {"ability_attack_effects", "morph_abilities"},
			{"ability_camouflage", "morph_abilities"},
			{"arm_shaping", "morph_ability"}, {"arm_katana", "arm_shaping"}, {"arm_pickaxe", "arm_shaping"}, {"arm_axe", "arm_shaping"},
			{"arm_shovel", "arm_shaping"}, {"arm_sickle", "arm_shaping"}, {"arm_bow", "arm_shaping"}, {"arm_whip", "arm_shaping"},
			{"arm_lantern", "arm_shaping"}};

	private static UpgradeTree.Node node(String id, String parent) {
		return new UpgradeTree.Node(id, id, "", "minecraft:paper", parent, 0, 1, "", "", 1, "", true);
	}

	private static List<UpgradeTree.Node> tree(boolean withArms) {
		List<UpgradeTree.Node> nodes = new ArrayList<>();
		for (String[] row : BUNDLED) {
			if (!withArms && row[0].startsWith("arm_")) continue;
			nodes.add(node(row[0], row[1]));
		}
		return nodes;
	}

	/** The twelve tools and four accessories (2026-11-16): the Arsenal's sixteen nodes under Arm Shaping. */
	private static final String[] NEW_ARMS = {"arm_spear", "arm_mace", "arm_trident", "arm_crossbow", "arm_claws", "arm_shield", "arm_spyglass", "arm_flint"};

	@Test
	void sixteenArsenalNodesGoFourAcrossAndEightStayThreeAcross() {
		List<UpgradeTree.Node> nodes = tree(true);
		for (String id : NEW_ARMS) nodes.add(node(id, "arm_shaping"));
		TreeLayout layout = TreeLayout.of(nodes, TALL_W, TALL_H);
		Panel arsenal = layout.panel(Section.ARSENAL);
		assertNotNull(arsenal);
		assertEquals(106 + 32, arsenal.w(), "four columns: one more 32 px grid step than the eight forms' three");
		java.util.Set<String> places = new java.util.HashSet<>();
		int maxX = 0, maxY = 0;
		for (UpgradeTree.Node n : nodes) {
			if (layout.section(n.id()) != Section.ARSENAL) continue;
			int[] at = layout.position(n.id());
			assertNotNull(at, n.id());
			assertTrue(places.add(at[0] + "," + at[1]), n.id() + " has a place of its own");
			assertTrue(at[0] >= arsenal.x() && at[0] + at[2] <= arsenal.x() + arsenal.w(), n.id() + " inside the panel across");
			assertTrue(at[1] >= arsenal.y() && at[1] + at[2] <= arsenal.y() + arsenal.h(), n.id() + " inside the panel down");
			maxX = Math.max(maxX, at[0]);
			maxY = Math.max(maxY, at[1]);
		}
		assertEquals(16, places.size());
		assertEquals(4, places.stream().map(p -> p.split(",")[0]).distinct().count(), "four columns");
		assertEquals(4, places.stream().map(p -> p.split(",")[1]).distinct().count(), "four rows");
		// the original eight (and a server with none) are laid out exactly as before
		assertEquals(106, TreeLayout.of(tree(true), TALL_W, TALL_H).panel(Section.ARSENAL).w());
	}

	@Test
	void upperRowSpansThreeHundredEightySix() {
		TreeLayout layout = TreeLayout.of(tree(true), TALL_W, TALL_H);
		assertTrue(layout.sectioned());
		assertTrue(layout.armRow());
		// 64 + 22 + 32 + 22 + 64 + 22 + 32 + 22 + 106 (the gaps were 34 until 2026-11-18)
		assertEquals(386, layout.upperSpan());
		Panel arsenal = layout.panel(Section.ARSENAL);
		assertNotNull(arsenal);
		assertEquals(106, arsenal.w(), "three columns");
		assertEquals(112, arsenal.h(), "gridHeight(3) for eight forms");
		Panel biomass = layout.panel(Section.BIOMASS);
		assertNotNull(biomass);
		assertEquals(biomass.x() + layout.upperSpan(), arsenal.x() + arsenal.w(), "the Arsenal ends the row");
		// hung from the row's top, as the other panels are (2026-11-18: centred on the tallest, the trunk was off the first screen)
		assertEquals(layout.upperTop(), arsenal.y());
		assertEquals(layout.upperTop(), biomass.y());
		assertEquals(layout.upperTop(), layout.panel(Section.MORPH).y());
		int[] shaping = layout.position("arm_shaping");
		assertNotNull(shaping);
		assertEquals(32, shaping[2], "Arm Shaping is an orb");
		Panel morph = layout.panel(Section.MORPH);
		assertNotNull(morph);
		assertEquals(morph.x() + morph.w() + 22, shaping[0]);
		assertEquals(shaping[0] + 32 + 22, arsenal.x());
	}

	@Test
	void besideFitsTenEightyAtGuiScaleThree() {
		TreeLayout layout = TreeLayout.of(tree(true), SHORT_W, SHORT_H);
		assertEquals(606, TreeLayout.maxInsideWidth(SHORT_W));
		assertEquals(317, TreeLayout.maxInsideHeight(SHORT_H));
		assertTrue(layout.abilitiesBeside(), "too short for the Abilities below");
		assertEquals(510, layout.contentW());
		assertEquals(268, layout.contentH());
		assertTrue(layout.contentW() <= TreeLayout.maxInsideWidth(SHORT_W) && layout.contentH() <= TreeLayout.maxInsideHeight(SHORT_H), "no scrolling");
		Panel abilities = layout.panel(Section.ABILITIES);
		Panel arsenal = layout.panel(Section.ARSENAL);
		assertNotNull(abilities);
		assertNotNull(arsenal);
		assertEquals(arsenal.x() + arsenal.w() + 22, abilities.x(), "at the end of the upper row, after the Arsenal");
		assertEquals(74, abilities.w(), "two columns");
		assertEquals(layout.contentW() - 14, abilities.x() + abilities.w());
		// the rerouted link runs under the upper row, inside the content
		assertTrue(layout.upperBottom() + 6 < layout.contentH());
	}

	@Test
	void belowInATallWindow() {
		TreeLayout layout = TreeLayout.of(tree(true), TALL_W, TALL_H);
		assertFalse(layout.abilitiesBeside());
		assertEquals(414, layout.contentW());
		assertEquals(328, layout.contentH());
		Panel abilities = layout.panel(Section.ABILITIES);
		assertNotNull(abilities);
		assertTrue(abilities.y() >= layout.upperBottom() + 22);
		// before the screen knows its size the Abilities go below too
		TreeLayout early = TreeLayout.of(tree(true), 0, 0);
		assertFalse(early.abilitiesBeside());
		assertEquals(414, early.contentW());
	}

	@Test
	void withoutArmFormsTheTreeIsAsBefore() {
		TreeLayout tall = TreeLayout.of(tree(false), TALL_W, TALL_H);
		assertFalse(tall.armRow());
		assertNull(tall.panel(Section.ARSENAL));
		assertNull(tall.position("arm_shaping"));
		assertEquals(204, tall.upperSpan());
		assertEquals(262, tall.contentW());
		assertEquals(328, tall.contentH());
		TreeLayout shortWindow = TreeLayout.of(tree(false), SHORT_W, SHORT_H);
		assertTrue(shortWindow.abilitiesBeside());
		assertEquals(328, shortWindow.contentW());
		assertEquals(268, shortWindow.contentH());
		Panel morph = shortWindow.panel(Section.MORPH);
		Panel abilities = shortWindow.panel(Section.ABILITIES);
		assertNotNull(morph);
		assertNotNull(abilities);
		assertEquals(morph.x() + morph.w() + 22, abilities.x(), "right after the Morph section, as before");
	}

	@Test
	void everyArsenalFormSitsInTheArsenalGrid() {
		TreeLayout layout = TreeLayout.of(tree(true), SHORT_W, SHORT_H);
		Panel arsenal = layout.panel(Section.ARSENAL);
		assertNotNull(arsenal);
		Map<String, int[]> placed = new HashMap<>();
		for (String[] row : BUNDLED) {
			int[] p = layout.position(row[0]);
			assertNotNull(p, row[0] + " is placed");
			placed.put(row[0], p);
			if (!row[0].startsWith("arm_") || row[0].equals("arm_shaping")) continue;
			assertEquals(Section.ARSENAL, layout.section(row[0]));
			assertTrue(p[0] >= arsenal.x() && p[0] + p[2] <= arsenal.x() + arsenal.w(), row[0] + " inside the Arsenal across");
			assertTrue(p[1] >= arsenal.y() + 14 && p[1] + p[2] <= arsenal.y() + arsenal.h(), row[0] + " inside the Arsenal down, under its title");
		}
		// no two upgrades overlap
		List<String> ids = new ArrayList<>(placed.keySet());
		for (int i = 0; i < ids.size(); i++) {
			for (int j = i + 1; j < ids.size(); j++) {
				int[] a = placed.get(ids.get(i));
				int[] b = placed.get(ids.get(j));
				boolean apart = a[0] + a[2] <= b[0] || b[0] + b[2] <= a[0] || a[1] + a[2] <= b[1] || b[1] + b[2] <= a[1];
				assertTrue(apart, ids.get(i) + " overlaps " + ids.get(j));
			}
		}
	}

	@Test
	void sectionsFollowTheServersRule() {
		Map<String, String> parents = new HashMap<>();
		for (String[] row : BUNDLED) parents.put(row[0], row[1]);
		parents.put("arm_custom", "arm_katana"); // an admin's own upgrade under a form
		parents.put("orphan", "missing_parent");
		assertEquals(Section.MORPH_ABILITY, TreeLayout.sectionOf("morph_ability", parents::get));
		assertEquals(Section.ARM_SHAPING, TreeLayout.sectionOf("arm_shaping", parents::get), "before the Morph Ability's walk");
		assertEquals(Section.ARSENAL, TreeLayout.sectionOf("arm_katana", parents::get));
		assertEquals(Section.ARSENAL, TreeLayout.sectionOf("arm_lantern", parents::get));
		assertEquals(Section.ARSENAL, TreeLayout.sectionOf("arm_custom", parents::get));
		assertEquals(Section.ABILITIES, TreeLayout.sectionOf("morph_flight", parents::get));
		assertEquals(Section.MORPH, TreeLayout.sectionOf("rage", parents::get));
		assertEquals(Section.MORPH, TreeLayout.sectionOf("morph_variant_capacity", parents::get));
		assertEquals(Section.MORPH, TreeLayout.sectionOf("morph_abilities", parents::get));
		assertEquals(Section.BIOMASS, TreeLayout.sectionOf("biomass_reach", parents::get));
		assertEquals(Section.BIOMASS, TreeLayout.sectionOf("orphan", parents::get));
	}

	@Test
	void anArsenalWithoutArmShapingStillHasItsPanel() {
		// a server that sends forms but not Arm Shaping (never the bundled tree): the grid follows Morph, no orb
		List<UpgradeTree.Node> nodes = new ArrayList<>();
		for (UpgradeTree.Node n : tree(true)) if (!n.id().equals("arm_shaping")) nodes.add(n);
		TreeLayout layout = TreeLayout.of(nodes, TALL_W, TALL_H);
		assertNull(layout.position("arm_shaping"));
		assertNotNull(layout.panel(Section.ARSENAL));
		assertEquals(204 + 22 + 106, layout.upperSpan());
	}
}
