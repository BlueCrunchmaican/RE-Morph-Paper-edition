package dev.morphreborn.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Client-owned preferences (config/morphreborn-client.json), also editable in game (MorphSettingsScreen: /morphclient
 * or the RE:Morph Settings key). Keybinds live in the vanilla options file like any other key; favourites and the
 * collection are server-owned. Defaults are the original mod's ConfigClient values.
 */
public final class ClientConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** config/morphreborn-client.json; looked up on first use, so the unit tests can make a ClientConfig without the loader. */
	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("morphreborn-client.json");
	}

	public static ClientConfig INSTANCE = new ClientConfig();

	/** Distance of the selector from the top of the screen, in GUI pixels (original: 30). */
	public int selectorDistanceFromTop = 30;
	/** Selector tile size multiplier (original: 1.0, tiles are 50px). */
	public double selectorScale = 1.0;
	/** Mouse wheel scrolls, left click confirms, right click closes, middle click favourites. */
	public boolean selectorAllowMouseControl = true;
	/** Flip the mouse wheel direction in the selector. */
	public boolean invertScroll = false;
	/** Radial favourites menu size as a fraction of the screen (original: 0.75). */
	public double radialScale = 0.75;
	/**
	 * Play the tendril animation when someone acquires a morph; if false, the plain particle trails players without the
	 * mod see. Only this player's view changes.
	 */
	public boolean acquisitionAnimation = true;
	/**
	 * What you see when someone gains a new morph, and when a kill only gives biomass (Survival): TENDRILS (the original
	 * black tendrils), RED_TENDRILS, WISPS (short red streams, nothing taken), PLAIN (the particles players without the
	 * mod see) or OFF. absorbMorph starts from the old acquisitionAnimation setting.
	 */
	public String absorbMorph = null;
	public String absorbBiomass = "RED_TENDRILS";
	/** Tendril segments (also roughly ticks to reach the mob; original: 10). */
	public int acquisitionTendrilMaxChild = 10;
	/** First-person fade of your own tendril segments (original: 5, 0 = off). */
	public int acquisitionTendrilPartOpacity = 5;
	/**
	 * Render the box-melting transformation; if false, the plain version players without the mod see (the old body
	 * shrinks into dark smoke, the new one grows back). Only this player's view changes.
	 */
	public boolean transitionAnimation = true;
	/** Toast when you unlock a morph. */
	public boolean unlockToasts = true;
	/**
	 * Tooltips (decided 2026-11-17, on by default): show the explanation lines in every description (the wheels, the upgrade tree, the hints under
	 * a screen, the settings' own tooltips): what a thing does in detail, how to use it, which key or button. Off leaves names, levels, state,
	 * biomass costs and rates. The server is told (C_TOOLTIPS) so the text it builds follows it too.
	 */
	public boolean tooltips = true;
	/** Keep the player name tag above morphed players. */
	public boolean showNameTags = true;
	/** Survival Mode biomass bar: OFF, CHANGES (a few seconds after it changes) or ALWAYS. */
	public String biomassBar = "CHANGES";
	/**
	 * Devouring Frenzy's meter: OFF, CHANGES (a few seconds after it changes, and while charged or in a frenzy) or ALWAYS.
	 * Until 7.4.0 it followed the biomass bar; a settings file from before starts it there.
	 */
	public String frenzyMeter = null;

	// Paper doll: a live miniature of yourself (your morph, its animations and transformations, your vehicle) on screen.
	/** Master switch; the Paper Doll key flips it. Off = hidden until the key shows it. */
	public boolean doll = true;
	/** ALWAYS, MORPHED (while morphed or transforming) or ACTIVE (while moving, transforming, riding, swimming...). */
	public String dollShow = "ALWAYS";
	/** TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT or BOTTOM_RIGHT. */
	public String dollCorner = "TOP_LEFT";
	/** Distance from the corner's side and top/bottom edge, in screen pixels (the GUI scale doesn't change them). */
	public int dollX = 16;
	public int dollY = 16;
	/** Height of the doll's box, in screen pixels. */
	public int dollSize = 100;
	/** How solid the doll is drawn, in percent. */
	public int dollOpacity = 100;
	/** Which way the doll faces: 0 = straight at you, positive = turned to its left. */
	public int dollAngle = 25;
	/** FOLLOW (the head turns and looks as yours does) or FORWARD (head straight ahead). */
	public String dollHead = "FOLLOW";
	/** How far the doll is seen from above, in degrees (negative: from below). */
	public int dollTilt = 10;
	/** TRUE_SIZE (a player fills the box, smaller morphs are smaller) or FILL (every form fills the box). */
	public String dollScale = "TRUE_SIZE";
	/** Keep the head level while gliding with an elytra / spinning with a riptide trident. */
	public boolean dollLockElytra = true;
	public boolean dollLockSpin = true;
	/** "While Active" mode: the situations that show the doll (see PaperDoll.Trigger). */
	public java.util.List<String> dollShowWhen = new java.util.ArrayList<>(java.util.List.of("CROUCHING", "SPRINTING", "AIRBORNE", "ELYTRA",
			"SWIMMING", "FLYING", "CLIMBING", "RIDING", "BLOCKING", "USING_ITEM", "SWINGING", "HURT", "ON_FIRE", "POWDER_SNOW", "SLEEPING",
			"TRANSFORMING", "ABILITY"));
	/** "While Active" mode: how long the doll stays after the last trigger, in tenths of a second. */
	public int dollLinger = 10;
	/** How far the dark vignette reaches out from the doll, in screen pixels; 0 is off. -1 until set: taken from dollOutline. */
	public int dollVignette = -1;
	/**
	 * Whether the doll's size, distances and vignette are in screen pixels (6.6.0). Before, they were GUI pixels, so the
	 * doll grew and shrank with the GUI scale; older settings are converted once at the GUI scale in use, so the doll
	 * looks exactly as it did and only stops following the GUI scale. Missing from older settings files, so false.
	 */
	public boolean dollScreenPixels;
	/** Before 6.6.0 the vignette's size was OFF, THIN or THICK. Read once into {@link #dollVignette}, then dropped. */
	public String dollOutline;
	/** How dark the outline is, in percent. */
	public int dollOutlineStrength = 70;
	/** Hide while the "now playing" music toast is up (it shares the top right corner). */
	public boolean dollHideMusic = true;
	/** BRIGHT (always fully lit) or WORLD (lit like the place you stand in). */
	public String dollLight = "BRIGHT";
	/** Show the boat, minecart or mount you ride, with everyone on it. */
	public boolean dollVehicle = true;
	/** Keep showing the doll while a menu (inventory, chat...) is open. */
	public boolean dollInMenus = false;
	/** Hide in third-person view, where you can see yourself anyway. */
	public boolean dollHideThirdPerson = true;
	/** Hide while the F3 debug screen is open. */
	public boolean dollHideDebug = true;
	/** On each join, say in chat if a newer RE:Morph is out or the server's is older than this mod (see UpdateCheck). */
	public boolean updateCheck = true;

	// Arm forms (the Arsenal, servers from 8.0.0): only what this player sees; the arms themselves are the server's.
	/** How fine arms are drawn at a distance: HIGH (finer, farther out), MEDIUM or LOW (coarser, and your own arm too). */
	public String armDetail = "HIGH";
	/** The glowing red accents (the hamon, runes), drawn full bright. */
	public boolean armGlow = true;
	/**
	 * The fizzing, eroding edge on an arm that is forming, melting or cracking: AUTO (on, but off under a shader pack,
	 * which may not draw it), ON or OFF (a hard cut edge instead).
	 */
	public String armDissolve = "AUTO";
	/** Biomass drips falling off the arm. */
	public boolean armDrips = true;
	/** A vein being ripped out shows through the stone as a red outline. */
	public boolean armSenseOutline = true;
	/** The arm's durability bar in the hotbar: GOO (the biomass bar's living static) or VANILLA (the plain bar). */
	public String armBar = "GOO";
	/** Arsenal wheel size as a fraction of the screen, like {@link #radialScale}. */
	public double armWheelScale = 0.75;
	/** The lantern lights the world around its beam when LambDynamicLights is installed. */
	public boolean lanternDynamicLight = true;
	/** Warm red motes drifting in the lantern's beam. */
	public boolean lanternMotes = true;
	/**
	 * The lantern's light takes its owner's arm colour: a soft coloured wash over the surfaces it lights, drawn on top of
	 * the server's white light for every lantern you see (2026-11-16: blue arm, blue light). Off: the light is
	 * the game's colourless light only.
	 */
	public boolean lanternGlow = true;
	/** The lantern brightness last chosen in the settings (1 to 5); the server keeps the real one. */
	public int lanternBrightness = 3;
	/**
	 * Your colour for the red parts of your arm forms and the mod's other red accents, as "#RRGGBB". The server passes it
	 * on, so other players with the mod see you in it; players without the mod always see the default red. The default,
	 * {@code ArmProtocol.COLOUR_DEFAULT}, is the red the mod has always been.
	 */
	public String armColour = dev.morphreborn.client.arm.ArmProtocol.colourHex(dev.morphreborn.client.arm.ArmProtocol.COLOUR_DEFAULT);
	/**
	 * How your arm looks while an arm form is out, as everyone with the mod sees it (sent to the server with the colour):
	 * "arm" the tendril arm, "hand" your own arm with a few tendrils round the hand, "armour" the armoured tendril arm.
	 */
	public String armLook = dev.morphreborn.client.arm.ArmProtocol.LOOK_NAMES.get(dev.morphreborn.client.arm.ArmProtocol.LOOK_DEFAULT);
	// (there was a shieldBands setting here, tendril or classic, for a day: the animated tendril bands are the shield's look and the choice was removed,
	// 2026-11-24. A saved config that still has the key loads fine, the value ignored: Gson skips fields the class no longer has, and the next save drops it.)
	/**
	 * How fast the pulses on your arm forms and lantern run, as a multiple of the usual (2026-11-16: the
	 * player's choice, shown to everyone): 1.0 is the usual speed, 0.25 slow, 3.0 fast. Sent to the server with the colour
	 * and look, so everyone with the mod sees your pulses at this speed.
	 */
	public float armPulseSpeed = 1F;
	// (there was a shieldLook setting here, back or morph; I picked morph on 2026-11-16 and it was removed. A saved config that
	// still has the key loads fine: Gson skips fields the class no longer has, and the next save drops it.)

	/** The vignette slider's top, in screen pixels. */
	public static final int VIGNETTE_MAX = 32;

	public static void load() {
		Path file = file();
		boolean fresh = !Files.exists(file);
		if (fresh) {
			INSTANCE.dollScreenPixels = true;
			INSTANCE.dollVignette = 6;
		} else {
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				ClientConfig loaded = GSON.fromJson(r, ClientConfig.class);
				if (loaded != null) INSTANCE = loaded;
			} catch (IOException | JsonParseException e) {
				MorphClient.LOGGER.warn("Could not read {}, using defaults: {}", file, e.toString());
			}
		}
		INSTANCE.clamp();
		save();
	}

	public static void save() {
		Path file = file();
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(INSTANCE, w);
			}
		} catch (IOException e) {
			MorphClient.LOGGER.warn("Could not save {}: {}", file, e.toString());
		}
	}

	void clamp() {
		selectorDistanceFromTop = Math.max(0, selectorDistanceFromTop);
		selectorScale = Math.clamp(selectorScale, 0.25, 4.0);
		radialScale = Math.clamp(radialScale, 0.2, 1.0);
		acquisitionTendrilMaxChild = Math.clamp(acquisitionTendrilMaxChild, 3, 100);
		acquisitionTendrilPartOpacity = Math.clamp(acquisitionTendrilPartOpacity, 0, 100);
		if (biomassBar == null || !java.util.List.of("OFF", "CHANGES", "ALWAYS").contains(biomassBar)) biomassBar = "CHANGES";
		if (frenzyMeter == null || !java.util.List.of("OFF", "CHANGES", "ALWAYS").contains(frenzyMeter)) frenzyMeter = biomassBar;
		if (absorbMorph == null || !java.util.List.of("TENDRILS", "RED_TENDRILS", "WISPS", "PLAIN", "OFF").contains(absorbMorph)) absorbMorph = acquisitionAnimation ? "TENDRILS" : "PLAIN";
		if (absorbBiomass == null || !java.util.List.of("TENDRILS", "RED_TENDRILS", "WISPS", "PLAIN", "OFF").contains(absorbBiomass)) absorbBiomass = "RED_TENDRILS";
		if (dollShow == null || !java.util.List.of("ALWAYS", "MORPHED", "ACTIVE").contains(dollShow)) dollShow = "ALWAYS";
		if (dollCorner == null || !java.util.List.of("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT").contains(dollCorner)) dollCorner = "TOP_LEFT";
		if (dollHead == null || !java.util.List.of("FOLLOW", "TURN", "LOOK", "FORWARD", "AT_VIEWER").contains(dollHead)) dollHead = "FOLLOW";
		if (dollScale == null || !java.util.List.of("TRUE_SIZE", "FILL").contains(dollScale)) dollScale = "TRUE_SIZE";
		if (dollVignette < 0) dollVignette = "OFF".equals(dollOutline) ? 0 : "THICK".equals(dollOutline) ? 6 : 3;
		dollOutline = null;
		dollVignette = Math.clamp(dollVignette, 0, dollScreenPixels ? VIGNETTE_MAX : 16);
		if (dollShowWhen == null) dollShowWhen = new java.util.ArrayList<>();
		dollTilt = Math.clamp(dollTilt, -60, 60);
		dollLinger = Math.clamp(dollLinger, 0, 50);
		dollOutlineStrength = Math.clamp(dollOutlineStrength, 10, 100);
		if (dollLight == null || !java.util.List.of("BRIGHT", "WORLD").contains(dollLight)) dollLight = "BRIGHT";
		dollX = Math.clamp(dollX, 0, 4000);
		dollY = Math.clamp(dollY, 0, 4000);
		dollSize = Math.clamp(dollSize, dollScreenPixels ? 40 : 20, dollScreenPixels ? 800 : 200);
		dollOpacity = Math.clamp(dollOpacity, 10, 100);
		dollAngle = Math.clamp(dollAngle, -180, 180);
		if (armDetail == null || !java.util.List.of("HIGH", "MEDIUM", "LOW").contains(armDetail)) armDetail = "HIGH";
		if (armDissolve == null || !java.util.List.of("AUTO", "ON", "OFF").contains(armDissolve)) armDissolve = "AUTO";
		if (armBar == null || !java.util.List.of("GOO", "VANILLA").contains(armBar)) armBar = "GOO";
		armWheelScale = Double.isNaN(armWheelScale) ? 0.75 : Math.clamp(armWheelScale, 0.2, 1.0);
		armColour = dev.morphreborn.client.arm.ArmProtocol.colourHex(dev.morphreborn.client.arm.ArmProtocol.parseColour(armColour));
		armLook = dev.morphreborn.client.arm.ArmProtocol.LOOK_NAMES.get(dev.morphreborn.client.arm.ArmProtocol.parseLook(armLook));
		// whole 5% step (the slider's) in the range the wire allows, so a hand edit (NaN, 0, 99) can never stall or race the pulses
		armPulseSpeed = dev.morphreborn.client.arm.ArmProtocol.pulseSpeedFactor(dev.morphreborn.client.arm.ArmProtocol.pulseSpeedPercent(armPulseSpeed));
	}

	/**
	 * Settings from before 6.6.0 hold the doll's size, distances and vignette in GUI pixels: turn them into the screen
	 * pixels they cover at the GUI scale in use now, once. Called before the doll is first drawn or its settings opened.
	 */
	public static void dollToScreenPixels() {
		ClientConfig c = INSTANCE;
		if (c.dollScreenPixels) return;
		int scale = Math.max(1, (int) Math.round(net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale()));
		c.dollSize *= scale;
		c.dollX *= scale;
		c.dollY *= scale;
		c.dollVignette *= scale;
		c.dollScreenPixels = true;
		c.clamp();
		save();
	}
}
