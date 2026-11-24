package dev.morphreborn.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The mod's sounds (2026-11-24): the arm forming and retracting sounds are made from the recordings of the Morph mod's own transformation sound
 * (morph1 to morph6), not from other sounds, so the events registered here use only those files, each file exists, each event has its subtitle,
 * and no recording sits in the mod unused.
 */
class SoundRegistrationTest {
	private static JsonObject json(String path) throws IOException {
		try (InputStream in = SoundRegistrationTest.class.getResourceAsStream(path)) {
			assertNotNull(in, path);
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static String nameOf(JsonElement e) {
		return e.isJsonObject() ? e.getAsJsonObject().get("name").getAsString() : e.getAsString();
	}

	@Test
	void theEventsAreTheMorphSoundAndTheArmsFormAndRetract() throws IOException {
		JsonObject sounds = json("/assets/morphreborn/sounds.json");
		assertEquals(Set.of("morph", "arm_form", "arm_retract"), sounds.keySet());
	}

	@Test
	void everyEventUsesOnlyTheMorphRecordingsAndTheyAllExist() throws IOException {
		JsonObject sounds = json("/assets/morphreborn/sounds.json");
		Set<String> used = new HashSet<>();
		for (String event : sounds.keySet()) {
			JsonObject e = sounds.getAsJsonObject(event);
			assertTrue(e.getAsJsonArray("sounds").size() >= 1, event);
			for (JsonElement s : e.getAsJsonArray("sounds")) {
				String name = nameOf(s);
				assertTrue(name.matches("morphreborn:morph[1-6]"), event + " plays " + name + ", which is not one of the Morph mod's own sounds");
				used.add(name);
				assertNotNull(SoundRegistrationTest.class.getResource("/assets/morphreborn/sounds/" + name.substring("morphreborn:".length()) + ".ogg"), name + " is missing");
			}
		}
		assertEquals(6, used.size(), "all six recordings are in use, none left over");
	}

	@Test
	void everyEventHasASubtitleInTheLanguageFile() throws IOException {
		JsonObject sounds = json("/assets/morphreborn/sounds.json");
		JsonObject lang = json("/assets/morphreborn/lang/en_us.json");
		for (String event : sounds.keySet()) {
			String key = sounds.getAsJsonObject(event).get("subtitle").getAsString();
			assertTrue(lang.has(key), key);
			assertFalse(lang.get(key).getAsString().isBlank(), key);
		}
	}

	@Test
	void formingAndRetractingAreQuieterAndShorterThanAFullMorph() throws IOException {
		// they happen often and the recordings are three seconds: sped up (shorter, higher) and well under full volume
		JsonObject sounds = json("/assets/morphreborn/sounds.json");
		for (String event : new String[] {"arm_form", "arm_retract"}) {
			for (JsonElement s : sounds.getAsJsonObject(event).getAsJsonArray("sounds")) {
				JsonObject o = s.getAsJsonObject();
				assertTrue(o.get("volume").getAsFloat() <= 0.5F, event + " volume");
				assertTrue(o.get("pitch").getAsFloat() >= 1.2F && o.get("pitch").getAsFloat() <= 1.7F, event + " pitch");
			}
		}
		// retracting sounds lower than forming, so the pair reads as one family
		float form = sounds.getAsJsonObject("arm_form").getAsJsonArray("sounds").get(0).getAsJsonObject().get("pitch").getAsFloat();
		float retract = sounds.getAsJsonObject("arm_retract").getAsJsonArray("sounds").get(0).getAsJsonObject().get("pitch").getAsFloat();
		assertTrue(retract < form);
	}

	@Test
	void theResourcePackHasTheSameEventsAndSubtitles() throws IOException {
		// players without the mod hear the server's sounds through the pack: an event the pack lacks is silent for them
		Path pack = Path.of("..", "resourcepack", "assets", "morphreborn");
		JsonObject mod = json("/assets/morphreborn/sounds.json");
		JsonObject packSounds = JsonParser.parseString(Files.readString(pack.resolve("sounds.json"), StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals(mod, packSounds, "the pack's sounds.json must match the mod's");
		JsonObject packLang = JsonParser.parseString(Files.readString(pack.resolve("lang/en_us.json"), StandardCharsets.UTF_8)).getAsJsonObject();
		for (String event : mod.keySet()) {
			String key = mod.getAsJsonObject(event).get("subtitle").getAsString();
			assertTrue(packLang.has(key), "the pack has no subtitle " + key);
		}
		for (JsonElement s : mod.getAsJsonObject("arm_form").getAsJsonArray("sounds")) {
			String file = nameOf(s).substring("morphreborn:".length()) + ".ogg";
			assertTrue(Files.exists(pack.resolve("sounds").resolve(file)), "the pack lacks " + file);
		}
	}
}
