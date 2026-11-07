package dev.morphreborn.client.mixin;

import net.minecraft.client.model.monster.dragon.EnderDragonModel;
import net.minecraft.client.renderer.entity.EnderDragonRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Multimorph (render/HybridMorph): the dragon's model, to pose and measure it as its renderer draws it. */
@Mixin(EnderDragonRenderer.class)
public interface EnderDragonRendererAccessor {
	@Accessor("model")
	EnderDragonModel morph$model();
}
