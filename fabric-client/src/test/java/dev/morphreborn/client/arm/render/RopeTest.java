package dev.morphreborn.client.arm.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * The tail's rope: it comes to rest hanging straight down whichever way down is, keeps its links and its sanity at
 * the largest step a frame can take (2 ticks) even with its anchor thrashing, and holds still at a step of 0.
 */
class RopeTest {
	/** The sageo tail: 6 nodes, links of 1.6 px (0.1 block), gravity 0.35/16 block per tick², damping 0.86. */
	private static final int NODES = 6;
	private static final float LINK = 0.1F, G = 0.35F / 16F, DAMPING = 0.86F;

	private static float[] nodes(Rope rope) {
		float[] out = new float[3 * rope.nodes()];
		rope.copyNodes(out);
		return out;
	}

	@Test
	void hangsWorldDown() {
		for (Vector3f down : new Vector3f[] {new Vector3f(0F, -1F, 0F), new Vector3f(1F, 0F, 0F), new Vector3f(0.3F, -0.5F, 0.81F).normalize()}) {
			Rope rope = new Rope(NODES, LINK);
			Vector3f root = new Vector3f(2F, 3F, 4F);
			rope.reset(root);
			Vector3f gravity = new Vector3f(down).mul(G);
			// start it off sideways, then let it settle
			rope.impulse(0.3F, 0.2F, -0.25F);
			for (int i = 0; i < 600; i++) rope.step(root, gravity, 1F, DAMPING);
			float[] p = nodes(rope);
			for (int i = 0; i < NODES; i++) {
				Vector3f expected = new Vector3f(down).mul(LINK * i).add(root);
				assertEquals(0F, expected.distance(p[3 * i], p[3 * i + 1], p[3 * i + 2]), 2e-3F, "node " + i + " hanging along " + down);
			}
		}
	}

	@Test
	void stableAtTwoTickSteps() {
		Rope rope = new Rope(NODES, LINK);
		Vector3f root = new Vector3f(), gravity = new Vector3f(0F, -G, 0F);
		rope.reset(root);
		float worstStretch = 0F;
		for (int i = 0; i < 3000; i++) {
			// an anchor swinging hard (a sword arm), with now and then a jump
			float t = i * 2F;
			root.set(0.4F * (float) Math.sin(t * 0.7F), 0.2F * (float) Math.cos(t * 0.45F), 0.3F * (float) Math.sin(t * 1.3F));
			if (i % 500 == 499) root.add(3F, 0F, 0F);
			rope.step(root, gravity, i % 7 == 0 ? 5F : 2F, DAMPING); // 5 is clamped to 2
			float[] p = nodes(rope);
			for (float f : p) assertTrue(Float.isFinite(f), "step " + i);
			for (int k = 0; k + 1 < NODES; k++) {
				float d = new Vector3f(p[3 * k], p[3 * k + 1], p[3 * k + 2]).distance(p[3 * k + 3], p[3 * k + 4], p[3 * k + 5]);
				worstStretch = Math.max(worstStretch, Math.abs(d - LINK) / LINK);
			}
			assertEquals(0F, root.distance(p[0], p[1], p[2]), 1e-6F, "the first node stays on its anchor");
			// it can never be further from its anchor than its length (plus what the passes leave)
			float reach = root.distance(p[3 * (NODES - 1)], p[3 * (NODES - 1) + 1], p[3 * (NODES - 1) + 2]);
			assertTrue(reach <= LINK * (NODES - 1) * 1.25F, "step " + i + " reach " + reach);
		}
		assertTrue(worstStretch < 0.25F, "links keep their length: worst " + worstStretch);
		// and it still settles afterwards
		for (int i = 0; i < 600; i++) rope.step(root, gravity, 2F, DAMPING);
		float[] p = nodes(rope);
		assertEquals(root.y - LINK * (NODES - 1), p[3 * (NODES - 1) + 1], 2e-3F);
	}

	@Test
	void zeroStepHoldsStill() {
		Rope rope = new Rope(NODES, LINK);
		Vector3f root = new Vector3f(1F, 1F, 1F), gravity = new Vector3f(0F, -G, 0F);
		rope.reset(root);
		rope.impulse(0.2F, 0F, 0F);
		for (int i = 0; i < 5; i++) rope.step(root, gravity, 1F, DAMPING);
		float[] before = nodes(rope);
		for (int i = 0; i < 10; i++) rope.step(root, gravity, 0F, DAMPING);
		rope.step(root, gravity, Float.NaN, DAMPING);
		float[] after = nodes(rope);
		for (int i = 0; i < before.length; i++) assertEquals(before[i], after[i], 1e-5F, "a paused rope stays put");
	}

	@Test
	void droopLeavesAlongItsDirectionAndBendsDown() {
		float[] out = new float[3 * NODES];
		Rope.droop(new Vector3f(), new Vector3f(1F, 0F, 0F), new Vector3f(0F, -1F, 0F), NODES, LINK, out);
		assertEquals(0F, out[0], 1e-6F);
		assertTrue(out[3] > 0F && Math.abs(out[4]) < out[3], "it starts out sideways");
		assertTrue(out[3 * (NODES - 1) + 1] < -0.1F, "it ends hanging down");
	}
}
