package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.ArmTiers;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The bundled abilities.yml {@code arms:} and survival.yml {@code arm-cost:} say exactly what the code's defaults say.
 * ArmConfig lays the bundled section under a server's, and a server whose file lacks keys reads the code's defaults
 * for them, so the two must never drift apart: a change to one is a change to both.
 */
class ArmBundledConfigTest {
	private static ConfigurationSection section(String file, String path) throws Exception {
		YamlConfiguration yaml = new YamlConfiguration();
		try (Reader r = new InputStreamReader(Objects.requireNonNull(ArmBundledConfigTest.class.getResourceAsStream("/" + file)), StandardCharsets.UTF_8)) {
			yaml.load(r);
		}
		return Objects.requireNonNull(yaml.getConfigurationSection(path), file + " has no " + path + ":");
	}

	/** A section as nested plain maps, the other shape ArmConfig.parse takes. */
	private static Map<String, Object> nested(ConfigurationSection s) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (String key : s.getKeys(false)) {
			Object v = s.get(key);
			out.put(key, v instanceof ConfigurationSection child ? nested(child) : v);
		}
		return out;
	}

	@Test
	void theBundledSectionsAreTheDefaults() throws Exception {
		ConfigurationSection arms = section("abilities.yml", "arms");
		ConfigurationSection cost = section("survival.yml", "arm-cost");
		assertEquals(ArmConfig.DEFAULTS, ArmConfig.parse(arms.getValues(true), cost.getValues(true), Map.of()), "as a server's own sections");
		assertEquals(ArmConfig.DEFAULTS, ArmConfig.parse(Map.of(), cost.getValues(true), arms.getValues(true)), "laid under a server without arms:");
		assertEquals(ArmConfig.DEFAULTS, ArmConfig.parse(nested(arms), nested(cost), nested(arms)), "as plain nested maps");
		assertEquals(ArmConfig.DEFAULTS, ArmConfig.parse(arms.getValues(true), Map.of(), Map.of()), "a missing arm-cost: is the defaults too");
	}

	@Test
	void everyFormAndEveryAccessoryHaveTheirSection() throws Exception {
		ArmConfig c = ArmConfig.parse(Map.of(), Map.of(), section("abilities.yml", "arms").getValues(true));
		assertEquals(Set.of("katana", "pickaxe", "axe", "shovel", "sickle", "bow", "whip", "spear", "mace", "trident", "crossbow", "claws"), c.rawForms().keySet());
		// every tool form the code knows has a section, and the other way round
		assertEquals(Set.copyOf(ArmTiers.TOOL_FORMS), c.rawForms().keySet());
		// the accessories: the lantern's section beside forms:, the others' under accessories:
		assertEquals(List.of("lantern", "shield", "spyglass", "flint"), List.copyOf(c.rawAccessories().keySet()));
		for (String id : ArmTiers.ACCESSORIES) {
			assertEquals(true, c.accessory(id).get("enabled"), id);
			assertTrue(c.formEnabled(id), id);
		}
		assertEquals(c.rawLantern(), c.accessory("lantern"), "the lantern's section is the same map under both names");
		assertEquals(List.of(0.5, 0.6, 0.7), c.accessory("shield").get("reduction"));
		for (String form : c.rawForms().keySet()) {
			assertEquals(true, c.form(form).get("enabled"), form);
			assertTrue(c.formEnabled(form), form);
		}
		assertEquals(true, c.rawLantern().get("enabled"));
		assertTrue(c.disabledForms().isEmpty());
		// the phase 2 forms read their own keys; spot checks against the spec's values
		assertEquals(List.of(8, 7, 6), c.form("axe").get("cooldown"));
		assertEquals("Fell", ((Map<?, ?>) c.form("axe").get("fell")).get("name"));
		assertEquals("Cleave", ((Map<?, ?>) c.form("axe").get("cleave")).get("name"));
		assertEquals("Burrow", ((Map<?, ?>) c.form("shovel").get("burrow")).get("name"));
		assertEquals("Reap", ((Map<?, ?>) c.form("sickle").get("reap")).get("name"));
		assertEquals("Triple Arrow", ((Map<?, ?>) c.form("bow").get("triple")).get("name"));
		assertEquals(null, c.form("bow").get("harpoon"), "the Harpoon Shot went in 8.0.0-beta.2");
		assertEquals(Set.of("bleed", "slowness", "poison", "fire", "glowing"), ((Map<?, ?>) c.form("bow").get("tips")).keySet());
		assertEquals(List.of(8, 11, 14, 20, 30), c.rawLantern().get("brightness-reach"));
		assertEquals(null, c.rawLantern().get("flare"), "the Flare went in 7.6.0");
		for (String form : List.of("axe", "shovel", "sickle")) assertEquals(3, c.fortune(form, 3), form);
	}

	/** Every list under forms:, lantern: and accessories: is per level, so it has one entry for each of the three. */
	@Test
	void everyPerLevelListHasThreeEntries() throws Exception {
		for (Map.Entry<String, Object> e : section("abilities.yml", "arms").getValues(true).entrySet()) {
			if (!(e.getValue() instanceof List<?> list)) continue;
			if (!e.getKey().startsWith("forms.") && !e.getKey().startsWith("lantern.") && !e.getKey().startsWith("accessories.")) continue;
			// the lantern's brightness has five steps, not three levels
			assertEquals(e.getKey().equals("lantern.brightness-reach") ? 5 : 3, list.size(), "arms." + e.getKey() + " " + list);
		}
		for (Map.Entry<String, Object> e : section("survival.yml", "arm-cost").getValues(true).entrySet()) {
			if (e.getValue() instanceof List<?> list) assertEquals(3, list.size(), "arm-cost." + e.getKey() + " " + list);
		}
	}

	@Test
	void everySpecialHasACost() throws Exception {
		ConfigurationSection specials = Objects.requireNonNull(section("survival.yml", "arm-cost").getConfigurationSection("specials"));
		assertEquals(ArmConfig.DEFAULTS.costs().specials().keySet(), specials.getKeys(false));
		for (String key : specials.getKeys(false)) assertNotNull(ArmConfig.DEFAULTS.costs().specials().get(key), key);
	}

	/** The file's Fortune, Efficiency and extra reach lines say what the code's defaults say, for every form that has them. */
	@Test
	void fortuneEfficiencyAndReachLinesAreTheDefaults() throws Exception {
		ConfigurationSection forms = Objects.requireNonNull(section("abilities.yml", "arms").getConfigurationSection("forms"));
		ArmConfig defaults = ArmConfig.parse(Map.of(), Map.of(), Map.of());
		// the claws take Efficiency by level too (shears' speed), and no Fortune
		ConfigurationSection claws = Objects.requireNonNull(forms.getConfigurationSection("claws"));
		assertEquals(List.of(1, 2, 3), claws.getIntegerList("efficiency"));
		assertEquals(false, claws.contains("fortune"));
		for (int level = 1; level <= 3; level++) {
			assertEquals(level, defaults.efficiency("claws", level));
			assertEquals(0, defaults.fortune("claws", level));
		}
		for (String form : List.of("pickaxe", "axe", "shovel", "sickle")) {
			ConfigurationSection s = Objects.requireNonNull(forms.getConfigurationSection(form), form);
			assertEquals(List.of(1, 2, 3), s.getIntegerList("fortune"), form + " fortune");
			assertEquals(List.of(1, 2, 3), s.getIntegerList("efficiency"), form + " efficiency");
			for (int level = 1; level <= 3; level++) {
				assertEquals(level, defaults.fortune(form, level), form);
				assertEquals(level, defaults.efficiency(form, level), form);
			}
		}
		for (String form : List.of("katana", "axe", "shovel", "sickle", "whip")) {
			List<Double> lines = Objects.requireNonNull(forms.getConfigurationSection(form)).getDoubleList("extra-reach");
			assertEquals(3, lines.size(), form);
			for (int level = 1; level <= 3; level++) assertEquals(defaults.extraReach(form, level), lines.get(level - 1), 1e-9, form + " extra-reach level " + level);
		}
		for (String form : List.of("pickaxe", "bow", "spear", "mace", "trident", "crossbow", "claws")) {
			assertEquals(false, forms.contains(form + ".extra-reach"), form + " has no extra reach line");
		}
	}

	@Test
	void tierFollowsBiomassIsOnInTheFile() throws Exception {
		assertEquals(true, section("abilities.yml", "arms").get("tier-follows-biomass"));
		assertTrue(ArmConfig.DEFAULTS.tierFollowsBiomass());
		assertEquals(false, ArmConfig.parse(Map.of("tier-follows-biomass", false), Map.of(), Map.of()).tierFollowsBiomass());
	}
}
