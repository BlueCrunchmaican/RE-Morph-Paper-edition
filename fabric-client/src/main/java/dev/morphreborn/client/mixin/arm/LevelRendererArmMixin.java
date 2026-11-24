package dev.morphreborn.client.mixin.arm;

import dev.morphreborn.client.arm.model.ArmRender;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the level render's own submits (entities, block entities and the rest of {@code submitFeatures}) as the world
 * pass, so an arm drawn there is told from one in the paper doll or a preview. In 26.3 those submits run before
 * Fabric's START_MAIN, so the event can't mark them.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererArmMixin {
	@Inject(method = "submitFeatures", at = @At("HEAD"))
	private void morphreborn$worldSubmitsStart(CallbackInfo ci) {
		ArmRender.worldSubmits(true);
	}

	@Inject(method = "submitFeatures", at = @At("RETURN"))
	private void morphreborn$worldSubmitsEnd(CallbackInfo ci) {
		ArmRender.worldSubmits(false);
	}
}
