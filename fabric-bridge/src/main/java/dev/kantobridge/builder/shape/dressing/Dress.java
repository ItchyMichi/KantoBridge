package dev.kantobridge.builder.shape.dressing;

import dev.kantobridge.builder.shape.ShapeKey;
import dev.kantobridge.builder.shape.Structures;
import dev.kantobridge.scene.SceneMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * One map's columns while the dressing rules run, and what they decide for the builder: the room's wall tile (the ring
 * and the ceiling wear it), the ceiling's key, and the hangings (docs/specs/CAVES_AND_WOODS_PLAN.md §3): decoration
 * pieces by level from feet, each on a decor carrier the builder adds where the column leaves the level free. Hangings
 * are never structure: they stay out of the columns' keys, so the self-check and collision never see them.
 */
public final class Dress {
	final int atlas;
	final ShapeKey[][] keys;
	final int[] blocks;
	final int[] mask;
	final List<Structures.MeasuredRun> runs;
	final int[] decor;
	final ShapeKey[][] hung;
	final int[] hungMask;
	final Map<String, Integer> tally;
	int wallTile = -1;
	@Nullable ShapeKey ceiling;

	/**
	 * @param keys   per art tile, its column's keys (changed in place)
	 * @param blocks per art tile, its column's blocks (changed in place)
	 * @param mask   per art tile, its {@code shape_full} blocks
	 * @param runs   the map's measured runs
	 */
	public Dress(int atlas, ShapeKey[][] keys, int[] blocks, int[] mask, List<Structures.MeasuredRun> runs) {
		this(atlas, keys, blocks, mask, runs, new int[blocks.length], new TreeMap<>());
	}

	/**
	 * @param decor per art tile, the levels its decor carriers take (tufts, doorways), which hangings leave alone
	 * @param tally the planner's counts; the hangings add theirs as {@code dress_<name>}
	 */
	public Dress(int atlas, ShapeKey[][] keys, int[] blocks, int[] mask, List<Structures.MeasuredRun> runs, int[] decor,
			Map<String, Integer> tally) {
		this.atlas = atlas;
		this.keys = keys;
		this.blocks = blocks;
		this.mask = mask;
		this.runs = runs;
		this.decor = decor;
		this.hung = new ShapeKey[blocks.length][];
		this.hungMask = new int[blocks.length];
		this.tally = tally;
	}

	/** The room's dominant wall tile (the ring and the ceiling wear it), -1 when not dressed. */
	public int wallTile() {
		return this.wallTile;
	}

	/** The ceiling's key, null when not dressed. */
	public @Nullable ShapeKey ceiling() {
		return this.ceiling;
	}

	/** Per art tile, its hangings' keys by level from feet (null where none). */
	public ShapeKey[][] hung() {
		return this.hung;
	}

	/** Per art tile, bit k set when a hanging takes level k (a decor carrier at feet + k). */
	public int[] hungMask() {
		return this.hungMask;
	}

	/** True for a wall column: a run along the map's north, west or east edge, not taken by a cell unit. */
	boolean wall(SceneMap map, Structures.MeasuredRun run) {
		if (run.n() != 0 && run.tx() != 0 && run.tx() != map.artCols() - 1) {
			return false;
		}
		ShapeKey[] column = this.keys[run.n() * map.artCols() + run.tx()];
		return column == null || column.length == 0 || column[0] == null || column[0].kind() != ShapeKey.Kind.CELL;
	}

	/**
	 * True when a hanging may take level {@code k} of art tile {@code i}: above the feet level (a story block's, a ledge's,
	 * a tuft's), above the column's blocks, and not a level a decor carrier or another hanging already takes.
	 */
	boolean free(int i, int k) {
		return k >= 1 && k < 31 && k >= this.blocks[i] && (this.decor[i] >> k & 1) == 0 && (this.hungMask[i] >> k & 1) == 0;
	}

	/** Hangs {@code key} at level {@code k} of art tile {@code i} (which must be {@link #free}). */
	void hang(int i, int k, ShapeKey key) {
		ShapeKey[] column = this.hung[i];
		if (column == null || column.length <= k) {
			column = column == null ? new ShapeKey[k + 1] : Arrays.copyOf(column, k + 1);
			this.hung[i] = column;
		}
		column[k] = key;
		this.hungMask[i] |= 1 << k;
	}

	/** Counts one {@code name} in the planner's tally, as {@code dress_<name>}. */
	void count(String name) {
		this.tally.merge("dress_" + name, 1, Integer::sum);
	}
}
