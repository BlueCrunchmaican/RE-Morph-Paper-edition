package dev.morphreborn.paper.arm;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.arm.api.ArmAccessoryModule;
import dev.morphreborn.paper.arm.api.ArmLookModule;
import dev.morphreborn.paper.arm.api.ArmService;
import dev.morphreborn.paper.arm.api.ArmSpecialModule;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Where the arm code is found: fixed class names, loaded by reflection, each one optional. The arm forms arrive in
 * phases (the katana and pickaxe first, the other forms, the lantern and the pack look later), and a build may ship
 * with only some of them. A class missing from the jar simply isn't there: its form counts as not built and is hidden
 * from the tree, the chest menu, the wheel and S_ARSENAL, and /morph arm refuses it. Nothing else in the plugin names
 * these classes, so adding a phase means adding files and one line here, never editing shared ones.
 * <p>
 * The new forms of 8.0.0-beta.2 (spear, mace, trident, crossbow, claws; shield, spyglass, flint) are registered everywhere
 * else already and wait for their module: a tool's special is one line in {@link #SPECIALS}, an accessory's one line in
 * {@link #ACCESSORIES}. Until it is there the form is "not built", exactly as a missing special has always meant.
 */
public final class ArmModules {
	/** The arm manager: implements ArmService, with a public constructor taking the plugin. */
	public static final String SERVICE = "dev.morphreborn.paper.arm.ArmManager";
	/** The specials, one per form, each with a public no-argument constructor. */
	public static final List<String> SPECIALS = List.of("dev.morphreborn.paper.arm.special.DrainingCut", "dev.morphreborn.paper.arm.special.VeinRip",
			"dev.morphreborn.paper.arm.special.FellCleave", "dev.morphreborn.paper.arm.special.Burrow", "dev.morphreborn.paper.arm.special.Reap",
			"dev.morphreborn.paper.arm.special.BowArm", "dev.morphreborn.paper.arm.special.WhipLash",
			// phase 2 of the new forms (8.0.0-beta.2), registered ahead of their classes: a name with no class is skipped quietly and its form
			// stays hidden (make() below), so adding the file is all phase 2 does
			"dev.morphreborn.paper.arm.special.SpearImpale", "dev.morphreborn.paper.arm.special.MaceLeap", "dev.morphreborn.paper.arm.special.TridentArm",
			"dev.morphreborn.paper.arm.special.CrossbowArm", "dev.morphreborn.paper.arm.special.ClawRake");
	/**
	 * The accessories, each an {@code ArmAccessoryModule} with a public no-argument constructor (its {@code id()} says which
	 * accessory it is). The shield, spyglass and flint and steel modules (package {@code arm.accessory}) are registered ahead of
	 * their classes, as the new tools' specials are.
	 */
	public static final List<String> ACCESSORIES = List.of("dev.morphreborn.paper.arm.lantern.Lantern", "dev.morphreborn.paper.arm.accessory.ShieldAccessory",
			"dev.morphreborn.paper.arm.accessory.SpyglassAccessory", "dev.morphreborn.paper.arm.accessory.FlintAccessory");
	/** Needs PacketEvents at runtime; without it the class fails to link and the look is left out. */
	public static final String LOOK = "dev.morphreborn.paper.arm.look.ArmEquipmentView";

	private ArmModules() {}

	/**
	 * The arm manager, or {@link ArmService#NONE} when it isn't in this build or can't be made. Logged once: arms are
	 * off then, and everything else runs as before.
	 */
	public static ArmService service(MorphRebornPlugin plugin) {
		try {
			Class<?> c = Class.forName(SERVICE, true, ArmModules.class.getClassLoader());
			Object made = c.getConstructor(MorphRebornPlugin.class).newInstance(plugin);
			if (made instanceof ArmService service) return service;
			plugin.getLogger().warning("Arm forms are off: " + SERVICE + " isn't an ArmService.");
		} catch (ClassNotFoundException e) {
			plugin.getLogger().info("Arm forms aren't in this build; they are off.");
		} catch (Throwable t) {
			plugin.getLogger().log(Level.WARNING, "Arm forms are off: they failed to start.", t);
		}
		return ArmService.NONE;
	}

	/** Every special in this build, in SPECIALS order. A missing one is skipped quietly; a broken one with a warning. */
	public static List<ArmSpecialModule> specials(Logger log) {
		List<ArmSpecialModule> out = new ArrayList<>();
		for (String name : SPECIALS) {
			ArmSpecialModule m = make(name, ArmSpecialModule.class, log);
			if (m != null) out.add(m);
		}
		return out;
	}

	/** Every accessory in this build, in ACCESSORIES order. A missing one is skipped quietly; a broken one with a warning. */
	public static List<ArmAccessoryModule> accessories(Logger log) {
		List<ArmAccessoryModule> out = new ArrayList<>();
		for (String name : ACCESSORIES) {
			ArmAccessoryModule m = make(name, ArmAccessoryModule.class, log);
			if (m != null) out.add(m);
		}
		return out;
	}

	public static @Nullable ArmLookModule look(Logger log) {
		return make(LOOK, ArmLookModule.class, log);
	}

	/**
	 * One module by class name: null when the class isn't in the jar (logged at debug level) or can't be made, which
	 * includes a NoClassDefFoundError from a library that isn't installed (PacketEvents for the look).
	 */
	private static <T> @Nullable T make(String name, Class<T> type, Logger log) {
		try {
			Class<?> c = Class.forName(name, true, ArmModules.class.getClassLoader());
			Object made = c.getConstructor().newInstance();
			if (type.isInstance(made)) return type.cast(made);
			log.warning("Arm module " + name + " isn't a " + type.getSimpleName() + "; left out.");
		} catch (ClassNotFoundException e) {
			log.fine("Arm module " + name + " isn't in this build.");
		} catch (NoClassDefFoundError e) {
			log.info("Arm module " + name + " is left out: it needs " + e.getMessage() + ", which isn't installed.");
		} catch (Throwable t) {
			log.log(Level.WARNING, "Arm module " + name + " failed to load; left out.", t);
		}
		return null;
	}
}
