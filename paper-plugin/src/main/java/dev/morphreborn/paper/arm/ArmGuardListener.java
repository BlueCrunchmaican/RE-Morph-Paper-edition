package dev.morphreborn.paper.arm;

import dev.morphreborn.paper.arm.rules.ArmGuardRules;
import dev.morphreborn.paper.arm.rules.ArmGuardRules.Act;
import dev.morphreborn.paper.arm.rules.ArmGuardRules.Decision;
import dev.morphreborn.paper.arm.rules.ArmGuardRules.GuardCtx;
import dev.morphreborn.paper.arm.rules.ArmTiers;
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import io.papermc.paper.event.player.PlayerPickItemEvent;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Container;
import org.bukkit.block.Crafter;
import org.bukkit.command.Command;
import org.bukkit.entity.Allay;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SulfurCube;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareInventoryResultEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;

/**
 * Every event that could move, store, copy or lose an arm (spec 3.4, rows G1 to G27), and the player lifecycle around
 * it. The arm is the player's own arm, so it can never be dropped, stored, traded, crafted with or held by anything
 * else; anything that changes the player's situation around it (death, leaving, a morph, a world change under a
 * per-world inventory plugin, a command that acts on the held item) puts the arm back into a plain hand first.
 * <p>
 * Each handler only gathers what it knows into a {@link GuardCtx} and does what {@link ArmGuardRules#decide} says; the
 * rules themselves are pure and unit tested row by row. An arm stack found anywhere it shouldn't be (another slot, a
 * chest, the ground, a mob's hand) is a stray and is deleted: the real arm is only ever the session's own stack in its
 * home slot, and the item it replaced lives in the player's data, never in the arm, so nothing of value is lost.
 */
final class ArmGuardListener implements Listener {
	private final ArmManager arms;

	ArmGuardListener(ArmManager arms) {
		this.arms = arms;
	}

	private static Decision decide(Act act, GuardCtx ctx) {
		return ArmGuardRules.decide(act, ctx);
	}

	private static boolean isArm(@Nullable ItemStack stack) {
		return ArmItems.isArm(stack);
	}

	/** The click moved the item on the client's screen already; the next tick puts the real inventory back there. */
	private void resync(HumanEntity who) {
		if (who instanceof Player p) arms.plugin().getServer().getScheduler().runTask(arms.plugin(), () -> {
			if (p.isOnline()) p.updateInventory();
		});
	}

	// ------------------------------------------------------------------ G1 drop

	@EventHandler(priority = EventPriority.LOWEST)
	public void onDrop(PlayerDropItemEvent event) {
		if (decide(Act.DROP, GuardCtx.NONE.withArm(isArm(event.getItemDrop().getItemStack()))) == Decision.CANCEL) event.setCancelled(true);
	}

	// ------------------------------------------------------------------ G2 F: the tool's special (Sneak+F its sneak move), or the lantern's brightness with no tool out

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onSwapHands(PlayerSwapHandItemsEvent event) {
		Player p = event.getPlayer();
		ArmSession s = arms.session(p);
		ItemStack main = p.getInventory().getItemInMainHand();
		boolean arm = isArm(main);
		GuardCtx ctx = GuardCtx.NONE.withArmed(s != null && arms.isSessionArm(s, main)).withArm(arm).withSneaking(p.isSneaking())
				.withLanternLit(arms.isLit(p)).withAccessoryOn(arms.anyOn(p)).withRetracting(s != null && s.ending());
		switch (decide(Act.SWAP_HANDS, ctx)) {
			case SPECIAL -> {
				event.setCancelled(true);
				arms.special(p, p.isSneaking(), null);
			}
			case BRIGHTNESS -> {
				event.setCancelled(true);
				arms.stepBrightness(p, null);
			}
			case CANCEL -> event.setCancelled(true);
			case CANCEL_PURGE -> {
				event.setCancelled(true);
				arms.write(p, () -> p.getInventory().setItemInMainHand(null));
			}
			default -> {}
		}
	}

	// ------------------------------------------------------------------ G3, G5 clicks (creative clicks are InventoryCreativeEvents)

