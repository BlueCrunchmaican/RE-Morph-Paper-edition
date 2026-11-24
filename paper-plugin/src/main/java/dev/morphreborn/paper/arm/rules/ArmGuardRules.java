package dev.morphreborn.paper.arm.rules;

/**
 * What every event that could move, store, copy or lose an arm does with it (spec section 3.4, rows G1 to G27). The arm
 * is the player's own arm, so it can never be dropped, stored, traded, crafted with or held by anything else, and
 * anything that changes the player's situation around it (a world change, a command that acts on the held item, death)
 * puts the arm back into a plain hand first. The listener turns each event into an {@link Act} and a {@link GuardCtx}
 * and carries out the {@link Decision}; the rules themselves are here, pure, so every row is unit tested both ways
 * without a server.
 * <p>
 * Fields of {@link GuardCtx} that an act doesn't use are ignored, so the listener only has to fill in what it knows.
 */
public final class ArmGuardRules {
	private ArmGuardRules() {}

	/** Events the guards see, each named for the row it belongs to. */
	public enum Act {
		/** G1: Q or Ctrl+Q, or anything else that throws an item out of the player's inventory. stackIsArm: the dropped item. */
		DROP,
		/**
		 * G2/G2b: F. armed: a tool session; stackIsArm: the main hand holds the session arm (or any arm), where F is its special
		 * and Sneak+F its special with sneaking set (2026-11-16: so the whip's Whirl works with the lantern lit);
		 * sneaking and lanternLit step the lantern's brightness only with no arm in hand; retracting: the arm is retracting or melting.
		 */
		SWAP_HANDS,
		/** G3: a plain or shift click. involvesHome: the clicked slot is an armed holder's home slot; stackIsArm: current or cursor is an arm. */
		CLICK,
		/** G3: a number key. involvesHome: the clicked slot or the hotbar button is the home slot. */
		NUMBER_KEY,
		/** G3: F inside an inventory (button 40). involvesHome: the clicked slot is home (the offhand side can't be). */
		SWAP_OFFHAND,
		/** G3: creative middle click. stackIsArm: the clicked stack is an arm. */
		CLONE_STACK,
		/** G3: double click. stackIsArm: the cursor is an arm. */
		COLLECT_TO_CURSOR,
		/** G4: a drag. stackIsArm: the old cursor or a new item is an arm; involvesHome: a raw slot maps to home. */
		DRAG,
		/** G5: a creative client setting a slot. stackIsArm: the cursor is an arm; involvesHome: the slot is home (raw 36 + i). */
		CREATIVE_SET,
		/** G5: a creative client dropping a stack (slot -1). stackIsArm: the stack is an arm. */
		CREATIVE_DROP,
		/** G6: pick block or pick entity. */
		PICK_BLOCK,
		/** G7: the held slot changed. involvesHome: the previous slot was home. */
		HELD_SLOT,
		/** G8: right click on a shelf, a decorated pot, a sign or a hanging sign. stackIsArm: the main hand holds an arm. */
		USE_STORAGE_BLOCK,
		/** G9: right click on an item frame, glow item frame, armour stand, allay or sulfur cube. stackIsArm: the main hand holds an arm. */
		USE_ITEM_TAKER,
		/** G10: an armour stand manipulated or an item frame changed. stackIsArm: an arm is involved. */
		HOLDER_CHANGE,
		/** G11: the item would lose durability (strip, till, path, scrape). The listener bills per-transform times the damage. */
		ITEM_DAMAGE,
		/** G12: consuming the item (the bow arm's draw is a consumable). */
		CONSUME,
		/** G12: Mending repairing the item. */
		MEND,
		/** G12: the item breaking (can't be cancelled; never reached, the damage stays below the max). */
		BREAK_ITEM,
		/** G13: a crafting, anvil, grindstone, smithing or enchanting preview, or a crafter. stackIsArm: any input is an arm. */
		PREPARE,
		/** G14: a hopper or other container moving an item. */
		MOVE_ITEM,
		/** G14: a dispenser or dropper shooting an item. */
		DISPENSE,
		/** G15: an item entity about to spawn (a catch-all, creative slot -1 included). */
		ITEM_SPAWN,
		/** G16: an entity or player picking an item entity up. */
		PICKUP,
		/** G17: a non-player's equipment changed. stackIsArm: it now holds an arm. */
		EQUIPMENT_CHANGED,
		/** G18: an inventory opened. stackIsArm: its top inventory holds arms. */
		INVENTORY_OPEN,
		/** G19: a command typed. commandRetracts: CommandGuard says this command needs a plain hand. */
		COMMAND,
		/** G20: a teleport. crossWorld: to another world; inventorySafety: the setting resolves on. */
		TELEPORT,
		/** G21: a game mode change. toSpectator: the new mode is spectator; inventorySafety: the setting resolves on. */
		GAME_MODE,
		/** G22: death. */
		DEATH,
		/** G23: leaving the server. */
		QUIT,
		/** G24: a morph starting (arms are for your own body only). */
		MORPH_START,
		/** G25: RE:Morph being disabled. */
		PLUGIN_DISABLE,
		/** G26: breaking a block. */
		BLOCK_BREAK,
		/** G26: starting to dig a block. */
		BLOCK_DAMAGE,
		/** G26: a melee hit dealt by an armed player. */
		MELEE,
		/** G26: any other right or left click with the arm in hand. stackIsArm: the arm is in hand. */
		INTERACT,
		/**
		 * G27: a slot of the player's inventory changed. stackIsArm: the new item is an arm; involvesHome: the slot is home;
		 * armed: there is a session.
		 */
		SLOT_CHANGE
	}

