package dev.morphreborn.client.arm.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The spyglass accessory's eye stalk (2026-11-16): its quad budget in both views and every level of detail, the build and extend
 * timelines, where the eye looks (the owner's view brought into the arm's space), the lids, and that everything it writes is a finite
 * number. Also, with {@code SPYGLASS_PREVIEW_DIR} set, writes pictures of it (there is no way to see the game from a test).
 */
class SpyglassRendererTest {
	private static final ArmLayer[] LAYERS = {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW};

	@AfterEach
	void reset() {
		SpyglassMotion.source(null);
		SpyglassMotion.clear();
	}

	/** A spyglass draw: {@code age} the accessory's draw age, {@code zooming} its arg, {@code lod}, {@code ctx}, which hand holds the tool. */
	static ArmDraw draw(float age, boolean zooming, int lod, int ctx, boolean toolLeft, float time) {
		return draw(age, zooming, lod, ctx, toolLeft, time, "");
	}

	static ArmDraw draw(float age, boolean zooming, int lod, int ctx, boolean toolLeft, float time, String form) {
		float[] ages = new float[ArmProtocol.ACCESSORIES.size()];
		int index = ArmProtocol.accessoryIndex(ArmProtocol.SPYGLASS);
		ages[index] = age;
		int bit = 1 << index;
		int args = zooming ? 1 << (4 * index) : 0;
		return new ArmDraw(form, 2, lod, ctx, toolLeft, false, false, time, 7, form.isEmpty() ? ArmProtocol.STATE_NONE : ArmProtocol.STATE_FORMED, 1F, "", 0F, 0F, false, 1F,
				ArmDraw.FULL, 0F, ArmDraw.NEVER, ArmDraw.NO_SPECIAL, ArmDraw.NEVER, ArmDraw.NEVER, new float[0], 0, false, 0F, 0F, true, true, false, null, 0F, -1F, 0F, null,
				0xF000F0, 1F, bit, args, ages, false);
	}

	/** A vertex consumer that only counts, so the allocation test sees the renderer's own and not the recorder's. */
	static final class Counter implements VertexConsumer {
		int vertices;

		@Override
		public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
			vertices++;
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			throw new UnsupportedOperationException("SkinMesh writes whole vertices");
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			return this;
		}

		@Override
		public VertexConsumer setColor(int color) {
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv3(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			return this;
		}
	}

	/** The quads {@code d} draws, all layers, in either view; the vertices (4 a quad, x y z) land in {@code into} when it is not null. */
	static int quads(ArmDraw d, boolean firstPerson, List<float[]> into) {
		SpyglassRenderer r = new SpyglassRenderer();
		int total = 0;
		for (ArmLayer layer : LAYERS) {
			if (layer == ArmLayer.GLOW && !d.glow()) continue;
			ArmBudgetTest.Recorder rec = new ArmBudgetTest.Recorder();
			rec.keep = into != null;
			SkinMesh m = SkinMesh.begin(new PoseStack().last(), rec, 0xF000F0, 0);
			Loft.pulseFrom(null, 0F);
			if (firstPerson) r.emitFirstPerson(d, layer, m);
			else r.emitThirdPerson(d, layer, m);
			assertEquals(rec.quads(), m.quads(), "quads counted twice or lost");
			total += rec.quads();
			if (into != null) into.addAll(rec.kept);
		}
		return total;
	}

	// ------------------------------------------------------------------ the timelines

