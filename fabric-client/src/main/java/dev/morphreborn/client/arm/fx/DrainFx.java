package dev.morphreborn.client.arm.fx;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.ArmTint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Draining Cut's healing tendrils (S_ARM_FX DRAIN and PULSE): a red tendril from each creature the cut latched on to,
 * out of its wound and into the blade, carrying health home. I asked for "tendrils from the enemy to the sword
 * giving you some health", so unlike the frenzy's (which reach from the player to the creature) these grow the other
 * way: out of the creature, toward the blade, over 4 ticks, and they latch on with a knot.
 * <ul>
 *   <li>DRAIN (stateful) lists every creature latched now; the server resends it on every change and every second. A
 *       creature no longer listed lets go: its tendril lets go of the blade and snaps back into it over 4 ticks, drawn
 *       back the way the frenzy's are, by cutting it shorter along its own writhing path (an empty list lets go of all).
 *       One that died just goes, because the usual absorb plays for it instead. A drain the server hasn't mentioned for
 *       3 seconds lets go too (its striker went out of range, or the end was lost).</li>
 *   <li>PULSE is one heal on its way: a strong swelling running from the creature to the blade in exactly the
 *       {@code ticks} the server gave, so it reaches the blade on the tick the health lands. Between pulses a faint
 *       swelling runs along every 14 ticks. A pulse may come before the DRAIN that lists its creature (the server sends
 *       the pulse first), so it starts the tendril itself.</li>
 * </ul>
 * Widths are 2.2 px at the creature and 1.0 at the blade, halved for your own arm in first person, where the blade is
 * close to the eye. At most 8 tendrils per striker.
 */
public final class DrainFx implements FxHandler {
	/** Ticks a tendril takes to reach the blade, and to snap back. */
	static final int GROW = 4, RELEASE = 4;
	static final int MAX = 8;
	static final float IDLE_AMP = 0.3F, IDLE_PERIOD = 14F, PULSE_AMP = 1F;
	static final float WIDTH_CREATURE = 2.2F, WIDTH_BLADE = 1.0F, OWN_SCALE = 0.5F, KNOT_PX = 1.2F;

	/** One heal on its way: when it set off and how long it takes (ticks, at least 1). */
	record Pulse(long start, int travel) {}

	static final class Link {
		final int target;
		final long start;
		/** When it let go (-1: still latched). */
		long released = -1;
		/**
		 * Once it has let go: where its end was, from the creature's wound (blocks), taken the first frame it is drawn
		 * after letting go (NaN until then). It no longer follows the blade; it snaps back from there into the creature,
		 * which carries it along as it moves.
		 */
		double letGoX = Double.NaN, letGoY, letGoZ;
		final List<Pulse> pulses = new ArrayList<>(2);

		Link(int target, long start) {
			this.target = target;
			this.start = start;
		}

		boolean latched() {
			return released < 0;
		}
	}

	static final class Drain {
		final Map<Integer, Link> links = new LinkedHashMap<>();
		/** When the server last mentioned this drain. */
		long heard;

		int latched() {
			int n = 0;
			for (Link l : links.values()) if (l.latched()) n++;
			return n;
		}
	}

	/** Strikers' entity ids, to their drains. */
	private final Map<Integer, Drain> drains = new HashMap<>();

	public DrainFx() {}

	@Override
	public int[] kinds() {
		return new int[] {ArmProtocol.FX_DRAIN, ArmProtocol.FX_PULSE};
	}

	@Override
	public void start(ArmFxEvent e) {
		long now = e.receivedTick();
		if (e.kind() == ArmProtocol.FX_DRAIN) {
			Drain d = drains.get(e.origin());
			if (e.ends()) {
				if (d != null) letGoAll(d, now);
				return;
			}
			if (d == null) drains.put(e.origin(), d = new Drain());
			d.heard = now;
			Set<Integer> listed = new HashSet<>();
			for (int id : e.ids()) listed.add(id);
			for (Link l : d.links.values()) {
				if (l.latched() && !listed.contains(l.target)) l.released = now;
			}
			for (int id : e.ids()) {
				Link l = d.links.get(id);
				if (l != null && l.latched()) continue;
				if (d.latched() >= MAX) break;
				d.links.put(id, new Link(id, now)); // caught again while letting go: a new tendril
			}
		} else if (e.kind() == ArmProtocol.FX_PULSE) {
			if (e.ids().length == 0) return;
			int id = e.ids()[0];
			Drain d = drains.computeIfAbsent(e.origin(), k -> new Drain());
			d.heard = now;
			Link l = d.links.get(id);
			if (l == null || !l.latched()) {
				if (d.latched() >= MAX) return;
				d.links.put(id, l = new Link(id, now));
			}
			l.pulses.add(new Pulse(now, Math.max(1, e.ticks())));
		}
	}

	@Override
	public void tick() {
		long now = Arms.TICK;
		for (Iterator<Drain> it = drains.values().iterator(); it.hasNext(); ) {
			Drain d = it.next();
			if (now - d.heard > ArmProtocol.STATEFUL_TIMEOUT_TICKS) letGoAll(d, now);
			for (Iterator<Link> links = d.links.values().iterator(); links.hasNext(); ) {
				Link l = links.next();
				// a pulse stays until it has arrived (on the tick its travel ends), then goes
				l.pulses.removeIf(p -> now - p.start > p.travel);
				if (!l.latched() && now - l.released >= RELEASE) links.remove();
			}
			if (d.links.isEmpty()) it.remove();
		}
	}

