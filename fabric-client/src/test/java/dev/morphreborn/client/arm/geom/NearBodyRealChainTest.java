package dev.morphreborn.client.arm.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.model.ArmTransforms;
import dev.morphreborn.client.arm.model.NearBodyScale;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.SkinMesh;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * The bow in the hand of First-person Model's body, from the real chain (the rig, 2026-11-16: a black wedge down the LEFT of the view at full
 * draw; the first fix rested on a hand-replayed chain). Here the arm is posed by vanilla's own {@link PlayerModel#setupAnim} (a baked model,
 * the BOW_AND_ARROW pose) and carried to the hand by {@code translateToHand} and {@code ItemInHandLayer}'s own turn, so nothing about the
 * body is assumed but where First-person Model puts it (0.25 behind the eye, the feet 1.62 below it), and the form is the real one at full
 * pull, with its real vertices rather than its rest extents.
 */
class NearBodyRealChainTest {
	/** Camera-space points (right, up, ahead; blocks) of every vertex a draw makes, and a reference point on it. */
	static final class View {
		final java.util.List<Vector3f> pts = new java.util.ArrayList<>();
		final Vector3f ref = new Vector3f();
		float scale;

		/** The span (degrees) of the angle from the view axis, sideways, over every vertex in front of the lens: left is negative. */
		double[] horizontalSpan() {
			double lo = 180, hi = -180;
			for (Vector3f p : pts) {
				if (p.z <= 0.02F) continue;
				double a = Math.toDegrees(Math.atan2(p.x, p.z));
				lo = Math.min(lo, a);
				hi = Math.max(hi, a);
			}
			return new double[] {lo, hi};
		}

		/** The farthest any vertex is from the reference point, blocks. */
		float reach() {
			float far = 0F;
			for (Vector3f p : pts) far = Math.max(far, new Vector3f(p).sub(ref).length());
			return far;
		}
	}

	/** A posed player drawing a bow with the right (or left) main arm, and the entity's chain up to the model's root. */
	static final class Body {
		final PlayerModel model = new PlayerModel(LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64).bakeRoot(), false);
		final AvatarRenderState state = new AvatarRenderState();
		final PoseStack ps = new PoseStack();
		final boolean left;

		/**
		 * {@code bodyYawDeg}: how far the body is turned from where the camera looks (Minecraft yaw: positive is to the camera's right;
		 * vanilla lets the body trail the head by up to 50 degrees), the head turned back by the same so the arms still aim along the view.
		 */
		Body(boolean left, float bodyYawDeg) {
			this.left = left;
			state.yRot = -bodyYawDeg;
			state.mainArm = left ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
			state.isUsingItem = true;
			state.ticksUsingItem = 20F;
			if (left) state.leftArmPose = HumanoidModel.ArmPose.BOW_AND_ARROW;
			else state.rightArmPose = HumanoidModel.ArmPose.BOW_AND_ARROW;
			model.setupAnim(state);
			// LivingEntityRenderer: the body 0.25 behind the eye and the feet 1.62 below it, facing +Z (yaw 0), then its own flip
			ps.translate(0F, -1.62F, -0.25F);
			ps.mulPose(new Matrix4f().rotateY((float) Math.PI - (float) Math.toRadians(bodyYawDeg)));
			ps.scale(-1F, -1F, 1F);
			ps.translate(0F, -1.501F, 0F);
		}

		HumanoidArm side() {
			return left ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
		}

		/** ItemInHandLayer.submitArmWithItem, then the item's display block and the form's frame on it: grip space G of {@code form}. */
		PoseStack hand(String form, boolean bowFlag) {
			PoseStack h = new PoseStack();
			h.mulPose(ps.last().pose());
			model.translateToHand(state, side(), h);
			h.mulPose(new Matrix4f().rotateX((float) Math.toRadians(-90)));
			h.mulPose(new Matrix4f().rotateY((float) Math.toRadians(180)));
			h.translate((left ? -1 : 1) / 16F, 2F / 16F, -10F / 16F);
			ItemDisplayContext ctx = left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
			ArmTransforms.forForm(ctx, form, bowFlag).apply(left, h.last());
			h.mulPose(ArmTransforms.handFrame(ctx, form, bowFlag, false, new Matrix4f()));
			return h;
		}

