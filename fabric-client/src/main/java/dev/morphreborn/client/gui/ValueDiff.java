package dev.morphreborn.client.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * The upgrade tooltip's "Next:" line, cut down to what the next level changes. The server writes a level's numbers as clauses joined by
 * " · " ("While it is on, every melee hit you land sets the target alight · fire lasts 4 s · 0.5 biomass per ignition · ..."), and the next
 * level's line repeats every clause that does not change, so "Now:" and "Next:" were the same long sentence twice and the tooltip ran as
 * tall as the window (Tendril Flint at 960 by 520, 2026-11-18).
 */
public final class ValueDiff {
	/** What the server puts between a level's clauses. */
	public static final String SEPARATOR = " · ";

	private ValueDiff() {}

	/**
	 * The clauses of {@code next} that {@code now} does not have, joined as the server joins them; {@code next} as it is when every clause
	 * is already in {@code now} (nothing to cut it to) or when either text is a single clause.
	 */
	public static String changed(String now, String next) {
		if (now == null || next == null || now.isEmpty() || !now.contains(SEPARATOR) && !next.contains(SEPARATOR)) return next;
		List<String> had = List.of(now.split(java.util.regex.Pattern.quote(SEPARATOR)));
		List<String> out = new ArrayList<>();
		for (String clause : next.split(java.util.regex.Pattern.quote(SEPARATOR))) {
			if (!had.contains(clause)) out.add(clause);
		}
		return out.isEmpty() ? next : String.join(SEPARATOR, out);
	}
}
