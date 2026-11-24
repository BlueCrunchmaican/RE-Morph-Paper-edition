package dev.morphreborn.client.arm.fx;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmTips;
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
import org.jspecify.annotations.Nullable;

/**
 * The spear's Impale (S_ARM_FX kind 15, FX_SPEAR; my design, 2026-11-16). The server sends it in three messages, by
 * {@code arg} (the contract I fixed after review, 2026-11-16; paper-plugin SpearImpale, docs/PROTOCOL.md):
 * <ul>
 * <li>{@link #FX_LUNGE} (1), when F is paid: {@code level} the tier, {@code ticks} the lunge's length, {@code ids} the creature,
 * {@code positions} the feet's start and end blocks. Only the lunge's TRAIL is drawn from it: nothing has landed yet.</li>
 * <li>{@link #FX_PIN} (2), when the skewer landed: {@code ticks} how long the spikes hold ({@code 0} = skewered but not held, so they
 * only break through and withdraw), {@code ids} the creature, {@code positions} the one block under its feet. The cage, the skewer
 * and the impact are timed from receipt of this message, the spikes standing on the top of that block.</li>
 * <li>{@link #FX_RELEASE} (3), a pin cut short (the creature died or left): {@code ids} the creature; its spikes sink at once.</li>
 * </ul>
 * Three things are drawn, all in the owner's colour and the arm's own braided black tendril with red static:
 * <ul>
 * <li><b>The lunge's trail.</b> A stretched tendril from where the spear was to where it is, thin at its tail and fat at the head,
 * a ripple running down it, streak hairs flying back, its tail eaten forward to the spear over 7 ticks. It follows the spear's tip
 * as the arm renderer drew it, so the owner's own first-person view and everyone else's third-person one agree.</li>
 * <li><b>The cage.</b> At the pin point a ring of black tendril SPIKES erupts out of the ground round the creature (6, 8 or 10 by
 * tier), overshoots and settles, and its tips lean in and close over it, twisting as they do. Every spike has its own height, a red
 * tip, and at the higher tiers spurs; level 2 adds a red ring in the ground, level 3 a second, shorter ring of spikes inside the
 * first. A ring of dirt-black beads is thrown up as it erupts. It stays for the pin's {@code ticks}, then the spikes draw back into
 * the ground. This is not the whip's snare: nothing coils, the creature is held from below by what comes up round it.</li>
 * <li><b>The skewer.</b> While it holds, a short taut tendril joins the spear's tip to the creature's body, the spear through it. It
 * needs the tip as the arm renderer drew it this frame: with the arm not drawn (the owner swapped item, is far off or out of view)
 * there is no line, never one from an estimate of where the eyes are.</li>
 * </ul>
 * Allocation: every frame's pieces are pooled ({@link Strand}, {@link Ring}, {@link Debris}, the whip's hairs) and reused; a frame's
 * collect resets the pools, so nothing here survives into the next. A move's own points are kept and rebuilt only when they change.
 */
public final class SpearFx implements FxHandler {
	/** {@code arg}: the lunge (its trail), the skewer landing (the cage, skewer and impact) and a pin cut short. The server's SpearImpale.FX_* . */
	public static final int FX_LUNGE = 1, FX_PIN = 2, FX_RELEASE = 3;
	private static final float PX = 16F;
	/** The rise's length, the overshoot and the sink (all from the pin message's receipt: the blow has landed, so the spikes come at once). */
	static final float ERUPT = 2.5F, SETTLE = 1.5F, SINK = 6F, OVERSHOOT = 1.18F;
	/** The skewer's reach out, and the impact's, in ticks from receipt. */
	static final float SKEWER_OUT = 1.5F, IMPACT_LIFE = 4F;
	/** The trail's life. */
	static final float TRAIL_LIFE = 7F;
	/** The most effects drawn at once, spikes one ring holds, and beads of dirt thrown up. */
	private static final int MAX_MOVES = 6, MAX_SPIKES = 12, BEADS = 8;
	/** The widest a ring's spike gets at its root (px) by tier, and the thread at its tip. */
	private static final float[] SPIKE_ROOT = {0F, 3.0F, 3.4F, 3.8F};
	private static final float SPIKE_TIP = 0.3F;
	/** Where the red tip of a spike starts along it. */
	private static final float RED_FROM = 0.74F;

	/** One effect: a lunge's trail ({@link #FX_LUNGE}) or a pin's cage with its skewer and impact ({@link #FX_PIN}). */
	static final class Move {
		final int arg, origin, tier, target, seed;
		final long start;
		/** The pin's hold in ticks: as the server named it (bounded), cut shorter by a release. */
		int hold;
		/** The trail's start (where the spear was when the effect came); null when it could not be found. */
		@Nullable Vec3 from;
		/** The cage's centre (a creature's own place replaces it each frame while it is there) and the spikes' top above it; rebuilt only when they move. */
		@Nullable Vec3 base, top;
		float width = 0.6F, height = 1.8F;

