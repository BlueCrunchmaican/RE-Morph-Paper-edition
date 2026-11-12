package dev.morphreborn.paper.ability;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.SemVer;
import dev.morphreborn.paper.survival.SurvivalConfig;
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
import org.jspecify.annotations.Nullable;

/**
 * Rage: evening the odds when a crowd turns on you. Take enough hits in a short time from more than one attacker (three
 * skeletons juggling you with arrows, a ring of zombies) and your rage is charged; it waits for you to set it off (the
 * Rage key with the mod, /morph rage without), and stays charged until you do. Then tendrils latch onto the creatures
 * around you and drain their health while they're still alive, and each one that dies is absorbed as an ordinary kill
 * of yours (biomass and a morph under the usual rules, the usual absorb animation). The ender dragon, the wither, the
 * warden, the elder guardian, villagers, wandering traders, name-tagged creatures and other players' pets are drained
 * more slowly ({@code lesser}); players are drained too but never killed; your own pets are left alone. Survival needs
 * the Rage upgrade. Settings: abilities.yml rage.
 * <p>
 * The idea comes from a picture on Morph's old CurseForge page (a pig with many mobs being absorbed into it at once); it
 * was never an ability in Morph.
 */
public final class Rage implements Listener {
	/** Ticks between drain steps: a creature ignores a second hit this soon after the first (its hurt cooldown). */
	private static final int DRAIN_INTERVAL = 10;
	/** A latched creature further away than the radius plus this is let go. */
	private static final double RELEASE_MARGIN = 2;
	/**
	 * Hostile kinds that leave you alone until provoked (or in daylight): drained in "enemies" mode only when they are
	 * after you, or one of them hit you.
	 */
	private static final Set<String> NEUTRAL = Set.of("minecraft:enderman", "minecraft:zombified_piglin", "minecraft:piglin", "minecraft:spider",
			"minecraft:cave_spider");

	private final MorphRebornPlugin plugin;

	private boolean enabled;
	private int hitsNeeded;
	private long windowMillis;
	private int minAttackers;
	private int durationTicks;
	private double radius;
	private int maxTargets;
	private double drainPerSecond;
	private boolean enemiesOnly;
	private double lesser;
	private final Set<String> lesserTypes = new HashSet<>();
	private boolean lesserNamed, lesserPets;
	private boolean players;
	private double playerFloor;
	private int cooldownSeconds;

	/** A hit taken: when, and who dealt it. */
	private record Hit(long at, UUID attacker) {}

	/** Hits each player took within the window, oldest first. Not saved: they last seconds. */
	private final Map<UUID, ArrayDeque<Hit>> hits = new HashMap<>();
	/** What the rage meter last showed each player, so a hit running out only sends a change. */
	private final Map<UUID, Integer> shownHits = new HashMap<>();
	/** Who charged each charged rage (fair game when it is set off, whoever hit you first). Not saved. */
	private final Map<UUID, Set<UUID>> chargedBy = new HashMap<>();

	/** A rage in progress. */
	private static final class Raging {
		final int start, endAt;
		/** Creatures latched onto, with the tick each was caught; in the order they were caught. */
		final Map<UUID, Integer> targets = new LinkedHashMap<>();
		/** Whoever hit the player shortly before or during the rage: always fair game. */
		final Set<UUID> attackers = new HashSet<>();
		int taken, newMorphs;
		double biomassBefore;
		boolean targetsChanged = true;

		Raging(int start, int endAt) {
			this.start = start;
			this.endAt = endAt;
		}
	}

	private final Map<UUID, Raging> raging = new HashMap<>();
	/** The creature a rage is killing right now, and whether the kill already showed it being absorbed. */
	private @Nullable UUID absorbing;
	private boolean absorbShown;
	private @Nullable Raging absorbingFor;
	private int tick;

	public Rage(MorphRebornPlugin plugin) {
		this.plugin = plugin;
	}

	public void load(@Nullable ConfigurationSection c) {
		enabled = c != null && c.getBoolean("enabled", true);
		hitsNeeded = c == null ? 6 : Math.clamp(c.getInt("hits", 6), 1, 100);
		windowMillis = Math.round((c == null ? 6 : Math.clamp(c.getDouble("window", 6), 1, 60)) * 1000);
		minAttackers = c == null ? 2 : Math.clamp(c.getInt("attackers", 2), 1, 20);
		durationTicks = (int) Math.round((c == null ? 8 : Math.clamp(c.getDouble("duration", 8), 1, 60)) * 20);
		radius = c == null ? 16 : Math.clamp(c.getDouble("radius", 16), 1, 32);
		maxTargets = c == null ? 8 : Math.clamp(c.getInt("max-targets", 8), 1, 32);
		drainPerSecond = c == null ? 5 : Math.clamp(c.getDouble("drain", 5), 0.1, 1000);
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
		if (!enabled) releaseAll();
	}

	/** The server offers rage at all (abilities.yml rage.enabled); told to the client mod in the handshake. */
	public boolean enabled() {
		return enabled;
	}

	private boolean survival() {
		return plugin.survival().active();
	}

