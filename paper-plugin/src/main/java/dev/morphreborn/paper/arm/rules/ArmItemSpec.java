package dev.morphreborn.paper.arm.rules;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Everything the arm item will be, as plain values (spec 3.2): ArmItems turns it into an ItemStack. Kept apart from
 * the stack so the recipe is unit tested without a server.
 *
 * @param form the form id
 * @param tier 1 iron, 2 diamond, 3 netherite
 * @param material the base item, a Material constant name ("IRON_SWORD")
 * @param vanillaModel the item model for players without RE:Morph's pack ("minecraft:diamond_sword")
 * @param name the custom name, shown in {@link ArmItemSpecs#NAME_COLOR}, not italic
 * @param lore the lore lines, in order
 * @param fortune the Fortune level on the item (0: none)
 * @param efficiency the Efficiency level on the item (0: none)
 * @param useCooldownGroup carries {@code use_cooldown} with the group {@link #cooldownGroup()}, so a special's
 *            cooldown shows as the vanilla sweep over the slot (ArmTiers.cooldownSweep: the katana, pickaxe and mace; on the others it would block
 *            stripping, tilling, paths, the bow's draw and the spear's charge)
 * @param drawAnimation the use animation of a form drawn on a never-completing consumable, "BOW" (an ItemUseAnimation name; the crossbow is a real crossbow since my design note of 2026-11-16), "" for none (ArmTiers.drawAnimation: only the bow now)
 * @param maxDamage {@code max_damage}
 * @param damage {@code damage}
 * @param hideDurability the tooltip hides damage and max damage (Classic, where the bar means nothing)
 * @param extraReach blocks added to {@code entity_interaction_range} while held (0: none)
 */
public record ArmItemSpec(String form, int tier, String material, String vanillaModel, String name, List<String> lore, int fortune, int efficiency,
		boolean useCooldownGroup, String drawAnimation, int maxDamage, int damage, boolean hideDurability, double extraReach) {
	public ArmItemSpec {
		lore = List.copyOf(lore);
		drawAnimation = drawAnimation == null ? "" : drawAnimation;
		extraReach = Math.max(0, extraReach);
	}

	/** This recipe with the reach the server's settings give it. */
	public ArmItemSpec withExtraReach(double blocks) {
		return new ArmItemSpec(form, tier, material, vanillaModel, name, lore, fortune, efficiency, useCooldownGroup, drawAnimation, maxDamage, damage, hideDurability,
				blocks);
	}

	/** The bow's draw (the old flag): a consumable with the BOW animation. */
	public boolean bowConsumable() {
		return drawAnimation.equals("BOW");
	}

	/** Every arm stacks to 1 (needed before an item can be damageable; set first on paper and fishing rods). */
	public int maxStackSize() {
		return 1;
	}

	/** {@code custom_model_data} strings: {@code ["morphreborn:arm", form, tier name]}. No floats or flags on the server's stack. */
	public List<String> modelStrings() {
		return List.of(ArmItemSpecs.MODEL_TAG, form, ArmTiers.tierName(tier));
	}

	/** The {@code use_cooldown} group, {@code morphreborn:arm_<form>}, or null when the item has none. */
	public @Nullable String cooldownGroup() {
		return useCooldownGroup ? ArmItemSpecs.cooldownGroup(form) : null;
	}

	/** The Lure level on the item (the whip's rod only). */
	public int lure() {
		return ArmTiers.lure(form, tier);
	}

	/** The Luck of the Sea level on the item (the whip's rod only). */
	public int luckOfTheSea() {
		return ArmTiers.luckOfTheSea(form, tier);
	}

	public boolean hasAttackModifiers() {
		return ArmTiers.hasAttackModifiers(form);
	}

	public float attackDamage() {
		return ArmTiers.attackDamage(form, tier);
	}

	public float attackSpeed() {
		return ArmTiers.attackSpeed(form, tier);
	}
}
