package dev.morphreborn.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Which abilities.yml / variants.yml gets replaced on startup (MorphRebornPlugin.installOrUpgrade). */
class ConfigVersionTest {
	@Test
	void fileWithoutVersionIsOlderThanAnyRelease() {
		assertTrue(MorphRebornPlugin.compareVersions("", "5.0.0") < 0);
		assertEquals(0, MorphRebornPlugin.compareVersions("", ""));
	}

	@Test
	void sameReleaseIsKept() {
		assertEquals(0, MorphRebornPlugin.compareVersions("5.0.0", "5.0.0"));
		assertEquals(0, MorphRebornPlugin.compareVersions("5.0", "5.0.0"));
	}

	@Test
	void comparesNumbersNotText() {
		assertTrue(MorphRebornPlugin.compareVersions("5.2.0", "5.10.0") < 0);
		assertTrue(MorphRebornPlugin.compareVersions("5.1.0", "5.0.9") > 0);
		assertTrue(MorphRebornPlugin.compareVersions("6.0.0", "5.9.9") > 0);
	}
}
