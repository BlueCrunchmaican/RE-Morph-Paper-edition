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
	/** Distance from the corner's side and top/bottom edge, in GUI pixels. */
	public int dollX = 8;
	public int dollY = 8;
	/** Height of the doll's box, in GUI pixels. */
	public int dollSize = 50;
	/** How solid the doll is drawn, in percent. */
	public int dollOpacity = 100;
	/** Which way the doll faces: 0 = straight at you, positive = turned to its left. */
	public int dollAngle = 25;
	/** FOLLOW (the head turns and looks as yours does) or FORWARD (head straight ahead). */
	public String dollHead = "FOLLOW";
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

	public static void load() {
		if (Files.exists(FILE)) {
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
		if (dollShow == null || !java.util.List.of("ALWAYS", "MORPHED", "ACTIVE").contains(dollShow)) dollShow = "ALWAYS";
		if (dollCorner == null || !java.util.List.of("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT").contains(dollCorner)) dollCorner = "TOP_LEFT";
		if (dollHead == null || !java.util.List.of("FOLLOW", "FORWARD").contains(dollHead)) dollHead = "FOLLOW";
		if (dollLight == null || !java.util.List.of("BRIGHT", "WORLD").contains(dollLight)) dollLight = "BRIGHT";
		dollX = Math.clamp(dollX, 0, 1000);
		dollY = Math.clamp(dollY, 0, 1000);
		dollSize = Math.clamp(dollSize, 20, 200);
		dollOpacity = Math.clamp(dollOpacity, 10, 100);
		dollAngle = Math.clamp(dollAngle, -180, 180);
	}
}
