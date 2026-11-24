package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.ArmSlots;
import dev.morphreborn.paper.arm.rules.ArmSlots.Binding;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The arm slots' rules (2026-11-16): one slot per form, a different form replaces a bound slot, and a damaged save still reads. */
class ArmSlotsTest {
	@Test
	void startsEmpty() {
		assertTrue(ArmSlots.EMPTY.isEmpty());
		assertEquals(0, ArmSlots.EMPTY.size());
		assertNull(ArmSlots.EMPTY.formAt(0));
		assertEquals(-1, ArmSlots.EMPTY.slotOf("katana"));
		assertEquals("", ArmSlots.EMPTY.encode());
	}

	@Test
	void bindAndLookUp() {
		ArmSlots s = ArmSlots.EMPTY.bind(3, "katana").bind(0, "pickaxe");
		assertEquals("katana", s.formAt(3));
		assertEquals("pickaxe", s.formAt(0));
		assertEquals(3, s.slotOf("katana"));
		assertEquals(0, s.slotOf("pickaxe"));
		assertNull(s.formAt(1));
		assertEquals(2, s.size());
		assertEquals(List.of(new Binding(0, "pickaxe"), new Binding(3, "katana")), s.bindings(), "listed by slot");
	}

	@Test
	void oneSlotPerForm() {
		ArmSlots s = ArmSlots.EMPTY.bind(2, "katana").bind(6, "katana");
		assertEquals(1, s.size());
		assertNull(s.formAt(2), "the form's old binding went");
		assertEquals("katana", s.formAt(6));
		assertEquals(6, s.slotOf("katana"));
	}

	@Test
	void aDifferentFormReplacesABoundSlot() {
		ArmSlots s = ArmSlots.EMPTY.bind(4, "katana").bind(4, "bow");
		assertEquals(1, s.size());
		assertEquals("bow", s.formAt(4));
		assertEquals(-1, s.slotOf("katana"), "the replaced form is bound nowhere");
	}

	@Test
	void movingAFormOntoABoundSlotLeavesOnlyTheMovedOne() {
		ArmSlots s = ArmSlots.EMPTY.bind(1, "katana").bind(5, "axe").bind(1, "axe");
		assertEquals(List.of(new Binding(1, "axe")), s.bindings(), "axe moved to 1, replacing katana, and left 5");
	}

	@Test
	void bindingTheSameAgainChangesNothing() {
		ArmSlots s = ArmSlots.EMPTY.bind(7, "whip");
		assertSame(s, s.bind(7, "whip"));
		assertSame(s, s.unbind(0));
		assertSame(s, s.unbindForm("bow"));
		assertSame(s, s.retain(f -> true));
	}

	@Test
	void unbind() {
		ArmSlots s = ArmSlots.EMPTY.bind(1, "katana").bind(2, "bow");
		assertEquals(List.of(new Binding(2, "bow")), s.unbind(1).bindings());
		assertEquals(List.of(new Binding(1, "katana")), s.unbindForm("bow").bindings());
		assertTrue(s.unbind(1).unbind(2).isEmpty());
		assertEquals(List.of(new Binding(1, "katana"), new Binding(2, "bow")), s.bindings(), "a value is never changed");
	}

	@Test
	void retainDropsFormsNoLongerOwned() {
		ArmSlots s = ArmSlots.EMPTY.bind(0, "katana").bind(1, "bow").bind(2, "axe");
		ArmSlots kept = s.retain(f -> !f.equals("bow"));
		assertEquals(List.of(new Binding(0, "katana"), new Binding(2, "axe")), kept.bindings());
		assertTrue(s.retain(f -> false).isEmpty());
	}

	@Test
	void onlyTheHotbarAndRealForms() {
		assertThrows(IllegalArgumentException.class, () -> ArmSlots.EMPTY.bind(-1, "katana"));
		assertThrows(IllegalArgumentException.class, () -> ArmSlots.EMPTY.bind(9, "katana"));
		assertThrows(IllegalArgumentException.class, () -> ArmSlots.EMPTY.bind(0, ""));
		assertTrue(ArmSlots.validSlot(0));
		assertTrue(ArmSlots.validSlot(8));
		assertFalse(ArmSlots.validSlot(9));
		assertEquals(9, ArmSlots.SLOTS);
	}

