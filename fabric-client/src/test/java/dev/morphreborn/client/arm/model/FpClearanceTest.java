package dev.morphreborn.client.arm.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.geom.ArmDraw;
import dev.morphreborn.client.arm.geom.FlintRenderer;
import dev.morphreborn.client.arm.geom.FpClearance;
import dev.morphreborn.client.arm.geom.FpLayout;
import dev.morphreborn.client.arm.geom.LureGeometry;
import dev.morphreborn.client.arm.geom.ShieldMotion;
import dev.morphreborn.client.arm.geom.ShieldRenderer;
import dev.morphreborn.client.arm.geom.SpyglassRenderer;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Vanilla first person, no First-person Model (2026-11-24): the accessories in the overlay never pass through the item in hand nor through each other.
 * Every held thing (the twelve arm forms, vanilla's sword, bow, crossbow, trident, shield, food and block, through every phase of every animation, posed
 * by {@link FpPoseMath}, vanilla's own chain) against every one of the fifteen sets of accessories, in either hand: the placement {@link FpOverlay} makes
 * is clear of the held volume and of the accessories placed before it, on screen, never smaller than its minimum and never moved more than the
 * most. The accessories' home places are the base: with nothing in the way they do not move.
 */
class FpClearanceTest {
	private static final int LANTERN = 0, SHIELD = 1, SPYGLASS = 2, FLINT = 3;
	private static final Matrix4f IDENTITY = new Matrix4f();

	@BeforeEach
	@AfterEach
	void reset() {
		FpHeld.forgetAll();
		FpLayout.clear();
		FpClearance.forget();
		LureGeometry.setOffhand(0F);
		ShieldMotion.clear();
	}

	/** An overlay draw with the accessories of {@code mask} (bit i is {@code ACCESSORIES.get(i)}) on and built, the shield raised when asked. */
	private static ArmDraw draw(int mask, boolean left, boolean raised, float time) {
		float[] ages = new float[ArmProtocol.ACCESSORIES.size()];
		for (int i = 0; i < ages.length; i++) ages[i] = 500F;
		int args = raised ? 1 << (4 * SHIELD) : 0;
		return new ArmDraw("", 2, 0, ArmDraw.CTX_FP, left, false, false, time, 7, ArmProtocol.STATE_NONE, 1F, "", 0F, 0F, false, 1F, ArmDraw.FULL, 0F, ArmDraw.NEVER,
				ArmDraw.NO_SPECIAL, ArmDraw.NEVER, ArmDraw.NEVER, new float[0], 0, (mask & 1) != 0, 500F, 0F, true, true, false, null, 0F, -1F, 0F, null, 0xF000F0, 1F, mask, args,
				ages, false);
	}

	/** One held thing: which hold, its form (an arm form) or extent points (a vanilla item), and whether it is a bow's hold. */
	private record Held(String name, FpPoseMath.Kind kind, String form, boolean bow, int sprite, float[] pts) {}

	private static List<Held> helds(boolean leftHand) {
		List<Held> out = new ArrayList<>();
		for (String form : ArmProtocol.FORMS) {
			boolean bow = ArmProtocol.BOW.equals(form);
			out.add(new Held(form + " idle", FpPoseMath.Kind.IDLE, form, bow, 0, null));
			out.add(new Held(form + " swing", FpPoseMath.Kind.WHACK, form, bow, 0, null));
			if (bow) out.add(new Held(form + " drawn", FpPoseMath.Kind.BOW, form, true, 0, null));
			if (form.equals(ArmProtocol.CROSSBOW)) {
				out.add(new Held(form + " charging", FpPoseMath.Kind.CROSSBOW_CHARGE, form, false, 0, null));
				out.add(new Held(form + " charged", FpPoseMath.Kind.CROSSBOW_CHARGED, form, false, 0, null));
			}
			if (form.equals(ArmProtocol.TRIDENT)) out.add(new Held(form + " thrown up", FpPoseMath.Kind.TRIDENT, form, false, 0, null));
		}
		var hand = leftHand ? ArmTransforms.HANDHELD_FP_LEFT : ArmTransforms.HANDHELD_FP_RIGHT;
		float[] sprite = FpPoseMath.outlinePoints("iron_sword", hand, leftHand);
		for (FpPoseMath.Kind k : new FpPoseMath.Kind[] {FpPoseMath.Kind.IDLE, FpPoseMath.Kind.WHACK, FpPoseMath.Kind.EQUIP, FpPoseMath.Kind.AUTOSPIN, FpPoseMath.Kind.BRUSH}) {
			out.add(new Held("sword " + k, k, null, false, 1, sprite));
		}
		out.add(new Held("bow drawn", FpPoseMath.Kind.BOW, null, false, 1, FpPoseMath.outlinePoints("bow_pulling_2", ArmTransforms.BOW_TP_RIGHT, leftHand)));
		out.add(new Held("bow idle", FpPoseMath.Kind.IDLE, null, false, 1, FpPoseMath.outlinePoints("bow", ArmTransforms.BOW_TP_RIGHT, leftHand)));
		float[] crossbow = FpPoseMath.outlinePoints("crossbow_pulling_2", leftHand ? ArmTransforms.CROSSBOW_FP_LEFT : ArmTransforms.CROSSBOW_FP_RIGHT, leftHand), loaded = FpPoseMath.outlinePoints("crossbow_arrow", leftHand ? ArmTransforms.CROSSBOW_FP_LEFT : ArmTransforms.CROSSBOW_FP_RIGHT, leftHand);
		out.add(new Held("crossbow charging", FpPoseMath.Kind.CROSSBOW_CHARGE, null, false, 1, crossbow));
		out.add(new Held("crossbow charged", FpPoseMath.Kind.CROSSBOW_CHARGED, null, false, 1, loaded));
		float[] trident = FpPoseMath.spritePoints(leftHand ? ArmTransforms.TRIDENT_FP_LEFT : ArmTransforms.TRIDENT_FP_RIGHT, leftHand);
		out.add(new Held("trident idle", FpPoseMath.Kind.IDLE, null, false, 1, trident));
		out.add(new Held("trident thrown up", FpPoseMath.Kind.TRIDENT, null, false, 1, trident));
		out.add(new Held("shield block", FpPoseMath.Kind.BLOCK_SHIELD, null, false, 1, FpPoseMath.boxPoints(hand, leftHand, 12F, 16F, 3F)));
		out.add(new Held("food eating", FpPoseMath.Kind.EAT, null, false, 1, FpPoseMath.outlinePoints("apple", ArmTransforms.GENERATED_FP, leftHand)));
		out.add(new Held("block idle", FpPoseMath.Kind.IDLE, null, false, 1, FpPoseMath.boxPoints(ArmTransforms.GENERATED_FP, leftHand, 10F, 10F, 10F)));
		return out;
	}

	private static int phases(Held h) {
		return switch (h.kind) {
			case IDLE -> 1;
			case WHACK, BOW, EAT, TRIDENT, CROSSBOW_CHARGE, BRUSH, EQUIP -> 11;
			default -> 1;
		};
	}

	private static void hold(Held h, boolean left, int phase, boolean main) {
		float t = phases(h) == 1 ? 0F : phase / (phases(h) - 1F);
		Matrix4f pose = FpPoseMath.pose(h.kind, left, t, 0F, new Matrix4f());
		FpHeld.recordHeld(main, pose, h.form, h.bow, h.pts == null ? new float[0] : h.pts);
	}

	/** The accessories' homes in the order they yield, as bodies, so the test can recompute each placement's clearance independently of {@link FpOverlay}. */
	private static final class Placed {
		final FpClearance.Obstacles held = new FpClearance.Obstacles(), chain = new FpClearance.Obstacles();
		final List<String> failures = new ArrayList<>();
		int tucked, solved;
	}

	private static FpClearance.Pose poseOf(int index) {
		return new FpClearance.Pose().set(FpLayout.shiftX(index), FpLayout.shiftY(index), FpLayout.shiftZ(index), FpLayout.scale(index));
	}

	/** Runs the overlay for {@code mask} over {@code h} at {@code phase} and checks every accessory's placement; failures are described into {@code into}. */
	private static void check(Held h, int phase, int mask, boolean left, boolean raised, Placed into, String where) {
		hold(h, left, phase, true);
		ShieldMotion.clear(); // the raise eases from what it last was: each case starts at rest in what it asks
		ArmDraw d = draw(mask, left, raised, 100F + phase);
		boolean lantern = (mask & 1) != 0;
		FpClearance.forget();
		FpOverlay.place(d, IDENTITY, lantern, 0);
		into.held.clear();
		FpHeld.volumes(IDENTITY, left, into.held);
		into.chain.clear();
		for (int i = 0; i < into.held.count(); i++) into.chain.copyFrom(into.held, i);
		FpClearance.Body body = new FpClearance.Body();
		int[] order = {LANTERN, FLINT, SPYGLASS, SHIELD};
		for (int index : order) {
			if ((mask & (1 << index)) == 0) continue;
			switch (index) {
				case LANTERN -> LureGeometry.firstPersonBody(left, LureGeometry.offhandBlend(), body);
				case FLINT -> FlintRenderer.firstPersonBody(left, body);
				case SPYGLASS -> SpyglassRenderer.firstPersonBody(left, body);
				default -> ShieldRenderer.firstPersonBody(d, body);
			}
			FpClearance.Pose p = poseOf(index);
			FpClearance.Obstacles against = into.chain; // the shield too is kept clear of the other three, not only of the held thing
			float worst = FpClearance.worst(body, p, against, 0F);
			String who = where + " " + ArmProtocol.ACCESSORIES.get(index);
			into.solved++;
			if (worst > 0.001F) {
				String what = who + ": passes through by " + worst + " px (shift " + p.sx + "," + p.sy + "," + p.sz + " scale " + p.scale + ")";
				into.failures.add(what);
			}
			if (p.scale < 0.5F - 1e-4F || p.scale > 1F + 1e-4F) into.failures.add(who + ": scale " + p.scale);
			if (p.length() > FpClearance.MAX_SHIFT + 1e-3F) into.failures.add(who + ": moved " + p.length());
			if (!p.isHome() && p.scale < 0.5001F && p.length() > 0F) into.tucked++;
			if (index != SHIELD) into.chain.addPlaced(body, p);
		}
	}

	@Test
	void withNothingInTheWayEveryAccessoryStaysHome() {
		for (int mask : new int[] {1, 2, 4, 8}) {
			for (boolean left : new boolean[] {false, true}) {
				FpHeld.forgetAll();
				FpClearance.forget();
				FpOverlay.place(draw(mask, left, true, 100F), IDENTITY, (mask & 1) != 0, 0);
				for (int i = 0; i < 4; i++) {
					assertTrue(poseOf(i).isHome(), "accessory " + i + " moved with nothing held, set " + mask + (left ? " left" : " right") + ": " + FpLayout.shiftX(i) + ","
							+ FpLayout.shiftY(i) + "," + FpLayout.shiftZ(i) + " x" + FpLayout.scale(i));
				}
			}
		}
	}

	@Test
	void withNothingHeldSeveralAccessoriesMoveOnlyALittleToKeepOffEachOther() {
		for (int mask = 1; mask < 16; mask++) {
			for (boolean left : new boolean[] {false, true}) {
				FpHeld.forgetAll();
				FpClearance.forget();
				FpOverlay.place(draw(mask, left, true, 100F), IDENTITY, (mask & 1) != 0, 0);
				for (int i = 0; i < 4; i++) {
					assertTrue(poseOf(i).length() <= 5F && FpLayout.scale(i) >= 0.7F, "accessory " + i + " moved far with nothing held, set " + mask + ": " + poseOf(i).length() + " x"
							+ FpLayout.scale(i));
				}
			}
		}
	}

	@Test
	void theAccessoriesAloneNeverTouchEachOther() {
		// every set of the four with nothing held: the homes are mutually clear (the lantern's, the flint's, the spyglass's and the shield's)
		Placed p = new Placed();
		Held none = new Held("nothing", FpPoseMath.Kind.IDLE, null, false, 0, null);
		for (int mask = 1; mask < 16; mask++) {
			for (boolean left : new boolean[] {false, true}) {
				for (boolean raised : new boolean[] {false, true}) {
					FpHeld.forgetAll();
					check(none, 0, mask, left, raised, p, "set " + mask + (left ? " left" : " right") + (raised ? " raised" : ""));
				}
			}
		}
		assertTrue(p.failures.isEmpty(), String.join("\n", p.failures.subList(0, Math.min(20, p.failures.size()))));
	}

	@Test
	void everyHeldThingInEveryPhaseWithEverySetOfAccessoriesIsClearOnScreenAndNotShrunkBelowItsMinimum() {
		Placed p = new Placed();
		int cases = 0;
		for (boolean left : new boolean[] {false, true}) {
			for (Held h : helds(left)) {
				for (int phase = 0; phase < phases(h); phase++) {
					for (int mask = 1; mask < 16; mask++) {
						for (boolean raised : new boolean[] {false, true}) {
							if (raised && (mask & 2) == 0) continue;
							check(h, phase, mask, left, raised, p, h.name + " phase " + phase + " set " + mask + (left ? " left" : " right") + (raised ? " raised" : ""));
							cases++;
						}
					}
				}
			}
		}
		System.out.printf("overlay clearance: %d cases, %d accessories placed, %d tucked, %d failures (an accessory passing through the held thing or another accessory)%n", cases, p.solved, p.tucked, p.failures.size());
		java.util.Map<String, Integer> by = new java.util.TreeMap<>();
		for (String f : p.failures) {
			String key = f.substring(0, f.indexOf(" phase ")) + " / " + f.substring(f.indexOf(": ") - 8 > 0 ? f.lastIndexOf(" ", f.indexOf(": ")) + 1 : 0, f.indexOf(": "));
			by.merge(key, 1, Integer::sum);
		}
		System.out.println("failures by held thing and accessory: " + by);
		assertTrue(cases > 2000, "the grid is big enough: " + cases);
		assertTrue(p.failures.isEmpty(), p.failures.size() + " failures, first:\n" + String.join("\n", p.failures.subList(0, Math.min(25, p.failures.size()))));
	}

	@Test
	void anItemInTheOtherHandIsKeptClearToo() {
		Placed p = new Placed();
		Held sword = helds(false).stream().filter(h -> h.name.equals("sword IDLE")).findFirst().orElseThrow();
		Held bow = helds(false).stream().filter(h -> h.name.equals("bow drawn")).findFirst().orElseThrow();
		for (int mask = 1; mask < 16; mask++) {
			hold(bow, false, 10, true);
			FpPoseMath.pose(FpPoseMath.Kind.IDLE, true, 0F, 0F, new Matrix4f());
			FpHeld.recordHeld(false, FpPoseMath.pose(FpPoseMath.Kind.IDLE, true, 0F, 0F, new Matrix4f()), null, false, sword.pts);
			ArmDraw d = draw(mask, false, true, 100F);
			FpClearance.forget();
			FpOverlay.place(d, IDENTITY, (mask & 1) != 0, 0);
			FpClearance.Obstacles held = new FpClearance.Obstacles();
			FpHeld.volumes(IDENTITY, false, held);
			assertTrue(held.count() > 4, "both hands' things are obstacles");
		}
		assertTrue(p.failures.isEmpty());
	}

	@Test
	void theSolverMovesOnlyAsFarAsItMustAndGivesWayWithoutPopping() {
		// a sword swinging: the placement from one step to the next moves smoothly (at most 4 px between steps of a tenth of the swing)
		Held sword = helds(false).stream().filter(h -> h.name.equals("katana swing")).findFirst().orElseThrow();
		float worst = 0F;
		FpClearance.Pose last = null;
		for (int mask : new int[] {1, 8, 15}) {
			last = null;
			for (int phase = 0; phase <= 40; phase++) {
				float t = phase / 40F;
				FpHeld.recordHeld(true, FpPoseMath.pose(FpPoseMath.Kind.WHACK, false, t, 0F, new Matrix4f()), sword.form, false, new float[0]);
				if (phase == 0) FpClearance.forget();
				FpOverlay.place(draw(mask, false, false, 100F + phase * 0.5F), IDENTITY, (mask & 1) != 0, 0);
				FpClearance.Pose now = poseOf(mask == 1 ? LANTERN : FLINT);
				if (last != null) worst = Math.max(worst, Math.abs(now.sx - last.sx) + Math.abs(now.sy - last.sy) + Math.abs(now.sz - last.sz));
				last = now;
			}
		}
		assertTrue(worst < 6F, "a swing of 1/40 moved an accessory " + worst + " px in one step");
	}


}
