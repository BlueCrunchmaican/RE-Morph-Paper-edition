package dev.morphreborn.client.arm.geom;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * How the shield, the spyglass and the flint and steel hold on to the arm (2026-11-24): a few open tendrils that wind round it and end in
 * a small knot where the accessory leaves it, in the style of the lantern's sleeve, replacing the closed rings and collars they had
 * (they looked "a little too regular", not "tendril like"). One builder; what changes from arm to arm is data.
 * <p>
 * <b>What makes it tendrils and not bands.</b> Every strand is open (a thin free tip that curls 0.3 px into the skin, growing to a body
 * 0.34 to 0.40 px across, ending in the knot), has its own number of turns, direction (alternating, so they cross like a braid), start
 * angle (golden-ratio spaced, so the gaps are uneven) and thickness, and writhes slowly and independently: its angle by 0.05 to 0.08 rad
 * and its distance from the arm by about 0.1 px, each on its own period of 60 to 120 ticks. They grow from the knot outward over the
 * accessory's build.
 * <p>
 * <b>The surface they ride</b> ({@link Variant}) follows what the arm is showing, because the accessories never change the arm (the
 * tools do that): the plain Minecraft arm (a superellipse round its box, {@link #MARGIN} out of its faces, 0.35 more over a
 * chestplate), the tendril arm (a superellipse on {@link SheathGeometry#tendrilEnvelope}, so the strands pass over the arm's own
 * tendrils and taper with them), the plain hand (as the plain arm), and the armour (the tendril envelope slimmed, with the strands
 * kept off the plates: they leave from under a plate's edge, like the lantern's stalk does). While a tool forms or retracts the surface
 * blends between the plain arm and the look's by how much of the sheath is out, so nothing pops.
 * <p>
 * Arm space A of the arm the accessory sits on (px, +Y down the arm, -Z forward), drawn un-mirrored with an {@code out} sign (+1 for
 * the left arm). Render thread only (shared scratch), nothing allocated per frame.
 */
final class AccessoryGrip {
	private AccessoryGrip() {
	}

	/** What the arm shows under a grip. */
	enum Variant {
		BARE, TENDRIL, HAND, ARMOUR
	}

	/** How far out of the plain arm's faces a strand's centre lies at rest: the sleeve layer 0.25, the thickest strand 0.44, 0.06 over. */
	static final float MARGIN = 0.75F;
	/** Extra over a chestplate's arm, and how far outside the tendril arm's own envelope a strand rides. */
	static final float CHEST = 0.35F, RIDE = 0.5F;
	/** The pulse feed coordinate's length along a grip's strand. */
	static final float FEED = 12F;
	/** How far a strand's tip dips into the sleeve layer (px, of the margin). */
	private static final float TIP_DIP = 0.45F;

	/** One accessory's strands: tip heights, signed turns (the sign is the direction), end offsets, thickness, which one is red, and where it ends. */
	static final class Kit {
		final int part0;
		final float[] yTip, turns, endAngle, endY, width;
		final int red;
		final int count;

		Kit(int part0, int red, float[] yTip, float[] turns, float[] endAngle, float[] endY, float[] width) {
			this.part0 = part0;
			this.red = red;
			this.yTip = yTip;
			this.turns = turns;
			this.endAngle = endAngle;
			this.endY = endY;
			this.width = width;
			this.count = yTip.length;
		}
	}

	/** The spyglass's five, over y -1.4 to 1.2 (port at 0.9), 1.0 / 1.1 / 0.9 / 1.2 / 0.8 turns alternating, golden-ratio end angles. */
	static final Kit SPYGLASS = new Kit(360, 2, new float[] {-1.8F, 1.0F, -0.9F, 0.6F, -1.4F}, new float[] {0.75F, -0.9F, 0.65F, -0.85F, 0.6F},
			golden(5, 0.5F), new float[] {-0.35F, 0.3F, -0.05F, 0.4F, 0.1F}, new float[] {1.0F, 0.9F, 1.15F, 0.85F, 1.0F});
	/** The flint's five, over y 2.0 to 3.9 (port at 2.2). */
	static final Kit FLINT = new Kit(366, 2, new float[] {4.1F, 2.6F, 3.4F, 2.9F, 3.8F}, new float[] {0.7F, -0.95F, 0.8F, -0.75F, 0.9F}, golden(5, 0.5F),
			new float[] {0.35F, -0.3F, 0.05F, -0.4F, 0.15F}, new float[] {0.9F, 1.1F, 1.0F, 0.85F, 1.15F});
	/** The shield's six, tips on the forearm at y 4.6 to 6.6, all winding down into the wrist knot (a red one at index 2). */
	static final Kit SHIELD = new Kit(372, 2, new float[] {4.6F, 5.1F, 5.3F, 5.6F, 6.0F, 6.6F}, new float[] {1.3F, -1.1F, 0.9F, -1.2F, 0.7F, -1.0F},
			golden(6, 0.26F), new float[] {0.1F, -0.15F, 0F, 0.2F, -0.1F, 0.15F}, new float[] {1.0F, 0.9F, 1.15F, 0.85F, 1.05F, 0.95F});

	/** {@code n} end angles spread by the golden ratio over about {@code span} of a turn (radians, centred on 0): uneven gaps. */
	private static float[] golden(int n, float span) {
		float[] out = new float[n];
		for (int i = 0; i < n; i++) out[i] = (ArmMath.frac(0.6180339F * (i + 1)) - 0.5F) * ArmMath.TAU * span;
		return out;
	}

	// ------------------------------------------------------------------ the draw's working state

	private static final Matrix4f HOLD = new Matrix4f();
	private static final Quaternionf TURN = new Quaternionf();
	private static final Vector3f PORT = new Vector3f(), PT = new Vector3f(), END = new Vector3f(), BASE = new Vector3f(), DOWN = new Vector3f(),
			UP_Y = new Vector3f(0F, 1F, 0F), ALONG_Z = new Vector3f(0F, 0F, 1F), INWARD = new Vector3f();
	private static ArmDraw cur;
	private static Kit kit;
	private static Variant variant = Variant.BARE;
	private static boolean perStrandEnd, fold, chestOn;
	private static int k;
	private static float out = -1F, xc, hx, hz, look, time, ph, portA, portY, squeeze, feedLen = FEED, tipFront;
	/** Set by the shield: the strands carry beads and the drips hang. */
	private static boolean beads;
	private static float gooPhase;

	// ------------------------------------------------------------------ variants

	/** The surface a grip rides for {@code d}: the plain arm when no tool is formed, else by the arm look. */
	static Variant variantOf(ArmDraw d) {
		if (d.form().isEmpty()) return Variant.BARE;
		return switch (SheathGeometry.lookOf(d)) {
			case ArmProtocol.LOOK_HAND -> Variant.HAND;
			case ArmProtocol.LOOK_ARMOUR -> Variant.ARMOUR;
			default -> Variant.TENDRIL;
		};
	}

	/** How much of the look's surface (as against the plain arm's) a grip rides: the sheath's own growth, 0 with no tool. */
	static float lookWeight(ArmDraw d, Variant v) {
		if (v == Variant.BARE || v == Variant.HAND) return 0F;
		return ArmMath.clamp01(d.sheathCover());
	}

	/** The arm's centre x for the arm whose outer side is {@code outSign} (+1 left): -1 (wide) or -0.5 (slim) on the right arm, mirrored. */
	static float armCentre(ArmDraw d, float outSign) {
		return (d.slim() ? 0.5F : 1F) * outSign;
	}

	/**
	 * Point on the arm's surface at angle {@code a} (0 is the inner face, pi the outer, 3 pi / 2 the front, as {@link SheathGeometry} reads
	 * them) and height {@code y}, {@code margin} out of it, into {@code o}. The plain box's superellipse blended toward the tendril arm's.
	 */
	static void surface(float a, float y, float margin, Vector3f o) {
		float c = (float) Math.cos(a), s = (float) Math.sin(a);
		float sx = Math.signum(c) * (float) Math.pow(Math.abs(c), 0.25), sz = Math.signum(s) * (float) Math.pow(Math.abs(s), 0.25);
		float chestM = chestOn ? CHEST : 0F;
		float md = margin - MARGIN;
		float bx = hx + chestM + MARGIN + md, bz = hz + chestM + MARGIN + md;
		if (look > 0F) {
			float reach = SheathGeometry.tendrilReach(variant == Variant.ARMOUR, y);
			float lx = SheathGeometry.tendrilEnvelope(hx + chestM, reach) + RIDE + md, lz = SheathGeometry.tendrilEnvelope(hz + chestM, reach) + RIDE + md;
			bx = ArmMath.lerp(bx, lx, look);
			bz = ArmMath.lerp(bz, lz, look);
		}
		o.set(xc - out * bx * sx, y, bz * sz);
	}

	/** The angle round the arm (as {@link #surface} reads it) of the point ({@code x}, {@code z}) in this arm's space. */
	static float angleOf(float x, float z) {
		return (float) Math.atan2(z, -out * (x - xc));
	}

	// ------------------------------------------------------------------ one draw

	/**
	 * Starts a grip: {@code d}'s arm, {@code outSign}, the variant, the stalk's root port ({@code port}, or null: each strand ends on the
	 * surface near the angle it was given, as the shield's do), and {@code squeeze} px the strands tighten by (an event's).
	 */
	static void begin(ArmDraw d, Kit which, float outSign, Variant v, Vector3f port, float squeezePx) {
		cur = d;
		kit = which;
		out = outSign;
		xc = armCentre(d, outSign);
		hx = d.slim() ? 1.5F : 2F;
		hz = 2F;
		chestOn = d.chestArmour();
		variant = v;
		look = lookWeight(d, v);
		time = d.time();
		ph = d.phase();
		squeeze = squeezePx;
		perStrandEnd = port == null;
		if (port != null) {
			PORT.set(port);
			portA = angleOf(PORT.x, PORT.z);
			portY = PORT.y;
		}
		fold = v == Variant.ARMOUR && which == FLINT;
		beads = false;
	}

	/** Whether the strands must keep to the bare arc between the plates (the armour's flint). */
	private static final float BARE_ARC_C = 2.07F * ArmMath.PI, BARE_ARC_HALF = 0.33F * ArmMath.PI;

	/** Overrides the port angle and height (the shield and the armour's ports are not on the outer face). */
	static void portAt(float angle, float y) {
		portA = angle;
		portY = y;
	}

	private static final Loft.CurveFn STRAND = (s, o) -> strandAt(k, s, o);

	/** The tendril {@code i}'s centre at {@code s} (0 the free tip, 1 the root in the knot). */
	static void strandAt(int i, float s, Vector3f o) {
		float dir = kit.turns[i] < 0F ? -1F : 1F;
		float turns = Math.abs(kit.turns[i]);
		float period = 60F + 20F * ((i * 3) % 4);
		float writheA = (0.05F + 0.01F * (i % 4)) * (float) Math.sin(ArmMath.TAU * time / period + ph + 2.1F * i + 3F * s) * (1F - 0.4F * s);
		// the angle does not advance at an even rate: each strand speeds up and slows down along its length, so no two lie as parallel straps
		float warp = (1F - s) + 0.12F * (float) Math.sin(ArmMath.TAU * (1F - s) * (1.1F + 0.17F * i) + 1.7F * i) * (float) Math.sin(ArmMath.PI * (1F - s));
		float turn = dir * turns * ArmMath.TAU * warp;
		float aEnd = portA + kit.endAngle[i];
		float a;
		if (fold) {
			// between plates the strands may not lie on the plates: they weave back and forth over the bare arc instead of winding round
			float edge = ArmMath.clamp((aEnd - foldCentre()) / BARE_ARC_HALF, -1F, 1F);
			a = foldCentre() + BARE_ARC_HALF * (float) Math.sin((float) Math.asin(edge) + turn);
		} else {
			a = aEnd - turn;
		}
		a += writheA;
		// each strand also bows up or down off the straight line between its tip and the knot (alternately), and rises a little off the arm mid-way,
		// so they read as living tendrils reaching for the knot rather than bands
		float bow = (i % 2 == 0 ? 1F : -1F) * 0.45F * (float) Math.sin(ArmMath.PI * (1F - s));
		float y = ArmMath.lerp(kit.yTip[i], portY + kit.endY[i], s) + bow + 0.12F * (float) Math.sin(ArmMath.TAU * time / (period * 0.8F) + ph + i + 5F * s);
		float breath = 0.1F * (float) Math.sin(ArmMath.TAU * time / (period + 15F) + ph * 0.7F + 1.7F * i + 4F * s);
		float lift = (i % 3 == 0 ? 0.16F : i % 3 == 1 ? 0.04F : -0.04F) * (float) Math.sin(ArmMath.PI * (1F - s));
		float margin = MARGIN + breath + lift - squeeze - TIP_DIP * (1F - ArmMath.sm(s / 0.12F));
		surface(a, y, margin, o);
		float w = ArmMath.sm((s - 0.78F) / 0.22F);
		if (w > 0F) {
			if (perStrandEnd) {
				surface(aEnd, portY + kit.endY[i], MARGIN - 0.5F, END);
				o.lerp(END, w);
			} else {
				o.lerp(PORT, w);
			}
		}
	}

	private static float foldCentre() {
		return BARE_ARC_C;
	}

	private static final Loft.RadiusFn RADIUS = s -> {
		float r = (k == kit.red ? 0.4F : 0.34F) * kit.width[k] * ArmMath.sm(s / 0.1F) * (1F - 0.35F * ArmMath.sm((s - 0.8F) / 0.2F))
				* (1F + 0.1F * (float) Math.sin(0.15F * time + 8F * s + k)) + 0.04F;
		// a growing strand's front thins to a point
		if (tipFront > 0F) r *= ArmMath.lerp(0.2F, 1F, ArmMath.sm((s - tipFront) / 0.1F));
		r *= 1F + 0.22F * cur.pulse((1F - s) * feedLen, FEED + 10F);
		if (beads) {
			float bump = 0F;
			for (int b = 0; b < 2; b++) bump += ArmMath.gauss(s - beadAt(k, b), 0.06F);
			r *= 1F + 0.9F * bump;
		}
		return r;
	};

	/** Where along strand {@code i} bead {@code b} is now: they crawl from the free tip toward the wrist, one every half a lap. */
	static float beadAt(int i, int b) {
		return 0.1F + 0.8F * ArmMath.frac(0.006F * time + 0.37F * i + 0.5F * b + gooPhase);
	}

	/**
	 * Draws the strands of the grip {@link #begin} set up, in {@code layer} (the red one also in the glow pass), grown to {@code g} (0..1,
	 * root first), with {@code stations} along each and {@code strandCount} of them (the red one is always among them).
	 */
	static void strands(SkinMesh m, ArmLayer layer, Static st, float g, int stations, int strandCount) {
		if (g < 0.03F) return;
		for (int i = 0; i < kit.count; i++) {
			if (i >= strandCount && i != kit.red) continue;
			boolean red = i == kit.red;
			ArmLayer own = red ? ArmLayer.RED : ArmLayer.BLACK;
			if (layer != own && !(red && Loft.pulseGlowPass(layer))) continue;
			float gk = ArmMath.clamp01(g * 1.35F - 0.08F * i);
			if (gk < 0.04F) continue;
			k = i;
			tipFront = gk < 1F ? 1F - gk : 0F;
			Loft.tube(m, STRAND, 1F - gk, 1F, Math.max(4, Math.round(stations * (0.45F + 0.55F * gk))), 3, RADIUS, own, layer, st, kit.part0 + i, 2F, feedLen * gk, 0F,
					UP_Y, 1F);
		}
		tipFront = 0F;
	}

	/**
	 * The root knot at {@code p}, where the stalk leaves the arm: a lump from just inside the arm out past the stalk's open root, closing it
	 * (as the lantern's), shrunk to 1.05 across at the outer end and 1.45 at the inner so that neighbouring ports do not meet. {@code normal} is the
	 * unit vector out of the surface there.
	 */
	static void knot(SkinMesh m, Static st, int part, Vector3f p, Vector3f normal, float k0) {
		if (k0 < 0.03F) return;
		INWARD.set(-normal.x, -normal.y, -normal.z);
		HOLD.identity().translate(p.x - INWARD.x * 0.75F * k0, p.y - INWARD.y * 0.75F * k0, p.z - INWARD.z * 0.75F * k0).rotate(TURN.rotationTo(ALONG_Z, INWARD));
		m.frustum(HOLD, 2.0F * k0, 1.05F * k0, 1.05F * k0, 1.45F * k0, 1.45F * k0, st.u0(part, 0, 0.5F), st.v0(part, 0, 0.5F), 2F, 1F, 1F, m.tint());
	}

	/** The outward unit normal of the arm's surface at the port, into {@code o}: the face the port is on. */
	static Vector3f normalAt(float x, float z, Variant v, Vector3f o) {
		float dx = -out * (x - xc), dz = z;
		float len = (float) Math.sqrt(dx * dx + dz * dz);
		if (len < 1e-4F) return o.set(-out, 0F, 0F);
		// a port under a plate faces the way the plate does: out along the line from the arm's axis, not square to a face
		if (v == Variant.ARMOUR && look > 0.5F) return o.set(-out * dx / hx, 0F, dz / hz).normalize();
		// the box's faces rather than a circle: whichever axis the point is further out on
		if (Math.abs(dx) / hx >= Math.abs(dz) / hz) return o.set(-out * Math.signum(dx), 0F, 0F);
		return o.set(0F, 0F, Math.signum(dz));
	}

	// ------------------------------------------------------------------ the stalks' roots by arm look

	/**
	 * The spyglass's stalk root and its first control point for this variant (arm space A, the arm whose outer side is {@code outSign}),
	 * into {@code p0} and {@code p1} (p1 arrives holding today's value and leaves lifted for the armour, whose root is under the top plate).
	 */
	static void spyglassPort(ArmDraw d, float outSign, Variant v, Vector3f p0, Vector3f p1) {
		float xcA = armCentre(d, outSign), hxA = d.slim() ? 1.5F : 2F, xo = xcA + outSign * hxA;
		p0.set(xo - 0.3F * outSign, 0.9F, 0.9F);
		if (v == Variant.ARMOUR) {
			// blended by how much of the plating is on, so the root slides under the plate as it grows and nothing pops
			float w = lookWeight(d, v);
			platePoint(d, outSign, 0, 0, 2.1F, 0.35F, PT);
			p0.lerp(PT, w);
			p1.y = ArmMath.lerp(p1.y, -4.0F, w);
		}
	}

	/** The flint's stalk root and first control point for this variant, as {@link #spyglassPort}. */
	static void flintPort(ArmDraw d, float outSign, Variant v, Vector3f p0, Vector3f p1) {
		float xcA = armCentre(d, outSign), hxA = d.slim() ? 1.5F : 2F, xo = xcA + outSign * hxA;
		p0.set(xo - 0.3F * outSign, 2.2F, -1.1F);
		if (v == Variant.ARMOUR) {
			// the plates are a continuous shield (their columns overlap and so do their rows): the stalk leaves from under the bottom row's lower edge
			float w = lookWeight(d, v);
			int row = SheathGeometry.plateRows() - 1;
			platePoint(d, outSign, row, SheathGeometry.plateColumns() - 1, SheathGeometry.plateY(row) + SheathGeometry.plateHeight() / 2F + 0.1F, 0.35F, PT);
			p0.lerp(PT, w);
			p1.x = ArmMath.lerp(p1.x, xo + outSign * 2.4F, w);
			p1.y = ArmMath.lerp(p1.y, 8.4F, w);
			p1.z = ArmMath.lerp(p1.z, -0.8F, w);
		}
	}

	/**
	 * A point under armour plate (row, column): at the plate's angle, {@code under} px inside its centre line, at height {@code y} (arm space
	 * A of the arm whose outer side is {@code outSign}, over a chestplate's lift when worn).
	 */
	static void platePoint(ArmDraw d, float outSign, int row, int col, float y, float under, Vector3f o) {
		float xcA = armCentre(d, outSign), hxA = d.slim() ? 1.5F : 2F, hzA = 2F;
		float lift = d.chestArmour() ? CHEST : 0F;
		float a = SheathGeometry.plateAngle(row, col);
		float rx = hxA * SheathGeometry.ARMOUR_FIT + lift - under, rz = hzA * SheathGeometry.ARMOUR_FIT + lift - under;
		o.set(xcA - outSign * rx * (float) Math.cos(a), y, rz * (float) Math.sin(a));
	}

	// ------------------------------------------------------------------ the whole grip of a stalk-holding accessory

	/** The shield's wrist knot: where the strands of its bands meet, closing over the hand's end. */
	static void wristKnot(SkinMesh m, Static st, float k0) {
		if (k0 < 0.03F) return;
		HOLD.identity().translate(xc, 9.7F, 0F).rotate(TURN.rotationTo(ALONG_Z, UP_Y));
		float r = ArmMath.lerp(1.2F, 2.4F, k0);
		m.frustum(HOLD, 1.0F * k0, 2F * r, 2F * r, 1.6F * k0 * 2F, 1.6F * k0 * 2F, st.u0(381, 0, 0.5F), st.v0(381, 0, 0.5F), 2F, 1F, 1F, m.tint());
	}

	/**
	 * The shield's bands, animated and gooey: its six strands wind down the forearm into the wrist knot, each with two beads crawling toward the
	 * wrist, and three drips hanging from the strands (a pendant with a bead at its tip, its length breathing over about 90 ticks). The
	 * margin breathes, tightens as the shield rises and flinches when a blow lands.
	 */
	static void shieldBands(ArmDraw d, ArmLayer layer, SkinMesh m, Static st, float outSign, float g, float raise, float recoil, boolean full, int lod) {
		begin(d, SHIELD, outSign, Variant.BARE, null, 0.18F * raise + 0.3F * recoil);
		portAt(outerAngle(), 9.7F);
		beads = full;
		gooPhase = 0.17F * (d.seed() % 5);
		feedLen = FEED + 6F;
		strands(m, layer, st, g, full ? 9 : 6, full ? kit.count : 3);
		if (layer == ArmLayer.BLACK) wristKnot(m, st, ArmMath.sm((g - 0.1F) / 0.5F));
		if (full && layer == ArmLayer.BLACK) drips(d, m, st, g);
		beads = false;
		feedLen = FEED;
	}

	/** The angle of the outer face on this arm. */
	static float outerAngle() {
		return ArmMath.PI;
	}

	private static final int[] DRIP_FROM = {1, 3, 4};
	private static float dripLen, dripBase;
	private static final Loft.CurveFn DRIP = (s, o) -> {
		// hangs along world down from the strand, sagging a little and swaying with the arm's phase
		o.set(BASE).fma(s * dripLen, DOWN);
		o.x += 0.12F * s * (float) Math.sin(ph + 0.09F * time + dripBase);
		o.z += 0.12F * s * (float) Math.cos(ph + 0.07F * time + dripBase);
	};
	private static final Loft.RadiusFn DRIP_R = s -> 0.26F * (1F - 0.45F * s) + 0.24F * ArmMath.gauss(s - 1F, 0.16F);

	private static void drips(ArmDraw d, SkinMesh m, Static st, float g) {
		float grow = ArmMath.sm((g - 0.5F) / 0.4F);
		if (grow < 0.05F) return;
		d.down(DOWN);
		kit = SHIELD;
		for (int j = 0; j < DRIP_FROM.length; j++) {
			dripBase = j * 2.3F;
			// the length breathes over about 90 ticks between 0.4 and 2.2 px
			float breathe = 0.5F + 0.5F * (float) Math.sin(ArmMath.TAU * time / (90F + 17F * j) + ph + 1.9F * j);
			dripLen = (0.4F + 1.8F * breathe) * grow;
			strandAt(DRIP_FROM[j], 0.5F, BASE);
			Loft.tube(m, DRIP, 0F, 1F, 3, 3, DRIP_R, ArmLayer.BLACK, ArmLayer.BLACK, st, SHIELD.part0 + 6 + j, 2F, 6F, 8F, UP_Y, 1F);
		}
	}

	/** Strand {@code i} of the kit last begun, at {@code s}, into {@code o} (the tests walk them). */
	static void sample(int i, float s, Vector3f o) {
		strandAt(i, s, o);
	}
}
