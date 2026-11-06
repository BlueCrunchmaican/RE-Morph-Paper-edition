package dev.morphreborn.client.morph;

import dev.morphreborn.client.MorphClient;
import dev.morphreborn.client.data.MorphState;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import dev.morphreborn.client.mixin.IllusionerAccessor;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemState;
import net.minecraft.world.entity.animal.panda.Panda;
import net.minecraft.world.entity.animal.polarbear.PolarBear;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.illager.Illusioner;
import net.minecraft.world.entity.monster.illager.SpellcasterIllager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Mob animations that vanilla drives with entity events or synced flags rather than an arm swing: a morphed player's
 * melee swing plays the mob's own attack animation, and abilities play the matching mob animation.
 */
public final class MorphAnimations {
	/** Entity event that starts a mob's attack animation (IronGolem, Ravager, Hoglin, Zoglin, Warden, Creaking). */
	private static final byte ATTACK = 4;
	private static final byte SHEEP_EAT = 10;
	/** Rabbit.handleEntityEvent(1): one hop of its legs. */
	private static final byte RABBIT_HOP = 1;
	private static final byte GOAT_RAM = 58;
	private static final byte GOAT_RAISE_HEAD = 59;
	private static final byte WARDEN_SONIC_BOOM = 62;
	private static final Set<EntityType<?>> ATTACK_ON_SWING = Set.of(EntityTypes.IRON_GOLEM, EntityTypes.RAVAGER, EntityTypes.HOGLIN, EntityTypes.ZOGLIN,
			EntityTypes.WARDEN, EntityTypes.CREAKING);

	private static final Map<LivingEntity, Boolean> WAS_SWINGING = new WeakHashMap<>();
	/** Copper golems mid-sort, with ticks since they reached into the chest. */
	private static final Map<LivingEntity, Integer> SORTING = new WeakHashMap<>();
	/** Flags to switch off again after a few ticks (ghast face, illager casting pose, a ramming goat's head). */
	private static final Map<LivingEntity, Integer> RESET_IN = new WeakHashMap<>();
	/** Morphs mid long jump (frog, goat, breeze), with ticks since take-off: they hold the LONG_JUMPING pose until landing. */
	private static final Map<LivingEntity, Integer> LEAPING = new WeakHashMap<>();

	private MorphAnimations() {}

	/** Called every tick per morph: a fresh player swing becomes the mob's attack animation. */
	public static void tickSwing(LivingEntity morph, Player player) {
		boolean swinging = player.isSwinging();
		Boolean was = WAS_SWINGING.put(morph, swinging);
		if (swinging && !Boolean.TRUE.equals(was) && ATTACK_ON_SWING.contains(morph.getType())) morph.handleEntityEvent(ATTACK);
	}

