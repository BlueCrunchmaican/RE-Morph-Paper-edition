package dev.morphreborn.paper.morph;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.PluginSettings;
import dev.morphreborn.paper.data.MorphRef;
import dev.morphreborn.paper.data.PlayerMorphData;
import dev.morphreborn.paper.net.ClientChannel;
import dev.morphreborn.paper.variant.VariantCapture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.util.BoundingBox;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.jspecify.annotations.Nullable;

/**
 * Authoritative morph logic: unlocking, selection, the transformation timeline and keeping every viewer in sync.
 * Server-side port of the original's MorphHandler + MorphInfoImpl.tick (classic mode).
 */
public final class MorphManager {
	private static final double PLAYER_HEIGHT = 1.8;
	/** Smallest size the vanilla shrink/grow animation goes down to before the disguise swaps. */
	private static final double VANILLA_MIN_DISGUISE_SCALE = 0.15;
	/** The disguise while the morph skin melts over it for everyone who can see it: as small as the game draws a body. */
	private static final double HIDDEN_DISGUISE_SCALE = 0.0625;
	/** Players whose disguise is out of sight while the morph skin melts over it (SkinEffects). */
	private final java.util.Set<UUID> skinHidden = new java.util.HashSet<>();

	private final MorphRebornPlugin plugin;
	private final NamespacedKey scaleKey;
	/** The form each player's physical body (hitbox, copied attributes) has right now; absent = their own body. */
	private final Map<UUID, MorphRef> bodies = new HashMap<>();
	private final NamespacedKey attributeKey;
	private final Map<UUID, ActiveMorph> states = new HashMap<>();
	private final Map<UUID, Long> lastMorphStart = new HashMap<>();
	private long serverTick;

	public MorphManager(MorphRebornPlugin plugin) {
		this.plugin = plugin;
		this.scaleKey = MorphRebornPlugin.key("morph_scale");
		this.attributeKey = MorphRebornPlugin.key("morph_attribute");
	}

	public @Nullable ActiveMorph state(Player player) {
		return states.get(player.getUniqueId());
	}

	/** The form the player currently counts as (null = own body). */
	public @Nullable MorphRef activeForm(Player player) {
		ActiveMorph s = states.get(player.getUniqueId());
		if (s == null) return null;
		MorphRef form = s.activeForm();
		return form == null || form.isSelf() ? null : form;
	}

	public boolean isTransitioning(Player player) {
		ActiveMorph s = states.get(player.getUniqueId());
		return s != null && s.isTransitioning();
	}

	// ------------------------------------------------------------------ requests

	/** Selection from the client mod or /morph. {@code variantId} "self" means return to your own form. */
	public void requestMorph(Player player, String variantId) {
		requestMorph(player, variantId, null);
	}

	/**
	 * Why {@code form} can't be part of a multimorph, or null if it can. Your own body takes part as
	 * {@link MorphRef#selfBody}; the plain "self" form (no morph at all) never does.
	 */
	public static @Nullable String cannotCombine(MorphRef form) {
		if (form.isSelf()) return "Your own body can't be combined.";
		return null;
	}

	/**
	 * Selection, optionally a multimorph: {@code variantId} is the main body and {@code borrowedId} the form it borrows
	 * from (null for a single form). Picking the body you are already in changes only what it borrows (or drops it);
	 * Survival players pay the price of each form that is new to them.
	 */
	public void requestMorph(Player player, String variantId, @Nullable String borrowedId) {
		PluginSettings s = plugin.settings();
		ClientChannel ch = plugin.channel();
		if (!s.selectorEnabled || !player.hasPermission("morphreborn.use")) {
			refuse(player, ClientChannel.RESULT_DENIED, "You are not allowed to morph.");
			return;
		}
		ActiveMorph current = states.get(player.getUniqueId());
		if (current != null && current.isTransitioning()) {
			refuse(player, ClientChannel.RESULT_BUSY, "Wait for the current transformation to finish.");
			return;
		}
		Long last = lastMorphStart.get(player.getUniqueId());
		if (last != null && s.morphCooldownTicks > 0 && serverTick - last < s.morphCooldownTicks) {
			long secs = (s.morphCooldownTicks - (serverTick - last) + 19) / 20;
			refuse(player, ClientChannel.RESULT_BUSY, "You can morph again in " + secs + "s.");
			return;
		}
		if (MorphRef.ownBodyId(variantId) && borrowedId == null) {
			// your own body: from your own body with something borrowed, only the borrowed form goes, at once
			if (current != null && current.isMorphed()) demorph(player, current.next != null && current.next.isSelfBody());
			ch.sendResult(player, ClientChannel.RESULT_OK, "");
			return;
		}
		String locked = plugin.mode().cannotMorph(player);
		if (locked != null) {
			refuse(player, ClientChannel.RESULT_DENIED, locked);
			return;
		}
		PlayerMorphData data = plugin.store().get(player.getUniqueId());
		MorphRef ref = resolve(player, data, variantId);
		if (ref == null) {
			refuse(player, ClientChannel.RESULT_UNKNOWN_MORPH, "You don't have that morph.");
			ch.sendCollection(player);
			return;
		}
		if (!ref.isSelfBody() && !canUseType(player, ref.entityType())) {
			refuse(player, ClientChannel.RESULT_DENIED, "You are not allowed to use that morph.");
			return;
		}
		MorphRef borrowed = null;
		if (borrowedId != null) {
			if (!s.multimorphEnabled || !player.hasPermission("morphreborn.multimorph")) {
				refuse(player, ClientChannel.RESULT_DENIED, "You are not allowed to combine morphs.");
				return;
			}
			String noCombining = plugin.mode().cannotCombine(player);
			if (noCombining != null) {
				refuse(player, ClientChannel.RESULT_DENIED, noCombining);
				return;
			}
			borrowed = resolve(player, data, borrowedId);
			if (borrowed == null) {
				refuse(player, ClientChannel.RESULT_UNKNOWN_MORPH, "You don't have that morph.");
				ch.sendCollection(player);
				return;
			}
			if (!borrowed.isSelfBody() && !canUseType(player, borrowed.entityType())) {
				refuse(player, ClientChannel.RESULT_DENIED, "You are not allowed to use that morph.");
				return;
			}
			String why = cannotCombine(ref);
			if (why == null) why = cannotCombine(borrowed);
			if (why == null && ref.entityType().equals(borrowed.entityType())) why = "Combine two different creatures.";
			if (why != null) {
				refuse(player, ClientChannel.RESULT_DENIED, why);
				return;
			}
		}
		MorphRef wasBorrowed = plugin.borrowedHeads().borrowed(player);
		boolean sameBody = current != null && current.next != null && current.next.variantId().equals(ref.variantId());
		if (sameBody && java.util.Objects.equals(borrowed == null ? null : borrowed.variantId(), wasBorrowed == null ? null : wasBorrowed.variantId())) {
			ch.sendResult(player, ClientChannel.RESULT_OK, "");
			return;
		}
		// Survival: each form that is new to the body costs its price; the body you keep is already paid for
		// your own body is free, as a main body or borrowed
		int cost = (sameBody || ref.isSelfBody() ? 0 : plugin.mode().morphCost(player, ref))
				+ (borrowed == null || borrowed.isSelfBody() ? 0 : plugin.mode().morphCost(player, borrowed));
		if (borrowed != null) cost += plugin.mode().multimorphExtra(player, cost);
		if (!plugin.mode().payBiomass(player, cost)) {
			refuse(player, ClientChannel.RESULT_DENIED, "Not enough biomass: " + (borrowed == null ? "that morph" : "that combination") + " costs " + cost + ".");
			return;
		}
		if (sameBody) plugin.borrowedHeads().setBorrowed(player, borrowed);
		else morphTo(player, ref, ref.isSelfBody() && (current == null || !current.isMorphed()), borrowed); // own body + borrowed: at once
		ch.sendResult(player, ClientChannel.RESULT_OK, "");
	}

