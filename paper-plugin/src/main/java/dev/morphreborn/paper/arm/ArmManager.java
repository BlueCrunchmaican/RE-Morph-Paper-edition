package dev.morphreborn.paper.arm;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.PluginSettings;
import dev.morphreborn.paper.arm.api.ArmAccessoryModule;
import dev.morphreborn.paper.arm.api.ArmContext;
import dev.morphreborn.paper.arm.api.ArmFxMsg;
import dev.morphreborn.paper.arm.api.ArmLookModule;
import dev.morphreborn.paper.arm.api.ArmService;
import dev.morphreborn.paper.arm.api.ArmSlotsMsg;
import dev.morphreborn.paper.arm.api.ArmSpecialModule;
import dev.morphreborn.paper.arm.api.ArmStateMsg;
import dev.morphreborn.paper.arm.api.ArmView;
import dev.morphreborn.paper.arm.api.ArsenalMsg;
import dev.morphreborn.paper.arm.api.SpecialCharge;
import dev.morphreborn.paper.arm.api.SpecialResult;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.ArmItemSpec;
import dev.morphreborn.paper.arm.rules.ArmItemSpecs;
import dev.morphreborn.paper.arm.rules.ArmLedger;
import dev.morphreborn.paper.arm.rules.ArmSlots;
import dev.morphreborn.paper.arm.rules.ArmTiers;
import dev.morphreborn.paper.arm.rules.ArmTuck;
import dev.morphreborn.paper.arm.rules.DeathLedger;
import dev.morphreborn.paper.arm.rules.HeldSlotWatch;
import dev.morphreborn.paper.arm.rules.JoinDeploy;
import dev.morphreborn.paper.arm.rules.SaidOnce;
import dev.morphreborn.paper.arm.rules.SessionRecord;
import dev.morphreborn.paper.morph.MorphManager;
import dev.morphreborn.paper.net.ClientChannel;
import dev.morphreborn.paper.tips.Lines;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;

/**
 * Arm forms (the Arsenal): the player's own arm reshaped into a tool or weapon of morph tendrils, held in the selected
 * hotbar slot while the item that was there waits in the player's data. This is the whole server side of it, loaded by
 * name (ArmModules.SERVICE) so the plugin runs the same without it.
 * <p>
 * The life of an arm (spec 3.3): it forms over ArmProtocol.FORM_TICKS (QUICKFORM_TICKS for the quick re-form of a scroll
 * onto a bound slot), stays formed, may reshape into another form in place, and goes back either by choice (a retract),
 * by running out of biomass (a melt), or at once when something forces it (scrolling away, an inventory screen opening,
 * death, leaving, a morph, a world change under a per-world inventory plugin, a command that acts on the held item, a
 * reload). Every way back writes the item it replaced into the slot and clears the saved record in the same tick, and
 * S_ARM is always sent before the slot changes, so a modded client knows what it is about to see.
 * <p>
 * Arm slots (2026-11-16): each form can be bound to one hotbar slot ({@link ArmSlots}, saved on the player),
 * and the binding stays after the player scrolls away. The bindings are only labels: the one session (the arm in the
 * held slot, with the item it replaced) works exactly as before, an unheld bound slot keeps its real item, and
 * scrolling onto a bound slot forms that slot's arm again (as does a join, a respawn, a world change, a screen closing,
 * a selection moved with no held event, and since 2026-11-17 the end of a transformation back to the own body, each said once if refused). Forming binds the held slot; a chosen retract unbinds
 * it. An admin's forced arm binds only what the player could form themselves, at no special level or cost.
 * <p>
 * A container screen open on the modded client tucks the arm (2026-11-16): the held slot gets its real item back so
 * the inventory shows it, but the session stays formed and drawn, with no arm item anywhere; closing the screen untucks it
 * ({@link ArmTuck}), stashing whatever the slot holds then.
 * <p>
 * Around that: the guards that keep the arm from going anywhere (ArmGuardListener, ArmAudit), the billing that makes
 * biomass its durability (ArmBilling), the specials of each form (loaded by name, so a form whose special isn't in the
 * build is "not built" and hidden everywhere), the accessories (the lantern, and since 8.0.0-beta.2 the shield, spyglass and
 * flint and steel, which may be on several at once, each its own module), and the commands.
 * <p>
 * The modules talk to this through {@link ArmContext}, implemented by an inner object: ArmService and ArmContext both
 * declare {@code tick()} with different return types, so one class can't be both.
 */
public final class ArmManager implements ArmService {
	/** Where an item goes that can't go back into the hand: dropped at the player's feet, or kept for their next join. */
	enum Overflow { DROP, PENDING }

	/**
	 * Why an arm is being formed: the player's choice (which binds the held slot to the form), the quick re-form of
	 * scrolling onto a bound slot or closing an inventory screen (the binding is already there), or an admin forcing it.
	 */
	enum Cause { CHOSEN, SCROLL, ADMIN }

	/** Plugins that give a player a separate inventory per world or game mode: arms.inventory-safety auto retracts with these. */
	private static final List<String> INVENTORY_PLUGINS = List.of("Multiverse-Inventories", "PerWorldInventory", "MyWorlds", "WorldInventories");
	/** F after a special found nothing to act on: ignored this long, so it can't be spammed. */
	private static final int NO_TARGET_LOCKOUT = 10;
	/** The two low-biomass warnings of a session (5 and 1 biomass). */
	private static final long LOW_MILLI = 5_000, EMPTY_MILLI = 1_000;
	private static final int MAX_LEVEL = 3;

	private final MorphRebornPlugin plugin;
	private final Context context = new Context();
	private final ArmBilling billing;
	private final ArmAudit audit;
	private final ArmEffects effects;
	private final ArmCommand commands;
	private final ArmGuardListener guards;

	private ArmConfig config = ArmConfig.DEFAULTS;
	private boolean started;
	private boolean lookShutDown;
	private int tick;

	private final Map<UUID, ArmSession> sessions = new LinkedHashMap<>();
	/** Each online player's arm slots, as last read from or written to their data ({@link ArmStash#readSlots}). */
	private final Map<UUID, ArmSlots> slotCache = new HashMap<>();
	/** The last S_ARM_SLOTS each owner was sent: an unchanged one isn't sent again. */
	private final Map<UUID, ArmSlotsMsg> slotsSent = new HashMap<>();
	/** The bindings and tiers each owner's client was last found to need, checked once a second: a tier that moved is sent again. */
	private final Map<UUID, List<ArmSlotsMsg.Slot>> tiersSeen = new HashMap<>();
	/** The refusals an arm that formed by itself has already said (2026-11-16): each once, until an arm forms or the reason changes. */
	private final SaidOnce<UUID> saidOnce = new SaidOnce<>();
	/** Where each player's selection was last seen, to catch one that moved with no held event (pick-block, another plugin). */
	private final HeldSlotWatch<UUID> heldWatch = new HeldSlotWatch<>();
	/**
	 * Players whose modded client has a container screen open (ACTION_INVENTORY_OPEN until CLOSED, quit, death or a world
	 * change). While it is set nothing forms by itself (it waits for the CLOSED): a number key and E in one tick can bring the
	 * OPEN before the held change, and the arm would form over the slot while the screen is up.
	 */
	private final Set<UUID> screens = new HashSet<>();
	/** No new form before this tick (arms.reform-lockout-ticks after a form, reshape or retract). */
	private final Map<UUID, Integer> lockouts = new HashMap<>();
	private final Map<UUID, Integer> noTargetLocks = new HashMap<>();
	/** Specials' cooldowns by form id: the tick each is ready. Kept across rejoins. */
	private final Map<UUID, Map<String, Integer>> cooldowns = new HashMap<>();
	/** The form a J tap re-forms. */
	private final Map<UUID, String> lastForms = new HashMap<>();
	/**
	 * Each player's accessories that are on (2026-11-16: several at once), in the order they were switched on, as this
	 * manager last told clients: the id, the level it went on at, and whether an admin made it free. A player with none has no entry.
	 */
	private final Map<UUID, ArrayList<On>> on = new HashMap<>();

	/**
	 * One accessory that is on: its id, the level it was switched on at, and whether an admin switched it on with {@code free} (no
	 * drain until it goes off). A player's accessories are an ArrayList of these, at most four, read with index loops and
	 * {@link #find}: the every-tick paths (the drain rate, the check for a module that went off on its own) allocate nothing
	 * (2026-11-16: no per-tick allocations in the steady state).
	 */
	private record On(String id, int level, boolean free) {}

	private static @Nullable On find(@Nullable List<On> mine, String id) {
		if (mine == null) return null;
		for (int i = 0; i < mine.size(); i++) {
			On o = mine.get(i);
			if (o.id().equals(id)) return o;
		}
		return null;
	}

	/** checkAccessories' per-player step and its scratch lists, made once so that a tick with accessories on allocates nothing. */
	private final java.util.function.BiConsumer<UUID, ArrayList<On>> accessoryCheck = this::checkOnePlayer;
	private final List<UUID> accessoriesGone = new ArrayList<>();
	private final List<UUID> accessoriesChanged = new ArrayList<>();

	/** The mode each player chose for each accessory that has one (the lantern's brightness), as kept on the player ({@link #modeKey}). */
	private final Map<UUID, Map<String, Integer>> modes = new HashMap<>();
	/** Where the lantern's brightness has been kept since 8.0.0; the other accessories' modes (none yet) go under {@code <id>_mode}. */
	private static final String LANTERN_BRIGHT_KEY = "lantern_brightness";
	/** The session's low-biomass warnings given so far: 1 the first, 2 both. */
	private final Map<UUID, Integer> warned = new HashMap<>();
	/** Players whose last spend emptied their biomass: melted and put out on the next tick. */
	private final Set<UUID> depleted = new LinkedHashSet<>();
	private final Map<String, ArmSpecialModule> specials = new LinkedHashMap<>();
	/** The accessory modules in this build, by id, in ArmForm.ACCESSORIES order (a module that isn't in the jar isn't here: that accessory is not built). */
	private final Map<String, ArmAccessoryModule> accessories = new LinkedHashMap<>();
	private @Nullable ArmLookModule look;
	private final ConcurrentMap<Integer, ArmView> views = new ConcurrentHashMap<>();
	/** Players inside a special's hit or break right now (nesting depth). */
	private final Map<UUID, Integer> specialHits = new HashMap<>();
	private final Map<UUID, Integer> specialBreaks = new HashMap<>();
	/** Players inside a special's test hit right now (also counted in specialHits): a probe is no hit to react to. */
	private final Map<UUID, Integer> probeHits = new HashMap<>();
	/** Modules whose tick failed: logged once each. */
	private final Set<Object> failing = new HashSet<>();
	/** Depth of our own inventory writes (the slot-change guard ignores them). */
	private int writing;

	public ArmManager(MorphRebornPlugin plugin) {
		this.plugin = plugin;
		this.billing = new ArmBilling(this);
		this.audit = new ArmAudit(this);
		this.effects = new ArmEffects(plugin);
		this.commands = new ArmCommand(this);
		this.guards = new ArmGuardListener(this);
	}

	// ------------------------------------------------------------------ settings and lifecycle

	@Override
	public boolean enabled() {
		return started && config.enabled();
	}

	@Override
	public void enable(@Nullable ConfigurationSection arms, @Nullable ConfigurationSection armCost, @Nullable ConfigurationSection bundledArms) {
		config = parse(arms, armCost, bundledArms);
		if (!started) {
			loadModules();
			var pm = plugin.getServer().getPluginManager();
			// the guards and the join repair run even with arms switched off, so arms left from before are still cleaned up
			pm.registerEvents(guards, plugin);
			pm.registerEvents(billing, plugin);
			for (ArmSpecialModule m : specials.values()) register(m.listener(), m.form());
			for (ArmAccessoryModule m : accessories.values()) register(m.listener(), m.id());
			started = true;
		}
		loadModuleSettings();
		plugin.getLogger().info("Arm forms " + (config.enabled() ? "on" : "off") + ". Built: " + (builtForms().isEmpty() ? "none" : String.join(", ", builtForms()))
				+ (accessories.isEmpty() ? "" : "; accessories: " + String.join(", ", accessories.keySet())) + (look != null ? "; the pack look is on." : "."));
	}

	private void register(@Nullable Listener listener, String what) {
		if (listener == null) return;
		try {
			plugin.getServer().getPluginManager().registerEvents(listener, plugin);
		} catch (RuntimeException e) {
			plugin.getLogger().log(Level.WARNING, "The " + what + " arm module's events couldn't be registered.", e);
		}
	}

	private ArmConfig parse(@Nullable ConfigurationSection arms, @Nullable ConfigurationSection armCost, @Nullable ConfigurationSection bundledArms) {
		try {
			return ArmConfig.parse(ArmConfigMaps.of(arms), ArmConfigMaps.of(armCost), ArmConfigMaps.of(bundledArms));
		} catch (RuntimeException e) {
			plugin.getLogger().log(Level.WARNING, "The arms settings couldn't be read; using the defaults.", e);
			return ArmConfig.DEFAULTS;
		}
	}

	/** The specials, the accessories and the pack look, each only if it is in this build. Once, at enable. */
	private void loadModules() {
		for (ArmSpecialModule m : dev.morphreborn.paper.arm.ArmModules.specials(plugin.getLogger())) {
			try {
				String form = m.form();
				ArmForm f = ArmForm.of(form);
				if (f == null || !f.tool()) {
					plugin.getLogger().warning("Arm module " + m.getClass().getSimpleName() + " is for '" + form + "', which isn't an arm form; left out.");
					continue;
				}
				if (specials.containsKey(f.id())) continue;
				m.init(context);
				specials.put(f.id(), m);
			} catch (Throwable t) {
				plugin.getLogger().log(Level.WARNING, "Arm module " + m.getClass().getSimpleName() + " failed to start; left out.", t);
			}
		}
		Map<String, ArmAccessoryModule> found = new HashMap<>();
		for (ArmAccessoryModule m : ArmModules.accessories(plugin.getLogger())) {
			try {
				String id = m.id();
				ArmForm f = ArmForm.of(id);
				if (f == null || f.tool()) {
					plugin.getLogger().warning("Arm module " + m.getClass().getSimpleName() + " is for '" + id + "', which isn't an arm accessory; left out.");
					continue;
				}
				if (found.containsKey(f.id())) continue;
				m.init(context);
				found.put(f.id(), m);
			} catch (Throwable t) {
				plugin.getLogger().log(Level.WARNING, "Arm module " + m.getClass().getSimpleName() + " failed to start; left out.", t);
			}
		}
		for (ArmForm f : ArmForm.ACCESSORIES) { // in the wheel's inner ring order, whatever order the modules were listed in
			ArmAccessoryModule m = found.get(f.id());
			if (m != null) accessories.put(f.id(), m);
		}
		ArmLookModule v = ArmModules.look(plugin.getLogger());
		if (v != null) {
			try {
				v.init(context);
				look = v;
			} catch (Throwable t) {
				plugin.getLogger().log(Level.WARNING, "The arm pack look failed to start; everyone without the mod sees plain tools.", t);
			}
		}
	}

	private void loadModuleSettings() {
		for (ArmSpecialModule m : specials.values()) {
			try {
				m.load(config.form(m.form()));
			} catch (Throwable t) {
				plugin.getLogger().log(Level.WARNING, "The " + m.form() + " arm's settings couldn't be read.", t);
			}
		}
		for (ArmAccessoryModule m : accessories.values()) {
			try {
				m.load(config.accessory(m.id()));
			} catch (Throwable t) {
				plugin.getLogger().log(Level.WARNING, "The " + m.id() + " accessory's settings couldn't be read.", t);
			}
		}
	}

	@Override
	public void reload(@Nullable ConfigurationSection arms, @Nullable ConfigurationSection armCost, @Nullable ConfigurationSection bundledArms) {
		if (!started) {
			enable(arms, armCost, bundledArms);
			return;
		}
		retractAll(ArmProtocol.WHY_MODE_OR_RELOAD);
		config = parse(arms, armCost, bundledArms);
		loadModuleSettings();
		for (Player p : plugin.getServer().getOnlinePlayers()) refreshArsenal(p);
	}

