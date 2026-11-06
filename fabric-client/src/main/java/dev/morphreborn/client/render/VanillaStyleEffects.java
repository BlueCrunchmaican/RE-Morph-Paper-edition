package dev.morphreborn.client.render;

import dev.morphreborn.client.data.MorphState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.TrailParticleOption;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * What players without the mod see, drawn locally for players who switched RE:Morph's own animations off in the
 * settings. The same particles, timing and colours as the server's VanillaEffects (which only sends them to unmodded
 * players), so this player sees the plain version and nobody else is affected.
 */
public final class VanillaStyleEffects {
	/** Colours of the original morphskin.png (near-black, greys 2..38), as in VanillaEffects. */
	private static final int[] SKIN = {0x020202, 0x0F0F0F, 0x1A1A1A, 0x262626};

	private VanillaStyleEffects() {}

	/** One tick of a vanilla-style transformation: the dark cocoon of dust, and a burst as the body swaps at the midpoint. */
	public static void transitionTick(Player subject, MorphState state) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || !state.isTransitioning()) return;
		RandomSource r = level.getRandom();
		float progress = state.morphProgress(0F);
		double width = subject.getBbWidth();
		double height = subject.getBbHeight();
		float before = state.duration <= 0 ? 1F : MorphState.clamp((state.time - 1F) / state.duration);
		if (before < 0.5F && progress >= 0.5F) {
			Vec3 mid = subject.position().add(0, height / 2, 0);
			burst(level, ParticleTypes.SQUID_INK, mid, 25, width * 0.4, height * 0.4, 0.05);
			burst(level, ParticleTypes.LARGE_SMOKE, mid, 8, width * 0.3, height * 0.3, 0.02);
		}
		float skinAlpha = progress < 0.125F ? MorphState.sineify(progress / 0.125F) : progress > 0.875F ? 1F - MorphState.sineify((progress - 0.875F) / 0.125F) : 1F;
		if (skinAlpha <= 0.02F) return;
		int count = Math.max(1, Math.round(10 * skinAlpha));
		for (int i = 0; i < count; i++) {
			double angle = r.nextDouble() * Math.PI * 2;
			double radius = width * (0.45 + 0.25 * r.nextDouble());
			level.addParticle(new DustParticleOptions(SKIN[r.nextInt(SKIN.length)], 0.8F + 0.8F * skinAlpha), subject.getX() + Math.cos(angle) * radius,
					subject.getY() + r.nextDouble() * Math.max(0.3, height), subject.getZ() + Math.sin(angle) * radius, 0, 0, 0);
		}
		if (r.nextFloat() < 0.3F * skinAlpha) {
			burst(level, ParticleTypes.SQUID_INK, subject.position().add(0, height / 2, 0), 2, width * 0.3, height * 0.3, 0.01);
		}
	}

	/** Five dark trails from the absorbed mob into the player, and a puff of dust where the mob was. */
	/** As the server shows players without the mod: dark trails and dust for a new morph, a few red sparks for biomass. */
	public static void acquisition(Entity origin, Entity acquired, boolean newMorph) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		RandomSource r = level.getRandom();
		Vec3 from = acquired.position().add(0, acquired.getBbHeight() / 2, 0);
		Vec3 to = origin.position().add(0, origin.getBbHeight() / 2, 0);
		if (!newMorph) {
			for (int i = 0; i < 3; i++) {
				Vec3 start = from.add(r.nextGaussian() * 0.2, r.nextGaussian() * 0.2, r.nextGaussian() * 0.2);
				burst(level, new TrailParticleOption(to, 0x69181A, 14 + r.nextInt(6)), start, 3, 0.08, 0.08, 0);
			}
			return;
		}
		for (int i = 0; i < 5; i++) {
			Vec3 start = from.add(r.nextGaussian() * 0.25, r.nextGaussian() * 0.25, r.nextGaussian() * 0.25);
			TrailParticleOption trail = new TrailParticleOption(to, SKIN[r.nextInt(SKIN.length)], 25 + r.nextInt(15));
			burst(level, trail, start, 6, 0.1, 0.1, 0);
		}
		double w = acquired.getBbWidth() * 0.4;
		double h = acquired.getBbHeight() * 0.4;
		DustParticleOptions dust = new DustParticleOptions(SKIN[0], 1.6F);
		for (int i = 0; i < 30; i++) {
			level.addParticle(dust, from.x + r.nextGaussian() * w, from.y + r.nextGaussian() * h, from.z + r.nextGaussian() * w, 0, 0, 0);
		}
	}

	/** Same spread as a server particle packet with a count: gaussian offsets, gaussian speed. */
	private static void burst(ClientLevel level, ParticleOptions particle, Vec3 at, int count, double spreadXZ, double spreadY, double speed) {
		RandomSource r = level.getRandom();
		for (int i = 0; i < count; i++) {
			level.addParticle(particle, at.x + r.nextGaussian() * spreadXZ, at.y + r.nextGaussian() * spreadY, at.z + r.nextGaussian() * spreadXZ,
					r.nextGaussian() * speed, r.nextGaussian() * speed, r.nextGaussian() * speed);
		}
	}
}
