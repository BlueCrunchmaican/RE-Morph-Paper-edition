package dev.morphreborn.paper.arm.rules;

import java.util.ArrayList;
import java.util.List;

/**
 * What the pickaxe arm tells its owner when a Vein Rip ends (2026-11-16: the rip used to end in silence, so a partial
 * rip or an empty creative one looked like a bug). One line for the action bar. Pure, so the wording is unit tested.
 */
public final class VeinSummary {
	private VeinSummary() {}

	/**
	 * @param name the special's name ("Vein Rip")
	 * @param group the ore group ("iron", "nether_quartz")
	 * @param planned the ores in the plan
	 * @param broken the ores torn out
	 * @param outOfReach the ores skipped because the owner had moved too far from them by their turn
	 * @param otherSkipped the ores skipped for any other reason (changed since the plan, a plugin refused them)
	 * @param finished the rip ran to its end; false when it was cut short (a reshape, a retract, a world change)
	 * @param creative the owner is in creative mode, where a break has no drops and no experience (vanilla)
	 */
	public static String of(String name, String group, int planned, int broken, int outOfReach, int otherSkipped, boolean finished, boolean creative) {
		String what = group.replace('_', ' ');
		StringBuilder out = new StringBuilder(name);
		if (!finished) {
			out.append(" cut short: ").append(broken).append(" of ").append(planned).append(planned == 1 ? " ore" : " ores").append(" of ").append(what)
					.append(" torn out");
		} else if (broken == 0) {
			out.append(": none of the ").append(planned).append(planned == 1 ? " ore" : " ores").append(" of ").append(what).append(" came out");
		} else {
			out.append(": tore out ").append(broken).append(broken == 1 ? " ore" : " ores").append(" of ").append(what);
		}
		List<String> left = new ArrayList<>();
		if (outOfReach > 0) left.add(outOfReach + " out of reach");
		if (otherSkipped > 0) left.add(otherSkipped + " left in the rock");
		if (finished && !left.isEmpty()) out.append(", ").append(String.join(", ", left));
		if (broken > 0 && creative) out.append(" (creative: ores don't drop)");
		return out.toString();
	}
}
