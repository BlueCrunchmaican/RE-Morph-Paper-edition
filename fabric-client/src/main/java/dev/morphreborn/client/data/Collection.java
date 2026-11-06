package dev.morphreborn.client.data;

import dev.morphreborn.client.net.MorphRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import org.jspecify.annotations.Nullable;

/**
 * The local copy of this player's morph collection, as last sent by the server, arranged like the original
 * selector: one row per entity type (player forms first, the rest by translated name), variants in acquisition order.
 * Row 0 / column 0 is always your own form.
 */
public final class Collection {
	public static final class Variant {
		public final MorphRef ref;
		public boolean favourite;

		Variant(MorphRef ref, boolean favourite) {
			this.ref = ref;
			this.favourite = favourite;
		}
	}

	public static final class Row {
		public final String entityType;
		public final List<Variant> variants = new ArrayList<>();

		Row(String entityType) {
			this.entityType = entityType;
		}

		public boolean hasFavourite() {
			for (Variant v : variants) if (v.favourite) return true;
			return false;
		}
	}

	private List<Row> rows = new ArrayList<>();
	/** Bumped whenever the server sends a new collection, so the GUI can re-validate its indices. */
	public int revision;

	public List<Row> rows() {
		return rows;
	}

	public void replace(List<Row> fromServer) {
		Minecraft mc = Minecraft.getInstance();
		Row self = new Row(MorphRef.PLAYER_TYPE);
		MorphRef.Skin skin = mc.player == null ? null
				: new MorphRef.Skin(mc.player.getUUID(), mc.player.getGameProfile().name(), "", "");
		self.variants.add(new Variant(new MorphRef(MorphRef.PLAYER_TYPE, MorphRef.SELF_ID, "", skin), false));
		List<Row> others = new ArrayList<>();
		for (Row r : fromServer) {
			if (r.entityType.equals(MorphRef.PLAYER_TYPE)) self.variants.addAll(r.variants);
			else others.add(r);
		}
		others.sort(Comparator.comparing(r -> I18n.get(translationKey(r.entityType))));
		List<Row> result = new ArrayList<>();
		result.add(self);
		result.addAll(others);
		rows = result;
		revision++;
	}

	public static Row newRow(String type) {
		return new Row(type);
	}

	public static Variant newVariant(MorphRef ref, boolean favourite) {
		return new Variant(ref, favourite);
	}

	public @Nullable Variant find(String variantId) {
		for (Row r : rows) {
			for (Variant v : r.variants) if (v.ref.variantId().equals(variantId)) return v;
		}
		return null;
	}

	public List<Variant> favourites() {
		List<Variant> out = new ArrayList<>();
		out.add(rows.isEmpty() ? null : rows.getFirst().variants.getFirst());
		for (Row r : rows) {
			for (Variant v : r.variants) if (v.favourite && !v.ref.isSelf()) out.add(v);
		}
		out.removeIf(java.util.Objects::isNull);
		return out;
	}

	public static String translationKey(String entityType) {
		int colon = entityType.indexOf(':');
		return "entity." + entityType.substring(0, colon) + "." + entityType.substring(colon + 1);
	}

	public void clear() {
		rows = new ArrayList<>();
		revision++;
	}
}
