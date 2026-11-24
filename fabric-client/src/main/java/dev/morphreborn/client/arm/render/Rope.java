package dev.morphreborn.client.arm.render;

import org.joml.Vector3fc;

/**
 * A short hanging strand with real inertia: the katana's sageo tail and anything else that should swing, lag behind a
 * swing and settle. A chain of nodes, the first pinned to its anchor, integrated as position Verlet (each node keeps
 * its velocity as the distance it moved last step) with gravity and damping, then pulled back to its link lengths by a
 * few constraint passes. The renderer steps it once a frame, in {@code submit()}, never inside a geometry lambda, and
 * hands the lambdas a copy of the nodes ({@link #copyNodes}).
 * <p>
 * Units are the caller's: positions, link length and gravity all in the space it simulates in (camera-relative
 * world blocks for third person; the hand pass's world-aligned space in first person, so a camera turn swings the
 * tail). Time is in ticks, and a frame's step is clamped to 0..2 ticks: a long hitch can't fling it, and a step of 0
 * (a paused game) only holds it in shape. Damping is per tick ({@code damping^dt} per step), so it settles at the same
 * pace at any frame rate. Speeds are capped too, so an anchor that jumps (a teleport the caller didn't reset for) can't
 * whip the rope off to infinity.
 */
public final class Rope {
	/** Shared constraint passes per step (the spec's 3), before the exact pass from the anchor. */
	private static final int PASSES = 3;
	/** The largest node speed, in links per tick. */
	private static final float MAX_SPEED_LINKS = 6F;

	private final int nodes;
	private final float segLen;
	private final float[] p, prev, vel;
	private boolean started;
	/** World down as last seen, for laying the rope out on a reset. */
	private float downX, downY = -1F, downZ;

	public Rope(int nodes, float segLen) {
		this.nodes = Math.max(2, nodes);
		this.segLen = Math.max(1e-4F, segLen);
		p = new float[3 * this.nodes];
		prev = new float[3 * this.nodes];
		vel = new float[3 * this.nodes];
	}

	public int nodes() {
		return nodes;
	}

	public float segLen() {
		return segLen;
	}

	/** Puts the rope at rest, hanging straight down (as last seen) from {@code root}. */
	public void reset(Vector3fc root) {
		for (int i = 0; i < nodes; i++) {
			p[3 * i] = root.x() + downX * segLen * i;
			p[3 * i + 1] = root.y() + downY * segLen * i;
			p[3 * i + 2] = root.z() + downZ * segLen * i;
			vel[3 * i] = vel[3 * i + 1] = vel[3 * i + 2] = 0F;
		}
		started = true;
	}

	/**
	 * Moves the anchor to {@code root} and advances {@code dt} ticks under {@code gravity} (units per tick², e.g.
	 * world down × 0.35/16 blocks), keeping {@code damping} of its speed per tick (0.86 for the tail, 0.6 when limp).
	 */
	public void step(Vector3fc root, Vector3fc gravity, float dt, float damping) {
		float gl = (float) Math.sqrt(gravity.x() * gravity.x() + gravity.y() * gravity.y() + gravity.z() * gravity.z());
		if (gl > 1e-9F && Float.isFinite(gl)) {
			downX = gravity.x() / gl;
			downY = gravity.y() / gl;
			downZ = gravity.z() / gl;
		}
		if (!started || !Float.isFinite(p[3])) reset(root);
		float h = Float.isFinite(dt) ? ArmMath.clamp(dt, 0F, 2F) : 0F;
		p[0] = root.x();
		p[1] = root.y();
		p[2] = root.z();
		System.arraycopy(p, 0, prev, 0, p.length);
		if (h > 0F) {
			float keep = (float) Math.pow(ArmMath.clamp01(damping), h);
			float gx = gravity.x() * h, gy = gravity.y() * h, gz = gravity.z() * h;
			for (int i = 1; i < nodes; i++) {
				int o = 3 * i;
				vel[o] = vel[o] * keep + gx;
				vel[o + 1] = vel[o + 1] * keep + gy;
				vel[o + 2] = vel[o + 2] * keep + gz;
				p[o] += vel[o] * h;
				p[o + 1] += vel[o + 1] * h;
				p[o + 2] += vel[o + 2] * h;
			}
		}
		constrain();
		if (h > 1e-3F) {
			float max = MAX_SPEED_LINKS * segLen;
			for (int i = 1; i < nodes; i++) {
				int o = 3 * i;
				float vx = (p[o] - prev[o]) / h, vy = (p[o + 1] - prev[o + 1]) / h, vz = (p[o + 2] - prev[o + 2]) / h;
				float sp = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
				if (sp > max) {
					float k = max / sp;
					vx *= k;
					vy *= k;
					vz *= k;
				}
				vel[o] = vx;
				vel[o + 1] = vy;
				vel[o + 2] = vz;
			}
		}
	}

