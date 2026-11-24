package dev.morphreborn.client.arm;

import dev.morphreborn.client.net.Wire;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * This player's own arsenal (S_ARSENAL): whether arm forms can be used here at all and why not, what they cost, and
 * each form the server has built, in the wheel's order, with its level, its special and the special's wait. The server
 * sends it on the handshake, after a purchase, a mode switch or a reload, on every form change and after every special;
 * the wheel, the bar and the tree only read it. Never predicted: the client waits for the next one.
 * <p>
 * Waits arrive as ticks left and become end ticks on {@link Arms#TICK}, counted down here between packets, as the rage
 * meter's are.
 */
public final class ArmArsenal {
	/**
	 * One form: its id and display name; the level owned (0 = not owned) and the most there is; its special's name, what
	 * it costs in thousandths of biomass, its whole wait, and when that wait ends.
	 */
	public record Entry(String id, String name, int level, int maxLevel, String specialName, int specialCostMilli, int cooldownTicks,
			long cooldownEndTick) {
		public boolean owned() {
			return level > 0;
		}

		/** Ticks of the special's wait left at {@code now} ({@link Arms#TICK}); 0 when it is ready. */
		public int cooldownLeft(long now) {
			return (int) Math.max(0L, cooldownEndTick - now);
		}
	}

	/**
	 * One accessory (S_ARSENAL, from {@link ArmProtocol#NEW_FORMS_SINCE}): its id, the level owned (0 = not owned) and whether it is
	 * on. The server lists the accessories it has built, in the wheel's ring order; one it has not built is not listed.
	 */
	public record AccessoryEntry(String id, int level, boolean on) {
		public boolean owned() {
			return level > 0;
		}
	}

	private static final List<AccessoryEntry> NO_ACCESSORIES = List.of();

	/** Before the server has said anything, or on a server without arm forms: nothing usable, nothing listed. */
	public static final ArmArsenal EMPTY = new ArmArsenal(false, "", true, 0, 0, 0, 0, 0, List.of(), "", 0, false, 0, 0, 0L, 0L);

	private final boolean usable;
	private final String whyNot;
	private final boolean free;
	private final int perBlockMilli, perHitMilli, perBoltMilli, drainMilliPerMinute, lanternDrainMilliPerMinute;
	private final List<Entry> entries;
	private final String lastForm;
	private final int lanternLevel;
	private final boolean lanternLit;
	private final List<AccessoryEntry> accessories;
	/** {@link #accessories()}, worked out once: the wheel asks for it every frame and on every mouse move. */
	private final List<AccessoryEntry> ring;
	private final int flareCostMilli, flareCooldownTicks;
	private final long flareCooldownEndTick;
	private final long receivedTick;

	public ArmArsenal(boolean usable, String whyNot, boolean free, int perBlockMilli, int perHitMilli, int perBoltMilli, int drainMilliPerMinute,
			int lanternDrainMilliPerMinute, List<Entry> entries, String lastForm, int lanternLevel, boolean lanternLit, int flareCostMilli,
			int flareCooldownTicks, long flareCooldownEndTick, long receivedTick) {
		this(usable, whyNot, free, perBlockMilli, perHitMilli, perBoltMilli, drainMilliPerMinute, lanternDrainMilliPerMinute, entries, lastForm,
				lanternLevel, lanternLit, flareCostMilli, flareCooldownTicks, flareCooldownEndTick, receivedTick, NO_ACCESSORIES);
	}

	public ArmArsenal(boolean usable, String whyNot, boolean free, int perBlockMilli, int perHitMilli, int perBoltMilli, int drainMilliPerMinute,
			int lanternDrainMilliPerMinute, List<Entry> entries, String lastForm, int lanternLevel, boolean lanternLit, int flareCostMilli,
			int flareCooldownTicks, long flareCooldownEndTick, long receivedTick, List<AccessoryEntry> accessories) {
		this.usable = usable;
		this.whyNot = whyNot == null ? "" : whyNot;
		this.free = free;
		this.perBlockMilli = perBlockMilli;
		this.perHitMilli = perHitMilli;
		this.perBoltMilli = perBoltMilli;
		this.drainMilliPerMinute = drainMilliPerMinute;
		this.lanternDrainMilliPerMinute = lanternDrainMilliPerMinute;
		this.entries = List.copyOf(entries);
		this.lastForm = lastForm == null ? "" : lastForm;
		this.lanternLevel = lanternLevel;
		this.lanternLit = lanternLit;
		this.accessories = List.copyOf(accessories);
		this.flareCostMilli = flareCostMilli;
		this.flareCooldownTicks = flareCooldownTicks;
		this.flareCooldownEndTick = flareCooldownEndTick;
		this.receivedTick = receivedTick;
		this.ring = ring();
	}

	/**
	 * Reads S_ARSENAL after its id, received at {@code now} ({@link Arms#TICK}): bool usable; string why not; bool free;
	 * varInt per block, per hit, per bolt (thousandths of biomass); varInt drain and lantern drain per minute; varInt n,
	 * then n times {string id, string name, varInt level, varInt max level, string special name, varInt special cost,
	 * varInt wait, varInt wait left}; string last form; varInt lantern level; bool lantern lit; varInt flare cost, flare
	 * wait, flare wait left; then, from servers at {@link ArmProtocol#NEW_FORMS_SINCE}, varInt n and n times {string accessory id,
	 * varInt level owned, bool on}. A newer server's fields come after all of that and are left unread.
	 */
	public static ArmArsenal read(Wire.In in, long now) {
		boolean usable = in.bool();
		String whyNot = in.string();
		boolean free = in.bool();
		int perBlock = in.varInt();
		int perHit = in.varInt();
		int perBolt = in.varInt();
		int drain = in.varInt();
		int lanternDrain = in.varInt();
		int n = in.count();
		List<Entry> entries = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			String id = in.string();
			String name = in.string();
			int level = in.varInt();
			int maxLevel = in.varInt();
			String special = in.string();
			int cost = in.varInt();
			int wait = in.varInt();
			int left = in.varInt();
			entries.add(new Entry(id, name, level, maxLevel, special, cost, wait, now + Math.max(0, left)));
		}
		String lastForm = in.string();
		int lanternLevel = in.varInt();
		boolean lanternLit = in.bool();
		int flareCost = in.varInt();
		int flareWait = in.varInt();
		int flareLeft = in.varInt();
		List<AccessoryEntry> accessories = NO_ACCESSORIES;
		if (in.hasMore()) {
			int count = in.count();
			List<AccessoryEntry> list = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				String id = in.string();
				int level = in.varInt();
				boolean on = in.bool();
				// an accessory a newer server has and this client does not is skipped
				if (ArmProtocol.isAccessory(id)) list.add(new AccessoryEntry(id, Math.max(0, level), on));
			}
			accessories = list;
		}
		return new ArmArsenal(usable, whyNot, free, perBlock, perHit, perBolt, drain, lanternDrain, entries, lastForm, lanternLevel, lanternLit,
				flareCost, flareWait, now + Math.max(0, flareLeft), now, accessories);
	}

	/** Arm forms can be formed here at all (bought Arm Shaping, the server has them on, this game mode allows them). */
	public boolean usable() {
		return usable;
	}

	/** The server's reason when they can't ("" when they can), shown as it is. */
	public String whyNot() {
		return whyNot;
	}

	/** Nothing costs biomass (Classic, creative, or costs switched off): no bar, no drain. */
	public boolean free() {
		return free;
	}

	/** The built forms, in the wheel's order (never the lantern). */
	public List<Entry> entries() {
		return entries;
	}

	public @Nullable Entry entry(String id) {
		for (Entry e : entries) {
			if (e.id().equals(id)) return e;
		}
		return null;
	}

	/** The form a tap of the arm key re-forms ("" when there is none yet). */
	public String lastForm() {
		return lastForm;
	}

	/**
	 * The accessories the wheel's inner ring shows, in the ring's order ({@code ArmProtocol.ACCESSORIES}'s, whatever order the
	 * server listed them in): what the server listed, and for a server before {@link ArmProtocol#NEW_FORMS_SINCE} (or one that left
	 * the lantern out) the lantern from its own fields when it is owned. An accessory the server has not built (or lists at level 0) is not here.
	 */
	public List<AccessoryEntry> accessories() {
		return ring;
	}

	private List<AccessoryEntry> ring() {
		List<AccessoryEntry> out = new ArrayList<>(accessories.size() + 1);
		for (String id : ArmProtocol.ACCESSORIES) {
			AccessoryEntry listed = accessory(id);
			// listed at level 0 means not grown: hidden like an unbuilt one (2026-11-16: it showed as a dimmed lump)
			if (listed != null) {
				if (listed.level() > 0) out.add(listed);
			}
			else if (ArmProtocol.LANTERN.equals(id) && lanternLevel > 0) out.add(new AccessoryEntry(id, lanternLevel, lanternLit));
		}
		return List.copyOf(out);
	}

	/** {@code id}'s entry as the server listed it, or null (the lantern of an older server is in {@link #accessories()}, not here). */
	public @Nullable AccessoryEntry accessory(String id) {
		for (int i = 0; i < accessories.size(); i++) { // indexed: no iterator per call
			AccessoryEntry e = accessories.get(i);
			if (e.id().equals(id)) return e;
		}
		return null;
	}

	/** The lantern's level (0 = not owned) and whether it is lit. */
	public int lanternLevel() {
		return lanternLevel;
	}

	public boolean lanternLit() {
		return lanternLit;
	}

	/** Costs in thousandths of biomass: a block broken, a hit landed, a bolt shot. */
	public int perBlockMilli() {
		return perBlockMilli;
	}

	public int perHitMilli() {
		return perHitMilli;
	}

	public int perBoltMilli() {
		return perBoltMilli;
	}

	/** What a formed arm, and a lit lantern on top, drain per minute (thousandths of biomass). */
	public int drainMilliPerMinute() {
		return drainMilliPerMinute;
	}

	public int lanternDrainMilliPerMinute() {
		return lanternDrainMilliPerMinute;
	}

	/** The lantern's Flare: its cost, its whole wait, and when the wait ends ({@link Arms#TICK}). */
	public int flareCostMilli() {
		return flareCostMilli;
	}

	public int flareCooldownTicks() {
		return flareCooldownTicks;
	}

	public long flareCooldownEndTick() {
		return flareCooldownEndTick;
	}

	/** When this arrived ({@link Arms#TICK}). */
	public long receivedTick() {
		return receivedTick;
	}
}
