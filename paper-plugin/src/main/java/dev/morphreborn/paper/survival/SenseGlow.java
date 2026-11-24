package dev.morphreborn.paper.survival;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Tendril Sense's glow, for one viewer only: the entity's flags byte (metadata index 0) sent to them with the glowing bit
 * set or put back. Through packetevents when it is installed (it is optional); without it nothing glows. The classes
 * that touch packetevents are only loaded when it is there.
 */
final class SenseGlow {
	private static @Nullable Boolean present;

	private SenseGlow() {}

	static void show(Player viewer, @Nullable Entity e, boolean glow) {
		if (e == null) return;
		if (present == null) present = Bukkit.getPluginManager().isPluginEnabled("packetevents");
		if (!present) return;
		try {
			Pe.send(viewer, e.getEntityId(), flags(e, glow));
		} catch (LinkageError | RuntimeException ex) {
			present = false;
		}
	}

	/** The entity's own flags as vanilla sends them, with the glowing bit as asked. */
	private static byte flags(Entity e, boolean glow) {
		int f = 0;
		if (e.getFireTicks() > 0) f |= 0x01;
		if (e.isSneaking()) f |= 0x02;
		if (e instanceof LivingEntity l) {
			if (l.isSwimming()) f |= 0x10;
			if (l.isInvisible()) f |= 0x20;
			if (l.isGliding()) f |= 0x80;
		}
		if (glow || e.isGlowing()) f |= 0x40;
		return (byte) f;
	}

	/** packetevents, kept apart so nothing loads it unless it is installed. */
	private static final class Pe {
		static void send(Player viewer, int entityId, byte flags) {
			java.util.List<com.github.retrooper.packetevents.protocol.entity.data.EntityData<?>> data = java.util.List.of(new com.github.retrooper.packetevents.protocol.entity.data.EntityData<>(0,
					com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes.BYTE, flags));
			com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
					new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata(entityId, data));
		}
	}
}
