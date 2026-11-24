package dev.morphreborn.client.render;

/**
 * How big a borrowed head is drawn on the body it goes on (HybridMorph.place), as pure numbers so it can be tested and
 * kept level with the plugin's pack heads for players without the mod (BorrowedHeads.modScale, which mirrors this).
 */
final class LentHeadSize {
	/** A borrowed head is drawn at most this many times the size of the head it replaces. */
	static final float BIG_HEAD = 1.5F;

	private LentHeadSize() {}

	/**
	 * The factor a borrowed head is drawn at, against its own size. {@code ownSize} and {@code mainSize} are the sides of
	 * the cubes of the two heads' big blocks, in blocks as drawn; the body's head as it rests, not as it is turned (2026-11-17,
	 * rig B3-08: a giant looking up has a head 1.25 times as big by the box round it, and the ghast on it was drawn that
	 * much bigger than players without the mod see it).
	 * <ul>
	 * <li>On any body, a creature lent whole (a ghast, a slime) is a head no bigger than the head it stands in for: a
	 * full-size ghast on a person buried the body and the ground under its tentacles (I saw it).</li>
	 * <li>On a giant or a dragon ({@code growsToBody}) a small head is scaled up to the body's head, as the dragon's parts
	 * are to the body: at its own size a cow's head was a speck on the end of the dragon's neck, and a squid's no bigger.
	 * A creature lent whole is then exactly as big as that head, up or down. Any other head is at most BIG_HEAD times it
	 * (2026-11-17, rig B3-08): a ghast on a dragon, whose head is one block, was drawn as big as the
	 * whole dragon, hiding it, and on a giant 1.28 times wider than players without the mod see it.</li>
	 * <li>On any other body, a much bigger head (a giant's, a ravager's) is at most BIG_HEAD times the one it replaces: a
	 * giant's head on a person is bigger than a zombie's would be, not a giant's (my call). Otherwise its own.</li>
	 * </ul>
	 */
	static float grow(boolean wholeBody, boolean growsToBody, float ownSize, float mainSize) {
		return grow(wholeBody, growsToBody, ownSize, mainSize, Float.MAX_VALUE, BIG_HEAD);
	}

	/**
	 * {@link #grow(boolean, boolean, float, float)} held to {@code cap} times the head it replaces on every axis as well, not only by
	 * the cube roots (rig, 2026-11-20, about 400 pairs): a llama's neck and head, a ravager's, a warden's, a wither's or a sniffer's is
	 * a tall or wide slab whose cube root passes the cap while one side is 2 to 4 times the main's head, a column over a villager's head
	 * or a head wider than a goat's whole body. {@code axisFit} is {@link #axisFit}: how many times bigger than the main's head the
	 * borrowed one may be along its tightest side; {@link Float#MAX_VALUE} for none. {@code cap} is BIG_HEAD, or 1
	 * ({@link #headCap}) for a body with no head part under TINY_HEADLESS: a head as long as the whole fish was too much (my call, 2026-11-20).
	 * A creature lent whole (a squid, a tadpole, a strider) is sized by its core above, then held to the cap on every side of the whole of
	 * it as drawn, tentacles and legs counted ({@code axisFit} from the box round all of it): a squid's mantle fitted the head and its
	 * tentacles ran to the floor, the whole squid three times the head (rig, 2026-11-20). Not on a giant, a
	 * dragon or a ghast, where it is exactly as big as the head by design.
	 */
	static float grow(boolean wholeBody, boolean growsToBody, float ownSize, float mainSize, float axisFit, float cap) {
		if (ownSize <= 0.01F) return 1F;
		if (wholeBody) return growsToBody ? mainSize / ownSize : Math.min(Math.min(1F, mainSize / ownSize), cap * axisFit);
		float k;
		if (growsToBody) k = Math.min(Math.max(1F, mainSize / ownSize), cap * mainSize / ownSize);
		else k = ownSize > cap * mainSize ? cap * mainSize / ownSize : 1F;
		return Math.min(k, cap * axisFit);
	}

	/** A main side under this share of its longest counts as that share in {@link #axisFit}, so a flat head does not turn a lent head into a sliver. */
	static final float FLAT_FLOOR = 0.25F;
	/**
	 * The same floor for a main head that rests pitched and is measured level (a hoglin's, a zoglin's 14 x 6 x 19 pixel slab): its 6 pixels
	 * are a snout, not a head, and a quarter of its length held the lent head to 0.45 of its size (rig, 2026-11-20).
	 */
	static final float PITCHED_FLAT_FLOOR = 0.5F;

	/**
	 * How many times bigger than the main's head a borrowed one may be along its tightest side: the least, over the three sides, of the
	 * main's over the borrowed one's ({@code main} and {@code own}: the x, y and z sides of the boxes round their big blocks, in blocks as
	 * drawn). A main side under {@link #FLAT_FLOOR} of its longest counts as that, so a flat head (a frog's 3 pixels) does not turn a
	 * lent head into a sliver. {@link Float#MAX_VALUE} when either is missing or a borrowed side has no size.
	 */
	static float axisFit(float @org.jspecify.annotations.Nullable [] main, float @org.jspecify.annotations.Nullable [] own) {
		return axisFit(main, own, FLAT_FLOOR);
	}

	/** {@link #axisFit(float[], float[])} with {@code floor} as the share of the longest side a flat side counts as. */
	static float axisFit(float @org.jspecify.annotations.Nullable [] main, float @org.jspecify.annotations.Nullable [] own, float floor) {
		if (main == null || own == null) return Float.MAX_VALUE;
		float longest = Math.max(main[0], Math.max(main[1], main[2])), fit = Float.MAX_VALUE;
		if (longest <= 0.01F) return Float.MAX_VALUE;
		for (int axis = 0; axis < 3; axis++) {
			if (own[axis] > 0.005F) fit = Math.min(fit, Math.max(main[axis], floor * longest) / own[axis]);
		}
		return fit;
	}

