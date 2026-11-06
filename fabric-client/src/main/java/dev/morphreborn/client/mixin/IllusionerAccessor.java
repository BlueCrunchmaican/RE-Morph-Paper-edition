package dev.morphreborn.client.mixin;

import net.minecraft.world.entity.monster.illager.Illusioner;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The illusioner's mirror images: [previous, current] offsets of its four copies, blended over a few ticks. */
@Mixin(Illusioner.class)
public interface IllusionerAccessor {
	@Accessor("clientSideIllusionOffsets")
	Vec3[][] morph$offsets();

	@Accessor("clientSideIllusionTicks")
	void morph$setIllusionTicks(int ticks);
}
