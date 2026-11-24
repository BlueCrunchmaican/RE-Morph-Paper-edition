package dev.morphreborn.client.dev;

import dev.morphreborn.client.MorphClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.InteractionHand;
import org.jspecify.annotations.Nullable;

/**
 * Development tool, not part of play: lets a test rig drive a dev client without keyboard focus. With
 * {@code MORPHREBORN_INBOX=<file>} set in a development environment, the client reads that file every few ticks,
 * deletes it, and runs each line:
 * <ul>
 *   <li>{@code /command}: sent as if typed in chat (client commands such as /morphclient included);</li>
 *   <li>{@code !command}: run by the integrated server's console, with full permission (single player only; for
 *       worlds without cheats);</li>
 *   <li>{@code click X Y}, {@code drag X Y DX DY}, {@code scroll X Y N}: a left click, drag or N wheel notches (negative:
 *       down) on the open screen, in GUI pixels;</li>
 *   <li>{@code close}: closes the open screen;</li>
 *   <li>{@code camera first|back|front}, {@code look YAW PITCH}, {@code slot N} (1-9), {@code swing}, {@code hud on|off};</li>
 *   <li>{@code light [N]}: a dump of the block light round the player (see {@link #lightDump});</li>
 *   <li>{@code drop [all]}, {@code swaphands}, {@code invclick SLOT BUTTON MODE}: Q, F and a click in the own inventory.</li>
 *   <li>{@code inventory}: opens the own inventory (E); {@code close} closes it.</li>
 * </ul>
 * Anything else is logged and skipped. Off (null) outside development or without the variable.
 */
public final class CommandInbox {
	private static final @Nullable Path FILE = file();
	private static int clock;

	private CommandInbox() {}

	private static @Nullable Path file() {
		if (!net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) return null;
		String path = System.getenv("MORPHREBORN_INBOX");
		return path == null || path.isBlank() ? null : Path.of(path);
	}

