package dev.morphreborn.client.arm.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.model.ArmTransforms;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.Pulse;
import dev.morphreborn.client.arm.render.SkinMesh;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;

/**
 * The recurve against section 2.11: the bow's own transforms and G frame, a draw read from {@link ArmDraw#bowPull()}
 * that flexes the limbs, pulls the string back 6 px and forms a bolt on it, a string that rings after a shot, the tiers
 * adding detail, every timeline, level of detail and reaction drawing real geometry, and the budgets of section 2.10
 * counted with the bow fully drawn (the bolt is its biggest piece).
 */
class RecurveGeometryTest {
	private static final FormGeometry BOW = ArmGeometry.get(ArmProtocol.BOW);

	private static ArmBudgetTest.Draw bow() {
		return ArmBudgetTest.draw(ArmProtocol.BOW);
	}

	/** {@code d} drawn back to {@code pull}: the record's own constructor, with only the bow's draw changed. */
	private static ArmDraw pulled(ArmDraw d, float pull) {
		return new ArmDraw(d.form(), d.tier(), d.lod(), d.ctx(), d.leftHand(), d.slim(), d.chestArmour(), d.time(), d.seed(), d.state(), d.progress(), d.fromForm(),
				d.swing(), pull, d.mining(), d.biomassFrac(), d.biomassAbs(), d.surge(), d.specialAge(), d.specialKind(), d.hitAge(), d.breakAge(), d.pulses(), d.pulseCount(),
				d.lanternLit(), d.lanternAge(), d.recallLeft(), d.glow(), d.dissolve(), d.drips(), d.ropeNodes(), d.downX(), d.downY(), d.downZ(), d.fpAnchor(), d.light());
	}

	private static ArmDraw build(ArmBudgetTest.Draw b, float pull) {
		return pulled(b.build(), pull);
	}

