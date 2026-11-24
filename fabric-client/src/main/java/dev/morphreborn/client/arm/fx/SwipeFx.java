package dev.morphreborn.client.arm.fx;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.ArmTint;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import dev.morphreborn.client.arm.render.Stations;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Draining Cut's swing (S_ARM_FX SWIPE): a crescent of black tendril sweeping round the striker through the special's
 * arc, the way a sword's sweep particle does but alive. It runs from the right to the left (the other way for a
 * left-handed player) over ticks 2 to 6 of the special, as the vanilla swing does, 1.8 blocks out from just below the
 * eyes (1.3 for your own in first person, so it crosses the view rather than leaving it):
 * <ul>
 *   <li>the lead crescent: a tendril along the last 70 degrees of the swing, 2.4 px thick at its leading end and 0.6 px
 *       at its tail, which keeps up 70 degrees behind and catches up once the swing is done;</li>
 *   <li>a thin red ribbon (0.8 px) just outside it, the cut's edge;</li>
 *   <li>8 hairs shed outward as the lead passes, drifting 0.3 blocks out and shrinking away over 5 ticks;</li>
 *   <li>3 red beads bursting from each creature cut, as the lead reaches it.</li>
 * </ul>
 * The swing's yaw is taken as the effect arrives, so the arc stays where the cut was aimed while the striker turns; its
 * centre follows them. The targets come in the order they are hit; where each sits on the arc is worked out here.
 */
public final class SwipeFx implements FxHandler {
	private static final float RADIUS = 1.8F, OWN_RADIUS = 1.3F, DROP = 0.35F;
	/** The swing: from tick 2 for 4 ticks; the crescent's length in degrees; ticks its tail takes to catch up after. */
	private static final float SWEEP_FROM = 2F, SWEEP_TICKS = 4F, TRAIL = 70F, CATCH_UP = 2F;
	private static final float LEAD_PX = 2.4F, TAIL_PX = 0.6F, WRITHE_PX = 0.7F;
	private static final float RIBBON_PX = 0.8F, RIBBON_OUT = 0.05F;
	private static final int HAIRS = 8;
	private static final float HAIR_TICKS = 5F, HAIR_OUT = 0.3F, HAIR_PX = 3F, HAIR_WIDTH = 0.4F;
	private static final int BEADS = 3;
	private static final float BEAD_TICKS = 6F, BEAD_PX = 0.7F, BEAD_GRAVITY = 0.04F;
	/** Ticks a swipe lasts (the last hair and bead are gone by then); how many show at once; targets per swipe. */
	private static final int LIFE = 12, MAX = 8, MAX_TARGETS = 8;
	/** The arc when the server didn't say (Draining Cut's level 2). */
	private static final float DEFAULT_ARC = 135F;
	/** The crescent's stations: one every 5 degrees, at most this many. */
	private static final float STEP_DEG = 5F;
	private static final int MAX_STATIONS = 16;

	private static final class Swipe {
		final int origin;
		final long start;
		final float arc;
		final int[] targets;
		final int seed;
		/** The aim (degrees) and the swing's direction (+1 right to left), set once the striker is known. */
		float yaw = Float.NaN;
		float side = 1F;
		/** Per target: when the lead reaches it (ticks into the swipe), and where it was then (x, y, z). */
		final float[] burstAge;
		final double[] burstAt;

		Swipe(int origin, long start, float arc, int[] targets, int seed) {
			this.origin = origin;
			this.start = start;
			this.arc = arc;
			this.targets = targets;
			this.seed = seed;
			burstAge = new float[targets.length];
			Arrays.fill(burstAge, Float.NaN);
			burstAt = new double[3 * targets.length];
			Arrays.fill(burstAt, Double.NaN);
		}
	}

	private final List<Swipe> swipes = new ArrayList<>();

	// render-thread scratch, used inside one geometry lambda at a time
	private static final Stations S = new Stations();
	private static final Static ST = new Static();
	private static final float[] POLY = new float[8];
	private static final float[] RIBBON = {-RIBBON_PX / 2F, 0F, RIBBON_PX / 2F, 0F};
	private static final float[] PTS = new float[3 * MAX_STATIONS];
	private static final Loft.FaceLayer ALL_RED = (segment, face) -> ArmLayer.RED;

	public SwipeFx() {}

