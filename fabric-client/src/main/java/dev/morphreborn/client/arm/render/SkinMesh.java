package dev.morphreborn.client.arm.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Writes arm-form geometry straight into a vertex consumer, in the ENTITY vertex layout (position, colour, uv0,
 * overlay, light, normal) and in the same order {@code ModelPart.Cube.compile} writes a cube, so a box drawn here looks
 * exactly like a model box. It exists because the old route, {@code ModelCapture.render}, builds a new
 * {@code ModelPart.Cube} (about 47 objects) for every box every frame; an arm is about a thousand quads, so this one
 * writes them with no allocation at all.
 * <p>
 * Units: whatever the caller's geometry is in (the arm forms work in model pixels), written exactly as given and then
 * transformed by the pose; the pose carries the 1/16 that turns pixels into blocks, as the item and sheath transforms
 * do. Texture coordinates are in units of the 64-unit skin sheet that every morph-skin part samples (4 texels a unit on
 * the 256 images), and wrap (the textures repeat).
 * <p>
 * Colour: the vertex alpha carries the dissolve value (0 erodes away, 1 stays; only the dissolve render type reads it,
 * so pass 1 when dissolve is off), and the RGB carries brightness, which can only darken the texture (255 is full).
 * <p>
 * Face normals are worked out from each quad's own corners, {@code normalize((p2 − p0) × (p3 − p1))}, after every local
 * transform, then turned by the pose's normal matrix: bent, tapered and squashed pieces all light correctly, and a
 * quad's winding decides which way it faces (the cube's winding faces out). The arm's render types light each face
 * by which side of it the camera sees (PER_FACE_LIGHTING: the back of a face is lit as if its normal were reversed),
 * so a normal that always follows the winding stays right even through a local matrix that mirrors the geometry. A
 * quad with no area (a collapsed tip) keeps the last normal written.
 * <p>
 * Render thread only: there is one shared instance, re-bound by {@link #begin} at the start of every geometry lambda.
 * The renderer runs those lambdas one after another, never interleaved, so the shared scratch is never in use twice.
 */
public final class SkinMesh {
	/** Texture units across the skin sheet: UVs are divided by this, as a 64×64 model texture's are. */
	public static final float SHEET = 64F;
	/** Full brightness: the texture as it is. */
	public static final int WHITE = 0xFFFFFF;
	/** Below this a box's {@code w + h + d} (or a frustum's length and widths) counts as nothing, and is skipped. */
	private static final float MIN_SIZE = 0.001F;

	private static final SkinMesh SHARED = new SkinMesh();

	// Cube.compile's corners: t0..t3 on the min-Z face, l0..l3 on the max-Z face.
	private static final int T0 = 0, T1 = 1, T2 = 2, T3 = 3, L0 = 4, L1 = 5, L2 = 6, L3 = 7;

	private PoseStack.@Nullable Pose pose;
	private @Nullable VertexConsumer out;
	private int light;
	private int overlay;
	private int tint = WHITE;
	private int quads;
	/** {@link #transform}'s matrix, applied before everything's own local matrix. */
	private final Matrix4f base = new Matrix4f();
	private boolean based;
	/** The matrix taking the current box or frustum into the pose's space: base · local. */
	private final Matrix4f model = new Matrix4f();
	private boolean modelIdentity;

	private final float[] corner = new float[24];
	private final float[] cornerAlpha = new float[8];
	private final float[] qp = new float[12];
	private final float[] quv = new float[8];
	private final float[] qa = new float[4];
	private final int[] qrgb = new int[4];
	private final Vector3f scratch = new Vector3f();
	private final Vector3f normal = new Vector3f();
	private float lastNx, lastNy = 1F, lastNz;

	private SkinMesh() {}

	/**
	 * Binds the shared mesh to one geometry lambda's pose and buffer, with the light (use {@link ArmTex#FULL_BRIGHT}
	 * for {@link ArmLayer#GLOW}) and overlay every vertex gets. Clears {@link #transform} and {@link #tint} and the
	 * quad count.
	 */
	public static SkinMesh begin(PoseStack.Pose pose, VertexConsumer vc, int light, int overlay) {
		SkinMesh m = SHARED;
		m.pose = pose;
		m.out = vc;
		m.light = light;
		m.overlay = overlay;
		m.tint = WHITE;
		m.quads = 0;
		m.based = false;
		m.lastNx = 0F;
		m.lastNy = 1F;
		m.lastNz = 0F;
		return m;
	}

	/**
	 * The pose this mesh was begun on: the space everything is drawn in, taken to the world's (in the world pass, arm px to blocks
	 * from the camera). For a piece that must aim at a world direction (the spyglass's eye); null before {@link #begin}.
	 */
	public PoseStack.@Nullable Pose pose() {
		return pose;
	}

	/**
	 * A matrix applied to everything drawn from now on, before each piece's own local matrix (null: none). Lets a whole
	 * form be scaled, nudged or squashed (a spasm, a recoil) without the geometry code knowing; the normals follow.
	 */
	public SkinMesh transform(@Nullable Matrix4fc m) {
		if (m == null) {
			based = false;
		} else {
			base.set(m);
			based = true;
		}
		return this;
	}

	/** The brightness lofts and tendrils are drawn with (they take no colour of their own); white until set. */
	public SkinMesh tint(int rgb) {
		tint = rgb & 0xFFFFFF;
		return this;
	}

	public int tint() {
		return tint;
	}

	/** Quads written since {@link #begin}: for the budget tests and the preview's counter. */
	public int quads() {
		return quads;
	}

	/**
	 * One quad: {@code p12} its four corners (x, y, z each) in the current space ({@link #transform} applies),
	 * {@code uv8} their texture coordinates in sheet units, {@code alpha4} their dissolve alphas.
	 */
	public SkinMesh quad(float[] p12, float[] uv8, float[] alpha4, int rgb) {
		int c = rgb & 0xFFFFFF;
		qrgb[0] = c;
		qrgb[1] = c;
		qrgb[2] = c;
		qrgb[3] = c;
		return quad(p12, uv8, alpha4, qrgb);
	}

	/** As {@link #quad(float[], float[], float[], int)}, with a brightness per corner (a pulse's flash along a vein). */
	public SkinMesh quad(float[] p12, float[] uv8, float[] alpha4, int[] rgb4) {
		for (int i = 0; i < 4; i++) {
			float x = p12[3 * i], y = p12[3 * i + 1], z = p12[3 * i + 2];
			if (based) {
				base.transformPosition(x, y, z, scratch);
				x = scratch.x;
				y = scratch.y;
				z = scratch.z;
			}
			qp[3 * i] = x;
			qp[3 * i + 1] = y;
			qp[3 * i + 2] = z;
			quv[2 * i] = uv8[2 * i];
			quv[2 * i + 1] = uv8[2 * i + 1];
			qa[i] = alpha4[i];
		}
		if (rgb4 != qrgb) {
			for (int i = 0; i < 4; i++) qrgb[i] = rgb4[i] & 0xFFFFFF;
		}
		write();
		return this;
	}

	/**
	 * A box from ({@code x, y, z}) sized {@code w × h × d}, through {@code local} (null: none): six quads, unwrapped onto
	 * the sheet exactly as a model cube's are, from ({@code u0, v0}), its sizes times {@code uvScale}. Skipped when
	 * {@code w + h + d} is under 0.001 (an empty box would only cost vertices).
	 */
	public SkinMesh box(@Nullable Matrix4fc local, float x, float y, float z, float w, float h, float d, float u0, float v0, float uvScale, float alpha, int rgb) {
		if (!(w + h + d >= MIN_SIZE)) return this; // also skips NaN sizes
		model(local);
		float x1 = x + w, y1 = y + h, z1 = z + d;
		corner(T0, x, y, z, alpha);
		corner(T1, x1, y, z, alpha);
		corner(T2, x1, y1, z, alpha);
		corner(T3, x, y1, z, alpha);
		corner(L0, x, y, z1, alpha);
		corner(L1, x1, y, z1, alpha);
		corner(L2, x1, y1, z1, alpha);
		corner(L3, x, y1, z1, alpha);
		cube(u0, v0, w * uvScale, h * uvScale, d * uvScale, rgb);
		return this;
	}

	/**
	 * A box tapered along +Z through {@code local} (null: none): {@code w0 × h0} centred on the axis at z = 0 to
	 * {@code w1 × h1} at z = {@code len}, four sides and two caps (24 vertices), with the dissolve alpha running from
	 * {@code a0} to {@code a1}. Tapered pieces are what make thin tips and threads read as points rather than steps.
	 * Unwrapped like a box of its widest section. Skipped when its length and widths add up to under 0.001.
	 */
	public SkinMesh frustum(@Nullable Matrix4fc local, float len, float w0, float h0, float w1, float h1, float u0, float v0, float uvScale, float a0, float a1, int rgb) {
		float wMax = Math.max(w0, w1), hMax = Math.max(h0, h1);
		if (!(len + wMax + hMax >= MIN_SIZE)) return this;
		model(local);
		float hw0 = w0 / 2F, hh0 = h0 / 2F, hw1 = w1 / 2F, hh1 = h1 / 2F;
		corner(T0, -hw0, -hh0, 0F, a0);
		corner(T1, hw0, -hh0, 0F, a0);
		corner(T2, hw0, hh0, 0F, a0);
		corner(T3, -hw0, hh0, 0F, a0);
		corner(L0, -hw1, -hh1, len, a1);
		corner(L1, hw1, -hh1, len, a1);
		corner(L2, hw1, hh1, len, a1);
		corner(L3, -hw1, hh1, len, a1);
		cube(u0, v0, wMax * uvScale, hMax * uvScale, len * uvScale, rgb);
		return this;
	}

	private void model(@Nullable Matrix4fc local) {
		if (based) {
			model.set(base);
			if (local != null) model.mul(local);
			modelIdentity = false;
		} else if (local != null) {
			model.set(local);
			modelIdentity = false;
		} else {
			modelIdentity = true;
		}
	}

	private void corner(int i, float x, float y, float z, float alpha) {
		if (!modelIdentity) {
			model.transformPosition(x, y, z, scratch);
			x = scratch.x;
			y = scratch.y;
			z = scratch.z;
		}
		corner[3 * i] = x;
		corner[3 * i + 1] = y;
		corner[3 * i + 2] = z;
		cornerAlpha[i] = alpha;
	}

	/**
	 * The eight corners as a model cube's six faces, in its order (down, up, west, north, east, south), with its
	 * vertex order and its texture unwrap: {@code w, h, d} are the box's sizes in sheet units.
	 */
	private void cube(float u, float v, float w, float h, float d, int rgb) {
		float u0 = u, u1 = u + d, u2 = u + d + w, u22 = u + d + w + w, u3 = u + d + w + d, u4 = u + d + w + d + w;
		float v0 = v, v1 = v + d, v2 = v + d + h;
		int c = rgb & 0xFFFFFF;
		for (int i = 0; i < 4; i++) qrgb[i] = c;
		face(L1, L0, T0, T1, u1, v0, u2, v1);
		face(T2, T3, L3, L2, u2, v1, u22, v0);
		face(T0, L0, L3, T3, u0, v1, u1, v2);
		face(T1, T0, T3, T2, u1, v1, u2, v2);
		face(L1, T1, T2, L2, u2, v1, u3, v2);
		face(L0, L1, L2, L3, u3, v1, u4, v2);
	}

	/** One cube face, its corners mapped as {@code ModelPart.Polygon} maps them: (a1,b0) (a0,b0) (a0,b1) (a1,b1). */
	private void face(int c0, int c1, int c2, int c3, float a0, float b0, float a1, float b1) {
		load(0, c0);
		load(1, c1);
		load(2, c2);
		load(3, c3);
		quv[0] = a1;
		quv[1] = b0;
		quv[2] = a0;
		quv[3] = b0;
		quv[4] = a0;
		quv[5] = b1;
		quv[6] = a1;
		quv[7] = b1;
		write();
	}

	private void load(int slot, int c) {
		qp[3 * slot] = corner[3 * c];
		qp[3 * slot + 1] = corner[3 * c + 1];
		qp[3 * slot + 2] = corner[3 * c + 2];
		qa[slot] = cornerAlpha[c];
	}

	/** Writes the quad in the scratch arrays (corners already in the pose's space). */
	private void write() {
		VertexConsumer vc = out;
		PoseStack.Pose p = pose;
		if (vc == null || p == null) return;
		float ax = qp[6] - qp[0], ay = qp[7] - qp[1], az = qp[8] - qp[2];
		float bx = qp[9] - qp[3], by = qp[10] - qp[4], bz = qp[11] - qp[5];
		float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
		Matrix3f nm = p.normal();
		nm.transform(nx, ny, nz, normal);
		float len2 = normal.lengthSquared();
		if (len2 > 1e-30F && Float.isFinite(len2)) {
			float inv = 1F / (float) Math.sqrt(len2);
			lastNx = normal.x * inv;
			lastNy = normal.y * inv;
			lastNz = normal.z * inv;
		}
		Matrix4f pm = p.pose();
		for (int i = 0; i < 4; i++) {
			pm.transformPosition(qp[3 * i], qp[3 * i + 1], qp[3 * i + 2], scratch);
			int a = Math.round(ArmMath.clamp01(qa[i]) * 255F);
			vc.addVertex(scratch.x, scratch.y, scratch.z, (a << 24) | qrgb[i], quv[2 * i] / SHEET, quv[2 * i + 1] / SHEET, overlay, light, lastNx, lastNy, lastNz);
		}
		quads++;
	}
}
