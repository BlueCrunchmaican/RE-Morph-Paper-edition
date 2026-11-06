package dev.morphreborn.client.mixin;

import dev.morphreborn.client.compat.PaperDollCompat;
import dev.morphreborn.client.render.MorphWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While the PaperDoll mod builds its doll, a morphed player is handed over as their morph (see {@link PaperDollCompat}). */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	@Inject(method = "createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;", at = @At("HEAD"),
			cancellable = true)
	private void morph$paperDoll(Entity entity, float partialTicks, CallbackInfoReturnable<EntityRenderState> cir) {
		if (!PaperDollCompat.drawing()) return;
		EntityRenderState morph = MorphWorldRenderer.extract(Minecraft.getInstance().getEntityRenderDispatcher(), entity, partialTicks);
		if (morph != null) cir.setReturnValue(morph);
	}
}
