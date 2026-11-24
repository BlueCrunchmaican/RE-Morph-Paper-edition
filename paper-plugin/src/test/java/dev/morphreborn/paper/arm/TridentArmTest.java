package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.api.ArmSpecialModule;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.ArmTiers;
import dev.morphreborn.paper.arm.rules.TridentFlight;
import dev.morphreborn.paper.arm.rules.TridentSettings;
import dev.morphreborn.paper.arm.special.TridentArm;
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
 * The trident arm (my design, 2026-11-16): its settings and bundled rows, the flight's decisions, the reel's maths (the trident
 * comes to the hand and never overshoots it; nothing here moves a player) and what the module says of itself.
 */
class TridentArmTest {
	private static YamlConfiguration load(String file) throws Exception {
		YamlConfiguration yaml = new YamlConfiguration();
		try (Reader r = new InputStreamReader(Objects.requireNonNull(TridentArmTest.class.getResourceAsStream("/" + file)), StandardCharsets.UTF_8)) {
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
		ConfigurationSection trident = Objects.requireNonNull(load("abilities.yml").getConfigurationSection("arms.forms.trident"));
		assertEquals(TridentSettings.DEFAULTS, TridentSettings.parse(nested(trident)));
		assertEquals(TridentSettings.DEFAULTS, TridentSettings.parse(ArmConfig.parse(Map.of(), Map.of(), nested(Objects.requireNonNull(load("abilities.yml")
				.getConfigurationSection("arms")))).form("trident")), "through the arm config's merged section too");
		assertEquals(TridentSettings.DEFAULTS, TridentSettings.parse(null));
		assertEquals(TridentSettings.DEFAULTS, TridentSettings.parse(Map.of()));
	}

	@Test
	void theDefaultsAreTheChosenNumbers() {
		TridentSettings s = TridentSettings.DEFAULTS;
		assertEquals("Recall", s.name());
		assertEquals(List.of(4.0, 5.0, 6.0), levels(s.recallDamage()));
		assertEquals(List.of(80, 70, 60), List.of(s.cooldownTicks(1), s.cooldownTicks(2), s.cooldownTicks(3)), "4, 3.5 and 3 seconds");
		assertEquals(List.of(8.0, 9.0, 10.0), levels(s.throwDamage()), "the game's own trident does 8; the tier adds");
		assertEquals(2.5, s.throwSpeed(), "vanilla's throw speed");
		assertEquals(List.of(32.0, 40.0, 48.0), levels(s.range()));
		assertEquals(List.of(2.0, 2.4, 2.8), levels(s.reelSpeed()));
		for (int level = 1; level <= 3; level++) {
			assertTrue(s.reelSpeed().at(level) < 2.5 * 1.2, "the pull is no faster than the throw was");
		}
	}

	@Test
	void settingsAreClampedAndABadValueTakesTheDefault() {
		TridentSettings d = TridentSettings.DEFAULTS;
		TridentSettings s = TridentSettings.parse(Map.of("special", Map.of("name", "  ", "damage", List.of(1, "x"), "cooldown", -5, "width", 99),
				"throw", Map.of("speed", 50, "flight-ticks", 0, "range", "far", "stuck-ticks", -3, "damage", 7),
				"reel", Map.of("speed", List.of(1.5), "accel", 9, "max-ticks", 1)));
		assertEquals("Recall", s.name(), "a blank name is the default");
		assertEquals(List.of(1.0, d.recallDamage().at(2), d.recallDamage().at(3)), levels(s.recallDamage()), "a bad entry takes that level's default");
		assertEquals(0, s.cooldownTicks(1), "a negative cooldown is none");
		assertEquals(4.0, s.recallWidth(), "the path's width is capped");
		assertEquals(5.0, s.throwSpeed());
		assertEquals(1, s.flightTicks());
		assertEquals(d.range(), s.range(), "an unreadable list is the default");
		assertEquals(0, s.stuckTicks());
		assertEquals(List.of(7.0, 7.0, 7.0), levels(s.throwDamage()), "one number is every level's");
		assertEquals(List.of(1.5, 1.5, 1.5), levels(s.reelSpeed()), "a short list repeats its last");
		assertEquals(2.0, s.reelAccel());
		assertEquals(5, s.reelMaxTicks());
	}

	@Test
	void describeSaysWhatRecallDoes() {
		assertEquals("Recall snaps the trident back at once, hitting everything on the way · Recall hits for 5 · thrown for 9", TridentSettings.DEFAULTS.describe(2));
	}

	// ------------------------------------------------------------------ the flight

	@Test
	void aFlightEndsOnAStrikeStuckTimeFlightTimeOrRange() {
		TridentSettings s = TridentSettings.DEFAULTS;
		TridentFlight.Flight f = new TridentFlight.Flight(100, s, 2);
		assertEquals(TridentFlight.Cue.NONE, f.update(101, false, false, 10));
		assertEquals(TridentFlight.Cue.STRUCK, f.update(102, true, false, 10), "a strike takes it back at once");
		TridentFlight.Flight out = new TridentFlight.Flight(0, s, 2);
		assertEquals(TridentFlight.Cue.RANGE, out.update(5, false, false, 40.5), "past the tier's range (40 at level 2)");
		assertEquals(TridentFlight.Cue.NONE, new TridentFlight.Flight(0, s, 1).update(5, false, false, 33 - 1), "level 1's range is 32");
		assertEquals(TridentFlight.Cue.RANGE, new TridentFlight.Flight(0, s, 1).update(5, false, false, 33));
		TridentFlight.Flight late = new TridentFlight.Flight(0, s, 3);
		assertEquals(TridentFlight.Cue.NONE, late.update(39, false, false, 5));
		assertEquals(TridentFlight.Cue.TIMEOUT, late.update(40, false, false, 5), "after flight-ticks");
	}

	@Test
	void restingInABlockEndsItAfterTheStuckTicksAndAFreshStartResetsTheCount() {
		TridentFlight.Flight f = new TridentFlight.Flight(0, 1000, 3, 100);
		assertEquals(TridentFlight.Cue.NONE, f.update(1, false, true, 5));
		assertEquals(TridentFlight.Cue.NONE, f.update(2, false, true, 5));
		assertEquals(TridentFlight.Cue.NONE, f.update(3, false, true, 5));
		assertEquals(TridentFlight.Cue.NONE, f.update(4, false, false, 5), "it came loose: the count starts over");
		assertEquals(TridentFlight.Cue.NONE, f.update(5, false, true, 5));
		assertEquals(TridentFlight.Cue.NONE, f.update(6, false, true, 5));
		assertEquals(TridentFlight.Cue.NONE, f.update(7, false, true, 5));
		assertEquals(TridentFlight.Cue.STUCK, f.update(8, false, true, 5), "stuck for 4 ticks with a limit of 3");
		assertEquals(0, new TridentFlight.Flight(0, 1000, 0, 100).ticksLeft(2000), "no time left is 0, never negative");
		assertEquals(30, new TridentFlight.Flight(10, 40, 0, 100).ticksLeft(20));
	}

	// ------------------------------------------------------------------ the reel

	@Test
	void theReelBringsTheTridentToAHandThatStaysAndNeverPassesIt() {
		for (int level = 1; level <= 3; level++) {
			double speed = TridentSettings.DEFAULTS.reelSpeed().at(level), accel = TridentSettings.DEFAULTS.reelAccel();
			double x = 0, last = 0;
			int n = 0;
			boolean arrived = false;
			while (n < 200 && !arrived) {
				TridentFlight.Step s = TridentFlight.reel(x, 0, 0, 45, 0, 0, n, speed, accel, 80);
				arrived = s.arrived();
				if (!arrived) {
					assertTrue(s.vx() > 0 && s.vy() == 0 && s.vz() == 0, "straight at the hand");
					assertTrue(s.vx() <= speed + 1e-9, "no faster than the tier's pull");
					assertTrue(s.vx() >= last - 1e-9 || x + s.vx() > 45 - TridentFlight.CATCH_RADIUS, "it speeds up (until the last step is cut short)");
					last = s.vx();
					x += s.vx();
					assertTrue(x < 45, "never past the hand");
				}
				n++;
			}
			assertTrue(arrived, "it arrives");
			assertTrue(45 - x <= TridentFlight.CATCH_RADIUS + 1e-9, "within the catch radius");
			assertTrue(n <= 80, "within the longest pull");
			assertEquals(n, TridentFlight.reelTicks(45, speed, accel, 80), 1, "the told length is the real one");
		}
	}

	@Test
	void theFirstStepStartsSlowAndAnArrivedTridentStops() {
		TridentFlight.Step first = TridentFlight.reel(0, 0, 0, 20, 0, 0, 0, 2.8, 0.3, 80);
		assertEquals(TridentFlight.START_SPEED, first.vx(), 1e-9);
		assertFalse(first.arrived());
		TridentFlight.Step close = TridentFlight.reel(0, 0, 0, 1.0, 0.5, 0.5, 3, 2.8, 0.3, 80);
		assertTrue(close.arrived(), "inside the catch radius it is back");
		assertEquals(0, close.vx() + close.vy() + close.vz(), 0, "an arrived trident is given no more speed");
		assertTrue(TridentFlight.reel(0, 0, 0, 50, 0, 0, 80, 2.8, 0.3, 80).arrived(), "the longest pull ends whatever the distance");
	}

	@Test
	void theReelFollowsAMovingHandInThreeDimensions() {
		double px = 10, py = 40, pz = -5, hx = 0, hy = 64, hz = 0;
		int n = 0;
		while (n < 80) {
			TridentFlight.Step s = TridentFlight.reel(px, py, pz, hx, hy, hz, n, 2.4, 0.3, 80);
			if (s.arrived()) break;
			// the hand walks away at a walking pace; the trident, faster, still catches it
			hx += 0.2;
			px += s.vx();
			py += s.vy();
			pz += s.vz();
			n++;
		}
		double d = Math.sqrt((px - hx) * (px - hx) + (py - hy) * (py - hy) + (pz - hz) * (pz - hz));
		assertTrue(n < 80, "it catches a hand that walks away");
		assertTrue(d <= TridentFlight.CATCH_RADIUS + 0.5, "and ends at the hand: " + d);
	}

	@Test
	void reelTicksIsBounded() {
		assertEquals(1, TridentFlight.reelTicks(0, 2, 0.3, 80), "at the hand already: one tick");
		assertEquals(80, TridentFlight.reelTicks(10_000, 2, 0.3, 80), "never more than the longest pull");
		assertTrue(TridentFlight.reelTicks(10, 2, 0.3, 80) < TridentFlight.reelTicks(40, 2, 0.3, 80), "further takes longer");
	}

	// ------------------------------------------------------------------ the module

	@Test
	void theModuleIsRegisteredAndSaysWhatItIs() {
		assertTrue(ArmModules.SPECIALS.contains("dev.morphreborn.paper.arm.special.TridentArm"));
		ArmSpecialModule found = null;
		for (ArmSpecialModule m : ArmModules.specials(Logger.getLogger("test"))) if (m.form().equals("trident")) found = m;
		assertNotNull(found, "TridentArm loads by name");
		assertSame(TridentArm.class, found.getClass());
		assertNotNull(found.listener());
		assertFalse(found.breaksBlocks());
		assertEquals("Recall", found.specialName(1));
		assertEquals(List.of(4_000L, 5_000L, 6_000L), List.of(found.costMilli(1), found.costMilli(2), found.costMilli(3)), "trident-recall 4, 5, 6 biomass");
		assertEquals(List.of(80, 70, 60), List.of(found.cooldownTicks(1), found.cooldownTicks(2), found.cooldownTicks(3)));
		assertTrue(found.describe(3).startsWith("Recall "));
		// F must work while the trident is away, which is exactly when a throw is out: the manager refuses F while a module says it is going
		assertFalse(found.running(null), "never running, whatever the player");
		assertEquals(List.of(500L, 500L, 500L), List.of(ArmConfig.DEFAULTS.costs().special("trident-throw", 1), ArmConfig.DEFAULTS.costs().special("trident-throw", 2),
				ArmConfig.DEFAULTS.costs().special("trident-throw", 3)), "a throw costs 0.5 biomass at every level");
	}

	@Test
	void theWireContractNumbersAreFixed() {
		// the clients (TridentFx) read these numbers; I fixed them 2026-11-16, and nothing is resent on a timer any more
		assertEquals(17, ArmProtocol.FX_TRIDENT);
		assertEquals(List.of(0, 1, 2, 3, 4, 5), List.of(TridentArm.FX_END, TridentArm.FX_THROW, TridentArm.FX_REEL, TridentArm.FX_RECALL, TridentArm.FX_CATCH, TridentArm.FX_STRIKE));
		assertTrue(ArmProtocol.newFormsFx(ArmProtocol.FX_TRIDENT), "only clients that know the new forms are sent it");
		assertFalse(ArmProtocol.statefulFx(ArmProtocol.FX_TRIDENT), "a client does not time it out on the stateful clock: the projectile's removal and FX_END end it");
	}

	@Test
	void theFormNeverCarriesAnEnchantmentThatMovesThePlayerOrReturnsTheItem() {
		// no Riptide (it launches the player) and no Loyalty (it puts the item in a slot): the arm builds only these enchantments, and the trident has none
		for (int tier = 1; tier <= 3; tier++) {
			assertEquals(0, ArmTiers.lure("trident", tier));
			assertEquals(0, ArmTiers.luckOfTheSea("trident", tier));
			assertFalse(ArmTiers.hasFortune("trident"));
			assertFalse(ArmTiers.hasEfficiency("trident"));
		}
		assertTrue(ArmTiers.billsOwnWear("trident"), "the throw's wear is cancelled and the module bills its own");
		assertEquals("TRIDENT", ArmTiers.material("trident"));
	}

	@Test
	void absorptionIsNettedOutOfTheFinalDamageNotAddedToTheHealthAgain() {
		// 4 health and 4 absorption under a 10 point blow: the absorption modifier takes 4 off, the final damage is 6 and kills (it is more than the 4 health)
		assertTrue(TridentArm.lethal(6, 4), "the old test compared 6 with health plus absorption (8) and missed the kill");
		assertTrue(TridentArm.lethal(4, 4));
		assertFalse(TridentArm.lethal(3.9, 4));
		assertFalse(TridentArm.lethal(0, 4), "a blow that did nothing never kills");
	}

	@Test
	void aBlowTheAbsorptionHeartsTookWholeStillWentThrough() {
		// the absorption modifier is negative (-8 here): the final damage is 0 but the blow cost the creature 8
		assertEquals(8.0, TridentArm.taken(0, -8), 1e-9);
		assertEquals(10.0, TridentArm.taken(6, -4), 1e-9, "6 off the hearts and 4 off the absorption");
		assertEquals(0.0, TridentArm.taken(0, 0), 1e-9, "a shield took it all: nothing went through");
		assertEquals(5.0, TridentArm.taken(5, 0), 1e-9);
	}
}
