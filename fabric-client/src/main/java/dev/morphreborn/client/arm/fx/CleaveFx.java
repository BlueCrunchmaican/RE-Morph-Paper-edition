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
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Cleave (S_ARM_FX CLEAVE): the axe's overhead chop. A crescent of black tendril swings down through a vertical plane
 * in front of the striker, over ticks 2 to 5, with a red ribbon on its outer edge and hairs shed as it passes; where it
 * lands on a creature (the effect names it) the creature is bitten from above and below by six red tendrils closing
 * on its middle, with a clenched knot where they meet and a splash of beads and hairs. It is the axe's answer to
 * Draining Cut's sweep: the same alive crescent, but straight down, and it bites instead of sweeping.
 * <ul>
 *   <li>The swing: from 75 degrees above the forward direction to 35 below it, the lead eased over ticks 2..5; the
 *       crescent keeps 55 degrees behind the lead (2.8 px thick at the lead, 0.6 at the tail), and closes up over 2 ticks
 *       once the swing is done.</li>
 *   <li>Its radius is the distance to the creature hit (kept between 1.3 and 4.6 blocks); with no creature named, or for your
 *       own arm in first person, a fixed 1.5, so the chop crosses the view rather than leaving it.</li>
 *   <li>The bite: ticks 4..10, the jaws closing over 2 ticks, held, and opening again over the last 2.</li>
 * </ul>
 * The aim is taken as the effect arrives, so the chop stays where it was aimed while the striker turns. Nothing here comes
 * from local input: it is all from the effect and the world, so every viewer sees the same thing.
 */
public final class CleaveFx implements FxHandler {
	/** Blocks: the chop's radius for your own arm and when no creature was hit; the limits when one was. */
	static final float OWN_RADIUS = 1.5F, DEFAULT_RADIUS = 2.0F, MIN_RADIUS = 1.3F, MAX_RADIUS = 4.6F, DROP = 0.25F;
	/** The swing: degrees above forward it starts, the sweep's size, from which tick for how many, and the crescent's trail. */
	static final float START_DEG = 75F, SWEEP_DEG = 110F, SWEEP_FROM = 2F, SWEEP_TICKS = 3F, TRAIL = 55F, CATCH_UP = 2F;
	private static final float LEAD_PX = 2.8F, TAIL_PX = 0.6F, WRITHE_PX = 0.8F, RIBBON_PX = 0.9F, RIBBON_OUT = 0.05F;
	private static final int HAIRS = 8;
	private static final float HAIR_TICKS = 5F, HAIR_OUT = 0.3F, HAIR_PX = 3F, HAIR_WIDTH = 0.4F;
	/** The bite: when the jaws start closing, how long they take to, hold, and open again. */
	static final float BITE_FROM = 4F, BITE_CLOSE = 2F, BITE_HOLD = 2F, BITE_OPEN = 2F;
	private static final int JAWS = 6;
	private static final float JAW_REACH = 0.8F, JAW_FROM_PX = 1.8F, JAW_TO_PX = 0.6F, KNOT_PX = 2.2F;
	private static final int BEADS = 6;
	private static final float BEAD_TICKS = 6F, BEAD_PX = 0.8F, BEAD_GRAVITY = 0.04F;
	/** Ticks a cleave lasts, how many show at once, the crescent's stations (one every 5 degrees, at most). */
	static final int LIFE = 12, MAX = 6, MAX_STATIONS = 16;
	/** How long the axe's plates stay splayed in hand (the geometry closes them again by the sixth tick). */
	static final int SPLAY_TICKS = 6;
	private static final float STEP_DEG = 5F;

	private static final class Chop {
		final int origin, target, seed;
		final long start;
		/** The aim (yaw, degrees), set once the striker is known (NaN until then). */
		float yaw = Float.NaN;

		Chop(int origin, int target, long start, int seed) {
			this.origin = origin;
			this.target = target;
			this.start = start;
			this.seed = seed;
		}
	}

	private final List<Chop> chops = new ArrayList<>();

	private static final Stations S = new Stations();
	private static final Static ST = new Static();
	private static final float[] POLY = new float[8], PTS = new float[3 * MAX_STATIONS];
	private static final float[] RIBBON = {-RIBBON_PX / 2F, 0F, RIBBON_PX / 2F, 0F};
	private static final Loft.FaceLayer ALL_RED = (segment, face) -> ArmLayer.RED;

	public CleaveFx() {}

	@Override
	public int[] kinds() {
		return new int[] {ArmProtocol.FX_CLEAVE};
	}

