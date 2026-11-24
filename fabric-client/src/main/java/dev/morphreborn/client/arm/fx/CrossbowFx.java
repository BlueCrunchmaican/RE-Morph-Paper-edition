package dev.morphreborn.client.arm.fx;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.geom.CrossbowGeometry;
import dev.morphreborn.client.arm.geom.FistGeometry;
import dev.morphreborn.client.arm.model.ArmDrawFactory;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.ArmTint;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The crossbow's world side (my design, 2026-11-16: it loads itself, and after each shot the bolt regrows out of the weapon over
 * the vanilla charge time). S_ARM_FX kind {@link ArmProtocol#FX_CROSSBOW}, the move in {@code arg}:
 * <ul>
 *   <li><b>1 BOLT</b> (a shot): {@code ids = [the projectile]}. The server's real, unpickable projectile wears the tendril bolt of
 *       {@link CrossbowGeometry} (a red core with black strands winding round it, vanes, a barbed head with a red point), turned along its
 *       flight and spinning, trailing a black tendril up to 3 blocks that shortens with its speed; the vanilla model isn't drawn for it
 *       ({@link #hides}). On landing the trail snaps off, five beads fly out and four short roots reach back out of the spot. The weapon
 *       in the hand empties its groove at once ({@link CrossbowGeometry#markShot}).</li>
 *   <li><b>2 SPINE BURST</b>: one message for the whole burst, {@code ids = [the spine projectiles]} (three by default, up to
 *       {@link #MAX_SPINES}), all named up front though the server adds each to the world on its own tick, {@code ticks} = the spacing. Each
 *       spine is a pale bone barb (drawn in vanilla's bone, the one thing here that isn't the arm's own skin) with a black tendril tail and a
 *       thin red one winding in it; they pierce, so each flies until the server removes it, then bursts as a bolt does. The muzzle flashes once
 *       for each as it leaves ({@link CrossbowGeometry#markBurst}), and the weapon is empty from the first.</li>
 *   <li><b>3 REGROW</b>: {@code ticks} = the whole length (the vanilla crossbow charge time of the item: 25 ticks, less with Quick Charge),
 *       {@code ids = [ticks left]} (empty: all of them), {@code seed} = the regrow's. The string draws itself back first, then the bolt in the
 *       weapon grows back out of the stock over the rest of it, head last ({@link CrossbowGeometry#markRegrow}). The server sends it again every
 *       10 ticks for viewers who come into range; the same seed is the same regrow and changes nothing, a new seed starts one at
 *       {@code 1 - left / total}. Nothing is drawn in the world.</li>
 * </ul>
 * Whether the weapon is loaded at all comes from the held stack ({@link #loaded}), so a viewer who came into range later sees it right;
 * these effects only ease it. A flight for a projectile that was never seen (out of range, or not spawned yet) draws nothing and times out; one
 * that goes out of sight in flight simply ends, and the burst plays at the last place it was.
 */
public final class CrossbowFx implements FxHandler {
	public static final int BOLT = 1, SPINES = 2, REGROW = 3;
	/** The most spines of one burst that are drawn (the server's spines setting goes up to 12); the rest show as plain arrows. */
	public static final int MAX_SPINES = 12;

	/** The trail's longest reach (blocks), its widths at the end and at the projectile (px), and how long it takes to snap off. */
	private static final float TRAIL_MAX = 3F, TRAIL_TAIL_PX = 0.15F, TRAIL_HEAD_PX = 1.2F, SNAP_TICKS = 3F;
	/** The bolt in the world, in px along its heading from the projectile's position: the rear, where the head begins, and the point. */
	private static final float REAR = -5.2F, HEAD_FROM = 3F, TIP = 5.4F, STRAND_R = 0.55F, TURNS = 2.5F;
	/** The spine: its length from the root, its widest (the root's), the tail it starts. */
	private static final float SPINE_BACK = -4.2F, SPINE_LEN = 9.6F, SPINE_W = 1.3F, SPINE_TAIL_MAX = 2.4F;
	private static final int BEADS = 5, ROOTS = 4;
	private static final float BEAD_TICKS = 6F, BEAD_PX = 0.7F, BEAD_GRAVITY = 0.04F, ROOT_TICKS = 4F, ROOT_PX = 5F, ROOT_WIDTH = 0.5F;
	/** A projectile that doesn't move faster than this (blocks a tick) has landed; the first ticks after launch are never taken for landing. */
	private static final double LANDED_SPEED = 0.05;
	private static final int GRACE_TICKS = 3, LIFE = 140, MAX = 24;
	/** A projectile whose place moves less than this (blocks) for this many ticks in a row has landed, whatever motion it still claims. */
	private static final double STILL_BLOCKS = 0.02;
	private static final int STILL_TICKS = 2;
	/** A flight whose projectile is never seen is given up on after this many ticks (a spine spawned late by the server is waited for). */
	private static final int UNSEEN_TICKS = 24;
	/** Vanilla's bone, for the spines. */
	private static final Identifier BONE = Identifier.withDefaultNamespace("textures/block/bone_block_side.png");

	/** One flight: the projectile, what it is, and where it ended (the last place and way it was seen). */
	private static final class Flight {
		final int origin, projectile, seed, index, waitTicks;
		final boolean spine;
		final long start;
		double x, y, z, dx, dy = -1, dz;
		/** The heading as a Vec3, made when the tick changes it (not every frame), and the place it ended at, made once. */
		Vec3 dir = new Vec3(0, -1, 0);
		@Nullable Vec3 landed;
		/** This flight's own frame (its heading turned to +Z), refilled every frame: its geometry runs after collect returns, and each flight has its own. */
		final Matrix4f frame = new Matrix4f();
		boolean seen;
		/** Ticks in a row the projectile has not moved (its place, not its motion: a stuck one keeps whatever motion it was given). */
		int still;
		/** The tick it landed or vanished (-1: still flying). */
		long ended = -1;

		Flight(int origin, int projectile, boolean spine, int index, int waitTicks, long start, int seed) {
			this.origin = origin;
			this.projectile = projectile;
			this.spine = spine;
			this.index = index;
			this.waitTicks = waitTicks;
			this.start = start;
			this.seed = seed;
		}
	}

	private final List<Flight> flights = new ArrayList<>();
	/**
	 * The projectile entities whose vanilla model isn't drawn: the tendril bolt or the bone spine is drawn in its place, until the entity
	 * is gone. Render thread.
	 */
	private static final java.util.Set<Integer> HIDDEN = new java.util.HashSet<>();

	private static final Static ST = new Static();
	private static final Quaternionf TURN = new Quaternionf();
	private static final Vector3f UP_Y = new Vector3f(0F, 1F, 0F);
	private static final Matrix4f LOCAL = new Matrix4f();
	private static @Nullable RenderType boneType;
	private static final ArmLayer[] BOLT_LAYERS = {ArmLayer.BLACK, ArmLayer.RED};
	/** Scratch for the landing's beads (the larger of the two layers' counts, 3, times 4) and roots (ROOTS times 8). */
	private static final double[] BEADS_DATA = new double[12], ROOTS_DATA = new double[8 * ROOTS];

	public CrossbowFx() {}

	/** Whether the projectile entity {@code id} is one of an arm's crossbow shots, whose vanilla model isn't drawn (the renderer mixin asks, for any entity). */
	public static boolean hides(int id) {
		return !HIDDEN.isEmpty() && HIDDEN.contains(id);
	}

	/**
	 * Whether the crossbow in {@code living}'s main hand has a bolt in it, as its stack says: true for anything that isn't an arm stack
	 * (a tucked arm, a bare hand: nothing to show empty). The arm's drawing asks, so a late joiner sees the weapon right.
	 */
	public static boolean loaded(net.minecraft.world.entity.LivingEntity living) {
		net.minecraft.world.item.ItemStack stack = living.getMainHandItem();
		return !dev.morphreborn.client.arm.ArmStacks.isArm(stack) || net.minecraft.world.item.CrossbowItem.isCharged(stack);
	}

	/** The world being drawn, or null before there is one (and in the unit tests, which have no game). */
	private static @Nullable ClientLevel level() {
		Minecraft mc = Minecraft.getInstance();
		return mc == null ? null : mc.level;
	}

	private static float clock(long tick) {
		return (float) (tick % ArmDrawFactory.CLOCK_WRAP);
	}

	/** How many flights are showing now: for the tests. */
	int active() {
		return flights.size();
	}

	@Override
	public int[] kinds() {
		return new int[] {ArmProtocol.FX_CROSSBOW};
	}

	// ------------------------------------------------------------------ events

	@Override
	public void start(ArmFxEvent e) {
		switch (e.arg()) {
			case BOLT -> {
				// a shot empties the weapon's groove at once, with or without a projectile to show
				CrossbowGeometry.markShot(e.origin(), clock(e.receivedTick()));
				if (e.ids().length == 0) return;
				add(new Flight(e.origin(), e.ids()[0], false, 0, UNSEEN_TICKS, e.receivedTick(), (e.seed() ^ e.ids()[0] * 31) & 0x7FFF));
			}
			case SPINES -> {
				int n = Math.min(e.ids().length, MAX_SPINES);
				CrossbowGeometry.markBurst(e.origin(), clock(e.receivedTick()), e.ticks(), n);
				// the later spines are added to the world later: each is waited for as long as it takes the burst to get to it
				int wait = UNSEEN_TICKS + n * Math.max(0, e.ticks());
				for (int i = 0; i < n; i++) {
					add(new Flight(e.origin(), e.ids()[i], true, i, wait, e.receivedTick(), (e.seed() ^ e.ids()[i] * 31) & 0x7FFF));
				}
			}
			case REGROW -> {
				// ticks = the whole regrow, ids[0] = how many are left (no id: all of them); the same seed as the mark's is the same regrow
				int left = e.ids().length > 0 ? e.ids()[0] : e.ticks();
				CrossbowGeometry.markRegrow(e.origin(), clock(e.receivedTick()), e.ticks(), left, e.seed());
			}
			default -> {
				// an arg a newer server added: nothing to draw
			}
		}
	}

	private void add(Flight f) {
		// one flight per projectile
		flights.removeIf(o -> o.projectile == f.projectile);
		if (flights.size() >= MAX) flights.remove(0);
		flights.add(f);
		HIDDEN.add(f.projectile);
	}

	// ------------------------------------------------------------------ the clock

	@Override
	public void tick() {
		long now = Arms.TICK;
		ClientLevel level = level();
		for (Flight b : flights) {
			if (b.ended >= 0) continue;
			Entity projectile = level == null ? null : level.getEntity(b.projectile);
			if (projectile == null || projectile.isRemoved()) {
				// gone: out of sight or struck and removed; if it was seen, it ends where it was last seen; one never seen is given up on in time
				if (b.seen || now - b.start > b.waitTicks) b.ended = now;
				continue;
			}
			Vec3 at = projectile.position();
			Vec3 v = projectile.getDeltaMovement();
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
				b.dir = new Vec3(b.dx, b.dy, b.dz);
			}
			b.seen = true;
			// a spine spawned later than the others is not judged by the first ticks after the packet but after it appeared
			if (now - b.start >= GRACE_TICKS + b.index * 4 && (speed < LANDED_SPEED || b.still >= STILL_TICKS)) b.ended = now;
		}
		flights.removeIf(b -> now - b.start > LIFE || (b.ended >= 0 && now - b.ended > BEAD_TICKS + 2));
		// stop hiding projectiles that are gone (a stuck one stays hidden until the server removes it), but not a spine that the server has
		// named and not yet added to the world (a burst names all its spines at once): its flight is still waiting for it
		if (!HIDDEN.isEmpty()) {
			for (Iterator<Integer> it = HIDDEN.iterator(); it.hasNext();) {
				int id = it.next();
				Entity projectile = level == null ? null : level.getEntity(id);
				if ((projectile == null || projectile.isRemoved()) && !waiting(id)) it.remove();
			}
		}
	}

	/** Whether a flight for the projectile {@code id} is still waiting for it to appear. */
	private boolean waiting(int id) {
		for (int i = 0; i < flights.size(); i++) {
			Flight b = flights.get(i);
			if (b.projectile == id && !b.seen && b.ended < 0) return true;
		}
		return false;
	}

	@Override
	public void clear() {
		flights.clear();
		HIDDEN.clear();
		CrossbowGeometry.clearMarks();
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void collect(ArmFxFrame f) {
		if (flights.isEmpty()) return;
		ClientLevel level = level();
		if (level == null) return;
		float pt = f.partialTick();
		for (Flight b : flights) {
			ArmTint.owner(b.origin); // this effect is drawn in its owner's colour
			if (!b.seen) continue;
			Entity projectile = level.getEntity(b.projectile);
			boolean flying = b.ended < 0 && projectile != null && !projectile.isRemoved();
			Vec3 at;
			if (flying) at = projectile.getPosition(pt);
			else {
				if (b.landed == null) b.landed = new Vec3(b.x, b.y, b.z);
				at = b.landed;
			}
			if (!TendrilFx.finite(at)) continue;
			Vec3 dir = b.dir;
			int light = TendrilFx.light(level, at, at);
			if (flying) {
				float age = Arms.age(b.start, pt);
				double speed = projectile.getDeltaMovement().length();
				if (b.spine) spine(f, b, at, dir, light, b.seed, age);
				else bolt(f, b, at, dir, light, b.seed, age);
				trail(f, at, dir, speed, light, b.seed, 1F, b.spine);
			} else {
				float since = Arms.age(b.ended, pt);
				if (since < SNAP_TICKS) trail(f, at, dir, 1.5, light, b.seed, 1F - ArmMath.sm(since / SNAP_TICKS), b.spine);
				burst(f, b, at, dir, since, light);
			}
		}
	}

	/**
	 * The trail: a tendril from up to {@link #TRAIL_MAX} blocks behind the projectile (less when it is slow) to it, {@code share} of it
	 * left (the part nearest the projectile stays while it snaps off). A spine's is shorter and gets a thin red one winding in it.
	 */
	private static void trail(ArmFxFrame f, Vec3 at, Vec3 dir, double speed, int light, int seed, float share, boolean spine) {
		if (!(share > 0.01F)) return;
		double len = Math.min(spine ? SPINE_TAIL_MAX : TRAIL_MAX, 0.4 + speed * (spine ? 0.8 : 1.1));
		Vec3 cutFrom = at.subtract(dir.scale(len * share));
		float widen = 1F - share;
		TendrilFx.draw(f, cutFrom, at, 1F, seed * 0.37F, TRAIL_TAIL_PX + (TRAIL_HEAD_PX - TRAIL_TAIL_PX) * widen, TRAIL_HEAD_PX, null, ArmLayer.BLACK, light, TendrilFx.ACTIVE);
		if (spine) {
			TendrilFx.draw(f, cutFrom, at, 1F, seed * 0.37F + 1.9F, 0.1F + 0.3F * widen, 0.55F, null, ArmLayer.RED, light, TendrilFx.ACTIVE);
		}
	}

	/** The flight's own frame, refilled: the one that takes +Z to {@code dir} (the geometry lambda runs after this returns, so each flight keeps its own). */
	private static Matrix4f frameAlong(Flight b, Vec3 dir, float spin) {
		return b.frame.identity().rotate(TURN.rotationTo(0F, 0F, 1F, (float) dir.x, (float) dir.y, (float) dir.z)).rotateZ(spin);
	}

	// ------------------------------------------------------------------ the bolt

	/**
	 * The bolt: the weapon's own, along its heading (+Z here): a red core, two black strands winding round it, three vanes at the rear
	 * (alternating red and black), a black head with three barbs swept back and a red point. It spins as it flies, the strands too.
	 */
	private static void bolt(ArmFxFrame f, Flight b, Vec3 at, Vec3 dir, int light, int seed, float age) {
		float time = f.time();
		float spin = 0.35F * time + seed * 0.1F;
		Matrix4f frame = frameAlong(b, dir, spin);
		for (ArmLayer layer : BOLT_LAYERS) {
			TendrilFx.submit(f, at, layer, light, m -> {
				m.transform(frame);
				ST.set(time, TendrilFx.ACTIVE, 1F, seed);
				float total = HEAD_FROM - REAR;
				if (layer == ArmLayer.BLACK) {
					for (int k = 0; k < 2; k++) {
						final int kk = k;
						Loft.CurveFn strand = (s, out) -> {
							float ang = ArmMath.TAU * TURNS * s + kk * ArmMath.PI;
							float r = STRAND_R * (1F + 0.06F * (float) Math.sin(0.5F * time + seed + kk));
							out.set(r * (float) Math.cos(ang), r * (float) Math.sin(ang), REAR + total * s);
						};
						Loft.tube(m, strand, 0F, 1F, 7, 4, s -> FistGeometry.width(0.55F * (1F - 0.3F * s), 4), ArmLayer.BLACK, layer, ST, 50 + k, 2F, 8F, 8F + total, UP_Y, 1F);
					}
					// the head's collar and three barbs swept back
					LOCAL.identity().translate(0F, 0F, HEAD_FROM - 0.4F);
					m.frustum(LOCAL, 1.5F, 0.9F, 0.9F, 1.5F, 1.5F, ST.u0(52, 0, 0F), ST.v0(52, 0, 0F), 1.5F, 1F, 1F, SkinMesh.WHITE);
					for (int k = 0; k < 3; k++) {
						float a = ArmMath.TAU * k / 3F + 0.5F * (float) Math.sin(0.1F * time + k);
						float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
						LOCAL.identity().translate(0.7F * ca, 0.7F * sa, HEAD_FROM + 1.1F).rotateTowards(0.6F * ca, 0.6F * sa, -0.7F, 0F, 1F, 0F);
						m.frustum(LOCAL, 1.2F, 0.45F, 0.45F, 0.05F, 0.05F, ST.u0(53, k, 0F), ST.v0(53, k, 0F), 2F, 1F, 1F, SkinMesh.WHITE);
					}
				}
				vanes(m, layer, time);
				if (layer == ArmLayer.RED) {
					Loft.CurveFn core = (s, out) -> out.set(0F, 0F, REAR + (total + 1.6F) * s);
					Loft.tube(m, core, 0F, 1F, 4, 4, s -> FistGeometry.width(0.65F * (1F - 0.8F * s * s), 4), ArmLayer.RED, layer, ST, 57, 2F, 8F, 8F + total, UP_Y, 1F);
					LOCAL.identity().translate(0F, 0F, HEAD_FROM + 1.1F);
					m.frustum(LOCAL, TIP - HEAD_FROM - 1.1F + 0.8F, 1.4F, 1.4F, 0.05F, 0.05F, ST.u0(58, 0, 0F), ST.v0(58, 0, 0F), 1.5F, 1F, 1F, SkinMesh.WHITE);
				}
			});
		}
	}

	/** Three vanes at the rear, alternating (the middle one is red). */
	private static void vanes(SkinMesh m, ArmLayer layer, float time) {
		for (int k = 0; k < 3; k++) {
			if ((k == 1) != (layer == ArmLayer.RED)) continue;
			float a = ArmMath.TAU * k / 3F + 0.15F * (float) Math.sin(0.2F * time + k);
			LOCAL.identity().translate(0F, 0F, REAR + 0.5F).rotateZ(a);
			m.box(LOCAL, 0.3F, -0.05F, -0.55F, 2F, 0.1F, 1.3F, ST.u0(59, k, 0F), ST.v0(59, k, 0F), 2F, 1F, SkinMesh.WHITE);
		}
	}

	// ------------------------------------------------------------------ the spine

	private static RenderType boneType() {
		RenderType t = boneType;
		if (t == null) boneType = t = RenderTypes.entityCutout(BONE);
		return t;
	}

	/**
	 * A spine: a pale bone barb, a four-sided spear with two small barbs back along it, point first, spinning slowly as it flies; its tail
	 * (the tendrils of {@link #trail}) comes out of its root. Drawn in vanilla's bone texture, lit by the world.
	 */
	private static void spine(ArmFxFrame f, Flight b, Vec3 at, Vec3 dir, int light, int seed, float age) {
		float time = f.time();
		Matrix4f frame = frameAlong(b, dir, 0.18F * time + seed * 0.1F);
		Vec3 cam = f.camPos();
		PoseStack poseStack = f.poseStack();
		poseStack.pushPose();
		poseStack.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
		poseStack.scale(1F / 16F, 1F / 16F, 1F / 16F);
		f.collector().submitCustomGeometry(poseStack, boneType(), (pose, buffer) -> {
			SkinMesh m = SkinMesh.begin(pose, buffer, light, OverlayTexture.NO_OVERLAY);
			m.transform(frame);
			// the shaft tapering to its point, and its two small barbs (the second turned a quarter)
			LOCAL.identity().translate(0F, 0F, SPINE_BACK);
			m.frustum(LOCAL, SPINE_LEN, SPINE_W, SPINE_W * 0.8F, 0.06F, 0.06F, 20F, 20F, 1.5F, 1F, 1F, SkinMesh.WHITE);
			for (int k = 0; k < 2; k++) {
				float a = ArmMath.PI / 2F * k;
				float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
				LOCAL.identity().translate(0.55F * ca, 0.55F * sa, SPINE_BACK + 2.4F).rotateTowards(0.7F * ca, 0.7F * sa, 0.8F, 0F, 1F, 0F);
				m.frustum(LOCAL, 1.7F, 0.5F, 0.5F, 0.05F, 0.05F, 34F, 20F, 1.5F, 1F, 1F, SkinMesh.WHITE);
			}
		});
		poseStack.popPose();
	}

	// ------------------------------------------------------------------ the landing

	/**
	 * The landing: five beads (red and black) flying out of the spot and falling, shrinking away over 6 ticks, and four short roots
	 * reaching back out of it (opposite the heading, splayed) for 4 ticks. A spine's is smaller.
	 */
	private static void burst(ArmFxFrame f, Flight b, Vec3 at, Vec3 dir, float since, int light) {
		if (since < 0F) return;
		float scale = b.spine ? 0.75F : 1F;
		if (since < BEAD_TICKS) {
			for (int layerIdx = 0; layerIdx < 2; layerIdx++) {
				ArmLayer layer = layerIdx == 0 ? ArmLayer.RED : ArmLayer.BLACK;
				int count = layerIdx == 0 ? 3 : 2;
				double[] data = BEADS_DATA; // TendrilFx copies it at once, so one buffer serves every flight
				for (int j = 0; j < count; j++) {
					int idx = layerIdx == 0 ? j : j + 3;
					double spin = ArmMath.TAU * TendrilFx.hash(b.seed, idx);
					double speed = 0.04 + 0.04 * TendrilFx.hash(b.seed, idx + 17);
					double vx = -dir.x * 0.04 + Math.cos(spin) * speed, vz = -dir.z * 0.04 + Math.sin(spin) * speed;
					double vy = -dir.y * 0.04 + 0.05 + 0.05 * TendrilFx.hash(b.seed, idx + 29);
					data[4 * j] = at.x + vx * since;
					data[4 * j + 1] = at.y + vy * since - 0.5 * BEAD_GRAVITY * since * since;
					data[4 * j + 2] = at.z + vz * since;
					data[4 * j + 3] = BEAD_PX * scale * (1F - since / BEAD_TICKS);
				}
				TendrilFx.beads(f, data, count, layer, light, b.seed + 3 + layerIdx);
			}
		}
		if (since < ROOT_TICKS) {
			double[] data = ROOTS_DATA;
			float g = since / ROOT_TICKS;
			for (int k = 0; k < ROOTS; k++) {
				double spin = ArmMath.TAU * (k + TendrilFx.hash(b.seed, 40 + k) * 0.5) / ROOTS;
				double sx = Math.cos(spin) * 0.6, sz = Math.sin(spin) * 0.6;
				int o = 8 * k;
				data[o] = at.x;
				data[o + 1] = at.y;
				data[o + 2] = at.z;
				data[o + 3] = -dir.x + sx;
				data[o + 4] = -dir.y + 0.3;
				data[o + 5] = -dir.z + sz;
				data[o + 6] = ROOT_PX * scale * (float) Math.sin(ArmMath.PI * Math.min(1F, g * 1.25F));
				data[o + 7] = ROOT_WIDTH * (1F - 0.5F * g);
			}
			TendrilFx.filaments(f, data, ROOTS, ArmLayer.BLACK, light, b.seed + 5);
		}
	}
}
