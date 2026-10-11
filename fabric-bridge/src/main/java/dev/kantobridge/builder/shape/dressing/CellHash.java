package dev.kantobridge.builder.shape.dressing;

/**
 * The dressing's position hash (docs/specs/CAVES_AND_WOODS_PLAN.md §3): the common fract-of-sine mixer that Kanto in
 * First Person places its cave rock, canopy and vines with, so a cell gets the piece it gets there. Inputs are a cell's
 * coordinates in its map (negative in a ring) and a per-decision salt. {@link StrictMath#sin} gives the same bits on
 * every JVM, so the server and every client plan the same pieces (I7); the expression runs in the mod's order.
 */
public final class CellHash {
	private CellHash() {
	}

	/** A value in [0, 1) for (a, b) and salt c. */
	public static double hash01(int a, int b, int c) {
		double x = a * 127.1 + b * 311.7 + c * 74.7;
		double s = StrictMath.sin(x) * 43758.5453123;
		return s - Math.floor(s);
	}

	/** True for about one (a, b) in {@code n}: {@code floor(hash01 · n) == 0}. */
	public static boolean oneIn(int a, int b, int c, int n) {
		return (int) Math.floor(hash01(a, b, c) * n) == 0;
	}
}
