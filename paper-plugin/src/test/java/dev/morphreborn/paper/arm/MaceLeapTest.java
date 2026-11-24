package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.api.ArmSpecialModule;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.ArmTiers;
import dev.morphreborn.paper.arm.rules.LeapTrack;
import dev.morphreborn.paper.arm.rules.MaceMath;
import dev.morphreborn.paper.arm.rules.MaceSettings;
import dev.morphreborn.paper.arm.special.MaceLeap;
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

/** The mace's Skyward Leap: the launch maths, the flight's track, the settings, the bundled rows and what the module says of itself. */
class MaceLeapTest {
	private static YamlConfiguration load(String file) throws Exception {
		YamlConfiguration yaml = new YamlConfiguration();
		try (Reader r = new InputStreamReader(Objects.requireNonNull(MaceLeapTest.class.getResourceAsStream("/" + file)), StandardCharsets.UTF_8)) {
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

	// ------------------------------------------------------------------ the maths

	@Test
	void aJumpRisesVanillasHeight() {
		// the model is vanilla's: a jump (0.42) rises 1.25 blocks
		assertEquals(1.2522, MaceMath.riseHeight(0.42), 0.001);
		assertEquals(0, MaceMath.riseHeight(0), 1e-9);
		assertEquals(0, MaceMath.riseHeight(-1), 1e-9);
	}

	@Test
	void theLaunchSpeedRisesTheHeightAsked() {
		for (double h : new double[] {2, 6, 8, 10, 25}) {
			double v = MaceMath.launchSpeed(h);
			assertEquals(h, MaceMath.riseHeight(v), 0.01, "height " + h);
			assertTrue(MaceMath.riseHeight(v - 0.01) < h, "and no slower would do");
		}
		assertEquals(0, MaceMath.launchSpeed(0), 1e-9);
		assertEquals(0, MaceMath.launchSpeed(-3), 1e-9);
		assertEquals(0, MaceMath.launchSpeed(Double.NaN), 1e-9);
		assertTrue(MaceMath.launchSpeed(10) > MaceMath.launchSpeed(8) && MaceMath.launchSpeed(8) > MaceMath.launchSpeed(6));
	}

	@Test
	void theRiseTakesAboutAsLongAsItShould() {
		assertEquals(6, MaceMath.riseTicks(0.42), "a jump's rise");
		int six = MaceMath.riseTicks(MaceMath.launchSpeed(6)), ten = MaceMath.riseTicks(MaceMath.launchSpeed(10));
		assertTrue(six > 10 && six < 25, "6 blocks: " + six);
		assertTrue(ten > six && ten < 35, "10 blocks: " + ten);
		assertEquals(0, MaceMath.riseTicks(0));
	}

	@Test
	void aCeilingShortensTheLeapOrRefusesIt() {
		assertEquals(8, MaceMath.plan(8, 12), 1e-9, "room to spare: the level's height");
		assertEquals(8, MaceMath.plan(8, 8), 1e-9, "exactly enough");
		assertEquals(4.75, MaceMath.plan(8, 5), 1e-9, "a ceiling at 5: just short of it");
		assertEquals(0, MaceMath.plan(8, 2.2), 1e-9, "under 2 blocks of rise there is no leap");
		assertEquals(2.0, MaceMath.plan(8, 2.25), 1e-9, "2 blocks is the least");
		assertEquals(0, MaceMath.plan(0, 20), 1e-9, "a height set to nothing is no leap");
	}

	@Test
	void vanillasSmashBonusIsTheFallsDamage() {
		assertEquals(0, MaceMath.smashBonus(1.5), 1e-9, "no smash at the threshold");
		assertEquals(0, MaceMath.smashBonus(0), 1e-9);
		assertEquals(12, MaceMath.smashBonus(3), 1e-9, "4 a block to 3");
		assertEquals(18, MaceMath.smashBonus(6), 1e-9, "then 2 a block to 8");
		assertEquals(22, MaceMath.smashBonus(8), 1e-9);
		assertEquals(24, MaceMath.smashBonus(10), 1e-9, "then 1 a block");
		assertTrue(MaceMath.smashBonus(10) > MaceMath.smashBonus(8) && MaceMath.smashBonus(8) > MaceMath.smashBonus(6), "each level smashes harder");
	}

	@Test
	void theFallRidesTheFxAsTenthsOfABlock() {
		assertEquals(60, MaceMath.fallTenths(6.0));
		assertEquals(0, MaceMath.fallTenths(-1));
		assertEquals(0, MaceMath.fallTenths(Double.NaN));
		assertEquals(MaceMath.MAX_TENTHS, MaceMath.fallTenths(5000), "never a runaway number");
	}

	@Test
	void theForgivenFallIsBoundedByThePlan() {
		// a leap of 8 lands from about 8: all of it forgiven, even a couple of blocks lower than the launch
		assertEquals(0, MaceMath.fallDamageAfter(5, 8.0, 8), 1e-9, "the leap's own landing");
		assertEquals(0, MaceMath.fallDamageAfter(7, 8 + MaceMath.FALL_MARGIN, 8), 1e-9, "a step or two lower than the launch");
		// steered off a 100-block cliff: only the plan's worth is forgiven
		assertEquals(97 - 8, MaceMath.fallDamageAfter(97, 100, 8), 1e-9, "the rest is real damage");
		assertEquals(2, MaceMath.fallDamageAfter(10, 8 + MaceMath.FALL_MARGIN + 0.1, 8), 1e-9);
		assertEquals(0, MaceMath.fallDamageAfter(5, 60, 8), 1e-9, "a plan bigger than the damage never leaves a negative one");
		assertEquals(0, MaceMath.fallDamageAfter(0, 100, 8), 1e-9, "no damage stays none");
		assertEquals(0, MaceMath.fallDamageAfter(Double.NaN, 100, 8), 1e-9);
		assertEquals(22, MaceMath.fallDamageAfter(30, Double.NaN, 8), 1e-9, "an unreadable fall is not forgiven past the plan");
	}

	@Test
	void aLeapMustStartFromRest() {
		assertTrue(MaceMath.MAX_START_FALL > 0 && MaceMath.MAX_START_FALL <= 0.5, "F needs a fall distance under half a block");
	}

	// ------------------------------------------------------------------ the flight

	@Test
	void aLeapLandsOnceThenTheProtectionEndsAfterAGrace() {
		LeapTrack t = new LeapTrack(100, 600);
		assertEquals(LeapTrack.Step.NONE, t.update(100, true, false, 0), "still on the ground the tick it was launched");
		assertEquals(LeapTrack.Step.NONE, t.update(101, true, false, 0), "the launch hasn't lifted the body yet");
		assertEquals(LeapTrack.Step.NONE, t.update(102, false, false, 0));
		assertTrue(t.airborne());
		assertEquals(LeapTrack.Step.NONE, t.update(110, false, false, 3.0));
		assertEquals(LeapTrack.Step.NONE, t.update(120, false, false, 7.9));
		assertEquals(LeapTrack.Step.LANDED, t.update(121, true, false, 0), "the ground contact resets the player's own fall distance");
		assertEquals(7.9, t.maxFall(), 1e-9, "and the largest one seen is what the landing effect carries");
		assertFalse(t.airborne());
		assertEquals(LeapTrack.Step.NONE, t.update(122, true, false, 0), "the fall damage of that landing may still be in this packet: the protection outlives it");
		assertEquals(LeapTrack.Step.OVER, t.update(123, true, false, 0), "and is cleared after: only this leap's landing");
	}

	@Test
	void theRealFallRidesTheLandingNotTheSample() {
		LeapTrack t = new LeapTrack(0, 600);
		t.update(1, false, false, 0);
		t.update(10, false, false, 5.0);
		assertEquals(6.0, t.landingFall(6), 1e-9, "no damage event: never under the plan (the sample was 5)");
		t.sawFall(6.2);
		t.update(11, true, false, 0);
		assertEquals(6.2, t.landingFall(6), 1e-9, "the fall at the damage event wins over the last sample (5.0)");
		assertEquals(6.2, t.maxFall(), 1e-9);
	}

	@Test
	void aShortLeapWithNoDamageEventFallsBackToThePlan() {
		LeapTrack t = new LeapTrack(0, 600);
		t.update(1, false, false, 0);
		t.update(8, false, false, 1.1);
		t.update(9, true, false, 0);
		assertEquals(2.5, t.landingFall(2.5), 1e-9, "the samples miss the last tick's descent");
		assertEquals(1.1, t.maxFall(), 1e-9);
	}

	@Test
	void aLaggyLiftoffIsGivenAboutASecond() {
		assertEquals(20, LeapTrack.LIFT_GRACE);
		LeapTrack t = new LeapTrack(0, 600);
		assertEquals(LeapTrack.Step.NONE, t.update(LeapTrack.LIFT_GRACE, true, false, 0), "still grounded 20 ticks in: a slow connection, not a failed launch");
		assertEquals(LeapTrack.Step.NONE, t.update(LeapTrack.LIFT_GRACE, false, false, 0));
		assertTrue(t.airborne());
	}

	@Test
	void aLaunchThatNeverLiftsIsCalledOff() {
		LeapTrack t = new LeapTrack(0, 600);
		assertEquals(LeapTrack.Step.NONE, t.update(LeapTrack.LIFT_GRACE, true, false, 0));
		assertEquals(LeapTrack.Step.OVER, t.update(LeapTrack.LIFT_GRACE + 1, true, false, 0), "no protection is left lying about");
	}

	@Test
	void waterAndOtherEndingsEndItAtOnce() {
		LeapTrack t = new LeapTrack(0, 600);
		t.update(1, false, false, 0);
		assertEquals(LeapTrack.Step.OVER, t.update(15, false, true, 5), "into water (which cancels a fall), a ladder, a vehicle, a glide");
	}

	@Test
	void aLeapThatNeverLandsTimesOut() {
		LeapTrack t = new LeapTrack(0, 100);
		t.update(1, false, false, 0);
		assertEquals(LeapTrack.Step.NONE, t.update(100, false, false, 40));
		assertEquals(LeapTrack.Step.OVER, t.update(101, false, false, 41), "over a void: protect-seconds is the most it lasts");
	}

	@Test
	void aSmashUsesTheFallSoTheGroundSendsNoSecondEffect() {
		LeapTrack t = new LeapTrack(0, 600);
		t.update(1, false, false, 0);
		assertFalse(t.smashed());
		t.smash(6.4);
		assertTrue(t.smashed());
		assertEquals(6.4, t.maxFall(), 1e-9);
		assertEquals(LeapTrack.Step.LANDED, t.update(30, true, false, 0), "the track still reports the landing (the protection runs its grace)");
		assertTrue(t.smashed(), "and the module sends nothing for it");
	}

	// ------------------------------------------------------------------ the settings

	@Test
	void theBundledSectionIsTheDefaults() throws Exception {
		ConfigurationSection mace = Objects.requireNonNull(load("abilities.yml").getConfigurationSection("arms.forms.mace"));
		assertEquals(MaceSettings.DEFAULTS, MaceSettings.parse(nested(mace)));
		assertEquals(MaceSettings.DEFAULTS, MaceSettings.parse(ArmConfig.parse(Map.of(), Map.of(), nested(Objects.requireNonNull(load("abilities.yml")
				.getConfigurationSection("arms")))).form("mace")), "through the arm config's merged section too");
	}

	@Test
	void theDefaultsAreTheDesignsNumbers() {
		MaceSettings s = MaceSettings.DEFAULTS;
		assertEquals("Skyward Leap", s.name());
		assertEquals(List.of(6.0, 8.0, 10.0), List.of(s.heightAt(1), s.heightAt(2), s.heightAt(3)), "6, 8 and 10 blocks by tier");
		assertEquals(List.of(200, 180, 160), List.of(s.cooldownTicks(1), s.cooldownTicks(2), s.cooldownTicks(3)), "10, 9 and 8 seconds");
		assertEquals(600, s.protectTicks());
	}

	@Test
	void aMissingSectionIsTheDefaults() {
		assertSame(MaceSettings.DEFAULTS, MaceSettings.parse(null));
		assertSame(MaceSettings.DEFAULTS, MaceSettings.parse(Map.of()));
		assertSame(MaceSettings.DEFAULTS, MaceSettings.parse(Map.of("enabled", true)), "a form section with no special: row");
		assertEquals(MaceSettings.DEFAULTS, MaceSettings.parse(Map.of("special", Map.of())));
	}

	@Test
	void aServersValuesAreReadAndBadOnesTakeTheDefault() {
		MaceSettings s = MaceSettings.parse(Map.of("special", Map.of("name", "Hop", "height", List.of(3, "oops"), "cooldown", 2, "protect-seconds", 5)));
		assertEquals("Hop", s.name());
		assertEquals(List.of(3.0, 8.0, 10.0), List.of(s.heightAt(1), s.heightAt(2), s.heightAt(3)), "an unreadable entry takes that level's default, and a missing level repeats the last entry (read as unreadable too)");
		assertEquals(40, s.cooldownTicks(1), "a number is every level");
		assertEquals(100, s.protectTicks());
		assertEquals(MaceMath.MAX_HEIGHT, MaceSettings.parse(Map.of("special", Map.of("height", 5000))).heightAt(1), 1e-9, "no leap past the sky");
		assertEquals(0, MaceSettings.parse(Map.of("special", Map.of("height", -4))).heightAt(1), 1e-9, "never negative");
		assertEquals(20, MaceSettings.parse(Map.of("special", Map.of("protect-seconds", 0))).protectTicks(), "at least a second");
	}

	@Test
	void theLevelsInWords() {
		assertEquals("Leaps 8 blocks · the fall adds 22 damage to the smash", MaceSettings.DEFAULTS.describe(2));
		assertEquals("Leaps 6 blocks · the fall adds 18 damage to the smash", MaceSettings.DEFAULTS.describe(1));
	}

	// ------------------------------------------------------------------ the module

	@Test
	void theModuleSpeaksForTheForm() {
		MaceLeap m = new MaceLeap();
		assertEquals("mace", m.form());
		assertFalse(m.breaksBlocks(), "a leap breaks nothing");
		assertEquals("Skyward Leap", m.specialName(1));
		assertEquals(List.of(8_000L, 10_000L, 12_000L), List.of(m.costMilli(1), m.costMilli(2), m.costMilli(3)), "survival.yml arm-cost.specials.mace");
		assertEquals(List.of(200, 180, 160), List.of(m.cooldownTicks(1), m.cooldownTicks(2), m.cooldownTicks(3)));
		assertEquals(m.cooldownTicks(1), m.cooldownTicks(0), "a level 0 reads as iron");
		assertEquals("Leaps 10 blocks · the fall adds 24 damage to the smash", m.describe(3));
		assertNull(m.loreLine(2, true, v -> v), "one move: the usual F line");
		m.load(Map.of("special", Map.of("name", "Hop", "cooldown", 1)));
		assertEquals("Hop", m.specialName(2));
		assertEquals(20, m.cooldownTicks(3));
	}

	@Test
	void theModuleIsLoadedByTheArmModules() {
		List<String> forms = ArmModules.specials(Logger.getAnonymousLogger()).stream().map(ArmSpecialModule::form).toList();
		assertTrue(forms.contains("mace"), "MaceLeap is registered and loads: " + forms);
	}

	@Test
	void theArmItemKeepsTheMacesSmash() {
		// MaceItem's smash is the item class's, so the arm must be a real mace; its postHurtEnemy (the fall distance reset after the smash)
		// only runs for an item with the WEAPON component, which ArmItems sets for a form with attack modifiers (itemDamagePerAttack 0)
		assertEquals("MACE", ArmTiers.material("mace"));
		assertTrue(ArmTiers.hasAttackModifiers("mace"), "no WEAPON component would mean no post-hit reset");
		for (int t = 1; t <= 3; t++) {
			assertEquals(5f, ArmTiers.attackDamage("mace", t), "vanilla's mace damage at every tier");
			assertEquals(-3.4f, ArmTiers.attackSpeed("mace", t));
		}
	}

	@Test
	void aMacesBlowIsBilledWhateverCausePaperGivesIt() {
		assertTrue(ArmTiers.isMaceBlow("mace", true, false), "a hit or a smash struck by the owner");
		assertFalse(ArmTiers.isMaceBlow("mace", false, false), "an arrow or a thrown item is not the mace");
		assertFalse(ArmTiers.isMaceBlow("mace", true, true), "thorns names the wearer as the damager too");
		assertFalse(ArmTiers.isMaceBlow("spear", true, false), "only the mace");
		assertTrue(ArmTiers.isWeaponDamageType("minecraft:mace_smash"));
	}
}
