package dev.morphreborn.client.arm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.SemVer;
import dev.morphreborn.client.net.Wire;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The pulse speed (2026-11-16: each player's choice, shown to everyone) on the wire, byte for byte with the
 * server's WireArmColourTest, and how the client keeps, sends and relays it: an appended field, read only when present
 * and sent only to servers new enough.
 */
class ArmPulseSpeedTest {
	static final UUID SUBJECT = ArmWireTest.SUBJECT;
	static final int BLUE = ArmColourTest.BLUE;
	/** C_ARM_COLOUR for 0x2A5BFF, the armour look and 150% (varInt 0x96 0x01). */
	static final String CLIENT_PULSE_FIXTURE = "0A FF B7 A9 01 02 96 01";
	/** S_ARM_COLOUR: SUBJECT is 0x2A5BFF with the armour look and 150%. */
	static final String SERVER_PULSE_FIXTURE = "11 00 00 00 00 00 00 00 01 00 00 00 00 00 00 00 02 FF B7 A9 01 02 96 01";

	private String savedColour, savedLook;
	private float savedPulse;

	@BeforeEach
	void reset() {
		ArmColours.tick(null, 0, false); // forgets who you were in an earlier test (tick keeps it between calls)
		ArmColours.clear();
		savedColour = ClientConfig.INSTANCE.armColour;
		savedLook = ClientConfig.INSTANCE.armLook;
		savedPulse = ClientConfig.INSTANCE.armPulseSpeed;
		ClientConfig.INSTANCE.armLook = "arm";
		ClientConfig.INSTANCE.armPulseSpeed = 1F;
	}

	@AfterEach
	void restore() {
		ClientConfig.INSTANCE.armColour = savedColour;
		ClientConfig.INSTANCE.armLook = savedLook;
		ClientConfig.INSTANCE.armPulseSpeed = savedPulse;
		ArmColours.tick(null, 0, false);
		ArmColours.clear();
	}

	@Test
	void clientPacketFixture() {
		assertArrayEquals(ArmWireTest.hex(CLIENT_PULSE_FIXTURE), ArmColours.packet(BLUE, ArmProtocol.LOOK_ARMOUR, 150).bytes());
		// the packet for a server from before the pulse speed is the old one, a prefix of the new
		byte[] with = ArmColours.packet(BLUE, ArmProtocol.LOOK_ARMOUR, 150).bytes();
		byte[] without = ArmColours.packet(BLUE, ArmProtocol.LOOK_ARMOUR).bytes();
		assertArrayEquals(ArmWireTest.hex(ArmColourTest.CLIENT_FIXTURE_ARMOUR), without);
		assertArrayEquals(without, java.util.Arrays.copyOf(with, without.length));
		// out of range is never written
		Wire.In in = new Wire.In(ArmColours.packet(BLUE, ArmProtocol.LOOK_ARM, 9999).bytes());
		in.varInt();
		in.varInt();
		in.varInt();
		assertEquals(ArmProtocol.PULSE_SPEED_MAX, in.varInt());
	}

	@Test
	void serverPacketWithAPulseSpeedIsKept() {
		Wire.In in = new Wire.In(ArmWireTest.hex(SERVER_PULSE_FIXTURE));
		assertEquals(Wire.S_ARM_COLOUR, in.varInt());
		ArmColours.handle(in);
		assertFalse(in.hasMore());
		assertEquals(BLUE, ArmColours.of(SUBJECT));
		assertEquals(ArmProtocol.LOOK_ARMOUR, ArmColours.lookOf(SUBJECT));
		assertEquals(150, ArmColours.pulseSpeedOf(SUBJECT));
	}

	/** A server from before the pulse speed (or before the look) sends fewer fields: every pulse at the usual speed. */
	@Test
	void serverPacketWithoutAPulseSpeedMeansTheUsualSpeed() {
		ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(SUBJECT).varInt(BLUE).varInt(ArmProtocol.LOOK_HAND).varInt(200).in());
		assertEquals(200, ArmColours.pulseSpeedOf(SUBJECT));
		ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(SUBJECT).varInt(BLUE).varInt(ArmProtocol.LOOK_HAND).in());
		assertEquals(ArmProtocol.PULSE_SPEED_DEFAULT, ArmColours.pulseSpeedOf(SUBJECT), "a newer packet without the field resets it");
		assertTrue(ArmColours.pulses().isEmpty());
		ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(SUBJECT).varInt(BLUE).in());
		assertEquals(ArmProtocol.PULSE_SPEED_DEFAULT, ArmColours.pulseSpeedOf(SUBJECT));
		assertEquals(BLUE, ArmColours.of(SUBJECT), "the colour is untouched");
	}

	@Test
	void serverPacketWithAnOutOfRangePulseSpeedIsClamped() {
		ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(SUBJECT).varInt(BLUE).varInt(0).varInt(100000).in());
		assertEquals(ArmProtocol.PULSE_SPEED_MAX, ArmColours.pulseSpeedOf(SUBJECT));
		ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(SUBJECT).varInt(BLUE).varInt(0).varInt(0).in());
		assertEquals(ArmProtocol.PULSE_SPEED_MIN, ArmColours.pulseSpeedOf(SUBJECT));
		assertEquals(BLUE, ArmColours.of(SUBJECT), "a pulse speed never costs the colour");
	}

	@Test
	void theUsualSpeedDropsTheEntry() {
		ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(SUBJECT).varInt(BLUE).varInt(0).varInt(150).in());
		assertEquals(1, ArmColours.pulses().size());
		ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(SUBJECT).varInt(BLUE).varInt(0).varInt(ArmProtocol.PULSE_SPEED_DEFAULT).in());
		assertTrue(ArmColours.pulses().isEmpty());
	}

	@Test
	void yourOwnPulseSpeedIsYourSettingNotTheRelay() {
		ArmColours.tick(SUBJECT, 0, true); // learns who you are
		ClientConfig.INSTANCE.armPulseSpeed = 2F;
		assertEquals(200, ArmColours.ownPulseSpeed());
		assertEquals(200, ArmColours.pulseSpeedOf(SUBJECT));
		ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(SUBJECT).varInt(BLUE).varInt(0).varInt(50).in());
		assertEquals(200, ArmColours.pulseSpeedOf(SUBJECT), "a relay of your own never overrides your setting");
		assertEquals(ArmProtocol.PULSE_SPEED_DEFAULT, ArmColours.pulseSpeedOf(new UUID(7, 7)), "an unknown player pulses at the usual speed");
	}

	@Test
	void ownSettingIsKeptToTheAllowedRange() {
		ClientConfig.INSTANCE.armPulseSpeed = 50F;
		assertEquals(ArmProtocol.PULSE_SPEED_MAX, ArmColours.ownPulseSpeed());
		ClientConfig.INSTANCE.armPulseSpeed = 0F;
		assertEquals(ArmProtocol.PULSE_SPEED_MIN, ArmColours.ownPulseSpeed());
		ClientConfig.INSTANCE.armPulseSpeed = -3F;
		assertEquals(ArmProtocol.PULSE_SPEED_MIN, ArmColours.ownPulseSpeed());
		ClientConfig.INSTANCE.armPulseSpeed = Float.NaN;
		assertEquals(ArmProtocol.PULSE_SPEED_DEFAULT, ArmColours.ownPulseSpeed());
		ClientConfig.INSTANCE.armPulseSpeed = 1.5F;
		assertEquals(150, ArmColours.ownPulseSpeed());
	}

	@Test
	void tickSendsThePulseSpeedOnlyToAServerThatTakesIt() {
		ClientConfig.INSTANCE.armColour = "#2A5BFF";
		ClientConfig.INSTANCE.armPulseSpeed = 1.5F;
		Wire.Out first = ArmColours.tick(SUBJECT, 100, true);
		assertNotNull(first);
		assertArrayEquals(ArmWireTest.hex("0A FF B7 A9 01 00 96 01"), first.bytes());
		assertNull(ArmColours.tick(SUBJECT, 101, true), "nothing changed");
		// a change of the speed alone is sent (after the throttle)
		ClientConfig.INSTANCE.armPulseSpeed = 0.5F;
		assertNull(ArmColours.tick(SUBJECT, 100 + ArmProtocol.COLOUR_CLIENT_THROTTLE_TICKS - 1, true), "throttled: held, not lost");
		Wire.Out second = ArmColours.tick(SUBJECT, 100 + ArmProtocol.COLOUR_CLIENT_THROTTLE_TICKS, true);
		assertNotNull(second);
		assertArrayEquals(ArmWireTest.hex("0A FF B7 A9 01 00 32"), second.bytes());

		// to an older server: colour and look only, and a speed change is nothing to send it
		ArmColours.clear();
		Wire.Out old = ArmColours.tick(SUBJECT, 1000, false);
		assertNotNull(old);
		assertArrayEquals(ArmWireTest.hex(ArmColourTest.CLIENT_FIXTURE), old.bytes());
		ClientConfig.INSTANCE.armPulseSpeed = 2.5F;
		assertNull(ArmColours.tick(SUBJECT, 2000, false), "the older server has no use for the speed");
	}

	@Test
	void theGateIsTheVersionTheOwnerDeclared() {
		assertEquals("8.0.0-beta.1", ArmProtocol.PULSE_SPEED_SINCE);
		assertTrue(SemVer.compare("7.6.0-beta.4", ArmProtocol.PULSE_SPEED_SINCE) < 0);
		assertTrue(SemVer.compare("8.0.0-beta.1", ArmProtocol.PULSE_SPEED_SINCE) >= 0);
	}

	@Test
	void clearForgetsEveryonesSpeedAndWhatWasSent() {
		ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(SUBJECT).varInt(BLUE).varInt(0).varInt(150).in());
		ArmColours.clear();
		assertTrue(ArmColours.pulses().isEmpty());
		assertEquals(ArmProtocol.PULSE_SPEED_DEFAULT, ArmColours.pulseSpeedOf(SUBJECT));
	}

	// ------------------------------------------------------------------ percent, factor and the slider

	/** A speed typed or edited in is snapped to the slider's 0.05 step, so the slider shows the speed that is really stored. */
	@Test
	void aSpeedSnapsToTheSliderStep() {
		assertEquals(135, ArmProtocol.pulseSpeedPercent(1.33F));
		assertEquals(130, ArmProtocol.pulseSpeedPercent(1.32F));
		assertEquals(25, ArmProtocol.pulseSpeedPercent(0.26F));
		for (float f = 0.25F; f <= 3F; f += 0.0137F) {
			float stored = ArmProtocol.pulseSpeedFactor(ArmProtocol.pulseSpeedPercent(f));
			assertEquals(stored, ArmProtocol.pulseSpeedFromSlider(ArmProtocol.pulseSpeedToSlider(stored)), 1e-5F, "the slider shows " + stored);
		}
	}

	@Test
	void percentAndFactor() {
		assertEquals(1F, ArmProtocol.pulseSpeedFactor(100));
		assertEquals(0.25F, ArmProtocol.pulseSpeedFactor(0), "clamped to the slowest");
		assertEquals(3F, ArmProtocol.pulseSpeedFactor(900), "clamped to the fastest");
		assertEquals(100, ArmProtocol.pulseSpeedPercent(1F));
		assertEquals(150, ArmProtocol.pulseSpeedPercent(1.5F));
		assertEquals(25, ArmProtocol.pulseSpeedPercent(-1F));
		assertEquals(300, ArmProtocol.pulseSpeedPercent(1e9F));
		assertEquals(100, ArmProtocol.pulseSpeedPercent(Float.NaN));
		assertEquals(300, ArmProtocol.pulseSpeedPercent(Float.POSITIVE_INFINITY));
	}

	@Test
	void sliderEndsAreTheLimitsAndTheUsualSpeedCanBePicked() {
		assertEquals(0.25F, ArmProtocol.pulseSpeedFromSlider(0.0), 1e-6F);
		assertEquals(3F, ArmProtocol.pulseSpeedFromSlider(1.0), 1e-6F);
		assertEquals(0.25F, ArmProtocol.pulseSpeedFromSlider(-5.0), 1e-6F);
		assertEquals(3F, ArmProtocol.pulseSpeedFromSlider(5.0), 1e-6F);
		// exactly 1.0 sits on the slider: the position of 1.0 comes back as 1.0
		assertEquals(1F, ArmProtocol.pulseSpeedFromSlider(ArmProtocol.pulseSpeedToSlider(1F)), 1e-6F);
		// the usual speed is near the middle (the scale is logarithmic, so a quarter and three times get the same room)
		double usual = ArmProtocol.pulseSpeedToSlider(1F);
		assertTrue(usual > 0.5 && usual < 0.6, "was " + usual);
	}

	@Test
	void sliderIsMonotonicInStepsOfFiveHundredths() {
		float last = 0F;
		for (double p = 0.0; p <= 1.0; p += 0.01) {
			float speed = ArmProtocol.pulseSpeedFromSlider(p);
			assertTrue(speed >= last, "never goes down at " + p);
			assertEquals(Math.round(speed * 20F) / 20F, speed, 1e-5F, "a tidy step at " + p);
			last = speed;
		}
	}

	@Test
	void everyTidySpeedRoundTripsThroughTheSlider() {
		for (int hundredths = 25; hundredths <= 300; hundredths += 5) {
			float speed = hundredths / 100F;
			assertEquals(speed, ArmProtocol.pulseSpeedFromSlider(ArmProtocol.pulseSpeedToSlider(speed)), 1e-5F, "speed " + speed);
		}
	}
}
