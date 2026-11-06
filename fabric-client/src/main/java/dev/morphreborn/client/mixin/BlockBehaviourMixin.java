package dev.morphreborn.client.mixin;

import dev.morphreborn.client.morph.ClientTraits;
import dev.morphreborn.client.net.Wire;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Webs as scaffolding for spider morphs (spider and cave spider): the top of a cobweb is a floor while you are above it
 * and not sneaking, exactly like scaffolding's (ScaffoldingBlock.getCollisionShape). Climbing inside webs is in
 * LivingEntityMixin. Everyone else, and spiders walking through a web from the side, still pass through.
 */
@Mixin(BlockBehaviour.class)
public abstract class BlockBehaviourMixin {
	@Unique
	private static final VoxelShape WEB_TOP = Block.column(16.0, 14.0, 16.0);

	@Inject(method = "getCollisionShape", at = @At("HEAD"), cancellable = true)
	private void morph$webScaffolding(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
		if (!((Object) this instanceof WebBlock)) return;
		if (!(context instanceof EntityCollisionContext entityContext) || entityContext.getEntity() == null) return;
		if (!ClientTraits.has(entityContext.getEntity(), Wire.TRAIT_WEB)) return;
		if (context.isAbove(Shapes.block(), pos, true) && !context.isDescending()) cir.setReturnValue(WEB_TOP);
	}
}
