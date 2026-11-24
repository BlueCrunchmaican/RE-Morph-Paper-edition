package dev.morphreborn.client.arm.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * Tendrils: both ends stay pinned however they writhe, growing only ever extends the same path, the piece count is the
 * frenzy's, and a tendril of no length (or a vertical one) never produces a value that isn't a number.
 */
class TendrilPathTest {
	private static final TendrilPath.WidthFn WIDTH = s -> ArmMath.lerp(2.2F, 1.0F, s);

	@Test
	void endsArePinned() {
		Vector3f from = new Vector3f(1F, 2F, 3F), to = new Vector3f(-20F, 15F, 40F), out = new Vector3f();
		for (float time : new float[] {0F, 13.7F, 2000.25F}) {
			for (float phase : new float[] {0F, 1.7F, 51F}) {
				for (float amp : new float[] {0F, 3F, 8F}) {
					TendrilPath.point(from, to, 0F, phase, time, amp, out);
					assertEquals(0F, out.distance(from), 1e-4F, "start at t " + time + " phase " + phase + " amp " + amp);
					TendrilPath.point(from, to, 1F, phase, time, amp, out);
					assertEquals(0F, out.distance(to), 1e-3F, "end at t " + time + " phase " + phase + " amp " + amp);
				}
			}
		}
		// the drawn strand: its start cap sits on `from`, its end cap on `to`
		SkinMeshTest.Recorder r = new SkinMeshTest.Recorder();
		TendrilPath.sweep(SkinMeshTest.mesh(r), from, to, 1F, 1.7F, 13.7F, 5F, WIDTH, null, 12, ArmLayer.RED, ArmLayer.RED, null, 0);
		int quads = r.quads();
		assertEquals(12 * 4 + 2, quads, "12 pieces of a square strand and two caps");
		assertEquals(0F, r.centre(quads - 2).distance(from), 1e-3F, "start cap");
		assertEquals(0F, r.centre(quads - 1).distance(to), 1e-3F, "end cap");
		r.assertFinite();
		r.assertUnitNormals();
	}

	@Test
	void writheIsStillAtTheEndsAndMovesInTheMiddle() {
		Vector3f from = new Vector3f(), to = new Vector3f(0F, 0F, 32F), a = new Vector3f(), b = new Vector3f();
		TendrilPath.point(from, to, 0.5F, 0F, 0F, 4F, a);
		TendrilPath.point(from, to, 0.5F, 0F, 10F, 4F, b);
		assertTrue(a.distance(b) > 0.5F, "the middle writhes over time");
		TendrilPath.point(from, to, 0.02F, 0F, 0F, 4F, a);
		TendrilPath.point(from, to, 0.02F, 0F, 10F, 4F, b);
		assertTrue(a.distance(b) < 0.6F, "near the ends it hardly moves");
	}

