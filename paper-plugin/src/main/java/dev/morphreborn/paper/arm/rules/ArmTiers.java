package dev.morphreborn.paper.arm.rules;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Each arm form's vanilla numbers, by tier (1 iron, 2 diamond, 3 netherite: the Arsenal's three levels). The arm is
 * exactly as strong and as fast as the vanilla tool of its tier, never more, so anticheats see an ordinary sword or
 * pickaxe and players can judge it by what they know. The numbers are the game's own (Items.java, ToolMaterial.java in
 * 26.3): attack modifiers are the tool's baseline plus the material's bonus, without the player's own 1 damage.
 * <p>
 * Every tier uses one base item per form (an iron sword for the katana at all three tiers); the tier is expressed only
 * through components, so a re-tier after an upgrade never changes the item type and never resets the attack timer.
 * The form data lives here, without server types, so the item recipe (ArmItemSpecs) is unit tested.
 */
public final class ArmTiers {
	public static final int IRON = 1, DIAMOND = 2, NETHERITE = 3;
	/** The tool forms in the chest menu's order; the accessories (lantern, shield, spyglass, flint) are not items. */
	public static final List<String> TOOL_FORMS = List.of("katana", "pickaxe", "axe", "shovel", "sickle", "bow", "whip", "spear", "mace", "trident", "crossbow",
			"claws");

	/** The accessories, in the wheel's inner ring order (lantern first). They are not items, so they have no base, model or attack numbers. */
	public static final List<String> ACCESSORIES = List.of("lantern", "shield", "spyglass", "flint");

	/** Sword mining (ToolMaterial.applySwordProperties): cobweb at 15, these tags at their own speeds. */
	public static final String SWORD_INSTANTLY_MINES = "minecraft:sword_instantly_mines", SWORD_EFFICIENT = "minecraft:sword_efficient";
	public static final float SWORD_COBWEB_SPEED = 15f, SWORD_EFFICIENT_SPEED = 1.5f;

	/**
	 * The vanilla spear of a tier (Items.java, ToolMaterial.java in 26.3): Item.Properties.spear's ten numbers, kept per
	 * tier because the arm's spear is one base item (the iron spear) whose kinetic weapon, swing and attributes are set per
	 * level (2026-11-16: keep vanilla's jab and charge components but set per-tier numbers).
	 *
	 * @param attackDuration seconds a jab takes; the swing animation's length and, as {@code 1 / duration - 4}, the attack speed modifier
	 * @param damageMultiplier the charge's damage multiplier
	 * @param delay seconds from the start of a charge before it can hit
	 * @param dismountTime seconds, and its speed threshold, within which a charge dismounts
	 * @param knockbackTime seconds, and its speed threshold, within which a charge knocks back
	 * @param damageTime seconds, and its relative speed threshold, within which a charge damages
	 * @param attackBonus the attack damage modifier (the material's bonus, without the player's own 1)
	 * @param fireResistant the item survives fire and lava (netherite)
	 */
	public record Spear(float attackDuration, float damageMultiplier, float delay, float dismountTime, float dismountThreshold, float knockbackTime,
			float knockbackThreshold, float damageTime, float damageThreshold, float attackBonus, boolean fireResistant) {
		/** The attack speed modifier a vanilla spear of this tier carries. */
		public float attackSpeed() {
			return (float) (1.0 / attackDuration - 4.0);
		}

		/** The jab's swing length in ticks. */
		public int swingTicks() {
			return (int) (attackDuration * 20.0F);
		}
	}

	private static final Spear SPEAR_IRON = new Spear(0.95f, 0.95f, 0.6f, 2.5f, 11.0f, 6.75f, 5.1f, 11.25f, 4.6f, 2, false);
	private static final Spear SPEAR_DIAMOND = new Spear(1.05f, 1.075f, 0.5f, 3.0f, 10.0f, 6.5f, 5.1f, 10.0f, 4.6f, 3, false);
	private static final Spear SPEAR_NETHERITE = new Spear(1.15f, 1.2f, 0.4f, 2.5f, 9.0f, 5.5f, 5.1f, 8.75f, 4.6f, 4, true);

