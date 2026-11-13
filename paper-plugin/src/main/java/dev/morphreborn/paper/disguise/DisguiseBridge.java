package dev.morphreborn.paper.disguise;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.armadillo.ArmadilloState;
import com.github.retrooper.packetevents.protocol.entity.data.struct.CopperGolemState;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import dev.morphreborn.paper.PluginSettings;
import dev.morphreborn.paper.data.MorphRef;
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
import me.libraryaddict.disguise.disguisetypes.watchers.SlimeWatcher;
import dev.morphreborn.paper.nbt.Snbt;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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
	 * Disguise {@code player} as {@code ref} for everyone except {@code excluded}.
	 *
	 * @param template    unspawned entity holding the variant's data (null for player-type morphs)
	 * @param selfVisible let the player see their own disguise (vanilla clients only)
	 * @return false if the disguise could not be created
	 */
	public boolean show(Player player, MorphRef ref, @Nullable LivingEntity template, double scale, Collection<Player> excluded, boolean selfVisible,
			PluginSettings.NameTagMode nameTags) {
		TargetedDisguise disguise = build(player, ref, template);
		if (disguise == null) return false;
		configure(player, disguise, excluded, selfVisible);
		applyNameTag(player, disguise, nameTags);
		setScale(disguise, scale);
		neverConvert(disguise);
		if (template != null) {
			copySize(disguise, template);
			templates.put(player.getUniqueId(), template);
		} else {
			templates.remove(player.getUniqueId());
		}
		return start(player, disguise);
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

	/** Show the live appearance overlay (see MorphManager.setOverlay) on the vanilla disguise. */
	public void applyOverlay(Player player, String overlaySnbt) {
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
			if (watcher instanceof BeeWatcher bee) bee.setHasStung(flag(overlay, "HasStung", template instanceof Bee b && b.hasStung()));
			if (watcher instanceof ArmadilloWatcher armadillo) armadillo.setState("scared".equals(overlay.get("state")) ? ArmadilloState.SCARED : ArmadilloState.IDLE);
			if (watcher instanceof BatWatcher bat) bat.setHanging(flag(overlay, "BatFlags", false));
			shake(watcher, flag(overlay, "MorphShaking", false));
		} catch (RuntimeException | LinkageError e) {
			logger.log(Level.FINE, "Could not apply overlay " + overlaySnbt, e);
		}
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
		if (due == null) return;
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

	public void setScale(Player player, double scale) {
		TargetedDisguise disguise = active.get(player.getUniqueId());
		if (disguise != null) setScale(disguise, scale);
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

	private static void setScale(TargetedDisguise disguise, double scale) {
		if (disguise.getWatcher() instanceof LivingWatcher living) {
			living.setScale(scale);
		}
	}
}
