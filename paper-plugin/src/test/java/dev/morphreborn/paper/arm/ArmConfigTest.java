package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.ArmConfig.BlockSpecials;
import dev.morphreborn.paper.arm.rules.ArmConfig.InventorySafety;
import dev.morphreborn.paper.arm.rules.ArmConfig.Levels;
import dev.morphreborn.paper.arm.rules.ArmTiers;
import dev.morphreborn.paper.arm.rules.CommandGuard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** arms: and arm-cost: read into typed settings: defaults, clamping, and the bundled section laid underneath. */
class ArmConfigTest {
	/** A map from key, value pairs, as YAML would give one. */
	private static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	private static ArmConfig parse(Map<String, Object> arms, Map<String, Object> cost) {
		return ArmConfig.parse(arms, cost, Map.of());
	}

	@Test
	void emptyMapsAreTheDefaults() {
		assertEquals(ArmConfig.DEFAULTS, ArmConfig.parse(Map.of(), Map.of(), Map.of()));
		assertEquals(ArmConfig.DEFAULTS, ArmConfig.parse(null, null, null));
		assertEquals(ArmConfig.DEFAULTS.hashCode(), ArmConfig.parse(Map.of(), Map.of(), Map.of()).hashCode());
	}

	@Test
	void defaultsAreTheBundledValues() {
		ArmConfig c = ArmConfig.DEFAULTS;
		assertTrue(c.enabled());
		assertFalse(c.report());
		assertEquals(2, c.classicLevel());
		assertEquals(10, c.reformLockoutTicks());
		assertEquals(8, c.recallSeconds());
		assertEquals(1000, c.minToFormMilli());
		assertEquals(524_288, c.stashMaxBytes());
		assertEquals(InventorySafety.AUTO, c.inventorySafety());
		assertEquals(CommandGuard.Mode.LISTED, c.commandGuard().mode());
		assertEquals(20, c.commandGuard().listed().size());
		assertEquals(19, c.commandGuard().keep().size());
		assertEquals(BlockSpecials.ON, c.blockSpecials());
		assertTrue(c.players());
		assertEquals(2, c.playerFloor());
		assertEquals(0.5, c.lesserRate());
		assertEquals(0.5, c.passiveRate());
		assertTrue(c.disabledForms().isEmpty());
		// arm-cost, in milli
		assertTrue(c.costs().enabled());
		assertEquals(100, c.costs().perBlock());
		assertEquals(250, c.costs().perHit());
		assertEquals(100, c.costs().perTransform());
		assertEquals(1000, c.costs().perBolt());
		assertEquals(1000, c.costs().toolDrainPerMinute());
		assertEquals(500, c.costs().lanternDrainPerMinute());
		assertEquals(15_000, c.costs().special("katana", 2));
		assertEquals(12_000, c.costs().special("pickaxe", 3));
		assertEquals(10_000, c.costs().special("bow-triple", 3));
		assertEquals(0, c.costs().special("bow", 3), "the Harpoon Shot's row is gone");
		assertEquals(22, c.costs().specials().size(), "seven specials (two for the axe), the whip's four moves and its three line costs, the new forms' six and the accessories' two");
		assertEquals(500, c.costs().accessoryDrainPerMinute("lantern"));
		assertEquals(500, c.costs().accessoryDrainPerMinute("shield"));
		assertEquals(250, c.costs().accessoryDrainPerMinute("spyglass"));
		assertEquals(250, c.costs().accessoryDrainPerMinute("flint"));
		assertEquals(0, c.costs().accessoryDrainPerMinute("nothing"));
		// Draining Cut (3.7)
		ArmConfig.Katana k = c.katana();
		assertEquals("Draining Cut", k.name());
		assertEquals(new Levels(4.5, 5.0, 5.5), k.reach());
		assertEquals(new Levels(120, 135, 150), k.arc());
		assertEquals(2.5, k.band());
		assertEquals(new Levels(4, 6, 8), k.maxTargets());
		assertEquals(new Levels(6, 7, 8), k.damage());
		assertEquals(new Levels(0, 1, 2), k.latchPulses());
		assertEquals(new Levels(0, 1.5, 2.0), k.latchDamage());
		assertEquals(0.5, k.healRatio());
		assertEquals(new Levels(1.0, 1.5, 2.0), k.healPerPulse());
		assertEquals(new Levels(2, 3, 4), k.healPerTarget());
		assertEquals(new Levels(6, 9, 12), k.healCap());
		assertEquals(new Levels(140, 120, 100), k.cooldownTicks());
		// Vein Rip (3.8)
		ArmConfig.Pickaxe p = c.pickaxe();
		assertEquals("Vein Rip", p.name());
		assertEquals(new Levels(1, 2, 3), p.fortune());
		assertEquals(new Levels(5, 6, 7), p.reach());
		assertEquals(new Levels(8, 16, 32), p.maxBlocks());
		assertEquals(6, p.maxOffset());
		assertEquals(15, p.rateTenths(2));
		assertEquals(64, p.visitCap(2));
		assertTrue(p.pullDrops());
		assertEquals(new Levels(160, 120, 80), p.cooldownTicks());
		assertTrue(p.extraOreGroups().isEmpty());
	}

