package dev.morphreborn.client.arm.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The shield accessory (2026-11-16): its quad budget in both views, the build and raise timelines, the blow's kick and ripple, and
 * that everything it writes is a finite number. Also, with {@code SHIELD_PREVIEW_DIR} set, writes pictures of it (there is no way to
 * see the game from a test). It has one look, the hand morphing into the shield (my pick, 2026-11-16).
 */
class ShieldRendererTest {
	private static final ArmLayer[] LAYERS = {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW};
	private static final ArmLayer[] SOLID = {ArmLayer.BLACK, ArmLayer.RED};

	@AfterEach
	void reset() {
		ShieldMotion.clear();
	}

	/** A shield draw: {@code age} the accessory's draw age, {@code raised} its arg, {@code lod}, {@code ctx}, which hand holds the tool. */
	static ArmDraw draw(float age, boolean raised, int lod, int ctx, boolean toolLeft, float time) {
		float[] ages = new float[ArmProtocol.ACCESSORIES.size()];
		ages[ArmProtocol.accessoryIndex(ArmProtocol.SHIELD)] = age;
		int bit = 1 << ArmProtocol.accessoryIndex(ArmProtocol.SHIELD);
		int args = raised ? 1 << (4 * ArmProtocol.accessoryIndex(ArmProtocol.SHIELD)) : 0;
		return new ArmDraw("", 2, lod, ctx, toolLeft, false, false, time, 7, ArmProtocol.STATE_NONE, 1F, "", 0F, 0F, false, 1F, ArmDraw.FULL, 0F, ArmDraw.NEVER,
				ArmDraw.NO_SPECIAL, ArmDraw.NEVER, ArmDraw.NEVER, new float[0], 0, false, 0F, 0F, true, true, false, null, 0F, -1F, 0F, null, 0xF000F0, 1F, bit, args, ages,
				false);
	}

	/** The quads {@code d} draws, all layers, in either view; the vertices (4 a quad, x y z) land in {@code into} when it is not null. */
	static int quads(ArmDraw d, boolean firstPerson, java.util.List<float[]> into) {
		return quads(d, firstPerson, into, LAYERS);
	}

	/** As above, in {@code layers} only (the glow pass draws a red face only where a pulse is passing, so it changes from moment to moment). */
	static int quads(ArmDraw d, boolean firstPerson, java.util.List<float[]> into, ArmLayer[] layers) {
		ShieldRenderer r = new ShieldRenderer();
		int total = 0;
		for (ArmLayer layer : layers) {
			if (layer == ArmLayer.GLOW && !d.glow()) continue;
			ArmBudgetTest.Recorder rec = new ArmBudgetTest.Recorder();
			rec.keep = into != null;
			SkinMesh m = SkinMesh.begin(new PoseStack().last(), rec, 0xF000F0, 0);
			Loft.pulseFrom(null, 0F);
			if (firstPerson) r.emitFirstPerson(d, layer, m);
			else r.emitThirdPerson(d, layer, m);
			assertEquals(rec.quads(), m.quads(), "quads counted twice or lost");
			total += rec.quads();
			if (into != null) into.addAll(rec.kept);
		}
		return total;
	}

	// ------------------------------------------------------------------ the shape

	@Test
	void outlineIsAClosedLoopOfFinitePoints() {
		for (float x = -ShieldRenderer.HW; x <= ShieldRenderer.HW; x += 0.1F) {
			assertTrue(Float.isFinite(ShieldRenderer.topY(x)));
			assertTrue(ShieldRenderer.botY(x) >= ShieldRenderer.TIP - 1e-3F && ShieldRenderer.botY(x) <= ShieldRenderer.WAIST + 1e-3F);
			assertTrue(ShieldRenderer.topY(x) > ShieldRenderer.botY(x), "the shield has height at every x");
		}
		assertTrue(ShieldRenderer.topY(0F) > ShieldRenderer.topY(ShieldRenderer.HW), "the top edge arches");
		assertEquals(ShieldRenderer.TIP, ShieldRenderer.botY(0F), 1e-4F, "the point");
		assertEquals(ShieldRenderer.WAIST, ShieldRenderer.botY(ShieldRenderer.HW), 1e-4F, "the sides come down to the waist");
		assertTrue(ShieldRenderer.face(0F, 0F) > ShieldRenderer.face(ShieldRenderer.HW * 0.9F, 0F), "the face stands highest in the middle");
	}

	@Test
	void weaveFitsInsideTheRim() {
		assertTrue(ShieldRenderer.EDGE < ShieldRenderer.HW, "the weft ends under the rim");
		assertTrue(-ShieldRenderer.X0 + ShieldRenderer.STRAND_R < ShieldRenderer.HW, "the outer warp strands are inside it");
		// the strands just touch where they cross: two tubes' centres are 2 * amplitude apart
		assertTrue(2F * ShieldRenderer.WEAVE_A >= 2F * ShieldRenderer.STRAND_R * 0.95F, "the weave's strands separate where they cross");
	}

