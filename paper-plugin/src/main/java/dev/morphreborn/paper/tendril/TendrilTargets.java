package dev.morphreborn.paper.tendril;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.ability.Rage;
import dev.morphreborn.paper.ability.ShulkerLock;
import java.util.Set;
import org.bukkit.GameMode;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Which creatures a tendril may take hold of: an arm special's cut (Draining Cut) and, later, the bow's harpoon. The
 * rules are Devouring Frenzy's (Rage.canTarget, wears, drainable), copied rather than shared, so tuning the frenzy
 * never changes what the arm may strike, or the other way round. What changes is that an arm special is aimed: the player
 * chose to strike, so in {@code deliberate} mode any creature in front of them is fair game, not only hostile ones,
 * and players are struck by the PvP rules (the damage event decides) rather than never taken below the frenzy's floor.
 * <p>
 * Never selected, in either mode: the player themself, anything dead or invulnerable, NPCs (the NPC metadata
 * Citizens and others set), the player's own mount and riders, anything that isn't a mob (armour stands and the like),
 * the player's own pets, anything wearing a saddle, harness or body armour or carrying a rider (someone's mount, made
 * theirs in ways the game doesn't record), and the helpers that hold a locked shulker body in its block.
 */
public final class TendrilTargets {
	/**
	 * Hostile kinds that leave you alone until provoked (or in daylight): outside deliberate mode they are only taken
	 * when they are after the player. The frenzy's list.
	 */
	private static final Set<String> NEUTRAL = Set.of("minecraft:enderman", "minecraft:zombified_piglin", "minecraft:piglin", "minecraft:spider",
			"minecraft:cave_spider");

	private TendrilTargets() {}

	/**
	 * Whether {@code owner}'s tendrils may take hold of {@code e}.
	 *
	 * @param deliberate an aimed strike: any mob in reach (true); otherwise only creatures that are hostile to the owner
	 *            right now (enemies that aren't of the neutral kinds, and anything targeting the owner), and never players
	 * @param players arms.players: aimed strikes may hit players in Survival or Adventure (their PvP rules still decide)
	 */
	public static boolean canTarget(MorphRebornPlugin plugin, Player owner, Entity e, boolean deliberate, boolean players) {
		if (!(e instanceof LivingEntity living) || e == owner || living.isDead() || !living.isValid() || living.isMarkedInvulnerable() || e.hasMetadata("NPC")) {
			return false;
		}
		if (e.getWorld() != owner.getWorld()) return false;
		// the player's own mount and riders
		if (e.equals(owner.getVehicle()) || owner.getPassengers().contains(e) || e.getPassengers().contains(owner)) return false;
		// someone's mount, in use
		if (!e.getPassengers().isEmpty()) return false;
		ShulkerLock lock = plugin.shulkerLock();
		if (lock != null && lock.isHelper(e)) return false;
		if (e instanceof Player p) return deliberate && players && playable(p);
		if (!(e instanceof Mob m)) return false; // armour stands and the like
		if (ownPet(owner, m)) return false;
		// made someone's own with a saddle, a harness or armour (the game doesn't record whose)
		if (wears(m, EquipmentSlot.SADDLE) || wears(m, EquipmentSlot.BODY)) return false;
		if (deliberate || owner.equals(m.getTarget())) return true;
		return m instanceof Enemy && !NEUTRAL.contains(m.getType().getKey().toString());
	}

	/** A player who can be struck at all: in Survival or Adventure, alive, not made invulnerable. */
	public static boolean playable(Player p) {
		return (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) && !p.isMarkedInvulnerable() && !p.isDead();
	}

	/** A player a drain may still take health from: playable and above {@code floor}, which a drain never takes them below. */
	public static boolean drainable(Player p, double floor) {
		return playable(p) && p.getHealth() > floor;
	}

	/** The owner's own tamed pet. */
	public static boolean ownPet(Player owner, Entity e) {
		return e instanceof Tameable pet && pet.isTamed() && owner.getUniqueId().equals(pet.getOwnerUniqueId());
	}

	/** Something in a creature's saddle or body slot (horse, wolf and nautilus armour, a llama's carpet, a harness). */
	public static boolean wears(LivingEntity e, EquipmentSlot slot) {
		if (!e.canUseEquipmentSlot(slot)) return false;
		EntityEquipment equipment = e.getEquipment();
		return equipment != null && !equipment.getItem(slot).isEmpty();
	}

	/**
	 * How much of what a tendril takes from {@code e} heals its owner (spec 3.7): all of it from a hostile mob, the
	 * passive rate from anything else, and the lesser rate from players and from what the frenzy drains more slowly
	 * (bosses, villagers, name-tagged creatures, other players' pets: Rage.isLesser). Where more than one applies the
	 * smallest counts, so a name-tagged zombie heals at the lesser rate, not the hostile one: a pen of pets or a
	 * captive villager is never a better healing station than a fight.
	 */
	public static double healRate(MorphRebornPlugin plugin, Player owner, LivingEntity e, double lesserRate, double passiveRate) {
		double lesser = Math.clamp(lesserRate, 0, 1), passive = Math.clamp(passiveRate, 0, 1);
		if (e instanceof Player) return lesser;
		double rate = e instanceof Enemy ? 1 : passive;
		Rage rage = plugin.rage();
		if (rage != null && rage.isLesser(owner, e)) rate = Math.min(rate, lesser);
		return rate;
	}
}
