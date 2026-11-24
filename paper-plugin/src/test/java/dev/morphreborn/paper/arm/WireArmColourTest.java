package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.net.ClientChannel;
import dev.morphreborn.paper.net.Wire;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The arm colour, look and pulse speed (C_ARM_COLOUR / S_ARM_COLOUR) byte for byte, with the same fixtures as the
 * client's ArmColourTest, and the rules for what the server accepts.
 */
class WireArmColourTest {
	private static final UUID SUBJECT = new UUID(1, 2);
	private static final int BLUE = 0x2A5BFF;
	/** The default look, for the tests that are about the colour. */
	private static final int LOOK = ArmProtocol.LOOK_DEFAULT;
	/** C_ARM_COLOUR for 0x2A5BFF with the armoured look (2), as the client writes it. */
	private static final String CLIENT_FIXTURE = "0A FF B7 A9 01 02";
	/** C_ARM_COLOUR from a beta.2 client, which has no look: just the colour. */
	private static final String OLD_CLIENT_FIXTURE = "0A FF B7 A9 01";
	/** S_ARM_COLOUR: SUBJECT is 0x2A5BFF with the armoured look (2). */
	private static final String SERVER_FIXTURE = "11 00 00 00 00 00 00 00 01 00 00 00 00 00 00 00 02 FF B7 A9 01 02";

	/** 150% (1.5x): varInt 0x96 0x01. */
	private static final int PULSE = 150;
	/** C_ARM_COLOUR for 0x2A5BFF, armoured look, pulse speed 150, as a client from ArmProtocol.PULSE_SPEED_SINCE writes it. */
	private static final String CLIENT_PULSE_FIXTURE = "0A FF B7 A9 01 02 96 01";
	/** S_ARM_COLOUR: SUBJECT is 0x2A5BFF with the armoured look (2) and pulse speed 150. */
	private static final String SERVER_PULSE_FIXTURE = "11 00 00 00 00 00 00 00 01 00 00 00 00 00 00 00 02 FF B7 A9 01 02 96 01";

	private static byte[] hex(String s) {
		return HexFormat.ofDelimiter(" ").parseHex(s);
	}

	@Test
	void idsAreTheAgreedOnes() {
		assertEquals(10, Wire.C_ARM_COLOUR);
		assertEquals(17, Wire.S_ARM_COLOUR);
		assertEquals(0xFF3A3F, ArmProtocol.COLOUR_DEFAULT);
		assertEquals(3, Wire.PROTOCOL_VERSION, "everything is additive");
	}

	@Test
	void serverPacketFixture() {
		assertArrayEquals(hex(SERVER_FIXTURE), ClientChannel.armColourPacket(SUBJECT, BLUE, ArmProtocol.LOOK_ARMOUR));
	}

	/** S_ARM_COLOUR as the client reads it, for any colour and look; the look is the last field. */
	@Test
	void serverPacketRoundTrip() {
		for (int rgb : new int[] {0x000000, BLUE, ArmProtocol.COLOUR_DEFAULT, 0xFFFFFF}) {
			for (int look : new int[] {ArmProtocol.LOOK_ARM, ArmProtocol.LOOK_HAND, ArmProtocol.LOOK_ARMOUR}) {
				Wire.In in = new Wire.In(ClientChannel.armColourPacket(SUBJECT, rgb, look));
				assertEquals(Wire.S_ARM_COLOUR, in.varInt());
				assertEquals(SUBJECT, in.uuid());
				assertEquals(rgb, in.varInt());
				assertEquals(look, in.varInt());
				assertFalse(in.hasMore());
			}
		}
	}

	/** C_ARM_COLOUR as the client writes it, read as ClientChannel reads it: colour, then the look. */
	@Test
	void clientPacketReads() {
		Wire.In in = new Wire.In(hex(CLIENT_FIXTURE));
		assertEquals(Wire.C_ARM_COLOUR, in.varInt());
		assertEquals(BLUE, in.varInt());
		assertTrue(in.hasMore());
		assertEquals(ArmProtocol.LOOK_ARMOUR, in.varInt());
		assertFalse(in.hasMore());
	}

