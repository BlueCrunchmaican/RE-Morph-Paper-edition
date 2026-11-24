package dev.morphreborn.client.arm.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.SkinMesh;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * The spear's Impale (FX_SPEAR, 2026-11-16): the contract's numbers and the real server's three messages (LUNGE, PIN, RELEASE, as
 * SpearImpale sends them), the timelines (the rise and overshoot of the spikes, how long they hold, the sink), and the cage's
 * geometry (a ring of spikes closing over the middle, distinct by tier).
 */
class SpearFxTest {
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

	private static SpearFx.Ring ring(ArmLayer layer, int tier, float age, int hold, int outer, int inner) {
		SpearFx.Ring r = new SpearFx.Ring();
		r.reset(layer, 100F, 7, tier, outer, inner, 9.6F, 36F, age, hold, SpearFx.close(age, hold));
		return r;
	}

	@Test
	void contractIsTheServersFxKind() {
		assertEquals(15, ArmProtocol.FX_SPEAR);
		assertEquals(21, ArmProtocol.FX_KINDS);
		assertEquals("spear", ArmProtocol.fxName(ArmProtocol.FX_SPEAR));
		// the server's SpearImpale.FX_LUNGE / FX_PIN / FX_RELEASE (docs/PROTOCOL.md)
		assertEquals(1, SpearFx.FX_LUNGE);
		assertEquals(2, SpearFx.FX_PIN);
		assertEquals(3, SpearFx.FX_RELEASE);
		assertEquals(ArmProtocol.FX_SPEAR, new SpearFx().kinds()[0]);
		assertEquals(1, new SpearFx().kinds().length);
	}

	@Test
	void handlerIsRegisteredAndLoadsByName() throws Exception {
		assertTrue(ArmFx.HANDLERS.contains(SpearFx.class.getName()));
		assertTrue(FxHandler.class.isAssignableFrom(Class.forName(SpearFx.class.getName())));
	}

	@Test
	void holdIsBoundedWhateverTheServerSays() {
		assertEquals(4, SpearFx.hold(0));
		assertEquals(4, SpearFx.hold(-9));
		assertEquals(30, SpearFx.hold(30));
		assertEquals(100, SpearFx.hold(1_000_000));
		assertEquals(30 + SpearFx.SINK, SpearFx.life(30), 1e-6F);
	}

	/** An event as SpearImpale sends it (ArmFxMsg to S_ARM_FX): origin 7 is the owner. */
	private static ArmFxEvent event(int arg, int ticks, int[] ids, long[] positions, long at) {
		return new ArmFxEvent(7, ArmProtocol.FX_SPEAR, 2, ticks, 99, ids, positions, arg, at);
	}

	@Test
	void theServersThreeMessagesEachDoTheirOwnThing() {
		long floor = BlockPos.asLong(10, 63, -4);
		long start = BlockPos.asLong(0, 64, 0), end = BlockPos.asLong(4, 64, 0);
		Arms.TICK = 1000;
		SpearFx fx = new SpearFx();
		// a pin's cage, from the PIN message alone: the lunge's own message draws no cage
		fx.start(event(SpearFx.FX_PIN, 40, new int[] {55}, new long[] {floor}, 1000));
		assertEquals(1, fx.moveCount());
		assertEquals(SpearFx.FX_PIN, fx.move(0).arg);
		assertEquals(40, fx.move(0).hold, "the pin's ticks are the hold, not the lunge's");
		assertEquals(55, fx.move(0).target);
		// the spikes stand on the TOP of the floor block named: its bottom centre plus one block
		assertEquals(10.5, fx.move(0).base.x, 1e-9);
		assertEquals(64.0, fx.move(0).base.y, 1e-9);
		assertEquals(-3.5, fx.move(0).base.z, 1e-9);
		// a message that names a pin with nothing to stand on is dropped; the lunge with no arm to trail from adds nothing (no level here)
		fx.start(event(SpearFx.FX_PIN, 40, new int[0], new long[0], 1000));
		assertEquals(1, fx.moveCount(), "no block and no creature: nowhere to draw it");
		// the lunge: its ticks (1 to 6) are never taken for a hold; it adds a trail, never a cage
		fx.lunge(event(SpearFx.FX_LUNGE, 4, new int[] {55}, new long[] {start, end}, 1000), new Vec3(0, 65, 0));
		assertEquals(1, countOf(fx, SpearFx.FX_LUNGE));
		assertEquals(0, fx.move(1).hold, "a trail holds nothing");
		assertEquals(SpearFx.TRAIL_LIFE, fx.move(1).life(), 1e-6F);
		assertEquals(1, countOf(fx, SpearFx.FX_PIN), "the lunge made no cage");
		fx.lunge(event(SpearFx.FX_LUNGE, 4, new int[] {55}, new long[] {start, end}, 1000), null);
		assertEquals(1, countOf(fx, SpearFx.FX_LUNGE), "no arm to trail from: no trail");
		// a release cuts that creature's pin short, and no other
		fx.start(event(SpearFx.FX_PIN, 50, new int[] {56}, new long[] {floor}, 1000));
		Arms.TICK = 1006;
		fx.start(event(SpearFx.FX_RELEASE, 0, new int[] {55}, new long[0], 1006));
		assertEquals(6, fx.move(0).hold, "sinks from where it had got to");
		assertEquals(50, pinOf(fx, 56).hold, "another creature's pin is untouched");
		fx.start(event(SpearFx.FX_RELEASE, 0, new int[] {55}, new long[0], 1010));
		assertEquals(6, fx.move(0).hold, "a release never lengthens a pin");
		// somebody else's release leaves the pin alone (matched by owner)
		fx.start(new ArmFxEvent(8, ArmProtocol.FX_SPEAR, 2, 0, 1, new int[] {56}, new long[0], SpearFx.FX_RELEASE, 1006));
		assertEquals(50, pinOf(fx, 56).hold);
		// and an unknown move is ignored
		int before = fx.moveCount();
		fx.start(event(9, 40, new int[] {55}, new long[] {floor}, 1006));
		assertEquals(before, fx.moveCount());
		// the effect is over by its life
		Arms.TICK = 1006 + 60;
		fx.tick();
		assertEquals(0, fx.moveCount());
	}

