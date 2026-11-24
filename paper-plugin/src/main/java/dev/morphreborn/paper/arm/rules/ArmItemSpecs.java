package dev.morphreborn.paper.arm.rules;

import dev.morphreborn.paper.tips.Lines;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The arm item's recipe (spec 3.2) for a form, tier and player situation. What changes with the situation is only the
 * durability, which shows biomass: in Survival the bar is the player's biomass against their capacity (creative players
 * on a Survival server see it too, though for them it never moves); in Classic arms are free, so the bar is hidden and
 * the item simply never takes damage.
 * <p>
 * The name and lore are fixed per form, level and mode, so the stack stays the same while it is held: any change to
 * the held item's components restarts the client's block breaking and replays the equip animation. Pure, so it is
 * unit tested.
 */
public final class ArmItemSpecs {
	/** The first custom_model_data string, which the client mod recognises arms by. */
	public static final String MODEL_TAG = "morphreborn:arm";
	/** The name's colour: the mod's tendril red. Not italic, which also keeps it out of the recipe book's autofill. */
	public static final int NAME_COLOR = 0xC62828;
	/** {@code use_cooldown} seconds: the shortest that still registers, so only {@code setCooldown} shows a sweep. */
	public static final float USE_COOLDOWN_SECONDS = 0.05f;
	/** {@code repair_cost}: anvils treat the arm as too expensive to work. */
	public static final int REPAIR_COST = 32767;
	public static final String LORE_FIRST = "Your own arm, reshaped.";

	private ArmItemSpecs() {}

	/**
	 * The recipe with the form's default Fortune and Efficiency (the level, for the pickaxe, axe, shovel and sickle).
	 *
	 * @param survival the bar shows biomass (a Survival server, creative players included); false in Classic
	 * @param specialCostMilli what the special costs now, for the lore (0: free or no special)
	 * @param specialName the special's name for the lore ("" for none)
	 */
	public static ArmItemSpec of(String form, int tier, boolean survival, long balanceMilli, long capacityMilli, long specialCostMilli, String specialName) {
		return of(form, tier, survival, balanceMilli, capacityMilli, specialCostMilli, specialName, ArmTiers.hasFortune(form) ? ArmTiers.clampTier(tier) : 0);
	}

	/**
	 * The recipe with an explicit Fortune level (abilities.yml arms.forms.<form>.fortune); ignored on forms without
	 * Fortune. Efficiency is the form's default.
	 */
	public static ArmItemSpec of(String form, int tier, boolean survival, long balanceMilli, long capacityMilli, long specialCostMilli, String specialName,
			int fortune) {
		return of(form, tier, survival, balanceMilli, capacityMilli, specialCostMilli, specialName, fortune,
				ArmTiers.hasEfficiency(form) ? ArmTiers.clampTier(tier) : 0);
	}

	/**
	 * The recipe with explicit Fortune and Efficiency levels (abilities.yml arms.forms.<form>.fortune and .efficiency);
	 * each is ignored on a form without it.
	 */
	public static ArmItemSpec of(String form, int tier, boolean survival, long balanceMilli, long capacityMilli, long specialCostMilli, String specialName,
			int fortune, int efficiency) {
		Lines special = specialName == null || specialName.isEmpty() ? Lines.EMPTY
				: Lines.builder().always(specialName).always(survival && specialCostMilli > 0 ? biomass(specialCostMilli) + " biomass" : "").build();
		return of(form, tier, survival, balanceMilli, capacityMilli, special, fortune, efficiency, true);
	}

	/**
	 * The recipe with the special's line as typed pieces and the player's Tooltips setting: with it off, the lore drops "Your own arm,
	 * reshaped." and the "F: " in front of the special, and the special's own hints (the bow's "Sneak+F: arrow tip", the whip's "(sneak)");
	 * its name and its cost stay, and so do Fortune, Efficiency, Lure and Luck of the Sea. The lore is made when the arm is formed, so a
	 * change of the setting shows on the next arm.
	 *
	 * @param special the special's name and its cost (already "biomass" worded) as clauses; empty for none
	 */
	public static ArmItemSpec of(String form, int tier, boolean survival, long balanceMilli, long capacityMilli, Lines special, int fortune, int efficiency,
			boolean tooltips) {
		if (!ArmTiers.isToolForm(form)) throw new IllegalArgumentException("Not an arm tool form: " + form);
		int t = ArmTiers.clampTier(tier);
		int fortuneLevel = ArmTiers.hasFortune(form) ? Math.clamp(fortune, 0, 255) : 0;
		int efficiencyLevel = ArmTiers.hasEfficiency(form) ? Math.clamp(efficiency, 0, 255) : 0;
		List<String> lore = new ArrayList<>();
		if (tooltips) lore.add(LORE_FIRST);
		if (special != null && special.any(tooltips)) {
			lore.add((tooltips ? "F: " : "") + special.text(tooltips));
		}
		if (fortuneLevel > 0) lore.add("Fortune " + roman(fortuneLevel));
		if (efficiencyLevel > 0) lore.add("Efficiency " + roman(efficiencyLevel));
		// the whip's rod fishes as a vanilla one, with the enchantments its level gives
		if (ArmTiers.lure(form, t) > 0) lore.add("Lure " + roman(ArmTiers.lure(form, t)));
		if (ArmTiers.luckOfTheSea(form, t) > 0) lore.add("Luck of the Sea " + roman(ArmTiers.luckOfTheSea(form, t)));
		int maxDamage, damage;
		if (survival) {
			maxDamage = ArmDurability.maxDamage(capacityMilli);
			damage = ArmDurability.damage(balanceMilli, maxDamage);
		} else {
			maxDamage = ArmDurability.MIN_MAX_DAMAGE;
			damage = 0;
		}
		return new ArmItemSpec(form, t, ArmTiers.material(form), ArmTiers.vanillaModel(form, t), ArmTiers.displayName(form), lore, fortuneLevel, efficiencyLevel,
				ArmTiers.cooldownSweep(form), ArmTiers.drawAnimation(form), maxDamage, damage, !survival, ArmTiers.extraReach(form, t));
	}

	/** {@code morphreborn:arm_<form>}: the cooldown group a special's cooldown is shown on. */
	public static String cooldownGroup(String form) {
		return "morphreborn:arm_" + form;
	}

	/** Milli as biomass for text: 15000 is "15", 12500 "12.5", 100 "0.1". */
	public static String biomass(long milli) {
		return BigDecimal.valueOf(milli).movePointLeft(3).stripTrailingZeros().toPlainString();
	}

	/** I to X as the game writes enchantment levels; higher levels as digits. */
	public static String roman(int n) {
		return switch (n) {
			case 1 -> "I";
			case 2 -> "II";
			case 3 -> "III";
			case 4 -> "IV";
			case 5 -> "V";
			case 6 -> "VI";
			case 7 -> "VII";
			case 8 -> "VIII";
			case 9 -> "IX";
			case 10 -> "X";
			default -> Integer.toString(n);
		};
	}
}
