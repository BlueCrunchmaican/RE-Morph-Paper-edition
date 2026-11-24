package dev.morphreborn.paper.disguise;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.armadillo.ArmadilloState;
import com.github.retrooper.packetevents.protocol.entity.data.struct.CopperGolemState;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import dev.morphreborn.paper.PluginSettings;
import dev.morphreborn.paper.data.MorphRef;
import dev.morphreborn.paper.disguise.BorrowedHeads.Shell;
import dev.morphreborn.paper.nbt.Snbt;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.libraryaddict.disguise.DisguiseAPI;
import me.libraryaddict.disguise.DisguiseConfig;
import me.libraryaddict.disguise.disguisetypes.Disguise;
import me.libraryaddict.disguise.disguisetypes.FlagWatcher;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import me.libraryaddict.disguise.disguisetypes.TargetedDisguise;
import me.libraryaddict.disguise.disguisetypes.watchers.ArmadilloWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.BatWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.BeeWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.CopperGolemWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.CreeperWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.EndermanWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.GhastWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.HoglinWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.IllagerWizardWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.InsentientWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.LivingWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.PandaWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.PiglinAbstractWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.PiglinWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.PillagerWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.PolarBearWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.SheepWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.ShulkerWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.SlimeWatcher;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.AbstractCubeMob;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Bee;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Spellcaster;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The only class that talks to Lib's Disguises. Disguises are what <em>unmodded</em> clients see; players with the
 * Fabric companion are kept in each disguise's exclusion list so they keep receiving the real player entity and
 * render the morph themselves.
 * <p>
 * The watcher scale is always set explicitly. Lib's Disguises otherwise carries the player's own scale attribute
 * (which we use for the hitbox) over to the disguise, which is what made mobs render at the wrong size with the
 * old Morph plugin.
 */
public final class DisguiseBridge {
	private final Logger logger;
	private final Map<UUID, TargetedDisguise> active = new HashMap<>();
	private boolean playerDisguisesWork = true;

	public DisguiseBridge(Logger logger) {
		this.logger = logger;
	}

	/**
	 * Each disguise's own size, which every scale given for it is a share of (see {@link #show}); absent = 1.
	 */
	private final Map<UUID, Double> bases = new HashMap<>();

	/**
	 * Disguise {@code player} as {@code ref} for everyone except {@code excluded}.
	 *
	 * @param template    unspawned entity holding the variant's data (null for player-type morphs)
	 * @param base        the disguise's own size: a giant drawn as the player it borrows from is that player at
	 *                    {@link GiantPlayer#GIANT_PLAYER_SCALE}. {@code scale} and every later {@link #setScale(Player, double)}
	 *                    are shares of it, so a transformation's shrink and growth, which end on 1.0, end on this size
	 * @param selfVisible let the player see their own disguise (vanilla clients only)
	 * @return false if the disguise could not be created
	 */
	public boolean show(Player player, MorphRef ref, @Nullable LivingEntity template, double base, double scale, Collection<Player> excluded,
			boolean selfVisible, PluginSettings.NameTagMode nameTags) {
		TargetedDisguise disguise = build(player, ref, template);
		if (disguise == null) return false;
		if (base == 1.0) bases.remove(player.getUniqueId());
		else bases.put(player.getUniqueId(), base);
		configure(player, disguise, excluded, selfVisible);
		applyNameTag(player, disguise, nameTags);
		neverConvert(disguise);
		if (template != null) {
			copySize(disguise, template);
			templates.put(player.getUniqueId(), template);
		} else {
			templates.remove(player.getUniqueId());
		}
		// A held shulker is born on its face, open as far as it is and with its head turned: set before start() it goes
		// out with the spawn, where set after it the viewers' games would draw it on the floor first.
		if (disguise.getWatcher() instanceof ShulkerWatcher shulker) {
			try {
				String overlay = overlayNow(player);
				if (overlay.isEmpty()) overlays.remove(player.getUniqueId());
				else overlays.put(player.getUniqueId(), overlay);
				shell(player, disguise, shulker, Shell.of(overlay), template);
			} catch (RuntimeException | LinkageError e) {
				logger.log(Level.FINE, "Could not put the shulker on its face", e);
			}
		}
		// after the face: a shell on a wall or ceiling keeps its full size (see setScale), and the spawn carries it
		setScale(disguise, scale * base);
		return start(player, disguise);
	}

	/**
	 * The player's overlay as the morph state has it now (MorphManager.overlay): a disguise is only ever built for the
	 * body that state shows. A transformation sets its overlay without passing it here (a shulker's hold when the lock put
	 * the player in its cell as it began; otherwise the hold comes with the half-way swap, through applyOverlay), so the
	 * last one applied may be out of date when the new body's disguise is built. That one is the fallback.
	 */
	private String overlayNow(Player player) {
		try {
			return org.bukkit.plugin.java.JavaPlugin.getPlugin(dev.morphreborn.paper.MorphRebornPlugin.class).morphs().overlay(player);
		} catch (RuntimeException | LinkageError e) {
			return overlays.getOrDefault(player.getUniqueId(), "");
		}
	}

