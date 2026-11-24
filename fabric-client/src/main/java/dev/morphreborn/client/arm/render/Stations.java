package dev.morphreborn.client.arm.render;

/**
 * The cross-sections a {@link Loft} sweeps: each station is a centre, a frame (T along the piece, N and B across it),
 * a polygon in that frame (the same number of points at every station), its arc position v and its dissolve alpha.
 * A blade is 28 stations of a 7-point section; a tube is a ring of 4, 6 or 8.
 * <p>
 * Frames: {@link #add} takes T and N as given (T need not be unit length; N is made square to T, the
 * {@code N − (N·T)T} step), and B = T × N, so (N, B, T) is right-handed like (x, y, z). {@link #addTransported}
 * carries the previous station's N onto the new T instead: parallel transport, which keeps a bending tube from twisting.
 * <p>
 * Polygons are (n, b) pairs, normally counter-clockwise seen from +T. The loft works out the winding of each one and
 * faces every quad outward either way, so a section written the other way round still lights correctly. Two points
 * make a flat ribbon, drawn once (nothing is culled).
 * <p>
 * The feed coordinate φ (for the static's crawl) of a station is {@link #feed}'s value plus its v, so a piece that
 * starts partway along the arm continues the crawl from the piece before it.
 * <p>
 * The arrays grow when a longer piece needs them and are never shrunk, so a Stations kept for reuse stops allocating
 * after its first frames. Not thread-safe; fill and sweep it inside one geometry lambda.
 */
public final class Stations {
	int n;
	int k;
	float[] c = new float[3 * 32];
	float[] t = new float[3 * 32];
	float[] nn = new float[3 * 32];
	float[] b = new float[3 * 32];
	float[] v = new float[32];
	float[] phi = new float[32];
	float[] alpha = new float[32];
	/** Twice the polygon's signed area: below 0 means it winds clockwise. */
	float[] area = new float[32];
	/** The colour of each station's corners, or -1 for the mesh's tint. */
	int[] rgb = new int[32];
	float[] poly = new float[2 * 8 * 32];
	/** Per station, k + 1 running lengths round the polygon (the last is the whole perimeter). */
	float[] perim = new float[9 * 32];

	private float feed;
	private int color = -1;
	private float hintX, hintY = 1F, hintZ;

	/** Empties the list for polygons of {@code sides} points, with no feed offset, colour or hint. */
	public Stations reset(int sides) {
		k = Math.max(2, sides);
		n = 0;
		feed = 0F;
		color = -1;
		hintX = 0F;
		hintY = 1F;
		hintZ = 0F;
		if (poly.length < 2 * k * v.length) poly = new float[2 * k * v.length];
		if (perim.length < (k + 1) * v.length) perim = new float[(k + 1) * v.length];
		return this;
	}

	/** The feed coordinate (px from the shoulder) where this piece's v is 0. */
	public Stations feed(float phiAtZero) {
		feed = phiAtZero;
		return this;
	}

	/** The brightness (0xRRGGBB) of the stations added from now on; -1 goes back to the mesh's tint. */
	public Stations color(int rgb) {
		color = rgb < 0 ? -1 : rgb & 0xFFFFFF;
		return this;
	}

	/** The direction the first {@link #addTransported} station's N starts from (made square to its T); +Y until set. */
	public Stations hint(float x, float y, float z) {
		hintX = x;
		hintY = y;
		hintZ = z;
		return this;
	}

	public int count() {
		return n;
	}

	public int sides() {
		return k;
	}

