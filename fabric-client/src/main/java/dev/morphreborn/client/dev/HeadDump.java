package dev.morphreborn.client.dev;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.morphreborn.client.MorphClient;
import dev.morphreborn.client.mixin.EnderDragonRendererAccessor;
import dev.morphreborn.client.mixin.LivingEntityRendererInvoker;
import dev.morphreborn.client.mixin.ModelPartAccessor;
import dev.morphreborn.client.render.HybridMorph;
import dev.morphreborn.client.render.ModelCapture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EnderDragonRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.model.monster.dragon.EnderDragonModel;
import net.minecraft.client.renderer.entity.state.BatRenderState;
import net.minecraft.client.renderer.entity.state.EnderDragonRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PhantomRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Development tool, not part of play: reads every mob's head out of the game's own models and writes it to
 * {@code morph-heads.json} in the run folder. {@code tools/gen_head_pack.py} turns that into the resource pack's head
 * models (what players without the mod see as a borrowed head when multimorphed) and the plugin's table of where each
 * body's neck is.
 * <p>
 * Each head is given as block-model elements in item-model space: pixels, the neck (the head part's pivot) at (8, 8, 8)
 * and the face towards -z, which is how an item display shows it facing the way it's turned. Sizes are the model's own;
 * {@code scale} is how much the mob's renderer enlarges it (a giant's head is a zombie's, six times over).
 * <p>
 * Each mob's {@code parts} are what it lends besides (or instead of) a head, as the client mod grafts them
 * (HybridMorph.findAppendages): wings, tails, fins, rods and stingers, each as elements around the point where it joins
 * its body. {@code lendsHead} is false for a creature the mod shows by its parts alone (a bat lends its wings, a wolf its
 * ears and tail). The ender dragon only has parts: its renderer isn't a living-entity one.
 */
public final class HeadDump {
	/** Parts drawn as part of the face when they aren't inside the head part (a chicken's beak and wattle). */
	private static final Set<String> FACE_PARTS = Set.of("hat", "beak", "red_thing", "nose", "jaw", "mouth", "upper_mouth", "lower_mouth", "snout",
			"left_ear", "right_ear", "left_horn", "right_horn", "eyes", "left_antenna", "right_antenna");

	private static int nextId;

	private HeadDump() {}

	public static String run(Minecraft mc) {
		if (mc.level == null) return "Join a world first.";
		JsonObject out = new JsonObject();
		List<String> skipped = new ArrayList<>();
		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
			try {
				JsonObject head = dump(mc, type);
				if (head != null) out.add(id, head);
			} catch (RuntimeException e) {
				skipped.add(id + " (" + e + ")");
			}
		}
		// the player's model (player forms, and your own body), as the morph-skin boxes only
		try {
			JsonObject player = player(mc);
			if (player != null) out.add("minecraft:player", player);
		} catch (RuntimeException e) {
			skipped.add("minecraft:player (" + e + ")");
		}
		Path file = mc.gameDirectory.toPath().resolve("morph-heads.json");
		try {
			Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(out), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return "Could not write " + file + ": " + e;
		}
		if (!skipped.isEmpty()) MorphClient.LOGGER.warn("Head dump skipped: {}", skipped);
		return "Wrote " + out.size() + " heads to " + file + (skipped.isEmpty() ? "" : " (" + skipped.size() + " failed, see the log)");
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static @Nullable JsonObject dump(Minecraft mc, EntityType<?> type) {
		Entity entity = type.create(mc.level, new EntitySpawnRequest(EntitySpawnReason.LOAD, true));
		if (!(entity instanceof LivingEntity living) || entity instanceof Player) return null;
		entity.setId(-1_000_000 - nextId++); // renderers read the id; far below anything the server hands out
		EntityRenderer<?, ?> renderer = mc.getEntityRenderDispatcher().getRenderer(entity);
		if (renderer instanceof EnderDragonRenderer dragon) return dragon(dragon, entity);
		if (!(renderer instanceof LivingEntityRenderer lr)) return null;
		LivingEntityRenderState state = (LivingEntityRenderState) lr.createRenderState(living, 1F);
		state.bodyRot = 0F;
		state.yRot = 0F;
		state.xRot = 0F;
		ModelCapture.selectModel(lr, state);
		EntityModel model = lr.getModel();
		ModelPart root = model.root();
		for (ModelPart part : root.getAllParts()) part.resetPose();

		// The parts that make up the head: the part called "head" (the wither's middle one) and everything in it, plus
		// face parts beside it. Mobs without one (slimes, ghasts, squid, fish, bees) lend their whole body as the head.
		List<List<ModelPart>> heads = new ArrayList<>();
		List<ModelPart> headPath = findPath(root, "head", new ArrayList<>());
		if (headPath == null) headPath = findPath(root, "center_head", new ArrayList<>());
		boolean whole = headPath == null;
		if (whole) {
			heads.add(new ArrayList<>(List.of(root)));
		} else {
			heads.add(headPath);
			List<ModelPart> parentOfHead = headPath.subList(0, headPath.size() - 1);
			ModelPart headParent = parentOfHead.getLast();
			for (Map.Entry<String, ModelPart> sibling : ((ModelPartAccessor) (Object) headParent).morph$children().entrySet()) {
				if (FACE_PARTS.contains(sibling.getKey())) {
					List<ModelPart> path = new ArrayList<>(parentOfHead);
					path.add(sibling.getValue());
					heads.add(path);
				}
			}
		}

		// The renderer's own transforms, as LivingEntityRenderer.submit applies them, facing +z (yaw 0).
		PoseStack base = new PoseStack();
		base.scale(state.scale, state.scale, state.scale);
		((LivingEntityRendererInvoker) lr).morph$setupRotations(state, base, 0F, state.scale);
		base.scale(-1F, -1F, 1F);
		((LivingEntityRendererInvoker) lr).morph$scale(state, base);
		base.translate(0F, -1.501F, 0F);
		PoseStack scaleOnly = new PoseStack();
		((LivingEntityRendererInvoker) lr).morph$scale(state, scaleOnly);
		float rendererScale = (float) Math.cbrt(Math.abs(scaleOnly.last().pose().determinant()));

		// neck: where a borrowed head goes on this body; pivot: the point of this mob's own head that goes there. Both
		// are the head part's pivot, except for a whole body: a borrowed head sits on top of it, and it sits on a neck
		// with its bottom.
		Vector3f neck;
		Vector3f pivot;
		if (whole) {
			float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
			float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
			PoseStack pose = new PoseStack();
			pose.last().pose().set(base.last().pose());
			bounds(root, pose, lo, hi);
			if (lo[0] > hi[0]) return null;
			neck = new Vector3f((lo[0] + hi[0]) / 2F, hi[1], (lo[2] + hi[2]) / 2F);
			pivot = new Vector3f((lo[0] + hi[0]) / 2F, lo[1], (lo[2] + hi[2]) / 2F);
		} else {
			PoseStack neckPose = new PoseStack();
			neckPose.last().pose().set(base.last().pose());
			for (ModelPart part : headPath) part.translateAndRotate(neckPose);
			neck = neckPose.last().pose().transformPosition(new Vector3f());
			pivot = neck;
		}

		// an animal that lends its ears and tail instead of its head (HybridMorph.lentEars): its ears are marked among the
		// head's elements and measured, and its tail is written as a model of its own
		Measure measure = new Measure(whole ? java.util.Collections.emptySet() : HybridMorph.lentEars(root, base));
		JsonArray elements = new JsonArray();
		for (List<ModelPart> path : heads) {
			PoseStack pose = new PoseStack();
			pose.last().pose().set(base.last().pose());
			for (int i = 0; i < path.size() - 1; i++) path.get(i).translateAndRotate(pose);
			collect(path.getLast(), pose, pivot, rendererScale * state.scale, elements, measure);
		}
		if (elements.isEmpty()) return null;

		JsonObject head = new JsonObject();
		Identifier texture = lr.getTextureLocation(state);
		String path = texture.getPath();
		if (path.startsWith("textures/")) path = path.substring("textures/".length());
		if (path.endsWith(".png")) path = path.substring(0, path.length() - 4);
		head.addProperty("texture", texture.getNamespace() + ":" + path);
		head.addProperty("scale", round(rendererScale));
		head.add("neck", vec(neck.x, neck.y, neck.z));
		if (whole) head.addProperty("whole", true);
		Vec3 passenger = entity.getAttachments().get(EntityAttachment.PASSENGER, 0, 0F);
		head.add("passenger", vec((float) passenger.x, (float) passenger.y, (float) passenger.z));
		head.addProperty("width", round(entity.getBbWidth()));
		head.addProperty("height", round(entity.getBbHeight()));
		head.add("elements", elements);
		// the torso a borrowed tail is grafted onto, as the client mod measures it: item space (the face towards -z),
		// blocks from the feet, at the mob's real size
		PoseStack itemBase = itemBase(base);
		float[][] torso = HybridMorph.torsoBox(root, itemBase);
		if (torso != null) head.add("torso", box(torso));
		float[][] all = all(root, itemBase);
		if (all != null) head.add("all", box(all));
		// Parts are posed as the mod draws them, by the creature's own animation (heads and torsos above are at rest: at
		// rest a vex's and an allay's wings hang edge-on behind them, and from behind they disappeared). Where it flaps,
		// at one end of the flap, and each part carries its turn to the other end (see flap).
		Flap flap = null;
		java.util.function.Consumer<Float> animate = t -> {
			clock(state, t);
			model.setupAnim(state);
		};
		try {
			flap = flap(animate, root, base);
			if (flap != null) {
				for (ModelPart part : root.getAllParts()) part.resetPose();
				clock(state, flap.start());
				model.setupAnim(state);
			} else {
				for (ModelPart part : root.getAllParts()) part.resetPose();
				LivingEntityRenderState fresh = (LivingEntityRenderState) lr.createRenderState(living, 1F);
				fresh.bodyRot = 0F;
				fresh.yRot = 0F;
				fresh.xRot = 0F;
				ModelCapture.selectModel(lr, fresh);
				model.setupAnim(fresh);
			}
		} catch (RuntimeException e) {
			flap = null;
			for (ModelPart part : root.getAllParts()) part.resetPose();
		}
		// the mod grafts parts on unless the creature is lent whole as a head (a slime has none to give)
		JsonArray parts = whole ? new JsonArray() : parts(root, base, rendererScale * state.scale, flap, animate);
		// ears only with a tail outside the head, as before parts were written (a guardian's spikes stand out of its
		// head, and its tail is in it: it lends its head)
		boolean hasTail = false;
		for (var p : parts) hasTail |= p.getAsJsonObject().get("kind").getAsString().equals("tail");
		boolean earsAndTail = hasTail && !measure.ears.isEmpty() && measure.earLo[0] <= measure.earHi[0];
		if (earsAndTail) {
			// item pixels, as the elements: the ears' box, and the box of the head's big blocks without them
			JsonObject ears = new JsonObject();
			ears.add("box", box(new float[][] {measure.earLo, measure.earHi}));
			ears.add("core", box(measure.core()));
			head.add("ears", ears);
		}
		// as the client mod decides (HybridMorph.attach): a pufferfish is its puffed body, not its fins
		boolean partsOnly = type != EntityTypes.PUFFERFISH && (earsAndTail || HybridMorph.hasDefiningParts(root));
		head.addProperty("lendsHead", !partsOnly);
		if (partsOnly && whole) parts = parts(root, base, rendererScale * state.scale, flap, animate); // a bee: its wings and stinger
		if (!parts.isEmpty()) head.add("parts", parts);
		// last: the capture poses the model by its animation, which shows and hides parts (an armadillo rolling up), and
		// everything above is measured as the model was
		LivingEntityRenderState fresh = (LivingEntityRenderState) lr.createRenderState(living, 1F);
		fresh.bodyRot = 0F;
		fresh.yRot = 0F;
		fresh.xRot = 0F;
		JsonArray boxes = boxes(lr, fresh);
		if (!boxes.isEmpty()) head.add("boxes", boxes);
		return head;
	}

	/**
	 * The ender dragon, which lends its wings and tail and no head: its parts, torso and whole box, at rest, as
	 * EnderDragonRenderer draws it, turned to face +z as a living mob's renderer does at yaw 0.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static JsonObject dragon(EnderDragonRenderer renderer, Entity entity) {
		EnderDragonModel model = ((EnderDragonRendererAccessor) renderer).morph$model();
		ModelPart root = model.root();
		for (ModelPart part : root.getAllParts()) part.resetPose();
		EnderDragonRenderState state = (EnderDragonRenderState) ((EntityRenderer) renderer).createRenderState(entity, 1F);
		PoseStack base = new PoseStack();
		base.rotateDegrees(Axis.YP, 180F);
		base.translate(0F, 0F, 1F);
		base.scale(-1F, -1F, 1F);
		base.translate(0F, -1.501F, 0F);
		JsonObject out = new JsonObject();
		out.addProperty("texture", "minecraft:entity/enderdragon/dragon");
		out.addProperty("scale", 1F);
		out.addProperty("width", round(entity.getBbWidth()));
		out.addProperty("height", round(entity.getBbHeight()));
		out.addProperty("lendsHead", false);
		PoseStack itemBase = itemBase(base);
		float[][] torso = HybridMorph.torsoBox(root, itemBase);
		if (torso != null) out.add("torso", box(torso));
		float[][] all = all(root, itemBase);
		if (all != null) out.add("all", box(all));
		// its wings and tail as the mod draws them, flapping at the share of a hovering dragon's beat the mod uses
		// (HybridMorph.DRAGON_FLAP_SPEED): a hovering dragon's flapTime grows by 0.2 a tick (EnderDragon.aiStep)
		Flap flap = null;
		java.util.function.Consumer<Float> animate = t -> {
			state.flapTime = t * 0.2F * HybridMorph.DRAGON_FLAP_SPEED;
			model.setupAnim(state);
		};
		try {
			flap = flap(animate, root, base);
			for (ModelPart part : root.getAllParts()) part.resetPose();
			state.flapTime = (flap == null ? 0F : flap.start()) * 0.2F * HybridMorph.DRAGON_FLAP_SPEED;
			model.setupAnim(state);
		} catch (RuntimeException e) {
			flap = null;
			for (ModelPart part : root.getAllParts()) part.resetPose();
		}
		out.add("parts", parts(root, base, 1F, flap, animate));
		return out;
	}

	/**
	 * A creature's clocks at {@code t} ticks: its age, and the flap clocks that aren't its age (a phantom's, sped up as
	 * the mod does: HybridMorph.PHANTOM_FLAP_SPEED); a bat flying.
	 */
	private static void clock(LivingEntityRenderState state, float t) {
		state.ageInTicks = t;
		if (state instanceof PhantomRenderState phantom) phantom.flapTime = t * HybridMorph.PHANTOM_FLAP_SPEED;
		if (state instanceof BatRenderState bat) {
			bat.isResting = false;
			bat.restAnimationState.stop();
			bat.flyAnimationState.startIfStopped(0);
		}
	}

	/** Where a flap starts (ticks into the creature's animation) and how long one stroke takes. */
	private record Flap(float start, float ticks) {}

	/** Ticks the flap is looked for over, and the step. */
	private static final float FLAP_SPAN = 40F, FLAP_STEP = 0.25F;
	/** A flap shorter than this many ticks is drawn at it (each stroke is a display update for every viewer). */
	private static final float FLAP_MIN_TICKS = 2F;

	/**
	 * For players without the mod, who see each part as a display that can only turn as a whole: the two poses of the
	 * creature's animation in which a part is turned furthest apart (the part that moves most: a bee's buzzing wings, not
	 * its slowly bobbing stinger), a stroke apart; of those within 3% of the furthest, the two closest in time (one stroke,
	 * not one and a half). Null when no part turns by 5 degrees (a chicken standing, a blaze, whose rods move without
	 * turning).
	 */
	private static @Nullable Flap flap(java.util.function.Consumer<Float> pose, ModelPart root, PoseStack base) {
		PoseStack itemBase = itemBase(base);
		int samples = (int) (FLAP_SPAN / FLAP_STEP) + 1;
		List<List<org.joml.Quaternionf>> turns = new ArrayList<>(samples);
		for (int k = 0; k < samples; k++) {
			for (ModelPart part : root.getAllParts()) part.resetPose();
			pose.accept(k * FLAP_STEP);
			List<org.joml.Quaternionf> at = new ArrayList<>();
			for (PartRef ref : partRefs(root, itemBase)) at.add(ref.joint().getUnnormalizedRotation(new org.joml.Quaternionf()).normalize());
			turns.add(at);
		}
		int parts = turns.getFirst().size();
		if (parts == 0) return null;
		float[][] apart = new float[samples][samples];
		float best = 0F;
		for (int a = 0; a < samples; a++) {
			for (int b = a + 1; b < samples && (b - a) * FLAP_STEP <= FLAP_SPAN / 2; b++) {
				float most = 0F;
				for (int i = 0; i < parts; i++) most = Math.max(most, angle(turns.get(a).get(i), turns.get(b).get(i)));
				apart[a][b] = most;
				best = Math.max(best, most);
			}
		}
		if (best < Math.toRadians(5)) return null;
		int fa = -1, fb = -1;
		for (int a = 0; a < samples; a++) {
			for (int b = a + 1; b < samples && (b - a) * FLAP_STEP <= FLAP_SPAN / 2; b++) {
				if (apart[a][b] >= best * 0.97F && (fa < 0 || b - a < fb - fa)) {
					fa = a;
					fb = b;
				}
			}
		}
		return new Flap(fa * FLAP_STEP, (fb - fa) * FLAP_STEP);
	}

	private static float angle(org.joml.Quaternionf a, org.joml.Quaternionf b) {
		float dot = Math.abs(a.dot(b));
		return 2F * (float) Math.acos(Math.min(1F, dot));
	}

	/**
	 * Each part's turn from one end of the flap to the other (item space, about where it joins), with the model posed at
	 * the start of the flap when this returns.
	 */
	private static List<org.joml.Quaternionf> strokes(java.util.function.Consumer<Float> pose, ModelPart root, PoseStack base, Flap flap) {
		PoseStack itemBase = itemBase(base);
		List<org.joml.Quaternionf> to = new ArrayList<>();
		for (ModelPart part : root.getAllParts()) part.resetPose();
		pose.accept(flap.start() + flap.ticks());
		for (PartRef ref : partRefs(root, itemBase)) to.add(ref.joint().getUnnormalizedRotation(new org.joml.Quaternionf()).normalize());
		for (ModelPart part : root.getAllParts()) part.resetPose();
		pose.accept(flap.start());
		List<PartRef> from = partRefs(root, itemBase);
		List<org.joml.Quaternionf> out = new ArrayList<>();
		for (int i = 0; i < from.size(); i++) {
			org.joml.Quaternionf a = from.get(i).joint().getUnnormalizedRotation(new org.joml.Quaternionf()).normalize();
			out.add(new org.joml.Quaternionf(to.get(i)).mul(a.conjugate()).normalize());
		}
		return out;
	}

	/**
	 * The model as the mod's transformation sees it (ModelCapture.capture: the renderer's transforms and the creature's
	 * animation, facing +z), each cube as a box: its middle (blocks from the feet), its size along its own axes (blocks,
	 * an outer layer's inflation included) and its turn, as [x, y, z, width, height, depth, qx, qy, qz, qw]. Players
	 * without the mod see a transformation, and a creature being absorbed, as these boxes in the morph skin.
	 */
	@SuppressWarnings({"rawtypes"})
	private static JsonArray boxes(LivingEntityRenderer renderer, LivingEntityRenderState state) {
		JsonArray out = new JsonArray();
		for (ModelCapture.Box b : ModelCapture.capture(renderer, state)) {
			Matrix3f pose = b.pose().get3x3(new Matrix3f());
			Vector3f kx = pose.getColumn(0, new Vector3f()), ky = pose.getColumn(1, new Vector3f()), kz = pose.getColumn(2, new Vector3f());
			float lx = kx.length(), ly = ky.length(), lz = kz.length();
			if (lx < 1e-6F || ly < 1e-6F || lz < 1e-6F) continue;
			float w = (b.w() + 2 * b.gx()) * lx / 16F, h = (b.h() + 2 * b.gy()) * ly / 16F, d = (b.d() + 2 * b.gz()) * lz / 16F;
			if (w + h + d < 1e-4F) continue;
			kx.div(lx);
			ky.div(ly);
			kz.div(lz);
			if (new Vector3f(kx).cross(ky).dot(kz) < 0F) kx.negate(); // a mirrored part: the same box
			org.joml.Quaternionf q = new Matrix3f(kx, ky, kz).getNormalizedRotation(new org.joml.Quaternionf());
			Vector3f c = b.pose().transformPosition(new Vector3f(b.x() + b.w() / 2F, b.y() + b.h() / 2F, b.z() + b.d() / 2F).div(16F));
			JsonArray box = new JsonArray();
			for (float v : new float[] {c.x, c.y, c.z, w, h, d, q.x, q.y, q.z, q.w}) box.add(round(v));
			out.add(box);
		}
		return out;
	}

	/** The player model's boxes (as the player is drawn: 0.9375 of the model), standing and facing +z. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static @Nullable JsonObject player(Minecraft mc) {
		if (mc.player == null) return null;
		EntityRenderer renderer = mc.getEntityRenderDispatcher().getRenderer(mc.player);
		if (!(renderer instanceof LivingEntityRenderer lr)) return null;
		LivingEntityRenderState state = (LivingEntityRenderState) lr.createRenderState(mc.player, 1F);
		state.bodyRot = 0F;
		state.yRot = 0F;
		state.xRot = 0F;
		state.scale = 1F;
		state.walkAnimationPos = 0F;
		state.walkAnimationSpeed = 0F;
		JsonArray boxes = boxes(lr, state);
		if (boxes.isEmpty()) return null;
		JsonObject out = new JsonObject();
		out.addProperty("height", 1.8F);
		out.add("boxes", boxes);
		return out;
	}

	/** {@code base} in item space: turned half way round, as an item display shows its item. */
	private static PoseStack itemBase(PoseStack base) {
		PoseStack itemBase = new PoseStack();
		itemBase.last().pose().rotationY((float) Math.PI).mul(base.last().pose());
		itemBase.last().normal().rotationY((float) Math.PI).mul(base.last().normal());
		return itemBase;
	}

	/** The whole model's box {lo, hi} in {@code base}'s frame (item space: blocks from the feet), or null when empty. */
	private static float @Nullable [][] all(ModelPart root, PoseStack base) {
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		PoseStack pose = new PoseStack();
		pose.last().pose().set(base.last().pose());
		bounds(root, pose, lo, hi);
		return lo[0] > hi[0] ? null : new float[][] {lo, hi};
	}

	/**
	 * One part the client mod grafts onto a main body (HybridMorph.findAppendages), as posed now: its name, kind, the
	 * parts drawn with it (a segmented tail's segments) and the pose of what it hangs from.
	 */
	private record PartRef(String name, String kind, boolean segment, List<ModelPart> limbs, ModelPart first, Matrix4f parent) {
		/** Its own pose: where it joins its body, turned as it is. */
		Matrix4f joint() {
			PoseStack at = new PoseStack();
			at.last().pose().set(parent);
			first.translateAndRotate(at);
			return new Matrix4f(at.last().pose());
		}
	}

	/**
	 * Every part named as a wing, tail, fin, rods or stinger outside the head, with everything in it (a wing's tip), in
	 * the model's order. A tail made of numbered segments side by side (the dragon's tail0..tail11) is one part, from its
	 * first segment, as the mod moves it.
	 */
	private static List<PartRef> partRefs(ModelPart root, PoseStack base) {
		List<PartRef> out = new ArrayList<>();
		PoseStack pose = new PoseStack();
		pose.last().pose().set(base.last().pose());
		findParts(root, pose, out);
		return out;
	}

	private static void findParts(ModelPart part, PoseStack pose, List<PartRef> out) {
		if (!part.visible) return;
		pose.pushPose();
		part.translateAndRotate(pose);
		Map<String, ModelPart> children = ((ModelPartAccessor) (Object) part).morph$children();
		boolean chained = false;
		for (Map.Entry<String, ModelPart> child : children.entrySet()) {
			String name = child.getKey();
			ModelPart limb = child.getValue();
			if (HybridMorph.headName(name) || !limb.visible) continue;
			if (!HybridMorph.appendageName(name)) {
				findParts(limb, pose, out);
				continue;
			}
			boolean segment = name.matches("tail\\d+");
			if (segment && chained) continue;
			List<ModelPart> limbs = new ArrayList<>();
			ModelPart first = limb;
			if (segment) {
				chained = true;
				int lowest = Integer.MAX_VALUE;
				for (Map.Entry<String, ModelPart> s : children.entrySet()) {
					if (!s.getKey().matches("tail\\d+") || !s.getValue().visible) continue;
					limbs.add(s.getValue());
					int n = Integer.parseInt(s.getKey().substring(4));
					if (n < lowest) {
						lowest = n;
						first = s.getValue();
					}
				}
			} else {
				limbs.add(limb);
			}
			String kind = name.equals("rods") || name.matches("part\\d+") ? "rods" : name.contains("wing") ? "wing" : name.contains("tail") ? "tail" : "other";
			out.add(new PartRef(segment ? "tail" : name, kind, segment, limbs, first, new Matrix4f(pose.last().pose())));
		}
		pose.popPose();
	}

	/**
	 * The parts, as posed now, each written as elements around the point where it joins its body (at (8, 8, 8)); with a
	 * flap, each part's turn to the flap's other end and the ticks a stroke takes (parts turned by under 3 degrees keep
	 * still). The model is posed for the flap's start when {@code flap} is given (by the caller).
	 */
	private static JsonArray parts(ModelPart root, PoseStack base, float scale, @Nullable Flap flap, java.util.function.Consumer<Float> pose) {
		JsonArray out = new JsonArray();
		List<org.joml.Quaternionf> strokes = null;
		if (flap != null) {
			// strokes() poses the model at both ends and leaves it at the start, where the parts are written from
			strokes = strokes(pose, root, base, flap);
		}
		List<PartRef> refs = partRefs(root, base);
		for (int i = 0; i < refs.size(); i++) {
			PartRef ref = refs.get(i);
			Vector3f joint = ref.joint().transformPosition(new Vector3f());
			JsonArray elements = new JsonArray();
			Measure measure = new Measure(java.util.Collections.emptySet());
			for (ModelPart l : ref.limbs()) {
				PoseStack from = new PoseStack();
				from.last().pose().set(ref.parent());
				collect(l, from, joint, scale, elements, measure);
			}
			if (elements.isEmpty()) continue;
			JsonObject p = new JsonObject();
			p.addProperty("name", ref.name());
			p.addProperty("kind", ref.kind());
			// a tail joins the back: set into it, or moved out of it (the mod's Graft.touch; not a fin, not a segmented tail)
			p.addProperty("touch", ref.kind().equals("tail") && !ref.segment() && !ref.name().contains("fin"));
			p.add("joint", vec(-joint.x, joint.y, -joint.z)); // item space is the body's frame turned half way round
			p.add("box", box(new float[][] {measure.allLo, measure.allHi}));
			if (strokes != null && i < strokes.size() && angle(new org.joml.Quaternionf(), strokes.get(i)) > Math.toRadians(3)) {
				org.joml.Quaternionf q = strokes.get(i);
				JsonArray turn = new JsonArray();
				turn.add(round(q.x));
				turn.add(round(q.y));
				turn.add(round(q.z));
				turn.add(round(q.w));
				p.add("flap", turn);
				p.addProperty("flapTicks", Math.max(Math.round(FLAP_MIN_TICKS), Math.round(flap.ticks())));
			}
			p.add("elements", elements);
			out.add(p);
		}
		return out;
	}

	/** {lo, hi} as two arrays. */
	private static JsonArray box(float[][] b) {
		JsonArray out = new JsonArray();
		out.add(vec(b[0][0], b[0][1], b[0][2]));
		out.add(vec(b[1][0], b[1][1], b[1][2]));
		return out;
	}

	/** Boxes measured while collecting, in item pixels: the ears', every cube's, and each other cube's with its volume. */
	private static final class Measure {
		final Set<ModelPart.Cube> ears;
		final float[] earLo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, earHi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		final float[] allLo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, allHi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		final List<float[][]> others = new ArrayList<>();
		float biggest;

		Measure(Set<ModelPart.Cube> ears) {
			this.ears = ears;
		}

		void add(ModelPart.Cube cube, float[] lo, float[] hi) {
			grow(allLo, allHi, lo, hi);
			float volume = (cube.maxX - cube.minX) * (cube.maxY - cube.minY) * (cube.maxZ - cube.minZ);
			biggest = Math.max(biggest, volume);
			if (ears.contains(cube)) grow(earLo, earHi, lo, hi);
			else others.add(new float[][] {lo, hi, {volume}});
		}

		/** The head's big blocks without the ears (as the client mod's coreBox: a quarter of the biggest or more). */
		float[][] core() {
			float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
			for (float[][] o : others) if (o[2][0] >= biggest * 0.25F) grow(lo, hi, o[0], o[1]);
			return new float[][] {lo, hi};
		}

		private static void grow(float[] lo, float[] hi, float[] blo, float[] bhi) {
			for (int i = 0; i < 3; i++) {
				lo[i] = Math.min(lo[i], blo[i]);
				hi[i] = Math.max(hi[i], bhi[i]);
			}
		}
	}

	/** Depth-first path from {@code part} to the first descendant called {@code name}, both ends included. */
	private static @Nullable List<ModelPart> findPath(ModelPart part, String name, List<ModelPart> path) {
		path.add(part);
		for (Map.Entry<String, ModelPart> child : ((ModelPartAccessor) (Object) part).morph$children().entrySet()) {
			if (child.getKey().equals(name)) {
				path.add(child.getValue());
				return path;
			}
		}
		for (ModelPart child : ((ModelPartAccessor) (Object) part).morph$children().values()) {
			List<ModelPart> found = findPath(child, name, new ArrayList<>(path));
			if (found != null) return found;
		}
		return null;
	}

	/** World-space bounds of every visible cube under {@code part}. */
	private static void bounds(ModelPart part, PoseStack pose, float[] lo, float[] hi) {
		if (!part.visible) return;
		ModelPartAccessor access = (ModelPartAccessor) (Object) part;
		pose.pushPose();
		part.translateAndRotate(pose);
		if (!part.skipDraw) {
			for (ModelPart.Cube cube : access.morph$cubes()) {
				for (ModelPart.Polygon polygon : cube.polygons) {
					for (ModelPart.Vertex v : polygon.vertices()) {
						Vector3f w = pose.last().pose().transformPosition(new Vector3f(v.x() / 16F, v.y() / 16F, v.z() / 16F));
						lo[0] = Math.min(lo[0], w.x); lo[1] = Math.min(lo[1], w.y); lo[2] = Math.min(lo[2], w.z);
						hi[0] = Math.max(hi[0], w.x); hi[1] = Math.max(hi[1], w.y); hi[2] = Math.max(hi[2], w.z);
					}
				}
			}
		}
		for (ModelPart child : access.morph$children().values()) bounds(child, pose, lo, hi);
		pose.popPose();
	}

	/**
	 * Every visible cube of {@code part} and its children as an element. A cube's corners in item space are
	 * {@code A * local + t}: the element is the cube's own box at {@code t}, turned by {@code A} about {@code t}.
	 */
	private static void collect(ModelPart part, PoseStack pose, Vector3f neck, float scale, JsonArray out, Measure measure) {
		if (!part.visible) return;
		ModelPartAccessor access = (ModelPartAccessor) (Object) part;
		pose.pushPose();
		part.translateAndRotate(pose);
		Matrix4f world = pose.last().pose();
		// world -> item space: subtract the neck, undo the renderer's scale, turn 180 degrees about Y (an item display
		// shows its item turned that way), blocks -> pixels, neck at (8, 8, 8)
		Matrix4f toItem = new Matrix4f().translate(8, 8, 8).scale(16F / scale).rotateY((float) Math.PI).translate(-neck.x, -neck.y, -neck.z);
		Matrix4f local = new Matrix4f(toItem).mul(world).scale(1F / 16F); // cube pixels -> item pixels
		Matrix3f rot = new Matrix3f();
		local.get3x3(rot);
		Vector3f t = local.transformPosition(new Vector3f());
		if (!part.skipDraw) {
			for (ModelPart.Cube cube : access.morph$cubes()) {
				JsonObject element = element(cube, rot, t);
				if (element == null) continue;
				if (measure.ears.contains(cube)) element.addProperty("ear", true);
				out.add(element);
				// its box in item pixels, from its turned corners
				float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
				for (ModelPart.Polygon polygon : cube.polygons) {
					for (ModelPart.Vertex v : polygon.vertices()) {
						Vector3f p = local.transformPosition(new Vector3f(v.x(), v.y(), v.z()));
						lo[0] = Math.min(lo[0], p.x); lo[1] = Math.min(lo[1], p.y); lo[2] = Math.min(lo[2], p.z);
						hi[0] = Math.max(hi[0], p.x); hi[1] = Math.max(hi[1], p.y); hi[2] = Math.max(hi[2], p.z);
					}
				}
				measure.add(cube, lo, hi);
			}
		}
		for (ModelPart child : access.morph$children().values()) collect(child, pose, neck, scale, out, measure);
		pose.popPose();
	}

	/**
	 * A cube as an element. {@code rot} may carry a uniform scale besides the turn: models enlarged by their root part
	 * rather than their renderer (a ghast's 4.5, a giant's 6) have one. It is taken out first and applied to the cube's
	 * size: left in, a ghast's tentacles kept their own size but stood 4.5 times as far apart, and a giant's head was a
	 * zombie's, written upside down.
	 */
	private static @Nullable JsonObject element(ModelPart.Cube cube, Matrix3f rot, Vector3f t) {
		float s = (float) Math.cbrt(Math.abs(rot.determinant()));
		if (s < 1e-6F) return null;
		if (quarterTurns(new Matrix3f(rot).scale(1F / s))) return alignedElement(cube, rot, t);
		Matrix3f turn = new Matrix3f(rot).scale(1F / s);
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (ModelPart.Polygon polygon : cube.polygons) {
			for (ModelPart.Vertex v : polygon.vertices()) {
				lo[0] = Math.min(lo[0], v.x() * s); lo[1] = Math.min(lo[1], v.y() * s); lo[2] = Math.min(lo[2], v.z() * s);
				hi[0] = Math.max(hi[0], v.x() * s); hi[1] = Math.max(hi[1], v.y() * s); hi[2] = Math.max(hi[2], v.z() * s);
			}
		}
		if (lo[0] > hi[0]) return null;
		JsonObject element = new JsonObject();
		element.add("from", vec(t.x + lo[0], t.y + lo[1], t.z + lo[2]));
		element.add("to", vec(t.x + hi[0], t.y + hi[1], t.z + hi[2]));
		Vector3f euler = turn.getEulerAnglesZYX(new Vector3f());
		if (Math.abs(euler.x) + Math.abs(euler.y) + Math.abs(euler.z) > 1e-4F) {
			JsonObject rotation = new JsonObject();
			rotation.add("origin", vec(t.x, t.y, t.z));
			rotation.addProperty("x", round((float) Math.toDegrees(euler.x)));
			rotation.addProperty("y", round((float) Math.toDegrees(euler.y)));
			rotation.addProperty("z", round((float) Math.toDegrees(euler.z)));
			element.add("rotation", rotation);
		}
		JsonObject faces = new JsonObject();
		for (ModelPart.Polygon polygon : cube.polygons) {
			float[][] pos = new float[polygon.vertices().length][];
			for (int i = 0; i < pos.length; i++) pos[i] = new float[] {polygon.vertices()[i].x(), polygon.vertices()[i].y(), polygon.vertices()[i].z()};
			addFace(faces, new Vector3f(polygon.normal()), polygon, pos);
		}
		if (faces.isEmpty()) return null;
		element.add("faces", faces);
		return element;
	}

	/** True when {@code rot} only turns by whole quarter turns, so the element can be written turned, without a rotation. */
	private static boolean quarterTurns(Matrix3f rot) {
		float[] m = new float[9];
		rot.get(m);
		for (float v : m) {
			float a = Math.abs(v);
			if (a > 1e-3F && Math.abs(a - 1F) > 1e-3F) return false;
		}
		return true;
	}

	/** The cube turned into item space as it is: corners, faces and UV corners all taken from the turned polygons. */
	private static @Nullable JsonObject alignedElement(ModelPart.Cube cube, Matrix3f rot, Vector3f t) {
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		JsonObject faces = new JsonObject();
		for (ModelPart.Polygon polygon : cube.polygons) {
			float[][] pos = new float[polygon.vertices().length][];
			for (int i = 0; i < pos.length; i++) {
				ModelPart.Vertex v = polygon.vertices()[i];
				Vector3f p = rot.transform(new Vector3f(v.x(), v.y(), v.z())).add(t);
				pos[i] = new float[] {p.x, p.y, p.z};
				lo[0] = Math.min(lo[0], p.x); lo[1] = Math.min(lo[1], p.y); lo[2] = Math.min(lo[2], p.z);
				hi[0] = Math.max(hi[0], p.x); hi[1] = Math.max(hi[1], p.y); hi[2] = Math.max(hi[2], p.z);
			}
			addFace(faces, rot.transform(new Vector3f(polygon.normal())).normalize(), polygon, pos); // rot may scale
		}
		if (lo[0] > hi[0] || faces.isEmpty()) return null;
		JsonObject element = new JsonObject();
		element.add("from", vec(lo[0], lo[1], lo[2]));
		element.add("to", vec(hi[0], hi[1], hi[2]));
		element.add("faces", faces);
		return element;
	}

	private static void addFace(JsonObject faces, Vector3f n, ModelPart.Polygon polygon, float[][] pos) {
		String face = n.x > 0.5F ? "east" : n.x < -0.5F ? "west" : n.y > 0.5F ? "up" : n.y < -0.5F ? "down" : n.z > 0.5F ? "south" : n.z < -0.5F ? "north" : null;
		if (face == null) return;
		ModelPart.Vertex first = polygon.vertices()[corner(pos, face, false)];
		ModelPart.Vertex second = polygon.vertices()[corner(pos, face, true)];
		JsonArray uv = new JsonArray();
		uv.add(round(first.u() * 16F));
		uv.add(round(first.v() * 16F));
		uv.add(round(second.u() * 16F));
		uv.add(round(second.v() * 16F));
		JsonObject f = new JsonObject();
		f.add("uv", uv);
		f.addProperty("texture", "#t");
		faces.add(face, f);
	}

	/**
	 * The polygon's corner that a block model puts the first (or, opposite, the second) UV pair on, by FaceBakery's
	 * default UVs: up (min x, min z), down (min x, max z), north (max x, max y), south (min x, max y), west (min z, max y),
	 * east (max z, max y).
	 */
	private static int corner(float[][] pos, String face, boolean opposite) {
		int best = 0;
		float bestScore = -Float.MAX_VALUE;
		for (int i = 0; i < pos.length; i++) {
			float x = pos[i][0], y = pos[i][1], z = pos[i][2];
			float score = switch (face) {
				case "up" -> -x - z;
				case "down" -> -x + z;
				case "north" -> x + y;
				case "south" -> -x + y;
				case "west" -> -z + y;
				default -> z + y; // east
			};
			if (opposite) score = -score;
			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}
		return best;
	}

	private static JsonArray vec(float x, float y, float z) {
		JsonArray a = new JsonArray();
		a.add(round(x));
		a.add(round(y));
		a.add(round(z));
		return a;
	}

	private static float round(float v) {
		return Math.round(v * 10000F) / 10000F;
	}
}
