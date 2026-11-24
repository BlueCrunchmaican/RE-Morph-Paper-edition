package dev.morphreborn.client.arm.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.geom.ArmDraw;
import dev.morphreborn.client.arm.geom.LureGeometry;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The lantern's flare against section 3.10 and 4: the seed flies at 1.2 blocks a tick (3 to 40 ticks), lands exactly on the
 * cell with a lob in between, the bloom swells the core to its peak and settles it, the petals grow and draw back when its
 * time runs out, a message with no cell shows nothing, a second flare from the same thrower replaces the first, and the
 * lure bursts as one arrives.
 */
class FlareFxTest {
	@AfterEach
	void forget() {
		LureGeometry.clear();
	}

	private static ArmFxEvent flare(int origin, int ticks, BlockPos... cells) {
		long[] positions = new long[cells.length];
		for (int i = 0; i < cells.length; i++) positions[i] = cells[i].asLong();
		return new ArmFxEvent(origin, ArmProtocol.FX_FLARE, 2, ticks, 123, new int[0], positions, 0, 500L);
	}

	@Test
	void declaresTheFlareKindAndIsListedForTheFxModule() throws Exception {
		assertEquals(1, new FlareFx().kinds().length);
		assertEquals(ArmProtocol.FX_FLARE, new FlareFx().kinds()[0]);
		assertTrue(ArmFx.HANDLERS.contains("dev.morphreborn.client.arm.fx.FlareFx"));
		assertTrue(FxHandler.class.isAssignableFrom(Class.forName("dev.morphreborn.client.arm.fx.FlareFx")));
	}

	@Test
	void flightIsDistanceOverTheSeedsSpeedClamped() {
		assertEquals(3, FlareFx.flightTicks(0.0), "never an instant");
		assertEquals(3, FlareFx.flightTicks(2.0));
		assertEquals(6, FlareFx.flightTicks(7.0), "7 blocks at 1.2 a tick, rounded up");
		assertEquals(14, FlareFx.flightTicks(16.0));
		assertEquals(27, FlareFx.flightTicks(32.0), "the longest beam");
		assertEquals(40, FlareFx.flightTicks(500.0), "capped");
		assertEquals(3, FlareFx.flightTicks(Double.NaN));
	}

	@Test
	void theSeedLeavesTheLureLandsOnTheCellAndLobsBetween() {
		double[] p = new double[3];
		FlareFx.seedAt(0, 10, 0, 30, 10, 0, 0.0, p);
		assertEquals(0.0, p[0], 1e-9);
		assertEquals(10.0, p[1], 1e-9);
		FlareFx.seedAt(0, 10, 0, 30, 10, 0, 1.0, p);
		assertEquals(30.0, p[0], 1e-9, "exactly on the cell");
		assertEquals(10.0, p[1], 1e-9);
		FlareFx.seedAt(0, 10, 0, 30, 10, 0, 0.5, p);
		assertEquals(15.0, p[0], 1e-9);
		assertEquals(10.25, p[1], 1e-9, "a quarter block of lob in the middle of a long throw");
		FlareFx.seedAt(0, 10, 0, 2, 10, 0, 0.5, p);
		assertEquals(10.05, p[1], 1e-9, "less for a short one");
		FlareFx.seedAt(0, 10, 0, 30, 10, 0, 7.0, p);
		assertEquals(30.0, p[0], 1e-9, "past the end it stays on the cell");
	}

	@Test
	void theBloomSwellsTheCoreThenSettlesIt() {
		assertEquals(0F, FlareFx.coreSize(-1F), 0F, "nothing before it lands");
		assertEquals(0F, FlareFx.coreSize(0F), 1e-4F);
		float peak = 0F;
		for (float t = 0F; t < 10F; t += 0.1F) peak = Math.max(peak, FlareFx.coreSize(t));
		assertEquals(FlareFx.CORE_PEAK, peak, 0.2F, "it swells to its peak");
		assertEquals(FlareFx.CORE_REST, FlareFx.coreSize(30F), 0.01F, "and settles");
		assertTrue(FlareFx.CORE_PEAK > 1.8F * FlareFx.CORE_REST, "about twice");
	}

