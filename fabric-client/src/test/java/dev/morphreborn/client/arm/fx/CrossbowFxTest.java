package dev.morphreborn.client.arm.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.geom.ArmDraw;
import dev.morphreborn.client.arm.geom.CrossbowGeometry;
import dev.morphreborn.client.arm.model.ArmDrawFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The crossbow's world side (FX_CROSSBOW, 2026-11-16): the contract (the moves in {@code arg}), what the effect hides, and what it tells the
 * weapon in the hand (a shot empties the groove, a regrow grows the bolt over the server's ticks, a burst flashes the muzzle).
 */
class CrossbowFxTest {
	private static final int OWNER = 41, BOLT = 7001;
	private CrossbowFx fx;

	@BeforeEach
	void fresh() {
		fx = new CrossbowFx();
		fx.clear();
		Arms.TICK = 6000;
	}

	@AfterEach
	void clean() {
		fx.clear();
	}

	private ArmFxEvent event(int arg, int ticks, int... ids) {
		return seeded(arg, ticks, 4242, ids);
	}

	private ArmFxEvent seeded(int arg, int ticks, int seed, int... ids) {
		return new ArmFxEvent(OWNER, ArmProtocol.FX_CROSSBOW, 2, ticks, seed, ids, new long[0], arg, Arms.TICK);
	}

	/** A draw of {@code OWNER}'s crossbow at {@code ticksAfter} ticks after now, with the groove empty or not (the stack's word). */
	private static ArmDraw draw(float ticksAfter, boolean empty) {
		return new ArmDraw(ArmProtocol.CROSSBOW, 2, 0, ArmDraw.CTX_TP, false, false, false, ArmDrawFactory.clock(0F) + ticksAfter, OWNER, ArmProtocol.STATE_FORMED, 1F, "", 0F,
				0F, false, 1F, ArmDraw.FULL, 0F, ArmDraw.NEVER, ArmDraw.NO_SPECIAL, ArmDraw.NEVER, ArmDraw.NEVER, new float[0], 0, false, 0F, 0F, true, true, false, null, 0F, -1F,
				0F, null, 0xF000F0, 1F, 0, 0, null, empty);
	}

	@Test
	void theContractNumbers() {
		assertEquals(18, ArmProtocol.FX_CROSSBOW, "the kind the server sends");
		assertEquals(1, CrossbowFx.BOLT);
		assertEquals(2, CrossbowFx.SPINES);
		assertEquals(3, CrossbowFx.REGROW);
		assertEquals(1, fx.kinds().length);
		assertEquals(ArmProtocol.FX_CROSSBOW, fx.kinds()[0]);
		assertTrue(ArmFx.HANDLERS.contains("dev.morphreborn.client.arm.fx.CrossbowFx"));
	}

	@Test
	void aBoltHidesTheProjectileAndEmptiesTheGroove() {
		assertFalse(CrossbowFx.hides(BOLT));
		fx.start(event(CrossbowFx.BOLT, 0, BOLT));
		assertTrue(CrossbowFx.hides(BOLT), "the vanilla model isn't drawn for it");
		assertFalse(CrossbowFx.hides(BOLT + 1));
		assertEquals(1, fx.active());
		assertEquals(0F, CrossbowGeometry.growth(draw(1F, false)), 0F, "the weapon's groove is empty at the shot, whatever the stack says yet");
		assertEquals(1F, CrossbowGeometry.growth(draw(5F, false)), 0F, "and the loaded stack is believed once it has settled");
		fx.clear();
		assertFalse(CrossbowFx.hides(BOLT));
		assertEquals(1F, CrossbowGeometry.growth(draw(1F, false)), 0F, "clearing forgets the marks");
	}

	@Test
	void aShotWithNoProjectileStillEmptiesTheGroove() {
		fx.start(event(CrossbowFx.BOLT, 0));
		assertEquals(0, fx.active());
		assertEquals(0F, CrossbowGeometry.growth(draw(1F, true)), 0F);
	}

	@Test
	void aSpineBurstIsOneMessageThatHidesAllItsSpines() {
		fx.start(event(CrossbowFx.SPINES, 3, 8001, 8002, 8003));
		for (int id = 8001; id <= 8003; id++) assertTrue(CrossbowFx.hides(id), "spine " + id);
		assertEquals(3, fx.active());
		// up to MAX_SPINES are drawn, whatever the server's spines setting; one past it is not
		int[] many = new int[CrossbowFx.MAX_SPINES + 1];
		for (int i = 0; i < many.length; i++) many[i] = 8100 + i;
		fx.start(event(CrossbowFx.SPINES, 3, many));
		assertTrue(CrossbowFx.hides(8100 + CrossbowFx.MAX_SPINES - 1));
		assertFalse(CrossbowFx.hides(8100 + CrossbowFx.MAX_SPINES));
		assertEquals(3 + CrossbowFx.MAX_SPINES, fx.active());
		// the burst is not a shot: the groove keeps its bolt
		assertEquals(1F, CrossbowGeometry.growth(draw(1F, false)), 0F);
	}

	@Test
	void aSpineTheServerHasNamedButNotAddedYetIsStillHiddenWhenItArrives() {
		// the burst names all its spines at once; the later ones enter the world on their own ticks, so for a while the client has no such entity
		// (in the tests there is no level at all): the hiding must not be dropped on the first tick for want of one
		fx.start(event(CrossbowFx.SPINES, 3, 8201, 8202, 8203));
		for (int i = 0; i < 6; i++) {
			Arms.TICK++;
			fx.tick();
		}
		for (int id = 8201; id <= 8203; id++) assertTrue(CrossbowFx.hides(id), "still waiting for " + id);
		// given up on after the wait (UNSEEN_TICKS plus the spacing for the burst's length), and then no longer hidden
		for (int i = 0; i < 80; i++) {
			Arms.TICK++;
			fx.tick();
		}
		assertEquals(0, fx.active());
		for (int id = 8201; id <= 8203; id++) assertFalse(CrossbowFx.hides(id), "forgotten " + id);
	}

	@Test
	void aBurstEmptiesTheGrooveWhateverARegrowHeld() {
		fx.start(seeded(CrossbowFx.REGROW, 25, 5, 25));
		Arms.TICK += 30;
		fx.start(event(CrossbowFx.SPINES, 3, 8301, 8302, 8303));
		assertEquals(0F, CrossbowGeometry.growth(draw(31F, true)), 0F, "no held-full bolt over an empty stack");
	}

	@Test
	void aRegrowResentWithTheSameSeedDoesNotRestartTheGrowth() {
		fx.start(seeded(CrossbowFx.REGROW, 25, 5, 25)); // ticks = the whole, ids = [left]
		assertEquals(0.4F, CrossbowGeometry.growth(draw(10F, true)), 1e-3F);
		Arms.TICK += 10; // the draws below are timed from here
		fx.start(seeded(CrossbowFx.REGROW, 25, 5, 15)); // the resend at +10
		assertEquals(0.4F, CrossbowGeometry.growth(draw(0F, true)), 1e-3F, "the same regrow: not back to 0");
		assertEquals(0.8F, CrossbowGeometry.growth(draw(10F, true)), 1e-3F);
		// the next regrow has its own seed and starts over
		Arms.TICK += 100;
		fx.start(seeded(CrossbowFx.REGROW, 25, 6, 25));
		assertEquals(0F, CrossbowGeometry.growth(draw(0F, true)), 1e-3F);
		assertEquals(0.5F, CrossbowGeometry.growth(draw(12.5F, true)), 1e-3F);
	}

	@Test
	void aViewerWhoCameIntoRangeMidRegrowStartsWhereTheServerIs() {
		fx.start(seeded(CrossbowFx.REGROW, 25, 31, 10)); // their first message: 10 of 25 left
		assertEquals(0.6F, CrossbowGeometry.growth(draw(0F, true)), 1e-3F);
		assertEquals(0.8F, CrossbowGeometry.growth(draw(5F, true)), 1e-3F);
		// a message with no ids (all the ticks left) starts from nothing
		fx.clear();
		fx.start(seeded(CrossbowFx.REGROW, 25, 32));
		assertEquals(0F, CrossbowGeometry.growth(draw(0F, true)), 1e-3F);
	}

	@Test
	void aRegrowGrowsTheBoltOverTheServersTicks() {
		fx.start(seeded(CrossbowFx.REGROW, 25, 7, 25));
		assertEquals(0, fx.active(), "nothing is drawn in the world");
		assertEquals(0F, CrossbowGeometry.growth(draw(0F, true)), 1e-3F);
		assertEquals(0.5F, CrossbowGeometry.growth(draw(12.5F, true)), 1e-3F);
		assertEquals(1F, CrossbowGeometry.growth(draw(25F, true)), 1e-3F);
		// a Quick Charge crossbow regrows faster
		fx.clear();
		Arms.TICK = 6100;
		fx.start(seeded(CrossbowFx.REGROW, 14, 8, 14));
		assertEquals(0.5F, CrossbowGeometry.growth(draw(7F, true)), 1e-3F);
	}

	@Test
	void anUnseenFlightGivesUpAndAnUnknownArgIsIgnored() {
		fx.start(event(99, 0, BOLT));
		assertEquals(0, fx.active());
		fx.start(event(CrossbowFx.BOLT, 0, BOLT));
		for (int i = 0; i < 60; i++) {
			Arms.TICK++;
			fx.tick();
		}
		assertEquals(0, fx.active(), "a projectile that never showed up is forgotten");
	}
}
