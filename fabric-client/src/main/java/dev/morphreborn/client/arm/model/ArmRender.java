package dev.morphreborn.client.arm.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.morphreborn.client.MorphClient;
import dev.morphreborn.client.arm.ArmClientModule;
import dev.morphreborn.client.arm.ArmColours;
import dev.morphreborn.client.arm.ArmHooks;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmState;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.geom.AccessoryRenderer;
import dev.morphreborn.client.arm.geom.ArmDraw;
import dev.morphreborn.client.arm.geom.ArmGeometry;
import dev.morphreborn.client.arm.geom.LureRenderer;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmTex;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.data.MorphState;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityTypes;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The look of arm forms, as one client module (ArmClientModules loads it by name). It wires the drawing pipeline into
 * the game:
 * <ul>
 *   <li>the item path (ArmHooks.itemLayers → {@link ArmFormItemModel} → {@link ArmFormRenderer}), so an arm stack is
 *       the arm in every view and never the vanilla tool;</li>
 *   <li>the avatar snapshot (ArmHooks.extract → {@link ArmSnapshot}) and the sheath layer on both player renderers
 *       ({@link ArmSheathLayer});</li>
 *   <li>the first-person lure and the accessories after the hands (ArmHooks.afterHands), once a frame, each left out when the
 *       world pass already drew it on First-person Model's body (D41; the lantern and the other three alike since 2026-11-24);</li>
 *   <li>the in-hand reactions ({@link ArmReactions}, an effect listener);</li>
 *   <li>the frame bookkeeping: a frame counter and whether the world's own submits are running (inside
 *       LevelRenderer.submitFeatures), which tells a third-person draw in the world (recorded, simulated) from
 *       one in the paper doll or the inventory preview (neither);</li>
 *   <li>{@code /morphclient arm <form> [level]}, the arm preview screen ({@link ArmPreviewScreen}), which works on any
 *       server or none.</li>
 * </ul>
 */
public final class ArmRender implements ArmClientModule {
	private static long frame;
	private static boolean worldPass;
	/** The last frame the hand pass ran (Iris runs it twice). */
	private static long handsFrame = Long.MIN_VALUE;
	/**
	 * The world pass drew the camera player's lure (D41: First-person Model's body has it) since the hand pass last ran:
	 * the hand pass leaves its own out (and only its own lure: the accessories it still has to draw are not skipped with it). A flag
	 * the world pass sets and the hand pass takes, and not a frame number: the world's entities are submitted before
	 * {@code START_MAIN} counts the frame, so a frame number never matched and both were drawn (2026-11-15: two lanterns with
	 * First-person Model on). Set again from 2026-11-24, when the lantern went back on First-person Model's body: it is set only
	 * when the body drew the lantern, so where that mod shows vanilla hands (no body arm to hang it on) the hand pass still does.
	 */
	private static boolean lureInWorld;
	/** The preview asked for by the command: opened on the next tick, once the chat screen that ran it has closed. */
	private static @Nullable ArmPreviewScreen pending;

