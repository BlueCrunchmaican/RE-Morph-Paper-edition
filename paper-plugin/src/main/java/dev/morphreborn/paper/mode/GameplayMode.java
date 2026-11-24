package dev.morphreborn.paper.mode;

import dev.morphreborn.paper.arm.rules.ArmLedger;
import dev.morphreborn.paper.data.MorphRef;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The rules that differ between Classic and Survival Mode (iChun's MorphMode). Everything else (morphing,
 * abilities, disguises, client rendering) is shared and only asks these questions. Set by {@code gameplay-mode} in
 * config.yml, at startup.
 */
public interface GameplayMode {
	/** "classic" or "survival", as in config.yml and the client handshake. */
	String id();

	/**
	 * {@code killer} killed {@code victim}. {@code appearance} is the morph it would unlock, or null when it can't be a
	 * morph (disabled type, the killer's own form...).
	 */
	void onKill(Player killer, LivingEntity victim, @Nullable MorphRef appearance);

	/** Why the player can't morph into anything right now, or null if they can. Returning to their own body is always allowed. */
	@Nullable String cannotMorph(Player player);

	/** Why the player can't use morph abilities (ability key, enderman carrying), or null if they can. */
	@Nullable String cannotUseAbility(Player player);

	/** Multiplies ability cooldowns. */
	double cooldownFactor(Player player);

	/** Multiplies the transformation length. */
	double transitionFactor(Player player);

	/** Whether a flying morph may fly. If not, it glides down slowly instead. */
	boolean mayFly(Player player);

	/**
	 * Whether the player's form may use a beneficial trait: an abilities.yml passive name ({@code fire-immunity}) or
	 * {@code effect:<name>} for one of its constant effects. Classic: always. Survival: once its unlock is bought.
	 * Drawbacks (sunburn, Healing that hurts...) are never asked about; they're always on.
	 */
	default boolean hasTrait(Player player, String trait) {
		return true;
	}

	/** How many mob types a collection may hold before kills stop adding new ones; -1 for no limit. */
	int typeCapacity(Player player);

	/** How many looks of one mob type a collection may hold; -1 for no limit (config.yml's cap still applies). */
	int variantCapacity(Player player);

	/** Biomass a morph into {@code form} costs this player now; 0 when morphing is free (Classic). */
	default int morphCost(Player player, MorphRef form) {
		return 0;
	}

	/** Take the price of a morph into {@code form}; false when the player can't afford it (nothing is taken). */
	default boolean payForMorph(Player player, MorphRef form) {
		return payBiomass(player, morphCost(player, form));
	}

	/** Why the player can't combine two morphs (Survival: the Multimorph upgrade isn't bought), or null if they can. */
	default @Nullable String cannotCombine(Player player) {
		return null;
	}

	/** The extra on a multimorph's price of {@code cost} (Survival: a share of it), 0 when combining is free. */
	default int multimorphExtra(Player player, int cost) {
		return 0;
	}

	/** Take {@code cost} biomass (a multimorph's prices together); false when the player can't afford it (nothing is taken). */
	default boolean payBiomass(Player player, int cost) {
		return true;
	}

	/**
	 * The level of an arm form ("katana") the player has bought (Survival: its arm_* upgrade, once Arm Shaping is
	 * bought); 0 here. Classic's level (abilities.yml arms.classic-level) and creative's minimum are applied by the arm
	 * code itself, so this only ever answers what was bought.
	 */
	default int armLevel(Player player, String form) {
		return 0;
	}

	/** The biomass arm forms spend, in milli (thousandths); {@link Long#MAX_VALUE} when the mode has none (arms are free). */
	default long armBalanceMilli(Player player) {
		return Long.MAX_VALUE;
	}

	/**
	 * Bill an arm form's use: all or nothing ({@code partial} false: specials, bow bolts) or whatever the biomass covers.
	 * Free here. Arm code always bills through the mode, after taking creative players out (free, as vanilla's infinite
	 * materials), so a mode decides only for survival and adventure players.
	 */
	default ArmLedger.Spend armSpend(Player player, long milli, boolean partial) {
		return ArmLedger.Spend.free(Long.MAX_VALUE);
	}

	/**
	 * Give an arm form's owner biomass, in milli, as a kill does (the bar and its gain pulse update); returns what fit.
	 * Nothing here: only Survival has biomass.
	 */
	default long armGain(Player player, long milli) {
		return 0;
	}

	/** Arm forms cost this player nothing now: no biomass bar, no drain, no melt. Always, here. */
	default boolean armsFree(Player player) {
		return true;
	}

	/** The player joined, or their client finished its handshake. */
	default void onJoin(Player player) {}

	default void reload() {}
}
