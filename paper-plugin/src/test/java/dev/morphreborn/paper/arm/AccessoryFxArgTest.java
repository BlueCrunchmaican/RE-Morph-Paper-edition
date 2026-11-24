package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.morphreborn.paper.arm.rules.FlintRules;
import dev.morphreborn.paper.arm.rules.ShieldRules;
import dev.morphreborn.paper.arm.rules.SpyglassRules;
import org.junit.jupiter.api.Test;

/** FX_ACCESSORY's {@code arg} is {@code (place << 8) | move} (decided 2026-11-16), and the three accessories agree on the places. */
class AccessoryFxArgTest {
	@Test
	void theArgIsThePlaceInBits8To11AndTheMoveInBits0To7() {
		assertEquals(0x101, ArmProtocol.accessoryArg(ArmProtocol.ACCESSORY_PLACE_SHIELD, 1));
		assertEquals(0x202, ArmProtocol.accessoryArg(ArmProtocol.ACCESSORY_PLACE_SPYGLASS, 2));
		assertEquals(0x302, ArmProtocol.accessoryArg(ArmProtocol.ACCESSORY_PLACE_FLINT, 2));
		for (int place = 0; place < ArmForm.ACCESSORIES.size(); place++) {
			int arg = ArmProtocol.accessoryArg(place, 0xFF);
			assertEquals(place, ArmProtocol.accessoryPlace(arg));
			assertEquals(0xFF, ArmProtocol.accessoryMove(arg));
		}
	}

	@Test
	void thePlacesAreTheAccessoryRingsOrder() {
		assertEquals(ArmForm.ACCESSORIES.indexOf(ArmForm.LANTERN), ArmProtocol.ACCESSORY_PLACE_LANTERN);
		assertEquals(ArmForm.ACCESSORIES.indexOf(ArmForm.SHIELD), ArmProtocol.ACCESSORY_PLACE_SHIELD);
		assertEquals(ArmForm.ACCESSORIES.indexOf(ArmForm.SPYGLASS), ArmProtocol.ACCESSORY_PLACE_SPYGLASS);
		assertEquals(ArmForm.ACCESSORIES.indexOf(ArmForm.FLINT), ArmProtocol.ACCESSORY_PLACE_FLINT);
	}

	@Test
	void eachAccessorysOwnEncoderAgreesWithTheSharedOne() {
		for (int move = 0; move <= 0xFF; move += 17) {
			assertEquals(ArmProtocol.accessoryArg(ArmProtocol.ACCESSORY_PLACE_SHIELD, move), ShieldRules.fxArg(move));
			assertEquals(ArmProtocol.accessoryArg(ArmProtocol.ACCESSORY_PLACE_SPYGLASS, move), SpyglassRules.fxArg(move));
			assertEquals(ArmProtocol.accessoryArg(ArmProtocol.ACCESSORY_PLACE_FLINT, move), FlintRules.fxArg(move));
		}
	}
}
