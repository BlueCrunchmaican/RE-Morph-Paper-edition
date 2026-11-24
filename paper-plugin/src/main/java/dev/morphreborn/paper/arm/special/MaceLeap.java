package dev.morphreborn.paper.arm.special;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.arm.ArmProtocol;
import dev.morphreborn.paper.arm.api.ArmContext;
import dev.morphreborn.paper.arm.api.ArmFxMsg;
import dev.morphreborn.paper.arm.api.ArmSpecialModule;
import dev.morphreborn.paper.arm.api.SpecialCharge;
import dev.morphreborn.paper.arm.api.SpecialResult;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.LeapTrack;
import dev.morphreborn.paper.arm.rules.MaceMath;
import dev.morphreborn.paper.arm.rules.MaceSettings;
import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import dev.morphreborn.paper.effects.SkinEffects;
import dev.morphreborn.paper.effects.VanillaEffects;
import dev.morphreborn.paper.tips.Lines;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * Skyward Leap, the mace arm's special (F; my design, 2026-11-16, 8.0.0-beta.2). The arm is a real mace, so its smash is
 * vanilla's: a hit while falling more than 1.5 blocks adds damage by fall distance (MaceItem.getAttackDamageBonus: 4 a block to 3, 2 a
 * block to 8, 1 after), knocks back what stands near and takes the fall's damage from the wielder. F gives the mace the one thing it
 * lacks, the height to fall from:
 * <ol>
 * <li><b>The leap.</b> Tendrils launch the player straight up, 6 / 8 / 10 blocks by tier (rules/MaceMath finds the launch speed that
 * rises exactly that under vanilla's gravity and drag). It needs the ground under the feet and room above the head: under a
 * ceiling the leap is planned just short of it, and with less than 2 blocks of room there is no leap, no cost and no cooldown.</li>
 * <li><b>No fall damage, for this landing only.</b> The fall damage of the landing that ends the leap is cancelled, as far as the leap's
 * own fall goes: the planned height plus {@link MaceMath#FALL_MARGIN} (rules/MaceMath.fallDamageAfter). A leaper who steps off an edge
 * mid-air and lands from far higher gets only the planned height's worth forgiven (2026-11-16 review). The protection is then cleared
 * (rules/LeapTrack: a couple of ticks after the ground contact, or at the latest {@code protect-seconds} after the launch for a leap
 * that never lands). F needs the body on the ground (onGround, fall distance under {@link MaceMath#MAX_START_FALL}) and untangled
 * (no cobweb or sweet berry bush: those all but stop the rise); both refusals are free. (A launch another plugin cancels at PlayerVelocityEvent, which Paper fires at the end of the
 * tick, is not refused: the leap is paid, and the protection simply ends after its grace.) The module never touches the player's fall distance, so the whole fall stays on the books: the
 * next mace hit smashes by it (vanilla reads it in MaceItem, and the arm item keeps the mace's {@code WEAPON} component, so its
 * {@code postHurtEnemy} resets it after the hit as in vanilla; ArmItems tested). A smash in the air also gives vanilla's own
 * {@code setIgnoreFallDamageFromCurrentImpulse}, so the landing after it is free either way.</li>
 * <li><b>The landing effect.</b> One FX_MACE {@link #FX_LANDING}: on the smash itself, or, when the leap ends with no hit, on the
 * ground contact. Never both.</li>
 * </ol>
 * The smash's hits are the mace's own, billed per hit like any melee hit (ArmBilling: no cause for a mace smash, so the arm in hand
 * says it, ArmTiers.isMaceBlow). F costs and cools down by tier through the usual charge.
 * <p>
 * The clients are sent S_ARM_FX kind FX_MACE two ways, by {@code arg}: {@link #FX_LEAP} when F is paid, and {@link #FX_LANDING}; their
 * fields are documented on each constant. Players whose game doesn't draw the new forms get particles and sounds instead.
 */
public final class MaceLeap implements ArmSpecialModule {
	public static final String FORM = "mace";
	/** Its row in survival.yml arm-cost.specials. */
	private static final String COST_KEY = "mace";

	/**
	 * FX_MACE {@code arg}: the leap, sent when F is paid. level = the tier; ticks = how many ticks the rise takes (to the apex); seed = the
	 * clients' randomness; ids = []; positions = [the block the leaper's feet start in, the block at the apex straight above it]
	 * (BlockPos.asLong). The planned height (under a ceiling it is shorter than the tier's) is the distance between the two.
	 */
	public static final int FX_LEAP = 1;
	/**
	 * FX_MACE {@code arg}: the landing, sent once per leap. On the smash hit while the mace is in hand (any smash with the mace arm, a
	 * leap's or a plain fall's) it is sent with ids = [the creature hit]; when a leap ends on the ground with no smash, ids = [].
	 * level = the tier; ticks = the fall distance in tenths of a block, 0..1000 (MaceMath.fallTenths: over 50 is vanilla's heavy smash; for
	 * a ground contact it is the real fall, read at the fall damage event, or the planned height when the fall dealt none),
	 * the shockwave's size to scale by; seed = the clients' randomness; positions = [the block under the impact: the creature's feet for
	 * a hit, the leaper's feet for the ground]. The owner is the origin; a leap that ended with the arm gone sends none.
	 */
	public static final int FX_LANDING = 2;

	private static final NamespacedKey ARM_FORM = MorphRebornPlugin.key("arm");
	/** The body's box is shrunk this much on every side, so touching a wall isn't being inside it; the support slice under the feet is this thick. */
	private static final double SKIN = 0.01, SLICE = 0.03;
	/** The ceiling is looked for in steps of this many blocks. */
	private static final double STEP = 0.25;
	/** The fallback's ring: how many motes, and how wide. */
	private static final int RING_MOTES = 16;
	private static final double RING_RADIUS = 1.6;
	/** What a smash over this many blocks of fall is, to vanilla: the heavy one (MaceItem.SMASH_ATTACK_HEAVY_THRESHOLD). */
	private static final double HEAVY_FALL = 5;

	private @Nullable ArmContext ctx;
	private MaceSettings settings = MaceSettings.DEFAULTS;
	/** Viewers whose game draws the new forms' effects (FX_MACE reaches only them); every particle fallback leaves them out. */
	private Predicate<Player> natives = v -> false;
	/** The leaps whose landing is still to come, by leaper. */
	private final Map<UUID, Leap> leaps = new HashMap<>();
	private final Listener events = new Events();

	/** One leap: who paid for it, at which tier, how high it was planned to rise and where it is in its flight. */
	private static final class Leap {
		final long token;
		final int level, seed;
		final double planned;
		final LeapTrack track;

		Leap(long token, int level, int seed, double planned, LeapTrack track) {
			this.token = token;
			this.level = level;
			this.seed = seed;
			this.planned = planned;
			this.track = track;
		}
	}

	public MaceLeap() {}

	@Override
	public String form() {
		return FORM;
	}

	@Override
	public void init(ArmContext ctx) {
		this.ctx = ctx;
		this.natives = ctx::drawsNewForms;
		this.settings = MaceSettings.parse(ctx.config().form(FORM));
	}

	@Override
	public void load(Map<String, Object> formSection) {
		settings = MaceSettings.parse(formSection);
	}

	@Override
	public @Nullable Listener listener() {
		return events;
	}

	@Override
	public boolean breaksBlocks() {
		return false;
	}

	// ------------------------------------------------------------------ F

	@Override
	public SpecialResult fire(Player p, int level, boolean sneaking, SpecialCharge charge) {
		ArmContext c = ctx;
		if (c == null) return SpecialResult.refused(settings.name() + " isn't ready yet.");
		int lvl = level(level);
		if (p.isInsideVehicle() || p.isGliding() || p.isFlying() || p.isSwimming() || p.isInWater() || p.isInLava() || p.isClimbing()) {
			return SpecialResult.refused(settings.name() + " needs your feet on the ground.");
		}
		World world = p.getWorld();
		BoundingBox body = p.getBoundingBox();
		// onGround and no fall in progress are enforced, not assumed: a fall carried into a leap would be forgiven and then added to the smash
		if (!p.isOnGround() || p.getFallDistance() >= MaceMath.MAX_START_FALL) return SpecialResult.refused(settings.name() + " needs your feet on the ground.");
		if (!standing(world, body)) return SpecialResult.refused(settings.name() + " needs your feet on solid ground.");
		// a cobweb or a berry bush has no collision shape, so the checks below can't see it, and it scales the rise to almost nothing
		if (tangled(world, body)) return SpecialResult.refused(settings.name() + " can't lift you out of that.");
		double planned = MaceMath.plan(settings.heightAt(lvl), freeRise(world, body, settings.heightAt(lvl)));
		// a ceiling too close, or a height set to nothing: no leap, so nothing is paid and F is locked out a moment (the manager's no-target rule)
		if (planned <= 0) return SpecialResult.noTarget("No room above you to leap");

		int now = now(c);
		int seed = ThreadLocalRandom.current().nextInt(1 << 30);
		double speed = MaceMath.launchSpeed(planned);
		Location feet = p.getLocation();
		// paid first, so a refused F never touches the player's motion (setVelocity marks the body for a motion packet, which would reset a
		// refused presser's momentum). Another plugin cancelling the launch is not caught: Paper fires PlayerVelocityEvent at the end of the
		// tick, after this returns (second round, 2026-11-16).
		if (!charge.pay(costMilli(lvl), cooldownTicks(lvl))) return new SpecialResult(SpecialResult.Outcome.REFUSED, null); // pay showed why
		// Straight up: the sideways motion is cleared, the body's fall distance is left alone (it is under half a block here, and it is what the smash reads)
		p.setVelocity(new Vector(0, speed, 0));
		leaps.put(p.getUniqueId(), new Leap(c.token(p), lvl, seed, planned, new LeapTrack(now, settings.protectTicks())));
		world.playSound(feet, Sound.ENTITY_BREEZE_JUMP, SoundCategory.PLAYERS, 1F, 0.8F);
		c.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_MACE, lvl, MaceMath.riseTicks(speed), seed, null,
				new long[] {block(feet.getX(), feet.getY(), feet.getZ()), block(feet.getX(), feet.getY() + planned, feet.getZ())}, FX_LEAP),
				ArmProtocol.fxRadius(ArmProtocol.FX_MACE));
		fallbackLaunch(c, p, feet);
		if (c.config().report()) {
			c.report(settings.name() + ": " + p.getName() + " at level " + lvl + " leaps " + number(planned) + " blocks (asked " + number(settings.heightAt(lvl)) + "), speed "
					+ number(speed) + ", " + MaceMath.riseTicks(speed) + " ticks up");
		}
		return SpecialResult.FIRED;
	}

	/** The ground is under the feet: a thin slice under the body's box meets a block. */
	private static boolean standing(World world, BoundingBox body) {
		return world.hasCollisionsIn(new BoundingBox(body.getMinX() + SKIN, body.getMinY() - SLICE, body.getMinZ() + SKIN, body.getMaxX() - SKIN, body.getMinY(),
				body.getMaxZ() - SKIN));
	}

	/** The body is in a cobweb or a sweet berry bush: blocks with no collision shape that all but stop a rise (Entity.stuckSpeedMultiplier). */
	private static boolean tangled(World world, BoundingBox body) {
		int x0 = (int) Math.floor(body.getMinX() + SKIN), x1 = (int) Math.floor(body.getMaxX() - SKIN);
		int y0 = (int) Math.floor(body.getMinY() + SKIN), y1 = (int) Math.floor(body.getMaxY() - SKIN);
		int z0 = (int) Math.floor(body.getMinZ() + SKIN), z1 = (int) Math.floor(body.getMaxZ() - SKIN);
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					Material m = world.getBlockAt(x, y, z).getType();
					if (m == Material.COBWEB || m == Material.SWEET_BERRY_BUSH) return true;
				}
			}
		}
		return false;
	}

	/**
	 * How many blocks the body can rise before a block stops it, looking {@code wanted} up and a little more (anything at least {@code wanted}
	 * means the way is clear), and never past the world's top.
	 */
	private static double freeRise(World world, BoundingBox body, double wanted) {
		double limit = Math.min(wanted + 1, world.getMaxHeight() - body.getMaxY());
		for (double dy = STEP; dy <= limit; dy += STEP) {
			if (world.hasCollisionsIn(new BoundingBox(body.getMinX() + SKIN, body.getMinY() + dy + SKIN, body.getMinZ() + SKIN, body.getMaxX() - SKIN,
					body.getMaxY() + dy - SKIN, body.getMaxZ() - SKIN))) {
				return dy - STEP;
			}
		}
		return limit >= wanted ? wanted + 1 : limit;
	}

	// ------------------------------------------------------------------ the flight

	@Override
	public void tick() {
		ArmContext c = ctx;
		if (c == null || leaps.isEmpty()) return;
		int now = now(c);
		for (Iterator<Map.Entry<UUID, Leap>> it = leaps.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Leap> e = it.next();
			Leap leap = e.getValue();
			Player p = c.plugin().getServer().getPlayer(e.getKey());
			if (p == null || !p.isOnline() || p.isDead()) {
				it.remove();
				continue;
			}
			boolean ended = p.isInWater() || p.isInLava() || p.isClimbing() || p.isInsideVehicle() || p.isGliding() || p.isFlying();
			LeapTrack.Step step = leap.track.update(now, p.isOnGround(), ended, p.getFallDistance());
			if (step == LeapTrack.Step.LANDED && !leap.track.smashed()) {
				// the real fall was noted at the fall damage event (the ground contact has reset the player's own by now)
				double fall = Math.max(leap.track.landingFall(leap.planned), p.getFallDistance());
				leap.track.smash(fall); // one landing effect per leap on the ground: a smash that came first has had its own
				landing(c, p, leap.token, leap.level, leap.seed, fall, null);
			}
			if (step == LeapTrack.Step.OVER) it.remove();
		}
	}

	/**
	 * The landing effect, sent once: for the smash that used the fall ({@code victim} the creature hit), or for the ground contact of a leap
	 * that smashed nothing ({@code victim} null). Nothing when the arm is no longer in hand: its tendrils are gone with it.
	 */
	private void landing(ArmContext c, Player p, long token, int level, int seed, double fall, @Nullable LivingEntity victim) {
		Location at = victim != null ? victim.getLocation() : p.getLocation();
		if (!c.stillArmed(p, token)) return;
		boolean heavy = fall > HEAVY_FALL;
		if (victim == null) {
			p.getWorld().playSound(at, heavy ? Sound.ITEM_MACE_SMASH_GROUND_HEAVY : Sound.ITEM_MACE_SMASH_GROUND, SoundCategory.PLAYERS, 0.8F, 1F);
		}
		c.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_MACE, level, MaceMath.fallTenths(fall), seed, victim == null ? null : new int[] {victim.getEntityId()},
				new long[] {block(at.getX(), at.getY() - 0.1, at.getZ())}, FX_LANDING), ArmProtocol.fxRadius(ArmProtocol.FX_MACE));
		fallbackRing(at, heavy);
		if (c.config().report()) {
			c.report(settings.name() + ": " + p.getName() + (victim == null ? " landed" : " smashed " + victim.getName()) + " after a fall of " + number(fall) + " blocks (vanilla adds "
					+ number(MaceMath.smashBonus(fall)) + " damage)");
		}
	}

	@Override
	public boolean running(Player p) {
		return leaps.containsKey(p.getUniqueId());
	}

	/**
	 * A retract, a reshape, leaving: the leap's protection stays. The player paid for the leap and is still in the air, and a fall
	 * damage after letting go of the arm would punish a retract; the module's own tick (and the quit and death below) ends it.
	 */
	@Override
	public void abort(Player p) {}

	/**
	 * A reload or a mode switch keeps the in-air protection like {@link #abort} does (a leap 8 blocks up would otherwise land on real fall
	 * damage it paid to avoid; the tick and the events end each one themselves). Only the plugin going away drops it.
	 */
	@Override
	public void abortAll() {
		ArmContext c = ctx;
		if (c == null || !c.plugin().isEnabled()) leaps.clear();
	}

	@Override
	public String specialName(int level) {
		return settings.name();
	}

	@Override
	public long costMilli(int level) {
		ArmContext c = ctx;
		return (c == null ? ArmConfig.DEFAULTS : c.config()).costs().special(COST_KEY, level(level));
	}

	@Override
	public int cooldownTicks(int level) {
		return settings.cooldownTicks(level(level));
	}

	/** "Leaps 8 blocks · the fall adds 22 damage to the smash" for the upgrade tree, after the manager's "Diamond strength · ". */
	@Override
	public Lines describeLines(int level) {
		return settings.lines(level(level));
	}

	// ------------------------------------------------------------------ what the others see

	/** The leap's launch for players whose game doesn't draw the new forms: the forming puff and a ring of grey motes at the feet. */
	private void fallbackLaunch(ArmContext c, Player p, Location feet) {
		VanillaEffects vanilla = c.plugin().effects();
		if (vanilla != null) vanilla.armBurst(SkinEffects.armTip(p), natives);
		fallbackRing(feet, false);
	}

	/** A ring of grey motes on the ground where it landed or struck, for players whose game doesn't draw the new forms; wider and doubled for a heavy smash. */
	private void fallbackRing(Location at, boolean heavy) {
		World world = at.getWorld();
		if (world == null) return;
		Particle.DustOptions dust = new Particle.DustOptions(VanillaEffects.ARM_GREY, 1.3F);
		double radius = heavy ? RING_RADIUS * 1.6 : RING_RADIUS;
		for (Player v : world.getPlayers()) {
			if (natives.test(v) || v.getLocation().distanceSquared(at) > 48 * 48) continue;
			for (int i = 0; i < RING_MOTES; i++) {
				double a = i * 2 * Math.PI / RING_MOTES;
				v.spawnParticle(Particle.DUST, at.getX() + Math.cos(a) * radius, at.getY() + 0.1, at.getZ() + Math.sin(a) * radius, 1, 0, 0, 0, 0, dust);
			}
		}
	}

	// ------------------------------------------------------------------ the module's own events

	private final class Events implements Listener {
		/**
		 * The fall damage of the leap's landing is cancelled, as far as the leap's own fall goes (and only a fall's: every other damage is the
		 * game's): a fall within the planned height plus a margin is forgiven whole, a longer one (the leaper stepped off an edge in the air)
		 * only by the planned height. The fall distance is still intact here (the game resets it after the event), so it is read: it is what the
		 * landing effect carries, and what bounds the forgiveness. It is not touched, so it still powers the smash.
		 */
		@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
		public void onFall(EntityDamageEvent event) {
			if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player p)) return;
			Leap leap = leaps.get(p.getUniqueId());
			if (leap == null) return;
			double fall = p.getFallDistance();
			leap.track.sawFall(fall);
			double left = MaceMath.fallDamageAfter(event.getDamage(), fall, leap.planned);
			if (left <= 0) event.setCancelled(true);
			else event.setDamage(left);
		}

		/**
		 * The mace's smash (its own damage type, whatever cause Paper gives it) with the mace arm in hand: the landing effect, once. A smash
		 * with no leap before it (a fall from a ledge) is drawn too, at the arm's level.
		 */
		@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
		public void onSmash(EntityDamageByEntityEvent event) {
			ArmContext c = ctx;
			if (c == null || !(event.getDamager() instanceof Player p) || !(event.getEntity() instanceof LivingEntity victim)) return;
			if (!"minecraft:mace_smash".equals(event.getDamageSource().getDamageType().getKey().toString())) return;
			long token = c.token(p);
			if (token == 0 || !FORM.equals(formOf(p.getInventory().getItemInMainHand())) || !c.stillArmed(p, token)) return;
			// the fall distance is still the smash's here (vanilla resets it after the event); every smash is drawn, and a leap's ground contact
			// after one sends nothing more
			double fall = p.getFallDistance();
			Leap leap = leaps.get(p.getUniqueId());
			if (leap != null) leap.track.smash(fall);
			landing(c, p, token, leap != null ? leap.level : level(c.level(p, FORM)), leap != null ? leap.seed : ThreadLocalRandom.current().nextInt(1 << 30), fall, victim);
		}

		/** A player who dies is out of their leap (the respawned body has no fall to forgive). */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onDeath(PlayerDeathEvent event) {
			leaps.remove(event.getEntity().getUniqueId());
		}

		@EventHandler(priority = EventPriority.MONITOR)
		public void onQuit(PlayerQuitEvent event) {
			leaps.remove(event.getPlayer().getUniqueId());
		}

		/** A teleport ends the flight (the game resets the fall distance), and with it the protection. */
		@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
		public void onTeleport(PlayerTeleportEvent event) {
			leaps.remove(event.getPlayer().getUniqueId());
		}
	}

	// ------------------------------------------------------------------ helpers

	/** An arm item's form id, null when it isn't a mace or carries no arm tag. */
	private static @Nullable String formOf(@Nullable ItemStack stack) {
		if (stack == null || stack.getType() != Material.MACE) return null;
		try {
			return stack.getPersistentDataContainer().get(ARM_FORM, PersistentDataType.STRING);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** A level in 1..3 (0 reads as iron: a formed arm always has one). */
	private static int level(int level) {
		return Math.clamp(level, 1, 3);
	}

	/** The server's own tick count, as the other specials time against. */
	private static int now(ArmContext c) {
		return c.plugin().getServer().getCurrentTick();
	}

	/** A point's block as S_ARM_FX packs positions (BlockPos.asLong, the protocol's; never Paper's Block.getBlockKey, which packs differently). */
	private static long block(double x, double y, double z) {
		return new Pos((int) Math.floor(x), (int) Math.floor(y + 1e-4), (int) Math.floor(z)).asLong();
	}

	private static String number(double v) {
		return java.math.BigDecimal.valueOf(Math.round(v * 100) / 100.0).stripTrailingZeros().toPlainString();
	}
}
