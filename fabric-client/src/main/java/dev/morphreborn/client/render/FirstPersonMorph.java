package dev.morphreborn.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.morphreborn.client.mixin.LivingEntityRendererInvoker;
import dev.morphreborn.client.mixin.ModelPartAccessor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EnderDragonRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * A morph drawn as the player's own body in first person (see {@code FirstPersonCompat}): its head is hidden so the
 * camera isn't inside it, and the body is placed so the camera sits where the head would be.
 *
 * <p>Models name their parts, and every vanilla mob calls its head "head" (a horse's whole head group is "head_parts").
 * Parts that sit on the face but hang off the root instead of the head (a chicken's beak and wattle, a slime's eyes and
 * mouth) are listed too. The ender dragon has its own renderer and state, not a living one; it's handled the same way,
 * measured as it is drawn ({@link #dragonOffset}).
 */
public final class FirstPersonMorph {
	private static final Set<String> FACE_PARTS = Set.of("hat", "beak", "red_thing", "nose", "jaw", "mouth", "upper_mouth", "lower_mouth", "snout",
			"left_ear", "right_ear", "left_horn", "right_horn", "left_eye", "right_eye", "eyes", "left_antenna", "right_antenna", "tongue");
	/** A head closer than this to the middle of the body (blocks, sideways) is above it, like a player's or a zombie's. */
	private static final float UPRIGHT = 0.15F;
	/** How far the front of the body sits behind the camera (blocks). */
	private static final float MARGIN = 0.06F;
	/** Whether each form's model has a head to put the camera at, by variant id. */
	private static final Map<String, Boolean> HAS_HEAD = new java.util.HashMap<>();
	/** Parts hidden for the draw in progress, restored right after it. */
	private static final List<ModelPart> HIDDEN = new ArrayList<>();
	/** Further than this from the model's origin (pixels) a part has been moved out of sight, not posed. */
	private static final float MOVED_AWAY = 1000F;
	/** The dragon's placement, measured each time its body is drawn in first person (used for the next frame). */
	public static volatile @Nullable Vec3 dragonOffset;

	private FirstPersonMorph() {}

	/** Marks a render state as the local player's first-person morph body. */
	public interface Mark {
		boolean morph$firstPersonBody();

		void morph$setFirstPersonBody(boolean body);
	}

	static boolean faceName(String name) {
		return name.contains("head") || FACE_PARTS.contains(name);
	}

	/**
	 * World-space shift that puts the camera just in front of the morph's body, where its head starts: the body is moved
	 * back along the way the head points until its front edge (head, face and legs left out) is just behind the camera.
	 * Looking ahead and down to aim stays clear, and looking further down shows the front legs and the body. Measured
	 * with the head facing straight ahead, so it doesn't swing as the player looks around. Null when the head is above
	 * the body (keep the first-person mod's own shift) or the model has no head part (slimes, ghasts, squid, which
	 * {@link #hasHead} keeps out of the body view).
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static @Nullable Vec3 headOffset(EntityRenderer<?, ?> renderer, LivingEntityRenderState state) {
		if (!(renderer instanceof LivingEntityRenderer living)) return null;
		float xRot = state.xRot;
		float yRot = state.yRot;
		state.xRot = 0F;
		state.yRot = 0F;
		try {
			ModelCapture.selectModel(living, state);
			PoseStack poseStack = new PoseStack();
			float scale = state.scale;
			poseStack.scale(scale, scale, scale);
			((LivingEntityRendererInvoker) living).morph$setupRotations(state, poseStack, state.bodyRot, scale);
			poseStack.scale(-1.0F, -1.0F, 1.0F);
			((LivingEntityRendererInvoker) living).morph$scale(state, poseStack);
			poseStack.translate(0.0F, -1.501F, 0.0F);
			Model model = living.getModel();
			model.setupAnim(state);
			Vector3f neck = neck(model.root(), poseStack);
			if (neck == null) return null;
			double reach = Math.hypot(neck.x, neck.z);
			if (reach < UPRIGHT) return null;
			float dx = (float) (neck.x / reach);
			float dz = (float) (neck.z / reach);
			float front = bodyFront(model.root(), poseStack, dx, dz, -Float.MAX_VALUE);
			if (front == -Float.MAX_VALUE) front = (float) reach;
			float shift = front + MARGIN;
			return new Vec3(-dx * shift, 0, -dz * shift);
		} catch (RuntimeException e) {
			return null;
		} finally {
			state.xRot = xRot;
			state.yRot = yRot;
		}
	}

	/** Pivot of the whole head: a horse's "head_parts" group (neck and head) if there is one, else the part named "head". */
	private static @Nullable Vector3f neck(ModelPart root, PoseStack poseStack) {
		Vector3f found = findPivot(root, "head_parts", poseStack);
		return found != null ? found : findPivot(root, "head", poseStack);
	}

	private static @Nullable Vector3f findPivot(ModelPart part, String wanted, PoseStack poseStack) {
		poseStack.pushPose();
		part.translateAndRotate(poseStack);
		try {
			for (Map.Entry<String, ModelPart> child : ((ModelPartAccessor) (Object) part).morph$children().entrySet()) {
				if (child.getKey().equals(wanted)) {
					poseStack.pushPose();
					restingTranslateAndRotate(child.getValue(), poseStack);
					Vector3f pivot = poseStack.last().pose().transformPosition(new Vector3f());
					poseStack.popPose();
					return pivot;
				}
			}
			for (ModelPart child : ((ModelPartAccessor) (Object) part).morph$children().values()) {
				Vector3f pivot = findPivot(child, wanted, poseStack);
				if (pivot != null) return pivot;
			}
			return null;
		} finally {
			poseStack.popPose();
		}
	}

	/**
	 * A part that is hidden right now is measured where it rests. First-person Model hides a humanoid model's head in
	 * first person by moving it 5000 units away, which read as a head far out behind the body: zombies, husks,
	 * skeletons and endermen were pushed forward past the camera instead of keeping that mod's own placement. The
	 * enderman's model shows its head again straight after (EndermanModel.setupAnim), still 5000 units away, so a
	 * part that far out counts as hidden too.
	 */
	private static void restingTranslateAndRotate(ModelPart part, PoseStack poseStack) {
		if (part.visible && Math.abs(part.x) < MOVED_AWAY && Math.abs(part.y) < MOVED_AWAY && Math.abs(part.z) < MOVED_AWAY) {
			part.translateAndRotate(poseStack);
			return;
		}
		float x = part.x;
		float y = part.y;
		float z = part.z;
		PartPose rest = ((ModelPartAccessor) (Object) part).morph$initialPose();
		part.x = rest.x();
		part.y = rest.y();
		part.z = rest.z();
		try {
			part.translateAndRotate(poseStack);
		} finally {
			part.x = x;
			part.y = y;
			part.z = z;
		}
	}

	/**
	 * The ender dragon's placement, the same rule as {@link #headOffset}, from the model as posed for drawing (its neck
	 * and head move with its flight history). Replays {@code EnderDragonRenderer.submit}'s transforms. The dragon is
	 * also raised or lowered so its head is at the camera: its eye height comes from its huge hitbox, not its model, and
	 * left the camera high above its back.
	 */
	private static @Nullable Vec3 measureDragon(Model<?> model, EnderDragonRenderState state) {
		try {
			PoseStack poseStack = new PoseStack();
			poseStack.rotateDegrees(Axis.YP, -state.getHistoricalPos(7).yRot());
			poseStack.rotateDegrees(Axis.XP, (float) (state.getHistoricalPos(5).y() - state.getHistoricalPos(10).y()) * 10.0F);
			poseStack.translate(0.0F, 0.0F, 1.0F);
			poseStack.scale(-1.0F, -1.0F, 1.0F);
			poseStack.translate(0.0F, -1.501F, 0.0F);
			Vector3f neck = neck(model.root(), poseStack);
			if (neck == null) return null;
			double reach = Math.hypot(neck.x, neck.z);
			if (reach < UPRIGHT) return null;
			float dx = (float) (neck.x / reach);
			float dz = (float) (neck.z / reach);
			float front = bodyFront(model.root(), poseStack, dx, dz, -Float.MAX_VALUE);
			if (front == -Float.MAX_VALUE) front = (float) reach;
			float shift = front + MARGIN;
			net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
			float eye = player == null ? neck.y : dev.morphreborn.client.morph.CameraHeight.eyeHeight(player);
			return new Vec3(-dx * shift, eye - neck.y, -dz * shift);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * How far the body reaches along (dx, dz), the way the head points: the furthest corner of the visible cubes, leaving
	 * out the head and face (hidden in this view) and the legs (a spider's reach far forward around the camera).
	 */
	private static float bodyFront(ModelPart part, PoseStack poseStack, float dx, float dz, float front) {
		if (!part.visible) return front;
		poseStack.pushPose();
		part.translateAndRotate(poseStack);
		ModelPartAccessor access = (ModelPartAccessor) (Object) part;
		if (!part.skipDraw) {
			org.joml.Matrix4f pose = poseStack.last().pose();
			for (ModelPart.Cube cube : access.morph$cubes()) {
				for (int corner = 0; corner < 8; corner++) {
					Vector3f v = new Vector3f((corner & 1) == 0 ? cube.minX : cube.maxX, (corner & 2) == 0 ? cube.minY : cube.maxY,
							(corner & 4) == 0 ? cube.minZ : cube.maxZ).div(16F);
					pose.transformPosition(v);
					front = Math.max(front, v.x * dx + v.z * dz);
				}
			}
		}
		for (Map.Entry<String, ModelPart> child : access.morph$children().entrySet()) {
			if (!faceName(child.getKey()) && !child.getKey().contains("leg")) front = bodyFront(child.getValue(), poseStack, dx, dz, front);
		}
		poseStack.popPose();
		return front;
	}

	/**
	 * Whether a form's model has a head part to put the camera at. Ones without (slimes, magma cubes, ghasts, squid,
	 * pufferfish) are a single block of body with the camera somewhere inside it, so their body isn't drawn in first
	 * person at all and the view stays plain.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static boolean hasHead(java.util.UUID player, dev.morphreborn.client.net.MorphRef form) {
		if (form.isSelf() || form.isPlayer()) return true;
		Boolean known = HAS_HEAD.get(form.variantId());
		if (known != null) return known;
		boolean head = true;
		try {
			net.minecraft.world.entity.LivingEntity morph = dev.morphreborn.client.MorphClient.ENTITIES.forWorld(player, form);
			if (morph != null && net.minecraft.client.Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(morph) instanceof LivingEntityRenderer living) {
				LivingEntityRenderState state = (LivingEntityRenderState) living.createRenderState(morph, 1F);
				ModelCapture.selectModel(living, state);
				ModelPart root = living.getModel().root();
				head = hasPart(root, "head") || hasPart(root, "head_parts");
			}
		} catch (RuntimeException e) {
			head = true;
		}
		HAS_HEAD.put(form.variantId(), head);
		return head;
	}

	private static boolean hasPart(ModelPart part, String name) {
		for (Map.Entry<String, ModelPart> child : ((ModelPartAccessor) (Object) part).morph$children().entrySet()) {
			if (child.getKey().equals(name) || hasPart(child.getValue(), name)) return true;
		}
		return false;
	}

	/** Called right after a submitted model is posed: hides the head of a first-person morph body. */
	public static void beforeDraw(Model<?> model, Object state) {
		if (!(state instanceof Mark mark) || !mark.morph$firstPersonBody()) return;
		if (state instanceof EnderDragonRenderState dragon) dragonOffset = measureDragon(model, dragon);
		Deque<Map.Entry<String, ModelPart>> queue = new ArrayDeque<>(((ModelPartAccessor) (Object) model.root()).morph$children().entrySet());
		while (!queue.isEmpty()) {
			Map.Entry<String, ModelPart> entry = queue.poll();
			ModelPart part = entry.getValue();
			if (faceName(entry.getKey())) {
				if (part.visible) {
					part.visible = false;
					HIDDEN.add(part);
				}
			} else {
				queue.addAll(((ModelPartAccessor) (Object) part).morph$children().entrySet());
			}
		}
	}

	/** Called right after the model is drawn: shows what {@link #beforeDraw} hid. */
	public static void afterDraw() {
		if (HIDDEN.isEmpty()) return;
		for (ModelPart part : HIDDEN) part.visible = true;
		HIDDEN.clear();
	}
}
