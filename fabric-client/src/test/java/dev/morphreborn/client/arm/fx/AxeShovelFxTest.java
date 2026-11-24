package dev.morphreborn.client.arm.fx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.model.ArmReactions;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * The axe's and the shovel's world effects, as far as they are arithmetic: Fell's climb up the trunk, the logs going
 * top-down at the server's rate, the tendril trailing them and drawing back; Cleave's swing, its crescent's trail and
 * its bite; Burrow's ring, its claw print on the dug face and its window (opened by the effect, kept open by resends,
 * closed by the empty one). The drawing itself is done in the game (the rig); what makes it the same for every viewer is
 * that all of it comes from the effect's own fields and the world.
 */
class AxeShovelFxTest {
	private static long pos(int x, int y, int z) {
		return BlockPos.asLong(x, y, z);
	}

	/** A trunk of {@code height} logs at (10, 64.., 10), listed top-down as the server sends them, with two side branches. */
	private static long[] tree(int height) {
		long[] out = new long[height + 2];
		int n = 0;
		for (int y = 64 + height - 1; y >= 64; y--) {
			out[n++] = pos(10, y, 10);
			if (n == 2) out[n++] = pos(11, y, 10); // a branch log up near the top
			if (n == 5) out[n++] = pos(9, y, 10);
		}
		return Arrays.copyOf(out, n);
	}

	// ------------------------------------------------------------------ the kinds each handler draws

	@Test
	void eachHandlerDrawsItsOwnKind() {
		assertArrayEquals(new int[] {ArmProtocol.FX_FELL}, new FellFx().kinds());
		assertArrayEquals(new int[] {ArmProtocol.FX_CLEAVE}, new CleaveFx().kinds());
		assertArrayEquals(new int[] {ArmProtocol.FX_BURROW}, new BurrowFx().kinds());
	}

	// ------------------------------------------------------------------ Fell

	@Test
	void fellListsTheLogsTopDownAndFindsTheFootAndTheTop() {
		FellFx.Fell f = FellFx.of(1, tree(7), 0);
		assertEquals(f.n, f.xyz.length / 3);
		assertEquals(8, FellFx.DEFAULT_RATE, "arg 0 means the server's default of 8 a tick");
		assertEquals(8, f.perTick);
		assertEquals(10, f.footX);
		assertEquals(64, f.footY, "the foot is the lowest log");
		assertEquals(10, f.footZ);
		assertEquals(64 + 7, f.topY, 1e-9, "the top is the highest log's top face");
		assertEquals(5, FellFx.of(1, tree(7), 5).perTick, "the server's own rate");
		assertEquals(64, FellFx.of(1, tree(7), 500).perTick, "a mad rate is capped at 64");
	}

	@Test
	void logsGoTopDownAtTheServersRateAndNeverMoreThanThereAre() {
		assertEquals(0, FellFx.broken(0F, 20, 8));
		assertEquals(0, FellFx.broken(1.9F, 20, 8));
		assertEquals(8, FellFx.broken(2F, 20, 8));
		assertEquals(8, FellFx.broken(2.99F, 20, 8));
		assertEquals(16, FellFx.broken(3F, 20, 8));
		assertEquals(20, FellFx.broken(4F, 20, 8), "capped at the logs there are");
		assertEquals(20, FellFx.broken(1000F, 20, 8));
		assertEquals(0, FellFx.broken(-3F, 20, 8));
		int last = 0;
		for (float a = 0F; a < 30F; a += 0.37F) {
			int now = FellFx.broken(a, 77, 8);
			assertTrue(now >= last, "logs never come back");
			last = now;
		}
	}

	@Test
	void theClimbIsFastAndMonotonic() {
		assertEquals(0F, FellFx.climbed(0F), 1e-6F);
		assertEquals(1F, FellFx.climbed(FellFx.CLIMB_FROM + FellFx.CLIMB_TICKS), 1e-6F);
		assertTrue(FellFx.CLIMB_TICKS <= 2.5F, "a small tree is down in two ticks: the climb has to fit");
		float last = -1F;
		for (float a = -1F; a < 4F; a += 0.1F) {
			float c = FellFx.climbed(a);
			assertTrue(c >= last - 1e-6F && c >= 0F && c <= 1F);
			last = c;
		}
		assertEquals(0F, FellFx.rootGrown(0F), 1e-6F);
		assertEquals(1F, FellFx.rootGrown(FellFx.ROOT_GROW), 1e-6F);
	}

