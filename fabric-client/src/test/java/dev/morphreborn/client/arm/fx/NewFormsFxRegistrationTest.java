package dev.morphreborn.client.arm.fx;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The new forms' effect handlers are on ArmFx's list ahead of their classes (2026-11-16): one not in the build is skipped when it loads. */
class NewFormsFxRegistrationTest {
	@Test
	void everyNewKindsHandlerIsRegistered() {
		for (String name : new String[] {"SpearFx", "MaceFx", "TridentFx", "CrossbowFx", "ClawsFx", "AccessoryFx"}) {
			assertTrue(ArmFx.HANDLERS.contains("dev.morphreborn.client.arm.fx." + name), name);
		}
	}
}
