package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.ArmItemSpec;
import dev.morphreborn.paper.arm.rules.ArmItemSpecs;
import dev.morphreborn.paper.arm.rules.ArmTiers;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The arm item's recipe (spec 3.2) per form and tier: vanilla numbers, tags, Fortune, model strings and durability. */
class ArmItemSpecTest {
	private static ArmItemSpec survival(String form, int tier) {
		return ArmItemSpecs.of(form, tier, true, 40_000, 100_000, 15_000, "Draining Cut");
	}

	private static ArmItemSpec classic(String form, int tier) {
		return ArmItemSpecs.of(form, tier, false, 0, 0, 15_000, "Draining Cut");
	}

	@Test
	void vanillaAttackNumbers() {
		float[][] damage = {{5, 6, 7}, {3, 4, 5}, {8, 8, 9}, {3.5f, 4.5f, 5.5f}, {0, 0, 0}, {3.5f, 4.5f, 5.5f}};
		float[][] speed = {{-2.4f, -2.4f, -2.4f}, {-2.8f, -2.8f, -2.8f}, {-3.1f, -3.0f, -3.0f}, {-3.0f, -3.0f, -3.0f}, {-1, 0, 0}, {-2.7f, -2.7f, -2.7f}};
		String[] forms = {"katana", "pickaxe", "axe", "shovel", "sickle", "whip"};
		for (int f = 0; f < forms.length; f++) {
			for (int t = 1; t <= 3; t++) {
				ArmItemSpec s = survival(forms[f], t);
				assertTrue(s.hasAttackModifiers(), forms[f]);
				assertEquals(damage[f][t - 1], s.attackDamage(), 1e-6, forms[f] + " tier " + t + " damage");
				assertEquals(speed[f][t - 1], s.attackSpeed(), 1e-6, forms[f] + " tier " + t + " speed");
			}
		}
		assertFalse(survival("bow", 2).hasAttackModifiers());
		// the whip's left-click (2026-11-16): a bit under a sword's damage, between a sword's and an axe's speed
		for (int t = 1; t <= 3; t++) {
			assertTrue(survival("whip", t).attackDamage() < survival("katana", t).attackDamage(), "a whip hits a little softer than the katana, tier " + t);
			assertTrue(survival("whip", t).attackSpeed() < survival("katana", t).attackSpeed() && survival("whip", t).attackSpeed() > survival("axe", t).attackSpeed(),
					"and swings between a sword and an axe, tier " + t);
		}
	}

	@Test
	void tagsAndSpeeds() {
		assertEquals(6, ArmTiers.miningSpeed(1));
		assertEquals(8, ArmTiers.miningSpeed(2));
		assertEquals(9, ArmTiers.miningSpeed(3));
		assertEquals("minecraft:mineable/pickaxe", ArmTiers.mineableTag("pickaxe"));
		assertEquals("minecraft:mineable/axe", ArmTiers.mineableTag("axe"));
		assertEquals("minecraft:mineable/shovel", ArmTiers.mineableTag("shovel"));
		assertEquals("minecraft:mineable/hoe", ArmTiers.mineableTag("sickle"));
		assertNull(ArmTiers.mineableTag("katana"), "the katana mines like a sword");
		assertEquals("minecraft:incorrect_for_iron_tool", ArmTiers.incorrectTag(1));
		assertEquals("minecraft:incorrect_for_diamond_tool", ArmTiers.incorrectTag(2));
		assertEquals("minecraft:incorrect_for_netherite_tool", ArmTiers.incorrectTag(3));
		assertTrue(ArmTiers.isSword("katana"));
		assertEquals(5, ArmTiers.disableBlockingSeconds("axe"));
		assertEquals(0, ArmTiers.disableBlockingSeconds("katana"));
	}