	/**
	 * Piglins and hoglins outside the Nether count as "converting" to zombified mobs on vanilla clients, which shake
	 * the body left and right non-stop. Lib's Disguises calls the immune-to-zombification flag "shaking" (inverted).
	 */
	private void neverConvert(TargetedDisguise disguise) {
		try {
			if (disguise.getWatcher() instanceof PiglinAbstractWatcher piglin) piglin.setShaking(false);
			else if (disguise.getWatcher() instanceof HoglinWatcher hoglin) hoglin.setShaking(false);
		} catch (RuntimeException | LinkageError e) {
			logger.log(Level.FINE, "Could not stop the zombification shake", e);
		}
	}

	/** The template each disguise was built from: the variant's own state, which overlays fall back to. */
	private final Map<UUID, LivingEntity> templates = new HashMap<>();

	/**
	 * Cube mobs: Lib's Disguises sizes the disguise itself, which made a small sulfur cube render huge. The template
	 * holds the variant's real size and age (a size-1 sulfur cube is also a baby, with its own smaller model).
	 */
	private void copySize(TargetedDisguise disguise, LivingEntity template) {
		try {
			if (disguise.getWatcher() instanceof SlimeWatcher slime && template instanceof AbstractCubeMob cube) {
				slime.setSize(cube.getSize());
				if (template instanceof Ageable ageable) slime.setBaby(!ageable.isAdult());
			}
		} catch (RuntimeException | LinkageError e) {
			logger.log(Level.FINE, "Could not copy the cube size onto the disguise", e);
		}
	}

	/**
	 * MORPH: player morphs carry the copied player's name (set in {@link #build}); mob morphs keep only the mob's own
	 * custom name copied from the template, so the real player's name is never shown. PLAYER / HIDDEN override that.
	 */
	private static void applyNameTag(Player player, TargetedDisguise disguise, PluginSettings.NameTagMode mode) {
		if (disguise instanceof PlayerDisguise playerDisguise) {
			switch (mode) {
				case PLAYER -> playerDisguise.setName(player.getName());
				case HIDDEN -> playerDisguise.setNameVisible(false);
				case MORPH -> { }
			}
			return;
		}
		FlagWatcher watcher = disguise.getWatcher();
		switch (mode) {
			case PLAYER -> {
				watcher.setCustomName(player.getName());
				watcher.setCustomNameVisible(true);
			}
			case HIDDEN -> {
				watcher.setCustomName(null);
				watcher.setCustomNameVisible(false);
			}
			case MORPH -> { }
		}
	}

	/** A copy of the player's own body, used to shrink/grow the player for vanilla viewers during a transition. */
	public boolean showSelfCopy(Player player, double scale, Collection<Player> excluded, boolean selfVisible) {
		if (!playerDisguisesWork) return false;
		try {
			PlayerDisguise disguise = new PlayerDisguise(player);
			configure(player, disguise, excluded, selfVisible);
			bases.remove(player.getUniqueId());
			setScale(disguise, scale);
			return start(player, disguise);
		} catch (RuntimeException | LinkageError e) {
			playerDisguisesWork = false;
			logger.log(Level.WARNING, "Lib's Disguises refused a player disguise; vanilla transitions will skip the player shrink step.", e);
			return false;
		}
	}

	/** What each disguise currently shows per slot, so equipment packets only go out when something changes. */
	private final Map<UUID, Map<EquipmentSlot, ItemStack>> shownEquipment = new HashMap<>();
	/** Multimorph: what the disguise wears on its head instead of the player's helmet (a borrowed form's skull). */
	private final Map<UUID, ItemStack> headOverride = new HashMap<>();