	@Override
	public int[] kinds() {
		return new int[] {ArmProtocol.FX_SWIPE};
	}

	@Override
	public void start(ArmFxEvent e) {
		float arc = e.arg() > 0 ? Math.clamp(e.arg(), 10, 360) : DEFAULT_ARC;
		int[] targets = Arrays.copyOf(e.ids(), Math.min(e.ids().length, MAX_TARGETS));
		Swipe s = new Swipe(e.origin(), e.receivedTick(), arc, targets, (e.seed() ^ e.origin() * 31) & 0x7FFF);
		ClientLevel level = Minecraft.getInstance().level;
		Entity origin = level == null ? null : level.getEntity(e.origin());
		if (origin != null) aim(s, origin);
		if (swipes.size() >= MAX) swipes.remove(0);
		swipes.add(s);
	}

	@Override
	public void tick() {
		long now = Arms.TICK;
		swipes.removeIf(s -> now - s.start > LIFE);
	}

	@Override
	public void clear() {
		swipes.clear();
	}

	@Override
	public void collect(ArmFxFrame f) {
		if (swipes.isEmpty()) return;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		float pt = f.partialTick();
		for (Swipe s : swipes) {
			ArmTint.owner(s.origin); // this effect is drawn in its owner's colour
			Entity origin = level.getEntity(s.origin);
			if (origin == null) continue;
			if (Float.isNaN(s.yaw)) aim(s, origin);
			float age = Arms.age(s.start, pt);
			boolean own = ArmFx.isOwnFirstPerson(s.origin, f.firstPersonCamera());
			float r = own ? OWN_RADIUS : RADIUS;
			Vec3 centre = origin.getEyePosition(pt).subtract(0, DROP, 0);
			if (!TendrilFx.finite(centre)) continue;
			int light = TendrilFx.light(level, centre, centre);
			float startBearing = s.yaw + s.side * s.arc / 2F;
			float lead = lead(age, s.arc), tail = tail(age, s.arc);
			if (lead - tail > 0.5F) {
				float rPx = r * 16F, side = s.side, time = f.time(), phase = s.origin * 0.37F;
				int seed = s.seed;
				TendrilFx.submit(f, centre, ArmLayer.BLACK, light, m -> crescent(m, rPx, startBearing, side, tail, lead, time, phase, seed));
				TendrilFx.submit(f, centre, ArmLayer.RED, light, m -> ribbon(m, rPx + RIBBON_OUT * 16F, startBearing, side, tail, lead, time, seed));
			}
			hairs(f, s, centre, r, startBearing, age, light);
			beads(f, level, s, centre, age, pt);
		}
	}

	/**
	 * Takes the striker's aim and main hand: the swing sweeps from the main-hand side. The aim is where the head looks
	 * (another player's body turns after it), as the server's is the eye's.
	 */
	private static void aim(Swipe s, Entity origin) {
		s.yaw = origin.getViewYRot(1F);
		s.side = origin instanceof Player p && p.getMainArm() == HumanoidArm.LEFT ? -1F : 1F;
	}

	// ------------------------------------------------------------------ the swing's timing (degrees along the arc from its start side)

	/** Where the crescent's leading end is {@code age} ticks into the swipe: eased through the arc over ticks 2..6. */
	static float lead(float age, float arc) {
		return arc * ArmMath.sm((age - SWEEP_FROM) / SWEEP_TICKS);
	}

	/** Where its tail is: 70 degrees behind the lead (never before the start), closing on it over 2 ticks once the swing ends. */
	static float tail(float age, float arc) {
		float end = SWEEP_FROM + SWEEP_TICKS;
		float gap = age <= end ? TRAIL : TRAIL * Math.max(0F, 1F - (age - end) / CATCH_UP);
		return ArmMath.clamp(lead(age, arc) - gap, 0F, arc);
	}

	/** When (ticks into the swipe) the lead reaches {@code along} degrees: smoothstep turned round. */
	static float reachesAt(float along, float arc) {
		float y = ArmMath.clamp01(along / Math.max(1e-3F, arc));
		float x = 0.5F - (float) Math.sin(Math.asin(1F - 2F * y) / 3F);
		return SWEEP_FROM + SWEEP_TICKS * x;
	}

