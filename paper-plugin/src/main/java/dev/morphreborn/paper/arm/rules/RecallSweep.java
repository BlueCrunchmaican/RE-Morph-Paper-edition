package dev.morphreborn.paper.arm.rules;

import java.util.Arrays;

/**
 * Recall's sweep maths (the trident arm's F, 8.0.0-beta.2), free of Bukkit so it can be tested: how far a creature's hitbox is from the
 * path the trident snaps back along, and where along that path the creature is first within the {@code width} setting.
 * <p>
 * The test is against the creature's whole box (distance from the segment to the nearest point of the box), never its centre: a creature
 * whose body is within {@code width} of the path is on it, whichever way its centre lies (owner's report from the rig, 2026-11-16: a
 * creature 0.8 off was missed at width 0.8 while 0.5 off was hit). The distance is the true one (round, the same in every direction), where
 * growing the box by {@code width} would square the path's corners and take creatures up to 1.7 times the width away on a diagonal.
 */
public final class RecallSweep {
	private RecallSweep() {}

	/** How closely the entry point is found (the bisection's steps; far finer than anything a tick can show). */
	private static final int BISECT_STEPS = 60;

	/**
	 * The shortest distance between the segment {@code a} to {@code b} and the box ({@code min}, {@code max}): 0 when they touch or overlap.
	 * Exact: the squared distance to a box is convex along a line and quadratic between the points where the segment crosses a face's plane,
	 * so each stretch is minimised in closed form.
	 */
	public static double distance(double[] a, double[] b, double[] min, double[] max) {
		return Math.sqrt(closest(a, b, min, max)[1]);
	}

	/**
	 * The segment's nearest approach to the box: {t, distance}, t from 0 at {@code a} to 1 at {@code b} (the earliest, when several points tie).
	 * It orders creatures that {@link #entry} meets at the same moment (all within the width of the trident's own spot, so all at 0): the
	 * one the path comes nearest to first, then the one it passes closest to, so a crowd is cut by the path and not by the order the game
	 * lists entities in (a review finding, 2026-11-16).
	 */
	public static double[] nearest(double[] a, double[] b, double[] min, double[] max) {
		double[] best = closest(a, b, min, max);
		return new double[] {best[0], Math.sqrt(best[1])};
	}

	/**
	 * Where along the segment from {@code a} (0) to {@code b} (1) the box is first within {@code width} of it; -1 when it never is. A box the
	 * segment starts within {@code width} of is met at 0. Recall hits what it meets in this order, so the damage runs from the trident's end
	 * toward the hand.
	 */
	public static double entry(double[] a, double[] b, double[] min, double[] max, double width) {
		double w = Math.max(0, width), w2 = w * w;
		if (dist2(a, b, min, max, 0) <= w2) return 0;
		double[] best = closest(a, b, min, max); // {t, squared distance} of the nearest approach
		if (best[1] > w2) return -1;
		// the squared distance only falls from 0 to the nearest approach (convex), so the first moment it is within the width can be bisected
		double lo = 0, hi = best[0];
		for (int i = 0; i < BISECT_STEPS; i++) {
			double mid = (lo + hi) / 2;
			if (dist2(a, b, min, max, mid) <= w2) hi = mid;
			else lo = mid;
		}
		return hi;
	}

	/** {t, squared distance} at the segment's nearest approach to the box (the earliest, when several points tie). */
	private static double[] closest(double[] a, double[] b, double[] min, double[] max) {
		double[] d = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
		// the parameters where the segment crosses a face's plane: between two of them each axis' gap to the box is a straight line in t
		double[] cuts = new double[8];
		int n = 0;
		cuts[n++] = 0;
		for (int i = 0; i < 3; i++) {
			if (Math.abs(d[i]) < 1e-12) continue;
			for (double plane : new double[] {min[i], max[i]}) {
				double t = (plane - a[i]) / d[i];
				if (t > 0 && t < 1) cuts[n++] = t;
			}
		}
		cuts[n++] = 1;
		Arrays.sort(cuts, 0, n);
		double bestT = 0, bestD2 = Double.POSITIVE_INFINITY;
		for (int k = 0; k + 1 < n; k++) {
			double t0 = cuts[k], t1 = cuts[k + 1];
			double mid = (t0 + t1) / 2;
			// on this stretch the gap on each axis is c + s * t (or nothing, when the segment is inside the slab there)
			double sumCS = 0, sumSS = 0;
			for (int i = 0; i < 3; i++) {
				double p = a[i] + mid * d[i];
				if (p < min[i]) {
					sumCS += (min[i] - a[i]) * -d[i];
					sumSS += d[i] * d[i];
				} else if (p > max[i]) {
					sumCS += (a[i] - max[i]) * d[i];
					sumSS += d[i] * d[i];
				}
			}
			double t = sumSS > 1e-18 ? Math.min(t1, Math.max(t0, -sumCS / sumSS)) : t0;
			double d2 = dist2(a, b, min, max, t);
			if (d2 < bestD2 - 1e-12) {
				bestD2 = d2;
				bestT = t;
			}
		}
		return new double[] {bestT, bestD2};
	}

	/** The squared distance from the point at {@code t} on the segment to the box. */
	private static double dist2(double[] a, double[] b, double[] min, double[] max, double t) {
		double sum = 0;
		for (int i = 0; i < 3; i++) {
			double p = a[i] + t * (b[i] - a[i]);
			double gap = p < min[i] ? min[i] - p : p > max[i] ? p - max[i] : 0;
			sum += gap * gap;
		}
		return sum;
	}
}
