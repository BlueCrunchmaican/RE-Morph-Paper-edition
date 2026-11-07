package dev.morphreborn.paper.mode;

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
		return true;
	}

	/** The player joined, or their client finished its handshake. */
	default void onJoin(Player player) {}

	default void reload() {}
}
