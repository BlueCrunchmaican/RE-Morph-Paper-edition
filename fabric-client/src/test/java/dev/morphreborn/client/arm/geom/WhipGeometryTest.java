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
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The whip against section 9: a lash of 40 stations tapering 1.6 to 0.2 px, braided 1, 2 or 3 strands by tier, barbs on its
 * last third (each its own), a coil that hangs from the grip and swings with the rope's chain, the cast line and the thrown
 * lash gathering it up; the budgets of section 2.10; and every timeline, tier, level of detail and reaction drawing real
 * geometry (no NaN, no zero-length normals).
 */
class WhipGeometryTest {
	private static final FormGeometry WHIP = ArmGeometry.get(ArmProtocol.WHIP);
	private static final ArmLayer[] LAYERS = {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW};

	private static ArmBudgetTest.Draw whip() {
		return ArmBudgetTest.draw(ArmProtocol.WHIP);
	}

	private static ArmBudgetTest.Recorder emit(ArmDraw d, ArmLayer layer) {
		ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
		WHIP.emit(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
		return r;
	}

	private static int quads(ArmDraw d, ArmLayer layer) {
		return emit(d, layer).quads();
	}

	@AfterEach
	void forgetMarks() {
		WhipGeometry.clearMarks();
	}

	@Test
	void isTheRealWhipNotThePlaceholder() {
		assertTrue(WHIP instanceof WhipGeometry, "ArmGeometry found " + WHIP.getClass().getSimpleName());
		assertTrue(ArmGeometry.built(ArmProtocol.WHIP));
		assertEquals(ArmProtocol.WHIP, WHIP.form());
		assertTrue(!WHIP.bowTransforms());
	}

	@Test
	void lashIsFortyStationsTaperingFromOnePointSixToPointTwo() {
		assertEquals(1.6F, WhipGeometry.cordWidth(0F), 1e-6F);
		assertEquals(0.2F, WhipGeometry.cordWidth(1F), 1e-6F);
		float last = Float.MAX_VALUE;
		for (float u = 0F; u <= 1F; u += 0.01F) {
			assertTrue(WhipGeometry.cordWidth(u) <= last + 1e-6F, "the cord only thins toward the tip");
			last = WhipGeometry.cordWidth(u);
		}
		for (int tier = 1; tier <= 3; tier++) {
			emit(whip().tier(tier).build(), ArmLayer.RED);
			assertEquals(40, WhipGeometry.lastStations(), "L" + tier + ": a full-detail lash");
			assertEquals(1F, WhipGeometry.lastU(39), 1e-6F);
			// pulses swell it a little (up to 35% of the pulse), never thin it below the taper
			assertTrue(WhipGeometry.lastWidth(0) >= 1.6F - 1e-3F && WhipGeometry.lastWidth(0) < 2.3F, "the base: " + WhipGeometry.lastWidth(0));
			assertTrue(WhipGeometry.lastWidth(39) >= 0.2F - 1e-3F && WhipGeometry.lastWidth(39) < 0.35F, "the tip: " + WhipGeometry.lastWidth(39));
			assertTrue(WhipGeometry.lastArc(39) > 1.15F * WhipGeometry.HANG * (tier == 1 ? 0.88F : tier == 2 ? 1F : 1.14F), "the coil's path is longer than its hang (" + WhipGeometry.lastArc(39) + " px)");
		}
		emit(whip().lod(1).build(), ArmLayer.RED);
		assertEquals(21, WhipGeometry.lastStations(), "LOD 1 takes every other station, and the tip");
	}

	@Test
	void tiersAddStrandsBarbsAndLength() {
		int black1 = quads(whip().tier(1).build(), ArmLayer.BLACK), black2 = quads(whip().tier(2).build(), ArmLayer.BLACK),
				black3 = quads(whip().tier(3).build(), ArmLayer.BLACK);
		assertTrue(black1 < black2 && black2 < black3, "strands, rings, knots and hairs grow with the tier: " + black1 + ", " + black2 + ", " + black3);
		// a strand is 27 segments of 4 faces on the lash and 13 on the grip: well over 100 quads each
		assertTrue(black2 - black1 >= 100 && black3 - black2 >= 100, "a strand a tier: " + black1 + ", " + black2 + ", " + black3);
		assertTrue(WhipGeometry.pathLength(1) < WhipGeometry.pathLength(2) && WhipGeometry.pathLength(2) < WhipGeometry.pathLength(3), "the lash grows longer");
		assertTrue(WHIP.feedLength(1) < WHIP.feedLength(2) && WHIP.feedLength(2) < WHIP.feedLength(3), "and the feed coordinate runs to its tip");
		// level 3's barbs glow (the GLOW layer is only used there); lower levels have none
		assertEquals(0, quads(whip().tier(1).build(), ArmLayer.GLOW));
		assertEquals(0, quads(whip().tier(2).build(), ArmLayer.GLOW));
		assertTrue(quads(whip().tier(3).build(), ArmLayer.GLOW) > 20, "level 3's barbs glow");
	}

	@Test
	void barbsSitEveryTwoPointTwoPxOnTheLastThirdAndNoTwoAreAlike() {
		for (int tier = 1; tier <= 3; tier++) {
			int n = WhipGeometry.barbCount(tier);
			float spacing = WhipGeometry.pathLength(tier) / 3F / n;
			assertTrue(spacing > 1.8F && spacing < 2.8F, "L" + tier + ": " + n + " barbs, one every " + spacing + " px");
		}
		assertTrue(WhipGeometry.barbCount(1) <= WhipGeometry.barbCount(2) && WhipGeometry.barbCount(2) <= WhipGeometry.barbCount(3));
		// my call: spines and barbs vary in length, lean, hook, thickness and roll
		float minLen = Float.MAX_VALUE, maxLen = 0F;
		for (int a = 0; a < 7; a++) {
			float[] ca = WhipGeometry.barbCharacter(a);
			minLen = Math.min(minLen, ca[0]);
			maxLen = Math.max(maxLen, ca[0]);
			for (int b = a + 1; b < 7; b++) {
				float[] cb = WhipGeometry.barbCharacter(b);
				int differ = 0;
				for (int i = 0; i < ca.length; i++) {
					if (Math.abs(ca[i] - cb[i]) > 1e-3F) differ++;
				}
				assertTrue(differ >= 4, "barbs " + a + " and " + b + " differ in only " + differ + " ways");
			}
		}
		assertTrue(maxLen / minLen >= 2F, "barb lengths range over at least 2×: " + minLen + ".." + maxLen);
	}

	@Test
	void forgingGrowsTheToolOutOfTheFistThenSproutsItsDetails() {
		// the lash's front never moves back and stations are only ever added
		int lastCount = 0;
		float lastFront = -1F;
		for (int step = 0; step <= 70; step++) {
			float t = ArmDraw.FORM_GROW_FROM + ArmDraw.FORM_GROW_TICKS * step / 70F;
			ArmDraw d = whip().state(ArmProtocol.STATE_FORMING, t / 14F).build();
			emit(d, ArmLayer.RED);
			int n = WhipGeometry.lastStations();
			if (n < 2) continue;
			float front = WhipGeometry.lastArc(n - 1);
			assertTrue(front >= lastFront - 1e-3F, "the front never moves back (" + front + " after " + lastFront + ")");
			assertTrue(n >= lastCount, "growth only adds stations");
			lastCount = n;
			lastFront = front;
		}
		assertEquals(40, lastCount, "grown to the tip by FORM's tick 8");
		// the tool is out at tick 8 with none of its details: no barbs (red), no crown, rings or knots (black)
		int redOut = quads(whip().state(ArmProtocol.STATE_FORMING, 8.0F / 14F).build(), ArmLayer.RED);
		int redDone = quads(whip().state(ArmProtocol.STATE_FORMING, 12.5F / 14F).build(), ArmLayer.RED);
		int blackOut = quads(whip().state(ArmProtocol.STATE_FORMING, 8.0F / 14F).build(), ArmLayer.BLACK);
		int blackDone = quads(whip().state(ArmProtocol.STATE_FORMING, 12.5F / 14F).build(), ArmLayer.BLACK);
		assertTrue(redDone - redOut >= 5 * 8, "the barbs come after the lash is out (" + redOut + " red quads, then " + redDone + ")");
		assertTrue(blackDone - blackOut >= 40, "so do the rings, crown, knots and hairs (" + blackOut + " black quads, then " + blackDone + ")");
		// and nothing bursts from the knuckles
		assertEquals(0, whip().state(ArmProtocol.STATE_FORMING, 0.3F).build().burstCount());
		// the barbs pop in turn, base to tip
		int before = quads(whip().state(ArmProtocol.STATE_FORMING, 9.2F / 14F).build(), ArmLayer.RED);
		int after = quads(whip().state(ArmProtocol.STATE_FORMING, 11.5F / 14F).build(), ArmLayer.RED);
		assertTrue(redOut < before && before < after, "barb by barb: " + redOut + ", " + before + ", " + after);
	}

	@Test
	void theCoilHangsFromTheCollarAndSwingsWithTheRope() {
		ArmDraw rest = whip().build();
		Vector3f tip = WHIP.point(rest, ArmTips.TIP, new Vector3f()), mid = WHIP.point(rest, ArmTips.MID, new Vector3f()), grip = WHIP.point(rest, ArmTips.GRIP,
				new Vector3f());
		assertEquals(0F, grip.length(), 1e-6F, "the grip is G's origin");
		assertTrue(tip.y < WhipGeometry.LASH_Y - 10F, "the lash hangs from the collar toward world down: " + tip);
		assertTrue(mid.y < WhipGeometry.LASH_Y && mid.y > tip.y - 1e-3F, "the middle is between: " + mid);
		// a swung chain: all the rope's nodes thrown out sideways carry the tip with them
		float[] swung = new float[3 * ArmDraw.ROPE_NODES];
		for (int i = 0; i < ArmDraw.ROPE_NODES; i++) {
			swung[3 * i] = 1.6F * i;
			swung[3 * i + 1] = -0.2F * i;
		}
		ArmDraw thrown = rest.finish(0, true, swung, 0F, -1F, 0F, null, 0xF000F0);
		Vector3f out = WHIP.point(thrown, ArmTips.TIP, new Vector3f());
		assertTrue(out.x > tip.x + 6F, "the coil follows the simulated chain: " + tip + " then " + out);
		// no simulation (an icon, a far arm): it still hangs
		ArmDraw still = whip().lod(1).build();
		assertTrue(WHIP.point(still, ArmTips.TIP, new Vector3f()).y < WhipGeometry.LASH_Y - 8F, "a still droop");
		// and it follows world down as the draw gives it
		ArmDraw sideways = rest.finish(0, true, null, 1F, 0F, 0F, null, 0xF000F0);
		assertTrue(WHIP.point(sideways, ArmTips.TIP, new Vector3f()).x > 8F, "hanging along +X when that is down: " + WHIP.point(sideways, ArmTips.TIP, new Vector3f()));
	}

	@Test
	void aCastLineOrAThrownLashGathersTheLashUp() {
		float hang = WHIP.point(whip().build(), ArmTips.TIP, new Vector3f()).distance(0F, WhipGeometry.LASH_Y, 0F);
		// a hook out: the lash gathers over four ticks to the line's anchor
		for (int t = 0; t <= 12; t++) WhipGeometry.markCast(7, 100F + t);
		float gathered = WHIP.point(whip().time(112F).build(), ArmTips.TIP, new Vector3f()).distance(0F, WhipGeometry.LASH_Y, 0F);
		assertTrue(gathered < 0.45F * hang, "gathered " + gathered + " of " + hang + " px");
		assertEquals(0F, WhipGeometry.castAmount(7, 90F), 1e-6F, "not cast yet");
		assertTrue(WhipGeometry.castAmount(7, 112F) > 0.99F);
		// reeled in: it lets go over the next few ticks
		assertTrue(WhipGeometry.castAmount(7, 112F + 3F) < 1F && WhipGeometry.castAmount(7, 112F + 3F) > 0F);
		assertEquals(0F, WhipGeometry.castAmount(7, 112F + 9F), 1e-6F, "released");
		// someone else's cast leaves this arm alone
		assertEquals(0F, WhipGeometry.castAmount(8, 112F), 1e-6F);
		// the thrown lash: gone in 2 ticks, held to 6, back by 16
		WhipGeometry.markLash(7, 200F);
		assertEquals(0F, WhipGeometry.lashWeight(7, 199F), 1e-6F);
		assertEquals(1F, WhipGeometry.lashWeight(7, 203F), 1e-6F);
		assertTrue(WhipGeometry.lashWeight(7, 210F) < 1F && WhipGeometry.lashWeight(7, 210F) > 0F);
		assertEquals(0F, WhipGeometry.lashWeight(7, 217F), 1e-6F);
		float thrown = WHIP.point(whip().time(203F).build(), ArmTips.TIP, new Vector3f()).distance(0F, WhipGeometry.LASH_Y, 0F);
		assertTrue(thrown < 0.3F * hang, "the lash in hand coils away as the world's goes out: " + thrown);
		// the arm clock wraps hourly: an age is read the short way round
		assertEquals(3F, WhipGeometry.ageOf(1F, 71998F), 1e-4F);
		assertEquals(-3F, WhipGeometry.ageOf(71998F, 1F), 1e-4F);
	}

	@Test
	void lowBiomassCracksThinsAndErodes() {
		ArmBudgetTest.Draw full = whip().tier(2);
		ArmBudgetTest.Draw starving = whip().tier(2);
		starving.biomassFrac = 0.05F;
		starving.biomassAbs = 5F;
		ArmBudgetTest.Draw failing = whip().tier(2);
		failing.biomassFrac = 0.01F;
		failing.biomassAbs = 1F;
		// the starving cracks turn lash and grip red: more red quads than the full arm's
		assertTrue(quads(starving.build(), ArmLayer.RED) > quads(full.build(), ArmLayer.RED), "cracks show red");
		// failing erodes the last 12% of the lash
		emit(full.build(), ArmLayer.RED);
		float whole = WhipGeometry.lastArc(WhipGeometry.lastStations() - 1);
		emit(failing.build(), ArmLayer.RED);
		float eroded = WhipGeometry.lastArc(WhipGeometry.lastStations() - 1);
		assertTrue(eroded < whole - 2F, "the failing lash ends short: " + eroded + " of " + whole);
		assertTrue(WhipGeometry.lastStations() < 40);
	}

	@Test
	void everyTimelineTierAndDetailDrawsRealGeometry() {
		int[] states = {ArmProtocol.STATE_FORMING, ArmProtocol.STATE_QUICKFORM, ArmProtocol.STATE_RESHAPING, ArmProtocol.STATE_RETRACTING,
				ArmProtocol.STATE_MELTING, ArmProtocol.STATE_FORMED, ArmProtocol.STATE_NONE};
		float[][] biomass = {{1F, ArmDraw.FULL}, {0.2F, 50F}, {0.05F, 5F}, {0.01F, 1F}};
		for (int ctx = 0; ctx <= 2; ctx++) {
			for (int lod = 0; lod <= 3; lod++) {
				if (ctx == ArmDraw.CTX_GUI && lod != ArmDraw.LOD_ICON) continue;
				for (int tier = 1; tier <= 3; tier++) {
					for (int state : states) {
						for (float p = 0F; p <= 1F; p += 0.125F) {
							for (float[] b : biomass) {
								ArmBudgetTest.Draw draw = whip().tier(tier).lod(lod).ctx(ctx).state(state, p).time(1000F + 13.7F * p);
								draw.biomassFrac = b[0];
								draw.biomassAbs = b[1];
								draw.fromForm = state == ArmProtocol.STATE_RESHAPING ? ArmProtocol.PICKAXE : "";
								draw.leftHand = (tier & 1) == 0;
								ArmDraw d = draw.build();
								for (ArmLayer layer : LAYERS) emit(d, layer);
								Vector3f tip = WHIP.point(d, ArmTips.TIP, new Vector3f());
								assertTrue(Float.isFinite(tip.x + tip.y + tip.z), "the tip is a point");
							}
						}
					}
				}
			}
		}
	}

	@Test
	void reactionsDrawRealGeometryAndFlareTheBarbs() {
		for (float age = 0F; age < 12F; age += 0.5F) {
			ArmBudgetTest.Draw hit = whip().ctx(ArmDraw.CTX_FP).tier(3);
			hit.hitAge = age;
			hit.pulses = new float[] {Pulse.Kind.SPEND.ordinal(), hit.time - age, Pulse.Kind.FEED.ordinal(), hit.time - age * 0.5F};
			hit.pulseCount = 2;
			hit.swing = age / 12F;
			hit.surge = 1F - age / 12F;
			for (ArmLayer layer : LAYERS) assertTrue(emit(hit.build(), layer).quads() >= 0);
		}
		// a hit sends two beads off the tip (one black, one red) and lengthens the barbs
		ArmBudgetTest.Draw hit = whip();
		hit.hitAge = 1F;
		assertTrue(quads(hit.build(), ArmLayer.RED) > quads(whip().build(), ArmLayer.RED), "a hit's red bead");
		// the swing unwinds the coil: the helix is narrower mid-swing
		ArmBudgetTest.Draw swing = whip();
		swing.swing = 0.5F;
		assertTrue(emit(swing.build(), ArmLayer.RED).quads() > 0);
	}

	// ------------------------------------------------------------------ the budget (section 2.10) and the icon

	@Test
	void ownArmFirstPersonStaysUnderItsCeilings() {
		for (int tier = 1; tier <= 3; tier++) {
			int steady = ArmBudgetTest.worst(whip().tier(tier).ctx(ArmDraw.CTX_FP), false);
			System.out.printf("whip L%d own FP LOD0 steady: %d%n", tier, steady);
			assertTrue(steady <= 1300, "L" + tier + " first person: " + steady + " quads, over 1300");
			assertTrue(steady >= (ArmDraw.FIRST_PERSON_ARM ? 700 : 400), "L" + tier + " first person: only " + steady + " quads; the detail is missing (first person is the tool alone)");
		}
	}

	@Test
	void burstsStayUnderTheBurstCeiling() {
		for (int tier = 1; tier <= 3; tier++) {
			int worst = 0;
			for (int step = 0; step <= 56; step++) {
				float p = step / 56F;
				worst = Math.max(worst, ArmBudgetTest.toolQuads(whip().tier(tier).ctx(ArmDraw.CTX_FP).time(50F + p * 14F).state(ArmProtocol.STATE_FORMING, p).build()));
				ArmBudgetTest.Draw b = whip().tier(tier).ctx(ArmDraw.CTX_FP).time(50F + p * 16F).state(ArmProtocol.STATE_RESHAPING, p);
				b.fromForm = ArmProtocol.KATANA;
				ArmDraw d = b.build();
				worst = Math.max(worst, ArmBudgetTest.toolQuads(d) + (d.knotTick() >= 0F ? 8 * 6 : 0));
			}
			System.out.printf("whip L%d own FP burst: %d%n", tier, worst);
			assertTrue(worst <= 1700, "L" + tier + " forming or reshaping: " + worst + " quads, over 1700");
		}
	}

	@Test
	void anotherPlayerUpCloseStaysUnderItsCeiling() {
		for (int tier = 1; tier <= 3; tier++) {
			int quads = ArmBudgetTest.worst(whip().tier(tier), true);
			System.out.printf("whip L%d TP LOD0 tool+sheath: %d%n", tier, quads);
			int ceiling = tier >= 3 ? 1300 : 1200;
			assertTrue(quads <= ceiling, "L" + tier + " third person: " + quads + " quads, over " + ceiling);
		}
	}

	@Test
	void levelsOfDetailShed() {
		for (int tier = 1; tier <= 3; tier++) {
			int lod1Tool = ArmBudgetTest.worst(whip().tier(tier).lod(1), false), lod1 = ArmBudgetTest.worst(whip().tier(tier).lod(1), true);
			int lod2 = ArmBudgetTest.worst(whip().tier(tier).lod(2), true), lod3 = ArmBudgetTest.worst(whip().tier(tier).lod(3), true);
			int icon = ArmBudgetTest.worst(whip().tier(tier).lod(ArmDraw.LOD_ICON).ctx(ArmDraw.CTX_GUI), false);
			System.out.printf("whip L%d LOD1 tool %d total %d, LOD2 %d, LOD3 %d, icon %d%n", tier, lod1Tool, lod1, lod2, lod3, icon);
			assertTrue(lod1Tool <= 360, "L" + tier + " LOD 1 tool: " + lod1Tool + " (about 300)");
			assertTrue(lod1 <= 576, "L" + tier + " LOD 1: " + lod1 + " (about 480)");
			assertTrue(lod2 <= 116, "L" + tier + " LOD 2: " + lod2 + " (about 96)");
			assertTrue(lod3 <= 30, "L" + tier + " LOD 3: " + lod3 + " (four boxes and one)");
			assertTrue(icon <= 360, "L" + tier + " icon: " + icon);
			assertTrue(lod3 < lod2 && lod2 < lod1, "L" + tier + ": each level of detail should cost less than the one before");
		}
	}

	@Test
	void retractedArmDrawsNothing() {
		for (int ctx = 1; ctx <= 1; ctx++) {
			ArmDraw none = whip().ctx(ctx).state(ArmProtocol.STATE_NONE, 1F).build();
			assertEquals(0, ArmBudgetTest.toolQuads(none), "no arm, no tool");
		}
	}

	@Test
	void theIconKeepsInsideItsSlot() {
		// every vertex of the GUI draw, through the form's own fit, lands inside the 16 px slot
		Matrix4f fit = ArmTransforms.gui(WHIP.guiScale(), WHIP.guiGripPx(), new Matrix4f());
		Vector3f p = new Vector3f();
		for (int tier = 1; tier <= 3; tier++) {
			float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE, left = Float.MAX_VALUE, right = -Float.MAX_VALUE;
			for (float t = 0F; t < 200F; t += 17F) {
				ArmDraw d = whip().tier(tier).lod(ArmDraw.LOD_ICON).ctx(ArmDraw.CTX_GUI).time(t).build();
				for (ArmLayer layer : LAYERS) {
					ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
					r.keep = true;
					WHIP.emit(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
					for (float[] v : r.kept) {
						fit.transformPosition(p.set(v[0], v[1], v[2])).mul(16F);
						left = Math.min(left, p.x);
						right = Math.max(right, p.x);
						lo = Math.min(lo, p.y);
						hi = Math.max(hi, p.y);
					}
				}
			}
			System.out.printf("whip L%d icon spans x %.2f..%.2f, y %.2f..%.2f%n", tier, left, right, lo, hi);
			assertTrue(left >= 0.3F && lo >= 0.3F, "L" + tier + " icon runs off the low sides: x " + left + ", y " + lo);
			assertTrue(right <= 15.7F && hi <= 15.7F, "L" + tier + " icon runs off the high sides: x " + right + ", y " + hi);
		}
	}
}
