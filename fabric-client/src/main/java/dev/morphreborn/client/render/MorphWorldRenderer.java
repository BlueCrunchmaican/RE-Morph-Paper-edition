package dev.morphreborn.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.MorphClient;
import dev.morphreborn.client.data.MorphState;
import dev.morphreborn.client.morph.MorphSync;
import dev.morphreborn.client.net.MorphRef;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** In-world rendering of morphs, transitions and acquisition effects. */
public final class MorphWorldRenderer {
	public static final Identifier MORPH_SKIN = Identifier.fromNamespaceAndPath(MorphClient.MOD_ID, "textures/skin/morphskin.png");

	/** Set while LevelExtractor extracts the first-person player (hands/items must stay vanilla). */
	public static boolean extractingFirstPerson;
	/** Re-entrancy guard: we extract the player's own state while replacing it. */
	private static boolean extracting;

	private static final List<AcquisitionEffect> EFFECTS = new ArrayList<>();

	private MorphWorldRenderer() {}

	/**
	 * Fully opaque skin uses an opaque, depth-writing, no-cull type: interpolated poses can briefly flip a face's
	 * winding, and unsorted translucent boxes swap draw order frame to frame, both of which read as flicker.
	 * Only the fade in/out over the real body is translucent.
	 */
	private static RenderType skinType(float alpha) {
		return alpha >= 0.999F ? RenderTypes.entityCutout(MORPH_SKIN) : RenderTypes.entityTranslucent(MORPH_SKIN);
	}

	// ------------------------------------------------------------------ extraction

	/** Called at the head of {@code EntityRenderDispatcher.extractEntity}. Returns a replacement state, or null. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static @Nullable EntityRenderState extract(EntityRenderDispatcher dispatcher, Entity entity, float partialTick) {
		if (extracting || extractingFirstPerson || !(entity instanceof AbstractClientPlayer player)) return null;
		if (entity instanceof dev.morphreborn.client.morph.MorphEntities.MorphPlayer) return null;
		MorphState state = MorphClient.STATES.get(player.getUUID());
		if (state == null || !state.isMorphed()) return null;
		extracting = true;
		try {
			EntityRenderer playerRenderer = dispatcher.getRenderer(player);
			EntityRenderState playerState = playerRenderer.createRenderState(player, partialTick);
			if (!state.isTransitioning()) {
				return formState(dispatcher, player, state.next, playerState, partialTick);
			}
			// The server eases the player's hitbox (scale attribute) once per tick during a transformation; that is
			// gameplay only. Drawing the player's own body at that stepped scale made the animation judder, so the
			// player form is always drawn at natural size, as in the original.
			if (playerState instanceof LivingEntityRenderState own && own.scale != 1F && own.scale > 0F) {
				own.boundingBoxWidth /= own.scale;
				own.boundingBoxHeight /= own.scale;
				own.scale = 1F;
			}
			if (!ClientConfig.INSTANCE.transitionAnimation) {
				// RE:Morph's animation switched off in the settings: what players without the mod see instead.
				// The old body shrinks, swaps at the midpoint and the new one grows (particles: VanillaStyleEffects).
				MorphRef shown = state.morphProgress(partialTick) < 0.5F ? state.prev : state.next;
				EntityRenderState plain = formState(dispatcher, player, shown, playerState, partialTick);
				if (plain instanceof LivingEntityRenderState living) living.scale *= state.vanillaScale(partialTick);
				return plain;
			}
			EntityRenderState from = formState(dispatcher, player, state.prev, playerState, partialTick);
			EntityRenderState to = formState(dispatcher, player, state.next, playerState, partialTick);
			if (!(from instanceof LivingEntityRenderState f) || !(to instanceof LivingEntityRenderState t)) return to;
			MorphTransitionRenderState out = new MorphTransitionRenderState();
			out.entityType = playerState.entityType;
			out.x = playerState.x;
			out.y = playerState.y;
			out.z = playerState.z;
			out.lightCoords = playerState.lightCoords;
			out.boundingBoxWidth = playerState.boundingBoxWidth;
			out.boundingBoxHeight = playerState.boundingBoxHeight;
			out.distanceToCameraSq = playerState.distanceToCameraSq;
			out.from = f;
			out.to = t;
			out.fromRenderer = dispatcher.getRenderer(f);
			out.toRenderer = dispatcher.getRenderer(t);
			out.transition = state.transitionSine(partialTick);
			out.skinAlpha = state.skinAlpha(partialTick);
			return out;
		} finally {
			extracting = false;
		}
	}

	/** Render state for one form of {@code player}. The player's own form is their regular state. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static EntityRenderState formState(EntityRenderDispatcher dispatcher, AbstractClientPlayer player, @Nullable MorphRef form, EntityRenderState playerState,
			float partialTick) {
		if (form == null || form.isSelf()) return playerState;
		LivingEntity morph = MorphClient.ENTITIES.forWorld(player.getUUID(), form);
		if (morph == null) return playerState;
		EntityRenderer renderer = dispatcher.getRenderer(morph);
		EntityRenderState morphState = renderer.createRenderState(morph, partialTick);
		MorphSync.copyAnimation(morphState, playerState, player, partialTick);
		applyNameTag(morphState, playerState, form, morph);
		return morphState;
	}

	/**
	 * Same rules as the server applies to Lib's Disguises, so modded and vanilla viewers see the same name:
	 * MORPH hides the real name on mob morphs (a name-tagged mob keeps its own name) and shows the copied player's
	 * name on player morphs; PLAYER always shows the real name; HIDDEN shows nothing.
	 */
	private static void applyNameTag(EntityRenderState morphState, EntityRenderState playerState, MorphRef form, LivingEntity morph) {
		boolean nameWouldShow = playerState.nameTag != null;
		String mode = dev.morphreborn.client.net.ClientNet.nameTagMode();
		if (!ClientConfig.INSTANCE.showNameTags || mode.equals("HIDDEN")) {
			morphState.nameTag = null;
			morphState.scoreText = null;
			return;
		}
		Vec3 attachment = new Vec3(0, morph.getBbHeight() + 0.25, 0);
		if (mode.equals("PLAYER")) {
			morphState.nameTag = playerState.nameTag;
			morphState.scoreText = playerState.scoreText;
			if (nameWouldShow) morphState.nameTagAttachment = attachment;
			return;
		}
		morphState.scoreText = null;
		if (form.isPlayer()) {
			morphState.nameTag = nameWouldShow && form.skin() != null ? net.minecraft.network.chat.Component.literal(form.skin().name()) : null;
			if (morphState.nameTag != null) morphState.nameTagAttachment = attachment;
		}
		// mob morphs: whatever the mob's own renderer decided (its custom name, if it has one), never the player's name
	}