	/** The most a head is drawn times the head it replaces: BIG_HEAD, but 1 on a body with no head part under TINY_HEADLESS ({@code bodyCore}). */
	static float headCap(boolean headless, float bodyCore) {
		return headless && bodyCore > 0.01F && bodyCore < TINY_HEADLESS ? 1F : BIG_HEAD;
	}

	/**
	 * A head that rests pitched (a hoglin's and a zoglin's, 50 degrees nose down) is drawn level: a person with it had a bowed head
	 * with the snout on its chest, a pig's lay on the floor in front of the legs (rig, 2026-11-20, 45 pairs). {@code pitch}: how far
	 * nose down it rests, radians; the turn that levels it, or 0 for a head that rests less than LEVEL_PITCH down (a horse's 30
	 * degrees is the animal's own carriage and stays).
	 */
	static float levelPitch(float pitch) {
		return Math.abs(pitch) >= LEVEL_PITCH ? pitch : 0F;
	}

	/**
	 * How far nose down a head faces, radians, from the direction it faces in its body's frame (+y down). That direction comes out of a
	 * matrix that may carry a size (a baby's or a scaled lender's), so its length is not 1: read the height off it as it comes and a
	 * head at 0.5 size resting 50 degrees down read under 40 and stayed bowed, at 2 it read a quarter turn. 0 for no direction.
	 */
	static float pitchOf(float x, float y, float z) {
		float length = (float) Math.sqrt(x * x + y * y + z * z);
		if (length < 1e-6F) return 0F;
		return (float) Math.asin(Math.clamp(y / length, -1F, 1F));
	}

	/** 40 degrees. */
	static final float LEVEL_PITCH = (float) Math.toRadians(40);

	/**
	 * Whether a lent head is drawn as tall as the per-axis cap leaves a big head on a pitched main (a hoglin's, a zoglin's): the head block is
	 * flat (6 pixels), and a flat side counts as PITCHED_FLAT_FLOOR of the block's longest side ({@link #axisFit}), so no head is held taller than
	 * BIG_HEAD times that, 0.9 block for the hoglin's 1.2 long block. A head that reaches it (a giant's, a ravager's, a sniffer's, a warden's,
	 * all drawn 0.9 tall) is seated at the hog's head. One shorter (a zombie's, a pig's, a villager's) keeps the earlier seat above the
	 * shoulders (the 'apart' lift): all of those are taller than the 6 pixel block and took the new seat in round 4 before this test, unchecked.
	 * {@code share} of the cap leaves room for the float noise of the boxes.
	 */
	static boolean fillsPitchedHead(float lentHeight, float headLongest) {
		return headLongest > 0.01F && lentHeight >= PITCHED_SEAT_SHARE * BIG_HEAD * PITCHED_FLAT_FLOOR * headLongest;
	}

	/** 95 percent: how near the axis cap's height a lent head must come to count as filling it ({@link #fillsPitchedHead}). */
	static final float PITCHED_SEAT_SHARE = 0.95F;

	/**
	 * The lent heads that are levelled on a body whose top is a flat plate: a goat's and a skeleton horse's rest nose down and leaned over the
	 * front edge of the elder guardian's top plate (rig, 2026-11-21), and hung out in front of the magma cube and the sulfur cube, touching only
	 * by the nose (round 5). {@code main}: the body they stand on.
	 */
	static boolean levelsOnFlatTop(net.minecraft.world.entity.EntityType<?> main, net.minecraft.world.entity.EntityType<?> lent) {
		boolean plate = main == net.minecraft.world.entity.EntityTypes.ELDER_GUARDIAN || main == net.minecraft.world.entity.EntityTypes.MAGMA_CUBE
				|| main == net.minecraft.world.entity.EntityTypes.SULFUR_CUBE;
		return plate && (lent == net.minecraft.world.entity.EntityTypes.GOAT || lent == net.minecraft.world.entity.EntityTypes.SKELETON_HORSE);
	}

	/**
	 * How far a head ({@code depth} deep as drawn, hinged at its back edge) that stands on a flat top {@code topDepth} deep is moved back so it
	 * does not hang out over the front edge: a lent head at 1.5 times the cube's own size reached 1.5 cubes forward of the centre it was hinged
	 * on, 55 to 65 percent of it in the air in front of a magma cube (round 5). Moved back until its front is at the top's front edge; a head
	 * deeper than the top is centred on it instead. 0 for a head that already fits inside the front half.
	 */
	static float backIntoTop(float depth, float topDepth) {
		if (depth <= 0.01F || topDepth <= 0.01F) return 0F;
		float over = depth - topDepth / 2F;
		return over <= 0F ? 0F : Math.min(over, depth / 2F);
	}

	/** A head's bottom may stand this far (blocks) above the top it is seated on before {@link #closeGap} moves it down: float noise, not a gap anyone sees. */
	static final float SEAT_GAP_TOLERANCE = 0.005F;

	/**
	 * How far down (blocks, model axes: +y is down) a lent head that stands on a body's top is moved so that no air is left under it. {@code lowest}: the lowest point
	 * of its big blocks as placed, {@code top}: the top it is seated on, both in the body's frame; {@code sunk}: how far into the top it ends. 0 where it already meets
	 * the top or sinks into it: it only ever closes a gap, it never lifts a head. A sniffer's head hovered about 0.1 block over a magma cube, only its hanging ear
	 * strands reaching the cube (rig, 2026-11-22, round 6), however the seat's own numbers added up.
	 */
	static float closeGap(float lowest, float top, float sunk) {
		float gap = top - lowest;
		return gap > SEAT_GAP_TOLERANCE ? gap + sunk : 0F;
	}

	/** The share of its body a stand-in head is, for a body as big as a ghast that is all one cube: a head half as big as the cube. */
	static final float HUGE_HEADLESS_SHARE = 0.5F;

