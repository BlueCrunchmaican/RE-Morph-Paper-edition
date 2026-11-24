package dev.morphreborn.client.arm.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.render.ArmLayer;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/** The whip's moves (FX_WHIP_MOVE, 2026-11-16): the contract's numbers, the timelines and the lash paths' joins. */
class WhipMoveFxTest {
	@Test
	void contractMatchesTheServersNumbers() {
		assertEquals(14, ArmProtocol.FX_WHIP_MOVE);
		assertEquals(21, ArmProtocol.FX_KINDS, "the new forms' kinds 15 to 20 came after");
		assertEquals("whip_move", ArmProtocol.fxName(ArmProtocol.FX_WHIP_MOVE));
		assertEquals("7.6.0-beta.4", ArmProtocol.WHIP_MOVES_SINCE);
		assertEquals(1, ArmProtocol.WHIP_STRIKE);
		assertEquals(2, ArmProtocol.WHIP_SNARE);
		assertEquals(3, ArmProtocol.WHIP_DISARM);
		assertEquals(4, ArmProtocol.WHIP_CRACK);
		assertEquals(5, ArmProtocol.WHIP_WHIRL);
	}

	@Test
	void livesAreBoundedWhateverTheServerSays() {
		assertEquals(0, WhipMoveFx.life(0, 10));
		assertEquals(0, WhipMoveFx.life(9, 10));
		assertEquals(10, WhipMoveFx.life(ArmProtocol.WHIP_STRIKE, 1_000_000));
		assertEquals(3, WhipMoveFx.life(ArmProtocol.WHIP_STRIKE, -5));
		// a snare lasts its hold plus the 3 ticks out and 5 to unravel
		assertEquals(3 + 40 + 5, WhipMoveFx.life(ArmProtocol.WHIP_SNARE, 40));
		assertEquals(3 + 100 + 5, WhipMoveFx.life(ArmProtocol.WHIP_SNARE, 1_000_000));
	}

	@Test
	void lashGoesOutFastAndDrawsBack() {
		assertEquals(0F, WhipMoveFx.outBack(0F, 1.3F, 5F), 1e-6F);
		assertEquals(1F, WhipMoveFx.outBack(1.3F, 1.3F, 5F), 1e-6F);
		assertTrue(WhipMoveFx.outBack(0.65F, 1.3F, 5F) > 0.7F, "eased out");
		assertEquals(0F, WhipMoveFx.outBack(5F, 1.3F, 5F), 1e-6F);
		assertEquals(1F, WhipMoveFx.outHoldBack(3F, 1.2F, 2.5F, 6F), 1e-6F);
		assertEquals(0F, WhipMoveFx.outHoldBack(6F, 1.2F, 2.5F, 6F), 1e-6F);
	}

	@Test
	void snareCoilsHoldsAndUnravels() {
		int hold = 40;
		assertEquals(0F, WhipMoveFx.snareReach(0F, hold), 1e-6F);
		assertEquals(1F, WhipMoveFx.snareReach(3F, hold), 1e-6F);
		assertEquals(1F, WhipMoveFx.snareReach(3F + hold, hold), 1e-6F);
		assertEquals(0F, WhipMoveFx.snareReach(3F + hold + 5F, hold), 1e-6F);
		assertEquals(0F, WhipMoveFx.snareSqueeze(0F, hold), 1e-6F);
		assertEquals(1F, WhipMoveFx.snareSqueeze(20F, hold), 1e-6F, "fully squeezed after the first 10 ticks of the hold");
		assertEquals(0F, WhipMoveFx.snareSqueeze(3F + hold + 5F, hold), 1e-6F);
	}

	@Test
	void whirlSpinsOnceAndAHalfWorthOfFraction() {
		assertEquals(0F, WhipMoveFx.whirlSpin(0F, 8F), 1e-6F);
		assertEquals(1F, WhipMoveFx.whirlSpin(8F, 8F), 1e-6F);
		assertTrue(WhipMoveFx.whirlSpin(2F, 8F) > 0.25F, "fast off the mark");
		for (float a = 0F; a < 8F; a += 0.5F) assertTrue(WhipMoveFx.whirlSpin(a + 0.5F, 8F) >= WhipMoveFx.whirlSpin(a, 8F));
	}

