package dev.morphreborn.client.mixin.arm;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.morphreborn.client.arm.ArmHooks;
import dev.morphreborn.client.arm.gui.ShieldHold;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Tendril Shield on right-click (decided 2026-11-17). {@code Minecraft.startUseItem} is the game's own answer to a use click: for each hand,
 * main first, it tries a creature in front, then a block, then the item's own use, and stops at the first success (or at a block's refusal). This
 * watches it for {@link ShieldHold}, and changes nothing about the main hand:
 * <ul>
 *   <li>at the head, a click while the shield is already up on this press is not tried again (the game does not either with an item in use);</li>
 *   <li>each of the three calls, for the off hand while the shield is on, is skipped, so the real item there is never used or sent (shield priority);
 *       for the main hand its result is noted: a success (or a block's refusal) means the main hand used the click;</li>
 *   <li>at the return, a click the main hand did not use raises the shield.</li>
 * </ul>
 * The targets are the three {@code MultiPlayerGameMode} calls in {@code startUseItem} (checked against the 26.3 bytecode).
 */
@Mixin(Minecraft.class)
public abstract class MinecraftUseItemMixin {
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void morph$shieldBegin(CallbackInfo ci) {
		if (!ArmHooks.live) return;
		Minecraft mc = (Minecraft) (Object) this;
		LocalPlayer player = mc.player;
		MultiPlayerGameMode gameMode = mc.gameMode;
		// the game tries the main hand unless a block is being broken or both hands are busy (it has returned by then)
		boolean tried = player != null && gameMode != null && !gameMode.isDestroying() && !player.isHandsBusy();
		if (!ShieldHold.begin(mc.options.keyUse.isDown(), tried)) ci.cancel();
	}

	@Inject(method = "startUseItem", at = @At("RETURN"))
	private void morph$shieldEnd(CallbackInfo ci) {
		if (!ArmHooks.live) return;
		LocalPlayer player = ((Minecraft) (Object) this).player;
		ShieldHold.end(player != null && player.isUsingItem());
	}

	@WrapOperation(method = "startUseItem", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;interact(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;"
					+ "Lnet/minecraft/world/phys/EntityHitResult;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"))
	private InteractionResult morph$shieldInteract(MultiPlayerGameMode gameMode, Player player, Entity entity, EntityHitResult hit, InteractionHand hand,
			Operation<InteractionResult> original) {
		boolean off = hand == InteractionHand.OFF_HAND;
		if (ArmHooks.live && ShieldHold.skip(off)) return InteractionResult.PASS;
		InteractionResult result = original.call(gameMode, player, entity, hit, hand);
		if (ArmHooks.live) ShieldHold.note(off, result instanceof InteractionResult.Success);
		return result;
	}

	@WrapOperation(method = "startUseItem", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;useItemOn(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/InteractionHand;"
					+ "Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"))
	private InteractionResult morph$shieldUseOn(MultiPlayerGameMode gameMode, LocalPlayer player, InteractionHand hand, BlockHitResult hit,
			Operation<InteractionResult> original) {
		boolean off = hand == InteractionHand.OFF_HAND;
		if (ArmHooks.live && ShieldHold.skip(off)) return InteractionResult.PASS;
		InteractionResult result = original.call(gameMode, player, hand, hit);
		// a block's refusal (Fail) ends the click as a success does, so it counts as the main hand's too
		if (ArmHooks.live) ShieldHold.note(off, result instanceof InteractionResult.Success || result instanceof InteractionResult.Fail);
		return result;
	}

	@WrapOperation(method = "startUseItem", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;useItem(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)"
					+ "Lnet/minecraft/world/InteractionResult;"))
	private InteractionResult morph$shieldUse(MultiPlayerGameMode gameMode, Player player, InteractionHand hand, Operation<InteractionResult> original) {
		boolean off = hand == InteractionHand.OFF_HAND;
		if (ArmHooks.live && ShieldHold.skip(off)) return InteractionResult.PASS;
		InteractionResult result = original.call(gameMode, player, hand);
		// the arm crossbow's click is the arm's whatever the game answers for an empty crossbow (the server fires the bolt; ShieldHold.armCrossbowClick)
		if (ArmHooks.live) ShieldHold.note(off, result instanceof InteractionResult.Success || (!off && player instanceof LocalPlayer self && ShieldHold.armCrossbowClick(self)));
		return result;
	}
}