	/** A beta.2 client sends no look: ClientChannel's read (hasMore, else the default) finds none and means the default. */
	@Test
	void clientPacketWithoutTheLookMeansTheDefault() {
		Wire.In in = new Wire.In(hex(OLD_CLIENT_FIXTURE));
		assertEquals(Wire.C_ARM_COLOUR, in.varInt());
		int rgb = in.varInt();
		int look = in.hasMore() ? in.varInt() : ArmProtocol.LOOK_DEFAULT;
		assertEquals(BLUE, rgb);
		assertEquals(ArmProtocol.LOOK_DEFAULT, look);
		ArmColours c = new ArmColours();
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, rgb, look, 10));
		assertEquals(BLUE, c.of(SUBJECT));
		assertEquals(ArmProtocol.LOOK_DEFAULT, c.lookOf(SUBJECT));
	}

	// ------------------------------------------------------------------ the pulse speed (2026-11-16)

	@Test
	void pulseSpeedServerPacketFixtureIsTheLookPacketPlusOneField() {
		byte[] with = ClientChannel.armColourPacket(SUBJECT, BLUE, ArmProtocol.LOOK_ARMOUR, PULSE);
		assertArrayEquals(hex(SERVER_PULSE_FIXTURE), with);
		// the packet for a client from before the pulse speed is byte for byte the old one, which is a prefix of the new
		byte[] without = ClientChannel.armColourPacket(SUBJECT, BLUE, ArmProtocol.LOOK_ARMOUR);
		assertArrayEquals(hex(SERVER_FIXTURE), without);
		assertArrayEquals(without, java.util.Arrays.copyOf(with, without.length));
	}

	@Test
	void pulseSpeedServerPacketRoundTrip() {
		for (int percent : new int[] {ArmProtocol.PULSE_SPEED_MIN, 60, ArmProtocol.PULSE_SPEED_DEFAULT, PULSE, ArmProtocol.PULSE_SPEED_MAX}) {
			Wire.In in = new Wire.In(ClientChannel.armColourPacket(SUBJECT, BLUE, ArmProtocol.LOOK_HAND, percent));
			assertEquals(Wire.S_ARM_COLOUR, in.varInt());
			assertEquals(SUBJECT, in.uuid());
			assertEquals(BLUE, in.varInt());
			assertEquals(ArmProtocol.LOOK_HAND, in.varInt());
			assertEquals(percent, in.varInt());
			assertFalse(in.hasMore());
		}
	}

	@Test
	void pulseSpeedServerPacketNeverCarriesAnOutOfRangeSpeed() {
		Wire.In in = new Wire.In(ClientChannel.armColourPacket(SUBJECT, BLUE, LOOK, 100000));
		in.varInt();
		in.uuid();
		in.varInt();
		in.varInt();
		assertEquals(ArmProtocol.PULSE_SPEED_MAX, in.varInt());
	}

	@Test
	void pulseSpeedClientPacketReads() {
		Wire.In in = new Wire.In(hex(CLIENT_PULSE_FIXTURE));
		assertEquals(Wire.C_ARM_COLOUR, in.varInt());
		assertEquals(BLUE, in.varInt());
		assertEquals(ArmProtocol.LOOK_ARMOUR, in.varInt());
		assertTrue(in.hasMore());
		assertEquals(PULSE, in.varInt());
		assertFalse(in.hasMore());
	}

	/** A client from before the pulse speed sends colour and look only: ClientChannel's read (hasMore, else the default) means the usual speed. */
	@Test
	void pulseSpeedMissingMeansTheUsualSpeed() {
		Wire.In in = new Wire.In(hex(CLIENT_FIXTURE));
		in.varInt();
		int rgb = in.varInt();
		int look = in.hasMore() ? in.varInt() : ArmProtocol.LOOK_DEFAULT;
		int pulse = in.hasMore() ? in.varInt() : ArmProtocol.PULSE_SPEED_DEFAULT;
		assertEquals(ArmProtocol.PULSE_SPEED_DEFAULT, pulse);
		ArmColours c = new ArmColours();
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, rgb, look, pulse, 10));
		assertEquals(ArmProtocol.PULSE_SPEED_DEFAULT, c.pulseSpeedOf(SUBJECT));
	}

	@Test
	void pulseSpeedDefaultsAndLimitsAreTheAgreedOnes() {
		assertEquals(100, ArmProtocol.PULSE_SPEED_DEFAULT);
		assertEquals(25, ArmProtocol.PULSE_SPEED_MIN);
		assertEquals(300, ArmProtocol.PULSE_SPEED_MAX);
		assertEquals(ArmProtocol.PULSE_SPEED_MIN, ArmProtocol.clampPulseSpeed(0));
		assertEquals(ArmProtocol.PULSE_SPEED_MIN, ArmProtocol.clampPulseSpeed(-40));
		assertEquals(ArmProtocol.PULSE_SPEED_MAX, ArmProtocol.clampPulseSpeed(Integer.MAX_VALUE));
		assertEquals(ArmProtocol.PULSE_SPEED_MAX, ArmProtocol.clampPulseSpeed(301));
		assertEquals(77, ArmProtocol.clampPulseSpeed(77));
		assertEquals(ArmProtocol.PULSE_SPEED_DEFAULT, new ArmColours().pulseSpeedOf(SUBJECT), "nobody is known: the usual speed");
	}

	@Test
	void aPulseSpeedChangeAloneIsAChangeAndIsKept() {
		ArmColours c = new ArmColours();
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, BLUE, LOOK, ArmProtocol.PULSE_SPEED_DEFAULT, 100));
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, BLUE, LOOK, PULSE, 200), "same colour and look, new speed");
		assertEquals(PULSE, c.pulseSpeedOf(SUBJECT));
		assertEquals(BLUE, c.of(SUBJECT));
		assertEquals(ArmColours.Result.SAME, c.request(SUBJECT, BLUE, LOOK, PULSE, 300));
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, BLUE, LOOK, ArmProtocol.PULSE_SPEED_DEFAULT, 400), "back to the usual speed is a change");
		assertEquals(ArmProtocol.PULSE_SPEED_DEFAULT, c.pulseSpeedOf(SUBJECT));
		assertEquals(Map.of(SUBJECT, ArmProtocol.PULSE_SPEED_DEFAULT), c.allPulseSpeeds());
	}

	@Test
	void anOutOfRangePulseSpeedIsClampedAndKeepsTheColour() {
		ArmColours c = new ArmColours();
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, BLUE, LOOK, 5000, 100));
		assertEquals(BLUE, c.of(SUBJECT), "the colour is not dropped");
		assertEquals(ArmProtocol.PULSE_SPEED_MAX, c.pulseSpeedOf(SUBJECT));
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, BLUE, LOOK, -3, 200));
		assertEquals(ArmProtocol.PULSE_SPEED_MIN, c.pulseSpeedOf(SUBJECT));
		assertEquals(ArmColours.Result.SAME, c.request(SUBJECT, BLUE, LOOK, 1, 300), "also the slowest: nothing changed");
	}

	@Test
	void aPulseSpeedIsCustomEvenWithTheDefaultColourAndLook() {
		ArmColours c = new ArmColours();
		assertFalse(c.custom(SUBJECT));
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, ArmProtocol.COLOUR_DEFAULT, LOOK, PULSE, 100));
		assertTrue(c.custom(SUBJECT), "it has to be relayed to everyone");
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, ArmProtocol.COLOUR_DEFAULT, LOOK, ArmProtocol.PULSE_SPEED_DEFAULT, 200));
		assertFalse(c.custom(SUBJECT));
	}

	@Test
	void aThrottledPulseSpeedIsHeldWithTheRestOfTheRequest() {
		ArmColours c = new ArmColours();
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, BLUE, LOOK, ArmProtocol.PULSE_SPEED_DEFAULT, 1000));
		assertEquals(ArmColours.Result.THROTTLED, c.request(SUBJECT, BLUE, LOOK, PULSE, 1001));
		assertArrayEquals(new int[] {BLUE, LOOK, PULSE}, c.takePending(SUBJECT));
		assertEquals(ArmProtocol.PULSE_SPEED_DEFAULT, c.pulseSpeedOf(SUBJECT), "not applied until taken");
	}

	@Test
	void aKeptPulseSpeedComesBackWithTheColourAndLookUntilTheClientSendsOne() {
		ArmColours c = new ArmColours();
		c.restore(SUBJECT, BLUE, ArmProtocol.LOOK_ARMOUR, PULSE);
		assertEquals(PULSE, c.pulseSpeedOf(SUBJECT));
		c.restore(SUBJECT, BLUE, ArmProtocol.LOOK_ARMOUR, 200);
		assertEquals(PULSE, c.pulseSpeedOf(SUBJECT), "a kept speed never overrides one already known this session");
		assertEquals(ArmColours.Result.SAME, c.request(SUBJECT, BLUE, ArmProtocol.LOOK_ARMOUR, PULSE, 10), "the client confirms what was kept");
		c.forget(SUBJECT);
		assertEquals(ArmProtocol.PULSE_SPEED_DEFAULT, c.pulseSpeedOf(SUBJECT));
		assertTrue(c.allPulseSpeeds().isEmpty());
		// a kept speed out of range is clamped; the three-argument restore (a file from before) means the usual speed
		c.restore(SUBJECT, BLUE, LOOK, 99999);
		assertEquals(ArmProtocol.PULSE_SPEED_MAX, c.pulseSpeedOf(SUBJECT));
		ArmColours d = new ArmColours();
		d.restore(SUBJECT, BLUE, LOOK);
		assertEquals(ArmProtocol.PULSE_SPEED_DEFAULT, d.pulseSpeedOf(SUBJECT));
	}

	/** The pulse speed goes only to clients from 8.0.0-beta.1 (ClientChannel.drawsPulseSpeed compares versions the same way). */
	@Test
	void pulseSpeedIsVersionGated() {
		assertEquals("8.0.0-beta.1", ArmProtocol.PULSE_SPEED_SINCE);
		assertTrue(dev.morphreborn.paper.SemVer.compare("7.6.0-beta.4", ArmProtocol.PULSE_SPEED_SINCE) < 0, "the last build before it is not sent the field");
		assertTrue(dev.morphreborn.paper.SemVer.compare("8.0.0-beta.1", ArmProtocol.PULSE_SPEED_SINCE) >= 0);
		assertTrue(dev.morphreborn.paper.SemVer.compare("8.0.0", ArmProtocol.PULSE_SPEED_SINCE) >= 0);
	}

	@Test
	void theLookNumbersAreTheAgreedOnes() {
		assertEquals(0, ArmProtocol.LOOK_ARM);
		assertEquals(1, ArmProtocol.LOOK_HAND);
		assertEquals(2, ArmProtocol.LOOK_ARMOUR);
		assertEquals(ArmProtocol.LOOK_ARM, ArmProtocol.LOOK_DEFAULT);
	}

	@Test
	void changesAreTakenRepeatsAreSameAndOutOfRangeIsDropped() {
		ArmColours c = new ArmColours();
		assertEquals(ArmProtocol.COLOUR_DEFAULT, c.of(SUBJECT));
		assertFalse(c.custom(SUBJECT));
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, BLUE, LOOK, 100));
		assertEquals(BLUE, c.of(SUBJECT));
		assertTrue(c.custom(SUBJECT));
		assertEquals(ArmColours.Result.SAME, c.request(SUBJECT, BLUE, LOOK, 200));
		assertEquals(ArmColours.Result.DROPPED, c.request(SUBJECT, 0x1000000, LOOK, 300));
		assertEquals(ArmColours.Result.DROPPED, c.request(SUBJECT, -1, LOOK, 400));
		assertEquals(BLUE, c.of(SUBJECT));
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, ArmProtocol.COLOUR_DEFAULT, LOOK, 500), "back to the default is a change");
		assertFalse(c.custom(SUBJECT));
	}

	@Test
	void moreThanOneInTheThrottleIsDropped() {
		ArmColours c = new ArmColours();
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, BLUE, LOOK, 1000));
		assertEquals(ArmColours.Result.THROTTLED, c.request(SUBJECT, 0x00FF00, LOOK, 1000 + ArmProtocol.COLOUR_SERVER_THROTTLE_TICKS - 1));
		// held, not lost: the latest throttled request is there to take once, and an accepted one clears it
		int[] held = c.takePending(SUBJECT);
		assertArrayEquals(new int[] {0x00FF00, LOOK, ArmProtocol.PULSE_SPEED_DEFAULT}, held);
		assertNull(c.takePending(SUBJECT));
		assertEquals(ArmColours.Result.THROTTLED, c.request(SUBJECT, 0x00FF00, LOOK, 1000 + ArmProtocol.COLOUR_SERVER_THROTTLE_TICKS - 1));
		assertEquals(BLUE, c.of(SUBJECT));
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, 0x00FF00, LOOK, 1000 + ArmProtocol.COLOUR_SERVER_THROTTLE_TICKS));
		assertTrue(ArmProtocol.COLOUR_SERVER_THROTTLE_TICKS < ArmProtocol.COLOUR_CLIENT_THROTTLE_TICKS, "an honest client is never dropped");
		// another player has their own throttle
		assertEquals(ArmColours.Result.CHANGED, c.request(new UUID(3, 4), BLUE, LOOK, 1000 + ArmProtocol.COLOUR_SERVER_THROTTLE_TICKS));
	}

	@Test
	void aKeptColourComesBackUntilTheClientSendsOne() {
		ArmColours c = new ArmColours();
		c.restore(SUBJECT, BLUE, LOOK);
		assertEquals(BLUE, c.of(SUBJECT));
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, 0x00FF00, LOOK, 10));
		c.restore(SUBJECT, BLUE, LOOK);
		assertEquals(0x00FF00, c.of(SUBJECT), "a kept colour never overrides one sent this session");
		c.forget(SUBJECT);
		assertEquals(ArmProtocol.COLOUR_DEFAULT, c.of(SUBJECT));
		assertTrue(c.all().isEmpty());
	}

	@Test
	void aLookChangeAloneIsAChange() {
		ArmColours c = new ArmColours();
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, BLUE, ArmProtocol.LOOK_ARM, 100));
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, BLUE, ArmProtocol.LOOK_HAND, 200), "same colour, new look");
		assertEquals(BLUE, c.of(SUBJECT));
		assertEquals(ArmProtocol.LOOK_HAND, c.lookOf(SUBJECT));
		assertEquals(ArmColours.Result.SAME, c.request(SUBJECT, BLUE, ArmProtocol.LOOK_HAND, 300), "same colour and look");
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, 0x00FF00, ArmProtocol.LOOK_HAND, 400), "same look, new colour");
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, 0x00FF00, ArmProtocol.LOOK_ARMOUR, 500));
		assertEquals(ArmProtocol.LOOK_ARMOUR, c.lookOf(SUBJECT));
	}

	@Test
	void anUnknownLookBecomesTheDefaultAndKeepsTheColour() {
		ArmColours c = new ArmColours();
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, BLUE, 99, 100));
		assertEquals(BLUE, c.of(SUBJECT), "the colour is not dropped");
		assertEquals(ArmProtocol.LOOK_DEFAULT, c.lookOf(SUBJECT));
		assertEquals(ArmColours.Result.SAME, c.request(SUBJECT, BLUE, -5, 200), "an unknown look is the default, which it already was");
		assertEquals(ArmProtocol.LOOK_DEFAULT, c.lookOf(SUBJECT));
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, BLUE, ArmProtocol.LOOK_HAND, 300));
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, BLUE, 99, 400), "back to the default look is a change");
		assertEquals(ArmProtocol.LOOK_DEFAULT, c.lookOf(SUBJECT));
	}

	@Test
	void aLookIsCustomEvenWithTheDefaultColour() {
		ArmColours c = new ArmColours();
		assertFalse(c.custom(SUBJECT));
		assertEquals(ArmProtocol.LOOK_DEFAULT, c.lookOf(SUBJECT));
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, ArmProtocol.COLOUR_DEFAULT, ArmProtocol.LOOK_HAND, 100));
		assertTrue(c.custom(SUBJECT));
		assertEquals(ArmProtocol.COLOUR_DEFAULT, c.of(SUBJECT));
		assertEquals(ArmColours.Result.CHANGED, c.request(SUBJECT, ArmProtocol.COLOUR_DEFAULT, ArmProtocol.LOOK_DEFAULT, 200));
		assertFalse(c.custom(SUBJECT));
	}

	@Test
	void aKeptColourAndLookComeBackTogether() {
		ArmColours c = new ArmColours();
		c.restore(SUBJECT, BLUE, ArmProtocol.LOOK_ARMOUR);
		assertEquals(BLUE, c.of(SUBJECT));
		assertEquals(ArmProtocol.LOOK_ARMOUR, c.lookOf(SUBJECT));
		assertEquals(ArmColours.Result.SAME, c.request(SUBJECT, BLUE, ArmProtocol.LOOK_ARMOUR, 10), "the client confirms what was kept");
		c.restore(SUBJECT, 0x00FF00, ArmProtocol.LOOK_HAND);
		assertEquals(BLUE, c.of(SUBJECT), "a kept colour never overrides one sent this session");
		assertEquals(ArmProtocol.LOOK_ARMOUR, c.lookOf(SUBJECT), "nor a kept look");
		c.forget(SUBJECT);
		assertEquals(ArmProtocol.LOOK_DEFAULT, c.lookOf(SUBJECT));
		assertTrue(c.allLooks().isEmpty());

		// a kept look with an unknown number is the default; a colour out of range is ignored
		c.restore(SUBJECT, BLUE, 99);
		assertEquals(ArmProtocol.LOOK_DEFAULT, c.lookOf(SUBJECT));
		ArmColours d = new ArmColours();
		d.restore(SUBJECT, 0x1000000, ArmProtocol.LOOK_HAND);
		assertFalse(d.custom(SUBJECT));
	}
}