	@Test
	void everySlotCanBeBound() {
		ArmSlots s = ArmSlots.EMPTY;
		for (int i = 0; i < ArmSlots.SLOTS; i++) s = s.bind(i, "form" + i);
		assertEquals(9, s.size());
		for (int i = 0; i < ArmSlots.SLOTS; i++) assertEquals("form" + i, s.formAt(i));
	}

	@Test
	void rowsRoundTrip() {
		ArmSlots s = ArmSlots.EMPTY.bind(8, "whip").bind(0, "katana").bind(4, "sickle");
		assertEquals(s, ArmSlots.fromRows(s.toRows()));
		assertEquals(ArmSlots.EMPTY, ArmSlots.fromRows(ArmSlots.EMPTY.toRows()));
		assertEquals(ArmSlots.EMPTY, ArmSlots.fromRows(null));
	}

	@Test
	void aDamagedSaveStillGivesAValidValue() {
		ArmSlots s = ArmSlots.fromRows(List.of(
				new Binding(12, "katana"), // off the hotbar
				new Binding(-3, "bow"),
				new Binding(2, ""), // no form
				new Binding(1, "axe"),
				new Binding(5, "axe"), // the same form twice: the later wins
				new Binding(3, "pickaxe"),
				new Binding(3, "whip"))); // the same slot twice: the later wins
		assertEquals(List.of(new Binding(3, "whip"), new Binding(5, "axe")), s.bindings());
	}

	@Test
	void textRoundTrip() {
		ArmSlots s = ArmSlots.EMPTY.bind(0, "katana").bind(3, "bow");
		assertEquals("0:katana,3:bow", s.encode());
		assertEquals(s, ArmSlots.decode(s.encode()));
		assertEquals(ArmSlots.EMPTY, ArmSlots.decode(""));
		assertEquals(ArmSlots.EMPTY, ArmSlots.decode(null));
		assertEquals(s, ArmSlots.decode("0:katana, 3:bow,x:y,9:nope,:axe,7"), "what can't be a binding is skipped");
	}

	@Test
	void equalityIsByBindings() {
		assertEquals(ArmSlots.EMPTY.bind(1, "a").bind(2, "b"), ArmSlots.EMPTY.bind(2, "b").bind(1, "a"));
		assertEquals(ArmSlots.EMPTY.bind(1, "a").hashCode(), ArmSlots.EMPTY.bind(1, "a").hashCode());
		assertFalse(ArmSlots.EMPTY.bind(1, "a").equals(ArmSlots.EMPTY.bind(2, "a")));
	}

	@Test
	void anAdminsArmBindsOnlyWhatThePlayerCouldFormThemselves() {
		assertTrue(ArmSlots.adminBinds(1, false, 0), "owned the normal way: a plain forced arm binds like their own");
		assertTrue(ArmSlots.adminBinds(3, false, 0));
		assertNull(ArmSlots.adminOneOffReason(2, false, 0));
	}

	@Test
	void anAdminsArmThePlayerDoesNotOwnIsAOneOff() {
		assertFalse(ArmSlots.adminBinds(0, false, 0), "not theirs: the first tuck would drop the binding as 'isn't yours any more'");
		assertFalse(ArmSlots.adminBinds(0, false, 2));
		assertEquals("they don't have that form themselves", ArmSlots.adminOneOffReason(0, true, 3));
	}

	@Test
	void anAdminsFreeOrLevelOverrideIsAOneOff() {
		assertFalse(ArmSlots.adminBinds(2, true, 0), "a binding re-forms at normal cost");
		assertFalse(ArmSlots.adminBinds(2, false, 3), "and at the player's own level");
		assertFalse(ArmSlots.adminBinds(2, true, 3));
		assertEquals("it is free", ArmSlots.adminOneOffReason(2, true, 0));
		assertEquals("it has a set level", ArmSlots.adminOneOffReason(2, false, 1));
		assertEquals("it is free and has a set level", ArmSlots.adminOneOffReason(2, true, 1));
	}
}
