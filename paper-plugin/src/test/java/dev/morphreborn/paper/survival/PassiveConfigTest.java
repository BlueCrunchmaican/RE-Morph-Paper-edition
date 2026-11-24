package dev.morphreborn.paper.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.survival.PassiveSettings.Billing;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** survival.yml passives: every passive has an entry of a known kind with the numbers that kind needs, and the code's defaults are the shipped file's. */
class PassiveConfigTest {
	private static YamlConfiguration bundled() throws Exception {
		YamlConfiguration yaml = new YamlConfiguration();
		try (Reader r = new InputStreamReader(Objects.requireNonNull(PassiveConfigTest.class.getResourceAsStream("/survival.yml")), StandardCharsets.UTF_8)) {
			yaml.load(r);
		}
		return yaml;
	}

	private static Map<String, Object> entry(YamlConfiguration yaml, String id) {
		ConfigurationSection s = Objects.requireNonNull(yaml.getConfigurationSection("passives." + id), id);
		return s.getValues(false);
	}

	@Test
	void everyPassiveUpgradeHasAnEntryAndEveryEntryAnUpgrade() throws Exception {
		YamlConfiguration yaml = bundled();
		Set<String> upgrades = new HashSet<>();
		for (String id : Objects.requireNonNull(yaml.getConfigurationSection("upgrades")).getKeys(false)) if (id.startsWith("passive_")) upgrades.add(id);
		assertEquals(new HashSet<>(PassiveSettings.ALL), upgrades, "the tree's passives and the ones the code bills");
		assertEquals(upgrades, Objects.requireNonNull(yaml.getConfigurationSection("passives")).getKeys(false));
	}

	@Test
	void everyEntryNamesAKnownKindWithTheNumbersItNeeds() throws Exception {
		YamlConfiguration yaml = bundled();
		for (String id : PassiveSettings.ALL) {
			Map<String, Object> raw = entry(yaml, id);
			assertNotNull(Billing.parse(raw.get("billing")), id + ": billing must be constant, acting or triggered");
			List<String> needs = switch (id) {
				case PassiveSettings.SUSTENANCE -> List.of("per-food-point", "per-saturation", "saturation-floor");
				case PassiveSettings.NIGHT_EYES -> List.of("drain-per-minute", "dark-light-level");
				case PassiveSettings.GILLS, PassiveSettings.FIRE_SHEDDING -> List.of("per-second");
				case PassiveSettings.ANTITOXIN -> List.of("per-effect");
				case PassiveSettings.TENDRIL_SENSE -> List.of("drain-per-minute", "radius");
				case PassiveSettings.SCAVENGER -> List.of("per-item", "reach");
				default -> List.of("drain-per-minute");
			};
			for (String key : needs) {
				assertTrue(raw.containsKey(key), id + " has no " + key);
				if (!key.equals("reach")) assertTrue(PassiveSettings.number(raw.get(key), -1) >= 0, id + "." + key + " must be a number, 0 or more");
			}
		}
	}

	@Test
	void theCodesDefaultsAreTheShippedFile() throws Exception {
		YamlConfiguration yaml = bundled();
		for (String id : PassiveSettings.ALL) {
			Map<String, Object> shipped = entry(yaml, id);
			Map<String, Object> defaults = PassiveSettings.defaultsOf(id);
			assertEquals(shipped.keySet(), defaults.keySet(), id + ": the same settings");
			for (Map.Entry<String, Object> d : defaults.entrySet()) {
				Object s = shipped.get(d.getKey());
				if (d.getValue() instanceof List<?> list) assertEquals(list.stream().map(o -> ((Number) o).doubleValue()).toList(),
						((List<?>) s).stream().map(o -> ((Number) o).doubleValue()).toList(), id + "." + d.getKey());
				else if (d.getValue() instanceof Number n) assertEquals(n.doubleValue(), ((Number) s).doubleValue(), 1e-9, id + "." + d.getKey());
				else assertEquals(d.getValue(), s, id + "." + d.getKey());
			}
		}
	}

