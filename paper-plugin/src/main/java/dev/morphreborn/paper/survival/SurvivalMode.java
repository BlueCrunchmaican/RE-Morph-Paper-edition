package dev.morphreborn.paper.survival;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.data.MorphRef;
import dev.morphreborn.paper.data.SurvivalData;
import dev.morphreborn.paper.mode.GameplayMode;
import dev.morphreborn.paper.survival.SurvivalConfig.Upgrade;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * Survival Mode, iChun's planned "Default Mode": morphing is earned. Having the unlock effects together (Wither and
 * Regeneration) unlocks the Biomass ability; from then on kills give biomass (iChun's formula: the mob's volume as
 * kilograms of meat, times the biomass value and the player's efficiency), which buys upgrades, one of which is the
 * ability to morph. Until then kills give biomass only.
 * <p>
 * This object always exists, so admins can inspect and edit progress while the server runs Classic Mode; it is only
 * the active {@link GameplayMode} when config.yml says {@code gameplay-mode: survival}.
 */
public final class SurvivalMode implements GameplayMode {
	private final MorphRebornPlugin plugin;
	private SurvivalConfig config;

	public SurvivalMode(MorphRebornPlugin plugin, SurvivalConfig config) {
		this.plugin = plugin;
		this.config = config;
	}

	public SurvivalConfig config() {
		return config;
	}

	public void setConfig(SurvivalConfig config) {
		this.config = config;
	}

	/** True when this is the server's gameplay mode (not just the progress keeper behind Classic). */
	public boolean active() {
		return plugin.mode() == this;
	}

	@Override
	public String id() {
		return "survival";
	}

	// ------------------------------------------------------------------ progress

	public SurvivalData data(Player player) {
		SurvivalData d = plugin.store().get(player.getUniqueId()).survival();
		if (d.layout < SurvivalData.LAYOUT) {
			if (convertLayout(d.upgrades)) plugin.getLogger().info("Converted " + player.getName() + "'s Survival upgrades to the 6.6.0 levels.");
			d.layout = SurvivalData.LAYOUT;
			plugin.store().markDirty(player.getUniqueId());
		}
		return d;
	}

	/**
	 * 6.6.0 changed what some upgrade levels mean. Each level bought before is moved to the first new level that is at
	 * least as good, so nobody loses anything (the new levels are bigger steps, so some players gain a little).
	 * Returns whether anything changed.
	 */
	static boolean convertLayout(Map<String, Integer> upgrades) {
		boolean changed = false;
		for (var e : upgrades.entrySet()) {
			int old = e.getValue();
			int now = switch (e.getKey()) {
				// 4, 8, 12 ... 24 blocks -> 3, 8, 16, 32, 64, 150
				case SurvivalConfig.BIOMASS_REACH -> old <= 0 ? old : old <= 2 ? 2 : old <= 4 ? 3 : 4;
				// 1,200 x 2.5^(level - 1) kg -> 1,200, 3,000, 10,000, 160,000, no limit
				case SurvivalConfig.BIOMASS_MAX_MASS -> old <= 0 ? old : old <= 2 ? old : old <= 3 ? 3 : old <= 6 ? 4 : 5;
				// 0-50% faster (level 0 = Classic) -> 130, 115, 100, 90, 80, 70% of Classic's time (level 1 is the start)
				case SurvivalConfig.MORPH_EFFICIENCY -> old <= 0 ? 3 : Math.min(old + 3, 6);
				// level 1 unlocked at Classic's cooldowns, then 10% shorter each -> 130, 115, 100, 90, 80, 70%
				case SurvivalConfig.MORPH_ABILITIES -> old <= 0 ? 0 : Math.min(old + 2, 6);
				default -> old;
			};
			if (now != old) {
				e.setValue(now);
				changed = true;
			}
		}
		return changed;
	}

	private void changed(Player player) {
		plugin.store().markDirty(player.getUniqueId());
		sync(player);
	}

	public int level(Player player, String id) {
		Upgrade u = config.upgrades.get(id);
		if (u == null) return 0;
		Integer bought = data(player).upgrades.get(id);
		return Math.clamp(bought == null ? u.startLevel() : bought, 0, u.maxLevel());
	}

	public double value(Player player, String id) {
		Upgrade u = config.upgrades.get(id);
		return u == null ? 0 : u.value().at(level(player, id));
	}

	public boolean isUnlocked(Player player) {
		return config.bypassUnlock || data(player).unlocked;
	}

	public boolean canMorph(Player player) {
		return isUnlocked(player) && level(player, SurvivalConfig.MORPH_ABILITY) >= 1;
	}

	public double capacity(Player player) {
		return value(player, SurvivalConfig.BIOMASS_CAPACITY);
	}

	// ------------------------------------------------------------------ GameplayMode

	@Override
	public void onKill(Player killer, LivingEntity victim, @Nullable MorphRef appearance) {
		if (!isUnlocked(killer)) return; // before "A Mutation Complication", kills give nothing
		double reach = value(killer, SurvivalConfig.BIOMASS_REACH);
		if (config.upgrades.containsKey(SurvivalConfig.BIOMASS_REACH) && reach >= 0 && distance(killer, victim) > reach) {
			killer.sendActionBar(Component.text("Too far away to absorb (your reach is " + number(reach) + " blocks).", NamedTextColor.GRAY));
			return;
		}
		double mass = massOf(victim);
		if (mass <= 0) return;
		double maxMass = value(killer, SurvivalConfig.BIOMASS_MAX_MASS);
		if (config.upgrades.containsKey(SurvivalConfig.BIOMASS_MAX_MASS) && maxMass >= 0 && mass > maxMass) {
			killer.sendActionBar(Component.text("Too big to absorb: " + number(mass) + " kg (you can absorb up to " + number(maxMass) + " kg).",
					NamedTextColor.GRAY));
			return;
		}
		double gain = mass * config.biomassValue * value(killer, SurvivalConfig.BIOMASS_EFFICIENCY) / 100.0 * config.gainMultiplier;
		double added = addBiomass(killer, gain);
		MorphRef morph = appearance != null && canMorph(killer) ? plugin.morphs().acquire(killer, appearance) : null;
		if (added > 0 || morph != null) plugin.morphs().playAcquisition(killer, victim, morph != null);
		SurvivalData d = data(killer);
		if (added > 0) {
			killer.sendActionBar(Component.text("+" + number(added) + " biomass", NamedTextColor.RED)
					.append(Component.text("  (" + number(d.biomass) + " / " + number(capacity(killer)) + ")", NamedTextColor.GRAY)));
		} else if (gain > 0) {
			killer.sendActionBar(Component.text("Biomass full (" + number(d.biomass) + " / " + number(capacity(killer)) + "). Spend it or upgrade Biomass Capacity: /morph upgrades",
					NamedTextColor.YELLOW));
		}
	}

	/** Distance from the killer's eyes to the nearest point of the victim's body. */
	private static double distance(Player killer, LivingEntity victim) {
		if (killer.getWorld() != victim.getWorld()) return Double.MAX_VALUE;
		Vector eye = killer.getEyeLocation().toVector();
		BoundingBox box = victim.getBoundingBox();
		double dx = Math.max(Math.max(box.getMinX() - eye.getX(), 0), eye.getX() - box.getMaxX());
		double dy = Math.max(Math.max(box.getMinY() - eye.getY(), 0), eye.getY() - box.getMaxY());
		double dz = Math.max(Math.max(box.getMinZ() - eye.getZ(), 0), eye.getZ() - box.getMaxZ());
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/**
	 * iChun's getLivingWeight before the biomass value: width x width x height x 1000 kg/m^3 ("meat averages about
	 * 1000 kg/m^3"), with survival.yml's per-mob multiplier or fixed mass. A player is 648 kg.
	 */
	public double massOf(LivingEntity entity) {
		String type = entity.getType().getKey().toString();
		if (config.noBiomass.contains(type)) return 0;
		Double fixed = config.massOverrides.get(type);
		if (fixed != null) return fixed;
		double mass = entity.getWidth() * entity.getWidth() * entity.getHeight() * 1000.0;
		return mass * config.massMultipliers.getOrDefault(type, 1.0);
	}

	/** survival.yml's morph-cost: a fixed price for the mob, or its mass x per-kg, rounded up, at least the minimum. */
	@Override
	public int morphCost(Player player, MorphRef form) {
		if (!active() || !config.morphCostEnabled || form.isSelf()) return 0;
		Integer fixed = config.morphPrices.get(form.entityType());
		if (fixed != null) return fixed;
		double mass = PLAYER_MASS;
		if (!form.isPlayer()) {
			LivingEntity template = plugin.templates().get(form, player.getWorld());
			if (template != null) mass = massOf(template);
		}
		return Math.max(config.morphCostMinimum, (int) Math.ceil(mass * config.morphCostPerKg - 1e-9));
	}

	@Override
	public boolean payBiomass(Player player, int cost) {
		if (cost <= 0) return true;
		SurvivalData d = data(player);
		if (d.biomass + 1e-9 < cost) return false;
		d.biomass -= cost;
		changed(player);
		player.sendActionBar(Component.text("-" + cost + " biomass", NamedTextColor.RED)
				.append(Component.text("  (" + number(d.biomass) + " / " + number(capacity(player)) + ")", NamedTextColor.GRAY)));
		return true;
	}

	/** A player's mass by iChun's formula (0.6 x 0.6 x 1.8 x 1000 kg), for player forms. */
	private static final double PLAYER_MASS = 648;

	/** Send a modded player the price of every morph in their collection (empty when morphing is free). */
	public void sendCosts(Player player) {
		if (!plugin.channel().isModded(player)) return;
		Map<String, Integer> costs = new LinkedHashMap<>();
		if (active() && config.morphCostEnabled) {
			var data = plugin.store().get(player.getUniqueId());
			for (var e : data.morphs.entrySet()) {
				for (var v : e.getValue()) {
					MorphRef ref = dev.morphreborn.paper.data.PlayerMorphData.toRef(e.getKey(), v);
					costs.put(ref.variantId(), morphCost(player, ref));
				}
			}
		}
		plugin.channel().sendCosts(player, costs);
	}

	@Override
	public @Nullable String cannotCombine(Player player) {
		if (!active()) return null;
		if (level(player, SurvivalConfig.MULTIMORPH) < 1) return "Combining morphs needs the Multimorph upgrade: /morph upgrades";
		return null;
	}

	@Override
	public int multimorphExtra(Player player, int cost) {
		if (!active() || !config.morphCostEnabled || cost <= 0) return 0;
		return (int) Math.ceil(cost * config.multimorphExtraPercent / 100.0 - 1e-9);
	}

	/** The percent a multimorph adds to its price, for the client's price panel. */
	public int multimorphExtraPercent() {
		return active() && config.morphCostEnabled ? config.multimorphExtraPercent : 0;
	}

	@Override
	public @Nullable String cannotMorph(Player player) {
		if (!isUnlocked(player)) return "You can't morph yet. First gain the Biomass ability: have Wither and Regeneration at the same time.";
		if (level(player, SurvivalConfig.MORPH_ABILITY) < 1) return "You can't morph yet. Buy the Morph Ability upgrade with biomass: /morph upgrades";
		return null;
	}

	@Override
	public @Nullable String cannotUseAbility(Player player) {
		if (config.upgrades.containsKey(SurvivalConfig.MORPH_ABILITIES) && level(player, SurvivalConfig.MORPH_ABILITIES) < 1) {
			return "Your morph's abilities are locked. Buy the Morph Abilities upgrade: /morph upgrades";
		}
		return null;
	}

	/** Morph Abilities' value: cooldowns as a share of Classic's (130% at level 1 down to 70% at level 6). */
	@Override
	public double cooldownFactor(Player player) {
		return percentOfClassic(player, SurvivalConfig.MORPH_ABILITIES, 0.05);
	}

	/** Morph Efficiency's value: transformation time as a share of Classic's (130% at the start down to 70%). */
	@Override
	public double transitionFactor(Player player) {
		return percentOfClassic(player, SurvivalConfig.MORPH_EFFICIENCY, 0.1);
	}

	/** An upgrade whose value is "% of Classic"; level 0 counts as level 1, and a missing upgrade as Classic. */
	private double percentOfClassic(Player player, String id, double min) {
		Upgrade u = config.upgrades.get(id);
		if (u == null) return 1.0;
		double percent = u.value().at(Math.max(1, level(player, id)));
		return percent <= 0 ? 1.0 : Math.clamp(percent / 100.0, min, 5.0);
	}

	@Override
	public boolean mayFly(Player player) {
		return !config.upgrades.containsKey(SurvivalConfig.MORPH_FLIGHT) || level(player, SurvivalConfig.MORPH_FLIGHT) >= 1;
	}

	/** A beneficial trait is on once any upgrade that lists it is bought; traits no upgrade lists are always on. */
	@Override
	public boolean hasTrait(Player player, String trait) {
		java.util.List<String> unlocks = config.traitUnlocks.get(trait);
		if (unlocks == null) return true;
		for (String id : unlocks) {
			if (level(player, id) >= 1) return true;
		}
		return false;
	}

	@Override
	public int typeCapacity(Player player) {
		return config.upgrades.containsKey(SurvivalConfig.MORPH_CAPACITY) ? (int) Math.floor(value(player, SurvivalConfig.MORPH_CAPACITY)) : -1;
	}

	@Override
	public int variantCapacity(Player player) {
		return config.upgrades.containsKey(SurvivalConfig.MORPH_VARIANT_CAPACITY) ? (int) Math.floor(value(player, SurvivalConfig.MORPH_VARIANT_CAPACITY)) : -1;
	}

	@Override
	public void onJoin(Player player) {
		checkUnlock(player);
		sync(player);
	}

	// ------------------------------------------------------------------ unlocking ("A Mutation Complication")

	/** Unlock biomass if the player has every unlock effect at once. Called when their effects change. */
	public void checkUnlock(Player player) {
		if (!active() || data(player).unlocked || config.unlockEffects.isEmpty()) return;
		for (PotionEffectType type : config.unlockEffects) {
			if (!player.hasPotionEffect(type)) return;
		}
		unlock(player);
	}

	public void unlock(Player player) {
		SurvivalData d = data(player);
		if (d.unlocked) return;
		d.unlocked = true;
		d.unlockedAt = System.currentTimeMillis();
		changed(player);
		player.showTitle(Title.title(Component.text("A Mutation Complication", NamedTextColor.DARK_GREEN),
				Component.text("You can now absorb biomass", NamedTextColor.GRAY), Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1))));
		player.sendMessage(Component.text("Rapid cellular death combined with rapid cellular regeneration increases the risk for random mutations.",
				NamedTextColor.DARK_GREEN, TextDecoration.ITALIC));
		player.sendMessage(Component.text("You can now absorb the biomass of creatures you kill. Spend it on upgrades, starting with the ability to morph: ",
				NamedTextColor.GREEN).append(Component.text("/morph upgrades", NamedTextColor.YELLOW)));
		player.playSound(player.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.6F, 1.2F);
		plugin.getLogger().info(player.getName() + " unlocked the Biomass ability.");
	}

	// ------------------------------------------------------------------ biomass

	/** Add biomass up to the player's capacity; returns how much fit. */
	public double addBiomass(Player player, double amount) {
		SurvivalData d = data(player);
		double room = Math.max(0, capacity(player) - d.biomass);
		double added = Math.max(0, Math.min(amount, room));
		if (added > 0) {
			d.biomass += added;
			changed(player);
		}
		return added;
	}

	/** Admin: set biomass directly (not limited by capacity). */
	public void setBiomass(Player player, double amount) {
		data(player).biomass = Math.max(0, amount);
		changed(player);
	}

	// ------------------------------------------------------------------ upgrades

	public enum Purchase { BOUGHT, BIOMASS_LOCKED, MAXED, MISSING_REQUIREMENT, TOO_EXPENSIVE, UNKNOWN }

	/** The next level's cost in whole biomass, or -1 when maxed. */
	public double nextCost(Player player, Upgrade u) {
		int level = level(player, u.id());
		return level >= u.maxLevel() ? -1 : Math.max(0, Math.round(u.cost().at(level + 1)));
	}

	/** What still has to be bought first, or null. */
	public @Nullable String missingRequirement(Player player, Upgrade u) {
		if (!u.parent().equals("root") && level(player, u.parent()) < 1) return displayName(u.parent());
		for (Map.Entry<String, Integer> r : u.requires().entrySet()) {
			if (level(player, r.getKey()) < r.getValue()) return displayName(r.getKey()) + (r.getValue() > 1 ? " level " + r.getValue() : "");
		}
		return null;
	}

	public String displayName(String id) {
		Upgrade u = config.upgrades.get(id);
		return u == null ? id : u.name();
	}

	public Purchase buy(Player player, String id) {
		Upgrade u = config.upgrades.get(id);
		if (u == null) return Purchase.UNKNOWN;
		if (!isUnlocked(player)) return Purchase.BIOMASS_LOCKED;
		double cost = nextCost(player, u);
		if (cost < 0) return Purchase.MAXED;
		if (missingRequirement(player, u) != null) return Purchase.MISSING_REQUIREMENT;
		SurvivalData d = data(player);
		if (d.biomass + 1e-9 < cost) return Purchase.TOO_EXPENSIVE;
		d.biomass -= cost;
		d.upgrades.put(id, level(player, id) + 1);
		changed(player);
		afterUpgrade(player, id);
		return Purchase.BOUGHT;
	}

	/** A player's own purchase (menu or client screen): buy, with a sound either way. */
	public Purchase buyWithFeedback(Player player, String id) {
		Purchase result = buy(player, id);
		if (result == Purchase.BOUGHT) player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6F, 1.4F);
		else if (result != Purchase.UNKNOWN) player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6F, 0.7F);
		return result;
	}

	/** Admin: set an upgrade's level directly. */
	public void setLevel(Player player, String id, int level) {
		Upgrade u = config.upgrades.get(id);
		if (u == null) throw new IllegalArgumentException("Unknown upgrade " + id + ". Upgrades: " + String.join(", ", config.upgrades.keySet()));
		data(player).upgrades.put(id, Math.clamp(level, 0, u.maxLevel()));
		changed(player);
		afterUpgrade(player, id);
	}

	/** Some upgrades change what the player's current form can do right away. */
	private void afterUpgrade(Player player, String id) {
		if (!active()) return;
		Upgrade u = config.upgrades.get(id);
		if (id.equals(SurvivalConfig.MORPH_FLIGHT) || id.equals(SurvivalConfig.MORPH_ABILITIES) || (u != null && !u.traits().isEmpty())) {
			plugin.abilities().refreshMovement(player);
		}
	}

	/** Start over: locked biomass, no biomass, start levels. The morph collection is untouched. */
	public void reset(Player player) {
		plugin.store().get(player.getUniqueId()).survival = null;
		changed(player);
		if (active()) {
			if (plugin.morphs().activeForm(player) != null) plugin.morphs().demorph(player, true);
			plugin.abilities().refreshMovement(player);
		}
	}

	/** What a purchase attempt means to the player. */
	public String describe(Purchase result, Player player, String id) {
		Upgrade u = config.upgrades.get(id);
		return switch (result) {
			case BOUGHT -> "Bought " + displayName(id) + (u != null && u.maxLevel() > 1 ? " level " + level(player, id) : "") + ".";
			case BIOMASS_LOCKED -> "Unlock the Biomass ability first: have Wither and Regeneration at the same time.";
			case MAXED -> displayName(id) + " is already fully upgraded.";
			case MISSING_REQUIREMENT -> "Needs " + missingRequirement(player, u) + " first.";
			case TOO_EXPENSIVE -> "Not enough biomass: it costs " + number(nextCost(player, u)) + ".";
			case UNKNOWN -> "Unknown upgrade " + id + ".";
		};
	}

	/** One upgrade as the client's tree screen shows it. */
	public record View(String id, String name, String description, String icon, String parent, int level, int maxLevel, String now, String next, int cost,
			String missing, boolean affordable) {}

	public java.util.List<View> views(Player player) {
		java.util.List<View> out = new java.util.ArrayList<>();
		double biomass = data(player).biomass;
		for (Upgrade u : config.upgrades.values()) {
			int level = level(player, u.id());
			double cost = nextCost(player, u);
			String missing = missingRequirement(player, u);
			String icon = u.effectIcon().isEmpty() ? u.icon().getKey().toString() : "effect:" + u.effectIcon();
			out.add(new View(u.id(), u.name(), u.description(), icon, u.parent(), level, u.maxLevel(),
					u.maxLevel() > 1 && level > 0 ? formatValue(u, u.value().at(level)) : "",
					u.maxLevel() > 1 && level < u.maxLevel() ? formatValue(u, u.value().at(level + 1)) : "",
					cost < 0 ? -1 : (int) cost, missing == null ? "" : missing, cost >= 0 && biomass + 1e-9 >= cost));
		}
		return out;
	}

	// ------------------------------------------------------------------ display

	public void sync(Player player) {
		if (!active()) return;
		plugin.channel().sendSurvival(player, isUnlocked(player), (int) Math.floor(data(player).biomass), (int) Math.floor(capacity(player)), canMorph(player));
	}

	/**
	 * The upgrade's format with {value} filled in; a negative value means "no limit". For a value that is a percentage
	 * of the normal length (130 = 30% longer), {seconds} is a transformation's length in seconds at that percentage
	 * (config.yml's transition length) and {factor} the multiplier ("×1.3").
	 */
	public String formatValue(Upgrade u, double value) {
		if (value < 0) return "no limit";
		String text = u.format().replace("{value}", number(value));
		if (text.contains("{seconds}")) text = text.replace("{seconds}", transformSeconds(plugin.settings().transitionTicks, value));
		return text.replace("{factor}", factor(value));
	}

	/** A transformation's length at this percentage of the normal one, in seconds (never under a second, as MorphManager). */
	static String transformSeconds(int transitionTicks, double percent) {
		return number(Math.max(20, Math.round(transitionTicks * percent / 100.0)) / 20.0);
	}

	private static final DecimalFormat FACTOR = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ROOT));

	static String factor(double percent) {
		synchronized (FACTOR) {
			return "×" + FACTOR.format(percent / 100.0);
		}
	}

	private static final DecimalFormat NUMBER = new DecimalFormat("#,##0.#", DecimalFormatSymbols.getInstance(Locale.ROOT));

	public static String number(double value) {
		synchronized (NUMBER) {
			return NUMBER.format(value);
		}
	}
}
