package dev.morphreborn.client.arm.lantern;

import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehaviorManager;
import dev.lambdaurora.lambdynlights.api.behavior.LineLightBehavior;
import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.arm.ArmClientModule;
import dev.morphreborn.client.arm.ArmLights;
import dev.morphreborn.client.arm.ArmState;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.geom.LureGeometry;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/**
 * The client side of the lantern that is not the lure's drawing (LureGeometry) or the flare (FlareFx): the light and the
 * motes of every lit lantern, and the little bits of the camera and the other hand that the first-person lure wants.
 * Everything comes from state every modded client has (who is lit, from S_ARM; where they stand and look, from the
 * entity), never from local-only input, so you see everyone's lantern as they see yours. The real light for players who
 * don't draw arms is the server's (fake light blocks); this adds to it.
 * <ul>
 *   <li><b>Motes:</b> one warm red mote every 4 ticks per lit lantern, in the part of its beam within 8 blocks of the camera
 *       (setting {@code lanternMotes}; {@link LanternMotes}).</li>
 *   <li><b>Dynamic light, with LambDynamicLights only:</b> per lit player a {@link SpotLightBehavior} at the beam's
 *       smoothed landing point and a line of light, luminance 7, from the lure to it. They are added when the lantern is
 *       lit and removed when it goes out, the player leaves, the world changes or the setting
 *       ({@code lanternDynamicLight}) is turned off. LambDynamicLights is optional and compile-only: the manager arrives in
 *       {@link ArmLights#manager} (set by {@code ArmLdl}, which that mod itself calls) as a plain Object, and the
 *       code that touches its classes is in {@link Lights}, which nothing loads unless the manager is there. Without the
 *       mod the manager stays null and none of this runs.</li>
 *   <li><b>The first-person lure's inputs:</b> how far the camera turned each frame (the rope swings against it) and how
 *       busy the other hand is (the stalk arches in nearer the middle), handed to {@link LureGeometry}.</li>
 * </ul>
 * The beam is worked out as the server does (a ray from the eyes along the look, 16, 24 or 32 blocks by level, stopped by
 * solid blocks), from the loaded world, once a tick; the landing point is smoothed so the pool of light glides.
 */
public final class ArmLantern implements ArmClientModule {
	/** The line of light from the lure to where the beam lands: its luminance. */
	static final int LINE_LUMINANCE = 7;
	/** The landing point's smoothing per tick, and the jump (blocks) past which it snaps instead (a new target, a teleport). */
	private static final double SMOOTH = 0.5, SNAP = 6.0;
	/** The light sits this far in front of what the beam hits, so it is in the air and not inside the wall. */
	private static final double STAND_OFF = 0.5;
	/** How quickly the first-person lure's room for the other hand eases in and out, per tick. */
	private static final float OFFHAND_EASE = 0.25F;

	/** One lit player's beam as last worked out, and the light made for it. */
	static final class Track {
		/** The arm tick this was last seen lit. */
		long seen;
		boolean has, lineSet;
		/** The pool of light's smoothed centre. */
		double hx, hy, hz;
		/** The lure's place, where the beam lands, and the line of light last given to the light's owner. */
		final double[] lure = new double[3], hit = new double[3], lineStart = new double[3], lineEnd = new double[3];
		@Nullable Object handle;
	}

	/**
	 * Whatever turns a beam into dynamic light: LambDynamicLights' behaviours (the {@link Lights} class) in the game, a
	 * recorder in the tests. Handles are the sink's own.
	 */
	interface Sink {
		/** Adds the pool of light at ({@code x, y, z}) and the line from {@code lure} to {@code hit}; the handle that removes them. */
		Object create(double x, double y, double z, double[] lure, double[] hit);

		/** Aims the pool of light (the sink decides whether that is far enough to matter). */
		void spot(Object handle, double x, double y, double z);

		/** Moves the line of light. */
		void line(Object handle, double[] lure, double[] hit);

		/** Takes both lights away. */
		void remove(Object handle);
	}

	private static final Map<UUID, Track> TRACKS = new HashMap<>();
	private static float offhandBlend;
	private static boolean cameraValid;
	private static float lastYaw, lastPitch;
	private static boolean lightsBroken;
	/** The light sink for the manager that is there now, made once (LambDynamicLights' classes are first touched here). */
	private static @Nullable Sink sink;
	private static @Nullable Object sinkFor;

