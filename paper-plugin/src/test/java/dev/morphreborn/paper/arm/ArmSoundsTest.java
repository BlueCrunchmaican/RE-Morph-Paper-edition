package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.morphreborn.paper.SemVer;
import dev.morphreborn.paper.arm.ArmSounds.Moment;
import dev.morphreborn.paper.arm.ArmSounds.Voice;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** An arm forming and retracting is Morph's own sound for every listener, whatever their game has (2026-11-24). */
class ArmSoundsTest {
	private static final String FALLBACK = "minecraft:entity.illusioner.mirror_move";

	@Test
	void everyMomentPlaysAMorphEventForPlayersWhoHaveIt() {
		for (Moment m : Moment.values()) {
			Voice v = ArmSounds.voice(m, true, FALLBACK);
			assertTrue(v.key().equals("morphreborn:arm_form") || v.key().equals("morphreborn:arm_retract"), m + ": " + v.key());
			assertEquals(m.volume, v.volume(), m + " volume");
			assertEquals(m.pitch, v.pitch(), m + " pitch");
		}
	}

	@Test
	void theEventsAndNumbersAreTheClientsTable() {
		assertEquals(new Voice("morphreborn:arm_form", 1.0F, 1.0F), ArmSounds.voice(Moment.FORM, true, FALLBACK));
		assertEquals(new Voice("morphreborn:arm_form", 0.6F, 1.1F), ArmSounds.voice(Moment.FORM_QUICK, true, FALLBACK));
		assertEquals(new Voice("morphreborn:arm_form", 0.8F, 1.15F), ArmSounds.voice(Moment.RESHAPE, true, FALLBACK));
		assertEquals(new Voice("morphreborn:arm_retract", 1.0F, 1.0F), ArmSounds.voice(Moment.RETRACT, true, FALLBACK));
		assertEquals(new Voice("morphreborn:arm_retract", 0.9F, 0.75F), ArmSounds.voice(Moment.MELT, true, FALLBACK));
	}

	@Test
	void playersWithoutTheEventsHearTheTransformationsStandIn() {
		for (Moment m : Moment.values()) {
			Voice v = ArmSounds.voice(m, false, FALLBACK);
			assertEquals(FALLBACK, v.key(), m + " uses the configured stand-in");
			assertEquals(m.volume * 0.6F, v.volume(), 1e-6, m + " is quieter, as the morph's stand-in is");
			assertTrue(v.pitch() >= 0.5F && v.pitch() <= 2.0F, m + " pitch stays inside what the game plays: " + v.pitch());
		}
	}

	@Test
	void theStandInKeepsAFormAndARetractApart() {
		Voice form = ArmSounds.voice(Moment.FORM, false, FALLBACK);
		Voice retract = ArmSounds.voice(Moment.RETRACT, false, FALLBACK);
		assertTrue(retract.pitch() < form.pitch(), "a retract is lower than a form, as with the events");
		assertTrue(ArmSounds.voice(Moment.MELT, false, FALLBACK).pitch() <= retract.pitch(), "a melt is the lowest");
	}

	@Test
	void aRetractIsLowerThanAFormOnTheEventsToo() {
		assertTrue(ArmSounds.voice(Moment.MELT, true, FALLBACK).pitch() < ArmSounds.voice(Moment.FORM, true, FALLBACK).pitch());
		assertNotEquals(ArmSounds.voice(Moment.FORM, true, FALLBACK).key(), ArmSounds.voice(Moment.RETRACT, true, FALLBACK).key());
	}

	@Test
	void whoHasTheEvents() {
		assertTrue(ArmSounds.hasEvents(true, true, false), "the mod at the version that has them");
		assertTrue(ArmSounds.hasEvents(true, true, true));
		assertFalse(ArmSounds.hasEvents(true, false, true), "an older mod has no events, and an unknown id is silent: stand-in");
		assertFalse(ArmSounds.hasEvents(false, false, false), "no mod and no known pack, Bedrock included: stand-in");
		assertTrue(ArmSounds.hasEvents(false, false, true), "no mod, and the server says its pack holds them");
	}

	@Test
	void theVersionThatHasTheEventsComparesTheSemVerWay() {
		String since = ArmProtocol.ARM_SOUNDS_SINCE;
		assertTrue(SemVer.compare("8.0.0-beta.2+26.3", since) >= 0, "the build with the events");
		assertTrue(SemVer.compare("8.0.0", since) > 0, "the release");
		assertTrue(SemVer.compare("8.0.0-beta.1", since) < 0, "the beta before it");
		assertTrue(SemVer.compare("7.5.0", since) < 0);
	}

	@Test
	void theClientModDefinesTheEventsTheServerPlays() throws IOException {
		Path json = Path.of("..", "fabric-client", "src", "main", "resources", "assets", "morphreborn", "sounds.json");
		assumeTrue(Files.exists(json), "built from the plugin's folder alone");
		String text = Files.readString(json, StandardCharsets.UTF_8);
		assertTrue(text.contains("\"arm_form\""), "arm_form is defined by the mod");
		assertTrue(text.contains("\"arm_retract\""), "arm_retract is defined by the mod");
		assertEquals("morphreborn:arm_form", ArmSounds.FORM_EVENT);
		assertEquals("morphreborn:arm_retract", ArmSounds.RETRACT_EVENT);
	}

	@Test
	void theArmEffectsPlayNoVanillaSoundOfTheirOwn() throws IOException {
		Path src = Path.of("src", "main", "java", "dev", "morphreborn", "paper", "arm", "ArmEffects.java");
		assumeTrue(Files.exists(src));
		String code = Files.readString(src, StandardCharsets.UTF_8);
		assertFalse(code.matches("(?s).*\"(entity|block|item|ui)\\.[a-z_.]+\".*"), "the arm's sounds are Morph's: no vanilla sound id in ArmEffects");
		assertFalse(code.contains("SoundCategory"), "everything goes through ArmSounds");
	}
}
