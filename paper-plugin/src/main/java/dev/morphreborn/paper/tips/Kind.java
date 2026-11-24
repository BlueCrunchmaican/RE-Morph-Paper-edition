package dev.morphreborn.paper.tips;

/**
 * What a piece of description is, for the Tooltips setting. ALWAYS stays however the setting is: names, levels, state, biomass costs and
 * rates (per use, per minute, per hit, capacity, prices). TIP is explanation: what a thing does in detail, how to use it, which key or
 * button. Decided 2026-11-17: costs and rates are never tooltips, even when they sit next to an explanation.
 */
public enum Kind {
	ALWAYS,
	TIP
}
