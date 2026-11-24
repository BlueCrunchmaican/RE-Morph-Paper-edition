package dev.morphreborn.client.tips;

/**
 * What a piece of description is, for the Tooltips setting. ALWAYS stays however the setting is: names, levels, state, biomass costs and
 * rates (per use, per minute, per hit, capacity, prices). TIP is explanation: what a thing does in detail, how to use it, which key or
 * button. Decided 2026-11-17: costs and rates are never tooltips, even when they sit next to an explanation. The plugin has the same two
 * kinds for the text it builds (paper.tips.Kind).
 */
public enum Kind {
	ALWAYS,
	TIP
}
