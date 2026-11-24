package dev.morphreborn.client.arm.fx;

import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.geom.ArmDraw;
import dev.morphreborn.client.arm.geom.FistGeometry;
import dev.morphreborn.client.arm.geom.TridentGeometry;
import dev.morphreborn.client.arm.model.ArmDrawFactory;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.ArmTex;
import dev.morphreborn.client.arm.render.ArmTint;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.Static;
import dev.morphreborn.client.arm.render.TendrilPath;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The trident's world side (my design, 2026-11-16: a tendril line stays on the thrown trident and pulls it back, never the
 * player; Recall snaps it home at once, hitting along the path). S_ARM_FX kind {@link ArmProtocol#FX_TRIDENT}, the move in {@code arg}:
 * <ul>
 *   <li><b>1 THROW</b>: {@code ids = [the plugin's trident projectile]}. The projectile is the server's real, unpickable trident; clients
 *       that draw arms don't show the vanilla model for it (the renderer mixin asks {@link #hides}) but the arm's own: the tendril
 *       trident of {@link TridentGeometry}, turned along its flight (its fork always spread level), its point at the projectile's
 *       position, with a braided line, black with a red strand winding round it, from the hand to the eye in its butt. The hand keeps
 *       a stub of tendril while it is away ({@link #isThrown}); the line sags with its speed and goes slack on the ground. Idempotent:
 *       a THROW for an owner and projectile this client has on (not ended, not recalled) changes nothing (the server sends it once, and
 *       again only to a viewer who has just come into range, with {@code positions = [the trident's block]} to say it is no new throw).</li>
 *   <li><b>2 RETURN</b> (the server's FX_REEL): {@code ids = [the projectile]}. The line pulls taut, thin and straight, and beads of
 *       biomass run down it to the hand as the server reels the projectile in, the eye leading (the tendril pulls it by the butt) and
 *       the trident shrinking as it nears the hand.</li>
 *   <li><b>3 RECALL</b> (F): {@code ids = [the projectile or -1, the creatures hit...]}, {@code positions = [where it was]}. The line
 *       snaps taut and the trident flies back along it from where this client last saw it (the packet's block only when it never did)
 *       to the hand in 2 ticks, a red streak flashing down its path; each creature on the path sheds hairs from its wound as the
 *       trident passes, and a handful of beads fly from the hand as it lands.</li>
 *   <li><b>4 CATCH</b> and <b>0 END</b>: the throw is over. The trident, still where it was, shrinks into the hand over 3 ticks with the
 *       line reeling in behind it, and the hand takes it back.</li>
 *   <li><b>5 STRIKE</b>: {@code ids = [the projectile, the creature]}, {@code positions = [the creature's block]}. A red knot and a
 *       few black hairs flash from the creature's wound for 4 ticks.</li>
 * </ul>
 * Everything is drawn from what every modded viewer has: the packet, the projectile entity in the world, the owner's recorded hand.
 * An effect for a projectile that was never seen (out of range) draws nothing and times out; one that goes out of sight in flight ends
 * at the last place it was.
 */
public final class TridentFx implements FxHandler {
	public static final int END = 0, THROW = 1, RETURN = 2, RECALL = 3, CATCH = 4, STRIKE = 5;

	/** The most flights at once, the longest one lasts, and how long one that was never seen lingers (ticks). */
	private static final int MAX = 8, LIFE = 2400, UNSEEN = 80;
	/** Once the throw is over (caught, ended, or the projectile gone) the trident shrinks into the hand and the line reels in over this many ticks. */
	private static final float RETRACT_TICKS = 3F;
	/** The heading eases from the flight's to the reel's (eye leading) over this many ticks. */
	private static final float TURN_TICKS = 4F;
	/** A strike's flash lasts this many ticks. */
	private static final float STRIKE_TICKS = 4F;
	/** The recall's flight home, the streak's life and the whole effect (ticks), and when the hand has the trident back (ticks in). */
	private static final float RECALL_OUT = 2.2F, STREAK_TICKS = 4F;
	private static final int RECALL_TICKS = 8, RECALL_HOME = 2;
	/** The line's widths at the hand and at the trident (px), and its writhe (blocks). */
	private static final float LINE_ROOT = 1.25F, LINE_TIP = 0.75F, LINE_WRITHE = 0.05F;
	/** Sag by state, as a share of the line's length: in flight, reeling, lying on the ground. */
	private static final float SLACK_FLIGHT = 0.07F, SLACK_REEL = 0.012F, SLACK_STUCK = 0.2F, SAG_MAX = 40F;
	/**
	 * How much smaller than the arm's own the thrown trident is drawn (world px to G px), and how far (G px) the middle prong's point stands
	 * ahead of the entity's position. The server's collisions happen at the position, so the drawn point sits on it (a px ahead, the
	 * vanilla trident's own lead is about a quarter of a block): it neither pushes a block into a mob before the hit registers nor buries a stuck
	 * trident deeper than where it landed (2026-11-16; it was a point 1.2 blocks out).
	 */
	private static final float WORLD_SCALE = 0.85F, TIP_LEAD_PX = 1F;
	/** The reel's eye stops short of the hand by this share of the way left, so the butt never goes through the owner's camera. */
	private static final float NEAR_HAND = 0.9F;
	/** A projectile slower than this (blocks a tick) isn't flying; its heading is then its own view vector. */
	private static final double SLOW = 0.06;
	/** Hairs shed at each creature the recall hits (how many, how long they last, their length and width in px). */
	private static final int HAIRS = 6;
	private static final float HAIR_TICKS = 4F, HAIR_PX = 3.4F, HAIR_WIDTH = 0.4F;
	/** Beads flung from the hand as the recall lands: how many, how long they fly (ticks). */
	private static final int LAND_BEADS = 5;
	private static final float LAND_TICKS = 5F;
	/** Beads running down a taut line to the hand: how many. */
	private static final int REEL_BEADS = 4;

	private static final class Throw {
		final int origin, projectile, tier, seed;
		final long start;
		long returnAt = -1, recallAt = -1, ended = -1, homeAt = -1;
		boolean seen, homed, sounded, landed;
		/** Where the projectile was last seen and which way it points (frozen once the reel, the recall or the end begins: the reel's heading is drawn from the hand). */
		double x, y, z, dx = 0, dy = 1, dz = 0, speed;
		/** The last strike: when, which creature, and the block it stood in (for a client that doesn't know the creature). */
		long strikeAt = -1;
		int strikeId = -1;
		Vec3 strikePos = Vec3.ZERO;
		/** The line's slack now (a share of its length), moving toward its state's. */
		float slack = SLACK_FLIGHT;
		/** The recall: where it flew from (the creatures hit and the streak's start). */
		Vec3 from = Vec3.ZERO;
		int[] hits = new int[0];

		Throw(int origin, int projectile, int tier, int seed, long start) {
			this.origin = origin;
			this.projectile = projectile;
			this.tier = tier;
			this.seed = seed;
			this.start = start;
		}

		boolean recalled() {
			return recallAt >= 0;
		}
	}

	private final List<Throw> flights = new ArrayList<>();
	/** The projectiles whose vanilla model isn't drawn, and the owners whose trident is away (render thread; small arrays, nothing allocated reading them). */
	private static final int[] HIDDEN = new int[MAX], AWAY = new int[MAX];
	private static int hiddenCount, awayCount;

	private static final Static ST = new Static();
	private static final Vector3f UP_Y = new Vector3f(0F, 1F, 0F);
	/**
	 * Render-thread scratch for what is used at once and not kept: the heading, the trident's axes, and the data arrays of beads and hairs
	 * (TendrilFx copies them as it submits). What the deferred geometry lambdas read is never put here.
	 */
	private static final double[] HEADING = new double[3], AXES = new double[9], AIM = new double[3];
	private static final double[] REEL_BEAD_DATA = new double[4 * REEL_BEADS], LAND_BEAD_DATA = new double[4 * 3], HAIR_DATA = new double[8 * HAIRS];
	private static final Vector3f BEAD_AT = new Vector3f();
	private static final ArmDrawFactory.Spec SPEC = new ArmDrawFactory.Spec();

	public TridentFx() {}

	/** Whether the projectile entity {@code id} is an arm's thrown trident whose vanilla model isn't drawn (the renderer mixin asks, for any entity). */
	public static boolean hides(int id) {
		for (int i = 0; i < hiddenCount; i++) {
			if (HIDDEN[i] == id) return true;
		}
		return false;
	}

	/** Whether {@code owner}'s trident is away now (thrown and not yet back in the hand): the hand's draw shows the stub. */
	public static boolean isThrown(int owner) {
		for (int i = 0; i < awayCount; i++) {
			if (AWAY[i] == owner) return true;
		}
		return false;
	}

	/** How many flights are showing: for the tests. */
	int active() {
		return flights.size();
	}

	private static @Nullable ClientLevel level() {
		Minecraft mc = Minecraft.getInstance();
		return mc == null ? null : mc.level;
	}

	private static float clock(long tick) {
		return (float) (tick % ArmDrawFactory.CLOCK_WRAP);
	}

	@Override
	public int[] kinds() {
		return new int[] {ArmProtocol.FX_TRIDENT};
	}

	// ------------------------------------------------------------------ events

	@Override
	public void start(ArmFxEvent e) {
		switch (e.arg()) {
			case THROW -> {
				if (e.ids().length == 0) return;
				// idempotent: this owner's flight for this very projectile is kept as it is (a late viewer's repeat must not restart the line,
				// its slack or its clocks). Only a flight still on: one that has ended (the client lost the projectile and the flight is
				// lingering for its retract) or been recalled is replaced, or a viewer who tracks the trident again right after losing it
				// would be left with no line and the vanilla decoy for good, the server sending the throw only once (2026-11-16)
				for (Throw have : flights) {
					if (have.origin == e.origin() && have.projectile == e.ids()[0] && have.ended < 0 && !have.recalled()) return;
				}
				// one throw at a time per owner: a new one replaces the old
				flights.removeIf(t -> t.origin == e.origin());
				if (flights.size() >= MAX) flights.remove(0);
				Throw t = new Throw(e.origin(), e.ids()[0], Math.clamp(e.level(), 1, 3), (e.seed() ^ e.ids()[0] * 31) & 0x7FFF, e.receivedTick());
				flights.add(t);
				TridentGeometry.markAway(t.origin, true, clock(e.receivedTick()));
			}
			case RETURN -> {
				if (e.ids().length == 0) return;
				Throw t = find(e.origin(), e.ids()[0], true, e);
				if (t != null && t.returnAt < 0) t.returnAt = e.receivedTick();
			}
			case RECALL -> recall(e);
			case END, CATCH -> {
				Throw t = find(e.origin(), e.ids().length > 0 ? e.ids()[0] : -1, false, e);
				if (t != null) finish(t, e.receivedTick());
			}
			case STRIKE -> {
				Throw t = find(e.origin(), e.ids().length > 0 ? e.ids()[0] : -1, false, e);
				if (t != null && e.ids().length > 1) {
					t.strikeAt = e.receivedTick();
					t.strikeId = e.ids()[1];
					t.strikePos = e.positions().length > 0 ? Vec3.atCenterOf(BlockPos.of(e.positions()[0])).add(0, 0.1, 0) : Vec3.ZERO;
				}
			}
			default -> {
				// an arg a newer server added: nothing to draw
			}
		}
		refresh();
	}

	/** The throw of {@code origin} for projectile {@code id} (-1: whichever it has), made from {@code e} when there is none and {@code create}. */
	private @Nullable Throw find(int origin, int id, boolean create, ArmFxEvent e) {
		for (Throw t : flights) {
			if (t.origin == origin && (id < 0 || t.projectile == id)) return t;
		}
		if (!create) return null;
		// the throw's own packet was missed (we came into range with the trident already out): the effect starts from here
		if (flights.size() >= MAX) flights.remove(0);
		Throw t = new Throw(origin, id, Math.clamp(e.level(), 1, 3), (e.seed() ^ id * 31) & 0x7FFF, e.receivedTick());
		flights.add(t);
		TridentGeometry.markAway(origin, true, clock(e.receivedTick()));
		return t;
	}

	private void recall(ArmFxEvent e) {
		int id = e.ids().length > 0 ? e.ids()[0] : -1;
		Throw t = find(e.origin(), id, true, e);
		if (t == null || t.recalled()) return;
		t.recallAt = e.receivedTick();
		t.ended = e.receivedTick() + RECALL_TICKS;
		t.homeAt = e.receivedTick() + RECALL_HOME;
		// from where it was: the place this client last saw it (it was drawn there, so nothing jumps), else the block the server names
		if (t.seen) t.from = new Vec3(t.x, t.y, t.z);
		else if (e.positions().length > 0) t.from = Vec3.atCenterOf(BlockPos.of(e.positions()[0]));
		int n = 0;
		int[] hits = new int[Math.max(0, e.ids().length - 1)];
		for (int i = 1; i < e.ids().length; i++) {
			if (e.ids()[i] >= 0) hits[n++] = e.ids()[i];
		}
		t.hits = java.util.Arrays.copyOf(hits, n);
		// a recall with no known start has nothing to fly back from: the hand still takes the trident back
		if (!t.seen && e.positions().length == 0) t.from = Vec3.ZERO;
	}

	// ------------------------------------------------------------------ the clock

	@Override
	public void tick() {
		long now = Arms.TICK;
		ClientLevel level = level();
		for (Throw t : flights) {
			Entity projectile = level == null || t.projectile < 0 ? null : level.getEntity(t.projectile);
			boolean present = projectile != null && !projectile.isRemoved();
			if (present) {
				Vec3 at = projectile.position();
				Vec3 v = projectile.getDeltaMovement();
				t.speed = v.length();
				t.x = at.x;
				t.y = at.y;
				t.z = at.z;
				// the heading is the flight's own until the reel, the recall or the end: then the velocity points at the hand (or nowhere) and the
				// trident is drawn eye first, so it keeps the heading it had and eases to the one drawn from the hand (heading())
				// A flight first seen already reeling (a late viewer's THROW and REEL come in one tick, before this runs) has no flight
				// heading to keep, so it takes one now: the velocity points at the hand while the eye leads, so the heading is its
				// reverse, already the one drawn from the hand and no swing from straight up.
				boolean eyeFirst = t.returnAt >= 0 || t.recalled() || t.ended >= 0;
				if (!t.seen || !eyeFirst) {
					Vec3 look = t.speed > SLOW ? Vec3.ZERO : projectile.getViewVector(1F);
					if (aim(v, t.speed, look, !t.seen && eyeFirst, AIM)) {
						t.dx = AIM[0];
						t.dy = AIM[1];
						t.dz = AIM[2];
					}
				}
				t.seen = true;
			}
			if (t.ended < 0) {
				boolean gone = !present && (t.seen || now - t.start > UNSEEN);
				if (gone || now - t.start > LIFE) finish(t, now);
			}
			// the line slackens on the ground and tightens in flight and while reeling
			float target = t.returnAt >= 0 ? SLACK_REEL : t.speed > SLOW ? SLACK_FLIGHT : SLACK_STUCK;
			t.slack += (target - t.slack) * 0.3F;
			if (t.homeAt >= 0 && now >= t.homeAt && !t.homed) {
				t.homed = true;
				TridentGeometry.markAway(t.origin, false, clock(now));
			}
		}
		// a finished effect lingers while its line draws back into the hand (a recall's own ticks cover its streak and beads)
		flights.removeIf(t -> t.ended >= 0 && now - t.ended > RETRACT_TICKS + 1F);
		refresh();
	}

	/**
	 * The heading a projectile moving at {@code v} (blocks a tick, {@code speed} its length) points along, into {@code out} (unit): its
	 * velocity's direction, or {@code look} (its view vector) when it is slower than {@link #SLOW}; {@code reversed} for a flight first seen
	 * mid-reel, whose velocity points at the hand while the trident is drawn eye first. False (nothing written) when neither gives a direction.
	 */
	static boolean aim(Vec3 v, double speed, Vec3 look, boolean reversed, double[] out) {
		double sign = reversed ? -1 : 1;
		if (speed > SLOW) {
			out[0] = sign * v.x / speed;
			out[1] = sign * v.y / speed;
			out[2] = sign * v.z / speed;
			return true;
		}
		if (look.lengthSqr() > 1e-6) {
			out[0] = look.x;
			out[1] = look.y;
			out[2] = look.z;
			return true;
		}
		return false;
	}

	/**
	 * The throw is over at {@code now} (caught, ended, or the projectile is gone): a trident this client has seen shrinks into the hand over
	 * {@link #RETRACT_TICKS} and the hand takes it back as it arrives; one it never saw has nothing to draw, and the hand has it at once.
	 */
	private static void finish(Throw t, long now) {
		if (t.ended >= 0) return;
		t.ended = now;
		t.homeAt = t.seen ? now + (long) RETRACT_TICKS : now + 1;
	}

	/** Brings the owners-away and projectiles-hidden lists in step with the flights. */
	private void refresh() {
		ClientLevel level = level();
		hiddenCount = awayCount = 0;
		for (Throw t : flights) {
			if (!t.homed && awayCount < MAX) AWAY[awayCount++] = t.origin;
			// the vanilla model stays hidden while the entity exists (even after the effect, until the server removes it) so it never flashes
			if (t.projectile >= 0 && hiddenCount < MAX && (t.ended < 0 || level != null && level.getEntity(t.projectile) != null)) HIDDEN[hiddenCount++] = t.projectile;
		}
	}

	@Override
	public void clear() {
		flights.clear();
		hiddenCount = awayCount = 0;
		TridentGeometry.clearMarks();
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void collect(ArmFxFrame f) {
		if (flights.isEmpty()) return;
		ClientLevel level = level();
		if (level == null) return;
		float pt = f.partialTick();
		for (Throw t : flights) {
			ArmTint.owner(t.origin); // drawn in its owner's colour
			Vec3 hand = ArmFx.anchor(t.origin, ArmTips.TIP);
			if (hand == null || !TendrilFx.finite(hand)) continue;
			if (t.recalled()) recallFrame(f, level, t, hand, pt);
			else flightFrame(f, level, t, hand, pt);
			if (t.strikeAt >= 0) strikeFrame(f, level, t, hand, pt);
		}
	}

	/** The middle prong's point's height (G px) that sits at the entity's position, by tier. */
	static float centreY(int tier) {
		return TridentGeometry.tipY(tier) - TIP_LEAD_PX;
	}

	/** How far behind its point the line is made fast (the eye in the butt), in blocks at full size. */
	static float poleBlocks(int tier) {
		return (centreY(tier) - TridentGeometry.eyeY()) * WORLD_SCALE / 16F;
	}

	/**
	 * The share of its full size a trident drawn with its point {@code dist} blocks from the hand has while the eye leads (reeling, recalled,
	 * coming in): small enough that the butt, which is toward the hand, stops short of it and never goes through the owner's camera.
	 */
	static float nearHand(int tier, double dist) {
		return (float) Math.clamp(NEAR_HAND * dist / poleBlocks(tier), 0.0, 1.0);
	}

	/**
	 * The trident's axes for a point heading {@code (dx, dy, dz)} (unit), into {@code out}: Y the heading, X the level direction across it
	 * (the fork's spread is along the model's X, so the fork always lies in a level plane whichever way the owner threw: no roll that
	 * depends on the compass), Z across both. Right-handed and orthonormal; straight up or down takes X along the world's x.
	 */
	static void axes(double dx, double dy, double dz, double[] out) {
		double sx = dz, sz = -dx; // world up x heading
		double len = Math.sqrt(sx * sx + sz * sz);
		if (len < 1e-6) {
			sx = 1;
			sz = 0;
		} else {
			sx /= len;
			sz /= len;
		}
		out[0] = sx;
		out[1] = 0;
		out[2] = sz;
		out[3] = dx;
		out[4] = dy;
		out[5] = dz;
		out[6] = -sz * dy; // X x Y
		out[7] = sz * dx - sx * dz;
		out[8] = sx * dy;
	}

	/**
	 * The heading to draw the trident with (into {@link #HEADING}), {@code at} being its point. In flight it is the projectile's own. Once the
	 * reel (or the end) has begun the eye leads: the tendril pulls it by the butt, so the prongs point away from the hand and the line from
	 * the hand to the eye never threads the shaft; the turn from the flight's heading takes {@link #TURN_TICKS}.
	 */
	private static void heading(Throw t, Vec3 at, Vec3 hand, float pt) {
		double hx = t.dx, hy = t.dy, hz = t.dz;
		long since = t.returnAt >= 0 ? t.returnAt : t.ended;
		if (since >= 0) {
			double ax = at.x - hand.x, ay = at.y - hand.y, az = at.z - hand.z;
			double len = Math.sqrt(ax * ax + ay * ay + az * az);
			if (len > 1e-3) {
				double k = ArmMath.sm(ArmMath.clamp01(Arms.age(since, pt) / TURN_TICKS));
				hx += (ax / len - hx) * k;
				hy += (ay / len - hy) * k;
				hz += (az / len - hz) * k;
			}
		}
		double l = Math.sqrt(hx * hx + hy * hy + hz * hz);
		if (l < 1e-3) {
			// a flight that turned straight round passes through nothing: take the heading it is turning to
			hx = t.dx;
			hy = t.dy;
			hz = t.dz;
			l = Math.sqrt(hx * hx + hy * hy + hz * hz);
		}
		if (l < 1e-6) {
			HEADING[0] = 0;
			HEADING[1] = 1;
			HEADING[2] = 0;
			return;
		}
		HEADING[0] = hx / l;
		HEADING[1] = hy / l;
		HEADING[2] = hz / l;
	}

	/** The trident where the projectile is (or was last seen), and the line from the hand to its eye. */
	private void flightFrame(ArmFxFrame f, ClientLevel level, Throw t, Vec3 hand, float pt) {
		if (!t.seen) return;
		Entity projectile = t.projectile < 0 ? null : level.getEntity(t.projectile);
		boolean flying = t.ended < 0 && projectile != null && !projectile.isRemoved();
		Vec3 at = flying ? projectile.getPosition(pt) : new Vec3(t.x, t.y, t.z);
		if (!TendrilFx.finite(at)) return;
		boolean eyeFirst = t.returnAt >= 0 || t.ended >= 0;
		float scale = 1F;
		if (t.ended >= 0) {
			// the throw is over: the trident shrinks into the hand where it was, the line reeling in behind it
			float k = ArmMath.sm(Arms.age(t.ended, pt) / RETRACT_TICKS);
			if (k >= 0.99F) return;
			at = at.lerp(hand, k);
			scale = 1F - k;
		}
		heading(t, at, hand, pt);
		double dx = HEADING[0], dy = HEADING[1], dz = HEADING[2];
		if (eyeFirst) scale *= nearHand(t.tier, at.distanceTo(hand));
		if (scale < 0.04F) return;
		drawTrident(f, level, t, at, dx, dy, dz, scale, pt);
		Vec3 eye = eyeOf(t.tier, at, dx, dy, dz, scale);
		float reel = t.returnAt >= 0 && t.ended < 0 ? 1F : 0F;
		line(f, level, t, hand, eye, t.slack, 1F, reel, Arms.age(t.start, pt));
	}

	/** The recall: the trident flies home along the line, a streak flashes down the path and creatures on it shed hairs. */
	private void recallFrame(ArmFxFrame f, ClientLevel level, Throw t, Vec3 hand, float pt) {
		float age = Arms.age(t.recallAt, pt);
		if (age < 0F) return;
		Vec3 from = t.from;
		if (from.equals(Vec3.ZERO) && !t.seen) return;
		int light = TendrilFx.light(level, from, hand);
		if (!t.sounded && age >= 0F) {
			t.sounded = true;
			level.playLocalSound(hand.x, hand.y, hand.z, SoundEvents.BREEZE_WHIRL, SoundSource.PLAYERS, 0.5F, 1.9F, false);
		}
		float u = ArmMath.sm(ArmMath.clamp01(age / RECALL_OUT));
		Vec3 path = hand.subtract(from);
		double len = path.length();
		if (len < 0.3) return;
		Vec3 pos = from.add(path.scale(u));
		// the trident comes home butt first, the tendril snapping it in by the eye, its prongs trailing away from the hand; it shrinks as it
		// nears the hand and into it at the end; the line is taut and short behind the eye, its wave dying away
		if (u < 0.999F) {
			double ix = -path.x / len, iy = -path.y / len, iz = -path.z / len;
			float scale = nearHand(t.tier, pos.distanceTo(hand)) * (1F - ArmMath.sm((u - 0.85F) / 0.15F));
			if (scale >= 0.04F) {
				drawTrident(f, level, t, pos, ix, iy, iz, scale, pt);
				Vec3 eye = eyeOf(t.tier, pos, ix, iy, iz, scale);
				line(f, level, t, hand, eye, 0.004F, 1F, 1F, age);
			}
		}
		// the streak: a red flash laid down the path from where it was, wide at first, gone in a few ticks
		float streak = 1F - ArmMath.clamp01(age / STREAK_TICKS);
		if (streak > 0.01F) {
			float reach = ArmMath.clamp01(1.1F * age / RECALL_OUT);
			TendrilFx.draw(f, from, hand, reach, t.seed * 0.37F + 2F, 0.6F + 1.4F * streak, 0.2F, null, ArmLayer.RED, light, TendrilFx.ACTIVE);
		}
		hairs(f, level, t, from, hand, path, len, age, pt, light);
		// landing: beads flung from the hand, once, as it arrives
		float landed = age - RECALL_OUT;
		if (landed >= 0F && landed < LAND_TICKS) {
			if (!t.landed) {
				t.landed = true;
				level.playLocalSound(hand.x, hand.y, hand.z, SoundEvents.SCULK_BLOCK_CHARGE, SoundSource.PLAYERS, 0.4F, 1.6F, false);
			}
			landBeads(f, t, hand, landed, light);
		}
	}

	/** A strike's flash: a red knot at the creature's wound and a few black hairs spraying back toward the hand, for {@link #STRIKE_TICKS}. */
	private static void strikeFrame(ArmFxFrame f, ClientLevel level, Throw t, Vec3 hand, float pt) {
		float u = Arms.age(t.strikeAt, pt) / STRIKE_TICKS;
		if (u < 0F || u > 1F) return;
		Entity e = t.strikeId < 0 ? null : level.getEntity(t.strikeId);
		Vec3 wound;
		if (e != null && !e.isRemoved()) wound = TendrilFx.wound(e, pt);
		else if (!t.strikePos.equals(Vec3.ZERO)) wound = t.strikePos;
		else return;
		if (!TendrilFx.finite(wound)) return;
		int light = TendrilFx.light(level, wound, wound);
		double bx = hand.x - wound.x, by = hand.y - wound.y, bz = hand.z - wound.z;
		double bl = Math.sqrt(bx * bx + by * by + bz * bz);
		if (bl < 1e-6) {
			bx = 0;
			by = 1;
			bz = 0;
		} else {
			bx /= bl;
			by /= bl;
			bz /= bl;
		}
		shed(f, t, wound, bx, by, bz, u, 5, light);
		TendrilFx.knot(f, wound, 3.2F * (1F - u), ArmLayer.RED, light, t.seed + 7);
	}

	/** Hairs from each creature's wound as the trident passes it (the moment it reaches that part of the path), spraying back toward the hand. */
	private static void hairs(ArmFxFrame f, ClientLevel level, Throw t, Vec3 from, Vec3 hand, Vec3 path, double len, float age, float pt, int light) {
		if (t.hits.length == 0) return;
		for (int i = 0; i < t.hits.length; i++) {
			Entity e = level.getEntity(t.hits[i]);
			if (e == null || e.isRemoved()) continue;
			Vec3 wound = TendrilFx.wound(e, pt);
			// how far along the path the creature is, and so when the trident passes it
			double along = Math.clamp(wound.subtract(from).dot(path) / (len * len), 0.0, 1.0);
			float passes = RECALL_OUT * (float) Math.sqrt(along) * 0.9F;
			float hu = (age - passes) / HAIR_TICKS;
			if (hu < 0F || hu > 1F) continue;
			double bx = hand.x - wound.x, by = hand.y - wound.y, bz = hand.z - wound.z;
			double bl = Math.sqrt(bx * bx + by * by + bz * bz);
			if (bl < 1e-6) {
				bx = 0;
				by = 1;
				bz = 0;
			} else {
				bx /= bl;
				by /= bl;
				bz /= bl;
			}
			shed(f, t, wound, bx, by, bz, hu, 17 * (i + 1), light);
			// a clenched knot at the wound for the first two ticks of it
			if (hu < 0.5F) TendrilFx.knot(f, wound, 2.4F * (1F - 2F * hu), ArmLayer.RED, light, t.seed + 4 + i);
		}
	}

	/** {@link #HAIRS} hairs shed at {@code wound}, spraying toward {@code (bx, by, bz)} (unit), {@code hu} (0 to 1) of the way through their life; {@code salt} varies them. */
	private static void shed(ArmFxFrame f, Throw t, Vec3 wound, double bx, double by, double bz, float hu, int salt, int light) {
		double[] data = HAIR_DATA;
		for (int k = 0; k < HAIRS; k++) {
			double spin = ArmMath.TAU * TendrilFx.hash(t.seed + salt, k);
			double lift = 0.4 + 0.6 * TendrilFx.hash(t.seed + 3 + salt, k);
			int o = 8 * k;
			data[o] = wound.x;
			data[o + 1] = wound.y;
			data[o + 2] = wound.z;
			data[o + 3] = bx * 0.6 + Math.cos(spin) * lift;
			data[o + 4] = by * 0.6 + 0.3 + Math.sin(spin) * lift * 0.6;
			data[o + 5] = bz * 0.6 + Math.sin(spin) * lift;
			data[o + 6] = HAIR_PX * (1F - hu) * (0.7F + 0.6F * (float) TendrilFx.hash(t.seed + 9 + salt, k));
			data[o + 7] = HAIR_WIDTH * (1F - 0.7F * hu);
		}
		TendrilFx.filaments(f, data, HAIRS, ArmLayer.BLACK, light, t.seed + 2 + salt);
	}

	/** Beads flung out of the hand as the trident lands, red and black, falling and shrinking away. */
	private static void landBeads(ArmFxFrame f, Throw t, Vec3 hand, float since, int light) {
		double[] data = LAND_BEAD_DATA;
		for (int layerIdx = 0; layerIdx < 2; layerIdx++) {
			ArmLayer layer = layerIdx == 0 ? ArmLayer.RED : ArmLayer.BLACK;
			int count = layerIdx == 0 ? 3 : 2;
			for (int j = 0; j < count; j++) {
				int idx = layerIdx == 0 ? j : j + 3;
				double spin = ArmMath.TAU * TendrilFx.hash(t.seed, idx);
				double speed = 0.04 + 0.05 * TendrilFx.hash(t.seed, idx + 17);
				double vy = 0.03 + 0.05 * TendrilFx.hash(t.seed, idx + 29);
				data[4 * j] = hand.x + Math.cos(spin) * speed * since;
				data[4 * j + 1] = hand.y + vy * since - 0.5 * 0.04 * since * since;
				data[4 * j + 2] = hand.z + Math.sin(spin) * speed * since;
				data[4 * j + 3] = 0.8F * (1F - since / LAND_TICKS);
			}
			TendrilFx.beads(f, data, count, layer, light, t.seed + 3 + layerIdx);
		}
	}

	// ------------------------------------------------------------------ the thrown trident

	/** Where the line is made fast: the eye in the trident's butt, behind its point along its heading {@code (dx, dy, dz)}. */
	private static Vec3 eyeOf(int tier, Vec3 tip, double dx, double dy, double dz, float scale) {
		double back = poleBlocks(tier) * scale;
		return new Vec3(tip.x - dx * back, tip.y - dy * back, tip.z - dz * back);
	}

	/**
	 * The arm's trident with its point at {@code tip} (world), its prongs along {@code (dx, dy, dz)} (unit) and its fork level, drawn
	 * {@code scale} of its flight size in the owner's colour: three layers (black, red and, with the glow setting on, glow), each its own
	 * geometry submit. The geometry is submitted for later, so what it reads (the frame, the draw) is made fresh for each call; what is
	 * only used now is the class's scratch.
	 */
	private static void drawTrident(ArmFxFrame f, ClientLevel level, Throw t, Vec3 tip, double dx, double dy, double dz, float scale, float pt) {
		double[] a = AXES;
		axes(dx, dy, dz, a);
		float k = WORLD_SCALE * scale;
		Matrix4f frame = new Matrix4f((float) a[0], (float) a[1], (float) a[2], 0F, (float) a[3], (float) a[4], (float) a[5], 0F, (float) a[6], (float) a[7], (float) a[8], 0F,
				0F, 0F, 0F, 1F).scale(k).translate(0F, -centreY(t.tier), 0F);
		// world down in the trident's own frame, for what hangs from it (the rotation's transpose)
		float downX = (float) -a[1], downY = (float) -a[4], downZ = (float) -a[7];
		float dist = (float) tip.distanceTo(f.camPos());
		int lod = dist < 20F ? ArmDraw.LOD_FULL : dist < 48F ? 1 : dist < 90F ? 2 : ArmDraw.LOD_FAR;
		ArmDrawFactory.Spec s = SPEC.reset();
		s.form = ArmProtocol.TRIDENT;
		s.tier = t.tier;
		s.lod = lod;
		s.ctx = ArmDraw.CTX_TP;
		s.seed = t.origin;
		s.time = ArmDrawFactory.clock(pt);
		s.pulseSpeed = ArmTint.pulseSpeedOfEntity(t.origin);
		s.glow = ClientConfig.INSTANCE.armGlow;
		s.dissolve = ArmTex.dissolveOn();
		s.light = TendrilFx.light(level, tip, tip);
		ArmDraw base = s.build();
		ArmDraw d = base.finish(lod, false, null, downX, downY, downZ, null, s.light);
		float feed = FistGeometry.PHI_TOOL + TridentGeometry.tipY(t.tier) - 1.8F;
		for (ArmLayer layer : DRAWN) {
			if (layer == ArmLayer.GLOW && !d.glow()) continue;
			TendrilFx.submit(f, tip, layer, s.light, m -> {
				m.transform(frame);
				Loft.pulseFrom(d, feed);
				try {
					TridentGeometry.emitFlying(d, layer, m);
				} finally {
					Loft.pulseFrom(null, 0F);
					m.transform(null);
				}
			});
		}
	}

	private static final ArmLayer[] DRAWN = {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW};

	// ------------------------------------------------------------------ the line

	/**
	 * The braided line from the hand to the trident's eye: a black tendril with a red strand winding round it, {@code slack} of its length
	 * of sag (a share), writhing a little, drawn out to {@code reach} of its length from the hand. While {@code reel} is 1 beads of
	 * biomass run down it to the hand.
	 */
	private static void line(ArmFxFrame f, ClientLevel level, Throw t, Vec3 hand, Vec3 eye, float slack, float reach, float reel, float age) {
		Vec3 off = eye.subtract(hand);
		double len = off.length();
		if (len < 0.05 || !(reach > 0.01F)) return;
		int light = TendrilFx.light(level, hand, eye);
		float px = 16F;
		float dx = (float) off.x * px, dy = (float) off.y * px, dz = (float) off.z * px;
		float lenPx = (float) len * px;
		float sag = Math.min(SAG_MAX, slack * lenPx);
		float writhe = LINE_WRITHE * px * Math.min(1F, (float) len / 6F) * (reel > 0F ? 0.5F : 1F);
		Vec3 dir = off.scale(1 / len);
		Vec3 side = dir.cross(new Vec3(0, 1, 0));
		side = side.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : side.normalize();
		Vec3 up = side.cross(dir).normalize();
		float sx = (float) side.x, sy = (float) side.y, sz = (float) side.z, ux = (float) up.x, uy = (float) up.y, uz = (float) up.z;
		float time = f.time();
		float phase = t.seed * 0.37F;
		int seed = t.seed;
		int pieces = TendrilPath.pieces((float) len);
		float r = Math.min(1F, reach);
		TendrilFx.submit(f, hand, ArmLayer.BLACK, light, m -> {
			ST.set(time, TendrilFx.CALM, 1F, seed);
			Loft.tube(m, (s, out) -> lineAt(s, dx, dy, dz, sx, sy, sz, ux, uy, uz, sag, writhe, phase, time, out), 0F, r, Math.max(3, Math.round(pieces * r) + 1), 4,
					s -> FistGeometry.width(ArmMath.lerp(LINE_ROOT, LINE_TIP, s), 4), ArmLayer.BLACK, ArmLayer.BLACK, ST, 90, 1F, 0F, lenPx, UP_Y, 1F);
		});
		TendrilFx.submit(f, hand, ArmLayer.RED, light, m -> {
			ST.set(time, TendrilFx.CALM, 1F, seed + 1);
			Loft.tube(m, (s, out) -> {
				lineAt(s, dx, dy, dz, sx, sy, sz, ux, uy, uz, sag, writhe, phase, time, out);
				float rho = 0.5F * ArmMath.lerp(LINE_ROOT, LINE_TIP, s);
				float psi = s * lenPx / 3F - 0.4F * time;
				out.add(rho * ((float) Math.cos(psi) * sx + (float) Math.sin(psi) * ux), rho * ((float) Math.cos(psi) * sy + (float) Math.sin(psi) * uy),
						rho * ((float) Math.cos(psi) * sz + (float) Math.sin(psi) * uz));
			}, 0F, r, Math.max(3, Math.round(pieces * r) + 1), 4, s -> FistGeometry.width(0.42F * ArmMath.lerp(LINE_ROOT, LINE_TIP, s), 4), ArmLayer.RED,
					ArmLayer.RED, ST, 91, 1.5F, 0F, lenPx, UP_Y, 1F);
		});
		if (reel > 0F && reach >= 0.99F) reelBeads(f, hand, dx, dy, dz, sx, sy, sz, ux, uy, uz, sag, writhe, phase, time, age, light, seed);
	}

	/** Beads of biomass running down the taut line toward the hand, evenly spaced and steady. */
	private static void reelBeads(ArmFxFrame f, Vec3 hand, float dx, float dy, float dz, float sx, float sy, float sz, float ux, float uy, float uz, float sag,
			float writhe, float phase, float time, float age, int light, int seed) {
		double[] data = REEL_BEAD_DATA;
		Vector3f p = BEAD_AT;
		for (int k = 0; k < REEL_BEADS; k++) {
			// from the trident (1) toward the hand (0)
			float s = 1F - ArmMath.frac(0.09F * age + (float) k / REEL_BEADS);
			lineAt(s, dx, dy, dz, sx, sy, sz, ux, uy, uz, sag, writhe, phase, time, p);
			data[4 * k] = hand.x + p.x / 16.0;
			data[4 * k + 1] = hand.y + p.y / 16.0;
			data[4 * k + 2] = hand.z + p.z / 16.0;
			data[4 * k + 3] = 1.3F * (0.6F + 0.4F * (float) Math.sin(ArmMath.PI * s));
		}
		TendrilFx.beads(f, data, REEL_BEADS, ArmLayer.RED, light, seed + 6);
	}

	/**
	 * The line's point at {@code s} (0 at the hand, 1 at the eye) relative to the hand, in px: along the line with a hanging sag of
	 * {@code sag} at the middle ({@code 4s(1-s)}, down in the world) and a little writhe pinned at both ends.
	 */
	static void lineAt(float s, float dx, float dy, float dz, float sx, float sy, float sz, float ux, float uy, float uz, float sag, float writhe, float phase,
			float time, Vector3f out) {
		float env = (float) Math.sin(Math.PI * s) * writhe;
		float w1 = (float) Math.sin(phase + 7F * s + 0.35F * time) * env, w2 = 0.6F * (float) Math.cos(0.8F * phase + 5F * s + 0.28F * time) * env;
		float hang = sag * 4F * s * (1F - s);
		out.set(dx * s + sx * w1 + ux * w2, dy * s - hang + sy * w1 + uy * w2, dz * s + sz * w1 + uz * w2);
	}
}
