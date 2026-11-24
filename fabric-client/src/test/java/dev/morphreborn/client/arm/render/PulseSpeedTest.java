package dev.morphreborn.client.arm.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.geom.ArmDraw;
import org.junit.jupiter.api.Test;

/**
 * The pulse speed (2026-11-16: each player's choice, shown to everyone) in the pulse maths: every pulse runs
 * on a clock that is the usual one times the speed, so 2 is twice as fast (the idle train repeats every 23 ticks, a
 * SPEND reaches the tip in half the time), 1 is exactly what it always was, and the draw reads its owner's speed.
 */
class PulseSpeedTest {
	/** A katana's feed length: sheath 8, fist 4, then the tool to its tip. */
	private static final float KATANA = 36.5F;

	@Test
	void theUsualSpeedIsExactlyTheOldMaths() {
		float[] events = {Pulse.Kind.FEED.ordinal(), 10F, Pulse.Kind.SPEND.ordinal(), 11F};
		for (float t = 0.5F; t < 90F; t += 2.7F) {
			for (float phi = 0F; phi <= KATANA; phi += 3.1F) {
				assertEquals(Pulse.idle(phi, t, 1.3F, KATANA), Pulse.idle(phi, t, 1.3F, KATANA, 1F), 0F);
				assertEquals(Pulse.events(phi, t, events, 2, KATANA), Pulse.events(phi, t, events, 2, KATANA, 1F), 0F);
				assertEquals(Pulse.amp(phi, t, 1.3F, events, 2, KATANA), Pulse.amp(phi, t, 1.3F, events, 2, KATANA, 1F), 0F);
			}
		}
	}

	@Test
	void idleRepeatsEveryPeriodOverTheSpeed() {
		for (float speed : new float[] {0.25F, 0.5F, 2F, 3F}) {
			float period = Pulse.IDLE_PERIOD / speed;
			for (float t = 0.5F; t < 200F; t += 4.1F) {
				for (float phi = 0F; phi <= KATANA; phi += 3.3F) {
					assertEquals(Pulse.idle(phi, t, 0.9F, KATANA, speed), Pulse.idle(phi, t + period, 0.9F, KATANA, speed), 2e-4F, "speed " + speed + " t " + t + " phi " + phi);
				}
			}
		}
		// and not half of it: at twice the speed, 23 ticks on is the same place in the train, 46 / 2 is its period
		assertEquals(Pulse.idle(20F, 5F, 0F, KATANA, 2F), Pulse.idle(20F, 28F, 0F, KATANA, 2F), 1e-4F);
	}

	@Test
	void idleAtDoubleSpeedIsTheUsualPulseAtDoubleTheTime() {
		for (float t = 0.2F; t < 60F; t += 1.7F) {
			for (float phi = 0F; phi <= KATANA; phi += 2.9F) {
				assertEquals(Pulse.idle(phi, 2F * t, 0.4F, KATANA), Pulse.idle(phi, t, 0.4F, KATANA, 2F), 1e-4F);
				assertEquals(Pulse.idle(phi, 0.5F * t, 0.4F, KATANA), Pulse.idle(phi, t, 0.4F, KATANA, 0.5F), 1e-4F);
			}
		}
	}

	@Test
	void aSpendPulseReachesTheTipInHalfTheTimeAtDoubleSpeed() {
		for (float speed : new float[] {0.5F, 1F, 2F, 3F}) {
			float[] events = {Pulse.Kind.SPEND.ordinal(), 0F};
			float best = -1F, bestAt = -1F;
			for (float t = 0F; t < 60F; t += 0.01F) {
				float a = Pulse.events(KATANA, t, events, 1, KATANA, speed);
				if (a > best) {
					best = a;
					bestAt = t;
				}
			}
			assertEquals(4.9F / speed, bestAt, 0.2F / speed + 0.02F, "speed " + speed);
			assertEquals(Pulse.Kind.SPEND.amplitude, best, 0.05F, "the same strength, only sooner or later");
		}
	}

