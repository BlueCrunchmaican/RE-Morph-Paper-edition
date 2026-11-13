package dev.morphreborn.paper.ability;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.SemVer;
import dev.morphreborn.paper.survival.SurvivalConfig;
import dev.morphreborn.paper.survival.SurvivalMode;
import dev.morphreborn.paper.variant.VariantRules;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.jspecify.annotations.Nullable;

/**
 * Devouring Frenzy (called "rage" in settings, permissions, the upgrade's id and the code; it was named Rage in 7.2.0):
 * evening the odds when a crowd turns on you. Take enough hits in a short time from more than one attacker (three
 * skeletons juggling you with arrows, a ring of zombies) and a frenzy is charged; it waits for you to set it off (the
 * Devouring Frenzy key with the mod, /morph frenzy without), and stays charged until you do. Then tendrils latch onto
 * the creatures around you and drain their health with a hit every half second while they're still alive, and each one
 * that dies is absorbed as an ordinary kill of yours (biomass and a morph under the usual rules, the usual absorb
 * animation). How far it reaches, how hard each hit is and how long it lasts depend on its level (abilities.yml
 * rage.levels): the upgrade's level in Survival, rage.classic-level in Classic. The ender dragon, the wither, the warden,
 * the elder guardian, villagers, wandering traders, name-tagged creatures and other players' pets are drained more
 * slowly ({@code lesser}); players are drained too but never killed; your own pets and anything wearing a saddle,
 * harness or armour are left alone.
 * <p>
 * The idea comes from a picture on Morph's old CurseForge page (a pig with many mobs being absorbed into it at once); it
 * was never an ability in Morph.
 */
public final class Rage implements Listener {
	/** What players see it called. */
	public static final String NAME = "Devouring Frenzy";
	/** Ticks between drain hits: a creature ignores a second hit this soon after the first (its hurt cooldown). */
	private static final int DRAIN_INTERVAL = 10;
	/** A latched creature further away than the radius plus this is let go. */
	private static final double RELEASE_MARGIN = 2;
	/**
	 * Hostile kinds that leave you alone until provoked (or in daylight): drained in "enemies" mode only when they are
	 * after you, or one of them hit you.
	 */
	private static final Set<String> NEUTRAL = Set.of("minecraft:enderman", "minecraft:zombified_piglin", "minecraft:piglin", "minecraft:spider",
			"minecraft:cave_spider");

	/**
	 * One level: how far the tendrils reach (blocks), how much health each drain hit takes, how long it lasts (ticks), and
	 * how high its tendrils lift the player (blocks above the ground, 0: no hover).
	 */
	public record Level(double radius, double damage, int ticks, double hover) {}

	/** The levels when abilities.yml has none. */
	private static final List<Level> DEFAULT_LEVELS = List.of(new Level(10, 2, 100, 4), new Level(16, 2.5, 160, 6), new Level(20, 3.5, 200, 8));

	private final MorphRebornPlugin plugin;

	private boolean enabled;
	private int hitsNeeded;
	private long windowMillis;
	private int minAttackers;
	private final List<Level> levels = new ArrayList<>();
	private int classicLevel;
	private int maxTargets;
	private boolean enemiesOnly;
	private double lesser;
	private final Set<String> lesserTypes = new HashSet<>();
	private boolean lesserNamed, lesserPets;
	private boolean players;
	private double playerFloor;
	private int cooldownSeconds;
	private boolean report;

	/** A hit taken: when, and who dealt it. */
	private record Hit(long at, UUID attacker) {}

	/** Hits each player took within the window, oldest first. Not saved: they last seconds. */
	private final Map<UUID, ArrayDeque<Hit>> hits = new HashMap<>();
	/** What the meter last showed each player, so a hit running out only sends a change. */
	private final Map<UUID, Integer> shownHits = new HashMap<>();
	/** Who charged each charged frenzy (fair game when it is set off, whoever hit you first). Not saved. */
	private final Map<UUID, Set<UUID>> chargedBy = new HashMap<>();

