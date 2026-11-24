package dev.morphreborn.paper.morph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.nbt.Snbt;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** One overlay string, several writers: each only touches its own keys (B1-04). */
class OverlaysTest {
	private static final Set<String> ROLL = Set.of("state");
	private static final Set<String> CREEPER = Set.of("powered", "ignited");
	private static final String CARRIED = "{carriedBlockState:{id:\"minecraft:stone\"}}";

	private static Map<String, Object> tags(String snbt) {
		return Snbt.parseCompound(snbt);
	}

	@Test
	void aRollKeepsTheCarriedBlock() {
		String rolled = Overlays.merge(CARRIED, ROLL, "{state:\"scared\"}");
		assertTrue(tags(rolled).containsKey("carriedBlockState"), rolled);
		assertEquals("scared", tags(rolled).get("state"));
		// and uncurling takes only the roll out
		String after = Overlays.merge(rolled, ROLL, "");
		assertEquals(tags(CARRIED), tags(after));
	}

	@Test
	void aChargeSurvivesARoll() {
		String charged = Overlays.merge("", CREEPER, "{powered:1b}");
		String rolled = Overlays.merge(charged, ROLL, "{state:\"scared\"}");
		assertTrue(tags(rolled).containsKey("powered"), rolled);
		assertTrue(tags(Overlays.merge(rolled, ROLL, "")).containsKey("powered"));
	}

	@Test
	void aWriterReplacesItsOwnKeysOnly() {
		String primed = Overlays.merge("{powered:1b}", CREEPER, "{powered:1b,ignited:1b}");
		assertTrue(tags(primed).containsKey("ignited"));
		String calm = Overlays.merge(primed, CREEPER, "");
		assertEquals("", calm);
	}

	@Test
	void aWriterCannotSmuggleInAnotherWritersKeys() {
		assertEquals("", Overlays.merge("", ROLL, "{powered:1b}"));
	}

	@Test
	void unreadableOverlayIsDropped() {
		assertEquals("{state:\"scared\"}", Overlays.merge("{oops", ROLL, "{state:\"scared\"}"));
	}
}
