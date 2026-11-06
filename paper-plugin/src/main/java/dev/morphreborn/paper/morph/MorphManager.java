package dev.morphreborn.paper.morph;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.PluginSettings;
import dev.morphreborn.paper.data.MorphRef;
import dev.morphreborn.paper.data.PlayerMorphData;
import dev.morphreborn.paper.net.ClientChannel;
import dev.morphreborn.paper.variant.VariantCapture;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

	private final MorphRebornPlugin plugin;
	private final NamespacedKey scaleKey;
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
		if (MorphRef.SELF_ID.equals(variantId)) {
			if (current != null && current.isMorphed()) demorph(player, false);
			ch.sendResult(player, ClientChannel.RESULT_OK, "");
			return;
		}
		String locked = plugin.mode().cannotMorph(player);
		if (locked != null) {
			refuse(player, ClientChannel.RESULT_DENIED, locked);
			return;
		}
		PlayerMorphData data = plugin.store().get(player.getUniqueId());
		MorphRef ref = data.byId(variantId);
		if (ref == null) {
			refuse(player, ClientChannel.RESULT_UNKNOWN_MORPH, "You don't have that morph.");
			ch.sendCollection(player);
			return;
		}
		if (!canUseType(player, ref.entityType())) {
			refuse(player, ClientChannel.RESULT_DENIED, "You are not allowed to use that morph.");
			return;
		}
		if (current != null && current.next != null && current.next.variantId().equals(ref.variantId())) {
			ch.sendResult(player, ClientChannel.RESULT_OK, "");
			return;
		}
		morphTo(player, ref, false);
		ch.sendResult(player, ClientChannel.RESULT_OK, "");
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
		}
		plugin.channel().sendCollection(player);
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
	 * Count a kill toward {@code appearance} and unlock it when enough kills are in (required-kills) and the collection
	 * has room (config.yml's per-type cap, and the mode's capacity). Returns the new morph, or null.
	 */
	public @Nullable MorphRef acquire(Player killer, MorphRef appearance) {
		PluginSettings s = plugin.settings();
		PlayerMorphData data = plugin.store().get(killer.getUniqueId());
		if (data.find(appearance) != null) return null;
		List<PlayerMorphData.StoredVariant> existing = data.morphs.get(appearance.entityType());
		int types = plugin.mode().typeCapacity(killer);
		if (types >= 0 && existing == null && data.morphs.size() >= types) {
			killer.sendActionBar(Component.text("Your collection is full (" + types + " mob types). Upgrade Morph Capacity or delete a morph.", NamedTextColor.GRAY));
			return null;
		}
		int looks = plugin.mode().variantCapacity(killer);
		if (looks >= 0 && existing != null && existing.size() >= looks) {
			killer.sendActionBar(Component.text("No room for another look of this mob (" + looks + "). Upgrade Morph Variant Capacity.", NamedTextColor.GRAY));
			return null;
		}
		int needed = s.requiredKills(appearance.entityType());
		if (needed > 1) {
			int kills = data.killCounts.merge(appearance.entityType(), 1, Integer::sum);
			plugin.store().markDirty(killer.getUniqueId());
			if (kills < needed) {
				killer.sendActionBar(Component.text("Morph progress: " + kills + "/" + needed, NamedTextColor.GRAY));
				return null;
			}
		}
		if (s.maxVariantsPerType > 0 && existing != null && existing.size() >= s.maxVariantsPerType) return null;
		return unlock(killer, appearance);
	}

	/** The acquisition animation: tendrils for modded viewers, particle trails for everyone else. */
	public void playAcquisition(Player killer, LivingEntity victim) {
		plugin.channel().sendAcquire(killer, victim);
		plugin.effects().acquisition(killer, victim);
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
		PluginSettings settings = plugin.settings();
		ActiveMorph s = states.computeIfAbsent(player.getUniqueId(), k -> new ActiveMorph());
		MorphRef from = s.next != null ? s.next : MorphRef.self(VariantCapture.skinOf(player));
		if (from.isSelf() && ref.isSelf()) {
			states.remove(player.getUniqueId());
			return;
		}
		s.prev = from;
		s.next = ref;
		s.time = 0;
		s.duration = instant ? 0 : Math.max(20, (int) Math.round(settings.transitionTicks * plugin.mode().transitionFactor(player)));
		s.swappedDisguise = false;
		s.soundPlayed = false;
		lastMorphStart.put(player.getUniqueId(), serverTick);

		PlayerMorphData data = plugin.store().get(player.getUniqueId());
		data.current = ref.isSelf() ? null : ref;
		plugin.store().markDirty(player.getUniqueId());

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
			showDisguise(player, s.next, 1.0);
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
		if (s != null && ref.equals(s.next)) plugin.disguises().applyOverlay(player, s.overlay);
	}

	/** Called once per server tick. */
	public void tick() {
		serverTick++;
		plugin.disguises().tick();
		if (states.isEmpty()) return;
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
			}
		}
	}

	private void tickTransition(Player player, ActiveMorph s) {
		PluginSettings settings = plugin.settings();
		s.time++;
		float progress = s.morphProgress();
		float eased = s.transitionSine();

		applyBody(player, s.prev, s.next, eased);

		if (!s.soundPlayed && s.time >= Math.max(0, (s.duration - 60) / 2)) {
			s.soundPlayed = true;
			plugin.effects().morphSound(player);
		}

		double width = player.getWidth();
		double height = player.getHeight();
		if (settings.vanillaTransition) {
			float skinAlpha = progress < 0.125F ? ActiveMorph.sineify(progress / 0.125F)
					: progress > 0.875F ? 1F - ActiveMorph.sineify((progress - 0.875F) / 0.125F) : 1F;
			plugin.effects().transitionTick(player, skinAlpha, width, height);
			if (progress < 0.5F) {
				float shrink = ActiveMorph.clamp((progress - 0.125F) / 0.375F);
				plugin.disguises().setScale(player, lerp(1.0, VANILLA_MIN_DISGUISE_SCALE, ActiveMorph.sineify(shrink)));
			} else {
				if (!s.swappedDisguise) {
					s.swappedDisguise = true;
					showDisguise(player, s.next, VANILLA_MIN_DISGUISE_SCALE);
					plugin.effects().swapBurst(player, width, height);
				}
				float grow = ActiveMorph.clamp((progress - 0.5F) / 0.375F);
				plugin.disguises().setScale(player, lerp(VANILLA_MIN_DISGUISE_SCALE, 1.0, ActiveMorph.sineify(grow)));
			}
		} else if (!s.swappedDisguise && progress >= 0.5F) {
			s.swappedDisguise = true;
			showDisguise(player, s.next, 1.0);
		}

		if (s.time >= s.duration) finish(player, s);
	}

	private void finish(Player player, ActiveMorph s) {
		s.prev = null;
		s.time = s.duration;
		MorphRef form = s.next;
		if (form == null || form.isSelf()) {
			s.next = null;
			plugin.disguises().remove(player);
			applyBody(player, null, null, 1F);
			states.remove(player.getUniqueId());
		} else {
			applyBody(player, form, form, 1F);
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
			MorphRef owned = data.byId(data.current.variantId());
			// A Survival player who can't morph (yet) logs in as themselves; their morphs wait until they can.
			if (owned != null && canUseType(player, owned.entityType()) && plugin.mode().cannotMorph(player) == null) {
				Bukkit.getScheduler().runTaskLater(plugin, () -> {
					if (player.isOnline()) morphTo(player, owned, true);
				}, 5L);
			} else {
				data.current = null;
			}
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
			}
			applyBody(player, s.next, s.next, 1F);
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
