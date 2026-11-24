package dev.morphreborn.paper.arm;

import dev.morphreborn.paper.MorphRebornPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.MainHand;
import org.bukkit.util.Vector;

/**
 * The arm's forming, retracting and melting as everyone hears them, and as players whose client doesn't draw arms see
 * them: a dark puff of dust at the hand where the item appears or vanishes. Clients that draw arms animate all of this
 * themselves from S_ARM (and puff their own ink), so the particles go only to the others, older modded clients
 * included (they get the plain vanilla tool). The sounds are the server's for everyone (spec 2.6): Morph's own recordings ({@link ArmSounds}).
 */
final class ArmEffects {
	/** The morph skin's greys, as the rest of the plugin's particle effects use them. */
	private static final Color[] SKIN = {Color.fromRGB(2, 2, 2), Color.fromRGB(15, 15, 15), Color.fromRGB(26, 26, 26), Color.fromRGB(38, 38, 38)};
	private static final double VIEW_RANGE_SQ = 64 * 64;
	/** How far an arm sound carries: the game's own 16 blocks at full volume. */
	private static final double HEARING_SQ = 16 * 16;

	private final MorphRebornPlugin plugin;

	ArmEffects(MorphRebornPlugin plugin) {
		this.plugin = plugin;
	}

	/** A new arm taking shape: tendrils clicking out of the knuckles (FORM), or the quick re-form of scrolling onto a bound slot. */
	void forming(Player p, boolean quick) {
		sound(p, quick ? ArmSounds.Moment.FORM_QUICK : ArmSounds.Moment.FORM);
		if (!quick) puff(p, 4, 6, 0); // a quick re-form is sound only (spec 2.6), as clients that draw arms show it
	}

	/** One form collapsing into another. */
	void reshaping(Player p) {
		sound(p, ArmSounds.Moment.RESHAPE);
		puff(p, 3, 6, 0);
	}

	/** A chosen retract begins (12 ticks): the tool shrinks back into the hand. */
	void retracting(Player p) {
		sound(p, ArmSounds.Moment.RETRACT);
	}

	/** A chosen retract ends: a small puff where the tool was. */
	void retracted(Player p) {
		puff(p, 2, 4, 0);
	}

	/** Out of biomass: the arm sags and melts for 20 ticks. */
	void melting(Player p) {
		sound(p, ArmSounds.Moment.MELT);
		p.sendActionBar(Component.text("Your arm ran out of biomass.", NamedTextColor.RED));
	}

	/** A melt under way: drips off the hand. */
	void meltTick(Player p, int elapsed) {
		if (elapsed % 2 != 0) return;
		List<Player> viewers = viewers(p);
		if (viewers.isEmpty()) return;
		ThreadLocalRandom r = ThreadLocalRandom.current();
		Location at = hand(p).add(r.nextGaussian() * 0.08, -0.05, r.nextGaussian() * 0.08);
		Particle.DustOptions dust = new Particle.DustOptions(SKIN[r.nextInt(SKIN.length)], 0.9F);
		for (Player v : viewers) v.spawnParticle(Particle.DUST, at, 2, 0.04, 0.12, 0.04, 0, dust, true);
	}

	/** The melt is over and the item comes back. */
	void melted(Player p) {
		puff(p, 6, 4, 0);
	}

	/** A forced retract: the item vanishes at once, and the puff masks the pop. */
	void popped(Player p) {
		puff(p, 6, 0, 2);
	}

	private void puff(Player p, int ink, int dust, int smoke) {
		List<Player> viewers = viewers(p);
		if (viewers.isEmpty()) return;
		ThreadLocalRandom r = ThreadLocalRandom.current();
		Location at = hand(p);
		Particle.DustOptions inkDust = new Particle.DustOptions(SKIN[1], 1.0F);
		for (Player v : viewers) {
			// dark dust where the game's ink would go: SQUID_INK is drawn as black squares, and these puffs sit at the hand, near anyone looking
			if (ink > 0) v.spawnParticle(Particle.DUST, at, ink, 0.08, 0.08, 0.08, 0, inkDust, true);
			if (smoke > 0) v.spawnParticle(Particle.LARGE_SMOKE, at, smoke, 0.06, 0.06, 0.06, 0.01, null, true);
			for (int i = 0; i < dust; i++) {
				Particle.DustOptions d = new Particle.DustOptions(SKIN[r.nextInt(SKIN.length)], 0.8F + r.nextFloat() * 0.6F);
				v.spawnParticle(Particle.DUST, at, 1, 0.12, 0.12, 0.12, 0, d, true);
			}
		}
	}

	/**
	 * One Morph sound for everyone in earshot, each at the key their game has: the event for players with the mod (or the
	 * server's pack), the transformation's stand-in for the rest, Bedrock players included.
	 */
	private void sound(Player p, ArmSounds.Moment moment) {
		var s = plugin.settings();
		Location at = p.getLocation();
		for (Player listener : at.getWorld().getPlayers()) {
			if (listener != p && listener.getLocation().distanceSquared(at) > HEARING_SQ) continue;
			boolean modded = plugin.channel().isModded(listener);
			boolean has = ArmSounds.hasEvents(modded, plugin.channel().hasArmSounds(listener), s.vanillaHasSoundPack);
			ArmSounds.Voice v = ArmSounds.voice(moment, has, s.morphSoundFallback);
			listener.playSound(Sound.sound(Key.key(v.key()), Sound.Source.PLAYER, v.volume(), v.pitch()), at.getX(), at.getY(), at.getZ());
		}
	}

	/** Players in range whose client doesn't draw arms (the owner included). */
	private List<Player> viewers(Player p) {
		List<Player> out = new ArrayList<>();
		Location at = p.getLocation();
		for (Player v : p.getWorld().getPlayers()) {
			if (plugin.channel().drawsArms(v)) continue;
			if (v == p || v.getLocation().distanceSquared(at) <= VIEW_RANGE_SQ) out.add(v);
		}
		return out;
	}

	/**
	 * Roughly where the arm's hand is as others see it: in front of the body, on the main-hand side, a little below the
	 * eyes.
	 */
	static Location hand(Player p) {
		Location eye = p.getEyeLocation();
		Vector look = eye.getDirection();
		Vector flat = new Vector(look.getX(), 0, look.getZ());
		if (flat.lengthSquared() < 1e-6) flat = new Vector(0, 0, 1);
		flat.normalize();
		// look x up points to the player's right
		Vector right = flat.clone().crossProduct(new Vector(0, 1, 0)).normalize();
		if (p.getMainHand() == MainHand.LEFT) right.multiply(-1);
		return eye.add(flat.multiply(0.4)).add(right.multiply(0.35)).add(0, -0.55, 0);
	}
}