	// ------------------------------------------------------------------ the timelines

	@Test
	void builtGrowsAndDrawsBack() {
		assertEquals(0F, ShieldMotion.built(0F), 1e-6F);
		assertEquals(1F, ShieldMotion.built(ShieldMotion.BUILD_TICKS), 1e-6F);
		assertEquals(1F, ShieldMotion.built(500F), 1e-6F, "a late viewer sees it built");
		float last = 0F;
		for (float t = 0F; t <= ShieldMotion.BUILD_TICKS; t += 0.5F) {
			float b = ShieldMotion.built(t);
			assertTrue(b >= last - 1e-6F, "growth never goes back");
			last = b;
		}
		// going out: the build backwards over OUT_TICKS from the full shield
		assertEquals(1F, ShieldMotion.built(-1F), 1e-6F);
		assertEquals(0F, ShieldMotion.built(-1F - ShieldMotion.OUT_TICKS), 1e-6F);
		assertEquals(0F, ShieldMotion.built(-1000F), 1e-6F);
		last = 1F;
		for (float t = 0F; t <= ShieldMotion.OUT_TICKS; t += 0.5F) {
			float b = ShieldMotion.built(-1F - t);
			assertTrue(b <= last + 1e-6F, "retraction never grows");
			last = b;
		}
		assertEquals(ArmProtocol.ACCESSORIES.indexOf(ArmProtocol.SHIELD) > 0 ? ShieldMotion.OUT_TICKS : -1F, dev.morphreborn.client.arm.ArmState.ACCESSORY_OUT_TICKS,
				"the shield draws back as long as the state keeps it going out");
	}

	@Test
	void raiseEasesUpAndDownAndAgreesWithinAFrame() {
		float t = 100F;
		assertEquals(0F, ShieldMotion.raise(1, t, 0), 1e-6F, "first seen lowered: lowered");
		assertEquals(0F, ShieldMotion.raise(1, t + 1F, 0), 1e-6F);
		// raised: up over RAISE_TICKS, the same answer for the same time
		float a = ShieldMotion.raise(1, t + 2F, 1);
		assertEquals(0F, a, 1e-6F, "the instant it flips it is still down");
		float mid = ShieldMotion.raise(1, t + 2F + ShieldMotion.RAISE_TICKS / 2F, 1);
		assertTrue(mid > 0.2F && mid < 0.8F, "halfway: " + mid);
		assertEquals(mid, ShieldMotion.raise(1, t + 2F + ShieldMotion.RAISE_TICKS / 2F, 1), 1e-6F, "asking twice for one moment gives one answer");
		assertEquals(1F, ShieldMotion.raise(1, t + 2F + ShieldMotion.RAISE_TICKS, 1), 1e-6F);
		// lowered again
		ShieldMotion.raise(1, t + 20F, 0);
		float down = ShieldMotion.raise(1, t + 20F + ShieldMotion.LOWER_TICKS / 2F, 0);
		assertTrue(down > 0.2F && down < 0.8F, "coming down: " + down);
		assertEquals(0F, ShieldMotion.raise(1, t + 20F + ShieldMotion.LOWER_TICKS, 0), 1e-6F);
		// a flip back mid-raise starts from where it was
		ShieldMotion.raise(2, t, 0);
		ShieldMotion.raise(2, t + 1F, 1);
		float up = ShieldMotion.raise(2, t + 3F, 1);
		float back = ShieldMotion.raise(2, t + 3F, 0);
		assertEquals(up, back, 1e-5F, "lowering from half way starts at half way");
		// another player's is its own
		assertEquals(1F, ShieldMotion.raise(3, t, 1), 1e-6F, "first seen raised: raised");
		// the table keeps working past its size
		for (int id = 100; id < 200; id++) ShieldMotion.raise(id, t, id & 1);
		assertEquals(1F, ShieldMotion.raise(199, t, 1), 1e-6F);
	}

