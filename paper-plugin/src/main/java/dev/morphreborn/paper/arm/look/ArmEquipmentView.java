package dev.morphreborn.paper.arm.look;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ChargedProjectiles;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemCustomModelData;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemModel;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.PluginSettings;
import dev.morphreborn.paper.arm.ArmProtocol;
import dev.morphreborn.paper.arm.api.ArmContext;
import dev.morphreborn.paper.arm.api.ArmLookModule;
import dev.morphreborn.paper.arm.api.ArmView;
import dev.morphreborn.paper.arm.rules.ArmDurability;
import dev.morphreborn.paper.arm.rules.ArmItemSpecs;
import dev.morphreborn.paper.arm.rules.ArmTiers;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * How players without the client mod see an arm form (spec 3.11): the living arm model when their game has RE:Morph's
 * resource pack ({@code morphreborn:arm/<form>}, animated by the pack itself), the plain tool of the arm's tier when it
 * doesn't. The arm's stack in the owner's hand is one stack; this module makes every other player's copy of it right for
 * that player, by rewriting the equipment packets the server sends about the owner (a PacketEvents listener):
 * <ul>
 *   <li>the item model is set to the pack's for a viewer who sees the pack, and to the vanilla tool's for one who doesn't
 *       (the owner's own stack carries the pack's model when the owner sees the pack, {@link #usePackModel});</li>
 *   <li>while the arm forms, quick-forms, reshapes, retracts or melts, a viewer with the pack also gets a forming float in
 *       the stack's custom_model_data (1 + progress), which the pack's item definition turns into one of its growth
 *       models; the server sends the owner's equipment again every few ticks for that ({@link #onArmChanged});</li>
 *   <li>a Bedrock player (Floodgate) gets the plain tool, and a bow as a bow (the crossbow is a real crossbow already).</li>
 * </ul>
 * The Tendril Shield accessory (2026-11-16) is shown the same way to everyone whose game doesn't draw it itself: a vanilla
 * shield in the owner's OFF_HAND equipment entry (added when vanilla sends none for an empty hand), and, while it is raised, the
 * owner's living-entity flags (metadata index 8) with the off hand in use, which every client draws as the blocking pose. The owner's
 * real off hand is never touched. The states come from {@link ShieldLook}, which this class feeds the packets' rewrite from and sends
 * the changes through ({@link #sendShield}); it works with or without an arm formed. The shield's owner without the mod is shown it in their
 * own off hand too (decided 2026-11-17) so that the game raises it on a right-click the main hand did not use: that is {@link ShieldHandView}, which
 * this class starts and stops.
 * The listener runs on netty threads. It reads the arm manager's concurrent map of armed players by entity id (not the
 * stacks' components, which a server that obfuscates items strips from other viewers' packets), the pack-loaded set and
 * the mod handshake, all concurrent, and touches no world or entity state.
 * <p>
 * Needs PacketEvents: without it this class doesn't load (the constructor says so), and everyone without the mod sees
 * the plain tool. A pack that can't be found by a viewer isn't offered to them: a viewer is only sent the pack's model
 * once the server knows their game loaded RE:Morph's own pack (see BorrowedHeads.onPack), so nobody is shown a missing
 * model.
 */
public final class ArmEquipmentView implements ArmLookModule {
	/** Players this far from an armed player (blocks) are sent its hand again when it changes. */
	private static final double RESEND_RANGE = 64;

	private @Nullable ArmContext ctx;
	private @Nullable PacketListenerCommon listener;
	private volatile boolean failed;
	/** The shield owner's own off hand and use packets ({@link ShieldGrip}); started and stopped with this view. */
	private @Nullable ShieldHandView handView;

	/** Fails with NoClassDefFoundError, quietly, where PacketEvents isn't installed (the arm manager leaves the look out). */
	public ArmEquipmentView() {
		Class<?> probe = PacketEvents.class;
		if (probe == null) throw new IllegalStateException();
	}

	@Override
	public void init(ArmContext ctx) {
		this.ctx = ctx;
		if (PacketEvents.getAPI() == null) throw new IllegalStateException("PacketEvents isn't ready");
		listener = PacketEvents.getAPI().getEventManager().registerListener(new Rewriter(this));
		ShieldLook.use(this::sendShield);
		// the shield's own owner without the mod is shown it in their own off hand too, and their use packets are read (decided 2026-11-17)
		ShieldHandView hand = new ShieldHandView(ctx.plugin().getLogger());
		hand.start();
		handView = hand;
	}

	@Override
	public void shutdown() {
		ShieldLook.use(null);
		ShieldHandView hand = handView;
		handView = null;
		if (hand != null) hand.stop();
		PacketListenerCommon l = listener;
		listener = null;
		if (l != null && PacketEvents.getAPI() != null) PacketEvents.getAPI().getEventManager().unregisterListener(l);
	}

	@Override
	public boolean usePackModel(Player owner) {
		return seesArmPack(owner);
	}

	@Override
	public @Nullable String cannotForm(Player p, String form) {
		// a Bedrock client can't be shown a draw of the bow (a form on a paper base), which needs the pack's model of it; the crossbow is a real crossbow now
		if (ArmTiers.isToolForm(form) && !ArmTiers.bedrockItem(form).isEmpty() && Bedrock.is(p)) {
			return "Bedrock players can't draw the " + ArmTiers.displayName(form) + "; pick another form.";
		}
		return null;
	}

	/**
	 * The arm of {@code owner} changed ({@code view} null: it is gone), or a forming step came: viewers with the pack get
	 * the owner's hand sent again, which the listener rewrites with the stage the arm is in now.
	 */
	@Override
	public void onArmChanged(Player owner, @Nullable ArmView view, boolean formingStep) {
		ArmContext c = ctx;
		if (c == null || failed) return;
		org.bukkit.inventory.ItemStack hand = owner.getInventory().getItemInMainHand();
		for (Player viewer : owner.getWorld().getPlayers()) {
			if (viewer == owner || viewer.getLocation().distanceSquared(owner.getLocation()) > RESEND_RANGE * RESEND_RANGE) continue;
			if (!seesArmPack(viewer)) continue;
			viewer.sendEquipmentChange(owner, org.bukkit.inventory.EquipmentSlot.HAND, hand);
		}
	}

	/**
	 * The owner's main hand goes to every player tracking them, and the listener rewrites it by the views as they are now (the
	 * caller has updated or removed the view first): a still-tucked arm is shown as the arm, an ended one as what the hand
	 * really holds. Not just viewers with the pack, as {@link #onArmChanged} is: modded and plain viewers must lose a stub too.
	 */
	@Override
	public void onHandChanged(Player owner) {
		if (ctx == null || failed) return;
		org.bukkit.inventory.ItemStack hand = owner.getInventory().getItemInMainHand();
		for (Player viewer : owner.getTrackedBy()) {
			if (viewer != owner) viewer.sendEquipmentChange(owner, org.bukkit.inventory.EquipmentSlot.HAND, hand);
		}
	}

	/** {@inheritDoc} A viewer that started tracking a tucked owner is sent the hand, which the listener rewrites to the arm. */
	@Override
	public void onTracked(Player viewer, Player owner) {
		if (ctx == null || failed || viewer == owner) return;
		viewer.sendEquipmentChange(owner, org.bukkit.inventory.EquipmentSlot.HAND, owner.getInventory().getItemInMainHand());
	}

	/** The living-entity flags' metadata index (LivingEntity.DATA_LIVING_ENTITY_FLAGS): 1 using an item, 2 in the off hand, 4 spin attack. */
	private static final int LIVING_FLAGS = 8;

	/**
	 * The Tendril Shield's state changed ({@link ShieldLook}, already updated): the viewers tracking the owner ({@code only}: one viewer who
	 * began to track them) whose game doesn't draw the shield itself are sent the owner's off hand again, which the rewriter turns into the
	 * shield (or, when it went away, leaves as it really is), and, when it was or is raised, the owner's flags, which it sets or clears the
	 * off-hand use bits of. Main thread.
	 */
	private void sendShield(Player owner, int state, int before, @Nullable Player only) {
		ArmContext c = ctx;
		if (c == null || failed) return;
		try {
			Iterable<? extends Player> viewers = only != null ? List.of(only) : owner.getTrackedBy();
			for (Player viewer : viewers) {
				if (viewer == owner || !viewer.isOnline() || c.drawsNewForms(viewer)) continue;
				// a new viewer gets both; otherwise the hand only when the shield came or went, the flags only when it rose or lowered
				if (only != null || (state == ShieldLook.NONE) != (before == ShieldLook.NONE)) {
					viewer.sendEquipmentChange(owner, org.bukkit.inventory.EquipmentSlot.OFF_HAND, owner.getInventory().getItemInOffHand());
				}
				if (only != null ? state == ShieldLook.RAISED : (state == ShieldLook.RAISED) != (before == ShieldLook.RAISED)) {
					byte flags = livingFlags(owner);
					if (state == ShieldLook.RAISED && (flags & 1) == 0) flags |= 3;
					List<EntityData<?>> data = List.of(new EntityData<>(LIVING_FLAGS, EntityDataTypes.BYTE, flags));
					PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, new WrapperPlayServerEntityMetadata(owner.getEntityId(), data));
				}
			}
		} catch (Throwable t) {
			failedOnce(t);
		}
	}

	/** The owner's living-entity flags as vanilla sends them: using an item (1), in the off hand (2), spin attack (4). */
	private static byte livingFlags(Player owner) {
		int f = 0;
		if (owner.isHandRaised()) f |= owner.getHandRaised() == org.bukkit.inventory.EquipmentSlot.OFF_HAND ? 3 : 1;
		if (owner.isRiptiding()) f |= 4;
		return (byte) f;
	}

	/**
	 * Whether {@code viewer} is shown the pack's arm models: arm models are on ({@code appearance.vanilla-arm-models}),
	 * the pack is on for non-modded viewers ({@code multimorph.vanilla-heads} isn't off), the viewer doesn't draw arms
	 * itself, isn't a Bedrock player, and has RE:Morph's pack (mode {@code pack}: everyone; {@code auto}: whoever loaded it).
	 */
	boolean seesArmPack(Player viewer) {
		ArmContext c = ctx;
		if (c == null) return false;
		MorphRebornPlugin plugin = c.plugin();
		PluginSettings settings = plugin.settings();
		return Rules.seesPack(settings.vanillaArmModels, settings.multimorphVanillaHeads, plugin.channel().drawsArms(viewer), Bedrock.is(viewer),
				plugin.borrowedHeads().packLoaded(viewer.getUniqueId()));
	}

	private void failedOnce(Throwable t) {
		if (failed) return;
		failed = true;
		ArmContext c = ctx;
		if (c != null) c.plugin().getLogger().log(Level.WARNING, "The arm pack look failed; players without the mod now see plain tools.", t);
	}

	// ------------------------------------------------------------------ the pure rules

	/** What the rewriter decides, without PacketEvents, so it can be tested. */
	public static final class Rules {
		/** The pack's item model of a form. */
		public static String packModel(String form) {
			return "morphreborn:arm/" + form;
		}

		/** The vanilla item model a viewer without the pack sees. */
		public static String plainModel(String form, int tier) {
			return ArmTiers.vanillaModel(form, tier);
		}

		/**
		 * The forming float a viewer with the pack gets in custom_model_data[0], or 0 for none (a finished arm). The pack's
		 * item definition reads it as 1 + progress (so that no float at all, which reads as 0, is the finished arm):
		 * forming and quick-forming grow with their own progress, a reshape's new form grows over the ticks 7 to 16
		 * (before that the old form is collapsing, and the new tool is just starting), a retract or melt shrinks back
		 * along the same models.
		 */
		public static float formingValue(int state, int elapsed, int duration) {
			if (duration <= 0) return 0F;
			float p = Math.clamp(elapsed / (float) duration, 0F, 1F);
			return switch (state) {
				case ArmProtocol.STATE_FORMING, ArmProtocol.STATE_QUICKFORM -> 1F + p;
				case ArmProtocol.STATE_RESHAPING -> 1F + Math.clamp((p - 7F / 16F) / (9F / 16F), 0F, 1F); // the old form collapses for 7/16 of it
				case ArmProtocol.STATE_RETRACTING, ArmProtocol.STATE_MELTING -> 1F + (1F - p);
				default -> 0F;
			};
		}

		/**
		 * Whether a tucked owner's equipment packet needs a MAIN_HAND entry added for the arm's stub: the vanilla pairing leaves
		 * out empty slots, so a viewer starting to track a tucked owner with an empty held slot gets none (and would see no arm).
		 * A packet that has one is rewritten in place instead; an arm that is not tucked has an item of its own in the packet.
		 */
		public static boolean needsMainHandEntry(boolean tucked, boolean hasMainHand) {
			return tucked && !hasMainHand;
		}

		/** The viewer rule, with its five inputs (see {@link ArmEquipmentView#seesArmPack}). */
		public static boolean seesPack(boolean armModelsOn, String vanillaHeadsMode, boolean drawsArms, boolean bedrock, boolean packLoaded) {
			if (!armModelsOn || vanillaHeadsMode.equals("off") || drawsArms || bedrock) return false;
			return vanillaHeadsMode.equals("pack") || vanillaHeadsMode.equals("auto") && packLoaded;
		}

		/** The custom_model_data strings an arm stack carries: {@code ["morphreborn:arm", form, tier name]}. */
		public static List<String> modelStrings(String form, int tier) {
			return List.of(ArmItemSpecs.MODEL_TAG, form, ArmTiers.tierName(tier));
		}

		private Rules() {}
	}

	// ------------------------------------------------------------------ the rewriter (netty threads)

	private static final class Rewriter extends PacketListenerAbstract {
		private final ArmEquipmentView view;

		Rewriter(ArmEquipmentView view) {
			super(PacketListenerPriority.NORMAL);
			this.view = view;
		}

		@Override
		public void onPacketSend(PacketSendEvent event) {
			if (view.failed) return;
			if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA) {
				shieldFlags(event);
				return;
			}
			if (event.getPacketType() != PacketType.Play.Server.ENTITY_EQUIPMENT) return;
			ArmContext ctx = view.ctx;
			if (ctx == null) return;
			try {
				WrapperPlayServerEntityEquipment packet = new WrapperPlayServerEntityEquipment(event);
				boolean shielded = shieldOffHand(packet, event.getPlayer());
				ArmView arm = ctx.views().get(packet.getEntityId());
				if (arm == null || !ArmTiers.isToolForm(arm.form())) {
					if (shielded) event.markForReEncode(true);
					return;
				}
				Object who = event.getPlayer();
				if (!(who instanceof Player viewer)) {
					if (shielded) event.markForReEncode(true);
					return;
				}
				boolean sees = view.seesArmPack(viewer);
				boolean bedrock = Bedrock.is(viewer);
				boolean changed = shielded;
				List<Equipment> entries = packet.getEquipment();
					if (Rules.needsMainHandEntry(arm.tucked(), entries.stream()
							.anyMatch(e -> e.getSlot() == com.github.retrooper.packetevents.protocol.player.EquipmentSlot.MAIN_HAND))) {
						// A viewer starting to track a tucked owner whose held slot is empty: the vanilla pairing leaves empty slots
						// out, so there is no MAIN_HAND entry to rewrite, and the arm would not show at all. One is added here.
						ItemStack stub = tuckedArm(arm);
						if (stub != null) {
							entries = new ArrayList<>(entries);
							entries.add(new Equipment(com.github.retrooper.packetevents.protocol.player.EquipmentSlot.MAIN_HAND, stub));
							packet.setEquipment(entries);
							changed = true;
						}
					}
					for (Equipment entry : entries) {
					ItemStack item = entry.getItem();
					if (arm.tucked() && entry.getSlot() == com.github.retrooper.packetevents.protocol.player.EquipmentSlot.MAIN_HAND) {
						// tucked while its owner has an inventory open: the hand holds the real item, but the arm is still
						// formed, so everyone else is shown the arm (2026-11-16), modded viewers by its marks
						item = tuckedArm(arm);
						if (item == null) continue;
						entry.setItem(item);
						changed = true;
					}
					if (item == null || item.isEmpty() || !isArm(entry, item, arm)) continue;
					ItemStack out = rewrite(item, arm, sees, bedrock, ctx.tick());
					if (out != null) {
						entry.setItem(out);
						changed = true;
					}
				}
				if (changed) event.markForReEncode(true);
			} catch (Throwable t) {
				view.failedOnce(t);
			}
		}

		/**
		 * Whether this viewer is shown the Tendril Shield on {@code entityId} by the packets: the owner has one ({@link ShieldLook}), the viewer
		 * isn't the owner, and the viewer's game doesn't draw the shield itself (the mod at the new forms' version does, from S_ARM).
		 */
		private boolean showsShield(int entityId, @Nullable Object who) {
			if (ShieldLook.state(entityId) == ShieldLook.NONE || !(who instanceof Player viewer) || viewer.getEntityId() == entityId) return false;
			ArmContext ctx = view.ctx;
			return ctx != null && !ctx.drawsNewForms(viewer);
		}

		/**
		 * A shield in the owner's OFF_HAND entry (or a new entry, when vanilla sent none for an empty hand), unless the real item already is
		 * one. The real item is never changed: only this packet's copy. True when the packet was changed.
		 */
		private boolean shieldOffHand(WrapperPlayServerEntityEquipment packet, @Nullable Object who) {
			if (!showsShield(packet.getEntityId(), who)) return false;
			com.github.retrooper.packetevents.protocol.player.EquipmentSlot off = com.github.retrooper.packetevents.protocol.player.EquipmentSlot.OFF_HAND;
			List<Equipment> entries = packet.getEquipment();
			for (Equipment entry : entries) {
				if (entry.getSlot() != off) continue;
				ItemStack item = entry.getItem();
				if (item != null && item.getType() == ItemTypes.SHIELD) return false;
				entry.setItem(ItemStack.builder().type(ItemTypes.SHIELD).amount(1).build());
				return true;
			}
			entries = new ArrayList<>(entries);
			entries.add(new Equipment(off, ItemStack.builder().type(ItemTypes.SHIELD).amount(1).build()));
			packet.setEquipment(entries);
			return true;
		}

		/**
		 * While the owner's shield is raised, any metadata packet about them that carries the living-entity flags has the off hand in use
		 * (bits 1 and 2) unless they really are using an item, which every client draws as the shield held up.
		 */
		@SuppressWarnings("unchecked")
		private void shieldFlags(PacketSendEvent event) {
			if (!ShieldLook.any()) return;
			try {
				WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(event);
				if (ShieldLook.state(packet.getEntityId()) != ShieldLook.RAISED || !showsShield(packet.getEntityId(), event.getPlayer())) return;
				boolean changed = false;
				for (EntityData<?> data : packet.getEntityMetadata()) {
					if (data.getIndex() != LIVING_FLAGS || !(data.getValue() instanceof Byte flags) || (flags & 1) != 0) continue;
					((EntityData<Byte>) data).setValue((byte) (flags | 3));
					changed = true;
				}
				if (changed) event.markForReEncode(true);
			} catch (Throwable t) {
				view.failedOnce(t);
			}
		}

		/**
		 * The main hand's stack of an armed player, if it is of the arm's base item; the off hand's only when it carries the
		 * arm's tag (the arm is only ever in the main hand, so an off-hand sword is someone's real one).
		 */
		private static boolean isArm(Equipment entry, ItemStack item, ArmView arm) {
			String base = ArmTiers.material(arm.form()).toLowerCase(Locale.ROOT);
			if (!item.getType().getName().getKey().equals(base)) return false;
			if (entry.getSlot() == com.github.retrooper.packetevents.protocol.player.EquipmentSlot.MAIN_HAND) return true;
			if (entry.getSlot() != com.github.retrooper.packetevents.protocol.player.EquipmentSlot.OFF_HAND) return false;
			Optional<ItemCustomModelData> data = item.getComponent(ComponentTypes.CUSTOM_MODEL_DATA_LISTS);
			return data.isPresent() && data.get().getStrings().contains(ArmItemSpecs.MODEL_TAG);
		}

		/** The viewer's copy of an arm stack, or null when it is already right. */
		private static @Nullable ItemStack rewrite(ItemStack item, ArmView arm, boolean sees, boolean bedrock, int tick) {
			if (bedrock && ArmTiers.isToolForm(arm.form()) && !ArmTiers.bedrockItem(arm.form()).isEmpty()) return bedrockItemFor(item, arm.form());
			String wanted = sees ? Rules.packModel(arm.form()) : Rules.plainModel(arm.form(), arm.tier());
			float forming = sees ? Rules.formingValue(arm.state(), tick - arm.stateTick(), arm.duration()) : 0F;
			Optional<ItemModel> current = item.getComponent(ComponentTypes.ITEM_MODEL);
			boolean modelRight = current.isPresent() && current.get().getModelLocation().toString().equals(wanted);
			Optional<ItemCustomModelData> data = item.getComponent(ComponentTypes.CUSTOM_MODEL_DATA_LISTS);
			boolean hasFloat = data.isPresent() && !data.get().getFloats().isEmpty();
			if (modelRight && forming == 0F && !hasFloat) return null;
			ItemStack copy = item.copy();
			if (!modelRight) copy.setComponent(ComponentTypes.ITEM_MODEL, new ItemModel(new ResourceLocation(wanted)));
			if (forming > 0F) {
				List<Float> floats = new ArrayList<>(List.of(forming));
				ItemCustomModelData made = data.map(d -> new ItemCustomModelData(floats, d.getFlags(), d.getStrings(), d.getColors()))
						.orElseGet(() -> new ItemCustomModelData(floats, List.of(), Rules.modelStrings(arm.form(), arm.tier()), List.of()));
				copy.setComponent(ComponentTypes.CUSTOM_MODEL_DATA_LISTS, made);
			} else if (hasFloat) {
				ItemCustomModelData d = data.get();
				copy.setComponent(ComponentTypes.CUSTOM_MODEL_DATA_LISTS, new ItemCustomModelData(List.of(), d.getFlags(), d.getStrings(), d.getColors()));
			}
			return copy;
		}

		/**
		 * The stack shown in place of the real item while the arm is tucked: the form's base item with the arm's marks (the
		 * mod's clients recognise it by them; the rewrite below then gives it the pack's or the plain tool's model). Null
		 * when the base item is unknown, which leaves the packet as it was.
		 */
		private static @Nullable ItemStack tuckedArm(ArmView arm) {
			com.github.retrooper.packetevents.protocol.item.type.ItemType type =
					ItemTypes.getByName("minecraft:" + ArmTiers.material(arm.form()).toLowerCase(Locale.ROOT));
			if (type == null) return null;
			ItemStack out = ItemStack.builder().type(type).amount(1).build();
			out.setComponent(ComponentTypes.CUSTOM_MODEL_DATA_LISTS,
					new ItemCustomModelData(List.of(), List.of(), Rules.modelStrings(arm.form(), arm.tier()), List.of()));
			// the biomass bar the arm item had when it was tucked (a modded viewer draws it from the stack it is shown; without
			// it the stub reads as an undamaged item, a full arm whatever the biomass)
			int damage = ArmDurability.stubDamage(arm.maxDamage(), arm.damage());
			if (damage >= 0) {
				out.setComponent(ComponentTypes.MAX_DAMAGE, arm.maxDamage());
				out.setComponent(ComponentTypes.DAMAGE, damage);
			}
			// a whole bolt: the pack's crossbow reads the stack's charged projectile (charge_type) and shows the empty groove without one, so a
			// tucked loaded crossbow would flip to empty for pack viewers while the owner's inventory is open (2026-11-16)
			if (arm.charged()) {
				ItemStack bolt = ItemStack.builder().type(ItemTypes.ARROW).amount(1).build();
				out.setComponent(ComponentTypes.CHARGED_PROJECTILES, new ChargedProjectiles(new ArrayList<>(List.of(bolt))));
			}
			return out;
		}

		/**
		 * A Bedrock viewer's bow: the arm's paper, drawn by that weapon, becomes the real weapon (ArmTiers.bedrockItem),
		 * keeping the bar.
		 */
		private static @Nullable ItemStack bedrockItemFor(ItemStack item, String form) {
			com.github.retrooper.packetevents.protocol.item.type.ItemType type =
					ItemTypes.getByName("minecraft:" + ArmTiers.bedrockItem(form).toLowerCase(Locale.ROOT));
			if (type == null || item.getType() == type) return null;
			ItemStack weapon = ItemStack.builder().type(type).amount(1).build();
			item.getComponent(ComponentTypes.MAX_DAMAGE).ifPresent(v -> weapon.setComponent(ComponentTypes.MAX_DAMAGE, v));
			item.getComponent(ComponentTypes.DAMAGE).ifPresent(v -> weapon.setComponent(ComponentTypes.DAMAGE, v));
			return weapon;
		}
	}
}
