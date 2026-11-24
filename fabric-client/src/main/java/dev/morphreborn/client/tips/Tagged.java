package dev.morphreborn.client.tips;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * One line of a description (a wheel label, a tooltip) with its {@link Kind}. Whatever builds a description lists its lines as these and
 * filters once, so a new line has to say which kind it is; the filter is the same for every screen. Generic over the line, so it is tested
 * without the game's text classes. Pure.
 */
public record Tagged<T>(Kind kind, T value) {
	public static <T> Tagged<T> always(T value) {
		return new Tagged<>(Kind.ALWAYS, value);
	}

	public static <T> Tagged<T> tip(T value) {
		return new Tagged<>(Kind.TIP, value);
	}

	/** What is left of {@code items} with Tooltips {@code tips} (on) or off, in order. */
	public static <T> List<T> filter(List<Tagged<T>> items, boolean tips) {
		List<T> out = new ArrayList<>(items.size());
		for (Tagged<T> item : items) if (tips || item.kind == Kind.ALWAYS) out.add(item.value);
		return out;
	}

	/**
	 * Like {@link #filter(List, boolean)}, then tidied: a blank separator ({@code blank}) never leads, trails or follows another, so taking the
	 * tips out of a tooltip leaves no gap where they were.
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