	public static void tick(Minecraft mc) {
		if (FILE == null || mc.player == null || ++clock % 5 != 0 || !Files.isRegularFile(FILE)) return;
		List<String> lines;
		try {
			lines = Files.readAllLines(FILE, StandardCharsets.UTF_8);
			Files.delete(FILE);
		} catch (IOException e) {
			return; // still being written: next time
		}
		for (String raw : lines) {
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) continue;
			try {
				run(mc, line);
			} catch (RuntimeException e) {
				MorphClient.LOGGER.warn("Inbox line failed: {}", line, e);
			}
		}
	}

	private static void run(Minecraft mc, String line) {
		if (line.startsWith("/")) {
			mc.player.connection.sendCommand(line.substring(1));
			return;
		}
		if (line.startsWith("!")) {
			var server = mc.getSingleplayerServer();
			if (server == null) {
				MorphClient.LOGGER.warn("Inbox: {} needs a single-player world", line);
				return;
			}
			String command = line.substring(1);
			server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
			return;
		}
		String[] a = line.split("\\s+");
		Screen screen = mc.gui.screen();
		switch (a[0]) {
			case "click" -> {
				if (screen == null) return;
				MouseButtonEvent e = new MouseButtonEvent(Double.parseDouble(a[1]), Double.parseDouble(a[2]), new MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0));
				screen.mouseClicked(e, false);
				screen.mouseReleased(e);
			}
			case "drag" -> {
				if (screen == null) return;
				double x = Double.parseDouble(a[1]), y = Double.parseDouble(a[2]);
				double dx = Double.parseDouble(a[3]), dy = Double.parseDouble(a[4]);
				MouseButtonInfo left = new MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0);
				screen.mouseClicked(new MouseButtonEvent(x, y, left), false);
				screen.mouseDragged(new MouseButtonEvent(x + dx, y + dy, left), dx, dy);
				screen.mouseReleased(new MouseButtonEvent(x + dx, y + dy, left));
			}
			case "scroll" -> {
				if (screen == null) return;
				double x = Double.parseDouble(a[1]), y = Double.parseDouble(a[2]);
				int notches = Integer.parseInt(a[3]);
				for (int i = 0; i < Math.abs(notches); i++) screen.mouseScrolled(x, y, 0, Math.signum(notches));
			}
			case "close" -> mc.gui.setScreen(null);
			case "inventory" -> mc.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player)); // E
			case "sneak" -> mc.options.keyShift.setDown(a[1].equals("on")); // held until "sneak off"
			// a left click on what the crosshair finds (its reach is the player's own entity_interaction_range), else a swing
			case "attack" -> {
				if (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit) mc.gameMode.attack(mc.player, hit.getEntity());
				mc.player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
			}
			case "camera" -> mc.options.setCameraType(switch (a[1]) {
				case "back" -> CameraType.THIRD_PERSON_BACK;
				case "front" -> CameraType.THIRD_PERSON_FRONT;
				default -> CameraType.FIRST_PERSON;
			});
			case "look" -> {
				mc.player.setYRot(Float.parseFloat(a[1]));
				mc.player.setXRot(Float.parseFloat(a[2]));
				mc.player.yRotO = mc.player.getYRot();
				mc.player.xRotO = mc.player.getXRot();
			}
			case "slot" -> mc.player.getInventory().setSelectedSlot(Integer.parseInt(a[1]) - 1);
			case "swing" -> mc.player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
			case "hud" -> {
				if (mc.gui.hud.isHidden() == a[1].equals("on")) mc.gui.hud.toggle();
			}
			case "shaders" -> shaders(a[1].equals("on"));
			case "use" -> mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND); // a right-click with what is in the main hand
			case "pulse" -> dev.morphreborn.client.arm.render.Loft.pulseLighting = a[1].equals("on");
			case "shaderpack" -> shaderPack(line.substring(line.indexOf(' ') + 1).trim());
			case "light" -> lightDump(mc, a.length > 1 ? Integer.parseInt(a[1]) : 40);
			case "drop" -> mc.gameMode.dropItem(mc.player, a.length > 1 && a[1].equals("all")); // Q, or Ctrl+Q with "all"
			case "swaphands" -> mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
					net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, net.minecraft.core.BlockPos.ZERO,
					net.minecraft.core.Direction.DOWN)); // F
			// invclick SLOT BUTTON MODE: a click in the player's own inventory menu (hotbar 36-44, offhand 45), MODE a ContainerInput
			case "invclick" -> mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, Integer.parseInt(a[1]), Integer.parseInt(a[2]),
					net.minecraft.world.inventory.ContainerInput.valueOf(a[3]), mc.player);
			default -> MorphClient.LOGGER.warn("Inbox: unknown line {}", line);
		}
	}

	/**
	 * {@code light [N]}: the block light the client sees at the player's feet height, out N blocks along +X, -X, +Z and -Z
	 * (one line each), written next to the inbox as {@code <inbox>.light}: to measure light sent as block changes.
	 */
	private static void lightDump(Minecraft mc, int n) {
		var level = mc.level;
		if (level == null || FILE == null) return;
		net.minecraft.core.BlockPos at = mc.player.blockPosition();
		StringBuilder out = new StringBuilder();
		int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		String[] names = {"+x", "-x", "+z", "-z"};
		for (int d = 0; d < 4; d++) {
			out.append(names[d]);
			for (int i = 0; i <= n; i++) {
				out.append(' ').append(level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at.offset(dirs[d][0] * i, 0, dirs[d][1] * i)));
			}
			out.append('\n');
		}
		try {
			Files.writeString(FILE.resolveSibling(FILE.getFileName() + ".light"), out.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			MorphClient.LOGGER.warn("Inbox: could not write the light dump", e);
		}
	}

	/** {@code shaders on|off}: Iris's shaders, through its API by name (no compile-time dependency; a no-op without Iris). */
	private static void shaders(boolean on) {
		try {
			Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			Object instance = api.getMethod("getInstance").invoke(null);
			Object config = api.getMethod("getConfig").invoke(instance);
			config.getClass().getMethod("setShadersEnabledAndApply", boolean.class).invoke(config, on);
		} catch (ReflectiveOperationException | LinkageError e) {
			MorphClient.LOGGER.warn("Inbox: no Iris to turn shaders {}", on ? "on" : "off", e);
		}
	}

	/** {@code shaderpack NAME}: Iris's shader pack by file name, then shaders on (Iris's own config, by name). */
	private static void shaderPack(String name) {
		try {
			Object config = Class.forName("net.irisshaders.iris.Iris").getMethod("getIrisConfig").invoke(null);
			config.getClass().getMethod("setShaderPackName", String.class).invoke(config, name);
		} catch (ReflectiveOperationException | LinkageError e) {
			MorphClient.LOGGER.warn("Inbox: could not set the shader pack to {}", name, e);
			return;
		}
		shaders(true);
	}
}