	@Test
	void builtGrowsAndDrawsBack() {
		assertEquals(0F, SpyglassMotion.built(0F), 1e-6F);
		assertEquals(1F, SpyglassMotion.built(SpyglassMotion.BUILD_TICKS), 1e-6F);
		assertEquals(1F, SpyglassMotion.built(500F), 1e-6F, "a late viewer sees it built");
		float last = 0F;
		for (float t = 0F; t <= SpyglassMotion.BUILD_TICKS; t += 0.5F) {
			float b = SpyglassMotion.built(t);
			assertTrue(b >= last - 1e-6F, "growth never goes back");
			last = b;
		}
		// going out: the build backwards over OUT_TICKS from the full eye
		assertEquals(1F, SpyglassMotion.built(-1F), 1e-6F);
		assertEquals(0F, SpyglassMotion.built(-1F - SpyglassMotion.OUT_TICKS), 1e-6F);
		assertEquals(0F, SpyglassMotion.built(-1000F), 1e-6F);
		last = 1F;
		for (float t = 0F; t <= SpyglassMotion.OUT_TICKS; t += 0.5F) {
			float b = SpyglassMotion.built(-1F - t);
			assertTrue(b <= last + 1e-6F, "retraction never grows");
			last = b;
		}
		assertEquals(SpyglassMotion.OUT_TICKS, dev.morphreborn.client.arm.ArmState.ACCESSORY_OUT_TICKS, "the eye draws back as long as the state keeps it going out");
	}

	@Test
	void extendEasesOutAndBackAndAgreesWithinAFrame() {
		float t = 100F;
		assertEquals(0F, SpyglassMotion.extend(1, t, false), 1e-6F, "first seen at rest: at rest");
		assertEquals(0F, SpyglassMotion.extend(1, t + 1F, false), 1e-6F);
		// zooming: out over EXTEND_TICKS, the same answer for the same time
		float a = SpyglassMotion.extend(1, t + 2F, true);
		assertEquals(0F, a, 1e-6F, "the move starts now");
		float mid = SpyglassMotion.extend(1, t + 2F + SpyglassMotion.EXTEND_TICKS / 2F, true);
		assertTrue(mid > 0.3F && mid < 0.7F, "half way at half the time: " + mid);
		assertEquals(mid, SpyglassMotion.extend(1, t + 2F + SpyglassMotion.EXTEND_TICKS / 2F, true), 1e-6F, "the same time, the same answer");
		assertEquals(1F, SpyglassMotion.extend(1, t + 2F + SpyglassMotion.EXTEND_TICKS, true), 1e-6F);
		float last = 1F;
		float back = t + 20F;
		assertEquals(1F, SpyglassMotion.extend(1, back, false), 1e-6F, "the move back starts from where it was");
		for (float dt = 0F; dt <= SpyglassMotion.RETRACT_TICKS; dt += 0.5F) {
			float e = SpyglassMotion.extend(1, back + dt, false);
			assertTrue(e <= last + 1e-6F, "coming back never goes out");
			last = e;
		}
		assertEquals(0F, last, 1e-6F);
		// another player's is its own
		assertEquals(0F, SpyglassMotion.extend(2, back + 1F, false), 1e-6F);
		// a let go partway turns round from where it had got to
		SpyglassMotion.extend(3, 10F, false);
		SpyglassMotion.extend(3, 11F, true);
		float partway = SpyglassMotion.extend(3, 14F, true);
		float turned = SpyglassMotion.extend(3, 14F, false);
		assertEquals(partway, turned, 1e-6F, "turning round does not jump");
	}

	@Test
	void extendTableKeepsOnWhenManyPlayersAreSeen() {
		for (int id = 0; id < 100; id++) SpyglassMotion.extend(id, 10F + id, id % 2 == 0);
		float e = SpyglassMotion.extend(99, 200F, true);
		assertTrue(e >= 0F && e <= 1F);
	}

	// ------------------------------------------------------------------ where the eye looks

	/** A source that says where everyone looks, for the aim. */
	static final class Fixed implements SpyglassMotion.Source {
		float yaw, pitch;
		boolean world = true, own;

		@Override
		public boolean ownZoom(int entityId) {
			return own;
		}

		@Override
		public boolean look(int entityId, float pt, float[] out) {
			out[0] = yaw;
			out[1] = pitch;
			return true;
		}

		@Override
		public boolean worldPass() {
			return world;
		}
	}

