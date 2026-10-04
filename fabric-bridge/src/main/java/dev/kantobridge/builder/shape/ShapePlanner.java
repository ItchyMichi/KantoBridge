package dev.kantobridge.builder.shape;

import com.google.gson.JsonArray;
import dev.kantobridge.builder.plan.Box3;
import dev.kantobridge.builder.plan.HeightRules;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.Scene;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.ShapeIndex;
import dev.kantobridge.world.ShapeMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Turns the scene into shape keys per block (docs/specs/VOXEL_BLOCKS_PLAN.md §5.4): pure, integer-only, shared by the
 * server and the clients; it never reads a pixel (I8). Keys come from the <b>whole export</b> (every map, both states of
 * every swap site), never from a build's map filter, so a filtered and a full build share one shape index; the shape map
 * holds the positions of the maps the build places.
 *
 * <p>Per art tile it also plans the column the builder places: how many blocks stand on the floor block, and which of
 * them are {@code shape_full} carriers (the rest are {@code tile_full}).
 *
 * <p>{@code kb-shapes/1} (heights {@code classified}, V2) folds today's columns: every block of a solid column keeps its
 * height and carrier, and becomes a {@code cube} whose faces show what the 2D map shows. {@code kb-shapes/2} (heights
 * {@code measured}, V3) plans {@link Structures#measured measured runs}:
 *
 * <pre>
 * band k = 0..wallH-1 at feet+k of the column at (tx, ty) in run n..f (tile_full):
 *   south = west = east = tile(tx, max(n, origin - k))   the fold rule (origin: f, or a folded door's southern row)
 *   north = tile(tx, min(f, n + 1))                      a plain back, never a second front
 *   up = down = under a roof: none; else row ty-h in the run ? tile(tx, ty - h) : tile(tx, n)
 * roof (r &gt; 0) at feet+wallH (shape_full): rise(s) = min(roofRise, 32 * min(s, L - s) / L), dp = origin - ty, the depth
 *   L = origin - n + 1 (a folded door's columns roofed like the facade beside them)
 *   roof(top = 2dp &lt; L ? tile(n + r - 1) : tile(n), south = tile(max(n, origin - wallH)), north = tile(n),
 *        h0 = rise(dp), h1 = rise(dp + 1), hipW/hipE = the west/east neighbour column has no roof at this height)
 * a LEDGE cell's feet level (its ledge collider): slab(tile, ledgeLip)
 * a COUNTER cell: one block, plain
 * a cube whose six faces all show the position's own tile is "no shape" (id 0): the plain cube already is it
 * </pre>
 */
public final class ShapePlanner {
	private ShapePlanner() {
	}

	/**
	 * The keys of one map: per art tile, the keys of its column's blocks from feet level up (null for none), open and
	 * closed (the closed state of swap-site cells only), and the column the builder places.
	 *
	 * @param blocks    per art tile, the blocks standing on its floor block (0: none)
	 * @param shapeMask per art tile, bit k set when block k (from feet level) is a {@code shape_full} carrier
	 */
	public record MapShapes(String map, ShapeKey[][] open, ShapeKey[][] closed, int[] blocks, int[] shapeMask) {
	}

	/** One state's plan of a map. */
	record Columns(ShapeKey[][] keys, int[] blocks, int[] shapeMask) {
	}

	/**
	 * Plans a world's shapes.
	 *
	 * @param placements the maps the build places (their shape map positions)
	 */
	public static ShapePlan plan(Scene scene, Collection<MapPlacement> placements, ShapeParams params) {
		long started = System.nanoTime();
		List<String> atlases = new ArrayList<>(new TreeSet<>(scene.atlases().keySet()));
		TreeSet<String> paletteSet = new TreeSet<>();
		for (SceneMap map : scene.maps().values()) {
			paletteSet.add(map.palette());
		}
		List<String> palettes = new ArrayList<>(paletteSet);
		Map<String, MapShapes> shapes = new TreeMap<>();
		List<ShapeIndex.Use> uses = new ArrayList<>();
		for (SceneMap map : scene.maps().values()) {
			int atlas = atlases.indexOf(map.atlas());
			if (atlas < 0) {
				continue;
			}
			MapShapes planned = planMap(map, atlas, params);
			shapes.put(map.id(), planned);
			for (ShapeKey[][] state : new ShapeKey[][][] {planned.open(), planned.closed()}) {
				for (ShapeKey[] column : state) {
					if (column == null) {
						continue;
					}
					for (ShapeKey key : column) {
						if (key != null) {
							uses.add(new ShapeIndex.Use(key, map.palette()));
						}
					}
				}
			}
		}
		ShapeIndex index = ShapeIndex.of(params.planner(), params.toJson(), atlases, palettes, uses, new JsonArray());

		// ---- the shape map: the surface box and each interior volume, from the feet level up ----
		Box3 surface = WorldPlan.surfaceVolume(placements);
		ShapeMap.GridBuilder surfaceGrid = surface == null ? null
				: new ShapeMap.GridBuilder(surface.minX(), surface.minZ(), surface.maxX() - surface.minX(), surface.maxZ() - surface.minZ(),
						surface.minY() + 1);
		List<ShapeMap.GridBuilder> grids = new ArrayList<>();
		if (surfaceGrid != null) {
			grids.add(surfaceGrid);
		}
		long shaped = 0;
		for (MapPlacement placement : placements) {
			MapShapes planned = shapes.get(placement.id());
			SceneMap map = scene.map(placement.id());
			if (planned == null || map == null) {
				continue;
			}
			ShapeMap.GridBuilder grid;
			if (placement.kind() == MapPlacement.Kind.SURFACE) {
				grid = surfaceGrid;
			} else {
				Box3 volume = WorldPlan.interiorVolume(placement);
				grid = new ShapeMap.GridBuilder(volume.minX(), volume.minZ(), volume.maxX() - volume.minX(), volume.maxZ() - volume.minZ(),
						placement.y());
				grids.add(grid);
			}
			if (grid == null) {
				continue;
			}
			int cols = map.artCols();
			for (int ty = 0; ty < map.artRows(); ty++) {
				for (int tx = 0; tx < cols; tx++) {
					ShapeKey[] column = planned.open()[ty * cols + tx];
					if (column == null) {
						continue;
					}
					for (int k = 0; k < column.length; k++) {
						if (column[k] == null) {
							continue;
						}
						int id = index.shapeId(column[k], map.palette());
						grid.set(placement.x() + tx, placement.y() + k, placement.z() + ty, id);
						shaped++;
					}
				}
			}
		}
		List<ShapeMap.Grid> built = new ArrayList<>();
		for (ShapeMap.GridBuilder grid : grids) {
			built.add(grid.build());
		}
		ShapeMap map = new ShapeMap(ShapeMap.NO_HASH, index.hash(), built, List.of());
		Map<ShapeKey.Kind, Integer> byKind = new EnumMap<>(ShapeKey.Kind.class);
		for (ShapeKey key : index.geoms()) {
			byKind.merge(key.kind(), 1, Integer::sum);
		}
		return new ShapePlan(params, index, map, shapes, byKind, shaped, (System.nanoTime() - started) / 1_000_000L);
	}

	/** One map's keys and columns, open and closed. */
	static MapShapes planMap(SceneMap map, int atlas, ShapeParams params) {
		Columns open = columns(map, atlas, params);
		ShapeKey[][] closed = new ShapeKey[open.keys().length][];
		if (map.closedTiles() != null) {
			// The closed state of the cells a swap site changes (a standing cut tree), planned on the map with every site
			// closed, exactly as the open state is; kept for the cells that change only.
			Columns all = columns(map.closedVariant(), atlas, params);
			int cols = map.artCols();
			int per = map.perCell();
			for (var site : map.swapSites()) {
				for (var cell : site.cells()) {
					for (int dy = 0; dy < per; dy++) {
						for (int dx = 0; dx < per; dx++) {
							int i = (cell.y() * per + dy) * cols + cell.x() * per + dx;
							closed[i] = all.keys()[i];
						}
					}
				}
			}
		}
		return new MapShapes(map.id(), open.keys(), closed, open.blocks(), open.shapeMask());
	}

	static Columns columns(SceneMap map, int atlas, ShapeParams params) {
		return params.measured() ? measured(map, atlas, params) : classified(map, atlas, params);
	}

	// ---- kb-shapes/1: today's classified heights, folded ----

	/** The heights the classified mode gives each cell. */
	static int[] heights(SceneMap map, ShapeParams params) {
		boolean[] solid = Structures.solidTiles(map);
		int[] units = Structures.units(map, solid);
		return Structures.classifiedHeights(map, units, params.interiorWall());
	}

	static Columns classified(SceneMap map, int atlas, ShapeParams params) {
		int cols = map.artCols();
		int rows = map.artRows();
		int[] cellHeights = heights(map, params);
		int[] blocks = new int[cols * rows];
		for (int ty = 0; ty < rows; ty++) {
			for (int tx = 0; tx < cols; tx++) {
				blocks[ty * cols + tx] = cellHeights[map.cellIndexOfTile(tx, ty)];
			}
		}
		return new Columns(fold(map, atlas, Structures.solidTiles(map), cellHeights), blocks, new int[cols * rows]);
	}

	/** The classified fold over every run of {@code solid}, with per-cell heights. */
	static ShapeKey[][] fold(SceneMap map, int atlas, boolean[] solid, int[] heights) {
		int cols = map.artCols();
		int rows = map.artRows();
		ShapeKey[][] out = new ShapeKey[cols * rows][];
		for (Structures.Run run : Structures.runs(solid, cols, rows)) {
			int tx = run.tx();
			for (int ty = run.n(); ty <= run.f(); ty++) {
				int h = heights[map.cellIndexOfTile(tx, ty)];
				if (h <= 0) {
					continue;
				}
				int own = map.tile(tx, ty);
				int north = map.tile(tx, Math.min(run.f(), run.n() + 1));
				int up = ty - h >= run.n() ? map.tile(tx, ty - h) : map.tile(tx, run.n());
				ShapeKey[] column = new ShapeKey[h];
				boolean any = false;
				for (int k = 0; k < h; k++) {
					int front = map.tile(tx, Math.max(run.n(), run.f() - k));
					if (up == own && north == own && front == own) {
						continue;
					}
					column[k] = ShapeKey.cube(atlas, up, up, north, front, front, front);
					any = true;
				}
				if (any) {
					out[ty * cols + tx] = column;
				}
			}
		}
		return out;
	}

	// ---- kb-shapes/2: measured runs, door folds, roofs, ledge lips ----

	static Columns measured(SceneMap map, int atlas, ShapeParams params) {
		int cols = map.artCols();
		int rows = map.artRows();
		int n = cols * rows;
		List<Structures.MeasuredRun> runs = Structures.measured(map, Structures.solidTiles(map), params.maxUnit(), params.minRepeatRows(),
				params.interiorWall());
		int[] blocks = new int[n];
		int[] mask = new int[n];
		// The block index of each column's roof, -1 for none: what the hips compare.
		int[] roofLevel = new int[n];
		Arrays.fill(roofLevel, -1);
		for (Structures.MeasuredRun run : runs) {
			for (int ty = run.n(); ty <= run.f(); ty++) {
				int i = ty * cols + run.tx();
				blocks[i] = run.blocks();
				if (run.r() > 0) {
					roofLevel[i] = run.wallH();
					mask[i] = 1 << run.wallH();
				}
			}
		}
		ShapeKey[][] keys = new ShapeKey[n][];
		for (int ty = 0; ty < rows; ty++) {
			for (int tx = 0; tx < cols; tx++) {
				CellKind kind = map.kindAtTile(tx, ty);
				if (kind == CellKind.COUNTER) {
					blocks[ty * cols + tx] = HeightRules.COUNTER;
				} else if (kind == CellKind.LEDGE) {
					keys[ty * cols + tx] = new ShapeKey[] {ShapeKey.slab(atlas, map.tile(tx, ty), params.ledgeLip())};
				}
			}
		}
		for (Structures.MeasuredRun run : runs) {
			int tx = run.tx();
			int top = run.n();
			int f = run.f();
			// The roof spans the run to its fold origin: a folded door's columns rise as the facade beside them does.
			int length = run.origin() - top + 1;
			int h = run.h();
			int wallH = run.wallH();
			int north = map.tile(tx, Math.min(f, top + 1));
			for (int ty = top; ty <= f; ty++) {
				int i = ty * cols + tx;
				int own = map.tile(tx, ty);
				int up = run.r() > 0 ? -1 : ty - h >= top ? map.tile(tx, ty - h) : map.tile(tx, top);
				ShapeKey[] column = new ShapeKey[run.blocks()];
				boolean any = false;
				for (int k = 0; k < wallH; k++) {
					int front = map.tile(tx, Math.max(top, run.origin() - k));
					if (up == own && north == own && front == own) {
						continue;
					}
					column[k] = ShapeKey.cube(atlas, up, up, north, front, front, front);
					any = true;
				}
				if (run.r() > 0) {
					int dp = run.origin() - ty;
					int roofTop = 2 * dp < length ? map.tile(tx, top + run.r() - 1) : map.tile(tx, top);
					int south = map.tile(tx, Math.max(top, run.origin() - wallH));
					boolean hipW = tx == 0 || roofLevel[i - 1] != wallH;
					boolean hipE = tx == cols - 1 || roofLevel[i + 1] != wallH;
					column[wallH] = ShapeKey.roof(atlas, roofTop, south, map.tile(tx, top), rise(dp, length, params.roofRise()),
							rise(dp + 1, length, params.roofRise()), hipW, hipE);
					any = true;
				}
				if (any) {
					keys[i] = column;
				}
			}
		}
		return new Columns(keys, blocks, mask);
	}

	/** The roof's height {@code s} rows behind the eave of a run {@code length} rows deep: a gable over the run's depth. */
	static int rise(int s, int length, int roofRise) {
		return Math.min(roofRise, 32 * Math.min(s, length - s) / length);
	}
}
