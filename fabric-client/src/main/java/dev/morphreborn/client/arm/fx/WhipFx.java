package dev.morphreborn.client.arm.fx;

import dev.morphreborn.client.MorphClient;
import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmStacks;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.geom.FistGeometry;
import dev.morphreborn.client.arm.geom.WhipGeometry;
import dev.morphreborn.client.arm.geom.WhipLineShape;
import dev.morphreborn.client.arm.model.ArmDrawFactory;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.ArmTint;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import dev.morphreborn.client.arm.render.Stations;
import dev.morphreborn.client.arm.render.TendrilPath;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The whip's world side (section 9): the Lash special's crack, and the fishing line a whip casts.
 * <p>
 * <b>The lash</b> (S_ARM_FX kind 13: {@code ids} the creature hit or none, {@code positions} the end point,
 * {@code arg} 1 hit a creature, 2 a block, 0 a miss, {@code ticks} 6, {@code level} the tier). A braided tendril, black with
 * red strands twisting round it (none, one, two by tier), shoots from the whip's tip to the end point in 2 ticks with a
 * wave running down it: {@code 0.4·sin(9s − 1.2t)} across the line and half as much, a quarter-turn behind, up and down,
 * its crests pinned still at both ends and dying away after the crack. At 2 ticks it cracks: a flat red ribbon flares for
 * 3 ticks, six hair filaments spray from the end point, and the client plays the breeze's whirl with a sculk charge's
 * tick; then the lash draws back toward the whip over 4. The end is the creature's wound while it is there, and the block's
 * centre otherwise. Every viewer who draws arms gets this packet (within 96 blocks), so everyone sees the same crack.
 * <p>
 * <b>The line.</b> A whip is a fishing rod, so its hook is a vanilla {@code FishingHook}; the mixin on the hook's renderer
 * sends the hooks of armed players here instead of drawing the vanilla string and bobber. Each is drawn at the end of the
 * frame's entity submits (when the whip's tip has been recorded, whichever of the player and the hook the game submitted
 * first): a tendril from the whip's tip to the hook, 1.4 px thick at the whip and 0.5 at the hook, writhing a little; and
 * the bobber as a three-pronged claw, black with a red band round its core and red tips, that opens on the water and closes
 * over whatever the hook catches. Vanilla viewers don't have the mixin and see the vanilla line.
 * <p>
 * <b>The grapple look</b> (decided 2026-11-16: a blend of the arcing chain throw and the straight tendril shot;
 * the maths is {@link WhipLineShape}). While the hook is floating the line hangs in the vanilla line's own slack
 * ({@code y·(a² + a)/2}), as fishing always did. While it flies the line leaves taut and fast, nearly straight like a shot,
 * but trails along the hook's real ballistic path as a thrown chain does (an arc whose tangent at the hook is the hook's
 * heading, a light sag, braided chain links riding its last stretch before the hook). When the hook catches (a block, the
 * ground, a creature: still for 2.5 ticks, or caught) the arc snaps straight in about two ticks, shivers and goes taut for
 * the reel, the links staying, and the claw grips. The hook's state comes from the hook entity every viewer has; nothing
 * is sent for it.
 * <p>
 * Everything shown here comes from what every modded viewer has: the packet, the hook entity in the world and the held stack.
 */
public final class WhipFx implements FxHandler {
	/** The mixin's hand-off: what the renderer state of an armed player's hook carries from extraction to its submit. */
	public record Hook(int owner, int tier, boolean hooked, boolean inWater, float vx, float vy, float vz) {}

	/** Where a hook's whip data rides on its render state. */
	public static final RenderStateDataKey<Hook> HOOK = RenderStateDataKey.create(() -> "morphreborn:whip_hook");

	/** Ticks out, ticks the crack's flash lasts, and the whole lash's life (it draws back from tick 2 to 6). */
	private static final float OUT = 2F, FLASH = 3F, DRAW_BACK = 4F;
	private static final int LIFE = 7, MAX = 8;
	/** The lash's wave: its crest size in blocks, its wave number and speed (spec 9: 0.4·sin(9s − 1.2t)). */
	private static final float WAVE_BLOCKS = 0.4F, WAVE_K = 9F, WAVE_SPEED = 1.2F;
	/** The lash's widths at the whip and at the end (px). */
	private static final float LASH_ROOT = 1.6F, LASH_TIP = 0.5F;
	/** The line's widths at the whip and at the hook (px), and its writhe (blocks). */
	private static final float LINE_ROOT = 1.4F, LINE_TIP = 0.5F, LINE_WRITHE = 0.05F;
	/** Hairs sprayed at the crack: how many, their life in ticks, length and width (px). */
	private static final int HAIRS = 6;
	private static final float HAIR_TICKS = 4F, HAIR_PX = 3F, HAIR_WIDTH = 0.4F;
	/** A miss with no end point reaches this far (blocks) by tier. */
	private static final float[] RANGE = {0F, 8F, 10F, 12F};

