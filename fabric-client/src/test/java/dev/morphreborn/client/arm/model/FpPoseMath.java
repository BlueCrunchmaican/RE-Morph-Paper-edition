package dev.morphreborn.client.arm.model;

import net.minecraft.client.resources.model.cuboid.ItemTransform;
import org.joml.Matrix4f;

/**
 * Test only: vanilla's first-person item pose chain ({@code FirstPersonHandsAndItemsRenderer.submitArmWithItem} and its helpers, read from the
 * reference source of 26.3) re-implemented with joml, so the clearance tests can pose a held item through every phase of every animation (the game
 * hands the real pose over at runtime, {@link FpHeld}). Poses are in blocks, in the hand pass's root space, with the item's own display transform
 * not yet applied (as vanilla's stack has it when it submits the item). Not a test of anything itself; {@code FpPoseMathTest} checks a few known
 * numbers.
 */
final class FpPoseMath {
	private FpPoseMath() {
	}

	/** One of vanilla's holds. {@code t} below is the phase: the swing's progress, the use's progress. */
	enum Kind {
		IDLE, WHACK, EAT, BLOCK_SHIELD, BLOCK_OTHER, BOW, TRIDENT, CROSSBOW_CHARGE, CROSSBOW_CHARGED, AUTOSPIN, BRUSH, EQUIP
	}

	private static final double PI = Math.PI;

	private static Matrix4f rot(Matrix4f m, char axis, float deg) {
		float r = (float) Math.toRadians(deg);
		return switch (axis) {
			case 'X' -> m.rotateX(r);
			case 'Y' -> m.rotateY(r);
			default -> m.rotateZ(r);
		};
	}

	private static float sin(double x) {
		return (float) Math.sin(x);
	}

	private static void armTransform(Matrix4f m, int inv, float inverseArmHeight) {
		m.translate(inv * 0.56F, -0.52F + inverseArmHeight * -0.6F, -0.72F);
	}

	private static void attackTransform(Matrix4f m, int inv, float a) {
		rot(m, 'Y', inv * (45F + sin(a * a * PI) * -20F));
		float xz = sin(Math.sqrt(a) * PI);
		rot(m, 'Z', inv * xz * -20F);
		rot(m, 'X', xz * -80F);
		rot(m, 'Y', inv * -45F);
	}

	private static void swingArm(Matrix4f m, int inv, float a) {
		float x = -0.4F * sin(Math.sqrt(a) * PI), y = 0.2F * sin(Math.sqrt(a) * (PI * 2)), z = -0.2F * sin(a * PI);
		m.translate(inv * x, y, z);
		attackTransform(m, inv, a);
	}

	private static void shake(Matrix4f m, float timeHeld, float power) {
		if (power > 0.1F) {
			float s = sin((timeHeld - 0.1F) * 1.3F) * (power - 0.1F);
			m.translate(s * 0F, s * 0.004F, s * 0F);
		}
	}

