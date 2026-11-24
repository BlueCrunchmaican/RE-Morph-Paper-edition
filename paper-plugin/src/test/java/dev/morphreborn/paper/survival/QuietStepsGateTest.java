package dev.morphreborn.paper.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Quiet steps is a trait no Survival unlock lists, so it is on from the start in both modes and Camouflage has nothing to do with it; an admin
 * can still make it a bought trait. SurvivalConfig itself needs a running server to build, so the trait map is read from the YAML the way
 * its constructor does, and the rule under test is {@link TraitGate} (what {@link SurvivalMode#hasTrait} runs).
 */
class QuietStepsGateTest {
	private static YamlConfiguration survival() throws Exception {
		YamlConfiguration yaml = new YamlConfiguration();
		try (Reader r = new InputStreamReader(Objects.requireNonNull(QuietStepsGateTest.class.getResourceAsStream("/survival.yml")), StandardCharsets.UTF_8)) {
			yaml.load(r);
		}
		return yaml;
	}

	/** Trait to the upgrades listing it, as SurvivalConfig.traitUnlocks builds it. */
	private static Map<String, List<String>> unlocks(ConfigurationSection upgrades) {
		Map<String, List<String>> out = new HashMap<>();
		for (String id : upgrades.getKeys(false)) {
			ConfigurationSection u = upgrades.getConfigurationSection(id);
			if (u == null) continue;
			for (String t : u.getStringList("traits")) out.computeIfAbsent(t.toLowerCase(java.util.Locale.ROOT).trim(), k -> new ArrayList<>()).add(id);
		}
		return out;
	}

	@Test
	void noShippedUpgradeListsItSoItIsOnFromTheStartAndStaysOnWhateverIsBought() throws Exception {
		Map<String, List<String>> unlocks = unlocks(Objects.requireNonNull(survival().getConfigurationSection("upgrades")));
		assertNull(unlocks.get("quiet-steps"), "no unlock lists it");
		assertTrue(TraitGate.on(unlocks, "quiet-steps", id -> 0), "nothing bought: on");
		Set<String> bought = Set.of();
		assertTrue(TraitGate.on(unlocks, "quiet-steps", id -> bought.contains(id) ? 1 : 0));
		// Camouflage is bought separately and does not touch it
		List<String> camouflage = unlocks.get("hostile-camouflage");
		assertNotNull(camouflage, "Camouflage's own traits are still gated");
		assertEquals(List.of("ability_camouflage"), camouflage);
		assertFalse(TraitGate.on(unlocks, "hostile-camouflage", id -> 0), "Camouflage is off until bought");
		assertTrue(TraitGate.on(unlocks, "hostile-camouflage", id -> id.equals("ability_camouflage") ? 1 : 0), "and on once it is");
		assertTrue(TraitGate.on(unlocks, "quiet-steps", id -> id.equals("ability_camouflage") ? 1 : 0), "buying Camouflage changes nothing for quiet steps");
	}

	@Test
	void anAdminCanGateItByListingItUnderAnUpgrade() throws Exception {
		YamlConfiguration yaml = survival();
		yaml.set("upgrades.ability_camouflage.traits", List.of("hostile-camouflage", "intimidate", "fear", "quiet-steps"));
		Map<String, List<String>> unlocks = unlocks(Objects.requireNonNull(yaml.getConfigurationSection("upgrades")));
		assertEquals(List.of("ability_camouflage"), unlocks.get("quiet-steps"));
		assertFalse(TraitGate.on(unlocks, "quiet-steps", id -> 0), "off until that upgrade is bought");
		assertTrue(TraitGate.on(unlocks, "quiet-steps", id -> id.equals("ability_camouflage") ? 1 : 0), "on once it is");
	}
}
