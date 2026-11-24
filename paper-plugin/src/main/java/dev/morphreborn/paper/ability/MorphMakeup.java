package dev.morphreborn.paper.ability;

import dev.morphreborn.paper.data.MorphRef;
import dev.morphreborn.paper.disguise.GiantPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Which of two things hides a morph from the mobs, decided from what the body is made of (2026-11-17, on B2-02 and
 * B2-09: "camouflage is for mobs and borrowed scent is for the player"). Pure (it needs no running server, only the API's interfaces), so it is unit tested.
 * <ul>
 *   <li>{@link Kind#CAMOUFLAGE}: made only of creatures (one, or one lent to another). The free Family-B trait does it, and any
 *       of the creatures counts, so a zombie lent to a cow body still hides you from zombies.</li>
 *   <li>{@link Kind#SCENT}: made of you and a creature (your own body with a creature lent to it, or a creature with your own
 *       head). The billed Borrowed Scent passive does it, for the creature's kind only; Camouflage never applies. A creature that
 *       never targets players anyway (a cow) leaves the passive idle: see {@link #scentHasWork}.</li>
 *   <li>{@link Kind#NONE}: your own body with nothing borrowed, or no morph. Nothing is borrowed, so nothing acts and nothing is billed.</li>
 * </ul>
 * Any player-type form counts as "you" (your own body, or another player's skin worn as a body): a human frame with a creature on it.
 * The giant drawn as a player ({@link GiantPlayer}) is the exception: it is the giant's body, so Camouflage's.
 *
 * @param kind      what does the hiding
 * @param creatures the creature kinds in the body, the main form's first, no duplicates ("minecraft:zombie"); empty for NONE
 */
public record MorphMakeup(Kind kind, List<String> creatures) {
	public enum Kind { NONE, CAMOUFLAGE, SCENT }

	public static final MorphMakeup NONE = new MorphMakeup(Kind.NONE, List.of());

	/** The makeup of a body: {@code main} is the form it is (null: own body, no morph), {@code lent} the form it borrows (null: none). */
	public static MorphMakeup of(@Nullable MorphRef main, @Nullable MorphRef lent) {
		if (main == null) return NONE;
		// A giant wearing a player form is drawn as that player, sized up (GiantPlayer), but its body is still the giant's: no you
		// in it to borrow a scent for (no giant mob exists to be spared), so it stays Camouflage's as it was (fix round, 2026-11-17)
		if (GiantPlayer.applies(main, lent)) return new MorphMakeup(Kind.CAMOUFLAGE, List.of(main.entityType()));
		List<String> creatures = new ArrayList<>(2);
		boolean you = false;
		you |= add(creatures, main);
		you |= add(creatures, lent);
		if (creatures.isEmpty()) return NONE; // a body with no creature in it has nothing to hide behind
		return new MorphMakeup(you ? Kind.SCENT : Kind.CAMOUFLAGE, List.copyOf(creatures));
	}

	/** Adds {@code part}'s creature kind to {@code creatures} (once); true when {@code part} is a player-type form, that is you. */
	private static boolean add(List<String> creatures, @Nullable MorphRef part) {
		if (part == null) return false;
		if (part.isPlayer()) return true;
		if (!creatures.contains(part.entityType())) creatures.add(part.entityType());
		return false;
	}

	/**
	 * True when Camouflage (the free trait) is what hides this body. A body with you in it is Borrowed Scent's, but only
	 * Survival has the passive: in Classic there is no biomass to bill, so a creature with your own head (or your own body with
	 * a creature) falls back to Camouflage, as it was before the split, rather than going unprotected (fix round, 2026-11-17).
	 */
	public boolean camouflages(boolean survival) {
		return kind == Kind.CAMOUFLAGE || (kind == Kind.SCENT && !survival);
	}

	/** True when the creature kind {@code mobType} is among the creatures of a body Borrowed Scent covers: those mobs leave you alone while it is paid. */
	public boolean scentSpares(String mobType) {
		return kind == Kind.SCENT && creatures.contains(mobType);
	}

	/**
	 * Neutral kinds that are not {@code Enemy} in the server's types but do come for a player: they go for you when provoked
	 * (wolf, bee, iron golem, polar bear, panda, fox, llama, goat, dolphin). The ones that are an Enemy (piglin, enderman, spider,
	 * zombified piglin...) are caught by that test instead. The rabbit is here for its killer bunny variant (it adds a player
	 * target goal, Rabbit.setVariant); the decision is by kind and cannot tell the variant, so an ordinary rabbit is billed too: the
	 * cost of not leaving a killer bunny's body unprotected (fix round, 2026-11-17).
	 */
	private static final Set<String> PROVOKABLE = Set.of("minecraft:wolf", "minecraft:bee", "minecraft:iron_golem", "minecraft:polar_bear",
			"minecraft:panda", "minecraft:fox", "minecraft:llama", "minecraft:trader_llama", "minecraft:goat", "minecraft:dolphin", "minecraft:rabbit");

	/**
	 * Whether a creature of {@code type} can ever target a player: a hostile kind ({@code enemy}, from the server's own types), one
	 * the targeting config calls always hostile, or a neutral one that fights back when provoked. A cow, pig or sheep never does.
	 */
	public static boolean canTargetPlayers(String type, boolean enemy, boolean alwaysHostile) {
		return enemy || alwaysHostile || PROVOKABLE.contains(type);
	}

	/**
	 * {@link #canTargetPlayers(String, boolean, boolean)} from the server's class for the kind ({@code entityClass}). A kind the
	 * server does not resolve (null) counts as able to: Scent then bills and spares as it did before it could idle, rather than
	 * silently doing neither for a hostile creature (fix round, 2026-11-17).
	 */
	public static boolean canTargetPlayers(String type, @Nullable Class<?> entityClass, boolean alwaysHostile) {
		if (entityClass == null) return true;
		return canTargetPlayers(type, org.bukkit.entity.Enemy.class.isAssignableFrom(entityClass), alwaysHostile);
	}

	/**
	 * True when Borrowed Scent has something to do for this body: it is Scent's, and the creature in it is a kind that would
	 * target the player at all. A cow's scent spares nothing, so it is idle and bills nothing (2026-11-17: "the bar
	 * should not go down for scent that does nothing").
	 *
	 * @param targetsPlayers whether a creature kind can target players, as {@link #canTargetPlayers} answers it for the server
	 */
	public boolean scentHasWork(Predicate<String> targetsPlayers) {
		if (kind != Kind.SCENT) return false;
		for (String creature : creatures) if (targetsPlayers.test(creature)) return true;
		return false;
	}
}