	private static final class Lash {
		final int origin, tier, arg, seed;
		final long start;
		final int target;
		/** The end point when it isn't a creature: the block's centre (or the miss's far point). */
		final Vec3 fixed;
		boolean sounded;

		Lash(int origin, int tier, int arg, int seed, long start, int target, Vec3 fixed) {
			this.origin = origin;
			this.tier = tier;
			this.arg = arg;
			this.seed = seed;
			this.start = start;
			this.target = target;
			this.fixed = fixed;
		}
	}

	/** A hook drawn this frame, queued by the renderer mixin for the end of the frame's submits. */
	private record Queued(Hook hook, double x, double y, double z, int light) {}

	private static final List<Queued> HOOKS = new ArrayList<>();
	/**
	 * What one owner's hook has been doing: the line's shape easing with it (flying, caught, floating) and how closed the claw
	 * is (0 open, 1 shut) with the clock it moved on. One object per owner in an int-keyed map, so a frame's lookups box and
	 * allocate nothing (fix round, 2026-11-16).
	 */
	private static final class Owner {
		final WhipLineShape.Motion motion;
		/** Negative until the claw is first drawn. */
		float claw = -1F, clawClock;
		/** Whether this cast's "hidden behind your own body" line is already in the log (see {@link #reportCover}). */
		boolean coverLogged;

		Owner(WhipLineShape.Motion motion) {
			this.motion = motion;
		}
	}

	private static final Int2ObjectOpenHashMap<Owner> OWNERS = new Int2ObjectOpenHashMap<>();
	/** The reusable draws of this frame's lines, and how many are in use (see {@link LineDraw}). */
	private static final List<LineDraw> DRAWS = new ArrayList<>();
	private static int drawsUsed;
	private final List<Lash> lashes = new ArrayList<>();

	private static final Static ST = new Static();
	private static final float[] POLY = new float[8];
	private static final float[] RIBBON = new float[4];
	private static final Quaternionf TURN = new Quaternionf();
	private static final Matrix4f CLAW_M = new Matrix4f();
	private static final Vector3f LOCAL = new Vector3f();

	public WhipFx() {}

	@Override
	public int[] kinds() {
		return new int[] {ArmProtocol.FX_WHIP};
	}

	@Override
	public void start(ArmFxEvent e) {
		ClientLevel level = Minecraft.getInstance().level;
		int tier = Math.clamp(e.level(), 1, 3);
		int target = e.arg() == 1 && e.ids().length > 0 ? e.ids()[0] : -1;
		Vec3 fixed = null;
		if (e.positions().length > 0) fixed = Vec3.atCenterOf(BlockPos.of(e.positions()[0]));
		if (fixed == null && level != null && level.getEntity(e.origin()) instanceof Entity origin) {
			// nothing said where it ended: along the look, as far as the lash reaches
			fixed = origin.getEyePosition().add(origin.getLookAngle().scale(RANGE[tier]));
		}
		// the hand's own lash coils away as this one goes out, on every viewer's draw of that arm
		WhipGeometry.markLash(e.origin(), (float) (e.receivedTick() % ArmDrawFactory.CLOCK_WRAP));
		if (target < 0 && fixed == null) return;
		if (lashes.size() >= MAX) lashes.remove(0);
		lashes.add(new Lash(e.origin(), tier, e.arg(), (e.seed() ^ e.origin() * 31) & 0x7FFF, e.receivedTick(), target, fixed == null ? Vec3.ZERO : fixed));
	}

	@Override
	public void tick() {
		long now = Arms.TICK;
		ClientLevel level = Minecraft.getInstance().level;
		for (Lash l : lashes) {
			if (!l.sounded && now - l.start >= OUT && now - l.start < LIFE && level != null) {
				l.sounded = true;
				Vec3 at = endPoint(level, l, 1F);
				level.playLocalSound(at.x, at.y, at.z, SoundEvents.BREEZE_WHIRL, SoundSource.PLAYERS, 0.5F, 1.8F, false);
				level.playLocalSound(at.x, at.y, at.z, SoundEvents.SCULK_BLOCK_CHARGE, SoundSource.PLAYERS, 0.4F, 1.6F, false);
			}
		}
		lashes.removeIf(l -> now - l.start > LIFE);
		// lines and claws of hooks not drawn for a while are forgotten
		float clock = (float) (now % ArmDrawFactory.CLOCK_WRAP);
		OWNERS.values().removeIf(o -> WhipGeometry.ageOf(clock, o.clawClock) > 60F && WhipGeometry.ageOf(clock, o.motion.stamp) > 60F);
	}

	@Override
	public void clear() {
		lashes.clear();
		HOOKS.clear();
		OWNERS.clear();
		WhipGeometry.clearMarks();
	}