	/** Admin {@code /morph borrow} on a player in their own body: the body borrows {@code borrowed}, at once (7.1.3). */
	public void lendToOwnBody(Player player, MorphRef borrowed) {
		morphTo(player, MorphRef.selfBody(VariantCapture.skinOf(player)), true, borrowed);
	}

	/** A form from the player's collection by id, or their own body for "self" / "self-body"; null if they don't have it. */
	private static @Nullable MorphRef resolve(Player player, PlayerMorphData data, String variantId) {
		return MorphRef.ownBodyId(variantId) ? MorphRef.selfBody(VariantCapture.skinOf(player)) : data.byId(variantId);
	}

	/** A refused request: modded clients show it themselves; everyone else gets it in chat. */
	private void refuse(Player player, int code, String message) {
		if (plugin.channel().isModded(player)) plugin.channel().sendResult(player, code, message);
		else player.sendMessage(Component.text(message, NamedTextColor.RED));
	}

	public boolean canUseType(Player player, String entityType) {
		if (plugin.capture().rules().isDisabled(entityType)) return false;
		if (!plugin.settings().perTypePermissions) return true;
		return player.hasPermission("morphreborn.type." + entityType.replace(':', '.'));
	}

	public void setFavourite(Player player, String variantId, boolean favourite) {
		PlayerMorphData data = plugin.store().get(player.getUniqueId());
		PlayerMorphData.StoredVariant v = data.storedById(variantId);
		if (v != null && v.favourite != favourite) {
			v.favourite = favourite;
			plugin.store().markDirty(player.getUniqueId());
		}
		plugin.channel().sendCollection(player);
	}

	public void deleteVariant(Player player, String variantId) {
		PlayerMorphData data = plugin.store().get(player.getUniqueId());
		ActiveMorph s = states.get(player.getUniqueId());
		boolean inUse = s != null && ((s.next != null && s.next.variantId().equals(variantId)) || (s.prev != null && s.prev.variantId().equals(variantId)));
		if (!plugin.settings().allowDelete || inUse) {
			plugin.channel().sendResult(player, ClientChannel.RESULT_DENIED, inUse ? "You can't delete the morph you are using." : "Deleting morphs is disabled.");
		} else if (data.remove(variantId)) {
			plugin.store().markDirty(player.getUniqueId());
			checkBorrowed(player);
		}
		plugin.channel().sendCollection(player);
	}

	/** After morphs were removed from a collection: a borrowed form that went with them is dropped too. */
	public void checkBorrowed(Player player) {
		MorphRef form = plugin.borrowedHeads().borrowed(player);
		if (form != null && !form.isSelfBody() && plugin.store().get(player.getUniqueId()).byId(form.variantId()) == null) plugin.borrowedHeads().setBorrowed(player, null);
	}

	// ------------------------------------------------------------------ unlocking

	/** Called for every player kill (EntityDeathEvent). What the kill gives is up to the gameplay mode. */
	public void handleKill(Player killer, LivingEntity victim) {
		if (!killer.hasPermission("morphreborn.acquire") || victim.hasMetadata("NPC")) return;
		plugin.mode().onKill(killer, victim, appearanceOf(killer, victim));
	}

