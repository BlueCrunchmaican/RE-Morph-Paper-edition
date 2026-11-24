package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.api.ArmAccessoryModule;
import dev.morphreborn.paper.arm.api.ArmSpecialModule;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.ArmTiers;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The accessories (8.0.0-beta.2): the registry of modules, the settings they read, and the rule that a form or accessory whose
 * module class is missing from the build is simply not there (hidden everywhere), which is how the new forms wait for phase 2.
 */
class ArmAccessoriesTest {
	private static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	/** Every class named in ArmModules loads, if it is in this build, and is the kind of module and form it says. */
	@Test
	void everyListedModuleIsAnAccessoryOrSpecialForItsKindOfForm() throws Exception {
		Set<String> seen = new HashSet<>();
		for (String name : ArmModules.ACCESSORIES) {
			Class<?> c;
			try {
				c = Class.forName(name);
			} catch (ClassNotFoundException e) {
				continue; // a module not in this build is hidden, which is the point
			}
			ArmAccessoryModule m = (ArmAccessoryModule) c.getConstructor().newInstance();
			ArmForm f = ArmForm.of(m.id());
			assertNotNull(f, name);
			assertFalse(f.tool(), name + " is an accessory's module");
			assertTrue(seen.add(m.id()), "two modules for " + m.id());
		}
		assertTrue(seen.contains("lantern"), "the lantern is ported to the accessory API");
		for (String name : ArmModules.SPECIALS) {
			try {
				ArmSpecialModule m = (ArmSpecialModule) Class.forName(name).getConstructor().newInstance();
				ArmForm f = ArmForm.of(m.form());
				assertNotNull(f, name);
				assertTrue(f.tool(), name + " is a tool's module");
			} catch (ClassNotFoundException e) {
				// not in this build
			}
		}
	}

	/**
	 * Phase 2's modules are registered ahead of their classes (the lead, 2026-11-16), so phase 2 edits no shared file: the names are
	 * fixed here, and a class that isn't in the jar yet is skipped quietly (the form stays hidden), with nothing above debug level logged.
	 */
	@Test
	void thePhaseTwoModulesAreRegisteredByNameAndAMissingOneIsSkippedQuietly() {
		String special = "dev.morphreborn.paper.arm.special.";
		String accessory = "dev.morphreborn.paper.arm.accessory.";
		List<String> specials = List.of(special + "SpearImpale", special + "MaceLeap", special + "TridentArm", special + "CrossbowArm", special + "ClawRake");
		List<String> accessories = List.of(accessory + "ShieldAccessory", accessory + "SpyglassAccessory", accessory + "FlintAccessory");
		assertTrue(ArmModules.SPECIALS.containsAll(specials), "specials: " + ArmModules.SPECIALS);
		assertTrue(ArmModules.ACCESSORIES.containsAll(accessories), "accessories: " + ArmModules.ACCESSORIES);
		assertEquals("dev.morphreborn.paper.arm.lantern.Lantern", ArmModules.ACCESSORIES.get(0), "the lantern stays first");
		List<java.util.logging.LogRecord> loud = new ArrayList<>();
		java.util.logging.Logger log = java.util.logging.Logger.getAnonymousLogger();
		log.setUseParentHandlers(false);
		log.setLevel(java.util.logging.Level.ALL);
		log.addHandler(new java.util.logging.Handler() {
			@Override
			public void publish(java.util.logging.LogRecord r) {
				if (r.getLevel().intValue() > java.util.logging.Level.FINE.intValue()) loud.add(r);
			}

			@Override
			public void flush() {}

			@Override
			public void close() {}
		});
		List<ArmSpecialModule> madeSpecials = ArmModules.specials(log);
		List<ArmAccessoryModule> madeAccessories = ArmModules.accessories(log);
		assertTrue(loud.isEmpty(), "a missing module logs nothing above debug: " + loud.stream().map(java.util.logging.LogRecord::getMessage).toList());
		for (String name : specials) {
			boolean there = exists(name);
			assertEquals(there, madeSpecials.stream().anyMatch(m -> m.getClass().getName().equals(name)), name + ": hidden exactly when its class is missing");
		}
		for (String name : accessories) {
			boolean there = exists(name);
			assertEquals(there, madeAccessories.stream().anyMatch(m -> m.getClass().getName().equals(name)), name + ": hidden exactly when its class is missing");
		}
	}

