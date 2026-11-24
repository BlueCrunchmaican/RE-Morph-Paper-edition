package dev.morphreborn.paper.ability;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.data.MorphRef;
import dev.morphreborn.paper.variant.VariantRules;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.LlamaSpit;
import org.bukkit.entity.Player;
import org.bukkit.entity.ShulkerBullet;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Trident;
import org.bukkit.entity.WindCharge;
import org.bukkit.entity.WitherSkull;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * Mob traits (passive) and abilities (active). All rules come from abilities.yml; the gameplay effects use only the
 * public Paper API. Events are wired in {@link AbilityListener}.
 */
public final class AbilityManager {
	public record TimedEffect(PotionEffectType type, int amplifier, int ticks) {}

	private final MorphRebornPlugin plugin;
	private boolean enabled;
	private String vanillaTrigger = "sneak-swap-hand";
	private boolean cooldownMessage = true;

	private Set<String> flight = Set.of();
	private float flySpeed = 0.05F;
	/**
	 * After Morph Flight, a flier's morph that isn't flying glides while jump is held and drops when it isn't
	 * (2026-11-15: how the flight upgrade was always meant to work; passive.flight.hold-jump-glide, on by default).
	 */
	private boolean holdJumpGlide = true;
	private Set<String> slowFall = Set.of();
	private Set<String> fallImmunity = Set.of();
	/** passive.quiet-steps: the forms that raise no step, swim or soft-landing vibrations (see {@link QuietSteps}). */
	private QuietSteps.Settings quietSteps = QuietSteps.Settings.NONE;
	private Set<String> fireImmunity = Set.of();
	private Set<String> waterBreathing = Set.of();
	private Set<String> swimBoost = Set.of();
	private Set<String> climb = Set.of();
	/** Unmodded players currently holding on to a wall (spider climbing). */
	private final Set<UUID> climbing = new HashSet<>();
	private Set<String> sunburn = Set.of();
	private Set<String> waterDamage = Set.of();
	private Map<String, List<TimedEffect>> constantEffects = Map.of();
	private Map<String, Set<PotionEffectType>> effectImmunity = Map.of();
	private Map<String, List<TimedEffect>> attackEffects = Map.of();
	private Map<String, List<TimedEffect>> retaliateEffects = Map.of();
	private boolean hostileCamouflage = true;
	private Map<String, Set<String>> intimidate = Map.of();
	private Map<String, Set<String>> fear = Map.of();
	private boolean flightNeedsNether;
	private Set<String> waterOnlyBreathing = Set.of();
	private Set<String> sink = Set.of();
	private boolean attackEffectsUnarmedOnly;
	private Set<String> milkable = Set.of();
	private Set<String> snowTrail = Set.of();
	private Set<String> endermanCarry = Set.of();
	private int creeperFuseTicks = 30;
	private Set<String> heatSensitive = Set.of();
	private Set<String> lavaWalk = Set.of();
	private Set<String> berryImmune = Set.of();
	private Set<String> freezeImmunity = Set.of();
	private Set<String> deflectProjectiles = Set.of();
	private Set<String> teleportFromProjectiles = Set.of();
	private final Map<String, Aura> auras = new HashMap<>();
	private Map<String, TimedEffect> tippedArrows = Map.of();
	/** Vanilla #undead: Healing hurts them and Harming heals them. */
	private Set<String> undead = Set.of();
	/** Blocks that don't slow you down: cobwebs (spiders), sweet berry bushes (foxes), powder snow (walked on). */
	private Set<String> webWalk = Set.of();
	private Set<String> berryWalk = Set.of();
	private Set<String> powderSnowWalk = Set.of();
	/** Stand on lava like a strider (client mod; unmodded players float up through it instead). */
	private Set<String> lavaStand = Set.of();
	private Set<String> freezeWhenWatched = Set.of();
	private Set<String> waterTeleport = Set.of();
	private Set<String> hurtBySnowballs = Set.of();
	private Map<String, Double> magicResistance = Map.of();
	/** Walking speed on land as a share of the player's: turtles plod (vanilla 0.06 against a player's 0.1). */
	private Map<String, Double> landSpeed = Map.of();
	/** Land-speed multiplier currently applied per player (absent: none). */
	private final Map<UUID, Double> landSlowed = new HashMap<>();
	private final NamespacedKey landSpeedKey;
	/** Out of water this long (ticks) and you start drying out: axolotl, dolphin, nautilus. */
	private Map<String, Integer> dryOutTicks = Map.of();
	private Set<String> rainKeepsWet = Set.of();
	/** Underwater this long (ticks) before you start drowning: dolphins hold their breath for minutes. */
	private Map<String, Integer> breathHoldTicks = Map.of();
	private Set<String> dashWaterOnly = Set.of();
	private int sulfurCubeFuseTicks = 120;
	private int stingDeathTicks = 1200;
	private List<WitchPotion> witchPotions = List.of();
	private final Map<UUID, Integer> fishOnLand = new HashMap<>();
	private final Map<UUID, Integer> dryTicks = new HashMap<>();
	private final Map<UUID, Integer> underwaterTicks = new HashMap<>();
	private final Map<UUID, Long> sneakingSince = new HashMap<>();
	private final Map<UUID, Integer> witchChoice = new HashMap<>();
	private final Map<UUID, Long> lastWaterTeleport = new HashMap<>();
	/** Bees that have used their stinger, until they change form. */
	private final Set<UUID> stung = new HashSet<>();
	/**
	 * The wool of the borrowed sheep forms, once shears or grass changed it: per borrowed form, whether it is bald. Not the look
	 * overlay: that is the main body's, and a cow body has no wool to show (2026-11-17, B1-12). It is per form and survives a
	 * borrowed swap, as a main sheep's bald overlay does, so swapping the borrowed form is no way to farm wool; only a new body
	 * (or grass) grows it back.
	 */
	private final Map<UUID, Map<MorphRef, Boolean>> lentWool = new HashMap<>();

	/** Each spent stinger's own death clock (a number per sting), so an old sting's timer cannot run out on a newer one. */
	private final Map<UUID, Integer> stingTimer = new HashMap<>();
	private int stingSeq;
	/** Angry bees (red eyes), until they calm down or change form: the ability key with nothing in reach, or a sting. */
	private final Set<UUID> angry = new HashSet<>();
	/** Creakings that can't move because someone is looking at them. */
	private final Set<UUID> frozen = new HashSet<>();
	private final NamespacedKey frozenKey;
	private final NamespacedKey tradeFreezeKey = MorphRebornPlugin.key("trade_freeze"); // VillagerTrading's hold
	// the arm holds' modifier keys, as SpearImpale (PIN_MODIFIER) and WhipLash (SNARE_MODIFIER) name them
	private final NamespacedKey spearPinKey = MorphRebornPlugin.key("spear_pin"), whipSnareKey = MorphRebornPlugin.key("whip_snare");
	/** Guardian beams being charged. */
	private final Set<UUID> beaming = new HashSet<>();
	/** Death message keys for players killed by their own mob (creeper blast, spent bee stinger). */
	private final Map<UUID, String> deathMessages = new HashMap<>();
	/** Players whose Healing or Harming is being turned around right now, so the swap doesn't undo itself. */
	private final Set<UUID> inverting = new HashSet<>();

	private record WitchPotion(boolean drink, String potion) {}

	private static final Map<org.bukkit.Material, org.bukkit.Material> INFESTED = Map.of(
			org.bukkit.Material.STONE, org.bukkit.Material.INFESTED_STONE,
			org.bukkit.Material.COBBLESTONE, org.bukkit.Material.INFESTED_COBBLESTONE,
			org.bukkit.Material.STONE_BRICKS, org.bukkit.Material.INFESTED_STONE_BRICKS,
			org.bukkit.Material.MOSSY_STONE_BRICKS, org.bukkit.Material.INFESTED_MOSSY_STONE_BRICKS,
			org.bukkit.Material.CRACKED_STONE_BRICKS, org.bukkit.Material.INFESTED_CRACKED_STONE_BRICKS,
			org.bukkit.Material.CHISELED_STONE_BRICKS, org.bukkit.Material.INFESTED_CHISELED_STONE_BRICKS,
			org.bukkit.Material.DEEPSLATE, org.bukkit.Material.INFESTED_DEEPSLATE);
	/** Players playing dead (axolotl) until the given tick: mobs ignore them. */
	private final Map<UUID, Long> playingDead = new HashMap<>();

	private boolean targetingEnabled = true;
	private boolean predatorsHunt = true;
	private Set<String> alwaysHostile = Set.of();
	/** mob type -> morph types it naturally attacks (vanilla who-attacks-whom). */
	private Map<String, Set<String>> attacks = Map.of();

	/** inWater: only while the morph is in water; targetsInWater: only players who are swimming too. */
	private record Aura(PotionEffectType effect, int amplifier, int ticks, double radius, int everyTicks, boolean inWater, boolean targetsInWater) {}

	/**
	 * Sneak + jump: launch speed in blocks/tick, cooldown, and the mob's jump sound. A charged leap (horses) starts at
	 * minVelocity and builds up to velocity over the first second of sneaking.
	 */
	private record Leap(double velocity, double minVelocity, boolean charged, int cooldownTicks, @Nullable Sound sound) {}

	private Map<String, Leap> leaps = Map.of();
	private final Map<UUID, Long> leapReady = new HashMap<>();
	/** Tags fireballs and skulls shot through abilities, so their explosions follow the world-effects settings. */
	private final NamespacedKey abilityProjectileKey;

	/** Players whose creeper fuse is burning (explosion scheduled). */
	private final Set<UUID> fusing = new HashSet<>();
	/** Creeper morphs struck by lightning: charged until their next explosion (Creeper.thunderHit). */
	private final Set<UUID> charged = new HashSet<>();

	private final Map<String, ActiveAbility> actives = new HashMap<>();
	private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
	private final Map<UUID, Set<PotionEffectType>> appliedEffects = new HashMap<>();
	private final Set<UUID> grantedFlight = new HashSet<>();
	/** Mobs a camouflaged player has attacked: they may fight back. */
	private final Map<UUID, Set<UUID>> provoked = new HashMap<>();
	/** Each player's {@link #makeup} for the forms they had last: it is asked for on every target and damage event, and per mob each second. */
	private final Map<UUID, CachedMakeup> makeups = new HashMap<>();

	private record CachedMakeup(MorphRef main, @Nullable MorphRef lent, MorphMakeup makeup) {}
	private final Set<UUID> explosionImmune = new HashSet<>();
	private long tick;

	public AbilityManager(MorphRebornPlugin plugin) {
		this.plugin = plugin;
		this.abilityProjectileKey = MorphRebornPlugin.key("ability_projectile");
		this.frozenKey = MorphRebornPlugin.key("watched_freeze");
		this.landSpeedKey = MorphRebornPlugin.key("land_speed");
	}

	/** {@code quietStepsDefaults}: the bundled file's passive.quiet-steps, for a server file that has no such section. */
	public void load(FileConfiguration c, @Nullable ConfigurationSection quietStepsDefaults) {
		enabled = c.getBoolean("enabled", true);
		vanillaTrigger = c.getString("activation.vanilla-trigger", "sneak-swap-hand");
		cooldownMessage = c.getBoolean("activation.cooldown-message", true);
		flight = types(c.getStringList("passive.flight.types"));
		flySpeed = (float) Math.clamp(c.getDouble("passive.flight.fly-speed", 0.05), 0.01, 0.2);
		flightNeedsNether = c.getBoolean("passive.flight.require-nether-visit", false);
		holdJumpGlide = c.getBoolean("passive.flight.hold-jump-glide", true);
		waterOnlyBreathing = types(c.getStringList("passive.water-only-breathing"));
		sink = types(c.getStringList("passive.sink"));
		attackEffectsUnarmedOnly = c.getBoolean("passive.attack-effects-unarmed-only", false);
		milkable = types(c.getStringList("passive.milkable"));
		snowTrail = types(c.getStringList("passive.snow-trail"));
		endermanCarry = types(c.getStringList("active.enderman-carry.types"));
		creeperFuseTicks = Math.max(0, c.getInt("active.creeper-fuse-ticks", 30));
		heatSensitive = types(c.getStringList("passive.heat-sensitive"));
		lavaWalk = types(c.getStringList("passive.lava-walk"));
		berryImmune = types(c.getStringList("passive.berry-bush-immunity"));
		freezeImmunity = types(c.getStringList("passive.freeze-immunity"));
		deflectProjectiles = types(c.getStringList("passive.deflect-projectiles"));
		teleportFromProjectiles = types(c.getStringList("passive.teleport-from-projectiles"));
		undead = types(c.getStringList("passive.undead"));
		webWalk = types(c.getStringList("passive.web-walk"));
		berryWalk = types(c.getStringList("passive.berry-walk"));
		powderSnowWalk = types(c.getStringList("passive.powder-snow-walk"));
		lavaStand = types(c.getStringList("passive.lava-stand"));
		freezeWhenWatched = types(c.getStringList("passive.freeze-when-watched"));
		waterTeleport = types(c.getStringList("passive.teleport-out-of-water"));
		hurtBySnowballs = types(c.getStringList("passive.hurt-by-snowballs"));
		Map<String, Double> resist = new HashMap<>();
		ConfigurationSection resistSection = c.getConfigurationSection("passive.magic-resistance");
		if (resistSection != null) {
			for (String type : resistSection.getKeys(false)) resist.put(VariantRules.normaliseType(type), Math.clamp(resistSection.getDouble(type), 0, 1));
		}
		magicResistance = resist;
		Map<String, Double> land = new HashMap<>();
		ConfigurationSection landSection = c.getConfigurationSection("passive.land-speed");
		if (landSection != null) {
			for (String type : landSection.getKeys(false)) land.put(VariantRules.normaliseType(type), Math.clamp(landSection.getDouble(type), 0.05, 1));
		}
		landSpeed = land;
		dryOutTicks = secondsMap(c.getConfigurationSection("passive.dry-out"));
		rainKeepsWet = types(c.getStringList("passive.rain-keeps-wet"));
		breathHoldTicks = secondsMap(c.getConfigurationSection("passive.breath-hold"));
		dashWaterOnly = types(c.getStringList("active.dash.water-only"));
		sulfurCubeFuseTicks = Math.max(0, c.getInt("active.sulfur-cube-fuse-ticks", 120));
		stingDeathTicks = (int) Math.round(Math.max(0, c.getDouble("active.sting.dies-after-seconds", 60)) * 20);
		List<WitchPotion> potions = new ArrayList<>();
		for (String spec : c.getStringList("active.witch-potions.potions")) {
			String[] parts = spec.split(":", 2);
			boolean drink = parts.length == 2 && parts[0].trim().equalsIgnoreCase("drink");
			String name = (parts.length == 2 ? parts[1] : parts[0]).trim().toLowerCase(java.util.Locale.ROOT);
			if (org.bukkit.Registry.POTION.get(NamespacedKey.minecraft(name)) == null) {
				plugin.getLogger().warning("abilities.yml: unknown witch potion '" + name + "'");
				continue;
			}
			potions.add(new WitchPotion(drink, name));
		}
		witchPotions = potions;
		Map<String, Leap> leapMap = new HashMap<>();
		ConfigurationSection leapSection = c.getConfigurationSection("passive.leap");
		if (leapSection != null) {
			for (String type : leapSection.getKeys(false)) {
				ConfigurationSection l = leapSection.getConfigurationSection(type);
				if (l == null) continue;
				NamespacedKey soundKey = NamespacedKey.fromString(l.getString("sound", ""));
				Sound sound = soundKey == null ? null : org.bukkit.Registry.SOUNDS.get(soundKey);
				double velocity = Math.clamp(l.getDouble("velocity", 1.0), 0.1, 3.0);
				leapMap.put(VariantRules.normaliseType(type), new Leap(velocity, Math.clamp(l.getDouble("min-velocity", velocity * 0.4), 0.1, velocity),
						l.getBoolean("charged", false), (int) Math.round(Math.max(0, l.getDouble("cooldown-seconds", 1)) * 20), sound));
			}
		}
		leaps = leapMap;
		auras.clear();
		ConfigurationSection auraSection = c.getConfigurationSection("passive.auras");
		if (auraSection != null) {
			for (String type : auraSection.getKeys(false)) {
				ConfigurationSection a = auraSection.getConfigurationSection(type);
				PotionEffectType effect = a == null ? null : effect(a.getString("effect", ""));
				if (effect == null) continue;
				auras.put(VariantRules.normaliseType(type), new Aura(effect, a.getInt("amplifier", 0), a.getInt("seconds", 10) * 20, a.getDouble("radius", 16),
						Math.max(1, a.getInt("every-seconds", 30)) * 20, a.getBoolean("in-water", false), a.getBoolean("targets-in-water", false)));
			}
		}
		Map<String, TimedEffect> tipped = new HashMap<>();
		ConfigurationSection tippedSection = c.getConfigurationSection("active.arrow.tipped");
		if (tippedSection != null) {
			for (String type : tippedSection.getKeys(false)) {
				List<TimedEffect> parsed = parseEffects(List.of(tippedSection.getString(type, "")), true, type);
				if (!parsed.isEmpty()) tipped.put(VariantRules.normaliseType(type), parsed.getFirst());
			}
		}
		tippedArrows = tipped;
		targetingEnabled = c.getBoolean("targeting.enabled", true);
		predatorsHunt = c.getBoolean("targeting.predators-hunt-morphs", true);
		alwaysHostile = types(c.getStringList("targeting.always-hostile"));
		Map<String, Set<String>> attackMap = new HashMap<>();
		ConfigurationSection attackSection = c.getConfigurationSection("targeting.attacks");
		if (attackSection != null) {
			for (String type : attackSection.getKeys(false)) attackMap.put(VariantRules.normaliseType(type), types(attackSection.getStringList(type)));
		}
		attacks = attackMap;
		slowFall = types(c.getStringList("passive.slow-fall"));
		fallImmunity = types(c.getStringList("passive.fall-immunity"));
		// an 8.0.0 beta file has this release's version, so it is never merged and lacks the section: the bundled one applies then.
		// A section the admin has, even an emptied list, is theirs.
		ConfigurationSection quiet = c.getConfigurationSection("passive.quiet-steps");
		quietSteps = QuietSteps.Settings.read(quiet != null ? quiet : quietStepsDefaults);
		fireImmunity = types(c.getStringList("passive.fire-immunity"));
		waterBreathing = types(c.getStringList("passive.water-breathing"));
		swimBoost = types(c.getStringList("passive.swim-boost"));
		climb = types(c.getStringList("passive.climb"));
		sunburn = types(c.getStringList("passive.sunburn"));
		waterDamage = types(c.getStringList("passive.water-damage"));
		constantEffects = effectMap(c.getConfigurationSection("passive.effects"), false);
		attackEffects = effectMap(c.getConfigurationSection("passive.attack-effects"), true);
		retaliateEffects = effectMap(c.getConfigurationSection("passive.retaliate-effects"), true);
		effectImmunity = new HashMap<>();
		ConfigurationSection immune = c.getConfigurationSection("passive.effect-immunity");
		if (immune != null) {
			for (String type : immune.getKeys(false)) {
				Set<PotionEffectType> set = new HashSet<>();
				for (String e : immune.getStringList(type)) {
					PotionEffectType t = effect(e);
					if (t != null) set.add(t);
				}
				effectImmunity.put(VariantRules.normaliseType(type), set);
			}
		}
		hostileCamouflage = c.getBoolean("passive.hostile-camouflage", true);
		intimidate = new HashMap<>();
		ConfigurationSection scare = c.getConfigurationSection("passive.intimidate");
		if (scare != null) {
			for (String type : scare.getKeys(false)) intimidate.put(VariantRules.normaliseType(type), types(scare.getStringList(type)));
		}
		fear = new HashMap<>();
		ConfigurationSection feared = c.getConfigurationSection("passive.fear");
		if (feared != null) {
			for (String type : feared.getKeys(false)) fear.put(VariantRules.normaliseType(type), types(feared.getStringList(type)));
		}
		actives.clear();
		ConfigurationSection active = c.getConfigurationSection("active");
		if (active != null) {
			for (String name : active.getKeys(false)) {
				ConfigurationSection a = active.getConfigurationSection(name);
				if (a == null || name.equals("enderman-carry")) continue; // carrying is right-click, not the ability key
				ActiveAbility ability = new ActiveAbility(name, (long) (a.getDouble("cooldown-seconds", 3) * 20), a);
				for (String type : types(a.getStringList("types"))) {
					ActiveAbility previous = actives.put(type, ability);
					if (previous != null) {
						plugin.getLogger().warning("abilities.yml: " + type + " is listed under both " + previous.name + " and " + name + "; using " + name + ".");
					}
				}
			}
		}
	}

