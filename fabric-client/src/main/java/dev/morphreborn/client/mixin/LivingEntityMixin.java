package dev.morphreborn.client.mixin;

import dev.morphreborn.client.morph.ClientTraits;
import dev.morphreborn.client.morph.WallClimb;
import dev.morphreborn.client.net.Wire;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Spider morphs treat any wall as a ladder (WallClimb); strider morphs stand on lava. Player.onClimbable still rules
 * out creative flight first.
 * <p>
 * Standing on lava takes both halves of Strider's own setup: canStandOnFluid, and the half-block floor it collides
 * with (LiquidBlock asks the entity for that shape, and a player's is empty).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Unique
	private static final VoxelShape STRIDER_LAVA_FLOOR = Block.column(16.0, 0.0, 8.0);

	@Inject(method = "onClimbable", at = @At("HEAD"), cancellable = true)
	private void morph$wallClimb(CallbackInfoReturnable<Boolean> cir) {
		if (WallClimb.isClimbing((LivingEntity) (Object) this)) cir.setReturnValue(true);
	}

	@Inject(method = "canStandOnFluid", at = @At("HEAD"), cancellable = true)
	private void morph$standOnLava(FluidState fluid, CallbackInfoReturnable<Boolean> cir) {
		if (fluid.is(FluidTags.LAVA) && ClientTraits.has((LivingEntity) (Object) this, Wire.TRAIT_LAVA)) cir.setReturnValue(true);
	}

	@Inject(method = "getLiquidCollisionShape", at = @At("HEAD"), cancellable = true)
	private void morph$lavaFloor(CallbackInfoReturnable<VoxelShape> cir) {
		if (ClientTraits.has((LivingEntity) (Object) this, Wire.TRAIT_LAVA)) cir.setReturnValue(STRIDER_LAVA_FLOOR);
	}
}
