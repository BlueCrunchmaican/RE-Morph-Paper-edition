package dev.morphreborn.client.arm.fx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import org.junit.jupiter.api.Test;

/**
 * The claws' world side (FX_CLAWS, 2026-11-16): the server's packed contract decoded (the args here are encoded as the server's ClawMath
 * encodes them, copied below), Rake's ribbons timed to the server's swings, who is marked (only who a slash really hit), and a bleed keyed by
 * (owner, creature), replaced by the latest, run for its message's ticks, re-armed by a pulse and ended by an end.
 */
class ClawsFxTest {
	// ---- the server's ClawMath encoders (paper-plugin .../arm/rules/ClawMath.java), copied: neither side may renumber

	private static final int GAP = 3;

	private static int rakeArg(int slashes, int arcDeg, boolean leftHanded, int reachTenths) {
		return 1 | (Math.clamp(slashes, 0, 15) << 4) | (Math.clamp(arcDeg, 0, 1023) << 8) | (leftHanded ? 1 << 18 : 0) | (GAP << 19)
				| (Math.clamp(reachTenths, 0, 63) << 24);
	}

	private static int slashArg(int index) {
		return 2 | (Math.clamp(index, 0, 15) << 4);
	}

	private static int bleedArg(int intervalTicks) {
		return 3 | (Math.clamp(intervalTicks, 0, 255) << 4);
	}

	private static final int PULSE = 4, END = 5;
	private static final int OWNER = 7, OTHER = 8, ZOMBIE = 100, COW = 101;

	private static ArmFxEvent msg(int origin, int level, int ticks, int seed, int[] ids, int arg, long tick) {
		return new ArmFxEvent(origin, ArmProtocol.FX_CLAWS, level, ticks, seed, ids, new long[0], arg, tick);
	}

	@Test
	void contractIsTheServersFxKindAndPackedMoves() {
		assertEquals(19, ArmProtocol.FX_CLAWS);
		assertEquals(21, ArmProtocol.FX_KINDS);
		assertEquals("claws", ArmProtocol.fxName(ArmProtocol.FX_CLAWS));
		assertEquals(1, ClawsFx.MOVE_RAKE);
		assertEquals(2, ClawsFx.MOVE_SLASH);
		assertEquals(3, ClawsFx.MOVE_BLEED);
		assertEquals(4, ClawsFx.MOVE_BLEED_PULSE);
		assertEquals(5, ClawsFx.MOVE_BLEED_END);
		assertArrayEquals(new int[] {ArmProtocol.FX_CLAWS}, new ClawsFx().kinds());
	}

	@Test
	void theServersPackedArgsDecode() {
		int rake = rakeArg(3, 100, false, 40);
		assertNotEquals(ClawsFx.MOVE_RAKE, rake, "the real Rake arg is not the bare move: a test for == 1 would never match");
		assertEquals(ClawsFx.MOVE_RAKE, ClawsFx.move(rake));
		assertEquals(3, ClawsFx.slashes(rake));
		assertEquals(100, ClawsFx.arcDeg(rake));
		assertFalse(ClawsFx.leftHanded(rake));
		assertEquals(GAP, ClawsFx.gap(rake));
		int left = rakeArg(6, 180, true, 63);
		assertEquals(ClawsFx.MOVE_RAKE, ClawsFx.move(left));
		assertEquals(6, ClawsFx.slashes(left));
		assertEquals(180, ClawsFx.arcDeg(left));
		assertTrue(ClawsFx.leftHanded(left));
		assertEquals(GAP, ClawsFx.gap(left));
		int max = rakeArg(15, 1023, true, 63);
		assertEquals(15, ClawsFx.slashes(max));
		assertEquals(1023, ClawsFx.arcDeg(max));
		assertEquals(GAP, ClawsFx.gap(max));
		for (int i = 0; i < 6; i++) {
			assertEquals(ClawsFx.MOVE_SLASH, ClawsFx.move(slashArg(i)));
			assertEquals(i, ClawsFx.slashIndex(slashArg(i)));
		}
		assertEquals(ClawsFx.MOVE_BLEED, ClawsFx.move(bleedArg(20)));
		assertEquals(20, ClawsFx.bleedInterval(bleedArg(20)));
		assertEquals(255, ClawsFx.bleedInterval(bleedArg(9999)));
		assertEquals(ClawsFx.MOVE_BLEED_PULSE, ClawsFx.move(PULSE));
		assertEquals(ClawsFx.MOVE_BLEED_END, ClawsFx.move(END));
	}

