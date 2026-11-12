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
 * selector: one row per entity type (player forms first, the rest by translated name). In a row, adults come before
 * babies and bigger before smaller (slimes, magma cubes, sulfur cubes); otherwise variants are in acquisition order.
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
		/** The creature comes in more than one look, so the look limit means something for it (servers from 7.2.0 say). */
		public boolean hasLooks;

		Row(String entityType) {
			this.entityType = entityType;
		}

		/** Looks the collection holds of this creature, as the server counts them (your own body isn't one). */
		public int looksHeld() {
			int held = 0;
			for (Variant v : variants) if (!v.ref.isSelf()) held++;
			return held;
		}

		public boolean hasFavourite() {
			for (Variant v : variants) if (v.favourite) return true;
			return false;
		}
	}

	private List<Row> rows = new ArrayList<>();
	/** Bumped whenever the server sends a new collection, so the GUI can re-validate its indices. */
	public int revision;
	/** Kinds of creature in the collection, as the server counts them against {@link #kindLimit}. */
	public int kinds;
	/** The most kinds of creature (Survival's Morph Capacity) and looks of one the collection may hold; -1: no limit, or a server before 7.2.0. */
	public int kindLimit = -1;
	public int lookLimit = -1;

	public List<Row> rows() {
		return rows;
	}

	/** The server's collection with its limits ({@code -1}: none). */
	public void replace(List<Row> fromServer, int kinds, int kindLimit, int lookLimit) {
		this.kinds = kinds;
		this.kindLimit = kindLimit;
		this.lookLimit = lookLimit;
		replace(fromServer);
	}

	private void replace(List<Row> fromServer) {
		Minecraft mc = Minecraft.getInstance();
		Row self = new Row(MorphRef.PLAYER_TYPE);
		MorphRef.Skin skin = mc.player == null ? null
				: new MorphRef.Skin(mc.player.getUUID(), mc.player.getGameProfile().name(), "", "");
		self.variants.add(new Variant(new MorphRef(MorphRef.PLAYER_TYPE, MorphRef.SELF_ID, "", skin), false));
		List<Row> others = new ArrayList<>();
		for (Row r : fromServer) {
			if (r.entityType.equals(MorphRef.PLAYER_TYPE)) {
				self.variants.addAll(r.variants);
				self.hasLooks = r.hasLooks; // other players' looks share your own row
			} else {
				r.variants.sort(VARIANT_ORDER); // stable: ties keep the order they were collected in
				others.add(r);
			}
		}
		others.sort(Comparator.comparing(r -> I18n.get(translationKey(r.entityType))));
		List<Row> result = new ArrayList<>();
		result.add(self);
		result.addAll(others);
		rows = result;
		revision++;
	}

	private static final java.util.regex.Pattern SIZE = java.util.regex.Pattern.compile("(?<![A-Za-z])Size:(\\d+)");
	private static final java.util.regex.Pattern BABY = java.util.regex.Pattern.compile("IsBaby:1b|(?<![A-Za-z])Age:-\\d");

	/**
	 * Adults before babies, then the biggest first (a slime's Size; none is the smallest). The user saw slimes in the
	 * order they were killed: tiny, big, medium.
	 */
	private static final Comparator<Variant> VARIANT_ORDER = Comparator.<Variant>comparingInt(v -> BABY.matcher(v.ref.snbt()).find() ? 1 : 0)
			.thenComparingInt(v -> {
				java.util.regex.Matcher m = SIZE.matcher(v.ref.snbt());
				return m.find() ? -Integer.parseInt(m.group(1)) : 0;
			});

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
		kinds = 0;
		kindLimit = lookLimit = -1;
		revision++;
	}
}
