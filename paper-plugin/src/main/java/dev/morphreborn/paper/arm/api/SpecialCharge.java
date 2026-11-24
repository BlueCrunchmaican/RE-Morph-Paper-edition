package dev.morphreborn.paper.arm.api;

/**
 * Pays for a special once it has found its targets: all or nothing through the gameplay mode (free in Classic and
 * creative). On success it starts the cooldown (times the mode's cooldown factor) and shows it on the slot; on failure
 * it shows the player why ("Not enough biomass for Draining Cut (needs 15)") and the special must not go on.
 */
@FunctionalInterface
public interface SpecialCharge {
	boolean pay(long costMilli, int cooldownTicks);
}
