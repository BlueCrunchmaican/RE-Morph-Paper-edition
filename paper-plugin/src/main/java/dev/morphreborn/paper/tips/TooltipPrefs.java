package dev.morphreborn.paper.tips;

import dev.morphreborn.paper.SemVer;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The rules of the Tooltips setting that need no server (decided 2026-11-17): it is one per-player choice, ON by default, that shows or
 * hides the explanation lines of every description (see {@link Kind}). A player with the client mod sets it in the mod's settings screen
 * and the client tells the server (C_TOOLTIPS); a player without the mod sets it from the upgrades menu or with {@code /morph tooltips on|off}.
 * Both end in the same stored flag, so server-built text follows it either way.
 */
public final class TooltipPrefs {
	/** The first client mod version that has the setting and sends C_TOOLTIPS. An older client never does, so its player stays on the default. */
	public static final String SINCE = "8.0.0-beta.2";
	/** The words the command takes after "tooltips". */
	public static final List<String> WORDS = List.of("on", "off");

	private TooltipPrefs() {}

	/** True when a client mod of this version (what C_HELLO said; null or "?" when it didn't) has the setting and sends it. */
	public static boolean clientKnows(@Nullable String clientVersion) {
		return clientVersion != null && !clientVersion.equals("?") && SemVer.compare(clientVersion, SINCE) >= 0;
	}

	/** What the command's word means: on, off, or null for a word that is neither (null/"" reads the state back). */
	public static @Nullable Boolean parse(@Nullable String word) {
		if (word == null) return null;
		return switch (word.trim().toLowerCase(Locale.ROOT)) {
			case "on", "true", "yes", "show" -> Boolean.TRUE;
			case "off", "false", "no", "hide" -> Boolean.FALSE;
			default -> null;
		};
	}

	/** The setting for a stored flag (the file keeps "off" so a file from before the setting reads as on). */
	public static boolean on(boolean storedOff) {
		return !storedOff;
	}
}
