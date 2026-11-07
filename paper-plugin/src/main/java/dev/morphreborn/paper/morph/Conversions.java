package dev.morphreborn.paper.morph;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.data.MorphRef;
import dev.morphreborn.paper.nbt.Snbt;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jspecify.annotations.Nullable;

/**
 * Vanilla conversions for morphs: whatever turns a mob into another in vanilla turns a morph of it too, for as long as
 * the player keeps that body (MorphManager.convert; the collection, the selector and what is saved keep the form that
 * was picked). A villager killed by a zombie becomes a zombie villager (by difficulty), and a weakened zombie villager
 * fed a golden apple turns back after a while; a zombie or husk under water drowns (a husk into a zombie first); a
 * skeleton stuck in powder snow becomes a stray; piglins, piglin brutes and hoglins outside the Nether zombify;
 * lightning turns a pig into a zombified piglin and a villager into a witch, and flips a mooshroom's colour (a
 * creeper's charge is AbilityManager's); a sulfur cube can be fed a block, TNT making it explosive. Multimorphs don't
 * convert (one creature at a time). Each kind can be switched off under {@code conversions} in config.yml.
 */
public final class Conversions implements Listener {
	/** Checked every this many ticks; timers count in ticks. */
	private static final int STEP = 5;
	/** Vanilla: a zombie under water for 30 s starts drowning, which takes 15 s more. */
	private static final int DROWN_START = 600, DROWN_TICKS = 300;
	/** Vanilla: a skeleton in powder snow for 7 s starts freezing, 15 s more to a stray. */
	private static final int FREEZE_START = 140, FREEZE_TICKS = 300;
	/** Vanilla: piglins and hoglins outside the Nether zombify after 15 s, shaking all the while. */
	private static final int ZOMBIFY_TICKS = 300;

	private final MorphRebornPlugin plugin;
	private final Map<UUID, Watch> watches = new HashMap<>();
	/** The last lightning bolt that struck each player: a bolt hurts for several ticks, a mooshroom flips once. */
	private final Map<UUID, UUID> lastBolt = new HashMap<>();

	/** One player's countdowns, for one look; a new look starts them over. */
	private static final class Watch {
		final MorphRef look;
		int underWater;
		int inPowderSnow;
		int outsideNether;
		/** A started conversion (drowning, freezing, curing): ticks left, and what it ends as. */
		int converting = -1;
		@Nullable MorphRef into;
		@Nullable String sound;

		Watch(MorphRef look) {
			this.look = look;
		}
	}

	public Conversions(MorphRebornPlugin plugin) {
		this.plugin = plugin;
	}

	private boolean on(String kind) {
		return plugin.settings().conversionsEnabled && plugin.settings().conversions.contains(kind);
	}

	/** The look that may convert now: a plain morph (not a multimorph), not transforming. */
	private @Nullable MorphRef look(Player player) {
		if (plugin.morphs().isTransitioning(player) || plugin.borrowedHeads().borrowed(player) != null) return null;
		return plugin.morphs().activeForm(player);
	}

	// ------------------------------------------------------------------ over time: drowning, freezing, zombifying, curing

	private long ticks;

