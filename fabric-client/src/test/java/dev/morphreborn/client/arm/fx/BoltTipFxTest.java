package dev.morphreborn.client.arm.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.net.ClientNet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The bow's arrow tips and Triple Arrow puff (2026-11-16), the parts that need no game: how FX_BOLT's {@code ticks} is read
 * (the server's BowTips layout: power in bits 0-7, tip in 8-10, fan bit 11, notices 14 and 15), that every tip has a tint of its own, and when the triple shot's puff is made
 * and how long it lives.
 */
class BoltTipFxTest {
	private String serverBefore;

	@BeforeEach
	void newServer() {
		serverBefore = ClientNet.serverVersion;
		ClientNet.serverVersion = ArmProtocol.NEW_FORMS_SINCE;
	}

	@AfterEach
	void restore() {
		ClientNet.serverVersion = serverBefore;
	}

	/** The server's arg for a bolt: power percent, wire tip id (0 bleed .. 4 glowing), and whether it is in the fan (place 1). */
	private static int arg(int power, int wireTip, boolean fan) {
		return power | wireTip << 8 | (fan ? (1 << 11) | (1 << 12) : 0);
	}

	private static ArmFxEvent bolt(int origin, int arrow, int arg, long tick) {
		return new ArmFxEvent(origin, ArmProtocol.FX_BOLT, 2, 0, 11, new int[] {arrow}, new long[0], arg, tick);
	}

	@Test
	void argNamesTheTipAndTheFan() {
		assertEquals(BoltFx.TIP_BLEED, BoltFx.tipOf(arg(100, 0, false)), "wire tip 0 is bleed, the default");
		assertEquals(BoltFx.TIP_SLOWNESS, BoltFx.tipOf(arg(73, 1, false)));
		assertEquals(BoltFx.TIP_POISON, BoltFx.tipOf(arg(73, 2, true)), "the fan bit does not change the tip");
		assertEquals(BoltFx.TIP_FIRE, BoltFx.tipOf(arg(0, 3, false)));
		assertEquals(BoltFx.TIP_GLOWING, BoltFx.tipOf(arg(100, 4, false)));
		assertEquals(BoltFx.TIP_NONE, BoltFx.tipOf(arg(100, 7, false)), "a tip this client doesn't know is drawn plain");
		assertEquals(BoltFx.TIP_NONE, BoltFx.tipOf(arg(0, 2, false) | (1 << 14)), "the armed notice carries a tip but is no arrow");
		assertEquals(BoltFx.TIP_NONE, BoltFx.tipOf(arg(0, 2, false) | (1 << 15)), "nor does the tip-changed notice");
		assertTrue(BoltFx.isTriple(arg(100, 0, true)));
		assertTrue(!BoltFx.isTriple(arg(100, 0, false)));
		assertTrue(!BoltFx.isTriple(arg(100, 0, true) | (1 << 14)));
	}

	@Test
	void everyTipHasATintAndAShapeOfItsOwn() {
		Set<Integer> tints = new HashSet<>();
		for (int tip = BoltFx.TIP_BLEED; tip <= BoltFx.TIP_GLOWING; tip++) {
			int rgb = BoltFx.tipRgb(tip);
			assertEquals(rgb & 0xFFFFFF, rgb, "a plain 0xRRGGBB");
			assertNotEquals(ArmProtocol.COLOUR_DEFAULT, rgb, "a tip is not the arm's own red");
			assertTrue(tints.add(rgb), "tip " + tip + " has its own tint");
		}
		// the bleed tip is a dark red: darker than the arm's red; slowness is pale blue (blue strongest), poison green, fire orange, glowing gold-white
		int bleed = BoltFx.tipRgb(BoltFx.TIP_BLEED), slow = BoltFx.tipRgb(BoltFx.TIP_SLOWNESS), poison = BoltFx.tipRgb(BoltFx.TIP_POISON);
		int fire = BoltFx.tipRgb(BoltFx.TIP_FIRE), glow = BoltFx.tipRgb(BoltFx.TIP_GLOWING);
		assertTrue(((bleed >> 16) & 0xFF) > ((bleed >> 8) & 0xFF) * 3 && ((bleed >> 16) & 0xFF) < 0xC0, "bleed is a dark red");
		assertTrue((slow & 0xFF) > ((slow >> 16) & 0xFF) && (slow & 0xFF) > 0xE0 && ((slow >> 16) & 0xFF) > 0x80, "slowness is pale blue");
		assertTrue(((poison >> 8) & 0xFF) > ((poison >> 16) & 0xFF) && ((poison >> 8) & 0xFF) > (poison & 0xFF), "poison is green");
		assertTrue(((fire >> 16) & 0xFF) > 0xE0 && ((fire >> 8) & 0xFF) > 0x60 && ((fire >> 8) & 0xFF) < 0xC0 && (fire & 0xFF) < 0x60, "fire is orange");
		assertTrue(((glow >> 16) & 0xFF) > 0xE0 && ((glow >> 8) & 0xFF) > 0xD0 && (glow & 0xFF) > 0x60 && (glow & 0xFF) < 0xC0, "glowing is white-gold");
		assertEquals(ArmProtocol.COLOUR_DEFAULT, BoltFx.tipRgb(BoltFx.TIP_NONE));
		assertEquals(ArmProtocol.COLOUR_DEFAULT, BoltFx.tipRgb(99), "an unknown tip falls back to the plain bolt");
		assertTrue(BoltFx.tipGlows(BoltFx.TIP_FIRE) && BoltFx.tipGlows(BoltFx.TIP_GLOWING));
		assertTrue(!BoltFx.tipGlows(BoltFx.TIP_BLEED) && !BoltFx.tipGlows(BoltFx.TIP_POISON) && !BoltFx.tipGlows(BoltFx.TIP_SLOWNESS));
	}

	@Test
	void theFanPuffsOnceAtTheBowAndFadesInFiveTicks() {
		BoltFx fx = new BoltFx();
		Arms.TICK = 5000;
		// three arrows of a fan in one tick: one puff, however many arrows
		fx.start(bolt(7, 801, arg(100, 0, true), 5000));
		fx.start(bolt(7, 802, arg(100, 0, true), 5000));
		fx.start(bolt(7, 803, arg(100, 0, true), 5000));
		assertEquals(1, fx.pops());
		assertEquals(3, fx.active());
		fx.start(bolt(8, 811, arg(100, 2, true), 5000));
		assertEquals(2, fx.pops(), "the second bow's fan");
		Arms.TICK = 5000 + 7;
		fx.tick();
		assertEquals(0, fx.pops(), "the puff is over after 5 ticks");
		assertEquals(4, fx.active(), "the bolts themselves go on");
		fx.clear();
		assertEquals(0, fx.active());
	}

	@Test
	void aFanSplitAcrossTicksIsStillOnePuff() {
		BoltFx fx = new BoltFx();
		Arms.TICK = 5500;
		// the volley's packets share the seed (11) but land on two client ticks: one puff, not two (2026-11-16 review)
		fx.start(bolt(7, 831, arg(100, 0, true), 5500));
		fx.start(bolt(7, 832, arg(100, 0, true), 5501));
		fx.start(bolt(7, 833, arg(100, 0, true), 5502));
		assertEquals(1, fx.pops());
		// the same bow's next volley, with its own seed, a few ticks on: its own puff
		fx.start(new ArmFxEvent(7, ArmProtocol.FX_BOLT, 2, 0, 12, new int[] {834}, new long[0], arg(100, 0, true), 5503));
		assertEquals(2, fx.pops());
		// the same seed far apart in time is a different volley
		fx.start(bolt(7, 835, arg(100, 0, true), 5510));
		assertEquals(3, fx.pops());
		fx.clear();
	}

	@Test
	void bleedAndPoisonBarbsAreSweptBack() {
		assertTrue(BoltFx.tipSweptBack(BoltFx.TIP_BLEED) && BoltFx.tipSweptBack(BoltFx.TIP_POISON), "hooked back, not forward");
		assertTrue(!BoltFx.tipSweptBack(BoltFx.TIP_SLOWNESS) && !BoltFx.tipSweptBack(BoltFx.TIP_FIRE) && !BoltFx.tipSweptBack(BoltFx.TIP_GLOWING));
		assertTrue(!BoltFx.tipSweptBack(BoltFx.TIP_NONE) && !BoltFx.tipSweptBack(99));
	}

	@Test
	void singleArrowsAndOlderServersMakeNoPuff() {
		BoltFx fx = new BoltFx();
		Arms.TICK = 6000;
		// an ordinary draw (no fan bit), even two arrows in one tick
		fx.start(bolt(7, 821, arg(100, 3, false), 6000));
		fx.start(bolt(7, 822, arg(100, 3, false), 6000));
		assertEquals(0, fx.pops());
		// the fan bit from a server that predates the tips is not read
		ClientNet.serverVersion = "7.5.0";
		fx.start(bolt(9, 823, arg(100, 0, true), 6001));
		fx.start(bolt(9, 824, arg(100, 0, true), 6001));
		assertEquals(0, fx.pops());
		assertEquals(4, fx.active());
	}

	private static ArmFxEvent notice(int origin, int flag, int wireTip, int ticks, long tick) {
		return new ArmFxEvent(origin, ArmProtocol.FX_BOLT, 2, ticks, 0, new int[0], new long[0], flag | wireTip << 8, tick);
	}

	@Test
	void theArmedAndTipNoticesDrawTheReadyRing() {
		BoltFx fx = new BoltFx();
		Arms.TICK = 7000;
		assertEquals(BoltFx.TIP_FIRE, BoltFx.noticeTip((1 << 14) | 3 << 8), "a notice's tip is read past its flag bit");
		// ARMED: the ring is up in the tip's tint for the ticks the server says, and a TIP notice retints it
		fx.start(notice(7, 1 << 14, 2, 100, 7000));
		assertTrue(fx.armedFor(7));
		assertEquals(BoltFx.TIP_POISON, fx.readyTip(7));
		fx.start(notice(7, 1 << 15, 3, 0, 7010));
		assertEquals(BoltFx.TIP_FIRE, fx.readyTip(7));
		assertEquals(1, fx.readies(), "one ring a player");
		assertEquals(0, fx.active(), "a notice is no bolt");
		// ARMED with 0 ticks puts it away
		fx.start(notice(7, 1 << 14, 3, 0, 7020));
		assertEquals(0, fx.readies());
		// a bare TIP flashes a short ring and is not armed
		fx.start(notice(8, 1 << 15, 1, 0, 7020));
		assertTrue(!fx.armedFor(8));
		assertEquals(1, fx.readies());
		Arms.TICK = 7020 + 20;
		fx.tick();
		assertEquals(0, fx.readies(), "the flash is over");
		// an armed ring runs out by itself (a viewer who left range never hears the end)
		fx.start(notice(9, 1 << 14, 0, 30, 7040));
		Arms.TICK = 7040 + 29;
		fx.tick();
		assertEquals(1, fx.readies());
		Arms.TICK = 7040 + 30;
		fx.tick();
		assertEquals(0, fx.readies());
		// an older server sends none of this
		ClientNet.serverVersion = "7.5.0";
		fx.start(notice(7, 1 << 14, 2, 100, 7100));
		assertEquals(0, fx.readies());
		fx.clear();
	}
}