	private ArmTiers() {}

	/** The spear's vanilla numbers at a tier: the iron, diamond or netherite spear. */
	public static Spear spear(int tier) {
		return switch (clampTier(tier)) {
			case IRON -> SPEAR_IRON;
			case DIAMOND -> SPEAR_DIAMOND;
			default -> SPEAR_NETHERITE;
		};
	}

	public static int clampTier(int tier) {
		return Math.clamp(tier, IRON, NETHERITE);
	}

	/** iron, diamond or netherite: the third custom_model_data string, and the model the client draws. */
	public static String tierName(int tier) {
		return switch (clampTier(tier)) {
			case IRON -> "iron";
			case DIAMOND -> "diamond";
			default -> "netherite";
		};
	}

	/** The tier a name stands for, 0 when it is none. */
	public static int tier(@Nullable String name) {
		if (name == null) return 0;
		return switch (name.trim().toLowerCase(Locale.ROOT)) {
			case "iron" -> IRON;
			case "diamond" -> DIAMOND;
			case "netherite" -> NETHERITE;
			default -> 0;
		};
	}

	public static boolean isToolForm(@Nullable String form) {
		return form != null && TOOL_FORMS.contains(form);
	}

	/** The attack damage modifier (minecraft:base_attack_damage, main hand, add value). 0 for the bow, which has none. */
	public static float attackDamage(String form, int tier) {
		int t = clampTier(tier);
		return switch (form) {
			case "katana" -> 4 + t; // 5 / 6 / 7
			case "pickaxe" -> 2 + t; // 3 / 4 / 5
			case "axe" -> t == NETHERITE ? 9 : 8;
			case "shovel" -> 2.5f + t; // 3.5 / 4.5 / 5.5
			// the whip's left-click (2026-11-16): a bit under a sword (total 4.5 / 5.5 / 6.5 against the katana's 6 / 7 / 8),
			// since it reaches 6 to 7 blocks
			case "whip" -> 2.5f + t;
			case "spear" -> spear(t).attackBonus(); // 2 / 3 / 4, as the vanilla iron, diamond and netherite spears
			// the mace and the trident are one item in vanilla, so every tier carries the vanilla numbers: the tier makes their special
			// stronger, never the plain hit (never stronger than vanilla; anticheats see an ordinary mace or trident)
			case "mace" -> 5;
			case "trident" -> 8;
			// claws: a light, fast weapon (shears hit for 1 in vanilla); a placeholder the owner can steer: 2.5 / 3.5 / 4.5
			case "claws" -> 1.5f + t;
			default -> 0; // sickle 0 / 0 / 0; bow and crossbow none
		};
	}

	/** The attack speed modifier (minecraft:base_attack_speed). 0 for the bow, which has none. */
	public static float attackSpeed(String form, int tier) {
		int t = clampTier(tier);
		return switch (form) {
			case "katana" -> -2.4f;
			case "pickaxe" -> -2.8f;
			case "axe" -> t == IRON ? -3.1f : -3.0f;
			case "shovel" -> -3.0f;
			// between a sword (-2.4, 1.6 a second) and an axe (-3.0): 1.3 a second
			case "whip" -> -2.7f;
			case "sickle" -> t == IRON ? -1.0f : 0f;
			case "spear" -> spear(t).attackSpeed(); // 1 / duration - 4, as vanilla
			case "mace" -> -3.4f;
			case "trident" -> -2.9f;
			case "claws" -> -1.8f; // about 2.2 a second
			default -> 0;
		};
	}