	@Override
	public void clear() {
		drains.clear();
	}

	private static void letGoAll(Drain d, long now) {
		for (Link l : d.links.values()) {
			if (l.latched()) l.released = now;
			l.pulses.clear(); // nothing heals once it has let go
		}
	}

	/**
	 * How far along its tendril a pulse is, 0 at the creature to 1 at the blade, {@code age} ticks after it set off:
	 * straight on, arriving at the blade at exactly {@code travel} ticks, when the server lands the heal.
	 */
	static float pulsePosition(float age, int travel) {
		return ArmMath.clamp01(age / Math.max(1, travel));
	}

	/** How far a tendril has grown, {@code age} ticks after it set off: eased out, always ahead of a pulse sent with it. */
	static float grown(float age) {
		float x = ArmMath.clamp01(age / GROW);
		return 1F - (1F - x) * (1F - x);
	}

	/**
	 * Where the newest pulse in flight on {@code origin}'s tendril to {@code target} is (as {@link #pulsePosition}), or
	 * -1 with none: for the tests, which check that a pulse arrives on the tick the server said.
	 */
	float pulseAt(int origin, int target, float partialTick) {
		Drain d = drains.get(origin);
		Link l = d == null ? null : d.links.get(target);
		if (l == null || l.pulses.isEmpty()) return -1F;
		Pulse p = l.pulses.get(l.pulses.size() - 1);
		return pulsePosition(Arms.age(p.start, partialTick), p.travel);
	}

	@Override
	public void collect(ArmFxFrame f) {
		if (drains.isEmpty()) return;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		float pt = f.partialTick();
		for (Map.Entry<Integer, Drain> entry : drains.entrySet()) {
			int originId = entry.getKey();
			ArmTint.owner(originId); // this effect is drawn in its owner's colour
			// null when the striker isn't in this world any more: tendrils that already let go can still snap back
			Vec3 blade = ArmFx.anchor(originId, ArmTips.MID);
			float scale = ArmFx.isOwnFirstPerson(originId, f.firstPersonCamera()) ? OWN_SCALE : 1F;
			for (Iterator<Link> it = entry.getValue().links.values().iterator(); it.hasNext(); ) {
				Link l = it.next();
				LivingEntity target = level.getEntity(l.target) instanceof LivingEntity living ? living : null;
				if (target == null || target.isRemoved() || target.isDeadOrDying()) {
					// dying or gone: the absorb takes over, nothing snaps back. One merely out of sight stays latched
					// until the server lets it go.
					if (target != null || !l.latched()) it.remove();
					continue;
				}
				float reach = grown(Arms.age(l.start, pt));
				if (!l.latched()) reach = Math.min(reach, 1F - ArmMath.sm(Arms.age(l.released, pt) / RELEASE));
				if (!(reach > 0.001F)) continue;
				Vec3 from = TendrilFx.wound(target, pt);
				Vec3 to = end(l, from, blade);
				if (to == null) continue;
				float[] swells = swells(l, pt);
				boolean pulsing = swells.length > 2 || swells[1] >= PULSE_AMP;
				int light = TendrilFx.light(level, from, to);
				TendrilFx.draw(f, from, to, reach, l.target * 1.7F, WIDTH_CREATURE * scale, WIDTH_BLADE * scale, swells, ArmLayer.RED, light,
						pulsing ? TendrilFx.ACTIVE : TendrilFx.CALM);
				if (l.latched() && reach >= 0.999F) TendrilFx.knot(f, to, KNOT_PX * scale, ArmLayer.RED, light, l.target);
			}
		}
	}

	/**
	 * Where a tendril ends this frame: on the blade while it is latched; once it has let go, where the blade was then,
	 * kept beside the creature (so it doesn't follow the blade away, and moves with the creature it is snapping back
	 * into). Null when neither is known.
	 */
	private static @Nullable Vec3 end(Link l, Vec3 wound, @Nullable Vec3 blade) {
		if (l.latched()) return blade;
		if (Double.isNaN(l.letGoX)) {
			if (blade == null) return null;
			l.letGoX = blade.x - wound.x;
			l.letGoY = blade.y - wound.y;
			l.letGoZ = blade.z - wound.z;
		}
		return wound.add(l.letGoX, l.letGoY, l.letGoZ);
	}

	/** The swellings on a tendril now: every pulse in flight at full strength, or the faint one running along between pulses. */
	private static float[] swells(Link l, float pt) {
		if (l.latched() && !l.pulses.isEmpty()) {
			float[] out = new float[2 * l.pulses.size()];
			for (int i = 0; i < l.pulses.size(); i++) {
				Pulse p = l.pulses.get(i);
				out[2 * i] = pulsePosition(Arms.age(p.start, pt), p.travel);
				out[2 * i + 1] = PULSE_AMP;
			}
			return out;
		}
		return new float[] {ArmMath.frac(Arms.age(l.start, pt) / IDLE_PERIOD + l.target * 0.31F), l.latched() ? IDLE_AMP : 0F};
	}
}
