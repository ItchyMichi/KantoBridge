package dev.kantobridge.routes.gen;

/**
 * The 16 shapes a block of an area can take, named by their exit mask ({@link Dir#bit}): 0 is a closed block (no
 * exits, not part of the maze), 15 a crossing. DWM's floor generator picks its pieces by number of exits; this is our
 * own version of that idea (docs/specs/WILD_ROUTES_PLAN.md §4.3): every exit count gets a share of the weight, split
 * evenly over the shapes of that count the neighbours still allow, so more exits are likelier and the maze branches.
 */
final class BlockShapes {
	/** A closed block. */
	static final int CLOSED = 0;
	/** Weight per exit count (0–4 exits), shared by the allowed shapes with that count. */
	static final int[] EXIT_WEIGHT = {0, 20, 45, 60, 75};
	/** Splits a share evenly over 1–6 shapes without rounding (lcm of 1..6). */
	private static final int SPLIT = 60;

	private BlockShapes() {
	}

	static int exits(int mask) {
		return Integer.bitCount(mask & 0xF);
	}

	/**
	 * A shape with every {@code required} exit and none of the {@code forbidden} ones, picked by weight. With
	 * nothing required and every exit forbidden, the block is closed.
	 */
	static int pick(RouteRng rng, int required, int forbidden) {
		int[] count = new int[5];
		for (int mask = 0; mask < 16; mask++) {
			if (allowed(mask, required, forbidden)) {
				count[exits(mask)]++;
			}
		}
		int total = 0;
		for (int mask = 0; mask < 16; mask++) {
			if (allowed(mask, required, forbidden)) {
				total += weight(mask, count);
			}
		}
		if (total == 0) {
			return required;
		}
		int r = rng.nextInt(total);
		for (int mask = 0; mask < 16; mask++) {
			if (allowed(mask, required, forbidden)) {
				r -= weight(mask, count);
				if (r < 0) {
					return mask;
				}
			}
		}
		throw new IllegalStateException("weights don't add up");
	}

	private static boolean allowed(int mask, int required, int forbidden) {
		return (mask & forbidden) == 0 && (mask & required) == required;
	}

	private static int weight(int mask, int[] count) {
		int n = exits(mask);
		return n == 0 ? 0 : EXIT_WEIGHT[n] * SPLIT / count[n];
	}
}
