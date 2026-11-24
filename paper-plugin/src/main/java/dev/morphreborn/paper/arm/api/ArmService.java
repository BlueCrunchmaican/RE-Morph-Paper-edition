package dev.morphreborn.paper.arm.api;

import dev.morphreborn.paper.tips.Lines;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Arm forms (the Arsenal) as the rest of the plugin sees them: the player's own arm reshaped into tools and weapons of
 * morph tendrils. Everything outside the arm package talks to arms only through this, from {@code plugin.arms()}, so the
 * plugin builds and runs the same whether or not the arm code is in the jar: {@link #NONE} answers every call with
 * "no arms" and does nothing (ArmModules falls back to it when ArmManager is missing or fails to load).
 */
public interface ArmService {
	/** No arm forms: every question says no, every call does nothing. */
	ArmService NONE = new ArmService() {};

	/** The server offers arm forms (abilities.yml arms.enabled); told to the client mod in S_HELLO. */
	default boolean enabled() {
		return false;
	}

	/** Start up: the server's arms: section, survival.yml's arm-cost:, and the bundled arms: section to fill gaps. */
	default void enable(@Nullable ConfigurationSection arms, @Nullable ConfigurationSection armCost, @Nullable ConfigurationSection bundledArms) {}

	/** /morph reload: retracts every arm (why MODE_OR_RELOAD) first, then reads the settings again. */
	default void reload(@Nullable ConfigurationSection arms, @Nullable ConfigurationSection armCost, @Nullable ConfigurationSection bundledArms) {}

	/** Shutdown: every arm retracted, every stash back in its slot or kept for the next join. */
	default void disable() {}

	/** Once a tick, from the plugin's tick loop. */
	default void tick() {}

	/** The gameplay mode is about to switch: retract every arm and switch every accessory off while the old mode still bills. */
	default void beforeModeSwitch() {}

	/** An online player at enable time (/reload): repair what a previous run may have left (strays, a saved session). */
	default void reconcile(Player p) {}

	/** S_ARSENAL to {@code viewer}, and S_ARM of every armed or lit player in the viewer's world. */
	default void sync(Player viewer) {}

	/** C_ARM from a client: ArmProtocol.ACTION_* and, for a FORM, the form id. */
	default void request(Player p, int action, String form) {}

	/**
	 * C_ARM with its step: {@link dev.morphreborn.paper.arm.ArmProtocol#ACTION_ACCESSORY_MODE} carries one (+1 or -1), and
	 * {@link dev.morphreborn.paper.arm.ArmProtocol#ACTION_ACCESSORY_HOLD} a held key's 1 or 0; every other action 0. Defaults to the request without it.
	 */
	default void request(Player p, int action, String form, int step) {
		request(p, action, form);
	}

	/** A morph is starting: arms are for the player's own body only, so it retracts at once. */
	default void onMorphStart(Player p) {}

	/** A transformation ended with the player back in their plain own body: the arm form bound to the held slot forms again (2026-11-17). */
	default void onOwnBody(Player p) {}

	/** A Survival upgrade was bought: a formed arm takes its new tier in place. */
	default void onUpgrade(Player p, String upgradeId) {}

	/** The player's Survival progress is being reset. */
	default void onSurvivalReset(Player p) {}

	/** The {@code {arm}} value of an arm upgrade at a level, for the upgrade tree and chest menu. */
	default Lines describe(String upgradeId, int level) {
		return Lines.EMPTY;
	}

	/** Whether an arm upgrade's code is in this build ("arm_shaping" always is while arms are enabled); unbuilt ones are hidden. */
	default boolean isBuilt(String upgradeId) {
		return false;
	}

	/** A hit dealt by an arm special: not billed as a melee hit, never charges the frenzy, no attack effects. */
	default boolean isSpecialHit(EntityDamageEvent e) {
		return false;
	}

	/**
	 * A hit that is only a test (a special asking whether the target may be touched, damage 0): the event goes out so protection
	 * plugins can veto it, but nothing in this plugin may react to it as a real hit (provoke the mob, bill, charge). Other
	 * plugins' listeners can't be told, so a combat-tag plugin may still see it.
	 */
	default boolean isProbeHit(EntityDamageEvent e) {
		return false;
	}

	/** The stack is an arm (carries the arm tag), whoever's it is. */
	default boolean isArm(@Nullable ItemStack stack) {
		return false;
	}

	/**
	 * The player's arm is formed but tucked away while they have an inventory open (2026-11-16): their hand holds
	 * its real item, yet other players are still shown the arm there.
	 */
	default boolean isTucked(Player p) {
		return false;
	}

	/** What the player's arm and accessories drain now, milli a minute (0: nothing drains), for S_SURVIVAL. */
	default long drainMilliPerMinute(Player p) {
		return 0;
	}

	/** /morph arm ...: {@code args} are the words after "arm". False when arms didn't handle it. */
	default boolean command(CommandSender sender, String[] args) {
		return false;
	}

	default List<String> tabComplete(CommandSender sender, String[] args) {
		return List.of();
	}
}
