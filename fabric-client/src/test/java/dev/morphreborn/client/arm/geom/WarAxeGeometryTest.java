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
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * The war axe against section 2.11 and my look rules: a fan of six ribs (four and seven at the other levels)
 * webbed with plates and edged by a red crescent that is chipped and ends in points; built with its edge toward -X in G
 * (the katana's mirror), so it faces down in hand; its budgets (section 2.10); the forming order (the haft out of the
 * fist first, the details after); Cleave's splay, Fell's bristling, hits, chops and low biomass; and every state, tier,
 * level of detail and reaction drawing real geometry.
 */
class WarAxeGeometryTest {
	private static final FormGeometry AXE = ArmGeometry.get(ArmProtocol.AXE);
	private static final ArmLayer[] LAYERS = {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW};

	private static ArmBudgetTest.Draw axe() {
		return ArmBudgetTest.draw(ArmProtocol.AXE);
	}

	private static ArmBudgetTest.Recorder emit(ArmDraw d, ArmLayer layer) {
		ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
		r.keep = true;
		AXE.emit(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
		return r;
	}

	/** The bounding box of the vertices {@code layers} drew: minX, maxX, minY, maxY, minZ, maxZ. */
	private static float[] bounds(ArmDraw d, ArmLayer... layers) {
		return boundsWhere(d, v -> true, layers);
	}

	/** As {@link #bounds}, over the vertices {@code keep} accepts (the head is where x is below -2.5 and y above 6). */
	private static float[] boundsWhere(ArmDraw d, java.util.function.Predicate<float[]> keep, ArmLayer... layers) {
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
		return AXE.point(d, which, new Vector3f());
	}

	@Test
	void isTheRealAxeNotThePlaceholder() {
		assertTrue(AXE instanceof WarAxeGeometry, "ArmGeometry found " + AXE.getClass().getSimpleName());
		assertTrue(ArmGeometry.built(ArmProtocol.AXE));
		assertEquals(ArmProtocol.AXE, AXE.form());
		assertFalse(AXE.bowTransforms());
		assertEquals(0.62F, AXE.guiScale(), 1e-6F);
		assertEquals(3.7F, AXE.guiGripPx(), 1e-6F);
		assertEquals(KatanaGeometry.EDGE_SIGN, WarAxeGeometry.EDGE_SIGN, 0F, "the axe is mirrored as the katana is");
	}

	@Test
	void theFanHasFourSixAndSevenRibs() {
		assertEquals(4, WarAxeGeometry.ribCount(1));
		assertEquals(6, WarAxeGeometry.ribCount(2));
		assertEquals(7, WarAxeGeometry.ribCount(3));
	}

	@Test
	void theEdgeIsAChippedCrescentThatEndsInPoints() {
		emit(axe().build(), ArmLayer.BLACK);
		Stations s = WarAxeGeometry.lastEdge();
		assertEquals(14, s.count(), "the edge's stations at full detail");
		float arc = WarAxeGeometry.edgeArc(2);
		assertTrue(arc > 9F && arc < 10.5F, "the edge's arc: " + arc);
		for (int end : new int[] {0, s.count() - 1}) {
			float spread = 0F;
			for (int j = 0; j < s.sides(); j++) {
				float dx = s.corner(end, j, 0) - s.centre(end, 0), dy = s.corner(end, j, 1) - s.centre(end, 1), dz = s.corner(end, j, 2) - s.centre(end, 2);
				spread = Math.max(spread, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
			}
			assertTrue(spread < 0.12F, "the edge ends in a point (corners " + spread + " px out at station " + end + ")");
		}
		// the beard is longer at level 3 (1.5 px), and level 1's head is smaller
		assertTrue(WarAxeGeometry.edgeArc(3) > arc + 1.4F, "level 3's beard: " + WarAxeGeometry.edgeArc(3) + " against " + arc);
		assertTrue(WarAxeGeometry.edgeArc(1) < arc - 0.5F);
		// the crescent bulges outward: a station in the middle is further out than the chord of the ends
		float midOut = Math.abs(s.centre(7, 0)), endsOut = 0.5F * (Math.abs(s.centre(0, 0)) + Math.abs(s.centre(s.count() - 1, 0)));
		assertTrue(midOut > endsOut + 1.2F, "the crescent bows out: " + midOut + " against " + endsOut);
	}

	@Test
	void theEdgeFacesDownAndLeadsTheSwing() {
		// built on +X and drawn through the katana's mirror: the head is on -X in G, the poll on +X
		ArmDraw d = axe().build();
		float[] red = bounds(d, ArmLayer.RED);
		assertTrue(red[0] < -6.5F, "the red edge reaches out on -X: " + red[0]);
		float[] all = bounds(d, ArmLayer.BLACK, ArmLayer.RED);
		assertTrue(all[0] < -7F && all[1] < 5.5F, "the head is on -X, the poll's thorns stay near: " + all[0] + ".." + all[1]);
		assertTrue(point(d, ArmTips.TIP).x < -5F, "the tip is on the edge: " + point(d, ArmTips.TIP));
		assertTrue(point(d, ArmTips.TIP).y > 11F && point(d, ArmTips.TIP).y < 16F);
		assertEquals(0F, point(d, ArmTips.GRIP).length(), 1e-6F);
		Vector3f mid = point(d, ArmTips.MID);
		assertTrue(mid.x < -2F && mid.x > -7F, "the healing latch sits on the fan: " + mid);
		float[] ext = new float[] {Float.MAX_VALUE, -Float.MAX_VALUE};
		AXE.extents(p -> {
			ext[0] = Math.min(ext[0], p.x());
			ext[1] = Math.max(ext[1], p.x());
		});
		assertTrue(ext[0] < -7F, "the declared extents are mirrored too: " + ext[0]);
	}

	@Test
	void tiersAddDetail() {
		int l1 = ArmBudgetTest.toolQuads(axe().tier(1).build()), l2 = ArmBudgetTest.toolQuads(axe().tier(2).build()),
				l3 = ArmBudgetTest.toolQuads(axe().tier(3).build());
		assertTrue(l1 < l2 && l2 < l3, "levels 1, 2, 3: " + l1 + ", " + l2 + ", " + l3);
		// level 3's edge and runes glow (with the glow setting on); level 2's don't
		assertTrue(emit(axe().tier(3).build(), ArmLayer.GLOW).quads() > 0, "level 3 glows");
		assertEquals(0, emit(axe().tier(2).build(), ArmLayer.GLOW).quads(), "level 2 at rest doesn't");
		// level 1 has no thong, no runes, one haft strand
		assertTrue(emit(axe().tier(1).build(), ArmLayer.RED).quads() < emit(axe().tier(2).build(), ArmLayer.RED).quads());
		// with the glow setting off, level 3's glowing faces are drawn red instead
		ArmBudgetTest.Draw off = axe().tier(3);
		off.glow = false;
		assertEquals(0, emit(off.build(), ArmLayer.GLOW).quads());
		assertTrue(emit(off.build(), ArmLayer.RED).quads() > emit(axe().tier(3).build(), ArmLayer.RED).quads());
	}

	@Test
	void theHaftGrowsOutOfTheFistAndTheDetailsFollow() {
		// FORM tick 4 (third person: no forearm): only the twisted haft, thin, partway out of the grip
		ArmDraw four = axe().state(ArmProtocol.STATE_FORMING, 4F / 14F).build();
		float[] b4 = bounds(four, ArmLayer.BLACK, ArmLayer.RED);
		assertTrue(b4[1] < 1.6F && b4[0] > -1.6F, "no head yet: x " + b4[0] + ".." + b4[1]);
		assertTrue(b4[3] > 3F && b4[3] < 14F && b4[2] < -0.5F, "the haft is out both ways from the fist: y " + b4[2] + ".." + b4[3]);
		// tick 8: the whole haft is out, but nothing of the head, no cuff, no ribs, no edge
		ArmDraw eight = axe().state(ArmProtocol.STATE_FORMING, 8F / 14F).build();
		float[] b8 = bounds(eight, ArmLayer.BLACK, ArmLayer.RED);
		assertTrue(b8[3] > 17F && b8[2] < -4.5F, "the haft is all out by tick 8: y " + b8[2] + ".." + b8[3]);
		assertTrue(b8[0] > -1.6F && b8[1] < 1.6F, "and still no head: x " + b8[0] + ".." + b8[1]);
		assertEquals(0, emit(eight, ArmLayer.GLOW).quads());
		// tick 10: the cuff, poll and lashings are out and the ribs are sprouting
		ArmDraw ten = axe().state(ArmProtocol.STATE_FORMING, 10F / 14F).build();
		float[] b10 = bounds(ten, ArmLayer.BLACK, ArmLayer.RED);
		assertTrue(b10[0] < -2.5F && b10[0] > -7.5F, "the ribs are growing out of the cuff: " + b10[0]);
		// tick 13 and the end: the whole head, edge and all
		ArmDraw end = axe().state(ArmProtocol.STATE_FORMING, 1F).build();
		float[] be = bounds(end, ArmLayer.BLACK, ArmLayer.RED);
		assertTrue(be[0] < -7F, "the whole head at the end of FORM: " + be[0]);
		// the red edge is the last thing drawn: none of it at tick 10, all of it by tick 13
		assertTrue(emit(ten, ArmLayer.RED).quads() < emit(end, ArmLayer.RED).quads());
		float[] r10 = bounds(ten, ArmLayer.RED), r13 = bounds(axe().state(ArmProtocol.STATE_FORMING, 13.5F / 14F).build(), ArmLayer.RED);
		assertTrue(r10[0] > -2F, "no red edge yet at tick 10: " + r10[0]);
		assertTrue(r13[0] < -6.5F, "the edge is drawn by tick 13.5: " + r13[0]);
		// quick-form plays the same order
		ArmDraw quick = axe().state(ArmProtocol.STATE_QUICKFORM, 4.5F / 8F).build();
		assertTrue(bounds(quick, ArmLayer.BLACK, ArmLayer.RED)[3] > 17F, "quick-form's haft is out by tick 4.5");
	}

	@Test
	void bristlesVaryAndNoneSitsOnTheEdgesEnds() {
		// every bristle table has its own numbers: no two the same length, lean and hook together
		float[][] tables = bristleTables();
		int n = tables[0].length;
		for (int i = 0; i < n; i++) {
			for (int j = i + 1; j < n; j++) {
				boolean same = tables[0][i] == tables[0][j] && tables[1][i] == tables[1][j] && tables[2][i] == tables[2][j];
				assertFalse(same, "bristles " + i + " and " + j + " are alike");
			}
		}
		float min = Float.MAX_VALUE, max = 0F;
		for (float v : tables[0]) {
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		assertTrue(max / min > 2F, "lengths range " + min + ".." + max);
		// bristles lengthen with Fell's bristling (the whole geometry grows) and lean straight out
		ArmBudgetTest.Draw fell = axe();
		fell.specialKind = ArmProtocol.FX_FELL;
		fell.specialAge = 4F;
		float[] rest = bounds(axe().build(), ArmLayer.BLACK), bristling = bounds(fell.build(), ArmLayer.BLACK);
		assertTrue((bristling[1] - bristling[0]) + (bristling[3] - bristling[2]) > (rest[1] - rest[0]) + (rest[3] - rest[2]) + 0.8F, "Fell bristles the fur");
	}

	/** Length, lean and hook of the bristles, read from the geometry's own tables by reflection. */
	private static float[][] bristleTables() {
		try {
			String[] names = {"BR_LEN", "BR_ANG", "BR_CURL"};
			float[][] out = new float[3][];
			for (int i = 0; i < 3; i++) {
				var f = WarAxeGeometry.class.getDeclaredField(names[i]);
				f.setAccessible(true);
				out[i] = (float[]) f.get(null);
			}
			return out;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	void cleaveSplaysThePlatesApartForSixTicks() {
		java.util.function.Predicate<float[]> fan = v -> v[0] < -2.5F && v[1] > 9F;
		float[] rest = boundsWhere(axe().build(), fan, ArmLayer.BLACK);
		ArmBudgetTest.Draw cleave = axe();
		cleave.specialKind = ArmProtocol.FX_CLEAVE;
		cleave.specialAge = 2.5F;
		float[] splayed = boundsWhere(cleave.build(), fan, ArmLayer.BLACK);
		assertTrue((splayed[5] - splayed[4]) > (rest[5] - rest[4]) + 0.4F, "the plates splay in depth: " + (rest[5] - rest[4]) + " to " + (splayed[5] - splayed[4]));
		// closed again after the sixth tick, and not before the special begins
		cleave.specialAge = 6.5F;
		float[] closed = boundsWhere(cleave.build(), fan, ArmLayer.BLACK);
		assertEquals(rest[5] - rest[4], closed[5] - closed[4], 0.05F);
		ArmBudgetTest.Draw early = axe();
		early.specialKind = ArmProtocol.FX_CLEAVE;
		early.specialAge = 0F;
		float[] start = boundsWhere(early.build(), fan, ArmLayer.BLACK);
		assertEquals(rest[5] - rest[4], start[5] - start[4], 0.05F, "no splay at the first instant");
		// the splay never exceeds 0.6 px between neighbours (0.3 each way): well inside the budget, finite
		assertTrue(ArmBudgetTest.toolQuads(cleave.build()) > 0);
	}

	@Test
	void hitsChopsAndBreaksMoveTheHead() {
		Vector3f rest = point(axe().build(), ArmTips.TIP);
		ArmBudgetTest.Draw brk = axe();
		brk.breakAge = 1.9F;
		assertTrue(point(brk.build(), ArmTips.TIP).distance(rest) > 0.4F, "a broken block tips the head");
		ArmBudgetTest.Draw chop = axe().time(101.25F);
		chop.mining = true;
		assertTrue(point(chop.build(), ArmTips.TIP).distance(point(axe().time(101.25F).build(), ArmTips.TIP)) > 0.3F, "chopping bites the head forward");
		// a hit flares the edge: its red faces widen
		ArmBudgetTest.Draw hit = axe();
		hit.hitAge = 1F;
		float[] flared = bounds(hit.build(), ArmLayer.RED), calm = bounds(axe().build(), ArmLayer.RED);
		assertTrue(flared[0] < calm[0] - 0.05F, "the edge band flares out: " + flared[0] + " against " + calm[0]);
		// and flicks beads off the edge
		assertTrue(ArmBudgetTest.toolQuads(hit.build()) > ArmBudgetTest.toolQuads(axe().build()), "beads off the edge");
	}

	@Test
	void lowBiomassThinsCracksAndErodes() {
		ArmBudgetTest.Draw thin = axe();
		thin.biomassFrac = 0.2F;
		thin.biomassAbs = 25F;
		float[] full = bounds(axe().build(), ArmLayer.BLACK, ArmLayer.RED), thinned = bounds(thin.build(), ArmLayer.BLACK, ArmLayer.RED);
		assertTrue(thinned[0] > full[0] - 0.3F, "thinning narrows it, never widens it");
		ArmBudgetTest.Draw starving = axe();
		starving.biomassFrac = 0.08F;
		starving.biomassAbs = 8F;
		assertTrue(ArmBudgetTest.toolQuads(starving.build()) > 0);
		// failing: the edge's last 8% is eroded away: its beard toe (the lowest red) is cut short
		ArmBudgetTest.Draw failing = axe();
		failing.biomassFrac = 0.01F;
		failing.biomassAbs = 1F;
		java.util.function.Predicate<float[]> head = v -> v[1] > 6F && v[0] < -2.5F;
		float[] redRest = boundsWhere(axe().build(), head, ArmLayer.RED), redFailing = boundsWhere(failing.build(), head, ArmLayer.RED);
		assertTrue(redFailing[2] > redRest[2] + 0.1F, "the beard's toe is eroded: " + redFailing[2] + " against " + redRest[2]);
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
							ArmBudgetTest.Draw b = axe().tier(tier).lod(lod).ctx(ctx).time(30F + step * 1.7F).state(state, step / 20F);
							b.fromForm = ArmProtocol.KATANA;
							b.leftHand = (step & 1) == 1;
							b.biomassFrac = step % 5 == 4 ? 0.01F : step % 5 == 3 ? 0.08F : step % 5 == 2 ? 0.2F : 1F;
							b.biomassAbs = step % 5 == 4 ? 1F : step % 5 == 3 ? 8F : step % 5 == 2 ? 25F : ArmDraw.FULL;
							b.mining = step % 7 == 3;
							b.hitAge = step % 4 == 1 ? 1F + step * 0.1F : ArmDraw.NEVER;
							b.breakAge = step % 6 == 2 ? 2.4F : ArmDraw.NEVER;
							b.surge = step % 9 == 5 ? 0.6F : 0F;
							b.swing = step % 3 == 0 ? 0.5F : 0F;
							if (step % 8 == 6) {
								b.specialKind = step % 16 == 6 ? ArmProtocol.FX_CLEAVE : ArmProtocol.FX_FELL;
								b.specialAge = 2.5F;
							}
							ArmDraw d = b.build();
							drawn += ArmBudgetTest.toolQuads(d); // finite, unit normals, no lost or doubled quads
							for (int which : new int[] {ArmTips.TIP, ArmTips.MID, ArmTips.GRIP}) {
								Vector3f p = AXE.point(d, which, new Vector3f());
								assertTrue(Float.isFinite(p.x) && Float.isFinite(p.y) && Float.isFinite(p.z), "point " + which + " " + p);
							}
						}
					}
				}
			}
		}
		assertTrue(drawn > 0);
	}

