package dev.morphreborn.paper.survival;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.ability.MorphMakeup;
import dev.morphreborn.paper.data.SurvivalData;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * Biomass passives (8.0.0; 2026-11-15): abilities bought in the upgrade tree (survival.yml upgrades passive_*) and
 * then switched on and off at will, in the menu's Passives page or with {@code /morph passive [id] [on|off]}. The state is kept
 * per player (SurvivalData.passivesOff: the bought ones they switched off).
 * <p>
 * Billing (2026-11-16): a passive uses no biomass until it acts, and the cost is the passive's own
 * ({@link PassiveSettings}, survival.yml passives:). Triggered ones are billed for each thing they do (Fire Shedding for each
 * second of fire it stops, Antitoxin for each effect it stops, Scavenger for each item it fetches); acting ones drain only in the
 * runs in which they are doing something (Night Eyes in the dark, Gills under water, Tendril Sense with a creature in range,
 * Sustenance for each point of hunger it fills); Borrowed Scent drains the whole time it is on and your body has you and a
 * creature in it (your own body with a creature lent to it, or a creature with your own head; a body of creatures alone is
 * Camouflage's, your own body with nothing borrowed has nothing to hide, and a creature that never targets players, like a cow,
 * has nothing to be spared: all do nothing and cost nothing, as decided 2026-11-17). A passive that can't pay doesn't act: nothing is spent, and
 * the fire burns, the poison poisons. Every spend is all of it or nothing, through {@link SurvivalMode#armSpend}.
 * <ul>
 *   <li>Sustenance keeps you fed. Night Eyes: night vision, in the dark. Gills: air under water. Fire Shedding: fire and lava
 *       can't burn you. Antitoxin: poison, wither, hunger and nausea wear off at once.</li>
 *   <li>Tendril Sense: the creatures round you glow, for you alone (with packetevents; without it, nothing).</li>
 *   <li>Scavenger: small tendrils fetch the items lying round you, further at each level.</li>
 *   <li>Borrowed Scent: creatures of the kind lent to your own body (or on your own head) leave you alone, as Camouflage does for a
 *       body of creatures: they drop you as a target and stop hitting you, until you strike them.</li>
 * </ul>
 * Survival Mode only (Classic has no biomass to spend): nothing here runs unless Survival is the mode.
 */
public final class Passives implements Listener {
	public static final String SUSTENANCE = PassiveSettings.SUSTENANCE, NIGHT_EYES = PassiveSettings.NIGHT_EYES, GILLS = PassiveSettings.GILLS,
			FIRE_SHEDDING = PassiveSettings.FIRE_SHEDDING, ANTITOXIN = PassiveSettings.ANTITOXIN, TENDRIL_SENSE = PassiveSettings.TENDRIL_SENSE,
			SCAVENGER = PassiveSettings.SCAVENGER, BORROWED_SCENT = PassiveSettings.BORROWED_SCENT;
	/** In the order the command and the menu list them. */
	public static final List<String> ALL = PassiveSettings.ALL;
	/** How often the passives run (ticks). */
	static final int EVERY = 10;
	/** Effects are given for this long and topped up each run, so they never run out while on (night vision blinks below 10 s). */
	private static final int EFFECT_TICKS = 260;
	/** "For each second": fire and a re-applied effect are billed once in this many ticks. */
	private static final int WINDOW = 20;
	/** A paid-for item entity is remembered this long: an item despawns after 5 minutes. */
	private static final long ITEM_LIFE = 6000;
	private static final Set<EntityDamageEvent.DamageCause> FIRE_CAUSES = EnumSet.of(EntityDamageEvent.DamageCause.FIRE, EntityDamageEvent.DamageCause.FIRE_TICK,
			EntityDamageEvent.DamageCause.LAVA, EntityDamageEvent.DamageCause.HOT_FLOOR, EntityDamageEvent.DamageCause.CAMPFIRE);
	private static final List<PotionEffectType> CLEANSED = List.of(PotionEffectType.POISON, PotionEffectType.WITHER, PotionEffectType.HUNGER,
			PotionEffectType.NAUSEA);

	/** What a passive is doing for a player, as the menu's toggle pane and /morph passive tell it. */
	public enum State { NOT_BOUGHT, OFF, RUNNING, IDLE, BROKE }

	/** A state the last run (or trigger) set, good until {@code until}. */
	private record Live(State state, long until) {}

	private final MorphRebornPlugin plugin;
	/** Ticks since start (one per call of {@link #tick}): the clock of the windows and the states. */
	private long now;
	private final Map<UUID, Map<String, Live>> live = new HashMap<>();
	private final Map<UUID, Map<String, PassiveMeter>> meters = new HashMap<>();
	private final Map<UUID, DarkRule> darks = new HashMap<>();
	/** The creatures each player's Tendril Sense made glow, to put back when they leave it. */
	private final Map<UUID, Set<Integer>> sensed = new HashMap<>();

	public Passives(MorphRebornPlugin plugin) {
		this.plugin = plugin;
	}

	// ------------------------------------------------------------------ settings (survival.yml passives)

	/** {@code id}'s settings: survival.yml's entry over the built-in defaults (a stale file still bills). */
	PassiveSettings.Entry settings(String id) {
		return PassiveSettings.of(id, plugin.survival().config().passives.get(id));
	}

	// ------------------------------------------------------------------ owned, on, off

	/** True once {@code id} is bought. */
	public boolean owns(Player p, String id) {
		return plugin.survival().level(p, id) >= 1;
	}

	/** True when {@code id} is bought and not switched off. */
	public boolean on(Player p, String id) {
		return owns(p, id) && !plugin.survival().data(p).passivesOff.contains(id);
	}

	/** Switch {@code id} on or off for {@code p}; false when they haven't bought it. */
	public boolean set(Player p, String id, boolean on) {
		if (!owns(p, id)) return false;
		SurvivalData d = plugin.survival().data(p);
		if (on) d.passivesOff.remove(id);
		else d.passivesOff.add(id);
		plugin.store().markDirty(p.getUniqueId());
		if (!on) {
			stop(p, id);
			mark(p, id, State.IDLE, 0);
		}
		return true;
	}

	/** What {@code id} is doing for {@code p} now. */
	public State state(Player p, String id) {
		if (!owns(p, id)) return State.NOT_BOUGHT;
		if (!on(p, id)) return State.OFF;
		Map<String, Live> m = live.get(p.getUniqueId());
		Live l = m == null ? null : m.get(id);
		return l != null && l.until() > now ? l.state() : State.IDLE;
	}

	/** Every passive's state for {@code p}, in the menu's order (what S_PASSIVES lists; the ones not bought are left out there). */
	public Map<String, State> states(Player p) {
		Map<String, State> out = new LinkedHashMap<>();
		for (String id : ALL) out.put(id, state(p, id));
		return out;
	}

	private void mark(Player p, String id, State state, long ticks) {
		live.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(id, new Live(state, now + ticks));
	}

	private PassiveMeter meter(Player p, String id) {
		return meters.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).computeIfAbsent(id, k -> new PassiveMeter());
	}

	/** Survival is the mode and the player has the Biomass ability: the only time a passive does anything. */
	private boolean eligible(Player p) {
		SurvivalMode s = plugin.survival();
		return s.active() && s.isUnlocked(p) && p.getGameMode() != GameMode.SPECTATOR;
	}

	// ------------------------------------------------------------------ paying

	/** Pays {@code milli} for a passive: all of it or nothing (and nothing at all in creative or outside Survival). */
	private boolean pay(Player p, long milli) {
		if (milli <= 0 || plugin.survival().armsFree(p)) return true;
		return plugin.survival().armSpend(p, milli, false).paid();
	}

	/** What the player can spend, for the passives that restore as much as the balance covers. */
	private long balance(Player p) {
		return plugin.survival().armsFree(p) ? Long.MAX_VALUE / 4 : plugin.survival().armBalanceMilli(p);
	}

	/**
	 * A "for each second" bill: the first event of a window pays and opens it, the rest of the window is free. False (and
	 * nothing spent) when the balance can't pay, which is the passive not acting.
	 */
	private boolean chargeWindow(Player p, String id, String key, long milli) {
		PassiveMeter m = meter(p, id);
		if (!m.windowOpen(key, now, WINDOW)) {
			if (!pay(p, milli)) {
				mark(p, id, State.BROKE, 100);
				return false;
			}
			m.openWindow(key, now);
		}
		mark(p, id, State.RUNNING, 40);
		return true;
	}

	// ------------------------------------------------------------------ the tick

	/** Every tick from the plugin; the passives run every {@link #EVERY}. */
	public void tick() {
		now++;
		if (now % EVERY != 0) return;
		SurvivalMode s = plugin.survival();
		if (!s.active()) {
			if (!live.isEmpty() || !sensed.isEmpty()) for (Player p : Bukkit.getOnlinePlayers()) stopAll(p);
			live.clear();
			meters.clear();
			darks.clear();
			return;
		}
		for (Player p : Bukkit.getOnlinePlayers()) {
			boolean able = !p.isDead() && eligible(p);
			for (String id : ALL) {
				if (!able || !on(p, id)) {
					if (state(p, id) == State.RUNNING || sensed.containsKey(p.getUniqueId())) stop(p, id);
					continue;
				}
				run(p, id);
			}
			SurvivalMenu.refreshPassives(p);
			plugin.channel().syncPassives(p); // the client tree's toggles (coalesced there; modded clients from PassiveProtocol.SINCE only)
		}
		if (now % ITEM_LIFE == 0) forgetOffline();
	}

	/**
	 * Plugin disable (and what /reload does to it): take back what the passives gave while the players are still here: the
	 * Tendril Sense glow (metadata only a resend would clear, and the {@code sensed} map dies with this instance) and the
	 * hidden night vision (up to 13 s left on it). Nothing else of theirs is left running.
	 */
	public void shutdown() {
		for (Player p : Bukkit.getOnlinePlayers()) {
			try {
				stopAll(p);
			} catch (RuntimeException e) {
				plugin.getLogger().warning("Passives: couldn't take back " + p.getName() + "'s passives on disable: " + e);
			}
		}
		live.clear();
		meters.clear();
		darks.clear();
		sensed.clear();
	}

	/** Offline players' Scavenger memory (kept across a relog, see {@link #onQuit}) is dropped once every item in it has despawned. */
	private void forgetOffline() {
		meters.entrySet().removeIf(en -> Bukkit.getPlayer(en.getKey()) == null && en.getValue().values().stream().allMatch(m -> m.prune(now)));
	}

	private void run(Player p, String id) {
		PassiveSettings.Entry e = settings(id);
		boolean constant = e.billing() == PassiveSettings.Billing.CONSTANT;
		switch (id) {
			case SUSTENANCE -> sustenance(p, e);
			case NIGHT_EYES -> {
				boolean dark = constant || dark(p, e);
				drained(p, id, e, dark && !realEffect(p, PotionEffectType.NIGHT_VISION), () -> effect(p, PotionEffectType.NIGHT_VISION));
			}
			case GILLS -> drained(p, id, e, p.isUnderWater() && !coveredBreathing(p), () -> p.setRemainingAir(p.getMaximumAir()));
			case TENDRIL_SENSE -> {
				double r = e.num("radius", 24);
				Set<Integer> near = nearby(p, r);
				drained(p, id, e, constant || !near.isEmpty(), () -> glow(p, r, near));
			}
			// idle (no bill) for a creature that never targets players anyway, as a cow's scent spares nothing (2026-11-17)
			case BORROWED_SCENT -> drained(p, id, e, plugin.abilities().scentHasWork(p), () -> {});
			case ANTITOXIN -> sweep(p);
			case SCAVENGER -> scavenge(p, e);
			default -> {}
		}
	}

	/**
	 * A drain a minute for a passive that is {@code acting} this run: billed (exactly, in milli) only for the runs in which it
	 * is, and then {@code act} does its work. Idle: nothing accrues and what it gave is taken back. Not paid: the same, and
	 * the state says why.
	 */
	private void drained(Player p, String id, PassiveSettings.Entry e, boolean acting, Runnable act) {
		PassiveMeter m = meter(p, id);
		if (!acting) {
			m.stopDrain();
			mark(p, id, State.IDLE, 2 * EVERY);
			release(p, id);
			return;
		}
		long due = m.drain(e.drainMilliPerMinute(), EVERY);
		// a run too short to owe a whole milli still needs some biomass in hand, or a broke player would run for free
		boolean paid = due > 0 ? pay(p, due) : balance(p) > 0;
		if (!paid) {
			mark(p, id, State.BROKE, 2 * EVERY);
			release(p, id);
			return;
		}
		mark(p, id, State.RUNNING, 2 * EVERY);
		act.run();
	}

	/** True when the form already gives the player this effect on its own, or they drank it: not ours to give or to bill. */
	private static boolean realEffect(Player p, PotionEffectType type) {
		PotionEffect current = p.getPotionEffect(type);
		return current != null && !(current.isAmbient() && !current.hasIcon());
	}

	/** True when something else already lets the player breathe under water, so Gills has nothing to do. */
	private boolean coveredBreathing(Player p) {
		return p.hasPotionEffect(PotionEffectType.WATER_BREATHING) || p.hasPotionEffect(PotionEffectType.CONDUIT_POWER) || plugin.abilities().coversDrowning(p);
	}

	// ------------------------------------------------------------------ Sustenance

	/** Fills the hunger a point a run, paid for each point (a full stomach costs nothing; see {@link SustenanceMath}). */
	private void sustenance(Player p, PassiveSettings.Entry e) {
		int food = p.getFoodLevel();
		if (food >= SustenanceMath.MAX_FOOD) {
			mark(p, SUSTENANCE, State.IDLE, 2 * EVERY);
			return;
		}
		SustenanceMath.Plan plan = SustenanceMath.plan(food, p.getSaturation(), e.num("saturation-floor", 5), balance(p), e.milli("per-food-point"), e.milli("per-saturation"));
		if (!plan.any() || !pay(p, plan.costMilli())) {
			mark(p, SUSTENANCE, State.BROKE, 2 * EVERY);
			return;
		}
		p.setFoodLevel(Math.min(SustenanceMath.MAX_FOOD, food + plan.food()));
		if (plan.saturation() > 0) p.setSaturation((float) Math.min(p.getFoodLevel(), p.getSaturation() + plan.saturation()));
		mark(p, SUSTENANCE, State.RUNNING, 2 * EVERY);
	}

	// ------------------------------------------------------------------ Night Eyes

	/** Whether the player is in the dark (see {@link DarkRule}); the light is read at their eyes. */
	private boolean dark(Player p, PassiveSettings.Entry e) {
		World w = p.getWorld();
		Block eyes = p.getEyeLocation().getBlock();
		boolean night = w.getEnvironment() == World.Environment.NORMAL && w.getTime() >= 13000 && w.getTime() < 23000;
		return darks.computeIfAbsent(p.getUniqueId(), k -> new DarkRule()).update(now, eyes.getLightFromBlocks(), eyes.getLightFromSky(), night, w.isThundering(),
				(int) e.num("dark-light-level", 6));
	}

	/**
	 * The form's own effects were just re-given (a borrowed night-vision bat dropped): if Night Eyes has the player in the
	 * dark and no real night vision is on them, hand its hidden effect back now rather than at the next run, which left a
	 * blink of darkness. Not billed: the next run bills as it always does.
	 */
	public void restore(Player p) {
		if (!eligible(p) || !on(p, NIGHT_EYES) || realEffect(p, PotionEffectType.NIGHT_VISION)) return;
		PassiveSettings.Entry e = settings(NIGHT_EYES);
		if (e.billing() == PassiveSettings.Billing.CONSTANT || dark(p, e)) effect(p, PotionEffectType.NIGHT_VISION);
	}

	/** A hidden effect of ours (ambient, no particles, no icon), topped up each run. */
	private static void effect(Player p, PotionEffectType type) {
		PotionEffect current = p.getPotionEffect(type);
		if (current != null && !(current.isAmbient() && !current.hasIcon())) return; // a real potion: leave it
		if (current == null || current.getDuration() < EFFECT_TICKS - EVERY * 2) p.addPotionEffect(new PotionEffect(type, EFFECT_TICKS, 0, true, false, false));
	}

	private static void unEffect(Player p, PotionEffectType type) {
		PotionEffect current = p.getPotionEffect(type);
		if (current != null && current.isAmbient() && !current.hasIcon()) p.removePotionEffect(type);
	}

	/** {@code id} has nothing to do this run, or can't pay: take back what it gave (the dark rule keeps its hold, so a torch's edge doesn't flicker). */
	private void release(Player p, String id) {
		switch (id) {
			case NIGHT_EYES -> unEffect(p, PotionEffectType.NIGHT_VISION);
			case TENDRIL_SENSE -> unsense(p);
			default -> {}
		}
	}

	/** {@code id} stopped (switched off, the player gone dark, or the mode changed): take back what it gave and forget the dark rule's answer. */
	private void stop(Player p, String id) {
		release(p, id);
		if (id.equals(NIGHT_EYES)) {
			DarkRule d = darks.get(p.getUniqueId());
			if (d != null) d.reset();
		}
	}

	private void stopAll(Player p) {
		for (String id : ALL) stop(p, id);
	}

	// ------------------------------------------------------------------ Fire Shedding

	/**
	 * Fire, lava and the like can't burn you: the damage is cancelled and you are put out, and the first of each second of it is
	 * paid for. HIGHEST, after the morph abilities' own (HIGH, which cancel for a form that is fire immune): a cancelled event
	 * is not ours, so a blaze form is never billed. A balance that can't pay lets the damage through.
	 */
	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onDamage(EntityDamageEvent event) {
		if (!(event.getEntity() instanceof Player p) || !FIRE_CAUSES.contains(event.getCause())) return;
		// final damage is 0 for fire the form already shrugs off (nothing to shed or bill), but golden hearts take the whole hit
		// off it too: with absorption the base damage says whether the fire was really burning them
		if (event.getFinalDamage() <= 0 && !(p.getAbsorptionAmount() > 0 && event.getDamage() > 0)) return;
		if (!eligible(p) || !on(p, FIRE_SHEDDING)) return;
		// a zombie's or a skeleton's daylight burning is a drawback "always on by design" (abilities.yml passive.sunburn): shedding
		// it would buy the drawback off for 0.5 a second (fix round, 2026-11-16); the fire blocks and lava still count
		if (event.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK && plugin.abilities().burnsInSunlight(p)) return;
		if (!chargeWindow(p, FIRE_SHEDDING, "fire", settings(FIRE_SHEDDING).milli("per-second"))) return;
		event.setCancelled(true);
		p.setFireTicks(0);
	}

	// ------------------------------------------------------------------ Antitoxin

	/** The four effects are cancelled as they are applied, the first of each kind in a second paid for. HIGH, after the morph traits' own check. */
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onPotion(EntityPotionEffectEvent event) {
		if (!(event.getEntity() instanceof Player p) || event.getNewEffect() == null) return;
		EntityPotionEffectEvent.Action action = event.getAction();
		if (action != EntityPotionEffectEvent.Action.ADDED && action != EntityPotionEffectEvent.Action.CHANGED) return;
		PotionEffectType type = event.getNewEffect().getType();
		if (!CLEANSED.contains(type) || !eligible(p) || !on(p, ANTITOXIN)) return;
		if (chargeWindow(p, ANTITOXIN, type.getKey().toString(), settings(ANTITOXIN).milli("per-effect"))) event.setCancelled(true);
	}

	/** Effects that were already on (the passive was switched on after them, or one slipped past), each paid for as it is removed. */
	private void sweep(Player p) {
		long cost = settings(ANTITOXIN).milli("per-effect");
		for (PotionEffectType t : CLEANSED) {
			if (p.hasPotionEffect(t) && chargeWindow(p, ANTITOXIN, t.getKey().toString(), cost)) p.removePotionEffect(t);
		}
	}

	// ------------------------------------------------------------------ Tendril Sense

	/** The living, non-player creatures within {@code r} of the player (what the sense would show). */
	private Set<Integer> nearby(Player p, double r) {
		Set<Integer> out = new HashSet<>();
		for (Entity e : around(p, r)) {
			// a locked shulker's hidden helper is the player's own shell, not a creature near them
			if (e instanceof LivingEntity && !(e instanceof Player) && e.isValid() && !plugin.shulkerLock().isHelper(e)) out.add(e.getEntityId());
		}
		return out;
	}

	/**
	 * Entities within {@code r} of the player's own position. Entity#getNearbyEntities grows the player's bounding box by
	 * {@code r}, so a giant would sense and fetch from its own width further out than anyone else.
	 */
	private static List<Entity> around(Player p, double r) {
		org.bukkit.Location at = p.getLocation();
		List<Entity> out = new java.util.ArrayList<>();
		for (Entity e : p.getWorld().getNearbyEntities(at, r, r, r)) {
			if (e == p) continue;
			org.bukkit.Location l = e.getLocation();
			if (Math.abs(l.getX() - at.getX()) <= r && Math.abs(l.getY() - at.getY()) <= r && Math.abs(l.getZ() - at.getZ()) <= r) out.add(e);
		}
		return out;
	}

	private void glow(Player p, double r, Set<Integer> now) {
		Set<Integer> before = sensed.getOrDefault(p.getUniqueId(), Set.of());
		Map<Integer, Entity> byId = new LinkedHashMap<>();
		for (Entity e : p.getNearbyEntities(r + 8, r + 8, r + 8)) byId.put(e.getEntityId(), e);
		for (int id : now) SenseGlow.show(p, byId.get(id), true);
		for (int id : before) if (!now.contains(id) && byId.containsKey(id)) SenseGlow.show(p, byId.get(id), false);
		sensed.put(p.getUniqueId(), now);
	}

	private void unsense(Player p) {
		Set<Integer> before = sensed.remove(p.getUniqueId());
		if (before == null) return;
		for (Entity e : p.getNearbyEntities(64, 64, 64)) if (before.contains(e.getEntityId())) SenseGlow.show(p, e, false);
	}

	// ------------------------------------------------------------------ Scavenger

	/**
	 * Items lying within the level's reach are fetched to you by small tendrils, each item paid for once, however long it takes
	 * to reach you. An item the player has no room for is left alone, so a full inventory costs nothing.
	 */
	private void scavenge(Player p, PassiveSettings.Entry e) {
		int level = plugin.survival().level(p, SCAVENGER);
		double reach = e.perLevel("reach", level, 6);
		long perItem = e.milli("per-item");
		PassiveMeter m = meter(p, SCAVENGER);
		Vector to = p.getLocation().toVector().add(new Vector(0, 0.9, 0));
		int fetched = 0;
		// what the game's own pick-up takes for free: inside the body's box and a block around it (a giant's is wide)
		org.bukkit.util.BoundingBox pickUp = p.getBoundingBox().expand(1.0, 0.5, 1.0);
		for (Entity en : around(p, reach)) {
			if (!(en instanceof Item item) || item.getPickupDelay() > 0 || !item.isValid() || fetched >= 6) continue;
			Vector at = item.getLocation().toVector();
			double d = at.distance(to);
			if (d < 1.5 || d > reach || pickUp.contains(at)) continue;
			if (!canTake(p, item.getItemStack())) continue;
			String key = item.getUniqueId().toString();
			if (!m.paid(key, now)) {
				if (!pay(p, perItem)) {
					mark(p, SCAVENGER, State.BROKE, 100);
					return;
				}
				m.remember(key, now, ITEM_LIFE);
			}
			Vector pull = to.clone().subtract(at).normalize().multiply(Math.min(0.9, 0.25 + d * 0.06)).setY(Math.max(0.12, (to.getY() - at.getY()) * 0.12 + 0.1));
			item.setVelocity(pull);
			// the tendril reaching out to it (dark motes along the line: the client draws them as it draws the arm's)
			p.getWorld().spawnParticle(Particle.DUST, at.clone().add(to).multiply(0.5).toLocation(p.getWorld()), 3, 0.15, 0.15, 0.15, 0,
					new Particle.DustOptions(org.bukkit.Color.fromRGB(30, 10, 12), 0.9F));
			fetched++;
			mark(p, SCAVENGER, State.RUNNING, 40);
		}
	}

	/** True when the stack would fit: a free slot, or a stack of the same thing with room. */
	static boolean canTake(Player p, ItemStack stack) {
		org.bukkit.inventory.PlayerInventory inv = p.getInventory();
		if (inv.firstEmpty() != -1) return true;
		for (ItemStack s : inv.getStorageContents()) {
			if (s != null && s.isSimilar(stack) && s.getAmount() < s.getMaxStackSize()) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ Borrowed Scent

	/**
	 * Whether Borrowed Scent is paid up and acting for {@code p} now. The mobs it spares are decided in
	 * {@code AbilityManager.shouldIgnore} (new targets, a target they already have, and their hits, with the provoked rule, as
	 * Camouflage does): it only works for a body with you in it, as a body of creatures alone is Camouflage's job and neither
	 * acts nor bills here (2026-11-17; see {@link MorphMakeup}), and neither does a body whose creature never targets
	 * players (a cow): there is nothing for the scent to spare, so it idles (2026-11-17; {@link MorphMakeup#scentHasWork}).
	 */
	public boolean scentActive(Player p) {
		return state(p, BORROWED_SCENT) == State.RUNNING;
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		UUID id = event.getPlayer().getUniqueId();
		live.remove(id);
		// Scavenger's memory of the items it was paid for outlives the session, or a relog would bill the same item again
		// (forgetOffline drops it once those items have despawned); everything else of the meters goes
		Map<String, PassiveMeter> kept = meters.remove(id);
		PassiveMeter scav = kept == null ? null : kept.get(SCAVENGER);
		if (scav != null && !scav.prune(now)) meters.put(id, new HashMap<>(Map.of(SCAVENGER, scav)));
		darks.remove(id);
		sensed.remove(id);
	}

	// ------------------------------------------------------------------ the command

	/** Every passive's state, for the command. */
	public Map<String, String> describe(Player p) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String id : ALL) {
			if (!plugin.survival().config().upgrades.containsKey(id)) continue;
			out.put(id, switch (state(p, id)) {
				case NOT_BOUGHT -> "not bought";
				case OFF -> "off";
				case RUNNING -> "on";
				case IDLE -> "on (idle)";
				case BROKE -> "on (no biomass to run it)";
			});
		}
		return out;
	}

}