	@Test
	void oneBaseItemPerFormAndTheTiersModel() {
		String[][] rows = {{"katana", "IRON_SWORD", "minecraft:diamond_sword"}, {"pickaxe", "IRON_PICKAXE", "minecraft:diamond_pickaxe"},
				{"axe", "IRON_AXE", "minecraft:diamond_axe"}, {"shovel", "IRON_SHOVEL", "minecraft:diamond_shovel"},
				{"sickle", "IRON_HOE", "minecraft:diamond_hoe"}, {"bow", "PAPER", "minecraft:bow"}, {"whip", "FISHING_ROD", "minecraft:fishing_rod"},
				// the new forms (8.0.0-beta.2): one base per form for every tier, the spear's tier only in its model
				{"spear", "IRON_SPEAR", "minecraft:diamond_spear"}, {"mace", "MACE", "minecraft:mace"}, {"trident", "TRIDENT", "minecraft:trident"},
				// the crossbow is a real crossbow (my design note, 2026-11-16: it loads itself, a click fires)
				{"crossbow", "CROSSBOW", "minecraft:crossbow"}, {"claws", "SHEARS", "minecraft:shears"}};
		for (String[] row : rows) {
			for (int t = 1; t <= 3; t++) assertEquals(row[1], survival(row[0], t).material(), row[0] + " keeps its base item at every tier");
			assertEquals(row[2], survival(row[0], 2).vanillaModel());
		}
		assertEquals("minecraft:netherite_sword", survival("katana", 3).vanillaModel());
		assertEquals("minecraft:iron_pickaxe", survival("pickaxe", 1).vanillaModel());
		assertEquals("minecraft:bow", survival("bow", 3).vanillaModel());
		assertEquals("minecraft:iron_spear", survival("spear", 1).vanillaModel());
		assertEquals("minecraft:netherite_spear", survival("spear", 3).vanillaModel());
	}

	/** The spear keeps vanilla's jab and charge but with per-tier numbers (Item.Properties.spear in 26.3). */
	@Test
	void theSpearIsTheVanillaSpearOfItsTier() {
		assertEquals(2f, ArmTiers.attackDamage("spear", 1));
		assertEquals(3f, ArmTiers.attackDamage("spear", 2));
		assertEquals(4f, ArmTiers.attackDamage("spear", 3));
		assertEquals(1f / 0.95f - 4f, ArmTiers.attackSpeed("spear", 1), 1e-5);
		assertEquals(1f / 1.05f - 4f, ArmTiers.attackSpeed("spear", 2), 1e-5);
		assertEquals(1f / 1.15f - 4f, ArmTiers.attackSpeed("spear", 3), 1e-5);
		assertEquals(19, ArmTiers.spear(1).swingTicks());
		assertEquals(21, ArmTiers.spear(2).swingTicks());
		assertEquals(23, ArmTiers.spear(3).swingTicks(), "(int) (1.15f * 20f), the same float arithmetic as vanilla's own");
		assertFalse(ArmTiers.spear(1).fireResistant());
		assertFalse(ArmTiers.spear(2).fireResistant());
		assertTrue(ArmTiers.spear(3).fireResistant(), "only the netherite spear is fire resistant");
		assertEquals(1.2f, ArmTiers.spear(3).damageMultiplier());
		assertEquals(ArmTiers.spear(1), ArmTiers.spear(0), "tiers clamp to 1..3");
		assertEquals(ArmTiers.spear(3), ArmTiers.spear(9));
		ArmItemSpec s = survival("spear", 2);
		assertTrue(s.hasAttackModifiers());
		assertEquals(3f, s.attackDamage());
		assertEquals(0, s.extraReach(), "the spear's own attack range component is the reach");
		assertNull(s.cooldownGroup(), "a use_cooldown would block the charge");
		assertTrue(s.drawAnimation().isEmpty());
	}

	/** The mace and trident carry the vanilla numbers at every tier; the claws are a light fast weapon; the crossbow hits for nothing. */
	@Test
	void theOtherNewFormsCarryTheirNumbers() {
		for (int t = 1; t <= 3; t++) {
			assertEquals(5f, ArmTiers.attackDamage("mace", t));
			assertEquals(-3.4f, ArmTiers.attackSpeed("mace", t));
			assertEquals(8f, ArmTiers.attackDamage("trident", t));
			assertEquals(-2.9f, ArmTiers.attackSpeed("trident", t));
			assertTrue(ArmTiers.attackDamage("claws", t) > 0);
			assertFalse(ArmTiers.hasAttackModifiers("crossbow"));
			assertFalse(ArmTiers.hasAttackModifiers("bow"));
		}
		assertTrue(ArmTiers.isShears("claws"));
		assertFalse(ArmTiers.isShears("katana"));
		assertTrue(ArmTiers.isWeaponOnly("mace"));
		assertTrue(ArmTiers.isWeaponOnly("trident"));
		assertFalse(ArmTiers.isWeaponOnly("spear"));
		// the claws take Efficiency by level, no Fortune
		for (int t = 1; t <= 3; t++) {
			assertEquals(t, survival("claws", t).efficiency());
			assertEquals(0, survival("claws", t).fortune());
		}
		assertEquals("Tendril Spear", ArmTiers.displayName("spear"));
		assertEquals("Tendril Claws", ArmTiers.displayName("claws"));
		for (String id : ArmTiers.ACCESSORIES) assertTrue(ArmTiers.displayName(id).startsWith("Tendril "), id);
	}

