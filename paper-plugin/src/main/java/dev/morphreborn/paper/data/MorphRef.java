package dev.morphreborn.paper.data;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One concrete form a player can take: an entity type plus the whitelisted appearance data that makes this
 * variant distinct (the original mod's {@code MorphVariant} with {@code thisVariant} set).
 *
 * @param entityType namespaced entity type, e.g. {@code minecraft:sheep}
 * @param variantId  the collection-local id of this variant, or {@link #SELF_ID} for the player's own form
 * @param snbt       canonical whitelisted SNBT (empty for players)
 * @param skin       set for player-type morphs only
 */
public record MorphRef(String entityType, String variantId, String snbt, @Nullable PlayerSkin skin) {
	public static final String PLAYER_TYPE = "minecraft:player";
	/** Mirrors the original's {@code default_player_state}: your own body. */
	public static final String SELF_ID = "self";

	public MorphRef {
		Objects.requireNonNull(entityType);
		Objects.requireNonNull(variantId);
		snbt = snbt == null ? "" : snbt;
	}

	/**
	 * Your own body as part of a multimorph (7.1.3): the main body, or the form a creature borrows from. It is a player
	 * form with your own skin, never stored in a collection, and not {@link #isSelf()}, so a combination with it is a
	 * morph like any other (disguise, hybrid model, borrowed head).
	 */
	public static final String SELF_BODY_ID = "self-body";

	public static MorphRef self(PlayerSkin skin) {
		return new MorphRef(PLAYER_TYPE, SELF_ID, "", skin);
	}

	public static MorphRef selfBody(PlayerSkin skin) {
		return new MorphRef(PLAYER_TYPE, SELF_BODY_ID, "", skin);
	}

	/** "self" or "self-body": an id that names the player's own body. */
	public static boolean ownBodyId(@Nullable String variantId) {
		return SELF_ID.equals(variantId) || SELF_BODY_ID.equals(variantId);
	}

	public boolean isPlayer() {
		return PLAYER_TYPE.equals(entityType);
	}

	public boolean isSelf() {
		return SELF_ID.equals(variantId);
	}

	public boolean isSelfBody() {
		return SELF_BODY_ID.equals(variantId);
	}

	/** Two refs describe the same appearance (ignores the collection-local id). */
	public boolean sameAppearance(MorphRef other) {
		if (!entityType.equals(other.entityType)) return false;
		if (isPlayer()) {
			UUID a = skin == null ? null : skin.profileId();
			UUID b = other.skin == null ? null : other.skin.profileId();
			return Objects.equals(a, b);
		}
		return snbt.equals(other.snbt);
	}
}
