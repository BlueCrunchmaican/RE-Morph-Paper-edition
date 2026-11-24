package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.accessory.SpyglassAccessory;
import dev.morphreborn.paper.arm.api.ArmAccessoryModule;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.ArmTiers;
import dev.morphreborn.paper.arm.rules.SpyglassMarks;
import dev.morphreborn.paper.arm.rules.SpyglassRules;
import dev.morphreborn.paper.arm.rules.SpyglassSettings;
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

/**
 * The Tendril Spyglass (my design, 2026-11-16): its settings and bundled rows, the mark set (the cap, who goes when a new one
 * comes, the time a mark lasts, how often the client is told again), the S_ARM_FX numbers, how far a look reaches, the entity flags byte
 * with the glow bit, and what the module says of itself.
 */
class SpyglassAccessoryTest {
	/** A mark's life after a glance: the defaults' 5 seconds. */
	private static final int LIFE = 100;

	private static YamlConfiguration load(String file) throws Exception {
		YamlConfiguration yaml = new YamlConfiguration();
		try (Reader r = new InputStreamReader(Objects.requireNonNull(SpyglassAccessoryTest.class.getResourceAsStream("/" + file)), StandardCharsets.UTF_8)) {
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
		YamlConfiguration abilities = load("abilities.yml");
		ConfigurationSection spyglass = Objects.requireNonNull(abilities.getConfigurationSection("arms.accessories.spyglass"));
		assertEquals(SpyglassSettings.DEFAULTS, SpyglassSettings.parse(nested(spyglass)));
		assertEquals(SpyglassSettings.DEFAULTS, SpyglassSettings.parse(ArmConfig.parse(Map.of(), Map.of(), nested(Objects.requireNonNull(abilities.getConfigurationSection("arms"))))
				.accessory("spyglass")), "through the arm config's merged section too");
		assertEquals(SpyglassSettings.DEFAULTS, SpyglassSettings.parse(null));
		assertEquals(SpyglassSettings.DEFAULTS, SpyglassSettings.parse(Map.of()));
	}

	@Test
	void theDefaultsAreTheOwnersNumbers() {
		SpyglassSettings s = SpyglassSettings.DEFAULTS;
		assertEquals(List.of(64.0, 96.0, 128.0), levels(s.range()), "it sees 64 / 96 / 128 blocks");
		assertEquals(List.of(1, 2, 3), List.of(s.marksAt(1), s.marksAt(2), s.marksAt(3)), "and marks up to 3 at once by level");
		assertEquals(100, s.markTicks(), "a mark outlasts the last glance by a few seconds");
		assertTrue(s.scanTicks() >= 2 && s.scanTicks() <= 6, "it looks every few ticks");
		assertTrue(s.zoomTimeoutTicks() > 200, "a zoom is kept alive by the client's repeat every 10 seconds, with room to spare");
		assertTrue(SpyglassSettings.MIN_TIMEOUT_SECONDS * 20 > 200, "and so is even the shortest a server may set");
		assertTrue(s.markPlayers());
	}

	@Test
	void settingsAreClampedAndABadValueTakesTheDefault() {
		SpyglassSettings d = SpyglassSettings.DEFAULTS;
		SpyglassSettings s = SpyglassSettings.parse(Map.of("range", List.of(9999, "x"), "marks", 40, "mark-seconds", -3, "scan-ticks", 0, "ray-width", 9, "zoom-timeout-seconds", 1,
				"mark-players", "off"));
		assertEquals(List.of(512.0, d.range().at(2), d.range().at(3)), levels(s.range()), "a range is kept to 512, a bad entry takes the level's default");
		assertEquals(16, s.marksAt(3), "one number is every level's, kept to 16");
		assertEquals(20, s.markTicks(), "a mark lasts a second at least");
		assertEquals(1, s.scanTicks());
		assertEquals(2.0, s.rayWidth());
		assertEquals(SpyglassSettings.MIN_TIMEOUT_SECONDS * 20, s.zoomTimeoutTicks(), "a zoom times out after 15 seconds at the least: the client's repeat is every 10");
		assertFalse(s.markPlayers());
		SpyglassSettings fewer = SpyglassSettings.parse(Map.of("range", List.of(10), "marks", List.of(2, 4)));
		assertEquals(List.of(10.0, 10.0, 10.0), levels(fewer.range()), "fewer entries repeat the last");
		assertEquals(List.of(2, 4, 4), List.of(fewer.marksAt(1), fewer.marksAt(2), fewer.marksAt(3)));
		assertEquals(1, SpyglassSettings.parse(Map.of("marks", 0)).marksAt(1), "there is always room for one mark");
		assertEquals(d.markTicks(), SpyglassSettings.parse(Map.of("mark-seconds", "soon")).markTicks());
	}

	@Test
	void theUpgradeTreeIsToldWhatEachLevelDoes() {
		assertTrue(SpyglassSettings.DEFAULTS.describe(1).contains("1 creature within"), SpyglassSettings.DEFAULTS.describe(1));
		assertTrue(SpyglassSettings.DEFAULTS.describe(1).startsWith("Hold the zoom key (Z) while it is on"), "the key is said first");
		assertTrue(SpyglassSettings.DEFAULTS.describe(1).contains("64 blocks"));
		assertTrue(SpyglassSettings.DEFAULTS.describe(3).contains("3 creatures") && SpyglassSettings.DEFAULTS.describe(3).contains("128 blocks"));
	}

	// ------------------------------------------------------------------ the marks

	@Test
	void aNewCreatureIsMarkedAndToldOnce() {
		SpyglassMarks m = new SpyglassMarks();
		SpyglassMarks.Seen first = m.see(7, 100, 2, LIFE);
		assertTrue(first.isNew() && first.announce());
		assertEquals(SpyglassMarks.NO_ONE, first.evicted());
		assertTrue(m.has(7));
		SpyglassMarks.Seen again = m.see(7, 104, 2, LIFE);
		assertFalse(again.isNew(), "looked at again, still the same mark");
		assertFalse(again.announce(), "and the client isn't told again so soon");
		assertEquals(1, m.size());
	}

	@Test
	void aMarkThatIsKeptUpIsToldAgainEveryResendInterval() {
		SpyglassMarks m = new SpyglassMarks();
		m.see(7, 100, 2, LIFE);
		assertFalse(m.see(7, 100 + SpyglassRules.RESEND_TICKS - 1, 2, LIFE).announce());
		assertTrue(m.see(7, 100 + SpyglassRules.RESEND_TICKS, 2, LIFE).announce(), "the client's own timer would run out under a mark the server still holds");
		assertFalse(m.see(7, 100 + SpyglassRules.RESEND_TICKS + 1, 2, LIFE).announce(), "the interval starts again from the message");
		assertTrue(SpyglassRules.RESEND_TICKS * 2 < SpyglassSettings.DEFAULTS.markTicks(), "the resend is well inside a mark's life");
	}

	@Test
	void theResendIntervalFitsInsideAShortMarkLife() {
		assertEquals(SpyglassRules.RESEND_TICKS, SpyglassRules.resendTicks(LIFE, 4), "the default life keeps the 20-tick interval");
		for (int life = 20; life <= 600; life += 20) {
			for (int scan = 1; scan <= 20; scan++) {
				int every = SpyglassRules.resendTicks(life, scan);
				assertTrue(every >= 1 && every <= SpyglassRules.RESEND_TICKS);
				// the check only runs at scans, so the message can be up to scan - 1 ticks late: still before the client's timer ends
				if (life > scan) assertTrue(every + scan - 1 < life, "life " + life + " scan " + scan + " resend " + every);
			}
		}
		assertEquals(10, SpyglassRules.resendTicks(20, 4), "one second at the default scan: half the life");
		SpyglassMarks m = new SpyglassMarks();
		m.see(7, 0, 2, 20, 10);
		assertFalse(m.see(7, 9, 2, 20, 10).announce());
		assertTrue(m.see(7, 10, 2, 20, 10).announce(), "a short life is told again at its own interval");
	}

	@Test
	void aFullSetDropsTheCreatureSeenLongestAgo() {
		SpyglassMarks m = new SpyglassMarks();
		m.see(1, 10, 3, LIFE);
		m.see(2, 12, 3, LIFE);
		m.see(3, 14, 3, LIFE);
		m.see(1, 16, 3, LIFE); // 1 is looked at again, so 2 is now the oldest
		SpyglassMarks.Seen s = m.see(4, 18, 3, LIFE);
		assertTrue(s.isNew() && s.announce());
		assertEquals(2, s.evicted(), "the one seen longest ago goes, not the first marked");
		assertEquals(3, m.size());
		assertFalse(m.has(2));
		assertArrayEquals(new int[] {1, 3, 4}, m.ids(), "in the order they were marked");
	}

	@Test
	void theCapIsAtLeastOne() {
		SpyglassMarks m = new SpyglassMarks();
		m.see(1, 0, 0, LIFE);
		SpyglassMarks.Seen s = m.see(2, 1, -5, LIFE);
		assertEquals(1, s.evicted());
		assertEquals(1, m.size());
	}

	@Test
	void aMarkExpiresAfterItsLifeFromTheLastGlance() {
		SpyglassMarks m = new SpyglassMarks();
		m.see(1, 0, 3, LIFE);
		m.see(2, 50, 3, LIFE);
		m.see(1, 60, 3, LIFE); // looked at again: its life starts over
		assertEquals(0, m.expire(99, 100).length, "nothing is old enough yet");
		assertArrayEquals(new int[] {2}, m.expire(150, 100), "2 was last seen at 50, so it is over at 150");
		assertTrue(m.has(1));
		assertArrayEquals(new int[] {1}, m.expire(160, 100));
		assertTrue(m.isEmpty());
		assertEquals(0, m.expire(1000, 100).length, "nothing to expire from an empty set");
	}

	@Test
	void removingAndClearingGiveBackWhatWentAway() {
		SpyglassMarks m = new SpyglassMarks();
		m.see(5, 0, 3, LIFE);
		m.see(6, 1, 3, LIFE);
		assertTrue(m.remove(5));
		assertFalse(m.remove(5), "already gone");
		m.see(7, 2, 3, LIFE);
		assertArrayEquals(new int[] {6, 7}, m.clear());
		assertTrue(m.isEmpty());
		assertEquals(0, m.clear().length);
		assertEquals(0, m.ids().length);
	}

	@Test
	void aDroppedMarkCanBeMarkedAgainAsNew() {
		SpyglassMarks m = new SpyglassMarks();
		m.see(1, 0, 1, LIFE);
		m.see(2, 5, 1, LIFE); // pushes 1 out
		SpyglassMarks.Seen back = m.see(1, 9, 1, LIFE);
		assertTrue(back.isNew() && back.announce(), "it glows and is announced afresh");
		assertEquals(2, back.evicted());
	}

	@Test
	void aMarkTheLookMovedOffIsToldAgainWithTheTimeItHasLeft() {
		SpyglassMarks m = new SpyglassMarks();
		m.see(7, 100, 3, LIFE); // told at once: the client's timer ends at 200
		m.see(7, 108, 3, LIFE); // looked at again, not told: the server's mark now ends at 208
		assertEquals(0, m.stale(108, LIFE, 7).length, "still looked at: it is kept up on its own interval");
		int[] stale = m.stale(112, LIFE, 8);
		assertArrayEquals(new int[] {7}, stale, "the look moved on: the client's clock is out by 8 ticks");
		assertEquals(96, m.left(7, 112, LIFE), "and it is told what the server has left, not a whole life");
		assertEquals(0, m.stale(116, LIFE, 8).length, "once told it is right until it is seen again");
		m.see(7, 120, 3, LIFE);
		assertArrayEquals(new int[] {7}, m.stale(124, LIFE, 8), "a new glance makes the client's clock short again");
		assertEquals(0, m.stale(124, LIFE, 8).length);
		assertEquals(0, m.left(99, 124, LIFE), "an unmarked creature has no time left");
	}

	@Test
	void aMarkToldAtTheServersOwnClockNeverFallsShortOfIt() {
		SpyglassMarks m = new SpyglassMarks();
		m.see(1, 0, 3, LIFE);
		for (int now = 4; now <= 56; now += 4) m.see(1, now, 3, LIFE); // looked at throughout
		// at 56 the last message was at 40 (20-tick interval): the client's timer ends at 140, the server's mark at 156
		assertArrayEquals(new int[] {1}, m.stale(60, LIFE, 2));
		assertEquals(96, m.left(1, 60, LIFE), "the client is set to end at 156 as the server's mark does");
		assertEquals(0, m.expire(155, LIFE).length);
		assertArrayEquals(new int[] {1}, m.expire(156, LIFE));
	}

	@Test
	void aLowerCapDropsTheOldestMarksOnly() {
		SpyglassMarks m = new SpyglassMarks();
		m.see(1, 10, 3, LIFE);
		m.see(2, 12, 3, LIFE);
		m.see(3, 14, 3, LIFE);
		m.see(1, 16, 3, LIFE);
		assertEquals(0, m.trim(3).length, "within the cap nothing goes");
		assertArrayEquals(new int[] {2}, m.trim(2), "the one seen longest ago");
		assertArrayEquals(new int[] {3}, m.trim(1));
		assertArrayEquals(new int[] {1}, m.ids());
		assertEquals(0, m.trim(0).length, "there is always room for one");
	}

	// ------------------------------------------------------------------ the rules

	@Test
	void theEffectArgIsTheSpyglassesPlaceAndMove() {
		assertEquals(0x201, SpyglassRules.fxArg(SpyglassAccessory.FX_MARK));
		assertEquals(0x202, SpyglassRules.fxArg(SpyglassAccessory.FX_UNMARK));
		for (int move : List.of(SpyglassAccessory.FX_MARK, SpyglassAccessory.FX_UNMARK)) {
			assertEquals(move, SpyglassRules.fxMove(SpyglassRules.fxArg(move)));
		}
		assertEquals(0, SpyglassRules.fxMove(0x101), "the shield's move is not the spyglass's");
		assertEquals(0, SpyglassRules.fxMove(1), "nor is a plain small arg");
		assertEquals(ArmTiers.ACCESSORIES.indexOf("spyglass"), SpyglassRules.fxArg(1) >> 8, "bits 8-11 are the spyglass's place in the accessory ring");
		assertEquals(ArmProtocol.FX_ACCESSORY, 20);
		assertEquals(0x3F, SpyglassRules.fxArg(0x13F) & 0xFF, "a move is kept to its byte");
	}

	@Test
	void aLookNeverReachesPastTheChunksTheOwnerWasSent() {
		assertEquals(128.0, SpyglassRules.reach(128, 32));
		assertEquals(96.0, SpyglassRules.reach(128, 6), "6 chunks is 96 blocks");
		assertEquals(16.0, SpyglassRules.reach(128, 0), "at least the chunk they stand in");
		assertEquals(0.0, SpyglassRules.reach(-5, 10));
	}

	@Test
	void aMarkAtTheEdgeOfTheRangeDoesNotFlicker() {
		assertTrue(SpyglassRules.inRange(64 * 64, 64));
		assertTrue(SpyglassRules.inRange(67 * 67, 64), "a few blocks of give");
		assertFalse(SpyglassRules.inRange(69 * 69, 64));
	}

	@Test
	void theFlagsByteHasTheGlowBitWithTheRestKept() {
		byte plain = SpyglassRules.flags(true, true, false, false, false, false, false);
		assertEquals(0x03, plain);
		assertEquals(0x43, SpyglassRules.withGlow(plain), "the glow bit is added to what vanilla sends");
		assertEquals(0x43, SpyglassRules.withGlow(SpyglassRules.withGlow(plain)), "and adding it twice changes nothing");
		assertEquals((byte) 0xF3, SpyglassRules.flags(true, true, false, true, true, true, true), "fire, sneaking, swimming, invisible, glowing, gliding");
		assertEquals(0x08, SpyglassRules.flags(false, false, true, false, false, false, false));
		assertEquals(0x40, SpyglassRules.withGlow((byte) 0));
		assertEquals(0x0A, SpyglassRules.restored((byte) 0x0A, false), "a mark put out gives back the byte the game sent");
		assertEquals(0x4A, SpyglassRules.restored((byte) 0x0A, true), "and a creature that really glows keeps its glow");
		assertEquals(0x0A, SpyglassRules.restored((byte) 0x4A, false), "(a byte with the bit is never kept, so the bit follows the creature)");
	}

	// ------------------------------------------------------------------ the module and the wire

	@Test
	void theModuleIsTheSpyglassAndKnowsItsPlaceBeforeItIsInitialised() {
		ArmAccessoryModule m = new SpyglassAccessory();
		assertEquals("spyglass", m.id());
		assertEquals(0, m.maxMode(3), "it has no mode: the wheel's scroll does nothing to it");
		assertNull(m.listener());
		assertTrue(m.describe(2).contains("96 blocks") && m.describe(2).contains("2 creatures"), "it can be described before init");
		m.load(Map.of("range", List.of(10, 20, 30), "marks", 5));
		assertTrue(m.describe(3).contains("30 blocks") && m.describe(3).contains("5 creatures"), "and a reload changes what it says");
		m.switchOffAll();
	}

	@Test
	void theHeldKeyActionIsOnTheWireAndTheSpyglassIsAnAccessory() {
		assertEquals(9, ArmProtocol.ACTION_ACCESSORY_HOLD, "never renumber");
		assertTrue(ArmTiers.ACCESSORIES.contains("spyglass"));
		assertEquals(2, ArmTiers.ACCESSORIES.indexOf("spyglass"), "the third place of the accessory ring, as the effect arg says");
	}
}
