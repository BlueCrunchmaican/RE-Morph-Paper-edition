package dev.morphreborn.client.arm.model;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.geom.ArmDraw;
import dev.morphreborn.client.arm.geom.FlintRenderer;
import dev.morphreborn.client.arm.geom.FpClearance;
import dev.morphreborn.client.arm.geom.FpLayout;
import dev.morphreborn.client.arm.geom.LureGeometry;
import dev.morphreborn.client.arm.geom.ShieldRenderer;
import dev.morphreborn.client.arm.geom.SpyglassRenderer;
import org.joml.Matrix4fc;

/**
 * Lays the accessories out in vanilla's first-person overlay without anything passing through anything (2026-11-24), once a frame in the hand pass
 * before they are submitted. The homes are the base: each accessory moves only when something is in its way, only its free end, and never hides.
 * Who yields: the item in hand never does; the lantern yields to it alone; the flint to the item and the lantern; the spyglass to those and the
 * flint; the shield, last, to all of them (it sits on the other side, so it seldom has to move for any but the item, but a bow swung across the screen can push the
 * lantern into its home). A player may have all four out with a tool and a bow drawn. The lantern's chain, which swings, is kept out of everything solid as it
 * swings ({@link FpLayout#collide}). The arithmetic is
 * {@link FpClearance}; the held things are {@link FpHeld}. Render thread only.
 */
final class FpOverlay {
	private FpOverlay() {
	}

	private static final FpClearance.Body BODY = new FpClearance.Body();
	private static final FpClearance.Obstacles HELD = new FpClearance.Obstacles(), CHAIN = new FpClearance.Obstacles(), SOLID = new FpClearance.Obstacles();
	private static final FpClearance.Pose POSE = new FpClearance.Pose(), SOLVED = new FpClearance.Pose(), WARM = new FpClearance.Pose();

	private static float distance(FpClearance.Pose a, FpClearance.Pose b) {
		float dx = a.sx - b.sx, dy = a.sy - b.sy, dz = a.sz - b.sz;
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz) + 20F * Math.abs(a.scale - b.scale);
	}

	/**
	 * Places every accessory the overlay is to draw: {@code base} the hand pass's pose before its px scale, {@code d} the finished draw
	 * (its accessories, the main hand), {@code lantern} the lantern is drawn, {@code onBody} the bit set of those the world pass drew on the body.
	 */
	static void place(ArmDraw d, Matrix4fc base, boolean lantern, int onBody) {
		FpLayout.clear();
		boolean left = d.leftHand();
		HELD.clear();
		FpHeld.volumes(base, left, HELD);
		CHAIN.clear();
		SOLID.clear();
		for (int i = 0; i < HELD.count(); i++) {
			CHAIN.copyFrom(HELD, i);
			SOLID.copyFrom(HELD, i);
		}
		if (lantern) {
			LureGeometry.firstPersonBody(left, LureGeometry.offhandBlend(), BODY);
			solve(0, d, CHAIN, true);
		}
		if (on(d, ArmProtocol.FLINT, onBody)) {
			FlintRenderer.firstPersonBody(left, BODY);
			solve(ArmProtocol.accessoryIndex(ArmProtocol.FLINT), d, CHAIN, true);
		}
		if (on(d, ArmProtocol.SPYGLASS, onBody)) {
			SpyglassRenderer.firstPersonBody(left, BODY);
			solve(ArmProtocol.accessoryIndex(ArmProtocol.SPYGLASS), d, CHAIN, true);
		}
		if (on(d, ArmProtocol.SHIELD, onBody)) {
			ShieldRenderer.firstPersonBody(d, BODY);
			solve(ArmProtocol.accessoryIndex(ArmProtocol.SHIELD), d, CHAIN, false);
		}
		FpLayout.avoid(SOLID);
	}

	private static boolean on(ArmDraw d, String id, int onBody) {
		return d.accessoryOn(id) && (onBody & (1 << ArmProtocol.accessoryIndex(id))) == 0;
	}

	/** Solves {@link #BODY} against {@code against}, eases it, and (when {@code later}) adds it to {@link #CHAIN} for the accessories that yield to it. */
	private static void solve(int index, ArmDraw d, FpClearance.Obstacles against, boolean later) {
		if (BODY.count() == 0) return;
		FpClearance.solve(BODY, against, FpClearance.MARGIN, SOLVED);
		// a held item moving a little must move the answer a little: when the smallest placement is far from the one shown, and the one found
		// by carrying on from it is clear too, ease toward that (it relaxes home by itself once the held thing moves on)
		FpClearance.Pose shown = FpClearance.shownPose(index);
		if (shown != null && distance(SOLVED, shown) > 3F) {
			WARM.copy(shown);
			FpClearance.solveFrom(BODY, against, FpClearance.MARGIN, WARM, shown);
			if (WARM.worst <= 0F && SOLVED.worst <= 0F || WARM.worst < SOLVED.worst) SOLVED.copy(WARM);
		}
		POSE.copy(SOLVED);
		FpClearance.ease(index, POSE, d.time());
		// the eased placement lags a relaxing push: it must still clear what stands now, or the solved one is drawn
		if (FpClearance.worst(BODY, POSE, against, FpClearance.MARGIN) > Math.max(0F, SOLVED.worst) + 0.02F) {
			POSE.copy(SOLVED);
			FpClearance.show(index, POSE);
		}
		FpLayout.set(index, POSE);
		if (later) CHAIN.addPlaced(BODY, POSE);
		// the lantern'"'"'s chain swings round everything but itself
		if (index != 0) SOLID.addPlaced(BODY, POSE);
	}
}
