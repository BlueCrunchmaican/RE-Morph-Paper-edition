package dev.morphreborn.client.arm;

import dev.morphreborn.client.arm.gui.ArmInventorySignal;
import dev.morphreborn.client.arm.gui.ArmWheel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import org.jspecify.annotations.Nullable;

/**
 * Telling an arm from any other item. The server marks every arm stack with {@code custom_model_data} strings {@code
 * ["morphreborn:arm", form, tier]}, which ride the network (the arm's real marks, its PDC, mean nothing to the client),
 * so the client reads those.
 * <p>
 * A server with Paper's item obfuscation may strip them from other players' equipment, though. For a hand that is
 * drawn, {@link #recognize(ItemStack, ItemOwner, ItemDisplayContext)} falls back to what S_ARM says: if that player's
 * arm is out and this stack is the very one in their main hand, and of the form's base item (an iron sword for the
 * katana), it is the arm. The base item check keeps the old item from being drawn as the arm in the moment between
 * S_ARM and the equipment update (the server sends S_ARM first).
 * <p>
 * The one exception is your own arm tucked away for a container screen (2026-11-16: "when the inventory
 * opens you should maintain the hand morph"): the server puts the real item back in the slot so the inventory shows it,
 * but the arm stays formed, so whatever you are holding is drawn as the arm in your hand and on the inventory's doll
 * while the screen is open and a moment after it closes, until the arm item is back ({@link
 * dev.morphreborn.client.arm.gui.ArmInventorySignal#tucked}).
 * <p>
 * {@link #parse} and {@link #tier} are pure, for the unit tests.
 */
public final class ArmStacks {
	/** What an arm stack is: its form id and tier (1 iron, 2 diamond, 3 netherite). */
	public record Recognized(String form, int tier) {}

	/** One Recognized per form and tier, so recognising a stack every frame allocates nothing. */
	private static final Map<String, Recognized[]> INTERNED = new ConcurrentHashMap<>();

	private ArmStacks() {}

	/** The arm this stack is, from its custom_model_data strings; null for every other stack. */
	public static @Nullable Recognized recognize(ItemStack s) {
		if (s == null || s.isEmpty()) return null;
		CustomModelData cmd = s.get(DataComponents.CUSTOM_MODEL_DATA);
		return cmd == null ? null : parse(cmd.strings());
	}

	/**
	 * As {@link #recognize(ItemStack)}, then, for a stack drawn in a hand ({@code ctx} first or third person), the S_ARM
	 * fallback described above. {@code owner} is the entity the item model resolver was given (null in some screens and
	 * previews: then only the stack's own strings count).
	 */
	public static @Nullable Recognized recognize(ItemStack s, @Nullable ItemOwner owner, ItemDisplayContext ctx) {
		Recognized own = recognize(s);
		if (own != null || owner == null || s == null || s.isEmpty() || !inHand(ctx)) return own;
		LivingEntity living = owner.asLivingEntity();
		if (living == null || living.getMainHandItem() != s) return null;
		ArmState state = Arms.state(living.getUUID());
		if (state == null || !state.armed()) return null;
		String form = state.heldForm();
		Item base = baseItem(form);
		if (base == null || !s.is(base) && !ArmInventorySignal.tucked(living)) return null;
		return intern(form, Math.max(1, state.level));
	}

	public static boolean isArm(ItemStack s) {
		return recognize(s) != null;
	}

	/**
	 * What to read an arm's biomass from: {@code stack}, except while it is only taken for the arm because you are tucked
	 * (the real item the slot holds, e.g. a damaged tool: its durability is not biomass, so it would draw the arm
	 * starving); null then, which reads as a full arm. Anything carrying the arm's own marks, and every other player's
	 * stack, is returned as it is (2026-11-16).
	 */
	public static @Nullable ItemStack biomassSource(ItemStack stack, @Nullable LivingEntity living) {
		if (recognize(stack) != null) return stack;
		return living != null && ArmInventorySignal.tucked(living) ? null : stack;
	}

