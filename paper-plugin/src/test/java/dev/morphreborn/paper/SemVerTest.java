package dev.morphreborn.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Update checks and the old-client notice compare versions the SemVer way (docs/VERSIONING.md). */
class SemVerTest {
	@Test
	void numbersNotText() {
		assertTrue(SemVer.compare("6.10.0", "6.9.0") > 0);
		assertTrue(SemVer.compare("6.6.0", "6.1.8") > 0);
		assertTrue(SemVer.compare("7.0.0", "6.99.99") > 0);
	}

	@Test
	void tagPrefixAndBuildMetadataAreIgnored() {
		assertEquals(0, SemVer.compare("v6.6.0", "6.6.0"));
		assertEquals(0, SemVer.compare("6.6.0+26.3", "6.6.0"));
		assertEquals("6.6.0-beta.1", SemVer.clean("v6.6.0-beta.1+26.3"));
	}

	@Test
	void preReleasesComeBeforeTheirRelease() {
		assertTrue(SemVer.compare("6.6.0-beta.1", "6.6.0") < 0);
		assertTrue(SemVer.compare("6.6.0-beta.1", "6.1.8") > 0);
		assertTrue(SemVer.compare("6.6.0-beta.2", "6.6.0-beta.10") < 0);
		assertTrue(SemVer.compare("6.6.0-alpha", "6.6.0-beta") < 0);
		assertTrue(SemVer.compare("6.6.0-beta", "6.6.0-beta.1") < 0);
	}
}
