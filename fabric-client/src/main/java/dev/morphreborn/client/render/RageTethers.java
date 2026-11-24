package dev.morphreborn.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A rage's tendrils (servers from 7.2.0, S_RAGE_DRAIN): red morph-skin tendrils from the raging player's chest to each
 * creature being drained, wavering, with swellings running along them into the player as the creature's health is
 * taken. They grow out when a creature is caught and draw back when it is let go; one that dies is absorbed with the
 * usual animation (S_ACQUIRE), which takes over from here.
 * <p>
 * A player hovering in their frenzy (servers from 7.4.0) stands on six tendril legs from the hips to the ground around
 * them, walking as a spider's do (gait), as the server draws them for players without the mod. From 8.0.0 the legs
 * stay when the frenzy runs out, lowering the player to the ground (still walking), then draw back out of the ground into
 * the body (my idea).
 */
public final class RageTethers {
	/** Ticks a tendril takes to reach its creature, and to draw back. */
	private static final int GROW = 6;
	private static final int RETRACT = 6;

	private static final class Tether {
		final int target;
		final long start;
		long end = -1;

		Tether(int target, long start) {
			this.target = target;
			this.start = start;
		}
	}

	/** Raging players' entity ids, to their tendrils. */
	private static final Map<Integer, List<Tether>> BY_ORIGIN = new HashMap<>();
	/** A hovering player's legs: where each foot stands (world), and a stepping foot's start, its step's start and arc. */
	private static final class Legs {
		final Vec3[] feet = new Vec3[LEGS], from = new Vec3[LEGS];
		final float[] stepAt = new float[LEGS];
		final double[] arc = new double[LEGS];
		/** The set of legs (0: even, 1: odd) that stepped last, so the other goes next. */
		int lastSet = 1;
		/** When the legs began drawing back into the body (-1: not drawing back). */
		float retractAt = -1;
		/** When the server last said they are out (it says so every second while they are). */
		long heard;
		/** Drawing back: the ground under each foot when it began (a foot held mid-step stays lifted in the doll). */
		final double[] under = new double[LEGS];
	}

	/** Legs the server hasn't mentioned for this long go: the player went out of range, or the packet that ended them was missed. */
	private static final int STALE_TICKS = 100;

	/** S_RAGE_DRAIN's legs (servers from 8.0.0; as the server's FrenzyHover.LEGS_*): none, hovering, lowering the player, drawing back. */
	public static final int LEGS_NONE = 0, LEGS_HOVER = 1, LEGS_LOWER = 2, LEGS_RETRACT = 3;
	/** As the server's: ticks the legs take to draw back into the body, and how high a foot lifts as it goes. */
	private static final int RETRACT_TICKS = 10;
	private static final double RETRACT_LIFT = 0.3;

	/** Hovering players' entity ids, to their legs. */
	private static final Map<Integer, Legs> HOVER = new HashMap<>();
	/** As the server's (FrenzyHover): the feet's spread, a step's trigger, the legs, a step's length in ticks, its lead. */
	private static final double REACH = 1.0, REACH_PER_WIDTH = 0.85, STEP = 0.6, STEP_PER_REACH = 0.3;
	private static final int LEGS = 6, STEP_TICKS = 6, PIECES = 6;
	private static final double LEAD_TICKS = 6, LEAD_SHARE = 0.8, FAR = 2.5;
	/** As the server's: a leg leaves the body this share of its width out from the middle, this share of its height up. */
	private static final double HIP_OUT = 0.2, HIP_UP = 0.45;
	private static final double SPREAD_PER_HEIGHT = 0.5;
	private static long tick;

	private RageTethers() {}

