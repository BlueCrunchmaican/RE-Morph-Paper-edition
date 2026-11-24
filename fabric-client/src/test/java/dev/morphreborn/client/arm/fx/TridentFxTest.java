package dev.morphreborn.client.arm.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.geom.ArmDraw;
import dev.morphreborn.client.arm.geom.TridentGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The trident's world side (FX_TRIDENT, 2026-11-16): the contract (the moves in {@code arg}), what the effect hides and tells the hand while a
 * trident is away, the timelines (a recall is back in the hand in 2 ticks and gone in 8; a throw that was never seen times out; one
 * that is seen lasts until the server's projectile is gone), and the line's shape.
 */
class TridentFxTest {
	private static final int OWNER = 77, PROJECTILE = 9001;
	private TridentFx fx;

	@BeforeEach
	void fresh() {
		fx = new TridentFx();
		fx.clear();
		Arms.TICK = 5000;
	}

	@AfterEach
	void clean() {
		fx.clear();
	}

	private ArmFxEvent event(int arg, int[] ids, long[] positions) {
		return new ArmFxEvent(OWNER, ArmProtocol.FX_TRIDENT, 2, 0, 12345, ids, positions, arg, Arms.TICK);
	}

	private void tick(int n) {
		for (int i = 0; i < n; i++) {
			Arms.TICK++;
			fx.tick();
		}
	}

	@Test
	void theContractNumbers() {
		assertEquals(17, ArmProtocol.FX_TRIDENT, "the kind the server sends");
		assertEquals(0, TridentFx.END);
		assertEquals(1, TridentFx.THROW);
		assertEquals(2, TridentFx.RETURN);
		assertEquals(3, TridentFx.RECALL);
		assertEquals(4, TridentFx.CATCH);
		assertEquals(5, TridentFx.STRIKE);
		assertEquals(1, fx.kinds().length);
		assertEquals(ArmProtocol.FX_TRIDENT, fx.kinds()[0]);
	}

	@Test
	void aThrowHidesTheProjectileAndTellsTheHand() {
		assertFalse(TridentFx.hides(PROJECTILE));
		assertFalse(TridentFx.isThrown(OWNER));
		fx.start(event(TridentFx.THROW, new int[] {PROJECTILE}, new long[0]));
		assertTrue(TridentFx.hides(PROJECTILE), "the vanilla model isn't drawn for it");
		assertFalse(TridentFx.hides(PROJECTILE + 1));
		assertTrue(TridentFx.isThrown(OWNER), "the hand's trident is away");
		assertFalse(TridentFx.isThrown(OWNER + 1));
		assertEquals(1, fx.active());
		fx.clear();
		assertFalse(TridentFx.hides(PROJECTILE));
		assertFalse(TridentFx.isThrown(OWNER));
	}

	@Test
	void aThrowWithNoProjectileIsIgnored() {
		fx.start(event(TridentFx.THROW, new int[0], new long[0]));
		assertEquals(0, fx.active());
		assertFalse(TridentFx.isThrown(OWNER));
	}

	@Test
	void aNewThrowReplacesTheOwnersOldOne() {
		fx.start(event(TridentFx.THROW, new int[] {PROJECTILE}, new long[0]));
		fx.start(event(TridentFx.THROW, new int[] {PROJECTILE + 5}, new long[0]));
		assertEquals(1, fx.active());
		assertTrue(TridentFx.hides(PROJECTILE + 5));
		assertFalse(TridentFx.hides(PROJECTILE));
	}

	@Test
	void aRepeatedThrowForTheSameProjectileChangesNothing() {
		// the server sends THROW once, and again only to a viewer who has just come into range: a repeat must not restart the flight's clocks
		fx.start(event(TridentFx.THROW, new int[] {PROJECTILE}, new long[0]));
		tick(40);
		fx.start(event(TridentFx.THROW, new int[] {PROJECTILE}, new long[0]));
		assertEquals(1, fx.active());
		tick(42);
		assertFalse(TridentFx.isThrown(OWNER), "still on the clock of the first: 82 ticks in, never seen, the hand has it back");
	}

	@Test
	void aThrowRightAfterTheFlightEndedReplacesTheLingeringOne() {
		// the client lost the projectile (out of tracking range), the flight is retracting, and the viewer tracks it again: the server sends the
		// throw once, so it must not be taken for a repeat of the ended flight, which is about to be removed
		fx.start(event(TridentFx.THROW, new int[] {PROJECTILE}, new long[0]));
		fx.start(event(TridentFx.END, new int[] {PROJECTILE}, new long[0]));
		tick(1);
		assertFalse(TridentFx.isThrown(OWNER), "the ended flight is over");
		fx.start(event(TridentFx.THROW, new int[] {PROJECTILE}, new long[0]));
		assertTrue(TridentFx.isThrown(OWNER), "a new flight, on");
		assertTrue(TridentFx.hides(PROJECTILE));
		tick(8);
		assertEquals(1, fx.active(), "and it is not removed with the old one");
		assertTrue(TridentFx.hides(PROJECTILE));
	}

	@Test
	void aFlightFirstSeenReelingIsAimedEyeFirstAndTheOthersAlongTheirVelocity() {
		double[] out = new double[3];
		// flying: along the velocity; slow: the view vector; neither: nothing written
		assertTrue(TridentFx.aim(new Vec3(0, 0, 2), 2, Vec3.ZERO, false, out));
		assertEquals(1.0, out[2], 1e-9);
		out[2] = 7;
		assertFalse(TridentFx.aim(Vec3.ZERO, 0, Vec3.ZERO, false, out));
		assertEquals(7.0, out[2], 1e-9, "nothing written");
		assertTrue(TridentFx.aim(Vec3.ZERO, 0, new Vec3(1, 0, 0), false, out));
		assertEquals(1.0, out[0], 1e-9);
		// first seen mid-reel: the velocity points at the hand, the eye leads, so the prongs point the other way
		assertTrue(TridentFx.aim(new Vec3(0, -0.5, 0), 0.5, Vec3.ZERO, true, out));
		assertEquals(1.0, out[1], 1e-9);
		assertEquals(0.0, out[0], 1e-9);
	}

	@Test
	void endAndCatchLetTheHandHaveItBackWithoutWaitingForTheProjectileToVanish() {
		for (int move : new int[] {TridentFx.END, TridentFx.CATCH}) {
			fx.clear();
			fx.start(event(TridentFx.THROW, new int[] {PROJECTILE}, new long[0]));
			tick(3);
			assertTrue(TridentFx.isThrown(OWNER));
			fx.start(event(move, move == TridentFx.CATCH ? new int[] {PROJECTILE} : new int[0], new long[0]));
			tick(1);
			assertFalse(TridentFx.isThrown(OWNER), "move " + move + ": a flight this client never saw has nothing to shrink, so the hand has it at once");
			tick(6);
			assertEquals(0, fx.active(), "and it is forgotten");
		}
		// an end for a throw this client does not have is ignored
		fx.start(event(TridentFx.CATCH, new int[] {PROJECTILE}, new long[0]));
		assertEquals(0, fx.active());
	}

	@Test
	void aStrikeIsRememberedForTheFlashAndNeverStartsAThrowOfItsOwn() {
		fx.start(event(TridentFx.STRIKE, new int[] {PROJECTILE, 31}, new long[] {BlockPos.asLong(1, 2, 3)}));
		assertEquals(0, fx.active());
		fx.start(event(TridentFx.THROW, new int[] {PROJECTILE}, new long[0]));
		fx.start(event(TridentFx.STRIKE, new int[] {PROJECTILE, 31}, new long[] {BlockPos.asLong(1, 2, 3)}));
		fx.start(event(TridentFx.STRIKE, new int[] {PROJECTILE}, new long[0]));
		assertEquals(1, fx.active());
		assertTrue(TridentFx.isThrown(OWNER), "a strike doesn't end the throw: the reel follows");
	}

	@Test
	void theTridentsPointSitsOnTheEntityAndTheFlightCentreIsNoLongerOneAndAHalfBlocksOut() {
		for (int tier = 1; tier <= 3; tier++) {
			assertEquals(TridentGeometry.tipY(tier) - 1F, TridentFx.centreY(tier), 1e-4F, "the point is a px ahead of the position");
			// the point is within 0.1 block of the position (it was 1.2 blocks ahead)
			assertTrue(Math.abs(TridentGeometry.tipY(tier) - TridentFx.centreY(tier)) * 0.85F / 16F < 0.1F);
			assertTrue(TridentFx.poleBlocks(tier) > 1.5F && TridentFx.poleBlocks(tier) < 2.6F, "the pole runs about two blocks back: " + TridentFx.poleBlocks(tier));
		}
	}

	@Test
	void theEyeNeverGoesPastTheHandWhileItLeads() {
		for (int tier = 1; tier <= 3; tier++) {
			assertEquals(1F, TridentFx.nearHand(tier, 50.0), 1e-6F, "far from the hand it is full size");
			assertEquals(0F, TridentFx.nearHand(tier, 0.0), 1e-6F);
			for (double dist = 0.0; dist <= 6.0; dist += 0.25) {
				float scale = TridentFx.nearHand(tier, dist);
				assertTrue(scale >= 0F && scale <= 1F);
				// the butt, toward the hand, is at dist - pole * scale from it: never negative
				assertTrue(dist - TridentFx.poleBlocks(tier) * scale >= -1e-5, "tier " + tier + " dist " + dist);
			}
		}
	}

	@Test
	void theForkLiesLevelWhicheverWayItIsThrown() {
		double[] a = new double[9];
		int bad = 0;
		for (int yaw = 0; yaw < 360; yaw += 15) {
			for (int pitch = -85; pitch <= 85; pitch += 17) {
				double cy = Math.cos(Math.toRadians(pitch));
				double dx = -Math.sin(Math.toRadians(yaw)) * cy, dy = -Math.sin(Math.toRadians(pitch)), dz = Math.cos(Math.toRadians(yaw)) * cy;
				TridentFx.axes(dx, dy, dz, a);
				assertEquals(0.0, a[1], 1e-9, "the fork's spread axis is level");
				assertEquals(1.0, Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]), 1e-9);
				assertEquals(1.0, Math.sqrt(a[6] * a[6] + a[7] * a[7] + a[8] * a[8]), 1e-9);
				assertEquals(0.0, a[0] * a[3] + a[1] * a[4] + a[2] * a[5], 1e-9, "across the heading");
				assertEquals(0.0, a[6] * a[3] + a[7] * a[4] + a[8] * a[5], 1e-9);
				assertEquals(0.0, a[6] * a[0] + a[7] * a[1] + a[8] * a[2], 1e-9);
				// right-handed: X x Y = Z
				double cx = a[1] * a[5] - a[2] * a[4], cyy = a[2] * a[3] - a[0] * a[5], cz = a[0] * a[4] - a[1] * a[3];
				if (Math.abs(cx - a[6]) > 1e-9 || Math.abs(cyy - a[7]) > 1e-9 || Math.abs(cz - a[8]) > 1e-9) bad++;
			}
		}
		assertEquals(0, bad, "right-handed");
		// straight up and down are defined too (the world's x)
		TridentFx.axes(0, 1, 0, a);
		assertEquals(1.0, a[0], 1e-9);
		TridentFx.axes(0, -1, 0, a);
		assertEquals(1.0, a[0], 1e-9);
	}

	@Test
	void aThrowThatWasNeverSeenTimesOut() {
		fx.start(event(TridentFx.THROW, new int[] {PROJECTILE}, new long[0]));
		tick(79);
		assertTrue(TridentFx.isThrown(OWNER), "waiting for the entity to come into range");
		tick(5);
		assertFalse(TridentFx.isThrown(OWNER), "the hand has it back once the effect gives up");
		tick(12);
		assertEquals(0, fx.active(), "and it is forgotten");
		assertFalse(TridentFx.hides(PROJECTILE));
	}

	@Test
	void aReturnForAThrowWeMissedStartsOneAndAnEmptyOneIsIgnored() {
		fx.start(event(TridentFx.RETURN, new int[0], new long[0]));
		assertEquals(0, fx.active());
		fx.start(event(TridentFx.RETURN, new int[] {PROJECTILE}, new long[0]));
		assertEquals(1, fx.active(), "we came into range with it already out");
		assertTrue(TridentFx.isThrown(OWNER));
	}

	@Test
	void aRecallIsBackInTheHandInTwoTicksAndGoneInAtMostTwelve() {
		fx.start(event(TridentFx.THROW, new int[] {PROJECTILE}, new long[0]));
		tick(3);
		fx.start(event(TridentFx.RECALL, new int[] {PROJECTILE, 31, 32}, new long[] {BlockPos.asLong(10, 70, -4)}));
		assertTrue(TridentFx.isThrown(OWNER), "still away the moment it is called");
		tick(1);
		assertTrue(TridentFx.isThrown(OWNER));
		tick(1);
		assertFalse(TridentFx.isThrown(OWNER), "back in the hand at tick 2");
		tick(5);
		assertEquals(1, fx.active(), "the streak and the beads are still going");
		tick(8);
		assertEquals(0, fx.active());
	}

	@Test
	void aRecallWithNoProjectileStillBringsItHome() {
		fx.start(event(TridentFx.RECALL, new int[] {-1}, new long[] {BlockPos.asLong(1, 2, 3)}));
		assertTrue(TridentFx.isThrown(OWNER));
		tick(2);
		assertFalse(TridentFx.isThrown(OWNER));
		fx.start(event(TridentFx.RECALL, new int[0], new long[0]));
		tick(40);
		assertEquals(0, fx.active());
	}

	@Test
	void anUnknownMoveFromANewerServerIsLeftAlone() {
		fx.start(event(9, new int[] {PROJECTILE}, new long[0]));
		assertEquals(0, fx.active());
	}

	@Test
	void theHandsAwayStateReachesTheGeometry() {
		fx.start(event(TridentFx.THROW, new int[] {PROJECTILE}, new long[0]));
		// the mark TridentFx made eases the draw of this owner's arm: just thrown, the trident is still all there
		ArmDraw d = ArmDraw.rest(ArmProtocol.TRIDENT, 2, ArmDraw.CTX_TP, 0, (float) (Arms.TICK % 72000L));
		assertEquals(TridentGeometry.tipY(2), TRIDENT_TIP.apply(d, false), 0.6F);
	}

	/** The tip the geometry reports for {@code d}, as drawn with {@code thrown}. */
	private static final java.util.function.BiFunction<ArmDraw, Boolean, Float> TRIDENT_TIP = (d, thrown) -> {
		ArmDraw t = new ArmDraw(d.form(), d.tier(), d.lod(), d.ctx(), d.leftHand(), d.slim(), d.chestArmour(), d.time(), d.seed(), d.state(), d.progress(), d.fromForm(),
				d.swing(), d.bowPull(), d.mining(), d.biomassFrac(), d.biomassAbs(), d.surge(), d.specialAge(), d.specialKind(), d.hitAge(), d.breakAge(), d.pulses(),
				d.pulseCount(), d.lanternLit(), d.lanternAge(), d.recallLeft(), d.glow(), d.dissolve(), d.drips(), d.ropeNodes(), d.downX(), d.downY(), d.downZ(),
				d.fpAnchor(), d.light(), d.pulseSpeed(), d.accessories(), d.accessoryArgs(), d.accessoryAges(), thrown);
		return new TridentGeometry().point(t, dev.morphreborn.client.arm.ArmTips.TIP, new Vector3f()).y;
	};

	// ------------------------------------------------------------------ the line

	@Test
	void theLineStartsAtTheHandAndEndsAtTheEyeAndHangsBetween() {
		Vector3f out = new Vector3f();
		float[] side = {1F, 0F, 0F}, up = {0F, 1F, 0F};
		TridentFx.lineAt(0F, 160F, 0F, 0F, side[0], side[1], side[2], up[0], up[1], up[2], 20F, 0.8F, 1F, 5F, out);
		assertEquals(0F, out.length(), 1e-4F, "the hand");
		TridentFx.lineAt(1F, 160F, 0F, 0F, side[0], side[1], side[2], up[0], up[1], up[2], 20F, 0.8F, 1F, 5F, out);
		assertEquals(160F, out.x, 1e-3F, "the eye");
		assertEquals(0F, out.y, 1e-3F);
		// the middle hangs by the sag, and a line with no sag and no writhe is straight
		TridentFx.lineAt(0.5F, 160F, 0F, 0F, side[0], side[1], side[2], up[0], up[1], up[2], 20F, 0F, 1F, 5F, out);
		assertEquals(-20F, out.y, 1e-3F);
		assertEquals(80F, out.x, 1e-3F);
		for (float s = 0F; s <= 1F; s += 0.1F) {
			TridentFx.lineAt(s, 160F, 0F, 0F, side[0], side[1], side[2], up[0], up[1], up[2], 0F, 0F, 1F, 5F, out);
			assertEquals(0F, out.y, 1e-4F);
			assertEquals(0F, out.z, 1e-4F);
		}
		// the writhe is pinned at both ends and never wild
		for (float s = 0F; s <= 1F; s += 0.05F) {
			TridentFx.lineAt(s, 160F, 0F, 0F, side[0], side[1], side[2], up[0], up[1], up[2], 0F, 0.8F, 1F, 5F, out);
			assertTrue(Math.abs(out.y) <= 0.8F + 1e-3F && Math.abs(out.x - 160F * s) <= 0.8F + 1e-3F, "s=" + s + " " + out);
		}
	}
}