	@EventHandler(priority = EventPriority.LOWEST)
	public void onClick(InventoryClickEvent event) {
		if (!(event.getWhoClicked() instanceof Player clicker)) return;
		if (event instanceof InventoryCreativeEvent creative) {
			onCreative(clicker, creative);
			return;
		}
		ArmSession own = arms.session(clicker);
		boolean home = isHome(event.getClickedInventory(), event.getSlot());
		Act act;
		boolean arm;
		if (event.getClick() == ClickType.NUMBER_KEY) {
			act = Act.NUMBER_KEY;
			int button = event.getHotbarButton();
			home |= own != null && !own.tucked && button == own.slot; // a tucked arm has no item in its slot: the real one moves freely
			arm = isArm(event.getCurrentItem()) || isArm(event.getCursor()) || (button >= 0 && isArm(clicker.getInventory().getItem(button)));
		} else if (event.getClick() == ClickType.SWAP_OFFHAND) {
			act = Act.SWAP_OFFHAND;
			arm = isArm(event.getCurrentItem()) || isArm(clicker.getInventory().getItemInOffHand());
		} else if (event.getAction() == InventoryAction.CLONE_STACK) {
			act = Act.CLONE_STACK;
			arm = isArm(event.getCurrentItem());
		} else if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
			act = Act.COLLECT_TO_CURSOR;
			arm = isArm(event.getCursor());
		} else {
			act = Act.CLICK;
			arm = isArm(event.getCurrentItem()) || isArm(event.getCursor());
		}
		if (decide(act, GuardCtx.NONE.withArmed(home).withHome(home).withArm(arm)) == Decision.CANCEL_RESYNC) {
			event.setCancelled(true);
			resync(clicker);
		}
	}

	/** G5: a creative client sets slots freely, so it may never put an arm anywhere, touch the home slot, or drop an arm. */
	private void onCreative(Player clicker, InventoryCreativeEvent event) {
		boolean arm = isArm(event.getCursor());
		Act act = event.getSlot() < 0 || event.getRawSlot() < 0 ? Act.CREATIVE_DROP : Act.CREATIVE_SET;
		ArmSession own = arms.session(clicker);
		boolean home = own != null && !own.tucked && act == Act.CREATIVE_SET
				&& (event.getRawSlot() == 36 + own.slot || isHome(event.getClickedInventory(), event.getSlot()));
		if (decide(act, GuardCtx.NONE.withArmed(home).withHome(home).withArm(arm)) == Decision.CANCEL_RESYNC) {
			event.setCancelled(true);
			resync(clicker);
		}
	}

	/** The slot is the home slot of an armed player's inventory (theirs, or someone's opened through /invsee). */
	private boolean isHome(@Nullable Inventory inv, int slot) {
		if (!(inv instanceof PlayerInventory pi) || !(pi.getHolder() instanceof Player holder)) return false;
		ArmSession s = arms.session(holder);
		return s != null && !s.tucked && s.slot == slot;
	}

	// ------------------------------------------------------------------ G4 drags

	@EventHandler(priority = EventPriority.LOWEST)
	public void onDrag(InventoryDragEvent event) {
		boolean arm = isArm(event.getOldCursor());
		for (ItemStack item : event.getNewItems().values()) arm |= isArm(item);
		boolean home = false;
		InventoryView view = event.getView();
		for (int raw : event.getRawSlots()) home |= isHome(view.getInventory(raw), view.convertSlot(raw));
		if (decide(Act.DRAG, GuardCtx.NONE.withArmed(home).withHome(home).withArm(arm)) == Decision.CANCEL) {
			event.setCancelled(true);
			resync(event.getWhoClicked());
		}
	}

	// ------------------------------------------------------------------ G6 pick block

	@EventHandler(priority = EventPriority.LOWEST)
	public void onPick(PlayerPickItemEvent event) {
		Player p = event.getPlayer();
		if (decide(Act.PICK_BLOCK, GuardCtx.NONE.withArmed(arms.session(p) != null)) == Decision.CANCEL) {
			event.setCancelled(true);
			// the refusal is "your arm is formed"; which key or command puts it away is a tip
			String how = !arms.plugin().tooltips(p) ? "" : arms.plugin().channel().drawsArms(p) ? ": tap J or /morph arm off" : ": /morph arm off";
			p.sendActionBar(Component.text("Your arm is formed" + how, NamedTextColor.GRAY));
		}
	}

	// ------------------------------------------------------------------ G7 scrolling away, and onto a bound slot

	/**
	 * Scrolling away from the arm puts it back at once (the binding stays); scrolling onto a bound slot forms that slot's
	 * arm (2026-11-16). Both in this one event, the old arm first, so no tick has two arms. The new arm goes
	 * into the slot being scrolled to, which the server doesn't count as held until the event returns, so it is passed in.
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onHeld(PlayerItemHeldEvent event) {
		Player p = event.getPlayer();
		ArmSession s = arms.session(p);
		boolean home = s != null && event.getPreviousSlot() == s.slot;
		if (s != null && decide(Act.HELD_SLOT, GuardCtx.NONE.withArmed(true).withHome(home)) == Decision.RETRACT_THEN_ALLOW) {
			arms.finish(p, s, ArmProtocol.WHY_SLOT, ArmManager.Overflow.DROP);
		}
		arms.scrolledOnto(p, event.getNewSlot());
	}

	// ------------------------------------------------------------------ G8 storage blocks, G26 interactions while ending

	@EventHandler(priority = EventPriority.LOWEST)
	public void onUseBlock(PlayerInteractEvent event) {
		if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
		Material type = event.getClickedBlock().getType();
		if (!(Tag.WOODEN_SHELVES.isTagged(type) || type == Material.DECORATED_POT || Tag.ALL_SIGNS.isTagged(type) || Tag.ALL_HANGING_SIGNS.isTagged(type))) return;
		Player p = event.getPlayer();
		ArmSession s = arms.session(p);
		// a powered shelf swaps the whole hotbar, so either hand's click counts while the arm is held
		GuardCtx ctx = GuardCtx.NONE.withArm(isArm(p.getInventory().getItemInMainHand())).withRetracting(s != null && s.ending());
		switch (decide(Act.USE_STORAGE_BLOCK, ctx)) {
			case DENY_USE -> event.setUseInteractedBlock(Event.Result.DENY);
			case CANCEL -> event.setCancelled(true);
			default -> {}
		}
	}

	@EventHandler(priority = EventPriority.LOW)
	public void onInteractEnding(PlayerInteractEvent event) {
		Player p = event.getPlayer();
		ArmSession s = arms.session(p);
		if (s == null) return;
		GuardCtx ctx = GuardCtx.NONE.withArmed(true).withRetracting(s.ending()).withArm(arms.isSessionArm(s, p.getInventory().getItemInMainHand()));
		if (decide(Act.INTERACT, ctx) == Decision.CANCEL) event.setCancelled(true);
	}

	// ------------------------------------------------------------------ G9, G10 entities that take the held item

	@EventHandler(priority = EventPriority.LOWEST)
	public void onUseEntity(PlayerInteractEntityEvent event) {
		if (event.getHand() != EquipmentSlot.HAND) return;
		Entity target = event.getRightClicked();
		if (!(target instanceof ItemFrame || target instanceof ArmorStand || target instanceof Allay || target instanceof SulfurCube)) return;
		if (decide(Act.USE_ITEM_TAKER, GuardCtx.NONE.withArm(isArm(event.getPlayer().getInventory().getItemInMainHand()))) == Decision.CANCEL) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onStand(PlayerArmorStandManipulateEvent event) {
		boolean arm = isArm(event.getPlayerItem()) || isArm(event.getArmorStandItem());
		if (decide(Act.HOLDER_CHANGE, GuardCtx.NONE.withArm(arm)) == Decision.CANCEL) event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onFrame(PlayerItemFrameChangeEvent event) {
		boolean arm = isArm(event.getItemStack()) || isArm(event.getPlayer().getInventory().getItemInMainHand());
		if (decide(Act.HOLDER_CHANGE, GuardCtx.NONE.withArm(arm)) == Decision.CANCEL) event.setCancelled(true);
	}

	// ------------------------------------------------------------------ G11, G12 the item's own use

	/**
	 * Strip, till, path and scrape wear the item: the wear is cancelled and billed as biomass. The whip's reel is not
	 * billed here: the whip bills its own cast, grapple and yank (WhipLash), so only the wear is cancelled.
	 */
	@EventHandler(priority = EventPriority.LOWEST)
	public void onItemDamage(PlayerItemDamageEvent event) {
		ItemStack item = event.getItem();
		if (decide(Act.ITEM_DAMAGE, GuardCtx.NONE.withArm(isArm(item))) != Decision.CANCEL) return;
		event.setCancelled(true);
		String form = ArmItems.formOf(item);
		if (form != null && ArmTiers.billsOwnWear(form)) return; // the whip bills its own line costs; only the wear is cancelled
		Player p = event.getPlayer();
		ArmSession s = arms.session(p);
		if (s != null && arms.isSessionArm(s, item)) arms.billing().transform(p, s, event.getOriginalDamage());
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onConsume(PlayerItemConsumeEvent event) {
		if (decide(Act.CONSUME, GuardCtx.NONE.withArm(isArm(event.getItem()))) == Decision.CANCEL) event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onMend(PlayerItemMendEvent event) {
		if (decide(Act.MEND, GuardCtx.NONE.withArm(isArm(event.getItem()))) == Decision.CANCEL) event.setCancelled(true);
	}

	/** Can't be cancelled, and is never reached (the damage stays below the max); logged in case another plugin forces it. */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onBreakItem(PlayerItemBreakEvent event) {
		if (decide(Act.BREAK_ITEM, GuardCtx.NONE.withArm(isArm(event.getBrokenItem()))) == Decision.CANCEL) {
			arms.plugin().getLogger().warning(event.getPlayer().getName() + "'s arm item broke (another plugin damaged it); retracting.");
			ArmSession s = arms.session(event.getPlayer());
			if (s != null) {
				arms.plugin().getServer().getScheduler().runTask(arms.plugin(), () -> {
					if (arms.session(event.getPlayer()) == s) arms.finish(event.getPlayer(), s, ArmProtocol.WHY_ERROR, ArmManager.Overflow.DROP);
				});
			}
		}
	}

	// ------------------------------------------------------------------ G13 crafting, anvils, grindstones, smithing, enchanting

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPrepareCraft(PrepareItemCraftEvent event) {
		if (decide(Act.PREPARE, GuardCtx.NONE.withArm(holdsArm(event.getInventory()))) == Decision.CANCEL) event.getInventory().setResult(null);
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPrepareResult(PrepareInventoryResultEvent event) {
		if (decide(Act.PREPARE, GuardCtx.NONE.withArm(holdsArm(event.getInventory()))) == Decision.CANCEL) event.setResult(null);
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onPrepareEnchant(PrepareItemEnchantEvent event) {
		if (decide(Act.PREPARE, GuardCtx.NONE.withArm(isArm(event.getItem()))) == Decision.CANCEL) event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onCrafter(CrafterCraftEvent event) {
		boolean arm = event.getBlock().getState(false) instanceof Crafter crafter && holdsArm(crafter.getInventory());
		if (decide(Act.PREPARE, GuardCtx.NONE.withArm(arm)) == Decision.CANCEL) event.setCancelled(true);
	}

	private static boolean holdsArm(Inventory inv) {
		for (ItemStack item : inv.getContents()) {
			if (isArm(item)) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ G14-G17 arms outside a player: strays

	@EventHandler(priority = EventPriority.LOWEST)
	public void onMove(InventoryMoveItemEvent event) {
		if (decide(Act.MOVE_ITEM, GuardCtx.NONE.withArm(isArm(event.getItem()))) == Decision.CANCEL_PURGE) {
			event.setCancelled(true);
			arms.purge(event.getSource());
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onDispense(BlockDispenseEvent event) {
		if (decide(Act.DISPENSE, GuardCtx.NONE.withArm(isArm(event.getItem()))) == Decision.CANCEL_PURGE) {
			event.setCancelled(true);
			if (event.getBlock().getState(false) instanceof Container c) arms.purge(c.getInventory());
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onSpawn(ItemSpawnEvent event) {
		if (decide(Act.ITEM_SPAWN, GuardCtx.NONE.withArm(isArm(event.getEntity().getItemStack()))) == Decision.CANCEL) event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onPickup(EntityPickupItemEvent event) {
		if (decide(Act.PICKUP, GuardCtx.NONE.withArm(isArm(event.getItem().getItemStack()))) == Decision.CANCEL_PURGE) {
			event.setCancelled(true);
			event.getItem().remove();
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onPlayerPickup(PlayerAttemptPickupItemEvent event) {
		if (decide(Act.PICKUP, GuardCtx.NONE.withArm(isArm(event.getItem().getItemStack()))) == Decision.CANCEL_PURGE) {
			event.setCancelled(true);
			event.getItem().remove();
		}
	}

	/** G17: a mob, armour stand or other non-player now holds an arm (a dispenser, a plugin): the slot is cleared next tick. */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onEquipment(EntityEquipmentChangedEvent event) {
		LivingEntity e = event.getEntity();
		if (e instanceof Player) return;
		for (Map.Entry<EquipmentSlot, EntityEquipmentChangedEvent.EquipmentChange> change : event.getEquipmentChanges().entrySet()) {
			if (decide(Act.EQUIPMENT_CHANGED, GuardCtx.NONE.withArm(isArm(change.getValue().newItem()))) != Decision.PURGE) continue;
			EquipmentSlot slot = change.getKey();
			arms.plugin().getServer().getScheduler().runTask(arms.plugin(), () -> {
				var equipment = e.getEquipment();
				if (e.isValid() && equipment != null && isArm(equipment.getItem(slot))) equipment.setItem(slot, null);
			});
		}
	}

	// ------------------------------------------------------------------ G18 inventories opening (and closing: A3)

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onOpen(InventoryOpenEvent event) {
		Inventory top = event.getView().getTopInventory();
		if (decide(Act.INVENTORY_OPEN, GuardCtx.NONE.withArm(holdsArm(top))) == Decision.PURGE) arms.purge(top);
		if (event.getPlayer() instanceof Player p) {
			ArmSession s = arms.session(p);
			if (s != null) arms.audit().scan(p, s);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onClose(InventoryCloseEvent event) {
		if (!(event.getPlayer() instanceof Player p)) return;
		ArmSession s = arms.session(p);
		if (s != null) arms.plugin().getServer().getScheduler().runTask(arms.plugin(), () -> {
			if (arms.session(p) == s && p.isOnline()) arms.audit().scan(p, s);
		});
	}

	// ------------------------------------------------------------------ G19 commands that act on the held item

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onCommand(PlayerCommandPreprocessEvent event) {
		Player p = event.getPlayer();
		ArmSession s = arms.session(p);
		if (s == null) return;
		String message = event.getMessage();
		String label = message.startsWith("/") ? message.substring(1) : message;
		int space = label.indexOf(' ');
		if (space >= 0) label = label.substring(0, space);
		String resolved = null;
		if (!label.isEmpty()) {
			Command command = arms.plugin().getServer().getCommandMap().getCommand(label.toLowerCase(java.util.Locale.ROOT));
			if (command != null) resolved = command.getName();
		}
		boolean retracts = arms.config().commandGuard().retractFirst(label, resolved);
		if (decide(Act.COMMAND, GuardCtx.NONE.withArmed(true).withCommandRetracts(retracts)) == Decision.RETRACT_THEN_ALLOW) {
			arms.finish(p, s, ArmProtocol.WHY_COMMAND, ArmManager.Overflow.DROP);
		}
	}

	// ------------------------------------------------------------------ G20, G21 worlds and game modes

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onTeleport(PlayerTeleportEvent event) {
		Player p = event.getPlayer();
		ArmSession s = arms.session(p);
		boolean on = arms.anyOn(p);
		if (s == null && !on) return;
		boolean crossWorld = event.getTo().getWorld() != null && event.getFrom().getWorld() != event.getTo().getWorld();
		GuardCtx ctx = GuardCtx.NONE.withArmed(s != null).withAccessoryOn(on).withCrossWorld(crossWorld).withInventorySafety(arms.inventorySafety());
		if (decide(Act.TELEPORT, ctx) == Decision.RETRACT_THEN_ALLOW) {
			if (s != null) arms.finish(p, s, ArmProtocol.WHY_WORLD, ArmManager.Overflow.DROP);
			arms.switchOffAll(p, ArmProtocol.WHY_WORLD);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onGameMode(PlayerGameModeChangeEvent event) {
		Player p = event.getPlayer();
		ArmSession s = arms.session(p);
		boolean on = arms.anyOn(p);
		if (s == null && !on) return;
		GuardCtx ctx = GuardCtx.NONE.withArmed(s != null).withAccessoryOn(on).withToSpectator(event.getNewGameMode() == GameMode.SPECTATOR)
				.withInventorySafety(arms.inventorySafety());
		if (decide(Act.GAME_MODE, ctx) == Decision.RETRACT_THEN_ALLOW) {
			if (s != null) arms.finish(p, s, ArmProtocol.WHY_GAMEMODE, ArmManager.Overflow.DROP);
			arms.switchOffAll(p, ArmProtocol.WHY_GAMEMODE);
		}
	}

	// ------------------------------------------------------------------ G22 death, G23 leaving, the join repair

	@EventHandler(priority = EventPriority.LOWEST)
	public void onDeath(PlayerDeathEvent event) {
		Player p = event.getEntity();
		arms.screenGone(p); // dying closes any screen, so a flag a missed CLOSED left set must not outlive it
		GuardCtx ctx = GuardCtx.NONE.withArmed(arms.session(p) != null).withAccessoryOn(arms.anyOn(p));
		if (decide(Act.DEATH, ctx) == Decision.RETRACT_THEN_ALLOW) arms.onDeath(event);
		else stripArms(event.getDrops(), event.getItemsToKeep()); // strays never drop
	}

	/** After every other plugin: whatever put an arm back into the lists, it never drops or stays. */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onDeathSweep(PlayerDeathEvent event) {
		stripArms(event.getDrops(), event.getItemsToKeep());
	}

	private static void stripArms(List<ItemStack> drops, List<ItemStack> kept) {
		drops.removeIf(ArmItems::isArm);
		kept.removeIf(ArmItems::isArm);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onRespawn(PlayerRespawnEvent event) {
		Player p = event.getPlayer();
		arms.plugin().getServer().getScheduler().runTaskLater(arms.plugin(), () -> {
			if (p.isOnline()) arms.syncWorld(p, false);
		}, 1L);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onWorldChanged(PlayerChangedWorldEvent event) {
		arms.syncWorld(event.getPlayer(), true);
	}

	/** Leaving: the arm retracts and its item goes back in the slot before Paper saves the player (it saves after this). */
	@EventHandler(priority = EventPriority.LOWEST)
	public void onQuit(PlayerQuitEvent event) {
		Player p = event.getPlayer();
		GuardCtx ctx = GuardCtx.NONE.withArmed(arms.session(p) != null).withAccessoryOn(arms.anyOn(p));
		if (decide(Act.QUIT, ctx) == Decision.RETRACT_THEN_ALLOW) {
			ArmSession s = arms.session(p);
			if (s != null) arms.finish(p, s, ArmProtocol.WHY_QUIT, ArmManager.Overflow.PENDING);
			arms.switchOffAll(p, ArmProtocol.WHY_QUIT);
		}
		arms.forget(p);
	}

	/** Before the morph handling (MorphListener.onJoin is MONITOR): strays purged, a saved arm's item given back. */
	@EventHandler(priority = EventPriority.LOWEST)
	public void onJoin(PlayerJoinEvent event) {
		arms.reconcile(event.getPlayer());
	}

	// ------------------------------------------------------------------ G25 RE:Morph itself being disabled

	/** While the plugin channel can still tell the clients: every arm back to a plain hand. */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onDisabling(PluginDisableEvent event) {
		if (event.getPlugin() == arms.plugin() && decide(Act.PLUGIN_DISABLE, GuardCtx.NONE) == Decision.RETRACT_THEN_ALLOW) {
			arms.retractAll(ArmProtocol.WHY_DISABLE);
		}
	}

	// ------------------------------------------------------------------ G26 nothing is done with an arm that is retracting or melting

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onBreakEnding(BlockBreakEvent event) {
		ArmSession s = arms.session(event.getPlayer());
		// a tucked arm has no item in the hand, so a break it is ending (retracting, melting) is made with the player's real item
		if (s != null && !s.tucked && decide(Act.BLOCK_BREAK, GuardCtx.NONE.withArmed(true).withRetracting(s.ending())) == Decision.CANCEL) event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onDigEnding(BlockDamageEvent event) {
		ArmSession s = arms.session(event.getPlayer());
		if (s != null && !s.tucked && decide(Act.BLOCK_DAMAGE, GuardCtx.NONE.withArmed(true).withRetracting(s.ending())) == Decision.CANCEL) event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onHitEnding(EntityDamageByEntityEvent event) {
		if (!(event.getDamager() instanceof Player p)) return;
		ArmSession s = arms.session(p);
		if (s != null && !s.tucked && decide(Act.MELEE, GuardCtx.NONE.withArmed(true).withRetracting(s.ending())) == Decision.CANCEL) event.setCancelled(true);
	}

	// ------------------------------------------------------------------ a tucked arm, for a viewer who just came into range

	/**
	 * A player started tracking another: if the other's arm is tucked, the new viewer is sent their hand once the pairing is
	 * done (ArmManager.viewerTracking). Vanilla's pairing leaves an empty slot out, so a tucked owner with an empty hand sends
	 * the newcomer no equipment packet at all, and no arm would show.
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onTrack(PlayerTrackEntityEvent event) {
		if (event.getEntity() instanceof Player owner) arms.viewerTracking(event.getPlayer(), owner);
	}

	// ------------------------------------------------------------------ G27 any slot of the player's inventory changed

	private boolean inSlotChange;

	@EventHandler(priority = EventPriority.MONITOR)
	public void onSlotChange(PlayerInventorySlotChangeEvent event) {
		if (inSlotChange || arms.writing()) return; // our own fixes below, and the manager's own writes, change slots too
		Player p = event.getPlayer();
		int slot = event.getSlot();
		ArmSession s = arms.session(p);
		boolean home = s != null && !s.tucked && slot == s.slot;
		boolean arm = isArm(event.getNewItemStack());
		if (!arm && !home) return;
		GuardCtx ctx = GuardCtx.NONE.withArmed(s != null).withHome(home).withArm(arm).withRetracting(s != null && s.ending());
		inSlotChange = true;
		try {
			switch (decide(Act.SLOT_CHANGE, ctx)) {
				case PURGE -> {
					// the live slot, not the event's copy: only delete what is really there
					PlayerInventory inv = p.getInventory();
					if (slot >= 0 && slot < inv.getSize() && isArm(inv.getItem(slot)) && !(s != null && slot == s.slot && arms.isSessionArm(s, inv.getItem(slot)))) {
						arms.plugin().debug("Deleted a stray arm from " + p.getName() + "'s slot " + slot + ".");
						arms.write(p, () -> inv.setItem(slot, null));
					}
				}
				case RETRACT_THEN_ALLOW -> {
					// home lost its arm without a retract; confirm against the live slot (a cancelled drop puts it back first)
					if (s != null && arms.session(p) == s && !arms.isSessionArm(s, p.getInventory().getItem(s.slot))) {
						arms.plugin().getLogger().warning(p.getName() + "'s arm left its slot without a retract (another plugin?). The item it held is given back.");
						arms.finish(p, s, ArmProtocol.WHY_ERROR, ArmManager.Overflow.DROP);
					}
				}
				default -> {}
			}
		} finally {
			inSlotChange = false;
		}
	}
}
