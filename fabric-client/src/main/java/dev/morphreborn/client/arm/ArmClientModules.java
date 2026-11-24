package dev.morphreborn.client.arm;

import dev.morphreborn.client.MorphClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;

/**
 * The client's arm modules, by class name, loaded with {@code Class.forName} at start. A name whose class isn't in the
 * build is skipped (logged at debug), so the parts can arrive in any order and a beta can ship with some of them; one
 * whose class is there but fails to load or start is logged as an error and skipped, and the rest still run.
 * <p>
 * The list is fixed: a new part adds its own class under one of these names rather than editing a shared file. The
 * forms' geometry and the effects have lists of their own (ArmGeometry, ArmFx).
 */
public final class ArmClientModules {
	static final List<String> MODULES = List.of(
			"dev.morphreborn.client.arm.model.ArmRender", "dev.morphreborn.client.arm.gui.ArmUi",
			"dev.morphreborn.client.arm.fx.ArmFx", "dev.morphreborn.client.arm.lantern.ArmLantern",
			"dev.morphreborn.client.dev.ArmDump");

	private static final List<ArmClientModule> LOADED = new ArrayList<>();
	/** Modules whose tick or reset threw once already: logged the first time only. */
	private static final Set<ArmClientModule> FAILED = new HashSet<>();
	private static boolean initialised;

	private ArmClientModules() {}

	/** Loads and starts every module in the build, once. */
	public static void initAll() {
		if (initialised) return;
		initialised = true;
		for (String name : MODULES) {
			Class<?> type;
			try {
				type = Class.forName(name, true, ArmClientModules.class.getClassLoader());
			} catch (ClassNotFoundException e) {
				MorphClient.LOGGER.debug("Arm module {} isn't in this build", name);
				continue;
			} catch (LinkageError e) {
				MorphClient.LOGGER.error("Arm module {} could not be loaded", name, e);
				continue;
			}
			if (!ArmClientModule.class.isAssignableFrom(type)) {
				MorphClient.LOGGER.error("Arm module {} is not an ArmClientModule; skipped", name);
				continue;
			}
			try {
				ArmClientModule module = (ArmClientModule) type.getDeclaredConstructor().newInstance();
				module.init();
				LOADED.add(module);
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				MorphClient.LOGGER.error("Arm module {} failed to start; skipped", name, e);
			}
		}
		MorphClient.LOGGER.debug("Arm modules running: {}", LOADED.size());
	}

	/** One game tick for every module (see {@link ArmClientModule#tick}). */
	public static void tickAll(Minecraft mc) {
		for (ArmClientModule module : LOADED) {
			try {
				module.tick(mc);
			} catch (RuntimeException e) {
				failed(module, "tick", e);
			}
		}
	}

	/** Joining or leaving a server: every module forgets the last one. */
	public static void resetAll() {
		for (ArmClientModule module : LOADED) {
			try {
				module.reset();
			} catch (RuntimeException e) {
				failed(module, "reset", e);
			}
		}
	}

	/** The modules that loaded and started, in list order. */
	public static List<ArmClientModule> loaded() {
		return Collections.unmodifiableList(LOADED);
	}

	/** A module's bug shouldn't take the game down with it: logged in full once, then it keeps being called. */
	private static void failed(ArmClientModule module, String what, RuntimeException e) {
		if (FAILED.add(module)) MorphClient.LOGGER.error("Arm module {} failed in {}; later failures aren't logged", module.getClass().getName(), what, e);
	}
}
