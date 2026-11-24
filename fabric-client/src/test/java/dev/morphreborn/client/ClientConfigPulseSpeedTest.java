package dev.morphreborn.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

/** The pulse speed setting: the usual speed out of the box, from an older settings file, and after a bad hand edit. */
class ClientConfigPulseSpeedTest {
	private static ClientConfig load(String json) {
		ClientConfig c = new Gson().fromJson(json, ClientConfig.class);
		c.clamp();
		return c;
	}

	@Test
	void defaultIsTheUsualSpeed() {
		assertEquals(1F, new ClientConfig().armPulseSpeed);
	}

	@Test
	void anOlderSettingsFileGetsTheDefault() {
		assertEquals(1F, load("{\"armGlow\": false, \"armColour\": \"#2A5BFF\", \"armLook\": \"hand\"}").armPulseSpeed);
	}

	@Test
	void aChosenSpeedIsKept() {
		assertEquals(1.5F, load("{\"armPulseSpeed\": 1.5}").armPulseSpeed);
		assertEquals(0.25F, load("{\"armPulseSpeed\": 0.25}").armPulseSpeed);
		assertEquals(3F, load("{\"armPulseSpeed\": 3.0}").armPulseSpeed);
	}

	@Test
	void aHandEditIsKeptToTheAllowedRange() {
		assertEquals(0.25F, load("{\"armPulseSpeed\": 0}").armPulseSpeed, "zero would stall every pulse");
		assertEquals(0.25F, load("{\"armPulseSpeed\": -2}").armPulseSpeed);
		assertEquals(3F, load("{\"armPulseSpeed\": 40}").armPulseSpeed);
		assertEquals(1.25F, load("{\"armPulseSpeed\": 1.2345}").armPulseSpeed, 1e-6F, "snapped to the slider's 0.05 step, so the slider shows the stored speed");
	}

	@Test
	void theSpeedDoesNotDisturbTheColourOrTheLook() {
		ClientConfig c = load("{\"armPulseSpeed\": 99, \"armLook\": \"junk\", \"armColour\": \"2a5bff\"}");
		assertEquals(3F, c.armPulseSpeed);
		assertEquals("arm", c.armLook);
		assertEquals("#2A5BFF", c.armColour);
	}
}
