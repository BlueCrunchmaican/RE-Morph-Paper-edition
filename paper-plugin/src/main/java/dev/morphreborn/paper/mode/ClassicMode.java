package dev.morphreborn.paper.mode;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.data.MorphRef;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** The original Morph experience (iChun's ClassicMode): a kill unlocks the morph, everything else is always allowed. */
public final class ClassicMode implements GameplayMode {
	private final MorphRebornPlugin plugin;

	public ClassicMode(MorphRebornPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public String id() {
		return "classic";
	}

	@Override
	public void onKill(Player killer, LivingEntity victim, @Nullable MorphRef appearance) {
		if (appearance == null) return;
		if (plugin.morphs().acquire(killer, appearance) != null) plugin.morphs().playAcquisition(killer, victim, true);
	}

	@Override
	public @Nullable String cannotMorph(Player player) {
		return null;
	}

	@Override
	public @Nullable String cannotUseAbility(Player player) {
		return null;
	}

	@Override
	public double cooldownFactor(Player player) {
		return 1;
	}

	@Override
	public double transitionFactor(Player player) {
		return 1;
	}

	@Override
	public boolean mayFly(Player player) {
		return true;
	}

	@Override
	public int typeCapacity(Player player) {
		return -1;
	}

	@Override
	public int variantCapacity(Player player) {
		return -1;
	}
}
