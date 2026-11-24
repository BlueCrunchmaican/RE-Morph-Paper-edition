package dev.morphreborn.paper.arm.special;

import dev.morphreborn.paper.arm.api.ArmContext;
import dev.morphreborn.paper.arm.rules.WhipMoves;
import org.bukkit.Bukkit;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Whether an arm special that takes hold of a creature (the whip's lash, the spear's Impale) may touch it under the world's
 * rules and the protection plugins. Moved out of WhipLash on 2026-11-16 so every such move asks the same question the same way;
 * the pure rules are WhipMoves.mayAffect and WhipMoves.teamProtects.
 */
public final class ArmTouch {
	private ArmTouch() {}

	/**
	 * A player in a world with PvP off is out, and a hit from the owner that tests the creature (damage 0, flagged as the arm's
	 * own so nothing bills or charges for it) must not be cancelled. Mobs are tested too: a claim's animals are safe from a
	 * tendril as from a sword. The test is marked as a probe, so this plugin's own listeners ignore it (a mob isn't provoked by
	 * it); another plugin's listeners can't be told apart from a real hit, so a combat-tag plugin may still count it. Players on
	 * one scoreboard team with friendly fire off are refused first: vanilla stops that hit before any event, so the test would
	 * never hear of it.
	 */
	@SuppressWarnings("removal") // every constructor of the event is marked for removal; the test hit has no other way to be made
	public static boolean mayAffect(ArmContext c, Player p, LivingEntity t) {
		boolean pvp = t.getWorld().getPVP();
		if (!WhipMoves.mayAffect(t instanceof Player, pvp, false)) return false;
		if (t instanceof Player victim && teamProtects(p, victim)) return false;
		DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(p).withDirectEntity(p).build();
		EntityDamageByEntityEvent test = new EntityDamageByEntityEvent(p, t, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 0.0);
		c.asProbeDamage(p, () -> c.plugin().getServer().getPluginManager().callEvent(test));
		return WhipMoves.mayAffect(t instanceof Player, pvp, test.isCancelled());
	}

	/** Both are on one team of the main scoreboard (or of the owner's own, if they use another) whose friendly fire is off. */
	public static boolean teamProtects(Player owner, Player victim) {
		Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
		Scoreboard own = owner.getScoreboard();
		return teamProtectsOn(main, owner, victim) || own != main && teamProtectsOn(own, owner, victim);
	}

	private static boolean teamProtectsOn(Scoreboard board, Player owner, Player victim) {
		Team a = board.getEntryTeam(owner.getName());
		Team b = board.getEntryTeam(victim.getName());
		return a != null && a.equals(b) && WhipMoves.teamProtects(true, a.allowFriendlyFire());
	}
}
