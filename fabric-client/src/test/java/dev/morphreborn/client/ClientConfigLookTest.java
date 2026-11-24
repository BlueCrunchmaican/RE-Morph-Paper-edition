package dev.morphreborn.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

/** The arm look setting: the tendril arm out of the box, from an older settings file, and after a bad hand edit. */
class ClientConfigLookTest {
	private static ClientConfig load(String json) {
		ClientConfig c = new Gson().fromJson(json, ClientConfig.class);
		c.clamp();
		return c;
	}

	@Test
	void defaultIsTheTendrilArm() {
		assertEquals("arm", new ClientConfig().armLook);
	}

	@Test
	void anOlderSettingsFileGetsTheDefault() {
		assertEquals("arm", load("{\"armGlow\": false, \"armColour\": \"#2A5BFF\"}").armLook);
	}

	@Test
	void aHandEditIsTidiedOrReset() {
		assertEquals("armour", load("{\"armLook\": \"ARMOUR \"}").armLook);
		assertEquals("hand", load("{\"armLook\": \"hand\"}").armLook);
		assertEquals("arm", load("{\"armLook\": \"tentacles\"}").armLook);
		assertEquals("arm", load("{\"armLook\": \"\"}").armLook);
		assertEquals("arm", load("{\"armLook\": null}").armLook);
	}

	@Test
	void aSavedShieldLookIsIgnored() {
		// the shield look setting (back or morph) was removed when I picked morph, 2026-11-16: a settings file that still has
		// it must load, and leave the rest of the settings as they were
		ClientConfig c = load("{\"shieldLook\": \"back\", \"armLook\": \"hand\", \"armColour\": \"#2A5BFF\"}");
		assertEquals("hand", c.armLook);
		assertEquals("#2A5BFF", c.armColour);
		assertTrue(java.util.Arrays.stream(ClientConfig.class.getFields()).noneMatch(f -> f.getName().equals("shieldLook")), "the field is gone");
	}

	@Test
	void theLookDoesNotDisturbTheColour() {
		ClientConfig c = load("{\"armLook\": \"junk\", \"armColour\": \"2a5bff\"}");
		assertEquals("arm", c.armLook);
		assertEquals("#2A5BFF", c.armColour);
	}
}