	// ------------------------------------------------------------------ the renderer mixin's side

	/**
	 * What {@code hook} carries for the renderer: the owner's id, the whip's tier and whether it has caught something
	 * (a creature, or the ground), or null when its owner doesn't hold a whip (then the vanilla line draws).
	 */
	public static @Nullable Hook hookOf(FishingHook hook) {
		Player owner = hook.getPlayerOwner();
		if (owner == null) return null;
		int tier = whipTier(owner);
		if (tier <= 0) return null;
		boolean water = hook.isInWater();
		boolean hooked = hook.getHookedIn() != null || hook.onGround() && !water;
		// the heading and speed (blocks per tick) shape the line while the hook flies: it trails along the hook's ballistic path
		Vec3 v = hook.getDeltaMovement();
		return new Hook(owner.getId(), tier, hooked, water, (float) v.x, (float) v.y, (float) v.z);
	}

	/** The tier of the whip {@code owner} holds in either hand (the one a rod's line comes from), or 0. */
	public static int whipTier(Player owner) {
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack stack = owner.getItemInHand(hand);
			if (stack.isEmpty()) continue;
			ItemDisplayContext ctx = hand == InteractionHand.MAIN_HAND ? ItemDisplayContext.THIRD_PERSON_RIGHT_HAND : ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
			ArmStacks.Recognized r = ArmStacks.recognize(stack, owner, ctx);
			if (r != null && ArmProtocol.WHIP.equals(r.form())) return r.tier();
		}
		return 0;
	}

	/** The renderer mixin: a whip's hook was submitted at ({@code x, y, z}); it is drawn at the end of the frame's submits. */
	public static void queue(Hook hook, double x, double y, double z, int light) {
		if (HOOKS.size() < 32) HOOKS.add(new Queued(hook, x, y, z, light));
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void collect(ArmFxFrame f) {
		ClientLevel level = Minecraft.getInstance().level;
		drawsUsed = 0;
		try {
			if (level == null) return;
			for (Queued q : HOOKS) {
				ArmTint.owner(q.hook().owner()); // each line and lash is drawn in its owner's colour
				line(f, level, q);
			}
			for (Lash l : lashes) {
				ArmTint.owner(l.origin);
				lash(f, level, l);
			}
		} finally {
			HOOKS.clear();
		}
	}

	// ------------------------------------------------------------------ the Lash

	/** Where the lash ends: the creature's wound while it is there, else the fixed point. */
	private static Vec3 endPoint(ClientLevel level, Lash l, float pt) {
		if (l.target >= 0 && level.getEntity(l.target) instanceof Entity e && !e.isRemoved()) return TendrilFx.wound(e, pt);
		return l.fixed;
	}

	/** The lash's reach {@code age} ticks in: out fast (eased) over 2 ticks, then drawn back over 4. */
	static float reach(float age) {
		if (age < OUT) {
			float x = 1F - ArmMath.clamp01(age / OUT);
			return 1F - x * x;
		}
		return 1F - ArmMath.sm((age - OUT) / DRAW_BACK);
	}

	/** The wave's size after the crack: full until it, then dying away. */
	static float decay(float age) {
		return age <= OUT ? 1F : (float) Math.exp(-0.5F * (age - OUT));
	}

	/**
	 * The lash's wave across the line at {@code s} (0..1) {@code age} ticks in, as a fraction of its crest: {@code sin(9s −
	 * 1.2t)} under an envelope that holds both ends still.
	 */
	static float wave(float s, float age) {
		return (float) (Math.sin(WAVE_K * s - WAVE_SPEED * age) * Math.sin(Math.PI * s));
	}

	private void lash(ArmFxFrame f, ClientLevel level, Lash l) {
		float pt = f.partialTick();
		float age = Arms.age(l.start, pt);
		if (age < 0F || age > LIFE) return;
		float reach = reach(age);
		if (!(reach > 0.01F)) return;
		Vec3 from = ArmFx.anchor(l.origin, ArmTips.TIP);
		Vec3 to = endPoint(level, l, pt);
		if (from == null || to == null || !TendrilFx.finite(from) || !TendrilFx.finite(to)) return;
		Vec3 line = to.subtract(from);
		double len = line.length();
		if (len < 0.3) return;
		int light = TendrilFx.light(level, from, to);
		Vec3 dir = line.scale(1 / len);
		Vec3 side = dir.cross(new Vec3(0, 1, 0));
		side = side.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : side.normalize();
		Vec3 up = side.cross(dir).normalize();
		float px = 16F;
		float amp = WAVE_BLOCKS * px * Math.min(1F, (float) len / 5F) * decay(age);
		float lenPx = (float) len * px;
		float dx = (float) line.x * px, dy = (float) line.y * px, dz = (float) line.z * px;
		float sx = (float) side.x, sy = (float) side.y, sz = (float) side.z, ux = (float) up.x, uy = (float) up.y, uz = (float) up.z;
		float time = f.time();
		int seed = l.seed;
		int pieces = TendrilPath.pieces((float) len);
		int tier = l.tier;
		float r = reach;
		TendrilFx.submit(f, from, ArmLayer.BLACK, light, m -> {
			ST.set(time, TendrilFx.ACTIVE, 1F, seed);
			Loft.tube(m, (s, out) -> lashAt(s, lenPx, dx, dy, dz, sx, sy, sz, ux, uy, uz, amp, age, seed, out), 0F, r, Math.max(3, Math.round(pieces * r) + 1), 4,
					s -> FistGeometry.width(ArmMath.lerp(LASH_ROOT, LASH_TIP, s), 4), ArmLayer.BLACK, ArmLayer.BLACK, ST, 70, 1F, 0F, lenPx, UP_Y, 1F);
		});
		if (tier >= 2) {
			int strands = tier - 1;
			for (int k = 0; k < strands; k++) {
				float phase = ArmMath.TAU * k / strands;
				int part = 71 + k;
				TendrilFx.submit(f, from, ArmLayer.RED, light, m -> {
					ST.set(time, TendrilFx.ACTIVE, 1F, seed + 1 + part);
					Loft.tube(m, (s, out) -> {
						lashAt(s, lenPx, dx, dy, dz, sx, sy, sz, ux, uy, uz, amp, age, seed, out);
						float rho = 0.55F * ArmMath.lerp(LASH_ROOT, LASH_TIP, s);
						float psi = phase + s * lenPx / 4F - 0.5F * age;
						out.add(rho * ((float) Math.cos(psi) * sx + (float) Math.sin(psi) * ux), rho * ((float) Math.cos(psi) * sy + (float) Math.sin(psi) * uy),
								rho * ((float) Math.cos(psi) * sz + (float) Math.sin(psi) * uz));
					}, 0F, r, Math.max(3, Math.round(pieces * r) + 1), 4, s -> FistGeometry.width(0.5F * ArmMath.lerp(LASH_ROOT, LASH_TIP, s), 4), ArmLayer.RED,
							ArmLayer.RED, ST, part, 1.5F, 0F, lenPx, UP_Y, 1F);
				});
			}
		}
		crack(f, l, from, to, age, light, lenPx, dx, dy, dz, sx, sy, sz, ux, uy, uz, amp, time);
	}

	private static final Vector3f UP_Y = new Vector3f(0F, 1F, 0F);

	/**
	 * The lash's path in px from its start at {@code s} (0..1): along the line, with the wave across it ({@code
	 * 0.4·sin(9s − 1.2t)}, in blocks) and half as much up and down a quarter-turn behind, both held still at the ends.
	 */
	static void lashAt(float s, float lenPx, float dx, float dy, float dz, float sx, float sy, float sz, float ux, float uy, float uz, float amp, float age,
			int seed, Vector3f out) {
		float w1 = amp * wave(s, age);
		float w2 = 0.5F * amp * (float) (Math.cos(WAVE_K * s - WAVE_SPEED * age + 0.3F * seed) * Math.sin(Math.PI * s));
		out.set(dx * s + sx * w1 + ux * w2, dy * s + sy * w1 + uy * w2, dz * s + sz * w1 + uz * w2);
	}

	/** The crack at the end (age 2..5): a red ribbon flashing along the lash, and hairs spraying from the end point. */
	private void crack(ArmFxFrame f, Lash l, Vec3 from, Vec3 to, float age, int light, float lenPx, float dx, float dy, float dz, float sx, float sy, float sz, float ux,
			float uy, float uz, float amp, float time) {
		float flash = (age - OUT) / FLASH;
		if (flash >= 0F && flash < 1F && reach(age) > 0.5F) {
			float wide = (1F - flash) * 1.6F;
			int seed = l.seed;
			float reach = Math.min(1F, reach(age) * 1.05F);
			RIBBON[0] = -wide / 2F;
			RIBBON[1] = 0F;
			RIBBON[2] = wide / 2F;
			RIBBON[3] = 0F;
			TendrilFx.submit(f, from, ArmLayer.RED, light, m -> {
				ST.set(time, TendrilFx.ACTIVE, 1F, seed + 5);
				int n = Math.clamp((int) Math.ceil(lenPx / 8F), 3, 24);
				Stations s = RIBBON_STATIONS.reset(2);
				float v = 0F;
				float px = 0F, py = 0F, pz = 0F;
				for (int i = 0; i <= n; i++) {
					float u = reach * i / n;
					lashAt(u, lenPx, dx, dy, dz, sx, sy, sz, ux, uy, uz, amp, age, seed, LOCAL);
					// the strip stands on its edge across the line's up: wide along the line's side
					if (i > 0) v += (float) Math.sqrt((LOCAL.x - px) * (LOCAL.x - px) + (LOCAL.y - py) * (LOCAL.y - py) + (LOCAL.z - pz) * (LOCAL.z - pz));
					px = LOCAL.x;
					py = LOCAL.y;
					pz = LOCAL.z;
					POLY[0] = -wide / 2F;
					POLY[1] = 0F;
					POLY[2] = wide / 2F;
					POLY[3] = 0F;
					s.add(LOCAL.x, LOCAL.y, LOCAL.z, dx, dy, dz, sx, sy, sz, v, POLY, 1F);
				}
				Loft.sweep(m, s, ArmLayer.RED, (seg, face) -> ArmLayer.RED, ST, 74, 2F, false, false);
			});
		}
		float hu = (age - OUT) / HAIR_TICKS;
		if (hu >= 0F && hu <= 1F && l.arg != 0) {
			double[] data = new double[8 * HAIRS];
			Vec3 back = from.subtract(to).normalize();
			for (int k = 0; k < HAIRS; k++) {
				double spin = ArmMath.TAU * TendrilFx.hash(l.seed, k);
				double lift = 0.4 + 0.6 * TendrilFx.hash(l.seed + 3, k);
				int o = 8 * k;
				data[o] = to.x;
				data[o + 1] = to.y;
				data[o + 2] = to.z;
				data[o + 3] = back.x * 0.6 + Math.cos(spin) * lift;
				data[o + 4] = back.y * 0.6 + 0.3 + Math.sin(spin) * lift * 0.6;
				data[o + 5] = back.z * 0.6 + Math.sin(spin) * lift;
				data[o + 6] = HAIR_PX * (1F - hu) * (0.7F + 0.6F * (float) TendrilFx.hash(l.seed + 9, k));
				data[o + 7] = HAIR_WIDTH * (1F - 0.7F * hu);
			}
			TendrilFx.filaments(f, data, HAIRS, ArmLayer.BLACK, light, l.seed + 2);
		}
	}

	private static final Stations RIBBON_STATIONS = new Stations();

	// ------------------------------------------------------------------ the line and its claw

	private void line(ArmFxFrame f, ClientLevel level, Queued q) {
		Hook h = q.hook;
		Vec3 tip = ArmFx.anchor(h.owner(), ArmTips.TIP);
		if (tip == null || !TendrilFx.finite(tip)) return;
		float pt = f.partialTick();
		float clock = (float) ((double) Arms.TICK % ArmDrawFactory.CLOCK_WRAP) + pt;
		WhipGeometry.markCast(h.owner(), clock);
		// the vanilla string ends 0.25 above the hook's centre
		Vec3 hook = new Vec3(q.x, q.y + 0.25, q.z);
		float px = 16F;
		float dx = (float) (tip.x - hook.x) * px, dy = (float) (tip.y - hook.y) * px, dz = (float) (tip.z - hook.z) * px;
		float lenPx = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		int light = TendrilFx.light(level, tip, hook);
		// what the hook is doing: flying (arcs behind it), caught (snaps taut for the reel) or floating (vanilla's slack)
		int first = h.hooked() ? WhipLineShape.ANCHORED : h.inWater() ? WhipLineShape.RESTING : WhipLineShape.FLYING;
		Owner own = OWNERS.get(h.owner());
		if (own == null) {
			own = new Owner(new WhipLineShape.Motion(first));
			own.motion.stamp = clock;
			OWNERS.put(h.owner(), own);
		}
		WhipLineShape.Motion mo = own.motion;
		mo.update(WhipGeometry.ageOf(clock, mo.stamp), h.inWater(), h.hooked(), h.vx(), h.vy(), h.vz());
		mo.stamp = clock;
		float closed = closed(own, h.hooked() || mo.anchored(), f.time());
		LineDraw d = draw(drawsUsed++);
		float time = f.time();
		d.time = time;
		d.seed = h.owner() & 0x7FFF;
		d.lenPx = lenPx;
		d.nLinks = 0;
		// your own view keeps the line and the claw off the lens (NearCamera): with First-person Model the body's whip tip is half a
		// block from the camera and a new hook starts at the eye, so for a frame the line filled the view (the rig, 2026-11-16)
		d.s0 = 0F;
		d.s1 = 1F;
		d.clawK = 1F;
		if (ArmFx.isOwnFirstPerson(h.owner(), f.firstPersonCamera())) {
			Vec3 cam = f.camPos();
			NearCamera.outsideSphere(tip.x - cam.x, tip.y - cam.y, tip.z - cam.z, hook.x - cam.x, hook.y - cam.y, hook.z - cam.z, NearCamera.CLEAR, OUTSIDE);
			d.s0 = (float) OUTSIDE[0];
			d.s1 = (float) OUTSIDE[1];
			// the claw's centre is 0.1 above the hook's point; plain arithmetic, no Vec3, as this runs per line per frame
			double cx = q.x - cam.x, cy = q.y + 0.1 - cam.y, cz = q.z - cam.z;
			d.clawK = NearCamera.fade(Math.sqrt(cx * cx + cy * cy + cz * cz), NearCamera.CLEAR, NearCamera.BAND);
		} else {
			reportCover(own, h.owner(), f, tip, hook);
		}
		if (lenPx > 0.8F) {
			float lenBlocks = lenPx / px;
			float writhe = LINE_WRITHE * px * Math.min(1F, lenBlocks / 6F);
			// drawn from the whip (s = 0) to the hook (s = 1): the vanilla string's own points run the other way, a from 1 to 0
			// while the arc eases out after a catch or a splashdown the line keeps the heading it flew on: its bulge and sag fade
			// with the arc, instead of the bulge vanishing at once and the sag jumping when the hook's speed falls to nothing
			boolean easing = mo.mode() != WhipLineShape.FLYING && mo.arc > 0F;
			d.shape.aim(dx, dy, dz).trail(easing ? mo.hx : h.vx(), easing ? mo.hy : h.vy(), easing ? mo.hz : h.vz())
					.mix(mo, writhe, h.owner() * 0.37F, time);
			// a flying or caught line has curve to show, so it gets stations to show it with
			d.pieces = Math.max(TendrilPath.pieces(lenBlocks), mo.slack < 1F ? 8 : 0);
			// braided links near the hook (the chain's end of the blend), all of them while the line is flying or caught
			float linkS = ArmMath.sm((1F - mo.slack) * 1.25F);
			float span = Math.min(LINK_SPAN, 0.5F * lenPx);
			d.nLinks = linkS > 0.05F ? Math.clamp((int) (span / LINK_PITCH), 0, MAX_LINKS) : 0;
			d.linkS = linkS;
			if (d.s1 - d.s0 > 0.02F) {
				TendrilFx.submit(f, hook, ArmLayer.BLACK, light, d.lineBlack);
				TendrilFx.submit(f, hook, ArmLayer.RED, light, d.lineRed);
			}
		}
		Vec3 centre = new Vec3(q.x, q.y + 0.1, q.z);
		d.spin = 0.04F * time + h.owner();
		d.closed = closed;
		int lit = q.light;
		if (d.clawK > 0.01F) {
			TendrilFx.submit(f, centre, ArmLayer.BLACK, lit, d.clawBlack);
			TendrilFx.submit(f, centre, ArmLayer.RED, lit, d.clawRed);
		}
	}

	/**
	 * The owner's third-person back view (rig, 2026-11-16: "whip line and hook not drawn", front view and everyone else's view fine): a cast goes
	 * straight ahead, onto the camera's axis, so the owner's own torso covers the claw and nearly all the line, and the stub left at the hand is
	 * a tenth of a block seen end on. The draw is the same for every viewer (the mixin has no camera or owner check), so this logs once per
	 * cast when the camera's own body hides the claw, with how much of the line it hides: a "FAIL" in that view that has this line in the log
	 * is the body in the way, and one without it is a real missing draw. Making the line show through the body would take a depth-free
	 * pass and is my call (vanilla's rod line hides the same way). No allocation: plain arithmetic, one log call per cast.
	 */
	private static void reportCover(Owner own, int ownerId, ArmFxFrame f, Vec3 tip, Vec3 hook) {
		Entity camera = Minecraft.getInstance().getCameraEntity();
		// only the camera's own entity can cover its own hook (someone else's body is not in the way of a rig, nor is it logged)
		if (camera == null || camera.getId() != ownerId || f.firstPersonCamera()) return;
		Vec3 cam = f.camPos();
		double bodyX = camera.getX(), bodyY = camera.getY(), bodyZ = camera.getZ();
		double radius = camera.getBbWidth() * 0.5, height = camera.getBbHeight();
		boolean hidden = OwnBodyCover.hides(cam.x, cam.y, cam.z, hook.x, hook.y, hook.z, bodyX, bodyY, bodyZ, radius, height);
		if (!hidden) {
			own.coverLogged = false;
		} else if (!own.coverLogged) {
			own.coverLogged = true;
			float share = OwnBodyCover.hiddenShare(cam.x, cam.y, cam.z, tip.x, tip.y, tip.z, hook.x, hook.y, hook.z, bodyX, bodyY, bodyZ, radius, height, 12);
			MorphClient.LOGGER.info("Whip hook is behind its owner's own body from this camera ({}% of the line hidden); the line is drawn, the body is in front of it",
					Math.round(100F * share));
		}
	}

	/** Scratch for the line's run outside the camera's clearance ({@link NearCamera#outsideSphere}); the render thread draws one line at a time. */
	private static final double[] OUTSIDE = new double[2];
	private static final Matrix4f CLAW_K = new Matrix4f();

	/** The reusable draw state for the {@code i}th line of a frame, made when first needed (the geometry callbacks run after this frame's collect). */
	private static LineDraw draw(int i) {
		while (DRAWS.size() <= i) DRAWS.add(new LineDraw());
		return DRAWS.get(i);
	}

	/** Chain links on the line's hook end: how far from the hook they reach, their pitch (px) and the most there are. */
	private static final float LINK_SPAN = 19F, LINK_PITCH = 1.9F;
	private static final int MAX_LINKS = 10;
	private static final Loft.RadiusFn LINE_BLACK_W = s -> FistGeometry.width(ArmMath.lerp(LINE_ROOT, LINE_TIP, s), 4),
			LINE_RED_W = s -> FistGeometry.width(0.42F * ArmMath.lerp(LINE_ROOT, LINE_TIP, s), 4);
	private static final Matrix4f LINK_M = new Matrix4f();
	private static final Vector3f LINK_A = new Vector3f(), LINK_B = new Vector3f(), LINK_P = new Vector3f();

	/**
	 * One whip line's draw, kept and reused frame after frame so that its shape and the four geometry callbacks (line and claw,
	 * black and red layers) are made once, not per frame. A frame still makes a handful of short-lived objects per hook (the
	 * tip and hook points, the queue entry, the frame record), a few hundred bytes a frame for at most 32 hooks, which is left alone (fix
	 * round, 2026-11-16: the earlier wording said "allocates nothing", which overstated it). The collector calls the callbacks
	 * back during the frame's rendering, after this frame's collect has set the fields.
	 */
	private static final class LineDraw {
		final WhipLineShape.Shape shape = new WhipLineShape.Shape();
		float lenPx, time, closed, spin, linkS;
		/** The run of the line that is drawn (0..1 from the whip to the hook) and the claw's size: whole in third person, trimmed off the lens in your own first person. */
		float s0, s1 = 1F, clawK = 1F;
		int seed, pieces, nLinks;
		final Loft.CurveFn redCurve = this::redAt;
		final TendrilFx.Geometry lineBlack = this::emitBlack, lineRed = this::emitRed;
		final TendrilFx.Geometry clawBlack = m -> claw(m.transform(clawK < 1F ? CLAW_K.scaling(clawK) : null), ArmLayer.BLACK, closed, spin, time, seed),
				clawRed = m -> claw(m.transform(clawK < 1F ? CLAW_K.scaling(clawK) : null), ArmLayer.RED, closed, spin, time, seed);

		/** The red strand: the line's path with a twist round it, as a braid. */
		private void redAt(float s, Vector3f out) {
			shape.at(s, out);
			float rho = 0.5F * ArmMath.lerp(LINE_ROOT, LINE_TIP, s);
			float psi = s * lenPx / 3F - 0.4F * time;
			float c = (float) Math.cos(psi), sn = (float) Math.sin(psi);
			out.add(rho * (c * shape.sx + sn * shape.ux), rho * (c * shape.sy + sn * shape.uy), rho * (c * shape.sz + sn * shape.uz));
		}

		private void emitBlack(SkinMesh m) {
			ST.set(time, TendrilFx.CALM, 1F, seed);
			Loft.tube(m, shape, s0, s1, pieces + 1, 4, LINE_BLACK_W, ArmLayer.BLACK, ArmLayer.BLACK, ST, 76, 1F, s0 * lenPx, s1 * lenPx, UP_Y, 1F, s0 * lenPx);
			links(m, false);
		}

		private void emitRed(SkinMesh m) {
			ST.set(time, TendrilFx.CALM, 1F, seed + 1);
			Loft.tube(m, redCurve, s0, s1, pieces + 1, 4, LINE_RED_W, ArmLayer.RED, ArmLayer.RED, ST, 77, 1.5F, s0 * lenPx, s1 * lenPx, UP_Y, 1F, s0 * lenPx);
			links(m, true);
		}

		/**
		 * The chain's end: braided links riding the line's last stretch before the hook, each a black loop turned a quarter
		 * from its neighbour (a chain's way) with a red stud at the joint between, following the line's own curve and turning
		 * a little with the clock; scaled in and out with {@code linkS}.
		 */
		private void links(SkinMesh m, boolean red) {
			if (nLinks <= 0 || !(lenPx > 1F)) return;
			float e = 0.5F * LINK_PITCH / lenPx;
			for (int i = 0; i < nLinks; i++) {
				float mid = 1F - (i + 0.5F) * LINK_PITCH / lenPx;
				if (mid - e < 0F) break;
				if (mid + e > s1 + 1e-4F || mid - e < s0 - 1e-4F) continue; // off the trimmed run (off the lens)
				shape.at(mid, LINK_P);
				shape.at(mid - e, LINK_A);
				shape.at(Math.min(1F, mid + e), LINK_B);
				LINK_B.sub(LINK_A);
				float tl = LINK_B.length();
				if (tl < 1e-5F) continue;
				LINK_B.div(tl);
				TURN.rotationTo(0F, 0F, 1F, LINK_B.x, LINK_B.y, LINK_B.z);
				float roll = (i & 1) * ArmMath.PI / 2F + 0.15F * (float) Math.sin(0.2F * time + i);
				LINK_M.identity().translate(LINK_P).rotate(TURN).rotateZ(roll).scale(linkS);
				if (red) {
					m.box(LINK_M, -0.3F, -0.3F, -1.45F, 0.6F, 0.6F, 2.9F, ST.u0(85, i & 3, 0F), ST.v0(85, i & 3, 0F), 1F, 1F, SkinMesh.WHITE);
				} else {
					m.box(LINK_M, -0.75F, -0.5F, -1.1F, 1.5F, 1.0F, 2.2F, ST.u0(84, i & 3, 0F), ST.v0(84, i & 3, 0F), 1F, 1F, SkinMesh.WHITE);
				}
			}
		}
	}

	/** How shut {@code o}'s claw is now (0 open, 1 shut): it moves a third of the way each tick; {@code shut} is a catch or an anchor. */
	private static float closed(Owner o, boolean shut, float time) {
		float clock = (float) (time % ArmDrawFactory.CLOCK_WRAP);
		if (o.claw < 0F) {
			// first drawn: it starts as it will be drawn
			o.claw = shut ? 1F : 0F;
			o.clawClock = clock;
		}
		float dt = Math.clamp(WhipGeometry.ageOf(clock, o.clawClock), 0F, 2F);
		float target = shut ? 1F : 0F;
		float step = dt / 3F;
		o.claw += Math.clamp(target - o.claw, -step, step);
		o.clawClock = clock;
		return ArmMath.sm(o.claw);
	}

	/**
	 * The bobber's claw, in px about the hook's centre: a black cap on top and a black base, a red band round the core
	 * between them, and three prongs reaching down and out (black, with red tips), opening on the water and closing in
	 * over a catch. {@code closed} 0 is open (about 50°), 1 shut (about 12°).
	 */
	private static void claw(SkinMesh m, ArmLayer layer, float closed, float spin, float time, int seed) {
		ST.set(time, TendrilFx.CALM, 1F, seed);
		if (layer == ArmLayer.BLACK) {
			m.box(null, -1.1F, 0.55F, -1.1F, 2.2F, 0.75F, 2.2F, ST.u0(80, 0, 0F), ST.v0(80, 0, 0F), 1F, 1F, SkinMesh.WHITE);
			m.box(null, -0.95F, -1.65F, -0.95F, 1.9F, 0.75F, 1.9F, ST.u0(80, 1, 0F), ST.v0(80, 1, 0F), 1F, 1F, SkinMesh.WHITE);
		} else {
			m.box(null, -0.8F, -0.9F, -0.8F, 1.6F, 1.45F, 1.6F, ST.u0(81, 0, 0F), ST.v0(81, 0, 0F), 1F, 1F, SkinMesh.WHITE);
		}
		float spread = ArmMath.lerp(0.9F, 0.2F, closed);
		for (int k = 0; k < 3; k++) {
			float a = spin + ArmMath.TAU * k / 3F;
			float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
			float flutter = 0.06F * (float) Math.sin(0.2F * time + 2F * k) * (1F - closed);
			float sp = spread + flutter;
			float ss = (float) Math.sin(sp), cs = (float) Math.cos(sp);
			// the prong leaves the base going down and out, bending in toward the axis for its red tip
			float baseY = -1.5F, baseR = 0.55F;
			float x0 = baseR * ca, z0 = baseR * sa;
			float len1 = 2.6F;
			TURN.rotationTo(0F, 0F, 1F, ss * ca, -cs, ss * sa);
			float x1 = x0 + ss * ca * len1, y1 = baseY - cs * len1, z1 = z0 + ss * sa * len1;
			float bend = sp - 0.55F - 0.3F * closed;
			float bs = (float) Math.sin(bend), bc = (float) Math.cos(bend);
			if (layer == ArmLayer.BLACK) {
				Matrix4f l = CLAW_M.identity().translate(x0, baseY, z0).rotate(TURN);
				m.frustum(l, len1, 0.9F, 0.9F, 0.45F, 0.45F, ST.u0(82, k, 0F), ST.v0(82, k, 0F), 2F, 1F, 1F, SkinMesh.WHITE);
			} else {
				TURN.rotationTo(0F, 0F, 1F, bs * ca, -bc, bs * sa);
				Matrix4f l = CLAW_M.identity().translate(x1, y1, z1).rotate(TURN);
				m.frustum(l, 1.3F, 0.45F, 0.45F, 0.05F, 0.05F, ST.u0(83, k, 0F), ST.v0(83, k, 0F), 2F, 1F, 1F, SkinMesh.WHITE);
			}
		}
	}
}
