package dev.morphreborn.paper.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The bundled survival.yml's tree, and the conversion of levels bought before 6.6.0. */
class UpgradeTreeTest {
	private static YamlConfiguration load(String name) throws Exception {
		YamlConfiguration yaml = new YamlConfiguration();
		try (Reader r = new InputStreamReader(Objects.requireNonNull(UpgradeTreeTest.class.getResourceAsStream("/" + name)), StandardCharsets.UTF_8)) {
			yaml.load(r);
		}
		return yaml;
	}

	private static ConfigurationSection upgrades() throws Exception {
		return Objects.requireNonNull(load("survival.yml").getConfigurationSection("upgrades"));
	}

	@Test
	void levelsAreTheAgreedOnes() throws Exception {
		ConfigurationSection u = upgrades();
		Curve reach = Curve.read(Objects.requireNonNull(u.getConfigurationSection("biomass_reach")), "value");
		assertEquals(3, reach.at(1), 1e-9);
		assertEquals(150, reach.at(6), 1e-9);
		ConfigurationSection mass = Objects.requireNonNull(u.getConfigurationSection("biomass_max_mass_absorbable"));
		assertEquals(5, mass.getInt("max-level"));
		assertEquals(-1, Curve.read(mass, "value").at(5), 1e-9);
		for (String id : List.of("morph_efficiency", "morph_abilities")) {
			Curve speed = Curve.read(Objects.requireNonNull(u.getConfigurationSection(id)), "value");
			assertEquals(130, speed.at(1), 1e-9, id + " starts 30% slower than Classic");
			assertEquals(70, speed.at(6), 1e-9, id + " ends 30% faster than Classic");
		}
		assertEquals("root", u.getString("morph_ability.parent"));
	}

	@Test
	void everyParentExistsAndAbilitiesHangOffMorphAbilities() throws Exception {
		ConfigurationSection u = upgrades();
		int abilities = 0;
		for (String id : u.getKeys(false)) {
			String parent = u.getString(id + ".parent", "root");
			assertTrue(parent.equals("root") || u.contains(parent), id + " has parent " + parent);
			if (parent.equals(SurvivalConfig.MORPH_ABILITIES) && u.getInt(id + ".max-level") == 1) abilities++;
		}
		assertEquals(13, abilities);
	}

	/** Every trait an unlock lists is a passive abilities.yml really has, so none of them is a typo that never unlocks. */
	@Test
	void traitsNameRealPassives() throws Exception {
		ConfigurationSection passive = Objects.requireNonNull(load("abilities.yml").getConfigurationSection("passive"));
		Set<String> effects = new HashSet<>();
		ConfigurationSection constant = Objects.requireNonNull(passive.getConfigurationSection("effects"));
		for (String type : constant.getKeys(false)) {
			for (String e : constant.getStringList(type)) effects.add(e.substring(0, e.indexOf(':')));
		}
		Map<String, String> owner = new HashMap<>();
		ConfigurationSection u = upgrades();
		for (String id : u.getKeys(false)) {
			for (String raw : u.getStringList(id + ".traits")) {
				String trait = SurvivalConfig.normaliseTrait(raw);
				if (trait.startsWith("effect:")) assertTrue(effects.contains(trait.substring(7)), id + ": no mob has " + trait);
				else assertTrue(passive.contains(trait), id + ": abilities.yml has no passive " + trait);
				assertFalse(owner.containsKey(trait), trait + " is unlocked by both " + owner.get(trait) + " and " + id);
				owner.put(trait, id);
			}
		}
		// Drawbacks are never gated.
		for (String drawback : List.of("sunburn", "water-damage", "undead", "sink", "water-only-breathing", "effect:slowness")) {
			assertFalse(owner.containsKey(drawback), drawback + " is a drawback but " + owner.get(drawback) + " gates it");
		}
		assertNotNull(owner.get("effect:night_vision"));
	}

	@Test
	void speedsReadInSecondsAndMultipliers() {
		assertEquals("6.5", SurvivalMode.transformSeconds(100, 130));
		assertEquals("5.8", SurvivalMode.transformSeconds(100, 115)); // 115 ticks
		assertEquals("3.5", SurvivalMode.transformSeconds(100, 70));
		assertEquals("1", SurvivalMode.transformSeconds(20, 70)); // never under a second
		assertEquals("×1.3", SurvivalMode.factor(130));
		assertEquals("×1.15", SurvivalMode.factor(115));
		assertEquals("×1", SurvivalMode.factor(100));
		assertEquals("×0.7", SurvivalMode.factor(70));
	}

	@Test
	void oldLevelsMoveToOnesAtLeastAsGood() {
		Map<String, Integer> levels = new HashMap<>(Map.of(SurvivalConfig.BIOMASS_REACH, 6, SurvivalConfig.BIOMASS_MAX_MASS, 4,
				SurvivalConfig.MORPH_EFFICIENCY, 2, SurvivalConfig.MORPH_ABILITIES, 1, SurvivalConfig.BIOMASS_CAPACITY, 5));
		assertTrue(SurvivalMode.convertLayout(levels));
		assertEquals(4, levels.get(SurvivalConfig.BIOMASS_REACH)); // 24 blocks -> 32
		assertEquals(4, levels.get(SurvivalConfig.BIOMASS_MAX_MASS)); // 18,750 kg -> 160,000
		assertEquals(5, levels.get(SurvivalConfig.MORPH_EFFICIENCY)); // 20% faster -> 80% of the time
		assertEquals(3, levels.get(SurvivalConfig.MORPH_ABILITIES)); // Classic cooldowns -> 100%
		assertEquals(5, levels.get(SurvivalConfig.BIOMASS_CAPACITY)); // unchanged
		Map<String, Integer> none = new HashMap<>(Map.of(SurvivalConfig.MORPH_ABILITIES, 0));
		assertFalse(SurvivalMode.convertLayout(none));
	}
}