	/**
	 * The stack to draw in {@code owner}'s main hand in place of {@code stack}: while you are tucked, an empty main hand
	 * (the arm replaced nothing, so the tuck left air) draws the arm anyway, because vanilla builds no item model for
	 * an empty stack and the hand would show no tool (2026-11-16, "maintain the hand morph").
	 * {@code stack} itself in every other case, which is all that is ever asked for in the common one. For drawing only:
	 * nothing here is the inventory, and nothing the game reads. {@code ctx} is the hand's display context; only the
	 * main hand's side is ever replaced.
	 */
	public static ItemStack tuckedStandIn(ItemStack stack, @Nullable ItemOwner owner, ItemDisplayContext ctx) {
		if (!stack.isEmpty() || owner == null || !inHand(ctx)) return stack;
		LivingEntity living = owner.asLivingEntity();
		// the main hand's side: the offhand is drawn with the other context and stays empty
		if (living == null || ctx.leftHand() != (living.getMainArm() == HumanoidArm.LEFT)) return stack;
		return tuckedStandIn(stack, living);
	}

	/** As {@link #tuckedStandIn(ItemStack, ItemOwner, ItemDisplayContext)} for a hand already known to be {@code living}'s main hand. */
	public static ItemStack tuckedStandIn(ItemStack stack, @Nullable LivingEntity living) {
		if (!stack.isEmpty() || living == null || !ArmInventorySignal.tucked(living)) return stack;
		ArmState state = Arms.state(living.getUUID());
		if (state == null || !state.armed()) return stack;
		String form = state.heldForm();
		if (!ArmWheel.hasItem(form)) return stack;
		return ArmWheel.armStack(form, Math.max(1, state.level));
	}

	/** The bow arm: drawn, zoomed and held two-handed as vanilla does a bow, though its item is paper. */
	public static boolean isBowArm(ItemStack s) {
		Recognized r = recognize(s);
		return r != null && ArmProtocol.BOW.equals(r.form());
	}

	/** The tier a name stands for (1 iron, 2 diamond, 3 netherite), 0 for anything else. */
	public static int tier(String name) {
		return ArmProtocol.tier(name);
	}

	/**
	 * The arm in a list of custom_model_data strings, or null: the tag first, then a form id (lower-case letters, digits
	 * and underscores, at most 32), then the tier's name. A tier this client doesn't know (or none) is read as iron, so a
	 * newer server's tier still draws.
	 */
	public static @Nullable Recognized parse(@Nullable List<String> strings) {
		if (strings == null || strings.size() < 2 || !ArmProtocol.CMD_TAG.equals(strings.get(0))) return null;
		String form = strings.get(1);
		if (!validForm(form)) return null;
		int tier = strings.size() > 2 ? tier(strings.get(2)) : 0;
		return intern(form, Math.max(1, tier));
	}

	static boolean validForm(@Nullable String form) {
		if (form == null || form.isEmpty() || form.length() > ArmProtocol.MAX_FORM_ID) return false;
		for (int i = 0; i < form.length(); i++) {
			char c = form.charAt(i);
			if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_')) return false;
		}
		return true;
	}

	private static Recognized intern(String form, int tier) {
		Recognized[] tiers = INTERNED.computeIfAbsent(form, f -> new Recognized[4]);
		int t = Math.clamp(tier, 1, 3);
		Recognized r = tiers[t];
		if (r == null) tiers[t] = r = new Recognized(form, t);
		return r;
	}

	private static boolean inHand(ItemDisplayContext ctx) {
		return ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND || ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
				|| ctx == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND || ctx == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
	}

	/** The item every tier of a form is made from on the server (the tier is in the components only). */
	static @Nullable Item baseItem(String form) {
		return switch (form) {
			case ArmProtocol.KATANA -> Items.IRON_SWORD;
			case ArmProtocol.PICKAXE -> Items.IRON_PICKAXE;
			case ArmProtocol.AXE -> Items.IRON_AXE;
			case ArmProtocol.SHOVEL -> Items.IRON_SHOVEL;
			case ArmProtocol.SICKLE -> Items.IRON_HOE;
			case ArmProtocol.BOW -> Items.PAPER;
			case ArmProtocol.WHIP -> Items.FISHING_ROD;
			// the new forms (2026-11-16): the spear is one material for every tier (the tier is in its components and model), the
			// crossbow is a real crossbow (2026-11-16: it loads itself, and vanilla shows its loaded pose to every client), the claws
			// are shears
			case ArmProtocol.SPEAR -> Items.IRON_SPEAR;
			case ArmProtocol.MACE -> Items.MACE;
			case ArmProtocol.TRIDENT -> Items.TRIDENT;
			case ArmProtocol.CROSSBOW -> Items.CROSSBOW;
			case ArmProtocol.CLAWS -> Items.SHEARS;
			default -> null;
		};
	}
}
