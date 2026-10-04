package dev.kantobridge.builder.shape;

import com.google.gson.JsonArray;
import dev.kantobridge.builder.plan.Box3;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.scene.Scene;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.ShapeIndex;
import dev.kantobridge.world.ShapeMap;
import java.util.ArrayList;
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
 * <p>V2 ({@code kb-shapes/1}, heights {@code classified}) folds today's columns: every block of a solid column keeps its
 * height and its carrier, and becomes a {@code cube} whose faces show what the 2D map shows:
 *
 * <pre>
 * band k = 0..h-1 at feet+k of the column at (tx, ty), in a run n..f:
 *   south = west = east = tile(tx, f - k)       the fold rule (clamped to the run)
 *   north = tile(tx, min(f, n + 1))             a plain back, never a second front
 *   up = down = row ty-h in the run ? tile(tx, ty - h) : tile(tx, n)
 * a cube whose six faces all show the position's own tile is "no shape" (id 0): the plain cube already is it
 * </pre>
 */
public final class ShapePlanner {
	private ShapePlanner() {
	}

	/** The keys of one map: per art tile, the keys of its column's blocks from feet level up (null for none). */
	public record MapShapes(String map, ShapeKey[][] open, ShapeKey[][] closed, int[] heights) {
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

	/** One map's keys, open and closed (V2: classified heights, the fold rule, cubes only). */
	static MapShapes planMap(SceneMap map, int atlas, ShapeParams params) {
		int[] heights = heights(map, params);
		ShapeKey[][] open = fold(map, atlas, Structures.solidTiles(map), heights);
		ShapeKey[][] closed = new ShapeKey[open.length][];
		if (map.closedTiles() != null) {
			// The closed state of the cells a swap site changes (a standing cut tree), planned on the map with every site
			// closed, exactly as the open state is; kept for the cells that change only.
			SceneMap shut = map.closedVariant();
			ShapeKey[][] all = fold(shut, atlas, Structures.solidTiles(shut), heights(shut, params));
			int cols = map.artCols();
			int per = map.perCell();
			for (var site : map.swapSites()) {
				for (var cell : site.cells()) {
					for (int dy = 0; dy < per; dy++) {
						for (int dx = 0; dx < per; dx++) {
							int i = (cell.y() * per + dy) * cols + cell.x() * per + dx;
							closed[i] = all[i];
						}
					}
				}
			}
		}
		return new MapShapes(map.id(), open, closed, heights);
	}

	/** The heights the planner's mode gives each cell. */
	static int[] heights(SceneMap map, ShapeParams params) {
		boolean[] solid = Structures.solidTiles(map);
		int[] units = Structures.units(map, solid);
		return Structures.classifiedHeights(map, units, params.interiorWall());
	}

	/** The fold rule over every run of {@code solid}, with per-cell heights. */
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
}
