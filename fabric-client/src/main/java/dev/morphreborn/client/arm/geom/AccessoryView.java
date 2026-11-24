package dev.morphreborn.client.arm.geom;

/**
 * How the accessory about to be drawn is seen, for the one draw (2026-11-24): set by the sheath layer round an accessory's third-person draw
 * on the camera player's own body under First-person Model, where the body's hand is half a block from the lens and the accessory is seen
 * from behind. {@link #near} shrinks the accessory's free part about its pivot (the grips stay on the arm); {@link #backView} puts the
 * shield's red veins toward the camera and fades the spyglass's eye out while it zooms. The defaults (1, false) are every other view. Render
 * thread only, set and cleared inside the draw's own lambda.
 */
public final class AccessoryView {
	private static float near = 1F, sheath = 1F;
	private static boolean backView;

	private AccessoryView() {
	}

	/** Sets the view for the draw about to run; {@code set(1F, false)} restores the default. */
	public static void set(float nearScale, boolean fromBehind) {
		set(nearScale, fromBehind, 1F);
	}

	/**
	 * As above, with the scale the arm form's sheath is drawn at on that body (below 1 when its hand is near the lens). The lantern's stalk grows out of
	 * the sheath, so its root moves with it; the other accessories are not rooted on it.
	 */
	public static void set(float nearScale, boolean fromBehind, float sheathScale) {
		near = nearScale;
		backView = fromBehind;
		sheath = sheathScale;
	}

	/** The scale of the sheath the lantern's stalk grows from, 1 for none. */
	static float sheath() {
		return sheath;
	}

	/** The scale of the accessory's free part, 1 for none. */
	static float near() {
		return near;
	}

	/** Whether the accessory is on the camera player's own body, seen from behind and close. */
	static boolean backView() {
		return backView;
	}
}
