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
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("morphreborn-client.json");

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
	/** Keep the player name tag above morphed players. */
	public boolean showNameTags = true;
	/** Survival Mode biomass bar: OFF, CHANGES (a few seconds after it changes) or ALWAYS. */
	public String biomassBar = "CHANGES";

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
	/** Ask GitHub once per game start whether a newer RE:Morph is out, and say so in chat (see UpdateCheck). */
	public boolean updateCheck = true;

	/** The vignette slider's top, in screen pixels. */
	public static final int VIGNETTE_MAX = 32;

	public static void load() {
		boolean fresh = !Files.exists(FILE);
		if (fresh) {
			INSTANCE.dollScreenPixels = true;
			INSTANCE.dollVignette = 6;
		} else {
			try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
				ClientConfig loaded = GSON.fromJson(r, ClientConfig.class);
				if (loaded != null) INSTANCE = loaded;
			} catch (IOException | JsonParseException e) {
				MorphClient.LOGGER.warn("Could not read {}, using defaults: {}", FILE, e.toString());
			}
		}
		INSTANCE.clamp();
		save();
	}

	public static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
				GSON.toJson(INSTANCE, w);
			}
		} catch (IOException e) {
			MorphClient.LOGGER.warn("Could not save {}: {}", FILE, e.toString());
		}
	}

	private void clamp() {
		selectorDistanceFromTop = Math.max(0, selectorDistanceFromTop);
		selectorScale = Math.clamp(selectorScale, 0.25, 4.0);
		radialScale = Math.clamp(radialScale, 0.2, 1.0);
		acquisitionTendrilMaxChild = Math.clamp(acquisitionTendrilMaxChild, 3, 100);
		acquisitionTendrilPartOpacity = Math.clamp(acquisitionTendrilPartOpacity, 0, 100);
		if (biomassBar == null || !java.util.List.of("OFF", "CHANGES", "ALWAYS").contains(biomassBar)) biomassBar = "CHANGES";
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