	/**
	 * The pose of the item in the hand of the arm {@code leftArm}, in hold {@code kind} at phase {@code t} (0..1) with the hand lowered by
	 * {@code inverseArmHeight} (0 raised, 1 fully down: an equip or a swap).
	 */
	static Matrix4f pose(Kind kind, boolean leftArm, float t, float inverseArmHeight, Matrix4f out) {
		int inv = leftArm ? -1 : 1;
		out.identity();
		switch (kind) {
			case IDLE, EQUIP -> armTransform(out, inv, kind == Kind.EQUIP ? Math.max(inverseArmHeight, 1F - t) : inverseArmHeight);
			case WHACK -> {
				armTransform(out, inv, inverseArmHeight);
				swingArm(out, inv, t);
			}
			case EAT -> {
				armTransform(out, inv, inverseArmHeight);
				float curr = t * 32F, u = t;
				if (u < 0.8F) out.translate(0F, Math.abs((float) Math.cos(curr / 4F * PI) * 0.1F), 0F);
				float jiggle = 1F - (float) Math.pow(u, 27.0);
				out.translate(jiggle * 0.6F * inv, jiggle * -0.5F, 0F);
				rot(out, 'Y', inv * jiggle * 90F);
				rot(out, 'X', jiggle * 10F);
				rot(out, 'Z', inv * jiggle * 30F);
				armTransform(out, inv, inverseArmHeight);
			}
			case BLOCK_SHIELD -> armTransform(out, inv, inverseArmHeight);
			case BLOCK_OTHER -> {
				armTransform(out, inv, inverseArmHeight);
				out.translate(inv * -0.14142136F, 0.08F, 0.14142136F);
				rot(out, 'X', -102.25F);
				rot(out, 'Y', inv * 13.365F);
				rot(out, 'Z', inv * 78.05F);
			}
			case BOW -> {
				armTransform(out, inv, inverseArmHeight);
				out.translate(inv * -0.2785682F, 0.18344387F, 0.15731531F);
				rot(out, 'X', -13.935F);
				rot(out, 'Y', inv * 35.3F);
				rot(out, 'Z', inv * -9.785F);
				float timeHeld = t * 20F;
				float power = timeHeld / 20F;
				power = (power * power + power * 2F) / 3F;
				power = Math.min(power, 1F);
				shake(out, timeHeld, power);
				out.translate(0F, 0F, power * 0.04F);
				out.scale(1F, 1F, 1F + power * 0.2F);
				rot(out, 'Y', -inv * 45F);
			}
			case TRIDENT -> {
				armTransform(out, inv, inverseArmHeight);
				out.translate(inv * -0.5F, 0.7F, 0.1F);
				rot(out, 'X', -55F);
				rot(out, 'Y', inv * 35.3F);
				rot(out, 'Z', inv * -9.785F);
				float timeHeld = t * 10F, power = Math.min(timeHeld / 10F, 1F);
				shake(out, timeHeld, power);
				out.translate(0F, 0F, power * 0.2F);
				out.scale(1F, 1F, 1F + power * 0.2F);
				rot(out, 'Y', -inv * 45F);
			}
			case CROSSBOW_CHARGE -> {
				armTransform(out, inv, inverseArmHeight);
				out.translate(inv * -0.4785682F, -0.094387F, 0.05731531F);
				rot(out, 'X', -11.935F);
				rot(out, 'Y', inv * 65.3F);
				rot(out, 'Z', inv * -9.785F);
				float timeHeld = t * 25F, power = Math.min(timeHeld / 25F, 1F);
				shake(out, timeHeld, power);
				out.translate(0F, 0F, power * 0.04F);
				out.scale(1F, 1F, 1F + power * 0.2F);
				rot(out, 'Y', -inv * 45F);
			}
			case CROSSBOW_CHARGED -> {
				armTransform(out, inv, inverseArmHeight);
				swingArm(out, inv, 0F);
				out.translate(inv * -0.641864F, 0F, 0F);
				rot(out, 'Y', inv * 10F);
			}
			case AUTOSPIN -> {
				armTransform(out, inv, inverseArmHeight);
				out.translate(inv * -0.4F, 0.8F, 0.3F);
				rot(out, 'Y', inv * 65F);
				rot(out, 'Z', inv * -85F);
			}
			case BRUSH -> {
				float swipe = -15F + 75F * (float) Math.cos(t * 2.0 * PI);
				if (leftArm) {
					out.translate(0.1F, 0.83F, 0.35F);
					rot(out, 'X', -80F);
					rot(out, 'Y', -90F);
					rot(out, 'X', swipe);
					out.translate(-0.3F, 0.22F, 0.35F);
				} else {
					out.translate(-0.25F, 0.22F, 0.35F);
					rot(out, 'X', -80F);
					rot(out, 'Y', 90F);
					rot(out, 'X', swipe);
				}
			}
		}
		return out;
	}

	/** The extent points (x y z each, blocks) of a flat 16 by 16 sprite item with the display transform {@code t} applied, as the item model reports them. */
	static float[] spritePoints(ItemTransform t, boolean leftHand) {
		com.mojang.blaze3d.vertex.PoseStack ps = new com.mojang.blaze3d.vertex.PoseStack();
		t.apply(leftHand, ps.last());
		Matrix4f m = new Matrix4f(ps.last().pose());
		float[] out = new float[3 * 8];
		int k = 0;
		for (int i = 0; i < 8; i++) {
			org.joml.Vector3f v = new org.joml.Vector3f((i & 1) == 0 ? 0F : 1F, (i & 2) == 0 ? 0F : 1F, (i & 4) == 0 ? 7.5F / 16F : 8.5F / 16F);
			m.transformPosition(v);
			out[k++] = v.x;
			out[k++] = v.y;
			out[k++] = v.z;
		}
		return out;
	}

