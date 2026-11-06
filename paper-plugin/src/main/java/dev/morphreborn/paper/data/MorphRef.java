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

	public static MorphRef self(PlayerSkin skin) {
		return new MorphRef(PLAYER_TYPE, SELF_ID, "", skin);
	}

	public boolean isPlayer() {
		return PLAYER_TYPE.equals(entityType);
	}

	public boolean isSelf() {
		return SELF_ID.equals(variantId);
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
