package dev.morphreborn.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.morphreborn.client.ClientConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * The morph acquisition animation: morph-skin tendrils grow from the killer to the dying mob, cover it, and retract
 * carrying its model back into the player. A port of the original's EntityAcquisition (morph acquisition branch),
 * run as a client-side effect instead of a registered entity.
 */
public final class AcquisitionEffect {
	private final RandomSource rand = RandomSource.create();
	final LivingEntity origin;
	final LivingEntity acquired;
	/** Drawn in the red morph skin (the red tendrils style). */
	final boolean red;
	/** Frozen model of the acquired mob relative to its feet, captured on the first frame. */
	@Nullable List<ModelCapture.Box> acquiredCapture;
	final List<Tendril> tendrils = new ArrayList<>();
	int age;
	boolean removed;
	private boolean acquiredRemoved;

	public AcquisitionEffect(LivingEntity origin, LivingEntity acquired, boolean red) {
		this.origin = origin;
		this.acquired = acquired;
		this.red = red;
		tendrils.add(new Tendril(null).headTowards(targetPos(), false));
	}

	Vec3 pos() {
		return origin.position().add(0, origin.getBbHeight() / 2D, 0);
	}

	Vec3 targetPos() {
		return acquired.position().add(0, acquired.getBbHeight() / 2D, 0);
	}

	/** Lerped origin centre for rendering. */
	Vec3 renderPos(float partialTick) {
		return origin.getPosition(partialTick).add(0, origin.getBbHeight() / 2D, 0);
	}

	public void tick() {
		age++;
		freezeAcquired();
		if (age == 10 && !acquiredRemoved && acquired != Minecraft.getInstance().player && Minecraft.getInstance().level != null) {
			acquiredRemoved = true;
			Minecraft.getInstance().level.removeEntity(acquired.getId(), Entity.RemovalReason.DISCARDED);
		}
		if (!origin.isAlive() || origin.isRemoved() || origin.level() != acquired.level()) {
			if (origin.isRemoved()) removed = true;
			return;
		}
		if (age > ClientConfig.INSTANCE.acquisitionTendrilMaxChild * 10 + 100) {
			removed = true;
			return;
		}
		boolean allDone = !tendrils.isEmpty();
		boolean anyRetracting = false;
		boolean anyNonRetracting = false;
		for (Tendril t : tendrils) {
			if (!t.isDone()) {
				allDone = false;
				t.tick();
				if (t.retract) anyRetracting = true;
				else anyNonRetracting = true;
			} else {
				anyRetracting = true;
			}
		}
		if (tendrils.size() < 5 && age % 2 == 0) {
			tendrils.add(new Tendril(null).headTowards(targetPos(), false));
			allDone = false;
		}
		if (anyRetracting && anyNonRetracting) {
			for (Tendril t : tendrils) t.propagateRetractToChild();
		}
		if (allDone) removed = true;
	}

	/** Keep the dying mob in its last living pose (no death tilt, no red flash) while it is being taken. */
	private void freezeAcquired() {
		if (acquiredRemoved) return;
		acquired.deathTime = 0;
		acquired.hurtTime = 0;
		acquired.yBodyRotO = acquired.yBodyRot;
		acquired.yHeadRotO = acquired.yHeadRot;
		acquired.yRotO = acquired.getYRot();
		acquired.xRotO = acquired.getXRot();
	}

	/** True when the tendrils are drawn faded (your own, seen in first person) and need a translucent render type. */
	public boolean firstPersonFade() {
		return origin == Minecraft.getInstance().getCameraEntity() && Minecraft.getInstance().options.getCameraType() == CameraType.FIRST_PERSON
				&& ClientConfig.INSTANCE.acquisitionTendrilPartOpacity > 0;
	}

	public boolean isAcquiredRemoved() {
		return acquiredRemoved;
	}

	public void render(PoseStack.Pose cameraRelativeOrigin, VertexConsumer buffer, int light, float partialTick) {
		boolean firstPerson = origin == Minecraft.getInstance().getCameraEntity() && Minecraft.getInstance().options.getCameraType() == CameraType.FIRST_PERSON;
		int opacityParts = ClientConfig.INSTANCE.acquisitionTendrilPartOpacity;
		for (Tendril t : tendrils) {
			if (t.isDone()) continue;
			List<Tendril> chain = new ArrayList<>();
			t.collect(chain);
			for (int i = 0; i < chain.size(); i++) {
				float alpha = 1F;
				if (firstPerson && opacityParts > 0) alpha = Mth.clamp((chain.size() - i) / (float) opacityParts, 0F, 1F);
				chain.get(i).renderSegment(cameraRelativeOrigin, buffer, light, partialTick, alpha);
			}
			t.renderCapture(cameraRelativeOrigin, buffer, light, partialTick, firstPerson);
		}
	}

