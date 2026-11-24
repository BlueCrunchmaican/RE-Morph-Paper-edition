package dev.morphreborn.client.arm.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * Lofts: quad counts as the budget counts them (2.10), outward faces whichever way a section winds, caps facing along
 * the piece, layer filtering, and frames that are carried along without twisting.
 */
class LoftTest {
	private static final Vector3f UP = new Vector3f(0F, 0F, 1F);

	/** A straight tube up +Y, 4 px long. */
	private static final Loft.CurveFn STRAIGHT = (s, out) -> out.set(0F, 4F * s, 0F);

	@Test
	void tubeIsOneQuadPerSegmentAndSide() {
		SkinMeshTest.Recorder r = new SkinMeshTest.Recorder();
		SkinMesh m = SkinMeshTest.mesh(r);
		Static st = new Static().set(10F, 2, 1F, 0);
		Loft.tube(m, STRAIGHT, 0F, 1F, 20, 4, s -> 0.5F, ArmLayer.BLACK, ArmLayer.BLACK, st, 1, 1F, 0F, 4F, UP, 1F);
		assertEquals(19 * 4, r.quads(), "a 20-station 4-sided tube (the wrap strands' 2 x 76 = 152)");
		Loft.tube(m, STRAIGHT, 0F, 1F, 20, 4, s -> 0.5F, ArmLayer.BLACK, ArmLayer.RED, st, 1, 1F, 0F, 4F, UP, 1F);
		assertEquals(19 * 4, r.quads(), "a tube is drawn only in its own layer");
		r.assertFinite();
		r.assertUnitNormals();
		for (int q = 0; q < r.quads(); q++) {
			Vector3f c = r.centre(q);
			Vector3f out = new Vector3f(c.x, 0F, c.z);
			assertTrue(out.dot(r.normal(q)) > 0.9F * out.length(), "side " + q + " faces out");
		}
	}

	@Test
	void closedTubeJoinsItsEnds() {
		SkinMeshTest.Recorder r = new SkinMeshTest.Recorder();
		Loft.CurveFn loop = (s, out) -> out.set(2.25F * (float) Math.cos(ArmMath.TAU * s), 3F, 2.25F * (float) Math.sin(ArmMath.TAU * s));
		Loft.closedTube(SkinMeshTest.mesh(r), loop, 20, 4, s -> 0.325F, ArmLayer.BLACK, ArmLayer.BLACK, null, 2, 1F, 20F, new Vector3f(0F, 1F, 0F), 1F);
		assertEquals(20 * 4, r.quads(), "a tsuba loop of 20 stations is 80 quads");
		r.assertFinite();
		r.assertUnitNormals();
		// every face faces away from the tube's own centreline
		for (int q = 0; q < r.quads(); q++) {
			Vector3f c = r.centre(q);
			Vector3f onLoop = new Vector3f(c.x, 0F, c.z).normalize(2.25F).add(0F, 3F, 0F);
			assertTrue(new Vector3f(c).sub(onLoop).dot(r.normal(q)) > 0F, "face " + q + " faces in");
		}
	}

	/** A blade-like section: 7 points round (A, C, H+, F, H-, D, B), its edge band (H+F, FH-) red. */
	private static float[] section(float h, float th, boolean clockwise) {
		float[] p = {0F, -0.35F * th, 0.3F * h, -0.5F * th, 0.65F * h, -0.25F * th, h, 0F, 0.65F * h, 0.25F * th, 0.3F * h, 0.5F * th, 0F, 0.35F * th};
		if (!clockwise) return p;
		float[] q = new float[p.length];
		for (int j = 0; j < 7; j++) {
			q[2 * j] = p[2 * (6 - j)];
			q[2 * j + 1] = p[2 * (6 - j) + 1];
		}
		return q;
	}

	private static Stations blade(boolean clockwise) {
		Stations s = new Stations().reset(7);
		for (int i = 0; i < 28; i++) {
			float y = 3.5F + 21F * i / 27F;
			s.add(0F, y, 0F, 0F, 1F, 0F, 1F, 0F, 0F, y - 3.5F, section(1.9F - 0.5F * i / 27F, 0.55F, clockwise), 1F);
		}
		return s;
	}

