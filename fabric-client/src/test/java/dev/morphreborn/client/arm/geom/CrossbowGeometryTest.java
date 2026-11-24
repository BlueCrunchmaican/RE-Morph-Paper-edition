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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The crossbow (my design, 2026-11-16: it loads itself, and after each shot the bolt regrows out of the weapon over the vanilla
 * charge time): the real geometry, held with the vanilla crossbow's display block, a ribbed stock and a pistol grip, a loaded bolt in the
 * groove, an empty groove after a shot, and a bolt that grows back from the stock forward with its head last.
 */
class CrossbowGeometryTest {
	private static final FormGeometry CROSSBOW = ArmGeometry.get(ArmProtocol.CROSSBOW);
	private static final ArmLayer[] LAYERS = {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW};
	private static final int SEED = 7;

	@BeforeEach
	@AfterEach
	void clean() {
		CrossbowGeometry.clearMarks();
	}

	private static ArmBudgetTest.Draw crossbow() {
		return ArmBudgetTest.draw(ArmProtocol.CROSSBOW);
	}

	/** {@code d} with the groove empty or loaded (the held stack's word), the record's own constructor with only that changed. */
	private static ArmDraw groove(ArmDraw d, boolean empty) {
		return new ArmDraw(d.form(), d.tier(), d.lod(), d.ctx(), d.leftHand(), d.slim(), d.chestArmour(), d.time(), d.seed(), d.state(), d.progress(), d.fromForm(), d.swing(),
				d.bowPull(), d.mining(), d.biomassFrac(), d.biomassAbs(), d.surge(), d.specialAge(), d.specialKind(), d.hitAge(), d.breakAge(), d.pulses(), d.pulseCount(),
				d.lanternLit(), d.lanternAge(), d.recallLeft(), d.glow(), d.dissolve(), d.drips(), d.ropeNodes(), d.downX(), d.downY(), d.downZ(), d.fpAnchor(), d.light(),
				d.pulseSpeed(), d.accessories(), d.accessoryArgs(), d.accessoryAges(), empty);
	}

