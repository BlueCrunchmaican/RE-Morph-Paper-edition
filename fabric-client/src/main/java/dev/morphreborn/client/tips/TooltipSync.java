package dev.morphreborn.client.tips;

import dev.morphreborn.client.SemVer;
import org.jspecify.annotations.Nullable;

/**
 * Tells the server this player's Tooltips setting (C_TOOLTIPS, docs/PROTOCOL.md "Tooltips setting") so the descriptions it builds (the upgrade
 * tree's text) follow it. Sent once after the handshake and whenever the setting differs from what the server was last told; never to a server
 * older than {@link #SINCE}, which would not know the packet. No Minecraft classes, so it is unit tested.
 */
public final class TooltipSync {
	/** The first version on both sides that has the setting (the plugin's TooltipPrefs.SINCE). */
	public static final String SINCE = "8.0.0-beta.2";

	private @Nullable Boolean sent;

	/** True when a server plugin of this version ("" when it didn't say: servers before 6.6.0) takes C_TOOLTIPS. */
	public static boolean serverKnows(String serverVersion) {
		return !serverVersion.isEmpty() && SemVer.compare(serverVersion, SINCE) >= 0;
	}

	/**
	 * The value to send now, or null for nothing: the player's setting {@code want} when this connection is live ({@code active}) on a server
	 * that knows it and the server has not been told that value yet. Calling it counts the value as sent.
	 */
	public @Nullable Boolean due(boolean want, boolean active, String serverVersion) {
		if (!active || !serverKnows(serverVersion)) return null;
		if (sent != null && sent == want) return null;
		sent = want;
		return want;
	}

	/** A new connection: the server knows nothing of the setting. */
	public void reset() {
		sent = null;
	}
}