	/**
	 * Each link back to its length. The passes share each correction between both ends of a link (the anchored end of
	 * the first never moves), so a swinging tip tugs on the nodes above it; then one last pass from the anchor out sets
	 * every link exactly, moving only its outer end. Without it the tail would stretch: at a 2-tick step gravity alone
	 * drops a node most of a link's length, more than the shared passes take back.
	 */
	private void constrain() {
		for (int pass = 0; pass < PASSES; pass++) {
			for (int i = 0; i + 1 < nodes; i++) link(i, i > 0);
		}
		for (int i = 0; i + 1 < nodes; i++) link(i, false);
	}

	/** Link {@code i} to its length: shared between its ends, or ({@code share} false) by moving its outer end only. */
	private void link(int i, boolean share) {
		int a = 3 * i, b = a + 3;
		float dx = p[b] - p[a], dy = p[b + 1] - p[a + 1], dz = p[b + 2] - p[a + 2];
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (!(len > 1e-7F)) {
			// two nodes on one spot have no direction to part in: hang the outer one straight down
			p[b] = p[a] + downX * segLen;
			p[b + 1] = p[a + 1] + downY * segLen;
			p[b + 2] = p[a + 2] + downZ * segLen;
			return;
		}
		float diff = (len - segLen) / len;
		if (share) {
			float half = diff * 0.5F;
			p[a] += dx * half;
			p[a + 1] += dy * half;
			p[a + 2] += dz * half;
			p[b] -= dx * half;
			p[b + 1] -= dy * half;
			p[b + 2] -= dz * half;
		} else {
			p[b] -= dx * diff;
			p[b + 1] -= dy * diff;
			p[b + 2] -= dz * diff;
		}
	}

	/**
	 * A sudden shove (units per tick), strongest at the free end: the tail's flick when a heal arrives along the blade.
	 */
	public void impulse(float vx, float vy, float vz) {
		for (int i = 1; i < nodes; i++) {
			float w = i / (float) (nodes - 1);
			vel[3 * i] += vx * w;
			vel[3 * i + 1] += vy * w;
			vel[3 * i + 2] += vz * w;
		}
	}

	/** The nodes' positions into {@code out} (x, y, z each; at least {@code 3·nodes} long), anchor first. */
	public void copyNodes(float[] out) {
		System.arraycopy(p, 0, out, 0, Math.min(p.length, out.length));
	}

	/**
	 * Where a rope of {@code nodes} links of {@code segLen} lies when nothing simulates it (GUI icons, the preview at
	 * rest): leaving {@code root} along {@code out} and curving down along {@code down}, as a quadratic Bezier, into
	 * {@code dst} in the same layout as {@link #copyNodes}. Both directions should be unit length.
	 */
	public static void droop(Vector3fc root, Vector3fc out, Vector3fc down, int nodes, float segLen, float[] dst) {
		int n = Math.max(2, nodes);
		float len = segLen * (n - 1);
		float bx = root.x() + out.x() * len * 0.5F, by = root.y() + out.y() * len * 0.5F, bz = root.z() + out.z() * len * 0.5F;
		float cx = root.x() + (out.x() + down.x()) * len * 0.6F, cy = root.y() + (out.y() + down.y()) * len * 0.6F, cz = root.z() + (out.z() + down.z()) * len * 0.6F;
		for (int i = 0; i < n && 3 * i + 2 < dst.length; i++) {
			float s = i / (float) (n - 1), u = 1F - s;
			float wa = u * u, wb = 2F * u * s, wc = s * s;
			dst[3 * i] = root.x() * wa + bx * wb + cx * wc;
			dst[3 * i + 1] = root.y() * wa + by * wb + cy * wc;
			dst[3 * i + 2] = root.z() * wa + bz * wb + cz * wc;
		}
	}
}
