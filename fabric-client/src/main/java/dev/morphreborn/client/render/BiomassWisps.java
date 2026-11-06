package dev.morphreborn.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The light absorb effect, for kills that only give biomass: three thin streams of red motes curve from the body into
 * the player over about two thirds of a second and end in a small red puff. The mob dies as usual; nothing is taken.
 * Much quicker and quieter than the tendrils, so a new morph still stands out.
 */
public final class BiomassWisps {
	static final int DURATION = 14;
	private static final int STRANDS = 3;
	private static final DustParticleOptions MOTE = new DustParticleOptions(0xB0202A, 0.8F);
	private static final DustParticleOptions BRIGHT = new DustParticleOptions(0xE0404A, 0.6F);

	private final LivingEntity origin;
	private final Vec3 from;
	private final Vec3[] bends = new Vec3[STRANDS];
	int age;
	boolean removed;

	public BiomassWisps(LivingEntity origin, LivingEntity source) {
		this.origin = origin;
		this.from = source.position().add(0, source.getBbHeight() / 2D, 0);
		RandomSource r = RandomSource.create();
		Vec3 to = target(1F);
		Vec3 mid = from.add(to).scale(0.5);
		for (int i = 0; i < STRANDS; i++) {
			bends[i] = mid.add(r.nextGaussian() * 0.6, 0.5 + r.nextFloat() * 0.6, r.nextGaussian() * 0.6);
		}
	}

	private Vec3 target(float partialTick) {
		return origin.getPosition(partialTick).add(0, origin.getBbHeight() * 0.6, 0);
	}

	public void tick() {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || origin.isRemoved() || origin.level() != level) {
			removed = true;
			return;
		}
		age++;
		Vec3 to = target(1F);
		float t = Mth.clamp(age / (float) DURATION, 0F, 1F);
		for (Vec3 bend : bends) {
			// Two motes per strand per tick: the head of the stream and one just behind it.
			for (float back : new float[] {0F, 0.08F}) {
				float s = Math.max(0F, t - back);
				Vec3 p = bezier(from, bend, to, s);
				level.addParticle(back == 0F ? BRIGHT : MOTE, p.x, p.y, p.z, 0, 0, 0);
			}
		}
		if (age >= DURATION) {
			RandomSource r = level.getRandom();
			for (int i = 0; i < 6; i++) {
				level.addParticle(MOTE, to.x + r.nextGaussian() * 0.2, to.y + r.nextGaussian() * 0.25, to.z + r.nextGaussian() * 0.2, 0, 0, 0);
			}
			removed = true;
		}
	}

	private static Vec3 bezier(Vec3 a, Vec3 b, Vec3 c, float t) {
		float u = 1F - t;
		return a.scale(u * u).add(b.scale(2F * u * t)).add(c.scale(t * t));
	}
}
