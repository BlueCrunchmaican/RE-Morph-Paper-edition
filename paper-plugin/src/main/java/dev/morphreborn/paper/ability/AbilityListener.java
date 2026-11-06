package dev.morphreborn.paper.ability;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.data.PlayerMorphData;
import dev.morphreborn.paper.morph.MorphManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/** Event side of {@link AbilityManager}. */
public final class AbilityListener implements Listener {
	private final MorphRebornPlugin plugin;

	public AbilityListener(MorphRebornPlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
	public void onDamage(EntityDamageEvent event) {
		if (!(event.getEntity() instanceof Player player)) return;
		AbilityManager a = plugin.abilities();
		if (isMagic(event)) {
			// Undead: Harming heals them (6 damage -> 4 health, like vanilla's 6<<amp / 4<<amp).
			if (potionMagic(event) && a.isUndead(player) && !a.isInverting(player)) {
				event.setCancelled(true);
				double max = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null ? 20
						: player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
				player.setHealth(Math.min(max, player.getHealth() + event.getDamage() * 2 / 3));
				return;
			}
			double resist = a.magicResistance(player);
			if (resist > 0) event.setDamage(event.getDamage() * (1 - resist));
		}
		if (event instanceof org.bukkit.event.entity.EntityDamageByBlockEvent byBlock && byBlock.getDamager() != null
				&& byBlock.getDamager().getType() == Material.SWEET_BERRY_BUSH && a.ignoresBerryBush(player)) {
			event.setCancelled(true);
			return;
		}
		if (a.ignoresDamage(player, event.getCause())) {
			event.setCancelled(true);
			if (a.extinguish(player)) player.setFireTicks(0);
		}
	}

	private static boolean isMagic(EntityDamageEvent event) {
		org.bukkit.damage.DamageType type = event.getDamageSource().getDamageType();
		return type == org.bukkit.damage.DamageType.MAGIC || type == org.bukkit.damage.DamageType.INDIRECT_MAGIC;
	}

	/** Magic damage that comes from a Harming effect: drunk, splashed, lingering or on a tipped arrow. */
	private static boolean potionMagic(EntityDamageEvent event) {
		org.bukkit.entity.Entity direct = event.getDamageSource().getDirectEntity();
		return direct == null || direct instanceof org.bukkit.entity.ThrownPotion || direct instanceof org.bukkit.entity.AreaEffectCloud
				|| direct instanceof org.bukkit.entity.AbstractArrow;
	}

	/** Undead: Healing hurts them (4 health -> 6 damage). */
	@EventHandler(ignoreCancelled = true)
	public void onRegain(org.bukkit.event.entity.EntityRegainHealthEvent event) {
		if (!(event.getEntity() instanceof Player player) || event.getRegainReason() != org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.MAGIC) return;
		AbilityManager a = plugin.abilities();
		if (!a.isUndead(player) || !a.beginInverting(player)) return;
		try {
			event.setCancelled(true);
			player.damage(event.getAmount() * 1.5, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.MAGIC).build());
		} finally {
			a.endInverting(player);
		}
	}

	/** Spiders through cobwebs, foxes through berry bushes, rabbits and friends over powder snow (server side). */
	@EventHandler(ignoreCancelled = true)
	public void onInsideBlock(io.papermc.paper.event.entity.EntityInsideBlockEvent event) {
		if (event.getEntity() instanceof Player player && plugin.abilities().passesThrough(player, event.getBlock().getType())) event.setCancelled(true);
	}

	/** How long sneak has been held charges a horse's jump. */
	@EventHandler
	public void onSneak(org.bukkit.event.player.PlayerToggleSneakEvent event) {
		plugin.abilities().onSneak(event.getPlayer(), event.isSneaking());
	}

