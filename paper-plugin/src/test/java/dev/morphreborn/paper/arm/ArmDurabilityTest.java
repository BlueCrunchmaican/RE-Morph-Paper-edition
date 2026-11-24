package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.ArmDurability;
import org.junit.jupiter.api.Test;

/** The durability bar as biomass: always shown, never broken. */
class ArmDurabilityTest {
	@Test
	void fullShowsOne() {
		int max = ArmDurability.maxDamage(100_000);
		assertEquals(100, max);
		assertEquals(1, ArmDurability.damage(100_000, max), "a full arm is damaged by 1 so the bar shows");
		assertEquals(1, ArmDurability.damage(250_000, max), "more than the capacity still shows the bar");
	}

	@Test
	void neverReachesTheMax() {
		for (long capacity : new long[] {100_000, 51_200_000, 1, 0, 1500}) {
			int max = ArmDurability.maxDamage(capacity);
			for (long balance : new long[] {0, 1, 999, 1000, 1001, 50_000, capacity}) {
				int d = ArmDurability.damage(balance, max);
				assertTrue(d >= 1 && d <= max - 1, "capacity " + capacity + " balance " + balance + ": " + d + " of " + max);
			}
		}
	}

	@Test
	void monotonic() {
		int max = ArmDurability.maxDamage(51_200_000);
		assertEquals(51_200, max);
		int last = Integer.MAX_VALUE;
		for (long balance = 0; balance <= 51_200_000; balance += 7_919) {
			int d = ArmDurability.damage(balance, max);
			assertTrue(d <= last, "more biomass never shows a shorter bar");
			last = d;
		}
	}

	@Test
	void wholeBiomassOnly() {
		int max = ArmDurability.maxDamage(100_000);
		assertEquals(ArmDurability.damage(42_000, max), ArmDurability.damage(42_999, max), "the bar moves with floor(biomass) only");
		assertEquals(ArmDurability.damage(42_000, max) + 1, ArmDurability.damage(41_999, max));
	}

	@Test
	void maxIsAtLeastTwo() {
		assertEquals(2, ArmDurability.maxDamage(0));
		assertEquals(2, ArmDurability.maxDamage(1));
		assertEquals(2, ArmDurability.maxDamage(2000));
		assertEquals(3, ArmDurability.maxDamage(2001), "capacity rounds up");
		assertEquals(1, ArmDurability.damage(0, 2));
	}

	@Test
	void aTuckedStubCarriesTheBarClampedLikeTheArmsOwn() {
		assertEquals(40, ArmDurability.stubDamage(100, 40));
		assertEquals(-1, ArmDurability.stubDamage(100, 0), "no bar (Classic, damage 0): the stub carries none and reads full");
		assertEquals(-1, ArmDurability.stubDamage(2, 0), "a Classic arm (max 2, damage 0)");
		assertEquals(99, ArmDurability.stubDamage(100, 100), "never broken");
		assertEquals(-1, ArmDurability.stubDamage(0, 0), "no bar known: the stub carries none");
		assertEquals(-1, ArmDurability.stubDamage(ArmDurability.MIN_MAX_DAMAGE - 1, 1));
		assertEquals(1, ArmDurability.stubDamage(ArmDurability.MIN_MAX_DAMAGE, 7));
	}
}
