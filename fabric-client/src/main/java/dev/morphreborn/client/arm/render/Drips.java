package dev.morphreborn.client.arm.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Goo dripping off an arm form: the biomass bar's drip cycle (BiomassHud) in 3D. Each emitter runs its own cycle,
 * {@code phase = frac(t·(0.010 + 0.004·(j % 3))·rate + 0.37j)}:
 * <ul>
 *   <li>for the first three quarters a thread gathers, a tapered strand (0.35 → 0.25 px) growing straight down to
 *       3 px, with a bead at its end swelling from 0.3 to 0.7 px;</li>
 *   <li>then the bead breaks off and falls, {@code 8u²/16} blocks below the thread's end by the time it is gone
 *       (u from 0 to 1 over the last quarter; {@code (3 + 8u²)/16} blocks from the emitter at world scale), shrinking
 *       to nothing as it goes.</li>
 * </ul>
 * Shrinking rather than fading keeps the bead a solid cut-out shape (cutout render types can't fade). The rate is
 * doubled for a starving arm, tripled failing, quadrupled melting; drips are off in GUI views and at a distance (the
 * caller's choice). The fall is along {@code downLocal}, world down as seen in the geometry's space, so a drip falls
 * straight down however the blade is held.
 */
public final class Drips {
	/** A thread's widths at its root and at its end, its full length, and the bead's sizes, all in px. */
	private static final float THREAD_ROOT = 0.35F, THREAD_END = 0.25F, THREAD_LENGTH = 3F, BEAD_MIN = 0.3F, BEAD_MAX = 0.7F;
	/** How far the bead falls, in blocks, by the end of its drop (u = 1): as the bar's 8 px over its 16. */
	private static final float FALL_BLOCKS = 8F / 16F;

	private static final Matrix4f THREAD = new Matrix4f();
	private static final Quaternionf TURN = new Quaternionf();

	private Drips() {}

	/**
	 * Emitter {@code j}'s drip from ({@code x, y, z}) in {@code local}'s space (null: the mesh's), falling along
	 * {@code downLocal}; {@code rateMul} speeds the cycle, {@code blocksToPx} is how many of these units make a block
	 * (16 for world-scale px; more where the geometry is drawn scaled down, as in a hand). The thread and bead take
	 * their static from segments {@code 2j} and {@code 2j + 1} of {@code part}. A thread is a frustum and a bead a box
	 * (12 quads while it gathers, 6 while it falls).
	 */
	public static void emit(SkinMesh m, @Nullable Matrix4fc local, float x, float y, float z, Vector3fc downLocal, float time, int j, float rateMul, float blocksToPx,
			@Nullable Static st, int part) {
		float dx = downLocal.x(), dy = downLocal.y(), dz = downLocal.z();
		float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (dl > 1e-6F && Float.isFinite(dl)) {
			dx /= dl;
			dy /= dl;
			dz /= dl;
		} else {
			dx = 0F;
			dy = -1F;
			dz = 0F;
		}
		float rate = (0.010F + 0.004F * Math.floorMod(j, 3)) * rateMul;
		float phase = ArmMath.frac(time * rate + 0.37F * j);
		float tu = st == null ? 0F : st.u0(part, 2 * j, 0F), tv = st == null ? 0F : st.v0(part, 2 * j, 0F);
		float bu = st == null ? 0F : st.u0(part, 2 * j + 1, 0F), bv = st == null ? 0F : st.v0(part, 2 * j + 1, 0F);
		float drop, bead;
		if (phase < 0.75F) {
			float g = phase / 0.75F;
			float len = THREAD_LENGTH * g;
			if (local != null) THREAD.set(local);
			else THREAD.identity();
			THREAD.translate(x, y, z).rotate(TURN.rotationTo(0F, 0F, 1F, dx, dy, dz));
			m.frustum(THREAD, len, THREAD_ROOT, THREAD_ROOT, THREAD_END, THREAD_END, tu, tv, 2F, 1F, 1F, SkinMesh.WHITE);
			drop = len;
			bead = ArmMath.lerp(BEAD_MIN, BEAD_MAX, g);
		} else {
			float u = (phase - 0.75F) / 0.25F;
			drop = THREAD_LENGTH + FALL_BLOCKS * u * u * blocksToPx;
			bead = BEAD_MAX * (1F - u);
		}
		if (bead <= 0F) return;
		float h = bead / 2F;
		m.box(local, x + dx * drop - h, y + dy * drop - h, z + dz * drop - h, bead, bead, bead, bu, bv, 2F, 1F, SkinMesh.WHITE);
	}
}