	@Test
	void worldDirectionsAreMinecrafts() {
		Vector3f v = new Vector3f();
		SpyglassMotion.toArm(null, 0F, 0F, v);
		assertEquals(0F, v.x, 1e-5F);
		assertEquals(0F, v.y, 1e-5F);
		assertEquals(1F, v.z, 1e-5F, "yaw 0 looks south, +Z");
		SpyglassMotion.toArm(null, 90F, 0F, v);
		assertEquals(-1F, v.x, 1e-5F, "yaw 90 looks west, -X");
		SpyglassMotion.toArm(null, 0F, 90F, v);
		assertEquals(-1F, v.y, 1e-5F, "pitch 90 looks straight down: -Y in the world, whose Y is up");
		SpyglassMotion.toArm(null, -135F, -30F, v);
		assertEquals(1F, v.length(), 1e-5F);
	}

	@Test
	void aWorldDirectionIsBroughtIntoTheArmsSpaceAndBack() {
		// a pose that turns the arm's space a quarter and a bit, scaled by 1/16 as the arm's px to blocks are
		Matrix4f pose = new Matrix4f().rotateY(1.1F).rotateX(-0.4F).rotateZ(0.2F).scale(1F / 16F);
		Vector3f arm = new Vector3f(), world = new Vector3f(), expected = new Vector3f();
		for (float yaw = -170F; yaw <= 170F; yaw += 37F) {
			for (float pitch = -80F; pitch <= 80F; pitch += 31F) {
				SpyglassMotion.toArm(pose, yaw, pitch, arm);
				assertEquals(1F, arm.length(), 1e-4F);
				// back out through the pose it must be the world direction again
				pose.transformDirection(arm, world).normalize();
				SpyglassMotion.toArm(null, yaw, pitch, expected);
				assertEquals(expected.x, world.x, 1e-4F);
				assertEquals(expected.y, world.y, 1e-4F);
				assertEquals(expected.z, world.z, 1e-4F);
			}
		}
		// an unusable pose leaves the arm's own forward
		SpyglassMotion.toArm(new Matrix4f().zero(), 10F, 10F, arm);
		assertEquals(-1F, arm.z, 1e-6F);
	}

	@Test
	void theEyeFollowsTheOwnersViewLazilyAndThenLocksOnWhenZooming() {
		Fixed f = new Fixed();
		SpyglassMotion.source(f);
		float[] out = new float[2];
		f.yaw = 0F;
		f.pitch = 0F;
		assertTrue(SpyglassMotion.aim(5, 100F, 1F, out));
		assertEquals(0F, out[0], 1e-4F, "a first sighting looks where they look");
		// they turn: the eye lags at rest, and is almost there in a tick while zooming
		f.yaw = 60F;
		SpyglassMotion.aim(5, 101F, 1F, out);
		float zoom = out[0];
		SpyglassMotion.clear();
		f.yaw = 0F;
		SpyglassMotion.aim(5, 100F, 1F, out);
		f.yaw = 60F;
		SpyglassMotion.aim(5, 101F, 0F, out);
		float rest = out[0] - SpyglassMotion.wanderYaw(5, 101F);
		assertTrue(zoom > rest + 1F, "zooming follows closer than resting: " + zoom + " vs " + rest);
		assertTrue(zoom < 60F && zoom > 30F, "but is not instant: " + zoom);
		// and it gets there
		for (float t = 102F; t < 140F; t += 1F) SpyglassMotion.aim(5, t, 1F, out);
		assertEquals(60F, out[0], 0.5F);
		// the same time, the same answer (the three layers of a draw)
		float a = out[0];
		SpyglassMotion.aim(5, 139F, 1F, out);
		assertEquals(a, out[0], 1e-6F);
	}