	/**
	 * What a body with no head part (a pufferfish, a tadpole, a bee, a ghast) has for a head, so a borrowed head has something to
	 * be sized against: its body's own core (the side of the cube of its big blocks), and for a body as big as a ghast, half of
	 * it. Without one the cap was skipped and a husk's head was 2 to 5 times the whole fish (rig, 2026-11-18). A creature lent
	 * whole is held to it too, and a ghast, which is nothing but a cube, takes small heads up to the stand-in as a giant does.
	 */
	static float headlessStandIn(float bodyCore, boolean huge) {
		return huge ? bodyCore * HUGE_HEADLESS_SHARE : bodyCore;
	}

	/** A huge body this much bigger than the creature a part comes from (by their torsos) grows the part with it, as a dragon's parts always do. */
	private static final float PARTS_STAY = 2.5F, PARTS_FOLLOW = 4F;

	/** No part is drawn bigger than this, whatever a thin torso's fit says (Graft.fit floors each axis at 0.01, so a flat box gives a huge number). */
	static final float PARTS_MAX = 24F;

	/**
	 * How much to scale a borrowed creature's wings, fins, rods and tail for a body whose torso is {@code fit} times as big (the cube
	 * root of the ratio of the torsos' volumes, HybridMorph.Graft.fit). Only on a huge body ({@code hugeMain}: a giant, a ghast, a happy
	 * ghast; the dragon's own parts are always scaled and do not come here): up to 2.5 times they keep their own size, as they always
	 * did; from 4 times they grow with the body ("wings and tails scale with huge bodies": allay wings were a few pixels on a giant and
	 * on a ghast); in between they run from one to the other, so a body just past the edge sees no jump. On every other body, a horse
	 * or a cow or a villager with an allay's wings, they keep their size: only the huge ones were meant to change. Never more than
	 * PARTS_MAX.
	 */
	static float partScale(boolean hugeMain, float fit) {
		if (!hugeMain || fit <= PARTS_STAY) return 1F;
		if (fit >= PARTS_FOLLOW) return Math.min(fit, PARTS_MAX);
		return 1F + (fit - PARTS_STAY) / (PARTS_FOLLOW - PARTS_STAY) * (PARTS_FOLLOW - 1F);
	}

	/**
	 * The bodies that grow a lent creature's parts with them: the plugin's pack view holds the same list (BorrowedHeads.PARTS_GROW). The
	 * ender dragon is one (2026-11-20): a bat's wings on a 16 block dragon were a few pixels, nothing came over. The elder guardian
	 * is another (2026-11-21): a 2 block cube whose lent bat, bee and vex wings were a tenth of its side, hovering off it.
	 */
	static boolean growsParts(net.minecraft.world.entity.EntityType<?> type) {
		return type == net.minecraft.world.entity.EntityTypes.GIANT || type == net.minecraft.world.entity.EntityTypes.GHAST
				|| type == net.minecraft.world.entity.EntityTypes.HAPPY_GHAST || type == net.minecraft.world.entity.EntityTypes.ENDER_DRAGON
				|| type == net.minecraft.world.entity.EntityTypes.ELDER_GUARDIAN;
	}

	/**
	 * The size ears are drawn at on the body's head: the share of the head's width to the borrowed head's. Never twice (an armadillo's
	 * ears at 2.5 were twice its head) except on a giant or a dragon, whose heads are the reason the ears grow, and not under a
	 * seventh (a guardian's plates on a parrot's head were 0.88 blocks across at the floor of a half, 3 times its head).
	 */
	static float earScale(float headRatio, boolean huge) {
		return Math.max(0.15F, huge ? headRatio : Math.min(2F, headRatio));
	}

	/** The tallest a lent ear is drawn, as a share of the height of the head it stands on (the stand-in's on a body without a head part). */
	static final float EAR_HEIGHT_SHARE = 0.75F;

	/**
	 * {@code size} (from {@link #earScale}, the ratio of the heads' widths) held so the tallest lent ear, {@code earHeight} tall at its own
	 * size, is at most EAR_HEIGHT_SHARE of {@code headHeight}, the head it stands on; a giant's or a dragon's ({@code huge}), whose ears grow
	 * with their heads, at most all of it. A donkey's ears on a zombie were a head's height over it (0.5 block over a 0.5 block head), on a cube
	 * or a nautilus the shell's height and more, and a parrot's flat crest 3 blocks over a dragon's head (rig, 2026-11-20, about 100 pairs).
	 * {@code size} unchanged for an ear or a head with no height. It is applied after {@link #earScale}'s floor and wins over it: the floor
	 * was what made a guardian's plates three times a parrot's head, so a very tall ear may end up under it.
	 */
	static float earCap(float size, float earHeight, float headHeight, boolean huge) {
		if (earHeight <= 0.001F || headHeight <= 0.001F) return size;
		float most = (huge ? 1F : EAR_HEIGHT_SHARE) * headHeight;
		return earHeight * size > most ? most / earHeight : size;
	}

	/** A torso narrower than this (a vex's 0.25, an allay's) puts wings at its side edges, not 20 percent in: the two roots were a tenth of a block apart. */
	static final float THIN_TORSO = 0.35F;

	/**
	 * The most a lent fin is rooted in from the side of a wide standing torso ({@link HybridMorph.Graft#WIDE_MAIN}): a wither's 1.25 block shoulder bar
	 * takes its fifth, 0.25; a giant's 3 blocks would take 0.6, half the size of the fin, buried in its flank, so it takes this and no more.
	 */
	static final float WIDE_FIN_INSET_MAX = 0.3F;

	/** The inset grows from nothing at THIN_TORSO - WING_RAMP to its full fifth at THIN_TORSO, so the roots do not jump as a body's size crosses the line. */
	private static final float WING_RAMP = 0.05F;

	/** How far in from each side edge a standing torso {@code width} across puts its wings' roots: a fifth of it, 0 for a thin torso, eased in over the WING_RAMP under THIN_TORSO. */
	static float wingInset(float width) {
		if (width >= THIN_TORSO) return 0.2F * width;
		return width <= THIN_TORSO - WING_RAMP ? 0F : 0.2F * width * (width - (THIN_TORSO - WING_RAMP)) / WING_RAMP;
	}

