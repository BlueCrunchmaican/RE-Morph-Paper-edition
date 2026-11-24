package dev.morphreborn.client.arm.model;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmStacks;
import dev.morphreborn.client.arm.geom.FpClearance;
import java.util.function.Consumer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * What vanilla's first-person renderer holds in each hand this frame, as spheres the overlay's accessories keep clear of (2026-11-24). The mixin on
 * {@code submitArmWithItem} hands over, for each hand, the pose it is about to submit the item with (all of vanilla's own animation in it: equip,
 * the swing and the stab, a bow drawn, a crossbow loaded, the trident, eating, blocking) and the item model's extent points. An arm form's volume is its
 * own solid, measured from the quads it draws ({@link FormCover}: spheres in its grip frame, every part of it, the pickaxe's head ends and the bow's limbs
 * included). Any other item's is spheres over its extent points filled between along their principal axes ({@link #coverPoints}: a flat sprite is a thin slab
 * that follows its silhouette). The accessories give way to the volume, never the other way round. {@link #FORM_SPAN} is the older rod-shaped volume, kept
 * for a form the cover table does not have (and its drift test).
 */
public final class FpHeld {
	private FpHeld() {
	}

	private static final int MAX_POINTS = 512;

	/**
	 * Each form's span in grip space G as the hand pass draws it (first person: its own FP sizes in): {yMin, yMax, across} in px, from the grip
	 * along +Y and the farthest any vertex is from that axis, measured over every tier, swing, pull, special and time (FormExtentsTest keeps it honest).
	 */
	public static final java.util.Map<String, float[]> FORM_SPAN = java.util.Map.ofEntries(java.util.Map.entry(ArmProtocol.KATANA, new float[] {-29.5F, 26.5F, 7F}),
			java.util.Map.entry(ArmProtocol.PICKAXE, new float[] {-22.5F, 18.5F, 11F}), java.util.Map.entry(ArmProtocol.AXE, new float[] {-29.5F, 21F, 9F}),
			java.util.Map.entry(ArmProtocol.SHOVEL, new float[] {-24F, 19F, 5.5F}), java.util.Map.entry(ArmProtocol.SICKLE, new float[] {-31F, 22.5F, 13F}),
			java.util.Map.entry(ArmProtocol.BOW, new float[] {-14.5F, 8F, 7F}), java.util.Map.entry(ArmProtocol.WHIP, new float[] {-33F, 7F, 8.5F}),
			java.util.Map.entry(ArmProtocol.SPEAR, new float[] {-8.5F, 18.5F, 5.2F}), java.util.Map.entry(ArmProtocol.MACE, new float[] {-11F, 26.5F, 8.5F}),
			java.util.Map.entry(ArmProtocol.TRIDENT, new float[] {-28.5F, 5.8F, 11.2F}), java.util.Map.entry(ArmProtocol.CROSSBOW, new float[] {-21.3F, 7.8F, 9.9F}),
			java.util.Map.entry(ArmProtocol.CLAWS, new float[] {-26.5F, 15.5F, 8.6F}));

	private static final class Slot implements Consumer<Vector3fc> {
		long frame = Long.MIN_VALUE;
		final Matrix4f pose = new Matrix4f();
		String form;
		boolean bow;
		final float[] pts = new float[3 * MAX_POINTS];
		int n;

		@Override
		public void accept(Vector3fc v) {
			if (n < MAX_POINTS) {
				pts[3 * n] = v.x();
				pts[3 * n + 1] = v.y();
				pts[3 * n + 2] = v.z();
				n++;
			}
		}
	}

	private static final Slot MAIN = new Slot(), OFF = new Slot();
	private static final Matrix4f REL = new Matrix4f(), BASE_INV = new Matrix4f(), G = new Matrix4f();
	private static final Vector3f A = new Vector3f(), B = new Vector3f(), P = new Vector3f(), AB = new Vector3f(), Q = new Vector3f();

	/**
	 * The hand pass is about to submit {@code stack} in a hand with {@code pose} (blocks) and the item's model {@code model} (its extent points,
	 * display transform included, in the pose's space; null: unknown).
	 */
	public static void record(boolean mainHand, Matrix4fc pose, ItemStack stack, ItemStackRenderState model) {
		Slot s = mainHand ? MAIN : OFF;
		s.frame = ArmRender.frame();
		s.pose.set(pose);
		s.n = 0;
		ArmStacks.Recognized arm = stack == null || stack.isEmpty() ? null : ArmStacks.recognize(stack);
		s.form = arm == null ? null : arm.form();
		s.bow = arm != null && ArmProtocol.BOW.equals(arm.form());
		if (arm == null && model != null) model.visitExtents(s);
	}

	/** For the tests: the same as {@link #record} with the answer already worked out (an arm form of {@code form}, or extent points {@code pts}, x y z each). */
	static void recordHeld(boolean mainHand, Matrix4fc pose, String form, boolean bow, float[] pts) {
		Slot s = mainHand ? MAIN : OFF;
		s.frame = ArmRender.frame();
		s.pose.set(pose);
		s.form = form;
		s.bow = bow;
		s.n = form == null ? Math.min(MAX_POINTS, pts.length / 3) : 0;
		System.arraycopy(pts, 0, s.pts, 0, 3 * s.n);
	}

	/** Forgets both hands (the tests). */
	static void forgetAll() {
		MAIN.frame = Long.MIN_VALUE;
		OFF.frame = Long.MIN_VALUE;
	}

	/**
	 * Adds the spheres of what both hands hold this frame to {@code out}, in the hand pass's view space (px) as seen from {@code base} (the
	 * pose stack's top when the hand pass ended, in blocks). {@code mainArmLeft}: the main hand is the left.
	 */
	public static void volumes(Matrix4fc base, boolean mainArmLeft, FpClearance.Obstacles out) {
		BASE_INV.set(base).invert();
		add(MAIN, mainArmLeft, BASE_INV, out);
		add(OFF, !mainArmLeft, BASE_INV, out);
	}

	private static void add(Slot s, boolean leftHand, Matrix4f baseInv, FpClearance.Obstacles out) {
		if (s.frame != ArmRender.frame()) return;
		REL.set(baseInv).mul(s.pose);
		if (s.form != null) {
			ArmTransforms.firstPersonFrame(s.form, s.bow, leftHand, G);
			G.mulLocal(REL).scaleLocal(16F);
			formSpheres(s.form, G, out);
			return;
		}
		if (s.n > 0) pointSpheres(s, REL, out);
	}

	/**
	 * A form's volume: a chain of spheres along G's Y axis over the span measured for the form in the hand pass ({@link #FORM_SPAN}: which way a form
	 * runs from its grip differs: the whip and the trident run back, the katana both ways), as thick as 0.55 of how far its farthest vertex is from the axis.
	 */
	static void formSpheres(String form, Matrix4f g, FpClearance.Obstacles out) {
		float[] cover = FormCover.of(form);
		if (cover != null && cover.length >= 4) {
			// the form's own solid, measured from the quads it draws: each sphere carried by G, its radius by G's largest stretch
			float k = (float) Math.sqrt(Math.max(g.m00() * g.m00() + g.m01() * g.m01() + g.m02() * g.m02(),
					Math.max(g.m10() * g.m10() + g.m11() * g.m11() + g.m12() * g.m12(), g.m20() * g.m20() + g.m21() * g.m21() + g.m22() * g.m22())));
			for (int i = 0; i + 3 < cover.length; i += 4) {
				g.transformPosition(P.set(cover[i], cover[i + 1], cover[i + 2]));
				out.add(P.x, P.y, P.z, cover[i + 3] * k);
			}
			return;
		}
		float[] span = FORM_SPAN.get(form);
		if (span != null) {
			float r = Math.max(2.4F, 0.55F * span[2]);
			float step = Math.max(2F, r * 0.9F);
			int n = Math.max(1, (int) Math.ceil((span[1] - span[0]) / step));
			for (int i = 0; i <= n; i++) {
				g.transformPosition(P.set(0F, span[0] + (span[1] - span[0]) * i / n, 0F));
				out.add(P.x, P.y, P.z, r);
			}
			return;
		}
		float reach = NearBodyScale.reachPx(form, 0F);
		boolean wide = form.equals(ArmProtocol.BOW) || form.equals(ArmProtocol.CROSSBOW) || form.equals(ArmProtocol.WHIP);
		boolean headed = form.equals(ArmProtocol.MACE) || form.equals(ArmProtocol.AXE);
		float r = wide ? 4.5F : headed || form.equals(ArmProtocol.TRIDENT) || form.equals(ArmProtocol.SPEAR) || form.equals(ArmProtocol.SICKLE) ? 3.2F : 2.4F;
		for (float y = -6F; y <= reach + 0.01F; y += Math.max(2F, r * 0.9F)) {
			g.transformPosition(P.set(0F, y, 0F));
			out.add(P.x, P.y, P.z, r * (y > reach * 0.8F && headed ? 1.6F : 1F));
		}
		g.transformPosition(P.set(0F, reach, 0F));
		out.add(P.x, P.y, P.z, r);
	}

	/**
	 * Any other item: its extent points (the model's own vertices: for a flat sprite the corners of its quad and the ends of every pixel edge, for a box
	 * its corners) laid in a grid of cells along their principal axes, the gaps between occupied cells along each axis filled (a sprite's inside, a block's
	 * volume; a handful of points are joined by their segments first), and a sphere over every occupied cell. A flat sprite becomes a thin slab of spheres
	 * that follows its silhouette, not one fat capsule round its diagonal.
	 */
	private static void pointSpheres(Slot s, Matrix4f rel, FpClearance.Obstacles out) {
		int n = s.n;
		for (int i = 0; i < n; i++) {
			Q.set(s.pts[3 * i], s.pts[3 * i + 1], s.pts[3 * i + 2]);
			rel.transformPosition(Q).mul(16F);
			PX[3 * i] = Q.x;
			PX[3 * i + 1] = Q.y;
			PX[3 * i + 2] = Q.z;
		}
		coverPoints(PX, n, out);
	}

	private static final float[] PX = new float[3 * MAX_POINTS];
	/** The most spheres one item gets (the cell grows to keep to it), the smallest cell, and the widest gap between occupied cells that is filled (cells). */
	private static final int MAX_ITEM_SPHERES = 140;
	private static final float CELL = 2.4F, FILL_GAP = 8;

	/** Covers the {@code n} points {@code p} (x y z each, view px) with spheres added to {@code out}; see {@link #pointSpheres}. */
	static void coverPoints(float[] p, int n, FpClearance.Obstacles out) {
		if (n <= 0) return;
		float cx = 0F, cy = 0F, cz = 0F;
		for (int i = 0; i < n; i++) {
			cx += p[3 * i];
			cy += p[3 * i + 1];
			cz += p[3 * i + 2];
		}
		cx /= n;
		cy /= n;
		cz /= n;
		double[][] cov = new double[3][3];
		for (int i = 0; i < n; i++) {
			double[] d = {p[3 * i] - cx, p[3 * i + 1] - cy, p[3 * i + 2] - cz};
			for (int a = 0; a < 3; a++) {
				for (int b = 0; b < 3; b++) cov[a][b] += d[a] * d[b];
			}
		}
		double[][] axes = principalAxes(cov);
		float[] q = new float[3 * n];
		float[] lo = {1e9F, 1e9F, 1e9F}, hi = {-1e9F, -1e9F, -1e9F};
		for (int i = 0; i < n; i++) {
			for (int a = 0; a < 3; a++) {
				float v = (float) ((p[3 * i] - cx) * axes[a][0] + (p[3 * i + 1] - cy) * axes[a][1] + (p[3 * i + 2] - cz) * axes[a][2]);
				q[3 * i + a] = v;
				lo[a] = Math.min(lo[a], v);
				hi[a] = Math.max(hi[a], v);
			}
		}
		float h = CELL;
		for (int attempt = 0; attempt < 8; attempt++) {
			float ru = hi[0] - lo[0], rv = hi[1] - lo[1], rw = hi[2] - lo[2];
			int du = Math.min(256, (int) (ru / h) + 1), dv = Math.min(256, (int) (rv / h) + 1);
			int dw = rw <= 1.5F * h ? 1 : Math.min(256, (int) Math.ceil(rw / h));
			// a pose that is not a number (or a model a thousand px long) gets no cover rather than a grid the size of the screen
			if (!(ru < 2000F) || !(rv < 2000F) || !(rw < 2000F)) return;
			if (du * dv * dw > 40000) {
				h *= 1.3F;
				continue;
			}
			float cu = Math.max(h, ru / du), cv = Math.max(h, rv / dv), cw = dw == 1 ? Math.max(0.8F, rw + 0.2F) : Math.max(h, rw / dw);
			boolean[] occ = new boolean[du * dv * dw];
			for (int i = 0; i < n; i++) mark(occ, du, dv, dw, lo, cu, cv, cw, q[3 * i], q[3 * i + 1], q[3 * i + 2]);
			if (n <= 16) {
				// a few points are a box's corners or a sprite's: their segments run through the volume the fill then closes
				for (int i = 0; i < n; i++) {
					for (int j = i + 1; j < n; j++) {
						float ex = q[3 * i] - q[3 * j], ey = q[3 * i + 1] - q[3 * j + 1], ez = q[3 * i + 2] - q[3 * j + 2];
						float len = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
						int steps = Math.max(1, (int) Math.ceil(len / (0.5F * h)));
						for (int k = 1; k < steps; k++) {
							float t = k / (float) steps;
							mark(occ, du, dv, dw, lo, cu, cv, cw, q[3 * i] - ex * t, q[3 * i + 1] - ey * t, q[3 * i + 2] - ez * t);
						}
					}
				}
			} else {
				dropLoose(occ, du, dv, dw);
			}
			fill(occ, du, dv, dw);
			int count = 0;
			for (boolean b : occ) count += b ? 1 : 0;
			if (count > MAX_ITEM_SPHERES && attempt < 7) {
				h *= 1.2F;
				continue;
			}
			float r = 0.5F * (float) Math.sqrt(cu * cu + cv * cv + cw * cw) + 0.3F;
			for (int iu = 0; iu < du; iu++) {
				for (int iv = 0; iv < dv; iv++) {
					for (int iw = 0; iw < dw; iw++) {
						if (!occ[(iu * dv + iv) * dw + iw]) continue;
						float u = lo[0] + (iu + 0.5F) * cu, v = lo[1] + (iv + 0.5F) * cv, w = lo[2] + (iw + 0.5F) * cw;
						out.add(cx + (float) (axes[0][0] * u + axes[1][0] * v + axes[2][0] * w), cy + (float) (axes[0][1] * u + axes[1][1] * v + axes[2][1] * w),
								cz + (float) (axes[0][2] * u + axes[1][2] * v + axes[2][2] * w), r);
					}
				}
			}
			return;
		}
	}

	private static void mark(boolean[] occ, int du, int dv, int dw, float[] lo, float cu, float cv, float cw, float u, float v, float w) {
		int iu = Math.clamp((int) ((u - lo[0]) / cu), 0, du - 1), iv = Math.clamp((int) ((v - lo[1]) / cv), 0, dv - 1), iw = Math.clamp((int) ((w - lo[2]) / cw), 0, dw - 1);
		occ[(iu * dv + iv) * dw + iw] = true;
	}

	/** Drops a cell with no other within two cells of it: the unseen corners of a sprite's quad (the pixels' edges are all joined to one another). */
	private static void dropLoose(boolean[] occ, int du, int dv, int dw) {
		boolean[] keep = occ.clone();
		int total = 0;
		for (boolean b : occ) total += b ? 1 : 0;
		if (total <= 6) return;
		for (int iu = 0; iu < du; iu++) {
			for (int iv = 0; iv < dv; iv++) {
				for (int iw = 0; iw < dw; iw++) {
					if (!occ[(iu * dv + iv) * dw + iw]) continue;
					boolean near = false;
					for (int a = Math.max(0, iu - 2); a <= Math.min(du - 1, iu + 2) && !near; a++) {
						for (int b = Math.max(0, iv - 2); b <= Math.min(dv - 1, iv + 2) && !near; b++) {
							for (int c = Math.max(0, iw - 2); c <= Math.min(dw - 1, iw + 2); c++) {
								if ((a != iu || b != iv || c != iw) && occ[(a * dv + b) * dw + c]) {
									near = true;
									break;
								}
							}
						}
					}
					if (!near) keep[(iu * dv + iv) * dw + iw] = false;
				}
			}
		}
		System.arraycopy(keep, 0, occ, 0, occ.length);
	}

	/** Fills the gaps (up to {@link #FILL_GAP} cells) between occupied cells along each axis in turn. */
	private static void fill(boolean[] occ, int du, int dv, int dw) {
		for (int axis = 0; axis < 3; axis++) {
			int len = axis == 0 ? du : axis == 1 ? dv : dw;
			int a1 = axis == 0 ? dv : du, a2 = axis == 2 ? dv : dw;
			for (int i = 0; i < a1; i++) {
				for (int j = 0; j < a2; j++) {
					int last = -1;
					for (int k = 0; k < len; k++) {
						int idx = axis == 0 ? (k * dv + i) * dw + j : axis == 1 ? (i * dv + k) * dw + j : (i * dv + j) * dw + k;
						if (!occ[idx]) continue;
						if (last >= 0 && k - last - 1 > 0 && k - last - 1 <= FILL_GAP) {
							for (int m = last + 1; m < k; m++) {
								int f = axis == 0 ? (m * dv + i) * dw + j : axis == 1 ? (i * dv + m) * dw + j : (i * dv + j) * dw + m;
								occ[f] = true;
							}
						}
						last = k;
					}
				}
			}
		}
	}

	/** The principal axes of a symmetric 3 by 3 matrix, largest variance first (Jacobi rotations), each a unit row. */
	static double[][] principalAxes(double[][] m) {
		double[][] a = {m[0].clone(), m[1].clone(), m[2].clone()};
		double[][] v = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
		for (int sweep = 0; sweep < 24; sweep++) {
			double off = Math.abs(a[0][1]) + Math.abs(a[0][2]) + Math.abs(a[1][2]);
			if (off < 1e-12) break;
			for (int p = 0; p < 2; p++) {
				for (int q = p + 1; q < 3; q++) {
					if (Math.abs(a[p][q]) < 1e-14) continue;
					double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
					double t = theta == 0 ? 1 : Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
					double c = 1 / Math.sqrt(t * t + 1), sn = t * c;
					for (int k = 0; k < 3; k++) {
						double akp = a[k][p], akq = a[k][q];
						a[k][p] = c * akp - sn * akq;
						a[k][q] = sn * akp + c * akq;
					}
					for (int k = 0; k < 3; k++) {
						double apk = a[p][k], aqk = a[q][k];
						a[p][k] = c * apk - sn * aqk;
						a[q][k] = sn * apk + c * aqk;
					}
					for (int k = 0; k < 3; k++) {
						double vkp = v[k][p], vkq = v[k][q];
						v[k][p] = c * vkp - sn * vkq;
						v[k][q] = sn * vkp + c * vkq;
					}
				}
			}
		}
		Integer[] order = {0, 1, 2};
		java.util.Arrays.sort(order, (x, y) -> Double.compare(a[y][y], a[x][x]));
		double[][] out = new double[3][3];
		for (int i = 0; i < 3; i++) {
			for (int k = 0; k < 3; k++) out[i][k] = v[k][order[i]];
		}
		return out;
	}
}
