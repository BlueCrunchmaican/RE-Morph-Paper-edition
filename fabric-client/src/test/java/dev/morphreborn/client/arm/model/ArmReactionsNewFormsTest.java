package dev.morphreborn.client.arm.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.geom.ArmDraw;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What the new forms' effects do to the arm in hand (2026-11-16): each special winds the tool for a short, fixed time and only on the
 * moves that are the hand's own moment. The moves are the server's numbering (docs/PROTOCOL.md).
 */
class ArmReactionsNewFormsTest {
	private static final int OWNER = 41;

	@AfterEach
	void reset() {
		ArmReactions.clear();
	}

	private static void fx(int kind, int ticks, int seed, int[] ids, int arg, long at) {
		ArmReactions.onFx(new ArmFxEvent(OWNER, kind, 2, ticks, seed, ids, new long[0], arg, at));
	}

	private static ArmReactions.Reaction mine() {
		ArmReactions.Reaction r = ArmReactions.get(OWNER);
		assertNotNull(r);
		return r;
	}

	private static int pulses(ArmReactions.Reaction r) {
		return r.pulseCount;
	}

	@Test
	void theMacesLandingIsAShortFlareNotTheFallInTenths() {
		fx(ArmProtocol.FX_MACE, 1000, 3, new int[] {9}, ArmReactions.MACE_LANDING, 100L);
		ArmReactions.Reaction r = mine();
		assertEquals(ArmProtocol.FX_MACE, r.specialKind);
		assertEquals(ArmReactions.FLARE_TICKS, r.specialLength, 1e-9, "a 100 block fall in tenths is not 1000 ticks of wind-up");
		// the leap's rise is its own length, clamped to something worth seeing
		fx(ArmProtocol.FX_MACE, 12, 3, new int[0], ArmReactions.MACE_LEAP, 200L);
		assertEquals(12.0, r.specialLength, 1e-9);
		assertEquals(200.0, r.specialAt, 1e-9);
	}

	@Test
	void theClawsWindUpOnlyForRakeAndForTheRakesOwnLength() {
		int rake = 1 | (3 << 4);
		fx(ArmProtocol.FX_CLAWS, 9, 3, new int[] {4}, rake, 100L);
		ArmReactions.Reaction r = mine();
		assertEquals(ArmProtocol.FX_CLAWS, r.specialKind);
		assertEquals(9.0, r.specialLength, 1e-9);
		// a rake with no length on the message is the default three slashes
		fx(ArmProtocol.FX_CLAWS, 0, 3, new int[0], rake, 150L);
		assertEquals(9.0, r.specialLength, 1e-9);
		assertEquals(150.0, r.specialAt, 1e-9);
		// slashes, bleeds (their ticks are 3 to 5 s) and their pulses and ends are the target's: the hand keeps its last special untouched
		int count = pulses(r);
		fx(ArmProtocol.FX_CLAWS, 0, 3, new int[] {4}, 2 | (1 << 4), 160L);
		fx(ArmProtocol.FX_CLAWS, 80, 3, new int[] {4}, 3 | (10 << 4), 170L);
		fx(ArmProtocol.FX_CLAWS, 0, 0, new int[] {4}, 4, 180L);
		fx(ArmProtocol.FX_CLAWS, 0, 0, new int[] {4}, 5, 190L);
		assertEquals(150.0, r.specialAt, 1e-9, "no later message wound the talons again");
		assertEquals(9.0, r.specialLength, 1e-9);
		assertEquals(count, pulses(r));
	}

	@Test
	void aBoltSpendsOnlyWhenAnArrowLeft() {
		// the bow's ARMED and TIP notices have no ids and spend nothing; a bolt (the bow's or the crossbow's) does
		fx(ArmProtocol.FX_BOLT, 40, 0, new int[0], (1 << 14) | (3 << 8), 100L);
		assertNull(ArmReactions.get(OWNER), "an ARMED notice touches nothing");
		fx(ArmProtocol.FX_BOLT, 0, 0, new int[0], (1 << 15) | (2 << 8), 110L);
		assertNull(ArmReactions.get(OWNER), "a TIP notice touches nothing");
		fx(ArmProtocol.FX_BOLT, 0, 5, new int[] {77}, 100, 120L);
		assertEquals(1, pulses(mine()));
	}