	/**
	 * What the listener does: let it be; cancel it; cancel it and resend the inventory next tick (the client already
	 * moved the item on its screen); retract the arm (forced, at once) and let the event go on; fire the form's special;
	 * step the lantern's brightness (no tool out only); delete the arm stacks found; cancel and delete them; or deny the use of the clicked
	 * block while still allowing the item's own use.
	 */
	public enum Decision { ALLOW, CANCEL, CANCEL_RESYNC, RETRACT_THEN_ALLOW, SPECIAL, BRIGHTNESS, PURGE, CANCEL_PURGE, DENY_USE }

	/**
	 * What the listener knows about the event (see each {@link Act} for what each flag means there).
	 *
	 * @param armed the player has a tool session (an arm formed, forming, retracting or melting)
	 * @param involvesHome the event touches the session's home slot
	 * @param stackIsArm the item in question carries the arm tag
	 * @param sneaking the player is sneaking
	 * @param lanternLit the player's lantern is lit
	 * @param accessoryOn the player has an accessory on (the lantern, shield, spyglass or flint: the lantern counts too, but {@code lanternLit} alone does as well)
	 * @param commandRetracts CommandGuard says the typed command needs a plain hand
	 * @param crossWorld a teleport to another world
	 * @param inventorySafety arms.inventory-safety resolves on (on, or auto with a per-world inventory plugin)
	 * @param toSpectator a game mode change to spectator
	 * @param retracting the arm is retracting or melting
	 */
	public record GuardCtx(boolean armed, boolean involvesHome, boolean stackIsArm, boolean sneaking, boolean lanternLit, boolean commandRetracts,
			boolean crossWorld, boolean inventorySafety, boolean toSpectator, boolean retracting, boolean accessoryOn) {
		/** Nothing set: a starting point for the listener's {@code with...} copies and for tests. */
		public static final GuardCtx NONE = new GuardCtx(false, false, false, false, false, false, false, false, false, false, false);

		/** Some accessory is on, the lantern or another: what ends with the arm at a world change, death, quitting or a morph. */
		public boolean anyAccessory() {
			return lanternLit || accessoryOn;
		}

		public GuardCtx withAccessoryOn(boolean v) {
			return new GuardCtx(armed, involvesHome, stackIsArm, sneaking, lanternLit, commandRetracts, crossWorld, inventorySafety, toSpectator, retracting, v);
		}

		public GuardCtx withArmed(boolean v) {
			return new GuardCtx(v, involvesHome, stackIsArm, sneaking, lanternLit, commandRetracts, crossWorld, inventorySafety, toSpectator, retracting, accessoryOn);
		}

		public GuardCtx withHome(boolean v) {
			return new GuardCtx(armed, v, stackIsArm, sneaking, lanternLit, commandRetracts, crossWorld, inventorySafety, toSpectator, retracting, accessoryOn);
		}

		public GuardCtx withArm(boolean v) {
			return new GuardCtx(armed, involvesHome, v, sneaking, lanternLit, commandRetracts, crossWorld, inventorySafety, toSpectator, retracting, accessoryOn);
		}

		public GuardCtx withSneaking(boolean v) {
			return new GuardCtx(armed, involvesHome, stackIsArm, v, lanternLit, commandRetracts, crossWorld, inventorySafety, toSpectator, retracting, accessoryOn);
		}

		public GuardCtx withLanternLit(boolean v) {
			return new GuardCtx(armed, involvesHome, stackIsArm, sneaking, v, commandRetracts, crossWorld, inventorySafety, toSpectator, retracting, accessoryOn);
		}

		public GuardCtx withCommandRetracts(boolean v) {
			return new GuardCtx(armed, involvesHome, stackIsArm, sneaking, lanternLit, v, crossWorld, inventorySafety, toSpectator, retracting, accessoryOn);
		}

		public GuardCtx withCrossWorld(boolean v) {
			return new GuardCtx(armed, involvesHome, stackIsArm, sneaking, lanternLit, commandRetracts, v, inventorySafety, toSpectator, retracting, accessoryOn);
		}

		public GuardCtx withInventorySafety(boolean v) {
			return new GuardCtx(armed, involvesHome, stackIsArm, sneaking, lanternLit, commandRetracts, crossWorld, v, toSpectator, retracting, accessoryOn);
		}

		public GuardCtx withToSpectator(boolean v) {
			return new GuardCtx(armed, involvesHome, stackIsArm, sneaking, lanternLit, commandRetracts, crossWorld, inventorySafety, v, retracting, accessoryOn);
		}

		public GuardCtx withRetracting(boolean v) {
			return new GuardCtx(armed, involvesHome, stackIsArm, sneaking, lanternLit, commandRetracts, crossWorld, inventorySafety, toSpectator, v, accessoryOn);
		}
	}