	@Override
	public void disable() {
		if (!started) return;
		retractAll(ArmProtocol.WHY_DISABLE);
		if (look != null && !lookShutDown) {
			lookShutDown = true;
			try {
				look.shutdown();
			} catch (Throwable t) {
				plugin.getLogger().log(Level.WARNING, "The arm pack look failed to shut down.", t);
			}
		}
		views.clear();
		billing.clear();
	}

	@Override
	public void beforeModeSwitch() {
		if (!started) return;
		retractAll(ArmProtocol.WHY_MODE_OR_RELOAD);
	}

	/**
	 * Every arm retracted at once, then every accessory off. An item with no room to go back into is kept for the next join
	 * when RE:Morph is shutting down (nobody may be there to pick it up); on a reload or mode switch the player is still
	 * here, so it drops at their feet as at any other retract.
	 */
	void retractAll(int why) {
		Overflow overflow = why == ArmProtocol.WHY_DISABLE ? Overflow.PENDING : Overflow.DROP;
		for (UUID id : List.copyOf(sessions.keySet())) {
			ArmSession s = sessions.get(id);
			Player p = plugin.getServer().getPlayer(id);
			if (s == null) continue;
			if (p != null) finish(p, s, why, overflow);
			else sessions.remove(id); // offline without a quit: the join repair gives the item back from the saved record
		}
		for (ArmSpecialModule m : specials.values()) {
			try {
				m.abortAll();
			} catch (Throwable t) {
				plugin.getLogger().log(Level.WARNING, "The " + m.form() + " arm's specials failed to stop.", t);
			}
		}
		List<UUID> wasOn = List.copyOf(on.keySet());
		for (ArmAccessoryModule m : accessories.values()) {
			try {
				m.switchOffAll();
			} catch (Throwable t) {
				plugin.getLogger().log(Level.WARNING, "The " + m.id() + " accessories failed to go off.", t);
			}
		}
		on.clear();
		for (UUID id : wasOn) {
			Player p = plugin.getServer().getPlayer(id);
			if (p != null) tellClients(p, stateMsg(p, null, why));
		}
	}

	// ------------------------------------------------------------------ the tick

	@Override
	public void tick() {
		tick++;
		if (!started) return;
		for (UUID id : List.copyOf(depleted)) {
			Player p = plugin.getServer().getPlayer(id);
			if (p == null) continue;
			ArmSession s = sessions.get(id);
			if (s != null && !s.ending()) end(p, s, ArmProtocol.STATE_MELTING, ArmProtocol.WHY_DEPLETED);
			switchOffAll(p, ArmProtocol.WHY_DEPLETED);
		}
		depleted.clear();
		for (ArmSession s : List.copyOf(sessions.values())) {
			if (sessions.get(s.player) != s) continue;
			Player p = plugin.getServer().getPlayer(s.player);
			if (p == null) {
				sessions.remove(s.player); // left without a quit event: the join repair gives the item back
				continue;
			}
			try {
				audit.check(p, s, tick);
				if (sessions.get(s.player) == s) step(p, s);
			} catch (Throwable t) {
				plugin.getLogger().log(Level.SEVERE, "Arm check failed for " + p.getName() + "; retracting the arm.", t);
				if (sessions.get(s.player) == s) {
					try {
						finish(p, s, ArmProtocol.WHY_ERROR, Overflow.DROP);
					} catch (Throwable again) {
						sessions.remove(s.player);
						plugin.getLogger().log(Level.SEVERE, "Retracting " + p.getName() + "'s arm failed too; the join repair will give the item back.", again);
					}
				}
			}
		}
		watchPlayers();
		billing.tick(tick);
		for (ArmSpecialModule m : specials.values()) guarded(m, m::tick);
		for (ArmAccessoryModule m : accessories.values()) guarded(m, m::tick);
		checkAccessories();
		if (tick % 10 == 0) plugin.survival().flushArmSync();
		if (tick % 10 == 5) followBiomass();
	}

	private void guarded(Object module, Runnable r) {
		try {
			r.run();
			failing.remove(module);
		} catch (Throwable t) {
			if (failing.add(module)) plugin.getLogger().log(Level.WARNING, "Arm module " + module.getClass().getSimpleName() + " failed in its tick.", t);
		}
	}

	/**
	 * Each tick, every player: a selection that moved with no held event is treated as a scroll (a middle-click pick-block
	 * moving onto a bound slot, another plugin's setHeldItemSlot); and once a second, staggered, a bound arm's tier that
	 * moved with the biomass bar or an upgrade is sent to the owner's client again. The sessions' own audit (A1) has
	 * already dealt with a held slot that left an arm behind and noted where the selection is, so this only sees what
	 * nobody handled. Nothing here retries: a deploy that was refused waits for the slot to move again.
	 */
	private void watchPlayers() {
		for (Player p : plugin.getServer().getOnlinePlayers()) {
			UUID id = p.getUniqueId();
			try {
				int held = p.getInventory().getHeldItemSlot();
				if (heldWatch.moved(id, held) && !p.isDead()) {
					ArmSession s = sessions.get(id);
					if (s != null && s.slot != held) finish(p, s, ArmProtocol.WHY_SLOT, Overflow.DROP);
					scrolledOnto(p, held);
				}
				if (Math.floorMod(tick + id.hashCode(), 20) == 0) checkTiers(p);
			} catch (Throwable t) {
				plugin.getLogger().log(Level.WARNING, "Arm slot check failed for " + p.getName() + ".", t);
			}
		}
	}

	/** A session's timeline: forming settles into FORMED, a retract or melt finishes. */
	private void step(Player p, ArmSession s) {
		int elapsed = s.elapsed(tick);
		switch (s.state) {
			case ArmProtocol.STATE_FORMING, ArmProtocol.STATE_QUICKFORM, ArmProtocol.STATE_RESHAPING -> {
				if (elapsed >= s.duration) {
					s.enter(ArmProtocol.STATE_FORMED, tick);
					changed(p, s, ArmProtocol.WHY_CHOSEN);
				} else if (elapsed > 0 && elapsed % 3 == 0 && elapsed <= 12) {
					lookChanged(p, s.view(), true); // the pack look's forming steps
				}
			}
			case ArmProtocol.STATE_RETRACTING -> {
				if (elapsed >= s.duration) finish(p, s, s.endWhy, Overflow.DROP);
			}
			case ArmProtocol.STATE_MELTING -> {
				safely("the melt's drips", () -> effects.meltTick(p, elapsed));
				if (elapsed >= s.duration) finish(p, s, s.endWhy, Overflow.DROP);
			}
			default -> {}
		}
	}

	/** Accessories a module put off on its own (its owner offline, dead or a spectator): clients are told, once per player and tick. */
	private void checkAccessories() {
		if (on.isEmpty()) return;
		accessoriesGone.clear();
		accessoriesChanged.clear();
		on.forEach(accessoryCheck); // nothing is added to or removed from `on` inside it: that waits for the loops below
		for (int i = 0; i < accessoriesGone.size(); i++) on.remove(accessoriesGone.get(i));
		for (int i = 0; i < accessoriesChanged.size(); i++) {
			UUID id = accessoriesChanged.get(i);
			Player p = plugin.getServer().getPlayer(id);
			if (p == null) continue;
			tellClients(p, stateMsg(p, sessions.get(id), ArmProtocol.WHY_CHOSEN));
			refreshArsenal(p);
			plugin.survival().markArmSync(p);
		}
	}

	private void checkOnePlayer(UUID id, ArrayList<On> mine) {
		Player p = plugin.getServer().getPlayer(id);
		if (p == null) {
			accessoriesGone.add(id);
			return;
		}
		boolean changed = false;
		for (int i = mine.size() - 1; i >= 0; i--) {
			if (!moduleOn(p, mine.get(i).id())) {
				mine.remove(i);
				changed = true;
			}
		}
		if (mine.isEmpty()) accessoriesGone.add(id);
		if (changed) accessoriesChanged.add(id);
	}

	// ------------------------------------------------------------------ forming

	/**
	 * Form {@code formId} (spec 3.3): a new arm, a reshape of the one formed, or nothing when it's already that form.
	 * Checks run in order and the first failure changes nothing.
	 *
	 * @param slot the slot to form in, -1 for the held one (a scroll onto a bound slot passes the slot being scrolled to,
	 *            which isn't held yet)
	 * @param levelOverride an admin's level, 0 for the player's own
	 * @param free an admin's {@code free}: nothing this arm does costs biomass
	 * @return null when done, otherwise why not
	 */
	@Nullable String form(Player p, String formId, Cause cause, int slot, int levelOverride, boolean free) {
		ArmForm f = ArmForm.of(formId);
		if (f == null || !f.tool()) {
			return "There is no arm form '" + formId + "'." + (builtForms().isEmpty() ? "" : " Forms: " + String.join(", ", builtForms()) + ".");
		}
		String form = f.id();
		boolean admin = cause == Cause.ADMIN;
		// 1. arms on, the form built and on, allowed
		if (!config.enabled()) return "Arm forms are switched off on this server.";
		if (!specials.containsKey(form)) return f.displayName() + " isn't in this build of RE:Morph.";
		if (!config.formEnabled(form)) return f.displayName() + " is switched off on this server.";
		if (!admin && !(p.hasPermission("morphreborn.arm") && p.hasPermission(f.permission()))) return "You are not allowed to use the " + f.displayName() + ".";
		// 2. here and alive
		if (!p.isOnline() || p.isDead()) return "You can't shape your arm right now.";
		if (p.getGameMode() == GameMode.SPECTATOR) return "Spectators have no arm to shape.";
		// 3. own body only (decision D10)
		if (plugin.morphs().activeForm(p) != null || plugin.morphs().isTransitioning(p)) return "Arm forms are for your own body only: turn back first.";
		// 4. a level
		int level = levelOverride > 0 ? Math.clamp(levelOverride, 1, MAX_LEVEL) : levelOf(p, form);
		if (admin && level < 1) level = 1;
		if (level < 1) return levelWhy(p, f);
		// 5. the lockout after the last change
		Integer until = lockouts.get(p.getUniqueId());
		if (cause == Cause.CHOSEN && until != null && tick < until) return "Your arm is still settling.";
		// 6. enough biomass to start
		if (!admin && !free(p) && balanceMilli(p, capacityMilli(p)) < config.minToFormMilli()) {
			return "Your arm needs at least " + ArmItemSpecs.biomass(config.minToFormMilli()) + " biomass to take shape.";
		}
		// 7. the look's own reason (a Bedrock player can't form the bow)
		String lookWhy = look == null ? null : safeLook(() -> look.cannotForm(p, form));
		if (lookWhy != null) return lookWhy;
		// 8. already formed: nothing to do, a re-tier, or a reshape; biomass may draw the arm below the level owned
		ArmSession s = sessions.get(p.getUniqueId());
		int owned = level;
		level = effective(p, owned, s != null && s.form.equals(form) ? s : null, admin);
		// The binding (2026-11-16): choosing a form binds the slot its arm takes to it, moving the form's binding
		// there if it was elsewhere; a scroll onto a bound slot only re-forms what is already bound. Saved before the arm is
		// made so the S_ARM_SLOTS that follows carries it, and put back if the arm then can't be made.
		ArmSlots before = slots(p);
		int target = s != null ? s.slot : slot >= 0 ? slot : p.getInventory().getHeldItemSlot();
		// An admin's arm binds only when the player could form it themselves and it carries no free or level of its own
		// (ArmSlots.adminOneOffReason); otherwise it is a one-off that leaves every binding as it was.
		boolean binds = cause == Cause.CHOSEN || (cause == Cause.ADMIN && adminBinds(p, form, levelOverride, free));
		ArmSlots bound = binds ? before.bind(target, form) : before;
		if (s != null) {
			if (s.ending()) return s.state == ArmProtocol.STATE_MELTING ? "Your arm is melting." : "Your arm is still retracting.";
			if (s.tucked) return "Your arm is tucked away while an inventory is open.";
			if (admin && s.free != free) {
				s.free = free; // an admin's word on cost stands for the arm as it is now
				refreshArsenal(p);
			}
			saveSlots(p, bound);
			if (s.form.equals(form)) {
				s.cap = owned;
				if (s.level != level) retier(p, s, level, admin ? ArmProtocol.WHY_ADMIN : ArmProtocol.WHY_UPGRADE);
				if (!bound.equals(before)) refreshSlots(p, false);
				return null;
			}
			reshape(p, s, form, level);
			s.cap = owned;
			s.band = 0;
			return null;
		}
		saveSlots(p, bound);
		String refused = create(p, form, level, cause, slot, free);
		if (refused != null) {
			saveSlots(p, before); // nothing was sent: the client never heard of the binding
			return refused;
		}
		ArmSession made = sessions.get(p.getUniqueId());
		if (made != null) made.cap = owned;
		return null;
	}

	/** Whether an admin-forced {@code form} would bind the slot it takes ({@link ArmSlots#adminOneOffReason}). */
	boolean adminBinds(Player p, String form, int levelOverride, boolean free) {
		return ArmSlots.adminBinds(levelOf(p, form), free, levelOverride);
	}

	/** Why it would not (for the admin's feedback), or null. */
	@Nullable String adminOneOffReason(Player p, String form, int levelOverride, boolean free) {
		return ArmSlots.adminOneOffReason(levelOf(p, form), free, levelOverride);
	}

	/** Steps 9 to 16: a new session in the slot. Steps 13 to 15 (record, S_ARM, slot) run in this one call. */
	private @Nullable String create(Player p, String form, int level, Cause cause, int slotWanted, boolean free) {
		// 9. a container open on top would let the client move things around the arm; the cursor goes back
		InventoryType open = p.getOpenInventory().getType();
		if (open != InventoryType.CRAFTING && open != InventoryType.CREATIVE) p.closeInventory();
		// A saved record without a live session (only after a failed retract): its item goes back first, or writing the
		// new record below would lose it.
		if (ArmStash.has(p)) {
			List<String> repaired = new ArrayList<>();
			purgeEverywhere(p);
			restoreRecord(p, repaired);
			plugin.getLogger().warning(p.getName() + " had an arm record without a formed arm; repaired before forming: "
					+ (repaired.isEmpty() ? "nothing to give back" : String.join("; ", repaired)) + ".");
		}
		// 10. the slot and what's in it; an arm already there is a stray
		PlayerInventory inv = p.getInventory();
		int slot = slotWanted >= 0 ? slotWanted : inv.getHeldItemSlot();
		ItemStack current = inv.getItem(slot);
		if (ArmItems.isArm(current)) {
			plugin.getLogger().warning("A stray arm was in " + p.getName() + "'s hand; deleted.");
			write(p, () -> inv.setItem(slot, null));
			current = null;
		}
		// 11. the stash, as bytes
		ItemStack held = current;
		byte[] bytes = held == null || held.isEmpty() ? new byte[0] : held.serializeAsBytes();
		if (bytes.length > config.stashMaxBytes()) return "That item is too big for your arm to hold; empty your hand.";
		// 12. a nonce, and the arm built before anything is written: a failure here changes nothing
		UUID id = p.getUniqueId();
		long nonce = freshNonce();
		ItemStack arm = buildItem(p, form, level, nonce);
		ArmSession s = new ArmSession(id, form, level, slot, nonce, bytes, System.currentTimeMillis(), free);
		s.enter(cause == Cause.SCROLL ? ArmProtocol.STATE_QUICKFORM : ArmProtocol.STATE_FORMING, tick);
		// 13-15 in this one call: the record saved, clients told, the arm in the slot
		try {
			ArmStash.write(p, s.record());
			sessions.put(id, s);
			// 14. clients that draw arms learn the state (and the item under the arm) before they see the item
			int why = cause == Cause.SCROLL ? ArmProtocol.WHY_RECALL : cause == Cause.ADMIN ? ArmProtocol.WHY_ADMIN : ArmProtocol.WHY_CHOSEN;
			tellClients(p, stateMsg(p, s, why));
			refreshSlots(p, false);
			// 15. the arm takes the slot
			write(p, () -> inv.setItem(slot, arm));
		} catch (RuntimeException e) {
			// Undone, so the record never promises an item the slot still holds (that would hand it back twice later).
			sessions.remove(id);
			if (isSessionArm(s, inv.getItem(slot))) write(p, () -> inv.setItem(slot, held));
			ArmStash.clear(p);
			tellClients(p, stateMsg(p, null, ArmProtocol.WHY_ERROR));
			refreshSlots(p, false);
			plugin.getLogger().log(Level.SEVERE, "Forming " + p.getName() + "'s " + form + " arm failed; nothing was changed.", e);
			return "Your arm failed to take shape; the server log says why.";
		}
		// 16. the rest
		if (cause != Cause.SCROLL) lockouts.put(id, tick + config.reformLockoutTicks());
		lastForms.put(id, form);
		warned.remove(id);
		saidOnce.clear(id); // an arm formed: the next refusal of a deploy is news again
		safely("the forming effects", () -> effects.forming(p, cause == Cause.SCROLL));
		views.put(p.getEntityId(), s.view());
		lookChanged(p, s.view(), false);
		refreshArsenal(p);
		plugin.survival().markArmSync(p);
		report(p, "formed " + form + " L" + level + " in slot " + slot + " (" + causeName(cause) + (free ? ", free" : "") + "), holding "
				+ ArmStash.describe(bytes));
		return null;
	}

