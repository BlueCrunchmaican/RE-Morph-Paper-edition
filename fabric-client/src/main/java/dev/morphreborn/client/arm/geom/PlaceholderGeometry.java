package dev.morphreborn.client.arm.geom;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import java.util.function.Consumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * What a form looks like when its geometry isn't in the build (phase 2's forms before they land, or one that failed to
 * load): a rod of the morph skin's static as long as a sword, with a red guard and a red tip, growing and shrinking with
 * the arm's timelines and swelling with its pulses. It is deliberately plain, but it is the arm: it never lets the
 * vanilla tool show, and in first person it brings a simple forearm and fist so the hand is never missing.
 * <p>
 * One instance per form ({@link ArmGeometry#get}); render thread only (shared scratch).
 */
public final class PlaceholderGeometry implements FormGeometry {
	/** The rod's ends in G px (from the pommel to the tip) and its radius at the grip. */
	private static final float BUTT = -4.5F, TIP = 17F, RADIUS = 0.8F;
	/** Where the guard sits, its size, and where the red tip starts. */
	private static final float GUARD_Y = 2.6F, RED_FROM = 14.5F;
	/** The feed coordinate where the tool begins (the top of the fist) and at its tip. */
	private static final float TOOL_PHI = 12F;
	/** First person: the wrist and the rig's elbow point (section 2.3 F1/F2), x mirrored for the left hand. */
	private static final float WRIST_X = 0.4F, WRIST_Y = -1.6F, WRIST_Z = 1.9F, RIG_X = 1.2F, RIG_Y = -20F, RIG_Z = 3.5F;

	private static final Static ST = new Static();
	private static final Matrix4f LOCAL = new Matrix4f();
	private static final Vector3f UP_Z = new Vector3f(0F, 0F, 1F);
	private static final Vector3f FROM = new Vector3f(), TO = new Vector3f();
	/** The draw being emitted, for the curve and radius callbacks (render thread, one emit at a time). */
	private static ArmDraw current;
	private static float reach = 1F, feedLength = TOOL_PHI + TIP, top = TIP;
	private static boolean failing;
	/** The stretch of the rod the next tube covers, in G px along Y. */
	private static float y0 = BUTT, y1 = TIP;

	private static final Loft.CurveFn ROD = (s, out) -> out.set(0F, ArmMath.lerp(y0, y1, s), 0F);
	private static final Loft.RadiusFn ROD_RADIUS = s -> {
		float y = ArmMath.lerp(y0, y1, s);
		float taper = y < 0F ? 1F : ArmMath.lerp(1F, 0.35F, ArmMath.clamp01(y / TIP));
		float phi = TOOL_PHI + y;
		float swell = 1F + current.pulse(phi, feedLength);
		return RADIUS * taper * swell * (failing ? 0.85F : 1F);
	};
	private static final Loft.AlphaFn ROD_ALPHA = s -> ArmMath.fringe(top - ArmMath.lerp(y0, y1, s), 1.5F); // the growth front erodes (dissolve on)
	private static final Loft.CurveFn ARM = (s, out) -> out.set(FROM).lerp(TO, s);
	private static final Loft.RadiusFn ARM_RADIUS = s -> ArmMath.lerp(1.9F, 2.6F, s) * (1F + 0.25F * current.pulse(ArmMath.lerp(8F, 0F, s), feedLength));

	private final String form;

	public PlaceholderGeometry(String form) {
		this.form = form == null ? "" : form;
	}

	@Override
	public String form() {
		return form;
	}

	@Override
	public void emit(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer != ArmLayer.BLACK && layer != ArmLayer.RED) return;
		current = d;
		feedLength = feedLength(d.tier());
		reach = reach(d);
		failing = d.lowStage() == ArmDraw.LOW_FAILING;
		ST.set(d.time(), d.cadence(), d.crawlDir(), d.seed());
		if (d.firstPerson()) forearm(d, layer, m);
		if (reach <= 0.01F) return;
		top = ArmMath.lerp(0F, TIP, reach);
		if (d.lod() >= ArmDraw.LOD_FAR) {
			// a single static box where the rod would be
			if (layer == ArmLayer.BLACK) m.box(null, -RADIUS, BUTT, -RADIUS, 2F * RADIUS, top - BUTT, 2F * RADIUS, ST.u0(0, 0, 0F), ST.v0(0, 0, 0F), 1F, 1F, SkinMesh.WHITE);
			return;
		}
		int sides = d.lod() >= 2 ? 4 : 6;
		int stations = d.lod() >= 2 ? 4 : d.lod() == 1 ? 8 : 16;
		// growth by truncation: the rod is cut at the front (its last 1.5 px eroding when dissolve is on), the black up to
		// the red tip and the tip itself
		Loft.AlphaFn fringe = d.dissolve() && reach < 1F ? ROD_ALPHA : null;
		y0 = BUTT;
		y1 = Math.min(top, RED_FROM);
		Loft.tube(m, ROD, 0F, 1F, stations, sides, ROD_RADIUS, ArmLayer.BLACK, layer, ST, 1, 1F, TOOL_PHI + y0, TOOL_PHI + y1, UP_Z, fringe);
		if (top > RED_FROM + 0.05F) {
			y0 = RED_FROM;
			y1 = top;
			Loft.tube(m, ROD, 0F, 1F, Math.max(3, stations / 4), sides, ROD_RADIUS, ArmLayer.RED, layer, ST, 2, 2F, TOOL_PHI + y0, TOOL_PHI + y1, UP_Z, fringe);
		}
		// the guard, popping out as the rod passes it
		if (layer == ArmLayer.RED && top > GUARD_Y) {
			float pop = ArmMath.sm((top - GUARD_Y) / 2F);
			float w = 3.2F * pop, h = 0.8F, dz = 1.8F * pop;
			m.box(null, -w / 2F, GUARD_Y, -dz / 2F, w, h, dz, ST.u0(3, 0, TOOL_PHI + GUARD_Y), ST.v0(3, 0, TOOL_PHI + GUARD_Y), 1F, 1F, SkinMesh.WHITE);
		}
	}

	/** First person: a plain forearm from the wrist back toward the elbow point, and a fist round the grip. */
	private static void forearm(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer != ArmLayer.BLACK) return;
		float side = d.leftHand() ? -1F : 1F;
		FROM.set(WRIST_X * side, WRIST_Y, WRIST_Z);
		float[] a = d.fpAnchor();
		if (a != null && a.length >= 3) TO.set(a[0], a[1], a[2]);
		else TO.set(RIG_X * side, RIG_Y, RIG_Z);
		int sides = d.lod() == 0 ? 8 : 4;
		Loft.tube(m, ARM, 0F, 1F, d.lod() == 0 ? 8 : 4, sides, ARM_RADIUS, ArmLayer.BLACK, layer, ST, 4, 1F, 8F, 0F, UP_Z, 1F);
		LOCAL.identity().translate(0.15F * side, 0F, 1.05F);
		m.box(LOCAL, -1.3F, -1.8F, -1F, 2.6F, 3.6F, 2F, ST.u0(5, 0, 10F), ST.v0(5, 0, 10F), 1F, 1F, SkinMesh.WHITE);
	}

	/**
	 * How much of the rod is out, as every form reads it ({@link ArmDraw#reach()}): grown from the grip while forming,
	 * shrunk back while retracting or melting; while reshaping, the renderer draws the old form collapsing for the first
	 * 7 ticks and then this one growing.
	 */
	static float reach(ArmDraw d) {
		return d.reach();
	}

	@Override
	public void extents(Consumer<Vector3fc> out) {
		for (int i = 0; i < 8; i++) {
			out.accept(new Vector3f((i & 1) == 0 ? -1.6F : 1.6F, (i & 2) == 0 ? BUTT : TIP, (i & 4) == 0 ? -1F : 1F));
		}
	}

	@Override
	public Vector3f point(ArmDraw d, int which, Vector3f out) {
		float top = ArmMath.lerp(0F, TIP, reach(d));
		return switch (which) {
			case ArmTips.TIP -> out.set(0F, Math.max(0F, top), 0F);
			case ArmTips.MID -> out.set(0F, Math.max(0F, top) * 0.55F, 0F);
			default -> out.set(0F, 0F, 0F);
		};
	}

	@Override
	public float feedLength(int tier) {
		return TOOL_PHI + TIP;
	}

	@Override
	public float guiScale() {
		return 0.75F;
	}

	@Override
	public float guiGripPx() {
		return 4.0F;
	}

	@Override
	public boolean bowTransforms() {
		return ArmProtocol.BOW.equals(form);
	}
}
