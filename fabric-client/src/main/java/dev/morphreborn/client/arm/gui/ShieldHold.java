package dev.morphreborn.client.arm.gui;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmState;
import dev.morphreborn.client.arm.ArmStacks;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.net.ClientNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * The Tendril Shield's raise on right-click (decided 2026-11-17, in place of holding sneak), for the mod's own game. The use key is the
 * vanilla one, and the main hand comes first, as it does for a vanilla shield in the off hand:
 * <ul>
 *   <li><b>What the game does with a click.</b> {@code Minecraft.startUseItem} tries the main hand (a creature or a block in front of it, then the
 *       item's own use) and, when that result is not a success, goes on to the off hand. {@code MinecraftUseItemMixin} watches that: with the shield
 *       on, the off hand is skipped (so the real item there is never used or even sent, shield priority), and a click the main hand did not use
 *       (nothing to place, eat, draw, open or talk to, and nothing of the arm form's own) arms the shield ({@link #end}). Everything the main hand
 *       uses stays exactly as it was, since it is the game's own answer (an arm form's bow, crossbow, trident, spear or whip, food, a block, a door,
 *       a villager). What only the server knows (flint lighting something, an enderman's block, milking, a mount) is told to the server, which does
 *       not raise it for that click.</li>
 *   <li><b>Held, like a vanilla shield.</b> Once armed the click is not tried again while the key stays down (the game does not try either with
 *       an item in use), and the shield is up while the key is. Letting go, a screen opening, or the shield going off lowers it.</li>
 *   <li><b>The server.</b> It is told with C_ARM ACTION_ACCESSORY_HOLD (9), id "shield", a varInt 1 when it is raised and 0 when it is lowered, as
 *       the spyglass's zoom key is (the same contract: not throttled, repeated every {@link ArmProtocol#HOLD_REPEAT_TICKS} while held, a change the
 *       server could not be told of is tried again next tick). The server raises its own state from that, and every viewer is shown it from S_ARM.</li>
 * </ul>
 * The mixins and the key need the game; the decisions here ({@link #decide}, {@link #sync}) do not.
 */
public final class ShieldHold {
	/** What the game's click turns into with the shield on. */
	public enum Click {
		/** The shield is not involved: it is off, or the game's own answer stands (an item was already in use, or nothing was tried). */
		LEAVE,
		/** The main hand used the click: that is the item's, and the shield stays down. */
		MAIN_HAND,
		/** The main hand did not use the click: the shield is raised. */
		RAISE
	}

	/** The shield is on and offered by the server, as of the click being tried (the off hand is skipped for it). */
	private static boolean shieldOn;
	/** The main hand was tried, and the game found a use for the click in it. */
	private static boolean mainUsed, mainTried;
	/** The click raised the shield and the key has not been let go. */
	private static boolean armed;
	/** The server has been told the last answer, and ticks since it was told the shield is up (the repeat's clock). */
	private static boolean sent;
	private static int sinceTold;

	private ShieldHold() {}

	// ------------------------------------------------------------------ the pure parts

	/**
	 * What a click becomes: {@code shieldOn} the accessory is on and offered; {@code tried} the game tried the main hand at all (it does not while
	 * a block is being broken or both hands are busy); {@code mainUsed} the main hand's attempt (a creature, a block or the item) was a success
	 * or refused outright; {@code usingItem} an item was already in use (a drawn bow, a meal): the game does not start another click then.
	 */
	public static Click decide(boolean shieldOn, boolean tried, boolean mainUsed, boolean usingItem) {
		if (!shieldOn || usingItem || !tried) return Click.LEAVE;
		return mainUsed ? Click.MAIN_HAND : Click.RAISE;
	}

	/** Whether the off hand is skipped for this hand: the shield is on, and it is the off hand (shield priority). */
	public static boolean skipsHand(boolean shieldOn, boolean offHand) {
		return shieldOn && offHand;
	}

	/** Whether a new click is not tried: the shield is already up on this press, as with an item in use. */
	public static boolean blocksClick(boolean armed, boolean keyDown) {
		return armed && keyDown;
	}

	/** Whether the shield is armed this tick: the click armed it, the key is still down, the accessory is on and offered, and no screen is open. */
	static boolean stays(boolean armed, boolean keyDown, boolean accessoryOn, boolean screenOpen) {
		return armed && keyDown && accessoryOn && !screenOpen;
	}

	/** What to tell the server this tick: +1 start, -1 stop, 0 nothing (it already knows). */
	static int sync(boolean want, boolean sent) {
		return want == sent ? 0 : want ? 1 : -1;
	}

	/** As {@link #sync(boolean, boolean)}, and +1 again (the repeat that keeps it raised) once it has been up {@code sinceTold} ticks since the server was told. */
	static int sync(boolean want, boolean sent, int sinceTold) {
		int tell = sync(want, sent);
		return tell == 0 && want && sinceTold >= ArmProtocol.HOLD_REPEAT_TICKS ? 1 : tell;
	}

	// ------------------------------------------------------------------ what the mixin asks and reports

	/**
	 * A click begins ({@code Minecraft.startUseItem}). Returns false when it is not to be tried at all (the shield is already up on this press).
	 * {@code tried} is whether the game will try the main hand (not while a block is being broken or both hands are busy).
	 */
	public static boolean begin(boolean keyDown, boolean tried) {
		if (blocksClick(armed, keyDown)) return false;
		shieldOn = live();
		mainTried = tried;
		mainUsed = false;
		return true;
	}

	/** Whether this hand's attempt is skipped (the real off-hand item is not to be used or sent while the shield is on). */
	public static boolean skip(boolean offHand) {
		return skipsHand(shieldOn, offHand);
	}

	/**
	 * Whether a click with this arm form in the main hand is the arm's own even though the game's answer is not a success: the arm crossbow
	 * loads itself and needs no ammunition, so the server fires its bolt on the click, while the game's CrossbowItem.use refuses an uncharged
	 * crossbow with nothing to load (the normal case) and so looks like an unused click. Without this the click both fired the bolt and
	 * raised the shield (rig 2026-11-22). {@code mainIsArm}: the main hand really holds the arm. The session stays formed while another hotbar slot
	 * is selected (tucked), and then a click uses that slot's item, never the arm, so the shield must still rise.
	 */
	public static boolean armCrossbowClick(String heldForm, int state, boolean mainIsArm) {
		return mainIsArm && ArmProtocol.CROSSBOW.equals(heldForm) && ArmProtocol.usable(state);
	}

	/** {@link #armCrossbowClick(String, int, boolean)} for the local player's own arm and main hand. */
	public static boolean armCrossbowClick(LocalPlayer player) {
		ArmState st = Arms.state(player.getUUID());
		return st != null && armCrossbowClick(st.heldForm(), st.state, ArmStacks.isArm(player.getMainHandItem()));
	}

	/** The main hand's attempt came back: {@code used} it was a success (or, for a block, refused outright, which ends the click too). */
	public static void note(boolean offHand, boolean used) {
		if (shieldOn && !offHand && used) mainUsed = true;
	}

	/** The click is over: the main hand either used it or did not, and the shield is armed when it did not. */
	public static void end(boolean usingItem) {
		Click click = decide(shieldOn, mainTried, mainUsed, usingItem);
		shieldOn = false;
		if (click == Click.RAISE) armed = true;
	}

	private static boolean live() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc == null ? null : mc.player;
		if (player == null || mc.level == null || !ClientNet.armNewFormsOffered()) return false;
		ArmState st = Arms.state(player.getUUID());
		return st != null && st.accessoryOn(ArmProtocol.SHIELD);
	}

	// ------------------------------------------------------------------ the tick

	/** Every client tick (ArmUi registers it): lowers the shield when it should be, and keeps the server told. */
	public static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		boolean on = player != null && mc.level != null && live();
		armed = stays(armed, mc.options.keyUse.isDown(), on, mc.gui.screen() != null);
		int tell = sync(armed, sent, sinceTold);
		if (tell > 0) {
			if (ClientNet.sendAccessoryHold(ArmProtocol.SHIELD, true)) {
				sent = true;
				sinceTold = 0;
			}
		} else if (tell < 0) {
			if (!ClientNet.armNewFormsOffered() || ClientNet.sendAccessoryHold(ArmProtocol.SHIELD, false)) sent = false;
		}
		if (sent && armed) sinceTold++;
	}

	/** Leaving a world or server: nothing is raised and nothing is owed to the server. */
	public static void reset() {
		shieldOn = false;
		mainUsed = false;
		mainTried = false;
		armed = false;
		sent = false;
		sinceTold = 0;
	}
}
