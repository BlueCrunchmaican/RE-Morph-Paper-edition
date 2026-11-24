package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.api.ArmSpecialModule;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.ArmTiers;
import dev.morphreborn.paper.arm.rules.CrossbowBolt;
import dev.morphreborn.paper.arm.rules.CrossbowSettings;
import dev.morphreborn.paper.arm.special.CrossbowArm;
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

/**
 * The crossbow arm (my design note, 2026-11-16): its settings and bundled rows, the loaded and regrowing bolt's state (formed
 * loaded, a click fires it and it grows back in the time the game takes to charge a crossbow, Spine Burst takes it), the marks on its
 * projectiles, and what the module says of itself.
 */
class CrossbowArmTest {
	private static YamlConfiguration load(String file) throws Exception {
		YamlConfiguration yaml = new YamlConfiguration();
		try (Reader r = new InputStreamReader(Objects.requireNonNull(CrossbowArmTest.class.getResourceAsStream("/" + file)), StandardCharsets.UTF_8)) {
			yaml.load(r);
		}
		return yaml;
	}

	private static Map<String, Object> nested(ConfigurationSection s) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (String key : s.getKeys(false)) {
			Object v = s.get(key);
			out.put(key, v instanceof ConfigurationSection child ? nested(child) : v);
		}
		return out;
	}

	private static List<Double> levels(ArmConfig.Levels l) {
		return List.of(l.at(1), l.at(2), l.at(3));
	}

	// ------------------------------------------------------------------ settings

	@Test
	void theBundledSectionIsTheDefaults() throws Exception {
		ConfigurationSection crossbow = Objects.requireNonNull(load("abilities.yml").getConfigurationSection("arms.forms.crossbow"));
		assertEquals(CrossbowSettings.DEFAULTS, CrossbowSettings.parse(nested(crossbow)));
		assertEquals(CrossbowSettings.DEFAULTS, CrossbowSettings.parse(ArmConfig.parse(Map.of(), Map.of(), nested(Objects.requireNonNull(load("abilities.yml")
				.getConfigurationSection("arms")))).form("crossbow")), "through the arm config's merged section too");
		assertEquals(CrossbowSettings.DEFAULTS, CrossbowSettings.parse(null));
		assertEquals(CrossbowSettings.DEFAULTS, CrossbowSettings.parse(Map.of()));
	}

	@Test
	void theDefaultsAreTheChosenNumbers() {
		CrossbowSettings s = CrossbowSettings.DEFAULTS;
		assertEquals("Spine Burst", s.name());
		assertEquals(List.of(7.0, 8.0, 9.0), levels(s.boltDamage()));
		assertEquals(3.15, s.boltSpeed(), "the game's own crossbow speed");
		assertEquals(1.25, s.regrowSeconds(), "the game's own charge time");
		assertEquals(3, s.spines());
		assertEquals(3, s.pierce());
		assertEquals(2, s.pierceLevel(), "the game's piercing N stops an arrow after N + 1 creatures");
		assertEquals(List.of(3.0, 4.0, 5.0), levels(s.spineDamage()));
		assertEquals(List.of(160, 140, 120), List.of(s.cooldownTicks(1), s.cooldownTicks(2), s.cooldownTicks(3)), "8, 7 and 6 seconds");
		assertEquals(4, s.burstTicks(), "the first spine at once, the others every 2 ticks");
		assertTrue(s.gapTicks() >= 2 && s.gapTicks() <= 3, "my design: 2 to 3 ticks apart");
		assertEquals(15, s.spineLifeTicks(), "48 blocks at 3.5 a tick is 14 ticks, one more for the last of it");
		for (int level = 1; level <= 3; level++) {
			assertTrue(s.spineDamage().at(level) < s.boltDamage().at(level), "a spine is lighter than a bolt; the burst's three make up for it");
			assertTrue(s.spineDamage().at(level) * s.spines() > s.boltDamage().at(level), "a burst that lands whole beats one bolt");
		}
	}

	@Test
	void settingsAreClampedAndABadValueTakesTheDefault() {
		CrossbowSettings d = CrossbowSettings.DEFAULTS;
		CrossbowSettings s = CrossbowSettings.parse(Map.of("bolt-damage", List.of(1, "x"), "bolt-speed", 50, "regrow-seconds", -1,
				"special", Map.of("name", "  ", "spines", 99, "pierce", 0, "damage", 6, "speed", "fast", "range", 1000, "gap-ticks", 0, "cooldown", -5)));
		assertEquals("Spine Burst", s.name(), "a blank name is the default");
		assertEquals(List.of(1.0, d.boltDamage().at(2), d.boltDamage().at(3)), levels(s.boltDamage()), "a bad entry takes that level's default");
		assertEquals(6.0, s.boltSpeed());
		assertEquals(0.05, s.regrowSeconds());
		assertEquals(12, s.spines());
		assertEquals(1, s.pierce());
		assertEquals(0, s.pierceLevel(), "a spine that pierces 1 creature has no piercing at all");
		assertEquals(List.of(6.0, 6.0, 6.0), levels(s.spineDamage()), "one number is every level's");
		assertEquals(d.spineSpeed(), s.spineSpeed(), "an unreadable number is the default");
		assertEquals(128.0, s.spineRange());
		assertEquals(1, s.gapTicks());
		assertEquals(0, s.cooldownTicks(1), "a negative cooldown is none");
	}

	@Test
	void describeSaysWhatTheCrossbowDoes() {
		assertEquals("Spine Burst fires 3 spines in a line, each piercing up to 3 creatures · spines hit for 4 · bolts hit for 8", CrossbowSettings.DEFAULTS.describe(2));
	}

	// ------------------------------------------------------------------ the bolt's state

	@Test
	void anArmIsFormedLoadedAndAClickFiresItAtOnce() {
		CrossbowBolt b = new CrossbowBolt(77);
		assertTrue(b.loaded(), "formed loaded");
		assertEquals(CrossbowBolt.Phase.LOADED, b.phase());
		assertEquals(0, b.cycle());
		assertTrue(b.fired(100, 25));
		assertFalse(b.loaded(), "empty from the shot");
		assertEquals(CrossbowBolt.Phase.GROWING, b.phase());
		assertEquals(1, b.cycle());
		assertFalse(b.fired(101, 25), "a second click has no bolt to fire");
		assertEquals(1, b.cycle(), "and starts no second regrow");
	}

	@Test
	void theBoltGrowsBackInTheTimeTheGameTakesToChargeACrossbow() {
		CrossbowBolt b = new CrossbowBolt(1);
		b.fired(100, CrossbowBolt.growTicks(1.25, 0));
		assertEquals(125, b.readyAt(), "25 ticks, vanilla's 1.25 seconds");
		assertEquals(25, b.ticksLeft(100));
		b.advance(124);
		assertFalse(b.loaded());
		assertFalse(b.takeReady());
		assertEquals(1, b.ticksLeft(124));
		b.advance(125);
		assertTrue(b.loaded(), "whole at its tick");
		assertEquals(0, b.ticksLeft(125));
		assertTrue(b.takeReady(), "the ready message is due, once");
		assertFalse(b.takeReady());
		b.advance(500);
		assertFalse(b.takeReady(), "a bolt that stays whole has no second ready");
		assertTrue(b.fired(500, 25), "and fires again");
		assertEquals(2, b.cycle(), "each regrow has its own number");
	}

	@Test
	void aWholeBoltNoticedByAnotherCallerIsStillAnnouncedOnce() {
		CrossbowBolt b = new CrossbowBolt(1);
		b.fired(0, 25);
		b.advance(30); // a click's handler looked at the state first
		b.advance(31); // then the tick loop
		assertTrue(b.takeReady());
		assertFalse(b.takeReady());
	}

	@Test
	void quickChargeShortensTheRegrowAsTheGameWorksItOut() {
		// CrossbowItem.getChargeDuration: floor((1.25 - 0.25 * level) * 20)
		assertEquals(25, CrossbowBolt.growTicks(1.25, 0));
		assertEquals(20, CrossbowBolt.growTicks(1.25, 1));
		assertEquals(15, CrossbowBolt.growTicks(1.25, 2));
		assertEquals(10, CrossbowBolt.growTicks(1.25, 3));
		assertEquals(1, CrossbowBolt.growTicks(1.25, 9), "never instant: a bolt always takes a moment to grow");
		assertEquals(23, CrossbowBolt.growTicks(1.15, 0), "no rounding down from float noise");
		assertEquals(25, CrossbowBolt.growTicks(1.25, -2), "a negative level is none");
	}

	@Test
	void aFreshRegrowStartsItsOwnClock() {
		CrossbowBolt b = new CrossbowBolt(1);
		b.fired(10, 25);
		b.advance(35);
		b.fired(40, 20);
		assertEquals(60, b.readyAt());
		assertEquals(20, b.ticksLeft(40));
		assertEquals(2, b.cycle());
	}

	@Test
	void spineBurstTakesTheBoltAndTheRegrowStartsWhenTheLastSpineHasGone() {
		CrossbowBolt b = new CrossbowBolt(1);
		assertTrue(b.beginBurst());
		assertFalse(b.loaded());
		assertEquals(CrossbowBolt.Phase.BURSTING, b.phase());
		assertFalse(b.beginBurst(), "no second burst from an empty crossbow");
		assertFalse(b.fired(5, 25), "a click can't fire what the burst took");
		b.advance(10_000);
		assertEquals(CrossbowBolt.Phase.BURSTING, b.phase(), "no regrow while the spines are leaving, however long it takes");
		assertEquals(0, b.ticksLeft(10_000));
		b.endBurst(200, 25);
		assertEquals(CrossbowBolt.Phase.GROWING, b.phase());
		assertEquals(225, b.readyAt());
		assertEquals(1, b.cycle());
		b.endBurst(300, 25);
		assertEquals(225, b.readyAt(), "a second end changes nothing");
	}

	@Test
	void aGrowingBoltIsNeverTakenByABurst() {
		CrossbowBolt b = new CrossbowBolt(1);
		b.fired(0, 25);
		assertFalse(b.beginBurst(), "F with no whole bolt finds nothing");
		assertEquals(CrossbowBolt.Phase.GROWING, b.phase());
	}

	@Test
	void aGrowingBoltSurvivesWhateverTheArmSessionDoes() {
		// the state is the player's, not the session's (2026-11-16): it has no nonce, so a tuck and untuck, a re-form or a switch of form
		// and back (each a new nonce) cannot start it over loaded; time alone finishes the regrow
		CrossbowBolt b = new CrossbowBolt(9);
		b.fired(100, 25);
		b.advance(110); // the arm was tucked, and came back with a new nonce: the module hands back this same state
		assertFalse(b.loaded(), "still growing: no free reload");
		assertEquals(15, b.ticksLeft(110));
		b.advance(130); // away for the rest of the regrow
		assertTrue(b.loaded(), "a bolt that finished meanwhile is whole when the hand comes back");
	}

	@Test
	void aRegrowHasItsOwnSeedAndTotalLength() {
		CrossbowBolt b = new CrossbowBolt(5);
		b.fired(100, 25);
		int first = b.regrowSeed();
		assertEquals(25, b.totalTicks());
		assertTrue(first > 0, "never 0 or negative");
		assertEquals(first, b.regrowSeed(), "the same on every message of one regrow");
		b.advance(125);
		b.fired(200, 20);
		assertEquals(20, b.totalTicks());
		assertTrue(b.regrowSeed() != first, "a new regrow is a new seed: that is what tells a client to start over");
		assertTrue(new CrossbowBolt(6).regrowSeed() > 0);
		CrossbowBolt other = new CrossbowBolt(1_000_003);
		other.fired(0, 25);
		assertTrue(other.regrowSeed() != first, "another player's first regrow does not share it");
	}

	@Test
	void aBurstThatEndsInAnyCaseStartsTheRegrow() {
		CrossbowBolt b = new CrossbowBolt(1);
		b.beginBurst();
		b.endBurst(40, 25); // the arm went away mid-burst (or a stray state was found without its burst): the bolt still grows back
		assertEquals(CrossbowBolt.Phase.GROWING, b.phase());
		assertEquals(65, b.readyAt());
	}

	@Test
	void theRegrowIsSentAgainEveryTenTicksWhileItGrows() {
		CrossbowBolt b = new CrossbowBolt(1);
		assertFalse(b.resendDue(5), "a whole bolt sends nothing");
		b.fired(100, 25);
		assertFalse(b.resendDue(105));
		assertTrue(b.resendDue(110), "ten ticks after the first message");
		assertFalse(b.resendDue(111));
		assertTrue(b.resendDue(120));
		assertFalse(b.resendDue(125), "a bolt that is whole is not resent");
	}

	@Test
	void theCrossbowsChargeSoundsPlayAFifthAndHalfwayInEachOnce() {
		CrossbowBolt b = new CrossbowBolt(1);
		assertEquals(CrossbowBolt.SOUND_NONE, b.soundDue(0), "a whole bolt makes none");
		b.fired(100, 25);
		assertEquals(CrossbowBolt.SOUND_NONE, b.soundDue(103));
		assertEquals(CrossbowBolt.SOUND_START, b.soundDue(105), "20% of 25 ticks");
		assertEquals(CrossbowBolt.SOUND_NONE, b.soundDue(106), "once");
		assertEquals(CrossbowBolt.SOUND_NONE, b.soundDue(111));
		assertEquals(CrossbowBolt.SOUND_MID, b.soundDue(113), "50% of 25 ticks");
		assertEquals(CrossbowBolt.SOUND_NONE, b.soundDue(120));
		b.advance(125); // whole again: the next regrow starts the sounds over
		b.fired(130, 20);
		assertEquals(CrossbowBolt.SOUND_START, b.soundDue(134), "a fifth of 20 ticks");
	}

	// ------------------------------------------------------------------ Spine Burst's line and the marks

	@Test
	void theSpinesLeaveOneAfterAnotherInALine() {
		assertArrayEquals(new int[] {0, 2, 4}, CrossbowBolt.spineTicks(3, 2));
		assertArrayEquals(new int[] {0, 3, 6}, CrossbowBolt.spineTicks(3, 3));
		assertArrayEquals(new int[] {0}, CrossbowBolt.spineTicks(0, 0), "always at least one, always at least a tick apart");
		CrossbowSettings s = CrossbowSettings.DEFAULTS;
		int[] at = CrossbowBolt.spineTicks(s.spines(), s.gapTicks());
		assertEquals(s.burstTicks(), at[at.length - 1], "the burst lasts until its last spine");
	}

	@Test
	void aMarkSaysWhatAProjectileIsAndTheTierItWasShotAt() {
		for (int level = 1; level <= 3; level++) {
			for (int kind : new int[] {CrossbowBolt.KIND_BOLT, CrossbowBolt.KIND_SPINE}) {
				int mark = CrossbowBolt.mark(kind, level);
				assertEquals(kind, CrossbowBolt.kindOf(mark));
				assertEquals(level, CrossbowBolt.levelOf(mark));
			}
		}
		assertEquals(0, CrossbowBolt.kindOf(0), "no mark is neither");
		assertEquals(0, CrossbowBolt.kindOf(CrossbowBolt.mark(9, 1)), "an unknown kind is neither");
		assertEquals(3, CrossbowBolt.levelOf(CrossbowBolt.mark(CrossbowBolt.KIND_BOLT, 99)), "a level is clamped to the tiers");
	}

	// ------------------------------------------------------------------ the item and the module

	@Test
	void theArmIsARealCrossbowThatTheModuleLoads() {
		assertEquals("CROSSBOW", ArmTiers.material("crossbow"));
		assertEquals("minecraft:crossbow", ArmTiers.vanillaModel("crossbow", 2));
		assertEquals("", ArmTiers.drawAnimation("crossbow"), "no consumable: the use is the game's, the module owns the charged state");
		assertFalse(ArmTiers.hasAttackModifiers("crossbow"));
		assertFalse(ArmTiers.cooldownSweep("crossbow"));
		assertTrue(ArmTiers.billsOwnWear("crossbow"), "the module bills its bolts; the game's wear is only cancelled");
	}

	@Test
	void theModuleIsRegisteredAndSaysWhatItIs() {
		assertTrue(ArmModules.SPECIALS.contains("dev.morphreborn.paper.arm.special.CrossbowArm"));
		ArmSpecialModule found = null;
		for (ArmSpecialModule m : ArmModules.specials(Logger.getLogger("test"))) if (m.form().equals("crossbow")) found = m;
		assertNotNull(found, "CrossbowArm loads by name");
		assertSame(CrossbowArm.class, found.getClass());
		assertNotNull(found.listener());
		assertFalse(found.breaksBlocks());
		assertEquals("Spine Burst", found.specialName(1));
		assertEquals(List.of(10_000L, 12_000L, 15_000L), List.of(found.costMilli(1), found.costMilli(2), found.costMilli(3)), "specials.crossbow 10, 12, 15 biomass");
		assertEquals(List.of(160, 140, 120), List.of(found.cooldownTicks(1), found.cooldownTicks(2), found.cooldownTicks(3)));
		assertTrue(found.describe(3).startsWith("Spine Burst fires 3 spines"));
		assertEquals(ArmForm.CROSSBOW.id(), found.form());
		assertEquals(1_000L, ArmConfig.DEFAULTS.costs().perBolt(), "a bolt costs the usual 1 biomass, as the bow's");
	}

	@Test
	void theFxMovesAreNumberedAsTheClientsAreTold() {
		// the original contract's numbering, the one the client (CrossbowFx) reads: 1 BOLT, 2 SPINES, 3 REGROW
		assertEquals(List.of(1, 2, 3), List.of(CrossbowArm.FX_BOLT, CrossbowArm.FX_SPINES, CrossbowArm.FX_REGROW));
		assertEquals(18, ArmProtocol.FX_CROSSBOW);
	}
}
