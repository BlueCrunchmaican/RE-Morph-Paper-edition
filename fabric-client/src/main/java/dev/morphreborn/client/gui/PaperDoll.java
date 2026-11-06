package dev.morphreborn.client.gui;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.MorphClient;
import dev.morphreborn.client.data.MorphState;
import dev.morphreborn.client.net.MorphRef;
import dev.morphreborn.client.render.MorphTransitionRenderState;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * The paper doll: a live miniature of the player in a corner of the screen.
 *
 * <p>It shows exactly what the world shows, because it asks the same place: {@code EntityRenderDispatcher.extractEntity},
 * which RE:Morph's dispatcher hook answers with the morph (or the transformation) for a morphed player. So the doll
 * carries the morph's variant, baby size, mob animations (hops, flaps, flops, AnimationStates), the player's pose and
 * movement (walking, sneaking, swimming, sleeping, riding, flying) and the box-melting transformation, with no
 * animation code of its own. Extracting a render state only reads the entities; the doll never moves, ticks or rotates
 * anything, so it can't disturb the real player or morph. Riding, the whole vehicle is drawn with every passenger, each
 * through the same path, so a morphed friend in the same boat shows as their morph too.
 *
 * <p>The scene is turned as a whole so the player (or their vehicle) faces the viewer at the chosen angle, keeping
 * every entity's own rotation; it is drawn into its own off-screen texture, separate from the selector's.
 */
public final class PaperDoll {
	private static final int FULL_BRIGHT = 15728880;
	/** Models reach past their hitbox (heads, tails, a fox seen from the side): fit a box this much larger. */
	private static final float FIT_WIDTH = 1.6F, FIT_HEIGHT = 1.2F;
	private static final float PLAYER_WIDTH = 0.6F, PLAYER_HEIGHT = 1.8F;
	/** How long the doll stays after the last activity in ACTIVE mode, in milliseconds. */
	private static final long ACTIVE_LINGER = 1500L;

	private static long activeUntil;
	/** Smoothed zoom, so the doll doesn't jump when the shown body changes size (transformations, vehicles). */
	private static float smoothedFit = -1F;

	private PaperDoll() {}

	public static void render(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || !visible(mc, player)) {
			smoothedFit = -1F;
			return;
		}
		ClientConfig c = ClientConfig.INSTANCE;
		float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
		EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();

		Entity root = c.dollVehicle && player.isPassenger() ? player.getRootVehicle() : player;
		List<Entity> scene = new ArrayList<>();
		scene.add(root);
		if (root != player) root.getIndirectPassengers().forEach(scene::add);
		Vec3 origin = root.getPosition(partialTick);

		List<Part> parts = new ArrayList<>(scene.size());
		float minY = 0F, maxY = 0F, radius = 0F;
		for (Entity e : scene) {
			EntityRenderState state = dispatcher.extractEntity(e, partialTick);
			present(state, c);
			Vec3 offset = e.getPosition(partialTick).subtract(origin);
			parts.add(new Part(state, offset));
			float[] size = e == player ? playerSize(player, partialTick) : new float[] {e.getBbWidth(), e.getBbHeight()};
			minY = Math.min(minY, (float) offset.y);
			maxY = Math.max(maxY, (float) offset.y + size[1]);
			radius = Math.max(radius, (float) Math.sqrt(offset.x * offset.x + offset.z * offset.z) + size[0] / 2F);
		}

		int box = c.dollSize;
		// One scale for every form, set by a player-sized body, so a rabbit looks small and a baby half its adult size.
		// Only a scene bigger than that (a ghast, a boat with passengers) zooms out to fit.
		float fit = box / Math.max(Math.max(radius * 2F, PLAYER_WIDTH) * FIT_WIDTH, Math.max(maxY - minY, PLAYER_HEIGHT) * FIT_HEIGHT);
		smoothedFit = smoothedFit < 0F ? fit : smoothedFit + (fit - smoothedFit) * 0.2F;