	private static boolean exists(String name) {
		try {
			Class.forName(name);
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}

	@Test
	void everyAccessoryIsAnAccessoryFormWithItsOwnPermissionAndUpgrade() {
		for (ArmForm f : ArmForm.ACCESSORIES) {
			assertFalse(f.tool(), f.id());
			assertEquals("morphreborn.arm." + f.id(), f.permission());
			assertEquals("arm_" + f.id(), f.upgradeId());
			assertNull(f.base(), "an accessory is no item");
			assertEquals("", f.vanillaModel(2));
			assertEquals(f, ArmForm.byUpgrade("arm_" + f.id()));
		}
		for (ArmForm f : ArmForm.WHEEL) {
			assertTrue(f.tool(), f.id());
			assertNotNull(f.base(), f.id());
		}
		assertEquals(ArmForm.values().length, ArmForm.WHEEL.size() + ArmForm.ACCESSORIES.size(), "every form is in the wheel or the accessory ring");
	}

	@Test
	void anAccessoryIsSwitchedOffInItsOwnSection() {
		ArmConfig c = ArmConfig.parse(map("accessories", map("shield", map("enabled", false), "spyglass", map("range", List.of(10, 20, 30)))), Map.of(), Map.of());
		assertFalse(c.formEnabled("shield"));
		assertTrue(c.formEnabled("spyglass"));
		assertTrue(c.formEnabled("flint"));
		assertTrue(c.formEnabled("lantern"));
		assertEquals(List.of(10, 20, 30), c.accessory("spyglass").get("range"));
		assertEquals(Map.of(), c.accessory("flint"));
		assertEquals(Map.of(), c.accessory("nothing"));
		ArmConfig lantern = ArmConfig.parse(map("lantern", map("enabled", false, "update-ticks", 4)), Map.of(), Map.of());
		assertFalse(lantern.formEnabled("lantern"));
		assertEquals(4, lantern.accessory("lantern").get("update-ticks"));
		assertEquals(lantern.rawLantern(), lantern.accessory("lantern"));
		// the raw sections are frozen
		try {
			c.accessory("spyglass").put("x", 1);
			throw new AssertionError("a module could change another's settings");
		} catch (UnsupportedOperationException expected) {
			// as it should be
		}
	}

	@Test
	void accessoryDrainsAreSetPerAccessoryAndSwitchedOffWithTheCosts() {
		ArmConfig c = ArmConfig.parse(Map.of(), map("drain-per-minute", map("tool", 1, "lantern", 2, "shield", 3, "flint", 0)), Map.of());
		assertEquals(2000, c.costs().accessoryDrainPerMinute("lantern"));
		assertEquals(2000, c.costs().lanternDrainPerMinute());
		assertEquals(3000, c.costs().accessoryDrainPerMinute("shield"));
		assertEquals(250, c.costs().accessoryDrainPerMinute("spyglass"), "unset: the default");
		assertEquals(0, c.costs().accessoryDrainPerMinute("flint"));
		ArmConfig off = ArmConfig.parse(Map.of(), map("enabled", false), Map.of());
		for (String id : ArmTiers.ACCESSORIES) assertEquals(0, off.costs().accessoryDrainPerMinute(id), id + ": costs off means no drain");
		assertEquals(1000, ArmConfig.DEFAULTS.costs().special("shield-block", 1));
		assertEquals(500, ArmConfig.DEFAULTS.costs().special("flint-ignite", 3));
	}
}
