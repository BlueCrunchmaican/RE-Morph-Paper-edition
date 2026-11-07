package dev.morphreborn.paper.data;

import java.util.HashMap;
import java.util.Map;

/**
 * A player's Survival Mode progress, saved in their player file in both modes, so switching the server back to
 * Classic and later to Survival again never loses it.
 */
public final class SurvivalData {
	/** Has had the unlock effects together ("A Mutation Complication"). */
	public boolean unlocked;
	public long unlockedAt;
	public double biomass;
	/** Upgrade id -> level bought. Upgrades not listed are at their start level from survival.yml. */
	public Map<String, Integer> upgrades = new HashMap<>();
	/**
	 * Which upgrade tree the levels are for: 0 before 6.6.0, {@link #LAYOUT} since. 6.6.0 changed what some levels
	 * mean (reach, absorbable mass, morph speeds); older levels are converted once, the first time they're read.
	 */
	public int layout;

	public static final int LAYOUT = 2;
}
