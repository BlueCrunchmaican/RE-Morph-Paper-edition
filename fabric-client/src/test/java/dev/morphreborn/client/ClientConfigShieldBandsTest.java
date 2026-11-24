package dev.morphreborn.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

/**
 * The shield's wrist bands are no longer a setting (2026-11-24: the animated tendril bands are the look). A settings file written while it was one
 * must still load, its value ignored, and the next save must not carry it on.
 */
class ClientConfigShieldBandsTest {
	private static ClientConfig load(String json) {
		ClientConfig c = new Gson().fromJson(json, ClientConfig.class);
		c.clamp();
		return c;
	}

	@Test
	void anOldFileWithTheChoiceStillLoadsAndKeepsItsOtherSettings() {
		for (String old : new String[] {"classic", "CLASSIC", "tendril", "rings", "", "null"}) {
			ClientConfig c = load("{\"shieldBands\": " + ("null".equals(old) ? "null" : "\"" + old + "\"") + ", \"armLook\": \"hand\", \"lanternGlow\": false}");
			assertEquals("hand", c.armLook, "the other settings in the file load as they were (" + old + ")");
			assertFalse(c.lanternGlow);
		}
	}

	@Test
	void theChoiceIsNotWrittenBackAndHasNoField() {
		String saved = new Gson().toJson(load("{\"shieldBands\": \"classic\"}"));
		assertFalse(saved.contains("shieldBands"), "dropped at the next save: " + saved);
		for (java.lang.reflect.Field f : ClientConfig.class.getDeclaredFields()) assertFalse(f.getName().toLowerCase(java.util.Locale.ROOT).contains("shieldband"), f.getName());
	}
}
