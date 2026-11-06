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

	@org.spongepowered.asm.mixin.Shadow
	protected boolean jumping;

	@Inject(method = "onClimbable", at = @At("HEAD"), cancellable = true)
	private void morph$wallClimb(CallbackInfoReturnable<Boolean> cir) {
		if (WallClimb.isClimbing((LivingEntity) (Object) this)) cir.setReturnValue(true);
		else if (morph$inWebScaffold()) cir.setReturnValue(true);
	}

	/**
	 * Webs as scaffolding for spider morphs: inside a cobweb you climb by jumping and otherwise slide down slowly, as in
	 * scaffolding. On the ground a web is only climbable while jumping, so walking through webs keeps its full speed.
	 * (Standing on a web's top: BlockBehaviourMixin.)
	 */
	@Unique
	private boolean morph$inWebScaffold() {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!ClientTraits.has(self, Wire.TRAIT_WEB) || self.isSpectator() || !self.getInBlockState().is(net.minecraft.world.level.block.Blocks.COBWEB)) return false;
		return this.jumping || !self.onGround();
	}

	/** Scaffolding, not a ladder: sneaking inside webs takes you down instead of holding you in place. */
	@Inject(method = "handleOnClimbable", at = @At("RETURN"), cancellable = true)
	private void morph$webDescend(net.minecraft.world.phys.Vec3 delta, CallbackInfoReturnable<net.minecraft.world.phys.Vec3> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		net.minecraft.world.phys.Vec3 out = cir.getReturnValue();
		if (self.isShiftKeyDown() && out.y == 0.0 && morph$inWebScaffold()) cir.setReturnValue(new net.minecraft.world.phys.Vec3(out.x, -0.15, out.z));
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