	private static ArmBudgetTest.Recorder emit(ArmDraw d, ArmLayer layer) {
		ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
		BOW.emit(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
		return r;
	}

	private static int quads(ArmDraw d) {
		return ArmBudgetTest.toolQuads(d);
	}

	/** Whether the two draws' black faces differ in any vertex (thinning changes shapes, not counts). */
	private static boolean differs(ArmDraw a, ArmDraw b) {
		ArmBudgetTest.Recorder ra = new ArmBudgetTest.Recorder(), rb = new ArmBudgetTest.Recorder();
		ra.keep = true;
		rb.keep = true;
		BOW.emit(a, ArmLayer.BLACK, SkinMesh.begin(new PoseStack().last(), ra, 0xF000F0, 0));
		BOW.emit(b, ArmLayer.BLACK, SkinMesh.begin(new PoseStack().last(), rb, 0xF000F0, 0));
		if (ra.kept.size() != rb.kept.size()) return true;
		for (int i = 0; i < ra.kept.size(); i++) {
			for (int k = 0; k < 3; k++) if (Math.abs(ra.kept.get(i)[k] - rb.kept.get(i)[k]) > 1e-4F) return true;
		}
		return false;
	}

	/** The most quads over a long stretch of time, bow drawn to {@code pull}. */
	private static int worst(ArmBudgetTest.Draw b, float pull, boolean sheath) {
		int max = 0;
		for (float t = 0F; t < 420F; t += 3.7F) {
			ArmDraw d = pulled(b.time(t).build(), pull);
			max = Math.max(max, quads(d) + (sheath ? ArmBudgetTest.sheathQuads(d) : 0));
		}
		return max;
	}

	@Test
	void isTheRealBowAndUsesTheBowTransforms() {
		assertTrue(BOW instanceof RecurveGeometry, "ArmGeometry found " + BOW.getClass().getSimpleName());
		assertTrue(ArmGeometry.built(ArmProtocol.BOW));
		assertTrue(BOW.bowTransforms(), "the recurve is drawn with the bow's item transforms and its G frame");
		// the item transform the model picks for the bow's hands is vanilla's bow, not the handheld
		ItemTransform bowRight = ArmTransforms.forContext(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, BOW.bowTransforms());
		ItemTransform handheld = ArmTransforms.forContext(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, false);
		assertTrue(!bowRight.equals(handheld), "the bow's third-person transform differs from the handheld's");
		// its G frame is the grip on the bow sprite's arc, not the sword's grip pixel
		Matrix4f grip = ArmTransforms.bowGrip(new Matrix4f());
		Vector3f o = grip.transformPosition(new Vector3f());
		assertEquals(ArmTransforms.BOW_GRIP_X / 16F, o.x, 1e-6F);
		assertEquals(ArmTransforms.BOW_GRIP_Y / 16F, o.y, 1e-6F);
	}

	@Test
	void limbsRecurveAndTheStringSitsAtTheNock() {
		for (int tier = 1; tier <= 3; tier++) {
			float[] nock = RecurveGeometry.nock(tier);
			assertTrue(nock[0] > 2.5F && nock[0] < 4F, "the nock stands toward the string side: " + nock[0]);
			assertTrue(nock[1] > 8F && nock[1] < 11F, "and out along the limb: " + nock[1]);
			assertTrue(RecurveGeometry.limbArc(tier) > 8F && RecurveGeometry.limbArc(tier) < 12.5F, "limb arc " + RecurveGeometry.limbArc(tier));
		}
		assertTrue(RecurveGeometry.limbArc(1) < RecurveGeometry.limbArc(2) && RecurveGeometry.limbArc(2) < RecurveGeometry.limbArc(3));
		assertEquals(BOW.feedLength(2), RecurveGeometry.phiRiser(RecurveGeometry.RISER_HALF) + RecurveGeometry.limbArc(2), 1e-4F);
	}

	@Test
	void drawingPullsTheStringBackSixPixelsAndFormsABolt() {
		ArmDraw rest = build(bow(), 0F), half = build(bow(), 0.5F), full = build(bow(), 1F);
		Vector3f m0 = BOW.point(rest, ArmTips.MID, new Vector3f()), m1 = BOW.point(full, ArmTips.MID, new Vector3f());
		assertEquals(RecurveGeometry.STRING_PULL, m1.x - m0.x, 1e-3F, "the string comes back 6 px at full draw");
		// nothing on the string at rest; the bolt takes quads once drawn and has formed by a third of the way
		int qRest = quads(rest), qHalf = quads(half), qFull = quads(full);
		assertTrue(qHalf > qRest + 40 && qFull >= qHalf, "the bolt on the string: " + qRest + ", " + qHalf + ", " + qFull);
		assertEquals(qHalf, qFull, 12, "the bolt is formed by half draw");
		// it forms a little at a time (growth by truncation): more of it at 10% than at 2%
		int q2 = quads(build(bow(), 0.02F)), q10 = quads(build(bow(), 0.10F)), q30 = quads(build(bow(), 0.30F));
		assertTrue(q2 <= q10 && q10 <= q30 && q30 > q2, "the bolt forms: " + q2 + ", " + q10 + ", " + q30);
	}

	@Test
	void drawFlexesTheLimbsTowardTheArcher() {
		// the upper limb's strands end further toward +X when drawn (the tip's flexed 1.5 px)
		ArmBudgetTest.Recorder r0 = new ArmBudgetTest.Recorder(), r1 = new ArmBudgetTest.Recorder();
		r0.keep = true;
		r1.keep = true;
		BOW.emit(build(bow(), 0F), ArmLayer.BLACK, SkinMesh.begin(new PoseStack().last(), r0, 0xF000F0, 0));
		BOW.emit(build(bow(), 1F), ArmLayer.BLACK, SkinMesh.begin(new PoseStack().last(), r1, 0xF000F0, 0));
		float max0 = -100F, max1 = -100F;
		for (float[] v : r0.kept) if (v[1] > 8F) max0 = Math.max(max0, v[0]);
		for (float[] v : r1.kept) if (v[1] > 8F) max1 = Math.max(max1, v[0]);
		assertTrue(max1 > max0 + 0.5F, "the upper limb flexes toward the string: " + max0 + " to " + max1);
	}

	@Test
	void stringRingsAfterAShot() {
		// a SPEND pulse a few ticks ago and no draw: the string twangs (it draws differently from the quiet one)
		ArmBudgetTest.Draw shot = bow();
		shot.time(100F);
		shot.pulses = new float[] {Pulse.Kind.SPEND.ordinal(), 97.5F};
		shot.pulseCount = 1;
		ArmBudgetTest.Recorder quiet = new ArmBudgetTest.Recorder(), ringing = new ArmBudgetTest.Recorder();
		quiet.keep = true;
		ringing.keep = true;
		BOW.emit(build(bow().time(100F), 0F), ArmLayer.RED, SkinMesh.begin(new PoseStack().last(), quiet, 0xF000F0, 0));
		BOW.emit(build(shot, 0F), ArmLayer.RED, SkinMesh.begin(new PoseStack().last(), ringing, 0xF000F0, 0));
		assertEquals(quiet.kept.size(), ringing.kept.size());
		float diff = 0F;
		for (int i = 0; i < quiet.kept.size(); i++) diff = Math.max(diff, Math.abs(quiet.kept.get(i)[2] - ringing.kept.get(i)[2]));
		assertTrue(diff > 0.1F, "the ringing string moves off the quiet one by " + diff + " px");
	}

	@Test
	void tiersAddDetail() {
		int l1 = quads(build(bow().tier(1), 0F)), l2 = quads(build(bow().tier(2), 0F)), l3 = quads(build(bow().tier(3), 0F));
		assertTrue(l1 < l2 && l2 < l3, "levels 1, 2, 3: " + l1 + ", " + l2 + ", " + l3);
		assertTrue(emit(build(bow().tier(3), 0F), ArmLayer.GLOW).quads() > 0, "level 3's nocks and core glow");
		assertEquals(0, emit(build(bow().tier(2), 0F), ArmLayer.GLOW).quads(), "level 2 at rest doesn't");
	}

	@Test
	void everyTierLodContextStateAndDrawDrawsRealGeometry() {
		int[] states = {ArmProtocol.STATE_FORMING, ArmProtocol.STATE_FORMED, ArmProtocol.STATE_RESHAPING, ArmProtocol.STATE_RETRACTING, ArmProtocol.STATE_MELTING,
				ArmProtocol.STATE_QUICKFORM, ArmProtocol.STATE_NONE};
		for (int tier = 1; tier <= 3; tier++) {
			for (int lod = 0; lod <= 3; lod++) {
				for (int ctx = 0; ctx <= 2; ctx++) {
					for (int state : states) {
						for (int step = 0; step <= 8; step++) {
							ArmBudgetTest.Draw b = bow().tier(tier).lod(lod).ctx(ctx).state(state, step / 8F).time(40F + step * 1.3F);
							b.fromForm = ArmProtocol.KATANA;
							b.leftHand = (step & 1) == 1;
							quads(pulled(b.build(), (step % 3) * 0.5F));
						}
					}
				}
			}
		}
	}

	@Test
	void growsOutOfTheFistBeforeAnyDetail() {
		ArmDraw early = build(bow().state(ArmProtocol.STATE_FORMING, 3F / 14F).time(50F), 0F);
		ArmDraw done = build(bow().state(ArmProtocol.STATE_FORMING, 1F).time(50F), 0F);
		int q = quads(early);
		assertTrue(q > 0 && q < quads(done) / 3, "FORM tick 3 draws " + q + " of " + quads(done));
		int prev = 0;
		for (int step = 0; step <= 28; step++) {
			int now = quads(build(bow().tier(2).ctx(ArmDraw.CTX_TP).state(ArmProtocol.STATE_FORMING, step / 28F).time(50F), 0F));
			assertTrue(now >= prev - 24, "FORM lost " + (prev - now) + " quads at step " + step);
			prev = Math.max(prev, now);
		}
		assertEquals(0, quads(build(bow().state(ArmProtocol.STATE_NONE, 1F), 1F)));
	}

	@Test
	void reactionsAndLowBiomassDrawReal() {
		ArmBudgetTest.Draw hit = bow();
		hit.hitAge = 2F;
		assertTrue(quads(build(hit, 0F)) > 0);
		for (Pulse.Kind kind : Pulse.Kind.values()) {
			ArmBudgetTest.Draw p = bow();
			p.pulses = new float[] {kind.ordinal(), 98F};
			p.pulseCount = 1;
			assertTrue(quads(build(p, 0.4F)) > 0);
		}
		int full = quads(build(bow(), 0F));
		ArmBudgetTest.Draw thin = bow();
		thin.biomassFrac = 0.2F;
		thin.biomassAbs = 25F;
		assertTrue(differs(build(thin, 0F), build(bow(), 0F)), "thinning shortens the barbs and thins the limbs");
		ArmBudgetTest.Draw starving = bow();
		starving.biomassFrac = 0.08F;
		starving.biomassAbs = 8F;
		assertTrue(emit(build(starving, 0F), ArmLayer.RED).quads() > emit(build(bow(), 0F), ArmLayer.RED).quads(), "starving adds red cracks");
		ArmBudgetTest.Draw failing = bow();
		failing.biomassFrac = 0.01F;
		failing.biomassAbs = 1F;
		assertTrue(quads(build(failing, 0.5F)) > 0);
		for (boolean left : new boolean[] {false, true}) {
			ArmBudgetTest.Draw b = bow().ctx(ArmDraw.CTX_FP);
			b.leftHand = left;
			for (ArmLayer layer : new ArmLayer[] {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW}) emit(build(b, 0.6F), layer);
		}
	}

	@Test
	void guiFitKeepsTheWholeRestPoseInTheSlot() {
		List<Vector3fc> pts = new ArrayList<>();
		BOW.extents(pts::add);
		Matrix4f gui = ArmTransforms.gui(BOW.guiScale(), BOW.guiGripPx(), new Matrix4f());
		Vector3f p = new Vector3f();
		for (Vector3fc e : pts) {
			gui.transformPosition(p.set(e));
			float x = p.x * 16F, y = p.y * 16F;
			assertTrue(x >= 1F && x <= 15F && y >= 1F && y <= 15F, "extent " + e + " lands at (" + x + ", " + y + ") of the 16 px slot");
		}
		// the limb tips at level 3 (the longest) fit as well
		float[] nock = RecurveGeometry.nock(3);
		for (float sy : new float[] {1F, -1F}) {
			gui.transformPosition(p.set(nock[0] + 1F, sy * (RecurveGeometry.RISER_HALF + RecurveGeometry.limbArc(3) * 0.8F), 0F));
			assertTrue(p.x * 16F >= 1F && p.x * 16F <= 15F && p.y * 16F >= 1F && p.y * 16F <= 15F, "limb at (" + p.x * 16F + ", " + p.y * 16F + ")");
		}
	}

	// ------------------------------------------------------------------ budgets (section 2.10), the bow fully drawn

	@Test
	void ownArmFirstPersonStaysUnderItsCeilings() {
		for (int tier = 1; tier <= 3; tier++) {
			int steady = Math.max(worst(bow().tier(tier).ctx(ArmDraw.CTX_FP), 1F, false), worst(bow().tier(tier).ctx(ArmDraw.CTX_FP), 0F, false));
			System.out.printf("bow L%d own FP LOD0 steady: %d%n", tier, steady);
			assertTrue(steady <= 1300, "bow L" + tier + " first person: " + steady + " quads, over 1300");
			assertTrue(steady >= (ArmDraw.FIRST_PERSON_ARM ? 700 : 400), "bow L" + tier + " first person: only " + steady + " quads; the detail is missing (first person is the tool alone)");
		}
	}

	@Test
	void burstsStayUnderTheBurstCeiling() {
		for (int tier = 1; tier <= 3; tier++) {
			int worst = 0;
			for (int step = 0; step <= 56; step++) {
				float p = step / 56F;
				worst = Math.max(worst, quads(build(bow().tier(tier).ctx(ArmDraw.CTX_FP).time(50F + p * 14F).state(ArmProtocol.STATE_FORMING, p), 0F)));
				ArmBudgetTest.Draw b = bow().tier(tier).ctx(ArmDraw.CTX_FP).time(50F + p * 16F).state(ArmProtocol.STATE_RESHAPING, p);
				b.fromForm = ArmProtocol.KATANA;
				ArmDraw d = build(b, 0F);
				worst = Math.max(worst, quads(d) + (d.knotTick() >= 0F ? 8 * 6 : 0));
			}
			System.out.printf("bow L%d own FP burst: %d%n", tier, worst);
			assertTrue(worst <= 1700, "bow L" + tier + " forming or reshaping: " + worst + " quads, over 1700");
		}
	}

	@Test
	void anotherPlayerUpCloseStaysUnderItsCeiling() {
		for (int tier = 1; tier <= 3; tier++) {
			int total = Math.max(worst(bow().tier(tier), 1F, true), worst(bow().tier(tier), 0F, true));
			System.out.printf("bow L%d TP LOD0 tool+sheath: %d%n", tier, total);
			int ceiling = tier >= 3 ? 1300 : 1200;
			assertTrue(total <= ceiling, "bow L" + tier + " third person: " + total + " quads, over " + ceiling);
		}
	}

	@Test
	void levelsOfDetailShed() {
		for (int tier = 1; tier <= 3; tier++) {
			int lod1Tool = worst(bow().tier(tier).lod(1), 1F, false), lod1 = worst(bow().tier(tier).lod(1), 1F, true);
			int lod2 = worst(bow().tier(tier).lod(2), 1F, true), lod3 = worst(bow().tier(tier).lod(3), 1F, true);
			int icon = worst(bow().tier(tier).lod(ArmDraw.LOD_ICON).ctx(ArmDraw.CTX_GUI), 0F, false);
			System.out.printf("bow L%d LOD1 tool %d total %d, LOD2 %d, LOD3 %d, icon %d%n", tier, lod1Tool, lod1, lod2, lod3, icon);
			assertTrue(lod1Tool <= 360, "LOD 1 tool: " + lod1Tool + " (about 300)");
			assertTrue(lod1 <= 576, "LOD 1: " + lod1 + " (about 480)");
			assertTrue(lod2 <= 116, "LOD 2: " + lod2 + " (about 96)");
			assertTrue(lod3 <= 30, "LOD 3: " + lod3 + " (four boxes and one)");
			assertTrue(icon <= 360, "icon: " + icon);
			assertTrue(lod3 < lod2 && lod2 < lod1, "each level of detail should cost less than the one before");
		}
	}

	/**
	 * In your own first-person view the bow is drawn at {@link RecurveGeometry#FP_SCALE} of its size (the rig, 2026-11-16: the limb filled a
	 * third of the screen at full draw): every vertex of the same draw is that share as far from the grip, the recorded points go with
	 * it, and third person and the GUI keep the full size.
	 */
	@Test
	void firstPersonDrawsTheBowSmallerAboutTheGrip() {
		for (float pull : new float[] {0F, 1F}) {
			// no drips: they fall by the world's down, which the two views measure differently (blocksToPx), so they are not the bow's shape
			ArmBudgetTest.Draw tpDraw = bow().ctx(ArmDraw.CTX_TP), fpDraw = bow().ctx(ArmDraw.CTX_FP);
			tpDraw.drips = false;
			fpDraw.drips = false;
			ArmDraw tp = build(tpDraw, pull), fp = build(fpDraw, pull);
			ArmBudgetTest.Recorder a = new ArmBudgetTest.Recorder(), b = new ArmBudgetTest.Recorder();
			a.keep = true;
			b.keep = true;
			BOW.emit(tp, ArmLayer.BLACK, SkinMesh.begin(new PoseStack().last(), a, 0xF000F0, 0));
			BOW.emit(fp, ArmLayer.BLACK, SkinMesh.begin(new PoseStack().last(), b, 0xF000F0, 0));
			assertEquals(a.kept.size(), b.kept.size(), "the same bow, only smaller");
			float spanTp = 0F, spanFp = 0F;
			for (int i = 0; i < a.kept.size(); i++) {
				for (int k = 0; k < 3; k++) {
					assertEquals(RecurveGeometry.FP_SCALE * a.kept.get(i)[k], b.kept.get(i)[k], 1e-3F, "vertex " + i + " axis " + k + " at pull " + pull);
				}
				spanTp = Math.max(spanTp, Math.abs(a.kept.get(i)[1]));
				spanFp = Math.max(spanFp, Math.abs(b.kept.get(i)[1]));
			}
			assertEquals(RecurveGeometry.FP_SCALE, spanFp / spanTp, 1e-3F);
			for (int which : new int[] {ArmTips.TIP, ArmTips.MID, ArmTips.GRIP}) {
				Vector3f world = BOW.point(tp, which, new Vector3f()), hand = BOW.point(fp, which, new Vector3f());
				assertEquals(RecurveGeometry.FP_SCALE * world.x, hand.x, 1e-4F, "point " + which + " x");
				assertEquals(RecurveGeometry.FP_SCALE * world.y, hand.y, 1e-4F, "point " + which + " y");
			}
		}
		// the vanilla bow's span along its diagonal is about 17 px: the drawn bow stays within a third more of that in the hand
		float riser = RecurveGeometry.RISER_HALF, arc = RecurveGeometry.limbArc(2);
		assertTrue(2F * (riser + arc) * RecurveGeometry.FP_SCALE <= 17F * 1.4F, "span " + 2F * (riser + arc) * RecurveGeometry.FP_SCALE);
		ArmDraw gui = build(bow().ctx(ArmDraw.CTX_GUI), 0F);
		assertEquals(1F, RecurveGeometry.viewScale(gui), 0F);
	}
}
