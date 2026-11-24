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
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * The claws' world side (my design, 2026-11-16: Rake is a three-slash claw sweep that leaves its targets bleeding). S_ARM_FX kind
 * {@link ArmProtocol#FX_CLAWS}. The server packs the move into the low four bits of {@code arg} and data above it (ClawMath on the server,
 * whose numbers are copied here and in the test; neither side may renumber; I decided on 2026-11-16 that the client decodes the
 * server's packed contract):
 * <ul>
 *   <li><b>1 RAKE</b> (F is paid): {@code level} = the tier, {@code ticks} = how long the whole Rake takes, {@code seed}; {@code ids} (the
 *       creatures in the fan before any damage) are not used. {@code arg}: bits 4-7 the slashes, 8-17 the fan in degrees, bit 18 left-handed,
 *       19-23 the ticks per slash (GAP, 3), 24-29 the reach in tenths of a block. One slash ribbon tears across the view per slash, each
 *       timed to the server's swing: slash {@code i} swings from {@code GAP * i} to {@code GAP * (i + 1)} ticks after the message, right to
 *       left on even {@code i} for a right-handed player (mirrored for a left-handed one), back on odd. Each ribbon is a black tendril thick at
 *       its leading end with a thin red edge on its lower side, drawn along a diagonal bowed arc across a sphere 1.7 blocks round the eyes
 *       (1.2 for your own, so they cross the view rather than leave it), as wide as the fan. Each takes its aim when it starts, as the
 *       server works out who is in the fan again for each slash.</li>
 *   <li><b>2 SLASH</b> (slash {@code arg >> 4 & 0xF} landed, a third of the way into its swing): {@code ids} = the creatures it really hit.
 *       Each gets short red claw marks across its body, facing the striker, and a burst of red beads; a creature that was in the fan but not
 *       hit (a cancelled hit, a protection rule) is not marked.</li>
 *   <li><b>3 BLEED</b> (a hit started or topped up a bleed): {@code ids = [the creature]}, {@code ticks} = how long it bleeds from now, to its
 *       last pulse, {@code arg} bits 4-11 the ticks between pulses. Dark red beads gather on the body and fall, and two strands hang off it
 *       and draw back, over and over until the bleed runs out. Keyed by (origin, creature): a later BLEED on the same pair replaces the
 *       earlier one (the three slashes of one Rake are one bleed), while two owners' bleeds never end each other. The claws' and the bow's bleeds on one creature by one owner share the one key
 *       (both send {@code origin} = the owner, {@code ids} = [the creature]), so they are drawn as one bleed and either's END ends it
 *       (the next PULSE of the other draws it again): accepted, as it is only drips (review finding, 2026-11-16).</li>
 *   <li><b>4 BLEED_PULSE</b> (a pulse took health): {@code ids = [the creature]}. A spurt of beads, and the bleed is re-armed: it runs on for
 *       another pulse's time from here.</li>
 *   <li><b>5 BLEED_END</b>: {@code ids = [the creature]}. The drips stop; a bleed whose PULSE came in the last few ticks (the last pulse
 *       sends both in one tick) stays just long enough for that spurt to be drawn.</li>
 * </ul>
 * In first person the claws in the hand wind up for a Rake through the arm's reactions (the special), not through this handler.
 */
public final class ClawsFx implements FxHandler {
	/** FX_CLAWS {@code arg & 0xF}, as the server's ClawMath: never renumber. */
	public static final int MOVE_RAKE = 1, MOVE_SLASH = 2, MOVE_BLEED = 3, MOVE_BLEED_PULSE = 4, MOVE_BLEED_END = 5;

	/** The marks' sphere round the eyes (blocks), your own in first person, and how far below the eyes it is centred. */
	static final float RADIUS = 1.7F, OWN_RADIUS = 1.2F, DROP = 0.25F;
	/** A mark's half extent across the view, along it and up it (blocks, as a sphere's arc, before the tier's scale and the fan), the bow of its arc, and the gap between marks. */
	static final float HALF_W = 0.8F, HALF_H = 0.7F, BOW = 0.24F, SPACING = 0.27F;
	/** The fan (degrees) the marks' width is drawn for, how a wider or narrower fan scales it, within these limits. */
	static final float FAN_DEG = 100F, FAN_MIN = 0.5F, FAN_MAX = 1.8F;
	/** How long a lead takes along one ribbon (ticks), how far behind its tail runs (of the mark), and how long the tail takes to catch it once it has arrived. */
	static final float SWEEP = 2.4F, TRAIL = 0.6F, CATCH_UP = 3F;
	/** The ticks per slash if the message names none, and the most one names. */
	static final int DEFAULT_GAP = 3, MAX_GAP = 8;
	/** The mark's widths (px): at its lead end, at its tail, and its red edge's; the edge stands this far off it (blocks). */
	private static final float LEAD_PX = 2.2F, TAIL_PX = 0.5F, EDGE_PX = 0.9F, EDGE_OFF = 0.03F;
	/** The claw marks on a creature: their half length and bow (blocks), and widths (px). */
	private static final float WOUND_HALF = 0.36F, WOUND_BOW = 0.07F, WOUND_SPACING = 0.1F, WOUND_LEAD_PX = 1.2F, WOUND_TAIL_PX = 0.3F;
	/** Ticks a slash's marks last, how many rakes, hits and bleeds show at once, and how many creatures a hit marks. */
	static final int HIT_LIFE = 10, MAX = 6, MAX_HITS = 24, MAX_BLEEDS = 12, MAX_TARGETS = 8;
	/** A bleed's drip cycle (ticks): drops gather, fall and strands draw back inside it, and it begins again for as long as the bleed runs. */
	static final float CYCLE = 20F;
	/** Ticks a bleed lingers past its message's length, and past each pulse's expected next, so a late tick doesn't cut it; and a pulse's interval when none is known. */
	static final int LINGER = 4, DEFAULT_INTERVAL = 20, MAX_BLEED_TICKS = 1200;
	private static final int BEADS = 6;
	private static final float BEAD_TICKS = 7F, BEAD_PX = 0.8F, GRAVITY = 0.04F;
	/** Ticks past its pulse a bleed that was ended in that same moment stays, to show the pulse's spurt (a spurt lives BEAD_TICKS). */
	private static final int SPURT_KEEP = (int) BEAD_TICKS + 1;
	/** The bleed's beads (gathering for this long on the body, then falling), strands, and how far a strand hangs (blocks). */
	private static final int DROPS = 3, STRANDS = 2;
	private static final float GATHER = 4F, DROP_PX = 1.0F, STRAND_LEN = 0.3F, STRAND_PX = 0.9F;
	/** Dark red: the red layer's vertex colour at this brightness. */
	private static final int DARK = FistGeometry.grey(0.6F);
	private static final int MAX_STATIONS = 14;

	// ------------------------------------------------------------------ the wire (as the server's ClawMath)

	static int move(int arg) {
		return arg & 0xF;
	}

	static int slashes(int arg) {
		return (arg >> 4) & 0xF;
	}

	static int arcDeg(int arg) {
		return (arg >> 8) & 0x3FF;
	}

	static boolean leftHanded(int arg) {
		return (arg & (1 << 18)) != 0;
	}

	static int gap(int arg) {
		return (arg >> 19) & 0x1F;
	}

	static int slashIndex(int arg) {
		return (arg >> 4) & 0xF;
	}

	static int bleedInterval(int arg) {
		return (arg >> 4) & 0xFF;
	}

	/** The aim of one stroke: unit look, right and up. Taken once the striker is known. */
	private static final class Aim {
		boolean set;
		float fx, fy, fz, rx, ry, rz, ux, uy, uz;
	}

	private static final class Rake {
		final int origin, rawSeed, seed, slashes, gap;
		final long start;
		final int tier;
		final boolean left;
		/** The fan's width, as a share of the 100 degrees the marks are drawn for. */
		final float fan;
		/** Each slash's aim, taken when its ribbon starts. */
		final Aim[] aims;

		Rake(int origin, long start, int tier, int rawSeed, int slashes, int gap, boolean left, int arcDeg) {
			this.origin = origin;
			this.start = start;
			this.tier = Math.clamp(tier, 1, 3);
			this.rawSeed = rawSeed;
			this.seed = (rawSeed ^ origin * 31) & 0x7FFF;
			this.slashes = Math.clamp(slashes, 1, 15);
			this.gap = gap <= 0 ? DEFAULT_GAP : Math.min(gap, MAX_GAP);
			this.left = left;
			this.fan = arcDeg <= 0 ? 1F : Math.clamp(arcDeg / FAN_DEG, FAN_MIN, FAN_MAX);
			this.aims = new Aim[this.slashes];
			for (int i = 0; i < this.slashes; i++) aims[i] = new Aim();
		}

		boolean over(long now) {
			return now - start > rakeLife(slashes, gap);
		}
	}

	/** One slash that landed: whom it hit, from which side. */
	private static final class Hit {
		final int origin, index, tier, seed;
		final long start;
		final int[] targets;
		final float side;
		final Aim aim = new Aim();

		Hit(int origin, long start, int tier, int seed, int index, int[] targets, float side) {
			this.origin = origin;
			this.start = start;
			this.tier = Math.clamp(tier, 1, 3);
			this.seed = seed;
			this.index = index;
			this.targets = targets;
			this.side = side;
		}
	}

	private static final class Bleed {
		final int target, origin, seed;
		final long start;
		int tier;
		/** The tick it ends at unless a pulse re-arms it, the ticks between pulses, and the tick of the last pulse (-1 none). */
		long until;
		int interval;
		long pulseAt = -1;
		/** Where its strands hang, taken from the creature's size once it is drawn (nothing else about it changes while it bleeds). */
		float[] roots;

		Bleed(int target, int origin, long start, int tier, int seed, long until, int interval) {
			this.target = target;
			this.origin = origin;
			this.start = start;
			this.tier = Math.clamp(tier, 1, 3);
			this.seed = seed;
			this.until = until;
			this.interval = interval;
		}
	}

	private final List<Rake> rakes = new ArrayList<>();
	private final List<Hit> hits = new ArrayList<>();
	private final List<Bleed> bleeds = new ArrayList<>();

	// render-thread scratch, used inside one geometry lambda at a time (or read at once, as the beads are)
	private static final Stations S = new Stations();
	private static final Static ST = new Static();
	private static final float[] POLY = new float[8];
	private static final float[] PTS = new float[3 * MAX_STATIONS];
	private static final Matrix4f LOCAL = new Matrix4f();
	private static final double[] BURST = new double[4 * BEADS];
	private static final float[] DROP_DATA = new float[4 * DROPS];

	public ClawsFx() {}

	@Override
	public int[] kinds() {
		return new int[] {ArmProtocol.FX_CLAWS};
	}

	@Override
	public void start(ArmFxEvent e) {
		int arg = e.arg();
		switch (move(arg)) {
			case MOVE_RAKE -> {
				Rake r = new Rake(e.origin(), e.receivedTick(), e.level(), e.seed(), slashes(arg), gap(arg), leftHanded(arg), arcDeg(arg));
				if (rakes.size() >= MAX) rakes.remove(0);
				rakes.add(r);
			}
			case MOVE_SLASH -> {
				int index = slashIndex(arg);
				Rake r = rakeOf(e.origin(), e.seed());
				// without its Rake (never heard of, or out of order) the striker's main hand says which side
				boolean left = r != null ? r.left : mainArmLeft(e.origin());
				int[] targets = Arrays.copyOf(e.ids(), Math.min(e.ids().length, MAX_TARGETS));
				if (targets.length == 0) return; // nobody was hit: nothing to mark
				if (hits.size() >= MAX_HITS) hits.remove(0);
				hits.add(new Hit(e.origin(), e.receivedTick(), e.level(), (e.seed() ^ e.origin() * 31) & 0x7FFF, index, targets, sideOf(left, index)));
			}
			case MOVE_BLEED -> {
				int interval = bleedInterval(arg) > 0 ? bleedInterval(arg) : DEFAULT_INTERVAL;
				long until = e.receivedTick() + Math.clamp(e.ticks(), 0, MAX_BLEED_TICKS) + LINGER;
				// one bleed per (owner, creature) named (normally one): a later message replaces the earlier, which keeps its clock so the drip carries on
				for (int id : e.ids()) {
					Bleed old = bleedOf(e.origin(), id);
					long since = old != null ? old.start : e.receivedTick();
					if (old != null) bleeds.remove(old);
					else if (bleeds.size() >= MAX_BLEEDS) bleeds.remove(0);
					Bleed b = new Bleed(id, e.origin(), since, e.level(), old != null ? old.seed : (e.seed() + id * 7) & 0x7FFF, until, interval);
					if (old != null) {
						b.pulseAt = old.pulseAt;
						b.roots = old.roots;
					}
					bleeds.add(b);
				}
			}
			case MOVE_BLEED_PULSE -> {
				for (int id : e.ids()) {
					Bleed b = bleedOf(e.origin(), id);
					if (b == null) {
						// its BLEED was missed: a pulse alone still shows a wound for a pulse's time
						if (bleeds.size() >= MAX_BLEEDS) bleeds.remove(0);
						b = new Bleed(id, e.origin(), e.receivedTick(), e.level(), (e.seed() + id * 7) & 0x7FFF, 0L, DEFAULT_INTERVAL);
						bleeds.add(b);
					}
					b.pulseAt = e.receivedTick();
					b.until = Math.max(b.until, e.receivedTick() + b.interval + LINGER);
				}
			}
			case MOVE_BLEED_END -> {
				for (int id : e.ids()) {
					Bleed b = bleedOf(e.origin(), id);
					if (b == null) continue;
					// the last pulse sends its PULSE and this END in one server tick: the spurt is drawn in the frames after it, so the bleed
					// stays for the spurt's life rather than being removed before it was ever seen (2026-11-16)
					if (b.pulseAt >= 0L && e.receivedTick() - b.pulseAt < BEAD_TICKS) b.until = Math.min(b.until, b.pulseAt + SPURT_KEEP);
					else bleeds.remove(b);
				}
			}
			default -> {
				// a move this client doesn't know (a newer server's): nothing to draw
			}
		}
	}

	private @Nullable Rake rakeOf(int origin, int rawSeed) {
		for (int i = rakes.size() - 1; i >= 0; i--) {
			Rake r = rakes.get(i);
			if (r.origin == origin && r.rawSeed == rawSeed) return r;
		}
		return null;
	}

	private @Nullable Bleed bleedOf(int origin, int target) {
		for (int i = 0; i < bleeds.size(); i++) {
			Bleed b = bleeds.get(i);
			if (b.origin == origin && b.target == target) return b;
		}
		return null;
	}

	/** Whether the striker is a left-handed player (anything else is read as right-handed). */
	private static boolean mainArmLeft(int origin) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc == null ? null : mc.level;
		Entity e = level == null ? null : level.getEntity(origin);
		return e instanceof Player p && p.getMainArm() == HumanoidArm.LEFT;
	}

	@Override
	public void tick() {
		expire(Arms.TICK);
	}

	/** Forgets what has run its time at game tick {@code now}. */
	void expire(long now) {
		for (int i = rakes.size() - 1; i >= 0; i--) if (rakes.get(i).over(now)) rakes.remove(i);
		for (int i = hits.size() - 1; i >= 0; i--) if (now - hits.get(i).start > HIT_LIFE) hits.remove(i);
		for (int i = bleeds.size() - 1; i >= 0; i--) if (now >= bleeds.get(i).until) bleeds.remove(i);
	}

	@Override
	public void clear() {
		rakes.clear();
		hits.clear();
		bleeds.clear();
	}

	int rakeCount() {
		return rakes.size();
	}

	int hitCount() {
		return hits.size();
	}

	int bleedCount() {
		return bleeds.size();
	}

	/** The tick a bleed of {@code origin} on {@code target} ends at unless a pulse re-arms it, or -1 when there is none. */
	long bleedUntil(int origin, int target) {
		Bleed b = bleedOf(origin, target);
		return b == null ? -1L : b.until;
	}

	/** Takes the striker's aim: unit look, right and up. */
	private static void aim(Aim a, Entity origin, float pt) {
		Vec3 look = origin.getViewVector(pt);
		Vec3 up = new Vec3(0, 1, 0);
		Vec3 right = look.cross(up);
		if (right.lengthSqr() < 1e-6) {
			// looking straight up or down: the heading's right instead
			double yaw = Math.toRadians(origin.getViewYRot(pt));
			right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		}
		right = right.normalize();
		Vec3 upv = right.cross(look).normalize();
		a.fx = (float) look.x;
		a.fy = (float) look.y;
		a.fz = (float) look.z;
		a.rx = (float) right.x;
		a.ry = (float) right.y;
		a.rz = (float) right.z;
		a.ux = (float) upv.x;
		a.uy = (float) upv.y;
		a.uz = (float) upv.z;
		a.set = true;
	}

	// ------------------------------------------------------------------ timing

	/** Which side slash {@code index} starts from (+1 the right): the main-hand side's top on even slashes, the other side's on odd, as the server's swing alternates. */
	static float sideOf(boolean left, int index) {
		return (left ? -1F : 1F) * ((index & 1) == 0 ? 1F : -1F);
	}

	/** When ribbon {@code i} starts, in ticks into the rake: the start of the server's swing, which takes {@code gap} ticks. */
	static float startOf(int i, int gap) {
		return (float) gap * i;
	}

	/** Where a ribbon's leading end is {@code age} ticks into the rake, 0..1 along it: eased over {@link #SWEEP} ticks from its start. */
	static float lead(float age, float start) {
		return ArmMath.sm((age - start) / SWEEP);
	}

	/** Where its tail is: {@link #TRAIL} behind the lead, closing on it over {@link #CATCH_UP} ticks once the lead has arrived. */
	static float tail(float age, float start) {
		float arrived = start + SWEEP;
		float gap = age <= arrived ? TRAIL : TRAIL * Math.max(0F, 1F - (age - arrived) / CATCH_UP);
		return ArmMath.clamp(lead(age, start) - gap, 0F, 1F);
	}

	/** How many ticks after the message a Rake's last ribbon is gone. */
	static int rakeLife(int slashes, int gap) {
		return gap * (Math.max(1, slashes) - 1) + (int) Math.ceil(SWEEP + CATCH_UP) + 1;
	}

	/** Mark {@code i}'s length factor: every third is the longest (the middle of three). */
	static float lengthOf(int i) {
		return i % 3 == 1 ? 1F : 0.84F;
	}

	/** Mark {@code i}'s offset across the hand, in spacings: -1, 0, 1 and round again. */
	static int lane(int i) {
		return i % 3 - 1;
	}

	/** The tier's scale of a mark: a little bigger a level. */
	static float tierScale(int tier) {
		return 1F + 0.1F * (Math.clamp(tier, 1, 3) - 1);
	}

	// ------------------------------------------------------------------ collect

	@Override
	public void collect(ArmFxFrame f) {
		if (rakes.isEmpty() && hits.isEmpty() && bleeds.isEmpty()) return;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		float pt = f.partialTick();
		for (int i = 0; i < rakes.size(); i++) rake(f, level, rakes.get(i), pt);
		for (int i = 0; i < hits.size(); i++) hit(f, level, hits.get(i), pt);
		for (int i = 0; i < bleeds.size(); i++) bleed(f, level, bleeds.get(i), pt);
	}

	private void rake(ArmFxFrame f, ClientLevel level, Rake r, float pt) {
		ArmTint.owner(r.origin); // this effect is drawn in its owner's colour
		Entity origin = level.getEntity(r.origin);
		if (origin == null) return;
		float age = Arms.age(r.start, pt);
		boolean own = ArmFx.isOwnFirstPerson(r.origin, f.firstPersonCamera());
		float radius = own ? OWN_RADIUS : RADIUS;
		Vec3 eye = origin.getEyePosition(pt).subtract(0, DROP, 0);
		if (!TendrilFx.finite(eye)) return;
		int light = TendrilFx.light(level, eye, eye);
		float scale = tierScale(r.tier) * (own ? 0.8F : 1F);
		for (int i = 0; i < r.slashes; i++) {
			float start = startOf(i, r.gap);
			if (age < start) break; // the later ribbons haven't begun
			float lead = lead(age, start), tail = tail(age, start);
			if (lead - tail < 0.02F) continue;
			// each stroke takes the aim it begins with: the server finds who is in the fan afresh for each slash
			Aim a = r.aims[i];
			if (!a.set) aim(a, origin, pt);
			Mark m = new Mark(a.fx, a.fy, a.fz, a.rx, a.ry, a.rz, a.ux, a.uy, a.uz, sideOf(r.left, i), radius * 16F, HALF_W * r.fan * scale * 16F * lengthOf(i),
					HALF_H * scale * 16F * lengthOf(i), BOW * scale * 16F, lane(i) * SPACING * scale * 16F, tail, lead, f.time(), r.seed + i, LEAD_PX * scale, TAIL_PX);
			TendrilFx.submit(f, eye, ArmLayer.BLACK, light, g -> mark(g, m, 0F, m.leadPx, m.tailPx, ArmLayer.BLACK));
			TendrilFx.submit(f, eye, ArmLayer.RED, light, g -> mark(g, m, EDGE_OFF * 16F, EDGE_PX, EDGE_PX * 0.6F, ArmLayer.RED));
		}
	}

	/** One mark's shape, in px about the eyes: the aim's axes, the sphere's radius, its half extents, bow and offset, the stretch drawn and its widths. */
	private record Mark(float fx, float fy, float fz, float rx, float ry, float rz, float ux, float uy, float uz, float side, float radius, float halfW,
			float halfH, float bow, float offset, float tail, float lead, float time, int seed, float leadPx, float tailPx) {}

	/**
	 * Mark {@code m}'s point at {@code s} (0..1 along it) into {@code out} (x, y, z at {@code o}), in px from the eyes: the diagonal from the
	 * main-hand side's top to the other side's bottom, bowed toward its lower side, on the sphere {@code radius} round the eyes.
	 */
	static void markPoint(Mark m, float s, float off, float[] out, int o) {
		float along = 1F - 2F * s;
		float pr = m.side * m.halfW * along, pu = m.halfH * along;
		// the travel direction (right, up) and its perpendicular, which the bow and the spacing run along
		float dr = -m.side * m.halfW, du = -m.halfH;
		float dl = (float) Math.sqrt(dr * dr + du * du);
		dr /= dl;
		du /= dl;
		float perR = -du, perU = dr;
		float bow = m.bow * (float) Math.sin(ArmMath.PI * s) + m.offset + off;
		pr += perR * bow;
		pu += perU * bow;
		// a little writhe, pinned at the ends
		float env = (float) Math.sin(ArmMath.PI * s);
		pu += 0.5F * env * (float) Math.sin(0.4F * m.seed + 7F * s + 0.3F * m.time);
		float x = m.fx * m.radius + m.rx * pr + m.ux * pu;
		float y = m.fy * m.radius + m.ry * pr + m.uy * pu;
		float z = m.fz * m.radius + m.rz * pr + m.uz * pu;
		float len = (float) Math.sqrt(x * x + y * y + z * z);
		float k = len > 1e-4F ? m.radius / len : 1F;
		out[o] = x * k;
		out[o + 1] = y * k;
		out[o + 2] = z * k;
	}

	/** One mark's tendril, from its tail to its lead, {@code widthTail} px at the tail to {@code widthLead} at the lead, {@code off} px off the mark's line. */
	private static void mark(SkinMesh g, Mark m, float off, float widthLead, float widthTail, ArmLayer layer) {
		int n = stations(m.tail, m.lead);
		float[] pts = PTS;
		for (int i = 0; i < n; i++) {
			float s = ArmMath.lerp(m.tail, m.lead, i / (float) (n - 1));
			markPoint(m, s, off, pts, 3 * i);
		}
		strip(g, n, widthLead, widthTail, layer, m.time, m.seed, layer == ArmLayer.RED ? 2F : 1F);
	}

	/** A four-sided strand through the first {@code n} of {@link #PTS}, {@code widthTail} px at its first point to {@code widthLead} at its last. */
	private static void strip(SkinMesh g, int n, float widthLead, float widthTail, ArmLayer layer, float time, int seed, float uv) {
		Stations s = S.reset(4).hint(0F, 1F, 0F);
		float v = 0F;
		for (int i = 0; i < n; i++) {
			if (i > 0) v += dist(i - 1, i);
			float half = 0.5F * ArmMath.lerp(widthTail, widthLead, i / (float) (n - 1));
			ArmMath.ring(POLY, 4, half, half, ArmMath.PI / 4F);
			int a = Math.max(0, i - 1), z = Math.min(n - 1, i + 1);
			s.addTransported(PTS[3 * i], PTS[3 * i + 1], PTS[3 * i + 2], PTS[3 * z] - PTS[3 * a], PTS[3 * z + 1] - PTS[3 * a + 1], PTS[3 * z + 2] - PTS[3 * a + 2], v, POLY,
					1F);
		}
		ST.set(time, TendrilFx.ACTIVE, 1F, seed);
		Loft.sweep(g, s, layer, null, ST, layer == ArmLayer.RED ? 4 : 3, uv, true, true);
	}

	static int stations(float tail, float lead) {
		return Math.clamp((int) Math.ceil((lead - tail) * 16F) + 1, 2, MAX_STATIONS);
	}

	private static float dist(int a, int b) {
		float dx = PTS[3 * b] - PTS[3 * a], dy = PTS[3 * b + 1] - PTS[3 * a + 1], dz = PTS[3 * b + 2] - PTS[3 * a + 2];
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	// ------------------------------------------------------------------ the marks on a creature

	/** Each creature a slash really hit gets its claw marks, drawn as the slash lands, and a burst of red beads. */
	private void hit(ArmFxFrame f, ClientLevel level, Hit h, float pt) {
		ArmTint.owner(h.origin);
		Entity origin = level.getEntity(h.origin);
		if (origin == null) return;
		if (!h.aim.set) aim(h.aim, origin, pt);
		Vec3 eye = origin.getEyePosition(pt).subtract(0, DROP, 0);
		if (!TendrilFx.finite(eye)) return;
		float age = Arms.age(h.start, pt);
		for (int t = 0; t < h.targets.length; t++) {
			Entity target = level.getEntity(h.targets[t]);
			if (target == null) continue;
			wounds(f, level, h, target, eye, age, pt, t);
		}
	}

	/**
	 * Short red claw marks across {@code target}'s body as slash {@code h.index} lands on it, facing the striker, and a burst of red beads.
	 * They follow the creature.
	 */
	private void wounds(ArmFxFrame f, ClientLevel level, Hit h, Entity target, Vec3 eye, float age, float pt, int index) {
		if (age > 9F) return;
		Vec3 at = TendrilFx.wound(target, pt);
		if (!TendrilFx.finite(at)) return;
		Vec3 toward = eye.subtract(at);
		double hl = Math.sqrt(toward.x * toward.x + toward.z * toward.z);
		// the mark's plane faces the striker; its right runs across that view
		float fx = hl > 1e-4 ? (float) (toward.x / hl) : h.aim.fx, fz = hl > 1e-4 ? (float) (toward.z / hl) : h.aim.fz;
		float rx = -fz, rz = fx;
		float rl = (float) Math.sqrt(rx * rx + rz * rz);
		if (rl < 1e-4F) return;
		rx /= rl;
		rz /= rl;
		// stand the marks on the creature's near surface and keep the right the same hand as the strike's
		float hand = (h.aim.rx * rx + h.aim.rz * rz) >= 0F ? 1F : -1F;
		rx *= hand;
		rz *= hand;
		int light = TendrilFx.light(level, at, at);
		float scale = (0.6F + 0.4F * Math.min(1.6F, target.getBbHeight() / 1.8F)) * tierScale(h.tier);
		float near = 0.5F * target.getBbWidth() + 0.02F;
		Vec3 centre = at.add(fx * near, 0, fz * near);
		// the mark draws over 1.5 ticks, then its tail catches it
		float lead = ArmMath.sm(age / 1.5F), tail = ArmMath.clamp01(lead - 0.7F * (1F - ArmMath.sm((age - 1.5F) / 4F)));
		if (age > 5.5F) tail = 1F;
		if (lead - tail >= 0.02F) {
			Wound w = new Wound(rx, rz, h.side, WOUND_HALF * 16F * scale * lengthOf(h.index), WOUND_BOW * 16F * scale, lane(h.index) * WOUND_SPACING * 16F * scale, tail, lead,
					f.time(), h.seed + 5 * h.index + 3 * index);
			TendrilFx.submit(f, centre, ArmLayer.RED, light, g -> woundMark(g, w));
		}
		burst(f, h.seed + 7 * h.index, -h.aim.fx * 0.02, -h.aim.fz * 0.02, at, age, light, index);
	}

	/** A claw mark on a body: its right axis in the ground plane, the side, its half length and bow, the offset across the three, the stretch drawn, and the clock. */
	private record Wound(float rx, float rz, float side, float half, float bow, float offset, float tail, float lead, float time, int seed) {}

	private static void woundMark(SkinMesh g, Wound w) {
		int n = Math.clamp((int) Math.ceil((w.lead - w.tail) * 8F) + 1, 2, 8);
		float dr = -w.side * 0.6F, du = -0.8F;
		float dl = (float) Math.sqrt(dr * dr + du * du);
		dr /= dl;
		du /= dl;
		float perR = -du, perU = dr;
		for (int i = 0; i < n; i++) {
			float s = ArmMath.lerp(w.tail, w.lead, i / (float) (n - 1));
			float along = 1F - 2F * s;
			float bow = w.bow * (float) Math.sin(ArmMath.PI * s) + w.offset;
			float pr = w.side * 0.6F * w.half * along + perR * bow, pu = 0.8F * w.half * along + perU * bow;
			PTS[3 * i] = w.rx * pr;
			PTS[3 * i + 1] = pu;
			PTS[3 * i + 2] = w.rz * pr;
		}
		strip(g, n, WOUND_LEAD_PX, WOUND_TAIL_PX, ArmLayer.RED, w.time, w.seed, 2F);
	}

	/**
	 * Red beads bursting from {@code at} {@code u} ticks ago: they fly out, drifting {@code (driftX, driftZ)} a tick, and fall, shrinking away.
	 * The scratch array is read at once by the beads, so one is enough.
	 */
	private static void burst(ArmFxFrame f, int seed, double driftX, double driftZ, Vec3 at, float u, int light, int index) {
		if (u < 0F || u > BEAD_TICKS) return;
		double[] data = BURST;
		for (int j = 0; j < BEADS; j++) {
			double spin = ArmMath.TAU * TendrilFx.hash(seed, j);
			double vx = Math.cos(spin) * 0.05 + driftX, vz = Math.sin(spin) * 0.05 + driftZ;
			double vy = 0.06 + 0.05 * TendrilFx.hash(seed, j + 11);
			data[4 * j] = at.x + vx * u;
			data[4 * j + 1] = at.y + vy * u - 0.5 * GRAVITY * u * u;
			data[4 * j + 2] = at.z + vz * u;
			data[4 * j + 3] = BEAD_PX * (1F - u / BEAD_TICKS);
		}
		TendrilFx.beads(f, data, BEADS, ArmLayer.RED, light, seed + 3 + index);
	}

	// ------------------------------------------------------------------ the bleed

	/** How far along its gathering and fall drop {@code j} is {@code age} ticks into a drip cycle: 0 not yet, 0..1 gathering on the body, then ticks falling. */
	static float dropClock(float age, int j) {
		return age - (1.5F + 3F * j);
	}

	/** A strand's length (blocks) {@code age} ticks into a drip cycle: out over 3 ticks, held, drawn back from tick 10 to 16. */
	static float strandLength(float age) {
		return STRAND_LEN * ArmMath.sm(age / 3F) * (1F - ArmMath.sm((age - 10F) / 6F));
	}

	/** Where in its drip cycle a bleed that has run {@code age} ticks is: the cycle begins again for as long as the bleed lasts. */
	static float cycleAge(float age) {
		return age - CYCLE * (float) Math.floor(age / CYCLE);
	}

	private void bleed(ArmFxFrame f, ClientLevel level, Bleed b, float pt) {
		ArmTint.owner(b.origin);
		Entity target = level.getEntity(b.target);
		if (target == null) return;
		if (!target.isAlive()) {
			// a creature that died bleeds no more (the server sends no end for it); tick() drops it
			b.until = 0L;
			return;
		}
		float age = cycleAge(Arms.age(b.start, pt));
		Vec3 at = TendrilFx.wound(target, pt);
		if (!TendrilFx.finite(at)) return;
		int light = TendrilFx.light(level, at, at);
		float w = target.getBbWidth(), h = target.getBbHeight();
		float scale = tierScale(b.tier);
		float[] data = DROP_DATA;
		int count = 0;
		// drops: a bead gathers on the body, then falls from it
		for (int j = 0; j < DROPS; j++) {
			float u = dropClock(age, j);
			if (u < 0F) continue;
			float ox = (TendrilFx.hash(b.seed, j) - 0.5F) * 0.6F * w, oz = (TendrilFx.hash(b.seed, j + 5) - 0.5F) * 0.6F * w, oy = (TendrilFx.hash(b.seed, j + 9) - 0.5F) * 0.4F * h;
			float size, drop;
			if (u < GATHER) {
				size = DROP_PX * scale * ArmMath.sm(u / GATHER);
				drop = 0F;
			} else {
				float fall = u - GATHER;
				size = DROP_PX * scale * Math.max(0F, 1F - fall / 8F);
				drop = 0.5F * GRAVITY * fall * fall;
			}
			if (size < 0.05F) continue;
			data[4 * count] = ox;
			data[4 * count + 1] = oy - drop;
			data[4 * count + 2] = oz;
			data[4 * count + 3] = size;
			count++;
		}
		int beads = count;
		float time = f.time();
		int seed = b.seed;
		if (beads > 0) {
			float[] snap = Arrays.copyOf(data, 4 * count); // the geometry runs later, after the scratch is reused
			TendrilFx.submit(f, at, ArmLayer.RED, light, g -> drops(g, snap, beads, time, seed));
		}
		// strands: two hang off the body and draw back (their roots are the same every cycle, so they are made once)
		float len = strandLength(age) * scale;
		if (len > 0.01F) {
			float[] s = b.roots;
			if (s == null) {
				s = new float[3 * STRANDS];
				for (int k = 0; k < STRANDS; k++) {
					s[3 * k] = (TendrilFx.hash(b.seed, 20 + k) - 0.5F) * 0.7F * w;
					s[3 * k + 1] = (TendrilFx.hash(b.seed, 30 + k) - 0.3F) * 0.3F * h;
					s[3 * k + 2] = (TendrilFx.hash(b.seed, 40 + k) - 0.5F) * 0.7F * w;
				}
				b.roots = s;
			}
			float[] roots = s;
			float lenPx = len * 16F;
			TendrilFx.submit(f, at, ArmLayer.RED, light, g -> strands(g, roots, lenPx, time, seed));
		}
		// a pulse took health: a spurt from the wound
		if (b.pulseAt >= 0L) burst(f, b.seed + 17, 0.0, 0.0, at, Arms.age(b.pulseAt, pt), light, 0);
	}

	private static void drops(SkinMesh g, float[] d, int n, float time, int seed) {
		ST.set(time, TendrilFx.ACTIVE, 0F, seed);
		for (int i = 0; i < n; i++) {
			float size = d[4 * i + 3], half = size / 2F;
			g.box(null, d[4 * i] * 16F - half, d[4 * i + 1] * 16F - half, d[4 * i + 2] * 16F - half, size, size, size, ST.u0(61, i, 0F), ST.v0(61, i, 0F), 1F, 1F, DARK);
		}
	}

	private static void strands(SkinMesh g, float[] roots, float lenPx, float time, int seed) {
		ST.set(time, TendrilFx.ACTIVE, 0F, seed + 1);
		for (int k = 0; k < STRANDS; k++) {
			// hanging down, swaying a little; the frustum runs along +Z, so turn it to face down
			float sway = 0.25F * (float) Math.sin(0.2F * time + 2.1F * k);
			LOCAL.translation(roots[3 * k] * 16F, roots[3 * k + 1] * 16F, roots[3 * k + 2] * 16F).rotateTowards(sway, -1F, 0.4F * sway, 0F, 0F, 1F);
			g.frustum(LOCAL, lenPx, STRAND_PX, STRAND_PX, STRAND_PX * 0.25F, STRAND_PX * 0.25F, ST.u0(62, k, 0F), ST.v0(62, k, 0F), 2F, 1F, 1F, DARK);
		}
	}
}
