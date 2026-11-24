package dev.morphreborn.client.arm.geom;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.Drips;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import dev.morphreborn.client.arm.render.Stations;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The first-person fist round a tool's grip (section 2.3 F8), and the small pieces every form's hand shares. Vanilla
 * draws no arm under a held item, so in first person this and {@link ForearmGeometry} are the player's hand; a tool's
 * geometry draws them itself, inside {@link #mirror} for the left hand.
 * <p>
 * The fist: a palm of black static against the grip on the forearm's side, four knuckle knots on the far side, and
 * three finger tendrils wrapped 300° round the grip between them. The knuckles beat (+25%) after a hit or as a feeding
 * pulse comes through; the fingers squeeze the grip 3% as a pulse passes; while the arm melts, goo runs off the finger
 * tips. Grip space G, px, drawn as a right hand (the left is the mirror).
 * <p>
 * Shared with the forms: the feed coordinate where the tool begins ({@link #PHI_TOOL}), a strand's face width as a
 * tube radius ({@link #width}), the left hand's mirror, a seeded hash, a grey for vertex colours, loose beads of goo, and
 * FORM's burst tendrils out of the knuckles. Render thread only (shared scratch).
 */
public final class FistGeometry {
	/** +1: the palm on the grip's +Z side (toward the forearm), the knuckles on its −X side. The preview shows which. */
	public static final float KNUCKLE_SIDE = 1F;
	/** The feed coordinate where the fist hands biomass to the tool: the sheath is 0..8, the fist 8..12. */
	public static final float PHI_TOOL = 12F;
	private static final float PHI_FIST = 8F;
	private static final float[] KNUCKLE_Y = {-1.35F, -0.45F, 0.45F, 1.35F};
	/** The wrist's x (the forearm's F2 wrist). */
	private static final float WRIST_PX = 0.4F;
	/**
	 * The claw: the fingers' heights on the grip (the thumb's last), where they start round it (the palm's side, +Z) and
	 * how far they wrap; the thumb comes round the other way.
	 */
	private static final float[] CLAW_Y = {-2.0F, -0.35F, 1.4F, 0.55F};
	private static final float CLAW_FROM = (float) Math.toRadians(95), CLAW_WRAP = (float) Math.toRadians(225);
	private static final float CLAW_FROM_THUMB = (float) Math.toRadians(70), CLAW_THUMB = (float) Math.toRadians(-185);
	/** A reshape's three burst tendrils go to these of the six targets: the point, the far edge, the pommel. */
	private static final int[] RESHAPE_TARGETS = {0, 2, 4};
	private static final int PART_PALM = 50, PART_KNUCKLE = 51, PART_FINGER = 60, PART_DRIP = 55, PART_BEAD = 56, PART_BURST = 57;

	private static final Matrix4f MIRROR = new Matrix4f().scaling(-1F, 1F, 1F);
	private static final Matrix4f LOCAL = new Matrix4f();
	private static final Stations STRAND = new Stations();
	private static final float[] RING = new float[8];
	private static final float[] PTS = new float[3 * 8];
	private static final float[] RAD = new float[8];
	private static final Vector3f DOWN = new Vector3f(), FROM = new Vector3f(), TO = new Vector3f(), CTRL = new Vector3f(), P = new Vector3f();
	private static final Vector3f SIDE = new Vector3f(), UP = new Vector3f(), DIR = new Vector3f();

	private FistGeometry() {}

	// ------------------------------------------------------------------ shared small pieces

	/**
	 * The radius {@code Loft.tube} needs for a tube of {@code sides} faces to be {@code w} px across its faces: the spec
	 * gives strand widths, and a tube's radius is to its corners.
	 */
	public static float width(float w, int sides) {
		return w / (2F * (float) Math.cos(Math.PI / Math.max(3, sides)));
	}

	/** Draws everything after this mirrored across G's YZ plane ({@code left}), or plainly again. */
	public static void mirror(SkinMesh m, boolean left) {
		m.transform(left ? MIRROR : null);
	}

	/** A seeded number in 0..1 (lowbias32), the same every frame for the same seed. */
	public static float hash(int x) {
		x ^= x >>> 16;
		x *= 0x7feb352d;
		x ^= x >>> 15;
		x *= 0x846ca68b;
		x ^= x >>> 16;
		return (x >>> 8) / 16777216F;
	}

	/** A grey of brightness {@code b} (0..1) as 0xRRGGBB: what a vertex colour can do to the texture is darken it. */
	public static int grey(float b) {
		int c = Math.round(ArmMath.clamp01(b) * 255F);
		return (c << 16) | (c << 8) | c;
	}

	/** A bead of goo ({@code beadLayer}, as the melt paints it) of {@code size} px centred on ({@code x, y, z}). */
	public static void bead(ArmDraw d, ArmLayer layer, SkinMesh m, Static st, ArmLayer beadLayer, float x, float y, float z, float size, int j) {
		if (size <= 0.02F || d.paint(beadLayer) != layer) return;
		float h = size / 2F;
		m.box(null, x - h, y - h, z - h, size, size, size, st.u0(PART_BEAD, j, 0F), st.v0(PART_BEAD, j, 0F), 2F, 1F, m.tint());
	}

	/**
	 * RESHAPE's churning knot is drawn by the renderer round the grip for every form (ArmFormRenderer.knot), between the
	 * form being left and the one forming; a form has nothing to add here, so this draws nothing.
	 */
	public static void knot(ArmDraw d, ArmLayer layer, SkinMesh m, Static st) {
		// the renderer's knot covers it
	}

	/** Knuckle {@code k}'s centre (0..3, bottom to top) round a grip of {@code hiltRadius}, as a right hand, into {@code out}. */
	static Vector3f knuckle(int k, float hiltRadius, Vector3f out) {
		return out.set(-(hiltRadius + 0.45F) * KNUCKLE_SIDE, KNUCKLE_Y[k & 3], 0.25F * KNUCKLE_SIDE);
	}

	// ------------------------------------------------------------------ the fist

	/** The static the pinned entry point draws with (the forms hand theirs to the other one). */
	private static final Static OWN_STATIC = new Static();

	/**
	 * The pinned entry point (section 6.1): the fist for {@code d} round a grip of {@code hiltRadiusPx} in plain grip
	 * space G, the left hand's mirror applied here (and cleared after), its own static, the pulses read on {@code d}'s
	 * form. The forms themselves call the other entry point, sharing their static and their mirror with the forearm.
	 */
	public static void emit(ArmDraw d, ArmLayer layer, SkinMesh m, float hiltRadiusPx) {
		if (d == null || layer == ArmLayer.SENSE) return;
		OWN_STATIC.set(d.time(), d.staticCadence(), d.crawlDir(), d.seed());
		mirror(m, d.leftHand());
		try {
			emit(d, layer, m, OWN_STATIC, hiltRadiusPx, ArmGeometry.get(d.form()).feedLength(d.tier()));
		} finally {
			mirror(m, false);
		}
	}

	/**
	 * The fist for {@code d} round a grip of {@code hiltR} px (katana 1.05, pickaxe 0.95), the faces of {@code layer},
	 * its static from {@code st}, pulses read on a form whose feed coordinate ends at {@code feedLength}. Call inside
	 * {@link #mirror} for the left hand.
	 */
	public static void emit(ArmDraw d, ArmLayer layer, SkinMesh m, Static st, float hiltR, float feedLength) {
		if (!ArmDraw.FIRST_PERSON_ARM) return; // first person shows just the tool
		if (d == null) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), vein = d.glow() ? ArmLayer.GLOW : d.paint(ArmLayer.RED);
		if (layer != black && layer != vein) return; // black static, and one red finger
		float k = KNUCKLE_SIDE;
		int lod = Math.clamp(d.lod(), 0, 3);
		float g = d.swell(PHI_FIST + 2F, feedLength);
		// The claw (2026-11-15: claw fingers, the handle showing): three tendril fingers and a thumb out of the
		// wrist, each round the grip most of the way from the palm's side, knuckled, tapering to a hooked point dug into
		// the grip, the grip showing between them; always flexing a little. A beat (a hit, a feeding pulse) clenches them.
		// They reach round as the tendril arm grows. The middle finger is red (glowing with glow on), as in third person.
		if (lod >= 2) return;
		float reach = ArmMath.sm((d.sheathCover() - 0.5F) / 0.5F);
		if (reach <= 0.01F) return;
		int n = lod == 0 ? 7 : 5;
		float beat = 1F + 0.5F * d.knotBeat();
		float rho = (hiltR + 0.3F) * (1F - 0.04F * ArmMath.clamp01(g)) / beat;
		float time = d.time();
		for (int f = 0; f < 4; f++) {
			if (layer != (f == 1 ? vein : black)) continue;
			boolean thumb = f == 3;
			float from = thumb ? CLAW_FROM_THUMB : CLAW_FROM, sweep = (thumb ? CLAW_THUMB : CLAW_WRAP) * reach;
			float flex = 1F + 0.06F * (float) Math.sin(0.12F * time + 1.9F * f);
			for (int i = 0; i < n; i++) {
				float u = i / (float) (n - 1);
				float a = from + sweep * u * flex;
				// out of the wrist over the first stretch, then round the grip, closing in to the point
				float in = 1F - ArmMath.sm(u / 0.22F);
				float r = rho * (1F + 0.18F * (float) Math.sin(ArmMath.PI * ArmMath.clamp01((u - 0.25F) / 0.45F))) * (1F - 0.3F * ArmMath.sm((u - 0.82F) / 0.18F));
				float x = r * (float) Math.cos(a) * k, z = r * (float) Math.sin(a) * k;
				PTS[3 * i] = ArmMath.lerp(x, WRIST_PX * k, in);
				PTS[3 * i + 1] = ArmMath.lerp(CLAW_Y[f] + (thumb ? -0.4F : 0.25F) * u, -1.6F, in);
				PTS[3 * i + 2] = ArmMath.lerp(z, 1.9F, in);
				RAD[i] = width((thumb ? 0.6F : 0.5F) * ArmMath.lerp(1F, 0.18F, ArmMath.sm((u - 0.45F) / 0.55F)) * (1F + 0.15F * ArmMath.clamp01(g)), 4);
			}
			strand(m, n, layer, st, PART_FINGER + f, 1.5F, PHI_FIST, true);
		}
		// melting: goo runs off the claw points
		if (layer == black && d.meltTick() >= 10F && d.dripsNow()) {
			d.down(DOWN);
			if (d.leftHand()) DOWN.x = -DOWN.x; // drawn inside the left hand's mirror
			float a = CLAW_FROM + CLAW_WRAP;
			for (int j = 0; j < 2; j++) {
				Drips.emit(m, null, rho * 0.7F * (float) Math.cos(a) * k, CLAW_Y[j == 0 ? 0 : 2], rho * 0.7F * (float) Math.sin(a) * k, DOWN, d.time(), j, d.dripRate(),
						d.blocksToPx(), st, PART_DRIP);
			}
		}
	}

	// ------------------------------------------------------------------ FORM's burst tendrils

	/**
	 * FORM's burst tendrils (six; a reshape's new form three): out of the knuckles toward {@code targets} (x, y, z per
	 * target in G px, unmirrored), steering on a curve that leaves the knuckle outward and bends onto its target,
	 * writhing with the frenzy's end-pinned wave, overshooting by a fifth and settling by their third tick, 1.6 px thick
	 * at the knuckle and 0.4 at the tip. Each sinks into its target over two ticks once FORM's clock passes
	 * {@code pass[i]} (the tick the growing tool reaches it).
	 */
	public static void burst(ArmDraw d, ArmLayer layer, SkinMesh m, Static st, float[] targets, float[] pass) {
		int count = d.burstCount();
		if (count <= 0 || layer != d.paint(ArmLayer.BLACK) && layer != d.paint(ArmLayer.RED)) return;
		boolean reshape = d.state() == ArmProtocol.STATE_RESHAPING;
		float b = reshape ? d.stateTicks() - ArmDraw.RESHAPE_SWAP : d.burstTick();
		float reach = b < 0F ? 0F : b < 2.2F ? 1.2F * ArmMath.sm(b / 2.2F) : ArmMath.lerp(1.2F, 1F, ArmMath.sm((b - 2.2F) / 0.8F));
		if (reach <= 0.01F) return;
		float tau = d.formTick();
		float sh = d.leftHand() ? -1F : 1F;
		for (int q = 0; q < count; q++) {
			int i = count >= 6 ? q : RESHAPE_TARGETS[q % RESHAPE_TARGETS.length];
			if (3 * i + 2 >= targets.length || i >= pass.length) continue;
			float sink = ArmMath.clamp01((tau - pass[i]) / 2F);
			if (sink >= 0.999F) continue;
			if (layer != d.paint(i % 3 == 1 ? ArmLayer.RED : ArmLayer.BLACK)) continue;
			knuckle(q % 4, 1.0F, FROM);
			FROM.x *= sh;
			TO.set(targets[3 * i], targets[3 * i + 1], targets[3 * i + 2]);
			float len = FROM.distance(TO);
			if (len < 0.2F) continue;
			int seed = d.seed() * 31 + i;
			// the control point: out of the knuckle and a third of the way along, nudged by the seed
			DIR.set(TO).sub(FROM);
			CTRL.set(FROM).fma(0.35F, DIR).add(-sh * KNUCKLE_SIDE * 0.35F * len + (hash(seed) - 0.5F) * 1.2F, (hash(seed + 7) - 0.5F) * 1.2F,
					0.2F * len + (hash(seed + 13) - 0.5F) * 1.2F);
			DIR.normalize();
			SIDE.set(DIR).cross(0F, 1F, 0F);
			if (SIDE.lengthSquared() < 1e-4F) SIDE.set(1F, 0F, 0F);
			SIDE.normalize();
			UP.set(SIDE).cross(DIR).normalize();
			float phase = d.phase() + 1.3F * i, time = d.time();
			float s0 = sink * Math.min(reach, 1F);
			for (int p = 0; p < 6; p++) {
				float s = s0 + (reach - s0) * p / 5F;
				ArmMath.bezier(FROM, CTRL, TO, s, P);
				float env = (float) Math.sin(Math.PI * Math.min(s, 1F)) * 0.45F;
				float wa = (float) Math.sin(phase + 7F * s + 0.35F * time) * env, wb = 0.6F * (float) Math.cos(0.8F * phase + 5F * s + 0.28F * time) * env;
				PTS[3 * p] = P.x + SIDE.x * wa + UP.x * wb;
				PTS[3 * p + 1] = P.y + SIDE.y * wa + UP.y * wb;
				PTS[3 * p + 2] = P.z + SIDE.z * wa + UP.z * wb;
				RAD[p] = width(ArmMath.lerp(1.6F, 0.4F, Math.min(s, 1.2F) / 1.2F) * (1F - sink), 4);
			}
			strand(m, 6, layer, st, PART_BURST + q, 1F, PHI_TOOL, true);
		}
	}

	/**
	 * A four-sided strand through the first {@code n} of {@link #PTS} (radius {@link #RAD}), its frame carried along by
	 * parallel transport so it never twists, in {@code layer}; capped at its end when asked.
	 */
	private static void strand(SkinMesh m, int n, ArmLayer layer, Static st, int part, float uvScale, float phi, boolean capEnd) {
		Stations s = STRAND.reset(4).feed(phi).hint(0F, 1F, 0F);
		float arc = 0F;
		for (int i = 0; i < n; i++) {
			if (i > 0) {
				float dx = PTS[3 * i] - PTS[3 * i - 3], dy = PTS[3 * i + 1] - PTS[3 * i - 2], dz = PTS[3 * i + 2] - PTS[3 * i - 1];
				arc += (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			}
			int a = i > 0 ? i - 1 : 0, b = i + 1 < n ? i + 1 : n - 1;
			ArmMath.ring(RING, 4, RAD[i], RAD[i], ArmMath.PI / 4F);
			s.addTransported(PTS[3 * i], PTS[3 * i + 1], PTS[3 * i + 2], PTS[3 * b] - PTS[3 * a], PTS[3 * b + 1] - PTS[3 * a + 1], PTS[3 * b + 2] - PTS[3 * a + 2], arc,
					RING, 1F);
		}
		Loft.sweep(m, s, layer, ALL[layer.ordinal()], st, part, uvScale, false, capEnd);
	}

	/** Every face in one layer, by the layer's ordinal (no lambda made per strand). */
	private static final Loft.FaceLayer[] ALL = {(s, f) -> ArmLayer.BLACK, (s, f) -> ArmLayer.RED, (s, f) -> ArmLayer.GLOW, (s, f) -> ArmLayer.SENSE};
}
