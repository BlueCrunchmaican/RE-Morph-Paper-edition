package dev.morphreborn.client.arm.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.render.ArmMath;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The tendril grips of the shield, spyglass and flint and steel (2026-11-24), by geometry: which surface each arm look gives them, that every
 * strand lies on that surface and ends in its knot (nothing floating, nothing sunk into the arm), that the strands stay off the armour's plates,
 * that neighbouring ports and strands keep clear of each other, and that the armour's plates line up row on row (the middle row hung ajar).
 */
class AccessoryGripTest {
	private static final Vector3f P0 = new Vector3f(), P1 = new Vector3f(), O = new Vector3f();

	/** One arm the accessories can sit on: no tool (the plain arm), or a tool with an arm look. */
	record LookCase(String name, String form, int look, boolean tendrilLike, boolean armour) {}

	static List<LookCase> lookCases() {
		return List.of(new LookCase("plain", "", -1, false, false), new LookCase("tendril", ArmProtocol.KATANA, ArmProtocol.LOOK_ARM, true, false),
				new LookCase("hand", ArmProtocol.KATANA, ArmProtocol.LOOK_HAND, false, false), new LookCase("armour", ArmProtocol.KATANA, ArmProtocol.LOOK_ARMOUR, true, true));
	}

	@AfterEach
	void reset() {
		SheathGeometry.lookOverride = -1;
		ShieldRenderer.classicBands(false);
	}

	/** A draw with a tool formed (or none), on the right arm unless {@code left}. */
	private static ArmDraw draw(String form, boolean slim, boolean chest, boolean left, float time) {
		return AccessoryGolden.build(form, form.isEmpty() ? ArmProtocol.STATE_NONE : ArmProtocol.STATE_FORMED, ArmDraw.CTX_TP, 0, left, slim, chest, time,
				AccessoryGolden.bit(ArmProtocol.SPYGLASS) | AccessoryGolden.bit(ArmProtocol.FLINT), false, false, 500F, false);
	}

	@Test
	void theVariantFollowsWhatTheArmShows() {
		assertEquals(AccessoryGrip.Variant.BARE, AccessoryGrip.variantOf(draw("", false, false, false, 100F)), "no tool: the plain Minecraft arm");
		for (int look = 0; look < ArmProtocol.LOOK_NAMES.size(); look++) {
			SheathGeometry.lookOverride = look;
			AccessoryGrip.Variant v = AccessoryGrip.variantOf(draw(ArmProtocol.KATANA, false, false, false, 100F));
			assertEquals(switch (look) {
				case ArmProtocol.LOOK_HAND -> AccessoryGrip.Variant.HAND;
				case ArmProtocol.LOOK_ARMOUR -> AccessoryGrip.Variant.ARMOUR;
				default -> AccessoryGrip.Variant.TENDRIL;
			}, v, "arm look " + ArmProtocol.LOOK_NAMES.get(look));
			assertEquals(AccessoryGrip.Variant.BARE, AccessoryGrip.variantOf(draw("", false, false, false, 100F)), "and the plain arm again without a tool");
		}
	}

	/** How far {@code p} lies outside the arm's box (px): positive outside, negative in. */
	private static float outside(Vector3f p, float xc, float hx) {
		return Math.max(Math.abs(p.x - xc) - hx, Math.abs(p.z) - 2F);
	}

	/** How far {@code p} lies outside the tendril arm's outer edge at its height (px): the envelope of {@link SheathGeometry#tendrilEnvelope}. */
	private static float tendrilLift(Vector3f p, float xc, float hx, boolean chest, boolean armour) {
		float reach = SheathGeometry.tendrilReach(armour, p.y);
		float lift = chest ? AccessoryGrip.CHEST : 0F;
		float ex = SheathGeometry.tendrilEnvelope(hx + lift, reach), ez = SheathGeometry.tendrilEnvelope(2F + lift, reach);
		return Math.max(Math.abs(p.x - xc) - ex, Math.abs(p.z) - ez);
	}