	/** A lent wing, fin or stinger is drawn at least this share of the main torso's longest side across, so something shows on a big body. */
	static final float PART_FLOOR = 0.15F;

	/**
	 * {@code scale} for a lent wing, fin or stinger that is {@code extent} across at scale 1, raised so it is at least PART_FLOOR of the main
	 * torso's longest side {@code torsoLongest} (the bee's wings and a tropical fish's fins were 1 to 3 pixels on a horse, a giant, a
	 * ravager), but never past {@code ceiling} (the tiny-main limit of a body too small for them, {@link #tinyMainPartLimit}) and never raised
	 * past PARTS_MAX (a part with next to no extent would otherwise ask for a slab many blocks across).
	 */
	static float partFloor(float scale, float extent, float torsoLongest, float ceiling) {
		return partFloor(scale, extent, torsoLongest, ceiling, false);
	}

	/**
	 * A lent fin that is a flat plane (a fish's, a dolphin's) is drawn at least this share of the main torso's longest side across. A plane
	 * has no area side-on, so the 0.15 every part gets left a cod's or a tropical fish's fins 2 to 3 pixels on a person, a horse or a cube:
	 * nothing readable came over on about 150 pairs (rig, 2026-11-20 and 2026-11-21, my call: about a third).
	 */
	static final float FIN_FLOOR = 0.3F;

	/** No single lent fin or flipper is drawn longer than this share of the main torso's longest side: a dolphin's dorsal and flippers were as long as a cat or an axolotl. */
	static final float FIN_CAP = 0.6F;

	/**
	 * Whether a lent part is a flat plane: {@code sides} are its three sides at rest, smallest first. Thin (a quarter of the longest or
	 * less) and broad (the middle side at least 0.4 of the longest): a fin or a flat tail, not a stick (a cat's tail 1 x 1 x 8) or a block.
	 */
	static boolean planar(float[] sides) {
		float longest = sides[2];
		return longest > 0.01F && sides[0] <= 0.25F * longest && sides[1] >= 0.4F * longest;
	}

	/** {@link #partFloor(float, float, float, float)}, with FIN_FLOOR for the share where the part is a flat plane ({@code planar}). */
	static float partFloor(float scale, float extent, float torsoLongest, float ceiling, boolean planar) {
		if (extent <= 0.01F || torsoLongest <= 0.01F) return Math.min(scale, ceiling);
		return Math.min(Math.max(scale, Math.min((planar ? FIN_FLOOR : PART_FLOOR) * torsoLongest / extent, PARTS_MAX)), ceiling);
	}

	/**
	 * A strip fin (a cod's dorsal: 0 x 1 x 6 pixels, thin on two sides, so not {@link #planar}) is drawn at least this share of the main torso's
	 * longest side high. The floors above grow a part by its longest side, which the 6 pixel dorsal already passes, so its one pixel of height
	 * never grew: a hairline on a person (rig, 2026-11-21, cod on witch, wither skeleton, bogged, piglin). Alone it does nothing on a fin more than
	 * 3 times as long as high: {@link #FIN_CAP} on its length wins first (0.6 of the torso long is 0.1 of it high for the cod's 6 to 1), which is why
	 * the elder guardian's cod stayed 0.235 block high after the floor went from 0.12 to 0.2 (round 5). A cube takes the
	 * longer {@link #STRIP_FIN_CAP} instead ({@link #stripFinScale}).
	 */
	static final float FIN_HEIGHT_FLOOR = 0.2F;

	/**
	 * How long a lent strip fin (a cod's dorsal) may be on a big cube (an elder guardian's 2.35 blocks, a ghast's 4), as a share of the torso's
	 * longest side: 0.8 makes the cod's dorsal 0.13 of it high, 0.31 block on the guardian, where FIN_CAP's 0.6 left it 0.235 (a hairline over a
	 * 2.35 block cube). A fish's dorsal runs most of its back, so this is not out of line; other bodies keep FIN_CAP (my call, 2026-11-21).
	 */
	static final float STRIP_FIN_CAP = 0.8F;

	/**
	 * The scale of a lent strip fin ({@code length} its longest side and {@code height} its second, at scale 1, blocks as drawn): the height floor
	 * ({@link #finHeightFloor}), then the length cap, STRIP_FIN_CAP on a big cube ({@code cube} and at least BIG_MAIN across) and FIN_CAP on any other
	 * body. The one place both are applied, so a test sees what is drawn and not each half alone.
	 */
	static float stripFinScale(float scale, float length, float height, float torsoLongest, float ceiling, boolean cube) {
		float raised = finHeightFloor(scale, height, torsoLongest, ceiling);
		float share = cube && torsoLongest >= BIG_MAIN ? STRIP_FIN_CAP : FIN_CAP;
		return finCap(raised, length, torsoLongest, share);
	}

	/**
	 * {@code scale} for a lent strip fin whose second side is {@code height} at scale 1, raised so that side is at least FIN_HEIGHT_FLOOR of
	 * {@code torsoLongest}, never past {@code ceiling} or PARTS_MAX; {@code scale} unchanged for a fin or a torso with no size.
	 */
	static float finHeightFloor(float scale, float height, float torsoLongest, float ceiling) {
		if (height <= 0.01F || torsoLongest <= 0.01F) return scale;
		return Math.max(scale, Math.min(Math.min(FIN_HEIGHT_FLOOR * torsoLongest / height, PARTS_MAX), ceiling));
	}

	/** A main torso this long (blocks, its longest side) or more is a big body for a lent wing: see {@link #WING_FLOOR}. */
	static final float BIG_MAIN = 1.5F;

	/**
	 * A lent wing is drawn at least this share of the torso's longest side across on a big body ({@link #BIG_MAIN}): the 0.15 every part gets left
	 * a bee's, vex's or bat's wings 0.3 to 0.5 block on a ravager, a sniffer, a camel or a wither (rig, 2026-11-21, 9 pairs).
	 */
	static final float WING_FLOOR = 0.3F;