	@Test
	void bladeCountsAsTheBudgetDoes() {
		Stations s = blade(false);
		Loft.FaceLayer edge = (seg, face) -> face == 2 || face == 3 ? ArmLayer.RED : ArmLayer.BLACK;
		SkinMeshTest.Recorder black = new SkinMeshTest.Recorder();
		Loft.sweep(SkinMeshTest.mesh(black), s, ArmLayer.BLACK, edge, new Static().set(0F, 2, 1F, 0), 0, 1F, true, false);
		SkinMeshTest.Recorder red = new SkinMeshTest.Recorder();
		Loft.sweep(SkinMeshTest.mesh(red), s, ArmLayer.RED, edge, new Static().set(0F, 2, 1F, 0), 0, 1F, true, false);
		assertEquals(27 * 2, red.quads(), "the red edge band: 54 quads");
		assertEquals(27 * 5 + 3, black.quads(), "the rest, and a 3-quad cap at the habaki");
		assertEquals(192, black.quads() + red.quads(), "K1's 192 quads");
		black.assertUnitNormals();
		red.assertUnitNormals();
	}

	@Test
	void facesPointOutWhicheverWayTheSectionWinds() {
		for (boolean clockwise : new boolean[] {false, true}) {
			SkinMeshTest.Recorder r = new SkinMeshTest.Recorder();
			Loft.sweep(SkinMeshTest.mesh(r), blade(clockwise), ArmLayer.BLACK, null, null, 0, 1F, true, true);
			int sides = 27 * 7;
			assertEquals(sides + 6, r.quads());
			for (int q = 0; q < sides; q++) {
				Vector3f c = r.centre(q);
				// the section's middle is about (0.5h, 0) in (N, B) = (x, z)
				Vector3f out = new Vector3f(c.x - 0.45F * 1.65F, 0F, c.z);
				assertTrue(out.dot(r.normal(q)) > 0F, (clockwise ? "clockwise" : "counter-clockwise") + " side " + q + " faces in");
			}
			assertTrue(r.normal(sides).y < -0.99F, "the start cap faces back along the piece");
			assertTrue(r.normal(sides + 3).y > 0.99F, "the end cap faces on along it");
		}
	}

	@Test
	void ribbonIsDrawnOnce() {
		Stations s = new Stations().reset(2);
		float[] strip = {-0.25F, 0F, 0.25F, 0F};
		for (int i = 0; i < 12; i++) s.add(0F, i, 0F, 0F, 1F, 0F, 1F, 0F, 0F, i, strip, 1F);
		SkinMeshTest.Recorder r = new SkinMeshTest.Recorder();
		Loft.sweep(SkinMeshTest.mesh(r), s, ArmLayer.RED, (seg, face) -> ArmLayer.RED, null, 0, 1F, true, true);
		assertEquals(11, r.quads(), "a 12-station vein strip is 11 quads, no back face and no caps");
	}

	@Test
	void framesAreCarriedAlongWithoutTwisting() {
		// a helix: transported normals stay square to the tangent and turn smoothly from one station to the next
		Stations s = new Stations().reset(4);
		float[] ring = ArmMath.ring(new float[8], 4, 0.3F, 0.3F, ArmMath.PI / 4F);
		int n = 40;
		for (int i = 0; i < n; i++) {
			float a = 3F * ArmMath.PI * i / (n - 1);
			s.addTransported(2F * (float) Math.cos(a), 0.5F * i, 2F * (float) Math.sin(a), -2F * (float) Math.sin(a), 0.5F * (n - 1) / (3F * ArmMath.PI),
					2F * (float) Math.cos(a), i, ring, 1F);
		}
		for (int i = 0; i < n; i++) {
			Vector3f t = new Vector3f(s.tangent(i, 0), s.tangent(i, 1), s.tangent(i, 2));
			Vector3f nrm = new Vector3f(s.normal(i, 0), s.normal(i, 1), s.normal(i, 2));
			assertEquals(1F, t.length(), 1e-4F);
			assertEquals(1F, nrm.length(), 1e-4F);
			assertEquals(0F, t.dot(nrm), 1e-4F, "N square to T at " + i);
			if (i > 0) {
				Vector3f prev = new Vector3f(s.normal(i - 1, 0), s.normal(i - 1, 1), s.normal(i - 1, 2));
				assertTrue(prev.dot(nrm) > 0.9F, "N turns smoothly at " + i);
			}
		}
		assertEquals(n, s.count());
		SkinMeshTest.Recorder r = new SkinMeshTest.Recorder();
		Loft.sweep(SkinMeshTest.mesh(r), s, ArmLayer.BLACK, null, null, 0, 1F, false, false);
		assertEquals((n - 1) * 4, r.quads());
		r.assertFinite();
		r.assertUnitNormals();
	}

