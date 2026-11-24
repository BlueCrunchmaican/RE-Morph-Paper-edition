package dev.morphreborn.client.arm.fx;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmState;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.geom.FistGeometry;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.ArmTex;
import dev.morphreborn.client.arm.render.ArmTint;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import dev.morphreborn.client.net.ClientNet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A biomass bolt in flight (S_ARM_FX BOLT, ids = [the arrow]): the arrow the server launched wears the bolt's look as it
 * goes, for the shooter and every viewer. The arrow itself is the vanilla entity (the server's real, unpickable arrow); what
 * this adds round it is the bolt:
 * <ul>
 *   <li>a coat: two black strands winding round a red core along its length, a red point on its head, fluttering a hair;</li>
 *   <li>a trail: a black tendril streaming back from it up to 4 blocks, 0.2 px thick at its end and 1.5 at the bolt, writhing
 *       with the frenzy's waves (it shortens with the arrow's speed);</li>
 *   <li>the landing: when the arrow stops or is gone, the trail snaps off and a burst follows, five beads flying out and
 *       falling, shrinking away over 6 ticks, and four short black roots reaching back out of the spot for 4 ticks.</li>
 * </ul>
 * The arrow's tip (my design, 2026-11-16; FX_BOLT {@code arg}, see {@link #tipOf}) tints the bolt's red parts and gives its
 * head a shape of its own: bleed dark red and hooked, slowness pale blue and a frost crown, poison green with a bulb, fire orange and
 * licking, glowing white-gold as a halo (the last two glow), with a filament of the tint down the trail and beads of it on landing.
 * The fan of the Triple Arrow (the arg's fan bit) also puffs at the bow's tip.
 * <p>
 * Everything is worked out from the arrow entity's position and motion (which every client has), so another player's bolt
 * looks the same as yours. An arrow that was never seen (out of range) draws nothing; one that goes out of sight in flight
 * simply ends, and the burst plays at the last place it was.
 */
public final class BoltFx implements FxHandler {
	/** The trail's longest reach (blocks), its widths at the end and at the arrow (px), and how long it takes to snap off. */
	private static final float TRAIL_MAX = 4F, TRAIL_TAIL_PX = 0.2F, TRAIL_HEAD_PX = 1.5F, SNAP_TICKS = 3F;
	/** The coat's length and radius (px), and how many turns the strands make along it. */
	private static final float COAT_BACK = 5F, COAT_FRONT = 4F, COAT_RADIUS = 1.1F, COAT_TURNS = 1.5F;
	private static final int BEADS = 5, ROOTS = 4;
	private static final float BEAD_TICKS = 6F, BEAD_PX = 0.7F, BEAD_GRAVITY = 0.04F, ROOT_TICKS = 4F, ROOT_PX = 5F, ROOT_WIDTH = 0.5F;
	/** An arrow that doesn't move faster than this (blocks a tick) has landed; the first ticks after launch are never taken for landing. */
	private static final double LANDED_SPEED = 0.05;
	private static final int GRACE_TICKS = 3, LIFE = 140, MAX = 24;
	/** An arrow whose place moves less than this (blocks) for this many ticks in a row has landed, whatever motion it still claims. */
	private static final double STILL_BLOCKS = 0.02;
	private static final int STILL_TICKS = 2;

	// ------------------------------------------------------------------ the arrow tips (my design, 2026-11-16)

	/**
	 * This client's own numbering of the tips (0 none, then the server's wire ids 0..4 plus one): the wire carries the tip in FX_BOLT's
	 * {@code arg}, as the server's BowTips lays it out: bits 0-7 the draw power in percent, bits 8-10 the tip (0 bleed, 1 slowness, 2 poison,
	 * 3 fire, 4 glowing), bit 11 an arrow of the Triple Arrow fan (bits 12-13 its place), bit 14 and bit 15 messages with no arrow (the
	 * armed and tip-changed notices: {@link #tipOf} ignores them, {@link #noticeTip} reads their tip, and they are drawn as the ready ring). Only servers at {@link ArmProtocol#NEW_FORMS_SINCE} send the tip, and the crossbow's
	 * bolts (arg 100: the tip bits read as bleed) are plain; older servers' args are the power alone and also read plain.
	 */
	public static final int TIP_NONE = 0, TIP_BLEED = 1, TIP_SLOWNESS = 2, TIP_POISON = 3, TIP_FIRE = 4, TIP_GLOWING = 5;
	public static final int WIRE_TIP_SHIFT = 8, WIRE_TIP_MASK = 7, WIRE_FAN = 1 << 11, WIRE_ARMED = 1 << 14, WIRE_TIP_MSG = 1 << 15;
	/** The tint of each tip's tendril point (index = the tip): bleed dark red, slowness pale blue, poison green, fire orange, glowing white-gold. */
	private static final int[] TIP_RGB = {ArmProtocol.COLOUR_DEFAULT, 0x8E0E14, 0xA9DDFF, 0x4FD13A, 0xFF8A1F, 0xFFE79A};
	/**
	 * The shape of each tip's head, so no two read alike: spike count, their pitch (rad off the arrow's heading: below pi/2 the
	 * spikes lean forward, above it they are swept back; a negative pitch is the same cone as its positive, the spikes being spread
	 * all the way round, so it can't say "back"), length and width (px), where along the arrow they start, the central point's
	 * length and width, and how much it flickers. Bleed's and poison's barbs are hooked back (2.3 and 2.0 rad, 2026-11-16 review:
	 * they were -0.9 and -0.5, which drew forward like slowness's frost crown), poison's also a bulb behind its point, slowness's a
	 * frost crown, fire's licking forward, glowing's a halo.
	 */
	private static final float[][] TIP_SHAPE = {
			{0F, 0F, 0F, 0F, 0F, 0F, 0F, 0F},
			{3F, 2.3F, 2.4F, 0.5F, 5.0F, 3.2F, 1.2F, 0F},
			{4F, 0.55F, 1.7F, 0.55F, 3.6F, 2.4F, 1.5F, 0F},
			{2F, 2.0F, 2.0F, 0.55F, 4.8F, 2.8F, 1.0F, 0F},
			{3F, 0.28F, 3.2F, 0.9F, 4.0F, 4.2F, 1.2F, 0.3F},
			{6F, 1.15F, 2.4F, 0.5F, 4.6F, 3.6F, 0.9F, 0.15F}};
	/** The triple shot's puff at the bow: how long it lasts (ticks), and the most kept at once. */
	private static final float POP_TICKS = 5F;
	private static final int POP_MAX = 6, POP_BEADS = 6;
	/** Arrows of one volley (same origin and seed) that arrive this many ticks apart still share a puff; a Bolt that is in no volley has this for its volley. */
	private static final int POP_JOIN_TICKS = 2, NO_VOLLEY = -1;
	/**
	 * The ready ring (the ARMED and TIP notices, 2026-11-16 review: they were sent and nothing drew them): three small beads in the nocked
	 * tip's tint circling the bow's tip while Triple Arrow is ready (the three nocked arrows), and for TIP_FLASH_TICKS after a bare tip change,
	 * one turn of the same ring (so every viewer sees which tip was picked). At most READY_MAX at once, one per player.
	 */
	private static final int READY_MAX = 12, READY_BEADS = 3, TIP_FLASH_TICKS = 12;
	private static final float READY_RADIUS = 0.1F, READY_SPIN = 0.2F, READY_BEAD_PX = 0.8F;

	/** The arrow tip a FX_BOLT {@code arg} names ({@link #TIP_NONE} for a notice with no arrow and anything out of range). */
	public static int tipOf(int arg) {
		if ((arg & (WIRE_ARMED | WIRE_TIP_MSG)) != 0) return TIP_NONE;
		int tip = ((arg >> WIRE_TIP_SHIFT) & WIRE_TIP_MASK) + 1;
		return tip < TIP_RGB.length ? tip : TIP_NONE;
	}

	/** Whether a FX_BOLT {@code arg} marks an arrow of the Triple Arrow fan. */
	public static boolean isTriple(int arg) {
		return (arg & WIRE_FAN) != 0 && (arg & (WIRE_ARMED | WIRE_TIP_MSG)) == 0;
	}

	/** The tip a FX_BOLT ARMED or TIP notice's {@code arg} names (the notice bits are ignored here), {@link #TIP_NONE} for one out of range. */
	public static int noticeTip(int arg) {
		int tip = ((arg >> WIRE_TIP_SHIFT) & WIRE_TIP_MASK) + 1;
		return tip < TIP_RGB.length ? tip : TIP_NONE;
	}

	/** The tint of a tip's tendril point (0xRRGGBB). */
	public static int tipRgb(int tip) {
		return TIP_RGB[tip < 0 || tip >= TIP_RGB.length ? 0 : tip];
	}

	/** Whether a tip's spikes are swept back along the shaft (pitch past a right angle): bleed's and poison's barbs. */
	static boolean tipSweptBack(int tip) {
		return tip > TIP_NONE && tip < TIP_SHAPE.length && TIP_SHAPE[tip][1] > ArmMath.PI / 2F;
	}

	/** Whether a tip's head is drawn glowing (full bright): fire's and the glowing tip's. */
	static boolean tipGlows(int tip) {
		return tip == TIP_FIRE || tip == TIP_GLOWING;
	}

	/** One bolt: the arrow, when it was launched, and where it ended (the last place and way it was seen). */
	private static final class Bolt {
		final int origin, arrow, seed, tip;
		/** The message's own seed, shared by the three arrows of one Triple Arrow volley (what ties them to their puff). */
		final int volley;
		final long start;
		double x, y, z, dx, dy = -1, dz;
		boolean seen;
		/** Ticks in a row the arrow has not moved (its place, not its motion: a stuck arrow keeps whatever motion it was given). */
		int still;
		/** The tick it landed or vanished (-1: still flying). */
		long ended = -1;

		Bolt(int origin, int arrow, long start, int seed, int tip, int volley) {
			this.volley = volley;
			this.origin = origin;
			this.arrow = arrow;
			this.start = start;
			this.seed = seed;
			this.tip = tip;
		}
	}

	/** The puff at the bow when a Triple Arrow fan leaves it: whose bow, when, the tip it is tinted in. */
	private static final class Pop {
		final int origin, tip, seed;
		final long start;

		Pop(int origin, long start, int tip, int seed) {
			this.origin = origin;
			this.start = start;
			this.tip = tip;
			this.seed = seed;
		}
	}

	/** One player's ready ring: whose bow, the tip it is tinted in, when it started and when it ends (client ticks), and whether Triple Arrow is armed (else a bare tip flash). */
	private static final class Ready {
		final int origin;
		int tip;
		long start, until;
		boolean armed;

		Ready(int origin) {
			this.origin = origin;
		}
	}

	private final List<Bolt> bolts = new ArrayList<>();
	private final List<Ready> ready = new ArrayList<>();
	private final List<Pop> pops = new ArrayList<>();
	private static final Matrix4f LOCAL = new Matrix4f();
	/** Scratch for the puff's beads and filaments (render thread; the draw calls copy what they need before they submit). */
	private static final double[] POP_BEADS_DATA = new double[4 * POP_BEADS], POP_SPURTS = new double[8 * 4];
	private static final double[] READY_BEADS_DATA = new double[4 * READY_BEADS];
	/** Scratch for a landing's glints (two beads), which the draw copies before it submits. */
	private static final double[] GLINT = new double[8];
	/**
	 * The arrow entities that are bolts: the vanilla arrow isn't drawn for them (BoltArrowHideMixin), only the tendril
	 * coat round it, until the entity is gone. Render thread.
	 */
	private static final java.util.Set<Integer> HIDDEN = new java.util.HashSet<>();

	/** Whether the arrow entity {@code id} is a bolt whose vanilla arrow should not be drawn. */
	public static boolean hides(int id) {
		return !HIDDEN.isEmpty() && HIDDEN.contains(id);
	}

	private static final Static ST = new Static();
	private static final Matrix4f FRAME = new Matrix4f();
	private static final Quaternionf TURN = new Quaternionf();
	private static final Vector3f DIR = new Vector3f();

	public BoltFx() {}

	/** The world being drawn, or null before there is one (and in the unit tests, which have no game). */
	private static @org.jspecify.annotations.Nullable ClientLevel level() {
		Minecraft mc = Minecraft.getInstance();
		return mc == null ? null : mc.level;
	}

	/** How many are showing now: for the tests. */
	int active() {
		return bolts.size();
	}

	@Override
	public int[] kinds() {
		return new int[] {ArmProtocol.FX_BOLT};
	}

	/** How many triple-shot puffs are showing now: for the tests. */
	int pops() {
		return pops.size();
	}

	@Override
	public void start(ArmFxEvent e) {
		if (e.ids().length == 0) {
			notice(e);
			return;
		}
		if (bolts.size() >= MAX) bolts.remove(0);
		// the tip rides on arg for servers that send it; the crossbow's bolts have none (its arg is 100, which would read as bleed)
		ArmState from = Arms.stateByEntity(e.origin());
		boolean tips = ClientNet.serverAtLeast(ArmProtocol.NEW_FORMS_SINCE) && !(from != null && "crossbow".equals(from.form));
		int tip = tips ? tipOf(e.arg()) : TIP_NONE;
		boolean triple = tips && isTriple(e.arg());
		bolts.add(new Bolt(e.origin(), e.ids()[0], e.receivedTick(), (e.seed() ^ e.ids()[0] * 31) & 0x7FFF, tip, triple ? e.seed() : NO_VOLLEY));
		HIDDEN.add(e.ids()[0]);
		if (triple) {
			// the volley's three packets share one seed: a fan split over a tick boundary is still one puff (2026-11-16 review)
			boolean have = false;
			for (Pop p : pops) {
				if (p.origin == e.origin() && p.seed == e.seed() && Math.abs(p.start - e.receivedTick()) <= POP_JOIN_TICKS) have = true;
			}
			if (!have) {
				if (pops.size() >= POP_MAX) pops.remove(0);
				pops.add(new Pop(e.origin(), e.receivedTick(), tip, e.seed()));
			}
		}
	}

	/** How many ready rings are showing now: for the tests. */
	int readies() {
		return ready.size();
	}

	/** Whether {@code origin}'s Triple Arrow is showing as ready, and the tip it shows (TIP_NONE when no ring): for the tests. */
	boolean armedFor(int origin) {
		for (Ready r : ready) if (r.origin == origin && r.armed) return true;
		return false;
	}

	/** The tip {@code origin}'s ready ring is tinted in, TIP_NONE when it has none: for the tests. */
	int readyTip(int origin) {
		for (Ready r : ready) if (r.origin == origin) return r.tip;
		return TIP_NONE;
	}

	/**
	 * An ARMED or TIP notice (no arrow). ARMED with ticks above 0 starts the ring for that long (and ticks 0 ends it); TIP retints a ring
	 * that is up, or else flashes one for a moment. Only servers that send them (and only the bow does), so older ones never get here
	 * with a notice bit set.
	 */
	private void notice(ArmFxEvent e) {
		if (!ClientNet.serverAtLeast(ArmProtocol.NEW_FORMS_SINCE)) return;
		int arg = e.arg();
		boolean armedMsg = (arg & WIRE_ARMED) != 0, tipMsg = (arg & WIRE_TIP_MSG) != 0;
		if (!armedMsg && !tipMsg) return;
		Ready r = null;
		for (Ready x : ready) if (x.origin == e.origin()) r = x;
		int tip = noticeTip(arg);
		if (armedMsg) {
			if (e.ticks() <= 0) {
				if (r != null) ready.remove(r);
				return;
			}
			if (r == null) {
				if (ready.size() >= READY_MAX) ready.remove(0);
				r = new Ready(e.origin());
				ready.add(r);
			}
			r.armed = true;
			r.tip = tip;
			r.start = e.receivedTick();
			r.until = e.receivedTick() + e.ticks();
			return;
		}
		if (r != null && r.armed) {
			r.tip = tip; // the ring that is up changes its tint
			return;
		}
		if (r == null) {
			if (ready.size() >= READY_MAX) ready.remove(0);
			r = new Ready(e.origin());
			ready.add(r);
		}
		r.armed = false;
		r.tip = tip;
		r.start = e.receivedTick();
		r.until = e.receivedTick() + TIP_FLASH_TICKS;
	}

	@Override
	public void tick() {
		long now = Arms.TICK;
		ready.removeIf(r -> now >= r.until);
		ClientLevel level = level();
		for (Bolt b : bolts) {
			if (b.ended >= 0) continue;
			Entity arrow = level == null ? null : level.getEntity(b.arrow);
			if (arrow == null || arrow.isRemoved()) {
				// gone: out of sight or struck and removed; if it was seen, it ends where it was last seen
				if (b.seen || now - b.start > GRACE_TICKS * 4L) b.ended = now;
				continue;
			}
			Vec3 at = arrow.position();
			Vec3 v = arrow.getDeltaMovement();
			double moved = b.seen ? Math.sqrt((at.x - b.x) * (at.x - b.x) + (at.y - b.y) * (at.y - b.y) + (at.z - b.z) * (at.z - b.z)) : 1.0;
			b.still = moved < STILL_BLOCKS ? b.still + 1 : 0;
			b.x = at.x;
			b.y = at.y;
			b.z = at.z;
			double speed = v.length();
			if (speed > 1e-4) {
				b.dx = v.x / speed;
				b.dy = v.y / speed;
				b.dz = v.z / speed;
			}
			b.seen = true;
			if (now - b.start >= GRACE_TICKS && (speed < LANDED_SPEED || b.still >= STILL_TICKS)) b.ended = now;
		}
		bolts.removeIf(b -> now - b.start > LIFE || (b.ended >= 0 && now - b.ended > BEAD_TICKS + 2));
		pops.removeIf(p -> now - p.start > POP_TICKS + 1);
		// stop hiding arrows that are gone (a stuck bolt stays hidden until the server removes it)
		if (!HIDDEN.isEmpty()) HIDDEN.removeIf(id -> {
			Entity arrow = level == null ? null : level.getEntity(id);
			return arrow == null || arrow.isRemoved();
		});
	}

	@Override
	public void clear() {
		bolts.clear();
		pops.clear();
		ready.clear();
		HIDDEN.clear();
	}

	@Override
	public void collect(ArmFxFrame f) {
		if (bolts.isEmpty() && ready.isEmpty()) return;
		ClientLevel level = level();
		if (level == null) return;
		for (Ready r : ready) ring(f, level, r);
		if (bolts.isEmpty()) return;
		float pt = f.partialTick();
		for (Bolt b : bolts) {
			ArmTint.owner(b.origin); // this effect is drawn in its owner's colour
			if (!b.seen) continue;
			Entity arrow = level.getEntity(b.arrow);
			boolean flying = b.ended < 0 && arrow != null && !arrow.isRemoved();
			Vec3 at = flying ? arrow.getPosition(pt) : new Vec3(b.x, b.y, b.z);
			if (!TendrilFx.finite(at)) continue;
			Vec3 dir = new Vec3(b.dx, b.dy, b.dz);
			int light = TendrilFx.light(level, at, at);
			float age = Arms.age(b.start, pt);
			// your own bolt in your own first-person view starts at the eye and its trail runs back through it: a tendril a hand's breadth from
			// the lens fills the screen (the rig, 2026-11-16: a grey ghost for ~3 frames after a loose), so nothing of it is drawn within
			// NearCamera.TRAIL_CLEAR of the camera (the coat only inside the small piece clearance: at 1.4 its on/off was a hard-edged pop)
			boolean own = ArmFx.isOwnFirstPerson(b.origin, f.firstPersonCamera());
			boolean nearEye = own && f.camPos().distanceTo(at) < NearCamera.CLEAR;
			if (flying) {
				if (!nearEye) coat(f, b, at, dir, light, age);
				trail(f, at, dir, arrow.getDeltaMovement().length(), light, b.seed, 1F, own);
				if (b.tip != TIP_NONE) {
					// a thin filament in the tip's tint winds down the black trail, so the arrow reads by its tip even from afar
					ArmTint.colour(tipRgb(b.tip));
					double len = clearLength(f, own, at, dir, Math.min(TRAIL_MAX, 0.4 + arrow.getDeltaMovement().length() * 1.1) * 0.7);
					if (len > 1e-3) {
						TendrilFx.draw(f, new Vec3(at.x - dir.x * len, at.y - dir.y * len, at.z - dir.z * len), at, 1F, b.seed * 0.37F + 1.9F, 0.15F, 0.5F, null, ArmLayer.RED, light, TendrilFx.ACTIVE);
					}
					ArmTint.owner(b.origin);
				}
			} else {
				float since = Arms.age(b.ended, pt);
				if (since < SNAP_TICKS) trail(f, at, dir, 1.5, light, b.seed, 1F - ArmMath.sm(since / SNAP_TICKS), own);
				// the landing burst always draws, however close the spot is (a point-blank hit gave the owner no feedback); its beads and roots
				// shrink away by their distance from your eye instead
				burst(f, b, at, dir, since, light, own ? NearCamera.fade(f.camPos().distanceTo(at), NearCamera.CLEAR, NearCamera.BAND) : 1F);
			}
		}
		if (!pops.isEmpty()) {
			for (Pop p : pops) pop(f, level, p);
		}
	}

	/**
	 * The ready ring: three beads in the tip's tint circling the bow's tip, drawn all the while Triple Arrow is armed (a tip flash is one
	 * quicker turn that fades out). Allocation-free but for the anchor.
	 */
	private void ring(ArmFxFrame f, ClientLevel level, Ready r) {
		float age = Arms.age(r.start, f.partialTick());
		if (age < 0F) return;
		float fade = r.armed ? 1F : 1F - age / TIP_FLASH_TICKS;
		if (!(fade > 0F)) return;
		ArmTint.owner(r.origin);
		Vec3 at = ArmFx.anchor(r.origin, ArmTips.TIP);
		if (at == null || !TendrilFx.finite(at)) return;
		float scale = ArmFx.isOwnFirstPerson(r.origin, f.firstPersonCamera()) ? 0.6F : 1F;
		float spin = age * (r.armed ? READY_SPIN : 3F * READY_SPIN);
		for (int j = 0; j < READY_BEADS; j++) {
			double a = ArmMath.TAU * j / READY_BEADS + spin;
			READY_BEADS_DATA[4 * j] = at.x + Math.cos(a) * READY_RADIUS * scale;
			READY_BEADS_DATA[4 * j + 1] = at.y + Math.sin(a * 2.0) * 0.02;
			READY_BEADS_DATA[4 * j + 2] = at.z + Math.sin(a) * READY_RADIUS * scale;
			READY_BEADS_DATA[4 * j + 3] = READY_BEAD_PX * scale * fade;
		}
		ArmTint.colour(r.tip == TIP_NONE ? ArmProtocol.COLOUR_DEFAULT : tipRgb(r.tip));
		TendrilFx.beads(f, READY_BEADS_DATA, READY_BEADS, ArmLayer.RED, TendrilFx.light(level, at, at), r.origin * 31 + 5);
		ArmTint.owner(r.origin);
	}

	/**
	 * The Triple Arrow's puff, 5 ticks at the bow's tip as the fan leaves it: a short spurt of tendril in the tip's tint (and a
	 * thinner black one) along each arrow's heading, and a ring of beads flying out, shrinking away. Aimed by the arrows' own
	 * headings once they have been seen; until then only the beads.
	 */
	private void pop(ArmFxFrame f, ClientLevel level, Pop p) {
		float pt = f.partialTick();
		float g = Arms.age(p.start, pt) / POP_TICKS;
		if (g < 0F || g >= 1F) return;
		ArmTint.owner(p.origin);
		Vec3 at = ArmFx.anchor(p.origin, ArmTips.TIP);
		if (at == null || !TendrilFx.finite(at)) return;
		float scale = ArmFx.isOwnFirstPerson(p.origin, f.firstPersonCamera()) ? 0.6F : 1F;
		int light = TendrilFx.light(level, at, at);
		int rgb = p.tip == TIP_NONE ? ArmProtocol.COLOUR_DEFAULT : tipRgb(p.tip);
		int n = 0;
		for (Bolt b : bolts) {
			if (b.origin != p.origin || b.volley != p.seed || !b.seen || n >= 4) continue;
			int o = 8 * n++;
			POP_SPURTS[o] = at.x;
			POP_SPURTS[o + 1] = at.y;
			POP_SPURTS[o + 2] = at.z;
			POP_SPURTS[o + 3] = b.dx;
			POP_SPURTS[o + 4] = b.dy;
			POP_SPURTS[o + 5] = b.dz;
			POP_SPURTS[o + 6] = 9F * scale * (float) Math.sin(ArmMath.PI * Math.min(1F, g * 1.2F));
			POP_SPURTS[o + 7] = 0.8F * scale * (1F - 0.5F * g);
		}
		if (n > 0) {
			ArmTint.colour(rgb);
			TendrilFx.filaments(f, POP_SPURTS, n, ArmLayer.RED, light, p.seed + 7);
			ArmTint.owner(p.origin);
			for (int i = 0; i < n; i++) {
				POP_SPURTS[8 * i + 6] *= 1.4F;
				POP_SPURTS[8 * i + 7] *= 0.7F;
			}
			TendrilFx.filaments(f, POP_SPURTS, n, ArmLayer.BLACK, light, p.seed + 8);
		}
		// the ring: beads leave the bow's tip in every direction (sideways more than back and forth), the tip's tint
		float t = g * POP_TICKS;
		for (int j = 0; j < POP_BEADS; j++) {
			double spin = ArmMath.TAU * (j + TendrilFx.hash(p.seed, j) * 0.6) / POP_BEADS;
			double speed = 0.05 + 0.04 * TendrilFx.hash(p.seed, j + 11);
			POP_BEADS_DATA[4 * j] = at.x + Math.cos(spin) * speed * t;
			POP_BEADS_DATA[4 * j + 1] = at.y + (TendrilFx.hash(p.seed, j + 23) - 0.4) * 0.06 * t;
			POP_BEADS_DATA[4 * j + 2] = at.z + Math.sin(spin) * speed * t;
			POP_BEADS_DATA[4 * j + 3] = 0.8F * scale * (1F - g);
		}
		ArmTint.colour(rgb);
		TendrilFx.beads(f, POP_BEADS_DATA, POP_BEADS, ArmLayer.RED, light, p.seed + 3);
		ArmTint.owner(p.origin);
	}

	/** {@code len} blocks of trail behind {@code at}, shortened for {@code own} (your own bolt seen from your own eye) so it stays {@link NearCamera#TRAIL_CLEAR} from the camera. */
	private static double clearLength(ArmFxFrame f, boolean own, Vec3 at, Vec3 dir, double len) {
		if (!own) return len;
		Vec3 cam = f.camPos();
		return NearCamera.trailLength(at.x - cam.x, at.y - cam.y, at.z - cam.z, dir.x, dir.y, dir.z, len, NearCamera.TRAIL_CLEAR);
	}

	/**
	 * The trail: a tendril from {@code 4} blocks behind the bolt (less when it is slow: a block a tick and a bit) to it,
	 * {@code share} of it left (the part nearest the bolt stays while it snaps off).
	 */
	private static void trail(ArmFxFrame f, Vec3 at, Vec3 dir, double speed, int light, int seed, float share, boolean own) {
		if (!(share > 0.01F)) return;
		double len = clearLength(f, own, at, dir, Math.min(TRAIL_MAX, 0.4 + speed * 1.1));
		if (!(len > 1e-3)) return;
		// the share left is the part nearest the bolt: the tail end is cut off as the trail snaps
		Vec3 cutFrom = at.subtract(dir.scale(len * share));
		TendrilFx.draw(f, cutFrom, at, 1F, seed * 0.37F, TRAIL_TAIL_PX + (TRAIL_HEAD_PX - TRAIL_TAIL_PX) * (1F - share), TRAIL_HEAD_PX, null, ArmLayer.BLACK, light,
				TendrilFx.ACTIVE);
	}

	/**
	 * The coat round the arrow's shaft near its head: two black strands winding over a red core and a red point on the head,
	 * in px from the arrow's position, along its direction of flight.
	 */
	private static void coat(ArmFxFrame f, Bolt b, Vec3 at, Vec3 dir, int light, float age) {
		int seed = b.seed;
		int tip = b.tip;
		float time = f.time();
		float spin = 0.35F * time + seed * 0.1F;
		float flutter = 0.08F * (float) Math.sin(0.5F * time + seed);
		FRAME.identity().rotate(TURN.rotationTo(0F, 0F, 1F, (float) dir.x, (float) dir.y, (float) dir.z));
		Matrix4f frame = new Matrix4f(FRAME);
		// the strands and the core, and the point, each in their layer
		coatLayer(f, at, frame, ArmLayer.BLACK, light, seed, spin, flutter, time, tip);
		// the tip: the red core and the point on the head in the tip's tint (the owner's colour is back for the next draw)
		if (tip != TIP_NONE) ArmTint.colour(tipRgb(tip));
		coatLayer(f, at, frame, ArmLayer.RED, light, seed, spin, flutter, time, tip);
		if (tipGlows(tip)) coatLayer(f, at, frame, ArmLayer.GLOW, light, seed, spin, flutter, time, tip);
		if (tip != TIP_NONE) ArmTint.owner(b.origin);
	}

	private static void coatLayer(ArmFxFrame f, Vec3 at, Matrix4f frame, ArmLayer layer, int light, int seed, float spin, float flutter, float time, int tip) {
		RenderType type = ArmTex.type(layer, ArmTex.dissolveOn());
		Vec3 cam = f.camPos();
		PoseStack poseStack = f.poseStack();
		poseStack.pushPose();
		poseStack.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
		poseStack.scale(1F / 16F, 1F / 16F, 1F / 16F);
		f.collector().submitCustomGeometry(poseStack, type, (pose, buffer) -> {
			// the glow layer is bright by being full bright (ArmTex has no emissive type), as TendrilFx and ArmFormRenderer light it
			SkinMesh m = SkinMesh.begin(pose, buffer, layer == ArmLayer.GLOW ? ArmTex.FULL_BRIGHT : light, OverlayTexture.NO_OVERLAY);
			m.transform(frame);
			ST.set(time, TendrilFx.ACTIVE, 1F, seed);
			float total = COAT_BACK + COAT_FRONT;
			if (layer == ArmLayer.BLACK) {
				for (int k = 0; k < 2; k++) {
					final int kk = k;
					Loft.CurveFn strand = (s, out) -> {
						float z = -COAT_BACK + total * s;
						float ang = ArmMath.TAU * COAT_TURNS * s + kk * ArmMath.PI + spin;
						float r = COAT_RADIUS * (1F + flutter) * (1F - 0.45F * s * s);
						out.set(r * (float) Math.cos(ang), r * (float) Math.sin(ang), z);
					};
					Loft.tube(m, strand, 0F, 1F, 6, 4, s -> FistGeometry.width(0.55F * (1F - 0.5F * s), 4), ArmLayer.BLACK, layer, ST, 50 + k, 2F,
							8F, 8F + total, new Vector3f(0F, 1F, 0F), 1F);
				}
			} else if (layer == ArmLayer.RED) {
				Loft.CurveFn core = (s, out) -> out.set(0F, 0F, -COAT_BACK + (total + 1.6F) * s);
				Loft.tube(m, core, 0F, 1F, 4, 4, s -> FistGeometry.width(0.7F * (1F - 0.85F * s * s), 4), ArmLayer.RED, layer, ST, 52, 2F, 8F,
						8F + total, new Vector3f(0F, 1F, 0F), 1F);
				if (tip != TIP_NONE) tipHead(m, tip, time, seed, 1F, false);
			} else if (layer == ArmLayer.GLOW) {
				tipHead(m, tip, time, seed, 1.2F, true);
			}
		});
		poseStack.popPose();
	}

	/**
	 * The head of a tip, in px along the arrow's heading (+Z) from the arrow's position: a central point and spikes round it,
	 * shaped by {@link #TIP_SHAPE}. {@code glow} draws the same head a fifth bigger in the glow layer, which the bright tips
	 * (fire, glowing) lay over the red one. The spikes turn slowly with the coat, and flicker where the shape says so.
	 */
	private static void tipHead(SkinMesh m, int tip, float time, int seed, float grow, boolean glow) {
		float[] t = TIP_SHAPE[tip];
		float flick = 1F + t[7] * (float) Math.sin(0.9F * time + seed);
		float main = t[5] * grow * flick, w = t[6] * grow;
		float u = ST.u0(60, 0, 8F), v = ST.v0(60, 0, 8F);
		LOCAL.translation(0F, 0F, COAT_FRONT + 0.4F);
		m.frustum(LOCAL, main, w, w, 0.05F, 0.05F, u, v, 1.5F, 1F, 1F, SkinMesh.WHITE);
		int n = (int) t[0];
		float spin = 0.25F * time + seed * 0.1F;
		for (int k = 0; k < n; k++) {
			float a = ArmMath.TAU * k / n + spin;
			float len = t[2] * grow * (1F + 0.5F * t[7] * (float) Math.sin(1.3F * time + seed + 2F * k));
			LOCAL.translation(0F, 0F, t[4]).rotateZ(a).rotateX(-t[1]);
			m.frustum(LOCAL, len, t[3] * grow, t[3] * grow, 0.05F, 0.05F, ST.u0(61, k, 8F), ST.v0(61, k, 8F), 1.5F, 1F, 1F, SkinMesh.WHITE);
		}
		// poison's head swells into a bulb behind its point
		if (tip == TIP_POISON && !glow) {
			m.box(null, -0.8F, -0.8F, COAT_FRONT - 0.6F, 1.6F, 1.6F, 1.6F, ST.u0(62, 0, 8F), ST.v0(62, 0, 8F), 1.5F, 1F, SkinMesh.WHITE);
		}
	}

	/**
	 * The landing: five beads (red and black) flying out of the spot and falling, shrinking away over 6 ticks, and four short
	 * roots reaching back out of it (opposite the arrow's heading, splayed) for 4 ticks. {@code keep} (0..1) scales the pieces for your own
	 * bolt landing near your eye ({@link NearCamera}): the roots reach back toward it.
	 */
	private static void burst(ArmFxFrame f, Bolt b, Vec3 at, Vec3 dir, float since, int light, float keep) {
		if (since < 0F || !(keep > 0.02F)) return;
		if (since < BEAD_TICKS) {
			for (int layerIdx = 0; layerIdx < 2; layerIdx++) {
				ArmLayer layer = layerIdx == 0 ? ArmLayer.RED : ArmLayer.BLACK;
				int count = layerIdx == 0 ? 3 : 2;
				double[] data = new double[4 * count];
				for (int j = 0; j < count; j++) {
					int idx = layerIdx == 0 ? j : j + 3;
					double spin = ArmMath.TAU * TendrilFx.hash(b.seed, idx);
					double speed = 0.04 + 0.04 * TendrilFx.hash(b.seed, idx + 17);
					double vx = -dir.x * 0.04 + Math.cos(spin) * speed, vz = -dir.z * 0.04 + Math.sin(spin) * speed;
					double vy = -dir.y * 0.04 + 0.05 + 0.05 * TendrilFx.hash(b.seed, idx + 29);
					data[4 * j] = at.x + vx * since;
					data[4 * j + 1] = at.y + vy * since - 0.5 * BEAD_GRAVITY * since * since;
					data[4 * j + 2] = at.z + vz * since;
					data[4 * j + 3] = BEAD_PX * keep * (1F - since / BEAD_TICKS);
				}
				// the red beads are the tip's tint
				boolean tinted = layer == ArmLayer.RED && b.tip != TIP_NONE;
				if (tinted) ArmTint.colour(tipRgb(b.tip));
				TendrilFx.beads(f, data, count, layer, light, b.seed + 3 + layerIdx);
				if (tinted) ArmTint.owner(b.origin);
			}
			if (tipGlows(b.tip)) {
				// fire's sparks and the glowing tip's glints rise out of the spot, bright
				double[] glint = GLINT;
				for (int j = 0; j < 2; j++) {
					glint[4 * j] = at.x + (TendrilFx.hash(b.seed, 60 + j) - 0.5) * 0.12;
					glint[4 * j + 1] = at.y + 0.03 * since * (1 + j);
					glint[4 * j + 2] = at.z + (TendrilFx.hash(b.seed, 70 + j) - 0.5) * 0.12;
					glint[4 * j + 3] = BEAD_PX * 0.9F * keep * (1F - since / BEAD_TICKS);
				}
				ArmTint.colour(tipRgb(b.tip));
				TendrilFx.beads(f, glint, 2, ArmLayer.GLOW, light, b.seed + 5);
				ArmTint.owner(b.origin);
			}
		}
		if (since < ROOT_TICKS) {
			double[] data = new double[8 * ROOTS];
			float g = since / ROOT_TICKS;
			for (int k = 0; k < ROOTS; k++) {
				double spin = ArmMath.TAU * (k + TendrilFx.hash(b.seed, 40 + k) * 0.5) / ROOTS;
				// back out of the spot: against the heading, splayed round it
				double sx = Math.cos(spin) * 0.6, sz = Math.sin(spin) * 0.6;
				int o = 8 * k;
				data[o] = at.x;
				data[o + 1] = at.y;
				data[o + 2] = at.z;
				data[o + 3] = -dir.x + sx;
				data[o + 4] = -dir.y + 0.3;
				data[o + 5] = -dir.z + sz;
				data[o + 6] = ROOT_PX * keep * (float) Math.sin(ArmMath.PI * Math.min(1F, g * 1.25F));
				data[o + 7] = ROOT_WIDTH * (1F - 0.5F * g);
			}
			TendrilFx.filaments(f, data, ROOTS, ArmLayer.BLACK, light, b.seed + 5);
		}
	}
}