	@Test
	void aFeedPulseRunsBackToTheShoulderFasterAndSlower() {
		float[] events = {Pulse.Kind.FEED.ordinal(), 0F};
		// at the shoulder after 36.5 / 4 pulse ticks: 9.1 real ticks at the usual speed
		assertEquals(Pulse.at(Pulse.Kind.FEED, 0F, KATANA / 4F, KATANA), Pulse.events(0F, KATANA / 4F, events, 1, KATANA, 1F), 1e-5F);
		assertEquals(Pulse.at(Pulse.Kind.FEED, 0F, KATANA / 4F, KATANA), Pulse.events(0F, KATANA / 8F, events, 1, KATANA, 2F), 1e-4F);
		assertEquals(Pulse.at(Pulse.Kind.FEED, 0F, KATANA / 4F, KATANA), Pulse.events(0F, KATANA / 2F, events, 1, KATANA, 0.5F), 1e-4F);
	}

	@Test
	void aPulseThatHasNotStartedStaysAbsentAtAnySpeed() {
		float[] events = {Pulse.Kind.SPEND.ordinal(), 10F};
		for (float speed : new float[] {0.25F, 1F, 3F}) assertEquals(0F, Pulse.events(20F, 9F, events, 1, KATANA, speed));
	}

	@Test
	void aSlowPulseOutlivesTheUsualOneAndTheReactionsKeepItLongEnough() {
		float life = Pulse.lifetime(Pulse.Kind.FEED, KATANA);
		// the slowest speed: the same pulse takes 4 times as many real ticks, and must still be on the arm then
		float[] events = {Pulse.Kind.FEED.ordinal(), 0F};
		assertTrue(Pulse.events(0F, life / Pulse.MIN_SPEED * 0.9F, events, 1, KATANA, Pulse.MIN_SPEED) >= 0F);
		assertEquals(0F, Pulse.events(0F, life / Pulse.MIN_SPEED * 1.01F, events, 1, KATANA, Pulse.MIN_SPEED), 1e-6F, "gone past its lifetime");
		assertTrue(life / Pulse.MIN_SPEED <= 40F / Pulse.MIN_SPEED, "the reactions keep 40 / the slowest speed ticks: " + life);
	}

	@Test
	void speedIsKeptToTheAllowedRange() {
		assertEquals(Pulse.MIN_SPEED, Pulse.clampSpeed(0F));
		assertEquals(Pulse.MIN_SPEED, Pulse.clampSpeed(-4F));
		assertEquals(Pulse.MAX_SPEED, Pulse.clampSpeed(80F));
		assertEquals(Pulse.MAX_SPEED, Pulse.clampSpeed(Float.POSITIVE_INFINITY));
		assertEquals(1F, Pulse.clampSpeed(Float.NaN), "an unusable speed is the usual one");
		assertEquals(1.7F, Pulse.clampSpeed(1.7F));
		// a zero speed would freeze the pulses where they stand: it reads as the slowest instead
		assertEquals(Pulse.idle(10F, 5F, 0F, KATANA, Pulse.MIN_SPEED), Pulse.idle(10F, 5F, 0F, KATANA, 0F), 0F);
		assertEquals(Pulse.idle(10F, 5F, 0F, KATANA, Pulse.MAX_SPEED), Pulse.idle(10F, 5F, 0F, KATANA, 1000F), 0F);
		// and the limits are the protocol's, in percent
		assertEquals(ArmProtocol.PULSE_SPEED_MIN / 100F, Pulse.MIN_SPEED);
		assertEquals(ArmProtocol.PULSE_SPEED_MAX / 100F, Pulse.MAX_SPEED);
	}

	// ------------------------------------------------------------------ the draw: what every swelling reads

