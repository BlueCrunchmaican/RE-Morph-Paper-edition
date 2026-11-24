package dev.morphreborn.client.render;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.model.animal.sniffer.SnifferModel;
import net.minecraft.client.model.animal.squid.SquidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.monster.guardian.GuardianModel;
import net.minecraft.client.model.monster.ravager.RavagerModel;
import net.minecraft.client.model.monster.vex.VexModel;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * The Graft rules on the REAL vanilla models (2026-11-22), not hand-built boxes: earlier tests used boxes built by hand (a 2.35 cube for the
 * elder guardian, an upright 12 x 16 x 12 mantle for the squid, a synthetic ravager), and each of those passed while the real torso took another branch. The renderer's
 * own transforms are rebuilt here (LivingEntityRenderer.submit: the yaw, the model's (-1, -1, 1) axes, the 1.501 drop), so a body facing any way is measured as the
 * client measures it.
 */
class HybridMorphRealMainsTest {
	/** The pose the renderer starts a model from, the body facing {@code bodyRot} degrees (no pitch). */
	private static PoseStack base(float bodyRot) {
		PoseStack ps = new PoseStack();
		ps.last().pose().rotationY((float) Math.toRadians(180F - bodyRot));
		ps.scale(-1F, -1F, 1F);
		ps.translate(0F, -1.501F, 0F);
		return ps;
	}

	private static Matrix4f unturn(float bodyRot) {
		return new Matrix4f().rotationY((float) Math.toRadians(bodyRot - 180F));
	}

	/** {@code base} seen in the body's own frame (its yaw taken out). */
	private static PoseStack local(PoseStack base, float bodyRot) {
		PoseStack to = new PoseStack();
		to.last().pose().set(unturn(bodyRot)).mul(base.last().pose());
		return to;
	}

	/** The box of the cubes of {@code part} itself (not its children) that {@code keep} takes, with {@code frame} the pose up to the part's parent. */
	private static float[][] box(PoseStack frame, ModelPart part, Predicate<ModelPart.Cube> keep) {
		float[] lo = {1e9F, 1e9F, 1e9F}, hi = {-1e9F, -1e9F, -1e9F};
		part.visit(frame, (pose, path, i, cube) -> {
			if (!path.isEmpty() || !keep.test(cube)) return;
			for (int c = 0; c < 8; c++) {
				Vector3f v = pose.pose().transformPosition((c & 1) == 0 ? cube.minX / 16F : cube.maxX / 16F, (c & 2) == 0 ? cube.minY / 16F : cube.maxY / 16F,
						(c & 4) == 0 ? cube.minZ / 16F : cube.maxZ / 16F, new Vector3f());
				for (int a = 0; a < 3; a++) {
					lo[a] = Math.min(lo[a], v.get(a));
					hi[a] = Math.max(hi[a], v.get(a));
				}
			}
		});
		return new float[][] {lo, hi};
	}

	private static float volume(ModelPart.Cube c) {
		return (c.maxX - c.minX) * (c.maxY - c.minY) * (c.maxZ - c.minZ);
	}

	private static List<ModelPart.Cube> cubesOf(ModelPart part) {
		List<ModelPart.Cube> out = new ArrayList<>();
		part.visit(new PoseStack(), (pose, path, i, cube) -> {
			if (path.isEmpty()) out.add(cube);
		});
		return out;
	}

	@Test
	void aGuardiansTorsoIsTheLyingBlockAndASquidsIsItOnLandAndPitchedAndUprightInTheWater() {
		// the guardian: only the head part's big blocks count (HybridMorph.bigBlockBounds), the 2 pixel plates are under a quarter of the cube's volume
		ModelPart head = GuardianModel.createBodyLayer().bakeRoot().getChild("head");
		float biggest = 0F;
		for (ModelPart.Cube c : cubesOf(head)) biggest = Math.max(biggest, volume(c));
		final float big = biggest;
		float[][] core = box(local(base(0F), 0F), head, c -> volume(c) >= big * 0.25F);
		assertTrue(!HybridMorph.Graft.cubic(core) && !HybridMorph.Graft.blocky(core), "neither window takes the guardian's 12 x 12 x 16 core: " + Arrays.deepToString(core));
		assertTrue(HybridMorph.Graft.lyingBlockBox(core), "the lying block does: " + Arrays.deepToString(core));
		// and the cube rules (the tail from the middle of its rear face, the dorsal held to a share of the height, the longer strip fin cap) run on it too
		Matrix4f id = new Matrix4f();
		float[][] cod = {{-0.0625F, 0F, -0.25F}, {0.0625F, 0.25F, 0.25F}};
		assertTrue(new HybridMorph.Graft(cod, core, id, id, 1F, cod, core, Float.MAX_VALUE, Float.MAX_VALUE, 1F, false, false, false, null, null, 0F, false, true).cubical(),
				"a guardian's core is a cube for those rules: " + Arrays.deepToString(core));
		// the squid, as SquidRenderer.setupRotations poses it: translate, yaw, pitch about x (-90 lying on land), translate; then the model's axes
		ModelPart squid = SquidModel.createBodyLayer().bakeRoot();
		for (float pitch : new float[] {0F, -90F, -45F, -20F}) {
			PoseStack ps = new PoseStack();
			ps.translate(0F, 0.5F, 0F);
			ps.last().pose().rotateY((float) Math.toRadians(180F));
			ps.last().pose().rotateX((float) Math.toRadians(pitch));
			ps.translate(0F, -1.2F, 0F);
			ps.scale(-1F, -1F, 1F);
			ps.translate(0F, -1.501F, 0F);
			float[][] mantle = box(local(ps, 0F), squid.getChild("body"), c -> true);
			assertTrue(HybridMorph.Graft.lyingBlockBox(mantle), "the mantle at pitch " + pitch + " is a block: " + Arrays.deepToString(mantle));
			if (pitch == -90F) assertTrue(!HybridMorph.Graft.blocky(mantle) && !HybridMorph.Graft.cubic(mantle), "and on land it is not one the old windows took: " + Arrays.deepToString(mantle));
		}
	}

	@Test
	void aVexsWingsFoldBackAlongARavagerAndASnifferWhateverWayTheyFace() {
		for (float mainYaw : new float[] {0F, 37F, 150F, 270F}) {
			for (float lentYaw : new float[] {0F, 90F, 200F}) {
				for (String mainName : new String[] {"ravager", "sniffer"}) {
					PoseStack mainBase = base(mainYaw);
					float[][] main;
					if (mainName.equals("ravager")) {
						main = box(local(mainBase, mainYaw), RavagerModel.createBodyLayer().bakeRoot().getChild("body"), c -> true);
					} else {
						ModelPart root = SnifferModel.createBodyLayer().bakeRoot();
						PoseStack ps = local(mainBase, mainYaw);
						root.getChild("bone").translateAndRotate(ps);
						main = box(ps, root.getChild("bone").getChild("body"), c -> true);
					}
					assertTrue(!HybridMorph.Graft.stands(main) && !HybridMorph.Graft.cubic(main), mainName + " is a lying main that is not a cube");
					ModelPart vexRoot = VexModel.createBodyLayer().bakeRoot().getChild("root");
					ModelPart body = vexRoot.getChild("body");
					body.xRot = (float) (Math.PI / 20);
					PoseStack lentBase = base(lentYaw);
					float[][] own = box(local(lentBase, lentYaw), body, c -> true);
					HybridMorph.Graft graft = new HybridMorph.Graft(own, main, unturn(lentYaw), unturn(mainYaw), 1F, own, main, Float.MAX_VALUE, Float.MAX_VALUE, 1F, false, true, false, null,
							null, 0F, true);
					// the size the planar floor gives on a big body: PLANAR_WING_FLOOR of the longest side over the wing's 0.5 block length
					float longest = Math.max(main[1][2] - main[0][2], Math.max(main[1][1] - main[0][1], main[1][0] - main[0][0]));
					float size = LentHeadSize.PLANAR_WING_FLOOR * longest / 0.5F;
					float wingLong = 0.5F * size, wingTall = 5F / 16F * size;
					for (String side : new String[] {"left_wing", "right_wing"}) {
						boolean left = side.startsWith("left");
						ModelPart wing = body.getChild(side);
						wing.xRot = 0.47123888F;
						wing.zRot = left ? -0.47123888F : 0.47123888F;
						// the vex's rest angle: the wing is posed from it, and drawn with the stroke damped about it (HybridMorph.findAppendages)
						float mean = left ? 1.0995574F : -1.0995574F;
						for (float flap : new float[] {0F, 0.07F, -0.07F}) {
							wing.yRot = mean;
							PoseStack meanStack = new PoseStack();
							meanStack.last().pose().set(lentBase.last().pose());
							vexRoot.translateAndRotate(meanStack);
							body.translateAndRotate(meanStack);
							wing.translateAndRotate(meanStack);
							Matrix4f meanPose = new Matrix4f(meanStack.last().pose());
							Vector3f joint = meanPose.transformPosition(new Vector3f());
							wing.yRot = mean + flap;
							PoseStack drawn = new PoseStack();
							drawn.last().pose().set(lentBase.last().pose());
							vexRoot.translateAndRotate(drawn);
							body.translateAndRotate(drawn);
							wing.translateAndRotate(drawn);
							Matrix4f pose = new Matrix4f(drawn.last().pose());
							Matrix4f move = graft.foldedWing(joint, meanPose, side, size, HybridMorph.Graft.VEX_LIFT_ROLL);
							Matrix4f toMain = new Matrix4f(unturn(mainYaw)).mul(move).mul(pose);
							String what = mainName + ", main facing " + mainYaw + ", lent facing " + lentYaw + ", " + side + ", flap " + flap;
							Vector3f normal = toMain.transformDirection(new Vector3f(1F, 0F, 0F)).normalize();
							assertTrue(Math.abs(normal.x) > 0.75F, "its face looks out to the side: " + normal + " " + what);
							float[] lo = {1e9F, 1e9F, 1e9F}, hi = {-1e9F, -1e9F, -1e9F};
							for (int c = 0; c < 4; c++) {
								Vector3f v = toMain.transformPosition(0F, (c & 1) == 0 ? 0F : 5F / 16F, (c & 2) == 0 ? 0F : 8F / 16F, new Vector3f());
								for (int a = 0; a < 3; a++) {
									lo[a] = Math.min(lo[a], v.get(a));
									hi[a] = Math.max(hi[a], v.get(a));
								}
							}
							float rootY = toMain.transformPosition(new Vector3f()).y;
							// none of it in the body: the whole sheet is outside the flank it is on (the damped stroke, 4 degrees, swings its trailing edge in by up to 0.07 of its length)
							if (left) assertTrue(hi[0] <= main[0][0] + 0.08F * wingLong, "all of it outside the left flank: " + hi[0] + " against " + main[0][0] + ", " + what);
							else assertTrue(lo[0] >= main[1][0] - 0.08F * wingLong, "all of it outside the right flank: " + lo[0] + " against " + main[1][0] + ", " + what);
							// it runs back along the body (its 8 pixel edge) and up from its root (its 5 pixel edge), never down into the flank
							assertTrue(hi[2] - lo[2] > 0.8F * wingLong, "it runs back along the body: " + (hi[2] - lo[2]) + " of " + wingLong + ", " + what);
							assertTrue(lo[1] >= rootY - 0.05F, "and up from its root, not down (the stroke dips its trailing edge by a few centimetres): " + lo[1] + " against " + rootY + ", " + what);
							assertTrue(hi[1] - lo[1] > 0.5F * wingTall, "it stands up: " + (hi[1] - lo[1]) + " of " + wingTall + ", " + what);
							assertTrue(rootY >= main[1][1] - 0.2F * (main[1][1] - main[0][1]) - 0.01F, "rooted in the top fifth of the flank: " + rootY + ", " + what);
							// and not above its back line (the sheet would then touch nothing of the flank), nor off the end of the body, nor floating off the side
							assertTrue(rootY <= main[1][1] + 1e-3F, "rooted on the flank, not over the back: " + rootY + " against " + main[1][1] + ", " + what);
							assertTrue(lo[2] >= main[0][2] - 0.08F * wingLong && hi[2] <= main[1][2] + 0.08F * wingLong, "within the body's length: " + lo[2] + " to " + hi[2] + " on " + main[0][2] + " to "
									+ main[1][2] + ", " + what);
							if (left) assertTrue(hi[0] >= main[0][0] - 0.1F - 0.08F * wingLong, "close to the left flank: " + hi[0] + " against " + main[0][0] + ", " + what);
							else assertTrue(lo[0] <= main[1][0] + 0.1F + 0.08F * wingLong, "close to the right flank: " + lo[0] + " against " + main[1][0] + ", " + what);
						}
					}
				}
			}
		}
	}
}
