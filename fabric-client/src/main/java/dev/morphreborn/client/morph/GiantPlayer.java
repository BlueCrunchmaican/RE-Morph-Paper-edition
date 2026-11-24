package dev.morphreborn.client.morph;

import dev.morphreborn.client.net.MorphRef;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.jspecify.annotations.Nullable;

/**
 * A giant wearing a player form: a multimorph whose main body is a giant and whose borrowed form is a player's look
 * (your own body, or another player's). It is drawn as that player, model and skin, scaled up to a giant, instead of a
 * giant with the player's head on it. My call (8.0.0): their own head on a giant pulsed in size as they looked
 * around and vanished under shaders, and what they wanted from the pair was "just making the player a giant".
 *
 * <p>Only the look changes. The physical body (hitbox, attributes), abilities, traits, the selector entry and the costs
 * stay the giant's, and nothing is borrowed onto it: no head swap, no parts. The reverse pair, a player body wearing a
 * giant's head, is an ordinary multimorph. The plugin keeps the same rule and scale, so players without the mod see the
 * same player at the same size.
 */
public final class GiantPlayer {
	public static final String GIANT_TYPE = "minecraft:giant";
	/**
	 * How much bigger than a player the player is drawn: a giant's height (EntityTypes.GIANT, 12) over a player's (1.8),
	 * the scale the server gives a giant body's hitbox (MorphManager.scaleFor), so the drawn player fills that box and its
	 * eyes are where the camera is (1.62 of it). Sized by the giant's eye height (10.44) instead, they sat 0.36 below it.
	 */
	public static final float GIANT_PLAYER_SCALE = 12F / 1.8F;

	private GiantPlayer() {}

	/** True for a giant main body wearing a player form, drawn as that player at {@link #GIANT_PLAYER_SCALE}. */
	public static boolean isGiantPlayer(@Nullable MorphRef main, @Nullable MorphRef borrowed) {
		return main != null && borrowed != null && GIANT_TYPE.equals(main.entityType()) && borrowed.isPlayer();
	}

	/**
	 * Makes {@code player}, a player morph entity kept for this alone (MorphEntities.giantPlayerForWorld), giant-sized.
	 * Through its scale attribute, as vanilla scales an entity, so its box, shadow and name tag grow with it and its
	 * render state carries the scale. Its own instance, because the same form can be drawn at its own size in the same
	 * frame: borrowed by the other body of a transformation out of the giant.
	 */
	public static void scaleUp(LivingEntity player) {
		AttributeInstance scale = player.getAttribute(Attributes.SCALE);
		if (scale == null) return;
		scale.setBaseValue(GIANT_PLAYER_SCALE);
		player.refreshDimensions();
	}
}
