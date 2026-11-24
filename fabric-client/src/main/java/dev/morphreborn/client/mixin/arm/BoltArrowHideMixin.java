package dev.morphreborn.client.mixin.arm;

import dev.morphreborn.client.arm.fx.BoltFx;
import dev.morphreborn.client.arm.fx.CrossbowFx;
import dev.morphreborn.client.arm.fx.TridentFx;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A bow arm's bolt is a real arrow on the server; clients that draw arms show it as a tendril (BoltFx), so the vanilla
 * arrow inside the tendril coat isn't drawn. Clients without the mod still see a plain arrow.
 */
@Mixin(EntityRenderer.class)
abstract class BoltArrowHideMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private void morphreborn$hideBoltArrow(Entity entity, Frustum culler, double camX, double camY, double camZ, float partialTicks,
			CallbackInfoReturnable<Boolean> cir) {
		// the thrown trident of an arm (TridentFx) is hidden the same way, whatever entity the server flies it as; and the crossbow's bolts and spines
		// (CrossbowFx), which are drawn as the arm's own tendril bolt and bone spine
		if (entity instanceof AbstractArrow && BoltFx.hides(entity.getId()) || TridentFx.hides(entity.getId())
				|| CrossbowFx.hides(entity.getId())) cir.setReturnValue(false);
	}
}
