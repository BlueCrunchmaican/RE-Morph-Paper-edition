package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.RecallSweep;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Recall's sweep maths (trident arm, 8.0.0-beta.2): the creature's box, not its centre, is what is within the path's width (rig
 * check, 2026-11-16: a creature 0.8 off the path at width 0.8 was not hit, one 0.5 off was).
 */
class RecallSweepTest {
	private static final double[] TRIDENT = {30, 0, 0}, HAND = {0, 0, 0};

	/** A creature box {@code w} wide and {@code h} tall, its centre at x, its feet at y, {@code z} to the side of the line. */
	private static double[][] creature(double x, double y, double z, double w, double h) {
		return new double[][] {{x - w / 2, y, z - w / 2}, {x + w / 2, y + h, z + w / 2}};
	}

	private static double entry(double[][] box, double width) {
		return RecallSweep.entry(TRIDENT, HAND, box[0], box[1], width);
	}

	@Test
	void aCreatureWhoseCentreIsTheWidthOffIsHitBecauseItsBodyIsCloser() {
		// the rig's case: width 0.8, a 0.6-wide creature (a zombie, a villager) whose centre is 0.8 off the line, and one 0.5 off
		assertTrue(entry(creature(15, 0, 0.8, 0.6, 1.95), 0.8) >= 0, "0.8 off the centre: its box is only 0.5 from the line");
		assertTrue(entry(creature(15, 0, 0.5, 0.6, 1.95), 0.8) >= 0, "0.5 off");
		assertTrue(entry(creature(15, 0, -0.8, 0.6, 1.95), 0.8) >= 0, "and to the other side");
		// a body wider than the width is hit from further: a 0.9 pig, centre 1.2 off, box 0.75 from the line
		assertTrue(entry(creature(15, 0, 1.2, 0.9, 0.9), 0.8) >= 0);
	}

	@Test
	void whatIsBeyondTheWidthIsMissed() {
		assertEquals(-1, entry(creature(15, 0, 1.3, 0.6, 1.95), 0.8), "box face 1.0 from the line");
		assertEquals(-1, entry(creature(15, 0, 3, 0.6, 1.8), 0.8), "well to the side");
		// the rounding: a box whose near corner is 0.7 over and 0.7 up is 0.99 away, outside 0.8 (a grown square box would take it)
		assertEquals(-1, entry(new double[][] {{14.5, 0.7, 0.7}, {15.5, 1.7, 1.7}}, 0.8));
		assertTrue(entry(new double[][] {{14.5, 0.5, 0.5}, {15.5, 1.5, 1.5}}, 0.8) >= 0, "0.71 away at the corner is inside");
	}

	@Test
	void theWidthIsMeasuredToTheBoxExactly() {
		// the face is 0.5 from the line: width 0.5 just takes it, 0.49 just doesn't
		double[][] box = creature(15, 0, 0.8, 0.6, 1.95);
		assertTrue(entry(box, 0.5) >= 0);
		assertEquals(-1, entry(box, 0.49));
		assertEquals(0.5, RecallSweep.distance(TRIDENT, HAND, box[0], box[1]), 1e-9);
		// a corner: 3-4-5 from the line (z 3, y 4 beyond the box's near edges)
		assertEquals(5, RecallSweep.distance(TRIDENT, HAND, new double[] {10, 4, 3}, new double[] {11, 5, 4}), 1e-9);
	}

	@Test
	void aBoxTheLineRunsThroughIsAtZero() {
		double[][] box = creature(15, -1, 0, 0.6, 1.95);
		assertEquals(0, RecallSweep.distance(TRIDENT, HAND, box[0], box[1]), 1e-12);
		assertTrue(entry(box, 0.8) >= 0);
		assertEquals((30 - 15.3) / 30, entry(box, 0), 1e-9, "met at its face");
	}

	@Test
	void whatIsFirstMetFromTheTridentIsFirstInTheOrder() {
		double near = entry(creature(25, 0, 0.0, 0.6, 1.8), 0.8), far = entry(creature(5, 0, 0.5, 0.6, 1.8), 0.8);
		assertTrue(near >= 0 && far >= 0);
		assertTrue(near < far, "the one nearer the trident's end is met first");
		// met where the path comes within the width of the box: 0.8 ahead of the face for a box right on the line
		assertEquals((30 - (25 + 0.3 + 0.8)) / 30, near, 1e-9);
	}