	/** A frenzy in progress. */
	private static final class Raging {
		final int start, endAt, level;
		final Level stats;
		/** Creatures latched onto, with the tick each was caught; in the order they were caught. */
		final Map<UUID, Integer> targets = new LinkedHashMap<>();
		/** Whoever hit the player shortly before or during the frenzy: always fair game. */
		final Set<UUID> attackers = new HashSet<>();
		int taken, newMorphs, caught;
		/** Health taken from everything it held, for the report. */
		double drained;
		double biomassBefore;
		boolean targetsChanged = true;

		Raging(int start, int level, Level stats) {
			this.start = start;
			this.endAt = start + stats.ticks();
			this.level = level;
			this.stats = stats;
		}
	}

	private final Map<UUID, Raging> raging = new HashMap<>();
	/** The creature a frenzy is killing right now, and whether the kill already showed it being absorbed. */
	private @Nullable UUID absorbing;
	private boolean absorbShown;
	private @Nullable Raging absorbingFor;
	private int tick;

	/** The hover a frenzy allows (Space to rise on the tendrils, crouch to sink). */
	private final FrenzyHover hover;

	public Rage(MorphRebornPlugin plugin) {
		this.plugin = plugin;
		this.hover = new FrenzyHover(plugin);
		plugin.getServer().getPluginManager().registerEvents(hover, plugin);
	}

	/** abilities.yml rage.report: the hover's starts and stops too. */
	void reportHover(Player p, String what) {
		if (report) report(p, what);
	}

	/** A hover started or ended: players with the mod draw its legs. */
	void hoverChanged(Player p) {
		Raging r = raging.get(p.getUniqueId());
		plugin.channel().broadcastRageDrain(p, r == null ? List.of() : ids(p, r), hover.hovering(p));
	}

	public void load(@Nullable ConfigurationSection c) {
		enabled = c != null && c.getBoolean("enabled", true);
		hitsNeeded = c == null ? 6 : Math.clamp(c.getInt("hits", 6), 1, 100);
		windowMillis = Math.round((c == null ? 6 : Math.clamp(c.getDouble("window", 6), 1, 60)) * 1000);
		minAttackers = c == null ? 2 : Math.clamp(c.getInt("attackers", 2), 1, 20);
		levels.clear();
		if (c != null) {
			for (Map<?, ?> m : c.getMapList("levels")) {
				double radius = number(m.get("radius"), 16);
				levels.add(level(radius, number(m.get("damage"), 2.5), number(m.get("duration"), 8), number(m.get("hover"), defaultHover(radius))));
			}
			// a file in 7.2.0's layout (one level, drain in health a second)
			if (levels.isEmpty() && (c.contains("radius") || c.contains("drain") || c.contains("duration"))) {
				double radius = c.getDouble("radius", 16);
				levels.add(level(radius, c.getDouble("drain", 5) * DRAIN_INTERVAL / 20.0, c.getDouble("duration", 8), defaultHover(radius)));
			}
		}
		if (levels.isEmpty()) levels.addAll(DEFAULT_LEVELS);
		classicLevel = Math.clamp(c == null ? 2 : c.getInt("classic-level", 2), 1, levels.size());
		// 0: every creature in reach (the user: "however many were around you"); any other number caps it
		maxTargets = c == null ? 0 : Math.max(0, c.getInt("max-targets", 0));
		enemiesOnly = c == null || !"all".equalsIgnoreCase(c.getString("targets", "enemies"));
		lesser = c == null ? 0.25 : Math.clamp(c.getDouble("lesser", 0.25), 0, 1);
		lesserTypes.clear();
		List<String> types = c == null || !c.isList("lesser-types") ? List.of("ender_dragon", "wither", "warden", "elder_guardian", "villager", "wandering_trader")
				: c.getStringList("lesser-types");
		for (String t : types) lesserTypes.add(VariantRules.normaliseType(t));
		lesserNamed = c == null || c.getBoolean("lesser-named", true);
		lesserPets = c == null || c.getBoolean("lesser-pets", true);
		players = c == null || c.getBoolean("players", true);
		playerFloor = c == null ? 2 : Math.clamp(c.getDouble("player-floor", 2), 0.5, 20);
		cooldownSeconds = c == null ? 20 : Math.clamp(c.getInt("cooldown", 20), 0, 3600);
		report = c != null && c.getBoolean("report", false);
		hover.load(c);
		if (!enabled) releaseAll();
	}

