package dev.morphreborn.client.morph;

import dev.morphreborn.client.net.Wire;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * Movement traits of the local player's current form that only the player's own game can apply smoothly: climbing
 * walls, walking through cobwebs and berry bushes, walking on powder snow, standing on lava, a chicken's slow fall.
 * The server sends them
 * (S_TRAITS) whenever the form changes.
 */
public final class ClientTraits {
	private static int traits;

	private ClientTraits() {}

	public static void set(int bits) {
		traits = bits;
		WallClimb.setAllowed((bits & Wire.TRAIT_CLIMB) != 0);
	}

	/** True when {@code entity} is the local player and their form has {@code trait} (a Wire.TRAIT_* bit). */
	public static boolean has(Entity entity, int trait) {
		return (traits & trait) != 0 && entity == Minecraft.getInstance().player;
	}

	/**
	 * End of each client tick: a strider morph that sank into lava floats back up (Strider.floatStrider), until it is
	 * above the half-block floor LivingEntityMixin gives it and stands there.
	 */
	public static void tickLava(@Nullable LocalPlayer player) {
		if (player == null || !has(player, Wire.TRAIT_LAVA) || !player.isInLava()) return;
		if (CollisionContext.of(player).isAbove(player.getLiquidCollisionShape(), player.blockPosition(), true)
				&& !player.level().getFluidState(player.blockPosition().above()).is(FluidTags.LAVA)) return;
		player.setDeltaMovement(player.getDeltaMovement().scale(0.5).add(0.0, 0.05, 0.0));
	}

	/**
	 * End of each client tick: a chicken morph falls the way a chicken does (Chicken.aiStep multiplies its falling
	 * speed by 0.6 every tick in the air). Only the vertical speed changes, so jumping and running keep their momentum.
	 */
	public static void tickSlowFall(@Nullable LocalPlayer player) {
		if (player == null || !has(player, Wire.TRAIT_SLOW_FALL) || player.onGround() || player.getAbilities().flying || player.isInWater()
				|| player.isFallFlying() || player.onClimbable()) return;
		Vec3 movement = player.getDeltaMovement();
		if (movement.y < 0.0) {
			player.setDeltaMovement(movement.multiply(1.0, 0.6, 1.0));
			player.resetFallDistance();
		}
	}
}