	/** A creeper blast or a spent bee stinger gets the death message the real mob's end would. */
	@EventHandler(priority = EventPriority.LOW)
	public void onDeathMessage(org.bukkit.event.entity.PlayerDeathEvent event) {
		String key = plugin.abilities().takeDeathMessage(event.getPlayer());
		if (key != null) event.deathMessage(net.kyori.adventure.text.Component.translatable(key, event.getPlayer().displayName()));
	}

	/**
	 * A disguised player can't be hurt by a mob that wouldn't fight what they look like (it may still be mid-swing or
	 * have fired before it lost interest). Hitting a mob, in melee or with a projectile, provokes it.
	 */
	@EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
	public void onMobHit(EntityDamageByEntityEvent event) {
		LivingEntity attacker = event.getDamager() instanceof LivingEntity living ? living
				: event.getDamager() instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof LivingEntity shooter ? shooter : null;
		if (attacker == null) return;
		if (event.getEntity() instanceof Player victim && !(attacker instanceof Player) && plugin.abilities().shouldIgnore(attacker, victim)) {
			event.setCancelled(true);
			return;
		}
		if (attacker instanceof Player player && event.getEntity() instanceof LivingEntity mob && !(mob instanceof Player)) {
			plugin.abilities().provoke(player, mob);
		}
	}

