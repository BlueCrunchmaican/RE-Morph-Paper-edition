package dev.morphreborn.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.survival.SurvivalConfig;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** How an admin's old config.yml or survival.yml is brought up to a newer release (MorphRebornPlugin.mergeUpgrade). */
class ConfigMergeTest {
	private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
		YamlConfiguration config = new YamlConfiguration();
		config.loadFromString(text);
		return config;
	}

	private static final String TEMPLATE = """
			# header

			config-version: 6.1.4

			# the mode
			gameplay-mode: classic

			morphs:
			  # ticks
			  transition-ticks: 100
			  disabled-types:
			    - minecraft:armor_stand
			  required-kills-per-type: {}

			attributes:
			  enabled: true
			  list:
			    minecraft:max_health: { direction: more, cap: 20 }
			    minecraft:jump_strength: { direction: more, cap: 0.7 }
			""";

	@Test
	void addsNewSettingsAndKeepsEveryValueTheAdminSet() throws Exception {
		YamlConfiguration template = yaml(TEMPLATE);
		YamlConfiguration admin = yaml("""
				morphs:
				  transition-ticks: 40
				  disabled-types: []
				  required-kills-per-type:
				    minecraft:wither: 3
				attributes:
				  enabled: false
				  list:
				    minecraft:max_health: { direction: more }
				""");
		List<String> added = ConfigMerge.merge(template, admin, "config-version");

		assertEquals(List.of("gameplay-mode", "attributes.list.minecraft:jump_strength"), added);
		assertEquals("classic", template.getString("gameplay-mode"));
		assertEquals(40, template.getInt("morphs.transition-ticks"));
		assertTrue(template.getList("morphs.disabled-types").isEmpty(), "an emptied list stays empty");
		assertEquals(3, template.getInt("morphs.required-kills-per-type.minecraft:wither"));
		assertFalse(template.getBoolean("attributes.enabled"));
		assertFalse(template.contains("attributes.list.minecraft:max_health.cap"), "an admin's entry isn't completed with default fields");
		assertEquals(0.7, template.getDouble("attributes.list.minecraft:jump_strength.cap"));
		assertEquals("6.1.4", template.getString("config-version"));
	}

	@Test
	void keepsUnknownKeysAndTheTemplatesComments() throws Exception {
		YamlConfiguration template = yaml(TEMPLATE);
		YamlConfiguration admin = yaml("""
				config-version: 6.1.2
				old-setting: 5
				morphs:
				  transition-ticks: 60
				""");
		ConfigMerge.merge(template, admin, "config-version");
		String out = template.saveToString();

		assertEquals(5, template.getInt("old-setting"));
		assertEquals("6.1.4", template.getString("config-version"), "the version always comes from the release");
		assertTrue(out.contains("# the mode"), out);
		assertTrue(out.contains("# ticks"), out);
		assertTrue(out.indexOf("gameplay-mode") < out.indexOf("morphs:"), "new settings sit where the release puts them");
	}

	@Test
	void anOldConfigFromTheFirstReleaseGetsEverySettingAddedSince() throws Exception {
		YamlConfiguration bundled = new YamlConfiguration();
		try (Reader reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")), StandardCharsets.UTF_8)) {
			bundled.load(reader);
		}
		// What the first release's config.yml lacked, with a few changed values.
		YamlConfiguration admin = yaml(bundled.saveToString());
		admin.set("config-version", null);
		admin.set("gameplay-mode", null);
		admin.set("world-effects.bee-sting-kills-user", null);
		admin.set("attributes.min-max-health", null);
		admin.set("attributes.health-overrides", null);
		admin.set("attributes.list.minecraft:jump_strength", null);
		admin.set("morphs.transition-ticks", 40);
		admin.set("debug", true);

		List<String> added = ConfigUpgrade.upgrade(bundled, admin, MorphRebornPlugin.SURVIVAL_REPLACED).added();

		assertEquals(List.of("gameplay-mode", "world-effects.bee-sting-kills-user", "attributes.list.minecraft:jump_strength",
				"attributes.min-max-health", "attributes.health-overrides"), added);
		assertEquals(40, bundled.getInt("morphs.transition-ticks"));
		assertTrue(bundled.getBoolean("debug"));
		assertEquals(20, bundled.getInt("attributes.health-overrides.minecraft:creaking"));
	}

	private static YamlConfiguration bundled(String name) throws Exception {
		YamlConfiguration bundled = new YamlConfiguration();
		try (Reader reader = new InputStreamReader(Objects.requireNonNull(ConfigMergeTest.class.getResourceAsStream("/" + name)), StandardCharsets.UTF_8)) {
			bundled.load(reader);
		}
		return bundled;
	}

	/**
	 * A server's survival.yml from 7.4.0 (the 7.5.0 release shipped the same file), with prices the admin changed and an
	 * upgrade of their own, updated by this release (MorphRebornPlugin.mergeUpgrade): the Arsenal and arm-cost arrive
	 * with their defaults, and nothing the admin set changes. No section is replaced: SURVIVAL_REPLACED names nothing
	 * newer than 7.4.0.
	 */
	@Test
	void aSurvivalFileFrom740GainsTheArsenalAndKeepsTheAdminsEdits() throws Exception {
		YamlConfiguration bundled = bundled("survival.yml");
		String release = bundled.getString("config-version");
		YamlConfiguration admin = yaml(bundled.saveToString());
		List<String> arms = new ArrayList<>();
		for (String id : Objects.requireNonNull(admin.getConfigurationSection("upgrades")).getKeys(false)) {
			if (id.startsWith("arm_")) arms.add(id);
		}
		for (String id : arms) admin.set("upgrades." + id, null);
		admin.set("arm-cost", null);
		admin.set("config-version", "7.4.0");
		// the admin's own changes
		admin.set("morph-cost.per-kg", 0.02);
		admin.set("upgrades.rage.cost", List.of(1000, 3000, 8000));
		admin.set("upgrades.morph_ability.cost", List.of(100));
		admin.createSection("upgrades.ability_glowing", Map.of("name", "Glowing", "parent", "morph_abilities", "max-level", 1, "cost", List.of(250),
				"traits", List.of("effect:glowing")));
		assertTrue(MorphRebornPlugin.compareVersions("7.4.0", Objects.requireNonNull(release)) < 0, "this release's file is newer, so it is merged");

		List<String> added = ConfigUpgrade.upgrade(bundled, admin, MorphRebornPlugin.SURVIVAL_REPLACED).added();

		List<String> expected = new ArrayList<>();
		expected.add("arm-cost");
		for (String id : arms) expected.add("upgrades." + id);
		assertEquals(expected, added, "what the console lists as new settings");
		assertEquals(17, arms.size(), "Arm Shaping, twelve tools and four accessories");
		assertEquals(release, bundled.getString("config-version"));
		// the Arsenal and its costs, at their defaults
		assertEquals("morph_ability", bundled.getString("upgrades.arm_shaping.parent"));
		assertEquals("arm_shaping", bundled.getString("upgrades.arm_katana.parent"));
		assertEquals(List.of(400, 1500, 5000), bundled.getIntegerList("upgrades.arm_katana.cost"));
		assertEquals(0.1, bundled.getDouble("arm-cost.per-block"), 1e-9);
		assertEquals(List.of(12, 15, 18), bundled.getIntegerList("arm-cost.specials.katana"));
		// the whip's node and its cost rows arrive too (SurvivalConfig.KNOWN lists it, so a file still without it is warned about)
		assertTrue(arms.contains(SurvivalConfig.ARM_WHIP), "the whip is one of the arm nodes the old file lacked");
		assertTrue(added.contains("upgrades." + SurvivalConfig.ARM_WHIP));
		assertEquals("arm_shaping", bundled.getString("upgrades.arm_whip.parent"));
		assertEquals(3, bundled.getInt("upgrades.arm_whip.max-level"));
		assertEquals(List.of(0.25, 0.25, 0.25), bundled.getDoubleList("arm-cost.specials.whip-cast"));
		assertEquals(List.of(0.5, 0.5, 0.5), bundled.getDoubleList("arm-cost.specials.whip-pull"));
		assertEquals(List.of(0.5, 0.5, 0.5), bundled.getDoubleList("arm-cost.specials.whip-yank"));
		assertTrue(SurvivalConfig.KNOWN.containsAll(arms), "every arm node in the file is a known upgrade");
		// the admin's edits and own upgrade
		assertEquals(0.02, bundled.getDouble("morph-cost.per-kg"), 1e-9);
		assertEquals(List.of(1000, 3000, 8000), bundled.getIntegerList("upgrades.rage.cost"));
		assertEquals(List.of(100), bundled.getIntegerList("upgrades.morph_ability.cost"));
		assertEquals("Glowing", bundled.getString("upgrades.ability_glowing.name"));
		assertEquals(List.of("effect:glowing"), bundled.getStringList("upgrades.ability_glowing.traits"));
		// in the release's layout: arm-cost after morph-cost, the Arsenal after the abilities, the admin's upgrade last
		String out = bundled.saveToString();
		assertTrue(out.indexOf("\nmorph-cost:") < out.indexOf("\narm-cost:") && out.indexOf("\narm-cost:") < out.indexOf("\nupgrades:"), out);
		assertTrue(out.indexOf("  ability_camouflage:") < out.indexOf("  arm_shaping:") && out.indexOf("  arm_lantern:") < out.indexOf("  ability_glowing:"));
		assertTrue(out.contains("# Arsenal: Arm Shaping opens it"), "the new section's comments come along");
	}

	/**
	 * A server's survival.yml from a 7.6.0 release or an 8.0.0 beta 1 (the whip's era: twelve tool and four accessory nodes
	 * did not exist yet) has to be merged by the 8.0.0 file, which the version bump makes happen: the new tool and
	 * accessory upgrade nodes and their cost rows arrive at their defaults, and the admin's changed prices stay
	 * (2026-11-16: a version bump, because config-version 7.6.0 meant existing servers never got the eight new nodes).
	 */
	@Test
	void aSurvivalFileFrom760GainsTheEightNewNodesAndKeepsItsPrices() throws Exception {
		YamlConfiguration bundled = bundled("survival.yml");
		String release = bundled.getString("config-version");
		List<String> fresh = List.of("arm_spear", "arm_mace", "arm_trident", "arm_crossbow", "arm_claws", "arm_shield", "arm_spyglass", "arm_flint");
		YamlConfiguration admin = yaml(bundled.saveToString());
		for (String id : fresh) admin.set("upgrades." + id, null);
		for (String row : List.of("spear", "mace", "trident-recall", "trident-throw", "crossbow", "claws", "shield-block", "flint-ignite")) {
			admin.set("arm-cost.specials." + row, null);
		}
		for (String row : List.of("shield", "spyglass", "flint")) admin.set("arm-cost.drain-per-minute." + row, null);
		admin.set("config-version", "7.6.0");
		admin.set("upgrades.arm_katana.cost", List.of(1, 2, 3)); // the admin's own price
		assertTrue(MorphRebornPlugin.compareVersions("7.6.0", Objects.requireNonNull(release)) < 0, "the bundled file is newer, so the server's is merged");

		List<String> added = ConfigMerge.merge(bundled, admin, "config-version");

		for (String id : fresh) {
			assertTrue(added.contains("upgrades." + id), id + " is listed as new");
			assertEquals("arm_shaping", bundled.getString("upgrades." + id + ".parent"), id);
		}
		assertTrue(added.stream().anyMatch(a -> a.startsWith("arm-cost.specials.spear")), "the cost rows arrive too: " + added);
		assertEquals(List.of(1, 2, 3), bundled.getIntegerList("upgrades.arm_katana.cost"), "the admin's price is kept");
		assertEquals(release, bundled.getString("config-version"));
	}

	/**
	 * abilities.yml and config.yml carried 7.6.0 while survival.yml was 8.0.0, so a server whose file came from a 7.6.0 beta
	 * or an 8.0.0 beta 1 was never updated and lacked the new arm forms, accessories, bow tips and whip moves (the plugin
	 * only papered over arms with ArmConfig's bundled overlay). All three are 8.0.0 now, so every file from 7.5.0 or any
	 * 7.6.0 beta is older than the bundled one and is updated at startup.
	 */
	@Test
	void everyOlderServerFileIsOlderThanTheBundledOnes() throws Exception {
		for (String name : List.of("config.yml", "abilities.yml", "survival.yml")) {
			String release = bundled(name).getString("config-version");
			for (String old : List.of("7.5.0", "7.6.0", "7.6.0-beta.1", "7.6.0-beta.3")) {
				assertTrue(MorphRebornPlugin.compareVersions(old, Objects.requireNonNull(release)) < 0, name + " from " + old + " is updated by " + release);
			}
			assertEquals(0, MorphRebornPlugin.compareVersions(release, "8.0.0"), name + " is marked for the release that added the arm forms");
		}
	}

	/**
	 * What a server's abilities.yml from 7.5.0 (marked 7.4.0) or a 7.6.0 beta gets from this release: installOrUpgrade decides
	 * MERGE for it (see {@link #abilitiesFromAnyReleaseSince740AreMergedNotReplaced}) and runs the same ConfigUpgrade.upgrade
	 * as here, so the new arm keys arrive at their defaults and the admin's values stay in the live file
	 * (2026-11-16: merge the new keys without clobbering what a server set).
	 */
	@Test
	void anAbilitiesFileFrom750GainsTheNewArmKeysAndKeepsItsValues() throws Exception {
		YamlConfiguration bundled = bundled("abilities.yml");
		List<String> fresh = List.of("arms.forms.spear", "arms.forms.mace", "arms.forms.trident", "arms.forms.crossbow", "arms.forms.claws",
				"arms.accessories.shield", "arms.accessories.spyglass", "arms.accessories.flint", "arms.forms.bow.tips", "arms.forms.whip.moves");
		for (String path : fresh) assertTrue(bundled.contains(path), path + " is in the bundled file");
		YamlConfiguration admin = yaml(bundled.saveToString());
		for (String path : fresh) admin.set(path, null);
		admin.set("config-version", "7.4.0");
		admin.set("arms.forms.bow.enabled", false); // the admin's own setting
		admin.set("arms.forms.whip.extra-reach", List.of(1, 1, 1));

		List<String> added = ConfigUpgrade.upgrade(bundled, admin, Map.of()).added();

		for (String path : fresh) assertTrue(added.contains(path), path + " is listed as new: " + added);
		assertEquals("8.0.0", bundled.getString("config-version"));
		assertTrue(bundled.contains("arms.forms.spear.special.lunge"), "the new form arrives whole, with its settings");
		assertTrue(bundled.contains("arms.accessories.flint"));
		assertTrue(bundled.contains("arms.forms.bow.tips.bleed.seconds"));
		assertTrue(bundled.contains("arms.forms.whip.moves.crack.range"));
		assertFalse(bundled.getBoolean("arms.forms.bow.enabled"), "the admin's value is kept");
		assertEquals(List.of(1, 1, 1), bundled.getIntegerList("arms.forms.whip.extra-reach"));
	}

	/** config.yml from 7.6.0 is merged by the 8.0.0 file: every value kept, the file marked 8.0.0. */
	@Test
	void aConfigFrom760IsMergedAndKeepsEveryValue() throws Exception {
		YamlConfiguration bundled = bundled("config.yml");
		YamlConfiguration admin = yaml(bundled.saveToString());
		admin.set("config-version", "7.6.0");
		admin.set("morphs.transition-ticks", 33);
		admin.set("debug", true);
		assertTrue(MorphRebornPlugin.compareVersions("7.6.0", Objects.requireNonNull(bundled.getString("config-version"))) < 0);

		ConfigMerge.merge(bundled, admin, "config-version");

		assertEquals("8.0.0", bundled.getString("config-version"));
		assertEquals(33, bundled.getInt("morphs.transition-ticks"));
		assertTrue(bundled.getBoolean("debug"));
	}

	/**
	 * Which of my older servers have abilities.yml replaced (the old file moved aside) and which merged (the file kept,
	 * the new keys added): installOrUpgrade asks ConfigUpgrade.decide, so this is what a real server gets. The finding that led
	 * here: the file used to be replaced for every older version, a 7.5.0 server's hand edits included, and the test above only
	 * proved what merging would do.
	 */
	@Test
	void abilitiesFromAnyReleaseSince740AreMergedNotReplaced() throws Exception {
		String release = bundled("abilities.yml").getString("config-version");
		for (String old : List.of("7.4.0", "7.5.0", "7.6.0", "7.6.0-beta.1", "7.6.0-beta.3")) {
			assertEquals(ConfigUpgrade.Action.MERGE, ConfigUpgrade.decide(old, Objects.requireNonNull(release), ConfigUpgrade.ABILITIES_MERGE_FROM),
					old + " is merged");
		}
		assertEquals(ConfigUpgrade.Action.KEEP, ConfigUpgrade.decide(release, release, ConfigUpgrade.ABILITIES_MERGE_FROM));
		assertEquals(ConfigUpgrade.Action.KEEP, ConfigUpgrade.decide("9.0.0", release, ConfigUpgrade.ABILITIES_MERGE_FROM), "a newer file is left as it is");
		// before 7.4.0, and without a version, the old rule stands: the file is replaced and its backup kept
		assertEquals(ConfigUpgrade.Action.REPLACE, ConfigUpgrade.decide("7.3.0", release, ConfigUpgrade.ABILITIES_MERGE_FROM));
		assertEquals(ConfigUpgrade.Action.REPLACE, ConfigUpgrade.decide("5.0.0", release, ConfigUpgrade.ABILITIES_MERGE_FROM));
		assertEquals(ConfigUpgrade.Action.REPLACE, ConfigUpgrade.decide("", release, ConfigUpgrade.ABILITIES_MERGE_FROM), "no version: older than everything");
		// variants.yml has no merge floor: older is always replaced
		assertEquals(ConfigUpgrade.Action.REPLACE, ConfigUpgrade.decide("5.0.0", "5.1.0", null));
		assertEquals(ConfigUpgrade.Action.KEEP, ConfigUpgrade.decide("5.1.0", "5.1.0", null));
	}

	/** The merge a MERGE decision runs, on a file marked 7.4.0 that an admin edited by hand: what it saves keeps the edits and the new keys. */
	@Test
	void aMergedAbilitiesFileKeepsHandEditsInWhatItSaves() throws Exception {
		YamlConfiguration bundled = bundled("abilities.yml");
		YamlConfiguration admin = yaml(bundled.saveToString());
		admin.set("config-version", "7.4.0");
		admin.set("arms.forms.spear", null);
		admin.set("rage.hits", 12);
		admin.set("rage.levels", List.of(Map.of("radius", 5, "damage", 1, "duration", 2, "hover", 1)));
		admin.set("shulker", null);
		admin.set("activation.vanilla-trigger", "sneak-swap-hands");

		ConfigUpgrade.Result result = ConfigUpgrade.upgrade(bundled, admin, Map.of());
		YamlConfiguration saved = yaml(bundled.saveToString()); // what mergeUpgrade writes over the live file

		assertEquals("8.0.0", saved.getString("config-version"));
		assertEquals(12, saved.getInt("rage.hits"), "a hand edit stays in the live file");
		assertEquals(1, saved.getMapList("rage.levels").size(), "an edited list is the admin's whole list");
		assertEquals("sneak-swap-hands", saved.getString("activation.vanilla-trigger"));
		assertTrue(saved.contains("arms.forms.spear.special"), "a new key arrives");
		assertTrue(saved.contains("shulker.closed-armor"), "a whole new section arrives");
		assertTrue(result.added().contains("shulker") && result.added().contains("arms.forms.spear"), result.added().toString());
		assertTrue(result.reset().isEmpty(), "nothing is replaced");
	}

	/** The quiet-steps block arrives at its defaults in an older abilities.yml, and an admin's own list, landing height and other values stay. */
	@Test
	void anOlderAbilitiesFileGainsQuietStepsAtItsDefaultsAndKeepsTheAdminsOwn() throws Exception {
		YamlConfiguration bundled = bundled("abilities.yml");
		List<String> defaults = bundled.getStringList("passive.quiet-steps.types");
		assertFalse(defaults.isEmpty());
		YamlConfiguration admin = yaml(bundled.saveToString());
		admin.set("passive.quiet-steps", null);
		admin.set("config-version", "7.6.0");
		admin.set("passive.flight.fly-speed", 0.1);
		List<String> added = ConfigUpgrade.upgrade(bundled, admin, Map.of()).added();
		assertTrue(added.contains("passive.quiet-steps"), added.toString());
		assertEquals(defaults, bundled.getStringList("passive.quiet-steps.types"));
		assertEquals(3, bundled.getInt("passive.quiet-steps.soft-landing"));
		assertTrue(bundled.getBoolean("passive.quiet-steps.swimming"));
		assertEquals(0.1, bundled.getDouble("passive.flight.fly-speed"), 1e-9, "the admin's other values are kept");

		// an admin who already edited the block keeps every edit, and a key they never had arrives at its default
		YamlConfiguration edited = bundled("abilities.yml");
		YamlConfiguration adminEdited = yaml(edited.saveToString());
		adminEdited.set("config-version", "7.6.0");
		adminEdited.set("passive.quiet-steps.types", List.of("fox"));
		adminEdited.set("passive.quiet-steps.soft-landing", null);
		adminEdited.set("passive.quiet-steps.swimming", false);
		List<String> addedAgain = ConfigUpgrade.upgrade(edited, adminEdited, Map.of()).added();
		assertEquals(List.of("fox"), edited.getStringList("passive.quiet-steps.types"));
		assertFalse(edited.getBoolean("passive.quiet-steps.swimming"));
		assertEquals(List.of("passive.quiet-steps.soft-landing"), addedAgain);
		assertEquals(3, edited.getInt("passive.quiet-steps.soft-landing"));
	}

	/**
	 * An 8.0.0 survival.yml from before beta 2 is the same version as the bundled one but lacks the eight new upgrade nodes
	 * (config-version cannot carry a beta). It is recognised, and merged once; the merge makes the nodes present, so it is
	 * not repeated (config-versions item, 2026-11-16).
	 */
	@Test
	void anEarlierBetaSurvivalFileMarked800IsCompletedOnce() throws Exception {
		YamlConfiguration bundled = bundled("survival.yml");
		YamlConfiguration admin = yaml(bundled.saveToString());
		for (String id : ConfigUpgrade.BETA2_ARM_UPGRADES) {
			assertTrue(admin.contains("upgrades." + id), id + " is a node of the bundled file");
			assertTrue(SurvivalConfig.KNOWN.contains(id), id + " is a known upgrade, so a file without it is warned about");
		}
		assertFalse(ConfigUpgrade.lacksBeta2ArmUpgrades(admin), "the release's own file is complete");
		for (String id : ConfigUpgrade.BETA2_ARM_UPGRADES) admin.set("upgrades." + id, null);
		admin.set("upgrades.arm_katana.cost", List.of(1, 2, 3));
		admin.set("morph-cost.per-kg", 0.5);
		assertEquals("8.0.0", admin.getString("config-version"));
		assertEquals(0, MorphRebornPlugin.compareVersions(admin.getString("config-version", ""), Objects.requireNonNull(bundled.getString("config-version"))),
				"same version: the ordinary check would leave it");
		assertTrue(ConfigUpgrade.lacksBeta2ArmUpgrades(admin), "so it is recognised by what it lacks");

		List<String> added = ConfigUpgrade.upgrade(bundled, admin, MorphRebornPlugin.SURVIVAL_REPLACED).added();
		YamlConfiguration saved = yaml(bundled.saveToString());

		for (String id : ConfigUpgrade.BETA2_ARM_UPGRADES) assertTrue(added.contains("upgrades." + id), id);
		assertFalse(ConfigUpgrade.lacksBeta2ArmUpgrades(saved), "and the merge ends it");
		assertEquals(List.of(1, 2, 3), saved.getIntegerList("upgrades.arm_katana.cost"), "the admin's price is kept");
		assertEquals(0.5, saved.getDouble("morph-cost.per-kg"), 1e-9);
		// an admin who removed only some of them chose to
		YamlConfiguration partial = bundled("survival.yml");
		partial.set("upgrades.arm_flint", null);
		assertFalse(ConfigUpgrade.lacksBeta2ArmUpgrades(partial));
	}

	@Test
	void aSurvivalFileWithoutAnUpgradesSectionIsCompletedToo() throws Exception {
		assertTrue(ConfigUpgrade.lacksBeta2ArmUpgrades(yaml("config-version: 8.0.0\n")));
	}
}
