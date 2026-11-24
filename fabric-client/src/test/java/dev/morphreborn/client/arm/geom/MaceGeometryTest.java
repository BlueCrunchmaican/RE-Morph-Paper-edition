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
 * The mace (my design, 2026-11-16): a heavy tendril-knotted head on a braided haft, with flanges of hardened static. It is the
 * real geometry (not the placeholder), the tiers differ in what they carry, every timeline, tier and level of detail draws real geometry
 * (no NaN, no zero-length normals, growing by truncation), Skyward Leap opens the flanges, and it fits its GUI slot.
 */
class MaceGeometryTest {
	private static final FormGeometry MACE = ArmGeometry.get(ArmProtocol.MACE);
	private static final ArmLayer[] LAYERS = {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW};

	private static ArmBudgetTest.Draw mace() {
		return ArmBudgetTest.draw(ArmProtocol.MACE);
	}

	private static ArmBudgetTest.Recorder emit(ArmDraw d, ArmLayer layer, boolean keep) {
		ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
		r.keep = keep;
		MACE.emit(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
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
	void isTheRealMaceNotThePlaceholder() {
		assertTrue(MACE instanceof MaceGeometry, "ArmGeometry found " + MACE.getClass().getSimpleName());
		assertTrue(ArmGeometry.built(ArmProtocol.MACE));
		assertEquals(ArmProtocol.MACE, MACE.form());
		assertFalse(MACE.bowTransforms());
	}

	@Test
	void theTiersDifferInWhatTheyCarry() {
		for (int t = 1; t < 3; t++) {
			assertTrue(MaceGeometry.strandCount(t) < MaceGeometry.strandCount(t + 1), "strands, tier " + t);
			assertTrue(MaceGeometry.finCount(t) < MaceGeometry.finCount(t + 1), "flanges, tier " + t);
			assertTrue(MaceGeometry.knotCount(t) <= MaceGeometry.knotCount(t + 1), "knots, tier " + t);
			assertTrue(MaceGeometry.knotLobes(t) < MaceGeometry.knotLobes(t + 1), "the knot's lobes, tier " + t);
			assertTrue(MaceGeometry.tasselCount(t) < MaceGeometry.tasselCount(t + 1), "tassels, tier " + t);
			assertTrue(MaceGeometry.tailSpikeCount(t) <= MaceGeometry.tailSpikeCount(t + 1), "tail spikes, tier " + t);
			assertTrue(MaceGeometry.finialLength(t) < MaceGeometry.finialLength(t + 1), "the finial, tier " + t);
			assertTrue(MaceGeometry.headRadius(t) < MaceGeometry.headRadius(t + 1), "the head, tier " + t);
			assertTrue(MaceGeometry.finExtent(t) < MaceGeometry.finExtent(t + 1), "how far the flanges stand out, tier " + t);
			assertTrue(MaceGeometry.tipY(t) < MaceGeometry.tipY(t + 1), "the finial's top, tier " + t);
			assertTrue(MACE.feedLength(t) < MACE.feedLength(t + 1), "the feed coordinate runs to the top");
		}
		assertEquals(2, MaceGeometry.knotCount(3), "level 3 winds two interlaced knots");
		assertEquals(4, MaceGeometry.finCount(1));
		assertEquals(8, MaceGeometry.finCount(3));
		for (ArmLayer l : new ArmLayer[] {ArmLayer.BLACK, ArmLayer.RED}) {
			int a = quads(mace().tier(1).build(), l), b = quads(mace().tier(2).build(), l), c = quads(mace().tier(3).build(), l);
			assertTrue(a < b && b < c, l + " quads grow with the tier: " + a + ", " + b + ", " + c);
		}
		// level 3's flanges and collar glow (the glow layer carries them); level 1 has none
		assertTrue(quads(mace().tier(3).build(), ArmLayer.GLOW) > quads(mace().tier(1).build(), ArmLayer.GLOW));
	}

	@Test
	void theHeadIsAKnottedBallWithFlangesStandingOutOfIt() {
		for (int t = 1; t <= 3; t++) {
			assertEquals(MaceGeometry.headRadius(t), MaceGeometry.ballRadius(t, 0F), 1e-4F, "widest at its middle");
			assertEquals(0F, MaceGeometry.ballRadius(t, MaceGeometry.headRadius(t) * 1.2F), 1e-4F, "closed at its pole");
			assertTrue(MaceGeometry.ballRadius(t, 1F) < MaceGeometry.ballRadius(t, 0F), "narrower toward the pole");
			// a flange stands out of the ball, furthest at the middle, and ends in horns beyond the ball's poles
			assertEquals(MaceGeometry.headRadius(t) + MaceGeometry.finExtent(t), MaceGeometry.finOuter(t, 0F), 1e-4F, "tier " + t);
			float last = Float.MAX_VALUE;
			for (float v = 0F; v <= MaceGeometry.headRadius(t) * 1.12F * 1.3F; v += 0.25F) {
				assertTrue(MaceGeometry.finOuter(t, v) <= last + 1e-4F, "only narrows away from the middle, tier " + t + " at " + v);
				last = MaceGeometry.finOuter(t, v);
			}
			assertTrue(MaceGeometry.finOuter(t, MaceGeometry.headRadius(t) * 1.12F * 1.3F) < MaceGeometry.headRadius(t) * 0.3F, "a horn's point");
			assertEquals(MaceGeometry.finOuter(t, 3F), MaceGeometry.finOuter(t, -3F), 1e-4F, "the same either side");
		}
		// drawn: the level-3 finial reaches its top, no further than a pixel beyond it
		float top = topOf(mace().tier(3).build());
		assertTrue(top >= MaceGeometry.tipY(3) - 0.1F && top <= MaceGeometry.tipY(3) + 0.6F, "the drawn top: " + top + " vs " + MaceGeometry.tipY(3));
		// the head is the widest part of it: out to the flanges, and much wider than the haft
		float yc = MaceGeometry.headY(3);
		float head = widest(mace().tier(3).build(), yc - 1.5F, yc + 1.5F), haft = widest(mace().tier(3).build(), 2F, 12F);
		assertTrue(head > MaceGeometry.headRadius(3) + 0.6F * MaceGeometry.finExtent(3), "the flanges stand out of the ball: " + head);
		assertTrue(head < MaceGeometry.headRadius(3) + MaceGeometry.finExtent(3) + 1.6F, "and no further than they should: " + head);
		assertTrue(head > 2.5F * haft, "a heavy head on a slim haft: " + head + " vs " + haft);
	}

	@Test
	void growsByTruncationFromTheFist() {
		float lastTop = -1F, lastLow = 1F;
		for (int step = 0; step <= 70; step++) {
			float t = ArmDraw.FORM_GROW_FROM + ArmDraw.FORM_GROW_TICKS * step / 70F;
			ArmDraw d = mace().state(ArmProtocol.STATE_FORMING, t / 14F).build();
			if (allQuads(d) == 0) continue;
			float top = topOf(d), low = lowOf(d);
			assertTrue(top >= lastTop - 1e-3F, "the front never moves back (" + top + " after " + lastTop + ")");
			assertTrue(low <= lastLow + 1e-3F, "nor the butt (" + low + " after " + lastLow + ")");
			lastTop = top;
			lastLow = low;
		}
		assertTrue(lastTop >= MaceGeometry.tipY(2) - 0.1F, "grown to the top by FORM's tick 8: " + lastTop);
		assertTrue(lastLow <= MaceGeometry.BUTT, "and out of the butt: " + lastLow);
		// the head and its details come once the haft reaches the ball: nothing above the collar at a quarter of the way
		assertTrue(topOf(mace().state(ArmProtocol.STATE_FORMING, 3F / 14F).build()) < MaceGeometry.headY(2), "the head isn't out at tick 3");
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
							ArmBudgetTest.Draw b = mace().tier(tier).lod(lod).ctx(ctx).time(60F + 3.1F * step).state(state, step / 8F);
							b.fromForm = ArmProtocol.KATANA;
							allQuads(b.build()); // the recorder checks every value is a number and every normal unit length
						}
					}
				}
			}
		}
	}

	@Test
	void theFarBraidKeepsARedStrand() {
		// LOD 1 draws two strands: from level 2 one of them is red, so the braid doesn't lose its red across the LOD boundary
		for (int tier = 1; tier <= 3; tier++) {
			int strands = MaceGeometry.strandCount(tier), reds = Math.max(0, tier - 1);
			int a = MaceGeometry.strandAt(1, tier, 0), b = MaceGeometry.strandAt(1, tier, 1);
			assertTrue(a != b && a >= 0 && b < strands, "two distinct strands at tier " + tier);
			assertEquals(reds > 0, b >= strands - reds, "level " + tier + ": the second strand is red when the tier has red ones");
			assertTrue(a < strands - reds || reds == 0, "and the first is black");
			for (int i = 0; i < 4; i++) assertEquals(i, MaceGeometry.strandAt(0, tier, i), "LOD 0 draws them in order");
		}
	}

	@Test
	void retractedArmDrawsNothing() {
		assertEquals(0, allQuads(mace().state(ArmProtocol.STATE_NONE, 1F).build()));
	}

	@Test
	void lowBiomassThinsCracksAndFails() {
		ArmBudgetTest.Draw healthy = mace();
		int ok = allQuads(healthy.build());
		ArmBudgetTest.Draw thin = mace();
		thin.biomassFrac = 0.2F;
		thin.biomassAbs = 25F;
		assertTrue(widest(thin.build(), 2F, 14F) <= widest(healthy.build(), 2F, 14F) + 1e-3F, "thinner");
		ArmBudgetTest.Draw starving = mace();
		starving.biomassFrac = 0.05F;
		starving.biomassAbs = 6F;
		assertTrue(quads(starving.build(), ArmLayer.RED) > quads(healthy.build(), ArmLayer.RED), "starving cracks red rings across the braid");
		ArmBudgetTest.Draw failing = mace();
		failing.biomassFrac = 0.01F;
		failing.biomassAbs = 1F;
		float top = topOf(failing.build());
		assertTrue(top < MaceGeometry.tipY(2) * 0.93F + 1F, "a failing arm's finial has eroded: " + top);
		assertTrue(ok > 0);
	}

	@Test
	void skywardLeapOpensTheFlangesAndTheHitFlaresThem() {
		float yc = MaceGeometry.headY(3);
		float rest = widest(mace().tier(3).build(), yc - 1.5F, yc + 1.5F);
		ArmBudgetTest.Draw leap = mace().tier(3);
		leap.specialKind = ArmProtocol.FX_MACE;
		leap.specialAge = 6F;
		float open = widest(leap.build(), yc - 1.5F, yc + 1.5F);
		assertTrue(open > rest + 0.4F, "the flanges open while Skyward Leap runs: " + rest + " then " + open);
		// it recedes after the special ends
		ArmBudgetTest.Draw after = mace().tier(3);
		after.specialKind = ArmProtocol.FX_MACE;
		after.specialAge = -12F;
		assertEquals(rest, widest(after.build(), yc - 1.5F, yc + 1.5F), 0.05F, "gone 12 ticks after");
		// another form's special leaves it alone
		ArmBudgetTest.Draw other = mace().tier(3);
		other.specialKind = ArmProtocol.FX_SWIPE;
		other.specialAge = 6F;
		assertEquals(rest, widest(other.build(), yc - 1.5F, yc + 1.5F), 1e-3F);
		// a swing folds the flanges back a little
		ArmBudgetTest.Draw swing = mace().tier(3);
		swing.swing = 0.5F;
		assertTrue(widest(swing.build(), yc - 1.5F, yc + 1.5F) < rest, "the flanges fold back in a swing");
	}

	@Test
	void theLeftHandIsTheSameMace() {
		for (int tier = 1; tier <= 3; tier++) {
			ArmBudgetTest.Draw left = mace().tier(tier);
			left.leftHand = true;
			assertEquals(allQuads(mace().tier(tier).build()), allQuads(left.build()), "tier " + tier);
		}
	}

	@Test
	void levelsOfDetailShed() {
		int last = Integer.MAX_VALUE;
		for (int lod = 0; lod <= 3; lod++) {
			int n = allQuads(mace().tier(3).lod(lod).build());
			assertTrue(n < last, "LOD " + lod + " costs " + n + " after " + last);
			last = n;
		}
	}

	@Test
	void pointsAreOnTheMace() {
		ArmDraw d = mace().build();
		assertEquals(0F, MACE.point(d, ArmTips.GRIP, new Vector3f()).length(), 1e-6F);
		Vector3f tip = MACE.point(d, ArmTips.TIP, new Vector3f()), mid = MACE.point(d, ArmTips.MID, new Vector3f());
		assertEquals(MaceGeometry.tipY(2), tip.y, 1e-3F);
		assertEquals(0F, tip.x, 1e-6F);
		assertEquals(MaceGeometry.headY(2), mid.y, 1e-3F, "the middle is the head's centre: where it strikes");
		// retracting draws the top back to the grip
		Vector3f drawn = MACE.point(mace().state(ArmProtocol.STATE_RETRACTING, 0.8F).build(), ArmTips.TIP, new Vector3f());
		assertTrue(drawn.y < tip.y - 4F, "retracting: " + drawn);
	}

	@Test
	void fitsItsGuiSlot() {
		List<Vector3fc> pts = new ArrayList<>();
		MACE.extents(pts::add);
		assertFalse(pts.isEmpty());
		float[] span = ArmTransforms.guiSpan(MACE.guiScale(), MACE.guiGripPx(), pts);
		assertTrue(span[0] >= 1F && span[1] <= 15F, "the GUI fit spans " + span[0] + " to " + span[1] + " item pixels");
		assertTrue(span[1] - span[0] > 10F, "and fills the slot: " + (span[1] - span[0]));
		float maxY = Float.MIN_VALUE, minY = Float.MAX_VALUE;
		for (Vector3fc p : pts) {
			maxY = Math.max(maxY, p.y());
			minY = Math.min(minY, p.y());
		}
		// (drips fall out of any box; they are drawn only up close and are not the mace's shape)
		ArmBudgetTest.Draw dry = mace().tier(3);
		dry.drips = false;
		ArmDraw d = dry.build();
		assertTrue(topOf(d) <= maxY && lowOf(d) >= minY, "drawn " + lowOf(d) + ".." + topOf(d) + " inside " + minY + ".." + maxY);
		float w = 0F;
		for (Vector3fc p : pts) w = Math.max(w, Math.abs(p.x()));
		assertTrue(widest(d, -20F, 40F) <= w, "and the head's flanges inside the box's width");
	}

	@Test
	void firstPersonIsScaledToMatchVanillasMaceScale() {
		// vanilla holds the mace at 0.9 where the handheld tools are held at 0.68: a G pixel would be a third bigger than the others'
		assertEquals(0.68F / 0.9F, MaceGeometry.FP_SCALE, 1e-6F);
		ArmBudgetTest.Draw fp = mace().tier(3).ctx(ArmDraw.CTX_FP);
		fp.drips = false;
		assertEquals(MaceGeometry.FP_SCALE * MaceGeometry.tipY(3), topOf(fp.build()), 0.4F, "the drawn top");
		Vector3f tip = MACE.point(mace().tier(3).ctx(ArmDraw.CTX_FP).build(), ArmTips.TIP, new Vector3f());
		assertEquals(MaceGeometry.FP_SCALE * MaceGeometry.tipY(3), tip.y, 1e-3F, "the point the world effects start from is where it is drawn");
		assertEquals(MaceGeometry.tipY(3), MACE.point(mace().tier(3).ctx(ArmDraw.CTX_TP).build(), ArmTips.TIP, new Vector3f()).y, 1e-3F, "third person is unscaled");
	}

	@Test
	void firstPersonCarriesTheDetail() {
		for (int tier = 1; tier <= 3; tier++) {
			int n = allQuads(mace().tier(tier).ctx(ArmDraw.CTX_FP).build());
			assertTrue(n >= 400 && n <= 1300, "tier " + tier + " first person: " + n);
		}
	}
}
