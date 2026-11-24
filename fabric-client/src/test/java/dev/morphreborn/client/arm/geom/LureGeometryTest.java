package dev.morphreborn.client.arm.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.SkinMesh;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The lantern's lure against section 2.11: where it hangs (the point the sheath layer records for the beam), that it grows
 * out of the arm and then sprouts, what it costs in quads, that it mirrors for the other hand and follows the glow setting,
 * that a flare bursts its cage and the cage grows back, that its rope keeps its length and swings against a camera turn,
 * and that every state, view and level of detail draws real geometry (no NaN, no zero normals).
 */
class LureGeometryTest {
	private static final LureGeometry LURE = new LureGeometry();
	private static final ArmLayer[] LAYERS = {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW};

	/** A lit lure's draw to change field by field. */
	static final class Lit {
		int ctx = ArmDraw.CTX_TP, lod = 0, seed = 7;
		boolean leftHand, slim, chest, glow = true, dissolve = true;
		String form = "";
		int state = ArmProtocol.STATE_NONE;
		float progress = 1F;
		float time = 100F, age = 1000F, biomassFrac = 1F, biomassAbs = ArmDraw.FULL;
		float downX, downY = -1F, downZ;

		Lit ctx(int c) {
			ctx = c;
			return this;
		}

		Lit lod(int l) {
			lod = l;
			return this;
		}

		/** A tool's arm out with its own timeline (the lantern lit on top of it). */
		Lit tool(String f, int s, float p) {
			form = f;
			state = s;
			progress = p;
			return this;
		}

		Lit age(float a) {
			age = a;
			return this;
		}

		Lit time(float t) {
			time = t;
			return this;
		}

		Lit down(float x, float y, float z) {
			downX = x;
			downY = y;
			downZ = z;
			return this;
		}

		ArmDraw build() {
			return new ArmDraw(form, form.isEmpty() ? 0 : 1, lod, ctx, leftHand, slim, chest, time, seed, state, progress, "", 0F, 0F, false, biomassFrac, biomassAbs, 0F,
					ArmDraw.NEVER, ArmDraw.NO_SPECIAL, ArmDraw.NEVER, ArmDraw.NEVER, new float[0], 0, true, age, 0F, glow, dissolve, false, null, downX, downY,
					downZ, null, 0xF000F0);
		}
	}

	private static Lit lit() {
		return new Lit();
	}