		Move(int arg, int origin, int tier, int target, int seed, int hold, long start) {
			this.arg = arg;
			this.origin = origin;
			this.tier = tier;
			this.target = target;
			this.seed = seed;
			this.hold = hold;
			this.start = start;
		}

		/** The effect's life from receipt, in ticks. */
		float life() {
			return arg == FX_LUNGE ? TRAIL_LIFE : SpearFx.life(hold);
		}

		/** The cage stands at ({@code x}, {@code y}, {@code z}) for a creature {@code w} wide and {@code h} tall. */
		void stand(double x, double y, double z, float w, float h) {
			Vec3 b = base;
			if (b == null || b.x != x || b.y != y || b.z != z || h != height) {
				base = new Vec3(x, y, z);
				top = new Vec3(x, y + spikeHeight(h), z);
			}
			width = w;
			height = h;
		}
	}

	private final List<Move> moves = new ArrayList<>();

	private static final Static ST = new Static();
	private static final Quaternionf TURN = new Quaternionf();
	private static final Matrix4f LOCAL = new Matrix4f();
	private static final Vector3f UP_X = new Vector3f(1F, 0F, 0F), UP_Y = new Vector3f(0F, 1F, 0F);

	private static final List<Strand> STRANDS = new ArrayList<>();
	private static final List<Ring> RINGS = new ArrayList<>();
	private static final List<Debris> DEBRIS = new ArrayList<>();
	private static final List<WhipMoveFx.Hairs> HAIRS = new ArrayList<>();
	private static int strandsUsed, ringsUsed, debrisUsed, hairsUsed;

	public SpearFx() {}

	@Override
	public int[] kinds() {
		return new int[] {ArmProtocol.FX_SPEAR};
	}

	// ------------------------------------------------------------------ timelines (pure, tested)

	/** How many ticks the pin holds, as the server named it: bounded so a bad packet can't leave spikes standing. */
	static int hold(int ticks) {
		return Math.clamp(ticks, 4, 100);
	}

	/** The pin's life from receipt: the hold, then the sink. */
	static float life(int hold) {
		return hold + SINK;
	}

	/** Spikes in the outer ring, by tier (6, 8, 10). */
	static int spikeCount(int tier) {
		return 4 + 2 * Math.clamp(tier, 1, 3);
	}

	/** Spikes in the inner ring: level 3 only, half the outer's. */
	static int innerCount(int tier) {
		return tier >= 3 ? spikeCount(tier) / 2 : 0;
	}

	/** Spurs on each spike: none, one, two by tier. */
	static int spurCount(int tier) {
		return Math.clamp(tier, 1, 3) - 1;
	}

	/** The ring's radius in blocks for a creature {@code width} wide: its own half, a third of a block clear of it. */
	static float ringRadius(float width) {
		return Math.clamp(0.5F * width + 0.3F, 0.4F, 2.6F);
	}

	/** The spikes' height in blocks for a creature {@code height} tall: over its head. */
	static float spikeHeight(float height) {
		return Math.clamp(0.95F * height + 0.35F, 0.8F, 4.5F);
	}

	/**
	 * How far up a spike is, {@code age} ticks after it started (0 on the ground; a little over 1 at the top of its overshoot, 1 held,
	 * 0 again once it has sunk): out over {@link #ERUPT} with an overshoot that settles over {@link #SETTLE}, held for {@code hold}
	 * ticks from the start, then drawn back over {@link #SINK}.
	 */
	static float rise(float age, float hold) {
		if (age <= 0F) return 0F;
		float up;
		if (age < ERUPT) {
			float x = 1F - age / ERUPT;
			up = OVERSHOOT * (1F - x * x * x);
		} else if (age < ERUPT + SETTLE) {
			up = ArmMath.lerp(OVERSHOOT, 1F, ArmMath.sm((age - ERUPT) / SETTLE));
		} else {
			up = 1F;
		}
		if (age <= hold) return up;
		return up * (1F - ArmMath.sm((age - hold) / SINK));
	}

	/** How far the tips have leaned in over what is held, 0..1: closing over {@link #ERUPT} plus 3 ticks, easing out as the spikes sink. */
	static float close(float age, int hold) {
		float c = ArmMath.sm((age - 0.8F) / (ERUPT + 3F));
		return age <= hold ? c : c * (1F - ArmMath.sm((age - hold) / SINK));
	}

