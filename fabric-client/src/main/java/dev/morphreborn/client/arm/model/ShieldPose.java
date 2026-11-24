package dev.morphreborn.client.arm.model;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.geom.ArmDraw;
import dev.morphreborn.client.arm.geom.ShieldMotion;
import dev.morphreborn.client.arm.render.ArmMath;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;

/**
 * What the shield accessory does to the player model, which the geometry (a drawing in the off arm's space) can't: it raises the off
 * arm across the body the way vanilla's blocking pose does, by the shield's raise ({@link ShieldMotion#raise}). (It used to remember the
 * arm's and the body's pose too, for the back look's roots; the owner dropped that look on 2026-11-16.) Called from the tail of {@code PlayerModel.setupAnim} (PlayerModelArmMixin), for every place a
 * player model is drawn.
 * <p>
 * The pose is vanilla's {@code poseBlockingArm} blended in by the raise: {@code xRot * 0.5 - 0.9424779 + clamp(head pitch)} and a
 * 30 degree turn across the body plus the head's yaw, so the arm bobs and follows the head as it does with a real shield. It is left
 * alone when the model is already posing that arm to block (a real shield, in a server that gave one), and when the main hand is in a
 * two-handed pose (a bow or crossbow drawn, a trident thrown), which also poses the off arm.
 * <p>
 * It does not hide a vanilla shield in the off hand (it used to, 2026-11-16): the server's rewrite of the off hand to a shield goes only to
 * viewers whose game does not draw the new forms ({@code ArmEquipmentView.showsShield}), so a modded viewer is never sent one, and the only
 * shield this client could meet there is a real one the owner holds (the server leaves a real raised shield alone), which stays drawn.
 */
public final class ShieldPose {
	private static final float BLOCK_X = -0.9424779F, BLOCK_Y = (float) Math.toRadians(30.0);

	private ShieldPose() {}

	/** At the tail of {@code PlayerModel.setupAnim}. */
	public static void apply(PlayerModel model, AvatarRenderState state) {
		ArmSnapshot snapshot = state.getData(ArmSnapshot.ARM);
		if (snapshot == null) return;
		ArmDraw d = snapshot.draw();
		if (!d.accessoryOn(ArmProtocol.SHIELD)) return;
		HumanoidArm main = snapshot.mainArm();
		HumanoidArm off = main.getOpposite();
		ModelPart arm = model.getArm(off);
		float built = ShieldMotion.built(d.accessoryAge(ArmProtocol.SHIELD));
		// the shield is raised (or coming down) only once it has grown; the pose of the main hand's item can take the off arm too
		float r = ShieldMotion.raise(snapshot.entityId(), d.time(), d.accessoryArg(ArmProtocol.SHIELD)) * ArmMath.clamp01(built * 2.5F);
		HumanoidModel.ArmPose mainPose = state.getArmPose(main);
		HumanoidModel.ArmPose offPose = state.getArmPose(off);
		if (r > 0.001F && offPose != HumanoidModel.ArmPose.BLOCK && !mainPose.affectsOffhandPose()) {
			float toX = arm.xRot * 0.5F + BLOCK_X + Mth.clamp(model.head.xRot, (float) (-Math.PI * 4.0 / 9.0), 0.43633232F);
			float toY = (off == HumanoidArm.RIGHT ? -BLOCK_Y : BLOCK_Y) + Mth.clamp(model.head.yRot, (float) (-Math.PI / 6), (float) (Math.PI / 6));
			arm.xRot = Mth.lerp(r, arm.xRot, toX);
			arm.yRot = Mth.lerp(r, arm.yRot, toY);
		}
	}
}