	private static int countOf(SpearFx fx, int arg) {
		int n = 0;
		for (int i = 0; i < fx.moveCount(); i++) if (fx.move(i).arg == arg) n++;
		return n;
	}

	private static SpearFx.Move pinOf(SpearFx fx, int target) {
		for (int i = 0; i < fx.moveCount(); i++) if (fx.move(i).arg == SpearFx.FX_PIN && fx.move(i).target == target) return fx.move(i);
		throw new AssertionError("no pin on " + target);
	}

	@Test
	void aPinNotHeldStillBreaksThroughBriefly() {
		Arms.TICK = 5;
		SpearFx fx = new SpearFx();
		fx.start(event(SpearFx.FX_PIN, 0, new int[] {55}, new long[] {BlockPos.asLong(0, 63, 0)}, 5));
		assertEquals(4, fx.move(0).hold, "ticks 0 (skewered, not held): the spikes come through and go");
	}

	@Test
	void tiersHaveMoreSpikesAndSpurs() {
		assertEquals(6, SpearFx.spikeCount(1));
		assertEquals(8, SpearFx.spikeCount(2));
		assertEquals(10, SpearFx.spikeCount(3));
		assertEquals(0, SpearFx.innerCount(1));
		assertEquals(0, SpearFx.innerCount(2));
		assertEquals(5, SpearFx.innerCount(3));
		assertEquals(0, SpearFx.spurCount(1));
		assertEquals(1, SpearFx.spurCount(2));
		assertEquals(2, SpearFx.spurCount(3));
		// the ring clears the creature and the spikes stand over its head
		assertEquals(0.6F, SpearFx.ringRadius(0.6F), 1e-6F);
		assertTrue(SpearFx.ringRadius(0F) >= 0.4F && SpearFx.ringRadius(100F) <= 2.6F);
		assertTrue(SpearFx.spikeHeight(1.95F) > 1.95F, "over a zombie's head: " + SpearFx.spikeHeight(1.95F));
		assertTrue(SpearFx.spikeHeight(0.1F) >= 0.8F && SpearFx.spikeHeight(50F) <= 4.5F);
	}

	@Test
	void spikesRiseOvershootHoldAndSink() {
		int hold = 30;
		assertEquals(0F, SpearFx.rise(0F, hold), 1e-6F);
		assertEquals(0F, SpearFx.rise(-3F, hold), 1e-6F);
		float last = 0F, peak = 0F;
		for (float a = 0.1F; a <= SpearFx.ERUPT; a += 0.1F) {
			float r = SpearFx.rise(a, hold);
			assertTrue(r >= last - 1e-5F, "rising");
			last = r;
			peak = Math.max(peak, r);
		}
		assertEquals(SpearFx.OVERSHOOT, peak, 0.01F, "it overshoots at the top of the rise");
		assertEquals(1F, SpearFx.rise(SpearFx.ERUPT + SpearFx.SETTLE, hold), 1e-5F, "and settles");
		assertEquals(1F, SpearFx.rise(20F, hold), 1e-5F, "held for the pin");
		assertEquals(1F, SpearFx.rise(hold, hold), 1e-5F);
		assertTrue(SpearFx.rise(hold + 3F, hold) < 1F && SpearFx.rise(hold + 3F, hold) > 0F, "sinking");
		assertEquals(0F, SpearFx.rise(hold + SpearFx.SINK, hold), 1e-5F, "gone into the ground");
		// the effect is over by its life
		assertEquals(0F, SpearFx.rise(SpearFx.life(hold), hold), 1e-5F);
	}

