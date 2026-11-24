package dev.morphreborn.client.arm.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.Arms;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * The sickle's and the bow's world effects that can be checked without a game: that ArmFx's list names them and each loads
 * as a handler of its own kind, the Reap lash's timing (a full turn by tick 8, eased, each crop's cut falling where the lead
 * passes it), the harpoon's cable (grows out in 3 ticks, swells out while it flies and home while it pulls) and the life of
 * each effect as events and ticks come in (with no world, so nothing is drawn, only kept and timed out).
 */
class SickleBowFxTest {
	private static ArmFxEvent event(int kind, int[] ids, long[] positions, long tick) {
		return new ArmFxEvent(7, kind, 2, 6, 11, ids, positions, 0, tick);
	}

	@Test
	void handlersAreListedAndLoadForTheirKinds() throws Exception {
		String[] names = {"ReapFx", "BoltFx", "HarpoonFx"};
		int[] kinds = {ArmProtocol.FX_REAP, ArmProtocol.FX_BOLT, ArmProtocol.FX_HARPOON};
		for (int i = 0; i < names.length; i++) {
			String full = "dev.morphreborn.client.arm.fx." + names[i];
			assertTrue(ArmFx.HANDLERS.contains(full), names[i] + " is on ArmFx's list");
			Class<?> c = Class.forName(full);
			assertTrue(FxHandler.class.isAssignableFrom(c));
			FxHandler h = (FxHandler) c.getDeclaredConstructor().newInstance();
			assertEquals(1, h.kinds().length);
			assertEquals(kinds[i], h.kinds()[0], names[i] + "'s kind");
		}
	}

	@Test
	void reapLashTurnsOnceInEightTicksAndCutsCropsAsItPassesThem() {
		assertEquals(0F, ReapFx.lead(0F), 1e-4F);
		assertEquals(360F, ReapFx.lead(8F), 1e-3F, "a full turn by tick 8");
		float prev = -1F;
		for (float a = 0F; a <= 9F; a += 0.25F) {
			float lead = ReapFx.lead(a);
			assertTrue(lead >= prev - 1e-4F, "the lead only goes forward");
			prev = lead;
		}
		// the cut falls on the tick the lead is there: reachesAt inverts the lead
		for (float along = 20F; along < 340F; along += 40F) {
			assertEquals(along, ReapFx.lead(ReapFx.reachesAt(along)), 0.6F, "the lead reaches " + along + " degrees when reachesAt says");
		}
		// the whip draws out in 1.5 ticks, holds, and is back in by tick 12
		assertEquals(0F, ReapFx.extent(0F), 1e-4F);
		assertEquals(1F, ReapFx.extent(5F), 1e-4F);
		assertEquals(0F, ReapFx.extent(12F), 1e-4F);
		assertTrue(ReapFx.lag(3F) > 70F && ReapFx.lag(11F) < 1F, "it lags behind its lead while it swings and closes up after");
	}

	@Test
	void harpoonCableGrowsAndSwellsTheRightWay() {
		assertEquals(0F, HarpoonFx.grown(0F), 1e-4F);
		assertEquals(1F, HarpoonFx.grown(3F), 1e-4F);
		assertTrue(HarpoonFx.grown(1F) > 0.5F, "eased out: over half in the first tick");
		// flying: the swelling runs out from the bow; hooked or grappled: it runs home to it
		float a = HarpoonFx.swellAt(HarpoonFx.Mode.FLIGHT, 0.5F), b = HarpoonFx.swellAt(HarpoonFx.Mode.FLIGHT, 1.5F);
		assertTrue(b > a, "out along the cable while it flies");
		float c = HarpoonFx.swellAt(HarpoonFx.Mode.CREATURE, 1F), d = HarpoonFx.swellAt(HarpoonFx.Mode.CREATURE, 3F);
		assertTrue(d < c, "home along the cable while it pulls");
		float e = HarpoonFx.swellAt(HarpoonFx.Mode.BLOCK, 1F), f = HarpoonFx.swellAt(HarpoonFx.Mode.BLOCK, 3F);
		assertTrue(f < e);
		assertTrue(HarpoonFx.amplitude(HarpoonFx.Mode.CREATURE) > HarpoonFx.amplitude(HarpoonFx.Mode.FLIGHT), "the pull swells hardest");
	}

