package dev.morphreborn.client.net;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Client mirror of the server's MorphRef: one concrete form (entity type + appearance data). */
public record MorphRef(String entityType, String variantId, String snbt, @Nullable Skin skin) {
	public static final String PLAYER_TYPE = "minecraft:player";
	public static final String SELF_ID = "self";

	public record Skin(UUID profileId, String name, String textureValue, String textureSignature) {}

	public boolean isPlayer() {
		return PLAYER_TYPE.equals(entityType);
	}

	public boolean isSelf() {
		return SELF_ID.equals(variantId);
	}

	/** Cache key for entity instances: same appearance, same entity. */
	public String appearanceKey() {
		if (isPlayer()) return entityType + "|" + (skin == null ? "?" : skin.profileId());
		return entityType + "|" + snbt;
	}

	public boolean sameAppearance(@Nullable MorphRef other) {
		return other != null && appearanceKey().equals(other.appearanceKey());
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof MorphRef r && r.variantId.equals(variantId) && r.appearanceKey().equals(appearanceKey());
	}

	@Override
	public int hashCode() {
		return Objects.hash(variantId, appearanceKey());
	}
}