	@Override
	public void init() {
		ArmHooks.itemLayers = ArmFormItemModel::append;
		ArmHooks.extract = ArmSnapshot::extract;
		ArmHooks.afterHands = ArmRender::afterHands;
		ArmHooks.heldPose = FpHeld::record;
		LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
			if (type == EntityTypes.PLAYER && renderer instanceof AvatarRenderer<?> avatar) {
				@SuppressWarnings("unchecked")
				RenderLayerParent<AvatarRenderState, PlayerModel> parent = (RenderLayerParent<AvatarRenderState, PlayerModel>) (Object) avatar;
				helper.register(new ArmSheathLayer(parent));
			}
		});
		// the world pass itself is marked around LevelRenderer.submitFeatures (LevelRendererArmMixin): in 26.3 the
		// entities are submitted there, before START_MAIN
		LevelRenderEvents.START_MAIN.register(ctx -> frame++);
		// a backstop: a level render that threw before its submits ended must not leave the GUI thinking it is the world
		LevelRenderEvents.END_MAIN.register(ctx -> worldPass = false);
		Arms.addFxListener(ArmReactions::onFx);
		// a new world's entity ids belong to other players: no receding sheath may carry over (ArmFx clears its own too)
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((mc, level) -> ArmReactions.clear());
		PictureInPictureRendererRegistry.register(context -> new ArmPreviewScreen.Renderer());
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(command()));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			ArmPreviewScreen screen = pending;
			if (screen != null) {
				pending = null;
				mc.gui.setScreen(screen);
			}
		});
	}

	@Override
	public void tick(Minecraft mc) {
		ArmReactions.tick(mc);
		ArmDrawFactory.prune();
	}

	@Override
	public void reset() {
		ArmReactions.clear();
		ArmDrawFactory.clear();
		dev.morphreborn.client.arm.geom.FpClearance.forget();
		dev.morphreborn.client.arm.geom.FpLayout.clear();
	}

	/** The render frame number: counts the level renders. */
	public static long frame() {
		return frame;
	}

	/** LevelRendererArmMixin: the level render's own submits start (true) or end (false). */
	public static void worldSubmits(boolean running) {
		if (running) {
			lureInWorld = false; // this frame's world pass starts: nothing of it drawn yet
			accessoryOnBodyMask = 0;
		}
		worldPass = running;
	}

	/** True while the world's own submits run (entities and block entities of the level render), false in the GUI. */
	public static boolean inWorldPass() {
		return worldPass;
	}

	/** Whether the accessory geometry being drawn right now was submitted in the world pass (see {@link #drawingInWorld}). */
	private static boolean accessoryInWorld;

	/**
	 * ArmSheathLayer: the accessory draw about to run (its custom-geometry lambda, which the game runs later, in its feature dispatch, after
	 * the world's submits have ended: {@link #inWorldPass} is false by then) was submitted in the world pass, or not. Set around that draw
	 * with what the submit saw, so a renderer that must tell the world's pose from the paper doll's can (the spyglass eye's look).
	 */
	static void drawingInWorld(boolean world) {
		accessoryInWorld = world;
	}

	/** True while an accessory is drawn that was submitted in the world pass; unlike {@link #inWorldPass}, true inside the draw's own lambda. */
	public static boolean accessoryDrawnInWorld() {
		return accessoryInWorld;
	}

	/**
	 * Which accessories (bit i is {@code ArmProtocol.ACCESSORIES.get(i)}) the world pass drew on the camera player's own body this frame
	 * (2026-11-24): with First-person Model the shield, spyglass and flint and steel are on the real arm, as the tools are, so the hand pass
	 * leaves them out and the effects start from the body's point. Set by the sheath layer, read by the hand pass and the effects, cleared
	 * when the next world pass starts.
	 */
	private static int accessoryOnBodyMask;

	/** The sheath layer drew accessory {@code index} (its place in {@code ArmProtocol.ACCESSORIES}) on the camera player's own body. */
	static void accessoryDrawnOnBody(int index) {
		accessoryOnBodyMask |= 1 << index;
	}

	/** Whether this frame's world pass drew accessory {@code id} on the camera player's own body, so the hand pass does not. */
	public static boolean accessoryOnBody(String id) {
		int i = ArmProtocol.accessoryIndex(id);
		return i >= 0 && (accessoryOnBodyMask & (1 << i)) != 0;
	}

	/** The sheath layer drew the camera player's lure in this frame's world pass. */
	static void lureDrawnInWorld() {
		lureInWorld = true;
	}

	// ------------------------------------------------------------------ the first-person lure

	/** The hand pass's pose when vanilla's items were submitted (blocks), kept for the clearance of the overlay's accessories. */
	private static final org.joml.Matrix4f HAND_BASE = new org.joml.Matrix4f();

	/** The lure's point in the hand pass (view space px), worked out each frame: the lure's own rest point, per hand. */
	private static final Vector3f FP_LURE = new Vector3f();

	/**
	 * ArmHooks.afterHands: the lantern's lure over your other arm in first person (phase 2's LureGeometry), in the hand
	 * pass's view-anchored space in px (the pose there is the bobbed view, scaled to model pixels). Once a frame, and not
	 * when the world pass drew it on First-person Model's body already.
	 */
	private static void afterHands(float partialTick, PoseStack poseStack, SubmitNodeCollector collector, PlayerRenderState playerState,
			FirstPersonHandsAndItemsRenderState hands) {
		if (handsFrame == frame) return;
		handsFrame = frame;
		boolean lureOnBody = lureInWorld; // the world pass drew the lantern on First-person Model's body: this pass leaves out its own lure, not the accessories
		lureInWorld = false;
		int onBody = accessoryOnBodyMask; // not cleared: the effects read it too, until the next world pass
		// while the spyglass scopes (PlayerScopeMixin makes the player count as scoping) vanilla draws no hands, and the lure and every
		// accessory in this pass go with them: nothing of the arm shows over the scope's view (review of 2026-11-16)
		if (hands.isScoping) return;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) return;
		ArmState st = Arms.state(player.getUUID());
		if (st == null) return;
		boolean lantern = AccessoryRouting.lureInOverlay(st.lanternLit || st.lanternGoingOut(Arms.TICK), lureOnBody);
		if (!AccessoryRouting.handPassNeeded(lantern, liveAccessories(st), onBody)) return;
		MorphState morph = MorphClient.STATES.get(player.getUUID());
		if (morph != null && (morph.isMorphed() || morph.isTransitioning())) return;
		HAND_BASE.set(poseStack.last().pose()); // the pose vanilla's held items are submitted with, before the px scale
		poseStack.pushPose();
		try {
			poseStack.scale(1F / 16F, 1F / 16F, 1F / 16F);
			ArmDraw base = ArmDrawFactory.forSheath(player, ArmDrawFactory.partialTick());
			// where the lure is drawn: mirrored for a left main arm and drawn in when the other hand is busy, so the flare
			// and the beam start from it
			if (lantern) {
				if (base != null) dev.morphreborn.client.arm.geom.LureGeometry.restPointFirstPerson(base.leftHand(),
						dev.morphreborn.client.arm.geom.LureGeometry.offhandBlend(), FP_LURE);
				else FP_LURE.set(-0.55F * 16F, (-0.32F - 0.12F) * 16F, -0.80F * 16F);
				ArmTipsRecorder.record(player.getId(), true, ArmTips.LURE, poseStack.last().pose(), FP_LURE);
			}
			LureRenderer lure = lantern ? ArmGeometry.lure() : null;
			if (base == null) return;
			int light = playerState.avatarRenderState != null ? playerState.avatarRenderState.lightCoords : ArmTex.FULL_BRIGHT;
			ArmDraw d = ArmDrawFactory.finishSheath(new ArmDraw(base.form(), base.tier(), base.lod(), ArmDraw.CTX_FP, base.leftHand(), base.slim(),
					base.chestArmour(), base.time(), base.seed(), base.state(), base.progress(), base.fromForm(), base.swing(), base.bowPull(), base.mining(),
					base.biomassFrac(), base.biomassAbs(), base.surge(), base.specialAge(), base.specialKind(), base.hitAge(), base.breakAge(), base.pulses(),
					base.pulseCount(), base.lanternLit(), base.lanternAge(), base.recallLeft(), base.glow(), base.dissolve(), false, null, 0F, -1F, 0F, null,
					light, base.pulseSpeed(), base.accessories(), base.accessoryArgs(), base.accessoryAges(), base.thrown()), ArmDrawFactory.Pass.HAND,
					poseStack.last(), light, 0);
			// nothing passes through anything: the accessories make room for what is in the hand and for each other (2026-11-24)
			FpOverlay.place(d, HAND_BASE, lantern, onBody);
			for (ArmLayer layer : new ArmLayer[] {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW}) {
				int layerLight = layer == ArmLayer.GLOW ? ArmTex.FULL_BRIGHT : light;
				if (lure != null) {
					collector.submitCustomGeometry(poseStack, ArmTex.type(layer, d.dissolve()), (p, vc) -> {
						SkinMesh m = SkinMesh.begin(p, vc, layerLight, OverlayTexture.NO_OVERLAY);
						try {
							lure.emitFirstPerson(d, layer, m);
						} catch (RuntimeException e) {
							MorphClient.LOGGER.debug("First-person lure failed", e);
						}
					});
				}
				if (layer == ArmLayer.GLOW && !d.glow()) continue;
				// the other accessories, each in the same hand-pass space (a renderer that needs the off hand's side reads leftHand)
				for (int i = 1; i < ArmProtocol.ACCESSORIES.size(); i++) {
					String id = ArmProtocol.ACCESSORIES.get(i);
					AccessoryRenderer renderer = d.accessoryOn(id) && (onBody & (1 << i)) == 0 ? ArmGeometry.accessory(id) : null;
					if (renderer == null) continue;
					collector.submitCustomGeometry(poseStack, ArmTex.type(layer, d.dissolve()), (p, vc) -> {
						SkinMesh m = SkinMesh.begin(p, vc, layerLight, OverlayTexture.NO_OVERLAY);
						try {
							renderer.emitFirstPerson(d, layer, m);
						} catch (RuntimeException e) {
							MorphClient.LOGGER.debug("First-person accessory {} failed", id, e);
						}
					});
				}
			}
		} finally {
			poseStack.popPose();
		}
	}

	/** Which accessories (bit i is {@code ArmProtocol.ACCESSORIES.get(i)}) are on or drawing back into the arm. */
	private static int liveAccessories(ArmState st) {
		int live = 0;
		for (int i = 1; i < ArmProtocol.ACCESSORIES.size(); i++) {
			String id = ArmProtocol.ACCESSORIES.get(i);
			if (st.accessoryOn(id) || st.accessoryGoingOut(id, Arms.TICK)) live |= 1 << i;
		}
		return live;
	}

	// ------------------------------------------------------------------ the command

	/** {@code /morphclient arm [form] [level]}: merged into the mod's own /morphclient (Brigadier joins same-named literals). */
	private static LiteralArgumentBuilder<FabricClientCommandSource> command() {
		return ClientCommands.literal("morphclient").then(ClientCommands.literal("arm")
				.executes(c -> open(c.getSource(), ArmProtocol.KATANA, ArmProtocol.TIER_DIAMOND))
				.then(ClientCommands.argument("form", StringArgumentType.word())
						.suggests((c, builder) -> SharedSuggestionProvider.suggest(ArmProtocol.FORMS, builder))
						.executes(c -> open(c.getSource(), StringArgumentType.getString(c, "form"), ArmProtocol.TIER_DIAMOND))
						.then(ClientCommands.argument("level", IntegerArgumentType.integer(1, 3))
								.executes(c -> open(c.getSource(), StringArgumentType.getString(c, "form"), IntegerArgumentType.getInteger(c, "level"))))))
				// /morphclient lanternlook <name>: the lantern-only look options to compare (2026-11-16), this client only
				.then(ClientCommands.literal("lanternlook")
						.then(ClientCommands.argument("look", StringArgumentType.word())
								.suggests((c, builder) -> SharedSuggestionProvider.suggest(dev.morphreborn.client.arm.geom.LureGeometry.LANTERN_ONLY_LOOKS, builder))
								.executes(c -> {
									int i = dev.morphreborn.client.arm.geom.LureGeometry.LANTERN_ONLY_LOOKS.indexOf(StringArgumentType.getString(c, "look").toLowerCase(java.util.Locale.ROOT));
									if (i < 0) {
										c.getSource().sendError(Component.literal("Lantern looks: " + String.join(", ", dev.morphreborn.client.arm.geom.LureGeometry.LANTERN_ONLY_LOOKS)));
										return 0;
									}
									dev.morphreborn.client.arm.geom.LureGeometry.lanternOnlyLook(i);
									c.getSource().sendFeedback(Component.literal("Lantern-only look: " + dev.morphreborn.client.arm.geom.LureGeometry.LANTERN_ONLY_LOOKS.get(i)));
									return 1;
								})))
				// /morphclient armlook [arm|hand|armour]: shows or sets your arm look (the setting on the Arm Forms page; sent to
				// the server with your colour, so everyone with the mod sees it)
				.then(ClientCommands.literal("armlook")
						.executes(c -> {
							c.getSource().sendFeedback(Component.literal("Arm look: " + ArmProtocol.LOOK_NAMES.get(ArmColours.ownLook()) + " (options: "
									+ String.join(", ", ArmProtocol.LOOK_NAMES) + ")"));
							return 1;
						})
						.then(ClientCommands.argument("look", StringArgumentType.word())
								.suggests((c, builder) -> SharedSuggestionProvider.suggest(ArmProtocol.LOOK_NAMES, builder))
								.executes(c -> {
									String name = StringArgumentType.getString(c, "look").toLowerCase(java.util.Locale.ROOT);
									if (!ArmProtocol.LOOK_NAMES.contains(name)) {
										c.getSource().sendError(Component.literal("Looks: " + String.join(", ", ArmProtocol.LOOK_NAMES)));
										return 0;
									}
									dev.morphreborn.client.ClientConfig.INSTANCE.armLook = name;
									dev.morphreborn.client.ClientConfig.save();
									c.getSource().sendFeedback(Component.literal("Arm look: " + name));
									return 1;
								})))
				// /morphclient pulsespeed [x]: shows or sets how fast the pulses on your arm and lantern run, a multiple of the
				// usual from 0.25 to 3 in steps of 0.05 (the setting on the Arm Forms page; sent to the server with your colour, so everyone with
				// the mod sees your pulses at this speed; 2026-11-16)
				.then(ClientCommands.literal("pulsespeed")
						.executes(c -> {
							c.getSource().sendFeedback(Component.literal("Pulse speed: " + pulseSpeedText(ArmColours.ownPulseSpeed()) + " (from "
									+ pulseSpeedText(ArmProtocol.PULSE_SPEED_MIN) + " to " + pulseSpeedText(ArmProtocol.PULSE_SPEED_MAX) + ", 1.00x is the usual)"));
							return 1;
						})
						.then(ClientCommands.argument("speed", FloatArgumentType.floatArg(ArmProtocol.PULSE_SPEED_MIN / 100F, ArmProtocol.PULSE_SPEED_MAX / 100F))
								.executes(c -> {
									int percent = ArmProtocol.pulseSpeedPercent(FloatArgumentType.getFloat(c, "speed"));
									dev.morphreborn.client.ClientConfig.INSTANCE.armPulseSpeed = ArmProtocol.pulseSpeedFactor(percent);
									dev.morphreborn.client.ClientConfig.save();
									c.getSource().sendFeedback(Component.literal("Pulse speed: " + pulseSpeedText(percent)));
									return 1;
								})));
	}

	/** A pulse speed in percent as the multiple shown to the player ("1.50x"). */
	private static String pulseSpeedText(int percent) {
		return String.format(java.util.Locale.ROOT, "%.2fx", percent / 100.0);
	}

	private static int open(FabricClientCommandSource source, String form, int level) {
		String id = form.toLowerCase(java.util.Locale.ROOT);
		if (!ArmProtocol.FORMS.contains(id)) {
			source.sendError(Component.translatable("morphreborn.arm.preview.unknown_form", form));
			source.sendFeedback(Component.translatable("morphreborn.arm.preview.usage"));
			return 0;
		}
		pending = new ArmPreviewScreen(id, level);
		return 1;
	}
}
