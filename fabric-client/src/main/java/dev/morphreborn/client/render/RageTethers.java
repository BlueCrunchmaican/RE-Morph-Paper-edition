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
 * A player hovering in their frenzy (servers from 7.4.0) stands on four tendril legs from the hips to the ground around
 * them, each foot planted until the body has moved well past it, then stepping, as the server draws them for players
 * without the mod.
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
	/** A hovering player's legs: where each foot stands (world), and a stepping foot's start and its step's start. */
	private static final class Legs {
		final Vec3[] feet = new Vec3[LEGS], from = new Vec3[LEGS];
		final float[] stepAt = new float[LEGS];
	}

	/** Hovering players' entity ids, to their legs. */
	private static final Map<Integer, Legs> HOVER = new HashMap<>();
	/** As the server's (FrenzyHover): the feet's spread, a step's trigger, the legs, a step's length in ticks. */
	private static final double REACH = 1.0, REACH_PER_WIDTH = 0.85, STEP = 1.1;
	private static final int LEGS = 6, STEP_TICKS = 4, PIECES = 6;
	private static final double SPREAD_PER_HEIGHT = 0.5;
	private static long tick;

	private RageTethers() {}

	/** The creatures {@code origin} drains now: new ones get a tendril, ones no longer listed draw theirs back. */
	public static void set(int origin, int[] targets, boolean hovering) {
		if (hovering) HOVER.computeIfAbsent(origin, k -> new Legs());
		else HOVER.remove(origin);
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

	public static void tick() {
		tick++;
		for (Iterator<List<Tether>> it = BY_ORIGIN.values().iterator(); it.hasNext(); ) {
			List<Tether> list = it.next();
			list.removeIf(t -> t.end >= 0 && tick - t.end > RETRACT);
			if (list.isEmpty()) it.remove();
		}
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
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(MorphWorldRenderer.MORPH_SKIN_RED),
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
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(MorphWorldRenderer.MORPH_SKIN_RED),
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
		double ground = groundAt(level, body.x, body.z, body.y + 0.1);
		if (Double.isNaN(ground)) return;
		float bodyYaw = origin instanceof net.minecraft.world.entity.LivingEntity living ? living.yBodyRot : origin.getYRot();
		double yaw = Math.toRadians(bodyYaw);
		double reach = Math.max(Math.max(REACH, origin.getBbWidth() * REACH_PER_WIDTH), (body.y - ground) * SPREAD_PER_HEIGHT);
		Vec3 motion = origin.getDeltaMovement().multiply(1, 0, 1);
		for (int i = 0; i < LEGS; i++) {
			double angle = yaw + Math.toRadians(30 + 60 * i);
			Vec3 outward = new Vec3(-Math.sin(angle), 0, Math.cos(angle));
			Vec3 ideal = body.add(outward.scale(reach));
			Vec3 foot = legs.feet[i];
			if (foot == null || (legs.from[i] == null && foot.multiply(1, 0, 1).distanceTo(ideal.multiply(1, 0, 1)) > STEP + 0.35 * reach)) {
				Vec3 ahead = ideal.add(motion.scale(4));
				double y = groundAt(level, ahead.x, ahead.z, body.y);
				Vec3 to = new Vec3(ahead.x, Double.isNaN(y) ? ground : y, ahead.z);
				legs.from[i] = foot == null ? to : foot;
				legs.feet[i] = to;
				legs.stepAt[i] = time;
			}
			Vec3 at = legs.feet[i];
			if (legs.from[i] != null) {
				float t = Mth.clamp((time - legs.stepAt[i]) / STEP_TICKS, 0F, 1F);
				at = legs.from[i].lerp(legs.feet[i], t).add(0, 0.5 * Mth.sin(Mth.PI * t), 0);
				if (t >= 1F) legs.from[i] = null;
			}
			Vec3 hip = body.add(outward.scale(reach * 0.25)).add(0, origin.getBbHeight() * 0.35, 0);
			Vec3 line = at.subtract(hip);
			double length = line.length();
			Vec3 side = outward.cross(new Vec3(0, 1, 0));
			side = side.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : side.normalize();
			Vec3 prev = null;
			for (int k = 0; k <= PIECES; k++) {
				double s = k / (double) PIECES;
				double bow = Math.sin(Math.PI * s);
				Vec3 p = hip.add(line.scale(s)).add(0, bow * (0.4 + 0.15 * length), 0).add(outward.scale(bow * (0.2 + 0.12 * length)))
						.add(side.scale(bow * 0.15 * Math.sin(i * 1.3 + s * 5 + time * 0.3)));
				if (prev != null) segment(prev.subtract(body), p.subtract(body), (float) (2.4 + (1.2 - 2.4) * (k - 0.5) / PIECES), out);
				prev = p;
			}
		}
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
