package dev.morphreborn.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.MorphClient;
import dev.morphreborn.client.compat.FirstPersonCompat;
import dev.morphreborn.client.data.MorphState;
import dev.morphreborn.client.morph.GiantPlayer;
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
import net.minecraft.client.renderer.entity.state.EnderDragonRenderState;
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
	/** The same static in red: the red tendrils absorb style. */
	public static final Identifier MORPH_SKIN_RED = Identifier.fromNamespaceAndPath(MorphClient.MOD_ID, "textures/skin/morphskin_red.png");

	/** Set while LevelExtractor extracts the first-person player (hands/items must stay vanilla). */
	public static boolean extractingFirstPerson;
	/** Re-entrancy guard: we extract the player's own state while replacing it. */
	private static boolean extracting;

	private static final List<AcquisitionEffect> EFFECTS = new ArrayList<>();
	private static final List<BiomassWisps> WISPS = new ArrayList<>();

	private MorphWorldRenderer() {}

	/**
	 * Fully opaque skin uses an opaque, depth-writing, no-cull type: interpolated poses can briefly flip a face's
	 * winding, and unsorted translucent boxes swap draw order frame to frame, both of which read as flicker.
	 * Only the fade in/out over the real body is translucent.
	 */
	private static RenderType skinType(float alpha) {
		return skinType(MORPH_SKIN, alpha);
	}

	private static RenderType skinType(Identifier skin, float alpha) {
		return alpha >= 0.999F ? RenderTypes.entityCutout(skin) : RenderTypes.entityTranslucent(skin);
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
			boolean firstPerson = FirstPersonCompat.drawingBody(player);
			if (!state.isTransitioning()) {
				EntityRenderState formed = pairState(dispatcher, player, state.next, state.borrowed, state.lockFace, playerState, partialTick);
				if (firstPerson) FirstPersonCompat.place(firstPersonBody(dispatcher, state.next, state.borrowed, formed), ownShift(state.next, state.borrowed));
				// another borrowed form on the same body: a short transformation of its own, old parts into new (and a
				// giant into the giant-sized player it becomes by wearing a player form, or back)
				if (state.isBorrowChanging() && ClientConfig.INSTANCE.transitionAnimation) {
					EntityRenderState before = pairState(dispatcher, player, state.next, state.prevBorrowed, state.lockFace, playerState, partialTick);
					if (before instanceof LivingEntityRenderState f && formed instanceof LivingEntityRenderState t) {
						float p = state.borrowProgress(partialTick);
						return transition(dispatcher, playerState, f, t, MorphState.transitionAt(p), MorphState.skinAlphaAt(p));
					}
				}
				return formed;
			}
			// The player's hitbox (scale attribute) is gameplay only: the server changes it in one step half-way through.
			// The player form is always drawn at natural size, as in the original, so the animation doesn't jump with it.
			if (playerState instanceof LivingEntityRenderState own && own.scale != 1F && own.scale > 0F) {
				own.boundingBoxWidth /= own.scale;
				own.boundingBoxHeight /= own.scale;
				own.scale = 1F;
			}
			if (!ClientConfig.INSTANCE.transitionAnimation) {
				// RE:Morph's animation switched off in the settings: what players without the mod see instead.
				// The old body shrinks, swaps at the midpoint and the new one grows (particles: VanillaStyleEffects).
				boolean before = state.morphProgress(partialTick) < 0.5F;
				MorphRef shown = before ? state.prev : state.next;
				MorphRef lent = before ? state.prevBorrowed : state.borrowed;
				EntityRenderState plain = pairState(dispatcher, player, shown, lent, before ? state.prevLockFace : state.lockFace, playerState, partialTick);
				if (plain instanceof LivingEntityRenderState living) living.scale *= state.vanillaScale(partialTick);
				if (firstPerson) FirstPersonCompat.place(firstPersonBody(dispatcher, shown, lent, plain), ownShift(shown, lent));
				return plain;
			}
			// multimorph parts morph with the bodies: the old body's borrowed form away, the new one's in (a giant-sized
			// player melts as the player it is drawn as)
			EntityRenderState from = pairState(dispatcher, player, state.prev, state.prevBorrowed, state.prevLockFace, playerState, partialTick);
			EntityRenderState to = pairState(dispatcher, player, state.next, state.borrowed, state.lockFace, playerState, partialTick);
			if (!(from instanceof LivingEntityRenderState f) || !(to instanceof LivingEntityRenderState t)) {
				if (firstPerson) FirstPersonCompat.place(firstPersonBody(dispatcher, state.next, state.borrowed, to), ownShift(state.next, state.borrowed));
				return to;
			}
			MorphTransitionRenderState out = transition(dispatcher, playerState, f, t, state.transitionSine(partialTick), state.skinAlpha(partialTick));
			// In first person the animation plays on the body First-person Model draws, heads left out, and the body
			// slides from the old form's placement to the new one's as the boxes move.
			if (firstPerson) {
				FirstPersonCompat.place(firstPersonBody(dispatcher, state.prev, state.prevBorrowed, f), firstPersonBody(dispatcher, state.next, state.borrowed, t),
						out.transition, ownShift(state.prev, state.prevBorrowed), ownShift(state.next, state.borrowed));
			}
			return out;
		} finally {
			extracting = false;
		}
	}

	/**
	 * The local player's morph, about to be drawn as their own body in first person by First-person Model: a mob
	 * gets its head hidden at draw time, and this returns where to put the body so the camera sits at its head (used
	 * from the next frame on). Null keeps that mod's own player-shaped placement: player forms, and mobs whose head is
	 * on top of the body. {@code form} is the main body and {@code lent} what it borrows.
	 * <p>
	 * A giant wearing a player form (GiantPlayer) is drawn as that player, giant-sized, and is handled as the giant is:
	 * its head is hidden here (the camera, at the giant body's eye height, is inside that head, and a transformation's
	 * melt leaves it out too), and like the giant's upright head it keeps that mod's own placement.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	/**
	 * How much First-person Model's own (player-shaped) shift is scaled for this body: a giant-sized player is a player
	 * {@link GiantPlayer#GIANT_PLAYER_SCALE} times bigger, and the mod's fixed quarter-block shift left its chest in front
	 * of the camera (my call: it should look as a player does, seen only when looking down). 1 for everything else.
	 */
	private static float ownShift(@Nullable MorphRef form, @Nullable MorphRef lent) {
		return GiantPlayer.isGiantPlayer(form, lent) ? GiantPlayer.GIANT_PLAYER_SCALE : 1F;
	}

	private static @Nullable Vec3 firstPersonBody(EntityRenderDispatcher dispatcher, @Nullable MorphRef form, @Nullable MorphRef lent, EntityRenderState formed) {
		if (GiantPlayer.isGiantPlayer(form, lent)) {
			((FirstPersonMorph.Mark) formed).morph$setFirstPersonBody(true);
			return null;
		}
		if (form == null || form.isSelf() || form.isPlayer()) return null;
		if (formed instanceof LivingEntityRenderState living) {
			((FirstPersonMorph.Mark) living).morph$setFirstPersonBody(true);
			return FirstPersonMorph.headOffset(dispatcher.getRenderer(living), living);
		}
		if (formed instanceof EnderDragonRenderState) {
			((FirstPersonMorph.Mark) formed).morph$setFirstPersonBody(true);
			return FirstPersonMorph.dragonOffset;
		}
		return null;
	}

	private static boolean noFace(EntityRenderState state) {
		return ((FirstPersonMorph.Mark) state).morph$firstPersonBody();
	}

	/**
	 * Render state for a main body {@code form} of {@code player} wearing {@code lent} (null when it borrows nothing): the
	 * body with the borrowed head and parts on it, or, for a giant wearing a player form, that player drawn at a giant's
	 * size instead, with nothing attached (GiantPlayer). The player's walk, swing, sneak and look come from the player, as
	 * for any player-form morph, and its name tag goes above the giant-sized head.
	 */
	private static EntityRenderState pairState(EntityRenderDispatcher dispatcher, AbstractClientPlayer player, @Nullable MorphRef form, @Nullable MorphRef lent,
			net.minecraft.core.@Nullable Direction lockFace, EntityRenderState playerState, float partialTick) {
		if (GiantPlayer.isGiantPlayer(form, lent)) {
			LivingEntity giant = MorphClient.ENTITIES.giantPlayerForWorld(player.getUUID(), lent);
			if (giant != null) return stateOf(dispatcher, player, lent, giant, playerState, partialTick);
		}
		EntityRenderState body = bodyState(dispatcher, player, form, lockFace, playerState, partialTick);
		borrow(dispatcher, player, form, lent, body, playerState, partialTick);
		return body;
	}

	/** Multimorph: {@code body} (the form {@code form}) is drawn with {@code lent}'s head and parts, if it can be. */
	private static void borrow(EntityRenderDispatcher dispatcher, AbstractClientPlayer player, @Nullable MorphRef form, @Nullable MorphRef lent,
			EntityRenderState body, EntityRenderState playerState, float partialTick) {
		if (lent == null || form == null || form.isSelf()) return;
		// a baby body borrows from the borrowed creature's baby, where it has one, as the plugin shows it to players
		// without the mod
		LivingEntity baby = body instanceof LivingEntityRenderState b && b.isBaby && !lent.isPlayer() ? MorphClient.ENTITIES.babyForWorld(player.getUUID(), lent) : null;
		HybridMorph.attach(dispatcher, body, baby != null ? stateOf(dispatcher, player, lent, baby, playerState, partialTick)
				: formState(dispatcher, player, lent, playerState, partialTick));
	}

	/** A transformation's render state: {@code from} morphing into {@code to}, the player's place and light. */
	private static MorphTransitionRenderState transition(EntityRenderDispatcher dispatcher, EntityRenderState playerState, LivingEntityRenderState f,
			LivingEntityRenderState t, float transition, float skinAlpha) {
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
		out.transition = transition;
		out.skinAlpha = skinAlpha;
		return out;
	}

	/**
	 * Render state for a main body of {@code player} (the body now, or either end of a transformation; never a borrowed
	 * form): a shulker is pinned in its block when the server holds it there ({@code lockFace}), and otherwise drawn as
	 * a free one (ShulkerLock.pin).
	 */
	private static EntityRenderState bodyState(EntityRenderDispatcher dispatcher, AbstractClientPlayer player, @Nullable MorphRef form,
			net.minecraft.core.@Nullable Direction lockFace, EntityRenderState playerState, float partialTick) {
		EntityRenderState out = formState(dispatcher, player, form, playerState, partialTick);
		if (out instanceof net.minecraft.client.renderer.entity.state.ShulkerRenderState shulker && form != null && !form.isSelf()) {
			dev.morphreborn.client.morph.ShulkerLock.pin(shulker, MorphClient.ENTITIES.forWorld(player.getUUID(), form), lockFace, player, partialTick);
		}
		return out;
	}

	/** Render state for one form of {@code player}. The player's own form is their regular state. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static EntityRenderState formState(EntityRenderDispatcher dispatcher, AbstractClientPlayer player, @Nullable MorphRef form, EntityRenderState playerState,
			float partialTick) {
		if (form == null || form.isSelf()) return playerState;
		LivingEntity morph = MorphClient.ENTITIES.forWorld(player.getUUID(), form);
		if (morph == null) return playerState;
		return stateOf(dispatcher, player, form, morph, playerState, partialTick);
	}

	/** Render state for {@code form} of {@code player}, drawn as {@code morph}. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static EntityRenderState stateOf(EntityRenderDispatcher dispatcher, AbstractClientPlayer player, MorphRef form, LivingEntity morph,
			EntityRenderState playerState, float partialTick) {
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
			submitSkin(ModelCapture.captureHybrid(dispatcher, s.fromRenderer, s.from, noFace(s.from)), x, y, z, poseStack, collector, s.lightCoords, s.skinAlpha, true);
		} else if (s.transition >= 1F) {
			dispatcher.submit(s.to, camera, x, y, z, poseStack, collector);
			submitSkin(ModelCapture.captureHybrid(dispatcher, s.toRenderer, s.to, noFace(s.to)), x, y, z, poseStack, collector, s.lightCoords, s.skinAlpha, true);
		} else {
			List<ModelCapture.Box> from = ModelCapture.captureHybrid(dispatcher, s.fromRenderer, s.from, noFace(s.from));
			List<ModelCapture.Box> to = ModelCapture.captureHybrid(dispatcher, s.toRenderer, s.to, noFace(s.to));
			List<ModelCapture.Box> mid = ModelCapture.interpolate(from, to, s.from.boundingBoxHeight / 2F, s.to.boundingBoxHeight / 2F, s.transition);
			submitSkin(mid, x, y, z, poseStack, collector, s.lightCoords, 1F, false);
		}
	}

	private static void submitSkin(List<ModelCapture.Box> boxes, double x, double y, double z, PoseStack poseStack, SubmitNodeCollector collector, int light,
			float alpha, boolean overlayGrow) {
		submitSkin(boxes, x, y, z, poseStack, collector, light, alpha, overlayGrow, MORPH_SKIN);
	}

	private static void submitSkin(List<ModelCapture.Box> boxes, double x, double y, double z, PoseStack poseStack, SubmitNodeCollector collector, int light,
			float alpha, boolean overlayGrow, Identifier skin) {
		if (boxes.isEmpty() || alpha <= 0F) return;
		poseStack.pushPose();
		poseStack.translate(x, y, z);
		collector.submitCustomGeometry(poseStack, skinType(skin, alpha), (pose, buffer) -> ModelCapture.render(boxes, pose, buffer, light, OverlayTexture.NO_OVERLAY, alpha, overlayGrow));
		poseStack.popPose();
	}

	// ------------------------------------------------------------------ acquisition

	/**
	 * Someone absorbed a mob: {@code newMorph} for a new morph, false for a kill that only gave biomass (Survival). Each
	 * plays the style chosen for it in the settings, so the two never look alike unless the player wants them to.
	 */
	public static void startAcquisition(LivingEntity origin, LivingEntity acquired, boolean newMorph) {
		ClientConfig c = ClientConfig.INSTANCE;
		switch (newMorph ? c.absorbMorph : c.absorbBiomass) {
			case "TENDRILS" -> EFFECTS.add(new AcquisitionEffect(origin, acquired, false));
			case "RED_TENDRILS" -> EFFECTS.add(new AcquisitionEffect(origin, acquired, true));
			case "WISPS" -> WISPS.add(new BiomassWisps(origin, acquired));
			case "PLAIN" -> VanillaStyleEffects.acquisition(origin, acquired, newMorph); // what players without the mod see
			default -> {
			}
		}
	}

	public static void tickEffects() {
		RageTethers.tick();
		for (Iterator<AcquisitionEffect> it = EFFECTS.iterator(); it.hasNext(); ) {
			AcquisitionEffect e = it.next();
			e.tick();
			if (e.removed) it.remove();
		}
		for (Iterator<BiomassWisps> it = WISPS.iterator(); it.hasNext(); ) {
			BiomassWisps w = it.next();
			w.tick();
			if (w.removed) it.remove();
		}
	}

	public static void clearEffects() {
		RageTethers.clear();
		EFFECTS.clear();
		WISPS.clear();
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public static void collectSubmits(LevelRenderContext ctx) {
		RageTethers.collectSubmits(ctx);
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
				submitSkin(e.acquiredCapture, at.x, at.y, at.z, poseStack, collector, dispatcher.getPackedLightCoords(e.acquired, partialTick), alpha, true,
						e.red ? dev.morphreborn.client.arm.render.ArmTint.redSkinOf(e.origin.getId()) : MORPH_SKIN); // red: the absorber's colour
			}
			Vec3 origin = e.renderPos(partialTick).subtract(cam);
			poseStack.pushPose();
			poseStack.translate(origin.x, origin.y, origin.z);
			collector.submitCustomGeometry(poseStack, skinType(e.red ? dev.morphreborn.client.arm.render.ArmTint.redSkinOf(e.origin.getId()) : MORPH_SKIN,
					e.firstPersonFade() ? 0F : 1F),
					(pose, buffer) -> e.render(pose, buffer, light, partialTick));
			poseStack.popPose();
		}
	}
}