	@Test
	void creaturesMetTogetherAtTheStartAreToldApartByTheNearestApproach() {
		// a crowd around the trident's own spot: all met at 0, but the line (30 -> 0) comes nearest to each at a different point
		double[][] ahead = creature(29, 0, 0.0, 0.6, 1.8), beside = creature(30, 0, 0.7, 0.6, 1.8), behind = creature(31, 0, 0.0, 0.6, 1.8);
		assertEquals(0, entry(ahead, 0.8), 1e-12);
		assertEquals(0, entry(beside, 0.8), 1e-12);
		assertEquals(0, entry(behind, 0.8), 1e-12);
		double[] nAhead = RecallSweep.nearest(TRIDENT, HAND, ahead[0], ahead[1]);
		double[] nBeside = RecallSweep.nearest(TRIDENT, HAND, beside[0], beside[1]);
		double[] nBehind = RecallSweep.nearest(TRIDENT, HAND, behind[0], behind[1]);
		assertEquals(0, nAhead[1], 1e-12, "the line runs through the one ahead");
		assertEquals(0, nBehind[0], 1e-12, "the one behind is nearest at the start");
		assertTrue(nBehind[1] > 0, "and the line never touches it");
		assertEquals((30 - 29.3) / 30, nAhead[0], 1e-9, "the one ahead is first touched a little along");
		assertEquals(0.4, nBeside[1], 1e-9, "the one beside: its face is 0.4 from the line");
		// the sort is (entry, how near the line passes, where): the one the line runs through ranks above the one beside it and the one behind
		assertTrue(nAhead[1] < nBeside[1] && nBeside[1] < nBehind[1], "the line's own creature comes first when the crowd is cut");
	}

	@Test
	void theEndsAreRoundAndTheSegmentStopsAtThem() {
		assertEquals(-1, entry(creature(-1.5, 0, 0, 0.6, 1.8), 0.8), "behind the hand: 1.2 past it");
		assertTrue(entry(creature(-1.0, 0, 0, 0.6, 1.8), 0.8) >= 0, "just behind the hand, within the width of its end");
		assertEquals(-1, entry(creature(32, 0, 0, 0.6, 1.8), 0.8), "past the trident by more than the width");
		assertEquals(0, entry(creature(30.5, 0, 0, 0.6, 1.8), 0.8), 1e-12, "the trident is next to it already: met at 0");
		assertEquals(0, entry(creature(30, 0, 0, 0.6, 1.8), 0), 1e-12, "inside it: met at 0");
	}

	@Test
	void aVerticalPathAndAZeroLengthPathWork() {
		// the trident straight above the hand (a parallel to two axes)
		double[] up = {0, 10, 0}, down = {0, 0, 0};
		assertTrue(RecallSweep.entry(up, down, new double[] {0.4, 4, -0.3}, new double[] {1.0, 5, 0.3}, 0.5) > 0, "0.4 to the side");
		assertEquals(-1, RecallSweep.entry(up, down, new double[] {0.6, 4, -0.3}, new double[] {1.2, 5, 0.3}, 0.5));
		// a trident that is already at the hand
		assertEquals(0, RecallSweep.entry(down, down, new double[] {0.3, -1, -1}, new double[] {0.9, 1, 1}, 0.5), 1e-12);
		assertEquals(-1, RecallSweep.entry(down, down, new double[] {2, -1, -1}, new double[] {3, 1, 1}, 0.5));
	}

	@Test
	void aSlantedPathAgreesWithSamplingTheSegment() {
		// the exact distance is never beaten by a fine sample of the segment, and never more than a sample step from it
		Random rnd = new Random(8);
		for (int n = 0; n < 400; n++) {
			double[] a = point(rnd, 20), b = point(rnd, 20), lo = point(rnd, 15);
			double[] hi = {lo[0] + 0.3 + rnd.nextDouble() * 1.5, lo[1] + 0.3 + rnd.nextDouble() * 2, lo[2] + 0.3 + rnd.nextDouble() * 1.5};
			double exact = RecallSweep.distance(a, b, lo, hi), sampled = Double.POSITIVE_INFINITY;
			int steps = 4000;
			for (int i = 0; i <= steps; i++) {
				double t = (double) i / steps, s = 0;
				for (int k = 0; k < 3; k++) {
					double p = a[k] + t * (b[k] - a[k]);
					double gap = p < lo[k] ? lo[k] - p : p > hi[k] ? p - hi[k] : 0;
					s += gap * gap;
				}
				sampled = Math.min(sampled, Math.sqrt(s));
			}
			double step = Math.sqrt(sq(b[0] - a[0]) + sq(b[1] - a[1]) + sq(b[2] - a[2])) / steps;
			assertTrue(exact <= sampled + 1e-9, "exact is the least: " + exact + " vs " + sampled);
			assertTrue(sampled - exact <= step + 1e-9, "and the sample is within a step of it: " + exact + " vs " + sampled);
			// the entry point is where the box is first within the width: the distance there is the width, and earlier it was more
			double width = 0.4 + rnd.nextDouble();
			double t = RecallSweep.entry(a, b, lo, hi, width);
			if (exact > width) {
				assertEquals(-1, t);
			} else if (t > 0) {
				assertEquals(width, distanceAt(a, b, lo, hi, t), 1e-6, "at the entry point the box is the width away");
				assertTrue(distanceAt(a, b, lo, hi, Math.max(0, t - 1e-3)) >= width - 1e-6, "just before it, no closer");
			}
		}
	}

	private static double[] point(Random rnd, double spread) {
		return new double[] {(rnd.nextDouble() - 0.5) * spread, (rnd.nextDouble() - 0.5) * spread, (rnd.nextDouble() - 0.5) * spread};
	}

	private static double distanceAt(double[] a, double[] b, double[] lo, double[] hi, double t) {
		double[] p = {a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]), a[2] + t * (b[2] - a[2])};
		return RecallSweep.distance(p, p, lo, hi);
	}

	private static double sq(double v) {
		return v * v;
	}
}
