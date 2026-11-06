package dev.morphreborn.paper.effects;

import dev.morphreborn.paper.MorphRebornPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * What unmodded clients see instead of the client-rendered animations. Only sent to viewers without the Fabric mod,
 * so modded players get the original-style rendering without particle noise on top.
 * Colours are sampled from the original's {@code morphskin.png} (near-black biomass, greys 2..38).
 */
public final class VanillaEffects {
	private static final Color[] SKIN = {Color.fromRGB(2, 2, 2), Color.fromRGB(15, 15, 15), Color.fromRGB(26, 26, 26), Color.fromRGB(38, 38, 38)};
	private static final double VIEW_RANGE_SQ = 64 * 64;

	private final MorphRebornPlugin plugin;

	public VanillaEffects(MorphRebornPlugin plugin) {
		this.plugin = plugin;
	}

	private List<Player> vanillaViewers(Location at, Player subject, boolean includeSubject) {
		List<Player> out = new ArrayList<>();
		for (Player p : at.getWorld().getPlayers()) {
			if (plugin.channel().isModded(p)) continue;
			if (p == subject && !includeSubject) continue;
			if (p.getLocation().distanceSquared(at) <= VIEW_RANGE_SQ) out.add(p);
		}
		return out;
	}

	/**
	 * One tick of the transformation cocoon. {@code skinAlpha} follows the original's skin curve
	 * (fades in over the first 12.5%, full during the box transition, fades out over the last 12.5%).
	 */
	public void transitionTick(Player subject, float skinAlpha, double width, double height) {
		if (skinAlpha <= 0.02F) return;
		Location base = subject.getLocation();
		List<Player> viewers = vanillaViewers(base, subject, true);
		if (viewers.isEmpty()) return;
		ThreadLocalRandom r = ThreadLocalRandom.current();
		int count = Math.max(1, Math.round(10 * skinAlpha));
		for (int i = 0; i < count; i++) {
			double angle = r.nextDouble(Math.PI * 2);
			double radius = width * (0.45 + 0.25 * r.nextDouble());
			Location at = base.clone().add(Math.cos(angle) * radius, r.nextDouble() * Math.max(0.3, height), Math.sin(angle) * radius);
			Particle.DustOptions dust = new Particle.DustOptions(SKIN[r.nextInt(SKIN.length)], 0.8F + 0.8F * skinAlpha);
			for (Player v : viewers) v.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, dust);
		}
		if (r.nextFloat() < 0.3F * skinAlpha) {
			Location mid = base.clone().add(0, height / 2, 0);
			for (Player v : viewers) v.spawnParticle(Particle.SQUID_INK, mid, 2, width * 0.3, height * 0.3, width * 0.3, 0.01);
		}
	}

	/** The instant the vanilla disguise swaps (transition midpoint). */
	public void swapBurst(Player subject, double width, double height) {
		Location mid = subject.getLocation().add(0, height / 2, 0);
		for (Player v : vanillaViewers(mid, subject, true)) {
			v.spawnParticle(Particle.SQUID_INK, mid, 25, width * 0.4, height * 0.4, width * 0.4, 0.05);
			v.spawnParticle(Particle.LARGE_SMOKE, mid, 8, width * 0.3, height * 0.3, width * 0.3, 0.02);
		}
	}

	/** Five dark trails from the dying mob into the player, standing in for the original tendrils. */
	public void acquisition(Player origin, LivingEntity acquired) {
		if (!plugin.settings().vanillaAcquisitionEffects) return;
		Location from = acquired.getLocation().add(0, acquired.getHeight() / 2, 0);
		Location to = origin.getLocation().add(0, origin.getHeight() / 2, 0);
		List<Player> viewers = vanillaViewers(from, origin, true);
		if (viewers.isEmpty()) return;
		ThreadLocalRandom r = ThreadLocalRandom.current();
		for (int i = 0; i < 5; i++) {
			Location start = from.clone().add(r.nextGaussian() * 0.25, r.nextGaussian() * 0.25, r.nextGaussian() * 0.25);
			Particle.Trail trail = new Particle.Trail(to, SKIN[r.nextInt(SKIN.length)], 25 + r.nextInt(15));
			for (Player v : viewers) v.spawnParticle(Particle.TRAIL, start, 6, 0.1, 0.1, 0.1, 0, trail, true);
		}
		Particle.DustOptions dust = new Particle.DustOptions(SKIN[0], 1.6F);
		for (Player v : viewers) {
			v.spawnParticle(Particle.DUST, from, 30, acquired.getWidth() * 0.4, acquired.getHeight() * 0.4, acquired.getWidth() * 0.4, 0, dust);
		}
	}

	/**
	 * The morph sound, played half-way through so its 3 seconds are centred like the original.
	 * Modded players always get the real sound (their mod ships it); others get it only if the server pack has it.
	 */
	public void morphSound(Player subject) {
		var s = plugin.settings();
		Sound custom = Sound.sound(Key.key(s.morphSound), Sound.Source.PLAYER, s.morphSoundVolume, 1F);
		Sound fallback = Sound.sound(Key.key(s.morphSoundFallback), Sound.Source.PLAYER, s.morphSoundVolume * 0.6F, 0.8F);
		Location at = subject.getLocation();
		for (Player p : at.getWorld().getPlayers()) {
			if (p.getLocation().distanceSquared(at) > 32 * 32) continue;
			boolean hasSound = plugin.channel().isModded(p) || s.vanillaHasSoundPack;
			p.playSound(hasSound ? custom : fallback, subject);
		}
	}

	public static Vector randomUnit() {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		return new Vector(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize();
	}

	public static boolean visibleTo(Entity e, Player p) {
		return p.canSee(e);
	}
}
