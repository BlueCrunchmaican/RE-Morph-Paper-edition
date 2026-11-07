package dev.morphreborn.client.dev;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.morphreborn.client.MorphClient;
import dev.morphreborn.client.mixin.LivingEntityRendererInvoker;
import dev.morphreborn.client.mixin.ModelPartAccessor;
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
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
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

		JsonArray elements = new JsonArray();
		for (List<ModelPart> path : heads) {
			PoseStack pose = new PoseStack();
			pose.last().pose().set(base.last().pose());
			for (int i = 0; i < path.size() - 1; i++) path.get(i).translateAndRotate(pose);
			collect(path.getLast(), pose, pivot, rendererScale * state.scale, elements);
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
		return head;
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
	private static void collect(ModelPart part, PoseStack pose, Vector3f neck, float scale, JsonArray out) {
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
				if (element != null) out.add(element);
			}
		}
		for (ModelPart child : access.morph$children().values()) collect(child, pose, neck, scale, out);
		pose.popPose();
	}

	private static @Nullable JsonObject element(ModelPart.Cube cube, Matrix3f rot, Vector3f t) {
		if (quarterTurns(rot)) return alignedElement(cube, rot, t);
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (ModelPart.Polygon polygon : cube.polygons) {
			for (ModelPart.Vertex v : polygon.vertices()) {
				lo[0] = Math.min(lo[0], v.x()); lo[1] = Math.min(lo[1], v.y()); lo[2] = Math.min(lo[2], v.z());
				hi[0] = Math.max(hi[0], v.x()); hi[1] = Math.max(hi[1], v.y()); hi[2] = Math.max(hi[2], v.z());
			}
		}
		if (lo[0] > hi[0]) return null;
		JsonObject element = new JsonObject();
		element.add("from", vec(t.x + lo[0], t.y + lo[1], t.z + lo[2]));
		element.add("to", vec(t.x + hi[0], t.y + hi[1], t.z + hi[2]));
		Vector3f euler = rot.getEulerAnglesZYX(new Vector3f());
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
			addFace(faces, rot.transform(new Vector3f(polygon.normal())), polygon, pos);
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
