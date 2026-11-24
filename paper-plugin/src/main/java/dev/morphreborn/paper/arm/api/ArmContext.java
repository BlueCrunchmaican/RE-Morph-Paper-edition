package dev.morphreborn.paper.arm.api;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.ArmLedger;
import java.util.concurrent.ConcurrentMap;
import org.bukkit.entity.Player;

/**
 * What an arm module (a special, an accessory, the pack look) may ask of the arm manager. Modules are loaded by class
 * name and may be missing from a build, so they never see the manager itself, only this.
 */
public interface ArmContext {
	MorphRebornPlugin plugin();

	ArmConfig config();

	/** The arm manager's tick counter. */
	int tick();

	/**
	 * The level a form works at for this player: the formed session's level when it is this form (so an admin's forced level and the
	 * tier biomass has stepped down to are what hits and pulls use), otherwise the bought level in Survival, arms.classic-level in
	 * Classic, 0 when they can't use it.
	 */
	int level(Player p, String form);

	/** The player's session nonce, 0 when no arm is formed. A special keeps it to check every later step against. */
	long token(Player p);

	/**
	 * A special's later step may go on: the player is online and alive, the session with this token is still FORMED, and
	 * the main hand still holds its arm. When false, the step stops quietly, with no refund and no further cost.
	 */
	boolean stillArmed(Player p, long token);

	/** Bill the player through the gameplay mode (Classic and creative are free); partial or all-or-nothing. */
	ArmLedger.Spend spend(Player p, long milli, boolean partial);

	/**
	 * Whether an all-or-nothing {@link #spend} of {@code milli} would go through now, without billing anything (the flint asks before it
	 * puts a placement to other plugins, 2026-11-16, so a player who cannot pay sends none).
	 */
	boolean canAfford(Player p, long milli);

	/**
	 * Give the player biomass through the gameplay mode, as a kill does (their client's bar and its gain pulse update).
	 * Survival only: a player whose arm is free (Classic, creative, arm-cost off, an admin's free arm) gains nothing.
	 * Returns what fit under their capacity, in milli (0 when nothing did).
	 */
	long gain(Player p, long milli);

	/** Run a special's hit: flagged, so it isn't billed as a melee hit, doesn't charge the frenzy and plays no attack effects. */
	void asSpecialDamage(Player p, Runnable hit);

	/** Run a special's test hit (damage 0, to ask protection plugins whether a target may be touched): flagged as a special's, and as a probe the plugin's own listeners ignore. */
	void asProbeDamage(Player p, Runnable probe);

	/** Run a special's block break: flagged, so triggers that start specials from breaks (Burrow, Fell) ignore it. */
	void asSpecialBreak(Player p, Runnable brk);

	boolean inSpecialBreak(Player p);

	/** S_ARM_FX from {@code origin} to players who draw arms within {@code radius}. */
	void fx(Player origin, ArmFxMsg msg, double radius);

	/** The viewer's client draws arms (the mod, new enough); everyone else gets the server's particle fallbacks. */
	boolean drawsArms(Player viewer);

	/** The viewer's client draws the whip's moves (S_ARM_FX kind FX_WHIP_MOVE, from ArmProtocol.WHIP_MOVES_SINCE); others get the server's particles for them. */
	boolean drawsWhipMoves(Player viewer);

	/** The viewer's client knows the new forms (8.0.0-beta.2): their FX kinds go only to such clients, and the accessory lists in S_ARM and S_ARSENAL. Others get particles. */
	boolean drawsNewForms(Player viewer);

	/** Whether the player has this accessory (an ArmForm.ACCESSORIES id) on now. */
	boolean accessoryOn(Player p, String id);

	/**
	 * An accessory's own state changed while it stays on (the shield raised or lowered, the spyglass zooming): S_ARM is sent
	 * again so every client sees it (ArmAccessoryModule.arg). Nothing when the player has no accessory on.
	 */
	void accessoryChanged(Player p);

	/** A line in the console when arms.report is on. */
	void report(String line);

	/** Armed players' arms by entity id, for the pack look's packet rewriter (read on netty threads). */
	ConcurrentMap<Integer, ArmView> views();

	/**
	 * The loaded state of a tucked crossbow changed (its bolt finished regrowing while a screen was open, 2026-11-16): the stand-in other
	 * players are shown for the tucked arm is charged or emptied to match and sent to everyone tracking the player. Nothing when the arm
	 * isn't tucked or isn't the crossbow, or the stand-in already shows it.
	 */
	void tuckedLoaded(Player p, boolean loaded);

	/** Send the player their S_ARSENAL again (a cooldown started, a level changed). */
	void refreshArsenal(Player p);
}