	public boolean anyActive() {
		return enabled && !actives.isEmpty();
	}

	/** abilities.yml {@code enabled}: whether mob traits and abilities work at all. */
	public boolean isEnabled() {
		return enabled;
	}

	public String vanillaTrigger() {
		return vanillaTrigger;
	}

	// ------------------------------------------------------------------ form changes

	/** The player's current form's entity type ("minecraft:zombie"), null for their own body or mid-transformation. */
	public @Nullable String currentFormType(Player player) {
		return type(player);
	}

	/**
	 * What the player's body is made of, to tell Camouflage (creatures only) from Borrowed Scent (you with a creature); see
	 * {@link MorphMakeup}. Nothing while they transform, as {@link #type} says.
	 */
	public MorphMakeup makeup(Player player) {
		if (plugin.morphs().isTransitioning(player)) return MorphMakeup.NONE;
		MorphRef main = plugin.morphs().activeForm(player);
		if (main == null) return MorphMakeup.NONE;
		MorphRef lent = plugin.borrowedHeads().borrowed(player);
		// kept until the forms change, so a crowd of mobs near a morphed player allocates nothing per event (fix round, 2026-11-17)
		CachedMakeup cached = makeups.get(player.getUniqueId());
		if (cached != null && java.util.Objects.equals(cached.main(), main) && java.util.Objects.equals(cached.lent(), lent)) return cached.makeup();
		MorphMakeup made = MorphMakeup.of(main, lent);
		makeups.put(player.getUniqueId(), new CachedMakeup(main, lent, made));
		return made;
	}

	/**
	 * Whether Borrowed Scent has anything to do for this player's body: it is Scent's and the creature in it is a kind that would
	 * ever target a player. A cow or pig with your own head spares nothing, so the passive idles and bills nothing
	 * (2026-11-17). Neutral kinds that fight when provoked (wolf, bee, iron golem...) still count; see {@link MorphMakeup#canTargetPlayers}.
	 */
	public boolean scentHasWork(Player player) {
		return makeup(player).scentHasWork(this::canTargetPlayers);
	}

	/** Whether creatures of {@code type} ("minecraft:cow") can ever target a player: hostile in the server's types, always hostile here, or provokable. */
	private boolean canTargetPlayers(String type) {
		NamespacedKey key = NamespacedKey.fromString(type);
		org.bukkit.entity.EntityType entityType = key == null ? null : org.bukkit.Registry.ENTITY_TYPE.get(key);
		Class<?> cls = entityType == null ? null : entityType.getEntityClass();
		return MorphMakeup.canTargetPlayers(type, cls, alwaysHostile.contains(type));
	}

	private @Nullable String type(Player player) {
		MorphRef form = plugin.morphs().activeForm(player);
		return form == null || plugin.morphs().isTransitioning(player) ? null : form.entityType();
	}

	/**
	 * The forms this player's body is made of, the main one first; multimorphed, the form it borrows from too. Traits
	 * work as "any of the forms has it" (the good ones still need their Survival unlock), and drawbacks combine the same
	 * way: a multimorph's cost. Size, health and the body's own abilities (carrying blocks, jockeys) stay the main form's.
	 */
	private List<String> types(Player player) {
		String main = type(player);
		if (main == null) return List.of();
		MorphRef lent = plugin.borrowedHeads().borrowed(player);
		return lent == null || lent.isSelf() || lent.isPlayer() || lent.entityType().equals(main) ? List.of(main) : List.of(main, lent.entityType());
	}

	private static boolean any(List<String> forms, Set<String> set) {
		for (String t : forms) if (set.contains(t)) return true;
		return false;
	}

	private boolean anyHas(Player player, List<String> forms, Set<String> set, String trait) {
		for (String t : forms) if (has(player, t, set, trait)) return true;
		return false;
	}

	/** About to transform: drop everything the old form granted. */
	public void onFormChanging(Player player) {
		clearPassives(player);
	}

	public void onFormChanged(Player player, @Nullable MorphRef form) {
		onFormChanged(player, form, false);
	}

	/**
	 * {@code sameBody}: the body is the same and only what it is made of was refreshed (another borrowed form, a reload, a
	 * mode switch): the state that belongs to a form still in it (a charge, a perch, a spent stinger) and the air and
	 * dry-out clocks carry on, so swapping the borrowed form is no way to reset them, and the look stays what the state is.
	 */
	public void onFormChanged(Player player, @Nullable MorphRef form, boolean sameBody) {
		// a flier in the air stays in the air through a borrowed swap that still has a flier: the teardown below drops the flight
		// and the grant only gives the permission back, so without this the player falls until they double-jump
		boolean wasAirborne = sameBody && grantedFlight.contains(player.getUniqueId()) && player.isFlying();
		clearPassives(player, sameBody ? types(player) : null);
		if (sameBody) plugin.passives().restore(player); // the form's night vision just went: no blink before Night Eyes' own
		abilityChoice.remove(player.getUniqueId()); // a new combination starts at its main form's ability
		if (!enabled || form == null || !player.hasPermission("morphreborn.ability")) return;
		List<String> forms = types(player);
		if (forms.isEmpty()) forms = List.of(form.entityType());
		if (!isPerched(player)) for (String t : forms) grantFlight(player, t); // a perched bat holds still: flight at zero speed
		if (wasAirborne && !isPerched(player) && grantedFlight.contains(player.getUniqueId()) && player.getAllowFlight()) flying(player, true);
		plugin.channel().sendTraits(player, traitBits(player, forms));
		applyEffects(player, forms);
	}