	@Test
	void petalsGrowAfterTheBloomAndDrawBackAtTheEnd() {
		assertEquals(0F, FlareFx.petalGrowth(0F, 400), 1e-6F);
		assertEquals(0F, FlareFx.petalGrowth(FlareFx.PETALS_FROM, 400), 1e-6F);
		assertEquals(1F, FlareFx.petalGrowth(FlareFx.PETALS_FROM + FlareFx.PETALS_TICKS + 1F, 400), 1e-6F);
		assertEquals(1F, FlareFx.petalGrowth(200F, 400), 1e-6F);
		float a = FlareFx.petalGrowth(400F - 12F, 400), b = FlareFx.petalGrowth(400F - 4F, 400);
		assertTrue(a < 1F && a > b && b > 0F, "drawing back over the last 16 ticks: " + a + " then " + b);
		assertEquals(0F, FlareFx.petalGrowth(400F, 400), 1e-6F);
		assertEquals(0F, FlareFx.petalGrowth(500F, 400), 1e-6F, "and none after");
	}

	@Test
	void theHairsAreFlungOutAndGoneByTwelveTicks() {
		assertEquals(0F, FlareFx.hairReach(0F), 1e-6F);
		float peak = 0F;
		for (float t = 0F; t < 12F; t += 0.25F) peak = Math.max(peak, FlareFx.hairReach(t));
		assertTrue(peak > 1.5F && peak <= 1.8F, "they reach about 1.8 blocks: " + peak);
		assertEquals(0F, FlareFx.hairReach(FlareFx.HAIR_OUT), 1e-6F);
		assertEquals(0F, FlareFx.hairReach(30F), 1e-6F);
	}

	@Test
	void aLifeIsTheMessagesTicksOrSecondsOrTheDefault() {
		assertEquals(FlareFx.DEFAULT_LIFE, FlareFx.lifeTicks(0));
		assertEquals(FlareFx.DEFAULT_LIFE, FlareFx.lifeTicks(-5));
		assertEquals(400, FlareFx.lifeTicks(400), "20 s in ticks");
		assertEquals(800, FlareFx.lifeTicks(800), "40 s in ticks");
		assertEquals(400, FlareFx.lifeTicks(20), "20 can only be seconds");
		assertEquals(800, FlareFx.lifeTicks(40));
		assertEquals(FlareFx.MAX_LIFE, FlareFx.lifeTicks(90000), "never longer than a minute");
	}

	@Test
	void aMessageWithNoCellShowsNothingAndOneFlarePerThrower() {
		FlareFx fx = new FlareFx();
		fx.start(flare(5, 400));
		assertEquals(0, fx.count(), "no cell, nothing to draw");
		fx.start(flare(5, 400, new BlockPos(1, 2, 3)));
		fx.start(flare(6, 400, new BlockPos(4, 5, 6)));
		assertEquals(2, fx.count());
		fx.start(flare(5, 400, new BlockPos(7, 8, 9)));
		assertEquals(2, fx.count(), "the thrower's second flare replaces their first");
		for (int i = 0; i < 20; i++) fx.start(flare(100 + i, 400, new BlockPos(i, 0, 0)));
		assertTrue(fx.count() <= FlareFx.MAX, "no more than " + FlareFx.MAX + " at once: " + fx.count());
		fx.clear();
		assertEquals(0, fx.count());
	}

	@Test
	void theLureBurstsAsTheFlareArrives() {
		assertEquals(ArmDraw.NEVER, LureGeometry.burstAge(5, 500F));
		new FlareFx().start(flare(5, 400, new BlockPos(1, 2, 3)));
		assertEquals(0F, LureGeometry.burstAge(5, 500F), 1e-4F, "the thrower's lure bursts at the message's tick");
		assertEquals(ArmDraw.NEVER, LureGeometry.burstAge(6, 500F), "nobody else's does");
	}
}
