package dev.morphreborn.paper.disguise;

import dev.morphreborn.paper.data.MorphRef;
import org.jspecify.annotations.Nullable;

/**
 * A giant that borrows a player form (your own body or another player's look) is drawn as that player, sized up to a
 * giant (my call, 8.0.0). Their giant wearing their own head pulsed in size as they looked about and vanished
 * under shaders, and what they asked for was the player sized up, with no zombie body or head. So for players without
 * the mod this pair has no head swap, no lent parts and no head displays: its disguise is the player form's own at
 * {@link #GIANT_PLAYER_SCALE}, and transformations melt from and to that player's boxes at that size. Everything else is
 * the giant's, as for any multimorph's main body: hitbox, attributes, abilities, traits, what is saved and what it costs.
 * The reverse, a player body borrowing a giant's head, stays a multimorph like any other. The client mod has the same
 * rule and scale, so both sides draw the same player.
 */
public final class GiantPlayer {
	public static final String GIANT = "minecraft:giant";
	/**
	 * How much bigger than a player the player is drawn: a giant's height (EntityTypes.GIANT, 12) over a player's (1.8),
	 * the scale a giant body's hitbox gets (MorphManager.scaleFor), so the model fills that box and its eyes are where
	 * the player's camera is. Well inside the scale attribute's range (Attributes.SCALE, 0.0625 to 16) and Lib's
	 * Disguises' (LivingWatcher.setScale clamps to 0 to 100). The client mod uses the same number.
	 */
	public static final double GIANT_PLAYER_SCALE = 12.0 / 1.8;
	/**
	 * A giant-sized player moves like a player at that size (my call: walking "should feel equivalent to walking
	 * normally", suggesting 4 to 6 times): 5 times a player's speed, a real giant's own (Giant movement speed 0.5 against
	 * a player's 0.1).
	 */
	public static final double GIANT_PLAYER_SPEED = 5.0;
	/**
	 * Its jump: a player's jump at that size (my call: "the equivalent to one block"). Vanilla's 0.42 peaks at 1.25
	 * blocks; this peaks at 8.35, the same times {@link #GIANT_PLAYER_SCALE} (drag 0.98 and gravity 0.08 a tick).
	 */
	public static final double GIANT_PLAYER_JUMP = 1.213;

	private GiantPlayer() {}

	/** Whether main body {@code main} borrowing {@code lent} is drawn as {@code lent}, a giant's size. */
	public static boolean applies(@Nullable MorphRef main, @Nullable MorphRef lent) {
		return main != null && lent != null && !main.isPlayer() && GIANT.equals(main.entityType()) && lent.isPlayer();
	}
}
