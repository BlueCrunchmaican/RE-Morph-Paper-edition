package dev.morphreborn.paper.ability;

/**
 * Which form of a combination a pair of shears takes wool from. My rule (2026-11-17, B1-12: "let either form
 * count"): a sheep that is the main body or the borrowed form can be sheared, as a cow or goat gives milk from either.
 * The main body is asked first, then the borrowed form, so a bald main sheep with a woolly borrowed one still gives wool.
 * Bukkit-free so it can be tested.
 */
final class ShearRules {
	private ShearRules() {}

	enum Pick { NONE, MAIN, LENT }

	/** What shearing needs to know of one form: whether it is a sheep, grown up (vanilla refuses a lamb) and already bald. */
	record Candidate(boolean sheep, boolean adult, boolean sheared) {
		static final Candidate NONE = new Candidate(false, false, false);

		boolean ready() {
			return sheep && adult && !sheared;
		}
	}

	static Pick pick(Candidate main, Candidate lent) {
		if (main.ready()) return Pick.MAIN;
		if (lent.ready()) return Pick.LENT;
		return Pick.NONE;
	}
}