	@Test
	void theKindsAreTheOwnersRule() {
		// 2026-11-16: Borrowed Scent drains constantly; hunger only while it fills; fire and poison and items per thing
		assertEquals(Billing.CONSTANT, PassiveSettings.of(PassiveSettings.BORROWED_SCENT, null).billing());
		assertEquals(Billing.ACTING, PassiveSettings.of(PassiveSettings.SUSTENANCE, null).billing());
		assertEquals(Billing.ACTING, PassiveSettings.of(PassiveSettings.NIGHT_EYES, null).billing());
		assertEquals(Billing.ACTING, PassiveSettings.of(PassiveSettings.GILLS, null).billing());
		assertEquals(Billing.ACTING, PassiveSettings.of(PassiveSettings.TENDRIL_SENSE, null).billing());
		assertEquals(Billing.TRIGGERED, PassiveSettings.of(PassiveSettings.FIRE_SHEDDING, null).billing());
		assertEquals(Billing.TRIGGERED, PassiveSettings.of(PassiveSettings.ANTITOXIN, null).billing());
		assertEquals(Billing.TRIGGERED, PassiveSettings.of(PassiveSettings.SCAVENGER, null).billing());
	}

	@Test
	void aMissingEntryStillBills() {
		PassiveSettings.Entry e = PassiveSettings.of(PassiveSettings.NIGHT_EYES, null);
		assertEquals(1500, e.drainMilliPerMinute(), "a stale file used to make a passive free");
		assertEquals(2000, PassiveSettings.of(PassiveSettings.SUSTENANCE, Map.of()).milli("per-food-point"));
	}

	@Test
	void anOldBetaEntryIsIgnoredForTheDefaults() {
		// 7.6.0 betas: a drain a minute for everything, no billing key
		PassiveSettings.Entry night = PassiveSettings.of(PassiveSettings.NIGHT_EYES, Map.of("drain-per-minute", 1));
		assertEquals(Billing.ACTING, night.billing());
		assertEquals(1500, night.drainMilliPerMinute());
		PassiveSettings.Entry sustenance = PassiveSettings.of(PassiveSettings.SUSTENANCE, Map.of("drain-per-minute", 6));
		assertEquals(2000, sustenance.milli("per-food-point"));
		assertEquals(6000, PassiveSettings.of(PassiveSettings.BORROWED_SCENT, Map.of("drain-per-minute", 6)).drainMilliPerMinute());
	}

	/** my test servers' stale survival.yml: eight old-layout entries are one INFO line, so SurvivalConfig asks once (config-versions item, 2026-11-16). */
	@Test
	void oldLayoutNamesTheEntriesWithoutAValidBilling() {
		Map<String, Map<String, Object>> file = new java.util.LinkedHashMap<>();
		for (String id : PassiveSettings.ALL) file.put(id, Map.of("drain-per-minute", 6));
		assertEquals(PassiveSettings.ALL, PassiveSettings.oldLayout(file), "all eight, in menu order");
		file.put(PassiveSettings.GILLS, Map.of("billing", "acting", "per-second", 0.15));
		file.put(PassiveSettings.ANTITOXIN, Map.of("billing", "nonsense"));
		assertEquals(6, PassiveSettings.oldLayout(file).size(), "a valid billing is current; a word that is not a kind is a typo, not the old layout");
		assertEquals(List.of(PassiveSettings.ANTITOXIN), PassiveSettings.badBilling(file));
		file.put("passive_of_my_own", Map.of("drain-per-minute", 1));
		assertEquals(6, PassiveSettings.oldLayout(file).size(), "an id that is not a passive is the admin's own");
		assertEquals(List.of(), PassiveSettings.oldLayout(Map.of()), "no passives section, nothing to say");
		Map<String, Map<String, Object>> current = new java.util.LinkedHashMap<>();
		for (String id : PassiveSettings.ALL) current.put(id, Map.of("billing", "triggered"));
		assertEquals(List.of(), PassiveSettings.oldLayout(current));
	}

