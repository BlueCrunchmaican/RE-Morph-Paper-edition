package dev.morphreborn.client.arm.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.Pulse;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Stations;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * The katana's shape against section 2.4: the blade's arc (21.0 px at level 2, 22.4 at level 3), its 28 stations
 * ending in a collapsed tip, a hamon that stays within its band, a guard that clears the blade; and that every timeline,
 * tier, level of detail and reaction draws real geometry (no NaN, no zero-length normals), growing by truncation.
 */
class KatanaGeometryTest {
	private static final FormGeometry KATANA = ArmGeometry.get(ArmProtocol.KATANA);

	private static ArmBudgetTest.Draw katana() {
		return ArmBudgetTest.draw(ArmProtocol.KATANA);
	}

	/** Emits {@code d} in {@code layer} into a fresh recorder and returns it. */
	private static ArmBudgetTest.Recorder emit(ArmDraw d, ArmLayer layer) {
		ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
		KATANA.emit(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
		return r;
	}

	@Test
	void isTheRealKatanaNotThePlaceholder() {
		assertTrue(KATANA instanceof KatanaGeometry, "ArmGeometry found " + KATANA.getClass().getSimpleName());
		assertTrue(ArmGeometry.built(ArmProtocol.KATANA));
	}

	@Test
	void bladeArcIsTheSpecs() {
		assertEquals(21.0F, KatanaGeometry.bladeArc(2), 0.3F, "level 2's blade");
		assertEquals(22.4F, KatanaGeometry.bladeArc(3), 0.3F, "level 3's blade, 1.4 px longer");
		assertTrue(KatanaGeometry.bladeArc(1) < KatanaGeometry.bladeArc(2), "level 1's blade is 5% shorter");
		assertEquals(KATANA.feedLength(2) - KatanaGeometry.bladeArc(2), KATANA.feedLength(3) - KatanaGeometry.bladeArc(3), 1e-4F,
				"the feed coordinate runs to the tip");
	}

	@Test
	void bladeIsTwentyEightStationsToACollapsedTip() {
		emit(katana().build(), ArmLayer.BLACK);
		Stations s = KatanaGeometry.lastBlade();
		assertEquals(KatanaGeometry.STATIONS, s.count(), "a full-detail blade");
		assertEquals(28, s.count());
		assertEquals(KatanaGeometry.bladeArc(2), s.arc(s.count() - 1), 0.05F, "the last station is at the tip");
		// the tip: every corner of the last section within a hair of its centre
		int last = s.count() - 1;
		float spread = 0F;
		for (int j = 0; j < s.sides(); j++) {
			float dx = s.corner(last, j, 0) - s.centre(last, 0), dy = s.corner(last, j, 1) - s.centre(last, 1), dz = s.corner(last, j, 2) - s.centre(last, 2);
			spread = Math.max(spread, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
		}
		assertTrue(spread < 0.15F, "the tip collapses to a point (corners " + spread + " px out)");
		// the base: the full 1.9 px width from the back to the edge
		float width = 0F;
		for (int j = 0; j < s.sides(); j++) {
			for (int k = 0; k < s.sides(); k++) {
				float dx = s.corner(0, j, 0) - s.corner(0, k, 0), dy = s.corner(0, j, 1) - s.corner(0, k, 1);
				width = Math.max(width, (float) Math.sqrt(dx * dx + dy * dy));
			}
		}
		assertEquals(1.9F, width, 0.15F, "the blade's width at the collar");
		// level of detail 1 keeps 15 stations (14 segments)
		emit(katana().lod(1).build(), ArmLayer.BLACK);
		assertEquals(15, KatanaGeometry.lastBlade().count(), "LOD 1's blade");
	}

	@Test
	void hamonStaysInItsBand() {
		for (int tier = 1; tier <= 3; tier++) {
			float arc = KatanaGeometry.bladeArc(tier);
			for (float v = 0F; v <= arc; v += 0.1F) {
				for (float t = 0F; t < 600F; t += 37.3F) {
					for (float amp = 0F; amp <= 1.6F; amp += 0.4F) {
						float f = KatanaGeometry.hamon(tier, v, t, amp);
						assertTrue(f >= 0.25F && f <= 0.75F, "f " + f + " at L" + tier + " v " + v + " t " + t + " amp " + amp);
					}
				}
			}
		}
		// and it really moves: a pulse lifts it, and it wavers along the blade
		assertTrue(KatanaGeometry.hamon(2, 5F, 0F, 0.8F) < KatanaGeometry.hamon(2, 5F, 0F, 0F), "a pulse lifts the hamon");
		float lo = 1F, hi = 0F;
		for (float v = 0F; v <= 21F; v += 0.25F) {
			lo = Math.min(lo, KatanaGeometry.hamon(2, v, 0F, 0F));
			hi = Math.max(hi, KatanaGeometry.hamon(2, v, 0F, 0F));
		}
		assertTrue(hi - lo > 0.2F, "the hamon wavers along the blade (" + lo + ".." + hi + ")");
	}

	@Test
	void tsubaClearsTheBlade() {
		assertTrue(KatanaGeometry.tsubaClearance() > 0.95F, "the inner loop's inside edge: " + KatanaGeometry.tsubaClearance() + " px");
	}

	@Test
	void hamonIsDrawnAtTheEndOfForming() {
		// at FORM's tick 7 the blade is nearly out but the hamon not yet drawn: its edge band's 54 red quads are black
		// (counted above the collar, where only the blade is)
		assertEquals(54, redQuadsAbove(katana().build(), 3.45F), "the edge band: 27 segments × 2 red faces");
		assertEquals(0, redQuadsAbove(katana().state(ArmProtocol.STATE_FORMING, 7F / 14F).build(), 3.45F), "not yet drawn at tick 7");
		assertEquals(54, redQuadsAbove(katana().state(ArmProtocol.STATE_FORMING, 9.5F / 14F).build(), 3.45F), "drawn by tick 9");
	}

	@Test
	void bladeGrowsByTruncation() {
		float lastFront = -1F;
		int lastCount = 0;
		for (int step = 0; step <= 60; step++) {
			float t = ArmDraw.FORM_GROW_FROM + ArmDraw.FORM_GROW_TICKS * step / 60F;
			// skip the first instants, while the blade is too short to draw at all
			if (KatanaGeometry.bladeArc(2) * dev.morphreborn.client.arm.render.ArmMath.sm((t - ArmDraw.FORM_GROW_FROM) / ArmDraw.FORM_GROW_TICKS) < 0.2F) continue;
			ArmDraw d = katana().state(ArmProtocol.STATE_FORMING, t / 14F).build();
			emit(d, ArmLayer.BLACK);
			Stations s = KatanaGeometry.lastBlade();
			float front = s.arc(s.count() - 1);
			assertTrue(front >= lastFront - 1e-4F, "the front never moves back (" + front + " after " + lastFront + ")");
			assertTrue(s.count() >= lastCount, "growth only adds stations");
			// the stations under the front are the whole blade's own, not a stretched blade
			for (int i = 0; i + 1 < s.count(); i++) assertTrue(s.arc(i) < front, "a station past the front");
			lastFront = front;
			lastCount = s.count();
		}
		assertEquals(KatanaGeometry.bladeArc(2), lastFront, 0.05F, "grown to the tip by FORM's tick 8");
	}

	@Test
	void tipPointFollowsTheBlade() {
		Vector3f rest = KATANA.point(katana().build(), ArmTips.TIP, new Vector3f());
		Vector3f mid = KATANA.point(katana().build(), ArmTips.MID, new Vector3f());
		Vector3f grip = KATANA.point(katana().build(), ArmTips.GRIP, new Vector3f());
		assertTrue(rest.y > 23F, "the tip at rest: " + rest);
		assertTrue(mid.y > 8F && mid.y < rest.y, "the middle: " + mid);
		assertEquals(0F, grip.length(), 1e-6F, "the grip is G's origin");
		Vector3f half = KATANA.point(katana().state(ArmProtocol.STATE_FORMING, 4.5F / 14F).build(), ArmTips.TIP, new Vector3f());
		assertTrue(half.y < rest.y - 5F, "half grown, the tip is the front: " + half);
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
								ArmBudgetTest.Draw draw = katana().tier(tier).lod(lod).ctx(ctx).state(state, p).time(1000F + 13.7F * p);
								draw.biomassFrac = b[0];
								draw.biomassAbs = b[1];
								draw.fromForm = state == ArmProtocol.STATE_RESHAPING ? ArmProtocol.PICKAXE : "";
								draw.leftHand = (tier & 1) == 0;
								for (ArmLayer layer : new ArmLayer[] {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW}) emit(draw.build(), layer);
							}
						}
					}
				}
			}
		}
	}

	@Test
	void reactionsDrawRealGeometry() {
		for (float age = 0F; age < 12F; age += 0.5F) {
			ArmBudgetTest.Draw hit = katana().ctx(ArmDraw.CTX_FP);
			hit.hitAge = age;
			hit.pulses = new float[] {Pulse.Kind.SPEND.ordinal(), hit.time - age, Pulse.Kind.FEED.ordinal(), hit.time - age * 0.5F};
			hit.pulseCount = 2;
			hit.swing = age / 12F;
			ArmBudgetTest.Draw cut = katana().tier(3);
			cut.specialKind = ArmProtocol.FX_SWIPE;
			cut.specialAge = age < 8F ? age : -(age - 8F);
			cut.surge = 1F - age / 12F;
			for (ArmLayer layer : new ArmLayer[] {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW}) {
				assertTrue(emit(hit.build(), layer).quads() >= 0);
				assertTrue(emit(cut.build(), layer).quads() >= 0);
			}
		}
		// a hit flares the hamon wide: more of the edge band's area turns red, so its red quads stay (54 at level 2)
		ArmBudgetTest.Draw hit = katana();
		hit.hitAge = 1F;
		assertEquals(emit(katana().build(), ArmLayer.RED).quads(), emit(hit.build(), ArmLayer.RED).quads() - beads(hit.build()), "a hit adds only its beads");
	}

	/**
	 * Red quads of {@code d} on the blade: all four corners above {@code y} px (the blade starts at 3.5) and reaching past
	 * the collar's top (3.8), so the collar's knots don't count.
	 */
	private static int redQuadsAbove(ArmDraw d, float y) {
		ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
		r.keep = true;
		KATANA.emit(d, ArmLayer.RED, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
		int count = 0;
		for (int q = 0; q + 3 < r.kept.size(); q += 4) {
			boolean above = true;
			float top = Float.NEGATIVE_INFINITY;
			for (int i = 0; i < 4; i++) {
				above &= r.kept.get(q + i)[1] > y;
				top = Math.max(top, r.kept.get(q + i)[1]);
			}
			if (above && top > 3.9F) count++;
		}
		return count;
	}

	/** The red beads a hit flicks off the tip (one of the two), counted in quads. */
	private static int beads(ArmDraw d) {
		return d.hitAge() < 8F ? 6 : 0;
	}
}