	@Override
	public void start(ArmFxEvent e) {
		int target = e.ids().length > 0 ? e.ids()[0] : -1;
		Chop c = new Chop(e.origin(), target, e.receivedTick(), (e.seed() ^ e.origin() * 31) & 0x7FFF);
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc == null ? null : mc.level;
		Entity origin = level == null ? null : level.getEntity(e.origin());
		if (origin != null) c.yaw = origin.getViewYRot(1F);
		if (chops.size() >= MAX) chops.remove(0);
		chops.add(c);
		// in hand, the axe's plates splay for the chop's first six ticks
		FellFx.reaction(e.origin(), e.receivedTick()).special(ArmProtocol.FX_CLEAVE, e.receivedTick(), SPLAY_TICKS);
	}

	@Override
	public void tick() {
		long now = Arms.TICK;
		chops.removeIf(c -> now - c.start > LIFE);
	}

	@Override
	public void clear() {
		chops.clear();
	}

	// ------------------------------------------------------------------ timing (degrees along the sweep from its start)

	/** How far the crescent's leading end has swung, in degrees of the sweep, {@code age} ticks in. */
	static float lead(float age) {
		return SWEEP_DEG * ArmMath.sm((age - SWEEP_FROM) / SWEEP_TICKS);
	}

	/** Where its tail is: {@link #TRAIL} degrees behind the lead (never before the start), closing on it after the swing. */
	static float tail(float age) {
		float end = SWEEP_FROM + SWEEP_TICKS;
		float gap = age <= end ? TRAIL : TRAIL * Math.max(0F, 1F - (age - end) / CATCH_UP);
		return ArmMath.clamp(lead(age) - gap, 0F, SWEEP_DEG);
	}

	/** When (ticks in) the lead has swung {@code along} degrees: smoothstep turned round. */
	static float reachesAt(float along) {
		float y = ArmMath.clamp01(along / SWEEP_DEG);
		float x = 0.5F - (float) Math.sin(Math.asin(1F - 2F * y) / 3F);
		return SWEEP_FROM + SWEEP_TICKS * x;
	}

	/** How far the jaws have closed, 0..1: closing from tick 4, held, opening over the last 2 ticks. */
	static float bite(float age) {
		float closed = ArmMath.sm((age - BITE_FROM) / BITE_CLOSE);
		float open = ArmMath.sm((age - BITE_FROM - BITE_CLOSE - BITE_HOLD) / BITE_OPEN);
		return closed * (1F - open);
	}

	/** The chop's radius in blocks: fixed for your own arm in first person, else the distance to what it hit, kept in range. */
	static float radius(boolean own, double distanceToTarget) {
		if (own || !(distanceToTarget > 0)) return own ? OWN_RADIUS : DEFAULT_RADIUS;
		return (float) Math.clamp(distanceToTarget, MIN_RADIUS, MAX_RADIUS);
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void collect(ArmFxFrame f) {
		if (chops.isEmpty()) return;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		float pt = f.partialTick();
		for (Chop c : chops) {
			ArmTint.owner(c.origin); // this effect is drawn in its owner's colour
			Entity origin = level.getEntity(c.origin);
			if (origin == null) continue;
			if (Float.isNaN(c.yaw)) c.yaw = origin.getViewYRot(1F);
			float age = Arms.age(c.start, pt);
			boolean own = ArmFx.isOwnFirstPerson(c.origin, f.firstPersonCamera());
			Vec3 centre = origin.getEyePosition(pt).subtract(0, DROP, 0);
			if (!TendrilFx.finite(centre)) continue;
			Entity target = c.target >= 0 ? level.getEntity(c.target) : null;
			double dist = target == null ? -1 : horizontal(TendrilFx.wound(target, pt).subtract(centre));
			float r = radius(own, dist);
			int light = TendrilFx.light(level, centre, centre);
			float yawRad = (float) Math.toRadians(c.yaw);
			float lead = lead(age), tail = tail(age);
			if (lead - tail > 0.5F) {
				float rPx = r * 16F, time = f.time(), phase = c.origin * 0.37F;
				int seed = c.seed;
				TendrilFx.submit(f, centre, ArmLayer.BLACK, light, m -> crescent(m, rPx, yawRad, tail, lead, time, phase, seed));
				TendrilFx.submit(f, centre, ArmLayer.RED, light, m -> ribbon(m, rPx + RIBBON_OUT * 16F, yawRad, tail, lead, time, seed));
			}
			hairs(f, c, centre, r, yawRad, age, light);
			if (target instanceof LivingEntity victim && !victim.isRemoved()) bite(f, level, c, victim, centre, age, pt);
		}
	}

	private static double horizontal(Vec3 v) {
		return Math.sqrt(v.x * v.x + v.z * v.z);
	}

	/** The point {@code beta} degrees above forward at radius {@code r} (any unit) from the centre, for yaw {@code yawRad}: x, y, z. */
	private static void arcPoint(float yawRad, float beta, float r, float[] out, int o) {
		double b = Math.toRadians(beta);
		double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);
		out[o] = (float) (fx * Math.cos(b) * r);
		out[o + 1] = (float) (Math.sin(b) * r);
		out[o + 2] = (float) (fz * Math.cos(b) * r);
	}

