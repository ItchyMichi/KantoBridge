package dev.kantobridge.builder.plan;

import dev.kantobridge.block.EdgeWallBlock;
import dev.kantobridge.builder.export.ExportField;
import dev.kantobridge.builder.export.G3Map;
import dev.kantobridge.builder.export.G3Tiles;
import dev.kantobridge.world.Scale;
import org.jetbrains.annotations.Nullable;

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

	/**
	 * A Gen 3 map's elevation edges (MG7, GEN3_BRIDGE_PLAN §10 E8, MULTI_GAME_PLAN §4.10): the engine refuses a step onto a
	 * cell whose elevation differs from the walker's unless one of them is 0 (any elevation) or the cell's is 15 (a bridge,
	 * which keeps the walker's), so a flat build walls each edge between two walkable cells (ground or ledge) whose
	 * elevations differ and are neither 0 nor 15. Water gets none: surfing on and off is the engine's own move.
	 */
	public static PairEdges elevation(MapModel model, G3Map map) {
		return elevation(model, map, null);
	}

	/**
	 * The same, with the field audit's two more (FIELD_MECHANICS_AUDIT_PLAN §13.8 item 3), when {@code tiles} gives the
	 * map's behaviours: the one-way sides ({@code MB_IMPASSABLE_*}: the engine refuses leaving a cell across a side its
	 * behaviour closes, and entering one across it, {@code Collision.directionallyImpassable}), between any two cells a
	 * player stands on; and water to water across two elevations, which a surfer can't cross either.
	 */
	public static PairEdges elevation(MapModel model, G3Map map, @Nullable G3Tiles tiles) {
		int w = model.cellWidth();
		int h = model.cellHeight();
		if (w != map.w() || h != map.h()) {
			throw new IllegalArgumentException("the model is not the map's");
		}
		byte[] sides = new byte[w * h];
		int edges = 0;
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				if (cx + 1 < w && (elevationWall(model, map, cx, cy, cx + 1, cy) || tiles != null && fieldWall(model, map, tiles, cx, cy, cx + 1, cy))) {
					sides[cy * w + cx] |= EdgeWallBlock.SIDE_EAST;
					sides[cy * w + cx + 1] |= EdgeWallBlock.SIDE_WEST;
					edges++;
				}
				if (cy + 1 < h && (elevationWall(model, map, cx, cy, cx, cy + 1) || tiles != null && fieldWall(model, map, tiles, cx, cy, cx, cy + 1))) {
					sides[cy * w + cx] |= EdgeWallBlock.SIDE_SOUTH;
					sides[(cy + 1) * w + cx] |= EdgeWallBlock.SIDE_NORTH;
					edges++;
				}
			}
		}
		return edges == 0 ? NONE : new PairEdges(w, h, sides, edges);
	}

	/**
	 * A Gen 2 map's one-way edges (MG9, docs/specs/GEN2_BRIDGE_PLAN.md §7): {@code walls} lists pairs of neighbouring cells
	 * {@code {ax, ay, bx, by}} the engine refuses a step between (its side walls and buoys: the standing cell's own sides
	 * and the neighbour arms, which in Crystal close the same edge from both cells). Only edges between two cells a
	 * player stands on (ground, ledge or water) are walled. Duplicates count once.
	 */
	public static PairEdges walls(MapModel model, java.util.List<int[]> walls) {
		int w = model.cellWidth();
		int h = model.cellHeight();
		byte[] sides = new byte[w * h];
		int edges = 0;
		for (int[] e : walls) {
			int ax = Math.min(e[0], e[2]);
			int ay = Math.min(e[1], e[3]);
			int bx = Math.max(e[0], e[2]);
			int by = Math.max(e[1], e[3]);
			if (ax < 0 || ay < 0 || bx >= w || by >= h || (bx - ax) + (by - ay) != 1) {
				continue;
			}
			if (!standable(model.kind(ax, ay)) || !standable(model.kind(bx, by))) {
				continue;
			}
			int first = bx > ax ? EdgeWallBlock.SIDE_EAST : EdgeWallBlock.SIDE_SOUTH;
			int second = bx > ax ? EdgeWallBlock.SIDE_WEST : EdgeWallBlock.SIDE_NORTH;
			if ((sides[ay * w + ax] & first) != 0) {
				continue;
			}
			sides[ay * w + ax] |= first;
			sides[by * w + bx] |= second;
			edges++;
		}
		return edges == 0 ? NONE : new PairEdges(w, h, sides, edges);
	}

	/**
	 * {@code base} with the map's closed borders walled (FIELD_MECHANICS_AUDIT_PLAN §13.8 item 2): a cell a player stands on
	 * along a side of the map that has no connection ({@code open} lacks its direction: north, south, east, west) gets a
	 * wall on its outer side, where the game refuses the step off the map and Minecraft had the void fill or nothing.
	 */
	public static PairEdges withBorders(PairEdges base, MapModel model, java.util.Set<String> open) {
		int w = model.cellWidth();
		int h = model.cellHeight();
		byte[] sides = new byte[w * h];
		int edges = base.edges;
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				sides[cy * w + cx] = (byte) base.sides(cx, cy);
			}
		}
		int added = 0;
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				if (!standable(model.kind(cx, cy))) {
					continue;
				}
				int add = 0;
				if (cy == 0 && !open.contains("north")) {
					add |= EdgeWallBlock.SIDE_NORTH;
				}
				if (cy == h - 1 && !open.contains("south")) {
					add |= EdgeWallBlock.SIDE_SOUTH;
				}
				if (cx == 0 && !open.contains("west")) {
					add |= EdgeWallBlock.SIDE_WEST;
				}
				if (cx == w - 1 && !open.contains("east")) {
					add |= EdgeWallBlock.SIDE_EAST;
				}
				if ((sides[cy * w + cx] & add) != add) {
					sides[cy * w + cx] |= (byte) add;
					added++;
				}
			}
		}
		return added == 0 ? base : new PairEdges(w, h, sides, edges + added);
	}

	/** True when the engine refuses a step between cells (ax, ay) and (bx, by) for their elevations. */
	static boolean elevationWall(MapModel model, G3Map map, int ax, int ay, int bx, int by) {
		if (!walked(model.kind(ax, ay)) || !walked(model.kind(bx, by))) {
			return false;
		}
		int a = map.elev(ax, ay);
		int b = map.elev(bx, by);
		return a != b && a != 0 && b != 0 && a != 15 && b != 15;
	}

	/** True when a one-way side or water's elevation closes the edge from (ax, ay) to its east or south neighbour (bx, by). */
	static boolean fieldWall(MapModel model, G3Map map, G3Tiles tiles, int ax, int ay, int bx, int by) {
		MapModel.Kind ka = model.kind(ax, ay);
		MapModel.Kind kb = model.kind(bx, by);
		if (!standable(ka) || !standable(kb)) {
			return false;
		}
		int a = tiles.behavior(map.mid(ax, ay)) & 0xFF;
		int b = tiles.behavior(map.mid(bx, by)) & 0xFF;
		boolean east = bx > ax;
		// leaving a east (south), or entering b from the west (north): the engine's two checks, one edge
		if (east ? eastBlocked(a) || westBlocked(b) : southBlocked(a) || northBlocked(b)) {
			return true;
		}
		if (ka == MapModel.Kind.WATER && kb == MapModel.Kind.WATER) {
			int ea = map.elev(ax, ay);
			int eb = map.elev(bx, by);
			return ea != eb && ea != 0 && eb != 0 && ea != 15 && eb != 15;
		}
		return false;
	}

	// The engine's MB_IMPASSABLE_* (0x30 east, 0x31 west, 0x32 north, 0x33 south, 0x34 NE, 0x35 NW, 0x36 SE, 0x37 SW;
	// game3/collision.lua, the canonical ids)
	static boolean eastBlocked(int beh) {
		return beh == 0x30 || beh == 0x34 || beh == 0x36;
	}

	static boolean westBlocked(int beh) {
		return beh == 0x31 || beh == 0x35 || beh == 0x37;
	}

	static boolean northBlocked(int beh) {
		return beh == 0x32 || beh == 0x34 || beh == 0x35;
	}

	static boolean southBlocked(int beh) {
		return beh == 0x33 || beh == 0x36 || beh == 0x37;
	}

	private static boolean walked(MapModel.Kind kind) {
		return kind == MapModel.Kind.GROUND || kind == MapModel.Kind.LEDGE;
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

	/** True when the edge between neighbouring cells (ax, ay) and (bx, by) is walled. */
	public boolean closed(int ax, int ay, int bx, int by) {
		int side = bx > ax ? EdgeWallBlock.SIDE_EAST : bx < ax ? EdgeWallBlock.SIDE_WEST : by > ay ? EdgeWallBlock.SIDE_SOUTH : EdgeWallBlock.SIDE_NORTH;
		return (this.sides(ax, ay) & side) != 0;
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
