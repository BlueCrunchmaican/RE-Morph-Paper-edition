package dev.morphreborn.client.arm.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The spyglass's marks (S_ARM_FX kind FX_ACCESSORY, the spyglass's place; 2026-11-16): decoded as the server sends them (arg =
 * place 2 in bits 8-11 and the move, ids = the creature without the owner, the owner as origin, ticks = time left), several held at
 * once, taken away by an UNMARK, and timed by the server's own clock.
 */
class SpyglassFxTest {
	private static final int OWNER = 7;
	private static final int MARK_ARG = 0x201, UNMARK_ARG = 0x202;

	@AfterEach
	void reset() {
		SpyglassFx.clearMark();
	}

	private static ArmFxEvent event(int origin, int arg, int ticks, long tick, int... ids) {
		return new ArmFxEvent(origin, ArmProtocol.FX_ACCESSORY, 2, ticks, 99, ids, new long[0], arg, tick);
	}

	private static int[] marks() {
		int[] out = new int[SpyglassFx.count()];
		for (int i = 0; i < out.length; i++) out[i] = SpyglassFx.id(i);
		return out;
	}

	@Test
	void theServersArgsAreTheSpyglassesMoves() {
		assertEquals(MARK_ARG, ArmProtocol.accessoryArg(ArmProtocol.ACCESSORY_PLACE_SPYGLASS, SpyglassFx.MARK), "the server's SpyglassRules.fxArg(FX_MARK)");
		assertEquals(UNMARK_ARG, ArmProtocol.accessoryArg(ArmProtocol.ACCESSORY_PLACE_SPYGLASS, SpyglassFx.UNMARK));
		assertTrue(SpyglassFx.handles(MARK_ARG) && SpyglassFx.handles(UNMARK_ARG));
		for (int other : new int[] {0, 1, 2, 3, 4, 5, 0x101, 0x102, 0x301, 0x200, 0x203, -1}) assertFalse(SpyglassFx.handles(other), "arg " + other + " is not the spyglass's");
	}

	@Test
	void aMarkNamesTheCreatureInIdsAndTheOwnerIsTheOrigin() {
		SpyglassFx.apply(OWNER, event(OWNER, MARK_ARG, 100, 1000L, 42));
		assertEquals(1, SpyglassFx.count());
		assertEquals(42, SpyglassFx.marked());
	}

	@Test
	void anEventFromAnyoneElseOrOfAnotherAccessoryIsIgnored() {
		SpyglassFx.apply(OWNER, event(8, MARK_ARG, 100, 1000L, 42));
		SpyglassFx.apply(OWNER, event(OWNER, 0x101, 100, 1000L, 42));
		assertEquals(0, SpyglassFx.count());
		SpyglassFx.apply(OWNER, event(OWNER, MARK_ARG, 100, 1000L, OWNER, -1));
		assertEquals(0, SpyglassFx.count(), "never the owner or a bad id");
		assertEquals(-1, SpyglassFx.marked());
	}

	@Test
	void severalMarksAreHeldInTheOrderTheyCame() {
		SpyglassFx.apply(OWNER, event(OWNER, MARK_ARG, 100, 1000L, 10));
		SpyglassFx.apply(OWNER, event(OWNER, MARK_ARG, 100, 1001L, 11));
		SpyglassFx.apply(OWNER, event(OWNER, MARK_ARG, 100, 1002L, 12));
		assertEquals(3, SpyglassFx.count());
		assertEquals(10, SpyglassFx.id(0));
		assertEquals(12, SpyglassFx.id(2));
		SpyglassFx.apply(OWNER, event(OWNER, MARK_ARG, 100, 1003L, 11)); // told again: still one
		assertEquals(3, SpyglassFx.count());
	}

	@Test
	void anUnmarkTakesAwaySomeOrAll() {
		SpyglassFx.apply(OWNER, event(OWNER, MARK_ARG, 100, 1000L, 10));
		SpyglassFx.apply(OWNER, event(OWNER, MARK_ARG, 100, 1000L, 11));
		SpyglassFx.apply(OWNER, event(OWNER, MARK_ARG, 100, 1000L, 12));
		SpyglassFx.apply(OWNER, event(OWNER, UNMARK_ARG, 0, 1001L, 11));
		assertEquals(2, SpyglassFx.count());
		assertEquals(10, SpyglassFx.id(0));
		assertEquals(12, SpyglassFx.id(1));
		SpyglassFx.apply(OWNER, event(8, UNMARK_ARG, 0, 1001L));
		assertEquals(2, SpyglassFx.count(), "not the owner's: ignored");
		SpyglassFx.apply(OWNER, event(OWNER, UNMARK_ARG, 0, 1002L));
		assertEquals(0, SpyglassFx.count(), "no ids: every mark");
	}

	@Test
	void aMarkEndsWhereTheServersClockSaysAndEachMessageResetsIt() {
		SpyglassFx.apply(OWNER, event(OWNER, MARK_ARG, 100, 1000L, 10));
		SpyglassFx.apply(OWNER, event(OWNER, MARK_ARG, 30, 1000L, 11));
		SpyglassFx.expire(1029L);
		assertEquals(2, SpyglassFx.count());
		SpyglassFx.expire(1030L);
		assertEquals(1, SpyglassFx.count(), "11 had 30 ticks left");
		SpyglassFx.apply(OWNER, event(OWNER, MARK_ARG, 100, 1050L, 10)); // renewed: ends at 1150 now
		SpyglassFx.expire(1100L);
		assertEquals(1, SpyglassFx.count());
		SpyglassFx.expire(1150L);
		assertEquals(0, SpyglassFx.count());
		SpyglassFx.apply(OWNER, event(OWNER, MARK_ARG, 0, 2000L, 10));
		SpyglassFx.expire(2050L);
		assertEquals(1, SpyglassFx.count(), "a message with no time left to say takes the default life");
		SpyglassFx.expire(2000L + SpyglassFx.DEFAULT_TICKS);
		assertEquals(0, SpyglassFx.count());
	}

	@Test
	void theMostMarksHeldIsBoundedAndTheOldestGoes() {
		for (int i = 0; i < SpyglassFx.MAX + 3; i++) SpyglassFx.apply(OWNER, event(OWNER, MARK_ARG, 100, 1000L, 100 + i));
		assertEquals(SpyglassFx.MAX, SpyglassFx.count());
		assertEquals(103, SpyglassFx.id(0), "the first three were pushed out");
		assertEquals(100 + SpyglassFx.MAX + 2, SpyglassFx.id(SpyglassFx.MAX - 1));
		assertEquals(marks().length, SpyglassFx.count());
	}

	@Test
	void aCreatureThatIsGoneCanBeRemoved() {
		SpyglassFx.apply(OWNER, event(OWNER, MARK_ARG, 100, 1000L, 10));
		SpyglassFx.apply(OWNER, event(OWNER, MARK_ARG, 100, 1000L, 11));
		SpyglassFx.remove(10);
		SpyglassFx.remove(99);
		assertEquals(1, SpyglassFx.count());
		assertEquals(11, SpyglassFx.marked());
	}
}