	public static Decision decide(Act act, GuardCtx c) {
		boolean home = c.armed() && c.involvesHome();
		return switch (act) {
			case DROP, ITEM_SPAWN, CONSUME, MEND, BREAK_ITEM, HOLDER_CHANGE, USE_ITEM_TAKER, ITEM_DAMAGE -> c.stackIsArm() ? Decision.CANCEL : Decision.ALLOW;
			case SWAP_HANDS -> swapHands(c);
			// The client already moved the item on its own screen, so a cancel alone would leave it showing in the wrong place.
			case CLICK, NUMBER_KEY, SWAP_OFFHAND, CREATIVE_SET -> home || c.stackIsArm() ? Decision.CANCEL_RESYNC : Decision.ALLOW;
			case CLONE_STACK, COLLECT_TO_CURSOR, CREATIVE_DROP -> c.stackIsArm() ? Decision.CANCEL_RESYNC : Decision.ALLOW;
			case DRAG -> home || c.stackIsArm() ? Decision.CANCEL : Decision.ALLOW;
			// creative pick block can overwrite the selected slot (Inventory.addAndPickItem)
			case PICK_BLOCK -> c.armed() ? Decision.CANCEL : Decision.ALLOW;
			// scrolling away retracts at once (decision D4: the arm is always the held item); the binding stays, and a bound slot scrolled onto forms again
			case HELD_SLOT -> home ? Decision.RETRACT_THEN_ALLOW : Decision.ALLOW;
			// shelves, pots and signs would take or copy the held item; DENY keeps the arm's own use (none) and the block untouched
			case USE_STORAGE_BLOCK -> c.stackIsArm() ? (c.retracting() ? Decision.CANCEL : Decision.DENY_USE) : Decision.ALLOW;
			case PREPARE -> c.stackIsArm() ? Decision.CANCEL : Decision.ALLOW;
			// an arm outside a player is a stray: never let it go anywhere, and delete it
			case MOVE_ITEM, DISPENSE, PICKUP -> c.stackIsArm() ? Decision.CANCEL_PURGE : Decision.ALLOW;
			case EQUIPMENT_CHANGED, INVENTORY_OPEN -> c.stackIsArm() ? Decision.PURGE : Decision.ALLOW;
			case COMMAND -> c.armed() && c.commandRetracts() ? Decision.RETRACT_THEN_ALLOW : Decision.ALLOW;
			// Same-world teleports keep the arm (TARDIS trips, ender pearls); another world only with inventory safety on,
			// where a per-world inventory plugin would otherwise swap inventories with the arm in one of them.
			case TELEPORT -> (c.armed() || c.anyAccessory()) && c.crossWorld() && c.inventorySafety() ? Decision.RETRACT_THEN_ALLOW : Decision.ALLOW;
			case GAME_MODE -> (c.armed() || c.anyAccessory()) && (c.toSpectator() || c.inventorySafety()) ? Decision.RETRACT_THEN_ALLOW : Decision.ALLOW;
			case DEATH, QUIT, MORPH_START -> c.armed() || c.anyAccessory() ? Decision.RETRACT_THEN_ALLOW : Decision.ALLOW;
			case PLUGIN_DISABLE -> Decision.RETRACT_THEN_ALLOW;
			// nothing can be dug, hit or used while the arm retracts or melts (decision D26)
			case BLOCK_BREAK, BLOCK_DAMAGE, MELEE -> c.armed() && c.retracting() ? Decision.CANCEL : Decision.ALLOW;
			case INTERACT -> c.armed() && c.retracting() && c.stackIsArm() ? Decision.CANCEL : Decision.ALLOW;
			case SLOT_CHANGE -> slotChange(c);
		};
	}