	/** Why this player can't rage at all, or null if they can (charged or not). */
	public @Nullable String unavailable(Player player) {
		if (!enabled) return "Rage is switched off on this server.";
		if (!player.hasPermission("morphreborn.rage")) return "You are not allowed to rage.";
		if (survival() && plugin.survival().level(player, SurvivalConfig.RAGE) < 1) return "Rage needs the Rage upgrade: /morph upgrades";
		return null;
	}

	private long cooldownLeftMillis(Player player) {
		return Math.max(0, plugin.store().get(player.getUniqueId()).rageReadyAt - System.currentTimeMillis());
	}

	public boolean isRaging(Player player) {
		return raging.containsKey(player.getUniqueId());
	}

	/** True while a rage is killing {@code victim}: its kill is absorbed wherever it is (Survival's reach doesn't apply). */
	public boolean isRageKill(Entity victim) {
		return absorbing != null && absorbing.equals(victim.getUniqueId());
	}

	// ------------------------------------------------------------------ charging

	/** A hit that hurt a player: one toward their rage, if it came from a creature or another player. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onHurt(EntityDamageByEntityEvent event) {
		if (!enabled || !(event.getEntity() instanceof Player player) || event.getFinalDamage() <= 0) return;
		Entity cause = event.getDamageSource().getCausingEntity();
		if (cause == null) cause = event.getDamager();
		if (!(cause instanceof LivingEntity attacker) || attacker == player) return;
		Raging r = raging.get(player.getUniqueId());
		if (r != null) {
			r.attackers.add(attacker.getUniqueId()); // whoever hits you while you rage is fair game
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

	/** The rage is charged: it waits for the player (saved with them) until they set it off. */
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
			player.sendMessage(Component.text("Your rage is charged. /morph rage sets it off.", NamedTextColor.RED));
		}
		sync(player);
	}

	/** Whether the player's client draws the rage meter: the mod from 7.2.0 (older mods skip S_RAGE). */
	private boolean hasMeter(Player player) {
		if (!plugin.channel().isModded(player)) return false;
		String version = plugin.channel().clientVersion(player);
		return version != null && SemVer.compare(version, "7.2.0") >= 0;
	}

	/** Admin and testing: charge the player's rage. */
	public void fill(Player player) {
		if (!isCharged(player)) charge(player, hits.get(player.getUniqueId()));
	}

	/**
	 * The Rage key or /morph rage: set off a charged rage, or say why not (not charged yet, the wait after the last one,
	 * no upgrade...).
	 */
	public void trigger(Player player) {
		String why = unavailable(player);
		if (why == null && isRaging(player)) return;
		if (why == null && !isCharged(player)) why = notReady(player);
		if (why == null) why = start(player);
		if (why != null) player.sendActionBar(Component.text(why, NamedTextColor.GRAY));
	}

	private String notReady(Player player) {
		long wait = cooldownLeftMillis(player);
		if (wait > 0) return "Your rage is spent; hits count toward it again in " + (wait + 999) / 1000 + "s.";
		String window = windowMillis / 1000 + (windowMillis % 1000 == 0 ? "" : "." + windowMillis % 1000 / 100);
		return "Rage isn't charged: " + shown(player) + "/" + hitsNeeded + " hits. It takes " + hitsNeeded + " within " + window + "s"
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

	// ------------------------------------------------------------------ the rage

	/** Set off a rage now (charged, or an admin's /morph rage <player> go), using up the charge. Returns why not, or null. */
	public @Nullable String start(Player player) {
		String why = unavailable(player);
		if (why != null) return why;
		if (isRaging(player)) return player.getName() + " is already raging.";
		if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) return "You can't rage right now.";
		Raging r = new Raging(tick, tick + durationTicks);
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
		plugin.effects().rageStart(player);
		player.sendActionBar(Component.text("Rage!", NamedTextColor.RED, TextDecoration.BOLD));
		latch(player, r);
		sync(player);
		return null;
	}

	/** Every tick: drain, let go of what got away, end rages that ran out, and age the meters. */
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
			if ((tick - r.start) % 5 == 0) latch(p, r);
			if ((tick - r.start) % DRAIN_INTERVAL == DRAIN_INTERVAL - 1) drain(p, r);
			if (tick % 2 == 0) {
				for (LivingEntity t : living(p, r)) plugin.effects().rageTether(p, t, tick);
			}
			// tendrils for players with the mod: on a change, and now and then for anyone who just came in sight
			if (r.targetsChanged || tick % 20 == 0) {
				r.targetsChanged = false;
				plugin.channel().broadcastRageDrain(p, ids(p, r));
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

	/** Latch onto the nearest creatures in reach and in sight, up to the most at once. */
	private void latch(Player player, Raging r) {
		if (r.targets.size() >= maxTargets) return;
		Location at = player.getLocation();
		List<LivingEntity> found = new ArrayList<>();
		for (LivingEntity e : at.getWorld().getNearbyLivingEntities(at, radius)) {
			if (!r.targets.containsKey(e.getUniqueId()) && canTarget(player, r, e) && player.hasLineOfSight(e)) found.add(e);
		}
		// whoever hit you first, then the nearest
		found.sort(Comparator.comparing((LivingEntity e) -> !r.attackers.contains(e.getUniqueId()))
				.thenComparingDouble(e -> e.getLocation().distanceSquared(at)));
		for (LivingEntity e : found) {
			if (r.targets.size() >= maxTargets) break;
			r.targets.put(e.getUniqueId(), tick);
			r.targetsChanged = true;
		}
	}

	/** Let go of creatures that died, left, or went too far. */
	private void release(Player player, Raging r) {
		double far = (radius + RELEASE_MARGIN) * (radius + RELEASE_MARGIN);
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

	/** One drain step on every latched creature. */
	private void drain(Player player, Raging r) {
		DamageSource step = DamageSource.builder(DamageType.GENERIC).withCausingEntity(player).withDirectEntity(player).build();
		DamageSource kill = DamageSource.builder(DamageType.GENERIC_KILL).withCausingEntity(player).withDirectEntity(player).build();
		double base = drainPerSecond * DRAIN_INTERVAL / 20.0;
		for (LivingEntity t : living(player, r)) {
			double amount = base * (isLesser(player, t) ? lesser : 1);
			if (amount <= 0) continue;
			if (t instanceof Player victim) {
				// players: drained, never killed
				amount = Math.min(amount, victim.getHealth() - playerFloor);
				if (amount >= 0.05) victim.damage(amount, step);
				continue;
			}
			if (t.getHealth() + t.getAbsorptionAmount() > amount) {
				t.damage(amount, step);
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
				r.taken++;
				if (!absorbShown) plugin.morphs().playAcquisition(player, t, false); // nothing new: still taken in
				r.targets.remove(t.getUniqueId());
				r.targetsChanged = true;
			}
		}
	}

	/** Called for every absorption shown: the kill handling already showed this rage kill being absorbed. */
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

	/** Whether a rage may drain this creature or player. */
	private boolean canTarget(Player player, Raging r, LivingEntity e) {
		if (e == player || e.isDead() || !e.isValid() || e.isInvulnerable() || e.hasMetadata("NPC")) return false;
		// the player's own mount and riders
		if (e.equals(player.getVehicle()) || player.getPassengers().contains(e) || e.getPassengers().contains(player)) return false;
		if (e instanceof Player p) return players && drainable(p) && (!enemiesOnly || r.attackers.contains(p.getUniqueId()));
		if (!(e instanceof Mob m)) return false; // armour stands and the like
		if (m instanceof Tameable pet && pet.isTamed() && player.getUniqueId().equals(pet.getOwnerUniqueId())) return false; // your own pets
		if (!enemiesOnly || r.attackers.contains(m.getUniqueId()) || player.equals(m.getTarget())) return true;
		return m instanceof Enemy && !NEUTRAL.contains(m.getType().getKey().toString());
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

	/** End a rage: let go of everything, start the wait, and sum it up ({@code summary}) on the action bar. */
	private void end(Player player, boolean summary) {
		Raging r = raging.remove(player.getUniqueId());
		if (r == null) return;
		var data = plugin.store().get(player.getUniqueId());
		data.rageReadyAt = System.currentTimeMillis() + cooldownSeconds * 1000L;
		plugin.store().markDirty(player.getUniqueId());
		plugin.channel().broadcastRageDrain(player, List.of());
		hits.remove(player.getUniqueId());
		shownHits.remove(player.getUniqueId());
		sync(player);
		if (!summary || !player.isOnline()) return;
		if (r.taken == 0) {
			player.sendActionBar(Component.text("Rage over.", NamedTextColor.GRAY));
			return;
		}
		StringBuilder what = new StringBuilder(" absorbed " + r.taken + (r.taken == 1 ? " creature" : " creatures"));
		if (survival()) {
			double gained = plugin.survival().data(player).biomass - r.biomassBefore;
			if (gained > 0) what.append(", +").append(dev.morphreborn.paper.survival.SurvivalMode.number(gained)).append(" biomass");
		}
		if (r.newMorphs > 0) what.append(", ").append(r.newMorphs).append(r.newMorphs == 1 ? " new morph" : " new morphs");
		player.sendActionBar(Component.text("Rage:", NamedTextColor.RED).append(Component.text(what.toString(), NamedTextColor.GRAY)));
	}

	/** Tell a modded player their rage meter (nothing to the others; they get the action bar). */
	public void sync(Player player) {
		String why = unavailable(player);
		Raging r = raging.get(player.getUniqueId());
		int wait = cooldownSeconds * 20;
		long left = (cooldownLeftMillis(player) + 49) / 50;
		int rageLeft = r == null ? 0 : Math.max(0, r.endAt - tick);
		plugin.channel().sendRage(player, why == null, shown(player), hitsNeeded, (int) Math.min(left, wait), wait, r != null, rageLeft, durationTicks);
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

	/** Shutdown, reload with rage switched off: every rage ends. */
	public void releaseAll() {
		for (UUID id : List.copyOf(raging.keySet())) {
			Player p = plugin.getServer().getPlayer(id);
			if (p != null) end(p, false);
			else raging.remove(id);
		}
		chargedBy.clear();
		hits.clear();
	}
}
