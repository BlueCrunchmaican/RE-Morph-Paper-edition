package dev.morphreborn.client.arm.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.geom.AccessoryRenderer;
import dev.morphreborn.client.arm.geom.ArmDraw;
import dev.morphreborn.client.arm.geom.ArmGeometry;
import dev.morphreborn.client.arm.geom.LureGeometry;
import dev.morphreborn.client.arm.geom.LureRenderer;
import dev.morphreborn.client.arm.geom.SheathGeometry;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.ArmTex;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import dev.morphreborn.client.compat.FirstPersonCompat;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The sheath of static over the arm that holds an arm form, in third person (section 2.3 "TP sheath"): over other
 * players, your own in F5, First-person Model's body when you look down at it, the paper doll and the inventory
 * preview. Registered on the player renderers (LivingEntityRenderLayerRegistrationCallback); it runs after the model is
 * posed, so it follows the arm exactly. It draws on top of the vanilla arm, which is never hidden.
 * <p>
 * Arm space A (section 2.0): the model's root, then the arm bone, in model pixels, with no slim shift, so the box the
 * geometry knows (wide x −3..1, slim x −2..1, y −2..10, z −2..2 on the right arm) is where the arm really is. The
 * sheath's geometry is {@code SheathGeometry.emit(draw, layer, mesh, slim, leftArm)}, found by name, so this layer
 * builds before that class lands and, without it, draws a plain shell (the far LOD's box). The geometry's other entry
 * point, {@code emit(draw, layer, mesh, static, bracelet)} (the arm and slimness read from the draw), is used when the
 * pinned one isn't there.
 * <p>
 * The lantern's lure goes over the other arm the same way (phase 2's LureGeometry), and its point is recorded for the
 * lantern's beam. When it is the camera's own player in the world pass, the hand pass leaves out its own lure that
 * frame (D41), so First-person Model's body view never shows two.
 * <p>
 * Skipped when the player is invisible or a spectator, or when First-person Model hides that arm.
 */