	@Test
	void everyStrandLiesOnTheArmAndEndsInItsKnot() {
		for (LookCase look : lookCases()) {
			SheathGeometry.lookOverride = look.look;
			for (boolean left : new boolean[] {false, true}) {
				for (boolean slim : new boolean[] {false, true}) {
					for (boolean chest : new boolean[] {false, true}) {
						for (float time : new float[] {100F, 137F, 211F}) {
							ArmDraw d = draw(look.form, slim, chest, left, time);
							float out = left ? 1F : -1F;
							AccessoryGrip.Variant v = AccessoryGrip.variantOf(d);
							float xc = AccessoryGrip.armCentre(d, out), hx = slim ? 1.5F : 2F;
							for (AccessoryGrip.Kit kit : new AccessoryGrip.Kit[] {AccessoryGrip.SPYGLASS, AccessoryGrip.FLINT}) {
								P1.set(xc + out * hx, 0F, 0F);
								if (kit == AccessoryGrip.SPYGLASS) AccessoryGrip.spyglassPort(d, out, v, P0, P1);
								else AccessoryGrip.flintPort(d, out, v, P0, P1);
								AccessoryGrip.begin(d, kit, out, v, P0, 0F);
								String where = look.name + (left ? " left" : " right") + (slim ? " slim" : " wide") + (chest ? " chest" : "") + " "
										+ (kit == AccessoryGrip.SPYGLASS ? "spyglass" : "flint");
								for (int i = 0; i < kit.count; i++) {
									AccessoryGrip.sample(i, 1F, O);
									assertTrue(O.distance(P0) < 0.05F, where + " strand " + i + " ends in its knot, got " + O + " for " + P0);
									for (float s = 0F; s < 0.78F; s += 0.04F) {
										AccessoryGrip.sample(i, s, O);
										assertTrue(Float.isFinite(O.x + O.y + O.z), where);
										if (!look.tendrilLike) {
											// on the plain arm: not past the skin box (the tip dips into the sleeve layer, 0.25 thick, and is hidden there) and not floating
											float lift = outside(O, xc, hx);
											assertTrue(lift >= -0.05F, where + " strand " + i + " s " + s + " sunk into the arm: " + lift);
											assertTrue(lift <= AccessoryGrip.MARGIN + (chest ? AccessoryGrip.CHEST : 0F) + 0.7F,
													where + " strand " + i + " s " + s + " floats: " + lift);
										} else {
											// on the tendril arm: outside its strands' middle line, not floating beyond the ride and a strand
											float lift = tendrilLift(O, xc, hx, chest, look.armour);
											assertTrue(lift >= -0.3F, where + " strand " + i + " s " + s + " is inside the arm's tendrils: " + lift);
											assertTrue(lift <= AccessoryGrip.RIDE + 0.9F, where + " strand " + i + " s " + s + " floats: " + lift);
										}
									}
								}
							}
						}
					}
				}
			}
		}
	}

	@Test
	void theArmoursFlintStrandsStayOffThePlates() {
		SheathGeometry.lookOverride = ArmProtocol.LOOK_ARMOUR;
		for (boolean left : new boolean[] {false, true}) {
			ArmDraw d = draw(ArmProtocol.KATANA, false, false, left, 100F);
			float out = left ? 1F : -1F;
			P1.set(0F, 0F, 0F);
			AccessoryGrip.flintPort(d, out, AccessoryGrip.Variant.ARMOUR, P0, P1);
			AccessoryGrip.begin(d, AccessoryGrip.FLINT, out, AccessoryGrip.Variant.ARMOUR, P0, 0F);
			float plateLo = SheathGeometry.plateY(0) - SheathGeometry.plateHeight() / 2F;
			float plateHi = SheathGeometry.plateY(SheathGeometry.plateRows() - 1) + SheathGeometry.plateHeight() / 2F;
			float a0 = SheathGeometry.plateAngle(0, 0) - 0.2F * ArmMath.PI, a1 = SheathGeometry.plateAngle(0, SheathGeometry.plateColumns() - 1) + 0.2F * ArmMath.PI;
			for (int i = 0; i < AccessoryGrip.FLINT.count; i++) {
				for (float s = 0F; s < 0.78F; s += 0.03F) {
					AccessoryGrip.sample(i, s, O);
					if (O.y < plateLo + 0.2F || O.y > plateHi - 0.2F) continue;
					float a = ArmMath.frac(AccessoryGrip.angleOf(O.x, O.z) / ArmMath.TAU) * ArmMath.TAU;
					assertFalse(a > a0 && a < a1, "flint strand " + i + " at s " + s + " lies over the plates (angle " + a + ", y " + O.y + ")");
				}
			}
		}
	}

