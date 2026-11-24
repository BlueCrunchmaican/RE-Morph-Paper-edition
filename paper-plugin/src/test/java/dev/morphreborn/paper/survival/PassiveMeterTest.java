package dev.morphreborn.paper.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The passives' meter: a drain that accrues only while acting, a bill once a second, a memory of what was paid for (2026-11-16). */
class PassiveMeterTest {
	@Test
	void aDrainIsExactInMilliAndOnlyAccruesWhileActing() {
		PassiveMeter m = new PassiveMeter();
		// 1.5 a minute = 1500 milli: after 1200 acting ticks exactly 1500 milli has come due, in runs of 10 ticks
		long total = 0;
		for (int run = 0; run < 120; run++) total += m.drain(1500, 10);
		assertEquals(1500, total);
		// idle runs bill nothing: a rate is only ticked when the passive acts, so not calling drain accrues nothing
		assertEquals(0, m.drain(1500, 0));
	}

	@Test
	void aDrainNeverBillsAMilliMoreOrLess() {
		PassiveMeter m = new PassiveMeter();
		long total = 0;
		for (int run = 0; run < 7; run++) total += m.drain(100, 10); // 70 ticks at 100 milli a minute: 5.83 milli
		assertEquals(70L * 100 / 1200, total);
	}

	@Test
	void aRateOfZeroAccruesNothing() {
		PassiveMeter m = new PassiveMeter();
		assertEquals(0, m.drain(0, 1000));
		assertEquals(0, m.drain(-5, 1000));
	}

	@Test
	void stoppingDropsTheLeftOver() {
		PassiveMeter m = new PassiveMeter();
		m.drain(1, 10); // a hair of a milli carried
		m.stopDrain();
		assertEquals(0, m.drain(1, 1199)); // not 1199 plus the carried hair: a full minute is needed again
	}

	@Test
	void aWindowBillsOnlyTheFirstEventOfASecond() {
		PassiveMeter m = new PassiveMeter();
		assertFalse(m.windowOpen("fire", 100, 20), "nothing paid yet");
		m.openWindow("fire", 100);
		assertTrue(m.windowOpen("fire", 100, 20));
		assertTrue(m.windowOpen("fire", 119, 20));
		assertFalse(m.windowOpen("fire", 120, 20), "the next second is billed again");
		m.openWindow("fire", 120);
		assertTrue(m.windowOpen("fire", 130, 20));
	}

	@Test
	void aWindowIsPerKey() {
		PassiveMeter m = new PassiveMeter();
		m.openWindow("minecraft:poison", 10);
		assertFalse(m.windowOpen("minecraft:wither", 10, 20), "another effect pays for itself");
	}

	@Test
	void aRefusedPaymentIsAskedAgainAtTheNextEvent() {
		PassiveMeter m = new PassiveMeter();
		// the caller opens the window only after paying: nothing opened, so the next event asks again
		assertFalse(m.windowOpen("fire", 5, 20));
		assertFalse(m.windowOpen("fire", 6, 20));
	}

	@Test
	void anItemIsPaidForOnceUntilItIsForgotten() {
		PassiveMeter m = new PassiveMeter();
		assertFalse(m.paid("item-1", 0));
		m.remember("item-1", 0, 6000);
		assertTrue(m.paid("item-1", 5999));
		assertFalse(m.paid("item-1", 6000), "an item despawns by then; a stack pulled again later is a new bill");
		assertFalse(m.paid("item-2", 10));
	}

	@Test
	void anOfflineMeterIsDroppedOnlyOnceEveryItemInItHasExpired() {
		PassiveMeter m = new PassiveMeter();
		m.remember("item-1", 0, 6000);
		m.remember("item-2", 0, 100);
		assertFalse(m.prune(50), "both are still paid for");
		assertFalse(m.prune(100), "item-2 is forgotten, item-1 is not");
		assertEquals(1, m.remembered());
		assertTrue(m.prune(6000), "nothing left: the meter can go");
		assertTrue(new PassiveMeter().prune(0), "an empty one can go at once");
	}

	@Test
	void theMemoryStaysSmall() {
		PassiveMeter m = new PassiveMeter();
		for (int i = 0; i < 300; i++) m.remember("item-" + i, 0, 10);
		m.remember("late", 1000, 10); // everything before has expired: swept when it grows
		assertTrue(m.remembered() <= 2, "" + m.remembered());
	}
}