public final class ArmSheathLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	/** The mod's logger by name (MorphClient.LOGGER is the same one), so this class loads without the client. */
	private static final Logger LOG = LoggerFactory.getLogger("RE:Morph");
	private static final String SHEATH_CLASS = "dev.morphreborn.client.arm.geom.SheathGeometry";
	/** SheathGeometry.emit(draw, layer, mesh, slim, leftArm), the pinned entry point, or null when it isn't in the build. */
	private static final @Nullable MethodHandle SHEATH = find(MethodType.methodType(void.class, ArmDraw.class, ArmLayer.class, SkinMesh.class, boolean.class,
			boolean.class));
	/** SheathGeometry.emit(draw, layer, mesh, static, bracelet), its other entry point, used only without the pinned one. */
	private static final @Nullable MethodHandle SHEATH_WITH_STATIC = SHEATH != null ? null
			: find(MethodType.methodType(void.class, ArmDraw.class, ArmLayer.class, SkinMesh.class, Static.class, boolean.class));
	/** The static the second entry point re-seeds itself (render thread, one lambda at a time). */
	private static final Static SHEATH_STATIC = new Static();
	private static boolean failed;

	public ArmSheathLayer(RenderLayerParent<AvatarRenderState, PlayerModel> renderer) {
		super(renderer);
	}

	private static @Nullable MethodHandle find(MethodType type) {
		try {
			Class<?> sheath = Class.forName(SHEATH_CLASS, true, ArmSheathLayer.class.getClassLoader());
			return MethodHandles.publicLookup().findStatic(sheath, "emit", type);
		} catch (ClassNotFoundException e) {
			LOG.debug("Arm sheath geometry isn't in this build; the plain shell stands in");
		} catch (NoSuchMethodException e) {
			LOG.debug("Arm sheath geometry has no emit{}", type);
		} catch (ReflectiveOperationException | LinkageError e) {
			LOG.error("Arm sheath geometry could not be loaded; the plain shell stands in", e);
		}
		return null;
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, AvatarRenderState state, float yRot, float xRot) {
		ArmSnapshot snapshot = state.getData(ArmSnapshot.ARM);
		if (snapshot == null || state.isInvisible || state.isSpectator) return;
		ArmDrawFactory.Pass pass = ArmRender.inWorldPass() ? ArmDrawFactory.Pass.WORLD : ArmDrawFactory.Pass.PIP;
		int overlay = LivingEntityRenderer.getOverlayCoords(state, 0F);
		PlayerModel model = getParentModel();
		HumanoidArm main = snapshot.mainArm();
		if (!snapshot.draw().form().isEmpty() || snapshot.draw().recallLeft() > 0F) {
			ModelPart arm = model.getArm(main);
			// hidden by us, or by First-person Model's body view (which shows the vanilla first-person hand there): either way
			// the tendril arm is the arm now, and first person draws just the tool, so the body keeps it
			if ((arm.visible || snapshot.replacesArm()) && (snapshot.replacesArm() || !FirstPersonCompat.hidesArm(state, main))) {
				Matrix4f bodyToArm = bodyToArm(model, arm);
				poseStack.pushPose();
				toArm(model, arm, poseStack);
				// First-person Model's body holds this arm up to half a block from the camera (a bow drawn, a whip swung), and the sheath
				// is not the tool, so ArmFormRenderer's scaling never reached it: drawn smaller about the hand by the same rule, so the arm
				// and its tool stay one piece (fix round, 2026-11-16). Whole beyond 0.85 block: looking down at the hanging arm is as it was.
				float near = ownFirstPersonBody(snapshot, pass) ? armScale(poseStack.last()) : 1F;
				if (near < 1F) {
					poseStack.translate(0F, NearBodyScale.SHEATH_HAND_Y, 0F);
					poseStack.scale(near, near, near);
					poseStack.translate(0F, -NearBodyScale.SHEATH_HAND_Y, 0F);
				}
				submitSheath(snapshot.draw(), pass, poseStack, collector, lightCoords, overlay, snapshot.slim(), main == HumanoidArm.LEFT, -1, bodyToArm);
				poseStack.popPose();
			}
		}
		// the lantern hangs over the hand that holds the form (2026-11-15), out of that arm. Your own, seen in first person, is on the body only
		// where First-person Model draws it (2026-11-24, as the tools and the other accessories are: drawn smaller when the hand is near the lens, and the
		// hand pass leaves its own lure out that frame); in vanilla first person there is no body and the hand pass hangs it over the tool where you see
		// it. On the side where that mod shows the vanilla hands instead, hidesArm is true and the hand pass still draws it.
		boolean ownFirstPerson = snapshot.camera() && pass == ArmDrawFactory.Pass.WORLD && net.minecraft.client.Minecraft.getInstance().options.getCameraType().isFirstPerson();
		boolean fpBody = ownFirstPerson && ownFirstPersonBody(snapshot, pass);
		boolean onBody = AccessoryRouting.onBody(ownFirstPerson, fpBody);
		// the spyglass's zoom draws no hands in vanilla first person and the lantern goes with them (the hand pass's rule); on the body the same
		if (snapshot.draw().lanternLit() && onBody && !(fpBody && ownScoping())) {
			ModelPart arm = model.getArm(main);
			if (AccessoryRouting.armCarries(arm.visible, snapshot.replacesArm(), true, FirstPersonCompat.hidesArm(state, main))) {
				poseStack.pushPose();
				toArm(model, arm, poseStack);
				// the free part (stalk, chain, lure) drawn smaller by how near the lens the stalk's root is; the holder stays on the arm. Where a tool's own arm
				// carries the stalk, that arm's sheath was drawn smaller about the hand by the rule above, and the root goes with it (the same arm, so the same pose)
				float near = fpBody ? accessoryScale(poseStack.last(), ArmProtocol.LANTERN, snapshot.slim(), main == HumanoidArm.LEFT) : 1F;
				float sheathNear = fpBody ? armScale(poseStack.last()) : 1F;
				boolean drawn = submitLure(snapshot.draw(), pass, poseStack, collector, lightCoords, overlay, snapshot.slim(), main == HumanoidArm.LEFT, near, fpBody, sheathNear);
				if (pass == ArmDrawFactory.Pass.WORLD) {
					// the rest point is where the ball hangs unscaled: seen through the same matrix the draw puts the free part through, so the flare and the
					// beam start from the lure as it is shown
					if (near < 1F || sheathNear < 1F) poseStack.mulPose(LureGeometry.freeTransform(snapshot.draw(), near, sheathNear, FREE));
					ArmTipsRecorder.record(snapshot.entityId(), false, ArmTips.LURE, poseStack.last().pose(), lurePoint(snapshot.slim(), main == HumanoidArm.LEFT));
					if (drawn && snapshot.camera()) ArmRender.lureDrawnInWorld();
				}
				poseStack.popPose();
			}
		}
		// the other accessories (shield, spyglass, flint and steel: 2026-11-16), each on its own arm and drawn only
		// when its renderer is in the build; the lantern's lure above is its own path
		// (2026-11-24: with First-person Model the three go on its body's real arm, as the tools do, and the hand pass leaves them out; on the
		// side where that mod shows the vanilla hands instead, hidesArm is true and the hand pass still draws them)
		if (snapshot.draw().anyAccessory() && onBody) {
			for (int i = 1; i < ArmProtocol.ACCESSORIES.size(); i++) {
				String id = ArmProtocol.ACCESSORIES.get(i);
				AccessoryRenderer renderer = snapshot.draw().accessoryOn(id) ? ArmGeometry.accessory(id) : null;
				if (renderer == null) continue;
				HumanoidArm side = renderer.offArm() ? main.getOpposite() : main;
				ModelPart arm = model.getArm(side);
				if (AccessoryRouting.armCarries(arm.visible, snapshot.replacesArm(), side == main, FirstPersonCompat.hidesArm(state, side))) {
					poseStack.pushPose();
					toArm(model, arm, poseStack);
					// the accessory's free part drawn smaller by how near the lens its pivot is, so a shield or a stalk does not fill the view
					float near = fpBody ? accessoryScale(poseStack.last(), id, snapshot.slim(), side == HumanoidArm.LEFT) : 1F;
					submitAccessory(renderer, snapshot.draw(), pass, poseStack, collector, lightCoords, overlay, snapshot.slim(), side == HumanoidArm.LEFT, near, fpBody);
					if (fpBody) ArmRender.accessoryDrawnOnBody(i);
					if (pass == ArmDrawFactory.Pass.WORLD) {
						Vector3f at = renderer.restPoint(snapshot.slim(), side == HumanoidArm.LEFT, REST);
						if (at != null) ArmTipsRecorder.record(snapshot.entityId(), false, ArmTips.accessory(id), poseStack.last().pose(), at);
					}
					poseStack.popPose();
				}
			}
		}
	}

	/** Whether this client's own player counts as scoping (the vanilla spyglass, or the accessory's zoom through {@code PlayerScopeMixin}). */
	private static boolean ownScoping() {
		net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
		return player != null && player.isScoping();
	}

	/** Whether this sheath is the camera player's own on First-person Model's body in the world pass (the mod's flag and the owner both checked). */
	private static boolean ownFirstPersonBody(ArmSnapshot snapshot, ArmDrawFactory.Pass pass) {
		return snapshot.camera() && pass == ArmDrawFactory.Pass.WORLD && FirstPersonCompat.drawingOwnBody(snapshot.entityId());
	}

	/**
	 * {@link NearBodyScale} for the sheath at {@code armPose} (arm space A of a world-pass submit, so camera-relative): the arm's hand end's
	 * distance from the camera, and the sheath's reach from it in blocks (px times the pose's own scale, the 1/16).
	 */
	private static float armScale(PoseStack.Pose armPose) {
		Matrix4f m = armPose.pose();
		Vector3f hand = m.transformPosition(0F, NearBodyScale.SHEATH_HAND_Y, 0F, new Vector3f());
		double px = Math.sqrt((double) m.m00() * m.m00() + (double) m.m01() * m.m01() + (double) m.m02() * m.m02());
		return NearBodyScale.of(hand.length(), NearBodyScale.SHEATH_REACH_PX * px);
	}

	/**
	 * {@link NearBodyScale} for accessory {@code id}'s free part at {@code armPose} (arm space A of the arm it sits on): the distance of its
	 * pivot from the camera and its own reach from there (px times the pose's own scale, the 1/16). The grips are not scaled: they lie on the arm.
	 */
	static float accessoryScale(PoseStack.Pose armPose, String id, boolean slim, boolean leftArm) {
		Matrix4f m = armPose.pose();
		float x = (slim ? 0.5F : 1F) * (leftArm ? 1F : -1F);
		Vector3f pivot = m.transformPosition(x, NearBodyScale.accessoryPivotY(id), 0F, new Vector3f());
		double px = Math.sqrt((double) m.m00() * m.m00() + (double) m.m01() * m.m01() + (double) m.m02() * m.m02());
		return NearBodyScale.of(pivot.length(), NearBodyScale.accessoryReachPx(id) * px, NearBodyScale.accessoryCap(id));
	}

	/**
	 * The body's model space as seen from the arm's (both in px, as posed this frame): for the tendril arm's roots into
	 * the body at the shoulder, so they stay on the body however the arm swings.
	 */
	static Matrix4f bodyToArm(PlayerModel model, ModelPart arm) {
		PoseStack a = new PoseStack(), b = new PoseStack();
		toArm(model, arm, a);
		model.root().translateAndRotate(b);
		model.body.translateAndRotate(b);
		b.scale(1F / 16F, 1F / 16F, 1F / 16F);
		return new Matrix4f(a.last().pose()).invert().mul(b.last().pose());
	}

	/** Into arm space A: the model's root, the arm bone, then model pixels. */
	static void toArm(PlayerModel model, ModelPart arm, PoseStack poseStack) {
		model.root().translateAndRotate(poseStack);
		arm.translateAndRotate(poseStack);
		poseStack.scale(1F / 16F, 1F / 16F, 1F / 16F);
	}

	/**
	 * Submits the sheath of {@code draw} at the pose on top of {@code poseStack} (arm space A, px) in {@code pass}
	 * ({@code forcedLod} ≥ 0 sets the LOD): one lambda per layer, as the tool's are.
	 */
	public static void submitSheath(ArmDraw draw, ArmDrawFactory.Pass pass, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay,
			boolean slim, boolean leftArm, int forcedLod) {
		submitSheath(draw, pass, poseStack, collector, light, overlay, slim, leftArm, forcedLod, null);
	}

	/** As above, with the body's space as seen from the arm's ({@link #bodyToArm}) for the roots, or null for none. */
	public static void submitSheath(ArmDraw draw, ArmDrawFactory.Pass pass, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay,
			boolean slim, boolean leftArm, int forcedLod, @Nullable Matrix4f bodyToArm) {
		ArmDraw d = ArmDrawFactory.finishSheath(draw, pass, poseStack.last(), light, forcedLod);
		for (ArmLayer layer : new ArmLayer[] {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW}) {
			if (layer == ArmLayer.GLOW && !d.glow()) continue;
			int layerLight = layer == ArmLayer.GLOW ? ArmTex.FULL_BRIGHT : light;
			collector.submitCustomGeometry(poseStack, ArmTex.type(layer, d.dissolve()), (p, vc) -> {
				SheathGeometry.bodyToArm(bodyToArm);
				if (!d.form().isEmpty()) Loft.pulseFrom(d, ArmGeometry.get(d.form()).feedLength(d.tier()));
				try {
					emitSheath(p, vc, layer, layerLight, overlay, d, slim, leftArm);
				} finally {
					SheathGeometry.bodyToArm(null);
					Loft.pulseFrom(null, 0F);
				}
			});
		}
	}

	private static void emitSheath(PoseStack.Pose pose, VertexConsumer vc, ArmLayer layer, int light, int overlay, ArmDraw d, boolean slim, boolean leftArm) {
		SkinMesh m = SkinMesh.begin(pose, vc, light, overlay);
		MethodHandle sheath = SHEATH, withStatic = SHEATH_WITH_STATIC;
		try {
			if (sheath != null) {
				sheath.invokeExact(d, layer, m, slim, leftArm);
			} else if (withStatic != null) {
				// no arm out and a recall running: the bracelet instead of the sheath
				boolean bracelet = d.form().isEmpty() && d.recallLeft() > 0F;
				withStatic.invokeExact(d, layer, m, SHEATH_STATIC, bracelet);
			} else {
				plainShell(d, layer, m, slim, leftArm);
			}
		} catch (Throwable e) {
			if (e instanceof Error error && !(e instanceof LinkageError)) throw error;
			if (!failed) {
				failed = true;
				LOG.error("Arm sheath failed to draw; later failures aren't logged", e);
			}
		}
	}

	private static final Static SHELL_STATIC = new Static();

	/**
	 * The stand-in while SheathGeometry isn't in the build: the far LOD's single box, the arm plus a pixel from the
	 * sheath's top down to the hand, its top sliding as the timelines move it (section 2.6).
	 */
	static void plainShell(ArmDraw d, ArmLayer layer, SkinMesh m, boolean slim, boolean leftArm) {
		if (layer != ArmLayer.BLACK || d.form().isEmpty()) return;
		float top = sheathTop(d);
		if (top >= 10.4F) return;
		float xc = leftArm ? (slim ? 0.5F : 1F) : (slim ? -0.5F : -1F);
		float hx = slim ? 1.5F : 2F, hz = 2F, grow = 1F + (d.chestArmour() ? 0.35F : 0F);
		SHELL_STATIC.set(d.time(), d.cadence(), d.crawlDir(), d.seed());
		m.box(null, xc - hx - grow, top, -hz - grow, 2F * (hx + grow), 10.5F - top, 2F * (hz + grow), SHELL_STATIC.u0(9, 0, 4F), SHELL_STATIC.v0(9, 0, 4F), 1F,
				1F, SkinMesh.WHITE);
	}

	/** The sheath's top edge in A px (section 2.6): sliding up the forearm while forming, back down when it goes. */
	static float sheathTop(ArmDraw d) {
		float top = 3.2F, bottom = 10.4F;
		float t = d.stateTicks();
		return switch (d.state()) {
			case ArmProtocol.STATE_FORMING -> bottom - (bottom - top) * ArmMath.sm(t / 4F);
			case ArmProtocol.STATE_QUICKFORM -> ArmMath.lerp(9.6F, top, ArmMath.sm(t / 3F));
			case ArmProtocol.STATE_RETRACTING -> ArmMath.lerp(top, bottom, ArmMath.sm((t - 6F) / 5F));
			case ArmProtocol.STATE_MELTING -> ArmMath.lerp(top, bottom, ArmMath.sm((t - 10F) / 10F));
			case ArmProtocol.STATE_NONE -> bottom;
			default -> top;
		};
	}

	/**
	 * Submits the lantern's lure for {@code draw} on the arm whose space is on top of {@code poseStack} (A, px); false
	 * when its geometry isn't in the build.
	 */
	static boolean submitLure(ArmDraw draw, ArmDrawFactory.Pass pass, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, boolean slim,
			boolean leftArm) {
		return submitLure(draw, pass, poseStack, collector, light, overlay, slim, leftArm, 1F, false, 1F);
	}

	/**
	 * As above, with {@code near} (below 1 on First-person Model's body, half a block from the lens: the free part is drawn that much smaller about the stalk's
	 * root, the holder not) and whether it is seen from behind (that mod's own body), and {@code sheathNear}, the scale the arm form's sheath was drawn at there (the root goes with it
	 * where a tool's own arm carries the stalk).
	 */
	static boolean submitLure(ArmDraw draw, ArmDrawFactory.Pass pass, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, boolean slim,
			boolean leftArm, float near, boolean backView, float sheathNear) {
		LureRenderer lure = ArmGeometry.lure();
		if (lure == null) return false;
		ArmDraw d = ArmDrawFactory.finishSheath(draw, pass, poseStack.last(), light, -1);
		// in the world the chain swings in world space (everyone sees it lag as its holder moves): it needs the camera
		net.minecraft.world.phys.Vec3 cam = pass == ArmDrawFactory.Pass.WORLD ? ArmTipsRecorder.cameraPos() : null;
		for (ArmLayer layer : new ArmLayer[] {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW}) {
			int layerLight = layer == ArmLayer.GLOW ? ArmTex.FULL_BRIGHT : light;
			collector.submitCustomGeometry(poseStack, ArmTex.type(layer, d.dissolve()), (p, vc) -> {
				SkinMesh m = SkinMesh.begin(p, vc, layerLight, overlay);
				if (cam != null) dev.morphreborn.client.arm.geom.LureGeometry.worldFrame(p.pose(), cam.x, cam.y, cam.z);
				dev.morphreborn.client.arm.geom.AccessoryView.set(near, backView, sheathNear);
				// the lantern on its own pulses as an arm does: down what holds it on the arm, and up the stalk
				if (d.form().isEmpty()) Loft.pulseFrom(d, dev.morphreborn.client.arm.geom.LureGeometry.HOLD_FEED);
				try {
					lure.emitThirdPerson(d, layer, m);
				} catch (RuntimeException e) {
					if (!failed) {
						failed = true;
						LOG.error("Arm lure failed to draw; later failures aren't logged", e);
					}
				} finally {
					dev.morphreborn.client.arm.geom.LureGeometry.worldFrame(null, 0, 0, 0);
					dev.morphreborn.client.arm.geom.AccessoryView.set(1F, false);
					Loft.pulseFrom(null, 0F);
				}
			});
		}
		return true;
	}

	/**
	 * Submits accessory {@code renderer}'s third-person look for {@code draw} on the arm whose space A (px) is on top of
	 * {@code poseStack}: one lambda per layer, as the tool's are (GLOW only with glow on).
	 */
	static void submitAccessory(AccessoryRenderer renderer, ArmDraw draw, ArmDrawFactory.Pass pass, PoseStack poseStack, SubmitNodeCollector collector,
			int light, int overlay, boolean slim, boolean leftArm, float near, boolean backView) {
		ArmDraw d = ArmDrawFactory.finishSheath(draw, pass, poseStack.last(), light, -1);
		boolean world = pass == ArmDrawFactory.Pass.WORLD; // taken now: the lambda below runs after the world pass has ended
		for (ArmLayer layer : new ArmLayer[] {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW}) {
			if (layer == ArmLayer.GLOW && !d.glow()) continue;
			int layerLight = layer == ArmLayer.GLOW ? ArmTex.FULL_BRIGHT : light;
			collector.submitCustomGeometry(poseStack, ArmTex.type(layer, d.dissolve()), (p, vc) -> {
				SkinMesh m = SkinMesh.begin(p, vc, layerLight, overlay);
				ArmRender.drawingInWorld(world);
				dev.morphreborn.client.arm.geom.AccessoryView.set(near, backView);
				try {
					renderer.emitThirdPerson(d, layer, m);
				} catch (RuntimeException e) {
					if (!failed) {
						failed = true;
						LOG.error("An accessory failed to draw ({}); later failures aren't logged", renderer.id(), e);
					}
				} finally {
					ArmRender.drawingInWorld(false);
					dev.morphreborn.client.arm.geom.AccessoryView.set(1F, false);
				}
			});
		}
	}

	/** Where the lure hangs at rest in the space A of the arm holding the form (LureGeometry's rest point). */
	static Vector3f lurePoint(boolean slim, boolean leftArm) {
		return dev.morphreborn.client.arm.geom.LureGeometry.restPointThirdPerson(slim, leftArm, REST);
	}

	/**
	 * Scratch for the rest points handed to the tip recorder, which reads one at once and keeps none: the render thread draws
	 * one arm at a time, so this takes no allocation per frame per accessory.
	 */
	private static final Vector3f REST = new Vector3f();
	/** Scratch for the lantern's near-lens matrix (the recorded rest point), render thread only. */
	private static final Matrix4f FREE = new Matrix4f();
}
