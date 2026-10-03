package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.builder.export.ExportSwaps;
import dev.kantobridge.builder.export.ExportTileset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * One map as the builder sees it (docs/PLAN.md §4.5 "Cell model and heights"): the tile under every block, and per
 * cell what is built there.
 *
 * <p>Swap sites are built in their {@code after} (open) state: story cells close them per player. Their cells' flags
 * are re-derived from the {@code after} block's tile classes, keeping the positional warp and sign bits.
 */
public final class MapModel {
	/** What a cell is built as. */
	public enum Kind {
		/** Walkable: a full tile block under the feet (ground, grass, flowers, floors, doors, mats, stairs). */
		GROUND,
		/** Water: a half-height tile block, top half a block below the feet. */
		WATER,
		/** A one-way ledge: ground-level art plus a ledge collider at feet level. */
		LEDGE,
		/** A counter, one block high. */
		COUNTER,
		/** Anything else: a column of tile blocks. */
		SOLID
	}

	private final ExportMap map;
	private final ExportTileset tileset;
	private final boolean interior;
	private final int cw;
	private final int ch;
	private final int tw;
	private final int th;
	private final int[] tiles;
	private final int[] flags;
	private final Kind[] kinds;
	private final int[] heights;
	private final Map<Integer, ExportSwaps.Site> sites = new HashMap<>();

	private MapModel(ExportMap map, ExportTileset tileset, boolean interior) {
		this.map = map;
		this.tileset = tileset;
		this.interior = interior;
		this.cw = map.cellWidth();
		this.ch = map.cellHeight();
		this.tw = this.cw * 2;
		this.th = this.ch * 2;
		this.tiles = new int[this.tw * this.th];
		this.flags = new int[this.cw * this.ch];
		this.kinds = new Kind[this.cw * this.ch];
		this.heights = new int[this.cw * this.ch];
	}

	/**
	 * @param swaps the export's swap sites on this map
	 */
	public static MapModel build(ExportMap map, ExportTileset tileset, List<ExportSwaps.Site> swaps, boolean interior, Warnings warnings) {
		MapModel model = new MapModel(map, tileset, interior);
		int[] blockIds = map.blocks().clone();
		for (ExportSwaps.Site site : swaps) {
			if (site.bx() >= map.width() || site.by() >= map.height()) {
				warnings.warn("swap_out_of_bounds");
				continue;
			}
			int index = site.by() * map.width() + site.bx();
			if (blockIds[index] != site.before()) {
				warnings.warn("swap_block_mismatch");
				continue;
			}
			blockIds[index] = site.after();
			model.sites.put(index, site);
		}
		int unknownBlocks = 0;
		for (int ty = 0; ty < model.th; ty++) {
			for (int tx = 0; tx < model.tw; tx++) {
				int block = blockIds[(ty / 4) * map.width() + tx / 4];
				int tile = tileset.tile(block, tx, ty);
				if (tile < 0) {
					unknownBlocks++;
					tile = 0;
				}
				model.tiles[ty * model.tw + tx] = tile;
			}
		}
		warnings.warn("block_out_of_tileset", unknownBlocks);

		for (int cy = 0; cy < model.ch; cy++) {
			for (int cx = 0; cx < model.cw; cx++) {
				int f = map.flags(cx, cy);
				if (model.sites.containsKey((cy / 2) * map.width() + cx / 2)) {
					f = model.derivedFlags(cx, cy) | (f & (CellFlags.WARP | CellFlags.SIGN));
				}
				int i = cy * model.cw + cx;
				model.flags[i] = f;
				model.kinds[i] = kindOf(f);
				model.heights[i] = switch (model.kinds[i]) {
					case COUNTER -> HeightRules.COUNTER;
					case SOLID -> interior ? HeightRules.INTERIOR_WALL : 0;
					default -> 0;
				};
			}
		}
		if (!interior) {
			model.measureSurfaceHeights();
		}
		return model;
	}

