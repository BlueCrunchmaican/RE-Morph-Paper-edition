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
