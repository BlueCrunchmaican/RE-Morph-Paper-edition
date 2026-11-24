package dev.morphreborn.paper.net;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.SemVer;
import dev.morphreborn.paper.arm.ArmColours;
import dev.morphreborn.paper.arm.ArmProtocol;
import dev.morphreborn.paper.survival.PassiveProtocol;
import dev.morphreborn.paper.survival.PassiveSync;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A modified client that lies (backlog "test the untested: a lying client", 2026-11-16): hostile bytes into every decoder the server
 * reads a C_ packet with, the version string it claims, the throttles and the budget that bound a flood. The handlers that touch Bukkit are
 * reasoned about in the report; what is pure is pinned here.
 */
class LyingClientTest {
	private static final UUID A = new UUID(1, 1);
	private static final UUID B = new UUID(2, 2);

	private static byte[] bytes(int... values) {
		byte[] out = new byte[values.length];
		for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
		return out;
	}

	private static byte[] concat(byte[]... parts) {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		for (byte[] p : parts) out.writeBytes(p);
		return out.toByteArray();
	}

	// ------------------------------------------------------------------ Wire.In

	@Test
	void emptyAndTruncatedPacketsAreMalformedNotCrashes() {
		assertThrows(Wire.MalformedPacketException.class, () -> new Wire.In(new byte[0]).varInt());
		assertThrows(Wire.MalformedPacketException.class, () -> new Wire.In(bytes(0x80)).varInt()); // a continuation with nothing after
		assertThrows(Wire.MalformedPacketException.class, () -> new Wire.In(new byte[0]).bool());
		assertThrows(Wire.MalformedPacketException.class, () -> new Wire.In(bytes(1, 2, 3)).longValue());
		assertThrows(Wire.MalformedPacketException.class, () -> new Wire.In(bytes(1, 2, 3)).uuid());
	}