	static Kind kindOf(int flags) {
		if (CellFlags.has(flags, CellFlags.LEDGE)) {
			return Kind.LEDGE;
		}
		if (CellFlags.has(flags, CellFlags.WALKABLE)) {
			return Kind.GROUND;
		}
		if (CellFlags.has(flags, CellFlags.WATER)) {
			return Kind.WATER;
		}
		if (CellFlags.has(flags, CellFlags.COUNTER)) {
			return Kind.COUNTER;
		}
		return Kind.SOLID;
	}

	/** Flags of a swapped cell from its collision tile (the bottom-left tile, as the engine reads it). */
	private int derivedFlags(int cx, int cy) {
		int tile = this.tiles[(cy * 2 + 1) * this.tw + cx * 2];
		int f = 0;
		f |= this.tileset.walkable().contains(tile) ? CellFlags.WALKABLE : 0;
		f |= this.tileset.water().contains(tile) ? CellFlags.WATER : 0;
		f |= this.tileset.shore().contains(tile) ? CellFlags.SHORE : 0;
		f |= this.tileset.grass() != null && this.tileset.grass() == tile ? CellFlags.GRASS : 0;
		f |= this.tileset.door().contains(tile) ? CellFlags.DOOR : 0;
		f |= this.tileset.warp().contains(tile) ? CellFlags.WARP_TILE : 0;
		f |= this.tileset.counter().contains(tile) ? CellFlags.COUNTER : 0;
		f |= this.tileset.cutTree().contains(tile) ? CellFlags.CUT_TREE : 0;
		return f;
	}

	private void measureSurfaceHeights() {
		boolean[] solid = new boolean[this.tw * this.th];
		for (int ty = 0; ty < this.th; ty++) {
			for (int tx = 0; tx < this.tw; tx++) {
				solid[ty * this.tw + tx] = this.kinds[(ty / 2) * this.cw + tx / 2] == Kind.SOLID;
			}
		}
		int[] units = HeightRules.measure(this.tiles, this.tw, this.th, solid);
		for (int cy = 0; cy < this.ch; cy++) {
			for (int cx = 0; cx < this.cw; cx++) {
				int i = cy * this.cw + cx;
				if (this.kinds[i] != Kind.SOLID) {
					continue;
				}
				int unit = 0;
				for (int dy = 0; dy < 2; dy++) {
					for (int dx = 0; dx < 2; dx++) {
						unit = Math.max(unit, units[(cy * 2 + dy) * this.tw + cx * 2 + dx]);
					}
				}
				this.heights[i] = HeightRules.classify(unit);
			}
		}
	}

	public ExportMap map() {
		return this.map;
	}

	public ExportTileset tileset() {
		return this.tileset;
	}

	public String id() {
		return this.map.id();
	}

	public boolean interior() {
		return this.interior;
	}

	/** Width in cells. */
	public int cellWidth() {
		return this.cw;
	}

	/** Height in cells. */
	public int cellHeight() {
		return this.ch;
	}

	/** The tile shown by block (tx, ty) of the map (one tile per block). */
	public int tile(int tx, int ty) {
		return this.tiles[ty * this.tw + tx];
	}

	/** The tile the map's border block shows at tile position (tx, ty), which may lie outside the map. */
	public int borderTile(int tx, int ty) {
		int tile = this.tileset.tile(this.map.border(), Math.floorMod(tx, 4), Math.floorMod(ty, 4));
		return Math.max(tile, 0);
	}

	public Kind kind(int cx, int cy) {
		return this.kinds[cy * this.cw + cx];
	}

	/** Column height above the floor block for {@code SOLID} and {@code COUNTER} cells; 0 otherwise. */
	public int height(int cx, int cy) {
		return this.heights[cy * this.cw + cx];
	}

	/** The flags the cell is built from (re-derived on swap sites). */
	public int flags(int cx, int cy) {
		return this.flags[cy * this.cw + cx];
	}

	/** The swap site covering cell (cx, cy), or null. */
	public @Nullable ExportSwaps.Site site(int cx, int cy) {
		return this.sites.get((cy / 2) * this.map.width() + cx / 2);
	}

	/** The swap sites applied to this map. */
	public Map<Integer, ExportSwaps.Site> sites() {
		return this.sites;
	}
}