	/** The horizontal direction at {@code bearing} degrees: the way a player with that yaw looks. */
	private static double dirX(double bearing) {
		return -Math.sin(Math.toRadians(bearing));
	}

	private static double dirZ(double bearing) {
		return Math.cos(Math.toRadians(bearing));
	}

	// ------------------------------------------------------------------ geometry (inside the lambdas; px from the centre)

	/** The lead crescent: a writhing strand along the arc from {@code tail} to {@code lead}, thickening toward the lead. */
	private static void crescent(SkinMesh m, float rPx, float startBearing, float side, float tail, float lead, float time, float phase, int seed) {
		int n = stations(tail, lead);
		for (int i = 0; i < n; i++) {
			float u = i / (float) (n - 1);
			double bearing = startBearing - side * (tail + (lead - tail) * u);
			// the frenzy's end-pinned writhe, up and down across the arc and in and out along the radius
			float env = (float) Math.sin(Math.PI * u) * WRITHE_PX;
			float out = rPx + 0.6F * env * (float) Math.cos(0.8F * phase + 5F * u + 0.28F * time);
			float up = env * (float) Math.sin(phase + 7F * u + 0.35F * time);
			PTS[3 * i] = (float) dirX(bearing) * out;
			PTS[3 * i + 1] = up;
			PTS[3 * i + 2] = (float) dirZ(bearing) * out;
		}
		Stations s = S.reset(4).hint(0F, 1F, 0F);
		float v = 0F;
		for (int i = 0; i < n; i++) {
			if (i > 0) v += dist(i - 1, i);
			float half = 0.5F * ArmMath.lerp(TAIL_PX, LEAD_PX, i / (float) (n - 1));
			ArmMath.ring(POLY, 4, half, half, ArmMath.PI / 4F);
			int a = Math.max(0, i - 1), z = Math.min(n - 1, i + 1);
			s.addTransported(PTS[3 * i], PTS[3 * i + 1], PTS[3 * i + 2], PTS[3 * z] - PTS[3 * a], PTS[3 * z + 1] - PTS[3 * a + 1], PTS[3 * z + 2] - PTS[3 * a + 2], v,
					POLY, 1F);
		}
		ST.set(time, TendrilFx.ACTIVE, 1F, seed);
		Loft.sweep(m, s, ArmLayer.BLACK, null, ST, 3, 1F, true, true);
	}

	/** The red edge: a flat strip standing upright just outside the crescent, over the same stretch of the arc. */
	private static void ribbon(SkinMesh m, float rPx, float startBearing, float side, float tail, float lead, float time, int seed) {
		int n = stations(tail, lead);
		for (int i = 0; i < n; i++) {
			double bearing = startBearing - side * (tail + (lead - tail) * (i / (float) (n - 1)));
			PTS[3 * i] = (float) dirX(bearing) * rPx;
			PTS[3 * i + 1] = 0F;
			PTS[3 * i + 2] = (float) dirZ(bearing) * rPx;
		}
		Stations s = S.reset(2);
		float v = 0F;
		for (int i = 0; i < n; i++) {
			if (i > 0) v += dist(i - 1, i);
			int a = Math.max(0, i - 1), z = Math.min(n - 1, i + 1);
			s.add(PTS[3 * i], PTS[3 * i + 1], PTS[3 * i + 2], PTS[3 * z] - PTS[3 * a], 0F, PTS[3 * z + 2] - PTS[3 * a + 2], 0F, 1F, 0F, v, RIBBON, 1F);
		}
		ST.set(time, TendrilFx.ACTIVE, 1F, seed + 1);
		Loft.sweep(m, s, ArmLayer.RED, ALL_RED, ST, 4, 2F, false, false);
	}

	private static int stations(float tail, float lead) {
		return Math.clamp((int) Math.ceil((lead - tail) / STEP_DEG) + 1, 2, MAX_STATIONS);
	}