	/** The lateral direction (to the striker's right) for yaw {@code yawRad}, x and z. */
	private static float rightX(float yawRad) {
		return (float) -Math.cos(yawRad);
	}

	private static float rightZ(float yawRad) {
		return (float) -Math.sin(yawRad);
	}

	/** The lead crescent: a writhing strand down the arc from {@code tail} to {@code lead}, thickening toward the lead. */
	private static void crescent(SkinMesh m, float rPx, float yawRad, float tail, float lead, float time, float phase, int seed) {
		int n = stations(tail, lead);
		float rx = rightX(yawRad), rz = rightZ(yawRad);
		for (int i = 0; i < n; i++) {
			float u = i / (float) (n - 1);
			float p = tail + (lead - tail) * u;
			float beta = START_DEG - p;
			// the frenzy's end-pinned writhe: sideways across the plane and in and out along the radius
			float env = (float) Math.sin(Math.PI * u) * WRITHE_PX;
			float out = rPx + 0.6F * env * (float) Math.cos(0.8F * phase + 5F * u + 0.28F * time);
			float side = env * (float) Math.sin(phase + 7F * u + 0.35F * time);
			arcPoint(yawRad, beta, out, PTS, 3 * i);
			PTS[3 * i] += rx * side;
			PTS[3 * i + 2] += rz * side;
		}
		Stations s = S.reset(4).hint(rx, 0F, rz);
		float v = 0F;
		for (int i = 0; i < n; i++) {
			if (i > 0) v += dist(i - 1, i);
			float half = 0.5F * ArmMath.lerp(TAIL_PX, LEAD_PX, i / (float) (n - 1));
			ArmMath.ring(POLY, 4, half, half, ArmMath.PI / 4F);
			int a = Math.max(0, i - 1), z = Math.min(n - 1, i + 1);
			s.addTransported(PTS[3 * i], PTS[3 * i + 1], PTS[3 * i + 2], PTS[3 * z] - PTS[3 * a], PTS[3 * z + 1] - PTS[3 * a + 1], PTS[3 * z + 2] - PTS[3 * a + 2], v, POLY, 1F);
		}
		ST.set(time, TendrilFx.ACTIVE, 1F, seed);
		Loft.sweep(m, s, ArmLayer.BLACK, null, ST, 3, 1F, true, true);
	}