	@Test
	void theSpearFlaresOnTheLungeAndTheLandingNotForThePinsHold() {
		fx(ArmProtocol.FX_SPEAR, 5, 3, new int[] {4}, ArmReactions.SPEAR_LUNGE, 100L);
		ArmReactions.Reaction r = mine();
		assertEquals(ArmProtocol.FX_SPEAR, r.specialKind);
		assertEquals(5.0, r.specialLength, 1e-9);
		fx(ArmProtocol.FX_SPEAR, 40, 3, new int[] {4}, ArmReactions.SPEAR_PIN, 105L);
		assertEquals(ArmReactions.FLARE_TICKS, r.specialLength, 1e-9, "the spikes hold the creature for 40 ticks, not the hand");
		int count = pulses(r);
		fx(ArmProtocol.FX_SPEAR, 0, 3, new int[] {4}, 3, 140L); // RELEASE
		assertEquals(105.0, r.specialAt, 1e-9);
		assertEquals(count, pulses(r));
	}

	@Test
	void theTridentFlaresOnTheThrowOnceNotOnEachResend() {
		fx(ArmProtocol.FX_TRIDENT, 60, 7, new int[] {5}, ArmReactions.TRIDENT_THROW, 100L);
		ArmReactions.Reaction r = mine();
		assertEquals(ArmProtocol.FX_TRIDENT, r.specialKind);
		assertEquals(100.0, r.specialAt, 1e-9);
		assertEquals(ArmReactions.FLARE_TICKS, r.specialLength, 1e-9);
		fx(ArmProtocol.FX_TRIDENT, 40, 7, new int[] {5}, ArmReactions.TRIDENT_THROW, 120L);
		fx(ArmProtocol.FX_TRIDENT, 20, 7, new int[] {5}, ArmReactions.TRIDENT_THROW, 140L);
		assertEquals(100.0, r.specialAt, 1e-9, "a resend of the same throw doesn't wind the hand up again");
		// the reel, a strike and the end are not the hand's moment
		fx(ArmProtocol.FX_TRIDENT, 30, 7, new int[] {5}, 2, 150L);
		fx(ArmProtocol.FX_TRIDENT, 0, 7, new int[] {5, 8}, 5, 155L);
		fx(ArmProtocol.FX_TRIDENT, 0, 7, new int[0], 0, 158L);
		assertEquals(100.0, r.specialAt, 1e-9);
		// a recall is; a new throw (a new seed) is
		fx(ArmProtocol.FX_TRIDENT, 6, 7, new int[] {5, 8}, 3, 160L);
		assertEquals(160.0, r.specialAt, 1e-9);
		fx(ArmProtocol.FX_TRIDENT, 60, 9, new int[] {6}, ArmReactions.TRIDENT_THROW, 300L);
		assertEquals(300.0, r.specialAt, 1e-9);
	}

	@Test
	void aLateViewersThrowIsNotTheHandsMoment() {
		// a viewer who comes into range mid-flight is sent the throw with the trident's block as its one position: no wind-up for the owner's hand
		ArmReactions.onFx(new ArmFxEvent(OWNER, ArmProtocol.FX_TRIDENT, 2, 30, 7, new int[] {5}, new long[] {1234L}, ArmReactions.TRIDENT_THROW, 100L));
		assertFalse(mine().specialKind == ArmProtocol.FX_TRIDENT, "no trident flare");
		// the same seed again, without a position, is still a repeat of a throw the hand has already seen
		fx(ArmProtocol.FX_TRIDENT, 20, 7, new int[] {5}, ArmReactions.TRIDENT_THROW, 110L);
		assertFalse(mine().specialKind == ArmProtocol.FX_TRIDENT);
	}

	@Test
	void theCrossbowWindsForTheBurstAndNotForRegrowsOrSpines() {
		// the contract's numbering: 1 BOLT, 2 SPINES (the burst), 3 REGROW
		assertEquals(2, ArmReactions.CROSSBOW_BURST);
		fx(ArmProtocol.FX_CROSSBOW, 0, 3, new int[] {9}, 1, 100L); // BOLT
		fx(ArmProtocol.FX_CROSSBOW, 25, 3, new int[0], 3, 110L); // REGROW
		assertNull(ArmReactions.get(OWNER));
		// 3 spines at a gap of 2 ticks: the burst runs 4 ticks, and the recoil follows
		fx(ArmProtocol.FX_CROSSBOW, 2, 3, new int[] {9, 10, 11}, ArmReactions.CROSSBOW_BURST, 130L);
		ArmReactions.Reaction r = mine();
		assertEquals(ArmProtocol.FX_CROSSBOW, r.specialKind);
		assertEquals(8.0, r.specialLength, 1e-9, "the burst's 4 ticks and the recoil");
		// a longer burst holds the hand for longer
		fx(ArmProtocol.FX_CROSSBOW, 2, 3, new int[] {1, 2, 3, 4, 5, 6}, ArmReactions.CROSSBOW_BURST, 200L);
		assertEquals(14.0, mine().specialLength, 1e-9, "6 spines at gap 2: 10 ticks and the recoil");
	}

