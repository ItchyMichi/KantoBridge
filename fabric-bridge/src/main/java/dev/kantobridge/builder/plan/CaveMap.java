package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.builder.export.G2Map;
import dev.kantobridge.builder.export.G2Tiles;
import dev.kantobridge.builder.export.G3Map;
import dev.kantobridge.builder.export.G3Tiles;
import dev.kantobridge.scene.gen3.QuarterPages;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * One map of the 3D area as the cave planner reads it (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D1): game-neutral, so Gen 1's
 * caves and Gen 2's and Gen 3's complexes take the same planner ({@link CavePlanner}, {@link CaveRooms},
 * {@link CavePieces}). Each family fills it from its own export ({@link #gen1}, {@link #gen2}, {@link #gen3}). Pure Java.
 *
 * @param index the export's order of the map (Gen 1's map index, the others' order by id)
 * @param walks per cell, row by row: a cell some story state lets a player walk or surf on (rooms never depend on the
 *              story: CAVES_3D_PLAN §3.5)
 * @param warps its warps, each with its arrival cell resolved and its kind
 * @param door  where its parent's door opens into it, or null when no door leads in (a surface map, a map only a warp
 *              reaches)
 */
public record CaveMap(String id, int index, int w, int h, BitSet walks, List<Warp> warps, @Nullable Door door, String palette, boolean dark) {
	public CaveMap {
		walks = (BitSet) walks.clone();
		warps = List.copyOf(warps);
	}

	/** What a warp cell is (D2). */
	public enum Kind {
		DOOR,
		/** A ladder: a real shaft when its ends line up. */
		LADDER,
		/** A hole: a one-way drop when its ends line up. */
		HOLE,
		/** Stairs between maps: always a warp with its fade (the user's V2). */
		STAIRS,
		PAD,
		OTHER
	}

	/**
	 * A warp: its cell, the map it leads to, the cell it arrives on there (-1 when the export doesn't say: a dynamic warp,
	 * Gen 1's return to the last map), its kind and which way it leads ({@code way}: +1 up, -1 down, 0 unknown).
	 */
	public record Warp(int x, int y, @Nullable String to, int arrivalX, int arrivalY, Kind kind, int way) {
		/** True when the arrival cell is known. */
		public boolean arrives() {
			return this.to != null && this.arrivalX >= 0;
		}
	}

	/** An interior's door: the cell of the parent map it opens from, and the cell it arrives on in this map. */
	public record Door(String map, int x, int y, int arrivalX, int arrivalY) {
	}

	/** True when cell (cx, cy) is walked or surfed on in some story state. */
	public boolean walks(int cx, int cy) {
		return cx >= 0 && cy >= 0 && cx < this.w && cy < this.h && this.walks.get(cy * this.w + cx);
	}

	/** The map's warp on cell (x, y), or null. */
	public @Nullable Warp warpAt(int x, int y) {
		for (Warp warp : this.warps) {
			if (warp.x() == x && warp.y() == y) {
				return warp;
			}
		}
		return null;
	}

	/** The parent's id (the map its door is in), or null. */
	public @Nullable String parent() {
		return this.door == null ? null : this.door.map();
	}

	// ---- the families ----

	/**
	 * A Gen 1 map. Its walk cells are the export's walkable and water cells and its swap sites' blocks ({bx, by}, 2 × 2
	 * cells); a hole is the hole flag (a pad of its own), a door the door flag, a ladder a warp tile the review draws as
	 * one, or in a cave any other warp tile that isn't a door (as H5's rule had it).
	 *
	 * @param model its cell model, for the drawing the review keys a warp cell by
	 */
	public static CaveMap gen1(ExportMap map, MapModel model, Map<String, ExportMap> all, Collection<int[]> siteBlocks, String game, boolean cave) {
		return gen1(map, all, siteBlocks, cave, (x, y) -> x < model.cellWidth() && y < model.cellHeight()
				? WarpKinds.drawn(game, atlas(model), cellTiles(model, x, y)) : null);
	}

	/** The same without the review: every warp tile in a cave that isn't a door is a ladder (H5's rule). */
	public static CaveMap gen1(ExportMap map, Map<String, ExportMap> all, Collection<int[]> siteBlocks) {
		return gen1(map, all, siteBlocks, true, (x, y) -> null);
	}

	/** The review's kind of a warp cell, or null. */
	@FunctionalInterface
	interface Review {
		WarpKinds.@Nullable Drawn at(int x, int y);
	}

	private static CaveMap gen1(ExportMap map, Map<String, ExportMap> all, Collection<int[]> siteBlocks, boolean cave, Review review) {
		int w = map.cellWidth();
		int h = map.cellHeight();
		BitSet walks = new BitSet(w * h);
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				int flags = map.flags(cx, cy);
				walks.set(cy * w + cx, CellFlags.has(flags, CellFlags.WALKABLE) || CellFlags.has(flags, CellFlags.WATER));
			}
		}
		for (int[] site : siteBlocks) {
			for (int dy = 0; dy < 2; dy++) {
				for (int dx = 0; dx < 2; dx++) {
					int cx = site[0] * 2 + dx;
					int cy = site[1] * 2 + dy;
					if (cx >= 0 && cy >= 0 && cx < w && cy < h) {
						walks.set(cy * w + cx);
					}
				}
			}
		}
		List<Warp> warps = new ArrayList<>();
		for (ExportMap.Warp warp : map.warps()) {
			ExportMap to = all.get(warp.to());
			ExportMap.Warp arrival = to == null ? null : to.warp(warp.warp());
			boolean in = warp.x() >= 0 && warp.y() >= 0 && warp.x() < w && warp.y() < h;
			int flags = in ? map.flags(warp.x(), warp.y()) : 0;
			WarpKinds.Drawn drawn = in ? review.at(warp.x(), warp.y()) : null;
			Kind kind;
			int way = drawn == null ? 0 : drawn.way();
			if (CellFlags.has(flags, CellFlags.HOLE)) {
				kind = Kind.HOLE;
				way = -1;
			} else if (CellFlags.has(flags, CellFlags.DOOR)) {
				kind = Kind.DOOR;
			} else if (CellFlags.has(flags, CellFlags.WARP_TILE)) {
				kind = drawn == null ? (cave ? Kind.LADDER : Kind.STAIRS) : fromDrawn(drawn, cave);
			} else if (CellFlags.has(flags, CellFlags.PAD)) {
				kind = Kind.PAD;
			} else {
				kind = Kind.OTHER;
			}
			warps.add(new Warp(warp.x(), warp.y(), to == null ? null : warp.to(), arrival == null ? -1 : arrival.x(), arrival == null ? -1 : arrival.y(),
					kind, way));
		}
		Door door = null;
		if (map.parent() != null) {
			ExportMap parent = all.get(map.parent().map());
			ExportMap.Warp opens = parent == null ? null : parent.warp(map.parent().warp());
			ExportMap.Warp arrival = opens != null && opens.to().equals(map.id()) ? map.warp(opens.warp()) : null;
			door = new Door(map.parent().map(), map.parent().x(), map.parent().y(), arrival == null ? 0 : arrival.x(), arrival == null ? 0 : arrival.y());
		}
		return new CaveMap(map.id(), map.index(), w, h, walks, warps, door, map.palette(), map.dark());
	}

	/**
	 * A Gen 2 map. Its walk cells are its model's walkable, ledge and water cells in either state of its swap sites (a
	 * ledge is hopped, so the cells either side of it are one room); a warp's kind is its collision byte's
	 * ({@link G2Tiles.Meaning#warp}), the review telling a ladder from a staircase and up from down.
	 *
	 * @param door its door as the planner found it, or null
	 */
	public static CaveMap gen2(G2Map map, G2Tiles tiles, MapModel model, Map<String, G2Map> all, @Nullable Door door, String palette, int index,
			String game) {
		int w = model.cellWidth();
		int h = model.cellHeight();
		List<Warp> warps = new ArrayList<>();
		for (G2Map.Warp warp : map.warps()) {
			G2Map to = warp.to() == null || warp.dynamic() ? null : all.get(warp.to());
			G2Map.Warp arrival = to == null || warp.warp() < 0 || warp.warp() >= to.warps().size() ? null : to.warps().get(warp.warp());
			String meaning = null;
			if (map.inCells(warp.x(), warp.y())) {
				int block = map.wallBlock(warp.x() / 2, warp.y() / 2) ? -1 : map.drawnBlock(warp.x() / 2, warp.y() / 2);
				meaning = block < 0 ? null : tiles.meaning(tiles.coll(block, warp.x() % 2, warp.y() % 2)).warp();
			}
			WarpKinds.Drawn drawn = inModel(model, warp.x(), warp.y()) ? WarpKinds.drawn(game, atlas(model), cellTiles(model, warp.x(), warp.y())) : null;
			Kind kind;
			int way = drawn == null ? 0 : drawn.way();
			if (meaning == null) {
				kind = Kind.OTHER;
			} else {
				kind = switch (meaning) {
					case "door" -> Kind.DOOR;
					case "pad" -> Kind.PAD;
					case "hole" -> Kind.HOLE;
					case "ladder" -> Kind.LADDER;
					case "stairs" -> drawn != null && drawn.ladder() ? Kind.LADDER : Kind.STAIRS;
					default -> Kind.OTHER; // a mat
				};
			}
			if (kind == Kind.HOLE) {
				way = -1;
			}
			warps.add(new Warp(warp.x(), warp.y(), to == null ? null : warp.to(), arrival == null ? -1 : arrival.x(), arrival == null ? -1 : arrival.y(),
					kind, way));
		}
		return new CaveMap(map.id(), index, w, h, walkCells(model), warps, door, palette, map.dark());
	}

	/**
	 * A Gen 3 map. Its walk cells are its model's walkable, ledge and water cells (its changeable cells are built in their
	 * most walkable state already: {@link G3Built}); a warp's kind is its cell's behaviour (a ladder; a fall warp or a hole;
	 * stairs, escalators and arrow warps), its collision's door, else the review, which also says up or down.
	 */
	public static CaveMap gen3(G3Map map, G3Tiles tiles, MapModel model, Map<String, G3Map> all, @Nullable Door door, String palette, int index,
			String game) {
		int w = model.cellWidth();
		int h = model.cellHeight();
		List<Warp> warps = new ArrayList<>();
		for (G3Map.Warp warp : map.warps()) {
			G3Map to = warp.to() == null || warp.dynamic() ? null : all.get(warp.to());
			G3Map.Warp arrival = to == null || warp.warp() < 0 || warp.warp() >= to.warps().size() ? null : to.warps().get(warp.warp());
			boolean in = map.inGrid(warp.x(), warp.y());
			String behavior = in ? tiles.behaviorName(map.mid(warp.x(), warp.y())) : null;
			int flags = in ? model.flags(warp.x(), warp.y()) : 0;
			WarpKinds.Drawn drawn = in && inModel(model, warp.x(), warp.y()) ? WarpKinds.drawn(game, atlas(model), cellTiles(model, warp.x(), warp.y()))
					: null;
			Kind kind;
			int way = drawn == null ? 0 : drawn.way();
			if (behavior != null && behavior.equals("LADDER")) {
				kind = Kind.LADDER;
			} else if (behavior != null && (behavior.contains("FALL") && !behavior.contains("WATERFALL") || behavior.endsWith("_HOLE")
					|| behavior.equals("LAVARIDGE_1F_WARP"))) {
				kind = Kind.HOLE;
			} else if (behavior != null && (behavior.contains("STAIR") || behavior.contains("ESCALATOR") || behavior.contains("ARROW_WARP"))) {
				kind = Kind.STAIRS;
				if (behavior.startsWith("UP_")) {
					way = 1;
				} else if (behavior.startsWith("DOWN_")) {
					way = -1;
				}
			} else if (CellFlags.has(flags, CellFlags.DOOR)) {
				kind = Kind.DOOR;
			} else if (drawn != null) {
				kind = fromDrawn(drawn, false);
			} else {
				kind = Kind.OTHER;
			}
			if (kind == Kind.HOLE) {
				way = -1;
			}
			warps.add(new Warp(warp.x(), warp.y(), to == null ? null : warp.to(), arrival == null ? -1 : arrival.x(), arrival == null ? -1 : arrival.y(),
					kind, way));
		}
		return new CaveMap(map.id(), index, w, h, walkCells(model), warps, door, palette, map.dark());
	}

	private static Kind fromDrawn(WarpKinds.Drawn drawn, boolean cave) {
		if (drawn.ladder()) {
			return Kind.LADDER;
		}
		if (drawn.stairs()) {
			return Kind.STAIRS;
		}
		return switch (drawn) {
			case HOLE -> Kind.HOLE;
			case DOOR, CAVE_MOUTH, WALL_EXIT -> Kind.DOOR;
			case PAD -> Kind.PAD;
			// a warp tile drawn as floor or a mat in a cave is still the cave's way between floors (H5's rule)
			default -> cave ? Kind.LADDER : Kind.OTHER;
		};
	}

	/** A model's cells walked, surfed or hopped in either state of its swap sites. */
	static BitSet walkCells(MapModel model) {
		int w = model.cellWidth();
		int h = model.cellHeight();
		BitSet walks = new BitSet(w * h);
		MapModel closed = model.closedModel();
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				walks.set(cy * w + cx, walked(model.kind(cx, cy)) || closed != null && walked(closed.kind(cx, cy)));
			}
		}
		return walks;
	}

	private static boolean walked(MapModel.Kind kind) {
		return kind == MapModel.Kind.GROUND || kind == MapModel.Kind.WATER || kind == MapModel.Kind.LEDGE;
	}

	private static boolean inModel(MapModel model, int cx, int cy) {
		return cx >= 0 && cy >= 0 && cx < model.cellWidth() && cy < model.cellHeight();
	}

	/** The atlas a model's art tiles index, as the fitted-shape tables name it: Gen 1's tileset image, else the baked art's. */
	static String atlas(MapModel model) {
		return model.baked() ? QuarterPages.VIRTUAL : model.tileset().image();
	}

	/** The art tiles of cell (cx, cy), row by row, as the fitted-shape tables key a cell. */
	static int[] cellTiles(MapModel model, int cx, int cy) {
		int[] tiles = new int[4];
		for (int k = 0; k < 4; k++) {
			tiles[k] = model.tile(cx * 2 + k % 2, cy * 2 + k / 2);
		}
		return tiles;
	}
}