	@Test
	void theEyeTurnsTheShortWayRoundAndSurvivesTheClockWrapping() {
		Fixed f = new Fixed();
		SpyglassMotion.source(f);
		float[] out = new float[2];
		f.yaw = 170F;
		SpyglassMotion.aim(6, 10F, 1F, out);
		f.yaw = -170F;
		for (float t = 11F; t < 14F; t += 1F) {
			SpyglassMotion.aim(6, t, 1F, out);
			float shown = SpyglassMotion.wrapDegrees(out[0]);
			assertTrue(Math.abs(shown) > 160F, "through 180, not back through 0: " + shown);
		}
		// the hourly clock wraps back to near 0: no smoothing across it, and nothing breaks
		SpyglassMotion.aim(6, 71999F, 1F, out);
		SpyglassMotion.aim(6, 1F, 1F, out);
		assertTrue(Float.isFinite(out[0]) && Float.isFinite(out[1]));
	}

	@Test
	void withNothingToReadTheEyeWandersFromAhead() {
		float[] out = new float[2];
		assertFalse(SpyglassMotion.aim(8, 100F, 0F, out), "no source: it looks ahead");
		assertEquals(SpyglassMotion.wanderYaw(8, 100F), out[0], 1e-6F);
		Fixed f = new Fixed();
		f.world = false;
		SpyglassMotion.source(f);
		assertFalse(SpyglassMotion.aim(8, 100F, 0F, out), "not the world pass (the doll): ahead too");
		// the wander is bounded, smooth, and gone while zooming
		float prev = SpyglassMotion.wanderYaw(8, 0F);
		float max = 0F;
		for (float t = 0.25F; t < 600F; t += 0.25F) {
			float y = SpyglassMotion.wanderYaw(8, t), p = SpyglassMotion.wanderPitch(8, t);
			assertTrue(Math.abs(y) <= SpyglassMotion.WANDER_YAW + 1e-3F && Math.abs(p) <= SpyglassMotion.WANDER_PITCH + 1e-3F, "bounded");
			assertTrue(Math.abs(y - prev) < 4F, "no jumps: " + (y - prev) + " at " + t);
			prev = y;
			max = Math.max(max, Math.abs(y));
		}
		assertTrue(max > 5F, "it does move");
		SpyglassMotion.aim(8, 300F, 1F, out);
		assertEquals(0F, out[0], 1e-6F, "no wander while zooming");
		assertEquals(0F, out[1], 1e-6F);
	}

	// ------------------------------------------------------------------ the lids

	@Test
	void lidsAreHeavyAtRestWideOpenZoomingAndBlinkNowAndThen() {
		int shut = 0;
		for (float t = 0F; t < 1200F; t += 0.5F) {
			float c = SpyglassMotion.cover(11, t, 0F);
			assertTrue(c >= 0F && c <= 1F);
			float zoom = SpyglassMotion.cover(11, t, 1F);
			assertEquals(0F, zoom, 1e-6F, "wide open while zooming, and no blink");
			if (SpyglassMotion.blink(11, t) > 0.8F) shut++;
			assertTrue(c >= SpyglassMotion.REST_COVER - 0.07F, "heavy at rest: " + c);
		}
		assertTrue(shut >= 4, "it blinks: " + shut);
		// blinks are not on a metronome, and each player has their own
		float first = -1F, second = -1F;
		for (float t = 0F; t < 400F && second < 0F; t += 0.25F) {
			if (SpyglassMotion.blink(11, t) > 0.8F) {
				if (first < 0F) first = t;
				else if (t - first > 20F) second = t;
			}
		}
		assertTrue(first >= 0F && second > first);
		float other = -1F;
		for (float t = 0F; t < 400F && other < 0F; t += 0.25F) {
			if (SpyglassMotion.blink(12, t) > 0.8F) other = t;
		}
		assertNotEquals(first, other, "each player has their own beat");
	}

	// ------------------------------------------------------------------ the drawing

