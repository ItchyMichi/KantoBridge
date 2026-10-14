package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.G3Map;
import dev.kantobridge.builder.export.G3Tiles;
import java.util.HashSet;
import java.util.Set;

/**
 * A Gen 3 map's cell model (MG7, docs/specs/GEN3_BRIDGE_PLAN.md §5 "The collision byte", §6): from a {@code g3map} and its
 * pair's {@code g3tiles}. Every cell of the layout's whole grid is built (a padded map's cells past its true size show
 * the border and are solid, as the engine has them). Pure Java.
 *
 * <ul>
 * <li><b>Art:</b> each cell's metatile shows its four flattened quarters (top left, top right, bottom left, bottom right)
 * as the cell's 2×2 art tiles; a metatile the pair lacks shows the border there ({@code g3_metatile_missing}).</li>
 * <li><b>Kind and flags</b> from the engine's collision byte, which it seeds at import from the metatile's behaviour and
 * the map's collision bit (Gen 2's vocabulary; {@link #flags}); the cells the map's warps stand on are warps.</li>
 * <li><b>Wild cells:</b> walkable cells whose behaviour rolls land encounters by the engine's own rule (the export's
 * {@code land} list); an export without the list falls back to tall grass ({@code g3_land_rule_missing}).</li>
 * <li><b>Border:</b> {@code bw × bh} metatiles, so {@code 2bw × 2bh} art tiles, repeated from the map's cell (0, 0) as the
 * engine samples it (layout_native.lua:6-13, {@code wrap_border}).</li>
 * </ul>
 */
public final class G3Cells {
	/** The collision bytes (GEN3_BRIDGE_PLAN §5; the engine's collision_frlg.lua:62-84, 148-175). */
	public static final int WALK = 0x00;
	public static final int BLOCKED = 0x07;
	public static final int TALL_GRASS = 0x18;
	public static final int WATER = 0x29;
	public static final int CAVE = 0x2B;
	public static final int DOOR = 0x71;
	public static final int WARP_KEEP_FACING = 0x72;
	public static final int SIGN = 0x82;
	public static final int COUNTER = 0x90;
	public static final int PC = 0x93;
	public static final int LEDGE_EAST = 0xA0;
	public static final int LEDGE_WEST = 0xA1;
	public static final int LEDGE_NORTH = 0xA2;
	public static final int LEDGE_SOUTH = 0xA3;
	public static final int NOTHING = 0xFF;
	/** The engine's MAP_TYPE_* numbers of outdoor maps: town, city, route, ocean route. */
	public static final java.util.Set<Integer> OUTDOOR_MAP_TYPES = java.util.Set.of(1, 2, 3, 6);
	/** MAP_TYPE_UNDERGROUND: caves. */
	public static final int MAP_TYPE_UNDERGROUND = 4;

	private G3Cells() {
	}

	/** True for an outdoor map (a town, city, route or ocean route), by its map type. */
	public static boolean outdoor(G3Map map) {
		return OUTDOOR_MAP_TYPES.contains(map.mapType());
	}

	/** True for a cave, by its map type or the engine's kind for it. */
	public static boolean cave(G3Map map) {
		return map.mapType() == MAP_TYPE_UNDERGROUND || "cave".equals(map.kind());
	}

	/**
	 * The {@link CellFlags} bits of a collision byte; -1 for a byte the vocabulary doesn't have (built solid, counted).
	 * Ledges hop the way they jump: east right (3), west left (2), north up (1), south down (0).
	 */
	public static int flags(int coll) {
		return switch (coll) {
			case WALK, CAVE -> CellFlags.WALKABLE;
			case TALL_GRASS -> CellFlags.WALKABLE | CellFlags.GRASS;
			case WATER -> CellFlags.WATER;
			case DOOR -> CellFlags.WALKABLE | CellFlags.DOOR;
			case WARP_KEEP_FACING -> CellFlags.WALKABLE | CellFlags.WARP_TILE;
			case SIGN -> CellFlags.SIGN;
			case COUNTER -> CellFlags.COUNTER;
			case BLOCKED, PC, NOTHING -> 0;
			case LEDGE_EAST -> ledge(3);
			case LEDGE_WEST -> ledge(2);
			case LEDGE_NORTH -> ledge(1);
			case LEDGE_SOUTH -> ledge(0);
			default -> -1;
		};
	}

	private static int ledge(int dir) {
		return CellFlags.LEDGE | dir << CellFlags.LEDGE_DIR_SHIFT;
	}

	/**
	 * The model of a map.
	 *
	 * @param interior placed under the surface (interior walls and no measured heights)
	 */
	public static MapModel model(G3Map map, G3Tiles tiles, boolean interior, Warnings warnings) {
		int w = map.w();
		int h = map.h();
		int cols = w * 2;
		int[] border = borderTiles(map, tiles, warnings);
		int borderCols = map.border().w() * 2;
		int borderRows = map.border().h() * 2;
		Set<Integer> warpCells = new HashSet<>();
		for (G3Map.Warp warp : map.warps()) {
			if (map.inGrid(warp.x(), warp.y())) {
				warpCells.add(map.index(warp.x(), warp.y()));
			}
		}
		int[] art = new int[cols * h * 2];
		int[] flags = new int[w * h];
		boolean[] wild = new boolean[w * h];
		int missing = 0;
		int unknown = 0;
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				int i = cy * w + cx;
				int mid = map.mid(cx, cy);
				int[] quarters = tiles.quarters(mid);
				if (quarters == null) {
					missing++;
				}
				for (int q = 0; q < 4; q++) {
					int tx = cx * 2 + q % 2;
					int ty = cy * 2 + q / 2;
					art[ty * cols + tx] = quarters != null ? quarters[q]
							: border[Math.floorMod(ty, borderRows) * borderCols + Math.floorMod(tx, borderCols)];
				}
				int f = map.inMap(cx, cy) ? flags(map.coll(cx, cy)) : 0;
				if (f < 0) {
					unknown++;
					f = 0;
				}
				if (warpCells.contains(i)) {
					f |= CellFlags.WARP;
				}
				flags[i] = f;
				boolean walkable = CellFlags.has(f, CellFlags.WALKABLE) && !CellFlags.has(f, CellFlags.LEDGE);
				wild[i] = walkable && (tiles.knowsLand() ? tiles.landEncounter(mid) : map.coll(cx, cy) == TALL_GRASS);
			}
		}
		warnings.warn("g3_metatile_missing", missing);
		warnings.warn("g3_collision_unknown", unknown);
		if (!tiles.knowsLand()) {
			warnings.note("g3_land_rule_missing", Warnings.symbols("map", map.id()));
		}
		return MapModel.gen3(map.id(), interior, w, h, art, flags, wild, border, borderCols, borderRows);
	}

	/** The border's art tiles, {@code 2bw × 2bh}, row-major; a metatile the pair lacks shows quarter 0 (counted). */
	static int[] borderTiles(G3Map map, G3Tiles tiles, Warnings warnings) {
		G3Map.Border border = map.border();
		int cols = border.w() * 2;
		int[] out = new int[cols * border.h() * 2];
		int missing = 0;
		for (int by = 0; by < border.h(); by++) {
			for (int bx = 0; bx < border.w(); bx++) {
				int[] quarters = tiles.quarters(border.mids()[by * border.w() + bx]);
				if (quarters == null) {
					missing++;
				}
				for (int q = 0; q < 4; q++) {
					out[(by * 2 + q / 2) * cols + bx * 2 + q % 2] = quarters == null ? 0 : quarters[q];
				}
			}
		}
		warnings.warn("g3_border_metatile_missing", missing);
		return out;
	}
}
