package dev.morphreborn.client.arm.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * The pickaxe against section 2.5: a 14-station talon that runs to a point, the tiers adding detail, the prong gnawing
 * while mining and recoiling from a broken block, Vein Rip pushing it out, the braid growing both ways out of the fist
 * (faster toward the butt); and every timeline, tier, level of detail and reaction drawing real geometry.
 */
class PickaxeGeometryTest {
	private static final FormGeometry PICKAXE = ArmGeometry.get(ArmProtocol.PICKAXE);

	private static ArmBudgetTest.Draw pickaxe() {
		return ArmBudgetTest.draw(ArmProtocol.PICKAXE);
	}

	private static ArmBudgetTest.Recorder emit(ArmDraw d, ArmLayer layer) {
		ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
		PICKAXE.emit(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
		return r;
	}

	private static Vector3f tip(ArmDraw d) {
		return PICKAXE.point(d, ArmTips.TIP, new Vector3f());
	}

	@Test
	void isTheRealPickaxeNotThePlaceholder() {
		assertTrue(PICKAXE instanceof PickaxeGeometry, "ArmGeometry found " + PICKAXE.getClass().getSimpleName());
		assertTrue(ArmGeometry.built(ArmProtocol.PICKAXE));
		assertEquals(0.80F, PICKAXE.guiScale(), 1e-6F);
		assertEquals(4.2F, PICKAXE.guiGripPx(), 1e-6F);
	}

	@Test
	void prongIsFourteenStationsToAPoint() {
		emit(pickaxe().build(), ArmLayer.BLACK);
		Stations s = PickaxeGeometry.lastProng();
		assertEquals(14, s.count(), "a full-detail prong");
		assertTrue(PickaxeGeometry.PRONG_ARC > 8F && PickaxeGeometry.PRONG_ARC < 10.5F, "the prong's arc: " + PickaxeGeometry.PRONG_ARC);
		int last = s.count() - 1;
		float spread = 0F;
		for (int j = 0; j < s.sides(); j++) {
			float dx = s.corner(last, j, 0) - s.centre(last, 0), dy = s.corner(last, j, 1) - s.centre(last, 1), dz = s.corner(last, j, 2) - s.centre(last, 2);
			spread = Math.max(spread, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
		}
		assertTrue(spread < 0.12F, "the prong ends in a point (corners " + spread + " px out)");
		// it curves down onto its point, on the leading side
		Vector3f p = tip(pickaxe().build());
		assertTrue(p.x * PickaxeGeometry.EDGE_SIGN > 8F && p.y < 13.5F, "the talon's point, on the leading (mirrored) side: " + p);
		assertEquals(PICKAXE.feedLength(2), PickaxeGeometry.phiHaft(15.6F) + PickaxeGeometry.PRONG_ARC, 1e-4F, "the feed coordinate runs to the point");
	}

	@Test
	void tiersAddDetail() {
		int l1 = ArmBudgetTest.toolQuads(pickaxe().tier(1).build()), l2 = ArmBudgetTest.toolQuads(pickaxe().tier(2).build()),
				l3 = ArmBudgetTest.toolQuads(pickaxe().tier(3).build());
		assertTrue(l1 < l2 && l2 < l3, "levels 1, 2, 3: " + l1 + ", " + l2 + ", " + l3);
		// level 3's eye and keel glow (with the glow setting on); level 2's don't, unless it is mining
		assertTrue(emit(pickaxe().tier(3).build(), ArmLayer.GLOW).quads() > 0, "level 3 glows");
		assertEquals(0, emit(pickaxe().tier(2).build(), ArmLayer.GLOW).quads(), "level 2 at rest doesn't");
		ArmBudgetTest.Draw mining = pickaxe().tier(2);
		mining.mining = true;
		assertEquals(12, emit(mining.build(), ArmLayer.GLOW).quads(), "level 2's eyes glow while mining");
	}

	@Test
	void prongGnawsRecoilsAndRips() {
		Vector3f rest = tip(pickaxe().time(100F).build());
		// mining: a bite every 5 ticks, at its deepest a quarter of the way through each
		ArmBudgetTest.Draw bite = pickaxe().time(101.25F);
		bite.mining = true;
		Vector3f bitten = tip(bite.build());
		Vector3f restThen = tip(pickaxe().time(101.25F).build());
		assertTrue(bitten.distance(restThen) > 0.5F, "the prong bites out 0.6 px (moved " + bitten.distance(restThen) + ")");
		// a broken block: pulled back after 2 ticks, past rest after 3.5
		ArmBudgetTest.Draw back = pickaxe().time(100F);
		back.breakAge = 2F;
		ArmBudgetTest.Draw over = pickaxe().time(100F);
		over.breakAge = 3.5F;
		Vector3f socket = new Vector3f(0F, 15.4F, 0F);
		assertTrue(tip(back.build()).distance(socket) < rest.distance(socket) - 0.5F, "recoil pulls the point in");
		assertTrue(tip(over.build()).distance(socket) > rest.distance(socket) + 0.2F, "then it overshoots");
		// Vein Rip: pushed out 1.5 px
		ArmBudgetTest.Draw rip = pickaxe().time(100F);
		rip.specialKind = ArmProtocol.FX_VEIN;
		rip.specialAge = 5F;
		assertEquals(1.5F, tip(rip.build()).distance(rest), 0.2F, "Vein Rip pushes the prong out");
	}

	@Test
	void braidGrowsBothWaysFromTheFist() {
		// FORM's tick 6: the braid is out further toward the butt (1.5× faster) than toward the head, in proportion
		ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
		r.keep = true;
		// (the red core under the braid, which grows with it; the burst tendrils it would catch stay between its ends)
		PICKAXE.emit(pickaxe().state(ArmProtocol.STATE_FORMING, 6F / 14F).build(), ArmLayer.RED, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
		float lo = 0F, hi = 0F;
		for (float[] v : r.kept) {
			if (Math.abs(v[0]) > 0.6F || Math.abs(v[2]) > 0.6F) continue;
			lo = Math.min(lo, v[1]);
			hi = Math.max(hi, v[1]);
		}
		assertTrue(-lo / 5F > hi / 14.2F, "toward the butt " + (-lo / 5F) + " of the way, toward the head " + (hi / 14.2F));
		assertTrue(hi > 1F && lo < -1F, "it grows both ways: " + lo + ".." + hi);
		// nothing of the head yet, and by the end of FORM all of it
		assertTrue(tip(pickaxe().state(ArmProtocol.STATE_FORMING, 6F / 14F).build()).y < 15F, "no prong at tick 6");
		assertTrue(tip(pickaxe().state(ArmProtocol.STATE_FORMING, 1F).build()).x * PickaxeGeometry.EDGE_SIGN > 8F, "the whole prong at the end");
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
						for (float p = 0F; p <= 1F; p += 0.25F) {
							for (float[] b : biomass) {
								ArmBudgetTest.Draw draw = pickaxe().tier(tier).lod(lod).ctx(ctx).state(state, p).time(1000F + 13.7F * p);
								draw.biomassFrac = b[0];
								draw.biomassAbs = b[1];
								draw.fromForm = state == ArmProtocol.STATE_RESHAPING ? ArmProtocol.KATANA : "";
								draw.leftHand = (tier & 1) == 0;
								draw.mining = state == ArmProtocol.STATE_FORMED && tier >= 2;
								draw.breakAge = p * 6F;
								for (ArmLayer layer : new ArmLayer[] {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW}) emit(draw.build(), layer);
							}
						}
					}
				}
			}
		}
	}

	@Test
	void crackedWhenStarving() {
		// starving, the haft's crack and the prong's crack turn red: more red quads than a well-fed pick
		ArmBudgetTest.Draw starving = pickaxe();
		starving.biomassFrac = 0.05F;
		starving.biomassAbs = 5F;
		int fed = emit(pickaxe().build(), ArmLayer.RED).quads(), starved = emit(starving.build(), ArmLayer.RED).quads();
		assertTrue(starved > fed, "cracks: " + fed + " red quads fed, " + starved + " starving");
	}
}
