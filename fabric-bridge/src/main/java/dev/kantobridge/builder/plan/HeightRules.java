package dev.kantobridge.builder.plan;

/**
 * Column heights for solid cells (docs/PLAN.md §4.5 "Cell model and heights"): walls and trees 2 blocks, roofs and
 * cliffs 4, void fill 3, counters 1, interior walls 2.
 *
 * <p>Outdoors, which solids are "tall" is measured from the drawing, re-implementing the rule Dramaless Shape
 * documents for its volumes (refs/_research/D_voxel_pokered.md, "Volume heights"; no code was copied): along each
 * 8-px tile column, a vertical run of solid tiles whose art repeats (a tree line, a fence) has a drawn unit of 2 rows,
 * while art that doesn't repeat (a roof over a facade, a cliff face) is as tall as its run, up to 6 rows. One tile row
 * is 8 px, which is one block at our scale. Runs are split where a repeating stretch of at least two cells starts or
 * ends, so a tree line touching a roof stays low. The MVP then rounds the measured unit to the two classes: up to 2
 * rows is a wall, more is a roof.
 */
public final class HeightRules {
	/** Walls, fences, trees, signs. */
	public static final int WALL = 2;
	/** Roofs, building facades, cliffs. */
	public static final int ROOF = 4;
	/** Tree-wall fill in the gaps between surface maps. */
	public static final int VOID = 3;
	/** Counters stay low so clerks remain clickable. */
	public static final int COUNTER = 1;
	/** Interior walls. */
	public static final int INTERIOR_WALL = 2;

	/** The tallest drawn unit, in tile rows (48 px). */
	public static final int MAX_UNIT = 6;
	/** A repeating stretch must cover at least two cells (4 tile rows) to split a run. */
	public static final int MIN_REPEAT_ROWS = 4;

	private HeightRules() {
	}

	/**
	 * The drawn unit (in tile rows) of every solid tile; 0 for tiles that aren't solid.
	 *
	 * @param tiles row-major tile ids, {@code tw × th}
	 * @param solid row-major, true where the tile belongs to a solid cell
	 */
	public static int[] measure(int[] tiles, int tw, int th, boolean[] solid) {
		int[] units = new int[tw * th];
		int[] run = new int[th];
		for (int tx = 0; tx < tw; tx++) {
			int ty = 0;
			while (ty < th) {
				if (!solid[ty * tw + tx]) {
					ty++;
					continue;
				}
				int start = ty;
				int n = 0;
				while (ty < th && solid[ty * tw + tx]) {
					run[n++] = tiles[ty * tw + tx];
					ty++;
				}
				int[] runUnits = measureRun(run, n);
				for (int i = 0; i < n; i++) {
					units[(start + i) * tw + tx] = runUnits[i];
				}
			}
		}
		return units;
	}

	/** Units for the first {@code n} rows of one vertical run of solid tiles. */
	static int[] measureRun(int[] seq, int n) {
		int[] units = new int[n];
		int i = 0;
		while (i < n) {
			int repeat = repeatLength(seq, n, i);
			if (repeat >= MIN_REPEAT_ROWS) {
				for (int k = i; k < i + repeat; k++) {
					units[k] = WALL;
				}
				i += repeat;
				continue;
			}
			int j = i + 1;
			while (j < n && repeatLength(seq, n, j) < MIN_REPEAT_ROWS) {
				j++;
			}
			int unit = Math.min(j - i, MAX_UNIT);
			for (int k = i; k < j; k++) {
				units[k] = unit;
			}
			i = j;
		}
		return units;
	}

	/** The length of the longest stretch starting at {@code i} that repeats with a period of 1 or 2 rows. */
	static int repeatLength(int[] seq, int n, int i) {
		int one = 1;
		while (i + one < n && seq[i + one] == seq[i + one - 1]) {
			one++;
		}
		int two = Math.min(2, n - i);
		while (i + two < n && seq[i + two] == seq[i + two - 2]) {
			two++;
		}
		// A period-2 stretch of exactly two rows is just two different tiles, not a repeat.
		return Math.max(one, two > 2 ? two : 1);
	}

	/** The MVP height class of a measured unit. */
	public static int classify(int unit) {
		return unit >= 3 ? ROOF : WALL;
	}
}