	@Test
	void blockKicksAndRipples() {
		assertEquals(ArmDraw.NEVER, ShieldMotion.blockAge(5, 10F));
		ShieldMotion.blocked(5, 1000L, 1F, 77);
		assertEquals(0F, ShieldMotion.blockAge(5, 1000F), 1e-5F);
		assertEquals(3F, ShieldMotion.blockAge(5, 1003F), 1e-5F);
		assertEquals(ArmDraw.NEVER, ShieldMotion.blockAge(5, 1000F + ShieldMotion.BLOCK_FORGET + 1F), "forgotten");
		assertEquals(1F, ShieldMotion.blockPower(5), 1e-6F);
		assertEquals(77, ShieldMotion.blockSeed(5));
		// a later blow replaces it
		ShieldMotion.blocked(5, 1010L, 1.5F, 78);
		assertEquals(1.5F, ShieldMotion.blockPower(5), 1e-6F);
		// the kick comes in fast, goes out slowly, and is over when the blow is
		assertEquals(0F, ShieldMotion.recoil(0F), 1e-6F);
		assertTrue(ShieldMotion.recoil(0.5F) > 0.5F, "in fast");
		assertTrue(ShieldMotion.recoil(2F) < ShieldMotion.recoil(0.7F), "then out");
		assertEquals(0F, ShieldMotion.recoil(ShieldMotion.BLOCK_TICKS + 1F), 1e-6F);
		assertEquals(0F, ShieldMotion.recoil(ArmDraw.NEVER), 1e-6F);
		float max = 0F;
		for (float age = 0F; age <= ShieldMotion.BLOCK_TICKS; age += 0.25F) {
			for (float dist = 0F; dist <= 14F; dist += 0.5F) {
				float r = ShieldMotion.ripple(dist, age);
				assertTrue(Float.isFinite(r));
				max = Math.max(max, Math.abs(r));
			}
		}
		assertTrue(max > 0.3F && max < 1.1F, "the ripple is a dent, not a spike: " + max);
		assertEquals(0F, ShieldMotion.ripple(3F, ShieldMotion.BLOCK_TICKS + 1F), 1e-6F);
		assertEquals(0F, ShieldMotion.ripple(-1F, 2F), 1e-6F);
		// the clock's hourly wrap
		ShieldMotion.blocked(6, 72000L * 3 + 71999L, 1F, 1);
		assertEquals(2F, ShieldMotion.blockAge(6, 1F), 1e-3F, "a blow just before the wrap is two ticks old just after it");
	}

	// ------------------------------------------------------------------ what is drawn

	@Test
	void drawsNothingWhenOffOrUnbuilt() {
		for (boolean fp : new boolean[] {false, true}) {
			// a draw whose accessory set doesn't hold the shield
			ArmDraw off = new ArmDraw("", 2, 0, ArmDraw.CTX_TP, false, false, false, 100F, 7, ArmProtocol.STATE_NONE, 1F, "", 0F, 0F, false, 1F, ArmDraw.FULL, 0F,
					ArmDraw.NEVER, ArmDraw.NO_SPECIAL, ArmDraw.NEVER, ArmDraw.NEVER, new float[0], 0, false, 0F, 0F, true, true, false, null, 0F, -1F, 0F, null, 0xF000F0);
			assertEquals(0, quads(off, fp, null));
			// age 0: nothing yet; the end of the draw-back: nothing left
			assertEquals(0, quads(draw(0F, false, 0, fp ? ArmDraw.CTX_FP : ArmDraw.CTX_TP, false, 100F), fp, null), "not grown yet");
			assertEquals(0, quads(draw(-1F - ShieldMotion.OUT_TICKS, false, 0, fp ? ArmDraw.CTX_FP : ArmDraw.CTX_TP, false, 100F), fp, null), "drawn all the way back");
		}
	}

	@Test
	void quadBudgetInBothViews() {
		for (boolean raised : new boolean[] {false, true}) {
			int tp = 0, fp = 0;
			for (float t = 0F; t < 300F; t += 7.3F) {
				tp = Math.max(tp, quads(draw(500F, raised, 0, ArmDraw.CTX_TP, false, 100F + t), false, null));
				fp = Math.max(fp, quads(draw(500F, raised, 0, ArmDraw.CTX_FP, false, 100F + t), true, null));
			}
			System.out.printf("shield %s: third person %d quads, first person %d%n", raised ? "raised" : "lowered", tp, fp);
			assertTrue(tp >= 200, "the shield's detail is missing in third person: " + tp);
			// 900 with the closed rings; the animated tendril bands (six strands, their beads and three drips) cost about 140 more (2026-11-24)
			assertTrue(tp <= 960, "third person: " + tp + " quads, over 960");
			assertTrue(fp >= 200, "the shield's detail is missing in first person: " + fp);
			assertTrue(fp <= 900, "first person: " + fp + " quads, over 900");
		}
	}

