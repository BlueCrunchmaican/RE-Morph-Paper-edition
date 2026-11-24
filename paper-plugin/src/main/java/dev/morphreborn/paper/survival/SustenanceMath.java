package dev.morphreborn.paper.survival;

/**
 * What Sustenance restores in one run and what that costs (2026-11-16: hunger drains only while it is filling your
 * hunger). Pure, so it is unit tested. Per run (every 10 ticks) it fills at most one food point and at most one point of
 * saturation, each billed as it is given, and only as many as the balance covers: a full stomach costs nothing, an empty
 * balance restores nothing. The old passive also held saturation up to 5 for free (a fast-heal perk on top of the food);
 * that is billed now, at a quarter of a food point's price for each point (the plan's suggestion, tunable in survival.yml).
 */
public final class SustenanceMath {
	/** The most food points restored in one run. */
	public static final int FOOD_PER_RUN = 1;
	/** The most saturation restored in one run. */
	public static final double SATURATION_PER_RUN = 1.0;
	public static final int MAX_FOOD = 20;

	/** What to give now: whole food points, saturation, and the milli they cost together. */
	public record Plan(int food, double saturation, long costMilli) {
		public boolean any() {
			return food > 0 || saturation > 0;
		}
	}

	private SustenanceMath() {}

	/**
	 * @param food the player's food level now (0 to 20)
	 * @param saturation their saturation now
	 * @param saturationFloor the saturation it keeps them at (0 for none)
	 * @param balanceMilli the biomass they can spend
	 * @param foodMilli price of one food point
	 * @param saturationMilli price of one point of saturation
	 */
	public static Plan plan(int food, double saturation, double saturationFloor, long balanceMilli, long foodMilli, long saturationMilli) {
		long balance = Math.max(0, balanceMilli);
		int points = Math.min(FOOD_PER_RUN, Math.max(0, MAX_FOOD - food));
		if (foodMilli > 0) points = (int) Math.min(points, balance / foodMilli);
		long cost = points * foodMilli;
		balance -= cost;
		// only in a run that also fills a food point (a full stomach costs nothing, whatever the saturation); and vanilla's saturation
		// can't exceed the food level, so it is judged against the level after this run's point
		double room = points > 0 ? Math.max(0, Math.min(saturationFloor, food + points) - saturation) : 0;
		double sat = Math.min(SATURATION_PER_RUN, room);
		if (saturationMilli > 0) sat = Math.min(sat, balance / (double) saturationMilli);
		long satCost = Math.round(sat * saturationMilli);
		if (satCost > balance) { // rounding up must not overspend: give what is paid for
			sat = balance / (double) saturationMilli;
			satCost = Math.min(balance, Math.round(sat * saturationMilli));
		}
		if (sat < 1e-6) {
			sat = 0;
			satCost = 0;
		}
		return new Plan(points, sat, cost + satCost);
	}
}
