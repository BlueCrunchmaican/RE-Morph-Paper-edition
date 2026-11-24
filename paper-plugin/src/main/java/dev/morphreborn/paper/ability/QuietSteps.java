package dev.morphreborn.paper.ability;

import dev.morphreborn.paper.variant.VariantRules;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

/**
 * Quiet steps: soft-footed forms (fox, rabbit, cat...) raise no walking, swimming or soft-landing vibrations, so sculk sensors and
 * wardens do not hear them move, as if they were always sneaking (including its one gap: a sensor or shrieker stood on directly still goes
 * off, because its block acts on whoever stands on it without a vibration). Heavy or noisy forms (creeper, skeleton...) are heard as vanilla has it.
 * It is a morph trait (abilities.yml {@code passive.quiet-steps}), on from the start in both modes: no unlock, no biomass, no toggle.
 * <p>
 * Pure on purpose (it needs no running server), so the rules are unit tested; {@link AbilityListener} feeds it the game events and
 * {@link AbilityManager#quietSteps} supplies the form. Only the body's own movement is ever silenced: a hit, a meal, a door, a
 * projectile and the elytra stay as loud as ever, and the touch and sniff senses of a warden are not vibrations at all.
 */
public final class QuietSteps {
	private QuietSteps() {}

	/** The three movement vibrations a quiet form can silence (vanilla's STEP, SWIM and HIT_GROUND). */
	public enum Move { STEP, SWIM, LANDING }

	/**
	 * What abilities.yml {@code passive.quiet-steps} holds.
	 *
	 * @param types       the quiet forms ("minecraft:fox"), a body made of one of these is quiet
	 * @param softLanding a landing from this many blocks or less is silent too (3 is the fall that never hurts); 0: every landing is heard
	 * @param swimming    moving through water is silent too
	 */
	public record Settings(Set<String> types, double softLanding, boolean swimming) {
		public static final Settings NONE = new Settings(Set.of(), 0, false);

		/**
		 * Reads abilities.yml {@code passive.quiet-steps}; null (no section) means nothing is quiet. A negative landing height counts as 0,
		 * and a form name may leave out "minecraft:" like every other list in the file.
		 */
		public static Settings read(@Nullable ConfigurationSection section) {
			if (section == null) return NONE;
			Set<String> types = new HashSet<>();
			for (String name : section.getStringList("types")) types.add(VariantRules.normaliseType(name));
			return new Settings(Set.copyOf(types), Math.max(0, section.getDouble("soft-landing", 3)), section.getBoolean("swimming", true));
		}

		/**
		 * Whether this game event is silenced. {@code move} is null for every other game event (the vast majority: blocks, mobs,
		 * hits...) and {@code main} null while there is no body to judge (own body, no morph, mid-transformation), so both answer false
		 * before anything is looked up. Sneaking is not asked: vanilla already drops a sneaker's movement, and a quiet form
		 * that sneaks is silent either way.
		 */
		public boolean silences(@Nullable Move move, @Nullable String main, float fallDistance) {
			if (move == null || main == null) return false;
			return QuietSteps.silences(move, fallDistance, softLanding, swimming, quietBody(main, null, types));
		}
	}

	/**
	 * Whether a body is a quiet one. Steps come from feet, and a borrowed form lends a head, ears, a tail, wings and the like, never legs, so
	 * the main body decides: a fox with a creeper head is quiet, a creeper with a fox head is heard, and your own body (a player-type main form)
	 * with a fox head is heard too. {@code lent} is deliberately not looked at; it stays in the signature so every caller says what
	 * the body is made of and the rule can change in one place.
	 */
	public static boolean quietBody(@Nullable String main, @Nullable String lent, Set<String> quiet) {
		return main != null && quiet.contains(main);
	}

	/**
	 * Whether one movement vibration is dropped. A landing is silent from {@code softLanding} blocks and below, measured the way vanilla
	 * does it: the game posts HIT_GROUND before it resets the fall distance, so {@code fallDistance} is the real fall.
	 */
	public static boolean silences(Move move, float fallDistance, double softLanding, boolean swimming, boolean quietBody) {
		if (!quietBody) return false;
		return switch (move) {
			case STEP -> true;
			case SWIM -> swimming;
			case LANDING -> softLanding > 0 && fallDistance <= softLanding;
		};
	}
}