	/**
	 * The creatures {@code origin} drains now: new ones get a tendril, ones no longer listed draw theirs back. {@code
	 * legs} ({@link #LEGS_NONE} to {@link #LEGS_RETRACT}): hovering and lowering walk the legs; drawing back pulls them in,
	 * and they go when that is done (a "none" meanwhile lets it finish); "none" otherwise takes them away at once.
	 */
	public static void set(int origin, int[] targets, int legs) {
		Legs mine = HOVER.get(origin);
		switch (legs) {
			case LEGS_HOVER, LEGS_LOWER -> {
				if (mine == null || mine.retractAt >= 0) HOVER.put(origin, mine = new Legs()); // a new hover after one drew back
				mine.heard = tick;
			}
			case LEGS_RETRACT -> {
				if (mine != null && mine.retractAt < 0) retract(origin, mine);
			}
			default -> {
				if (mine != null && mine.retractAt < 0) HOVER.remove(origin);
			}
		}
		List<Tether> list = BY_ORIGIN.computeIfAbsent(origin, k -> new ArrayList<>());
		java.util.Set<Integer> now = new java.util.HashSet<>();
		for (int id : targets) now.add(id);
		for (Tether t : list) {
			if (t.end < 0 && !now.contains(t.target)) t.end = tick;
		}
		for (int id : targets) {
			boolean held = false;
			for (Tether t : list) held |= t.target == id && t.end < 0;
			if (held) continue;
			list.removeIf(t -> t.target == id); // caught again while drawing back: a new tendril
			list.add(new Tether(id, tick));
		}
		if (list.isEmpty()) BY_ORIGIN.remove(origin);
	}

	/** The legs begin drawing back: a foot in the middle of a step is held where it is; legs never drawn just go. */
	private static void retract(int origin, Legs legs) {
		for (Vec3 foot : legs.feet) {
			if (foot == null) {
				HOVER.remove(origin);
				return;
			}
		}
		for (int i = 0; i < LEGS; i++) {
			legs.under[i] = groundUnder(legs, i);
			legs.feet[i] = footAt(legs, i, tick);
			legs.from[i] = null;
		}
		legs.retractAt = tick;
	}

	public static void tick() {
		tick++;
		for (Iterator<List<Tether>> it = BY_ORIGIN.values().iterator(); it.hasNext(); ) {
			List<Tether> list = it.next();
			list.removeIf(t -> t.end >= 0 && tick - t.end > RETRACT);
			if (list.isEmpty()) it.remove();
		}
		HOVER.values().removeIf(legs -> legs.retractAt >= 0 ? tick - legs.retractAt > RETRACT_TICKS : tick - legs.heard > STALE_TICKS);
	}

	public static void clear() {
		BY_ORIGIN.clear();
		HOVER.clear();
	}

