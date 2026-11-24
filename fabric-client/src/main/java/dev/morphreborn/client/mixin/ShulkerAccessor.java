package dev.morphreborn.client.mixin;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.monster.Shulker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * A shulker morph held in a block's space (ShulkerLock) is attached and opened as the server says. Vanilla only sets
 * these from the server or a save; on a client-side entity setRawPeekAmount only sets the synced byte (its armour and
 * sound are server-side), and the entity animates its lid toward it as a real shulker does.
 */
@Mixin(Shulker.class)
public interface ShulkerAccessor {
	@Invoker("setAttachFace")
	void morph$setAttachFace(Direction face);

	@Invoker("getRawPeekAmount")
	int morph$getRawPeekAmount();

	@Invoker("setRawPeekAmount")
	void morph$setRawPeekAmount(int peek);

	@Accessor("currentPeekAmount")
	void morph$setCurrentPeekAmount(float peek);

	@Accessor("currentPeekAmountO")
	void morph$setCurrentPeekAmountO(float peek);
}