	@Test
	void theStalksRootsKeepClearOfEachOtherAndOfTheLanterns() {
		for (LookCase look : lookCases()) {
			SheathGeometry.lookOverride = look.look;
			ArmDraw d = draw(look.form, false, false, false, 100F);
			AccessoryGrip.Variant v = AccessoryGrip.variantOf(d);
			Vector3f spy = new Vector3f(), fl = new Vector3f();
			P1.set(-3F, 0F, 0F);
			AccessoryGrip.spyglassPort(d, -1F, v, spy, P1);
			P1.set(-3F, 0F, 0F);
			AccessoryGrip.flintPort(d, -1F, v, fl, P1);
			// the knots are 1.05 to 1.45 across: their ports keep more than that apart, and from the lantern's (its root: y 4.4 on its own, y 5 on a tool's
			// arm, or just under the top plate)
			assertTrue(spy.distance(fl) > 2.1F, look.name + ": spyglass and flint ports " + spy.distance(fl) + " apart");
			Vector3f lantern = look.form.isEmpty() ? new Vector3f(-2.9F, 4.4F, 0F)
					: look.armour ? new Vector3f(-1.5F, SheathGeometry.armourTopY() + 0.4F, -0.3F) : new Vector3f(-2.6F, 5F, -0.6F);
			assertTrue(fl.distance(lantern) > 1.9F, look.name + ": flint and lantern ports " + fl.distance(lantern) + " apart");
		}
	}

	@Test
	void strandsOfNeighbouringGripsKeepClear() {
		// the spyglass's and the flint's strands, away from the knots, never come closer than the thin parts of their tubes allow
		for (LookCase look : lookCases()) {
			SheathGeometry.lookOverride = look.look;
			AccessoryGrip.Variant v = AccessoryGrip.variantOf(draw(look.form, false, false, false, 100F));
			float worst = Float.MAX_VALUE;
			for (float time : new float[] {100F, 123F, 160F, 199F, 250F}) {
				ArmDraw dt = draw(look.form, false, false, false, time);
				List<Vector3f> spy = strandPoints(dt, AccessoryGrip.SPYGLASS, v), fl = strandPoints(dt, AccessoryGrip.FLINT, v);
				for (Vector3f a : spy) {
					for (Vector3f b : fl) worst = Math.min(worst, a.distance(b));
				}
			}
			assertTrue(worst > 0.35F, look.name + ": strands of the spyglass and flint grips come within " + worst);
		}
	}

	private static List<Vector3f> strandPoints(ArmDraw d, AccessoryGrip.Kit kit, AccessoryGrip.Variant v) {
		Vector3f p0 = new Vector3f(), p1 = new Vector3f(-3F, 0F, 0F);
		if (kit == AccessoryGrip.SPYGLASS) AccessoryGrip.spyglassPort(d, -1F, v, p0, p1);
		else AccessoryGrip.flintPort(d, -1F, v, p0, p1);
		AccessoryGrip.begin(d, kit, -1F, v, p0, 0F);
		List<Vector3f> out = new ArrayList<>();
		for (int i = 0; i < kit.count; i++) {
			for (float s = 0.02F; s < 0.7F; s += 0.04F) {
				Vector3f p = new Vector3f();
				AccessoryGrip.sample(i, s, p);
				out.add(p);
			}
		}
		return out;
	}

	@Test
	void everyStrandHasItsOwnTurnsDirectionAndEndAndTheyAlternate() {
		for (AccessoryGrip.Kit kit : new AccessoryGrip.Kit[] {AccessoryGrip.SPYGLASS, AccessoryGrip.FLINT, AccessoryGrip.SHIELD}) {
			for (int i = 0; i < kit.count; i++) {
				for (int j = i + 1; j < kit.count; j++) {
					assertTrue(Math.abs(Math.abs(kit.turns[i]) - Math.abs(kit.turns[j])) > 0.04F || Math.abs(kit.yTip[i] - kit.yTip[j]) > 0.2F, "strands " + i + " and " + j + " differ");
					assertTrue(Math.abs(kit.endAngle[i] - kit.endAngle[j]) > 0.05F, "their ends are not stacked: " + i + " and " + j);
				}
				if (i > 0) assertTrue(Math.signum(kit.turns[i]) != Math.signum(kit.turns[i - 1]), "alternating directions, so they cross like a braid");
				assertTrue(Math.abs(kit.turns[i]) >= 0.5F && Math.abs(kit.turns[i]) <= 1.35F, "0.5 to 1.35 turns");
			}
			assertEquals(2, kit.red, "one red strand");
		}
	}