	/**
	 * G2/G2b: F with the arm in hand never swaps; it fires the special, and Sneak+F fires it with sneaking set
	 * (2026-11-16: the tool's own sneak move, the whip's Whirl or the bow's tip cycle, wins even with the lantern lit; it used to
	 * be swallowed by the lantern's brightness). The lantern's brightness steps only with NO tool out, sneaking with it lit; with a
	 * tool out it is set by scrolling over the lantern in the wheel or by /morph arm lantern bright N.
	 */
	private static Decision swapHands(GuardCtx c) {
		if (c.stackIsArm()) {
			if (!c.armed()) return Decision.CANCEL_PURGE; // a foreign arm: not this player's session
			if (c.retracting()) return Decision.CANCEL; // nothing fires while it retracts or melts
			return Decision.SPECIAL;
		}
		// No arm in hand: plain F still swaps, Sneak+F with the lantern lit steps its brightness.
		return c.sneaking() && c.lanternLit() ? Decision.BRIGHTNESS : Decision.ALLOW;
	}

	/**
	 * G27: an arm in any slot but home is a stray, deleted; home losing its arm without a retract is a lost arm, which a
	 * forced retract repairs (its FINISH sees the home slot without the session arm and gives the stash back elsewhere).
	 */
	private static Decision slotChange(GuardCtx c) {
		if (c.stackIsArm() && !(c.armed() && c.involvesHome())) return Decision.PURGE;
		if (c.armed() && c.involvesHome() && !c.stackIsArm() && !c.retracting()) return Decision.RETRACT_THEN_ALLOW;
		return Decision.ALLOW;
	}
}