	@Test
	void tipsCloseOverTheCreatureThenOpenAsTheySink() {
		int hold = 30;
		assertEquals(0F, SpearFx.close(0F, hold), 1e-6F);
		assertEquals(1F, SpearFx.close(SpearFx.ERUPT + 3F + 1F, hold), 1e-6F);
		assertEquals(1F, SpearFx.close(hold, hold), 1e-6F);
		assertEquals(0F, SpearFx.close(hold + SpearFx.SINK, hold), 1e-6F);
		float last = 0F;
		for (float a = 0F; a <= 6F; a += 0.25F) {
			assertTrue(SpearFx.close(a, hold) >= last - 1e-6F);
			last = SpearFx.close(a, hold);
		}
		// spikes don't all rise at once, but every one has started within a tick
		for (int n : new int[] {5, 6, 8, 10}) {
			float max = 0F;
			boolean[] seen = new boolean[n];
			for (int k = 0; k < n; k++) {
				float s = SpearFx.stagger(k, n);
				assertTrue(s >= 0F && s < 1F, "within a tick: " + s);
				max = Math.max(max, s);
				seen[Math.round(s * n / 0.9F) % n] = true;
			}
			assertTrue(max > 0.5F, "staggered");
			for (boolean b : seen) assertTrue(b, "every slot used once for n " + n);
		}
	}

	@Test
	void trailIsEatenTowardTheSpearAndFades() {
		assertEquals(0F, SpearFx.trailEaten(0F), 1e-6F);
		assertEquals(0F, SpearFx.trailEaten(0.8F), 1e-6F);
		assertEquals(1F, SpearFx.trailEaten(SpearFx.TRAIL_LIFE), 1e-6F);
		float last = 0F;
		for (float a = 0F; a <= SpearFx.TRAIL_LIFE; a += 0.2F) {
			assertTrue(SpearFx.trailEaten(a) >= last - 1e-6F);
			last = SpearFx.trailEaten(a);
		}
		assertEquals(1F, SpearFx.trailThickness(0F), 1e-6F);
		assertEquals(0.5F, SpearFx.trailThickness(SpearFx.TRAIL_LIFE), 1e-6F);
	}

	@Test
	void skewerJoinsWithTheCageAndLeavesWithIt() {
		int hold = 30;
		assertEquals(0F, SpearFx.skewerReach(0F, hold), 1e-6F);
		assertEquals(1F, SpearFx.skewerReach(SpearFx.SKEWER_OUT, hold), 1e-6F);
		assertEquals(1F, SpearFx.skewerReach(hold, hold), 1e-6F);
		assertEquals(0F, SpearFx.skewerReach(hold + SpearFx.SINK, hold), 1e-6F);
		// the same clock as the spikes: the line is held exactly as long as the spikes are, and draws back with them
		for (float a = SpearFx.ERUPT + SpearFx.SETTLE; a <= SpearFx.life(hold); a += 0.5F) {
			assertEquals(SpearFx.rise(a, hold) > 0.999F, SpearFx.skewerReach(a, hold) > 0.999F, "held together at " + a);
		}
		assertTrue(SpearFx.skewerReach(hold + 2.5F, hold) > 0F && SpearFx.skewerReach(hold + 2.5F, hold) < 1F, "still partly out as the spikes sink");
	}