	/**
	 * A vex's flat wing plane on a big body is drawn at least this share of the torso's longest side across: edge-on it read as a sliver (rig,
	 * 2026-11-21, ravager and sniffer + vex). Only the vex gets it (the caller passes {@code planar} for its wings alone): the chicken's, parrot's,
	 * bat's, allay's and bee's wings are flat too, and the 0.3 they have on a big body is what their settled pairs were seen with.
	 */
	static final float PLANAR_WING_FLOOR = 0.5F;

	/** {@link #partFloor(float, float, float, float, boolean)} with the share a wing gets on a big body. */
	static float wingFloor(float scale, float extent, float torsoLongest, float ceiling) {
		return wingFloor(scale, extent, torsoLongest, ceiling, false);
	}

	/** {@link #wingFloor(float, float, float, float)} with PLANAR_WING_FLOOR for a vex's flat plane ({@code planar}: the caller says so) on a big body. */
	static float wingFloor(float scale, float extent, float torsoLongest, float ceiling, boolean planar) {
		if (extent <= 0.01F || torsoLongest <= 0.01F) return Math.min(scale, ceiling);
		float share = torsoLongest >= BIG_MAIN ? (planar ? PLANAR_WING_FLOOR : WING_FLOOR) : PART_FLOOR;
		return Math.min(Math.max(scale, Math.min(share * torsoLongest / extent, PARTS_MAX)), ceiling);
	}

	/** A body this tall (blocks, its torso) or more gives a lent breeze's rods the floor in {@link #RODS_FLOOR}. */
	static final float TALL_MAIN = 1.5F;

	/**
	 * The rods of a lent breeze are at least this share of a tall body's torso height ({@link #TALL_MAIN}): 0.45 block rods stood as stubs against a
	 * 1.8 block sniffer (rig, 2026-11-21).
	 */
	static final float RODS_FLOOR = 0.4F;

	/** {@code scale} for the rods, {@code rodsHeight} tall at scale 1, on a torso {@code torsoHeight} tall: raised to RODS_FLOOR of it on a tall one, never past {@code ceiling}. */
	static float rodsScale(float scale, float rodsHeight, float torsoHeight, float ceiling) {
		if (rodsHeight <= 0.01F || torsoHeight < TALL_MAIN) return scale;
		return Math.min(Math.max(scale, Math.min(RODS_FLOOR * torsoHeight / rodsHeight, PARTS_MAX)), ceiling);
	}

	/**
	 * The distance the ring of a lent breeze's rods stands off the torso: {@code margin} (0.12 blocks) where the rods are the size every other part
	 * has, less as they are held smaller on a tiny body, and more as they are grown on a big one (the margin was held at 0.12 whatever their
	 * size, so a rod grown to 0.7 block stood flush against a 1.6 block slab). {@code scale}: the rods' size over the plain parts scale (1 for
	 * neither held nor raised, so an ordinary body keeps its settled ring).
	 */
	static float ringMargin(float margin, float scale) {
		return margin * Math.max(0F, scale);
	}

	/**
	 * No pectoral fin or flipper on a blocky main (a cube, a ghast, a squid's mantle) is drawn longer than this share of the torso's longest side: FIN_CAP's 0.6
	 * made a dolphin's flipper as long as the cube it was on, lying along its floor, and a cod's pectoral on the elder guardian a 0.7 to 0.8 block slab hanging
	 * down its face (rig, 2026-11-21 and 2026-11-22, round 6; my call, 0.4: a flipper two fifths of the body long).
	 */
	static final float PECTORAL_CAP = 0.4F;

	/**
	 * The scale of a pectoral fin or flipper ({@code extent} long at scale 1) on a blocky main whose torso is {@code torsoLongest} at its longest side: PECTORAL_CAP of it, the
	 * floor and the cap in one, so a squid's 0.75 block mantle gets 0.3 block fins (they were 0.22) and a 1 block cube 0.4; never past {@code ceiling} (a tiny main's limit) or
	 * PARTS_MAX. {@code scale} where the fin or the torso has no size.
	 */
	static float pectoralScale(float scale, float extent, float torsoLongest, float ceiling) {
		if (extent <= 0.01F || torsoLongest <= 0.01F) return scale;
		return Math.min(Math.min(PECTORAL_CAP * torsoLongest / extent, PARTS_MAX), ceiling);
	}

	/** A dorsal fin on a cube is at most this share of the cube's height tall: a dolphin's, grown by its length, stood half a cube over the top as a square post (round 6). */
	static final float CUBE_DORSAL_SHARE = 0.3F;

	/** {@code scale} held so a fin {@code extent} across is at most FIN_CAP of {@code torsoLongest}; never raised, unchanged for a part or a torso with no size. */
	static float finCap(float scale, float extent, float torsoLongest) {
		return finCap(scale, extent, torsoLongest, FIN_CAP);
	}

	/** {@link #finCap(float, float, float)} with {@code share} of the torso's longest side in place of FIN_CAP. */
	static float finCap(float scale, float extent, float torsoLongest, float share) {
		if (extent <= 0.01F || torsoLongest <= 0.01F) return scale;
		return Math.min(scale, share * torsoLongest / extent);
	}

	/**
	 * Whether a lent tail is a strip: no thickness at all and narrow (a camel's, 3 x 14 pixels), which {@link #planar} leaves out because its middle
	 * side is a fifth of the longest. It gets the flat tail's floor: a strip scaled to a third of its size was a 1 pixel speck on a nautilus (rig,
	 * 2026-11-21, round 5). {@code sides}: its three sides at rest, smallest first.
	 */
	static boolean stripTail(float[] sides) {
		float longest = sides[2];
		return longest > 0.01F && sides[0] <= 0.02F * longest && sides[1] >= 0.15F * longest;
	}