	/**
	 * The reach the held arm adds, in blocks (2026-11-15: a tool longer than its vanilla one should reach
	 * further): the katana 0.5 / 0.75 / 1.0, the axe 0.2 / 0.3 / 0.4, the shovel 0.2 / 0.25 / 0.3, the sickle 0 / 0.25 /
	 * 0.5; the whip 3 / 3.5 / 4 (2026-11-16: its left-click reaches 6 / 6.5 / 7 blocks, the player's 3 plus this);
	 * none for the pickaxe and the bow.
	 */
	public static double extraReach(String form, int tier) {
		int t = clampTier(tier);
		return switch (form) {
			case "katana" -> 0.25 + 0.25 * t;
			case "axe" -> 0.1 + 0.1 * t;
			case "shovel" -> 0.15 + 0.05 * t;
			case "sickle" -> 0.25 * (t - 1);
			case "whip" -> 2.5 + 0.5 * t;
			default -> 0;
		};
	}

	/** The player's own reach to a creature (minecraft:entity_interaction_range's base), which a held arm adds to. */
	public static final double BASE_REACH = 3.0;

	/** How far the whip's left-click reaches at a tier: 6 / 6.5 / 7 blocks, with the arm's default reach (abilities.yml may change it). */
	public static double whipStrikeReach(int tier) {
		return BASE_REACH + extraReach("whip", tier);
	}

	/**
	 * The level biomass allows (2026-11-15: the bar's thirds are where an arm steps down or back up): the top
	 * third netherite (3), the middle diamond (2), the bottom iron (1). {@code current} is the band the arm is in now, for
	 * a little hysteresis: stepping back up takes 2% of the capacity past the line, so an arm sitting on a line doesn't
	 * flicker; 0 means none (a fresh arm). A capacity of 0 or less allows everything.
	 */
	public static int biomassBand(long balanceMilli, long capacityMilli, int current) {
		if (capacityMilli <= 0) return NETHERITE;
		long third = capacityMilli / 3, twoThirds = capacityMilli * 2 / 3, margin = capacityMilli / 50;
		int band = balanceMilli >= twoThirds ? NETHERITE : balanceMilli >= third ? DIAMOND : IRON;
		if (current <= 0 || band <= current) return band;
		// stepping up: only once past the line by the margin, one or two steps as far as the balance carries it
		int up = balanceMilli >= twoThirds + margin ? NETHERITE : balanceMilli >= third + margin ? DIAMOND : IRON;
		return Math.max(current, up);
	}

	/** Whether the item carries attack modifiers at all (the bow and the crossbow don't, as in vanilla; the whip does since the owner's rework, 2026-11-16). */
	public static boolean hasAttackModifiers(String form) {
		return switch (form) {
			case "katana", "pickaxe", "axe", "shovel", "sickle", "whip", "spear", "mace", "trident", "claws" -> true;
			default -> false;
		};
	}

	/** The mining speed on the tool's own blocks: 6 iron, 8 diamond, 9 netherite. */
	public static int miningSpeed(int tier) {
		return switch (clampTier(tier)) {
			case IRON -> 6;
			case DIAMOND -> 8;
			default -> 9;
		};
	}

	/** The blocks the tool mines and drops ({@code #minecraft:mineable/...}); null for the katana (sword rules), bow and whip. */
	public static @Nullable String mineableTag(String form) {
		return switch (form) {
			case "pickaxe" -> "minecraft:mineable/pickaxe";
			case "axe" -> "minecraft:mineable/axe";
			case "shovel" -> "minecraft:mineable/shovel";
			case "sickle" -> "minecraft:mineable/hoe";
			default -> null;
		};
	}

	/** The blocks the tier is too weak to drop ({@code #minecraft:incorrect_for_<tier>_tool}). */
	public static String incorrectTag(int tier) {
		return "minecraft:incorrect_for_" + tierName(tier) + "_tool";
	}

	public static boolean isSword(String form) {
		return form.equals("katana");
	}

	/** The claws carry shears' tool rules (cobweb, leaves, wool at shears' speeds), with no wear. */
	public static boolean isShears(String form) {
		return form.equals("claws");
	}

	/**
	 * Forms whose vanilla base carries a Tool component that would wear the item or refuse block breaking in creative the way a
	 * real weapon does (the mace's and the trident's {@code damage_per_block 2}): they get an empty rule set with no wear.
	 */
	public static boolean isWeaponOnly(String form) {
		return form.equals("mace") || form.equals("trident");
	}