	@Test
	void reapWindsTheSickleUpInHandForEveryViewer() {
		dev.morphreborn.client.arm.model.ArmReactions.clear();
		try {
			new ReapFx().start(event(ArmProtocol.FX_REAP, new int[0], new long[0], 500));
			dev.morphreborn.client.arm.model.ArmReactions.Reaction r = dev.morphreborn.client.arm.model.ArmReactions.get(7);
			assertTrue(r != null && r.specialKind == ArmProtocol.FX_REAP, "the sickle in hand reaps from the same effect");
			assertEquals(500.0, r.specialAt, 1e-9);
		} finally {
			dev.morphreborn.client.arm.model.ArmReactions.clear();
		}
	}

	@Test
	void reapIsKeptAndTimesOut() {
		ReapFx fx = new ReapFx();
		long[] crops = new long[50];
		for (int i = 0; i < crops.length; i++) crops[i] = BlockPos.asLong(i % 7, 64, i / 7);
		Arms.TICK = 1000;
		fx.start(event(ArmProtocol.FX_REAP, new int[0], crops, 1000));
		assertEquals(1, fx.active());
		fx.tick();
		assertEquals(1, fx.active(), "a reap lasts its whole lash");
		Arms.TICK = 1030;
		fx.tick();
		assertEquals(0, fx.active(), "and is gone once the whip has drawn back in");
		for (int i = 0; i < 9; i++) fx.start(event(ArmProtocol.FX_REAP, new int[0], new long[0], 1030));
		assertTrue(fx.active() <= 4, "at most four at once");
		fx.clear();
		assertEquals(0, fx.active());
	}

	@Test
	void boltIsKeptUntilItsArrowIsGone() {
		BoltFx fx = new BoltFx();
		Arms.TICK = 2000;
		fx.start(event(ArmProtocol.FX_BOLT, new int[] {900}, new long[0], 2000));
		fx.start(event(ArmProtocol.FX_BOLT, new int[0], new long[0], 2000));
		assertEquals(1, fx.active(), "a bolt event needs its arrow's id");
		Arms.TICK = 2000 + 141;
		fx.tick();
		assertEquals(0, fx.active(), "an arrow never seen is dropped by its lifetime");
	}

	@Test
	void harpoonIsStatefulAndLetsGoOnEndOrSilence() {
		HarpoonFx fx = new HarpoonFx();
		Arms.TICK = 3000;
		fx.start(event(ArmProtocol.FX_HARPOON, new int[] {901}, new long[0], 3000));
		assertEquals(1, fx.active());
		// resent while it lasts: still one
		Arms.TICK = 3020;
		fx.start(event(ArmProtocol.FX_HARPOON, new int[0], new long[] {BlockPos.asLong(1, 70, 2)}, 3020));
		fx.tick();
		assertEquals(1, fx.active());
		// an empty list ends it: it lets go and is gone after the snap-back
		Arms.TICK = 3030;
		fx.start(event(ArmProtocol.FX_HARPOON, new int[0], new long[0], 3030));
		fx.tick();
		assertEquals(1, fx.active(), "still snapping back");
		Arms.TICK = 3035;
		fx.tick();
		assertEquals(0, fx.active(), "gone after 4 ticks");
		// not heard from for 60 ticks: lets go by itself
		fx.start(event(ArmProtocol.FX_HARPOON, new int[] {902}, new long[0], 3035));
		Arms.TICK = 3035 + ArmProtocol.STATEFUL_TIMEOUT_TICKS + 1;
		fx.tick();
		assertEquals(1, fx.active(), "letting go");
		Arms.TICK += 5;
		fx.tick();
		assertEquals(0, fx.active());
	}
}