	/** The morph a kill would unlock (ClassicMode.handleMurderEvent's variant), or null if it can't give one. */
	private @Nullable MorphRef appearanceOf(Player killer, LivingEntity victim) {
		PluginSettings s = plugin.settings();
		MorphRef appearance;
		if (victim instanceof Player target) {
			MorphRef theirForm = activeForm(target);
			if (theirForm != null && isTransitioning(target)) return null; // mid morph: no variant (original behaviour)
			if (theirForm != null && s.stealMorphs) {
				appearance = new MorphRef(theirForm.entityType(), "", theirForm.snbt(), theirForm.skin());
			} else if (s.allowPlayerMorphs) {
				appearance = new MorphRef(MorphRef.PLAYER_TYPE, "", "", VariantCapture.skinOf(target));
			} else {
				return null;
			}
			if (appearance.isPlayer() && (!s.allowPlayerMorphs || appearance.skin() == null || appearance.skin().profileId().equals(killer.getUniqueId()))) return null;
		} else {
			appearance = plugin.capture().capture(victim);
		}
		if (appearance == null || plugin.capture().rules().isDisabled(appearance.entityType())) return null;
		return appearance;
	}

	/**
	 * What a kill did to the collection: the morph it unlocked (or null), a short {@code status} for the action bar
	 * (or null), and a {@code hint} to add when the status has the action bar to itself.
	 */
	public record Acquisition(@Nullable MorphRef morph, @Nullable Component status, @Nullable Component hint) {
		public static final Acquisition NOTHING = new Acquisition(null, null, null);

		/** The status with its hint after it, for an action bar of its own. */
		public @Nullable Component withHint() {
			if (status == null || hint == null) return status;
			return status.append(Component.text(". ", NamedTextColor.GRAY)).append(hint);
		}
	}

	/** Separates parts of one action bar line ("+12 biomass (40 / 100)  ·  Morphs 5/6"). */
	public static final Component SEPARATOR = Component.text("  ·  ", NamedTextColor.DARK_GRAY);

	/**
	 * The most looks of one creature the player's collection may hold now: the mode's limit (Survival's Variant
	 * Capacity) or config.yml's {@code max-variants-per-type}, whichever is lower; -1 for no limit.
	 */
	public int lookLimit(Player player) {
		int mode = plugin.mode().variantCapacity(player);
		int cap = plugin.settings().maxVariantsPerType;
		if (cap <= 0) return mode;
		return mode < 0 ? cap : Math.min(mode, cap);
	}

	/**
	 * Whether the player's morphs of {@code type} get a look counter in the selector: the creature can come in more
	 * than one look ({@link VariantCapture#hasLooks}), or the player already holds more than one of it (a name-tagged
	 * one, say), which the look limit counts too.
	 */
	public boolean hasLooks(Player player, String type, int held) {
		if (held > 1) return true;
		return plugin.capture().hasLooks(type, () -> templateSaveData(type, player.getWorld()));
	}

	/** The raw save data of a default, never-spawned {@code type}, or null (players, unknown types). */
	private @Nullable String templateSaveData(String type, org.bukkit.World world) {
		if (type.equals(MorphRef.PLAYER_TYPE)) return null;
		LivingEntity template = plugin.templates().get(new MorphRef(type, "", "", null), world);
		if (template == null) return null;
		try {
			org.bukkit.entity.EntitySnapshot snapshot = template.createSnapshot();
			return snapshot == null ? null : snapshot.getAsString();
		} catch (RuntimeException e) {
			plugin.debug("No save data for " + type + ": " + e);
			return null;
		}
	}

	/** A creature's name for messages, in the reader's language (players: "Player"). */
	public static Component typeName(String entityType) {
		String path = entityType.substring(entityType.indexOf(':') + 1);
		return Component.translatable("entity." + entityType.replace(':', '.'), path);
	}

	/**
	 * Count a kill toward {@code appearance} and unlock it when the collection has room (the mode's Morph Capacity for
	 * a new creature; the look limit, {@link #lookLimit}, for another look of one) and enough kills are in
	 * (required-kills). A kill that can't fit isn't counted toward required-kills. The result says what happened, for
	 * the mode's action bar.
	 */
	public Acquisition acquire(Player killer, MorphRef appearance) {
		PluginSettings s = plugin.settings();
		PlayerMorphData data = plugin.store().get(killer.getUniqueId());
		if (data.find(appearance) != null) return Acquisition.NOTHING;
		String type = appearance.entityType();
		List<PlayerMorphData.StoredVariant> existing = data.morphs.get(type);
		int kinds = plugin.mode().typeCapacity(killer);
		if (kinds >= 0 && existing == null && data.morphs.size() >= kinds) {
			Component status = Component.text("No room for ", NamedTextColor.GOLD).append(typeName(type))
					.append(Component.text(": morphs full (" + data.morphs.size() + "/" + kinds + ")"));
			String upgrade = plugin.survival().displayName(dev.morphreborn.paper.survival.SurvivalConfig.MORPH_CAPACITY);
			return new Acquisition(null, status, Component.text("Upgrade " + upgrade + (s.allowDelete ? " or delete one." : "."), NamedTextColor.GRAY));
		}
		int looks = lookLimit(killer);
		if (looks >= 0 && existing != null && existing.size() >= looks) {
			Component status = Component.text("No room for this ", NamedTextColor.GOLD).append(typeName(type))
					.append(Component.text(" look (" + existing.size() + "/" + looks + ")")); // short: it shares a line with the biomass
			int fromMode = plugin.mode().variantCapacity(killer);
			boolean serverCap = s.maxVariantsPerType > 0 && (fromMode < 0 || s.maxVariantsPerType <= fromMode);
			// Hints stay short: with the status they must fit a small window's action bar.
			String hint;
			if (serverCap) {
				hint = s.allowDelete ? "Delete one to make room." : "That's this server's limit.";
			} else {
				String upgrade = plugin.survival().displayName(dev.morphreborn.paper.survival.SurvivalConfig.MORPH_VARIANT_CAPACITY);
				hint = "Upgrade " + upgrade + (s.allowDelete ? " or delete one." : ".");
			}
			return new Acquisition(null, status, Component.text(hint, NamedTextColor.GRAY));
		}
		int needed = s.requiredKills(type);
		if (needed > 1) {
			int kills = data.killCounts.merge(type, 1, Integer::sum);
			plugin.store().markDirty(killer.getUniqueId());
			if (kills < needed) return new Acquisition(null, Component.text("Morph progress: " + kills + "/" + needed, NamedTextColor.GRAY), null);
		}
		MorphRef added = unlock(killer, appearance);
		if (added == null) return Acquisition.NOTHING;
		// the room left after it, where a limit applies: "Morphs 5/6" for a new creature, "Sheep looks 2/3" for a look
		Component room = null;
		if (existing == null && kinds >= 0) {
			room = count(Component.text("Morphs "), data.morphs.size(), kinds);
		} else if (existing != null && looks >= 0) {
			room = count(typeName(type).append(Component.text(" looks ")), data.morphs.get(type).size(), looks);
		}
		return new Acquisition(added, room, null);
	}