	final class Tendril {
		@Nullable Tendril parent;
		@Nullable Tendril child;
		Vec3 offset;
		float yaw;
		float pitch;
		/** Previous tick's aim, so segments turn smoothly between ticks instead of snapping 20 times a second. */
		float lastYaw = Float.NaN;
		float lastPitch = Float.NaN;
		float lastHeight = 1F;
		float height = 1F;
		final float maxGrowth = 7F + (float) rand.nextGaussian() * 2F;
		boolean retract;
		int retractTime;
		float prevRotateSpin;
		float rotateSpin;
		final float spinFactor = (float) rand.nextGaussian() * 15F;
		int depth;
		@Nullable List<ModelCapture.Box> capture;

		Tendril(@Nullable Tendril parent) {
			this.parent = parent;
			if (parent != null) {
				this.offset = parent.reachOffset().subtract(look(parent.pitch, parent.yaw).scale(0.025F));
				this.depth = parent.depth + 1;
			} else {
				this.offset = Vec3.ZERO;
			}
		}

		Tendril headTowards(Vec3 pos, boolean reverse) {
			float randGaus = 5F;
			if (parent != null) {
				yaw = parent.yaw;
				pitch = parent.pitch;
				Vec3 o = parent.reachCoord();
				double d0 = pos.x - o.x;
				double d1 = pos.y - o.y;
				double d2 = pos.z - o.z;
				double dist = Math.sqrt(d0 * d0 + d2 * d2);
				float newYaw = (float) (Mth.atan2(d2, d0) * Mth.RAD_TO_DEG) - 90F;
				float newPitch = (float) -(Mth.atan2(d1, dist) * Mth.RAD_TO_DEG);
				pitch = rotlerp(pitch, newPitch, 30F);
				yaw = rotlerp(yaw, newYaw, 30F);
			} else {
				yaw = (reverse ? origin.yBodyRot + 180F : origin.yBodyRot) % 360F;
				pitch = 0;
				yaw += (5F + 25F * rand.nextFloat()) * (rand.nextBoolean() ? 1F : -1F);
				pitch += (float) rand.nextGaussian() * randGaus;
			}
			yaw += (float) rand.nextGaussian() * randGaus;
			pitch += (float) rand.nextGaussian() * randGaus;
			return this;
		}

		void tick() {
			lastHeight = height;
			lastYaw = yaw;
			lastPitch = pitch;
			if (retract) retractTime++;
			if (child != null) {
				child.tick();
				return;
			}
			float distToEnt = origin.distanceTo(acquired);
			int maxChild = ClientConfig.INSTANCE.acquisitionTendrilMaxChild;
			if (!retract) {
				if (height < maxGrowth) {
					float growth = Math.max(0.0625F, distToEnt / maxChild + (float) rand.nextGaussian() * 0.125F);
					height += growth * 16F;
					if (reachCoord().distanceTo(targetPos()) < Math.max(0.3F, growth)) {
						child = new Tendril(this);
						Vec3 pos = targetPos();
						Vec3 o = reachCoord();
						double d0 = pos.x - o.x;
						double d1 = pos.y - o.y;
						double d2 = pos.z - o.z;
						double dist = Math.sqrt(d0 * d0 + d2 * d2);
						child.yaw = (float) (Mth.atan2(d2, d0) * Mth.RAD_TO_DEG) - 90F;
						child.pitch = (float) -(Mth.atan2(d1, dist) * Mth.RAD_TO_DEG);
						child.lastHeight = child.height = (float) Math.sqrt(d0 * d0 + d1 * d1 + d2 * d2) / 16F;
						child.capture = acquiredCapture;
						acquiredCapture = null;
						child.propagateRetractToParent();
					}
				} else {
					child = new Tendril(this).headTowards(targetPos(), false);
				}
			} else if (retractTime <= 3) {
				float growth = Math.max(0.0625F, distToEnt / maxChild + (float) rand.nextGaussian() * 0.125F);
				if (reachCoord().distanceTo(targetPos()) > Math.max(0.5F, growth)) {
					Vec3 pos = targetPos();
					Vec3 o = reachCoord();
					double d0 = pos.x - o.x;
					double d1 = pos.y - o.y;
					double d2 = pos.z - o.z;
					double dist = Math.sqrt(d0 * d0 + d2 * d2);
					yaw = (float) (Mth.atan2(d2, d0) * Mth.RAD_TO_DEG) - 90F;
					pitch = (float) -(Mth.atan2(d1, dist) * Mth.RAD_TO_DEG);
					height += growth * 16F;
				}
			} else if (height > 0 && retractTime > 6) {
				prevRotateSpin = rotateSpin;
				if (capture != null) rotateSpin += spinFactor;
				float growth = Math.max(0.0625F, distToEnt / (maxChild * 2F) + (float) rand.nextGaussian() * 0.125F);
				height -= growth * 16F;
				if (height <= 0) {
					height = 0;
					if (parent != null) {
						parent.child = null;
						if (capture != null) {
							parent.capture = capture;
							parent.prevRotateSpin = prevRotateSpin;
							parent.rotateSpin = rotateSpin;
						}
					}
				}
			}
		}