	@Test
	void sheathAndForearmHaveNoTrouble() {
		// the third-person sheath is the shared one; the axe only has to stay out of its way (budgets below count both)
		assertTrue(ArmBudgetTest.sheathQuads(axe().build()) > 0);
	}

	// ------------------------------------------------------------------ budgets (section 2.10)

	@Test
	void ownArmFirstPersonStaysUnderItsCeilings() {
		for (int tier = 1; tier <= 3; tier++) {
			int steady = ArmBudgetTest.worst(axe().tier(tier).ctx(ArmDraw.CTX_FP), false);
			System.out.printf("axe L%d own FP LOD0 steady: %d%n", tier, steady);
			assertTrue(steady <= 1300, "axe L" + tier + " first person: " + steady + " quads, over 1300");
			assertTrue(steady >= (ArmDraw.FIRST_PERSON_ARM ? 700 : 400), "axe L" + tier + " first person: only " + steady + " quads; the detail is missing (first person is the tool alone)");
		}
	}

	@Test
	void burstsStayUnderTheBurstCeiling() {
		for (int tier = 1; tier <= 3; tier++) {
			int worst = 0;
			for (int step = 0; step <= 56; step++) {
				float p = step / 56F;
				worst = Math.max(worst, ArmBudgetTest.toolQuads(axe().tier(tier).ctx(ArmDraw.CTX_FP).time(50F + p * 14F).state(ArmProtocol.STATE_FORMING, p).build()));
				ArmBudgetTest.Draw b = axe().tier(tier).ctx(ArmDraw.CTX_FP).time(50F + p * 16F).state(ArmProtocol.STATE_RESHAPING, p);
				b.fromForm = ArmProtocol.KATANA;
				ArmDraw d = b.build();
				worst = Math.max(worst, ArmBudgetTest.toolQuads(d) + (d.knotTick() >= 0F ? 8 * 6 : 0));
			}
			System.out.printf("axe L%d own FP burst: %d%n", tier, worst);
			assertTrue(worst <= 1700, "axe L" + tier + " forming or reshaping: " + worst + " quads, over 1700");
		}
	}

