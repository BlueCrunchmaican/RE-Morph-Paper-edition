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
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;

/**
 * The spear (my design, 2026-11-16): a long braided shaft with a barbed leaf head. It is the real geometry (not the
 * placeholder), the tiers differ in what they carry, every timeline, tier and level of detail draws real geometry (no NaN, no
 * zero-length normals, growing by truncation), Impale opens the barbs, and it fits its GUI slot.
 */
class SpearGeometryTest {
	private static final FormGeometry SPEAR = ArmGeometry.get(ArmProtocol.SPEAR);
	private static final ArmLayer[] LAYERS = {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW};

	private static ArmBudgetTest.Draw spear() {
		return ArmBudgetTest.draw(ArmProtocol.SPEAR);
	}

	private static ArmBudgetTest.Recorder emit(ArmDraw d, ArmLayer layer, boolean keep) {
		ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
		r.keep = keep;
		SPEAR.emit(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
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

	/** The highest y any layer draws, in G px. */
	private static float topOf(ArmDraw d) {
		float top = -Float.MAX_VALUE;
		for (ArmLayer l : LAYERS) {
			for (float[] v : emit(d, l, true).kept) top = Math.max(top, v[1]);
		}
		return top;
	}

	private static float lowOf(ArmDraw d) {
		float low = Float.MAX_VALUE;
		for (ArmLayer l : LAYERS) {
			for (float[] v : emit(d, l, true).kept) low = Math.min(low, v[1]);
		}
		return low;
	}

	private static float widest(ArmDraw d, float yFrom, float yTo) {
		float w = 0F;
		for (ArmLayer l : LAYERS) {
			for (float[] v : emit(d, l, true).kept) {
				if (v[1] >= yFrom && v[1] <= yTo) w = Math.max(w, Math.max(Math.abs(v[0]), Math.abs(v[2])));
			}
		}
		return w;
	}

	@Test
	void isTheRealSpearNotThePlaceholder() {
		assertTrue(SPEAR instanceof SpearGeometry, "ArmGeometry found " + SPEAR.getClass().getSimpleName());
		assertTrue(ArmGeometry.built(ArmProtocol.SPEAR));
		assertEquals(ArmProtocol.SPEAR, SPEAR.form());
		assertFalse(SPEAR.bowTransforms());
	}

	@Test
	void theTiersDifferInWhatTheyCarry() {
		for (int t = 1; t < 3; t++) {
			assertTrue(SpearGeometry.strandCount(t) < SpearGeometry.strandCount(t + 1), "strands, tier " + t);
			assertTrue(SpearGeometry.barbCount(t) <= SpearGeometry.barbCount(t + 1), "barbs, tier " + t);
			assertTrue(SpearGeometry.lugCount(t) <= SpearGeometry.lugCount(t + 1), "lugs, tier " + t);
			assertTrue(SpearGeometry.ringCount(t) <= SpearGeometry.ringCount(t + 1), "rings, tier " + t);
			assertTrue(SpearGeometry.pennonCount(t) <= SpearGeometry.pennonCount(t + 1), "pennons, tier " + t);
			assertTrue(SpearGeometry.tailSpikeCount(t) < SpearGeometry.tailSpikeCount(t + 1), "tail spikes, tier " + t);
			assertTrue(SpearGeometry.bristleCount(t) < SpearGeometry.bristleCount(t + 1), "bristles, tier " + t);
			assertTrue(SpearGeometry.tipY(t) < SpearGeometry.tipY(t + 1), "the point, tier " + t);
			assertTrue(SPEAR.feedLength(t) < SPEAR.feedLength(t + 1), "the feed coordinate runs to the point");
		}
		// no two tiers are alike in the head's barbs and lugs together
		assertTrue(SpearGeometry.barbCount(3) == 4 && SpearGeometry.lugCount(3) == 4, "level 3's cross of four barbs and four lugs");
		assertEquals(0, SpearGeometry.lugCount(1), "level 1 has no lugs");
		for (ArmLayer l : new ArmLayer[] {ArmLayer.BLACK, ArmLayer.RED}) {
			int a = quads(spear().tier(1).build(), l), b = quads(spear().tier(2).build(), l), c = quads(spear().tier(3).build(), l);
			assertTrue(a < b && b < c, l + " quads grow with the tier: " + a + ", " + b + ", " + c);
		}
		// level 3's ridge and collar glow (the glow layer carries them); level 1 has none
		assertTrue(quads(spear().tier(3).build(), ArmLayer.GLOW) > quads(spear().tier(1).build(), ArmLayer.GLOW));
	}

	@Test
	void theHeadIsALeafThatEndsInAPoint() {
		for (int t = 1; t <= 3; t++) {
			assertEquals(0.55F * (t == 1 ? 1.5F : t == 2 ? 1.9F : 2.3F), SpearGeometry.headHalfWidth(t, 0F), 1e-4F, "narrower at the collar");
			float belly = SpearGeometry.headHalfWidth(t, 0.28F);
			assertTrue(belly > SpearGeometry.headHalfWidth(t, 0F) && belly > SpearGeometry.headHalfWidth(t, 0.7F), "it swells then narrows");
			assertTrue(SpearGeometry.headHalfWidth(t, 1F) < 0.1F, "the point: " + SpearGeometry.headHalfWidth(t, 1F));
			float last = Float.MAX_VALUE;
			for (float u = 0.28F; u <= 1F; u += 0.02F) {
				assertTrue(SpearGeometry.headHalfWidth(t, u) <= last + 1e-4F, "only narrows past the belly, tier " + t + " at " + u);
				last = SpearGeometry.headHalfWidth(t, u);
			}
		}
		assertTrue(SpearGeometry.headHalfThickness(0F) > SpearGeometry.headHalfThickness(1F));
		// drawn: the level-3 point reaches its tip, no further than a pixel beyond it
		float top = topOf(spear().tier(3).build());
		assertTrue(top >= SpearGeometry.tipY(3) - 0.05F && top <= SpearGeometry.tipY(3) + 0.6F, "the drawn point: " + top + " vs " + SpearGeometry.tipY(3));
	}

	@Test
	void growsByTruncationFromTheFist() {
		float lastTop = -1F, lastLow = 1F;
		for (int step = 0; step <= 70; step++) {
			float t = ArmDraw.FORM_GROW_FROM + ArmDraw.FORM_GROW_TICKS * step / 70F;
			ArmDraw d = spear().state(ArmProtocol.STATE_FORMING, t / 14F).build();
			if (allQuads(d) == 0) continue;
			float top = topOf(d), low = lowOf(d);
			assertTrue(top >= lastTop - 1e-3F, "the front never moves back (" + top + " after " + lastTop + ")");
			assertTrue(low <= lastLow + 1e-3F, "nor the butt (" + low + " after " + lastLow + ")");
			lastTop = top;
			lastLow = low;
		}
		assertTrue(lastTop >= SpearGeometry.tipY(2) - 0.05F, "grown to the point by FORM's tick 8: " + lastTop);
		assertTrue(lastLow <= SpearGeometry.BUTT, "and out of the butt: " + lastLow);
		// the head and its details come once the shaft reaches the collar: nothing red above the grip at a quarter of the way
		assertTrue(topOf(spear().state(ArmProtocol.STATE_FORMING, 3F / 14F).build()) < SpearGeometry.collarY(2), "the collar isn't out at tick 3");
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
							ArmBudgetTest.Draw b = spear().tier(tier).lod(lod).ctx(ctx).time(60F + 3.1F * step).state(state, step / 8F);
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
		assertEquals(0, allQuads(spear().state(ArmProtocol.STATE_NONE, 1F).build()));
	}

	@Test
	void lowBiomassThinsCracksAndFails() {
		ArmBudgetTest.Draw healthy = spear();
		int ok = allQuads(healthy.build());
		ArmBudgetTest.Draw thin = spear();
		thin.biomassFrac = 0.2F;
		thin.biomassAbs = 25F;
		assertTrue(widest(thin.build(), 2F, 18F) <= widest(healthy.build(), 2F, 18F) + 1e-3F, "thinner");
		ArmBudgetTest.Draw starving = spear();
		starving.biomassFrac = 0.05F;
		starving.biomassAbs = 6F;
		assertTrue(quads(starving.build(), ArmLayer.RED) > quads(healthy.build(), ArmLayer.RED), "starving cracks red rings across the braid");
		ArmBudgetTest.Draw failing = spear();
		failing.biomassFrac = 0.01F;
		failing.biomassAbs = 1F;
		float top = topOf(failing.build());
		assertTrue(top < SpearGeometry.tipY(2) * 0.93F + 1F, "a failing arm's point has eroded: " + top);
		assertTrue(ok > 0);
	}

	@Test
	void impaleOpensTheBarbsAndTheHitFlaresTheRidge() {
		float rest = widest(spear().tier(3).build(), SpearGeometry.collarY(3), SpearGeometry.collarY(3) + 6F);
		ArmBudgetTest.Draw pinned = spear().tier(3);
		pinned.specialKind = ArmProtocol.FX_SPEAR;
		pinned.specialAge = 6F;
		float open = widest(pinned.build(), SpearGeometry.collarY(3), SpearGeometry.collarY(3) + 6F);
		assertTrue(open > rest + 0.3F, "the barbs flare while Impale pins: " + rest + " then " + open);
		// it recedes after the special ends
		ArmBudgetTest.Draw after = spear().tier(3);
		after.specialKind = ArmProtocol.FX_SPEAR;
		after.specialAge = -12F;
		assertEquals(rest, widest(after.build(), SpearGeometry.collarY(3), SpearGeometry.collarY(3) + 6F), 0.05F, "gone 12 ticks after");
		// another form's special leaves it alone
		ArmBudgetTest.Draw other = spear().tier(3);
		other.specialKind = ArmProtocol.FX_SWIPE;
		other.specialAge = 6F;
		assertEquals(rest, widest(other.build(), SpearGeometry.collarY(3), SpearGeometry.collarY(3) + 6F), 1e-3F);
		// a jab folds the barbs back a little
		ArmBudgetTest.Draw jab = spear().tier(3);
		jab.swing = 0.5F;
		assertTrue(widest(jab.build(), SpearGeometry.collarY(3), SpearGeometry.collarY(3) + 6F) < rest, "the barbs fold back in a jab");
	}

	@Test
	void theLeftHandIsTheSameSpear() {
		for (int tier = 1; tier <= 3; tier++) {
			ArmBudgetTest.Draw left = spear().tier(tier);
			left.leftHand = true;
			assertEquals(allQuads(spear().tier(tier).build()), allQuads(left.build()), "tier " + tier);
		}
	}

	@Test
	void levelsOfDetailShed() {
		int last = Integer.MAX_VALUE;
		for (int lod = 0; lod <= 3; lod++) {
			int n = allQuads(spear().tier(3).lod(lod).build());
			assertTrue(n < last, "LOD " + lod + " costs " + n + " after " + last);
			last = n;
		}
	}

	@Test
	void pointsAreOnTheSpear() {
		ArmDraw d = spear().build();
		assertEquals(0F, SPEAR.point(d, ArmTips.GRIP, new Vector3f()).length(), 1e-6F);
		Vector3f tip = SPEAR.point(d, ArmTips.TIP, new Vector3f()), mid = SPEAR.point(d, ArmTips.MID, new Vector3f());
		assertEquals(SpearGeometry.tipY(2), tip.y, 1e-3F);
		assertEquals(0F, tip.x, 1e-6F);
		assertTrue(mid.y > 5F && mid.y < tip.y, "the middle: " + mid);
		// retracting draws the point back to the grip
		Vector3f drawn = SPEAR.point(spear().state(ArmProtocol.STATE_RETRACTING, 0.8F).build(), ArmTips.TIP, new Vector3f());
		assertTrue(drawn.y < tip.y - 4F, "retracting: " + drawn);
	}

	@Test
	void fitsItsGuiSlot() {
		List<Vector3fc> pts = new ArrayList<>();
		SPEAR.extents(pts::add);
		assertFalse(pts.isEmpty());
		float[] span = ArmTransforms.guiSpan(SPEAR.guiScale(), SPEAR.guiGripPx(), pts);
		assertTrue(span[0] >= 1F && span[1] <= 15F, "the GUI fit spans " + span[0] + " to " + span[1] + " item pixels");
		assertTrue(span[1] - span[0] > 11F, "and fills the slot: " + (span[1] - span[0]));
		// every tier's drawn spear is inside the extents
		float maxY = Float.MIN_VALUE, minY = Float.MAX_VALUE;
		for (Vector3fc p : pts) {
			maxY = Math.max(maxY, p.y());
			minY = Math.min(minY, p.y());
		}
		// (drips fall out of any box; they are drawn only up close and are not the spear's shape)
		ArmBudgetTest.Draw dry = spear().tier(3);
		dry.drips = false;
		ArmDraw d = dry.build();
		assertTrue(topOf(d) <= maxY && lowOf(d) >= minY, "drawn " + lowOf(d) + ".." + topOf(d) + " inside " + minY + ".." + maxY);
	}

	@Test
	void firstPersonIsHalvedToMatchVanillasSpearScale() {
		// vanilla holds the spear at 1.36 where the handheld tools are held at 0.68: a G pixel would be twice the others'
		ArmBudgetTest.Draw fp = spear().tier(3).ctx(ArmDraw.CTX_FP);
		fp.drips = false;
		assertEquals(SpearGeometry.FP_SCALE * SpearGeometry.tipY(3), topOf(fp.build()), 0.4F, "the drawn point");
		Vector3f tip = SPEAR.point(spear().tier(3).ctx(ArmDraw.CTX_FP).build(), ArmTips.TIP, new Vector3f());
		assertEquals(SpearGeometry.FP_SCALE * SpearGeometry.tipY(3), tip.y, 1e-3F, "the point the world effects start from is where it is drawn");
		assertEquals(SpearGeometry.tipY(3), SPEAR.point(spear().tier(3).ctx(ArmDraw.CTX_TP).build(), ArmTips.TIP, new Vector3f()).y, 1e-3F, "third person is unscaled");
	}

	@Test
	void firstPersonCarriesTheDetail() {
		for (int tier = 1; tier <= 3; tier++) {
			int n = allQuads(spear().tier(tier).ctx(ArmDraw.CTX_FP).build());
			assertTrue(n >= 400 && n <= 1300, "tier " + tier + " first person: " + n);
		}
	}
}
