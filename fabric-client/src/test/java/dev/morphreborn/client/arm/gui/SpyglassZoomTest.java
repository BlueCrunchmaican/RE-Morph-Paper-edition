package dev.morphreborn.client.arm.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.net.Wire;
import org.junit.jupiter.api.Test;

/**
 * The spyglass accessory's zoom (2026-11-16): when the key zooms, how the field of view eases, what the server is told and that the
 * tells are never lost, and the HUD's readout. The mixins and the key itself need the game; these are the parts that do not.
 */
class SpyglassZoomTest {
	@Test
	void zoomNeedsTheKeyTheAccessoryFirstPersonAndNoScreen() {
		assertTrue(SpyglassZoom.wants(true, true, true, false));
		assertFalse(SpyglassZoom.wants(false, true, true, false), "no key");
		assertFalse(SpyglassZoom.wants(true, false, true, false), "no spyglass on (or a server that has none)");
		assertFalse(SpyglassZoom.wants(true, true, false, false), "vanilla's scope is first person only, and so is this");
		assertFalse(SpyglassZoom.wants(true, true, true, true), "a screen open lets go of it");
	}

	@Test
	void theFovEasesInAndOutToTheVanillaSpyglassNumber() {
		assertEquals(0.1F, SpyglassZoom.FOV_MULTIPLIER, "the vanilla spyglass's modifier");
		assertEquals(1F, SpyglassZoom.fov(1F, 0F), "no zoom leaves the game's own modifier alone");
		assertEquals(1.1F, SpyglassZoom.fov(1.1F, 0F), "sprinting's too");
		assertEquals(0.1F, SpyglassZoom.fov(1F, 1F), 1e-6F);
		assertEquals(0.1F, SpyglassZoom.fov(1.1F, 1F), 1e-6F, "fully zoomed is the same whatever you were doing");
		float last = 1F;
		float level = 0F;
		int ticks = 0;
		while (level < 1F) {
			level = SpyglassZoom.step(level, true);
			float f = SpyglassZoom.fov(1F, level);
			assertTrue(f <= last + 1e-6F, "zooming in never widens");
			last = f;
			ticks++;
			assertTrue(ticks < 20, "it arrives");
		}
		assertTrue(ticks >= 3 && ticks <= 8, "in a few ticks, not at once: " + ticks);
		ticks = 0;
		while (level > 0F) {
			level = SpyglassZoom.step(level, false);
			float f = SpyglassZoom.fov(1F, level);
			assertTrue(f >= last - 1e-6F, "zooming out never narrows");
			last = f;
			ticks++;
		}
		assertEquals(1F, SpyglassZoom.fov(1F, level), "and it ends exactly where the game had it");
		assertTrue(ticks >= 3 && ticks <= 8, "out in a few ticks: " + ticks);
		assertEquals(1F, SpyglassZoom.fov(1F, Float.NaN), "a bad level is no zoom");
	}

	@Test
	void theServerIsToldWhenTheKeyChangesAndNeverTwice() {
		assertEquals(1, SpyglassZoom.sync(true, false), "start");
		assertEquals(-1, SpyglassZoom.sync(false, true), "stop");
		assertEquals(0, SpyglassZoom.sync(true, true));
		assertEquals(0, SpyglassZoom.sync(false, false));
		// a tap shorter than the throttle: the start went, the stop is owed until it goes (the tick retries while sync says -1)
		boolean want = true, sent = false;
		int sends = 0;
		boolean throttled = false;
		for (int tick = 0; tick < 12; tick++) {
			if (tick == 1) want = false; // let go a tick after pressing
			int tell = SpyglassZoom.sync(want, sent);
			if (tell != 0) {
				throttled = tick > 0 && tick < 4 && sends > 0; // C_ARM goes at most once every 4 ticks
				if (!throttled) {
					sent = tell > 0;
					sends++;
				}
			}
		}
		assertEquals(2, sends, "one start and one stop");
		assertFalse(sent, "and the book is closed");
	}

	@Test
	void theTellsAreTheHeldKeyActionTheContractNames() {
		assertEquals(9, ArmProtocol.ACTION_ACCESSORY_HOLD, "the server's ArmProtocol.ACTION_ACCESSORY_HOLD");
		assertArrayEquals(new Wire.Out(Wire.C_ARM).varInt(9).string("spyglass").varInt(1).bytes(), Arms.requestPacket(ArmProtocol.ACTION_ACCESSORY_HOLD, ArmProtocol.SPYGLASS, 1).bytes(),
				"start: ACTION_ACCESSORY_HOLD, 'spyglass', 1");
		assertArrayEquals(new Wire.Out(Wire.C_ARM).varInt(9).string("spyglass").varInt(0).bytes(), Arms.requestPacket(ArmProtocol.ACTION_ACCESSORY_HOLD, ArmProtocol.SPYGLASS, 0).bytes(),
				"stop: the same with 0");
		assertArrayEquals(Arms.requestPacket(ArmProtocol.ACTION_ACCESSORY_HOLD, ArmProtocol.SPYGLASS, 0).bytes(), Arms.requestPacket(ArmProtocol.ACTION_ACCESSORY_HOLD, ArmProtocol.SPYGLASS, -1).bytes(),
				"anything not above 0 is a release: never a negative on the wire");
		assertArrayEquals(new Wire.Out(Wire.C_ARM).varInt(8).string("spyglass").varInt(1).bytes(), Arms.requestPacket(ArmProtocol.ACTION_ACCESSORY_MODE, ArmProtocol.SPYGLASS, 1).bytes(),
				"the mode step's own form is unchanged");
	}

	@Test
	void aHeldKeyIsToldAgainEveryTenSecondsAndNeverBefore() {
		int repeat = ArmProtocol.HOLD_REPEAT_TICKS;
		assertEquals(200, repeat, "about every 10 seconds, as the server's doc says");
		assertEquals(0, SpyglassZoom.sync(true, true, repeat - 1));
		assertEquals(1, SpyglassZoom.sync(true, true, repeat), "the repeat that keeps the server's zoom alive");
		assertEquals(0, SpyglassZoom.sync(false, false, repeat * 5), "nothing to keep alive with the key up");
		assertEquals(1, SpyglassZoom.sync(true, false, 0), "a press is still a start");
		assertEquals(-1, SpyglassZoom.sync(false, true, repeat * 5), "and a release a stop, however long it was held");
		// a 70-second hold: one start, then a repeat every 200 ticks, well inside the server's timeout of 15 seconds at the least
		boolean sent = false;
		int sinceTold = 0, sends = 0;
		for (int tick = 0; tick < 1400; tick++) {
			int tell = SpyglassZoom.sync(true, sent, sinceTold);
			if (tell > 0) {
				sent = true;
				sinceTold = 0;
				sends++;
			}
			if (sent) sinceTold++;
		}
		assertEquals(1 + (1400 - 1) / repeat, sends, "the start and one repeat per 200 ticks");
	}

	@Test
	void theReadoutRoundsToTenthsAndReadsTheSameEverywhere() {
		assertEquals("0.0", SpyglassHud.format(0));
		assertEquals("0.5", SpyglassHud.format(5));
		assertEquals("23.4", SpyglassHud.format(SpyglassHud.tenths(23.4D)));
		assertEquals("100.0", SpyglassHud.format(SpyglassHud.tenths(99.96D)));
		assertEquals(0, SpyglassHud.tenths(-3D), "never negative");
		assertEquals(0, SpyglassHud.tenths(Double.NaN), "a bad distance reads as 0");
	}
}