	public static void collectSubmits(LevelRenderContext ctx) {
		if (BY_ORIGIN.isEmpty() && HOVER.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) return;
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vec3 cam = ctx.levelState().cameraRenderState.pos;
		PoseStack poseStack = ctx.poseStack();
		SubmitNodeCollector collector = ctx.submitNodeCollector();
		EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
		float time = tick + partialTick;
		for (Map.Entry<Integer, List<Tether>> e : BY_ORIGIN.entrySet()) {
			Entity origin = level.getEntity(e.getKey());
			if (origin == null) continue;
			// your own, in first person: from in front of you and below the view, and thinner (from the chest they started
			// under the camera and filled the screen)
			boolean firstPerson = origin == mc.getCameraEntity() && mc.options.getCameraType() == CameraType.FIRST_PERSON;
			Vec3 from = firstPerson ? origin.getEyePosition(partialTick).add(origin.getViewVector(partialTick).multiply(1, 0, 1).normalize().scale(0.5)).add(0, -0.7, 0)
					: origin.getPosition(partialTick).add(0, origin.getBbHeight() * 0.6, 0);
			float start = firstPerson ? 1.3F : 2.6F;
			int light = dispatcher.getPackedLightCoords(origin, partialTick);
			List<ModelCapture.Box> boxes = new ArrayList<>();
			for (Tether t : e.getValue()) {
				Entity target = level.getEntity(t.target);
				if (target == null) continue;
				Vec3 to = target.getPosition(partialTick).add(0, target.getBbHeight() / 2, 0);
				float grow = Mth.clamp((tick - t.start + partialTick) / GROW, 0F, 1F);
				float back = t.end < 0 ? 1F : Mth.clamp(1F - (tick - t.end + partialTick) / RETRACT, 0F, 1F);
				float reach = grow * back;
				if (reach > 0.01F) tendril(to.subtract(from), reach, t.target * 1.7F, time, start, boxes);
			}
			if (boxes.isEmpty()) continue;
			Vec3 at = from.subtract(cam);
			poseStack.pushPose();
			poseStack.translate(at.x, at.y, at.z);
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(dev.morphreborn.client.arm.render.ArmTint.redSkinOf(origin.getId())),
					(pose, buffer) -> ModelCapture.render(boxes, pose, buffer, light, OverlayTexture.NO_OVERLAY, 1F, false));
			poseStack.popPose();
		}
		for (Map.Entry<Integer, Legs> e : HOVER.entrySet()) {
			Entity origin = level.getEntity(e.getKey());
			if (origin == null) continue;
			List<ModelCapture.Box> boxes = new ArrayList<>();
			Vec3 body = origin.getPosition(partialTick);
			legs(level, origin, body, e.getValue(), time, boxes);
			if (boxes.isEmpty()) continue;
			int light = dispatcher.getPackedLightCoords(origin, partialTick);
			Vec3 at = body.subtract(cam);
			poseStack.pushPose();
			poseStack.translate(at.x, at.y, at.z);
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(dev.morphreborn.client.arm.render.ArmTint.redSkinOf(origin.getId())),
					(pose, buffer) -> ModelCapture.render(boxes, pose, buffer, light, OverlayTexture.NO_OVERLAY, 1F, false));
			poseStack.popPose();
		}
	}

	/**
	 * A hovering player's six legs as boxes from {@code body}, as the server draws them for players without the mod
	 * (FrenzyHover.legs): set evenly round the body, each arched up and out from the hip to its foot, writhing; a foot
	 * stays until the body has moved well past it, then lifts and swings a little ahead over a few ticks.
	 */
	private static void legs(ClientLevel level, Entity origin, Vec3 body, Legs legs, float time, List<ModelCapture.Box> out) {
		Vec3[] outwards = outwards(origin);
		if (legs.retractAt >= 0) {
			float in = drawnIn(legs, time);
			for (int i = 0; i < LEGS; i++) {
				Vec3 hip = hip(origin, body, outwards[i]);
				leg(body, hip, drawingBack(legs, i, hip, time), outwards[i], i, time, 0.5F + 0.5F * (1F - in), 1F - in, out);
			}
			return;
		}
		double ground = groundAt(level, body.x, body.z, body.y + 0.1);
		if (Double.isNaN(ground)) return;
		double reach = Math.max(Math.max(REACH, origin.getBbWidth() * REACH_PER_WIDTH), (body.y - ground) * SPREAD_PER_HEIGHT);
		// how far it went in the last tick (another player's own motion isn't known here)
		Vec3 motion = new Vec3(origin.getX() - origin.xo, 0, origin.getZ() - origin.zo);
		Vec3[] ideals = new Vec3[LEGS];
		for (int i = 0; i < LEGS; i++) ideals[i] = body.add(outwards[i].scale(reach));
		gait(level, legs, ideals, motion, reach, ground, body.y, time);
		for (int i = 0; i < LEGS; i++) leg(body, hip(origin, body, outwards[i]), footAt(legs, i, time), outwards[i], i, time, 1F, 1F, out);
	}

	/** Which way each leg goes out: round the body from the front, every 60 degrees, turned with it. */
	private static Vec3[] outwards(Entity origin) {
		float bodyYaw = origin instanceof net.minecraft.world.entity.LivingEntity living ? living.yBodyRot : origin.getYRot();
		double yaw = Math.toRadians(bodyYaw);
		Vec3[] outwards = new Vec3[LEGS];
		for (int i = 0; i < LEGS; i++) {
			double angle = yaw + Math.toRadians(30 + 60 * i);
			outwards[i] = new Vec3(-Math.sin(angle), 0, Math.cos(angle));
		}
		return outwards;
	}

	/** Where a leg leaves the body: inside it at the waist, as the server's. */
	private static Vec3 hip(Entity origin, Vec3 body, Vec3 outward) {
		return body.add(outward.scale(origin.getBbWidth() * HIP_OUT)).add(0, origin.getBbHeight() * HIP_UP, 0);
	}

	/** How far the legs have drawn back into the body, 0 to 1 (eased). */
	private static float drawnIn(Legs legs, float time) {
		float t = Mth.clamp((time - legs.retractAt) / RETRACT_TICKS, 0F, 1F);
		return t * t * (3 - 2 * t);
	}

	/**
	 * Leg {@code i}'s foot while drawing back (my call: from the ground back into you), as the server's
	 * (FrenzyHover.drawBack): from where it stood up into the hip, lifting a little as it goes.
	 */
	private static Vec3 drawingBack(Legs legs, int i, Vec3 hip, float time) {
		float t = Mth.clamp((time - legs.retractAt) / RETRACT_TICKS, 0F, 1F);
		return legs.feet[i].lerp(hip, drawnIn(legs, time)).add(0, RETRACT_LIFT * Mth.sin(Mth.PI * t), 0);
	}

	/** Where leg {@code i}'s foot is now (world): planted, or along its step, eased and lifted in an arc. */
	private static Vec3 footAt(Legs legs, int i, float time) {
		if (legs.from[i] == null) return legs.feet[i];
		float t = Mth.clamp((time - legs.stepAt[i]) / STEP_TICKS, 0F, 1F);
		double along = t * t * (3 - 2 * t);
		return legs.from[i].lerp(legs.feet[i], along).add(0, legs.arc[i] * Mth.sin(Mth.PI * t), 0);
	}

	/**
	 * One leg from {@code hip} to {@code foot} as boxes from {@code body}: arched up and {@code outward}, writhing sideways,
	 * thinning to the foot ({@code thick} scales it), the arch and writhing scaled by {@code bend} (less while drawing
	 * back, so a short leg doesn't loop over the hip), as the server's (FrenzyHover.curve).
	 */
	private static void leg(Vec3 body, Vec3 hip, Vec3 foot, Vec3 outward, int i, float time, float thick, float bend, List<ModelCapture.Box> out) {
		Vec3 line = foot.subtract(hip);
		double length = line.length();
		Vec3 side = outward.cross(new Vec3(0, 1, 0));
		side = side.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : side.normalize();
		Vec3 prev = null;
		for (int k = 0; k <= PIECES; k++) {
			double s = k / (double) PIECES;
			double bow = Math.sin(Math.PI * s) * bend;
			Vec3 p = hip.add(line.scale(s)).add(0, bow * (0.4 + 0.15 * length), 0).add(outward.scale(bow * (0.2 + 0.12 * length)))
					.add(side.scale(bow * 0.15 * Math.sin(i * 1.3 + s * 5 + time * 0.3)));
			if (prev != null) segment(prev.subtract(body), p.subtract(body), (float) (2.4 + (1.2 - 2.4) * (k - 0.5) / PIECES) * thick, out);
			prev = p;
		}
	}

	/** For the paper doll: a hovering player's legs as boxes from their feet, and how low and wide they reach. */
	public record DollLegs(List<ModelCapture.Box> boxes, float low, float wide) {}

	/**
	 * The paper doll's view of a hovering player's legs (my idea). At their real length (up to eight blocks down)
	 * the doll had to shrink the player to a speck, and drawn in toward the hips they hung like strings: here they stand
	 * as a short spider's stance of their own, feet round the body a little below it, its size following the hover's
	 * height (the doll grows and shrinks as the player rises and sinks; my call: not with every step, which jolted it).
	 * Each foot moves about its spot as the real one does about its own, so the doll walks too. Nothing steps here: the
	 * world's drawing walks them. Null when the player isn't hovering.
	 */
	public static @org.jspecify.annotations.Nullable DollLegs dollLegs(Entity origin, float partialTick) {
		Legs legs = HOVER.get(origin.getId());
		if (legs == null || !(origin.level() instanceof ClientLevel level)) return null;
		for (Vec3 foot : legs.feet) if (foot == null) return null;
		Vec3 body = origin.getPosition(partialTick);
		double ground = groundAt(level, body.x, body.z, body.y + 0.1);
		double height = Double.isNaN(ground) ? 1 : Math.max(0, body.y - ground);
		double reach = Math.max(Math.max(REACH, origin.getBbWidth() * REACH_PER_WIDTH), height * SPREAD_PER_HEIGHT);
		double depth = Mth.clamp(height * DOLL_DEPTH_PER_HEIGHT, DOLL_DEPTH_MIN, DOLL_DEPTH_MAX);
		double spread = Mth.clamp(reach * DOLL_SPREAD_SHARE, DOLL_SPREAD_MIN, DOLL_SPREAD_MAX);
		double k = spread / reach;
		float time = tick + partialTick;
		// drawing back: the doll's legs come in as the real ones do, and the doll's frame shrinks with them
		float in = legs.retractAt >= 0 ? drawnIn(legs, time) : 0F;
		float lifted = legs.retractAt >= 0 ? (float) RETRACT_LIFT * Mth.sin(Mth.PI * Mth.clamp((time - legs.retractAt) / RETRACT_TICKS, 0F, 1F)) : 0F;
		Vec3[] outwards = outwards(origin);
		List<ModelCapture.Box> boxes = new ArrayList<>();
		for (int i = 0; i < LEGS; i++) {
			Vec3 outward = outwards[i];
			Vec3 real = footAt(legs, i, time);
			// how far the real foot is from its spot (and how high it is lifted), at the doll's size
			Vec3 off = real.subtract(body.add(outward.scale(reach))).multiply(k, 0, k);
			double lift = Math.max(0, real.y - (Double.isNaN(ground) ? real.y : groundUnder(legs, i)));
			Vec3 foot = body.add(outward.scale(spread)).add(off).add(0, -depth + lift * k * 2, 0);
			Vec3 hip = hip(origin, body, outward);
			if (in > 0F || lifted > 0F) foot = foot.lerp(hip, in).add(0, lifted, 0);
			leg(body, hip, foot, outward, i, time, 0.5F + 0.5F * (1F - in), 1F - in, boxes);
		}
		return new DollLegs(boxes, (float) (-depth * (1 - in)), (float) ((spread + 0.35) * (1 - in)));
	}

	/**
	 * The ground under leg {@code i}'s step: where it stands, or the lower end of its step while it steps (drawing back:
	 * as it was when that began).
	 */
	private static double groundUnder(Legs legs, int i) {
		if (legs.retractAt >= 0) return legs.under[i];
		return legs.from[i] == null ? legs.feet[i].y : Math.min(legs.from[i].y, legs.feet[i].y);
	}

	/** The doll's stance: blocks below the feet per block of hover height, within these; spread as a share of the real. */
	private static final double DOLL_DEPTH_PER_HEIGHT = 0.3, DOLL_DEPTH_MIN = 0.5, DOLL_DEPTH_MAX = 1.6;
	private static final double DOLL_SPREAD_SHARE = 0.55, DOLL_SPREAD_MIN = 0.8, DOLL_SPREAD_MAX = 1.8;

	/**
	 * The walking gait, as the server's (FrenzyHover.gait): two alternating sets of three legs, every other one round the
	 * body; a foot stays planted until the body has left it behind, then its set steps, a tick apart, a little ahead the
	 * way the body is going, and the other set waits until they have landed; a foot left far behind steps at once.
	 */
	private static void gait(ClientLevel level, Legs legs, Vec3[] ideals, Vec3 motion, double reach, double ground, double fromY, float time) {
		double due = STEP + STEP_PER_REACH * reach;
		Vec3 lead = motion.scale(LEAD_TICKS);
		if (lead.length() > due * LEAD_SHARE) lead = lead.normalize().scale(due * LEAD_SHARE);
		double[] off = new double[LEGS];
		boolean anyLifted = false;
		boolean[] setDue = new boolean[2];
		for (int i = 0; i < LEGS; i++) {
			if (legs.from[i] != null && time - legs.stepAt[i] >= STEP_TICKS) legs.from[i] = null; // landed
			if (legs.feet[i] == null) {
				double y = groundAt(level, ideals[i].x, ideals[i].z, fromY);
				legs.feet[i] = new Vec3(ideals[i].x, Double.isNaN(y) ? ground : y, ideals[i].z);
			}
			off[i] = legs.feet[i].multiply(1, 0, 1).distanceTo(ideals[i].multiply(1, 0, 1));
			if (legs.from[i] != null) anyLifted = true;
			else if (off[i] > due * FAR) step(level, legs, i, ideals[i].add(lead), ground, fromY, time); // left far behind
			else if (off[i] > due) setDue[i % 2] = true;
		}
		if (anyLifted || !(setDue[0] || setDue[1])) return;
		int set = setDue[0] && setDue[1] ? 1 - legs.lastSet : setDue[0] ? 0 : 1;
		int ripple = 0;
		for (int i = set; i < LEGS; i += 2) {
			if (off[i] < 0.15) continue; // already where it should stand
			step(level, legs, i, ideals[i].add(lead), ground, fromY, time + ripple++);
		}
		legs.lastSet = set;
	}

	/** Leg {@code i} steps to the ground under {@code ahead}, starting at {@code at}. */
	private static void step(ClientLevel level, Legs legs, int i, Vec3 ahead, double ground, double fromY, float at) {
		double y = groundAt(level, ahead.x, ahead.z, fromY);
		Vec3 to = new Vec3(ahead.x, Double.isNaN(y) ? ground : y, ahead.z);
		legs.from[i] = legs.feet[i];
		legs.feet[i] = to;
		legs.stepAt[i] = at;
		legs.arc[i] = Math.min(0.6, 0.15 + 0.2 * legs.from[i].distanceTo(to));
	}

	/** One box from {@code a} to {@code b} (blocks), {@code width} pixels thick. */
	private static void segment(Vec3 a, Vec3 b, float width, List<ModelCapture.Box> out) {
		Vec3 d = b.subtract(a);
		float len = (float) d.length();
		if (len < 1e-4F) return;
		Quaternionf turn = new Quaternionf().rotationTo(0F, 0F, 1F, (float) (d.x / len), (float) (d.y / len), (float) (d.z / len));
		Matrix4f pose = new Matrix4f().translation((float) a.x, (float) a.y, (float) a.z).rotate(turn);
		out.add(new ModelCapture.Box(pose, new Matrix3f().set(turn), -width / 2F, -width / 2F, 0F, width, width, len * 16F + 0.3F, 0, 0, 0));
	}

	/** The top of whatever is under (x, z), looking down from {@code fromY}; NaN with nothing within 32 blocks. */
	private static double groundAt(ClientLevel level, double x, double z, double fromY) {
		// vanilla's block walk never ends on a ray whose start isn't a number (FirstPersonMorph.measureDragon)
		if (!Double.isFinite(x) || !Double.isFinite(z) || !Double.isFinite(fromY)) return Double.NaN;
		Vec3 from = new Vec3(x, fromY + 0.5, z);
		net.minecraft.world.phys.BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(from, from.add(0, -32, 0),
				net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.ANY,
				net.minecraft.world.phys.shapes.CollisionContext.empty()));
		return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? Double.NaN : hit.getLocation().y;
	}

	/**
	 * One tendril along {@code line} (blocks, from the player's chest), drawn out to {@code reach} of its length: segments
	 * on a sideways wave that is still at both ends, thinning towards the creature, with swellings running back along it.
	 */
	private static void tendril(Vec3 line, float reach, float phase, float time, float start, List<ModelCapture.Box> out) {
		float length = (float) line.length();
		if (length < 0.2F) return;
		Vector3f dir = new Vector3f((float) line.x, (float) line.y, (float) line.z).div(length);
		Vector3f side = new Vector3f(dir).cross(0F, 1F, 0F);
		if (side.lengthSquared() < 1e-4F) side.set(1F, 0F, 0F);
		side.normalize();
		Vector3f up = new Vector3f(side).cross(dir).normalize();
		int n = Mth.clamp(Mth.ceil(length * 2.5F), 4, 40);
		float amp = Math.min(0.35F, 0.06F * length + 0.05F);
		// where the swelling is along the tendril: 1 at the creature, running to 0 at the player
		float pulse = 1F - Mth.frac(time * 0.07F + phase);
		Vector3f prev = null;
		for (int i = 0; i <= n; i++) {
			float s = Math.min(i / (float) n, reach);
			float wave = Mth.sin(s * Mth.PI) * amp;
			Vector3f p = new Vector3f(dir).mul(length * s)
					.add(new Vector3f(side).mul(wave * Mth.sin(phase + s * 7F + time * 0.35F)))
					.add(new Vector3f(up).mul(wave * 0.6F * Mth.cos(phase * 0.8F + s * 5F + time * 0.28F)));
			if (prev != null) {
				Vector3f d = new Vector3f(p).sub(prev);
				float len = d.length();
				if (len > 1e-4F) {
					float mid = s - 0.5F / n;
					float swell = 1F + 0.8F * (float) Math.exp(-(mid - pulse) * (mid - pulse) / 0.006F);
					float w = Mth.lerp(mid, start, 1.2F) * swell; // pixels
					Quaternionf turn = new Quaternionf().rotationTo(0F, 0F, 1F, d.x / len, d.y / len, d.z / len);
					Matrix4f pose = new Matrix4f().translation(prev).rotate(turn);
					out.add(new ModelCapture.Box(pose, new Matrix3f().set(turn), -w / 2F, -w / 2F, 0F, w, w, len * 16F + 0.3F, 0, 0, 0));
				}
			}
			prev = p;
			if (s >= reach) break;
		}
	}
}
