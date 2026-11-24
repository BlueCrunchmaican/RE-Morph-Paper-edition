package dev.morphreborn.paper.tips;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * One line of a multi-line description (a chest menu's lore, say) with its {@link Kind}. Lore is built as a list of these and filtered
 * once, so a new line has to say which kind it is. Pure, so the filter is unit tested without a server.
 */
public record Tagged<T>(Kind kind, T value) {
	public static <T> Tagged<T> always(T value) {
		return new Tagged<>(Kind.ALWAYS, value);
	}

	public static <T> Tagged<T> tip(T value) {
		return new Tagged<>(Kind.TIP, value);
	}

	/** What is left of {@code items} with Tooltips {@code tips} (on) or off, in order. Blank lines are not tagged: see the other overload. */
	public static <T> List<T> filter(List<Tagged<T>> items, boolean tips) {
		List<T> out = new ArrayList<>(items.size());
		for (Tagged<T> item : items) if (tips || item.kind == Kind.ALWAYS) out.add(item.value);
		return out;
	}

	/**
	 * Like {@link #filter(List, boolean)}, then tidied: a blank separator line ({@code blank}) never leads, trails or follows another, so
	 * taking the tips out of a description does not leave a gap where they were.
	 */
	public static <T> List<T> filter(List<Tagged<T>> items, boolean tips, Predicate<T> blank) {
		List<T> out = new ArrayList<>(items.size());
		for (T value : filter(items, tips)) {
			if (blank.test(value) && (out.isEmpty() || blank.test(out.getLast()))) continue;
			out.add(value);
		}
		while (!out.isEmpty() && blank.test(out.getLast())) out.removeLast();
		return out;
	}
}