	@Test
	void truncationOnlyExtends() {
		Vector3f from = new Vector3f(0F, 0F, 0F), to = new Vector3f(10F, 30F, -5F);
		Vector3f dir = new Vector3f(to).sub(from).normalize();
		SkinMeshTest.Recorder whole = new SkinMeshTest.Recorder();
		TendrilPath.sweep(SkinMeshTest.mesh(whole), from, to, 1F, 0.4F, 7F, 3F, WIDTH, s -> TendrilPath.swell(s, 0.4F, 0.8F), 20, ArmLayer.BLACK, ArmLayer.BLACK, null,
				0);
		int lastQuads = 0;
		float lastFar = Float.NEGATIVE_INFINITY;
		for (int step = 1; step <= 40; step++) {
			float reach = step / 40F;
			SkinMeshTest.Recorder r = new SkinMeshTest.Recorder();
			TendrilPath.sweep(SkinMeshTest.mesh(r), from, to, reach, 0.4F, 7F, 3F, WIDTH, s -> TendrilPath.swell(s, 0.4F, 0.8F), 20, ArmLayer.BLACK, ArmLayer.BLACK,
					null, 0);
			r.assertFinite();
			int sides = r.quads() - 2;
			assertTrue(r.quads() >= lastQuads, "more reach never means fewer pieces");
			float far = Float.NEGATIVE_INFINITY;
			for (SkinMeshTest.Recorder.Vertex v : r.vertices) far = Math.max(far, dir.dot(v.x(), v.y(), v.z()));
			assertTrue(far >= lastFar - 1e-4F, "the front never draws back as reach grows (" + reach + ")");
			// every whole piece is exactly the full tendril's piece: it grows along its path, it doesn't stretch
			int whole4 = Math.max(0, sides - 4);
			for (int i = 0; i < 4 * whole4; i++) {
				SkinMeshTest.Recorder.Vertex v = r.vertices.get(i), w = whole.vertices.get(i);
				assertEquals(w.x(), v.x(), 1e-4F);
				assertEquals(w.y(), v.y(), 1e-4F);
				assertEquals(w.z(), v.z(), 1e-4F);
			}
			lastQuads = r.quads();
			lastFar = far;
		}
	}

	@Test
	void noLengthDrawsNothingAndNothingIsNaN() {
		Vector3f p = new Vector3f(4F, 5F, 6F), out = new Vector3f();
		SkinMeshTest.Recorder r = new SkinMeshTest.Recorder();
		SkinMesh m = SkinMeshTest.mesh(r);
		TendrilPath.sweep(m, p, p, 1F, 0F, 0F, 2F, WIDTH, null, 8, ArmLayer.BLACK, ArmLayer.BLACK, null, 0);
		assertEquals(0, r.vertices.size(), "a tendril of no length draws nothing");
		TendrilPath.point(p, p, 0.5F, 0F, 0F, 2F, out);
		assertEquals(p, out);
		// almost no length, straight up (no sideways from +Y), and no reach
		TendrilPath.sweep(m, p, new Vector3f(4F, 5.001F, 6F), 1F, 0F, 0F, 2F, WIDTH, null, 8, ArmLayer.BLACK, ArmLayer.BLACK, null, 0);
		TendrilPath.sweep(m, p, new Vector3f(4F, 50F, 6F), 0.5F, 0F, 0F, 2F, WIDTH, null, 8, ArmLayer.BLACK, ArmLayer.BLACK, null, 0);
		int drawn = r.vertices.size();
		TendrilPath.sweep(m, p, new Vector3f(4F, 50F, 6F), 0F, 0F, 0F, 2F, WIDTH, null, 8, ArmLayer.BLACK, ArmLayer.BLACK, null, 0);
		TendrilPath.sweep(m, p, new Vector3f(4F, 50F, 6F), Float.NaN, 0F, 0F, 2F, WIDTH, null, 8, ArmLayer.BLACK, ArmLayer.BLACK, null, 0);
		assertEquals(drawn, r.vertices.size(), "no reach draws nothing");
		r.assertFinite();
		r.assertUnitNormals();
	}

	@Test
	void piecesAreTheFrenzys() {
		assertEquals(4, TendrilPath.pieces(0F));
		assertEquals(4, TendrilPath.pieces(0.5F));
		assertEquals(5, TendrilPath.pieces(1.9F));
		assertEquals(25, TendrilPath.pieces(10F));
		assertEquals(40, TendrilPath.pieces(100F));
		assertEquals(4, TendrilPath.pieces(Float.NaN));
	}

	@Test
	void drawnOnlyInItsLayer() {
		SkinMeshTest.Recorder r = new SkinMeshTest.Recorder();
		TendrilPath.sweep(SkinMeshTest.mesh(r), new Vector3f(), new Vector3f(0F, 0F, 20F), 1F, 0F, 0F, 1F, WIDTH, null, 8, ArmLayer.RED, ArmLayer.BLACK, null, 0);
		assertEquals(0, r.vertices.size());
	}
}
