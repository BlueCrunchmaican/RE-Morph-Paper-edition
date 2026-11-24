package dev.morphreborn.client.arm.fx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.geom.ArmDraw;
import dev.morphreborn.client.arm.geom.ShieldMotion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The shield's world effects (FX_ACCESSORY, arg (1 << 8) | move for moves 1 to 3, 2026-11-16): the routing, the wire's numbers and the timelines. */
class ShieldFxTest {
	@AfterEach
	void reset() {
		ShieldFx.clear();
	}

	/** An event as the server sends it: the arg is the shield's place and the move, and the ids never carry the owner. */
	private static ArmFxEvent event(int arg, int owner, int other, long tick) {
		return new ArmFxEvent(owner, ArmProtocol.FX_ACCESSORY, 2, 0, 4242, new int[] {other}, new long[] {0L}, arg, tick);
	}

	private static int arg(int move) {
		return ArmProtocol.accessoryArg(ArmProtocol.ACCESSORY_PLACE_SHIELD, move);
	}

	@Test
	void theAccessoryHandlerTakesTheAccessoryKindOnly() {
		AccessoryFx fx = new AccessoryFx();
		assertArrayEquals(new int[] {ArmProtocol.FX_ACCESSORY}, fx.kinds());
		assertEquals(20, ArmProtocol.FX_ACCESSORY);
		assertEquals(1, ShieldFx.BLOCK, "the server's FX_BLOCK");
		assertEquals(2, ShieldFx.REFLECT, "the server's FX_REFLECT");
		assertEquals(3, ShieldFx.ABSORB, "the server's FX_ABSORB");
		assertEquals(0x101, arg(ShieldFx.BLOCK));
		assertEquals(0x102, arg(ShieldFx.REFLECT));
		assertEquals(0x103, arg(ShieldFx.ABSORB));
		assertTrue(ShieldFx.handles(0x101) && ShieldFx.handles(0x102) && ShieldFx.handles(0x103), "the three moves of the server's encoding");
		assertEquals(ShieldFx.REFLECT, ShieldFx.move(0x102));
		assertTrue(!ShieldFx.handles(0) && !ShieldFx.handles(0x100) && !ShieldFx.handles(0x104), "the shield's place with a move it doesn't have is nobody's");
		assertTrue(!ShieldFx.handles(1) && !ShieldFx.handles(2) && !ShieldFx.handles(3), "a bare move is not the shield's: the place is in the arg");
		assertTrue(!ShieldFx.handles(0x201) && !ShieldFx.handles(0x301), "other accessories' args are not the shield's");
	}

	@Test
	void aBlockOrReflectKicksTheOwnersShieldAndOthersAreIgnored() {
		AccessoryFx fx = new AccessoryFx();
		fx.start(event(99, 5, 6, 1000L));
		fx.start(event(ShieldFx.BLOCK, 5, 6, 1000L)); // a bare move is not the shield's
		assertEquals(ArmDraw.NEVER, ShieldMotion.blockAge(5, 1001F), "an arg that isn't the shield's does nothing");
		fx.start(event(arg(ShieldFx.BLOCK), 5, 6, 1000L));
		assertEquals(1F, ShieldMotion.blockAge(5, 1001F), 1e-5F);
		assertEquals(1F, ShieldMotion.blockPower(5), 1e-6F);
		assertEquals(4242, ShieldMotion.blockSeed(5));
		fx.start(event(arg(ShieldFx.REFLECT), 5, 6, 1010L));
		assertEquals(1.5F, ShieldMotion.blockPower(5), 1e-6F, "a reflect hits the shield harder");
		fx.start(event(arg(ShieldFx.ABSORB), 5, 6, 1020L));
		assertEquals(0.7F, ShieldMotion.blockPower(5), 1e-6F, "an absorb is a smaller kick, and the shield still recoils");
		// a malformed event (no entity ids at all) is fine
		fx.start(new ArmFxEvent(7, ArmProtocol.FX_ACCESSORY, 1, 0, 1, new int[0], new long[0], arg(ShieldFx.BLOCK), 5L));
		assertTrue(ShieldMotion.blockAge(7, 6F) < 2F);
		fx.clear();
		assertEquals(ArmDraw.NEVER, ShieldMotion.blockAge(5, 1001F), "cleared with the world");
	}

	@Test
	void sparksSpreadOutFastAndRingGrows() {
		assertEquals(0F, ShieldFx.spread(0F), 1e-6F);
		assertEquals(1F, ShieldFx.spread(ShieldFx.BLOCK_LIFE), 1e-6F);
		float last = 0F;
		for (float a = 0F; a <= ShieldFx.BLOCK_LIFE; a += 0.25F) {
			float s = ShieldFx.spread(a);
			assertTrue(s >= last - 1e-6F, "the sparks never come back");
			last = s;
		}
		assertTrue(ShieldFx.spread(2F) > 0.5F, "most of the spread is in the first quarter of the life");
		assertEquals(0F, ShieldFx.ring(0F), 1e-6F);
		assertEquals(1F, ShieldFx.ring(6F), 1e-6F);
		assertEquals(1F, ShieldFx.ring(60F), 1e-6F);
	}

	@Test
	void theReflectsTendrilSnapsOutHoldsAndComesBack() {
		assertEquals(0F, ShieldFx.snap(0F), 1e-6F);
		assertEquals(0F, ShieldFx.snap(-1F), 1e-6F);
		assertEquals(1F, ShieldFx.snap(ShieldFx.REFLECT_OUT), 1e-6F);
		assertEquals(1F, ShieldFx.snap(3F), 1e-6F);
		assertEquals(0F, ShieldFx.snap(ShieldFx.REFLECT_LIFE), 1e-6F);
		float mid = ShieldFx.snap(ShieldFx.REFLECT_LIFE - 1F);
		assertTrue(mid > 0.1F && mid < 0.9F, "drawing back: " + mid);
		assertTrue(ShieldFx.life(ShieldFx.REFLECT) < ShieldFx.life(ShieldFx.BLOCK) + 1, "a reflect is the shorter of the two");
		assertTrue(ShieldFx.life(ShieldFx.ABSORB) < ShieldFx.life(ShieldFx.REFLECT), "an absorb is a snap, the shortest");
	}
}