		float renderYaw(float partialTick) {
			return Float.isNaN(lastYaw) ? yaw : Mth.rotLerp(partialTick, lastYaw, yaw);
		}

		float renderPitch(float partialTick) {
			return Float.isNaN(lastPitch) ? pitch : Mth.rotLerp(partialTick, lastPitch, pitch);
		}

		boolean isDone() {
			return retract && height <= 0F;
		}

		void propagateRetractToParent() {
			retract = true;
			if (parent != null) parent.propagateRetractToParent();
		}

		void propagateRetractToChild() {
			retract = true;
			if (child != null) child.propagateRetractToChild();
		}

		Vec3 reachOffset() {
			float growth = height / 16F;
			return offset.add(look(pitch, yaw).scale(growth));
		}

		Vec3 reachCoord() {
			return pos().add(reachOffset());
		}

		float width(float partialTick) {
			float depthLeft = 0;
			Tendril c = child;
			while (c != null) {
				depthLeft += Math.min((c.lastHeight + (c.height - c.lastHeight) * partialTick) / c.maxGrowth, 1F);
				c = c.child;
			}
			return Math.min(3.5F, 1F + 0.2F * depthLeft);
		}

		void collect(List<Tendril> out) {
			out.add(this);
			if (child != null) child.collect(out);
		}

		void renderSegment(PoseStack.Pose origin, VertexConsumer buffer, int light, float partialTick, float alpha) {
			float w = width(partialTick);
			float len = lastHeight + (height - lastHeight) * partialTick;
			PoseStack ps = new PoseStack();
			ps.last().pose().set(origin.pose());
			ps.last().normal().set(origin.normal());
			ps.translate(offset.x, offset.y, offset.z);
			ps.rotate(new Quaternionf().rotationZYX(0F, -renderYaw(partialTick) * Mth.DEG_TO_RAD, renderPitch(partialTick) * Mth.DEG_TO_RAD));
			// The box is in model pixels; Cube.compile converts pixels to blocks itself, like a ModelPart.
			ModelCapture.Box box = new ModelCapture.Box(new org.joml.Matrix4f(), new org.joml.Matrix3f(), -w / 2F, -w / 2F, 0F, w, w, Math.max(0F, len), 0, 0, 0);
			ModelCapture.render(List.of(box), ps.last(), buffer, light, OverlayTexture.NO_OVERLAY, alpha, false);
		}

		void renderCapture(PoseStack.Pose origin, VertexConsumer buffer, int light, float partialTick, boolean firstPerson) {
			if (child != null) {
				child.renderCapture(origin, buffer, light, partialTick, firstPerson);
				return;
			}
			if (capture == null) return;
			float renderHeight = lastHeight + (height - lastHeight) * partialTick;
			Vec3 renderPoint = offset.add(look(renderPitch(partialTick), renderYaw(partialTick)).scale(renderHeight / 16F));
			float alpha = firstPerson ? Mth.clamp((depth + 1) / (float) Math.max(1, ClientConfig.INSTANCE.acquisitionTendrilPartOpacity), 0F, 1F) : 1F;
			if (alpha <= 0F) return;
			double distToEnt = origin().distanceTo(acquired);
			double distToPoint = renderPoint.length();
			float scale = distToEnt > 0 ? (float) (Math.min(distToEnt, distToPoint + 0.5D) / distToEnt) : 0F;
			float rot = prevRotateSpin + (rotateSpin - prevRotateSpin) * partialTick;
			PoseStack ps = new PoseStack();
			ps.last().pose().set(origin.pose());
			ps.last().normal().set(origin.normal());
			ps.translate(renderPoint.x, renderPoint.y, renderPoint.z);
			ps.scale(scale, scale, scale);
			ps.rotate(new Quaternionf().rotationZ(rot * Mth.DEG_TO_RAD));
			ps.rotate(new Quaternionf().rotationY(rot * Mth.DEG_TO_RAD));
			ps.translate(0, -acquired.getBbHeight() / 2D, 0);
			ModelCapture.render(capture, ps.last(), buffer, light, OverlayTexture.NO_OVERLAY, alpha, false);
		}

		private LivingEntity origin() {
			return origin;
		}
	}

	static Vec3 look(float pitch, float yaw) {
		float f = Mth.cos(-yaw * Mth.DEG_TO_RAD - (float) Math.PI);
		float f1 = Mth.sin(-yaw * Mth.DEG_TO_RAD - (float) Math.PI);
		float f2 = -Mth.cos(-pitch * Mth.DEG_TO_RAD);
		float f3 = Mth.sin(-pitch * Mth.DEG_TO_RAD);
		return new Vec3(f1 * f2, f3, f * f2);
	}

	/** EntityHelper.updateRotation: turn {@code from} towards {@code to} by at most {@code max} degrees. */
	static float rotlerp(float from, float to, float max) {
		float diff = Mth.wrapDegrees(to - from);
		return from + Mth.clamp(diff, -max, max);
	}
}
