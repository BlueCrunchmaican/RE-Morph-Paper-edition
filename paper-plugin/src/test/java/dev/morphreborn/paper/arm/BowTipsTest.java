package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.api.ArmSpecialModule;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.BowSettings;
import dev.morphreborn.paper.arm.rules.BowTips;
import dev.morphreborn.paper.arm.rules.BowTips.Tip;
import dev.morphreborn.paper.arm.special.BowArm;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The bow's Triple Arrow and arrow tips: the tip cycle, the settings and their bundled rows, the wire numbers and the fan's maths. */
class BowTipsTest {
	private static final Predicate<String> NONE = id -> false;
	private static final Predicate<String> FLINT = "flint"::equals;
	private static final Predicate<String> SPYGLASS = "spyglass"::equals;
	private static final Predicate<String> BOTH = id -> id.equals("flint") || id.equals("spyglass");

	private static YamlConfiguration load(String file) throws Exception {
		YamlConfiguration yaml = new YamlConfiguration();
		try (Reader r = new InputStreamReader(Objects.requireNonNull(BowTipsTest.class.getResourceAsStream("/" + file)), StandardCharsets.UTF_8)) {
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

	// ------------------------------------------------------------------ the tips

	@Test
	void theCycleGoesThroughBleedSlownessPoisonAndNothingElseWithNoAccessory() {
		Tip t = Tip.BLEED;
		StringBuilder seen = new StringBuilder();
		for (int i = 0; i < 6; i++) {
			seen.append(t.label()).append(',');
			t = BowTips.next(t, NONE);
		}
		assertEquals("Bleed,Slowness,Poison,Bleed,Slowness,Poison,", seen.toString());
	}

	@Test
	void fireComesWithTheFlintAndGlowingWithTheSpyglass() {
		assertEquals(Tip.FIRE, BowTips.next(Tip.POISON, FLINT));
		assertEquals(Tip.BLEED, BowTips.next(Tip.FIRE, FLINT));
		assertEquals(Tip.GLOWING, BowTips.next(Tip.POISON, SPYGLASS));
		assertEquals(Tip.BLEED, BowTips.next(Tip.GLOWING, SPYGLASS));
		assertEquals(List.of(Tip.BLEED, Tip.SLOWNESS, Tip.POISON, Tip.FIRE, Tip.GLOWING, Tip.BLEED),
				List.of(Tip.BLEED, BowTips.next(Tip.BLEED, BOTH), BowTips.next(Tip.SLOWNESS, BOTH), BowTips.next(Tip.POISON, BOTH), BowTips.next(Tip.FIRE, BOTH),
						BowTips.next(Tip.GLOWING, BOTH)));
	}

	@Test
	void aTipWhoseAccessoryWentOutActsAsBleedButIsKept() {
		assertEquals(Tip.FIRE, BowTips.effective(Tip.FIRE, FLINT));
		assertEquals(Tip.BLEED, BowTips.effective(Tip.FIRE, NONE), "flint is off");
		assertEquals(Tip.BLEED, BowTips.effective(Tip.GLOWING, FLINT), "spyglass is off");
		assertEquals(Tip.GLOWING, BowTips.effective(Tip.GLOWING, BOTH));
		assertEquals(Tip.BLEED, BowTips.effective(null, BOTH), "nothing chosen is Bleed");
		assertEquals(Tip.SLOWNESS, BowTips.effective(Tip.SLOWNESS, NONE));
		// cycling from an accessory tip that went out starts from Bleed (the tip that was acting), not from where it was kept
		assertEquals(Tip.SLOWNESS, BowTips.next(BowTips.effective(Tip.FIRE, NONE), NONE));
	}

	@Test
	void savedNamesReadBackAndStrangersAreNothing() {
		for (Tip t : Tip.values()) assertSame(t, Tip.byName(t.name()));
		assertSame(Tip.POISON, Tip.byName("poison"));
		assertSame(Tip.FIRE, Tip.byName(" Fire "));
		assertNull(Tip.byName("freeze"));
		assertNull(Tip.byName(null));
		assertSame(Tip.BLEED, Tip.of(99), "an unknown wire id is Bleed");
	}

	@Test
	void everyAccessoryTipNamesAnAccessoryThatExists() {
		for (Tip t : Tip.values()) {
			if (t.accessory() != null) assertTrue(dev.morphreborn.paper.arm.rules.ArmTiers.ACCESSORIES.contains(t.accessory()), t.label());
		}
		assertEquals("flint", Tip.FIRE.accessory());
		assertEquals("spyglass", Tip.GLOWING.accessory());
		assertEquals(Set.of(0, 1, 2, 3, 4), Set.of(Tip.values()).stream().map(Tip::id).collect(java.util.stream.Collectors.toSet()));
	}

	// ------------------------------------------------------------------ the wire

	@Test
	void aSingleBoltsArgIsStillThePowerForBleed() {
		// a client that reads only the low byte (and the old server's arg) sees the same number
		assertEquals(100, BowTips.boltArg(100, Tip.BLEED, -1));
		assertEquals(37, BowTips.boltArg(37, Tip.BLEED, -1));
		assertEquals(100, BowTips.power(BowTips.boltArg(100, Tip.GLOWING, BowTips.FAN_RIGHT)));
	}

	@Test
	void boltArgsRoundTripPowerTipAndPlace() {
		for (Tip t : Tip.values()) {
			for (int place = -1; place < BowTips.FAN_SIZE; place++) {
				int arg = BowTips.boltArg(83, t, place);
				assertEquals(83, BowTips.power(arg));
				assertSame(t, BowTips.tip(arg));
				assertEquals(place >= 0, BowTips.fanned(arg));
				if (place >= 0) assertEquals(place, BowTips.fanPlace(arg));
				assertFalse(BowTips.armedMessage(arg));
				assertFalse(BowTips.tipMessage(arg));
			}
		}
	}

	@Test
	void theArmedAndTipMessagesAreDistinctFromBolts() {
		for (Tip t : Tip.values()) {
			int armed = BowTips.armedArg(t), tip = BowTips.tipArg(t);
			assertTrue(BowTips.armedMessage(armed));
			assertFalse(BowTips.tipMessage(armed));
			assertTrue(BowTips.tipMessage(tip));
			assertFalse(BowTips.armedMessage(tip));
			assertSame(t, BowTips.tip(armed));
			assertSame(t, BowTips.tip(tip));
			assertFalse(BowTips.fanned(armed));
			assertEquals(0, BowTips.power(armed), "no power: these carry no arrow");
		}
	}

	@Test
	void powerIsClampedToAPercentAndNeverSpillsIntoTheFlags() {
		assertEquals(100, BowTips.power(BowTips.boltArg(250, Tip.BLEED, -1)));
		assertEquals(0, BowTips.power(BowTips.boltArg(-4, Tip.POISON, 1)));
		assertSame(Tip.POISON, BowTips.tip(BowTips.boltArg(250, Tip.POISON, 2)));
	}

	// ------------------------------------------------------------------ the fan

	@Test
	void theFanOffsetsAreMinusZeroPlusTheSpread() {
		assertEquals(0, BowTips.fanOffset(BowTips.FAN_MIDDLE, 15));
		assertEquals(-15, BowTips.fanOffset(BowTips.FAN_LEFT, 15));
		assertEquals(15, BowTips.fanOffset(BowTips.FAN_RIGHT, 15));
		assertEquals(3, BowTips.FAN_SIZE);
	}

	@Test
	void turningKeepsTheSlopeAndTheHorizontalLength() {
		double[] v = BowTips.turn(0.3, -0.4, 0.5, 15);
		assertEquals(-0.4, v[1], 0.0);
		assertEquals(Math.hypot(0.3, 0.5), Math.hypot(v[0], v[2]), 1e-12);
		double[] same = BowTips.turn(0.3, -0.4, 0.5, 0);
		assertEquals(0.3, same[0], 1e-12);
		assertEquals(0.5, same[2], 1e-12);
	}

	@Test
	void positiveTurnsToThePlayersRightAsYawGrows() {
		// facing south (+z) the right hand is west (-x): the game's yaw 0 looks along +z and yaw 90 along -x
		double[] right = BowTips.turn(0, 0, 1, 15);
		assertTrue(right[0] < 0 && right[2] > 0, "south turned 15 right is south-west");
		assertEquals(-Math.sin(Math.toRadians(15)), right[0], 1e-12);
		double[] left = BowTips.turn(0, 0, 1, -15);
		assertTrue(left[0] > 0, "south turned 15 left is south-east");
		// a full 90 to the right from south is west
		double[] west = BowTips.turn(0, 0, 1, 90);
		assertEquals(-1, west[0], 1e-12);
		assertEquals(0, west[2], 1e-12);
	}

	@Test
	void theThreeBoltsAreSpreadFifteenDegreesApartAtTheDefault() {
		double angle = Math.toRadians(BowSettings.DEFAULTS.spreadDeg());
		double[] l = BowTips.turn(0, 0, 1, BowTips.fanOffset(BowTips.FAN_LEFT, BowSettings.DEFAULTS.spreadDeg()));
		double[] m = BowTips.turn(0, 0, 1, 0);
		double[] r = BowTips.turn(0, 0, 1, BowTips.fanOffset(BowTips.FAN_RIGHT, BowSettings.DEFAULTS.spreadDeg()));
		assertEquals(15, BowSettings.DEFAULTS.spreadDeg());
		assertEquals(angle, Math.acos(l[0] * m[0] + l[2] * m[2]), 1e-9);
		assertEquals(angle, Math.acos(r[0] * m[0] + r[2] * m[2]), 1e-9);
		assertEquals(2 * angle, Math.acos(l[0] * r[0] + l[2] * r[2]), 1e-9);
	}

	// ------------------------------------------------------------------ the settings

	@Test
	void theBundledSectionIsTheDefaults() throws Exception {
		ConfigurationSection bow = Objects.requireNonNull(load("abilities.yml").getConfigurationSection("arms.forms.bow"));
		assertEquals(BowSettings.DEFAULTS, BowSettings.parse(nested(bow)));
		assertEquals(BowSettings.DEFAULTS, BowSettings.parse(ArmConfig.parse(Map.of(), Map.of(), nested(Objects.requireNonNull(load("abilities.yml")
				.getConfigurationSection("arms")))).form("bow")), "through the arm config's merged section too");
	}

	@Test
	void theDefaultsAreTheChosenNumbers() {
		BowSettings s = BowSettings.DEFAULTS;
		assertEquals("Triple Arrow", s.tripleName());
		assertEquals(30, s.armedSeconds());
		assertEquals(600, s.armedTicks());
		assertEquals(120, s.cooldownTicks(1));
		assertEquals(100, s.cooldownTicks(2));
		assertEquals(80, s.cooldownTicks(3));
		assertEquals(2.0, s.boltDamage().at(1));
		assertEquals(3.0, s.boltDamage().at(3));
		// bleed: a pulse every second for 3 / 4 / 5 seconds
		assertEquals(20, s.bleedIntervalTicks());
		assertEquals(3, s.bleedPulses(1));
		assertEquals(4, s.bleedPulses(2));
		assertEquals(5, s.bleedPulses(3));
		assertEquals(0.5, s.bleedDamage().at(1));
		assertEquals(1.0, s.bleedDamage().at(3));
		assertEquals(60, BowSettings.ticks(s.slowSeconds(), 1));
		assertEquals(1, s.slowAmplifier().at(2));
		assertEquals(120, BowSettings.ticks(s.poisonSeconds(), 3));
		assertEquals(80, BowSettings.ticks(s.fireSeconds(), 1));
		assertEquals(200, BowSettings.ticks(s.glowSeconds(), 3));
	}

	@Test
	void badValuesTakeTheDefaultsAndNumbersAreClamped() {
		assertEquals(BowSettings.DEFAULTS, BowSettings.parse(null));
		assertEquals(BowSettings.DEFAULTS, BowSettings.parse(Map.of()));
		Map<String, Object> form = Map.of("bolt-damage", List.of(1, "x"), "triple", Map.of("spread", 500, "armed-seconds", -3, "name", " "),
				"tips", Map.of("bleed", Map.of("interval", 0.01, "seconds", 0), "slowness", "nonsense"));
		BowSettings s = BowSettings.parse(form);
		assertEquals(1.0, s.boltDamage().at(1));
		assertEquals(2.5, s.boltDamage().at(2), "a bad entry takes that level's default");
		assertEquals(3.0, s.boltDamage().at(3), "and the last entry repeats");
		assertEquals(BowSettings.MAX_SPREAD, s.spreadDeg());
		assertEquals("Triple Arrow", s.tripleName());
		assertEquals(1.0, s.armedSeconds());
		assertEquals(20, s.armedTicks(), "at least a second");
		assertEquals(BowSettings.MIN_BLEED_INTERVAL, s.bleedIntervalSeconds());
		assertEquals(0, s.bleedPulses(1), "no seconds, no bleed");
		assertEquals(BowSettings.DEFAULTS.slowSeconds(), s.slowSeconds());
	}

	@Test
	void aSlowerBleedIntervalMeansFewerPulses() {
		BowSettings s = BowSettings.parse(Map.of("tips", Map.of("bleed", Map.of("interval", 2, "seconds", List.of(3, 4, 5)))));
		assertEquals(40, s.bleedIntervalTicks());
		assertEquals(1, s.bleedPulses(1));
		assertEquals(2, s.bleedPulses(2));
		assertEquals(2, s.bleedPulses(3));
	}

	// ------------------------------------------------------------------ what the module says of itself

	@Test
	void theModuleIsTheTripleArrowWithTheBowsCostRow() {
		ArmSpecialModule m = new BowArm();
		assertEquals("bow", m.form());
		assertEquals("Triple Arrow", m.specialName(1));
		assertEquals(6_000, m.costMilli(1));
		assertEquals(8_000, m.costMilli(2));
		assertEquals(10_000, m.costMilli(3));
		assertEquals(120, m.cooldownTicks(1));
		assertEquals(80, m.cooldownTicks(3));
		assertFalse(m.breaksBlocks());
		assertFalse(m.running(null), "Triple Arrow ready is not a special under way: F must still reach it to put it away");
	}

	@Test
	void describeAndTheLoreLineSayTripleArrow() {
		ArmSpecialModule m = new BowArm();
		assertEquals("Triple Arrow: three bolts 15° apart on a full draw · bolts hit for 2.5 · tips last 4 s (bleed)", m.describe(2));
		assertFalse(m.describe(1).contains("Harpoon") || m.describe(1).contains("tether"));
		assertEquals("Triple Arrow 6 biomass · Sneak+F: arrow tip", m.loreLine(1, true, v -> v));
		assertEquals("Triple Arrow · Sneak+F: arrow tip", m.loreLine(1, false, v -> v));
		assertEquals("Triple Arrow · Sneak+F: arrow tip", m.loreLine(1, true, v -> 0));
	}

	@Test
	void theBowsDrawRulesAreTheGamesAndAFullDrawIsPowerOne() {
		assertFalse(BowArm.full(BowArm.power(19)));
		assertTrue(BowArm.full(BowArm.power(20)));
		assertTrue(BowArm.full(BowArm.power(40)));
		assertFalse(BowArm.full(0.99F));
	}

	@Test
	void theHarpoonsKeysAreGone() throws Exception {
		String abilities = new String(Objects.requireNonNull(BowTipsTest.class.getResourceAsStream("/abilities.yml")).readAllBytes(), StandardCharsets.UTF_8);
		String survival = new String(Objects.requireNonNull(BowTipsTest.class.getResourceAsStream("/survival.yml")).readAllBytes(), StandardCharsets.UTF_8);
		assertFalse(abilities.contains("pulls-players"), "the harpoon's settings");
		assertFalse(survival.contains("Harpoon"), "survival.yml still describes the harpoon");
		assertEquals(0, ArmConfig.DEFAULTS.costs().special("bow", 1));
	}
}