	@Test
	void theTendrilIsCutWithTheLogsAndTrailsThemByATick() {
		FellFx.Fell f = FellFx.of(1, tree(30), 8);
		// climbing: the head's height rises from the foot to the top
		assertEquals(f.footY, FellFx.headY(f, 0F), 1e-6);
		assertTrue(FellFx.headY(f, 1.1F) > f.footY + 3 && FellFx.headY(f, 1.1F) < f.topY);
		assertEquals(f.topY, FellFx.headY(f, 2.3F), 1e-6, "at the top of the tree before a log has gone (the first goes at tick 2, the tendril trails it by one)");
		// then it follows the highest log still standing, down
		double last = Double.MAX_VALUE;
		for (float a = 2.3F; a < f.endsAt() + 1; a += 0.25F) {
			double y = FellFx.headY(f, a);
			assertTrue(y <= last + 1e-9, "the head only ever goes down once the logs do: " + a);
			last = y;
		}
		assertEquals(f.footY, FellFx.headY(f, f.endsAt()), 1e-6, "no trunk left: the head is at the foot");
		// the whole fell is over a tick-and-a-bit's drawing back after the last log
		assertEquals(FellFx.FIRST_BREAK + (f.n + 7) / 8 + FellFx.TRAIL, f.endsAt());
		assertEquals(f.endsAt() + (int) FellFx.RELEASE + 1, f.finish());
	}

	@Test
	void theWholePathDrawsBackOverTheReleaseTime() {
		int ends = 10;
		assertEquals(1F, FellFx.reach(0F, ends), 1e-6F);
		assertEquals(1F, FellFx.reach(ends, ends), 1e-6F);
		assertEquals(0F, FellFx.reach(ends + FellFx.RELEASE, ends), 1e-6F);
		float last = 2F;
		for (float a = 0F; a < 20F; a += 0.2F) {
			float r = FellFx.reach(a, ends);
			assertTrue(r <= last + 1e-6F, "it only ever draws back");
			last = r;
		}
	}

	@Test
	void theHelixKeepsAFewStationsToATurnAndNeverTooMany() {
		assertEquals(4, FellFx.stations(0F));
		assertEquals(4, FellFx.stations(0.4F));
		assertTrue(FellFx.stations(7F) >= 20 && FellFx.stations(7F) <= 24, "7 blocks is about 3 turns: " + FellFx.stations(7F));
		assertEquals(48, FellFx.stations(32F), "capped, so a 32-block tree is two draws of 48 stations at most");
		assertEquals(48, FellFx.stations(1000F));
		int last = 0;
		for (float h = 0F; h < 40F; h += 0.5F) {
			int n = FellFx.stations(h);
			assertTrue(n >= last && n >= 4 && n <= 48);
			last = n;
		}
	}

	@Test
	void fellEventsBuildTheSameFromTheWire() {
		// the event as the server sends it: origin, kind, level, ticks, seed, ids, positions, arg
		ArmFxEvent e = new ArmFxEvent(7, ArmProtocol.FX_FELL, 2, 0, 5, new int[0], tree(5), 8, 0L);
		FellFx fx = new FellFx();
		fx.start(e);
		fx.clear();
		// an event with no logs is nothing to draw (a refused fell never reaches the viewers, but a garbled one mustn't throw)
		fx.start(new ArmFxEvent(7, ArmProtocol.FX_FELL, 2, 0, 5, new int[0], new long[0], 8, 0L));
		// more than the cap is cut off at the server's own
		long[] huge = new long[400];
		for (int i = 0; i < huge.length; i++) huge[i] = pos(0, 200 - i / 4, i % 4);
		assertEquals(FellFx.MAX_LOGS, FellFx.of(1, huge, 8).n);
	}

	// ------------------------------------------------------------------ Cleave