	/** The red edge: a flat strip standing across the arc's plane just outside the crescent, over the same stretch. */
	private static void ribbon(SkinMesh m, float rPx, float yawRad, float tail, float lead, float time, int seed) {
		int n = stations(tail, lead);
		float rx = rightX(yawRad), rz = rightZ(yawRad);
		for (int i = 0; i < n; i++) {
			float p = tail + (lead - tail) * (i / (float) (n - 1));
			arcPoint(yawRad, START_DEG - p, rPx, PTS, 3 * i);
		}
		Stations s = S.reset(2);
		float v = 0F;
		for (int i = 0; i < n; i++) {
			if (i > 0) v += dist(i - 1, i);
			int a = Math.max(0, i - 1), z = Math.min(n - 1, i + 1);
			s.add(PTS[3 * i], PTS[3 * i + 1], PTS[3 * i + 2], PTS[3 * z] - PTS[3 * a], PTS[3 * z + 1] - PTS[3 * a + 1], PTS[3 * z + 2] - PTS[3 * a + 2], rx, 0F, rz, v, RIBBON, 1F);
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

	/** The hairs shed as the lead passes their spot: drifting outward and down the way the chop goes, shrinking. */
	private static void hairs(ArmFxFrame f, Chop c, Vec3 centre, float r, float yawRad, float age, int light) {
		double[] data = null;
		int count = 0;
		float[] at = new float[3];
		for (int k = 0; k < HAIRS; k++) {
			float along = SWEEP_DEG * (k + 0.5F) / HAIRS;
			float u = (age - reachesAt(along)) / HAIR_TICKS;
			if (u < 0F || u > 1F) continue;
			float beta = START_DEG - along;
			double out = r + HAIR_OUT * (1 - (1 - u) * (1 - u));
			arcPoint(yawRad, beta, (float) out, at, 0);
			// outward along the radius, tipped back up against the chop and a little sideways
			double b = Math.toRadians(beta), fx = -Math.sin(yawRad), fz = Math.cos(yawRad);
			double ox = fx * Math.cos(b), oy = Math.sin(b), oz = fz * Math.cos(b);
			double backY = -Math.cos(b) * 0.35;
			double lift = (TendrilFx.hash(c.seed, k) - 0.5) * 0.4;
			if (data == null) data = new double[8 * HAIRS];
			int o = 8 * count++;
			data[o] = centre.x + at[0];
			data[o + 1] = centre.y + at[1];
			data[o + 2] = centre.z + at[2];
			data[o + 3] = ox + rightX(yawRad) * lift;
			data[o + 4] = oy + backY;
			data[o + 5] = oz + rightZ(yawRad) * lift;
			data[o + 6] = HAIR_PX * (1F - u);
			data[o + 7] = HAIR_WIDTH * (1F - 0.7F * u);
		}
		if (count > 0) TendrilFx.filaments(f, data, count, ArmLayer.BLACK, light, c.seed + 2);
	}

	/**
	 * The bite on the creature the chop landed on: six red tendrils from above and below (round a horizontal axis across
	 * the chop) closing on its middle, a clenched knot where they meet, hairs splashed outward and beads flung off.
	 */
	private static void bite(ArmFxFrame f, ClientLevel level, Chop c, LivingEntity victim, Vec3 centre, float age, float pt) {
		float closed = bite(age);
		if (!(closed > 0.002F)) return;
		// the creature may be knocked on while it is bitten: the bite follows it
		Vec3 at = TendrilFx.wound(victim, pt);
		int light = TendrilFx.light(level, centre, at);
		double yaw = Math.toRadians(c.yaw);
		double rx = rightX((float) yaw), rz = rightZ((float) yaw);
		for (int k = 0; k < JAWS; k++) {
			// jaws spread round the chop's own direction (forward-and-down), above and below the wound
			double ang = ArmMath.TAU * k / JAWS + c.seed * 0.01;
			double vertical = Math.cos(ang), lateral = Math.sin(ang);
			Vec3 from = at.add(rx * lateral * 0.45, vertical * JAW_REACH, rz * lateral * 0.45);
			Vec3 to = at.add(rx * lateral * 0.05, vertical * 0.05, rz * lateral * 0.05);
			TendrilFx.draw(f, from, to, closed, c.seed * 0.9F + k * 1.7F, JAW_FROM_PX, JAW_TO_PX, 0F, 0F, ArmLayer.RED, light);
		}
		if (closed > 0.8F) TendrilFx.knot(f, at, KNOT_PX, ArmLayer.BLACK, light, c.seed);
		splash(f, level, c, at, age, light);
	}

	/** Beads and hairs thrown off the bite from tick 5: hairs radially, red beads upward and out, falling. */
	private static void splash(ArmFxFrame f, ClientLevel level, Chop c, Vec3 at, float age, int light) {
		float u = age - (BITE_FROM + 1F);
		if (u < 0F || u > BEAD_TICKS) return;
		double[] beads = new double[4 * BEADS];
		for (int j = 0; j < BEADS; j++) {
			double spin = ArmMath.TAU * TendrilFx.hash(c.seed, j);
			double vx = Math.cos(spin) * 0.07, vz = Math.sin(spin) * 0.07;
			double vy = 0.06 + 0.06 * TendrilFx.hash(c.seed, j + 11);
			beads[4 * j] = at.x + vx * u;
			beads[4 * j + 1] = at.y + vy * u - 0.5 * BEAD_GRAVITY * u * u;
			beads[4 * j + 2] = at.z + vz * u;
			beads[4 * j + 3] = BEAD_PX * (1F - u / BEAD_TICKS);
		}
		TendrilFx.beads(f, beads, BEADS, ArmLayer.RED, light, c.seed + 3);
		double[] data = new double[8 * 6];
		for (int j = 0; j < 6; j++) {
			double spin = ArmMath.TAU * j / 6 + c.seed * 0.2;
			int o = 8 * j;
			data[o] = at.x;
			data[o + 1] = at.y;
			data[o + 2] = at.z;
			data[o + 3] = Math.cos(spin);
			data[o + 4] = 0.25 + 0.5 * TendrilFx.hash(c.seed, j + 30);
			data[o + 5] = Math.sin(spin);
			data[o + 6] = 3.2F * (1F - u / BEAD_TICKS);
			data[o + 7] = 0.4F * (1F - 0.6F * u / BEAD_TICKS);
		}
		TendrilFx.filaments(f, data, 6, ArmLayer.BLACK, light, c.seed + 4);
	}
}