	@Test
	void varIntLongerThanFiveBytesIsRejected() {
		assertThrows(Wire.MalformedPacketException.class, () -> new Wire.In(bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x01)).varInt());
		assertThrows(Wire.MalformedPacketException.class, () -> new Wire.In(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)).varInt());
	}

	@Test
	void fiveByteVarIntsDecodeToTheIntTheyWrapTo() {
		// the largest accepted: it wraps to -1 (as every varInt reader does), and the readers below treat negatives as hostile
		assertEquals(-1, new Wire.In(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x0F)).varInt());
		assertEquals(Integer.MIN_VALUE, new Wire.In(bytes(0x80, 0x80, 0x80, 0x80, 0x08)).varInt());
		assertEquals(Integer.MAX_VALUE, new Wire.In(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x07)).varInt());
	}

	@Test
	void stringLengthsAreChecked() {
		assertThrows(Wire.MalformedPacketException.class, () -> new Wire.In(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x0F)).string(64), "a negative length");
		assertThrows(Wire.MalformedPacketException.class, () -> new Wire.In(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x07)).string(64), "Integer.MAX_VALUE");
		assertThrows(Wire.MalformedPacketException.class, () -> new Wire.In(bytes(5, 'a', 'b')).string(64), "longer than the packet");
		assertThrows(Wire.MalformedPacketException.class, () -> new Wire.In(bytes(65)).string(64), "over the cap, nothing after it");
		assertThrows(Wire.MalformedPacketException.class, () -> new Wire.In(concat(bytes(65), "a".repeat(65).getBytes(StandardCharsets.UTF_8))).string(64), "over the cap");
		assertEquals("", new Wire.In(bytes(0)).string(64));
		assertEquals("a".repeat(64), new Wire.In(concat(bytes(64), "a".repeat(64).getBytes(StandardCharsets.UTF_8))).string(64), "exactly the cap");
		// a cap above MAX_STRING does not raise it
		byte[] over = concat(new Wire.Out(0).varInt(Wire.MAX_STRING + 1).bytes(), new byte[Wire.MAX_STRING + 1]);
		Wire.In in = new Wire.In(over);
		in.varInt();
		assertThrows(Wire.MalformedPacketException.class, () -> in.string(Integer.MAX_VALUE));
	}

	@Test
	void invalidUtf8AndControlCharactersDecodeWithoutThrowing() {
		assertNotNull(assertDoesNotThrow(() -> new Wire.In(bytes(3, 0xFF, 0xFE, 0xC0)).string(64)));
		assertEquals("\0\n", new Wire.In(bytes(2, 0, '\n')).string(64));
	}

	@Test
	void trailingBytesAreIgnoredNotRead() {
		Wire.In in = new Wire.In(concat(new Wire.Out(Wire.C_FAVOURITE).string("x").bool(true).bytes(), bytes(9, 9, 9, 9)));
		assertEquals(Wire.C_FAVOURITE, in.varInt());
		assertEquals("x", in.string(64));
		assertTrue(in.bool());
		assertTrue(in.hasMore());
	}

	@Test
	void varIntRoundTripsEveryEdge() {
		for (int v : new int[] {0, 1, 127, 128, 16383, 16384, Integer.MAX_VALUE, -1, Integer.MIN_VALUE}) {
			Wire.In in = new Wire.In(new Wire.Out(0).varInt(v).bytes());
			assertEquals(0, in.varInt());
			assertEquals(v, in.varInt());
		}
	}

	// ------------------------------------------------------------------ C_ARM

	private static ClientChannel.ArmRequest arm(byte[] afterId) {
		return ClientChannel.ArmRequest.read(new Wire.In(afterId));
	}

	@Test
	void armRequestWithNothingAfterTheActionHasNoFormAndNoStep() {
		ClientChannel.ArmRequest r = arm(bytes(ArmProtocol.ACTION_ACCESSORY_HOLD));
		assertEquals(new ClientChannel.ArmRequest(ArmProtocol.ACTION_ACCESSORY_HOLD, "", 0), r);
		r = arm(concat(bytes(ArmProtocol.ACTION_ACCESSORY_MODE), bytes(7, 'l', 'a', 'n', 't', 'e', 'r', 'n')));
		assertEquals(0, r.step(), "a mode with no step is a no-op");
	}

	@Test
	void armRequestRejectsAnOverlongFormId() {
		byte[] form = concat(bytes(ArmProtocol.MAX_FORM_ID + 1), "a".repeat(ArmProtocol.MAX_FORM_ID + 1).getBytes(StandardCharsets.UTF_8));
		assertThrows(Wire.MalformedPacketException.class, () -> arm(concat(bytes(ArmProtocol.ACTION_FORM), form)));
		// the cap is in bytes: 17 two-byte characters are 34
		byte[] wide = "é".repeat(17).getBytes(StandardCharsets.UTF_8);
		assertThrows(Wire.MalformedPacketException.class, () -> arm(concat(bytes(ArmProtocol.ACTION_FORM, wide.length), wide)));
		byte[] ok = "a".repeat(ArmProtocol.MAX_FORM_ID).getBytes(StandardCharsets.UTF_8);
		assertEquals("a".repeat(32), arm(concat(bytes(ArmProtocol.ACTION_FORM, ok.length), ok)).form());
	}

	@Test
	void armStepIsReducedToItsSignWhateverItClaims() {
		byte[] lantern = bytes(7, 'l', 'a', 'n', 't', 'e', 'r', 'n');
		assertEquals(1, arm(concat(bytes(ArmProtocol.ACTION_ACCESSORY_MODE), lantern, bytes(1))).step());
		assertEquals(1, arm(concat(bytes(ArmProtocol.ACTION_ACCESSORY_MODE), lantern, bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x07))).step(), "Integer.MAX_VALUE");
		assertEquals(-1, arm(concat(bytes(ArmProtocol.ACTION_ACCESSORY_MODE), lantern, bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x0F))).step(), "-1");
		assertEquals(-1, arm(concat(bytes(ArmProtocol.ACTION_ACCESSORY_MODE), lantern, bytes(0x80, 0x80, 0x80, 0x80, 0x08))).step(), "Integer.MIN_VALUE");
		assertEquals(0, arm(concat(bytes(ArmProtocol.ACTION_ACCESSORY_MODE), lantern, bytes(0))).step());
		assertEquals(1, arm(concat(bytes(ArmProtocol.ACTION_ACCESSORY_HOLD), lantern, bytes(0x40))).step(), "anything above 0 is held");
	}

	@Test
	void armStepIsReadOnlyForTheActionsThatHaveOne() {
		byte[] lantern = bytes(7, 'l', 'a', 'n', 't', 'e', 'r', 'n');
		for (int action : new int[] {ArmProtocol.ACTION_FORM, ArmProtocol.ACTION_RETRACT, ArmProtocol.ACTION_LANTERN_ON, ArmProtocol.ACTION_ACCESSORY_ON,
				ArmProtocol.ACTION_ACCESSORY_OFF, ArmProtocol.ACTION_INVENTORY_OPEN, ArmProtocol.ACTION_INVENTORY_CLOSED}) {
			assertEquals(0, arm(concat(bytes(action), lantern, bytes(1))).step(), "action " + action);
		}
	}

	@Test
	void armRequestAcceptsAnyActionNumberAndLeavesTheVerdictToTheHandler() {
		// out of range, negative and huge actions decode (the handler logs "unknown action" and does nothing); only the bytes can fail
		for (byte[] action : new byte[][] {bytes(10), bytes(127), bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x0F), bytes(0x80, 0x80, 0x80, 0x80, 0x08), bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x07)}) {
			ClientChannel.ArmRequest r = arm(action);
			assertEquals("", r.form());
			assertEquals(0, r.step());
		}
	}

	// ------------------------------------------------------------------ C_PASSIVE

	@Test
	void passiveRequestHostileBytes() {
		assertThrows(Wire.MalformedPacketException.class, () -> PassiveProtocol.Request.read(new Wire.In(new byte[0])));
		assertThrows(Wire.MalformedPacketException.class, () -> PassiveProtocol.Request.read(new Wire.In(bytes(2, 'a', 'b'))), "no bool");
		assertThrows(Wire.MalformedPacketException.class, () -> PassiveProtocol.Request.read(new Wire.In(concat(bytes(65), "a".repeat(65).getBytes(StandardCharsets.UTF_8), bytes(1)))));
		PassiveProtocol.Request r = PassiveProtocol.Request.read(new Wire.In(concat(bytes(2, 'a', 'b'), bytes(0x7F))));
		assertTrue(r.on(), "any non-zero byte is on");
		assertEquals("ab", r.id());
	}

	@Test
	void passiveFloodIsAnsweredAtTheRateOfTheThrottle() {
		PassiveSync sync = new PassiveSync();
		int allowed = 0;
		for (int i = 0; i < 1000; i++) if (sync.allowToggle(A, 100)) allowed++;
		assertEquals(1, allowed, "a thousand in one tick: one is acted on");
		assertTrue(sync.allowToggle(A, 102));
		assertFalse(sync.allowToggle(A, 103));
		assertTrue(sync.allowToggle(B, 100), "the throttle is per player");
	}

	// ------------------------------------------------------------------ C_ARM_COLOUR

	@Test
	void armColourRejectsEveryOutOfRangeColour() {
		ArmColours colours = new ArmColours();
		int tick = 1000;
		for (int rgb : new int[] {-1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x1000000, 0x7F000000, 0xFF000000}) {
			assertEquals(ArmColours.Result.DROPPED, colours.request(A, rgb, 0, 100, tick += 100), "rgb " + rgb);
		}
		assertEquals(ArmProtocol.COLOUR_DEFAULT, colours.of(A), "nothing was kept");
		assertEquals(ArmColours.Result.CHANGED, colours.request(A, 0xFFFFFF, 0, 100, tick += 100));
		assertEquals(ArmColours.Result.CHANGED, colours.request(A, 0x000000, 0, 100, tick += 100));
	}

	@Test
	void armColourLookAndPulseNeverDropTheColourButAreBrought() {
		ArmColours colours = new ArmColours();
		assertEquals(ArmColours.Result.CHANGED, colours.request(A, 0x123456, Integer.MIN_VALUE, Integer.MIN_VALUE, 100));
		assertEquals(ArmProtocol.LOOK_DEFAULT, colours.lookOf(A));
		assertEquals(ArmProtocol.PULSE_SPEED_MIN, colours.pulseSpeedOf(A));
		colours.request(A, 0x123456, -5, Integer.MAX_VALUE, 200);
		assertEquals(ArmProtocol.LOOK_DEFAULT, colours.lookOf(A));
		assertEquals(ArmProtocol.PULSE_SPEED_MAX, colours.pulseSpeedOf(A));
		colours.request(A, 0x123456, 99, 0, 300);
		assertEquals(ArmProtocol.LOOK_DEFAULT, colours.lookOf(A), "a look this build does not know");
		assertEquals(ArmProtocol.PULSE_SPEED_MIN, colours.pulseSpeedOf(A), "0 percent is the slowest, never a stopped pulse");
	}

	@Test
	void armColourFloodHoldsOnlyTheLatestAndTakesOne() {
		ArmColours colours = new ArmColours();
		assertEquals(ArmColours.Result.CHANGED, colours.request(A, 1, 0, 100, 500));
		int changed = 0;
		for (int i = 0; i < 500; i++) if (colours.request(A, 100 + i, 0, 100, 500 + i % 9) == ArmColours.Result.CHANGED) changed++;
		assertEquals(0, changed, "nothing more is taken inside the window, however many arrive");
		assertEquals(1, colours.of(A), "the kept colour did not move");
		int[] held = colours.takePending(A);
		assertNotNull(held);
		assertEquals(599, held[0], "the latest request is the one held");
		assertNull(colours.takePending(A), "and only one");
	}

	@Test
	void armColourClockGoingBackwardsDoesNotWedgeTheThrottle() {
		ArmColours colours = new ArmColours();
		assertEquals(ArmColours.Result.CHANGED, colours.request(A, 1, 0, 100, 5000));
		// a tick before the last accepted one (a restarted clock): taken, not held for ever
		assertEquals(ArmColours.Result.CHANGED, colours.request(A, 2, 0, 100, 10));
		assertEquals(2, colours.of(A));
	}

	// ------------------------------------------------------------------ the hello's version

	@Test
	void plainVersionsAreKept() {
		for (String v : new String[] {"8.0.0-beta.2", "8.0.0-beta.2+26.3", "7.5.0", "v6.6.0", "1.0.0-rc.1", "0.0.0", "26.3_dev", "a".repeat(32)}) {
			assertEquals(v, ClientVersion.sanitize(v), v);
		}
	}

	@Test
	void hostileVersionsBecomeUnknown() {
		String[] hostile = {
				"", " ", "?", "<click:run_command:/op me>", "<selector:@e>", "<nbt:block:0 0 0:Items>", "8.0.0\n[INFO] forged line", "8.0.0\r", "1.0.0 ", " 1.0.0",
				"{client}", "${version}", "%s", "a".repeat(33), "9".repeat(5000), "../../etc", "8.0.0\0", "é8.0.0", "8.0.0‮", "\u0000", "-1.0.0", ".1", "+1",
		};
		for (String v : hostile) assertEquals(ClientVersion.UNKNOWN, ClientVersion.sanitize(v), "version " + v.replace("\n", "\\n"));
		assertEquals(ClientVersion.UNKNOWN, ClientVersion.sanitize(null));
	}

	@Test
	void unknownVersionGetsNothingNewerThanTheBasics() {
		assertFalse(PassiveProtocol.knows(ClientVersion.UNKNOWN));
		assertTrue(SemVer.compare(ClientVersion.UNKNOWN, ArmProtocol.CLIENT_SINCE) < 0);
		assertTrue(SemVer.compare(ClientVersion.UNKNOWN, ArmProtocol.NEW_FORMS_SINCE) < 0);
	}

	@Test
	void semVerNeverThrowsOnWhatAClientMayClaim() {
		String[] claims = {"1.0.0-" + "9".repeat(30), "1.0.0-" + "9".repeat(30) + ".1", "99999999999999999999999.0.0", "1..", "..", "-", "--", "+", "1.0.0-", "1.0.0-.", "v", "V1",
				"1.2.3.4.5.6", "1.0.0-1.2.3.4.5.6.7", "", "?", "x.y.z", "1.0.0-beta." + "9".repeat(40)};
		for (String a : claims) {
			for (String b : new String[] {ArmProtocol.CLIENT_SINCE, ArmProtocol.NEW_FORMS_SINCE, PassiveProtocol.SINCE, "8.0.0-1", "8.0.0-" + "9".repeat(30), "8.0.0"}) {
				assertDoesNotThrow(() -> SemVer.compare(a, b), a + " vs " + b);
				assertDoesNotThrow(() -> SemVer.compare(b, a), b + " vs " + a);
			}
		}
		// an absurd numeric pre-release counts as the largest, not as a crash
		assertTrue(SemVer.compare("8.0.0-" + "9".repeat(30), "8.0.0-1") > 0);
	}

	@Test
	void aVersionClaimedNewerOnlyChangesWhatThatClientIsSent() {
		// the claim decides which packets the claimant receives (drawsArms and its relatives), never anything about anyone else:
		// it is read per viewer from the viewer's own entry, and it costs nothing to claim a lower or a higher one
		assertTrue(SemVer.compare("99.0.0", ArmProtocol.NEW_FORMS_SINCE) > 0);
		assertTrue(SemVer.compare("0.0.1", ArmProtocol.CLIENT_SINCE) < 0);
	}

	// ------------------------------------------------------------------ the budget

	/**
	 * The exact sequence ClientChannel runs for a modded player's packet: the total once (gate(ANY)), then the kind's own bucket only
	 * (gateKind). The tests below once drew the total once per packet while the listener drew it twice, so they passed on a double charge.
	 */
	private static boolean listener(PacketBudget budget, PacketBudget.Kind kind, long tick) {
		if (!budget.allowWithTotal(A, PacketBudget.Kind.ANY, tick)) return false;
		return kind == PacketBudget.Kind.ANY || budget.allow(A, kind, tick);
	}

	@Test
	void aHeldKeyPressHasABucketOfItsOwnAndAReleaseHasNone() {
		assertEquals(PacketBudget.Kind.HOLD, ClientChannel.armBudget(ArmProtocol.ACTION_ACCESSORY_HOLD, 1));
		assertNull(ClientChannel.armBudget(ArmProtocol.ACTION_ACCESSORY_HOLD, 0), "a release only lowers something: a dropped one left the shield up");
		assertEquals(PacketBudget.Kind.SCREEN, ClientChannel.armBudget(ArmProtocol.ACTION_INVENTORY_OPEN, 0));
		assertEquals(PacketBudget.Kind.SCREEN, ClientChannel.armBudget(ArmProtocol.ACTION_INVENTORY_CLOSED, 0));
		assertTrue(ClientChannel.skipsArmThrottle(ArmProtocol.ACTION_ACCESSORY_HOLD));
		assertTrue(ClientChannel.skipsArmThrottle(ArmProtocol.ACTION_INVENTORY_OPEN));
		assertFalse(ClientChannel.skipsArmThrottle(ArmProtocol.ACTION_ACCESSORY_ON), "every other arm request keeps the throttle");
		// a long run of right-clicks with the shield on (a press and a release each) uses up the presses, not the screen signals
		PacketBudget budget = new PacketBudget();
		int pressed = 0;
		for (int i = 0; i < 60; i++) if (listener(budget, PacketBudget.Kind.HOLD, 500)) pressed++;
		assertEquals(PacketBudget.Kind.HOLD.capacity, pressed, "the presses are bounded");
		assertTrue(listener(budget, PacketBudget.Kind.SCREEN, 500), "an inventory signal still gets through");
	}

	@Test
	void anHonestClientNeverMeetsTheBudget() {
		PacketBudget budget = new PacketBudget();
		long tick = 1000;
		// a hello, then a minute of a very busy player: ability 8 a second, colour and arm requests, E mashed 5 times a second, selector clicks
		assertTrue(budget.allowWithTotal(A, PacketBudget.Kind.HELLO, tick));
		for (int t = 0; t < 1200; t++, tick++) {
			if (t % 3 == 0) assertTrue(listener(budget, PacketBudget.Kind.ABILITY, tick), "ability at " + t);
			if (t % 4 == 0) assertTrue(listener(budget, PacketBudget.Kind.ANY, tick), "arm at " + t);
			if (t % 10 == 0) {
				assertTrue(listener(budget, PacketBudget.Kind.SCREEN, tick), "open at " + t);
				assertTrue(listener(budget, PacketBudget.Kind.SCREEN, tick), "close at " + t);
			}
			if (t % 6 == 0) assertTrue(listener(budget, PacketBudget.Kind.LIST, tick), "list at " + t);
			if (t % 8 == 0) assertTrue(listener(budget, PacketBudget.Kind.UPGRADES, tick), "upgrades at " + t);
		}
	}

	@Test
	void aKindCostsOneTotalTokenNotTwo() {
		// the double charge: 100 tokens of ANY must admit 100 packets of a kind with room, not 50
		PacketBudget budget = new PacketBudget();
		int passed = 0;
		for (int i = 0; i < 1000; i++) {
			// spread over kinds so no single kind's own bucket is what limits this
			PacketBudget.Kind kind = i % 2 == 0 ? PacketBudget.Kind.SCREEN : PacketBudget.Kind.LIST;
			if (listener(budget, kind, 0)) passed++;
		}
		assertEquals(PacketBudget.Kind.SCREEN.capacity + PacketBudget.Kind.LIST.capacity, passed, "limited by the two kinds' own buckets, not by a halved total");
		// and ANY alone, with kinds that have room for all of it, is the documented 100
		PacketBudget other = new PacketBudget();
		PacketBudget.Kind[] roomy = {PacketBudget.Kind.SCREEN, PacketBudget.Kind.LIST, PacketBudget.Kind.UPGRADES, PacketBudget.Kind.ABILITY};
		int sum = 0;
		for (int i = 0; i < 1000; i++) if (listener(other, roomy[i % roomy.length], 0)) sum++;
		assertEquals(PacketBudget.Kind.SCREEN.capacity + PacketBudget.Kind.LIST.capacity + PacketBudget.Kind.UPGRADES.capacity + PacketBudget.Kind.ABILITY.capacity, sum);
		// the total itself: 100 plain packets pass in a burst
		PacketBudget plain = new PacketBudget();
		int anyPassed = 0;
		for (int i = 0; i < 1000; i++) if (listener(plain, PacketBudget.Kind.ANY, 0)) anyPassed++;
		assertEquals(PacketBudget.Kind.ANY.capacity, anyPassed);
	}

	@Test
	void aBurstPassesAndTheFloodAfterItIsDropped() {
		PacketBudget budget = new PacketBudget();
		int passed = 0;
		for (int i = 0; i < 10_000; i++) if (budget.allowWithTotal(A, PacketBudget.Kind.LIST, 500)) passed++;
		assertEquals(PacketBudget.Kind.LIST.capacity, passed, "the whole burst of a kind, then nothing in the same tick");
		assertFalse(budget.allowWithTotal(A, PacketBudget.Kind.LIST, 500));
		// three ticks earn one back
		assertFalse(budget.allowWithTotal(A, PacketBudget.Kind.LIST, 502));
		assertTrue(budget.allowWithTotal(A, PacketBudget.Kind.LIST, 503));
		assertFalse(budget.allowWithTotal(A, PacketBudget.Kind.LIST, 503));
	}

	@Test
	void sustainedFloodIsHeldToTheRefillRate() {
		PacketBudget budget = new PacketBudget();
		int passed = 0;
		// 500 of them every tick for a minute (24 000 a second): the 20 a second of ANY is what gets through, plus the opening burst
		for (long tick = 0; tick < 1200; tick++) {
			for (int i = 0; i < 500; i++) if (listener(budget, PacketBudget.Kind.SCREEN, tick)) passed++;
		}
		assertTrue(passed <= PacketBudget.Kind.ANY.capacity + 1200 + 1, "passed " + passed);
		assertTrue(passed >= 1100, "but it is not starved: " + passed);
	}

	@Test
	void theTotalCapsAMixOfKinds() {
		PacketBudget budget = new PacketBudget();
		PacketBudget.Kind[] kinds = {PacketBudget.Kind.LIST, PacketBudget.Kind.UPGRADES, PacketBudget.Kind.ABILITY, PacketBudget.Kind.SCREEN};
		int passed = 0;
		for (int i = 0; i < 20_000; i++) if (listener(budget, kinds[i % kinds.length], 0)) passed++;
		assertTrue(passed <= PacketBudget.Kind.ANY.capacity, "passed " + passed);
	}

	@Test
	void oneKindOutOfTokensDoesNotBlockTheOthers() {
		PacketBudget budget = new PacketBudget();
		for (int i = 0; i < 100; i++) budget.allow(A, PacketBudget.Kind.SYNC, 0);
		assertFalse(budget.allow(A, PacketBudget.Kind.SYNC, 0));
		assertTrue(budget.allow(A, PacketBudget.Kind.ABILITY, 0));
	}

	@Test
	void helloIsLimitedHard() {
		PacketBudget budget = new PacketBudget();
		int passed = 0;
		for (int i = 0; i < 100; i++) if (budget.allowWithTotal(A, PacketBudget.Kind.HELLO, i)) passed++;
		assertEquals(PacketBudget.Kind.HELLO.capacity, passed, "two hellos in the first 100 ticks, then one every 5 seconds");
		assertTrue(budget.allowWithTotal(A, PacketBudget.Kind.HELLO, 200));
	}

	@Test
	void budgetIsPerPlayerAndForgotten() {
		PacketBudget budget = new PacketBudget();
		for (int i = 0; i < 1000; i++) budget.allowWithTotal(A, PacketBudget.Kind.ANY, 0);
		assertFalse(budget.allowWithTotal(A, PacketBudget.Kind.ANY, 0));
		assertTrue(budget.allowWithTotal(B, PacketBudget.Kind.ANY, 0), "B is not touched by A's flood");
		assertEquals(2, budget.tracked());
		budget.forget(A);
		assertEquals(1, budget.tracked());
		assertTrue(budget.allowWithTotal(A, PacketBudget.Kind.ANY, 0), "a player back after leaving starts full");
	}

	@Test
	void budgetSurvivesTheClockGoingBackwards() {
		PacketBudget budget = new PacketBudget();
		for (int i = 0; i < 100; i++) budget.allow(A, PacketBudget.Kind.ANY, 10_000);
		assertFalse(budget.allow(A, PacketBudget.Kind.ANY, 10_000));
		assertFalse(budget.allow(A, PacketBudget.Kind.ANY, 5), "an earlier tick earns nothing");
		assertTrue(budget.allow(A, PacketBudget.Kind.ANY, 6), "and the clock carries on from there");
		long huge = Long.MAX_VALUE - 5;
		assertDoesNotThrow(() -> budget.allow(B, PacketBudget.Kind.ANY, huge));
		assertDoesNotThrow(() -> budget.allow(B, PacketBudget.Kind.ANY, Long.MAX_VALUE));
		assertDoesNotThrow(() -> budget.allow(B, PacketBudget.Kind.ANY, Long.MIN_VALUE));
	}

	@Test
	void logLinesAreRateLimited() {
		PacketBudget budget = new PacketBudget();
		assertEquals(0, budget.shouldLog(A, 100), "the first is written");
		for (int i = 0; i < 50; i++) assertEquals(-1, budget.shouldLog(A, 100 + i));
		assertEquals(-1, budget.shouldLog(A, 100 + PacketBudget.LOG_WINDOW_TICKS - 1));
		assertEquals(51, budget.shouldLog(A, 100 + PacketBudget.LOG_WINDOW_TICKS), "after the window: written, with how many were kept quiet");
		assertEquals(0, budget.shouldLog(B, 100), "another player has their own");
		assertEquals(0, budget.shouldLog(A, 5), "a clock that went back does not silence for ever");
	}

	// ------------------------------------------------------------------ fuzz

	/** The server reads a packet with exactly these decoders; whatever the bytes, the only thing that may come out is a MalformedPacketException. */
	@Test
	void randomBytesOnlyEverFailAsMalformed() {
		Random random = new Random(0x4C1E);
		Map<String, java.util.function.Consumer<Wire.In>> readers = Map.of(
				"arm", ClientChannel.ArmRequest::read,
				"passive", PassiveProtocol.Request::read,
				"hello", in -> {
					in.varInt();
					if (in.hasMore()) ClientVersion.sanitize(in.string(64));
				},
				"select", in -> {
					in.string(64);
					if (in.hasMore()) in.string(64);
				},
				"favourite", in -> {
					in.string(64);
					in.bool();
				},
				"colour", in -> {
					in.varInt();
					if (in.hasMore()) in.varInt();
					if (in.hasMore()) in.varInt();
				});
		for (int round = 0; round < 20_000; round++) {
			byte[] data = new byte[random.nextInt(48)];
			random.nextBytes(data);
			if (round % 3 == 0 && data.length > 1) data[0] = (byte) random.nextInt(0x40); // short, plausible lengths too
			for (var e : readers.entrySet()) {
				try {
					e.getValue().accept(new Wire.In(data));
				} catch (Wire.MalformedPacketException expected) {
					// fine: the handler logs it once and drops it
				} catch (RuntimeException other) {
					throw new AssertionError(e.getKey() + " threw " + other + " for " + java.util.HexFormat.of().formatHex(data), other);
				}
			}
		}
	}
}