	// ------------------------------------------------------------------ submission

	/** Port of MorphRenderHandler.renderTransitionState: skin over the old body, box melt, skin off the new body. */
	public static void submitTransition(EntityRenderDispatcher dispatcher, MorphTransitionRenderState s, CameraRenderState camera, double x, double y, double z,
			PoseStack poseStack, SubmitNodeCollector collector) {
		if (s.transition <= 0F) {
			dispatcher.submit(s.from, camera, x, y, z, poseStack, collector);
			submitSkin(ModelCapture.capture(s.fromRenderer, s.from), x, y, z, poseStack, collector, s.lightCoords, s.skinAlpha, true);
		} else if (s.transition >= 1F) {
			dispatcher.submit(s.to, camera, x, y, z, poseStack, collector);
			submitSkin(ModelCapture.capture(s.toRenderer, s.to), x, y, z, poseStack, collector, s.lightCoords, s.skinAlpha, true);
		} else {
			List<ModelCapture.Box> from = ModelCapture.capture(s.fromRenderer, s.from);
			List<ModelCapture.Box> to = ModelCapture.capture(s.toRenderer, s.to);
			List<ModelCapture.Box> mid = ModelCapture.interpolate(from, to, s.from.boundingBoxHeight / 2F, s.to.boundingBoxHeight / 2F, s.transition);
			submitSkin(mid, x, y, z, poseStack, collector, s.lightCoords, 1F, false);
		}
	}

	private static void submitSkin(List<ModelCapture.Box> boxes, double x, double y, double z, PoseStack poseStack, SubmitNodeCollector collector, int light,
			float alpha, boolean overlayGrow) {
		if (boxes.isEmpty() || alpha <= 0F) return;
		poseStack.pushPose();
		poseStack.translate(x, y, z);
		collector.submitCustomGeometry(poseStack, skinType(alpha), (pose, buffer) -> ModelCapture.render(boxes, pose, buffer, light, OverlayTexture.NO_OVERLAY, alpha, overlayGrow));
		poseStack.popPose();
	}

	// ------------------------------------------------------------------ acquisition

	public static void startAcquisition(LivingEntity origin, LivingEntity acquired) {
		if (!ClientConfig.INSTANCE.acquisitionAnimation) {
			VanillaStyleEffects.acquisition(origin, acquired); // the tendrils switched off: what players without the mod see
			return;
		}
		EFFECTS.add(new AcquisitionEffect(origin, acquired));
	}

	public static void tickEffects() {
		for (Iterator<AcquisitionEffect> it = EFFECTS.iterator(); it.hasNext(); ) {
			AcquisitionEffect e = it.next();
			e.tick();
			if (e.removed) it.remove();
		}
	}

	public static void clearEffects() {
		EFFECTS.clear();
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public static void collectSubmits(LevelRenderContext ctx) {
		if (EFFECTS.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vec3 cam = ctx.levelState().cameraRenderState.pos;
		PoseStack poseStack = ctx.poseStack();
		SubmitNodeCollector collector = ctx.submitNodeCollector();
		EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
		for (AcquisitionEffect e : EFFECTS) {
			int light = dispatcher.getPackedLightCoords(e.origin, partialTick);
			if (e.acquiredCapture == null && e.age <= 1 && !e.isAcquiredRemoved()) {
				EntityRenderer renderer = dispatcher.getRenderer(e.acquired);
				if (renderer.createRenderState(e.acquired, partialTick) instanceof LivingEntityRenderState living) {
					living.deathTime = 0;
					living.hasRedOverlay = false;
					e.acquiredCapture = ModelCapture.capture(renderer, living);
				}
			}
			// The morph skin creeping over the mob during its first 10 ticks.
			if (e.acquiredCapture != null && !e.isAcquiredRemoved()) {
				float alpha = Math.clamp((e.age + partialTick) / 10F, 0F, 1F);
				Vec3 at = e.acquired.getPosition(partialTick).subtract(cam);
				submitSkin(e.acquiredCapture, at.x, at.y, at.z, poseStack, collector, dispatcher.getPackedLightCoords(e.acquired, partialTick), alpha, true);
			}
			Vec3 origin = e.renderPos(partialTick).subtract(cam);
			poseStack.pushPose();
			poseStack.translate(origin.x, origin.y, origin.z);
			collector.submitCustomGeometry(poseStack, skinType(e.firstPersonFade() ? 0F : 1F), (pose, buffer) -> e.render(pose, buffer, light, partialTick));
			poseStack.popPose();
		}
	}
}
