package dev.morphreborn.client.mixin;

import dev.morphreborn.client.morph.CameraHeight;
import net.minecraft.client.Camera;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The camera chases the eye height {@link CameraHeight} gives a transforming player, instead of the hitbox's. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getEyeHeight()F"))
	private float morph$eyeHeight(Entity entity) {
		if (entity instanceof LocalPlayer player) {
			float eased = CameraHeight.eyeHeight(player);
			if (!Float.isNaN(eased)) return eased;
		}
		return entity.getEyeHeight();
	}
}
