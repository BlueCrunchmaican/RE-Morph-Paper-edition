package dev.morphreborn.client.arm.geom;

import dev.morphreborn.client.arm.render.ArmMath;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The spyglass eye's timelines and the little per-player memory its look needs, kept apart from the geometry so the numbers can be
 * tested without drawing anything (2026-11-16: a tendril eye stalk that turns to look where its owner looks and extends
 * while they zoom).
 * <ul>
 *   <li>{@link #built}: how much of the eye exists, from the accessory's draw age (the lure's way: ticks since it came on, negative
 *       while it draws back, so the growth plays backwards).</li>
 *   <li>{@link #extend}: how far out the stalk is, 0 resting over the shoulder to 1 reaching out while zooming. The server only says
 *       zooming or not (the accessory's arg), so each viewer eases it: out in 6 ticks, back in 9. Your own eye does not wait for the
 *       server's answer ({@link Source#ownZoom}).</li>
 *   <li>{@link #aim}: where the eye looks, as a smoothed world yaw and pitch of the owner's view, plus an idle wander that fades out as
 *       the stalk extends (a zooming eye is dead on). {@link #toArm} turns it into the arm's own space.</li>
 *   <li>{@link #cover} and {@link #blink}: how far the lids are closed: heavy-lidded at rest, wide open while zooming, and a blink
 *       now and then.</li>
 * </ul>
 * Everything is keyed by the player's entity id (a draw's seed). Render thread only. Nothing here allocates per frame: the tables
 * are fixed arrays, found by the id's hash with a short probe (a crowd of {@link #SLOTS} players is held without a scan of them all);
 * an entry nobody has drawn for {@link #EXPIRE_TICKS} is free to take, and only when the probe finds none is the oldest reused, so zoom
 * state does not snap for a crowd (2026-11-16: 32 slots evicted each other past 32 spyglass users).
 */
public final class SpyglassMotion {
	/** Ticks the eye takes to grow out when switched on, and to draw back in when switched off (ArmState.ACCESSORY_OUT_TICKS). */
	public static final float BUILD_TICKS = 14F, OUT_TICKS = 20F;
	/** Ticks the stalk takes to reach out, and to come back: the zoom's own ease is about the same, so the two read as one move. */
	public static final float EXTEND_TICKS = 6F, RETRACT_TICKS = 9F;
	/** How fast the eye follows the owner's view (the time constant, ticks): lazily at rest, near at once while zooming. */
	public static final float AIM_TAU_REST = 2.6F, AIM_TAU_ZOOM = 0.7F;
	/** How far the idle eye wanders from where the owner looks (degrees), and how long it stays on one spot (ticks, a range). */
	public static final float WANDER_YAW = 24F, WANDER_PITCH = 11F, WANDER_MIN = 34F, WANDER_SPAN = 38F;
	/** A blink: the mean wait (ticks), how much it varies, and how long the lids take shut and open again. */
	public static final float BLINK_EVERY = 96F, BLINK_VARY = 70F, BLINK_TICKS = 4.5F;
	/** How far the lids are closed at rest, 0..1 (1: shut). */
	public static final float REST_COVER = 0.5F;
	/** Table size (a power of two): far more spyglass eyes than are ever drawn in one frame. */
	static final int SLOTS = 512;
	/** How many slots from an id's hash are looked at before one is reused. */
	private static final int PROBES = 16;
	/** An entry not drawn for this many ticks (a minute) is free to take. */
	static final float EXPIRE_TICKS = 1200F;

	/**
	 * What the client tells the renderer that the geometry cannot know without the game: whether this player's own zoom is on (so
	 * the eye need not wait for the server's word), where a player looks, and whether the pose being drawn is the world pass's (the
	 * paper doll and the inventory preview turn the figure, so the world's look direction means nothing there). Set once by the
	 * client's spyglass module; null in a test, where the eye simply looks ahead.
	 */
	public interface Source {
		/** Whether {@code entityId} is this client's own player and is holding the zoom key now. */
		boolean ownZoom(int entityId);

		/** Where {@code entityId} looks at partial tick {@code pt}: world yaw and pitch in degrees (Minecraft's) into {@code out}; false when unknown. */
		boolean look(int entityId, float pt, float[] out);

		/** Whether the draw being made is in the world pass (its pose is the world's, camera-relative). */
		boolean worldPass();
	}

	private static volatile @Nullable Source source;

	private SpyglassMotion() {}

	public static void source(@Nullable Source s) {
		source = s;
	}

	static @Nullable Source source() {
		return source;
	}

	// ------------------------------------------------------------------ built

	/**
	 * How much of the eye is out, 0..1, at draw age {@code age}: growing over {@link #BUILD_TICKS} from switch-on, and while it draws
	 * back ({@code age} below 0: {@code -1 - ticks since it went out}) the same growth played backwards over {@link #OUT_TICKS}.
	 */
	public static float built(float age) {
		if (age >= 0F) return ArmMath.sm(age / BUILD_TICKS);
		float out = ArmMath.clamp01((-age - 1F) / OUT_TICKS);
		return 1F - ArmMath.sm(out);
	}

	// ------------------------------------------------------------------ extend

	private static final boolean[] EUSED = new boolean[SLOTS];
	private static final int[] EID = new int[SLOTS];
	private static final float[] EFROM = new float[SLOTS], ETO = new float[SLOTS], ESINCE = new float[SLOTS], ELAST = new float[SLOTS];

	private static int extendSlot(int id, float time) {
		int i = slot(EUSED, EID, ELAST, id, time);
		// the clock wraps hourly: a time far behind the last one is a new hour, not the past
		if (EUSED[i] && EID[i] == id && time < ELAST[i] - 2000F) ESINCE[i] = time;
		return i;
	}

	/**
	 * The slot holding {@code id} in a table, or the one to start it in: probes {@link #PROBES} slots from the id's hash, taking a match, else
	 * the first free or expired one, else the one drawn longest ago of those probed. A match is told by {@code used[i] && ids[i] == id}.
	 */
	private static int slot(boolean[] used, int[] ids, float[] last, int id, float time) {
		int h = (int) (hash(id, 41) * SLOTS) & (SLOTS - 1);
		int free = -1, oldest = h;
		float oldestAt = Float.MAX_VALUE;
		for (int k = 0; k < PROBES; k++) {
			int i = (h + k) & (SLOTS - 1);
			if (!used[i]) {
				if (free < 0) free = i;
				continue;
			}
			if (ids[i] == id) return i;
			// drawn long ago (or the clock wrapped since): free to take
			if (free < 0 && (time - last[i] > EXPIRE_TICKS || time < last[i] - 2000F)) free = i;
			if (last[i] < oldestAt) {
				oldestAt = last[i];
				oldest = i;
			}
		}
		return free >= 0 ? free : oldest;
	}

	/**
	 * How far out {@code id}'s stalk is, 0..1, at {@code time} (the draw's clock), given whether they are zooming ({@code zooming}: the
	 * accessory's arg, or the local key). A first sighting is simply at rest in whatever that says. Asking again for the same time
	 * gives the same answer, so the layers of one draw (black, red, glow) agree.
	 */
	public static float extend(int id, float time, boolean zooming) {
		int i = extendSlot(id, time);
		float to = zooming ? 1F : 0F;
		if (!EUSED[i] || EID[i] != id) {
			EUSED[i] = true;
			EID[i] = id;
			EFROM[i] = to;
			ETO[i] = to;
			ESINCE[i] = time - 1000F;
		}
		ELAST[i] = time;
		if (ETO[i] != to) {
			EFROM[i] = extendValue(i, time);
			ETO[i] = to;
			ESINCE[i] = time;
		}
		return extendValue(i, time);
	}

	private static float extendValue(int i, float time) {
		float len = ETO[i] > EFROM[i] ? EXTEND_TICKS : RETRACT_TICKS;
		float u = ArmMath.clamp01((time - ESINCE[i]) / len);
		return ArmMath.lerp(EFROM[i], ETO[i], ArmMath.sm(u));
	}

	// ------------------------------------------------------------------ aim

	private static final boolean[] AUSED = new boolean[SLOTS];
	private static final int[] AID = new int[SLOTS];
	private static final float[] AYAW = new float[SLOTS], APITCH = new float[SLOTS], ALAST = new float[SLOTS];
	private static final float[] LOOK = new float[2];

	/**
	 * Where {@code id}'s eye looks at {@code time}, into {@code out} as a world yaw and pitch in degrees: the owner's view, followed with
	 * a lag that closes as the stalk extends ({@code ext}), and an idle wander round it that fades out as it does. Returns false when
	 * the game cannot say where they look (no source, an entity not in the world, a draw outside the world pass); the eye then looks
	 * ahead, wandering from there ({@link #wanderYaw}, {@link #wanderPitch} are in {@code out} either way, as offsets to add).
	 * <p>
	 * Stepped once per time: asking again for the same time gives the stored answer, so the three layers of a draw agree.
	 */
	public static boolean aim(int id, float time, float ext, float[] out) {
		Source s = source;
		boolean world = false;
		if (s != null && s.worldPass()) {
			// the partial tick is the clock's fraction: the arm clock is the tick count plus it
			float pt = time - (float) Math.floor(time);
			world = s.look(id, pt, LOOK);
		}
		float wy = wanderYaw(id, time) * (1F - ext), wp = wanderPitch(id, time) * (1F - ext);
		if (!world) {
			out[0] = wy;
			out[1] = wp;
			return false;
		}
		int i = slot(AUSED, AID, ALAST, id, time);
		if (!AUSED[i] || AID[i] != id) {
			AUSED[i] = true;
			AID[i] = id;
			AYAW[i] = LOOK[0];
			APITCH[i] = LOOK[1];
			ALAST[i] = time;
		} else if (time != ALAST[i]) {
			float dt = time - ALAST[i];
			if (dt < 0F || dt > 40F) {
				// the clock wrapped, or it has been a while: start from where they look now
				AYAW[i] = LOOK[0];
				APITCH[i] = LOOK[1];
			} else {
				float tau = ArmMath.lerp(AIM_TAU_REST, AIM_TAU_ZOOM, ext);
				float k = 1F - (float) Math.exp(-dt / tau);
				AYAW[i] += wrapDegrees(LOOK[0] - AYAW[i]) * k;
				APITCH[i] += (LOOK[1] - APITCH[i]) * k;
			}
			ALAST[i] = time;
		}
		out[0] = AYAW[i] + wy;
		out[1] = ArmMath.clamp(APITCH[i] + wp, -89F, 89F);
		return true;
	}

	static float wrapDegrees(float deg) {
		float d = deg % 360F;
		if (d >= 180F) d -= 360F;
		if (d < -180F) d += 360F;
		return d;
	}

	/** A stable pseudo-random number in 0..1 from two ints. */
	static float hash(int a, int b) {
		int h = a * 0x27D4EB2D + b * 0x165667B1;
		h ^= h >>> 15;
		h *= 0x85EBCA77;
		h ^= h >>> 13;
		return (h & 0xFFFFFF) / (float) 0x1000000;
	}

	/**
	 * The idle wander, yaw (degrees): the eye rests on one spot for a stretch, then slides to the next, each a pick from the player's
	 * own sequence. Smooth in time (eased between picks), and the same for every caller at the same time.
	 */
	public static float wanderYaw(int id, float time) {
		return wander(id, time, 1) * WANDER_YAW;
	}

	/** The idle wander's pitch (degrees); see {@link #wanderYaw}. */
	public static float wanderPitch(int id, float time) {
		return wander(id, time, 2) * WANDER_PITCH;
	}

	private static float wander(int id, float time, int axis) {
		float len = WANDER_MIN + WANDER_SPAN * hash(id, 7);
		// the clock wraps hourly: the wander is stationary, so a jump at the wrap is one slide, not a glitch
		float t = time / len + hash(id, 11);
		int n = (int) Math.floor(t);
		float u = ArmMath.sm((t - n) * 2.4F - 1.2F + 0.5F); // rest for the first part, slide for the rest
		float a = hash(id * 31 + n, axis) * 2F - 1F, b = hash(id * 31 + n + 1, axis) * 2F - 1F;
		return ArmMath.lerp(a, b, u);
	}

	// ------------------------------------------------------------------ lids

	/** How far the lids are closed (0 open .. 1 shut) from a blink and the extension: heavy-lidded at rest, wide open zooming. */
	public static float cover(int id, float time, float ext) {
		float rest = REST_COVER + 0.05F * (float) Math.sin(0.09F * time + ArmMath.phase(id));
		float base = ArmMath.lerp(rest, 0F, ArmMath.sm(ext * 1.4F));
		// a zooming eye stays wide open: the blink fades out as the stalk reaches
		return Math.max(base, blink(id, time) * (1F - ext));
	}

	/** A blink, 0..1: shut at the middle of a short window now and then (each player's own beat). */
	public static float blink(int id, float time) {
		float every = BLINK_EVERY + BLINK_VARY * (hash(id, 3) - 0.5F);
		float t = time + 40F * hash(id, 5);
		int n = (int) Math.floor(t / every);
		float into = t - n * every;
		// each beat's own offset in its stretch, so they are not evenly spaced
		float at = every * (0.3F + 0.5F * hash(id + n * 13, 9));
		float u = (into - at) / BLINK_TICKS;
		if (u < 0F || u > 1F) return 0F;
		return 1F - Math.abs(2F * u - 1F);
	}

	// ------------------------------------------------------------------ the world's direction in the arm's space

	private static final Matrix4f INV = new Matrix4f();

	/**
	 * The world direction of a view with yaw {@code yawDeg} and pitch {@code pitchDeg} (Minecraft's: yaw 0 looks south, +Z, and a
	 * positive pitch looks down) in the space {@code pose} takes to the world's axes (the arm space A through the world pass's pose,
	 * which is the arm's px to blocks from the camera: world axes, so only its rotation matters), into {@code out}, unit length.
	 * An unusable pose leaves the arm's own forward, -Z.
	 */
	public static Vector3f toArm(@Nullable Matrix4fc pose, float yawDeg, float pitchDeg, Vector3f out) {
		float yaw = (float) Math.toRadians(yawDeg), pitch = (float) Math.toRadians(pitchDeg);
		float cp = (float) Math.cos(pitch);
		float wx = -(float) Math.sin(yaw) * cp, wy = -(float) Math.sin(pitch), wz = (float) Math.cos(yaw) * cp;
		if (pose == null) return out.set(wx, wy, wz);
		INV.set(pose).invert();
		INV.transformDirection(wx, wy, wz, out);
		float l = out.length();
		if (!(l > 1e-6F) || !Float.isFinite(l)) return out.set(0F, 0F, -1F);
		return out.div(l);
	}

	/** The arm's own forward turned {@code yawDeg} to the right and {@code pitchDeg} down, into {@code out}: the eye's look with no world to read. */
	public static Vector3f local(float yawDeg, float pitchDeg, Vector3f out) {
		float yaw = (float) Math.toRadians(yawDeg), pitch = (float) Math.toRadians(pitchDeg);
		float cp = (float) Math.cos(pitch);
		return out.set((float) Math.sin(yaw) * cp, (float) Math.sin(pitch), -(float) Math.cos(yaw) * cp);
	}

	// ------------------------------------------------------------------ housekeeping

	/** Forgets everything (a test; leaving a world). */
	public static void clear() {
		for (int i = 0; i < SLOTS; i++) {
			EUSED[i] = false;
			AUSED[i] = false;
		}
	}
}