	// ------------------------------------------------------------------ job 1: on First-person Model's body

	/** The farthest any vertex of {@code id}'s third-person draw is from the point (x, y, 0) of arm space, with the accessory view as {@code near}. */
	private static float far(String id, float near, boolean back, float pivotY, boolean raised) {
		ArmDraw d = AccessoryGolden.build("", ArmProtocol.STATE_NONE, ArmDraw.CTX_TP, 0, false, false, false, 100F, AccessoryGolden.bit(id), raised, false, 500F, false);
		float xc = id.equals(ArmProtocol.SHIELD) ? 1F : -1F;
		AccessoryView.set(near, back);
		float far = 0F;
		try {
			for (dev.morphreborn.client.arm.render.ArmLayer layer : new dev.morphreborn.client.arm.render.ArmLayer[] {dev.morphreborn.client.arm.render.ArmLayer.BLACK,
					dev.morphreborn.client.arm.render.ArmLayer.RED}) {
				ArmBudgetTest.Recorder rec = new ArmBudgetTest.Recorder();
				rec.keep = true;
				dev.morphreborn.client.arm.render.SkinMesh m = dev.morphreborn.client.arm.render.SkinMesh.begin(new com.mojang.blaze3d.vertex.PoseStack().last(), rec, 0xF000F0, 0);
				dev.morphreborn.client.arm.render.Loft.pulseFrom(null, 0F);
				ArmGeometry.accessory(id).emitThirdPerson(d, layer, m);
				for (float[] p : rec.kept) far = Math.max(far, new Vector3f(xc, pivotY, 0F).distance(p[0], p[1], p[2]));
			}
		} finally {
			AccessoryView.set(1F, false);
		}
		return far;
	}

	@Test
	void nearTheLensTheFreePartShrinksAboutItsPivot() {
		ShieldMotion.clear();
		float shield = far(ArmProtocol.SHIELD, 1F, false, 9.8F, true), shieldNear = far(ArmProtocol.SHIELD, 0.5F, true, 9.8F, true);
		assertTrue(shieldNear < 0.75F * shield, "the shield's free part drawn at half: " + shieldNear + " against " + shield);
		float spy = far(ArmProtocol.SPYGLASS, 1F, false, 0.9F, false), spyNear = far(ArmProtocol.SPYGLASS, 0.5F, true, 0.9F, false);
		assertTrue(spyNear < 0.75F * spy, "the spyglass's stalk and eye at half: " + spyNear + " against " + spy);
		float fl = far(ArmProtocol.FLINT, 1F, false, 2.2F, false), flNear = far(ArmProtocol.FLINT, 0.5F, true, 2.2F, false);
		assertTrue(flNear < 0.75F * fl, "the striker's stalk and knuckles at half: " + flNear + " against " + fl);
		assertEquals(shield, far(ArmProtocol.SHIELD, 1F, false, 9.8F, true), 0F, "the same view twice is the same draw");
	}

	// ------------------------------------------------------------------ job 6: the armour's plates

	@Test
	void theArmoursPlatesLineUpRowOnRow() {
		for (int c = 0; c < SheathGeometry.plateColumns(); c++) {
			for (int row = 1; row < SheathGeometry.plateRows(); row++) {
				assertEquals(SheathGeometry.plateAngle(0, c), SheathGeometry.plateAngle(row, c), 0F,
						"column " + c + ", row " + row + ": the middle row hung ajar (a 0.12 pi stagger) until 2026-11-24");
			}
		}
		for (int row = 1; row < SheathGeometry.plateRows(); row++) {
			assertEquals(1.5F, SheathGeometry.plateY(row) - SheathGeometry.plateY(row - 1), 1e-4F, "the rows' spacing is even");
		}
	}
}
