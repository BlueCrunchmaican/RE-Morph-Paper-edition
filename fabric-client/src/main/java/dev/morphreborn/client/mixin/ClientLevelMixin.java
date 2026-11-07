package dev.morphreborn.client.mixin;

import dev.morphreborn.client.morph.OwnParticles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Your own morph body's particles in first person go behind the camera, half as many (see {@link OwnParticles}). */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	@Inject(method = "doAddParticle", at = @At("HEAD"), cancellable = true)
	private void morph$ownParticles(ParticleOptions particle, boolean overrideLimiter, boolean alwaysShow, double x, double y, double z, double xd, double yd,
			double zd, CallbackInfo ci) {
		if (!OwnParticles.active()) return;
		ci.cancel();
		if (!OwnParticles.keep()) return;
		OwnParticles.end(); // the moved particle goes through the normal checks once, without coming back here
		try {
			net.minecraft.world.phys.Vec3 at = OwnParticles.behind(x, y, z);
			((ClientLevel) (Object) this).addParticle(particle, overrideLimiter, alwaysShow, at.x, at.y, at.z, xd, yd, zd);
		} finally {
			// still inside the body's tick: the rest of what it spawns this tick is handled the same way
			OwnParticles.resume();
		}
	}
}
