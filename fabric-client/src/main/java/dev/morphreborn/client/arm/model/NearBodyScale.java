package dev.morphreborn.client.arm.model;

import dev.morphreborn.client.arm.ArmProtocol;
import java.util.Map;

/**
 * How much smaller an arm form is drawn while it is held in the hand of the body First-person Model shows in your own first-person
 * view (the rig, 2026-11-16, MorphTester1 with the mod and its default "vanilla hands: off"): that mod draws no first-person hands
 * then, so the form is drawn by the body's own item layer at its third-person size, and the body's hand is only about half a block
 * from the camera (the shoulder is 0.25 behind the eye, the arm reaches forward). A bow at full draw, 30 px long and three times as
 * thick as the vanilla one, then fills the side of the view (the arm raises both arms in front of the face), and the whip's swing
 * crosses the lens; without the mod the same form is drawn by the hand pass at {@code RecurveGeometry.FP_SCALE} of its size.
 * <p>
 * The rule is the one the hand pass keeps by construction: the form never subtends more than {@link #CAP} of its distance (the
 * tangent of the angle its reach spans seen from the camera, 0.45 is about 24 degrees, where the hand pass puts the bow), however
 * close the hand comes. Far away (the hand hanging at the side while you look down: about a block) the form keeps its size: the
 * scale eases to 1 between {@link #NEAR} and {@link #NEAR} + {@link #BAND}, so what the rig saw there is unchanged.
 * <p>
 * The reach it is given is the form's real one ({@link #reachPx}: the farthest vertex of any pose it draws, measured, with a drift test),
 * not its rest-pose box: the box said 12.5 px for the bow and 9 for the whip, the drawn bow reaches 22 and the whip 31 (fix round,
 * 2026-11-16), so the first version's cap was too weak right where the lens fills. The same rule shrinks the sheath on the body's arm
 * ({@link #SHEATH_REACH_PX}) about its hand, so the arm and its tool stay one piece.
 * <p>
 * Pure arithmetic, no game types, so the unit tests can check it.
 */
public final class NearBodyScale {
	/** The most a form's reach may be, as a share of its distance from the camera (a tangent). */
	public static final double CAP = 0.45;
	/** Inside this distance (blocks) the cap holds fully; it fades out over the next {@link #BAND}. */
	public static final double NEAR = 0.55, BAND = 0.3;
	/** The scale never goes below this (a form at the lens is already out of sight; a hair of scale keeps the maths finite). */
	public static final float MIN = 0.12F;

	/**
	 * Each form's reach from its grip (G px): the farthest any vertex of any of its draws goes from G's origin, over every tier, swing,
	 * pull, special and time the drift test (NearBodyReachTest) samples, rounded up a little. Measured 2026-11-16; the test fails when a
	 * geometry change takes a form past its entry, so it is kept honest by changing the number here.
	 */
	private static final Map<String, Float> REACH_PX = Map.ofEntries(Map.entry(ArmProtocol.KATANA, 28F), Map.entry(ArmProtocol.PICKAXE, 21F),
			Map.entry(ArmProtocol.AXE, 28F), Map.entry(ArmProtocol.SHOVEL, 23F), Map.entry(ArmProtocol.SICKLE, 30F), Map.entry(ArmProtocol.BOW, 23F),
			Map.entry(ArmProtocol.WHIP, 33F), Map.entry(ArmProtocol.SPEAR, 38F), Map.entry(ArmProtocol.MACE, 38F), Map.entry(ArmProtocol.TRIDENT, 36F),
			Map.entry(ArmProtocol.CROSSBOW, 21F), Map.entry(ArmProtocol.CLAWS, 25F));
	/**
	 * The sheath's reach from the arm's hand end ({@link #SHEATH_HAND_Y}, arm space px): the arm box and its tendrils, measured over every
	 * arm look, form, tier and swing (12.5 px), rounded up.
	 */
	public static final float SHEATH_REACH_PX = 13F;
	/** The hand end of the arm in arm space A (px along the arm; the box runs y -2..10): where the sheath is scaled about. */
	public static final float SHEATH_HAND_Y = 10F;