		int screenW = g.guiWidth();
		int screenH = g.guiHeight();
		boolean left = c.dollCorner.endsWith("LEFT");
		boolean top = c.dollCorner.startsWith("TOP");
		int x = left ? c.dollX : screenW - c.dollX - box;
		int y = top ? c.dollY : screenH - c.dollY - box;
		float centerX = x + box / 2F;
		float feetY = y + box - smoothedFit * (-minY) - box * 0.06F;
		float yaw = rootYaw(root, partialTick) - 180F - c.dollAngle;
		// The texture reaches past the box on every side so arms, tails and wings that stick out aren't cut off.
		int pad = box / 2;
		int x0 = Math.max(0, x - pad), y0 = Math.max(0, y - pad), x1 = Math.min(screenW, x + box + pad), y1 = Math.min(screenH, y + box + pad);
		if (x1 <= x0 || y1 <= y0) return;
		g.guiRenderState.addPicturesInPictureState(new State(parts, centerX, feetY, smoothedFit, yaw, c.dollOpacity / 100F, x0, y0, x1, y1, null,
				new ScreenRectangle(x0, y0, x1 - x0, y1 - y0)));
	}

	/** Shown at all right now (settings, view, menus, the selector, activity). */
	private static boolean visible(Minecraft mc, LocalPlayer player) {
		ClientConfig c = ClientConfig.INSTANCE;
		if (!c.doll) return false;
		if (c.dollHideDebug && mc.getDebugOverlay().showDebugScreen()) return false;
		if (c.dollHideThirdPerson && !mc.options.getCameraType().isFirstPerson()) return false;
		if (!c.dollInMenus && mc.gui.screen() != null) return false;
		if (SelectorHud.INSTANCE.showSelector || SelectorHud.INSTANCE.showRadial) return false; // they share the left side
		MorphState state = MorphClient.STATES.get(player.getUUID());
		boolean morphed = state != null && state.isMorphed();
		return switch (c.dollShow) {
			case "MORPHED" -> morphed;
			case "ACTIVE" -> {
				long now = System.currentTimeMillis();
				if (active(player, state)) activeUntil = now + ACTIVE_LINGER;
				yield now < activeUntil;
			}
			default -> true;
		};
	}

	/** Moving or doing something worth watching yourself do. */
	private static boolean active(LocalPlayer player, @Nullable MorphState state) {
		Vec3 motion = player.getDeltaMovement();
		return (state != null && state.isTransitioning()) || motion.horizontalDistanceSqr() > 1.0E-4 || !player.onGround() || player.isShiftKeyDown()
				|| player.isSprinting() || player.isSwimming() || player.isFallFlying() || player.isPassenger() || player.isUsingItem() || player.isSwinging()
				|| player.hurtTime > 0 || player.isOnFire() || player.isSleeping();
	}

	/** Prepare an extracted state for the doll: no shadow, outline or name, optional full light, optional straight head. */
	private static void present(EntityRenderState state, ClientConfig c) {
		if (state instanceof MorphTransitionRenderState t) {
			present(t.from, c);
			present(t.to, c);
		}
		state.shadowPieces.clear();
		state.outlineColor = 0;
		state.nameTag = null;
		state.scoreText = null;
		if (c.dollLight.equals("BRIGHT")) state.lightCoords = FULL_BRIGHT;
		if (state instanceof LivingEntityRenderState living && c.dollHead.equals("FORWARD")) {
			living.yRot = 0F;
			living.xRot = 0F;
		}
	}

	/**
	 * Width and height of the body the player shows, standing, for framing: the morph (between the two forms while
	 * transforming) or their own body. Poses like sneaking don't change it, so the doll doesn't zoom when you crouch.
	 */
	private static float[] playerSize(LocalPlayer player, float partialTick) {
		MorphState state = MorphClient.STATES.get(player.getUUID());
		if (state == null || !state.isMorphed()) return standing(player, null);
		if (!state.isTransitioning()) return standing(player, state.next);
		float[] a = standing(player, state.prev), b = standing(player, state.next);
		float t = state.transitionSine(partialTick);
		return new float[] {Mth.lerp(t, a[0], b[0]), Mth.lerp(t, a[1], b[1])};
	}

	private static float[] standing(LocalPlayer player, @Nullable MorphRef form) {
		if (form != null && !form.isSelf()) {
			LivingEntity morph = MorphClient.ENTITIES.forWorld(player.getUUID(), form);
			if (morph != null) return new float[] {morph.getBbWidth(), morph.getBbHeight()};
		}
		var dims = player.getDimensions(Pose.STANDING);
		float scale = player.getScale() > 0F ? player.getScale() : 1F;
		return new float[] {dims.width() / scale, dims.height() / scale};
	}

	/** The direction the scene is turned from: the player's body, or their vehicle. */
	private static float rootYaw(Entity root, float partialTick) {
		if (root instanceof LivingEntity living) return Mth.rotLerp(partialTick, living.yBodyRotO, living.yBodyRot);
		return root.getYRot(partialTick);
	}

	/** One entity of the scene and where it is relative to the root (the player, or the vehicle they ride). */
	public record Part(EntityRenderState state, Vec3 offset) {}

	public record State(List<Part> parts, float centerX, float feetY, float pixelsPerBlock, float yaw, float opacity, int x0, int y0, int x1, int y1,
			@Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds) implements PictureInPictureRenderState {
		@Override
		public float scale() {
			return 1F;
		}
	}

	/** Draws the doll's scene into its own off-screen texture (one per frame, apart from the selector's previews). */
	public static final class Renderer extends PictureInPictureRenderer<State> {
		/** The selector's view: tilted 10 degrees and flipped into GUI space (see MorphGuiEntities). */
		private static final Quaternionf VIEW = new Quaternionf().rotateX((float) Math.toRadians(10F)).rotateZ((float) Math.PI);

		@Override
		public Class<State> getRenderStateClass() {
			return State.class;
		}

		@Override
		protected float getTranslateY(final int height, final int guiScale) {
			return 0F;
		}

		@Override
		protected void renderToTexture(State state, PoseStack poseStack, SubmitNodeCollector collector) {
			Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
			EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
			CameraRenderState camera = new CameraRenderState();
			float halfWidth = (state.x1() - state.x0()) / 2F;
			poseStack.pushPose();
			poseStack.translate(state.centerX() - state.x0() - halfWidth, state.feetY() - state.y0(), 0F);
			poseStack.scale(state.pixelsPerBlock(), state.pixelsPerBlock(), state.pixelsPerBlock());
			poseStack.rotate(VIEW);
			// Turn the whole scene so its root faces the viewer at the chosen angle; every entity keeps its own rotation.
			poseStack.rotate(Axis.YP.rotationDegrees(state.yaw()));
			for (Part part : state.parts()) {
				dispatcher.submit(part.state(), camera, part.offset().x, part.offset().y, part.offset().z, poseStack, collector);
			}
			poseStack.popPose();
		}

		/** As vanilla's blit, with the colour scaled by the opacity (the texture's colours are premultiplied by alpha). */
		@Override
		protected void blitTexture(State state, net.minecraft.client.renderer.state.gui.GuiRenderState guiRenderState) {
			if (state.opacity() >= 1F) {
				super.blitTexture(state, guiRenderState);
				return;
			}
			int a = Math.round(Math.clamp(state.opacity(), 0F, 1F) * 255F);
			int color = a << 24 | a << 16 | a << 8 | a;
			guiRenderState.addBlitToCurrentLayer(new net.minecraft.client.renderer.state.gui.BlitRenderState(
					net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
					net.minecraft.client.gui.render.TextureSetup.singleTexture(((dev.morphreborn.client.mixin.PictureInPictureRendererAccessor) (Object) this).morph$textureView(),
							com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getRepeat(com.mojang.renderpearl.api.textures.FilterMode.NEAREST)),
					state.pose(), state.x0(), state.y0(), state.x1(), state.y1(), 0.0F, 1.0F, 1.0F, 0.0F, color, state.scissorArea(), null));
		}

		@Override
		protected String getTextureLabel() {
			return "morphreborn paper doll";
		}
	}
}
