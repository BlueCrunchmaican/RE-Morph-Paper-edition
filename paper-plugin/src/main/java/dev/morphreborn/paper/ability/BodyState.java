package dev.morphreborn.paper.ability;

import java.util.List;

/**
 * Which per-body ability state survives a change that keeps the same body (another borrowed form, a reload, a mode
 * switch): the state belongs to the form that made it, so it stays while that form is still part of the player.
 * Bukkit-free so it can be tested.
 */
final class BodyState {
	private BodyState() {}

	/**
	 * Two forms giving the same constant effect (a camel's Jump Boost and a magma cube's): the stronger one counts, not the
	 * one that came first. Keeps the order of what is left.
	 */
	static <E, K> List<E> strongest(List<E> effects, java.util.function.Function<E, K> kind, java.util.function.ToIntFunction<E> amplifier) {
		java.util.Map<K, E> best = new java.util.LinkedHashMap<>();
		for (E e : effects) best.merge(kind.apply(e), e, (a, b) -> amplifier.applyAsInt(b) > amplifier.applyAsInt(a) ? b : a);
		return new java.util.ArrayList<>(best.values());
	}

	/** A lightning charge is a creeper's, main or borrowed. */
	static boolean keepsCharge(List<String> forms) {
		return forms.contains("minecraft:creeper");
	}

	/** Perching, a stinger spent, anger: a bat's and a bee's. */
	static boolean keepsPerch(List<String> forms) {
		return forms.contains("minecraft:bat");
	}

	static boolean keepsSting(List<String> forms) {
		return forms.contains("minecraft:bee");
	}
}