	@Test
	void ribbonsAreTimedToTheServersSwings() {
		// slash i swings from GAP*i: one ribbon per slash, three ticks apart, not a tick or two as the old stagger
		for (int i = 0; i < 3; i++) assertEquals(3F * i, ClawsFx.startOf(i, GAP), 1e-6F);
		for (int i = 0; i < 3; i++) {
			float start = ClawsFx.startOf(i, GAP);
			assertEquals(0F, ClawsFx.lead(start, start), 1e-6F, "not started before its tick");
			assertEquals(1F, ClawsFx.lead(start + 3F, start), 1e-6F, "all the way along by 3 ticks");
			float last = -1F;
			for (float age = 0F; age <= 20F; age += 0.25F) {
				float lead = ClawsFx.lead(age, start), tail = ClawsFx.tail(age, start);
				assertTrue(lead >= last - 1e-6F, "the lead never goes back");
				assertTrue(tail <= lead + 1e-6F && tail >= 0F, "the tail is behind the lead");
				last = lead;
			}
			float mid = start + 1.5F;
			assertTrue(ClawsFx.lead(mid, start) - ClawsFx.tail(mid, start) > 0.3F, "a mark has length while it is drawn");
			float done = ClawsFx.rakeLife(3, GAP);
			assertEquals(1F, ClawsFx.tail(done, ClawsFx.startOf(i, GAP)), 1e-6F, "every ribbon is gone by the rake's life");
		}
		assertTrue(ClawsFx.rakeLife(3, GAP) >= 2 * GAP + 6, "the last ribbon has time to finish");
		assertTrue(ClawsFx.rakeLife(1, GAP) < ClawsFx.rakeLife(3, GAP));
	}

	@Test
	void slashesAlternateSideAndAHandedPlayerIsMirrored() {
		assertEquals(1F, ClawsFx.sideOf(false, 0), "a right-handed player's first slash comes from the right, as the server's");
		assertEquals(-1F, ClawsFx.sideOf(false, 1), "and the next comes back");
		assertEquals(1F, ClawsFx.sideOf(false, 2));
		assertEquals(-1F, ClawsFx.sideOf(true, 0), "a left-handed player is mirrored");
		assertEquals(1F, ClawsFx.sideOf(true, 1));
	}

	@Test
	void theMiddleMarkIsLongestAndLevelsGrowThem() {
		assertTrue(ClawsFx.lengthOf(1) > ClawsFx.lengthOf(0));
		assertEquals(ClawsFx.lengthOf(0), ClawsFx.lengthOf(2), 1e-6F);
		assertEquals(ClawsFx.lengthOf(1), ClawsFx.lengthOf(4), 1e-6F, "and round again for a longer Rake");
		assertEquals(List3.of(-1, 0, 1, -1), List3.of(ClawsFx.lane(0), ClawsFx.lane(1), ClawsFx.lane(2), ClawsFx.lane(3)));
		assertTrue(ClawsFx.tierScale(1) < ClawsFx.tierScale(2) && ClawsFx.tierScale(2) < ClawsFx.tierScale(3));
		assertEquals(1F, ClawsFx.tierScale(1), 1e-6F);
	}

	/** Four ints compared as one value. */
	private record List3(int a, int b, int c, int d) {
		static List3 of(int a, int b, int c, int d) {
			return new List3(a, b, c, d);
		}
	}

