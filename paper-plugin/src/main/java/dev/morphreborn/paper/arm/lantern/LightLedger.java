package dev.morphreborn.paper.arm.lantern;

import dev.morphreborn.paper.arm.rules.LightBeam.Light;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What one lit lantern has shown to each player around it (spec 3.10): for every viewer, the fake light cells their
 * client was last sent. The light is only ever sent, never placed in the world, so the ledger is what makes sure each
 * viewer's client is put right again: a cell no longer wanted goes back to the real block, a viewer who goes out of
 * range gets all of theirs back, and a lantern put out gives every viewer everything back. On every one of those exits
 * the cells restored are exactly the cells sent and not yet taken back, so nothing is left showing light.
 * <p>
 * The ledger itself knows nothing of the server: players are ids, cells are {@code BlockPos.asLong} keys, and a
 * {@link Sender} does the sending (it restores a cell to whatever the real block is at that moment).
 */
public final class LightLedger {
	/** The two things that can be sent to a viewer. */
	public interface Sender {
		/** Show these light cells to the viewer (a multi block change). */
		void lights(UUID viewer, Map<Long, Light> cells);

		/** Show the viewer the real block in each of these cells again. */
		void restore(UUID viewer, Set<Long> cells);
	}

	/** Per viewer, the cells their client was last sent and what each shows. */
	private final Map<UUID, Map<Long, Light>> sent = new HashMap<>();
	/** Cells sent and restored through this ledger so far, for the console report. */
	private long sentCells, restoredCells;

	/**
	 * Bring every viewer's client in line with {@code wanted}: new or changed cells are sent, cells no longer wanted are
	 * restored, and a viewer not in {@code viewers} any more (out of range, gone) gets everything restored. {@code full}
	 * sends every wanted cell again, even those the viewer already has, which puts the light back after a client
	 * reloaded its chunks.
	 *
	 * @param viewers who should see the light now
	 * @param wanted the cells that should show light, by BlockPos.asLong
	 */
	public void sync(Set<UUID> viewers, Map<Long, Light> wanted, boolean full, Sender out) {
		for (Iterator<Map.Entry<UUID, Map<Long, Light>>> it = sent.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Map<Long, Light>> e = it.next();
			if (viewers.contains(e.getKey())) continue;
			it.remove();
			give(e.getKey(), new HashSet<>(e.getValue().keySet()), out);
		}
		for (UUID v : viewers) {
			Map<Long, Light> have = sent.computeIfAbsent(v, k -> new HashMap<>());
			Set<Long> gone = new HashSet<>();
			for (Long pos : have.keySet()) {
				if (!wanted.containsKey(pos)) gone.add(pos);
			}
			Map<Long, Light> send = new LinkedHashMap<>();
			for (Map.Entry<Long, Light> w : wanted.entrySet()) {
				if (full || !w.getValue().equals(have.get(w.getKey()))) send.put(w.getKey(), w.getValue());
			}
			if (!gone.isEmpty()) {
				have.keySet().removeAll(gone);
				give(v, gone, out);
			}
			if (!send.isEmpty()) {
				have.putAll(send);
				sentCells += send.size();
				out.lights(v, send);
			}
			if (have.isEmpty()) sent.remove(v);
		}
	}

	/** One viewer's cells restored and forgotten (they left range or the world); what was restored. */
	public Set<Long> drop(UUID viewer, Sender out) {
		Map<Long, Light> have = sent.remove(viewer);
		if (have == null || have.isEmpty()) return Set.of();
		Set<Long> cells = new HashSet<>(have.keySet());
		give(viewer, cells, out);
		return cells;
	}

	/**
	 * Every viewer's cells restored and the ledger emptied (the lantern went out): per viewer, what was restored.
	 */
	public Map<UUID, Set<Long>> clear(Sender out) {
		Map<UUID, Set<Long>> restored = new LinkedHashMap<>();
		for (UUID v : new ArrayList<>(sent.keySet())) {
			Set<Long> cells = drop(v, out);
			if (!cells.isEmpty()) restored.put(v, cells);
		}
		sent.clear();
		return restored;
	}

	/** What the viewer's client was last sent and has not had restored: the cells it shows as light. */
	public Set<Long> shown(UUID viewer) {
		Map<Long, Light> have = sent.get(viewer);
		return have == null ? Set.of() : Set.copyOf(have.keySet());
	}

	/** Viewers with something shown. */
	public List<UUID> viewers() {
		return List.copyOf(sent.keySet());
	}

	public boolean isEmpty() {
		return sent.isEmpty();
	}

	/** Cells sent and cells restored so far, for the console report. */
	public long sentCells() {
		return sentCells;
	}

	public long restoredCells() {
		return restoredCells;
	}

	private void give(UUID viewer, Set<Long> cells, Sender out) {
		if (cells.isEmpty()) return;
		restoredCells += cells.size();
		out.restore(viewer, cells);
	}
}