	private static Level level(double radius, double damage, double seconds, double hover) {
		return new Level(Math.clamp(radius, 1, 32), Math.clamp(damage, 0.1, 1000), (int) Math.round(Math.clamp(seconds, 1, 60) * 20), Math.clamp(hover, 0, 16));
	}

	/**
	 * A level without its own hover height lifts the player two fifths of its reach (the defaults: 4, 6 and 8 blocks with
	 * 10, 16 and 20; the user: start at 4 and go higher), so a hovering player still reaches most of the ground its
	 * tendrils would (a frenzy's kills are absorbed anywhere in reach).
	 */
	private static double defaultHover(double radius) {
		return Math.round(radius * 0.4);
	}

	private static double number(@Nullable Object o, double fallback) {
		return o instanceof Number n ? n.doubleValue() : fallback;
	}

	/** The server offers the frenzy at all (abilities.yml rage.enabled); told to the client mod in the handshake. */
	public boolean enabled() {
		return enabled;
	}

	private boolean survival() {
		return plugin.survival().active();
	}

	/** How many levels it has (abilities.yml rage.levels). */
	public int levels() {
		return levels.size();
	}

	/** The level this player's frenzy runs at: their upgrade's level in Survival (at least 1), classic-level in Classic. */
	public int level(Player player) {
		if (!survival()) return classicLevel;
		return Math.clamp(plugin.survival().level(player, SurvivalConfig.RAGE), 1, levels.size());
	}

	private Level stats(int level) {
		return levels.get(Math.clamp(level, 1, levels.size()) - 1);
	}

	/** A level's numbers in words, for the upgrade tree ("16 blocks, 2.5 damage a hit, 8 seconds"). */
	public String describe(int level) {
		Level l = stats(level);
		return SurvivalMode.number(l.radius()) + " blocks, " + SurvivalMode.number(l.damage()) + " damage a hit, " + SurvivalMode.number(l.ticks() / 20.0)
				+ " seconds" + (l.hover() > 0 ? ", hover " + SurvivalMode.number(l.hover()) + " blocks up" : "");
	}

	/** Why this player can't use the frenzy at all, or null if they can (charged or not). */
	public @Nullable String unavailable(Player player) {
		if (!enabled) return NAME + " is switched off on this server.";
		if (!player.hasPermission("morphreborn.rage")) return "You are not allowed to use " + NAME + ".";
		if (survival() && plugin.survival().level(player, SurvivalConfig.RAGE) < 1) return NAME + " needs its upgrade: /morph upgrades";
		return null;
	}

	private long cooldownLeftMillis(Player player) {
		return Math.max(0, plugin.store().get(player.getUniqueId()).rageReadyAt - System.currentTimeMillis());
	}

	public boolean isRaging(Player player) {
		return raging.containsKey(player.getUniqueId());
	}

	/** True while a frenzy is killing {@code victim}: its kill is absorbed wherever it is (Survival's reach doesn't apply). */
	public boolean isRageKill(Entity victim) {
		return absorbing != null && absorbing.equals(victim.getUniqueId());
	}

	// ------------------------------------------------------------------ charging

