package dev.kantobridge.scene;

import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * One map in the neutral scene (docs/specs/VOXEL_BLOCKS_PLAN.md §5.3): the art tile under every block column, per cell
 * its kind, tags and direction, its border art, and its swap sites with both states. Coordinates: art tiles
 * ({@code tx, ty}, one per block column), cells ({@code cx, cy}, {@code perCell × perCell} art tiles); rows grow
 * southward.
 *
 * @param index       the game's map number
 * @param tileset     the game's tileset id (for front-end rules)
 * @param atlas       the atlas the art tiles index
 * @param perCell     art tiles along a cell's edge (the scene's {@code cellPx / artPx})
 * @param surface     part of the outdoor surface layout (otherwise an interior, placed under its door)
 * @param tiles       {@code artCols × artRows} tile ids, row-major, with every swap site open (as built)
 * @param closedTiles the same with every swap site closed, or null when no site changes this map
 * @param kinds       per cell, {@link CellKind#id()}
 * @param tags        per cell, a {@link CellTag} bit set
 * @param dirs        per cell, a direction 0..3 (ledges: the hop; 0 down, 1 up, 2 left, 3 right), 255 for none
 * @param border      the art tiles of the border the game draws around the map (a square, row-major, repeated), or null when
 *                    it draws none
 * @param ownBorder   the art tiles of the map's own border (today's void fill and interior rings), a square like {@code border}
 */
public record SceneMap(String id, int index, String tileset, String atlas, String palette, int w, int h, int perCell, boolean surface,
		Env env, int[] tiles, int @Nullable [] closedTiles, byte[] kinds, int[] tags, byte[] dirs, int @Nullable [] border, int[] ownBorder,
		List<SceneObject> objects, List<SwapSite> swapSites) {
	public static final int NO_DIR = 255;

	public SceneMap {
		Objects.requireNonNull(id, "id");
		if (perCell < 1 || tiles.length != perCell * perCell * w * h || (closedTiles != null && closedTiles.length != tiles.length)) {
			throw new IllegalArgumentException("art size is not (w × perCell) × (h × perCell)");
		}
		if (kinds.length != w * h || tags.length != w * h || dirs.length != w * h) {
			throw new IllegalArgumentException("cell arrays are not w × h");
		}
		int side = side(ownBorder.length);
		if (side < 1 || (border != null && border.length != ownBorder.length)) {
			throw new IllegalArgumentException("a border is a square of tiles, both borders the same size");
		}
		for (SwapSite site : swapSites) {
			for (SwapSite.SwapCell cell : site.cells()) {
				if (cell.closed().art().length != perCell * perCell || cell.open().art().length != perCell * perCell) {
					throw new IllegalArgumentException("a swap cell's art is perCell × perCell tiles");
				}
			}
		}
		objects = List.copyOf(objects);
		swapSites = List.copyOf(swapSites);
	}

	/** The side of a square of {@code n} tiles, or 0 when {@code n} is not a square. */
	private static int side(int n) {
		int side = (int) Math.round(Math.sqrt(n));
		return side * side == n ? side : 0;
	}

	/** Art tile columns. */
	public int artCols() {
		return this.w * this.perCell;
	}

	/** Art tile rows. */
	public int artRows() {
		return this.h * this.perCell;
	}

	/** The border's side, in art tiles. */
	public int borderSize() {
		return side(this.ownBorder.length);
	}

	/** The kind of the cell holding art tile (tx, ty). */
	public CellKind kindAtTile(int tx, int ty) {
		return this.kind(tx / this.perCell, ty / this.perCell);
	}

	/** The index of the cell holding art tile (tx, ty) in the per-cell arrays. */
	public int cellIndexOfTile(int tx, int ty) {
		return (ty / this.perCell) * this.w + tx / this.perCell;
	}

	/** The tile at art position (tx, ty) as built (every site open). */
	public int tile(int tx, int ty) {
		return this.tiles[ty * this.artCols() + tx];
	}

	/** The tile at art position (tx, ty) with every site closed. */
	public int closedTile(int tx, int ty) {
		return this.closedTiles == null ? this.tile(tx, ty) : this.closedTiles[ty * this.artCols() + tx];
	}

	public CellKind kind(int cx, int cy) {
		return CellKind.byId(this.kinds[cy * this.w + cx]);
	}

	public int tags(int cx, int cy) {
		return this.tags[cy * this.w + cx];
	}

	public boolean hasTag(int cx, int cy, CellTag tag) {
		return CellTag.has(this.tags(cx, cy), tag);
	}

	public int dir(int cx, int cy) {
		return this.dirs[cy * this.w + cx] & 0xFF;
	}

	/** The tile the drawn border shows at art position (tx, ty) (which may lie outside the map), or -1 without one. */
	public int borderTile(int tx, int ty) {
		int side = this.borderSize();
		return this.border == null ? -1 : this.border[Math.floorMod(ty, side) * side + Math.floorMod(tx, side)];
	}

	/** The tile the map's own border shows at art position (tx, ty). */
	public int ownBorderTile(int tx, int ty) {
		int side = this.borderSize();
		return this.ownBorder[Math.floorMod(ty, side) * side + Math.floorMod(tx, side)];
	}

	/** The same map under a ceiling {@code blocks} over its floor (D5: the shape planner's, from the map's layout). */
	public SceneMap withHeadroom(int blocks) {
		return new SceneMap(this.id, this.index, this.tileset, this.atlas, this.palette, this.w, this.h, this.perCell, this.surface,
				this.env.withHeadroom(blocks), this.tiles, this.closedTiles, this.kinds, this.tags, this.dirs, this.border, this.ownBorder,
				this.objects, this.swapSites);
	}

	/**
	 * The same map with every swap site closed: the closed art everywhere, and the closed kind and tags on every cell a
	 * site changes. This map itself when no site changes it.
	 */
	public SceneMap closedVariant() {
		if (this.closedTiles == null) {
			return this;
		}
		byte[] closedKinds = this.kinds.clone();
		int[] closedTags = this.tags.clone();
		byte[] closedDirs = this.dirs.clone();
		for (SwapSite site : this.swapSites) {
			for (SwapSite.SwapCell cell : site.cells()) {
				int i = cell.y() * this.w + cell.x();
				closedKinds[i] = (byte) cell.closed().kind().id();
				closedTags[i] = cell.closed().tags();
				closedDirs[i] = (byte) NO_DIR;
			}
		}
		return new SceneMap(this.id, this.index, this.tileset, this.atlas, this.palette, this.w, this.h, this.perCell, this.surface, this.env,
				this.closedTiles, null, closedKinds, closedTags, closedDirs, this.border, this.ownBorder, this.objects, List.of());
	}

	/** A stationary or walking object placed in the map (people, items, boulders). */
	public record SceneObject(String name, String sprite, int x, int y, boolean stays, boolean hidden, String kind) {
		public static final String PERSON = "person";
		public static final String ITEM = "item";
		public static final String TRAINER = "trainer";
		public static final String POKEMON = "pokemon";
	}
}