	private static ArmBudgetTest.Recorder emit(ArmDraw d, ArmLayer layer) {
		ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
		SkinMesh m = SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0);
		if (d.firstPerson()) LURE.emitFirstPerson(d, layer, m);
		else LURE.emitThirdPerson(d, layer, m);
		assertEquals(r.quads(), m.quads(), "quads counted twice or lost");
		return r;
	}

	private static int quads(ArmDraw d) {
		int total = 0;
		for (ArmLayer layer : LAYERS) total += emit(d, layer).quads();
		return total;
	}

	@Test
	void theHolderGrowsInAsAToolsStaticSlidesOffInsteadOfPopping() {
		int retract = ArmProtocol.duration(ArmProtocol.STATE_RETRACTING);
		int formed = quads(lit().tool("katana", ArmProtocol.STATE_FORMED, 1F).build());
		// the static still covers the arm until retract tick 23: no holder yet, as before
		assertEquals(formed, quads(lit().tool("katana", ArmProtocol.STATE_RETRACTING, 22F / retract).build()));
		int mid = quads(lit().tool("katana", ArmProtocol.STATE_RETRACTING, 26.5F / retract).build());
		int bare = quads(lit().build());
		assertTrue(mid > formed, "the holder has begun: " + mid + " over " + formed);
		assertTrue(mid < bare, "and is not all there yet: " + mid + " under " + bare);
		// and the reverse: a tool forming over a lit lantern takes the holder back as its static covers the arm
		int forming = quads(lit().tool("katana", ArmProtocol.STATE_FORMING, 6F / ArmProtocol.duration(ArmProtocol.STATE_FORMING)).build());
		assertTrue(forming > formed && forming <= bare, "withdrawing: " + forming + " between " + formed + " and " + bare);
	}

	@BeforeEach
	@AfterEach
	void forget() {
		LureGeometry.clear();
		LureGeometry.setOffhand(0F);
	}

	// ------------------------------------------------------------------ where it hangs

	@Test
	void isTheRealLureAndFoundByName() {
		assertTrue(ArmGeometry.lure() instanceof LureGeometry, "ArmGeometry found " + ArmGeometry.lure());
	}

	@Test
	void restPointsAreTheOnesTheBeamIsAimedFrom() {
		Vector3f p = new Vector3f();
		// right-handed: over the blade of the form in the right hand (A space: the arm's centre x -1), the hook 1.5 px
		// down the arm and 11 in front, the core five 1 px links and the cage's cap (1.5) under it
		LureGeometry.restPointThirdPerson(false, false, p);
		assertEquals(-1F, p.x, 1e-5F);
		assertEquals(1.5F + 5F + 1.5F, p.y, 1e-5F);
		assertEquals(-11F, p.z, 1e-5F);
		LureGeometry.restPointThirdPerson(false, true, p);
		assertEquals(1F, p.x, 1e-5F, "left-handed: the left arm, mirrored");
		LureGeometry.restPointThirdPerson(true, false, p);
		assertEquals(-0.5F, p.x, 1e-5F, "slim arms are narrower");
		// first person: the arch end (0.45, -0.10, -0.80) blocks on the right, the core the chain (5 × 0.8) and the cap under it
		LureGeometry.restPointFirstPerson(false, 0F, p);
		assertEquals(0.45F * 16F, p.x, 1e-4F);
		assertEquals(-0.10F * 16F - 4F - 1.5F, p.y, 1e-4F);
		assertEquals(-0.80F * 16F, p.z, 1e-4F);
		LureGeometry.restPointFirstPerson(true, 0F, p);
		assertEquals(-0.45F * 16F, p.x, 1e-4F, "left-handed: mirrored");
		LureGeometry.restPointFirstPerson(false, 1F, p);
		assertEquals(0.36F * 16F, p.x, 1e-4F, "with the other hand busy the arch end moves in");
		assertEquals(-0.04F * 16F - 4F - 1.5F, p.y, 1e-4F);
		assertEquals(-0.85F * 16F, p.z, 1e-4F);
	}

	@Test
	void theDrawnLureHangsFromTheStalkEndWhereTheRestPointSays() {
		Vector3f rest = LureGeometry.restPointThirdPerson(false, false, new Vector3f());
		quads(lit().build()); // a doll: nothing to swing from, so it hangs straight down the arm
		float[] rope = LureGeometry.lastRope();
		int e = 3 * (LureGeometry.NODES - 1);
		// the chain's last node is the cage's cap: the core hangs 1.5 under it, within the slow sway's reach of the rest point
		assertEquals(rest.y - 1.5F, rope[e + 1], 0.3F, "cap height");
		assertEquals(rest.x, rope[e], 1.2F, "cap x (the still hang sways across the five links)");
		assertEquals(rest.z, rope[e + 2], 1.2F, "cap z");
		for (int i = 0; i + 1 < LureGeometry.NODES; i++) {
			int a = 3 * i, b = a + 3;
			assertEquals(1F, (float) Math.sqrt(sq(rope[b] - rope[a]) + sq(rope[b + 1] - rope[a + 1]) + sq(rope[b + 2] - rope[a + 2])), 1e-3F, "link " + i);
		}
		quads(lit().ctx(ArmDraw.CTX_FP).build());
		Vector3f fp = LureGeometry.restPointFirstPerson(false, 0F, new Vector3f());
		rope = LureGeometry.lastRope();
		assertEquals(fp.y + 1.5F, rope[e + 1], 0.3F, "first person: the chain's length under the arch end");
		assertEquals(fp.x, rope[e], 1.2F);
		assertEquals(fp.z, rope[e + 2], 1.2F);
	}

	private static float sq(float v) {
		return v * v;
	}

	// ------------------------------------------------------------------ growth

	@Test
	void growsOutOfTheArmThenSproutsItsBristles() {
		assertEquals(0, quads(lit().age(0F).build()), "the instant it is lit, nothing is out yet");
		int stalk = quads(lit().age(2F).build());
		assertTrue(stalk > 0, "the stalk is coming out");
		float stalkOut = LureGeometry.HOLD_LEAD + LureGeometry.STALK_TICKS;
		int grown = quads(lit().age(stalkOut).build());
		int formed = quads(lit().age(60F).build());
		assertTrue(grown > stalk, "growing: " + stalk + " then " + grown);
		assertTrue(formed > grown + 60, "the details come in after the stalk is out: " + grown + " at " + stalkOut + ", " + formed + " at 60");
		// every step of growth only adds
		int last = 0;
		for (float a = 0F; a <= 45F; a += 0.5F) {
			int q = quads(lit().age(a).build());
			assertTrue(q >= last, "age " + a + " drew " + q + " after " + last);
			last = q;
		}
		assertEquals(formed, quads(lit().age(100000F).build()), "a lure lit long ago is just there");
	}

	@Test
	void theCoreIgnitesAfterTheStalkIsOut() {
		// the lantern on its own: the holder first, the stalk HOLD_LEAD later, then the core
		float ignites = LureGeometry.HOLD_LEAD + LureGeometry.CORE_FROM;
		assertEquals(0, emit(lit().age(ignites - 1F).build(), ArmLayer.GLOW).quads(), "no light yet");
		assertTrue(emit(lit().age(ignites + LureGeometry.CORE_TICKS).build(), ArmLayer.GLOW).quads() >= 12, "the core, two boxes");
	}

	// ------------------------------------------------------------------ budget

	@Test
	void costsWhatItShouldAtEveryDistance() {
		int tp = worst(lit());
		int fp = worst(lit().ctx(ArmDraw.CTX_FP));
		int lod1 = worst(lit().lod(1)), lod2 = worst(lit().lod(2)), lod3 = worst(lit().lod(3));
		System.out.printf("lure quads: TP LOD0 %d, FP LOD0 %d, LOD1 %d, LOD2 %d, LOD3 %d%n", tp, fp, lod1, lod2, lod3);
		// the chain's links (five, 18 quads each, up close) are most of the rise since the rope
		// third person with only the lantern out also draws what holds it on the skin arm (the sleeve's tendrils, the
		// spines and the root knot, 2026-11-16): an arm with no tool, far under an armed arm's 1,200
		assertTrue(tp >= 150 && tp <= 540, "third person: " + tp);
		assertTrue(fp >= 150 && fp <= 420, "first person: " + fp);
		assertTrue(lod1 <= 110, "LOD 1: " + lod1);
		assertTrue(lod2 <= 30, "LOD 2: " + lod2);
		assertTrue(lod3 <= 14, "LOD 3: " + lod3);
		assertTrue(lod3 < lod2 && lod2 < lod1 && lod1 < tp, "each level of detail should cost less than the one before");
	}

	/** The most quads over a stretch of time (the flicker, the sway, the swelling pulses move things). */
	private static int worst(Lit b) {
		int max = 0;
		for (float t = 0F; t < 300F; t += 3.7F) max = Math.max(max, quads(b.time(t).build()));
		return max;
	}

	// ------------------------------------------------------------------ views

	@Test
	void mirrorsForTheOtherHand() {
		float right = meanX(lit()), left;
		Lit l = lit();
		l.leftHand = true;
		left = meanX(l);
		// the main arm is the right one, and the lure hangs over it (its outer face is -x in A space); left-handed, mirrored
		assertTrue(right < -0.5F, "over the right hand: mean x " + right);
		assertTrue(left > 0.5F, "over the left hand: mean x " + left);
		float fpRight = meanX(lit().ctx(ArmDraw.CTX_FP));
		Lit fl = lit().ctx(ArmDraw.CTX_FP);
		fl.leftHand = true;
		float fpLeft = meanX(fl);
		assertTrue(fpRight > 3F, "first person, right-handed: in from the right of the screen, mean x " + fpRight);
		assertTrue(fpLeft < -3F, "first person, left-handed: mirrored, mean x " + fpLeft);
	}

	private static float meanX(Lit b) {
		float sum = 0F;
		int n = 0;
		for (ArmLayer layer : LAYERS) {
			ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
			r.keep = true;
			ArmDraw d = b.build();
			SkinMesh m = SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0);
			if (d.firstPerson()) LURE.emitFirstPerson(d, layer, m);
			else LURE.emitThirdPerson(d, layer, m);
			for (float[] v : r.kept) {
				sum += v[0];
				n++;
			}
		}
		return n == 0 ? 0F : sum / n;
	}

	@Test
	void theGlowSettingMovesTheCoreFromTheGlowLayerToTheRedOne() {
		Lit on = lit();
		Lit off = lit();
		off.glow = false;
		assertTrue(emit(on.build(), ArmLayer.GLOW).quads() > 0);
		assertEquals(0, emit(off.build(), ArmLayer.GLOW).quads(), "no glow layer with the glow off");
		assertTrue(emit(off.build(), ArmLayer.RED).quads() > emit(on.build(), ArmLayer.RED).quads(), "the core is red static instead");
	}

	@Test
	void dissolveOffCutsTheGrowthFrontHard() {
		Lit hard = lit().age(4F);
		hard.dissolve = false;
		assertTrue(quads(hard.build()) > 0);
		assertTrue(quads(lit().age(4F).build()) > 0);
	}

	@Test
	void notLitDrawsNothing() {
		ArmDraw d = lit().build();
		ArmDraw dark = new ArmDraw(d.form(), d.tier(), d.lod(), d.ctx(), d.leftHand(), d.slim(), d.chestArmour(), d.time(), d.seed(), d.state(), d.progress(),
				d.fromForm(), d.swing(), d.bowPull(), d.mining(), d.biomassFrac(), d.biomassAbs(), d.surge(), d.specialAge(), d.specialKind(), d.hitAge(),
				d.breakAge(), d.pulses(), d.pulseCount(), false, 0F, d.recallLeft(), d.glow(), d.dissolve(), d.drips(), null, d.downX(), d.downY(), d.downZ(), null,
				d.light());
		assertEquals(0, quads(dark));
	}

	// ------------------------------------------------------------------ the flare

	@Test
	void aFlareBurstsTheCageAndItGrowsBack() {
		long fired = 1000L;
		float calm = emit(lit().time(fired - 5F).build(), ArmLayer.BLACK).quads();
		LureGeometry.flared(7, fired);
		float swelling = emit(lit().time(fired + 3F).build(), ArmLayer.BLACK).quads();
		float bare = emit(lit().time(fired + 8.5F).build(), ArmLayer.BLACK).quads();
		float regrowing = emit(lit().time(fired + 14F).build(), ArmLayer.BLACK).quads();
		float later = emit(lit().time(fired + 60F).build(), ArmLayer.BLACK).quads();
		assertEquals(calm, swelling, "the cage is still there as the core swells");
		assertTrue(bare < calm - 40F, "burst: the ribs are gone (" + bare + " against " + calm + ")");
		assertTrue(regrowing > bare && regrowing <= calm, "growing back");
		assertEquals(calm, later, "and back to normal once the flare is over");
		// the core swells to about twice its size before it bursts
		// (the core's own flicker moves its size by up to a fifth either way, so the bounds are loose)
		float before = glowSpan(lit().time(fired - 5F)), peak = glowSpan(lit().time(fired + 4.9F)), after = glowSpan(lit().time(fired + 60F));
		assertTrue(peak > before * 1.45F && peak < before * 2.7F, "core " + before + " px across at rest, " + peak + " at the burst");
		assertEquals(before, after, before * 0.4F, "and it settles");
	}

	/** The core's big box's diagonal (its first 24 vertices; a cube's diagonal doesn't change as it turns). */
	private static float glowSpan(Lit b) {
		ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
		r.keep = true;
		ArmDraw d = b.build();
		SkinMesh m = SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0);
		LURE.emitThirdPerson(d, ArmLayer.GLOW, m);
		float best = 0F;
		int n = Math.min(24, r.kept.size());
		for (int i = 0; i < n; i++) {
			for (int j = i + 1; j < n; j++) {
				float[] a = r.kept.get(i), c = r.kept.get(j);
				best = Math.max(best, (float) Math.sqrt(sq(a[0] - c[0]) + sq(a[1] - c[1]) + sq(a[2] - c[2])));
			}
		}
		return best;
	}

	@Test
	void burstAgeIsOnThePlayersOwnClockAndForgetsItself() {
		assertEquals(ArmDraw.NEVER, LureGeometry.burstAge(9, 500F));
		LureGeometry.flared(9, 500L);
		assertEquals(0F, LureGeometry.burstAge(9, 500F), 1e-4F);
		assertEquals(12.5F, LureGeometry.burstAge(9, 512.5F), 1e-4F);
		assertEquals(ArmDraw.NEVER, LureGeometry.burstAge(9, 700F), "long past");
		assertEquals(ArmDraw.NEVER, LureGeometry.burstAge(8, 500F), "someone else's");
		// the draw's clock wraps hourly: a flare just before the wrap is still found just after it
		LureGeometry.flared(9, 71999L);
		assertEquals(3F, LureGeometry.burstAge(9, 2F), 1e-4F);
	}

	// ------------------------------------------------------------------ the rope

	@Test
	void ropeHangsStillAndKeepsItsLengthWhenTheRootJumps() {
		LureGeometry.Hang h = new LureGeometry.Hang();
		h.reset(0F, 0F, 0F, 0F, 1F, 0F, 1.2F);
		for (int i = 0; i < 120; i++) h.step(0F, 0F, 0F, 0F, 1F, 0F, 1.2F, 1F, 0F, 0F);
		assertEquals(1.2F, h.p[4], 0.02F, "node 1 hangs one link down");
		assertEquals(2.4F, h.p[7], 0.02F, "node 2 two links down");
		assertEquals(0F, h.p[6], 0.02F);
		// the root jumps 3 px sideways: the rope swings after it and never stretches
		float maxStretch = 0F;
		for (int i = 0; i < 40; i++) {
			h.step(i == 0 ? 3F : 3F, 0F, 0F, 0F, 1F, 0F, 1.2F, 1F, 0F, 0F);
			float l1 = (float) Math.sqrt(sq(h.p[3] - h.p[0]) + sq(h.p[4] - h.p[1]) + sq(h.p[5] - h.p[2]));
			float l2 = (float) Math.sqrt(sq(h.p[6] - h.p[3]) + sq(h.p[7] - h.p[4]) + sq(h.p[8] - h.p[5]));
			maxStretch = Math.max(maxStretch, Math.max(Math.abs(l1 - 1.2F), Math.abs(l2 - 1.2F)));
		}
		assertTrue(maxStretch < 0.02F, "the links stayed " + maxStretch + " off their length");
		for (int i = 0; i < 200; i++) h.step(3F, 0F, 0F, 0F, 1F, 0F, 1.2F, 1F, 0F, 0F);
		assertEquals(3F, h.p[6], 0.05F, "and settles under the new root");
		assertEquals(2.4F, h.p[7], 0.05F);
	}

	@Test
	void aCameraTurnSwingsTheFirstPersonRopeAndItSettles() {
		LureGeometry.Hang h = new LureGeometry.Hang();
		// the view-space hang: 10 px ahead (z -10), hanging down (y -1)
		h.reset(0F, 0F, -10F, 0F, -1F, 0F, 0.96F);
		for (int i = 0; i < 60; i++) h.step(0F, 0F, -10F, 0F, -1F, 0F, 0.96F, 1F, 0F, 0F);
		assertEquals(0F, h.p[6], 0.02F);
		// the view turns 0.2 rad to the right: what hangs in the world stays, so it is pushed left of the root
		h.step(0F, 0F, -10F, 0F, -1F, 0F, 0.96F, 1F, 0.2F, 0F);
		assertTrue(h.p[6] < -0.3F, "swung left by the turn: x " + h.p[6]);
		// and then it comes back and hangs still
		for (int i = 0; i < 200; i++) h.step(0F, 0F, -10F, 0F, -1F, 0F, 0.96F, 1F, 0F, 0F);
		assertEquals(0F, h.p[6], 0.05F, "and settles");
		assertEquals(-1.92F, h.p[7], 0.05F);
	}

	@Test
	void aPausedGameHoldsTheRopeInShape() {
		LureGeometry.Hang h = new LureGeometry.Hang();
		h.reset(0F, 0F, 0F, 0F, 1F, 0F, 1.2F);
		h.step(0F, 0F, 0F, 1F, 0F, 0F, 1.2F, 1F, 0F, 0F); // gravity sideways: it starts to swing
		float x = h.p[6], y = h.p[7];
		h.step(0F, 0F, 0F, 1F, 0F, 0F, 1.2F, 0F, 0F, 0F);
		assertEquals(x, h.p[6], 1e-4F, "no time passed");
		assertEquals(y, h.p[7], 1e-4F);
	}

	@Test
	void aLiveRopeIsSteppedOncePerFrameNotOncePerLayer() {
		// a world draw: gravity tilted in the arm's space, so the rope has somewhere to swing
		Lit a = lit().time(200F).down(0.6F, 0.8F, 0F);
		quads(a.build()); // first frame: starts hanging along its gravity
		float[] first = LureGeometry.lastRope();
		// the next frame turns gravity: one step of the swing, not all of it
		Lit b = lit().time(201F).down(-0.6F, 0.8F, 0F);
		ArmDraw frame = b.build();
		emit(frame, ArmLayer.BLACK);
		float[] once = LureGeometry.lastRope();
		emit(frame, ArmLayer.RED);
		emit(frame, ArmLayer.GLOW);
		float[] thrice = LureGeometry.lastRope();
		assertNotEquals(first[6], once[6], 1e-3F, "the rope moved");
		assertEquals(once[6], thrice[6], 1e-6F, "and the other layers of the same frame didn't move it again");
		assertEquals(once[7], thrice[7], 1e-6F);
		// it has not jumped to rest along the new gravity either: it is part of the way
		float restX = once[0] - 0.6F * 2.4F;
		assertTrue(Math.abs(once[6] - restX) > 0.1F, "mid-swing, x " + once[6] + " on its way to " + restX);
	}

	// ------------------------------------------------------------------ everything draws

	@Test
	void everyStateViewAndLevelOfDetailDrawsRealGeometry() {
		float[] ages = {0F, 1F, 3F, 6F, 7F, 9F, 12F, 20F, 500F};
		float[][] downs = {{0F, -1F, 0F}, {0F, 1F, 0F}, {0.3F, 0.9F, 0.1F}, {-1F, 0F, 0F}, {0F, 0.01F, 1F}};
		for (int ctx : new int[] {ArmDraw.CTX_TP, ArmDraw.CTX_FP, ArmDraw.CTX_GUI}) {
			for (int lod = 0; lod <= 3; lod++) {
				for (float age : ages) {
					for (float[] down : downs) {
						for (int variant = 0; variant < 4; variant++) {
							Lit b = lit().ctx(ctx).lod(lod).age(age).down(down[0], down[1], down[2]).time(300F + age);
							b.leftHand = (variant & 1) != 0;
							b.slim = (variant & 2) != 0;
							b.chest = variant == 3;
							b.glow = variant != 2;
							b.dissolve = variant != 1;
							b.seed = 40 + variant;
							if (variant == 2) {
								b.biomassFrac = 0.01F;
								b.biomassAbs = 1F;
							}
							quads(b.build());
						}
					}
				}
			}
		}
		// and with a flare going off, a camera turning and the other hand busy
		LureGeometry.flared(7, 300L);
		LureGeometry.setOffhand(1F);
		for (float t = 295F; t < 340F; t += 0.7F) {
			LureGeometry.cameraStep(3F, -2F);
			quads(lit().ctx(ArmDraw.CTX_FP).time(t).down(0.1F, -0.9F, 0.2F).build());
			quads(lit().time(t).down(0.1F, 0.9F, 0.2F).build());
		}
	}

	@Test
	void theCoreIsInsideItsCage() {
		// the cage's ribs stay within a couple of pixels of the core: a runaway rib would show as a long spike
		ArmBudgetTest.Recorder black = new ArmBudgetTest.Recorder();
		black.keep = true;
		ArmDraw d = lit().build();
		LURE.emitThirdPerson(d, ArmLayer.BLACK, SkinMesh.begin(new PoseStack().last(), black, 0xF000F0, 0));
		float[] rope = LureGeometry.lastRope();
		float far = 0F;
		for (float[] v : black.kept) {
			// the arm's own fixtures (stalk, clutch) are far from the core; only what is near it counts
			float dist = (float) Math.sqrt(sq(v[0] - rope[6]) + sq(v[1] - rope[7]) + sq(v[2] - rope[8]));
			if (dist < 3.5F) far = Math.max(far, dist);
		}
		assertTrue(far > 0.8F, "there is a cage round the core");
		assertFalse(black.kept.isEmpty());
	}

	// ------------------------------------------------------------------ near the lens (First-person Model's body, 2026-11-24)

	/** Every vertex the lure writes for {@code d}, all layers, in the space the mesh was begun in. */
	private static java.util.List<float[]> kept(ArmDraw d) {
		java.util.List<float[]> all = new java.util.ArrayList<>();
		for (ArmLayer layer : LAYERS) {
			ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
			r.keep = true;
			SkinMesh m = SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0);
			LURE.emitThirdPerson(d, layer, m);
			all.addAll(r.kept);
		}
		return all;
	}

	@AfterEach
	void backToTheDefaultView() {
		AccessoryView.set(1F, false);
		LureGeometry.worldFrame(null, 0, 0, 0);
	}

	@Test
	void nearTheLensTheFreePartIsTheSameLureSmallerAboutItsRootAndNothingElseChanges() {
		// a tool formed under the lantern: its own arm holds the stalk, so there is no holder and everything drawn is the free part
		ArmDraw d = lit().tool("katana", ArmProtocol.STATE_FORMED, 1F).build();
		java.util.List<float[]> whole = kept(d);
		assertFalse(whole.isEmpty());
		float near = 0.4F;
		AccessoryView.set(near, true);
		java.util.List<float[]> small = kept(d);
		assertEquals(whole.size(), small.size(), "the same quads");
		// the stalk's root on the arm: right arm, wide, no armour look (the default look is the tendril arm): (xo - 0.4 out, 5.0, -0.6)
		Vector3f root = new Vector3f(-3F + 0.4F, 5.0F, -0.6F);
		for (int i = 0; i < whole.size(); i++) {
			float[] a = whole.get(i), b = small.get(i);
			for (int k = 0; k < 3; k++) assertEquals(root.get(k) + near * (a[k] - root.get(k)), b[k], 2e-3F, "vertex " + i + " axis " + k);
		}
		// and the default view is unchanged by all that
		AccessoryView.set(1F, false);
		java.util.List<float[]> again = kept(d);
		for (int i = 0; i < whole.size(); i++) for (int k = 0; k < 3; k++) assertEquals(whole.get(i)[k], again.get(i)[k], 1e-6F);
	}

	@Test
	void theHolderOnTheBareArmIsNotShrunkOnlyWhatHangsFromIt() {
		ArmDraw d = lit().build(); // the lantern alone: sleeve, spines and a root knot on the skin arm, then the stalk
		java.util.List<float[]> whole = kept(d);
		AccessoryView.set(0.3F, true);
		java.util.List<float[]> small = kept(d);
		// the holder (a ring of tendrils on the forearm, y 5.7 .. 9.4 down from the wrist) is drawn first, in the same place
		int same = 0;
		for (int i = 0; i < Math.min(whole.size(), small.size()); i++) {
			if (Math.abs(whole.get(i)[0] - small.get(i)[0]) < 1e-6F && Math.abs(whole.get(i)[1] - small.get(i)[1]) < 1e-6F && Math.abs(whole.get(i)[2] - small.get(i)[2]) < 1e-6F) same++;
		}
		assertTrue(same > 40, "the sleeve, the spines and the knot stay put: " + same + " vertices unchanged");
		float farWhole = 0F, farSmall = 0F;
		Vector3f ref = new Vector3f(-3.8F, 4.4F, 0F);
		for (float[] p : whole) farWhole = Math.max(farWhole, ref.distance(p[0], p[1], p[2]));
		for (float[] p : small) farSmall = Math.max(farSmall, ref.distance(p[0], p[1], p[2]));
		assertTrue(farSmall < 0.6F * farWhole, "the reach shrank: " + farSmall + " against " + farWhole);
	}

	@Test
	void theChainSwingsInTheWorldAtTheSmallerScaleAndKeepsItsLengthThereToo() {
		// a world draw (as the sheath layer sets it up): the arm's px to blocks, the lure's chain simulated in the world
		Matrix4f pose = new Matrix4f().scale(1F / 16F);
		float near = 0.4F;
		AccessoryView.set(near, true);
		float[] last = null;
		for (int frame = 0; frame < 160; frame++) {
			LureGeometry.worldFrame(pose, 100.0, 64.0, 100.0);
			ArmDraw d = lit().tool("katana", ArmProtocol.STATE_FORMED, 1F).time(200F + frame).down(0.02F, -1F, 0F).build(); // not the doll's straight-down: a world draw
			emit(d, ArmLayer.BLACK);
			last = LureGeometry.lastRope();
		}
		int e = 3 * (LureGeometry.NODES - 1);
		// drawn in the lure's own space, the links are as long as ever; through the scale they are 0.4 of it in the world
		for (int i = 0; i + 1 < LureGeometry.NODES; i++) {
			int a = 3 * i, b = a + 3;
			assertEquals(1F, (float) Math.sqrt(sq(last[b] - last[a]) + sq(last[b + 1] - last[a + 1]) + sq(last[b + 2] - last[a + 2])), 0.03F, "link " + i);
		}
		// settled under its hook, straight down in the world (the pose here keeps y up, so down is -y): one chain's length in the lure's own space
		assertEquals(last[1] - (LureGeometry.NODES - 1), last[e + 1], 0.15F, "hangs one chain's length straight down");
		assertEquals(last[0], last[e], 0.1F);
		LureGeometry.clear();
	}

	// ------------------------------------------------------------------ the sheath smaller near the lens (second pass, 2026-11-24)

	/** The point the arm form's sheath carries {@code p} to when it is drawn at {@code s} about its hand end (ArmSheathLayer's translate, scale, translate). */
	private static Vector3f underSheath(Vector3f p, float s) {
		float hy = dev.morphreborn.client.arm.model.NearBodyScale.SHEATH_HAND_Y;
		return new Vector3f(s * p.x, hy + s * (p.y - hy), s * p.z);
	}

	@Test
	void aToolsOwnArmCarriesTheStalkRootWithItWhenTheSheathIsSmallerNearTheLens() {
		for (int look : new int[] {ArmProtocol.LOOK_ARM, ArmProtocol.LOOK_ARMOUR}) {
			SheathGeometry.lookOverride = look;
			try {
				for (boolean left : new boolean[] {false, true}) {
					Lit lit = lit().tool("katana", ArmProtocol.STATE_FORMED, 1F);
					lit.leftHand = left;
					ArmDraw d = lit.build();
					Vector3f root = LureGeometry.rootPoint(d, 0F, new Vector3f());
					AccessoryView.set(1F, true, 1F);
					java.util.List<float[]> whole = kept(d);
					float near = 0.4F, sheath = 0.6F;
					AccessoryView.set(near, true, sheath);
					java.util.List<float[]> small = kept(d);
					assertEquals(whole.size(), small.size(), "the same quads");
					Vector3f moved = underSheath(root, sheath);
					assertTrue(moved.distance(root) > 1.5F, "the sheath's shrink really moves the root: look " + look + " by " + moved.distance(root));
					for (int i = 0; i < whole.size(); i++) {
						float[] a = whole.get(i), b = small.get(i);
						for (int k = 0; k < 3; k++) assertEquals(moved.get(k) + near * (a[k] - root.get(k)), b[k], 2e-3F, "look " + look + " vertex " + i + " axis " + k);
					}
				}
			} finally {
				SheathGeometry.lookOverride = -1;
			}
		}
	}

	@Test
	void onThePlainHandOrWithNoToolTheRootStaysWhereTheRealArmHoldsIt() {
		// the plain hand keeps the skin arm whole, and the lantern alone has the skin arm's holder: the sheath's scale is none of the root's business
		SheathGeometry.lookOverride = ArmProtocol.LOOK_HAND;
		try {
			ArmDraw hand = lit().tool("katana", ArmProtocol.STATE_FORMED, 1F).build();
			AccessoryView.set(0.4F, true, 1F);
			java.util.List<float[]> a = kept(hand);
			AccessoryView.set(0.4F, true, 0.5F);
			java.util.List<float[]> b = kept(hand);
			assertEquals(a.size(), b.size());
			for (int i = 0; i < a.size(); i++) for (int k = 0; k < 3; k++) assertEquals(a.get(i)[k], b.get(i)[k], 1e-6F, "hand look vertex " + i);
		} finally {
			SheathGeometry.lookOverride = -1;
		}
		ArmDraw alone = lit().build();
		AccessoryView.set(0.4F, true, 1F);
		java.util.List<float[]> a = kept(alone);
		AccessoryView.set(0.4F, true, 0.5F);
		java.util.List<float[]> b = kept(alone);
		assertEquals(a.size(), b.size());
		for (int i = 0; i < a.size(); i++) for (int k = 0; k < 3; k++) assertEquals(a.get(i)[k], b.get(i)[k], 1e-6F, "lantern alone vertex " + i);
	}

	@Test
	void theRootSlidesWithTheHoldersBlendWhileAToolArrives() {
		int forming = ArmProtocol.duration(ArmProtocol.STATE_FORMING);
		ArmDraw mid = lit().tool("katana", ArmProtocol.STATE_FORMING, 6F / forming).build();
		float w = mid.armStatic();
		assertTrue(w > 0.05F && w < 0.95F, "a partly formed arm: " + w);
		float sheath = 0.5F;
		Vector3f root = LureGeometry.rootPoint(mid, 1F - w, new Vector3f());
		Vector3f at = LureGeometry.freeTransform(mid, 1F, sheath, new Matrix4f()).transformPosition(root.x, root.y, root.z, new Vector3f());
		Vector3f full = underSheath(root, sheath);
		// by that share of the way from where the root is to where the sheath carries it
		assertEquals(root.y + w * (full.y - root.y), at.y, 1e-4F);
		assertEquals(root.x + w * (full.x - root.x), at.x, 1e-4F);
		// and no change at all with the sheath whole and the lens far
		Vector3f same = LureGeometry.freeTransform(mid, 1F, 1F, new Matrix4f()).transformPosition(root.x, root.y, root.z, new Vector3f());
		assertEquals(0F, same.distance(root), 1e-5F);
	}
}
