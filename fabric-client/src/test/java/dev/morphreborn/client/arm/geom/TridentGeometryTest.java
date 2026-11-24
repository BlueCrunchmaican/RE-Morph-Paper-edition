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
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The trident (my design, 2026-11-16): a braided shaft with a three-pronged fork of hardened static. It is the real geometry
 * (not the placeholder), the tiers differ in what they carry, every timeline, tier and level of detail draws real geometry (no NaN, no
 * zero-length normals, growing by truncation), a throw spreads the prongs, a thrown trident reels into the fist and leaves a stub (and
 * the world line's root), the hold fix lands it where the sword's lands, and it fits its GUI slot.
 */
class TridentGeometryTest {
	private static final FormGeometry TRIDENT = ArmGeometry.get(ArmProtocol.TRIDENT);
	private static final ArmLayer[] LAYERS = {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW};

	@AfterEach
	void forgetMarks() {
		TridentGeometry.clearMarks();
	}

	private static ArmBudgetTest.Draw trident() {
		return ArmBudgetTest.draw(ArmProtocol.TRIDENT);
	}

	/** {@code d} with its use pull and thrown flag changed (the test draw builder leaves both at rest). */
	private static ArmDraw with(ArmDraw d, float bowPull, boolean thrown) {
		return new ArmDraw(d.form(), d.tier(), d.lod(), d.ctx(), d.leftHand(), d.slim(), d.chestArmour(), d.time(), d.seed(), d.state(), d.progress(), d.fromForm(),
				d.swing(), bowPull, d.mining(), d.biomassFrac(), d.biomassAbs(), d.surge(), d.specialAge(), d.specialKind(), d.hitAge(), d.breakAge(), d.pulses(),
				d.pulseCount(), d.lanternLit(), d.lanternAge(), d.recallLeft(), d.glow(), d.dissolve(), d.drips(), d.ropeNodes(), d.downX(), d.downY(), d.downZ(),
				d.fpAnchor(), d.light(), d.pulseSpeed(), d.accessories(), d.accessoryArgs(), d.accessoryAges(), thrown);
	}

	private static ArmBudgetTest.Recorder emit(ArmDraw d, ArmLayer layer, boolean keep) {
		ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
		r.keep = keep;
		TRIDENT.emit(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
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

	/** The widest any layer draws across x (the fork's spread), between two heights. */
	private static float widestX(ArmDraw d, float yFrom, float yTo) {
		float w = 0F;
		for (ArmLayer l : LAYERS) {
			for (float[] v : emit(d, l, true).kept) {
				if (v[1] >= yFrom && v[1] <= yTo) w = Math.max(w, Math.abs(v[0]));
			}
		}
		return w;
	}

	@Test
	void isTheRealTridentNotThePlaceholder() {
		assertTrue(TRIDENT instanceof TridentGeometry, "ArmGeometry found " + TRIDENT.getClass().getSimpleName());
		assertTrue(ArmGeometry.built(ArmProtocol.TRIDENT));
		assertEquals(ArmProtocol.TRIDENT, TRIDENT.form());
		assertFalse(TRIDENT.bowTransforms());
	}

	@Test
	void theTiersDifferInWhatTheyCarry() {
		for (int t = 1; t < 3; t++) {
			assertTrue(TridentGeometry.strandCount(t) < TridentGeometry.strandCount(t + 1), "strands, tier " + t);
			assertTrue(TridentGeometry.midBarbCount(t) <= TridentGeometry.midBarbCount(t + 1), "middle barbs, tier " + t);
			assertTrue(TridentGeometry.sideBarbCount(t) <= TridentGeometry.sideBarbCount(t + 1), "side barbs, tier " + t);
			assertTrue(TridentGeometry.spurCount(t) <= TridentGeometry.spurCount(t + 1), "spurs, tier " + t);
			assertTrue(TridentGeometry.ringCount(t) <= TridentGeometry.ringCount(t + 1), "rings, tier " + t);
			assertTrue(TridentGeometry.tailSpikeCount(t) < TridentGeometry.tailSpikeCount(t + 1), "tail spikes, tier " + t);
			assertTrue(TridentGeometry.bristleCount(t) < TridentGeometry.bristleCount(t + 1), "bristles, tier " + t);
			assertTrue(TridentGeometry.coilTurns(t) < TridentGeometry.coilTurns(t + 1), "coiled line, tier " + t);
			assertTrue(TridentGeometry.tipY(t) < TridentGeometry.tipY(t + 1), "the point, tier " + t);
			assertTrue(TridentGeometry.sideX(t) < TridentGeometry.sideX(t + 1), "the fork's spread, tier " + t);
			assertTrue(TRIDENT.feedLength(t) < TRIDENT.feedLength(t + 1), "the feed coordinate runs to the point");
		}
		assertEquals(1, TridentGeometry.pennonCount(3), "level 3's pennon");
		assertEquals(2, TridentGeometry.barCount(3), "level 3's doubled crossbar");
		assertEquals(0, TridentGeometry.midBarbCount(1), "level 1 has no barbs on the middle prong");
		for (ArmLayer l : new ArmLayer[] {ArmLayer.BLACK, ArmLayer.RED}) {
			int a = quads(trident().tier(1).build(), l), b = quads(trident().tier(2).build(), l), c = quads(trident().tier(3).build(), l);
			assertTrue(a < b && b < c, l + " quads grow with the tier: " + a + ", " + b + ", " + c);
		}
		// level 3's points and collar glow (the glow layer carries them); level 1 has none
		assertTrue(quads(trident().tier(3).build(), ArmLayer.GLOW) > quads(trident().tier(1).build(), ArmLayer.GLOW));
	}

	@Test
	void theForkIsThreeProngsTheMiddleOneLongest() {
		for (int t = 1; t <= 3; t++) {
			assertTrue(TridentGeometry.sideRise(t) < TridentGeometry.tipY(t) - TridentGeometry.collarY(t), "the sides rise less than the middle, tier " + t);
			// drawn: the point reaches its tip, no further than a pixel past it
			ArmBudgetTest.Draw dry = trident().tier(t);
			dry.drips = false;
			float top = topOf(dry.build());
			assertTrue(top >= TridentGeometry.tipY(t) - 0.05F && top <= TridentGeometry.tipY(t) + 0.6F, "tier " + t + " point: " + top);
			// and the fork spreads about as far as it says, past the collar's own width
			float forkW = widestX(dry.build(), TridentGeometry.collarY(t) + 3F, TridentGeometry.collarY(t) + TridentGeometry.sideRise(t));
			assertTrue(forkW > 3F, "tier " + t + " fork " + forkW);
			assertTrue(forkW >= TridentGeometry.sideX(t) - 0.4F && forkW <= TridentGeometry.sideX(t) + 3.2F, "tier " + t + " fork " + forkW);
		}
	}

	@Test
	void growsByTruncationFromTheFist() {
		float lastTop = -1F, lastLow = 1F;
		for (int step = 0; step <= 70; step++) {
			float t = ArmDraw.FORM_GROW_FROM + ArmDraw.FORM_GROW_TICKS * step / 70F;
			ArmDraw d = trident().state(ArmProtocol.STATE_FORMING, t / 14F).build();
			if (allQuads(d) == 0) continue;
			float top = topOf(d), low = lowOf(d);
			assertTrue(top >= lastTop - 1e-3F, "the front never moves back (" + top + " after " + lastTop + ")");
			assertTrue(low <= lastLow + 0.05F, "nor the butt (" + low + " after " + lastLow + ")");
			lastTop = top;
			lastLow = low;
		}
		assertTrue(lastTop >= TridentGeometry.tipY(2) - 0.05F, "grown to the point by FORM's tick 8: " + lastTop);
		assertTrue(lastLow <= TridentGeometry.BUTT, "and out of the butt: " + lastLow);
		assertTrue(topOf(trident().state(ArmProtocol.STATE_FORMING, 3F / 14F).build()) < TridentGeometry.collarY(2), "the fork isn't out at tick 3");
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
							ArmBudgetTest.Draw b = trident().tier(tier).lod(lod).ctx(ctx).time(60F + 3.1F * step).state(state, step / 8F);
							b.fromForm = ArmProtocol.KATANA;
							ArmDraw d = b.build();
							allQuads(d); // the recorder checks every value is a number and every normal unit length
							allQuads(with(d, step / 8F, step % 2 == 0)); // a throw being drawn, and the trident away
						}
					}
				}
			}
		}
	}

	@Test
	void retractedArmDrawsNothing() {
		assertEquals(0, allQuads(trident().state(ArmProtocol.STATE_NONE, 1F).build()));
	}

	@Test
	void lowBiomassThinsCracksAndFails() {
		ArmBudgetTest.Draw healthy = trident();
		int ok = allQuads(healthy.build());
		ArmBudgetTest.Draw starving = trident();
		starving.biomassFrac = 0.05F;
		starving.biomassAbs = 6F;
		assertTrue(quads(starving.build(), ArmLayer.RED) > quads(healthy.build(), ArmLayer.RED), "starving cracks red rings across the braid");
		ArmBudgetTest.Draw failing = trident();
		failing.biomassFrac = 0.01F;
		failing.biomassAbs = 1F;
		float top = topOf(failing.build());
		assertTrue(top < TridentGeometry.tipY(2) * 0.93F + 1F, "a failing arm's point has eroded: " + top);
		assertTrue(ok > 0);
	}

	@Test
	void drawingAThrowSpreadsTheSideProngs() {
		ArmDraw rest = trident().tier(2).ctx(ArmDraw.CTX_GUI).build();
		float closed = widestX(rest, TridentGeometry.collarY(2) + 3F, TridentGeometry.collarY(2) + 8F);
		float wide = widestX(with(rest, 1F, false), TridentGeometry.collarY(2) + 3F, TridentGeometry.collarY(2) + 8F);
		assertTrue(wide > closed + 0.3F, "the fork opens as the throw is drawn: " + closed + " then " + wide);
		// the points light as it is drawn (more glow at full pull)
		assertTrue(quads(with(trident().tier(3).ctx(ArmDraw.CTX_GUI).build(), 1F, false), ArmLayer.GLOW) >= quads(trident().tier(3).ctx(ArmDraw.CTX_GUI).build(), ArmLayer.GLOW));
		// and a stab folds the bristles back
		ArmBudgetTest.Draw stab = trident().tier(3);
		stab.swing = 0.5F;
		assertTrue(allQuads(stab.build()) > 0);
	}

	@Test
	void theWindUpOfAMoveShowsInHandAndRecedes() {
		ArmBudgetTest.Draw run = trident().tier(3);
		run.specialKind = ArmProtocol.FX_TRIDENT;
		run.specialAge = 6F;
		ArmBudgetTest.Draw after = trident().tier(3);
		after.specialKind = ArmProtocol.FX_TRIDENT;
		after.specialAge = -12F;
		float rest = widestX(trident().tier(3).build(), TridentGeometry.collarY(3) + 2F, TridentGeometry.collarY(3) + 8F);
		assertTrue(widestX(run.build(), TridentGeometry.collarY(3) + 2F, TridentGeometry.collarY(3) + 8F) > rest, "barbs open while a move runs");
		assertEquals(rest, widestX(after.build(), TridentGeometry.collarY(3) + 2F, TridentGeometry.collarY(3) + 8F), 0.05F, "gone 12 ticks after");
		// another form's special leaves it alone
		ArmBudgetTest.Draw other = trident().tier(3);
		other.specialKind = ArmProtocol.FX_SWIPE;
		other.specialAge = 6F;
		assertEquals(rest, widestX(other.build(), TridentGeometry.collarY(3) + 2F, TridentGeometry.collarY(3) + 8F), 1e-3F);
	}

	@Test
	void aThrownTridentIsAStubInTheFist() {
		ArmBudgetTest.Draw dry = trident().tier(3);
		dry.drips = false;
		ArmDraw home = dry.build();
		ArmDraw away = with(home, 0F, true);
		float top = topOf(away);
		assertTrue(top > 4F && top < 12F, "the stub: " + top);
		assertTrue(topOf(home) > 3F * top, "the trident itself reaches far past it: " + topOf(home));
		assertTrue(lowOf(away) > -2F, "the shaft and the eye are gone from below the fist: " + lowOf(away));
		// the stub is on the arm's own layers and still draws at every detail
		for (int lod = 0; lod <= 3; lod++) {
			assertTrue(allQuads(with(trident().tier(3).lod(lod).build(), 0F, true)) > 0, "lod " + lod);
		}
		// the world line starts at the stub's bud, the point the effects read as the tip
		Vector3f tip = TRIDENT.point(away, ArmTips.TIP, new Vector3f());
		assertEquals(TridentGeometry.stubLength(), tip.y, 1.4F, "the line's root: " + tip);
		assertTrue(TRIDENT.point(home, ArmTips.TIP, new Vector3f()).y > 3F * tip.y);
		assertEquals(0F, TRIDENT.point(away, ArmTips.GRIP, new Vector3f()).length(), 1e-6F);
		// a failing, thrown arm doesn't draw the trident that isn't there
		ArmBudgetTest.Draw failing = trident().tier(3);
		failing.biomassFrac = 0.01F;
		failing.biomassAbs = 1F;
		assertTrue(topOf(with(failing.build(), 0F, true)) < 12F);
	}

	@Test
	void reelsInAndOutOverTheMarksTicks() {
		int owner = 4242;
		ArmBudgetTest.Draw b = trident().tier(2).time(100F);
		b.seed = owner;
		b.drips = false;
		// no mark for this arm: the state shows at once
		assertEquals(TridentGeometry.tipY(2), topOf(with(b.build(), 0F, false)), 0.6F);
		assertTrue(topOf(with(b.build(), 0F, true)) < 12F);
		// a mark eases toward it: the trident reels in over three ticks and is taken back over five
		TridentGeometry.markAway(owner, true, 100F);
		float last = Float.MAX_VALUE;
		for (float t = 100F; t <= 103.01F; t += 0.5F) {
			float top = topOf(with(b.time(t).build(), 0F, true));
			// (once the trident is shorter than the stub is long the top is the stub's, which grows as it goes)
			if (last > 12F) assertTrue(top <= last + 1e-3F, "reeling in only goes one way: " + top + " after " + last);
			last = top;
		}
		assertTrue(topOf(with(b.time(100F).build(), 0F, true)) > TridentGeometry.tipY(2) - 3F, "at the first tick it is still there");
		assertTrue(last < 12F, "three ticks later it is the stub: " + last);
		TridentGeometry.markAway(owner, false, 110F);
		float prev = -1F;
		for (float t = 110F; t <= 115.01F; t += 0.5F) {
			float top = topOf(with(b.time(t).build(), 0F, false));
			if (top > 12F && prev > 12F) assertTrue(top >= prev - 1e-3F, "coming home only goes one way: " + top + " after " + prev);
			prev = top;
		}
		assertEquals(TridentGeometry.tipY(2), prev, 0.6F, "home in five ticks");
		// a mark that disagrees with the draw (a preview's own toggle) is ignored
		TridentGeometry.markAway(owner, true, 200F);
		assertEquals(TridentGeometry.tipY(2), topOf(with(b.time(300F).build(), 0F, false)), 0.6F);
	}

	@Test
	void theCoilOfLineUnwindsWhenThrown() {
		ArmBudgetTest.Draw b = trident().tier(3);
		b.drips = false;
		// the coil is red turns round the shaft under the fist: more red quads there at home than with the trident away
		int home = quads(b.build(), ArmLayer.RED);
		int away = quads(with(b.build(), 0F, true), ArmLayer.RED);
		assertTrue(home > away, "red " + home + " against " + away);
	}

	// ------------------------------------------------------------------ where it is held

	private static Matrix4f applied(ArmTransforms.Set set, ItemDisplayContext ctx) {
		PoseStack.Pose pose = new PoseStack().last();
		ArmTransforms.forContext(ctx, set).apply(ctx.leftHand(), pose);
		return new Matrix4f(pose.pose());
	}

	private static void assertMatrix(Matrix4f want, Matrix4f got, String what) {
		for (int c = 0; c < 4; c++) {
			for (int r = 0; r < 4; r++) assertEquals(want.get(c, r), got.get(c, r), 1e-3F, what + " [" + c + "," + r + "]");
		}
	}

	@Test
	void firstPersonLandsGripSpaceWhereTheSwordsLands() {
		for (boolean left : new boolean[] {false, true}) {
			ItemDisplayContext ctx = left ? ItemDisplayContext.FIRST_PERSON_LEFT_HAND : ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
			ArmBudgetTest.Draw b = trident().ctx(ArmDraw.CTX_FP);
			b.leftHand = left;
			ArmDraw d = b.build();
			assertTrue(TridentGeometry.fixed(d));
			Matrix4f fix = TridentGeometry.holdFix(d, new Matrix4f());
			Matrix4f got = applied(ArmTransforms.Set.TRIDENT, ctx).mul(ArmTransforms.gripFrame(ArmTransforms.Set.TRIDENT, new Matrix4f())).mul(fix);
			Matrix4f want = applied(ArmTransforms.Set.HANDHELD, ctx).mul(ArmTransforms.gripFrame(ArmTransforms.Set.HANDHELD, new Matrix4f()));
			assertMatrix(want, got, (left ? "left" : "right") + " first person");
		}
	}

	@Test
	void theRaisedPoseKeepsTheRestHoldOnTheModel() {
		for (boolean left : new boolean[] {false, true}) {
			ItemDisplayContext ctx = left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
			ArmBudgetTest.Draw b = trident().ctx(ArmDraw.CTX_TP);
			b.leftHand = left;
			ArmDraw rest = b.build();
			assertFalse(TridentGeometry.fixed(rest), "third person at rest is already the sword's hold");
			assertMatrix(new Matrix4f(), TridentGeometry.holdFix(rest, new Matrix4f()), "no fix at rest");
			ArmDraw raised = with(rest, 0.4F, false);
			assertTrue(TridentGeometry.fixed(raised));
			Matrix4f got = ArmTransforms.gripFrame(ArmTransforms.Set.TRIDENT, new Matrix4f()).mul(TridentGeometry.holdFix(raised, new Matrix4f()));
			assertMatrix(ArmTransforms.handFrame(ctx, ArmProtocol.TRIDENT, false, false, new Matrix4f()), got, "the model's own hold");
		}
		// the icons are never fixed
		assertFalse(TridentGeometry.fixed(trident().ctx(ArmDraw.CTX_GUI).build()));
	}

	@Test
	void pointsFollowTheHoldFix() {
		ArmDraw fp = trident().ctx(ArmDraw.CTX_FP).build();
		Vector3f tip = TRIDENT.point(fp, ArmTips.TIP, new Vector3f());
		Vector3f want = new Vector3f(0F, TridentGeometry.tipY(2), 0F);
		TridentGeometry.holdFix(fp, new Matrix4f()).transformPosition(want);
		assertEquals(want.x, tip.x, 1e-3F);
		assertEquals(want.y, tip.y, 1e-3F);
		assertEquals(want.z, tip.z, 1e-3F);
		Vector3f tp = TRIDENT.point(trident().ctx(ArmDraw.CTX_TP).build(), ArmTips.TIP, new Vector3f());
		assertEquals(TridentGeometry.tipY(2), tp.y, 1e-3F);
		assertEquals(0F, tp.x, 1e-6F);
		Vector3f mid = TRIDENT.point(trident().ctx(ArmDraw.CTX_TP).build(), ArmTips.MID, new Vector3f());
		assertTrue(mid.y > 5F && mid.y < tp.y, "the middle: " + mid);
		// retracting draws the point back to the grip
		Vector3f drawn = TRIDENT.point(trident().ctx(ArmDraw.CTX_TP).state(ArmProtocol.STATE_RETRACTING, 0.8F).build(), ArmTips.TIP, new Vector3f());
		assertTrue(drawn.y < tp.y - 4F, "retracting: " + drawn);
	}

	// ------------------------------------------------------------------ the rest

	@Test
	void theLeftHandIsTheSameTrident() {
		for (int tier = 1; tier <= 3; tier++) {
			ArmBudgetTest.Draw left = trident().tier(tier);
			left.leftHand = true;
			assertEquals(allQuads(trident().tier(tier).build()), allQuads(left.build()), "tier " + tier);
		}
	}

	@Test
	void levelsOfDetailShed() {
		int last = Integer.MAX_VALUE;
		for (int lod = 0; lod <= 3; lod++) {
			int n = allQuads(trident().tier(3).lod(lod).build());
			assertTrue(n < last, "LOD " + lod + " costs " + n + " after " + last);
			last = n;
		}
	}

	@Test
	void fitsItsGuiSlot() {
		List<Vector3fc> pts = new ArrayList<>();
		TRIDENT.extents(pts::add);
		assertFalse(pts.isEmpty());
		float[] span = ArmTransforms.guiSpan(TRIDENT.guiScale(), TRIDENT.guiGripPx(), pts);
		assertTrue(span[0] >= 1F && span[1] <= 15F, "the GUI fit spans " + span[0] + " to " + span[1] + " item pixels");
		assertTrue(span[1] - span[0] > 11F, "and fills the slot: " + (span[1] - span[0]));
		float maxY = Float.MIN_VALUE, minY = Float.MAX_VALUE, maxX = 0F;
		for (Vector3fc p : pts) {
			maxY = Math.max(maxY, p.y());
			minY = Math.min(minY, p.y());
			maxX = Math.max(maxX, Math.abs(p.x()));
		}
		ArmBudgetTest.Draw dry = trident().tier(3);
		dry.drips = false;
		ArmDraw d = dry.build();
		assertTrue(topOf(d) <= maxY && lowOf(d) >= minY, "drawn " + lowOf(d) + ".." + topOf(d) + " inside " + minY + ".." + maxY);
		assertTrue(widestX(d, minY, maxY) <= maxX, "and across: " + widestX(d, minY, maxY) + " inside " + maxX);
	}

	@Test
	void firstPersonCarriesTheDetail() {
		for (int tier = 1; tier <= 3; tier++) {
			int n = allQuads(trident().tier(tier).ctx(ArmDraw.CTX_FP).build());
			assertTrue(n >= 400 && n <= 1300, "tier " + tier + " first person: " + n);
		}
	}

	@Test
	void theFlyingTridentIsTheWholeToolWithNoFist() {
		for (int tier = 1; tier <= 3; tier++) {
			ArmBudgetTest.Draw b = trident().tier(tier).ctx(ArmDraw.CTX_TP);
			b.drips = false;
			// whatever state the stand-in draw is in (it is a rest draw), the flying one is whole: it reaches the point and the eye
			ArmBudgetTest.Recorder top = new ArmBudgetTest.Recorder();
			top.keep = true;
			float hi = -Float.MAX_VALUE, lo = Float.MAX_VALUE;
			for (ArmLayer l : LAYERS) {
				ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
				r.keep = true;
				TridentGeometry.emitFlying(b.state(ArmProtocol.STATE_NONE, 1F).build(), l, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
				for (float[] v : r.kept) {
					hi = Math.max(hi, v[1]);
					lo = Math.min(lo, v[1]);
				}
			}
			assertEquals(TridentGeometry.tipY(tier), hi, 0.6F, "tier " + tier + " point");
			assertTrue(lo <= TridentGeometry.eyeY() + 1.2F, "tier " + tier + " eye: " + lo + " vs " + TridentGeometry.eyeY());
		}
	}
}