	@Test
	void negativeValuesClampToZero() {
		Map<String, Object> arms = map("reform-lockout-ticks", -5, "recall-seconds", -3, "min-biomass-to-form", -1, "stash-max-bytes", -1, "player-floor", -2,
				"lesser-rate", -0.5, "forms", map("katana", map("special", map("reach", List.of(-1, -2, -3), "cooldown", List.of(-7, 6, 5), "band", -1)),
						"pickaxe", map("special", map("max-offset", -6, "max-blocks", List.of(-8, 16, 32)))));
		Map<String, Object> cost = map("per-block", -0.1, "per-hit", -1, "drain-per-minute", map("tool", -1), "specials", map("katana", List.of(-12, 15, 18)));
		ArmConfig c = parse(arms, cost);
		assertEquals(0, c.reformLockoutTicks());
		assertEquals(0, c.recallSeconds());
		assertEquals(0, c.minToFormMilli());
		assertEquals(0, c.stashMaxBytes());
		assertEquals(0, c.playerFloor());
		assertEquals(0, c.lesserRate());
		assertEquals(new Levels(0, 0, 0), c.katana().reach());
		assertEquals(new Levels(0, 120, 100), c.katana().cooldownTicks());
		assertEquals(0, c.katana().band());
		assertEquals(0, c.pickaxe().maxOffset());
		assertEquals(0, c.pickaxe().maxBlocks().intAt(1));
		assertEquals(0, c.costs().perBlock());
		assertEquals(0, c.costs().perHit());
		assertEquals(0, c.costs().toolDrainPerMinute());
		assertEquals(0, c.costs().special("katana", 1));
		assertEquals(15_000, c.costs().special("katana", 2));
	}

	@Test
	void levelsAreClampedToTheirRange() {
		assertEquals(3, parse(map("classic-level", 9), Map.of()).classicLevel());
		assertEquals(1, parse(map("classic-level", 0), Map.of()).classicLevel());
		assertEquals(1, parse(map("lesser-rate", 4), Map.of()).lesserRate());
	}

	@Test
	void bundledMergeFillsMissingKeys() {
		Map<String, Object> server = map("classic-level", 3, "forms", map("katana", map("special", map("reach", List.of(6, 6, 6)))));
		Map<String, Object> bundled = map("classic-level", 1, "recall-seconds", 4, "forms",
				map("katana", map("special", map("reach", List.of(1, 1, 1), "damage", List.of(9, 9, 9))), "axe", map("cooldown", List.of(8, 7, 6))),
				"lantern", map("reach", List.of(16, 24, 32)));
		ArmConfig c = ArmConfig.parse(server, Map.of(), bundled);
		assertEquals(3, c.classicLevel(), "the server's own value wins");
		assertEquals(4, c.recallSeconds(), "a key the server lacks comes from the bundled section");
		assertEquals(new Levels(6, 6, 6), c.katana().reach(), "merged key by key inside nested sections");
		assertEquals(new Levels(9, 9, 9), c.katana().damage());
		assertEquals(List.of(8, 7, 6), c.form("axe").get("cooldown"), "phase 2 forms' raw sections are merged too");
		assertEquals(List.of(16, 24, 32), c.rawLantern().get("reach"));
		assertEquals(Map.of(), c.form("shovel"));
	}

	@Test
	void serverWithoutArmsGetsTheBundledSection() {
		Map<String, Object> bundled = map("enabled", false, "players", false);
		ArmConfig c = ArmConfig.parse(Map.of(), Map.of(), bundled);
		assertFalse(c.enabled());
		assertFalse(c.players());
	}

