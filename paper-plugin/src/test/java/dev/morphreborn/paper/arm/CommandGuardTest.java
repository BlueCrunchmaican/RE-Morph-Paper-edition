package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.CommandGuard;
import dev.morphreborn.paper.arm.rules.CommandGuard.Mode;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Which commands get a plain hand first. */
class CommandGuardTest {
	private static final Set<String> LISTED = ArmConfig.DEFAULTS.commandGuard().listed();
	private static final Set<String> KEEP = ArmConfig.DEFAULTS.commandGuard().keep();

	private static boolean listed(String typed, String resolved) {
		return CommandGuard.retractFirst(typed, resolved, Mode.LISTED, LISTED, KEEP);
	}

	@Test
	void namespaceIsStripped() {
		assertTrue(listed("essentials:sell", "sell"));
		assertTrue(listed("/essentials:sell hand", null));
		assertTrue(listed("hat", null));
	}

	@Test
	void caseDoesNotMatter() {
		assertTrue(listed("SELL", null));
		assertTrue(listed("Essentials:Repair", null));
		assertTrue(CommandGuard.retractFirst("sell", null, Mode.LISTED, Set.of("/Sell"), Set.of()), "an admin's own spelling in the list");
	}

	@Test
	void aliasesResolveToTheirCommand() {
		// a server alias "/s" for /sell, and EssentialsX's "/esell"
		assertTrue(listed("s", "sell"));
		assertTrue(listed("esell", "essentials:sell"));
		assertFalse(listed("s", "spawn"));
	}

	@Test
	void everydayCommandsKeepTheArm() {
		for (String c : new String[] {"home", "tpa", "msg", "spawn", "tardis", "morph", "warp"}) assertFalse(listed(c, c), c);
	}

	@Test
	void keepListUnderAll() {
		assertTrue(CommandGuard.retractFirst("tardis", "tardis", Mode.ALL, LISTED, KEEP), "anything not kept retracts");
		assertFalse(CommandGuard.retractFirst("home", "home", Mode.ALL, LISTED, KEEP));
		assertFalse(CommandGuard.retractFirst("t", "tell", Mode.ALL, LISTED, KEEP), "an alias of a kept command");
		assertFalse(CommandGuard.retractFirst("essentials:msg", null, Mode.ALL, LISTED, KEEP));
	}

	@Test
	void offNeverRetracts() {
		assertFalse(CommandGuard.retractFirst("sell", "sell", Mode.OFF, LISTED, KEEP));
		assertFalse(CommandGuard.retractFirst("hat", null, Mode.OFF, LISTED, KEEP));
	}

	@Test
	void modeParsesYamlBooleans() {
		assertEquals(Mode.OFF, Mode.parse(false), "YAML reads a bare off as false");
		assertEquals(Mode.OFF, Mode.parse("off"));
		assertEquals(Mode.ALL, Mode.parse("ALL"));
		assertEquals(Mode.LISTED, Mode.parse("listed"));
		assertEquals(Mode.LISTED, Mode.parse(null));
	}

	@Test
	void normalise() {
		assertEquals("sell", CommandGuard.normalise("//Essentials:SELL   hand"));
		assertEquals("", CommandGuard.normalise("/"));
	}
}