	/**
	 * Each spike starts a little after the one before, round the ring: stepping round by the smallest odd number from 3 that shares no
	 * factor with the count, so every slot is used once and neighbours don.t rise together (a spiral, not a line).
	 */
	static float stagger(int k, int n) {
		int step = 3;
		while (gcd(step, n) != 1) step += 2;
		return 0.9F * ((k * step) % n) / n;
	}

	private static int gcd(int a, int b) {
		return b == 0 ? a : gcd(b, a % b);
	}

	/** How much of the trail's tail has been eaten toward the spear, 0..1: it starts going at tick 0.8 and is gone by {@link #TRAIL_LIFE}. */
	static float trailEaten(float age) {
		return ArmMath.sm((age - 0.8F) / (TRAIL_LIFE - 0.8F));
	}

	/** The trail's thickness at the head as it fades, 1 down to 0.5. */
	static float trailThickness(float age) {
		return 1F - 0.5F * ArmMath.sm(age / TRAIL_LIFE);
	}

	/** The skewer's reach: out over {@link #SKEWER_OUT} from the pin's receipt, held for the pin, drawn back as the spikes sink (the same clock as theirs). */
	static float skewerReach(float age, int hold) {
		float out = ArmMath.sm(age / SKEWER_OUT);
		return age <= hold ? out : out * (1F - ArmMath.sm((age - hold) / SINK));
	}

	// ------------------------------------------------------------------ the effect's life

	@Override
	public void start(ArmFxEvent e) {
		switch (e.arg()) {
			case FX_LUNGE -> lunge(e, ArmFx.anchor(e.origin(), ArmTips.TIP));
			case FX_PIN -> pin(e);
			case FX_RELEASE -> release(e);
			default -> {
				// a newer server's move: nothing to draw
			}
		}
	}

	/** The lunge's trail only: the cage waits for the blow. */
	void lunge(ArmFxEvent e, @Nullable Vec3 from) {
		Move m = new Move(FX_LUNGE, e.origin(), Math.clamp(e.level(), 1, 3), e.ids().length > 0 ? e.ids()[0] : -1, (e.seed() ^ e.origin() * 31) & 0x7FFF, 0, e.receivedTick());
		if (from == null || !TendrilFx.finite(from)) return; // no arm to draw a trail from
		m.from = from;
		add(m);
	}

	/** The blow landed: spikes stand round the creature from now, for the hold the server named. */
	private void pin(ArmFxEvent e) {
		int target = e.ids().length > 0 ? e.ids()[0] : -1;
		if (e.positions().length == 0 && target < 0) return; // nowhere to draw it
		Move m = new Move(FX_PIN, e.origin(), Math.clamp(e.level(), 1, 3), target, (e.seed() ^ e.origin() * 31) & 0x7FFF, hold(e.ticks()), e.receivedTick());
		if (e.positions().length > 0) {
			// the server names the block UNDER the creature's feet: the spikes stand on its top. (A creature this client has loaded replaces
			// this with its own place each frame.)
			Vec3 at = Vec3.atBottomCenterOf(BlockPos.of(e.positions()[0]).above());
			if (TendrilFx.finite(at)) m.stand(at.x, at.y, at.z, m.width, m.height);
		}
		add(m);
	}

	/** A pin ended early: its spikes sink at once, from where they have got to. */
	private void release(ArmFxEvent e) {
		int target = e.ids().length > 0 ? e.ids()[0] : -1;
		for (int i = 0; i < moves.size(); i++) {
			Move m = moves.get(i);
			if (m.arg != FX_PIN || m.origin != e.origin() || target >= 0 && m.target != target) continue;
			int age = (int) Math.max(1, Math.min(m.hold, e.receivedTick() - m.start));
			m.hold = age;
		}
	}

	/** The effects alive, for the tests. */
	int moveCount() {
		return moves.size();
	}

	Move move(int i) {
		return moves.get(i);
	}

	private void add(Move m) {
		if (moves.size() >= MAX_MOVES) moves.remove(0);
		moves.add(m);
	}

	@Override
	public void tick() {
		long now = Arms.TICK;
		for (int i = moves.size() - 1; i >= 0; i--) {
			Move m = moves.get(i);
			if (now - m.start > m.life() + 1) moves.remove(i);
		}
	}