	/** One form into another, in place: same slot, same stash, a new nonce. Free; the old form's special stops. */
	private void reshape(Player p, ArmSession s, String form, int level) {
		long nonce = freshNonce();
		ItemStack arm = buildItem(p, form, level, nonce); // first: a failure here leaves the old form as it was
		abortSpecial(p, s);
		String from = s.form;
		s.form = form;
		s.level = level;
		s.nonce = nonce;
		s.enter(ArmProtocol.STATE_RESHAPING, tick);
		s.fromForm = from;
		ArmStash.write(p, s.record());
		tellClients(p, stateMsg(p, s, ArmProtocol.WHY_CHOSEN));
		refreshSlots(p, false); // the binding moved to this form, and the tier it would form at
		write(p, () -> p.getInventory().setItem(s.slot, arm));
		lockouts.put(p.getUniqueId(), tick + config.reformLockoutTicks());
		lastForms.put(p.getUniqueId(), form);
		safely("the reshape effects", () -> effects.reshaping(p));
		views.put(p.getEntityId(), s.view());
		lookChanged(p, s.view(), false);
		refreshArsenal(p);
		report(p, "reshaped " + from + " -> " + form + " L" + level);
	}

	/**
	 * The level an arm of {@code owned} levels is drawn at now: biomass draws it down by the bar's thirds (ArmTiers.
	 * biomassBand, with {@code s}'s band for hysteresis, which this updates), never above {@code owned}. As owned when
	 * {@code asGiven} (an admin's level), the arm is free, the setting is off, or there is no Survival biomass.
	 */
	private int effective(Player p, int owned, @Nullable ArmSession s, boolean asGiven) {
		if (asGiven || owned < 1 || free(p) || !config.tierFollowsBiomass() || !plugin.survival().active()) return owned;
		long capacity = capacityMilli(p);
		if (capacity <= 0) return owned;
		int band = ArmTiers.biomassBand(balanceMilli(p, capacity), capacity, s == null ? 0 : s.band);
		if (s != null) s.band = band;
		return Math.max(1, Math.min(owned, band));
	}

	/**
	 * Every half second: each formed arm's level follows the biomass bar (2026-11-15), stepping down a level
	 * as the biomass drops below a third and back up as it comes back, never above what the player owns. Not while
	 * digging (a new item would reset the block's progress), and not mid-timeline.
	 */
	private void followBiomass() {
		if (!config.tierFollowsBiomass()) return;
		for (ArmSession s : List.copyOf(sessions.values())) {
			if (s.state != ArmProtocol.STATE_FORMED || sessions.get(s.player) != s) continue;
			Player p = plugin.getServer().getPlayer(s.player);
			if (p == null || !billing.settled(s.player, tick)) continue;
			int level = effective(p, s.cap, s, false);
			if (level == s.level) continue;
			boolean down = level < s.level;
			retier(p, s, level, ArmProtocol.WHY_BIOMASS);
			p.sendActionBar(Component.text(down ? "Your arm thins as its biomass runs low." : "Your arm hardens again.",
					down ? NamedTextColor.GOLD : NamedTextColor.GREEN));
		}
	}

	/** A new level for the formed arm (an upgrade bought, an admin's level): same nonce, same state. */
	private void retier(Player p, ArmSession s, int level, int why) {
		ItemStack arm = buildItem(p, s.form, level, s.nonce);
		s.level = level;
		ArmStash.write(p, s.record());
		tellClients(p, stateMsg(p, s, why));
		refreshSlots(p, false);
		if (!s.tucked) write(p, () -> p.getInventory().setItem(s.slot, arm)); // tucked: the slot holds the real item; the untuck builds the arm at this level
		else {
			// the stub others are shown carries the new tier's marks and the bar the rebuilt item has (the slot's write that would
			// announce it never comes, so the hand is sent to every viewer below)
			s.tuckMaxDamage = ArmItems.maxDamageOf(arm);
			s.tuckDamage = ArmItems.damageOf(arm);
		}
		views.put(p.getEntityId(), s.view());
		lookChanged(p, s.view(), false);
		if (s.tucked) handChanged(p);
		refreshArsenal(p);
		report(p, s.form + " re-tiered to L" + level + " (" + ArmProtocol.whyName(why) + ")");
	}

	/** The arm item of a form and level with a session's nonce, its bar showing the player's biomass now. */
	private ItemStack buildItem(Player p, String form, int level, long nonce) {
		ArmSpecialModule m = specials.get(form);
		long capacity = capacityMilli(p);
		// a special that has more than one move writes its own F line (then there is no single cost to show beside it)
		Lines loreLines = m == null ? null : m.loreLines(level, plugin.survival().active(), config.costs()::charge);
		Lines special = Lines.EMPTY;
		if (m != null) {
			if (loreLines != null) {
				special = loreLines;
			} else {
				String name = m.specialName(level);
				long cost = plugin.survival().active() ? config.costs().charge(m.costMilli(level)) : 0;
				if (name != null && !name.isEmpty()) special = Lines.builder().always(name).always(cost > 0 ? ArmItemSpecs.biomass(cost) + " biomass" : "").build();
			}
		}
		// the lore follows the forming player's Tooltips setting as it is when the arm is formed
		ArmItemSpec spec = ArmItemSpecs.of(form, level, plugin.survival().active(), balanceMilli(p, capacity), capacity, special,
				config.fortune(form, level), config.efficiency(form, level), plugin.tooltips(p))
				.withExtraReach(config.extraReach(form, level));
		boolean pack = look != null && Boolean.TRUE.equals(safeLook(() -> look.usePackModel(p)));
		return ArmItems.build(spec, nonce, pack ? "morphreborn:arm/" + form : spec.vanillaModel());
	}

	private long freshNonce() {
		long n;
		do {
			n = ThreadLocalRandom.current().nextLong();
		} while (n == 0);
		return n;
	}

	// ------------------------------------------------------------------ arm slots

	/** The player's arm slots (read from their data the first time). */
	ArmSlots slots(Player p) {
		return slotCache.computeIfAbsent(p.getUniqueId(), id -> ArmStash.readSlots(p));
	}

	/** Remember and save the arm slots. Labels only, so this has no inventory rule to keep; clients are told by {@link #refreshSlots}. */
	private void saveSlots(Player p, ArmSlots slots) {
		if (slots.equals(slotCache.get(p.getUniqueId()))) return;
		slotCache.put(p.getUniqueId(), slots);
		ArmStash.writeSlots(p, slots);
	}

	/**
	 * Scrolling onto a bound slot (2026-11-16): that slot's arm forms again with the quick animation, over
	 * whatever the slot holds now. Called after the arm of the slot scrolled away from has gone back, so no tick has two
	 * arms; the slot is the one being scrolled to, which the server doesn't count as held until the event returns.
	 */
	void scrolledOnto(Player p, int newSlot) {
		heldWatch.note(p.getUniqueId(), newSlot); // handled here, so the per-tick watch doesn't take it for a move nobody saw
		if (!enabled() || sessions.containsKey(p.getUniqueId())) return; // arms switched off: the bindings wait, silently
		String form = slots(p).formAt(newSlot);
		if (form != null) deployBound(p, newSlot, form);
	}

	/**
	 * The held slot, if it is bound and no arm is out: that arm forms (quick, as a scroll onto it would). For the moments
	 * the selection is already there and nothing moves it: after a join or a respawn or a world change, and when a screen
	 * closes. Safe to repeat; does nothing for a player who is dead, gone or has a screen open.
	 */
	void deployHeld(Player p) {
		UUID id = p.getUniqueId();
		if (!enabled() || !p.isOnline() || p.isDead() || sessions.containsKey(id)) return;
		int held = p.getInventory().getHeldItemSlot();
		heldWatch.note(id, held);
		String form = slots(p).formAt(held);
		if (form != null) deployBound(p, held, form);
	}

	/** {@link #deployHeld} on the next tick, when whatever else a join or world change does to the player has settled. */
	private void deployHeldSoon(Player p) {
		plugin.getServer().getScheduler().runTask(plugin, () -> deployHeld(p));
	}

	/**
	 * {@link #deployHeldSoon} for a join. The morph handling of the join runs after our repair but before this tick, so by then
	 * {@code current} says whether a saved morph is about to be restored, which MorphManager does a few ticks on
	 * (RESTORE_DELAY_TICKS). That player's deploy waits until after it (JoinDeploy): the arm formed in the gap would be popped
	 * by the morph starting, at every login. The deploy then asks form(), which refuses silently for a morphed player; and for
	 * one whose restore did not happen after all, the arm forms as it would have.
	 */
	private void deployHeldAfterJoin(Player p) {
		plugin.getServer().getScheduler().runTask(plugin, () -> {
			if (!p.isOnline()) return; // gone again: reading the store would load and cache their data with nothing to unload it
			boolean restoring = plugin.settings().persistCurrentMorph && plugin.store().get(p.getUniqueId()).current != null;
			long wait = JoinDeploy.extraWaitTicks(restoring, MorphManager.RESTORE_DELAY_TICKS);
			if (wait <= 0) deployHeld(p);
			else plugin.getServer().getScheduler().runTaskLater(plugin, () -> deployHeld(p), wait);
		});
	}

	/**
	 * The inventory screen the modded client had open has closed (ACTION_INVENTORY_CLOSED): a tucked arm untucks (the same
	 * session, no forming animation: whatever the slot holds now goes under the arm item); with no arm out, the held slot, if
	 * it is bound, forms its arm again over whatever is in it now. A tucked arm that is retracting or melting ends at once.
	 * Safe to repeat, and to receive with no OPEN before it.
	 */
	void inventoryClosed(Player p) {
		UUID id = p.getUniqueId();
		screens.remove(id);
		ArmSession s = sessions.get(id);
		if (s == null) {
			deployHeld(p);
			return;
		}
		if (!s.tucked || !p.isOnline() || p.isDead()) return;
		int held = p.getInventory().getHeldItemSlot();
		boolean moved = held != s.slot;
		boolean ending = s.ending();
		byte[] bytes = moved || ending ? new byte[0] : slotBytes(p, s.slot);
		switch (ArmTuck.onClose(true, true, ending, moved, bytes.length <= config.stashMaxBytes())) {
			case UNTUCK -> untuck(p, s, bytes);
			case END_ENDING -> {
				// a tucked arm that was retracting or melting when the screen closed: it ends now, for the reason it was going
				// back. Safe: the stash is empty and the real item is in the slot, so finish gives back nothing a second time.
				finish(p, s, s.endWhy, Overflow.DROP);
				if (moved) scrolledOnto(p, held);
			}
			case END_AND_SCROLL -> {
				// the selection moved while the screen was open (a client that lied, another plugin): the arm is over, and the
				// slot now held counts as scrolled onto
				finish(p, s, ArmProtocol.WHY_SLOT, Overflow.DROP);
				scrolledOnto(p, held);
			}
			case END_TOO_BIG -> {
				finish(p, s, ArmProtocol.WHY_SLOT, Overflow.DROP);
				refuse(p, "That item is too big for your arm to hold, so your arm went back.");
			}
			default -> {}
		}
	}

	/**
	 * A screen opened on the modded client (ACTION_INVENTORY_OPEN): the held arm tucks (2026-11-16): the slot gets
	 * its real item back at once so the screen shows it, and the arm stays formed and drawn, with no item. One that can't be
	 * tucked (going back already, its item gone, its stash unreadable) goes back at once, as a scroll away would. The binding
	 * stays either way, and nothing forms by itself until the screen closes (a held change that arrives after the OPEN in the
	 * same tick forms nothing over it).
	 */
	void inventoryOpened(Player p) {
		UUID id = p.getUniqueId();
		screens.add(id);
		ArmSession s = sessions.get(id);
		boolean home = s != null && !s.tucked && isSessionArm(s, p.getInventory().getItem(s.slot));
		boolean readable = s != null && ArmStash.decode(s.stash) != null;
		switch (ArmTuck.onOpen(s != null, s != null && s.tucked, s != null && s.ending(), home, readable)) {
			case TUCK -> tuck(p, s);
			case END -> finish(p, s, ArmProtocol.WHY_SLOT, Overflow.DROP);
			default -> {}
		}
	}

	/** The item in a slot as stash bytes (empty for an empty slot or a stray arm, which is deleted). */
	private byte[] slotBytes(Player p, int slot) {
		ItemStack item = p.getInventory().getItem(slot);
		if (ArmItems.isArm(item)) return new byte[0];
		return item == null || item.isEmpty() ? new byte[0] : item.serializeAsBytes();
	}

	/**
	 * The arm item gives way to the real item and the session carries on: formed, drawn, billed, with nothing in the hand.
	 * The stash is emptied in the same call (the record too), so nothing can give the item back a second time, and the
	 * running special stops as at a retract. Clients hear only the arm slots (the held slot holds no arm now); S_ARM is not
	 * sent, because the arm has not changed.
	 */
	private void tuck(Player p, ArmSession s) {
		ItemStack stash = ArmStash.decode(s.stash);
		if (stash == null) { // not reachable through inventoryOpened, which checks; kept so a direct caller can't lose bytes
			finish(p, s, ArmProtocol.WHY_SLOT, Overflow.DROP);
			return;
		}
		abortSpecial(p, s);
		PlayerInventory inv = p.getInventory();
		byte[] was = s.stash;
		// the arm item's biomass bar goes into the view, since the stub others are shown has no item to read it from
		ItemStack armItem = inv.getItem(s.slot);
		s.tuckMaxDamage = armItem == null ? 0 : ArmItems.maxDamageOf(armItem);
		s.tuckDamage = armItem == null ? 0 : ArmItems.damageOf(armItem);
		// the crossbow's bolt too: the stub has no charged projectile of its own, and the pack's crossbow shows the empty groove without one
		s.tuckCharged = armItem != null && "crossbow".equals(s.form) && ArmItems.chargedOf(armItem);
		s.tucked = true;
		s.stash = new byte[0];
		try {
			ArmStash.write(p, s.record());
		} catch (RuntimeException e) {
			s.tucked = false;
			s.tuckMaxDamage = 0;
			s.tuckDamage = 0;
			s.tuckCharged = false;
			s.stash = was;
			plugin.getLogger().log(Level.SEVERE, "Tucking " + p.getName() + "'s arm failed; it goes back instead.", e);
			finish(p, s, ArmProtocol.WHY_ERROR, Overflow.DROP);
			return;
		}
		// before the slot changes: the equipment the server then sends about p is rewritten to the arm for everyone else
		// (ArmEquipmentView), so the arm stays on p's body for them too (2026-11-16: "maintain the hand morph")
		views.put(p.getEntityId(), s.view());
		refreshSlots(p, false);
		write(p, () -> {
			purgeOwn(p, null); // no arm item stays anywhere, the session's own included
			inv.setItem(s.slot, stash.isEmpty() ? null : stash);
		});
		// vanilla announces the slot's change at the end of the tick (the listener rewrites it by the view above); this sends the
		// hand to everyone tracking the owner now, so the stub is up whatever the slot's change turns out to be
		handChanged(p);
		report(p, "tucked " + s.form + " (screen open), gave back " + ArmStash.describe(was));
	}

