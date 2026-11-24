package dev.morphreborn.client.arm.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pulses on the feed coordinate: IDLE repeats every 46 ticks in two trains half a period apart, SPEND reaches a
 * katana's tip (φ 36.5) in 4.9 ticks, FEED runs from the tip back to the shoulder, and events are read as
 * (kind, start) pairs.
 */
class PulseTest {
	/** A katana's feed length: sheath 8, fist 4, then the tool to its tip. */
	private static final float KATANA = 36.5F;

	@Test
	void idleRepeatsEvery46Ticks() {
		for (float seed : new float[] {0F, 0.37F * 300, 4.2F}) {
			for (float t = 0.5F; t < 200F; t += 3.3F) {
				for (float phi = 0F; phi <= KATANA; phi += 2.5F) {
					assertEquals(Pulse.idle(phi, t, seed, KATANA), Pulse.idle(phi, t + 46F, seed, KATANA), 1e-4F, "seed " + seed + " t " + t + " phi " + phi);
				}
			}
		}
		assertEquals(46F, Pulse.IDLE_PERIOD);
	}

	@Test
	void idleIsTwoPulsesHalfAPeriodApart() {
		// at the shoulder end of the tool (phi 20), count the peaks over one period
		int peaks = 0;
		float before = Pulse.idle(20F, -0.1F, 0F, KATANA), now = Pulse.idle(20F, 0F, 0F, KATANA);
		float firstPeak = -1F;
		for (float t = 0.1F; t <= 46F; t += 0.1F) {
			float next = Pulse.idle(20F, t, 0F, KATANA);
			if (now > before && now >= next && now > 0.2F) {
				peaks++;
				if (firstPeak < 0F) firstPeak = t - 0.1F;
				else assertEquals(23F, t - 0.1F - firstPeak, 0.15F, "the second train is half a period behind");
			}
			before = now;
			now = next;
		}
		assertEquals(2, peaks);
		// a peak is the pulse's own amplitude, 0.45, where IDLE crosses phi 20 at 2.2 px a tick
		assertEquals(0.45F, Pulse.at(Pulse.Kind.IDLE, 20F, 20F / 2.2F, KATANA), 1e-4F);
	}

	@Test
	void spendReachesTheTipIn4Point9Ticks() {
		float best = -1F, bestAt = -1F;
		for (float age = 0F; age < 12F; age += 0.01F) {
			float a = Pulse.at(Pulse.Kind.SPEND, KATANA, age, KATANA);
			if (a > best) {
				best = a;
				bestAt = age;
			}
		}
		assertEquals(4.9F, bestAt, 0.2F, "SPEND's peak at the tip");
		assertEquals(0.60F, best, 1e-3F);
		// the same through amp() and an event list, with the idle pulses taken away
		float[] events = {Pulse.Kind.SPEND.ordinal(), 100F};
		float spend = Pulse.amp(KATANA, 104.9F, 1F, events, 1, KATANA) - Pulse.idle(KATANA, 104.9F, 1F, KATANA);
		assertEquals(0.60F, spend, 1e-3F);
		// it starts at the top of the fist, not at the shoulder: a tick in, it is 5 px into the tool
		assertTrue(Pulse.at(Pulse.Kind.SPEND, 2F, 1F, KATANA) < 0.01F);
		assertEquals(0.60F, Pulse.at(Pulse.Kind.SPEND, Pulse.TOOL_START + 5F, 1F, KATANA), 1e-4F);
	}

	@Test
	void feedRunsBackToTheShoulder() {
		assertEquals(0.80F, Pulse.at(Pulse.Kind.FEED, KATANA, 1F, KATANA) / ArmMath.gauss(4F, 2.5F), 1e-3F, "one tick in, 4 px back from the tip");
		assertEquals(0.80F, Pulse.at(Pulse.Kind.FEED, 0F, KATANA / 4F, KATANA), 1e-3F, "at the shoulder after 36.5 / 4 ticks");
		assertTrue(Pulse.at(Pulse.Kind.FEED, KATANA, KATANA / 4F, KATANA) < 1e-3F, "gone from the tip by then");
	}

	@Test
	void pulsesEaseInAndEnd() {
		assertEquals(0F, Pulse.at(Pulse.Kind.SPEND, Pulse.TOOL_START, 0F, KATANA), "a pulse never pops in at full strength");
		assertEquals(0F, Pulse.at(Pulse.Kind.SPEND, KATANA, -1F, KATANA), "nor before it starts");
		float life = Pulse.lifetime(Pulse.Kind.SPEND, KATANA);
		assertEquals((KATANA - 12F + 6F) / 5F, life, 1e-4F);
		assertTrue(Pulse.alive(Pulse.Kind.SPEND, life - 0.01F, KATANA));
		assertTrue(!Pulse.alive(Pulse.Kind.SPEND, life + 0.01F, KATANA));
		assertEquals(0F, Pulse.at(Pulse.Kind.SPEND, KATANA, life + 0.01F, KATANA));
	}

	@Test
	void eventsAreKindAndStartPairs() {
		float[] events = {Pulse.Kind.FEED.ordinal(), 10F, Pulse.Kind.SPEND.ordinal(), 10F, 99F, 10F, Pulse.Kind.SPEND.ordinal(), 10F};
		float two = Pulse.events(20F, 12F, events, 2, KATANA);
		float feed = Pulse.at(Pulse.Kind.FEED, 20F, 2F, KATANA), spend = Pulse.at(Pulse.Kind.SPEND, 20F, 2F, KATANA);
		assertEquals(feed + spend, two, 1e-5F);
		assertEquals(two, Pulse.events(20F, 12F, events, 3, KATANA), 1e-5F, "an unknown kind is skipped");
		assertEquals(two + spend, Pulse.events(20F, 12F, events, 4, KATANA), 1e-5F);
		assertEquals(two + spend, Pulse.events(20F, 12F, events, 99, KATANA), 1e-5F, "a count past the array stops at its end");
		assertEquals(0F, Pulse.events(20F, 12F, null, 3, KATANA));
	}
}