	public ArmLantern() {}

	@Override
	public void init() {
		// once a frame, before the hands are drawn: how far the camera turned since the last one
		LevelRenderEvents.START_MAIN.register(ctx -> cameraFrame());
		// a new world's players are other people: no beam carries over
		ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((mc, level) -> dropAll());
	}

	@Override
	public void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		LocalPlayer self = mc.player;
		if (level == null || self == null) {
			dropAll();
			return;
		}
		long now = Arms.TICK;
		offhandBlend += ((self.getOffhandItem().isEmpty() ? 0F : 1F) - offhandBlend) * OFFHAND_EASE;
		LureGeometry.setOffhand(offhandBlend);
		if ((now & 63L) == 0L) LureGeometry.prune(now);

		boolean wantMotes = ClientConfig.INSTANCE.lanternMotes;
		Entity cameraEntity = mc.getCameraEntity();
		Vec3 camera = (cameraEntity != null ? cameraEntity : self).getEyePosition(1F);
		for (ArmState st : Arms.states()) {
			if (!st.lanternLit) continue;
			// the server's entity id can lag a respawn by a moment: the UUID names the player
			Player p = level.getPlayerByUUID(st.subject);
			if (p == null || p.isRemoved() || p.isSpectator()) continue;
			Track t = TRACKS.computeIfAbsent(st.subject, k -> new Track());
			t.seen = now;
			work(level, p, st, t, wantMotes, now, camera);
		}
		// the lights follow the lit lanterns: added when one is lit, aimed while it is, removed when it goes out
		boolean wantLights = ClientConfig.INSTANCE.lanternDynamicLight && ArmLights.available() && !lightsBroken;
		reconcile(TRACKS, now, currentSink(), wantLights);
	}

	@Override
	public void reset() {
		dropAll();
		cameraValid = false;
		offhandBlend = 0F;
	}

	// ------------------------------------------------------------------ one lit player

	/** Works out {@code p}'s light into {@code t}: where the lure is (the pool of light, smoothed), and sheds this tick's mote. */
	private static void work(ClientLevel level, Player p, ArmState st, Track t, boolean wantMotes, long now, Vec3 camera) {
		// The light is round the holder now (2026-11-15; the server's halo), not a beam where they look: the
		// pool of light sits at the lure, over the hand that holds the form, and the line runs from it to the eyes.
		Vec3 eye = p.getEyePosition(1F), look = p.getViewVector(1F);
		// Beam.lurePoint puts the lure on the side opposite the arm it is told is the main one: tell it the other arm
		Beam.lurePoint(eye.x, eye.y, eye.z, look.x, look.y, look.z, p.getMainArm() != HumanoidArm.RIGHT, t.lure);
		double tx = t.lure[0], ty = t.lure[1], tz = t.lure[2];
		if (!t.has || Math.abs(tx - t.hx) + Math.abs(ty - t.hy) + Math.abs(tz - t.hz) > SNAP) {
			t.hx = tx;
			t.hy = ty;
			t.hz = tz;
			t.has = true;
		} else {
			t.hx = Beam.smooth(t.hx, tx, SMOOTH);
			t.hy = Beam.smooth(t.hy, ty, SMOOTH);
			t.hz = Beam.smooth(t.hz, tz, SMOOTH);
		}
		t.hit[0] = eye.x;
		t.hit[1] = eye.y;
		t.hit[2] = eye.z;

		if (wantMotes && LanternMotes.due(now, st.entityId)) {
			// drifting round the lure: a short run out of it in a direction that changes every mote
			double a = (now * 2.39996 + st.entityId) % (Math.PI * 2);
			double dx = Math.cos(a) * 0.6, dy = -0.8, dz = Math.sin(a) * 0.6;
			double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
			LanternMotes.spawn(level, t.lure[0], t.lure[1], t.lure[2], dx / len, dy / len, dz / len, 1.6, camera.x, camera.y, camera.z);
		}
	}

	// ------------------------------------------------------------------ the lights

	/**
	 * Brings the lights in line with who is lit at tick {@code now} ({@link Track#seen}): a lit player gets their lights
	 * (made the first time, aimed after), anyone not seen this tick loses theirs and is forgotten, and with
	 * {@code wantLights} off (the setting, or no LambDynamicLights) every light goes. The {@code sink} is what they are
	 * taken out of, so it is given even then; null only without the mod. Package-private so the tests drive it with a
	 * recording sink.
	 */
	static void reconcile(Map<UUID, Track> tracks, long now, @Nullable Sink sink, boolean wantLights) {
		for (Iterator<Track> it = tracks.values().iterator(); it.hasNext(); ) {
			Track t = it.next();
			boolean lit = t.seen == now;
			try {
				if (lit && wantLights && sink != null) update(t, sink);
				else drop(t, sink);
			} catch (LinkageError | RuntimeException e) {
				lightsFailed(e, tracks);
			}
			if (!lit) it.remove();
		}
	}

	private static void update(Track t, Sink sink) {
		if (t.handle == null) {
			t.handle = sink.create(t.hx, t.hy, t.hz, t.lure, t.hit);
			System.arraycopy(t.lure, 0, t.lineStart, 0, 3);
			System.arraycopy(t.hit, 0, t.lineEnd, 0, 3);
			t.lineSet = true;
			return;
		}
		sink.spot(t.handle, t.hx, t.hy, t.hz);
		// the line only moves when it moved enough: 0.35 blocks at either end, or 2 degrees of aim
		if (!t.lineSet || Beam.lineChanged(t.lineStart, t.lineEnd, t.lure, t.hit)) {
			sink.line(t.handle, t.lure, t.hit);
			System.arraycopy(t.lure, 0, t.lineStart, 0, 3);
			System.arraycopy(t.hit, 0, t.lineEnd, 0, 3);
			t.lineSet = true;
		}
	}

	/** Takes {@code t}'s lights away, if it has any and there is still something to take them from. */
	private static void drop(Track t, @Nullable Sink sink) {
		Object handle = t.handle;
		t.handle = null;
		t.lineSet = false;
		if (handle != null && sink != null) sink.remove(handle);
	}

	/** The sink for the manager that is there now; null without LambDynamicLights. */
	private static @Nullable Sink currentSink() {
		Object manager = ArmLights.manager;
		if (manager == null) return null;
		if (sink == null || sinkFor != manager) {
			try {
				sink = new Lights(manager);
				sinkFor = manager;
			} catch (LinkageError | RuntimeException e) {
				lightsFailed(e, TRACKS);
				return null;
			}
		}
		return sink;
	}

	/** Everything the lantern keeps: the lights out of the mod, the beams forgotten, the lure's ropes and bursts dropped. */
	private static void dropAll() {
		Sink s = ArmLights.manager != null ? sink : null;
		try {
			for (Track t : TRACKS.values()) drop(t, s);
		} catch (LinkageError | RuntimeException e) {
			lightsFailed(e, TRACKS);
		}
		TRACKS.clear();
		LureGeometry.clear();
	}

	/**
	 * LambDynamicLights is there but its API isn't what this was built against (an update moved something): the lantern
	 * carries on without its dynamic light, and says so once.
	 */
	private static void lightsFailed(Throwable e, Map<UUID, Track> tracks) {
		if (!lightsBroken) {
			lightsBroken = true;
			LoggerFactory.getLogger("RE:Morph").error("LambDynamicLights' behaviour API failed; the lantern goes on without its dynamic light", e);
		}
		ArmLights.manager = null;
		sink = null;
		sinkFor = null;
		for (Track t : tracks.values()) {
			t.handle = null;
			t.lineSet = false;
		}
	}

	/** Forgets that LambDynamicLights failed, so a test can start clean. */
	static void resetFailure() {
		lightsBroken = false;
	}

	// ------------------------------------------------------------------ the camera

	/** Once a frame: how far the camera turned since the last one, for the first-person rope (only while your lantern is lit). */
	private static void cameraFrame() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer self = mc.player;
		Entity camera = mc.getCameraEntity();
		ArmState st = self == null ? null : Arms.state(self.getUUID());
		if (camera == null || st == null || !st.lanternLit) {
			cameraValid = false;
			return;
		}
		float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		float yaw = camera.getViewYRot(pt), pitch = camera.getViewXRot(pt);
		if (cameraValid) LureGeometry.cameraStep(Mth.wrapDegrees(yaw - lastYaw), pitch - lastPitch);
		lastYaw = yaw;
		lastPitch = pitch;
		cameraValid = true;
	}

	// ------------------------------------------------------------------ pure parts

	/**
	 * The pool of light's maths (what {@link SpotLightBehavior} answers LambDynamicLights with): a sphere of light at a
	 * centre that follows its target in jumps of at least 0.35 blocks, so a hand's tremor never makes the mod redo its
	 * light every tick. No Minecraft or LambDynamicLights classes, so it lives here and not in the behaviour (which
	 * implements one of the mod's interfaces and so can't be loaded, or compiled against, without it).
	 */
	public static final class Spot {
		/** The light's reach in blocks, its strength at the centre, and the falloff's exponent. */
		public static final double RADIUS = 8.0, MAX_LUMINANCE = 15.0, EXPONENT = 0.7;
		/** The centre follows its target only past this many blocks. */
		public static final double MOVE_EPSILON = 0.35;

		private record Centre(double x, double y, double z) {}

		private volatile Centre target, reported;

		public Spot(double x, double y, double z) {
			target = new Centre(x, y, z);
		}

		/** The game thread: where the light should be. */
		public void moveTo(double x, double y, double z) {
			target = new Centre(x, y, z);
		}

		/**
		 * The mod asks once a tick whether the light changed: yes the first time, and again when the target is past
		 * {@link #MOVE_EPSILON} from the centre last reported (which it then becomes).
		 */
		public boolean changed() {
			Centre t = target, r = reported;
			if (r == null || dist(t, r) >= MOVE_EPSILON) {
				reported = t;
				return true;
			}
			return false;
		}

		/** The centre the light is answering for: the last one reported, or the target before the first report. */
		private Centre centre() {
			Centre r = reported;
			return r != null ? r : target;
		}

		public double x() {
			return centre().x;
		}

		public double y() {
			return centre().y;
		}

		public double z() {
			return centre().z;
		}

		/** The light at the block ({@code bx, by, bz}): its middle's distance to the centre sets it; 0 from 8 blocks out. */
		public double lightAt(int bx, int by, int bz) {
			Centre c = centre();
			double dx = bx + 0.5 - c.x, dy = by + 0.5 - c.y, dz = bz + 0.5 - c.z;
			return luminance(Math.sqrt(dx * dx + dy * dy + dz * dz));
		}

		/** {@code 15·(1 − d/8)^0.7}, 0 from {@link #RADIUS} out. */
		public static double luminance(double distance) {
			if (!(distance < RADIUS)) return 0.0;
			return MAX_LUMINANCE * Math.pow(1.0 - Math.max(0.0, distance) / RADIUS, EXPONENT);
		}

		/** The blocks the light can reach, inclusive: {@code floor(centre ± 8)} as (x0, y0, z0, x1, y1, z1). */
		public int[] box() {
			Centre c = centre();
			return new int[] {floor(c.x - RADIUS), floor(c.y - RADIUS), floor(c.z - RADIUS), floor(c.x + RADIUS), floor(c.y + RADIUS), floor(c.z + RADIUS)};
		}

		private static int floor(double v) {
			int i = (int) v;
			return v < i ? i - 1 : i;
		}

		private static double dist(Centre a, Centre b) {
			double dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
	}

	/** The beam's maths: no Minecraft classes at all, so the tests run without a game. */
	public static final class Beam {
		private Beam() {}

		/** The beam's reach in blocks by lantern level: 16, 24, 32 (anything else is clamped into that). */
		public static double reach(int level) {
			return 16.0 + 8.0 * (Math.min(3, Math.max(1, level)) - 1);
		}

		/** {@code from} moved {@code k} of the way to {@code to}. */
		public static double smooth(double from, double to, double k) {
			return from + (to - from) * k;
		}

		/**
		 * The lure's place in the world, as the server's lure cell has it: {@code eye + up·0.4 + offSide·0.45}, where the
		 * off side is the side away from the main hand. {@code (lx, ly, lz)} is the look (unit length), {@code mainRight}
		 * whether the main arm is the right one. Written into {@code out}.
		 */
		public static void lurePoint(double ex, double ey, double ez, double lx, double ly, double lz, boolean mainRight, double[] out) {
			// right = (−lz, 0, lx) normalised: the horizontal turned a quarter; straight up or down it is the x axis
			double hl = Math.sqrt(lx * lx + lz * lz);
			double rx, rz;
			if (hl < 1e-6) {
				rx = -1.0;
				rz = 0.0;
			} else {
				rx = -lz / hl;
				rz = lx / hl;
			}
			// up = right × look
			double ux = 0.0 * lz - rz * ly, uy = rz * lx - rx * lz, uz = rx * ly - 0.0 * lx;
			double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
			if (ul < 1e-9) {
				ux = 0.0;
				uy = 1.0;
				uz = 0.0;
			} else {
				ux /= ul;
				uy /= ul;
				uz /= ul;
			}
			double side = mainRight ? -1.0 : 1.0;
			out[0] = ex + ux * 0.4 + rx * 0.45 * side;
			out[1] = ey + uy * 0.4;
			out[2] = ez + uz * 0.4 + rz * 0.45 * side;
		}

		/**
		 * Whether the line of light from {@code s1} to {@code e1} differs enough from the one last set ({@code s0} to
		 * {@code e0}) to be worth moving: either end 0.35 blocks away, or the aim 2 degrees off.
		 */
		public static boolean lineChanged(double[] s0, double[] e0, double[] s1, double[] e1) {
			if (dist(s0, s1) >= 0.35 || dist(e0, e1) >= 0.35) return true;
			double ax = e0[0] - s0[0], ay = e0[1] - s0[1], az = e0[2] - s0[2];
			double bx = e1[0] - s1[0], by = e1[1] - s1[1], bz = e1[2] - s1[2];
			double la = Math.sqrt(ax * ax + ay * ay + az * az), lb = Math.sqrt(bx * bx + by * by + bz * bz);
			if (la < 1e-6 || lb < 1e-6) return la != lb;
			double cos = (ax * bx + ay * by + az * bz) / (la * lb);
			return Math.toDegrees(Math.acos(Math.min(1.0, Math.max(-1.0, cos)))) >= 2.0;
		}

		private static double dist(double[] a, double[] b) {
			double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
	}

	// ------------------------------------------------------------------ the part that names LambDynamicLights

	/**
	 * Everything that touches a LambDynamicLights class: the sink that adds, aims and removes the lantern's behaviours in
	 * the mod's manager. Nothing above reaches this class except {@link #currentSink} (only with a manager), so without
	 * the mod it is never loaded and the JVM never looks for the API.
	 */
	static final class Lights implements Sink {
		private record Handle(SpotLightBehavior spot, LineLightBehavior line) {}

		private final DynamicLightBehaviorManager manager;

		Lights(Object manager) {
			this.manager = (DynamicLightBehaviorManager) manager;
		}

		@Override
		public Object create(double x, double y, double z, double[] lure, double[] hit) {
			SpotLightBehavior spot = new SpotLightBehavior(x, y, z);
			LineLightBehavior line = new LineLightBehavior(new Vector3d(lure[0], lure[1], lure[2]), new Vector3d(hit[0], hit[1], hit[2]), LINE_LUMINANCE);
			manager.add(spot);
			manager.add(line);
			return new Handle(spot, line);
		}

		@Override
		public void spot(Object handle, double x, double y, double z) {
			((Handle) handle).spot.moveTo(x, y, z);
		}

		@Override
		public void line(Object handle, double[] lure, double[] hit) {
			// whole new points: the mod reads them from its own threads
			LineLightBehavior line = ((Handle) handle).line;
			line.setStartPoint(new Vector3d(lure[0], lure[1], lure[2]));
			line.setEndPoint(new Vector3d(hit[0], hit[1], hit[2]));
		}

		@Override
		public void remove(Object handle) {
			Handle h = (Handle) handle;
			h.spot.remove();
			manager.remove(h.spot);
			manager.remove(h.line);
		}
	}
}