	@Test
	void anotherPlayerUpCloseStaysUnderItsCeiling() {
		for (int tier = 1; tier <= 3; tier++) {
			int quads = ArmBudgetTest.worst(axe().tier(tier), true);
			System.out.printf("axe L%d TP LOD0 tool+sheath: %d%n", tier, quads);
			int ceiling = tier >= 3 ? 1300 : 1200;
			assertTrue(quads <= ceiling, "axe L" + tier + " third person: " + quads + " quads, over " + ceiling);
		}
	}

	@Test
	void levelsOfDetailShed() {
		for (int tier = 1; tier <= 3; tier++) {
			int lod1Tool = ArmBudgetTest.worst(axe().tier(tier).lod(1), false), lod1 = ArmBudgetTest.worst(axe().tier(tier).lod(1), true);
			int lod2 = ArmBudgetTest.worst(axe().tier(tier).lod(2), true), lod3 = ArmBudgetTest.worst(axe().tier(tier).lod(3), true);
			int icon = ArmBudgetTest.worst(axe().tier(tier).lod(ArmDraw.LOD_ICON).ctx(ArmDraw.CTX_GUI), false);
			System.out.printf("axe L%d LOD1 tool %d total %d, LOD2 %d, LOD3 %d, icon %d%n", tier, lod1Tool, lod1, lod2, lod3, icon);
			assertTrue(lod1Tool <= 360, "axe L" + tier + " LOD 1 tool: " + lod1Tool + " (about 300)");
			assertTrue(lod1 <= 576, "axe L" + tier + " LOD 1: " + lod1 + " (about 480)");
			assertTrue(lod2 <= 116, "axe L" + tier + " LOD 2: " + lod2 + " (about 96)");
			assertTrue(lod3 <= 30, "axe L" + tier + " LOD 3: " + lod3 + " (four boxes and one)");
			assertTrue(icon <= 360, "axe L" + tier + " icon: " + icon);
			assertTrue(lod3 < lod2 && lod2 < lod1, "axe L" + tier + ": each level of detail should cost less than the one before");
		}
	}

	@Test
	void retractedArmDrawsNothing() {
		ArmDraw none = axe().state(ArmProtocol.STATE_NONE, 1F).build();
		assertEquals(0, ArmBudgetTest.toolQuads(none));
		assertEquals(0, ArmBudgetTest.toolQuads(axe().state(ArmProtocol.STATE_RETRACTING, 1F).build()), "a finished retract draws nothing");
	}

	@Test
	void everyLayerOfEveryLevelHasThePiecesItShould() {
		for (int tier = 1; tier <= 3; tier++) {
			ArmDraw d = axe().tier(tier).build();
			for (ArmLayer layer : LAYERS) {
				if (layer == ArmLayer.GLOW && tier < 3) continue;
				assertTrue(emit(d, layer).quads() > 0, "axe L" + tier + " has " + layer + " faces");
			}
		}
	}
}
