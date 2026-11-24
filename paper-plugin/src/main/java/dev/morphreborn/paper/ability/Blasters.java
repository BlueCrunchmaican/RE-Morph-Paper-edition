package dev.morphreborn.paper.ability;

import dev.morphreborn.paper.data.MorphRef;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/** Which of a multimorph's forms does the blowing up (a creeper, a sulfur cube holding TNT); Bukkit-free so it can be tested. */
final class Blasters {
	private Blasters() {}

	/**
	 * The form that can explode right now, the main one first. A main form that cannot (a sulfur cube with no explosive
	 * block in it) does not shadow a borrowed one that can: the borrowed creeper still blows up.
	 */
	static @Nullable MorphRef pick(@Nullable MorphRef main, @Nullable MorphRef lent, Predicate<MorphRef> canExplode) {
		if (main != null && canExplode.test(main)) return main;
		return lent != null && canExplode.test(lent) ? lent : null;
	}

	/** {@code form} is still one of the two the player is made of (it may be the borrowed one while the body is another). */
	static boolean isOneOf(@Nullable MorphRef form, @Nullable MorphRef main, @Nullable MorphRef lent) {
		return form != null && (form.equals(main) || form.equals(lent));
	}
}
