package dev.morphreborn.client.arm.fx;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.model.ArmReactions;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.ArmTint;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Fell (S_ARM_FX FELL): the axe drives a tendril into the foot of the tree it is felling, and the tendril climbs the
 * trunk, two strands (one black, one red) winding round it in opposite turns, with a swollen head that bites the bark
 * with its own roots, sprouting hairs into the wood as it goes. Then the logs come down from the top, 8 a tick as the
 * server breaks them (its event lists them top-down and says how many a tick in {@code arg}), and the tendril is cut
 * shorter with them, its head following the highest log still standing. When the last log has gone the rest lets go and
 * draws back into the axe over 4 ticks.
 * <ul>
 *   <li>The root: a black tendril from the axe's drawn tip to the foot of the trunk, growing over 2 ticks.</li>
 *   <li>The climb: ticks 0 to 2.2, fast, because a small tree is down within two ticks: the helix rising from the foot
 *       to the top of the highest log, 2.4 blocks to a turn, with the frenzy's end-pinned writhe on its radius.</li>
 *   <li>The fall: log {@code i} (top-down) goes at tick {@code 2 + i / arg}; the tendril is cut a tick after its log
 *       goes (it is dragged down with it), so its top is the highest log left a tick ago. A few black beads fly off the
 *       logs that went this tick.</li>
 *   <li>The release: {@link #RELEASE} ticks, back along the path.</li>
 * </ul>
 * It is the same for every viewer: the server's list of logs is the whole of it. A felling of up to 128 logs shows; more
 * than that is cut off (the server's own cap).
 */
public final class FellFx implements FxHandler {
	/** The most logs one fell shows (the server caps at 128), and the most felling effects at once. */
	static final int MAX_LOGS = 128, MAX = 3;
	/** The server's default: logs broken a tick. */
	static final int DEFAULT_RATE = 8;
	/** Ticks: the root grows, the climb takes, a log goes after the server cracks it, the release takes. */
	static final float ROOT_GROW = 1.2F, CLIMB_FROM = 0F, CLIMB_TICKS = 2.2F, RELEASE = 4F;
	/** The first tick logs go (the server's one tick of crack lead and one more for the packet), and how many ticks the tendril trails it. */
	static final int FIRST_BREAK = 2, TRAIL = 1;
	/** Blocks of trunk to a full turn of the helix, and the helix's radius (a little outside the bark). */
	static final float PITCH = 2.4F, HELIX_RADIUS = 0.62F;
	/** Widths in px: the black strand at the foot and the head, the red strand, the root. */
	private static final float BLACK_FOOT = 2.2F, BLACK_HEAD = 1.3F, RED_WIDTH = 1.0F, ROOT_FROM = 2.0F, ROOT_TO = 1.6F, OWN_SCALE = 0.5F;
	/** The head's roots: how many, how long (px), and the hairs left along the climb: how many and how long. */
	private static final int BITERS = 4, MAX_HAIRS = 20;
	private static final float BITE_PX = 3.6F, HAIR_PX = 3.0F, HAIR_EVERY = 1.3F;
	private static final int MAX_STATIONS = 48;
	/** Beads flung off the logs that just went: how many places at most per tick, beads each, their lifetime in ticks. */
	private static final int BEAD_SPOTS = 3, BEADS_EACH = 2;
	private static final float BEAD_TICKS = 5F, BEAD_PX = 0.8F, BEAD_GRAVITY = 0.045F;

	static final class Fell {
		final int origin;
		final long start;
		final int n;
		final int perTick;
		final int seed;
		/** The logs' block positions, top-down, x, y, z each. */
		final int[] xyz;
		/** The foot of the trunk: the lowest log (nearest the felling axe on a tie), as its block's corner. */
		final int footX, footY, footZ;
		/** The highest log's top face, in world y. */
		final double topY;
		/** The tick (into the fell) the server stopped it early at, or -1: the tendril lets go from there instead of after the last log. */
		int stoppedAt = -1;

		Fell(int origin, long start, long[] positions, int arg, int seed) {
			this.origin = origin;
			this.start = start;
			this.seed = seed;
			n = Math.min(positions.length, MAX_LOGS);
			xyz = new int[3 * n];
			for (int i = 0; i < n; i++) {
				xyz[3 * i] = VeinFx.x(positions[i]);
				xyz[3 * i + 1] = VeinFx.y(positions[i]);
				xyz[3 * i + 2] = VeinFx.z(positions[i]);
			}
			perTick = arg > 0 ? Math.min(arg, 64) : DEFAULT_RATE;
			int low = 0, high = 0;
			for (int i = 1; i < n; i++) {
				if (xyz[3 * i + 1] < xyz[3 * low + 1]) low = i;
				if (xyz[3 * i + 1] > xyz[3 * high + 1]) high = i;
			}
			footX = xyz[3 * low];
			footY = xyz[3 * low + 1];
			footZ = xyz[3 * low + 2];
			topY = xyz[3 * high + 1] + 1.0;
		}

		/** Ticks into the fell when the last log has gone and the tendril has been dragged down after it. */
		int endsAt() {
			int natural = FIRST_BREAK + (n + perTick - 1) / perTick + TRAIL;
			return stoppedAt >= 0 ? Math.min(natural, stoppedAt) : natural;
		}

		/** Ticks into the fell when the very last of it (the release) is over. */
		int finish() {
			return endsAt() + (int) RELEASE + 1;
		}
	}

	private final List<Fell> fells = new ArrayList<>();

	private static final Static ST = new Static();
	private static final Vector3f UP_Z = new Vector3f(0F, 0F, 1F);

	public FellFx() {}

	@Override
	public int[] kinds() {
		return new int[] {ArmProtocol.FX_FELL};
	}

	@Override
	public void start(ArmFxEvent e) {
		if (e.positions().length == 0) {
			// the server stopped this felling early (a reshape): the tendril lets go where it is and draws back
			for (Fell f : fells) {
				int age = (int) Math.ceil(Arms.age(f.start, 0F));
				if (f.origin == e.origin() && age < f.endsAt()) f.stoppedAt = Math.max(0, age);
			}
			ArmReactions.Reaction r = ArmReactions.get(e.origin());
			if (r != null) r.endSpecial(ArmProtocol.FX_FELL, e.receivedTick());
			return;
		}
		if (fells.size() >= MAX) fells.remove(0);
		Fell f = new Fell(e.origin(), e.receivedTick(), e.positions(), e.arg(), (e.seed() ^ e.origin() * 31) & 0x7FFF);
		fells.add(f);
		// in hand, the axe rattles and bristles for as long as the felling lasts
		reaction(e.origin(), e.receivedTick()).special(ArmProtocol.FX_FELL, e.receivedTick(), f.endsAt());
	}

	/**
	 * The in-hand reactions of {@code origin}'s arm ({@link ArmReactions}), made if there are none yet: the axe's and the
	 * shovel's specials ride on them like Draining Cut's and Vein Rip's do, so the arm in hand (for every viewer, from the
	 * same effect) shows the special. A Vein Rip's end for an arm that wasn't ripping touches an arm's reactions into being
	 * without showing anything, which is how one is made from here without a way in of its own.
	 */
	static ArmReactions.Reaction reaction(int origin, long now) {
		ArmReactions.Reaction r = ArmReactions.get(origin);
		if (r == null) {
			ArmReactions.onFx(new ArmFxEvent(origin, ArmProtocol.FX_VEIN, 0, 0, 0, new int[0], new long[0], 0, now));
			r = ArmReactions.get(origin);
		}
		return r;
	}

	@Override
	public void tick() {
		long now = Arms.TICK;
		fells.removeIf(f -> now - f.start > f.finish());
	}

	@Override
	public void clear() {
		fells.clear();
	}

	// ------------------------------------------------------------------ timing (pure: unit tested)

	/** How many logs have gone {@code age} ticks into a fell of {@code n} logs at {@code perTick}: none before {@link #FIRST_BREAK}. */
	static int broken(float age, int n, int perTick) {
		if (age < FIRST_BREAK) return 0;
		long gone = (long) perTick * ((long) Math.floor(age) - FIRST_BREAK + 1);
		return (int) Math.min(n, gone);
	}

	/** How far up the trunk the climb has got, 0..1 of the whole height, {@code age} ticks in. */
	static float climbed(float age) {
		return ArmMath.sm((age - CLIMB_FROM) / CLIMB_TICKS);
	}

	/** How much of the whole path (root and helix) is drawn: grown, then drawn back over {@link #RELEASE} after the last log. */
	static float reach(float age, int endsAt) {
		return 1F - ArmMath.sm((age - endsAt) / RELEASE);
	}

	/** The root's own growth, 0..1, over its first 2 ticks. */
	static float rootGrown(float age) {
		return ArmMath.sm(age / ROOT_GROW);
	}

	/** The helix's stations for a climb of {@code height} blocks: a few to a turn, at least 4, at most 48. */
	static int stations(float height) {
		float turns = Math.max(0F, height) / PITCH;
		return Math.clamp((int) Math.ceil(turns * 7F), 4, MAX_STATIONS);
	}

	/** Where the helix's top is (world y) at {@code age}: the climb's height, capped by the highest log left standing. */
	static double headY(Fell f, float age) {
		double climb = f.footY + (f.topY - f.footY) * climbed(age);
		int gone = broken(age - TRAIL, f.n, f.perTick);
		if (gone <= 0) return climb;
		if (gone >= f.n) return f.footY;
		return Math.min(climb, f.xyz[3 * gone + 1] + 1.0);
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void collect(ArmFxFrame fr) {
		if (fells.isEmpty()) return;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		float pt = fr.partialTick();
		for (Fell f : fells) {
			ArmTint.owner(f.origin); // this effect is drawn in its owner's colour
			float age = Arms.age(f.start, pt);
			float reach = reach(age, f.endsAt());
			if (!(reach > 0.002F) || age < 0F) continue;
			Vec3 tip = ArmFx.anchor(f.origin, ArmTips.TIP);
			if (tip == null) continue;
			float own = ArmFx.isOwnFirstPerson(f.origin, fr.firstPersonCamera()) ? OWN_SCALE : 1F;
			// the foot of the trunk: the point of its column on the side facing the axe, a little above the ground
			double cx = f.footX + 0.5, cz = f.footZ + 0.5;
			double dx = tip.x - cx, dz = tip.z - cz, dl = Math.sqrt(dx * dx + dz * dz);
			if (dl < 1e-4) {
				dx = 1;
				dz = 0;
				dl = 1;
			}
			dx /= dl;
			dz /= dl;
			Vec3 foot = new Vec3(cx + dx * 0.52, f.footY + 0.35, cz + dz * 0.52);
			float rootReach = Math.min(reach, rootGrown(age));
			if (rootReach > 0.002F) {
				int light = TendrilFx.light(level, tip, foot);
				TendrilFx.draw(fr, tip, foot, rootReach, f.seed * 0.13F, ROOT_FROM * own, ROOT_TO * own, 0F, 0F, ArmLayer.BLACK, light);
			}
			double base0 = f.footY + 0.35;
			// letting go draws the helix back down to the foot with the root
			double headY = base0 + (headY(f, age) - base0) * reach;
			double span = headY - base0;
			if (span > 0.15 && climbed(age) > 0.02F) helix(fr, level, f, age, cx, cz, Math.atan2(dz, dx), headY, own);
			beads(fr, level, f, age, cx, cz);
		}
	}

	/**
	 * The two strands winding up the trunk, the head with its biting roots, and the hairs it leaves. The helix is one
	 * lofted tube per strand through points worked out here in px from the foot (the pose is moved to the foot, as every
	 * world effect's is), so a log's whole climb is two draws and at most a few small ones.
	 */
	private static void helix(ArmFxFrame fr, ClientLevel level, Fell f, float age, double cx, double cz, double base, double headY, float own) {
		double y0 = f.footY + 0.35;
		float heightBlocks = (float) (headY - y0);
		float top = heightBlocks;
		if (top < 0.1F) return;
		int n = stations(top);
		Vec3 footPoint = new Vec3(cx + Math.cos(base) * HELIX_RADIUS, y0, cz + Math.sin(base) * HELIX_RADIUS);
		Vec3 head = new Vec3(cx + Math.cos(base + ArmMath.TAU * top / PITCH) * HELIX_RADIUS, y0 + top, cz + Math.sin(base + ArmMath.TAU * top / PITCH) * HELIX_RADIUS);
		int light = TendrilFx.light(level, footPoint, head);
		float time = fr.time();
		float phase = f.seed * 0.1F;
		final double fcx = cx - footPoint.x, fcz = cz - footPoint.z;
		final float topF = top, widthBlack = own;
		final int stations = n;
		final float angle0 = (float) base;
		final int seed = f.seed;
		final boolean growing = age < f.endsAt() && climbed(age) < 0.999F;
		for (int strand = 0; strand < 2; strand++) {
			final int k = strand;
			ArmLayer layer = strand == 0 ? ArmLayer.BLACK : ArmLayer.RED;
			TendrilFx.submit(fr, footPoint, layer, light, m -> helixStrand(m, k, stations, topF, angle0, fcx * 16.0, fcz * 16.0, time, phase, seed, widthBlack));
		}
		// the head: a swollen knot with roots biting into the bark
		if (growing || age < f.endsAt()) {
			TendrilFx.knot(fr, head, 2.6F * own, ArmLayer.BLACK, light, f.seed);
			biters(fr, f, head, cx, cz, light, time, own);
		}
		hairs(fr, f, y0, top, base, cx, cz, light, own);
	}

	/**
	 * One strand's geometry: from the foot up, a helix in px about the trunk's axis ({@code ax, az} px from the foot),
	 * its radius breathing with the frenzy's end-pinned writhe, wider at the foot than at the head. Strand 1 (red) winds
	 * half a turn behind and the other way.
	 */
	private static void helixStrand(SkinMesh m, int k, int stations, float topBlocks, float angle0, double axPx, double azPx, float time, float phase, int seed, float scale) {
		float turns = topBlocks / PITCH;
		float dir = k == 0 ? 1F : -1F;
		float shift = k == 0 ? 0F : ArmMath.PI;
		Loft.CurveFn curve = (s, out) -> {
			float y = topBlocks * s * 16F;
			float ang = angle0 + shift + dir * ArmMath.TAU * turns * s;
			float env = (float) Math.sin(Math.PI * s);
			float rad = HELIX_RADIUS * 16F * (1F + 0.05F * env * (float) Math.sin(phase + 7F * s + 0.35F * time));
			float wob = 0.35F * env * (float) Math.cos(0.8F * phase + 5F * s + 0.28F * time);
			out.set((float) (axPx + Math.cos(ang) * (rad + wob)), y, (float) (azPx + Math.sin(ang) * (rad + wob)));
		};
		float footW = (k == 0 ? BLACK_FOOT : RED_WIDTH) * scale, headW = (k == 0 ? BLACK_HEAD : RED_WIDTH * 0.7F) * scale;
		Loft.RadiusFn radius = s -> tubeRadius(ArmMath.lerp(footW, headW, s));
		ST.set(time, TendrilFx.ACTIVE, 1F, seed + k);
		ArmLayer layer = k == 0 ? ArmLayer.BLACK : ArmLayer.RED;
		Loft.tube(m, curve, 0F, 1F, stations, 4, radius, layer, layer, ST, 1 + k, footW < 0.6F ? 2F : 1F, 0F, topBlocks * 16F, UP_Z, 1F);
	}

	/** A 4-sided tube's radius for a face width {@code w} (the arm forms' helper, written out so this class needs no geometry). */
	private static float tubeRadius(float w) {
		return w / (2F * (float) Math.cos(Math.PI / 4.0));
	}

	/** The head's roots: short tendrils from the knot into the trunk, writhing as they bite. */
	private static void biters(ArmFxFrame fr, Fell f, Vec3 head, double cx, double cz, int light, float time, float own) {
		double ix = cx - head.x, iz = cz - head.z, il = Math.sqrt(ix * ix + iz * iz);
		if (il < 1e-4) return;
		ix /= il;
		iz /= il;
		for (int k = 0; k < BITERS; k++) {
			double a = ArmMath.TAU * k / BITERS + f.seed * 0.3 + 0.2 * Math.sin(0.4 * time + k);
			// into the bark (toward the axis) and spread out a little round it
			double sx = -iz * Math.cos(a) * 0.5, sz = ix * Math.cos(a) * 0.5, sy = Math.sin(a) * 0.5;
			Vec3 to = head.add(ix * BITE_PX / 16.0 + sx * BITE_PX / 16.0, sy * BITE_PX / 16.0, iz * BITE_PX / 16.0 + sz * BITE_PX / 16.0);
			TendrilFx.draw(fr, head, to, 1F, f.seed * 0.7F + k * 1.9F, 0.6F * own, 0.2F * own, 0F, 0F, ArmLayer.BLACK, light);
		}
	}

	/** The hairs the head leaves along the trunk, short and pointing into the bark, one every 1.3 blocks of climb. */
	private static void hairs(ArmFxFrame fr, Fell f, double y0, float top, double base, double cx, double cz, int light, float own) {
		int count = Math.min(MAX_HAIRS, (int) (top / HAIR_EVERY));
		if (count <= 0) return;
		double[] data = new double[8 * count];
		for (int i = 0; i < count; i++) {
			float h = (i + 0.5F) * HAIR_EVERY;
			double ang = base + ArmMath.TAU * h / PITCH + (TendrilFx.hash(f.seed, i) - 0.5) * 0.8;
			double ox = Math.cos(ang), oz = Math.sin(ang);
			int o = 8 * i;
			data[o] = cx + ox * HELIX_RADIUS;
			data[o + 1] = y0 + h;
			data[o + 2] = cz + oz * HELIX_RADIUS;
			data[o + 3] = -ox;
			data[o + 4] = (TendrilFx.hash(f.seed, i + 40) - 0.6) * 0.5;
			data[o + 5] = -oz;
			data[o + 6] = HAIR_PX * (0.7F + 0.6F * TendrilFx.hash(f.seed, i + 80));
			data[o + 7] = 0.5F * own;
		}
		TendrilFx.filaments(fr, data, count, ArmLayer.BLACK, light, f.seed + 5);
	}

	/** Beads off the logs that went this tick: a few places, flung outward from the trunk and falling, shrinking away. */
	private static void beads(ArmFxFrame fr, ClientLevel level, Fell f, float age, double cx, double cz) {
		int tick = (int) Math.floor(age);
		for (int back = 0; back < 3; back++) {
			int bt = tick - back;
			if (bt < FIRST_BREAK) break;
			float u = age - bt;
			if (u < 0F || u > BEAD_TICKS) continue;
			int first = (bt - FIRST_BREAK) * f.perTick;
			if (first >= f.n) continue;
			int last = Math.min(f.n, first + f.perTick);
			int spots = Math.min(BEAD_SPOTS, last - first);
			double[] data = new double[4 * spots * BEADS_EACH];
			int c = 0;
			for (int s = 0; s < spots; s++) {
				int idx = first + (spots == 1 ? 0 : s * (last - first - 1) / (spots - 1));
				double x0 = f.xyz[3 * idx] + 0.5, y0 = f.xyz[3 * idx + 1] + 0.5, z0 = f.xyz[3 * idx + 2] + 0.5;
				double ox = x0 - cx, oz = z0 - cz, ol = Math.sqrt(ox * ox + oz * oz);
				if (ol < 1e-3) {
					double a = ArmMath.TAU * TendrilFx.hash(f.seed, idx);
					ox = Math.cos(a);
					oz = Math.sin(a);
				} else {
					ox /= ol;
					oz /= ol;
				}
				for (int j = 0; j < BEADS_EACH; j++) {
					double spin = ArmMath.TAU * TendrilFx.hash(f.seed + idx, j);
					double vx = ox * 0.06 + Math.cos(spin) * 0.04, vz = oz * 0.06 + Math.sin(spin) * 0.04;
					double vy = 0.05 + 0.05 * TendrilFx.hash(f.seed + idx, j + 9);
					data[4 * c] = x0 + vx * u * 2;
					data[4 * c + 1] = y0 + vy * u * 2 - 0.5 * BEAD_GRAVITY * u * u;
					data[4 * c + 2] = z0 + vz * u * 2;
					data[4 * c + 3] = BEAD_PX * (1F - u / BEAD_TICKS);
					c++;
				}
			}
			Vec3 at = new Vec3(data[0], data[1], data[2]);
			TendrilFx.beads(fr, data, c, ArmLayer.BLACK, TendrilFx.light(level, at, at), f.seed + 11 + bt);
		}
	}

	/** The first fell showing, and how many there are: for the tests. */
	Fell firstForTest() {
		return fells.get(0);
	}

	int countForTest() {
		return fells.size();
	}

	/** The whole of a fell's data, as the tests build it from a position list. */
	static Fell of(int origin, long[] positions, int arg) {
		return new Fell(origin, 0L, positions, arg, 0);
	}
}
