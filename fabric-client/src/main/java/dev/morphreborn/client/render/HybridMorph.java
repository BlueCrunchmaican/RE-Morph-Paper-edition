package dev.morphreborn.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.mixin.LivingEntityRendererInvoker;
import dev.morphreborn.client.mixin.ModelPartAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.monster.dragon.EnderDragonModel;
import net.minecraft.client.renderer.entity.EnderDragonRenderer;
import net.minecraft.client.renderer.entity.state.EnderDragonRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import com.mojang.math.Axis;
import dev.morphreborn.client.mixin.EnderDragonRendererAccessor;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PhantomRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Multimorph for players with the mod: the main body, with the borrowed form's head in place of its own and its
 * appendages (wings, tails, fins, blaze and breeze rods) grafted on. Bodies are living mobs or the ender dragon, as main
 * or borrowed form; the dragon's wings and tail are scaled to the body they go on. A creature known by its wings, rods or fins lends
 * only its parts and the main body keeps its own head (see {@link #lendsPartsOnly}). The main body is drawn as usual with its head left
 * out (hidden at draw time, as for the first-person body); the borrowed head's cubes are then drawn on the main head's
 * posed frame, so it turns and nods with it, at the borrowed mob's real size. Mobs without a head part (slimes, ghasts,
 * squid, fish, bees) lend their whole body, standing on the neck, and nothing else.
 * <p>
 * An appendage keeps its real size and its own animation (a bat's wings flap), and goes to the same place on the main
 * body's torso as it has on the borrowed one's, measured in the body's own frame: where it joins, as a share of the
 * borrowed torso's box along each axis, is put at that share of the main torso's box, and anything past the box (a
 * blaze's rods) keeps its distance from it. A lying torso (wolf, phantom, bee) maps onto a standing one (zombie) as if
 * it stood up: its front to the top, its rear to the bottom, its top to the back, so a wolf's tail joins a zombie at
 * the small of its back. Wings always go on the back (on top, for a lying body), near the middle, and wings and tails
 * stand just clear of the body, so they don't cut through it or the arms beside it.
 * <p>
 * The cubes are posed and captured when the body is submitted and drawn as custom geometry, because the models are
 * shared with real mobs of the same kind, which re-pose them before the frame is drawn.
 */
public final class HybridMorph {
	/** Parts drawn as part of the face when they aren't inside the head part (a chicken's beak and wattle). */
	private static final Set<String> FACE_PARTS = Set.of("hat", "beak", "red_thing", "nose", "jaw", "mouth", "upper_mouth", "lower_mouth", "snout",
			"left_ear", "right_ear", "left_horn", "right_horn", "eyes", "left_antenna", "right_antenna");

	/** Carried on a render state from extraction to drawing: the borrowed form's state, and whether to leave out the head. */
	public interface Mark {
		@Nullable EntityRenderState morph$borrowed();

		void morph$setBorrowed(@Nullable EntityRenderState borrowed);

		boolean morph$hideHead();

		void morph$setHideHead(boolean hide);
	}

	private record Cube(Matrix4f pose, Matrix3f normal, ModelPart.Cube cube) {}

	/**
	 * A phantom's own flap is slow (a beat every 2.4 seconds) and reads as a stutter on a body that isn't gliding;
	 * borrowed phantom wings beat this many times faster.
	 */
	private static final float PHANTOM_FLAP_SPEED = 4.5F;
	/**
	 * A dragon that isn't flying anywhere beats its wings about four times a second (EnderDragon.aiStep), which on wings
	 * scaled down to a body looked frantic; a borrowed dragon's wings and tail (both timed by flapTime) go at this share.
	 */
	private static final float DRAGON_FLAP_SPEED = 0.4F;

	private HybridMorph() {}

	/** Per model: does a borrowed form with this model lend only its parts? (see {@link #lendsPartsOnly}) */
	private static final Map<ModelPart, Boolean> PARTS_ONLY = new java.util.WeakHashMap<>();

	/** Extraction: {@code main} is drawn with {@code borrowed}'s head and parts, or only its parts, on it. */
	public static void attach(EntityRenderDispatcher dispatcher, EntityRenderState main, EntityRenderState borrowed) {
		if (rootOf(dispatcher, main) == null) return;
		ModelPart lent = rootOf(dispatcher, borrowed);
		if (lent == null) return;
		if (borrowed instanceof PhantomRenderState phantom) phantom.flapTime *= PHANTOM_FLAP_SPEED; // extracted afresh every frame
		if (borrowed instanceof EnderDragonRenderState dragon) dragon.flapTime *= DRAGON_FLAP_SPEED;
		// a baby body borrows a baby's head and parts (the borrowed creature's baby model, where it has one)
		if (main instanceof LivingEntityRenderState m && m.isBaby && borrowed instanceof LivingEntityRenderState b && !b.isBaby) {
			b.isBaby = true;
			b.ageScale = m.ageScale;
		}
		// its head at rest: the body's look turns the borrowed head (place), once. Posed with the player's look of its
		// own as well, it turned and nodded twice as far, and the paper doll's level head modes left it looking up.
		if (borrowed instanceof LivingEntityRenderState b) {
			b.yRot = 0F;
			b.xRot = 0F;
		}
		((Mark) main).morph$setBorrowed(borrowed);
		// a pufferfish is its puffed body, not its fins (too small to see): it lends itself whole
		boolean partsOnly = borrowed.entityType != net.minecraft.world.entity.EntityTypes.PUFFERFISH && PARTS_ONLY.computeIfAbsent(lent, HybridMorph::hasDefiningParts);
		((Mark) main).morph$setHideHead(!partsOnly);
	}

	/**
	 * The model a body is drawn with, for the bodies this can draw: living mobs, and the ender dragon (its renderer is not
	 * a living-entity one); null for anything else.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static @Nullable ModelPart rootOf(EntityRenderDispatcher dispatcher, EntityRenderState state) {
		EntityRenderer<?, ?> renderer = dispatcher.getRenderer(state);
		if (renderer instanceof LivingEntityRenderer living && state instanceof LivingEntityRenderState s) {
			ModelCapture.selectModel(living, s);
			return living.getModel().root();
		}
		if (renderer instanceof EnderDragonRenderer dragon && state instanceof EnderDragonRenderState) return ((EnderDragonRendererAccessor) dragon).morph$model().root();
		return null;
	}

	/**
	 * A creature known by its wings, rods or fins (bat, phantom, parrot, bee, chicken, allay, vex, blaze, breeze, fish,
	 * dolphin, the ender dragon) lends only those, with its tail and stinger: a zombie with a bat's wings keeps its own
	 * head, which looks like a creature that could exist. Everything else lends its head (and any tail).
	 */
	private static boolean hasDefiningParts(ModelPart part) {
		for (Map.Entry<String, ModelPart> child : ((ModelPartAccessor) (Object) part).morph$children().entrySet()) {
			String name = child.getKey();
			if (name.contains("wing") || name.contains("fin") || name.equals("rods") || name.matches("part\\d+")) return true;
			if (hasDefiningParts(child.getValue())) return true;
		}
		return false;
	}

	/**
	 * A body ready to measure: its model posed for the state, the renderer's transforms up to the model ({@code base}),
	 * the turn that undoes the body's facing ({@code unturn}), and its texture.
	 */
	private record Body(ModelPart root, PoseStack base, Matrix4f unturn, RenderType type) {}

	private static final Identifier DRAGON_TEXTURE = Identifier.withDefaultNamespace("textures/entity/enderdragon/dragon.png");

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static @Nullable Body body(EntityRenderDispatcher dispatcher, EntityRenderState state) {
		EntityRenderer<?, ?> renderer = dispatcher.getRenderer(state);
		if (renderer instanceof LivingEntityRenderer living && state instanceof LivingEntityRenderState s) {
			PoseStack base = posed(living, s);
			EntityModel model = (EntityModel) living.getModel();
			return new Body(model.root(), base, new Matrix4f().rotationY((float) Math.toRadians(s.bodyRot - 180F)),
					model.renderType(living.getTextureLocation(s)));
		}
		if (renderer instanceof EnderDragonRenderer dragon && state instanceof EnderDragonRenderState s) {
			// EnderDragonRenderer.submit's transforms: facing and tilt from its flight history, then the model's axes
			EnderDragonModel model = ((EnderDragonRendererAccessor) dragon).morph$model();
			float yaw = s.getHistoricalPos(7).yRot();
			float tilt = (float) (s.getHistoricalPos(5).y() - s.getHistoricalPos(10).y());
			PoseStack base = new PoseStack();
			base.rotateDegrees(Axis.YP, -yaw);
			base.rotateDegrees(Axis.XP, tilt * 10F);
			base.translate(0F, 0F, 1F);
			base.scale(-1F, -1F, 1F);
			base.translate(0F, -1.501F, 0F);
			model.setupAnim(s);
			return new Body(model.root(), base, new Matrix4f().rotationY((float) Math.toRadians(yaw)), model.renderType(DRAGON_TEXTURE));
		}
		return null;
	}

	/** The borrowed head and parts, posed and placed on the body: what is drawn, and how it is textured. */
	private record Placed(List<Cube> cubes, RenderType type) {}

	/** After {@code state} was submitted: the borrowed head and parts, if it carries a borrowed form. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static void submit(EntityRenderDispatcher dispatcher, EntityRenderState state, double x, double y, double z, PoseStack poseStack,
			SubmitNodeCollector collector) {
		// First-person Model's body view: the borrowed parts are your body too (wings at your shoulders, a tail behind),
		// but not a borrowed head, which would sit on the camera
		boolean firstPerson = ((FirstPersonMorph.Mark) state).morph$firstPersonBody();
		Placed placed = place(dispatcher, state, !firstPerson);
		if (placed == null) return;
		Vec3 offset = ((EntityRenderer) dispatcher.getRenderer(state)).getRenderOffset(state);
		int light = state.lightCoords;
		int overlay = state instanceof LivingEntityRenderState living ? LivingEntityRenderer.getOverlayCoords(living, 0F)
				: state instanceof EnderDragonRenderState dragon ? OverlayTexture.pack(0F, dragon.hasRedOverlay) : OverlayTexture.NO_OVERLAY;
		poseStack.pushPose();
		poseStack.translate(x + offset.x(), y + offset.y(), z + offset.z());
		collector.submitCustomGeometry(poseStack, placed.type(), (pose, buffer) -> {
			PoseStack scratch = new PoseStack();
			PoseStack.Pose last = scratch.last();
			for (Cube c : placed.cubes()) {
				last.pose().set(pose.pose()).mul(c.pose());
				last.normal().set(pose.normal()).mul(c.normal());
				c.cube().compile(last, buffer, light, overlay, -1);
			}
		});
		poseStack.popPose();
	}

	/** The borrowed head and parts as boxes, for a transformation's capture (ModelCapture.captureHybrid). */
	public static List<ModelCapture.Box> boxes(EntityRenderDispatcher dispatcher, EntityRenderState state) {
		Placed placed = place(dispatcher, state, true);
		if (placed == null) return List.of();
		List<ModelCapture.Box> out = new ArrayList<>(placed.cubes().size());
		for (Cube c : placed.cubes()) out.add(ModelCapture.box(c.pose(), c.normal(), c.cube()));
		return out;
	}

	private static @Nullable Placed place(EntityRenderDispatcher dispatcher, EntityRenderState state, boolean withHead) {
		if (!(state instanceof Mark mark)) return null;
		EntityRenderState borrowed = mark.morph$borrowed();
		if (borrowed == null) return null;
		boolean headSwap = mark.morph$hideHead(); // otherwise the borrowed form lends only its wings, rods or fins (and tail)

		// The main body first: the borrowed form may be the same kind of mob, posing the same model.
		// Torsos are measured in the body's own frame (its facing undone), where -z is its front and +y is up.
		Body main = body(dispatcher, state);
		if (main == null) return null;
		// the main head's frame and its own cubes (hidden at draw time, still posed): where a borrowed head goes
		List<Cube> mainHead = new ArrayList<>();
		Matrix4f neck = !headSwap ? null : headPath(main.root()) != null ? headCubes(main.root(), main.base(), mainHead) : neckFrame(main.root(), main.base());
		float[][] mainTorso = torso(main.root(), local(main.base(), main.unturn()));
		Body lent = body(dispatcher, borrowed);
		if (lent == null) return null;
		List<Cube> placed = new ArrayList<>();
		if (headSwap && withHead) {
			List<Cube> cubes = new ArrayList<>();
			Matrix4f own = headCubes(lent.root(), lent.base(), cubes);
			if (neck != null && own != null && !cubes.isEmpty()) {
				// The borrowed head as it sits on its own body (an armadillo's head hangs off a sloping body; mapping its
				// part's frame onto the main head's stood it on end), moved so the place it meets its neck (the bottom of
				// the head, at the back) is on the main head's, and turned and nodded as the main head is. Each body's own
				// frame (its facing, model axes, no size) is where these are measured, so the head keeps its real size.
				boolean wholeBody = headPath(lent.root()) == null;
				Matrix4f bodyB = rigid(lent.base().last().pose());
				Matrix4f bodyM = rigid(main.base().last().pose());
				Vector3f ownNeck = neckPoint(cubes, bodyB, !wholeBody);
				Vector3f mainNeck = mainHead.isEmpty() ? topOf(main) : outOfTorso(neckPoint(mainHead, bodyM, !wholeBody), main, bodyM);
				Matrix4f look = new Matrix4f();
				if (state instanceof LivingEntityRenderState living) {
					look.rotationY((float) Math.toRadians(living.yRot)).rotateX((float) Math.toRadians(living.xRot));
				}
				// on a dragon, a head is scaled to the dragon's (as a borrowed dragon's parts are to the body): at its own
				// size a cow's head was a speck on the end of the neck
				float grow = 1F;
				if (state instanceof EnderDragonRenderState && !mainHead.isEmpty()) {
					float ownSize = headSize(cubes, bodyB);
					if (ownSize > 0.01F) grow = Math.max(1F, headSize(mainHead, bodyM) / ownSize);
				}
				Matrix4f place = new Matrix4f(bodyM).translate(mainNeck).mul(look).scale(grow).translate(-ownNeck.x, -ownNeck.y, -ownNeck.z)
						.mul(bodyB.invert(new Matrix4f()));
				Matrix3f turn = place.get3x3(new Matrix3f()).scale(1F / grow);
				for (Cube c : cubes) placed.add(new Cube(new Matrix4f(place).mul(c.pose()), new Matrix3f(turn).mul(c.normal()), c.cube()));
			}
		}
		// the parts; a whole body lent as a head (a slime) has none to give
		if (mainTorso != null && (!headSwap || headPath(lent.root()) != null)) {
			float[][] ownTorso = torso(lent.root(), local(lent.base(), lent.unturn()));
			if (ownTorso != null) {
				// the dragon's parts are scaled to the body they go on (the user's choice); everyone else's keep their size
				float scale = borrowed instanceof EnderDragonRenderState ? Graft.fit(ownTorso, mainTorso) : 1F;
				Graft graft = new Graft(ownTorso, mainTorso, lent.unturn(), main.unturn(), scale, everything(lent), everything(main));
				findAppendages(lent.root(), copy(lent.base()), graft, placed);
			}
		}
		return placed.isEmpty() ? null : new Placed(placed, lent.type());
	}

	/** The renderer's own transforms up to the model, as LivingEntityRenderer.submit applies them, with the model posed for {@code state}. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static PoseStack posed(LivingEntityRenderer renderer, LivingEntityRenderState state) {
		ModelCapture.selectModel(renderer, state);
		PoseStack ps = new PoseStack();
		ps.scale(state.scale, state.scale, state.scale);
		((LivingEntityRendererInvoker) renderer).morph$setupRotations(state, ps, state.bodyRot, state.scale);
		ps.scale(-1F, -1F, 1F);
		((LivingEntityRendererInvoker) renderer).morph$scale(state, ps);
		ps.translate(0F, -1.501F, 0F);
		((EntityModel) renderer.getModel()).setupAnim(state);
		return ps;
	}

	/** {@code base} seen in the body's own frame. */
	private static PoseStack local(PoseStack base, Matrix4f unturn) {
		PoseStack to = new PoseStack();
		to.last().pose().set(unturn).mul(base.last().pose());
		return to;
	}

	private static PoseStack copy(PoseStack from) {
		PoseStack to = new PoseStack();
		to.last().pose().set(from.last().pose());
		to.last().normal().set(from.last().normal());
		return to;
	}

	/** Where the borrowed head goes: the main head part's posed frame (rigid), or the top of a body without one. */
	private static @Nullable Matrix4f neckFrame(ModelPart root, PoseStack base) {
		PoseStack ps = copy(base);
		List<ModelPart> path = headPath(root);
		if (path != null) {
			for (ModelPart part : path) part.translateAndRotate(ps);
			return rigid(ps.last().pose());
		}
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		bounds(root, ps, lo, hi);
		if (lo[0] > hi[0]) return null;
		return new Matrix4f().translation((lo[0] + hi[0]) / 2F, hi[1], (lo[2] + hi[2]) / 2F).mul(turn(base));
	}

	/**
	 * The borrowed head's cubes, posed as its model is drawn, into {@code out}; returns the frame they hang from: the head
	 * part's (rigid), or for a mob without one the bottom middle of its whole body.
	 */
	private static @Nullable Matrix4f headCubes(ModelPart root, PoseStack base, List<Cube> out) {
		List<ModelPart> path = headPath(root);
		if (path == null) {
			float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
			float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
			PoseStack measure = new PoseStack();
			measure.last().pose().set(base.last().pose());
			bounds(root, measure, lo, hi);
			if (lo[0] > hi[0]) return null;
			PoseStack walk = new PoseStack();
			walk.last().pose().set(base.last().pose());
			walk.last().normal().set(base.last().normal());
			collect(root, walk, out);
			return new Matrix4f().translation((lo[0] + hi[0]) / 2F, lo[1], (lo[2] + hi[2]) / 2F).mul(turn(base));
		}
		PoseStack frame = new PoseStack();
		frame.last().pose().set(base.last().pose());
		for (ModelPart part : path) part.translateAndRotate(frame);
		// the head part with everything in it, and face parts beside it
		List<ModelPart> parents = path.subList(0, path.size() - 1);
		List<ModelPart> group = new ArrayList<>(List.of(path.getLast()));
		for (Map.Entry<String, ModelPart> sibling : ((ModelPartAccessor) (Object) parents.getLast()).morph$children().entrySet()) {
			if (FACE_PARTS.contains(sibling.getKey())) group.add(sibling.getValue());
		}
		for (ModelPart part : group) {
			PoseStack walk = new PoseStack();
			walk.last().pose().set(base.last().pose());
			walk.last().normal().set(base.last().normal());
			for (ModelPart parent : parents) parent.translateAndRotate(walk);
			collect(part, walk, out);
		}
		return rigid(frame.last().pose());
	}

	/** A body's whole box {lo, hi} in its own frame. */
	private static float[][] everything(Body body) {
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		bounds(body.root(), local(body.base(), body.unturn()), lo, hi);
		return lo[0] > hi[0] ? new float[][] {{0, 0, 0}, {0, 0, 0}} : new float[][] {lo, hi};
	}

	private static float volume(ModelPart.Cube cube) {
		return (cube.maxX - cube.minX) * (cube.maxY - cube.minY) * (cube.maxZ - cube.minZ);
	}

	/** The middle of the top of a body without a head part (a slime), in its frame: where a borrowed head sits on it. */
	private static Vector3f topOf(Body body) {
		List<Cube> all = new ArrayList<>();
		collect(body.root(), copy(body.base()), all);
		Matrix4f inverse = rigid(body.base().last().pose()).invert();
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (Cube c : all) {
			Matrix4f toLocal = new Matrix4f(inverse).mul(c.pose());
			for (ModelPart.Polygon polygon : c.cube().polygons) {
				for (ModelPart.Vertex v : polygon.vertices()) {
					Vector3f p = toLocal.transformPosition(new Vector3f(v.x() / 16F, v.y() / 16F, v.z() / 16F));
					lo[0] = Math.min(lo[0], p.x); lo[1] = Math.min(lo[1], p.y); lo[2] = Math.min(lo[2], p.z);
					hi[0] = Math.max(hi[0], p.x); hi[1] = Math.max(hi[1], p.y); hi[2] = Math.max(hi[2], p.z);
				}
			}
		}
		if (lo[0] > hi[0]) return new Vector3f();
		return new Vector3f((lo[0] + hi[0]) / 2F, lo[1], (lo[2] + hi[2]) / 2F); // model axes: up is -y
	}

	/**
	 * Where a head meets its neck, in its own frame (model axes: +y down, -z forward): the middle of the bottom of its
	 * cubes, at the back ({@code back}) or halfway.
	 */
	private static Vector3f neckPoint(List<Cube> cubes, Matrix4f frame, boolean back) {
		float[][] box = coreBox(cubes, frame);
		if (box == null) return new Vector3f();
		float[] lo = box[0], hi = box[1];
		return new Vector3f((lo[0] + hi[0]) / 2F, hi[1], back ? hi[2] : (lo[2] + hi[2]) / 2F);
	}

	/**
	 * A neck point ({@code bodyM}'s frame, model axes: +y down, -z forward) moved out of the body's torso, up or forward,
	 * whichever is nearer: a camel's and a llama's head parts hold their long necks, whose bottom is deep in the chest,
	 * and a panda's head set there sank into the camel (the user saw it).
	 */
	private static Vector3f outOfTorso(Vector3f neck, Body main, Matrix4f bodyM) {
		PoseStack inBody = new PoseStack();
		inBody.last().pose().set(bodyM).invert().mul(main.base().last().pose());
		float[][] torso = torso(main.root(), inBody);
		if (torso == null) return neck;
		float[] lo = torso[0], hi = torso[1];
		float inside = 0.01F;
		if (neck.x <= lo[0] + inside || neck.x >= hi[0] - inside || neck.y <= lo[1] + inside || neck.y >= hi[1] - inside
				|| neck.z <= lo[2] + inside || neck.z >= hi[2] - inside) return neck;
		float up = neck.y - lo[1], forward = neck.z - lo[2];
		return up <= forward ? new Vector3f(neck.x, lo[1], neck.z) : new Vector3f(neck.x, neck.y, lo[2]);
	}

	/** A head's size: the cube root of its core box's volume. */
	private static float headSize(List<Cube> cubes, Matrix4f frame) {
		float[][] box = coreBox(cubes, frame);
		return box == null ? 0F : (float) Math.cbrt((box[1][0] - box[0][0]) * (box[1][1] - box[0][1]) * (box[1][2] - box[0][2]));
	}

	/** The box {lo, hi} of a head's big blocks in {@code frame}, or null for none. */
	private static float @Nullable [][] coreBox(List<Cube> cubes, Matrix4f frame) {
		Matrix4f inverse = new Matrix4f(frame).invert();
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		// only the head's big blocks: a guardian's spikes, a goat's beard and a ghast's tentacles hang below the head
		// and would leave it floating over the neck
		float biggest = 0;
		for (Cube c : cubes) biggest = Math.max(biggest, volume(c.cube()));
		for (Cube c : cubes) {
			if (volume(c.cube()) < biggest * 0.25F) continue;
			Matrix4f toLocal = new Matrix4f(inverse).mul(c.pose());
			for (ModelPart.Polygon polygon : c.cube().polygons) {
				for (ModelPart.Vertex v : polygon.vertices()) {
					Vector3f p = toLocal.transformPosition(new Vector3f(v.x() / 16F, v.y() / 16F, v.z() / 16F));
					lo[0] = Math.min(lo[0], p.x); lo[1] = Math.min(lo[1], p.y); lo[2] = Math.min(lo[2], p.z);
					hi[0] = Math.max(hi[0], p.x); hi[1] = Math.max(hi[1], p.y); hi[2] = Math.max(hi[2], p.z);
				}
			}
		}
		return lo[0] > hi[0] ? null : new float[][] {lo, hi};
	}

	private static boolean headName(String name) {
		return name.contains("head") || FACE_PARTS.contains(name);
	}

	/** Wings, tails, fins, a blaze's rods ("part0".."part11"), a breeze's rods, a bee's stinger. */
	private static boolean appendageName(String name) {
		return name.contains("wing") || name.contains("tail") || name.contains("fin") || name.equals("rods") || name.equals("stinger")
				|| name.matches("part\\d+");
	}

	/**
	 * The torso's box {lo, hi}: the part called "body" (its own cubes), or failing that everything but the head, legs,
	 * arms and appendages, or failing that the same with the head, or failing that the whole model.
	 */
	private static float @Nullable [][] torso(ModelPart root, PoseStack base) {
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		List<ModelPart> body = find(root, "body", new ArrayList<>());
		if (body != null) {
			PoseStack ps = copy(base);
			for (ModelPart part : body) part.translateAndRotate(ps);
			cubeBounds(body.getLast(), ps, lo, hi);
		}
		if (lo[0] > hi[0]) torsoBounds(root, copy(base), lo, hi, false);
		if (lo[0] > hi[0]) torsoBounds(root, copy(base), lo, hi, true); // a blaze: all head, and rods around it
		if (lo[0] > hi[0]) bounds(root, copy(base), lo, hi);
		return lo[0] > hi[0] ? null : new float[][] {lo, hi};
	}

	private static void torsoBounds(ModelPart part, PoseStack pose, float[] lo, float[] hi, boolean withHead) {
		if (!part.visible) return;
		pose.pushPose();
		part.translateAndRotate(pose);
		cubeBounds(part, pose, lo, hi);
		for (Map.Entry<String, ModelPart> child : ((ModelPartAccessor) (Object) part).morph$children().entrySet()) {
			String name = child.getKey();
			if ((!withHead && headName(name)) || appendageName(name) || name.contains("leg") || name.contains("arm")) continue;
			torsoBounds(child.getValue(), pose, lo, hi, withHead);
		}
		pose.popPose();
	}

	/** One part's own cubes (not its children), with {@code pose} already at that part. */
	private static void cubeBounds(ModelPart part, PoseStack pose, float[] lo, float[] hi) {
		if (part.skipDraw) return;
		for (ModelPart.Cube cube : ((ModelPartAccessor) (Object) part).morph$cubes()) {
			for (ModelPart.Polygon polygon : cube.polygons) {
				for (ModelPart.Vertex v : polygon.vertices()) {
					Vector3f w = pose.last().pose().transformPosition(new Vector3f(v.x() / 16F, v.y() / 16F, v.z() / 16F));
					lo[0] = Math.min(lo[0], w.x); lo[1] = Math.min(lo[1], w.y); lo[2] = Math.min(lo[2], w.z);
					hi[0] = Math.max(hi[0], w.x); hi[1] = Math.max(hi[1], w.y); hi[2] = Math.max(hi[2], w.z);
				}
			}
		}
	}

	/**
	 * Where appendages go: from the borrowed torso's box to the main torso's, each in its own body's frame
	 * ({@code ownUnturn}, {@code mainUnturn}), at {@code scale} (1 but for the dragon). A standing torso is at least as
	 * tall as it is long.
	 */
	private record Graft(float[][] own, float[][] main, Matrix4f ownUnturn, Matrix4f mainUnturn, float scale, float[][] ownAll, float[][] mainAll) {
		/** Blocks wings and tails stand clear of the body. */
		private static final float CLEAR = 0.04F;

		static boolean stands(float[][] box) {
			return box[1][1] - box[0][1] >= box[1][2] - box[0][2];
		}

		/** Each main axis's borrowed axis: a lying torso on a standing one swaps height and length. */
		static int[] axes(float[][] own, float[][] main) {
			return stands(own) == stands(main) ? new int[] {0, 1, 2} : new int[] {0, 2, 1};
		}

		/** How much to scale the borrowed parts for the main torso: the cube root of the boxes' volumes' ratio. */
		static float fit(float[][] own, float[][] main) {
			int[] from = axes(own, main);
			double ratio = 1;
			for (int axis = 0; axis < 3; axis++) {
				ratio *= Math.max(0.01F, main[1][axis] - main[0][axis]) / Math.max(0.01F, own[1][from[axis]] - own[0][from[axis]]);
			}
			return (float) Math.cbrt(ratio);
		}

		/** Where an appendage joining the borrowed body at {@code joint} (own frame) joins the main body (main frame). */
		Vector3f place(Vector3f joint, String name) {
			int[] from = axes(own, main);
			boolean mainStands = stands(main);
			boolean[] flip = new boolean[3];
			if (from[1] == 2) {
				if (mainStands) flip[1] = true; // lying -> standing: front up, top to the back
				else flip[2] = true; // standing -> lying: top forward, back up
			}
			Vector3f at = new Vector3f();
			for (int axis = 0; axis < 3; axis++) {
				int a = from[axis];
				float lo = own[0][a], hi = own[1][a], j = joint.get(a);
				float mlo = main[0][axis], mhi = main[1][axis];
				float t;
				if (hi - lo < 0.01F) {
					float off = (j - (lo + hi) / 2F) * scale;
					t = (mlo + mhi) / 2F + (flip[axis] ? -off : off);
				} else if (j < lo) {
					t = flip[axis] ? mhi + (lo - j) * scale : mlo - (lo - j) * scale;
				} else if (j > hi) {
					t = flip[axis] ? mlo - (j - hi) * scale : mhi + (j - hi) * scale;
				} else {
					float share = (j - lo) / (hi - lo);
					t = mlo + (flip[axis] ? 1F - share : share) * (mhi - mlo);
				}
				at.setComponent(axis, t);
			}
			if (name.equals("rods") || name.matches("part\\d+")) {
				// a blaze's or breeze's rods: from head to feet as on their own body, over the main body's whole height
				// (measured from the torso they reached past the feet into the ground)
				float ownHeight = ownAll[1][1] - ownAll[0][1];
				float share = ownHeight < 0.01F ? 0.5F : Math.clamp((joint.y - ownAll[0][1]) / ownHeight, 0F, 1F);
				at.y = mainAll[0][1] + share * (mainAll[1][1] - mainAll[0][1]);
			} else if (name.contains("wing")) {
				float w = main[1][0] - main[0][0], h = main[1][1] - main[0][1];
				if (mainStands) {
					// on the back, near the middle, at the shoulder blades
					at.x = Math.clamp(at.x, main[0][0] + 0.2F * w, main[1][0] - 0.2F * w);
					at.z = Math.max(at.z, main[1][2] + CLEAR);
					at.y = Math.max(at.y, main[0][1] + 0.92F * h);
				} else {
					// on the flanks, high up, the side the joint is on (on top, a chicken's or bat's wings hid in the back)
					boolean left = at.x < (main[0][0] + main[1][0]) / 2F;
					at.x = left ? Math.min(at.x, main[0][0] - CLEAR) : Math.max(at.x, main[1][0] + CLEAR);
					at.y = Math.max(at.y, main[0][1] + 0.67F * h);
				}
			} else if (name.contains("tail")) {
				at.z = Math.max(at.z, main[1][2] + CLEAR); // behind: the back of a standing body, the rear of a lying one
			}
			return at;
		}

		/** The borrowed body's facing swapped for the main body's. */
		Matrix4f align() {
			return new Matrix4f(mainUnturn).invert().mul(ownUnturn);
		}

		/**
		 * The move for an appendage that joins at {@code joint} (posed, as drawn): its joint to where it goes on the main
		 * body, facing as the main body faces, scaled about the joint.
		 */
		Matrix4f move(Vector3f joint, String name) {
			Vector3f target = new Matrix4f(mainUnturn).invert().transformPosition(place(ownUnturn.transformPosition(new Vector3f(joint)), name));
			return new Matrix4f().translation(target).mul(align()).scale(scale).translate(-joint.x, -joint.y, -joint.z);
		}
	}

	/**
	 * The borrowed form's appendages, moved onto the main torso by {@link Graft#move}. A tail made of numbered segments
	 * side by side (the dragon's tail0..tail11) moves as one piece, from its first segment.
	 */
	private static void findAppendages(ModelPart part, PoseStack pose, Graft graft, List<Cube> out) {
		if (!part.visible) return;
		pose.pushPose();
		part.translateAndRotate(pose);
		Map<String, ModelPart> children = ((ModelPartAccessor) (Object) part).morph$children();
		Matrix3f turn = graft.align().get3x3(new Matrix3f());
		Matrix4f chain = null;
		for (Map.Entry<String, ModelPart> child : children.entrySet()) {
			String name = child.getKey();
			ModelPart limb = child.getValue();
			if (headName(name) || !limb.visible) continue;
			if (!appendageName(name)) {
				findAppendages(limb, pose, graft, out);
				continue;
			}
			boolean segment = name.matches("tail\\d+");
			Matrix4f move = segment ? chain : null;
			if (move == null) {
				// where it joins: its pivot (a segmented tail's: its first segment's)
				PoseStack at = copy(pose);
				(segment ? firstSegment(children) : limb).translateAndRotate(at);
				move = graft.move(at.last().pose().transformPosition(new Vector3f()), name);
				if (segment) chain = move;
			}
			List<Cube> cubes = new ArrayList<>();
			collect(limb, copy(pose), cubes);
			for (Cube c : cubes) out.add(new Cube(new Matrix4f(move).mul(c.pose()), new Matrix3f(turn).mul(c.normal()), c.cube()));
		}
		pose.popPose();
	}

	/** The lowest-numbered of the "tailN" parts. */
	private static ModelPart firstSegment(Map<String, ModelPart> children) {
		ModelPart first = null;
		int lowest = Integer.MAX_VALUE;
		for (Map.Entry<String, ModelPart> child : children.entrySet()) {
			String name = child.getKey();
			if (!name.matches("tail\\d+")) continue;
			int n = Integer.parseInt(name.substring(4));
			if (n < lowest) {
				lowest = n;
				first = child.getValue();
			}
		}
		return first;
	}

	private static @Nullable List<ModelPart> headPath(ModelPart root) {
		List<ModelPart> path = find(root, "head", new ArrayList<>());
		return path != null ? path : find(root, "center_head", new ArrayList<>());
	}

	private static @Nullable List<ModelPart> find(ModelPart part, String name, List<ModelPart> path) {
		path.add(part);
		Map<String, ModelPart> children = ((ModelPartAccessor) (Object) part).morph$children();
		ModelPart direct = children.get(name);
		if (direct != null) {
			path.add(direct);
			return path;
		}
		for (ModelPart child : children.values()) {
			List<ModelPart> found = find(child, name, new ArrayList<>(path));
			if (found != null) return found;
		}
		return null;
	}

	private static void collect(ModelPart part, PoseStack pose, List<Cube> out) {
		if (!part.visible) return;
		ModelPartAccessor access = (ModelPartAccessor) (Object) part;
		pose.pushPose();
		part.translateAndRotate(pose);
		if (!part.skipDraw) {
			for (ModelPart.Cube cube : access.morph$cubes()) out.add(new Cube(new Matrix4f(pose.last().pose()), new Matrix3f(pose.last().normal()), cube));
		}
		for (ModelPart child : access.morph$children().values()) collect(child, pose, out);
		pose.popPose();
	}

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
	 * The body's turn (and the model's upside-down axes) without its place or size: frames without a head part carry
	 * it too, so they line up with head frames, which do.
	 */
	private static Matrix4f turn(PoseStack base) {
		return rigid(base.last().pose()).setTranslation(0F, 0F, 0F);
	}

	private static Matrix4f rigid(Matrix4f m) {
		Vector3f s = m.getScale(new Vector3f());
		return new Matrix4f(m).scale(1F / s.x, 1F / s.y, 1F / s.z);
	}
}