	@Test
	void theFilesNumbersOverrideTheDefaultsAndTheRestKeepTheirs() {
		PassiveSettings.Entry e = PassiveSettings.of(PassiveSettings.SUSTENANCE, Map.of("billing", "acting", "per-food-point", 3));
		assertEquals(3000, e.milli("per-food-point"));
		assertEquals(500, e.milli("per-saturation"), "not in the file: the default");
		assertEquals(5, e.num("saturation-floor"), 0);
	}

	@Test
	void onlyThreeCanBeSwitchedToConstant() {
		PassiveSettings.Entry night = PassiveSettings.of(PassiveSettings.NIGHT_EYES, Map.of("billing", "constant", "drain-per-minute", 2));
		assertEquals(Billing.CONSTANT, night.billing());
		assertEquals(2000, night.drainMilliPerMinute());
		assertEquals(Billing.CONSTANT, PassiveSettings.of(PassiveSettings.TENDRIL_SENSE, Map.of("billing", "constant")).billing());
		assertEquals(Billing.ACTING, PassiveSettings.of(PassiveSettings.BORROWED_SCENT, Map.of("billing", "acting")).billing());
		// a kind that is how the passive works can't be changed: fire is billed for the fire, whatever the file says
		assertEquals(Billing.TRIGGERED, PassiveSettings.of(PassiveSettings.FIRE_SHEDDING, Map.of("billing", "constant", "per-second", 1)).billing());
		assertEquals(Billing.ACTING, PassiveSettings.of(PassiveSettings.GILLS, Map.of("billing", "triggered", "per-second", 1)).billing());
		assertEquals(Billing.ACTING, PassiveSettings.of(PassiveSettings.NIGHT_EYES, Map.of("billing", "triggered")).billing(), "a drain can't be triggered");
	}

	@Test
	void badNumbersFallBackAndNeverGoBelowZero() {
		PassiveSettings.Entry e = PassiveSettings.of(PassiveSettings.ANTITOXIN, Map.of("billing", "triggered", "per-effect", -4));
		assertEquals(0, e.milli("per-effect"), "never negative");
		PassiveSettings.Entry text = PassiveSettings.of(PassiveSettings.ANTITOXIN, Map.of("billing", "triggered", "per-effect", "lots"));
		assertEquals(1000, text.milli("per-effect"), "not a number: the default");
		assertEquals(1500, PassiveSettings.of(PassiveSettings.NIGHT_EYES, Map.of("billing", "acting", "drain-per-minute", "1.5")).drainMilliPerMinute());
	}

	@Test
	void gillsArePricedByTheSecond() {
		assertEquals(9000, PassiveSettings.of(PassiveSettings.GILLS, null).drainMilliPerMinute(), "0.15 a second is 9 a minute");
		assertEquals(500, PassiveSettings.of(PassiveSettings.FIRE_SHEDDING, null).milli("per-second"));
	}

	@Test
	void scavengersReachGrowsWithItsLevel() {
		PassiveSettings.Entry e = PassiveSettings.of(PassiveSettings.SCAVENGER, null);
		assertEquals(6, e.perLevel("reach", 1, 0), 0);
		assertEquals(10, e.perLevel("reach", 2, 0), 0);
		assertEquals(16, e.perLevel("reach", 3, 0), 0);
		assertEquals(16, e.perLevel("reach", 9, 0), 0, "the last for higher levels");
		assertEquals(6, e.perLevel("reach", 0, 0), 0, "level 0 reads as the first");
	}