	@Test
	void drawsNothingWhenOffOrNotYetBuiltOrNotItsLayer() {
		ArmDraw off = new ArmDraw("", 2, 0, ArmDraw.CTX_TP, false, false, false, 100F, 7, ArmProtocol.STATE_NONE, 1F, "", 0F, 0F, false, 1F, ArmDraw.FULL, 0F, ArmDraw.NEVER,
				ArmDraw.NO_SPECIAL, ArmDraw.NEVER, ArmDraw.NEVER, new float[0], 0, false, 0F, 0F, true, true, false, null, 0F, -1F, 0F, null, 0xF000F0);
		assertEquals(0, quads(off, false, null), "no accessory, no eye");
		assertEquals(0, quads(draw(0F, false, 0, ArmDraw.CTX_TP, false, 100F), false, null), "the first tick of the build is nothing yet");
		assertEquals(0, quads(draw(-1F - SpyglassMotion.OUT_TICKS, false, 0, ArmDraw.CTX_TP, false, 100F), false, null), "drawn all the way back");
		SpyglassRenderer r = new SpyglassRenderer();
		ArmBudgetTest.Recorder rec = new ArmBudgetTest.Recorder();
		r.emitThirdPerson(draw(500F, false, 0, ArmDraw.CTX_TP, false, 100F), ArmLayer.SENSE, SkinMesh.begin(new PoseStack().last(), rec, 0xF000F0, 0));
		assertEquals(0, rec.quads(), "the sense layer is not drawn");
		assertEquals(ArmProtocol.SPYGLASS, r.id());
		assertFalse(r.offArm(), "on the arm that holds the tool");
	}

	@Test
	void quadBudgetInBothViewsAndEveryLevelOfDetail() {
		int worstTp = 0, worstFp = 0;
		for (float t = 0F; t < 600F; t += 3.7F) {
			for (boolean zoom : new boolean[] {false, true}) {
				for (boolean left : new boolean[] {false, true}) {
					worstTp = Math.max(worstTp, quads(draw(500F, zoom, 0, ArmDraw.CTX_TP, left, t), false, null));
					worstFp = Math.max(worstFp, quads(draw(500F, zoom, 0, ArmDraw.CTX_FP, left, t), true, null));
				}
			}
		}
		System.out.printf("spyglass eye: third person %d, first person %d%n", worstTp, worstFp);
		assertTrue(worstTp <= 420, "third person: " + worstTp + " quads, over 420");
		assertTrue(worstFp <= 420, "first person: " + worstFp + " quads, over 420");
		assertTrue(worstTp >= 120, "third person: only " + worstTp + " quads; the detail is missing");
		int lod1 = 0, lod2 = 0, lod3 = 0, icon = 0;
		for (float t = 0F; t < 600F; t += 3.7F) {
			lod1 = Math.max(lod1, quads(draw(500F, true, 1, ArmDraw.CTX_TP, false, t), false, null));
			lod2 = Math.max(lod2, quads(draw(500F, true, 2, ArmDraw.CTX_TP, false, t), false, null));
			lod3 = Math.max(lod3, quads(draw(500F, true, 3, ArmDraw.CTX_TP, false, t), false, null));
			icon = Math.max(icon, quads(draw(500F, false, ArmDraw.LOD_ICON, ArmDraw.CTX_GUI, false, t), false, null));
		}
		System.out.printf("spyglass eye: LOD1 %d, LOD2 %d, LOD3 %d, icon %d%n", lod1, lod2, lod3, icon);
		assertTrue(lod1 <= 130, "LOD 1: " + lod1);
		assertTrue(lod2 <= 40, "LOD 2: " + lod2);
		assertTrue(lod3 <= 16, "LOD 3: " + lod3);
		assertTrue(icon <= 130, "icon: " + icon);
		assertTrue(lod3 < lod2 && lod2 < lod1 && lod1 < worstTp, "each level of detail should cost less than the one before");
	}

