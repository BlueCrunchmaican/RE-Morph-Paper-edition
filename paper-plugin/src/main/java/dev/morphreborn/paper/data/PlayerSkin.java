package dev.morphreborn.paper.data;

import java.util.UUID;

/** Identity of a player-type morph: whose form it is and the signed skin texture to show for it. */
public record PlayerSkin(UUID profileId, String name, String textureValue, String textureSignature) {
	public PlayerSkin {
		name = name == null ? "" : name;
		textureValue = textureValue == null ? "" : textureValue;
		textureSignature = textureSignature == null ? "" : textureSignature;
	}
}
