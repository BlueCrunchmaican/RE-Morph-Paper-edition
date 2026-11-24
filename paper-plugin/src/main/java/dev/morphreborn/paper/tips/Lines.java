package dev.morphreborn.paper.tips;

import java.util.ArrayList;
import java.util.List;

/**
 * A description made of typed pieces: each is {@link Kind#ALWAYS} (a name, a level, a rate, a cost) or {@link Kind#TIP} (explanation, a key,
 * a click). Whatever builds a description says which each piece is, where the text is written, so a new description follows the rule of the
 * Tooltips setting without anyone filtering strings at the point of display; the player's setting only chooses {@link #clauses}.
 * <p>
 * Pieces are <i>clauses</i> (read one after another, joined by " · "; a menu shows each on a line of its own) and <i>continuations</i>, which
 * go on the end of the clause before them with no separator. A sentence that holds both a rate and an explanation is written as a clause and
 * continuations, so each part has its own kind: {@code always("Reap:").tipMore(" harvests and replants ripe crops within").alwaysMore(" 5 blocks")}
 * reads "Reap: harvests and replants ripe crops within 5 blocks" and, with tooltips off, "Reap: 5 blocks". A clause left with nothing is
 * left out, with its separator.
 * <p>
 * Pure (no Bukkit), so the tagging and the filter are unit tested.
 */
public final class Lines {
	public static final String SEPARATOR = " · ";
	public static final Lines EMPTY = new Lines(List.of());

	private record Part(Kind kind, String text, boolean clause) {}

	private final List<Part> parts;

	private Lines(List<Part> parts) {
		this.parts = List.copyOf(parts);
	}

	/** One ALWAYS clause. */
	public static Lines always(String text) {
		return builder().always(text).build();
	}

	/** One TIP clause. */
	public static Lines tip(String text) {
		return builder().tip(text).build();
	}

	public static Builder builder() {
		return new Builder();
	}

	/** Whether nothing was written at all (not whether the setting would leave anything). */
	public boolean isEmpty() {
		return parts.isEmpty();
	}

	/**
	 * The clauses that stay with Tooltips on ({@code tips}) or off: every clause with its kept pieces joined. A clause none of whose pieces
	 * stay is left out, and a continuation whose clause went starts its own text.
	 */
	public List<String> clauses(boolean tips) {
		List<String> out = new ArrayList<>();
		StringBuilder current = null;
		for (Part p : parts) {
			if (p.clause) {
				if (current != null && !current.isEmpty()) out.add(current.toString());
				current = new StringBuilder();
			} else if (current == null) {
				current = new StringBuilder();
			}
			if (p.kind == Kind.TIP && !tips) continue;
			current.append(current.isEmpty() ? p.text.stripLeading() : p.text);
		}
		if (current != null && !current.isEmpty()) out.add(current.toString());
		return out;
	}

	/** The clauses that stay, joined by " · ": a description in one line. */
	public String text(boolean tips) {
		return String.join(SEPARATOR, clauses(tips));
	}

	/** Whether anything stays with Tooltips {@code tips}. */
	public boolean any(boolean tips) {
		return !clauses(tips).isEmpty();
	}

	/** This description, then {@code other}'s, as one. */
	public Lines then(Lines other) {
		List<Part> all = new ArrayList<>(parts);
		all.addAll(other.parts);
		return new Lines(all);
	}

	@Override
	public String toString() {
		return text(true);
	}

	public static final class Builder {
		private final List<Part> parts = new ArrayList<>();

		/** A new clause that stays whatever the setting. Empty text adds nothing. */
		public Builder always(String text) {
			return add(Kind.ALWAYS, text, true);
		}

		/** A new clause of explanation. */
		public Builder tip(String text) {
			return add(Kind.TIP, text, true);
		}

		/** More of the last clause, kept whatever the setting; the text carries its own leading space. */
		public Builder alwaysMore(String text) {
			return add(Kind.ALWAYS, text, false);
		}

		/** More of the last clause, explanation. */
		public Builder tipMore(String text) {
			return add(Kind.TIP, text, false);
		}

		/** All of {@code other}'s clauses, as they are. */
		public Builder add(Lines other) {
			parts.addAll(other.parts);
			return this;
		}

		private Builder add(Kind kind, String text, boolean clause) {
			if (text != null && !text.isEmpty()) parts.add(new Part(kind, text, clause));
			return this;
		}

		public Lines build() {
			return new Lines(parts);
		}
	}
}
