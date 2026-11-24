package dev.morphreborn.client.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.geom.ArmDraw;
import dev.morphreborn.client.arm.geom.ArmGeometry;
import dev.morphreborn.client.arm.geom.PlaceholderGeometry;
import dev.morphreborn.client.arm.gui.ArmWheel;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The new forms' framework (phase 1, 2026-11-16): every form the wheel, the hotbar and the recogniser know has the same base item
 * on both sides of the wire, a vanilla model the mod-less look falls back to that really exists, a geometry (the placeholder until
 * phase 2 registers one) and, for the accessories, a renderer slot that stays empty until then.
 */
class NewFormsFrameworkTest {
	/** The server's ArmForm bases (the contract): one material per form, whatever the tier. */
	private static Map<String, Item> bases() {
		return Map.ofEntries(Map.entry("katana", Items.IRON_SWORD), Map.entry("pickaxe", Items.IRON_PICKAXE),
			Map.entry("axe", Items.IRON_AXE), Map.entry("shovel", Items.IRON_SHOVEL), Map.entry("sickle", Items.IRON_HOE), Map.entry("bow", Items.PAPER),
			Map.entry("whip", Items.FISHING_ROD), Map.entry("spear", Items.IRON_SPEAR), Map.entry("mace", Items.MACE), Map.entry("trident", Items.TRIDENT),
			Map.entry("crossbow", Items.CROSSBOW), Map.entry("claws", Items.SHEARS));
	}
	/** The vanilla item model each form shows to a viewer without the arm model, per tier (the server's ArmForm.vanillaModel). */
	private static final Map<String, String[]> MODELS = Map.ofEntries(Map.entry("katana", metal("%s_sword")), Map.entry("pickaxe", metal("%s_pickaxe")),
			Map.entry("axe", metal("%s_axe")), Map.entry("shovel", metal("%s_shovel")), Map.entry("sickle", metal("%s_hoe")),
			Map.entry("bow", same("bow")), Map.entry("whip", same("fishing_rod")), Map.entry("spear", metal("%s_spear")), Map.entry("mace", same("mace")),
			Map.entry("trident", same("trident")), Map.entry("crossbow", same("crossbow")), Map.entry("claws", same("shears")));

	private static String[] metal(String pattern) {
		return new String[] {String.format(pattern, "iron"), String.format(pattern, "diamond"), String.format(pattern, "netherite")};
	}

	private static String[] same(String model) {
		return new String[] {model, model, model};
	}

	private static boolean booted;

	@BeforeAll
	static void boot() {
		if (!booted) {
			booted = true;
			net.minecraft.SharedConstants.tryDetectVersion();
			net.minecraft.server.Bootstrap.bootStrap();
		}
	}

	@Test
	void everyFormHasItsBaseAndItsVanillaModel() {
		for (String form : ArmProtocol.FORMS) {
			assertTrue(bases().containsKey(form), form + " has a base in this test's table");
			// the wheel's fake stacks, the hotbar's and the recogniser's fallback all use the server's base for the form
			assertEquals(bases().get(form), ArmWheel.baseItem(form), form + " in the wheel");
			assertEquals(bases().get(form), ArmStacks.baseItem(form), form + " in the recogniser");
			for (int tier = 1; tier <= 3; tier++) {
				Identifier model = ArmWheel.vanillaModel(form, tier);
				assertNotNull(model, form + " " + tier);
				assertEquals(MODELS.get(form)[tier - 1], model.getPath(), form + " tier " + tier);
				assertTrue(ArmWheel.class.getResource("/assets/minecraft/items/" + model.getPath() + ".json") != null,
						"the vanilla item definition " + model.getPath() + " exists in this game");
			}
		}
		assertEquals(ArmProtocol.FORMS.size(), bases().size());
		// the recogniser reads the strings the wheel's stacks carry, for every form and tier
		for (String form : ArmProtocol.FORMS) {
			for (int tier = 1; tier <= 3; tier++) {
				ArmStacks.Recognized r = ArmStacks.parse(java.util.List.of(ArmProtocol.CMD_TAG, form, ArmProtocol.tierName(tier)));
				assertNotNull(r, form);
				assertEquals(form, r.form());
				assertEquals(tier, r.tier());
			}
		}
	}

	@Test
	void theWheelHasTheSameTwelveAsTheSlotForms() {
		assertEquals(ArmProtocol.WHEEL, ArmWheel.SLOTS);
		for (String form : ArmProtocol.WHEEL) assertTrue(ArmWheel.hasItem(form), form);
		assertFalse(ArmWheel.hasItem("lantern"), "an accessory has no item");
		assertFalse(ArmWheel.hasItem("shield"));
	}

	@Test
	void anUnbuiltNewFormIsDrawnAsThePlaceholder() {
		for (String form : new String[] {"spear", "mace", "trident", "crossbow", "claws"}) {
			if (ArmGeometry.built(form)) continue; // phase 2 has registered it: its own geometry
			assertInstanceOf(PlaceholderGeometry.class, ArmGeometry.get(form), form);
		}
	}

