package dev.morphreborn.paper.arm.accessory;

import dev.morphreborn.paper.arm.ArmProtocol;
import dev.morphreborn.paper.arm.api.ArmContext;
import dev.morphreborn.paper.arm.api.ArmFxMsg;
import dev.morphreborn.paper.arm.rules.ArmLedger;
import dev.morphreborn.paper.arm.rules.FlintRules;
import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.potion.PotionEffectType;

/**
 * Setting a creature alight for the arm, the one way every arm that does it goes about it (the flint and steel's melee hits, the bow's Fire
 * tip and the crossbow's burning bolts): it can burn, isn't in water and isn't alight already, no plugin stops the fire
 * (EntityCombustByEntityEvent, as the game's Fire Aspect and a burning arrow are offered), and only then is the ignition paid for, at the
 * flint and steel's price (arm-cost.specials flint-ignite), all or nothing. Moved out of BowArm on 2026-11-16 so the three share one test
 * of what can burn.
 */
public final class Ignition {
	/** The flint and steel's row in survival.yml arm-cost.specials: one ignition's price. */
	public static final String COST_KEY = "flint-ignite";

	/**
	 * Creatures whose type the game marks fire-immune (EntityTypes' fireImmune(), 26.3; there is no API to ask), so lighting them costs
	 * nothing. A heart-bound creaking is immune too (Creaking.fireImmune), which {@link #fireImmune} asks of the creature itself.
	 * Modded mobs that are fire-immune are not in this list and are billed like any other.
	 */
	private static final Set<EntityType> FIREPROOF = EnumSet.of(EntityType.BLAZE, EntityType.MAGMA_CUBE, EntityType.GHAST, EntityType.STRIDER,
			EntityType.WITHER_SKELETON, EntityType.ZOMBIFIED_PIGLIN, EntityType.WITHER, EntityType.ENDER_DRAGON, EntityType.SHULKER, EntityType.VEX,
			EntityType.WARDEN, EntityType.ZOGLIN);

	private Ignition() {}

	/** Whether the creature can't burn: a fire-immune type, or a creaking bound to its heart (it has a home; a wild one burns). */
	public static boolean fireImmune(LivingEntity victim) {
		return FIREPROOF.contains(victim.getType()) || victim instanceof org.bukkit.entity.Creaking creaking && creaking.getHome() != null;
	}

	/** Whether the creature could be lit now: it can burn, isn't in water, and isn't alight already (nothing is billed for any of those). */
	public static boolean canBeLit(LivingEntity victim) {
		return !fireImmune(victim) && !victim.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE) && !victim.isInWater() && victim.getFireTicks() <= 0;
	}

	/**
	 * One ignition, paid in biomass once the creature can be lit and no other plugin has stopped it; not paid, not lit.
	 *
	 * @param source the entity that sets it alight as other plugins see it: the owner for a melee hit, the arrow for a shot
	 * @param ticks how long it burns
	 * @param level the level of what lit it (the flint's for a melee hit, the bow's or the crossbow's for a shot): the price's level, and the one the clients are told
	 * @return whether the creature is now alight
	 */
	public static boolean ignite(ArmContext c, Player owner, LivingEntity victim, Entity source, int ticks, int level) {
		if (ticks <= 0 || !canBeLit(victim)) return false;
		EntityCombustByEntityEvent combust = new EntityCombustByEntityEvent(source, victim, ticks / 20.0F);
		Bukkit.getPluginManager().callEvent(combust);
		if (combust.isCancelled()) return false;
		ArmLedger.Spend paid = c.spend(owner, c.config().costs().special(COST_KEY, level), false);
		if (!paid.paid()) {
			owner.sendActionBar(Component.text("Not enough biomass to set it alight", NamedTextColor.RED));
			return false;
		}
		int burn = Math.round(combust.getDuration() * 20);
		victim.setFireTicks(Math.max(victim.getFireTicks(), burn));
		Location at = victim.getLocation();
		at.getWorld().playSound(at, Sound.ITEM_FLINTANDSTEEL_USE, SoundCategory.PLAYERS, 0.6F, 0.9F + ThreadLocalRandom.current().nextFloat() * 0.3F);
		c.fx(owner, new ArmFxMsg(owner.getEntityId(), ArmProtocol.FX_ACCESSORY, level, burn, ThreadLocalRandom.current().nextInt(), new int[] {victim.getEntityId()},
				new long[] {new Pos(at.getBlockX(), at.getBlockY(), at.getBlockZ()).asLong()}, FlintRules.fxArg(FlintRules.FX_IGNITE)),
				ArmProtocol.fxRadius(ArmProtocol.FX_ACCESSORY));
		return true;
	}
}
