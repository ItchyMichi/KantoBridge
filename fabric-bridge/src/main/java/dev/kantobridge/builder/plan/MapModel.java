package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.builder.export.ExportSwaps;
import dev.kantobridge.builder.export.ExportTileset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import org.jetbrains.annotations.Nullable;

/**
 * One map as the builder sees it (docs/PLAN.md §4.5 "Cell model and heights"): the tile under every block, and per
 * cell what is built there.
 *
 * <p><b>Swap sites</b> (Cut trees, card-key doors, scripted swaps) are built open: each cell a site changes is built in
 * whichever of its two states is walkable (normally the site's {@code after}; a cell whose {@code after} is the solid
 * one is built {@code before}), and story cells close it per player. Its flags are re-derived from the tiles it is
 * built with, keeping the positional warp and sign bits. A site is accepted when the map data holds either of its
 * blocks: {@code before} (the data shows it closed, like a Cut tree) or {@code after} (the engine stamps it closed on
 * map load, like the card-key doors). A {@code script} site whose data holds neither is accepted too: its script writes
 * the block on every map entry, so the data's own block there never shows in play (Agatha's exit seal; noted
 * {@code swap_site_overwritten}, and every cell of its block takes its flags from what it is built with). Any other
 * mismatch is a {@code swap_block_mismatch}. {@link #closedModel()} is the same map with every changing cell in its
 * other, closed state: the art and height the story-cell renderer draws.
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
	private final boolean closedState;
	private final int cw;
	private final int ch;
	private final int tw;
	private final int th;
	private final int[] tiles;
	private final int[] flags;
	private final Kind[] kinds;
	private final int[] heights;
	/** Valid sites by Gen 1 block index ({@code by * width + bx}). */
	private final Map<Integer, ExportSwaps.Site> sites;
	/** Per cell of a site: the block it is built with, and the block that closes it (equal when the cell never changes). */
	private final int[] openBlock;
	private final int[] closedBlock;
	/** Cells of a site whose map data holds neither of its blocks: the data's flags don't describe what is built there. */
	private final boolean[] overwritten;
	private @Nullable MapModel closed;

	private MapModel(ExportMap map, ExportTileset tileset, boolean interior, boolean closedState, Map<Integer, ExportSwaps.Site> sites,
			int[] openBlock, int[] closedBlock, boolean[] overwritten) {
		this.map = map;
		this.tileset = tileset;
		this.interior = interior;
		this.closedState = closedState;
		this.cw = map.cellWidth();
		this.ch = map.cellHeight();
		this.tw = this.cw * 2;
		this.th = this.ch * 2;
		this.tiles = new int[this.tw * this.th];
		this.flags = new int[this.cw * this.ch];
		this.kinds = new Kind[this.cw * this.ch];
		this.heights = new int[this.cw * this.ch];
		this.sites = sites;
		this.openBlock = openBlock;
		this.closedBlock = closedBlock;
		this.overwritten = overwritten;
	}

	/**
	 * @param swaps the export's swap sites on this map
	 */
	public static MapModel build(ExportMap map, ExportTileset tileset, List<ExportSwaps.Site> swaps, boolean interior, Warnings warnings) {
		return build(map, tileset, swaps, interior, warnings, site -> -1);
	}

	/**
	 * @param swaps   the export's swap sites on this map
	 * @param numbers each site's position in the export's site list (for diagnostics), or -1
	 */
	public static MapModel build(ExportMap map, ExportTileset tileset, List<ExportSwaps.Site> swaps, boolean interior, Warnings warnings,
			ToIntFunction<ExportSwaps.Site> numbers) {
		Map<Integer, ExportSwaps.Site> valid = new HashMap<>();
		Set<Integer> overwrittenSites = new HashSet<>();
		for (ExportSwaps.Site site : swaps) {
			int number = numbers.applyAsInt(site);
			Map<String, String> detail = Warnings.symbols("map", map.id(), "kind", site.kind(), "source", site.source(), "site",
					number >= 0 ? Integer.toString(number) : null);
			if (site.bx() >= map.width() || site.by() >= map.height()) {
				warnings.warn("swap_out_of_bounds", detail);
				continue;
			}
			if (site.before() == site.after()) {
				warnings.note("swap_site_unchanged", detail);
				continue;
			}
			int index = site.by() * map.width() + site.bx();
			int data = map.blocks()[index];
			boolean neither = data != site.before() && data != site.after();
			if (neither && !overwritable(site)) {
				warnings.warn("swap_block_mismatch", detail);
				continue;
			}
			if (valid.containsKey(index)) {
				warnings.warn("swap_site_duplicate", detail);
				continue;
			}
			if (neither) {
				// Its script writes one of the two blocks on every map entry; the data's own block is never seen.
				warnings.note("swap_site_overwritten", detail);
				overwrittenSites.add(index);
			} else if (data == site.after()) {
				// The map data shows the site open; the engine closes it on map load until its event is set.
				warnings.note("swap_site_stamped", detail);
			}
			valid.put(index, site);
		}
		int cells = map.cellWidth() * map.cellHeight();
		int[] openBlock = new int[cells];
		int[] closedBlock = new int[cells];
		boolean[] overwritten = new boolean[cells];
		Arrays.fill(openBlock, -1);
		Arrays.fill(closedBlock, -1);
		for (Map.Entry<Integer, ExportSwaps.Site> entry : valid.entrySet()) {
			ExportSwaps.Site site = entry.getValue();
			for (int dy = 0; dy < 2; dy++) {
				for (int dx = 0; dx < 2; dx++) {
					int i = (site.by() * 2 + dy) * map.cellWidth() + site.bx() * 2 + dx;
					int open = openBlock(tileset, site, dx, dy);
					openBlock[i] = open;
					closedBlock[i] = changes(tileset, site, dx, dy) ? (open == site.after() ? site.before() : site.after()) : open;
					overwritten[i] = overwrittenSites.contains(entry.getKey());
				}
			}
		}
		MapModel model = new MapModel(map, tileset, interior, false, Collections.unmodifiableMap(valid), openBlock, closedBlock, overwritten);
		model.fill(warnings);
		return model;
	}

	/**
	 * True for a site the engine writes itself whatever the map data holds: a scripted swap (its script sets the block on
	 * entry). Cut trees, card-key doors and boulder barriers must match the data, or the export and the engine disagree.
	 */
	static boolean overwritable(ExportSwaps.Site site) {
		return "script".equals(site.kind());
	}

	/**
	 * The state cell ({@code dx}, {@code dy}) of a site's block is built in: {@code after} unless only {@code before} is
	 * walkable there.
	 */
	static int openBlock(ExportTileset tileset, ExportSwaps.Site site, int dx, int dy) {
		boolean before = tileset.walkable().contains(tileset.tile(site.before(), dx * 2, dy * 2 + 1));
		boolean after = tileset.walkable().contains(tileset.tile(site.after(), dx * 2, dy * 2 + 1));
		return after || !before ? site.after() : site.before();
	}

	/** True when a site changes cell ({@code dx}, {@code dy}) of its block: its art or its passability differs. */
	static boolean changes(ExportTileset tileset, ExportSwaps.Site site, int dx, int dy) {
		for (int ty = dy * 2; ty < dy * 2 + 2; ty++) {
			for (int tx = dx * 2; tx < dx * 2 + 2; tx++) {
				if (tileset.tile(site.before(), tx, ty) != tileset.tile(site.after(), tx, ty)) {
					return true;
				}
			}
		}
		int before = tileset.tile(site.before(), dx * 2, dy * 2 + 1);
		int after = tileset.tile(site.after(), dx * 2, dy * 2 + 1);
		return tileset.walkable().contains(before) != tileset.walkable().contains(after);
	}

	private void fill(Warnings warnings) {
		int[] blockIds = this.map.blocks();
		int unknownBlocks = 0;
		for (int ty = 0; ty < this.th; ty++) {
			for (int tx = 0; tx < this.tw; tx++) {
				int block = blockIds[(ty / 4) * this.map.width() + tx / 4];
				int cell = (ty / 2) * this.cw + tx / 2;
				if (this.openBlock[cell] >= 0) {
					block = this.closedState ? this.closedBlock[cell] : this.openBlock[cell];
				}
				int tile = this.tileset.tile(block, tx, ty);
				if (tile < 0) {
					unknownBlocks++;
					tile = 0;
				}
				this.tiles[ty * this.tw + tx] = tile;
			}
		}
		warnings.warn("block_out_of_tileset", unknownBlocks);

		for (int cy = 0; cy < this.ch; cy++) {
			for (int cx = 0; cx < this.cw; cx++) {
				int i = cy * this.cw + cx;
				int f = this.map.flags(cx, cy);
				int shown = this.closedState ? this.closedBlock[i] : this.openBlock[i];
				boolean rederive = this.openBlock[i] != this.closedBlock[i] || this.overwritten[i];
				if (rederive && shown != this.map.block(cx / 2, cy / 2)) {
					// Built (or drawn) in another state than the map data: its flags follow the tiles it shows.
					f = this.derivedFlags(cx, cy) | (f & (CellFlags.WARP | CellFlags.SIGN));
				}
				this.flags[i] = f;
				this.kinds[i] = kindOf(f);
				this.heights[i] = switch (this.kinds[i]) {
					case COUNTER -> HeightRules.COUNTER;
					case SOLID -> this.interior ? HeightRules.INTERIOR_WALL : 0;
					default -> 0;
				};
			}
		}
		if (!this.interior) {
			this.measureSurfaceHeights();
		}
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

	/**
	 * The tile the border the game really draws around the map ({@code borderDrawn}) shows at tile position (tx, ty); the
	 * map's own border block where it draws none, or where an older export doesn't say.
	 */
	public int drawnBorderTile(int tx, int ty) {
		Integer drawn = this.map.borderDrawn();
		int tile = this.tileset.tile(drawn == null ? this.map.border() : drawn, Math.floorMod(tx, 4), Math.floorMod(ty, 4));
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

	/** The swap sites applied to this map, by Gen 1 block index. */
	public Map<Integer, ExportSwaps.Site> sites() {
		return this.sites;
	}

	/** True when cell (cx, cy) belongs to a swap site that changes it (its art or its passability). */
	public boolean changes(int cx, int cy) {
		int i = cy * this.cw + cx;
		return this.openBlock[i] >= 0 && this.openBlock[i] != this.closedBlock[i];
	}

	/** The block whose presence at the cell's site closes the cell; -1 for cells no site changes. */
	public int closedBlock(int cx, int cy) {
		return this.changes(cx, cy) ? this.closedBlock[cy * this.cw + cx] : -1;
	}

	/** The block the map data has at the cell's Gen 1 block. */
	public int dataBlock(int cx, int cy) {
		return this.map.block(cx / 2, cy / 2);
	}

	/** True for the closed variant ({@link #closedModel()}). */
	public boolean closedState() {
		return this.closedState;
	}

	/**
	 * The same map with every changing site cell in its closed state: what a story cell draws while closed. Built on
	 * first use; anomalies were already counted for this model, so none are counted again.
	 */
	public MapModel closedModel() {
		if (this.closedState) {
			return this;
		}
		if (this.closed == null) {
			MapModel model = new MapModel(this.map, this.tileset, this.interior, true, this.sites, this.openBlock, this.closedBlock,
					this.overwritten);
			model.fill(new Warnings());
			this.closed = model;
		}
		return this.closed;
	}
}