	@Test
	void anAccessoryNobodyRendersIsSimplyNotDrawn() {
		for (String id : new String[] {"shield", "spyglass", "flint"}) {
			if (ArmGeometry.accessoryBuilt(id)) continue; // phase 2
			assertNull(ArmGeometry.accessory(id), id);
		}
		assertNull(ArmGeometry.accessory("lantern"), "the lantern's renderer is the lure's own path, not an accessory renderer");
		assertNull(ArmGeometry.accessory("unknown"));
	}

	@Test
	void drawsCarryTheAccessoriesAndOldConstructorsStillWork() {
		ArmDraw rest = ArmDraw.rest("spear", 2, ArmDraw.CTX_TP, ArmDraw.LOD_FULL, 0F);
		assertFalse(rest.anyAccessory());
		assertFalse(rest.thrown());
		assertEquals(0F, rest.accessoryAge("shield"));
		// a draw with the shield (bit 1) raised and the flint (bit 3) on, the shield's age drawing back
		ArmDraw withAccessories = new ArmDraw(rest.form(), rest.tier(), rest.lod(), rest.ctx(), false, false, false, 0F, 0, rest.state(), 1F, "", 0F, 0F, false, 1F,
				ArmDraw.FULL, 0F, ArmDraw.NEVER, ArmDraw.NO_SPECIAL, ArmDraw.NEVER, ArmDraw.NEVER, new float[0], 0, false, 0F, 0F, true, false, false, null, 0F, -1F,
				0F, null, 0xF000F0, 1F, 0b1010, 1 << 4, new float[] {0F, -3F, 0F, 12F}, true);
		assertTrue(withAccessories.anyAccessory());
		assertTrue(withAccessories.accessoryOn("shield"));
		assertFalse(withAccessories.accessoryOn("spyglass"));
		assertTrue(withAccessories.accessoryOn("flint"));
		assertFalse(withAccessories.accessoryOn("lantern"));
		assertEquals(1, withAccessories.accessoryArg("shield"));
		assertEquals(0, withAccessories.accessoryArg("flint"));
		assertEquals(-3F, withAccessories.accessoryAge("shield"));
		assertEquals(12F, withAccessories.accessoryAge("flint"));
		assertEquals(0F, withAccessories.accessoryAge("unknown"));
		assertTrue(withAccessories.thrown());
		// the copies the renderer makes keep them
		ArmDraw finished = withAccessories.finish(1, false, null, 0F, -1F, 0F, null, 0xF000F0);
		assertTrue(finished.accessoryOn("shield"));
		assertEquals(1, finished.accessoryArg("shield"));
		assertTrue(finished.thrown());
		assertTrue(withAccessories.withForm("mace").accessoryOn("flint"));
		assertTrue(withAccessories.withState(ArmProtocol.STATE_FORMED, 1F).accessoryOn("flint"));
	}

	@Test
	void formsAndRenderersRegisteredAheadOfTheirClassesAreTolerated() {
		// the phase 2 names are on the lists before their classes exist: a form without a geometry is drawn by the placeholder, an
		// accessory without a renderer and an effect without a handler are skipped, none of it throws
		for (String form : new String[] {"spear", "mace", "trident", "crossbow", "claws"}) assertNotNull(ArmGeometry.get(form), form);
		for (String id : new String[] {"shield", "spyglass", "flint"}) ArmGeometry.accessory(id);
	}

	@Test
	void aQuickFormsStaticCoversTheWristByTheSecondTick() {
		ArmDraw d = ArmDraw.rest("katana", 1, ArmDraw.CTX_TP, ArmDraw.LOD_FULL, 0F);
		int length = ArmProtocol.duration(ArmProtocol.STATE_QUICKFORM);
		assertEquals(0.5F, d.withState(ArmProtocol.STATE_QUICKFORM, 1F / length).armStatic(), 1e-4F);
		assertTrue(d.withState(ArmProtocol.STATE_QUICKFORM, 2F / length).armStatic() >= 0.999F, "the skin arm is hidden from tick 2");
	}

	@Test
	void everyTipPointIsDistinct() {
		java.util.Set<Integer> seen = new java.util.HashSet<>();
		for (String id : ArmProtocol.ACCESSORIES) assertTrue(seen.add(ArmTips.accessory(id)), id);
		assertEquals(ArmTips.LURE, ArmTips.accessory("lantern"), "the lantern keeps its recorded point");
		assertEquals(-1, ArmTips.accessory("unknown"));
		for (int point : seen) assertTrue(point >= 0 && point < 16, "the recorder keeps the low four bits");
		assertFalse(seen.contains(ArmTips.TIP) || seen.contains(ArmTips.MID) || seen.contains(ArmTips.GRIP));
	}
}
