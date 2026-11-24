package dev.morphreborn.client.mixin;

import dev.morphreborn.client.morph.MorphEntities;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A shulker morph's opening lid pushes nobody. Vanilla's lid moves whatever stands where it opens into
 * (onPeekAmountChange), and a morph entity ticks on the client level like a real one, so it pushed the player it stands
 * for (whose box reaches the top of the shell) and pushed anyone standing on another player's shell a second time:
 * the server shows players with the mod a hidden real shulker in a held shulker's block, and that one already pushes
 * them as it opens.
 */
@Mixin(Shulker.class)
public abstract class ShulkerMixin {
	@Redirect(method = "onPeekAmountChange", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
	private List<Entity> morph$noPushFromMorphs(Level level, Entity except, AABB box, Predicate<? super Entity> selector) {
		if (MorphEntities.isMorph((Entity) (Object) this)) return List.of();
		return level.getEntities(except, box, selector);
	}
}
