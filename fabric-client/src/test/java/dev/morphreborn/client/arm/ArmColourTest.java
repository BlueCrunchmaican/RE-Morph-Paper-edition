package dev.morphreborn.client.arm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.arm.render.ArmTint;
import dev.morphreborn.client.net.Wire;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The arm colour (C_ARM_COLOUR / S_ARM_COLOUR) byte for byte, with the same fixtures as the server's WireArmColourTest,
 * and the recolour that turns a colour into the arm's textures.
 */
class ArmColourTest {
	static final UUID SUBJECT = ArmWireTest.SUBJECT;
	/** C_ARM_COLOUR for 0x2A5BFF (blue) with the default look (the tendril arm). */
	static final String CLIENT_FIXTURE = "0A FF B7 A9 01 00";
	/** The same colour with the armour look. */
	static final String CLIENT_FIXTURE_ARMOUR = "0A FF B7 A9 01 02";
	/** S_ARM_COLOUR from a server older than the arm look: SUBJECT is 0x2A5BFF, no look field. */
	static final String SERVER_FIXTURE = "11 00 00 00 00 00 00 00 01 00 00 00 00 00 00 00 02 FF B7 A9 01";
	/** S_ARM_COLOUR: SUBJECT is 0x2A5BFF with the hand look. */
	static final String SERVER_FIXTURE_LOOK = SERVER_FIXTURE + " 01";
	static final int BLUE = 0x2A5BFF;

	private String savedColour, savedLook;

	@BeforeEach
	void reset() {
		ArmColours.clear();
		savedColour = ClientConfig.INSTANCE.armColour;
		savedLook = ClientConfig.INSTANCE.armLook;
		ClientConfig.INSTANCE.armLook = "arm";
	}

	@AfterEach
	void restore() {
		ClientConfig.INSTANCE.armColour = savedColour;
		ClientConfig.INSTANCE.armLook = savedLook;
		ArmColours.clear();
	}

	@Test
	void idsAreTheAgreedOnes() {
		assertEquals(10, Wire.C_ARM_COLOUR);
		assertEquals(17, Wire.S_ARM_COLOUR);
		assertEquals(0xFF3A3F, ArmProtocol.COLOUR_DEFAULT, "the default is the red the mod has always been");
	}

	@Test
	void clientPacketFixture() {
		assertArrayEquals(ArmWireTest.hex(CLIENT_FIXTURE), ArmColours.packet(BLUE, ArmProtocol.LOOK_ARM).bytes());
		assertArrayEquals(ArmWireTest.hex(CLIENT_FIXTURE_ARMOUR), ArmColours.packet(BLUE, ArmProtocol.LOOK_ARMOUR).bytes());
		// the server reads: id, then the colour, then the look
		Wire.In in = new Wire.In(ArmColours.packet(BLUE, ArmProtocol.LOOK_HAND).bytes());
		assertEquals(Wire.C_ARM_COLOUR, in.varInt());
		assertEquals(BLUE, in.varInt());
		assertEquals(ArmProtocol.LOOK_HAND, in.varInt());
		assertFalse(in.hasMore());
	}