		/** ArmSheathLayer.toArm: the model's root, the arm bone, then model pixels (arm space A). */
		PoseStack armSpace() {
			PoseStack a = new PoseStack();
			a.mulPose(ps.last().pose());
			model.root().translateAndRotate(a);
			model.getArm(side()).translateAndRotate(a);
			a.scale(1F / 16F, 1F / 16F, 1F / 16F);
			return a;
		}

		/** ArmSheathLayer.bodyToArm. */
		Matrix4f bodyToArm() {
			PoseStack a = new PoseStack(), b = new PoseStack();
			model.root().translateAndRotate(a);
			ModelPart arm = model.getArm(side());
			arm.translateAndRotate(a);
			a.scale(1F / 16F, 1F / 16F, 1F / 16F);
			model.root().translateAndRotate(b);
			model.body.translateAndRotate(b);
			b.scale(1F / 16F, 1F / 16F, 1F / 16F);
			return new Matrix4f(a.last().pose()).invert().mul(b.last().pose());
		}
	}

	private static Vector3f toCamera(float[] p) {
		return new Vector3f(-p[0], p[1], p[2]); // the player's right is -X facing +Z
	}

	private static ArmDraw bowDraw(float pull) {
		ArmBudgetTest.Draw b = ArmBudgetTest.draw(ArmProtocol.BOW).tier(2).ctx(ArmDraw.CTX_TP).time(100F);
		b.bowPull = pull;
		return b.build();
	}

	/** The bow's tool, in the body's hand at {@code pull}: its real vertices, the reference point the grip. */
	static View tool(float pull, float yaw) {
		FormGeometry g = ArmGeometry.get(ArmProtocol.BOW);
		Body body = new Body(false, yaw);
		PoseStack h = body.hand(ArmProtocol.BOW, g.bowTransforms());
		View v = new View();
		Vector3f grip = h.last().pose().transformPosition(new Vector3f());
		v.ref.set(toCamera(new float[] {grip.x, grip.y, grip.z}));
		Matrix4f m = h.last().pose();
		v.scale = (float) Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02());
		ArmDraw d = bowDraw(pull);
		for (ArmLayer layer : new ArmLayer[] {ArmLayer.BLACK, ArmLayer.RED}) {
			ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
			r.keep = true;
			g.emit(d, layer, SkinMesh.begin(h.last(), r, 0xF000F0, 0));
			for (float[] p : r.kept) v.pts.add(toCamera(p));
		}
		return v;
	}

	/** The sheath over the main arm in the same pose, as ArmSheathLayer draws it; the reference point is the arm's hand end. */
	static View sheath(float pull, float yaw) {
		Body body = new Body(false, yaw);
		PoseStack a = body.armSpace();
		View v = new View();
		Vector3f hand = a.last().pose().transformPosition(0F, 10F, 0F, new Vector3f());
		v.ref.set(toCamera(new float[] {hand.x, hand.y, hand.z}));
		ArmDraw d = bowDraw(pull);
		for (ArmLayer layer : new ArmLayer[] {ArmLayer.BLACK, ArmLayer.RED}) {
			ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
			r.keep = true;
			SheathGeometry.bodyToArm(body.bodyToArm());
			try {
				SheathGeometry.emit(d, layer, SkinMesh.begin(a.last(), r, 0xF000F0, 0), false, false);
			} finally {
				SheathGeometry.bodyToArm(null);
			}
			for (float[] p : r.kept) v.pts.add(toCamera(p));
		}
		return v;
	}

	private static final float[] YAWS = {-50F, -25F, 0F, 25F, 50F};

	/** The largest angle (degrees) between the direction to {@code centre} and the direction to any of {@code pts}, scaled about {@code about} by {@code k}. */
	private static double angularRadius(View v, Vector3f about, float k) {
		Vector3f axis = new Vector3f(about).normalize();
		double far = 0;
		for (Vector3f p : v.pts) {
			Vector3f q = new Vector3f(p).sub(about).mul(k).add(about);
			if (q.length() < 1e-4F) continue;
			far = Math.max(far, Math.toDegrees(Math.acos(Math.clamp(new Vector3f(q).normalize().dot(axis), -1F, 1F))));
		}
		return far;
	}

	@Test
	void theBodysHandIsAboutHalfABlockFromTheEyeAtTheRight() {
		for (float yaw : YAWS) {
			View t = tool(1F, yaw);
			assertTrue(t.ref.length() > 0.3F && t.ref.length() < 0.75F, "yaw " + yaw + ": the draw hand is about half a block from the eye: " + t.ref.length());
			assertTrue(t.ref.x > 0.05F && t.ref.z > 0F && t.ref.y < 0F, "yaw " + yaw + ": ahead, below the eye line and to the right: " + t.ref);
		}
	}

	@Test
	void aRightHandBowAndSheathStayOnTheRightOfTheView() {
		// The rig saw the wedge down the LEFT of the view (item5_fpm_bow_fulldraw_left_slab.png). This is what vanilla's own bow pose gives for
		// the right arm at every body turn vanilla allows (it lets the body trail the head by 50 degrees): both the tool and the sheath sit
		// on the right, none of either more than a few degrees left of the view axis, so the wedge is not explained by them, and the fix
		// round does not claim it is (it shrinks both by how near they are; the rig has to say what is on the left).
		for (float yaw : YAWS) {
			double[] tool = tool(1F, yaw).horizontalSpan(), sheath = sheath(1F, yaw).horizontalSpan();
			assertTrue(tool[0] > -10.0, "yaw " + yaw + ": the tool's left edge is " + tool[0] + " degrees");
			assertTrue(sheath[0] > -10.0, "yaw " + yaw + ": the sheath's left edge is " + sheath[0] + " degrees");
		}
	}

	@Test
	void theDrawnBowReachesFarPastItsRestBox() {
		FormGeometry g = ArmGeometry.get(ArmProtocol.BOW);
		float[] rest = {0F};
		g.extents(p -> rest[0] = Math.max(rest[0], p.length()));
		View t = tool(1F, 0F);
		float drawnPx = t.reach() / t.scale;
		assertTrue(drawnPx > 1.3F * rest[0], "the drawn bow reaches " + drawnPx + " px, its rest box " + rest[0]);
		assertTrue(drawnPx <= NearBodyScale.reachPx(ArmProtocol.BOW, rest[0]), "and the table covers it: " + drawnPx);
	}

	@Test
	void theBowNearTheEyeIsShrunkToTheCapAtEveryBodyTurn() {
		FormGeometry g = ArmGeometry.get(ArmProtocol.BOW);
		float[] rest = {0F};
		g.extents(p -> rest[0] = Math.max(rest[0], p.length()));
		for (float yaw : YAWS) {
			View t = tool(1F, yaw);
			float dist = t.ref.length();
			float k = NearBodyScale.of(dist, NearBodyScale.reachPx(ArmProtocol.BOW, rest[0]) * t.scale);
			assertTrue(k < (yaw < -40F ? 0.7F : yaw < -20F ? 0.5F : 0.35F) && k >= NearBodyScale.MIN, "yaw " + yaw + ": a bow that near is drawn at a fraction of its size: " + k);
			double before = angularRadius(t, t.ref, 1F), after = angularRadius(t, t.ref, k);
			assertTrue(dist > NearBodyScale.NEAR || after <= Math.toDegrees(Math.asin(NearBodyScale.CAP)) + 0.5, "yaw " + yaw + ": the bow subtends " + after + " degrees about its grip, over the cap");
			assertTrue(after < (dist > NearBodyScale.NEAR ? 0.9 : 0.45) * before, "yaw " + yaw + ": from " + before + " to " + after + " degrees");
		}
	}

	@Test
	void theSheathOnTheNearArmIsShrunkAboutItsHandToo() {
		for (float yaw : YAWS) {
			View s = sheath(1F, yaw);
			float dist = s.ref.length();
			float k = NearBodyScale.of(dist, NearBodyScale.SHEATH_REACH_PX / 16F);
			assertTrue(k < (yaw < -40F ? 0.6F : 0.5F) && k >= NearBodyScale.MIN, "yaw " + yaw + ": the arm that near is drawn at a half or less: " + k);
			double after = angularRadius(s, s.ref, k);
			assertTrue(dist > NearBodyScale.NEAR || after <= Math.toDegrees(Math.asin(NearBodyScale.CAP)) + 0.5, "yaw " + yaw + ": the sheath subtends " + after + " degrees about its hand, over the cap");
		}
	}

	@Test
	void theArmHungAtTheSideLookingDownIsLeftAlone() {
		// pitch 50 puts the hanging hand 0.84 away in the rig's numbers; the rule fades out by 0.85
		assertEquals(1F, NearBodyScale.of(NearBodyScale.NEAR + NearBodyScale.BAND + 0.01, NearBodyScale.reachPx(ArmProtocol.WHIP, 0F) / 16F), 0F);
	}
}