	/**
	 * The extent points vanilla reports for a flat item built from the item texture {@code texture} (a path under {@code assets/minecraft/textures/item/},
	 * read from the game's own jar): the corners of the full quad, front and back, and the ends of every edge between an opaque pixel and a transparent one
	 * (what {@code ItemModelGenerator} bakes as side quads), display transform {@code t} applied.
	 */
	static float[] outlinePoints(String texture, ItemTransform t, boolean leftHand) {
		java.awt.image.BufferedImage img;
		try (java.io.InputStream in = FpPoseMath.class.getResourceAsStream("/assets/minecraft/textures/item/" + texture + ".png")) {
			if (in == null) throw new IllegalStateException("no texture " + texture);
			img = javax.imageio.ImageIO.read(in);
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
		int w = img.getWidth(), h = Math.min(img.getHeight(), img.getWidth());
		java.util.Set<String> seen = new java.util.LinkedHashSet<>();
		java.util.List<float[]> pts = new java.util.ArrayList<>();
		float[][] corners = {{0, 0}, {16, 0}, {0, 16}, {16, 16}};
		for (float[] c : corners) {
			for (float z : new float[] {7.5F, 8.5F}) add(pts, seen, c[0], c[1], z);
		}
		float sx = 16F / w, sy = 16F / h;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (!opaque(img, x, y, w, h)) continue;
				boolean up = !opaque(img, x, y - 1, w, h), down = !opaque(img, x, y + 1, w, h), left = !opaque(img, x - 1, y, w, h), right = !opaque(img, x + 1, y, w, h);
				float x0 = x * sx, x1 = (x + 1) * sx, yTop = 16F - y * sy, yBot = 16F - (y + 1) * sy;
				for (float z : new float[] {7.5F, 8.5F}) {
					if (up) {
						add(pts, seen, x0, yTop, z);
						add(pts, seen, x1, yTop, z);
					}
					if (down) {
						add(pts, seen, x0, yBot, z);
						add(pts, seen, x1, yBot, z);
					}
					if (left) {
						add(pts, seen, x0, yTop, z);
						add(pts, seen, x0, yBot, z);
					}
					if (right) {
						add(pts, seen, x1, yTop, z);
						add(pts, seen, x1, yBot, z);
					}
				}
			}
		}
		com.mojang.blaze3d.vertex.PoseStack ps = new com.mojang.blaze3d.vertex.PoseStack();
		t.apply(leftHand, ps.last());
		Matrix4f m = new Matrix4f(ps.last().pose());
		float[] out = new float[3 * pts.size()];
		int k = 0;
		for (float[] p : pts) {
			org.joml.Vector3f v = new org.joml.Vector3f(p[0] / 16F, p[1] / 16F, p[2] / 16F);
			m.transformPosition(v);
			out[k++] = v.x;
			out[k++] = v.y;
			out[k++] = v.z;
		}
		return out;
	}

	private static void add(java.util.List<float[]> pts, java.util.Set<String> seen, float x, float y, float z) {
		if (seen.add(x + "," + y + "," + z)) pts.add(new float[] {x, y, z});
	}

	private static boolean opaque(java.awt.image.BufferedImage img, int x, int y, int w, int h) {
		return x >= 0 && y >= 0 && x < w && y < h && (img.getRGB(x, y) >>> 24) != 0;
	}

	/** The extent points of a box item (a block, a shield's plate) of {@code w} by {@code h} by {@code d} px about the item model's middle, display transform applied. */
	static float[] boxPoints(ItemTransform t, boolean leftHand, float w, float h, float d) {
		com.mojang.blaze3d.vertex.PoseStack ps = new com.mojang.blaze3d.vertex.PoseStack();
		t.apply(leftHand, ps.last());
		Matrix4f m = new Matrix4f(ps.last().pose());
		float[] out = new float[3 * 8];
		int k = 0;
		for (int i = 0; i < 8; i++) {
			org.joml.Vector3f v = new org.joml.Vector3f(0.5F + ((i & 1) == 0 ? -w : w) / 32F, 0.5F + ((i & 2) == 0 ? -h : h) / 32F, 0.5F + ((i & 4) == 0 ? -d : d) / 32F);
			m.transformPosition(v);
			out[k++] = v.x;
			out[k++] = v.y;
			out[k++] = v.z;
		}
		return out;
	}
}