	@Test
	void growsOutAndDrawsBackWithoutEverGrowingWhileDrawingBack() {
		int last = 0;
		for (float age = 0F; age <= SpyglassMotion.BUILD_TICKS + 2F; age += 1F) {
			SpyglassMotion.clear();
			int q = quads(draw(age, false, 0, ArmDraw.CTX_TP, false, 100F), false, null);
			assertTrue(q >= last - 40, "growing: " + q + " after " + last + " at age " + age);
			last = Math.max(last, q);
		}
		assertTrue(last > 100, "built: " + last);
		int shrinking = last;
		for (float t = 0F; t <= SpyglassMotion.OUT_TICKS; t += 1F) {
			SpyglassMotion.clear();
			int q = quads(draw(-1F - t, false, 0, ArmDraw.CTX_TP, false, 100F), false, null);
			assertTrue(q <= shrinking + 40, "drawing back: " + q + " after " + shrinking);
			shrinking = Math.max(0, Math.min(shrinking, q));
		}
		assertEquals(0, shrinking);
	}

	@Test
	void everyVertexIsFiniteAcrossViewsHandsLooksAndTime() {
		Fixed f = new Fixed();
		SpyglassMotion.source(f);
		for (float t = 0F; t < 300F; t += 7.3F) {
			f.yaw = t * 3F;
			f.pitch = (t % 160F) - 80F;
			for (boolean zoom : new boolean[] {false, true}) {
				for (boolean left : new boolean[] {false, true}) {
					for (String form : new String[] {"", ArmProtocol.KATANA}) {
						List<float[]> kept = new ArrayList<>();
						// the Recorder checks every value is finite and every normal unit length
						quads(draw(500F, zoom, 0, ArmDraw.CTX_TP, left, t, form), false, kept);
						quads(draw(500F, zoom, 0, ArmDraw.CTX_FP, left, t, form), true, kept);
						assertFalse(kept.isEmpty() && !zoom);
					}
				}
			}
		}
	}

	@Test
	void zoomingReachesTheEyeOutForwardAndUpAndFirstPersonHidesIt() {
		SpyglassMotion.clear();
		List<float[]> rest = new ArrayList<>(), out = new ArrayList<>();
		quads(draw(500F, false, 0, ArmDraw.CTX_TP, false, 100F), false, rest);
		SpyglassMotion.clear();
		quads(draw(500F, true, 0, ArmDraw.CTX_TP, false, 100F), false, out); // a first sighting zooming is already out
		float[] restBox = bounds(rest), outBox = bounds(out);
		assertTrue(outBox[2] < restBox[2] - 2F, "the eye is further forward (-Z) zooming: " + outBox[2] + " vs " + restBox[2]);
		assertTrue(outBox[1] < restBox[1] - 1F, "and higher (-Y is up): " + outBox[1] + " vs " + restBox[1]);
		// first person: out of sight once the zoom has hidden the hands
		SpyglassMotion.clear();
		assertTrue(quads(draw(500F, false, 0, ArmDraw.CTX_FP, false, 100F), true, null) > 60, "first person shows the eye at rest");
		SpyglassMotion.clear();
		assertEquals(0, quads(draw(500F, true, 0, ArmDraw.CTX_FP, false, 100F), true, null), "and not while zooming");
	}

	@Test
	void ownZoomExtendsTheStalkBeforeTheServerSaysSo() {
		Fixed f = new Fixed();
		f.world = false;
		f.own = true;
		SpyglassMotion.source(f);
		f.own = false;
		SpyglassMotion.clear();
		float[] rest = bounds(capture(draw(500F, false, 0, ArmDraw.CTX_TP, false, 100F)));
		f.own = true;
		SpyglassMotion.clear();
		float[] zoom = bounds(capture(draw(500F, false, 0, ArmDraw.CTX_TP, false, 100F)));
		assertTrue(zoom[2] < rest[2] - 2F, "the key alone reaches it out");
	}