	@Test
	void levelsOfDetailShed() {
		int lod0 = quads(draw(500F, true, 0, ArmDraw.CTX_TP, false, 100F), false, null);
		int lod1 = quads(draw(500F, true, 1, ArmDraw.CTX_TP, false, 100F), false, null);
		int lod2 = quads(draw(500F, true, 2, ArmDraw.CTX_TP, false, 100F), false, null);
		int lod3 = quads(draw(500F, true, 3, ArmDraw.CTX_TP, false, 100F), false, null);
		System.out.printf("shield LOD 0 %d, 1 %d, 2 %d, 3 %d%n", lod0, lod1, lod2, lod3);
		assertTrue(lod1 <= 330, "LOD 1: " + lod1);
		assertTrue(lod2 <= 40, "LOD 2: " + lod2);
		assertTrue(lod3 <= 16, "LOD 3: " + lod3);
		assertTrue(lod3 < lod2 && lod2 < lod1 && lod1 < lod0, "each level of detail costs less: " + lod0 + " " + lod1 + " " + lod2 + " " + lod3);
	}

	@Test
	void growsMonotonicallyAndEveryNumberIsFinite() {
		for (boolean fp : new boolean[] {false, true}) {
			int ctx = fp ? ArmDraw.CTX_FP : ArmDraw.CTX_TP;
			int last = 0;
			for (float age = 0F; age <= ShieldMotion.BUILD_TICKS + 2F; age += 1F) {
				int q = quads(draw(age, true, 0, ctx, false, 100F + age), fp, null, SOLID);
				assertTrue(q >= last - 2, "growing: " + q + " after " + last + " at age " + age);
				last = Math.max(last, q);
			}
			assertTrue(last > 200, "grown: " + last);
			// and back in
			long shrinking = Long.MAX_VALUE / 2;
			for (float t = 0F; t <= ShieldMotion.OUT_TICKS; t += 1F) {
				int q = quads(draw(-1F - t, false, 0, ctx, false, 100F + t), fp, null, SOLID);
				assertTrue(q <= shrinking + 2, "drawing back: " + q + " after " + shrinking);
				shrinking = Math.min(shrinking, q);
			}
		}
	}

	@Test
	void bothHandsAndAllStatesAreFine() {
		for (boolean toolLeft : new boolean[] {false, true}) {
			for (boolean raised : new boolean[] {false, true}) {
				for (int ctx : new int[] {ArmDraw.CTX_TP, ArmDraw.CTX_FP, ArmDraw.CTX_GUI}) {
					boolean fp = ctx == ArmDraw.CTX_FP;
					assertTrue(quads(draw(500F, raised, 0, ctx, toolLeft, 100F), fp, null) > 0);
				}
			}
		}
	}

	@Test
	void aBlowMovesTheShieldAndRingsItsWeave() {
		ShieldMotion.raise(7, 100F, 1);
		java.util.List<float[]> calm = new java.util.ArrayList<>(), struck = new java.util.ArrayList<>();
		int a = quads(draw(500F, true, 0, ArmDraw.CTX_TP, false, 100F), false, calm, SOLID);
		ShieldMotion.blocked(7, 100L, 1F, 12345);
		int b = quads(draw(500F, true, 0, ArmDraw.CTX_TP, false, 101.5F), false, struck, SOLID);
		assertEquals(a, b, "a blow adds nothing to draw");
		double moved = 0;
		for (int i = 0; i < Math.min(calm.size(), struck.size()); i++) {
			float[] p = calm.get(i), q = struck.get(i);
			moved += Math.abs(p[0] - q[0]) + Math.abs(p[1] - q[1]) + Math.abs(p[2] - q[2]);
		}
		assertTrue(moved > 1.0, "the blow moves the shield: " + moved);
	}

	@Test
	void restPointsAreWhereTheShieldIs() {
		org.joml.Vector3f p = new org.joml.Vector3f();
		ShieldRenderer r = new ShieldRenderer();
		assertTrue(r.offArm(), "on the off arm");
		assertEquals(ArmProtocol.SHIELD, r.id());
		assertTrue(r.restPoint(false, true, p) != null);
		float left = p.x;
		r.restPoint(false, false, p);
		assertEquals(-left, p.x, 1e-4F, "mirrored for the right arm");
		ShieldRenderer.restPointFirstPerson(true, p);
		float x = p.x;
		ShieldRenderer.restPointFirstPerson(false, p);
		assertEquals(-x, p.x, 1e-4F, "first person: the left hand on the left of the screen");
	}

	@Test
	void registeredAndLoadedByName() {
		assertTrue(ArmGeometry.accessoryBuilt(ArmProtocol.SHIELD), "ArmGeometry finds the shield's renderer by its class name");
		assertTrue(ArmGeometry.accessory(ArmProtocol.SHIELD) instanceof ShieldRenderer);
	}

	/** With SHIELD_PREVIEW_DIR set: pictures of both looks, raised and lowered, front and side and from above, and in first person. */
	@Test
	void writePreviewPictures() throws Exception {
		String dir = System.getenv("SHIELD_PREVIEW_DIR");
		if (dir == null || dir.isBlank()) return;
		ShieldPreview.write(java.nio.file.Path.of(dir));
	}
}