	@Test
	void aRakeAloneMarksNobodyAndASlashMarksOnlyWhoItHit() {
		ClawsFx fx = new ClawsFx();
		long t = 100;
		// the Rake names two creatures in the fan; the client does not mark them
		fx.start(msg(OWNER, 2, 9, 555, new int[] {ZOMBIE, COW}, rakeArg(3, 100, false, 40), t));
		assertEquals(1, fx.rakeCount());
		assertEquals(0, fx.hitCount(), "who was in the fan is not who was hit");
		// slash 0 hit only the zombie
		fx.start(msg(OWNER, 2, 0, 555, new int[] {ZOMBIE}, slashArg(0), t + 1));
		assertEquals(1, fx.hitCount());
		// slash 1 hit nobody: nothing to mark
		fx.start(msg(OWNER, 2, 0, 555, new int[0], slashArg(1), t + 4));
		assertEquals(1, fx.hitCount());
		fx.start(msg(OWNER, 2, 0, 555, new int[] {ZOMBIE, COW}, slashArg(2), t + 7));
		assertEquals(2, fx.hitCount());
		// the marks last a short time, the ribbons their rake's life
		fx.expire(t + 7 + ClawsFx.HIT_LIFE + 1);
		assertEquals(0, fx.hitCount());
		assertEquals(0, fx.rakeCount(), "the rake is over too by then");
	}

	@Test
	void aBleedRunsForTheMessagesTicksNotAFixedEighteen() {
		ClawsFx fx = new ClawsFx();
		long t = 200;
		fx.start(msg(OWNER, 2, 80, 9, new int[] {ZOMBIE}, bleedArg(20), t));
		assertEquals(1, fx.bleedCount());
		assertEquals(t + 80 + ClawsFx.LINGER, fx.bleedUntil(OWNER, ZOMBIE));
		fx.expire(t + 18 + 1);
		assertEquals(1, fx.bleedCount(), "still bleeding after the old fixed eighteen ticks");
		fx.expire(t + 80 + ClawsFx.LINGER);
		assertEquals(0, fx.bleedCount(), "and gone when its time is up");
	}

	@Test
	void aLaterBleedOnTheSamePairReplacesTheEarlier() {
		ClawsFx fx = new ClawsFx();
		long t = 300;
		// three slashes of one Rake top one bleed up, never stack
		fx.start(msg(OWNER, 2, 60, 9, new int[] {ZOMBIE}, bleedArg(20), t + 1));
		fx.start(msg(OWNER, 2, 60, 9, new int[] {ZOMBIE}, bleedArg(20), t + 4));
		fx.start(msg(OWNER, 2, 60, 9, new int[] {ZOMBIE}, bleedArg(20), t + 7));
		assertEquals(1, fx.bleedCount());
		assertEquals(t + 7 + 60 + ClawsFx.LINGER, fx.bleedUntil(OWNER, ZOMBIE), "the latest message sets its length");
		// another creature, and another owner's bleed on the same creature, are their own
		fx.start(msg(OWNER, 2, 60, 9, new int[] {COW}, bleedArg(20), t + 7));
		fx.start(msg(OTHER, 2, 60, 9, new int[] {ZOMBIE}, bleedArg(20), t + 7));
		assertEquals(3, fx.bleedCount());
	}

