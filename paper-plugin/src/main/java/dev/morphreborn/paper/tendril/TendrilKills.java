package dev.morphreborn.paper.tendril;

import dev.morphreborn.paper.MorphRebornPlugin;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Kills made by tendrils: an arm special's cut (Draining Cut, a harpoon) taking a creature in. Such a kill is an
 * ordinary kill of the player's, so biomass and morphs come through the usual kill handling, but two things differ, as
 * for a Devouring Frenzy's kills (Rage.isRageKill, which this copies so the frenzy itself is left as it is):
 * <ul>
 * <li>it is absorbed wherever the creature is: Survival's Biomass Reach doesn't apply, since the tendril reached it
 * ({@link #isTendrilKill}, asked by SurvivalMode.onKill);</li>
 * <li>it always shows the creature being taken in. When the kill handling showed nothing (no biomass, no new morph:
 * Classic with the morph already owned, a type that can't be a morph), the absorb animation plays anyway.</li>
 * </ul>
 * Main thread only. The state is one hit deep, saved and restored around nested hits.
 */
public final class TendrilKills {
	/** The creature a tendril hit is killing right now, and whether its kill already showed it being absorbed. */
	private static @Nullable UUID absorbing;
	private static boolean absorbShown;

	private TendrilKills() {}

	/**
	 * Run {@code hit} (the damage call) as a tendril hit by {@code owner} on {@code target}. If the target dies of it and
	 * the kill handling showed no absorption, the absorb animation plays. Players are never "taken in": a player killed
	 * by a cut respawns, so only the kill handling's own effects show.
	 */
	public static void during(MorphRebornPlugin plugin, Player owner, LivingEntity target, Runnable hit) {
		UUID outerAbsorbing = absorbing;
		boolean outerShown = absorbShown;
		absorbing = target.getUniqueId();
		absorbShown = false;
		boolean shown;
		try {
			hit.run();
		} finally {
			shown = absorbShown;
			absorbing = outerAbsorbing;
			absorbShown = outerShown;
		}
		if (!shown && target.isDead() && !(target instanceof Player)) plugin.morphs().playAcquisition(owner, target, false); // nothing new: still taken in
	}

	/** True while a tendril hit is killing {@code victim}: its kill is absorbed wherever it is. */
	public static boolean isTendrilKill(Entity victim) {
		return absorbing != null && absorbing.equals(victim.getUniqueId());
	}

	/** Called for every absorption shown (MorphManager.playAcquisition): this tendril kill already showed it. */
	public static void noteAcquisition(Entity victim) {
		if (absorbing != null && absorbing.equals(victim.getUniqueId())) absorbShown = true;
	}
}
