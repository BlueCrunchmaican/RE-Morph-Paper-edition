package dev.morphreborn.paper.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.survival.SurvivalConfig.Upgrade;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Costs and rates are never tooltips (2026-11-17): an upgrade's figure that is not part of its explanation is the level's own line (Now / Next),
 * which stays with Tooltips off, and the advice on the end of a kill's message goes with them.
 */
class TooltipFiguresTest {
	private static Upgrade upgrade(int maxLevel, String format) {
		return new Upgrade("x", "X", "does a thing", Material.PAPER, "", "root", Map.of(), 0, maxLevel, Curve.of(1), Curve.of(10), format, List.of());
	}

	@Test
	void aSingleLevelUpgradeWithAFormatOfItsOwnShowsItsFigure() {
		assertFalse(upgrade(1, "{value}").showsValue(), "an unlock has no figure to show");
		assertTrue(upgrade(1, "a combination costs both forms and a quarter more").showsValue());
		assertTrue(upgrade(3, "{value}").showsValue(), "several levels always did");
	}

	private static String survivalYml() throws IOException {
		try (InputStream in = TooltipFiguresTest.class.getResourceAsStream("/survival.yml")) {
			assertNotNull(in, "survival.yml is bundled");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** The text of one upgrade's block in survival.yml: from its id to the next upgrade's. */
	private static String block(String yml, String id) {
		int from = yml.indexOf("\n  " + id + ":\n");
		assertTrue(from >= 0, id + " is in survival.yml");
		java.util.regex.Matcher next = java.util.regex.Pattern.compile("\n  [a-z_]+:\n").matcher(yml);
		return next.find(from + 1 + id.length() + 3) ? yml.substring(from, next.start()) : yml.substring(from);
	}

	@Test
	void theMultimorphSurchargeAndTheFrenzyHitRateAreNotInTheDescriptions() throws IOException {
		String yml = survivalYml();
		String multimorph = block(yml, "multimorph");
		String description = multimorph.lines().filter(l -> l.startsWith("    description:")).findFirst().orElseThrow();
		assertFalse(description.contains("quarter"), "the surcharge is a cost, so it is not in the explanation: " + description);
		assertTrue(multimorph.contains("format: \"a combination costs both forms and a quarter more\""), "it is the level's own line instead");
		String rage = block(yml, "rage").lines().filter(l -> l.startsWith("    description:")).findFirst().orElseThrow();
		assertFalse(rage.contains("half second"), "the rate is in the level's numbers (Rage.describe): " + rage);
	}

	@Test
	void theAdviceOnAKillsMessageIsATip() {
		dev.morphreborn.paper.morph.MorphManager.Acquisition got = new dev.morphreborn.paper.morph.MorphManager.Acquisition(null,
				Component.text("No room for Cow: morphs full (4/4)"), Component.text("Upgrade Morph Capacity or delete one."));
		PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
		assertEquals("No room for Cow: morphs full (4/4). Upgrade Morph Capacity or delete one.", plain.serialize(got.withHint(true)));
		assertEquals("No room for Cow: morphs full (4/4)", plain.serialize(got.withHint(false)), "the state stays, the advice goes");
	}
}