	/** A katana arm at {@code time} with one SPEND pulse started at tick 0 and the owner's {@code speed}. */
	private static ArmDraw draw(float time, float speed) {
		ArmDraw rest = ArmDraw.rest("katana", 1, ArmDraw.CTX_TP, ArmDraw.LOD_FULL, time);
		return new ArmDraw(rest.form(), rest.tier(), rest.lod(), rest.ctx(), rest.leftHand(), rest.slim(), rest.chestArmour(), rest.time(), rest.seed(), rest.state(),
				rest.progress(), rest.fromForm(), rest.swing(), rest.bowPull(), rest.mining(), rest.biomassFrac(), rest.biomassAbs(), rest.surge(), rest.specialAge(),
				rest.specialKind(), rest.hitAge(), rest.breakAge(), new float[] {Pulse.Kind.SPEND.ordinal(), 0F}, 1, rest.lanternLit(), rest.lanternAge(), rest.recallLeft(),
				rest.glow(), rest.dissolve(), rest.drips(), rest.ropeNodes(), rest.downX(), rest.downY(), rest.downZ(), rest.fpAnchor(), rest.light(), speed);
	}

	@Test
	void anArmDrawnBeforeThePulseSpeedPulsesAtTheUsualSpeed() {
		assertEquals(1F, ArmDraw.rest("katana", 1, ArmDraw.CTX_TP, ArmDraw.LOD_FULL, 0F).pulseSpeed());
		ArmDraw d = draw(7F, 1F);
		assertEquals(Pulse.idle(20F, 7F, d.phase(), KATANA) + Pulse.events(20F, 7F, d.pulses(), 1, KATANA), d.pulse(20F, KATANA), 1e-5F);
	}

	@Test
	void theDrawsSwellingRunsAtItsOwnersSpeed() {
		// the old 8.0 ticks of a SPEND at the usual speed are 4 at double speed, and 16 at half
		for (float t = 0.3F; t < 12F; t += 0.7F) {
			assertEquals(draw(2F * t, 1F).pulse(KATANA, KATANA), draw(t, 2F).pulse(KATANA, KATANA), 1e-4F, "t " + t);
			assertEquals(draw(0.5F * t, 1F).pulse(24F, KATANA), draw(t, 0.5F).pulse(24F, KATANA), 1e-4F, "t " + t);
		}
		// the idle part: the same, whatever the arm is doing
		ArmDraw rest = ArmDraw.rest("katana", 1, ArmDraw.CTX_TP, ArmDraw.LOD_FULL, 30F);
		assertEquals(Pulse.idle(15F, 30F, rest.phase(), KATANA), rest.pulse(15F, KATANA), 1e-6F);
	}

	@Test
	void twoPlayersPulseAtTheirOwnSpeeds() {
		// the same moment, the same event, two owners: not the same swelling
		boolean differs = false;
		for (float phi = 0F; phi <= KATANA; phi += 1F) differs |= Math.abs(draw(3F, 1F).pulse(phi, KATANA) - draw(3F, 3F).pulse(phi, KATANA)) > 1e-3F;
		assertTrue(differs);
	}

	@Test
	void theDrawKeepsItsSpeedThroughEveryCopyTheRendererMakes() {
		ArmDraw d = draw(5F, 2.5F);
		assertEquals(2.5F, d.pulseSpeed());
		assertEquals(2.5F, d.withForm("sickle").pulseSpeed());
		assertEquals(2.5F, d.withState(ArmProtocol.STATE_FORMING, 0.5F).pulseSpeed());
		assertEquals(2.5F, d.finish(ArmDraw.LOD_FULL, false, null, 0F, -1F, 0F, null, 0xF000F0).pulseSpeed());
	}

	@Test
	void aBadSpeedInTheDrawIsKeptToTheAllowedRange() {
		assertEquals(Pulse.MIN_SPEED, draw(1F, 0F).pulseSpeed());
		assertEquals(Pulse.MAX_SPEED, draw(1F, 99F).pulseSpeed());
		assertEquals(1F, draw(1F, Float.NaN).pulseSpeed());
	}

	@Test
	void theRedFacesLightAndTheLanternSeamReadTheSameSwelling() {
		// Loft (the red faces pulse light, pulseGlowPass, the seam and stalk tubes) reads ArmDraw.swell, which is pulse plus
		// the settle, so the speed reaches all of them through the one method
		ArmDraw d = draw(3F, 2F);
		assertEquals(d.pulse(30F, KATANA) + d.settle(30F, KATANA), d.swell(30F, KATANA), 1e-6F);
		assertEquals(draw(6F, 1F).swell(30F, KATANA), d.swell(30F, KATANA), 1e-4F);
	}
}
