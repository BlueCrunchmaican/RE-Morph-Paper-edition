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
	/** "Fill" sizing: extra room above, for ears, horns and antennae that reach past the hitbox. */
	private static final float FILL_HEIGHT = 1.5F;

	/**
	 * The situations that show the doll in "While Active" mode, in settings order: the player's own movement and
	 * actions first, then the morph-specific ones.
	 */
	public static final java.util.List<String> TRIGGERS = java.util.List.of("WALKING", "CROUCHING", "SPRINTING", "AIRBORNE", "ELYTRA", "SWIMMING",
			"FLYING", "CLIMBING", "RIDING", "BLOCKING", "USING_ITEM", "SWINGING", "HURT", "ON_FIRE", "POWDER_SNOW", "SLEEPING", "TRANSFORMING", "ABILITY");

	private static long activeUntil;
	/** Smoothed zoom, so the doll doesn't jump when the shown body changes size (transformations, vehicles). */
	private static float smoothedFit = -1F;

	private PaperDoll() {}

	/** HUD element: the doll over the world. While a menu is open it's drawn over the menu instead ({@link #renderOverScreen}). */
	public static void render(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		if (Minecraft.getInstance().gui.screen() != null) return;
		draw(g, deltaTracker.getGameTimeDeltaPartialTick(false));
	}

	/**
	 * After any menu has been drawn (inventories, chat, the pause menu, other mods' screens): with Show in Menus on,
	 * the doll goes on top of it. Drawn as a HUD element it would sit under the menu's darkened, blurred background.
	 */
	public static void renderOverScreen(net.minecraft.client.gui.screens.Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY, float tickDelta) {
		draw(g, Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
	}

	private static void draw(GuiGraphicsExtractor g, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || !visible(mc, player)) {
			smoothedFit = -1F;
			return;
		}
		ClientConfig c = ClientConfig.INSTANCE;
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
			present(state, c, e == player ? player : null);
			Vec3 offset = e.getPosition(partialTick).subtract(origin);
			parts.add(new Part(state, offset));
			float[] size = e == player ? playerSize(player, partialTick) : new float[] {e.getBbWidth(), e.getBbHeight()};
			minY = Math.min(minY, (float) offset.y);
			maxY = Math.max(maxY, (float) offset.y + size[1]);
			radius = Math.max(radius, (float) Math.sqrt(offset.x * offset.x + offset.z * offset.z) + size[0] / 2F);
		}

		// The doll's settings are screen pixels, so the GUI scale doesn't make it bigger or smaller; it has its own Size.
		ClientConfig.dollToScreenPixels();
		float guiScale = (float) Math.max(1.0, mc.getWindow().getGuiScale());
		int box = Math.max(4, Math.round(c.dollSize / guiScale));
		// One scale for every form, set by a player-sized body, so a rabbit looks small and a baby half its adult size.
		// Only a scene bigger than that (a ghast, a boat with passengers) zooms out to fit.
		float fit = c.dollScale.equals("FILL")
				? box / Math.max(0.3F, Math.max(radius * 2F * FIT_WIDTH, (maxY - minY) * FILL_HEIGHT))
				: box / Math.max(Math.max(radius * 2F, PLAYER_WIDTH) * FIT_WIDTH, Math.max(maxY - minY, PLAYER_HEIGHT) * FIT_HEIGHT);
		smoothedFit = smoothedFit < 0F ? fit : smoothedFit + (fit - smoothedFit) * 0.2F;

		int screenW = g.guiWidth();
		int screenH = g.guiHeight();
		boolean left = c.dollCorner.endsWith("LEFT");
		boolean top = c.dollCorner.startsWith("TOP");
		int dx = Math.round(c.dollX / guiScale);
		int dy = Math.round(c.dollY / guiScale);
		int x = left ? dx : screenW - dx - box;
		int y = top ? dy : screenH - dy - box;
		float centerX = x + box / 2F;
		float feetY = y + box - smoothedFit * (-minY) - box * 0.06F;
		float yaw = rootYaw(root, partialTick) - 180F - c.dollAngle;
		// The texture reaches past the box on every side so arms, tails and wings that stick out aren't cut off.
		int pad = box / 2;
		int x0 = Math.max(0, x - pad), y0 = Math.max(0, y - pad), x1 = Math.min(screenW, x + box + pad), y1 = Math.min(screenH, y + box + pad);
		if (x1 <= x0 || y1 <= y0) return;
		float outline = c.dollVignette / guiScale; // vignette radius, from screen pixels to GUI pixels
		g.guiRenderState.addPicturesInPictureState(new State(parts, centerX, feetY, smoothedFit, yaw, c.dollTilt, c.dollOpacity / 100F, outline,
				c.dollOutlineStrength / 100F, x0, y0, x1, y1, null, new ScreenRectangle(x0, y0, x1 - x0, y1 - y0)));
	}

	/** Shown at all right now (settings, view, menus, the selector, activity). */
	private static boolean visible(Minecraft mc, LocalPlayer player) {
		ClientConfig c = ClientConfig.INSTANCE;
		if (!c.doll) return false;
		if (c.dollHideDebug && mc.getDebugOverlay().showDebugScreen()) return false;
		if (c.dollHideThirdPerson && !mc.options.getCameraType().isFirstPerson()) return false;
		if (!c.dollInMenus && mc.gui.screen() != null) return false;
		if (c.dollHideMusic && musicToastShowing(mc)) return false;
		if (SelectorHud.INSTANCE.showSelector || SelectorHud.INSTANCE.showRadial) return false; // they share the left side
		MorphState state = MorphClient.STATES.get(player.getUUID());
		boolean morphed = state != null && state.isMorphed();
		return switch (c.dollShow) {
			case "MORPHED" -> morphed;
			case "ACTIVE" -> {
				long now = System.currentTimeMillis();
				if (active(player, state, c.dollShowWhen)) activeUntil = now + c.dollLinger * 100L;
				yield now < activeUntil;
			}
			default -> true;
		};
	}

	/** True when one of the chosen situations ({@link #TRIGGERS}) is happening. */
	private static boolean active(LocalPlayer p, @Nullable MorphState state, java.util.List<String> on) {
		boolean flying = p.getAbilities().flying;
		return on.contains("WALKING") && p.onGround() && p.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4
				|| on.contains("CROUCHING") && p.isCrouching()
				|| on.contains("SPRINTING") && p.isSprinting()
				|| on.contains("AIRBORNE") && !p.onGround() && !flying && !p.isFallFlying() && !p.isPassenger() && !p.isInWater() && !p.onClimbable()
				|| on.contains("ELYTRA") && p.isFallFlying()
				|| on.contains("SWIMMING") && (p.isSwimming() || p.isVisuallySwimming())
				|| on.contains("FLYING") && flying
				|| on.contains("CLIMBING") && p.onClimbable() && !p.onGround()
				|| on.contains("RIDING") && p.isPassenger()
				|| on.contains("BLOCKING") && p.isBlocking()
				|| on.contains("USING_ITEM") && p.isUsingItem()
				|| on.contains("SWINGING") && p.isSwinging()
				|| on.contains("HURT") && p.hurtTime > 0
				|| on.contains("ON_FIRE") && p.isOnFire()
				|| on.contains("POWDER_SNOW") && p.isInPowderSnow
				|| on.contains("SLEEPING") && p.isSleeping()
				|| on.contains("TRANSFORMING") && state != null && state.isTransitioning()
				|| on.contains("ABILITY") && System.currentTimeMillis() - dev.morphreborn.client.net.ClientNet.lastAbilityAt < 1000L;
	}

	/**
	 * The "now playing" toast is on screen (when the music toast option shows it at all). ToastManager keeps it in a
	 * private field of a private class, so it's read by name (26.3 isn't obfuscated); if a future version renames
	 * them, this just stops hiding the doll.
	 */
	private static boolean musicToastShowing(Minecraft mc) {
		if (!mc.options.musicToast().get().renderToast() || MUSIC_TOAST_BROKEN) return false;
		try {
			if (musicToastField == null) {
				musicToastField = net.minecraft.client.gui.components.toasts.ToastManager.class.getDeclaredField("nowPlayingToast");
				musicToastField.setAccessible(true);
			}
			Object toast = musicToastField.get(mc.gui.toastManager());
			if (toast == null) return false;
			if (finishedMethod == null) {
				finishedMethod = toast.getClass().getDeclaredMethod("hasFinishedRendering");
				finishedMethod.setAccessible(true);
			}
			return !(boolean) finishedMethod.invoke(toast);
		} catch (ReflectiveOperationException | RuntimeException e) {
			MUSIC_TOAST_BROKEN = true;
			MorphClient.LOGGER.warn("Can't see the music toast ({}); the paper doll won't step aside for it.", e.toString());
			return false;
		}
	}

	private static java.lang.reflect.@Nullable Field musicToastField;
	private static java.lang.reflect.@Nullable Method finishedMethod;
	private static boolean MUSIC_TOAST_BROKEN;

	/**
	 * Prepare an extracted state for the doll: no shadow, glow or name, optional full light. For the player's own form
	 * ({@code player} not null), whatever it is, the head settings apply: every morph follows them as the player does.
	 */
	private static void present(EntityRenderState state, ClientConfig c, @Nullable LocalPlayer player) {
		if (state instanceof MorphTransitionRenderState t) {
			present(t.from, c, player);
			present(t.to, c, player);
		}
		state.shadowPieces.clear();
		state.outlineColor = 0;
		state.nameTag = null;
		state.scoreText = null;
		if (c.dollLight.equals("BRIGHT")) state.lightCoords = FULL_BRIGHT;
		if (player != null && state instanceof LivingEntityRenderState living) head(living, c, player);
	}

	/**
	 * Head modes. A render state's yRot is the head's turn away from the
	 * body and xRot its look up or down.
	 * <ul>
	 *   <li>FOLLOW: turns and looks as yours does.</li>
	 *   <li>TURN: turns, but keeps level.</li>
	 *   <li>LOOK: faces forward, looks up and down.</li>
	 *   <li>FORWARD: straight ahead, level.</li>
	 *   <li>AT_VIEWER: looks out of the screen at you, whatever the doll's turn and tilt.</li>
	 * </ul>
	 * Gliding with an elytra or spinning with a riptide trident can keep the head level (the lock settings).
	 */
	private static void head(LivingEntityRenderState living, ClientConfig c, LocalPlayer player) {
		switch (c.dollHead) {
			case "TURN" -> living.xRot = 0F;
			case "LOOK" -> living.yRot = 0F;
			case "FORWARD" -> {
				living.yRot = 0F;
				living.xRot = 0F;
			}
			case "AT_VIEWER" -> {
				// the turn back toward the viewer (with +dollAngle it turned further away: at 90 degrees the doll showed
				// the back of its head), and up at a viewer looking down from the tilt
				living.yRot = -c.dollAngle;
				living.xRot = -c.dollTilt;
			}
			default -> {
			}
		}
		if ((player.isFallFlying() && c.dollLockElytra) || (player.isAutoSpinAttack() && c.dollLockSpin)) living.xRot = 0F;
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

	public record State(List<Part> parts, float centerX, float feetY, float pixelsPerBlock, float yaw, float tilt, float opacity, float outline,
			float outlineStrength, int x0, int y0, int x1, int y1,
			@Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds) implements PictureInPictureRenderState {
		@Override
		public float scale() {
			return 1F;
		}
	}

	/** Draws the doll's scene into its own off-screen texture (one per frame, apart from the selector's previews). */
	public static final class Renderer extends PictureInPictureRenderer<State> {

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
			// Seen from above by the tilt and flipped into GUI space, as the selector's previews (see MorphGuiEntities).
			poseStack.rotate(new Quaternionf().rotateX((float) Math.toRadians(state.tilt())).rotateZ((float) Math.PI));
			// Turn the whole scene so its root faces the viewer at the chosen angle; every entity keeps its own rotation.
			poseStack.rotate(Axis.YP.rotationDegrees(state.yaw()));
			for (Part part : state.parts()) {
				dispatcher.submit(part.state(), camera, part.offset().x, part.offset().y, part.offset().z, poseStack, collector);
			}
			poseStack.popPose();
		}

		/** Directions the vignette is spread in. */
		private static final int VIGNETTE_ANGLES = 12;
		/** Rings of the vignette between the body's edge and its radius: at least this many, one per pixel past it. */
		private static final int VIGNETTE_RINGS = 6;
		private static final int VIGNETTE_MAX_RINGS = 12;

		/**
		 * Vanilla's blit, plus the vignette and opacity. The vignette is a soft dark haze around the doll: its silhouette
		 * drawn many times underneath, faint and black, at fractional offsets in rings out to the chosen radius, so the
		 * darkness builds up next to the body and fades out smoothly away from it. (The texture's colours are
		 * premultiplied by alpha, so a black colour keeps only the shape.)
		 */
		@Override
		protected void blitTexture(State state, net.minecraft.client.renderer.state.gui.GuiRenderState guiRenderState) {
			float opacity = Math.clamp(state.opacity(), 0F, 1F);
			float radius = state.outline(); // GUI pixels
			if (radius > 0F) {
				// one ring per screen pixel, within limits, so a wide haze stays smooth at any GUI scale
				int screenRadius = Math.round(radius * (float) Minecraft.getInstance().getWindow().getGuiScale());
				int rings = Math.clamp(screenRadius, VIGNETTE_RINGS, VIGNETTE_MAX_RINGS);
				int samples = VIGNETTE_ANGLES * rings;
				// Next to the body about half the samples overlap; give each the share that adds up to the strength there.
				float strength = Math.clamp(state.outlineStrength(), 0F, 0.98F) * opacity;
				float each = 1F - (float) Math.pow(1F - strength, 1.0 / (samples * 0.5));
				int black = Math.round(each * 255F) << 24;
				for (int ring = 1; ring <= rings; ring++) {
					float distance = radius * ring / (float) rings;
					for (int i = 0; i < VIGNETTE_ANGLES; i++) {
						double angle = Math.PI * 2 * (i + (ring % 2) * 0.5) / VIGNETTE_ANGLES;
						blit(state, guiRenderState, (float) (Math.cos(angle) * distance), (float) (Math.sin(angle) * distance), black);
					}
				}
			}
			int a = Math.round(opacity * 255F);
			blit(state, guiRenderState, 0F, 0F, a << 24 | a << 16 | a << 8 | a);
		}

		private void blit(State state, net.minecraft.client.renderer.state.gui.GuiRenderState guiRenderState, float dx, float dy, int color) {
			org.joml.Matrix3x2f pose = new org.joml.Matrix3x2f(state.pose()).translate(dx, dy);
			guiRenderState.addBlitToCurrentLayer(new net.minecraft.client.renderer.state.gui.BlitRenderState(
					net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
					net.minecraft.client.gui.render.TextureSetup.singleTexture(((dev.morphreborn.client.mixin.PictureInPictureRendererAccessor) (Object) this).morph$textureView(),
							com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getRepeat(com.mojang.renderpearl.api.textures.FilterMode.LINEAR)),
					pose, state.x0(), state.y0(), state.x1(), state.y1(), 0.0F, 1.0F, 1.0F, 0.0F, color, state.scissorArea(), null));
		}

		@Override
		protected String getTextureLabel() {
			return "morphreborn paper doll";
		}
	}
}
