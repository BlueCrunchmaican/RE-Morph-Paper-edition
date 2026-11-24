package dev.morphreborn.paper.ability;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.entity.AbstractCubeMob;
import org.bukkit.entity.MagmaCube;
import org.bukkit.entity.Slime;
import org.junit.jupiter.api.Test;

/**
 * The frog's tongue eats a small cube mob (AbilityManager "tongue"). In Paper 26.3 a magma cube is not a Slime (both are
 * AbstractCubeMob), so the check on Slime never saw one and no froglight dropped (rig L8). If the API changes back, this tells.
 */
class CubeMobTypeTest {
	@Test
	void slimeAndMagmaCubeShareTheCubeMobBase() {
		assertTrue(AbstractCubeMob.class.isAssignableFrom(Slime.class));
		assertTrue(AbstractCubeMob.class.isAssignableFrom(MagmaCube.class));
	}

	@Test
	void magmaCubeIsNotASlime() {
		assertFalse(Slime.class.isAssignableFrom(MagmaCube.class), "if a magma cube is a Slime again, the tongue's check may go back to Slime");
	}
}