	/**
	 * One station: centre, tangent T, a first guess at N (anything not along T), arc v in px, the polygon's
	 * {@link #sides} (n, b) pairs (copied, so the caller can reuse its array) and the dissolve alpha of its corners.
	 */
	public Stations add(float cx, float cy, float cz, float tx, float ty, float tz, float nx, float ny, float nz, float v, float[] polyNB, float alpha) {
		grow(n + 1);
		int i = n;
		// T: unit length; a zero tangent keeps the previous station's (or +Y)
		float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
		if (tl > 1e-12F && Float.isFinite(tl)) {
			tx /= tl;
			ty /= tl;
			tz /= tl;
		} else if (i > 0) {
			tx = t[3 * i - 3];
			ty = t[3 * i - 2];
			tz = t[3 * i - 1];
		} else {
			tx = 0F;
			ty = 1F;
			tz = 0F;
		}
		// N: square to T; if the guess runs along T, any direction square to it
		float d = nx * tx + ny * ty + nz * tz;
		nx -= d * tx;
		ny -= d * ty;
		nz -= d * tz;
		float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (!(nl > 1e-6F)) {
			// the axis least along T, made square to it
			float ax = Math.abs(tx), ay = Math.abs(ty), az = Math.abs(tz);
			float px = 0F, py = 0F, pz = 0F;
			if (ax <= ay && ax <= az) px = 1F;
			else if (ay <= az) py = 1F;
			else pz = 1F;
			d = px * tx + py * ty + pz * tz;
			nx = px - d * tx;
			ny = py - d * ty;
			nz = pz - d * tz;
			nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		}
		nx /= nl;
		ny /= nl;
		nz /= nl;
		c[3 * i] = cx;
		c[3 * i + 1] = cy;
		c[3 * i + 2] = cz;
		t[3 * i] = tx;
		t[3 * i + 1] = ty;
		t[3 * i + 2] = tz;
		nn[3 * i] = nx;
		nn[3 * i + 1] = ny;
		nn[3 * i + 2] = nz;
		// B = T × N
		b[3 * i] = ty * nz - tz * ny;
		b[3 * i + 1] = tz * nx - tx * nz;
		b[3 * i + 2] = tx * ny - ty * nx;
		this.v[i] = v;
		phi[i] = feed + v;
		this.alpha[i] = alpha;
		rgb[i] = color;
		int o = 2 * k * i;
		System.arraycopy(polyNB, 0, poly, o, 2 * k);
		float a2 = 0F, run = 0F;
		int po = (k + 1) * i;
		perim[po] = 0F;
		for (int j = 0; j < k; j++) {
			int j1 = (j + 1) % k;
			float x0 = poly[o + 2 * j], y0 = poly[o + 2 * j + 1], x1 = poly[o + 2 * j1], y1 = poly[o + 2 * j1 + 1];
			a2 += x0 * y1 - x1 * y0;
			run += (float) Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0));
			perim[po + j + 1] = run;
		}
		area[i] = a2;
		n++;
		return this;
	}

	/**
	 * As {@link #add}, with N carried over from the previous station by parallel transport,
	 * {@code N' = normalize(N − (N·T')T')} (the first station starts from {@link #hint}).
	 */
	public Stations addTransported(float cx, float cy, float cz, float tx, float ty, float tz, float v, float[] polyNB, float alpha) {
		if (n == 0) return add(cx, cy, cz, tx, ty, tz, hintX, hintY, hintZ, v, polyNB, alpha);
		int p = 3 * (n - 1);
		return add(cx, cy, cz, tx, ty, tz, nn[p], nn[p + 1], nn[p + 2], v, polyNB, alpha);
	}

	/** Station {@code i}'s centre, x/y/z by {@code axis} 0..2 (for tests and anchors on a piece). */
	public float centre(int i, int axis) {
		return c[3 * i + axis];
	}

	/** Station {@code i}'s N, x/y/z by {@code axis} 0..2. */
	public float normal(int i, int axis) {
		return nn[3 * i + axis];
	}

	/** Station {@code i}'s T, x/y/z by {@code axis} 0..2. */
	public float tangent(int i, int axis) {
		return t[3 * i + axis];
	}

	/** Station {@code i}'s arc position v in px. */
	public float arc(int i) {
		return v[i];
	}

	/** Corner {@code j} of station {@code i} in the loft's space, x/y/z by {@code axis} 0..2. */
	public float corner(int i, int j, int axis) {
		int o = 2 * k * i + 2 * j;
		return c[3 * i + axis] + nn[3 * i + axis] * poly[o] + b[3 * i + axis] * poly[o + 1];
	}

	private void grow(int need) {
		if (need <= v.length) return;
		int cap = Math.max(need, v.length * 2);
		c = java.util.Arrays.copyOf(c, 3 * cap);
		t = java.util.Arrays.copyOf(t, 3 * cap);
		nn = java.util.Arrays.copyOf(nn, 3 * cap);
		b = java.util.Arrays.copyOf(b, 3 * cap);
		v = java.util.Arrays.copyOf(v, cap);
		phi = java.util.Arrays.copyOf(phi, cap);
		alpha = java.util.Arrays.copyOf(alpha, cap);
		area = java.util.Arrays.copyOf(area, cap);
		rgb = java.util.Arrays.copyOf(rgb, cap);
		poly = java.util.Arrays.copyOf(poly, 2 * k * cap);
		perim = java.util.Arrays.copyOf(perim, (k + 1) * cap);
	}
}