	@Test
	void degenerateStationsStayFinite() {
		// a tip collapsed to a point, a station with no tangent, and a hint along the tangent
		Stations s = new Stations().reset(4).hint(0F, 1F, 0F);
		float[] ring = ArmMath.ring(new float[8], 4, 1F, 1F, 0F);
		s.addTransported(0F, 0F, 0F, 0F, 1F, 0F, 0F, ring, 1F);
		s.addTransported(0F, 1F, 0F, 0F, 0F, 0F, 1F, ring, 1F);
		s.addTransported(0F, 2F, 0F, 0F, 1F, 0F, 2F, new float[8], 0F);
		SkinMeshTest.Recorder r = new SkinMeshTest.Recorder();
		Loft.sweep(SkinMeshTest.mesh(r), s, ArmLayer.BLACK, null, null, 0, 1F, true, true);
		r.assertFinite();
		r.assertUnitNormals();
		assertEquals(2 * 4 + 2, r.quads());
	}

	@Test
	void aTrimmedRunKeepsItsTextureWhereItWas() {
		// the own-view whip line trimmed off the lens (fix round 2026-11-16): the second half of a line, drawn on its own with the arc starting
		// where the cut is, has the v the same stretch had in the whole line, so the pattern doesn't slide as the cut moves
		SkinMeshTest.Recorder whole = new SkinMeshTest.Recorder(), half = new SkinMeshTest.Recorder();
		Loft.tube(SkinMeshTest.mesh(whole), STRAIGHT, 0F, 1F, 9, 4, s -> 0.5F, ArmLayer.BLACK, ArmLayer.BLACK, null, 1, 1F, 0F, 4F, UP, 1F);
		Loft.tube(SkinMeshTest.mesh(half), STRAIGHT, 0.5F, 1F, 5, 4, s -> 0.5F, ArmLayer.BLACK, ArmLayer.BLACK, null, 1, 1F, 2F, 4F, UP, 1F, 2F);
		float lastWhole = whole.vertices.get(whole.vertices.size() - 1).v(), lastHalf = half.vertices.get(half.vertices.size() - 1).v();
		assertEquals(lastWhole, lastHalf, 1e-4F, "the line's far end has the same v whether or not its near half is drawn");
		assertEquals(whole.vertices.get(16 * 4).v(), half.vertices.get(0).v(), 1e-4F, "and the cut's first quad lands on the whole line's quad at the cut");
	}

	@Test
	void eachSegmentTakesItsOwnStatic() {
		SkinMeshTest.Recorder r = new SkinMeshTest.Recorder();
		Static st = new Static().set(10F, 2, 1F, 0);
		Loft.tube(SkinMeshTest.mesh(r), STRAIGHT, 0F, 1F, 6, 4, s -> 0.5F, ArmLayer.BLACK, ArmLayer.BLACK, st, 4, 1F, 12F, 16F, UP, 1F);
		// the first corner of each segment's first face: its u is that segment's offset (the perimeter starts at 0)
		java.util.Set<Float> offsets = new java.util.HashSet<>();
		for (int seg = 0; seg < 5; seg++) offsets.add(r.vertices.get(4 * (4 * seg)).u());
		assertTrue(offsets.size() >= 4, "segments share static offsets: " + offsets);
	}
}
