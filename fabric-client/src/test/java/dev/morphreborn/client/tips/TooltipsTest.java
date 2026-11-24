package dev.morphreborn.client.tips;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.net.Wire;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Tooltips setting on the client (decided 2026-11-17): the line filter, what the server is told and when, and the packet's bytes against the
 * same fixture as the plugin's TooltipsTest. The screens that use the filter need the game and are on the rig's checklist (docs/TESTING.md).
 */
class TooltipsTest {
	@Test
	void aTipIsLeftOutAndAnAlwaysLineStays() {
		List<Tagged<String>> wheel = List.of(Tagged.always("Shield"), Tagged.tip("Click to switch it on"), Tagged.tip("Hold right-click to raise it"),
				Tagged.always("Costs biomass while on"));
		assertEquals(List.of("Shield", "Click to switch it on", "Hold right-click to raise it", "Costs biomass while on"), Tagged.filter(wheel, true));
		assertEquals(List.of("Shield", "Costs biomass while on"), Tagged.filter(wheel, false));
		assertEquals(List.of(), Tagged.filter(List.of(Tagged.tip("Esc: close")), false), "nothing but a tip: nothing is left");
	}

	@Test
	void takingTheTipsOutLeavesNoGapInATooltip() {
		List<Tagged<String>> tooltip = List.of(Tagged.always("Tendril Shield"), Tagged.tip("What it does"), Tagged.always(""), Tagged.always("Level 1 / 3"),
				Tagged.always("Cost: 12 biomass"), Tagged.always(""), Tagged.tip("Click to buy"));
		assertEquals(List.of("Tendril Shield", "What it does", "", "Level 1 / 3", "Cost: 12 biomass", "", "Click to buy"), Tagged.filter(tooltip, true, String::isEmpty));
		assertEquals(List.of("Tendril Shield", "", "Level 1 / 3", "Cost: 12 biomass"), Tagged.filter(tooltip, false, String::isEmpty), "one blank between name and numbers, none at the end");
	}

	@Test
	void theSettingIsOnByDefault() {
		assertTrue(new ClientConfig().tooltips);
	}

	@Test
	void theServerIsToldOnceAfterTheHandshakeAndWhenItChanges() {
		TooltipSync sync = new TooltipSync();
		assertNull(sync.due(true, false, "8.0.0-beta.2"), "not before the handshake");
		assertEquals(Boolean.TRUE, sync.due(true, true, "8.0.0-beta.2"), "the first tick after it");
		assertNull(sync.due(true, true, "8.0.0-beta.2"), "and then nothing while it stays");
		assertEquals(Boolean.FALSE, sync.due(false, true, "8.0.0-beta.2"), "a change goes at once");
		assertNull(sync.due(false, true, "8.0.0-beta.2"));
		assertEquals(Boolean.TRUE, sync.due(true, true, "8.0.0-beta.2"));
		sync.reset();
		assertEquals(Boolean.TRUE, sync.due(true, true, "8.0.0-beta.2"), "a new connection knows nothing");
	}

	@Test
	void anOlderServerIsNeverToldBecauseItWouldNotKnowThePacket() {
		TooltipSync sync = new TooltipSync();
		assertNull(sync.due(false, true, "7.6.0"));
		assertNull(sync.due(false, true, "8.0.0-beta.1"));
		assertNull(sync.due(false, true, ""), "servers before 6.6.0 say no version");
		assertFalse(TooltipSync.serverKnows(""));
		assertTrue(TooltipSync.serverKnows("8.0.0-beta.2"));
		assertTrue(TooltipSync.serverKnows("8.1.0"));
		assertEquals("8.0.0-beta.2", TooltipSync.SINCE);
	}

	@Test
	void thePacketIsTheAgreedBytes() {
		assertEquals(12, Wire.C_TOOLTIPS);
		assertEquals(3, Wire.PROTOCOL_VERSION, "everything is additive");
		assertArrayEquals(HexFormat.ofDelimiter(" ").parseHex("0C 01"), new Wire.Out(Wire.C_TOOLTIPS).bool(true).bytes(), "id 12, bool on");
		assertArrayEquals(HexFormat.ofDelimiter(" ").parseHex("0C 00"), new Wire.Out(Wire.C_TOOLTIPS).bool(false).bytes());
	}

	@Test
	void everyTextTheTooltipsWorkAddedIsInTheLanguageFile() throws IOException {
		String json;
		try (InputStream in = TooltipsTest.class.getResourceAsStream("/assets/morphreborn/lang/en_us.json")) {
			assertTrue(in != null, "the language file is on the classpath");
			json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		for (String key : List.of("morphreborn.settings.tooltips", "morphreborn.settings.tooltips.tooltip", "morphreborn.message.unlock_biomass_short",
				"morphreborn.message.buy_morph_ability_short", "morphreborn.arm.wheel.special_plain", "morphreborn.arm.wheel.special_plain_free",
				"morphreborn.arm.wheel.lantern_brightness", "morphreborn.arm.wheel.lantern_scroll", "morphreborn.arm.wheel.accessory_click_on",
				"morphreborn.arm.wheel.accessory_click_off", "morphreborn.arm.how.shield", "morphreborn.arm.how.shield2", "morphreborn.arm.how.spyglass",
				"morphreborn.arm.how.spyglass2", "morphreborn.arm.how.flint", "morphreborn.arm.how.flint2")) {
			assertTrue(json.contains("\"" + key + "\":"), key + " is in en_us.json");
		}
	}

	@Test
	void noLanguageKeyCarriesAKeyHintTheSettingCouldNotHide() throws IOException {
		String json;
		try (InputStream in = TooltipsTest.class.getResourceAsStream("/assets/morphreborn/lang/en_us.json")) {
			assertTrue(in != null, "the language file is on the classpath");
			json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		// keys nothing reads any more: a hint in one would show, when wired up later, whatever the setting
		for (String gone : List.of("morphreborn.arm.special.bow", "morphreborn.arm.special.shield", "morphreborn.arm.special.spyglass", "morphreborn.arm.wheel.flare")) {
			assertFalse(json.contains("\"" + gone + "\":"), gone + " is not in en_us.json");
			assertFalse(json.contains("\"" + gone + "_free\":"), gone + "_free is not in en_us.json");
		}
		// the flint lines say what the server's own text says (FlintSettings.lines): melee hits and shots burn, and which blocks light
		assertTrue(json.contains("Every melee hit you land sets the target alight; your bow and crossbow shots burn"));
		assertTrue(json.contains("Right-click a campfire, candle, TNT or portal frame with an arm out, if the server allows it"));
	}
}
