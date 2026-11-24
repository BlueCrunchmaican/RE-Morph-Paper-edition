package dev.morphreborn.client.arm.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * The emitter every arm piece goes through: vertex counts, outward unit normals, the cube's own texture unwrap, the
 * skipping of empty pieces, colour packing, and the drips built on it.
 */
class SkinMeshTest {
	/** A vertex consumer that keeps every vertex it is given, for the tests to look at. */
	static final class Recorder implements VertexConsumer {
		record Vertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {}

		final List<Vertex> vertices = new ArrayList<>();

		@Override
		public void addVertex(float x, float y, float z, int color, float u, float v, int overlayCoords, int lightCoords, float nx, float ny, float nz) {
			vertices.add(new Vertex(x, y, z, color, u, v, overlayCoords, lightCoords, nx, ny, nz));
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			throw new UnsupportedOperationException("SkinMesh writes whole vertices");
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
			return vertices.size() / 4;
		}

		/** Quad {@code q}'s centre. */
		Vector3f centre(int q) {
			Vector3f c = new Vector3f();
			for (int i = 0; i < 4; i++) {
				Vertex v = vertices.get(4 * q + i);
				c.add(v.x, v.y, v.z);
			}
			return c.div(4F);
		}

		Vector3f normal(int q) {
			Vertex v = vertices.get(4 * q);
			return new Vector3f(v.nx, v.ny, v.nz);
		}

		void assertFinite() {
			for (Vertex v : vertices) {
				for (float f : new float[] {v.x, v.y, v.z, v.u, v.v, v.nx, v.ny, v.nz}) assertTrue(Float.isFinite(f), "a vertex value isn't a number: " + v);
			}
		}

		void assertUnitNormals() {
			for (Vertex v : vertices) assertEquals(1F, (float) Math.sqrt(v.nx * v.nx + v.ny * v.ny + v.nz * v.nz), 1e-4F, "normal length at " + v);
		}
	}