	@Test
	void theCostLineSaysWhatAndWhen() {
		assertEquals("Costs 1.5 biomass a minute, only in the dark.", PassiveSettings.of(PassiveSettings.NIGHT_EYES, null).costLine());
		assertEquals("Costs 1.5 biomass a minute while on.", PassiveSettings.of(PassiveSettings.NIGHT_EYES, Map.of("billing", "constant")).costLine());
		assertEquals("Costs 0.05 biomass for each item it fetches. Nothing otherwise.", PassiveSettings.of(PassiveSettings.SCAVENGER, null).costLine());
		assertTrue(PassiveSettings.of(PassiveSettings.SUSTENANCE, null).costLine().startsWith("Costs 2 biomass for each point of hunger it fills, and 0.5 for each point of saturation"));
		assertEquals("Costs 6 biomass a minute while on and a creature that can target you is combined with you. Nothing in your own body, in a morph of creatures alone, or with one that never targets players (a cow).",PassiveSettings.of(PassiveSettings.BORROWED_SCENT, null).costLine());
		for (String id : PassiveSettings.ALL) assertTrue(PassiveSettings.of(id, null).costLine().startsWith("Costs "), id);
	}

	/** What survival.yml's passives section makes the log say: the old layout once, a typo'd billing apart, a current file nothing. */
	@Test
	void theNoticesAreOneInfoForTheOldLayoutAndAWarnOnlyForATypo() {
		Map<String, Map<String, Object>> old = new java.util.LinkedHashMap<>();
		for (String id : PassiveSettings.ALL) old.put(id, Map.of("drain-per-minute", 6));
		List<PassiveSettings.Notice> notices = PassiveSettings.notices(old);
		assertEquals(1, notices.size(), "eight old entries are one line, not eight");
		assertTrue(!notices.get(0).warning(), "the old layout is information");
		assertTrue(notices.get(0).text().contains("8 entries") && notices.get(0).text().contains("old beta layout"), notices.get(0).text());

		Map<String, Map<String, Object>> typo = new java.util.LinkedHashMap<>();
		for (String id : PassiveSettings.ALL) typo.put(id, Map.of("billing", "triggered"));
		typo.put(PassiveSettings.BORROWED_SCENT, Map.of("billing", "constnt"));
		notices = PassiveSettings.notices(typo);
		assertEquals(1, notices.size());
		assertTrue(notices.get(0).warning(), "a typo is something to fix");
		assertTrue(notices.get(0).text().contains(PassiveSettings.BORROWED_SCENT) && !notices.get(0).text().contains("old beta layout"), notices.get(0).text());

		Map<String, Map<String, Object>> current = new java.util.LinkedHashMap<>();
		for (String id : PassiveSettings.ALL) current.put(id, Map.of("billing", "acting"));
		assertEquals(List.of(), PassiveSettings.notices(current));
		assertEquals(List.of(), PassiveSettings.notices(Map.of()));
	}

	/** SurvivalConfig logs exactly those notices: one INFO line, no WARN, for the owner's old test file; nothing for a current one. */
	@Test
	void survivalConfigLogsOneInfoLineAndNoWarningForTheOldLayout() {
		java.util.logging.Logger logger = java.util.logging.Logger.getAnonymousLogger();
		logger.setUseParentHandlers(false);
		logger.setLevel(java.util.logging.Level.ALL);
		List<java.util.logging.LogRecord> records = new java.util.ArrayList<>();
		logger.addHandler(new java.util.logging.Handler() {
			@Override
			public void publish(java.util.logging.LogRecord record) {
				records.add(record);
			}

			@Override
			public void flush() {}

			@Override
			public void close() {}
		});
		Map<String, Map<String, Object>> old = new java.util.LinkedHashMap<>();
		for (String id : PassiveSettings.ALL) old.put(id, Map.of("drain-per-minute", 6));

		SurvivalConfig.logPassiveNotices(logger, old);

		assertEquals(1, records.size());
		assertEquals(java.util.logging.Level.INFO, records.get(0).getLevel());

		records.clear();
		Map<String, Map<String, Object>> current = new java.util.LinkedHashMap<>();
		for (String id : PassiveSettings.ALL) current.put(id, Map.of("billing", "acting"));
		SurvivalConfig.logPassiveNotices(logger, current);
		assertEquals(0, records.size());
	}
}
