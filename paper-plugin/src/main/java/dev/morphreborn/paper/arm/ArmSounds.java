package dev.morphreborn.paper.arm;

/**
 * What an arm tool or accessory sounds like as it forms, reshapes, retracts or melts. Every one of them is the Morph
 * recordings, sped up and thinned out a little (the events {@code arm_form} and {@code arm_retract} in the client mod's
 * and the optional pack's sounds.json); the numbers here are the ones that go with those entries. Players who can't have
 * the events (no mod, and the server's pack isn't known to hold them) hear the same stand-in as the transformation does,
 * so every viewer hears one Morph sound per event.
 * <p>
 * Pure on purpose: who hears which is {@link #hasEvents}, what plays is {@link #voice}, and ArmEffects only sends it.
 */
final class ArmSounds {
	/** The client mod's and the pack's events (sounds.json). */
	static final String FORM_EVENT = "morphreborn:arm_form";
	static final String RETRACT_EVENT = "morphreborn:arm_retract";

	/** How loud and how high the stand-in plays, against the event it replaces: the same 0.6 the morph's stand-in uses. */
	private static final float FALLBACK_VOLUME = 0.6F;
	private static final float MIN_PITCH = 0.5F;

	/** The moments that make a sound. A forced retract, a death or a morph change makes none (a morph change plays the morph sound itself). */
	enum Moment {
		/** A new arm taking shape: tendrils out of the knuckles. */
		FORM(FORM_EVENT, 1.0F, 1.0F, 0.8F),
		/** Scrolling onto a bound slot: the quick re-form, softer. */
		FORM_QUICK(FORM_EVENT, 0.6F, 1.1F, 0.8F),
		/** One form collapsing into another. */
		RESHAPE(FORM_EVENT, 0.8F, 1.15F, 0.8F),
		/** A chosen retract begins: lower than forming, so the two read as a pair. */
		RETRACT(RETRACT_EVENT, 1.0F, 1.0F, 0.65F),
		/** Out of biomass: the arm sags, lower again. */
		MELT(RETRACT_EVENT, 0.9F, 0.75F, 0.65F);

		final String event;
		final float volume;
		final float pitch;
		/** The stand-in's pitch for this moment's family, before the moment's own pitch (a retract plays lower than a form). */
		final float fallbackPitch;

		Moment(String event, float volume, float pitch, float fallbackPitch) {
			this.event = event;
			this.volume = volume;
			this.pitch = pitch;
			this.fallbackPitch = fallbackPitch;
		}
	}

	/** One sound to play: the key, volume and pitch. */
	record Voice(String key, float volume, float pitch) {
	}

	private ArmSounds() {
	}

	/**
	 * Whether a listener's game has the events. A modded client has them from {@link ArmProtocol#ARM_SOUNDS_SINCE}; an older
	 * mod hasn't, and doesn't count the pack either (an unknown id is silent, and the stand-in is not). Everyone else (no mod,
	 * Bedrock included) has them only if the server says its pack holds them, as for the morph sound.
	 */
	static boolean hasEvents(boolean modded, boolean clientKnowsEvents, boolean serverPackHoldsSounds) {
		return modded ? clientKnowsEvents : serverPackHoldsSounds;
	}

	/** What plays for a listener: the event, or the transformation's stand-in key when the listener's game doesn't have it. */
	static Voice voice(Moment moment, boolean hasEvents, String fallbackKey) {
		if (hasEvents) return new Voice(moment.event, moment.volume, moment.pitch);
		// the game plays nothing below 0.5, and a melt's low pitch on a low stand-in would land there
		return new Voice(fallbackKey, moment.volume * FALLBACK_VOLUME, Math.max(MIN_PITCH, moment.fallbackPitch * moment.pitch));
	}
}