	@Override
	public void clear() {
		moves.clear();
		strandsUsed = 0;
		ringsUsed = 0;
		debrisUsed = 0;
		hairsUsed = 0;
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void collect(ArmFxFrame f) {
		strandsUsed = 0;
		ringsUsed = 0;
		debrisUsed = 0;
		hairsUsed = 0;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || moves.isEmpty()) return;
		for (int i = 0; i < moves.size(); i++) {
			Move m = moves.get(i);
			float age = Arms.age(m.start, f.partialTick());
			if (age < 0F || age > m.life()) continue;
			ArmTint.owner(m.origin); // each is drawn in its owner's colour
			if (m.arg == FX_LUNGE) {
				trail(f, level, m, age);
				continue;
			}
			Entity target = m.target >= 0 ? level.getEntity(m.target) : null;
			if (target != null && target.isRemoved()) target = null;
			Vec3 wound = null;
			if (target != null) {
				// the creature is held where it stands: the cage keeps its place when it is killed or goes out of range
				float pt = f.partialTick();
				double x = Mth.lerp((double) pt, target.xOld, target.getX()), y = Mth.lerp((double) pt, target.yOld, target.getY()),
						z = Mth.lerp((double) pt, target.zOld, target.getZ());
				if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
					m.stand(x, y, z, target.getBbWidth(), target.getBbHeight());
					wound = TendrilFx.wound(target, pt);
				}
			}
			cage(f, level, m, age);
			// the spear's own tip as drawn this frame, or none: with the arm not drawn there is nothing to join the creature to
			Vec3 tip = drawnTip(m.origin);
			if (wound != null && tip != null) skewer(f, level, m, wound, tip, age);
			impact(f, level, m, wound, tip, age);
		}
	}

	/** The spear's tip as the arm renderer recorded it this frame (the owner's first-person hand pass, else the body's), null when the arm wasn't drawn. */
	private static @Nullable Vec3 drawnTip(int origin) {
		Vec3 tip = ArmTips.get(origin, true, ArmTips.TIP);
		if (tip == null) tip = ArmTips.get(origin, false, ArmTips.TIP);
		return tip != null && TendrilFx.finite(tip) ? tip : null;
	}

	private static Strand strand() {
		if (strandsUsed == STRANDS.size()) STRANDS.add(new Strand());
		return STRANDS.get(strandsUsed++);
	}

	private static Ring ring() {
		if (ringsUsed == RINGS.size()) RINGS.add(new Ring());
		return RINGS.get(ringsUsed++);
	}

	private static Debris debris() {
		if (debrisUsed == DEBRIS.size()) DEBRIS.add(new Debris());
		return DEBRIS.get(debrisUsed++);
	}

	private static WhipMoveFx.Hairs hairs() {
		if (hairsUsed == HAIRS.size()) HAIRS.add(new WhipMoveFx.Hairs());
		return HAIRS.get(hairsUsed++);
	}

	/** The red strands twisting round a black tendril: none for iron, one for diamond, two for netherite, as the whip's. */
	private static int strands(int tier) {
		return Math.max(0, tier - 1);
	}

	// ------------------------------------------------------------------ the lunge's trail

	private static void trail(ArmFxFrame f, ClientLevel level, Move m, float age) {
		Vec3 from = m.from;
		if (from == null || age > TRAIL_LIFE) return;
		Vec3 tip = ArmFx.anchor(m.origin, ArmTips.TIP);
		if (tip == null || !TendrilFx.finite(tip)) return;
		double dx = tip.x - from.x, dy = tip.y - from.y, dz = tip.z - from.z;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 0.4 || !Double.isFinite(len)) return;
		float eaten = trailEaten(age);
		if (eaten >= 0.995F) return;
		float thick = trailThickness(age);
		int light = TendrilFx.light(level, tip, tip);
		Strand black = strand();
		black.reset(ArmLayer.BLACK, f.time(), m.seed, age);
		black.aim((float) dx * PX, (float) dy * PX, (float) dz * PX);
		black.s0 = eaten;
		black.s1 = 1F;
		// thin at the tail, fat at the spear: stretched out behind it
		black.w0 = 0.3F;
		black.w1 = 1.9F * thick;
		black.amp = 0.14F * PX * Math.min(1F, (float) len / 5F);
		black.stations = Math.max(4, Math.round(Math.min(26F, (float) len * 3F) * (1F - eaten)) + 2);
		black.part = 80;
		TendrilFx.submit(f, from, ArmLayer.BLACK, light, black);
		int n = strands(m.tier);
		for (int k = 0; k < n; k++) {
			Strand r = strand().copy(black);
			r.layer = ArmLayer.RED;
			r.piece = 1 + k;
			r.phase = ArmMath.TAU * k / n;
			r.part = 82 + k;
			TendrilFx.submit(f, from, ArmLayer.RED, light, r);
		}
		// streak hairs flying back off the spear's head along the trail
		WhipMoveFx.Hairs h = hairs();
		h.begin(f.time(), m.seed + 5);
		float back = 1F - ArmMath.sm(age / TRAIL_LIFE);
		float nx = (float) (-dx / len), ny = (float) (-dy / len), nz = (float) (-dz / len);
		for (int i = 0; i < 8; i++) {
			float along = 0.55F + 0.45F * TendrilFx.hash(m.seed, i);
			float spin = ArmMath.TAU * TendrilFx.hash(m.seed + 1, i);
			float out = 0.25F * TendrilFx.hash(m.seed + 2, i);
			h.add((float) dx * PX * along, (float) dy * PX * along, (float) dz * PX * along, nx + out * (float) Math.cos(spin), ny + out * (float) Math.sin(spin),
					nz + out * (float) Math.cos(spin + 1.7F), 9F * back * (0.6F + 0.8F * TendrilFx.hash(m.seed + 3, i)), 0.4F * back);
		}
		TendrilFx.submit(f, from, ArmLayer.BLACK, light, h);
	}

	// ------------------------------------------------------------------ the cage

	private static void cage(ArmFxFrame f, ClientLevel level, Move m, float age) {
		Vec3 base = m.base, top = m.top;
		if (base == null || top == null || !TendrilFx.finite(base)) return;
		float rise = rise(age, m.hold);
		if (rise <= 0.005F) return;
		float r0 = ringRadius(m.width) * PX;
		float h = spikeHeight(m.height) * PX;
		float closeBy = close(age, m.hold);
		int light = TendrilFx.light(level, base, top);
		int tier = m.tier;
		Ring black = ring();
		black.reset(ArmLayer.BLACK, f.time(), m.seed, tier, spikeCount(tier), innerCount(tier), r0, h, age, m.hold, closeBy);
		TendrilFx.submit(f, base, ArmLayer.BLACK, light, black);
		Ring red = ring().copy(black);
		red.layer = ArmLayer.RED;
		TendrilFx.submit(f, base, ArmLayer.RED, light, red);
		// the beads of earth thrown up as the ring erupts
		if (age < 6F) {
			Debris d = debris();
			d.reset(f.time(), m.seed, age, r0);
			TendrilFx.submit(f, base, ArmLayer.BLACK, light, d);
		}
	}

	// ------------------------------------------------------------------ the impact

	/**
	 * The spear going home: a clenched red knot at the point it landed (the creature's body, or the middle of the cage when the creature
	 * isn't on this client) and, while the arm is drawn, a spray of black hairs thrown back toward the spear, over 4 ticks from receipt.
	 */
	private static void impact(ArmFxFrame f, ClientLevel level, Move m, @Nullable Vec3 wound, @Nullable Vec3 tip, float age) {
		float hu = age / IMPACT_LIFE;
		if (hu < 0F || hu > 1F) return;
		Vec3 at = wound;
		if (at == null && m.base != null) at = m.base.add(0, 0.5 * m.height, 0);
		if (at == null || !TendrilFx.finite(at)) return;
		int light = TendrilFx.light(level, tip != null ? tip : at, at);
		if (tip != null) {
			double bx = tip.x - at.x, by = tip.y - at.y, bz = tip.z - at.z;
			double bl = Math.sqrt(bx * bx + by * by + bz * bz);
			if (bl > 1e-4) {
				bx /= bl;
				by /= bl;
				bz /= bl;
				WhipMoveFx.Hairs h = hairs();
				h.begin(f.time(), m.seed + 4);
				for (int k = 0; k < 7; k++) {
					double spin = ArmMath.TAU * TendrilFx.hash(m.seed, 20 + k);
					double lift = 0.4 + 0.6 * TendrilFx.hash(m.seed + 3, 20 + k);
					h.add(0F, 0F, 0F, (float) (bx * 0.6 + Math.cos(spin) * lift), (float) (by * 0.6 + 0.3 + Math.sin(spin) * lift * 0.6),
							(float) (bz * 0.6 + Math.sin(spin) * lift), 5F * (1F - hu) * (0.7F + 0.6F * TendrilFx.hash(m.seed + 9, 20 + k)), 0.4F * (1F - 0.7F * hu));
				}
				TendrilFx.submit(f, at, ArmLayer.BLACK, light, h);
			}
		}
		if (hu < 0.5F) TendrilFx.knot(f, at, 3F * (1F - 2F * hu), ArmLayer.RED, light, m.seed);
	}

	// ------------------------------------------------------------------ the skewer

	private static void skewer(ArmFxFrame f, ClientLevel level, Move m, Vec3 to, Vec3 tip, float age) {
		float reach = skewerReach(age, m.hold);
		if (!(reach > 0.01F)) return;
		if (!TendrilFx.finite(to)) return;
		double dx = to.x - tip.x, dy = to.y - tip.y, dz = to.z - tip.z;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 0.05 || !Double.isFinite(len)) return;
		int light = TendrilFx.light(level, tip, to);
		Strand black = strand();
		black.reset(ArmLayer.BLACK, f.time(), m.seed, age);
		black.aim((float) dx * PX, (float) dy * PX, (float) dz * PX);
		black.s0 = 0F;
		black.s1 = reach;
		black.w0 = 1.6F;
		black.w1 = 1.0F;
		black.amp = 0.03F * PX; // taut: it only trembles
		black.stations = 5;
		black.part = 90;
		TendrilFx.submit(f, tip, ArmLayer.BLACK, light, black);
		int n = strands(m.tier);
		for (int k = 0; k < n; k++) {
			Strand r = strand().copy(black);
			r.layer = ArmLayer.RED;
			r.piece = 1 + k;
			r.phase = ArmMath.TAU * k / n;
			r.part = 92 + k;
			TendrilFx.submit(f, tip, ArmLayer.RED, light, r);
		}
	}

	// ------------------------------------------------------------------ the pooled pieces

	/**
	 * One straight tendril from a point along a vector (px), a ripple running down it, black or a red strand twisting round it. It is
	 * its own curve and radius function and its own geometry, so a frame's pieces are reused, not allocated.
	 */
	static final class Strand implements Loft.CurveFn, Loft.RadiusFn, TendrilFx.Geometry {
		ArmLayer layer = ArmLayer.BLACK;
		float time, age, phase, amp, w0 = 1F, w1 = 1F, s0, s1 = 1F, lenPx;
		int seed, piece, part = 80, stations = 8;
		private float dx, dy, dz, sx = 1F, sy, sz, ux, uy = 1F, uz;

		void reset(ArmLayer layer, float time, int seed, float age) {
			this.layer = layer;
			this.time = time;
			this.seed = seed;
			this.age = age;
			phase = 0F;
			amp = 0F;
			w0 = 1F;
			w1 = 1F;
			s0 = 0F;
			s1 = 1F;
			piece = 0;
			part = 80;
			stations = 8;
		}

		Strand copy(Strand o) {
			layer = o.layer;
			time = o.time;
			age = o.age;
			phase = o.phase;
			amp = o.amp;
			w0 = o.w0;
			w1 = o.w1;
			s0 = o.s0;
			s1 = o.s1;
			lenPx = o.lenPx;
			seed = o.seed;
			piece = o.piece;
			part = o.part;
			stations = o.stations;
			dx = o.dx;
			dy = o.dy;
			dz = o.dz;
			sx = o.sx;
			sy = o.sy;
			sz = o.sz;
			ux = o.ux;
			uy = o.uy;
			uz = o.uz;
			return this;
		}

		/** Points it along ({@code x, y, z}) px and works out the frame across it: side = dir x up, up = side x dir. */
		void aim(float x, float y, float z) {
			dx = x;
			dy = y;
			dz = z;
			lenPx = (float) Math.sqrt(x * x + y * y + z * z);
			float l = Math.max(1e-6F, lenPx);
			x /= l;
			y /= l;
			z /= l;
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
		}

		@Override
		public void at(float s, Vector3f out) {
			// a ripple running toward the head, strongest in the middle
			float env = 4F * s * (1F - s);
			float a = amp * env * (float) Math.sin(ArmMath.TAU * 2.2F * s - 1.7F * age + seed);
			float b = 0.5F * amp * env * (float) Math.cos(ArmMath.TAU * 1.6F * s - 1.3F * age + 0.7F * seed);
			float x = dx * s + sx * a + ux * b, y = dy * s + sy * a + uy * b, z = dz * s + sz * a + uz * b;
			if (piece > 0) {
				float rho = 0.55F * ArmMath.lerp(w0, w1, s);
				float psi = phase + s * lenPx / 4F - 0.5F * age;
				float c = rho * (float) Math.cos(psi), sn = rho * (float) Math.sin(psi);
				x += c * sx + sn * ux;
				y += c * sy + sn * uy;
				z += c * sz + sn * uz;
			}
			out.set(x, y, z);
		}

		@Override
		public float at(float s) {
			float w = ArmMath.lerp(w0, w1, s);
			return FistGeometry.width(piece > 0 ? 0.5F * w : w, 4);
		}

		@Override
		public void emit(SkinMesh m) {
			if (!(s1 - s0 > 0.01F)) return;
			ST.set(time, TendrilFx.ACTIVE, 1F, seed + (piece > 0 ? 1 + part : 0));
			Loft.tube(m, this, s0, s1, stations, 4, this, layer, layer, ST, part, 1.5F, 0F, lenPx, UP_Y, 1F);
		}
	}

	/**
	 * The cage: a ring of spikes standing out of the ground, centred on the pin point, in px from it. A spike is a tapering tube whose
	 * foot is on the ring and whose tip leans in as the cage closes; it grows by truncation (a tube from the ground to however high it
	 * has come) and its own overshoot lengthens it. The black layer draws the spikes, the spurs and the ring in the ground; the red layer
	 * the tips, and the red ring at the higher tiers.
	 */
	static final class Ring implements Loft.CurveFn, Loft.RadiusFn, TendrilFx.Geometry {
		ArmLayer layer = ArmLayer.BLACK;
		float time, r0, h, age, closeBy;
		int seed, tier, outer, inner, hold;
		// the spike being drawn
		private int k;
		private float az, hk, rtip, twist, rootR, rootW, grow;
		private boolean innerRing;

		void reset(ArmLayer layer, float time, int seed, int tier, int outer, int inner, float r0, float h, float age, int hold, float closeBy) {
			this.layer = layer;
			this.time = time;
			this.seed = seed;
			this.tier = tier;
			this.outer = Math.min(outer, MAX_SPIKES);
			this.inner = Math.min(inner, MAX_SPIKES);
			this.r0 = r0;
			this.h = h;
			this.age = age;
			this.hold = hold;
			this.closeBy = closeBy;
		}

		Ring copy(Ring o) {
			reset(o.layer, o.time, o.seed, o.tier, o.outer, o.inner, o.r0, o.h, o.age, o.hold, o.closeBy);
			return this;
		}

		/** Points the spike curve at spike {@code index} of the outer ring or (inner) the inner one; false when it hasn't started. */
		private boolean aimSpike(int index, boolean innerSpike) {
			int n = innerSpike ? inner : outer;
			k = index;
			innerRing = innerSpike;
			float delay = stagger(index, Math.max(1, n)) + (innerSpike ? 0.6F : 0F);
			// each starts a little late but every one sinks away together, so the ring is gone by the effect's end
			float r = rise(age - delay, hold - delay);
			if (r <= 0.005F) return false;
			grow = r;
			float rnd = TendrilFx.hash(seed, index + (innerSpike ? 40 : 0));
			float ring = innerSpike ? 0.55F * r0 : r0;
			float height = h * (innerSpike ? 0.5F : 1F) * (0.82F + 0.34F * rnd);
			hk = height * Math.max(1F, r);
			float c = closeBy * (0.9F + 0.1F * rnd);
			rtip = ring * (1F - 0.85F * c);
			rootR = ring;
			twist = (innerSpike ? -1F : 1F) * 0.7F * c;
			// the outer ring is turned a half-step from the inner, so the inner spikes stand between the outer ones
			az = ArmMath.TAU * index / n + (innerSpike ? ArmMath.PI / n : 0F) + 0.9F * TendrilFx.hash(seed + 7, 0);
			rootW = SPIKE_ROOT[Math.clamp(tier, 1, 3)] * (innerSpike ? 0.7F : 1F) * (0.9F + 0.2F * rnd);
			return true;
		}

		/** Spike point at {@code s} (0 at the ground, 1 at the tip), px from the ring's centre. */
		private void spikePoint(float s, Vector3f out) {
			float rad = rootR + (rtip - rootR) * (float) Math.pow(s, 1.7);
			float a = az + twist * s;
			float wob = 0.55F * (float) Math.sin(8F * s + 1.3F * k - 0.4F * time + seed) * s * (1F - 0.6F * closeBy);
			float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
			out.set(rad * ca - wob * sa, hk * s, rad * sa + wob * ca);
		}

		@Override
		public void at(float s, Vector3f out) {
			spikePoint(s, out);
		}

		@Override
		public float at(float s) {
			return FistGeometry.width(ArmMath.lerp(rootW, SPIKE_TIP, (float) Math.pow(s, 0.7)), 4);
		}

		/** The spike's own fraction (0..1) that is out of the ground: by truncation. */
		private float out() {
			return Math.min(1F, grow);
		}

		@Override
		public void emit(SkinMesh m) {
			ST.set(time, TendrilFx.ACTIVE, 1F, seed);
			boolean black = layer == ArmLayer.BLACK;
			for (int pass = 0; pass < (inner > 0 ? 2 : 1); pass++) {
				boolean innerPass = pass == 1;
				int n = innerPass ? inner : outer;
				for (int i = 0; i < n; i++) {
					if (!aimSpike(i, innerPass)) continue;
					float s1 = out();
					int part = 100 + 2 * i + (innerPass ? 30 : 0);
					if (black) {
						float end = Math.min(s1, RED_FROM + 0.04F);
						Loft.tube(m, this, 0F, end, Math.max(3, Math.round(8 * end) + 1), 4, this, layer, layer, ST, part, 1.5F, 0F, hk, UP_X, 1F);
						spurs(m, i, s1);
					} else if (s1 > RED_FROM) {
						Loft.tube(m, this, RED_FROM, s1, 3, 4, this, layer, layer, ST, part + 1, 2F, 0F, hk, UP_X, 1F);
					}
				}
			}
			ground(m, black);
		}

		/** The spurs: short barbs off a spike, one at level 2 and two at level 3, leaning out and down. */
		private void spurs(SkinMesh m, int i, float s1) {
			int n = spurCount(tier);
			if (innerRing) return;
			for (int j = 0; j < n; j++) {
				float s = j == 0 ? 0.42F : 0.66F;
				if (s > s1 - 0.05F) continue;
				spikePoint(s, spurAt);
				float a = az + twist * s + (j == 0 ? 0.45F : -0.45F);
				float len = (3.2F - 0.8F * j) * Math.min(1F, grow);
				float dx = (float) Math.cos(a), dz = (float) Math.sin(a);
				TURN.rotationTo(0F, 0F, 1F, dx, 0.35F, dz);
				LOCAL.translation(spurAt.x, spurAt.y, spurAt.z).rotate(TURN);
				m.frustum(LOCAL, len, 0.9F, 0.9F, 0.12F, 0.12F, ST.u0(110 + j, i, 0F), ST.v0(110 + j, i, 0F), 2F, 1F, 1F, SkinMesh.WHITE);
			}
		}

		private final Vector3f spurAt = new Vector3f();
		private float ringRadius, ringWidth, ringWob;
		private final Loft.CurveFn groundCurve = (s, out) -> groundPoint(s, out);
		private final Loft.RadiusFn groundRadius = s -> FistGeometry.width(ringWidth, 4);

		/** The ring split open in the ground round the creature: black, and at the higher tiers a thin red ring inside it. */
		private void ground(SkinMesh m, boolean black) {
			float pop = ArmMath.clamp01(rise(age, hold) * 1.2F);
			if (pop <= 0.02F) return;
			ringRadius = r0 * (0.5F + 0.5F * pop);
			ringWob = 0.05F * r0;
			if (black) {
				ringWidth = 1.7F;
				Loft.closedTube(m, groundCurve, 18, 4, groundRadius, ArmLayer.BLACK, layer, ST, 120, 1.5F, 0F, UP_Y, 1F);
			} else if (tier >= 2) {
				ringRadius -= 1.6F;
				ringWidth = 0.9F;
				Loft.closedTube(m, groundCurve, 16, 4, groundRadius, ArmLayer.RED, layer, ST, 121, 2F, 0F, UP_Y, 1F);
			}
		}

		void groundPoint(float s, Vector3f out) {
			float a = ArmMath.TAU * s + 0.01F * time;
			float r = ringRadius + ringWob * (float) Math.sin(5F * a + 0.2F * time);
			out.set(r * (float) Math.cos(a), 0.3F, r * (float) Math.sin(a));
		}
	}

	/** The beads of earth thrown up as the ring erupts: thrown out and up from the ring, falling back and shrinking over 6 ticks. */
	static final class Debris implements TendrilFx.Geometry {
		float time, age, r0;
		int seed;

		void reset(float time, int seed, float age, float r0) {
			this.time = time;
			this.seed = seed;
			this.age = age;
			this.r0 = r0;
		}

		/** Bead {@code j}'s height at {@code t} ticks, px: up fast, back down under gravity (never below the ground). */
		static float height(int seed, int j, float t) {
			return Math.max(0F, (4F + 5F * TendrilFx.hash(seed + 9, j)) * t - 0.9F * t * t);
		}

		@Override
		public void emit(SkinMesh m) {
			ST.set(time, TendrilFx.ACTIVE, 0F, seed);
			for (int j = 0; j < BEADS; j++) {
				float t = age;
				float size = 1.6F * (1F - t / 6F) * (0.7F + 0.6F * TendrilFx.hash(seed + 5, j));
				if (!(size > 0.05F)) continue;
				float a = ArmMath.TAU * TendrilFx.hash(seed + 60, j);
				float r = r0 * (0.7F + 0.5F * TendrilFx.hash(seed + 3, j)) + 1.2F * t;
				float y = height(seed, j, t);
				float half = size / 2F;
				m.box(null, r * (float) Math.cos(a) - half, y - half, r * (float) Math.sin(a) - half, size, size, size, ST.u0(130, j, 0F), ST.v0(130, j, 0F), 1F, 1F,
						SkinMesh.WHITE);
			}
		}
	}
}
