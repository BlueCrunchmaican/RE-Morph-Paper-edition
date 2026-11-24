package dev.morphreborn.client.arm.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.model.ArmTransforms;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.SkinMesh;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;

/**
 * The claws (my design, 2026-11-16): three or four curved talons of braided tendril standing over the knuckles, their points
 * hardened to red static. It is the real geometry (not the placeholder), the tiers differ in what they carry, every timeline, tier and level
 * of detail draws real geometry (no NaN, no zero-length normals, growing by truncation), Rake opens and straightens the talons, and it fits
 * its GUI slot.
 */
class ClawsGeometryTest {
	private static final FormGeometry CLAWS = ArmGeometry.get(ArmProtocol.CLAWS);
	private static final ArmLayer[] LAYERS = {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW};

	private static ArmBudgetTest.Draw claws() {
		return ArmBudgetTest.draw(ArmProtocol.CLAWS);
	}

	private static ArmBudgetTest.Recorder emit(ArmDraw d, ArmLayer layer, boolean keep) {
		ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
		r.keep = keep;
		CLAWS.emit(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
		return r;
	}

	private static int quads(ArmDraw d, ArmLayer layer) {
		return emit(d, layer, false).quads();
	}

	private static int allQuads(ArmDraw d) {
		int n = 0;
		for (ArmLayer l : LAYERS) n += quads(d, l);
		return n;
	}

	/** The extremes of everything drawn, as {minX, maxX, minY, maxY, minZ, maxZ}. */
	private static float[] bounds(ArmDraw d) {
		float[] b = {Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE};
		for (ArmLayer l : LAYERS) {
			for (float[] v : emit(d, l, true).kept) {
				for (int a = 0; a < 3; a++) {
					b[2 * a] = Math.min(b[2 * a], v[a]);
					b[2 * a + 1] = Math.max(b[2 * a + 1], v[a]);
				}
			}
		}
		return b;
	}

	private static ArmBudgetTest.Draw dry(ArmBudgetTest.Draw d) {
		d.drips = false;
		return d;
	}

	/** The widest the talons stand across (x and z together) above the cuff. */
	private static float fanWidth(ArmDraw d) {
		float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
		for (ArmLayer l : LAYERS) {
			for (float[] v : emit(d, l, true).kept) {
				if (v[1] < ClawsGeometry.CUFF_Y + 3F) continue;
				float side = v[0] - 0.7F * v[2];
				lo = Math.min(lo, side);
				hi = Math.max(hi, side);
			}
		}
		return hi - lo;
	}

	@Test
	void isTheRealClawsNotThePlaceholder() {
		assertTrue(CLAWS instanceof ClawsGeometry, "ArmGeometry found " + CLAWS.getClass().getSimpleName());
		assertTrue(ArmGeometry.built(ArmProtocol.CLAWS));
		assertEquals(ArmProtocol.CLAWS, CLAWS.form());
		assertFalse(CLAWS.bowTransforms());
	}

	@Test
	void theTiersDifferInWhatTheyCarry() {
		assertEquals(3, ClawsGeometry.talonCount(1), "Raw has three talons");
		assertEquals(4, ClawsGeometry.talonCount(2));
		assertEquals(4, ClawsGeometry.talonCount(3));
		for (int t = 1; t < 3; t++) {
			assertTrue(ClawsGeometry.talonLength(t) < ClawsGeometry.talonLength(t + 1), "talons grow, tier " + t);
			assertTrue(ClawsGeometry.spurCount(t) < ClawsGeometry.spurCount(t + 1), "heel spurs, tier " + t);
			assertTrue(ClawsGeometry.cordCount(t) < ClawsGeometry.cordCount(t + 1), "cords, tier " + t);
			assertTrue(CLAWS.feedLength(t) < CLAWS.feedLength(t + 1), "the feed coordinate runs to the points");
			int a = allQuads(claws().tier(t).build()), b = allQuads(claws().tier(t + 1).build());
			assertTrue(a < b, "quads grow with the tier: " + a + ", " + b);
		}
		assertEquals(1, ClawsGeometry.strandCount(1));
		assertEquals(2, ClawsGeometry.strandCount(2));
		// level 3's points glow (the glow layer carries them); level 1's don't until a hit or a rake lights them
		assertTrue(quads(claws().tier(3).build(), ArmLayer.GLOW) > quads(claws().tier(1).build(), ArmLayer.GLOW));
	}

	@Test
	void theTalonsCurlToTheLeadingSideAndFanOutWithoutMeeting() {
		for (int tier = 1; tier <= 3; tier++) {
			int n = ClawsGeometry.talonCount(tier);
			Vector3f[] tips = new Vector3f[n];
			for (int slot = 0; slot < n; slot++) {
				tips[slot] = ClawsGeometry.restTip(tier, slot, new Vector3f());
				// out of the cuff, standing up the grip's axis and bent toward -X (the knuckles' side, where a swing leads)
				assertTrue(tips[slot].y > ClawsGeometry.CUFF_Y + 0.5F * ClawsGeometry.talonLength(tier), "tier " + tier + " talon " + slot + " reaches forward: " + tips[slot]);
				assertTrue(tips[slot].x < -0.5F, "tier " + tier + " talon " + slot + " curls to the leading side: " + tips[slot]);
				// standing well off the grip's axis, the outer ones fanned across Z as well as X
				assertTrue((float) Math.hypot(tips[slot].x, tips[slot].z) > 2.2F, "tier " + tier + " talon " + slot + " stands off the axis: " + tips[slot]);
			}
			for (int a = 0; a < n; a++) {
				for (int b = a + 1; b < n; b++) {
					assertTrue(tips[a].distance(tips[b]) > 1.4F, "tier " + tier + ": talons " + a + " and " + b + " end apart: " + tips[a] + " " + tips[b]);
				}
			}
		}
	}

	@Test
	void growsByTruncationFromTheFist() {
		float lastTop = -1F;
		for (int step = 0; step <= 70; step++) {
			float t = ArmDraw.FORM_GROW_FROM + ArmDraw.FORM_GROW_TICKS * step / 70F;
			ArmDraw d = dry(claws().state(ArmProtocol.STATE_FORMING, t / 14F)).build();
			if (allQuads(d) == 0) continue;
			float[] b = bounds(d);
			// (the cuff and the grip pop with a little overshoot, so the front may settle back a tenth of a pixel)
			assertTrue(b[3] >= lastTop - 0.15F, "the front never moves back (" + b[3] + " after " + lastTop + ")");
			lastTop = b[3];
		}
		// at FORM's tick 8 the grip has come out of the fist and the talons begin; they are all out by the end of FORM
		float[] done = bounds(dry(claws().state(ArmProtocol.STATE_FORMING, 1F)).build());
		assertTrue(done[3] >= ClawsGeometry.CUFF_Y + 0.8F * ClawsGeometry.talonLength(2), "grown out of the cuff by the end of FORM: " + done[3]);
		assertTrue(done[2] <= ClawsGeometry.SPIKE_Y - 0.8F, "and out of the spike: " + done[2]);
		// no talon yet at tick 3
		assertTrue(bounds(dry(claws().state(ArmProtocol.STATE_FORMING, 3F / 14F)).build())[3] < ClawsGeometry.CUFF_Y + 1F, "the talons aren't out at tick 3");
	}

	@Test
	void everyTimelineTierAndDetailDrawsRealGeometry() {
		int[] states = {ArmProtocol.STATE_FORMING, ArmProtocol.STATE_QUICKFORM, ArmProtocol.STATE_RESHAPING, ArmProtocol.STATE_RETRACTING,
				ArmProtocol.STATE_MELTING, ArmProtocol.STATE_FORMED};
		for (int tier = 1; tier <= 3; tier++) {
			for (int lod = 0; lod <= 3; lod++) {
				for (int ctx : new int[] {ArmDraw.CTX_FP, ArmDraw.CTX_TP, ArmDraw.CTX_GUI}) {
					for (int state : states) {
						for (int step = 0; step <= 8; step++) {
							ArmBudgetTest.Draw b = claws().tier(tier).lod(lod).ctx(ctx).time(60F + 3.1F * step).state(state, step / 8F);
							b.fromForm = ArmProtocol.KATANA;
							allQuads(b.build()); // the recorder checks every value is a number and every normal unit length
						}
					}
				}
			}
		}
	}

	@Test
	void retractedArmDrawsNothing() {
		assertEquals(0, allQuads(claws().state(ArmProtocol.STATE_NONE, 1F).build()));
	}

	@Test
	void theHardenedPointsAreRedAndTheBodyBlack() {
		for (int tier = 1; tier <= 3; tier++) {
			assertTrue(quads(claws().tier(tier).build(), ArmLayer.BLACK) > quads(claws().tier(tier).build(), ArmLayer.RED), "mostly black braid, tier " + tier);
			assertTrue(quads(claws().tier(tier).build(), ArmLayer.RED) > 20, "with red static in it, tier " + tier);
		}
	}

	@Test
	void lowBiomassThinsCracksAndFails() {
		ArmBudgetTest.Draw healthy = dry(claws());
		float[] ok = bounds(healthy.build());
		ArmBudgetTest.Draw thin = dry(claws());
		thin.biomassFrac = 0.2F;
		thin.biomassAbs = 25F;
		assertTrue(bounds(thin.build())[3] <= ok[3] + 1e-3F, "shorter");
		ArmBudgetTest.Draw starving = claws();
		starving.biomassFrac = 0.05F;
		starving.biomassAbs = 6F;
		assertTrue(quads(starving.build(), ArmLayer.RED) > quads(claws().build(), ArmLayer.RED), "starving cracks red across the braid");
		ArmBudgetTest.Draw failing = dry(claws());
		failing.biomassFrac = 0.01F;
		failing.biomassAbs = 1F;
		assertTrue(bounds(failing.build())[3] < ok[3] - 0.8F, "a failing arm's points have eroded: " + bounds(failing.build())[3] + " vs " + ok[3]);
	}

	@Test
	void rakeOpensAndStraightensTheTalonsAndTheHitFlaresThem() {
		float rest = fanWidth(dry(claws()).tier(3).build());
		ArmBudgetTest.Draw rake = dry(claws()).tier(3);
		rake.specialKind = ArmProtocol.FX_CLAWS;
		rake.specialAge = 6F;
		float open = fanWidth(rake.build());
		assertTrue(open > rest + 1F, "the talons spread while Rake runs: " + rest + " then " + open);
		float reachRest = bounds(dry(claws()).tier(3).build())[3], reachOpen = bounds(rake.build())[3];
		assertTrue(reachOpen > reachRest + 0.6F, "and straighten and lengthen: " + reachRest + " then " + reachOpen);
		// it recedes after the special ends
		ArmBudgetTest.Draw after = dry(claws()).tier(3);
		after.specialKind = ArmProtocol.FX_CLAWS;
		after.specialAge = -12F;
		assertEquals(rest, fanWidth(after.build()), 0.05F, "gone 12 ticks after");
		// another form's special leaves it alone
		ArmBudgetTest.Draw other = dry(claws()).tier(3);
		other.specialKind = ArmProtocol.FX_SWIPE;
		other.specialAge = 6F;
		assertEquals(rest, fanWidth(other.build()), 1e-3F);
		// a swing lays the talons back: they curl harder, so they stand lower
		ArmBudgetTest.Draw swing = dry(claws()).tier(3);
		swing.swing = 0.5F;
		assertTrue(bounds(swing.build())[3] < reachRest, "a swing lays them back");
		// a hit lights the points even at level 1, where nothing glows at rest
		ArmBudgetTest.Draw hit = claws().tier(1);
		hit.hitAge = 1F;
		assertTrue(quads(hit.build(), ArmLayer.GLOW) > quads(claws().tier(1).build(), ArmLayer.GLOW), "a hit flares the points");
	}

	@Test
	void theLeftHandIsTheSameClaws() {
		for (int tier = 1; tier <= 3; tier++) {
			ArmBudgetTest.Draw left = claws().tier(tier);
			left.leftHand = true;
			assertEquals(allQuads(claws().tier(tier).build()), allQuads(left.build()), "tier " + tier);
		}
	}

	@Test
	void levelsOfDetailShed() {
		int last = Integer.MAX_VALUE;
		for (int lod = 0; lod <= 3; lod++) {
			int n = allQuads(claws().tier(3).lod(lod).build());
			assertTrue(n < last, "LOD " + lod + " costs " + n + " after " + last);
			last = n;
		}
	}

	@Test
	void pointsAreOnTheClaws() {
		ArmDraw d = claws().build();
		assertEquals(0F, CLAWS.point(d, ArmTips.GRIP, new Vector3f()).length(), 1e-6F);
		Vector3f tip = CLAWS.point(d, ArmTips.TIP, new Vector3f()), mid = CLAWS.point(d, ArmTips.MID, new Vector3f());
		assertTrue(tip.y > mid.y && mid.y > ClawsGeometry.CUFF_Y, "tip " + tip + ", middle " + mid);
		assertTrue(tip.length() > 6F, "the point is well out of the fist: " + tip);
		// retracting draws the points back to the cuff
		Vector3f drawn = CLAWS.point(claws().state(ArmProtocol.STATE_RETRACTING, 0.8F).build(), ArmTips.TIP, new Vector3f());
		assertTrue(drawn.y < tip.y - 2F, "retracting: " + drawn);
		// the world effects start where the talons are drawn
		float top = bounds(dry(claws()).build())[3];
		assertTrue(tip.y <= top + 0.1F, "the point " + tip.y + " is inside what is drawn " + top);
	}

	@Test
	void fitsItsGuiSlot() {
		List<Vector3fc> pts = new ArrayList<>();
		CLAWS.extents(pts::add);
		assertFalse(pts.isEmpty());
		float[] span = ArmTransforms.guiSpan(CLAWS.guiScale(), CLAWS.guiGripPx(), pts);
		assertTrue(span[0] >= 1F && span[1] <= 15F, "the GUI fit spans " + span[0] + " to " + span[1] + " item pixels");
		assertTrue(span[1] - span[0] > 8F, "and fills the slot: " + (span[1] - span[0]));
		// and in both slot axes, not only along the diagonal the fit runs up
		Matrix4f gui = ArmTransforms.gui(CLAWS.guiScale(), CLAWS.guiGripPx(), new Matrix4f());
		Vector3f p = new Vector3f();
		for (Vector3fc e : pts) {
			gui.transformPosition(p.set(e));
			assertTrue(p.x * 16F >= 1F && p.x * 16F <= 15F && p.y * 16F >= 1F && p.y * 16F <= 15F, "an extent " + e + " lands at " + p.x * 16F + ", " + p.y * 16F);
		}
		// what is drawn stays inside the extents' box (drips fall out of any box; they are drawn only up close)
		float[] box = {Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE};
		for (Vector3fc e : pts) {
			box[0] = Math.min(box[0], e.x());
			box[1] = Math.max(box[1], e.x());
			box[2] = Math.min(box[2], e.y());
			box[3] = Math.max(box[3], e.y());
			box[4] = Math.min(box[4], e.z());
			box[5] = Math.max(box[5], e.z());
		}
		float[] drawn = bounds(dry(claws()).tier(3).ctx(ArmDraw.CTX_GUI).build());
		assertTrue(drawn[2] >= box[2] - 0.05F && drawn[3] <= box[3] + 0.05F, "drawn " + drawn[2] + ".." + drawn[3] + " inside " + box[2] + ".." + box[3]);
		assertTrue(drawn[0] >= box[0] - 0.3F && drawn[1] <= box[1] + 0.3F, "and its width " + drawn[0] + ".." + drawn[1] + " inside " + box[0] + ".." + box[1]);
	}

	@Test
	void firstPersonCarriesTheDetail() {
		for (int tier = 1; tier <= 3; tier++) {
			int n = allQuads(claws().tier(tier).ctx(ArmDraw.CTX_FP).build());
			assertTrue(n >= 400 && n <= 1300, "tier " + tier + " first person: " + n);
		}
	}
}