	@EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
	public void onMelee(EntityDamageByEntityEvent event) {
		if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
		if (event.getDamager() instanceof Player attacker && event.getEntity() instanceof LivingEntity victim) {
			plugin.abilities().onMeleeHit(attacker, victim);
		}
		if (event.getEntity() instanceof Player victim && event.getDamager() instanceof LivingEntity attacker) {
			plugin.abilities().onMeleeHurt(victim, attacker);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onTarget(EntityTargetLivingEntityEvent event) {
		if (event.getTarget() instanceof Player player && event.getEntity() instanceof LivingEntity mob && plugin.abilities().shouldIgnore(mob, player)) {
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onPotion(EntityPotionEffectEvent event) {
		if (event.getEntity() instanceof Player player && event.getNewEffect() != null
				&& (event.getAction() == EntityPotionEffectEvent.Action.ADDED || event.getAction() == EntityPotionEffectEvent.Action.CHANGED)
				&& plugin.abilities().blocksEffect(player, event.getNewEffect().getType())) {
			event.setCancelled(true);
		}
	}

	/**
	 * Enderman morphs pick up a block with an empty-handed right-click and put it down with the next one. Both go
	 * through BlockBreakEvent / BlockPlaceEvent so protection plugins can veto them like normal building.
	 */
	@EventHandler(priority = EventPriority.HIGH)
	public void onInteractBlock(PlayerInteractEvent event) {
		if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) return;
		Player player = event.getPlayer();
		if (!plugin.abilities().canCarryBlocks(player) || !player.getInventory().getItemInMainHand().isEmpty()) return;
		PlayerMorphData data = plugin.store().get(player.getUniqueId());
		Block clicked = event.getClickedBlock();
		if (data.carriedBlock == null) {
			if (!canPickUp(clicked)) return;
			event.setCancelled(true);
			BlockBreakEvent check = new BlockBreakEvent(clicked, player);
			check.setDropItems(false);
			check.setExpToDrop(0);
			if (!check.callEvent()) return;
			data.carriedBlock = clicked.getBlockData().getAsString();
			clicked.getWorld().playSound(clicked.getLocation(), clicked.getBlockSoundGroup().getBreakSound(), 1F, 1F);
			clicked.setType(Material.AIR);
		} else {
			Block target = clicked.isReplaceable() ? clicked : clicked.getRelative(event.getBlockFace());
			if (!target.isReplaceable()) return;
			event.setCancelled(true);
			BlockData carried;
			try {
				carried = Bukkit.createBlockData(data.carriedBlock);
			} catch (IllegalArgumentException e) {
				data.carriedBlock = null;
				return;
			}
			BlockState replaced = target.getState();
			target.setBlockData(carried, true);
			BlockPlaceEvent check = new BlockPlaceEvent(target, replaced, clicked, new ItemStack(carried.getPlacementMaterial()), player, true, EquipmentSlot.HAND);
			if (!check.callEvent()) {
				replaced.update(true, false);
				return;
			}
			target.getWorld().playSound(target.getLocation(), target.getBlockSoundGroup().getPlaceSound(), 1F, 1F);
			data.carriedBlock = null;
		}
		plugin.store().markDirty(player.getUniqueId());
		plugin.morphs().setOverlay(player, data.carriedBlock == null ? "" : MorphManager.carriedBlockOverlay(data.carriedBlock));
	}

	/**
	 * Vanilla: only #minecraft:enderman_holdable. With world-effects.enderman-pickup: any, every breakable block except
	 * ones that would lose data or a second half (chests, signs, doors, beds, tall plants), since only the block state
	 * is carried.
	 */
	private boolean canPickUp(Block block) {
		Material type = block.getType();
		if (type.isAir() || type.getHardness() < 0 || block.isLiquid()) return false;
		if (!plugin.settings().endermanPickupAny) return Tag.ENDERMAN_HOLDABLE.isTagged(type);
		BlockData data = block.getBlockData();
		boolean twoPart = data instanceof org.bukkit.block.data.type.Bed || data instanceof org.bukkit.block.data.type.PistonHead
				|| (data instanceof org.bukkit.block.data.Bisected && !(data instanceof org.bukkit.block.data.type.Stairs)
						&& !(data instanceof org.bukkit.block.data.type.TrapDoor));
		return !twoPart && !(block.getState(false) instanceof org.bukkit.block.TileState);
	}

	/** Milk a cow/goat morph with a bucket, or get stew from a mooshroom morph with a bowl. */
	@EventHandler(ignoreCancelled = true)
	public void onInteractEntity(PlayerInteractEntityEvent event) {
		if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Player target)) return;
		ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
		if (held.getType() == Material.SHEARS && plugin.abilities().shearSheep(event.getPlayer(), target)) {
			event.setCancelled(true);
			held.damage(1, event.getPlayer());
			return;
		}
		if (!plugin.abilities().isMilkable(target)) return;
		Player player = event.getPlayer();
		ItemStack hand = player.getInventory().getItemInMainHand();
		String type = plugin.morphs().activeForm(target) == null ? "" : plugin.morphs().activeForm(target).entityType();
		Material result;
		Sound sound;
		if (hand.getType() == Material.BUCKET) {
			result = Material.MILK_BUCKET;
			sound = type.equals("minecraft:goat") ? Sound.ENTITY_GOAT_MILK : Sound.ENTITY_COW_MILK;
		} else if (hand.getType() == Material.BOWL && type.equals("minecraft:mooshroom")) {
			result = Material.MUSHROOM_STEW;
			sound = Sound.ENTITY_MOOSHROOM_MILK;
		} else {
			return;
		}
		event.setCancelled(true);
		hand.subtract(1);
		for (ItemStack overflow : player.getInventory().addItem(new ItemStack(result)).values()) {
			player.getWorld().dropItemNaturally(player.getLocation(), overflow);
		}
		target.getWorld().playSound(target.getLocation(), sound, 1F, 1F);
	}

	@EventHandler(ignoreCancelled = true)
	public void onSwapHand(PlayerSwapHandItemsEvent event) {
		Player player = event.getPlayer();
		if (plugin.channel().isModded(player) || plugin.morphs().activeForm(player) == null) return;
		String trigger = plugin.abilities().vanillaTrigger();
		boolean fire = switch (trigger) {
			case "swap-hand" -> true;
			case "sneak-swap-hand" -> player.isSneaking();
			default -> false;
		};
		if (fire) {
			event.setCancelled(true);
			plugin.abilities().trigger(player, false);
		} else if (trigger.equals("sneak-swap-hand") && plugin.abilities().hasAlternate(player)) {
			// F without sneaking picks the witch's next potion
			event.setCancelled(true);
			plugin.abilities().trigger(player, true);
		}
	}

	/** Breeze morphs deflect projectiles; enderman morphs teleport away from them. The projectile flies on. */
	@EventHandler(ignoreCancelled = true)
	public void onProjectileHit(org.bukkit.event.entity.ProjectileHitEvent event) {
		if (event.getHitEntity() instanceof Player hit && event.getEntity() instanceof org.bukkit.entity.Snowball && plugin.abilities().hurtBySnowballs(hit)) {
			// snowballs hurt blazes (3 damage), as in vanilla
			if (event.getEntity().getShooter() instanceof org.bukkit.entity.Entity shooter) {
				hit.damage(3.0, shooter);
			} else {
				hit.damage(3.0);
			}
		}
		if (event.getHitEntity() instanceof Player player && event.getEntity().getShooter() != player
				&& plugin.abilities().dodgeProjectile(player, event.getEntity())) {
			event.setCancelled(true);
		}
	}

	/** Sneak-jump long jump for frog, goat and breeze morphs. */
	@EventHandler(ignoreCancelled = true)
	public void onJump(com.destroystokyo.paper.event.player.PlayerJumpEvent event) {
		plugin.abilities().leap(event.getPlayer());
	}

	/** The event fires before the new mode is applied, so the flight grant is redone a tick later. */
	@EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
	public void onGameModeChange(org.bukkit.event.player.PlayerGameModeChangeEvent event) {
		Player player = event.getPlayer();
		plugin.getServer().getScheduler().runTask(plugin, () -> {
			if (player.isOnline()) plugin.abilities().onGameModeChanged(player);
		});
	}

	/** Lightning charges a creeper morph, whether or not the strike's damage goes through. */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onLightning(EntityDamageEvent event) {
		if (event.getCause() == EntityDamageEvent.DamageCause.LIGHTNING && event.getEntity() instanceof Player player) plugin.abilities().onLightning(player);
	}

	/** A perched bat lets go when a movement key or jump is pressed. */
	@EventHandler
	public void onInput(org.bukkit.event.player.PlayerInputEvent event) {
		plugin.abilities().perchInput(event.getPlayer(), event.getInput());
	}

	/** A perched bat that gets hurt lets go, like a resting bat (Bat.hurtServer). */
	@EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
	public void onHurtWhilePerched(EntityDamageEvent event) {
		if (event.getEntity() instanceof Player player && event.getFinalDamage() > 0) plugin.abilities().unperch(player, true);
	}

	/**
	 * Fireballs and wither skulls shot through abilities: replace the vanilla explosion (which only breaks blocks when
	 * mobGriefing is on) with one that follows the world-effects settings, still credited to the shooter.
	 */
	@EventHandler(ignoreCancelled = true)
	public void onAbilityExplosion(org.bukkit.event.entity.ExplosionPrimeEvent event) {
		org.bukkit.entity.Entity projectile = event.getEntity();
		if (!plugin.abilities().isAbilityProjectile(projectile)) return;
		event.setCancelled(true);
		org.bukkit.World world = projectile.getWorld();
		org.bukkit.entity.Entity source = projectile instanceof org.bukkit.entity.Projectile p && p.getShooter() instanceof org.bukkit.entity.Entity shooter
				? shooter : projectile;
		boolean fire = projectile instanceof org.bukkit.entity.LargeFireball && plugin.settings().abilityFire.allows(world);
		world.createExplosion(source, projectile.getLocation(), event.getRadius(), fire, plugin.settings().explosionBlockDamage.allows(world));
		projectile.remove();
	}

	/** Blaze fireballs from abilities light blocks only when the fire setting allows it. */
	@EventHandler(ignoreCancelled = true)
	public void onAbilityIgnite(org.bukkit.event.block.BlockIgniteEvent event) {
		if (plugin.abilities().isAbilityProjectile(event.getIgnitingEntity()) && !plugin.settings().abilityFire.allows(event.getBlock().getWorld())) {
			event.setCancelled(true);
		}
	}
}