	@Test
	void aPulseRearmsAndAnEndEndsOnlyItsOwnersBleed() {
		ClawsFx fx = new ClawsFx();
		long t = 400;
		fx.start(msg(OWNER, 2, 40, 9, new int[] {ZOMBIE}, bleedArg(20), t));
		fx.start(msg(OTHER, 2, 40, 9, new int[] {ZOMBIE}, bleedArg(20), t)); // the bow's bleed on the same creature, by another origin
		long first = fx.bleedUntil(OWNER, ZOMBIE);
		// a pulse late in its life runs it on for another pulse's time
		fx.start(msg(OWNER, 2, 0, 0, new int[] {ZOMBIE}, PULSE, t + 38));
		assertTrue(fx.bleedUntil(OWNER, ZOMBIE) > first, "re-armed");
		assertEquals(t + 38 + 20 + ClawsFx.LINGER, fx.bleedUntil(OWNER, ZOMBIE));
		assertEquals(first, fx.bleedUntil(OTHER, ZOMBIE), "the other origin's bleed is untouched");
		// an end ends the owner's, not the other's
		fx.start(msg(OWNER, 2, 0, 0, new int[] {ZOMBIE}, END, t + 50));
		assertEquals(-1L, fx.bleedUntil(OWNER, ZOMBIE));
		assertEquals(1, fx.bleedCount());
		assertTrue(fx.bleedUntil(OTHER, ZOMBIE) > 0);
		// a pulse whose bleed was missed still shows a wound
		fx.start(msg(OWNER, 2, 0, 0, new int[] {COW}, PULSE, t + 60));
		assertTrue(fx.bleedUntil(OWNER, COW) > t + 60);
		fx.clear();
		assertEquals(0, fx.bleedCount() + fx.hitCount() + fx.rakeCount());
	}

	@Test
	void anEndInTheTickOfTheLastPulseKeepsTheBleedForItsSpurt() {
		ClawsFx fx = new ClawsFx();
		long t = 500;
		fx.start(msg(OWNER, 2, 40, 9, new int[] {ZOMBIE}, bleedArg(20), t));
		// the last pulse sends PULSE and END in one tick: the spurt must still be there to draw
		fx.start(msg(OWNER, 2, 0, 0, new int[] {ZOMBIE}, PULSE, t + 40));
		fx.start(msg(OWNER, 2, 0, 0, new int[] {ZOMBIE}, END, t + 40));
		assertEquals(1, fx.bleedCount(), "kept for the spurt");
		assertTrue(fx.bleedUntil(OWNER, ZOMBIE) <= t + 40 + 8, "but not for a pulse's whole interval");
		fx.expire(t + 40 + 8);
		assertEquals(0, fx.bleedCount(), "gone once the spurt is");
	}

	@Test
	void theBleedsDripCycleRepeatsForAsLongAsItRuns() {
		assertEquals(0F, ClawsFx.cycleAge(0F), 1e-6F);
		assertEquals(5F, ClawsFx.cycleAge(5F), 1e-6F);
		assertEquals(5F, ClawsFx.cycleAge(ClawsFx.CYCLE + 5F), 1e-4F);
		assertEquals(0.5F, ClawsFx.cycleAge(3 * ClawsFx.CYCLE + 0.5F), 1e-3F);
		for (float age = 0F; age < 200F; age += 0.7F) {
			float c = ClawsFx.cycleAge(age);
			assertTrue(c >= 0F && c < ClawsFx.CYCLE + 1e-3F, "inside the cycle: " + c);
		}
	}

	@Test
	void theBleedsDropsGatherThenFallAndItsStrandsDrawBack() {
		assertTrue(ClawsFx.dropClock(0F, 0) > ClawsFx.dropClock(0F, 2), "later drops start later (their clock is further behind)");
		assertTrue(ClawsFx.dropClock(0F, 0) < 0F, "none at the very first tick");
		assertEquals(0F, ClawsFx.strandLength(0F), 1e-6F, "a strand starts at nothing");
		float held = ClawsFx.strandLength(6F);
		assertTrue(held > 0.25F, "and is out and held: " + held);
		assertEquals(0F, ClawsFx.strandLength(16F), 1e-6F, "and drawn back by 16 ticks");
		assertTrue(ClawsFx.CYCLE >= 16F, "the cycle holds it");
		// the last drop's fall (it gathers from 7.5 ticks, for 4, falls over 8) ends inside the cycle
		assertTrue(ClawsFx.dropClock(ClawsFx.CYCLE, 2) >= 4F + 8F);
		float last = Float.MAX_VALUE;
		for (float age = 10F; age <= 16F; age += 0.5F) {
			float len = ClawsFx.strandLength(age);
			assertTrue(len <= last + 1e-6F, "only draws back");
			last = len;
		}
	}
}
