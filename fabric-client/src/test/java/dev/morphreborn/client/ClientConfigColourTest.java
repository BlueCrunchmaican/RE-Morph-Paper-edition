package dev.morphreborn.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.Gson;
import dev.morphreborn.client.arm.ArmProtocol;
import org.junit.jupiter.api.Test;

/** The arm colour setting: the default red out of the box, from an older settings file, and after a bad hand edit. */
class ClientConfigColourTest {
	@Test
	void defaultIsTheModsOwnRed() {
		ClientConfig c = new ClientConfig();
		assertEquals("#FF3A3F", c.armColour);
		assertEquals(ArmProtocol.COLOUR_DEFAULT, ArmProtocol.parseColour(c.armColour));
	}

	@Test
	void anOlderSettingsFileGetsTheDefault() {
		ClientConfig c = new Gson().fromJson("{\"armGlow\": false}", ClientConfig.class);
		c.clamp();
		assertEquals("#FF3A3F", c.armColour);
	}

	@Test
	void aHandEditIsTidiedOrReset() {
		ClientConfig c = new Gson().fromJson("{\"armColour\": \"2a5bff\"}", ClientConfig.class);
		c.clamp();
		assertEquals("#2A5BFF", c.armColour);
		c = new Gson().fromJson("{\"armColour\": \"blue\"}", ClientConfig.class);
		c.clamp();
		assertEquals("#FF3A3F", c.armColour);
		c = new Gson().fromJson("{\"armColour\": null}", ClientConfig.class);
		c.clamp();
		assertEquals("#FF3A3F", c.armColour);
	}
}