	/**
	 * The accessories' free parts on First-person Model's body (2026-11-24, they are drawn on the real arm there, half a block from the lens): each
	 * scales about its own pivot on the arm (arm space A, px along the arm), by its reach from there (px: the farthest any vertex of any pose,
	 * measured by NearBodyReachTest, rounded up). The grips lie on the arm and are not scaled. The shield's cap is wider than a blade's.
	 */
	public static final float SHIELD_REACH_PX = 12F, SPYGLASS_REACH_PX = 16F, FLINT_REACH_PX = 16F;
	public static final float SHIELD_PIVOT_Y = 9.8F, SPYGLASS_PIVOT_Y = 0.9F, FLINT_PIVOT_Y = 2.2F;
	public static final double SHIELD_CAP = 0.55;
	/**
	 * The lantern's free part (stalk, chain and lure) on the same body (2026-11-24): scaled about the stalk's root, which is at y 4.4 on the plain arm, 5.0 on the
	 * tendril arm and the plain hand and about 2.1 under the armour's top plate, so 4.4 stands for the distance taken; its reach from there is measured by
	 * {@code FormExtentsTest} over every look, side, slimness, level and flare (hook out to 11 px, the chain's 5 px and the cage's burst beyond it).
	 */
	public static final float LANTERN_REACH_PX = 22F, LANTERN_PIVOT_Y = 4.4F;
	public static final double LANTERN_CAP = 0.5;

	/** Accessory {@code id}'s pivot along the arm (A px), reach from it (px) and cap share; unknown ids keep the sheath's. */
	public static float accessoryPivotY(String id) {
		return ArmProtocol.SHIELD.equals(id) ? SHIELD_PIVOT_Y : ArmProtocol.SPYGLASS.equals(id) ? SPYGLASS_PIVOT_Y : ArmProtocol.FLINT.equals(id) ? FLINT_PIVOT_Y
				: ArmProtocol.LANTERN.equals(id) ? LANTERN_PIVOT_Y : SHEATH_HAND_Y;
	}

	public static float accessoryReachPx(String id) {
		return ArmProtocol.SHIELD.equals(id) ? SHIELD_REACH_PX : ArmProtocol.SPYGLASS.equals(id) ? SPYGLASS_REACH_PX : ArmProtocol.FLINT.equals(id) ? FLINT_REACH_PX
				: ArmProtocol.LANTERN.equals(id) ? LANTERN_REACH_PX : SHEATH_REACH_PX;
	}

	public static double accessoryCap(String id) {
		return ArmProtocol.SHIELD.equals(id) ? SHIELD_CAP : ArmProtocol.LANTERN.equals(id) ? LANTERN_CAP : CAP;
	}

	private NearBodyScale() {}

	/**
	 * {@code form}'s reach from its grip, G px: the table's, or {@code restExtentsPx} (the farthest corner of its rest-pose box) when it
	 * is larger or the form isn't in the table yet (a new form gets its box's reach until a measured one is entered).
	 */
	public static float reachPx(String form, float restExtentsPx) {
		Float measured = form == null ? null : REACH_PX.get(form);
		return measured == null ? restExtentsPx : Math.max(measured, restExtentsPx);
	}

	/**
	 * The scale to draw a form at, about its grip: 1 for no change.
	 *
	 * @param dist  the grip's distance from the camera, blocks
	 * @param reach the farthest the form reaches from its grip at full size, blocks (in the space it is drawn in, with the
	 *              display transform's own scale included)
	 */
	public static float of(double dist, double reach) {
		return of(dist, reach, CAP);
	}

	/** As {@link #of(double, double)} with the form's own cap (a shield may fill more of the view than a blade, as vanilla's does). */
	public static float of(double dist, double reach, double capShare) {
		if (!(dist >= 0.0) || !(reach > 1e-6) || Double.isInfinite(dist) || Double.isInfinite(reach)) return 1F;
		double cap = Math.clamp(capShare * dist / reach, MIN, 1.0);
		double t = Math.clamp((dist - NEAR) / BAND, 0.0, 1.0);
		double far = t * t * (3.0 - 2.0 * t);
		return (float) (cap + (1.0 - cap) * far);
	}
}
