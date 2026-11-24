package dev.morphreborn.client.arm.fx;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.ArmTint;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The harpoon shot's cable (S_ARM_FX HARPOON, stateful): a black tendril with a thinner red one twisting round it, from the
 * bow's arrow rest (the point the bow's geometry records) out to wherever the harpoon is, for every viewer and the shooter.
 * The server lists what the harpoon is on and resends it on every change and every second:
 * <ul>
 *   <li>an arrow in flight ({@code ids} = the arrow): the cable pays out behind it, growing from the bow over 3 ticks, with a
 *       quick swelling running out along it every 3 ticks, and a barbed knot on the arrow;</li>
 *   <li>a creature it has hooked ({@code ids} = the creature, which is not a projectile): the cable runs to its wound, taut,
 *       with strong swellings running creature to bow, the pull, every 6 ticks, and a three-clawed knot clamped on it;</li>
 *   <li>a block it has bitten into ({@code positions} = the block): the cable runs to the block's middle, with the swellings
 *       of the pull running home along it every 5 ticks, a knot and four roots biting into the face.</li>
 * </ul>
 * An empty list (no creature or block) ends it, and so does a harpoon the server hasn't mentioned for 3 seconds: the cable
 * lets go and snaps back into the bow over 4 ticks, from where its end was. Widths are 1.5 px at the bow and 0.6 at the end,
 * 0.6 of that in your own first-person view (the bow's point is right by the eye).
 */
public final class HarpoonFx implements FxHandler {
	private static final float GROW_TICKS = 3F, RELEASE_TICKS = 4F;
	private static final float FLIGHT_PERIOD = 3F, PULL_PERIOD = 6F, BLOCK_PERIOD = 5F, FLIGHT_AMP = 0.6F, PULL_AMP = 1F, BLOCK_AMP = 0.8F;
	private static final float BLACK_FROM_PX = 1.5F, BLACK_TO_PX = 0.6F, RED_FROM_PX = 0.55F, RED_TO_PX = 0.35F, OWN_SCALE = 0.6F;
	private static final float KNOT_PX = 1.6F;
	private static final int ROOTS = 4;
	private static final float ROOT_PX = 4F, ROOT_WIDTH = 0.5F;
	private static final int MAX = 8;

	/** What the server says the harpoon is on. */
	enum Mode {
		FLIGHT, CREATURE, BLOCK
	}

	private static final class Harpoon {
		final int origin;
		final long start;
		/** When the server last mentioned this harpoon, and when it let go (-1: still on). */
		long heard;
		long released = -1;
		Mode mode = Mode.FLIGHT;
		int target = -1;
		double bx, by, bz;
		/** Where the end was when it let go, from the bow-end it snapped back to (NaN until drawn after letting go). */
		double endX = Double.NaN, endY, endZ;
		/** When the end last changed what it is on, for the swellings' phase. */
		long since;

		Harpoon(int origin, long start) {
			this.origin = origin;
			this.start = start;
			this.since = start;
		}

		boolean on() {
			return released < 0;
		}
	}

	/** Shooters' entity ids, to their harpoons. */
	private final Map<Integer, Harpoon> harpoons = new HashMap<>();

	public HarpoonFx() {}

	/** The world being drawn, or null before there is one (and in the unit tests, which have no game). */
	private static @org.jspecify.annotations.Nullable ClientLevel level() {
		Minecraft mc = Minecraft.getInstance();
		return mc == null ? null : mc.level;
	}

	/** How many are showing now: for the tests. */
	int active() {
		return harpoons.size();
	}

	@Override
	public int[] kinds() {
		return new int[] {ArmProtocol.FX_HARPOON};
	}

	@Override
	public void start(ArmFxEvent e) {
		long now = e.receivedTick();
		Harpoon h = harpoons.get(e.origin());
		if (e.ends()) {
			if (h != null && h.on()) h.released = now;
			return;
		}
		if (h == null || !h.on()) {
			if (harpoons.size() >= MAX) return;
			harpoons.put(e.origin(), h = new Harpoon(e.origin(), now));
		}
		h.heard = now;
		Mode mode;
		if (e.positions().length > 0) {
			mode = Mode.BLOCK;
			long p = e.positions()[0];
			h.bx = BlockPos.getX(p) + 0.5;
			h.by = BlockPos.getY(p) + 0.5;
			h.bz = BlockPos.getZ(p) + 0.5;
		} else {
			h.target = e.ids().length > 0 ? e.ids()[0] : -1;
			ClientLevel level = level();
			Entity t = level == null || h.target < 0 ? null : level.getEntity(h.target);
			// an entity that isn't a projectile is a creature the harpoon is hooked on; one we haven't seen yet counts as the arrow
			mode = t != null && !(t instanceof Projectile) ? Mode.CREATURE : Mode.FLIGHT;
		}
		if (mode != h.mode) {
			h.mode = mode;
			h.since = now;
		}
	}

	@Override
	public void tick() {
		long now = Arms.TICK;
		for (Iterator<Harpoon> it = harpoons.values().iterator(); it.hasNext(); ) {
			Harpoon h = it.next();
			if (h.on() && now - h.heard > ArmProtocol.STATEFUL_TIMEOUT_TICKS) h.released = now;
			if (!h.on() && now - h.released >= RELEASE_TICKS) it.remove();
		}
	}

	@Override
	public void clear() {
		harpoons.clear();
	}

	// ------------------------------------------------------------------ timing

	/** How far the cable has grown {@code age} ticks after it was paid out: eased out. */
	static float grown(float age) {
		float x = ArmMath.clamp01(age / GROW_TICKS);
		return 1F - (1F - x) * (1F - x);
	}

	/** The swelling's period and strength for a harpoon on {@code mode}. */
	static float period(Mode mode) {
		return switch (mode) {
			case FLIGHT -> FLIGHT_PERIOD;
			case CREATURE -> PULL_PERIOD;
			case BLOCK -> BLOCK_PERIOD;
		};
	}

	static float amplitude(Mode mode) {
		return switch (mode) {
			case FLIGHT -> FLIGHT_AMP;
			case CREATURE -> PULL_AMP;
			case BLOCK -> BLOCK_AMP;
		};
	}

	/**
	 * Where the swelling is along the cable, 0 at the bow to 1 at the end, {@code age} ticks after the harpoon went on its
	 * present footing: out toward the end while it flies, home toward the bow while it pulls.
	 */
	static float swellAt(Mode mode, float age) {
		float u = ArmMath.frac(age / period(mode));
		return mode == Mode.FLIGHT ? u : 1F - u;
	}

	// ------------------------------------------------------------------ frame

	@Override
	public void collect(ArmFxFrame f) {
		if (harpoons.isEmpty()) return;
		ClientLevel level = level();
		if (level == null) return;
		float pt = f.partialTick();
		for (Harpoon h : harpoons.values()) {
			ArmTint.owner(h.origin); // this effect is drawn in its owner's colour
			Vec3 bow = ArmFx.anchor(h.origin, ArmTips.TIP);
			if (bow == null || !TendrilFx.finite(bow)) continue;
			boolean own = ArmFx.isOwnFirstPerson(h.origin, f.firstPersonCamera());
			float scale = own ? OWN_SCALE : 1F;
			Vec3 end = end(h, level, pt, bow);
			if (end == null) continue;
			float reach = grown(Arms.age(h.start, pt));
			if (!h.on()) reach = Math.min(reach, 1F - ArmMath.sm(Arms.age(h.released, pt) / RELEASE_TICKS));
			if (!(reach > 0.001F)) continue;
			int light = TendrilFx.light(level, bow, end);
			float[] swells = h.on() ? new float[] {swellAt(h.mode, Arms.age(h.since, pt)), amplitude(h.mode)} : null;
			float phase = h.origin * 0.37F;
			TendrilFx.draw(f, bow, end, reach, phase, BLACK_FROM_PX * scale, BLACK_TO_PX * scale, swells, ArmLayer.BLACK, light, TendrilFx.ACTIVE);
			// the red filament twists round the black cable on a path of its own, brighter where a swelling passes
			TendrilFx.draw(f, bow, end, reach, phase + 1.9F, RED_FROM_PX * scale, RED_TO_PX * scale, swells, ArmLayer.RED, light, TendrilFx.ACTIVE);
			if (h.on() && reach >= 0.999F) knot(f, h, end, bow, light, scale);
		}
	}

	/**
	 * Where the cable's end is this frame: on the harpoon (the arrow in flight), the creature's wound, the block's middle;
	 * once it has let go, where it was, kept where it was left. Null when nothing is known.
	 */
	private static @Nullable Vec3 end(Harpoon h, ClientLevel level, float pt, Vec3 bow) {
		if (!h.on()) {
			if (Double.isNaN(h.endX)) return null;
			return new Vec3(h.endX, h.endY, h.endZ);
		}
		Vec3 end = switch (h.mode) {
			case BLOCK -> new Vec3(h.bx, h.by, h.bz);
			case CREATURE -> {
				Entity t = level.getEntity(h.target);
				yield t == null || t.isRemoved() ? null : TendrilFx.wound(t, pt);
			}
			case FLIGHT -> {
				Entity t = h.target < 0 ? null : level.getEntity(h.target);
				yield t == null || t.isRemoved() ? null : t.getPosition(pt);
			}
		};
		if (end != null && TendrilFx.finite(end)) {
			h.endX = end.x;
			h.endY = end.y;
			h.endZ = end.z;
		}
		return end != null ? end : Double.isNaN(h.endX) ? null : new Vec3(h.endX, h.endY, h.endZ);
	}

	/** The end's knot: barbed on the arrow, three-clawed on a creature, rooted into a block's face. */
	private static void knot(ArmFxFrame f, Harpoon h, Vec3 end, Vec3 bow, int light, float scale) {
		TendrilFx.knot(f, end, KNOT_PX * scale, ArmLayer.RED, light, h.origin);
		Vec3 line = end.subtract(bow);
		double len = line.length();
		if (len < 1e-3) return;
		Vec3 along = line.scale(1.0 / len);
		if (h.mode == Mode.FLIGHT) return;
		// claws (creature) or roots (block) splay round the line's heading, away from the bow and into what it holds
		int n = h.mode == Mode.CREATURE ? 3 : ROOTS;
		double[] data = new double[8 * n];
		float spin = 0.04F * f.time();
		for (int k = 0; k < n; k++) {
			double a = ArmMath.TAU * k / n + spin + h.origin;
			Vec3 side = along.cross(new Vec3(0, 1, 0));
			side = side.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : side.normalize();
			Vec3 up = side.cross(along).normalize();
			Vec3 off = side.scale(Math.cos(a)).add(up.scale(Math.sin(a)));
			int o = 8 * k;
			data[o] = end.x;
			data[o + 1] = end.y;
			data[o + 2] = end.z;
			double bite = h.mode == Mode.CREATURE ? 0.9 : 0.55;
			data[o + 3] = along.x * bite + off.x * 0.8 - along.x * (h.mode == Mode.CREATURE ? 1.4 : 0.0);
			data[o + 4] = along.y * bite + off.y * 0.8 - along.y * (h.mode == Mode.CREATURE ? 1.4 : 0.0);
			data[o + 5] = along.z * bite + off.z * 0.8 - along.z * (h.mode == Mode.CREATURE ? 1.4 : 0.0);
			data[o + 6] = (h.mode == Mode.CREATURE ? 3.5F : ROOT_PX) * scale;
			data[o + 7] = ROOT_WIDTH * scale;
		}
		TendrilFx.filaments(f, data, n, ArmLayer.BLACK, light, h.origin + 9);
	}
}