	@Test
	void theChopSwingsDownOverTicksTwoToFive() {
		assertEquals(0F, CleaveFx.lead(0F), 1e-6F);
		assertEquals(0F, CleaveFx.lead(CleaveFx.SWEEP_FROM), 1e-6F);
		assertEquals(CleaveFx.SWEEP_DEG, CleaveFx.lead(CleaveFx.SWEEP_FROM + CleaveFx.SWEEP_TICKS), 1e-4F);
		assertEquals(CleaveFx.SWEEP_DEG, CleaveFx.lead(50F), 1e-4F);
		assertTrue(CleaveFx.START_DEG - CleaveFx.SWEEP_DEG < 0F, "it ends below the forward direction");
		float last = -1F;
		for (float a = 0F; a < 9F; a += 0.1F) {
			float l = CleaveFx.lead(a);
			assertTrue(l >= last - 1e-5F);
			last = l;
			float t = CleaveFx.tail(a);
			assertTrue(t >= 0F && t <= l + 1e-5F, "the tail is never ahead of the lead");
			assertTrue(l - t <= CleaveFx.TRAIL + 1e-4F, "and never more than 55 degrees behind");
		}
		// the tail catches the lead over 2 ticks after the swing
		float end = CleaveFx.SWEEP_FROM + CleaveFx.SWEEP_TICKS;
		assertEquals(CleaveFx.SWEEP_DEG, CleaveFx.lead(end + 2F), 1e-4F);
		assertEquals(CleaveFx.SWEEP_DEG, CleaveFx.tail(end + CleaveFx.CATCH_UP), 1e-3F);
	}

	@Test
	void theLeadReachesEachSpotOnTheTick() {
		for (float along = 5F; along < CleaveFx.SWEEP_DEG; along += 11F) {
			float at = CleaveFx.reachesAt(along);
			assertEquals(along, CleaveFx.lead(at), 0.5F, "the lead is at " + along + " degrees when " + at + " says");
			assertTrue(at >= CleaveFx.SWEEP_FROM && at <= CleaveFx.SWEEP_FROM + CleaveFx.SWEEP_TICKS);
		}
	}

	@Test
	void theBiteClosesHoldsAndOpens() {
		assertEquals(0F, CleaveFx.bite(0F), 1e-6F);
		assertEquals(0F, CleaveFx.bite(CleaveFx.BITE_FROM), 1e-6F);
		assertEquals(1F, CleaveFx.bite(CleaveFx.BITE_FROM + CleaveFx.BITE_CLOSE + 0.5F), 1e-5F);
		assertEquals(0F, CleaveFx.bite(CleaveFx.BITE_FROM + CleaveFx.BITE_CLOSE + CleaveFx.BITE_HOLD + CleaveFx.BITE_OPEN), 1e-5F);
		assertTrue(CleaveFx.BITE_FROM + CleaveFx.BITE_CLOSE + CleaveFx.BITE_HOLD + CleaveFx.BITE_OPEN <= CleaveFx.LIFE, "it is over before the effect is dropped");
		// the jaws close after the crescent has passed through the creature (it lands about tick 4)
		assertTrue(CleaveFx.reachesAt(CleaveFx.START_DEG + 10F) <= CleaveFx.BITE_FROM + 0.6F, "the crescent is at the middle of the target by the time of the bite");
	}

	@Test
	void theChopsRadiusReachesTheCreatureWithinItsLimits() {
		assertEquals(CleaveFx.OWN_RADIUS, CleaveFx.radius(true, 3.0), 1e-6F, "your own arm in first person: a fixed arc across the view");
		assertEquals(CleaveFx.DEFAULT_RADIUS, CleaveFx.radius(false, -1.0), 1e-6F, "nothing named: a default");
		assertEquals(3.2F, CleaveFx.radius(false, 3.2), 1e-5F);
		assertEquals(CleaveFx.MIN_RADIUS, CleaveFx.radius(false, 0.4), 1e-6F);
		assertEquals(CleaveFx.MAX_RADIUS, CleaveFx.radius(false, 40.0), 1e-6F);
		assertEquals(CleaveFx.DEFAULT_RADIUS, CleaveFx.radius(false, Double.NaN), 1e-6F, "a garbled distance can't stretch it");
	}

	// ------------------------------------------------------------------ Burrow

	@Test
	void theRingOpensAndClosesAndNeverOverruns() {
		assertEquals(0F, BurrowFx.ring(0F, -1F), 1e-6F);
		assertEquals(1F, BurrowFx.ring(BurrowFx.RING_OPEN, -1F), 1e-6F);
		assertEquals(1F, BurrowFx.ring(500F, -1F), 1e-6F);
		assertEquals(0F, BurrowFx.ring(500F, BurrowFx.RING_CLOSE), 1e-6F);
		assertTrue(BurrowFx.ring(1F, -1F) > 0F && BurrowFx.ring(1F, -1F) < 1F);
		// closing before it has fully opened: never above where it had got
		assertTrue(BurrowFx.ring(1F, 0.2F) <= BurrowFx.ring(1F, -1F) + 1e-6F);
	}

