package dev.kantobridge.builder.plan;

import dev.kantobridge.block.EdgeWallBlock;
import dev.kantobridge.builder.export.ExportField;
import dev.kantobridge.world.Scale;

/**
 * The tile-pair edges of one map (docs/specs/CAVES_3D_PLAN.md §3.2): between two neighbouring cells whose collision
 * tiles (each cell's bottom-left tile, as the engine reads it) form one of the tileset's land pairs, both of them walked
 * on, or water pairs, one of them water, the engine refuses the step, so an invisible wall stands there. Each cell walls
 * its own side of such an edge, so the edge stays closed even where one of the two has no room for a wall. Pure Java.
 */
public final class PairEdges {
	public static final PairEdges NONE = new PairEdges(0, 0, new byte[0], 0);

	private final int w;
	private final int h;
	/** Per cell, row by row: its walled sides ({@link EdgeWallBlock#SIDE_NORTH} …). */
	private final byte[] sides;
	private final int edges;

	private PairEdges(int w, int h, byte[] sides, int edges) {
		this.w = w;
		this.h = h;
		this.sides = sides;
		this.edges = edges;
	}

	/** The pair edges of a map, or {@link #NONE} when its tileset has no pairs. */
	public static PairEdges of(MapModel model, ExportField.TilePairs pairs) {
		String tileset = model.tileset().id();
		if (!pairs.any(tileset)) {
			return NONE;
		}
		int w = model.cellWidth();
		int h = model.cellHeight();
		byte[] sides = new byte[w * h];
		int edges = 0;
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				if (cx + 1 < w && blocked(model, pairs, tileset, cx, cy, cx + 1, cy)) {
					sides[cy * w + cx] |= EdgeWallBlock.SIDE_EAST;
					sides[cy * w + cx + 1] |= EdgeWallBlock.SIDE_WEST;
					edges++;
				}
				if (cy + 1 < h && blocked(model, pairs, tileset, cx, cy, cx, cy + 1)) {
					sides[cy * w + cx] |= EdgeWallBlock.SIDE_SOUTH;
					sides[(cy + 1) * w + cx] |= EdgeWallBlock.SIDE_NORTH;
					edges++;
				}
			}
		}
		return edges == 0 ? NONE : new PairEdges(w, h, sides, edges);
	}

	/** True when the engine refuses a step between cells (ax, ay) and (bx, by) of the map. */
	static boolean blocked(MapModel model, ExportField.TilePairs pairs, String tileset, int ax, int ay, int bx, int by) {
		MapModel.Kind a = model.kind(ax, ay);
		MapModel.Kind b = model.kind(bx, by);
		if (!standable(a) || !standable(b)) {
			return false;
		}
		int ta = collisionTile(model, ax, ay);
		int tb = collisionTile(model, bx, by);
		if (a != MapModel.Kind.WATER && b != MapModel.Kind.WATER) {
			return pairs.land(tileset, ta, tb);
		}
		return pairs.water(tileset, ta, tb);
	}

	private static boolean standable(MapModel.Kind kind) {
		return kind == MapModel.Kind.GROUND || kind == MapModel.Kind.LEDGE || kind == MapModel.Kind.WATER;
	}

	/** A cell's collision tile: its bottom-left 8×8 tile, the one the engine reads. */
	static int collisionTile(MapModel model, int cx, int cy) {
		return model.tile(cx * Scale.CELL_BLOCKS, cy * Scale.CELL_BLOCKS + 1);
	}

	/** Pair edges in the map. */
	public int edges() {
		return this.edges;
	}

	/** A cell's walled sides, 0 outside the map. */
	public int sides(int cx, int cy) {
		if (cx < 0 || cy < 0 || cx >= this.w || cy >= this.h) {
			return 0;
		}
		return this.sides[cy * this.w + cx];
	}

	/**
	 * The walled sides of one of a cell's four blocks ({@code bx, by} 0 or 1 within the cell): the cell's sides that this
	 * block lies along.
	 */
	public int blockSides(int cx, int cy, int bx, int by) {
		int touches = (by == 0 ? EdgeWallBlock.SIDE_NORTH : EdgeWallBlock.SIDE_SOUTH) | (bx == 0 ? EdgeWallBlock.SIDE_WEST : EdgeWallBlock.SIDE_EAST);
		return this.sides(cx, cy) & touches;
	}
}
