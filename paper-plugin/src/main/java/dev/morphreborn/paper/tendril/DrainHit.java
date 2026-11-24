package dev.morphreborn.paper.tendril;

import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * One tendril hit, measured: the damage goes in as an ordinary hit from the player, and what comes back is the health
 * it really took (health and absorption, before against after; all of it on a kill). Healing is only ever paid from
 * that measure, so anything that softened or stopped the hit (armour, Protection, Resistance, a PvP plugin cancelling
 * it, a totem) heals the player that much less, and a hit that was cancelled heals nothing. Devouring Frenzy's drain
 * loop (Rage.drain) does the same; this is a copy, so the frenzy is left as it is.
 * <p>
 * The damage always names the player as both the causing and the direct entity, so protection plugins, team rules and
 * the kill credit work as for a sword, and Looting on the arm applies. The caller wraps the call: in
 * {@code ArmContext.asSpecialDamage}, so it isn't billed as a melee hit or fed to the frenzy, and in
 * {@link TendrilKills#during}, so a kill is taken in wherever the creature is.
 */
public final class DrainHit {
	/** A player drain smaller than this isn't worth a hit (the frenzy's cut-off). */
	private static final double MIN_DRAIN = 0.05;

	private DrainHit() {}

	/**
	 * A blade's cut: {@code damage} of {@code type} (Draining Cut uses PLAYER_ATTACK, so armour counts as for a sword).
	 * It can kill, players too. Returns the health taken.
	 */
	public static double strike(Player owner, LivingEntity target, double damage, DamageType type) {
		if (!(damage > 0) || target.isDead() || !target.isValid()) return 0;
		DamageSource source = DamageSource.builder(type).withCausingEntity(owner).withDirectEntity(owner).build();
		return measured(target, () -> target.damage(damage, source));
	}

	/**
	 * A blade's cut as {@link #strike(Player, LivingEntity, double, DamageType)}, but a player is never taken below
	 * {@code playerFloor} (arms.player-floor): the blow shrinks to what is above the floor before armour, which can only
	 * take more off it, and nothing happens once they are there (no hit, no knockback, no shield broken). Anything that
	 * is not a player takes the whole of it.
	 */
	public static double strike(Player owner, LivingEntity target, double damage, DamageType type, double playerFloor) {
		if (target instanceof Player victim) {
			damage = cappedBlow(damage, victim.getHealth(), playerFloor);
			if (!(damage > 0)) return 0;
		}
		return strike(owner, target, damage, type);
	}

	/**
	 * The most a blow may do to a player at {@code health} with a floor of {@code playerFloor}: the blow, or what is above
	 * the floor, whichever is less; 0 when that is under {@link #MIN_DRAIN} (a hit that small is not worth the event).
	 */
	static double cappedBlow(double damage, double health, double playerFloor) {
		double hit = Math.min(damage, health - playerFloor);
		return hit < MIN_DRAIN ? 0 : hit;
	}

	/**
	 * A latched tendril's drain: generic damage (armour doesn't stop it, Protection and Resistance do), as the frenzy's.
	 * A player is never taken below {@code playerFloor}: the drain shrinks to what is above it, and nothing happens once
	 * they are there. Returns the health taken.
	 */
	public static double drain(Player owner, LivingEntity target, double amount, double playerFloor) {
		if (!(amount > 0) || target.isDead() || !target.isValid()) return 0;
		double hit = amount;
		if (target instanceof Player victim) {
			hit = Math.min(hit, victim.getHealth() - playerFloor);
			if (hit < MIN_DRAIN) return 0;
		}
		double damage = hit;
		DamageSource source = DamageSource.builder(DamageType.GENERIC).withCausingEntity(owner).withDirectEntity(owner).build();
		return measured(target, () -> target.damage(damage, source));
	}

	/** Health and absorption before against after {@code hit}; everything there was when it killed. */
	private static double measured(LivingEntity target, Runnable hit) {
		double before = target.getHealth() + target.getAbsorptionAmount();
		hit.run();
		if (target.isDead() || target.getHealth() <= 0) return before;
		return Math.max(0, before - (target.getHealth() + target.getAbsorptionAmount()));
	}
}
