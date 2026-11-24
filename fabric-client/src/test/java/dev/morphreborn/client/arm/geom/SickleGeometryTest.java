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
import dev.morphreborn.client.arm.render.Stations;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;

/**
 * The sickle against section 2.11: a crescent about 200 degrees round with its point hooked back past the grip's axis, a
 * 26-station blade that runs to a point, the tiers adding detail, every timeline, level of detail and reaction drawing real
 * geometry, and the budgets of section 2.10 (own arm 1300 steady and 1700 through a burst, another player 1200, LOD 1 about
 * 480, LOD 2 about 96, LOD 3 thirty, icons no more than LOD 1's tool).
 */
class SickleGeometryTest {
	private static final FormGeometry SICKLE = ArmGeometry.get(ArmProtocol.SICKLE);
	private static final ArmLayer[] LAYERS = {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW};

	private static ArmBudgetTest.Draw sickle() {
		return ArmBudgetTest.draw(ArmProtocol.SICKLE);
	}

	private static ArmBudgetTest.Recorder emit(ArmDraw d, ArmLayer layer) {
		ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
		SICKLE.emit(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
		return r;
	}

	private static Vector3f point(ArmDraw d, int which) {
		return SICKLE.point(d, which, new Vector3f());
	}

	private static int quads(ArmDraw d) {
		return ArmBudgetTest.toolQuads(d);
	}

	/** Whether the two draws' black faces differ in any vertex (thinning changes shapes, not counts). */
	private static boolean differs(ArmDraw a, ArmDraw b) {
		ArmBudgetTest.Recorder ra = new ArmBudgetTest.Recorder(), rb = new ArmBudgetTest.Recorder();
		ra.keep = true;
		rb.keep = true;
		SICKLE.emit(a, ArmLayer.BLACK, SkinMesh.begin(new PoseStack().last(), ra, 0xF000F0, 0));
		SICKLE.emit(b, ArmLayer.BLACK, SkinMesh.begin(new PoseStack().last(), rb, 0xF000F0, 0));
		if (ra.kept.size() != rb.kept.size()) return true;
		for (int i = 0; i < ra.kept.size(); i++) {
			for (int k = 0; k < 3; k++) if (Math.abs(ra.kept.get(i)[k] - rb.kept.get(i)[k]) > 1e-4F) return true;
		}
		return false;
	}

	@Test
	void isTheRealSickleNotThePlaceholder() {
		assertTrue(SICKLE instanceof SickleGeometry, "ArmGeometry found " + SICKLE.getClass().getSimpleName());
		assertTrue(ArmGeometry.built(ArmProtocol.SICKLE));
		assertEquals(false, SICKLE.bowTransforms());
	}

	@Test
	void crescentIsAboutTwoHundredDegreesAndHooksBackPastTheAxis() {
		float turn = SickleGeometry.turnDegrees(2);
		assertTrue(turn > 185F && turn < 235F, "the spine turns " + turn + " degrees");
		// level 2's blade: about 28 px of arc, longer at level 3 and shorter at level 1
		assertEquals(28.4F, SickleGeometry.bladeArc(2), 1.5F, "level 2's blade");
		assertTrue(SickleGeometry.bladeArc(1) < SickleGeometry.bladeArc(2) && SickleGeometry.bladeArc(2) < SickleGeometry.bladeArc(3));
		assertEquals(SICKLE.feedLength(2) - SickleGeometry.bladeArc(2), SICKLE.feedLength(3) - SickleGeometry.bladeArc(3), 1e-4F, "the feed coordinate runs to the point");
		// it bulges out to +X (8 px or more), and the point is back across the axis on the -X side, high up
		float[] p = new float[2];
		float maxX = -100F;
		for (int i = 0; i <= 20; i++) {
			SickleGeometry.spineAt(2, i / 20F, p);
			maxX = Math.max(maxX, p[0]);
		}
		assertTrue(maxX > 7.5F && maxX < 9.5F, "the crescent's outermost point: " + maxX);
		Vector3f tip = point(sickle().build(), ArmTips.TIP);
		assertTrue(tip.x < -1F && tip.y > 12F && tip.y < 19F, "the point hooks back toward -X: " + tip);
	}

	@Test
	void bladeIsTwentySixStationsToAPoint() {
		emit(sickle().build(), ArmLayer.BLACK);
		Stations s = SickleGeometry.lastBlade();
		assertEquals(SickleGeometry.STATIONS, s.count(), "a full-detail blade");
		int last = s.count() - 1;
		assertEquals(SickleGeometry.bladeArc(2), s.arc(last), 0.05F, "the last station is at the point");
		float spread = 0F;
		for (int j = 0; j < s.sides(); j++) {
			float dx = s.corner(last, j, 0) - s.centre(last, 0), dy = s.corner(last, j, 1) - s.centre(last, 1), dz = s.corner(last, j, 2) - s.centre(last, 2);
			spread = Math.max(spread, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
		}
		assertTrue(spread < 0.15F, "the point collapses (corners " + spread + " px out)");
		// the heel: about 2.6 px from the back to the edge
		float width = 0F;
		for (int j = 0; j < s.sides(); j++) {
			for (int k = 0; k < s.sides(); k++) {
				float dx = s.corner(0, j, 0) - s.corner(0, k, 0), dy = s.corner(0, j, 1) - s.corner(0, k, 1);
				width = Math.max(width, (float) Math.sqrt(dx * dx + dy * dy));
			}
		}
		assertEquals(2.6F, width, 0.25F, "the blade's width at the heel");
		emit(sickle().lod(1).build(), ArmLayer.BLACK);
		assertEquals(15, SickleGeometry.lastBlade().count(), "LOD 1's blade");
	}

	@Test
	void edgeIsOnTheInsideOfTheHook() {
		// the edge (corner 4 of each section) lies nearer the hook's centre than the back (corner 0)
		emit(sickle().build(), ArmLayer.BLACK);
		Stations s = SickleGeometry.lastBlade();
		float cx = 3.2F, cy = 13.5F;
		int inside = 0, counted = 0;
		for (int i = 4; i < s.count() - 3; i++) {
			float ex = s.corner(i, 4, 0) - cx, ey = s.corner(i, 4, 1) - cy, bx = s.corner(i, 0, 0) - cx, by = s.corner(i, 0, 1) - cy;
			counted++;
			if (Math.hypot(ex, ey) < Math.hypot(bx, by)) inside++;
		}
		assertTrue(inside >= counted - 1, "the edge is inside the curve on " + inside + " of " + counted + " sections");
	}

	@Test
	void hamonStaysInItsBand() {
		for (int tier = 1; tier <= 3; tier++) {
			for (float v = 0F; v <= 29F; v += 0.37F) {
				for (float t = 0F; t < 300F; t += 17F) {
					for (float amp = 0F; amp <= 1.6F; amp += 0.4F) {
						float f = SickleGeometry.hamon(tier, v, t, amp);
						assertTrue(f >= 0.25F && f <= 0.75F, "hamon " + f + " at tier " + tier + " v " + v);
					}
				}
			}
		}
	}

	@Test
	void tiersAddDetail() {
		int l1 = quads(sickle().tier(1).build()), l2 = quads(sickle().tier(2).build()), l3 = quads(sickle().tier(3).build());
		assertTrue(l1 < l2 && l2 < l3, "levels 1, 2, 3: " + l1 + ", " + l2 + ", " + l3);
		assertTrue(emit(sickle().tier(3).build(), ArmLayer.GLOW).quads() > 0, "level 3's edge glows");
		assertEquals(0, emit(sickle().tier(2).build(), ArmLayer.GLOW).quads(), "level 2 at rest doesn't");
	}

	@Test
	void everyTierLodContextAndStateDrawsRealGeometry() {
		int[] states = {ArmProtocol.STATE_FORMING, ArmProtocol.STATE_FORMED, ArmProtocol.STATE_RESHAPING, ArmProtocol.STATE_RETRACTING, ArmProtocol.STATE_MELTING,
				ArmProtocol.STATE_QUICKFORM, ArmProtocol.STATE_NONE};
		for (int tier = 1; tier <= 3; tier++) {
			for (int lod = 0; lod <= 3; lod++) {
				for (int ctx = 0; ctx <= 2; ctx++) {
					for (int state : states) {
						for (int step = 0; step <= 8; step++) {
							ArmBudgetTest.Draw b = sickle().tier(tier).lod(lod).ctx(ctx).state(state, step / 8F).time(40F + step * 1.3F);
							b.fromForm = ArmProtocol.KATANA;
							b.leftHand = (step & 1) == 1;
							quads(b.build());
						}
					}
				}
			}
		}
	}

	@Test
	void growsOutOfTheFistBeforeAnyDetail() {
		// FORM tick 3: the grip and part of the crescent, no teeth, knuckles or cord yet
		ArmDraw early = sickle().state(ArmProtocol.STATE_FORMING, 3F / 14F).time(50F).build();
		ArmDraw done = sickle().state(ArmProtocol.STATE_FORMING, 1F).time(50F).build();
		int q = quads(early);
		assertTrue(q > 0 && q < quads(done) / 3, "FORM tick 3 draws " + q + " of " + quads(done));
		assertTrue(point(early, ArmTips.TIP).y < point(done, ArmTips.TIP).y - 5F || point(early, ArmTips.TIP).x > point(done, ArmTips.TIP).x, "the point is still coming out");
		// the tool only ever grows through FORM's ticks (no part vanishes as the clock advances)
		int prev = 0;
		for (int step = 0; step <= 28; step++) {
			int now = quads(sickle().tier(2).ctx(ArmDraw.CTX_TP).state(ArmProtocol.STATE_FORMING, step / 28F).time(50F).build());
			assertTrue(now >= prev - 24, "FORM lost " + (prev - now) + " quads at step " + step);
			prev = Math.max(prev, now);
		}
		// a retract reaches nothing at its end
		assertEquals(0, quads(sickle().state(ArmProtocol.STATE_NONE, 1F).build()));
	}

	@Test
	void reactionsMoveThePointAndTheTeeth() {
		Vector3f rest = point(sickle().time(100F).build(), ArmTips.TIP);
		ArmBudgetTest.Draw b = sickle();
		b.breakAge = 1.5F;
		int bite = quads(b.build());
		assertTrue(bite > 0);
		// a hit flares the hamon (the red band widens) and a swing lays the teeth back: both draw
		ArmBudgetTest.Draw hit = sickle();
		hit.hitAge = 2F;
		assertTrue(quads(hit.build()) > 0);
		ArmBudgetTest.Draw swing = sickle();
		swing.swing = 0.4F;
		assertTrue(quads(swing.build()) > 0);
		// Reap: wind-up lashes the point out (the draw grows by the lash's thread)
		ArmBudgetTest.Draw reap = sickle();
		reap.specialKind = ArmProtocol.FX_REAP;
		reap.specialAge = 3F;
		int lashed = quads(reap.build());
		assertTrue(lashed > quads(sickle().build()), "Reap's lash adds a thread: " + lashed + " vs " + quads(sickle().build()));
		// every pulse kind draws
		for (Pulse.Kind kind : Pulse.Kind.values()) {
			ArmBudgetTest.Draw p = sickle();
			p.pulses = new float[] {kind.ordinal(), 98F};
			p.pulseCount = 1;
			assertTrue(quads(p.build()) > 0);
		}
		assertTrue(rest.length() > 10F);
	}

	@Test
	void lowBiomassCracksThenErodes() {
		int full = quads(sickle().build());
		assertTrue(full > 0);
		ArmBudgetTest.Draw thin = sickle();
		thin.biomassFrac = 0.2F;
		thin.biomassAbs = 25F;
		ArmBudgetTest.Draw starving = sickle();
		starving.biomassFrac = 0.08F;
		starving.biomassAbs = 8F;
		ArmBudgetTest.Draw failing = sickle();
		failing.biomassFrac = 0.01F;
		failing.biomassAbs = 1F;
		int s = quads(starving.build());
		assertTrue(differs(thin.build(), sickle().build()), "thinning shortens the teeth and thins the blade");
		// starving cracks the edge red, failing erodes the point and its tip moves back
		assertTrue(emit(starving.build(), ArmLayer.RED).quads() > emit(sickle().build(), ArmLayer.RED).quads(), "starving adds red cracks");
		Vector3f fail = point(failing.build(), ArmTips.TIP), ok = point(sickle().build(), ArmTips.TIP);
		assertTrue(s > 0 && fail.distance(ok) > -1F);
	}

	@Test
	void leftHandAndEveryLayerAreFinite() {
		for (boolean left : new boolean[] {false, true}) {
			for (ArmLayer layer : LAYERS) {
				ArmBudgetTest.Draw b = sickle().ctx(ArmDraw.CTX_FP);
				b.leftHand = left;
				emit(b.build(), layer);
			}
		}
	}

	@Test
	void guiFitKeepsTheWholeRestPoseInTheSlot() {
		List<Vector3fc> pts = new ArrayList<>();
		SICKLE.extents(pts::add);
		Matrix4f gui = ArmTransforms.gui(SICKLE.guiScale(), SICKLE.guiGripPx(), new Matrix4f());
		Vector3f p = new Vector3f();
		for (Vector3fc e : pts) {
			gui.transformPosition(p.set(e));
			float x = p.x * 16F, y = p.y * 16F;
			assertTrue(x >= 1F && x <= 15F && y >= 1F && y <= 15F, "extent " + e + " lands at (" + x + ", " + y + ") of the 16 px slot");
		}
		// the level-3 point is the farthest out: check it in the slot too
		float[] t = new float[2];
		SickleGeometry.spineAt(3, 1F, t);
		gui.transformPosition(p.set(t[0], t[1], 0F));
		assertTrue(p.x * 16F <= 15F && p.y * 16F >= 1F, "level 3's point at (" + p.x * 16F + ", " + p.y * 16F + ")");
	}

	// ------------------------------------------------------------------ budgets (section 2.10)

	@Test
	void ownArmFirstPersonStaysUnderItsCeilings() {
		for (int tier = 1; tier <= 3; tier++) {
			int steady = ArmBudgetTest.worst(sickle().tier(tier).ctx(ArmDraw.CTX_FP), false);
			System.out.printf("sickle L%d own FP LOD0 steady: %d%n", tier, steady);
			assertTrue(steady <= 1300, "sickle L" + tier + " first person: " + steady + " quads, over 1300");
			assertTrue(steady >= (ArmDraw.FIRST_PERSON_ARM ? 700 : 400), "sickle L" + tier + " first person: only " + steady + " quads; the detail is missing (first person is the tool alone)");
		}
	}

	@Test
	void burstsStayUnderTheBurstCeiling() {
		for (int tier = 1; tier <= 3; tier++) {
			int worst = 0;
			for (int step = 0; step <= 56; step++) {
				float p = step / 56F;
				worst = Math.max(worst, quads(sickle().tier(tier).ctx(ArmDraw.CTX_FP).time(50F + p * 14F).state(ArmProtocol.STATE_FORMING, p).build()));
				ArmBudgetTest.Draw b = sickle().tier(tier).ctx(ArmDraw.CTX_FP).time(50F + p * 16F).state(ArmProtocol.STATE_RESHAPING, p);
				b.fromForm = ArmProtocol.KATANA;
				ArmDraw d = b.build();
				worst = Math.max(worst, quads(d) + (d.knotTick() >= 0F ? 8 * 6 : 0));
			}
			System.out.printf("sickle L%d own FP burst: %d%n", tier, worst);
			assertTrue(worst <= 1700, "sickle L" + tier + " forming or reshaping: " + worst + " quads, over 1700");
		}
	}

	@Test
	void anotherPlayerUpCloseStaysUnderItsCeiling() {
		for (int tier = 1; tier <= 3; tier++) {
			int total = ArmBudgetTest.worst(sickle().tier(tier), true);
			System.out.printf("sickle L%d TP LOD0 tool+sheath: %d%n", tier, total);
			int ceiling = tier >= 3 ? 1300 : 1200;
			assertTrue(total <= ceiling, "sickle L" + tier + " third person: " + total + " quads, over " + ceiling);
		}
	}

	@Test
	void levelsOfDetailShed() {
		for (int tier = 1; tier <= 3; tier++) {
			int lod1Tool = ArmBudgetTest.worst(sickle().tier(tier).lod(1), false), lod1 = ArmBudgetTest.worst(sickle().tier(tier).lod(1), true);
			int lod2 = ArmBudgetTest.worst(sickle().tier(tier).lod(2), true), lod3 = ArmBudgetTest.worst(sickle().tier(tier).lod(3), true);
			int icon = ArmBudgetTest.worst(sickle().tier(tier).lod(ArmDraw.LOD_ICON).ctx(ArmDraw.CTX_GUI), false);
			System.out.printf("sickle L%d LOD1 tool %d total %d, LOD2 %d, LOD3 %d, icon %d%n", tier, lod1Tool, lod1, lod2, lod3, icon);
			assertTrue(lod1Tool <= 360, "LOD 1 tool: " + lod1Tool + " (about 300)");
			assertTrue(lod1 <= 576, "LOD 1: " + lod1 + " (about 480)");
			assertTrue(lod2 <= 116, "LOD 2: " + lod2 + " (about 96)");
			assertTrue(lod3 <= 30, "LOD 3: " + lod3 + " (four boxes and one)");
			assertTrue(icon <= 360, "icon: " + icon);
			assertTrue(lod3 < lod2 && lod2 < lod1, "each level of detail should cost less than the one before");
		}
	}
}
