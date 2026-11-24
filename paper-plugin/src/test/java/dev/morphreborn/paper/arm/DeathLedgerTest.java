package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.DeathLedger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Death with an arm: in every combination the stash comes out exactly once and the arm never does, whether the death
 * goes through or another plugin cancels it. Items are strings here; the game's handling is modelled as Paper does it.
 */
class DeathLedgerTest {
	private static final String ARM = "arm", STASH = "stash", OTHER = "torch";

	/** Where the arm is listed before the death handler runs. */
	private enum Where { DROPS, KEPT, NEITHER }

	/** The world after the death: the items dropped and the inventory the player has afterwards. */
	private record Outcome(List<String> world, List<String> inventory) {
		long count(String item) {
			return world.stream().filter(item::equals).count() + inventory.stream().filter(item::equals).count();
		}
	}

	private static Outcome die(boolean keepInventory, Where where, boolean stashEmpty, boolean cancelled) {
		// the live inventory: the arm in its home slot, another item beside it
		List<String> live = new ArrayList<>(List.of(ARM, OTHER));
		// as the game fills the event: drops are the inventory unless keepInventory
		List<String> drops = new ArrayList<>(keepInventory ? List.of() : List.of(OTHER));
		List<String> kept = new ArrayList<>();
		if (where == Where.DROPS) drops.add(0, ARM);
		if (where == Where.KEPT) kept.add(ARM);
		int dropsIndex = drops.indexOf(ARM), keepIndex = kept.indexOf(ARM);

		DeathLedger.Plan plan = DeathLedger.plan(keepInventory, dropsIndex, keepIndex, stashEmpty);
		apply(drops, dropsIndex, plan.replaceInDrops() && !plan.removeOnly());
		apply(kept, keepIndex, plan.replaceInKept() && !plan.removeOnly());
		if (plan.writeSlot()) live.set(0, stashEmpty ? "" : STASH);

		if (cancelled) return new Outcome(List.of(), live);
		if (keepInventory) return new Outcome(drops, live);
		// keepInventory off: the drops spawn; the inventory is cleared except listed items still matching the live slots
		List<String> after = new ArrayList<>();
		for (String item : live) {
			if (!item.isEmpty() && kept.remove(item)) after.add(item);
		}
		return new Outcome(drops, after);
	}

	/** The executor's part for one list: every arm out, then the stash in the arm's place (appended when it wasn't listed). */
	private static void apply(List<String> list, int index, boolean putStash) {
		list.removeIf(ARM::equals);
		if (!putStash) return;
		if (index >= 0) list.add(Math.min(index, list.size()), STASH);
		else list.add(STASH);
	}

	@Test
	void stashExactlyOnceNoArm() {
		for (boolean keep : new boolean[] {true, false}) {
			for (Where where : Where.values()) {
				for (boolean empty : new boolean[] {true, false}) {
					for (boolean cancelled : new boolean[] {false, true}) {
						Outcome o = die(keep, where, empty, cancelled);
						String what = "keepInventory " + keep + ", arm in " + where + ", stash " + (empty ? "empty" : "an item") + (cancelled ? ", cancelled" : "");
						assertEquals(0, o.count(ARM), what + ": no arm anywhere");
						assertEquals(empty ? 0 : 1, o.count(STASH), what + ": the stash once");
						assertEquals(1, o.count(OTHER), what + ": everything else untouched");
					}
				}
			}
		}
	}

	@Test
	void replacesInPlace() {
		DeathLedger.Plan drops = DeathLedger.plan(false, 3, -1, false);
		assertTrue(drops.replaceInDrops());
		assertTrue(drops.writeSlot());
		DeathLedger.Plan kept = DeathLedger.plan(true, -1, 2, false);
		assertTrue(kept.replaceInKept());
		DeathLedger.Plan empty = DeathLedger.plan(false, 0, -1, true);
		assertTrue(empty.removeOnly());
		assertTrue(empty.writeSlot(), "the slot is written with air");
	}
}