	@Test
	void leftHandedMirrorsTheStalkAcrossTheBody() {
		float[] right = bounds(capture(draw(500F, false, 0, ArmDraw.CTX_TP, false, 100F)));
		float[] left = bounds(capture(draw(500F, false, 0, ArmDraw.CTX_TP, true, 100F)));
		float rightMid = (right[0] + right[3]) / 2F, leftMid = (left[0] + left[3]) / 2F;
		assertTrue(rightMid < -1F, "the right arm's eye is on the -X side: " + rightMid);
		assertTrue(leftMid > 1F, "the left arm's on the +X side: " + leftMid);
		assertEquals(-rightMid, leftMid, 0.3F, "mirrored about the body's middle");
	}

	/** Allocation-free per frame (my rule for the arm's geometry): after warming up, a draw of every layer makes no garbage. */
	@Test
	void aDrawAllocatesNothing() {
		java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
		if (!(bean instanceof com.sun.management.ThreadMXBean sun) || !sun.isThreadAllocatedMemorySupported()) return;
		SpyglassRenderer r = new SpyglassRenderer();
		PoseStack.Pose pose = new PoseStack().last();
		Counter counter = new Counter();
		ArmDraw tp = draw(500F, true, 0, ArmDraw.CTX_TP, false, 100F, ArmProtocol.KATANA), fp = draw(500F, false, 0, ArmDraw.CTX_FP, false, 100F);
		for (int i = 0; i < 300; i++) drawAll(r, tp, fp, pose, counter);
		long id = Thread.currentThread().threadId();
		long before = sun.getThreadAllocatedBytes(id);
		for (int i = 0; i < 100; i++) drawAll(r, tp, fp, pose, counter);
		long used = sun.getThreadAllocatedBytes(id) - before;
		assertTrue(used < 4096, "a draw allocated " + used + " bytes over 100 draws (should be none per frame)");
		assertTrue(counter.vertices > 0);
	}

	private static void drawAll(SpyglassRenderer r, ArmDraw tp, ArmDraw fp, PoseStack.Pose pose, Counter counter) {
		for (ArmLayer layer : LAYERS) {
			r.emitThirdPerson(tp, layer, SkinMesh.begin(pose, counter, 0xF000F0, 0));
			r.emitFirstPerson(fp, layer, SkinMesh.begin(pose, counter, 0xF000F0, 0));
		}
	}

	private static List<float[]> capture(ArmDraw d) {
		List<float[]> kept = new ArrayList<>();
		quads(d, d.ctx() == ArmDraw.CTX_FP, kept);
		return kept;
	}

	/** The box round the vertices: minX, minY, minZ, maxX, maxY, maxZ. */
	private static float[] bounds(List<float[]> vertices) {
		float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (float[] v : vertices) {
			for (int a = 0; a < 3; a++) {
				b[a] = Math.min(b[a], v[a]);
				b[a + 3] = Math.max(b[a + 3], v[a]);
			}
		}
		return b;
	}

	@Test
	void geometryIsFoundByTheRegistryAndRestPointIsOverTheShoulder() {
		assertTrue(ArmGeometry.accessory(ArmProtocol.SPYGLASS) instanceof SpyglassRenderer);
		Vector3f p = new SpyglassRenderer().restPoint(false, false, new Vector3f());
		assertTrue(p != null && p.y < -5F, "over the shoulder");
		assertTrue(p.x < 0F, "on the right arm's outer side");
		Vector3f q = new SpyglassRenderer().restPoint(false, true, new Vector3f());
		assertEquals(-p.x, q.x, 1e-4F, "mirrored about the body's middle");
	}

	/** With SPYGLASS_PREVIEW_DIR set: pictures of the eye resting, zooming, from the front, side and above, and in first person. */
	@Test
	void writePreviewPictures() throws Exception {
		String dir = System.getenv("SPYGLASS_PREVIEW_DIR");
		if (dir == null || dir.isBlank()) return;
		SpyglassPreview.write(java.nio.file.Path.of(dir));
	}
}
