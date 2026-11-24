package dev.morphreborn.paper.arm.special;

import dev.morphreborn.paper.arm.rules.WhipMoves;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.jspecify.annotations.Nullable;

/**
 * Holding a creature where it stands for a time (the whip's Snare, the spear's Impale), as one module's own set of holds. The
 * way it is held is the Snare's, written down by the owner's rework of 2026-11-16: a transient -100% modifier on the walking,
 * jumping and flying speed (never saved), and what the attributes don't stop is taken back each tick by what kind of mover it
 * is (WhipMoves.holdKind and WhipMoves.heldVelocity, pure and tested). Each module keeps its own modifier key, so one module
 * letting go never frees a creature another still holds, and one hold per creature: holding again only extends it.
 * <p>
 * Teleports are the module's to cancel (an event listener asks {@link #isHeld}); a held player is never made to stand still by
 * velocity (their client moves them), only by the attributes.
 */
public final class ArmHold {
	/** Kinds a tendril never takes hold of, bosses and the like, whatever they weigh (the whip's list). */
	private static final Set<String> NEVER_HELD = Set.of("minecraft:ender_dragon", "minecraft:wither", "minecraft:warden", "minecraft:elder_guardian");

	/** One hold: whose, until which server tick, and how the creature moves (so how it is stopped). */
	public record Held(UUID owner, int until, WhipMoves.HoldKind kind) {}

	/** A hold ended: the creature's id, the hold it had, and whether it ran its course (true) or was cut short (false: the creature died or left). */
	@FunctionalInterface
	public interface Ended {
		void end(UUID id, Held held, boolean ranCourse);
	}

	private final NamespacedKey modifier;
	private final Map<UUID, Held> held = new HashMap<>();

	/** @param modifier this module's key for the attribute modifiers it adds */
	public ArmHold(NamespacedKey modifier) {
		this.modifier = modifier;
	}

	/** (Looked up when used, not held in a constant: the attribute constants need a running server's registry, and the class is loaded by tests.) */
	private static List<Attribute> heldAttributes() {
		return List.of(Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH, Attribute.FLYING_SPEED);
	}

	/**
	 * How a hold would stop this creature: a player is always a walker (the speed and jump attributes; a teleport is cancelled).
	 * Decided by what it is, not by its attributes (every living entity has them, an armour stand too): a mob with its AI on by its kind,
	 * anything else not at all ({@link WhipMoves.HoldKind#NONE}).
	 */
	public static WhipMoves.HoldKind kindOf(LivingEntity t) {
		if (t instanceof Player) return WhipMoves.HoldKind.WALKER;
		return WhipMoves.holdKind(t.getType().getKey().toString(), t instanceof Mob, t instanceof Mob m && m.hasAI(), t.hasGravity());
	}

	/** A creature no tendril holds: a boss, a creature of a never-held kind, another player's tamed pet. (Whether the owner may aim at it at all is TendrilTargets'.) */
	public static boolean neverHeld(Player owner, LivingEntity t) {
		if (t instanceof Boss || NEVER_HELD.contains(t.getType().getKey().toString())) return true;
		return t instanceof Tameable pet && pet.isTamed() && !owner.getUniqueId().equals(pet.getOwnerUniqueId());
	}

	/** Hold {@code t} for {@code owner} until {@code until} (a server tick). Its AI, and its target, go on. */
	public void hold(LivingEntity t, UUID owner, int until) {
		WhipMoves.HoldKind kind = kindOf(t);
		for (Attribute a : heldAttributes()) {
			AttributeInstance inst = t.getAttribute(a);
			if (inst == null || inst.getModifier(modifier) != null) continue;
			inst.addTransientModifier(new AttributeModifier(modifier, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
		}
		held.put(t.getUniqueId(), new Held(owner, until, kind));
	}

	/** The creature is held now (by anyone through this module). */
	public boolean isHeld(UUID id, int now) {
		Held h = held.get(id);
		return h != null && h.until() > now;
	}

	/** Who holds the creature and until when, null when nobody (a hold that has run out but not yet been swept counts as none). */
	public @Nullable Held get(UUID id, int now) {
		Held h = held.get(id);
		return h != null && h.until() > now ? h : null;
	}

	/** Let a creature go now: its modifiers off and the hold forgotten. */
	public void release(LivingEntity t) {
		held.remove(t.getUniqueId());
		strip(t);
	}

	private void strip(LivingEntity t) {
		for (Attribute a : heldAttributes()) {
			AttributeInstance inst = t.getAttribute(a);
			if (inst != null && inst.getModifier(modifier) != null) inst.removeModifier(modifier);
		}
	}

	/** Whether any creature is held at all (a tick with nothing held has nothing to do). */
	public boolean isEmpty() {
		return held.isEmpty();
	}

	/**
	 * One tick of every hold: a creature that is gone (dead, unloaded: it took its transient modifiers with it) or whose time is up
	 * is let go, and every other is kept where it stands and handed to {@code each} (for what the module shows of a hold).
	 * {@code ended} hears each release.
	 */
	public void tick(Server server, int now, Ended ended, Consumer<LivingEntity> each) {
		for (Iterator<Map.Entry<UUID, Held>> it = held.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Held> e = it.next();
			Entity found = server.getEntity(e.getKey());
			if (!(found instanceof LivingEntity t) || !t.isValid() || t.isDead()) {
				it.remove();
				ended.end(e.getKey(), e.getValue(), false);
				continue;
			}
			if (now >= e.getValue().until()) {
				strip(t);
				it.remove();
				ended.end(e.getKey(), e.getValue(), true);
				continue;
			}
			if (!(t instanceof Player)) {
				// held in place: a walker's walk is gone with its speed and what is left is a shove, which is taken back; one that
				// flies, floats or hops moves without those attributes, so its motion is stopped on every axis it has
				org.bukkit.util.Vector v = t.getVelocity();
				double[] w = WhipMoves.heldVelocity(e.getValue().kind(), v.getX(), v.getY(), v.getZ(), t.isInWater() || t.isInLava());
				if (Math.abs(w[0] - v.getX()) > 1e-3 || Math.abs(w[1] - v.getY()) > 1e-3 || Math.abs(w[2] - v.getZ()) > 1e-3) {
					t.setVelocity(new org.bukkit.util.Vector(w[0], w[1], w[2]));
				}
			}
			each.accept(t);
		}
	}

	/** Let every creature go (a reload, a mode switch, disabling): the modifiers are never saved, but nothing may stay frozen until its time is up. */
	public void releaseAll(Server server) {
		for (UUID id : held.keySet()) {
			if (server.getEntity(id) instanceof LivingEntity t) strip(t);
		}
		held.clear();
	}
}