	/**
	 * The screen closed with the arm tucked and the selection where it was: whatever the slot holds now is stashed (the
	 * player may have moved it, or put another item there) and the arm item takes its place, with a new nonce, so a copy of
	 * the old arm that slipped out is a stray. The same session: no forming animation, no lockout, no S_ARM (the arm never
	 * stopped being formed). {@code bytes} is the slot's item, already checked to fit.
	 */
	private void untuck(Player p, ArmSession s, byte[] bytes) {
		PlayerInventory inv = p.getInventory();
		long nonce = freshNonce();
		ItemStack arm = buildItem(p, s.form, s.level, nonce); // first: a failure leaves the arm tucked, as it was
		long oldNonce = s.nonce;
		s.stash = bytes;
		s.nonce = nonce;
		s.tucked = false;
		int wasMaxDamage = s.tuckMaxDamage, wasDamage = s.tuckDamage;
		boolean wasCharged = s.tuckCharged;
		s.tuckMaxDamage = 0;
		s.tuckDamage = 0;
		s.tuckCharged = false;
		try {
			ArmStash.write(p, s.record());
			refreshSlots(p, false);
			write(p, () -> {
				purgeOwn(p, null);
				inv.setItem(s.slot, arm);
			});
		} catch (RuntimeException e) {
			// back to tucked: the slot still holds the real item, which is where it belongs
			s.tucked = true;
			s.tuckMaxDamage = wasMaxDamage;
			s.tuckDamage = wasDamage;
			s.tuckCharged = wasCharged;
			s.stash = new byte[0];
			s.nonce = oldNonce;
			safely("the record after a failed untuck", () -> ArmStash.write(p, s.record()));
			plugin.getLogger().log(Level.SEVERE, "Untucking " + p.getName() + "'s arm failed; it stays tucked.", e);
			return;
		}
		views.put(p.getEntityId(), s.view());
		lookChanged(p, s.view(), false);
		handChanged(p); // the view is untucked now: every viewer, not only those with the pack, gets the real arm item's hand
		refreshArsenal(p);
		plugin.survival().markArmSync(p);
		report(p, "untucked " + s.form + " L" + s.level + " in slot " + s.slot + ", holding " + ArmStash.describe(bytes));
	}

	/** The player's screen is gone for certain (death): the flag a missed CLOSED would leave set goes. */
	void screenGone(Player p) {
		screens.remove(p.getUniqueId());
	}

	/**
	 * Form the arm bound to {@code slot}, because the player scrolled onto it, closed a screen, joined or came back. A form
	 * the player doesn't own any more loses its binding; any other refusal (no biomass, a morph, a spectator, a form
	 * switched off) keeps it and is said once: the same refusal is not said again until an arm forms or the reason changes
	 * (SaidOnce), and never while the player is dead. Nothing forms while a screen is open (the CLOSED forms it).
	 */
	private void deployBound(Player p, int slot, String form) {
		if (screens.contains(p.getUniqueId()) || !p.isOnline() || p.isDead()) return;
		// Nobody asked for this arm (a scroll, a join, a screen closing), and a morphed player has no arm to form: the binding
		// waits, and nothing is said (the owner's join pops no arm and says nothing either, whichever order the morph restore and
		// this deploy come in). A chosen arm still says "turn back first" (form).
		if (plugin.morphs().activeForm(p) != null || plugin.morphs().isTransitioning(p)) {
			report(p, "arm slot " + slot + " not formed: the player is morphed");
			return;
		}
		ArmForm f = ArmForm.of(form);
		if (f == null || !f.tool()) {
			saveSlots(p, slots(p).unbind(slot)); // a form this build doesn't know
			refreshSlots(p, false);
			return;
		}
		if (levelOf(p, form) < 1) {
			saveSlots(p, slots(p).unbindForm(form));
			refreshSlots(p, false);
			refuseOnce(p, "The " + f.displayName() + " isn't yours any more: its slot is free.");
			return;
		}
		String why = form(p, form, Cause.SCROLL, slot, 0, false);
		if (why != null) refuseOnce(p, why);
	}

	/** {@link #refuse} for an arm nobody asked for: once per reason (SaidOnce), and never to a player who is dead. */
	private void refuseOnce(Player p, String why) {
		if (p.isDead()) return;
		if (saidOnce.shouldSay(p.getUniqueId(), why)) refuse(p, why);
		else report(p, "arm slot refused again, not repeated: " + why);
	}

	/** One refusal, to the player and the report: a modded client shows it as a result, anyone else on the action bar. */
	private void refuse(Player p, String why) {
		report(p, "arm slot refused: " + why);
		if (plugin.channel().drawsArms(p)) plugin.channel().sendResult(p, ClientChannel.RESULT_DENIED, why);
		else p.sendActionBar(Component.text(why, NamedTextColor.GRAY));
	}

	/** Bindings whose form the player no longer owns are dropped (not the slot of an admin's free arm out now). */
	private void pruneSlots(Player p) {
		ArmSession s = sessions.get(p.getUniqueId());
		ArmSlots before = slots(p);
		ArmSlots after = before.retain(form -> levelOf(p, form) >= 1 || (s != null && s.free && form.equals(s.form)));
		if (after.equals(before)) return;
		saveSlots(p, after);
		refreshSlots(p, false);
		report(p, "arm slots now " + after.encode() + " (a form was lost)");
	}

