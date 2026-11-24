package dev.morphreborn.paper.net;

import java.util.regex.Pattern;

/**
 * What the mod version in C_HELLO may look like (lying-client audit, 2026-11-16). The string is the client's own word and is used three
 * ways: compared to the arm and passive protocol versions (SemVer), put into a MiniMessage template that is sent back to the player
 * (the "your mod is older" message), and written to the console. Free text there would let a client smuggle MiniMessage tags (a click
 * command, a selector or nbt component the server resolves) into that message, put line breaks into the log, and fill the "newer than this
 * server" set with one entry per made-up string. So anything that is not a plain version counts as no version ("?", what a client from before
 * the field sends), and the client then gets only what a client of unknown age gets.
 */
public final class ClientVersion {
	/** The longest version kept. The real ones are like {@code 8.0.0-beta.2+26.3}: 20 characters. */
	public static final int MAX_LENGTH = 32;
	/** "No version": a client that did not say, or whose version is not a plain one. SemVer reads it as 0.0.0 and PassiveProtocol.knows refuses it. */
	public static final String UNKNOWN = "?";
	private static final Pattern PLAIN = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._+-]{0," + (MAX_LENGTH - 1) + "}");

	private ClientVersion() {}

	/** {@code raw} when it is a plain version (letters, digits and . _ + - only, 1 to {@value #MAX_LENGTH} characters), otherwise {@link #UNKNOWN}. */
	public static String sanitize(String raw) {
		return raw != null && PLAIN.matcher(raw).matches() ? raw : UNKNOWN;
	}
}