	/** "Morphs 5/6" in grey, or "Morphs 6/6 full" in gold once the limit is reached. */
	private static Component count(Component label, int have, int limit) {
		boolean full = have >= limit;
		return Component.text("", full ? NamedTextColor.GOLD : NamedTextColor.GRAY).append(label)
				.append(Component.text(have + "/" + limit + (full ? " full" : "")));
	}

	/**
	 * The acquisition animation: tendrils for modded viewers, particle trails for everyone else. {@code newMorph} tells
	 * a new morph from a kill that only gave biomass (Survival), so each looks different.
	 */
	public void playAcquisition(Player killer, LivingEntity victim, boolean newMorph) {
		plugin.rage().noteAcquisition(victim, newMorph);
		plugin.channel().sendAcquire(killer, victim, newMorph);
		boolean drawn = plugin.skin().absorb(killer, victim, newMorph);
		plugin.effects().acquisition(killer, victim, newMorph, drawn);
	}

	/** Add a variant to the collection and tell the client. Returns the stored ref, or null if already owned. */
	public @Nullable MorphRef unlock(Player player, MorphRef appearance) {
		PlayerMorphData data = plugin.store().get(player.getUniqueId());
		MorphRef added = data.add(appearance);
		if (added == null) return null;
		data.killCounts.remove(appearance.entityType());
		plugin.store().markDirty(player.getUniqueId());
		plugin.channel().sendCollection(player);
		if (plugin.channel().isModded(player)) {
			plugin.channel().sendUnlock(player, added);
		} else if (plugin.settings().unlockChatMessage) {
			player.sendMessage(Component.text("New morph: ", NamedTextColor.GREEN).append(displayName(added)));
		}
		return added;
	}

	public static Component displayName(MorphRef ref) {
		if (ref.isPlayer()) return Component.text(ref.skin() != null ? ref.skin().name() : "Player", NamedTextColor.YELLOW);
		String path = ref.entityType().substring(ref.entityType().indexOf(':') + 1);
		return Component.translatable("entity." + ref.entityType().replace(':', '.'), path).color(NamedTextColor.YELLOW);
	}

	// ------------------------------------------------------------------ morphing

	public void demorph(Player player, boolean instant) {
		ActiveMorph s = states.get(player.getUniqueId());
		if (s == null || !s.isMorphed()) return;
		morphTo(player, MorphRef.self(VariantCapture.skinOf(player)), instant);
	}

	/** Start transforming into {@code ref} (MorphInfo.setNextState). {@code instant} skips the animation (login restore, admin). */
	public void morphTo(Player player, MorphRef ref, boolean instant) {
		morphTo(player, ref, instant, null);
	}

	/** As {@link #morphTo(Player, MorphRef, boolean)}, borrowing from {@code borrowed} (multimorph) once transformed. */
	public void morphTo(Player player, MorphRef ref, boolean instant, @Nullable MorphRef borrowed) {
		PluginSettings settings = plugin.settings();
		ActiveMorph s = states.computeIfAbsent(player.getUniqueId(), k -> new ActiveMorph());
		MorphRef from = s.next != null ? s.look() : MorphRef.self(VariantCapture.skinOf(player));
		if (from.isSelf() && ref.isSelf()) {
			states.remove(player.getUniqueId());
			return;
		}
		s.prev = from;
		s.next = ref;
		s.converted = null; // a new form is the creature itself again
		s.time = 0;
		s.duration = instant ? 0 : Math.max(20, (int) Math.round(settings.transitionTicks * plugin.mode().transitionFactor(player)));
		s.swappedDisguise = false;
		s.soundPlayed = false;
		lastMorphStart.put(player.getUniqueId(), serverTick);

		PlayerMorphData data = plugin.store().get(player.getUniqueId());
		data.current = ref.isSelf() ? null : ref;
		plugin.store().markDirty(player.getUniqueId());
		// a new body borrows only what it was picked with; the state sent below carries it
		plugin.borrowedHeads().setBorrowed(player, ref.isSelf() ? null : borrowed, false);

		plugin.abilities().onFormChanging(player);
		if (!ENDERMAN.equals(ref.entityType())) dropCarriedBlock(player);
		s.overlay = "";
		if (instant) {
			finish(player, s);
		} else {
			if (settings.vanillaTransition && from.isSelf()) {
				plugin.disguises().showSelfCopy(player, 1.0, plugin.channel().moddedPlayers(), selfVisible(player));
			}
			plugin.channel().broadcastState(player, s);
		}
	}

	/** Re-apply the vanilla disguise for the current form (after a viewer handshake, respawn, world change...). */
	public void refreshDisguise(Player player) {
		ActiveMorph s = states.get(player.getUniqueId());
		if (s == null || s.isTransitioning()) return;
		if (s.next == null || s.next.isSelf()) {
			plugin.disguises().remove(player);
		} else {
			showDisguise(player, s.look(), 1.0);
		}
	}