	/** A lent strip tail on a shell body is drawn at least this many blocks wide: the camel's 3 pixel strip was a 0.04 to 0.08 block hairline beside the shell's 0.75 block ears. */
	static final float STRIP_TAIL_WIDTH = 0.12F;

	/** ... but not longer than this share of the shell's height, so a strip that is made wide enough is not a streamer down the closed end. */
	static final float STRIP_TAIL_LENGTH_SHARE = 0.5F;

	/**
	 * {@code scale} for a lent strip tail (a camel's: no thickness, 3 x 14 pixels) on a shell body (a nautilus's, a zombie nautilus's), raised until the strip is at least
	 * STRIP_TAIL_WIDTH across ({@code width}: its middle side at scale 1, blocks as drawn) while its length ({@code length}, the longest side) stays within
	 * STRIP_TAIL_LENGTH_SHARE of the shell's height ({@code shellHeight}); never past {@code ceiling} or PARTS_MAX, never made smaller. The floors before it grow a strip by its
	 * length alone, and the width of a 14 pixel strip is a fifth of that (rounds 4 and 5 left it 0.05 block across; round 6).
	 */
	static float stripTailWidthFloor(float scale, float width, float length, float shellHeight, float ceiling) {
		if (width <= 0.01F || length <= 0.01F || shellHeight <= 0.01F) return scale;
		float wanted = Math.min(STRIP_TAIL_WIDTH / width, STRIP_TAIL_LENGTH_SHARE * shellHeight / length);
		return Math.max(scale, Math.min(Math.min(wanted, PARTS_MAX), ceiling));
	}

	/** A torso whose longest side is at most this is small enough to be a minor part of a taller creature ({@link #partReference}). */
	static final float SMALL_TORSO = 0.55F;

	/**
	 * The length the lent fins and tails are sized against ({@link #partFloor}, {@link #finCap}, {@link #tailFloor}): the torso's longest side, but
	 * for a creature whose torso is a small part of it (an allay's 0.2 block column under a big head, a blaze's head cube among its rods, a vex under
	 * its wings: a torso of at most SMALL_TORSO and 0.6 of the whole height) the whole drawn height, held to twice the torso. Against the torso
	 * alone the floors, and the fin cap even more, left a tropical fish's pieces 0.1 block on a 0.6 to 1.8 block creature and nothing read (rig,
	 * 2026-11-21, round 5: allay, blaze, vex). {@code whole}: the creature's height without its flapping wings; 0 for none.
	 */
	static float partReference(float torsoLongest, float whole) {
		if (torsoLongest <= 0.01F || whole <= torsoLongest || torsoLongest > SMALL_TORSO || torsoLongest > 0.6F * whole) return torsoLongest;
		return Math.min(whole, 2F * torsoLongest);
	}

	/**
	 * A flat tail ({@code planar}, {@link #planar}) is not drawn under FIN_FLOOR of the main torso's longest side either, as a fin is: a
	 * tropical fish's tail on a person was a hairline at the hip. {@code scale} otherwise; never past {@code ceiling} or PARTS_MAX.
	 */
	static float tailFloor(float scale, float tailExtent, float torsoLongest, float ceiling, boolean planar) {
		if (!planar || tailExtent <= 0.01F || torsoLongest <= 0.01F) return scale;
		return Math.max(scale, Math.min(Math.min(FIN_FLOOR * torsoLongest / tailExtent, PARTS_MAX), ceiling));
	}

	/**
	 * {@link #tailFloor(float, float, float, float, boolean)} that does not undo the thickness limit of {@link #tailScale(float, float, float,
	 * float, float)}: the floor is held to TAIL_THICK_SHARE of the torso's thinner side {@code torsoThin} across the tail's thick side
	 * {@code tailWidth} (at scale 1), as the scale it is raised from already is. A flat tail nearly as wide as long on a long thin torso (a
	 * cod's, a turtle's shell) came out of the floor wider than the body it joins. A scale already past that stays as it is.
	 */
	static float tailFloor(float scale, float tailExtent, float tailWidth, float torsoLongest, float torsoThin, float ceiling, boolean planar) {
		if (!planar || tailExtent <= 0.01F || torsoLongest <= 0.01F) return scale;
		float floor = Math.min(Math.min(FIN_FLOOR * torsoLongest / tailExtent, PARTS_MAX), ceiling);
		if (tailWidth > 0.01F && torsoThin > 0.01F) floor = Math.min(floor, TAIL_THICK_SHARE * torsoThin / tailWidth);
		return Math.max(scale, floor);
	}

	/**
	 * A body's drawn size with the slime-style squish taken out. The renderers of the slimes and the sulfur cube put scale(w, 1/w, w) times the
	 * size into the pose, so the cube root of the volume of a core measured as drawn swings by w^(1/3) with every jump and landing (a 9 percent
	 * swing for a small slime), and a line it is compared with was crossed back and forth. The product of the x and y scales is the size squared
	 * for that pair and for a uniform scale alike: the size is its square root. Returns the factor that takes the cube root of the scaled volume
	 * (sx * sy * sz) to the size, 1 for a uniform scale.
	 */
	static float squishFree(float sx, float sy, float sz) {
		float volume = sx * sy * sz;
		if (volume <= 1e-12F || sx <= 0F || sy <= 0F) return 1F;
		return (float) (Math.sqrt(sx * (double) sy) / Math.cbrt(volume));
	}

	/**
	 * The factor that takes a depth (z) measured on a body as drawn to its depth without the slime-style squish ({@link #squishFree}): the
	 * renderer's z scale is w times the size, so the drawn depth is w times too long at the top of a jump and too short on landing. 1 for a uniform scale.
	 */
	static float squishFreeDepth(float sx, float sy, float sz) {
		if (sx <= 0F || sy <= 0F || sz <= 1e-6F) return 1F;
		return (float) (Math.sqrt(sx * (double) sy) / sz);
	}

