package dev.morphreborn.paper.arm.rules;

/**
 * The Tendril Spyglass's small rules (my design, 2026-11-16, 8.0.0-beta.2): the S_ARM_FX numbers, how far a look may reach
 * when the server only has the owner's view of the world loaded, and the entity flags byte with the glow bit. Pure, so each is unit
 * tested without a server.
 */
public final class SpyglassRules {
	/** The spyglass's place in the accessory ring (ArmProtocol.ACCESSORIES: lantern 0, shield 1, spyglass 2, flint 3), in bits 8-11 of an FX_ACCESSORY arg. */
	public static final int PLACE = 2;
	/** FX_ACCESSORY move: a creature is marked (or still is: the message is repeated while it is looked at). */
	public static final int FX_MARK = 1;
	/** FX_ACCESSORY move: marks ended. */
	public static final int FX_UNMARK = 2;
	/** Ticks between two MARK messages for a mark that is being kept up. */
	public static final int RESEND_TICKS = 20;

	/**
	 * Ticks between two MARK messages for a mark whose glance lasts {@code life} ticks, looked for every {@code scanTicks}: {@link #RESEND_TICKS}
	 * at most, half a life at most, and early enough that the scan after the interval (the check only happens at a scan, up to
	 * {@code scanTicks - 1} late) still lands before the client's timer ends. A short {@code mark-seconds} or a long {@code scan-ticks}
	 * would otherwise let the client's HUD line run out just before the next message (2026-11-16: the second review's blink). At least 1.
	 */
	public static int resendTicks(int life, int scanTicks) {
		return Math.max(1, Math.min(RESEND_TICKS, Math.min(life / 2, life - Math.max(1, scanTicks))));
	}

	/** The entity flags byte's bits (metadata index 0), as vanilla's Entity. */
	public static final int FLAG_FIRE = 0x01, FLAG_SNEAKING = 0x02, FLAG_SPRINTING = 0x08, FLAG_SWIMMING = 0x10, FLAG_INVISIBLE = 0x20, FLAG_GLOWING = 0x40, FLAG_GLIDING = 0x80;

	private SpyglassRules() {}

	/** S_ARM_FX kind FX_ACCESSORY {@code arg} for a spyglass move: the place in bits 8-11, the move in bits 0-7 (as ShieldRules.fxArg does for the shield). */
	public static int fxArg(int move) {
		return (PLACE << 8) | (move & 0xFF);
	}

	/** The move in an FX_ACCESSORY {@code arg}, or 0 when the arg is not the spyglass's. */
	public static int fxMove(int arg) {
		return (arg >> 8 & 0xF) == PLACE ? arg & 0xFF : 0;
	}

	/**
	 * How far a look is traced: the level's range, but no further than the chunks the owner's game has been sent ({@code viewChunks}), so
	 * tracing never reaches ground the server has not loaded. At least one chunk.
	 */
	public static double reach(double range, int viewChunks) {
		return Math.min(Math.max(0, range), Math.max(1, viewChunks) * 16.0);
	}

	/** Whether a creature at {@code distanceSquared} from the owner is still in range, with a few blocks of give so a mark at the edge doesn't flicker. */
	public static boolean inRange(double distanceSquared, double range) {
		double r = range + 4;
		return distanceSquared <= r * r;
	}

	/**
	 * The entity flags byte as the game sends it, from the entity's state ({@code glowing} is the entity's own glow, not the mark's).
	 * Vanilla's other bits are unused here.
	 */
	public static byte flags(boolean fire, boolean sneaking, boolean sprinting, boolean swimming, boolean invisible, boolean glowing, boolean gliding) {
		int f = 0;
		if (fire) f |= FLAG_FIRE;
		if (sneaking) f |= FLAG_SNEAKING;
		if (sprinting) f |= FLAG_SPRINTING;
		if (swimming) f |= FLAG_SWIMMING;
		if (invisible) f |= FLAG_INVISIBLE;
		if (glowing) f |= FLAG_GLOWING;
		if (gliding) f |= FLAG_GLIDING;
		return (byte) f;
	}

	/** {@code flags} with the glow bit set, whatever it was. */
	public static byte withGlow(byte flags) {
		return (byte) (flags | FLAG_GLOWING);
	}

	/**
	 * The flags byte a mark put out gives the owner back: the last byte the game sent ({@code heard}, which never carries the glow bit, as
	 * the rewriter only records bytes without it), with the bit set again only when the creature really glows (a spectral arrow, the
	 * effect, a command: 2026-11-16, the second review: the byte recorded before it started glowing used to take that glow away).
	 */
	public static byte restored(byte heard, boolean realGlow) {
		int f = heard & ~FLAG_GLOWING;
		return (byte) (realGlow ? f | FLAG_GLOWING : f);
	}
}