	private boolean selfVisible(Player player) {
		return plugin.settings().vanillaSelfDisguise && !plugin.channel().isModded(player);
	}

	private void showDisguise(Player player, MorphRef ref, double scale) {
		if (ref.isSelf()) {
			plugin.disguises().showSelfCopy(player, scale, plugin.channel().moddedPlayers(), selfVisible(player));
			return;
		}
		LivingEntity template = plugin.templates().get(ref, player.getWorld());
		if (!plugin.disguises().show(player, ref, template, scale, plugin.channel().moddedPlayers(), selfVisible(player), plugin.settings().nameTagMode)) {
			plugin.getLogger().warning("Could not disguise " + player.getName() + " as " + ref.entityType() + "; unmodded players will see them unchanged.");
			return;
		}
		ActiveMorph s = states.get(player.getUniqueId());
		if (s != null && ref.equals(s.look())) plugin.disguises().applyOverlay(player, s.overlay);
	}

	/** Called once per server tick. */
	public void tick() {
		serverTick++;
		plugin.disguises().tick();
		if (states.isEmpty() && bodies.isEmpty()) return;
		for (UUID id : List.copyOf(bodies.keySet())) {
			// Bodies left over from a finished demorph that had no room to grow back yet.
			Player player = Bukkit.getPlayer(id);
			if (player == null || !player.isOnline()) bodies.remove(id);
			else if (!states.containsKey(id)) syncBody(player);
		}
		for (Map.Entry<UUID, ActiveMorph> e : List.copyOf(states.entrySet())) {
			Player player = Bukkit.getPlayer(e.getKey());
			ActiveMorph s = e.getValue();
			if (player == null || !player.isOnline()) continue;
			plugin.disguises().syncEquipment(player);
			plugin.disguises().syncPose(player);
			plugin.disguises().syncMotion(player);
			if (s.isTransitioning()) {
				tickTransition(player, s);
			} else if (!s.isMorphed()) {
				states.remove(e.getKey());
			} else {
				syncBody(player); // a body still waiting for room to grow
			}
		}
	}

	private void tickTransition(Player player, ActiveMorph s) {
		PluginSettings settings = plugin.settings();
		s.time++;
		float progress = s.morphProgress();
		syncBody(player);

		if (!s.soundPlayed && s.time >= Math.max(0, (s.duration - 60) / 2)) {
			s.soundPlayed = true;
			plugin.effects().morphSound(player);
		}

		double width = player.getWidth();
		double height = player.getHeight();
		if (settings.vanillaTransition) {
			// players with RE:Morph's pack see the body melt in the morph skin (SkinEffects); the others, the particles
			boolean skin = plugin.skin().transitionTick(player, s);
			float skinAlpha = progress < 0.125F ? ActiveMorph.sineify(progress / 0.125F)
					: progress > 0.875F ? 1F - ActiveMorph.sineify((progress - 0.875F) / 0.125F) : 1F;
			plugin.effects().transitionTick(player, skinAlpha, width, height, skin);
			// when everyone who would see the disguise sees the skin, the disguise is out of sight while the boxes melt,
			// rather than shrinking and growing inside them
			boolean hidden = skin && progress >= 0.125F && progress < 0.875F && plugin.skin().onlySkinViewers(player);
			if (hidden != skinHidden.contains(player.getUniqueId())) {
				if (hidden) skinHidden.add(player.getUniqueId());
				else skinHidden.remove(player.getUniqueId());
				plugin.disguises().setInvisible(player, hidden);
			}
			if (progress < 0.5F) {
				float shrink = ActiveMorph.clamp((progress - 0.125F) / 0.375F);
				plugin.disguises().setScale(player, hidden ? HIDDEN_DISGUISE_SCALE : lerp(1.0, VANILLA_MIN_DISGUISE_SCALE, ActiveMorph.sineify(shrink)));
			} else {
				if (!s.swappedDisguise) {
					s.swappedDisguise = true;
					showDisguise(player, s.look(), hidden ? HIDDEN_DISGUISE_SCALE : VANILLA_MIN_DISGUISE_SCALE);
					if (hidden) plugin.disguises().setInvisible(player, true); // a new disguise
					plugin.effects().swapBurst(player, width, height, skin);
				}
				float grow = ActiveMorph.clamp((progress - 0.5F) / 0.375F);
				plugin.disguises().setScale(player, hidden ? HIDDEN_DISGUISE_SCALE : lerp(VANILLA_MIN_DISGUISE_SCALE, 1.0, ActiveMorph.sineify(grow)));
			}
		} else if (!s.swappedDisguise && progress >= 0.5F) {
			s.swappedDisguise = true;
			showDisguise(player, s.look(), 1.0);
		}

		if (s.time >= s.duration) finish(player, s);
	}

	private void finish(Player player, ActiveMorph s) {
		plugin.skin().endTransition(player);
		if (skinHidden.remove(player.getUniqueId())) plugin.disguises().setInvisible(player, false);
		s.prev = null;
		s.time = s.duration;
		MorphRef form = s.next;
		if (form == null || form.isSelf()) {
			s.next = null;
			plugin.disguises().remove(player);
			states.remove(player.getUniqueId());
			syncBody(player);
		} else {
			form = s.look();
			syncBody(player);
			showDisguise(player, form, 1.0);
		}
		if (form != null && ENDERMAN.equals(form.entityType())) {
			String carried = plugin.store().get(player.getUniqueId()).carriedBlock;
			s.overlay = carried == null ? "" : carriedBlockOverlay(carried);
			plugin.disguises().applyOverlay(player, s.overlay);
		}
		plugin.abilities().onFormChanged(player, form == null || form.isSelf() ? null : form);
		plugin.channel().broadcastState(player, s);
	}

	// ------------------------------------------------------------------ vanilla conversions (per body, not saved)

