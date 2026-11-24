package dev.morphreborn.client.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.net.Wire;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** An arm state's timeline on the client clock: progress at its edges, times relative to arrival, recall and lantern. */
class ArmStateTest {
	private static final UUID WHO = new UUID(1, 2);

	@BeforeEach
	void reset() {
		Arms.clear();
		Arms.TICK = 500;
	}

	private static ArmState state(String form, int state, int time, int duration, boolean lit, int lanternTime, int recall) {
		return new ArmState(WHO, 300, form, "", 2, state, time, duration, 3, ArmProtocol.WHY_CHOSEN, lit, lit ? 1 : 0, lanternTime, recall, Arms.TICK);
	}

	private static void arrive(String form, String from, int state, int time, int duration, int slot, int why, boolean lit, int lanternTime, int recall) {
		Wire.In in = new ArmWireTest.Enc(Wire.S_ARM).arm(WHO, 300, form, from, 2, state, time, duration, slot, why, lit, lit ? 2 : 0, lanternTime, recall).in();
		Arms.handleState(in);
	}

	@Test
	void progressAtTheEdges() {
		ArmState s = state("katana", ArmProtocol.STATE_FORMING, 0, 14, false, 0, 0);
		assertEquals(0F, s.progress(0F), 1e-6, "a fresh forming starts at 0");
		assertEquals(0.5F / 14F, s.progress(0.5F), 1e-6);
		Arms.TICK += 13;
		assertEquals(13.5F / 14F, s.progress(0.5F), 1e-6);
		Arms.TICK += 1;
		assertEquals(1F, s.progress(0F), 1e-6, "exactly at its length");
		Arms.TICK += 100;
		assertEquals(1F, s.progress(0.99F), 1e-6, "never past 1");
		assertEquals(1F, state("katana", ArmProtocol.STATE_FORMED, 0, 0, false, 0, 0).progress(0.3F), "resting has no timeline");
		assertEquals(1F, state("", ArmProtocol.STATE_NONE, 0, 0, false, 0, 0).progress(0F));
		assertEquals(1F, state("katana", ArmProtocol.STATE_RETRACTING, 5, 0, false, 0, 0).progress(0F), "a timed state sent with no length counts as done");
	}

	@Test
	void progressNeverNegative() {
		// a clock that went back (it never should) still gives 0, not a negative progress
		ArmState s = state("katana", ArmProtocol.STATE_MELTING, 0, 20, false, 0, 0);
		Arms.TICK -= 5;
		assertEquals(0F, s.elapsed(0F), 1e-6);
		assertEquals(0F, s.progress(0F), 1e-6);
	}

	@Test
	void timesAreRelativeToArrival() {
		Arms.TICK = 100;
		ArmState s = state("pickaxe", ArmProtocol.STATE_RESHAPING, 5, 16, false, 0, 0);
		assertEquals(95, s.startTick, "5 ticks had passed before it came");
		assertEquals(5F, s.elapsed(0F), 1e-6);
		Arms.TICK = 110;
		assertEquals(15.25F, s.elapsed(0.25F), 1e-6);
		assertEquals(15.25F / 16F, s.progress(0.25F), 1e-6);
		// late in a long game the age stays exact, which a float time would not
		Arms.TICK = 40_000_000L;
		ArmState late = state("pickaxe", ArmProtocol.STATE_FORMING, 3, 14, false, 0, 0);
		Arms.TICK += 2;
		assertEquals(5.125F, late.elapsed(0.125F), 1e-6);
	}

	@Test
	void recallCountsDown() {
		Arms.TICK = 50;
		ArmState s = state("", ArmProtocol.STATE_NONE, 0, 0, false, 0, 160);
		assertEquals(210, s.recallUntil);
		assertEquals(160F, s.recallLeft(0F), 1e-6);
		Arms.TICK = 100;
		assertEquals(109.5F, s.recallLeft(0.5F), 1e-6);
		Arms.TICK = 210;
		assertEquals(0F, s.recallLeft(0F), 1e-6);
		Arms.TICK = 300;
		assertEquals(0F, s.recallLeft(0F), 1e-6, "never negative");
		assertEquals(0F, state("", ArmProtocol.STATE_NONE, 0, 0, false, 0, 0).recallLeft(0F), 1e-6, "no recall");
	}

	@Test
	void aRecallKeepsANoneUntilItEnds() {
		arrive("", "", ArmProtocol.STATE_NONE, 0, 0, 4, ArmProtocol.WHY_SLOT, false, 0, 40);
		ArmState s = Arms.state(WHO);
		assertNotNull(s, "the bracelet needs the state while the recall runs");
		assertFalse(s.armed());
		assertEquals(4, s.slot);
		for (int i = 0; i < 39; i++) Arms.tick();
		assertNotNull(Arms.state(WHO));
		Arms.tick();
		assertNull(Arms.state(WHO), "gone once the recall is over");
		assertNull(Arms.stateByEntity(300));
	}

