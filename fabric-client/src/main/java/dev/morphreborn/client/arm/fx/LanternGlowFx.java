package dev.morphreborn.client.arm.fx;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.arm.ArmColours;
import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmState;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.lantern.GlowField;
import dev.morphreborn.client.net.ClientNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The lantern's coloured light (2026-11-16: "if it's blue your lighting will be blue and if it's red,
 * red"): a soft additive wash, in the holder's arm colour, over the faces the lantern lights, fading smoothly from the
 * lantern out to a radius by the brightness chosen. Vanilla light has no colour, so the server's light blocks stay white
 * and this goes on top of them, for every modded viewer, from what they already know (who is lit, at what brightness,
 * and each player's arm colour): no packet of its own.
 * <p>
 * <b>How.</b> {@link GlowField} floods from the lantern's block through the open blocks round it and keeps the solid
 * faces it reaches (so the wash goes round corners and never through a wall), once when the lantern's block changes, its
 * brightness changes or every {@link #REFRESH_TICKS} (changed terrain); while the holder moves, at most every
 * {@link #MOVE_REBUILD_TICKS} ticks (the eye's block changes nearly every tick at a sprint, and a flood each time is
 * the cost for nothing: the field reaches two blocks past its radius, so it stays good for the few blocks between
 * rebuilds); never more than one field a tick. Every frame the
 * faces are drawn as additive quads, {@code RenderTypes.lightning()} (position and colour only, no texture or lightmap:
 * the type the game draws lightning with, so every shader pack already draws it), their corners' alpha following the
 * distance from the lantern's eye as it is this frame, so the wash glides with the holder and has no tiles. It reads the
 * world through the loaded chunks only. It draws only on a server from {@link ArmProtocol#LANTERN_TAPER_SINCE}: an older
 * server's light is its flat circle, which a wash with this taper would not match, so there the glow stays off.
 * <p>
 * <b>Cost.</b> At most {@link #MAX_FIELDS} lanterns at a time (the nearest to the camera), each at most
 * {@link GlowField#MAX_FACES} quads, those turned away from the camera skipped before any maths; a flood of at most
 * {@link GlowField#MAX_CELLS} blocks, one a tick. It is switched off by the {@code lanternGlow} setting.
 * <p>
 * <b>With shader packs.</b> The quads are an ordinary lightning-type submit, which Iris maps like the game's own
 * lightning; with the server's light still there under it, a pack that does not draw the type at all only loses the
 * colour. Render thread only.
 */
public final class LanternGlowFx implements FxHandler {
	/** The wash's radius (blocks) for brightness 1 to 5: near the server's reach for the first three, kept cheap for the last two. */
	static final int[] RADII = {7, 10, 13, 16, 20};
	/** The brightness assumed when the server did not send one (a server from before 8.0.0). */
	private static final int DEFAULT_BRIGHT = 3;
	/** Ticks the wash takes to fade in when a lantern is lit and out when it goes out. */
	static final int FADE_TICKS = 8;
	/** A field is built again after this many ticks even if the lantern did not move (blocks changed round it). */
	static final int REFRESH_TICKS = 40;
	/** While the holder moves into a new block, the field is built again at most this often (ticks); a lantern with no field or a new radius is built at once. */
	static final int MOVE_REBUILD_TICKS = 4;
	/** The most lanterns washed at once, and how far past its radius from the camera a lantern still is. */
	static final int MAX_FIELDS = 4;
	private static final double RANGE = 64.0;
	/** How strong the wash is where it is strongest: the share of the owner's colour added to the surface. */
	static final float STRENGTH = 0.42F;
	/** A corner's alpha below this (of 255) is not worth a vertex: a quad with all four under it is skipped. */
	private static final int MIN_ALPHA = 2;

	/** One lit lantern's wash. */
	private static final class Track {
		final UUID player;
		boolean lit;
		int bright = DEFAULT_BRIGHT;
		float fade, lastFade;
		@Nullable GlowField field;
		long builtAt;
		/** The block cell the field was built in, and the radius, to know when it is stale. */
		int bx, by, bz, builtRadius = -1;
		double distSq = Double.MAX_VALUE;

		Track(UUID player) {
			this.player = player;
		}
	}

	private final Map<UUID, Track> tracks = new HashMap<>();
	private final WorldGrid grid = new WorldGrid();
	private boolean failedOnce;

	public LanternGlowFx() {}

	@Override
	public int[] kinds() {
		return new int[0]; // it takes no effects from the server: it follows who is lit
	}

	@Override
	public void start(ArmFxEvent e) {}

	/** The radius (blocks) of the wash at a brightness, 1 to 5 (anything else: the middle one). */
	public static int radiusFor(int bright) {
		return RADII[(bright >= 1 && bright <= 5 ? bright : DEFAULT_BRIGHT) - 1];
	}

	@Override
	public void tick() {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || !drawing()) {
			tracks.clear();
			return;
		}
		Entity viewer = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
		Vec3 cam = viewer == null ? Vec3.ZERO : viewer.getEyePosition(1F);
		for (Track t : tracks.values()) t.lit = false;
		for (ArmState st : Arms.states()) {
			if (!st.lanternLit) continue;
			Player p = level.getPlayerByUUID(st.subject);
			if (p == null || p.isRemoved() || p.isSpectator()) continue;
			Track t = tracks.computeIfAbsent(st.subject, Track::new);
			t.lit = true;
			t.bright = st.lanternBright;
			t.distSq = p.getEyePosition(1F).distanceToSqr(cam);
		}
		Track best = null;
		for (Iterator<Track> it = tracks.values().iterator(); it.hasNext(); ) {
			Track t = it.next();
			t.lastFade = t.fade;
			t.fade = GlowField.fade(t.fade, t.lit, FADE_TICKS);
			if (!t.lit && t.fade <= 0F) {
				it.remove();
				continue;
			}
			Player p = level.getPlayerByUUID(t.player);
			if (p == null || p.isRemoved()) {
				t.lit = false;
				continue;
			}
			if (!t.lit) continue; // fading out keeps the field it had
			double reach = radiusFor(t.bright) + RANGE;
			if (t.distSq > reach * reach) continue; // too far to see: not worth a flood
			if (stale(t, p) && (best == null || t.distSq < best.distSq)) best = t;
		}
		if (best != null) {
			Player p = level.getPlayerByUUID(best.player);
			if (p != null) build(level, best, p);
		}
	}

	/** Whether the glow is on at all: the player's setting, and a server whose light the wash matches (from the taper's version). */
	private static boolean drawing() {
		return ClientConfig.INSTANCE.lanternGlow && ClientNet.serverAtLeast(ArmProtocol.LANTERN_TAPER_SINCE);
	}

	private boolean stale(Track t, Player p) {
		if (t.field == null || t.builtRadius != radiusFor(t.bright)) return true;
		long age = Arms.TICK - t.builtAt;
		if (age >= REFRESH_TICKS) return true;
		BlockPos eye = BlockPos.containing(p.getEyePosition(1F));
		boolean moved = eye.getX() != t.bx || eye.getY() != t.by || eye.getZ() != t.bz;
		return moved && age >= MOVE_REBUILD_TICKS; // the per-frame alpha follows the eye in between
	}

	private void build(ClientLevel level, Track t, Player p) {
		BlockPos eye = BlockPos.containing(p.getEyePosition(1F));
		int radius = radiusFor(t.bright);
		try {
			grid.use(level);
			t.field = GlowField.build(grid, eye.getX(), eye.getY(), eye.getZ(), radius);
		} catch (RuntimeException e) {
			// a chunk unloading under the flood or the like: no wash this time, and the next tick tries again
			t.field = null;
			if (!failedOnce) {
				failedOnce = true;
				dev.morphreborn.client.MorphClient.LOGGER.warn("The lantern's coloured light could not be worked out; later failures are not logged", e);
			}
		} finally {
			grid.release();
		}
		t.builtAt = Arms.TICK;
		t.bx = eye.getX();
		t.by = eye.getY();
		t.bz = eye.getZ();
		t.builtRadius = radius;
	}

	@Override
	public void collect(ArmFxFrame f) {
		if (tracks.isEmpty() || !drawing()) return;
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) return;
		List<Track> drawn = new ArrayList<>(MAX_FIELDS);
		for (Track t : tracks.values()) {
			if (t.field != null && t.field.count() > 0 && t.fade > 0F && t.distSq <= (radiusFor(t.bright) + RANGE) * (radiusFor(t.bright) + RANGE)) drawn.add(t);
		}
		drawn.sort((a, b) -> Double.compare(a.distSq, b.distSq));
		for (int n = 0; n < Math.min(MAX_FIELDS, drawn.size()); n++) {
			Track t = drawn.get(n);
			Player p = level.getPlayerByUUID(t.player);
			if (p == null || p.isRemoved()) continue;
			GlowField field = t.field;
			if (field == null) continue;
			Vec3 eye = p.getEyePosition(f.partialTick());
			float fade = t.lastFade + (t.fade - t.lastFade) * f.partialTick();
			int rgb = ArmColours.of(t.player);
			submit(f, field, eye, rgb, STRENGTH * fade);
		}
	}

	/** Scratch for {@link #submit}: the render thread draws one field at a time, so these are shared and a frame allocates nothing per face. */
	private static final float[] CORNERS = new float[12];
	private static final int[] ALPHAS = new int[4];
	private static final double[] NORMAL = new double[3];

	/** Submits one field's faces as additive quads in {@code rgb}, {@code strength} the colour's share at the lantern. */
	static void submit(ArmFxFrame f, GlowField field, Vec3 eye, int rgb, float strength) {
		Vec3 cam = f.camPos();
		// everything inside is relative to the field's own cell, exact as floats whatever the world coordinate
		double lx = eye.x - field.cx, ly = eye.y - field.cy, lz = eye.z - field.cz;
		double camX = cam.x - field.cx, camY = cam.y - field.cy, camZ = cam.z - field.cz;
		int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
		PoseStack poseStack = f.poseStack();
		poseStack.pushPose();
		poseStack.translate(field.cx - cam.x, field.cy - cam.y, field.cz - cam.z);
		f.collector().submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, buffer) -> {
			float[] c = CORNERS;
			int[] a = ALPHAS;
			double[] n = NORMAL;
			int count = field.count();
			for (int i = 0; i < count; i++) {
				// the face stands further off its block the further it is from the camera, so far washes do not z-fight (the
				// cell's centre is near enough to the face for this)
				double cdx = camX - (field.cellX(i) - field.cx + 0.5), cdy = camY - (field.cellY(i) - field.cy + 0.5), cdz = camZ - (field.cellZ(i) - field.cz + 0.5);
				field.corners(i, c, GlowField.liftAt(cdx * cdx + cdy * cdy + cdz * cdz));
				GlowField.normal(field.dir(i), n);
				// a face turned away from the camera is culled by the card anyway: skip it before the maths
				double mx = (c[0] + c[6]) * 0.5, my = (c[1] + c[7]) * 0.5, mz = (c[2] + c[8]) * 0.5;
				if (n[0] * (camX - mx) + n[1] * (camY - my) + n[2] * (camZ - mz) <= 0) continue;
				int most = 0;
				for (int k = 0; k < 4; k++) {
					a[k] = (int) Math.min(255.0, field.wash(i, c, k, lx, ly, lz) * strength * 255.0);
					most = Math.max(most, a[k]);
				}
				if (most < MIN_ALPHA) continue;
				for (int k = 0; k < 4; k++) buffer.addVertex(pose, c[3 * k], c[3 * k + 1], c[3 * k + 2]).setColor(r, g, b, a[k]);
			}
		});
		poseStack.popPose();
	}

	@Override
	public void clear() {
		tracks.clear();
	}

	/** Number of lanterns being washed: for the tests. */
	int tracked() {
		return tracks.size();
	}

	/**
	 * The game's blocks as the flood sees them: a solid, opaque block takes a face; anything else that is loaded is open;
	 * an unloaded chunk or the world's edge stops it.
	 */
	private static final class WorldGrid implements GlowField.Grid {
		private @Nullable ClientLevel level;
		private @Nullable ChunkAccess chunk;
		private int chunkX = Integer.MIN_VALUE, chunkZ = Integer.MIN_VALUE;
		private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		void use(ClientLevel l) {
			level = l;
			chunk = null;
			chunkX = chunkZ = Integer.MIN_VALUE;
		}

		void release() {
			level = null;
			chunk = null;
		}

		@Override
		public int kind(int x, int y, int z) {
			ClientLevel l = level;
			if (l == null || y < l.getMinY() || y > l.getMaxY()) return GlowField.BLOCKED;
			int cx = x >> 4, cz = z >> 4;
			if (chunk == null || cx != chunkX || cz != chunkZ) {
				chunk = l.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
				chunkX = cx;
				chunkZ = cz;
			}
			ChunkAccess c = chunk;
			if (c == null) return GlowField.BLOCKED;
			return c.getBlockState(pos.set(x, y, z)).isSolidRender() ? GlowField.SOLID : GlowField.OPEN;
		}
	}
}