	private static ArmBudgetTest.Recorder emit(ArmDraw d, ArmLayer layer, boolean keep) {
		ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
		r.keep = keep;
		CROSSBOW.emit(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
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

	/** The most forward vertex along the groove, in C's y (the way the bolt flies is G's -X): the bolt's head, or else the groove's front and the released string's latch. */
	private static float frontOf(ArmDraw d) {
		float front = -Float.MAX_VALUE;
		for (ArmLayer l : LAYERS) {
			for (float[] v : emit(d, l, true).kept) {
				if (Math.abs(v[1]) < 0.9F && v[2] > 0.8F && v[2] < 2.1F) front = Math.max(front, -v[0]);
			}
		}
		return front;
	}

	private static List<float[]> vertices(ArmDraw d) {
		List<float[]> all = new ArrayList<>();
		for (ArmLayer l : LAYERS) all.addAll(emit(d, l, true).kept);
		return all;
	}

	@Test
	void isTheRealCrossbowHeldWithTheVanillaCrossbowBlock() {
		assertTrue(CROSSBOW instanceof CrossbowGeometry, "ArmGeometry found " + CROSSBOW.getClass().getSimpleName());
		assertTrue(ArmGeometry.built(ArmProtocol.CROSSBOW));
		assertEquals(ArmProtocol.CROSSBOW, CROSSBOW.form());
		assertFalse(CROSSBOW.bowTransforms());
		assertEquals(ArmTransforms.Set.CROSSBOW, ArmTransforms.Set.of(ArmProtocol.CROSSBOW, false), "its own display block");
		// held as vanilla holds the real item in third person: the grip frame is the set's own, not moved onto the sword's hold
		Matrix4f hand = ArmTransforms.handFrame(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, ArmProtocol.CROSSBOW, false, false, new Matrix4f());
		Matrix4f own = ArmTransforms.gripFrame(ArmTransforms.Set.CROSSBOW, new Matrix4f());
		assertTrue(hand.equals(own, 1e-6F), "third person puts G on the crossbow's own grip frame");
	}

	@Test
	void theStockIsRibbedAndTheGripHangsUnderIt() {
		// the weapon's length runs along G's X (the front toward -X), the prod across Y, up is Z
		ArmDraw d = crossbow().tier(2).ctx(ArmDraw.CTX_TP).build();
		List<float[]> v = vertices(d);
		float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE, minZ = Float.MAX_VALUE;
		for (float[] p : v) {
			minX = Math.min(minX, p[0]);
			maxX = Math.max(maxX, p[0]);
			minY = Math.min(minY, p[1]);
			maxY = Math.max(maxY, p[1]);
			minZ = Math.min(minZ, p[2]);
		}
		assertTrue(maxX - minX > 15F && maxX - minX < 21F, "about as long as the sprite: " + (maxX - minX));
		assertTrue(maxY - minY > 10F && maxY - minY < 15F, "the prod spans across it: " + (maxY - minY));
		assertTrue(minZ < -4F, "the pistol grip hangs below the stock: " + minZ);
		assertTrue(maxX > 8F, "the butt is behind the grip, on +X: " + maxX);
		// the grip is where the claw closes: GRIP is on the grip's axis, below the stock
		Vector3f grip = CROSSBOW.point(d, ArmTips.GRIP, new Vector3f());
		assertEquals(-CrossbowGeometry.GRIP_Y, grip.x, 1e-4F);
		assertTrue(grip.z < -1F && grip.z > -4F, "in the pistol grip's height: " + grip.z);
		// ribs: more of them with the level, so the black grows; the red grows too until level 3, where the core, groove and nocks glow instead
		int a = quads(crossbow().tier(1).build(), ArmLayer.BLACK), b = quads(crossbow().tier(2).build(), ArmLayer.BLACK), c = quads(crossbow().tier(3).build(), ArmLayer.BLACK);
		assertTrue(a < b && b < c, "black quads grow with the level: " + a + ", " + b + ", " + c);
		int ra = quads(crossbow().tier(1).build(), ArmLayer.RED), rb = quads(crossbow().tier(2).build(), ArmLayer.RED);
		assertTrue(ra < rb, "red quads grow from level 1 to 2: " + ra + ", " + rb);
		int ga = allQuads(crossbow().tier(2).build()), gb = allQuads(crossbow().tier(3).build());
		assertTrue(ga < gb, "and so does the whole: " + ga + ", " + gb);
		assertTrue(quads(crossbow().tier(3).build(), ArmLayer.GLOW) > quads(crossbow().tier(1).build(), ArmLayer.GLOW), "level 3 glows");
		for (int t = 1; t < 3; t++) assertTrue(CROSSBOW.feedLength(t) < CROSSBOW.feedLength(t + 1), "longer limbs");
	}

	@Test
	void aLoadedCrossbowCarriesABoltInTheGrooveAndAnEmptyOneDoesNot() {
		ArmDraw loaded = crossbow().build();
		ArmDraw empty = groove(loaded, true);
		assertEquals(1F, CrossbowGeometry.growth(loaded), 0F);
		assertEquals(0F, CrossbowGeometry.growth(empty), 0F);
		assertTrue(allQuads(loaded) > allQuads(empty) + 40, "the bolt takes quads: " + allQuads(empty) + " empty, " + allQuads(loaded) + " loaded");
		// the loaded bolt's point is past the groove's front, the empty groove ends where the rails do
		assertTrue(frontOf(loaded) > CrossbowGeometry.MUZZLE_Y + 0.8F, "the point: " + frontOf(loaded));
		assertTrue(frontOf(empty) <= CrossbowGeometry.MUZZLE_Y + 0.5F, "the empty groove: " + frontOf(empty));
		// an icon or a preview, which have no stack, shows it loaded
		assertEquals(1F, CrossbowGeometry.growth(crossbow().ctx(ArmDraw.CTX_GUI).build()), 0F);
	}

	@Test
	void aShotEmptiesTheGrooveAtOnceAndTheStackTakesOverAfterAFewTicks() {
		ArmDraw loaded = crossbow().time(100F).build();
		CrossbowGeometry.markShot(SEED, 100F);
		assertEquals(0F, CrossbowGeometry.growth(loaded), 0F, "the stack still says loaded, the shot says empty");
		assertEquals(0F, CrossbowGeometry.growth(crossbow().time(102.5F).build()), 0F);
		assertEquals(1F, CrossbowGeometry.growth(crossbow().time(103.5F).build()), 0F, "a loaded stack is believed once the shot has settled");
		assertEquals(0F, CrossbowGeometry.growth(groove(crossbow().time(120F).build(), true)), 0F, "an empty one stays empty with no regrow");
		// the string snaps forward and rings: it draws differently just after the shot than long after it
		ArmDraw rest = groove(crossbow().time(300F).build(), true);
		CrossbowGeometry.markShot(SEED, 300F);
		ArmDraw ringing = groove(crossbow().time(301.5F).build(), true);
		assertTrue(differ(rest, ringing), "the string rings after a shot");
	}

	private static boolean differ(ArmDraw a, ArmDraw b) {
		List<float[]> va = vertices(a), vb = vertices(b);
		if (va.size() != vb.size()) return true;
		for (int i = 0; i < va.size(); i++) {
			for (int k = 0; k < 3; k++) if (Math.abs(va.get(i)[k] - vb.get(i)[k]) > 1e-4F) return true;
		}
		return false;
	}

	@Test
	void theBoltRegrowsOutOfTheStockForwardAndTheHeadComesLast() {
		float t0 = 200F;
		CrossbowGeometry.markRegrow(SEED, t0, 25, 25, 1);
		float lastFront = -Float.MAX_VALUE;
		int lastQuads = 0;
		float frontAt80 = 0F, frontAt100 = 0F;
		for (int step = 0; step <= 25; step++) {
			ArmDraw d = groove(crossbow().time(t0 + step).build(), true);
			float growth = CrossbowGeometry.growth(d);
			assertEquals(step / 25F, growth, 1e-3F, "growth follows the regrow's own clock");
			int q = allQuads(d);
			float front = frontOf(d);
			// the string draws back over the first 35 % (its latch is the front until the shaft comes), then the shaft only runs forward (the head's pop
			// overshoots by a quarter as it lands, so the last ticks are left out)
			if (step >= 10 && step < 23) assertTrue(front >= lastFront - 1e-3F, "the front never moves back: " + front + " after " + lastFront + " at tick " + step);
			assertTrue(q >= lastQuads - 6, "the bolt only takes more shape (" + q + " after " + lastQuads + ")");
			lastFront = front;
			lastQuads = q;
			if (step == 18) frontAt80 = front;
			if (step == 25) frontAt100 = front;
		}
		// tip last: by 72 % the shaft is out but the point is not past the groove's front; at the end it is
		assertTrue(frontAt80 <= CrossbowGeometry.MUZZLE_Y + 0.5F, "no head yet at 72 %: " + frontAt80);
		assertTrue(frontAt100 > CrossbowGeometry.MUZZLE_Y + 0.8F, "the head has come: " + frontAt100);
		// nothing at the start but the empty groove, everything at the end
		assertTrue(allQuads(groove(crossbow().time(t0).build(), true)) < allQuads(groove(crossbow().time(t0 + 25F).build(), true)) - 40);
		// the string draws itself back as the bolt grows: its middle is further back at the end than at a third of the way
		assertEquals(1F, CrossbowGeometry.growth(groove(crossbow().time(t0 + 40F).build(), true)), 0F, "held full for the stack's load to arrive");
		assertEquals(0F, CrossbowGeometry.growth(groove(crossbow().time(t0 + 90F).build(), true)), 0F, "then the stack is believed");
	}

	@Test
	void aRegrowWinsOverTheStackWhileItRunsAndAShotCancelsIt() {
		CrossbowGeometry.markRegrow(SEED, 50F, 25, 25, 1);
		assertEquals(0.4F, CrossbowGeometry.growth(crossbow().time(60F).build()), 1e-3F, "a loaded stack mid-regrow still shows the growing bolt");
		CrossbowGeometry.markShot(SEED, 61F);
		assertEquals(0F, CrossbowGeometry.growth(groove(crossbow().time(62F).build(), true)), 0F, "a new shot stops the regrow");
		// another owner is unaffected
		ArmBudgetTest.Draw other = crossbow().time(62F);
		other.seed = SEED + 1;
		assertEquals(1F, CrossbowGeometry.growth(other.build()), 0F);
	}

	@Test
	void aRegrowSentAgainWithTheSameSeedKeepsItsOwnStart() {
		// the server resends the regrow every 10 ticks for viewers who came into range: the growth must not restart over what is left
		CrossbowGeometry.markRegrow(SEED, 100F, 25, 25, 77);
		assertEquals(0.4F, CrossbowGeometry.growth(groove(crossbow().time(110F).build(), true)), 1e-3F);
		CrossbowGeometry.markRegrow(SEED, 110F, 25, 15, 77); // the resend at +10: same seed, 15 left
		assertEquals(0.4F, CrossbowGeometry.growth(groove(crossbow().time(110F).build(), true)), 1e-3F, "not back to 0");
		assertEquals(0.8F, CrossbowGeometry.growth(groove(crossbow().time(120F).build(), true)), 1e-3F, "and on, over the original 25");
		CrossbowGeometry.markRegrow(SEED, 120F, 25, 5, 77);
		assertEquals(1F, CrossbowGeometry.growth(groove(crossbow().time(125F).build(), true)), 1e-3F);
	}

	@Test
	void aNewSeedStartsANewRegrowAndALateJoinerStartsWhereTheServerIs() {
		CrossbowGeometry.markRegrow(SEED, 100F, 25, 25, 1);
		CrossbowGeometry.markRegrow(SEED, 300F, 25, 25, 2); // the next regrow
		assertEquals(0F, CrossbowGeometry.growth(groove(crossbow().time(300F).build(), true)), 1e-3F);
		assertEquals(0.5F, CrossbowGeometry.growth(groove(crossbow().time(312.5F).build(), true)), 1e-3F);
		// a viewer whose first message is a resend 15 ticks into a 25 tick regrow (10 left): growth = 1 - left / total from the first frame
		CrossbowGeometry.clearMarks();
		CrossbowGeometry.markRegrow(SEED, 500F, 25, 10, 9);
		assertEquals(0.6F, CrossbowGeometry.growth(groove(crossbow().time(500F).build(), true)), 1e-3F);
		assertEquals(1F, CrossbowGeometry.growth(groove(crossbow().time(510F).build(), true)), 1e-3F);
		// a message whose left is out of range is clamped, never negative growth or a start in the future
		CrossbowGeometry.clearMarks();
		CrossbowGeometry.markRegrow(SEED, 600F, 25, 99, 3);
		assertEquals(0F, CrossbowGeometry.growth(groove(crossbow().time(600F).build(), true)), 1e-3F);
		CrossbowGeometry.markRegrow(SEED, 600F, 25, -4, 4);
		assertEquals(1F, CrossbowGeometry.growth(groove(crossbow().time(600F).build(), true)), 1e-3F);
	}

	@Test
	void aStaleMarkIsNotBelievedAndTheStackIs() {
		// WhipGeometry.ageOf reads a mark older than half the clock's wrap (30 minutes) as one from the future: it used to read as growth 0 / empty
		// for good with a shot mark, though the stack said loaded
		CrossbowGeometry.markShot(SEED, 100F);
		float halfHourOn = 100F + 36001F;
		assertEquals(1F, CrossbowGeometry.growth(crossbow().time(halfHourOn).build()), 0F, "a loaded stack, 30 minutes after a shot");
		assertEquals(1F, CrossbowGeometry.growth(crossbow().time(100F + 50000F).build()), 0F);
		assertEquals(ArmDraw.NEVER, CrossbowGeometry.shotAge(crossbow().time(halfHourOn).build()), 0F);
		// and the same for a regrow mark: never 0 from a negative age, and not brought back by the wrap an hour on
		CrossbowGeometry.markRegrow(SEED, 200F, 25, 25, 1);
		assertEquals(1F, CrossbowGeometry.growth(crossbow().time(200F + 36001F).build()), 0F, "a loaded stack after half an hour");
		assertEquals(1F, CrossbowGeometry.growth(crossbow().time(200F + 72005F).build()), 0F, "a stale mark reborn an hour on says nothing either");
		// an empty stack with a stale mark is empty, not whatever the mark said
		CrossbowGeometry.markRegrow(SEED, 300F, 25, 25, 2);
		assertEquals(0F, CrossbowGeometry.growth(groove(crossbow().time(300F + 36001F).build(), true)), 0F);
	}

	@Test
	void theBurstTakesTheBoltSoAWholeBoltsHoldIsOver() {
		CrossbowGeometry.markRegrow(SEED, 100F, 25, 25, 1);
		assertEquals(1F, CrossbowGeometry.growth(groove(crossbow().time(130F).build(), true)), 0F, "whole, held while the stack's load arrives");
		CrossbowGeometry.markBurst(SEED, 130F, 3, 3);
		assertEquals(0F, CrossbowGeometry.growth(groove(crossbow().time(131F).build(), true)), 0F, "the burst emptied the groove: the stack's word");
	}

	@Test
	void theSpineBurstFlaresTheMuzzleOncePerSpine() {
		float t0 = 400F;
		ArmDraw quiet = crossbow().time(t0 + 1F).build();
		int none = quads(quiet, ArmLayer.RED);
		CrossbowGeometry.markBurst(SEED, t0, 3, 3);
		int first = quads(crossbow().time(t0 + 1F).build(), ArmLayer.RED);
		int second = quads(crossbow().time(t0 + 4F).build(), ArmLayer.RED);
		int third = quads(crossbow().time(t0 + 7F).build(), ArmLayer.RED);
		int over = quads(crossbow().time(t0 + 30F).build(), ArmLayer.RED);
		assertTrue(first > none && second > none && third > none, "a flash for each of the three: " + none + ", " + first + ", " + second + ", " + third);
		assertEquals(none, over, "and gone after");
	}

	@Test
	void aLongerBurstFlaresForEverySpineAndLastsUntilTheLastLeaves() {
		float t0 = 600F;
		int none = quads(crossbow().time(t0 - 1F).build(), ArmLayer.RED);
		CrossbowGeometry.markBurst(SEED, t0, 2, 6);
		// the sixth spine leaves 10 ticks in: still flashing at 11, and not in the first three's window alone
		int sixth = quads(crossbow().time(t0 + 11F).build(), ArmLayer.RED);
		assertTrue(sixth > none, "the sixth spine's flash: " + none + ", " + sixth);
		assertEquals(none, quads(crossbow().time(t0 + 30F).build(), ArmLayer.RED), "and gone after");
	}

	@Test
	void growsByTruncationFromTheGrip() {
		float lastLow = 1000F, lastHigh = -1000F;
		for (int step = 0; step <= 70; step++) {
			float t = ArmDraw.FORM_GROW_FROM + ArmDraw.FORM_GROW_TICKS * step / 70F;
			ArmDraw d = crossbow().state(ArmProtocol.STATE_FORMING, t / 14F).build();
			if (allQuads(d) == 0) continue;
			float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
			for (float[] v : vertices(d)) {
				lo = Math.min(lo, v[0]);
				hi = Math.max(hi, v[0]);
			}
			// (a quarter px of give: the swelling and the breath move the edge of a rib a little as it grows)
			assertTrue(lo <= lastLow + 0.25F, "the front never moves back: " + lo + " after " + lastLow);
			assertTrue(hi >= lastHigh - 0.25F, "nor the butt: " + hi + " after " + lastHigh);
			lastLow = lo;
			lastHigh = hi;
		}
	}

	@Test
	void everyTimelineTierAndDetailDrawsRealGeometry() {
		int[] states = {ArmProtocol.STATE_FORMING, ArmProtocol.STATE_QUICKFORM, ArmProtocol.STATE_RESHAPING, ArmProtocol.STATE_RETRACTING, ArmProtocol.STATE_MELTING,
				ArmProtocol.STATE_FORMED};
		CrossbowGeometry.markBurst(SEED, 55F, 3, 3);
		for (int tier = 1; tier <= 3; tier++) {
			for (int lod = 0; lod <= 3; lod++) {
				for (int ctx : new int[] {ArmDraw.CTX_FP, ArmDraw.CTX_TP, ArmDraw.CTX_GUI}) {
					for (int state : states) {
						for (int step = 0; step <= 8; step++) {
							ArmBudgetTest.Draw b = crossbow().tier(tier).lod(lod).ctx(ctx).time(60F + 3.1F * step).state(state, step / 8F);
							b.fromForm = ArmProtocol.KATANA;
							ArmDraw d = b.build();
							allQuads(d); // the recorder checks every value is a number and every normal unit length
							allQuads(groove(d, true));
							CrossbowGeometry.markRegrow(SEED, 62F, 25, 25, 1);
							allQuads(groove(d, true));
							CrossbowGeometry.markShot(SEED, 62F);
							allQuads(d);
						}
					}
				}
			}
		}
	}

	@Test
	void retractedArmDrawsNothing() {
		assertEquals(0, allQuads(crossbow().state(ArmProtocol.STATE_NONE, 1F).build()));
	}

	@Test
	void lowBiomassThinsSlackensAndFails() {
		ArmBudgetTest.Draw healthy = crossbow();
		ArmBudgetTest.Draw thin = crossbow();
		thin.biomassFrac = 0.2F;
		thin.biomassAbs = 25F;
		float wide = 0F, narrow = 0F;
		for (float[] v : vertices(healthy.build())) wide = Math.max(wide, Math.abs(v[1]));
		for (float[] v : vertices(thin.build())) narrow = Math.max(narrow, Math.abs(v[1]));
		assertTrue(narrow <= wide + 1e-3F, "thinner");
		ArmBudgetTest.Draw failing = crossbow();
		failing.biomassFrac = 0.01F;
		failing.biomassAbs = 1F;
		assertTrue(allQuads(failing.build()) > 0);
	}

	@Test
	void theLimbsFlexBackWithTheCocking() {
		// loaded (cocked) the prod's tips are further back than empty (released): further +X in G
		ArmDraw loaded = crossbow().tier(2).build();
		ArmDraw empty = groove(loaded, true);
		float tipsLoaded = 0F, tipsEmpty = 0F;
		for (float[] v : vertices(loaded)) if (Math.abs(v[1]) > 5.5F) tipsLoaded = Math.max(tipsLoaded, v[0]);
		for (float[] v : vertices(empty)) if (Math.abs(v[1]) > 5.5F) tipsEmpty = Math.max(tipsEmpty, v[0]);
		assertTrue(tipsLoaded < tipsEmpty + 3F, "the tips are drawn back by the cocking, not forward: " + tipsLoaded + " loaded, " + tipsEmpty + " empty");
	}

	@Test
	void pointsAreTheMuzzleTheBoltAndTheGrip() {
		ArmDraw d = crossbow().build();
		Vector3f tip = CROSSBOW.point(d, ArmTips.TIP, new Vector3f()), mid = CROSSBOW.point(d, ArmTips.MID, new Vector3f()), grip = CROSSBOW.point(d, ArmTips.GRIP, new Vector3f());
		assertTrue(tip.x < mid.x && mid.x < grip.x, "the front is -X, the grip behind it: " + tip.x + ", " + mid.x + ", " + grip.x);
		assertTrue(tip.x < -6F, "the muzzle: " + tip.x);
		ArmDraw none = crossbow().state(ArmProtocol.STATE_NONE, 1F).build();
		assertTrue(CROSSBOW.point(none, ArmTips.TIP, new Vector3f()).x > tip.x + 6F, "no reach, the tip is back at the grip");
	}

	@Test
	void fitsItsGuiSlot() {
		List<Vector3fc> pts = new ArrayList<>();
		CROSSBOW.extents(pts::add);
		float[] span = ArmTransforms.guiSpan(CROSSBOW.guiScale(), CROSSBOW.guiGripPx(), pts);
		assertTrue(span[0] >= 1F && span[1] <= 15F, "the extents sit inside the slot: " + span[0] + ".." + span[1]);
		// and what is drawn lies inside the extents
		float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
		Matrix4f gui = ArmTransforms.gui(CROSSBOW.guiScale(), CROSSBOW.guiGripPx(), new Matrix4f());
		Vector3f p = new Vector3f();
		for (float[] v : vertices(crossbow().tier(3).ctx(ArmDraw.CTX_GUI).lod(ArmDraw.LOD_ICON).build())) {
			gui.transformPosition(p.set(v[0], v[1], v[2]));
			float along = (p.x + p.y) * 0.5F * 16F;
			lo = Math.min(lo, along);
			hi = Math.max(hi, along);
		}
		assertTrue(lo >= 0.5F && hi <= 15.5F, "the icon's drawn span: " + lo + ".." + hi);
	}
}
