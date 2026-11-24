package dev.morphreborn.client.arm.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.geom.ArmDraw;
import dev.morphreborn.client.arm.geom.FlintMotion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The flint and steel's world effects (FX_ACCESSORY place 3: 0x301 IGNITE, 0x302 LIGHT, 0x303 SPARK, the server's encoding, kept as decided
 * on 2026-11-16): the routing, the contract's numbers, the striker rule and the timelines.
 */
class FlintFxTest {
	@AfterEach
	void reset() {
		new AccessoryFx().clear();
		FlintFx.strikerOn = id -> false;
	}

	private static ArmFxEvent event(int arg, int owner, int[] ids, long tick) {
		return new ArmFxEvent(owner, ArmProtocol.FX_ACCESSORY, 2, 0, 4242, ids, new long[0], arg, tick);
	}

	@Test
	void theContractsNumbersAreTheServersAndTheShieldsAndSpyglassesAreNotTaken() {
		assertEquals(0x301, FlintFx.IGNITE, "the server's FlintRules.fxArg(FX_IGNITE)");
		assertEquals(0x302, FlintFx.LIGHT, "FX_LIGHT");
		assertEquals(0x303, FlintFx.SPARK, "FX_SPARK, added on 2026-11-16");
		assertEquals(FlintFx.IGNITE, ArmProtocol.accessoryArg(ArmProtocol.ACCESSORY_PLACE_FLINT, 1));
		assertTrue(FlintFx.handles(0x301) && FlintFx.handles(0x302) && FlintFx.handles(0x303));
		for (int other : new int[] {0, 1, 2, 3, 4, 5, 6, 99, -1, 0x300, 0x304, 0x1FF, 0x101, 0x102, 0x201, 0x202, 0x401}) assertFalse(FlintFx.handles(other), "arg " + other + " is not the flint's");
		assertTrue(!ShieldFx.handles(0x301) && !ShieldFx.handles(0x303) && !SpyglassFx.handles(0x301) && !SpyglassFx.handles(0x303), "and the flint's are not theirs");
	}

	@Test
	void onlyAHitWithinReachIsTheStrikersOwnStrike() {
		assertTrue(FlintFx.strikeRange(0.0) && FlintFx.strikeRange(6.0 * 6.0) && FlintFx.strikeRange(8.0 * 8.0), "melee and a clicked block");
		assertFalse(FlintFx.strikeRange(8.5 * 8.5), "a far arrow's Fire tip: no knuckle strike, no embers across the air");
		assertFalse(FlintFx.strikeRange(40.0 * 40.0));
	}

	@Test
	void theAccessoryHandlerRoutesFlintsPlaceToIt() {
		AccessoryFx fx = new AccessoryFx();
		FlintFx.strikerOn = id -> true;
		fx.start(event(FlintFx.IGNITE, 5, new int[] {9}, 1000L));
		assertEquals(0F, FlintMotion.forcedAge(5, 1000F), 1e-5F, "an ignite through the accessory handler strikes the owner's striker");
		fx.start(event(0x101, 6, new int[] {9}, 1000L));
		assertEquals(ArmDraw.NEVER, FlintMotion.forcedAge(6, 1001F), "the shield's place is not the flint's");
	}

