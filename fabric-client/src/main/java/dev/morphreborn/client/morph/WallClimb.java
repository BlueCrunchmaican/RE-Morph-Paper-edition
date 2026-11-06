package dev.morphreborn.client.morph;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Spider wall climbing for the local player, using vanilla's own ladder physics: while it's on, onClimbable() answers
 * true (LivingEntityMixin), so walking into a wall or jumping against it climbs at ladder speed, letting go slides down
 * slowly and sneaking holds you in place. The server says whether the current form may climb (S_TRAITS).
 */
public final class WallClimb {
	private static boolean allowed;
	private static boolean climbing;

	private WallClimb() {}

	public static void setAllowed(boolean value) {
		allowed = value;
		if (!value) climbing = false;
	}

	public static boolean isClimbing(LivingEntity entity) {
		return climbing && entity == Minecraft.getInstance().player;
	}

	/**
	 * End of each client tick, like a real spider's climbing flag: you get on by walking into a wall, and stay on while
	 * you're touching one off the ground. Walking or jumping past a wall without pushing into it doesn't grab it.
	 */
	public static void tick(@Nullable LocalPlayer player) {
		if (player == null || !allowed || player.isSpectator() || player.getAbilities().flying || player.isFallFlying() || player.isPassenger()) {
			climbing = false;
			return;
		}
		climbing = touchingWall(player) && (player.horizontalCollision || (climbing && !player.onGround()));
	}

	private static boolean touchingWall(LocalPlayer player) {
		AABB box = player.getBoundingBox();
		AABB shell = new AABB(box.minX - 0.08, box.minY + 0.05, box.minZ - 0.08, box.maxX + 0.08, box.maxY - 0.05, box.maxZ + 0.08);
		return !player.level().noBlockCollision(player, shell);
	}
}
