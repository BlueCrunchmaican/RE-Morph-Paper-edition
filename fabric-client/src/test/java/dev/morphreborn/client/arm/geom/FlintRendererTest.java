package dev.morphreborn.client.arm.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The flint-and-steel accessory's sparking tendril (2026-11-16): its quad budget in both views and every level of detail, the build and
 * strike timelines (the idle beat, the server's strikes, the clack and the heat), the sparks, that it is its own look in its own place,
 * and that everything it writes is a finite number. Also, with {@code FLINT_PREVIEW_DIR} set, writes pictures of it (there is no way to
 * see the game from a test).
 */
class FlintRendererTest {
	private static final ArmLayer[] LAYERS = {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW};
	/** The draw's entity id (its seed). */
	static final int SEED = 7;

	@AfterEach
	void reset() {
		FlintMotion.clear();
	}

	/** A flint draw: {@code age} the accessory's draw age, {@code lod}, {@code ctx}, which hand holds the tool, the clock {@code time}. */
	static ArmDraw draw(float age, int lod, int ctx, boolean toolLeft, float time, String form) {
		float[] ages = new float[ArmProtocol.ACCESSORIES.size()];
		int index = ArmProtocol.accessoryIndex(ArmProtocol.FLINT);
		ages[index] = age;
		return new ArmDraw(form, 2, lod, ctx, toolLeft, false, false, time, SEED, form.isEmpty() ? ArmProtocol.STATE_NONE : ArmProtocol.STATE_FORMED, 1F, "", 0F, 0F, false, 1F,
				ArmDraw.FULL, 0F, ArmDraw.NEVER, ArmDraw.NO_SPECIAL, ArmDraw.NEVER, ArmDraw.NEVER, new float[0], 0, false, 0F, 0F, true, true, false, null, 0F, -1F, 0F, null,
				0xF000F0, 1F, 1 << index, 0, ages, false);
	}

	static ArmDraw draw(float age, int lod, int ctx, boolean toolLeft, float time) {
		return draw(age, lod, ctx, toolLeft, time, "");
	}

	/** A time at which the idle beat is not striking, so a test sees the striker at rest. */
	static float quietTime() {
		for (float t = 100F; t < 2000F; t += 1F) {
			if (FlintMotion.idleAge(SEED, t) == ArmDraw.NEVER && FlintMotion.idleAge(SEED, t - 20F) == ArmDraw.NEVER && FlintMotion.idleAge(SEED, t + 20F) == ArmDraw.NEVER) return t;
		}
		throw new AssertionError("no quiet time");
	}

	/** The quads {@code d} draws, all layers, in either view; the vertices (4 a quad, x y z) land in {@code into} when it is not null. */
	static int quads(ArmDraw d, boolean firstPerson, List<float[]> into) {
		return quads(d, firstPerson, into, new PoseStack().last(), LAYERS);
	}

	static int quads(ArmDraw d, boolean firstPerson, List<float[]> into, PoseStack.Pose pose, ArmLayer[] layers) {
		FlintRenderer r = new FlintRenderer();
		int total = 0;
		for (ArmLayer layer : layers) {
			if (layer == ArmLayer.GLOW && !d.glow()) continue;
			ArmBudgetTest.Recorder rec = new ArmBudgetTest.Recorder();
			rec.keep = into != null;
			SkinMesh m = SkinMesh.begin(pose, rec, 0xF000F0, 0);
			Loft.pulseFrom(null, 0F);
			if (firstPerson) r.emitFirstPerson(d, layer, m);
			else r.emitThirdPerson(d, layer, m);
			assertEquals(rec.quads(), m.quads(), "quads counted twice or lost");
			total += rec.quads();
			if (into != null) into.addAll(rec.kept);
		}
		return total;
	}

	/** A vertex consumer that only counts, so the allocation test sees the renderer's own and not the recorder's. */
	static final class Counter implements com.mojang.blaze3d.vertex.VertexConsumer {
		int vertices;

		@Override
		public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
			vertices++;
		}

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
			throw new UnsupportedOperationException("SkinMesh writes whole vertices");
		}

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) {
			return this;
		}

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer setColor(int color) {
			return this;
		}

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) {
			return this;
		}

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) {
			return this;
		}

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) {
			return this;
		}

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer setUv3(float u, float v) {
			return this;
		}

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) {
			return this;
		}

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer setLineWidth(float width) {
			return this;
		}
	}

	/** The box round the vertices: minX, minY, minZ, maxX, maxY, maxZ. */
	private static float[] bounds(List<float[]> vertices) {
		float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (float[] v : vertices) {
			for (int a = 0; a < 3; a++) {
				b[a] = Math.min(b[a], v[a]);
				b[a + 3] = Math.max(b[a + 3], v[a]);
			}
		}
		return b;
	}

	// ------------------------------------------------------------------ the timelines

	@Test
	void builtGrowsAndDrawsBack() {
		assertEquals(0F, FlintMotion.built(0F), 1e-6F);
		assertEquals(1F, FlintMotion.built(FlintMotion.BUILD_TICKS), 1e-6F);
		assertEquals(1F, FlintMotion.built(500F), 1e-6F, "a late viewer sees it built");
		float last = 0F;
		for (float t = 0F; t <= FlintMotion.BUILD_TICKS; t += 0.5F) {
			float b = FlintMotion.built(t);
			assertTrue(b >= last - 1e-6F, "growth never goes back");
			last = b;
		}
		assertEquals(1F, FlintMotion.built(-1F), 1e-6F);
		assertEquals(0F, FlintMotion.built(-1F - FlintMotion.OUT_TICKS), 1e-6F);
		assertEquals(0F, FlintMotion.built(-1000F), 1e-6F);
		last = 1F;
		for (float t = 0F; t <= FlintMotion.OUT_TICKS; t += 0.5F) {
			float b = FlintMotion.built(-1F - t);
			assertTrue(b <= last + 1e-6F, "retraction never grows");
			last = b;
		}
		assertEquals(FlintMotion.OUT_TICKS, dev.morphreborn.client.arm.ArmState.ACCESSORY_OUT_TICKS, "it draws back as long as the state keeps it going out");
	}

	@Test
	void idleStrikesComeNowAndThenOnEachPlayersOwnBeat() {
		int starts = 0;
		float lastStart = -1F, minGap = Float.MAX_VALUE;
		for (float t = 0F; t < 6000F; t += 0.5F) {
			float age = FlintMotion.idleAge(SEED, t);
			assertTrue(age == ArmDraw.NEVER || age >= 0F && age <= FlintMotion.SPARK_TICKS, "an idle strike's age is within its sparks' life: " + age);
			if (age >= 0F && age < 0.5F && age != ArmDraw.NEVER) {
				starts++;
				if (lastStart >= 0F) minGap = Math.min(minGap, t - lastStart);
				lastStart = t;
			}
		}
		// one chance every 60 ticks at 70%: about 70 in 6000 ticks, "now and then" and not a flicker
		assertTrue(starts >= 40 && starts <= 100, "strikes in five minutes: " + starts);
		assertTrue(minGap >= FlintMotion.SPARK_TICKS, "one is over before the next: " + minGap);
		// each player has their own, and the same time gives the same answer
		boolean differs = false;
		for (float t = 0F; t < 600F && !differs; t += 1F) differs = (FlintMotion.idleAge(SEED, t) == ArmDraw.NEVER) != (FlintMotion.idleAge(SEED + 1, t) == ArmDraw.NEVER);
		assertTrue(differs, "two players' strikes are not in step");
		assertEquals(FlintMotion.idleAge(SEED, 777.25F), FlintMotion.idleAge(SEED, 777.25F));
		// a clock that wraps to near 0, and nonsense, break nothing
		assertEquals(ArmDraw.NEVER, FlintMotion.idleAge(SEED, -5F));
		assertEquals(ArmDraw.NEVER, FlintMotion.idleAge(SEED, Float.NaN));
		assertTrue(Float.isFinite(FlintMotion.idleAge(SEED, 71999.5F)) && Float.isFinite(FlintMotion.idleAge(SEED, 0.5F)));
	}

	@Test
	void theKnucklesClackShutAndSpringOpenWithAHeatThatCools() {
		assertEquals(0F, FlintMotion.clack(-1F), 1e-6F);
		assertEquals(0F, FlintMotion.clack(0F), 1e-6F, "open at the start");
		assertEquals(1F, FlintMotion.clack(FlintMotion.SHUT_TICKS), 1e-5F, "shut when the strike lands");
		assertEquals(0F, FlintMotion.clack(FlintMotion.CLACK_TICKS), 1e-6F);
		assertEquals(0F, FlintMotion.clack(500F), 1e-6F);
		float last = 0F;
		for (float a = 0F; a <= FlintMotion.SHUT_TICKS; a += 0.05F) {
			float c = FlintMotion.clack(a);
			assertTrue(c >= last - 1e-6F, "shutting never opens");
			last = c;
		}
		float max = 0F;
		for (float a = 0F; a <= FlintMotion.CLACK_TICKS; a += 0.05F) {
			float c = FlintMotion.clack(a);
			assertTrue(c >= -1e-6F && c <= 1F + 1e-5F, "between open and shut: " + c + " at " + a);
			max = Math.max(max, c);
		}
		assertTrue(max <= 1.001F, "it does not shut past shut");
		// the heat: hot at once, cooling to the idle glow, and the idle glow before and after
		assertEquals(0.3F, FlintMotion.heat(ArmDraw.NEVER, 0.3F), 1e-6F);
		assertEquals(1F, FlintMotion.heat(0F, 0.3F), 1e-6F);
		float prev = 1F;
		for (float a = 0F; a < FlintMotion.SPARK_TICKS; a += 0.25F) {
			float h = FlintMotion.heat(a, 0.3F);
			assertTrue(h <= prev + 1e-6F && h >= 0.3F - 1e-6F, "it cools toward the idle glow: " + h);
			prev = h;
		}
		// the sparks: each lives a different share of the strike, and none is alive before it or after its life
		assertTrue(FlintMotion.spark(-1F, 0, 5) > 1F);
		assertEquals(0F, FlintMotion.spark(0F, 0, 5), 1e-6F);
		boolean unequal = false;
		for (int i = 0; i < 8; i++) {
			assertTrue(FlintMotion.spark(FlintMotion.SPARK_TICKS, i, 5) >= 1F, "every spark is out by the end of the strike's life");
			unequal |= FlintMotion.spark(5F, i, 5) != FlintMotion.spark(5F, 0, 5);
		}
		assertTrue(unequal, "sparks are not alike");
	}

	@Test
	void strikesTheServerAsksForAreRememberedThenForgottenAndEachPlayerKeepsTheirOwn() {
		assertEquals(ArmDraw.NEVER, FlintMotion.forcedAge(5, 100F));
		assertEquals(0F, FlintMotion.forcedPower(5));
		FlintMotion.struck(5, 100L, FlintMotion.POWER_IGNITE, 4242);
		FlintMotion.struck(6, 90L, FlintMotion.POWER_SPARK, 1);
		assertEquals(0F, FlintMotion.forcedAge(5, 100F), 1e-6F);
		assertEquals(3F, FlintMotion.forcedAge(5, 103F), 1e-6F);
		assertEquals(FlintMotion.POWER_IGNITE, FlintMotion.forcedPower(5));
		assertEquals(4242, FlintMotion.forcedSeed(5));
		assertEquals(FlintMotion.POWER_SPARK, FlintMotion.forcedPower(6));
		assertEquals(ArmDraw.NEVER, FlintMotion.forcedAge(5, 100F + FlintMotion.FORGET_TICKS), "forgotten after a while");
		// a later strike replaces the earlier one
		FlintMotion.struck(5, 200L, FlintMotion.POWER_SPARK, 9);
		assertEquals(0F, FlintMotion.forcedAge(5, 200F), 1e-6F);
		assertEquals(FlintMotion.POWER_SPARK, FlintMotion.forcedPower(5));
		// the hourly clock wraps: a strike just before the wrap is still fresh just after it
		FlintMotion.struck(8, 71999L, FlintMotion.POWER_SPARK, 2);
		assertEquals(2F, FlintMotion.forcedAge(8, 1F), 1e-3F);
		// more players than the table holds: the oldest is the one lost
		for (int id = 100; id < 140; id++) FlintMotion.struck(id, 300L + id, FlintMotion.POWER_SPARK, id);
		assertEquals(FlintMotion.POWER_SPARK, FlintMotion.forcedPower(139));
		FlintMotion.clear();
		assertEquals(ArmDraw.NEVER, FlintMotion.forcedAge(139, 440F));
	}

	// ------------------------------------------------------------------ the drawing

	@Test
	void drawsNothingWhenOffOrNotYetBuiltOrNotItsLayer() {
		ArmDraw off = new ArmDraw("", 2, 0, ArmDraw.CTX_TP, false, false, false, 100F, SEED, ArmProtocol.STATE_NONE, 1F, "", 0F, 0F, false, 1F, ArmDraw.FULL, 0F, ArmDraw.NEVER,
				ArmDraw.NO_SPECIAL, ArmDraw.NEVER, ArmDraw.NEVER, new float[0], 0, false, 0F, 0F, true, true, false, null, 0F, -1F, 0F, null, 0xF000F0);
		assertEquals(0, quads(off, false, null), "no accessory, no striker");
		assertEquals(0, quads(draw(0F, 0, ArmDraw.CTX_TP, false, 100F), false, null), "the first tick of the build is nothing yet");
		assertEquals(0, quads(draw(-1F - FlintMotion.OUT_TICKS, 0, ArmDraw.CTX_TP, false, 100F), false, null), "drawn all the way back");
		FlintRenderer r = new FlintRenderer();
		ArmBudgetTest.Recorder rec = new ArmBudgetTest.Recorder();
		r.emitThirdPerson(draw(500F, 0, ArmDraw.CTX_TP, false, 100F), ArmLayer.SENSE, SkinMesh.begin(new PoseStack().last(), rec, 0xF000F0, 0));
		assertEquals(0, rec.quads(), "the sense layer is not drawn");
		assertEquals(ArmProtocol.FLINT, r.id());
		assertFalse(r.offArm(), "on the arm that holds the tool");
	}

	@Test
	void quadBudgetInBothViewsAndEveryLevelOfDetail() {
		int worstTp = 0, worstFp = 0;
		for (float t = 0F; t < 900F; t += 1.7F) {
			for (boolean left : new boolean[] {false, true}) {
				worstTp = Math.max(worstTp, quads(draw(500F, 0, ArmDraw.CTX_TP, left, t), false, null));
				worstFp = Math.max(worstFp, quads(draw(500F, 0, ArmDraw.CTX_FP, left, t), true, null));
			}
		}
		// the hardest strike there is: an ignite, at its worst moment for sparks
		for (float a = 0F; a < FlintMotion.SPARK_TICKS; a += 0.5F) {
			FlintMotion.struck(SEED, 1000L, FlintMotion.POWER_IGNITE, 4242);
			worstTp = Math.max(worstTp, quads(draw(500F, 0, ArmDraw.CTX_TP, false, 1000F + a), false, null));
			worstFp = Math.max(worstFp, quads(draw(500F, 0, ArmDraw.CTX_FP, false, 1000F + a), true, null));
		}
		System.out.printf("flint striker: third person %d, first person %d%n", worstTp, worstFp);
		assertTrue(worstTp <= 450, "third person: " + worstTp + " quads, over 450");
		assertTrue(worstFp <= 450, "first person: " + worstFp + " quads, over 450");
		assertTrue(worstTp >= 120, "third person: only " + worstTp + " quads; the detail is missing");
		int lod1 = 0, lod2 = 0, lod3 = 0, icon = 0;
		for (float t = 0F; t < 900F; t += 1.7F) {
			lod1 = Math.max(lod1, quads(draw(500F, 1, ArmDraw.CTX_TP, false, t), false, null));
			lod2 = Math.max(lod2, quads(draw(500F, 2, ArmDraw.CTX_TP, false, t), false, null));
			lod3 = Math.max(lod3, quads(draw(500F, 3, ArmDraw.CTX_TP, false, t), false, null));
			icon = Math.max(icon, quads(draw(500F, ArmDraw.LOD_ICON, ArmDraw.CTX_GUI, false, t), false, null));
		}
		System.out.printf("flint striker: LOD1 %d, LOD2 %d, LOD3 %d, icon %d%n", lod1, lod2, lod3, icon);
		assertTrue(lod1 <= 140, "LOD 1: " + lod1);
		assertTrue(lod2 <= 40, "LOD 2: " + lod2);
		assertTrue(lod3 <= 16, "LOD 3: " + lod3);
		assertTrue(icon <= 140, "icon: " + icon);
		assertTrue(lod3 < lod2 && lod2 < lod1 && lod1 < worstTp, "each level of detail should cost less than the one before");
	}

	@Test
	void growsOutAndDrawsBackWithoutEverGrowingWhileDrawingBack() {
		float t = quietTime();
		int last = 0;
		for (float age = 0F; age <= FlintMotion.BUILD_TICKS + 2F; age += 1F) {
			int q = quads(draw(age, 0, ArmDraw.CTX_TP, false, t), false, null);
			assertTrue(q >= last - 40, "growing: " + q + " after " + last + " at age " + age);
			last = Math.max(last, q);
		}
		assertTrue(last > 100, "built: " + last);
		int shrinking = last;
		for (float a = 0F; a <= FlintMotion.OUT_TICKS; a += 1F) {
			int q = quads(draw(-1F - a, 0, ArmDraw.CTX_TP, false, t), false, null);
			assertTrue(q <= shrinking + 40, "drawing back: " + q + " after " + shrinking);
			shrinking = Math.max(0, Math.min(shrinking, q));
		}
		assertEquals(0, shrinking);
	}

	@Test
	void everyVertexIsFiniteAcrossViewsHandsStrikesAndTime() {
		for (float t = 0F; t < 400F; t += 5.3F) {
			if (t > 100F && t < 120F) FlintMotion.struck(SEED, (long) t, FlintMotion.POWER_IGNITE, (int) t);
			for (boolean left : new boolean[] {false, true}) {
				for (String form : new String[] {"", ArmProtocol.KATANA}) {
					for (int lod = 0; lod <= 3; lod++) {
						List<float[]> kept = new ArrayList<>();
						// the Recorder checks every value is finite and every normal unit length
						quads(draw(500F, lod, ArmDraw.CTX_TP, left, t, form), false, kept);
						quads(draw(500F, lod, ArmDraw.CTX_FP, left, t, form), true, kept);
						assertFalse(kept.isEmpty());
					}
				}
			}
		}
		// a pose that cannot be read (a zero matrix) leaves the sparks falling the arm's own way and writes numbers all the same
		FlintMotion.struck(SEED, 500L, FlintMotion.POWER_SPARK, 3);
		PoseStack stack = new PoseStack();
		stack.last().pose().zero();
		quads(draw(500F, 0, ArmDraw.CTX_TP, false, 502F), false, new ArrayList<>(), stack.last(), LAYERS);
	}

	@Test
	void aStrikeThrowsSparksAndAnIgniteThrowsMore() {
		float t = quietTime();
		int rest = quads(draw(500F, 0, ArmDraw.CTX_TP, false, t), false, null);
		FlintMotion.struck(SEED, 1000L, FlintMotion.POWER_SPARK, 4242);
		int spark = quads(draw(500F, 0, ArmDraw.CTX_TP, false, 1002F), false, null);
		FlintMotion.struck(SEED, 1000L, FlintMotion.POWER_IGNITE, 4242);
		int ignite = quads(draw(500F, 0, ArmDraw.CTX_TP, false, 1002F), false, null);
		assertTrue(spark > rest + 20, "a strike adds sparks: " + rest + " -> " + spark);
		assertTrue(ignite > spark, "an ignite's burst is bigger: " + spark + " -> " + ignite);
		// and they are gone again by the end of the strike's life
		FlintMotion.struck(SEED, 1000L, FlintMotion.POWER_IGNITE, 4242);
		int after = quads(draw(500F, 0, ArmDraw.CTX_TP, false, 1000F + FlintMotion.SPARK_TICKS + 1F), false, null);
		assertTrue(after < ignite - 20, "the sparks go out: " + ignite + " -> " + after);
		// still growing: no strike yet
		FlintMotion.struck(SEED, 1000L, FlintMotion.POWER_IGNITE, 4242);
		int growing = quads(draw(4F, 0, ArmDraw.CTX_TP, false, 1002F), false, null);
		FlintMotion.clear();
		int growingQuiet = quads(draw(4F, 0, ArmDraw.CTX_TP, false, 1002F), false, null);
		assertEquals(growingQuiet, growing, "no sparks while it is still growing out");
	}

	@Test
	void sparksAreLightSoOnlyTheLitLayerDrawsThem() {
		float t = quietTime();
		FlintMotion.struck(SEED, 1000L, FlintMotion.POWER_IGNITE, 4242);
		ArmDraw struck = draw(500F, 0, ArmDraw.CTX_TP, false, 1002F);
		// the black layer is the same with or without a strike except for the knuckles moving; sparks add faces to the glow layer only
		int blackStruck = quads(struck, false, null, new PoseStack().last(), new ArmLayer[] {ArmLayer.BLACK});
		FlintMotion.clear();
		int blackQuiet = quads(draw(500F, 0, ArmDraw.CTX_TP, false, t), false, null, new PoseStack().last(), new ArmLayer[] {ArmLayer.BLACK});
		assertEquals(blackQuiet, blackStruck, "no black spark");
		FlintMotion.struck(SEED, 1000L, FlintMotion.POWER_IGNITE, 4242);
		int glowStruck = quads(struck, false, null, new PoseStack().last(), new ArmLayer[] {ArmLayer.GLOW});
		FlintMotion.clear();
		int glowQuiet = quads(draw(500F, 0, ArmDraw.CTX_TP, false, t), false, null, new PoseStack().last(), new ArmLayer[] {ArmLayer.GLOW});
		assertTrue(glowStruck > glowQuiet + 20, "the sparks are in the glow layer: " + glowQuiet + " -> " + glowStruck);
		// with glow off they are the red layer's instead
		ArmDraw noGlow = new ArmDraw("", 2, 0, ArmDraw.CTX_TP, false, false, false, 1002F, SEED, ArmProtocol.STATE_NONE, 1F, "", 0F, 0F, false, 1F, ArmDraw.FULL, 0F, ArmDraw.NEVER,
				ArmDraw.NO_SPECIAL, ArmDraw.NEVER, ArmDraw.NEVER, new float[0], 0, false, 0F, 0F, false, true, false, null, 0F, -1F, 0F, null, 0xF000F0, 1F,
				1 << ArmProtocol.accessoryIndex(ArmProtocol.FLINT), 0, draw(500F, 0, 1, false, 0F).accessoryAges(), false);
		FlintMotion.struck(SEED, 1000L, FlintMotion.POWER_IGNITE, 4242);
		int redStruck = quads(noGlow, false, null, new PoseStack().last(), new ArmLayer[] {ArmLayer.RED});
		FlintMotion.clear();
		int redQuiet = quads(noGlow, false, null, new PoseStack().last(), new ArmLayer[] {ArmLayer.RED});
		assertTrue(redStruck > redQuiet + 20, "glow off: the red layer takes the sparks: " + redQuiet + " -> " + redStruck);
	}

	@Test
	void sparksFallWithTheWorldsGravityNotTheArms() {
		Vector3f up = new Vector3f();
		// the pose the sheath layer draws with: arm space is y down, so a pose that flips y (and z, a turn about x) puts the world's up on -Y
		Matrix4f turned = new Matrix4f().scale(1F / 16F).rotateX((float) Math.PI);
		PoseStack stack = new PoseStack();
		stack.last().pose().set(turned);
		assertEquals(-1F, FlintRenderer.worldUp(stack.last(), false, up).y, 1e-5F, "the arm hangs upside down: up is -Y in its space");
		stack.last().pose().set(new Matrix4f().scale(1F / 16F));
		assertEquals(1F, FlintRenderer.worldUp(stack.last(), false, up).y, 1e-5F);
		// tilted: still a unit vector, and the world's up brought back out through the pose is up
		Matrix4f tilt = new Matrix4f().scale(1F / 16F).rotateY(0.9F).rotateX(-0.6F).rotateZ(0.3F);
		stack.last().pose().set(tilt);
		FlintRenderer.worldUp(stack.last(), false, up);
		assertEquals(1F, up.length(), 1e-5F);
		Vector3f world = tilt.transformDirection(up, new Vector3f()).normalize();
		assertEquals(0F, world.x, 1e-4F);
		assertEquals(1F, world.y, 1e-4F);
		assertEquals(0F, world.z, 1e-4F);
		// nothing to read: the space's own up, which is the view's +Y in the hand pass and the arm's -Y otherwise
		assertEquals(-1F, FlintRenderer.worldUp(null, false, up).y, 1e-6F);
		assertEquals(1F, FlintRenderer.worldUp(null, true, up).y, 1e-6F);
		stack.last().pose().zero();
		assertEquals(-1F, FlintRenderer.worldUp(stack.last(), false, up).y, 1e-6F, "an unusable pose");
	}

	@Test
	void inFirstPersonTheWorldsUpComesFromTheCamerasAxesNotTheViewsPlusY() {
		Vector3f up = new Vector3f();
		// level: the camera's up is world up, left and forward are horizontal: the view's +Y
		FlintRenderer.viewUp(0F, 1F, 0F, up);
		assertEquals(0F, up.x, 1e-6F);
		assertEquals(1F, up.y, 1e-6F);
		assertEquals(0F, up.z, 1e-6F);
		// looking 40 degrees down: forward = (0, -sin 40, ...), the camera's up tilts forward (y = cos 40): world up is (0, cos, +sin) in view space
		// (x right, y up, z toward the viewer): gravity pulls toward -z as well, into the screen, not straight down the screen
		float p = (float) Math.toRadians(40);
		FlintRenderer.viewUp(0F, (float) Math.cos(p), (float) -Math.sin(p), up);
		assertEquals(1F, up.length(), 1e-5F);
		assertEquals(Math.cos(p), up.y, 1e-5F);
		assertEquals(Math.sin(p), up.z, 1e-5F, "looking down, the world's up leans toward the viewer's side of the view");
		// looking up: the other way
		FlintRenderer.viewUp(0F, (float) Math.cos(p), (float) Math.sin(p), up);
		assertEquals(-Math.sin(p), up.z, 1e-5F);
		// rolled: world up leaves the screen's y toward its x
		FlintRenderer.viewUp(-0.5F, 0.8F, 0F, up);
		assertTrue(up.x > 0.4F && up.y > 0.7F);
		assertEquals(1F, up.length(), 1e-5F);
		// a degenerate camera falls back on the view's +Y
		assertEquals(1F, FlintRenderer.viewUp(0F, 0F, 0F, up).y, 1e-6F);
		assertEquals(1F, FlintRenderer.viewUp(Float.NaN, 0F, 0F, up).y, 1e-6F);
		// the draw itself never needs a game: with none it uses the view's +Y, as before
		List<float[]> quads = new ArrayList<>();
		assertTrue(quads(draw(500F, 0, ArmDraw.CTX_FP, false, quietTime()), true, quads) > 0);
	}

	@Test
	void theHotFacesNeverReachIntoTheOtherKnuckleWhenTheJawsShut() {
		float k = 1F;
		// open: the natural thickness
		assertEquals(0.28F, FlintRenderer.hotThick(FlintRenderer.GAP_OPEN * k, k), 1e-6F);
		// shut: both faces together stop short of the middle (each starts 0.02 k off its own knuckle and may reach 0.01 k from the middle)
		float shut = FlintRenderer.GAP_SHUT * k;
		float thick = FlintRenderer.hotThick(shut, k);
		assertTrue(thick > 0F && thick <= 0.5F * shut - 0.02F * k - 0.01F * k + 1e-6F, "the face ends before the middle: " + thick);
		for (float gap = FlintRenderer.GAP_OPEN; gap >= FlintRenderer.GAP_SHUT; gap -= 0.01F) {
			float t = FlintRenderer.hotThick(gap * k, k);
			assertTrue(t > 0F && t <= 0.28F * k + 1e-6F);
			assertTrue(0.5F * gap * k - 0.02F * k - t >= 0.01F * k - 1e-5F, "at gap " + gap + " the face stays on its own side of the middle");
		}
		// it scales with the knuckles
		assertEquals(FlintRenderer.hotThick(0.5F, 0.5F) * 2F, FlintRenderer.hotThick(1F, 1F), 1e-6F);
	}

	@Test
	void leftHandedMirrorsTheStrikerAcrossTheBodyAndItRunsDownTheOutsideOfTheArm() {
		float t = quietTime();
		List<float[]> r = new ArrayList<>(), l = new ArrayList<>();
		quads(draw(500F, 0, ArmDraw.CTX_TP, false, t), false, r);
		quads(draw(500F, 0, ArmDraw.CTX_TP, true, t), false, l);
		float[] right = bounds(r), left = bounds(l);
		float rightMid = (right[0] + right[3]) / 2F, leftMid = (left[0] + left[3]) / 2F;
		assertTrue(rightMid < -1F, "the right arm's striker is on the -X side: " + rightMid);
		assertTrue(leftMid > 1F, "the left arm's on the +X side: " + leftMid);
		assertEquals(-rightMid, leftMid, 0.3F, "mirrored about the body's middle");
		// down the forearm's outside to the hand, nowhere near the shoulder's top where the spyglass is, nor in front of the blade where the lantern hangs
		assertTrue(right[4] > 9F, "it reaches the hand's end: " + right[4]);
		assertTrue(right[1] > 1F, "and starts below the shoulder: " + right[1]);
		assertTrue(right[2] > -7F, "and does not reach far out in front of the tool (the lantern hangs at -11): " + right[2]);
	}

	@Test
	void theKnucklesShutOnAStrike() {
		float t = quietTime();
		// the knuckles' hot faces are the glow layer's boxes nearest the tip: with them shut the glow layer's vertical spread (the jaw axis
		// runs mostly along arm space's y) is smaller than open
		List<float[]> open = new ArrayList<>(), shutNow = new ArrayList<>();
		quads(draw(500F, 0, ArmDraw.CTX_TP, false, t), false, open, new PoseStack().last(), new ArmLayer[] {ArmLayer.BLACK});
		FlintMotion.struck(SEED, 1000L, FlintMotion.POWER_SPARK, 4242);
		quads(draw(500F, 0, ArmDraw.CTX_TP, false, 1000F + FlintMotion.SHUT_TICKS), false, shutNow, new PoseStack().last(), new ArmLayer[] {ArmLayer.BLACK});
		float[] a = bounds(open), b = bounds(shutNow);
		float openSpan = (a[3] - a[0]) + (a[4] - a[1]) + (a[5] - a[2]), shutSpan = (b[3] - b[0]) + (b[4] - b[1]) + (b[5] - b[2]);
		assertNotEquals(openSpan, shutSpan, "the knuckles moved");
		assertTrue(shutSpan < openSpan - 0.3F, "shut they take up less room: " + openSpan + " -> " + shutSpan);
	}

	@Test
	void firstPersonComesInFromTheLowerEdgeAndLeftHandedMirrors() {
		float t = quietTime();
		List<float[]> r = new ArrayList<>(), l = new ArrayList<>();
		quads(draw(500F, 0, ArmDraw.CTX_FP, false, t), true, r);
		quads(draw(500F, 0, ArmDraw.CTX_FP, true, t), true, l);
		float[] right = bounds(r), left = bounds(l);
		assertTrue(right[3] > 10F && right[0] > 5F, "on the right of the view for a right-handed player: " + right[0] + ".." + right[3]);
		assertTrue(left[0] < -10F && left[3] < -5F, "and the left for a left-handed one");
		assertTrue(right[4] < 0F, "low in the view (y up)");
		assertTrue(right[5] < -3F, "in front of the camera");
		Vector3f at = FlintRenderer.restPointFirstPerson(false, new Vector3f()), mirrored = FlintRenderer.restPointFirstPerson(true, new Vector3f());
		assertEquals(-at.x, mirrored.x, 1e-5F);
		assertEquals(at.y, mirrored.y, 1e-5F);
	}

	/** Allocation-free per frame (my rule for the arm's geometry): after warming up, a draw of every layer makes no garbage. */
	@Test
	void aDrawAllocatesNothing() {
		java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
		if (!(bean instanceof com.sun.management.ThreadMXBean sun) || !sun.isThreadAllocatedMemorySupported()) return;
		FlintRenderer r = new FlintRenderer();
		PoseStack.Pose pose = new PoseStack().last();
		Counter counter = new Counter();
		// a strike is on, so the sparks are drawn too
		FlintMotion.struck(SEED, 1000L, FlintMotion.POWER_IGNITE, 4242);
		ArmDraw tp = draw(500F, 0, ArmDraw.CTX_TP, false, 1002F, ArmProtocol.KATANA), fp = draw(500F, 0, ArmDraw.CTX_FP, false, 1002F);
		for (int i = 0; i < 300; i++) drawAll(r, tp, fp, pose, counter);
		long id = Thread.currentThread().threadId();
		long before = sun.getThreadAllocatedBytes(id);
		for (int i = 0; i < 100; i++) drawAll(r, tp, fp, pose, counter);
		long used = sun.getThreadAllocatedBytes(id) - before;
		assertTrue(used < 4096, "a draw allocated " + used + " bytes over 100 draws (should be none per frame)");
		assertTrue(counter.vertices > 0);
	}

	private static void drawAll(FlintRenderer r, ArmDraw tp, ArmDraw fp, PoseStack.Pose pose, Counter counter) {
		for (ArmLayer layer : LAYERS) {
			r.emitThirdPerson(tp, layer, SkinMesh.begin(pose, counter, 0xF000F0, 0));
			r.emitFirstPerson(fp, layer, SkinMesh.begin(pose, counter, 0xF000F0, 0));
		}
	}

	@Test
	void geometryIsFoundByTheRegistryAndRestPointIsAtTheHand() {
		assertTrue(ArmGeometry.accessory(ArmProtocol.FLINT) instanceof FlintRenderer);
		Vector3f p = new FlintRenderer().restPoint(false, false, new Vector3f());
		assertTrue(p != null && p.y > 8F, "down at the hand");
		assertTrue(p.x < -2F, "on the right arm's outer side");
		Vector3f q = new FlintRenderer().restPoint(false, true, new Vector3f());
		assertEquals(-p.x, q.x, 1e-4F, "mirrored about the body's middle");
		Vector3f slim = new FlintRenderer().restPoint(true, false, new Vector3f());
		assertTrue(slim.x > p.x, "a slim arm's outer face is nearer the body");
	}

	/** With FLINT_PREVIEW_DIR set: pictures of the striker resting and striking, from the front, side and above, and in first person. */
	@Test
	void writePreviewPictures() throws Exception {
		String dir = System.getenv("FLINT_PREVIEW_DIR");
		if (dir == null || dir.isBlank()) return;
		FlintPreview.write(java.nio.file.Path.of(dir));
	}
}
