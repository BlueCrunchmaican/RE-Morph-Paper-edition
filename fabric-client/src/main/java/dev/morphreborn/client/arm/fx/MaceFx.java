package dev.morphreborn.client.arm.fx;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.geom.FistGeometry;
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
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The mace's Skyward Leap (S_ARM_FX kind 16, FX_MACE; my design, 2026-11-16), in two moves multiplexed on {@code arg}.
 * <p>
 * <b>{@link #LEAP}</b> ({@code arg} 1, sent when F is paid): {@code level} is the tier, {@code ticks} how many ticks the rise takes,
 * {@code positions} the block the leaper's feet start in and the block at the apex straight above it, {@code seed} the server's. A
 * fan of black braided tendrils (five, seven or nine by tier) springs out of the ground in a ring round the launch point and
 * reaches up for the owner's feet, stretching as the body rises, taut at the end of their reach, then lets go and whips back into
 * the ground, rippling harder as they recoil. A crack opens in the ground under them and a few clods of earth are thrown up. The
 * tendrils follow the owner's real position, so the owner's own first-person view and everyone else's third-person one agree.
 * <p>
 * <b>{@link #LANDING}</b> ({@code arg} 2, once per leap or smash): {@code level} is the tier, {@code ticks} the fall in tenths of a
 * block (the shockwave is scaled by it, a short fall drawing a smaller one), {@code ids} the creature hit (none: the leap ended on the
 * ground), {@code positions} the block under the impact, {@code seed} the server's. At the landing point a shock ring races out over
 * the ground, a crown of black tendril teeth with red tips standing on it (and, by tier, a second ring in red and a third inside it); cracks of
 * tendril run out across the ground from the middle; and a burst of tendril shards, black with red tips, is thrown up and out and
 * falls back. A creature hit takes a red knot at its body and a small spray of shards of its own.
 * <p>
 * Allocation: every move keeps its own world point and the pieces it draws are pooled ({@link Fan}, {@link Ring}, {@link Shards}) and
 * reused; a frame's collect resets the pools, so nothing here survives into the next. (A submit still allocates its lambda, as every
 * world effect's does.)
 */
public final class MaceFx implements FxHandler {
	/** {@code arg}: the leap, and the landing. */
	public static final int LEAP = 1, LANDING = 2;
	private static final float PX = 16F;
	/** Ticks the tendrils take to spring out, their overshoot, how long they settle, and how long the recoil takes. */
	static final float ERUPT = 2.2F, OVERSHOOT = 1.15F, SETTLE = 1.5F, RECOIL = 4.5F;
	/** Ticks the shock ring takes to reach its full size, how long it stays, and the crack's growth and the shards' life. */
	static final float SHOCK = 7F, RING_LIFE = 10F, CRACK_GROW = 3.5F, SHARD_LIFE = 12F;
	/** The whole landing's life from receipt: the cracks are the last to go. */
	static final float LANDING_LIFE = 14F;
	/** How far above the feet block's floor a leap's ground point may sit (a slab, a path, carpet; never the owner's rise after the launch). */
	static final double LEAP_GROUND_RISE = 0.5;
	/** The most effects drawn at once, tendrils or teeth or shards one piece holds, and creatures a landing draws on. */
	private static final int MAX_MOVES = 6, MAX_TENDRILS = 10, MAX_TEETH = 20, MAX_SHARDS = 28, MAX_HIT = 4;
	/** How far a tendril reaches for the feet (blocks), by tier. */
	private static final float[] TETHER = {0F, 2.6F, 3.1F, 3.6F};
	/** The shock ring's greatest radius in blocks at a full-size landing, by tier. */
	private static final float[] RING_RADIUS = {0F, 3.2F, 4F, 4.8F};
	/** The shard ballistics, px per tick and px per tick squared: sideways, the lift's base and spread, and gravity. */
	private static final float SHARD_SIDE = 6.2F, SHARD_LIFT = 5F, SHARD_LIFT_SPREAD = 4F, SHARD_GRAVITY = 0.8F;

	private static final class Move {
		final int origin, tier, kind, seed, ticks;
		final int[] hits = new int[MAX_HIT];
		int hitCount;
		final long start;
		/** The point everything is drawn from (px offsets), kept so a frame allocates nothing for it: the ground at the launch or the impact. */
		final Vec3 base;
		/** How far the tendrils reach (blocks): the tier's, or the apex's height when a ceiling made the leap shorter. */
		final float tether;

		Move(int origin, int tier, int kind, int seed, int ticks, long start, Vec3 base, float tether) {
			this.origin = origin;
			this.tier = tier;
			this.kind = kind;
			this.seed = seed;
			this.ticks = ticks;
			this.start = start;
			this.base = base;
			this.tether = tether;
		}
	}

	private final List<Move> moves = new ArrayList<>();

	private static final Static ST = new Static();
	private static final Quaternionf TURN = new Quaternionf();
	private static final Matrix4f LOCAL = new Matrix4f();
	private static final Vector3f UP_Y = new Vector3f(0F, 1F, 0F);

	private static final List<Fan> FANS = new ArrayList<>();
	private static final List<Ring> RINGS = new ArrayList<>();
	private static final List<Shards> SHARDS = new ArrayList<>();
	private static int fansUsed, ringsUsed, shardsUsed;

	public MaceFx() {}

	@Override
	public int[] kinds() {
		return new int[] {ArmProtocol.FX_MACE};
	}

	// ------------------------------------------------------------------ timelines (pure, tested)

	/** The tier, whatever a bad packet says. */
	static int tier(int level) {
		return Math.clamp(level, 1, 3);
	}

	/** Tendrils in the leap's fan, by tier (5, 7, 9). */
	static int tendrilCount(int tier) {
		return 3 + 2 * tier(tier);
	}

	/** Red strands twisting round each black tendril: none at level 1, one at level 2, two at level 3, as the whip's and the spear's. */
	static int redStrands(int tier) {
		return tier(tier) - 1;
	}

	/** Teeth on the shock ring's crown, by tier (12, 16, 20). */
	static int teethCount(int tier) {
		return 8 + 4 * tier(tier);
	}

	/** Shards in the landing's burst, by tier (14, 20, 26), scaled down a little for a small landing. */
	static int shardCount(int tier, float scale) {
		int full = 8 + 6 * tier(tier);
		return Math.clamp(Math.round(full * (0.55F + 0.45F * Math.min(1F, scale))), 4, MAX_SHARDS);
	}

	/** Cracks running out across the ground, by tier (6, 8, 10). */
	static int crackCount(int tier) {
		return 4 + 2 * tier(tier);
	}

	/** The launch's clods of earth, by tier (6, 8, 10). */
	static int clodCount(int tier) {
		return 4 + 2 * tier(tier);
	}

	/** The shockwave's size from the fall in tenths of a block the server gives: 0.6 for a hop up to 1.1 for a fall of ten blocks. */
	static float sizeScale(int tenths) {
		return 0.6F + 0.5F * ArmMath.clamp01(Math.clamp(tenths, 0, 1000) / 100F);
	}

	/** The shock ring's greatest radius (blocks) for {@code tier} at {@code scale} (see {@link #sizeScale}). */
	static float ringRadius(int tier, float scale) {
		return RING_RADIUS[tier(tier)] * scale;
	}

	/** How far out the tendrils have sprung {@code age} ticks in: 0, out to a little over 1, settling to 1. */
	static float spring(float age) {
		if (age <= 0F) return 0F;
		if (age < ERUPT) {
			float x = 1F - age / ERUPT;
			return OVERSHOOT * (1F - x * x * x);
		}
		if (age < ERUPT + SETTLE) return ArmMath.lerp(OVERSHOOT, 1F, ArmMath.sm((age - ERUPT) / SETTLE));
		return 1F;
	}

	/** The tick the tendrils let go and whip back, from the rise's length: a fifth of the way up, between 2.2 and 5 ticks. */
	static float release(int riseTicks) {
		return Math.clamp(0.2F * Math.max(0, riseTicks), 2.2F, 5F);
	}

	/** How far the recoil has gone, 0 (not yet) to 1 (back in the ground). */
	static float recoil(float age, float release) {
		return ArmMath.sm((age - release) / RECOIL);
	}

	/** How much of its reach a tendril has out: the spring, less the recoil. */
	static float reach(float age, float release) {
		return spring(age) * (1F - recoil(age, release));
	}

	/** The leap effect's life from receipt. */
	static float leapLife(float release) {
		return release + RECOIL + 1F;
	}

	/** Each tendril springs a little after the one before, round the ring: a spiral rather than a line. */
	static float stagger(int k, int n) {
		int step = 3;
		while (gcd(step, n) != 1) step += 2;
		return 0.7F * ((k * step) % n) / n;
	}

	private static int gcd(int a, int b) {
		return b == 0 ? a : gcd(b, a % b);
	}

	/** The shock ring's radius {@code age} ticks in, blocks: racing out fast and slowing, to {@code max} at {@link #SHOCK}. */
	static float shockRadius(float age, float max) {
		float x = 1F - ArmMath.clamp01(age / SHOCK);
		return max * (1F - (float) Math.pow(x, 2.4));
	}

	/** The shock ring's tube width in px: fat at first, thinning to a thread as it fades; 0 once it is gone. */
	static float ringWidth(float age) {
		if (age >= RING_LIFE) return 0F;
		return 3.4F * (1F - ArmMath.sm(age / RING_LIFE)) + 0.25F;
	}

	/** How tall the crown's teeth stand, 0..1: out over 1.5 ticks, then going down with the ring. */
	static float teethHeight(float age) {
		if (age <= 0F || age >= RING_LIFE) return 0F;
		return ArmMath.sm(age / 1.5F) * (1F - ArmMath.sm((age - 3F) / (RING_LIFE - 3F)));
	}

	/** How far the cracks have run, 0..1: out over {@link #CRACK_GROW}. */
	static float crackGrow(float age) {
		return ArmMath.sm(age / CRACK_GROW);
	}

	/** How much of a crack's tail has been eaten toward the middle, 0..1: from tick 5.5 to the landing's end. */
	static float crackEaten(float age) {
		return ArmMath.sm((age - 5.5F) / (LANDING_LIFE - 5.5F - 0.5F));
	}

	/** A shard's height above the ground in px, {@code t} ticks after it was thrown with lift {@code lift}: up and back down (never below the ground). */
	static float shardHeight(float lift, float t) {
		return Math.max(0F, lift * t - 0.5F * SHARD_GRAVITY * t * t);
	}

	/** A shard's size at {@code t} ticks into its {@link #SHARD_LIFE}: full for most of it, shrinking to nothing at the end. */
	static float shardScale(float t) {
		return 1F - ArmMath.sm((t - 0.55F * SHARD_LIFE) / (0.45F * SHARD_LIFE));
	}

	// ------------------------------------------------------------------ the effect's life

	/**
	 * {@code real} kept inside the block {@code cell} (so a stale or wild entity position never drags the ring away from the block the server
	 * named): the exact spot where it stands, as the packet only has whole blocks. {@code top} is how far up the block the clamp reaches
	 * (1 for a ground that may be the block's top face).
	 */
	static double settle(int cell, double real, double top) {
		return Math.clamp(real, cell, cell + top);
	}

	static double settle(int cell, double real) {
		return settle(cell, real, 1.0);
	}

	@Override
	public void start(ArmFxEvent e) {
		if (e.arg() != LEAP && e.arg() != LANDING) return;
		if (e.positions().length == 0) return; // nowhere to draw it
		int tier = tier(e.level());
		BlockPos at = BlockPos.of(e.positions()[0]);
		Vec3 base;
		float tether = TETHER[tier];
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc == null ? null : mc.level;
		if (e.arg() == LEAP) {
			// the ground under the leaper's feet (the feet's block's bottom, or the real height when the leaper is in view: a path, a slab);
			// a ceiling that cut the leap short shortens the reach. The height is only refined up to half a block: the launch moves the owner
			// about a block a tick, so by the time this is handled their position may already be up the rise, and the roots must not start in the
			// air (review 2026-11-16, second round)
			Entity owner = level == null ? null : level.getEntity(e.origin());
			base = owner == null ? Vec3.atBottomCenterOf(at)
					: new Vec3(settle(at.getX(), owner.getX()), settle(at.getY(), owner.getY(), LEAP_GROUND_RISE), settle(at.getZ(), owner.getZ()));
			if (e.positions().length > 1) tether = Math.clamp(BlockPos.of(e.positions()[1]).getY() - at.getY(), 1.5F, tether);
		} else {
			// the block under the impact: its top face is the ground, unless the one hit (or the one who landed) is in view and stands on less
			// than a full block (a slab, carpet, snow), and then it is where they stand; the packet only names a block (review 2026-11-16)
			Entity on = level == null ? null : level.getEntity(e.ids().length > 0 ? e.ids()[0] : e.origin());
			if (on == null || !TendrilFx.finite(on.position())) {
				base = new Vec3(at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5);
			} else {
				base = new Vec3(settle(at.getX(), on.getX()), settle(at.getY(), on.getY(), 1.0), settle(at.getZ(), on.getZ()));
			}
		}
		if (!TendrilFx.finite(base)) return;
		if (moves.size() >= MAX_MOVES) moves.remove(0);
		Move m = new Move(e.origin(), tier, e.arg(), (e.seed() ^ e.origin() * 31) & 0x7FFF, e.ticks(), e.receivedTick(), base, tether);
		for (int i = 0; i < e.ids().length && m.hitCount < MAX_HIT; i++) m.hits[m.hitCount++] = e.ids()[i];
		moves.add(m);
	}

	@Override
	public void tick() {
		long now = Arms.TICK;
		for (int i = moves.size() - 1; i >= 0; i--) {
			Move m = moves.get(i);
			if (now - m.start > life(m) + 1) moves.remove(i);
		}
	}

	private static float life(Move m) {
		return m.kind == LEAP ? leapLife(release(m.ticks)) : LANDING_LIFE;
	}

	@Override
	public void clear() {
		moves.clear();
		fansUsed = 0;
		ringsUsed = 0;
		shardsUsed = 0;
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void collect(ArmFxFrame f) {
		fansUsed = 0;
		ringsUsed = 0;
		shardsUsed = 0;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || moves.isEmpty()) return;
		for (int i = 0; i < moves.size(); i++) {
			Move m = moves.get(i);
			float age = Arms.age(m.start, f.partialTick());
			if (age < 0F || age > life(m)) continue;
			ArmTint.owner(m.origin); // each is drawn in its owner's colour
			int light = TendrilFx.light(level, m.base, m.base);
			if (m.kind == LEAP) leap(f, level, m, age, light);
			else landing(f, level, m, age, light);
		}
	}

	private static Fan fan() {
		if (fansUsed == FANS.size()) FANS.add(new Fan());
		return FANS.get(fansUsed++);
	}

	private static Ring ring() {
		if (ringsUsed == RINGS.size()) RINGS.add(new Ring());
		return RINGS.get(ringsUsed++);
	}

	private static Shards shards() {
		if (shardsUsed == SHARDS.size()) SHARDS.add(new Shards());
		return SHARDS.get(shardsUsed++);
	}

	/** Submits a fan's black tendrils, then (at the higher tiers) its red strands. */
	private static void submitFan(ArmFxFrame f, Vec3 at, int light, Fan fan, int tier) {
		TendrilFx.submit(f, at, ArmLayer.BLACK, light, fan);
		if (redStrands(tier) > 0) TendrilFx.submit(f, at, ArmLayer.RED, light, fan.copyFor(ArmLayer.RED));
	}

	// ------------------------------------------------------------------ the leap

	private static void leap(ArmFxFrame f, ClientLevel level, Move m, float age, int light) {
		float rel = release(m.ticks);
		int n = Math.min(MAX_TENDRILS, tendrilCount(m.tier));
		// where the owner's feet are, relative to the ground point, in px (interpolated: no allocation); straight up when the owner isn't loaded
		Entity owner = level.getEntity(m.origin);
		float ex = 0F, ey = tetherPx(m), ez = 0F;
		if (owner != null && !owner.isRemoved()) {
			float pt = f.partialTick();
			ex = (float) ((Mth.lerp(pt, owner.xo, owner.getX()) - m.base.x) * PX);
			ey = (float) ((Mth.lerp(pt, owner.yo, owner.getY()) - m.base.y) * PX);
			ez = (float) ((Mth.lerp(pt, owner.zo, owner.getZ()) - m.base.z) * PX);
			if (!Float.isFinite(ex + ey + ez)) {
				ex = 0F;
				ey = tetherPx(m);
				ez = 0F;
			}
		}
		float cap = tetherPx(m);
		Fan fan = fan();
		fan.reset(ArmLayer.BLACK, f.time(), m.seed, age, m.tier);
		fan.w0 = 2.6F;
		fan.w1 = 1.0F;
		fan.part = 140;
		for (int k = 0; k < n; k++) {
			float a = ArmMath.TAU * (k + 0.4F * TendrilFx.hash(m.seed, k)) / n;
			float r = (0.28F + 0.22F * TendrilFx.hash(m.seed + 1, k)) * PX;
			float ox = r * (float) Math.cos(a), oz = r * (float) Math.sin(a);
			float dx = ex - ox, dy = ey - 0.4F, dz = ez - oz;
			float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			float ka = age - stagger(k, n);
			float out = reach(ka, rel);
			float len = Math.min(dist, cap * Math.min(OVERSHOOT, spring(ka))) * (1F - recoil(ka, rel));
			if (!(len > 0.3F) || !(dist > 1e-3F) || out <= 0.005F) continue;
			float s = len / dist;
			// slack ripples gently; the recoil snaps them into a flail
			float flail = 1F + 1.8F * recoil(ka, rel);
			float slack = 1F - Math.min(1F, len / Math.max(1F, cap));
			fan.add(ox, 0.4F, oz, dx * s, dy * s, dz * s, 0.12F * PX * Math.min(1F, len / 48F) * flail * (0.35F + 0.65F * slack + 0.25F * recoil(ka, rel)), 0F, 1F, k);
		}
		if (fan.count > 0) submitFan(f, m.base, light, fan, m.tier);
		// the ground splits under the tendrils, and clods are thrown up as they spring
		float ringAge = age - 0.4F;
		if (ringAge > 0F && ringAge < 6F) {
			Ring r = ring();
			r.reset(f.time(), m.seed, m.tier, ringAge, 6F, 1.25F * PX * ArmMath.sm(ringAge / 3F), 0F, false);
			TendrilFx.submit(f, m.base, ArmLayer.BLACK, light, r);
		}
		if (age < 7F) {
			Shards c = shards();
			c.reset(f.time(), m.seed + 9, m.tier, age, clodCount(m.tier), 0.45F, 0.35F * PX, true);
			if (ArmFx.isOwnFirstPerson(m.origin, f.firstPersonCamera())) c.avoid(f.camPos(), m.base);
			TendrilFx.submit(f, m.base, ArmLayer.BLACK, light, c); // clods are black alone
		}
	}

	private static float tetherPx(Move m) {
		return m.tether * PX;
	}

	// ------------------------------------------------------------------ the landing

	private static void landing(ArmFxFrame f, ClientLevel level, Move m, float age, int light) {
		float scale = sizeScale(m.ticks);
		float rMax = ringRadius(m.tier, scale) * PX;
		// your own landing in your own first-person view: the shards fly up past the eye (a red slab across the view for ~3 frames, the rig,
		// 2026-11-16), so they keep off it ({@link NearCamera})
		boolean own = ArmFx.isOwnFirstPerson(m.origin, f.firstPersonCamera());
		// the shock ring and its crown
		if (age < RING_LIFE) {
			Ring r = ring();
			r.reset(f.time(), m.seed, m.tier, age, RING_LIFE, shockRadius(age, rMax), rMax, true);
			if (own) r.avoid(f.camPos(), m.base);
			TendrilFx.submit(f, m.base, ArmLayer.BLACK, light, r);
			// the red layer: the teeth's tips, and from level 2 the red ring inside the black one
			TendrilFx.submit(f, m.base, ArmLayer.RED, light, r.copyFor(ArmLayer.RED));
		}
		// the cracks running out of the middle across the ground
		float grow = crackGrow(age), eaten = crackEaten(age);
		if (grow > 0.01F && eaten < 0.995F) {
			int n = Math.min(MAX_TENDRILS, crackCount(m.tier));
			Fan fan = fan();
			fan.reset(ArmLayer.BLACK, f.time(), m.seed + 3, age, m.tier);
			fan.w0 = 1.7F;
			fan.w1 = 0.4F;
			fan.part = 170;
			fan.ground = true;
			for (int k = 0; k < n; k++) {
				float a = ArmMath.TAU * (k + 0.7F * TendrilFx.hash(m.seed + 2, k)) / n;
				float len = rMax * (0.5F + 0.4F * TendrilFx.hash(m.seed + 4, k));
				float r0 = 0.12F * PX;
				float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
				fan.add(r0 * ca, 0.5F, r0 * sa, len * ca, 0F, len * sa, 0.05F * PX * Math.min(1F, len / 48F), eaten, grow, k);
			}
			if (fan.count > 0) submitFan(f, m.base, light, fan, m.tier);
		}
		// the burst of shards
		if (age < SHARD_LIFE) {
			Shards s = shards();
			s.reset(f.time(), m.seed + 17, m.tier, age, shardCount(m.tier, scale), scale, 0.4F * PX * scale, false);
			if (own) s.avoid(f.camPos(), m.base);
			TendrilFx.submit(f, m.base, ArmLayer.BLACK, light, s);
			TendrilFx.submit(f, m.base, ArmLayer.RED, light, s.copyFor(ArmLayer.RED));
		}
		// a creature hit: a red knot clenched on its body and a small spray of its own
		for (int i = 0; i < m.hitCount; i++) {
			float hu = age / 4F;
			if (hu > 1F) break;
			Entity e = level.getEntity(m.hits[i]);
			if (e == null || e.isRemoved()) continue;
			Vec3 at = TendrilFx.wound(e, f.partialTick());
			if (!TendrilFx.finite(at)) continue;
			int hl = TendrilFx.light(level, at, at);
			float knot = 3.4F * (1F - ArmMath.sm(hu));
			if (own) knot *= NearCamera.fade(f.camPos().distanceTo(at), NearCamera.CLEAR, NearCamera.BAND);
			TendrilFx.knot(f, at, knot, ArmLayer.RED, hl, m.seed + i);
			Shards s = shards();
			s.reset(f.time(), m.seed + 31 * (i + 1), m.tier, age, 7, 0.5F * scale, 0F, false);
			s.wound = true;
			if (own) s.avoid(f.camPos(), at);
			TendrilFx.submit(f, at, ArmLayer.BLACK, hl, s);
			TendrilFx.submit(f, at, ArmLayer.RED, hl, s.copyFor(ArmLayer.RED));
		}
	}

	// ------------------------------------------------------------------ the pooled pieces

	/**
	 * Straight tendrils, each from its own root along its own vector (px from the move's ground point), tapering from {@code w0} to
	 * {@code w1}, a ripple running along it, and optionally a window of it ({@code s0..s1}) drawn (a crack grows from its middle and
	 * is eaten back to it). The black layer draws the tendrils; the red layer the strands twisting round each ({@link #redStrands}). The
	 * leap's tendrils are this, and so are the landing's cracks ({@link #ground}: they lie on the ground, so they ripple only
	 * sideways). It is its own curve and radius function, so a frame's pieces are reused, not allocated.
	 */
	static final class Fan implements Loft.CurveFn, Loft.RadiusFn, TendrilFx.Geometry {
		ArmLayer layer = ArmLayer.BLACK;
		float time, age, w0 = 1F, w1 = 1F;
		int seed, tier = 1, part = 140, count;
		boolean ground;
		final float[] ox = new float[MAX_TENDRILS], oy = new float[MAX_TENDRILS], oz = new float[MAX_TENDRILS];
		final float[] vx = new float[MAX_TENDRILS], vy = new float[MAX_TENDRILS], vz = new float[MAX_TENDRILS];
		final float[] amp = new float[MAX_TENDRILS], from = new float[MAX_TENDRILS], to = new float[MAX_TENDRILS];
		final int[] id = new int[MAX_TENDRILS];
		// the tendril being drawn
		private int k, piece;
		private float lenPx, sx = 1F, sy, sz, ux, uy = 1F, uz;

		void reset(ArmLayer layer, float time, int seed, float age, int tier) {
			this.layer = layer;
			this.time = time;
			this.seed = seed;
			this.age = age;
			this.tier = tier;
			w0 = 1F;
			w1 = 1F;
			part = 140;
			count = 0;
			ground = false;
		}

		/** The other layer's copy of this (the same tendrils): shares the arrays' contents by copying them into a pooled twin. */
		Fan copyFor(ArmLayer l) {
			Fan twin = twin(this);
			twin.layer = l;
			return twin;
		}

		private Fan twinPiece;

		private static Fan twin(Fan o) {
			if (o.twinPiece == null) o.twinPiece = new Fan();
			Fan t = o.twinPiece;
			t.time = o.time;
			t.age = o.age;
			t.w0 = o.w0;
			t.w1 = o.w1;
			t.seed = o.seed;
			t.tier = o.tier;
			t.part = o.part;
			t.count = o.count;
			t.ground = o.ground;
			System.arraycopy(o.ox, 0, t.ox, 0, MAX_TENDRILS);
			System.arraycopy(o.oy, 0, t.oy, 0, MAX_TENDRILS);
			System.arraycopy(o.oz, 0, t.oz, 0, MAX_TENDRILS);
			System.arraycopy(o.vx, 0, t.vx, 0, MAX_TENDRILS);
			System.arraycopy(o.vy, 0, t.vy, 0, MAX_TENDRILS);
			System.arraycopy(o.vz, 0, t.vz, 0, MAX_TENDRILS);
			System.arraycopy(o.amp, 0, t.amp, 0, MAX_TENDRILS);
			System.arraycopy(o.from, 0, t.from, 0, MAX_TENDRILS);
			System.arraycopy(o.to, 0, t.to, 0, MAX_TENDRILS);
			System.arraycopy(o.id, 0, t.id, 0, MAX_TENDRILS);
			return t;
		}

		/** Adds a tendril rooted at ({@code x, y, z}) going out by ({@code dx, dy, dz}) px, rippling with {@code amplitude}, drawn from {@code s0} to {@code s1} of it. */
		void add(float x, float y, float z, float dx, float dy, float dz, float amplitude, float s0, float s1, int index) {
			if (count >= MAX_TENDRILS) return;
			ox[count] = x;
			oy[count] = y;
			oz[count] = z;
			vx[count] = dx;
			vy[count] = dy;
			vz[count] = dz;
			amp[count] = amplitude;
			from[count] = s0;
			to[count] = s1;
			id[count] = index;
			count++;
		}

		/** Points the curve at tendril {@code i} and works out the frame across it: side = dir x up (flat on the ground for a crack), up = side x dir. */
		private boolean aim(int i) {
			k = i;
			float x = vx[i], y = vy[i], z = vz[i];
			lenPx = (float) Math.sqrt(x * x + y * y + z * z);
			if (!(lenPx > 0.3F)) return false;
			x /= lenPx;
			y /= lenPx;
			z /= lenPx;
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
			ux = sy * z - sz * y;
			uy = sz * x - sx * z;
			uz = sx * y - sy * x;
			float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
			if (ul > 1e-6F) {
				ux /= ul;
				uy /= ul;
				uz /= ul;
			}
			return true;
		}

		@Override
		public void at(float s, Vector3f out) {
			// a ripple pinned at both ends, strongest in the middle
			float env = 4F * s * (1F - s);
			float ph = seed + 1.3F * id[k];
			float a = amp[k] * env * (float) Math.sin(ArmMath.TAU * 1.9F * s - 2.2F * age + ph);
			float b = ground ? 0F : 0.5F * amp[k] * env * (float) Math.cos(ArmMath.TAU * 1.5F * s - 1.7F * age + 0.7F * ph);
			float x = ox[k] + vx[k] * s + sx * a + ux * b, y = oy[k] + vy[k] * s + sy * a + uy * b, z = oz[k] + vz[k] * s + sz * a + uz * b;
			if (piece > 0) {
				// a red strand winding round the black tendril, each a share of the turn apart
				float rho = 0.55F * ArmMath.lerp(w0, w1, s);
				float psi = ArmMath.TAU * piece / Math.max(1, redStrands(tier)) + s * lenPx / 4F - 0.5F * age;
				float c = rho * (float) Math.cos(psi), sn = rho * (float) Math.sin(psi);
				x += c * sx + sn * ux;
				y += c * sy + sn * uy;
				z += c * sz + sn * uz;
			}
			out.set(x, ground ? Math.max(0.3F, y) : y, z);
		}

		@Override
		public float at(float s) {
			float w = ArmMath.lerp(w0, w1, s);
			return FistGeometry.width(piece > 0 ? 0.5F * w : w, 4);
		}

		@Override
		public void emit(SkinMesh m) {
			boolean red = layer == ArmLayer.RED;
			int strands = red ? redStrands(tier) : 1;
			for (int i = 0; i < count; i++) {
				if (!aim(i)) continue;
				float s0 = from[i], s1 = to[i];
				if (!(s1 - s0 > 0.01F)) continue;
				int stations = Math.max(4, Math.round(Math.min(18F, lenPx / 6F) * (s1 - s0)) + 2);
				for (int p = 0; p < strands; p++) {
					piece = red ? 1 + p : 0;
					ST.set(time, TendrilFx.ACTIVE, 1F, seed + 7 * id[i] + 131 * piece);
					Loft.tube(m, this, s0, s1, stations, 4, this, layer, layer, ST, part + 3 * i + piece, 1.5F, 0F, lenPx, UP_Y, 1F);
				}
			}
			piece = 0;
		}
	}

	/**
	 * The shock ring on the ground round the impact (or the leap's small crack under the launch), radius in px: a writhing tube with the
	 * crown of teeth standing on it. The black layer draws the ring, the teeth's lower parts and, at level 3, a second ring inside it; the
	 * red layer the teeth's tips and, from level 2, a red ring inside the black one.
	 */
	static final class Ring implements Loft.CurveFn, Loft.RadiusFn, TendrilFx.Geometry {
		ArmLayer layer = ArmLayer.BLACK;
		float time, age, life, radius, max;
		int seed, tier = 1;
		boolean crown;
		/** Your own eye in this piece's px and the clearance round it (as {@link Shards#avoid}): the crown's teeth shrink away near it. 0: none. */
		float camX, camY, camZ, clearPx;
		private float ringR, ringW, wobble;
		private final Vector3f tooth = new Vector3f();
		private Ring twinPiece;

		void reset(float time, int seed, int tier, float age, float life, float radius, float max, boolean crown) {
			this.layer = ArmLayer.BLACK;
			this.time = time;
			this.seed = seed;
			this.tier = tier;
			this.age = age;
			this.life = life;
			this.radius = radius;
			this.max = max;
			this.crown = crown;
			this.clearPx = 0F;
		}

		/** Keeps the crown's teeth off {@code cam} (world), drawn from {@code from}, for your own first-person view. */
		void avoid(Vec3 cam, Vec3 from) {
			camX = (float) ((cam.x - from.x) * PX);
			camY = (float) ((cam.y - from.y) * PX);
			camZ = (float) ((cam.z - from.z) * PX);
			clearPx = (float) (NearCamera.CLEAR * PX);
		}

		Ring copyFor(ArmLayer l) {
			if (twinPiece == null) twinPiece = new Ring();
			Ring t = twinPiece;
			t.reset(time, seed, tier, age, life, radius, max, crown);
			t.camX = camX;
			t.camY = camY;
			t.camZ = camZ;
			t.clearPx = clearPx;
			t.layer = l;
			return t;
		}

		@Override
		public void at(float s, Vector3f out) {
			float a = ArmMath.TAU * s;
			float r = ringR * (1F + wobble * (float) Math.sin(5F * a + 0.3F * time + seed));
			out.set(r * (float) Math.cos(a), 0.5F + 0.5F * (float) Math.sin(7F * a + 0.4F * time), r * (float) Math.sin(a));
		}

		@Override
		public float at(float s) {
			return FistGeometry.width(ringW * (1F + 0.35F * (float) Math.sin(ArmMath.TAU * 3F * s + 0.5F * time + seed)), 4);
		}

		private void tube(SkinMesh m, float r, float w, int part, ArmLayer l) {
			if (!(r > 0.5F) || !(w > 0.05F)) return;
			ringR = r;
			ringW = w;
			wobble = 0.04F;
			int stations = Math.clamp(Math.round(r / 2.2F), 10, 36);
			Loft.closedTube(m, this, stations, 4, this, l, layer, ST, part, 1.5F, 0F, UP_Y, 1F);
		}

		@Override
		public void emit(SkinMesh m) {
			ST.set(time, TendrilFx.ACTIVE, 1F, seed);
			float w = crown ? ringWidth(age) : 1.6F * (1F - ArmMath.sm(age / life)) + 0.2F;
			if (layer == ArmLayer.BLACK) {
				tube(m, radius, w, 150, ArmLayer.BLACK);
				// a third ring inside the outer ones at level 3, a beat behind
				if (crown && tier >= 3) tube(m, shockRadius(age - 2.5F, max) * 0.5F, w * 0.7F, 151, ArmLayer.BLACK);
			} else if (crown && tier >= 2) {
				tube(m, shockRadius(age - 1.5F, max) * 0.75F, w * 0.6F, 152, ArmLayer.RED);
			}
			if (crown) teeth(m);
		}

		/** The crown: teeth of tendril standing on the ring, black to two thirds of their length, then red; they lean out and sway. */
		private void teeth(SkinMesh m) {
			float h = teethHeight(age);
			if (h <= 0.02F || radius < 2F) return;
			int n = Math.min(MAX_TEETH, teethCount(tier));
			float tierK = 0.85F + 0.15F * tier;
			for (int j = 0; j < n; j++) {
				float a = ArmMath.TAU * (j + 0.5F * TendrilFx.hash(seed, j)) / n;
				float len = (3.5F + 3F * TendrilFx.hash(seed + 5, j)) * h * tierK * Math.min(2F, 0.7F + radius / 40F);
				float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
				float lean = 0.5F + 0.25F * (float) Math.sin(0.3F * time + 1.9F * j);
				float rr = radius * (1F + 0.04F * (float) Math.sin(5F * a + 0.3F * time + seed));
				float dx = ca * lean, dy = 1F, dz = sa * lean;
				float inv = 1F / (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
				dx *= inv;
				dy *= inv;
				dz *= inv;
				if (clearPx > 0F) {
					// a tooth whose tip nears your eye (the owner on a ledge, or sneaking at the middle) shrinks away rather than filling the view
					float tx = rr * ca + dx * len - camX, ty = 0.4F + dy * len - camY, tz = rr * sa + dz * len - camZ;
					len *= NearCamera.fade(Math.sqrt(tx * tx + ty * ty + tz * tz), clearPx, NearCamera.BAND * PX);
					if (!(len > 0.05F)) continue;
				}
				if (layer == ArmLayer.BLACK) {
					spike(m, rr * ca, 0.4F, rr * sa, dx, dy, dz, 0.66F * len, 1.3F * h + 0.2F, 0.7F * h + 0.1F, 160, j);
				} else {
					spike(m, rr * ca + dx * 0.66F * len, 0.4F + dy * 0.66F * len, rr * sa + dz * 0.66F * len, dx, dy, dz, 0.36F * len, 0.7F * h + 0.1F, 0.1F, 161, j);
				}
			}
		}
	}

	/** A tapered spike from ({@code x, y, z}) along a unit direction, {@code len} px long, {@code w0} wide to {@code w1}. */
	private static void spike(SkinMesh m, float x, float y, float z, float dx, float dy, float dz, float len, float w0, float w1, int part, int seg) {
		if (!(len > 0.05F)) return;
		TURN.rotationTo(0F, 0F, 1F, dx, dy, dz);
		LOCAL.translation(x, y, z).rotate(TURN);
		m.frustum(LOCAL, len, w0, w0, w1, w1, ST.u0(part, seg, 0F), ST.v0(part, seg, 0F), 2F, 1F, 1F, SkinMesh.WHITE);
	}

	/**
	 * Shards of tendril thrown up and out of a point, pointing along their flight, falling under gravity and shrinking away. The black layer draws
	 * the shards' lower parts, the red layer their tips. {@code wound}: thrown from a creature's body, in every direction (the landing's
	 * burst keeps to the ground's upper half and lands on it). For the leap the same piece throws clods of earth, small and short-lived, rather
	 * than shards ({@link #clod}).
	 */
	static final class Shards implements TendrilFx.Geometry {
		ArmLayer layer = ArmLayer.BLACK;
		float time, age, speed, spawnRadius;
		int seed, tier = 1, count;
		boolean clod, wound;
		/**
		 * Your own eye in this piece's px (from the point it is drawn at) and how close a shard may come to it ({@link NearCamera}): one that
		 * flew past the lens filled the view with a red slab for ~3 frames (the rig, 2026-11-16, first person). {@code clearPx} 0: nothing is kept off.
		 */
		float camX, camY, camZ, clearPx;
		private Shards twinPiece;

		/** Keeps this piece's shards off {@code cam} (world), drawn from {@code from}, for your own first-person view. */
		void avoid(Vec3 cam, Vec3 from) {
			camX = (float) ((cam.x - from.x) * PX);
			camY = (float) ((cam.y - from.y) * PX);
			camZ = (float) ((cam.z - from.z) * PX);
			clearPx = (float) (NearCamera.CLEAR * PX);
		}

		void reset(float time, int seed, int tier, float age, int count, float speed, float spawnRadius, boolean clod) {
			this.layer = ArmLayer.BLACK;
			this.time = time;
			this.seed = seed;
			this.tier = tier;
			this.age = age;
			this.count = Math.min(count, MAX_SHARDS);
			this.speed = speed;
			this.spawnRadius = spawnRadius;
			this.clod = clod;
			this.wound = false;
			this.clearPx = 0F;
		}

		Shards copyFor(ArmLayer l) {
			if (twinPiece == null) twinPiece = new Shards();
			Shards t = twinPiece;
			t.reset(time, seed, tier, age, count, speed, spawnRadius, clod);
			t.wound = wound;
			t.camX = camX;
			t.camY = camY;
			t.camZ = camZ;
			t.clearPx = clearPx;
			t.layer = l;
			return t;
		}

		@Override
		public void emit(SkinMesh m) {
			ST.set(time, TendrilFx.ACTIVE, 0F, seed);
			for (int j = 0; j < count; j++) {
				float t = age - 0.3F * TendrilFx.hash(seed + 2, j); // each leaves a little after the others
				if (t <= 0F) continue;
				float size = shardScale(t * (clod ? 1.7F : 1F));
				if (!(size > 0.03F)) continue;
				float a = ArmMath.TAU * TendrilFx.hash(seed + 60, j);
				float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
				float side = speed * SHARD_SIDE * (0.45F + 0.55F * TendrilFx.hash(seed + 3, j));
				float lift = speed * (SHARD_LIFT + SHARD_LIFT_SPREAD * TendrilFx.hash(seed + 9, j)) * (clod ? 0.7F : 1F);
				float r0 = spawnRadius * (0.6F + 0.4F * TendrilFx.hash(seed + 11, j));
				float x = r0 * ca + side * ca * t, z = r0 * sa + side * sa * t;
				float vy;
				float y;
				if (wound) {
					// out of a body: any direction, a lighter fall
					float ey = 2F * TendrilFx.hash(seed + 13, j) - 1F;
					y = 0.5F * lift * ey * t - 0.25F * SHARD_GRAVITY * t * t;
					vy = 0.5F * lift * ey - 0.5F * SHARD_GRAVITY * t;
				} else {
					y = shardHeight(lift, t);
					vy = y <= 0F ? 0F : lift - SHARD_GRAVITY * t;
				}
				float vx = side * ca, vz = side * sa;
				float len = Math.max(0.01F, (float) Math.sqrt(vx * vx + vy * vy + vz * vz));
				if (clearPx > 0F) {
					// a shard shrinks away as it nears your eye and is gone inside the clearance, rather than filling the view
					float dx = x - camX, dy = y - camY, dz = z - camZ;
					size *= NearCamera.fade(Math.sqrt(dx * dx + dy * dy + dz * dz), clearPx, NearCamera.BAND * PX);
					if (!(size > 0.03F)) continue;
				}
				float body = (clod ? 2.2F : 3.6F + 2.4F * TendrilFx.hash(seed + 5, j)) * size;
				float w = (clod ? 1.4F : 1.15F) * size;
				if (layer == ArmLayer.BLACK) {
					if (clod) {
						float half = w / 2F;
						m.box(null, x - half, y - half, z - half, w, w, w, ST.u0(170, j, 0F), ST.v0(170, j, 0F), 1F, 1F, SkinMesh.WHITE);
					} else {
						spike(m, x, y, z, vx / len, vy / len, vz / len, 0.64F * body, w, 0.6F * w, 180, j);
					}
				} else if (!clod) {
					float l0 = 0.64F * body;
					spike(m, x + vx / len * l0, y + vy / len * l0, z + vz / len * l0, vx / len, vy / len, vz / len, 0.36F * body, 0.6F * w, 0.1F, 181, j);
				}
			}
		}
	}
}
