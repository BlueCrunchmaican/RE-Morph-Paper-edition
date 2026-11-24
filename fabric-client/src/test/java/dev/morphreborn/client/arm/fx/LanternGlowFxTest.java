package dev.morphreborn.client.arm.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.arm.lantern.GlowField;
import org.junit.jupiter.api.Test;

/** The lantern's coloured light (2026-11-16): the radius by brightness, the setting and the cost bounds. */
class LanternGlowFxTest {
	/** A brightness 1 to 5 is a radius that grows with it and fits what a flood can cost; anything else is the middle one. */
	@Test
	void theRadiusGrowsWithTheBrightnessWithinTheFloodsLimit() {
		int last = 0;
		for (int bright = 1; bright <= 5; bright++) {
			int r = LanternGlowFx.radiusFor(bright);
			assertTrue(r > last, "brightness " + bright + " reaches further than the one before");
			assertTrue(r <= GlowField.MAX_RADIUS, "inside what the flood allows");
			last = r;
		}
		assertEquals(LanternGlowFx.radiusFor(3), LanternGlowFx.radiusFor(0), "no brightness sent: the middle one");
		assertEquals(LanternGlowFx.radiusFor(3), LanternGlowFx.radiusFor(9));
		assertEquals(LanternGlowFx.radiusFor(3), LanternGlowFx.radiusFor(-2));
	}

	/** It is on by default and the setting is a plain flag in the client's settings file. */
	@Test
	void theColouredLightIsOnByDefault() {
		assertTrue(new ClientConfig().lanternGlow, "on unless the player turns it off");
	}

	/** The handler takes no effects from the server (it follows who is lit) and says so. */
	@Test
	void itTakesNoServerEffects() {
		assertEquals(0, new LanternGlowFx().kinds().length);
	}
}
