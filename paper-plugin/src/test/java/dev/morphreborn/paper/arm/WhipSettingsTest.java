package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.ArmItemSpec;
import dev.morphreborn.paper.arm.rules.ArmItemSpecs;
import dev.morphreborn.paper.arm.rules.ArmTiers;
import dev.morphreborn.paper.arm.rules.WhipMoves;
import dev.morphreborn.paper.arm.rules.WhipSettings;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The whip's settings, its bundled rows, and the rod's enchantments by level. */
class WhipSettingsTest {
	private static YamlConfiguration load(String file) throws Exception {
		YamlConfiguration yaml = new YamlConfiguration();
		try (Reader r = new InputStreamReader(Objects.requireNonNull(WhipSettingsTest.class.getResourceAsStream("/" + file)), StandardCharsets.UTF_8)) {
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
		ConfigurationSection whip = Objects.requireNonNull(load("abilities.yml").getConfigurationSection("arms.forms.whip"));
		assertEquals(WhipSettings.DEFAULTS, WhipSettings.parse(nested(whip)));
		assertEquals(WhipSettings.DEFAULTS, WhipSettings.parse(ArmConfig.parse(Map.of(), Map.of(), nested(Objects.requireNonNull(load("abilities.yml")
				.getConfigurationSection("arms")))).form("whip")), "through the arm config's merged section too");
	}

	@Test
	void theDefaultsAreTheSpecsNumbers() {
		WhipSettings s = WhipSettings.DEFAULTS;
		assertEquals("Whip moves", s.name());
		assertEquals(List.of(12.0, 16.0, 20.0), List.of(s.range(1), s.range(2), s.range(3)));
		WhipSettings.Moves m = s.moves();
		assertEquals(List.of(8.0, 10.0, 12.0), List.of(m.reach().at(1), m.reach().at(2), m.reach().at(3)), "F finds a creature this far");
		assertEquals(8, m.sharedCooldownTicks());
		assertEquals(List.of(40, 50, 60), List.of(m.snareTicks(1), m.snareTicks(2), m.snareTicks(3)), "the Snare holds 2, 2.5 and 3 seconds");
		assertEquals(List.of(6.0, 7.0, 8.0), List.of(m.crackRange().at(1), m.crackRange().at(2), m.crackRange().at(3)));
		assertEquals(35, m.crackHalfAngle());
		assertEquals(List.of(2.0, 2.5, 3.0), List.of(m.crackDamage().at(1), m.crackDamage().at(2), m.crackDamage().at(3)));
		assertEquals(60, m.fleeTicks());
		assertEquals(List.of(4.0, 4.5, 5.0), List.of(m.whirlRadius().at(1), m.whirlRadius().at(2), m.whirlRadius().at(3)));
		assertEquals(List.of(4.0, 5.0, 6.0), List.of(m.whirlDamage().at(1), m.whirlDamage().at(2), m.whirlDamage().at(3)));
		// each move's own cooldown, in ticks
		for (int level = 1; level <= 3; level++) {
			assertEquals(List.of(120, 100, 80).get(level - 1), m.cooldownTicks(WhipMoves.Move.SNARE, level));
			assertEquals(List.of(80, 70, 60).get(level - 1), m.cooldownTicks(WhipMoves.Move.DISARM, level));
			assertEquals(List.of(100, 90, 80).get(level - 1), m.cooldownTicks(WhipMoves.Move.CRACK, level));
			assertEquals(List.of(200, 180, 160).get(level - 1), m.cooldownTicks(WhipMoves.Move.WHIRL, level));
		}
		assertFalse(s.snarePlayers(), "players are safe from the Snare until a server says otherwise");
		assertFalse(s.disarmPlayers(), "and from the Disarm");
		assertEquals(List.of(1.8, 2.0, 2.2), List.of(s.speedCap(1), s.speedCap(2), s.speedCap(3)));
		assertEquals(1200, s.maxMass());
	}

	@Test
	void aMissingSectionIsTheDefaults() {
		assertSame(WhipSettings.DEFAULTS, WhipSettings.parse(null));
		assertSame(WhipSettings.DEFAULTS, WhipSettings.parse(Map.of()));
		assertEquals(WhipSettings.DEFAULTS, WhipSettings.parse(Map.of("enabled", true)));
	}

	@Test
	void aServersValuesAreReadAndBadOnesTakeTheDefault() {
		Map<String, Object> form = Map.of(
				"grapple", Map.of("range", List.of(5, 6), "max-speed", 1.0, "lift", "oops", "speed-per-block", -3),
				"yank", Map.of("factor", List.of(0.1, 5, 99), "max-mass", 400),
				"moves", Map.of("name", "Moves", "reach", List.of(3, 4, 5), "shared-cooldown", 1, "snare", Map.of("seconds", 4, "cooldown", List.of(2, 1, 0.5)),
						"crack", Map.of("range", 3, "half-angle", 500, "flee-seconds", "oops"), "whirl", Map.of("radius", List.of(1, 2), "damage", -4)),
				"snare-players", true, "disarm-players", "yes");
		WhipSettings s = WhipSettings.parse(form);
		assertEquals("Moves", s.name());
		assertEquals(List.of(5.0, 6.0, 6.0), List.of(s.range(1), s.range(2), s.range(3)), "fewer entries repeat the last");
		assertEquals(1.0, s.maxSpeed().at(3), 1e-9, "one number is every level");
		assertEquals(0.25, s.lift(), 1e-9, "unreadable: the default");
		assertEquals(0, s.speedPerBlock(), 1e-9, "negative is clamped to 0");
		assertEquals(1.0, s.factor(1), 1e-9, "a yank is never weaker than vanilla's pull");
		assertEquals(5.0, s.factor(2), 1e-9);
		assertEquals(10.0, s.factor(3), 1e-9, "and is clamped at the top");
		assertEquals(400, s.maxMass(), 1e-9);
		WhipSettings.Moves m = s.moves();
		assertEquals(List.of(3.0, 4.0, 5.0), List.of(m.reach().at(1), m.reach().at(2), m.reach().at(3)));
		assertEquals(20, m.sharedCooldownTicks(), "seconds to ticks");
		assertEquals(80, m.snareTicks(1), "a number is every level");
		for (int level = 1; level <= 3; level++) {
			assertEquals(List.of(40, 20, 10).get(level - 1), m.cooldownTicks(WhipMoves.Move.SNARE, level), "seconds to ticks");
		}
		assertEquals(3.0, m.crackRange().at(3), 1e-9);
		assertEquals(90.0, m.crackHalfAngle(), 1e-9, "a cone is at most a half circle either side");
		assertEquals(60, m.fleeTicks(), "unreadable: the default");
		assertEquals(List.of(1.0, 2.0, 2.0), List.of(m.whirlRadius().at(1), m.whirlRadius().at(2), m.whirlRadius().at(3)));
		assertEquals(0.0, m.whirlDamage().at(1), 1e-9, "never negative");
		assertTrue(s.snarePlayers());
		assertFalse(s.disarmPlayers(), "unreadable: the default");
		assertTrue(WhipSettings.parse(Map.of("moves", Map.of("snare", Map.of("seconds", 0)))).moves().snareTicks(1) >= 1, "a snare lasts at least a moment");
	}

	@Test
	void theCostRowsAreTheSpecsAndInTheDefaults() throws Exception {
		ConfigurationSection specials = Objects.requireNonNull(load("survival.yml").getConfigurationSection("arm-cost.specials"));
		assertEquals(List.of(6, 7, 8), specials.getIntegerList("whip-snare"));
		assertEquals(List.of(4, 5, 6), specials.getIntegerList("whip-disarm"));
		assertEquals(List.of(6, 7, 8), specials.getIntegerList("whip-crack"));
		assertEquals(List.of(10, 12, 14), specials.getIntegerList("whip-whirl"));
		assertFalse(specials.contains("whip"), "the Lash's row is gone");
		assertEquals(0.25, specials.getDoubleList("whip-cast").get(0), 1e-9);
		assertEquals(0.5, specials.getDoubleList("whip-pull").get(1), 1e-9);
		assertEquals(0.5, specials.getDoubleList("whip-yank").get(2), 1e-9);
		ArmConfig.Costs costs = ArmConfig.DEFAULTS.costs();
		for (int level = 1; level <= 3; level++) {
			assertEquals(List.of(6_000L, 7_000L, 8_000L).get(level - 1), costs.special("whip-snare", level));
			assertEquals(List.of(4_000L, 5_000L, 6_000L).get(level - 1), costs.special("whip-disarm", level));
			assertEquals(List.of(6_000L, 7_000L, 8_000L).get(level - 1), costs.special("whip-crack", level));
			assertEquals(List.of(10_000L, 12_000L, 14_000L).get(level - 1), costs.special("whip-whirl", level));
		}
		assertEquals(0, costs.special("whip", 1), "the Lash has no row any more");
		for (int level = 1; level <= 3; level++) {
			assertEquals(250, costs.special("whip-cast", level));
			assertEquals(500, costs.special("whip-pull", level));
			assertEquals(500, costs.special("whip-yank", level));
		}
		assertEquals(0, ArmConfig.parse(Map.of(), Map.of("enabled", false), Map.of()).costs().special("whip-whirl", 3), "arm costs off: free");
	}

	@Test
	void theUpgradeNodeIsShapedLikeTheBows() throws Exception {
		ConfigurationSection u = Objects.requireNonNull(load("survival.yml").getConfigurationSection("upgrades"));
		ConfigurationSection whip = Objects.requireNonNull(u.getConfigurationSection("arm_whip"));
		ConfigurationSection bow = Objects.requireNonNull(u.getConfigurationSection("arm_bow"));
		assertEquals("arm_shaping", whip.getString("parent"));
		assertEquals(3, whip.getInt("max-level"));
		assertEquals(0, whip.getInt("start-level"));
		assertEquals(List.of(1, 2, 3), whip.getIntegerList("value"));
		assertEquals(bow.getString("format"), whip.getString("format"));
		assertEquals(3, whip.getIntegerList("cost").size());
		assertNotNull(ArmForm.byUpgrade("arm_whip"));
		assertEquals(ArmForm.WHIP, ArmForm.byUpgrade("arm_whip"));
	}

	@Test
	void theRodCarriesLureAndLuckOfTheSeaByLevel() {
		assertEquals(0, ArmTiers.lure("whip", 1));
		assertEquals(1, ArmTiers.lure("whip", 2));
		assertEquals(2, ArmTiers.lure("whip", 3));
		assertEquals(0, ArmTiers.luckOfTheSea("whip", 1));
		assertEquals(0, ArmTiers.luckOfTheSea("whip", 2));
		assertEquals(1, ArmTiers.luckOfTheSea("whip", 3));
		assertEquals(2, ArmTiers.lure("whip", 9), "a tier beyond netherite reads as netherite");
		for (String form : ArmTiers.TOOL_FORMS) {
			if (form.equals("whip")) continue;
			assertEquals(0, ArmTiers.lure(form, 3), form);
			assertEquals(0, ArmTiers.luckOfTheSea(form, 3), form);
		}
	}

	@Test
	void theRodsLoreAndSpecShowThem() {
		ArmItemSpec iron = ArmItemSpecs.of("whip", 1, true, 0, 0, 0, WhipMoves.loreLine(6_000, 4_000, 6_000, 10_000, true));
		ArmItemSpec diamond = ArmItemSpecs.of("whip", 2, true, 0, 0, 0, WhipMoves.loreLine(7_000, 5_000, 7_000, 12_000, true));
		ArmItemSpec netherite = ArmItemSpecs.of("whip", 3, true, 0, 0, 0, WhipMoves.loreLine(8_000, 6_000, 8_000, 14_000, true));
		assertEquals(List.of("Your own arm, reshaped.", "F: Snare 6 · Disarm 4 · Crack 6 · Whirl (sneak) 10 biomass"), iron.lore());
		assertEquals(List.of("Your own arm, reshaped.", "F: Snare 7 · Disarm 5 · Crack 7 · Whirl (sneak) 12 biomass", "Lure I"), diamond.lore());
		assertEquals(List.of("Your own arm, reshaped.", "F: Snare 8 · Disarm 6 · Crack 8 · Whirl (sneak) 14 biomass", "Lure II", "Luck of the Sea I"), netherite.lore());
		assertEquals(List.of(0, 1, 2), List.of(iron.lure(), diamond.lure(), netherite.lure()));
		assertEquals(List.of(0, 0, 1), List.of(iron.luckOfTheSea(), diamond.luckOfTheSea(), netherite.luckOfTheSea()));
		assertEquals("FISHING_ROD", netherite.material());
		assertEquals(0, netherite.fortune(), "no Fortune on a rod");
		assertEquals(0, ArmItemSpecs.of("katana", 3, true, 0, 0, 0, "").lure());
		assertFalse(netherite.hideDurability(), "Survival shows the biomass bar");
	}

	@Test
	void aCaughtFishMayGiveABitOfBiomass() throws Exception {
		WhipSettings d = WhipSettings.DEFAULTS;
		assertEquals(0.2, d.fishBiomassChance(), 1e-9);
		assertEquals(500, d.fishBiomassMilli());
		ConfigurationSection whip = Objects.requireNonNull(load("abilities.yml").getConfigurationSection("arms.forms.whip"));
		assertEquals(0.2, whip.getDouble("fish-biomass-chance"), 1e-9, "the file says what the code says");
		assertEquals(0.5, whip.getDouble("fish-biomass"), 1e-9);
		// the roll decides, below the chance only
		assertEquals(500, d.fishGainMilli(0.0));
		assertEquals(500, d.fishGainMilli(0.199));
		assertEquals(0, d.fishGainMilli(0.2), "a roll at the chance misses");
		assertEquals(0, d.fishGainMilli(0.99));
		// a server's values: read, clamped, and a bad one takes the default
		WhipSettings s = WhipSettings.parse(Map.of("fish-biomass-chance", 0.5, "fish-biomass", 2));
		assertEquals(0.5, s.fishBiomassChance(), 1e-9);
		assertEquals(2000, s.fishBiomassMilli());
		assertEquals(1.0, WhipSettings.parse(Map.of("fish-biomass-chance", 7, "enabled", true)).fishBiomassChance(), 1e-9, "a chance is at most 1");
		assertEquals(500, WhipSettings.parse(Map.of("fish-biomass-chance", 1, "fish-biomass", "oops")).fishGainMilli(0.99), "a bad amount is the default");
		WhipSettings never = WhipSettings.parse(Map.of("fish-biomass-chance", 0));
		assertEquals(0, never.fishGainMilli(0.0), "a chance of 0 never gives");
		WhipSettings nothing = WhipSettings.parse(Map.of("fish-biomass", 0, "fish-biomass-chance", 1));
		assertEquals(0, nothing.fishGainMilli(0.0), "an amount of 0 gives nothing");
		assertEquals(0, WhipSettings.parse(Map.of("fish-biomass", -3, "fish-biomass-chance", 1)).fishGainMilli(0.0), "never negative");
	}
}
