package dev.morphreborn.client.mixin;

import dev.morphreborn.client.morph.ClientTraits;
import dev.morphreborn.client.morph.ShulkerLock;
import dev.morphreborn.client.net.Wire;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Spiders aren't slowed by cobwebs, foxes by berry bushes, powder-snow walkers by powder snow (as in vanilla). A
 * shulker held in its block doesn't move at all.
 */
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

	/**
	 * The local player held in a block's space as a shulker (ShulkerLock) moves by nothing, whatever moves them: their
	 * own walking or flying, knockback, a piston, a lid opening under them. The server holds them there too, and would
	 * only pull them back. Every kind of move, not just their own. Input is untouched: the server reads sneak and jump
	 * from it.
	 * <p>
	 * A move is the only place the game updates the ground flag, so a cancelled one froze it at whatever it was when the
	 * hold began, often on the ground. On the ground and flying, the player's own game turns flight off at the end of
	 * every tick (LocalPlayer.aiStep) and the server turns it straight back on: a stream of ability packets and flight
	 * toggle events, a crouch on the ticks in between that lowers the eye the server aims the teleport from, and a
	 * ceiling shulker the server thinks is standing. The server's hold flies them, so they are off the ground, as the
	 * zero-length move of a player without the mod says too. Only while flying, vanilla's own condition; the game
	 * refuses an elytra glide while flying, so being off the ground opens no way to start one.
	 */
	@Inject(method = "move", at = @At("HEAD"), cancellable = true)
	private void morph$shulkerHold(MoverType type, Vec3 delta, CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		if (ShulkerLock.holdsLocalPlayer(self)) {
			if (self instanceof Player player && player.getAbilities().flying && self.onGround()) self.setOnGround(false);
			self.setDeltaMovement(Vec3.ZERO);
			ci.cancel();
		}
	}
}
