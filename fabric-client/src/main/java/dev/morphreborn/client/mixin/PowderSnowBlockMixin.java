package dev.morphreborn.client.mixin;

import dev.morphreborn.client.morph.ClientTraits;
import dev.morphreborn.client.net.Wire;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.PowderSnowBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rabbits, foxes, endermites and silverfish walk on top of powder snow (vanilla powder_snow_walkable_mobs). */
@Mixin(PowderSnowBlock.class)
public abstract class PowderSnowBlockMixin {
	@Inject(method = "canEntityWalkOnPowderSnow", at = @At("HEAD"), cancellable = true)
	private static void morph$walkOnSnow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (ClientTraits.has(entity, Wire.TRAIT_POWDER_SNOW)) cir.setReturnValue(true);
	}
}
