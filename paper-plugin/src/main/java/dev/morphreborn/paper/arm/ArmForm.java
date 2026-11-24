package dev.morphreborn.paper.arm;

import dev.morphreborn.paper.arm.rules.ArmTiers;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

/**
 * The arm forms (spec 3.1): twelve tools the arm reshapes into, held in the selected hotbar slot, and four accessories
 * that are never an item: the lantern, and (2026-11-16) the shield, the spyglass and flint and steel, several
 * of which may be on at once. The numbers behind each tool (base item, vanilla model, attack values) are in
 * {@link ArmTiers}, which the pure item recipe shares; this enum adds the server types.
 * <p>
 * A form whose module class is missing from the build counts as not built and is hidden everywhere, tools and
 * accessories alike ({@link ArmModules}): the spear, mace, trident, crossbow, claws, shield, spyglass and flint exist
 * here from 8.0.0-beta.2 so every shared table knows them, and show up as each one's module arrives.
 */
public enum ArmForm {
	KATANA("katana", Kind.TOOL, "arm_katana"),
	PICKAXE("pickaxe", Kind.TOOL, "arm_pickaxe"),
	AXE("axe", Kind.TOOL, "arm_axe"),
	SHOVEL("shovel", Kind.TOOL, "arm_shovel"),
	SICKLE("sickle", Kind.TOOL, "arm_sickle"),
	BOW("bow", Kind.TOOL, "arm_bow"),
	WHIP("whip", Kind.TOOL, "arm_whip"),
	// The new tool forms (2026-11-16; 8.0.0-beta.2): hidden until their special module exists
	SPEAR("spear", Kind.TOOL, "arm_spear"),
	MACE("mace", Kind.TOOL, "arm_mace"),
	TRIDENT("trident", Kind.TOOL, "arm_trident"),
	CROSSBOW("crossbow", Kind.TOOL, "arm_crossbow"),
	CLAWS("claws", Kind.TOOL, "arm_claws"),
	LANTERN("lantern", Kind.ACCESSORY, "arm_lantern"),
	// The new accessories (2026-11-16): hidden until their accessory module exists
	SHIELD("shield", Kind.ACCESSORY, "arm_shield"),
	SPYGLASS("spyglass", Kind.ACCESSORY, "arm_spyglass"),
	FLINT("flint", Kind.ACCESSORY, "arm_flint");

	/** A tool takes the hand; an accessory (the lantern, shield, spyglass, flint and steel) is on alongside one, or a bare hand. */
	public enum Kind { TOOL, ACCESSORY }

	/** The upgrade that opens the Arsenal: under Morph Ability, the parent of every arm_* upgrade. */
	public static final String ARM_SHAPING = "arm_shaping";
	/** The tool forms in the wheel's order, clockwise from the top; S_ARSENAL lists built forms in this order. */
	public static final List<ArmForm> WHEEL = List.of(KATANA, AXE, BOW, WHIP, SICKLE, SHOVEL, PICKAXE, SPEAR, MACE, TRIDENT, CROSSBOW, CLAWS);
	/**
	 * The accessories in the wheel's inner ring, lantern first (it keeps its place; the others are spaced round the ring with
	 * it). S_ARSENAL and S_ARM list accessories in this order.
	 */
	public static final List<ArmForm> ACCESSORIES = List.of(LANTERN, SHIELD, SPYGLASS, FLINT);

	private final String id;
	private final Kind kind;
	private final String upgradeId;

	ArmForm(String id, Kind kind, String upgradeId) {
		this.id = id;
		this.kind = kind;
		this.upgradeId = upgradeId;
	}

	/** The id in settings, commands, permissions (morphreborn.arm.<id>) and the protocol. */
	public String id() {
		return id;
	}

	public Kind kind() {
		return kind;
	}

	public boolean tool() {
		return kind == Kind.TOOL;
	}

	/** The base item for every tier, null for an accessory (no item). */
	public @Nullable Material base() {
		return tool() ? Material.valueOf(ArmTiers.material(id)) : null;
	}

	/** The vanilla item model of a tier (1 iron, 2 diamond, 3 netherite), "" for an accessory. */
	public String vanillaModel(int tier) {
		return tool() ? ArmTiers.vanillaModel(id, tier) : "";
	}

	/** Its Survival upgrade (arm_katana); its level is the form's level. */
	public String upgradeId() {
		return upgradeId;
	}

	/** Its item carries Fortune at its level (the pickaxe, axe, shovel and sickle). */
	public boolean fortune() {
		return ArmTiers.hasFortune(id);
	}

	/** Its item carries Efficiency at its level (the pickaxe, axe, shovel and sickle). */
	public boolean efficiency() {
		return ArmTiers.hasEfficiency(id);
	}

	/** "Tendril Katana". */
	public String displayName() {
		return ArmTiers.displayName(id);
	}

	/** The permission beside morphreborn.arm that this form needs. */
	public String permission() {
		return "morphreborn.arm." + id;
	}

	/** The form with this id (any case), or null. */
	public static @Nullable ArmForm of(@Nullable String id) {
		if (id == null) return null;
		String s = id.trim().toLowerCase(Locale.ROOT);
		for (ArmForm f : values()) {
			if (f.id.equals(s)) return f;
		}
		return null;
	}

	/** The form an arm_* upgrade belongs to, or null (arm_shaping, or not an arm upgrade). */
	public static @Nullable ArmForm byUpgrade(@Nullable String upgradeId) {
		if (upgradeId == null) return null;
		for (ArmForm f : values()) {
			if (f.upgradeId.equals(upgradeId)) return f;
		}
		return null;
	}
}
