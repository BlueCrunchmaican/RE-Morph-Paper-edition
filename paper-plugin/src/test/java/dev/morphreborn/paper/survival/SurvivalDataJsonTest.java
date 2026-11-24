package dev.morphreborn.paper.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.morphreborn.paper.data.SurvivalData;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The passives' on/off state is SurvivalData.passivesOff, saved with the player file by DataStore's Gson (2026-11-16: toggles persist). */
class SurvivalDataJsonTest {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create(); // as DataStore builds it

	@Test
	void theSwitchedOffPassivesSurviveASaveAndALoad() {
		SurvivalData d = new SurvivalData();
		d.biomass = 12.5;
		d.upgrades.put(PassiveSettings.SUSTENANCE, 1);
		d.passivesOff.add(PassiveSettings.GILLS);
		d.passivesOff.add(PassiveSettings.NIGHT_EYES);
		SurvivalData back = GSON.fromJson(GSON.toJson(d), SurvivalData.class);
		assertEquals(Set.of(PassiveSettings.GILLS, PassiveSettings.NIGHT_EYES), back.passivesOff);
		assertEquals(1, back.upgrades.get(PassiveSettings.SUSTENANCE));
		assertEquals(12.5, back.biomass, 0);
	}

	@Test
	void anOldFileWithoutTheFieldLoadsAsAllOn() {
		SurvivalData back = GSON.fromJson("{\"unlocked\": true, \"biomass\": 3.0, \"upgrades\": {\"passive_gills\": 1}}", SurvivalData.class);
		assertNotNull(back.passivesOff, "the field's initialiser still runs, so there is a set to add to");
		assertTrue(back.passivesOff.isEmpty(), "a bought passive not listed is on");
	}

	@Test
	void anEmptyListLoadsAsAMutableSet() {
		SurvivalData back = GSON.fromJson("{\"passivesOff\": []}", SurvivalData.class);
		back.passivesOff.add(PassiveSettings.ANTITOXIN); // Passives.set adds to it
		assertEquals(Set.of(PassiveSettings.ANTITOXIN), back.passivesOff);
	}
}