	/** A hit that hurt a player: one toward their frenzy, if it came from a creature or another player. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onHurt(EntityDamageByEntityEvent event) {
		if (!enabled || !(event.getEntity() instanceof Player player) || event.getFinalDamage() <= 0) return;
		Entity cause = event.getDamageSource().getCausingEntity();
		if (cause == null) cause = event.getDamager();
		if (!(cause instanceof LivingEntity attacker) || attacker == player) return;
		Raging r = raging.get(player.getUniqueId());
		if (r != null) {
			r.attackers.add(attacker.getUniqueId()); // whoever hits you during a frenzy is fair game
			return;
		}
		if (player.isDead() || unavailable(player) != null || cooldownLeftMillis(player) > 0) return;
		long now = System.currentTimeMillis();
		ArrayDeque<Hit> list = hits.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
		list.addLast(new Hit(now, attacker.getUniqueId()));
		prune(list, now);
		// already charged: the hits only say whom to drain first when it is set off
		if (!isCharged(player) && list.size() >= hitsNeeded && attackers(list) >= minAttackers) charge(player, list);
		syncIfChanged(player);
	}

	public boolean isCharged(Player player) {
		return plugin.store().get(player.getUniqueId()).rageCharged;
	}

	/** The frenzy is charged: it waits for the player (saved with them) until they set it off. */
	private void charge(Player player, @Nullable ArrayDeque<Hit> list) {
		var data = plugin.store().get(player.getUniqueId());
		data.rageCharged = true;
		plugin.store().markDirty(player.getUniqueId());
		Set<UUID> who = new HashSet<>();
		if (list != null) for (Hit h : list) who.add(h.attacker());
		chargedBy.put(player.getUniqueId(), who);
		player.playSound(player.getLocation(), Sound.ENTITY_RAVAGER_AMBIENT, 0.7F, 0.7F);
		// players with the mod see READY on the meter; the others are told how to set it off
		if (!hasMeter(player)) {
			player.sendMessage(Component.text("Your frenzy is charged. /morph frenzy sets it off.", NamedTextColor.RED));
		}
		sync(player);
	}

	/** Whether the player's client draws the meter: the mod from 7.2.0 (older mods skip S_RAGE). */
	private boolean hasMeter(Player player) {
		if (!plugin.channel().isModded(player)) return false;
		String version = plugin.channel().clientVersion(player);
		return version != null && SemVer.compare(version, "7.2.0") >= 0;
	}

	/** Admin and testing: charge the player's frenzy. */
	public void fill(Player player) {
		if (!isCharged(player)) charge(player, hits.get(player.getUniqueId()));
	}

	/**
	 * The Devouring Frenzy key or /morph frenzy: set off a charged frenzy, or say why not (not charged yet, the wait
	 * after the last one, no upgrade...).
	 */
	public void trigger(Player player) {
		String why = unavailable(player);
		if (why == null && isRaging(player)) return;
		if (why == null && !isCharged(player)) why = notReady(player);
		if (why == null) why = start(player, 0);
		if (why != null) player.sendActionBar(Component.text(why, NamedTextColor.GRAY));
	}

	private String notReady(Player player) {
		long wait = cooldownLeftMillis(player);
		if (wait > 0) return "Your frenzy is spent; hits count toward it again in " + (wait + 999) / 1000 + "s.";
		String window = windowMillis / 1000 + (windowMillis % 1000 == 0 ? "" : "." + windowMillis % 1000 / 100);
		return "Your frenzy isn't charged: " + shown(player) + "/" + hitsNeeded + " hits. It takes " + hitsNeeded + " within " + window + "s"
				+ (minAttackers > 1 ? " from at least " + minAttackers + " attackers." : ".");
	}

	private void prune(ArrayDeque<Hit> list, long now) {
		while (!list.isEmpty() && now - list.peekFirst().at() > windowMillis) list.removeFirst();
	}

	private static int attackers(ArrayDeque<Hit> list) {
		Set<UUID> who = new HashSet<>();
		for (Hit h : list) who.add(h.attacker());
		return who.size();
	}

	/**
	 * What the meter shows: full when charged, otherwise hits within the window, one short of full while they all came
	 * from too few attackers.
	 */
	private int shown(Player player) {
		if (isCharged(player)) return hitsNeeded;
		ArrayDeque<Hit> list = hits.get(player.getUniqueId());
		if (list == null || list.isEmpty()) return 0;
		int n = Math.min(list.size(), hitsNeeded);
		return n >= hitsNeeded && attackers(list) < minAttackers ? hitsNeeded - 1 : n;
	}