	@Test
	void aLitLanternKeepsANone() {
		arrive("", "", ArmProtocol.STATE_NONE, 0, 0, -1, ArmProtocol.WHY_CHOSEN, true, 30, 0);
		ArmState s = Arms.state(WHO);
		assertNotNull(s);
		assertEquals(30F, s.lanternAge(0F), 1e-6);
		assertEquals(2, s.lanternLevel);
		for (int i = 0; i < 200; i++) Arms.tick();
		assertNotNull(Arms.state(WHO), "a lit lantern stays until the server puts it out");
		arrive("", "", ArmProtocol.STATE_NONE, 0, 0, -1, ArmProtocol.WHY_CHOSEN, false, 0, 0);
		// put out: it stays while the lure draws back into the arm (2026-11-16), then goes
		ArmState out = Arms.state(WHO);
		assertNotNull(out, "kept for the lantern's retract animation");
		assertTrue(out.lanternGoingOut(Arms.TICK));
		assertTrue(out.lanternDrawAge(0F) < 0F, "the lure is told to play its growth backwards");
		for (int i = 0; i < ArmState.LANTERN_OUT_TICKS; i++) Arms.tick();
		assertNull(Arms.state(WHO), "gone once the lantern has drawn back");
	}

	@Test
	void aFinishedFormingRestsAsFormed() {
		arrive("katana", "", ArmProtocol.STATE_FORMING, 10, 14, 0, ArmProtocol.WHY_CHOSEN, false, 0, 0);
		for (int i = 0; i < 3; i++) Arms.tick();
		assertEquals(ArmProtocol.STATE_FORMING, Arms.state(WHO).state);
		Arms.tick();
		ArmState formed = Arms.state(WHO);
		assertEquals(ArmProtocol.STATE_FORMED, formed.state, "the timeline ran out before the server's FORMED came");
		assertEquals("katana", formed.form);
		assertEquals(0F, formed.elapsed(0F), 1e-6, "formed counts from the forming's end");
		assertEquals(1F, formed.progress(0F));
		assertTrue(formed.armed());
		assertEquals(formed, Arms.stateByEntity(300));
	}

	@Test
	void aReshapeRestsAsTheNewForm() {
		arrive("pickaxe", "katana", ArmProtocol.STATE_RESHAPING, 0, 16, 0, ArmProtocol.WHY_CHOSEN, false, 0, 0);
		assertEquals("katana", Arms.state(WHO).fromForm);
		for (int i = 0; i < 16; i++) Arms.tick();
		ArmState s = Arms.state(WHO);
		assertEquals(ArmProtocol.STATE_FORMED, s.state);
		assertEquals("pickaxe", s.form);
		assertEquals("", s.fromForm);
	}

	@Test
	void aLostRetractEndEventuallyEnds() {
		arrive("katana", "", ArmProtocol.STATE_RETRACTING, 0, 12, 0, ArmProtocol.WHY_CHOSEN, false, 0, 0);
		ArmState s = Arms.state(WHO);
		assertTrue(s.armed(), "the slot holds the arm until the retract is done");
		assertEquals("katana", s.heldForm());
		for (int i = 0; i < 12; i++) Arms.tick();
		assertNotNull(Arms.state(WHO), "the server's NONE gets a moment to arrive");
		assertEquals(ArmProtocol.STATE_RETRACTING, Arms.state(WHO).state);
		for (int i = 0; i < 20; i++) Arms.tick();
		assertNull(Arms.state(WHO), "with no lantern or recall nothing is left");
	}

	@Test
	void aMeltKeepsTheLantern() {
		arrive("katana", "", ArmProtocol.STATE_MELTING, 0, 20, 0, ArmProtocol.WHY_DEPLETED, true, 5, 0);
		for (int i = 0; i < 40; i++) Arms.tick();
		ArmState s = Arms.state(WHO);
		assertNotNull(s);
		assertEquals(ArmProtocol.STATE_NONE, s.state);
		assertFalse(s.armed());
		assertTrue(s.lanternLit);
	}

	@Test
	void heldFormFallsBackToTheFormerOne() {
		ArmState s = new ArmState(WHO, 1, "", "katana", 1, ArmProtocol.STATE_RETRACTING, 0, 12, 0, 0, false, 0, 0, 0, Arms.TICK);
		assertEquals("katana", s.heldForm());
		assertTrue(s.timed());
		assertFalse(state("katana", ArmProtocol.STATE_FORMED, 0, 0, false, 0, 0).timed());
	}

	@Test
	void durationsMatchTheProtocol() {
		assertEquals(40, ArmProtocol.duration(ArmProtocol.STATE_FORMING));
		assertEquals(20, ArmProtocol.duration(ArmProtocol.STATE_QUICKFORM));
		assertEquals(32, ArmProtocol.duration(ArmProtocol.STATE_RESHAPING));
		assertEquals(30, ArmProtocol.duration(ArmProtocol.STATE_RETRACTING));
		assertEquals(40, ArmProtocol.duration(ArmProtocol.STATE_MELTING));
		assertEquals(0, ArmProtocol.duration(ArmProtocol.STATE_FORMED));
		assertEquals(0, ArmProtocol.duration(ArmProtocol.STATE_NONE));
		assertTrue(ArmProtocol.usable(ArmProtocol.STATE_QUICKFORM));
		assertFalse(ArmProtocol.usable(ArmProtocol.STATE_MELTING), "nothing digs or hits while it melts");
		assertTrue(ArmProtocol.statefulFx(ArmProtocol.FX_HARPOON));
		assertFalse(ArmProtocol.statefulFx(ArmProtocol.FX_PULSE));
		assertEquals(32.0, ArmProtocol.fxRadius(ArmProtocol.FX_BREAK));
		assertEquals("quickform", ArmProtocol.stateName(ArmProtocol.STATE_QUICKFORM));
		assertEquals(0x7A00012C, ArmProtocol.crackSourceId(300));
	}

	@Test
	void armClockAges() {
		Arms.TICK = 1234;
		assertEquals(1234.5F, Arms.time(0.5F), 1e-3);
		assertEquals(4.5F, Arms.age(1230, 0.5F), 1e-6);
	}
}
