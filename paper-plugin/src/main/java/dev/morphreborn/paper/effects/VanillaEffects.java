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
	/** Biomass gains: the red of the biomass bar. */
	private static final Color BIOMASS = Color.fromRGB(105, 24, 26);
	/** A rage's tendrils: the red of the red tendrils. */
	private static final Color RAGE = Color.fromRGB(150, 28, 32);
	private static final double VIEW_RANGE_SQ = 64 * 64;

	private final MorphRebornPlugin plugin;

	public VanillaEffects(MorphRebornPlugin plugin) {
		this.plugin = plugin;
	}

	private List<Player> vanillaViewers(Location at, Player subject, boolean includeSubject) {
		return vanillaViewers(at, subject, includeSubject, false);
	}

	/** {@code skinShown}: leave out the players who see it drawn in the morph skin instead (SkinEffects). */
	private List<Player> vanillaViewers(Location at, Player subject, boolean includeSubject, boolean skinShown) {
		List<Player> out = new ArrayList<>();
		for (Player p : at.getWorld().getPlayers()) {
			if (plugin.channel().isModded(p)) continue;
			if (skinShown && plugin.skin().drawsFor(p)) continue;
			if (p == subject && !includeSubject) continue;
			if (p.getLocation().distanceSquared(at) <= VIEW_RANGE_SQ) out.add(p);
		}
		return out;
	}

	/**
	 * One tick of the transformation cocoon. {@code skinAlpha} follows the original's skin curve
	 * (fades in over the first 12.5%, full during the box transition, fades out over the last 12.5%).
	 */
	public void transitionTick(Player subject, float skinAlpha, double width, double height, boolean skinShown) {
		if (skinAlpha <= 0.02F) return;
		Location base = subject.getLocation();
		List<Player> viewers = vanillaViewers(base, subject, true, skinShown);
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
	public void swapBurst(Player subject, double width, double height, boolean skinShown) {
		Location mid = subject.getLocation().add(0, height / 2, 0);
		for (Player v : vanillaViewers(mid, subject, true, skinShown)) {
			v.spawnParticle(Particle.SQUID_INK, mid, 25, width * 0.4, height * 0.4, width * 0.4, 0.05);
			v.spawnParticle(Particle.LARGE_SMOKE, mid, 8, width * 0.3, height * 0.3, width * 0.3, 0.02);
		}
	}

	/** A new morph: dark trails and a cloud of morph-skin dust. Biomass only: a few red sparks, no cloud. */
	public void acquisition(Player origin, LivingEntity acquired, boolean newMorph, boolean skinShown) {
		if (!plugin.settings().vanillaAcquisitionEffects) return;
		Location from = acquired.getLocation().add(0, acquired.getHeight() / 2, 0);
		Location to = origin.getLocation().add(0, origin.getHeight() / 2, 0);
		List<Player> viewers = vanillaViewers(from, origin, true, skinShown);
		if (viewers.isEmpty()) return;
		ThreadLocalRandom r = ThreadLocalRandom.current();
		if (!newMorph) {
			for (int i = 0; i < 3; i++) {
				Location start = from.clone().add(r.nextGaussian() * 0.2, r.nextGaussian() * 0.2, r.nextGaussian() * 0.2);
				Particle.Trail trail = new Particle.Trail(to, BIOMASS, 14 + r.nextInt(6));
				for (Player v : viewers) v.spawnParticle(Particle.TRAIL, start, 3, 0.08, 0.08, 0.08, 0, trail, true);
			}
			return;
		}
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
	 * One frame of a rage's tendril: a wavering line of dark red from the player's chest to {@code target}, with red
	 * motes now and then running back along it into the player (the drain).
	 */
	public void rageTether(Player origin, LivingEntity target, int tick, boolean skinShown) {
		Location from = origin.getLocation().add(0, origin.getHeight() * 0.6, 0);
		Location to = target.getLocation().add(0, target.getHeight() / 2, 0);
		List<Player> viewers = vanillaViewers(from, origin, true, skinShown);
		if (viewers.isEmpty()) return;
		Vector line = to.toVector().subtract(from.toVector());
		double length = line.length();
		if (length < 0.3) return;
		// a sideways wave, still at both ends
		Vector side = line.clone().crossProduct(new Vector(0, 1, 0));
		if (side.lengthSquared() < 1e-4) side = new Vector(1, 0, 0);
		side.normalize();
		Vector up = side.clone().crossProduct(line).normalize();
		int points = (int) Math.ceil(length * 3);
		double phase = target.getEntityId() * 1.7 + tick * 0.35;
		Particle.DustOptions dust = new Particle.DustOptions(RAGE, 0.7F);
		for (int i = 1; i < points; i++) {
			double s = i / (double) points;
			double wave = Math.sin(s * Math.PI) * 0.25;
			Location at = from.clone().add(line.clone().multiply(s)).add(side.clone().multiply(wave * Math.sin(phase + s * 7)))
					.add(up.clone().multiply(wave * 0.6 * Math.cos(phase * 0.8 + s * 5)));
			for (Player v : viewers) v.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, dust, true);
		}
		if (tick % 6 == 0) {
			Particle.Trail trail = new Particle.Trail(from, BIOMASS, 10 + ThreadLocalRandom.current().nextInt(5));
			for (Player v : viewers) v.spawnParticle(Particle.TRAIL, to, 3, 0.1, 0.1, 0.1, 0, trail, true);
		}
	}

	/**
	 * The legs of a frenzy's hover as particle lines (players without the pack): {@code segments} are pairs of world
	 * points, hip to knee and knee to foot.
	 */
	public void legs(Player origin, List<Vector[]> segments, boolean skinShown) {
		Location at = origin.getLocation();
		List<Player> viewers = vanillaViewers(at, origin, true, skinShown);
		if (viewers.isEmpty()) return;
		Particle.DustOptions dust = new Particle.DustOptions(RAGE, 0.8F);
		for (Vector[] s : segments) {
			Vector d = s[1].clone().subtract(s[0]);
			int points = Math.max(2, (int) Math.ceil(d.length() * 4));
			for (int i = 0; i <= points; i++) {
				Location p = s[0].clone().add(d.clone().multiply(i / (double) points)).toLocation(at.getWorld());
				for (Player v : viewers) v.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, dust, true);
			}
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
