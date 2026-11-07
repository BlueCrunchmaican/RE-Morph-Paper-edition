package dev.morphreborn.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.morphreborn.client.mixin.LivingEntityRendererInvoker;
import dev.morphreborn.client.mixin.ModelPartAccessor;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Captures an entity model as a flat list of posed boxes and interpolates two such lists. This is the 26.3 version of
 * the original's {@code ModelRendererCapture} + iChunUtil's {@code ModelHelper.createInterimPart} /
 * {@code RenderHelper.createInterimStackEntry}: instead of intercepting {@code ModelRenderer.render} calls (26.3
 * renders models deferred), the posed {@link ModelPart} tree is walked directly after {@code setupAnim}.
 */
public final class ModelCapture {
	private static final Set<Direction> ALL_FACES = EnumSet.allOf(Direction.class);
	/** Morph skin texture is sampled as if every part had a 64x64 sheet, like most vanilla models. */
	private static final float TEX_SIZE = 64F;
	/**
	 * Pushes the skin overlay outside the real model so it can't z-fight with it. The original used +0.002, which
	 * worked in its single render pass; 26.3 draws the overlay in a separate pass, so it needs a visible margin.
	 */
	private static final float OVERLAY_GROW = 0.1F;

	/** One box in model pixels with the pose matrix that was current when it would have been drawn. */
	public record Box(Matrix4f pose, Matrix3f normal, float x, float y, float z, float w, float h, float d, float gx, float gy, float gz) {}

	private ModelCapture() {}

	/**
	 * Replays {@code LivingEntityRenderer.submit}'s transforms and {@code setupAnim}, then flattens the model.
	 * Returns an empty list for renderers that aren't living-entity renderers.
	 */
	public static List<Box> capture(EntityRenderer<?, ?> renderer, LivingEntityRenderState state) {
		return capture(renderer, state, false);
	}

