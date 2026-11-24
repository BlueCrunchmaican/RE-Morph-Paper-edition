package dev.morphreborn.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.bukkit.configuration.file.YamlConfiguration;
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

	/**
	 * A beta's version reads as newer than its release: "7.6.0-beta.1" is taken part by part as 7, 6, 0 and 1. So a
	 * bundled file never carries a beta version: a server that ran a file marked 7.6.0-beta.1 would keep it when 7.6.0
	 * itself came out ("newer than this one") and never get the release's new settings. Files are marked with the plain
	 * release that changes them, betas included; between two betas of one release, a test server's file is merged by
	 * hand.
	 */
	@Test
	void aBetaReadsAsNewerThanItsRelease() {
		assertTrue(MorphRebornPlugin.compareVersions("7.6.0-beta.1", "7.6.0") > 0);
		assertTrue(MorphRebornPlugin.compareVersions("7.6.0", "7.6.0-beta.1") < 0);
		assertTrue(MorphRebornPlugin.compareVersions("7.5.0", "7.6.0-beta.1") < 0);
	}

	@Test
	void bundledFilesCarryAPlainReleaseVersion() throws Exception {
		for (String name : List.of("config.yml", "survival.yml", "abilities.yml", "variants.yml")) {
			YamlConfiguration yaml = new YamlConfiguration();
			try (Reader r = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/" + name)), StandardCharsets.UTF_8)) {
				yaml.load(r);
			}
			String version = yaml.getString("config-version");
			assertNotNull(version, name);
			assertTrue(version.matches("\\d+\\.\\d+\\.\\d+"), name + " is marked " + version + ", not a plain release");
			if (!name.equals("variants.yml")) {
				// 8.0.0 added the spear, mace, trident, crossbow, claws and the shield, spyglass and flint (survival.yml nodes and
				// prices, abilities.yml arms); a file marked 7.6.0 is never updated, so all three carry the release that added them
				// (my config-versions item, 2026-11-16)
				assertTrue(MorphRebornPlugin.compareVersions(version, "8.0.0") >= 0, name + " is marked " + version);
			}
		}
	}
}
