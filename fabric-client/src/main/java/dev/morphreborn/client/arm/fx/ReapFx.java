package dev.morphreborn.client.arm.fx;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmTips;
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
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The sickle's Reap (S_ARM_FX REAP): the crescent draws out into a whip 2.5 blocks long at the height of the crops and
 * lashes once round the reaper, a full turn in 8 ticks, then draws back into the point. Every crop the server reaped is
 * snipped as the lash comes round to it (the ones beyond the whip's own length a moment later, as if a ripple ran out
 * from it): a quick black filament flicks up off the stem and two red beads burst from the cut.
 * <p>
 * Seen from your own first-person camera it roots at the hand the hand pass drew, and from anyone's at the hand the world
 * pass drew ({@link ArmFx#anchor}); the lash turns from the main-hand side, to the other side for a left-handed player. The
 * whip is a lofted tendril, 2.4 px at the root to 0.5 at the end, lagging up to 80 degrees behind its leading end while it
 * swings and writhing with the frenzy's end-pinned waves, with a thin red ribbon along its lower edge and eight hairs shed
 * outward as the lead goes by (140 boxes or fewer in all). Crops come from the server as block positions: up to 32 are
 * drawn.
 */
public final class ReapFx implements FxHandler {
	/** The whip's length in blocks, and the lash: it swings from tick 0.5 through 7.5, a full turn, lagging behind the lead. */
	private static final float WHIP_BLOCKS = 2.5F, SWEEP_FROM = 0.5F, SWEEP_TICKS = 7.5F, LAG_DEG = 80F;
	/** The whip draws out over this many ticks, and back in over the last stretch of its life. */
	private static final float OUT_TICKS = 1.5F, BACK_FROM = 9F, BACK_TICKS = 3F;
	/** How high above the reaper's feet the whip lashes (crop height), and how fast a ripple runs on to crops beyond its reach (blocks a tick). */
	private static final double HEIGHT = 0.5, RIPPLE = 1.0;
	/** Width of the whip at its root and its end, and of the red ribbon (px). */
	private static final float ROOT_PX = 2.4F, END_PX = 0.5F, RIBBON_PX = 0.7F;
	private static final int STATIONS = 14, HAIRS = 8;
	private static final float HAIR_TICKS = 5F, HAIR_OUT = 0.3F, HAIR_PX = 3F, HAIR_WIDTH = 0.4F;
	/** A snipped crop: its filament flicks for this long, its beads fly and shrink for this long (ticks). */
	private static final float SNIP_TICKS = 4F, BEAD_TICKS = 6F, BEAD_PX = 0.6F, BEAD_GRAVITY = 0.04F;
	private static final int MAX = 4, MAX_CROPS = 32, LIFE = 20;
	/** How long the reap holds the sickle in hand (the lash's turn and its pull back). */
	private static final int HAND_TICKS = 8;

	private static final class Reap {
		final int origin, seed;
		final long start;
		final long[] crops;
		/** The aim (degrees) and the lash's direction (+1 right to left), taken once the reaper is known. */
		float yaw = Float.NaN;
		float side = 1F;

		Reap(int origin, long start, int seed, long[] crops) {
			this.origin = origin;
			this.start = start;
			this.seed = seed;
			this.crops = crops;
		}
	}

	private final List<Reap> reaps = new ArrayList<>();

	// render-thread scratch, used inside one geometry lambda at a time
	private static final Stations S = new Stations();
	private static final Static ST = new Static();
	private static final float[] POLY = new float[8];
	private static final float[] RIBBON = {-RIBBON_PX / 2F, 0F, RIBBON_PX / 2F, 0F};
	private static final float[] PTS = new float[3 * STATIONS];
	private static final Loft.FaceLayer ALL_RED = (segment, face) -> ArmLayer.RED;

	public ReapFx() {}

	/** The world being drawn, or null before there is one (and in the unit tests, which have no game). */
	private static @org.jspecify.annotations.Nullable ClientLevel level() {
		Minecraft mc = Minecraft.getInstance();
		return mc == null ? null : mc.level;
	}

	/** How many are showing now: for the tests. */
	int active() {
		return reaps.size();
	}

	@Override
	public int[] kinds() {
		return new int[] {ArmProtocol.FX_REAP};
	}

	@Override
	public void start(ArmFxEvent e) {
		// a long list of crops is thinned to an even sample of at most MAX_CROPS
		long[] all = e.positions();
		int n = Math.min(all.length, MAX_CROPS);
		long[] crops = new long[n];
		for (int i = 0; i < n; i++) crops[i] = all[(int) ((long) i * all.length / Math.max(1, n))];
		Reap r = new Reap(e.origin(), e.receivedTick(), (e.seed() ^ e.origin() * 31) & 0x7FFF, crops);
		ClientLevel level = level();
		Entity origin = level == null ? null : level.getEntity(e.origin());
		if (origin != null) aim(r, origin);
		if (reaps.size() >= MAX) reaps.remove(0);
		reaps.add(r);
		// in hand, the blade winds up and lashes for the turn (ArmReactions has no case for REAP of its own)
		FellFx.reaction(e.origin(), e.receivedTick()).special(ArmProtocol.FX_REAP, e.receivedTick(), HAND_TICKS);
	}

	@Override
	public void tick() {
		long now = Arms.TICK;
		reaps.removeIf(r -> now - r.start > LIFE);
	}

	@Override
	public void clear() {
		reaps.clear();
	}

	/** The reaper's aim and main hand: the lash starts on the main-hand side, as the sweep of Draining Cut does. */
	private static void aim(Reap r, Entity origin) {
		r.yaw = origin.getViewYRot(1F);
		r.side = origin instanceof Player p && p.getMainArm() == HumanoidArm.LEFT ? -1F : 1F;
	}

	// ------------------------------------------------------------------ timing

	/** Where the whip's lead is {@code age} ticks into the reap, in degrees round from the start (0..360), eased. */
	static float lead(float age) {
		return 360F * ArmMath.sm((age - SWEEP_FROM) / SWEEP_TICKS);
	}

	/** How much of its length the whip has out: drawn out over 1.5 ticks, held, drawn back over ticks 9..12. */
	static float extent(float age) {
		return ArmMath.sm(age / OUT_TICKS) * (1F - ArmMath.sm((age - BACK_FROM) / BACK_TICKS));
	}

	/** How far behind its lead the whip's root-side end lags (degrees): full while it swings, closing once the turn is done. */
	static float lag(float age) {
		float end = SWEEP_FROM + SWEEP_TICKS;
		return age <= end ? LAG_DEG : LAG_DEG * Math.max(0F, 1F - (age - end) / 1.5F);
	}

	/** When (ticks into the reap) the lead reaches {@code along} degrees: smoothstep turned round (as SwipeFx's). */
	static float reachesAt(float along) {
		float y = ArmMath.clamp01(along / 360F);
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

	/** Degrees into 0..360. */
	private static float wrap360(float degrees) {
		return degrees - 360F * (float) Math.floor(degrees / 360F);
	}

	// ------------------------------------------------------------------ frame

	@Override
	public void collect(ArmFxFrame f) {
		if (reaps.isEmpty()) return;
		ClientLevel level = level();
		if (level == null) return;
		float pt = f.partialTick();
		for (Reap r : reaps) {
			ArmTint.owner(r.origin); // this effect is drawn in its owner's colour
			Entity origin = level.getEntity(r.origin);
			if (origin == null) continue;
			if (Float.isNaN(r.yaw)) aim(r, origin);
			float age = Arms.age(r.start, pt);
			Vec3 feet = origin.getPosition(pt);
			Vec3 centre = feet.add(0, HEIGHT, 0);
			if (!TendrilFx.finite(centre)) continue;
			int light = TendrilFx.light(level, centre, centre);
			float ext = extent(age);
			float startBearing = r.yaw + r.side * 90F;
			if (ext > 0.02F) {
				Vec3 hand = ArmFx.anchor(r.origin, ArmTips.GRIP);
				float hx = 0F, hy = 0F, hz = 0F;
				if (hand != null && TendrilFx.finite(hand)) {
					// the root is at the drawn hand, in px from the lash's centre
					hx = (float) ((hand.x - centre.x) * 16.0);
					hy = (float) ((hand.y - centre.y) * 16.0);
					hz = (float) ((hand.z - centre.z) * 16.0);
				}
				float lead = lead(age), lag = lag(age), time = f.time(), phase = r.origin * 0.37F + r.seed;
				float rPx = WHIP_BLOCKS * 16F * ext, side = r.side;
				float rx = hx, ry = hy, rz = hz;
				int seed = r.seed;
				TendrilFx.submit(f, centre, ArmLayer.BLACK, light, m -> whip(m, rx, ry, rz, rPx, startBearing, side, lead, lag, time, phase, seed, false));
				TendrilFx.submit(f, centre, ArmLayer.RED, light, m -> whip(m, rx, ry, rz, rPx, startBearing, side, lead, lag, time, phase, seed, true));
				hairs(f, r, centre, startBearing, age, light);
			}
			snips(f, level, r, centre, startBearing, age);
		}
	}

	/**
	 * The whip's points into {@link #PTS}: from the hand ({@code hx, hy, hz}) out to a point at radius {@code rPx}, each
	 * station a little further round than the one before (lagging {@code lag} degrees behind the lead at the end), writhing.
	 */
	private static void points(float hx, float hy, float hz, float rPx, float startBearing, float side, float lead, float lag, float time, float phase) {
		for (int i = 0; i < STATIONS; i++) {
			float u = i / (float) (STATIONS - 1);
			double bearing = startBearing - side * Math.max(0F, lead - lag * u * u);
			float rho = rPx * u;
			float env = (float) Math.sin(ArmMath.PI * Math.min(1F, u * 1.15F));
			float up = 3F * env * (float) Math.sin(phase + 7F * u + 0.35F * time);
			float out = 1.4F * env * (float) Math.cos(0.8F * phase + 5F * u + 0.28F * time);
			float rx = (float) dirX(bearing) * (rho + out), rz = (float) dirZ(bearing) * (rho + out);
			// near the root it blends from the hand onto the circle
			float k = ArmMath.sm(u / 0.35F);
			PTS[3 * i] = ArmMath.lerp(hx, rx, k);
			PTS[3 * i + 1] = ArmMath.lerp(hy, up, k);
			PTS[3 * i + 2] = ArmMath.lerp(hz, rz, k);
		}
	}

	private static void whip(SkinMesh m, float hx, float hy, float hz, float rPx, float startBearing, float side, float lead, float lag, float time, float phase, int seed,
			boolean ribbon) {
		points(hx, hy, hz, rPx, startBearing, side, lead, lag, time, phase);
		Stations s = ribbon ? S.reset(2) : S.reset(4).hint(0F, 1F, 0F);
		float v = 0F;
		for (int i = 0; i < STATIONS; i++) {
			if (i > 0) v += dist(i - 1, i);
			int a = Math.max(0, i - 1), z = Math.min(STATIONS - 1, i + 1);
			float tx = PTS[3 * z] - PTS[3 * a], ty = PTS[3 * z + 1] - PTS[3 * a + 1], tz = PTS[3 * z + 2] - PTS[3 * a + 2];
			float u = i / (float) (STATIONS - 1);
			if (ribbon) {
				// a flat strip standing upright along the whip's lower edge
				s.add(PTS[3 * i], PTS[3 * i + 1] - 0.7F * (1F - u), PTS[3 * i + 2], tx, ty, tz, 0F, 1F, 0F, v, RIBBON, 1F);
			} else {
				float half = 0.5F * ArmMath.lerp(ROOT_PX, END_PX, u);
				ArmMath.ring(POLY, 4, half, half, ArmMath.PI / 4F);
				s.addTransported(PTS[3 * i], PTS[3 * i + 1], PTS[3 * i + 2], tx, ty, tz, v, POLY, 1F);
			}
		}
		ST.set(time, TendrilFx.ACTIVE, 1F, seed + (ribbon ? 1 : 0));
		if (ribbon) Loft.sweep(m, s, ArmLayer.RED, ALL_RED, ST, 6, 2F, false, false);
		else Loft.sweep(m, s, ArmLayer.BLACK, null, ST, 5, 1F, true, true);
	}

	private static float dist(int a, int b) {
		float dx = PTS[3 * b] - PTS[3 * a], dy = PTS[3 * b + 1] - PTS[3 * a + 1], dz = PTS[3 * b + 2] - PTS[3 * a + 2];
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	// ------------------------------------------------------------------ hairs and snips

	/** Hairs shed outward as the lead goes by, drifting 0.3 blocks out and shrinking away over 5 ticks. */
	private static void hairs(ArmFxFrame f, Reap r, Vec3 centre, float startBearing, float age, int light) {
		double[] data = null;
		int count = 0;
		double radius = WHIP_BLOCKS * extent(Math.min(age, BACK_FROM));
		for (int k = 0; k < HAIRS; k++) {
			float along = 360F * (k + 0.5F) / HAIRS;
			float u = (age - reachesAt(along)) / HAIR_TICKS;
			if (u < 0F || u > 1F) continue;
			double bearing = startBearing - r.side * along;
			double ox = dirX(bearing), oz = dirZ(bearing);
			double backX = -r.side * Math.cos(Math.toRadians(bearing)), backZ = -r.side * Math.sin(Math.toRadians(bearing));
			double out = radius + HAIR_OUT * (1 - (1 - u) * (1 - u));
			double lift = (TendrilFx.hash(r.seed, k) - 0.5) * 0.12;
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
		if (count > 0) TendrilFx.filaments(f, data, count, ArmLayer.BLACK, light, r.seed + 2);
	}

	/**
	 * Each reaped crop is snipped as the lash comes round to it (those further than the whip's reach a moment later: the cut
	 * runs on out at a block a tick): a black filament flicks up off the stem for 4 ticks and two red beads burst from it,
	 * flying out and falling, shrinking away over 6.
	 */
	private static void snips(ArmFxFrame f, ClientLevel level, Reap r, Vec3 centre, float startBearing, float age) {
		int n = r.crops.length;
		if (n == 0) return;
		double[] hair = new double[8 * n], bead = new double[4 * 2 * n];
		int hairs = 0, beads = 0;
		for (int i = 0; i < n; i++) {
			long packed = r.crops[i];
			double x = BlockPos.getX(packed) + 0.5, y = BlockPos.getY(packed) + 0.35, z = BlockPos.getZ(packed) + 0.5;
			double dx = x - centre.x, dz = z - centre.z, dist = Math.sqrt(dx * dx + dz * dz);
			float bearing = (float) Math.toDegrees(Math.atan2(-dx, dz));
			float along = wrap360(r.side * (startBearing - bearing));
			float at = reachesAt(along) + (float) (Math.max(0.0, dist - WHIP_BLOCKS) / RIPPLE);
			float u = age - at;
			if (u < 0F || u > BEAD_TICKS) continue;
			if (u < SNIP_TICKS) {
				float g = u / SNIP_TICKS;
				int o = 8 * hairs++;
				hair[o] = x;
				hair[o + 1] = y;
				hair[o + 2] = z;
				hair[o + 3] = (TendrilFx.hash(r.seed, i) - 0.5) * 0.5;
				hair[o + 4] = 1.0;
				hair[o + 5] = (TendrilFx.hash(r.seed, i + 31) - 0.5) * 0.5;
				hair[o + 6] = 4F * (1F - g * g);
				hair[o + 7] = 0.45F * (1F - 0.5F * g);
			}
			for (int j = 0; j < 2; j++) {
				double spin = ArmMath.TAU * TendrilFx.hash(r.seed + 7 * i, j);
				double vx = Math.cos(spin) * 0.05, vz = Math.sin(spin) * 0.05, vy = 0.06 + 0.04 * TendrilFx.hash(r.seed + 7 * i, j + 11);
				int o = 4 * beads++;
				bead[o] = x + vx * u;
				bead[o + 1] = y + vy * u - 0.5 * BEAD_GRAVITY * u * u;
				bead[o + 2] = z + vz * u;
				bead[o + 3] = BEAD_PX * (1F - u / BEAD_TICKS);
			}
		}
		Vec3 at = centre;
		if (hairs > 0) TendrilFx.filaments(f, hair, hairs, ArmLayer.BLACK, TendrilFx.light(level, at, at), r.seed + 4);
		if (beads > 0) TendrilFx.beads(f, bead, beads, ArmLayer.RED, TendrilFx.light(level, at, at), r.seed + 5);
	}
}
