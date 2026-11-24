package dev.morphreborn.client.mixin.arm;

import dev.morphreborn.client.arm.ArmHooks;
import dev.morphreborn.client.arm.gui.SpyglassZoom;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Zooming with the spyglass accessory counts as scoping (2026-11-16: "zoom like the vanilla spyglass, a scope vignette,
 * hide the hand"). Everything the game does for the item hangs off {@code Player.isScoping()}: the scope's vignette in the HUD, the
 * hands hidden in first person, the mouse slowed, the beacon beams and sign text thinned out. Saying yes while the zoom key is held
 * gives the accessory all of it, as the item has it, without copying any of it. {@code SpyglassZoom.zooming()} is true only for this
 * client's own player, in first person, with the accessory on, so any other player (and the server's player in a single-player
 * world) answers as the game does.
 * <p>
 * The field of view is not part of it: the game only zooms for scoping while an item is in use, so {@code AbstractClientPlayerMixin}
 * does that one itself, eased.
 */
@Mixin(Player.class)
public abstract class PlayerScopeMixin {
	@Inject(method = "isScoping", at = @At("HEAD"), cancellable = true)
	private void morph$tendrilScope(CallbackInfoReturnable<Boolean> cir) {
		if (ArmHooks.live && SpyglassZoom.zooming() && (Object) this instanceof LocalPlayer) cir.setReturnValue(true);
	}
}