	@Test
	void anIgniteStrikesTheOwnersStrikerOnlyWhenItIsOnAndTheTargetIsIds0() {
		AccessoryFx fx = new AccessoryFx();
		FlintFx.strikerOn = id -> id == 5;
		fx.start(event(99, 5, new int[] {9}, 1000L));
		assertEquals(ArmDraw.NEVER, FlintMotion.forcedAge(5, 1001F), "an arg that isn't the flint's does nothing");
		fx.start(event(FlintFx.IGNITE, 5, new int[] {9}, 1010L));
		assertEquals(1F, FlintMotion.forcedAge(5, 1011F), 1e-5F);
		assertEquals(FlintMotion.POWER_IGNITE, FlintMotion.forcedPower(5), "an ignite strikes harder");
		assertEquals(4242, FlintMotion.forcedSeed(5));
		assertEquals(ArmDraw.NEVER, FlintMotion.forcedAge(9, 1011F), "the target's own striker is not struck (ids[0] is the creature, not the owner)");
		// a bow's Fire tip or a crossbow's bolt lit it with no flint and steel on: nothing is struck for a striker that is not there
		fx.start(event(FlintFx.IGNITE, 6, new int[] {9}, 1010L));
		assertEquals(ArmDraw.NEVER, FlintMotion.forcedAge(6, 1011F), "no striker on, no strike");
		// malformed events (an ignite with no target, a light with no block) are fine
		fx.start(new ArmFxEvent(5, ArmProtocol.FX_ACCESSORY, 99, 0, 1, new int[0], new long[0], FlintFx.IGNITE, 1500L));
		fx.start(new ArmFxEvent(5, ArmProtocol.FX_ACCESSORY, 1, 0, 1, new int[0], new long[0], FlintFx.LIGHT, 1500L));
		assertTrue(FlintMotion.forcedAge(5, 1501F) < 2F);
		fx.clear();
		assertEquals(ArmDraw.NEVER, FlintMotion.forcedAge(5, 1011F), "cleared with the world");
	}

	@Test
	void aLightStrikesTheStrikerAndASwitchOnSparkWaitsForTheTendrilToGrow() {
		AccessoryFx fx = new AccessoryFx();
		FlintFx.strikerOn = id -> id == 5;
		fx.start(new ArmFxEvent(5, ArmProtocol.FX_ACCESSORY, 2, 0, 77, new int[0], new long[] {123L}, FlintFx.LIGHT, 2000L));
		assertEquals(0F, FlintMotion.forcedAge(5, 2000F), 1e-5F, "lighting a block strikes at once");
		assertEquals(FlintMotion.POWER_SPARK, FlintMotion.forcedPower(5));
		// the switch-on's spark has no ids and no positions, needs no state (it goes out ahead of the arm state) and strikes once the tendril is out
		fx.start(new ArmFxEvent(7, ArmProtocol.FX_ACCESSORY, 1, 0, 11, new int[0], new long[0], FlintFx.SPARK, 3000L));
		assertEquals(ArmDraw.NEVER, FlintMotion.forcedAge(7, 3005F), "not yet: the knuckles only strike once the tendril has grown out");
		assertEquals(ArmDraw.NEVER, FlintMotion.forcedAge(7, 3000F), "a strike set for later has no age yet");
		assertEquals(0F, FlintMotion.forcedAge(7, 3000F + FlintFx.SPARK_DELAY), 1e-5F);
		assertEquals(2F, FlintMotion.forcedAge(7, 3002F + FlintFx.SPARK_DELAY), 1e-5F);
		assertEquals(FlintMotion.POWER_SPARK, FlintMotion.forcedPower(7));
		assertTrue(FlintMotion.built(FlintFx.SPARK_DELAY) > 0.9F, "the renderer's gate for a strike is built > 0.9");
		assertTrue(FlintMotion.built(FlintFx.SPARK_DELAY - 2F) < 0.9F, "and not two ticks earlier: the delay is the first whole tick that is");
		assertTrue(FlintFx.SPARK_DELAY < FlintMotion.FORGET_TICKS, "it is not forgotten before it happens");
	}

	@Test
	void manyIgnitesAtOnceNeverPileUpWithoutEnd() {
		AccessoryFx fx = new AccessoryFx();
		for (int i = 0; i < 100; i++) fx.start(event(FlintFx.IGNITE, i, new int[] {i, i + 1000}, 1000L + i));
		// the world is not there in a unit test, so tick only has to cope: it drops the old ones and keeps the rest bounded
		fx.tick();
		fx.clear();
	}

