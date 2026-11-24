package dev.morphreborn.paper.arm.rules;

import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Commands that act on the held item get a plain hand first. Without this, EssentialsX's /sell hand would pay out for
 * the arm, /hat would put it on the player's head, /repair and /rename would edit it. The arm retracts (at once, the
 * item it replaced back in the hand) before such a command runs, and the command then sees the real item.
 * <p>
 * Three modes (abilities.yml arms.command-guard.mode): {@code listed} retracts for the listed commands only, so
 * everyday ones (/home, /tpa, /msg, TARDIS's) keep the arm; {@code all} retracts for every command except the
 * {@code keep} list; {@code off} never. A command counts by the label typed and by the name it resolves to, both
 * without a namespace and case-insensitive: {@code /Essentials:SELL} and an alias whose command is {@code sell} both
 * match {@code sell}. Pure, so it is unit tested.
 */
public final class CommandGuard {
	private CommandGuard() {}

	public enum Mode {
		LISTED, ALL, OFF;

		/** listed, all or off (YAML reads a bare off as false); anything else is listed, the default. */
		public static Mode parse(@Nullable Object value) {
			if (value instanceof Boolean b) return b ? ALL : OFF;
			if (value == null) return LISTED;
			return switch (value.toString().trim().toLowerCase(Locale.ROOT)) {
				case "all" -> ALL;
				case "off", "false", "no", "none" -> OFF;
				default -> LISTED;
			};
		}
	}

	/**
	 * Whether the arm retracts before this command.
	 *
	 * @param typedLabel the command's label as typed (a leading slash and anything after the first space are ignored)
	 * @param resolvedName the name of the command it resolved to, null if unknown
	 * @param listed the commands that retract in {@link Mode#LISTED}
	 * @param keep the commands that don't in {@link Mode#ALL}
	 */
	public static boolean retractFirst(String typedLabel, @Nullable String resolvedName, Mode mode, Set<String> listed, Set<String> keep) {
		String label = normalise(typedLabel);
		String resolved = resolvedName == null ? "" : normalise(resolvedName);
		return switch (mode) {
			case OFF -> false;
			case LISTED -> contains(listed, label) || contains(listed, resolved);
			case ALL -> !label.isEmpty() && !contains(keep, label) && !contains(keep, resolved);
		};
	}

	/** {@code /Essentials:Sell hand} -> {@code sell}: no slash, no namespace, no arguments, lower case. */
	public static String normalise(String command) {
		String s = command.trim();
		while (s.startsWith("/")) s = s.substring(1);
		int space = indexOfWhitespace(s);
		if (space >= 0) s = s.substring(0, space);
		int colon = s.lastIndexOf(':');
		if (colon >= 0) s = s.substring(colon + 1);
		return s.toLowerCase(Locale.ROOT);
	}

	private static int indexOfWhitespace(String s) {
		for (int i = 0; i < s.length(); i++) {
			if (Character.isWhitespace(s.charAt(i))) return i;
		}
		return -1;
	}

	/** The lists come from config files, so compare each entry normalised too (an admin may write "/Sell"). */
	private static boolean contains(Set<String> names, String name) {
		if (name.isEmpty()) return false;
		if (names.contains(name)) return true;
		for (String n : names) {
			if (normalise(n).equals(name)) return true;
		}
		return false;
	}
}