	static SkinMesh mesh(Recorder r) {
		return SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0);
	}

	@Test
	void boxIsTwentyFourVerticesFacingOut() {
		Recorder r = new Recorder();
		SkinMesh m = mesh(r);
		m.box(null, -1F, -2F, -3F, 2F, 4F, 6F, 0F, 0F, 1F, 1F, SkinMesh.WHITE);
		assertEquals(24, r.vertices.size());
		assertEquals(6, m.quads());
		r.assertUnitNormals();
		for (int q = 0; q < 6; q++) {
			// the box is centred on the origin, so a face's centre points the way it faces
			Vector3f c = r.centre(q);
			assertTrue(c.dot(r.normal(q)) > 0F, "face " + q + " faces in");
			for (int i = 0; i < 4; i++) {
				Recorder.Vertex v = r.vertices.get(4 * q + i);
				assertEquals(c.dot(r.normal(q)), new Vector3f(v.x, v.y, v.z).dot(r.normal(q)), 1e-4F, "face " + q + " isn't flat");
			}
		}
	}

	@Test
	void frustumIsSixteenSideAndEightCapVertices() {
		Recorder r = new Recorder();
		SkinMesh m = mesh(r);
		m.frustum(null, 5F, 2F, 2F, 0.5F, 0.5F, 3F, 7F, 1F, 1F, 0.25F, SkinMesh.WHITE);
		assertEquals(16 + 8, r.vertices.size());
		r.assertUnitNormals();
		// the four sides lean in toward the narrow end, and still face out
		Vector3f axisMid = new Vector3f(0F, 0F, 2.5F);
		for (int q = 0; q < 6; q++) assertTrue(new Vector3f(r.centre(q)).sub(axisMid).dot(r.normal(q)) > 0F, "face " + q + " faces in");
		// alpha runs from the wide end (a0) to the narrow end (a1)
		for (Recorder.Vertex v : r.vertices) assertEquals(v.z < 1e-4F ? 255 : 64, v.color >>> 24, "alpha at z " + v.z);
	}

	@Test
	void emptyPiecesAreSkipped() {
		Recorder r = new Recorder();
		SkinMesh m = mesh(r);
		m.box(null, 1F, 1F, 1F, 0F, 0F, 0F, 0F, 0F, 1F, 1F, SkinMesh.WHITE);
		m.box(null, 1F, 1F, 1F, 0.0003F, 0.0003F, 0.0003F, 0F, 0F, 1F, 1F, SkinMesh.WHITE);
		m.box(null, 0F, 0F, 0F, Float.NaN, 1F, 1F, 0F, 0F, 1F, 1F, SkinMesh.WHITE);
		m.frustum(null, 0F, 0F, 0F, 0F, 0F, 0F, 0F, 1F, 1F, 1F, SkinMesh.WHITE);
		assertEquals(0, r.vertices.size());
		m.box(null, 0F, 0F, 0F, 0.001F, 0F, 0F, 0F, 0F, 1F, 1F, SkinMesh.WHITE); // a flat sliver still counts
		assertEquals(24, r.vertices.size());
		r.assertFinite();
		r.assertUnitNormals();
	}

	@Test
	void boxUnwrapsLikeAModelCube() {
		// the cube's own layout for an 8 x 4 x 2 box at sheet offset (10, 20): down/up are w x d, the sides d/w x h
		Recorder r = new Recorder();
		mesh(r).box(null, 0F, 0F, 0F, 8F, 4F, 2F, 10F, 20F, 1F, 1F, SkinMesh.WHITE);
		float[][] expected = {
				// DOWN: (u1,v0)-(u2,v1) with u1 = 12, u2 = 20, v0 = 20, v1 = 22
				{20, 20, 12, 20, 12, 22, 20, 22},
				// UP: (u2,v1)-(u22,v0) with u22 = 28
				{28, 22, 20, 22, 20, 20, 28, 20},
				// WEST: (u0,v1)-(u1,v2) with v2 = 26
				{12, 22, 10, 22, 10, 26, 12, 26},
				// NORTH: (u1,v1)-(u2,v2)
				{20, 22, 12, 22, 12, 26, 20, 26},
				// EAST: (u2,v1)-(u3,v2) with u3 = 22
				{22, 22, 20, 22, 20, 26, 22, 26},
				// SOUTH: (u3,v1)-(u4,v2) with u4 = 30
				{30, 22, 22, 22, 22, 26, 30, 26}};
		for (int q = 0; q < 6; q++) {
			for (int i = 0; i < 4; i++) {
				Recorder.Vertex v = r.vertices.get(4 * q + i);
				assertEquals(expected[q][2 * i] / SkinMesh.SHEET, v.u, 1e-6F, "u of face " + q + " corner " + i);
				assertEquals(expected[q][2 * i + 1] / SkinMesh.SHEET, v.v, 1e-6F, "v of face " + q + " corner " + i);
			}
		}
		// and in the cube's face order: down, up, west, north, east, south
		float[][] facing = {{0, -1, 0}, {0, 1, 0}, {-1, 0, 0}, {0, 0, -1}, {1, 0, 0}, {0, 0, 1}};
		for (int q = 0; q < 6; q++) {
			Vector3f n = r.normal(q);
			assertEquals(facing[q][0], n.x, 1e-5F);
			assertEquals(facing[q][1], n.y, 1e-5F);
			assertEquals(facing[q][2], n.z, 1e-5F);
		}
	}

	@Test
	void localTransformsAndThePoseAreApplied() {
		Recorder r = new Recorder();
		PoseStack stack = new PoseStack();
		stack.scale(1F / 16F, 1F / 16F, 1F / 16F);
		SkinMesh m = SkinMesh.begin(stack.last(), r, 7, 9);
		m.transform(new Matrix4f().translation(16F, 0F, 0F));
		m.box(new Matrix4f().rotationZ((float) Math.PI / 2F), 0F, 0F, 0F, 1F, 1F, 1F, 0F, 0F, 1F, 1F, SkinMesh.WHITE);
		r.assertUnitNormals();
		for (Recorder.Vertex v : r.vertices) {
			// rotated a quarter turn about Z (x' = -y), moved 16 px along X, then scaled to blocks
			assertTrue(v.x >= (16F - 1F) / 16F - 1e-5F && v.x <= 16F / 16F + 1e-5F, "x " + v.x);
			assertTrue(v.y >= -1e-5F && v.y <= 1F / 16F + 1e-5F, "y " + v.y);
			assertEquals(7, v.light);
			assertEquals(9, v.overlay);
		}
		m.transform(null);
		int before = r.vertices.size();
		m.box(null, 0F, 0F, 0F, 1F, 1F, 1F, 0F, 0F, 1F, 1F, SkinMesh.WHITE);
		for (int i = before; i < r.vertices.size(); i++) assertTrue(r.vertices.get(i).x <= 1F / 16F + 1e-5F, "transform(null) clears it");
	}

	@Test
	void colourCarriesAlphaAndBrightness() {
		Recorder r = new Recorder();
		SkinMesh m = mesh(r);
		float[] p = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};
		float[] uv = new float[8];
		m.quad(p, uv, new float[] {0F, 0.5F, 1F, 2F}, 0x80FF40);
		assertEquals(0x0080FF40, r.vertices.get(0).color);
		assertEquals(0x8080FF40, r.vertices.get(1).color);
		assertEquals(0xFF80FF40, r.vertices.get(2).color);
		assertEquals(0xFF80FF40, r.vertices.get(3).color, "alpha is clamped to 1");
		// a quad with no area keeps the last normal instead of dividing by zero
		m.quad(new float[12], uv, new float[] {1, 1, 1, 1}, SkinMesh.WHITE);
		r.assertFinite();
		r.assertUnitNormals();
		assertEquals(r.normal(0), r.normal(1));
	}

	@Test
	void dripsGatherThenFall() {
		Static st = new Static().set(0F, 2, 1F, 0);
		Vector3f down = new Vector3f(0F, -1F, 0F);
		int gathering = 0, falling = 0;
		for (int t = 0; t < 200; t++) {
			Recorder r = new Recorder();
			Drips.emit(mesh(r), null, 0F, 0F, 0F, down, t, 0, 1F, 16F, st, 3);
			r.assertFinite();
			r.assertUnitNormals();
			assertTrue(r.quads() == 12 || r.quads() == 6 || r.quads() == 0, "a drip is a thread and a bead, or a falling bead: " + r.quads());
			if (r.quads() == 12) gathering++;
			if (r.quads() == 6) {
				falling++;
				// a falling bead is below the thread's full length, never above where it hung
				for (Recorder.Vertex v : r.vertices) assertTrue(v.y < -2F, "bead at " + v.y);
			}
			for (Recorder.Vertex v : r.vertices) assertTrue(v.y <= 0.36F, "nothing rises above the emitter: " + v.y);
		}
		assertTrue(gathering > 0 && falling > 0, gathering + " gathering, " + falling + " falling");
	}
}