	private static float dist(int a, int b) {
		float dx = PTS[3 * b] - PTS[3 * a], dy = PTS[3 * b + 1] - PTS[3 * a + 1], dz = PTS[3 * b + 2] - PTS[3 * a + 2];
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	// ------------------------------------------------------------------ hairs and beads

	/** The hairs shed as the lead passes their spot: drifting outward, tilted up and back against the swing, shrinking. */
	private static void hairs(ArmFxFrame f, Swipe s, Vec3 centre, float r, float startBearing, float age, int light) {
		double[] data = null;
		int count = 0;
		for (int k = 0; k < HAIRS; k++) {
			float along = s.arc * (k + 0.5F) / HAIRS;
			float u = (age - reachesAt(along, s.arc)) / HAIR_TICKS;
			if (u < 0F || u > 1F) continue;
			double bearing = startBearing - s.side * along;
			double ox = dirX(bearing), oz = dirZ(bearing);
			// the way the swing travels here is the bearing's derivative: side · (cos, 0, sin)
			double backX = -s.side * Math.cos(Math.toRadians(bearing)), backZ = -s.side * Math.sin(Math.toRadians(bearing));
			double out = r + HAIR_OUT * (1 - (1 - u) * (1 - u));
			double lift = (TendrilFx.hash(s.seed, k) - 0.5) * 0.12;
			if (data == null) data = new double[8 * HAIRS];
			int o = 8 * count++;
			data[o] = centre.x + ox * out;
			data[o + 1] = centre.y + lift;
			data[o + 2] = centre.z + oz * out;
			data[o + 3] = ox + backX * 0.35;
			data[o + 4] = 0.3 + lift;
			data[o + 5] = oz + backZ * 0.35;
			data[o + 6] = HAIR_PX * (1F - u);
			data[o + 7] = HAIR_WIDTH * (1F - 0.7F * u);
		}
		if (count > 0) TendrilFx.filaments(f, data, count, ArmLayer.BLACK, light, s.seed + 2);
	}

	/** Each creature cut bursts three red beads as the lead reaches it; they fly out and fall, shrinking away. */
	private static void beads(ArmFxFrame f, ClientLevel level, Swipe s, Vec3 centre, float age, float pt) {
		for (int i = 0; i < s.targets.length; i++) {
			Entity target = null;
			if (Float.isNaN(s.burstAge[i])) {
				target = level.getEntity(s.targets[i]);
				if (target == null) continue;
				Vec3 d = TendrilFx.wound(target, pt).subtract(centre);
				double bearing = Math.toDegrees(Math.atan2(-d.x, d.z));
				float along = s.arc / 2F - s.side * wrap((float) (bearing - s.yaw));
				s.burstAge[i] = reachesAt(ArmMath.clamp(along, 0F, s.arc), s.arc);
			}
			float u = age - s.burstAge[i];
			if (u < 0F || u > BEAD_TICKS) continue;
			if (Double.isNaN(s.burstAt[3 * i])) {
				if (target == null) target = level.getEntity(s.targets[i]);
				if (target == null) continue;
				Vec3 at = TendrilFx.wound(target, pt);
				s.burstAt[3 * i] = at.x;
				s.burstAt[3 * i + 1] = at.y;
				s.burstAt[3 * i + 2] = at.z;
			}
			double x0 = s.burstAt[3 * i], y0 = s.burstAt[3 * i + 1], z0 = s.burstAt[3 * i + 2];
			double rx = x0 - centre.x, rz = z0 - centre.z, rl = Math.sqrt(rx * rx + rz * rz);
			if (rl > 1e-4) {
				rx /= rl;
				rz /= rl;
			}
			double[] data = new double[4 * BEADS];
			for (int j = 0; j < BEADS; j++) {
				double spin = ArmMath.TAU * TendrilFx.hash(s.seed + 7 * i, j);
				double vx = rx * 0.05 + Math.cos(spin) * 0.05, vz = rz * 0.05 + Math.sin(spin) * 0.05;
				double vy = 0.07 + 0.05 * TendrilFx.hash(s.seed + 7 * i, j + 11);
				data[4 * j] = x0 + vx * u;
				data[4 * j + 1] = y0 + vy * u - 0.5 * BEAD_GRAVITY * u * u;
				data[4 * j + 2] = z0 + vz * u;
				data[4 * j + 3] = BEAD_PX * (1F - u / BEAD_TICKS);
			}
			Vec3 at = new Vec3(x0, y0, z0);
			TendrilFx.beads(f, data, BEADS, ArmLayer.RED, TendrilFx.light(level, at, at), s.seed + 3 + i);
		}
	}

	/** Degrees into -180..180. */
	private static float wrap(float degrees) {
		return degrees - 360F * (float) Math.floor((degrees + 180F) / 360F);
	}
}