	/**
	 * The form's constant effects that the player may have (Survival: bought their unlock), added once; ones they may
	 * no longer have (an admin lowered their upgrades) are taken away.
	 */
	private void applyEffects(Player player, List<String> forms) {
		Set<PotionEffectType> applied = appliedEffects.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());
		List<TimedEffect> effects = new ArrayList<>();
		for (String t : forms) effects.addAll(constantEffects.getOrDefault(t, List.of()));
		// A giant-sized player's speed is the pair's own modifier (MorphManager.syncGiantPlayers, 5x a player's): the giant's
		// slowness is for a giant's body and would take the 5x down to 3.5x
		if (dev.morphreborn.paper.disguise.GiantPlayer.applies(plugin.morphs().activeForm(player), plugin.borrowedHeads().borrowed(player))) {
			effects.removeIf(e -> e.type().equals(PotionEffectType.SLOWNESS));
		}
		effects = BodyState.strongest(effects, TimedEffect::type, TimedEffect::amplifier); // the same effect from both forms: the stronger
		for (TimedEffect e : effects) {
			boolean allowed = allowed(player, "effect:" + e.type().getKey().getKey());
			if (allowed && applied.add(e.type())) {
				player.addPotionEffect(new PotionEffect(e.type(), PotionEffect.INFINITE_DURATION, e.amplifier(), true, false, true));
			} else if (!allowed && applied.remove(e.type())) {
				PotionEffect current = player.getPotionEffect(e.type());
				if (current != null && current.isInfinite() && current.isAmbient()) player.removePotionEffect(e.type());
			}
		}
		if (applied.isEmpty()) appliedEffects.remove(player.getUniqueId());
	}

	/**
	 * Whether a movement game event this player raised is dropped before any sensor or warden hears it (quiet steps; the listener calls
	 * this for every vibration in the world, so the cheap checks come first and nothing is allocated). The main form decides, read live
	 * each time, so the very next step after a form change follows the new form; {@link #type} is null while transforming and in your
	 * own body, which are never quiet. Like every trait it needs abilities on and the ability permission; no Survival unlock lists it
	 * unless an admin adds one under an upgrade's traits, so it is on from the start.
	 */
	public boolean quietSteps(Player player, QuietSteps.Move move, float fallDistance) {
		if (!enabled) return false;
		String main = type(player);
		if (main == null || !quietSteps.types().contains(main)) return false; // the set lookup goes before the permission and the mode
		if (!player.hasPermission("morphreborn.ability") || !allowed(player, "quiet-steps")) return false;
		return quietSteps.silences(move, main, fallDistance);
	}

	/** The gameplay mode lets the player use this beneficial trait (Survival: its unlock is bought; Classic: always). */
	private boolean allowed(Player player, String trait) {
		return plugin.mode().hasTrait(player, trait);
	}

	/** The form has this beneficial trait and the player may use it. */
	private boolean has(Player player, String type, Set<String> set, String trait) {
		return set.contains(type) && allowed(player, trait);
	}

	/**
	 * Water breathing: a bought trait, except for creatures that can't live out of water (fish, which suffocate on
	 * land, and the ones that dry out): without it they'd have nowhere to breathe.
	 */
	private boolean breathesWater(Player player, String type) {
		if (!waterBreathing.contains(type)) return false;
		return waterOnlyBreathing.contains(type) || dryOutTicks.containsKey(type) || allowed(player, "water-breathing");
	}

	/**
	 * True when one of the player's forms already breathes under water (a fish, an axolotl, or a form with the Water Breathing
	 * unlock), so the Gills passive has nothing to do and bills nothing (passives billing, 2026-11-16).
	 */
	public boolean coversDrowning(Player player) {
		for (String t : types(player)) if (breathesWater(player, t)) return true;
		return false;
	}

	/**
	 * True when one of the player's forms burns in daylight (a zombie, a skeleton: abilities.yml passive.sunburn). That fire is a
	 * drawback "always on by design", so the Fire Shedding passive leaves its burning tick alone (2026-11-16).
	 */
	public boolean burnsInSunlight(Player player) {
		return any(types(player), sunburn);
	}

	/** Whether {@code player}'s flight is a flying morph's (which a frenzy's hover leaves alone). */
	public boolean grantsFlight(Player player) {
		return grantedFlight.contains(player.getUniqueId());
	}

	/** Survival and adventure players morphed into a flyer may fly (creative and spectator fly anyway). */
	private void grantFlight(Player player, String type) {
		GameMode mode = player.getGameMode();
		boolean survivalLike = mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
		boolean netherOk = !flightNeedsNether || hasBeenToNether(player);
		if (flight.contains(type) && survivalLike && netherOk && plugin.mode().mayFly(player)) {
			allowFlight(player, true);
			flySpeed(player, flierSpeed(frozen.contains(player.getUniqueId()), isPerched(player), flySpeed));
			grantedFlight.add(player.getUniqueId());
		}
	}

	/*
	 * A shulker body clinging to a block is held there by flying at zero speed (ShulkerLock). Flight changes made while
	 * it holds the player (a borrowed bee's flight, the teardown when the form changes) go into what the lock gives back
	 * on letting go, so the player isn't dropped out of the hold now and gets exactly the right flight later.
	 */

	private void allowFlight(Player player, boolean allow) {
		if (!plugin.shulkerLock().routeAllowFlight(player, allow)) player.setAllowFlight(allow);
	}

	private void flying(Player player, boolean on) {
		if (!plugin.shulkerLock().routeFlying(player, on)) player.setFlying(on);
	}

	private void flySpeed(Player player, float speed) {
		if (!plugin.shulkerLock().routeFlySpeed(player, speed)) player.setFlySpeed(speed);
	}

	/**
	 * Called a tick after the player's game mode changed. Minecraft resets flying whenever the game mode changes
	 * (GameType.updatePlayerAbilities), which used to take a flying morph's flight away until the next morph, and a
	 * player who morphed in creative never got it on switching to survival. Only flight is redone: re-running every
	 * passive would also restart timers such as a bee's last minute or an axolotl drying out.
	 */
	public void onGameModeChanged(Player player) {
		// first: a held shulker takes the new mode's flight as what it gives back, before the grants below go into it
		plugin.shulkerLock().onGameModeChanged(player);
		unperch(player, true); // the mode change stopped the flying that held the bat up
		String type = type(player);
		if (!enabled || type == null || !player.hasPermission("morphreborn.ability")) return;
		GameMode mode = player.getGameMode();
		if (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE) {
			for (String t : types(player)) grantFlight(player, t);
		} else if (grantedFlight.remove(player.getUniqueId())) {
			flySpeed(player, 0.1F); // creative and spectator fly at the normal speed, not the morph's
		}
	}

	private static boolean hasBeenToNether(Player player) {
		org.bukkit.advancement.Advancement adv = org.bukkit.Bukkit.getAdvancement(NamespacedKey.minecraft("story/enter_the_nether"));
		return adv == null || player.getAdvancementProgress(adv).isDone();
	}

	/**
	 * Traits the client mod runs itself for this player's current form (Wire.TRAIT_*): they change movement physics,
	 * which only the player's own client can do smoothly.
	 */
	public int clientTraits(Player player) {
		List<String> forms = types(player);
		return !forms.isEmpty() && enabled && player.hasPermission("morphreborn.ability") ? traitBits(player, forms) : 0;
	}

	/**
	 * Survival Mode: a flying mob's morph can't fly until the player buys Morph Flight. Until then it glides down like
	 * a chicken (the slow-fall trait) instead of dropping like a stone.
	 */
	private boolean glides(Player player, String type) {
		return flight.contains(type) && !plugin.mode().mayFly(player);
	}

	/** A flier's morph that may fly, with hold-jump-glide on: it glides while jump is held ({@link #holdJumpGlide}). */
	private boolean glidesOnJump(Player player, String type) {
		return holdJumpGlide && flight.contains(type) && plugin.mode().mayFly(player);
	}

	/** Re-apply flight, movement traits and effects after a Survival upgrade changed what the current form may do. */
	public void refreshMovement(Player player) {
		List<String> forms = types(player);
		if (forms.isEmpty() || !enabled || !player.hasPermission("morphreborn.ability")) return;
		for (String t : forms) grantFlight(player, t);
		plugin.channel().sendTraits(player, traitBits(player, forms));
		applyEffects(player, forms);
	}

	private int traitBits(Player player, List<String> forms) {
		int bits = 0;
		for (String t : forms) bits |= traitBits(player, t);
		return bits;
	}

	private int traitBits(Player player, String type) {
		int bits = 0;
		if (has(player, type, climb, "climb")) bits |= dev.morphreborn.paper.net.Wire.TRAIT_CLIMB;
		if (has(player, type, webWalk, "web-walk")) bits |= dev.morphreborn.paper.net.Wire.TRAIT_WEB;
		if (has(player, type, powderSnowWalk, "powder-snow-walk")) bits |= dev.morphreborn.paper.net.Wire.TRAIT_POWDER_SNOW;
		if (has(player, type, lavaStand, "lava-stand")) bits |= dev.morphreborn.paper.net.Wire.TRAIT_LAVA;
		if (has(player, type, berryWalk, "berry-walk")) bits |= dev.morphreborn.paper.net.Wire.TRAIT_BERRY;
		if (has(player, type, slowFall, "slow-fall") || glides(player, type)) bits |= dev.morphreborn.paper.net.Wire.TRAIT_SLOW_FALL;
		if (glidesOnJump(player, type)) bits |= dev.morphreborn.paper.net.Wire.TRAIT_GLIDE_ON_JUMP;
		return bits;
	}

	/** The overlay keys each ability writes (MorphManager.setOverlayKeys): one writer never wipes another's look. */
	private static final Set<String> CREEPER_KEYS = Set.of("powered", "ignited");
	private static final Set<String> BEE_KEYS = Set.of("HasStung", "anger_end_time");
	private static final Set<String> BAT_KEYS = Set.of("BatFlags");
	private static final Set<String> ROLL_KEYS = Set.of("state");
	private static final Set<String> SHEAR_KEYS = Set.of("Sheared");

	/** A creeper morph's look: the charge's electric aura and the swelling fuse, together. */
	private String creeperLook(Player player, boolean ignited) {
		boolean powered = charged.contains(player.getUniqueId());
		if (!powered && !ignited) return "";
		return "{" + (powered ? "powered:1b" : "") + (powered && ignited ? "," : "") + (ignited ? "ignited:1b" : "") + "}";
	}

	/**
	 * The player was struck by lightning. A creeper morph becomes charged, like a real creeper (Creeper.thunderHit): the
	 * electric aura shows for everyone and its next explosion is twice as big.
	 */
	public void onLightning(Player player) {
		// any form with a creeper in it: a pig lending a creeper's head is as good a bomb as the creeper (any-form rule)
		if (!enabled || !types(player).contains("minecraft:creeper") || !player.hasPermission("morphreborn.ability")) return;
		if (isPoweredForm(plugin.morphs().activeForm(player)) || !charged.add(player.getUniqueId())) return; // already charged
		// the aura is the creeper body's; a borrowed creeper holds the charge for its blast without drawing it
		if ("minecraft:creeper".equals(type(player))) plugin.morphs().setOverlayKeys(player, CREEPER_KEYS, creeperLook(player, fusing.contains(player.getUniqueId())));
		player.sendActionBar(Component.text("Charged! Your next explosion is twice as big.", NamedTextColor.AQUA));
	}

	public boolean isCharged(Player player) {
		return charged.contains(player.getUniqueId()) || isPoweredForm(plugin.morphs().activeForm(player));
	}

	/** A charged-creeper morph: unlocked by killing a creeper that was charged. */
	private static boolean isPoweredForm(@Nullable MorphRef form) {
		return form != null && form.entityType().equals("minecraft:creeper") && form.snbt().replace(" ", "").contains("powered:1b");
	}

	/**
	 * A bee's look: its stinger gone once it has stung, red eyes while it is angry. Anger is a game time it lasts until;
	 * this one lasts until the bee calms down or changes form.
	 */
	private void beeOverlay(Player p) {
		List<String> tags = new ArrayList<>(2);
		if (stung.contains(p.getUniqueId())) tags.add("HasStung:1b");
		if (angry.contains(p.getUniqueId())) tags.add("anger_end_time:" + Long.MAX_VALUE + "L");
		plugin.morphs().setOverlayKeys(p, BEE_KEYS, tags.isEmpty() ? "" : "{" + String.join(",", tags) + "}");
	}

	public void clearPassives(Player player) {
		clearPassives(player, null);
	}

	/** {@code keep}: the forms the same body still has (see onFormChanged), or null for a new body: everything goes. */
	private void clearPassives(Player player, @Nullable List<String> keep) {
		plugin.channel().sendTraits(player, 0);
		UUID id = player.getUniqueId();
		boolean sameBody = keep != null;
		boolean keepsPerch = sameBody && BodyState.keepsPerch(keep) && perched.containsKey(id);
		boolean keepsSting = sameBody && BodyState.keepsSting(keep);
		// state that drew a look went: the look goes with it (it stays for what is kept)
		boolean chargeLook = false, beeLook = false, batLook = false;
		if (!(sameBody && BodyState.keepsCharge(keep))) chargeLook = charged.remove(id); // a creeper body's charge; a new form or a respawn loses it
		if (!keepsPerch) {
			batLook = perched.containsKey(id);
			unperch(player, false); // first: it puts back the flight state the flight passive below may clear
		}
		PotionEffect chickenFall = player.getPotionEffect(PotionEffectType.SLOW_FALLING);
		if (chickenFall != null && isChickenFall(chickenFall)) player.removePotionEffect(PotionEffectType.SLOW_FALLING);
		climbing.remove(id);
		setFrozen(player, false);
		setLandSpeed(player, 1.0);
		if (!keepsSting) {
			beeLook = stung.remove(id);
			beeLook |= angry.remove(id);
			stingTimer.remove(id); // a sting's death clock stops with it, and cannot be picked up by the next one
		}
		beaming.remove(id);
		if (!sameBody) lentWool.remove(id); // a new body starts woolly; within a body a shearing stays through borrowed swaps (grass is what regrows it)
		if (!sameBody || keep.stream().noneMatch(waterOnlyBreathing::contains)) fishOnLand.remove(id); // only a fish form's tick advances it: a stale count would skip the grace
		if (!sameBody || keep.stream().noneMatch(dryOutTicks::containsKey)) dryTicks.remove(id);
		if (!sameBody || keep.stream().noneMatch(breathHoldTicks::containsKey)) underwaterTicks.remove(id);
		if (sameBody) {
			// only what these abilities drew; a carried block, a roll or a shearing stays. (A new body resets its own look.)
			if (chargeLook) plugin.morphs().setOverlayKeys(player, CREEPER_KEYS, "");
			if (beeLook) plugin.morphs().setOverlayKeys(player, BEE_KEYS, "");
			if (batLook) plugin.morphs().setOverlayKeys(player, BAT_KEYS, "");
		}
		if (!keepsPerch && grantedFlight.remove(player.getUniqueId())) {
			GameMode mode = player.getGameMode();
			if (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE) {
				flying(player, false);
				allowFlight(player, false);
			}
			flySpeed(player, 0.1F);
		}
		Set<PotionEffectType> applied = appliedEffects.remove(player.getUniqueId());
		if (applied != null) {
			for (PotionEffectType t : applied) {
				PotionEffect current = player.getPotionEffect(t);
				if (current != null && current.isInfinite() && current.isAmbient()) player.removePotionEffect(t);
			}
		}
		provoked.remove(player.getUniqueId());
	}

	public void forget(Player player) {
		clearPassives(player);
		cooldowns.remove(player.getUniqueId());
		leapReady.remove(player.getUniqueId());
		witchChoice.remove(player.getUniqueId());
		abilityChoice.remove(player.getUniqueId());
		sneakingSince.remove(player.getUniqueId());
		deathMessages.remove(player.getUniqueId());
		makeups.remove(player.getUniqueId());
	}

	// ------------------------------------------------------------------ ticking

	public void tick() {
		tick++;
		if (!perched.isEmpty()) tickPerched();
		if (!enabled) return;
		for (Player player : plugin.getServer().getOnlinePlayers()) {
			List<String> forms = types(player);
			if (forms.isEmpty()) continue;
			if (anyHas(player, forms, slowFall, "slow-fall") || forms.stream().anyMatch(t -> glides(player, t))) tickSlowFall(player, true);
			else if (forms.stream().anyMatch(t -> glidesOnJump(player, t))) tickSlowFall(player, player.getCurrentInput().isJump());
			if (anyHas(player, forms, climb, "climb")) tickClimb(player);
			// Webs work like scaffolding for spider morphs (client mod): climbing and sliding down them never builds a fall.
			if (anyHas(player, forms, webWalk, "web-walk") && inCobweb(player)) player.setFallDistance(0);
			if (tick % 2 == 0 && any(forms, freezeWhenWatched)) tickWatched(player);
			// drying out: whichever form dries out soonest; holding breath: whichever holds it longest
			String dries = null;
			Integer dryLimit = null;
			Integer breathLimit = null;
			for (String t : forms) {
				Integer dry = dryOutTicks.get(t);
				if (dry != null && (dryLimit == null || dry < dryLimit)) {
					dryLimit = dry;
					dries = t;
				}
				Integer breath = breathHoldTicks.get(t);
				if (breath != null && (breathLimit == null || breath > breathLimit)) breathLimit = breath;
			}
			// a fish's suffocation is the quickest air drawback; while it drains, a slower dry-out clock must not write the
			// air back up each tick (drawbacks combine: the worse one counts)
			boolean fishDrains = any(forms, waterOnlyBreathing) && ticksOnLand(player) > 10 && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR;
			if (dryLimit != null) tickDryOut(player, dries, dryLimit, !fishDrains);
			if (breathLimit != null) tickBreathHold(player, breathLimit);
			if (any(forms, waterTeleport) && player.isInWater() && tick - lastWaterTeleport.getOrDefault(player.getUniqueId(), -100L) >= 20) {
				lastWaterTeleport.put(player.getUniqueId(), tick);
				teleportRandomly(player);
			}
			if (tick % 40 == 0 && plugin.mode().cannotUseAbility(player) == null) {
				// multimorphed with two abilities: which one the key uses, always on show; a witch: its next potion
				List<Choice> rotation = rotation(forms);
				if (!rotation.isEmpty()) showChoice(player, rotation.get(Math.floorMod(abilityChoice.getOrDefault(player.getUniqueId(), 0), rotation.size())));
				else if (forms.stream().anyMatch(t -> isWitch(player, t))) showWitchPotion(player);
			}
			if (anyHas(player, forms, swimBoost, "swim-boost") && player.isInWater() && tick % 20 == 0) {
				player.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 40, 0, true, false, false));
			}
			if (forms.stream().anyMatch(t -> breathesWater(player, t)) && player.isInWater()) player.setRemainingAir(player.getMaximumAir());
			if (tick % 20 == 0 && any(forms, sunburn)) tickSunburn(player);
			if (tick % 10 == 0 && any(forms, waterDamage) && player.isInWaterOrRainOrBubbleColumn()) player.damage(1.0);
			if (any(forms, sink) && player.isInWater() && !player.isOnGround()) {
				Vector v = player.getVelocity();
				if (v.getY() > -0.15) player.setVelocity(v.setY(Math.min(v.getY(), 0) - 0.04));
			}
			// A fish bobbing out at the surface for a moment doesn't start suffocating: half a second out of water first.
			if (fishDrains) {
				int air = player.getRemainingAir() - 5; // vanilla refills 4 per tick on land, so this nets -1 like a fish out of water
				if (air <= -20) {
					air = 0;
					player.damage(2.0);
				}
				player.setRemainingAir(air);
			}
			if (tick % 10 == 0 && allowed(player, "fear")) {
				Set<String> scared = new HashSet<>();
				for (String t : forms) scared.addAll(fear.getOrDefault(t, Set.of()));
				if (!scared.isEmpty()) tickFear(player, scared);
			}
			if (tick % 4 == 0 && any(forms, snowTrail)) tickSnowTrail(player);
			// also for Borrowed Scent, which drops a mob it spares that already has you in its sights (fix round, 2026-11-17)
			if (tick % 20 == 0 && (targetingEnabled || makeup(player).kind() == MorphMakeup.Kind.SCENT)) tickTargeting(player, forms);
			if (tick % 20 == 0 && any(forms, heatSensitive) && player.getLocation().getBlock().getTemperature() > 1.0) player.damage(1.0);
			// slow on land: whichever form is slowest
			Double land = null;
			for (String t : forms) {
				Double share = landSpeed.get(t);
				if (share != null && (land == null || share < land)) land = share;
			}
			if (land != null) tickLandSpeed(player, land);
			// Modded striders stand on the lava themselves (LivingEntityMixin); pushing them up too made them bob.
			if (anyHas(player, forms, lavaWalk, "lava-walk") && player.isInLava() && !(anyHas(player, forms, lavaStand, "lava-stand") && plugin.channel().isModded(player))) {
				Vector v = player.getVelocity();
				player.setVelocity(v.setY(Math.max(v.getY(), 0.12)));
				player.setFallDistance(0);
			}
			for (String t : forms) {
				Aura aura = auras.get(t);
				if (aura == null || tick % aura.everyTicks() != 0 || !allowed(player, "auras") || (aura.inWater() && !player.isInWater())) continue;
				for (Player other : player.getWorld().getPlayers()) {
					if (other == player || other.getGameMode() == GameMode.CREATIVE || other.getGameMode() == GameMode.SPECTATOR) continue;
					if (aura.targetsInWater() && !other.isInWater()) continue;
					if (other.getLocation().distanceSquared(player.getLocation()) <= aura.radius() * aura.radius()) {
						other.addPotionEffect(new PotionEffect(aura.effect(), aura.ticks(), aura.amplifier()));
					}
				}
			}
		}
	}

	/**
	 * Spider climbing: any wall works like a ladder. Players with the client mod get real ladder physics from their
	 * client (S_TRAITS), so the server only forgives the fall. For everyone else (plain Java, Bedrock) it runs from
	 * here off their movement keys: walk into a wall or jump against it to climb, let go to slide down slowly, sneak
	 * to hold on.
	 */
	private void tickClimb(Player player) {
		UUID id = player.getUniqueId();
		if (player.isFlying() || player.isGliding() || player.isInsideVehicle() || player.getGameMode() == GameMode.SPECTATOR || !wallAround(player, 0, 0)) {
			climbing.remove(id);
			return;
		}
		player.setFallDistance(0);
		if (plugin.channel().isModded(player)) return;
		org.bukkit.Input input = player.getCurrentInput();
		Vector wish = wishDirection(player, input);
		boolean pushing = wish != null && wallAround(player, wish.getX() * 0.12, wish.getZ() * 0.12);
		boolean attached = climbing.contains(id) && !player.isOnGround();
		double y;
		if (pushing || (attached && input.isJump())) {
			y = CLIMB_SPEED;
		} else if (attached) {
			y = input.isSneak() ? 0.0 : -0.15; // vanilla ladders: sneak holds you, otherwise you slide at 0.15
		} else {
			climbing.remove(id);
			return;
		}
		climbing.add(id);
		Vector v = player.getVelocity();
		player.setVelocity(new Vector(v.getX(), y, v.getZ()));
	}

	private static boolean inCobweb(Player player) {
		org.bukkit.Location at = player.getLocation();
		return at.getBlock().getType() == org.bukkit.Material.COBWEB || at.clone().add(0, -0.05, 0).getBlock().getType() == org.bukkit.Material.COBWEB
				|| player.getEyeLocation().getBlock().getType() == org.bukkit.Material.COBWEB;
	}

	/** Climb speed for unmodded players, close to a ladder's. */
	private static final double CLIMB_SPEED = 0.15;

	/**
	 * Whether a wall touches the player's sides (dx = dz = 0), or is in the way when moving by dx/dz. Trimmed top and
	 * bottom so the floor and ceiling don't count.
	 */
	private static boolean wallAround(Player player, double dx, double dz) {
		org.bukkit.util.BoundingBox b = player.getBoundingBox();
		double grow = dx == 0 && dz == 0 ? 0.08 : 0;
		return player.getWorld().hasCollisionsIn(new org.bukkit.util.BoundingBox(b.getMinX() - grow + dx, b.getMinY() + 0.05, b.getMinZ() - grow + dz,
				b.getMaxX() + grow + dx, b.getMaxY() - 0.05, b.getMaxZ() + grow + dz));
	}

	/** The horizontal direction the player's movement keys point, or null when none are held. */
	static @Nullable Vector wishDirection(Player player, org.bukkit.Input input) {
		int forward = (input.isForward() ? 1 : 0) - (input.isBackward() ? 1 : 0);
		int left = (input.isLeft() ? 1 : 0) - (input.isRight() ? 1 : 0);
		if (forward == 0 && left == 0) return null;
		double yaw = Math.toRadians(player.getLocation().getYaw());
		return new Vector(-Math.sin(yaw) * forward + Math.cos(yaw) * left, 0, Math.cos(yaw) * forward + Math.sin(yaw) * left).normalize();
	}

	/**
	 * Mobs that would not attack what you look like drop you as a target; neutral predators (golems, wolves,
	 * axolotls...) pick you up as a target when you look like their prey.
	 */
	private void tickTargeting(Player player, List<String> forms) {
		GameMode mode = player.getGameMode();
		boolean huntable = mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
		for (Entity e : player.getNearbyEntities(16, 8, 16)) {
			if (!(e instanceof org.bukkit.entity.Mob mob)) continue;
			if (mob.getTarget() == player) {
				if (shouldIgnore(mob, player)) mob.setTarget(null);
				continue;
			}
			if (!targetingEnabled || !predatorsHunt || !huntable || mob.getTarget() != null || mob instanceof org.bukkit.entity.Enemy) continue;
			Set<String> prey = attacks.get(mob.getType().getKey().toString());
			if (prey != null && forms.stream().anyMatch(prey::contains) && mob.hasLineOfSight(player)) mob.setTarget(player);
		}
	}

	/** Feared mobs within 10 blocks run directly away (the original fear trait). */
	private void tickFear(Player player, Set<String> scared) {
		for (Entity e : player.getNearbyEntities(10, 4, 10)) {
			if (!(e instanceof org.bukkit.entity.Mob mob) || !scared.contains(mob.getType().getKey().toString())) continue;
			Vector away = mob.getLocation().toVector().subtract(player.getLocation().toVector()).setY(0);
			if (away.lengthSquared() < 1e-3) away = new Vector(1, 0, 0);
			Location target = mob.getLocation().add(away.normalize().multiply(8));
			if (mob.getTarget() == player) mob.setTarget(null);
			mob.getPathfinder().moveTo(target, 1.4);
		}
	}

	/** Snow golems leave snow where it is cold enough to snow. */
	private void tickSnowTrail(Player player) {
		Block feet = player.getLocation().getBlock();
		if (!feet.getType().isAir() || !player.isOnGround()) return;
		Block below = feet.getRelative(BlockFace.DOWN);
		if (!below.getType().isSolid() || !below.getType().isOccluding()) return;
		if (feet.getTemperature() >= 0.8) return;
		feet.setType(org.bukkit.Material.SNOW);
	}

	private void tickSunburn(Player player) {
		var world = player.getWorld();
		long time = world.getTime() % 24000;
		if (time > 12300 || world.hasStorm() || player.isInWaterOrRainOrBubbleColumn()) return;
		if (player.getInventory().getHelmet() != null && !player.getInventory().getHelmet().isEmpty()) return;
		if (player.getLocation().add(0, player.getEyeHeight(), 0).getBlock().getLightFromSky() < 15) return;
		player.setFireTicks(Math.max(player.getFireTicks(), 160));
	}

	// ------------------------------------------------------------------ vanilla traits

	/** Creaking: it can't move while a player is looking at it. */
	private void tickWatched(Player player) {
		boolean watched = false;
		if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {
			Location target = player.getEyeLocation();
			for (Player other : player.getWorld().getPlayers()) {
				if (other == player || other.getGameMode() == GameMode.SPECTATOR || other.isDead()) continue;
				Vector to = target.toVector().subtract(other.getEyeLocation().toVector());
				double distance = to.length();
				if (distance > 32 || distance < 1e-3) continue;
				// the same leeway as vanilla's isLookingAtMe: the closer you are, the wider the cone
				if (other.getEyeLocation().getDirection().dot(to.normalize()) > 1 - 0.5 / distance && other.hasLineOfSight(player)) {
					watched = true;
					break;
				}
			}
		}
		setFrozen(player, watched);
	}

	/**
	 * Turtles walk slowly on land; their leg animation is made for that pace and flails at a player's. Sprinting
	 * doesn't make it faster: the sprint bonus is divided back out.
	 */
	private void tickLandSpeed(Player player, double share) {
		boolean onLand = !player.isInWater() && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR;
		setLandSpeed(player, !onLand ? 1.0 : player.isSprinting() ? share / 1.3 : share);
	}

	private void setLandSpeed(Player player, double multiplier) {
		Double current = landSlowed.get(player.getUniqueId());
		if (current == null ? multiplier == 1.0 : current == multiplier) return;
		org.bukkit.attribute.AttributeInstance instance = player.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
		if (instance == null) return;
		instance.removeModifier(landSpeedKey);
		if (multiplier == 1.0) {
			landSlowed.remove(player.getUniqueId());
			return;
		}
		instance.addTransientModifier(new org.bukkit.attribute.AttributeModifier(landSpeedKey, multiplier - 1,
				org.bukkit.attribute.AttributeModifier.Operation.MULTIPLY_SCALAR_1, org.bukkit.inventory.EquipmentSlotGroup.ANY));
		landSlowed.put(player.getUniqueId(), multiplier);
	}

	/**
	 * Held in place: a creaking someone is looking at, a villager someone is trading with (trading.freeze-villager). The
	 * freeze is a movement attribute, so a borrowed dash, hop or swoop that sets the velocity would walk straight through it.
	 */
	private boolean heldStill(Player player) {
		if (frozen.contains(player.getUniqueId())) return true;
		org.bukkit.attribute.AttributeInstance speed = player.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
		// the arms' holds too (the spear's pin, the whip's snare: ArmHold puts these modifiers on the walking speed): a pinned
		// player with a lent camel, rabbit, goat or enderman got out by a dash, a hop, a ram or a teleport (rig B1-08)
		return speed != null && (speed.getModifier(tradeFreezeKey) != null || speed.getModifier(spearPinKey) != null || speed.getModifier(whipSnareKey) != null);
	}

	/**
	 * The fly speed of a morph that flies: nothing while it is held still by being watched (a creaking's freeze, rig B1-12) or hanging
	 * from a ceiling (a bat's perch), both of which hold the player by flying at zero speed; otherwise the configured one. Every place
	 * that puts the speed back goes through this, so one hold ending cannot undo the other.
	 */
	static float flierSpeed(boolean frozen, boolean perched, float normal) {
		return frozen || perched ? 0F : normal;
	}

	private void setFrozen(Player player, boolean on) {
		if (on == frozen.contains(player.getUniqueId())) return;
		for (org.bukkit.attribute.Attribute attribute : List.of(org.bukkit.attribute.Attribute.MOVEMENT_SPEED, org.bukkit.attribute.Attribute.JUMP_STRENGTH)) {
			org.bukkit.attribute.AttributeInstance instance = player.getAttribute(attribute);
			if (instance == null) continue;
			instance.removeModifier(frozenKey);
			if (on) {
				instance.addTransientModifier(new org.bukkit.attribute.AttributeModifier(frozenKey, -1,
						org.bukkit.attribute.AttributeModifier.Operation.MULTIPLY_SCALAR_1, org.bukkit.inventory.EquipmentSlotGroup.ANY));
			}
		}
		if (on) {
			frozen.add(player.getUniqueId());
			// a flier in the air flies by its own speed, which the attributes above do not touch (rig B1-12)
			if (grantedFlight.contains(player.getUniqueId())) flySpeed(player, 0F);
			player.getWorld().playSound(player.getLocation(), Sound.ENTITY_CREAKING_FREEZE, 1F, 1F);
		} else {
			frozen.remove(player.getUniqueId());
			// a perched bat is held by the same zero speed: unwatched, it stays held
			if (grantedFlight.contains(player.getUniqueId())) flySpeed(player, flierSpeed(false, isPerched(player), flySpeed));
		}
	}

	/**
	 * Axolotls, dolphins and nautiluses dry out away from water (rain counts for the first two). The air bar shows how
	 * long you have left; once it is empty you take drying-out damage every second.
	 */
	private void tickDryOut(Player player, String type, int limit, boolean showAir) {
		UUID id = player.getUniqueId();
		boolean wet = player.isInWater() || player.isInBubbleColumn() || (rainKeepsWet.contains(type) && player.isInRain());
		if (wet || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
			dryTicks.remove(id);
			return;
		}
		int t = dryTicks.merge(id, 1, Integer::sum);
		int max = player.getMaximumAir();
		// vanilla adds 4 air per tick out of water right after this, so set 4 less to show the real time left
		if (showAir) player.setRemainingAir(Math.max(0, max - (int) ((long) max * t / limit) - 4));
		if (t >= limit && (t - limit) % 20 == 0) player.damage(2.0, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.DRY_OUT).build());
	}

	/** Dolphins hold their breath for minutes, not seconds: the air bar drains at the dolphin's pace, then you drown. */
	private void tickBreathHold(Player player, int limit) {
		UUID id = player.getUniqueId();
		if (!player.isUnderWater()) {
			underwaterTicks.remove(id);
			return;
		}
		int t = underwaterTicks.merge(id, 1, Integer::sum);
		if (t < limit) {
			int max = player.getMaximumAir();
			player.setRemainingAir(Math.max(1, max - (int) ((long) max * t / limit)));
		}
	}

	/** Ticks in a row out of water (fish). */
	private int ticksOnLand(Player player) {
		if (player.isInWater() || player.isInBubbleColumn()) {
			fishOnLand.remove(player.getUniqueId());
			return 0;
		}
		return fishOnLand.merge(player.getUniqueId(), 1, Integer::sum);
	}

	/**
	 * Kills the player as their mob's own end (creeper blast, spent bee) without a huge hit, which would break their
	 * armour before the death drop. A totem of undying in either hand still saves them.
	 */
	private void killSelf(Player player, String deathKey) {
		if (useTotem(player)) return;
		deathMessages.put(player.getUniqueId(), deathKey);
		player.setHealth(0);
	}

	private static boolean useTotem(Player player) {
		org.bukkit.inventory.PlayerInventory inventory = player.getInventory();
		for (org.bukkit.inventory.EquipmentSlot hand : new org.bukkit.inventory.EquipmentSlot[] {org.bukkit.inventory.EquipmentSlot.HAND,
				org.bukkit.inventory.EquipmentSlot.OFF_HAND}) {
			org.bukkit.inventory.ItemStack item = inventory.getItem(hand);
			if (item.getType() != org.bukkit.Material.TOTEM_OF_UNDYING) continue;
			item.setAmount(item.getAmount() - 1);
			inventory.setItem(hand, item.getAmount() > 0 ? item : null);
			player.setHealth(1);
			for (PotionEffect effect : player.getActivePotionEffects()) player.removePotionEffect(effect.getType());
			player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));
			player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));
			player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0));
			player.playEffect(org.bukkit.EntityEffect.PROTECTED_FROM_DEATH);
			return true;
		}
		return false;
	}

	/** The death message for a self-inflicted mob death, once (PlayerDeathEvent). */
	public @Nullable String takeDeathMessage(Player player) {
		return deathMessages.remove(player.getUniqueId());
	}

	/** The block a sulfur cube form holds is explosive (TNT). */
	public boolean explosiveInside(MorphRef form) {
		return hasExplosiveInside(form);
	}

	/** Vanilla only lets a sulfur cube explode when the block it swallowed is in #sulfur_cube_archetype/explosive. */
	private static boolean hasExplosiveInside(MorphRef form) {
		Object equipment = parse(form.snbt()).get("equipment");
		if (!(equipment instanceof Map<?, ?> slots) || !(slots.get("body") instanceof Map<?, ?> body) || !(body.get("id") instanceof String id)) return false;
		org.bukkit.Material material = org.bukkit.Material.matchMaterial(id);
		Tag<org.bukkit.Material> explosive = org.bukkit.Bukkit.getTag(Tag.REGISTRY_ITEMS, NamespacedKey.minecraft("sulfur_cube_archetype/explosive"), org.bukkit.Material.class);
		return material != null && explosive != null && explosive.isTagged(material);
	}

	private static Map<String, Object> parse(String snbt) {
		if (snbt == null || snbt.isBlank()) return Map.of();
		try {
			return dev.morphreborn.paper.nbt.Snbt.parseCompound(snbt);
		} catch (IllegalArgumentException e) {
			return Map.of();
		}
	}

	private boolean isSheared(Player player, MorphRef form) {
		Object value = parse(plugin.morphs().overlay(player)).get("Sheared");
		if (value == null) value = parse(form.snbt()).get("Sheared");
		return value instanceof Number n ? n.intValue() != 0 : Boolean.TRUE.equals(value);
	}

	/** A borrowed sheep's wool: what shears and grass left of it, else what the form was captured with. */
	private boolean isLentSheared(Player player, MorphRef lent) {
		Map<MorphRef, Boolean> wool = lentWool.get(player.getUniqueId());
		Boolean known = wool == null ? null : wool.get(lent);
		if (known != null) return known;
		Object value = parse(lent.snbt()).get("Sheared");
		return value instanceof Number n ? n.intValue() != 0 : Boolean.TRUE.equals(value);
	}

	private void setLentWool(Player player, MorphRef lent, boolean sheared) {
		lentWool.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>()).put(lent, sheared);
	}

	private boolean isAdult(MorphRef form, org.bukkit.World world) {
		return !(plugin.templates().get(form, world) instanceof org.bukkit.entity.Ageable a) || a.isAdult();
	}

	/** Only a sheep form is asked its age and wool (a template is built for the question), anything else is not a candidate. */
	private ShearRules.Candidate shearCandidate(Player sheep, @Nullable MorphRef form, boolean lent, org.bukkit.World world) {
		if (form == null || !form.entityType().equals("minecraft:sheep")) return ShearRules.Candidate.NONE;
		return new ShearRules.Candidate(true, isAdult(form, world), lent ? isLentSheared(sheep, form) : isSheared(sheep, form));
	}

	/**
	 * Another player shears a sheep morph: wool of its colour drops and it stays bald until it eats grass. Either form of the
	 * combination counts (2026-11-17: "let either form count"), as milking does; a lamb refuses, as in vanilla.
	 */
	public boolean shearSheep(Player shearer, Player sheep) {
		if (!enabled || plugin.morphs().isTransitioning(sheep)) return false; // abilities off: milking refuses, so shearing does too
		MorphRef main = plugin.morphs().activeForm(sheep);
		MorphRef lent = plugin.borrowedHeads().borrowed(sheep);
		org.bukkit.World world = sheep.getWorld();
		ShearRules.Candidate asMain = shearCandidate(sheep, main, false, world);
		ShearRules.Candidate asLent = shearCandidate(sheep, lent, true, world);
		ShearRules.Pick pick = ShearRules.pick(asMain, asLent);
		if (pick == ShearRules.Pick.NONE) return false;
		MorphRef form = pick == ShearRules.Pick.MAIN ? main : lent;
		Object color = parse(form.snbt()).get("Color");
		org.bukkit.DyeColor dye = org.bukkit.DyeColor.getByWoolData((byte) (color instanceof Number n ? n.intValue() : 0));
		org.bukkit.Material wool = org.bukkit.Material.matchMaterial((dye == null ? org.bukkit.DyeColor.WHITE : dye).name() + "_WOOL");
		int count = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 4);
		if (wool != null) sheep.getWorld().dropItemNaturally(sheep.getLocation().add(0, 0.5, 0), new org.bukkit.inventory.ItemStack(wool, count));
		// the main body's wool is its look (every viewer sees it bald); a borrowed sheep's head is drawn from the base model, which has
		// no wool layer, so there is no look to change: only the state is kept, for the next shearing and for grass
		if (pick == ShearRules.Pick.MAIN) plugin.morphs().setOverlayKeys(sheep, SHEAR_KEYS, "{Sheared:1b}");
		else setLentWool(sheep, lent, true);
		sheep.getWorld().playSound(sheep.getLocation(), Sound.ENTITY_SHEEP_SHEAR, 1F, 1F);
		return true;
	}

	private boolean isWitch(Player player, String type) {
		ActiveAbility ability = actives.get(type);
		return ability != null && ability.name.equals("witch-potions") && !witchPotions.isEmpty();
	}

	/** True when the second input does something for this form (witch: pick the next potion). */
	public boolean hasAlternate(Player player) {
		List<String> forms = types(player);
		if (!enabled || forms.isEmpty()) return false;
		if (!rotation(forms).isEmpty()) return true; // steps through a multimorph's abilities
		for (String type : forms) {
			ActiveAbility ability = actives.get(type);
			if (ability == null) continue;
			return isWitch(player, type) || (ability.name.equals("explode") && ability.config.getBoolean("fakeout", true));
		}
		return false;
	}

	private WitchPotion currentWitchPotion(Player player) {
		return witchPotions.get(witchChoice.getOrDefault(player.getUniqueId(), 0) % witchPotions.size());
	}

	/** Action-bar indicator of the potion the witch will use next, and how to change it. */
	private void showWitchPotion(Player player) {
		WitchPotion next = currentWitchPotion(player);
		String name = next.potion().replace('_', ' ');
		name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
		String change = plugin.channel().isModded(player) ? "sneak + ability key" : "F";
		player.sendActionBar(Component.text("Next potion: ", NamedTextColor.GRAY)
				.append(Component.text((next.drink() ? "drink " : "throw ") + name, NamedTextColor.LIGHT_PURPLE))
				.append(Component.text("   " + change + " to change", NamedTextColor.DARK_GRAY)));
	}

	/** A guardian beam from the player's eyes to the target, turning from cyan to yellow as it charges. */
	private static void drawBeam(Player player, LivingEntity target, double charge) {
		Location eye = player.getEyeLocation();
		Location end = target.getLocation().add(0, target.getHeight() / 2, 0);
		Vector step = end.toVector().subtract(eye.toVector());
		double length = step.length();
		step.normalize().multiply(0.4);
		org.bukkit.Color color = org.bukkit.Color.fromRGB((int) (120 + 135 * charge), (int) (230 + 10 * charge), (int) (255 - 135 * charge));
		Particle.DustOptions dust = new Particle.DustOptions(color, 0.8F);
		Location at = eye.clone();
		for (double d = 0; d < length; d += 0.4) {
			player.getWorld().spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, dust);
			at.add(step);
		}
	}

	/** Nearest chest (not a copper one) with room for {@code stack}, preferring chests that already hold that item. */
	private static org.bukkit.inventory.@Nullable Inventory sortTarget(Block from, org.bukkit.inventory.ItemStack stack, int radius) {
		org.bukkit.inventory.Inventory best = null;
		boolean bestMatches = false;
		double bestDistance = Double.MAX_VALUE;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -radius; dy <= radius; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					Block b = from.getRelative(dx, dy, dz);
					if (b.getType() != org.bukkit.Material.CHEST && b.getType() != org.bukkit.Material.TRAPPED_CHEST) continue;
					if (!(b.getState() instanceof org.bukkit.block.Container container)) continue;
					org.bukkit.inventory.Inventory inventory = container.getInventory();
					boolean room = inventory.firstEmpty() >= 0;
					for (org.bukkit.inventory.ItemStack item : inventory.getContents()) {
						if (room) break;
						room = item != null && item.isSimilar(stack) && item.getAmount() < item.getMaxStackSize();
					}
					if (!room) continue;
					boolean matches = inventory.contains(stack.getType());
					double distance = dx * dx + dy * dy + dz * dz;
					if ((matches && !bestMatches) || (matches == bestMatches && distance < bestDistance)) {
						best = inventory;
						bestMatches = matches;
						bestDistance = distance;
					}
				}
			}
		}
		return best;
	}

	// ------------------------------------------------------------------ queries used by the listener

	public void onSneak(Player player, boolean sneaking) {
		if (sneaking) {
			sneakingSince.put(player.getUniqueId(), tick);
		} else {
			sneakingSince.remove(player.getUniqueId());
		}
	}

	/** Blocks the morph moves through without being slowed (server side; the client mod does its own side). */
	public boolean passesThrough(Player player, org.bukkit.Material block) {
		List<String> forms = types(player);
		if (!enabled || forms.isEmpty()) return false;
		return switch (block) {
			case COBWEB -> anyHas(player, forms, webWalk, "web-walk");
			case SWEET_BERRY_BUSH -> anyHas(player, forms, berryWalk, "berry-walk");
			case POWDER_SNOW -> anyHas(player, forms, powderSnowWalk, "powder-snow-walk");
			default -> false;
		};
	}

	public boolean isUndead(Player player) {
		return enabled && any(types(player), undead);
	}

	/** Share of magic damage the morph shrugs off (witches take 85% less); multimorphed, the best of the forms. */
	public double magicResistance(Player player) {
		if (!enabled || !allowed(player, "magic-resistance")) return 0;
		double best = 0;
		for (String t : types(player)) best = Math.max(best, magicResistance.getOrDefault(t, 0.0));
		return best;
	}

	public boolean beginInverting(Player player) {
		return inverting.add(player.getUniqueId());
	}

	public void endInverting(Player player) {
		inverting.remove(player.getUniqueId());
	}

	public boolean isInverting(Player player) {
		return inverting.contains(player.getUniqueId());
	}

	public boolean hurtBySnowballs(Player player) {
		return enabled && any(types(player), hurtBySnowballs);
	}

	private void tagAbilityProjectile(Entity projectile) {
		projectile.getPersistentDataContainer().set(abilityProjectileKey, org.bukkit.persistence.PersistentDataType.BOOLEAN, true);
	}

	public boolean isAbilityProjectile(@Nullable Entity entity) {
		return entity != null && entity.getPersistentDataContainer().has(abilityProjectileKey);
	}

	/**
	 * Frog, goat and breeze morphs: jumping while sneaking is the mob's long jump. Vanilla long jumps leave the ground
	 * at 65-80 degrees (LongJumpToRandomPos); looking further up gives the steeper, higher arc.
	 */
	public boolean leap(Player player) {
		String type = type(player);
		if (!enabled || type == null || !player.isSneaking() || player.isFlying() || !player.hasPermission("morphreborn.ability")) return false;
		// a shulker body clinging to a block stays put: a borrowed frog or goat can't launch it
		if (!allowed(player, "leap") || plugin.shulkerLock().isLocked(player)) return false;
		// held still (watched, pinned, trading) a leap sets the velocity straight through the frozen speed (rig B1-12)
		if (heldStill(player)) return false;
		Leap leap = null;
		for (String t : types(player)) { // the main form's leap, or the borrowed one's
			leap = leaps.get(t);
			if (leap != null) break;
		}
		if (leap == null || tick < leapReady.getOrDefault(player.getUniqueId(), 0L)) return false;
		leapReady.put(player.getUniqueId(), tick + leap.cooldownTicks());
		double velocity = leap.velocity();
		if (leap.charged()) {
			Long since = sneakingSince.get(player.getUniqueId());
			double charge = since == null ? 0 : Math.clamp((tick - since) / 20.0, 0, 1);
			velocity = leap.minVelocity() + (leap.velocity() - leap.minVelocity()) * charge;
		}
		double lookUp = Math.clamp(-player.getLocation().getPitch(), 0, 90) / 90.0;
		double angle = Math.toRadians(65 + 15 * lookUp);
		double yaw = Math.toRadians(player.getLocation().getYaw());
		Vector launch = new Vector(-Math.sin(yaw) * Math.cos(angle), Math.sin(angle), Math.cos(yaw) * Math.cos(angle)).multiply(velocity);
		player.setVelocity(launch);
		player.setFallDistance(0);
		if (leap.sound() != null) player.getWorld().playSound(player.getLocation(), leap.sound(), 1F, 1F);
		plugin.channel().broadcastAnimation(player, "leap");
		return true;
	}

	public boolean ignoresDamage(Player player, org.bukkit.event.entity.EntityDamageEvent.DamageCause cause) {
		if (explosionImmune.contains(player.getUniqueId())
				&& (cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_EXPLOSION || cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.BLOCK_EXPLOSION)) {
			return true;
		}
		if (!enabled) return false;
		for (String type : types(player)) {
			boolean ignored = switch (cause) {
				// Flyers never take fall damage: with Flight they fly, and without it they glide down.
				case FALL -> has(player, type, fallImmunity, "fall-immunity") || flight.contains(type) || has(player, type, slowFall, "slow-fall");
				case FIRE, FIRE_TICK, LAVA, HOT_FLOOR, CAMPFIRE -> has(player, type, fireImmunity, "fire-immunity");
				case DROWNING -> breathesWater(player, type);
				case FREEZE -> has(player, type, freezeImmunity, "freeze-immunity");
				default -> false;
			};
			if (ignored) return true;
		}
		return false;
	}

	/**
	 * A projectile is about to hit {@code player}. Breeze morphs bat it back (vanilla deflects_projectiles); enderman
	 * morphs teleport out of its way, like a real enderman dodging arrows. Returns true if the hit should not happen.
	 */
	public boolean dodgeProjectile(Player player, Entity projectile) {
		List<String> forms = types(player);
		if (forms.isEmpty() || !enabled) return false;
		if (anyHas(player, forms, deflectProjectiles, "deflect-projectiles")) {
			projectile.setVelocity(projectile.getVelocity().multiply(-0.5));
			return true;
		}
		if (anyHas(player, forms, teleportFromProjectiles, "teleport-from-projectiles")) return teleportRandomly(player);
		return false;
	}

	/** Enderman-style random teleport: up to 16 tries at a spot within 16 blocks with ground below and room to stand. */
	/** Nothing {@code blocked} where a body of this size would stand with its feet centred on {@code feet}. */
	private static boolean hasRoom(Block feet, double width, double height, java.util.function.Predicate<Block> blocked) {
		if (BodyRoom.cells(width, height) > BodyRoom.MAX_SCAN) return false; // a body this big has no room anywhere worth reading thousands of blocks for
		int[] span = BodyRoom.span(width);
		int high = BodyRoom.blocksHigh(height);
		for (int dy = 0; dy < high; dy++) {
			for (int dx = span[0]; dx <= span[1]; dx++) {
				for (int dz = span[0]; dz <= span[1]; dz++) {
					if (blocked.test(feet.getRelative(dx, dy, dz))) return false;
				}
			}
		}
		return true;
	}

	private boolean teleportRandomly(Player player) {
		if (heldStill(player)) return false; // as the teleport ability: a held body stays
		java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
		Location from = player.getLocation();
		for (int attempt = 0; attempt < 16; attempt++) {
			int x = from.getBlockX() + random.nextInt(-16, 17), z = from.getBlockZ() + random.nextInt(-16, 17);
			// never load a chunk for a try (getType would, on the main thread): an unloaded one is just another miss
			if (!from.getWorld().isChunkLoaded(x >> 4, z >> 4)) continue;
			Block feet = from.getWorld().getBlockAt(x, from.getBlockY() + random.nextInt(-8, 9), z);
			while (feet.getY() > from.getWorld().getMinHeight() && feet.getRelative(BlockFace.DOWN).isPassable()) feet = feet.getRelative(BlockFace.DOWN);
			Block ground = feet.getRelative(BlockFace.DOWN);
			if (!ground.getType().isSolid() || ground.isLiquid()) continue;
			if (!hasRoom(feet, player.getWidth(), player.getHeight(), b -> !b.isPassable() || b.isLiquid())) continue;
			Location to = feet.getLocation().add(0.5, 0, 0.5);
			to.setYaw(from.getYaw());
			to.setPitch(from.getPitch());
			from.getWorld().spawnParticle(Particle.PORTAL, from.clone().add(0, 1, 0), 40, 0.3, 0.6, 0.3, 0.2);
			player.teleport(to);
			from.getWorld().playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 1F, 1F);
			player.getWorld().playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 1F, 1F);
			return true;
		}
		return false;
	}

	/** Sweet berry bushes don't hurt foxes and bees. */
	public boolean ignoresBerryBush(Player player) {
		return enabled && anyHas(player, types(player), berryImmune, "berry-bush-immunity");
	}

	public boolean extinguish(Player player) {
		return anyHas(player, types(player), fireImmunity, "fire-immunity");
	}

	public boolean blocksEffect(Player player, PotionEffectType effect) {
		if (!enabled) return false;
		for (String t : types(player)) if (effectImmunity.getOrDefault(t, Set.of()).contains(effect)) return true;
		return false;
	}

	public void onMeleeHit(Player attacker, LivingEntity victim) {
		List<String> forms = types(attacker);
		provoked.computeIfAbsent(attacker.getUniqueId(), k -> new HashSet<>()).add(victim.getUniqueId());
		if (!enabled || forms.isEmpty()) return;
		if (attackEffectsUnarmedOnly && !attacker.getInventory().getItemInMainHand().isEmpty()) return;
		if (!allowed(attacker, "attack-effects")) return;
		for (String t : forms) {
			for (TimedEffect e : attackEffects.getOrDefault(t, List.of())) victim.addPotionEffect(new PotionEffect(e.type(), e.ticks(), e.amplifier()));
		}
	}

	/** You hit it (melee or projectile): it may fight back whatever you look like. */
	public void provoke(Player player, LivingEntity mob) {
		provoked.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>()).add(mob.getUniqueId());
	}

	public void onMeleeHurt(Player victim, LivingEntity attacker) {
		if (!enabled || !allowed(victim, "retaliate-effects")) return;
		for (String t : types(victim)) {
			for (TimedEffect e : retaliateEffects.getOrDefault(t, List.of())) attacker.addPotionEffect(new PotionEffect(e.type(), e.ticks(), e.amplifier()));
		}
	}

	/** Should {@code mob} be stopped from targeting {@code player}? */
	public boolean shouldIgnore(LivingEntity mob, Player player) {
		String mobType = mob.getType().getKey().toString();
		// Borrowed Scent (a paid passive, so no matter the abilities switch): creatures of the kind lent to you leave you alone,
		// dropping a target they already have and ceasing to hit, until you strike them (fix round, 2026-11-17)
		MorphMakeup makeup = makeup(player);
		if (makeup.scentSpares(mobType) && plugin.survival().active() && plugin.passives().scentActive(player)) {
			return !provoked.getOrDefault(player.getUniqueId(), Set.of()).contains(mob.getUniqueId());
		}
		List<String> forms = types(player);
		if (!enabled || forms.isEmpty()) return false;
		if (allowed(player, "intimidate")) {
			for (String t : forms) if (intimidate.getOrDefault(t, Set.of()).contains(mobType)) return true;
		}
		Long deadUntil = playingDead.get(player.getUniqueId());
		if (deadUntil != null && tick < deadUntil) return true;
		// Mobs seeing a morph as the creature it looks like (targeting below, or the simpler hostile-camouflage) is a
		// bought trait in Survival; until then they see through the disguise and treat you as a player.
		if (!allowed(player, "hostile-camouflage")) return false;
		// Camouflage is for mobs, Borrowed Scent for you (2026-11-17): a body made only of creatures is camouflaged
		// with any of them counting (a zombie lent to a cow still does); one with you in it (your own body, or your own head on a
		// creature) never is in Survival, where Borrowed Scent spares the creature's kind instead (above). Classic has no such
		// passive, so there that body keeps Camouflage rather than going unprotected.
		if (!makeup.camouflages(plugin.survival().active())) return false;
		if (targetingEnabled) {
			if (alwaysHostile.contains(mobType)) return false;
			if (provoked.getOrDefault(player.getUniqueId(), Set.of()).contains(mob.getUniqueId())) return false;
			Set<String> prey = attacks.get(mobType);
			if (prey == null) return false;
			for (String creature : makeup.creatures()) if (prey.contains(creature)) return false; // prey if any form is
			return true;
		}
		if (!hostileCamouflage || !(mob instanceof org.bukkit.entity.Enemy)) return false;
		boolean hostileForm = false;
		for (MorphRef form : new MorphRef[] {plugin.morphs().activeForm(player), plugin.borrowedHeads().borrowed(player)}) {
			if (form != null && !form.isPlayer() && plugin.templates().get(form, player.getWorld()) instanceof org.bukkit.entity.Enemy) hostileForm = true;
		}
		if (!hostileForm) return false;
		return !provoked.getOrDefault(player.getUniqueId(), Set.of()).contains(mob.getUniqueId());
	}

	public boolean isMilkable(Player player) {
		return milkingForm(player) != null;
	}

	/** The form (main first) that gives milk: a cow, goat or mooshroom that has grown up, as vanilla refuses a calf. */
	public @Nullable String milkingForm(Player player) {
		return milkingForm(player, null);
	}

	/** As above, but only a form of the {@code only} type (a bowl asks for a mooshroom, whichever form comes first). */
	public @Nullable String milkingForm(Player player, @Nullable String only) {
		if (!enabled || plugin.morphs().isTransitioning(player)) return null;
		MorphRef main = plugin.morphs().activeForm(player);
		MorphRef lent = plugin.borrowedHeads().borrowed(player);
		for (MorphRef form : new MorphRef[] {main, lent}) {
			if (form == null || form.isPlayer() || !milkable.contains(form.entityType()) || (only != null && !only.equals(form.entityType()))) continue;
			if (plugin.templates().get(form, player.getWorld()) instanceof org.bukkit.entity.Ageable a && !a.isAdult()) continue;
			return form.entityType();
		}
		return null;
	}

	public boolean canCarryBlocks(Player player) {
		String type = type(player);
		return enabled && type != null && endermanCarry.contains(type) && player.hasPermission("morphreborn.ability")
				&& plugin.mode().cannotUseAbility(player) == null;
	}

	// ------------------------------------------------------------------ bat perching

	/** A bat hanging from a ceiling: the block, and how the player was moving before, to put back on letting go. */
	private static final class Perch {
		final Block ceiling;
		final boolean allowFlight;
		final boolean flying;
		final float flySpeed;
		/** Movement keys held right now; a key only lets go when it is newly pressed. */
		org.bukkit.Input keys;

		Perch(Block ceiling, Player p, float freeSpeed) {
			this.ceiling = ceiling;
			this.allowFlight = p.getAllowFlight();
			this.flying = p.isFlying();
			this.flySpeed = freeSpeed;
			this.keys = p.getCurrentInput();
		}
	}

	private final Map<UUID, Perch> perched = new HashMap<>();

	/** Vanilla bats rest under full blocks; any block whose underside is a full face will do. */
	private static boolean canHangFrom(Block block) {
		return block.getBlockData().isFaceSturdy(BlockFace.DOWN, org.bukkit.block.BlockSupport.FULL);
	}

	/** The block right above the player's head, within {@code reach} blocks, if it can be hung from. */
	private static @Nullable Block ceilingAbove(Player p, double reach) {
		Location top = p.getLocation().add(0, p.getHeight() - 0.05, 0);
		RayTraceResult hit = p.getWorld().rayTraceBlocks(top, new Vector(0, 1, 0), reach + 0.05, org.bukkit.FluidCollisionMode.NEVER, true);
		if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() != BlockFace.DOWN) return null;
		return canHangFrom(hit.getHitBlock()) ? hit.getHitBlock() : null;
	}

	/**
	 * Hang upside down under {@code ceiling} like a resting bat (Bat.isResting): the top of the body touches the
	 * block (vanilla puts a resting bat at floor(y) + 1 - height) and the player is held there by flying at zero speed,
	 * so nothing fights gravity. Everyone sees the bat's resting pose through the BatFlags overlay.
	 */
	private void perch(Player p, Block ceiling) {
		// what it gives back on letting go is the speed it flies at when free: a watched flier is at zero now, and that is not it
		float free = frozen.contains(p.getUniqueId()) && grantedFlight.contains(p.getUniqueId()) ? flySpeed : p.getFlySpeed();
		perched.put(p.getUniqueId(), new Perch(ceiling, p, free));
		Location at = p.getLocation();
		at.setY(ceiling.getY() - p.getHeight());
		p.teleport(at);
		p.setAllowFlight(true);
		p.setFlying(true);
		p.setFlySpeed(0F);
		p.setVelocity(new Vector());
		p.setFallDistance(0);
		plugin.morphs().setOverlayKeys(p, BAT_KEYS, "{BatFlags:1b}");
	}

	/**
	 * Let go: back to flying or standing, exactly as before perching (a standing bat drops to the ground).
	 * {@code showPose} is false when the form is changing anyway, which resets the look by itself.
	 */
	public void unperch(Player p, boolean showPose) {
		Perch was = perched.remove(p.getUniqueId());
		if (was == null) return;
		// still watched: it lets go of the ceiling and stays held by the freeze
		p.setFlySpeed(flierSpeed(frozen.contains(p.getUniqueId()) && grantedFlight.contains(p.getUniqueId()), false, was.flySpeed));
		p.setAllowFlight(was.allowFlight);
		p.setFlying(was.allowFlight && was.flying);
		if (showPose) {
			plugin.morphs().setOverlayKeys(p, BAT_KEYS, "");
			p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 0.3F, 1F);
		}
	}

	public boolean isPerched(Player p) {
		return perched.containsKey(p.getUniqueId());
	}

	/** A perched bat lets go when a movement key or jump is newly pressed (sneak stays free for sneak + swap hands). */
	public void perchInput(Player p, org.bukkit.Input keys) {
		Perch perch = perched.get(p.getUniqueId());
		if (perch == null) return;
		org.bukkit.Input held = perch.keys;
		perch.keys = keys;
		boolean pressed = (keys.isForward() && !held.isForward()) || (keys.isBackward() && !held.isBackward()) || (keys.isLeft() && !held.isLeft())
				|| (keys.isRight() && !held.isRight()) || (keys.isJump() && !held.isJump());
		if (pressed) unperch(p, true);
	}

	/** Like a vanilla bat, a perched one lets go when the block above stops being solid, or when it is moved off it. */
	private void tickPerched() {
		for (Map.Entry<UUID, Perch> e : List.copyOf(perched.entrySet())) {
			Player p = plugin.getServer().getPlayer(e.getKey());
			if (p == null) {
				perched.remove(e.getKey());
				continue;
			}
			Block ceiling = e.getValue().ceiling;
			Location at = p.getLocation();
			double dx = at.getX() - (ceiling.getX() + 0.5), dz = at.getZ() - (ceiling.getZ() + 0.5);
			boolean moved = p.getWorld() != ceiling.getWorld() || dx * dx + dz * dz > 1.0 || Math.abs(at.getY() + p.getHeight() - ceiling.getY()) > 0.5;
			if (moved || !p.isFlying() || !canHangFrom(ceiling)) unperch(p, true);
		}
	}

	/** Facing direction along the ground. Built from the yaw, so looking straight up or down still has one. */
	private static Vector flat(Player p) {
		double yaw = Math.toRadians(p.getLocation().getYaw());
		return new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
	}

	// ------------------------------------------------------------------ chicken fall

	/**
	 * Chicken: falls slowly with full control sideways. The client mod runs vanilla's own chicken physics (falling
	 * speed x0.6 every tick, Chicken.aiStep). Without it the server can only send a whole new velocity, and the server
	 * never knows how fast the player is going sideways, so capping the fall that way stopped players dead mid-jump.
	 * Instead they get an invisible Slow Falling effect while in the air: the player's own game slows the fall and
	 * leaves sideways movement alone. It is close to a chicken on short drops and quicker on long ones.
	 */
	/**
	 * Slow fall for players without the mod (the mod's client does it itself, Wire.TRAIT_SLOW_FALL and
	 * TRAIT_GLIDE_ON_JUMP): a hidden Slow Falling while airborne and {@code wanted} (always for a chicken or a flier that
	 * can't fly yet; while jump is held for one that can, with hold-jump-glide).
	 */
	private void tickSlowFall(Player player, boolean wanted) {
		if (plugin.channel().isModded(player)) return; // the client does it (Wire.TRAIT_SLOW_FALL)
		PotionEffect current = player.getPotionEffect(PotionEffectType.SLOW_FALLING);
		boolean ours = current != null && isChickenFall(current);
		boolean airborne = wanted && !player.isOnGround() && !player.isFlying() && !player.isGliding() && !player.isInWater() && !player.isClimbing();
		if (airborne) {
			if (current == null || (ours && current.getDuration() < 10)) {
				player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, CHICKEN_FALL_TICKS, 0, true, false, false));
			}
		} else if (ours) {
			player.removePotionEffect(PotionEffectType.SLOW_FALLING);
		}
	}

	private static final int CHICKEN_FALL_TICKS = 40;

	/** The hidden effect tickSlowFall adds (a real Slow Falling potion shows its icon and is never touched). */
	private static boolean isChickenFall(PotionEffect effect) {
		return effect.isAmbient() && !effect.hasIcon() && !effect.hasParticles() && effect.getDuration() <= CHICKEN_FALL_TICKS;
	}

	// ------------------------------------------------------------------ active abilities

	/** Ability key (Fabric) or the vanilla trigger. */
	public void trigger(Player player) {
		trigger(player, false);
	}

	/**
	 * {@code alternate} is the second input (sneak + ability key with the mod, F without sneaking for unmodded
	 * players): it picks the witch's next potion instead of using it.
	 */
	public void trigger(Player player, boolean alternate) {
		if (perched.containsKey(player.getUniqueId())) {
			unperch(player, true); // letting go is never on cooldown
			return;
		}
		if (!enabled || !player.hasPermission("morphreborn.ability")) return;
		List<String> forms = types(player);
		if (forms.isEmpty()) return;
		List<Choice> rotation = rotation(forms);
		if (!rotation.isEmpty()) {
			String locked = plugin.mode().cannotUseAbility(player);
			if (locked != null) {
				player.sendActionBar(Component.text(locked, NamedTextColor.GRAY));
				return;
			}
			UUID id = player.getUniqueId();
			int index = Math.floorMod(abilityChoice.getOrDefault(id, 0), rotation.size());
			if (alternate) {
				index = (index + 1) % rotation.size();
				abilityChoice.put(id, index);
				showChoice(player, rotation.get(index));
				player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4F, 1.6F);
				return;
			}
			Choice choice = rotation.get(index);
			if (choice.potion() >= 0) witchChoice.put(id, choice.potion());
			fire(player, choice.ability(), choice.type(), choice.fakeout());
			return;
		}
		// one ability among the forms (the main form's first, except a dash by where you are, 2026-11-17): its own second
		// input, as for a single form
		String type = PlaceVariants.pick(forms, t -> {
			ActiveAbility a = actives.get(t);
			return a == null ? null : a.name;
		}, Map.of("dash", dashWaterOnly), player.isInWater());
		ActiveAbility ability = type == null ? null : actives.get(type);
		if (ability == null) return;
		String locked = plugin.mode().cannotUseAbility(player);
		if (locked != null) {
			player.sendActionBar(Component.text(locked, NamedTextColor.GRAY));
			return;
		}
		if (alternate && ability.name.equals("witch-potions")) {
			if (!witchPotions.isEmpty()) {
				witchChoice.merge(player.getUniqueId(), 1, (x, y) -> (x + y) % witchPotions.size());
				showWitchPotion(player);
				player.playSound(player.getLocation(), Sound.ITEM_BOTTLE_FILL, 0.6F, 1.4F);
			}
			return;
		}
		// The second input on an exploding mob is a fakeout: the same build-up, then nothing.
		boolean fakeout = alternate && ability.name.equals("explode");
		if (fakeout && !ability.config.getBoolean("fakeout", true)) return;
		fire(player, ability, type, fakeout);
	}

	/** Use {@code ability} (of the form {@code type}) unless it is cooling down, and start its cooldown. */
	private void fire(Player player, ActiveAbility ability, String type, boolean fakeout) {
		String cooldownKey = fakeout ? "explode-fakeout" : ability.name;
		long cooldownTicks = fakeout ? Math.round(ability.config.getDouble("fakeout-cooldown-seconds", 4) * 20) : ability.cooldownTicks;
		Map<String, Long> cds = cooldowns.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
		long ready = cds.getOrDefault(cooldownKey, 0L);
		if (tick < ready) {
			if (cooldownMessage) {
				player.sendActionBar(Component.text(String.format("Ability ready in %.1fs", (ready - tick) / 20.0), NamedTextColor.GRAY));
			}
			return;
		}
		if (fakeout ? fuse(player, true) : ability.run(player, type)) {
			cds.put(cooldownKey, tick + Math.round(cooldownTicks * plugin.mode().cooldownFactor(player)));
			if (!fakeout) plugin.channel().broadcastAnimation(player, ability.name);
			// A shulker body clinging to a block opens its shell for whatever it does, as a shulker opens to attack: the head
			// inside (its own, or the one it borrows) pops out to shoot, hiss or throw. Only a held shell opens.
			plugin.shulkerLock().openShell(player);
		}
	}

	/**
	 * One thing the ability key can do in a multimorph's rotation: an ability of one of its forms, a witch's potion
	 * ({@code potion}, else -1), or a creeper's fakeout.
	 */
	private record Choice(ActiveAbility ability, String type, int potion, boolean fakeout) {}

	/** The selected entry of each multimorphed player's ability rotation. */
	private final Map<UUID, Integer> abilityChoice = new HashMap<>();

	/**
	 * A multimorph's ability rotation, when two of its forms have different abilities (one ability keeps its own second
	 * input): every thing the ability key can do, the main form's first. The second input (sneak + ability key with the
	 * mod, F without) steps through it, the way it steps through a witch's potions.
	 */
	private List<Choice> rotation(List<String> forms) {
		if (forms.size() < 2) return List.of();
		List<Choice> out = new ArrayList<>();
		Set<String> names = new HashSet<>();
		for (String t : forms) {
			ActiveAbility ability = actives.get(t);
			if (ability == null || !names.add(ability.name)) continue;
			if (ability.name.equals("witch-potions")) {
				for (int i = 0; i < witchPotions.size(); i++) out.add(new Choice(ability, t, i, false));
			} else {
				out.add(new Choice(ability, t, -1, false));
				if (ability.name.equals("explode") && ability.config.getBoolean("fakeout", true)) out.add(new Choice(ability, t, -1, true));
			}
		}
		return names.size() < 2 ? List.of() : out;
	}

	/** The rotation's selected entry on the action bar, and how to change it. */
	private void showChoice(Player player, Choice choice) {
		String label;
		if (choice.potion() >= 0) {
			WitchPotion potion = witchPotions.get(choice.potion());
			label = (potion.drink() ? "drink " : "throw ") + potion.potion().replace('_', ' ');
		} else if (choice.fakeout()) {
			label = "fake explosion";
		} else {
			label = choice.ability().name.replace('-', ' ');
		}
		label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
		String change = plugin.channel().isModded(player) ? "sneak + ability key" : "F";
		player.sendActionBar(Component.text("Ability: ", NamedTextColor.GRAY).append(Component.text(label, NamedTextColor.LIGHT_PURPLE))
				.append(Component.text("   " + change + " to change", NamedTextColor.DARK_GRAY)));
	}

	/** The form among this player's that explodes (a creeper, an explosive sulfur cube), the main one first. */
	private @Nullable MorphRef explodingForm(Player p) {
		MorphRef main = plugin.morphs().activeForm(p);
		if (main == null || plugin.morphs().isTransitioning(p)) return null;
		return Blasters.pick(main, plugin.borrowedHeads().borrowed(p), this::canBlast);
	}

	private boolean explodes(String type) {
		ActiveAbility ability = actives.get(type);
		return ability != null && ability.name.equals("explode");
	}

	/** It explodes, and (a sulfur cube) holds an explosive block: a cube without one must not hide a borrowed creeper. */
	private boolean canBlast(MorphRef form) {
		return explodes(form.entityType()) && (!form.entityType().equals("minecraft:sulfur_cube") || hasExplosiveInside(form));
	}

	/**
	 * A creeper's or explosive sulfur cube's fuse: the hiss (and a creeper's swell) for everyone, then the explosion. A
	 * {@code fakeout} has the same build-up and then fizzles out: the creeper shrinks back to normal, nothing blows up,
	 * and a lightning charge is kept.
	 */
	private boolean fuse(Player p, boolean fakeout) {
		return fuse(p, fakeout, -1);
	}

	/**
	 * Vanilla primes a sulfur cube holding TNT from outside (SulfurCube.mobInteract, hurtServer): flint and steel or a
	 * fire charge, fire, a burning arrow, or a blast nearby ({@code imminent}: a short fuse, as PrimedTnt's). It isn't the
	 * cube's own choice, so there is no Survival unlock; it blows up as its ability does, and has its cooldown.
	 */
	public boolean ignite(Player p, boolean imminent) {
		if (!enabled || plugin.morphs().isTransitioning(p)) return false;
		// the cube may be the borrowed form (a zombie lending a TNT cube's body is lit the same way)
		MorphRef main = plugin.morphs().activeForm(p);
		MorphRef lent = plugin.borrowedHeads().borrowed(p);
		MorphRef form = Blasters.pick(main, lent, f -> f.entityType().equals("minecraft:sulfur_cube") && canBlast(f));
		if (form == null) return false;
		// A blast from outside has the ability's own cooldown, shared with its key: a borrowed cube's block is not spent (only a main
		// converted cube reverts), so without it an ally with flint and steel could relight it every half second
		ActiveAbility blast = actives.get(form.entityType());
		Map<String, Long> cds = cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
		if (blast != null && tick < cds.getOrDefault(blast.name, 0L)) return false;
		int ticks = imminent ? java.util.concurrent.ThreadLocalRandom.current().nextInt(Math.max(1, sulfurCubeFuseTicks / 4)) + sulfurCubeFuseTicks / 8
				: sulfurCubeFuseTicks;
		boolean lit = fuse(p, false, ticks, form);
		if (lit && blast != null) cds.put(blast.name, tick + ticks + Math.round(blast.cooldownTicks * plugin.mode().cooldownFactor(p)));
		return lit;
	}

	/** A creeper's or sulfur cube's fuse is burning (a primed sulfur cube can't be hurt, as in vanilla). */
	public boolean isFusing(Player p) {
		return fusing.contains(p.getUniqueId());
	}

	/** {@code fuseTicks} below 0: the form's own fuse. */
	private boolean fuse(Player p, boolean fakeout, int fuseTicks) {
		return fuse(p, fakeout, fuseTicks, null);
	}

	/** {@code lit}: the form that was lit from outside (a borrowed TNT cube under a creeper body), else the one that explodes. */
	private boolean fuse(Player p, boolean fakeout, int fuseTicks, @Nullable MorphRef lit) {
		MorphRef creeperForm = lit != null ? lit : explodingForm(p);
		if (creeperForm == null) {
			MorphRef main = plugin.morphs().activeForm(p);
			if (main != null && !plugin.morphs().isTransitioning(p) && main.entityType().equals("minecraft:sulfur_cube")) {
				p.sendActionBar(Component.text("Only a sulfur cube with an explosive block inside can explode.", NamedTextColor.GRAY));
			}
			return false;
		}
		ConfigurationSection config = actives.get(creeperForm.entityType()).config;
		boolean sulfur = creeperForm.entityType().equals("minecraft:sulfur_cube");
		// the swelling and the charge's aura are the creeper body's; a borrowed creeper only hisses and blows up
		boolean looks = !sulfur && creeperForm.equals(plugin.morphs().activeForm(p));
		if (!fusing.add(p.getUniqueId())) return false;
		if (looks) plugin.morphs().setOverlayKeys(p, CREEPER_KEYS, creeperLook(p, true));
		p.getWorld().playSound(p.getLocation(), sulfur ? Sound.ENTITY_TNT_PRIMED : Sound.ENTITY_CREEPER_PRIMED, 1F, sulfur ? 1F : 0.5F);
		plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
			fusing.remove(p.getUniqueId());
			// still made of the form that was lit (a lit borrowed cube need not be the one explodingForm names first)
			if (!p.isOnline() || p.isDead() || plugin.morphs().isTransitioning(p)
					|| !Blasters.isOneOf(creeperForm, plugin.morphs().activeForm(p), plugin.borrowedHeads().borrowed(p))) return;
			if (fakeout) {
				// Back to normal with a dud's fizzle; the charge (if any) stays for a real explosion.
				if (looks) plugin.morphs().setOverlayKeys(p, CREEPER_KEYS, creeperLook(p, false));
				Location chest = p.getLocation().add(0, p.getHeight() * 0.6, 0);
				p.getWorld().spawnParticle(Particle.SMOKE, chest, 12, 0.25, 0.25, 0.25, 0.02);
				p.getWorld().playSound(chest, Sound.BLOCK_FIRE_EXTINGUISH, 0.5F, 1.6F);
				return;
			}
			// A charged creeper's blast is twice as big (Creeper.explodeCreeper). A lightning charge is spent; a
			// charged-creeper morph (killed while charged) stays charged.
			boolean struck = !sulfur && charged.remove(p.getUniqueId());
			boolean wasCharged = struck || (!sulfur && isPoweredForm(creeperForm));
			if (looks) plugin.morphs().setOverlayKeys(p, CREEPER_KEYS, "");
			var settings = plugin.settings();
			float power = (float) (config.getDouble("power", 3) * (wasCharged ? config.getDouble("charged-multiplier", 2) : 1));
			// A borrowed creeper blows up with the body it is on: the blast grows with that body's size, the cube root of
			// its mass against the creeper's (the Survival weight chart), so a ghast's is huge and a chicken's a pop.
			MorphRef body = plugin.morphs().activeForm(p);
			if (body != null && !body.equals(creeperForm)) {
				LivingEntity bodyTemplate = plugin.templates().get(body, p.getWorld());
				LivingEntity bombTemplate = plugin.templates().get(creeperForm, p.getWorld());
				if (bodyTemplate != null && bombTemplate != null) {
					double ratio = plugin.survival().massOf(bodyTemplate) / Math.max(1, plugin.survival().massOf(bombTemplate));
					power = (float) Math.min(config.getDouble("multimorph-max-power", 16), power * Math.cbrt(ratio));
				}
			}
			// a creeper head in a held shulker's shell is out as it blows, however long the fuse burned (the shell opened as
			// it was lit, for open-seconds)
			plugin.shulkerLock().openShell(p);
			explosionImmune.add(p.getUniqueId());
			try {
				p.getWorld().createExplosion(p, p.getLocation(), power,
						config.getBoolean("set-fire", false) && settings.abilityFire.allows(p.getWorld()),
						settings.explosionBlockDamage.allows(p.getWorld()));
			} finally {
				explosionImmune.remove(p.getUniqueId());
			}
			if (sulfur) plugin.conversions().onExploded(p); // the block it was fed is spent
			// A real creeper is gone once it explodes: "<player> blew up". Killed outright rather than with a
			// huge hit, which would wear every armour piece down to nothing before the death drop.
			if (settings.creeperExplosionKillsUser && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)) {
				killSelf(p, "death.attack.explosion");
			}
		}, fuseTicks >= 0 ? fuseTicks : sulfur ? sulfurCubeFuseTicks : creeperFuseTicks);
		return true;
	}

	/**
	 * Abilities that throw the player's own body about. A shulker body clinging to a block stays put, so on one they do
	 * nothing (and cost no cooldown), as a borrowed frog's or goat's leap does.
	 */
	private static final Set<String> MOVES_BODY = Set.of("dash", "hop", "ram", "swoop", "roll");

	/**
	 * What the player aims at within {@code range}: a locked shulker's hidden helper stands for the player inside it.
	 * Never the player's own helper: with the eye above their closed shell (hitbox scaling off, a larger scale) a look
	 * down finds it first, and the bullet, toss or sting would land on themselves. The look is traced again past it,
	 * still stopped by blocks.
	 */
	private @Nullable Entity aimedAt(Player p, int range) {
		ShulkerLock lock = plugin.shulkerLock();
		Entity target = p.getTargetEntity(range);
		if (lock.isHelperOf(target, p)) {
			Location eye = p.getEyeLocation();
			RayTraceResult hit = p.getWorld().rayTrace(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true, 0,
					e -> e != p && e instanceof LivingEntity && !lock.isHelperOf(e, p) && !(e instanceof Player q && q.getGameMode() == GameMode.SPECTATOR));
			target = hit == null ? null : hit.getHitEntity();
		}
		return lock.standIn(target);
	}

	/** The box a {@link BodyReach} test reads from a Bukkit box. */
	private static BodyReach.Box reachBox(org.bukkit.util.BoundingBox b) {
		return new BodyReach.Box(b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ());
	}

	/** The player's body scale when it is bigger than a player's (a giant, a dragon, a ghast), else 0 for the plain eye trace. */
	private double bigBodyScale(Player p) {
		org.bukkit.attribute.AttributeInstance scale = p.getAttribute(org.bukkit.attribute.Attribute.SCALE);
		return scale != null && BodyReach.applies(scale.getValue()) ? scale.getValue() : 0;
	}

	/**
	 * {@link #aimedAt} for the short fixed-range abilities (sting, toss, scare, tongue). On a player-sized body this is
	 * exactly aimedAt. On a big one the eye is too high to meet a target at its feet inside the ability's range, so what
	 * the look points at also counts when it is within {@code range} of the body's hitbox edge: a giant can sting a cow
	 * at its ankle, and still reaches no further past its body than the ability always did (2026-11-17).
	 */
	private @Nullable Entity aimedAtNearBody(Player p, int range) {
		Entity hit = aimedAt(p, range);
		// A living hit ends it; a dropped item, orb, boat or frame the eye trace met first must not hide the cow behind it
		if (hit instanceof LivingEntity) return hit;
		if (bigBodyScale(p) == 0) return hit;
		ShulkerLock lock = plugin.shulkerLock();
		BodyReach.Box body = reachBox(p.getBoundingBox());
		Location eye = p.getEyeLocation();
		double length = BodyReach.traceLength(range, body, eye.getX(), eye.getY(), eye.getZ());
		// Ray size 0 like the plain getTargetEntity trace: reach is widened, aim picking is not (I wanted reach only)
		RayTraceResult ray = p.getWorld().rayTrace(eye, eye.getDirection(), length, FluidCollisionMode.NEVER, true, 0,
				e -> e != p && e instanceof LivingEntity && !lock.isHelperOf(e, p) && !(e instanceof Player q && q.getGameMode() == GameMode.SPECTATOR)
						&& BodyReach.within(body, reachBox(e.getBoundingBox()), range));
		// Nothing living found: hand back the plain trace's result so a non-living hit behaves exactly as before
		return ray == null ? hit : lock.standIn(ray.getHitEntity());
	}

	/** The block counterpart of {@link #aimedAtNearBody}, for infest and sort: the looked-at block, within {@code range} of the body's edge. */
	private @Nullable Block blockAimedNearBody(Player p, int range) {
		Block exact = p.getTargetBlockExact(range);
		if (exact != null) return exact;
		double scale = bigBodyScale(p);
		if (scale == 0) return null;
		BodyReach.Box body = reachBox(p.getBoundingBox());
		Location eye = p.getEyeLocation();
		RayTraceResult ray = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), BodyReach.traceLength(range, body, eye.getX(), eye.getY(), eye.getZ()),
				FluidCollisionMode.NEVER, true);
		Block block = ray == null ? null : ray.getHitBlock();
		return block != null && BodyReach.within(body, reachBox(block.getBoundingBox()), range) ? block : null;
	}

	/** A ray's living target, never the shooter's own helper, a helper standing for its player. */
	/** An untargeted shulker bullet's speed (blocks a tick) and how long it flies (ticks): about 32 blocks, the aim's reach. */
	private static final double SHULKER_BULLET_SPEED = 0.5;
	private static final long SHULKER_BULLET_TICKS = 64L;

	private @Nullable Entity rayTarget(Player p, Location eye, Vector dir, double range, double size) {
		ShulkerLock lock = plugin.shulkerLock();
		RayTraceResult hit = p.getWorld().rayTraceEntities(eye, dir, range, size, e -> e != p && e instanceof LivingEntity && !lock.isHelperOf(e, p));
		return hit == null ? null : lock.standIn(hit.getHitEntity());
	}

	private final class ActiveAbility {
		final String name;
		final long cooldownTicks;
		final ConfigurationSection config;

		ActiveAbility(String name, long cooldownTicks, ConfigurationSection config) {
			this.name = name;
			this.cooldownTicks = cooldownTicks;
			this.config = config;
		}

		/** Use it as the form {@code type} (main or borrowed) would. */
		boolean run(Player p, String type) {
			Location eye = p.getEyeLocation();
			Vector dir = eye.getDirection();
			if (MOVES_BODY.contains(name) && (plugin.shulkerLock().isLocked(p) || heldStill(p))) return false;
			switch (name) {
				case "explode" -> {
					return fuse(p, false);
				}
				case "teleport" -> {
					if (heldStill(p)) return false; // a pin or a freeze holds the body, a teleport is no way out of it (rig B1-08)
					Block target = p.getTargetBlockExact(config.getInt("range", 32));
					if (target == null) return false;
					Block above = target.getRelative(BlockFace.UP);
					// the whole body must fit: a ghast's head in the roof, a giant wedged through the walls, is not a teleport
					if (!hasRoom(above, p.getWidth(), p.getHeight(), Block::isSolid)) return false;
					Location from = p.getLocation();
					Location to = above.getLocation().add(0.5, 0, 0.5);
					to.setYaw(from.getYaw());
					to.setPitch(from.getPitch());
					p.getWorld().spawnParticle(Particle.PORTAL, from.add(0, 1, 0), 40, 0.3, 0.6, 0.3, 0.2);
					p.teleport(to);
					p.getWorld().playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 1F, 1F);
				}
				case "large-fireball" -> {
					LargeFireball f = p.launchProjectile(LargeFireball.class, dir.multiply(0.1));
					f.setYield((float) config.getDouble("yield", 1));
					tagAbilityProjectile(f);
					p.getWorld().playSound(eye, Sound.ENTITY_GHAST_SHOOT, 1F, 1F);
				}
				case "small-fireball" -> {
					tagAbilityProjectile(p.launchProjectile(SmallFireball.class, dir.multiply(0.1)));
					p.getWorld().playSound(eye, Sound.ENTITY_BLAZE_SHOOT, 1F, 1F);
				}
				case "wither-skull" -> {
					tagAbilityProjectile(p.launchProjectile(WitherSkull.class, dir.multiply(0.1)));
					p.getWorld().playSound(eye, Sound.ENTITY_WITHER_SHOOT, 1F, 1F);
				}
				case "dragon-fireball" -> {
					p.launchProjectile(DragonFireball.class, dir.multiply(0.1));
					p.getWorld().playSound(eye, Sound.ENTITY_ENDER_DRAGON_SHOOT, 1F, 1F);
				}
				case "snowball" -> {
					p.launchProjectile(Snowball.class);
					p.getWorld().playSound(eye, Sound.ENTITY_SNOW_GOLEM_SHOOT, 1F, 1F);
				}
				case "llama-spit" -> {
					p.launchProjectile(LlamaSpit.class);
					p.getWorld().playSound(eye, Sound.ENTITY_LLAMA_SPIT, 1F, 1F);
				}
				case "shulker-bullet" -> {
					ShulkerBullet bullet = p.launchProjectile(ShulkerBullet.class);
					Entity target = aimedAt(p, 32);
					if (target != null) {
						bullet.setTarget(target);
					} else {
						// Nothing in the crosshair: straight where you look. Vanilla's bullet with no target heads for the
						// block below it and falls (ShulkerBullet.selectNextMoveDirection, applyGravity), so every shot
						// that wasn't at a creature went into the ground (I saw it). Without gravity and a target
						// it keeps its speed; it is cleared after a while, as it would otherwise fly on forever.
						bullet.setGravity(false);
						bullet.setVelocity(dir.clone().normalize().multiply(SHULKER_BULLET_SPEED));
						plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
							if (bullet.isValid()) bullet.remove();
						}, SHULKER_BULLET_TICKS);
					}
					p.getWorld().playSound(eye, Sound.ENTITY_SHULKER_SHOOT, 1F, 1F);
				}
				case "wind-charge" -> p.launchProjectile(WindCharge.class);
				case "arrow" -> {
					Arrow arrow = p.launchProjectile(Arrow.class, dir.multiply(2.5));
					arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
					TimedEffect tipped = tippedArrows.get(type);
					if (tipped != null) arrow.addCustomEffect(new PotionEffect(tipped.type(), tipped.ticks(), tipped.amplifier()), true);
					p.getWorld().playSound(eye, Sound.ENTITY_SKELETON_SHOOT, 1F, 1F);
				}
				case "trident" -> {
					Trident trident = p.launchProjectile(Trident.class, dir.multiply(2.5));
					trident.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
					p.getWorld().playSound(eye, Sound.ENTITY_DROWNED_SHOOT, 1F, 1F);
				}
				case "evoker-fangs" -> {
					Vector step = flat(p);
					Location base = p.getLocation();
					for (int i = 1; i <= 8; i++) {
						Location at = base.clone().add(step.clone().multiply(i * 1.25));
						at.setY(base.getY());
						final int delay = i;
						p.getWorld().spawn(at, EvokerFangs.class, fangs -> {
							fangs.setOwner(p);
							fangs.setAttackDelay(delay * 2);
						});
					}
					p.getWorld().playSound(eye, Sound.ENTITY_EVOKER_CAST_SPELL, 1F, 1F);
				}
				case "sonic-boom" -> {
					double range = config.getDouble("range", 16);
					for (double d = 1; d <= range; d += 1) {
						p.getWorld().spawnParticle(Particle.SONIC_BOOM, eye.clone().add(dir.clone().multiply(d)), 1);
					}
					if (rayTarget(p, eye, dir, range, 1.0) instanceof LivingEntity target) {
						target.damage(config.getDouble("damage", 10), p);
						target.setVelocity(target.getVelocity().add(dir.clone().multiply(1.5).setY(0.5)));
					}
					p.getWorld().playSound(eye, Sound.ENTITY_WARDEN_SONIC_BOOM, 3F, 1F);
				}
				case "ram" -> {
					double strength = config.getDouble("strength", 1.6);
					p.setVelocity(dir.clone().setY(0.1).multiply(strength));
					plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
						for (Entity e : p.getNearbyEntities(1.5, 1.5, 1.5)) {
							// a locked shulker's helper is skipped: the player inside it is in reach and takes the hit itself
							// nor the mount a jockey sits on: it took 4 a press from its own rider until it died (rig B1-03)
							if (e instanceof LivingEntity le && e != p && e != p.getVehicle() && !plugin.shulkerLock().isHelper(e)) {
								le.damage(4, p);
								le.setVelocity(dir.clone().multiply(strength).setY(0.6));
							}
						}
					}, 4L);
					p.getWorld().playSound(eye, Sound.ENTITY_GOAT_RAM_IMPACT, 1F, 1F);
				}
				case "witch-potions" -> {
					if (witchPotions.isEmpty()) return false;
					WitchPotion choice = currentWitchPotion(p);
					org.bukkit.potion.PotionType potionType = org.bukkit.Registry.POTION.get(NamespacedKey.minecraft(choice.potion()));
					if (potionType == null) return false;
					if (choice.drink()) {
						for (PotionEffect e : potionType.getPotionEffects()) p.addPotionEffect(e);
						p.getWorld().playSound(eye, Sound.ENTITY_WITCH_DRINK, 1F, 1F);
					} else {
						org.bukkit.inventory.ItemStack potion = new org.bukkit.inventory.ItemStack(org.bukkit.Material.SPLASH_POTION);
						if (potion.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta meta) {
							meta.setBasePotionType(potionType);
							potion.setItemMeta(meta);
						}
						org.bukkit.entity.ThrownPotion thrown = p.launchProjectile(org.bukkit.entity.ThrownPotion.class);
						thrown.setItem(potion);
						p.getWorld().playSound(eye, Sound.ENTITY_WITCH_THROW, 1F, 1F);
					}
				}
				case "toss" -> {
					Entity target = aimedAtNearBody(p, 3);
					if (!(target instanceof LivingEntity victim)) return false;
					victim.damage(config.getDouble("damage", 7), p);
					victim.setVelocity(victim.getVelocity().add(new Vector(0, 0.9, 0)));
					p.swingMainHand();
					p.getWorld().playSound(eye, Sound.ENTITY_IRON_GOLEM_ATTACK, 1F, 1F);
				}
				case "guardian-beam" -> {
					// Like the real thing: the beam locks on and charges, and only hits if the target stays in sight.
					double range = config.getDouble("range", 16);
					if (!(rayTarget(p, eye, dir, range, 0.5) instanceof LivingEntity target)) return false;
					if (!beaming.add(p.getUniqueId())) return false;
					int chargeTicks = Math.max(2, (int) Math.round(config.getDouble("charge-seconds", 2) * 20));
					double damage = "minecraft:elder_guardian".equals(type) ? config.getDouble("elder-damage", 8) : config.getDouble("damage", 6);
					MorphRef form = plugin.morphs().activeForm(p);
					p.getWorld().playSound(eye, Sound.ENTITY_GUARDIAN_ATTACK, 1F, 1F);
					new org.bukkit.scheduler.BukkitRunnable() {
						int elapsed;

						@Override
						public void run() {
							elapsed += 2;
							boolean holding = p.isOnline() && !p.isDead() && target.isValid() && !target.isDead() && form != null && form.equals(plugin.morphs().activeForm(p))
									&& target.getWorld().equals(p.getWorld()) && p.getEyeLocation().distance(target.getLocation()) <= range + 2 && p.hasLineOfSight(target);
							if (!holding || !beaming.contains(p.getUniqueId())) {
								beaming.remove(p.getUniqueId());
								cancel();
								return;
							}
							drawBeam(p, target, Math.min(1, elapsed / (double) chargeTicks));
							if (elapsed >= chargeTicks) {
								target.damage(damage, p);
								beaming.remove(p.getUniqueId());
								cancel();
							}
						}
					}.runTaskTimer(plugin, 2L, 2L);
				}
				case "lay-egg" -> {
					p.getWorld().dropItemNaturally(p.getLocation(), new org.bukkit.inventory.ItemStack(org.bukkit.Material.EGG));
					p.getWorld().playSound(p.getLocation(), Sound.ENTITY_CHICKEN_EGG, 1F, 1F);
				}
				case "graze" -> {
					Block under = p.getLocation().subtract(0, 0.1, 0).getBlock();
					Block feet = p.getLocation().getBlock();
					if (feet.getType() == org.bukkit.Material.SHORT_GRASS || feet.getType() == org.bukkit.Material.TALL_GRASS) {
						feet.setType(org.bukkit.Material.AIR);
					} else if (under.getType() == org.bukkit.Material.GRASS_BLOCK) {
						under.setType(org.bukkit.Material.DIRT);
					} else {
						return false;
					}
					p.setFoodLevel(Math.min(20, p.getFoodLevel() + 2));
					p.setSaturation(Math.min(p.getFoodLevel(), p.getSaturation() + 1));
					MorphRef grazing = plugin.morphs().activeForm(p);
					if (grazing != null && grazing.entityType().equals("minecraft:sheep") && isSheared(p, grazing)) plugin.morphs().setOverlayKeys(p, SHEAR_KEYS, "{Sheared:0b}"); // eating grass regrows the wool
					MorphRef grazingLent = plugin.borrowedHeads().borrowed(p);
					if (grazingLent != null && grazingLent.entityType().equals("minecraft:sheep") && isLentSheared(p, grazingLent)) setLentWool(p, grazingLent, false); // a borrowed sheep's too
					p.getWorld().playSound(p.getLocation(), Sound.ENTITY_SHEEP_AMBIENT, 1F, 1F);
					p.getWorld().spawnParticle(Particle.BLOCK, p.getLocation(), 12, 0.3, 0.1, 0.3, org.bukkit.Material.GRASS_BLOCK.createBlockData());
				}
				case "web" -> {
					int range = config.getInt("range", 16);
					Block targetBlock = p.getTargetBlockExact(range);
					BlockFace face = p.getTargetBlockFace(range);
					if (targetBlock == null) return false;
					Block web = targetBlock.getRelative(face == null ? BlockFace.UP : face);
					if (!web.getType().isAir()) return false;
					web.setType(org.bukkit.Material.COBWEB);
					p.getWorld().playSound(web.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 1F, 1.4F);
					int seconds = config.getInt("duration", 0);
					if (seconds > 0) {
						plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
							if (web.getType() == org.bukkit.Material.COBWEB) web.setType(org.bukkit.Material.AIR);
						}, seconds * 20L);
					}
				}
				case "dash" -> {
					double strength = config.getDouble("strength", 1.4);
					if (dashWaterOnly.contains(type)) {
						if (!p.isInWater()) return false;
						p.setVelocity(dir.clone().normalize().multiply(strength));
						p.getWorld().playSound(p.getLocation(), Sound.ENTITY_DOLPHIN_SWIM, 1F, 1F);
						return true;
					}
					p.setVelocity(flat(p).multiply(strength).setY(0.35));
					p.getWorld().playSound(p.getLocation(), Sound.ENTITY_CAMEL_DASH, 1F, 1F);
				}
				case "hop" -> {
					// Rabbit: a long hop forward. Everyone sees the rabbit's own hop animation (DisguiseBridge /
					// MorphAnimations).
					p.setVelocity(flat(p).multiply(config.getDouble("strength", 1.4)).setY(config.getDouble("lift", 0.35)));
					p.getWorld().playSound(p.getLocation(), Sound.ENTITY_RABBIT_JUMP, 1F, 1F);
				}
				case "perch" -> {
					// a borrowed bat can't hang a shulker body that already clings to a block (both hold the player by flying)
					if (p.getGameMode() == GameMode.SPECTATOR || p.isInsideVehicle() || p.isGliding() || p.isSwimming() || plugin.shulkerLock().isLocked(p)) {
						return false;
					}
					Block ceiling = ceilingAbove(p, config.getDouble("reach", 2.5));
					if (ceiling == null) {
						p.sendActionBar(Component.text("Nothing solid above you to hang from.", NamedTextColor.GRAY));
						return false;
					}
					perch(p, ceiling);
				}
				case "ink" -> {
					double radius = config.getDouble("radius", 5);
					p.getWorld().spawnParticle(Particle.SQUID_INK, p.getLocation().add(0, 0.5, 0), 60, radius / 2, 0.8, radius / 2, 0.05);
					for (Entity e : p.getNearbyEntities(radius, radius, radius)) {
						if (e instanceof LivingEntity le) le.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 0));
					}
					p.getWorld().playSound(p.getLocation(), Sound.ENTITY_SQUID_SQUIRT, 1F, 1F);
				}
				case "tongue" -> {
					Entity target = aimedAtNearBody(p, config.getInt("range", 5));
					if (!(target instanceof LivingEntity prey) || target instanceof Player) return false;
					p.getWorld().playSound(p.getLocation(), Sound.ENTITY_FROG_TONGUE, 1F, 1F);
					// AbstractCubeMob, not Slime: in Paper 26.3 a magma cube no longer extends Slime, so the froglight below was unreachable
					boolean small = prey instanceof org.bukkit.entity.AbstractCubeMob cube && cube.getSize() <= 1;
					if (small) {
						org.bukkit.Material drop = org.bukkit.Material.SLIME_BALL;
						if (prey instanceof org.bukkit.entity.MagmaCube) {
							// the colour is the frog the tongue belongs to: a warm frog borrowed onto another body counts
							MorphRef main = plugin.morphs().activeForm(p);
							MorphRef frog = main != null && main.entityType().equals(type) ? main : plugin.borrowedHeads().borrowed(p);
							drop = switch (Frogs.froglight(frog == null ? "" : frog.snbt())) {
								case "pearlescent" -> org.bukkit.Material.PEARLESCENT_FROGLIGHT;
								case "verdant" -> org.bukkit.Material.VERDANT_FROGLIGHT;
								default -> org.bukkit.Material.OCHRE_FROGLIGHT;
							};
						}
						prey.remove();
						p.getWorld().dropItemNaturally(p.getLocation(), new org.bukkit.inventory.ItemStack(drop));
						p.getWorld().playSound(p.getLocation(), Sound.ENTITY_FROG_EAT, 1F, 1F);
					} else {
						prey.damage(2.0, p);
						prey.setVelocity(p.getLocation().toVector().subtract(prey.getLocation().toVector()).normalize().multiply(0.6).setY(0.2));
					}
				}
				case "sniff" -> {
					Block under = p.getLocation().subtract(0, 0.1, 0).getBlock();
					if (!Tag.SNIFFER_DIGGABLE_BLOCK.isTagged(under.getType())) return false;
					org.bukkit.Material seed = java.util.concurrent.ThreadLocalRandom.current().nextBoolean() ? org.bukkit.Material.TORCHFLOWER_SEEDS
							: org.bukkit.Material.PITCHER_POD;
					p.getWorld().playSound(p.getLocation(), Sound.ENTITY_SNIFFER_DIGGING, 1F, 1F);
					p.getWorld().spawnParticle(Particle.BLOCK, p.getLocation(), 20, 0.4, 0.1, 0.4, under.getBlockData());
					plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
						if (!p.isOnline()) return;
						p.getWorld().dropItemNaturally(p.getLocation(), new org.bukkit.inventory.ItemStack(seed));
						p.getWorld().playSound(p.getLocation(), Sound.ENTITY_SNIFFER_DROP_SEED, 1F, 1F);
					}, 40L);
				}
				case "roll-up" -> {
					int ticks = config.getInt("seconds", 6) * 20;
					p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, ticks, 2, true, false, true));
					p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 5, true, false, true));
					p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ARMADILLO_ROLL, 1F, 1F);
					MorphRef rolled = plugin.morphs().activeForm(p);
					plugin.morphs().setOverlayKeys(p, ROLL_KEYS, "{state:\"scared\"}"); // curled up, for every viewer
					plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
						if (p.isOnline() && rolled != null && rolled.equals(plugin.morphs().activeForm(p))) plugin.morphs().setOverlayKeys(p, ROLL_KEYS, "");
					}, ticks);
				}
				case "puff" -> {
					double radius = config.getDouble("radius", 3);
					for (Entity e : p.getNearbyEntities(radius, radius, radius)) {
						if (e instanceof LivingEntity le) le.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 120, 0));
					}
					p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PUFFER_FISH_BLOW_UP, 1F, 1F);
					p.getWorld().spawnParticle(Particle.BUBBLE_POP, p.getLocation().add(0, 0.5, 0), 20, radius / 2, 0.5, radius / 2, 0.05);
				}
				case "mimic" -> {
					Sound[] sounds = {Sound.ENTITY_PARROT_IMITATE_CREEPER, Sound.ENTITY_PARROT_IMITATE_ZOMBIE, Sound.ENTITY_PARROT_IMITATE_SKELETON,
							Sound.ENTITY_PARROT_IMITATE_SPIDER, Sound.ENTITY_PARROT_IMITATE_ENDERMITE, Sound.ENTITY_PARROT_IMITATE_WITCH,
							Sound.ENTITY_PARROT_IMITATE_BLAZE, Sound.ENTITY_PARROT_IMITATE_GHAST, Sound.ENTITY_PARROT_IMITATE_PHANTOM};
					p.getWorld().playSound(p.getLocation(), sounds[java.util.concurrent.ThreadLocalRandom.current().nextInt(sounds.length)], 1F, 1F);
				}
				case "magnet" -> {
					double radius = config.getDouble("radius", 8);
					int pulled = 0;
					for (Entity e : p.getNearbyEntities(radius, radius, radius)) {
						if (e instanceof org.bukkit.entity.Item item) {
							item.teleport(p.getLocation());
							pulled++;
						}
					}
					if (pulled == 0) return false;
					p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ALLAY_ITEM_TAKEN, 1F, 1F);
				}
				case "play-dead" -> {
					int ticks = config.getInt("seconds", 8) * 20;
					playingDead.put(p.getUniqueId(), tick + ticks);
					p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, ticks, 1, true, false, true));
					for (Entity e : p.getNearbyEntities(16, 8, 16)) {
						if (e instanceof org.bukkit.entity.Mob mob && mob.getTarget() == p) mob.setTarget(null);
					}
					p.getWorld().playSound(p.getLocation(), Sound.ENTITY_AXOLOTL_HURT, 1F, 0.6F);
				}
				case "scare" -> {
					Entity target = aimedAtNearBody(p, 4);
					if (!(target instanceof LivingEntity victim)) return false;
					victim.setVelocity(dir.clone().multiply(1.2).setY(0.4));
					victim.damage(3.0, p);
					p.getWorld().playSound(p.getLocation(), Sound.ENTITY_CREAKING_ATTACK, 1F, 1F);
				}
				case "swoop" -> {
					p.setVelocity(dir.clone().normalize().multiply(config.getDouble("strength", 1.8)));
					p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PHANTOM_SWOOP, 1F, 1F);
				}
				case "illusion" -> {
					// The illusioner's two spells: blind what you look at, or vanish behind mirror images.
					Entity target = aimedAt(p, config.getInt("range", 16));
					if (target instanceof LivingEntity victim) {
						victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, config.getInt("blindness-seconds", 20) * 20, 0));
						p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, 1F, 1F);
						plugin.channel().broadcastAnimation(p, "illusion-blind");
					} else {
						p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, config.getInt("invisible-seconds", 5) * 20, 0));
						p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1F, 1F);
						p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 1, 0), 20, 0.5, 0.8, 0.5, 0.02);
						plugin.channel().broadcastAnimation(p, "vanish");
					}
				}
				case "sting" -> {
					Entity target = aimedAtNearBody(p, config.getInt("range", 3));
					if (!(target instanceof LivingEntity victim)) {
						// nothing in reach: get angry (an angry bee's red eyes), or calm down again
						boolean now = !angry.remove(p.getUniqueId());
						if (now) angry.add(p.getUniqueId());
						beeOverlay(p);
						p.sendActionBar(Component.text(now ? "You're angry." : "You calm down.", NamedTextColor.GRAY));
						return true;
					}
					if (stung.contains(p.getUniqueId())) {
						p.sendActionBar(Component.text("Your stinger is spent.", NamedTextColor.GRAY));
						return false;
					}
					victim.damage(config.getDouble("damage", 2), p);
					victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, config.getInt("poison-seconds", 10) * 20, 0));
					p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BEE_STING, 1F, 1F);
					stung.add(p.getUniqueId());
					angry.add(p.getUniqueId()); // a bee that stings is an angry one
					beeOverlay(p);
					MorphRef bee = plugin.morphs().activeForm(p);
					boolean mortal = p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
					if (plugin.settings().beeStingKillsUser && stingDeathTicks > 0 && mortal) {
						p.sendActionBar(Component.text("You lost your stinger. Like a real bee, you won't last long.", NamedTextColor.GOLD));
						int sting = ++stingSeq;
						stingTimer.put(p.getUniqueId(), sting);
						plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
							// only this sting's clock: dropping the bee ended it, and a later sting has its own
							if (p.isOnline() && !p.isDead() && stingTimer.getOrDefault(p.getUniqueId(), -1) == sting && stung.contains(p.getUniqueId()) && bee != null && bee.equals(plugin.morphs().activeForm(p))) {
								killSelf(p, "death.attack.generic");
							}
						}, stingDeathTicks);
					}
				}
				case "turtle-eggs" -> {
					Block feet = p.getLocation().getBlock();
					if (!feet.getType().isAir() || !Tag.SAND.isTagged(feet.getRelative(BlockFace.DOWN).getType())) {
						p.sendActionBar(Component.text("Turtles lay their eggs on sand.", NamedTextColor.GRAY));
						return false;
					}
					feet.setType(org.bukkit.Material.TURTLE_EGG);
					if (feet.getBlockData() instanceof org.bukkit.block.data.type.TurtleEgg eggs) {
						eggs.setEggs(java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 5));
						feet.setBlockData(eggs);
					}
					p.getWorld().playSound(feet.getLocation(), Sound.ENTITY_TURTLE_LAY_EGG, 1F, 1F);
				}
				case "rear-up" -> {
					// A polar bear's warning: it rears up, roars and shoves back whatever is in front of it.
					p.getWorld().playSound(p.getLocation(), Sound.ENTITY_POLAR_BEAR_WARNING, 1F, 1F);
					Vector facing = flat(p);
					for (Entity e : p.getNearbyEntities(4, 2, 4)) {
						if (!(e instanceof LivingEntity victim) || e == p) continue;
						Vector to = e.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
						if (to.lengthSquared() < 1e-4 || to.clone().normalize().dot(facing) < 0.5) continue;
						victim.setVelocity(to.normalize().multiply(0.9).setY(0.35));
					}
				}
				case "infest" -> {
					Block target = blockAimedNearBody(p, config.getInt("range", 4));
					org.bukkit.Material infested = target == null ? null : INFESTED.get(target.getType());
					if (infested == null) {
						p.sendActionBar(Component.text("Look at stone, cobblestone, stone bricks or deepslate.", NamedTextColor.GRAY));
						return false;
					}
					org.bukkit.event.block.BlockBreakEvent check = new org.bukkit.event.block.BlockBreakEvent(target, p);
					check.setDropItems(false);
					check.setExpToDrop(0);
					if (!check.callEvent()) return false;
					target.setType(infested, false);
					p.getWorld().playSound(target.getLocation(), Sound.ENTITY_SILVERFISH_AMBIENT, 1F, 1F);
					p.getWorld().spawnParticle(Particle.BLOCK, target.getLocation().add(0.5, 0.5, 0.5), 15, 0.4, 0.4, 0.4, infested.createBlockData());
				}
				case "roll" -> {
					if (!p.isOnGround()) return false;
					p.setVelocity(flat(p).multiply(config.getDouble("strength", 0.7)).setY(0.25));
					p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PANDA_AMBIENT, 1F, 1F);
				}
				case "sort" -> {
					// Copper golem: carry a stack out of a copper chest into a nearby chest, one holding the same item if any.
					Block chest = blockAimedNearBody(p, config.getInt("range", 4));
					if (chest == null || !chest.getType().name().endsWith("COPPER_CHEST") || !(chest.getState() instanceof org.bukkit.block.Container from)) {
						p.sendActionBar(Component.text("Look at a copper chest to sort its items.", NamedTextColor.GRAY));
						return false;
					}
					org.bukkit.inventory.Inventory source = from.getInventory();
					int slot = -1;
					for (int i = 0; i < source.getSize() && slot < 0; i++) {
						org.bukkit.inventory.ItemStack item = source.getItem(i);
						if (item != null && !item.isEmpty()) slot = i;
					}
					if (slot < 0) return false;
					org.bukkit.inventory.ItemStack stack = source.getItem(slot);
					org.bukkit.inventory.Inventory dest = sortTarget(chest, stack, config.getInt("radius", 8));
					if (dest == null) {
						p.sendActionBar(Component.text("No chest nearby with room for it.", NamedTextColor.GRAY));
						return false;
					}
					Map<Integer, org.bukkit.inventory.ItemStack> left = dest.addItem(stack.clone());
					source.setItem(slot, left.isEmpty() ? null : left.values().iterator().next());
					// The lids open in turn, timed with the golem's reach-in and put-down animations (DisguiseBridge.animate).
					openLid(chest, 0, 30);
					if (dest.getLocation() != null) {
						Block to = dest.getLocation().getBlock();
						openLid(to, 30, 60);
						plugin.getServer().getScheduler().runTaskLater(plugin, () -> p.getWorld().playSound(to.getLocation(), Sound.ENTITY_COPPER_GOLEM_ITEM_DROP, 1F, 1F), 30);
					}
				}
				case "vanish" -> {
					p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, config.getInt("seconds", 5) * 20, 0));
					p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1F, 1F);
				}
				default -> {
					return false;
				}
			}
			return true;
		}
	}

	// ------------------------------------------------------------------ parsing

	private void openLid(Block block, int openAt, int closeAt) {
		org.bukkit.scheduler.BukkitScheduler scheduler = plugin.getServer().getScheduler();
		scheduler.runTaskLater(plugin, () -> {
			if (block.getState() instanceof org.bukkit.block.Lidded lid) lid.open();
		}, Math.max(1, openAt));
		scheduler.runTaskLater(plugin, () -> {
			if (block.getState() instanceof org.bukkit.block.Lidded lid) lid.close();
		}, closeAt);
	}

	private static Map<String, Integer> secondsMap(@Nullable ConfigurationSection section) {
		Map<String, Integer> out = new HashMap<>();
		if (section == null) return out;
		for (String type : section.getKeys(false)) out.put(VariantRules.normaliseType(type), (int) Math.round(Math.max(1, section.getDouble(type)) * 20));
		return out;
	}

	private static Set<String> types(List<String> names) {
		Set<String> out = new HashSet<>();
		for (String n : names) out.add(VariantRules.normaliseType(n));
		return out;
	}

	private static @Nullable PotionEffectType effect(String name) {
		NamespacedKey key = NamespacedKey.fromString(name.trim().toLowerCase(java.util.Locale.ROOT));
		return key == null ? null : RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT).get(key);
	}

	private List<TimedEffect> parseEffects(List<String> specs, boolean timed, String owner) {
		List<TimedEffect> list = new ArrayList<>();
		for (String spec : specs) {
			String[] parts = spec.split(":");
			PotionEffectType t = effect(parts[0]);
			if (t == null) {
				plugin.getLogger().warning("abilities.yml: unknown effect '" + parts[0] + "' for " + owner);
				continue;
			}
			int amp = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
			int ticks = timed && parts.length > 2 ? (int) (Double.parseDouble(parts[2].trim()) * 20) : PotionEffect.INFINITE_DURATION;
			list.add(new TimedEffect(t, amp, ticks));
		}
		return list;
	}

	private Map<String, List<TimedEffect>> effectMap(@Nullable ConfigurationSection section, boolean timed) {
		Map<String, List<TimedEffect>> out = new HashMap<>();
		if (section == null) return out;
		for (String type : section.getKeys(false)) {
			List<TimedEffect> list = new ArrayList<>();
			for (String spec : section.getStringList(type)) {
				String[] parts = spec.split(":");
				PotionEffectType t = effect(parts[0]);
				if (t == null) {
					plugin.getLogger().warning("abilities.yml: unknown effect '" + parts[0] + "' for " + type);
					continue;
				}
				int amp = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
				int ticks = timed && parts.length > 2 ? (int) (Double.parseDouble(parts[2].trim()) * 20) : PotionEffect.INFINITE_DURATION;
				list.add(new TimedEffect(t, amp, ticks));
			}
			out.put(VariantRules.normaliseType(type), list);
		}
		return out;
	}
}