	/** Put {@code item} on the disguise's head in place of the player's helmet, or (null) give the helmet back. */
	public void setHeadOverride(Player player, @Nullable ItemStack item) {
		UUID id = player.getUniqueId();
		ItemStack before = item == null ? headOverride.remove(id) : headOverride.put(id, item);
		if (java.util.Objects.equals(before, item)) return;
		Map<EquipmentSlot, ItemStack> shown = shownEquipment.get(id);
		if (shown != null) shown.remove(EquipmentSlot.HEAD);
		syncEquipment(player);
	}
	private static final EquipmentSlot[] GEAR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HAND,
			EquipmentSlot.OFF_HAND};

	/**
	 * Show the player's current armour and held items on their disguise (original Morph behaviour: the morph wears what
	 * you wear). Gear the copied player had when they were killed is never stored or shown.
	 */
	public void syncEquipment(Player player) {
		TargetedDisguise disguise = active.get(player.getUniqueId());
		if (disguise == null) return;
		Map<EquipmentSlot, ItemStack> shown = shownEquipment.computeIfAbsent(player.getUniqueId(), k -> new EnumMap<>(EquipmentSlot.class));
		EntityEquipment equipment = player.getEquipment();
		FlagWatcher watcher = disguise.getWatcher();
		for (EquipmentSlot slot : GEAR) {
			if (slot == EquipmentSlot.HAND && watcher instanceof EndermanWatcher) continue; // its "hand" is the carried block
			ItemStack item = slot == EquipmentSlot.HEAD && headOverride.containsKey(player.getUniqueId()) ? headOverride.get(player.getUniqueId())
					: equipment.getItem(slot);
			ItemStack last = shown.get(slot);
			if (last != null && last.equals(item)) continue;
			ItemStack copy = item.clone();
			shown.put(slot, copy);
			try {
				watcher.setItemStack(slot, copy);
			} catch (RuntimeException e) {
				logger.log(Level.FINE, "Could not mirror " + slot + " onto disguise", e);
			}
		}
	}

	/**
	 * The last overlay each player was given, disguised or not (a shulker's hold is set before its disguise exists, as
	 * the transformation begins or at its half-way swap), so a disguise built later starts with it (see {@link #show}).
	 */
	private final Map<UUID, String> overlays = new HashMap<>();

	/** Show the live appearance overlay (see MorphManager.setOverlay) on the vanilla disguise. */
	public void applyOverlay(Player player, String overlaySnbt) {
		if (overlaySnbt.isEmpty()) overlays.remove(player.getUniqueId());
		else overlays.put(player.getUniqueId(), overlaySnbt);
		TargetedDisguise disguise = active.get(player.getUniqueId());
		if (disguise == null) return;
		Map<String, Object> overlay;
		try {
			overlay = overlaySnbt.isEmpty() ? Map.of() : Snbt.parseCompound(overlaySnbt);
		} catch (IllegalArgumentException e) {
			return;
		}
		// Keys missing from the overlay fall back to the variant itself (a captured sheep may already be bald).
		LivingEntity template = templates.get(player.getUniqueId());
		FlagWatcher watcher = disguise.getWatcher();
		try {
			if (watcher instanceof EndermanWatcher enderman) enderman.setHeldBlock(SpigotConversionUtil.fromBukkitBlockData(carriedBlock(overlay)));
			if (watcher instanceof CreeperWatcher creeper) {
				creeper.setIgnited(flag(overlay, "ignited", false));
				// a charged-creeper morph is charged by itself; any creeper can be charged by lightning (overlay)
				creeper.setPowered(flag(overlay, "powered", template instanceof org.bukkit.entity.Creeper c && c.isPowered()));
			}
			if (watcher instanceof SheepWatcher sheep) sheep.setSheared(flag(overlay, "Sheared", template instanceof Sheep s && s.isSheared()));
			if (watcher instanceof BeeWatcher bee) {
				bee.setHasStung(flag(overlay, "HasStung", template instanceof Bee b && b.hasStung()));
				// angry (red eyes) until the game time given: an angry bee morph's is as far off as it goes
				bee.setAngryUntil(overlay.get("anger_end_time") instanceof Number until ? until.longValue() : -1L);
			}
			if (watcher instanceof ArmadilloWatcher armadillo) armadillo.setState("scared".equals(overlay.get("state")) ? ArmadilloState.SCARED : ArmadilloState.IDLE);
			if (watcher instanceof BatWatcher bat) bat.setHanging(flag(overlay, "BatFlags", false));
			if (watcher instanceof ShulkerWatcher shulker) shell(player, disguise, shulker, Shell.of(overlay), template);
			shake(watcher, flag(overlay, "MorphShaking", false));
		} catch (RuntimeException | LinkageError e) {
			logger.log(Level.FINE, "Could not apply overlay " + overlaySnbt, e);
		}
	}

	// ------------------------------------------------------------------ a shulker held to a block

	/**
	 * A held shulker as the viewers' games were last shown it: the block it snapped to, and its head's yaw in the shell's
	 * frame (kept while the player looks straight along the shell's axis, where there is none).
	 */
	private static final class ShellView {
		@Nullable UUID world;
		int x, y, z;
		float yaw;

		/** Whether {@code at} snaps to another block than last time (remembering it). */
		boolean moved(Location at) {
			UUID w = at.getWorld().getUID();
			int bx = (int) Math.floor(at.getX()), by = (int) Math.floor(at.getY() + 0.5), bz = (int) Math.floor(at.getZ());
			if (w.equals(world) && bx == x && by == y && bz == z) return false;
			world = w;
			x = bx;
			y = by;
			z = bz;
			return true;
		}
	}

	/** Held shulkers, by player (see {@link BorrowedHeads.Shell}). */
	private final Map<UUID, ShellView> shells = new HashMap<>();
	/** Degrees a held shulker's head turns before the viewers are told (a head-look packet's step is 1.4). */
	private static final float HEAD_STEP = 1F;

	/**
	 * Put a shulker disguise on the face it holds on to, open as far as its shell is ({@code shell}; null: a free shulker,
	 * as the variant itself is, the floor and shut). On a wall or ceiling its head is held to its yaw in the shell's frame
	 * and never tilted, as a vanilla shulker turns its head: Lib's Disguises puts the locks in every turn it sends for the
	 * player, and the player's own world yaw and pitch would spin it about the wrong axis. On a floor that frame is the
	 * world's, so its head turns with the player's look as any creature's does, but never tilts there either (the mod and
	 * a head lent to the shell keep it level too). A free shulker's head is left to the player's look altogether, as before.
	 */
	private void shell(Player player, TargetedDisguise disguise, ShulkerWatcher watcher, @Nullable Shell shell, @Nullable LivingEntity template) {
		org.bukkit.entity.Shulker variant = template instanceof org.bukkit.entity.Shulker s ? s : null;
		BlockFace face = shell != null ? shell.face() : variant != null && variant.getAttachedFace().isCartesian() ? variant.getAttachedFace() : BlockFace.DOWN;
		int peek = shell != null ? shell.peek() : variant != null ? Math.clamp(Math.round(variant.getPeek() * 100F), 0, 100) : 0;
		// every set goes out to the viewers again, even unchanged
		if (watcher.getFacingDirection() != face) watcher.setFacingDirection(face);
		if (watcher.getShieldHeight() != peek) watcher.setShieldHeight(peek);
		// held onto a wall or ceiling while a transformation still has it small (the hold can come with the half-way swap,
		// or move later): full size from now, not from the next tick's scale (see setScale)
		if (face != BlockFace.DOWN) setScale(disguise, 1.0);
		if (shell == null) {
			shells.remove(player.getUniqueId());
			freeHead(player, disguise, watcher);
			return;
		}
		// a move is left for tickShells to see, even one that came with this change: the peek has to follow it
		ShellView view = shells.computeIfAbsent(player.getUniqueId(), k -> new ShellView());
		if (shell.floor()) floorHead(player, disguise, watcher);
		else holdHead(player, disguise, watcher, shell, view);
	}

	/** A held shulker's head on a wall or ceiling: its yaw in the shell's frame, never tilted (see {@link #shell}). */
	private void holdHead(Player player, TargetedDisguise disguise, ShulkerWatcher watcher, Shell shell, ShellView view) {
		float yaw = shell.headYaw(player.getLocation());
		if (Float.isNaN(yaw)) yaw = view.yaw;
		else view.yaw = yaw;
		Float pitch = watcher.getPitchLock();
		if (pitch == null || pitch != 0F) watcher.setPitchLock(0F);
		Float locked = watcher.getYawLock();
		if (locked != null && Math.abs(wrapDegrees(yaw - locked)) <= HEAD_STEP) return;
		watcher.setYawLock(yaw);
		// The lock reaches the head only with the player's next head turn (Lib's Disguises sends a body turn for it, and a
		// shulker draws its head by its head's yaw): a look only up or down turns the head on a wall but not the player's,
		// so the head's own packet goes out here.
		if (disguise.isDisguiseInUse()) send(player, disguise, new WrapperPlayServerEntityHeadLook(player.getEntityId(), yaw));
	}

	/**
	 * A held shulker's head on a floor: it turns with the player's look (the shell's frame is the world's), from now rather
	 * than their next turn, and never tilts (ShulkerLookControl.getXRotD is always 0). Without the pitch lock the player's
	 * pitch reached the head in every turn sent for them, and it nodded in the open shell (ShulkerModel takes the head's
	 * xRot as it is). The pitch lock goes first, so the turn Lib's Disguises sends as the yaw lock goes is level already.
	 */
	private void floorHead(Player player, TargetedDisguise disguise, ShulkerWatcher watcher) {
		Float pitch = watcher.getPitchLock();
		if (pitch == null || pitch != 0F) watcher.setPitchLock(0F);
		if (watcher.getYawLock() == null) return;
		watcher.setYawLock(null);
		if (disguise.isDisguiseInUse()) send(player, disguise, new WrapperPlayServerEntityHeadLook(player.getEntityId(), player.getLocation().getYaw()));
	}

	/** The head free again (no hold): it follows the player's look, from now rather than their next turn. */
	private void freeHead(Player player, TargetedDisguise disguise, ShulkerWatcher watcher) {
		boolean was = watcher.getYawLock() != null || watcher.getPitchLock() != null;
		if (watcher.getYawLock() != null) watcher.setYawLock(null);
		if (watcher.getPitchLock() != null) watcher.setPitchLock(null);
		if (was && disguise.isDisguiseInUse()) send(player, disguise, new WrapperPlayServerEntityHeadLook(player.getEntityId(), player.getLocation().getYaw()));
	}

	/**
	 * Every tick, for each held shulker: its head turned with the player's look, and its shell opened again after it moved
	 * to another block, which the viewers' games shut (Shulker.setPos clears the peek when the block changes). The peek
	 * goes out a tick and two after the move is seen here: sent before the move reached them, it would be shut again.
	 */
	private void tickShells() {
		for (Iterator<Map.Entry<UUID, ShellView>> it = shells.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, ShellView> e = it.next();
			Player player = Bukkit.getPlayer(e.getKey());
			TargetedDisguise disguise = active.get(e.getKey());
			Shell shell = Shell.of(overlays.getOrDefault(e.getKey(), ""));
			if (player == null || disguise == null || shell == null || !(disguise.getWatcher() instanceof ShulkerWatcher watcher)) {
				it.remove();
				continue;
			}
			try {
				if (e.getValue().moved(player.getLocation()) && watcher.getShieldHeight() > 0) {
					for (int ticks = 1; ticks <= 2; ticks++) {
						later(player, disguise, ticks, w -> {
							if (w instanceof ShulkerWatcher s) s.setShieldHeight(s.getShieldHeight());
						});
					}
				}
				if (!shell.floor()) holdHead(player, disguise, watcher, shell, e.getValue());
			} catch (RuntimeException | LinkageError ex) {
				logger.log(Level.FINE, "Could not update a held shulker", ex);
			}
		}
	}

	private static float wrapDegrees(float degrees) {
		float d = degrees % 360F;
		if (d >= 180F) d -= 360F;
		if (d < -180F) d += 360F;
		return d;
	}

	/**
	 * A vanilla conversion under way (Conversions): the body shakes, the way each creature shows it. Off again, a piglin
	 * or hoglin goes back to never converting (see neverConvert).
	 */
	private static void shake(Object watcher, boolean shaking) {
		if (watcher instanceof me.libraryaddict.disguise.disguisetypes.watchers.ZombieVillagerWatcher villager) villager.setShaking(shaking);
		else if (watcher instanceof me.libraryaddict.disguise.disguisetypes.watchers.ZombieWatcher zombie) zombie.setConverting(shaking);
		else if (watcher instanceof me.libraryaddict.disguise.disguisetypes.watchers.SkeletonWatcher skeleton) skeleton.setConvertingStray(shaking);
		else if (watcher instanceof PiglinAbstractWatcher piglin) piglin.setShaking(shaking);
		else if (watcher instanceof HoglinWatcher hoglin) hoglin.setShaking(shaking);
	}

	private static boolean flag(Map<String, Object> overlay, String key, boolean fallback) {
		Object value = overlay.get(key);
		if (value == null) return fallback;
		return value instanceof Number n ? n.intValue() != 0 : Boolean.TRUE.equals(value);
	}

	/**
	 * The enderman's block. Lib's Disguises' Material overload goes through legacy MaterialData, which loses modern
	 * blocks, so the overlay's {id, properties} is turned back into real block data.
	 */
	private static BlockData carriedBlock(Map<String, Object> overlay) {
		if (overlay.get("carriedBlockState") instanceof Map<?, ?> state && state.get("id") instanceof String id) {
			StringBuilder data = new StringBuilder(id);
			if (state.get("properties") instanceof Map<?, ?> properties && !properties.isEmpty()) {
				StringBuilder props = new StringBuilder();
				properties.forEach((k, v) -> props.append(props.isEmpty() ? "" : ",").append(k).append('=').append(v));
				data.append('[').append(props).append(']');
			}
			try {
				return Bukkit.createBlockData(data.toString());
			} catch (IllegalArgumentException e) {
				Material material = Material.matchMaterial(id);
				if (material != null && material.isBlock()) return material.createBlockData();
			}
		}
		return Material.AIR.createBlockData();
	}

	// ------------------------------------------------------------------ animations for unmodded viewers

	/** Entity events vanilla clients animate by themselves (EntityEvent ids). */
	private static final byte ATTACK = 4;
	/** Rabbit.handleEntityEvent(1): one hop of its legs. */
	private static final byte RABBIT_HOP = 1;
	private static final byte SHEEP_EAT = 10;
	private static final byte GOAT_LOWER_HEAD = 58;
	private static final byte GOAT_RAISE_HEAD = 59;
	private static final byte WARDEN_SONIC_BOOM = 62;
	/** Mobs whose melee hit is an entity-event animation rather than an arm swing (as MorphAnimations on the client). */
	private static final Set<EntityType> ATTACK_ON_SWING = Set.of(EntityType.IRON_GOLEM, EntityType.RAVAGER, EntityType.HOGLIN, EntityType.ZOGLIN,
			EntityType.WARDEN, EntityType.CREAKING);

	/** A watcher change undone a few ticks later (casting pose, roll, rear-up...), if the same disguise is still on. */
	private record Timed(UUID player, TargetedDisguise disguise, Consumer<FlagWatcher> action) {}
	private final Map<Integer, List<Timed>> timed = new HashMap<>();
	private int tick;

	private void later(Player player, TargetedDisguise disguise, int ticks, Consumer<FlagWatcher> action) {
		timed.computeIfAbsent(tick + ticks, k -> new ArrayList<>()).add(new Timed(player.getUniqueId(), disguise, action));
	}

	/** Once per server tick. */
	public void tick() {
		tick++;
		List<Timed> due = timed.remove(tick);
		if (due != null) {
			for (Timed t : due) {
				Player player = Bukkit.getPlayer(t.player());
				if (player == null || active.get(t.player()) != t.disguise()) continue;
				try {
					t.action().accept(t.disguise().getWatcher());
				} catch (RuntimeException | LinkageError e) {
					logger.log(Level.FINE, "Could not finish a disguise animation", e);
				}
			}
		}
		if (!shells.isEmpty()) tickShells();
	}

	/**
	 * {@code player} used {@code ability}: play the mob's own animation for unmodded viewers. Modded viewers get the
	 * same through ClientChannel.broadcastAnimation and MorphAnimations.
	 */
	public void animate(Player player, String ability) {
		TargetedDisguise disguise = active.get(player.getUniqueId());
		if (disguise == null) return;
		FlagWatcher watcher = disguise.getWatcher();
		EntityType type = disguise.getType().getEntityType();
		try {
			switch (ability) {
				case "toss", "scare" -> entityEvent(player, disguise, ATTACK);
				case "ram" -> {
					if (type == EntityType.GOAT) {
						entityEvent(player, disguise, GOAT_LOWER_HEAD);
						later(player, disguise, 20, w -> entityEvent(player, disguise, GOAT_RAISE_HEAD));
					} else {
						entityEvent(player, disguise, ATTACK);
					}
				}
				case "sonic-boom" -> entityEvent(player, disguise, WARDEN_SONIC_BOOM);
				case "hop" -> {
					if (type == EntityType.RABBIT) entityEvent(player, disguise, RABBIT_HOP);
				}
				case "graze" -> entityEvent(player, disguise, SHEEP_EAT);
				case "large-fireball" -> {
					if (watcher instanceof GhastWatcher ghast) {
						ghast.setAggressive(true);
						later(player, disguise, 10, w -> ((GhastWatcher) w).setAggressive(false));
					}
				}
				case "evoker-fangs", "vanish", "illusion-blind" -> {
					if (watcher instanceof IllagerWizardWatcher caster) {
						caster.setSpell(switch (ability) {
							case "vanish" -> Spellcaster.Spell.DISAPPEAR;
							case "illusion-blind" -> Spellcaster.Spell.BLINDNESS;
							default -> Spellcaster.Spell.FANGS;
						});
						later(player, disguise, 20, w -> ((IllagerWizardWatcher) w).setSpell(Spellcaster.Spell.NONE));
					}
					// An invisible illusioner is drawn as four mirror images, which only spread out when it is hurt
					// (Illusioner.aiStep). A hurt animation scatters them, as vanilla's own mirror spell looks.
					if (ability.equals("vanish") && type == EntityType.ILLUSIONER) {
						later(player, disguise, 2, w -> send(player, disguise, new WrapperPlayServerHurtAnimation(player.getEntityId(), player.getYaw())));
					}
				}
				case "roll" -> {
					if (watcher instanceof PandaWatcher panda) {
						panda.setTumble(true);
						later(player, disguise, 20, w -> ((PandaWatcher) w).setTumble(false));
					}
				}
				case "rear-up" -> {
					if (watcher instanceof PolarBearWatcher bear) {
						bear.setStanding(true);
						later(player, disguise, 20, w -> ((PolarBearWatcher) w).setStanding(false));
					}
				}
				case "sort" -> {
					// Copper golem: reach into the chest, then put the stack down (each a 3-second vanilla animation).
					if (watcher instanceof CopperGolemWatcher golem) {
						golem.setState(CopperGolemState.GETTING_ITEM);
						later(player, disguise, 30, w -> ((CopperGolemWatcher) w).setState(CopperGolemState.DROPPING_ITEM));
						later(player, disguise, 60, w -> ((CopperGolemWatcher) w).setState(CopperGolemState.IDLE));
					}
				}
				default -> {
					// projectiles and potions animate through the player's own arm swing
				}
			}
		} catch (RuntimeException | LinkageError e) {
			logger.log(Level.FINE, "Could not animate " + ability + " for unmodded viewers", e);
		}
	}

	/** The player swung: golems, ravagers, hoglins, wardens and creakings play their attack animation instead. */
	public void swing(Player player) {
		TargetedDisguise disguise = active.get(player.getUniqueId());
		if (disguise != null && ATTACK_ON_SWING.contains(disguise.getType().getEntityType())) entityEvent(player, disguise, ATTACK);
	}

	/** Last aim pose sent per player: bit 1 aiming a ranged weapon, bit 2 loading a crossbow. */
	private final Map<UUID, Integer> aimPose = new HashMap<>();

	/**
	 * Ranged-weapon poses, which vanilla only draws on mobs in combat (MorphSync.syncAim on the client): skeletons and
	 * illusioners raise a held bow, pillagers and piglins load a crossbow, drowned wind up a trident.
	 */
	public void syncPose(Player player) {
		TargetedDisguise disguise = active.get(player.getUniqueId());
		if (disguise == null || !(disguise.getWatcher() instanceof InsentientWatcher mob)) return;
		ItemStack using = player.isHandRaised() ? player.getActiveItem() : ItemStack.empty();
		Material held = player.getInventory().getItemInMainHand().getType();
		boolean aiming = held == Material.BOW || using.getType() == Material.BOW || using.getType() == Material.CROSSBOW || using.getType() == Material.TRIDENT;
		boolean charging = using.getType() == Material.CROSSBOW;
		int pose = (aiming ? 1 : 0) | (charging ? 2 : 0);
		Integer last = aimPose.put(player.getUniqueId(), pose);
		if (last != null && last == pose) return;
		try {
			mob.setEnraged(aiming);
			if (mob instanceof PillagerWatcher pillager) pillager.setAimingBow(charging);
			if (mob instanceof PiglinWatcher piglin) piglin.setUsingCrossbow(charging);
		} catch (RuntimeException | LinkageError e) {
			logger.log(Level.FINE, "Could not mirror the aim pose", e);
		}
	}

	/** Where each bee was last tick. */
	private final Map<UUID, Location> lastBeePosition = new HashMap<>();

	/**
	 * Bees fold their wings on the ground unless they are moving (BeeRenderer), and vanilla clients only know a
	 * player's speed when it is knocked back. While a bee morph walks, tell them how fast it goes; standing still, the
	 * speed decays on their side and the wings settle, as a real bee's do.
	 */
	public void syncMotion(Player player) {
		TargetedDisguise disguise = active.get(player.getUniqueId());
		if (disguise == null || disguise.getType().getEntityType() != EntityType.BEE) {
			lastBeePosition.remove(player.getUniqueId());
			return;
		}
		Location now = player.getLocation();
		Location last = lastBeePosition.put(player.getUniqueId(), now);
		if (last == null || last.getWorld() != now.getWorld()) return;
		double dx = now.getX() - last.getX(), dy = now.getY() - last.getY(), dz = now.getZ() - last.getZ();
		if (dx * dx + dy * dy + dz * dz < 1e-4) return;
		send(player, disguise, new WrapperPlayServerEntityVelocity(player.getEntityId(), new Vector3d(dx, dy, dz)));
	}

	private void entityEvent(Player player, TargetedDisguise disguise, byte event) {
		send(player, disguise, new WrapperPlayServerEntityStatus(player.getEntityId(), event));
	}

	/** Send to everyone who sees this disguise: players tracking the morph, minus modded clients (the observer list). */
	private void send(Player player, TargetedDisguise disguise, PacketWrapper<?> packet) {
		for (Player viewer : player.getTrackedBy()) {
			if (disguise.getObservers().contains(viewer.getName())) continue;
			try {
				PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, packet);
			} catch (RuntimeException | LinkageError e) {
				logger.log(Level.FINE, "Could not send a disguise animation", e);
				return;
			}
		}
	}

	/**
	 * Hide the disguise's body, or show it again (what it holds and wears still shows, as on any invisible creature):
	 * while the morph skin melts over it for everyone who can see it.
	 */
	public void setInvisible(Player player, boolean invisible) {
		TargetedDisguise disguise = active.get(player.getUniqueId());
		if (disguise != null && disguise.getWatcher().isInvisible() != invisible) disguise.getWatcher().setInvisible(invisible);
	}

	/** The disguise at {@code scale} of its own size (1.0: as built; see {@link #show}). */
	public void setScale(Player player, double scale) {
		TargetedDisguise disguise = active.get(player.getUniqueId());
		if (disguise != null) setScale(disguise, scale * bases.getOrDefault(player.getUniqueId(), 1.0));
	}

	public boolean isDisguised(Player player) {
		return active.containsKey(player.getUniqueId());
	}

	/** For /morph info: whether there is a disguise, and which Lib's Disguises watcher drives it. */
	public String describe(Player player) {
		TargetedDisguise disguise = active.get(player.getUniqueId());
		return disguise == null ? "false" : "true (" + disguise.getType() + ", " + disguise.getWatcher().getClass().getSimpleName() + ")";
	}

	public void remove(Player player) {
		TargetedDisguise disguise = active.remove(player.getUniqueId());
		shownEquipment.remove(player.getUniqueId());
		templates.remove(player.getUniqueId());
		aimPose.remove(player.getUniqueId());
		lastBeePosition.remove(player.getUniqueId());
		overlays.remove(player.getUniqueId());
		shells.remove(player.getUniqueId());
		bases.remove(player.getUniqueId());
		if (disguise != null) {
			try {
				disguise.removeDisguise();
			} catch (RuntimeException e) {
				logger.log(Level.WARNING, "Failed to remove disguise of " + player.getName(), e);
			}
		}
	}

	public void removeAll() {
		for (TargetedDisguise disguise : active.values()) {
			try {
				disguise.removeDisguise();
			} catch (RuntimeException ignored) {
				// shutting down
			}
		}
		active.clear();
		overlays.clear();
		shells.clear();
		bases.clear();
	}

	/** A Fabric client finished its handshake: stop sending it disguises; it renders morphs itself. */
	public void exclude(Player viewer) {
		for (TargetedDisguise disguise : active.values()) {
			if (!disguise.getObservers().contains(viewer.getName())) disguise.addPlayer(viewer);
		}
	}

	public void include(Player viewer) {
		for (TargetedDisguise disguise : active.values()) {
			if (disguise.getObservers().contains(viewer.getName())) disguise.removePlayer(viewer);
		}
	}

	public void forget(UUID player) {
		active.remove(player);
		templates.remove(player);
		aimPose.remove(player);
		overlays.remove(player);
		shells.remove(player);
		bases.remove(player);
	}

	private @Nullable TargetedDisguise build(Player player, MorphRef ref, @Nullable LivingEntity template) {
		try {
			if (ref.isPlayer()) {
				if (!playerDisguisesWork) return null;
				String copied = ref.skin() != null && !ref.skin().name().isEmpty() ? ref.skin().name() : player.getName();
				return new PlayerDisguise(copied, copied);
			}
			if (template == null) return null;
			Disguise disguise = DisguiseAPI.constructDisguise(template, false, false);
			if (disguise instanceof TargetedDisguise targeted) return targeted;
			logger.warning("Lib's Disguises returned a non-targeted disguise for " + ref.entityType());
		} catch (RuntimeException | LinkageError e) {
			if (ref.isPlayer()) playerDisguisesWork = false;
			logger.log(Level.WARNING, "Could not create a disguise for " + ref.entityType(), e);
		}
		return null;
	}

	private void configure(Player player, TargetedDisguise disguise, Collection<Player> excluded, boolean selfVisible) {
		disguise.setDisguiseTarget(TargetedDisguise.TargetType.SHOW_TO_EVERYONE_BUT_THESE_PLAYERS);
		for (Player viewer : excluded) disguise.silentlyAddPlayer(viewer.getName());
		disguise.setEntity(player);
		disguise.setViewSelfDisguise(selfVisible);
		disguise.setNotifyBar(DisguiseConfig.NotifyBar.NONE);
		disguise.setKeepDisguiseOnPlayerDeath(false);
		disguise.setScalePlayerToDisguise(false);
	}

	private boolean start(Player player, TargetedDisguise disguise) {
		shownEquipment.remove(player.getUniqueId());
		aimPose.remove(player.getUniqueId());
		TargetedDisguise old = active.put(player.getUniqueId(), disguise);
		if (old != null && old != disguise) {
			try {
				old.removeDisguise();
			} catch (RuntimeException ignored) {
				// replaced anyway
			}
		}
		boolean started = disguise.startDisguise();
		if (!started) active.remove(player.getUniqueId());
		else syncEquipment(player);
		return started;
	}

	/**
	 * A shulker on a wall or ceiling stays at full size, which leaves the shrink and growth of a transformation to the melt's
	 * skin or particles: the viewers' games scale a creature before they turn it onto its face (LivingEntityRenderer, then
	 * ShulkerRenderer.setupRotations turning about half its height), so a smaller shell turns about a point below the
	 * block's middle and hangs off its wall or sits on the floor under its ceiling. Shulker.setPos snaps it to the block,
	 * so no shift of the disguise can make up for it. On a floor it grows from its feet, as any creature does.
	 */
	private static void setScale(TargetedDisguise disguise, double scale) {
		if (!(disguise.getWatcher() instanceof LivingWatcher living)) return;
		if (living instanceof ShulkerWatcher shulker && shulker.getFacingDirection() != BlockFace.DOWN) {
			// a transformation sets the scale every tick: only a change goes out
			if (living.getScale() != null && living.getScale() == 1.0) return;
			scale = 1.0;
		}
		living.setScale(scale);
	}
}
