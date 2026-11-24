package dev.morphreborn.paper.survival;

import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * The rule behind {@link SurvivalMode#hasTrait}: a beneficial trait is on once any upgrade that lists it is bought, and a trait no upgrade
 * lists is on from the start. Pulled out so it can be tested without a player or a server.
 */
final class TraitGate {
	private TraitGate() {}

	/** {@code unlocks}: trait to the upgrades listing it; {@code level}: how far an upgrade id is bought. */
	static boolean on(Map<String, List<String>> unlocks, String trait, ToIntFunction<String> level) {
		List<String> listed = unlocks.get(trait);
		if (listed == null) return true;
		for (String id : listed) {
			if (level.applyAsInt(id) >= 1) return true;
		}
		return false;
	}
}
