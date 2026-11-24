package dev.morphreborn.paper.arm.rules;

import java.util.HashMap;
import java.util.Map;

/**
 * Draining Cut's healing, capped twice: each creature can give at most {@code perTargetCap} over the whole special (the
 * cut's tendril and its latch pulses together), and the special as a whole at most {@code totalCap}. So eight cows can't
 * heal a player from one heart to full, and one creature drained three times gives no more than its share. Healing only
 * ever comes from health actually taken: a hit that took nothing heals nothing. One ledger per special; pure, so it is
 * unit tested.
 */
public final class HealLedger {
	private final double perTargetCap, totalCap;
	private final Map<Integer, Double> given = new HashMap<>();
	private double total;

	public HealLedger(double perTargetCap, double totalCap) {
		this.perTargetCap = Math.max(0, perTargetCap);
		this.totalCap = Math.max(0, totalCap);
	}

	/** How much of {@code amount} this arrival may heal, now booked against the caps. 0 when nothing is left. */
	public double take(int targetId, double amount) {
		if (!(amount > 0)) return 0;
		double fromTarget = given.getOrDefault(targetId, 0.0);
		double granted = Math.min(amount, Math.min(perTargetCap - fromTarget, totalCap - total));
		if (granted <= 0) return 0;
		given.put(targetId, fromTarget + granted);
		total += granted;
		return granted;
	}

	/** Healing booked so far, over every creature. */
	public double total() {
		return total;
	}

	/** Healing booked from one creature so far. */
	public double from(int targetId) {
		return given.getOrDefault(targetId, 0.0);
	}

	/**
	 * What one pulse offers before the caps: {@code took × ratio × rate}, at most {@code perPulseCap} (spec 3.7: heal per
	 * pulse = min(took × 0.5 × rate, cap)). {@code rate} is the lesser of the creature's rates (1 for hostile mobs, the
	 * passive or lesser rate otherwise). Nothing taken, nothing offered.
	 */
	public static double pulse(double took, double ratio, double rate, double perPulseCap) {
		if (!(took > 0) || !(ratio > 0) || !(rate > 0) || !(perPulseCap > 0)) return 0;
		return Math.min(took * ratio * rate, perPulseCap);
	}
}