	/**
	 * The forms whose special's cooldown shows as the vanilla sweep over the slot ({@code use_cooldown} on the item and
	 * {@code setCooldown} when the special fires): only forms with no use action of their own, since a cooldown would block
	 * stripping, tilling, paths, the bow's draw, the spear's charge, a throw or a shearing.
	 */
	public static boolean cooldownSweep(String form) {
		return switch (form) {
			case "katana", "pickaxe", "mace" -> true;
			default -> false;
		};
	}

	/**
	 * The use animation of a form whose item is paper made drawable (a consumable that never completes): "BOW" for the bow;
	 * "" for every other form. An ItemUseAnimation constant name. The crossbow is no longer one: my design note (2026-11-16)
	 * wants a real CROSSBOW that the plugin loads itself and the player only fires, so its use is the game's own (CrossbowArm).
	 */
	public static String drawAnimation(String form) {
		return switch (form) {
			case "bow" -> "BOW";
			default -> "";
		};
	}

	/**
	 * The damage types of the new weapons' own blows (a spear's jab and charge, a mace's smash), which count as melee hits for the
	 * per-hit cost whatever cause Paper reports for them (Bukkit's DamageCause has no spear or mace constant).
	 */
	public static boolean isWeaponDamageType(String key) {
		return key.equals("minecraft:spear") || key.equals("minecraft:mace_smash");
	}

	/**
	 * A blow the owner struck themself with a spear in hand, whatever cause Paper gives it (it has none of its own for a spear, and
	 * which of its causes a jab or a charge reports wasn't checked live, 2026-11-16): the damage's direct entity is the owner (so not
	 * an arrow or a thrown item), and it isn't thorns, which also names the wearer as the damager. A special's own damage never gets
	 * here (it is flagged and skipped before billing).
	 */
	public static boolean isSpearBlow(String form, boolean directIsOwner, boolean thorns) {
		return form.equals("spear") && directIsOwner && !thorns;
	}

	/**
	 * A blow the owner struck themself with a mace in hand, the smash included (my design, 2026-11-16: Bukkit has no cause for a
	 * mace smash, so the arm in hand is what says it is one of the mace's hits): as {@link #isSpearBlow}, the damage's direct entity is
	 * the owner and it isn't thorns. Billed per hit like any melee hit.
	 */
	public static boolean isMaceBlow(String form, boolean directIsOwner, boolean thorns) {
		return form.equals("mace") && directIsOwner && !thorns;
	}

	/**
	 * The forms that bill their own wear, so the guard only cancels the item's damage and doesn't also bill it as a strip,
	 * till or path (G11): the whip's line costs. A form that bills its own use (a throw, a shot) is listed here too: the trident's
	 * throw and the crossbow's shot (listed ahead of their modules, 2026-11-16, so phase 2 needs no shared edit).
	 */
	public static boolean billsOwnWear(String form) {
		return form.equals("whip") || form.equals("trident") || form.equals("crossbow");
	}

	/**
	 * What a Bedrock (Floodgate) viewer is shown in place of a form whose paper base would show as paper: the bow as a bow
	 * (a Material constant name); "" for a form whose base is a real item already, the crossbow now included (a real CROSSBOW).
	 */
	public static String bedrockItem(String form) {
		return switch (form) {
			case "bow" -> "BOW";
			default -> "";
		};
	}

	/** The forms whose item carries Fortune at their level: the pickaxe, the axe, the shovel and the sickle. */
	public static boolean hasFortune(String form) {
		return switch (form) {
			case "pickaxe", "axe", "shovel", "sickle" -> true;
			default -> false;
		};
	}

	/** The forms whose item carries Efficiency at their level: the pickaxe, the axe, the shovel and the sickle, and the claws (shears' speed on leaves, wool and cobweb). */
	public static boolean hasEfficiency(String form) {
		return switch (form) {
			case "pickaxe", "axe", "shovel", "sickle", "claws" -> true;
			default -> false;
		};
	}

