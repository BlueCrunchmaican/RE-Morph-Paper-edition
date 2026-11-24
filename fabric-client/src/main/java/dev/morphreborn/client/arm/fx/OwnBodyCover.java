package dev.morphreborn.client.arm.fx;

/**
 * Whether the camera's own body stands between the camera and a point (fix round, 2026-11-16, for the rig's "whip line missing for the
 * owner in third person back"). A cast goes straight ahead, so with the camera behind and above the owner the hook lands on the camera's
 * axis a few blocks past the body: the claw is behind the torso for the whole cast and the line, running away from the lens, shows as a stub.
 * The code draws the same line for every viewer, so this is a view that cannot see it, not a missing draw; this class lets the draw say so
 * in the log (once per cast) and lets the tests pin the geometry. Vanilla's own rod line is hidden the same way.
 * <p>
 * The body is a vertical cylinder standing on {@code (ox, oy, oz)} (the owner's feet). Pure arithmetic, no game types.
 */
final class OwnBodyCover {
	private OwnBodyCover() {}

	/**
	 * Whether the straight sight line from the camera {@code C} to the point {@code P} passes through the body before it reaches {@code P}.
	 * A camera inside the body sees nothing hidden by it, and a point inside the body is in it, not behind it. A bad number is not hidden.
	 */
	static boolean hides(double cx, double cy, double cz, double px, double py, double pz, double ox, double oy, double oz, double radius, double height) {
		double dx = px - cx, dy = py - cy, dz = pz - cz;
		double ex = cx - ox, ez = cz - oz;
		if (!(dx == dx) || !(dy == dy) || !(dz == dz) || !(ex == ex) || !(ez == ez) || !(cy == cy) || !(oy == oy) || !(radius > 0.0) || !(height > 0.0)) return false;
		double lo = 0.0, hi = 1.0;
		// inside the infinite cylinder: |e + t d|^2 (horizontal) <= r^2
		double qa = dx * dx + dz * dz;
		double qc = ex * ex + ez * ez - radius * radius;
		if (qc <= 0.0 && cy >= oy && cy <= oy + height) return false; // the camera is in the body
		if (qa < 1e-12) {
			if (qc > 0.0) return false; // looking straight along the axis, beside it
		} else {
			double b = ex * dx + ez * dz;
			double disc = b * b - qa * qc;
			if (disc <= 0.0) return false;
			double root = Math.sqrt(disc);
			lo = Math.max(lo, (-b - root) / qa);
			hi = Math.min(hi, (-b + root) / qa);
		}
		// and between the feet and the head
		if (Math.abs(dy) < 1e-12) {
			if (cy < oy || cy > oy + height) return false;
		} else {
			double t0 = (oy - cy) / dy, t1 = (oy + height - cy) / dy;
			lo = Math.max(lo, Math.min(t0, t1));
			hi = Math.min(hi, Math.max(t0, t1));
		}
		// the sight line is inside the body for t in [lo, hi]: in front of P means the body hides it (hi == 1 is P inside the body)
		return lo < hi && lo > 0.0 && hi < 1.0 - 1e-9;
	}

	/**
	 * The share (0..1) of the line from {@code A} to {@code B} that the body hides from the camera, over {@code samples} evenly spaced points
	 * (at least 2). For the log's line: a claw hidden with the whole line hidden is the owner's own back view; a claw hidden with the line
	 * visible is a short stub from the hand.
	 */
	static float hiddenShare(double cx, double cy, double cz, double ax, double ay, double az, double bx, double by, double bz, double ox, double oy, double oz,
			double radius, double height, int samples) {
		int n = Math.max(2, samples);
		int hidden = 0;
		for (int i = 0; i < n; i++) {
			double s = (double) i / (n - 1);
			if (hides(cx, cy, cz, ax + (bx - ax) * s, ay + (by - ay) * s, az + (bz - az) * s, ox, oy, oz, radius, height)) hidden++;
		}
		return (float) hidden / n;
	}
}