	/** Every server tick. */
	public void tick() {
		if (++ticks % STEP != 0) return;
		for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
			MorphRef look = look(player);
			if (look == null) {
				watches.remove(player.getUniqueId());
				continue;
			}
			Watch w = watches.get(player.getUniqueId());
			if (w == null || !w.look.equals(look)) {
				w = new Watch(look);
				watches.put(player.getUniqueId(), w);
			}
			step(player, w);
		}
	}

	private void step(Player player, Watch w) {
		if (w.converting >= 0) {
			w.converting -= STEP;
			if (w.converting < 0 && w.into != null) finish(player, w.into, w.sound);
			return;
		}
		String type = w.look.entityType();
		switch (type) {
			case "minecraft:zombie", "minecraft:husk", "minecraft:zombie_villager" -> {
				if (!on("drowning")) return;
				w.underWater = player.isUnderWater() ? w.underWater + STEP : 0;
				if (w.underWater >= DROWN_START) {
					boolean husk = type.equals("minecraft:husk");
					start(player, w, DROWN_TICKS, convertTo(w.look, husk ? "minecraft:zombie" : "minecraft:drowned"),
							husk ? "entity.husk.converted_to_zombie" : "entity.zombie.converted_to_drowned", "{DrownedConversionTime:" + DROWN_TICKS + ",MorphShaking:1b}");
				}
			}
			case "minecraft:skeleton" -> {
				if (!on("freezing")) return;
				w.inPowderSnow = inPowderSnow(player) ? w.inPowderSnow + STEP : 0;
				if (w.inPowderSnow >= FREEZE_START) {
					start(player, w, FREEZE_TICKS, convertTo(w.look, "minecraft:stray"), "entity.skeleton.converted_to_stray",
							"{StrayConversionTime:" + FREEZE_TICKS + ",MorphShaking:1b}");
				}
			}
			case "minecraft:piglin", "minecraft:piglin_brute", "minecraft:hoglin" -> {
				if (!on("zombification")) return;
				boolean outside = player.getWorld().getEnvironment() != World.Environment.NETHER;
				if (outside && w.outsideNether == 0) {
					plugin.morphs().setOverlay(player, "{MorphShaking:1b}"); // shakes all the while, as vanilla
					tell(player, "Away from the Nether, your body is zombifying. Go back within 15 seconds to stop it.");
				}
				if (!outside && w.outsideNether > 0) plugin.morphs().setOverlay(player, "");
				w.outsideNether = outside ? w.outsideNether + STEP : 0;
				if (w.outsideNether > ZOMBIFY_TICKS) {
					boolean hoglin = type.equals("minecraft:hoglin");
					finish(player, convertTo(w.look, hoglin ? "minecraft:zoglin" : "minecraft:zombified_piglin"),
							hoglin ? "entity.hoglin.converted_to_zombified"
									: type.equals("minecraft:piglin_brute") ? "entity.piglin_brute.converted_to_zombified" : "entity.piglin.converted_to_zombified");
				}
			}
			default -> {
			}
		}
	}

	/** Stuck in powder snow (the block at the feet or the head), as Entity.isInPowderSnow. */
	private static boolean inPowderSnow(Player player) {
		return player.getLocation().getBlock().getType() == Material.POWDER_SNOW || player.getEyeLocation().getBlock().getType() == Material.POWDER_SNOW;
	}

	/** A conversion that takes a while: the body shakes (overlay) until it turns. */
	private void start(Player player, Watch w, int ticks, MorphRef into, String sound, String shaking) {
		String becoming = switch (into.entityType()) {
			case "minecraft:drowned" -> "You're drowning: in " + ticks / 20 + " seconds you'll be a drowned.";
			case "minecraft:zombie" -> "You're drowning: in " + ticks / 20 + " seconds you'll be a zombie.";
			case "minecraft:stray" -> "You're freezing: in " + ticks / 20 + " seconds you'll be a stray.";
			case "minecraft:villager" -> "You're being cured: in " + ticks / 20 + " seconds you'll be a villager again.";
			default -> null;
		};
		if (becoming != null) tell(player, becoming);
		w.converting = ticks;
		w.into = into;
		w.sound = sound;
		plugin.morphs().setOverlay(player, shaking);
	}

	private void finish(Player player, MorphRef into, @Nullable String sound) {
		watches.remove(player.getUniqueId());
		if (plugin.morphs().convert(player, into) && sound != null) play(player, sound);
	}

	/** Vanilla shows a conversion only by shaking, which looks like a glitch: the player is told what is happening. */
	private static void tell(Player player, String message) {
		player.sendActionBar(net.kyori.adventure.text.Component.text(message, net.kyori.adventure.text.format.NamedTextColor.DARK_GREEN));
	}

	private static void play(Player player, String sound) {
		player.getWorld().playSound(player.getLocation(), "minecraft:" + sound, SoundCategory.PLAYERS, 1F, 1F);
	}

	// ------------------------------------------------------------------ a villager killed by a zombie

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onHurt(EntityDamageByEntityEvent event) {
		if (!(event.getEntity() instanceof Player player)) return;
		if (event.getDamager() instanceof LightningStrike bolt) {
			struck(player, bolt);
			return;
		}
		if (!on("zombie-villager")) return;
		MorphRef look = look(player);
		if (look == null || !look.entityType().equals("minecraft:villager")) return;
		Entity killer = event.getDamager() instanceof Projectile p && p.getShooter() instanceof Entity shooter ? shooter : event.getDamager();
		if (!(killer instanceof Zombie)) return;
		if (event.getFinalDamage() < player.getHealth() + player.getAbsorptionAmount()) return; // only a killing blow
		if (holdsTotem(player)) return; // the totem saves it, as it would a villager
		Difficulty difficulty = player.getWorld().getDifficulty();
		double chance = difficulty == Difficulty.HARD ? 1.0 : difficulty == Difficulty.NORMAL ? 0.5 : 0.0;
		if (Math.random() >= chance) return;
		// Vanilla removes the villager and puts a zombie villager in its place, at full health
		event.setCancelled(true);
		var maxHealth = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
		player.setHealth(maxHealth == null ? 20.0 : maxHealth.getValue());
		player.playHurtAnimation(0F);
		if (plugin.morphs().convert(player, convertTo(look, "minecraft:zombie_villager"))) play(player, "entity.zombie.infect");
	}

	private static boolean holdsTotem(Player player) {
		return player.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
				|| player.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
	}

	// ------------------------------------------------------------------ curing a zombie villager

	/** Another player feeds a golden apple to a weakened zombie villager morph. */
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onFeed(PlayerInteractEntityEvent event) {
		feed(event);
	}

	/** The same click as an "interact at" (the client sends both for some entities). */
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onFeedAt(org.bukkit.event.player.PlayerInteractAtEntityEvent event) {
		feed(event);
	}

	private void feed(PlayerInteractEntityEvent event) {
		if (!(event.getRightClicked() instanceof Player target)) return;
		Player feeder = event.getPlayer();
		ItemStack item = event.getHand() == EquipmentSlot.OFF_HAND ? feeder.getInventory().getItemInOffHand() : feeder.getInventory().getItemInMainHand();
		if (plugin.settings().debug) {
			plugin.getLogger().info("[debug] " + feeder.getName() + " used " + item.getType() + " (" + event.getHand() + ", " + event.getClass().getSimpleName()
					+ ") on " + target.getName() + " as " + plugin.morphs().activeForm(target));
		}
		if (item.getType() == Material.FLINT_AND_STEEL || item.getType() == Material.FIRE_CHARGE) {
			// lighting a sulfur cube that holds TNT, as vanilla's (SulfurCube.mobInteract)
			if (on("sulfur-cube") && plugin.abilities().ignite(target, false)) {
				event.setCancelled(true);
				if (feeder.getGameMode() != GameMode.CREATIVE) {
					if (item.getType() == Material.FLINT_AND_STEEL) item.damage(1, feeder);
					else item.subtract();
				}
			}
		} else if (item.getType() == Material.GOLDEN_APPLE) {
			if (cure(target)) {
				event.setCancelled(true);
				if (feeder.getGameMode() != GameMode.CREATIVE) item.subtract();
			}
		} else if (item.getType().isBlock() && item.getType().isItem() && !item.getType().isAir()) {
			if (feedBlock(target, item.getType())) {
				event.setCancelled(true);
				if (feeder.getGameMode() != GameMode.CREATIVE) item.subtract();
			}
		}
	}

	/** A weakened zombie villager morph eats a golden apple itself. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onEat(PlayerItemConsumeEvent event) {
		if (event.getItem().getType() == Material.GOLDEN_APPLE) cure(event.getPlayer());
	}

	/**
	 * Vanilla's cure: a zombie villager with Weakness given a golden apple shakes for a few minutes (Weakness becomes
	 * Strength meanwhile), then turns back into a villager. The body turns back too: into the villager it was picked as,
	 * or a villager of its kind.
	 */
	private boolean cure(Player player) {
		if (!on("curing")) return false;
		MorphRef look = look(player);
		if (look == null || !look.entityType().equals("minecraft:zombie_villager") || !player.hasPotionEffect(PotionEffectType.WEAKNESS)) return false;
		Watch w = watches.computeIfAbsent(player.getUniqueId(), k -> new Watch(look));
		if (w.converting >= 0) return false; // already being cured
		int ticks = Math.max(STEP, plugin.settings().cureTicks);
		player.removePotionEffect(PotionEffectType.WEAKNESS);
		player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, ticks, 0));
		play(player, "entity.zombie_villager.cure");
		start(player, w, ticks, convertTo(look, "minecraft:villager"), "entity.zombie_villager.converted", "{ConversionTime:" + ticks + ",MorphShaking:1b}");
		return true;
	}

	// ------------------------------------------------------------------ lightning

	private void struck(Player player, LightningStrike bolt) {
		if (!on("lightning")) return;
		if (bolt.getUniqueId().equals(lastBolt.put(player.getUniqueId(), bolt.getUniqueId()))) return; // the same bolt again
		MorphRef look = look(player);
		if (look == null) return;
		boolean peaceful = player.getWorld().getDifficulty() == Difficulty.PEACEFUL;
		switch (look.entityType()) {
			case "minecraft:pig" -> {
				if (!peaceful) plugin.morphs().convert(player, convertTo(look, "minecraft:zombified_piglin"));
			}
			case "minecraft:villager" -> {
				if (!peaceful) plugin.morphs().convert(player, convertTo(look, "minecraft:witch"));
			}
			case "minecraft:mooshroom" -> {
				Map<String, Object> tag = tag(look);
				String colour = "brown".equals(tag.get("Type")) ? "red" : "brown";
				tag.put("Type", colour);
				// back to the colour it was picked as (red when the form doesn't say): the form itself, no conversion
				ActiveMorph s = plugin.morphs().state(player);
				MorphRef into = s != null && s.next != null && colour.equals(tag(s.next).getOrDefault("Type", "red"))
						? s.next : new MorphRef(look.entityType(), look.variantId(), Snbt.write(tag), null);
				if (plugin.morphs().convert(player, into)) play(player, "entity.mooshroom.convert");
			}
			default -> {
			}
		}
	}

	// ------------------------------------------------------------------ a sulfur cube fed a block

	/**
	 * A sulfur cube morph given a block (right click with it) holds it, as a sulfur cube does: TNT makes it explosive
	 * (its ability can then blow up). Only an empty cube takes one, and only the blocks sulfur cubes take (the
	 * {@code sulfur_cube_archetype} item tags).
	 */
	private boolean feedBlock(Player player, Material block) {
		if (!on("sulfur-cube")) return false;
		MorphRef look = look(player);
		if (look == null || !look.entityType().equals("minecraft:sulfur_cube") || holdsBlock(look) || !swallowable(block)) return false;
		Map<String, Object> tag = tag(look);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("count", 1);
		body.put("id", block.getKey().toString());
		tag.put("equipment", new LinkedHashMap<>(Map.of("body", body)));
		if (!plugin.morphs().convert(player, new MorphRef(look.entityType(), look.variantId(), Snbt.write(tag), null))) return false;
		play(player, "entity.sulfur_cube.absorb");
		return true;
	}

	/** After a sulfur cube's blast: the block it held is spent. */
	public void onExploded(Player player) {
		MorphRef converted = plugin.morphs().converted(player);
		ActiveMorph s = plugin.morphs().state(player);
		if (converted == null || s == null || s.next == null || !converted.entityType().equals("minecraft:sulfur_cube")) return;
		plugin.morphs().convert(player, s.next);
	}

	/** Damage types a sulfur cube holding a block shrugs off (#minecraft:sulfur_cube_with_block_immune_to, 26.3). */
	private static final java.util.Set<String> CUBE_IMMUNE = java.util.Set.of("arrow", "cactus", "dry_out", "fall", "falling_anvil", "falling_block",
			"falling_stalactite", "freeze", "mace_smash", "hot_floor", "mob_attack", "mob_attack_no_aggro", "mob_projectile", "player_attack", "spear",
			"spit", "stalagmite", "sting", "sulfur_cube_hot", "sweet_berry_bush", "thrown", "trident", "wind_charge");
	/** #minecraft:is_explosion: immune too, and they light a cube's TNT with a short fuse. */
	private static final java.util.Set<String> EXPLOSIONS = java.util.Set.of("fireworks", "explosion", "player_explosion", "bad_respawn_point");
	/** #minecraft:is_fire: they light a cube's TNT. */
	private static final java.util.Set<String> FIRE = java.util.Set.of("in_fire", "campfire", "on_fire", "lava", "hot_floor", "sulfur_cube_hot",
			"unattributed_fireball", "fireball");

	/**
	 * A sulfur cube holding a block, as vanilla's (SulfurCube.hurtServer, knockback): hits, falls, thorns and blasts do no
	 * harm, they bounce it away from whoever hit it, harder the harder the hit (the archetype's knockback: 0.4125 across,
	 * 0.09 up, by the square root of the damage). Holding TNT, fire or a burning arrow lights it, and a blast lights it
	 * with a short fuse. Once lit nothing hurts it.
	 */
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onCubeHurt(org.bukkit.event.entity.EntityDamageEvent event) {
		if (!(event.getEntity() instanceof Player player) || !on("sulfur-cube") || plugin.morphs().isTransitioning(player)) return;
		MorphRef form = plugin.morphs().activeForm(player);
		if (form == null || !form.entityType().equals("minecraft:sulfur_cube") || !holdsBlock(form)) return;
		if (plugin.abilities().ignoresDamage(player, event.getCause())) return; // its own blast, and traits' immunities
		if (plugin.abilities().isFusing(player)) {
			event.setCancelled(true);
			return;
		}
		String type = event.getDamageSource().getDamageType().getKey().getKey();
		Entity direct = event.getDamageSource().getDirectEntity();
		boolean burningArrow = direct instanceof org.bukkit.entity.AbstractArrow arrow && arrow.getFireTicks() > 0;
		if (FIRE.contains(type) || burningArrow) plugin.abilities().ignite(player, false);
		else if (EXPLOSIONS.contains(type)) plugin.abilities().ignite(player, true);
		if (!CUBE_IMMUNE.contains(type) && !EXPLOSIONS.contains(type)) return;
		event.setCancelled(true);
		Entity attacker = event.getDamageSource().getCausingEntity();
		if (attacker == null || EXPLOSIONS.contains(type)) return;
		double dx = player.getX() - attacker.getX(), dz = player.getZ() - attacker.getZ();
		double length = Math.sqrt(dx * dx + dz * dz);
		if (length < 1.0E-4) {
			double angle = Math.random() * Math.PI * 2;
			dx = Math.cos(angle);
			dz = Math.sin(angle);
			length = 1;
		}
		var resistance = player.getAttribute(org.bukkit.attribute.Attribute.KNOCKBACK_RESISTANCE);
		double keep = 1.0 - (resistance == null ? 0.0 : Math.min(1.0, resistance.getValue()));
		double strength = Math.sqrt(Math.max(0.0, event.getDamage()));
		double across = 0.4125 * strength * 0.4 * keep, up = 0.09 * strength * 1.2 * keep;
		org.bukkit.util.Vector velocity = player.getVelocity();
		player.setVelocity(new org.bukkit.util.Vector(velocity.getX() + dx / length * across, velocity.getY() + up, velocity.getZ() + dz / length * across));
		play(player, plugin.abilities().explosiveInside(form) ? "entity.sulfur_cube.explosive.hit" : "entity.sulfur_cube.regular.hit");
	}

	private static boolean holdsBlock(MorphRef look) {
		return tag(look).get("equipment") instanceof Map<?, ?> slots && slots.get("body") != null;
	}

	private static boolean swallowable(Material block) {
		for (Tag<Material> tag : org.bukkit.Bukkit.getTags(Tag.REGISTRY_ITEMS, Material.class)) {
			if (tag.getKey().getKey().startsWith("sulfur_cube_archetype/") && tag.isTagged(block)) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ the creature it becomes

	/**
	 * {@code look} as {@code type}: a baby stays a baby (where the new creature can be one), a villager's kind, job and
	 * level carry over to a zombie villager and back, and nothing else does (a pig struck by lightning is a plain
	 * zombified piglin).
	 */
	static MorphRef convertTo(MorphRef look, String type) {
		Map<String, Object> from = tag(look);
		Map<String, Object> to = new LinkedHashMap<>();
		if (isBaby(from) && !type.equals("minecraft:witch") && !type.equals("minecraft:stray")) {
			to.put("Age", -24000);
			to.put("AgeLocked", (byte) 1);
			to.put("IsBaby", (byte) 1);
		}
		boolean villagers = type.equals("minecraft:zombie_villager") || type.equals("minecraft:villager");
		if (villagers && from.get("VillagerData") != null) to.put("VillagerData", from.get("VillagerData"));
		return new MorphRef(type, look.variantId(), to.isEmpty() ? "" : Snbt.write(to), null);
	}

	private static boolean isBaby(Map<String, Object> tag) {
		Object baby = tag.get("IsBaby");
		if (baby instanceof Number n && n.intValue() != 0) return true;
		return tag.get("Age") instanceof Number age && age.intValue() < 0;
	}

	private static Map<String, Object> tag(MorphRef ref) {
		if (ref.snbt().isBlank()) return new LinkedHashMap<>();
		try {
			return new LinkedHashMap<>(Snbt.parseCompound(ref.snbt()));
		} catch (IllegalArgumentException e) {
			return new LinkedHashMap<>();
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		watches.remove(event.getPlayer().getUniqueId());
		lastBolt.remove(event.getPlayer().getUniqueId());
	}
}