	/** Bukkit has no cause for a spear's blow or a mace's smash, so their damage types are what the per-hit cost recognises. */
	@Test
	void theNewWeaponsOwnDamageTypesCountAsMelee() {
		assertTrue(ArmTiers.isWeaponDamageType("minecraft:spear"));
		assertTrue(ArmTiers.isWeaponDamageType("minecraft:mace_smash"));
		assertFalse(ArmTiers.isWeaponDamageType("minecraft:player_attack"), "that one is ENTITY_ATTACK already");
		assertFalse(ArmTiers.isWeaponDamageType("minecraft:arrow"));
		assertFalse(ArmTiers.isWeaponDamageType("minecraft:trident"), "a thrown trident's hit is the module's, never billed per hit");
	}

	/**
	 * The bow is paper drawn with the bow's pose; the crossbow is a real crossbow with the game's own use (no consumable, no draw
	 * animation of ours): the plugin loads it and the game's click fires it (CrossbowArm), so Bedrock needs no stand-in for it either.
	 */
	@Test
	void theBowIsDrawnPaperAndTheCrossbowIsARealOne() {
		assertEquals("BOW", survival("bow", 1).drawAnimation());
		assertEquals("", survival("crossbow", 1).drawAnimation(), "no consumable on a real crossbow: its use is the game's");
		assertFalse(survival("crossbow", 1).bowConsumable());
		for (String form : ArmTiers.TOOL_FORMS) {
			if (form.equals("bow")) continue;
			assertEquals("", survival(form, 1).drawAnimation(), form);
		}
		assertEquals("BOW", ArmTiers.bedrockItem("bow"));
		assertEquals("", ArmTiers.bedrockItem("crossbow"), "a real crossbow shows as one on Bedrock already");
		assertEquals("", ArmTiers.bedrockItem("katana"));
		assertTrue(ArmTiers.billsOwnWear("crossbow"), "the module bills its shots, so the game's wear is only cancelled");
		assertFalse(ArmTiers.cooldownSweep("crossbow"), "a use_cooldown would block the click that fires");
	}

	@Test
	void fortuneOnPickaxeAxeShovelSickle() {
		for (String form : List.of("pickaxe", "axe", "shovel", "sickle")) {
			for (int t = 1; t <= 3; t++) assertEquals(t, survival(form, t).fortune(), form);
			assertTrue(survival(form, 2).lore().contains("Fortune II"));
		}
		for (String form : List.of("katana", "bow", "whip")) {
			assertEquals(0, survival(form, 3).fortune(), form);
			assertTrue(survival(form, 3).lore().stream().noneMatch(l -> l.startsWith("Fortune")));
		}
		assertEquals(3, ArmItemSpecs.of("pickaxe", 1, true, 0, 0, 0, "", 3).fortune(), "a configured Fortune level");
		assertEquals(3, ArmItemSpecs.of("axe", 1, true, 0, 0, 0, "", 3).fortune(), "the axe takes one too");
		assertEquals(0, ArmItemSpecs.of("katana", 1, true, 0, 0, 0, "", 3).fortune(), "never on a form without Fortune");
	}

