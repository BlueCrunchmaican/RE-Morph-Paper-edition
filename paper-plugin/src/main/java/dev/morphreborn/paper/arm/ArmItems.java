package dev.morphreborn.paper.arm;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.arm.rules.ArmItemSpec;
import dev.morphreborn.paper.arm.rules.ArmItemSpecs;
import dev.morphreborn.paper.arm.rules.ArmTiers;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.AttackRange;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.DamageResistant;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.KineticWeapon;
import io.papermc.paper.datacomponent.item.PiercingWeapon;
import io.papermc.paper.datacomponent.item.SwingAnimation;
import io.papermc.paper.datacomponent.item.Tool;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import io.papermc.paper.datacomponent.item.UseCooldown;
import io.papermc.paper.datacomponent.item.UseEffects;
import io.papermc.paper.datacomponent.item.Weapon;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.keys.BlockTypeKeys;
import io.papermc.paper.registry.keys.tags.BlockTypeTagKeys;
import io.papermc.paper.registry.keys.tags.DamageTypeTagKeys;
import io.papermc.paper.registry.set.RegistryKeySet;
import io.papermc.paper.registry.set.RegistrySet;
import io.papermc.paper.registry.tag.TagKey;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.util.TriState;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.BlockType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/**
 * The arm as an item stack: built from its recipe ({@link ArmItemSpec}, spec 3.2), recognised by its tag, and
 * re-written only in its durability, which shows the player's biomass.
 * <p>
 * What the stack carries matters for three reasons. Players without the mod see it as the plain vanilla tool of its
 * tier (the item model), so it must behave exactly like one: the tool and weapon components are the vanilla tier's,
 * except that neither ever damages the item ({@code damage_per_block 0}, {@code item_damage_per_attack 0}), because
 * biomass, not wear, is what an arm spends. The base item stays the same for every tier (an iron sword for the katana),
 * since a change of item type would reset the attack timer and play the equip animation. And the item's persistent data
 * goes to clients (and back from creative ones), so it holds only the form and the session nonce, never the stashed
 * item: a copy of an arm is worth nothing, and any arm whose nonce isn't a live session's is deleted on sight.
 */
final class ArmItems {
	/** The arm tag: the form id. Its presence is what makes a stack an arm. */
	static final NamespacedKey FORM = MorphRebornPlugin.key("arm");
	static final NamespacedKey NONCE = MorphRebornPlugin.key("arm_nonce");
	/** The vanilla ids of a tool's attribute modifiers, so the tooltip reads like a real tool's. */
	private static final NamespacedKey BASE_DAMAGE = NamespacedKey.minecraft("base_attack_damage");
	private static final NamespacedKey BASE_SPEED = NamespacedKey.minecraft("base_attack_speed");
	/** The arm's own reach modifier (a longer blade reaches further). */
	private static final NamespacedKey ARM_REACH = new NamespacedKey("morphreborn", "arm_reach");
	/** Every arm's base item: a quick check before reading a stack's persistent data. */
	private static final Set<Material> BASES = bases();
	/** The bow's draw: an hour, so the use never completes and the paper is never "eaten" (the crossbow is a real one now, loaded by CrossbowArm). */
	private static final float BOW_DRAW_SECONDS = 3600;
	/**
	 * Components a real vanilla base brings that would misbehave on an arm, taken off every stack before the form's own are
	 * set: nothing blocks attacks, nothing starts loaded, nothing is fire-proof or rare unless a form says so below (the
	 * spear's netherite tier sets {@code damage_resistant}). Unsetting one the base never had changes nothing.
	 */
	private static final List<DataComponentType> STRIPPED = List.of(DataComponentTypes.BLOCKS_ATTACKS, DataComponentTypes.CHARGED_PROJECTILES,
			DataComponentTypes.DAMAGE_RESISTANT, DataComponentTypes.RARITY);

	private ArmItems() {}

	private static Set<Material> bases() {
		Set<Material> out = EnumSet.noneOf(Material.class);
		for (String form : ArmTiers.TOOL_FORMS) out.add(Material.valueOf(ArmTiers.material(form)));
		return out;
	}

	/** The stack carries the arm tag, whoever's arm it is. */
	static boolean isArm(@Nullable ItemStack stack) {
		if (stack == null || stack.isEmpty() || !BASES.contains(stack.getType())) return false;
		return stack.getPersistentDataContainer().has(FORM);
	}

