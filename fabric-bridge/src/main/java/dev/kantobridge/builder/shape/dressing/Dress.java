package dev.kantobridge.builder.shape.dressing;

import dev.kantobridge.builder.shape.ShapeKey;
import dev.kantobridge.builder.shape.Structures;
import dev.kantobridge.scene.SceneMap;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * One map's columns while the dressing rules run, and what they decide for the builder: the room's wall tile (the ring
 * and the ceiling wear it) and the ceiling's key.
 */
public final class Dress {
	final int atlas;
	final ShapeKey[][] keys;
	final int[] blocks;
	final int[] mask;
	final List<Structures.MeasuredRun> runs;
	int wallTile = -1;
	@Nullable ShapeKey ceiling;

	/**
	 * @param keys   per art tile, its column's keys (changed in place)
	 * @param blocks per art tile, its column's blocks (changed in place)
	 * @param mask   per art tile, its {@code shape_full} blocks
	 * @param runs   the map's measured runs
	 */
	public Dress(int atlas, ShapeKey[][] keys, int[] blocks, int[] mask, List<Structures.MeasuredRun> runs) {
		this.atlas = atlas;
		this.keys = keys;
		this.blocks = blocks;
		this.mask = mask;
		this.runs = runs;
	}

	/** The room's dominant wall tile (the ring and the ceiling wear it), -1 when not dressed. */
	public int wallTile() {
		return this.wallTile;
	}

	/** The ceiling's key, null when not dressed. */
	public @Nullable ShapeKey ceiling() {
		return this.ceiling;
	}

	/** True for a wall column: a run along the map's north, west or east edge, not taken by a cell unit. */
	boolean wall(SceneMap map, Structures.MeasuredRun run) {
		if (run.n() != 0 && run.tx() != 0 && run.tx() != map.artCols() - 1) {
			return false;
		}
		ShapeKey[] column = this.keys[run.n() * map.artCols() + run.tx()];
		return column == null || column.length == 0 || column[0] == null || column[0].kind() != ShapeKey.Kind.CELL;
	}
}
