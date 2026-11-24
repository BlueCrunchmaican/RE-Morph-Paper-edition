package dev.morphreborn.client.arm.render;

/**
 * The draws an arm form is made of. Every piece of geometry belongs to one of them, and each is one render type
 * ({@link ArmTex#type}), so all the armed players on screen share three or four draws a frame however many pieces they
 * have. Geometry code is asked for one layer at a time and emits only its faces of that layer.
 * <ul>
 *   <li>{@link #BLACK}: the morph skin's black static, most of every form.</li>
 *   <li>{@link #RED}: the red static: edges, veins, windows between strands.</li>
 *   <li>{@link #GLOW}: the bright red skin, drawn at full brightness for glowing parts (L3 edges, a flaring hamon).</li>
 *   <li>{@link #SENSE}: the outline buffer, so a vein seen through stone shows as a red outline.</li>
 * </ul>
 */
public enum ArmLayer {
	BLACK, RED, GLOW, SENSE
}
