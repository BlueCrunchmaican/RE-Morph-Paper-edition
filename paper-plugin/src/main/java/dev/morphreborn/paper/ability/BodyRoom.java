package dev.morphreborn.paper.ability;

/** The blocks a body of a given size takes up when its feet stand centred on a block; Bukkit-free so it can be tested. */
final class BodyRoom {
	private BodyRoom() {}

	/** How many blocks high (from the feet block up) the body needs clear. */
	static int blocksHigh(double height) {
		return Math.max(1, (int) Math.ceil(height - 1e-6));
	}

	/** The most blocks a fit check reads: a giant (5 x 5 x 12) fits, a dragon (17 x 17 x 8) is too big to ever find room, so it is not scanned. */
	static final int MAX_SCAN = 1024;

	/** How many blocks a body of this size takes up, which is what a fit check reads. */
	static int cells(double width, double height) {
		int[] span = span(width);
		long side = span[1] - span[0] + 1;
		return (int) Math.min(Integer.MAX_VALUE, side * side * blocksHigh(height));
	}

	/**
	 * The block offsets, either side of the centre block, the body's width reaches (a 0.6 wide player: 0 to 0, one column;
	 * a 4 wide giant: -2 to 2).
	 */
	static int[] span(double width) {
		int min = (int) Math.floor(0.5 - width / 2 + 1e-6);
		int max = (int) Math.floor(0.5 + width / 2 - 1e-6);
		return new int[] {Math.min(min, 0), Math.max(max, 0)};
	}
}
