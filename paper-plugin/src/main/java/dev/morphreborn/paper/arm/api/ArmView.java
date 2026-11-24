package dev.morphreborn.paper.arm.api;

/**
 * An armed player's arm as the packet rewriter reads it, off the main thread: the form, its tier, its state
 * (ArmProtocol.STATE_*), the tick that state began, the state's length (0 for FORMED), and whether it is tucked away
 * while its owner has an inventory open (the slot holds the real item; everyone else is still shown the arm).
 * {@code maxDamage} and {@code damage} are the arm item's biomass bar, carried only while tucked (the stub shown for the
 * tucked arm has no item to read it from, and a modded viewer draws the bar of what it is shown); 0 and 0 otherwise.
 * {@code charged} is the crossbow's bolt, carried only while tucked for the same reason (2026-11-16, review of the pack's use states): the
 * stub has no charged projectile of its own, and the pack's crossbow shows its empty groove without one; false otherwise and for every other form.
 * Immutable, replaced whole.
 */
public record ArmView(String form, int tier, int state, int stateTick, int duration, boolean tucked, int maxDamage, int damage, boolean charged) {
	public ArmView(String form, int tier, int state, int stateTick, int duration, boolean tucked, int maxDamage, int damage) {
		this(form, tier, state, stateTick, duration, tucked, maxDamage, damage, false);
	}

	public ArmView(String form, int tier, int state, int stateTick, int duration, boolean tucked) {
		this(form, tier, state, stateTick, duration, tucked, 0, 0, false);
	}

	public ArmView(String form, int tier, int state, int stateTick, int duration) {
		this(form, tier, state, stateTick, duration, false, 0, 0, false);
	}
}