	/** Server says {@code player} used {@code ability}: animate their morph. */
	public static void ability(UUID player, String ability) {
		MorphState state = MorphClient.STATES.get(player);
		if (state == null || state.next == null || state.next.isSelf()) return;
		LivingEntity morph = MorphClient.ENTITIES.forWorld(player, state.next);
		if (morph == null) return;
		switch (ability) {
			case "toss", "scare" -> morph.handleEntityEvent(ATTACK);
			case "ram" -> {
				morph.handleEntityEvent(morph instanceof Goat ? GOAT_RAM : ATTACK);
				if (morph instanceof Goat) RESET_IN.put(morph, 20);
			}
			case "sort" -> {
				if (morph instanceof CopperGolem golem) {
					golem.setState(CopperGolemState.GETTING_ITEM);
					SORTING.put(morph, 0);
				}
			}
			case "sonic-boom" -> morph.handleEntityEvent(WARDEN_SONIC_BOOM);
			case "hop" -> {
				if (morph instanceof Rabbit) morph.handleEntityEvent(RABBIT_HOP);
			}
			case "graze" -> morph.handleEntityEvent(SHEEP_EAT);
			case "leap" -> LEAPING.put(morph, 0);
			case "large-fireball" -> {
				if (morph instanceof Ghast ghast) {
					ghast.setCharging(true);
					RESET_IN.put(morph, 10);
				}
			}
			case "evoker-fangs", "vanish", "illusion-blind" -> {
				if (morph instanceof SpellcasterIllager caster) {
					caster.setIsCastingSpell(switch (ability) {
						case "vanish" -> SpellcasterIllager.IllagerSpell.DISAPPEAR;
						case "illusion-blind" -> SpellcasterIllager.IllagerSpell.BLINDNESS;
						default -> SpellcasterIllager.IllagerSpell.FANGS;
					});
					RESET_IN.put(morph, 20);
				}
				if (ability.equals("vanish") && morph instanceof Illusioner illusioner) scatterMirrorImages(illusioner);
			}
			case "roll" -> {
				if (morph instanceof Panda panda) {
					panda.roll(true);
					RESET_IN.put(morph, 20);
				}
			}
			case "rear-up" -> {
				if (morph instanceof PolarBear bear) {
					bear.setStanding(true);
					RESET_IN.put(morph, 20);
				}
			}
			default -> {
				// swing-style abilities (projectiles, potions) already animate through the player's arm swing
			}
		}
	}

	/** The pose the morph should copy this tick: the player's own, except while a long jump is in the air. */
	public static Pose pose(LivingEntity morph, Player player) {
		Integer airborne = LEAPING.get(morph);
		if (airborne == null) return player.getPose();
		if ((airborne > 2 && player.onGround()) || airborne > 200 || player.isInWater()) {
			LEAPING.remove(morph);
			return player.getPose();
		}
		LEAPING.put(morph, airborne + 1);
		return Pose.LONG_JUMPING;
	}

	/**
	 * An invisible illusioner is drawn as four mirror images, which vanilla only spreads out when it gets hurt
	 * (Illusioner.aiStep). Casting the spell scatters them the same way, so the real one hides among the copies.
	 */
	private static void scatterMirrorImages(Illusioner illusioner) {
		IllusionerAccessor mirror = (IllusionerAccessor) illusioner;
		Vec3[][] offsets = mirror.morph$offsets();
		RandomSource random = illusioner.getRandom();
		mirror.morph$setIllusionTicks(3);
		for (int i = 0; i < 4; i++) {
			offsets[0][i] = offsets[1][i];
			offsets[1][i] = new Vec3((-6.0F + random.nextInt(13)) * 0.5, Math.max(0, random.nextInt(6) - 4), (-6.0F + random.nextInt(13)) * 0.5);
		}
	}

	public static void tick() {
		for (Iterator<Map.Entry<LivingEntity, Integer>> it = SORTING.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<LivingEntity, Integer> e = it.next();
			int t = e.getValue() + 1;
			e.setValue(t);
			// reach in, then put the stack down: the same timing as the server's chest lids
			if (t == 30 && e.getKey() instanceof CopperGolem golem) golem.setState(CopperGolemState.DROPPING_ITEM);
			if (t >= 60) {
				if (e.getKey() instanceof CopperGolem golem) golem.setState(CopperGolemState.IDLE);
				it.remove();
			}
		}
		for (Iterator<Map.Entry<LivingEntity, Integer>> it = RESET_IN.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<LivingEntity, Integer> e = it.next();
			int left = e.getValue() - 1;
			if (left > 0) {
				e.setValue(left);
				continue;
			}
			it.remove();
			if (e.getKey() instanceof Ghast ghast) ghast.setCharging(false);
			if (e.getKey() instanceof SpellcasterIllager caster) caster.setIsCastingSpell(SpellcasterIllager.IllagerSpell.NONE);
			if (e.getKey() instanceof Panda panda) panda.roll(false);
			if (e.getKey() instanceof PolarBear bear) bear.setStanding(false);
			if (e.getKey() instanceof Goat goat) goat.handleEntityEvent(GOAT_RAISE_HEAD);
		}
	}
}
