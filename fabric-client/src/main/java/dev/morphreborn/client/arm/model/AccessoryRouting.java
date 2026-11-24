package dev.morphreborn.client.arm.model;

/**
 * Which pass draws an accessory (2026-11-24), as pure rules so they can be tested: the lantern, the shield, the spyglass and the flint and steel are
 * drawn on the real arm of whatever body the world pass draws, and by the hand pass in vanilla first person, where no body is drawn. With
 * First-person Model the camera player's own body is drawn in the world pass, so they go there, as the tools and the tendril arm do (the shield, spyglass
 * and striker used to stay in the hand pass's overlay, which is what showed them wrongly: floating at fixed screen spots while the body's own off arm rose;
 * the lantern was an overlay there too until the same day, when I moved it onto the body).
 */
public final class AccessoryRouting {
	private AccessoryRouting() {
	}

	/**
	 * Whether the sheath layer draws accessories on this body now. {@code ownFirstPerson}: it is the camera player's own body in the world pass
	 * with a first-person camera. {@code firstPersonModelBody}: and First-person Model (not a stray draw) is the one drawing it.
	 */
	public static boolean onBody(boolean ownFirstPerson, boolean firstPersonModelBody) {
		return !ownFirstPerson || firstPersonModelBody;
	}

	/**
	 * Whether the hand pass still draws the lantern's lure: it is lit (or drawing back in) and the world pass did not draw it on the body this frame.
	 */
	public static boolean lureInOverlay(boolean lanternLive, boolean lureDrawnOnBody) {
		return lanternLive && !lureDrawnOnBody;
	}

	/**
	 * Whether the hand pass has anything to draw: the lantern's lure, or an accessory that is on (or drawing back in) and that the world pass did
	 * not draw on the body this frame. {@code liveMask} and {@code onBodyMask} have bit i for accessory i of {@code ArmProtocol.ACCESSORIES}; the lantern's own bit
	 * is ignored (it has its own flag: {@link #lureInOverlay}).
	 */
	public static boolean handPassNeeded(boolean lureInOverlay, int liveMask, int onBodyMask) {
		return lureInOverlay || (liveMask & ~onBodyMask & ~1) != 0;
	}

	/**
	 * Whether the arm the accessory sits on is there to carry it: drawn, or replaced by the tendril arm on the main side, and not hidden by
	 * First-person Model's "vanilla hands" modes (where the hand pass keeps drawing it in the overlay).
	 */
	public static boolean armCarries(boolean armVisible, boolean replacesArm, boolean mainSide, boolean modHidesArm) {
		return (armVisible || replacesArm && mainSide) && !modHidesArm;
	}
}