	@Test
	void coneStreaksStartAQuarterOutAndReachTheirDistance() {
		assertEquals(0.25F, WhipMoveFx.streakProgress(0F), 1e-6F);
		assertEquals(1F, WhipMoveFx.streakProgress(5.1F), 1e-6F);
		assertEquals(6F, WhipMoveFx.CONE[1], 0F);
		assertEquals(8F, WhipMoveFx.CONE[3], 0F);
		assertEquals(4.5F, WhipMoveFx.WHIRL_RADIUS[2], 0F);
	}

	@Test
	void coilJoinsItsLineToItsHelixAndStaysOnTheBody() {
		WhipMoveFx.Path p = new WhipMoveFx.Path();
		p.reset(WhipMoveFx.Path.TUBE, ArmLayer.BLACK, 0F, 1);
		p.mode = WhipMoveFx.Path.COIL;
		float phase = 0.7F, hr = 8F, top = -10F;
		p.hcx = 60F;
		p.hcy = top;
		p.hcz = 5F;
		p.hr = hr;
		p.hh = 30F;
		p.turns = 3F;
		p.hphase = phase;
		p.hsign = 1F;
		p.dx = p.hcx + hr * (float) Math.cos(phase);
		p.dy = top;
		p.dz = p.hcz + hr * (float) Math.sin(phase);
		p.sL = 0.4F;
		p.amp = 3F;
		p.frameFrom(p.dx, p.dy, p.dz);
		Vector3f out = new Vector3f();
		p.base(0F, out);
		assertEquals(0F, out.length(), 1e-3F, "starts at the whip");
		p.base(0.4F, out);
		assertEquals(p.dx, out.x, 1e-3F);
		assertEquals(p.dy, out.y, 1e-3F);
		assertEquals(p.dz, out.z, 1e-3F);
		p.base(0.4001F, out);
		assertEquals(p.dx, out.x, 0.1F, "no jump where the line becomes the helix");
		for (float s = 0.41F; s <= 1F; s += 0.03F) {
			p.base(s, out);
			float rx = out.x - p.hcx, rz = out.z - p.hcz;
			assertEquals(hr, (float) Math.sqrt(rx * rx + rz * rz), 1e-2F, "on the loops' radius");
			assertTrue(out.y <= top + 1e-3F && out.y >= top - p.hh - 1e-3F, "within the body's height");
		}
	}

	@Test
	void whirlLashLeavesTheHandAndEndsOnTheCircle() {
		WhipMoveFx.Path p = new WhipMoveFx.Path();
		p.reset(WhipMoveFx.Path.TUBE, ArmLayer.BLACK, 0F, 1);
		p.mode = WhipMoveFx.Path.ARC;
		p.acx = -10F;
		p.acy = -12F;
		p.acz = 5F;
		p.aR = 64F;
		p.atheta = 2F;
		p.alag = 1.15F;
		Vector3f out = new Vector3f();
		p.base(0F, out);
		assertEquals(0F, out.length(), 1e-3F, "from the hand");
		p.base(1F, out);
		float rx = out.x - p.acx, rz = out.z - p.acz;
		assertEquals(64F, (float) Math.sqrt(rx * rx + rz * rz), 1e-2F);
		assertEquals((float) Math.atan2(rz, rx), (float) Math.atan2(Math.sin(2F), Math.cos(2F)), 1e-3F, "the end is at the head's angle");
		p.mode = WhipMoveFx.Path.TRAIL;
		p.trail = 1.7F;
		p.base(1F, out);
		assertEquals(p.acx + 64F * (float) Math.cos(2F), out.x, 1e-3F);
	}

	@Test
	void ringStandsOnItsAxis() {
		WhipMoveFx.Path p = new WhipMoveFx.Path();
		p.reset(WhipMoveFx.Path.RING, ArmLayer.RED, 0F, 1);
		p.hr = 20F;
		p.frameFrom(0F, 0F, 1F);
		Vector3f out = new Vector3f();
		for (float s = 0F; s < 1F; s += 0.1F) {
			p.at(s, out);
			assertEquals(0F, out.z, 1e-3F, "in the plane across the look");
			assertEquals(20F, out.length(), 1e-3F);
		}
	}
}
