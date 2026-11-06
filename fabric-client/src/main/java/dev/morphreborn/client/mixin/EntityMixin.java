package dev.morphreborn.client.mixin;

import dev.morphreborn.client.morph.ClientTraits;
import dev.morphreborn.client.net.Wire;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Spiders aren't slowed by cobwebs, foxes by berry bushes, powder-snow walkers by powder snow (as in vanilla). */
@Mixin(Entity.class)
public abstract class EntityMixin {
	@Inject(method = "makeStuckInBlock", at = @At("HEAD"), cancellable = true)
	private void morph$passThrough(BlockState state, Vec3 speedMultiplier, CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		if ((state.is(Blocks.COBWEB) && ClientTraits.has(self, Wire.TRAIT_WEB))
				|| (state.is(Blocks.SWEET_BERRY_BUSH) && ClientTraits.has(self, Wire.TRAIT_BERRY))
				|| (state.is(Blocks.POWDER_SNOW) && ClientTraits.has(self, Wire.TRAIT_POWDER_SNOW))) {
			ci.cancel();
		}
	}
}
