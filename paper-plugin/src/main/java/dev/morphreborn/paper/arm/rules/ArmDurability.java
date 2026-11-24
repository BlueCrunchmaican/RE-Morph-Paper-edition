package dev.morphreborn.paper.arm.rules;

/**
 * The arm's vanilla durability bar shows its biomass: full bar at full capacity, a sliver near empty. The item's
 * {@code max_damage} is the capacity and its {@code damage} the biomass missing, with two clamps that matter:
 * <ul>
 * <li>damage is at least 1, because the game draws the bar only while an item is damaged at all, and the bar should
 * always show (a full arm still reads as "biomass", not as an undamaged sword);</li>
 * <li>damage is at most {@code max - 1}, because an item whose damage reaches its max breaks. The arm never breaks: at
 * 0 biomass it melts on its own timeline instead.</li>
 * </ul>
 * Whole biomass only: the bar moves when {@code floor(biomass)} changes, so it isn't rewritten for every 0.1 spent
 * (each rewrite restarts the client's block-breaking progress). Pure, so it is unit tested.
 */
public final class ArmDurability {
	/** The smallest max damage: with less, "at least 1 and at most max - 1" has no room. */
	public static final int MIN_MAX_DAMAGE = 2;

	private ArmDurability() {}

	/** {@code max(2, ceil(capacity))}, capacity in milli. */
	public static int maxDamage(long capacityMilli) {
		long whole = capacityMilli <= 0 ? 0 : (capacityMilli + 999) / 1000;
		return (int) Math.clamp(whole, MIN_MAX_DAMAGE, Integer.MAX_VALUE);
	}

	/**
	 * The damage the stub of a tucked arm carries (2026-11-16: others still see the arm while it is tucked, and a
	 * modded viewer draws the biomass bar of what it is shown), or -1 when the arm shows no bar ({@code maxDamage} under the
	 * minimum, as for a view that never saw the arm item, or damage 0: a Classic arm, or one with nothing to bill, which
	 * reads as a full arm; Survival always writes damage 1 or more). Clamped as the arm's own bar is, so the stub never
	 * reads as broken.
	 */
	public static int stubDamage(int maxDamage, int damage) {
		if (maxDamage < MIN_MAX_DAMAGE || damage < 1) return -1;
		return Math.clamp(damage, 1, maxDamage - 1);
	}

	/** {@code clamp(max - floor(balance), 1, max - 1)}, balance in milli. */
	public static int damage(long balanceMilli, int maxDamage) {
		int max = Math.max(MIN_MAX_DAMAGE, maxDamage);
		long whole = Math.max(0, balanceMilli) / 1000;
		return (int) Math.clamp(max - whole, 1, max - 1);
	}
}
