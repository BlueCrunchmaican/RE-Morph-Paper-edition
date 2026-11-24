package dev.morphreborn.client.arm.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Stations;
import java.util.function.Predicate;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * The spade against section 2.11 and my look rules: a cupped leaf widest a third of the way up and running to a
 * point, 0.55 px thick, ribbed with a red midrib; the clutching socket, the thorns that all differ; built with its leading
 * margin toward -X in G like the katana; Burrow's three claws (held while the window lasts); the forming order (the haft out
 * of the fist first, the details after); its budgets (section 2.10); and every state, tier, level of detail and reaction
 * drawing real geometry. It must not look like the axe.
 */
class SpadeGeometryTest {
	private static final FormGeometry SPADE = ArmGeometry.get(ArmProtocol.SHOVEL);
	private static final ArmLayer[] LAYERS = {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW};

	private static ArmBudgetTest.Draw spade() {
		return ArmBudgetTest.draw(ArmProtocol.SHOVEL);
	}

	private static ArmBudgetTest.Recorder emit(ArmDraw d, ArmLayer layer) {
		ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
		r.keep = true;
		SPADE.emit(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
		return r;
	}

	private static float[] bounds(ArmDraw d, ArmLayer... layers) {
		return boundsWhere(d, v -> true, layers);
	}

	/** The bounding box (minX, maxX, minY, maxY, minZ, maxZ) of the vertices {@code keep} accepts in {@code layers}. */
	private static float[] boundsWhere(ArmDraw d, Predicate<float[]> keep, ArmLayer... layers) {
		float[] b = {Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE};
		for (ArmLayer layer : layers) {
			for (float[] v : emit(d, layer).kept) {
				if (!keep.test(v)) continue;
				b[0] = Math.min(b[0], v[0]);
				b[1] = Math.max(b[1], v[0]);
				b[2] = Math.min(b[2], v[1]);
				b[3] = Math.max(b[3], v[1]);
				b[4] = Math.min(b[4], v[2]);
				b[5] = Math.max(b[5], v[2]);
			}
		}
		return b;
	}

	private static Vector3f point(ArmDraw d, int which) {
		return SPADE.point(d, which, new Vector3f());
	}

	@Test
	void isTheRealSpadeNotThePlaceholder() {
		assertTrue(SPADE instanceof SpadeGeometry, "ArmGeometry found " + SPADE.getClass().getSimpleName());
		assertTrue(ArmGeometry.built(ArmProtocol.SHOVEL));
		assertEquals(ArmProtocol.SHOVEL, SPADE.form());
		assertFalse(SPADE.bowTransforms());
		assertEquals(0.64F, SPADE.guiScale(), 1e-6F);
		assertEquals(5.4F, SPADE.guiGripPx(), 1e-6F);
		assertEquals(KatanaGeometry.EDGE_SIGN, SpadeGeometry.EDGE_SIGN, 0F);
	}

	@Test
	void theLeafIsWidestAThirdUpAndRunsToAPoint() {
		assertEquals(0F, SpadeGeometry.halfWidth(0F), 1e-6F);
		assertEquals(0F, SpadeGeometry.halfWidth(1F), 1e-6F);
		float best = 0F, at = 0F;
		for (int i = 1; i < 100; i++) {
			float h = SpadeGeometry.halfWidth(i / 100F);
			if (h > best) {
				best = h;
				at = i / 100F;
			}
		}
		assertTrue(at > 0.28F && at < 0.38F, "widest at t " + at + " (y " + (10.4F + 8.2F * at) + ")");
		assertTrue(2F * best > 5F && 2F * best < 6F, "widest " + 2F * best + " px across (spec 5)");
		assertEquals(0.55F, SpadeGeometry.slabThickness(), 0.05F, "the leaf's thickness");
		// the slab at full detail: 7 stations, ending in a point
		emit(spade().build(), ArmLayer.BLACK);
		Stations s = SpadeGeometry.lastSlab();
		assertEquals(7, s.count(), "a full-detail leaf");
		int last = s.count() - 1;
		float spread = 0F;
		for (int j = 0; j < s.sides(); j++) {
			float dx = s.corner(last, j, 0) - s.centre(last, 0);
			spread = Math.max(spread, Math.abs(dx));
		}
		assertTrue(spread < 0.1F, "the leaf's last station is a point (" + spread + " px wide)");
		// it is cupped: the margins sit off the middle's plane toward +Z (8 degrees' worth)
		int mid = 2;
		float zc = 0F, zm = 0F;
		for (int j = 0; j < s.sides(); j++) {
			zc = Math.max(zc, s.corner(mid, j, 2) - s.centre(mid, 2));
		}
		assertTrue(zc > 0.2F, "the section stands proud on the cup's side: " + zc);
	}

	@Test
	void theLeadingMarginFacesDownAndLeadsTheSwing() {
		ArmDraw d = spade().build();
		// the red band is on the leading margin: on -X in G, outside the midrib's line
		float[] red = bounds(d, ArmLayer.RED);
		assertTrue(red[0] < -2F, "the red margin band reaches the -X side: " + red[0]);
		Vector3f tip = point(d, ArmTips.TIP);
		assertTrue(tip.y > 17.5F && tip.y < 19.5F, "the point: " + tip);
		assertTrue(tip.x < 0F && tip.x > -1.2F, "the axis leans toward the leading side: " + tip);
		assertEquals(0F, point(d, ArmTips.GRIP).length(), 1e-6F);
		Vector3f mid = point(d, ArmTips.MID);
		assertTrue(mid.y > 12F && mid.y < 15F, "the healing latch sits on the leaf: " + mid);
		float[] ext = {Float.MAX_VALUE, -Float.MAX_VALUE};
		SPADE.extents(p -> {
			ext[0] = Math.min(ext[0], p.x());
			ext[1] = Math.max(ext[1], p.x());
		});
		assertTrue(ext[0] < -3F, "the declared extents are mirrored too: " + ext[0]);
	}

	@Test
	void tiersAddDetail() {
		int l1 = ArmBudgetTest.toolQuads(spade().tier(1).build()), l2 = ArmBudgetTest.toolQuads(spade().tier(2).build()),
				l3 = ArmBudgetTest.toolQuads(spade().tier(3).build());
		assertTrue(l1 < l2 && l2 < l3, "levels 1, 2, 3: " + l1 + ", " + l2 + ", " + l3);
		assertTrue(emit(spade().tier(3).build(), ArmLayer.GLOW).quads() > 0, "level 3 glows");
		assertEquals(0, emit(spade().tier(2).build(), ArmLayer.GLOW).quads(), "level 2 at rest doesn't");
		ArmBudgetTest.Draw off = spade().tier(3);
		off.glow = false;
		assertEquals(0, emit(off.build(), ArmLayer.GLOW).quads());
		assertTrue(emit(off.build(), ArmLayer.RED).quads() > emit(spade().tier(3).build(), ArmLayer.RED).quads(), "the glowing parts draw red instead");
	}

	@Test
	void theHaftGrowsOutOfTheFistAndTheDetailsFollow() {
		ArmDraw four = spade().state(ArmProtocol.STATE_FORMING, 4F / 14F).build();
		float[] b4 = bounds(four, ArmLayer.BLACK, ArmLayer.RED);
		assertTrue(b4[0] > -1.2F && b4[1] < 1.2F, "only the haft at tick 4: x " + b4[0] + ".." + b4[1]);
		assertTrue(b4[3] > 2F && b4[3] < 11F && b4[2] < -0.5F, "out both ways from the fist: y " + b4[2] + ".." + b4[3]);
		ArmDraw eight = spade().state(ArmProtocol.STATE_FORMING, 8F / 14F).build();
		float[] b8 = bounds(eight, ArmLayer.BLACK, ArmLayer.RED);
		assertTrue(b8[3] > 12.5F && b8[2] < -4.5F, "the whole haft is out by tick 8: y " + b8[2] + ".." + b8[3]);
		assertTrue(b8[0] > -1.2F && b8[1] < 1.2F, "and no leaf yet: x " + b8[0] + ".." + b8[1]);
		ArmDraw twelve = spade().state(ArmProtocol.STATE_FORMING, 12.5F / 14F).build();
		assertTrue(bounds(twelve, ArmLayer.BLACK, ArmLayer.RED)[3] > 15.5F, "the leaf is rising at tick 12.5");
		ArmDraw end = spade().state(ArmProtocol.STATE_FORMING, 1F).build();
		assertTrue(bounds(end, ArmLayer.BLACK, ArmLayer.RED)[3] > 18F, "the whole leaf at the end of FORM");
		ArmDraw quick = spade().state(ArmProtocol.STATE_QUICKFORM, 4.5F / 8F).build();
		assertTrue(bounds(quick, ArmLayer.BLACK, ArmLayer.RED)[3] > 12.5F, "quick-form plays the same order");
	}

	@Test
	void thornsAllDiffer() {
		float[][] tables = thornTables();
		int n = tables[0].length;
		for (int i = 0; i < n; i++) {
			for (int j = i + 1; j < n; j++) {
				boolean same = tables[0][i] == tables[0][j] && tables[1][i] == tables[1][j] && tables[2][i] == tables[2][j];
				assertFalse(same, "thorns " + i + " and " + j + " are alike");
			}
		}
		float min = Float.MAX_VALUE, max = 0F;
		for (float v : tables[0]) {
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		assertTrue(max / min > 2F, "lengths range " + min + ".." + max);
		// none past t 0.82 (the point stays clean)
		for (int k = 0; k < n - 1; k++) assertTrue(tables[3][k] <= 0.82F, "thorn " + k + " at t " + tables[3][k]);
	}

	/** Length, lean, hook and place of the thorns, read from the geometry's own tables by reflection. */
	private static float[][] thornTables() {
		try {
			String[] names = {"TH_LEN", "TH_ANG", "TH_CURL", "TH_T"};
			float[][] out = new float[4][];
			for (int i = 0; i < 4; i++) {
				var f = SpadeGeometry.class.getDeclaredField(names[i]);
				f.setAccessible(true);
				out[i] = (float[]) f.get(null);
			}
			return out;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	void burrowSplitsTheLeafIntoThreeClawsAndHoldsThemWhileTheWindowLasts() {
		Predicate<float[]> top = v -> v[1] > 16.2F;
		float[] rest = boundsWhere(spade().build(), top, ArmLayer.BLACK);
		ArmBudgetTest.Draw open = spade();
		open.specialKind = ArmProtocol.FX_BURROW;
		open.specialAge = 9F;
		float[] claws = boundsWhere(open.build(), top, ArmLayer.BLACK);
		assertTrue((claws[1] - claws[0]) > (rest[1] - rest[0]) + 0.6F, "the claws splay: " + (rest[1] - rest[0]) + " to " + (claws[1] - claws[0]));
		assertTrue(ArmBudgetTest.toolQuads(open.build()) != ArmBudgetTest.toolQuads(spade().build()), "the claws and the ring of tendrils are drawn");
		// the claws open over the window's first 5 ticks, and stay open as long as it lasts
		ArmBudgetTest.Draw opening = spade();
		opening.specialKind = ArmProtocol.FX_BURROW;
		opening.specialAge = 1.5F;
		float[] partway = boundsWhere(opening.build(), top, ArmLayer.BLACK);
		assertTrue(partway[1] - partway[0] < (claws[1] - claws[0]) - 0.05F || partway[1] - partway[0] > (claws[1] - claws[0]) + 0.05F, "partly open at 1.5 ticks");
		ArmBudgetTest.Draw longOpen = spade();
		longOpen.specialKind = ArmProtocol.FX_BURROW;
		longOpen.specialAge = 400F;
		float[] held = boundsWhere(longOpen.build(), top, ArmLayer.BLACK);
		assertEquals(claws[1] - claws[0], held[1] - held[0], 0.2F, "held open for as long as the window lasts");
		// after it ends they close over 8 ticks
		ArmBudgetTest.Draw closing = spade();
		closing.specialKind = ArmProtocol.FX_BURROW;
		closing.specialAge = -4F;
		float[] half = boundsWhere(closing.build(), top, ArmLayer.BLACK);
		closing.specialAge = -9F;
		float[] shut = boundsWhere(closing.build(), top, ArmLayer.BLACK);
		assertEquals(rest[1] - rest[0], shut[1] - shut[0], 0.05F, "closed again nine ticks after");
		assertTrue(half[1] - half[0] > (rest[1] - rest[0]) + 0.1F, "still partly open at 4 ticks"); // the blade is wide to its end now, so the claws add less
		// the ring of tendrils and the claws cost something, never past the ceilings
		for (int tier = 1; tier <= 3; tier++) {
			ArmBudgetTest.Draw b = spade().tier(tier).ctx(ArmDraw.CTX_FP);
			b.specialKind = ArmProtocol.FX_BURROW;
			b.specialAge = 9F;
			int worst = ArmBudgetTest.worst(b, false);
			assertTrue(worst <= 1300, "spade L" + tier + " burrowing in first person: " + worst);
			ArmBudgetTest.Draw tp = spade().tier(tier);
			tp.specialKind = ArmProtocol.FX_BURROW;
			tp.specialAge = 9F;
			int total = ArmBudgetTest.worst(tp, true);
			assertTrue(total <= (tier >= 3 ? 1300 : 1200) + 60, "spade L" + tier + " burrowing in third person: " + total);
		}
	}

	@Test
	void diggingBitesAndABrokenBlockJoltsTheLeaf() {
		Vector3f rest = point(spade().time(101.25F).build(), ArmTips.TIP);
		ArmBudgetTest.Draw dig = spade().time(101.25F);
		dig.mining = true;
		assertTrue(point(dig.build(), ArmTips.TIP).distance(rest) > 0.5F, "the tip bites out 0.6 px");
		ArmBudgetTest.Draw brk = spade().time(100F);
		brk.breakAge = 2F;
		assertTrue(point(brk.build(), ArmTips.TIP).y < point(spade().time(100F).build(), ArmTips.TIP).y - 0.6F, "a broken block pulls the leaf back");
		ArmBudgetTest.Draw over = spade().time(100F);
		over.breakAge = 3.5F;
		assertTrue(point(over.build(), ArmTips.TIP).y > point(spade().time(100F).build(), ArmTips.TIP).y + 0.2F, "then it overshoots");
		// beads off the point
		ArmBudgetTest.Draw flick = spade();
		flick.breakAge = 1F;
		assertTrue(ArmBudgetTest.toolQuads(flick.build()) > ArmBudgetTest.toolQuads(spade().build()));
	}

	@Test
	void lowBiomassThinsCracksAndErodes() {
		ArmBudgetTest.Draw failing = spade();
		failing.biomassFrac = 0.01F;
		failing.biomassAbs = 1F;
		float rest = point(spade().build(), ArmTips.TIP).y, eroded = point(failing.build(), ArmTips.TIP).y;
		assertTrue(eroded < rest - 0.8F, "the leaf's last 15% is gone: " + eroded + " against " + rest);
		ArmBudgetTest.Draw starving = spade();
		starving.biomassFrac = 0.08F;
		starving.biomassAbs = 8F;
		assertTrue(ArmBudgetTest.toolQuads(starving.build()) > 0);
	}

	@Test
	void doesNotLookLikeTheAxeOrTheOtherTools() {
		// a glance tells them apart: the axe's fan hangs 3 px or more to one side of its haft, the spade's leaf sits on the haft's line
		float spade = meanX(spade().build(), SPADE), axe = meanX(ArmBudgetTest.draw(ArmProtocol.AXE).build(), ArmGeometry.get(ArmProtocol.AXE));
		assertTrue(Math.abs(spade) < 1.2F, "the leaf is centred on the haft: mean x " + spade);
		assertTrue(axe < -2.5F, "the axe's head hangs to the leading side: mean x " + axe);
		// and the spade's loop hangs below the butt where no other tool has one
		float[] low = boundsWhere(spade().build(), v -> v[1] < -6F, ArmLayer.BLACK);
		assertTrue(low[3] > -9.9F && low[2] < -8.5F && low[1] - low[0] > 3.5F, "a loop under the butt: " + low[0] + ".." + low[1] + " y " + low[2]);
	}

	/** The mean x of the head's vertices (y above 9) as {@code g} draws them. */
	private static float meanX(ArmDraw d, FormGeometry g) {
		double sum = 0;
		int n = 0;
		for (ArmLayer layer : new ArmLayer[] {ArmLayer.BLACK, ArmLayer.RED}) {
			ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
			r.keep = true;
			g.emit(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
			for (float[] v : r.kept) {
				if (v[1] <= 9F) continue;
				sum += v[0];
				n++;
			}
		}
		return (float) (sum / Math.max(1, n));
	}

	@Test
	void everyStateTierAndDetailDrawsRealGeometry() {
		int[] states = {ArmProtocol.STATE_NONE, ArmProtocol.STATE_FORMING, ArmProtocol.STATE_FORMED, ArmProtocol.STATE_RESHAPING, ArmProtocol.STATE_RETRACTING,
				ArmProtocol.STATE_MELTING, ArmProtocol.STATE_QUICKFORM};
		int drawn = 0;
		for (int tier = 1; tier <= 3; tier++) {
			for (int lod = 0; lod <= 3; lod++) {
				for (int ctx : new int[] {ArmDraw.CTX_FP, ArmDraw.CTX_TP, ArmDraw.CTX_GUI}) {
					for (int state : states) {
						for (int step = 0; step <= 20; step++) {
							ArmBudgetTest.Draw b = spade().tier(tier).lod(lod).ctx(ctx).time(30F + step * 1.7F).state(state, step / 20F);
							b.fromForm = ArmProtocol.PICKAXE;
							b.leftHand = (step & 1) == 1;
							b.biomassFrac = step % 5 == 4 ? 0.01F : step % 5 == 3 ? 0.08F : step % 5 == 2 ? 0.2F : 1F;
							b.biomassAbs = step % 5 == 4 ? 1F : step % 5 == 3 ? 8F : step % 5 == 2 ? 25F : ArmDraw.FULL;
							b.mining = step % 7 == 3;
							b.hitAge = step % 4 == 1 ? 1F + step * 0.1F : ArmDraw.NEVER;
							b.breakAge = step % 6 == 2 ? 2.4F : ArmDraw.NEVER;
							b.surge = step % 9 == 5 ? 0.6F : 0F;
							b.swing = step % 3 == 0 ? 0.5F : 0F;
							if (step % 8 == 6) {
								b.specialKind = ArmProtocol.FX_BURROW;
								b.specialAge = step % 16 == 6 ? 2.5F : -3F;
							}
							ArmDraw d = b.build();
							drawn += ArmBudgetTest.toolQuads(d);
							for (int which : new int[] {ArmTips.TIP, ArmTips.MID, ArmTips.GRIP}) {
								Vector3f p = SPADE.point(d, which, new Vector3f());
								assertTrue(Float.isFinite(p.x) && Float.isFinite(p.y) && Float.isFinite(p.z), "point " + which + " " + p);
							}
						}
					}
				}
			}
		}
		assertTrue(drawn > 0);
	}

	// ------------------------------------------------------------------ budgets (section 2.10)

	@Test
	void ownArmFirstPersonStaysUnderItsCeilings() {
		for (int tier = 1; tier <= 3; tier++) {
			int steady = ArmBudgetTest.worst(spade().tier(tier).ctx(ArmDraw.CTX_FP), false);
			System.out.printf("spade L%d own FP LOD0 steady: %d%n", tier, steady);
			assertTrue(steady <= 1300, "spade L" + tier + " first person: " + steady + " quads, over 1300");
			assertTrue(steady >= (ArmDraw.FIRST_PERSON_ARM ? 700 : 400), "spade L" + tier + " first person: only " + steady + " quads; the detail is missing (first person is the tool alone)");
		}
	}

	@Test
	void burstsStayUnderTheBurstCeiling() {
		for (int tier = 1; tier <= 3; tier++) {
			int worst = 0;
			for (int step = 0; step <= 56; step++) {
				float p = step / 56F;
				worst = Math.max(worst, ArmBudgetTest.toolQuads(spade().tier(tier).ctx(ArmDraw.CTX_FP).time(50F + p * 14F).state(ArmProtocol.STATE_FORMING, p).build()));
				ArmBudgetTest.Draw b = spade().tier(tier).ctx(ArmDraw.CTX_FP).time(50F + p * 16F).state(ArmProtocol.STATE_RESHAPING, p);
				b.fromForm = ArmProtocol.KATANA;
				ArmDraw d = b.build();
				worst = Math.max(worst, ArmBudgetTest.toolQuads(d) + (d.knotTick() >= 0F ? 8 * 6 : 0));
			}
			System.out.printf("spade L%d own FP burst: %d%n", tier, worst);
			assertTrue(worst <= 1700, "spade L" + tier + " forming or reshaping: " + worst + " quads, over 1700");
		}
	}

	@Test
	void anotherPlayerUpCloseStaysUnderItsCeiling() {
		for (int tier = 1; tier <= 3; tier++) {
			int quads = ArmBudgetTest.worst(spade().tier(tier), true);
			System.out.printf("spade L%d TP LOD0 tool+sheath: %d%n", tier, quads);
			int ceiling = tier >= 3 ? 1300 : 1200;
			assertTrue(quads <= ceiling, "spade L" + tier + " third person: " + quads + " quads, over " + ceiling);
		}
	}

	@Test
	void levelsOfDetailShed() {
		for (int tier = 1; tier <= 3; tier++) {
			int lod1Tool = ArmBudgetTest.worst(spade().tier(tier).lod(1), false), lod1 = ArmBudgetTest.worst(spade().tier(tier).lod(1), true);
			int lod2 = ArmBudgetTest.worst(spade().tier(tier).lod(2), true), lod3 = ArmBudgetTest.worst(spade().tier(tier).lod(3), true);
			int icon = ArmBudgetTest.worst(spade().tier(tier).lod(ArmDraw.LOD_ICON).ctx(ArmDraw.CTX_GUI), false);
			System.out.printf("spade L%d LOD1 tool %d total %d, LOD2 %d, LOD3 %d, icon %d%n", tier, lod1Tool, lod1, lod2, lod3, icon);
			assertTrue(lod1Tool <= 360, "spade L" + tier + " LOD 1 tool: " + lod1Tool + " (about 300)");
			assertTrue(lod1 <= 576, "spade L" + tier + " LOD 1: " + lod1 + " (about 480)");
			assertTrue(lod2 <= 116, "spade L" + tier + " LOD 2: " + lod2 + " (about 96)");
			assertTrue(lod3 <= 30, "spade L" + tier + " LOD 3: " + lod3 + " (four boxes and one)");
			assertTrue(icon <= 360, "spade L" + tier + " icon: " + icon);
			assertTrue(lod3 < lod2 && lod2 < lod1, "spade L" + tier + ": each level of detail should cost less than the one before");
		}
	}

	@Test
	void retractedArmDrawsNothing() {
		assertEquals(0, ArmBudgetTest.toolQuads(spade().state(ArmProtocol.STATE_NONE, 1F).build()));
		assertEquals(0, ArmBudgetTest.toolQuads(spade().state(ArmProtocol.STATE_RETRACTING, 1F).build()), "a finished retract draws nothing");
	}

	@Test
	void everyLayerOfEveryLevelHasThePiecesItShould() {
		for (int tier = 1; tier <= 3; tier++) {
			ArmDraw d = spade().tier(tier).build();
			for (ArmLayer layer : LAYERS) {
				if (layer == ArmLayer.GLOW && tier < 3) continue;
				assertTrue(emit(d, layer).quads() > 0, "spade L" + tier + " has " + layer + " faces");
			}
		}
	}
}
