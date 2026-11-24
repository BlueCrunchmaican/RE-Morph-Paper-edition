package dev.morphreborn.client.arm;

import org.jspecify.annotations.Nullable;

/**
 * How the next arm drawn as a GUI item should be posed, for the screens that show a live arm as an icon (the Arsenal
 * wheel, the upgrade tree): turned on a turntable, tilted, scaled, tinted (dim for a locked form) and fully lit. The
 * screen sets it right before it renders the item; the arm's item model takes it while the item is extracted, which
 * happens inside that render call, so it reaches exactly that one icon. Nothing else changes: the arm still goes
 * through the ordinary item path.
 * <p>
 * Render thread only. The record is called Override as the shared contract names it, so this file uses no
 * {@code @Override} annotations.
 */
public final class ArmGuiOverride {
	/** Turntable yaw and tilt in degrees, a scale on top of the GUI fit, an ARGB tint (0xFFFFFFFF: none), and full brightness. */
	public record Override(float yawDeg, float pitchDeg, float scale, int tintArgb, boolean fullBright) {}

	private static @Nullable Override pending;

	private ArmGuiOverride() {}

	public static void set(Override o) {
		pending = o;
	}

	/** The pose set for this icon, once: the next arm drawn gets none unless it is set again. */
	public static @Nullable Override take() {
		Override o = pending;
		pending = null;
		return o;
	}

	/** Drops a pose that no arm took (a screen that set it and then drew something else). */
	public static void clear() {
		pending = null;
	}
}
