package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.api.ArmSpecialModule;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.ArmTiers;
import dev.morphreborn.paper.arm.rules.SpearSettings;
import dev.morphreborn.paper.arm.special.SpearImpale;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The spear's Impale: its settings, its bundled rows, what the module says of itself and what bills a jab. */
class SpearSettingsTest {
	private static YamlConfiguration load(String file) throws Exception {
		YamlConfiguration yaml = new YamlConfiguration();
		try (Reader r = new InputStreamReader(Objects.requireNonNull(SpearSettingsTest.class.getResourceAsStream("/" + file)), StandardCharsets.UTF_8)) {
			yaml.load(r);
		}
		return yaml;
	}

	/** A section as nested plain maps, the shape the manager hands a module. */
	private static Map<String, Object> nested(ConfigurationSection s) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (String key : s.getKeys(false)) {
			Object v = s.get(key);
			out.put(key, v instanceof ConfigurationSection child ? nested(child) : v);
		}
		return out;
	}

	@Test
	void theBundledSectionIsTheDefaults() throws Exception {
		ConfigurationSection spear = Objects.requireNonNull(load("abilities.yml").getConfigurationSection("arms.forms.spear"));
		assertEquals(SpearSettings.DEFAULTS, SpearSettings.parse(nested(spear)));
		assertEquals(SpearSettings.DEFAULTS, SpearSettings.parse(ArmConfig.parse(Map.of(), Map.of(), nested(Objects.requireNonNull(load("abilities.yml")
				.getConfigurationSection("arms")))).form("spear")), "through the arm config's merged section too");
	}

	@Test
	void theDefaultsAreTheDesignsNumbers() {
		SpearSettings s = SpearSettings.DEFAULTS;
		assertEquals("Impale", s.name());
		assertEquals(List.of(3.0, 4.0, 5.0), List.of(s.lungeAt(1), s.lungeAt(2), s.lungeAt(3)), "a short lunge: 3, 4 and 5 blocks");
		assertEquals(List.of(30, 40, 50), List.of(s.rootTicks(1), s.rootTicks(2), s.rootTicks(3)), "pinned 1.5, 2 and 2.5 seconds");
		assertEquals(List.of(4.0, 5.0, 6.0), List.of(s.damage().at(1), s.damage().at(2), s.damage().at(3)));
		assertEquals(List.of(160, 140, 120), List.of(s.cooldownTicks(1), s.cooldownTicks(2), s.cooldownTicks(3)));
		assertEquals(2.5, s.reach(), 1e-9);
		assertFalse(s.pinPlayers(), "players are safe from the spikes until a server says otherwise, as from the snare");
	}

	@Test
	void aMissingSectionIsTheDefaults() {
		assertSame(SpearSettings.DEFAULTS, SpearSettings.parse(null));
		assertSame(SpearSettings.DEFAULTS, SpearSettings.parse(Map.of()));
		assertSame(SpearSettings.DEFAULTS, SpearSettings.parse(Map.of("enabled", true)), "a form section with no special: row");
		assertEquals(SpearSettings.DEFAULTS, SpearSettings.parse(Map.of("special", Map.of())));
	}

	@Test
	void aServersValuesAreReadAndBadOnesTakeTheDefault() {
		Map<String, Object> form = Map.of("special", Map.of("name", "Skewer", "lunge", List.of(2, 6), "root-seconds", 4, "damage", List.of(-1, "oops", 9),
				"cooldown", List.of(1, 2, 3), "reach", 99, "pin-players", true));
		SpearSettings s = SpearSettings.parse(form);
		assertEquals("Skewer", s.name());
		assertEquals(List.of(2.0, 6.0, 6.0), List.of(s.lungeAt(1), s.lungeAt(2), s.lungeAt(3)), "fewer entries repeat the last");
		assertEquals(80, s.rootTicks(1), "a number is every level, seconds to ticks");
		assertEquals(0.0, s.damage().at(1), 1e-9, "never negative");
		assertEquals(5.0, s.damage().at(2), 1e-9, "unreadable: that level's default");
		assertEquals(9.0, s.damage().at(3), 1e-9);
		assertEquals(List.of(20, 40, 60), List.of(s.cooldownTicks(1), s.cooldownTicks(2), s.cooldownTicks(3)));
		assertEquals(SpearSettings.MAX_REACH, s.reach(), 1e-9, "clamped");
		assertTrue(s.pinPlayers());
		assertEquals(SpearSettings.MAX_LUNGE, SpearSettings.parse(Map.of("special", Map.of("lunge", 500))).lungeAt(2), 1e-9, "no lunge across the map");
		assertEquals(1, SpearSettings.parse(Map.of("special", Map.of("root-seconds", 0))).rootTicks(1), "a pin of no length is no pin");
		assertEquals(100, SpearSettings.parse(Map.of("special", Map.of("root-seconds", 60))).rootTicks(1), "the clients draw 100 ticks at most");
		assertEquals(dev.morphreborn.paper.arm.rules.ImpaleMath.MAX_DISTANCE, SpearSettings.MAX_LUNGE, 1e-9, "no lunge the movement can't cover");
		assertFalse(SpearSettings.parse(Map.of("special", Map.of("pin-players", "yes"))).pinPlayers(), "unreadable: the default");
	}

	@Test
	void theLevelsInWords() {
		assertEquals("Lunges 4 blocks · pins 2 s · 5 damage", SpearSettings.DEFAULTS.describe(2));
		assertEquals("Lunges 3 blocks · pins 1.5 s · 4 damage", SpearSettings.DEFAULTS.describe(1));
	}

	@Test
	void theModuleSpeaksForTheForm() {
		SpearImpale m = new SpearImpale();
		assertEquals("spear", m.form());
		assertFalse(m.breaksBlocks(), "a lunge breaks nothing");
		assertEquals("Impale", m.specialName(1));
		assertEquals(List.of(10_000L, 12_000L, 15_000L), List.of(m.costMilli(1), m.costMilli(2), m.costMilli(3)), "survival.yml arm-cost.specials.spear");
		assertEquals(List.of(160, 140, 120), List.of(m.cooldownTicks(1), m.cooldownTicks(2), m.cooldownTicks(3)));
		assertEquals(m.cooldownTicks(1), m.cooldownTicks(0), "a level 0 reads as iron");
		assertEquals("Lunges 5 blocks · pins 2.5 s · 6 damage", m.describe(3));
		assertEquals(null, m.loreLine(2, true, v -> v), "one move: the usual F line");
		m.load(Map.of("special", Map.of("name", "Skewer", "cooldown", 1)));
		assertEquals("Skewer", m.specialName(2));
		assertEquals(20, m.cooldownTicks(3));
	}

	@Test
	void theModuleIsLoadedByTheArmModules() {
		List<String> forms = ArmModules.specials(Logger.getAnonymousLogger()).stream().map(ArmSpecialModule::form).toList();
		assertTrue(forms.contains("spear"), "SpearImpale is registered and loads: " + forms);
		assertNotNull(ArmTiers.spear(1));
	}

	@Test
	void aSpearsJabAndChargeAreBilledByTheirDamageTypeAlone() {
		// both strike with the item's minecraft:spear damage type (stabAttack, Player.attack), which ArmBilling counts as melee; the old
		// "any own blow with the spear in hand" path is gone, so another plugin's damage is no longer billed
		assertTrue(ArmTiers.isWeaponDamageType("minecraft:spear"));
		assertFalse(ArmTiers.isWeaponDamageType("minecraft:player_attack"), "that one is ENTITY_ATTACK already");
		assertFalse(ArmTiers.isWeaponDamageType("minecraft:generic"));
	}
}
