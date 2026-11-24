package dev.morphreborn.paper.data;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A player's morph collection. Server-owned and authoritative; persisted by {@link DataStore}.
 * Variants are grouped by entity type like the original's {@code PlayerMorphData.morphs}; the player's own
 * form is implicit (it is always available and can never be removed).
 */
public final class PlayerMorphData {
	private static final String ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
	private static final SecureRandom RANDOM = new SecureRandom();

	public static final class StoredVariant {
		public String id;
		public String snbt;
		public boolean favourite;
		public @Nullable PlayerSkin skin;
		public long acquiredAt;

		public StoredVariant() {}

		StoredVariant(String id, String snbt, @Nullable PlayerSkin skin) {
			this.id = id;
			this.snbt = snbt;
			this.skin = skin;
			this.acquiredAt = System.currentTimeMillis();
		}
	}

	/**
	 * The variant a bare type name ("cow") means: the first grown-up one, else the first. A baby kept first in the
	 * collection must not be what {@code /morph combine cow wolf} quietly picks.
	 */
	public static @Nullable StoredVariant byTypeName(@Nullable List<StoredVariant> ofType) {
		if (ofType == null || ofType.isEmpty()) return null;
		for (StoredVariant v : ofType) if (!isBaby(v.snbt)) return v;
		return ofType.getFirst();
	}

	/** The look is a baby's (Age below 0, or IsBaby). */
	static boolean isBaby(@Nullable String snbt) {
		if (snbt == null || snbt.isBlank()) return false;
		try {
			Map<String, Object> tag = dev.morphreborn.paper.nbt.Snbt.parseCompound(snbt);
			return (tag.get("IsBaby") instanceof Number b && b.intValue() != 0) || (tag.get("Age") instanceof Number a && a.intValue() < 0);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	public UUID owner;
	/** entity type -> variants, in acquisition order. */
	public LinkedHashMap<String, List<StoredVariant>> morphs = new LinkedHashMap<>();
	/** entity type -> kills counted toward {@code required-kills}. */
	public Map<String, Integer> killCounts = new HashMap<>();
	/** Last selected form, restored on login when {@code persist-current-morph} is on. */
	public @Nullable MorphRef current;
	/** Multimorph: the form the current one borrows from, or null. Kept with {@link #current}. */
	public @Nullable MorphRef borrowed;
	/** Block an enderman morph is carrying (BlockData string), kept across logins. */
	public @Nullable String carriedBlock;
	/**
	 * Rage (ability/Rage): charged and waiting to be set off, when it was charged (it fades after charge-lasts), and when
	 * the wait after the last one ends (epoch millis, 0: none).
	 */
	public boolean rageCharged;
	public long rageChargedAt;
	public long rageReadyAt;
	/**
	 * The player's arm colour as their client last sent it (0xRRGGBB, C_ARM_COLOUR), so other players see it from the
	 * moment they join; null for the default red.
	 */
	public @Nullable Integer armColour;
	/**
	 * The player's arm look as their client last sent it (ArmProtocol.LOOK_*, C_ARM_COLOUR); null for the default, the
	 * tendril arm. A file from before this field loads as null.
	 */
	public @Nullable Integer armLook;
	/**
	 * How fast the pulses on the player's arm and lantern run, in percent of the usual, as their client last sent it
	 * (ArmProtocol.PULSE_SPEED_*, C_ARM_COLOUR; 2026-11-16); null for the usual speed. A file from before this
	 * field loads as null.
	 */
	public @Nullable Integer armPulseSpeed;
	/**
	 * The Tooltips setting turned OFF (decided 2026-11-17): explanation lines are left out of the descriptions the server builds for this
	 * player. Kept as "off" so a file from before the setting loads as false, which is the default, ON. A player with the client mod has it
	 * set from their settings screen (C_TOOLTIPS); a player without it, from the upgrades menu or {@code /morph tooltips}.
	 */
	public boolean tooltipsOff;
	/** Survival Mode progress; absent until the player first plays in Survival Mode, kept when the server is Classic. */
	public @Nullable SurvivalData survival;

	public SurvivalData survival() {
		if (survival == null) survival = new SurvivalData();
		return survival;
	}

	public PlayerMorphData() {}

	public PlayerMorphData(UUID owner) {
		this.owner = owner;
	}

	public @Nullable StoredVariant find(MorphRef appearance) {
		List<StoredVariant> list = morphs.get(appearance.entityType());
		if (list == null) return null;
		for (StoredVariant v : list) {
			if (toRef(appearance.entityType(), v).sameAppearance(appearance)) return v;
		}
		return null;
	}

	public @Nullable MorphRef byId(String variantId) {
		for (Map.Entry<String, List<StoredVariant>> e : morphs.entrySet()) {
			for (StoredVariant v : e.getValue()) {
				if (v.id.equals(variantId)) return toRef(e.getKey(), v);
			}
		}
		return null;
	}

	public @Nullable StoredVariant storedById(String variantId) {
		for (List<StoredVariant> list : morphs.values()) {
			for (StoredVariant v : list) {
				if (v.id.equals(variantId)) return v;
			}
		}
		return null;
	}

	/** Adds the variant if this appearance is new. Returns the stored ref, or null if it was already owned. */
	public @Nullable MorphRef add(MorphRef appearance) {
		if (find(appearance) != null) return null;
		StoredVariant stored = new StoredVariant(newId(), appearance.snbt(), appearance.skin());
		morphs.computeIfAbsent(appearance.entityType(), k -> new ArrayList<>()).add(stored);
		return toRef(appearance.entityType(), stored);
	}

	public boolean remove(String variantId) {
		for (var it = morphs.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<String, List<StoredVariant>> e = it.next();
			if (e.getValue().removeIf(v -> v.id.equals(variantId))) {
				if (e.getValue().isEmpty()) it.remove();
				return true;
			}
		}
		return false;
	}

	public int removeType(String entityType) {
		List<StoredVariant> removed = morphs.remove(entityType);
		return removed == null ? 0 : removed.size();
	}

	public int variantCount() {
		int n = 0;
		for (List<StoredVariant> list : morphs.values()) n += list.size();
		return n;
	}

	public static MorphRef toRef(String entityType, StoredVariant v) {
		return new MorphRef(entityType, v.id, v.snbt, v.skin);
	}

	private String newId() {
		while (true) {
			StringBuilder sb = new StringBuilder(8);
			for (int i = 0; i < 8; i++) sb.append(ID_CHARS.charAt(RANDOM.nextInt(ID_CHARS.length())));
			String id = sb.toString();
			if (storedById(id) == null && !id.equals(MorphRef.SELF_ID)) return id;
		}
	}
}
