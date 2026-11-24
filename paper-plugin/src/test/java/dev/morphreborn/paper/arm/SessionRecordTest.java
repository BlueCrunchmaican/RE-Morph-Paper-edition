package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.SessionRecord;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The saved session (the player's PDC arm_session) survives a round trip, and a damaged one is never half-read. */
class SessionRecordTest {
	private static final SessionRecord SAMPLE = new SessionRecord(1, "katana", 2, 3, 0x1234_5678_9ABC_DEF0L, new byte[] {1, 2, 3, -4}, 1_700_000_000_000L);

	@Test
	void roundTrip() {
		SessionRecord back = SessionRecord.fromMap(SAMPLE.toMap());
		assertNotNull(back);
		assertEquals(SAMPLE, back);
		assertArrayEquals(SAMPLE.stash(), back.stash());
		assertEquals(0x1234_5678_9ABC_DEF0L, back.nonce());
	}

	@Test
	void writesVersionOne() {
		assertEquals(1, SAMPLE.toMap().get("v"));
		assertEquals(1, SessionRecord.VERSION);
	}

	@Test
	void emptyHandIsAnEmptyStash() {
		SessionRecord empty = new SessionRecord(1, "pickaxe", 1, 0, 7, new byte[0], 0);
		assertTrue(empty.stashEmpty());
		SessionRecord back = SessionRecord.fromMap(empty.toMap());
		assertNotNull(back);
		assertTrue(back.stashEmpty());
	}

	@Test
	void missingFieldRejected() {
		for (String field : new String[] {"v", "form", "level", "slot", "nonce", "stash", "at"}) {
			Map<String, Object> m = new HashMap<>(SAMPLE.toMap());
			m.remove(field);
			assertNull(SessionRecord.fromMap(m), "without " + field);
		}
	}

	@Test
	void wrongTypesAndVersionsRejected() {
		Map<String, Object> m = new HashMap<>(SAMPLE.toMap());
		m.put("stash", "not bytes");
		assertNull(SessionRecord.fromMap(m));
		m = new HashMap<>(SAMPLE.toMap());
		m.put("v", 0);
		assertNull(SessionRecord.fromMap(m));
		m = new HashMap<>(SAMPLE.toMap());
		m.put("form", "");
		assertNull(SessionRecord.fromMap(m));
		assertNull(SessionRecord.fromMap(null));
	}

	@Test
	void newerVersionReadsItsVersionOneFields() {
		Map<String, Object> m = new HashMap<>(SAMPLE.toMap());
		m.put("v", 2);
		m.put("something-new", 5);
		SessionRecord back = SessionRecord.fromMap(m);
		assertNotNull(back);
		assertEquals("katana", back.form());
	}

	@Test
	void anyBoxedNumberIsAccepted() {
		Map<String, Object> m = new HashMap<>(SAMPLE.toMap());
		m.put("level", 2L);
		m.put("at", 5);
		SessionRecord back = SessionRecord.fromMap(m);
		assertNotNull(back);
		assertEquals(2, back.level());
		assertEquals(5L, back.at());
	}
}