	/**
	 * Turn this body into {@code into} the way vanilla turns a mob (a villager into a zombie villager, a zombie into a
	 * drowned): a short transformation to it, keeping the form's variant id, so the selector still shows the form picked
	 * and nothing saved changes (see {@link ActiveMorph#converted}). Turning back into the form itself (a cured zombie
	 * villager that was picked as a villager) ends the conversion. False when this body can't convert now: not morphed,
	 * transforming, or a multimorph (one creature at a time).
	 */
	public boolean convert(Player player, MorphRef into) {
		ActiveMorph s = states.get(player.getUniqueId());
		if (s == null || !s.isMorphed() || s.isTransitioning() || s.next == null || s.next.isSelf()) return false;
		if (plugin.borrowedHeads().borrowed(player) != null) return false;
		MorphRef from = s.look();
		MorphRef to = new MorphRef(into.entityType(), s.next.variantId(), into.snbt(), into.skin());
		if (from != null && from.sameAppearance(to)) return false;
		s.prev = from;
		s.converted = s.next.sameAppearance(to) ? null : to;
		s.time = 0;
		s.duration = Math.max(20, plugin.settings().conversionTicks);
		s.swappedDisguise = false;
		s.soundPlayed = true; // the conversion's own vanilla sound, not the morph sound
		s.overlay = "";
		plugin.abilities().onFormChanging(player);
		plugin.channel().broadcastState(player, s);
		return true;
	}

	/** The creature this body was turned into, or null. */
	public @Nullable MorphRef converted(Player player) {
		ActiveMorph s = states.get(player.getUniqueId());
		return s == null ? null : s.converted;
	}

	// ------------------------------------------------------------------ live appearance overlay

	public static final String ENDERMAN = "minecraft:enderman";

	/**
	 * Change the live appearance layered on the player's current form (carried block, primed creeper...). Unmodded
	 * viewers see it through the disguise watcher, modded viewers get it in the next state packet.
	 */
	public void setOverlay(Player player, String snbt) {
		ActiveMorph s = states.get(player.getUniqueId());
		if (s == null || !s.isMorphed()) return;
		s.overlay = snbt;
		plugin.disguises().applyOverlay(player, snbt);
		plugin.channel().broadcastState(player, s);
	}

	public String overlay(Player player) {
		ActiveMorph s = states.get(player.getUniqueId());
		return s == null ? "" : s.overlay;
	}

	/**
	 * Enderman: the block in its hands, as entity SNBT. Minecraft 26.3 decodes {@code carriedBlockState} as
	 * {@code {id:"minecraft:oak_log",properties:{axis:"y"}}} (the pre-1.21.5 {Name:...,Properties:...} form no longer loads).
	 */
	public static String carriedBlockOverlay(String blockData) {
		int bracket = blockData.indexOf('[');
		Map<String, Object> state = new java.util.LinkedHashMap<>();
		state.put("id", bracket < 0 ? blockData : blockData.substring(0, bracket));
		if (bracket >= 0 && blockData.endsWith("]")) {
			Map<String, Object> props = new java.util.LinkedHashMap<>();
			for (String pair : blockData.substring(bracket + 1, blockData.length() - 1).split(",")) {
				int eq = pair.indexOf('=');
				if (eq > 0) props.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
			}
			if (!props.isEmpty()) state.put("properties", props);
		}
		return dev.morphreborn.paper.nbt.Snbt.write(Map.of("carriedBlockState", state));
	}

	/** Leaving the enderman form puts down nothing: the carried block drops as an item so it is never lost. */
	public void dropCarriedBlock(Player player) {
		PlayerMorphData data = plugin.store().get(player.getUniqueId());
		if (data.carriedBlock == null) return;
		try {
			org.bukkit.block.data.BlockData block = Bukkit.createBlockData(data.carriedBlock);
			org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(block.getPlacementMaterial());
			if (!item.getType().isAir()) player.getWorld().dropItemNaturally(player.getLocation(), item);
		} catch (IllegalArgumentException e) {
			plugin.getLogger().warning("Dropping unknown carried block " + data.carriedBlock);
		}
		data.carriedBlock = null;
		plugin.store().markDirty(player.getUniqueId());
	}

	// ------------------------------------------------------------------ body (hitbox + attributes)

	/*
	 * The physical body (hitbox through the scale attribute, step height, speed and the other copied attributes)
	 * changes in one step, when the visible body swaps half-way through a transformation. It used to ease every tick,
	 * and every change reaches the client a moment after the server already moves with it: the server replays the
	 * client's movement with a different hitbox and step height, sends "moved wrongly" corrections, and the player
	 * jitters against walls and rough ground for the whole transformation.
	 *
	 * A body only grows into free space. Minecraft moves a growing mob out of the blocks around it, but never a player
	 * (Entity.refreshDimensions), and a player whose hitbox overlaps blocks can't move into anything new on the server
	 * (ServerGamePacketListenerImpl.isEntityCollidingWithAnythingNew): stuck, tries, blocked, again. So a growing body
	 * is first nudged to a free spot nearby, the way vanilla does for mobs, and if nothing fits (a ravager in a
	 * one-block tunnel), it keeps its smaller size and grows as soon as there is room.
	 */

	/** The form the body should have now: the old one before a transformation's half-way point, the new one after. */
	private @Nullable MorphRef bodyTarget(Player player) {
		ActiveMorph s = states.get(player.getUniqueId());
		MorphRef form = s == null ? null : s.activeForm();
		return form == null || form.isSelf() ? null : form;
	}

	/** Bring the body to {@link #bodyTarget}, unless it has to grow and there is no room for it yet. */
	private void syncBody(Player player) {
		MorphRef want = bodyTarget(player);
		UUID id = player.getUniqueId();
		if (Objects.equals(want, bodies.get(id))) return;
		if (!makeRoom(player, want)) return;
		applyBody(player, want, want, 1F);
		if (want == null) bodies.remove(id);
		else bodies.put(id, want);
	}

