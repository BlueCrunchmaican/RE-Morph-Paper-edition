package dev.morphreborn.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.morphreborn.client.mixin.EnderDragonRendererAccessor;
import dev.morphreborn.client.mixin.LivingEntityRendererInvoker;
import dev.morphreborn.client.mixin.ModelPartAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.monster.dragon.EnderDragonModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EnderDragonRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EnderDragonRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PhantomRenderState;
import net.minecraft.client.renderer.entity.state.ShulkerRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Multimorph for players with the mod: the main body, with the borrowed form's head in place of its own and its
 * appendages (wings, tails, fins, blaze and breeze rods) grafted on. Bodies are living mobs or the ender dragon, as main
 * or borrowed form; the dragon's wings and tail are scaled to the body they go on. A creature known by its wings, rods or fins lends
 * only its parts and the main body keeps its own head (see {@link #lendsPartsOnly}); an animal with a tail and ears standing
 * out of its head lends those (see {@link #EARS_AND_TAIL}). The main body is drawn as usual with its head left
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
 * A shulker body is a box with a head inside: a borrowed head replaces that inner head, shrunk to its size, and pops out
 * when the lid opens (see {@link #inShell}); borrowed ears stay on the inner head, inside the shell; nothing else is
 * drawn on it, since wings and tails would stick out of the box.
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

		/** The borrowed form lends its ears (and tail) instead of its head (see {@link #EARS_AND_TAIL}). */
		boolean morph$lendsEars();

		void morph$setLendsEars(boolean ears);
	}

	/** Package-private for HybridMorphGraftTest. */
	record Cube(Matrix4f pose, Matrix3f normal, ModelPart.Cube cube) {}

	/**
	 * A phantom's own flap is slow (a beat every 2.4 seconds) and reads as a stutter on a body that isn't gliding;
	 * borrowed phantom wings beat this many times faster.
	 */
	public static final float PHANTOM_FLAP_SPEED = 4.5F;
	/**
	 * A dragon that isn't flying anywhere beats its wings about four times a second (EnderDragon.aiStep), which on wings
	 * scaled down to a body looked frantic; a borrowed dragon's wings and tail (both timed by flapTime) go at this share.
	 */
	public static final float DRAGON_FLAP_SPEED = 0.4F;

	private HybridMorph() {}

	/** Blocks borrowed ears reach into the head they go on, on every side (see {@link #placeEars}). */
	private static final float EAR_SET_IN = 0.0625F;
	/**
	 * How far a closed shulker shell reaches from its head's pivot, in model pixels (ShulkerModel: the shut lid and the
	 * base together are the box x and z -8..8, y 8..24, +y down; the head part pivots at y 12, the top middle of its
	 * 6-pixel cube, which the lid lifts around and never carries): 4 up, 12 down and 8 out from the middle, less half a
	 * pixel each, so nothing borrowed inside it shows through the shut lid or flickers in its faces (see
	 * {@link #keepInShell}).
	 */
	private static final float SHELL_UP = 3.5F, SHELL_DOWN = 11.5F, SHELL_OUT = 7.5F;

	/** The fish that roll over out of water (see {@link #attach}); looked up when used, as the entity types need the game's registry. */
	private static boolean rollsOnLand(EntityRenderState state) {
		return state.entityType == net.minecraft.world.entity.EntityTypes.COD || state.entityType == net.minecraft.world.entity.EntityTypes.SALMON
				|| state.entityType == net.minecraft.world.entity.EntityTypes.TROPICAL_FISH;
	}

	/**
	 * Bodies that are a head all through: a guardian's one "head" part holds its cube, spikes, eye and tail, so hiding the head
	 * under a borrowed one hid the whole creature and left the lent head hanging in the air. It keeps its body, and the head
	 * goes on top of it as on any body without a head part.
	 */
	public static boolean allHead(EntityRenderState state) {
		return state.entityType == net.minecraft.world.entity.EntityTypes.GUARDIAN || state.entityType == net.minecraft.world.entity.EntityTypes.ELDER_GUARDIAN;
	}

	/** A squid, a glow squid, a guardian or an elder guardian: the mains whose torso box can be the lying 12 x 12 x 16 block ({@link Graft#lyingBlockBox}). */
	private static boolean lyingBlockMain(EntityRenderState state) {
		return allHead(state) || state.entityType == net.minecraft.world.entity.EntityTypes.SQUID || state.entityType == net.minecraft.world.entity.EntityTypes.GLOW_SQUID;
	}

	/** Per model: does a borrowed form with this model lend only its parts? (see {@link #lendsPartsOnly}) */
	private static final Map<ModelPart, Boolean> PARTS_ONLY = new java.util.WeakHashMap<>();
	/**
	 * Per model: does a borrowed form with this model lend its ears and tail instead of its head? Animals with a tail and
	 * ears that stand out from the head (wolves, foxes, cats, ocelots, rabbits, armadillos, camels, the horse family):
	 * the main body keeps its own head, with the ears on top of it (7.1.3, my call: a fox on a person reads as
	 * fox ears and a tail, not a fox's head).
	 */
	private static final Map<ModelPart, Boolean> EARS_AND_TAIL = new java.util.WeakHashMap<>();

	/**
	 * Extraction: {@code main} is drawn with {@code borrowed}'s head and parts, or only its parts, on it. A baby body is
	 * handed the borrowed creature's baby, where it has one (MorphWorldRenderer.borrow): 26.3's babies have models and
	 * textures of their own, picked as the state is extracted.
	 */
	public static void attach(EntityRenderDispatcher dispatcher, EntityRenderState main, EntityRenderState borrowed) {
		if (rootOf(dispatcher, main) == null) return;
		// What a creature lends (its head, its ears and tail, or its parts) is decided on its grown model, so a baby lends
		// what a grown one does, as the plugin shows it to players without the mod. (Cached on the grown model and looked up
		// on the baby's, the decision was lost: a baby body borrowing a wolf had no ears.)
		LivingEntityRenderState young = borrowed instanceof LivingEntityRenderState b && b.isBaby ? b : null;
		if (young != null) young.isBaby = false;
		ModelPart grown = rootOf(dispatcher, borrowed);
		boolean earsAndTail = grown != null && EARS_AND_TAIL.computeIfAbsent(grown, k -> lendsEarsAndTail(dispatcher, borrowed));
		boolean definingParts = grown != null && PARTS_ONLY.computeIfAbsent(grown, HybridMorph::hasDefiningParts);
		if (young != null) young.isBaby = true;
		if (grown == null || rootOf(dispatcher, borrowed) == null) return;
		if (borrowed instanceof PhantomRenderState phantom) phantom.flapTime *= PHANTOM_FLAP_SPEED; // extracted afresh every frame
		if (borrowed instanceof EnderDragonRenderState dragon) dragon.flapTime *= DRAGON_FLAP_SPEED;
		// its head at rest: the body's look turns the borrowed head (place), once. Posed with the player's look of its
		// own as well, it turned and nodded twice as far, and the paper doll's level head modes left it looking up.
		if (borrowed instanceof LivingEntityRenderState b) {
			b.yRot = 0F;
			b.xRot = 0F;
		}
		// a borrowed shulker is never held in a block (only a main body is): a free shulker's pose. Its head at rest too:
		// its model turns the head by yHeadRot - 180 - yBodyRot, not by yRot (its body is drawn half a turn round), so
		// the player's look turned it a second time, and a head level with its body faced backwards on the body it went
		// on. At 180 the head part sits as it was modelled, facing forward, as every other lent head (and the plugin's
		// head pack, from the model's own pose) has it.
		if (borrowed instanceof ShulkerRenderState shulker) {
			shulker.attachFace = net.minecraft.core.Direction.DOWN;
			shulker.renderOffset = Vec3.ZERO;
			shulker.yHeadRot = shulker.yBodyRot + 180F;
		}
		// a borrowed fish is drawn as it swims: copied from the player on land, isInWater made it lie on its side (CodRenderer,
		// SalmonRenderer, TropicalFishRenderer roll a fish out of water by 90 degrees), so its fins, which are all that comes over,
		// lay flat on every land body: no upright dorsal, a horizontal tail (rig, 2026-11-18)
		if (borrowed instanceof LivingEntityRenderState swimmer && rollsOnLand(borrowed)) swimmer.isInWater = true;
		((Mark) main).morph$setBorrowed(borrowed);
		// a shulker carrying a borrowed head never nods it (vanilla's ShulkerLookControl doesn't), so nothing inside the
		// shell swings out through its sides; held shells are already level (ShulkerLock.pin)
		if (main instanceof net.minecraft.client.renderer.entity.state.ShulkerRenderState shell) shell.xRot = 0F;
		// a pufferfish is its puffed body, not its fins (too small to see): it lends itself whole
		boolean partsOnly = borrowed.entityType != net.minecraft.world.entity.EntityTypes.PUFFERFISH && (earsAndTail || definingParts);
		((Mark) main).morph$setHideHead(!partsOnly);
		((Mark) main).morph$setLendsEars(earsAndTail);
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
	public static boolean hasDefiningParts(ModelPart part) {
		for (Map.Entry<String, ModelPart> child : ((ModelPartAccessor) (Object) part).morph$children().entrySet()) {
			String name = child.getKey();
			if (name.contains("wing") || name.contains("fin") || name.equals("rods") || name.matches("part\\d+")) return true;
			if (hasDefiningParts(child.getValue())) return true;
		}
		return false;
	}

	/** A tail, and ears standing out of the head (see {@link #EARS_AND_TAIL}). */
	private static boolean lendsEarsAndTail(EntityRenderDispatcher dispatcher, EntityRenderState state) {
		Body body = body(dispatcher, state);
		if (body == null || !hasTail(body.root()) || headPath(body.root()) == null) return false;
		List<Cube> head = new ArrayList<>();
		Matrix4f frame = headCubes(body.root(), body.base(), head);
		if (frame == null) return false;
		float[][] core = coreBox(head, frame);
		return core != null && !ears(head, frame, core, namedEars(body.root()), body.texture()).isEmpty();
	}

	/**
	 * For the head dump (dev/HeadDump): the cubes a borrowed form with this model lends as ears, posed by {@code base} and drawn with
	 * {@code texture}; empty when it lends its head instead (no tail, no ears standing out of its head, or ears its texture leaves empty).
	 */
	public static Set<ModelPart.Cube> lentEars(ModelPart root, PoseStack base, @Nullable Identifier texture) {
		Set<ModelPart.Cube> out = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		if (!hasTail(root) || headPath(root) == null) return out;
		List<Cube> head = new ArrayList<>();
		Matrix4f frame = headCubes(root, base, head);
		if (frame == null) return out;
		float[][] core = coreBox(head, frame);
		if (core == null) return out;
		for (Cube c : ears(head, frame, core, namedEars(root), texture)) out.add(c.cube());
		return out;
	}

	/** For the head dump: the torso's box {lo, hi} as borrowed parts are grafted onto it, in {@code base}'s frame. */
	public static float @Nullable [][] torsoBox(ModelPart root, PoseStack base) {
		return torso(root, base);
	}

	/**
	 * The cubes of parts named as ears ("left_ear", "ear", "right_ear_cube"...): a camel's head part holds its neck, so
	 * its ears don't stand out of the head's box.
	 */
	private static Set<ModelPart.Cube> namedEars(ModelPart part) {
		Set<ModelPart.Cube> out = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		addNamedEars(part, false, out);
		return out;
	}

	private static void addNamedEars(ModelPart part, boolean ear, Set<ModelPart.Cube> out) {
		ModelPartAccessor access = (ModelPartAccessor) (Object) part;
		if (ear) out.addAll(access.morph$cubes());
		for (Map.Entry<String, ModelPart> child : access.morph$children().entrySet()) {
			addNamedEars(child.getValue(), ear || child.getKey().matches("(.*_)?ears?(\\d+)?(_.*)?"), out);
		}
	}

	private static boolean hasTail(ModelPart part) {
		for (Map.Entry<String, ModelPart> child : ((ModelPartAccessor) (Object) part).morph$children().entrySet()) {
			String name = child.getKey();
			if (name.contains("tail") && !name.contains("fin")) return true;
			if (hasTail(child.getValue())) return true;
		}
		return false;
	}

	/**
	 * A head's ears: parts named as ears, and the small blocks (a quarter of its biggest or less) sticking out of its core
	 * box at the top or the sides, not the front (a nose) or below (a beard). Measured in the head's frame (model axes:
	 * +y down, -z forward).
	 */
	private static List<Cube> ears(List<Cube> head, Matrix4f frame, float[][] core, Set<ModelPart.Cube> named, @Nullable Identifier texture) {
		Matrix4f inverse = new Matrix4f(frame).invert();
		float biggest = 0;
		for (Cube c : head) biggest = Math.max(biggest, volume(c.cube()));
		List<Cube> out = new ArrayList<>();
		for (Cube c : head) {
			if (named.contains(c.cube())) {
				out.add(c);
				continue;
			}
			if (volume(c.cube()) >= biggest * 0.25F) continue;
			float[][] box = cubeBox(c, inverse);
			float x = (box[0][0] + box[1][0]) / 2F, y = (box[0][1] + box[1][1]) / 2F, z = (box[0][2] + box[1][2]) / 2F;
			boolean above = y < core[0][1];
			boolean beside = x < core[0][0] || x > core[1][0];
			boolean front = z < core[0][2];
			boolean below = y > core[1][1];
			if ((above || beside) && !front && !below) out.add(c);
		}
		// ears the texture leaves empty draw nothing (a skeleton horse's, whose ear and tail texels are all transparent): that is no
		// ear to lend, so the creature lends its head as any other (rig, 2026-11-18: 55 pairs came over with nothing)
		if (texture != null) {
			for (int i = out.size() - 1; i >= 0; i--) {
				if (seeThrough(out.get(i).cube(), texture)) out.remove(i);
			}
		}
		return out;
	}

	/**
	 * Per cube and texture: whether the texture is (all but) transparent where the cube reads it. Kept as the verdict only, not the
	 * image: a reload of the resources (F3+T, a new pack) rebuilds the models, so these cubes, and with them the verdicts, are gone
	 * and the next look reads the new texture.
	 */
	private static final Map<ModelPart.Cube, Map<Identifier, Boolean>> SEE_THROUGH = new java.util.WeakHashMap<>();

	/** Under this share of its texels opaque, a cube is not there. */
	private static final float OPAQUE_MIN = 0.05F;

	private static boolean seeThrough(ModelPart.Cube cube, Identifier texture) {
		Map<Identifier, Boolean> verdicts = SEE_THROUGH.get(cube);
		if (verdicts == null) {
			verdicts = new java.util.HashMap<>();
			SEE_THROUGH.put(cube, verdicts);
		}
		Boolean known = verdicts.get(texture);
		if (known == null) {
			known = readSeeThrough(cube, texture);
			verdicts.put(texture, known);
		}
		return known;
	}

	/** Reads the texture once (and lets it go again), for the one cube: nothing is taken for transparent that cannot be read. */
	private static boolean readSeeThrough(ModelPart.Cube cube, Identifier texture) {
		java.util.Optional<net.minecraft.server.packs.resources.Resource> resource = net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(texture);
		if (resource.isEmpty()) return false;
		try (java.io.InputStream in = resource.get().open(); com.mojang.blaze3d.platform.NativeImage image = com.mojang.blaze3d.platform.NativeImage.read(in)) {
			int seen = 0, opaque = 0;
			for (ModelPart.Polygon polygon : cube.polygons) {
				float u0 = Float.MAX_VALUE, v0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
				for (ModelPart.Vertex v : polygon.vertices()) {
					u0 = Math.min(u0, v.u()); u1 = Math.max(u1, v.u());
					v0 = Math.min(v0, v.v()); v1 = Math.max(v1, v.v());
				}
				int x0 = Math.max(0, (int) Math.floor(u0 * image.getWidth())), x1 = Math.min(image.getWidth(), (int) Math.ceil(u1 * image.getWidth()));
				int y0 = Math.max(0, (int) Math.floor(v0 * image.getHeight())), y1 = Math.min(image.getHeight(), (int) Math.ceil(v1 * image.getHeight()));
				for (int y = y0; y < y1; y++) {
					for (int x = x0; x < x1; x++) {
						seen++;
						if ((image.getPixel(x, y) >>> 24) >= 128) opaque++;
					}
				}
			}
			return seen > 0 && opaque < seen * OPAQUE_MIN;
		} catch (java.io.IOException e) {
			return false;
		}
	}

	/** One cube's box {lo, hi} in a frame ({@code inverse} takes model space into it). */
	private static float[][] cubeBox(Cube c, Matrix4f inverse) {
		Matrix4f toLocal = new Matrix4f(inverse).mul(c.pose());
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (ModelPart.Polygon polygon : c.cube().polygons) {
			for (ModelPart.Vertex v : polygon.vertices()) {
				Vector3f p = toLocal.transformPosition(new Vector3f(v.x() / 16F, v.y() / 16F, v.z() / 16F));
				lo[0] = Math.min(lo[0], p.x); lo[1] = Math.min(lo[1], p.y); lo[2] = Math.min(lo[2], p.z);
				hi[0] = Math.max(hi[0], p.x); hi[1] = Math.max(hi[1], p.y); hi[2] = Math.max(hi[2], p.z);
			}
		}
		return new float[][] {lo, hi};
	}

	/**
	 * The borrowed head's ears on the main head: each where it stands on its own head, as a share of that head's width
	 * and depth and above its top, moved to the same share of the main head, at the size the main head's width asks
	 * for. Both heads are measured in their own posed frames, so the ears turn and nod with the main head.
	 */
	private static void placeEars(Body lent, Body main, boolean huge, @Nullable Seat seat, List<Cube> out) {
		List<Cube> ownHead = new ArrayList<>();
		Matrix4f ownFrame = headCubes(lent.root(), lent.base(), ownHead);
		if (ownFrame == null) return;
		// what they stand on: the main's head part, or the seat a body without one (or one whose head is under a pumpkin) is given
		Matrix4f mainFrame;
		float[][] mine;
		if (seat != null) {
			mainFrame = seat.frame();
			mine = seat.box();
		} else {
			if (headPath(main.root()) == null) return;
			List<Cube> mainHead = new ArrayList<>();
			mainFrame = headCubes(main.root(), main.base(), mainHead);
			if (mainFrame == null) return;
			mine = earBox(mainHead, mainFrame, pixel(main));
		}
		float[][] own = earBox(ownHead, ownFrame, pixel(lent));
		if (own == null || mine == null) return;
		List<Cube> ears = ears(ownHead, ownFrame, own, namedEars(lent.root()), lent.texture());
		if (ears.isEmpty()) return;
		Matrix4f ownInverse = new Matrix4f(ownFrame).invert();
		float tallest = 0F;
		for (Cube c : ears) {
			float[][] box = cubeBox(c, ownInverse);
			tallest = Math.max(tallest, box[1][1] - box[0][1]);
		}
		float ownWidth = Math.max(0.01F, own[1][0] - own[0][0]), ownDepth = Math.max(0.01F, own[1][2] - own[0][2]);
		float size = LentHeadSize.earScale((mine[1][0] - mine[0][0]) / ownWidth, huge); // an armadillo's at 2.5 were twice its head
		// and the tallest ear no taller than three quarters of the head it stands on: a donkey's on a zombie stood a head over it
		size = LentHeadSize.earCap(size, tallest, mine[1][1] - mine[0][1], huge);
		for (Cube c : ears) {
			float[][] box = cubeBox(c, ownInverse);
			// where the ear meets the head: the middle of its bottom
			Vector3f joint = new Vector3f((box[0][0] + box[1][0]) / 2F, box[1][1], (box[0][2] + box[1][2]) / 2F);
			float shareX = (joint.x - own[0][0]) / ownWidth, shareZ = Math.clamp((joint.z - own[0][2]) / ownDepth, 0F, 1F);
			Vector3f at = new Vector3f(mine[0][0] + shareX * (mine[1][0] - mine[0][0]), mine[0][1] + (joint.y - own[0][1]) * size,
					mine[0][2] + shareZ * (mine[1][2] - mine[0][2]));
			// then into the head on every side by EAR_SET_IN: a tilted ear's box bottom isn't where it joins, and the head's
			// top is the hat layer, half a pixel above the head, so ears floated over it (I saw it)
			Matrix4f mainInverse = new Matrix4f(mainFrame).invert();
			float[][] box2 = cubeBox(new Cube(new Matrix4f(mainFrame).translate(at).scale(size).translate(-joint.x, -joint.y, -joint.z).mul(ownInverse)
					.mul(c.pose()), c.normal(), c.cube()), mainInverse);
			// sideways: at least touching the head (a camel's stick out of its sides)
			float lo = mine[0][0] + EAR_SET_IN, hi = mine[1][0] - EAR_SET_IN;
			if (box2[0][0] > hi) at.x -= box2[0][0] - hi;
			else if (box2[1][0] < lo) at.x += lo - box2[1][0];
			// front to back: its middle over the head (a horse's leans back, and off the back edge it floated behind)
			float middle = (box2[0][2] + box2[1][2]) / 2F;
			lo = mine[0][2] + EAR_SET_IN;
			hi = mine[1][2] - EAR_SET_IN;
			if (lo < hi) at.z += Math.clamp(middle, lo, hi) - middle;
			// down: its lowest third in the head, at least EAR_SET_IN, so a tilted base is in it all along (a one pixel ear
			// keeps half of itself above the head, LentHeadSize.earSink: cat and ocelot ears were buried flush)
			float sink = LentHeadSize.earSink(box2[1][1] - box2[0][1], EAR_SET_IN);
			at.y += mine[0][1] + sink - box2[1][1];
			Matrix4f move = new Matrix4f(mainFrame).translate(at).scale(size).translate(-joint.x, -joint.y, -joint.z).mul(ownInverse);
			Matrix3f turn = move.get3x3(new Matrix3f()).scale(1F / size);
			out.add(new Cube(new Matrix4f(move).mul(c.pose()), new Matrix3f(turn).mul(c.normal()), c.cube()));
		}
	}

	/** How far a sheep's wool layer stands out of its body, in model pixels (SheepFurModel's deformation). */
	private static final float WOOL_PX = 1.75F;

	/** A head box deeper than this many times its width is a head with a neck in it (a camel's: 7 pixels across, 19 deep). */
	private static final float NECK_DEPTH = 2.2F;

	/**
	 * The box ears are measured against on a head: its big blocks ({@link #coreBox}), but for a head part that holds a neck as well (a camel's
	 * and a camel husk's, 7 pixels across and 19 deep) only the blocks whose top is within a pixel of the highest: the 7 x 14 x 7 column the
	 * animal's own ears stand on. The chest block behind it made the box twice as deep as the head, and an ear's share of that depth put a
	 * lent camel's ears on the front of a donkey's muzzle and a lent horse's in the air behind a camel's neck (rig, 2026-11-20).
	 * {@code pixel}: one model pixel of the body, in blocks as drawn.
	 */
	private static float @Nullable [][] earBox(List<Cube> head, Matrix4f frame, float pixel) {
		float[][] box = coreBox(head, frame);
		if (box == null) return null;
		float width = box[1][0] - box[0][0], depth = box[1][2] - box[0][2];
		if (width <= 0.01F || depth <= NECK_DEPTH * width) return box;
		Matrix4f inverse = new Matrix4f(frame).invert();
		float biggest = 0;
		for (Cube c : head) {
			if (!brim(c, inverse)) biggest = Math.max(biggest, volume(c.cube()));
		}
		List<float[][]> boxes = new ArrayList<>();
		float top = Float.MAX_VALUE;
		for (Cube c : head) {
			if (volume(c.cube()) < biggest * 0.25F || brim(c, inverse)) continue;
			float[][] b = cubeBox(c, inverse);
			boxes.add(b);
			top = Math.min(top, b[0][1]);
		}
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (float[][] b : boxes) {
			if (b[0][1] > top + pixel) continue;
			for (int axis = 0; axis < 3; axis++) {
				lo[axis] = Math.min(lo[axis], b[0][axis]);
				hi[axis] = Math.max(hi[axis], b[1][axis]);
			}
		}
		// only where that really is a narrower column
		if (lo[0] > hi[0] || hi[2] - lo[2] > 0.6F * depth) return box;
		return new float[][] {lo, hi};
	}

	/** What borrowed ears stand on, where that is not the main's own head part: the frame they are measured in, and the box in it (model axes, +y down). */
	private record Seat(Matrix4f frame, float[][] box) {}

	/**
	 * Where borrowed ears go when the main has no head part of its own to take them: a body without one gets them on top, on a
	 * stand-in head the size the lent heads are held to there (rig, 2026-11-19: a silverfish, a squid, a nautilus and the cube mobs
	 * dropped every lent ear, and a guardian's plates and spikes came to nothing on them); a snow golem's go on its pumpkin, which
	 * swallows the head box they were seated in. Null for a main whose own head part is the seat.
	 */
	private static @Nullable Seat earSeat(EntityRenderState state, Body main) {
		if (headPath(main.root()) != null) {
			if (!(state instanceof net.minecraft.client.renderer.entity.state.SnowGolemRenderState golem) || golem.headBlock.isEmpty() || golem.isInvisible) return null;
			List<Cube> head = new ArrayList<>();
			Matrix4f frame = headCubes(main.root(), main.base(), head);
			return frame == null ? null : new Seat(frame, LentHeadSize.snowGolemPumpkin(main.base().last().pose().getScale(new Vector3f()).y));
		}
		Matrix4f bodyM = rigid(main.base().last().pose());
		List<Cube> core = new ArrayList<>();
		collectCore(main.root(), copy(main.base()), core);
		boolean hugeCube = hugeCube(state);
		// by the shell where there is one, as the lent head's stand-in is sized in place(): ears wider than the head beside them otherwise
		float size = LentHeadSize.headlessStandIn(headSize(shellOr(main.root(), core), bodyM), hugeCube);
		if (size <= 0.01F) return null;
		// on the shell, not the soft body that hangs off it (a nautilus's): the middle of the whole body put the ears past the shell's end
		List<Cube> seatCubes = shellOr(main.root(), core);
		Vector3f top = topOf(main, seatCubes);
		// a body whose face end is not in doubt (a bee, a tropical fish) takes the stand-in flush with its front, not over the middle of its
		// back: the lent ears stood 60 to 70 percent of the bee's length behind its face and read as sprouting from the back (rig, 2026-11-21)
		boolean flush = faceEnd(state) && !Float.isNaN(frontOf(main, seatCubes));
		if (flush) top.z = frontOf(main, seatCubes) + size / 2F;
		if (state.entityType == net.minecraft.world.entity.EntityTypes.SLIME) top.y -= LentHeadSize.SLIME_SHELL_LIFT_PX * pixel(main);
		// a body the renderer tips on its side (a squid on land) is seated where the upright turn leaves its top, not at the model's top,
		// which is its front end: the ears sank into the mantle (rig, 2026-11-20)
		Matrix4f upright = uprightTurn(state, main);
		top.add(upright.transformDirection(uprightLift(main, seatCubes, top, upright), new Vector3f()));
		Matrix4f frame = new Matrix4f(bodyM).translate(top).mul(headlessTurn(state, main));
		float half = size / 2F;
		// the stand-in is the body's own top: its box reaches down into the body from the top, so borrowed ears sink into it. It used to
		// stand on the body, and the ears sat a stand-in head's height up in the air (rig, 2026-11-20, about 130 pairs on the cube mobs,
		// the nautilus family, the strider, the ghast, small fish and the guardian's plates)
		float[] shellSides = shellPath(main.root()) != null ? sides(coreBox(seatCubes, bodyM)) : null;
		// a shell is not a cube of its volume: that stand-in was deeper and taller than it, and ears clamped onto it hung off the shell's end and
		// stood as tall as the shell (rig, 2026-11-21, nautilus and zombie nautilus + horse, donkey, mule, camel, wolf)
		float halfX = shellSides == null ? half : Math.min(half, shellSides[0] / 2F), halfZ = shellSides == null ? half : Math.min(half, shellSides[2] / 2F);
		// a seat flush with the front that is as deep as the whole core put an ear at its own 60 to 80 percent of the head's depth 40 to 65 percent
		// of the bee's length behind its face, over the middle stripe, sprouting from its mid-back (rig, 2026-11-21, round 5): half as deep, flush
		// with the front, they stand 20 to 35 percent from the face
		if (flush) {
			halfZ = Math.min(halfZ, FACE_SEAT_DEPTH * size);
			top.z += halfZ - half;
			frame =new Matrix4f(bodyM).translate(top).mul(headlessTurn(state, main));
		}
		float height = shellSides == null ? size : Math.min(size, shellSides[1]);
		return new Seat(frame, new float[][] {{-halfX, 0F, -halfZ}, {halfX, height, halfZ}});
	}

	/**
	 * A body that is one big cube all through, whose own head is a stand-in half its size and takes small lent heads up to it, as a giant's
	 * does: a ghast, a happy ghast and the elder guardian (2026-11-21: a 0.5 block head, a fifth of its 2 block cube, on 40 lenders; I
	 * picked the ghast's rule for it too). The plain guardian's 0.75 block cube keeps the head at its own size.
	 */
	private static boolean hugeCube(EntityRenderState state) {
		return state.entityType == net.minecraft.world.entity.EntityTypes.GHAST || state.entityType == net.minecraft.world.entity.EntityTypes.HAPPY_GHAST
				|| state.entityType == net.minecraft.world.entity.EntityTypes.ELDER_GUARDIAN;
	}

	/**
	 * A body with no head part whose face end is not in doubt (a bee's, a tropical fish's): a lent head or ears go flush with its front
	 * ({@link #frontOf}). Not the worms and the cube mobs, whose seat over the middle of the body is a call I have not changed (a magma or
	 * sulfur cube keeps the middle of its top, but a head deeper than half of it is moved back onto it: {@link LentHeadSize#backIntoTop}).
	 */
	private static boolean faceEnd(EntityRenderState state) {
		// the tadpole too (round 5, from tadpole + camel's ears; the cause was not found by reading; kept for the rule): its face
		// end is not in doubt either, and a head hinged by its back edge on the middle of its 3 pixel body hung a pixel or more in front of it. The
		// stand-in is a body deep at most, so flush with the front it sits on the body and not over the tail root; its ears take the narrower seat
		return state.entityType == net.minecraft.world.entity.EntityTypes.BEE || state.entityType == net.minecraft.world.entity.EntityTypes.TROPICAL_FISH
				|| state.entityType == net.minecraft.world.entity.EntityTypes.TADPOLE;
	}

	/** How far a lent ear's seat reaches either side of its middle on a flush-fronted body, as a share of the stand-in's size: the seat is half as deep as the stand-in. */
	private static final float FACE_SEAT_DEPTH = 0.25F;

	/** The front (the least z, model axes) of a body's big blocks in its own frame, in blocks as drawn; NaN when it has none. */
	private static float frontOf(Body body, List<Cube> cubes) {
		Matrix4f inverse = rigid(body.base().last().pose()).invert();
		float biggest = 0;
		for (Cube c : cubes) biggest = Math.max(biggest, volume(c.cube()));
		float front = Float.MAX_VALUE;
		for (Cube c : cubes) {
			if (volume(c.cube()) < biggest * 0.25F) continue;
			front = Math.min(front, cubeBox(c, inverse)[0][2]);
		}
		return front == Float.MAX_VALUE ? Float.NaN : front;
	}

	/** One model pixel of a body as it is drawn, in blocks. */
	private static float pixel(Body body) {
		return body.base().last().pose().getScale(new Vector3f()).y / 16F;
	}

	/**
	 * How a head put on top of a body without a head part is turned, in the body's frame: upright whatever the renderer tips the body
	 * to ({@link LentHeadSize#uprightFix}: a squid lying on land carried the head with it, face to the floor), then by the look. Not while
	 * the body is dying, upside down (a Dinnerbone name) or spinning with a riptide: there the renderer's tip is the point, the head
	 * goes over with the body, and cancelling it left the head standing off the side of a body lying down (or buried in an upside-down one).
	 * A frozen mob's shake is still cancelled, so its lent head holds still.
	 */
	private static Matrix4f headlessTurn(EntityRenderState state, Body main) {
		Matrix4f turn = uprightTurn(state, main);
		if (!(state instanceof LivingEntityRenderState living)) return turn;
		return turn.rotateY((float) Math.toRadians(living.yRot)).rotateX((float) Math.toRadians(living.xRot));
	}

	/** The upright half of {@link #headlessTurn}: the turn that cancels what the renderer tips the body to, the identity where it does not. */
	private static Matrix4f uprightTurn(EntityRenderState state, Body main) {
		if (!(state instanceof LivingEntityRenderState living)) return new Matrix4f();
		if (living.deathTime <= 0F && !living.isUpsideDown && !living.isAutoSpinAttack) return LentHeadSize.uprightFix(rigid(main.base().last().pose()), new Matrix4f(main.unturn()).invert());
		return new Matrix4f();
	}

	/**
	 * How far, in the body's frame, to move the top of a body without a head part ({@code top}, {@link #topOf}) so it is the top of the body
	 * as the upright turn leaves it: the highest point of its big blocks in the turned frame, and the middle of them there. Zero for a body
	 * that is not tipped (the turn is the identity), whose model top is its top.
	 */
	private static Vector3f uprightLift(Body main, List<Cube> cubes, Vector3f top, Matrix4f upright) {
		if (upright.equals(new Matrix4f(), 1e-4F)) return new Vector3f();
		Matrix4f inverse = rigid(main.base().last().pose()).invert();
		Matrix4f toTurned = new Matrix4f(upright).invert();
		float biggest = 0;
		for (Cube c : cubes) biggest = Math.max(biggest, volume(c.cube()));
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (Cube c : cubes) {
			if (volume(c.cube()) < biggest * 0.25F) continue;
			Matrix4f toLocal = new Matrix4f(inverse).mul(c.pose());
			for (ModelPart.Polygon polygon : c.cube().polygons) {
				for (ModelPart.Vertex v : polygon.vertices()) {
					Vector3f p = toLocal.transformPosition(new Vector3f(v.x() / 16F, v.y() / 16F, v.z() / 16F)).sub(top);
					toTurned.transformPosition(p);
					for (int axis = 0; axis < 3; axis++) {
						lo[axis] = Math.min(lo[axis], p.get(axis));
						hi[axis] = Math.max(hi[axis], p.get(axis));
					}
				}
			}
		}
		if (lo[0] > hi[0]) return new Vector3f();
		// model axes: up is -y, so the top is the least y; the middle sideways and front to back
		return new Vector3f((lo[0] + hi[0]) / 2F, lo[1], (lo[2] + hi[2]) / 2F);
	}

	/**
	 * A whole creature lent as a head is seated by its shell where it has one (a nautilus's, a zombie nautilus's), not by the box round all
	 * its big blocks: the soft body hangs off one end of the shell, so the middle of the box was half a body past the shell and the
	 * shell overhung the front of the body it went on (rig, 2026-11-19, a zombie nautilus on a camel husk).
	 */
	private static List<Cube> shellOr(ModelPart root, List<Cube> cubes) {
		List<ModelPart> shell = shellPath(root);
		if (shell == null) return cubes;
		Set<ModelPart.Cube> mine = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		addAllCubes(shell.getLast(), mine);
		List<Cube> out = new ArrayList<>();
		for (Cube c : cubes) {
			if (mine.contains(c.cube())) out.add(c);
		}
		return out.isEmpty() ? cubes : out;
	}

	private static void addAllCubes(ModelPart part, Set<ModelPart.Cube> out) {
		ModelPartAccessor access = (ModelPartAccessor) (Object) part;
		out.addAll(access.morph$cubes());
		for (ModelPart child : access.morph$children().values()) addAllCubes(child, out);
	}

	/**
	 * The most a tiny main body can carry lent appendages at (see {@link LentHeadSize#tinyMainPartLimit}): its core, the side of the cube of
	 * its big blocks (the plates on a silverfish's back are not its body), against the longest side of everything the lent creature gives.
	 * No limit for any other body.
	 */
	private static float[] tinyMainLimit(Body main, Body lent) {
		// every body under TINY_MAIN, with a head part or not (2026-11-20: a fish, a bat, an allay or a vex carrying a dolphin's or a
		// dragon's parts at full size wore wings and tails several times its own length)
		float mainCore = coreOf(main);
		// almost every body is past the larger line: out before the model is searched for a head part
		if (mainCore <= 0.01F || mainCore >= LentHeadSize.TINY_HEADLESS) return NO_LIMIT;
		// a body with no head part is tiny up to TINY_HEADLESS: a bee's 0.48 carried a donkey's tail past its floor (rig, 2026-11-20)
		float line = hasHead(main.root()) ? LentHeadSize.TINY_MAIN : LentHeadSize.TINY_HEADLESS;
		if (mainCore >= line) return NO_LIMIT;
		// the appendages at rest, so the limit does not breathe with the lent wings' flap (restAppendageExtent)
		float extent = restAppendageExtent(lent);
		if (extent <= 0.01F) return NO_LIMIT;
		// flat fins have their own, higher floor (LentHeadSize.TINY_FIN_FLOOR): a 0.38 block fin came over at 0.08 on an allay
		return new float[] {LentHeadSize.tinyMainPartLimit(extent, mainCore, line), LentHeadSize.tinyMainPartLimit(extent, mainCore, line, LentHeadSize.TINY_FIN_FLOOR)};
	}

	/** No tiny-main limit, for parts and for flat fins. */
	private static final float[] NO_LIMIT = {Float.MAX_VALUE, Float.MAX_VALUE};

	/** A model's core in its own units (as drawn at size 1), measured the first time it is asked for. */
	private static final Map<ModelPart, Float> CORE_UNITS = new java.util.WeakHashMap<>();

	/**
	 * A body's core, the side of the cube of its big blocks as drawn (headSize), without the back plates. Asked for every frame by
	 * every multimorph, and walking and copying the whole model each time to learn one number that only matters under 0.4 block is
	 * not free for a crowd: it is measured once per model, in the pose it is first seen in, and scaled by the size it is drawn at
	 * (a baby's). Also keeps a body from jumping over the tiny-main line as it crouches or sits, which would swap its parts between
	 * full size and a fraction of it.
	 */
	private static float coreOf(Body main) {
		// the size without the slime-style squish (LentHeadSize.squishFree): a small sulfur cube's core is 0.003 from the tiny line and
		// its scale.y swings with every jump
		Vector3f scale = main.base().last().pose().getScale(new Vector3f());
		float size = (float) Math.sqrt(scale.x * (double) scale.y);
		if (size <= 1e-4F) return 0F;
		Float unit = CORE_UNITS.get(main.root());
		if (unit == null) {
			List<Cube> core = new ArrayList<>();
			collectCore(main.root(), copy(main.base()), core);
			unit = headSize(core, rigid(main.base().last().pose())) * LentHeadSize.squishFree(scale.x, scale.y, scale.z) / size;
			if (unit * size > 0.01F) CORE_UNITS.put(main.root(), unit); // nothing measured: ask again next frame
		}
		return unit * size;
	}

	/** {@link LentHeadSize#squishFree} for the pose a body is drawn in: what takes a core measured as drawn to the one without the jump squish. */
	private static float squishFree(Body main) {
		Vector3f scale = main.base().last().pose().getScale(new Vector3f());
		return LentHeadSize.squishFree(scale.x, scale.y, scale.z);
	}

	/** The cubes {@link #findAppendages} would take from a body, as they are posed (not moved). */
	private static void appendageCubes(ModelPart part, PoseStack pose, List<Cube> out) {
		if (!part.visible) return;
		pose.pushPose();
		part.translateAndRotate(pose);
		for (Map.Entry<String, ModelPart> child : ((ModelPartAccessor) (Object) part).morph$children().entrySet()) {
			String name = child.getKey();
			ModelPart limb = child.getValue();
			if (headName(name) || !limb.visible) continue;
			if (appendageName(name)) collect(limb, copy(pose), out);
			else appendageCubes(limb, pose, out);
		}
		pose.popPose();
	}

	/**
	 * A body ready to measure: its model posed for the state, the renderer's transforms up to the model ({@code base}),
	 * the turn that undoes the body's facing ({@code unturn}), and its texture.
	 */
	private record Body(ModelPart root, PoseStack base, Matrix4f unturn, RenderType type, Identifier texture) {}

	private static final Identifier DRAGON_TEXTURE = Identifier.withDefaultNamespace("textures/entity/enderdragon/dragon.png");

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static @Nullable Body body(EntityRenderDispatcher dispatcher, EntityRenderState state) {
		EntityRenderer<?, ?> renderer = dispatcher.getRenderer(state);
		if (renderer instanceof LivingEntityRenderer living && state instanceof LivingEntityRenderState s) {
			PoseStack base = posed(living, s);
			EntityModel model = (EntityModel) living.getModel();
			return new Body(model.root(), base, unturn(s), model.renderType(living.getTextureLocation(s)), living.getTextureLocation(s));
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
			return new Body(model.root(), base, new Matrix4f().rotationY((float) Math.toRadians(yaw)), model.renderType(DRAGON_TEXTURE), DRAGON_TEXTURE);
		}
		return null;
	}

	/**
	 * The turn that undoes a living body's facing (LivingEntityRenderer.setupRotations turns it by 180 - bodyRot). A
	 * shulker's renderer turns it by -bodyRot and then by its attach face, about the middle of its block: on a wall or a
	 * ceiling (held there, ShulkerLock) both are undone, so parts go on the shell as if it sat on the floor and turn with
	 * it; undoing the yaw alone put them on world-up places, off the shell. It leaves the same half turn the yaw alone
	 * leaves on a floor shell (180 more than other mobs' setupRotations), where the parts were already right. (A shulker
	 * body now takes no parts at all, everything it borrows going inside the shell, see {@link #inShell}; a borrowed
	 * shulker, always on the floor, is still measured with this.)
	 */
	private static Matrix4f unturn(LivingEntityRenderState s) {
		if (s instanceof ShulkerRenderState shulker) {
			return new Matrix4f().rotationY((float) Math.PI).rotate(new org.joml.Quaternionf(shulker.attachFace.getOpposite().getRotation()).conjugate())
					.rotateY((float) Math.toRadians(s.bodyRot));
		}
		return new Matrix4f().rotationY((float) Math.toRadians(s.bodyRot - 180F));
	}

	/** The borrowed head and parts, posed and placed on the body: what is drawn, and how it is textured. */
	private record Placed(List<Cube> cubes, RenderType type, @Nullable Fish fish) {}

	/**
	 * A lent tropical fish's dye colours, as vanilla paints one: the base texture tinted by the base colour, the pattern texture over it
	 * tinted by the pattern colour (TropicalFishRenderer, TropicalFishPatternLayer). The base texture alone leaves the dorsal, the tail
	 * and most of the side fins see-through (all three are painted by the pattern layer, and by nothing else on a large fish), so only a
	 * grey flank nub came over (rig, 2026-11-21, round 4: about 90 pairs). {@code pattern}: the pattern layer's texture for the variant.
	 */
	private record Fish(int baseColor, int patternColor, Identifier pattern) {}

	/** An opaque, neutral texture: a lent fish's flat fins are given a solid ground in the base colour under their pattern. */
	private static final Identifier FIN_GROUND = Identifier.withDefaultNamespace("textures/block/white_concrete.png");

	/**
	 * How much the pattern pass is grown about each cube's own pivot, so it stands clear of the pass under it (vanilla's pattern layer is a
	 * model of its own, grown by 0.008 pixels).
	 */
	private static final float PATTERN_GROW = 1.01F;

	/** The pattern texture of a tropical fish variant: tropical_a_pattern_1..6 for the small body, tropical_b_pattern_1..6 for the large one, in the enum's order. */
	private static Identifier fishPattern(net.minecraft.world.entity.animal.fish.TropicalFish.Pattern pattern) {
		String body = pattern.base() == net.minecraft.world.entity.animal.fish.TropicalFish.Base.SMALL ? "a" : "b";
		return Identifier.withDefaultNamespace("textures/entity/fish/tropical_" + body + "_pattern_" + (pattern.ordinal() % 6 + 1) + ".png");
	}

	private static @Nullable Fish fishOf(EntityRenderState borrowed) {
		if (!(borrowed instanceof net.minecraft.client.renderer.entity.state.TropicalFishRenderState fish)) return null;
		return new Fish(fish.baseColor | 0xFF000000, fish.patternColor | 0xFF000000, fishPattern(fish.pattern));
	}

	/** Whether a cube has no thickness on some side (a fin, a flat tail): {@link ModelPart.Cube} sides in model pixels. */
	private static boolean flatCube(ModelPart.Cube k) {
		return k.maxX - k.minX < 0.01F || k.maxY - k.minY < 0.01F || k.maxZ - k.minZ < 0.01F;
	}

	/** After {@code state} was submitted: the borrowed head and parts, if it carries a borrowed form. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static void submit(EntityRenderDispatcher dispatcher, EntityRenderState state, double x, double y, double z, PoseStack poseStack,
			SubmitNodeCollector collector) {
		// an invisible body hides what it lends too: the wings must not hang in the air with nothing between them
		if (state.isInvisible) return;
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
		Fish fish = placed.fish();
		if (fish == null) {
			collector.submitCustomGeometry(poseStack, placed.type(), (pose, buffer) -> drawCubes(placed.cubes(), pose, buffer, light, overlay, -1, 0, false, false));
		} else {
			// as vanilla paints a tropical fish: the body in the base colour; the flat fins (the dorsal, the tail, the side fins) on an opaque
			// ground in the base colour, as the base texture leaves them see-through; then the pattern over all of it in the pattern colour,
			// one order later so it is drawn over the passes under it whatever their render types
			collector.submitCustomGeometry(poseStack, placed.type(), (pose, buffer) -> drawCubes(placed.cubes(), pose, buffer, light, overlay, fish.baseColor(), 0, true, false));
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(FIN_GROUND), (pose, buffer) -> drawCubes(placed.cubes(), pose, buffer, light, overlay, fish.baseColor(), 0, false, true));
			// the pattern pass is coplanar with the ground under it on the flat fins (a plane through its pivot does not move when grown, and vanilla's
			// 0.008 pixel growth of the pattern model thickens it): the z-offset render type draws it a step towards the camera, as the shulker's
			// lid is over its shell, so the fins do not flicker between the pattern and the base colour
			collector.order(1).submitCustomGeometry(poseStack, RenderTypes.entityCutoutZOffset(fish.pattern()),
					(pose, buffer) -> drawCubes(placed.cubes(), pose, buffer, light, overlay, fish.patternColor(), PATTERN_GROW, false, false));
		}
		poseStack.popPose();
	}

	/**
	 * The placed cubes into {@code buffer}, tinted {@code color}. {@code grow} (0 for none): each cube enlarged by it about its own pivot. {@code skipFlat}:
	 * leave out the cubes with no thickness; {@code onlyFlat}: draw only those.
	 */
	private static void drawCubes(List<Cube> cubes, PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buffer, int light, int overlay, int color, float grow, boolean skipFlat, boolean onlyFlat) {
		PoseStack scratch = new PoseStack();
		PoseStack.Pose last = scratch.last();
		for (Cube c : cubes) {
			boolean flat = flatCube(c.cube());
			if ((skipFlat && flat) || (onlyFlat && !flat)) continue;
			last.pose().set(pose.pose()).mul(c.pose());
			if (grow != 0F) last.pose().scale(grow);
			last.normal().set(pose.normal()).mul(c.normal());
			c.cube().compile(last, buffer, light, overlay, color);
		}
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
		// the main head's frame and its own cubes (hidden at draw time, still posed): where a borrowed head goes; and the
		// same head part at its rest turn, where it is now: the borrowed head is put where that head rests, then turned as
		// that head has turned from it, about its joint. Measured on the turned head's box and turned by the look about
		// its back edge, a borrowed head slid about as you looked round, more than a block on a giant (I saw it)
		// A shulker body keeps what it borrows inside its shell (inShell), so it has no neck or torso to put things on: wings,
		// tails and the like would stick out of the box, and aren't drawn on it at all (my design).
		boolean shell = state instanceof ShulkerRenderState;
		List<Cube> mainHead = new ArrayList<>();
		Matrix4f neck = !headSwap || shell ? null
				: headPath(main.root()) != null && !allHead(state) ? headCubes(main.root(), main.base(), mainHead) : neckFrame(main.root(), main.base(), allHead(state));
		Matrix4f restNeck = mainHead.isEmpty() ? null : restFrame(main.root(), main.base());
		float[][] mainTorso = shell ? null : torso(main.root(), local(main.base(), main.unturn()), true);
		// a slime's torso is its inner cube; what shows is the outer shell, a model pixel out on every side (SlimeOuterLayer): parts seated on
		// the inner box sank into it (rig, 2026-11-20, fins as hairlines inside the translucent shell)
		if (mainTorso != null && state.entityType == net.minecraft.world.entity.EntityTypes.SLIME) {
			float shell1 = LentHeadSize.SLIME_SHELL_LIFT_PX * pixel(main);
			for (int axis = 0; axis < 3; axis++) {
				mainTorso[0][axis] -= shell1;
				mainTorso[1][axis] += shell1;
			}
		}
		// a magma cube's and a sulfur cube's pieces sat flush with the faces of the cube (and a fin's flat plane z-fought with it, or was
		// seen through it): a pixel stand-off, as for the slime's shell (rig, 2026-11-21, round 4)
		if (mainTorso != null && (state.entityType == net.minecraft.world.entity.EntityTypes.MAGMA_CUBE || state.entityType == net.minecraft.world.entity.EntityTypes.SULFUR_CUBE)) {
			float stand = LentHeadSize.SLIME_SHELL_LIFT_PX * pixel(main);
			for (int axis = 0; axis < 3; axis++) {
				mainTorso[0][axis] -= stand;
				mainTorso[1][axis] += stand;
			}
			// but not off the top (y is up here): a dorsal rooted on the top face stood a pixel over it with sky between, in every view (rig,
			// 2026-11-21, round 5, magma cube + dolphin, tropical fish, cod). A flat fin stands on the top, not against it, so it needs no stand-off
			mainTorso[1][1] -= stand;
		}
		// a sheep's wool is a layer of its own, 1.75 pixels out on every side of the body the model holds: wings, fins and a tail seated
		// on the bare body were inside the wool and nothing came over (rig, 2026-11-21, sheep + tropical fish, + chicken)
		if (mainTorso != null && state instanceof net.minecraft.client.renderer.entity.state.SheepRenderState sheep && !sheep.isSheared) {
			float wool = WOOL_PX * pixel(main);
			for (int axis = 0; axis < 3; axis++) {
				mainTorso[0][axis] -= wool;
				mainTorso[1][axis] += wool;
			}
		}
		Inner shellHead = shell ? inner(main) : null; // measured before the borrowed form poses the model it may share
		Body lent = body(dispatcher, borrowed);
		if (lent == null) return null;
		List<Cube> placed = new ArrayList<>();
		if (headSwap && withHead && shell) {
			List<Cube> cubes = new ArrayList<>();
			if (shellHead != null && headCubes(lent.root(), lent.base(), cubes) != null) inShell(shellHead, lent, cubes, placed);
		} else if (headSwap && withHead) {
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
				// a goat's head part is not tilted, its big nose block is, 55 degrees, so restPitch below reads 0 for it and it was never levelled on
				// the elder guardian's plate (round 4 held); on a flat top that block is stood level before anything is measured (levelNose)
				if (!wholeBody && mainHead.isEmpty() && LentHeadSize.levelsOnFlatTop(state.entityType, borrowed.entityType)) levelNose(cubes, own);
				Vector3f ownNeck = wholeBody ? neckPoint(shellOr(lent.root(), cubes), bodyB, false) : neckJoint(cubes, own, bodyB, hatCubes(lent.root()));
				float grow = 1F;
				// how far its rest pose is nose down: a head that rests pitched is put level (below), so its size, its box and its seat are
				// those of the level head, not of the box round the tilted one (18 pixels tall for a hoglin's 6 pixel snout)
				float restTilt = wholeBody ? 0F : restPitch(bodyB, own);
				float level = LentHeadSize.levelPitch(restTilt);
				// a head resting a little nose down (a goat's, a skeleton horse's) stands tilted on a flat top plate, leaning over its front edge: level
				// on the elder guardian's (rig, 2026-11-21) and, since round 5, on the magma cube's and the sulfur cube's (LentHeadSize.levelsOnFlatTop;
				// the goat's nose is levelled by levelNose, its part not being tilted). Only those two lenders: the horse family's 30 degrees and the
				// goat's slope on every other body are the animal's own carriage, a question I have not settled
				if (level == 0F && !wholeBody && mainHead.isEmpty() && LentHeadSize.levelsOnFlatTop(state.entityType, borrowed.entityType)
						&& Math.abs(restTilt) > FLAT_REST) level = restTilt;
				float[][] levelBox = level != 0F ? coreBox(cubes, own) : null;
				float ownSize = levelBox != null ? (float) Math.cbrt((levelBox[1][0] - levelBox[0][0]) * (levelBox[1][1] - levelBox[0][1]) * (levelBox[1][2] - levelBox[0][2]))
						: headSize(cubes, bodyB);
				// how far the head's sides run, for the per-axis cap (LentHeadSize.grow)
				// a creature lent whole is measured by all of it as drawn, tentacles and legs too (LentHeadSize.grow); a head by its big blocks
				float[] ownAxes = sides(wholeBody ? wholeBox(cubes, bodyB) : levelBox != null ? levelBox : coreBox(cubes, bodyB));
				float[] mainAxes = null;
				boolean mainPitched = false;
				float cap = LentHeadSize.BIG_HEAD;
				List<Cube> headless = null; // a headless body's cubes: measured for the stand-in head, and again for the top the borrowed head sits on
				if (ownSize > 0.01F) {
					// a ghast is one cube all through: a head as big as its cube is a speck of a head that grows, as on a giant
					boolean hugeCube = hugeCube(state);
					float mainSize;
					if (mainHead.isEmpty()) {
						// no head part (a pufferfish, a tadpole, a bee, a ghast, a guardian's whole body): its body's core stands in for the
						// head it has not got, or no lent head was capped and a husk's was twice the whole fish (rig, 2026-11-18)
						headless = new ArrayList<>();
						collectCore(main.root(), copy(main.base()), headless);
						// by its shell where it has one (a nautilus's): the soft body hanging off it made a giant's head 1.9 times the shell
						float core = headSize(shellOr(main.root(), headless), bodyM);
						mainSize = LentHeadSize.headlessStandIn(core, hugeCube);
						// the cap's line (TINY_HEADLESS) is compared with the core without the jump squish: a small sulfur cube's core sits
						// 0.003 over it, and its squish swung the core across it, popping the head between 1x and 1.5x
						cap = LentHeadSize.headCap(true, core * squishFree(main));
						mainAxes = new float[] {mainSize, mainSize, mainSize};
					} else {
						// the body's head as it rests, not as it is turned now (the plugin's table holds it so; see restHeadBox)
						float[][] restBox = restHeadBox(mainHead, neck, restNeck, bodyM, hatCubes(main.root()));
						mainPitched = restNeck != null && LentHeadSize.levelPitch(restPitch(bodyM, restNeck)) != 0F;
						mainSize = restBox == null ? 0F : (float) Math.cbrt((restBox[1][0] - restBox[0][0]) * (restBox[1][1] - restBox[0][1]) * (restBox[1][2] - restBox[0][2]));
						mainAxes = sides(restBox);
						if (mainSize <= 0.01F) mainSize = headSize(mainHead, bodyM);
						// a creature lent whole on a head that holds a neck (a camel's, 19 pixels deep) is held to the head the animal carries on it,
						// the column its own ears stand on, not to the chest block behind it too (rig, 2026-11-20: a ghast over twice the
						// head, a nautilus floating over the shoulders)
						if (wholeBody && !(hugeCube || state instanceof EnderDragonRenderState || state.entityType == net.minecraft.world.entity.EntityTypes.GIANT)) {
							float[][] face = earBox(mainHead, neck, pixel(main));
							float[][] all = coreBox(mainHead, neck);
							if (face != null && all != null && face[1][2] - face[0][2] < 0.99F * (all[1][2] - all[0][2])) {
								mainSize = (float) Math.cbrt((face[1][0] - face[0][0]) * (face[1][1] - face[0][1]) * (face[1][2] - face[0][2]));
								mainAxes = sides(face);
							} else {
								// a head whose big block is tilted inside its part (a goat's nose, 55 degrees): the box round it is 12 pixels
								// over for a 5 x 7 x 10 block, and a squid lent whole was held to that, 2.5 times the level head (rig, 2026-11-21)
								float[] block = tiltedBlockSides(mainHead, neck, pixel(main));
								if (block != null) {
									mainSize = (float) Math.cbrt(block[0] * block[1] * block[2]);
									mainAxes = block;
								}
							}
						}
					}
					// how big it is drawn: see LentHeadSize (a giant's or a dragon's head takes a small one up to its own, and no head is
					// more than BIG_HEAD times the one it replaces; a creature lent whole is never bigger than that head)
					boolean growsToBody = hugeCube || state instanceof EnderDragonRenderState || state.entityType == net.minecraft.world.entity.EntityTypes.GIANT;
					if (mainSize > 0.01F) {
						grow = LentHeadSize.grow(wholeBody, growsToBody, ownSize, mainSize,
								LentHeadSize.axisFit(mainAxes, ownAxes, mainPitched ? LentHeadSize.PITCHED_FLAT_FLOOR : LentHeadSize.FLAT_FLOOR), cap);
					}
				}
				Vector3f mainNeck;
				// the top a head with a head part stands on, on a body with no head part (NaN for any other): see closeGap below
				float seatTop = Float.NaN;
				// how the main head has turned from its rest (bodyM's frame): the borrowed head turns with it
				Matrix4f turnHead;
				float[][] local = restNeck == null ? null : coreBox(mainHead, neck);
				if (local == null) {
					// a body without a head part: the borrowed head sits on top of it and turns with the look where it sits
					if (headless == null) {
						headless = new ArrayList<>();
						collectCore(main.root(), copy(main.base()), headless);
					}
					// on a silverfish or an endermite too, over its thick middle segment: whether a head sits there or hangs forward on the
					// head end is a seating call I have not made (a front-segment seat was tried and taken out, 2026-11-21)
					mainNeck = topOf(main, headless);
					// a body the renderer tips on its side (a squid on land) is seated where the upright turn leaves its top, as its ears are
					// (earSeat): the model's top is its front end, and the head stood on the end of the lying mantle (rig, 2026-11-20)
					Matrix4f upright = uprightTurn(state, main);
					mainNeck.add(upright.transformDirection(uprightLift(main, headless, mainNeck, upright), new Vector3f()));
					// a slime's model is its inner cube; the shell that shows is one model pixel higher (SlimeOuterLayer)
					if (state.entityType == net.minecraft.world.entity.EntityTypes.SLIME) mainNeck.y -= LentHeadSize.SLIME_SHELL_LIFT_PX * pixel(main);
					seatTop = mainNeck.y;
					// a head that rests pitched nose down (a goat's nose block, a skeleton horse's skull) hinged on its back-bottom point swings its front
					// under the plane it stands on: the elder guardian's 2 block cube buried it, a nautilus's shell took half of it. Raised by how far
					// it dips (rig, 2026-11-20)
					if (!wholeBody) {
						float dip = dipBelow(cubes, bodyB, ownNeck, grow, level, hatCubes(lent.root()));
						mainNeck.y -= dip;
						// the raise is what seats a dipping head's lowest point on the top: closeGap, measuring against the top before it, would take it back
						// down (and bury the nose again) if its own set of big blocks left that nose out
						if (dip > 0F) seatTop = Float.NaN;
					}
					// a head hinged at its back edge on the middle of a magma cube's or a sulfur cube's top, at up to 1.5 cubes deep, hung 55 to 65 percent
					// of itself out in front of the cube over air (rig, 2026-11-21, round 5); the slime's inner stand-in is small enough to stay on top.
					// Moved back till its front is at the top's front edge (a deeper head is centred)
					if (!wholeBody && (state.entityType == net.minecraft.world.entity.EntityTypes.MAGMA_CUBE
							|| state.entityType == net.minecraft.world.entity.EntityTypes.SULFUR_CUBE)) {
						// any lent head with a head part, not only the goat and the skeleton horse: every one deeper than half the top hung over the front
						// edge. The top's depth is the cube's without its jump squish (squishFree), as the cap is: the drawn depth swings with every jump and
						// the head slid along the top with it
						float[][] seated = levelBox != null ? levelBox : coreBox(cubes, bodyB), top = coreBox(headless, bodyM);
						Vector3f drawnScale = main.base().last().pose().getScale(new Vector3f());
						if (seated != null && top != null) {
							mainNeck.z += LentHeadSize.backIntoTop((seated[1][2] - seated[0][2]) * grow,
									(top[1][2] - top[0][2]) * LentHeadSize.squishFreeDepth(drawnScale.x, drawnScale.y, drawnScale.z));
						}
					}
					// a bee's back is a hair under the drawn top the slab sits on: sky showed between them (rig, 2026-11-21, round 5, bee + frog): sunk a pixel
					if (state.entityType == net.minecraft.world.entity.EntityTypes.BEE) mainNeck.y += pixel(main);
					// on a body whose face end is not in doubt (a bee, a tropical fish) the lent head sits at the front of it, flush, not centred
					// over the middle of its back with half of it past the face: a frog's slab overhung the bee's face by 0.27 of its length, and a
					// wither was a cube on the middle of a fish's back (rig, 2026-11-21). Its back edge is the joint; a creature lent whole is
					// centred by its core
					float front = faceEnd(state) ? frontOf(main, headless) : Float.NaN;
					// of the level head where it is put level (a hoglin's rests 50 degrees nose down: the box round the tilted one is 2 pixels shallower
					// than the level head, and its back edge went that far forward of the front it is flush with)
					float[][] ownCore = Float.isNaN(front) ? null : levelBox != null ? levelBox : coreBox(cubes, bodyB);
					if (ownCore != null) {
						float depth = (ownCore[1][2] - ownCore[0][2]) * grow;
						mainNeck.z = front + (wholeBody ? depth / 2F : depth);
					}
					turnHead = new Matrix4f().translation(mainNeck).mul(headlessTurn(state, main)).translate(-mainNeck.x, -mainNeck.y, -mainNeck.z);
				} else {
					Matrix4f inBody = new Matrix4f(bodyM).invert();
					Matrix4f rest = new Matrix4f(inBody).mul(restNeck);
					turnHead = new Matrix4f(inBody).mul(neck).mul(new Matrix4f(rest).invert());
					// the main head (at rest) and torso (bodyM's frame; model axes: +y down, so lo[1] is a top)
					float[][] headBox = boxIn(local, rest);
					float[][] torsoBox = torsoIn(main, bodyM);
					// a lent head that is put level (a hoglin's, a zoglin's) is seated by the box of the level head, in its own frame: the box round
					// the tilted one is 18 pixels tall for a 6 pixel snout, and a head centred by that sat 8 pixels too low (rig, 2026-11-20)
					float[][] ownBox = levelBox != null ? levelBox : coreBox(cubes, bodyB);
					// a head held up on a neck the mod hides with it, reaching down to the torso and rising above it by three
					// quarters of the borrowed head's height or more (a camel's, a llama's, a horse's): the borrowed one goes on
					// top of the shoulders, not low on the chest. By its whole height, a villager's hooded head on a horse was
					// not high enough and floated over the hidden neck. Not a head raised on a neck the mod draws (a diving
					// dragon's), which is clear of the torso
					// (on a horse's, tilted up at rest, a tall borrowed head asks for less: LentHeadSize.riseNeeded)
					boolean tilted = Math.abs(rest.transformDirection(new Vector3f(0F, 0F, -1F)).y) > 0.2F;
					boolean high = torsoBox != null && ownBox != null && headBox[1][1] >= torsoBox[0][1] - 1F / 16F
							&& torsoBox[0][1] - headBox[0][1] >= LentHeadSize.riseNeeded(ownBox[1][1] - ownBox[0][1], grow, headBox[1][1] - headBox[0][1], tilted);
					// a head in front of the body (a ravager's hangs off its neck, a sniffer's from its face, half a pixel into
					// it) has room for a borrowed head in it; one over the body or set into it does not: a horse's sits on its
					// shoulders over a neck the mod hides with it, and a borrowed head raised into it floated above them. Not
					// one whose borrowed head goes on the shoulders anyway (a baby camel's meets its chest: centred, then put on
					// the shoulders, the borrowed head hung in front of them). A pixel and a quarter, not exactly one: a polar
					// bear's and a baby cow's heads reach exactly a pixel in, and rounding flipped them as the body turned
					boolean apart = !high && torsoBox != null && headBox[1][2] <= torsoBox[0][2] + 1.25F / 16F;
					float[][] seatBox = coreBox(mainHead, neck, true);
					mainNeck = outOfTorso(seat(seatBox != null ? seatBox : local, rest, ownBox, grow, !wholeBody, apart), main, bodyM, high);
					// a head as tall as the per-axis cap leaves a big one (LentHeadSize.fillsPitchedHead: a giant's, a ravager's, a sniffer's, a warden's)
					// on a body whose own head rests pitched far nose down (a hoglin's, a zoglin's): its joint
					// at the back-bottom of the pitched head's level box came out up at the shoulders, and the level head stood on the corner of the
					// hog's torso, a block on the shoulder. It goes where the hog's head is: its middle at the middle of that head, back face behind
					// it (rig, 2026-11-21, 8 pairs). A shorter one (a zombie's, a pig's, a villager's: taller than the hog's 6 pixel block, but not by
					// that much) keeps the seat above (the 'apart' lift)
					// a frog's head part is its whole body (a slab with the eyes on it) and is hidden under a lent head, which sat at its back edge
					// over the rear half and left the front half an open dark bowl (rig, 2026-11-21, frog + warden): centred along it. Any lent head
					// shallower than the slab does it, not the warden's alone: a pig's or a cow's left the same bowl, so the rule is the same for all
					if (state.entityType == net.minecraft.world.entity.EntityTypes.FROG && !wholeBody && ownBox != null) {
						float lentDepth = (ownBox[1][2] - ownBox[0][2]) * grow;
						if (lentDepth < 0.9F * (local[1][2] - local[0][2])) mainNeck.z = rest.transformPosition(middle(local)).z + lentDepth / 2F;
					}
					if (mainPitched && !wholeBody && !high && ownBox != null) {
						float lentHeight = (ownBox[1][1] - ownBox[0][1]) * grow, lentDepth = (ownBox[1][2] - ownBox[0][2]) * grow;
						if (LentHeadSize.fillsPitchedHead(lentHeight, Math.max(local[1][0] - local[0][0], Math.max(local[1][1] - local[0][1], local[1][2] - local[0][2])))) {
							Vector3f centre = rest.transformPosition(middle(local));
							mainNeck = new Vector3f(centre.x, centre.y + lentHeight / 2F, centre.z + lentDepth / 2F);
						}
					}
					if (high) {
						// on the shoulders also where its own head sits above them: a horse's is tilted down at rest, and the back
						// of it is up at the top of the neck the mod hides with it, so a borrowed head set there floated. Put
						// there, it nods in place, about the middle of its bottom: turned about the main head's joint, deep in the
						// chest, it swung off the shoulders and into a camel's hump; about its back edge, a horse looking up lifted
						// its front off the shoulders
						mainNeck.y = torsoBox[0][1];
						Vector3f nod = new Vector3f(mainNeck);
						if (!wholeBody) nod.z -= (ownBox[1][2] - ownBox[0][2]) * grow / 2F;
						Matrix4f spin = new Matrix4f(turnHead).setTranslation(0F, 0F, 0F);
						turnHead = new Matrix4f().translation(nod).mul(spin).translate(-nod.x, -nod.y, -nod.z);
					}
				}
				// a head that rests pitched far nose down (a hoglin's, a zoglin's) is put level (level, above), about the point it meets its neck
				Matrix4f place = new Matrix4f(bodyM).mul(turnHead).translate(mainNeck)
						.scale(grow).rotateX(-level).translate(-ownNeck.x, -ownNeck.y, -ownNeck.z).mul(bodyB.invert(new Matrix4f()));
				// a head with a head part on a slime, a magma cube or a sulfur cube ends with the bottom of its big blocks on the top it stands on, with no air
				// between: a sniffer's head hovered about 0.1 block over a magma cube and a sulfur cube, only the strands of its ears reaching them (rig,
				// 2026-11-22, round 6). Measured as placed, so whatever the seat's own numbers came to, a gap is closed (and never a head lifted)
				if (!Float.isNaN(seatTop) && !wholeBody && (state.entityType == net.minecraft.world.entity.EntityTypes.MAGMA_CUBE
						|| state.entityType == net.minecraft.world.entity.EntityTypes.SULFUR_CUBE || state.entityType == net.minecraft.world.entity.EntityTypes.SLIME)) {
					// the cubes as placed, in the body's frame, without a copy of the list: a frame that takes the placement into account (every frame)
					float[][] seated = coreBox(cubes, new Matrix4f(place).invert().mul(bodyM));
					if (seated != null) {
						float down = LentHeadSize.closeGap(seated[1][1], seatTop, SEAT_SUNK_PX * pixel(main));
						if (down != 0F) place = new Matrix4f(bodyM).translate(0F, down, 0F).mul(new Matrix4f(bodyM).invert()).mul(place);
					}
				}
				Matrix3f turn = place.get3x3(new Matrix3f()).scale(1F / grow);
				for (Cube c : cubes) placed.add(new Cube(new Matrix4f(place).mul(c.pose()), new Matrix3f(turn).mul(c.normal()), c.cube()));
			}
		}
		// ears on the main head (not in First-person Model's body view, where the head is the camera); a shulker's head is
		// inside its shell, and they stay in there with it
		if (!headSwap && withHead && mark.morph$lendsEars()) {
			int from = placed.size();
			// a ghast and a happy ghast too: their heads are what the ears grow with, as on a giant (ghast:fox ears were two 0.1 block specks)
			placeEars(lent, main, state instanceof EnderDragonRenderState || LentHeadSize.growsParts(state.entityType), earSeat(state, main), placed);
			if (shellHead != null) keepInShell(shellHead, placed, from);
		}
		// the parts; a whole body lent as a head (a slime) has none to give, and a shulker body has no torso to take them
		if (mainTorso != null && (!headSwap || headPath(lent.root()) != null)) {
			float[][] ownTorso = torso(lent.root(), local(lent.base(), lent.unturn()), true);
			if (ownTorso != null) {
				// the dragon's parts are scaled to the body they go on (my choice); everyone else's keep their size, except on a huge
				// body (a giant, a ghast), where they grow with it (LentHeadSize.partScale)
				float scale = borrowed instanceof EnderDragonRenderState ? Graft.fit(ownTorso, mainTorso)
						: LentHeadSize.partScale(LentHeadSize.growsParts(state.entityType), Graft.fit(ownTorso, mainTorso));
				// and none is bigger than a tiny body can carry (a silverfish's, an endermite's, a tadpole's)
				float[] limit = tinyMainLimit(main, lent);
				scale = Math.min(scale, limit[0]);
				// a shell body (a nautilus's) has its face at the open end of the shell, so a lent tail goes on the closed end, pointing away; a lent
				// bee's flat wing discs stand up and out of the back as a pegasus's wings do; a tadpole's own tail runs past its body, so a lent tail
				// goes on the end of that (Graft.place)
				boolean shellMain = shellPath(main.root()) != null;
				boolean liftWings = borrowed.entityType == net.minecraft.world.entity.EntityTypes.BEE;
				boolean tadpoleMain = state.entityType == net.minecraft.world.entity.EntityTypes.TADPOLE;
				// a vex's wing planes stand up from a big main's back as the bee's discs do: edge-on and laid out flat they were 0.2 to 0.3 block slivers
				// (rig, 2026-11-21, round 5, ravager and sniffer + vex)
				float mainLongest = Math.max(mainTorso[1][0] - mainTorso[0][0], Math.max(mainTorso[1][1] - mainTorso[0][1], mainTorso[1][2] - mainTorso[0][2]));
				boolean vex = borrowed.entityType == net.minecraft.world.entity.EntityTypes.VEX;
				liftWings |= vex && mainLongest >= LentHeadSize.BIG_MAIN;
				Graft graft = new Graft(ownTorso, mainTorso, lent.unturn(), main.unturn(), scale, everything(lent), everything(main), limit[0], limit[1],
						lent.base().last().pose().getScale(new Vector3f()).y, shellMain, liftWings, tadpoleMain, ownTailBox(state, main), ownTipBox(state, main),
						mainLongest <= LentHeadSize.SMALL_TORSO ? restHeight(main) : 0F, vex, lyingBlockMain(state));
				findAppendages(lent.root(), copy(lent.base()), graft, placed);
			}
		}
		return placed.isEmpty() ? null : new Placed(placed, lent.type(), fishOf(borrowed));
	}

	/**
	 * A shulker body's inner head: its posed frame (rigid; model axes, +y down, its origin the head's pivot), the box of its
	 * cube there, and one of the shulker model's pixels there, in blocks.
	 */
	private record Inner(Matrix4f frame, float[][] box, float pixel) {}

	private static @Nullable Inner inner(Body main) {
		List<Cube> head = new ArrayList<>();
		Matrix4f frame = headCubes(main.root(), main.base(), head);
		float[][] box = frame == null ? null : coreBox(head, frame);
		return box == null ? null : new Inner(frame, box, main.base().last().pose().getScale(new Vector3f()).y / 16F);
	}

	/**
	 * A borrowed head on a shulker body goes inside the shell, in place of the shulker's own inner head (my design:
	 * "a little creeper head" that pops out when the lid opens, a surprise inside, whatever mob it is). Its big blocks, as
	 * it sits on its own body, are centred where the shulker's head cube is and sized to fit that cube, proportions kept
	 * (a creeper's 8-pixel head is drawn 6 pixels across), in that head's posed frame: so it turns as the shulker's head
	 * turns (the yaw ShulkerLock.pin gives it, in the frame of the face the shell holds on to), and stays where that head
	 * is while the lid lifts around it. Drawn with depth, as every borrowed head is, the shut shell hides it and the open
	 * one shows it. Its ears, horns and nose come along at the same size; where they would reach out of the shut shell,
	 * the head is shrunk a little more ({@link #keepInShell}).
	 */
	private static void inShell(Inner inner, Body lent, List<Cube> cubes, List<Cube> out) {
		Matrix4f bodyB = rigid(lent.base().last().pose());
		float[][] own = coreBox(cubes, bodyB, true);
		if (own == null) return;
		Vector3f middle = middle(inner.box()), ownMiddle = middle(own);
		float fit = Float.MAX_VALUE;
		for (int axis = 0; axis < 3; axis++) {
			fit = Math.min(fit, (inner.box()[1][axis] - inner.box()[0][axis]) / Math.max(1.0E-4F, own[1][axis] - own[0][axis]));
		}
		// the borrowed body's own frame onto the inner head's, model axes to model axes: its face (-z) goes where the inner
		// head looks, its top (-y) out of the shell's opening
		Matrix4f place = new Matrix4f(inner.frame()).translate(middle).scale(fit).translate(-ownMiddle.x, -ownMiddle.y, -ownMiddle.z)
				.mul(bodyB.invert(new Matrix4f()));
		Matrix3f turn = place.get3x3(new Matrix3f()).scale(1F / fit);
		int from = out.size();
		for (Cube c : cubes) out.add(new Cube(new Matrix4f(place).mul(c.pose()), new Matrix3f(turn).mul(c.normal()), c.cube()));
		keepInShell(inner, out, from, middle);
	}

	/** Borrowed ears on a shulker body's own inner head (cubes from {@code from} on): kept in the shut shell with it. */
	private static void keepInShell(Inner inner, List<Cube> cubes, int from) {
		if (from >= cubes.size()) return;
		float[][] box = inner.box();
		keepInShell(inner, cubes, from, new Vector3f((box[0][0] + box[1][0]) / 2F, box[0][1], (box[0][2] + box[1][2]) / 2F));
	}

	/**
	 * Cubes from {@code from} on, put in a shulker's inner head: shrunk about {@code about} (the head frame's coordinates)
	 * as far as it takes to keep all of them inside the shut shell ({@link #SHELL_UP}, {@link #SHELL_DOWN} from the head's
	 * pivot, {@link #SHELL_OUT} out from its middle, whichever way the head turns). At the size the small inner head asks
	 * for, a rabbit's or a donkey's ears reached up through the shut lid, and a squid's tentacles out of the bottom. Measured
	 * in the head's own frame, so the size never changes as it turns.
	 */
	private static void keepInShell(Inner inner, List<Cube> cubes, int from, Vector3f about) {
		Matrix4f inverse = new Matrix4f(inner.frame()).invert();
		float up = 0F, down = 0F, out = 0F;
		for (int i = from; i < cubes.size(); i++) {
			Cube c = cubes.get(i);
			Matrix4f toLocal = new Matrix4f(inverse).mul(c.pose());
			for (ModelPart.Polygon polygon : c.cube().polygons) {
				for (ModelPart.Vertex v : polygon.vertices()) {
					Vector3f p = toLocal.transformPosition(new Vector3f(v.x() / 16F, v.y() / 16F, v.z() / 16F));
					up = Math.max(up, about.y - p.y);
					down = Math.max(down, p.y - about.y);
					out = Math.max(out, (float) Math.hypot(p.x - about.x, p.z - about.z));
				}
			}
		}
		float px = inner.pixel(), share = 1F;
		if (up > 0F) share = Math.min(share, (about.y + SHELL_UP * px) / up);
		if (down > 0F) share = Math.min(share, (SHELL_DOWN * px - about.y) / down);
		if (out > 0F) share = Math.min(share, (SHELL_OUT * px - (float) Math.hypot(about.x, about.z)) / out);
		if (share >= 1F) return;
		Matrix4f shrink = new Matrix4f(inner.frame()).translate(about).scale(Math.max(0.05F, share)).translate(-about.x, -about.y, -about.z).mul(inverse);
		for (int i = from; i < cubes.size(); i++) {
			Cube c = cubes.get(i);
			cubes.set(i, new Cube(new Matrix4f(shrink).mul(c.pose()), c.normal(), c.cube()));
		}
	}

	private static Vector3f middle(float[][] box) {
		return new Vector3f((box[0][0] + box[1][0]) / 2F, (box[0][1] + box[1][1]) / 2F, (box[0][2] + box[1][2]) / 2F);
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
	private static @Nullable Matrix4f neckFrame(ModelPart root, PoseStack base, boolean allHead) {
		PoseStack ps = copy(base);
		List<ModelPart> path = allHead ? null : headPath(root);
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

	/**
	 * A body's height (blocks as drawn, its own frame, y up) with everything it has but its wings: the head, the torso, a blaze's rods. Its wings
	 * flap and its pose changes (an allay tips 45 degrees as it flies, a bat hangs upside down at rest), and a height that followed them would
	 * breathe, popping LentHeadSize.partReference's choice between the torso and the height as the pose crosses its line. So it is the height at the
	 * model's initial pose, measured once per model and scaled by the size the body is drawn at (as {@link #restAppendageExtent} is); 0 for none.
	 */
	private static float restHeight(Body body) {
		float size = body.base().last().pose().getScale(new Vector3f()).y;
		if (size <= 1e-4F) return 0F;
		Float unit = REST_HEIGHTS.get(body.root());
		if (unit == null) {
			float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
			float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
			atRest(body.root(), () -> unwinged(body.root(), local(body.base(), body.unturn()), lo, hi));
			if (lo[1] > hi[1]) return 0F; // nothing visible yet: asked again next frame
			unit = (hi[1] - lo[1]) / size;
			REST_HEIGHTS.put(body.root(), unit);
		}
		return unit * size;
	}

	/** Each model's height without its wings at rest, per model, in blocks at size 1 ({@link #restHeight}). */
	private static final Map<ModelPart, Float> REST_HEIGHTS = new java.util.WeakHashMap<>();

	private static void unwinged(ModelPart part, PoseStack pose, float[] lo, float[] hi) {
		if (!part.visible) return;
		pose.pushPose();
		part.translateAndRotate(pose);
		cubeBounds(part, pose, lo, hi);
		for (Map.Entry<String, ModelPart> child : ((ModelPartAccessor) (Object) part).morph$children().entrySet()) {
			if (!child.getKey().contains("wing")) unwinged(child.getValue(), pose, lo, hi);
		}
		pose.popPose();
	}

	/**
	 * The box {lo, hi} (the body's own frame, y up) of the tail a lent tail is rooted on, where the main has one of its own that runs past the
	 * torso: the first segment of the ender dragon's tail chain, whose trunk ends inside it, and a tadpole's tail slab. Null for any other body.
	 */
	private static float @Nullable [][] ownTailBox(EntityRenderState state, Body main) {
		String name = state instanceof EnderDragonRenderState ? "tail0" : state.entityType == net.minecraft.world.entity.EntityTypes.TADPOLE ? "tail" : null;
		return partBox(main, name);
	}

	/**
	 * The box {lo, hi} (the body's own frame, y up) of the last segment of the ender dragon's tail chain, where a hanging lent tail is rooted
	 * ({@link Graft#place}); null for any other body.
	 */
	private static float @Nullable [][] ownTipBox(EntityRenderState state, Body main) {
		return partBox(main, state instanceof EnderDragonRenderState ? "tail11" : null);
	}

	/** The box of the part called {@code name} (its own cubes, as posed) in the body's own frame, or null for none. */
	private static float @Nullable [][] partBox(Body main, @Nullable String name) {
		if (name == null) return null;
		List<ModelPart> path = find(main.root(), name, new ArrayList<>());
		if (path == null) return null;
		PoseStack ps = copy(local(main.base(), main.unturn()));
		for (ModelPart part : path) part.translateAndRotate(ps);
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		cubeBounds(path.getLast(), ps, lo, hi);
		return lo[0] > hi[0] ? null : new float[][] {lo, hi};
	}

	private static float volume(ModelPart.Cube cube) {
		return (cube.maxX - cube.minX) * (cube.maxY - cube.minY) * (cube.maxZ - cube.minZ);
	}

	/**
	 * The middle of the top of a body without a head part (a slime), in its frame: where a borrowed head sits on it. Its height is the
	 * top of everything but a silverfish's back plates (see {@link #collectCore}); the middle is that of its big blocks, so a stinger, a
	 * fin or a tail does not pull a head toward it, and a silverfish's head goes over its thick middle segment and not over the
	 * middle of its whole length (rig, 2026-11-19: heads and whole creatures sat on the plate tips, toward the tail).
	 */
	private static Vector3f topOf(Body body, @Nullable List<Cube> collected) {
		List<Cube> all = collected;
		if (all == null) {
			all = new ArrayList<>();
			collectCore(body.root(), copy(body.base()), all);
		}
		Matrix4f inverse = rigid(body.base().last().pose()).invert();
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		float[] coreLo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] coreHi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		float biggest = 0, biggestFoot = 0;
		for (Cube c : all) {
			if (volume(c.cube()) > biggest) {
				biggest = volume(c.cube());
				float[][] b = cubeBox(c, inverse);
				biggestFoot = (b[1][0] - b[0][0]) * (b[1][2] - b[0][2]);
			}
		}
		float pixel = pixel(body);
		for (Cube c : all) {
			// a plate a pixel thick or more that covers most of the body is the body's top too: the elder guardian's 12 x 2 x 12 top plate is 12
			// percent of its volume, and heads, ears and whole creatures sat on the cube under it, 0.1 to 0.3 block down in it (rig, 2026-11-20)
			float[][] b = cubeBox(c, inverse);
			boolean plate = b[1][1] - b[0][1] >= pixel - 1e-4F && (b[1][0] - b[0][0]) * (b[1][2] - b[0][2]) >= 0.6F * biggestFoot;
			boolean big = volume(c.cube()) >= biggest * 0.25F || plate;
			Matrix4f toLocal = new Matrix4f(inverse).mul(c.pose());
			for (ModelPart.Polygon polygon : c.cube().polygons) {
				for (ModelPart.Vertex v : polygon.vertices()) {
					Vector3f p = toLocal.transformPosition(new Vector3f(v.x() / 16F, v.y() / 16F, v.z() / 16F));
					lo[0] = Math.min(lo[0], p.x); lo[2] = Math.min(lo[2], p.z);
					hi[0] = Math.max(hi[0], p.x); hi[2] = Math.max(hi[2], p.z);
					if (big) {
						// the top is the big blocks' too: a fish's flat dorsal fin (no thickness, so not a big block) set the top, 0.17 to 0.25
						// block over its back, and the head sat on its tip with water under it (rig, 2026-11-20, about 80 pairs)
						lo[1] = Math.min(lo[1], p.y); hi[1] = Math.max(hi[1], p.y);
						coreLo[0] = Math.min(coreLo[0], p.x); coreLo[2] = Math.min(coreLo[2], p.z);
						coreHi[0] = Math.max(coreHi[0], p.x); coreHi[2] = Math.max(coreHi[2], p.z);
					}
				}
			}
		}
		if (lo[0] > hi[0]) return new Vector3f();
		return new Vector3f((coreLo[0] + coreHi[0]) / 2F, lo[1], (coreLo[2] + coreHi[2]) / 2F); // model axes: up is -y
	}

	/**
	 * Where a head meets its neck, in its own frame (model axes: +y down, -z forward): the middle of the bottom of its
	 * cubes, at the back ({@code back}) or halfway.
	 */
	private static Vector3f neckPoint(List<Cube> cubes, Matrix4f frame, boolean back) {
		float[][] box = coreBox(cubes, frame, true);
		if (box == null) return new Vector3f();
		float[] lo = box[0], hi = box[1];
		return new Vector3f((lo[0] + hi[0]) / 2F, hi[1], back ? hi[2] : (lo[2] + hi[2]) / 2F);
	}

	/**
	 * Where a borrowed head meets its neck, as {@link #neckPoint} with {@code back}, but taken in the head part's own frame
	 * ({@code own}) and brought into the body's ({@code bodyB}): the middle of the bottom of the head's back, which is its joint.
	 * In the body's frame a head held pitched at rest (a hoglin's 50 degrees nose down, a goat's) has the bottom of the box
	 * round it at its snout, and a head seated by that corner rested on its snout with the rest of it up in the air (rig,
	 * 2026-11-18). Heads that rest level are measured the same either way. Boxes of the cubes as the model defines them, with no
	 * overlay growth: a hat layer's half pixel lifted a head over the shoulders it sat on.
	 */
	private static Vector3f neckJoint(List<Cube> cubes, Matrix4f own, Matrix4f bodyB, Set<ModelPart.Cube> hats) {
		// not the hat layer where it reaches under the head: an illusioner's is a 12 pixel box over a 10 pixel head, 2 pixels under the
		// chin, and the head hovered a model pixel over the shoulders (rig, 2026-11-20). Every other hat (a humanoid's layer, a villager's
		// brim, a witch's 10 pixel brim over its 8 pixel head) stops at the chin or above it and counts as it always did, so no settled
		// pair moves
		List<Cube> head = cubes;
		if (!hats.isEmpty()) {
			List<Cube> bare = new ArrayList<>();
			for (Cube c : cubes) {
				if (!hats.contains(c.cube())) bare.add(c);
			}
			if (!bare.isEmpty()) {
				float[][] withHats = coreBox(cubes, own, true), without = coreBox(bare, own, true);
				if (withHats != null && without != null && withHats[1][1] > without[1][1] + 1F / 64F) head = bare;
			}
		}
		float[][] box = coreBox(head, own, true);
		if (box == null) return neckPoint(cubes, bodyB, true);
		Vector3f joint = new Vector3f((box[0][0] + box[1][0]) / 2F, box[1][1], box[1][2]);
		// a head whose big block is turned from the head part (a goat's nose is pitched 55 degrees, and it is the biggest block): the
		// bottom of the box round it is air, 8 pixels up from the block's own bottom edge, and the head was seated that much too high.
		// Its joint is the middle of the bottom of that block's back, in the block's own axes
		Matrix4f inOwn = new Matrix4f(own).invert();
		Cube big = null;
		for (Cube c : head) {
			if (!brim(c, inOwn) && (big == null || volume(c.cube()) > volume(big.cube()))) big = c;
		}
		if (big != null) {
			Matrix4f toOwn = new Matrix4f(inOwn).mul(big.pose());
			Vector3f up = toOwn.transformDirection(new Vector3f(0F, 1F, 0F)).normalize();
			if (up.y < Math.cos(Math.toRadians(10))) {
				ModelPart.Cube k = big.cube();
				joint = toOwn.transformPosition(new Vector3f((k.minX + k.maxX) / 32F, k.maxY / 16F, k.maxZ / 16F));
			}
		}
		return new Matrix4f(bodyB).invert().mul(own).transformPosition(joint);
	}

	/**
	 * The cubes of the parts called "hat" on a head (a child of the head part, or beside it), by identity: for a body with none, empty.
	 * An illusioner's hood is the one that reaches below the head.
	 */
	private static Set<ModelPart.Cube> hatCubes(ModelPart root) {
		// a model's hats never change, and this is asked for every lent head every frame: once per model
		return HATS.computeIfAbsent(root, HybridMorph::findHatCubes);
	}

	private static final Map<ModelPart, Set<ModelPart.Cube>> HATS = new java.util.WeakHashMap<>();

	private static Set<ModelPart.Cube> findHatCubes(ModelPart root) {
		Set<ModelPart.Cube> out = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		List<ModelPart> path = headPath(root);
		if (path == null) return out;
		if (path.size() > 1) {
			ModelPart beside = ((ModelPartAccessor) (Object) path.get(path.size() - 2)).morph$children().get("hat");
			if (beside != null) addAllCubes(beside, out);
		}
		hatsIn(path.getLast(), out);
		return out;
	}

	private static void hatsIn(ModelPart part, Set<ModelPart.Cube> out) {
		for (Map.Entry<String, ModelPart> child : ((ModelPartAccessor) (Object) part).morph$children().entrySet()) {
			if (child.getKey().equals("hat")) addAllCubes(child.getValue(), out);
			else hatsIn(child.getValue(), out);
		}
	}

	/**
	 * Where a borrowed head meets the main body ({@code bodyM}'s frame, model axes: +y down, -z forward), before it is
	 * lifted out of the torso. Measured in the main head's own frame ({@code box}: its big blocks there; {@code rest}: that
	 * frame at rest, in bodyM's): the middle of the bottom of its big blocks, at the back ({@code back}) or halfway. Where the main head is in front of the body
	 * ({@code apart}) and is bigger than the borrowed one as it is drawn ({@code grow}), the borrowed one goes to the
	 * middle of it, up and forward, not its bottom back corner. Measured in the body's frame instead, a tilted head's box
	 * reached down to its snout (on a hoglin, a borrowed head hung between the forelegs), and a big head's bottom left a
	 * small one dangling under the neck (a ravager's) or under the fur (a sniffer's); I wanted it where the body's
	 * own head sits. A head over the body keeps its bottom: moved up into a horse's head, whose neck the mod hides with
	 * it, a borrowed head floated above the shoulders.
	 */
	private static Vector3f seat(float[][] box, Matrix4f rest, float @Nullable [][] own, float grow, boolean back, boolean apart) {
		float[] lo = box[0], hi = box[1];
		Vector3f local = new Vector3f((lo[0] + hi[0]) / 2F, hi[1], back ? hi[2] : (lo[2] + hi[2]) / 2F);
		if (apart && own != null) {
			float up = ((hi[1] - lo[1]) - (own[1][1] - own[0][1]) * grow) / 2F;
			if (up > 0F) local.y -= up;
			if (back) {
				float forward = ((hi[2] - lo[2]) - (own[1][2] - own[0][2]) * grow) / 2F;
				if (forward > 0F) local.z -= forward;
			}
		}
		return rest.transformPosition(local);
	}

	/**
	 * The head part's frame (rigid) at rest, where it is now: the parts hidden with the head (named as heads, as
	 * FirstPersonMorph hides them: a horse's head_parts, which hold its neck and take its look, and the head in them) at
	 * their rest turns, the parts above them as they are posed (an axolotl's body turns with the look, its head with it).
	 */
	private static @Nullable Matrix4f restFrame(ModelPart root, PoseStack base) {
		List<ModelPart> path = headPath(root);
		if (path == null) return null;
		PoseStack frame = copy(base);
		for (int i = 0; i < path.size(); i++) {
			ModelPart part = path.get(i);
			String name = null;
			if (i > 0) {
				for (Map.Entry<String, ModelPart> child : ((ModelPartAccessor) (Object) path.get(i - 1)).morph$children().entrySet()) {
					if (child.getValue() == part) name = child.getKey();
				}
			}
			if (name == null || !FirstPersonMorph.faceName(name)) {
				part.translateAndRotate(frame);
				continue;
			}
			float xRot = part.xRot, yRot = part.yRot, zRot = part.zRot;
			net.minecraft.client.model.geom.PartPose rest = part.getInitialPose();
			part.xRot = rest.xRot();
			part.yRot = rest.yRot();
			part.zRot = rest.zRot();
			try {
				part.translateAndRotate(frame);
			} finally {
				part.xRot = xRot;
				part.yRot = yRot;
				part.zRot = zRot;
			}
		}
		return rigid(frame.last().pose());
	}

	/** A box {lo, hi} in a frame's own coordinates, as the box around it in the coordinates {@code frame} maps it to. */
	private static float[][] boxIn(float[][] box, Matrix4f frame) {
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (int i = 0; i < 8; i++) {
			Vector3f p = frame.transformPosition(new Vector3f(box[i & 1][0], box[(i >> 1) & 1][1], box[(i >> 2) & 1][2]));
			lo[0] = Math.min(lo[0], p.x); lo[1] = Math.min(lo[1], p.y); lo[2] = Math.min(lo[2], p.z);
			hi[0] = Math.max(hi[0], p.x); hi[1] = Math.max(hi[1], p.y); hi[2] = Math.max(hi[2], p.z);
		}
		return new float[][] {lo, hi};
	}

	/**
	 * A neck point ({@code bodyM}'s frame, model axes: +y down, -z forward) moved out of the body's torso, up or forward,
	 * whichever is nearer, or up ({@code high}): a camel's and a llama's head parts hold their long necks, whose bottom is
	 * deep in the chest, and a panda's head set there sank into the camel (I saw it). Moved forward, the nearer
	 * way, a borrowed head sat low on their chests; their own heads are held high, so it goes on the shoulders.
	 */
	private static Vector3f outOfTorso(Vector3f neck, Body main, Matrix4f bodyM, boolean high) {
		float[][] torso = torsoIn(main, bodyM);
		if (torso == null) return neck;
		float[] lo = torso[0], hi = torso[1];
		float inside = 0.01F;
		if (neck.x <= lo[0] + inside || neck.x >= hi[0] - inside || neck.y <= lo[1] + inside || neck.y >= hi[1] - inside
				|| neck.z <= lo[2] + inside || neck.z >= hi[2] - inside) return neck;
		float up = neck.y - lo[1], forward = neck.z - lo[2];
		// a tie goes up, whatever the floats' last digits say: a sheep's and a baby goat's are ties, and the plugin, which
		// works from rounded numbers, could only guess which way each had gone (tools/gen_head_pack.py, BorrowedHeads)
		return high || up <= forward + 1e-3F ? new Vector3f(neck.x, lo[1], neck.z) : new Vector3f(neck.x, neck.y, lo[2]);
	}

	/** The main body's torso box {lo, hi} in {@code bodyM}'s frame (model axes), or null without one. */
	private static float @Nullable [][] torsoIn(Body main, Matrix4f bodyM) {
		PoseStack inBody = new PoseStack();
		inBody.last().pose().set(bodyM).invert().mul(main.base().last().pose());
		return torso(main.root(), inBody);
	}

	/** A hat's brim: a flat sheet (under 1.5 pixels thick) at least 12 pixels across both ways, in the head's frame. */
	private static boolean brim(Cube c, Matrix4f inverse) {
		float[][] box = cubeBox(c, inverse);
		float x = box[1][0] - box[0][0], y = box[1][1] - box[0][1], z = box[1][2] - box[0][2];
		return y < 1.5F / 16F && Math.min(x, z) >= 12F / 16F;
	}

	/**
	 * The sides (x, y, z, blocks as drawn; {@code pixel}: one model pixel of the body) of the biggest block of a head, as the model defines it,
	 * when that block is turned 40 degrees or more from its head part's axes (a goat's nose block); null for a head whose big block is not.
	 * {@code frame}: the head part's posed frame, in which the cubes' poses are taken back to the part's own axes.
	 */
	private static float @Nullable [] tiltedBlockSides(List<Cube> head, Matrix4f frame, float pixel) {
		Matrix4f inverse = new Matrix4f(frame).invert();
		Cube big = null;
		for (Cube c : head) {
			if (!brim(c, inverse) && (big == null || volume(c.cube()) > volume(big.cube()))) big = c;
		}
		if (big == null) return null;
		Vector3f up = new Matrix4f(inverse).mul(big.pose()).transformDirection(new Vector3f(0F, 1F, 0F)).normalize();
		if (up.y >= Math.cos(Math.toRadians(40))) return null;
		ModelPart.Cube k = big.cube();
		return new float[] {(k.maxX - k.minX) * pixel, (k.maxY - k.minY) * pixel, (k.maxZ - k.minZ) * pixel};
	}

	/**
	 * Stands a head's biggest block level when it is turned inside its head part, which is not (a goat's: the part is flat and its 5 x 7 x 10 nose
	 * block is on a child turned 55 degrees), by undoing the block's turn about its own pivot; the rest of the head (the horns, the ears) stays
	 * as the model has it. Replaces that cube in {@code head}. Nothing for a head whose big block sits straight in its part (the part's own turn,
	 * a skeleton horse's, is {@link #restPitch}'s and the place() level). {@code own}: the head part's posed frame.
	 */
	private static void levelNose(List<Cube> head, Matrix4f own) {
		Matrix4f inverse = new Matrix4f(own).invert();
		int big = -1;
		for (int i = 0; i < head.size(); i++) {
			Cube c = head.get(i);
			if (!brim(c, inverse) && (big < 0 || volume(c.cube()) > volume(head.get(big).cube()))) big = i;
		}
		if (big < 0) return;
		Cube c = head.get(big);
		Vector3f up = new Matrix4f(inverse).mul(c.pose()).transformDirection(new Vector3f(0F, 1F, 0F)).normalize();
		// turned about the sideways axis only (a quarter of the cos is a side turn this is not made for)
		if (Math.abs(up.x) > 0.2F) return;
		float turn = (float) Math.atan2(up.z, up.y);
		if (Math.abs(turn) <= FLAT_REST) return;
		Cube level = new Cube(new Matrix4f(c.pose()).rotateX(-turn), new Matrix3f(c.normal()).rotateX(-turn), c.cube());
		head.set(big, level);
		// what hangs under the block (a goat's goatee, a cube in the head part itself) was fitted to the tilted block's chin: stood level the block's
		// underside is 2 pixels above it and the goatee hangs free. Lifted to touch the block's underside (model axes: +y is down), only a piece
		// that is under the block's footprint and within a quarter block of it; horns and ears are not under it and stay
		float[][] block = cubeBox(level, inverse);
		for (int i = 0; i < head.size(); i++) {
			if (i == big) continue;
			Cube under = head.get(i);
			float[][] box = cubeBox(under, inverse);
			float gap = box[0][1] - block[1][1];
			boolean beneath = box[0][0] <= block[1][0] && box[1][0] >= block[0][0] && box[0][2] <= block[1][2] && box[1][2] >= block[0][2];
			if (!beneath || gap <= 0.01F || gap > 0.25F) continue;
			Matrix4f lift = new Matrix4f(own).translate(0F, -gap, 0F).mul(inverse).mul(under.pose());
			head.set(i, new Cube(lift, under.normal(), under.cube()));
		}
	}

	/** A head's size: the cube root of its core box's volume. */
	private static float headSize(List<Cube> cubes, Matrix4f frame) {
		float[][] box = coreBox(cubes, frame);
		return box == null ? 0F : (float) Math.cbrt((box[1][0] - box[0][0]) * (box[1][1] - box[0][1]) * (box[1][2] - box[0][2]));
	}

	/**
	 * The box {lo, hi} of a body's own head as it sits at rest ({@code restNeck}: restFrame), its big blocks in the body's frame
	 * {@code bodyM}, which the plugin's table of heads holds the same way (tools/gen_head_pack.py); the head's size is the side of
	 * the cube of its volume (place() takes it from here). Not as it is posed: the box round a head that is turned (a giant's looking
	 * up, a dragon's hanging 45 degrees down from its neck) is bigger than the head, by up to 1.26 times across, and a ghast lent to the
	 * body was drawn that much bigger than players without the mod see it, and bigger as you looked up (2026-11-17, rig B3-08).
	 * Null for a body with no head part.
	 */
	private static float @Nullable [][] restHeadBox(List<Cube> head, Matrix4f neck, @Nullable Matrix4f restNeck, Matrix4f bodyM, Set<ModelPart.Cube> hats) {
		if (restNeck == null || head.isEmpty()) return null;
		float[][] local = coreBox(head, neck);
		if (local == null) return null;
		// a hat is not the head: held to the head with it, a wither's, a ravager's or a llama's was twice the bare head with a witch's cone
		// (rig, 2026-11-21, 4 pairs), and a wither's on a wither skeleton 1.7 to 1.8 times its visible head, over the 1.5 cap, because the
		// 0.5 pixel overlay layer of a humanoid's hat was in the box (round 4). The reference is the bare head whenever there is one: lent
		// heads on a humanoid that sat at the cap come out up to 12 percent smaller. The plugin's table holds the same bare head (`opaque`,
		// without the inflated copy), and BorrowedHeads.bareSide takes the main's reference from it, so the pack view holds the same size
		if (!hats.isEmpty()) {
			List<Cube> bare = new ArrayList<>(head.size());
			for (Cube c : head) {
				if (!hats.contains(c.cube())) bare.add(c);
			}
			float[][] without = bare.isEmpty() ? null : coreBox(bare, neck);
			if (without != null) local = without;
		}
		// a head that rests pitched far nose down (a hoglin's and a zoglin's 50 degrees) is measured level, in its own frame: the borrowed head
		// it is compared with is put level too, and the box round the tilted one is 18 pixels tall for a 6 pixel snout (4.4 times the head
		// height let in a ravager's head as tall as the hoglin's body; rig, 2026-11-20)
		if (LentHeadSize.levelPitch(restPitch(bodyM, restNeck)) != 0F) return local;
		return boxIn(local, new Matrix4f(bodyM).invert().mul(restNeck));
	}

	/** A flat tail counts in a lent whole creature's length when it runs past the solid body by more than this share of the body's depth ({@link #wholeBox}). */
	private static final float FLAT_TAIL = 1F;

	/** How far (model pixels) the bottom of a lent head's big blocks ends inside the top it stands on, where a gap under it is closed (LentHeadSize.closeGap). */
	private static final float SEAT_SUNK_PX = 0.5F;

	/** A head resting this far (radians, about 3 degrees) off level counts as tilted, for the bodies that put a lent head level ({@link #place}). */
	private static final float FLAT_REST = 0.05F;

	/**
	 * The box {lo, hi} of everything a creature lent whole draws, in {@code frame}: tentacles, legs and the soft body included. A squid's mantle
	 * is 12 pixels and its tentacles hang 18 more. Flat planes with no thickness (a strider's bristles, a fish's fins) are not counted, but for
	 * one along the front to back axis: a flat tail that runs past the solid body by more than that body is deep (a tadpole's) lengthens it
	 * ({@link #FLAT_TAIL}). A tropical fish's tail fin, shorter than its body, is not counted, as before.
	 */
	private static float @Nullable [][] wholeBox(List<Cube> cubes, Matrix4f frame) {
		Matrix4f inverse = new Matrix4f(frame).invert();
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		float flatLo = Float.MAX_VALUE, flatHi = -Float.MAX_VALUE;
		for (Cube c : cubes) {
			float[][] box = cubeBox(c, inverse);
			if (volume(c.cube()) <= 1e-4F) {
				// a flat plane (a tadpole's tail slab) counts along its length, not across: it is how far the creature runs, and a
				// whole tadpole on a pufferfish or a parrot ran 1.5 to 2 times the head's length behind it (rig, 2026-11-21)
				flatLo = Math.min(flatLo, box[0][2]);
				flatHi = Math.max(flatHi, box[1][2]);
				continue;
			}
			for (int axis = 0; axis < 3; axis++) {
				lo[axis] = Math.min(lo[axis], box[0][axis]);
				hi[axis] = Math.max(hi[axis], box[1][axis]);
			}
		}
		if (lo[0] > hi[0]) return null;
		// only a tail that outruns the body it hangs from (a tadpole's, twice its body): the settled pairs with a fish's short tail keep their size
		float depth = hi[2] - lo[2];
		if (flatHi - hi[2] > FLAT_TAIL * depth) hi[2] = flatHi;
		if (lo[2] - flatLo > FLAT_TAIL * depth) lo[2] = flatLo;
		return new float[][] {lo, hi};
	}

	/**
	 * How far the front of a lent head dips under the plane it is hinged on, in blocks as drawn: the head's big blocks placed as
	 * {@link #place} puts them, about {@code ownNeck} (its joint, in its body's frame {@code bodyB}) at {@code grow} and levelled by {@code level},
	 * measured from the joint down (model axes: +y is down). 0 for a head that rests level.
	 */
	private static float dipBelow(List<Cube> cubes, Matrix4f bodyB, Vector3f ownNeck, float grow, float level, Set<ModelPart.Cube> hats) {
		// the head as the joint is measured (neckJoint): as the model defines its cubes, and not the hat layer, which reaches under the chin
		List<Cube> head = new ArrayList<>(cubes.size());
		for (Cube c : cubes) {
			if (!hats.contains(c.cube())) head.add(c);
		}
		Matrix4f toBody = new Matrix4f().scale(grow).rotateX(-level).translate(-ownNeck.x, -ownNeck.y, -ownNeck.z).mul(bodyB.invert(new Matrix4f()));
		float[][] box = coreBox(head.isEmpty() ? cubes : head, toBody.invert(new Matrix4f()), true);
		return box == null ? 0F : Math.max(0F, box[1][1]);
	}

	/** The x, y and z sides of a box {lo, hi}, or null for none. */
	private static float @Nullable [] sides(float @Nullable [][] box) {
		return box == null ? null : new float[] {box[1][0] - box[0][0], box[1][1] - box[0][1], box[1][2] - box[0][2]};
	}

	/**
	 * How far nose down a head frame ({@code own}, a head part's posed frame) is as its own body ({@code bodyB}) holds it, in radians: the
	 * height its forward direction has in the body's frame (model axes: +y down, -z forward). 0 for a level head.
	 */
	private static float restPitch(Matrix4f bodyB, Matrix4f own) {
		Vector3f forward = new Matrix4f(bodyB).invert().mul(own).transformDirection(new Vector3f(0F, 0F, -1F));
		return LentHeadSize.pitchOf(forward.x, forward.y, forward.z);
	}

	/** The box {lo, hi} of a head's big blocks in {@code frame}, or null for none. */
	private static float @Nullable [][] coreBox(List<Cube> cubes, Matrix4f frame) {
		return coreBox(cubes, frame, false);
	}

	/**
	 * {@code modelled}: each cube as the model defines it, without the growth an overlay layer gets (a zombie's or
	 * skeleton's hat layer is grown half a pixel all round and is see-through: counted, the head inside a shulker's shell
	 * was sized to 9 pixels and drawn 5.3 across instead of 6).
	 */
	private static float @Nullable [][] coreBox(List<Cube> cubes, Matrix4f frame, boolean modelled) {
		Matrix4f inverse = new Matrix4f(frame).invert();
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		// only the head's big blocks: a guardian's spikes, a goat's beard and a ghast's tentacles hang below the head
		// and would leave it floating over the neck. Not a hat's brim either: every villager's head has one (drawn only
		// under a profession's hat, which a borrowed head never wears), and it made the head 16 pixels deep, so a
		// villager's head sat forward of its neck and ears on it were twice their size (tools/gen_head_pack.py leaves
		// brims out for players without the mod the same way)
		float biggest = 0;
		for (Cube c : cubes) {
			if (!brim(c, inverse)) biggest = Math.max(biggest, volume(c.cube()));
		}
		for (Cube c : cubes) {
			if (volume(c.cube()) < biggest * 0.25F || brim(c, inverse)) continue;
			Matrix4f toLocal = new Matrix4f(inverse).mul(c.pose());
			if (modelled) {
				ModelPart.Cube k = c.cube();
				for (int corner = 0; corner < 8; corner++) {
					Vector3f p = toLocal.transformPosition(new Vector3f(((corner & 1) == 0 ? k.minX : k.maxX) / 16F,
							((corner & 2) == 0 ? k.minY : k.maxY) / 16F, ((corner & 4) == 0 ? k.minZ : k.maxZ) / 16F));
					lo[0] = Math.min(lo[0], p.x); lo[1] = Math.min(lo[1], p.y); lo[2] = Math.min(lo[2], p.z);
					hi[0] = Math.max(hi[0], p.x); hi[1] = Math.max(hi[1], p.y); hi[2] = Math.max(hi[2], p.z);
				}
				continue;
			}
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

	/** For the head dump too: a part that belongs to the head (the head, or a face part beside it). */
	public static boolean headName(String name) {
		return name.contains("head") || FACE_PARTS.contains(name);
	}

	/** Wings, tails, fins, a blaze's rods ("part0".."part11"), a breeze's rods, a bee's stinger. */
	public static boolean appendageName(String name) {
		return name.contains("wing") || name.contains("tail") || name.contains("fin") || name.equals("rods") || name.equals("stinger")
				|| BLAZE_ROD.matcher(name).matches();
	}

	/** How far a wing hanging at the side of a lying body is turned outward at its free edge (20 degrees), and a lent bee's flat discs up (30 degrees). */
	private static final float WING_HANG_ROLL = (float) Math.toRadians(20), WING_LIFT_ROLL = (float) Math.toRadians(30);
	/** The vex model's wing yaw about which it flaps (1.0995574 radians, VexModel), and the share of its stroke kept on a lying main, where the turned plane's axis leans along the body. */
	private static final float VEX_WING_YAW = 1.0995574F, VEX_FLAP_DAMP = 0.25F;

	private static final java.util.regex.Pattern BLAZE_ROD = java.util.regex.Pattern.compile("part\\d+");

	/**
	 * The torso's box {lo, hi}: the part called "shell" where there is one (a nautilus's soft body hangs off it, and it is the creature),
	 * or the part called "body" (its own cubes), or failing that everything but the head, legs, arms, appendages and a silverfish's back
	 * plates, or failing that the same with the head, or failing that the whole model.
	 */
	private static float @Nullable [][] torso(ModelPart root, PoseStack base) {
		return torso(root, base, false);
	}

	/**
	 * {@code coreOnly}: where the torso is the head (a breeze's, a blaze's, a guardian's), only its big blocks count, as for any head's size
	 * ({@link #coreBox}): the plate round a breeze's face is 10 pixels wide against the head's 8, and made it read as lying with wings
	 * standing a pixel clear of its sides (rig, 2026-11-19, 3 pairs). A guardian's too, since 2026-11-20: its torso is the cube, not the box
	 * round its spike tips, so wings, fins and tails seat on the cube, where they floated a block off it.
	 */
	private static float @Nullable [][] torso(ModelPart root, PoseStack base, boolean coreOnly) {
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		List<ModelPart> body = shellPath(root);
		if (body == null) body = find(root, "body", new ArrayList<>());
		if (body != null) {
			PoseStack ps = copy(base);
			for (ModelPart part : body) part.translateAndRotate(ps);
			cubeBounds(body.getLast(), ps, lo, hi);
		}
		if (lo[0] > hi[0]) torsoBounds(root, copy(base), lo, hi, false);
		if (lo[0] > hi[0]) {
			// a blaze: all head, and rods around it
			if (coreOnly) bigBlockBounds(root, copy(base), lo, hi);
			else torsoBounds(root, copy(base), lo, hi, true);
		}
		if (lo[0] > hi[0]) bounds(root, copy(base), lo, hi);
		return lo[0] > hi[0] ? null : new float[][] {lo, hi};
	}

	/** {@link #torsoBounds} with the head, of only the blocks at least a quarter the size of the biggest. */
	private static void bigBlockBounds(ModelPart root, PoseStack base, float[] lo, float[] hi) {
		List<Cube> cubes = new ArrayList<>();
		torsoCubes(root, base, cubes);
		float biggest = 0;
		for (Cube c : cubes) biggest = Math.max(biggest, volume(c.cube()));
		for (Cube c : cubes) {
			if (volume(c.cube()) < biggest * 0.25F) continue;
			for (ModelPart.Polygon polygon : c.cube().polygons) {
				for (ModelPart.Vertex v : polygon.vertices()) {
					Vector3f w = c.pose().transformPosition(new Vector3f(v.x() / 16F, v.y() / 16F, v.z() / 16F));
					lo[0] = Math.min(lo[0], w.x); lo[1] = Math.min(lo[1], w.y); lo[2] = Math.min(lo[2], w.z);
					hi[0] = Math.max(hi[0], w.x); hi[1] = Math.max(hi[1], w.y); hi[2] = Math.max(hi[2], w.z);
				}
			}
		}
	}

	private static void torsoCubes(ModelPart part, PoseStack pose, List<Cube> out) {
		if (!part.visible) return;
		pose.pushPose();
		part.translateAndRotate(pose);
		if (!part.skipDraw) {
			for (ModelPart.Cube cube : ((ModelPartAccessor) (Object) part).morph$cubes()) out.add(new Cube(new Matrix4f(pose.last().pose()), new Matrix3f(pose.last().normal()), cube));
		}
		for (Map.Entry<String, ModelPart> child : ((ModelPartAccessor) (Object) part).morph$children().entrySet()) {
			String name = child.getKey();
			if (appendageName(name) || name.contains("leg") || name.contains("arm")) continue;
			torsoCubes(child.getValue(), pose, out);
		}
		pose.popPose();
	}

	private static void torsoBounds(ModelPart part, PoseStack pose, float[] lo, float[] hi, boolean withHead) {
		if (!part.visible) return;
		pose.pushPose();
		part.translateAndRotate(pose);
		cubeBounds(part, pose, lo, hi);
		for (Map.Entry<String, ModelPart> child : ((ModelPartAccessor) (Object) part).morph$children().entrySet()) {
			String name = child.getKey();
			if ((!withHead && headName(name)) || appendageName(name) || name.contains("leg") || name.contains("arm") || layerName(name)) continue;
			ModelPart limb = child.getValue();
			// a salmon's rear half swings (its yaw is a sine of the age), and the box round it as it swings is 5 pixels across for a 3 pixel body:
			// held to that, a lent rabbit's tail was drawn at its full 3 pixels on it, 1.4 times the body's thickness, for the cap is a share of the
			// torso's thinner side (rig, 2026-11-21, round 5, salmon + rabbit). Measured at rest, as findAppendages takes a lent salmon's
			if (name.equals("body_back")) {
				float yaw = limb.yRot;
				limb.yRot = limb.getInitialPose().yRot();
				try {
					torsoBounds(limb, pose, lo, hi, withHead);
				} finally {
					limb.yRot = yaw;
				}
			} else {
				torsoBounds(limb, pose, lo, hi, withHead);
			}
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
	/** Package-private for HybridMorphGraftTest. */
	record Graft(float[][] own, float[][] main, Matrix4f ownUnturn, Matrix4f mainUnturn, float scale, float[][] ownAll, float[][] mainAll, float ceiling, float finCeiling, float lentSize,
			boolean shell, boolean liftWings, boolean tadpoleMain, float @Nullable [][] ownTail, float @Nullable [][] ownTip, float silhouette, boolean vexLent, boolean lyingBlock) {
		/** The main is not a squid or a guardian, so its box is never the lying block ({@link #lyingBlock}). */
		Graft(float[][] own, float[][] main, Matrix4f ownUnturn, Matrix4f mainUnturn, float scale, float[][] ownAll, float[][] mainAll, float ceiling, float finCeiling, float lentSize,
				boolean shell, boolean liftWings, boolean tadpoleMain, float @Nullable [][] ownTail, float @Nullable [][] ownTip, float silhouette, boolean vexLent) {
			this(own, main, ownUnturn, mainUnturn, scale, ownAll, mainAll, ceiling, finCeiling, lentSize, shell, liftWings, tadpoleMain, ownTail, ownTip, silhouette, vexLent, false);
		}

		// lyingBlock: the main is a squid or a guardian (an elder one too), whose torso box is a 12 x 12 x 16 pixel block that lies once the squid is on land (its
		// renderer pitches it) and always for the guardian's cube-and-plates head: neither cubic() nor blocky() takes that box, which is why the pectoral fins of a lent cod
		// or salmon went to its lower front corners (rig, 2026-11-22). Only these four mains are told apart so: a polar bear's 14 x 14 x 19 body has the same ratios.
		// vexLent: the lent creature is a vex, whose flat wing planes get LentHeadSize.PLANAR_WING_FLOOR on a big body (no other lender's do).
		// ownTail: the box (this frame, y up) of the main's own tail where it has one a lent tail goes on: the first segment of the ender dragon's
		// tail chain, whose trunk's rear face is inside that segment, or a tadpole's tail slab; null for every other body. ownTip: the box of the last
		// segment of the dragon's chain, where a lent tail that hangs is rooted (null for every other body). silhouette: the main's
		// height without its wings at rest (restHeight, LentHeadSize.partReference), 0 when not measured.
		/** Blocks wings stand clear of the body. */
		static final float CLEAR = 0.04F;
		/** Blocks a wing that hangs at the side of a lying body (a chicken's, a parrot's) stands clear of it: about a model pixel. */
		static final float HANGING_WING_CLEAR = 0.07F;
		/** The sine of the most a fish's body yaws about its middle (its renderer's 5.6 degrees out of the water): how far off the midline a fin may land per block along the body. */
		static final float YAW_WOBBLE = 0.1F;
		/** A lying torso lower than this (a frog's 3 pixels) roots a hanging wing at its back line, not a share of its height. */
		static final float LOW_TORSO = 0.25F;
		/** A standing torso this wide (a wither's shoulder bar is 1.25) roots a lent fin a fifth in from its side, over the ribs under the bar, not at its end. */
		static final float WIDE_MAIN = 1.2F;
		/** Blocks a tail is set into the body it joins. */
		private static final float SET_IN = 0.03F;
		/** Where on the torso, from its bottom, a tail's root is at lowest (the small of the back). */
		private static final float TAIL_LOW = 0.15F;
		/** Where a standing body's hip is, as a share of its whole height from the floor: a person's legs end at 0.75 of 1.8 blocks. */
		private static final float HIP_SHARE = 0.4F;
		/** A torso is a robe's, down to the ankles, when its bottom is within this share of the whole body's height of the floor (an evoker's is at 0.13). */
		private static final float ROBE_REACH = 0.15F;
		/** Where on a lying body's torso, from its bottom, a standing lender's tail root is at lowest. */
		private static final float LYING_TAIL_LOW = 0.33F;
		/** Where on a shell body's torso, from its bottom, a lent tail's root is: about a third of the way up the closed end. */
		private static final float SHELL_TAIL_HEIGHT = 0.35F;
		/** How far outside the torso's box a breeze's rods are held, in blocks (the ring they stand in). */
		private static final float RING_MARGIN = 0.12F;
		/** A lent tail whose box at rest is at least this many times as tall as it is deep hangs (a horse's 3 x 14 x 4 pixels turned back 30 degrees, a camel's strip). */
		private static final float HANGING_TAIL = 1.25F;
		/**
		 * And at least this tall (blocks, in its own body's frame): a horse's tail is 0.88 and a camel's strip 0.875, where an armadillo's (0.36), a wolf's (0.48,
		 * a ratio of 1.21 that any pose change would flip) and a tropical fish's (0.37) are not meant to hang off the end of the dragon's tail.
		 */
		private static final float HANGING_TAIL_MIN = 0.5F;
		/** How far a lent pectoral fin on a blocky main is turned up at its free edge (30 degrees, as a lent bee's discs are): out, not down along the flank. */
		static final float PECTORAL_LIFT_ROLL = (float) Math.toRadians(30);
		/** How far a lent vex's wing planes on a lying main lean out of its back (35 degrees from upright), once laid out sideways ({@link #foldedWing}). */
		static final float VEX_LIFT_ROLL = (float) Math.toRadians(35);
		/** The long edge of a vex's wing plane at scale 1, 8 pixels, which {@link #foldedWing} lays back along the body. */
		static final float VEX_WING_LENGTH = 0.5F;

		static boolean stands(float[][] box) {
			return box[1][1] - box[0][1] >= box[1][2] - box[0][2];
		}

		/** A torso that is as wide as it is tall and as tall as it is long, within a quarter either way (a slime, a magma cube, a ghast, an elder guardian, a snow golem's block). */
		static boolean cubic(float[][] box) {
			float w = box[1][0] - box[0][0], h = box[1][1] - box[0][1], d = box[1][2] - box[0][2];
			if (w < 0.01F || h < 0.01F || d < 0.01F) return false;
			return h / d >= 0.8F && h / d <= 1.25F && w / h >= 0.8F && w / h <= 1.25F;
		}

		/**
		 * A torso that is a block more than a cube: a squid's mantle (12 x 16 x 12 pixels, 1.33 as tall as it is deep and 0.75 as wide as it is tall) as well as the cubes.
		 * A fin on one is a side fin on a block, rooted at the middle of its flank ({@link #place}, {@link LentHeadSize#PECTORAL_CAP}); the tails and the dorsal keep {@link #cubic}.
		 */
		static boolean blocky(float[][] box) {
			if (cubic(box)) return true;
			float w = box[1][0] - box[0][0], h = box[1][1] - box[0][1], d = box[1][2] - box[0][2];
			if (w < 0.01F || h < 0.01F || d < 0.01F) return false;
			// taller than deep by more than a quarter, up to 1.4, and not wider than tall: an upright mantle. Not a goat's (0.8 as tall as it is deep)
			return h / d > 1.25F && h / d <= 1.4F && w / h >= 0.7F && w / h <= 1F;
		}

		/**
		 * The box of a squid's mantle or a guardian's head cube, 12 x 12 x 16 pixels, as it can come out: lying (3/4 as tall as it is long and as wide as it is tall,
		 * which {@link #cubic} and {@link #blocky} both leave out), or pitched on the way up or down in the water, where the box round it is up to 20 pixels each way
		 * and 0.6 as wide as it is tall (the renderer pitches it about its x axis and {@code unturn} takes out only the yaw). Only asked of those four mains
		 * ({@link #lyingBlocks}): a polar bear's body is the same ratios.
		 */
		static boolean lyingBlockBox(float[][] box) {
			float w = box[1][0] - box[0][0], h = box[1][1] - box[0][1], d = box[1][2] - box[0][2];
			if (w < 0.01F || h < 0.01F || d < 0.01F) return false;
			return h / d >= 0.7F && h / d <= 1.4F && w / h >= 0.55F && w / h <= 1.1F;
		}

		/** Whether this main is a squid or a guardian with its box as the lying block ({@link #lyingBlockBox}). */
		boolean lyingBlocks() {
			return lyingBlock && lyingBlockBox(main);
		}

		/** Whether a fin on this main is a side fin on a block: a cube, a ghast, a squid's mantle ({@link #blocky}), its lying block or a guardian's, or the shell of a nautilus, whose box is 0.8 to 0.9 as wide as it is tall. */
		boolean blockish() {
			return shell || blocky(main) || lyingBlocks();
		}

		/**
		 * A cube for the rules that take a cube's size and middle ({@link #cubic}: the tail leaving the middle of its rear face, the dorsal held to a share of its height, the longer
		 * strip fin cap), which the real elder guardian's 12 x 12 x 16 block (0.75 as tall as it is long) fell just outside: a guardian's or a squid's block while it lies, not the
		 * upright mantle in the water (h/d over 1), which keeps the rules it was settled with (rig, 2026-11-22).
		 */
		boolean cubical() {
			return cubic(main) || (lyingBlocks() && !stands(main));
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
			return place(joint, name, scale);
		}

		/** The back plane a lent tail meets: the end of the torso, or the end of a tadpole's own tail, which runs past its body. */
		float back() {
			// the dragon's trunk ends inside its first tail segment: a lent tail's plane is the middle of that segment, where it sits on it
			if (ownTail != null && !tadpoleMain) return (ownTail[0][2] + ownTail[1][2]) / 2F;
			return tadpoleMain ? Math.max(main[1][2], mainAll[1][2]) : main[1][2];
		}

		/** {@link #place(Vector3f, String)} at {@code scale} (a tail is drawn smaller than the rest where it would be longer than the torso). */
		Vector3f place(Vector3f joint, String name, float scale) {
			return place(joint, name, scale, CLEAR, false);
		}

		/** {@link #place(Vector3f, String, float)} with {@code wingClear} blocks between a wing's root and the side of a lying body; {@code hanging}: the wing hangs (a chicken's, a parrot's). */
		Vector3f place(Vector3f joint, String name, float scale, float wingClear, boolean hanging) {
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
				// a breeze's rods run from 0.16 above their pivot to 0.35 below it (morph-heads.json), their middle 0.09 under it, and that
				// pivot is low on its own body (its head and the wind below are all of it): by the share of the whole body they sat at the
				// knees of a person and under the belly of an animal, round nothing. The pivot goes to the middle of the torso, as the
				// axis they stand round (a lying body's put it at its rear), a little above the middle so the rods' own middle is there:
				// they ring it (rig, 2026-11-18 and 2026-11-20, about 130 pairs). The ring's size is findAppendages'
				if (name.equals("rods")) {
					at.x = (main[0][0] + main[1][0]) / 2F;
					at.z = (main[0][2] + main[1][2]) / 2F;
					at.y = (main[0][1] + main[1][1]) / 2F + LentHeadSize.RODS_MIDDLE_BELOW * scale;
				}
			} else if (name.contains("wing")) {
				float w = main[1][0] - main[0][0], h = main[1][1] - main[0][1];
				if (mainStands) {
					// on the back, near the middle, at the shoulder blades
					// a torso under a third of a block across (a vex's) puts them at its side edges: two roots a tenth of a block apart
					// merged a chicken's wings into one slab hanging under it (rig, 2026-11-20)
					float inset = LentHeadSize.wingInset(w);
					at.x = Math.clamp(at.x, main[0][0] + inset, main[1][0] - inset);
					at.z = Math.max(at.z, main[1][2] + CLEAR);
					at.y = Math.max(at.y, main[0][1] + 0.92F * h);
				} else {
					// on the flanks, high up, the side the joint is on (on top, a chicken's or bat's wings hid in the back)
					boolean left = at.x < (main[0][0] + main[1][0]) / 2F;
					// 0.04 off a body under 0.4 wide is a gap of half a wing (a tadpole's, a bee's discs beside a flat mantle): there they
					// touch it, and never stand over its top, where a bee's joint lands above it (rig, 2026-11-20). Only there: on a cow's,
					// a horse's or a wolf's flank a root above the back stays above it, as the settled pairs have it
					boolean tiny = w < LentHeadSize.TINY_MAIN;
					float clear = tiny ? -SET_IN : wingClear;
					at.x = left ? Math.min(at.x, main[0][0] - clear) : Math.max(at.x, main[1][0] + clear);
					// a wing that hangs (a chicken's, a parrot's) on a torso lower than the wing is long is rooted at the back
					// line and hangs from it: rooted by a share of a 3 pixel torso it hung below the belly to the legs, a skirt (rig, 2026-11-21, round 5,
					// frog + parrot)
					at.y = hanging && h < LOW_TORSO ? main[1][1] - SET_IN : Math.max(at.y, main[0][1] + 0.67F * h);
					if (tiny) at.y = Math.min(at.y, main[1][1] - SET_IN);
				}
			} else if (name.equals("stinger")) {
				// the bee's stinger plate stands 5 to 7 pixels behind its pivot, the middle of the bee: at the middle of a shallow torso it
				// floated behind it with air between (rig, 2026-11-20, 10 pairs). Its front edge goes into the rear, as a tail does
				at.z = main[1][2] - 5F / 16F * scale - SET_IN;
			} else if (name.contains("tail") || rearFin(name, joint)) {
				// (a salmon's tail is named back_fin, and is the one at the rear of its body: it took the generic axis map and landed at the
				// bottom of a standing body, where a tail is rooted at the small of the back; a dolphin's back_fin is its dorsal, within the body;
				// rig, 2026-11-21)
				// behind: the back of a standing body, the rear of a lying one, set into it a little so it joins it (a tail's
				// pivot sits past the end of its own body, and kept that distance it floated behind; I saw it)
				at.z = back() - SET_IN;
				float torsoHeight = main[1][1] - main[0][1];
				if (ownTail != null && !shell) {
					if (tadpoleMain) {
						// the tadpole's own tail runs past its body, level with it and swung to one side: the root was the torso's (low and central) and the
						// lent tail hung 0.2 block under and past the tip (rig, 2026-11-21, round 5). At the slab's height and at its swung end
						float cx = (main[0][0] + main[1][0]) / 2F;
						at.y = (ownTail[0][1] + ownTail[1][1]) / 2F;
						at.x = Math.abs(ownTail[0][0] - cx) > Math.abs(ownTail[1][0] - cx) ? ownTail[0][0] : ownTail[1][0];
					} else if (hanging && ownTip != null) {
						// a tail that hangs (a horse's, a camel's strip) rooted on the top of the dragon's first segment pointed down through that 0.6 block
						// cube, and only its far end showed as a pale nub (rig, 2026-11-22, round 6: the tail was drawn, it was hidden). It hangs free from
						// the rear face of the last segment of the chain, as a tuft does, at the segment's middle height
						at.x = (ownTip[0][0] + ownTip[1][0]) / 2F;
						at.y = (ownTip[0][1] + ownTip[1][1]) / 2F;
						at.z = ownTip[1][2] - SET_IN;
					} else {
						// the dragon: its trunk's rear face is inside its first tail segment, and a lent tail rooted there was inside it, a crossbar flush
						// with the spine (rig, 2026-11-21, round 5). On the top of that segment, in its middle
						at.x = (ownTail[0][0] + ownTail[1][0]) / 2F;
						at.y = ownTail[1][1];
					}
					return at;
				}
				if (shell) {
					// a shell body's face is the open end of its shell, where the soft body hangs (a nautilus's): the tail came out of that end and
					// hung over the tentacles, eyes and mouth. It goes on the closed end, about a third of the way up it, and points away
					// (turnAround, Graft.move; rig, 2026-11-21, 16 pairs)
					at.z = main[0][2] + SET_IN;
					at.y = main[0][1] + SHELL_TAIL_HEIGHT * torsoHeight;
					return at;
				}
				// a lying creature's rear on a standing body lands at its bottom, and the tail's pivot past the end of its own body
				// was added below that: the root hung at the knees of a person (an ocelot's dragged on the floor). From the small of
				// the back up
				if (mainStands && from[1] == 2) {
					// and not under the hip: a robe's torso box reaches the ankles (an evoker's runs from 0.25 block), and 15 percent of it
					// is the knee, where a fox's brush and a dolphin's fluke stuck out horizontally (rig, 2026-11-21). The hip is about
					// 0.4 of the whole body's height from the floor (a person's 0.75 of 1.8), never put above the lower 0.6 of the torso
					// Only for a torso that starts at the ankles (ROBE_REACH): a creeper's, an iron golem's or an allay's rests on legs or
					// a base of its own, and its tails went up to 0.2 block higher than the settled pairs have them
					float whole = mainAll[1][1] - mainAll[0][1];
					float hip = Math.min(mainAll[0][1] + HIP_SHARE * whole, main[0][1] + 0.6F * torsoHeight);
					boolean robed = whole > 0.01F && main[0][1] - mainAll[0][1] <= ROBE_REACH * whole;
					at.y = Math.max(at.y, Math.max(main[0][1] + TAIL_LOW * torsoHeight, robed ? hip : Float.NEGATIVE_INFINITY));
				} else if (!mainStands && from[1] == 2) {
					// a standing creature's tail on a lying body: its root came out at the bottom of the flank (a sitting rabbit's tail on a
					// cod, a brown brick down to the floor). At least a third of the way up the rear
					at.y = Math.max(at.y, main[0][1] + LYING_TAIL_LOW * torsoHeight);
				}
				// a cube has no hip or small of the back: a tail leaves the middle of its rear face, not the bottom corner (rig, 2026-11-21). Whichever
				// way round its axes map: a dolphin's fluke on a magma cube or a sulfur cube, whose box counts as lying (no swap), lay on the floor
				// (rig, 2026-11-22, round 6)
				if (cubical()) at.y = Math.max(at.y, main[0][1] + 0.5F * torsoHeight);
			} else if (name.contains("fin") && blockish()) {
				// a lent flipper or pectoral fin on a cube is rooted at the middle of its flank, not at the floor. Set, not raised: a fish's
				// pectoral is at the front of its body, which on a cube that stands up is the top rim, and a ghast's cod fins stood on its top
				// corners like horns (rig, 2026-11-21 and 2026-11-22, round 6, ghast + cod and salmon)
				at.y = main[0][1] + 0.5F * (main[1][1] - main[0][1]);
				// and on the flank itself: a dolphin's flipper joins its body a quarter of the width in from its side, and put there it
				// stood inside the cube, its root buried, the rest of it lying along the floor (round 6, magma cube and sulfur cube + dolphin)
				boolean left = at.x < (main[0][0] + main[1][0]) / 2F;
				at.x = left ? main[0][0] + SET_IN : main[1][0] - SET_IN;
				// and on a big one (a 2 block elder guardian, a ghast) at the middle of the flank along it too: at the front corner a pectoral
				// grown to that size sloped down across the face like a tusk (rig, 2026-11-21, round 5, elder guardian + cod)
				// (and on the lying block of a squid or a guardian, whatever its width: the 0.75 block mantle is under BIG_MAIN, and its front corner is its
				// tip, where the cod's pectorals hung 0.1 block below the floor line and past the end; rig, 2026-11-22, round 7, squid, glow squid and elder
				// guardian + cod and salmon)
				if (main[1][0] - main[0][0] >= LentHeadSize.BIG_MAIN || lyingBlocks()) at.z = (main[0][2] + main[1][2]) / 2F;
			} else if (name.contains("fin") && mainStands && main[1][0] - main[0][0] >= WIDE_MAIN) {
				// a wide standing torso is a bar of shoulders over a narrower body (a wither's): its end is a third of a block off the ribs, and a lent
				// fin rooted there floated beside them (rig, 2026-11-21, round 5, wither + tropical fish). In by a fifth of the width, as a wing is, but
				// never more than WIDE_FIN_INSET_MAX: a fifth of a giant's 3 blocks buried half of its fin in the flank
				float inset = Math.min(LentHeadSize.wingInset(main[1][0] - main[0][0]), LentHeadSize.WIDE_FIN_INSET_MAX);
				at.x = Math.clamp(at.x, main[0][0] + inset, main[1][0] - inset);
			}
			return at;
		}

		/**
		 * Whether a part is the fin at the rear of its own body that a lent salmon has for a tail (named back_fin, as a dolphin's dorsal is):
		 * {@code joint} is where it joins, in the lender's frame. One of those is rooted, turned and seated as a tail ({@link #place},
		 * {@link #move}, {@link #touch}); a dolphin's dorsal, within its body, is not.
		 */
		boolean rearFin(String name, Vector3f joint) {
			return name.equals("back_fin") && joint.z > own[1][2] - 0.01F;
		}

		/**
		 * Whether a lent tail ({@code rest}: its cubes at rest on the borrowed body) hangs: its box is at least HANGING_TAIL times as tall as it is deep (a horse's tail
		 * and a camel's strip; a fox's brush, a cat's and a wolf's run back, not down). On the ender dragon a tail that hangs is rooted at the end of its tail chain
		 * ({@link #place}); one that runs back keeps the top of the first segment.
		 */
		boolean hangs(List<Cube> rest) {
			float[][] box = boxOf(rest);
			return box != null && box[1][1] - box[0][1] >= HANGING_TAIL_MIN && box[1][1] - box[0][1] >= HANGING_TAIL * (box[1][2] - box[0][2]);
		}

		/**
		 * A tail moved by {@code move} ({@code cubes} as posed on the borrowed body), shifted so it joins the main body's
		 * back: into it by SET_IN where it stands off it, out of it where it would be hidden (a camel's is a flat strip
		 * hanging down the back, and set in it disappeared). On a shell body the tail is on the closed end, which it meets the same way mirrored.
		 * A tail that is centred on its own body is centred on the main torso's middle line too ({@code rest}: the tail at rest, so its wag is
		 * not taken out): a fox's brush, whose pivot is 4 pixels off its body's middle, hung half a block beside a wither's spine (rig, 2026-11-21).
		 */
		Matrix4f touch(Matrix4f move, List<Cube> cubes, List<Cube> rest) {
			float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE, low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
			Matrix4f toMain = new Matrix4f(mainUnturn).mul(move);
			for (Cube c : cubes) {
				Matrix4f m = new Matrix4f(toMain).mul(c.pose());
				for (ModelPart.Polygon polygon : c.cube().polygons) {
					for (ModelPart.Vertex v : polygon.vertices()) {
						Vector3f p = m.transformPosition(new Vector3f(v.x() / 16F, v.y() / 16F, v.z() / 16F));
						lo = Math.min(lo, p.z);
						hi = Math.max(hi, p.z);
						low = Math.min(low, p.y);
						high = Math.max(high, p.y);
					}
				}
			}
			if (lo > hi) return move;
			float back = back(), shift = 0F, lift = 0F;
			if (shell) {
				float front = main[0][2];
				// a strip with no thickness (a camel's tail) lies in the plane it is parallel to, the shell's closed face, 0.005 off it, and the face
				// hid it (rig, 2026-11-21, round 5): a clear 0.02 out
				// A strip is one that is flat at rest (no depth in its own frame) too, not only as it is posed now: the lent camel's posed tail tilts about 5 degrees
				// (0.076 block deep), so "as posed" took it for a thick tail, left its root end 0.03 inside the face, and the face hid it down to where it
				// leaves the shell's floor line: only a 0.3 block hair below the shell showed (rig and a live dump, 2026-11-22, round 7, nautilus and zombie
				// nautilus + camel and camel husk). Rounds 4 to 6 tested an untilted strip.
				float[][] restBox = boxOf(rest);
				boolean strip = hi - lo < 0.02F || (restBox != null && restBox[1][2] - restBox[0][2] < 0.02F);
				float off = strip ? 0.02F : 0.005F;
				// the whole strip outside the face, its root end included (a tilted one has its far end 0.05 out and its root in); a strip left more than
				// that out stood off the face, and the old rule below buried it 0.03 in
				if (strip) shift = front - off - hi;
				else if (lo > front - off) shift = front - off - lo;
				else if (hi < front + SET_IN) shift = front + SET_IN - hi;
				if (strip) {
					// and it hangs over the face, not past the bottom of the shell: rooted a third of the way up the closed end, a strip longer than that
					// hung below the shell, on the floor (rig, 2026-11-22, round 6). Up till its end is in the shell's height, or if it is longer than that, from the top
					float bottom = main[0][1] + SET_IN, top = main[1][1] - SET_IN;
					if (high - low > top - bottom || high > top) lift = top - high;
					else if (low < bottom) lift = bottom - low;
				}
			} else if (hi < back + 0.005F) shift = back + 0.005F - hi;
			else if (lo > back - SET_IN) shift = back - SET_IN - lo;
			float sideways = centreShift(move, rest);
			if (shift == 0F && sideways == 0F && lift == 0F) return move;
			return new Matrix4f(mainUnturn).invert().translate(sideways, lift, shift).mul(mainUnturn).mul(move);
		}

		/** How far (main frame, x) a tail whose box at rest is centred on its own torso must go to be centred on the main torso's middle line; 0 for one that is not. */
		private float centreShift(Matrix4f move, List<Cube> rest) {
			// not on a body whose own tail the lent one is rooted on: that tail swings, and its root is where it is, not on the torso's line
			if (ownTail != null) return 0F;
			float[][] box = boxOf(rest);
			if (box == null) return 0F;
			float ownWidth = own[1][0] - own[0][0];
			if (Math.abs((box[0][0] + box[1][0]) / 2F - (own[0][0] + own[1][0]) / 2F) > 0.15F * Math.max(ownWidth, 0.01F)) return 0F;
			float[] x = xRange(move, rest);
			float shift = (main[0][0] + main[1][0]) / 2F - (x[0] + x[1]) / 2F;
			return Math.abs(shift) < 0.002F ? 0F : shift;
		}

		/** The sideways (main frame, x) range {lo, hi} of {@code cubes} as {@code move} puts them. */
		private float[] xRange(Matrix4f move, List<Cube> cubes) {
			float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
			Matrix4f toMain = new Matrix4f(mainUnturn).mul(move);
			Matrix4f m = new Matrix4f();
			Vector3f p = new Vector3f();
			for (Cube c : cubes) {
				toMain.mul(c.pose(), m);
				ModelPart.Cube k = c.cube();
				for (int corner = 0; corner < 8; corner++) {
					m.transformPosition(((corner & 1) == 0 ? k.minX : k.maxX) / 16F, ((corner & 2) == 0 ? k.minY : k.maxY) / 16F,
							((corner & 4) == 0 ? k.minZ : k.maxZ) / 16F, p);
					lo = Math.min(lo, p.x);
					hi = Math.max(hi, p.x);
				}
			}
			return new float[] {lo, hi};
		}

		/** A wing that stands off a lying body's side by more than this many blocks is moved in to it ({@link #touchWing}). */
		private static final float WING_GAP = 0.1F;

		/**
		 * A wing on a lying body ({@code cubes} as it is at rest on the borrowed body, moved by {@code move}) brought in until its inner edge is
		 * CLEAR off the side of the torso it goes on, where it stands off it by more than WING_GAP: a vex's wings floated 0.25 to 0.4 block outside
		 * a sniffer's slab and a turtle's shell, the root at the edge of the box but the drawn wing not meeting it (rig, 2026-11-21). A wing
		 * already near the side, or over the body, stays where it is: the settled pairs keep their CLEAR. Measured at rest, not as the wing
		 * flaps: the shift is a step at WING_GAP, and from the posed wing it came and went with every stroke, cancelling the flap's swing.
		 */
		Matrix4f touchWing(Matrix4f move, List<Cube> cubes) {
			if (stands(main)) return move;
			float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
			Matrix4f toMain = new Matrix4f(mainUnturn).mul(move);
			// the corners of each cube, with one matrix and one vector for all of them (this runs for every wing on every lying main, each frame)
			Matrix4f m = new Matrix4f();
			Vector3f p = new Vector3f();
			for (Cube c : cubes) {
				toMain.mul(c.pose(), m);
				ModelPart.Cube k = c.cube();
				for (int corner = 0; corner < 8; corner++) {
					m.transformPosition(((corner & 1) == 0 ? k.minX : k.maxX) / 16F, ((corner & 2) == 0 ? k.minY : k.maxY) / 16F,
							((corner & 4) == 0 ? k.minZ : k.maxZ) / 16F, p);
					lo = Math.min(lo, p.x);
					hi = Math.max(hi, p.x);
				}
			}
			if (lo > hi) return move;
			float shift = 0F;
			if ((lo + hi) / 2F < (main[0][0] + main[1][0]) / 2F) {
				float gap = main[0][0] - hi;
				if (gap > WING_GAP) shift = gap - CLEAR;
			} else {
				float gap = lo - main[1][0];
				if (gap > WING_GAP) shift = -(gap - CLEAR);
			}
			if (shift == 0F) return move;
			return new Matrix4f(mainUnturn).invert().translate(shift, 0F, 0F).mul(mainUnturn).mul(move);
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
			return move(joint, name, scale);
		}

		/** {@link #move(Vector3f, String)} at {@code scale}. */
		Matrix4f move(Vector3f joint, String name, float scale) {
			return move(joint, name, scale, false, 0F);
		}

		/**
		 * A lent vex's wing plane laid out as a pegasus's folded wing on a big lying main (a ravager, a sniffer, a camel). {@code limbPose} is the wing's pivot as posed on the
		 * vex ({@link #findAppendages} takes it with the flap at its mean), so its own axes can be put where they are wanted: its thin side (the plane's normal) out to the
		 * side, its 8 pixel edge back along the body, its 5 pixel edge up, the plane then leaning {@code lean} radians out of the back at its root. Mirrored for each side.
		 * Round 6 turned the plane a quarter about the upright axis and rolled it; the real vex, ravager and sniffer (HybridMorphRealMainsTest) show that left the plane's
		 * other axes where the vex's own 27 degree tilts put them, so the wing's 5 pixel edge ran inward and down from its root, into the flank, and half the sheet was
		 * inside the body: what stuck out read as an antler (rig, 2026-11-21 and 2026-11-22, rounds 5 to 7, ravager and sniffer + vex). Rooted in the top fifth of the
		 * flank, where a pegasus's wing joins its back, and standing CLEAR off the side.
		 */
		Matrix4f foldedWing(Vector3f joint, Matrix4f limbPose, String name, float scale, float lean) {
			Vector3f at = place(ownUnturn.transformPosition(new Vector3f(joint)), name, scale, CLEAR, false);
			// in the top fifth of the flank and not above it: place() maps the wing's joint onto the main's height, which at a big wing's size can come out over the back line, and the
			// sheet then touched nothing of the flank
			float flank = main[1][1] - main[0][1];
			at.y = Math.clamp(at.y, main[1][1] - 0.2F * flank, main[1][1] - 0.05F * flank);
			// and along the body, with the 8 pixel edge that runs back from the root inside its length: the vex's wing joint maps onto the front of the flank, a little past the front
			// end at a big wing's size, and the sheet then stuck out ahead of the body (real ravager, HybridMorphRealMainsTest)
			at.z = Math.clamp(at.z, main[0][2], Math.max(main[0][2], main[1][2] - VEX_WING_LENGTH * scale));
			Vector3f target = new Matrix4f(mainUnturn).invert().transformPosition(new Vector3f(at));
			float side = at.x < (main[0][0] + main[1][0]) / 2F ? -1F : 1F;
			// the wing's axes in the body's frame (the borrowed body's facing and the main's cancelled), and the turn that takes them to the main's axes, then leans them
			Matrix3f axes = new Matrix3f(ownUnturn).mul(rigid(limbPose).get3x3(new Matrix3f()));
			Matrix4f q = new Matrix4f(new Matrix3f().rotationZ(-side * lean).mul(axes.invert()));
			Matrix4f turn = new Matrix4f(mainUnturn).invert().mul(q).mul(mainUnturn);
			return new Matrix4f().translation(target).mul(turn).mul(align()).scale(scale).translate(-joint.x, -joint.y, -joint.z);
		}

		/**
		 * {@link #move(Vector3f, String, float)} for a wing that hangs ({@code hanging}) at the side of a lying body (HANGING_WING_CLEAR blocks off it, else CLEAR; place), and the wing turned
		 * {@code roll} radians about the body's front-to-back axis at its root, outward at its free edge and upward: a wing hanging at the side
		 * stands off the flank, and a flat disc stands up from the back as a pegasus's wing does. A tail on a shell body is turned half way
		 * round, to point away from it.
		 */
		Matrix4f move(Vector3f joint, String name, float scale, boolean hanging, float roll) {
			Vector3f at = place(ownUnturn.transformPosition(new Vector3f(joint)), name, scale, hanging ? HANGING_WING_CLEAR : CLEAR, hanging);
			Vector3f target = new Matrix4f(mainUnturn).invert().transformPosition(new Vector3f(at));
			Matrix4f turn = new Matrix4f();
			if (roll != 0F) {
				float side = at.x < (main[0][0] + main[1][0]) / 2F ? -1F : 1F;
				turn = new Matrix4f(mainUnturn).invert().rotateZ(side * roll).mul(mainUnturn);
			} else if (shell && (name.contains("tail") || rearFin(name, ownUnturn.transformPosition(new Vector3f(joint))))) {
				turn = new Matrix4f(mainUnturn).invert().rotateY((float) Math.PI).mul(mainUnturn);
			}
			return new Matrix4f().translation(target).mul(turn).mul(align()).scale(scale).translate(-joint.x, -joint.y, -joint.z);
		}

		/**
		 * The scale a lent tail ({@code limb}) is drawn at: LentHeadSize.tailScale against this torso's longest side. By the tail at rest
		 * ({@link #restSides}), not as it is posed: a wagging tail's box changes with the wag and its size breathed with it.
		 */
		float tailScale(ModelPart limb) {
			float[] sides = restSides(limb);
			float[] drawn = {sides[0] * lentSize, sides[1] * lentSize, sides[2] * lentSize};
			float longest = reference();
			// and no thicker than the body it joins: its middle side against the torso's thinner one (LentHeadSize.tailScale)
			float thin = Math.min(main[1][0] - main[0][0], main[1][1] - main[0][1]);
			float s = LentHeadSize.tailScale(scale, drawn[2], drawn[1], longest, thin);
			// a flat tail (a tropical fish's, a camel's) is not left a hairline either: a floor as for a fin, held to the same thickness
			// limit (the floor on a thin torso made a flat tail wider than the body it joins). A strip (a camel's, 3 x 14 pixels) too: it is
			// not planar by the middle side and came over as a 1 pixel speck on a nautilus
			float floored = LentHeadSize.tailFloor(s, drawn[2], drawn[1], longest, thin, finCeiling, LentHeadSize.planar(sides) || LentHeadSize.stripTail(sides));
			// and a strip on a shell is not a hairline beside the shell's ears: the floor above grows it by its length, and a 14 pixel strip is 3 across
			// (rig, 2026-11-22, round 6, zombie nautilus and nautilus + camel and camel husk)
			if (shell && LentHeadSize.stripTail(sides)) floored = LentHeadSize.stripTailWidthFloor(floored, drawn[1], drawn[2], main[1][1] - main[0][1], finCeiling);
			return floored;
		}

		/**
		 * What lent fins, wings and tails are sized against: the main torso's longest side, or for a creature whose torso is a small part of its
		 * height its whole height without the wings (LentHeadSize.partReference: an allay, a blaze, a vex).
		 */
		float reference() {
			float longest = Math.max(main[1][0] - main[0][0], Math.max(main[1][1] - main[0][1], main[1][2] - main[0][2]));
			return LentHeadSize.partReference(longest, silhouette);
		}

		/** The box {lo, hi} round some cubes (as posed on the borrowed body) in the borrowed body's own frame, or null for none. */
		float @Nullable [][] boxOf(List<Cube> cubes) {
			float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
			float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
			for (Cube c : cubes) {
				float[][] box = cubeBox(c, ownUnturn);
				for (int axis = 0; axis < 3; axis++) {
					lo[axis] = Math.min(lo[axis], box[0][axis]);
					hi[axis] = Math.max(hi[axis], box[1][axis]);
				}
			}
			return lo[0] > hi[0] ? null : new float[][] {lo, hi};
		}

		/**
		 * The scale a lent wing, fin or stinger ({@code limb}) is drawn at: the parts' scale, raised to LentHeadSize.PART_FLOOR of this
		 * torso's longest side where it would be a speck, but never past the tiny-main limit. By the part at rest ({@link #restSides}): the
		 * floor is inversely proportional to the extent, which moves while a wing flaps or a fin yaws, and the part breathed with it.
		 */
		float partScale(ModelPart limb) {
			return partScale(limb, false, false);
		}

		/**
		 * {@link #partScale(ModelPart)} for a fin ({@code fin}) or a wing ({@code wing}, which has its own, higher floor on a big body): a flat fin one is raised to LentHeadSize.FIN_FLOOR of the torso's longest side
		 * (to the tiny-main fin limit on a tiny body), and no fin is drawn over LentHeadSize.FIN_CAP of it (a dolphin's dorsal and
		 * flippers were as long as a cat).
		 */
		float partScale(ModelPart limb, boolean fin, boolean wing) {
			return partScale(limb, fin, wing, false);
		}

		/**
		 * {@link #partScale(ModelPart, boolean, boolean)} for a pectoral fin or flipper ({@code pectoral}: a fin that is neither the dorsal nor the tail) on a blocky main
		 * ({@link #blocky}), where it is PECTORAL_CAP of the torso's longest side long ({@link LentHeadSize#pectoralScale}) rather than the floor and FIN_CAP: a flipper as long as the cube it is on lay
		 * along its floor, and a pectoral on the elder guardian hung down its face like a tusk (rig, round 6).
		 */
		float partScale(ModelPart limb, boolean fin, boolean wing, boolean pectoral) {
			float[] sides = restSides(limb);
			float extent = sides[2] * lentSize;
			float longest = reference();
			// a pectoral on a blocky main is a share of the torso, by its length: neither the floors nor FIN_CAP below (LentHeadSize.pectoralScale)
			if (fin && pectoral && blockish()) return LentHeadSize.pectoralScale(scale, extent, longest, finCeiling);
			boolean flat = fin && LentHeadSize.planar(sides);
			// only a vex's planes get the higher floor on a big body: the other flat wings (chicken, parrot, bat, allay, bee) keep the share their pairs were settled with
			float s = wing ? LentHeadSize.wingFloor(scale, extent, longest, ceiling, vexLent && LentHeadSize.planar(sides))
					: LentHeadSize.partFloor(scale, extent, longest, flat ? finCeiling : ceiling, flat);
			// a strip fin (a cod's dorsal, 1 pixel high) is raised by its height too, or it stays a hairline whatever its length; its length cap is
			// longer on a big cube, where the shorter one left it 0.235 block high on the elder guardian (stripFinScale)
			if (fin && !flat) return LentHeadSize.stripFinScale(s, extent, sides[1] * lentSize, longest, finCeiling, cubical());
			return fin ? LentHeadSize.finCap(s, extent, longest) : s;
		}

		/** The scale a lent breeze's rods ({@code limb}) are drawn at: LentHeadSize.rodsScale against this torso's height. */
		float rodsScale(ModelPart limb) {
			float[] sides = restSides(limb);
			return LentHeadSize.rodsScale(scale, sides[2] * lentSize, main[1][1] - main[0][1], ceiling);
		}

		/**
		 * The move for a dorsal fin, a fin on the midline over its body's back ({@code cubes}, as posed on the borrowed body), or null for any
		 * other piece. A dolphin's and a fish's dorsal fins came over at their pivots, which are at the origin of the body (the fin's box is
		 * 8 to 13 pixels from it): a horizontal plank over the nape of a person, a slab hovering over a cat, buried in a goat (rig, 2026-11-20, over 100 pairs with a dolphin
		 * or a fish). The joint is the middle of its own base. On a lying body it stands upright on the back, its base set in by SET_IN;
		 * on a standing one it is turned onto the back, its base along the spine and its edge out of it, at about three quarters of the
		 * torso's height (shoulder blades), as a merman's or a dragon's dorsal is drawn.
		 */
		@Nullable Matrix4f dorsalMove(List<Cube> cubes, float scale) {
			float[][] fin = boxOf(cubes);
			// not on a lender that is clearly upright; a body as tall as it is long (a large tropical fish's 2 x 6 x 6) is a fish, whatever
			// the float noise says for stands()
			if (fin == null || own[1][1] - own[0][1] > 1.2F * (own[1][2] - own[0][2])) return null;
			float width = own[1][0] - own[0][0];
			float x = (fin[0][0] + fin[1][0]) / 2F, y = (fin[0][1] + fin[1][1]) / 2F, z = (fin[0][2] + fin[1][2]) / 2F;
			// a salmon's body yaws with a sine of the age (the renderer, out of the water 1.3 x 4.3 degrees), which moves a fin that far from the body's
			// middle by its lever along it, 0.1 of that: as much as the whole 0.15 of a 3 pixel body, so the fin was a dorsal in one frame and a
			// side fin standing off the back in the next (rig, 2026-11-21, round 5, salmon on eight mains)
			float wobble = YAW_WOBBLE * Math.abs(z - (own[0][2] + own[1][2]) / 2F);
			boolean midline = Math.abs(x - (own[0][0] + own[1][0]) / 2F) <= 0.15F * Math.max(width, 0.01F) + wobble;
			// a cube (a slime, a magma cube, a ghast, an elder guardian) counts as stands() on the float noise of its sides, and a dolphin's dorsal
			// was turned onto its back as onto a person's: a slab as big as the cube out of its top (rig, 2026-11-21). It is lying: upright on the top
			boolean standing = stands(main) && !cubic(main);
			// over its back: along it, not past its end (a tail fin is), above the middle of it (y is up)
			boolean over = z >= own[0][2] && z <= own[1][2] && y >= (own[0][1] + own[1][1]) / 2F;
			if (!midline || !over) return null;
			// a fin that is swept back on its own body (a dolphin's dorsal: its part is turned 60 degrees, a parallelogram 30 degrees off upright)
			// stands upright on a lying body or a cube: tilted it touched the back by its lowest corner only, a leaning tombstone (rig, 2026-11-21,
			// salmon, ghast and the cube mobs + dolphin). On a standing body it stays swept, as a merman's or a dragon's is drawn
			Matrix4f untilt = new Matrix4f();
			if (!standing) {
				float tilt = uprightTilt(cubes);
				if (Math.abs(tilt) > 0.02F) {
					untilt = new Matrix4f(ownUnturn).invert().rotateX(tilt).mul(ownUnturn);
					List<Cube> upright = new ArrayList<>(cubes.size());
					for (Cube c : cubes) upright.add(new Cube(new Matrix4f(untilt).mul(c.pose()), c.normal(), c.cube()));
					float[][] straight = boxOf(upright);
					if (straight != null) {
						fin = straight;
						x = (fin[0][0] + fin[1][0]) / 2F;
						z = (fin[0][2] + fin[1][2]) / 2F;
					}
				}
			}
			// its base: the bottom of its box, the middle across and along
			Vector3f joint = new Matrix4f(ownUnturn).invert().transformPosition(new Vector3f(x, fin[0][1], z));
			Vector3f at = new Vector3f();
			Matrix4f roll = new Matrix4f();
			float h = main[1][1] - main[0][1];
			// on a lying body no taller than 0.75 of its thickness: a dolphin's dorsal was 1.4 times a salmon's body
			boolean cube = cubical();
			if (!standing && !cube) {
				float finHeight = fin[1][1] - fin[0][1];
				// (not on a torso with no thickness: the scale would come out 0 or less and the fin's normals NaN)
				if (finHeight > 0.01F && h > 0.01F) scale = Math.min(scale, 0.75F * h / finHeight);
			} else if (cube) {
				// a dolphin's dorsal grown by its length (up to 0.6 of the cube) stood half a cube over the top, a thin square post, 1 pixel thick against
				// 4 pixels tall (rig, 2026-11-22, round 6, magma cube and sulfur cube + dolphin): held to a share of the cube's height, a fin
				float finHeight = fin[1][1] - fin[0][1];
				if (finHeight > 0.01F && h > 0.01F) scale = Math.min(scale, LentHeadSize.CUBE_DORSAL_SHARE * h / finHeight);
			}
			at.x = (main[0][0] + main[1][0]) / 2F;
			if (standing) {
				float half = (fin[1][2] - fin[0][2]) * scale / 2F;
				// where along the spine: three quarters up, moved by how far the fin is from the middle of its own body, at the fin's size,
				// the front one higher (as place() maps a lying body's front onto a standing one's top): a salmon's two midline fins were the
				// same point and drew stacked. Kept in the upper half of the back
				float spread = ((own[0][2] + own[1][2]) / 2F - z) * scale;
				float up = h < 2F * half ? h / 2F : Math.clamp(0.75F * h + spread, Math.max(half, 0.5F * h), h - half);
				at.y = main[0][1] + up;
				at.z = main[1][2] - SET_IN;
				// the fin's up (+y) goes to the body's back (+z), its front (-z) up the body: a quarter turn about the sideways axis
				roll = new Matrix4f(mainUnturn).invert().rotateX((float) (Math.PI / 2)).mul(mainUnturn);
			} else {
				at.y = main[1][1] - SET_IN;
				float length = own[1][2] - own[0][2];
				float share = length < 0.01F ? 0.5F : Math.clamp((z - own[0][2]) / length, 0.15F, 0.85F);
				at.z = main[0][2] + share * (main[1][2] - main[0][2]);
			}
			Vector3f target = new Matrix4f(mainUnturn).invert().transformPosition(at);
			Matrix4f move = new Matrix4f().translation(target).mul(roll).mul(align()).scale(scale).translate(-joint.x, -joint.y, -joint.z).mul(untilt);
			// and wherever the numbers above put it, its base is on the back: the fin's nearest edge to the body is at the back plane (a standing
			// body) or the top (a lying one), set in by SET_IN. A salmon's two dorsal fins stood 0.3 to 0.6 block off an iron golem's, a snow
			// golem's, a giant's and a warden's back with air between (rig, 2026-11-21, 8 pairs)
			return seatOnBack(move, cubes, standing);
		}

		/**
		 * {@code move} (for {@code cubes}, a dorsal fin as posed on the borrowed body) shifted so the edge of the fin nearest the body is at its
		 * back plane, SET_IN inside it: along z on a standing body, along y (the top) on a lying one.
		 */
		private Matrix4f seatOnBack(Matrix4f move, List<Cube> cubes, boolean standing) {
			Matrix4f toMain = new Matrix4f(mainUnturn).mul(move);
			Matrix4f m = new Matrix4f();
			Vector3f p = new Vector3f();
			float nearest = Float.MAX_VALUE;
			for (Cube c : cubes) {
				toMain.mul(c.pose(), m);
				ModelPart.Cube k = c.cube();
				for (int corner = 0; corner < 8; corner++) {
					m.transformPosition(((corner & 1) == 0 ? k.minX : k.maxX) / 16F, ((corner & 2) == 0 ? k.minY : k.maxY) / 16F,
							((corner & 4) == 0 ? k.minZ : k.maxZ) / 16F, p);
					nearest = Math.min(nearest, standing ? p.z : p.y);
				}
			}
			if (nearest == Float.MAX_VALUE) return move;
			// a lying body's top is its greatest y (y is up in this frame), a standing one's back its greatest z: the fin's nearest edge is its least
			float wanted = standing ? main[1][2] - SET_IN : main[1][1] - SET_IN;
			float shift = wanted - nearest;
			if (Math.abs(shift) < 0.002F) return move;
			return new Matrix4f(mainUnturn).invert().translate(0F, standing ? 0F : shift, standing ? shift : 0F).mul(mainUnturn).mul(move);
		}

		/**
		 * How far (radians, about the sideways axis in the lender's frame) a dorsal fin ({@code cubes}, one flat block as posed on the borrowed
		 * body) has to be turned for its tall side to stand upright; 0 for a fin that is upright, has more than one block or is not a plane
		 * across the body.
		 */
		private float uprightTilt(List<Cube> cubes) {
			if (cubes.size() != 1) return 0F;
			Cube c = cubes.get(0);
			ModelPart.Cube k = c.cube();
			float[] size = {k.maxX - k.minX, k.maxY - k.minY, k.maxZ - k.minZ};
			int thin = size[0] <= size[1] && size[0] <= size[2] ? 0 : size[1] <= size[2] ? 1 : 2;
			Matrix4f m = new Matrix4f(ownUnturn).mul(c.pose());
			Vector3f[] axis = new Vector3f[3];
			for (int i = 0; i < 3; i++) axis[i] = m.transformDirection(new Vector3f(i == 0 ? 1F : 0F, i == 1 ? 1F : 0F, i == 2 ? 1F : 0F)).normalize();
			// only a plane across the body (its thin side is sideways)
			if (Math.abs(axis[thin].x) < 0.9F) return 0F;
			Vector3f tall = null;
			for (int i = 0; i < 3; i++) {
				if (i != thin && (tall == null || Math.abs(axis[i].y) > Math.abs(tall.y))) tall = axis[i];
			}
			if (tall == null || Math.abs(tall.y) < 0.2F) return 0F;
			float tallY = tall.y > 0 ? tall.y : -tall.y, tallZ = tall.y > 0 ? tall.z : -tall.z;
			return (float) Math.atan2(-tallZ, tallY);
		}

		/**
		 * One of a breeze's rods ({@code c}, as posed on the borrowed body, moved by {@code move}) pushed out to stand round the torso, not
		 * in it (LentHeadSize.ringPush): along the line it already lies on from the torso's middle, to the torso's box and a margin.
		 */
		Cube ringed(Matrix4f move, Cube c, Matrix3f turn, float size) {
			Matrix4f pose = new Matrix4f(move).mul(c.pose());
			float[][] box = cubeBox(new Cube(pose, c.normal(), c.cube()), mainUnturn);
			float ax = (main[0][0] + main[1][0]) / 2F, az = (main[0][2] + main[1][2]) / 2F;
			float dx = (box[0][0] + box[1][0]) / 2F - ax, dz = (box[0][2] + box[1][2]) / 2F - az;
			// rods held small on a tiny body (a tadpole's, an endermite's) stand that much nearer it: the full margin made the ring wider
			// than the animal; grown on a tall body (a sniffer's) they stand that much further out. By how far they differ from the scale every
			// other part has ({@code scale}), so a body where they are neither held nor raised keeps the 0.12 the settled pairs have
			float held = scale > 0.01F ? size / scale : 1F;
			float[] to = LentHeadSize.ringPush(dx, dz, (main[1][0] - main[0][0]) / 2F, (main[1][2] - main[0][2]) / 2F, LentHeadSize.ringMargin(RING_MARGIN, held));
			if (to[0] != dx || to[1] != dz) pose = new Matrix4f(mainUnturn).invert().translate(to[0] - dx, 0F, to[1] - dz).mul(mainUnturn).mul(pose);
			return new Cube(pose, new Matrix3f(turn).mul(c.normal()), c.cube());
		}
	}

	/**
	 * A pectoral fin or flipper: a fin that is neither a tail, the one at the rear of its body (Graft.rearFin, which the joint decides) nor a dorsal
	 * (a cod's and a tropical fish's top_fin, a salmon's and a pufferfish's top_front_fin and top_back_fin). The dorsals keep the strip-fin size and
	 * height floor (LentHeadSize.stripFinScale); sized as a pectoral they came out about half as high.
	 */
	static boolean sideFin(String name) {
		return name.contains("fin") && !name.contains("tail") && !name.equals("back_fin") && !name.startsWith("top");
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
				// a salmon's tail and top fins hang off body_back, which the model yaws with a sine of the age: the fin's root swung 0.15 to
				// 0.3 block off the lent body's hip with air between (rig, 2026-11-21, 8 pairs). Collected with it at rest, the fins hold
				// their place on the main
				if (name.equals("body_back")) {
					float yaw = limb.yRot;
					limb.yRot = limb.getInitialPose().yRot();
					try {
						findAppendages(limb, pose, graft, out);
					} finally {
						limb.yRot = yaw;
					}
				} else {
					findAppendages(limb, pose, graft, out);
				}
				continue;
			}
			boolean segment = name.matches("tail\\d+");
			List<Cube> cubes = new ArrayList<>();
			// a vex's planes on a lying big main that is not a cube (a ravager, a sniffer, a camel) are laid out as a folded wing, out to the side, back along the body and up and out of its
			// back (Graft.foldedWing). A cube's box counts as lying only by float noise (Graft.stands), so it keeps the lift roll the pairs were settled with
			boolean vexSide = name.contains("wing") && graft.vexLent() && graft.liftWings() && !Graft.stands(graft.main()) && !graft.cubical();
			if (vexSide) {
				// the flap swings the wing about its own upright axis, which after that turn leans along the body: the stroke is a quarter of the vanilla
				// one about the wing's mean angle, so it does not sweep through the back or the flank (the static pose is what was measured)
				float flap = limb.yRot;
				limb.yRot = Math.copySign(VEX_WING_YAW, flap) + VEX_FLAP_DAMP * (flap - Math.copySign(VEX_WING_YAW, flap));
				try {
					collect(limb, copy(pose), cubes);
				} finally {
					limb.yRot = flap;
				}
			} else {
				collect(limb, copy(pose), cubes);
			}
			boolean tail = !segment && name.contains("tail") && !name.contains("fin");
			// wings, fins and a stinger are not left a speck on a big body
			boolean showy = name.contains("wing") || name.contains("fin") || name.equals("stinger");
			boolean sideFin = sideFin(name);
			float size = tail ? graft.tailScale(limb) : showy ? graft.partScale(limb, name.contains("fin"), name.contains("wing"), sideFin)
					: name.equals("rods") ? graft.rodsScale(limb) : graft.scale();
			Matrix4f move = segment ? chain : null;
			// a dorsal fin is seated by its own base on the back, not by its pivot
			if (move == null && !tail && !segment && name.contains("fin")) move = graft.dorsalMove(cubes, size);
			// a salmon's tail (a back_fin at the rear of its body) is seated as a tail is, once it is rooted (Graft.rearFin)
			boolean finTail = false;
			// a vex's wing laid out as a folded wing is rooted at the flank's top and stands clear of it: it has no gap to close (touchWing measures rest cubes the fold does not turn)
			boolean folded = false;
			// a tail that hangs on the ender dragon is rooted at the end of its tail chain and hangs free there (Graft.place): it is not moved to meet a back plane
			boolean tipped = false;
			if (move == null) {
				// where it joins: its pivot (a segmented tail's: its first segment's); a tail longer than the torso it goes on is drawn smaller
				PoseStack at = copy(pose);
				(segment ? firstSegment(children) : limb).translateAndRotate(at);
				Vector3f joint = at.last().pose().transformPosition(new Vector3f());
				if (name.contains("wing")) {
					// a wing that hangs at the side of a lying body (a chicken's, a parrot's) stands a pixel off the flank and a fifth of a turn out
					// of it, where it was flush and read as nothing on a pale coat; a bee's flat discs stand up and out of the back (Graft.move)
					boolean hangs = false;
					if (!Graft.stands(graft.main())) {
						float[][] box = graft.boxOf(restCubes(limb, pose));
						hangs = box != null && box[1][0] - box[0][0] > 0F && box[1][1] - box[0][1] >= 2F * (box[1][0] - box[0][0]);
					}
					// a vex's planes on a lying big main are laid out as a folded wing: out to the side, back along the body, up and leaning out of it (Graft.foldedWing)
					boolean planarVex = !hangs && vexSide;
					if (planarVex) {
						// from the wing's pivot with its flap at the mean angle, as the cubes were collected with the stroke damped about it
						float flapNow = limb.yRot;
						limb.yRot = Math.copySign(VEX_WING_YAW, flapNow);
						Matrix4f meanPose;
						try {
							PoseStack mean = copy(pose);
							limb.translateAndRotate(mean);
							meanPose = new Matrix4f(mean.last().pose());
						} finally {
							limb.yRot = flapNow;
						}
						move = graft.foldedWing(joint, meanPose, name, size, Graft.VEX_LIFT_ROLL);
						folded = true;
					} else {
						float roll = hangs ? WING_HANG_ROLL : graft.liftWings() ? WING_LIFT_ROLL : 0F;
						move = graft.move(joint, name, size, hangs, roll);
					}
				} else {
					boolean rear = graft.rearFin(name, graft.ownUnturn().transformPosition(new Vector3f(joint)));
					if (tail && graft.ownTip() != null && graft.hangs(restCubes(limb, pose))) {
						tipped = true;
						move = graft.move(joint, name, size, true, 0F);
					} else if (sideFin && !rear && graft.blockish()) {
						// a pectoral on a cube, a ghast or a squid's mantle hangs down the flank at its own 30 to 45 degrees, along the floor: turned up at the free edge
						move = graft.move(joint, name, size, false, Graft.PECTORAL_LIFT_ROLL);
					} else {
						move = graft.move(joint, name, size);
					}
					finTail = rear;
				}
				if (segment) chain = move;
			}
			if ((tail || finTail) && !tipped) move = graft.touch(move, cubes, restCubes(limb, pose));
			else if (name.contains("wing") && !folded && !Graft.stands(graft.main())) move = graft.touchWing(move, restCubes(limb, pose));
			if (name.equals("rods")) {
				for (Cube c : cubes) out.add(graft.ringed(move, c, turn, size));
				continue;
			}
			// the faces' normals turn with the piece as it is moved: a quarter turn onto a back, a tail turned half round on a shell, a wing rolled out
			// from the flank, not only the swap of the two bodies' facing (a tail on a nautilus was lit as if it faced the other way)
			Matrix3f cubeTurn = rigid(move).get3x3(new Matrix3f());
			for (Cube c : cubes) out.add(new Cube(new Matrix4f(move).mul(c.pose()), new Matrix3f(cubeTurn).mul(c.normal()), c.cube()));
		}
		pose.popPose();
	}

	/** Each limb's sides at rest (sorted, blocks at size 1): per limb, as the lent part's size must not follow its animation. */
	private static final Map<ModelPart, float[]> REST_SIDES = new java.util.WeakHashMap<>();

	/**
	 * The three sides of the box round a limb (a wing, a fin, a tail) with it and the parts under it at their initial poses, smallest first, in
	 * blocks at size 1 (its parent's frame at rest). Measured once per limb: the poses are put back at once, and a model's parts are set
	 * afresh each frame anyway. All zero for a limb with nothing to draw.
	 */
	private static float[] restSides(ModelPart limb) {
		float[] known = REST_SIDES.get(limb);
		if (known != null) return known;
		List<Cube> cubes = new ArrayList<>();
		atRest(limb, () -> collect(limb, new PoseStack(), cubes));
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		Matrix4f identity = new Matrix4f();
		for (Cube c : cubes) {
			float[][] box = cubeBox(c, identity);
			for (int axis = 0; axis < 3; axis++) {
				lo[axis] = Math.min(lo[axis], box[0][axis]);
				hi[axis] = Math.max(hi[axis], box[1][axis]);
			}
		}
		float[] sides = lo[0] > hi[0] ? new float[3] : new float[] {hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]};
		java.util.Arrays.sort(sides);
		REST_SIDES.put(limb, sides);
		return sides;
	}

	private static void allParts(ModelPart part, List<ModelPart> out) {
		out.add(part);
		for (ModelPart child : ((ModelPartAccessor) (Object) part).morph$children().values()) allParts(child, out);
	}

	/** Runs {@code measure} with {@code limb} and the parts under it at their initial poses, and puts the poses back after. */
	private static void atRest(ModelPart limb, Runnable measure) {
		List<ModelPart> parts = new ArrayList<>();
		allParts(limb, parts);
		// every field the pose sets, scales included (storePose does not keep them)
		float[][] posed = new float[parts.size()][];
		for (int i = 0; i < posed.length; i++) {
			ModelPart part = parts.get(i);
			posed[i] = new float[] {part.x, part.y, part.z, part.xRot, part.yRot, part.zRot, part.xScale, part.yScale, part.zScale};
			part.loadPose(part.getInitialPose());
		}
		try {
			measure.run();
		} finally {
			for (int i = 0; i < posed.length; i++) {
				ModelPart part = parts.get(i);
				float[] p = posed[i];
				part.x = p[0]; part.y = p[1]; part.z = p[2];
				part.xRot = p[3]; part.yRot = p[4]; part.zRot = p[5];
				part.xScale = p[6]; part.yScale = p[7]; part.zScale = p[8];
			}
		}
	}

	/** Each lent model's appendage extent at rest, per model, in blocks at size 1 ({@link #restAppendageExtent}). */
	private static final Map<ModelPart, Float> APPENDAGE_UNITS = new java.util.WeakHashMap<>();

	/**
	 * The longest side of the box round all of a lent body's appendages (what {@link #findAppendages} would take) with the whole model at its
	 * initial pose, in blocks as it is drawn (its size without the slime squish). By the parts at rest, as the sizes of the parts are
	 * ({@link #restSides}): in the pose of the moment the limit on a tiny main breathed with the lent wings' flap and the fins' yaw, and it
	 * cost a pose copy and a walk of every appendage cube each frame for every bee-sized main. Measured once per model; 0 for none, which is
	 * asked again (a model's visible parts are set per state).
	 */
	private static float restAppendageExtent(Body lent) {
		Vector3f scale = lent.base().last().pose().getScale(new Vector3f());
		float size = (float) Math.sqrt(scale.x * (double) scale.y);
		if (size <= 1e-4F) return 0F;
		Float unit = APPENDAGE_UNITS.get(lent.root());
		if (unit == null) {
			List<Cube> parts = new ArrayList<>();
			atRest(lent.root(), () -> appendageCubes(lent.root(), copy(lent.base()), parts));
			Matrix4f inverse = rigid(lent.base().last().pose()).invert();
			float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
			float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
			for (Cube c : parts) {
				float[][] box = cubeBox(c, inverse);
				for (int axis = 0; axis < 3; axis++) {
					lo[axis] = Math.min(lo[axis], box[0][axis]);
					hi[axis] = Math.max(hi[axis], box[1][axis]);
				}
			}
			if (lo[0] > hi[0]) return 0F;
			unit = Math.max(hi[0] - lo[0], Math.max(hi[1] - lo[1], hi[2] - lo[2])) / size;
			APPENDAGE_UNITS.put(lent.root(), unit);
		}
		return unit * size;
	}

	/** {@code limb}'s cubes with it and the parts under it at rest, as {@link #collect} takes them from {@code pose}, for a measure that must not follow the animation. */
	private static List<Cube> restCubes(ModelPart limb, PoseStack pose) {
		// the limb at rest in its own frame, once per limb (a model's parts never change): atRest snapshots and restores every part's pose, and this
		// is asked for every tail and wing on every hybrid in view, each frame. Only the parent's pose in front of it changes
		List<Cube> local = REST_LOCAL.computeIfAbsent(limb, l -> {
			List<Cube> cubes = new ArrayList<>();
			atRest(l, () -> collect(l, new PoseStack(), cubes));
			return cubes;
		});
		Matrix4f parent = pose.last().pose();
		List<Cube> out = new ArrayList<>(local.size());
		for (Cube c : local) out.add(new Cube(new Matrix4f(parent).mul(c.pose()), c.normal(), c.cube()));
		return out;
	}

	/** Each limb's cubes at rest, in the limb's own frame (see {@link #restCubes}). */
	private static final Map<ModelPart, List<Cube>> REST_LOCAL = new java.util.WeakHashMap<>();

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

	/** Whether a model has a head part, per model: headPath walks the whole tree twice for a body without one, and a model never gains or loses its head. */
	private static final Map<ModelPart, Boolean> HAS_HEAD = new java.util.WeakHashMap<>();

	private static boolean hasHead(ModelPart root) {
		return HAS_HEAD.computeIfAbsent(root, r -> headPath(r) != null);
	}

	/** Whether a model has a part called "shell", per model: find() walks the whole tree for a miss, and torso() asks for every body every frame. */
	private static final Map<ModelPart, Boolean> HAS_SHELL = new java.util.WeakHashMap<>();

	private static @Nullable List<ModelPart> shellPath(ModelPart root) {
		return HAS_SHELL.computeIfAbsent(root, r -> find(r, "shell", new ArrayList<>()) != null) ? find(root, "shell", new ArrayList<>()) : null;
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

	/**
	 * A silverfish's back plates ("layer0".."layer2": thin needles up the spine, 8 pixels tall beside the 4 pixel segments they stand
	 * on). They are drawn, but they are not its body: the top of its box was the plate tips, and a lent head or creature sat there with
	 * sky between it and the segments (rig, 2026-11-19, all 42 silverfish pairs).
	 */
	private static boolean layerName(String name) {
		return LentHeadSize.backPlate(name);
	}

	/** {@link #collect} for measuring a body (where something goes on it, how big it is), leaving out the back plates ({@link #layerName}). */
	private static void collectCore(ModelPart part, PoseStack pose, List<Cube> out) {
		if (!part.visible) return;
		ModelPartAccessor access = (ModelPartAccessor) (Object) part;
		pose.pushPose();
		part.translateAndRotate(pose);
		if (!part.skipDraw) {
			for (ModelPart.Cube cube : access.morph$cubes()) out.add(new Cube(new Matrix4f(pose.last().pose()), new Matrix3f(pose.last().normal()), cube));
		}
		for (Map.Entry<String, ModelPart> child : access.morph$children().entrySet()) {
			if (!layerName(child.getKey())) collectCore(child.getValue(), pose, out);
		}
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
