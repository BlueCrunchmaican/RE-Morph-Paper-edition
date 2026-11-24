package dev.morphreborn.client.arm.geom;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds each form's {@link FormGeometry} by class name, so the forms can land one file at a time (phase 1 the katana and
 * pickaxe, phase 2 the rest) without a shared list to edit: every name in {@link #FORMS} whose class is in the build is
 * loaded once, on first use, and keyed by the form id it reports. A form with no geometry in the build (or one that
 * fails to load) is drawn by a {@link PlaceholderGeometry}, a static-covered rod, so an arm is never invisible and never
 * falls back to the vanilla tool.
 * <p>
 * The lantern's lure is found the same way ({@link #LURE}); it has no placeholder, since the lantern is an accessory
 * and simply isn't drawn without its geometry. The other accessories are {@link AccessoryRenderer}s listed in
 * {@link #ACCESSORIES}, found the same way and likewise not drawn without theirs.
 * <p>
 * Phase 2 of the new forms (2026-11-16) adds each form's geometry as one new class and each accessory's renderer as one new
 * class; their names are already in {@link #FORMS} and {@link #ACCESSORIES}.
 */
public final class ArmGeometry {
	static final List<String> FORMS = List.of("dev.morphreborn.client.arm.geom.KatanaGeometry", "dev.morphreborn.client.arm.geom.PickaxeGeometry",
			"dev.morphreborn.client.arm.geom.WarAxeGeometry", "dev.morphreborn.client.arm.geom.SpadeGeometry", "dev.morphreborn.client.arm.geom.SickleGeometry",
			"dev.morphreborn.client.arm.geom.RecurveGeometry", "dev.morphreborn.client.arm.geom.WhipGeometry",
			// the new forms, registered ahead of their classes (2026-11-16): one not in the build is skipped (a debug line), so its form
			// is drawn by the placeholder until its file lands
			"dev.morphreborn.client.arm.geom.SpearGeometry", "dev.morphreborn.client.arm.geom.MaceGeometry", "dev.morphreborn.client.arm.geom.TridentGeometry",
			"dev.morphreborn.client.arm.geom.CrossbowGeometry", "dev.morphreborn.client.arm.geom.ClawsGeometry"
	);
	/**
	 * The accessory renderers' class names (the shield, spyglass and flint and steel, phase 2; the lantern's is {@link #LURE}),
	 * each keyed by the accessory id it reports.
	 */
	static final List<String> ACCESSORIES = List.of(
			// registered ahead of their classes, like the forms: one not in the build is skipped and its accessory is not drawn
			"dev.morphreborn.client.arm.geom.ShieldRenderer", "dev.morphreborn.client.arm.geom.SpyglassRenderer", "dev.morphreborn.client.arm.geom.FlintRenderer"
	);
	static final String LURE = "dev.morphreborn.client.arm.geom.LureGeometry";
	/** The mod's logger by name (MorphClient.LOGGER is the same one), so unit tests can use this class without the client. */
	private static final Logger LOG = LoggerFactory.getLogger("RE:Morph");

	private static volatile @Nullable Map<String, FormGeometry> loaded;
	private static volatile @Nullable Map<String, AccessoryRenderer> accessories;
	private static final Map<String, FormGeometry> PLACEHOLDERS = new ConcurrentHashMap<>();
	private static volatile boolean lureLooked;
	private static volatile @Nullable LureRenderer lure;

	private ArmGeometry() {}

	/** {@code form}'s geometry, or a {@link PlaceholderGeometry} for it when the build has none. Never null. */
	public static FormGeometry get(String form) {
		FormGeometry g = forms().get(form);
		return g != null ? g : PLACEHOLDERS.computeIfAbsent(form, PlaceholderGeometry::new);
	}

	/** Whether {@code form} has its real geometry in this build (false: it is drawn as the placeholder). */
	public static boolean built(String form) {
		return forms().containsKey(form);
	}

	/** Whether accessory {@code id} has its renderer in this build (false: it is not drawn). */
	public static boolean accessoryBuilt(String id) {
		return accessoryRenderers().containsKey(id);
	}

	/** Accessory {@code id}'s renderer, or null when the build has none (the lantern's is {@link #lure()}, not here). */
	public static @Nullable AccessoryRenderer accessory(String id) {
		return accessoryRenderers().get(id);
	}

	private static Map<String, AccessoryRenderer> accessoryRenderers() {
		Map<String, AccessoryRenderer> m = accessories;
		if (m == null) {
			synchronized (ArmGeometry.class) {
				m = accessories;
				if (m == null) {
					m = new HashMap<>();
					for (String name : ACCESSORIES) {
						AccessoryRenderer r = create(name, AccessoryRenderer.class);
						if (r == null) continue;
						String id = r.id();
						if (id == null || id.isEmpty()) {
							LOG.error("Accessory renderer {} names no accessory; skipped", name);
						} else if (m.putIfAbsent(id, r) != null) {
							LOG.warn("Accessory renderer {} draws {}, which another class already draws; skipped", name, id);
						}
					}
					LOG.debug("Accessory renderers loaded for {}", m.keySet());
					accessories = m;
				}
			}
		}
		return m;
	}

	/** The lantern's lure, or null when its geometry isn't in this build. */
	public static @Nullable LureRenderer lure() {
		if (!lureLooked) {
			lure = create(LURE, LureRenderer.class);
			lureLooked = true;
		}
		return lure;
	}

	private static Map<String, FormGeometry> forms() {
		Map<String, FormGeometry> m = loaded;
		if (m == null) {
			synchronized (ArmGeometry.class) {
				m = loaded;
				if (m == null) {
					m = new HashMap<>();
					for (String name : FORMS) {
						FormGeometry g = create(name, FormGeometry.class);
						if (g == null) continue;
						String id = g.form();
						if (id == null || id.isEmpty()) {
							LOG.error("Arm geometry {} names no form; skipped", name);
						} else if (m.putIfAbsent(id, g) != null) {
							LOG.warn("Arm geometry {} draws {}, which another class already draws; skipped", name, id);
						}
					}
					LOG.debug("Arm geometry loaded for {}", m.keySet());
					loaded = m;
				}
			}
		}
		return m;
	}

	/** An instance of class {@code name} as a {@code type}, or null when it isn't in the build or won't start (logged). */
	private static <T> @Nullable T create(String name, Class<T> type) {
		Class<?> c;
		try {
			c = Class.forName(name, true, ArmGeometry.class.getClassLoader());
		} catch (ClassNotFoundException e) {
			LOG.debug("Arm geometry {} isn't in this build", name);
			return null;
		} catch (LinkageError e) {
			LOG.error("Arm geometry {} could not be loaded", name, e);
			return null;
		}
		if (!type.isAssignableFrom(c)) {
			LOG.error("Arm geometry {} is not a {}; skipped", name, type.getSimpleName());
			return null;
		}
		try {
			return type.cast(c.getDeclaredConstructor().newInstance());
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			LOG.error("Arm geometry {} failed to start; skipped", name, e);
			return null;
		}
	}
}