	@Test
	void yamlOnAndOffAreBooleans() {
		assertEquals(BlockSpecials.ON, parse(map("block-specials", true), Map.of()).blockSpecials());
		assertEquals(BlockSpecials.OFF, parse(map("block-specials", false), Map.of()).blockSpecials());
		assertEquals(BlockSpecials.GAMERULE, parse(map("block-specials", "gamerule"), Map.of()).blockSpecials());
		assertEquals(InventorySafety.ON, parse(map("inventory-safety", true), Map.of()).inventorySafety());
		assertEquals(InventorySafety.OFF, parse(map("inventory-safety", false), Map.of()).inventorySafety());
		assertEquals(InventorySafety.AUTO, parse(map("inventory-safety", "auto"), Map.of()).inventorySafety());
		assertEquals(CommandGuard.Mode.OFF, parse(map("command-guard", map("mode", false)), Map.of()).commandGuard().mode());
	}

	@Test
	void flattenedSectionKeysReadAsNested() {
		// what ConfigurationSection.getValues(true) gives: dotted keys, beside section objects that aren't maps
		Map<String, Object> flat = map("forms", new Object(), "forms.katana", new Object(), "forms.katana.special.damage", List.of(9, 9, 9),
				"forms.axe.enabled", false, "classic-level", 3);
		Map<String, Object> nested = map("forms", map("katana", map("special", map("damage", List.of(9, 9, 9))), "axe", map("enabled", false)), "classic-level",
				3);
		assertEquals(parse(nested, Map.of()), parse(flat, Map.of()));
		assertFalse(parse(flat, Map.of()).formEnabled("axe"));
	}

	@Test
	void levelShorthands() {
		ArmConfig one = parse(map("forms", map("katana", map("special", map("damage", 10)))), Map.of());
		assertEquals(new Levels(10, 10, 10), one.katana().damage(), "one number for every level");
		ArmConfig two = parse(map("forms", map("katana", map("special", map("damage", List.of(5, 6))))), Map.of());
		assertEquals(new Levels(5, 6, 6), two.katana().damage(), "a short list repeats its last");
		ArmConfig bad = parse(map("forms", map("katana", map("special", map("damage", List.of("x", 7, 8))))), Map.of());
		assertEquals(new Levels(6, 7, 8), bad.katana().damage(), "a bad entry takes that level's default");
	}

	@Test
	void costsOffMakesEverythingFree() {
		ArmConfig c = parse(Map.of(), map("enabled", false));
		assertFalse(c.costs().enabled());
		assertEquals(0, c.costs().charge(c.costs().perBlock()));
		assertEquals(0, c.costs().special("katana", 3));
		assertEquals(100, c.costs().perBlock(), "the value itself is kept for when costs are switched back on");
	}

	@Test
	void specialCostsOverrideAndAdd() {
		ArmConfig c = parse(Map.of(), map("specials", map("katana", List.of(1, 2, 3), "whip", List.of(4, 5, 6))));
		assertEquals(2000, c.costs().special("katana", 2));
		assertEquals(6000, c.costs().special("whip", 3));
		assertEquals(10_000, c.costs().special("pickaxe", 2), "the rest keep their defaults");
		assertEquals(0, c.costs().special("nothing", 1));
	}

	@Test
	void fortuneByForm() {
		ArmConfig c = parse(map("forms", map("shovel", map("fortune", List.of(0, 1, 2)), "pickaxe", map("fortune", List.of(2, 2, 3)))), Map.of());
		assertEquals(2, c.fortune("pickaxe", 1));
		assertEquals(0, c.fortune("shovel", 1));
		assertEquals(2, c.fortune("shovel", 3));
		assertEquals(3, c.fortune("sickle", 3), "the level, by default");
		assertEquals(0, c.fortune("katana", 3), "no Fortune on a katana");
		assertEquals(3, c.fortune("axe", 3), "the axe has Fortune, the level by default");
		ArmConfig axe = parse(map("forms", map("axe", map("fortune", List.of(0, 0, 1)))), Map.of());
		assertEquals(0, axe.fortune("axe", 2));
		assertEquals(1, axe.fortune("axe", 3));
	}

	@Test
	void efficiencyByForm() {
		ArmConfig defaults = ArmConfig.parse(Map.of(), Map.of(), Map.of());
		for (String form : List.of("pickaxe", "axe", "shovel", "sickle")) {
			for (int level = 1; level <= 3; level++) assertEquals(level, defaults.efficiency(form, level), form + " level " + level + ": the level, by default");
		}
		for (String form : List.of("katana", "bow", "whip")) assertEquals(0, defaults.efficiency(form, 3), "no Efficiency on the " + form);
		ArmConfig c = parse(map("forms", map("shovel", map("efficiency", List.of(0, 1, 2)), "pickaxe", map("efficiency", List.of(2, 2, 5)),
				"axe", map("efficiency", 4), "katana", map("efficiency", 3))), Map.of());
		assertEquals(0, c.efficiency("shovel", 1), "0 is none");
		assertEquals(2, c.efficiency("shovel", 3));
		assertEquals(5, c.efficiency("pickaxe", 3));
		assertEquals(4, c.efficiency("axe", 1), "one number is every level");
		assertEquals(3, c.efficiency("sickle", 3), "a form that doesn't say takes the default");
		assertEquals(0, c.efficiency("katana", 3), "never on a katana, whatever the file says");
		assertEquals(0, parse(map("forms", map("sickle", map("efficiency", List.of(-4, 1, 2)))), Map.of()).efficiency("sickle", 1), "never negative");
		assertEquals(2, parse(map("forms", map("sickle", map("efficiency", List.of(1, 2)))), Map.of()).efficiency("sickle", 3), "a short list repeats its last");
		assertEquals(3, parse(map("forms", map("sickle", map("efficiency", "oops"))), Map.of()).efficiency("sickle", 3), "an unreadable value takes the default");
	}

