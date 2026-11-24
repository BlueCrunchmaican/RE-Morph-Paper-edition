package dev.morphreborn.client.arm.geom;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmState;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.model.NearBodyScale;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The lantern's lure (section 2.11; reworked 2026-11-15: over the tool arm, a ball on a chain with
 * physics, a light round the player whose brightness they choose): a glowing seed in a little cage, hanging on a chain
 * of links from a hook at the end of a tendril stalk that grows out of the arm that holds the form, arching out over
 * the hand. The core glows brighter or dimmer with the brightness chosen ({@link #brightness}). It is an accessory, so it is lit alongside any form
 * or a bare hand, and it is the one thing here that is not a tool: it has no grip space of its own, and each view hands
 * it the space to draw in.
 * <ul>
 *   <li>Third person ({@link #emitThirdPerson}): arm space A of the <b>off</b> arm, px (the sheath layer sets it up). The
 *       stalk leaves the forearm's outer face above the hand and arches up and forward over the arm, as the spec's
 *       Bezier says: P0 (x_out + 0.4·out, 6.0, 0), P1 (x_out + 4.0·out, 0.5, −3.0), P2 (x_out + 3.0·out, 3.0, −7.5).</li>
 *   <li>First person ({@link #emitFirstPerson}): the hand pass's view space in px. The stalk comes in from below and
 *       beyond the left edge of the screen and arches over to (−0.55, −0.32, −0.80) blocks, the lure hanging 0.12 below;
 *       with something in the other hand it arches in nearer the middle. For a left-handed player it is all mirrored.</li>
 * </ul>
 * What is drawn, and how it moves:
 * <ul>
 *   <li>The stalk: a tube of black static, 1.0 to 0.45 px wide, with three knuckles, a swelling that runs along it with
 *       the arm's idle pulses (and any feeding pulse), a writhe that is still at both ends, and a red sinew winding round
 *       it and slowly turning.</li>
 *   <li>Its bristles, which are not alike: three barbs (hooked, one of them forked) and five cilia (fine, waving in a
 *       travelling wave), each with its own length, lean, curl, width and sideways skew, from the tables below.</li>
 *   <li>In third person, the clutch: a red ring where the stalk grips the forearm and two fingers that wrap round it.</li>
 *   <li>The lure: a rope of three nodes with real inertia (in first person it swings against camera turns), a cage of
 *       five ribs that are not alike either, turning and breathing round a glowing 1.6 px core that flickers like a
 *       flame, a red eye ring that precesses round it, two motes orbiting, and a tuft under the cage.</li>
 *   <li>The flare ({@link #flared}): the core swells to twice its size, the rope jumps, the cage bursts open and fades,
 *       and then grows back over the next dozen ticks.</li>
 * </ul>
 * Timeline, by ticks since the lantern was lit ({@code d.lanternAge()}): the stalk grows out of the arm over ticks 0..7
 * and the clutch tightens on the forearm over 0..4; the core ignites at 6.5; the cage grows round it from 8; the bristles
 * sprout one after another from the base, from 6.5, once the stalk is out; the eye ring pops in at 12. A lure lit long ago
 * (a viewer who arrives late) is simply there. Low biomass makes the core flicker harder, then dim, then blink.
 * <p>
 * Detail drops with distance: LOD 1 (and icons) is the stalk, two barbs, the cage's three ribs and the core on a static
 * rope; LOD 2 a thin stalk and a box for the lure; LOD 3 two boxes. Only LOD 0 simulates the rope.
 * <p>
 * Render thread only (shared scratch, and the rope's state, which is stepped once per frame per player and view).
 */
public final class LureGeometry implements LureRenderer {
	// ------------------------------------------------------------------ the numbers

	/** The stalk's width at its root and tip, px (section 2.11); the rope's link, px (third person and first person). */
	private static final float STALK_W0 = 1.0F, STALK_W1 = 0.45F, LINK_TP = 1.0F, LINK_FP = 0.8F;
	/** The core cube's side (px: about 1.6 across once turned, so its corners stay inside the cage), the cage's radius and half-height, the eye ring's radius. */
	private static final float CORE = 1.3F, CAGE_R = 1.2F, CAGE_H = 1.45F, EYE_R = 1.5F;
	/** Stalk samples (frames carried along it), and the pieces' static parts. */
	private static final int SN = 14;
	private static final int PART_STALK = 70, PART_SINEW = 71, PART_BARB = 72, PART_RING = 82, PART_FINGER = 83, PART_ROPE = 85, PART_RIB = 86, PART_EYE = 92,
			PART_TUFT = 93, PART_CORE = 96, PART_MOTE = 97, PART_BOX = 99;
	/**
	 * The stalk's growth, when the core ignites and grows, and when everything else follows (ticks since lit). 1.6 times the
	 * first timings (2026-11-16: the lantern's animations were slightly fast).
	 */
	static final float STALK_TICKS = 11.2F, CORE_FROM = 10.4F, CORE_TICKS = 5.6F, CAGE_FROM = 12.8F, CAGE_TICKS = 12.8F, EYE_FROM = 19.2F, SPROUT_FROM = 10.4F,
			SPROUT_STAGGER = 1.1F, SPROUT_TICKS = 4.8F, CLUTCH_TICKS = 6.4F;
	/**
	 * With only the lantern out, what holds it on the arm grows first (from the stalk's root down the arm, over HOLD_TICKS)
	 * and the stalk follows HOLD_LEAD ticks after it was lit: the arm forms first, then the lantern comes out of it, as an
	 * arm form does (2026-11-16: "the lantern can just build off of that the same way").
	 */
	static final float HOLD_TICKS = 9F;
	public static final float HOLD_LEAD = 7F;
	/** When the build is complete (ticks since lit, before HOLD_LEAD: the cage is the last to finish): going out plays it backwards from here at most. */
	public static final float BUILT_AT = CAGE_FROM + CAGE_TICKS + 0.5F;

	/**
	 * The bristles along the stalk, base to tip (they sprout in this order): where (s), which way round it (radians),
	 * length (px), lean from straight out toward the tip (degrees), curl (radians added to the second half; + bends toward
	 * the tip), width (px at the root), sideways skew (× the length), and the wave a cilium makes (px; 0 for the stiff
	 * barbs). Hand-tuned, so no two look alike. Level 1 keeps the first five.
	 */
	static final float[] BR_S = {0.10F, 0.22F, 0.33F, 0.47F, 0.55F, 0.62F, 0.71F, 0.85F},
			BR_AZ = {2.2F, 0.6F, 5.6F, 3.5F, 1.1F, 4.4F, 5.1F, 3.0F},
			BR_LEN = {0.9F, 1.9F, 1.15F, 1.4F, 0.8F, 1.3F, 1.0F, 0.7F},
			BR_LEAN = {72F, 55F, 80F, 38F, 60F, 75F, 62F, 85F},
			BR_CURL = {0.10F, 0.50F, -0.10F, -0.20F, 0.15F, 0.05F, 0.35F, 0F},
			BR_W = {0.14F, 0.50F, 0.14F, 0.42F, 0.12F, 0.14F, 0.34F, 0.12F},
			BR_SKEW = {0.10F, 0.20F, -0.10F, -0.15F, 0.20F, -0.05F, 0.10F, 0.15F},
			BR_WAVE = {0.30F, 0F, 0.35F, 0F, 0.40F, 0.28F, 0F, 0.40F};
	/** Which of them fork (a small branch half way along). */
	static final boolean[] BR_FORK = {false, true, false, false, false, false, false, false};
	/** The cage's five ribs: where each starts round the core (radians), its radius (× the cage's), its twist round the core. */
	static final float[] RIB_PHI = {0F, 1.31F, 2.5F, 3.9F, 5.2F}, RIB_R = {1.0F, 1.15F, 0.9F, 1.2F, 1.05F}, RIB_TWIST = {0.5F, -0.35F, 0.2F, -0.6F, 0.4F};
	/** The stalk's three knuckles: where (s) and how much thicker. */
	private static final float[] KNUCKLE_S = {0.30F, 0.58F, 0.82F}, KNUCKLE_A = {0.24F, 0.20F, 0.28F};

	/** The chain's nodes (its links are one fewer): the stalk's hook first, the cage's cap last. */
	static final int NODES = 6;
	/** The rope's gravity (px a tick²), and how much of its speed it keeps each tick. */
	// heavier since 2026-11-15 (my call: "a little too floppy, moves too easily, needs more weight"): it settles back
	// faster and swings less (gravity 0.45 → 1.0), and loses its swing sooner (it keeps 74% of its speed a tick, not 88%)
	static final float GRAVITY = 1.0F, DAMPING = 0.74F;
	/** How far (radians) a frame's camera turn may carry the first-person rope: a flick is a flick, not a teleport. */
	private static final float MAX_CARRY = 0.25F;
	/** A flare's burst, in ticks after it fires: the core swells until the burst, the cage fades, then it grows back. */
	static final float BURST_AT = 5F, BURST_END = 8F, REGROW_FROM = 9F, REGROW_TICKS = 12F, BURST_FORGET = 40F;
	/** The arm clock's wrap, as ArmDrawFactory.CLOCK_WRAP (kept here so this class needs nothing of the model package). */
	private static final long CLOCK_WRAP = 72000L;

	// ------------------------------------------------------------------ what other code feeds it

	/** Per player: the arm tick of the last flare (the lure's burst), by entity id. */
	private static final Map<Integer, Long> FLARES = new HashMap<>();
	/** Camera turns since the first-person rope was last stepped (degrees), and how much the other hand is busy (0..1). */
	private static float pendingYaw, pendingPitch, offhand;

	/** The lure's chain, per player and view: {@link #NODES} nodes in the drawing space, and the clock it was stepped at. */
	static final class Hang {
		final float[] p = new float[3 * NODES], v = new float[3 * NODES];
		boolean started;
		/** Third person: the world point (blocks) the simulation's space is measured from (its nodes are px from it). */
		double ox, oy, oz;
		float lastTime = Float.NaN, lastFire = Float.NaN;
		long lastTick = -1000L;
		/** First person: the chain keeps out of what the overlay put in its way ({@link FpLayout#collide}), as it swings. */
		boolean avoid;

		void reset(float rx, float ry, float rz, float gx, float gy, float gz, float link) {
			for (int i = 0; i < NODES; i++) {
				p[3 * i] = rx + gx * link * i;
				p[3 * i + 1] = ry + gy * link * i;
				p[3 * i + 2] = rz + gz * link * i;
				v[3 * i] = v[3 * i + 1] = v[3 * i + 2] = 0F;
			}
			started = true;
		}

		/**
		 * Moves the root to ({@code rx, ry, rz}) and advances {@code dt} ticks under gravity along the unit vector
		 * ({@code gx, gy, gz}). {@code yaw} and {@code pitch} (radians) are how far the view turned since the last step:
		 * the free nodes are carried round the other way (they hang in the world, the view moves), and the rope pulls
		 * them back, so a camera turn swings it.
		 */
		void step(float rx, float ry, float rz, float gx, float gy, float gz, float link, float dt, float yaw, float pitch) {
			if (!started || !Float.isFinite(p[3]) || !Float.isFinite(p[3 * NODES - 3])) {
				reset(rx, ry, rz, gx, gy, gz, link);
				return;
			}
			if (yaw != 0F || pitch != 0F) {
				for (int i = 1; i < NODES; i++) {
					carry(p, 3 * i, yaw, pitch);
					carry(v, 3 * i, yaw, pitch);
				}
			}
			float h = Float.isFinite(dt) ? ArmMath.clamp(dt, 0F, 2F) : 0F;
			p[0] = rx;
			p[1] = ry;
			p[2] = rz;
			float[] prev = p.clone();
			if (h > 0F) {
				float keep = (float) Math.pow(DAMPING, h);
				for (int i = 1; i < NODES; i++) {
					int o = 3 * i;
					v[o] = v[o] * keep + gx * GRAVITY * h;
					v[o + 1] = v[o + 1] * keep + gy * GRAVITY * h;
					v[o + 2] = v[o + 2] * keep + gz * GRAVITY * h;
					p[o] += v[o] * h;
					p[o + 1] += v[o + 1] * h;
					p[o + 2] += v[o + 2] * h;
				}
			}
			for (int pass = 0; pass < 4; pass++) {
				for (int i = NODES - 2; i >= 1; i--) link(i, i + 1, link, true, gx, gy, gz);
				link(0, 1, link, false, gx, gy, gz);
				// the item in the hand and the other accessories are solid to the chain: a flick of the camera swings it round them, not through them
				if (avoid) FpLayout.collide(p, NODES);
			}
			for (int i = 0; i + 1 < NODES; i++) link(i, i + 1, link, false, gx, gy, gz);
			if (avoid) {
				FpLayout.collide(p, NODES);
				for (int i = 0; i + 1 < NODES; i++) link(i, i + 1, link, false, gx, gy, gz);
				FpLayout.collide(p, NODES);
			}
			if (h > 1e-3F) {
				float max = 6F * link;
				for (int i = 1; i < NODES; i++) {
					int o = 3 * i;
					float vx = (p[o] - prev[o]) / h, vy = (p[o + 1] - prev[o + 1]) / h, vz = (p[o + 2] - prev[o + 2]) / h;
					float sp = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
					if (sp > max) {
						float k = max / sp;
						vx *= k;
						vy *= k;
						vz *= k;
					}
					v[o] = vx;
					v[o + 1] = vy;
					v[o + 2] = vz;
				}
			}
		}

		/** A shove (px a tick), strongest at the free end. */
		void impulse(float x, float y, float z) {
			for (int i = 1; i < NODES; i++) {
				float k = i / (float) (NODES - 1);
				v[3 * i] += x * k;
				v[3 * i + 1] += y * k;
				v[3 * i + 2] += z * k;
			}
		}

		private void link(int a, int b, float len, boolean share, float gx, float gy, float gz) {
			int ia = 3 * a, ib = 3 * b;
			float dx = p[ib] - p[ia], dy = p[ib + 1] - p[ia + 1], dz = p[ib + 2] - p[ia + 2];
			float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (!(l > 1e-6F)) {
				p[ib] = p[ia] + gx * len;
				p[ib + 1] = p[ia + 1] + gy * len;
				p[ib + 2] = p[ia + 2] + gz * len;
				return;
			}
			float diff = (l - len) / l;
			if (share) {
				float half = diff * 0.5F;
				p[ia] += dx * half;
				p[ia + 1] += dy * half;
				p[ia + 2] += dz * half;
				p[ib] -= dx * half;
				p[ib + 1] -= dy * half;
				p[ib + 2] -= dz * half;
			} else {
				p[ib] -= dx * diff;
				p[ib + 1] -= dy * diff;
				p[ib + 2] -= dz * diff;
			}
		}

		/** A view turn of {@code yaw} to the right and {@code pitch} down, applied to the vector at {@code o}. */
		private static void carry(float[] a, int o, float yaw, float pitch) {
			float x = a[o], y = a[o + 1], z = a[o + 2];
			float cy = (float) Math.cos(yaw), sy = (float) Math.sin(yaw);
			float x1 = x * cy + z * sy, z1 = -x * sy + z * cy;
			float cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);
			a[o] = x1;
			a[o + 1] = y * cp - z1 * sp;
			a[o + 2] = y * sp + z1 * cp;
		}
	}

	private static final Map<Integer, Hang> HANGS = new HashMap<>();

	/** The server's FLARE reached the client: {@code entityId}'s lure bursts now ({@code tick} on the arm clock). */
	public static void flared(int entityId, long tick) {
		FLARES.put(entityId, tick);
	}

	/** Ticks since {@code entityId}'s lure last burst at the clock {@code time} (an ArmDraw's), or {@link ArmDraw#NEVER}. */
	public static float burstAge(int entityId, float time) {
		Long at = FLARES.get(entityId);
		if (at == null) return ArmDraw.NEVER;
		float age = time - (float) (at % CLOCK_WRAP);
		if (age < -CLOCK_WRAP / 2F) age += CLOCK_WRAP;
		else if (age > CLOCK_WRAP / 2F) age -= CLOCK_WRAP;
		if (age >= BURST_FORGET) return ArmDraw.NEVER;
		return Math.max(0F, age);
	}

	/** The view turned this frame (degrees; right and down are positive): the first-person rope is carried against it. */
	public static void cameraStep(float yawDeg, float pitchDeg) {
		if (Float.isFinite(yawDeg)) pendingYaw = ArmMath.clamp(pendingYaw + yawDeg, -90F, 90F);
		if (Float.isFinite(pitchDeg)) pendingPitch = ArmMath.clamp(pendingPitch + pitchDeg, -90F, 90F);
	}

	/** How much the first-person lure should make room for an item in the other hand, 0..1 (eased by the caller). */
	public static void setOffhand(float blend) {
		offhand = ArmMath.clamp01(blend);
	}

	/** What {@link #setOffhand} last said: the {@code busy} for {@link #restPointFirstPerson}. */
	public static float offhandBlend() {
		return offhand;
	}

	/** Forgets ropes and bursts that haven't been drawn for 200 ticks (called now and then from the module's tick). */
	public static void prune(long nowTick) {
		for (Iterator<Hang> it = HANGS.values().iterator(); it.hasNext(); ) {
			if (nowTick - it.next().lastTick > 200L) it.remove();
		}
		FLARES.values().removeIf(at -> nowTick - at > (long) BURST_FORGET + 40L);
	}

	/** Everything kept: joining or leaving a server, or a new world. */
	public static void clear() {
		HANGS.clear();
		FLARES.clear();
		pendingYaw = pendingPitch = 0F;
	}

	// ------------------------------------------------------------------ where things are (shared with the recorded point)

	/**
	 * The lure's rest point in the arm space A (px) of the arm holding the form, as the sheath layer records it (the
	 * Flare's start): the stalk's hook out past the outer face and in front, and the cage hanging the chain's length
	 * under it.
	 */
	public static Vector3f restPointThirdPerson(boolean slim, boolean mainArmLeft, Vector3f out) {
		float side = mainArmLeft ? 1F : -1F;
		float xc = (slim ? 0.5F : 1F) * side;
		return out.set(xc, HOOK_Y + (NODES - 1) * LINK_TP + 1.5F, HOOK_Z);
	}

	/**
	 * Where the stalk's hook is in third person (2026-11-15: "it should literally look like its hanging over
	 * the sword"): over the line of the hand (x as the arm's centre), up the arm, and out in front of the hand, so the
	 * ball hangs a few px over the blade, which runs forward (−Z) from the grip at y 8.56.
	 */
	private static final float HOOK_Y = 1.5F, HOOK_Z = -11.0F;

	/**
	 * The lure's rest point in the hand pass's view space (px): the stalk's arch end (−0.55, −0.32, −0.80 blocks, or
	 * (−0.40, −0.22, −0.85) with the other hand busy, {@code busy} easing between) and the lure 0.12 blocks under it,
	 * mirrored for a left-handed player.
	 */
	public static Vector3f restPointFirstPerson(boolean mainArmLeft, float busy, Vector3f out) {
		float k = ArmMath.clamp01(busy);
		float x = ArmMath.lerp(-0.45F, -0.36F, k), y = ArmMath.lerp(-0.10F, -0.04F, k), z = ArmMath.lerp(-0.80F, -0.85F, k);
		// over the hand that holds the form: the right of the screen for a right-handed player; the core hangs the chain's
		// length and the cage's cap under the arch end
		return out.set((mainArmLeft ? x : -x) * 16F, y * 16F - (NODES - 1) * LINK_FP - 1.5F, z * 16F);
	}

	/**
	 * The lantern's stalk, hanging chain and lure as spheres in the hand pass's view space at home, for {@link FpClearance}: the stalk's thickness
	 * and writhe from the screen's edge to the hook, the chain's links under it, and the lure's core with its cage (2.3 px across the core, the
	 * cage and its flare a little more). Its stalk can shrink toward its root and its hook's end move; the chain and the lure hang from the hook as drawn (a chain's
	 * length under it whatever the stalk does), so they ride the hook's move and are not scaled.
	 */
	public static void firstPersonBody(boolean mainArmLeft, float busy, FpClearance.Body out) {
		float side = mainArmLeft ? 1F : -1F;
		float k = ArmMath.clamp01(busy);
		Vector3f a = new Vector3f(side * -13.6F, -16.8F, -4.0F), b = new Vector3f(side * ArmMath.lerp(-12.0F, -10.5F, k), ArmMath.lerp(4.0F, 4.8F, k), ArmMath.lerp(-9.0F, -9.5F, k)),
				c = new Vector3f(side * ArmMath.lerp(-7.2F, -5.76F, k), ArmMath.lerp(-1.6F, -0.64F, k), ArmMath.lerp(-12.8F, -13.6F, k)), t = new Vector3f();
		out.clear(a.x, a.y, a.z, 0.5F);
		for (int i = 1; i <= 6; i++) {
			float u = i / 6F;
			ArmMath.bezier(a, b, c, u, t);
			out.addFixed(t.x, t.y, t.z, STALK_W0 * 0.5F + 0.95F, u);
		}
		// the chain hangs the lure's length under the hook, the core and its cage at the end of it
		Vector3f ball = restPointFirstPerson(mainArmLeft, busy, new Vector3f());
		for (int i = 1; i <= 2; i++) {
			float u = i / 3F;
			out.addFollow(c.x + (ball.x - c.x) * u, c.y + (ball.y - c.y) * u, c.z + (ball.z - c.z) * u, 0.7F, 5);
		}
		out.addFollow(ball.x, ball.y, ball.z, 3.6F, 5);
	}

	// ------------------------------------------------------------------ the entry points

	@Override
	public void emitThirdPerson(ArmDraw d, ArmLayer layer, SkinMesh m) {
		draw(d, layer, m, false);
	}

	@Override
	public void emitFirstPerson(ArmDraw d, ArmLayer layer, SkinMesh m) {
		draw(d, layer, m, true);
	}

	// ------------------------------------------------------------------ one draw's working state

	private static final Static ST = new Static();
	private static final Matrix4f LOCAL = new Matrix4f();

	// ------------------------------------------------------------------ the lantern on its own (2026-11-16)
	//
	// With only the lantern out (no tool), "the simple bracelet" could look cooler; of the options tried
	// (clutch, cuff, sleeve, palm, spine) the sleeve and the spines were liked, and a combination of the two asked for;
	// anything going round the arm follows its square section, not a circle. The sleeve and the spines alone stay
	// switchable for comparison with /morphclient lanternlook <name> on the viewing client (not a setting).
	public static final java.util.List<String> LANTERN_ONLY_LOOKS = java.util.List.of("sleevespine", "sleeve", "spine");
	public static final int LO_SLEEVE_SPINE = 0, LO_SLEEVE = 1, LO_SPINE = 2;
	private static int lanternOnlyLook = LO_SLEEVE_SPINE;

	public static int lanternOnlyLook() {
		return lanternOnlyLook;
	}

	public static void lanternOnlyLook(int look) {
		lanternOnlyLook = Math.clamp(look, 0, LANTERN_ONLY_LOOKS.size() - 1);
	}
	private static final Quaternionf TURN = new Quaternionf();
	private static final Vector3f P0 = new Vector3f(), P1 = new Vector3f(), P2 = new Vector3f();
	/** The lantern-only stalk root, while it is being blended with the tool's (a tool going or coming; see {@link #holdGate}). */
	private static final Vector3f B0 = new Vector3f(), B1 = new Vector3f();
	/**
	 * How much the lantern's own holder (sleeve, spines, knot) and its bare-arm stalk root hold, 0..1, in this draw: 1 with no tool,
	 * and with one, 1 minus how much of the skin arm the tool's static covers ({@code ArmDraw.armStatic}). So when a tool finishes
	 * retracting, or starts to form, the holder grows in or withdraws with the static sliding off or over the arm, and the stalk's
	 * root slides between the two places, instead of both switching in a frame (2026-11-16: the holder popped in fully
	 * grown at the end of a retract, and out again when a tool formed).
	 */
	private static float holdGate = 1F;
	private static final Vector3f V1 = new Vector3f(), V2 = new Vector3f(), V3 = new Vector3f();
	private static final float[] FT = new float[3], FN = new float[3], FB = new float[3];
	private static final float[] TX = new float[3 * SN], NX = new float[3 * SN], BX = new float[3 * SN];
	/** The chain's nodes as drawn (xyz each) and its axis and the cage's frame: U across it, A along it (down), V = U × A. */
	private static final float[] HP = new float[3 * NODES];
	private static final Vector3f AXIS = new Vector3f(), CU = new Vector3f(), CV = new Vector3f(), CORE_AT = new Vector3f();
	private static final float[] THORN = new float[9];
	private static final Vector3f UP_Y = new Vector3f(0F, 1F, 0F);

	// what the current draw has worked out, for the callbacks below (render thread, one draw at a time)
	private static ArmDraw cur;
	private static boolean fp, eroding;
	private static float time, ph, age, reach, arc, wrAmp, link, coreK, coreShine = 1F, ribOpen, ribGrow, ribAlpha, spin, thornW, tuftK;
	private static int lvl;
	/** The rib being drawn, the tuft hair being drawn. */
	private static int ribK, tuftI;
	/** The skin arm's centre x and its outward sign (the lantern-only holders go round it). */
	private static float ringX, ringOut;

	private static final Loft.CurveFn STALK = (s, out) -> centre(s, out);
	private static final Loft.RadiusFn STALK_R = s -> radius(s);
	/** The growth front's fringe, only while the stalk is still growing (a finished stalk keeps its tip). */
	private static final Loft.AlphaFn STALK_A = s -> eroding ? ArmMath.fringe((reach - s) * arc, 1.5F) : 1F;
	private static final Loft.CurveFn SINEW = (s, out) -> {
		float turn = 0.9F * ArmMath.TAU * s * (arc / 13F) + 0.045F * time + ph;
		float r = radius(s) * 1.05F + 0.18F;
		centre(s, out);
		frame(s);
		float c = (float) Math.cos(turn) * r, sn = (float) Math.sin(turn) * r;
		out.add(FN[0] * c + FB[0] * sn, FN[1] * c + FB[1] * sn, FN[2] * c + FB[2] * sn);
	};
	private static final Loft.RadiusFn SINEW_R = s -> 0.17F * (1F + 0.35F * (float) Math.sin(ph + 6F * s - 0.5F * time));
	private static final Loft.CurveFn THORN_PATH = (s, out) -> {
		float f = ArmMath.clamp(s, 0F, 1F) * 2F;
		int i = Math.min(1, (int) f);
		float u = f - i;
		out.set(ArmMath.lerp(THORN[3 * i], THORN[3 * i + 3], u), ArmMath.lerp(THORN[3 * i + 1], THORN[3 * i + 4], u), ArmMath.lerp(THORN[3 * i + 2], THORN[3 * i + 5], u));
	};
	private static final Loft.RadiusFn THORN_R = s -> thornW * (1F - 0.82F * s);
	/** The chain as one cord (far away): a polyline through the nodes. */
	private static final Loft.CurveFn ROPE = (s, out) -> {
		float f = ArmMath.clamp01(s) * (NODES - 1);
		int i = Math.min(NODES - 2, (int) f);
		float u = f - i;
		out.set(ArmMath.lerp(HP[3 * i], HP[3 * i + 3], u), ArmMath.lerp(HP[3 * i + 1], HP[3 * i + 4], u), ArmMath.lerp(HP[3 * i + 2], HP[3 * i + 5], u));
	};
	private static final Loft.RadiusFn ROPE_R = s -> 0.16F;
	/** One chain link, round the segment {@link #linkI}: an oval along it, its plane turned a quarter on every other link. */
	private static int linkI;
	private static final Vector3f LD = new Vector3f(), LW = new Vector3f(), LM = new Vector3f();
	private static final Loft.CurveFn LINK = (s, out) -> {
		float a = ArmMath.TAU * s;
		float along = 0.62F * link * (float) Math.cos(a), across = 0.36F * link * (float) Math.sin(a);
		out.set(LM.x + LD.x * along + LW.x * across, LM.y + LD.y * along + LW.y * across, LM.z + LD.z * along + LW.z * across);
	};
	private static final Loft.RadiusFn LINK_R = s -> 0.13F;
	/** The hook at the stalk's tip the chain hangs from: a half loop curling down and back up. */
	private static final Loft.CurveFn HOOK = (s, out) -> {
		float a = ArmMath.PI * 1.35F * s - 0.2F;
		float r = 0.55F;
		out.set(HP[0] + CU.x * r * (float) Math.sin(a) - AXIS.x * r * (float) Math.cos(a), HP[1] + CU.y * r * (float) Math.sin(a) - AXIS.y * r * (float) Math.cos(a),
				HP[2] + CU.z * r * (float) Math.sin(a) - AXIS.z * r * (float) Math.cos(a));
	};
	private static final Loft.RadiusFn HOOK_R = s -> 0.2F * (1F - 0.5F * s);
	private static final Loft.CurveFn RIB = (s, out) -> {
		float theta = ArmMath.PI * s * ribGrow;
		float phi = RIB_PHI[ribK] + spin + 0.14F * (float) Math.sin(ph + ribK * 1.7F + 0.21F * time) + RIB_TWIST[ribK] * s;
		float rr = RIB_R[ribK] * CAGE_R * ribOpen * (1F + 0.02F * (float) Math.sin(0.3F * time + ribK)) * (lvl >= 3 ? 1.12F : 1F);
		float sin = (float) Math.sin(theta) * rr, up = -(float) Math.cos(theta) * CAGE_H * coreK;
		float c = (float) Math.cos(phi) * sin, sn = (float) Math.sin(phi) * sin;
		out.set(CORE_AT.x + AXIS.x * up + CU.x * c + CV.x * sn, CORE_AT.y + AXIS.y * up + CU.y * c + CV.y * sn, CORE_AT.z + AXIS.z * up + CU.z * c + CV.z * sn);
	};
	private static final Loft.RadiusFn RIB_R_FN = s -> 0.2F;
	private static final Loft.CurveFn EYE = (s, out) -> {
		float a = ArmMath.TAU * s;
		float precess = 0.07F * time + ph;
		float r = EYE_R * (lvl >= 3 ? 1.1F : 1F) * (0.35F + 0.65F * Math.min(1F, coreK));
		float tilt = 0.34F + 0.1F * (float) Math.sin(0.13F * time + ph);
		float x = (float) Math.cos(a) * r, z = (float) Math.sin(a) * r;
		float y = (float) Math.sin(tilt) * (float) Math.cos(a - precess) * r;
		out.set(CORE_AT.x + CU.x * x + CV.x * z + AXIS.x * y, CORE_AT.y + CU.y * x + CV.y * z + AXIS.y * y, CORE_AT.z + CU.z * x + CV.z * z + AXIS.z * y);
	};
	private static final Loft.RadiusFn EYE_RAD = s -> (lvl >= 3 ? 0.2F : 0.15F) * (1F + 0.2F * (float) Math.sin(2F * ArmMath.TAU * s + 0.2F * time));
	private static final Loft.CurveFn TUFT_PATH = (s, out) -> {
		float len = 1.6F * tuftK * (1F + 0.2F * (float) Math.sin(ph * 3F + tuftI));
		float sway = (tuftI == 0 ? 0.5F : -0.45F) * s * s + 0.25F * (float) Math.sin(ph + 0.4F * time + tuftI * 2F) * s;
		out.set(CORE_AT.x + AXIS.x * (CAGE_H * coreK + len * s) + CU.x * sway + CV.x * sway * 0.4F,
				CORE_AT.y + AXIS.y * (CAGE_H * coreK + len * s) + CU.y * sway + CV.y * sway * 0.4F,
				CORE_AT.z + AXIS.z * (CAGE_H * coreK + len * s) + CU.z * sway + CV.z * sway * 0.4F);
	};
	private static final Loft.RadiusFn TUFT_R = s -> 0.13F * (1F - 0.8F * s);


	// ------------------------------------------------------------------ the draw

	private static void draw(ArmDraw d, ArmLayer layer, SkinMesh m, boolean firstPerson) {
		if (!d.lanternLit()) return;
		cur = d;
		fp = firstPerson;
		time = d.time();
		ph = d.phase();
		int lod = d.lod();
		boolean full = lod == ArmDraw.LOD_FULL;
		// the holder (with only the lantern out, third person, full detail) grows first and the stalk HOLD_LEAD later;
		// where no holder is drawn (first person, further away) there is nothing to wait for. The lead follows the holder's own
		// blend (holdGate) while a tool arrives or leaves, so the stalk and lure never jump in the frame the tool does
		// (2026-11-16: they jumped although the holder and the stalk's root already blended)
		boolean holderPossible = full && !fp;
		holdGate = d.form().isEmpty() ? 1F : 1F - d.armStatic();
		boolean holderDrawn = holderPossible && holdGate > 0.02F;
		float lead = HOLD_LEAD * holdGate;
		float raw = d.lanternAge();
		if (raw < 0F) {
			// going out (2026-11-16: a retraction animation): the build played backwards over LANTERN_OUT_TICKS
			// from where it had got to (the eye ring and the cage first, the core, the stalk into its root, the holder last)
			float out = ArmMath.clamp01((-raw - 1F) / ArmState.LANTERN_OUT_TICKS);
			ArmState st = Arms.stateByEntity(d.seed());
			float from = Math.min(st != null ? st.lanternOutFrom() : Float.MAX_VALUE, BUILT_AT + HOLD_LEAD);
			float back = 1F - out;
			float w = holderPossible ? holdGate : 0F; // how much of the holder's version of the playback this draw is
			holdAge = Math.max(0F, ArmMath.lerp(back * Math.max(0F, from - HOLD_LEAD), back * from, w));
			age = Math.max(0F, ArmMath.lerp(back * Math.max(0F, from - HOLD_LEAD), Math.max(0F, back * from - HOLD_LEAD), w));
		} else {
			holdAge = raw;
			age = Math.max(0F, raw - lead);
		}
		lvl = lanternLevel(d);
		ST.set(time, d.cadence(), 1F, d.seed());
		layout(d);
		if (holderDrawn) {
			// a bare skin arm (the tendril arm grows the stalk itself): what holds the lantern on it, before the stalk
			switch (lanternOnlyLook) {
				case LO_SLEEVE -> sleeve(d, layer, m);
				case LO_SPINE -> spine(d, layer, m);
				default -> {
					sleeve(d, layer, m);
					spine(d, layer, m);
				}
			}
			// the stalk leaves the arm just clear of it, so its open root is closed by a knot
			if (layer == ArmLayer.BLACK) rootKnot(m);
		}
		// near the lens (First-person Model's body, 2026-11-24): the free part, the stalk from its root, the chain and the lure, is drawn smaller about the stalk's root,
		// which stays on the arm with its holder, or goes with the sheath when a tool's own arm (smaller there too) carries it, see freeTransform; the whole free part
		// goes through one matrix, so every piece of it (and the chain's swing, see hangInWorld) agrees
		nearK = fp ? 1F : AccessoryView.near();
		freeScaled = nearK < 1F || (!fp && AccessoryView.sheath() < 1F);
		if (freeScaled) {
			freeTransform(d, nearK, fp ? 1F : AccessoryView.sheath(), NEAR_S);
			m.transform(NEAR_S);
		}
		try {
			drawFree(d, layer, m, lod, full);
		} finally {
			if (freeScaled) m.transform(null);
			nearK = 1F;
			freeScaled = false;
		}
	}

	/** The stalk, its bristles, the chain and the lure (everything but the holder on the arm), in the space {@code m} is set up to draw in. */
	private static void drawFree(ArmDraw d, ArmLayer layer, SkinMesh m, int lod, boolean full) {
		reach = ArmMath.sm(age / STALK_TICKS);
		eroding = d.dissolve() && reach < 0.999F;
		if (!(reach > 0.02F)) return;
		float[] fx = burst(d);
		// the core's growth, with the flare's swell and the low-biomass flicker
		float ignite = ArmMath.sm((age - CORE_FROM) / CORE_TICKS);
		ignite *= 1F + 0.3F * ArmMath.gauss(age - (CORE_FROM + CORE_TICKS), 1.6F);
		// the flame's flicker is in the core's brightness only: in its size it pumped the core, and the cage and the hang
		// follow the core's size, so the whole ball looked to change size (2026-11-15)
		coreK = ignite * fx[0];
		coreShine = flicker(d);
		ribOpen = fx[1];
		ribAlpha = fx[2];
		ribGrow = Math.min(ArmMath.sm((age - CAGE_FROM) / CAGE_TICKS), fx[3]);
		spin = 0.045F * time + ph;
		hang(d, lod, fx);
		if (lod >= ArmDraw.LOD_FAR) {
			lod3(d, layer, m);
			return;
		}
		if (lod == 2) {
			lod2(d, layer, m);
			return;
		}
		// the stalk, then its bristles, then the lure
		int sides = full ? (fp ? 6 : 4) : 4;
		int stations = full ? (fp ? 11 : 8) : 5;
		if (layer == ArmLayer.BLACK) {
			Loft.tube(m, STALK, 0F, reach, stations, sides, STALK_R, ArmLayer.BLACK, layer, ST, PART_STALK, 1F, 0F, arc * reach, UP_Y, STALK_A);
		}
		if (full) Loft.tube(m, SINEW, 0F, reach, fp ? 14 : 11, 3, SINEW_R, ArmLayer.RED, layer, ST, PART_SINEW, 2F, 0F, arc * reach, UP_Y, 1F);
		bristles(d, layer, m, full);
		if (coreK > 0.04F) lure(d, layer, m, full);
	}

	// ------------------------------------------------------------------ layout and the stalk

	/**
	 * Where the stalk's root is in arm space A (px) in third person, into {@code out}, for a draw whose holder weight is {@code gate} ({@link #holdGate}):
	 * on the tool's arm (out of the outer face above the hand, or from under the top plate in the armour look), on the skin arm's holder with the lantern
	 * alone, and slid between the two while a tool arrives or leaves. {@link #layout} takes its P0 from here, and the near-lens placement
	 * ({@link #freeTransform}) and the sheath layer's recorded rest point read the same point.
	 */
	static Vector3f rootPoint(ArmDraw d, float gate, Vector3f out) {
		float side = d.leftHand() ? 1F : -1F;
		float hx = d.slim() ? 1.5F : 2F;
		float xc = (d.slim() ? 0.5F : 1F) * side;
		float xo = xc + side * hx;
		if (!d.form().isEmpty() && SheathGeometry.lookOf(d) == ArmProtocol.LOOK_ARMOUR) {
			// the armour look (2026-11-16: "it looks like its coming out of the armor at the top of it, towards
			// the shoulder"): out from under the top edge of the highest plate on the outer side, the arch lifted to match;
			// the hook, and so where the ball hangs, stays where it always is
			// inside the top plate (its centre is hx * ARMOUR_FIT out, its faces 0.15 in and 0.3 out of that): the root and
			// its open end are hidden in the plate, and the stalk comes out through its top (it sat on the face)
			float plate = hx * SheathGeometry.ARMOUR_FIT - 0.35F + (d.chestArmour() ? 0.35F : 0F);
			out.set(xc + side * plate, SheathGeometry.armourTopY() + 0.4F, -0.3F);
		} else {
			out.set(xo - 0.4F * side, 5.0F, -0.6F);
		}
		if (d.form().isEmpty() || gate > 0F) {
			float holderX = xc + side * (hx + SLEEVE_MARGIN + (d.chestArmour() ? 0.35F : 0F));
			if (d.form().isEmpty()) out.set(holderX, LO_ROOT_Y, 0F);
			else out.lerp(B0.set(holderX, LO_ROOT_Y, 0F), gate);
		}
		return out;
	}

	/**
	 * The matrix the lantern's free part (stalk, chain, lure) is drawn through on First-person Model's body (2026-11-24): scaled by {@code near} about its
	 * root, which first moves with the arm form's sheath when that is drawn smaller about the hand end ({@code sheath} below 1, from
	 * {@link NearBodyScale}'s rule for the sheath: the stalk grows out of the tendrils or the plates, so it must not stay behind where they were; second
	 * pass, 2026-11-24). Only where a tool's own arm replaces the skin arm (not the plain-hand look, where the skin arm stays whole and the root is on
	 * it), and by how much of the skin arm the tool covers, so it slides with the holder's blend. Pure given {@code d}.
	 */
	public static Matrix4f freeTransform(ArmDraw d, float near, float sheath, Matrix4f out) {
		float gate = d.form().isEmpty() ? 1F : 1F - d.armStatic();
		Vector3f root = rootPoint(d, gate, FREE_ROOT);
		float w = d.form().isEmpty() || SheathGeometry.lookOf(d) == ArmProtocol.LOOK_HAND ? 0F : d.armStatic();
		// the sheath's own scale is about its hand end (x and z about the pivot line, y about the hand end), as ArmSheathLayer draws it
		float sx = sheath * root.x, sy = NearBodyScale.SHEATH_HAND_Y + sheath * (root.y - NearBodyScale.SHEATH_HAND_Y), sz = sheath * root.z;
		float rx = root.x + w * (sx - root.x), ry = root.y + w * (sy - root.y), rz = root.z + w * (sz - root.z);
		return out.identity().translate(rx, ry, rz).scale(near).translate(-root.x, -root.y, -root.z);
	}

	/** Sets the stalk's three control points for this view (section 2.11), the writhe, and the link of the rope. */
	private static void layout(ArmDraw d) {
		if (!fp) {
			// the arm holding the form (its outer face is −X on the right arm): out of the forearm's outer face above the
			// hand, up and forward, and over to the hook out in front, so the ball hangs over the tool's blade
			float out = d.leftHand() ? 1F : -1F;
			float hx = d.slim() ? 1.5F : 2F;
			float xc = (d.slim() ? 0.5F : 1F) * out;
			float xo = xc + out * hx;
			rootPoint(d, holdGate, P0);
			if (!d.form().isEmpty() && SheathGeometry.lookOf(d) == ArmProtocol.LOOK_ARMOUR) {
				P1.set(xo + 1.0F * out, -4.6F, -4.2F);
			} else {
				P1.set(xo + 0.6F * out, -3.0F, -5.0F);
			}
			if (d.form().isEmpty() || holdGate > 0F) {
				// the lantern on its own: out of the outer face, just clear of the skin arm, where the sleeve's tendrils (and the
				// spines' seam) meet; the hook, and so the ball, as always. With a tool coming or going the root slides between this
				// and the tool's by holdGate, so it never jumps (the root's own half of that is rootPoint's)
				Vector3f b1 = d.form().isEmpty() ? P1 : B1;
				b1.set(xo + 1.1F * out, -3.6F, -4.6F);
				if (!d.form().isEmpty()) P1.lerp(B1, holdGate);
			}
			P2.set(xc, HOOK_Y, HOOK_Z);
			wrAmp = 0.35F;
			link = LINK_TP;
			ringX = xc;
			ringOut = out;
		} else {
			// over the hand holding the form: the right of the screen for a right-handed player
			float side = d.leftHand() ? 1F : -1F;
			float k = ArmMath.clamp01(offhand);
			P0.set(side * -13.6F, -16.8F, -4.0F);
			// raised so the ball, a chain's length under the arch end, sits on the screen above the hand
			P1.set(side * ArmMath.lerp(-12.0F, -10.5F, k), ArmMath.lerp(4.0F, 4.8F, k), ArmMath.lerp(-9.0F, -9.5F, k));
			P2.set(side * ArmMath.lerp(-7.2F, -5.76F, k), ArmMath.lerp(-1.6F, -0.64F, k), ArmMath.lerp(-12.8F, -13.6F, k));
			// made room for what is in the hand (a bow drawn through its hook) and for the other accessories (FpClearance): only ever the hook's end moves
			FpLayout.apply(0, P0, P1, P2);
			wrAmp = 0.5F;
			link = LINK_FP;
		}
		// the stalk's length, for the feed coordinate and the fringe (a polyline through the control points is close)
		arc = 0.5F * (P0.distance(P1) + P1.distance(P2)) + 0.5F * P0.distance(P2);
		// frames carried along the base curve
		for (int i = 0; i < SN; i++) {
			float s = i / (float) (SN - 1);
			ArmMath.bezierTangent(P0, P1, P2, s, V2).normalize();
			TX[3 * i] = V2.x;
			TX[3 * i + 1] = V2.y;
			TX[3 * i + 2] = V2.z;
		}
		// N: start from a vector square to the first tangent, then carry it (parallel transport)
		V1.set(TX[0], TX[1], TX[2]);
		V3.set(Math.abs(V1.y) < 0.9F ? 0F : 1F, Math.abs(V1.y) < 0.9F ? 1F : 0F, 0F);
		float seed = V1.dot(V3);
		V2.set(V3.x - V1.x * seed, V3.y - V1.y * seed, V3.z - V1.z * seed).normalize();
		for (int i = 0; i < SN; i++) {
			V1.set(TX[3 * i], TX[3 * i + 1], TX[3 * i + 2]);
			if (i > 0) {
				float dot = V2.dot(V1);
				V2.sub(V1.x * dot, V1.y * dot, V1.z * dot);
				if (V2.lengthSquared() < 1e-8F) V2.set(NX[3 * i - 3], NX[3 * i - 2], NX[3 * i - 1]);
				V2.normalize();
			}
			NX[3 * i] = V2.x;
			NX[3 * i + 1] = V2.y;
			NX[3 * i + 2] = V2.z;
			V1.cross(V2, V3);
			BX[3 * i] = V3.x;
			BX[3 * i + 1] = V3.y;
			BX[3 * i + 2] = V3.z;
		}
	}

	/** Interpolates the carried frame at {@code s} into {@link #FT}, {@link #FN}, {@link #FB}. */
	private static void frame(float s) {
		float f = ArmMath.clamp01(s) * (SN - 1);
		int i = Math.min(SN - 2, (int) f);
		float u = f - i;
		for (int a = 0; a < 3; a++) {
			FT[a] = ArmMath.lerp(TX[3 * i + a], TX[3 * i + 3 + a], u);
			FN[a] = ArmMath.lerp(NX[3 * i + a], NX[3 * i + 3 + a], u);
			FB[a] = ArmMath.lerp(BX[3 * i + a], BX[3 * i + 3 + a], u);
		}
	}

	/** The stalk's centre line at {@code s}: the Bezier plus a writhe that is still at both ends (RageTethers' maths). */
	private static void centre(float s, Vector3f out) {
		ArmMath.bezier(P0, P1, P2, s, out);
		frame(s);
		float env = (float) Math.sin(Math.PI * s) * wrAmp;
		float a = env * (float) Math.sin(ph + 6F * s + 0.31F * time);
		float b = 0.6F * env * (float) Math.cos(0.8F * ph + 4F * s + 0.23F * time);
		out.add(FN[0] * a + FB[0] * b, FN[1] * a + FB[1] * b, FN[2] * a + FB[2] * b);
	}

	/** The stalk's tube radius at {@code s}: 1.0 to 0.45 px across, with its knuckles and the swelling pulses. */
	private static float radius(float s) {
		float w = ArmMath.lerp(STALK_W0, STALK_W1, s);
		for (int i = 0; i < KNUCKLE_S.length; i++) w *= 1F + KNUCKLE_A[i] * ArmMath.gauss(s - KNUCKLE_S[i], 0.05F);
		w *= 1F + 0.45F * cur.pulse(s * arc, arc + 10F);
		return FistGeometry.width(w, fp ? 6 : 4);
	}

	// ------------------------------------------------------------------ bristles and the clutch

	private static void bristles(ArmDraw d, ArmLayer layer, SkinMesh m, boolean full) {
		int n = lvl <= 1 ? 5 : BR_S.length;
		for (int i = 0; i < n; i++) {
			if (!full && !(i == 1 || i == 3)) continue; // LOD 1: the two big barbs
			boolean barb = BR_WAVE[i] == 0F;
			ArmLayer pieceLayer = barb ? ArmLayer.BLACK : ArmLayer.RED;
			if (layer != pieceLayer) continue;
			float sprout = ArmMath.sm((age - (SPROUT_FROM + i * SPROUT_STAGGER)) / SPROUT_TICKS);
			if (BR_S[i] > reach || sprout < 0.03F) continue;
			float len = BR_LEN[i] * sprout * (barb && lvl >= 3 ? 1.25F : 1F) * (fp ? 1.15F : 1F);
			float s = BR_S[i];
			centre(s, V1);
			frame(s);
			float az = BR_AZ[i] + 0.15F * (float) Math.sin(ph + 0.17F * time + i);
			float rx = FN[0] * (float) Math.cos(az) + FB[0] * (float) Math.sin(az), ry = FN[1] * (float) Math.cos(az) + FB[1] * (float) Math.sin(az),
					rz = FN[2] * (float) Math.cos(az) + FB[2] * (float) Math.sin(az);
			// the side direction, square to the radial and the stalk
			float sx = FT[1] * rz - FT[2] * ry, sy = FT[2] * rx - FT[0] * rz, sz = FT[0] * ry - FT[1] * rx;
			float lean = (float) Math.toRadians(BR_LEAN[i]), cl = (float) Math.cos(lean), sl = (float) Math.sin(lean);
			float root = radius(s) * 0.6F;
			float p0x = V1.x + rx * root, p0y = V1.y + ry * root, p0z = V1.z + rz * root;
			float dx = rx * cl + FT[0] * sl + sx * BR_SKEW[i], dy = ry * cl + FT[1] * sl + sy * BR_SKEW[i], dz = rz * cl + FT[2] * sl + sz * BR_SKEW[i];
			float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			dx /= dl;
			dy /= dl;
			dz /= dl;
			float half = len * 0.5F;
			float p1x = p0x + dx * half, p1y = p0y + dy * half, p1z = p0z + dz * half;
			// the second half bends: toward the tip by the curl (a hook), and a cilium sways in a travelling wave
			float wave = BR_WAVE[i] * (float) Math.sin(ph + 5F * s + 0.55F * time + i * 1.3F);
			float qx = dx + FT[0] * BR_CURL[i] * 1.6F + sx * wave - rx * BR_CURL[i] * 0.4F, qy = dy + FT[1] * BR_CURL[i] * 1.6F + sy * wave - ry * BR_CURL[i] * 0.4F,
					qz = dz + FT[2] * BR_CURL[i] * 1.6F + sz * wave - rz * BR_CURL[i] * 0.4F;
			float ql = (float) Math.sqrt(qx * qx + qy * qy + qz * qz);
			qx /= ql;
			qy /= ql;
			qz /= ql;
			THORN[0] = p0x;
			THORN[1] = p0y;
			THORN[2] = p0z;
			THORN[3] = p1x;
			THORN[4] = p1y;
			THORN[5] = p1z;
			THORN[6] = p1x + qx * half;
			THORN[7] = p1y + qy * half;
			THORN[8] = p1z + qz * half;
			thornW = BR_W[i] * (fp ? 1.2F : 1F) * 0.5F * sprout + 0.02F;
			Loft.tube(m, THORN_PATH, 0F, 1F, 3, 3, THORN_R, pieceLayer, layer, ST, PART_BARB + i, 2F, 0F, len, UP_Y, 1F);
			if (full && BR_FORK[i] && sprout > 0.5F) {
				// a small branch off the middle, leaning the other way
				float k = len * 0.35F;
				float fx = p1x, fy = p1y, fz = p1z;
				THORN[0] = fx;
				THORN[1] = fy;
				THORN[2] = fz;
				THORN[3] = fx + (dx * 0.4F - sx * 0.9F + FT[0] * 0.3F) * k;
				THORN[4] = fy + (dy * 0.4F - sy * 0.9F + FT[1] * 0.3F) * k;
				THORN[5] = fz + (dz * 0.4F - sz * 0.9F + FT[2] * 0.3F) * k;
				thornW *= 0.6F;
				// two stations: one slim tapering piece
				Loft.tube(m, FORK_PATH, 0F, 1F, 2, 3, THORN_R, ArmLayer.BLACK, layer, ST, PART_BARB + 9, 2F, 0F, k, UP_Y, 1F);
			}
		}
	}

	private static final Loft.CurveFn FORK_PATH = (s, out) -> out.set(ArmMath.lerp(THORN[0], THORN[3], s), ArmMath.lerp(THORN[1], THORN[4], s), ArmMath.lerp(THORN[2], THORN[5], s));

	// ------------------------------------------------------------------ the lantern-only holders: the sleeve and the spines

	private static final int PART_SLEEVE = 110, PART_SPINE = 120, PART_SPINE_SEAM = 127, PART_KNOT = 128;
	private static final Matrix4f HOLD = new Matrix4f();
	private static final Vector3f HB = new Vector3f();
	/**
	 * Where the stalk leaves the arm with only the lantern out (y, px down the arm), and how far out of the skin arm's
	 * faces the sleeve's tendrils and the stalk's root lie, and the spines' seam (the sleeve layer is 0.25 px out; the
	 * thickest tendril is 0.44 px across its half, so 0.8 keeps every one clear of it).
	 */
	static final float LO_ROOT_Y = 4.4F, SLEEVE_MARGIN = 0.8F, SEAM_MARGIN = 0.52F, SPINE_MARGIN = 0.34F;
	/**
	 * The pulse's path with only the lantern out (2026-11-16: the pulse on the sleeve's red tendril, moving
	 * behind the spines and down the arm): the feed coordinate is 0 at the stalk's root and runs down the arm, along the
	 * spines' seam to the wrist ({@link #SEAM_FEED}) and along the red tendril's winding to the wrist ({@link #HOLD_FEED}),
	 * so the arm's pulses start at the root and run down both. The renderer passes HOLD_FEED as the feed length.
	 */
	public static final float HOLD_FEED = 12F, SEAM_FEED = 5.5F;
	/** The spines up the outer forearm, wrist first (y, px), and their lengths (px). */
	private static final float[] SPINE_Y = {8.7F, 7.7F, 6.7F, 5.7F}, SPINE_LEN = {0.8F, 1.0F, 1.2F, 1.45F};
	/** Where each sleeve tendril ends round the root knot: above or below its middle (px) and to either side (radians round the arm). */
	private static final float[] SLEEVE_END_Y = {-0.55F, 0.45F, -0.15F, 0.55F, 0.1F}, SLEEVE_END_Z = {0.45F, -0.4F, 0F, 0.5F, -0.5F};
	/** The root knot: across where the stalk leaves it, across where it sinks into the arm (px). */
	private static final float KNOT_OUT = 1.35F, KNOT_IN = 1.8F, KNOT_BEYOND = 0.75F;
	private static final Vector3f ALONG_Z = new Vector3f(0F, 0F, 1F), INWARD = new Vector3f();

	/**
	 * The root knot (2026-11-16: the stalk's base was see-through from behind, "this could be fixed with the
	 * sleeves tendrils forming up around that spot"): a lump from just inside the skin arm out to where the stalk leaves
	 * it, closing the stalk's open root; the sleeve's tendrils end round its rim. Grows with the stalk.
	 */
	private static void rootKnot(SkinMesh m) {
		float k = Math.min(ArmMath.sm(holdAge / 3F), holdGate); // at the root, where the holder starts
		if (k < 0.03F) return;
		INWARD.set(-ringOut, 0F, 0F);
		// from KNOT_BEYOND out past P0 (the stalk's root ring is centred on P0, 0.5 px across its half, more as a pulse
		// swells it) to just inside the arm, so the whole open root is inside the knot
		HOLD.identity().translate(P0.x - INWARD.x * KNOT_BEYOND * k, P0.y, P0.z).rotate(TURN.rotationTo(ALONG_Z, INWARD));
		float len = (KNOT_BEYOND + SLEEVE_MARGIN + 0.35F) * k;
		m.frustum(HOLD, len, KNOT_OUT * k, KNOT_OUT * k, KNOT_IN * k, KNOT_IN * k, ST.u0(PART_KNOT, 0, 0.5F), ST.v0(PART_KNOT, 0, 0.5F), 2F, 1F, 1F, m.tint());
	}
	private static int holdK;
	private static float holdGrow, holdAge;

	/** The skin arm's half-width (third person). */
	private static float armHx() {
		return cur.slim() ? 1.5F : 2F;
	}

	/**
	 * The point at angle {@code a} round the skin arm, {@code margin} out from its faces (more over a chestplate's arm),
	 * at height {@code y}: on a superellipse (exponent 8) round the arm's square section, so whatever goes round it
	 * follows the faces and stays clear of the corners (2026-11-16: "going around the arm would be better in
	 * a square shape vs in a circle").
	 */
	private static void onArm(float a, float margin, float y, Vector3f out) {
		float m = margin + (cur.chestArmour() ? 0.35F : 0F);
		float c = (float) Math.cos(a), sn = (float) Math.sin(a);
		float sx = Math.signum(c) * (float) Math.pow(Math.abs(c), 0.25), sz = Math.signum(sn) * (float) Math.pow(Math.abs(sn), 0.25);
		out.set(ringX + (armHx() + m) * sx, y, (2F + m) * sz);
	}

	/** The angle round the arm of its outer face (−X on the right arm, +X on the left). */
	private static float outerAngle() {
		return ringOut < 0F ? ArmMath.PI : 0F;
	}

	/**
	 * Sleeve tendril {@link #holdK}: from the wrist, winding up round the forearm on its square section, each a different
	 * part of a turn and alternately each way, all ending at the stalk's root on the outer face (P0), where they meet.
	 */
	private static final Loft.CurveFn SLEEVE = (s, out) -> {
		int k = holdK;
		float dir = k % 2 == 0 ? 1F : -1F;
		float aEnd = outerAngle(), a0 = aEnd - dir * (0.55F + 0.12F * k) * ArmMath.TAU;
		float a = ArmMath.lerp(a0, aEnd, s) + 0.05F * (1F - s) * (float) Math.sin(0.11F * time + 3F * s + k);
		onArm(a, SLEEVE_MARGIN, ArmMath.lerp(9.4F, LO_ROOT_Y + SLEEVE_END_Y[k], s), out);
		// on the outer face (where every tendril ends) the end's place round the knot is a sideways offset in px, eased in:
		// an angle offset there would be stretched by the superellipse to over 1.5 px, clear of the knot (found in review)
		out.z += SLEEVE_END_Z[k] * ArmMath.sm((s - 0.75F) / 0.25F);
	};
	private static final Loft.RadiusFn SLEEVE_R = s -> ((holdK == 2 ? 0.4F : 0.34F) * ArmMath.sm(s / 0.1F) * (1F - 0.35F * ArmMath.sm((s - 0.8F) / 0.2F))
			* (1F + 0.1F * (float) Math.sin(0.15F * time + 8F * s)) + 0.04F);

	/** Sleeve: five tendrils (one red) winding up the forearm from the wrist and meeting where the stalk leaves the arm. */
	private static void sleeve(ArmDraw d, ArmLayer layer, SkinMesh m) {
		float g = Math.min(ArmMath.sm(holdAge / HOLD_TICKS), holdGate);
		if (g < 0.03F) return;
		for (int k = 0; k < 5; k++) {
			ArmLayer own = k == 2 ? ArmLayer.RED : ArmLayer.BLACK;
			// the red one also in the glow pass, where a passing pulse lights it (Loft draws only the lit part there)
			if (layer != own && !(own == ArmLayer.RED && Loft.pulseGlowPass(layer))) continue;
			holdK = k;
			// the feed runs from the root (s = 1) down to the wrist (s = 0): the red tendril carries the pulses down the arm
			// grows from the root (s = 1) down to the wrist, as the arm forms from the shoulder down
			Loft.tube(m, SLEEVE, 1F - g, 1F, 9, 3, SLEEVE_R, own, layer, ST, PART_SLEEVE + k, 2F, HOLD_FEED * g, 0F, UP_Y, 1F);
		}
	}

	/** The glowing seam the spines stand on: straight up the outer face from the wrist to the stalk's root. */
	private static final Loft.CurveFn SPINE_SEAM = (s, out) -> {
		onArm(outerAngle(), SEAM_MARGIN, ArmMath.lerp(LO_ROOT_Y, 9.3F, s * holdGrow), out);
		out.z += 0.08F * (float) Math.sin(0.1F * time + 5F * s); // a slight sideways writhe (as an angle it was stretched to ±1 px)
	};
	// wide enough to show round the spines' bases, so the pulse is seen running behind them
	private static final Loft.RadiusFn SPINE_SEAM_R = s -> 0.26F;

	/** Spine: a row of spines up the outer forearm on a glowing seam, each leaning up the arm, longer toward the elbow. */
	private static void spine(ArmDraw d, ArmLayer layer, SkinMesh m) {
		float g = Math.min(ArmMath.sm(holdAge / HOLD_TICKS), holdGate);
		if (g < 0.03F) return;
		holdGrow = g;
		// in the red pass, and again in the glow pass where a pulse is passing (Loft's pulse overlay): behind the spines
		if (layer == ArmLayer.RED || Loft.pulseGlowPass(layer)) {
			Loft.tube(m, SPINE_SEAM, 0F, 1F, 8, 3, SPINE_SEAM_R, ArmLayer.RED, layer, ST, PART_SPINE_SEAM, 2F, 0F, SEAM_FEED * g, UP_Y, 1F);
		}
		if (layer != ArmLayer.BLACK) return;
		float theta = ringOut < 0F ? -ArmMath.PI / 2F : ArmMath.PI / 2F; // local +Z onto the outer face's normal
		for (int i = 0; i < SPINE_Y.length; i++) {
			float front = ArmMath.lerp(LO_ROOT_Y, 9.3F, g); // the seam's front, coming down from the root
			float k = ArmMath.sm((front - SPINE_Y[i] + 0.6F) / 1.2F); // each spine grows as the seam reaches it
			if (k < 0.03F) continue;
			onArm(outerAngle(), SPINE_MARGIN, SPINE_Y[i], HB);
			HOLD.identity().translate(HB.x, HB.y, HB.z).rotateY(theta).rotateX((float) Math.toRadians(40));
			m.frustum(HOLD, SPINE_LEN[i] * k, 0.6F, 0.5F, 0.06F, 0.06F, ST.u0(PART_SPINE, i, 0.5F), ST.v0(PART_SPINE, i, 0.5F), 2F, 1F, 1F, m.tint());
		}
	}

	// ------------------------------------------------------------------ the lure

	/** The flare's effect on the lure at {@code d}'s clock: core scale, cage opening, cage alpha, cage regrowth (each 1 at rest). */
	private static float[] burst(ArmDraw d) {
		float b = burstAge(d.seed(), d.time());
		BURST[0] = BURST[1] = BURST[2] = BURST[3] = 1F;
		if (b >= BURST_FORGET) return BURST;
		if (b < BURST_AT) {
			float u = ArmMath.sm(b / BURST_AT);
			BURST[0] = 1F + u; // the core swells to twice its size
			BURST[1] = 1F - 0.35F * u; // and the cage tightens round it
		} else if (b < BURST_END) {
			float u = ArmMath.sm((b - BURST_AT) / (BURST_END - BURST_AT));
			BURST[0] = 2F - 0.7F * u;
			BURST[1] = ArmMath.lerp(0.65F, 3.0F, u);
			BURST[2] = 1F - u;
			BURST[3] = 1F;
		} else {
			float u = ArmMath.sm((b - BURST_END) / 14F);
			BURST[0] = ArmMath.lerp(1.3F, 1F, u);
			BURST[1] = 1F;
			BURST[2] = 1F;
			BURST[3] = b < REGROW_FROM ? 0F : ArmMath.sm((b - REGROW_FROM) / REGROW_TICKS);
		}
		return BURST;
	}

	private static final float[] BURST = new float[4];

	/** The core's brightness and size flicker: a flame's, harder when the arm is thin, blinking when it is failing. */
	private static float flicker(ArmDraw d) {
		float f = 1F + 0.10F * (float) Math.sin(1.3F * time + ph) + 0.07F * (float) Math.sin(2.9F * time + 2F * ph) + 0.04F * (float) Math.sin(5.3F * time);
		int low = d.lowStage();
		if (low >= ArmDraw.LOW_THINNING) f += 0.12F * (float) Math.sin(7.1F * time + ph);
		if (low >= ArmDraw.LOW_STARVING) f *= 0.85F;
		if (low >= ArmDraw.LOW_FAILING && ArmMath.frac(time / 37F + ph) < 0.1F) f *= 0.3F;
		return f;
	}

	/** Works out where the rope hangs this frame (stepped once per frame and view at LOD 0, a still sway otherwise). */
	private static void hang(ArmDraw d, int lod, float[] fx) {
		centre(Math.min(reach, 1F), V1);
		float rx = V1.x, ry = V1.y, rz = V1.z;
		boolean still = d.downX() == 0F && d.downY() == -1F && d.downZ() == 0F; // a doll or a picture: nothing to hang from
		float gx, gy, gz;
		if (fp) {
			gx = 0F;
			gy = -1F;
			gz = 0F;
		} else {
			gx = 0F;
			gy = 1F; // arm space: +Y runs down the arm, which hangs down
			gz = 0F;
		}
		if (lod == ArmDraw.LOD_FULL && !still) {
			V2.set(d.downX(), d.downY(), d.downZ());
			float l = V2.length();
			if (l > 1e-4F && Float.isFinite(l)) {
				gx = V2.x / l;
				gy = V2.y / l;
				gz = V2.z / l;
			}
			Hang h = HANGS.computeIfAbsent(d.seed() * 4 + (fp ? 1 : 0), k -> new Hang());
			long now = Arms.TICK;
			if (now - h.lastTick > 5L) h.started = false;
			Matrix4fc world = worldPose;
			if (!fp && world != null) {
				// scaled near the lens: the chain's world is the pose through that scale, and its links are as much shorter
				if (freeScaled) world = POSE_S.set(world).mul(NEAR_S);
				hangInWorld(h, world, rx, ry, rz, now);
			} else {
				if (h.lastTime != time) {
					float dt = Float.isNaN(h.lastTime) ? 1F : time - h.lastTime;
					if (dt < 0F || dt > 4F) dt = 1F;
					float yaw = 0F, pitch = 0F;
					if (fp) {
						yaw = (float) Math.toRadians(ArmMath.clamp(pendingYaw, -90F, 90F));
						pitch = (float) Math.toRadians(ArmMath.clamp(pendingPitch, -90F, 90F));
						yaw = ArmMath.clamp(yaw, -MAX_CARRY, MAX_CARRY);
						pitch = ArmMath.clamp(pitch, -MAX_CARRY, MAX_CARRY);
						pendingYaw = pendingPitch = 0F;
					}
					h.avoid = fp;
					h.step(rx, ry, rz, gx, gy, gz, link, dt, yaw, pitch);
					// the flare's recoil: the lure jumps up the moment it bursts, once
					float b = burstAge(d.seed(), time);
					float fired = time - b;
					if (b >= BURST_AT && b < BURST_AT + 1.2F && !(Math.abs(h.lastFire - fired) < 1F)) {
						h.lastFire = fired;
						h.impulse(-gx * 1.1F, -gy * 1.1F, -gz * 1.1F);
					}
					h.lastTime = time;
					h.lastTick = now;
				}
				h.lastTick = now;
				System.arraycopy(h.p, 0, HP, 0, 3 * NODES);
			}
		} else {
			// a still hang with a slow sway across the stalk's side
			frame(Math.min(reach, 1F));
			float sway = 0.16F * (float) Math.sin(ph + 0.11F * time) + 0.06F * (float) Math.sin(2.3F * ph + 0.27F * time);
			float dx = gx + FB[0] * sway, dy = gy + FB[1] * sway, dz = gz + FB[2] * sway;
			float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			dx /= dl;
			dy /= dl;
			dz /= dl;
			for (int i = 0; i < NODES; i++) {
				HP[3 * i] = rx + dx * link * i;
				HP[3 * i + 1] = ry + dy * link * i;
				HP[3 * i + 2] = rz + dz * link * i;
			}
		}
		// the cage's frame: A along the chain's last link (down), U across it (from the stalk's binormal), V = U × A
		int e = 3 * (NODES - 1);
		AXIS.set(HP[e] - HP[e - 3], HP[e + 1] - HP[e - 2], HP[e + 2] - HP[e - 1]);
		if (AXIS.lengthSquared() < 1e-8F) AXIS.set(gx, gy, gz);
		AXIS.normalize();
		frame(Math.min(reach, 1F));
		CU.set(FB[0], FB[1], FB[2]);
		CU.sub(AXIS.x * CU.dot(AXIS), AXIS.y * CU.dot(AXIS), AXIS.z * CU.dot(AXIS));
		if (CU.lengthSquared() < 1e-6F) {
			CU.set(1F, 0F, 0F);
			CU.sub(AXIS.x * CU.dot(AXIS), AXIS.y * CU.dot(AXIS), AXIS.z * CU.dot(AXIS));
		}
		CU.normalize();
		CU.cross(AXIS, CV);
		// the chain's last link holds the cage by its cap: the core hangs a cage's height under it
		CORE_AT.set(HP[e], HP[e + 1], HP[e + 2]).fma(CAGE_H * Math.max(0.4F, coreK) + 0.25F, AXIS);
	}

	/**
	 * Third person in the world (2026-11-15: other people should see its physics too): the chain is simulated
	 * in world space, so it lags and swings as its holder walks and turns, as everyone watching sees it, and is then
	 * brought back into the arm's space to be drawn. Its space is px from a world point near the hook (kept until the
	 * hook goes far, so the floats stay small).
	 */
	private static void hangInWorld(Hang h, Matrix4fc pose, float rx, float ry, float rz, long now) {
		pose.transformPosition(rx, ry, rz, V3); // blocks, from the camera
		double wx = V3.x + camX, wy = V3.y + camY, wz = V3.z + camZ;
		if (!h.started || Math.abs(wx - h.ox) > 16 || Math.abs(wy - h.oy) > 16 || Math.abs(wz - h.oz) > 16) {
			// a new start, or a long way from the old one (a teleport): measure from here, and hang straight down
			if (h.started) {
				float sx = (float) ((h.ox - wx) * 16), sy = (float) ((h.oy - wy) * 16), sz = (float) ((h.oz - wz) * 16);
				for (int i = 0; i < NODES; i++) {
					h.p[3 * i] += sx;
					h.p[3 * i + 1] += sy;
					h.p[3 * i + 2] += sz;
				}
				if (Math.abs(sx) > 16 * 16 || Math.abs(sy) > 16 * 16 || Math.abs(sz) > 16 * 16) h.started = false;
			}
			h.ox = wx;
			h.oy = wy;
			h.oz = wz;
		}
		float sx = (float) ((wx - h.ox) * 16), sy = (float) ((wy - h.oy) * 16), sz = (float) ((wz - h.oz) * 16);
		if (h.lastTime != time) {
			float dt = Float.isNaN(h.lastTime) ? 1F : time - h.lastTime;
			if (dt < 0F || dt > 4F) dt = 1F;
			h.step(sx, sy, sz, 0F, -1F, 0F, link * nearK, dt, 0F, 0F);
			h.lastTime = time;
		}
		h.lastTick = now;
		// back into the arm's space: px from the world point, to blocks from the camera, through the pose's inverse
		POSE_INV.set(pose).invert();
		for (int i = 0; i < NODES; i++) {
			float bx = (float) (h.p[3 * i] / 16.0 + h.ox - camX), by = (float) (h.p[3 * i + 1] / 16.0 + h.oy - camY), bz = (float) (h.p[3 * i + 2] / 16.0 + h.oz - camZ);
			POSE_INV.transformPosition(bx, by, bz, V3);
			HP[3 * i] = V3.x;
			HP[3 * i + 1] = V3.y;
			HP[3 * i + 2] = V3.z;
		}
		// the hook is the stalk's tip exactly (the simulation's root is there, rounding aside)
		HP[0] = rx;
		HP[1] = ry;
		HP[2] = rz;
	}

	/** The world pass's pose for the next third-person lure (arm px to blocks from the camera), and the camera. */
	private static @Nullable Matrix4fc worldPose;
	private static double camX, camY, camZ;
	private static final Matrix4f POSE_INV = new Matrix4f();
	/** The free part's near-lens matrix ({@link #freeTransform}, about the stalk's root), and the world pose through it. */
	private static final Matrix4f NEAR_S = new Matrix4f(), POSE_S = new Matrix4f();
	private static float nearK = 1F;
	/** Whether the free part goes through {@link #NEAR_S} in this draw (near the lens, or its root moved with a smaller sheath). */
	private static boolean freeScaled;
	/** Scratch for {@link #freeTransform}: the render thread draws one lure at a time. */
	private static final Vector3f FREE_ROOT = new Vector3f();

	/**
	 * The world pass sets this round each third-person lure it draws (ArmSheathLayer), and clears it after: with it the
	 * chain swings in the world (see {@link #hangInWorld}); without it (the doll, previews) it hangs in the arm's space.
	 */
	public static void worldFrame(@Nullable Matrix4fc pose, double cameraX, double cameraY, double cameraZ) {
		worldPose = pose;
		camX = cameraX;
		camY = cameraY;
		camZ = cameraZ;
	}

	private static void lure(ArmDraw d, ArmLayer layer, SkinMesh m, boolean full) {
		// the chain from the stalk's hook down to the cage's cap: links up close, a cord further out
		if (layer == ArmLayer.BLACK) {
			if (full) {
				Loft.tube(m, HOOK, 0F, 1F, 4, 3, HOOK_R, ArmLayer.BLACK, layer, ST, PART_ROPE, 2F, 0F, 1F, UP_Y, 1F);
				for (linkI = 0; linkI + 1 < NODES; linkI++) {
					int a = 3 * linkI, b = a + 3;
					LD.set(HP[b] - HP[a], HP[b + 1] - HP[a + 1], HP[b + 2] - HP[a + 2]);
					if (LD.lengthSquared() < 1e-8F) LD.set(AXIS);
					LD.normalize();
					LM.set(HP[a] + HP[b], HP[a + 1] + HP[b + 1], HP[a + 2] + HP[b + 2]).mul(0.5F);
					// across: the cage's U made square to the link, turned a quarter on every other link
					LW.set(CU).fma(-CU.dot(LD), LD);
					if (LW.lengthSquared() < 1e-6F) LW.set(CV);
					LW.normalize();
					if ((linkI & 1) == 1) LD.cross(LW, LW).normalize();
					Loft.closedTube(m, LINK, 6, 3, LINK_R, ArmLayer.BLACK, layer, ST, PART_ROPE + 1, 2F, 0F, UP_Y, 1F);
				}
			} else {
				Loft.tube(m, ROPE, 0F, 1F, NODES, 3, ROPE_R, ArmLayer.BLACK, layer, ST, PART_ROPE, 2F, 0F, (NODES - 1) * link, UP_Y, 1F);
			}
			// the cage's cap: a little socket where the ribs meet, the last link through its eye
			float capW = 0.85F * Math.max(0.4F, coreK);
			int c = 3 * (NODES - 1);
			LOCAL.identity().translate(HP[c], HP[c + 1], HP[c + 2]).rotate(TURN.rotationTo(UP_Y, AXIS));
			m.frustum(LOCAL, 0.55F, 0.35F, 0.35F, capW, capW, ST.u0(PART_ROPE, 2, 0F), ST.v0(PART_ROPE, 2, 0F), 2F, 1F, 1F, m.tint());
		}
		// the cage: five ribs, each its own radius and twist, growing round the core from the top
		boolean ribsShown = ribAlpha > 0.02F && (d.dissolve() || ribAlpha >= 0.5F) && ribGrow > 0.03F;
		int ribs = full ? 5 : 3;
		int segs = full ? (fp ? 5 : 4) : 3;
		if (ribsShown && layer == ArmLayer.BLACK) {
			for (ribK = 0; ribK < ribs; ribK++) {
				Loft.tube(m, RIB, 0F, 1F, segs + 1, 3, RIB_R_FN, ArmLayer.BLACK, layer, ST, PART_RIB + ribK, 2F, 0F, 3F, UP_Y, d.dissolve() ? ribAlpha : 1F);
			}
		}
		// the core: two boxes, one turned, so it reads as a lump of light and not a die; bigger and brighter with the
		// brightness its holder chose
		int bright = brightness(d);
		float size = CORE * coreK * (0.82F + 0.06F * bright);
		if (size > 0.05F && (d.glow() ? layer == ArmLayer.GLOW : layer == ArmLayer.RED)) {
			int shade = FistGeometry.grey(ArmMath.clamp01((0.45F + 0.1F * bright) * coreShine));
			basis(0F);
			m.box(LOCAL, -size / 2F, -size / 2F, -size / 2F, size, size, size, ST.u0(PART_CORE, 0, 0F), ST.v0(PART_CORE, 0, 0F), 1F, 1F, shade);
			basis(0.6F + 0.4F * (float) Math.sin(0.2F * time + ph));
			LOCAL.rotateX(0.78F).rotateZ(0.6F);
			float small = size * 0.78F;
			m.box(LOCAL, -small / 2F, -small / 2F, -small / 2F, small, small, small, ST.u0(PART_CORE, 1, 0F), ST.v0(PART_CORE, 1, 0F), 1F, 1F, shade);
		}
		if (!full) return;
		// the eye ring, popping in after the rest
		float eye = ArmMath.sm((age - EYE_FROM) / 3F);
		if (eye > 0.03F && layer == ArmLayer.RED && coreK > 0.3F) {
			Loft.closedTube(m, EYE, 8, 3, EYE_RAD, ArmLayer.RED, layer, ST, PART_EYE, 2F, 0F, UP_Y, 1F);
		}
		// the tuft under the cage, and two motes in orbit
		if (layer == ArmLayer.BLACK && coreK > 0.3F && ribsShown) {
			tuftK = ArmMath.sm((age - (CAGE_FROM + 2F)) / 4F);
			if (tuftK > 0.05F) {
				for (tuftI = 0; tuftI < 2; tuftI++) {
					Loft.tube(m, TUFT_PATH, 0F, 1F, 3, 3, TUFT_R, ArmLayer.BLACK, layer, ST, PART_TUFT + tuftI, 2F, 0F, 1.6F, UP_Y, 1F);
				}
			}
		}
		if (layer == ArmLayer.GLOW && d.glow() && coreK > 0.5F) {
			for (int i = 0; i < 2; i++) {
				float a = (i == 0 ? 0.21F : -0.15F) * time + ph * (i + 1);
				float r = i == 0 ? 1.9F : 2.35F, y = (i == 0 ? 0.35F : -0.5F) * (float) Math.sin(0.17F * time + i * 2F);
				float c = (float) Math.cos(a) * r, sn = (float) Math.sin(a) * r;
				float mx = CORE_AT.x + CU.x * c + CV.x * sn + AXIS.x * y, my = CORE_AT.y + CU.y * c + CV.y * sn + AXIS.y * y,
						mz = CORE_AT.z + CU.z * c + CV.z * sn + AXIS.z * y;
				LOCAL.identity().translate(mx, my, mz).rotateXYZ(a, 0.7F * a, 0.3F);
				m.box(LOCAL, -0.2F, -0.2F, -0.2F, 0.4F, 0.4F, 0.4F, ST.u0(PART_MOTE, i, 0F), ST.v0(PART_MOTE, i, 0F), 1F, 1F, SkinMesh.WHITE);
			}
		}
	}

	/** Puts {@link #LOCAL} on the cage's frame at the core, turned {@code extra} radians about its axis plus the slow spin. */
	private static void basis(float extra) {
		LOCAL.set(CU.x, CU.y, CU.z, 0F, AXIS.x, AXIS.y, AXIS.z, 0F, CV.x, CV.y, CV.z, 0F, CORE_AT.x, CORE_AT.y, CORE_AT.z, 1F);
		LOCAL.rotateY(spin * 1.7F + extra);
	}

	// ------------------------------------------------------------------ the far levels of detail

	/** LOD 2: a thin stalk and the lure as one small box. */
	private static void lod2(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer == ArmLayer.BLACK) {
			Loft.tube(m, STALK, 0F, reach, 4, 3, STALK_R, ArmLayer.BLACK, layer, ST, PART_STALK, 1F, 0F, arc, UP_Y, 1F);
		}
		float size = CORE * 1.3F * coreK;
		if (size > 0.05F && (d.glow() ? layer == ArmLayer.GLOW : layer == ArmLayer.RED)) {
			LOCAL.identity().translate(CORE_AT);
			m.box(LOCAL, -size / 2F, -size / 2F, -size / 2F, size, size, size, ST.u0(PART_BOX, 0, 0F), ST.v0(PART_BOX, 0, 0F), 1F, 1F, SkinMesh.WHITE);
		}
	}

	/** LOD 3: the stalk as one tapering box and the lure as one box. */
	private static void lod3(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer == ArmLayer.BLACK) {
			V1.set(P0);
			V2.set(P2).sub(V1);
			float len = V2.length();
			if (len > 1e-4F) {
				TURN.rotationTo(0F, 0F, 1F, V2.x / len, V2.y / len, V2.z / len);
				LOCAL.identity().translate(V1).rotate(TURN);
				m.frustum(LOCAL, len * reach, 1F, 1F, 0.6F, 0.6F, ST.u0(PART_BOX, 1, 0F), ST.v0(PART_BOX, 1, 0F), 1F, 1F, 1F, SkinMesh.WHITE);
			}
		}
		float size = CORE * 1.3F * coreK;
		if (size > 0.05F && (d.glow() ? layer == ArmLayer.GLOW : layer == ArmLayer.RED)) {
			LOCAL.identity().translate(CORE_AT);
			m.box(LOCAL, -size / 2F, -size / 2F, -size / 2F, size, size, size, ST.u0(PART_BOX, 0, 0F), ST.v0(PART_BOX, 0, 0F), 1F, 1F, SkinMesh.WHITE);
		}
	}

	// ------------------------------------------------------------------ helpers

	/** The brightness {@code d}'s holder chose for their lantern, 1 to 5 (3 when the server didn't say). */
	static int brightness(ArmDraw d) {
		ArmState st = Arms.stateByEntity(d.seed());
		int b = st == null ? 0 : st.lanternDrawBright(); // as it was, while it draws back after going out
		return b <= 0 ? 3 : Math.min(5, b);
	}

	/** The lantern's tier (1..3) for this draw's player, from the arm state the server sent; 2 when it isn't known (a preview). */
	static int lanternLevel(ArmDraw d) {
		ArmState st = Arms.stateByEntity(d.seed());
		int level = st == null ? 0 : st.lanternDrawLevel();
		return level <= 0 ? 2 : Math.min(3, level);
	}

	/** The rope's nodes as this frame's draw left them (3 × xyz): for the tests. */
	static float[] lastRope() {
		return HP.clone();
	}
}