	@Test
	void serverPacketRoundTrip() {
		Wire.In in = new Wire.In(ArmWireTest.hex(SERVER_FIXTURE));
		assertEquals(Wire.S_ARM_COLOUR, in.varInt());
		ArmColours.handle(in);
		assertEquals(BLUE, ArmColours.of(SUBJECT));
		assertFalse(in.hasMore());
		// what the server's encoder writes for any colour reads back the same
		for (int rgb : new int[] {0x000000, 0x123456, 0xFFFFFF, 0xF0F0F0}) {
			ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(SUBJECT).varInt(rgb).in());
			assertEquals(rgb, ArmColours.of(SUBJECT));
		}
	}

	@Test
	void defaultClearsAndOutOfRangeIsIgnored() {
		ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(SUBJECT).varInt(BLUE).in());
		ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(SUBJECT).varInt(0x1000000).in());
		assertEquals(BLUE, ArmColours.of(SUBJECT), "a colour out of range is dropped");
		ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(SUBJECT).varInt(ArmProtocol.COLOUR_DEFAULT).in());
		assertEquals(ArmProtocol.COLOUR_DEFAULT, ArmColours.of(SUBJECT));
		assertTrue(ArmColours.others().isEmpty(), "the default is not kept as a colour");
		assertEquals(ArmProtocol.COLOUR_DEFAULT, ArmColours.of(new UUID(9, 9)), "a player nobody spoke of is in the default");
	}

	@Test
	void yourOwnColourIsYourSettingNotTheRelay() {
		UUID me = new UUID(5, 5);
		ClientConfig.INSTANCE.armColour = "#00FF00";
		ArmColours.tick(me, 0);
		ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(me).varInt(BLUE).in());
		assertEquals(0x00FF00, ArmColours.of(me));
	}

	@Test
	void sendsOnceThenOnlyOnChangeAndNoFasterThanTheThrottle() {
		ClientConfig.INSTANCE.armColour = "#2A5BFF";
		Wire.Out first = ArmColours.tick(null, 100);
		assertNotNull(first, "sent once after the handshake");
		assertArrayEquals(ArmWireTest.hex(CLIENT_FIXTURE), first.bytes());
		assertNull(ArmColours.tick(null, 101), "nothing changed");
		ClientConfig.INSTANCE.armColour = "#FFFFFF";
		assertNull(ArmColours.tick(null, 100 + ArmProtocol.COLOUR_CLIENT_THROTTLE_TICKS - 1), "changed, but too soon");
		ClientConfig.INSTANCE.armColour = "#000000";
		Wire.Out later = ArmColours.tick(null, 100 + ArmProtocol.COLOUR_CLIENT_THROTTLE_TICKS);
		assertNotNull(later);
		assertArrayEquals(ArmColours.packet(0x000000, ArmProtocol.LOOK_ARM).bytes(), later.bytes(), "the latest colour, not the one in between");
		ArmColours.clear();
		assertNotNull(ArmColours.tick(null, 5), "a new server hears it again");
	}

	@Test
	void ownLookFollowsTheSetting() {
		assertEquals(ArmProtocol.LOOK_ARM, ArmColours.ownLook());
		ClientConfig.INSTANCE.armLook = "hand";
		assertEquals(ArmProtocol.LOOK_HAND, ArmColours.ownLook());
		ClientConfig.INSTANCE.armLook = "armour";
		assertEquals(ArmProtocol.LOOK_ARMOUR, ArmColours.ownLook());
		ClientConfig.INSTANCE.armLook = "tentacles";
		assertEquals(ArmProtocol.LOOK_DEFAULT, ArmColours.ownLook(), "an unknown name is the default");
		ClientConfig.INSTANCE.armLook = null;
		assertEquals(ArmProtocol.LOOK_DEFAULT, ArmColours.ownLook(), "a missing one too");
	}

	@Test
	void yourOwnLookIsYourSettingNotTheRelay() {
		UUID me = new UUID(5, 5);
		ClientConfig.INSTANCE.armLook = "armour";
		ArmColours.tick(me, 0);
		ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(me).varInt(BLUE).varInt(ArmProtocol.LOOK_HAND).in());
		assertEquals(ArmProtocol.LOOK_ARMOUR, ArmColours.lookOf(me));
	}

	@Test
	void aRelayedLookIsKeptForThatPlayer() {
		Wire.In in = new Wire.In(ArmWireTest.hex(SERVER_FIXTURE_LOOK));
		assertEquals(Wire.S_ARM_COLOUR, in.varInt());
		ArmColours.handle(in);
		assertFalse(in.hasMore());
		assertEquals(BLUE, ArmColours.of(SUBJECT));
		assertEquals(ArmProtocol.LOOK_HAND, ArmColours.lookOf(SUBJECT));
		assertEquals(ArmProtocol.LOOK_DEFAULT, ArmColours.lookOf(new UUID(9, 9)), "a player nobody spoke of has the default look");
		ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(SUBJECT).varInt(BLUE).varInt(ArmProtocol.LOOK_ARMOUR).in());
		assertEquals(ArmProtocol.LOOK_ARMOUR, ArmColours.lookOf(SUBJECT));
	}

	@Test
	void theDefaultLookIsNotKept() {
		ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(SUBJECT).varInt(BLUE).varInt(ArmProtocol.LOOK_ARMOUR).in());
		assertEquals(1, ArmColours.looks().size());
		ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(SUBJECT).varInt(BLUE).varInt(ArmProtocol.LOOK_DEFAULT).in());
		assertEquals(ArmProtocol.LOOK_DEFAULT, ArmColours.lookOf(SUBJECT));
		assertTrue(ArmColours.looks().isEmpty());
	}

	@Test
	void aPacketWithoutALookMeansTheDefaultLook() {
		ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(SUBJECT).varInt(BLUE).varInt(ArmProtocol.LOOK_HAND).in());
		// an older server's packet for the same player: the colour is applied and the look goes back to the default
		Wire.In in = new Wire.In(ArmWireTest.hex(SERVER_FIXTURE));
		in.varInt();
		ArmColours.handle(in);
		assertEquals(BLUE, ArmColours.of(SUBJECT));
		assertEquals(ArmProtocol.LOOK_DEFAULT, ArmColours.lookOf(SUBJECT));
	}

	@Test
	void anUnknownLookNumberIsTheDefaultAndTheColourStillApplies() {
		ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(SUBJECT).varInt(BLUE).varInt(ArmProtocol.LOOK_HAND).in());
		for (int unknown : new int[] {3, 7, 1000}) {
			ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(SUBJECT).varInt(0x112233).varInt(unknown).in());
			assertEquals(0x112233, ArmColours.of(SUBJECT), "the colour is not dropped for look " + unknown);
			assertEquals(ArmProtocol.LOOK_DEFAULT, ArmColours.lookOf(SUBJECT), "look " + unknown);
		}
		assertTrue(ArmColours.looks().isEmpty());
	}

	@Test
	void aColourOutOfRangeDropsTheWholePacketLookIncluded() {
		ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(SUBJECT).varInt(BLUE).varInt(ArmProtocol.LOOK_HAND).in());
		ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(SUBJECT).varInt(0x1000000).varInt(ArmProtocol.LOOK_ARMOUR).in());
		assertEquals(BLUE, ArmColours.of(SUBJECT));
		assertEquals(ArmProtocol.LOOK_HAND, ArmColours.lookOf(SUBJECT));
	}

	@Test
	void aLookChangeAloneIsSentAgainWithinTheThrottle() {
		ClientConfig.INSTANCE.armColour = "#2A5BFF";
		ClientConfig.INSTANCE.armLook = "arm";
		assertNotNull(ArmColours.tick(null, 100));
		assertNull(ArmColours.tick(null, 101), "nothing changed");
		ClientConfig.INSTANCE.armLook = "armour";
		assertNull(ArmColours.tick(null, 100 + ArmProtocol.COLOUR_CLIENT_THROTTLE_TICKS - 1), "the look changed, but too soon");
		Wire.Out later = ArmColours.tick(null, 100 + ArmProtocol.COLOUR_CLIENT_THROTTLE_TICKS);
		assertNotNull(later, "the look changed on its own");
		assertArrayEquals(ArmWireTest.hex(CLIENT_FIXTURE_ARMOUR), later.bytes(), "the same colour, the new look");
		assertNull(ArmColours.tick(null, 100 + 2 * ArmProtocol.COLOUR_CLIENT_THROTTLE_TICKS), "and only once");
	}

	@Test
	void clearForgetsLooks() {
		ArmColours.handle(new ArmWireTest.Enc(Wire.S_ARM_COLOUR).uuid(SUBJECT).varInt(BLUE).varInt(ArmProtocol.LOOK_HAND).in());
		assertFalse(ArmColours.looks().isEmpty());
		ArmColours.clear();
		assertTrue(ArmColours.looks().isEmpty());
		assertEquals(ArmProtocol.LOOK_DEFAULT, ArmColours.lookOf(SUBJECT));
	}

	@Test
	void looksParseByNameAndAnythingElseIsTheDefault() {
		assertEquals(ArmProtocol.LOOK_HAND, ArmProtocol.parseLook(" Hand "));
		assertEquals(ArmProtocol.LOOK_ARMOUR, ArmProtocol.parseLook("ARMOUR"));
		for (String bad : new String[] {null, "", "armor", "2"}) {
			assertEquals(ArmProtocol.LOOK_DEFAULT, ArmProtocol.parseLook(bad), String.valueOf(bad));
		}
		assertTrue(ArmProtocol.validLook(2));
		assertFalse(ArmProtocol.validLook(3));
		assertFalse(ArmProtocol.validLook(-1));
		assertEquals(ArmProtocol.LOOK_DEFAULT, ArmProtocol.lookOrDefault(99));
	}

	@Test
	void hexParsesAndBadTextFallsBackToTheDefault() {
		assertEquals(0x2A5BFF, ArmProtocol.parseColour("#2a5bff"));
		assertEquals(0x2A5BFF, ArmProtocol.parseColour(" 2A5BFF "));
		assertEquals("#2A5BFF", ArmProtocol.colourHex(0x2A5BFF));
		assertEquals("#000000", ArmProtocol.colourHex(0));
		for (String bad : new String[] {null, "", "#12345", "#1234567", "#GG0000", "red", "-12345"}) {
			assertEquals(ArmProtocol.COLOUR_DEFAULT, ArmProtocol.parseColour(bad), String.valueOf(bad));
		}
		assertTrue(ArmProtocol.validColour(0xFFFFFF));
		assertFalse(ArmProtocol.validColour(0x1000000));
		assertFalse(ArmProtocol.validColour(-1));
	}

	@Test
	void theDefaultRecoloursToItself() {
		for (int texel : new int[] {0xFF69181A, 0xFF060000, 0xFF7A1010, 0xFFFF5A4A, 0xFF000000, 0x80CA3D33}) {
			assertEquals(texel, ArmTint.recolour(texel, ArmProtocol.COLOUR_DEFAULT));
		}
	}

	@Test
	void aColourKeepsTheShadingInItsHue() {
		int bright = ArmTint.recolour(0xFFFF5A4A, BLUE), dark = ArmTint.recolour(0xFF7A1010, BLUE), stat = ArmTint.recolour(0xFF69181A, BLUE);
		for (int c : new int[] {bright, dark, stat}) {
			int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
			assertTrue(b > r && b > g, "blue leads: " + Integer.toHexString(c));
			assertEquals(0xFF, c >>> 24, "alpha kept");
		}
		assertTrue((bright & 0xFF) > (dark & 0xFF), "the glow's bright end stays brighter than its dark end");
		// white keeps the static's brightness steps as greys
		int grey = ArmTint.recolour(0xFF69181A, 0xFFFFFF);
		assertEquals((grey >> 16) & 0xFF, grey & 0xFF);
		assertEquals((grey >> 8) & 0xFF, grey & 0xFF);
		// the colour's own value: the default's brightest red becomes the colour itself
		int itself = ArmTint.recolour(0xFF000000 | ArmProtocol.COLOUR_DEFAULT, BLUE);
		for (int shift = 0; shift <= 16; shift += 8) {
			assertTrue(Math.abs(((itself >> shift) & 0xFF) - ((BLUE >> shift) & 0xFF)) <= 1, "within rounding: " + Integer.toHexString(itself));
		}
		assertEquals(0xFF000000, ArmTint.recolour(0xFFFF5A4A, 0x000000), "black is black");
	}
}