	/** As {@link #capture(EntityRenderer, LivingEntityRenderState)}; {@code noFace} leaves out the head and face. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static List<Box> capture(EntityRenderer<?, ?> renderer, LivingEntityRenderState state, boolean noFace) {
		List<Box> out = new ArrayList<>();
		if (!(renderer instanceof LivingEntityRenderer living)) return out;
		selectModel(living, state);
		PoseStack poseStack = new PoseStack();
		float scale = state.scale;
		poseStack.scale(scale, scale, scale);
		((LivingEntityRendererInvoker) living).morph$setupRotations(state, poseStack, state.bodyRot, scale);
		poseStack.scale(-1.0F, -1.0F, 1.0F);
		((LivingEntityRendererInvoker) living).morph$scale(state, poseStack);
		poseStack.translate(0.0F, -1.501F, 0.0F);
		EntityModel model = living.getModel();
		model.setupAnim(state);
		walk(model.root(), poseStack, out, noFace);
		return out;
	}

	private static final CameraRenderState NO_CAMERA = new CameraRenderState();

	/**
	 * Many 26.3 renderers own several models and only pick one inside {@code submit}: adult or baby (every
	 * AgeableMobRenderer), cold/warm cows, pigs and chickens, salmon and pufferfish sizes, small sulfur cubes.
	 * {@code getModel()} is whichever was drawn last, so a baby-to-adult morph captured the same model at both ends and
	 * only changed size when the real body appeared. A submit into a throwaway collector makes the renderer pick the
	 * model for this state, exactly as it would to draw it; nothing submitted there is ever drawn.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	static void selectModel(LivingEntityRenderer living, LivingEntityRenderState state) {
		try {
			living.submit(state, new PoseStack(), new SubmitNodeStorage(), NO_CAMERA);
		} catch (RuntimeException e) {
			// keep the last model; the transition still plays, as before
		}
	}

	private static void walk(ModelPart part, PoseStack poseStack, List<Box> out, boolean noFace) {
		if (!part.visible) return;
		ModelPartAccessor access = (ModelPartAccessor) (Object) part;
		List<ModelPart.Cube> cubes = access.morph$cubes();
		java.util.Map<String, ModelPart> children = access.morph$children();
		if (cubes.isEmpty() && children.isEmpty()) return;
		poseStack.pushPose();
		part.translateAndRotate(poseStack);
		if (!part.skipDraw) {
			PoseStack.Pose pose = poseStack.last();
			for (ModelPart.Cube cube : cubes) {
				Vector3f lo = new Vector3f(Float.MAX_VALUE);
				Vector3f hi = new Vector3f(-Float.MAX_VALUE);
				for (ModelPart.Polygon polygon : cube.polygons) {
					for (ModelPart.Vertex v : polygon.vertices()) {
						lo.min(new Vector3f(v.x(), v.y(), v.z()));
						hi.max(new Vector3f(v.x(), v.y(), v.z()));
					}
				}
				float w = cube.maxX - cube.minX;
				float h = cube.maxY - cube.minY;
				float d = cube.maxZ - cube.minZ;
				float gx = Math.max(0F, ((hi.x - lo.x) - w) / 2F);
				float gy = Math.max(0F, ((hi.y - lo.y) - h) / 2F);
				float gz = Math.max(0F, ((hi.z - lo.z) - d) / 2F);
				out.add(new Box(new Matrix4f(pose.pose()), new Matrix3f(pose.normal()), cube.minX, cube.minY, cube.minZ, w, h, d, gx, gy, gz));
			}
		}
		for (java.util.Map.Entry<String, ModelPart> child : children.entrySet()) {
			if (!noFace || !FirstPersonMorph.faceName(child.getKey())) walk(child.getValue(), poseStack, out, noFace);
		}
		poseStack.popPose();
	}

	/**
	 * Box-by-box interpolation between two captures. The shorter list is padded with empty boxes posed at the other
	 * model's middle, so surplus parts grow out of / shrink into the body's centre exactly like the original.
	 */
	public static List<Box> interpolate(List<Box> from, List<Box> to, float fromMidY, float toMidY, float t) {
		int n = Math.max(from.size(), to.size());
		List<Box> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			Box a = i < from.size() ? from.get(i) : empty(fromMidY);
			Box b = i < to.size() ? to.get(i) : empty(toMidY);
			Matrix4f pose = lerp(a.pose, b.pose, t);
			Matrix3f normal = lerp(a.normal, b.normal, t);
			out.add(new Box(pose, normal, lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.z, b.z, t), lerp(a.w, b.w, t), lerp(a.h, b.h, t), lerp(a.d, b.d, t),
					lerp(a.gx, b.gx, t), lerp(a.gy, b.gy, t), lerp(a.gz, b.gz, t)));
		}
		return out;
	}

	private static Box empty(float midY) {
		Matrix4f pose = new Matrix4f().translate(0, midY, 0);
		return new Box(pose, new Matrix3f(), 0, 0, 0, 0, 0, 0, 0, 0, 0);
	}

	/** Draw boxes with the morph skin; {@code entityPose} is the entity origin in camera space. */
	public static void render(List<Box> boxes, PoseStack.Pose entityPose, VertexConsumer buffer, int light, int overlay, float alpha, boolean overlayGrow) {
		int a = Math.round(Math.clamp(alpha, 0F, 1F) * 255F);
		if (a <= 0) return;
		int color = (a << 24) | 0xFFFFFF;
		PoseStack scratch = new PoseStack();
		float grow = overlayGrow ? OVERLAY_GROW : 0F;
		for (int i = 0; i < boxes.size(); i++) {
			Box b = boxes.get(i);
			if (b.w + b.h + b.d <= 0.001F) continue;
			PoseStack.Pose pose = scratch.last();
			pose.pose().set(entityPose.pose()).mul(b.pose);
			pose.normal().set(entityPose.normal()).mul(b.normal);
			int u = (i * 13) % 48;
			int v = (i * 7) % 48;
			ModelPart.Cube cube = new ModelPart.Cube(u, v, b.x, b.y, b.z, b.w, b.h, b.d, b.gx + grow, b.gy + grow, b.gz + grow, false, TEX_SIZE, TEX_SIZE, ALL_FACES);
			cube.compile(pose, buffer, light, overlay, color);
		}
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	private static Matrix4f lerp(Matrix4f a, Matrix4f b, float t) {
		Matrix4f out = new Matrix4f();
		for (int c = 0; c < 4; c++) {
			for (int r = 0; r < 4; r++) out.set(c, r, lerp(a.get(c, r), b.get(c, r), t));
		}
		return out;
	}

	private static Matrix3f lerp(Matrix3f a, Matrix3f b, float t) {
		Matrix3f out = new Matrix3f();
		for (int c = 0; c < 3; c++) {
			for (int r = 0; r < 3; r++) out.set(c, r, lerp(a.get(c, r), b.get(c, r), t));
		}
		return out;
	}
}