	@Test
	void theClawPrintGrowsOverThreeTicksAndDrawsBackOverTwo() {
		assertEquals(0F, BurrowFx.print(0F, -1F), 1e-6F);
		assertEquals(1F, BurrowFx.print(BurrowFx.PRINT_GROW, -1F), 1e-6F);
		assertEquals(1F, BurrowFx.print(100F, 0F), 1e-6F, "nothing drawn back yet the instant digging stops");
		assertEquals(0F, BurrowFx.print(100F, BurrowFx.PRINT_BACK), 1e-6F);
		assertEquals(0F, BurrowFx.print(100F, 1000F), 1e-6F, "long since stopped");
		assertEquals(0F, BurrowFx.print(1000F, Long.MAX_VALUE / 4F), 1e-6F, "a window that never dug draws nothing");
	}

	@Test
	void theEightNeighboursAreTheOtherEightOfTheThreeByThree() {
		float[] pairs = new float[16];
		BurrowFx.neighbours(pairs);
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 8; i++) {
			float a = pairs[2 * i], b = pairs[2 * i + 1];
			assertTrue(Math.abs(a) <= 1F && Math.abs(b) <= 1F);
			assertFalse(a == 0F && b == 0F, "never the block itself");
			assertTrue(seen.add(a + "," + b), "each once");
		}
		assertEquals(8, seen.size());
	}

	@Test
	void theWindowIsOpenedByTheEffectKeptOpenByResendsAndEndedByTheEmptyOne() {
		BurrowFx fx = new BurrowFx();
		assertNull(fx.stateOf(5));
		long old = Arms.TICK;
		try {
			Arms.TICK = 100;
			fx.start(new ArmFxEvent(5, ArmProtocol.FX_BURROW, 2, 300, 0, new int[0], new long[0], 0, 100L));
			boolean[] state = fx.stateOf(5);
			assertNotNull(state);
			assertTrue(state[0], "open");
			assertFalse(state[1], "not digging yet");
			// resent while it lasts: still the same window
			fx.start(new ArmFxEvent(5, ArmProtocol.FX_BURROW, 2, 280, 0, new int[0], new long[0], 0, 120L));
			assertTrue(fx.stateOf(5)[0]);
			// ended: the effect with no ticks left
			ArmFxEvent end = new ArmFxEvent(5, ArmProtocol.FX_BURROW, 2, 0, 0, new int[0], new long[0], 0, 130L);
			assertTrue(end.ends());
			fx.start(end);
			assertFalse(fx.stateOf(5)[0], "closing");
			// a new window opens afresh
			fx.start(new ArmFxEvent(5, ArmProtocol.FX_BURROW, 2, 200, 0, new int[0], new long[0], 0, 140L));
			assertTrue(fx.stateOf(5)[0]);
			// an end for someone who never opened one is nothing
			fx.start(new ArmFxEvent(9, ArmProtocol.FX_BURROW, 2, 0, 0, new int[0], new long[0], 0, 150L));
			assertNull(fx.stateOf(9));
			fx.clear();
			assertNull(fx.stateOf(5));
		} finally {
			Arms.TICK = old;
		}
	}

	@Test
	void noMoreWindowsThanTheLimit() {
		BurrowFx fx = new BurrowFx();
		for (int i = 0; i < BurrowFx.MAX + 4; i++) fx.start(new ArmFxEvent(100 + i, ArmProtocol.FX_BURROW, 2, 300, 0, new int[0], new long[0], 0, 0L));
		int open = 0;
		for (int i = 0; i < BurrowFx.MAX + 4; i++) if (fx.stateOf(100 + i) != null) open++;
		assertEquals(BurrowFx.MAX, open);
	}

	// ------------------------------------------------------------------ a stopped felling

	@Test
	void anEmptyFellLetsGoOfTheTendrilAndEndsTheSpecialInHand() {
		ArmReactions.clear();
		long old = Arms.TICK;
		try {
			Arms.TICK = 100;
			FellFx fell = new FellFx();
			fell.start(new ArmFxEvent(7, ArmProtocol.FX_FELL, 2, 0, 5, new int[0], tree(40), 8, 100L));
			FellFx.Fell f = fell.firstForTest();
			int natural = f.endsAt();
			Arms.TICK = 102;
			// the server stopped it (a reshape): an empty list, the same origin
			fell.start(new ArmFxEvent(7, ArmProtocol.FX_FELL, 2, 0, 5, new int[0], new long[0], 8, 102L));
			assertTrue(f.endsAt() <= 2, "it lets go where it was (tick 2), not after the last log");
			assertTrue(f.endsAt() < natural);
			assertEquals(1F, FellFx.reach(f.endsAt() - 1, f.endsAt()), 1e-4F);
			assertEquals(0F, FellFx.reach(f.endsAt() + FellFx.RELEASE, f.endsAt()), 1e-4F);
			ArmReactions.Reaction r = ArmReactions.get(7);
			assertEquals(2.0, r.specialLength, 1e-9, "the arm in hand stops rattling");
			// another player's empty effect leaves it alone
			fell.start(new ArmFxEvent(8, ArmProtocol.FX_FELL, 2, 0, 5, new int[0], tree(40), 8, 102L));
			fell.start(new ArmFxEvent(9, ArmProtocol.FX_FELL, 2, 0, 5, new int[0], new long[0], 8, 102L));
			assertEquals(2, fell.countForTest(), "an empty effect from someone with no felling starts nothing");
		} finally {
			Arms.TICK = old;
			ArmReactions.clear();
		}
	}

	// ------------------------------------------------------------------ in hand: the specials ride on the arm's reactions

	@Test
	void theSpecialsAreInHandForEveryViewerFromTheSameEffect() {
		ArmReactions.clear();
		try {
			// Fell: the axe rattles for as long as the felling lasts
			FellFx fell = new FellFx();
			fell.start(new ArmFxEvent(7, ArmProtocol.FX_FELL, 2, 0, 5, new int[0], tree(20), 8, 100L));
			ArmReactions.Reaction r = ArmReactions.get(7);
			assertNotNull(r, "the arm's reactions are made from the effect");
			assertEquals(ArmProtocol.FX_FELL, r.specialKind);
			assertEquals(100.0, r.specialAt, 1e-9);
			assertEquals(FellFx.of(7, tree(20), 8).endsAt(), r.specialLength, 1e-9);
			// Cleave: the plates splay for 6 ticks
			new CleaveFx().start(new ArmFxEvent(8, ArmProtocol.FX_CLEAVE, 2, 0, 5, new int[] {3}, new long[0], 0, 200L));
			ArmReactions.Reaction c = ArmReactions.get(8);
			assertEquals(ArmProtocol.FX_CLEAVE, c.specialKind);
			assertEquals(CleaveFx.SPLAY_TICKS, c.specialLength, 1e-9);
			// Burrow: the window, extended by resends and ended by the empty one
			BurrowFx burrow = new BurrowFx();
			burrow.start(new ArmFxEvent(9, ArmProtocol.FX_BURROW, 2, 300, 0, new int[0], new long[0], 0, 100L));
			ArmReactions.Reaction b = ArmReactions.get(9);
			assertEquals(ArmProtocol.FX_BURROW, b.specialKind);
			assertEquals(300.0, b.specialLength, 1e-9);
			burrow.start(new ArmFxEvent(9, ArmProtocol.FX_BURROW, 2, 280, 0, new int[0], new long[0], 0, 120L));
			assertEquals(100.0, b.specialAt, 1e-9, "a resend extends the window, it doesn't start another");
			assertEquals(300.0, b.specialLength, 1e-9);
			burrow.start(new ArmFxEvent(9, ArmProtocol.FX_BURROW, 2, 0, 0, new int[0], new long[0], 0, 130L));
			assertEquals(30.0, b.specialLength, 1e-9, "ended by the empty effect");
			burrow.start(new ArmFxEvent(9, ArmProtocol.FX_BURROW, 2, 200, 0, new int[0], new long[0], 0, 140L));
			assertEquals(140.0, b.specialAt, 1e-9, "the next window starts afresh");
			// a window for an arm that was hit a moment ago keeps what it had
			ArmReactions.get(9).hit(139.0);
			burrow.start(new ArmFxEvent(9, ArmProtocol.FX_BURROW, 2, 190, 0, new int[0], new long[0], 0, 150L));
			assertEquals(139.0, ArmReactions.get(9).hitAt, 1e-9);
		} finally {
			ArmReactions.clear();
		}
	}
}