	@Test
	void efficiencyOnTheMiningTools() {
		for (String form : List.of("pickaxe", "axe", "shovel", "sickle")) {
			for (int t = 1; t <= 3; t++) assertEquals(t, survival(form, t).efficiency(), form + " tier " + t);
			assertTrue(survival(form, 2).lore().contains("Efficiency II"), form);
			assertTrue(survival(form, 1).lore().contains("Efficiency I"), form);
		}
		for (String form : List.of("katana", "bow", "whip")) {
			assertEquals(0, survival(form, 3).efficiency(), form);
			assertTrue(survival(form, 3).lore().stream().noneMatch(l -> l.startsWith("Efficiency")), form);
		}
		assertEquals(2, ArmItemSpecs.of("shovel", 3, true, 0, 0, 0, "", 3, 2).efficiency(), "a configured Efficiency level");
		assertEquals(0, ArmItemSpecs.of("shovel", 3, true, 0, 0, 0, "", 3, 0).efficiency(), "0 is none");
		assertTrue(ArmItemSpecs.of("shovel", 3, true, 0, 0, 0, "", 3, 0).lore().stream().noneMatch(l -> l.startsWith("Efficiency")), "and no lore line");
		assertEquals(0, ArmItemSpecs.of("katana", 1, true, 0, 0, 0, "", 3, 3).efficiency(), "never on a form without Efficiency");
		assertEquals(255, ArmItemSpecs.of("pickaxe", 1, true, 0, 0, 0, "", 1, 9999).efficiency(), "clamped as the enchantment's level is");
		// Fortune's line first, then Efficiency's, after the special's
		assertEquals(List.of("Your own arm, reshaped.", "F: Vein Rip · 12.5 biomass", "Fortune II", "Efficiency II"),
				ArmItemSpecs.of("pickaxe", 2, true, 0, 0, 12_500, "Vein Rip").lore());
		// the same recipe in Classic: a free arm still digs and drops as its level says
		assertEquals(3, classic("axe", 3).efficiency());
		assertEquals(3, classic("axe", 3).fortune());
	}

	@Test
	void modelStrings() {
		assertEquals(List.of("morphreborn:arm", "katana", "iron"), survival("katana", 1).modelStrings());
		assertEquals(List.of("morphreborn:arm", "pickaxe", "diamond"), survival("pickaxe", 2).modelStrings());
		assertEquals(List.of("morphreborn:arm", "bow", "netherite"), survival("bow", 3).modelStrings());
		assertEquals(List.of("morphreborn:arm", "katana", "netherite"), survival("katana", 7).modelStrings(), "tiers clamp to 1..3");
	}

	@Test
	void stacksToOneAndTheBowDraws() {
		for (String form : ArmTiers.TOOL_FORMS) assertEquals(1, survival(form, 1).maxStackSize(), form);
		assertTrue(survival("bow", 1).bowConsumable());
		assertFalse(survival("katana", 1).bowConsumable());
	}

	@Test
	void useCooldownOnlyOnFormsWithNoUseAction() {
		for (String form : ArmTiers.TOOL_FORMS) {
			// the katana and pickaxe, and the mace (no right-click use); the spear charges, the trident throws, the crossbow and bow draw, the claws shear
			boolean expected = form.equals("katana") || form.equals("pickaxe") || form.equals("mace");
			assertEquals(expected, survival(form, 2).useCooldownGroup(), form);
		}
		assertEquals("morphreborn:arm_katana", survival("katana", 1).cooldownGroup());
		assertNull(survival("axe", 1).cooldownGroup(), "it would block stripping logs");
	}

	@Test
	void survivalDurabilityShowsBiomass() {
		ArmItemSpec s = survival("katana", 2);
		assertEquals(100, s.maxDamage());
		assertEquals(60, s.damage(), "40 of 100 biomass left");
		assertFalse(s.hideDurability());
	}

	@Test
	void classicHidesDurability() {
		ArmItemSpec c = classic("katana", 2);
		assertEquals(2, c.maxDamage());
		assertEquals(0, c.damage());
		assertTrue(c.hideDurability());
	}

	@Test
	void nameAndLore() {
		assertEquals("Tendril Katana", survival("katana", 1).name());
		assertEquals("Tendril War Axe", survival("axe", 1).name());
		assertEquals("Tendril Spade", survival("shovel", 1).name());
		assertEquals("Tendril Recurve", survival("bow", 1).name());
		assertEquals(List.of("Your own arm, reshaped.", "F: Draining Cut · 15 biomass"), survival("katana", 2).lore());
		assertEquals(List.of("Your own arm, reshaped.", "F: Draining Cut"), classic("katana", 2).lore(), "Classic shows no cost");
		assertEquals(List.of("Your own arm, reshaped.", "F: Vein Rip · 12.5 biomass", "Fortune I", "Efficiency I"),
				ArmItemSpecs.of("pickaxe", 1, true, 0, 0, 12_500, "Vein Rip").lore());
		assertEquals(List.of("Your own arm, reshaped."), ArmItemSpecs.of("whip", 1, true, 0, 0, 0, "").lore());
	}

	@Test
	void lanternIsNoItem() {
		assertThrows(IllegalArgumentException.class, () -> ArmItemSpecs.of("lantern", 1, true, 0, 0, 0, ""));
		assertThrows(IllegalArgumentException.class, () -> ArmItemSpecs.of("spoon", 1, true, 0, 0, 0, ""));
	}
}
