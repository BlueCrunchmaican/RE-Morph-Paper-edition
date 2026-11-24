package dev.morphreborn.client.mixin.arm;

import dev.morphreborn.client.arm.model.ArmSnapshot;
import dev.morphreborn.client.arm.model.ShieldPose;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An arm form replaces the arm (2026-11-15): once its tendrils cover the arm completely, the player's own arm
 * (and its sleeve) isn't drawn, and the tendril arm the sheath layer draws is all there is. Only the arm this hides is
 * shown again, so another mod hiding an arm (First-person Model) is left alone. Every place a player model is drawn goes
 * through here: the world, First-person Model's body, the paper doll and the inventory preview.
 */
@Mixin(PlayerModel.class)
abstract class PlayerModelArmMixin {
	@Unique
	private boolean morphreborn$hidRight, morphreborn$hidLeft;

	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("HEAD"))
	private void morphreborn$showArms(AvatarRenderState state, CallbackInfo ci) {
		PlayerModel self = (PlayerModel) (Object) this;
		if (morphreborn$hidRight) self.rightArm.visible = true;
		if (morphreborn$hidLeft) self.leftArm.visible = true;
		morphreborn$hidRight = morphreborn$hidLeft = false;
	}

	/**
	 * The shield accessory raises the off arm across the body (a blend of vanilla's blocking pose by how far up the shield is). Every place a player model is drawn
	 * goes through here, so the pose follows in the world, First-person Model's body and the paper doll alike.
	 */
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void morphreborn$raiseShield(AvatarRenderState state, CallbackInfo ci) {
		ShieldPose.apply((PlayerModel) (Object) this, state);
	}

	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void morphreborn$hideFormedArm(AvatarRenderState state, CallbackInfo ci) {
		ArmSnapshot snapshot = state.getData(ArmSnapshot.ARM);
		if (snapshot == null || !snapshot.replacesArm()) return;
		PlayerModel self = (PlayerModel) (Object) this;
		if (snapshot.mainArm() == HumanoidArm.LEFT) {
			if (self.leftArm.visible) {
				self.leftArm.visible = false;
				morphreborn$hidLeft = true;
			}
		} else if (self.rightArm.visible) {
			self.rightArm.visible = false;
			morphreborn$hidRight = true;
		}
	}
}
