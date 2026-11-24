package dev.morphreborn.client.arm.fx;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.geom.FistGeometry;
import dev.morphreborn.client.arm.geom.WhipGeometry;
import dev.morphreborn.client.arm.model.ArmDrawFactory;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.ArmTint;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import dev.morphreborn.client.arm.render.Stations;
import dev.morphreborn.client.arm.render.TendrilPath;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The whip's moves (S_ARM_FX kind 14, FX_WHIP_MOVE; decided 2026-11-16): the left-click lash, and the four
 * specials picked by what the whip is aimed at. {@code arg} is the move ({@code ArmProtocol.WHIP_*}), {@code level} the
 * tier, {@code ticks} how long it shows, {@code ids} and {@code positions} per move (see {@link ArmProtocol#FX_WHIP_MOVE}).
 * Every one is the whip's own braided tendril, black with red strands twisting round it (none, one, two by tier) and the
 * morph skin's static fizzing along it, drawn from the whip's tip (the arm renderer's recorded point, so the owner's own
 * first-person view and everyone else's third-person one start in the same place) in its owner's colour.
 * <ul>
 * <li><b>STRIKE</b> (5): a lash from the tip to the creature hit and back, a wave running down it, and a small crack at the end.
 * <li><b>SNARE</b> (the hold): the lash shoots out and goes round the creature in a few loops from its shoulders to its knees,
 * tightening for as long as the hold lasts, then comes off the way it went on.
 * <li><b>DISARM</b> (6): the lash snaps to the creature's weapon hand, bites (a knot and a spray of hairs), and whips back toward
 * the whip with a flick running down it. The item itself flies by the game's own physics.
 * <li><b>CRACK</b> (6): the lash straight out to the far point with a shock ring and hairs at the tip, a red flash along it, and
 * a cone of dark air-distortion streaks (35 degrees half-angle, 6/7/8 blocks) fanning out ahead of the whip.
 * <li><b>WHIRL</b> (8): the lash spinning in a full circle about the owner at waist height, a red band trailing its end.
 * </ul>
 * The hand's own lash coils away while one of these is out ({@link WhipGeometry#markHold}). Moves from servers that predate
 * them never come: the old lash ({@code FX_WHIP}) is {@link WhipFx}'s and is still drawn.
 * <p>
 * Allocation: every frame's pieces are pooled ({@link Path}, {@link Hairs}) and reused; the geometry lambdas of the other
 * effects are replaced by these objects, which hold the frame's numbers until it is dispatched. A frame's collect resets the
 * pools, so nothing here survives into the next.
 */
public final class WhipMoveFx implements FxHandler {
	private static final float PX = 16F;
	/** Ticks the lash takes to go out, per move; and the unravel of a snare. */
	static final float STRIKE_OUT = 1.3F, DISARM_OUT = 1.5F, CRACK_OUT = 1.2F, SNARE_OUT = 3F, SNARE_UNRAVEL = 5F, WHIRL_EDGE = 1.5F;
	/** The crack's cone: reach in blocks by tier (iron, diamond, netherite), and its half-angle. */
	static final float[] CONE = {0F, 6F, 7F, 8F};
	static final float CONE_HALF_ANGLE = (float) Math.toRadians(35);
	/** The whirl's reach in blocks by tier: the lash is drawn out to it. */
	static final float[] WHIRL_RADIUS = {0F, 4F, 4.5F, 5F};
	/** The most moves drawn at once, creatures a crack or whirl shows hits on, hairs one spray holds, and streaks in the crack's cone. */
	private static final int MAX_MOVES = 10, MAX_HITS = 8, MAX_HAIRS = 24, STREAKS = 14;
	/** The widths of the lash at the whip and at the end (px), as the old lash's. */
	private static final float LASH_ROOT = 1.6F, LASH_TIP = 0.5F;

	private static final class Move {
		final int origin, tier, move, seed, ticks, target;
		final int[] hits;
		/** CRACK's far point (BlockPos long), or {@link #NO_FAR}. */
		final long far;
		final long start;

		Move(int origin, int tier, int move, int seed, int ticks, int target, int[] hits, long far, long start) {
			this.origin = origin;
			this.tier = tier;
			this.move = move;
			this.seed = seed;
			this.ticks = ticks;
			this.target = target;
			this.hits = hits;
			this.far = far;
			this.start = start;
		}
	}

	private static final long NO_FAR = Long.MIN_VALUE;
	private final List<Move> moves = new ArrayList<>();

	private static final Static ST = new Static();
	private static final Stations RIB = new Stations();
	private static final float[] POLY = new float[4];
	private static final Quaternionf TURN = new Quaternionf();
	private static final Matrix4f LOCAL = new Matrix4f();
	private static final Vector3f V = new Vector3f();
	private static final Vector3f UP_Y = new Vector3f(0F, 1F, 0F);
	private static final Loft.FaceLayer RED_FACE = (seg, face) -> ArmLayer.RED;

	private static final List<Path> PATHS = new ArrayList<>();
	private static final List<Hairs> HAIRS = new ArrayList<>();
	private static int pathsUsed, hairsUsed;

	public WhipMoveFx() {}

	@Override
	public int[] kinds() {
		return new int[] {ArmProtocol.FX_WHIP_MOVE};
	}

	// ------------------------------------------------------------------ timelines (pure, tested)

	/** How many ticks {@code move} shows for, given the {@code ticks} the server named: bounded so a bad packet can't pin a lash up. */
	static int life(int move, int ticks) {
		return switch (move) {
			case ArmProtocol.WHIP_STRIKE -> Math.clamp(ticks, 3, 10);
			case ArmProtocol.WHIP_SNARE -> (int) SNARE_OUT + Math.clamp(ticks, 4, 100) + (int) SNARE_UNRAVEL;
			case ArmProtocol.WHIP_DISARM -> Math.clamp(ticks, 4, 10);
			case ArmProtocol.WHIP_CRACK -> Math.clamp(ticks, 4, 12);
			case ArmProtocol.WHIP_WHIRL -> Math.clamp(ticks, 4, 20);
			default -> 0;
		};
	}

	/** A lash that goes out fast (eased) over {@code out} ticks and draws back by {@code life}: its reach 0..1. */
	static float outBack(float age, float out, float life) {
		if (age <= 0F) return 0F;
		if (age < out) {
			float x = 1F - age / out;
			return 1F - x * x;
		}
		float back = life - out;
		return back <= 0F ? 0F : 1F - ArmMath.sm((age - out) / back);
	}

	/** A lash that goes out over {@code out} ticks, holds, and draws back over the last {@code back} of {@code life}. */
	static float outHoldBack(float age, float out, float back, float life) {
		if (age <= 0F) return 0F;
		if (age < out) {
			float x = 1F - age / out;
			return 1F - x * x;
		}
		return age < life - back ? 1F : 1F - ArmMath.sm((age - (life - back)) / back);
	}

	/** The wave's size: full while the lash goes out, then dying away. */
	static float decay(float age, float out) {
		return age <= out ? 1F : (float) Math.exp(-0.5F * (age - out));
	}

	/** The snare's reach: out in 3 ticks (shooting and coiling at once), held for the hold, unravelled over 5. */
	static float snareReach(float age, int hold) {
		if (age <= 0F) return 0F;
		if (age < SNARE_OUT) {
			float x = 1F - age / SNARE_OUT;
			return 1F - x * x;
		}
		float off = age - SNARE_OUT - hold;
		return off <= 0F ? 1F : 1F - ArmMath.sm(off / SNARE_UNRAVEL);
	}

	/** How much the snare has squeezed its loops, 0..1: tightening over the first 10 ticks of the hold, easing off as it unravels. */
	static float snareSqueeze(float age, int hold) {
		float tight = ArmMath.sm((age - 1.5F) / 10F);
		float off = age - SNARE_OUT - hold;
		return off <= 0F ? tight : tight * (1F - ArmMath.sm(off / SNARE_UNRAVEL));
	}

	/** The whirl's head, as a fraction of its spin, {@code age} ticks into {@code life}: fast off the mark, easing over. */
	static float whirlSpin(float age, float life) {
		float u = ArmMath.clamp01(age / life);
		float x = 1F - u;
		return 1F - x * (float) Math.sqrt(x);
	}

	/** A streak of the crack's cone: how far out (0..1 of its distance) it is {@code age} ticks in; it starts a quarter of the way out. */
	static float streakProgress(float age) {
		float u = ArmMath.clamp01((age - 0.6F) / 4.5F);
		float x = 1F - u;
		return 0.25F + 0.75F * (1F - x * x);
	}

	// ------------------------------------------------------------------ the effect's life

	@Override
	public void start(ArmFxEvent e) {
		int move = e.arg();
		int life = life(move, e.ticks());
		if (life <= 0) return;
		int tier = Math.clamp(e.level(), 1, 3);
		boolean one = move == ArmProtocol.WHIP_STRIKE || move == ArmProtocol.WHIP_SNARE || move == ArmProtocol.WHIP_DISARM;
		int target = one && e.ids().length > 0 ? e.ids()[0] : -1;
		if (one && target < 0) return; // nothing to draw it at
		int[] hits = new int[0];
		if (!one) {
			int n = Math.min(MAX_HITS, e.ids().length);
			hits = new int[n];
			System.arraycopy(e.ids(), 0, hits, 0, n);
		}
		long far = move == ArmProtocol.WHIP_CRACK && e.positions().length > 0 ? e.positions()[0] : NO_FAR;
		// a snare on the same creature from the same whip is renewed, not stacked
		if (move == ArmProtocol.WHIP_SNARE) moves.removeIf(m -> m.move == ArmProtocol.WHIP_SNARE && m.origin == e.origin() && m.target == target);
		WhipGeometry.markHold(e.origin(), (float) (e.receivedTick() % ArmDrawFactory.CLOCK_WRAP), life);
		if (moves.size() >= MAX_MOVES) moves.remove(0);
		moves.add(new Move(e.origin(), tier, move, (e.seed() ^ e.origin() * 31) & 0x7FFF, e.ticks(), target, hits, far, e.receivedTick()));
	}

	@Override
	public void tick() {
		// no sounds here: the server plays every move's sound to everyone, so a local copy would be heard twice
		long now = Arms.TICK;
		moves.removeIf(m -> now - m.start > life(m.move, m.ticks) + 1);
	}

	@Override
	public void clear() {
		moves.clear();
		pathsUsed = 0;
		hairsUsed = 0;
	}

	/** The crack's far point: the block the server named, else along the owner's look as far as the cone reaches. */
	private static @Nullable Vec3 crackFar(ClientLevel level, Move m) {
		if (m.far != NO_FAR) return Vec3.atCenterOf(BlockPos.of(m.far));
		if (level.getEntity(m.origin) instanceof Entity o) return o.getEyePosition().add(o.getLookAngle().scale(CONE[m.tier]));
		return null;
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void collect(ArmFxFrame f) {
		pathsUsed = 0;
		hairsUsed = 0;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || moves.isEmpty()) return;
		for (Move m : moves) {
			Vec3 from = ArmFx.anchor(m.origin, ArmTips.TIP);
			if (from == null || !TendrilFx.finite(from)) continue;
			float age = Arms.age(m.start, f.partialTick());
			int life = life(m.move, m.ticks);
			if (age < 0F || age > life) continue;
			ArmTint.owner(m.origin); // each is drawn in its owner's colour
			switch (m.move) {
				case ArmProtocol.WHIP_STRIKE -> strike(f, level, m, from, age, life);
				case ArmProtocol.WHIP_SNARE -> snare(f, level, m, from, age);
				case ArmProtocol.WHIP_DISARM -> disarm(f, level, m, from, age, life);
				case ArmProtocol.WHIP_CRACK -> crack(f, level, m, from, age, life);
				case ArmProtocol.WHIP_WHIRL -> whirl(f, level, m, from, age, life);
				default -> {}
			}
		}
	}

	private static Path path() {
		if (pathsUsed == PATHS.size()) PATHS.add(new Path());
		return PATHS.get(pathsUsed++);
	}

	private static Hairs hairs() {
		if (hairsUsed == HAIRS.size()) HAIRS.add(new Hairs());
		return HAIRS.get(hairsUsed++);
	}

	/** The strands of a lash of {@code tier}: none for iron, one red strand for diamond, two for netherite. */
	private static int strands(int tier) {
		return Math.max(0, tier - 1);
	}

	// ------------------------------------------------------------------ STRIKE

	private void strike(ArmFxFrame f, ClientLevel level, Move m, Vec3 from, float age, int life) {
		if (!(level.getEntity(m.target) instanceof Entity t) || t.isRemoved()) return;
		Vec3 to = TendrilFx.wound(t, f.partialTick());
		if (!TendrilFx.finite(to)) return;
		float reach = outBack(age, STRIKE_OUT, life);
		line(f, level, m, from, to, reach, age, 0.22F * decay(age, STRIKE_OUT));
		hairBurst(f, level, m, from, to, age - STRIKE_OUT, 3F, 5, 0);
	}

	// ------------------------------------------------------------------ DISARM

	/** The hand a creature holds its weapon in, in the world: out to its side and forward of its body, at chest height. */
	static Vec3 handOf(Entity e, float pt) {
		if (!(e instanceof LivingEntity living)) return TendrilFx.wound(e, pt);
		double yaw = Math.toRadians(Mth.rotLerp(pt, living.yBodyRotO, living.yBodyRot));
		double side = living.getMainArm() == HumanoidArm.LEFT ? -1 : 1;
		double w = Math.max(0.25, e.getBbWidth());
		// the creature's right is (-cos, -sin); its front (-sin, cos)
		double rx = -Math.cos(yaw), rz = -Math.sin(yaw), fx = -Math.sin(yaw), fz = Math.cos(yaw);
		return e.getPosition(pt).add(rx * 0.55 * w * side + fx * 0.4 * w, e.getBbHeight() * 0.6, rz * 0.55 * w * side + fz * 0.4 * w);
	}

	private void disarm(ArmFxFrame f, ClientLevel level, Move m, Vec3 from, float age, int life) {
		if (!(level.getEntity(m.target) instanceof Entity t) || t.isRemoved()) return;
		Vec3 hand = handOf(t, f.partialTick());
		if (!TendrilFx.finite(hand)) return;
		// out to the hand, then the flick: its end comes four fifths of the way back, with a wave thrown down the lash
		float back = ArmMath.sm((age - DISARM_OUT) / Math.max(0.5F, life - DISARM_OUT));
		Vec3 to = age <= DISARM_OUT ? hand : hand.add(from.subtract(hand).scale(0.8 * back));
		float reach = outHoldBack(age, DISARM_OUT, 1.5F, life);
		float flick = age <= DISARM_OUT ? 0F : (float) Math.sin(Math.PI * ArmMath.clamp01((age - DISARM_OUT) / Math.max(0.5F, life - DISARM_OUT)));
		float amp = 0.15F * decay(age, DISARM_OUT) + 0.38F * flick;
		line(f, level, m, from, to, reach, age, amp);
		float bite = age - DISARM_OUT;
		hairBurst(f, level, m, from, hand, bite, 3.5F, 6, 1);
		if (bite >= 0F && bite < 2F) TendrilFx.knot(f, hand, 3F * (1F - bite / 2F), ArmLayer.RED, TendrilFx.light(level, from, hand), m.seed);
	}

	// ------------------------------------------------------------------ CRACK

	private void crack(ArmFxFrame f, ClientLevel level, Move m, Vec3 from, float age, int life) {
		Vec3 far = crackFar(level, m);
		if (far == null || !TendrilFx.finite(far)) return;
		Vec3 line = far.subtract(from);
		double len = line.length();
		if (len < 0.5) return;
		float reach = outHoldBack(age, CRACK_OUT, 2.5F, life);
		int light = TendrilFx.light(level, from, far);
		Path black = line(f, level, m, from, far, reach, age, 0.3F * decay(age, CRACK_OUT));
		Vec3 axis = line.scale(1 / len);
		// the flash along the lash, as the old lash's: a red ribbon standing across the line, widest at the crack
		float flash = (age - CRACK_OUT) / 3F;
		if (black != null && flash >= 0F && flash < 1F && reach > 0.5F) {
			Path r = path().copy(black);
			r.type = Path.RIBBON;
			r.layer = ArmLayer.RED;
			r.part = 74;
			r.uvScale = 2F;
			r.wide = (1F - flash) * 1.6F;
			r.taper = false;
			r.r1 = Math.min(1F, reach * 1.05F);
			r.stations = Math.clamp((int) Math.ceil(r.lenPx / 8F), 3, 24);
			TendrilFx.submit(f, from, ArmLayer.RED, light, r);
		}
		// the shock ring at the far point, standing across the look and spreading
		float ring = (age - CRACK_OUT) / 4F;
		if (ring >= 0F && ring < 1F) {
			Path r = path();
			r.reset(Path.RING, ArmLayer.RED, f.time(), m.seed);
			float x = 1F - ring;
			r.hr = (0.15F + 1.6F * (1F - x * x)) * PX;
			r.rootW = 1.3F * x + 0.25F;
			r.frame(axis);
			r.axisX = (float) axis.x;
			r.axisY = (float) axis.y;
			r.axisZ = (float) axis.z;
			r.stations = 20;
			r.part = 75;
			r.uvScale = 2F;
			TendrilFx.submit(f, far, ArmLayer.RED, light, r);
		}
		hairBurst(f, level, m, from, far, age - CRACK_OUT, 4F, 6, 2);
		cone(f, m, from, axis, (float) len, age, light);
		for (int i = 0; i < m.hits.length; i++) {
			if (level.getEntity(m.hits[i]) instanceof Entity t && !t.isRemoved()) {
				hairBurst(f, level, m, from, TendrilFx.wound(t, f.partialTick()), age - CRACK_OUT - 0.3F * i, 3F, 4, 10 + i);
			}
		}
	}

	/**
	 * The cone of air-distortion streaks (35 degrees half-angle, 6/7/8 blocks by tier): dark hairs lying along their own
	 * direction out of the whip, each starting a quarter of the way to its distance and running out to it, thinning as they go.
	 * {@code axis} points at the far point the server named (clipped to the first block along the owner's look) and the streaks
	 * stop at {@code limit}, the distance to it, root and tip alike, so none are drawn through the wall; the tier's reach is only the upper bound.
	 */
	private void cone(ArmFxFrame f, Move m, Vec3 from, Vec3 axis, float limit, float age, int light) {
		float u = (age - 0.6F) / 4.5F;
		if (u < 0F || u > 1F) return;
		float p = streakProgress(age);
		float range = Math.min(CONE[m.tier], limit);
		// two directions square to the axis
		Vec3 helper = Math.abs(axis.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
		Vec3 pa = axis.cross(helper).normalize();
		Vec3 qa = axis.cross(pa);
		Hairs h = hairs();
		h.begin(f.time(), m.seed + 6);
		for (int i = 0; i < STREAKS; i++) {
			float psi = ArmMath.TAU * TendrilFx.hash(m.seed, i);
			float alpha = CONE_HALF_ANGLE * (float) Math.sqrt(TendrilFx.hash(m.seed + 1, i));
			float dist = range * (0.4F + 0.6F * TendrilFx.hash(m.seed + 2, i)) * p;
			float sa = (float) Math.sin(alpha), ca = (float) Math.cos(alpha), cp = (float) Math.cos(psi), sp = (float) Math.sin(psi);
			float dx = (float) (axis.x * ca + (pa.x * cp + qa.x * sp) * sa);
			float dy = (float) (axis.y * ca + (pa.y * cp + qa.y * sp) * sa);
			float dz = (float) (axis.z * ca + (pa.z * cp + qa.z * sp) * sa);
			float fade = 1F - u * u;
			float len = 14F * fade * (0.7F + 0.6F * TendrilFx.hash(m.seed + 3, i));
			// the limit bounds the tip, not just the root: a streak that would run past the far point is cut short at it
			len = Math.min(len, (limit - dist) * PX);
			if (len < 0.5F) continue;
			h.add(dx * dist * PX, dy * dist * PX, dz * dist * PX, dx, dy, dz, len, 0.5F * (1F - 0.6F * u));
		}
		TendrilFx.submit(f, from, ArmLayer.BLACK, light, h);
	}

	// ------------------------------------------------------------------ WHIRL

	private void whirl(ArmFxFrame f, ClientLevel level, Move m, Vec3 from, float age, int life) {
		if (!(level.getEntity(m.origin) instanceof Entity o)) return;
		float pt = f.partialTick();
		Vec3 centre = o.getPosition(pt).add(0, o.getBbHeight() * 0.5, 0);
		if (!TendrilFx.finite(centre)) return;
		// it starts at the hand's side and turns once and a half
		float theta0 = (float) Math.atan2(from.z - centre.z, from.x - centre.x);
		float theta = theta0 + 1.6F * ArmMath.TAU * whirlSpin(age, life);
		float reach = outHoldBack(age, WHIRL_EDGE, WHIRL_EDGE, life);
		float rBlocks = WHIRL_RADIUS[m.tier];
		float rPx = rBlocks * PX * 0.96F;
		float cx = (float) (centre.x - from.x) * PX, cy = (float) (centre.y - from.y) * PX, cz = (float) (centre.z - from.z) * PX;
		int light = TendrilFx.light(level, from, centre);
		Path black = path();
		black.reset(Path.TUBE, ArmLayer.BLACK, f.time(), m.seed);
		black.mode = Path.ARC;
		black.age = age;
		black.acx = cx;
		black.acy = cy;
		black.acz = cz;
		black.aR = rPx;
		black.atheta = theta;
		black.alag = 1.15F;
		black.lenPx = rPx * 1.4F;
		black.rootW = LASH_ROOT * 1.15F;
		black.tipW = LASH_TIP * 1.2F;
		black.r1 = reach;
		black.stations = Math.max(4, Math.round(28 * reach) + 2);
		black.part = 70;
		black.frameFrom(1F, 0F, 0F);
		strandsOf(f, black, m.tier, from, light);
		TendrilFx.submit(f, from, ArmLayer.BLACK, light, black);
		// the band trailing the lash's end round the circle, bright where the end is
		float band = ArmMath.sm(age / 2F) * (1F - ArmMath.sm((age - (life - 2F)) / 2F));
		if (band > 0.02F) {
			Path r = path().copy(black);
			r.type = Path.RIBBON;
			r.mode = Path.TRAIL;
			r.layer = ArmLayer.RED;
			r.part = 74;
			r.uvScale = 2F;
			r.wide = 2.4F * band;
			r.taper = true;
			r.trail = 1.7F;
			r.aR = rPx * 0.97F;
			r.r0 = 0F;
			r.r1 = 1F;
			r.stations = 24;
			TendrilFx.submit(f, from, ArmLayer.RED, light, r);
		}
		for (int i = 0; i < m.hits.length; i++) {
			if (level.getEntity(m.hits[i]) instanceof Entity t && !t.isRemoved()) {
				hairBurst(f, level, m, from, TendrilFx.wound(t, pt), age - WHIRL_EDGE - 0.5F * i, 3F, 4, 10 + i);
			}
		}
	}

	// ------------------------------------------------------------------ SNARE

	private void snare(ArmFxFrame f, ClientLevel level, Move m, Vec3 from, float age) {
		if (!(level.getEntity(m.target) instanceof Entity t) || t.isRemoved()) return;
		float pt = f.partialTick();
		int hold = Math.clamp(m.ticks, 4, 100);
		float reach = snareReach(age, hold);
		if (!(reach > 0.01F)) return;
		Vec3 feet = t.getPosition(pt);
		if (!TendrilFx.finite(feet)) return;
		float height = Math.max(0.4F, t.getBbHeight());
		float squeeze = snareSqueeze(age, hold);
		// the loops hug the body, closing in as they tighten and shivering a little
		float hr = (Math.max(0.2F, t.getBbWidth() * 0.5F) + 0.1F) * (1F - 0.2F * squeeze - 0.02F * (float) Math.sin(1.4F * age)) * PX;
		float topY = (float) (feet.y + 0.88F * height - from.y) * PX;
		float drop = 0.72F * height * PX;
		float cx = (float) (feet.x - from.x) * PX, cz = (float) (feet.z - from.z) * PX;
		float phase = (float) Math.atan2(from.z - feet.z, from.x - feet.x);
		float turns = Math.clamp(height * 1.9F, 2F, 4.5F);
		float h0x = cx + hr * (float) Math.cos(phase), h0z = cz + hr * (float) Math.sin(phase);
		float lineLen = (float) Math.sqrt(h0x * h0x + topY * topY + h0z * h0z);
		float helixLen = turns * ArmMath.TAU * hr + drop;
		if (!(lineLen > 4F)) return;
		int light = TendrilFx.light(level, from, feet.add(0, height * 0.5, 0));
		Path black = path();
		black.reset(Path.TUBE, ArmLayer.BLACK, f.time(), m.seed);
		black.mode = Path.COIL;
		black.age = age;
		black.dx = h0x;
		black.dy = topY;
		black.dz = h0z;
		black.hcx = cx;
		black.hcy = topY;
		black.hcz = cz;
		black.hr = hr;
		black.hh = drop;
		black.turns = turns;
		black.hphase = phase;
		black.hsign = (m.seed & 1) == 0 ? 1F : -1F;
		black.sL = lineLen / (lineLen + helixLen);
		black.lenPx = lineLen + helixLen;
		black.amp = 0.18F * PX * Math.min(1F, lineLen / 80F) * decay(age, SNARE_OUT);
		black.rootW = LASH_ROOT * 1.1F;
		black.tipW = LASH_TIP * 1.7F;
		black.r1 = reach;
		black.stations = Math.max(4, Math.round((TendrilPath.pieces(lineLen / PX) + (int) (turns * 12F)) * reach) + 2);
		black.part = 70;
		black.frameFrom(h0x, topY, h0z);
		strandsOf(f, black, m.tier, from, light);
		TendrilFx.submit(f, from, ArmLayer.BLACK, light, black);
	}

	// ------------------------------------------------------------------ shared pieces

	/** The red strands of {@code black}, copies of it twisting round it: one for diamond, two for netherite. */
	private static void strandsOf(ArmFxFrame f, Path black, int tier, Vec3 from, int light) {
		int n = strands(tier);
		for (int k = 0; k < n; k++) {
			Path r = path().copy(black);
			r.layer = ArmLayer.RED;
			r.piece = 1 + k;
			r.phase = ArmMath.TAU * k / n;
			r.part = 71 + k;
			r.uvScale = 1.5F;
			TendrilFx.submit(f, from, ArmLayer.RED, light, r);
		}
	}

	/**
	 * A straight lash with its wave from {@code from} to {@code to}, drawn out to {@code reach} of its length, with the
	 * old lash's wave ({@code 0.4·sin(9s − 1.2t)} across and half as much up). Returns the black piece (for a ribbon
	 * along it), or null when it is too short to show.
	 */
	private @Nullable Path line(ArmFxFrame f, ClientLevel level, Move m, Vec3 from, Vec3 to, float reach, float age, float ampBlocks) {
		if (!(reach > 0.01F)) return null;
		Vec3 d = to.subtract(from);
		double len = d.length();
		if (len < 0.3 || !Double.isFinite(len)) return null;
		float lenPx = (float) len * PX;
		int light = TendrilFx.light(level, from, to);
		Path black = path();
		black.reset(Path.TUBE, ArmLayer.BLACK, f.time(), m.seed);
		black.mode = Path.LINE;
		black.age = age;
		black.dx = (float) d.x * PX;
		black.dy = (float) d.y * PX;
		black.dz = (float) d.z * PX;
		black.lenPx = lenPx;
		black.amp = ampBlocks * PX * Math.min(1F, (float) len / 5F);
		black.rootW = LASH_ROOT;
		black.tipW = LASH_TIP;
		black.r1 = reach;
		black.stations = Math.max(3, Math.round(TendrilPath.pieces((float) len) * reach) + 1);
		black.part = 70;
		black.frame(d.scale(1 / len));
		strandsOf(f, black, m.tier, from, light);
		TendrilFx.submit(f, from, ArmLayer.BLACK, light, black);
		return black;
	}

	/**
	 * A small crack of hairs at {@code at}: {@code n} black hairs spraying back toward {@code from}, over 4 ticks from
	 * {@code since} (nothing before it or after). {@code salt} varies the spray between the effects drawn together.
	 */
	private void hairBurst(ArmFxFrame f, ClientLevel level, Move m, Vec3 from, Vec3 at, float since, float lengthPx, int n, int salt) {
		float hu = since / 4F;
		if (hu < 0F || hu > 1F || !TendrilFx.finite(at)) return;
		Vec3 back = from.subtract(at);
		double bl = back.length();
		if (bl < 1e-4) return;
		back = back.scale(1 / bl);
		Hairs h = hairs();
		h.begin(f.time(), m.seed + 2 + salt);
		int seed = m.seed + 31 * salt;
		for (int k = 0; k < Math.min(n, MAX_HAIRS); k++) {
			double spin = ArmMath.TAU * TendrilFx.hash(seed, k);
			double lift = 0.4 + 0.6 * TendrilFx.hash(seed + 3, k);
			h.add(0F, 0F, 0F, (float) (back.x * 0.6 + Math.cos(spin) * lift), (float) (back.y * 0.6 + 0.3 + Math.sin(spin) * lift * 0.6),
					(float) (back.z * 0.6 + Math.sin(spin) * lift), lengthPx * (1F - hu) * (0.7F + 0.6F * TendrilFx.hash(seed + 9, k)), 0.4F * (1F - 0.7F * hu));
		}
		TendrilFx.submit(f, at, ArmLayer.BLACK, TendrilFx.light(level, from, at), h);
	}

	// ------------------------------------------------------------------ the pooled pieces

	/**
	 * One piece of lash geometry: a tube (black, or a red strand twisting round it), a flat ribbon, or a ring, along a path
	 * from the whip. Paths are in px from the whip's tip: {@link #LINE} straight with the lash's wave, {@link #COIL} a line to
	 * the top of a helix and the helix down a creature, {@link #ARC} the whirling lash (a line from the hand spiralling out to
	 * the circle, its end {@code atheta} round it and trailing back by {@code alag}), {@link #TRAIL} the circle itself behind
	 * the end. It is its own curve and radius function and its own geometry, so a frame's pieces are reused, not allocated.
	 */
	static final class Path implements Loft.CurveFn, Loft.RadiusFn, TendrilFx.Geometry {
		static final int TUBE = 0, RIBBON = 1, RING = 2;
		static final int LINE = 0, COIL = 1, ARC = 2, TRAIL = 3;

		int type, mode, piece, part = 70, stations = 8;
		ArmLayer layer = ArmLayer.BLACK;
		float time, age, phase, r0, r1 = 1F, lenPx, uvScale = 1F, rootW = LASH_ROOT, tipW = LASH_TIP, wide, amp;
		int seed;
		boolean taper;
		// line: the vector (px) and the frame across it
		float dx, dy, dz, sx = 1F, sy, sz, ux, uy = 1F, uz;
		// coil
		float hcx, hcy, hcz, hr, hh, turns, hphase, hsign = 1F, sL = 0.5F;
		// arc and trail
		float acx, acy, acz, aR, atheta, alag, trail;
		// ring's plane normal
		float axisX, axisY = 1F, axisZ;
		private float[] buf = new float[3 * 32];
		private final Vector3f axis = new Vector3f();

		void reset(int type, ArmLayer layer, float time, int seed) {
			this.type = type;
			this.layer = layer;
			this.time = time;
			this.seed = seed;
			mode = LINE;
			piece = 0;
			part = 70;
			stations = 8;
			age = 0F;
			phase = 0F;
			r0 = 0F;
			r1 = 1F;
			lenPx = 0F;
			uvScale = 1F;
			rootW = LASH_ROOT;
			tipW = LASH_TIP;
			wide = 0F;
			amp = 0F;
			taper = false;
			dx = dy = dz = 0F;
			hcx = hcy = hcz = hr = hh = turns = hphase = 0F;
			hsign = 1F;
			sL = 0.5F;
			acx = acy = acz = aR = atheta = alag = trail = 0F;
			axisX = 0F;
			axisY = 1F;
			axisZ = 0F;
		}

		/** Takes everything of {@code o} (a piece of the same lash), then the caller changes what differs. */
		Path copy(Path o) {
			type = o.type;
			mode = o.mode;
			piece = o.piece;
			part = o.part;
			stations = o.stations;
			layer = o.layer;
			time = o.time;
			age = o.age;
			phase = o.phase;
			r0 = o.r0;
			r1 = o.r1;
			lenPx = o.lenPx;
			uvScale = o.uvScale;
			rootW = o.rootW;
			tipW = o.tipW;
			wide = o.wide;
			amp = o.amp;
			taper = o.taper;
			seed = o.seed;
			dx = o.dx;
			dy = o.dy;
			dz = o.dz;
			sx = o.sx;
			sy = o.sy;
			sz = o.sz;
			ux = o.ux;
			uy = o.uy;
			uz = o.uz;
			hcx = o.hcx;
			hcy = o.hcy;
			hcz = o.hcz;
			hr = o.hr;
			hh = o.hh;
			turns = o.turns;
			hphase = o.hphase;
			hsign = o.hsign;
			sL = o.sL;
			acx = o.acx;
			acy = o.acy;
			acz = o.acz;
			aR = o.aR;
			atheta = o.atheta;
			alag = o.alag;
			trail = o.trail;
			axisX = o.axisX;
			axisY = o.axisY;
			axisZ = o.axisZ;
			return this;
		}

		/** The frame across a direction: side = dir x up, up = side x dir, as the old lash has. */
		void frame(Vec3 dir) {
			frameFrom((float) dir.x, (float) dir.y, (float) dir.z);
		}

		void frameFrom(float x, float y, float z) {
			float l = (float) Math.sqrt(x * x + y * y + z * z);
			if (!(l > 1e-6F)) {
				sx = 1F;
				sy = 0F;
				sz = 0F;
				ux = 0F;
				uy = 1F;
				uz = 0F;
				return;
			}
			x /= l;
			y /= l;
			z /= l;
			// side = dir x (0,1,0) = (-z, 0, x)
			float sl = (float) Math.sqrt(x * x + z * z);
			if (sl < 1e-3F) {
				sx = 1F;
				sy = 0F;
				sz = 0F;
			} else {
				sx = -z / sl;
				sy = 0F;
				sz = x / sl;
			}
			// up = side x dir
			ux = sy * z - sz * y;
			uy = sz * x - sx * z;
			uz = sx * y - sy * x;
			float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
			if (ul > 1e-6F) {
				ux /= ul;
				uy /= ul;
				uz /= ul;
			}
		}

		/** The path's own point at {@code s}, in px from the whip's tip. */
		void base(float s, Vector3f out) {
			switch (mode) {
				case COIL -> {
					if (s <= sL) {
						WhipFx.lashAt(sL > 1e-6F ? s / sL : 1F, (float) Math.sqrt(dx * dx + dy * dy + dz * dz), dx, dy, dz, sx, sy, sz, ux, uy, uz, amp, age, seed, out);
					} else {
						float u = (s - sL) / (1F - sL);
						float ang = hphase + hsign * turns * ArmMath.TAU * u;
						out.set(hcx + hr * (float) Math.cos(ang), hcy - hh * u, hcz + hr * (float) Math.sin(ang));
					}
				}
				case ARC -> {
					// the lash from the hand out to the circle: radius grows with s, the end leads, the body trails behind it
					float w = ArmMath.sm(s / 0.3F);
					float r = aR * (1F - (1F - s) * (1F - s));
					float x = 1F - s;
					float ang = atheta - alag * x * (float) Math.sqrt(x);
					out.set(w * (acx + r * (float) Math.cos(ang)), w * (acy - 0.04F * aR * (float) Math.sin(Math.PI * s)), w * (acz + r * (float) Math.sin(ang)));
				}
				case TRAIL -> {
					float ang = atheta - trail * (1F - s);
					out.set(acx + aR * (float) Math.cos(ang), acy, acz + aR * (float) Math.sin(ang));
				}
				default -> WhipFx.lashAt(s, lenPx, dx, dy, dz, sx, sy, sz, ux, uy, uz, amp, age, seed, out);
			}
		}

		@Override
		public void at(float s, Vector3f out) {
			if (type == RING) {
				float ang = ArmMath.TAU * s;
				float c = (float) Math.cos(ang) * hr, sn = (float) Math.sin(ang) * hr;
				out.set(sx * c + ux * sn, sy * c + uy * sn, sz * c + uz * sn);
				return;
			}
			base(s, out);
			if (piece > 0) {
				// a red strand twisting round the lash, as the old lash's
				float rho = 0.55F * ArmMath.lerp(rootW, tipW, s);
				float psi = phase + s * lenPx / 4F - 0.5F * age;
				float c = rho * (float) Math.cos(psi), sn = rho * (float) Math.sin(psi);
				out.add(c * sx + sn * ux, c * sy + sn * uy, c * sz + sn * uz);
			}
		}

		@Override
		public float at(float s) {
			if (type == RING) return FistGeometry.width(rootW, 4);
			float w = ArmMath.lerp(rootW, tipW, s);
			return FistGeometry.width(piece > 0 ? 0.5F * w : w, 4);
		}

		@Override
		public void emit(SkinMesh m) {
			ST.set(time, TendrilFx.ACTIVE, 1F, seed + (piece > 0 ? 1 + part : 0));
			switch (type) {
				case RIBBON -> ribbon(m);
				case RING -> {
					// the loop's frame comes from the axis: side and up square to it
					axis.set(axisX, axisY, axisZ);
					Loft.closedTube(m, this, stations, 4, this, layer, layer, ST, part, uvScale, 0F, axis, 1F);
				}
				default -> Loft.tube(m, this, r0, r1, stations, 4, this, layer, layer, ST, part, uvScale, 0F, lenPx, UP_Y, 1F);
			}
		}

		/** A flat ribbon along the path, standing across it (its width along the radius on a circle, the side on a line). */
		private void ribbon(SkinMesh m) {
			int n = Math.max(2, stations);
			if (buf.length < 3 * (n + 1)) buf = new float[3 * (n + 1)];
			for (int i = 0; i <= n; i++) {
				base(r0 + (r1 - r0) * i / n, V);
				buf[3 * i] = V.x;
				buf[3 * i + 1] = V.y;
				buf[3 * i + 2] = V.z;
			}
			Stations st = RIB.reset(2);
			float v = 0F;
			boolean circle = mode == ARC || mode == TRAIL;
			for (int i = 0; i <= n; i++) {
				int a = Math.max(0, i - 1), z = Math.min(n, i + 1);
				float u = (float) i / n;
				float tx = buf[3 * z] - buf[3 * a], ty = buf[3 * z + 1] - buf[3 * a + 1], tz = buf[3 * z + 2] - buf[3 * a + 2];
				if (i > 0) {
					float ex = buf[3 * i] - buf[3 * i - 3], ey = buf[3 * i + 1] - buf[3 * i - 2], ez = buf[3 * i + 2] - buf[3 * i - 1];
					v += (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
				}
				float nx = sx, ny = sy, nz = sz;
				if (circle) {
					float rx = buf[3 * i] - acx, rz = buf[3 * i + 2] - acz;
					float rl = (float) Math.sqrt(rx * rx + rz * rz);
					if (rl > 1e-4F) {
						nx = rx / rl;
						ny = 0F;
						nz = rz / rl;
					}
				}
				float w = taper ? wide * u : wide;
				POLY[0] = -w / 2F;
				POLY[1] = 0F;
				POLY[2] = w / 2F;
				POLY[3] = 0F;
				st.add(buf[3 * i], buf[3 * i + 1], buf[3 * i + 2], tx, ty, tz, nx, ny, nz, v, POLY, taper ? u : 1F);
			}
			Loft.sweep(m, st, ArmLayer.RED, RED_FACE, ST, part, uvScale, false, false);
		}
	}

	/** Hairs (thin tapered strands) in px from a point: root, direction (any length), length and root width. */
	static final class Hairs implements TendrilFx.Geometry {
		private final float[] data = new float[8 * MAX_HAIRS];
		private int n;
		private float time;
		private int seed;

		void begin(float time, int seed) {
			this.time = time;
			this.seed = seed;
			n = 0;
		}

		void add(float rx, float ry, float rz, float dx, float dy, float dz, float len, float width) {
			if (n >= MAX_HAIRS) return;
			int o = 8 * n++;
			data[o] = rx;
			data[o + 1] = ry;
			data[o + 2] = rz;
			data[o + 3] = dx;
			data[o + 4] = dy;
			data[o + 5] = dz;
			data[o + 6] = len;
			data[o + 7] = width;
		}

		@Override
		public void emit(SkinMesh m) {
			ST.set(time, TendrilFx.ACTIVE, 0F, seed);
			for (int i = 0; i < n; i++) {
				int o = 8 * i;
				float len = data[o + 6], w = data[o + 7];
				float dx = data[o + 3], dy = data[o + 4], dz = data[o + 5];
				float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (!(len > 0.01F) || !(w > 0.005F) || !(d > 1e-6F)) continue;
				TURN.rotationTo(0F, 0F, 1F, dx / d, dy / d, dz / d);
				LOCAL.translation(data[o], data[o + 1], data[o + 2]).rotate(TURN);
				m.frustum(LOCAL, len, w, w, w * 0.25F, w * 0.25F, ST.u0(43, i, 0F), ST.v0(43, i, 0F), 2F, 1F, 1F, SkinMesh.WHITE);
			}
		}
	}
}
