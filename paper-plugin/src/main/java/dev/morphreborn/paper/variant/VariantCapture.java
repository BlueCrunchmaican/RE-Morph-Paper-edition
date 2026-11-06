package dev.morphreborn.paper.variant;

import com.destroystokyo.paper.profile.ProfileProperty;
import dev.morphreborn.paper.data.MorphRef;
import dev.morphreborn.paper.data.PlayerSkin;
import dev.morphreborn.paper.nbt.Snbt;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Turns a live entity into the appearance-only data that identifies its variant.
 * Equivalent to the original's {@code MorphHandler.createVariant} + {@code NbtModifier.apply} + special tag setters,
 * built on Paper's public entity snapshot API instead of NMS.
 */
public final class VariantCapture {
	/** Recessive panda genes: only visible when both genes match (from the original's panda special-case). */
	private static final Set<String> RECESSIVE_PANDA_GENES = Set.of("brown", "weak");

	private final Logger logger;
	private volatile VariantRules rules;

	public VariantCapture(VariantRules rules, Logger logger) {
		this.rules = rules;
		this.logger = logger;
	}

	public void setRules(VariantRules rules) {
		this.rules = rules;
	}

	public VariantRules rules() {
		return rules;
	}

	/** @return the appearance with an empty variant id, or null if this entity cannot be a morph. */
	public @Nullable MorphRef capture(LivingEntity living) {
		String type = living.getType().getKey().toString();
		if (rules.isDisabled(type)) return null;
		if (living instanceof Player player) {
			return new MorphRef(MorphRef.PLAYER_TYPE, "", "", skinOf(player));
		}
		if (!living.getType().isSpawnable()) return null;
		EntitySnapshot snapshot;
		try {
			snapshot = living.createSnapshot();
		} catch (RuntimeException e) {
			logger.log(Level.FINE, "No snapshot for " + type, e);
			snapshot = null;
		}
		if (snapshot == null) {
			return new MorphRef(type, "", "{}", null);
		}
		return new MorphRef(type, "", filter(type, snapshot.getAsString()), null);
	}

	/** Filters raw entity SNBT down to the canonical appearance SNBT for {@code type}. */
	public String filter(String type, String rawSnbt) {
		Map<String, Object> raw;
		try {
			raw = Snbt.parseCompound(rawSnbt);
		} catch (IllegalArgumentException e) {
			logger.log(Level.WARNING, "Could not read entity data for " + type + "; capturing it without a variant.", e);
			return "{}";
		}
		VariantRules r = rules;
		Map<String, Object> kept = new TreeMap<>();
		for (Map.Entry<String, Object> e : raw.entrySet()) {
			if (r.keeps(type, e.getKey())) kept.put(e.getKey(), e.getValue());
		}
		normaliseAge(kept, r.allowBabies);
		normalisePanda(kept);
		return Snbt.write(kept);
	}

	public static PlayerSkin skinOf(Player player) {
		String value = "";
		String signature = "";
		for (ProfileProperty p : player.getPlayerProfile().getProperties()) {
			if (p.getName().equals("textures")) {
				value = p.getValue();
				signature = p.getSignature() == null ? "" : p.getSignature();
			}
		}
		return new PlayerSkin(player.getUniqueId(), player.getName(), value, signature);
	}

	/**
	 * Babies vs adults is the only thing Age means for appearance. The original forced Age to -24000/0 so a baby
	 * morph stays a baby; AgeLocked makes that explicit for 26.3.
	 */
	private static void normaliseAge(Map<String, Object> tag, boolean allowBabies) {
		boolean baby = false;
		if (tag.get("Age") instanceof Number age) {
			baby = age.intValue() < 0;
		}
		if (tag.get("IsBaby") instanceof Number isBaby) {
			baby |= isBaby.byteValue() != 0;
		}
		tag.remove("Age");
		tag.remove("IsBaby");
		tag.remove("AgeLocked");
		if (baby && allowBabies) {
			tag.put("IsBaby", (byte) 1); // read by Zombie/Piglin/Zoglin
			tag.put("Age", -24000); // read by AgeableMob
			tag.put("AgeLocked", (byte) 1);
		}
	}

	private static void normalisePanda(Map<String, Object> tag) {
		if (!(tag.get("MainGene") instanceof String main) || !(tag.get("HiddenGene") instanceof String hidden)) return;
		String visible = RECESSIVE_PANDA_GENES.contains(main) && !main.equals(hidden) ? "normal" : main;
		tag.put("MainGene", visible);
		tag.put("HiddenGene", RECESSIVE_PANDA_GENES.contains(visible) ? visible : "normal");
	}
}
