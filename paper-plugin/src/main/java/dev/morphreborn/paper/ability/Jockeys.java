package dev.morphreborn.paper.ability;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.data.MorphRef;
import dev.morphreborn.paper.variant.VariantRules;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * Jockeys: a skeleton morph rides a spider, a baby zombie a chicken, as vanilla's jockeys do. Right-click the mount to
 * get on; the rider steers it with their movement keys (jump to jump; a spider pushed into a wall climbs it, a chicken
 * glides down), and sneaking gets off. While ridden, the mount's own mind is switched off (Mob#setAware) so it can't
 * wander or attack; it comes back when the rider gets off. Works the same with and without the client mod.
 *
 * <p>Which morph rides what is abilities.yml's {@code jockeys}: mount type -> rider types, where "baby " in front of a
 * rider means only its baby form.
 */
public final class Jockeys implements Listener {
	private final MorphRebornPlugin plugin;
	/** mount type -> riders (type ids, "baby " + type for baby-only). */
	private Map<String, Set<String>> mounts = Map.of();
	/** rider -> mount, for steering and restoring the mount's mind. */
	private final Map<UUID, UUID> riding = new HashMap<>();

	public Jockeys(MorphRebornPlugin plugin) {
		this.plugin = plugin;
	}

	public void load(@Nullable ConfigurationSection section) {
		Map<String, Set<String>> loaded = new HashMap<>();
		if (section != null) {
			for (String mount : section.getKeys(false)) {
				Set<String> riders = new HashSet<>();
				for (String rider : section.getStringList(mount)) {
					String r = rider.trim().toLowerCase(java.util.Locale.ROOT);
					riders.add(r.startsWith("baby ") ? "baby " + VariantRules.normaliseType(r.substring(5).trim()) : VariantRules.normaliseType(r));
				}
				loaded.put(VariantRules.normaliseType(mount), Set.copyOf(riders));
			}
		}
		mounts = Map.copyOf(loaded);
	}

	/** True when {@code player}'s current form may ride {@code mount}. */
	private boolean canRide(Player player, Entity mount) {
		Set<String> riders = mounts.get(mount.getType().getKey().toString());
		if (riders == null) return false;
		MorphRef form = plugin.morphs().activeForm(player);
		if (form == null || form.isPlayer()) return false;
		String type = form.entityType();
		if (riders.contains(type)) return true;
		if (!riders.contains("baby " + type)) return false;
		LivingEntity template = plugin.templates().get(form, player.getWorld());
		return template instanceof Ageable ageable && !ageable.isAdult();
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onInteract(PlayerInteractEntityEvent event) {
		if (event.getHand() != EquipmentSlot.HAND) return;
		Player player = event.getPlayer();
		Entity mount = event.getRightClicked();
		if (!(mount instanceof Mob mob) || player.isInsideVehicle() || !mount.getPassengers().isEmpty() || !canRide(player, mount)) return;
		if (!player.hasPermission("morphreborn.ability")) return;
		String locked = plugin.mode().cannotUseAbility(player);
		if (locked != null) {
			player.sendActionBar(net.kyori.adventure.text.Component.text(locked, net.kyori.adventure.text.format.NamedTextColor.RED));
			return;
		}
		event.setCancelled(true);
		if (!mob.addPassenger(player)) return;
		mob.setAware(false);
		mob.setTarget(null);
		riding.put(player.getUniqueId(), mob.getUniqueId());
	}

	/** Every server tick: steer each mount from its rider's movement keys. */
	public void tick() {
		if (riding.isEmpty()) return;
		for (Map.Entry<UUID, UUID> e : List.copyOf(riding.entrySet())) {
			Player player = Bukkit.getPlayer(e.getKey());
			Entity mount = Bukkit.getEntity(e.getValue());
			if (player == null || mount == null || !mount.isValid() || player.getVehicle() != mount) {
				release(e.getKey());
				continue;
			}
			if (!canRide(player, mount)) { // morphed into something that can't ride it
				mount.removePassenger(player);
				release(e.getKey());
				continue;
			}
			steer(player, mount);
		}
	}

	private static void steer(Player player, Entity mount) {
		org.bukkit.Input input = player.getCurrentInput();
		float yaw = player.getYaw();
		mount.setRotation(yaw, 0F);
		if (mount instanceof LivingEntity living) living.setBodyYaw(yaw);
		Vector v = mount.getVelocity();
		Vector wish = AbilityManager.wishDirection(player, input);
		double hx, hz;
		if (wish == null) {
			hx = v.getX() * 0.5;
			hz = v.getZ() * 0.5;
		} else {
			double speed = baseSpeed(mount) * 0.9 * (input.isSprint() ? 1.3 : 1.0);
			hx = wish.getX() * speed;
			hz = wish.getZ() * speed;
		}
		double y = v.getY();
		if (input.isJump() && mount.isOnGround()) y = 0.42;
		mount.setVelocity(new Vector(hx, y, hz));
	}

	private static double baseSpeed(Entity mount) {
		if (mount instanceof LivingEntity living) {
			AttributeInstance speed = living.getAttribute(Attribute.MOVEMENT_SPEED);
			if (speed != null) return speed.getValue();
		}
		return 0.25;
	}

	private void release(UUID rider) {
		UUID mountId = riding.remove(rider);
		if (mountId != null && Bukkit.getEntity(mountId) instanceof Mob mob && mob.isValid()) mob.setAware(true);
	}

	@EventHandler
	public void onDismount(EntityDismountEvent event) {
		if (event.getEntity() instanceof Player player) release(player.getUniqueId());
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		Player player = event.getPlayer();
		if (riding.containsKey(player.getUniqueId()) && player.getVehicle() != null) player.getVehicle().removePassenger(player);
		release(player.getUniqueId());
	}

	@EventHandler
	public void onMountDeath(EntityDeathEvent event) {
		UUID dead = event.getEntity().getUniqueId();
		riding.entrySet().removeIf(e -> e.getValue().equals(dead));
	}

	/** Give every ridden mount its mind back (shutdown, reload). */
	public void releaseAll() {
		for (UUID rider : List.copyOf(riding.keySet())) release(rider);
	}
}
