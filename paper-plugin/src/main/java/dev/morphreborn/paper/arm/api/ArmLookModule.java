package dev.morphreborn.paper.arm.api;

import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * How players without the client mod see an arm (phase 2): the living arm model for those with RE:Morph's resource
 * pack, through a rewrite of the equipment packets. Loaded by class name (ArmModules.LOOK); it needs PacketEvents, and
 * without it (or with the module missing) everyone without the mod sees the plain vanilla tool of the arm's tier.
 */
public interface ArmLookModule {
	void init(ArmContext ctx);

	/** Whether the owner's own stack should carry the pack's item model rather than the vanilla tool's. */
	boolean usePackModel(Player owner);

	/** The owner's arm changed ({@code view} null: gone); {@code formingStep}: one of the forming animation's steps. */
	void onArmChanged(Player owner, @Nullable ArmView view, boolean formingStep);

	/**
	 * The owner's main hand must be sent again to EVERY player tracking them, through the rewriter, because what other players
	 * should see in it changed without the slot doing so (2026-11-16: the arm tucks while an inventory is open): the
	 * tucked flag flipped, a tucked arm re-tiered, or a tucked arm ended (the slot held the real item all along, so vanilla sends
	 * nothing). Called after the views are updated or removed. The default does nothing.
	 */
	default void onHandChanged(Player owner) {}

	/**
	 * {@code viewer} started tracking {@code owner}, whose arm is tucked: their hand is sent to that viewer again, since the
	 * vanilla pairing omits an empty slot and so sends nothing the rewriter could turn into the arm. The default does nothing.
	 */
	default void onTracked(Player viewer, Player owner) {}

	/** Why this player can't form this form, or null (a Bedrock player can't form the bow). */
	@Nullable String cannotForm(Player p, String form);

	void shutdown();
}