	/** The arm's nonce, 0 when the stack isn't an arm or has none. */
	static long nonceOf(@Nullable ItemStack stack) {
		if (!isArm(stack)) return 0;
		try {
			Long nonce = stack.getPersistentDataContainer().get(NONCE, PersistentDataType.LONG);
			return nonce == null ? 0 : nonce;
		} catch (IllegalArgumentException e) {
			return 0;
		}
	}

	/** The arm's form id, or null. */
	static @Nullable String formOf(@Nullable ItemStack stack) {
		if (!isArm(stack)) return null;
		try {
			return stack.getPersistentDataContainer().get(FORM, PersistentDataType.STRING);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * A new arm stack from its recipe.
	 *
	 * @param nonce the session's nonce
	 * @param itemModel the item model to show: the vanilla tool of the tier, or the pack's arm model (phase 2)
	 */
	static ItemStack build(ArmItemSpec spec, long nonce, String itemModel) {
		ItemStack item = ItemStack.of(Material.valueOf(spec.material()));
		// A damageable item must stack to 1, and paper and fishing rods are checked before max_damage is accepted.
		item.setData(DataComponentTypes.MAX_STACK_SIZE, spec.maxStackSize());
		// A custom name (not italic) also keeps the arm out of the recipe book's autofill.
		item.setData(DataComponentTypes.CUSTOM_NAME, Component.text(spec.name(), TextColor.color(ArmItemSpecs.NAME_COLOR)).decoration(TextDecoration.ITALIC, false));
		List<Component> lore = new ArrayList<>();
		for (String line : spec.lore()) lore.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
		item.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
		item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addStrings(spec.modelStrings()));
		item.setData(DataComponentTypes.ITEM_MODEL, Key.key(itemModel));
		for (DataComponentType stripped : STRIPPED) item.unsetData(stripped);
		if (spec.form().equals("spear")) spearComponents(item, spec.tier());

		Tool tool = tool(spec.form(), spec.tier());
		if (tool != null) item.setData(DataComponentTypes.TOOL, tool);
		else item.unsetData(DataComponentTypes.TOOL);
		ItemAttributeModifiers.Builder modifiers = ItemAttributeModifiers.itemAttributes();
		boolean anyModifier = false;
		if (spec.hasAttackModifiers()) {
			item.setData(DataComponentTypes.WEAPON, Weapon.weapon().itemDamagePerAttack(0).disableBlockingForSeconds(ArmTiers.disableBlockingSeconds(spec.form())));
			modifiers.addModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(BASE_DAMAGE, spec.attackDamage(), AttributeModifier.Operation.ADD_NUMBER,
							EquipmentSlotGroup.MAINHAND))
					.addModifier(Attribute.ATTACK_SPEED, new AttributeModifier(BASE_SPEED, spec.attackSpeed(), AttributeModifier.Operation.ADD_NUMBER,
							EquipmentSlotGroup.MAINHAND));
			anyModifier = true;
		} else {
			item.unsetData(DataComponentTypes.WEAPON);
		}
		if (spec.extraReach() > 0) {
			modifiers.addModifier(Attribute.ENTITY_INTERACTION_RANGE, new AttributeModifier(ARM_REACH, spec.extraReach(), AttributeModifier.Operation.ADD_NUMBER,
					EquipmentSlotGroup.MAINHAND));
			anyModifier = true;
		}
		if (anyModifier) item.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, modifiers);
		else item.unsetData(DataComponentTypes.ATTRIBUTE_MODIFIERS);

