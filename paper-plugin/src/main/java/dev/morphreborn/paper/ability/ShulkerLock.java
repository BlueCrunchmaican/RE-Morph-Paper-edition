package dev.morphreborn.paper.ability;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.data.MorphRef;
import dev.morphreborn.paper.morph.ActiveMorph;
import dev.morphreborn.paper.nbt.Snbt;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Effect;
import org.bukkit.Fluid;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Shulker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * A shulker main body sits in a block's space as a real shulker does (my call: "you should essentially become a
 * block"): on a floor, on a wall or upside down on a ceiling, the player standing exactly at the cell's bottom centre.
 * Only the main body locks; a shulker that is just borrowed (another body wearing its head) moves as it always did.
 * <p>
 * A player is <b>locked</b> (a cell and the face it clings to), <b>pending</b> (a shulker body with nowhere to cling to
 * yet: in the air, in water, riding; it locks as soon as it stands on a floor it can) or neither. The hold is the bat
 * perch's: flying at fly speed 0, so nothing fights gravity and no key moves them. While locked every change the ability
 * system makes to their flight goes into the state saved here ({@link #routeAllowFlight}), so letting go gives back
 * exactly what they would have had.
 * <p>
 * Everyone sees the face and the shell's opening through the overlay keys {@code {AttachFace:<id>b,Peek:<0-100>b}}
 * (vanilla's own shulker NBT, see the design contract), present exactly while the body shown is a shulker and the player
 * is held in its cell: the keys always mean "the shell is in this player's block, on this face". A transformation into a
 * shulker locks at its start when the player's box already fits the planned block, so the whole melt plays there;
 * otherwise (a ceiling) it locks at the half-way swap, and no keys go out before. Solidity comes from a hidden helper
 * {@link Shulker} in the cell, shown only to players with the mod
 * other than the owner (players without it already collide with Lib's Disguises' shulker on their own screen). Sneak +
 * jump teleports onto the face looked at, as a shulker teleports; the shell is closed (more armour, arrows bounce) until
 * the player uses an ability, which opens it for a while (the head inside pops out to use it).
 */
public final class ShulkerLock implements Listener {
	public static final String SHULKER = "minecraft:shulker";
	/** Vanilla's Direction order: the 3D ids the AttachFace byte uses, and the order a shulker looks for a new face. */
	private static final BlockFace[] FACES = {BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST};
	private static final String ATTACH_FACE = "AttachFace", PEEK = "Peek";
	/** A player's standing box at scale 1, as the game sizes it. */
	private static final double PLAYER_WIDTH = 0.6, PLAYER_HEIGHT = 1.8;
	/** Further than this from the cell's bottom centre (blocks) and a locked player is put back. */
	private static final double DRIFT = 1e-3;
	/**
	 * A pending player moved by a teleport further than this (blocks) looks for a place at once (a pearl, /tp); smaller
	 * moves are other code nudging them (a frenzy hover held under its height, room made to grow).
	 */
	private static final double PENDING_TELEPORT = 1.5;
	/** How far from the player a face planned at a transformation's start may be when it comes to locking (blocks). */
	private static final double PLAN_SLACK = 2;

	private final MorphRebornPlugin plugin;
	/** Entity data on a helper: its owner, so a stray one (a crash, a reload) is known and removed. */
	private final NamespacedKey helperKey;
	/** The closed shell's armour (vanilla's covered modifier, on the player). */
	private final NamespacedKey shellKey;

	/** abilities.yml {@code shulker}; the same defaults here, since files from before 8.0.0 lack the section. */
	private double teleportRange = 8;
	private int teleportCooldownTicks = 20;
	private int openTicks = 60;
	private double closedArmor = 20;

	/** The flight a locked player had, given back when they let go. */
	private static final class Flight {
		boolean allowFlight;
		boolean flying;
		float flySpeed;

		Flight(Player p) {
			allowFlight = p.getAllowFlight();
			flying = p.isFlying();
			flySpeed = p.getFlySpeed();
		}
	}

	/** A cell and the face it clings to: the direction from the shulker to the block that holds it (floor = DOWN). */
	private record Spot(Block cell, BlockFace face) {}

	private static final class State {
		/** Null while pending. */
		@Nullable Block cell;
		@Nullable BlockFace face;
		/** The face last given to the helper: when it reports another, vanilla moved it (its own re-attach), and the player follows. */
		@Nullable BlockFace helperFace;
		/** Non-null exactly while locked. */
		@Nullable Flight saved;
		/** The game mode the saved flight belongs to: a change re-decides it. */
		@Nullable GameMode mode;
		@Nullable Shulker helper;
		/** A helper another plugin refused to spawn is tried again from this tick, not every tick. */
		int helperRetry;
		boolean open;
		int openUntil;
		/** Keys held, so a jump is only counted when newly pressed. */
		org.bukkit.Input keys;
		int teleportReady;
		/** Moved by something else (a pearl, /tp, another world): find a place where they are now, next tick. */
		boolean reattach;
		/** Pending: the block last tried as a floor, so standing still on one that won't do isn't checked every tick. */
		@Nullable Block lastTried;

		State(Player p) {
			keys = p.getCurrentInput();
		}
	}

	private final Map<UUID, State> states = new HashMap<>();
	/**
	 * The spot chosen when a transformation into a shulker began where the player's box doesn't fit yet (a ceiling):
	 * locked at the half-way swap if it still holds, and never shown before then.
	 */
	private final Map<UUID, Spot> plans = new HashMap<>();
	/** Cells held by locked players. */
	private final Map<Block, UUID> occupied = new HashMap<>();
	/** Helper entity -> its owner. */
	private final Map<UUID, UUID> helpers = new HashMap<>();
	/** The player this class is teleporting right now, so its own teleports aren't taken for someone else's. */
	private @Nullable UUID teleporting;
	private int tick;
	/**
	 * The last arrow hit that went through on a shell (its helper or the player inside it): an arrow hits every entity
	 * on its step, and the owner's box sits inside the helper's, so one step would hit both. A closed shell would reverse
	 * it twice (it stops dead and drops instead of bouncing), an open one be hit twice. One hit per arrow per step.
	 */
	private @Nullable UUID shellArrow, shellArrowOwner;
	private int shellArrowTick;
	/** Passing a helper's hit on to its owner right now: that nested hit is the pair's one hit, not a second. */
	private boolean forwarding;

	public ShulkerLock(MorphRebornPlugin plugin) {
		this.plugin = plugin;
		this.helperKey = MorphRebornPlugin.key("shulker_helper");
		this.shellKey = MorphRebornPlugin.key("shulker_shell");
	}

	public void load(@Nullable ConfigurationSection c) {
		teleportRange = c == null ? 8 : Math.clamp(c.getDouble("teleport-range", 8), 1, 32);
		teleportCooldownTicks = (int) Math.round((c == null ? 1 : Math.clamp(c.getDouble("teleport-cooldown-seconds", 1), 0, 60)) * 20);
		openTicks = (int) Math.round((c == null ? 3 : Math.clamp(c.getDouble("open-seconds", 3), 0.5, 60)) * 20);
		closedArmor = c == null ? 20 : Math.clamp(c.getDouble("closed-armor", 20), 0, 30);
	}

	public static boolean isShulker(@Nullable MorphRef form) {
		return form != null && SHULKER.equals(form.entityType());
	}

	// ------------------------------------------------------------------ queries

	public boolean isLocked(Player p) {
		State s = states.get(p.getUniqueId());
		return s != null && s.cell != null;
	}

	public boolean isHelper(@Nullable Entity e) {
		return e != null && helpers.containsKey(e.getUniqueId());
	}

	/** {@code e} is {@code p}'s own helper. */
	public boolean isHelperOf(@Nullable Entity e, Player p) {
		return e != null && p.getUniqueId().equals(helpers.get(e.getUniqueId()));
	}

	/** What {@code e} stands for: a helper is the player inside it (aiming at a locked shulker aims at the player). */
	public @Nullable Entity standIn(@Nullable Entity e) {
		if (e == null) return null;
		UUID owner = helpers.get(e.getUniqueId());
		if (owner == null) return e;
		Player p = plugin.getServer().getPlayer(owner);
		return p != null ? p : e;
	}

	/**
	 * Nothing rules a lock out (dead, spectating) and the body is a shulker: the physical body and the form that counts.
	 * A lock already taken also goes on while the body isn't one (yet): the first half of a transformation into a
	 * shulker (an early lock, see {@link #startOverlay}), and the moment between a transformation being redirected and
	 * the body following its new first half (MorphManager.syncBody, the next tick).
	 */
	private boolean wants(Player p) {
		if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return false;
		MorphRef active = plugin.morphs().activeForm(p);
		if (isShulker(plugin.morphs().body(p)) && isShulker(active)) return true;
		return isLocked(p) && (isShulker(active) || arriving(p));
	}

	/** A transformation into a shulker main body is under way. */
	private boolean arriving(Player p) {
		ActiveMorph am = plugin.morphs().state(p);
		return am != null && am.isTransitioning() && isShulker(am.look());
	}

	/**
	 * A transformation out of a shulker main body is under way: until the swap the body is still a shulker and a lock it
	 * has goes on, but it takes no new place, since no keys could show it (the form shown is the next one).
	 */
	private boolean leaving(Player p) {
		ActiveMorph am = plugin.morphs().state(p);
		return am != null && am.isTransitioning() && !isShulker(am.look());
	}

	/** The shulker form whose size counts: the body's, or the one a transformation is heading for. */
	private @Nullable MorphRef shulkerForm(Player p) {
		MorphRef body = plugin.morphs().body(p);
		if (isShulker(body)) return body;
		ActiveMorph am = plugin.morphs().state(p);
		MorphRef look = am == null ? null : am.look();
		return isShulker(look) ? look : null;
	}

	// ------------------------------------------------------------------ flight routed through the lock

	private @Nullable Flight held(Player p) {
		State s = states.get(p.getUniqueId());
		return s == null || s.cell == null ? null : s.saved;
	}

	/**
	 * The ability system changing a locked player's flight (a borrowed flyer's grant, the teardown when a form changes):
	 * it goes into the saved state, put back on letting go, instead of dropping them out of the hold. False when not
	 * locked: apply it yourself.
	 */
	public boolean routeAllowFlight(Player p, boolean allow) {
		Flight f = held(p);
		if (f == null) return false;
		f.allowFlight = allow;
		if (!allow) f.flying = false;
		return true;
	}

	public boolean routeFlying(Player p, boolean flying) {
		Flight f = held(p);
		if (f == null) return false;
		f.flying = flying && f.allowFlight;
		return true;
	}

	public boolean routeFlySpeed(Player p, float speed) {
		Flight f = held(p);
		if (f == null) return false;
		f.flySpeed = speed;
		return true;
	}

	/**
	 * The game mode changed (the game already set whether they may fly and stopped the flying): spectators let go; in any
	 * other mode letting go gives what the new mode gave, and the hold goes back on. Safe to call more than once.
	 */
	public void onGameModeChanged(Player p) {
		State s = states.get(p.getUniqueId());
		if (s != null) modeChanged(p, s);
	}

	private void modeChanged(Player p, State s) {
		if (s.cell == null || s.saved == null || p.getGameMode() == s.mode) return;
		s.mode = p.getGameMode();
		if (s.mode == GameMode.SPECTATOR) {
			release(p);
			return;
		}
		// what the mode itself gives (only creative may fly), not the flags: something may have run the hold since the
		// change (a teleport or a body swap in the same tick) and put them back, and survival would keep creative flight
		s.saved.allowFlight = s.mode == GameMode.CREATIVE;
		s.saved.flying = s.saved.flying && s.saved.allowFlight;
		hold(p, s);
	}

	// ------------------------------------------------------------------ the body changing

	/**
	 * A transformation (or a vanilla conversion) just started and cleared the overlay: the keys it should carry from the
	 * start. Into a shulker, the place is chosen now (crosshair, else floor). If the box the player keeps until the
	 * half-way swap already fits there, they lock at once (the whole lock: snap, hold, helper, armour), so the melt plays
	 * in the block the shell will be in; if not (a ceiling), the place waits for the swap and no keys go out until then,
	 * so nobody draws the shell on a face in the wrong block. A shulker to shulker change (another colour) keeps the face
	 * it has. Into anything else nothing changes here: a lock held goes on while the first half's body is still a shulker
	 * and lets go at the swap (MorphManager.syncBody), as any shulker turning into something else does.
	 * <p>
	 * Called from MorphManager.morphTo and convert, which put the returned keys in the overlay and send them themselves,
	 * so nothing here sends a state (an early lock is quiet).
	 */
	public String startOverlay(Player p, @Nullable MorphRef look) {
		UUID id = p.getUniqueId();
		plans.remove(id);
		if (!isShulker(look)) return "";
		ActiveMorph am = plugin.morphs().state(p);
		MorphRef first = am == null ? null : am.activeForm(); // the body until the swap
		State s = states.get(id);
		if (s != null) {
			// still a shulker body until the swap: the lock (or the wait for a floor) goes on
			// (a body still waiting for room to grow is about to be swapped by syncBody, which lets the lock go)
			boolean settled = plugin.morphs().bodySettled(p);
			if (isShulker(first) && settled) return s.cell == null ? "" : keys(s.face, s.open ? 100 : 0);
			// back into a shulker during the first half out of one: an early lock where it is, if that body fits there
			if (s.cell != null && settled && fitsBody(p, s.cell)) return keys(s.face, s.open ? 100 : 0);
			states.remove(id);
			unlock(p, s, false);
		}
		if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return "";
		Spot plan = find(p);
		if (plan == null) return "";
		if (am == null || !am.isTransitioning() || !plugin.morphs().bodySettled(p) || !fitsBody(p, plan.cell())) {
			// instant (the lock comes in a moment, at the swap), a body syncBody is about to swap (it would let an early
			// lock go at once), or the box doesn't fit there yet
			plans.put(id, plan);
			return "";
		}
		State early = new State(p);
		states.put(id, early);
		lock(p, early, plan, false);
		if (early.cell != null) return keys(early.face, 0);
		states.remove(id); // the snap was refused: the swap tries again
		plans.put(id, plan);
		return "";
	}

	/**
	 * The physical body just became a shulker (MorphManager.syncBody, after the swap): lock at the planned spot if it
	 * still holds, else where the player looks or stands, else pending. Already locked (an early lock at the
	 * transformation's start, a shulker to shulker change, a respawn): the hold and keys are put back as they were.
	 * During the first half out of a shulker (redirected mid-way) no new place is taken.
	 */
	public void engage(Player p) {
		if (!wants(p)) return;
		UUID id = p.getUniqueId();
		State s = states.computeIfAbsent(id, k -> new State(p));
		if (s.cell != null) {
			hold(p, s);
			showKeys(p);
			return;
		}
		if (leaving(p)) return; // pending until the swap lets go
		Spot plan = plans.remove(id);
		Spot spot = plan != null && near(p, plan.cell()) && valid(p, plan, false) ? plan : find(p);
		if (spot != null) lock(p, s, spot, true);
		else showKeys(p);
	}

	/** The body stops being a shulker (or the player leaves, dies, spectates): let go entirely. */
	public void release(Player p) {
		State s = states.remove(p.getUniqueId());
		if (s == null) return;
		unlock(p, s, true);
	}

	/** Logging out: let go before the player file is saved, so it holds their own flight and not the hold's. */
	public void forget(Player p) {
		release(p);
		plans.remove(p.getUniqueId());
	}

	/** A server that died while someone was held left them a fly speed of 0 in their player file. */
	public void onJoin(Player p) {
		if (!isLocked(p) && !plugin.abilities().isPerched(p) && p.getFlySpeed() == 0F) p.setFlySpeed(0.1F);
	}

	/**
	 * A viewer just proved it has the mod: it gets the helpers, so the shulkers it sees are solid. Not while spectating:
	 * a spectator's game draws invisible mobs see-through, so the helper would show as a second shell inside each one
	 * (spectators pass through everything anyway); {@link #onViewerGameMode} shows them on leaving spectator.
	 */
	public void showHelpers(Player viewer) {
		if (viewer.getGameMode() == GameMode.SPECTATOR) return;
		for (Map.Entry<UUID, State> e : states.entrySet()) {
			Shulker h = e.getValue().helper;
			if (h != null && h.isValid() && !e.getKey().equals(viewer.getUniqueId())) viewer.showEntity(plugin, h);
		}
	}

	/**
	 * Shutdown: everyone lets go and every helper goes. While the plugin is still enabled ({@link #onDisabling}) the
	 * keyless overlay goes out to every viewer, so no game with the mod goes on holding its player in place after the
	 * server stopped holding them. From onDisable plugin messages are refused (the plugin already counts as disabled),
	 * so there it only lets go of whatever is left.
	 */
	public void releaseAll() {
		boolean announce = plugin.isEnabled();
		plans.clear(); // first: no plan may put keys back
		for (UUID id : List.copyOf(states.keySet())) {
			Player p = plugin.getServer().getPlayer(id);
			State s = states.remove(id);
			if (p != null) unlock(p, s, announce);
			else removeHelper(s);
		}
		for (UUID id : List.copyOf(helpers.keySet())) {
			Entity e = plugin.getServer().getEntity(id);
			if (e != null) e.remove();
		}
		helpers.clear();
		occupied.clear();
	}

	/**
	 * Paper fires this before it marks the plugin disabled and runs onDisable: the last moment the plugin channel still
	 * sends, so the release happens here and its keyless state reaches the clients (onDisable's call finds nothing left).
	 */
	@EventHandler
	public void onDisabling(PluginDisableEvent event) {
		if (event.getPlugin() == plugin) releaseAll();
	}

	/** Helpers left in the world by a crash or a reload: they are never saved, but loaded worlds are swept anyway. */
	public void sweep() {
		for (World w : plugin.getServer().getWorlds()) {
			for (Shulker s : w.getEntitiesByClass(Shulker.class)) if (stray(s)) s.remove();
		}
	}

	private boolean stray(Entity e) {
		return e instanceof Shulker && e.getPersistentDataContainer().has(helperKey) && !helpers.containsKey(e.getUniqueId());
	}

	// ------------------------------------------------------------------ the shell

	/**
	 * Any ability the player uses (AbilityManager.fire), not only the shulker bullet: like a shulker attacking, the shell
	 * opens (armour off, arrows get through) for {@code open-seconds}, and using one again while open keeps it open. The
	 * head inside pops out to do it, the shulker's own or the one it borrows (a creeper's to blow up, a skeleton's to
	 * shoot). Only a locked main body has a shell; one just moved by something else (an enderman's teleport it borrows)
	 * is about to find its new place, which shuts it, so it stays shut rather than opening for a tick.
	 */
	public void openShell(Player p) {
		State s = states.get(p.getUniqueId());
		if (s == null || s.cell == null || s.reattach) return;
		s.openUntil = Math.max(s.openUntil, tick + openTicks);
		if (s.open) return;
		s.open = true;
		armour(p, false);
		syncPeek(p, s);
		p.getWorld().playSound(p.getLocation(), Sound.ENTITY_SHULKER_OPEN, 1F, 1F);
		showKeys(p);
	}

	/** Closed again: armour back on; {@code sound} false when a teleport shut it (vanilla's teleport closes it silently). */
	private void closeShell(Player p, State s, boolean sound) {
		boolean was = s.open;
		s.open = false;
		s.openUntil = 0;
		armour(p, true);
		syncPeek(p, s);
		if (was && sound) p.getWorld().playSound(p.getLocation(), Sound.ENTITY_SHULKER_CLOSE, 1F, 1F);
		if (was) showKeys(p);
	}

	/**
	 * The helper's lid follows the shell, so an open shell is solid too (a real one's box grows), except on a floor
	 * under an owner taller than the block (hitbox scaling off, or the body before the swap): vanilla's opening lid
	 * pushes everything in its sweep up, the owner too, and the hold would snap them back every tick, camera and all.
	 */
	private float helperPeek(Player p, State s) {
		return s.open && !(s.face == BlockFace.DOWN && p.getHeight() > 1 + 1e-6) ? 1F : 0F;
	}

	private void syncPeek(Player p, State s) {
		Shulker h = s.helper;
		if (h == null || !h.isValid()) return;
		float peek = helperPeek(p, s);
		if (h.getPeek() != peek) h.setPeek(peek);
	}

	private void armour(Player p, boolean on) {
		AttributeInstance armor = p.getAttribute(Attribute.ARMOR);
		if (armor == null) return;
		AttributeModifier current = armor.getModifier(shellKey);
		double want = on ? closedArmor : 0;
		if (current != null && Math.abs(current.getAmount() - want) < 1e-6) return;
		if (current != null) armor.removeModifier(shellKey);
		if (want > 0) armor.addTransientModifier(new AttributeModifier(shellKey, want, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
	}

	private boolean closedShell(Player p) {
		State s = states.get(p.getUniqueId());
		return s != null && s.cell != null && !s.open;
	}

	// ------------------------------------------------------------------ ticking

	public void tick() {
		tick++;
		if (!plans.isEmpty()) tickPlans();
		for (Player p : plugin.getServer().getOnlinePlayers()) {
			State s = states.get(p.getUniqueId());
			if (!wants(p)) {
				if (s != null) release(p);
				continue;
			}
			if (s == null) engage(p); // a shulker body the swap didn't announce (leaving spectator, a respawn)
			else if (s.cell == null) tickPending(p, s);
			else tickLocked(p, s);
		}
		if (tick % 20 == 0) {
			for (UUID id : List.copyOf(states.keySet())) {
				if (plugin.getServer().getPlayer(id) == null) unlockGone(id);
			}
		}
	}

	/** A plan whose transformation is over (or went elsewhere) goes; plans are never shown, so nothing else to do. */
	private void tickPlans() {
		for (Iterator<Map.Entry<UUID, Spot>> it = plans.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Spot> e = it.next();
			Player p = plugin.getServer().getPlayer(e.getKey());
			ActiveMorph am = p == null ? null : plugin.morphs().state(p);
			if (p == null || am == null || !am.isTransitioning() || !isShulker(am.look()) || p.isDead()) it.remove();
		}
	}

	private void tickPending(Player p, State s) {
		if (leaving(p)) {
			s.reattach = false; // the first half out of a shulker: no new place, the swap lets go
			return;
		}
		if (s.reattach) {
			s.reattach = false;
			Spot spot = find(p);
			if (spot != null) {
				lock(p, s, spot, true);
				return;
			}
		}
		if (p.isOnGround() && !p.isInsideVehicle()) {
			Block feet = p.getLocation().getBlock();
			if (feet.equals(s.lastTried) && tick % 10 != 0) return;
			s.lastTried = feet;
			Spot spot = floor(p);
			if (spot != null) lock(p, s, spot, true);
		}
	}

	private void tickLocked(Player p, State s) {
		if (p.getGameMode() != s.mode) modeChanged(p, s);
		if (s.cell == null) return;
		if (s.reattach || p.getWorld() != s.cell.getWorld()) {
			s.reattach = false;
			// the first half out of a shulker takes no new place (no keys could show it): free where they were put
			if (leaving(p)) toPending(p, s);
			else reattach(p, s);
			return;
		}
		Shulker h = s.helper;
		if (h == null || !h.isValid()) {
			if (tick >= s.helperRetry) placeHelper(p, s);
		} else if (h.getAttachedFace() != s.helperFace && h.getAttachedFace().isCartesian()) {
			// vanilla re-attached the helper (its face stopped being a place a shulker can stay): the player goes with it
			s.face = s.helperFace = h.getAttachedFace();
		} else if (!atAnchor(h.getLocation(), s.cell)) {
			placeHelper(p, s); // pushed off (a piston): back into the cell
		}
		if (!cellFree(s.cell)) {
			toPending(p, s);
			return;
		}
		if (!holds(s.cell, s.face)) {
			// the block it clung to is gone: another face of the same cell, in vanilla's order, or let go. One the helper
			// keeps first (else it would move on by itself next tick); one that only holds if there is no such face,
			// since then the helper finds nothing better either and stays put
			BlockFace other = null;
			for (BlockFace f : FACES) {
				if (stays(p, s.cell, f)) {
					other = f;
					break;
				}
			}
			for (int i = 0; other == null && i < FACES.length; i++) {
				if (holds(s.cell, FACES[i])) other = FACES[i];
			}
			if (other == null) {
				toPending(p, s);
				return;
			}
			s.face = other;
			placeHelper(p, s);
		}
		hold(p, s);
		if (s.cell == null) return; // the hold couldn't put them back
		if (s.open && tick >= s.openUntil) closeShell(p, s, true);
		syncPeek(p, s); // the owner's box changes at the swap
		showKeys(p);
	}

	/**
	 * Moved by something else: find a place where they are now (crosshair, floor), or let go there. An early lock (the
	 * body not a shulker yet) only goes where the body it has until the swap fits.
	 */
	private void reattach(Player p, State s) {
		Spot spot = find(p);
		if (spot != null && !isShulker(plugin.morphs().body(p)) && !fitsBody(p, spot.cell())) spot = null;
		if (spot == null) {
			toPending(p, s);
			return;
		}
		move(p, s, spot, false);
	}

	// ------------------------------------------------------------------ locking

	/** {@code announce} false: the caller sends the keys itself (startOverlay's early lock, inside morphTo). */
	private void lock(Player p, State s, Spot spot, boolean announce) {
		if (plugin.abilities().isPerched(p)) plugin.abilities().unperch(p, true);
		plugin.rage().stopHover(p);
		if (p.isInsideVehicle()) p.leaveVehicle();
		if (p.isGliding()) p.setGliding(false);
		if (s.saved == null) {
			s.saved = new Flight(p);
			s.mode = p.getGameMode();
		}
		s.cell = spot.cell();
		s.face = spot.face();
		s.lastTried = null;
		occupied.put(s.cell, p.getUniqueId());
		p.setAllowFlight(true);
		p.setFlying(true);
		p.setFlySpeed(0F);
		p.setVelocity(new Vector());
		p.setFallDistance(0);
		if (!snap(p, s.cell)) {
			toPending(p, s);
			return;
		}
		placeHelper(p, s);
		closeShell(p, s, false);
		s.keys = p.getCurrentInput();
		if (announce) showKeys(p);
	}

	/**
	 * A locked player to another spot (their own teleport, or arriving after someone else's), snapping there first; true
	 * when they got there. A refused snap on their own teleport ({@code own}) leaves them as they were: they never left
	 * the old cell, so it still holds them. After someone else's teleport they are already away from it: they let go.
	 */
	private boolean move(Player p, State s, Spot spot, boolean own) {
		if (!snap(p, spot.cell())) {
			if (!own) toPending(p, s);
			return false;
		}
		if (s.cell != null) occupied.remove(s.cell, p.getUniqueId());
		s.cell = spot.cell();
		s.face = spot.face();
		occupied.put(s.cell, p.getUniqueId());
		placeHelper(p, s);
		closeShell(p, s, false);
		hold(p, s);
		showKeys(p);
		return s.cell != null;
	}

	/** Back to free movement (as before the lock), the shulker body still waiting for a place. */
	private void toPending(Player p, State s) {
		unlock(p, s, true);
		s.lastTried = null;
	}

	private void unlock(Player p, State s, boolean keys) {
		if (s.cell != null) occupied.remove(s.cell, p.getUniqueId());
		s.cell = null;
		s.face = s.helperFace = null;
		s.open = false;
		s.openUntil = 0;
		removeHelper(s);
		armour(p, false);
		restore(p, s);
		if (keys) showKeys(p);
	}

	/** A player who went without a quit event: their helper and cell are freed. */
	private void unlockGone(UUID id) {
		State s = states.remove(id);
		if (s == null) return;
		if (s.cell != null) occupied.remove(s.cell, id);
		removeHelper(s);
	}

	/** The flight saved at locking, as the ability system has kept it since; spectators keep flying as their mode has it. */
	private void restore(Player p, State s) {
		Flight f = s.saved;
		s.saved = null;
		if (f == null) return;
		p.setFlySpeed(f.flySpeed);
		if (p.getGameMode() == GameMode.SPECTATOR) return;
		p.setAllowFlight(f.allowFlight);
		p.setFlying(f.allowFlight && f.flying);
	}

	/**
	 * The hold, every tick: flying at zero speed (a game mode change, a double-tapped Space or another plugin can stop
	 * it), no fall building up, the closed shell's armour (a respawned body loses it), and put back if anything moved them.
	 */
	private void hold(Player p, State s) {
		if (s.cell == null) return;
		if (s.saved != null && p.getGameMode() != s.mode) {
			// a game mode change not taken yet (made in this tick, say inside a teleport): taken first, and it holds again
			modeChanged(p, s);
			return;
		}
		if (s.saved != null && !p.getAllowFlight()) {
			// another plugin took their flight away while held (/fly off, a combat tag): it stays away on letting go
			s.saved.allowFlight = false;
			s.saved.flying = false;
		}
		if (p.isInsideVehicle()) p.leaveVehicle();
		if (!p.getAllowFlight()) p.setAllowFlight(true);
		if (!p.isFlying()) p.setFlying(true);
		if (p.getFlySpeed() != 0F) p.setFlySpeed(0F);
		p.setFallDistance(0);
		armour(p, !s.open);
		// moved elsewhere (an outside teleport, another world): the place where they arrived is still to be found, so
		// they are not pulled back to the old one, let alone across worlds
		if (s.reattach || p.getWorld() != s.cell.getWorld()) return;
		if (!atAnchor(p.getLocation(), s.cell) && !snap(p, s.cell)) toPending(p, s);
	}

	private static Location anchor(Block cell) {
		return new Location(cell.getWorld(), cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5);
	}

	private static boolean atAnchor(Location at, Block cell) {
		if (at.getWorld() != cell.getWorld()) return false;
		double dx = at.getX() - (cell.getX() + 0.5), dy = at.getY() - cell.getY(), dz = at.getZ() - (cell.getZ() + 0.5);
		return dx * dx + dy * dy + dz * dz <= DRIFT * DRIFT;
	}

	/**
	 * To the cell's bottom centre, keeping where they look, with no speed left; false if it was refused. The look goes as
	 * the server last had it: Paper 26.3 can't send it relative (its YAW and PITCH flags are deprecated, and passing them
	 * throws), so a mouse moving at that moment may jump back a hair.
	 */
	private boolean snap(Player p, Block cell) {
		Location to = anchor(cell);
		Location at = p.getLocation();
		to.setYaw(at.getYaw());
		to.setPitch(at.getPitch());
		teleporting = p.getUniqueId();
		try {
			return p.teleport(to);
		} finally {
			teleporting = null;
		}
	}

	// ------------------------------------------------------------------ the helper

	/**
	 * Put the helper in the cell with the face (spawned the first time, or after it was lost or the player changed
	 * world). Its peek follows the shell ({@link #helperPeek}).
	 */
	private void placeHelper(Player p, State s) {
		if (s.cell == null || s.face == null) return;
		Location at = anchor(s.cell);
		Shulker h = s.helper;
		if (h != null && h.isValid() && h.getWorld() == at.getWorld()) {
			if (!atAnchor(h.getLocation(), s.cell)) h.teleport(at);
		} else {
			removeHelper(s);
			h = spawnHelper(p, at, s.face);
			s.helper = h;
		}
		s.helperFace = s.face;
		if (h == null) {
			s.helperRetry = tick + 40;
			return;
		}
		if (h.getAttachedFace() != s.face) h.setAttachedFace(s.face);
		syncPeek(p, s); // a teleport into another block also shuts it
	}

	/**
	 * A real shulker is the only block-sized thing other entities collide with, so one stands in the cell: invisible,
	 * silent, unhurtable, without a mind or gravity, never saved. The owner never sees it (their eye is inside it, so
	 * it would catch every click they make); players without the mod don't either (Lib's Disguises' shulker is solid for
	 * them already, and a second box would split their clicks), nor spectators ({@link #showHelpers}).
	 */
	private @Nullable Shulker spawnHelper(Player owner, Location at, BlockFace face) {
		Shulker h;
		try {
			h = at.getWorld().spawn(at, Shulker.class, CreatureSpawnEvent.SpawnReason.CUSTOM, helper -> {
				helper.setVisibleByDefault(false);
				helper.setAI(false);
				helper.setInvisible(true);
				helper.setSilent(true);
				helper.setInvulnerable(true);
				helper.setCollidable(false); // solid, but it pushes nothing and nothing pushes it
				helper.setGravity(false);
				helper.setPersistent(false);
				helper.setRemoveWhenFarAway(false);
				helper.setAttachedFace(face);
				helper.getPersistentDataContainer().set(helperKey, PersistentDataType.STRING, owner.getUniqueId().toString());
			});
		} catch (IllegalArgumentException e) {
			plugin.debug("No helper shulker for " + owner.getName() + ": " + e);
			return null;
		}
		if (!h.isValid()) {
			plugin.debug("A plugin stopped the helper shulker for " + owner.getName() + " from spawning; others can walk through them.");
			return null;
		}
		helpers.put(h.getUniqueId(), owner.getUniqueId());
		for (Player viewer : plugin.channel().moddedPlayers()) {
			if (!viewer.equals(owner) && viewer.getGameMode() != GameMode.SPECTATOR) viewer.showEntity(plugin, h);
		}
		return h;
	}

	private void removeHelper(State s) {
		Shulker h = s.helper;
		s.helper = null;
		if (h == null) return;
		helpers.remove(h.getUniqueId());
		h.remove();
	}

	private @Nullable Player ownerOf(Entity helper) {
		UUID owner = helpers.get(helper.getUniqueId());
		return owner == null ? null : plugin.getServer().getPlayer(owner);
	}

	// ------------------------------------------------------------------ where to cling

	/** Becoming a shulker or arriving after a teleport: the face the crosshair is on within reach, else the floor. */
	private @Nullable Spot find(Player p) {
		if (p.isInsideVehicle()) return null;
		AttributeInstance reach = p.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
		Spot aimed = aim(p, reach == null ? 4.5 : reach.getValue(), false);
		return aimed != null ? aimed : floor(p);
	}

	/** The face the player looks at within {@code range}, if a shulker could cling there. */
	private @Nullable Spot aim(Player p, double range, boolean teleport) {
		Location eye = p.getEyeLocation();
		// grass and flowers don't stop the ray: the floor under them is what a player looking down means
		RayTraceResult hit = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true);
		if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null || !hit.getHitBlockFace().isCartesian()) return null;
		Spot spot = new Spot(hit.getHitBlock().getRelative(hit.getHitBlockFace()), hit.getHitBlockFace().getOppositeFace());
		return valid(p, spot, teleport) ? spot : null;
	}

	/** The floor: the block the feet are in, or the one above when the feet are inside a block (a path, a snow layer). */
	private @Nullable Spot floor(Player p) {
		if (p.isInsideVehicle()) return null;
		Location at = p.getLocation();
		Block feet = p.getWorld().getBlockAt(at.getBlockX(), (int) Math.floor(at.getY() + 1e-4), at.getBlockZ());
		if (!cellFree(feet)) feet = feet.getRelative(BlockFace.UP);
		Spot spot = new Spot(feet, BlockFace.DOWN);
		return valid(p, spot, false) ? spot : null;
	}

	/** A face planned at a transformation's start still means here: not left behind (another world, a long walk). */
	private static boolean near(Player p, Block cell) {
		if (p.getWorld() != cell.getWorld()) return false;
		AttributeInstance reach = p.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
		double far = (reach == null ? 4.5 : reach.getValue()) + PLAN_SLACK;
		return p.getEyeLocation().distanceSquared(cell.getLocation().add(0.5, 0.5, 0.5)) <= far * far;
	}

	/**
	 * Vanilla's {@code Shulker.canStayAt} ({@link #stays}), a little kinder for players: the cell may hold grass, flowers
	 * or a thin snow layer, just no collision, fluid or harm ({@link #cellFree}); the player's own box fits, inside the
	 * world border, no other shulker player holds the cell or opens into it; a teleport also never lands on bedrock
	 * (#shulker_does_not_teleport_to).
	 */
	private boolean valid(Player p, Spot spot, boolean teleport) {
		Block cell = spot.cell();
		World w = cell.getWorld();
		if (cell.getY() < w.getMinHeight() || cell.getY() >= w.getMaxHeight()) return false;
		if (!cellFree(cell) || !stays(p, cell, spot.face())) return false;
		if (teleport && Tag.SHULKER_DOES_NOT_TELEPORT_TO.isTagged(cell.getRelative(spot.face()).getType())) return false;
		if (!w.getWorldBorder().isInside(cell.getLocation().add(0.5, 0.5, 0.5))) return false;
		UUID holder = occupied.get(cell);
		if (holder != null && !holder.equals(p.getUniqueId())) return false;
		if (inOpening(p, cell)) return false;
		return !boxBlocked(p, cell);
	}

	/**
	 * What the helper itself checks every tick (Shulker.tick runs canStayAt with no mind too, and re-attaches on its own
	 * when it fails), so the plugin never picks a face its helper leaves the next tick: the support's collision covers
	 * the face (vanilla's rule: leaves count, soul sand and mud don't), and the shell fully opened (the cell and the
	 * block it opens into) runs into no block, no shulker or boat but the player's own helper, and stays inside the
	 * world border.
	 */
	private boolean stays(Player p, Block cell, @Nullable BlockFace face) {
		if (face == null || !holds(cell, face)) return false;
		Block into = cell.getRelative(face.getOppositeFace());
		World w = cell.getWorld();
		if (!w.getWorldBorder().isInside(into.getLocation().add(0.5, 0.5, 0.5))) return false;
		BoundingBox open = BoundingBox.of(cell, into).expand(-1e-6);
		if (hitsBlocks(w, open)) return false;
		State s = states.get(p.getUniqueId());
		Shulker own = s == null ? null : s.helper;
		return w.getNearbyEntities(open, e -> (e instanceof Shulker || e instanceof Boat) && !e.equals(own) && !e.isDead()).isEmpty();
	}

	/** {@code cell} is the block another held shulker opens into: a helper there would knock that one off its face. */
	private boolean inOpening(Player p, Block cell) {
		for (Map.Entry<UUID, State> e : states.entrySet()) {
			State o = e.getValue();
			if (o.cell != null && o.face != null && !e.getKey().equals(p.getUniqueId()) && cell.equals(o.cell.getRelative(o.face.getOppositeFace()))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Nothing in the cell to collide with, no water or lava, and nothing that harms or carries off whoever stays in it:
	 * fire, wither roses and powder snow have no collision (powder snow none for a shape read without an entity), and a
	 * portal would take the held player away. A held cell that gains one lets go.
	 */
	private static boolean cellFree(Block cell) {
		Material type = cell.getType();
		if (Tag.FIRE.isTagged(type) || type == Material.WITHER_ROSE || type == Material.POWDER_SNOW || type == Material.NETHER_PORTAL
				|| type == Material.END_PORTAL || type == Material.END_GATEWAY) {
			return false;
		}
		if (!cell.getCollisionShape().getBoundingBoxes().isEmpty()) return false;
		return cell.getWorld().getFluidData(cell.getX(), cell.getY(), cell.getZ()).getFluidType() == Fluid.EMPTY;
	}

	/**
	 * Whether the block on {@code face} of {@code cell} can hold a shulker: its collision shape covers the face toward
	 * the cell, as vanilla has it (leaves count). Only the collision: soul sand and mud are sturdy but 14/16 tall, and
	 * the helper would leave them for the next face it finds.
	 */
	private static boolean holds(Block cell, @Nullable BlockFace face) {
		if (face == null || !face.isCartesian()) return false;
		return covers(cell.getRelative(face).getCollisionShape().getBoundingBoxes(), face.getOppositeFace());
	}

	/** The boxes (block-relative) cover the whole of the block's {@code side}, checked at every pixel's centre. */
	private static boolean covers(Collection<BoundingBox> boxes, BlockFace side) {
		if (boxes.isEmpty()) return false;
		int axis = side.getModX() != 0 ? 0 : side.getModY() != 0 ? 1 : 2;
		boolean high = side.getModX() + side.getModY() + side.getModZ() > 0;
		int u = axis == 0 ? 1 : 0, v = axis == 2 ? 1 : 2;
		List<double[][]> flush = new ArrayList<>();
		for (BoundingBox b : boxes) {
			double[] min = {b.getMinX(), b.getMinY(), b.getMinZ()}, max = {b.getMaxX(), b.getMaxY(), b.getMaxZ()};
			if (high ? max[axis] >= 1 - 1e-6 : min[axis] <= 1e-6) flush.add(new double[][] {min, max});
		}
		if (flush.isEmpty()) return false;
		for (int i = 0; i < 16; i++) {
			double a = (i + 0.5) / 16;
			for (int j = 0; j < 16; j++) {
				double b = (j + 0.5) / 16;
				boolean hit = false;
				for (double[][] box : flush) {
					if (box[0][u] <= a && a <= box[1][u] && box[0][v] <= b && b <= box[1][v]) {
						hit = true;
						break;
					}
				}
				if (!hit) return false;
			}
		}
		return true;
	}

	/**
	 * The player's own box, standing at the cell's bottom centre with the shulker body's size, runs into a block: always
	 * fine with hitbox scaling (the body is a block tall), but without it the box is a player's and needs the cell above.
	 */
	private boolean boxBlocked(Player p, Block cell) {
		MorphRef form = shulkerForm(p);
		return boxBlocked(cell, form == null ? 1 : plugin.morphs().bodyScale(p, form));
	}

	/**
	 * The box the player has until a transformation's half-way swap (the body they transform from) fits at {@code cell}'s
	 * bottom centre, blocks only: where a lock may hold them before the body is a shulker.
	 */
	private boolean fitsBody(Player p, Block cell) {
		ActiveMorph am = plugin.morphs().state(p);
		return !boxBlocked(cell, plugin.morphs().bodyScale(p, am == null ? null : am.activeForm()));
	}

	/** A standing player's box at {@code scale}, at the cell's bottom centre, runs into a block. */
	private static boolean boxBlocked(Block cell, double scale) {
		double half = PLAYER_WIDTH * scale / 2, height = PLAYER_HEIGHT * scale;
		double cx = cell.getX() + 0.5, cz = cell.getZ() + 0.5, y = cell.getY();
		return hitsBlocks(cell.getWorld(), new BoundingBox(cx - half, y, cz - half, cx + half, y + height, cz + half).expand(-1e-5));
	}

	/** A block's collision runs into {@code box}; the layer below too, since fences and walls reach up out of their block. */
	private static boolean hitsBlocks(World w, BoundingBox box) {
		for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX()); x++) {
			for (int y = (int) Math.floor(box.getMinY()) - 1; y <= (int) Math.floor(box.getMaxY()); y++) {
				for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ()); z++) {
					for (BoundingBox part : w.getBlockAt(x, y, z).getCollisionShape().getBoundingBoxes()) {
						if (part.clone().shift(x, y, z).overlaps(box)) return true;
					}
				}
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ sneak + jump: the shulker's teleport

	private void teleport(Player p, State s) {
		if (!plugin.abilities().isEnabled() || !p.hasPermission("morphreborn.ability") || plugin.morphs().isTransitioning(p)) return;
		String locked = plugin.mode().cannotUseAbility(p);
		if (locked != null) {
			p.sendActionBar(Component.text(locked, NamedTextColor.GRAY));
			return;
		}
		if (tick < s.teleportReady) return;
		Spot spot = aim(p, teleportRange, true);
		if (spot == null) {
			p.sendActionBar(Component.text("Nowhere to attach there.", NamedTextColor.GRAY));
			return;
		}
		if (spot.cell().equals(s.cell) && spot.face() == s.face) return; // already there
		Location from = p.getLocation();
		Block fromBlock = from.getBlock();
		boolean moved;
		if (s.cell == null) {
			lock(p, s, spot, true);
			moved = s.cell != null;
		} else {
			moved = move(p, s, spot, true);
		}
		if (!moved) return; // refused (another plugin cancelled the teleport): no cooldown, and a held player still is
		s.teleportReady = tick + teleportCooldownTicks;
		World w = p.getWorld();
		w.playSound(from, Sound.ENTITY_SHULKER_TELEPORT, 1F, 1F);
		int dx = s.cell.getX() - fromBlock.getX(), dy = s.cell.getY() - fromBlock.getY(), dz = s.cell.getZ() - fromBlock.getZ();
		if (from.getWorld() == w && Math.abs(dx) <= 8 && Math.abs(dy) <= 8 && Math.abs(dz) <= 8) {
			// vanilla's teleport particles, from the old block toward the new (BlockUtil.packDifferenceInPosition, radius 8)
			w.playEffect(fromBlock.getLocation(), Effect.SHULKER_TELEPORT, (((dx + 8) & 0xFF) << 16) | (((dy + 8) & 0xFF) << 8) | ((dz + 8) & 0xFF));
		}
		w.playSound(anchor(s.cell), Sound.ENTITY_SHULKER_TELEPORT, 1F, 1F);
	}

	/**
	 * Sneak held and jump newly pressed. PlayerJumpEvent never fires for a flying player, but the input packet comes from
	 * every client, with or without the mod, flying or not.
	 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onInput(PlayerInputEvent event) {
		Player p = event.getPlayer();
		State s = states.get(p.getUniqueId());
		if (s == null) return;
		org.bukkit.Input was = s.keys, now = event.getInput();
		s.keys = now;
		if (now.isSneak() && now.isJump() && !was.isJump()) teleport(p, s);
	}

	// ------------------------------------------------------------------ keeping the hold

	/** A double-tapped Space would stop the flying that holds them (Paper sends the abilities back). */
	@EventHandler(ignoreCancelled = true)
	public void onToggleFlight(PlayerToggleFlightEvent event) {
		if (!event.isFlying() && isLocked(event.getPlayer())) event.setCancelled(true);
	}

	/** Real shulkers ignore knockback. */
	@EventHandler(ignoreCancelled = true)
	public void onKnockback(EntityKnockbackEvent event) {
		if (event.getEntity() instanceof Player p && isLocked(p)) event.setCancelled(true);
	}

	/** Nothing else moves a locked player either (explosions, wind charges, other plugins); stopping still is allowed. */
	@EventHandler(ignoreCancelled = true)
	public void onVelocity(PlayerVelocityEvent event) {
		if (isLocked(event.getPlayer()) && event.getVelocity().lengthSquared() > 1e-8) event.setCancelled(true);
	}

	@EventHandler(ignoreCancelled = true)
	public void onVehicleEnter(VehicleEnterEvent event) {
		if (event.getEntered() instanceof Player p && isLocked(p)) event.setCancelled(true);
	}

	@EventHandler(ignoreCancelled = true)
	public void onMount(EntityMountEvent event) {
		if (event.getEntity() instanceof Player p && isLocked(p)) event.setCancelled(true);
	}

	/**
	 * Moved by something else (a pearl, /tp, a borrowed enderman's teleport): next tick it clings where it arrived, by
	 * the same rules as becoming one. A teleport into its own cell changes nothing.
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onTeleport(PlayerTeleportEvent event) {
		Player p = event.getPlayer();
		if (p.getUniqueId().equals(teleporting)) return;
		State s = states.get(p.getUniqueId());
		if (s == null) return;
		Location from = event.getFrom(), to = event.getTo();
		boolean world = from.getWorld() != to.getWorld();
		if (s.cell != null) {
			Block arrives = to.getWorld().getBlockAt(to.getBlockX(), (int) Math.floor(to.getY() + 0.5), to.getBlockZ());
			if (world || !arrives.equals(s.cell)) s.reattach = true;
			if (world) removeHelper(s);
		} else if (world || from.distanceSquared(to) > PENDING_TELEPORT * PENDING_TELEPORT) {
			s.reattach = true;
		}
	}

	/** Portals change the world without a PlayerTeleportEvent this hears: the helper stays behind, so it goes. */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onWorldChange(PlayerChangedWorldEvent event) {
		if (event.getPlayer().getUniqueId().equals(teleporting)) return;
		State s = states.get(event.getPlayer().getUniqueId());
		if (s == null) return;
		s.reattach = true;
		removeHelper(s);
	}

	/**
	 * A player with the mod going into spectator stops seeing the helpers (a spectator's game draws invisible mobs
	 * see-through: a second shell inside every held shulker), and gets them back on leaving it (never their own).
	 * The event comes before the change, so the old mode is still the player's.
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onViewerGameMode(PlayerGameModeChangeEvent event) {
		Player viewer = event.getPlayer();
		boolean was = viewer.getGameMode() == GameMode.SPECTATOR, now = event.getNewGameMode() == GameMode.SPECTATOR;
		if (was == now || !plugin.channel().isModded(viewer)) return;
		for (Map.Entry<UUID, State> e : states.entrySet()) {
			Shulker h = e.getValue().helper;
			if (h == null || !h.isValid() || e.getKey().equals(viewer.getUniqueId())) continue;
			if (now) viewer.hideEntity(plugin, h);
			else viewer.showEntity(plugin, h);
		}
	}

	/** The body goes with the death: the helper too, and the flight back as it was (a respawn finds a new place). */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDeath(PlayerDeathEvent event) {
		release(event.getPlayer());
		plans.remove(event.getPlayer().getUniqueId());
	}

	// ------------------------------------------------------------------ the shell's protection

	/** A closed shell: arrows and tridents bounce off, as off a closed shulker (a cancelled hit deflects the arrow). */
	@EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
	public void onShellHit(EntityDamageByEntityEvent event) {
		if (event.getEntity() instanceof Player p && event.getDamageSource().getDirectEntity() instanceof AbstractArrow && closedShell(p)) {
			event.setCancelled(true);
		}
	}

	// ------------------------------------------------------------------ the helper stands for its owner

	/** Players with the mod see the helper, so their clicks land on it: the hit is the owner's. */
	@EventHandler(ignoreCancelled = true)
	public void onAttackHelper(PrePlayerAttackEntityEvent event) {
		if (!isHelper(event.getAttacked())) return;
		event.setCancelled(true);
		Player owner = ownerOf(event.getAttacked());
		if (owner != null && !owner.equals(event.getPlayer()) && event.willAttack()) event.getPlayer().attack(owner);
	}

	/**
	 * Projectiles meet the helper before the player inside it. A closed shell lets arrows hit it, and they bounce off as
	 * off a closed shulker; anything else is the owner's hit. The owner's own shots start inside it and never hit it.
	 * <p>
	 * An arrow hits every entity on its step at once, and the owner's box sits inside the helper's, so a step that
	 * reaches the owner holds both: only the first of the two goes through ({@link #onShellArrowHit}). A closed shell
	 * then reverses the arrow once (a bounce, not two that leave it creeping into the cell), an open one is hit once. A
	 * hit on the owner alone (a body reaching above the shell, an arrow starting inside the helper) still lands.
	 */
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
	public void onProjectileHit(ProjectileHitEvent event) {
		Entity hit = event.getHitEntity();
		Projectile projectile = event.getEntity();
		if (projectile instanceof AbstractArrow && !forwarding) {
			Player shell = shellOf(hit);
			if (shell != null && projectile.getUniqueId().equals(shellArrow) && shell.getUniqueId().equals(shellArrowOwner) && tick == shellArrowTick) {
				event.setCancelled(true); // the pair already had this step's hit
				return;
			}
		}
		if (!isHelper(hit)) return;
		Player owner = ownerOf(hit);
		if (owner == null || owner.equals(projectile.getShooter())) {
			event.setCancelled(true);
			return;
		}
		if (projectile instanceof AbstractArrow && closedShell(owner)) return;
		event.setCancelled(true);
		forwarding = true;
		try {
			projectile.hitEntity(owner);
		} finally {
			forwarding = false;
		}
	}

	/**
	 * An arrow hit on a shell that went through (the forwarded one included), remembered for the rest of the step. Only
	 * hits nobody cancelled: one another plugin stopped (a dodge) leaves the other entry its chance.
	 */
	@EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
	public void onShellArrowHit(ProjectileHitEvent event) {
		if (!(event.getEntity() instanceof AbstractArrow arrow)) return;
		Player shell = shellOf(event.getHitEntity());
		if (shell == null) return;
		shellArrow = arrow.getUniqueId();
		shellArrowOwner = shell.getUniqueId();
		shellArrowTick = tick;
	}

	/** The player whose shell {@code e} is part of: a helper's owner, or a held player whose helper stands around them. */
	private @Nullable Player shellOf(@Nullable Entity e) {
		if (e == null) return null;
		if (isHelper(e)) return ownerOf(e);
		if (!(e instanceof Player p)) return null;
		State s = states.get(p.getUniqueId());
		return s != null && s.cell != null && s.helper != null && s.helper.isValid() ? p : null;
	}

	/**
	 * Helpers can't be hurt. A direct hit someone deals it (another plugin's ability aimed at what it sees) is the
	 * owner's; blasts and sweeps reach the owner by themselves.
	 */
	@EventHandler(priority = EventPriority.LOWEST)
	public void onHelperHurt(EntityDamageEvent event) {
		if (!isHelper(event.getEntity())) return;
		event.setCancelled(true);
		if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK || !(event instanceof EntityDamageByEntityEvent)) return;
		Player owner = ownerOf(event.getEntity());
		Entity by = event.getDamageSource().getCausingEntity();
		if (owner != null && by != null && !by.equals(owner)) owner.damage(event.getDamage(), event.getDamageSource());
	}

	/** Golems hunt enemies and a shulker is one: never the helper (being invulnerable already rules it out; this is sure). */
	@EventHandler(ignoreCancelled = true)
	public void onTargetHelper(EntityTargetEvent event) {
		if (isHelper(event.getTarget())) event.setCancelled(true);
	}

	/** No effects (a glowing helper would show), no fire on it. */
	@EventHandler(ignoreCancelled = true)
	public void onHelperEffect(EntityPotionEffectEvent event) {
		if (isHelper(event.getEntity()) && event.getNewEffect() != null) event.setCancelled(true);
	}

	@EventHandler(ignoreCancelled = true)
	public void onHelperBurn(EntityCombustEvent event) {
		if (isHelper(event.getEntity())) event.setCancelled(true);
	}

	/**
	 * Leads, name tags and the like: nothing to do with a helper. Players with the mod click the helper (it surrounds the
	 * owner), so the click is the owner's, as a click on Lib's Disguises' shulker is for everyone else (milking a shulker
	 * wearing a cow's head, curing, igniting). Only the plain event comes here (the at-position one has its own handler
	 * list), so each click is passed on once.
	 */
	@EventHandler(ignoreCancelled = true)
	public void onInteractHelper(PlayerInteractEntityEvent event) {
		if (!isHelper(event.getRightClicked())) return;
		event.setCancelled(true);
		Player owner = ownerOf(event.getRightClicked());
		if (owner != null && !owner.equals(event.getPlayer())) new PlayerInteractEntityEvent(event.getPlayer(), owner, event.getHand()).callEvent();
	}

	/** Killed anyway (/kill gets past invulnerability): no shells, no experience; the next tick puts a new one in. */
	@EventHandler(priority = EventPriority.LOWEST)
	public void onHelperDeath(EntityDeathEvent event) {
		if (!isHelper(event.getEntity()) && !stray(event.getEntity())) return;
		event.getDrops().clear();
		event.setDroppedExp(0);
	}

	@EventHandler
	public void onEntitiesLoad(EntitiesLoadEvent event) {
		for (Entity e : event.getEntities()) if (stray(e)) e.remove();
	}

	// ------------------------------------------------------------------ what viewers see

	private static byte faceId(BlockFace face) {
		for (byte i = 0; i < FACES.length; i++) if (FACES[i] == face) return i;
		return 0;
	}

	private static String keys(@Nullable BlockFace face, int peek) {
		return face == null ? "" : "{" + ATTACH_FACE + ":" + faceId(face) + "b," + PEEK + ":" + peek + "b}";
	}

	/**
	 * The face the overlay should carry now, as the contract has it: only while the body shown ({@code next}) is a
	 * shulker and the player is held in its cell (from a transformation's start with an early lock, else from the
	 * swap); null otherwise. So the keys always mean "the shell is in this player's block, on this face": a place only
	 * planned is never shown, since the player isn't there yet.
	 */
	private @Nullable BlockFace shownFace(Player p) {
		ActiveMorph am = plugin.morphs().state(p);
		if (am == null || !am.isMorphed() || !isShulker(am.look())) return null;
		State s = states.get(p.getUniqueId());
		return s != null && s.cell != null ? s.face : null;
	}

	private int shownPeek(Player p) {
		State s = states.get(p.getUniqueId());
		return s != null && s.cell != null && s.open ? 100 : 0;
	}

	/** Put the overlay keys right: added, changed or taken out. Other keys in the overlay are left as they are. */
	private void showKeys(Player p) {
		ActiveMorph am = plugin.morphs().state(p);
		if (am == null || !am.isMorphed()) return;
		String overlay = plugin.morphs().overlay(p);
		String next = merged(overlay, shownFace(p), shownPeek(p));
		if (!next.equals(overlay)) plugin.morphs().setOverlay(p, next);
	}

	/**
	 * MorphManager.setOverlay: another look replacing the overlay (a borrowed bee's anger, a borrowed armadillo rolling
	 * up) keeps the face, so a shulker on a wall or ceiling never shows on the floor for a tick; and an overlay that
	 * isn't the lock's own never brings in keys for a shell that isn't held.
	 */
	public String withKeys(Player p, String snbt) {
		return merged(snbt, shownFace(p), shownPeek(p));
	}

	/** {@code overlay} with the keys for {@code face} and {@code peek} (none for a null face); the same string if it has them. */
	private static String merged(String overlay, @Nullable BlockFace face, int peek) {
		if (overlay.equals(keys(face, peek))) return overlay;
		if (face == null && !overlay.contains(ATTACH_FACE) && !overlay.contains(PEEK)) return overlay; // nothing to take out
		Map<String, Object> tags;
		try {
			tags = overlay.isBlank() ? new LinkedHashMap<>() : new LinkedHashMap<>(Snbt.parseCompound(overlay));
		} catch (IllegalArgumentException e) {
			tags = new LinkedHashMap<>(); // nothing readable to keep
		}
		if (face == null) {
			if (!tags.containsKey(ATTACH_FACE) && !tags.containsKey(PEEK)) return overlay;
			tags.remove(ATTACH_FACE);
			tags.remove(PEEK);
		} else {
			byte id = faceId(face);
			if (tags.get(ATTACH_FACE) instanceof Number f && f.intValue() == id && tags.get(PEEK) instanceof Number k && k.intValue() == peek) return overlay;
			tags.put(ATTACH_FACE, id);
			tags.put(PEEK, (byte) peek);
		}
		return tags.isEmpty() ? "" : Snbt.write(tags);
	}
}