	@Test
	void formsAndLanternSwitchOff() {
		ArmConfig c = parse(map("forms", map("bow", map("enabled", false)), "lantern", map("enabled", "off")), Map.of());
		assertFalse(c.formEnabled("bow"));
		assertFalse(c.formEnabled("lantern"));
		assertTrue(c.formEnabled("katana"));
		assertFalse(parse(map("enabled", false), Map.of()).formEnabled("katana"), "arms off switches every form off");
	}

	@Test
	void commandListsAreNormalised() {
		ArmConfig c = parse(map("command-guard", map("mode", "all", "commands", List.of("/Sell", "essentials:hat"), "keep", List.of("Home"))), Map.of());
		assertEquals(CommandGuard.Mode.ALL, c.commandGuard().mode());
		assertTrue(c.commandGuard().listed().contains("sell"));
		assertTrue(c.commandGuard().listed().contains("hat"));
		assertTrue(c.commandGuard().keep().contains("home"));
		assertFalse(c.commandGuard().retractFirst("home", null));
		assertTrue(c.commandGuard().retractFirst("tardis", null));
	}

	@Test
	void extraReach() {
		ArmConfig defaults = ArmConfig.parse(Map.of(), Map.of(), Map.of());
		assertEquals(0.5, defaults.extraReach("katana", 1), 1e-9, "the katana's longer blade reaches further");
		assertEquals(0.75, defaults.extraReach("katana", 2), 1e-9);
		assertEquals(1.0, defaults.extraReach("katana", 3), 1e-9);
		assertEquals(0, defaults.extraReach("pickaxe", 3), 1e-9, "the pickaxe adds none");
		double[][] reach = {{0.2, 0.3, 0.4}, {0.2, 0.25, 0.3}, {0, 0.25, 0.5}};
		String[] longer = {"axe", "shovel", "sickle"};
		for (int f = 0; f < longer.length; f++) {
			for (int level = 1; level <= 3; level++) assertEquals(reach[f][level - 1], defaults.extraReach(longer[f], level), 1e-9, longer[f] + " level " + level);
		}
		for (String form : List.of("bow", "lantern")) {
			for (int level = 1; level <= 3; level++) assertEquals(0, defaults.extraReach(form, level), 1e-9, form + " adds none");
		}
		// the whip's left-click reaches 6 / 6.5 / 7 blocks: the player's 3 and these (2026-11-16)
		assertEquals(List.of(3.0, 3.5, 4.0), List.of(defaults.extraReach("whip", 1), defaults.extraReach("whip", 2), defaults.extraReach("whip", 3)));
		assertEquals(List.of(6.0, 6.5, 7.0), List.of(ArmTiers.whipStrikeReach(1), ArmTiers.whipStrikeReach(2), ArmTiers.whipStrikeReach(3)));
		ArmConfig axe = parse(map("forms", map("axe", map("extra-reach", List.of(0, 0.5, 1)))), Map.of());
		assertEquals(0, axe.extraReach("axe", 1), 1e-9, "a server's list wins");
		assertEquals(1, axe.extraReach("axe", 3), 1e-9);
		assertEquals(0.3, axe.extraReach("shovel", 3), 1e-9, "and the other forms keep their defaults");
		ArmConfig off = parse(map("forms", map("katana", map("extra-reach", 0))), Map.of());
		assertEquals(0, off.extraReach("katana", 2), 1e-9, "0 turns it off (anticheats)");
		ArmConfig huge = parse(map("forms", map("katana", map("extra-reach", List.of(-1, 2, 99)))), Map.of());
		assertEquals(0, huge.extraReach("katana", 1), 1e-9, "never negative");
		assertEquals(4.0, huge.extraReach("katana", 3), 1e-9, "capped at 4 blocks");
	}
}
