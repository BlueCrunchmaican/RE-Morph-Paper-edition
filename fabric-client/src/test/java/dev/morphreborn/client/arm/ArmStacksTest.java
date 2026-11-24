package dev.morphreborn.client.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reading an arm from its custom_model_data strings, as the server writes them ({@code ["morphreborn:arm", form,
 * tier]}). Real ItemStacks need the game's registries, so this tests the strings the stack's component holds.
 */
class ArmStacksTest {
	@Test
	void readsTheServersStrings() {
		ArmStacks.Recognized r = ArmStacks.parse(List.of("morphreborn:arm", "katana", "diamond"));
		assertNotNull(r);
		assertEquals("katana", r.form());
		assertEquals(2, r.tier());
		for (String form : ArmProtocol.FORMS) {
			for (int tier = 1; tier <= 3; tier++) {
				ArmStacks.Recognized each = ArmStacks.parse(List.of(ArmProtocol.CMD_TAG, form, ArmProtocol.tierName(tier)));
				assertNotNull(each, form + " " + tier);
				assertEquals(form, each.form());
				assertEquals(tier, each.tier());
			}
		}
	}

	@Test
	void tiers() {
		assertEquals(1, ArmStacks.tier("iron"));
		assertEquals(2, ArmStacks.tier("diamond"));
		assertEquals(3, ArmStacks.tier("netherite"));
		assertEquals(0, ArmStacks.tier("gold"));
		assertEquals(0, ArmStacks.tier("Iron"), "the server writes lower case");
		assertEquals(0, ArmStacks.tier(""));
		assertEquals(0, ArmStacks.tier(null));
		assertEquals("iron", ArmProtocol.tierName(1));
		assertEquals("netherite", ArmProtocol.tierName(3));
		assertEquals("", ArmProtocol.tierName(0));
		assertEquals("", ArmProtocol.tierName(4));
	}

	@Test
	void anUnknownOrMissingTierDrawsAsIron() {
		assertEquals(1, ArmStacks.parse(List.of("morphreborn:arm", "katana", "obsidian")).tier());
		assertEquals(1, ArmStacks.parse(List.of("morphreborn:arm", "katana")).tier());
		assertEquals("harpoon_gun", ArmStacks.parse(List.of("morphreborn:arm", "harpoon_gun", "iron")).form(), "a newer server's form still reads");
	}

	@Test
	void otherStacksAreNotArms() {
		assertNull(ArmStacks.parse(null));
		assertNull(ArmStacks.parse(List.of()));
		assertNull(ArmStacks.parse(List.of("morphreborn:arm")));
		assertNull(ArmStacks.parse(List.of("minecraft:katana", "katana", "iron")), "another pack's strings");
		assertNull(ArmStacks.parse(List.of("katana", "morphreborn:arm", "iron")), "the tag must come first");
		assertNull(ArmStacks.parse(List.of("morphreborn:arm", "", "iron")));
		assertNull(ArmStacks.parse(List.of("morphreborn:arm", "Katana", "iron")));
		assertNull(ArmStacks.parse(List.of("morphreborn:arm", "kat ana", "iron")));
		assertNull(ArmStacks.parse(List.of("morphreborn:arm", "a".repeat(33), "iron")), "longer than a form id can be");
		assertNull(ArmStacks.parse(Arrays.asList("morphreborn:arm", null, "iron")));
	}

	@Test
	void formIds() {
		assertTrue(ArmStacks.validForm("sickle"));
		assertTrue(ArmStacks.validForm("form_2"));
		assertTrue(ArmStacks.validForm("a".repeat(32)));
		assertFalse(ArmStacks.validForm("a".repeat(33)));
		assertFalse(ArmStacks.validForm("morphreborn:katana"));
		assertFalse(ArmStacks.validForm(null));
	}

	@Test
	void recognisingAllocatesOnce() {
		ArmStacks.Recognized a = ArmStacks.parse(List.of("morphreborn:arm", "pickaxe", "netherite"));
		ArmStacks.Recognized b = ArmStacks.parse(List.of("morphreborn:arm", "pickaxe", "netherite"));
		assertSame(a, b, "one record per form and tier, reused every frame");
		assertEquals(new ArmStacks.Recognized("pickaxe", 3), a);
	}

	@Test
	void theBowArm() {
		ArmStacks.Recognized bow = ArmStacks.parse(List.of("morphreborn:arm", "bow", "iron"));
		assertNotNull(bow);
		assertEquals(ArmProtocol.BOW, bow.form());
	}
}