	/**
	 * The carved pumpkin a snow golem wears, as the box {lo, hi} SnowGolemHeadLayer draws it in the head part's frame, in blocks (model
	 * axes, +y down, the head part's pivot at its origin): a block scaled to 0.625, its middle 0.34375 above the pivot. Ears borrowed for
	 * a golem stand on this, not on its 8 pixel head box, which the pumpkin swallows ({@code scale}: the body's size).
	 */
	static float[][] snowGolemPumpkin(float scale) {
		float half = 0.3125F * scale, centre = -0.34375F * scale;
		return new float[][] {{-half, centre - half, -half}, {half, centre + half, half}};
	}

	/**
	 * A silverfish's back plates ("layer0".."layer2": thin needles up the spine, 8 pixels tall beside the 4 pixel segments they stand
	 * on). They are drawn, but they are not its body (HybridMorph.collectCore, torso): the top of its box was the plate tips, and a lent
	 * head or creature sat there with sky between it and the segments (rig, 2026-11-19, all 42 silverfish pairs).
	 */
	static boolean backPlate(String partName) {
		return BACK_PLATE.matcher(partName).matches();
	}

	/** Compiled once: this runs for every child part of every measured body, every frame. */
	private static final java.util.regex.Pattern BACK_PLATE = java.util.regex.Pattern.compile("layer\\d+");

	/**
	 * A slime's model is only its inner 6 pixel cube; the surface that shows is the outer layer's 8 pixel cube (SlimeOuterLayer), whose
	 * top is one model pixel above the inner one's. A lent head sat on the inner top is that far inside the shell.
	 */
	static final float SLIME_SHELL_LIFT_PX = 1F;

	/** A body whose core is under this many blocks across (a silverfish's, an endermite's, a tadpole's) is too small for parts at their own size. */
	static final float TINY_MAIN = 0.4F;
	/**
	 * The same line for a body with no head part, whose core stands in for the head a lent head is held to: a bee's 0.48 was over TINY_MAIN
	 * and gave a llama's column 2 times the bee, a wither's head 2 times per side, a whole squid on its back and a donkey's ears a block
	 * long (rig, 2026-11-20). The headless mains in this band are the bee and the smaller slimes, so no settled pair moves. The bee's core
	 * measures 0.4927 (its 7 x 7 x 10 pixel body: the cube root of 490 pixels is 7.88), not 0.48, which left the line of 0.49 under it and
	 * the head cap at 1.5 (a wither's head twice the bee, a llama's column 2.7 times it, rig 2026-11-21). Under half a block, not at it: a
	 * full pufferfish's 8 pixel core is exactly 0.5 and is measured through yaw-dependent matrix products, so a line at 0.5 would flip it
	 * between tiny and not as it turns. Half way between the two (0.4965) clears both by more than the noise. The small sulfur cube's
	 * core is 0.4995, three thousandths over it, and its jump squish swings a core measured as drawn by up to 9 percent: the cores that
	 * are compared with this line are taken squish-free ({@link #squishFree}), so nothing on either side of it moves with the animation.
	 */
	static final float TINY_HEADLESS = 0.4965F;
	/** Lent wings, fins, rods and tails on such a body reach at most this many times its core in any direction. */
	static final float TINY_PARTS_REACH = 1.5F;
	/**
	 * ... and are not shrunk below this share of their own size so they stay something to look at, unless that would lift them past
	 * one and a half times the reach (7 blocks of dragon wings on a 0.1 block core come out at 3 percent of their size, not at the
	 * floor's 20: see {@link #tinyMainPartLimit}). A tail's own floor ({@link #tailScale}) is this share of its scale.
	 */
	static final float TINY_PARTS_FLOOR = 0.2F;

	/**
	 * The most a tiny body's lent appendages are scaled to: they are held to {@link #TINY_PARTS_REACH} times the body's core
	 * ({@code mainCore}, the side of the cube of its big blocks, in blocks as drawn) along their longest side ({@code partExtent},
	 * measured on all of them together at their own size). Torso-ratio scaling does not see this: a silverfish's torso is as big as a
	 * bat's, and a bat's wings still reach past the whole bug. {@link Float#MAX_VALUE}, no limit, for any body that is not tiny.
	 */
	static float tinyMainPartLimit(float partExtent, float mainCore) {
		return tinyMainPartLimit(partExtent, mainCore, TINY_MAIN);
	}

	/** {@link #tinyMainPartLimit(float, float)} with the line a body counts as tiny under: TINY_MAIN, or TINY_HEADLESS for a body with no head part. */
	static float tinyMainPartLimit(float partExtent, float mainCore, float line) {
		return tinyMainPartLimit(partExtent, mainCore, line, TINY_PARTS_FLOOR);
	}

	/**
	 * A flat fin's floor on a tiny body: half of its own size (a 0.38 block fin was 0.08 block on an allay, a pufferfish or a tadpole and
	 * nothing came over; rig, 2026-11-21), still never past one and a half times the reach.
	 */
	static final float TINY_FIN_FLOOR = 0.5F;

	/** {@link #tinyMainPartLimit(float, float, float)} with {@code floor} as the share of their own size they are kept at (TINY_PARTS_FLOOR, or TINY_FIN_FLOOR for a flat fin). */
	static float tinyMainPartLimit(float partExtent, float mainCore, float line, float floor) {
		if (partExtent <= 0.01F || mainCore <= 0.01F || mainCore >= line) return Float.MAX_VALUE;
		float reach = TINY_PARTS_REACH * mainCore / partExtent;
		// the floor keeps them something to look at, but never lifts them past one and a half times the reach: for a 7 block dragon on a
		// 0.31 block mite the floor of 0.2 made wings 3 times the whole animal (rig, 2026-11-20)
		return Math.max(reach, Math.min(floor, TINY_PARTS_REACH * reach));
	}

