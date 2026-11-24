package dev.morphreborn.client.arm.fx;

/**
 * Keeps world effects off the lens in your own first-person view (the rig, 2026-11-16: a translucent grey ghost of the bolt's trail
 * for about three frames after a loose, and a red slab, one shard of the mace's landing, across the view after a smash). A tendril
 * or a shard a few hundredths of a block from the eye fills the screen whatever its size; in third person nobody's camera is ever
 * there. The effects that start at or near the owner (a bolt's trail runs back through the bow and the eye, the landing's shards
 * fly up past them) trim or fade what comes within a clearance sphere round the camera, in their own first-person view only.
 * <p>
 * Pure arithmetic, no game types, so the unit tests can check it.
 */
final class NearCamera {
	/**
	 * The clearance round your own eye (blocks) and the band over which a piece fades in beyond it, for the burst pieces (shards, clods, the
	 * wound knot, the crown's teeth, a bolt's landing burst). Fix round 2026-11-16: it was 1.4 + 0.9, larger than the eye's height over the
	 * ground (about 1.62 standing, 1.27 sneaking), so your own smash and hit feedback shrank away; now a piece is whole by 1.0 block, which
	 * still keeps a lens-filling shard off the eye (a shard is ~0.07 block wide). Unconfirmed on the rig.
	 */
	static final double CLEAR = 0.5, BAND = 0.5;
	/**
	 * How far a bolt's trail keeps from your own eye: the trail is one long tube running back through the bow and the eye, so it keeps the
	 * wider clearance (it is a cut in length, not a fade, so it costs nothing to see).
	 */
	static final double TRAIL_CLEAR = 1.4;

	private NearCamera() {}

	/**
	 * How much of a piece {@code dist} from the camera stays: 0 within {@code clear}, 1 beyond {@code clear + band}, smooth between
	 * (a shard shrinks away as it nears the eye rather than blinking out). A non-number distance counts as far.
	 */
	static float fade(double dist, double clear, double band) {
		if (!(dist == dist)) return 1F;
		if (dist <= clear) return 0F;
		if (band <= 0.0 || dist >= clear + band) return 1F;
		double x = (dist - clear) / band;
		return (float) (x * x * (3.0 - 2.0 * x));
	}

	/**
	 * The part of the straight line from A to B (both relative to the camera) that stays outside the sphere of radius {@code clear} round the
	 * camera, as the two ends of the line's own 0..1 run in {@code out[0]}, {@code out[1]} ({@code out[0] >= out[1]}: none of it). A line the
	 * sphere cuts in the middle leaves two pieces; the longer one is kept. For your own whip's line (the rig, 2026-11-16, with First-person
	 * Model: its body's whip tip is half a block from the lens and the new hook starts at the eye, so the line, a tube of a tenth of a block
	 * at the whip, filled the view for a frame). A bad number leaves the whole line.
	 */
	static void outsideSphere(double ax, double ay, double az, double bx, double by, double bz, double clear, double[] out) {
		out[0] = 0.0;
		out[1] = 1.0;
		double dx = bx - ax, dy = by - ay, dz = bz - az;
		double qa = dx * dx + dy * dy + dz * dz;
		double c = ax * ax + ay * ay + az * az - clear * clear;
		if (!(qa == qa) || !(c == c) || !(clear > 0.0)) return;
		if (qa < 1e-12) {
			if (c <= 0.0) out[1] = 0.0; // a point inside
			return;
		}
		double b = ax * dx + ay * dy + az * dz;
		double disc = b * b - qa * c;
		if (disc <= 0.0) return; // it passes beside the sphere
		double root = Math.sqrt(disc);
		double r1 = (-b - root) / qa, r2 = (-b + root) / qa; // the line is inside the sphere for s in [r1, r2]
		if (r2 <= 0.0 || r1 >= 1.0) return; // the sphere is off either end of the line
		double left = r1 > 0.0 ? r1 : 0.0, right = r2 < 1.0 ? 1.0 - r2 : 0.0;
		if (left >= right) {
			out[0] = 0.0;
			out[1] = left;
		} else {
			out[0] = 1.0 - right;
			out[1] = 1.0;
		}
	}

	/**
	 * How long a trail may be, running back from the arrow along the unit direction {@code (dx, dy, dz)} (the way the arrow flies, so the
	 * trail lies along {@code -d}) without coming within {@code clear} of the camera: {@code len} when it never does, the length up to
	 * where it enters the sphere when it passes through it, 0 when the arrow itself is inside. {@code (ax, ay, az)} is the arrow's place
	 * relative to the camera.
	 */
	static double trailLength(double ax, double ay, double az, double dx, double dy, double dz, double len, double clear) {
		if (!(len > 0.0)) return 0.0;
		double c = ax * ax + ay * ay + az * az - clear * clear;
		if (!(c == c)) return len;
		if (c <= 0.0) return 0.0;
		// |a - d t|^2 = clear^2 along t >= 0: t^2 - 2 b t + c = 0, b = d.a; the trail first meets the sphere at b - sqrt(b^2 - c)
		double b = dx * ax + dy * ay + dz * az;
		if (b <= 0.0) return len; // the trail runs away from the camera
		double disc = b * b - c;
		if (disc <= 0.0) return len; // it passes beside the sphere
		return Math.clamp(b - Math.sqrt(disc), 0.0, len);
	}
}
