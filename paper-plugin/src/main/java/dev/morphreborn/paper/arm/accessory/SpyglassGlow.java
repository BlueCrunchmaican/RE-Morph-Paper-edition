package dev.morphreborn.paper.arm.accessory;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import dev.morphreborn.paper.arm.rules.SpyglassRules;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * What makes the Tendril Spyglass's marked creatures glow for their marker and nobody else (my design, 2026-11-16): the entity's
 * flags byte (metadata index 0) with the glow bit set, sent to the marker only, through PacketEvents. Vanilla sends that byte again
 * whenever the creature's flags change (it catches fire, sneaks, starts sprinting), which would put the glow out, so while anything is
 * marked a packet listener sets the glow bit in every flags byte the marker is sent about a creature they have marked.
 * <p>
 * Needs PacketEvents: without it this class doesn't load (the constructor says so) and the spyglass runs with no glow. The listener is
 * on only while some player has the spyglass on ({@link #open}/{@link #close}); it reads a concurrent map of plain entity ids and touches
 * no world or entity state, as it runs on netty threads.
 */
public final class SpyglassGlow implements SpyglassAccessory.Glow {
	/** The entity flags byte's metadata index. */
	private static final int FLAGS = 0;

	/** {@link #marked}'s value when the creature's real flags byte hasn't been heard since it was marked. */
	private static final int UNKNOWN = -1;

	/**
	 * Marker entity id to the entity ids they have marked, each with the last flags byte the game itself sent the marker about it
	 * ({@link #UNKNOWN} until one is): the netty threads' view. That byte is what a creature really is, sprinting included (no API says
	 * whether a mob sprints), so a mark put out gives the creature back as the game last said, not as this class can rebuild it. A mob that
	 * was sprinting when it was marked and has sent nothing since has no byte to give back: it is restored as not sprinting until the game's
	 * next flags update for it (visual only; the API has no way to read it).
	 */
	private final ConcurrentMap<Integer, ConcurrentMap<Integer, Integer>> marked = new ConcurrentHashMap<>();
	private @Nullable PacketListenerCommon listener;

	/** Fails with NoClassDefFoundError, quietly, where PacketEvents isn't installed (the spyglass then makes nothing glow). */
	public SpyglassGlow() {
		Class<?> probe = PacketEvents.class;
		if (probe == null) throw new IllegalStateException();
	}

	@Override
	public void open() {
		if (listener != null) return;
		if (PacketEvents.getAPI() == null) throw new IllegalStateException("PacketEvents isn't ready");
		listener = PacketEvents.getAPI().getEventManager().registerListener(new Rewriter(marked));
	}

	@Override
	public void close() {
		PacketListenerCommon l = listener;
		listener = null;
		marked.clear();
		if (l != null && PacketEvents.getAPI() != null) PacketEvents.getAPI().getEventManager().unregisterListener(l);
	}

	@Override
	public void forget(int ownerEntityId) {
		marked.remove(ownerEntityId);
	}

	/** The owner's copy of the target's flags byte: with the glow bit while marked, as the creature really is once not. Main thread. */
	@Override
	public void show(Player owner, Entity target, boolean glow) {
		try {
			int id = target.getEntityId();
			// the map is updated first: the rewriter sees (and leaves alone) the packet sent below
			int heard = UNKNOWN;
			if (glow) {
				heard = marked.computeIfAbsent(owner.getEntityId(), k -> new ConcurrentHashMap<>()).computeIfAbsent(id, k -> UNKNOWN);
			} else {
				ConcurrentMap<Integer, Integer> mine = marked.get(owner.getEntityId());
				if (mine != null) {
					Integer was = mine.remove(id);
					if (was != null) heard = was;
					if (mine.isEmpty()) marked.remove(owner.getEntityId(), mine);
				}
			}
			if (PacketEvents.getAPI() == null || !owner.isOnline()) return;
			byte flags = heard != UNKNOWN ? (byte) heard : flags(target);
			if (glow) flags = SpyglassRules.withGlow(flags);
			else if (heard != UNKNOWN) flags = SpyglassRules.restored(flags, target.isGlowing()); // the creature's own glow stays
			List<EntityData<?>> data = List.of(new EntityData<>(FLAGS, EntityDataTypes.BYTE, flags));
			PacketEvents.getAPI().getPlayerManager().sendPacket(owner, new WrapperPlayServerEntityMetadata(id, data));
		} catch (Throwable t) {
			// a client that can't be sent to simply isn't shown the glow
		}
	}

	/** The creature's own flags as vanilla sends them (its glow here is the real one: an effect or a command, not a mark). */
	private static byte flags(Entity e) {
		boolean sprinting = e instanceof Player p && p.isSprinting(); // (a mob's is known only from what the game sends once it is marked)
		if (e instanceof LivingEntity l) {
			return SpyglassRules.flags(e.getFireTicks() > 0, e.isSneaking(), sprinting, l.isSwimming(), l.isInvisible(), e.isGlowing(), l.isGliding());
		}
		return SpyglassRules.flags(e.getFireTicks() > 0, e.isSneaking(), false, false, false, e.isGlowing(), false);
	}

	private static final class Rewriter extends PacketListenerAbstract {
		private final ConcurrentMap<Integer, ConcurrentMap<Integer, Integer>> marked;

		Rewriter(ConcurrentMap<Integer, ConcurrentMap<Integer, Integer>> marked) {
			super(PacketListenerPriority.NORMAL);
			this.marked = marked;
		}

		/** A flags byte sent to a marker about a creature they marked has the glow bit set, whatever the game's own update says. */
		@Override
		@SuppressWarnings("unchecked")
		public void onPacketSend(PacketSendEvent event) {
			if (marked.isEmpty() || event.getPacketType() != PacketType.Play.Server.ENTITY_METADATA) return;
			try {
				if (!(event.getPlayer() instanceof Player viewer)) return;
				ConcurrentMap<Integer, Integer> mine = marked.get(viewer.getEntityId());
				if (mine == null || mine.isEmpty()) return;
				WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(event);
				int target = packet.getEntityId();
				if (!mine.containsKey(target)) return;
				boolean changed = false;
				for (EntityData<?> data : packet.getEntityMetadata()) {
					if (data.getIndex() != FLAGS || !(data.getValue() instanceof Byte flags) || (flags & SpyglassRules.FLAG_GLOWING) != 0) continue;
					// the game's own byte (the packets sent by show() carry the bit already): kept, as the creature really is
					mine.replace(target, flags & 0xFF);
					((EntityData<Byte>) data).setValue(SpyglassRules.withGlow(flags));
					changed = true;
				}
				if (changed) event.markForReEncode(true);
			} catch (Throwable t) {
				// an update that can't be read is passed on as it is
			}
		}
	}
}