		Set<DataComponentType> hidden = new java.util.HashSet<>();
		if (spec.fortune() > 0 || spec.efficiency() > 0 || spec.lure() > 0 || spec.luckOfTheSea() > 0) {
			// real enchantments, so vanilla loot tables, fishing and Player#breakBlock apply them; hidden, and without the glint
			ItemEnchantments.Builder enchants = ItemEnchantments.itemEnchantments();
			if (spec.fortune() > 0) enchants.add(Enchantment.FORTUNE, spec.fortune());
			if (spec.efficiency() > 0) enchants.add(Enchantment.EFFICIENCY, spec.efficiency());
			if (spec.lure() > 0) enchants.add(Enchantment.LURE, spec.lure());
			if (spec.luckOfTheSea() > 0) enchants.add(Enchantment.LUCK_OF_THE_SEA, spec.luckOfTheSea());
			item.setData(DataComponentTypes.ENCHANTMENTS, enchants);
			hidden.add(DataComponentTypes.ENCHANTMENTS);
		} else {
			item.unsetData(DataComponentTypes.ENCHANTMENTS);
		}
		item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false);
		String group = spec.cooldownGroup();
		if (group != null) {
			item.setData(DataComponentTypes.USE_COOLDOWN, UseCooldown.useCooldown(ArmItemSpecs.USE_COOLDOWN_SECONDS).cooldownGroup(Key.key(group)));
		}
		if (!spec.drawAnimation().isEmpty()) {
			item.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable().consumeSeconds(BOW_DRAW_SECONDS).animation(ItemUseAnimation.valueOf(spec.drawAnimation()))
					.sound(Key.key("minecraft:intentionally_empty")).hasConsumeParticles(false));
		}

		item.setData(DataComponentTypes.MAX_DAMAGE, spec.maxDamage());
		item.setData(DataComponentTypes.DAMAGE, spec.damage());
		if (spec.hideDurability()) {
			hidden.add(DataComponentTypes.DAMAGE);
			hidden.add(DataComponentTypes.MAX_DAMAGE);
		}
		if (!hidden.isEmpty()) item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hiddenComponents(hidden));
		// never mended, repaired, enchanted or worked on an anvil
		item.unsetData(DataComponentTypes.REPAIRABLE);
		item.unsetData(DataComponentTypes.ENCHANTABLE);
		item.setData(DataComponentTypes.REPAIR_COST, ArmItemSpecs.REPAIR_COST);

		item.editPersistentDataContainer(pdc -> {
			pdc.set(FORM, PersistentDataType.STRING, spec.form());
			pdc.set(NONCE, PersistentDataType.LONG, nonce);
		});
		return item;
	}

	/**
	 * The spear's vanilla jab and charge at a tier (Item.Properties.spear in 26.3): the kinetic weapon, swing animation and
	 * damage resistance per tier; the piercing weapon, attack range, minimum charge and use effects are vanilla's and the same
	 * for every tier, so they are written out too rather than trusted to the base item's own. The attack modifiers are the
	 * recipe's (ArmTiers.attackDamage and attackSpeed return the same numbers).
	 */
	private static void spearComponents(ItemStack item, int tier) {
		ArmTiers.Spear t = ArmTiers.spear(tier);
		item.setData(DataComponentTypes.KINETIC_WEAPON, KineticWeapon.kineticWeapon().contactCooldownTicks(10).delayTicks((int) (t.delay() * 20.0F))
				.dismountConditions(KineticWeapon.condition((int) (t.dismountTime() * 20.0F), t.dismountThreshold(), 0.0F))
				.knockbackConditions(KineticWeapon.condition((int) (t.knockbackTime() * 20.0F), t.knockbackThreshold(), 0.0F))
				.damageConditions(KineticWeapon.condition((int) (t.damageTime() * 20.0F), 0.0F, t.damageThreshold()))
				.forwardMovement(0.38F).damageMultiplier(t.damageMultiplier()).sound(Key.key("minecraft:item.spear.use")).hitSound(Key.key("minecraft:item.spear.hit")));
		item.setData(DataComponentTypes.PIERCING_WEAPON, PiercingWeapon.piercingWeapon().dealsKnockback(true).dismounts(false)
				.sound(Key.key("minecraft:item.spear.attack")).hitSound(Key.key("minecraft:item.spear.hit")));
		item.setData(DataComponentTypes.ATTACK_RANGE, AttackRange.attackRange().minReach(2.0F).maxReach(4.5F).minCreativeReach(2.0F).maxCreativeReach(6.5F)
				.hitboxMargin(0.125F).mobFactor(0.5F));
		item.setData(DataComponentTypes.MINIMUM_ATTACK_CHARGE, 1.0F);
		item.setData(DataComponentTypes.ATTACK_ANIMATION, SwingAnimation.swingAnimation().type(SwingAnimation.Animation.STAB).duration(t.swingTicks()));
		item.setData(DataComponentTypes.USE_EFFECTS, UseEffects.useEffects().canSprint(true).interactVibrations(false).speedMultiplier(1.0F));
		if (t.fireResistant()) {
			RegistryKeySet<org.bukkit.damage.DamageType> fire = RegistryAccess.registryAccess().getRegistry(RegistryKey.DAMAGE_TYPE).getTag(DamageTypeTagKeys.IS_FIRE);
			item.setData(DataComponentTypes.DAMAGE_RESISTANT, DamageResistant.damageResistant(fire));
		}
	}

	/**
	 * The tier's tool component with no wear: a sword's rules for the katana (cobweb, the instant and efficient tags),
	 * the tier's mining rules for the pickaxe, axe, shovel and sickle, shears' rules for the claws, an empty rule set for the
	 * mace and trident (their base item's own would wear them 2 a block), none for the bow, crossbow, whip and spear.
	 */
	private static @Nullable Tool tool(String form, int tier) {
		if (ArmTiers.isShears(form)) {
			// shears' vanilla rules (ShearsItem.createToolProperties): cobweb mined and dropped at 15, then the three breaking speed tags
			Tool.Builder b = Tool.tool().defaultMiningSpeed(1).damagePerBlock(0).canDestroyBlocksInCreative(true);
			b.addRule(Tool.rule(RegistrySet.keySet(RegistryKey.BLOCK, BlockTypeKeys.COBWEB), 15f, TriState.TRUE));
			RegistryKeySet<BlockType> extreme = tag(BlockTypeTagKeys.SHEARS_EXTREME_BREAKING_SPEED);
			if (extreme != null) b.addRule(Tool.rule(extreme, 15f, TriState.NOT_SET));
			RegistryKeySet<BlockType> major = tag(BlockTypeTagKeys.SHEARS_MAJOR_BREAKING_SPEED);
			if (major != null) b.addRule(Tool.rule(major, 5f, TriState.NOT_SET));
			RegistryKeySet<BlockType> minor = tag(BlockTypeTagKeys.SHEARS_MINOR_BREAKING_SPEED);
			if (minor != null) b.addRule(Tool.rule(minor, 2f, TriState.NOT_SET));
			return b.build();
		}
		if (ArmTiers.isWeaponOnly(form)) return Tool.tool().defaultMiningSpeed(1).damagePerBlock(0).canDestroyBlocksInCreative(false).build();
		if (ArmTiers.isSword(form)) {
			Tool.Builder b = Tool.tool().defaultMiningSpeed(1).damagePerBlock(0).canDestroyBlocksInCreative(false);
			b.addRule(Tool.rule(RegistrySet.keySet(RegistryKey.BLOCK, BlockTypeKeys.COBWEB), ArmTiers.SWORD_COBWEB_SPEED, TriState.TRUE));
			RegistryKeySet<BlockType> instant = tag(BlockTypeTagKeys.SWORD_INSTANTLY_MINES);
			if (instant != null) b.addRule(Tool.rule(instant, Float.MAX_VALUE, TriState.NOT_SET));
			RegistryKeySet<BlockType> efficient = tag(BlockTypeTagKeys.SWORD_EFFICIENT);
			if (efficient != null) b.addRule(Tool.rule(efficient, ArmTiers.SWORD_EFFICIENT_SPEED, TriState.NOT_SET));
			return b.build();
		}
		String mineable = ArmTiers.mineableTag(form);
		if (mineable == null) return null;
		Tool.Builder b = Tool.tool().defaultMiningSpeed(1).damagePerBlock(0).canDestroyBlocksInCreative(true);
		RegistryKeySet<BlockType> incorrect = tag(BlockTypeTagKeys.create(Key.key(ArmTiers.incorrectTag(tier))));
		if (incorrect != null) b.addRule(Tool.rule(incorrect, null, TriState.FALSE));
		RegistryKeySet<BlockType> mines = tag(BlockTypeTagKeys.create(Key.key(mineable)));
		if (mines != null) b.addRule(Tool.rule(mines, (float) ArmTiers.miningSpeed(tier), TriState.TRUE));
		return b.build();
	}

	private static @Nullable RegistryKeySet<BlockType> tag(TagKey<BlockType> key) {
		try {
			return RegistryAccess.registryAccess().getRegistry(RegistryKey.BLOCK).getTag(key);
		} catch (RuntimeException e) {
			return null; // a tag this server doesn't have: the rule is left out
		}
	}

	/** A copy with a new durability (the biomass bar). Only DAMAGE and MAX_DAMAGE change, so the stack is otherwise the same. */
	static ItemStack withDurability(ItemStack arm, int maxDamage, int damage) {
		ItemStack copy = arm.clone();
		copy.setData(DataComponentTypes.MAX_DAMAGE, maxDamage);
		copy.setData(DataComponentTypes.DAMAGE, damage);
		return copy;
	}

	static int damageOf(ItemStack stack) {
		Integer d = stack.getData(DataComponentTypes.DAMAGE);
		return d == null ? 0 : d;
	}

	/** The stack is a loaded crossbow: its charged projectiles aren't empty (CrossbowArm writes the bolt's state there). */
	static boolean chargedOf(ItemStack stack) {
		io.papermc.paper.datacomponent.item.ChargedProjectiles charged = stack.getData(DataComponentTypes.CHARGED_PROJECTILES);
		return charged != null && !charged.projectiles().isEmpty();
	}

	static int maxDamageOf(ItemStack stack) {
		Integer d = stack.getData(DataComponentTypes.MAX_DAMAGE);
		return d == null ? 0 : d;
	}
}
