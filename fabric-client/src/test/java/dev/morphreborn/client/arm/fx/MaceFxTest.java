package dev.morphreborn.client.arm.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.SkinMesh;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * The mace's Skyward Leap (FX_MACE, 2026-11-16): the contract's numbers, the timelines (the tendrils' spring and recoil, the shock ring's
 * race, the cracks, the shards' flight), and the pieces' geometry (a fan of tendrils, a ring with a crown of teeth, a burst of shards,
 * all distinct by tier).
 */
class MaceFxTest {
	/** A vertex consumer that checks every value is a number and every normal unit length, and keeps the positions. */
	private static final class Recorder implements VertexConsumer {
		int vertices;
		final List<float[]> kept = new ArrayList<>();

		@Override
		public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
			for (float f : new float[] {x, y, z, u, v, nx, ny, nz}) assertTrue(Float.isFinite(f), "a vertex value isn't a number");
			assertEquals(1F, (float) Math.sqrt(nx * nx + ny * ny + nz * nz), 1e-3F, "normal length");
			vertices++;
			kept.add(new float[] {x, y, z});
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			throw new UnsupportedOperationException();
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			return this;
		}

		@Override
		public VertexConsumer setColor(int color) {
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv3(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			return this;
		}

		int quads() {
			return vertices / 4;
		}
	}

	private static Recorder emit(TendrilFx.Geometry g) {
		Recorder r = new Recorder();
		g.emit(SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
		return r;
	}

	private static float topOf(Recorder r) {
		float top = -Float.MAX_VALUE;
		for (float[] v : r.kept) top = Math.max(top, v[1]);
		return top;
	}

	private static float reachOf(Recorder r) {
		float w = 0F;
		for (float[] v : r.kept) w = Math.max(w, (float) Math.sqrt(v[0] * v[0] + v[2] * v[2]));
		return w;
	}

	private static MaceFx.Fan fan(ArmLayer layer, int tier, float age, float lenPx, int n, float eaten, float grown) {
		MaceFx.Fan f = new MaceFx.Fan();
		f.reset(layer, 100F, 7, age, tier);
		f.w0 = 2.6F;
		f.w1 = 1F;
		for (int k = 0; k < n; k++) {
			float a = 6.28F * k / n;
			f.add(5F * (float) Math.cos(a), 0.4F, 5F * (float) Math.sin(a), -5F * (float) Math.cos(a), lenPx, -5F * (float) Math.sin(a), 3F, eaten, grown, k);
		}
		return f;
	}

	private static MaceFx.Ring ring(ArmLayer layer, int tier, float age, float max) {
		MaceFx.Ring r = new MaceFx.Ring();
		r.reset(100F, 7, tier, age, MaceFx.RING_LIFE, MaceFx.shockRadius(age, max), max, true);
		return layer == ArmLayer.BLACK ? r : r.copyFor(layer);
	}

	private static MaceFx.Shards shards(ArmLayer layer, int tier, float age, int count) {
		MaceFx.Shards s = new MaceFx.Shards();
		s.reset(100F, 7, tier, age, count, 1F, 6F, false);
		return layer == ArmLayer.BLACK ? s : s.copyFor(layer);
	}

	@Test
	void contractIsTheServersFxKind() {
		assertEquals(16, ArmProtocol.FX_MACE);
		assertEquals(21, ArmProtocol.FX_KINDS);
		assertEquals("mace", ArmProtocol.fxName(ArmProtocol.FX_MACE));
		assertEquals(1, MaceFx.LEAP);
		assertEquals(2, MaceFx.LANDING);
		assertEquals(ArmProtocol.FX_MACE, new MaceFx().kinds()[0]);
		assertEquals(1, new MaceFx().kinds().length);
	}

	@Test
	void handlerIsRegisteredAndLoadsByName() throws Exception {
		assertTrue(ArmFx.HANDLERS.contains(MaceFx.class.getName()));
		assertTrue(FxHandler.class.isAssignableFrom(Class.forName(MaceFx.class.getName())));
	}

	@Test
	void anEventWithoutAPlaceOrAnUnknownMoveIsDropped() {
		MaceFx fx = new MaceFx();
		fx.start(new ArmFxEvent(1, ArmProtocol.FX_MACE, 2, 10, 5, new int[0], new long[0], MaceFx.LEAP, 0L));
		fx.start(new ArmFxEvent(1, ArmProtocol.FX_MACE, 2, 10, 5, new int[0], new long[] {BlockPos.asLong(1, 2, 3)}, 9, 0L));
		fx.start(new ArmFxEvent(1, ArmProtocol.FX_MACE, 2, 10, 5, new int[0], new long[] {BlockPos.asLong(1, 2, 3)}, MaceFx.LEAP, 0L));
		fx.start(new ArmFxEvent(1, ArmProtocol.FX_MACE, 99, -5, 5, new int[] {3, 4, 5, 6, 7, 8}, new long[] {BlockPos.asLong(1, 2, 3)}, MaceFx.LANDING, 0L));
		fx.clear(); // nothing blows up
	}

	@Test
	void theRingSitsWhereTheyStandWithinTheNamedBlock() {
		assertEquals(10.37, MaceFx.settle(10, 10.37), 1e-9, "the real spot inside the block");
		assertEquals(10, MaceFx.settle(10, 8.0), 1e-9, "never out of the block the server named");
		assertEquals(11, MaceFx.settle(10, 40.0), 1e-9);
		assertEquals(64.5, MaceFx.settle(64, 64.5), 1e-9, "a bottom slab's top, half the block, not the block's full top");
		assertEquals(64.5, MaceFx.settle(64, 65.0, MaceFx.LEAP_GROUND_RISE), 1e-9, "a leap's ground never rides the owner's rise more than half a block");
		assertEquals(64.25, MaceFx.settle(64, 64.25, MaceFx.LEAP_GROUND_RISE), 1e-9);
	}

	@Test
	void tiersHaveMoreOfEverything() {
		assertEquals(5, MaceFx.tendrilCount(1));
		assertEquals(7, MaceFx.tendrilCount(2));
		assertEquals(9, MaceFx.tendrilCount(3));
		assertEquals(0, MaceFx.redStrands(1));
		assertEquals(1, MaceFx.redStrands(2));
		assertEquals(2, MaceFx.redStrands(3));
		assertEquals(12, MaceFx.teethCount(1));
		assertEquals(16, MaceFx.teethCount(2));
		assertEquals(20, MaceFx.teethCount(3));
		assertEquals(6, MaceFx.crackCount(1));
		assertEquals(10, MaceFx.crackCount(3));
		assertEquals(14, MaceFx.shardCount(1, 1F));
		assertEquals(20, MaceFx.shardCount(2, 1F));
		assertEquals(26, MaceFx.shardCount(3, 1F));
		assertTrue(MaceFx.shardCount(3, 0.6F) < MaceFx.shardCount(3, 1F), "a small landing throws fewer");
		assertTrue(MaceFx.ringRadius(1, 1F) < MaceFx.ringRadius(2, 1F) && MaceFx.ringRadius(2, 1F) < MaceFx.ringRadius(3, 1F));
		// a bad tier is clamped
		assertEquals(MaceFx.tendrilCount(1), MaceFx.tendrilCount(-4));
		assertEquals(MaceFx.tendrilCount(3), MaceFx.tendrilCount(40));
	}

	@Test
	void theShockwaveIsScaledByTheFall() {
		assertEquals(0.6F, MaceFx.sizeScale(0), 1e-6F);
		assertEquals(0.6F, MaceFx.sizeScale(-50), 1e-6F);
		assertEquals(1.1F, MaceFx.sizeScale(100), 1e-6F);
		assertEquals(1.1F, MaceFx.sizeScale(1000), 1e-6F, "100 blocks is no bigger than 10");
		assertEquals(1.1F, MaceFx.sizeScale(Integer.MAX_VALUE), 1e-6F);
		float last = 0F;
		for (int t = 0; t <= 100; t += 5) {
			assertTrue(MaceFx.sizeScale(t) >= last, "the further the fall the bigger");
			last = MaceFx.sizeScale(t);
		}
	}

	@Test
	void tendrilsSpringOvershootAndRecoil() {
		assertEquals(0F, MaceFx.spring(0F), 1e-6F);
		assertEquals(0F, MaceFx.spring(-3F), 1e-6F);
		float last = 0F, peak = 0F;
		for (float a = 0.1F; a <= MaceFx.ERUPT; a += 0.1F) {
			float s = MaceFx.spring(a);
			assertTrue(s >= last - 1e-5F, "springing");
			last = s;
			peak = Math.max(peak, s);
		}
		assertEquals(MaceFx.OVERSHOOT, peak, 0.01F, "it overshoots at the top of the spring");
		assertEquals(1F, MaceFx.spring(MaceFx.ERUPT + MaceFx.SETTLE + 0.1F), 1e-6F, "and settles");
		float rel = MaceFx.release(20);
		assertEquals(0F, MaceFx.recoil(rel - 0.1F, rel), 1e-6F, "no recoil before the release");
		assertEquals(1F, MaceFx.recoil(rel + MaceFx.RECOIL + 0.1F, rel), 1e-6F, "all the way back after it");
		float prev = 1F;
		for (float a = rel; a <= rel + MaceFx.RECOIL; a += 0.1F) {
			float r = MaceFx.reach(a, rel);
			assertTrue(r <= prev + 0.2F, "the reach goes back in the recoil");
			prev = r;
		}
		assertEquals(0F, MaceFx.reach(MaceFx.leapLife(rel) + 1F, rel), 1e-6F, "nothing stays out");
		assertEquals(MaceFx.leapLife(rel), rel + MaceFx.RECOIL + 1F, 1e-6F);
	}

	@Test
	void theReleaseFollowsTheRiseWithinBounds() {
		assertEquals(2.2F, MaceFx.release(0), 1e-6F);
		assertEquals(2.2F, MaceFx.release(-9), 1e-6F);
		assertEquals(4F, MaceFx.release(20), 1e-6F);
		assertEquals(5F, MaceFx.release(1000), 1e-6F);
	}

	@Test
	void eachTendrilSpringsAfterTheOneBefore() {
		for (int n : new int[] {5, 7, 9}) {
			boolean[] seen = new boolean[n];
			for (int k = 0; k < n; k++) {
				float s = MaceFx.stagger(k, n);
				assertTrue(s >= 0F && s <= 0.7F, "a late start of up to 0.7 tick: " + s);
				int slot = Math.round(s * n / 0.7F);
				assertTrue(slot >= 0 && slot < n && !seen[slot], "every slot used once (n=" + n + ", k=" + k + ")");
				seen[slot] = true;
			}
		}
	}

	@Test
	void theShockRingRacesOutAndFades() {
		float max = 50F;
		assertEquals(0F, MaceFx.shockRadius(0F, max), 1e-5F);
		assertEquals(max, MaceFx.shockRadius(MaceFx.SHOCK, max), 1e-4F);
		assertEquals(max, MaceFx.shockRadius(MaceFx.SHOCK + 5F, max), 1e-4F, "it stops at its size");
		float last = -1F;
		for (float a = 0F; a <= MaceFx.SHOCK; a += 0.25F) {
			float r = MaceFx.shockRadius(a, max);
			assertTrue(r >= last, "growing");
			last = r;
		}
		assertTrue(MaceFx.shockRadius(2F, max) > 0.5F * max, "it races out and slows: " + MaceFx.shockRadius(2F, max));
		assertTrue(MaceFx.ringWidth(0F) > 3F && MaceFx.ringWidth(MaceFx.RING_LIFE - 0.1F) < 0.6F, "fat then a thread");
		assertEquals(0F, MaceFx.ringWidth(MaceFx.RING_LIFE), 1e-6F);
		assertEquals(0F, MaceFx.teethHeight(0F), 1e-6F);
		assertTrue(MaceFx.teethHeight(2F) > 0.9F, "the teeth stand quickly");
		assertEquals(0F, MaceFx.teethHeight(MaceFx.RING_LIFE), 1e-6F);
	}

	@Test
	void cracksRunOutThenAreEatenBack() {
		assertEquals(0F, MaceFx.crackGrow(0F), 1e-6F);
		assertEquals(1F, MaceFx.crackGrow(MaceFx.CRACK_GROW), 1e-6F);
		assertEquals(0F, MaceFx.crackEaten(5F), 1e-6F, "they hold before they go");
		assertTrue(MaceFx.crackEaten(MaceFx.LANDING_LIFE) > 0.99F, "and are gone by the landing's end");
		assertTrue(MaceFx.crackEaten(9F) > 0.1F && MaceFx.crackEaten(9F) < 0.9F);
	}

	@Test
	void shardsFlyUpFallAndShrinkAway() {
		float lift = 8F;
		assertEquals(0F, MaceFx.shardHeight(lift, 0F), 1e-6F);
		assertTrue(MaceFx.shardHeight(lift, 4F) > 15F, "thrown up");
		assertEquals(0F, MaceFx.shardHeight(lift, 30F), 1e-6F, "and never below the ground");
		assertEquals(1F, MaceFx.shardScale(0F), 1e-6F);
		assertEquals(0F, MaceFx.shardScale(MaceFx.SHARD_LIFE), 1e-6F);
		float last = 2F;
		for (float t = 0F; t <= MaceFx.SHARD_LIFE; t += 0.5F) {
			assertTrue(MaceFx.shardScale(t) <= last, "shrinking");
			last = MaceFx.shardScale(t);
		}
	}

	@Test
	void aFanOfTendrilsIsRealGeometryDistinctByTier() {
		// black tendrils at every tier; the red strands only from level 2
		for (int tier = 1; tier <= 3; tier++) {
			assertTrue(emit(fan(ArmLayer.BLACK, tier, 3F, 40F, 5, 0F, 1F)).quads() > 40, "black tendrils, tier " + tier);
		}
		int r2 = emit(fan(ArmLayer.RED, 2, 3F, 40F, 5, 0F, 1F)).quads(), r3 = emit(fan(ArmLayer.RED, 3, 3F, 40F, 5, 0F, 1F)).quads();
		assertTrue(r2 > 0 && r3 > r2, "one red strand at level 2, two at level 3: " + r2 + ", " + r3);
		assertEquals(0, emit(fan(ArmLayer.RED, 1, 3F, 40F, 5, 0F, 1F)).quads(), "no red strand at level 1");
		// the tendrils reach as far up as they are told, and no further (a ripple is part of a pixel or two)
		float top = topOf(emit(fan(ArmLayer.BLACK, 2, 3F, 40F, 5, 0F, 1F)));
		assertTrue(top >= 38F && top <= 44F, "the tendril reaches its length: " + top);
		// a window of the tendril is drawn: a crack grows from the middle and is eaten back to it
		int whole = emit(fan(ArmLayer.BLACK, 2, 3F, 40F, 5, 0F, 1F)).quads();
		int half = emit(fan(ArmLayer.BLACK, 2, 3F, 40F, 5, 0.5F, 1F)).quads();
		assertTrue(half < whole && half > 0, "half the tendril is eaten: " + half + " of " + whole);
		assertEquals(0, emit(fan(ArmLayer.BLACK, 2, 3F, 40F, 5, 1F, 1F)).quads(), "all of it eaten draws nothing");
		// a tendril of no length is skipped, not drawn as NaN
		assertEquals(0, emit(fan(ArmLayer.BLACK, 2, 3F, 0F, 0, 0F, 1F)).quads());
	}

	@Test
	void theCracksLieOnTheGround() {
		MaceFx.Fan f = new MaceFx.Fan();
		f.reset(ArmLayer.BLACK, 100F, 7, 3F, 2);
		f.ground = true;
		f.w0 = 1.7F;
		f.w1 = 0.4F;
		for (int k = 0; k < 8; k++) {
			float a = 6.28F * k / 8F;
			f.add(2F * (float) Math.cos(a), 0.5F, 2F * (float) Math.sin(a), 40F * (float) Math.cos(a), 0F, 40F * (float) Math.sin(a), 3F, 0F, 1F, k);
		}
		Recorder r = emit(f);
		assertTrue(r.quads() > 40);
		float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
		for (float[] v : r.kept) {
			low = Math.min(low, v[1]);
			high = Math.max(high, v[1]);
		}
		assertTrue(low >= -1F && high <= 3F, "flat on the ground: " + low + " to " + high);
		assertTrue(reachOf(r) >= 38F, "and running out to the end of them: " + reachOf(r));
	}

	@Test
	void theRingCarriesACrownThatGrowsWithTheTier() {
		float max = 50F;
		float last = 0F;
		for (int tier = 1; tier <= 3; tier++) {
			int n = emit(ring(ArmLayer.BLACK, tier, 4F, max)).quads() + emit(ring(ArmLayer.RED, tier, 4F, max)).quads();
			assertTrue(n > last, "more at tier " + tier + ": " + n + " after " + last);
			last = n;
		}
		// red: the teeth's tips (everywhere), and from level 2 a red ring inside
		int red1 = emit(ring(ArmLayer.RED, 1, 4F, max)).quads(), red2 = emit(ring(ArmLayer.RED, 2, 4F, max)).quads();
		assertTrue(red1 > 0 && red2 > red1, "red tips at level 1, and a ring too at level 2: " + red1 + ", " + red2);
		// it stays on the ground: the teeth are the only thing that stands, and they are a few pixels tall
		float top = topOf(emit(ring(ArmLayer.BLACK, 3, 4F, max)));
		assertTrue(top > 2F && top < 14F, "the crown's height: " + top);
		// the ring's outer edge is at its radius
		assertEquals(MaceFx.shockRadius(4F, max), reachOf(emit(ring(ArmLayer.BLACK, 1, 4F, max))), 4.5F, "the ring's reach");
		// gone at the end of its life
		assertEquals(0, emit(ring(ArmLayer.BLACK, 3, MaceFx.RING_LIFE + 1F, max)).quads(), "nothing at the ring's end");
	}

	@Test
	void theLeapsGroundCrackHasNoCrown() {
		MaceFx.Ring r = new MaceFx.Ring();
		r.reset(100F, 7, 2, 3F, 6F, 12F, 0F, false);
		int n = emit(r).quads();
		assertTrue(n > 0 && n < emit(ring(ArmLayer.BLACK, 2, 3F, 50F)).quads(), "a small ring, no teeth: " + n);
	}

	@Test
	void aBurstOfShardsIsRealAndWearsRedTips() {
		for (int tier = 1; tier <= 3; tier++) {
			int black = emit(shards(ArmLayer.BLACK, tier, 3F, MaceFx.shardCount(tier, 1F))).quads();
			int red = emit(shards(ArmLayer.RED, tier, 3F, MaceFx.shardCount(tier, 1F))).quads();
			assertTrue(black > 0 && red > 0, "black shards and their red tips, tier " + tier);
		}
		assertTrue(emit(shards(ArmLayer.BLACK, 3, 3F, 26)).quads() > emit(shards(ArmLayer.BLACK, 3, 3F, 8)).quads(), "more shards, more quads");
		assertEquals(0, emit(shards(ArmLayer.BLACK, 2, 0F, 20)).quads(), "nothing is thrown yet at age 0");
		assertEquals(0, emit(shards(ArmLayer.BLACK, 2, MaceFx.SHARD_LIFE + 1F, 20)).quads(), "and nothing is left at the end");
		// they fly up and out, then fall back to the ground (none is ever below it)
		Recorder early = emit(shards(ArmLayer.BLACK, 3, 4F, 26));
		float low = Float.MAX_VALUE;
		for (float[] v : early.kept) low = Math.min(low, v[1]);
		assertTrue(topOf(early) > 10F && low > -3F, "up and never far under the ground: " + topOf(early) + ", " + low);
		// clods: black boxes only, no red
		MaceFx.Shards clods = new MaceFx.Shards();
		clods.reset(100F, 7, 2, 3F, 8, 0.45F, 5F, true);
		int boxes = emit(clods).quads();
		assertTrue(boxes > 0 && boxes % 6 == 0, "boxes of six quads: " + boxes);
		assertEquals(0, emit(clods.copyFor(ArmLayer.RED)).quads(), "a clod is not red");
	}

	@Test
	void aCreatureHitTakesASprayOfItsOwn() {
		MaceFx.Shards s = new MaceFx.Shards();
		s.reset(100F, 7, 2, 2F, 7, 0.5F, 0F, false);
		s.wound = true;
		Recorder r = emit(s);
		assertTrue(r.quads() > 0, "a spray from the body");
		boolean down = false;
		for (float[] v : r.kept) down |= v[1] < 0F;
		assertTrue(down, "out of a body in every direction, not only up");
	}
}
