package dev.morphreborn.paper.arm.rules;

/**
 * Biomass arithmetic for arm forms, in whole milli-biomass (a thousandth of one biomass). Arms bill tiny amounts many
 * times a second (0.1 a block, 0.25 a hit, a drain of 1 a minute), and doubles drift over thousands of such charges:
 * 5.0 minus ten charges of 0.1 is not 4.0 in floating point. Integer milli keeps every charge exact, so a minute of
 * drain costs exactly 1.000 and a balance never shows a phantom 0.0000001 left over.
 * <p>
 * Pure: no server types, so it is unit tested directly.
 */
public final class ArmLedger {
	private ArmLedger() {}

	/**
	 * What one spend did: {@code charged} was taken, {@code refused} could not be (all of it when an all-or-nothing
	 * spend couldn't be afforded, the shortfall when a partial one ran dry), {@code after} is the balance left, and
	 * {@code depleted} says this spend took the balance to 0, which melts the arm.
	 */
	public record Spend(long charged, long refused, long after, boolean depleted) {
		/** Nothing taken, nothing refused: Classic, creative, or a cost of 0. */
		public static Spend free(long balance) {
			return new Spend(0, 0, Math.max(0, balance), false);
		}

		/** True when the whole cost was paid (a free spend counts as paid). */
		public boolean paid() {
			return refused == 0;
		}
	}

	/**
	 * Take {@code costMilli} from {@code balanceMilli}. A cost of 0 or less takes nothing. An all-or-nothing spend
	 * ({@code partial} false: specials and bow bolts) that can't be afforded takes nothing and refuses the whole cost. A
	 * partial spend (blocks, hits, drain) takes what there is, so the action that empties the arm still goes through
	 * (decision D7: at most one action leaks past 0). A negative balance counts as 0; the balance never goes below 0.
	 */
	public static Spend spend(long balanceMilli, long costMilli, boolean partial) {
		long balance = Math.max(0, balanceMilli);
		if (costMilli <= 0) return new Spend(0, 0, balance, false);
		if (!partial && balance < costMilli) return new Spend(0, costMilli, balance, false);
		long charged = Math.min(balance, costMilli);
		long after = balance - charged;
		return new Spend(charged, costMilli - charged, after, after == 0);
	}

	/** Config decimals to milli: 0.1 is 100, 0.25 is 250. Rounded, so 0.1 typed in a file is exactly 100. */
	public static long toMilli(double biomass) {
		if (Double.isNaN(biomass)) return 0;
		return Math.round(biomass * 1000);
	}

	public static double fromMilli(long milli) {
		return milli / 1000.0;
	}
}