	@Test
	void theTrailRunsOutToTheTargetInThreeTicksAndTheEmbersAreDroppedInOrder() {
		assertEquals(0F, FlintFx.head(0F), 1e-6F);
		assertEquals(1F, FlintFx.head(FlintFx.TRAIL_TICKS), 1e-6F);
		assertEquals(1F, FlintFx.head(50F), 1e-6F);
		float last = 0F;
		for (float a = 0F; a <= FlintFx.TRAIL_TICKS; a += 0.1F) {
			float h = FlintFx.head(a);
			assertTrue(h >= last - 1e-6F, "the head only runs on");
			last = h;
		}
		assertEquals(0F, FlintFx.dropAt(0, 10), 1e-6F, "the first ember is dropped at the striker");
		assertEquals(1F, FlintFx.dropAt(9, 10), 1e-6F, "the last at the target");
		assertEquals(0F, FlintFx.dropAt(0, 1), 1e-6F);
		float prev = -1F;
		for (int i = 0; i < 10; i++) {
			float at = FlintFx.dropAt(i, 10);
			assertTrue(at > prev, "dropped one after another along the line");
			prev = at;
			assertTrue(FlintFx.emberAge(0F, i, 10) <= 0F, "none exists at the very start but the first");
			assertEquals(0F, FlintFx.emberAge(FlintFx.TRAIL_TICKS * at, i, 10), 1e-5F, "an ember is born as the head reaches it");
		}
		assertTrue(FlintFx.emberAge(1F, 9, 10) < 0F, "the last is not there yet a tick in");
	}

	@Test
	void anEmberFlaresBurnsDownAndGoesOutWithinItsLife() {
		for (int seed = 0; seed < 20; seed++) {
			for (int i = 0; i < 10; i++) {
				float life = FlintFx.emberLife(seed, i);
				assertTrue(life >= 6F && life <= 12F, "a life of 6 to 12 ticks: " + life);
				assertEquals(0F, FlintFx.emberSize(-0.5F, life), 1e-6F, "nothing before it is dropped");
				assertEquals(0F, FlintFx.emberSize(life, life), 1e-6F, "out at the end of its life");
				assertEquals(0F, FlintFx.emberSize(life + 3F, life), 1e-6F);
				float peak = 0F, last = 0F;
				boolean falling = false;
				for (float a = 0F; a < life; a += 0.1F) {
					float s = FlintFx.emberSize(a, life);
					assertTrue(s >= 0F && s <= 2.4F + 1e-4F, "bounded: " + s);
					if (s < last - 1e-6F) falling = true;
					else assertFalse(falling && s > last + 1e-4F, "once it burns down it does not flare again");
					peak = Math.max(peak, s);
					last = s;
				}
				assertTrue(peak > 0.5F, "it is seen");
			}
		}
		boolean unequal = false;
		for (int i = 1; i < 10; i++) unequal |= FlintFx.emberLife(3, i) != FlintFx.emberLife(3, 0);
		assertTrue(unequal, "embers are not alike");
	}

	@Test
	void theBurstSpreadsOutFastAndTheKnotFlashesForTwoTicks() {
		assertEquals(0F, FlintFx.spread(0F), 1e-6F);
		assertEquals(1F, FlintFx.spread(FlintFx.BURST_LIFE), 1e-6F);
		float last = 0F;
		for (float a = 0F; a <= FlintFx.BURST_LIFE; a += 0.25F) {
			float s = FlintFx.spread(a);
			assertTrue(s >= last - 1e-6F, "the sparks never come back");
			last = s;
		}
		assertTrue(FlintFx.spread(FlintFx.BURST_LIFE / 3F) > 0.6F, "most of the spread is in the first third of the life");
		assertEquals(3.6F, FlintFx.knot(0F), 1e-5F);
		assertEquals(0F, FlintFx.knot(2F), 1e-6F);
		assertEquals(0F, FlintFx.knot(9F), 1e-6F);
		assertTrue(FlintFx.knot(1F) > 0F && FlintFx.knot(1F) < FlintFx.knot(0F));
		assertTrue(FlintFx.BURST_LIFE < FlintFx.LIFE, "the burst is over before the effect is");
	}

	@Test
	void theHandlerIsRegisteredAndTakesTheAccessoryKind() {
		assertTrue(ArmFx.HANDLERS.contains("dev.morphreborn.client.arm.fx.AccessoryFx"));
		assertEquals(20, ArmProtocol.FX_ACCESSORY);
	}
}
