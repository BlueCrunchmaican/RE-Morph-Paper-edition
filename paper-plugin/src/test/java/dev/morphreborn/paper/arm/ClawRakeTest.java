package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.api.ArmSpecialModule;
import dev.morphreborn.paper.arm.rules.ArcTargets;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.BleedLedger;
import dev.morphreborn.paper.arm.rules.ClawMath;
import dev.morphreborn.paper.arm.rules.ClawSettings;
import dev.morphreborn.paper.arm.special.ClawRake;
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

/** The claws' Rake: its settings, its bundled rows, its timeline and wire numbers, the bleed's clock, and what the module says of itself. */
class ClawRakeTest {
	private static YamlConfiguration load(String file) throws Exception {
		YamlConfiguration yaml = new YamlConfiguration();
		try (Reader r = new InputStreamReader(Objects.requireNonNull(ClawRakeTest.class.getResourceAsStream("/" + file)), StandardCharsets.UTF_8)) {
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

	// ------------------------------------------------------------------ settings

	@Test
	void theBundledSectionIsTheDefaults() throws Exception {
		ConfigurationSection claws = Objects.requireNonNull(load("abilities.yml").getConfigurationSection("arms.forms.claws"));
		assertEquals(ClawSettings.DEFAULTS, ClawSettings.parse(nested(claws)));
		assertEquals(ClawSettings.DEFAULTS, ClawSettings.parse(ArmConfig.parse(Map.of(), Map.of(), nested(Objects.requireNonNull(load("abilities.yml")
				.getConfigurationSection("arms")))).form("claws")), "through the arm config's merged section too");
	}

	@Test
	void theDefaultsAreTheDesignsNumbers() {
		ClawSettings s = ClawSettings.DEFAULTS;
		assertEquals("Rake", s.name());
		assertEquals(3, s.slashes(), "a three-slash sweep");
		assertEquals(100.0, s.arcDeg(), 1e-9, "in a 100-degree arc");
		assertEquals(List.of(3.5, 4.0, 4.5), List.of(s.reach().at(1), s.reach().at(2), s.reach().at(3)), "reach 3.5 / 4 / 4.5");
		assertEquals(List.of(35, 40, 45), List.of(s.reachTenths(1), s.reachTenths(2), s.reachTenths(3)));
		assertEquals(List.of(1.5, 2.0, 2.5), List.of(s.damage().at(1), s.damage().at(2), s.damage().at(3)), "damage by tier, per slash");
		assertEquals(List.of(3.0, 4.0, 5.0), List.of(s.bleedSeconds().at(1), s.bleedSeconds().at(2), s.bleedSeconds().at(3)), "bleeds 3 / 4 / 5 s");
		assertEquals(List.of(3, 4, 5), List.of(s.bleedPulses(1), s.bleedPulses(2), s.bleedPulses(3)), "a pulse a second");
		assertEquals(20, s.bleedIntervalTicks());
		assertEquals(List.of(140, 120, 100), List.of(s.cooldownTicks(1), s.cooldownTicks(2), s.cooldownTicks(3)));
		assertEquals(List.of(4.0, 6.0, 8.0), List.of(s.maxTargets().at(1), s.maxTargets().at(2), s.maxTargets().at(3)));
	}

	@Test
	void aMissingSectionIsTheDefaults() {
		assertSame(ClawSettings.DEFAULTS, ClawSettings.parse(null));
		assertSame(ClawSettings.DEFAULTS, ClawSettings.parse(Map.of()));
		assertSame(ClawSettings.DEFAULTS, ClawSettings.parse(Map.of("enabled", true)), "a form section with no special: row");
		assertEquals(ClawSettings.DEFAULTS, ClawSettings.parse(Map.of("special", Map.of())));
	}

	@Test
	void aServersValuesAreReadAndBadOnesTakeTheDefault() {
		Map<String, Object> form = Map.of("special", Map.of("name", "Maul", "slashes", 5, "damage", List.of(-1, "oops", 9), "reach", 99, "arc", 400, "bleed-seconds",
				List.of(2, 8), "bleed-damage", 0.5, "bleed-interval", 0.1, "max-targets", 0, "cooldown", 2));
		ClawSettings s = ClawSettings.parse(form);
		assertEquals("Maul", s.name());
		assertEquals(5, s.slashes());
		assertEquals(0.0, s.damage().at(1), 1e-9, "never negative");
		assertEquals(2.0, s.damage().at(2), 1e-9, "unreadable: that level's default");
		assertEquals(9.0, s.damage().at(3), 1e-9);
		assertEquals(ClawSettings.MAX_REACH, s.reach().at(1), 1e-9, "clamped: the wire carries the reach in six bits of tenths");
		assertEquals(180.0, s.arcDeg(), 1e-9, "no more than half the circle each way");
		assertEquals(List.of(2.0, 8.0, 8.0), List.of(s.bleedSeconds().at(1), s.bleedSeconds().at(2), s.bleedSeconds().at(3)), "fewer entries repeat the last");
		assertEquals(10, s.bleedIntervalTicks(), "never sooner than half a second: a creature shrugs off a hit inside its hurt window");
		assertEquals(1.0, s.maxTargets().at(1), 1e-9, "at least one");
		assertEquals(40, s.cooldownTicks(1), "a number is every level, seconds to ticks");
		assertEquals(ClawSettings.MAX_SLASHES, ClawSettings.parse(Map.of("special", Map.of("slashes", 99))).slashes());
		assertEquals(1, ClawSettings.parse(Map.of("special", Map.of("slashes", 0))).slashes());
	}

	@Test
	void aBleedOfNoTimeOrNoDamageIsNoBleed() {
		assertEquals(0, ClawSettings.parse(Map.of("special", Map.of("bleed-seconds", 0))).bleedPulses(1));
		assertEquals(0, ClawSettings.parse(Map.of("special", Map.of("bleed-damage", 0))).bleedPulses(1));
		assertEquals(1, ClawSettings.parse(Map.of("special", Map.of("bleed-seconds", 0.4))).bleedPulses(1), "a short bleed still pulses once");
		assertEquals(2, ClawSettings.parse(Map.of("special", Map.of("bleed-seconds", 4, "bleed-interval", 2))).bleedPulses(1), "the seconds over the interval");
	}

	@Test
	void theLevelsInWords() {
		assertEquals("3 slashes · 2 damage each · bleeds 4 s", ClawSettings.DEFAULTS.describe(2));
		assertEquals("3 slashes · 1.5 damage each · bleeds 3 s", ClawSettings.DEFAULTS.describe(1));
		assertEquals("1 slash · 2.5 damage each · bleeds 5 s", ClawSettings.parse(Map.of("special", Map.of("slashes", 1))).describe(3));
	}

	// ------------------------------------------------------------------ the module

	@Test
	void theModuleSpeaksForTheForm() {
		ClawRake m = new ClawRake();
		assertEquals("claws", m.form());
		assertFalse(m.breaksBlocks(), "a rake breaks nothing");
		assertEquals("Rake", m.specialName(1));
		assertEquals(List.of(8_000L, 10_000L, 12_000L), List.of(m.costMilli(1), m.costMilli(2), m.costMilli(3)), "survival.yml arm-cost.specials.claws");
		assertEquals(List.of(140, 120, 100), List.of(m.cooldownTicks(1), m.cooldownTicks(2), m.cooldownTicks(3)));
		assertEquals(m.cooldownTicks(1), m.cooldownTicks(0), "a level 0 reads as iron");
		assertEquals("3 slashes · 2.5 damage each · bleeds 5 s", m.describe(3));
		assertNull(m.loreLine(2, true, v -> v), "one move: the usual F line");
		assertNotNull(m.listener(), "it watches its own blows, and deaths and quits");
		m.load(Map.of("special", Map.of("name", "Maul", "cooldown", 1)));
		assertEquals("Maul", m.specialName(2));
		assertEquals(20, m.cooldownTicks(3));
	}

	@Test
	void theModuleIsLoadedByTheArmModules() {
		List<String> forms = ArmModules.specials(Logger.getAnonymousLogger()).stream().map(ArmSpecialModule::form).toList();
		assertTrue(forms.contains("claws"), "ClawRake is registered and loads: " + forms);
	}

	// ------------------------------------------------------------------ the timeline and the wire

	@Test
	void theSlashesLandOnTheTicksTheClientDrawsThem() {
		assertEquals(List.of(1, 4, 7), List.of(ClawMath.hitTick(0), ClawMath.hitTick(1), ClawMath.hitTick(2)), "the first on the tick after F, then one every swing");
		assertEquals(9, ClawMath.totalTicks(3));
		assertEquals(3, ClawMath.totalTicks(1), "even one slash takes a swing");
		assertEquals(3, ClawMath.totalTicks(0), "and none is read as one");
		assertTrue(ClawMath.rightToLeft(0), "the first slash is the vanilla swing");
		assertFalse(ClawMath.rightToLeft(1), "and the next comes back");
		assertTrue(ClawMath.rightToLeft(2));
		for (int i = 0; i < 3; i++) assertTrue(ClawMath.hitTick(i) < ClawMath.GAP * (i + 1), "a slash's hit lands inside its own swing");
	}

	@Test
	void theRakeArgRoundTrips() {
		int arg = ClawMath.rakeArg(3, 100, false, 40);
		assertEquals(ClawMath.MOVE_RAKE, ClawMath.move(arg));
		assertEquals(3, ClawMath.slashes(arg));
		assertEquals(100, ClawMath.arcDeg(arg));
		assertFalse(ClawMath.leftHanded(arg));
		assertEquals(ClawMath.GAP, ClawMath.gap(arg));
		assertEquals(40, ClawMath.reachTenths(arg));
		assertTrue(arg > 0, "never negative on the wire");
		int left = ClawMath.rakeArg(6, 180, true, 63);
		assertEquals(6, ClawMath.slashes(left));
		assertEquals(180, ClawMath.arcDeg(left));
		assertTrue(ClawMath.leftHanded(left));
		assertEquals(63, ClawMath.reachTenths(left));
		assertTrue(left > 0);
		// the fields don't leak into each other at their maxima
		int max = ClawMath.rakeArg(15, 1023, true, 63);
		assertEquals(List.of(15, 1023, ClawMath.GAP, 63), List.of(ClawMath.slashes(max), ClawMath.arcDeg(max), ClawMath.gap(max), ClawMath.reachTenths(max)));
		assertTrue(max > 0);
		// out-of-range inputs are clamped, not wrapped into the next field
		assertEquals(15, ClawMath.slashes(ClawMath.rakeArg(99, 100, false, 40)));
		assertEquals(63, ClawMath.reachTenths(ClawMath.rakeArg(3, 100, false, 500)));
		assertEquals(0, ClawMath.slashes(ClawMath.rakeArg(-4, 100, false, 40)));
	}

	@Test
	void aSlashOnlySwallowedByAnotherHurtWindowWasNotDealt() {
		assertTrue(ClawMath.dealt(2.0, false, false), "health fell");
		assertTrue(ClawMath.dealt(0, true, false), "its event ran and nothing cancelled it (a fully absorbed hit)");
		assertFalse(ClawMath.dealt(0, true, true), "a claim or a PvP rule cancelled it");
		assertFalse(ClawMath.dealt(0, false, false), "no event, no damage: vanilla's hurt window swallowed it, so no bleed and no window to clear");
	}

	@Test
	void theOtherArgsRoundTrip() {
		for (int i = 0; i < 6; i++) {
			int arg = ClawMath.slashArg(i);
			assertEquals(ClawMath.MOVE_SLASH, ClawMath.move(arg));
			assertEquals(i, ClawMath.slashIndex(arg));
		}
		int bleed = ClawMath.bleedArg(20);
		assertEquals(ClawMath.MOVE_BLEED, ClawMath.move(bleed));
		assertEquals(20, ClawMath.bleedInterval(bleed));
		assertEquals(255, ClawMath.bleedInterval(ClawMath.bleedArg(9999)), "clamped");
		assertEquals(List.of(1, 2, 3, 4, 5), List.of(ClawMath.MOVE_RAKE, ClawMath.MOVE_SLASH, ClawMath.MOVE_BLEED, ClawMath.MOVE_BLEED_PULSE, ClawMath.MOVE_BLEED_END),
				"never renumber: the client has the same numbers");
		assertEquals(ClawMath.MOVE_BLEED_PULSE, ClawMath.move(ClawMath.MOVE_BLEED_PULSE));
		assertEquals(ClawMath.MOVE_BLEED_END, ClawMath.move(ClawMath.MOVE_BLEED_END));
		assertEquals(19, ArmProtocol.FX_CLAWS, "the claws' FX kind");
		assertTrue(ArmProtocol.newFormsFx(ArmProtocol.FX_CLAWS), "only new clients are sent it");
		assertFalse(ArmProtocol.statefulFx(ArmProtocol.FX_CLAWS), "the bleed carries its own length, so nothing is resent");
	}

	@Test
	void theFanReachesWhatIsInFrontAndNotBehind() {
		// the fan the claws use: 100 degrees, reach 4 (a diamond level), seen from the eyes at the origin looking along +z (yaw 0)
		ClawSettings s = ClawSettings.DEFAULTS;
		ArcTargets.Candidate ahead = box(1, 0, 0, 2.5), side = box(2, 2.3, 0, 2.5), behind = box(3, 0, 0, -2.5), far = box(4, 0, 0, 7), edge = box(5, 3.2, 0, 2.5);
		List<ArcTargets.Hit> hits = ArcTargets.select(0, 0, 0, 0, s.reach().at(2), s.arcDeg(), s.band(), 8, false, List.of(ahead, side, behind, far, edge));
		assertEquals(List.of(1, 2), hits.stream().map(ArcTargets.Hit::id).sorted().toList(),
				"ahead and 43 degrees round are in the 100-degree fan; behind, out of reach and 52 degrees round are not");
		// level 3 reaches further than level 1
		ArcTargets.Candidate mid = box(6, 0, 0, 4.2);
		assertTrue(ArcTargets.select(0, 0, 0, 0, s.reach().at(1), s.arcDeg(), s.band(), 8, false, List.of(mid)).isEmpty());
		assertEquals(1, ArcTargets.select(0, 0, 0, 0, s.reach().at(3), s.arcDeg(), s.band(), 8, false, List.of(mid)).size());
	}

	@Test
	void protectedCreaturesNearerThanAValidOneDoNotTakeItsPlaceInTheCut() {
		// four protected animals nearer than one valid mob, max-targets 4: F must still find the mob, and the cut must keep it
		List<ArcTargets.Hit> fan = List.of(new ArcTargets.Hit(1, 0.1, 1), new ArcTargets.Hit(2, 0.2, 1.5), new ArcTargets.Hit(3, 0.3, 2), new ArcTargets.Hit(4, 0.4, 2.5),
				new ArcTargets.Hit(5, 0.5, 3));
		List<Integer> asked = new java.util.ArrayList<>();
		List<ArcTargets.Hit> cut = ClawMath.cut(fan, 4, true, id -> {
			asked.add(id);
			return id == 5;
		});
		assertEquals(List.of(5), cut.stream().map(ArcTargets.Hit::id).toList());
		assertEquals(List.of(1, 2, 3, 4, 5), asked, "nearest first");
		// with room for all of them a slash asks nobody (each hit tests itself), and F asks only those that matter, nearest first, stopping at the cut
		assertSame(fan, ClawMath.cut(fan, 8, false, id -> {
			throw new AssertionError("no probe needed");
		}));
		asked.clear();
		List<ArcTargets.Hit> two = ClawMath.cut(fan, 2, true, id -> {
			asked.add(id);
			return true;
		});
		assertEquals(List.of(1, 2), two.stream().map(ArcTargets.Hit::id).toList(), "kept in sweep order");
		assertEquals(List.of(1, 2), asked, "nobody beyond the cut is tested");
		// an empty fan asks nobody at all: a player behind the owner is never probed
		assertTrue(ClawMath.cut(List.of(), 4, true, id -> {
			throw new AssertionError("nobody to ask");
		}).isEmpty());
		// a slash over the cut probes, so the valid one is kept there too
		assertEquals(List.of(5), ClawMath.cut(fan, 4, false, id -> id == 5).stream().map(ArcTargets.Hit::id).toList());
	}

	private static ArcTargets.Candidate box(int id, double x, double y, double z) {
		// a 0.6 wide, 1.8 tall creature standing with its middle at (x, y, z)
		return new ArcTargets.Candidate(id, x, y, z, x - 0.3, y - 0.9, z - 0.3, x + 0.3, y + 0.9, z + 0.3);
	}

	// ------------------------------------------------------------------ the bleed's clock

	@Test
	void aBleedPulsesOnTheClockAndEnds() {
		BleedLedger<String> l = new BleedLedger<>();
		assertTrue(l.start("a", 100, 3, 20), "new");
		assertTrue(l.bleeding("a"));
		assertEquals(3, l.pulsesLeft("a"));
		assertTrue(l.due(119).isEmpty(), "the first pulse is a whole interval after the hit");
		assertEquals(List.of("a"), l.due(120));
		assertFalse(l.pulsed("a", 120));
		assertTrue(l.due(139).isEmpty());
		assertEquals(List.of("a"), l.due(140));
		assertFalse(l.pulsed("a", 140));
		assertEquals(List.of("a"), l.due(160));
		assertTrue(l.pulsed("a", 160), "the last pulse ends it");
		assertFalse(l.bleeding("a"));
		assertTrue(l.isEmpty());
	}

	@Test
	void aSecondHitTopsUpAndNeverAddsOrDelays() {
		BleedLedger<String> l = new BleedLedger<>();
		l.start("a", 100, 3, 20);
		// the next two slashes of the same Rake: three ticks apart each
		assertFalse(l.start("a", 103, 3, 20), "not new");
		assertFalse(l.start("a", 106, 3, 20));
		assertEquals(3, l.pulsesLeft("a"), "three slashes are one bleed of three pulses, not nine");
		assertTrue(l.due(119).isEmpty(), "and the first pulse is still due a whole interval after the first hit");
		assertEquals(List.of("a"), l.due(120));
		l.pulsed("a", 120);
		// a later Rake of a higher level tops it up to its own count
		l.start("a", 130, 5, 20);
		assertEquals(5, l.pulsesLeft("a"));
		l.start("a", 131, 3, 20);
		assertEquals(5, l.pulsesLeft("a"), "a weaker hit never shortens it");
		assertTrue(l.due(139).isEmpty(), "the clock was not reset by either");
		assertEquals(List.of("a"), l.due(140));
	}

	@Test
	void nothingStartsWithNoPulses() {
		BleedLedger<String> l = new BleedLedger<>();
		assertFalse(l.start("a", 0, 0, 20));
		assertFalse(l.start("a", 0, -3, 20));
		assertTrue(l.isEmpty());
	}

	@Test
	void aPulseInsideAHurtWindowIsRetriedThenGivenUp() {
		BleedLedger<String> l = new BleedLedger<>();
		l.start("a", 0, 2, 20);
		assertEquals(List.of("a"), l.due(20));
		assertFalse(l.retry("a", 20));
		assertTrue(l.due(20).isEmpty(), "tried again next tick");
		assertEquals(List.of("a"), l.due(21));
		assertEquals(2, l.pulsesLeft("a"), "a retry spends nothing");
		for (int i = 1; i < BleedLedger.MAX_RETRIES; i++) assertFalse(l.retry("a", 20 + i));
		assertEquals(2, l.pulsesLeft("a"), "the allowed tries are all free");
		assertFalse(l.retry("a", 60), "the next one gives the pulse up as spent, and one remains");
		assertEquals(1, l.pulsesLeft("a"));
		assertTrue(l.due(60).isEmpty());
		assertEquals(List.of("a"), l.due(80), "and the clock starts again from there");
		assertTrue(l.retry("nobody", 5), "a bleed that is not there is over");
	}

	@Test
	void aBleedCanBeStoppedOrSwept() {
		BleedLedger<String> l = new BleedLedger<>();
		l.start("a", 0, 3, 20);
		l.start("b", 0, 3, 20);
		l.start("c", 0, 3, 20);
		l.stop("a");
		assertFalse(l.bleeding("a"));
		assertEquals(2, l.stopIf(k -> !k.equals("zzz")));
		assertTrue(l.isEmpty());
		l.start("a", 0, 3, 20);
		l.clear();
		assertEquals(0, l.size());
		assertEquals(0, l.pulsesLeft("a"));
		assertTrue(l.pulsed("a", 0), "a pulse for a bleed that is gone ends it");
	}

	@Test
	void aBleedKnowsHowLongItHasLeft() {
		BleedLedger<String> l = new BleedLedger<>();
		l.start("a", 100, 4, 20);
		assertEquals(80, l.ticksLeft("a", 100), "4 pulses a second apart: 4 seconds");
		assertEquals(60, l.ticksLeft("a", 120), "twenty ticks on: three pulses still to come, one a second");
		assertEquals(0, l.ticksLeft("nobody", 100));
		l.pulsed("a", 120);
		assertEquals(60, l.ticksLeft("a", 120));
		assertEquals(List.of("a"), l.keys());
	}
}