	/** What one owner's S_ARM_SLOTS says now: their bindings and tiers, and the item under the held arm. */
	private ArmSlotsMsg slotsMsg(Player p) {
		List<ArmSlotsMsg.Slot> list = slotList(p);
		ArmSession s = sessions.get(p.getUniqueId());
		if (s == null || s.tucked) return ArmSlotsMsg.idle(list); // tucked: no arm item in the held slot, which holds its real item
		ItemStack under = ArmStash.decode(s.stash);
		if (under == null || under.isEmpty()) return new ArmSlotsMsg(list, true, "minecraft:air", 0, false);
		// the corner icon shimmers as the item would: an explicit glint setting decides, otherwise enchantments do
		Boolean override = under.getData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE);
		boolean glint = override != null ? override : !under.getEnchantments().isEmpty() || under.hasData(DataComponentTypes.STORED_ENCHANTMENTS);
		return new ArmSlotsMsg(list, true, under.getType().getKey().toString(), under.getAmount(), glint);
	}

	/** The bindings as S_ARM_SLOTS lists them: each with the tier its form would form at now (the biomass bar draws it down). */
	private List<ArmSlotsMsg.Slot> slotList(Player p) {
		List<ArmSlotsMsg.Slot> list = new ArrayList<>();
		for (ArmSlots.Binding b : slots(p).bindings()) {
			int owned = Math.max(1, levelOf(p, b.form()));
			list.add(new ArmSlotsMsg.Slot(b.slot(), b.form(), Math.clamp(effective(p, owned, null, false), 1, MAX_LEVEL)));
		}
		return list;
	}

	/**
	 * Once a second per player whose client draws slots: if a bound form's tier moved since the client was last told (the
	 * biomass bar crossed a third while no arm of that form was out, an upgrade, a mode reset), it is told again. The list
	 * is the signature; {@link #refreshSlots} still drops a message that says what the last one did.
	 */
	private void checkTiers(Player p) {
		UUID id = p.getUniqueId();
		if (!started || !plugin.channel().drawsSlots(p)) {
			tiersSeen.remove(id);
			return;
		}
		List<ArmSlotsMsg.Slot> now = slotList(p);
		if (now.equals(tiersSeen.put(id, now))) return;
		refreshSlots(p, false);
	}

	/**
	 * S_ARM_SLOTS to the owner (their client only, and only from SLOTS_SINCE): on join, on every binding change and on every
	 * deploy or retract. One that says what the last one did is not sent again unless {@code force} (a client that has
	 * just joined has heard nothing). Never throws.
	 */
	void refreshSlots(Player p, boolean force) {
		if (!started || !plugin.channel().drawsSlots(p)) return;
		safely("S_ARM_SLOTS to " + p.getName(), () -> {
			ArmSlotsMsg m = slotsMsg(p);
			if (!force && m.equals(slotsSent.get(p.getUniqueId()))) return;
			slotsSent.put(p.getUniqueId(), m);
			plugin.channel().sendArmSlots(p, m);
		});
	}

	// ------------------------------------------------------------------ retracting

	/**
	 * A chosen retract (J tap, /morph arm off, the wheel's centre): the retract timeline, then the item comes back. The
	 * held slot is unbound too (2026-11-16).
	 */
	@Nullable String retractChosen(Player p) {
		return retract(p, ArmProtocol.WHY_CHOSEN);
	}

	/**
	 * A retract on the retract timeline, for {@code why} (CHOSEN, or ADMIN for /morph arm <player> off), which also unbinds
	 * the slot the arm is in (the held one, with no arm out), unless the arm is an admin's one-off for a form the slot isn't
	 * bound to. An arm already retracting or melting just carries on.
	 */
	@Nullable String retract(Player p, int why) {
		ArmSession s = sessions.get(p.getUniqueId());
		int slot = s != null ? s.slot : p.getInventory().getHeldItemSlot();
		ArmSlots before = slots(p);
		if (s == null && before.formAt(slot) == null) return why == ArmProtocol.WHY_CHOSEN ? "Your arm isn't formed." : p.getName() + "'s arm isn't formed.";
		// an admin's one-off arm sits on a slot bound to some other form (or none): that binding is not its to free
		saveSlots(p, s != null && !s.form.equals(before.formAt(slot)) ? before : before.unbind(slot));
		if (s != null && !s.ending()) end(p, s, ArmProtocol.STATE_RETRACTING, why);
		refreshSlots(p, false);
		return null;
	}

	/**
	 * {@code /morph arm off all}: every slot unbound, and the arm retracted if one is out (2026-11-16).
	 *
	 * @return null when done, otherwise why not
	 */
	@Nullable String unbindAll(Player p, int why) {
		ArmSession s = sessions.get(p.getUniqueId());
		if (s == null && slots(p).isEmpty()) return why == ArmProtocol.WHY_CHOSEN ? "You have no arm slots." : p.getName() + " has no arm slots.";
		saveSlots(p, ArmSlots.EMPTY);
		if (s != null && !s.ending()) end(p, s, ArmProtocol.STATE_RETRACTING, why);
		refreshSlots(p, false);
		return null;
	}

	/** Start a retract or melt: the arm stays in the slot until its timeline ends, and nothing can be done with it. */
	private void end(Player p, ArmSession s, int state, int why) {
		abortSpecial(p, s);
		s.endWhy = why;
		s.enter(state, tick);
		changed(p, s, why);
		safely("the retract effects", () -> {
			if (state == ArmProtocol.STATE_MELTING) effects.melting(p);
			else effects.retracting(p);
		});
		report(p, (state == ArmProtocol.STATE_MELTING ? "melting" : "retracting") + " (" + ArmProtocol.whyName(why) + ")");
	}

	/**
	 * FINISH (spec 3.3): the session ends now. Running specials stop and the drain due is billed; S_ARM NONE goes out
	 * before the slot changes; every arm but the session's own at home is deleted; the item comes back into the home
	 * slot (or, if the arm left it, wherever it fits: lostArm); the record is cleared. All in this one call, so the
	 * saved data and the inventory never disagree.
	 */
	void finish(Player p, ArmSession s, int why, Overflow overflow) {
		UUID id = p.getUniqueId();
		if (sessions.get(id) != s) return;
		boolean completed = s.ending() && s.elapsed(tick) >= s.duration;
		int endedState = s.state;
		sessions.remove(id);
		// 1. the special stops, and the drain due so far is billed, so a retract never dodges it
		abortSpecial(p, s);
		safely("the drain flush for " + p.getName(), () -> billing.flush(p, anyOn(p)));
		// 2. the stash as an item (null: its bytes don't decode, so the hand stays empty and the bytes are kept below)
		ItemStack decoded = ArmStash.decode(s.stash);
		ItemStack stash = decoded == null ? ItemStack.empty() : decoded;
		views.remove(p.getEntityId());
		// 7, sent first (decision D26): clients learn the arm is gone before the slot changes under them. The binding stays
		// (scrolling away, an inventory screen opening and every forced retract keep it), so the owner's slots say only that
		// no arm is out any more.
		tellClients(p, stateMsg(p, null, why));
		if (why != ArmProtocol.WHY_QUIT) refreshSlots(p, false);
		// 3-5 together: the item back in its slot (or wherever it fits), every other arm gone, the record cleared
		PlayerInventory inv = p.getInventory();
		write(p, () -> {
			purgeOwn(p, s);
			if (isSessionArm(s, inv.getItem(s.slot))) inv.setItem(s.slot, stash.isEmpty() ? null : stash);
			else lostArm(p, s.slot, stash, overflow);
		});
		if (decoded == null) keepUndecodable(p, s.stash);
		ArmStash.clear(p);
		// 6. the lockout, and the rest of what follows a change (none of it touches the item)
		if (why != ArmProtocol.WHY_DEATH && why != ArmProtocol.WHY_QUIT && why != ArmProtocol.WHY_DISABLE && why != ArmProtocol.WHY_SLOT) {
			lockouts.put(id, tick + config.reformLockoutTicks());
		}
		lookChanged(p, null, false);
		// A tucked arm's end writes nothing to the held slot (it already holds the real item), so vanilla announces nothing and
		// every viewer would keep the stub: send the hand to everyone tracking the owner, now that the view is gone. Not for a
		// quit: the owner is leaving, and viewers forget them.
		if (s.tucked && why != ArmProtocol.WHY_QUIT) handChanged(p);
		safely("the arm's retract effects", () -> {
			if (completed && endedState == ArmProtocol.STATE_RETRACTING) effects.retracted(p);
			else if (completed && endedState == ArmProtocol.STATE_MELTING) effects.melted(p);
			else effects.popped(p);
		});
		if (why != ArmProtocol.WHY_QUIT) refreshArsenal(p);
		plugin.survival().markArmSync(p);
		report(p, "retracted " + s.form + " (" + ArmProtocol.whyName(why) + "), gave back " + ArmStash.describe(s.stash));
	}

	/**
	 * Stash bytes that didn't decode: kept as a pending entry for /morph arm recover. Written in the same call that clears
	 * the session record, so the bytes are saved exactly once, never in both.
	 */
	private void keepUndecodable(Player p, byte[] stash) {
		ArmStash.addPending(p, stash, ArmStash.DECODE);
		plugin.getLogger().severe("The item " + p.getName() + "'s arm replaced (" + stash.length + " bytes) couldn't be read back. It is kept; an admin"
				+ " can retry with /morph arm " + p.getName() + " recover.");
	}

	/** Every arm in the player's inventory and on their cursor except this session's own at home. */
	private void purgeOwn(Player p, @Nullable ArmSession s) {
		PlayerInventory inv = p.getInventory();
		for (int slot = 0; slot < inv.getSize(); slot++) {
			ItemStack item = inv.getItem(slot);
			if (!ArmItems.isArm(item)) continue;
			if (s != null && slot == s.slot && isSessionArm(s, item)) continue;
			if (s != null && ArmItems.nonceOf(item) == s.nonce) plugin.getLogger().warning("A copy of " + p.getName() + "'s arm was in slot " + slot + "; deleted.");
			inv.setItem(slot, null);
		}
		if (ArmItems.isArm(p.getItemOnCursor())) p.setItemOnCursor(null);
	}

	/**
	 * The arm left its slot without a retract: the item goes back into the slot if it is empty, else wherever it fits,
	 * and what doesn't fit drops at the player's feet or waits for their next join.
	 */
	private void lostArm(Player p, int slot, ItemStack stash, Overflow overflow) {
		if (stash.isEmpty()) return;
		PlayerInventory inv = p.getInventory();
		ItemStack there = slot >= 0 && slot < inv.getSize() ? inv.getItem(slot) : null;
		if (slot >= 0 && slot < inv.getSize() && (there == null || there.isEmpty())) {
			inv.setItem(slot, stash);
			return;
		}
		for (ItemStack rest : inv.addItem(stash).values()) overflow(p, rest, overflow);
	}

	private void overflow(Player p, ItemStack item, Overflow overflow) {
		if (overflow == Overflow.DROP && p.isOnline() && !p.isDead()) {
			// another plugin may refuse the item entity (a no-drops world); then it waits for the next join instead
			if (p.getWorld().dropItemNaturally(p.getLocation(), item).isValid()) {
				report(p, "no room for " + item.getAmount() + " × " + item.getType().getKey().getKey() + ": dropped at their feet");
				return;
			}
		}
		ArmStash.addPending(p, item.serializeAsBytes(), ArmStash.OVERFLOW);
		plugin.getLogger().info("No room for " + p.getName() + "'s " + item.getType().getKey().getKey() + "; it comes back at their next join.");
	}

	private void abortSpecial(Player p, ArmSession s) {
		ArmSpecialModule m = specials.get(s.form);
		if (m == null) return;
		try {
			m.abort(p);
		} catch (Throwable t) {
			plugin.getLogger().log(Level.WARNING, "The " + s.form + " arm's special failed to stop.", t);
		}
	}

	// ------------------------------------------------------------------ death, joining, leaving

	/**
	 * G22, at the lowest priority so every later plugin (gravestones, death chests) sees the corrected lists: the arm is
	 * swapped for the item it replaced in the drops, the items to keep and the live slot, exactly once whatever
	 * keepInventory says (DeathLedger), and the session ends with no lockout and no effects.
	 */
	void onDeath(PlayerDeathEvent event) {
		Player p = event.getEntity();
		UUID id = p.getUniqueId();
		screens.remove(id); // dying closes the screen
		ArmSession s = sessions.get(id);
		switchOffAll(p, ArmProtocol.WHY_DEATH);
		List<ItemStack> drops = event.getDrops();
		List<ItemStack> kept = event.getItemsToKeep();
		if (s == null) {
			drops.removeIf(ArmItems::isArm);
			kept.removeIf(ArmItems::isArm);
			return;
		}
		sessions.remove(id);
		abortSpecial(p, s);
		safely("the drain flush for " + p.getName(), () -> billing.flush(p, false));
		ItemStack decoded = ArmStash.decode(s.stash);
		ItemStack stash = decoded == null ? ItemStack.empty() : decoded;
		int dropsIndex = indexOf(drops, s.nonce), keepIndex = indexOf(kept, s.nonce);
		DeathLedger.Plan plan = DeathLedger.plan(event.getKeepInventory(), dropsIndex, keepIndex, stash.isEmpty());
		replace(drops, dropsIndex, plan.replaceInDrops() && !plan.removeOnly(), stash);
		replace(kept, keepIndex, plan.replaceInKept() && !plan.removeOnly(), stash);
		views.remove(p.getEntityId());
		tellClients(p, stateMsg(p, null, ArmProtocol.WHY_DEATH));
		refreshSlots(p, false); // the bindings outlive the death (2026-11-16); only the arm is gone
		PlayerInventory inv = p.getInventory();
		write(p, () -> {
			boolean homeOk = isSessionArm(s, inv.getItem(s.slot));
			purgeOwn(p, null);
			if (!plan.writeSlot()) return;
			ItemStack there = inv.getItem(s.slot);
			if (homeOk || there == null || there.isEmpty()) {
				inv.setItem(s.slot, stash.isEmpty() ? null : stash);
			} else if (!stash.isEmpty() && event.getKeepInventory()) {
				// the arm had already left its slot and something else is there: with keepInventory the item must still
				// come back once, so it goes wherever it fits, and what doesn't fit drops with the death
				for (ItemStack rest : inv.addItem(stash).values()) drops.add(rest);
			}
		});
		if (decoded == null) keepUndecodable(p, s.stash);
		ArmStash.clear(p);
		lookChanged(p, null, false);
		if (s.tucked) handChanged(p); // the stub must not stay on a body that lies dead with its real item (see finish)
		plugin.survival().markArmSync(p);
		report(p, "died with " + s.form + " formed (keepInventory " + event.getKeepInventory() + "): " + ArmStash.describe(s.stash) + " "
				+ (plan.removeOnly() ? "(nothing to give back)" : plan.replaceInDrops() ? "drops" : "kept"));
	}

	private static int indexOf(List<ItemStack> items, long nonce) {
		for (int i = 0; i < items.size(); i++) {
			if (ArmItems.isArm(items.get(i)) && ArmItems.nonceOf(items.get(i)) == nonce) return i;
		}
		return -1;
	}

	/** Every arm out of the list; with {@code put}, the stash where the session's arm was (added at the end if it wasn't listed). */
	private static void replace(List<ItemStack> items, int index, boolean put, ItemStack stash) {
		List<ItemStack> out = new ArrayList<>(items.size());
		int at = -1;
		for (int i = 0; i < items.size(); i++) {
			ItemStack item = items.get(i);
			if (ArmItems.isArm(item)) {
				if (i == index) at = out.size();
				continue;
			}
			out.add(item);
		}
		if (put && !stash.isEmpty()) {
			if (at >= 0) out.add(at, stash.clone());
			else out.add(stash.clone());
		}
		items.clear();
		items.addAll(out);
	}

	/**
	 * The join repair (also run for every online player at enable, for /reload): stray arms deleted from the inventory,
	 * cursor and ender chest; a saved session's item given back (the server stopped or crashed while it was formed);
	 * and items owed from earlier (no room when they left) handed over. Entries whose bytes don't decode stay, for
	 * /morph arm <player> recover.
	 */
	@Override
	public void reconcile(Player p) {
		if (!started) return;
		try {
			slotCache.remove(p.getUniqueId()); // the bindings are read from the player's data again
			slotsSent.remove(p.getUniqueId());
			tiersSeen.remove(p.getUniqueId());
			saidOnce.clear(p.getUniqueId());
			screens.remove(p.getUniqueId());
			heldWatch.forget(p.getUniqueId());
			ArmSession live = sessions.get(p.getUniqueId());
			if (live != null) finish(p, live, ArmProtocol.WHY_ERROR, Overflow.DROP);
			int purged = purgeEverywhere(p);
			List<String> repaired = new ArrayList<>();
			if (purged > 0) repaired.add("deleted " + purged + (purged == 1 ? " stray arm" : " stray arms"));
			restoreRecord(p, repaired);
			List<ArmStash.Pending> waiting = ArmStash.pending(p);
			if (!waiting.isEmpty()) {
				List<ArmStash.Pending> kept = new ArrayList<>();
				int given = 0;
				for (ArmStash.Pending e : waiting) {
					ItemStack item = e.reason().equals(ArmStash.DECODE) ? null : ArmStash.decode(e.stash());
					if (item == null) {
						kept.add(e);
						continue;
					}
					if (item.isEmpty()) continue;
					write(p, () -> lostArm(p, -1, item, Overflow.DROP));
					given++;
				}
				ArmStash.setPending(p, kept);
				if (given > 0) repaired.add("handed over " + given + (given == 1 ? " item" : " items") + " owed from before");
				if (!kept.isEmpty()) {
					plugin.getLogger().warning(p.getName() + " has " + kept.size() + " arm item(s) that couldn't be read. /morph arm " + p.getName()
							+ " recover retries; /morph arm " + p.getName() + " info lists them.");
				}
			}
			if (!repaired.isEmpty()) plugin.getLogger().info("Repaired " + p.getName() + "'s arm at join: " + String.join("; ", repaired) + ".");
			sync(p);
			heldWatch.note(p.getUniqueId(), p.getInventory().getHeldItemSlot());
			// A bound held slot forms its arm (2026-11-16). Not here: the join's morph handling (MONITOR, after this) has
			// not run yet, and it restores a saved morph only RESTORE_DELAY_TICKS later, so the deploy asks after both.
			deployHeldAfterJoin(p);
		} catch (Throwable t) {
			plugin.getLogger().log(Level.SEVERE, "The arm repair for " + p.getName() + " failed.", t);
		}
	}

	/**
	 * A saved session record with no live session behind it (the server stopped or crashed while the arm was formed):
	 * its item goes back into its slot if that is empty, else wherever it fits, else at the player's feet; bytes that
	 * don't decode are kept for /morph arm recover. The record is cleared in the same call. Strays must be purged first,
	 * so the arm itself no longer fills the slot.
	 */
	private void restoreRecord(Player p, List<String> repaired) {
		if (!ArmStash.has(p)) return;
		SessionRecord r = ArmStash.read(p);
		if (r == null) {
			plugin.getLogger().severe(p.getName() + " has an arm record that can't be read; it is removed. If they lost the item their arm held, it"
					+ " can't be recovered from it.");
		} else {
			ItemStack stash = ArmStash.decode(r.stash());
			if (stash == null) {
				ArmStash.addPending(p, r.stash(), ArmStash.DECODE);
				plugin.getLogger().severe("The item " + p.getName() + "'s saved arm held couldn't be read; kept for /morph arm " + p.getName() + " recover.");
				repaired.add("kept an unreadable item for /morph arm recover");
			} else if (!stash.isEmpty()) {
				write(p, () -> lostArm(p, r.slot(), stash, Overflow.DROP));
				repaired.add("gave back " + ArmStash.describe(r.stash()) + " from a " + r.form() + " arm formed when the server last stopped");
			}
		}
		ArmStash.clear(p);
	}

	/** Every arm-tagged stack the player carries or keeps: inventory, cursor and ender chest. */
	private int purgeEverywhere(Player p) {
		int n = 0;
		PlayerInventory inv = p.getInventory();
		for (int slot = 0; slot < inv.getSize(); slot++) {
			if (ArmItems.isArm(inv.getItem(slot))) {
				int at = slot;
				write(p, () -> inv.setItem(at, null));
				n++;
			}
		}
		if (ArmItems.isArm(p.getItemOnCursor())) {
			p.setItemOnCursor(null);
			n++;
		}
		n += purge(p.getEnderChest());
		return n;
	}

	/** /morph arm <player> recover: retry the items whose bytes didn't decode. */
	String recover(Player p) {
		List<ArmStash.Pending> waiting = ArmStash.pending(p);
		if (waiting.isEmpty()) return p.getName() + " has no arm items waiting.";
		List<ArmStash.Pending> kept = new ArrayList<>();
		int given = 0;
		for (ArmStash.Pending e : waiting) {
			ItemStack item = ArmStash.decode(e.stash());
			if (item == null) {
				kept.add(e);
				continue;
			}
			if (!item.isEmpty()) write(p, () -> lostArm(p, -1, item, Overflow.DROP));
			given++;
		}
		ArmStash.setPending(p, kept);
		plugin.getLogger().info("Recovered " + given + " of " + waiting.size() + " waiting arm item(s) for " + p.getName() + ".");
		return "Gave " + p.getName() + " " + given + " of " + waiting.size() + " waiting item(s)" + (kept.isEmpty() ? "." : "; " + kept.size()
				+ " still can't be read (they stay saved).");
	}

	/** Left the server: what is kept per player in memory goes (cooldowns and the last form stay, so a rejoin can't reset them). */
	void forget(Player p) {
		UUID id = p.getUniqueId();
		lockouts.remove(id);
		noTargetLocks.remove(id);
		on.remove(id);
		modes.remove(id);
		warned.remove(id);
		depleted.remove(id);
		specialHits.remove(id);
		probeHits.remove(id);
		specialBreaks.remove(id);
		slotCache.remove(id); // the bindings themselves stay on the player: they are read again at the next join
		slotsSent.remove(id);
		tiersSeen.remove(id);
		saidOnce.clear(id);
		screens.remove(id);
		heldWatch.forget(id);
		billing.forget(id);
	}

	/**
	 * After a respawn or a world change: the player's own state to the players around them, and theirs to the player. The
	 * arm slots stay through both (2026-11-16): they are written again if the respawn dropped them from the
	 * player's data, and the owner's client hears them once more. A bound held slot forms its arm again (nothing else
	 * would: no held event comes): at once after a respawn, which is already a tick after it, and a tick after a world
	 * change, so a per-world inventory plugin has swapped the inventory first.
	 */
	void syncWorld(Player p, boolean worldChanged) {
		if (!started) return;
		if (worldChanged) screens.remove(p.getUniqueId()); // the screen did not come along
		ArmSlots known = slotCache.get(p.getUniqueId());
		if (known != null && !known.isEmpty() && ArmStash.readSlots(p).isEmpty()) ArmStash.writeSlots(p, known);
		refreshSlots(p, true);
		if (sessions.containsKey(p.getUniqueId()) || anyOn(p)) {
			tellClients(p, stateMsg(p, sessions.get(p.getUniqueId()), ArmProtocol.WHY_CHOSEN));
		}
		if (worldChanged) deployHeldSoon(p);
		else deployHeld(p);
		if (!plugin.channel().drawsArms(p)) return;
		for (Player other : p.getWorld().getPlayers()) {
			if (other != p && (sessions.containsKey(other.getUniqueId()) || anyOn(other))) {
				tellViewer(p, stateMsg(other, sessions.get(other.getUniqueId()), ArmProtocol.WHY_CHOSEN));
			}
		}
	}

	// ------------------------------------------------------------------ the accessories

	/** The accessory's module is in this build and its section doesn't say {@code enabled: false}. */
	boolean accessoryBuilt(String id) {
		return accessories.containsKey(id) && config.formEnabled(id);
	}

	boolean lanternBuilt() {
		return accessoryBuilt(ArmForm.LANTERN.id());
	}

	@Nullable ArmAccessoryModule accessory(String id) {
		return accessories.get(id);
	}

	/** The lantern's own words ("lit", "out") and the others' ("on", "off"), so the lantern's messages read as they always have. */
	private static String onWord(String id) {
		return id.equals(ArmForm.LANTERN.id()) ? "lit" : "on";
	}

	private static String offWord(String id) {
		return id.equals(ArmForm.LANTERN.id()) ? "out" : "off";
	}

	/**
	 * Switch an accessory on (spec 3.10's checks, for each accessory); null when on, otherwise why not. An admin's switch skips
	 * ownership and the biomass minimum, and with {@code free} the accessory doesn't drain until it goes off.
	 */
	@Nullable String switchOn(Player p, String id, boolean admin, int levelOverride, boolean free) {
		ArmForm f = ArmForm.of(id);
		ArmAccessoryModule module = accessories.get(id);
		if (f == null || f.tool()) return "There is no arm accessory '" + id + "'.";
		String name = f.displayName();
		if (module == null) return "The " + name + " isn't in this build of RE:Morph.";
		if (isOn(p, id)) return admin ? p.getName() + "'s " + id + " is already " + onWord(id) + "." : "Your " + id + " is already " + onWord(id) + ".";
		if (!config.enabled()) return "Arm forms are switched off on this server.";
		if (!config.formEnabled(id)) return "The " + name + " is switched off on this server.";
		if (!admin && !(p.hasPermission("morphreborn.arm") && p.hasPermission(f.permission()))) {
			return "You are not allowed to use the " + name + ".";
		}
		if (p.isDead()) return "You can't switch the " + id + " on right now.";
		if (p.getGameMode() == GameMode.SPECTATOR) return "Spectators have no arm to use.";
		if (plugin.morphs().activeForm(p) != null || plugin.morphs().isTransitioning(p)) return "The " + id + " is for your own body only: turn back first.";
		int level = levelOverride > 0 ? Math.clamp(levelOverride, 1, MAX_LEVEL) : levelOf(p, id);
		if (admin && level < 1) level = 1;
		if (level < 1) return levelWhy(p, f);
		if (!admin && !free(p) && balanceMilli(p, capacityMilli(p)) < config.minToFormMilli()) {
			return "The " + id + " needs at least " + ArmItemSpecs.biomass(config.minToFormMilli()) + " biomass to switch on.";
		}
		String why;
		try {
			why = module.switchOn(p, level);
		} catch (Throwable t) {
			plugin.getLogger().log(Level.SEVERE, "The " + id + " failed to switch on for " + p.getName() + ".", t);
			return "The " + id + " failed to switch on; the server log says why.";
		}
		if (why != null) return why;
		ArrayList<On> mineOn = on.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
		mineOn.removeIf(o -> o.id().equals(id)); // a stale entry of a module that went off on its own since the last check
		mineOn.add(new On(id, level, admin && free));
		int mode = modeOf(p, id, level);
		if (mode > 0) {
			try {
				module.mode(p, mode);
			} catch (Throwable t) {
				plugin.getLogger().log(Level.WARNING, "The " + id + "'s mode couldn't be set for " + p.getName() + ".", t);
			}
		}
		if (!sessions.containsKey(p.getUniqueId())) warned.remove(p.getUniqueId());
		tellClients(p, stateMsg(p, sessions.get(p.getUniqueId()), admin ? ArmProtocol.WHY_ADMIN : ArmProtocol.WHY_CHOSEN));
		refreshArsenal(p);
		plugin.survival().markArmSync(p);
		report(p, id + " " + onWord(id) + " at L" + level);
		return null;
	}

	/** Switch one accessory off (it restores everything it did) and tell clients. Nothing if it isn't on. */
	void switchOff(Player p, String id, int why) {
		switchOffSome(p, List.of(id), why);
	}

	/** Switch every accessory the player has on off at once (one S_ARM, one S_ARSENAL): death, a morph, a world change, depletion, a reload. */
	void switchOffAll(Player p, int why) {
		switchOffSome(p, List.copyOf(accessories.keySet()), why);
	}

	private void switchOffSome(Player p, List<String> ids, int why) {
		UUID uid = p.getUniqueId();
		ArrayList<On> mine = on.get(uid);
		boolean any = false;
		for (String id : ids) {
			boolean was = mine != null && mine.removeIf(o -> o.id().equals(id));
			boolean now = moduleOn(p, id);
			if (!was && !now) continue;
			any = true;
			if (now) {
				try {
					accessories.get(id).switchOff(p, why);
				} catch (Throwable t) {
					plugin.getLogger().log(Level.WARNING, "The " + id + " failed to go " + offWord(id) + " for " + p.getName() + ".", t);
				}
			}
			report(p, id + " " + offWord(id) + " (" + ArmProtocol.whyName(why) + ")");
		}
		if (mine != null && mine.isEmpty()) on.remove(uid);
		if (!any) return;
		tellClients(p, stateMsg(p, sessions.get(uid), why));
		if (why != ArmProtocol.WHY_QUIT) refreshArsenal(p);
		plugin.survival().markArmSync(p);
	}

	/** The module says it is on for this player. A module that throws is off. */
	private boolean moduleOn(Player p, String id) {
		ArmAccessoryModule m = accessories.get(id);
		if (m == null) return false;
		try {
			return m.isOn(p);
		} catch (Throwable t) {
			return false;
		}
	}

	boolean isOn(Player p, String id) {
		return moduleOn(p, id);
	}

	/** The lantern is lit (the one accessory whose brightness Sneak+F steps, with no tool out). */
	boolean isLit(Player p) {
		return moduleOn(p, ArmForm.LANTERN.id());
	}

	/** Some accessory is on, the lantern or any other. */
	boolean anyOn(Player p) {
		for (String id : accessories.keySet()) {
			if (moduleOn(p, id)) return true;
		}
		return false;
	}

	/** The player's accessories that are on, in the order they were switched on. */
	List<String> onIds(Player p) {
		ArrayList<On> mine = on.get(p.getUniqueId());
		if (mine == null) return List.of();
		List<String> out = new ArrayList<>(mine.size());
		for (int i = 0; i < mine.size(); i++) {
			String id = mine.get(i).id();
			if (moduleOn(p, id)) out.add(id);
		}
		return out;
	}

	/**
	 * What the player's accessories drain a minute, in milli, summed over those that are on (and not an admin's free ones): the part of
	 * ArmBilling's rate every tick asks for, so it allocates nothing (no list, no iterator).
	 */
	long accessoryDrainPerMinute(Player p) {
		ArrayList<On> mine = on.get(p.getUniqueId());
		if (mine == null) return 0;
		var costs = config.costs();
		long total = 0;
		for (int i = 0; i < mine.size(); i++) {
			On o = mine.get(i);
			if (!o.free() && moduleOn(p, o.id())) total += costs.accessoryDrainPerMinute(o.id());
		}
		return total;
	}

	/** Players with an accessory on, as this manager last told clients: whose drain the billing counts. */
	Set<UUID> accessoryOwners() {
		return on.keySet();
	}

	/** The accessory was switched on by an admin with {@code free}: it doesn't drain. */
	boolean accessoryFree(Player p, String id) {
		On o = find(on.get(p.getUniqueId()), id);
		return o != null && o.free();
	}

	/** The level an accessory is on at, or (when it isn't on) the level the player owns, at least 1: what S_ARM and its mode are worked out at. */
	private int accessoryLevel(Player p, String id) {
		On o = find(on.get(p.getUniqueId()), id);
		return o != null ? o.level() : Math.max(1, levelOf(p, id));
	}

	private static String modeKey(String id) {
		return id.equals(ArmForm.LANTERN.id()) ? LANTERN_BRIGHT_KEY : id + "_mode";
	}

	/** The mode the player chose for an accessory, as kept on them: the module's default until they choose. Not yet kept to the level's range. */
	private int chosenMode(Player p, String id) {
		ArmAccessoryModule m = accessories.get(id);
		if (m == null) return 0;
		Map<String, Integer> mine = modes.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
		Integer chosen = mine.get(id);
		if (chosen == null) {
			Integer kept = p.getPersistentDataContainer().get(new org.bukkit.NamespacedKey(plugin, modeKey(id)), org.bukkit.persistence.PersistentDataType.INTEGER);
			chosen = kept == null ? m.defaultMode() : kept;
			mine.put(id, chosen);
		}
		return chosen;
	}

	/** Keep the player's choice of mode for an accessory, in memory and on the player. */
	private void keepMode(Player p, String id, int n) {
		modes.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(id, n);
		p.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, modeKey(id)), org.bukkit.persistence.PersistentDataType.INTEGER, n);
	}

	/** The mode an accessory of {@code level} runs in: the player's choice, as far as the level goes; 0 for an accessory without one. */
	private int modeOf(Player p, String id, int level) {
		ArmAccessoryModule m = accessories.get(id);
		int max = m == null ? 0 : m.maxMode(level);
		if (max <= 0) return 0;
		return Math.clamp(chosenMode(p, id), 1, max);
	}

	/**
	 * The lantern brightness {@code p} chose (1 to 5), as far as a lantern of {@code level} goes: kept on the player, 3
	 * (or the level's brightest, if that is less) until they choose.
	 */
	int lanternBrightness(Player p, int level) {
		return modeOf(p, ArmForm.LANTERN.id(), level);
	}

	/**
	 * {@code /morph arm lantern bright N}: sets {@code p}'s lantern brightness (1 to 5, how far its light reaches round
	 * them), kept on the player; a lit lantern changes at once. Returns what to tell them.
	 */
	String setLanternBrightness(Player p, int wanted) {
		String id = ArmForm.LANTERN.id();
		ArmAccessoryModule module = accessories.get(id);
		if (module == null) return "The Tendril Lantern isn't in this build of RE:Morph.";
		int level = accessoryLevel(p, id);
		int max = module.maxMode(level);
		int n = Math.clamp(wanted, 1, 5);
		keepMode(p, id, n);
		int used = Math.min(n, max);
		if (isLit(p)) {
			try {
				module.mode(p, used);
			} catch (Throwable t) {
				plugin.getLogger().log(Level.WARNING, "The lantern's brightness couldn't be set for " + p.getName() + ".", t);
			}
			tellClients(p, stateMsg(p, sessions.get(p.getUniqueId()), ArmProtocol.WHY_CHOSEN));
		}
		return n > max ? "Lantern brightness " + n + " kept; your lantern's level reaches " + max + ", so it shines at " + max + " for now."
				: "Lantern brightness " + n + ".";
	}

	/**
	 * One step of an accessory's mode (C_ARM {@link ArmProtocol#ACTION_ACCESSORY_MODE}): the lantern's brightness, or the mode of
	 * any accessory that has one. Clamped at the first and last mode, as the lantern's brightness is (the scroll doesn't wrap, the
	 * Sneak+F step does), kept on the player, and applied at once when the accessory is on. Null when done, otherwise why not. Free,
	 * with no cooldown, and it needs no biomass: choosing a mode is no more than choosing how it shines.
	 */
	@Nullable String stepMode(Player p, String id, int step) {
		ArmForm f = ArmForm.of(id);
		ArmAccessoryModule module = accessories.get(id);
		if (f == null || f.tool() || module == null) return "There is no arm accessory '" + id + "'.";
		if (!started || !config.enabled()) return "Arm forms are switched off on this server.";
		int level = accessoryLevel(p, id);
		if (!isOn(p, id) && levelOf(p, id) < 1) return levelWhy(p, f);
		int max = module.maxMode(level);
		if (max <= 0) return null; // an accessory with no mode ignores the scroll: no refusal to show
		int dir = Integer.signum(step);
		if (dir == 0) return null;
		int now = modeOf(p, id, level);
		int next = Math.clamp(now + dir, 1, max);
		if (next == now) return null; // already at the end of its range
		keepMode(p, id, next);
		if (isOn(p, id)) {
			try {
				module.mode(p, next);
			} catch (Throwable t) {
				plugin.getLogger().log(Level.WARNING, "The " + id + "'s mode couldn't be set for " + p.getName() + ".", t);
			}
			tellClients(p, stateMsg(p, sessions.get(p.getUniqueId()), ArmProtocol.WHY_CHOSEN));
		} else {
			// nothing is drawn to show it while the accessory is off, so the wheel's scroll gets its feedback on the action bar
			p.sendActionBar(Component.text((ArmForm.LANTERN.id().equals(id) ? "Lantern brightness " : f.displayName() + " mode ") + next + " of " + max, NamedTextColor.GRAY));
		}
		report(p, id + " mode " + next + "/" + max);
		return null;
	}

	// ------------------------------------------------------------------ specials

	/**
	 * The specials' gate (spec 3.6): formed with the arm in hand, the module built and idle, the cooldown over, the
	 * block rules allowing it. The first failure says why and costs nothing. Then the module finds its targets and pays
	 * through {@link #charge}; a swing at nothing only locks F for half a second.
	 *
	 * @param replyTo who hears why it didn't fire: null for F (the player's action bar), the command's sender for /morph
	 *            arm special (in chat)
	 */
	void special(Player p, boolean sneaking, @Nullable CommandSender replyTo) {
		ArmSession s = sessions.get(p.getUniqueId());
		if (s == null) {
			tell(p, "Form an arm first: " + (plugin.channel().drawsArms(p) ? "J" : "/morph arm <form>") + ".", replyTo);
			return;
		}
		if (s.state != ArmProtocol.STATE_FORMED) {
			tell(p, s.ending() ? "Your arm is going back." : "Your arm is still taking shape.", replyTo);
			return;
		}
		if (s.tucked) { // a screen is open: nothing to fire with (said only to a command's sender)
			if (replyTo != null) tell(p, "Your arm is tucked away while an inventory is open.", replyTo);
			return;
		}
		if (!isSessionArm(s, p.getInventory().getItemInMainHand())) return; // the audit retracts it next tick
		ArmSpecialModule m = specials.get(s.form);
		if (m == null) {
			tell(p, "This form's special isn't in this build of RE:Morph.", replyTo);
			return;
		}
		String name = m.specialName(s.level);
		if (m.running(p)) {
			tell(p, name + " is still going.", replyTo);
			return;
		}
		Integer locked = noTargetLocks.get(p.getUniqueId());
		if (locked != null && tick < locked) return;
		int left = cooldownLeft(p, s.form);
		if (left > 0 && !(sneaking && m.sneakBypassesCooldown())) {
			tell(p, name + " ready in " + seconds(left), replyTo);
			return;
		}
		if (m.breaksBlocks()) {
			if (!blockSpecialsAllowed(p.getWorld())) {
				tell(p, name + " can't break blocks in this world.", replyTo);
				return;
			}
			if (p.getGameMode() == GameMode.ADVENTURE) {
				tell(p, name + " can't break blocks in adventure mode.", replyTo);
				return;
			}
		}
		SpecialResult r;
		try {
			r = m.fire(p, s.level, sneaking, charge(p, s.form, name, false, replyTo));
		} catch (Throwable t) {
			plugin.getLogger().log(Level.SEVERE, name + " failed for " + p.getName() + ".", t);
			r = SpecialResult.refused(name + " failed.");
		}
		result(p, r, replyTo);
	}

	/**
	 * Sneak+F with no tool out and the lantern lit (or /morph arm brightness): the next brightness, back to 1 past the
	 * lantern's brightest (2026-11-15, in place of the Flare: the special is choosing how bright it glows and
	 * how far it lights round you; since 8.0.0-beta.2 with a tool out Sneak+F is the tool's own, and the wheel's scroll over the
	 * lantern steps it instead). Free and with no cooldown. {@code replyTo} as for {@link #special}.
	 */
	void stepBrightness(Player p, @Nullable CommandSender replyTo) {
		ArmAccessoryModule lantern = accessories.get(ArmForm.LANTERN.id());
		if (lantern == null || !isLit(p)) {
			tell(p, "Your lantern isn't lit.", replyTo);
			return;
		}
		int level = accessoryLevel(p, ArmForm.LANTERN.id());
		int max = lantern.maxMode(level);
		int now = lanternBrightness(p, level);
		int next = now >= max ? 1 : now + 1;
		setLanternBrightness(p, next);
		p.sendActionBar(Component.text("Lantern brightness " + next + " of " + max, NamedTextColor.GRAY));
		if (replyTo != null && replyTo != p) replyTo.sendMessage(Component.text(p.getName() + ": lantern brightness " + next + " of " + max, NamedTextColor.GRAY));
		report(p, "lantern brightness " + next + "/" + max);
	}

	private void result(Player p, SpecialResult r, @Nullable CommandSender replyTo) {
		if (r.outcome() == SpecialResult.Outcome.NO_TARGET) noTargetLocks.put(p.getUniqueId(), tick + NO_TARGET_LOCKOUT);
		if (!r.fired() && r.message() != null && !r.message().isEmpty()) tell(p, r.message(), replyTo);
		else if (replyTo != null && replyTo != p) {
			replyTo.sendMessage(Component.text(p.getName() + ": " + (r.fired() ? "fired." : r.outcome() == SpecialResult.Outcome.NO_TARGET
					? "nothing in reach." : "refused."), NamedTextColor.GRAY));
		}
	}

	/**
	 * Pays for a special: all or nothing through the gameplay mode (free in Classic and creative, and for an admin's free
	 * lantern). On success the cooldown starts (times the mode's cooldown factor) and shows as the vanilla sweep over the
	 * arm's slot (katana and pickaxe, whose items carry a cooldown group), and the wheel hears of it. A refusal is shown on
	 * the player's action bar (and to the command's sender): a module that was refused returns without a message of its
	 * own.
	 */
	private SpecialCharge charge(Player p, String key, String name, boolean free, @Nullable CommandSender replyTo) {
		return (costMilli, cooldownTicks) -> {
			ArmLedger.Spend spend = free ? ArmLedger.Spend.free(balanceMilli(p, capacityMilli(p))) : spend(p, costMilli, false);
			if (!spend.paid()) {
				String why = "Not enough biomass for " + name + " (needs " + ArmItemSpecs.biomass(costMilli) + ")";
				p.sendActionBar(Component.text(why, NamedTextColor.RED));
				if (replyTo != null && replyTo != p) replyTo.sendMessage(Component.text(why, NamedTextColor.RED));
				report(p, name + " refused: needs " + costMilli + " milli");
				return false;
			}
			int ticks = (int) Math.round(Math.max(0, cooldownTicks) * plugin.mode().cooldownFactor(p));
			cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(key, tick + ticks);
			if (ticks > 0 && ArmTiers.cooldownSweep(key)) p.setCooldown(Key.key(ArmItemSpecs.cooldownGroup(key)), ticks);
			refreshArsenal(p);
			report(p, name + ": " + (spend.charged() > 0 ? "-" + spend.charged() + " milli, " + spend.after() + " left" : "free") + ", cooldown " + ticks + " t");
			return true;
		};
	}

	int cooldownLeft(Player p, String key) {
		Map<String, Integer> m = cooldowns.get(p.getUniqueId());
		Integer until = m == null ? null : m.get(key);
		return until == null ? 0 : Math.max(0, until - tick);
	}

	private boolean blockSpecialsAllowed(World world) {
		return PluginSettings.WorldRule.valueOf(config.blockSpecials().name()).allows(world);
	}

	/** The lantern special's name: it steps the brightness (it was the Flare until 8.0.0). */
	String brightnessName() {
		return "Brightness";
	}

	private static String seconds(int ticks) {
		return ((ticks + 19) / 20) + "s";
	}

	/** A message about a key press goes to the player's action bar; about a command, into the sender's chat. */
	private static void tell(Player p, String message, @Nullable CommandSender replyTo) {
		if (replyTo != null) replyTo.sendMessage(Component.text(message, NamedTextColor.GRAY));
		else p.sendActionBar(Component.text(message, NamedTextColor.GRAY));
	}

	// ------------------------------------------------------------------ biomass

	/**
	 * Free for this player now: Classic, creative (before the mode is asked, as vanilla's infinite materials), arm-cost
	 * off, an admin's free arm, or not in survival or adventure.
	 */
	boolean free(Player p) {
		ArmSession s = sessions.get(p.getUniqueId());
		if (s != null && s.free) return true;
		if (!config.costs().enabled() || p.getGameMode() == GameMode.CREATIVE) return true;
		return plugin.mode().armsFree(p);
	}

	/** Bill through the gameplay mode. A spend that empties the biomass melts the arm and puts the lantern out on the next tick. */
	ArmLedger.Spend spend(Player p, long milli, boolean partial) {
		if (milli <= 0 || free(p)) return ArmLedger.Spend.free(balanceMilli(p, capacityMilli(p)));
		ArmLedger.Spend r = plugin.mode().armSpend(p, milli, partial);
		if (r.charged() > 0) warn(p, r.after());
		if (r.depleted()) depleted.add(p.getUniqueId());
		return r;
	}

	private void warn(Player p, long after) {
		int given = warned.getOrDefault(p.getUniqueId(), 0);
		if (after <= EMPTY_MILLI && given < 2) {
			warned.put(p.getUniqueId(), 2);
			p.sendActionBar(Component.text("Your arm is nearly out of biomass.", NamedTextColor.RED));
		} else if (after <= LOW_MILLI && given < 1) {
			warned.put(p.getUniqueId(), 1);
			p.sendActionBar(Component.text("Your arm is running low on biomass (" + ArmItemSpecs.biomass(after) + " left).", NamedTextColor.GOLD));
		}
	}

	/** The player's Biomass Capacity in milli (Survival), 0 in Classic. */
	long capacityMilli(Player p) {
		return plugin.survival().active() ? Math.round(plugin.survival().capacity(p) * 1000) : 0;
	}

	/** The player's biomass in milli; when the mode has none (Classic), {@code orElse}. */
	long balanceMilli(Player p, long orElse) {
		long b = plugin.mode().armBalanceMilli(p);
		return b == Long.MAX_VALUE ? orElse : b;
	}

	@Override
	public long drainMilliPerMinute(Player p) {
		return started ? billing.rate(p) : 0;
	}

	// ------------------------------------------------------------------ levels and what is built

	/** The player's level of a form: classic-level in Classic, the bought level in Survival (at least 1 for creative players), 0 when not allowed. */
	int levelOf(Player p, String form) {
		ArmForm f = ArmForm.of(form);
		if (f == null || !(p.hasPermission("morphreborn.arm") && p.hasPermission(f.permission()))) return 0;
		if (!plugin.survival().active()) return config.classicLevel();
		int bought = Math.clamp(plugin.mode().armLevel(p, f.id()), 0, MAX_LEVEL);
		return p.getGameMode() == GameMode.CREATIVE ? Math.max(1, bought) : bought;
	}

	/**
	 * The level a form's module works at: the formed session's own level when that session is this form (an admin's forced level, or
	 * the tier biomass has stepped the arm down to), else the level the player owns. Reading the owned level alone made a forced
	 * bow, whip, trident or crossbow hit like the owned one, and a thinned Survival arm keep its full strength.
	 */
	static int workingLevel(@Nullable ArmSession s, String form, int owned) {
		return s != null && s.form.equals(form) && s.level >= 1 ? s.level : owned;
	}

	private String levelWhy(Player p, ArmForm f) {
		var survival = plugin.survival();
		if (survival.active() && (!survival.isUnlocked(p) || survival.level(p, ArmForm.ARM_SHAPING) < 1)) {
			return "Arm forms need the Arm Shaping upgrade: /morph upgrades";
		}
		return "You haven't grown the " + f.displayName() + " yet: /morph upgrades";
	}

	/** The tool forms in this build and switched on, in the wheel's order. */
	List<String> builtForms() {
		List<String> out = new ArrayList<>();
		for (ArmForm f : ArmForm.WHEEL) {
			if (specials.containsKey(f.id()) && config.formEnabled(f.id())) out.add(f.id());
		}
		return out;
	}

	@Nullable ArmSpecialModule module(String form) {
		return specials.get(form);
	}

	@Override
	public boolean isBuilt(String upgradeId) {
		if (!enabled()) return false;
		if (ArmForm.ARM_SHAPING.equals(upgradeId)) return true;
		ArmForm f = ArmForm.byUpgrade(upgradeId);
		if (f == null) return false;
		return f.tool() ? specials.containsKey(f.id()) && config.formEnabled(f.id()) : accessoryBuilt(f.id());
	}

	@Override
	public Lines describe(String upgradeId, int level) {
		ArmForm f = ArmForm.byUpgrade(upgradeId);
		if (f == null || !isBuilt(upgradeId) || level < 1) return Lines.EMPTY;
		try {
			if (!f.tool()) {
				ArmAccessoryModule accessory = accessories.get(f.id());
				return accessory == null ? Lines.EMPTY : accessory.describeLines(level);
			}
			ArmSpecialModule m = specials.get(f.id());
			String tier = ArmTiers.tierName(level);
			String strength = Character.toUpperCase(tier.charAt(0)) + tier.substring(1) + " strength";
			int fortune = config.fortune(f.id(), level), efficiency = config.efficiency(f.id(), level);
			// the tier, Fortune and Efficiency are the level's own and stay; the special's pieces are typed by the special
			Lines.Builder b = Lines.builder().always(strength);
			if (fortune > 0) b.always("Fortune " + ArmItemSpecs.roman(fortune));
			if (efficiency > 0) b.always("Efficiency " + ArmItemSpecs.roman(efficiency));
			if (m != null) b.add(m.describeLines(level));
			return b.build();
		} catch (Throwable t) {
			plugin.getLogger().log(Level.WARNING, "Describing " + upgradeId + " failed.", t);
			return Lines.EMPTY;
		}
	}

	// ------------------------------------------------------------------ what clients are told

	/**
	 * S_ARM for one player: their arm (or none), the lantern in its own fields (older clients read only those), and every accessory
	 * that is on (the lantern included) in the list newer clients read.
	 */
	ArmStateMsg stateMsg(Player p, @Nullable ArmSession s, int why) {
		boolean lit = isLit(p);
		int lanternLevel = lit ? accessoryLevel(p, ArmForm.LANTERN.id()) : 0;
		int lanternTime = 0;
		if (lit) {
			try {
				lanternTime = accessories.get(ArmForm.LANTERN.id()).onTicks(p);
			} catch (Throwable t) {
				lanternTime = 0;
			}
		}
		int lanternBright = lit ? lanternBrightness(p, lanternLevel) : 0;
		List<ArmStateMsg.Accessory> list = new ArrayList<>();
		for (String id : onIds(p)) {
			int arg = 0;
			try {
				arg = accessories.get(id).arg(p);
			} catch (Throwable t) {
				arg = 0;
			}
			list.add(new ArmStateMsg.Accessory(id, true, accessoryLevel(p, id), arg));
		}
		if (s == null) {
			return new ArmStateMsg(p.getUniqueId(), p.getEntityId(), "", "", 0, ArmProtocol.STATE_NONE, 0, 0, -1, why, lit, lanternLevel, lanternTime, 0,
					lanternBright, list);
		}
		return new ArmStateMsg(p.getUniqueId(), p.getEntityId(), s.form, s.state == ArmProtocol.STATE_RESHAPING ? s.fromForm : "", s.level, s.state,
				s.elapsed(tick), s.duration, s.slot, why, lit, lanternLevel, lanternTime, 0, lanternBright, list);
	}

	/** A session's state changed: clients, the views the pack look reads, and the look itself. */
	private void changed(Player p, ArmSession s, int why) {
		tellClients(p, stateMsg(p, s, why));
		views.put(p.getEntityId(), s.view());
		lookChanged(p, s.view(), false);
	}

	/**
	 * The owner's main hand to every viewer tracking them, through the packet rewriter (ArmLookModule.onHandChanged): for what
	 * others should see in it changing with no change of the slot to announce it. Call after the views are updated or removed.
	 */
	private void handChanged(Player p) {
		if (look == null) return;
		safeLook(() -> {
			look.onHandChanged(p);
			return null;
		});
	}

	/** A viewer started tracking {@code owner}: a tucked owner's hand is sent to the newcomer a tick later, once the pairing is done. */
	void viewerTracking(Player viewer, Player owner) {
		ArmSession s = sessions.get(owner.getUniqueId());
		if (look == null || s == null || !s.tucked) return;
		plugin.getServer().getScheduler().runTask(plugin, () -> {
			ArmSession now = sessions.get(owner.getUniqueId());
			if (now == null || !now.tucked || !viewer.isOnline() || !owner.isOnline() || !owner.getTrackedBy().contains(viewer)) return;
			safeLook(() -> {
				look.onTracked(viewer, owner);
				return null;
			});
		});
	}

	/**
	 * The biomass bar of a tucked arm moved: the stub others are shown carries it, so the view is replaced and the hand sent to
	 * every viewer (the arm item whose bar this would be does not exist while tucked). Nothing if it did not change.
	 */
	void tuckedBar(Player p, ArmSession s, int maxDamage, int damage) {
		if (!s.tucked || (s.tuckMaxDamage == maxDamage && s.tuckDamage == damage)) return;
		s.tuckMaxDamage = maxDamage;
		s.tuckDamage = damage;
		views.put(p.getEntityId(), s.view());
		handChanged(p);
	}

	/**
	 * The tucked crossbow's bolt changed while the screen is open (CrossbowArm's regrow ran on): the stub others are shown is charged or
	 * emptied to match, the view replaced and the hand sent to every viewer, as {@link #tuckedBar} does for the bar. Nothing if it did not change.
	 */
	void tuckedLoaded(Player p, boolean loaded) {
		ArmSession s = sessions.get(p.getUniqueId());
		if (s == null || !s.refreshTuckCharged(loaded)) return;
		views.put(p.getEntityId(), s.view());
		handChanged(p);
	}

	@Override
	public boolean isTucked(Player p) {
		ArmSession s = sessions.get(p.getUniqueId());
		return s != null && s.tucked;
	}

	private void lookChanged(Player p, @Nullable ArmView view, boolean formingStep) {
		if (look == null) return;
		safeLook(() -> {
			look.onArmChanged(p, view, formingStep);
			return null;
		});
	}

	private <T> @Nullable T safeLook(java.util.function.Supplier<T> call) {
		try {
			return call.get();
		} catch (Throwable t) {
			if (failing.add(look)) plugin.getLogger().log(Level.WARNING, "The arm pack look failed; players without the mod may see plain tools.", t);
			return null;
		}
	}

	@Override
	public void sync(Player viewer) {
		if (!started) return;
		refreshArsenal(viewer);
		refreshSlots(viewer, true); // a client that has just joined has heard nothing
		if (!plugin.channel().drawsArms(viewer)) return;
		for (Player other : viewer.getWorld().getPlayers()) {
			UUID id = other.getUniqueId();
			if (sessions.containsKey(id) || anyOn(other)) {
				tellViewer(viewer, stateMsg(other, sessions.get(id), ArmProtocol.WHY_CHOSEN));
			}
		}
	}

	void refreshArsenal(Player p) {
		if (!started || !plugin.channel().drawsArms(p)) return;
		safely("S_ARSENAL to " + p.getName(), () -> plugin.channel().sendArsenal(p, arsenal(p)));
	}

	/**
	 * S_ARM about {@code subject} to every player in its world who draws arms. Never throws: telling clients is the step
	 * before a slot write, and a failed send (a plugin message refused while RE:Morph is shutting down) must never stop
	 * the slot and the saved record from changing together.
	 */
	private void tellClients(Player subject, ArmStateMsg m) {
		safely("S_ARM about " + subject.getName(), () -> plugin.channel().broadcastArm(subject, m));
	}

	private void tellViewer(Player viewer, ArmStateMsg m) {
		safely("S_ARM to " + viewer.getName(), () -> plugin.channel().sendArm(viewer, m));
	}

	/** A step that may fail without leaving an arm half changed: logged, and the caller carries on. */
	private void safely(String what, Runnable r) {
		try {
			r.run();
		} catch (RuntimeException e) {
			plugin.getLogger().log(Level.WARNING, "Arm forms: " + what + " failed.", e);
		}
	}

	/** Why the player can't form an arm here at all, "" when they can (the wheel shows it). */
	String whyNot(Player p) {
		if (!config.enabled()) return "Arm forms are switched off on this server.";
		if (!p.hasPermission("morphreborn.arm")) return "You are not allowed to use arm forms.";
		var survival = plugin.survival();
		if (survival.active() && p.getGameMode() != GameMode.CREATIVE && (!survival.isUnlocked(p) || survival.level(p, ArmForm.ARM_SHAPING) < 1)) {
			return "Arm forms need the Arm Shaping upgrade: /morph upgrades";
		}
		return "";
	}

	/** S_ARSENAL: built forms in the wheel's order, each with its level, special, cost and cooldown now. */
	ArsenalMsg arsenal(Player p) {
		String whyNot = whyNot(p);
		boolean free = free(p);
		var costs = config.costs();
		double factor = plugin.mode().cooldownFactor(p);
		List<ArsenalMsg.Entry> entries = new ArrayList<>();
		for (String form : builtForms()) {
			ArmForm f = ArmForm.of(form);
			ArmSpecialModule m = specials.get(form);
			if (f == null || m == null) continue;
			int level = levelOf(p, form);
			int at = Math.max(1, level);
			entries.add(new ArsenalMsg.Entry(form, f.displayName(), level, MAX_LEVEL, m.specialName(at), free ? 0 : clampInt(costs.charge(m.costMilli(at))),
					(int) Math.round(m.cooldownTicks(at) * factor), cooldownLeft(p, form)));
		}
		boolean lanternOn = lanternBuilt();
		int lanternLevel = lanternOn ? levelOf(p, ArmForm.LANTERN.id()) : 0;
		// the lantern's special steps the brightness now: free, no cooldown
		int flareCost = 0;
		int flareCooldown = 0;
		List<ArsenalMsg.AccessoryEntry> accessoryEntries = new ArrayList<>();
		for (ArmForm f : ArmForm.ACCESSORIES) {
			if (accessoryBuilt(f.id())) accessoryEntries.add(new ArsenalMsg.AccessoryEntry(f.id(), levelOf(p, f.id()), isOn(p, f.id())));
		}
		return new ArsenalMsg(whyNot.isEmpty(), whyNot, free, clampInt(costs.charge(costs.perBlock())), clampInt(costs.charge(costs.perHit())),
				clampInt(costs.charge(costs.perBolt())), clampInt(costs.charge(costs.toolDrainPerMinute())), clampInt(costs.charge(costs.lanternDrainPerMinute())),
				entries, lastForms.getOrDefault(p.getUniqueId(), ""), lanternLevel, isLit(p), flareCost, flareCooldown, cooldownLeft(p, ArmForm.LANTERN.id()),
				accessoryEntries);
	}

	private static int clampInt(long v) {
		return (int) Math.clamp(v, 0, Integer.MAX_VALUE);
	}

	// ------------------------------------------------------------------ ArmService: requests and hooks

	@Override
	public void request(Player p, int action, String form) {
		request(p, action, form, 0);
	}

	@Override
	public void request(Player p, int action, String form, int step) {
		if (!started) return;
		try {
			String why = switch (action) {
				case ArmProtocol.ACTION_FORM -> {
					String wanted = form.isEmpty() ? lastForms.getOrDefault(p.getUniqueId(), "") : form;
					yield wanted.isEmpty() ? "Pick a form on the wheel first." : form(p, wanted, Cause.CHOSEN, -1, 0, false);
				}
				case ArmProtocol.ACTION_RETRACT -> retractChosen(p);
				// the lantern's own actions, which clients before ArmProtocol.NEW_FORMS_SINCE send
				case ArmProtocol.ACTION_LANTERN_ON -> switchOn(p, ArmForm.LANTERN.id(), false, 0, false);
				case ArmProtocol.ACTION_LANTERN_OFF -> {
					switchOff(p, ArmForm.LANTERN.id(), ArmProtocol.WHY_CHOSEN);
					yield null;
				}
				// an accessory by id (8.0.0-beta.2): the id rides in the form string
				case ArmProtocol.ACTION_ACCESSORY_ON -> {
					ArmForm acc = ArmForm.of(form);
					yield acc == null || acc.tool() ? "Pick an accessory on the wheel first." : switchOn(p, acc.id(), false, 0, false);
				}
				case ArmProtocol.ACTION_ACCESSORY_OFF -> {
					ArmForm acc = ArmForm.of(form);
					if (acc != null && !acc.tool()) switchOff(p, acc.id(), ArmProtocol.WHY_CHOSEN);
					yield null;
				}
				// the wheel's scroll over an accessory (2026-11-16): one step of its mode, the lantern's brightness
				case ArmProtocol.ACTION_ACCESSORY_MODE -> {
					ArmForm acc = ArmForm.of(form);
					yield acc == null || acc.tool() ? "Pick an accessory on the wheel first." : stepMode(p, acc.id(), step);
				}
				// a held key of an accessory (the spyglass's zoom key; 2026-11-16): the step field carries 1 held or 0 let go; never refused
				case ArmProtocol.ACTION_ACCESSORY_HOLD -> {
					ArmForm acc = ArmForm.of(form);
					ArmAccessoryModule module = acc == null || acc.tool() ? null : accessories.get(acc.id());
					if (module != null && moduleOn(p, acc.id())) module.hold(p, step > 0);
					yield null;
				}
				// the screen signals of 7.6.0-beta.3 (2026-11-16): never answered with a refusal
				case ArmProtocol.ACTION_INVENTORY_OPEN -> {
					inventoryOpened(p);
					yield null;
				}
				case ArmProtocol.ACTION_INVENTORY_CLOSED -> {
					inventoryClosed(p);
					yield null;
				}
				default -> {
					plugin.debug("Unknown arm action " + action + " from " + p.getName());
					yield null;
				}
			};
			if (why != null) plugin.channel().sendResult(p, ClientChannel.RESULT_DENIED, why);
		} catch (RuntimeException e) {
			plugin.getLogger().log(Level.SEVERE, "An arm request from " + p.getName() + " failed.", e);
			plugin.channel().sendResult(p, ClientChannel.RESULT_DENIED, "That didn't work; the server log says why.");
		}
	}

	@Override
	public void onMorphStart(Player p) {
		if (!started) return;
		ArmSession s = sessions.get(p.getUniqueId());
		if (s != null) finish(p, s, ArmProtocol.WHY_MORPH, Overflow.DROP);
		switchOffAll(p, ArmProtocol.WHY_MORPH);
	}

	/**
	 * Back in the own body (2026-11-17, on a bound arm that stayed away after a borrow ended or a demorph until the slot
	 * was scrolled away from and back): a bound held slot forms its arm as a scroll onto it would (deployHeld: same checks, billing, tier
	 * and stash; nothing for an unbound or unheld slot, a player who can't pay is told once). A tick later, so the rest of what ended
	 * the morph (a command's reply, an ability reset, a death's respawn) has settled and nothing forms inside a tick loop. Only arm
	 * forms are bound to slots: the lantern and the accessories are switched, never bound, so there is nothing of theirs to restore.
	 */
	@Override
	public void onOwnBody(Player p) {
		if (!started) return;
		plugin.getServer().getScheduler().runTask(plugin, () -> {
			// a container open on a client that doesn't report screens (unmodded, or a missed OPEN) is not ours to close from a timer
			if (p.isOnline() && OwnBodyDeploy.fairMoment(p.getOpenInventory().getType())) deployHeld(p);
		});
	}

	/** A Survival upgrade was bought (or set by an admin): a formed arm re-tiers in place, or goes if its level is gone. */
	@Override
	public void onUpgrade(Player p, String upgradeId) {
		if (!started) return;
		ArmSession s = sessions.get(p.getUniqueId());
		if (s != null) {
			ArmForm f = ArmForm.byUpgrade(upgradeId);
			if ((f != null && f.id().equals(s.form)) || ArmForm.ARM_SHAPING.equals(upgradeId)) {
				int owned = levelOf(p, s.form);
				if (owned < 1 && !s.free) finish(p, s, ArmProtocol.WHY_UPGRADE, Overflow.DROP);
				else if (owned >= 1 && f != null) {
					s.cap = owned;
					int level = effective(p, owned, s, false);
					if (level != s.level) retier(p, s, level, ArmProtocol.WHY_UPGRADE);
				}
			}
		}
		pruneSlots(p); // a form whose level is gone loses its binding (2026-11-16)
		refreshSlots(p, false); // a level bought changes the tier a bound arm would form at
		refreshArsenal(p);
	}

	@Override
	public void onSurvivalReset(Player p) {
		if (!started) return;
		ArmSession s = sessions.get(p.getUniqueId());
		if (s != null) finish(p, s, ArmProtocol.WHY_ADMIN, Overflow.DROP);
		switchOffAll(p, ArmProtocol.WHY_ADMIN);
		saveSlots(p, ArmSlots.EMPTY); // every level is going: nothing stays bound (2026-11-16)
		refreshSlots(p, false);
		refreshArsenal(p);
	}

	@Override
	public boolean isSpecialHit(EntityDamageEvent e) {
		if (specialHits.isEmpty()) return false;
		Entity cause = e.getDamageSource().getCausingEntity();
		if (cause == null && e instanceof EntityDamageByEntityEvent byEntity) cause = byEntity.getDamager();
		return cause instanceof Player p && specialHits.containsKey(p.getUniqueId());
	}

	@Override
	public boolean isProbeHit(EntityDamageEvent e) {
		if (probeHits.isEmpty()) return false;
		Entity cause = e.getDamageSource().getCausingEntity();
		if (cause == null && e instanceof EntityDamageByEntityEvent byEntity) cause = byEntity.getDamager();
		return cause instanceof Player p && probeHits.containsKey(p.getUniqueId());
	}

	@Override
	public boolean isArm(@Nullable ItemStack stack) {
		return ArmItems.isArm(stack);
	}

	@Override
	public boolean command(CommandSender sender, String[] args) {
		return commands.command(sender, args);
	}

	@Override
	public List<String> tabComplete(CommandSender sender, String[] args) {
		return commands.tabComplete(sender, args);
	}

	// ------------------------------------------------------------------ for the arm package

	MorphRebornPlugin plugin() {
		return plugin;
	}

	ArmConfig config() {
		return config;
	}

	/** This manager's tick counter. */
	int now() {
		return tick;
	}

	ArmBilling billing() {
		return billing;
	}

	ArmAudit audit() {
		return audit;
	}

	@Nullable ArmSession session(Player p) {
		return sessions.get(p.getUniqueId());
	}

	@Nullable ArmSession session(UUID id) {
		return sessions.get(id);
	}

	/** Adds every session owner's id to {@code into} (the caller's scratch collection: the tick loop must not allocate). */
	void collectSessionIds(Collection<UUID> into) {
		into.addAll(sessions.keySet());
	}

	boolean isSessionArm(ArmSession s, @Nullable ItemStack stack) {
		return !s.tucked && ArmItems.isArm(stack) && ArmItems.nonceOf(stack) == s.nonce; // a tucked arm has no item
	}

	/** Run one of our own inventory writes (the slot-change guard knows to ignore it). */
	void write(Player p, Runnable r) {
		writing++;
		try {
			r.run();
		} finally {
			writing--;
		}
	}

	boolean writing() {
		return writing > 0;
	}

	/**
	 * Delete every arm in a container, except a live session's own arm in its home slot (an armed player's inventory
	 * opened through /invsee). Returns how many went.
	 */
	int purge(Inventory inv) {
		ArmSession holderSession = inv instanceof PlayerInventory pi && pi.getHolder() instanceof Player holder ? sessions.get(holder.getUniqueId()) : null;
		int n = 0;
		for (int i = 0; i < inv.getSize(); i++) {
			ItemStack item = inv.getItem(i);
			if (!ArmItems.isArm(item)) continue;
			if (holderSession != null && i == holderSession.slot && isSessionArm(holderSession, item)) continue;
			inv.setItem(i, null);
			n++;
		}
		if (n > 0) plugin.debug("Deleted " + n + " stray arm(s) from a " + inv.getType().name().toLowerCase(Locale.ROOT) + " inventory.");
		return n;
	}

	boolean inventorySafety() {
		return switch (config.inventorySafety()) {
			case ON -> true;
			case OFF -> false;
			case AUTO -> {
				var pm = plugin.getServer().getPluginManager();
				boolean any = false;
				for (String name : INVENTORY_PLUGINS) any |= pm.isPluginEnabled(name);
				yield any;
			}
		};
	}

	boolean inSpecialBreak(Player p) {
		return specialBreaks.containsKey(p.getUniqueId());
	}

	void fx(Player origin, ArmFxMsg msg, double radius) {
		safely("S_ARM_FX from " + origin.getName(), () -> plugin.channel().broadcastArmFx(origin, msg, radius));
	}

	/** S_ARM_FX to only the viewers {@code to} accepts (a whip's blow: the old FX_HIT to clients that don't know the whip's moves). */
	void fx(Player origin, ArmFxMsg msg, double radius, java.util.function.Predicate<Player> to) {
		safely("S_ARM_FX from " + origin.getName(), () -> plugin.channel().broadcastArmFx(origin, msg, radius, to));
	}

	/** arms.report: one line to the console per cost, drain flush, form and retract, for balance testing. */
	void report(Player p, String text) {
		if (config.report()) plugin.getLogger().info("[Arm report] " + p.getName() + ": " + text);
	}

	private static String causeName(Cause cause) {
		return cause.name().toLowerCase(Locale.ROOT);
	}

	/** The player's bindings as /morph arm shows them: "1 katana, 4 bow" (slots counted from 1 as on the hotbar), or "none". */
	String describeSlots(Player p) {
		ArmSlots slots = slots(p);
		if (slots.isEmpty()) return "none";
		List<String> parts = new ArrayList<>();
		for (ArmSlots.Binding b : slots.bindings()) parts.add((b.slot() + 1) + " " + b.form());
		return String.join(", ", parts);
	}

	/** What /morph arm <player> info shows. */
	List<String> info(Player p) {
		List<String> out = new ArrayList<>();
		ArmSession s = sessions.get(p.getUniqueId());
		if (s == null) {
			out.add(p.getName() + ": no arm formed.");
		} else {
			out.add(p.getName() + ": " + s.form + " L" + s.level + ", " + ArmProtocol.stateName(s.state) + " for " + s.elapsed(tick) + " t, slot " + s.slot
					+ ", nonce " + Long.toHexString(s.nonce) + (s.free ? ", free" : "") + (s.tucked ? ", tucked (a screen is open)" : ""));
			out.add("  holding: " + (s.tucked ? "nothing: its real item is in the slot" : ArmStash.describe(s.stash)));
		}
		out.add("  arm slots: " + describeSlots(p));
		long capacity = capacityMilli(p);
		long balance = balanceMilli(p, -1);
		out.add("  biomass: " + (balance < 0 ? "none (Classic)" : balance + " milli of " + capacity) + "; " + (free(p) ? "free here" : "drains " + billing.rate(p)
				+ " milli/min") + "; " + billing.drainInfo(p));
		List<String> ready = new ArrayList<>();
		Map<String, Integer> cds = cooldowns.get(p.getUniqueId());
		if (cds != null) {
			for (String key : cds.keySet()) {
				int left = cooldownLeft(p, key);
				if (left > 0) ready.add(key + " " + seconds(left));
			}
		}
		List<String> onNow = new ArrayList<>();
		for (String id : onIds(p)) onNow.add(id + " L" + accessoryLevel(p, id) + (accessoryFree(p, id) ? " (free)" : ""));
		out.add("  cooldowns: " + (ready.isEmpty() ? "none" : String.join(", ", ready)) + "; accessories on: " + (onNow.isEmpty() ? "none" : String.join(", ", onNow)));
		List<ArmStash.Pending> pending = ArmStash.pending(p);
		if (pending.isEmpty()) {
			out.add("  nothing waiting.");
		} else {
			out.add("  waiting: " + pending.size() + " item(s)");
			for (ArmStash.Pending e : pending) out.add("    " + e.reason() + ": " + ArmStash.describe(e.stash()));
		}
		return out;
	}

	// ------------------------------------------------------------------ ArmContext, for the modules

	/** What a module may ask of the manager (see the class comment for why this isn't the manager itself). */
	private final class Context implements ArmContext {
		@Override
		public MorphRebornPlugin plugin() {
			return plugin;
		}

		@Override
		public ArmConfig config() {
			return config;
		}

		@Override
		public int tick() {
			return tick;
		}

		@Override
		public int level(Player p, String form) {
			return workingLevel(sessions.get(p.getUniqueId()), form, levelOf(p, form));
		}

		@Override
		public long token(Player p) {
			ArmSession s = sessions.get(p.getUniqueId());
			return s == null ? 0 : s.nonce;
		}

		@Override
		public boolean stillArmed(Player p, long token) {
			ArmSession s = sessions.get(p.getUniqueId());
			return token != 0 && p.isOnline() && !p.isDead() && s != null && s.nonce == token && s.state == ArmProtocol.STATE_FORMED
					&& isSessionArm(s, p.getInventory().getItemInMainHand());
		}

		@Override
		public ArmLedger.Spend spend(Player p, long milli, boolean partial) {
			ArmLedger.Spend r = ArmManager.this.spend(p, milli, partial);
			ArmManager.this.report(p, "module spend " + milli + " milli" + (partial ? "" : " (all or nothing)") + ": " + (r.paid() ? "-" + r.charged() : "refused") + ", "
					+ r.after() + " left");
			return r;
		}

		@Override
		public boolean canAfford(Player p, long milli) {
			return milli <= 0 || free(p) || balanceMilli(p, Long.MAX_VALUE) >= milli;
		}

		@Override
		public long gain(Player p, long milli) {
			if (milli <= 0 || free(p)) return 0;
			long gained = plugin.mode().armGain(p, milli);
			ArmManager.this.report(p, "module gain " + milli + " milli: +" + gained);
			return gained;
		}

		@Override
		public void asSpecialDamage(Player p, Runnable hit) {
			nested(specialHits, p.getUniqueId(), hit);
		}

		@Override
		public void asProbeDamage(Player p, Runnable probe) {
			nested(specialHits, p.getUniqueId(), () -> nested(probeHits, p.getUniqueId(), probe));
		}

		@Override
		public void asSpecialBreak(Player p, Runnable brk) {
			nested(specialBreaks, p.getUniqueId(), brk);
		}

		@Override
		public boolean inSpecialBreak(Player p) {
			return ArmManager.this.inSpecialBreak(p);
		}

		@Override
		public void fx(Player origin, ArmFxMsg msg, double radius) {
			ArmManager.this.fx(origin, msg, radius);
		}

		@Override
		public boolean drawsArms(Player viewer) {
			return plugin.channel().drawsArms(viewer);
		}

		@Override
		public boolean drawsWhipMoves(Player viewer) {
			return plugin.channel().drawsWhipMoves(viewer);
		}

		@Override
		public boolean drawsNewForms(Player viewer) {
			return plugin.channel().drawsNewForms(viewer);
		}

		@Override
		public boolean accessoryOn(Player p, String id) {
			return isOn(p, id);
		}

		@Override
		public void accessoryChanged(Player p) {
			if (started && anyOn(p)) tellClients(p, stateMsg(p, sessions.get(p.getUniqueId()), ArmProtocol.WHY_CHOSEN));
		}

		@Override
		public void report(String line) {
			if (config.report()) plugin.getLogger().info("[Arm report] " + line);
		}

		@Override
		public ConcurrentMap<Integer, ArmView> views() {
			return views;
		}

		@Override
		public void tuckedLoaded(Player p, boolean loaded) {
			ArmManager.this.tuckedLoaded(p, loaded);
		}

		@Override
		public void refreshArsenal(Player p) {
			ArmManager.this.refreshArsenal(p);
		}
	}

	private static void nested(Map<UUID, Integer> depth, UUID id, Runnable r) {
		depth.merge(id, 1, Integer::sum);
		try {
			r.run();
		} finally {
			depth.computeIfPresent(id, (k, v) -> v <= 1 ? null : v - 1);
		}
	}
}