	/**
	 * The scale a lent tail is drawn at on a main whose torso is {@code torsoLongest} blocks at its longest side: {@code scale} (what the
	 * parts are scaled to) unless the tail, {@code tailExtent} long at scale 1, would be longer than that torso, then the scale that makes
	 * it as long, but never under TINY_PARTS_FLOOR of {@code scale}. A wither's 1.25 block tail slab was longer than an allay, a
	 * silverfish or a frog, and as thick as its height (rig, 2026-11-20, about 40 pairs); a tail that fits is left alone.
	 */
	static float tailScale(float scale, float tailExtent, float torsoLongest) {
		if (tailExtent <= 0.01F || torsoLongest <= 0.01F || tailExtent * scale <= torsoLongest) return scale;
		return Math.max(TINY_PARTS_FLOOR * scale, torsoLongest / tailExtent);
	}

	/** A lent tail is at most this share of the main torso's thinner side (its width or height) across. */
	static final float TAIL_THICK_SHARE = 0.8F;

	/**
	 * {@link #tailScale(float, float, float)} also held so the tail's thick side ({@code tailWidth}, the middle of its three sides at scale 1)
	 * is at most TAIL_THICK_SHARE of the main torso's thinner side {@code torsoThin}: a long thin torso passed the length test with a tail
	 * slab 1.8 times as wide as the cat (0.36 on 0.2) and as wide as a fish (rig, 2026-11-20). Never under TINY_PARTS_FLOOR of
	 * {@code scale}; the length rule is left as it is (whether a tail may be as long as the body is a call I have not made).
	 */
	static float tailScale(float scale, float tailExtent, float tailWidth, float torsoLongest, float torsoThin) {
		float s = tailScale(scale, tailExtent, torsoLongest);
		if (tailWidth <= 0.01F || torsoThin <= 0.01F || tailWidth * s <= TAIL_THICK_SHARE * torsoThin) return s;
		return Math.max(TINY_PARTS_FLOOR * scale, TAIL_THICK_SHARE * torsoThin / tailWidth);
	}

	/**
	 * A breeze's rods ring the torso instead of hiding in it. {@code dx}, {@code dz}: one rod's middle from the axis they turn round
	 * (the torso's middle), {@code hx}, {@code hz}: the torso's half sides, {@code margin}: how far outside it they stand. The offset
	 * moved outwards, along the line it already lies on, to the torso's box grown by the margin; a rod already outside it stays.
	 * Their pivot sits at the torso's middle and the rods, only 6 pixels from the axis, were inside any torso wider than a person's
	 * (rig, 2026-11-20).
	 */
	static float[] ringPush(float dx, float dz, float hx, float hz, float margin) {
		float r = (float) Math.hypot(dx, dz);
		if (r < 1.0E-4F) return new float[] {dx, dz};
		float ux = dx / r, uz = dz / r;
		float t = Float.MAX_VALUE;
		if (Math.abs(ux) > 1.0E-6F) t = Math.min(t, (hx + margin) / Math.abs(ux));
		if (Math.abs(uz) > 1.0E-6F) t = Math.min(t, (hz + margin) / Math.abs(uz));
		float out = Math.max(r, t);
		return new float[] {ux * out, uz * out};
	}

	/** Where a breeze's rods' middle is below the pivot they turn on, in blocks (their box runs 0.16 above it to 0.35 below: morph-heads.json), at scale 1. */
	static final float RODS_MIDDLE_BELOW = 0.0913F;

	/**
	 * How far an ear {@code height} tall is sunk into the head it stands on, given {@code setIn}, the least an ear reaches into it: a third of
	 * its height or {@code setIn}, whichever is more, as before, except that an ear under two pixels is sunk at most half its height
	 * instead of being buried to a flat square (rig, 2026-11-20, cat and ocelot ears of one pixel).
	 */
	static float earSink(float height, float setIn) {
		float third = height / 3F;
		return third >= setIn ? third : Math.min(setIn, height / 2F);
	}

	/**
	 * The turn that puts a lent head upright on a body without a head part whose renderer tips it over (a squid lying on land is
	 * pitched 90 degrees, and a head carried with it faced the floor with its top forward): {@code body} is the main body's frame as
	 * its renderer poses it (its yaw, any pitch, and the model's upside-down axes), {@code yaw} the turn that is its yaw alone (the
	 * renderer's 180 - bodyRot about the y axis). The result, applied in the body's frame, leaves a head in the frame the body would
	 * have with no pitch. The identity for a body whose only turns are its yaw and a pitch (the unit tests build those); it cancels any
	 * other turn of the frame just the same (a death fall, an upside-down flip, a frozen shake), so HybridMorph.headlessTurn does not
	 * call it for the first two.
	 */
	static org.joml.Matrix4f uprightFix(org.joml.Matrix4f body, org.joml.Matrix4f yaw) {
		org.joml.Matrix4f upright = new org.joml.Matrix4f(yaw).rotateZ((float) Math.PI); // the model's (-1, -1, 1) axes
		return new org.joml.Matrix4f(body).setTranslation(0F, 0F, 0F).invert().mul(upright);
	}

	/**
	 * How far above the torso the main head's box has to rise for a borrowed head to go on the shoulders (HybridMorph.place's
	 * {@code high}): three quarters of the borrowed head's height. On a body whose head is tilted up on its neck at rest ({@code tilted}:
	 * a horse's, donkey's, mule's) no more than half the main head's own height: its head and neck rise about as far as they are
	 * tall, and a tall borrowed head (a llama's column, a sniffer's, a warden's, a witch's with her hat) asked for more than they do
	 * and was set at the top of the hidden neck, a third of a block over the withers (rig, 2026-11-18). Other bodies keep the three
	 * quarters: a person's or a chicken's head rises exactly its own height, so the half would have put a tall head there on the
	 * shoulders and turned it about its middle, which I have not checked. A cow's or a sheep's head, which does not rise above its
	 * torso at all, still asks for more than it has.
	 */
	static float riseNeeded(float ownHeight, float grow, float mainHeadHeight, boolean tilted) {
		float full = 0.75F * ownHeight * grow;
		return tilted ? Math.min(full, 0.5F * mainHeadHeight) : full;
	}
}
