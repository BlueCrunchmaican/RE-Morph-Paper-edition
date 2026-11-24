package dev.morphreborn.client.arm.fx;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.geom.LureGeometry;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.ArmTint;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The lantern's flare (S_ARM_FX FLARE: {@code positions[0]} the cell it lands in, {@code ticks} how long it lives): a seed
 * of the lure's own glowing core is thrown along the beam at 1.2 blocks a tick, trailing two twisted tendrils and shedding
 * beads, and where the beam ended it blooms and settles into an ember, a core of light among seven writhing petals tipped
 * with glowing buds. When its time runs out the petals draw back into it and it goes out. (The lure bursts as it is thrown:
 * that is {@link LureGeometry#flared}, called here as the effect arrives.)
 * <p>
 * Everything shown comes from the one effect message every viewer gets, so everyone sees the same flare. Where the seed
 * starts is the thrower's lure as drawn this frame ({@link ArmFx#anchor}), looked up when it is first drawn.
 * <ul>
 *   <li><b>Flight</b> ({@code distance / 1.2} ticks, 3 to 40): a lob a little above the straight line for long throws; the
 *       core flickers; the tail is the last 4 ticks of the path as a black tendril and a thinner red one wound round it;
 *       five beads, smaller further back, hang in it.</li>
 *   <li><b>Bloom</b> (the first 10 ticks after landing): the core swells to about twice its size and settles; ten hairs
 *       are flung out of it and draw back by tick 12; the petals grow over ticks 3..9.</li>
 *   <li><b>Ember</b>: the core breathes; each petal is a black tendril with a thin red one beside it, swelling in a pulse
 *       that runs out along it, and ends in a glowing bud that flickers.</li>
 *   <li><b>Going out</b> (the last 16 ticks): the petals and the core shrink away together.</li>
 * </ul>
 * One flare per thrower (a new one replaces the old), at most {@link #MAX} showing, none drawn beyond 96 blocks.
 * Render thread only (the effect, the tick and the frame all run there).
 */
public final class FlareFx implements FxHandler {
	/** The seed's speed, blocks a tick (the server's). */
	public static final double SPEED = 1.2;
	/** Flights are at least this many ticks and at most this many. */
	public static final int MIN_FLIGHT = 3, MAX_FLIGHT = 40;
	/** The most flares shown at once, and the distance past which one isn't drawn. */
	static final int MAX = 8;
	private static final double RANGE = 96.0;
	/** A flare lasts this long when the message gives no time, and at most this long. */
	static final int DEFAULT_LIFE = 400, MAX_LIFE = 1200;
	/** The bloom: the core's peak (px) and settled size, the petals' growth window, and the hairs' flight (ticks after landing). */
	static final float CORE_PEAK = 6.5F, CORE_REST = 3.2F, PETALS_FROM = 3F, PETALS_TICKS = 6F, HAIR_OUT = 12F, DYING_TICKS = 16F;
	/** The tail: ticks of the path it covers. */
	private static final float TAIL_TICKS = 4F;
	private static final int PETALS = 7, HAIRS = 10, BEADS = 5;
	/** The petals, each its own: which way round (radians), how far up from level, length (blocks), root width (px), phase. Hand-tuned. */
	static final float[] P_AZ = {0.3F, 1.2F, 2.2F, 3.1F, 4.0F, 4.9F, 5.7F}, P_EL = {0.7F, 0.3F, 1.0F, 0.5F, 0.9F, 0.2F, 0.6F},
			P_LEN = {0.62F, 0.48F, 0.75F, 0.55F, 0.66F, 0.42F, 0.58F}, P_W = {1.5F, 1.2F, 1.7F, 1.3F, 1.6F, 1.1F, 1.4F},
			P_PH = {0.4F, 2.9F, 5.1F, 1.7F, 3.8F, 0.9F, 4.6F};
	private static final int PART_CORE = 120, PART_BUD = 121;

	/** One flare in flight or burning. */
	static final class Flare {
		final int origin, level, life, seed;
		final long start;
		final double cx, cy, cz;
		/** The seed's start, worked out when first drawn (the thrower's lure), and the flight in ticks. */
		boolean resolved;
		double fx, fy, fz;
		int flight = MIN_FLIGHT;

		Flare(int origin, int level, int life, int seed, long start, double cx, double cy, double cz) {
			this.origin = origin;
			this.level = level;
			this.life = life;
			this.seed = seed;
			this.start = start;
			this.cx = cx;
			this.cy = cy;
			this.cz = cz;
		}
	}

	private final List<Flare> flares = new ArrayList<>();
	private static final Static ST = new Static();
	private static final Matrix4f LOCAL = new Matrix4f();

	public FlareFx() {}

	@Override
	public int[] kinds() {
		return new int[] {ArmProtocol.FX_FLARE};
	}

	@Override
	public void start(ArmFxEvent e) {
		if (e.positions().length == 0) return; // a FLARE with no cell shows nothing
		BlockPos cell = BlockPos.of(e.positions()[0]);
		flares.removeIf(f -> f.origin == e.origin()); // one flare per thrower
		if (flares.size() >= MAX) flares.remove(0);
		flares.add(new Flare(e.origin(), e.level(), lifeTicks(e.ticks()), e.seed(), e.receivedTick(), cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5));
		// the lure bursts as the seed leaves it
		LureGeometry.flared(e.origin(), e.receivedTick());
	}

	@Override
	public void tick() {
		long now = Arms.TICK;
		// the longest it could still be showing: its flight (at most 40 ticks), its life, and a margin
		flares.removeIf(f -> now - f.start > MAX_FLIGHT + f.life + 20L);
	}

	@Override
	public void clear() {
		flares.clear();
	}

	/** How many flares are showing or in flight: for the tests. */
	int count() {
		return flares.size();
	}

	// ------------------------------------------------------------------ the pure parts

	/**
	 * How long a flare lives, in ticks, from the message's {@code ticks}: a time not given is the default (20 s); one that
	 * is under 3 s's worth of ticks can only be seconds (the server's flare lasts 20, 30 or 40 of them) and is converted;
	 * the rest is taken as ticks, capped at a minute.
	 */
	public static int lifeTicks(int ticks) {
		if (ticks <= 0) return DEFAULT_LIFE;
		if (ticks < 60) return ticks * 20;
		return Math.min(ticks, MAX_LIFE);
	}

	/** The flight's length in ticks for a throw of {@code distance} blocks at {@link #SPEED}. */
	public static int flightTicks(double distance) {
		if (!(distance > 0.0)) return MIN_FLIGHT;
		return Math.min(MAX_FLIGHT, Math.max(MIN_FLIGHT, (int) Math.ceil(distance / SPEED)));
	}

	/**
	 * The seed's place after {@code e} (0..1) of its flight from ({@code fx, fy, fz}) to ({@code tx, ty, tz}), into
	 * {@code out}: the straight line plus a lob that lifts it up to 0.25 blocks in the middle of a throw of 10 blocks or
	 * more (less for short ones), landing exactly on the cell.
	 */
	public static void seedAt(double fx, double fy, double fz, double tx, double ty, double tz, double e, double[] out) {
		double k = Math.min(1.0, Math.max(0.0, e));
		double dx = tx - fx, dy = ty - fy, dz = tz - fz;
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		double lob = 0.25 * Math.min(1.0, dist / 10.0) * Math.sin(Math.PI * k);
		out[0] = fx + dx * k;
		out[1] = fy + dy * k + lob;
		out[2] = fz + dz * k;
	}

	/** The core's size in px {@code t} ticks after landing: the bloom's swell to {@link #CORE_PEAK}, settling to {@link #CORE_REST}. */
	public static float coreSize(float t) {
		if (t < 0F) return 0F;
		float swell = CORE_REST + (CORE_PEAK - CORE_REST) * ArmMath.gauss(t - 2F, 1.6F);
		// it opens from nothing in the first tick
		return swell * ArmMath.sm(t / 1F);
	}

	/**
	 * How far the petals have grown, 0..1, {@code t} ticks after landing for a flare that lives {@code life} ticks: they
	 * grow over {@link #PETALS_TICKS} from {@link #PETALS_FROM}, and draw back over the last {@link #DYING_TICKS}.
	 */
	public static float petalGrowth(float t, int life) {
		float up = ArmMath.sm((t - PETALS_FROM) / PETALS_TICKS);
		float down = ArmMath.sm((life - t) / DYING_TICKS);
		return up * down;
	}

	/** The hairs flung at the bloom: how far out they reach (blocks) {@code t} ticks after landing; gone by {@link #HAIR_OUT}. */
	public static float hairReach(float t) {
		if (t < 0F || t >= HAIR_OUT) return 0F;
		float out = 1F - (1F - ArmMath.clamp01(t / 4F)) * (1F - ArmMath.clamp01(t / 4F));
		float back = 1F - ArmMath.sm((t - 6F) / (HAIR_OUT - 6F));
		return 1.8F * out * back;
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void collect(ArmFxFrame f) {
		if (flares.isEmpty()) return;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		for (Flare fl : flares) {
			ArmTint.owner(fl.origin); // this effect is drawn in its owner's colour
			if (!fl.resolved) resolve(fl);
			Vec3 cam = f.camPos();
			double dx = fl.cx - cam.x, dy = fl.cy - cam.y, dz = fl.cz - cam.z;
			if (dx * dx + dy * dy + dz * dz > RANGE * RANGE) continue;
			float age = Arms.age(fl.start, f.partialTick());
			if (age < 0F) continue;
			if (age < fl.flight) flight(f, level, fl, age);
			else landed(f, level, fl, age - fl.flight);
		}
	}

	/** The seed's start: the thrower's lure, or the cell itself when the thrower isn't there (no flight then). */
	private static void resolve(Flare fl) {
		fl.resolved = true;
		Vec3 from = ArmFx.anchor(fl.origin, ArmTips.LURE);
		if (from == null || !TendrilFx.finite(from)) {
			fl.fx = fl.cx;
			fl.fy = fl.cy;
			fl.fz = fl.cz;
			fl.flight = MIN_FLIGHT;
			return;
		}
		fl.fx = from.x;
		fl.fy = from.y;
		fl.fz = from.z;
		fl.flight = flightTicks(from.distanceTo(new Vec3(fl.cx, fl.cy, fl.cz)));
	}

	private static void flight(ArmFxFrame f, ClientLevel level, Flare fl, float age) {
		double[] at = new double[3], tail = new double[3];
		float u = age / fl.flight;
		seedAt(fl.fx, fl.fy, fl.fz, fl.cx, fl.cy, fl.cz, u, at);
		Vec3 head = new Vec3(at[0], at[1], at[2]);
		float ph = fl.seed * 0.37F;
		// the tail: the last few ticks of the path, a black tendril and a thinner red one wound round it
		float back = Math.min(age, TAIL_TICKS);
		seedAt(fl.fx, fl.fy, fl.fz, fl.cx, fl.cy, fl.cz, (age - back) / fl.flight, tail);
		Vec3 tailEnd = new Vec3(tail[0], tail[1], tail[2]);
		int light = TendrilFx.light(level, head, tailEnd);
		if (head.distanceToSqr(tailEnd) > 1e-4) {
			TendrilFx.draw(f, tailEnd, head, 1F, ph, 0.25F, 1.2F, 0.8F, 0.5F, ArmLayer.BLACK, light);
			TendrilFx.draw(f, tailEnd, head, 1F, ph + 1.9F, 0.2F, 0.7F, 0.6F, 0.5F, ArmLayer.RED, light);
		}
		// beads in the tail, smaller the further back
		double[] beads = new double[4 * BEADS];
		int n = 0;
		double[] p = new double[3];
		for (int k = 1; k <= BEADS; k++) {
			float e = (age - k * 0.8F) / fl.flight;
			if (e < 0F) break;
			seedAt(fl.fx, fl.fy, fl.fz, fl.cx, fl.cy, fl.cz, e, p);
			float jx = TendrilFx.hash(fl.seed, k) - 0.5F, jy = TendrilFx.hash(fl.seed, k + 17) - 0.5F, jz = TendrilFx.hash(fl.seed, k + 31) - 0.5F;
			beads[4 * n] = p[0] + jx * 0.16;
			beads[4 * n + 1] = p[1] + jy * 0.16;
			beads[4 * n + 2] = p[2] + jz * 0.16;
			beads[4 * n + 3] = 1.5F - k * 0.22F;
			n++;
		}
		TendrilFx.beads(f, beads, n, ArmLayer.BLACK, light, fl.seed);
		// the seed itself: the lure's core, flickering
		float size = 2.0F * (1F + 0.15F * (float) Math.sin(age * 1.7F + ph));
		core(f, head, size, f.time(), fl.seed);
	}

	private static void landed(ArmFxFrame f, ClientLevel level, Flare fl, float t) {
		Vec3 c = new Vec3(fl.cx, fl.cy, fl.cz);
		int light = TendrilFx.light(level, c, c);
		float time = f.time();
		float ph = fl.seed * 0.37F;
		float dying = ArmMath.sm((fl.life - t) / DYING_TICKS);
		float breath = 1F + 0.1F * (float) Math.sin(0.45F * time + ph) + 0.05F * (float) Math.sin(1.3F * time + 2F * ph);
		core(f, c, coreSize(t) * breath * dying, time, fl.seed);
		// the hairs flung at the bloom
		float hair = hairReach(t);
		if (hair > 0.01F) {
			double[] data = new double[8 * HAIRS];
			for (int i = 0; i < HAIRS; i++) {
				float az = ArmMath.TAU * i / HAIRS + TendrilFx.hash(fl.seed, i) * 0.6F;
				float el = (TendrilFx.hash(fl.seed, i + 40) - 0.35F) * 1.6F;
				float ce = (float) Math.cos(el);
				int o = 8 * i;
				data[o] = c.x;
				data[o + 1] = c.y;
				data[o + 2] = c.z;
				data[o + 3] = ce * Math.cos(az);
				data[o + 4] = Math.sin(el);
				data[o + 5] = ce * Math.sin(az);
				data[o + 6] = hair * (0.55F + 0.45F * TendrilFx.hash(fl.seed, i + 80));
				data[o + 7] = 0.8F;
			}
			TendrilFx.filaments(f, data, HAIRS, ArmLayer.RED, light, fl.seed);
		}
		// the petals
		float grow = petalGrowth(t, fl.life);
		if (!(grow > 0.02F)) return;
		double[] buds = new double[4 * PETALS];
		for (int i = 0; i < PETALS; i++) {
			float el = P_EL[i], az = P_AZ[i] + 0.08F * (float) Math.sin(ph + 0.05F * time + i);
			double ce = Math.cos(el);
			double dx = ce * Math.cos(az), dy = Math.sin(el), dz = ce * Math.sin(az);
			float len = P_LEN[i] * grow * (1F + 0.06F * (float) Math.sin(0.4F * time + P_PH[i]));
			Vec3 root = new Vec3(c.x + dx * 0.04, c.y + dy * 0.04, c.z + dz * 0.04);
			Vec3 tip = new Vec3(c.x + dx * len, c.y + dy * len, c.z + dz * len);
			// a pulse running out along the petal, each on its own beat
			float pulse = ArmMath.frac(0.045F * time + P_PH[i] * 0.2F);
			TendrilFx.draw(f, root, tip, 1F, P_PH[i], P_W[i], 0.35F, pulse, 0.55F, ArmLayer.BLACK, light);
			Vec3 side = new Vec3(-dz, 0, dx).scale(0.025);
			TendrilFx.draw(f, root.add(side), tip.add(side.scale(-1.0)), 1F, P_PH[i] + 2.0F, 0.6F, 0.15F, pulse, 0.4F, ArmLayer.RED, light);
			buds[4 * i] = tip.x;
			buds[4 * i + 1] = tip.y;
			buds[4 * i + 2] = tip.z;
			buds[4 * i + 3] = (0.9F + 0.35F * (float) Math.sin(1.1F * time + P_PH[i] * 2F)) * grow * dying;
		}
		budsGlow(f, buds, light, fl.seed);
	}

	/** The core: two boxes, one turned, in the glow layer at full brightness; {@code size} px. */
	private static void core(ArmFxFrame f, Vec3 at, float size, float time, int seed) {
		if (!(size > 0.05F)) return;
		float turn = time * 0.11F + seed * 0.37F;
		TendrilFx.submit(f, at, ArmLayer.GLOW, 0xF000F0, m -> {
			ST.set(time, TendrilFx.ACTIVE, 0F, seed);
			float half = size / 2F;
			m.box(null, -half, -half, -half, size, size, size, ST.u0(PART_CORE, 0, 0F), ST.v0(PART_CORE, 0, 0F), 1F, 1F, SkinMesh.WHITE);
			LOCAL.identity().rotateXYZ(0.78F + turn, 0.6F * turn, 0.5F);
			float small = size * 0.78F;
			m.box(LOCAL, -small / 2F, -small / 2F, -small / 2F, small, small, small, ST.u0(PART_CORE, 1, 0F), ST.v0(PART_CORE, 1, 0F), 1F, 1F, SkinMesh.WHITE);
		});
	}

	/** The petals' buds: a small glowing cube at each tip ({@code x, y, z, size px} per bud). */
	private static void budsGlow(ArmFxFrame f, double[] buds, int light, int seed) {
		Vec3 at = new Vec3(buds[0], buds[1], buds[2]);
		float time = f.time();
		float[] rel = new float[buds.length];
		for (int i = 0; i < PETALS; i++) {
			rel[4 * i] = (float) ((buds[4 * i] - at.x) * 16.0);
			rel[4 * i + 1] = (float) ((buds[4 * i + 1] - at.y) * 16.0);
			rel[4 * i + 2] = (float) ((buds[4 * i + 2] - at.z) * 16.0);
			rel[4 * i + 3] = (float) buds[4 * i + 3];
		}
		TendrilFx.submit(f, at, ArmLayer.GLOW, 0xF000F0, m -> {
			ST.set(time, TendrilFx.ACTIVE, 0F, seed);
			for (int i = 0; i < PETALS; i++) {
				float size = rel[4 * i + 3];
				if (!(size > 0.05F)) continue;
				float half = size / 2F;
				m.box(null, rel[4 * i] - half, rel[4 * i + 1] - half, rel[4 * i + 2] - half, size, size, size, ST.u0(PART_BUD, i, 0F), ST.v0(PART_BUD, i, 0F), 1F, 1F,
						SkinMesh.WHITE);
			}
		});
	}
}