	private void syncIfChanged(Player player) {
		int now = shown(player);
		Integer before = shownHits.put(player.getUniqueId(), now);
		if (before == null || before != now) sync(player);
	}

	// ------------------------------------------------------------------ the frenzy

	/**
	 * Set off a frenzy now (charged, or an admin's /morph frenzy <player> go), using up the charge. {@code level}: the
	 * level to run at (an admin testing one), or 0 for the player's own. Returns why not, or null.
	 */
	public @Nullable String start(Player player, int level) {
		String why = unavailable(player);
		if (why != null) return why;
		if (isRaging(player)) return player.getName() + " is already in a frenzy.";
		if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) return "You can't set off a frenzy right now.";
		int lv = level > 0 ? Math.clamp(level, 1, levels.size()) : level(player);
		Raging r = new Raging(tick, lv, stats(lv));
		var data = plugin.store().get(player.getUniqueId());
		data.rageCharged = false;
		plugin.store().markDirty(player.getUniqueId());
		Set<UUID> charged = chargedBy.remove(player.getUniqueId());
		if (charged != null) r.attackers.addAll(charged);
		ArrayDeque<Hit> list = hits.remove(player.getUniqueId());
		if (list != null) for (Hit h : list) r.attackers.add(h.attacker());
		r.biomassBefore = survival() ? plugin.survival().data(player).biomass : 0;
		raging.put(player.getUniqueId(), r);
		shownHits.remove(player.getUniqueId());
		Location at = player.getLocation();
		player.getWorld().playSound(at, Sound.ENTITY_RAVAGER_ROAR, 1F, 0.8F);
		player.getWorld().playSound(at, Sound.ENTITY_WARDEN_HEARTBEAT, 1.5F, 0.6F);
		// no burst of particles for players without the mod: the mod has none, and the tendrils growing out show it
		// how to hover is in the upgrade's description (the user: not on the screen every time)
		player.sendActionBar(Component.text(NAME + "!", NamedTextColor.RED, TextDecoration.BOLD));
		if (report) report(player, "level " + lv + " (" + describe(lv) + "), " + (maxTargets > 0 ? "up to " + maxTargets + " at once" : "every creature in reach"));
		latch(player, r);
		sync(player);
		return null;
	}

	/** Every tick: drain, let go of what got away, end frenzies that ran out, and age the meters. */
	public void tick() {
		tick++;
		for (Map.Entry<UUID, Raging> e : List.copyOf(raging.entrySet())) {
			Player p = plugin.getServer().getPlayer(e.getKey());
			if (p == null) {
				raging.remove(e.getKey());
				continue;
			}
			Raging r = e.getValue();
			if (tick >= r.endAt || p.isDead() || !p.isValid()) {
				end(p, true);
				continue;
			}
			release(p, r);
			hover.tick(p, tick, r.stats.hover());
			if ((tick - r.start) % 5 == 0) latch(p, r);
			if ((tick - r.start) % DRAIN_INTERVAL == DRAIN_INTERVAL - 1) drain(p, r);
			if (tick % 2 == 0) {
				for (LivingEntity t : living(p, r)) plugin.effects().rageTether(p, t, tick, plugin.skin().tether(p, t, tick));
			}
			// tendrils for players with the mod: on a change, and now and then for anyone who just came in sight
			if (r.targetsChanged || tick % 20 == 0) {
				r.targetsChanged = false;
				plugin.channel().broadcastRageDrain(p, ids(p, r), hover.hovering(p));
			}
			if ((tick - r.start) % 20 == 0) sync(p);
		}
		if (tick % 5 == 0 && !hits.isEmpty()) {
			long now = System.currentTimeMillis();
			for (Iterator<Map.Entry<UUID, ArrayDeque<Hit>>> it = hits.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry<UUID, ArrayDeque<Hit>> e = it.next();
				prune(e.getValue(), now);
				Player p = plugin.getServer().getPlayer(e.getKey());
				if (e.getValue().isEmpty()) it.remove();
				if (p != null) syncIfChanged(p);
			}
		}
	}

	/** Latch onto the creatures in reach and in sight (up to the most at once, when there is a most). */
	private void latch(Player player, Raging r) {
		if (maxTargets > 0 && r.targets.size() >= maxTargets) return;
		Location at = player.getLocation();
		double reach = r.stats.radius() * r.stats.radius();
		List<LivingEntity> found = new ArrayList<>();
		// the search is a box: its corners are cut off, so the reach is the same all round
		for (LivingEntity e : at.getWorld().getNearbyLivingEntities(at, r.stats.radius())) {
			if (!r.targets.containsKey(e.getUniqueId()) && e.getLocation().distanceSquared(at) <= reach && canTarget(player, r, e)
					&& player.hasLineOfSight(e)) {
				found.add(e);
			}
		}
		// whoever hit you first, then the nearest
		found.sort(Comparator.comparing((LivingEntity e) -> !r.attackers.contains(e.getUniqueId()))
				.thenComparingDouble(e -> e.getLocation().distanceSquared(at)));
		for (LivingEntity e : found) {
			if (maxTargets > 0 && r.targets.size() >= maxTargets) break;
			r.targets.put(e.getUniqueId(), tick);
			r.caught++;
			r.targetsChanged = true;
		}
	}

	/** Let go of creatures that died, left, or went too far. */
	private void release(Player player, Raging r) {
		double far = (r.stats.radius() + RELEASE_MARGIN) * (r.stats.radius() + RELEASE_MARGIN);
		for (Iterator<UUID> it = r.targets.keySet().iterator(); it.hasNext(); ) {
			Entity e = plugin.getServer().getEntity(it.next());
			boolean keep = e instanceof LivingEntity l && l.isValid() && !l.isDead() && l.getWorld() == player.getWorld()
					&& l.getLocation().distanceSquared(player.getLocation()) <= far && (!(l instanceof Player p) || drainable(p));
			if (!keep) {
				it.remove();
				r.targetsChanged = true;
			}
		}
	}

	/** One drain hit on every latched creature. */
	private void drain(Player player, Raging r) {
		DamageSource step = DamageSource.builder(DamageType.GENERIC).withCausingEntity(player).withDirectEntity(player).build();
		DamageSource kill = DamageSource.builder(DamageType.GENERIC_KILL).withCausingEntity(player).withDirectEntity(player).build();
		for (LivingEntity t : living(player, r)) {
			double amount = r.stats.damage() * (isLesser(player, t) ? lesser : 1);
			if (amount <= 0) continue;
			if (t instanceof Player victim) {
				// players: drained, never killed
				amount = Math.min(amount, victim.getHealth() - playerFloor);
				if (amount < 0.05) continue;
				double before = victim.getHealth() + victim.getAbsorptionAmount();
				victim.damage(amount, step);
				r.drained += Math.max(0, before - victim.getHealth() - victim.getAbsorptionAmount());
				continue;
			}
			double before = t.getHealth() + t.getAbsorptionAmount();
			if (before > amount) {
				t.damage(amount, step);
				r.drained += Math.max(0, before - (t.isDead() ? 0 : t.getHealth() + t.getAbsorptionAmount()));
				continue;
			}
			// the last of it: a kill by the player, so biomass and morphs come through the usual kill handling
			absorbing = t.getUniqueId();
			absorbShown = false;
			absorbingFor = r;
			try {
				t.damage(1_000_000, kill);
			} finally {
				absorbing = null;
				absorbingFor = null;
			}
			if (t.isDead()) {
				r.drained += before;
				r.taken++;
				if (report) {
					Integer caughtAt = r.targets.get(t.getUniqueId());
					report(player, t.getName() + " (" + SurvivalMode.number(maxHealth(t)) + " health) absorbed " + seconds(tick - r.start) + " in, after "
							+ seconds(caughtAt == null ? 0 : tick - caughtAt) + " held");
				}
				if (!absorbShown) plugin.morphs().playAcquisition(player, t, false); // nothing new: still taken in
				r.targets.remove(t.getUniqueId());
				r.targetsChanged = true;
			}
		}
	}

	/** Called for every absorption shown: the kill handling already showed this frenzy's kill being absorbed. */
	public void noteAcquisition(Entity victim, boolean newMorph) {
		if (absorbing == null || !absorbing.equals(victim.getUniqueId())) return;
		absorbShown = true;
		if (newMorph && absorbingFor != null) absorbingFor.newMorphs++;
	}

	/** The latched creatures that are still there. */
	private List<LivingEntity> living(Player player, Raging r) {
		List<LivingEntity> out = new ArrayList<>(r.targets.size());
		for (UUID id : List.copyOf(r.targets.keySet())) {
			if (plugin.getServer().getEntity(id) instanceof LivingEntity l && l.isValid() && !l.isDead()) out.add(l);
		}
		return out;
	}

	private List<Integer> ids(Player player, Raging r) {
		List<Integer> out = new ArrayList<>();
		for (LivingEntity l : living(player, r)) out.add(l.getEntityId());
		return out;
	}

	/** Whether a frenzy may drain this creature or player. */
	private boolean canTarget(Player player, Raging r, LivingEntity e) {
		if (e == player || e.isDead() || !e.isValid() || e.isInvulnerable() || e.hasMetadata("NPC")) return false;
		// the player's own mount and riders
		if (e.equals(player.getVehicle()) || player.getPassengers().contains(e) || e.getPassengers().contains(player)) return false;
		if (e instanceof Player p) return players && drainable(p) && (!enemiesOnly || r.attackers.contains(p.getUniqueId()));
		if (!(e instanceof Mob m)) return false; // armour stands and the like
		if (m instanceof Tameable pet && pet.isTamed() && player.getUniqueId().equals(pet.getOwnerUniqueId())) return false; // your own pets
		// made someone's own with a saddle, a harness or armour (the game doesn't record whose)
		if (wears(m, EquipmentSlot.SADDLE) || wears(m, EquipmentSlot.BODY)) return false;
		if (!enemiesOnly || r.attackers.contains(m.getUniqueId()) || player.equals(m.getTarget())) return true;
		return m instanceof Enemy && !NEUTRAL.contains(m.getType().getKey().toString());
	}

	/** Something in a creature's saddle or body slot (horse, wolf and nautilus armour, a llama's carpet, a harness). */
	private static boolean wears(LivingEntity e, EquipmentSlot slot) {
		if (!e.canUseEquipmentSlot(slot)) return false;
		EntityEquipment equipment = e.getEquipment();
		return equipment != null && !equipment.getItem(slot).isEmpty();
	}

	private boolean drainable(Player p) {
		return (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) && !p.isInvulnerable() && !p.isDead()
				&& p.getHealth() > playerFloor;
	}

	/** Drained at the lesser rate: bosses, villagers, name-tagged creatures, other players' pets, and players. */
	private boolean isLesser(Player player, LivingEntity e) {
		if (e instanceof Player) return true;
		if (lesserTypes.contains(e.getType().getKey().toString())) return true;
		if (lesserNamed && e.customName() != null) return true;
		return lesserPets && e instanceof Tameable pet && pet.isTamed() && !player.getUniqueId().equals(pet.getOwnerUniqueId());
	}

	/** End a frenzy: let go of everything, start the wait, and sum it up ({@code summary}) on the action bar. */
	private void end(Player player, boolean summary) {
		Raging r = raging.remove(player.getUniqueId());
		if (r == null) return;
		hover.stop(player, true);
		if (report) {
			StringBuilder line = new StringBuilder("over after " + seconds(tick - r.start) + ": held " + r.caught + ", absorbed " + r.taken + ", drained "
					+ SurvivalMode.number(r.drained) + " health");
			if (survival()) line.append(", +").append(SurvivalMode.number(plugin.survival().data(player).biomass - r.biomassBefore)).append(" biomass");
			for (LivingEntity t : living(player, r)) {
				line.append("; ").append(t.getName()).append(" left at ").append(SurvivalMode.number(t.getHealth())).append('/')
						.append(SurvivalMode.number(maxHealth(t)));
			}
			report(player, line.toString());
		}
		var data = plugin.store().get(player.getUniqueId());
		data.rageReadyAt = System.currentTimeMillis() + cooldownSeconds * 1000L;
		plugin.store().markDirty(player.getUniqueId());
		plugin.channel().broadcastRageDrain(player, List.of(), false);
		hits.remove(player.getUniqueId());
		shownHits.remove(player.getUniqueId());
		sync(player);
		if (!summary || !player.isOnline()) return;
		if (r.taken == 0) {
			player.sendActionBar(Component.text("Frenzy over.", NamedTextColor.GRAY));
			return;
		}
		StringBuilder what = new StringBuilder(" absorbed " + r.taken + (r.taken == 1 ? " creature" : " creatures"));
		if (survival()) {
			double gained = plugin.survival().data(player).biomass - r.biomassBefore;
			if (gained > 0) what.append(", +").append(SurvivalMode.number(gained)).append(" biomass");
		}
		if (r.newMorphs > 0) what.append(", ").append(r.newMorphs).append(r.newMorphs == 1 ? " new morph" : " new morphs");
		player.sendActionBar(Component.text(NAME + ":", NamedTextColor.RED).append(Component.text(what.toString(), NamedTextColor.GRAY)));
	}

	/** abilities.yml rage.report: a line to the console and the player for balance testing. */
	private void report(Player player, String text) {
		plugin.getLogger().info("[Frenzy report] " + player.getName() + ": " + text);
		if (player.isOnline()) player.sendMessage(Component.text("[Frenzy report] " + text, NamedTextColor.GRAY));
	}

	private static String seconds(int ticks) {
		return SurvivalMode.number(Math.round(ticks / 2.0) / 10.0) + " s";
	}

	private static double maxHealth(LivingEntity e) {
		AttributeInstance max = e.getAttribute(Attribute.MAX_HEALTH);
		return max == null ? e.getHealth() : max.getValue();
	}

	/** Tell a modded player their meter (nothing to the others; they get the action bar). */
	public void sync(Player player) {
		String why = unavailable(player);
		Raging r = raging.get(player.getUniqueId());
		int wait = cooldownSeconds * 20;
		long left = (cooldownLeftMillis(player) + 49) / 50;
		int rageLeft = r == null ? 0 : Math.max(0, r.endAt - tick);
		int length = r != null ? r.stats.ticks() : stats(level(player)).ticks();
		plugin.channel().sendRage(player, why == null, shown(player), hitsNeeded, (int) Math.min(left, wait), wait, r != null, rageLeft, length);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		end(event.getPlayer(), false);
		hits.remove(event.getPlayer().getUniqueId());
		shownHits.remove(event.getPlayer().getUniqueId());
		chargedBy.remove(event.getPlayer().getUniqueId()); // the charge itself is saved with the player
	}

	@EventHandler
	public void onDeath(PlayerDeathEvent event) {
		end(event.getEntity(), false);
	}

	@EventHandler
	public void onWorld(PlayerChangedWorldEvent event) {
		end(event.getPlayer(), true);
	}

	/** Shutdown, reload with the frenzy switched off: every frenzy ends. */
	public void releaseAll() {
		hover.releaseAll();
		for (UUID id : List.copyOf(raging.keySet())) {
			Player p = plugin.getServer().getPlayer(id);
			if (p != null) end(p, false);
			else raging.remove(id);
		}
		chargedBy.clear();
		hits.clear();
	}
}
