package dev.morphreborn.paper.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A bare type name in a command means the first grown-up variant, not a baby that happens to be stored first (B3-12). */
class ByTypeNameTest {
	private static PlayerMorphData.StoredVariant variant(String id, String snbt) {
		PlayerMorphData.StoredVariant v = new PlayerMorphData.StoredVariant();
		v.id = id;
		v.snbt = snbt;
		return v;
	}

	@Test
	void aBabyStoredFirstIsSkipped() {
		var baby = variant("a", "{Age:-24000,AgeLocked:1b}");
		var adult = variant("b", "{}");
		assertSame(adult, PlayerMorphData.byTypeName(List.of(baby, adult)));
	}

	@Test
	void aBabyZombieIsRecognisedByIsBaby() {
		var baby = variant("a", "{IsBaby:1b}");
		var adult = variant("b", "");
		assertSame(adult, PlayerMorphData.byTypeName(List.of(baby, adult)));
	}

	@Test
	void onlyBabiesGivesTheFirst() {
		var baby = variant("a", "{IsBaby:1b}");
		assertSame(baby, PlayerMorphData.byTypeName(List.of(baby, variant("b", "{Age:-1}"))));
	}

	@Test
	void nothingStoredIsNothing() {
		assertNull(PlayerMorphData.byTypeName(List.of()));
		assertNull(PlayerMorphData.byTypeName(null));
		assertEquals("a", PlayerMorphData.byTypeName(List.of(variant("a", "{variant:\"minecraft:cold\"}"))).id);
	}
}
