package dev.morphreborn.paper.effects;

import dev.morphreborn.paper.MorphRebornPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.data.BlockData;
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
	/** The arm's black tendrils as particles: the skin's mid grey (near-black dust reads as a shadow). */
	public static final Color ARM_GREY = SKIN[2];
	/** The arm's red tendrils (Draining Cut's healing ones): the frenzy's red. */
	public static final Color ARM_RED = RAGE;
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

	// ------------------------------------------------------------------ arm forms (8.0.0)

	/**
	 * Who gets an arm effect's particles: players near {@code at} whose game doesn't draw arms itself ({@code
	 * drawsNative}: unmodded players, and modded ones from before the arms, so they aren't left with nothing), the
	 * subject included. {@code skinShown}: leave out players who see the effect in the morph skin instead (SkinEffects).
	 */
	private List<Player> armViewers(Location at, Predicate<Player> drawsNative, boolean skinShown) {
		List<Player> out = new ArrayList<>();
		if (at.getWorld() == null) return out;
		for (Player p : at.getWorld().getPlayers()) {
			if (drawsNative.test(p)) continue;
			if (skinShown && plugin.skin() != null && plugin.skin().drawsFor(p)) continue;
			if (p.getLocation().distanceSquared(at) <= VIEW_RANGE_SQ) out.add(p);
		}
		return out;
	}

	/**
	 * One frame of an arm tendril from {@code from} to {@code to} (call it every other tick while it lasts): a wavering
	 * line of {@code color} dust, still at both ends, as the frenzy's ({@link #rageTether}), with motes now and then
	 * running along it, toward {@code from} when {@code motesToFrom} (Draining Cut's healing: the creature's life running
	 * into the blade), otherwise toward {@code to}. {@code seed} sets the wave, so each tendril sways its own way.
	 * Players who have the pack are left out: they get SkinEffects.tendril.
	 */
	public void tendril(Player origin, Location from, Location to, Color color, int seed, int tick, boolean motesToFrom, Predicate<Player> drawsNative) {
		if (from.getWorld() == null || from.getWorld() != to.getWorld()) return;
		List<Player> viewers = armViewers(from, drawsNative, true);
		if (viewers.isEmpty()) return;
		Vector line = to.toVector().subtract(from.toVector());
		double length = line.length();
		if (length < 0.3) return;
		Vector side = line.clone().crossProduct(new Vector(0, 1, 0));
		if (side.lengthSquared() < 1e-4) side = new Vector(1, 0, 0);
		side.normalize();
		Vector up = side.clone().crossProduct(line).normalize();
		int points = (int) Math.ceil(length * 3);
		double phase = seed * 1.7 + tick * 0.35;
		Particle.DustOptions dust = new Particle.DustOptions(color, 0.7F);
		for (int i = 1; i < points; i++) {
			double s = i / (double) points;
			double wave = Math.sin(s * Math.PI) * 0.25;
			Location at = from.clone().add(line.clone().multiply(s)).add(side.clone().multiply(wave * Math.sin(phase + s * 7)))
					.add(up.clone().multiply(wave * 0.6 * Math.cos(phase * 0.8 + s * 5)));
			for (Player v : viewers) v.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, dust, true);
		}
		// about every 6 ticks, whichever ticks the calls fall on
		if (Math.floorMod(tick, 6) < 2) {
			Location start = motesToFrom ? to : from, end = motesToFrom ? from : to;
			Particle.Trail trail = new Particle.Trail(end, color, 10 + ThreadLocalRandom.current().nextInt(5));
			for (Player v : viewers) v.spawnParticle(Particle.TRAIL, start, 3, 0.1, 0.1, 0.1, 0, trail, true);
		}
	}

	/**
	 * Draining Cut's swipe for players whose game doesn't draw it: a flat arc of {@code color} dust {@code radius} out
	 * from just below the eyes, {@code arcDeg} wide about where the player looks, and three sweep marks along it (none on a
	 * full ring). Dust and sweep marks only: no ink. Players with the pack get it too: the skin has no arc of its own.
	 */
	public void sweepArc(Player origin, double radius, double arcDeg, Color color, Predicate<Player> drawsNative) {
		Location eye = origin.getEyeLocation();
		Location centre = eye.clone().add(0, -0.35, 0);
		List<Player> viewers = armViewers(centre, drawsNative, false);
		if (viewers.isEmpty() || !(radius > 0) || !(arcDeg > 0)) return;
		double yaw = Math.toRadians(eye.getYaw());
		// along the ground: the look, and the player's right
		double lx = -Math.sin(yaw), lz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
		double half = Math.toRadians(Math.min(180, arcDeg / 2));
		int points = Math.max(8, (int) Math.ceil(radius * 2 * half / 0.15));
		Particle.DustOptions dust = new Particle.DustOptions(color, 1.0F);
		for (int i = 0; i <= points; i++) {
			Location at = onArc(centre, radius, -half + 2 * half * i / points, lx, lz, rx, rz);
			for (Player v : viewers) v.spawnParticle(Particle.DUST, at, 1, 0.02, 0.02, 0.02, 0, dust, true);
		}
		// from the right of the arc to the left: the way the blade sweeps. Not on a full ring (Reap): its marks would fall round anyone
		// standing in it, and a sweep mark is a flat sprite a block wide
		if (arcDeg < 360) {
			for (double f : new double[] {0.6, 0, -0.6}) {
				Location at = onArc(centre, radius, half * f, lx, lz, rx, rz);
				for (Player v : viewers) v.spawnParticle(Particle.SWEEP_ATTACK, at, 1, 0, 0, 0, 0, null, true);
			}
		}
		// No ink at the ends: SQUID_INK is drawn as dark squares up to half a block wide, which filled the view of anyone near the arc (rig
		// 2026-11-22: a third to half of the screen at 3 m, all of it for a watcher standing in Reap's ring).
	}

	/** The point {@code radius} out at {@code angle} radians from the look, to the right for positive angles. */
	private static Location onArc(Location centre, double radius, double angle, double lx, double lz, double rx, double rz) {
		double c = Math.cos(angle), s = Math.sin(angle);
		return centre.clone().add((lx * c + rx * s) * radius, 0, (lz * c + rz * s) * radius);
	}

	/**
	 * A straight line of {@code dust} from {@code from} to {@code to}, a mote every {@code step} blocks (Vein Rip's
	 * tendril to the ore it tears out). Players who have the pack are left out: they get SkinEffects.tendril.
	 */
	public void line(Player origin, Location from, Location to, Particle.DustOptions dust, double step, Predicate<Player> drawsNative) {
		if (from.getWorld() == null || from.getWorld() != to.getWorld()) return;
		List<Player> viewers = armViewers(from, drawsNative, true);
		if (viewers.isEmpty()) return;
		Vector d = to.toVector().subtract(from.toVector());
		int points = Math.max(1, (int) Math.ceil(d.length() / Math.max(0.05, step)));
		for (int i = 0; i <= points; i++) {
			Location at = from.clone().add(d.clone().multiply(i / (double) points));
			for (Player v : viewers) v.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, dust, true);
		}
	}

	/**
	 * A block crumbling at {@code at} as a tendril bites into it (Vein Rip's ore about to be torn out). Players who have
	 * the pack are left out, as for {@link #line}: their tendril shows the bite.
	 */
	public void crumble(Player origin, Location at, BlockData block, Predicate<Player> drawsNative) {
		for (Player v : armViewers(at, drawsNative, true)) v.spawnParticle(Particle.BLOCK_CRUMBLE, at, 10, 0.3, 0.3, 0.3, 0, block, true);
	}

	/**
	 * A puff of the arm's dust at {@code at}, for players whose game doesn't draw arms: the forming puff the mod draws at
	 * the hand (dark and light motes of the skin's greys), which also masks an arm that comes or goes at once. Dust only:
	 * SQUID_INK is drawn as black squares up to a quarter block wide.
	 */
	public void armBurst(Location at, Predicate<Player> drawsNative) {
		List<Player> viewers = armViewers(at, drawsNative, false);
		if (viewers.isEmpty()) return;
		Particle.DustOptions dark = new Particle.DustOptions(SKIN[1], 1.0F), light = new Particle.DustOptions(SKIN[3], 1.0F);
		for (Player v : viewers) {
			v.spawnParticle(Particle.DUST, at, 7, 0.15, 0.15, 0.15, 0, dark, true);
			v.spawnParticle(Particle.DUST, at, 3, 0.15, 0.15, 0.15, 0, light, true);
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
