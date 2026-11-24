package dev.morphreborn.client.arm.fx;

import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmState;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.ArmTint;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Vein Rip (S_ARM_FX VEIN): the pick drives a tendril into the ore it is aimed at, and a red network of tendrils runs
 * from ore to ore through the rock, reaching each one on the tick the server breaks it, then draws back once the vein
 * is out.
 * <ul>
 *   <li>The root: a black tendril from the pick's tip to the face of the first ore, growing over ticks 0-3 (2.0 px
 *       thinning to 1.0); from tick 2, four burrowing filaments (0.4 px, half a block) bite into that face. When the
 *       ore breaks the root reaches on into the hole it left.</li>
 *   <li>The branches: a red tendril (1.4 px to 0.8) into each further ore from the one before it in the network's tree,
 *       growing for up to 2 ticks after that one breaks and arriving as its own ore breaks. The tree is rebuilt here
 *       from the ordered list by the same rule as the server's planner (VeinPlanner.parents), so every screen shows
 *       the same network.</li>
 *   <li>Through stone: the branches are drawn into the outline buffer as well (red), for the owner and anyone within
 *       32 blocks who has the "sense outline" setting on, so the vein shows through the rock it runs in.</li>
 *   <li>The end: the branches draw back from the leaves inward, 3 ticks each, then the root snaps back into the pick
 *       (4 ticks). A rip cut short (the arm retracted or melted) draws back from where it got to.</li>
 * </ul>
 * Break times come from {@link ArmProtocol#veinBreakTick} with the event's rate ({@code arg}, ores a tick times 10).
 */
public final class VeinFx implements FxHandler {
	/** The most ores drawn (the server plans at most 32: 31 branches and the root). */
	static final int MAX_BLOCKS = 33;
	/** Veins showing at once (several players ripping together); the oldest goes first. */
	private static final int MAX = 4;
	/** Ticks: the root grows, the burrowers start, a branch grows at most, a leaf draws back, the root snaps back. */
	static final int ROOT_GROW = 3, BURROW_FROM = 2, BRANCH_GROW = 2, LEAF_RETRACT = 3, ROOT_RETRACT = 4;
	private static final float ROOT_FROM_PX = 2.0F, ROOT_TO_PX = 1.0F, BRANCH_FROM_PX = 1.4F, BRANCH_TO_PX = 0.8F;
	private static final int BURROWERS = 4;
	private static final float BURROW_PX = 0.4F, BURROW_LENGTH = 0.5F, BULB = 0.35F;
	private static final double SENSE_RANGE = 32;
	/** The most ticks a vein stays fully grown after its last break before drawing back. */
	private static final int MAX_LINGER = 40;

	static final class Vein {
		final int origin;
		final long start;
		final int n;
		/** The ores' block positions, x, y, z each, in break order. */
		final int[] xyz;
		final int[] parent, height, breakAt;
		/** Ticks into the rip when the drawing back starts: after the last break, or when the rip was cut short. */
		int end;
		final int seed;
		/** Where the root bites the first ore (NaN until first drawn) and that face's normal. */
		double faceX = Double.NaN, faceY, faceZ;
		int normalX, normalY, normalZ;

		Vein(int origin, long start, long[] positions, int rateTenths, int ticks, int seed) {
			this.origin = origin;
			this.start = start;
			this.seed = seed;
			n = Math.min(positions.length, MAX_BLOCKS);
			long[] used = n == positions.length ? positions : Arrays.copyOf(positions, n);
			xyz = new int[3 * n];
			for (int i = 0; i < n; i++) {
				xyz[3 * i] = x(used[i]);
				xyz[3 * i + 1] = y(used[i]);
				xyz[3 * i + 2] = z(used[i]);
			}
			parent = parents(used);
			height = heights(parent);
			breakAt = new int[n];
			for (int i = 0; i < n; i++) breakAt[i] = ArmProtocol.veinBreakTick(i, rateTenths);
			// the rip's length as the server gave it (its last break), never before the last break worked out here and
			// never more than a couple of seconds after it, so a garbled number can't leave a vein up for good
			int last = n == 0 ? 0 : breakAt[n - 1];
			end = Math.clamp(ticks, last, last + MAX_LINGER);
		}

		/** Ticks into the rip when the last of it (the root) has drawn back. */
		int finish() {
			int tallest = 0;
			for (int h : height) tallest = Math.max(tallest, h);
			return end + LEAF_RETRACT * tallest + ROOT_RETRACT;
		}

		Vec3 centre(int i) {
			return new Vec3(xyz[3 * i] + 0.5, xyz[3 * i + 1] + 0.5, xyz[3 * i + 2] + 0.5);
		}
	}

	private final List<Vein> veins = new ArrayList<>();

	public VeinFx() {}

	@Override
	public int[] kinds() {
		return new int[] {ArmProtocol.FX_VEIN};
	}

	@Override
	public void start(ArmFxEvent e) {
		if (e.positions().length == 0) {
			// the server stopped this ripper's rip (a reshape, say): draw its veins back from where they got to
			long now = Arms.TICK;
			for (Vein v : veins) {
				int age = (int) Math.min(Integer.MAX_VALUE, now - v.start);
				if (v.origin == e.origin() && age < v.end) v.end = Math.max(1, age);
			}
			return;
		}
		if (veins.size() >= MAX) veins.remove(0);
		veins.add(new Vein(e.origin(), e.receivedTick(), e.positions(), e.arg(), e.ticks(), (e.seed() ^ e.origin() * 31) & 0x7FFF));
	}

	@Override
	public void tick() {
		long now = Arms.TICK;
		veins.removeIf(v -> {
			int age = (int) Math.min(Integer.MAX_VALUE, now - v.start);
			if (age >= 1 && age < v.end && !armed(v.origin)) v.end = age; // the rip was cut short: draw back from here
			return age > v.finish();
		});
	}

	@Override
	public void clear() {
		veins.clear();
	}

	/** Whether the ripper's arm is still out and usable (its S_ARM says so); a rip can't go on without it. */
	private static boolean armed(int origin) {
		ArmState state = Arms.stateByEntity(origin);
		return state != null && ArmProtocol.usable(state.state);
	}

	@Override
	public void collect(ArmFxFrame f) {
		if (veins.isEmpty()) return;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		float pt = f.partialTick();
		boolean senseOn = ClientConfig.INSTANCE.armSenseOutline;
		for (Vein v : veins) {
			ArmTint.owner(v.origin); // this effect is drawn in its owner's colour
			if (v.n == 0) continue;
			float age = Arms.age(v.start, pt);
			Vec3 first = v.centre(0);
			boolean sense = senseOn && first.distanceToSqr(f.camPos()) <= SENSE_RANGE * SENSE_RANGE;
			for (int i = 1; i < v.n; i++) {
				int p = v.parent[i];
				if (p < 0) continue; // touches no earlier ore (the server never plans one): no tendril to grow from
				float reach = branchReach(age, v.breakAt, v.parent, v.height, v.end, i);
				if (!(reach > 0.001F)) continue;
				Vec3 from = v.centre(p), to = v.centre(i);
				float[] bulb = age < v.breakAt[i] ? new float[] {reach, BULB} : null;
				float phase = i * 1.7F + v.seed * 0.01F;
				TendrilFx.draw(f, from, to, reach, phase, BRANCH_FROM_PX, BRANCH_TO_PX, bulb, ArmLayer.RED, TendrilFx.light(level, from, to), TendrilFx.ACTIVE);
				if (sense) TendrilFx.draw(f, from, to, reach, phase, BRANCH_FROM_PX, BRANCH_TO_PX, bulb, ArmLayer.SENSE, 0, TendrilFx.ACTIVE);
			}
			root(f, level, v, age, first);
		}
	}

	/** The root from the pick to the first ore, and the filaments that bite into its face. */
	private static void root(ArmFxFrame f, ClientLevel level, Vein v, float age, Vec3 first) {
		Vec3 tip = ArmFx.anchor(v.origin, ArmTips.TIP);
		if (tip == null) return; // the ripper isn't in this world any more
		if (Double.isNaN(v.faceX)) {
			Entity owner = level.getEntity(v.origin);
			face(v, owner == null ? tip : owner.getEyePosition(f.partialTick()));
		}
		Vec3 face = new Vec3(v.faceX, v.faceY, v.faceZ);
		Vec3 front = face.add(v.normalX * 0.3, v.normalY * 0.3, v.normalZ * 0.3); // the air the face looks into
		int light = TendrilFx.light(level, tip, front);
		float reach = rootReach(age, v.height[0], v.end);
		if (reach > 0.001F) {
			// once the first ore is out, the root reaches on into its hole, where the branches leave from
			Vec3 to = face.lerp(first, ArmMath.clamp01(age - v.breakAt[0]));
			float[] bulb = age < ROOT_GROW ? new float[] {reach, BULB} : null;
			TendrilFx.draw(f, tip, to, reach, v.origin * 0.37F, ROOT_FROM_PX, ROOT_TO_PX, bulb, ArmLayer.BLACK, light, TendrilFx.ACTIVE);
		}
		float bite = ArmMath.sm(age - BURROW_FROM) * (1F - ArmMath.sm(age - v.breakAt[0]));
		if (!(bite > 0.001F)) return;
		// They creep out across the face from the root's end and dig their tips in: drawn straight into the ore they
		// would be hidden by it at once, so they run along the surface and only their ends go under.
		Vec3 normal = new Vec3(v.normalX, v.normalY, v.normalZ);
		Vec3 u = v.normalY != 0 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
		Vec3 w = normal.cross(u);
		Vec3 from = face.add(normal.scale(0.03));
		for (int k = 0; k < BURROWERS; k++) {
			double angle = ArmMath.PI / 4 + k * ArmMath.PI / 2 + v.seed * 0.1;
			Vec3 along = u.scale(Math.cos(angle)).add(w.scale(Math.sin(angle)));
			Vec3 to = face.add(along.scale(BURROW_LENGTH * bite)).add(normal.scale(-0.1 * bite));
			TendrilFx.draw(f, from, to, 1F, v.seed + k * 2.3F, BURROW_PX, BURROW_PX * 0.4F, 0F, 0F, ArmLayer.BLACK, light);
		}
	}

	/**
	 * Where the root bites the first ore: the point of its block nearest the ripper's eye (on the face toward them, kept
	 * a little in from the face's edges), and that face's outward normal.
	 */
	private static void face(Vein v, Vec3 eye) {
		double minX = v.xyz[0], minY = v.xyz[1], minZ = v.xyz[2];
		double dx = eye.x - ArmMath.clamp((float) (eye.x - minX), 0F, 1F) - minX;
		double dy = eye.y - ArmMath.clamp((float) (eye.y - minY), 0F, 1F) - minY;
		double dz = eye.z - ArmMath.clamp((float) (eye.z - minZ), 0F, 1F) - minZ;
		double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
		v.normalX = v.normalY = v.normalZ = 0;
		if (ax >= ay && ax >= az && ax > 0) v.normalX = dx > 0 ? 1 : -1;
		else if (ay >= az && ay > 0) v.normalY = dy > 0 ? 1 : -1;
		else if (az > 0) v.normalZ = dz > 0 ? 1 : -1;
		else v.normalY = 1; // the eye inside the block: say the top
		v.faceX = v.normalX != 0 ? minX + (v.normalX > 0 ? 1 : 0) : minX + inset(eye.x - minX);
		v.faceY = v.normalY != 0 ? minY + (v.normalY > 0 ? 1 : 0) : minY + inset(eye.y - minY);
		v.faceZ = v.normalZ != 0 ? minZ + (v.normalZ > 0 ? 1 : 0) : minZ + inset(eye.z - minZ);
	}

	/** A coordinate along a face, kept 0.2 in from its edges so the root doesn't bite a corner. */
	private static double inset(double along) {
		return Math.clamp(along, 0.2, 0.8);
	}

	// ------------------------------------------------------------------ the network (pure: unit tested)

	/**
	 * The network's tree, by the server's rule (VeinPlanner.parents, part of the protocol): for each ore, the index of
	 * the ore its tendril grows from, -1 for the first. It is the nearest earlier ore among the 26 around it (sharing a
	 * face before an edge before a corner), the earliest of equally near ones; -1 when no earlier ore touches it.
	 */
	public static int[] parents(long[] positions) {
		int n = positions.length;
		int[] out = new int[n];
		for (int i = 0; i < n; i++) {
			int px = x(positions[i]), py = y(positions[i]), pz = z(positions[i]);
			int best = -1, bestDistance = Integer.MAX_VALUE;
			for (int j = 0; j < i; j++) {
				int dx = x(positions[j]) - px, dy = y(positions[j]) - py, dz = z(positions[j]) - pz;
				if (Math.abs(dx) > 1 || Math.abs(dy) > 1 || Math.abs(dz) > 1 || (dx == 0 && dy == 0 && dz == 0)) continue;
				int d = dx * dx + dy * dy + dz * dz;
				if (d < bestDistance) {
					best = j;
					bestDistance = d;
				}
			}
			out[i] = best;
		}
		return out;
	}

	/**
	 * Each ore's height in the tree: 0 for a leaf, one more than its tallest child otherwise. The drawing back runs from
	 * the leaves inward by it. A parent always comes before its children, so one pass from the end settles every height.
	 */
	public static int[] heights(int[] parents) {
		int[] height = new int[parents.length];
		for (int i = parents.length - 1; i >= 0; i--) {
			int p = parents[i];
			if (p >= 0 && p < i) height[p] = Math.max(height[p], height[i] + 1);
		}
		return height;
	}

	/**
	 * When the branch into ore {@code i} starts growing (ticks into the rip): when the ore it grows from breaks, but no
	 * earlier than {@link #BRANCH_GROW} ticks before it must arrive, and at least a tick before.
	 */
	static int branchStart(int[] breakAt, int[] parents, int i) {
		int arrive = breakAt[i];
		int p = parents[i];
		int from = p < 0 ? arrive - BRANCH_GROW : Math.max(breakAt[p], arrive - BRANCH_GROW);
		return Math.min(from, arrive - 1);
	}

	/**
	 * How far the branch into ore {@code i} reaches {@code age} ticks into the rip: growing from {@link #branchStart} to
	 * its ore's break, all of it once there, drawing back over {@link #LEAF_RETRACT} ticks from {@code end} plus 3 ticks
	 * per level of the tree below it. Growth stops at {@code end}: a rip cut short draws back from where it got to, and a
	 * branch that hadn't started by then never sprouts.
	 */
	static float branchReach(float age, int[] breakAt, int[] parents, int[] heights, int end, int i) {
		int from = branchStart(breakAt, parents, i), arrive = breakAt[i];
		float grow = ArmMath.sm((Math.min(age, end) - from) / (arrive - from));
		float back = 1F - ArmMath.sm((age - end - LEAF_RETRACT * heights[i]) / LEAF_RETRACT);
		return Math.min(grow, back);
	}

	/** The root's reach: grown over ticks 0-3 (as far as it got by {@code end}), snapping back once every branch has drawn back. */
	static float rootReach(float age, int rootHeight, int end) {
		float grow = ArmMath.sm(Math.min(age, end) / ROOT_GROW);
		float back = 1F - ArmMath.sm((age - end - LEAF_RETRACT * rootHeight) / ROOT_RETRACT);
		return Math.min(grow, back);
	}

	// BlockPos.asLong's packing (26 bits of x, 26 of z, 12 of y), unpacked here so the tree needs no game classes.
	private static final int HORIZONTAL_BITS = 26, Y_BITS = 12, Z_SHIFT = Y_BITS, X_SHIFT = Y_BITS + HORIZONTAL_BITS;

	static int x(long packed) {
		return (int) (packed << (64 - X_SHIFT - HORIZONTAL_BITS) >> (64 - HORIZONTAL_BITS));
	}

	static int y(long packed) {
		return (int) (packed << (64 - Y_BITS) >> (64 - Y_BITS));
	}

	static int z(long packed) {
		return (int) (packed << (64 - Z_SHIFT - HORIZONTAL_BITS) >> (64 - HORIZONTAL_BITS));
	}
}
