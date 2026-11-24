package dev.morphreborn.client.mixin.arm;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmHooks;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * After both first-person hands are submitted (ArmHooks.afterHands): the place for what belongs in the hand pass but
 * not to a held item, such as the lantern's lure over the other arm. At the end, never at the head, so it adds to what
 * vanilla, First-person Model and Iris draw there instead of replacing any of it. TAIL is the method's last return,
 * after the hands; the early one (no hand state, so no hands drawn) is left out on purpose.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsAndItemsRendererMixin {
	/**
	 * Just before vanilla submits the item in a hand (both of the branches that do, the crossbow's and the general one): hands over the pose it has
	 * on the stack, with all its animation in it, and the item's model box (2026-11-24), so the overlay's accessories can keep clear of what is held.
	 * Not required: if a game version moves the call, the accessories only lose the held item to make room for and nothing else.
	 */
	@Inject(method = "submitArmWithItem(Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"),
			require = 0)
	private void morph$heldPose(PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState state, float partialTicks, float xRot, InteractionHand hand,
			float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, CallbackInfo ci) {
		boolean main = hand == InteractionHand.MAIN_HAND;
		ArmHooks.heldPose.heldPose(main, poseStack.last().pose(), itemStack, main ? state.mainHandRenderState : state.offHandRenderState);
	}

	@Inject(method = "submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;)V",
			at = @At("TAIL"))
	private void morph$armAfterHands(float partialTicks, PoseStack poseStack, SubmitNodeCollector collector, PlayerRenderState playerState,
			FirstPersonHandsAndItemsRenderState state, CallbackInfo ci) {
		ArmHooks.afterHands.afterHands(partialTicks, poseStack, collector, playerState, state);
	}
}