	@Test
	void cageDrawsRealSpikesThatStandOverTheCreatureAndCloseIn() {
		int hold = 30;
		for (int tier = 1; tier <= 3; tier++) {
			int outer = SpearFx.spikeCount(tier), inner = SpearFx.innerCount(tier);
			// nothing before the first spike's start, then more as they come up
			assertEquals(0, emit(ring(ArmLayer.BLACK, tier, 0F, hold, outer, inner)).quads());
			int growing = emit(ring(ArmLayer.BLACK, tier, 0.4F, hold, outer, inner)).quads();
			int full = emit(ring(ArmLayer.BLACK, tier, 12F, hold, outer, inner)).quads();
			assertTrue(growing > 0 && full > growing, "tier " + tier + ": " + growing + " then " + full);
			assertTrue(emit(ring(ArmLayer.RED, tier, 12F, hold, outer, inner)).quads() > 0, "the red tips, tier " + tier);
			// the spikes stand up to their height (with the overshoot's slack) and no higher
			float top = 0F;
			for (float[] v : emit(ring(ArmLayer.BLACK, tier, 12F, hold, outer, inner)).kept) top = Math.max(top, v[1]);
			assertTrue(top > 0.7F * 36F && top <= 36F * 1.35F + 3F, "tier " + tier + " stands " + top + " px");
			// gone into the ground after the sink
			assertEquals(0, emit(ring(ArmLayer.BLACK, tier, hold + SpearFx.SINK + 0.1F, hold, outer, inner)).quads(), "sunk");
		}
		// the tips lean in as it closes: the topmost vertices' distance from the axis shrinks
		float open = tipRadius(ring(ArmLayer.BLACK, 2, 1.6F, hold, 8, 0));
		float closed = tipRadius(ring(ArmLayer.BLACK, 2, 12F, hold, 8, 0));
		assertTrue(closed < 0.5F * open, "closing: " + open + " px out, then " + closed);
		// tiers differ: more spikes (and spurs, red ground ring and an inner ring) cost more
		int a = emit(ring(ArmLayer.BLACK, 1, 12F, hold, 6, 0)).quads(), b = emit(ring(ArmLayer.BLACK, 2, 12F, hold, 8, 0)).quads(),
				c = emit(ring(ArmLayer.BLACK, 3, 12F, hold, 10, 5)).quads();
		assertTrue(a < b && b < c, a + ", " + b + ", " + c);
		int redA = emit(ring(ArmLayer.RED, 1, 12F, hold, 6, 0)).quads(), redB = emit(ring(ArmLayer.RED, 2, 12F, hold, 8, 0)).quads();
		assertTrue(redB > redA, "level 2 adds a red ring in the ground: " + redA + ", " + redB);
	}

	/** The farthest from the axis any of the upper vertices is (those above 88% of the height). */
	private static float tipRadius(SpearFx.Ring r) {
		float max = 0F;
		for (float[] v : emit(r).kept) {
			if (v[1] > 0.88F * 36F) max = Math.max(max, (float) Math.sqrt(v[0] * v[0] + v[2] * v[2]));
		}
		return max;
	}

	@Test
	void trailAndSkewerAreStraightTendrilsThatGrowByTruncation() {
		for (int piece = 0; piece <= 2; piece++) {
			SpearFx.Strand s = new SpearFx.Strand();
			s.reset(piece == 0 ? ArmLayer.BLACK : ArmLayer.RED, 100F, 7, 2F);
			s.aim(60F, 10F, -20F);
			s.piece = piece;
			s.w0 = 0.3F;
			s.w1 = 1.9F;
			s.amp = 2F;
			s.stations = 12;
			float full = emit(s).quads();
			assertTrue(full >= 11 * 4, "a tube of 12 stations: " + full);
			s.s0 = 0.4F;
			float nearest = Float.MAX_VALUE;
			for (float[] v : emit(s).kept) nearest = Math.min(nearest, (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]));
			assertTrue(nearest > 0.3F * s.lenPx, "its tail eaten: nothing within " + nearest + " of the start");
			s.s0 = 0.995F;
			assertEquals(0, emit(s).quads(), "nothing left of it");
		}
		// its ends: the root at the start, the head at the full vector
		SpearFx.Strand s = new SpearFx.Strand();
		s.reset(ArmLayer.BLACK, 0F, 1, 0F);
		s.aim(60F, 10F, -20F);
		org.joml.Vector3f out = new org.joml.Vector3f();
		s.at(0F, out);
		assertEquals(0F, out.length(), 1e-4F);
		s.at(1F, out);
		assertEquals(60F, out.x, 1e-3F);
		assertEquals(10F, out.y, 1e-3F);
		assertEquals(-20F, out.z, 1e-3F);
		// straight up and down still has a frame
		s.aim(0F, 40F, 0F);
		s.at(1F, out);
		assertEquals(40F, out.y, 1e-3F);
		assertTrue(emit(s).quads() > 0);
	}

	@Test
	void debrisThrownUpFallsBackAndShrinks() {
		SpearFx.Debris d = new SpearFx.Debris();
		d.reset(100F, 7, 0.5F, 9.6F);
		int early = emit(d).quads();
		assertTrue(early > 0, "beads fly while the ring erupts");
		d.reset(100F, 7, 5.9F, 9.6F);
		assertTrue(emit(d).quads() <= early, "dwindling");
		d.reset(100F, 7, 6.5F, 9.6F);
		assertEquals(0, emit(d).quads(), "gone by tick 6");
		for (int j = 0; j < 8; j++) {
			assertEquals(0F, SpearFx.Debris.height(7, j, 0F), 1e-6F);
			for (float t = 0F; t <= 6F; t += 0.5F) assertTrue(SpearFx.Debris.height(7, j, t) >= 0F, "never below the ground");
		}
	}
}