	/** True when the body for {@code form} fits where the player is, after nudging them to a free spot if needed. */
	private boolean makeRoom(Player player, @Nullable MorphRef form) {
		if (!plugin.settings().hitboxScaling || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return true;
		AttributeInstance scale = player.getAttribute(Attribute.SCALE);
		if (scale == null) return true;
		double current = scale.getValue();
		AttributeModifier mine = scale.getModifier(scaleKey);
		double next = current - (mine == null ? 0 : mine.getAmount()) + (scaleFor(player, form) - 1.0);
		if (next <= current + 1e-4) return true; // the same size or smaller always fits
		BoundingBox box = player.getBoundingBox();
		org.bukkit.World world = player.getWorld();
		if (world.hasCollisionsIn(box.clone().expand(-1e-5))) return true; // already overlapping blocks: nothing to protect
		if (player.isInsideVehicle() || !player.getPassengers().isEmpty()) return true; // the vehicle decides where they are
		double ratio = next / current;
		double half = box.getWidthX() * ratio / 2, height = box.getHeight() * ratio;
		double reach = (box.getWidthX() * ratio - box.getWidthX()) / 2 + 1e-3;
		double drop = player.isOnGround() ? 0 : box.getHeight() * ratio - box.getHeight();
		List<double[]> offsets = new ArrayList<>();
		for (int i = -4; i <= 4; i++) {
			for (int k = -4; k <= 4; k++) {
				for (int j = 0; j <= 4; j++) {
					if (j > 0 && drop == 0) break;
					offsets.add(new double[] { reach * i / 4, -drop * j / 4, reach * k / 4 });
				}
			}
		}
		offsets.sort(java.util.Comparator.comparingDouble(o -> o[0] * o[0] + o[1] * o[1] + o[2] * o[2]));
		double cx = box.getCenterX(), cz = box.getCenterZ(), y = box.getMinY();
		for (double[] o : offsets) {
			BoundingBox grown = new BoundingBox(cx + o[0] - half, y + o[1], cz + o[2] - half, cx + o[0] + half, y + o[1] + height, cz + o[2] + half);
			if (world.hasCollisionsIn(grown.expand(-1e-5))) continue;
			if (o[0] != 0 || o[1] != 0 || o[2] != 0) {
				player.teleport(player.getLocation().add(o[0], o[1], o[2]), TeleportFlag.Relative.VELOCITY_X, TeleportFlag.Relative.VELOCITY_Y,
						TeleportFlag.Relative.VELOCITY_Z, TeleportFlag.Relative.VELOCITY_ROTATION);
			}
			return true;
		}
		return false;
	}

	/** Ease hitbox scale and copied attributes from {@code from} to {@code to}; nulls mean the player's own body. */
	private void applyBody(Player player, @Nullable MorphRef from, @Nullable MorphRef to, float t) {
		PluginSettings s = plugin.settings();
		if (s.hitboxScaling) {
			double scale = lerp(scaleFor(player, from), scaleFor(player, to), t);
			setModifier(player, Attribute.SCALE, scaleKey, Math.abs(scale - 1.0) < 1e-4 ? 0 : scale - 1.0);
			// Taller morphs see the ground from further away; grow reach with them so an enderman can still reach
			// the block in front of its feet. Smaller morphs keep normal reach.
			double grow = Math.max(0, scale - 1.0);
			setModifier(player, Attribute.BLOCK_INTERACTION_RANGE, scaleKey, grow * 4.5);
			setModifier(player, Attribute.ENTITY_INTERACTION_RANGE, scaleKey, grow * 3.0);
		}
		if (s.attributesEnabled) {
			for (Map.Entry<String, PluginSettings.AttributeRule> e : s.attributes.entrySet()) {
				Attribute attribute = attribute(e.getKey());
				if (attribute == null) continue;
				double delta = lerp(attributeDelta(player, from, attribute, e.getValue()), attributeDelta(player, to, attribute, e.getValue()), t);
				setModifier(player, attribute, attributeKey, delta);
			}
			AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
			if (maxHealth != null && player.getHealth() > maxHealth.getValue()) player.setHealth(maxHealth.getValue());
		}
	}

	private double scaleFor(Player player, @Nullable MorphRef ref) {
		if (ref == null || ref.isPlayer()) return 1.0;
		LivingEntity template = plugin.templates().get(ref, player.getWorld());
		if (template == null) return 1.0;
		PluginSettings s = plugin.settings();
		return Math.clamp(template.getHeight() / PLAYER_HEIGHT, s.minScale, s.maxScale);
	}

	private double attributeDelta(Player player, @Nullable MorphRef ref, Attribute attribute, PluginSettings.AttributeRule rule) {
		if (ref == null || ref.isPlayer()) return 0;
		LivingEntity template = plugin.templates().get(ref, player.getWorld());
		AttributeInstance mob = template == null ? null : template.getAttribute(attribute);
		AttributeInstance own = player.getAttribute(attribute);
		if (mob == null || own == null) return 0;
		double value = mob.getBaseValue();
		if (rule.cap() != null) value = rule.more() ? Math.min(value, rule.cap()) : Math.max(value, rule.cap());
		if (attribute == Attribute.MAX_HEALTH) {
			// half-heart mobs (small slimes, the creaking, whose real body can't be hurt while its heart lives) get a floor
			Double override = plugin.settings().healthOverrides.get(ref.entityType());
			value = override != null ? override : Math.max(value, plugin.settings().minMaxHealth);
		}
		return value - own.getBaseValue();
	}

	private static @Nullable Attribute attribute(String key) {
		NamespacedKey k = NamespacedKey.fromString(key);
		return k == null ? null : Registry.ATTRIBUTE.get(k);
	}

	private static void setModifier(Player player, Attribute attribute, NamespacedKey key, double amount) {
		AttributeInstance instance = player.getAttribute(attribute);
		if (instance == null) return;
		AttributeModifier existing = instance.getModifier(key);
		if (existing != null) {
			if (Math.abs(existing.getAmount() - amount) < 1e-6) return;
			instance.removeModifier(key);
		}
		if (amount != 0) {
			instance.addTransientModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
		}
	}

	/** Strip every modifier we own (shutdown / quit) so nothing persists in the player file. */
	public void resetBody(Player player) {
		bodies.remove(player.getUniqueId());
		for (Attribute attribute : Registry.ATTRIBUTE) {
			AttributeInstance instance = player.getAttribute(attribute);
			if (instance == null) continue;
			instance.removeModifier(scaleKey);
			instance.removeModifier(attributeKey);
		}
	}

	private static double lerp(double a, double b, float t) {
		return a + (b - a) * t;
	}

	// ------------------------------------------------------------------ lifecycle

	public void onJoin(Player player) {
		PlayerMorphData data = plugin.store().get(player.getUniqueId());
		resetBody(player);
		plugin.mode().onJoin(player);
		if (plugin.settings().persistCurrentMorph && data.current != null) {
			MorphRef owned = resolve(player, data, data.current.variantId());
			// A Survival player who can't morph (yet) logs in as themselves; their morphs wait until they can.
			if (owned != null && (owned.isSelfBody() || canUseType(player, owned.entityType())) && plugin.mode().cannotMorph(player) == null) {
				MorphRef borrowed = data.borrowed == null ? null : resolve(player, data, data.borrowed.variantId());
				if (borrowed != null && (!plugin.settings().multimorphEnabled || !player.hasPermission("morphreborn.multimorph") || plugin.mode().cannotCombine(player) != null
						|| (!borrowed.isSelfBody() && !canUseType(player, borrowed.entityType())) || cannotCombine(borrowed) != null)) {
					borrowed = null;
				}
				if (owned.isSelfBody() && borrowed == null) {
					// your own body with nothing borrowed is just you
					data.current = null;
					data.borrowed = null;
					return;
				}
				MorphRef keep = borrowed;
				Bukkit.getScheduler().runTaskLater(plugin, () -> {
					if (player.isOnline()) morphTo(player, owned, true, keep);
				}, 5L);
			} else {
				data.current = null;
				data.borrowed = null;
			}
		} else {
			data.borrowed = null;
		}
	}

	public void onQuit(Player player) {
		plugin.disguises().remove(player);
		resetBody(player);
		ActiveMorph s = states.remove(player.getUniqueId());
		lastMorphStart.remove(player.getUniqueId());
		PlayerMorphData data = plugin.store().get(player.getUniqueId());
		data.current = s != null && s.next != null && !s.next.isSelf() ? s.next : null;
		plugin.channel().broadcastForget(player, player.getWorld().getPlayers());
	}

	public void onDeath(Player player) {
		PluginSettings s = plugin.settings();
		PlayerMorphData data = plugin.store().get(player.getUniqueId());
		MorphRef form = activeForm(player);
		if (s.resetCollectionOnDeath) {
			data.morphs.clear();
			data.killCounts.clear();
			plugin.store().markDirty(player.getUniqueId());
		} else if (s.loseCurrentMorphOnDeath && form != null) {
			data.remove(form.variantId());
			plugin.store().markDirty(player.getUniqueId());
		}
		if (!s.keepMorphOnDeath || s.resetCollectionOnDeath || s.loseCurrentMorphOnDeath) {
			demorph(player, true);
		}
		plugin.channel().sendCollection(player);
	}

	public void onRespawn(Player player) {
		ActiveMorph s = states.get(player.getUniqueId());
		if (s == null) return;
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (!player.isOnline()) return;
			// A fresh body: the stinger grows back, the wool too, a primed creeper is no longer primed. The enderman
			// keeps whatever block it still has stored.
			if (s.isMorphed() && s.next != null) {
				String carried = plugin.store().get(player.getUniqueId()).carriedBlock;
				s.overlay = ENDERMAN.equals(s.next.entityType()) && carried != null ? carriedBlockOverlay(carried) : "";
				s.converted = null; // and the creature it was picked as, whatever it was turned into
			}
			bodies.remove(player.getUniqueId()); // a respawned player has a fresh body: apply it again
			syncBody(player);
			refreshDisguise(player);
			plugin.abilities().onFormChanged(player, activeForm(player));
			plugin.channel().broadcastState(player, s);
		}, 1L);
	}

	public void onWorldChange(Player player, List<Player> oldWorldPlayers) {
		plugin.channel().broadcastForget(player, oldWorldPlayers);
		ActiveMorph s = states.get(player.getUniqueId());
		if (s != null && s.isMorphed()) plugin.channel().broadcastState(player, s);
		if (plugin.channel().isModded(player)) {
			for (Player other : player.getWorld().getPlayers()) {
				ActiveMorph os = states.get(other.getUniqueId());
				if (os != null && os.isMorphed()) plugin.channel().sendState(player, other, os);
			}
		}
	}

	/** A viewer just proved it has the client mod. */
	public void onClientModded(Player player) {
		plugin.disguises().exclude(player);
		plugin.borrowedHeads().refreshViewer(player);
		refreshDisguise(player); // its own disguise loses the self-view
	}

	public void shutdown() {
		for (UUID id : List.copyOf(states.keySet())) {
			Player p = Bukkit.getPlayer(id);
			if (p == null) continue;
			ActiveMorph s = states.get(id);
			PlayerMorphData data = plugin.store().get(id);
			data.current = s != null && s.next != null && !s.next.isSelf() ? s.next : null;
			plugin.store().markDirty(id);
			resetBody(p);
		}
		states.clear();
		plugin.disguises().removeAll();
	}
}