	@Test
	void anAccessoryEffectDecodesItsPlaceAndMove() {
		// my decision, 2026-11-16: arg = place << 8 | move
		assertEquals(0x101, ArmProtocol.accessoryArg(ArmProtocol.ACCESSORY_PLACE_SHIELD, 1));
		assertEquals(0x202, ArmProtocol.accessoryArg(ArmProtocol.ACCESSORY_PLACE_SPYGLASS, 2));
		assertEquals(0x302, ArmProtocol.accessoryArg(ArmProtocol.ACCESSORY_PLACE_FLINT, 2));
		for (int place = 0; place < ArmProtocol.ACCESSORIES.size(); place++) {
			assertEquals(place, ArmProtocol.accessoryPlace(ArmProtocol.accessoryArg(place, 0xFF)));
			assertEquals(0xFF, ArmProtocol.accessoryMove(ArmProtocol.accessoryArg(place, 0xFF)));
		}
		assertEquals(ArmProtocol.ACCESSORIES.indexOf(ArmProtocol.SHIELD), ArmProtocol.ACCESSORY_PLACE_SHIELD);
		assertEquals(ArmProtocol.ACCESSORIES.indexOf(ArmProtocol.SPYGLASS), ArmProtocol.ACCESSORY_PLACE_SPYGLASS);
		assertEquals(ArmProtocol.ACCESSORIES.indexOf(ArmProtocol.FLINT), ArmProtocol.ACCESSORY_PLACE_FLINT);
		assertEquals(ArmProtocol.ACCESSORIES.indexOf(ArmProtocol.LANTERN), ArmProtocol.ACCESSORY_PLACE_LANTERN);
	}

	@Test
	void anAccessoryEffectIsAShortPulseAndNeverAWinding() {
		// a shield's block, reflect and absorb, and flint's ignition and lit block, give the tool one spend pulse and wind nothing
		int[] args = {0x101, 0x102, 0x103, 0x301, 0x302};
		for (int i = 0; i < args.length; i++) {
			ArmReactions.clear();
			fx(ArmProtocol.FX_ACCESSORY, 20, 3, new int[] {9}, args[i], 100L);
			ArmReactions.Reaction r = mine();
			assertEquals(1, pulses(r), "arg " + Integer.toHexString(args[i]));
			assertEquals(ArmDraw.NO_SPECIAL, r.specialKind, "an accessory never takes the special slot");
		}
		// ... so a Rake in progress is not cut short by a block landing in the middle of it
		ArmReactions.clear();
		fx(ArmProtocol.FX_CLAWS, 9, 3, new int[] {9}, ArmReactions.CLAWS_RAKE, 100L);
		fx(ArmProtocol.FX_ACCESSORY, 0, 3, new int[] {9}, 0x101, 102L);
		assertEquals(ArmProtocol.FX_CLAWS, mine().specialKind);
	}

	@Test
	void theSpyglassAndAnUnknownPlaceAreTheWorldsOnly() {
		// the marks (resent every 20 ticks) and their end, the lantern's place (it sends none) and a newer server's place 7
		for (int arg : new int[] {0x201, 0x202, 0x001, 0x701, 0x401}) {
			fx(ArmProtocol.FX_ACCESSORY, 100, 3, new int[] {9}, arg, 100L);
			assertNull(ArmReactions.get(OWNER), "arg " + Integer.toHexString(arg));
		}
		// a move the shield or flint does not have is the world's too
		fx(ArmProtocol.FX_ACCESSORY, 0, 3, new int[] {9}, 0x1FF, 100L);
		fx(ArmProtocol.FX_ACCESSORY, 0, 3, new int[] {9}, 0x300, 100L);
		assertNull(ArmReactions.get(OWNER));
	}
}