	/** The Lure level the whip's rod carries: none at iron, I at diamond, II at netherite. Every other form has none. */
	public static int lure(String form, int tier) {
		return form.equals("whip") ? Math.max(0, clampTier(tier) - 1) : 0;
	}

	/** The Luck of the Sea level the whip's rod carries: none until netherite, then I. Every other form has none. */
	public static int luckOfTheSea(String form, int tier) {
		return form.equals("whip") && clampTier(tier) == NETHERITE ? 1 : 0;
	}

	/** The axe disables a shield it hits for 5 seconds, as a vanilla axe does. */
	public static float disableBlockingSeconds(String form) {
		return form.equals("axe") ? 5 : 0;
	}

	/**
	 * The base item, as a Material constant name. One per form for every tier. The bow is paper made usable with a BOW
	 * draw (a damageable bow item can't be stacked to 1 and still keep its vanilla behaviour); the whip keeps a fishing
	 * rod's cast.
	 */
	public static String material(String form) {
		return switch (form) {
			case "katana" -> "IRON_SWORD";
			case "pickaxe" -> "IRON_PICKAXE";
			case "axe" -> "IRON_AXE";
			case "shovel" -> "IRON_SHOVEL";
			case "sickle" -> "IRON_HOE";
			case "bow" -> "PAPER";
			case "whip" -> "FISHING_ROD";
			// the spear is one base for every tier (the iron spear): the tier is in its components and model, never its type
			case "spear" -> "IRON_SPEAR";
			case "mace" -> "MACE"; // the smash is item behaviour, so it needs the real mace
			case "trident" -> "TRIDENT"; // vanilla's raise-to-throw pose; the launch itself is cancelled and flown by the module
			// a real crossbow (my design note, 2026-11-16: it loads itself, a click fires): the plugin sets its charged state
			// and turns the vanilla shot into the tendril bolt (CrossbowArm), so every client and Bedrock see the loaded pose natively
			case "crossbow" -> "CROSSBOW";
			case "claws" -> "SHEARS"; // shears' use on sheep, vines and honeycomb, and loot tables that ask for shears
			default -> throw new IllegalArgumentException("Not an arm tool form: " + form);
		};
	}

	/** The vanilla item model players without RE:Morph's pack see: the tool of the tier. */
	public static String vanillaModel(String form, int tier) {
		String t = tierName(tier);
		return switch (form) {
			case "katana" -> "minecraft:" + t + "_sword";
			case "pickaxe" -> "minecraft:" + t + "_pickaxe";
			case "axe" -> "minecraft:" + t + "_axe";
			case "shovel" -> "minecraft:" + t + "_shovel";
			case "sickle" -> "minecraft:" + t + "_hoe";
			case "bow" -> "minecraft:bow";
			case "whip" -> "minecraft:fishing_rod";
			case "spear" -> "minecraft:" + t + "_spear";
			case "mace" -> "minecraft:mace";
			case "trident" -> "minecraft:trident";
			case "crossbow" -> "minecraft:crossbow";
			case "claws" -> "minecraft:shears";
			default -> throw new IllegalArgumentException("Not an arm tool form: " + form);
		};
	}

	/** "Tendril Katana": the item's name, and the upgrade's. */
	public static String displayName(String form) {
		return switch (form) {
			case "katana" -> "Tendril Katana";
			case "pickaxe" -> "Tendril Pickaxe";
			case "axe" -> "Tendril War Axe";
			case "shovel" -> "Tendril Spade";
			case "sickle" -> "Tendril Sickle";
			case "bow" -> "Tendril Recurve";
			case "whip" -> "Tendril Whip";
			case "spear" -> "Tendril Spear";
			case "mace" -> "Tendril Mace";
			case "trident" -> "Tendril Trident";
			case "crossbow" -> "Tendril Crossbow";
			case "claws" -> "Tendril Claws";
			case "lantern" -> "Tendril Lantern";
			case "shield" -> "Tendril Shield";
			case "spyglass" -> "Tendril Spyglass";
			case "flint" -> "Tendril Flint";
			default -> throw new IllegalArgumentException("Not an arm form: " + form);
		};
	}
}
