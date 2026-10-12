package dev.kantobridge.builder.shape;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.plan.Box3;
import dev.kantobridge.builder.plan.HeightRules;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.shape.dressing.Apron;
import dev.kantobridge.builder.shape.dressing.Dress;
import dev.kantobridge.builder.shape.dressing.Dressing;
import dev.kantobridge.builder.shape.geom.CellUnits;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.Scene;
import dev.kantobridge.scene.SceneAtlas;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.scene.profile.Profile;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.ShapeIndex;
import dev.kantobridge.world.ShapeMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;

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
	 * @param floor     per art tile, the tile the floor block wears (a unit's ground tile), -1 for its own
	 * @param decor     per art tile, bit k set when block k (from feet level) is a {@code decor} carrier (a tuft)
	 * @param units     the map's cell units (open state), in scan order
	 * @param overlay   per art tile of a swap site's cell, its closed column bottom-up from feet level as an overlay
	 *                  draws it (V5); null elsewhere
	 * @param templates the map's structure templates (V7), in region order
	 * @param wallTile  a dressed interior's dominant wall tile, which its ring and ceiling wear (V7); -1 when not dressed
	 * @param ceiling   a dressed interior's ceiling key (V7), null when not dressed
	 * @param hung      per art tile, its hangings' keys by level from feet, null where none (docs/specs/CAVES_AND_WOODS_PLAN.md
	 *                  §3): decoration outside {@code open}, so the self-check never sees it
	 * @param hungMask  per art tile, bit k set when a hanging takes level k: a {@code decor} carrier at feet + k
	 * @param hungLeaves per art tile, bit k set when that carrier is {@code decor_leaves} (a wood's canopy and vines, CW2)
	 * @param curtain   the leaf curtain an open-sky wood's enclosure ring carries above its top (CW2), null for none
	 * @param apron     a canopy wood's apron past its edge, in vanilla blocks (CW3; it replaces the curtain), null for none
	 */
	public record MapShapes(String map, ShapeKey[][] open, ShapeKey[][] closed, int[] blocks, int[] shapeMask, int[] floor, int[] decor,
			List<Units.Cell> units, ShapeKey[][] overlay, List<Templates.Placed> templates, int wallTile, @Nullable ShapeKey ceiling,
			ShapeKey[][] hung, int[] hungMask, int[] hungLeaves, @Nullable ShapeKey curtain, Apron.@Nullable Plan apron) {
		/** The plan of a map without Tier B (no floor change, no decor, no unit, no overlay). */
		public MapShapes(String map, ShapeKey[][] open, ShapeKey[][] closed, int[] blocks, int[] shapeMask) {
			this(map, open, closed, blocks, shapeMask, filled(blocks.length, -1), new int[blocks.length], List.of(), new ShapeKey[blocks.length][],
					List.of(), -1, null, new ShapeKey[blocks.length][], new int[blocks.length], new int[blocks.length], null, null);
		}

		/** The tile the floor block of art tile {@code i} wears, given its own. */
		public int floorTile(int i, int own) {
			return this.floor[i] >= 0 ? this.floor[i] : own;
		}
	}

	/** One state's plan of a map. */
	record Columns(ShapeKey[][] keys, int[] blocks, int[] shapeMask, int[] floor, int[] decor, List<Units.Cell> units,
			List<Structures.MeasuredRun> runs, List<Templates.Placed> templates, int wallTile, @Nullable ShapeKey ceiling, ShapeKey[][] hung,
			int[] hungMask, int[] hungLeaves, @Nullable ShapeKey curtain) {
		Columns(ShapeKey[][] keys, int[] blocks, int[] shapeMask) {
			this(keys, blocks, shapeMask, List.of());
		}

		Columns(ShapeKey[][] keys, int[] blocks, int[] shapeMask, List<Structures.MeasuredRun> runs) {
			this(keys, blocks, shapeMask, filled(blocks.length, -1), new int[blocks.length], List.of(), runs, List.of(), -1, null,
					new ShapeKey[blocks.length][], new int[blocks.length], new int[blocks.length], null);
		}
	}

	/** The levels a placement's hangings may take: under its ceiling, or (open sky, the surface) any. */
	public static int hangTop(MapPlacement placement) {
		return placement.kind() == MapPlacement.Kind.INTERIOR && !WorldPlan.openSky(placement) ? WorldPlan.CEILING_ABOVE_FEET : 31;
	}

	/** A column's hanging mask as built: all of it when every level lies below {@code top}, else none. */
	public static int hungFits(int mask, int top) {
		return top >= 31 || mask >>> top == 0 ? mask : 0;
	}

	static int[] filled(int n, int value) {
		int[] out = new int[n];
		Arrays.fill(out, value);
		return out;
	}

	/** Plans a world's shapes with no position taken and no void fill (tests). */
	public static ShapePlan plan(Scene scene, Collection<MapPlacement> placements, ShapeParams params) {
		return plan(scene, placements, params, Occupancy.NONE);
	}

	/** Plans a world's shapes without the probe of PD3. */
	public static ShapePlan plan(Scene scene, Collection<MapPlacement> placements, ShapeParams params, Occupancy taken) {
		return plan(scene, placements, params, taken, null);
	}

	/**
	 * Plans a world's shapes.
	 *
	 * @param placements the maps the build places (their shape map positions)
	 * @param taken      the positions the builder's story blocks take, and the surface's void fill
	 * @param probe      asks the builder's own art whether a repeating cell stands as a unit (PD3), or null
	 */
	public static ShapePlan plan(Scene scene, Collection<MapPlacement> placements, ShapeParams params, Occupancy taken,
			@Nullable UnitProbe probe) {
		long started = System.nanoTime();
		List<String> atlases = new ArrayList<>(new TreeSet<>(scene.atlases().keySet()));
		TreeSet<String> paletteSet = new TreeSet<>();
		for (SceneMap map : scene.maps().values()) {
			paletteSet.add(map.palette());
		}
		List<String> palettes = new ArrayList<>(paletteSet);
		Map<String, MapShapes> shapes = new TreeMap<>();
		List<ShapeIndex.Use> uses = new ArrayList<>();
		Map<String, JsonObject> templates = new TreeMap<>();
		Map<String, Integer> unitSources = new TreeMap<>();
		for (SceneMap map : scene.maps().values()) {
			int atlas = atlases.indexOf(map.atlas());
			if (atlas < 0) {
				continue;
			}
			MapShapes planned = params.profileUnits()
					? planMap(map, scene.atlas(map.atlas()), atlas, params, scene.profileTileset(map.tileset()), probe, unitSources)
					: planMap(map, scene.atlas(map.atlas()), atlas, params);
			shapes.put(map.id(), planned);
			for (Templates.Placed template : planned.templates()) {
				templates.putIfAbsent(template.sig(), template.entry());
			}
			if (planned.ceiling() != null) {
				uses.add(new ShapeIndex.Use(planned.ceiling(), map.palette()));
			}
			if (planned.curtain() != null) {
				uses.add(new ShapeIndex.Use(planned.curtain(), map.palette()));
			}
			for (ShapeKey key : borderUnitKeys(map, scene.atlas(map.atlas()), atlas, params)) {
				uses.add(new ShapeIndex.Use(key, map.palette()));
			}
			for (ShapeKey[][] state : new ShapeKey[][][] {planned.open(), planned.closed(), planned.overlay(), planned.hung()}) {
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
		ShapeIndex index = ShapeIndex.of(params.planner(), params.toJson(), atlases, palettes, uses, Templates.indexEntries(templates));

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
		long decorSkipped = 0;
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
			if (planned.ceiling() != null && placement.kind() == MapPlacement.Kind.INTERIOR && !WorldPlan.openSky(placement)) {
				// A dressed interior's ceiling (V7), over the map's footprint.
				int ceiling = index.shapeId(planned.ceiling(), map.palette());
				for (int ty = 0; ty < map.artRows(); ty++) {
					for (int tx = 0; tx < cols; tx++) {
						grid.set(placement.x() + tx, placement.y() + WorldPlan.CEILING_ABOVE_FEET, placement.z() + ty, ceiling);
						shaped++;
					}
				}
			}
			for (int ty = 0; ty < map.artRows(); ty++) {
				for (int tx = 0; tx < cols; tx++) {
					ShapeKey[] column = planned.open()[ty * cols + tx];
					if (column == null) {
						continue;
					}
					boolean story = taken.storyAt(map.id(), map.cellIndexOfTile(tx, ty));
					if (story && column.length > 0 && column[0] != null && column[0].kind() == ShapeKey.Kind.CELL && column[0].variant() != null
							&& column[0].variant().contains("_stair")) {
						// A stair (P3) whose bottom block a story block takes would float: it stays out (counted).
						decorSkipped++;
						continue;
					}
					// Under a ceiling a column stops below it (an open-air interior has the ceiling's level too, P3).
					int top = placement.kind() == MapPlacement.Kind.INTERIOR && !WorldPlan.openSky(placement) ? WorldPlan.CEILING_ABOVE_FEET
							: column.length;
					for (int k = 0; k < column.length && k < top; k++) {
						if (column[k] == null) {
							continue;
						}
						if (k == 0 && story) {
							// The story block keeps the feet level; a tuft there is skipped (and counted).
							decorSkipped += (planned.decor()[ty * cols + tx] & 1) != 0 ? 1 : 0;
							continue;
						}
						int id = index.shapeId(column[k], map.palette());
						grid.set(placement.x() + tx, placement.y() + k, placement.z() + ty, id);
						shaped++;
					}
				}
			}
			// The hangings (CW1, CW2): each on the decor carrier the builder adds at its level; under a ceiling, a column's
			// hangings stand only if all of them fit below it (a wood that kept its ceiling hangs no canopy and no vines).
			int hangTop = hangTop(placement);
			for (int ty = 0; ty < map.artRows(); ty++) {
				for (int tx = 0; tx < cols; tx++) {
					int mask = hungFits(planned.hungMask()[ty * cols + tx], hangTop);
					ShapeKey[] column = planned.hung()[ty * cols + tx];
					for (int k = 0; column != null && k < column.length; k++) {
						if ((mask >> k & 1) != 0 && column[k] != null) {
							grid.set(placement.x() + tx, placement.y() + k, placement.z() + ty, index.shapeId(column[k], map.palette()));
							shaped++;
						}
					}
				}
			}
			if (planned.curtain() != null && WorldPlan.openSky(placement)) {
				// An open-sky wood's leaf curtain (CW2): its enclosure ring carries it above the ring's top, up to the canopy's.
				int curtain = index.shapeId(planned.curtain(), map.palette());
				for (int[] xz : WorldPlan.ringColumns(placement)) {
					for (int k = dev.kantobridge.builder.shape.dressing.Canopy.CURTAIN_FROM; k < dev.kantobridge.builder.shape.dressing.Canopy.TOP_LEVEL;
							k++) {
						grid.set(xz[0], placement.y() + k, xz[1], curtain);
						shaped++;
					}
				}
			}
		}
		// ---- overlays: each tile story cell's closed columns (V5) ----
		List<ShapeMap.Overlay> overlays = new ArrayList<>();
		Map<String, ShapePlan.Closed> closedColumns = new TreeMap<>();
		Map<String, MapPlacement> placed = new TreeMap<>();
		for (MapPlacement placement : placements) {
			placed.put(placement.id(), placement);
		}
		for (Occupancy.TileCell cell : params.storyArt() ? taken.tileCells() : List.<Occupancy.TileCell>of()) {
			MapShapes planned = shapes.get(cell.map());
			SceneMap map = scene.map(cell.map());
			MapPlacement placement = placed.get(cell.map());
			if (planned == null || map == null || placement == null) {
				continue;
			}
			int per = map.perCell();
			int cols = map.artCols();
			int[][] columns = new int[per * per][];
			int[] heights = new int[per * per];
			boolean leaves = false;
			boolean complete = per * per == 4;
			for (int dz = 0; dz < per && complete; dz++) {
				for (int dx = 0; dx < per; dx++) {
					ShapeKey[] keys = planned.overlay()[(cell.cy() * per + dz) * cols + cell.cx() * per + dx];
					if (keys == null) {
						complete = false;
						break;
					}
					int[] ids = new int[keys.length];
					for (int k = 0; k < keys.length; k++) {
						ids[k] = index.shapeId(keys[k], map.palette());
						leaves |= keys[k].kind() == ShapeKey.Kind.CELL && CellUnits.Context.parse(keys[k].variant()).tree();
					}
					columns[dz * per + dx] = ids;
					heights[dz * per + dx] = keys.length;
				}
			}
			if (!complete) {
				// A tile cell outside every swap site (not planned by a front end): its block entity's renderer draws it.
				continue;
			}
			overlays.add(new ShapeMap.Overlay(cell.key(), placement.x() + cell.cx() * per, placement.y(), placement.z() + cell.cy() * per, columns));
			closedColumns.put(cell.key(), new ShapePlan.Closed(heights, leaves));
		}
		// ---- the void fill's ring: border trees as units within hullRing of a map body (V4) ----
		Map<Integer, ShapePlan.VoidUnit> voidUnits = new TreeMap<>();
		Occupancy.VoidFill fill = taken.voidFill();
		if (params.tierB() && fill != null && surfaceGrid != null) {
			shaped += voidUnits(scene, placements, params, index, atlases, fill, surfaceGrid, voidUnits);
		}
		List<ShapeMap.Grid> built = new ArrayList<>();
		for (ShapeMap.GridBuilder grid : grids) {
			built.add(grid.build());
		}
		ShapeMap map = new ShapeMap(ShapeMap.NO_HASH, index.hash(), built, overlays);
		Map<ShapeKey.Kind, Integer> byKind = new EnumMap<>(ShapeKey.Kind.class);
		for (ShapeKey key : index.geoms()) {
			byKind.merge(key.kind(), 1, Integer::sum);
		}
		return new ShapePlan(params, index, map, shapes, byKind, shaped, (System.nanoTime() - started) / 1_000_000L, decorSkipped, fill,
				voidUnits, closedColumns, unitSources);
	}

	/** The context of the border's units: outdoors, no walkable side, repeating, foliage (the void tells no more). */
	static final CellUnits.Context BORDER_UNIT = new CellUnits.Context(0, false, true, false, true);

	/**
	 * The keys of a map's border units, from the whole export: one unit per cell of the drawn border block of an outdoor
	 * surface map (the void fill's ring wears them, V4), on the map's most frequent ground tile.
	 */
	static List<ShapeKey> borderUnitKeys(SceneMap map, @Nullable SceneAtlas sceneAtlas, int atlas, ShapeParams params) {
		List<ShapeKey> out = new ArrayList<>();
		int per = map.perCell();
		int side = map.borderSize();
		if (!params.tierB() || sceneAtlas == null || !map.surface() || !map.env().outdoor() || map.border() == null || per != ShapeTable.UNIT_CELL
				|| side % per != 0) {
			return out;
		}
		int ground = Units.mapGround(map, sceneAtlas.grassTiles(), sceneAtlas.frameTiles());
		for (int qy = 0; qy < side; qy += per) {
			for (int qx = 0; qx < side; qx += per) {
				int[] tiles = new int[per * per];
				for (int dy = 0; dy < per; dy++) {
					for (int dx = 0; dx < per; dx++) {
						tiles[dy * per + dx] = map.borderTile(qx + dx, qy + dy);
					}
				}
				out.addAll(List.of(Units.keys(atlas, tiles, ground, BORDER_UNIT.variant(), per)));
			}
		}
		return out;
	}

	/**
	 * Plans the void fill's ring: every void cell within {@code hullRing} cells of a map body whose owner draws a border
	 * becomes a unit of that border's cell (keys from {@link #borderUnitKeys}). Returns the positions it shaped.
	 */
	private static long voidUnits(Scene scene, Collection<MapPlacement> placements, ShapeParams params, ShapeIndex index, List<String> atlases,
			Occupancy.VoidFill fill, ShapeMap.GridBuilder grid, Map<Integer, ShapePlan.VoidUnit> out) {
		Map<String, MapPlacement> byId = new TreeMap<>();
		for (MapPlacement placement : placements) {
			byId.put(placement.id(), placement);
		}
		int[] distance = fill.distances();
		// Per owner: its ground tile, or null when its border has no units.
		Map<String, Integer> grounds = new TreeMap<>();
		long shaped = 0;
		for (int cell = 0; cell < distance.length; cell++) {
			String owner = fill.owner(cell);
			if (owner == null || distance[cell] > params.hullRing()) {
				continue;
			}
			SceneMap map = scene.map(owner);
			MapPlacement placement = byId.get(owner);
			if (map == null || placement == null) {
				continue;
			}
			SceneAtlas sceneAtlas = scene.atlas(map.atlas());
			Integer ground = grounds.computeIfAbsent(owner, k -> borderUnitKeys(map, sceneAtlas, 0, params).isEmpty() ? null
					: Units.mapGround(map, sceneAtlas.grassTiles(), sceneAtlas.frameTiles()));
			if (ground == null) {
				continue;
			}
			int per = map.perCell();
			int x0 = fill.minX() + (cell % fill.w()) * fill.cellBlocks();
			int z0 = fill.minZ() + (cell / fill.w()) * fill.cellBlocks();
			int[] tiles = new int[per * per];
			for (int dy = 0; dy < per; dy++) {
				for (int dx = 0; dx < per; dx++) {
					tiles[dy * per + dx] = map.borderTile(x0 - placement.x() + dx, z0 - placement.z() + dy);
				}
			}
			ShapeKey[] keys = Units.keys(atlases.indexOf(map.atlas()), tiles, ground, BORDER_UNIT.variant(), per);
			int[] ids = new int[keys.length];
			boolean known = true;
			for (int b = 0; b < keys.length; b++) {
				ids[b] = index.shapeId(keys[b], map.palette());
				known &= ids[b] != 0;
			}
			if (!known) {
				// A cell off the border block's grid (never with cell-aligned placements) keeps its void column.
				continue;
			}
			for (int by = 0; by < per; by++) {
				for (int bz = 0; bz < per; bz++) {
					for (int bx = 0; bx < per; bx++) {
						grid.set(x0 + bx, placement.y() + by, z0 + bz, ids[CellUnits.block(per, bx, by, bz)]);
						shaped++;
					}
				}
			}
			out.put(cell, new ShapePlan.VoidUnit(owner, ground, distance[cell], keys[0]));
		}
		return shaped;
	}

	/** One map's keys and columns, open and closed. */
	static MapShapes planMap(SceneMap map, @Nullable SceneAtlas sceneAtlas, int atlas, ShapeParams params) {
		return planMap(map, sceneAtlas, atlas, params, null, null, new TreeMap<>());
	}

	/**
	 * One map's keys and columns, open and closed, with the profile's entry for its tileset (null: none), the probe of
	 * PD3 (null: none), and the unit tally of {@link Units#plan} (the open state's only).
	 */
	static MapShapes planMap(SceneMap map, @Nullable SceneAtlas sceneAtlas, int atlas, ShapeParams params, Profile.@Nullable Tileset tileset,
			@Nullable UnitProbe probe, Map<String, Integer> tally) {
		Columns open = columns(map, sceneAtlas, atlas, params, tileset, probe, tally);
		ShapeKey[][] closed = new ShapeKey[open.keys().length][];
		ShapeKey[][] overlay = new ShapeKey[open.keys().length][];
		if (map.closedTiles() != null) {
			// The closed state of the cells a swap site changes (a standing cut tree), planned on the map with every site
			// closed, exactly as the open state is; kept for the cells that change only.
			SceneMap shut = map.closedVariant();
			Columns all = columns(shut, sceneAtlas, atlas, params, tileset, probe, new TreeMap<>());
			int cols = map.artCols();
			int per = map.perCell();
			for (var site : map.swapSites()) {
				for (var cell : site.cells()) {
					for (int dy = 0; dy < per; dy++) {
						for (int dx = 0; dx < per; dx++) {
							int tx = cell.x() * per + dx;
							int ty = cell.y() * per + dy;
							int i = ty * cols + tx;
							closed[i] = all.keys()[i];
							if (params.storyArt()) {
								overlay[i] = closedColumn(shut, all, atlas, tx, ty);
							}
						}
					}
				}
			}
		}
		ShapeKey curtain = open.curtain();
		Apron.Plan apron = null;
		if (params.apron() && curtain != null) {
			// CW3: a canopy wood (the canopy found leaves, so it hung a curtain) gets its apron instead of the ring's curtain.
			Set<Integer> grass = sceneAtlas == null ? Set.of() : sceneAtlas.grassTiles();
			int ground = sceneAtlas == null ? -1 : Units.mapGround(map, grass, sceneAtlas.frameTiles());
			apron = Apron.plan(map, Apron.floorTile(map, grass, ground), tally);
			curtain = null;
		}
		return new MapShapes(map.id(), open.keys(), closed, open.blocks(), open.shapeMask(), open.floor(), open.decor(), open.units(), overlay,
				open.templates(), open.wallTile(), open.ceiling(), open.hung(), open.hungMask(), open.hungLeaves(), curtain, apron);
	}

	/**
	 * The closed column of a swap-site cell's tile, bottom-up from feet level, as its overlay draws it (V5): every block of
	 * the closed state's column, a plain one made explicit (an overlay's carriers have no cube of their own to fall back
	 * to); a closed state that is walkable art (hull art over water) is a slab 1/16 high.
	 */
	static ShapeKey[] closedColumn(SceneMap shut, Columns all, int atlas, int tx, int ty) {
		int i = ty * shut.artCols() + tx;
		int tile = shut.tile(tx, ty);
		CellKind kind = shut.kindAtTile(tx, ty);
		if (kind != CellKind.SOLID && kind != CellKind.COUNTER) {
			return new ShapeKey[] {ShapeKey.slab(atlas, tile, 1)};
		}
		ShapeKey[] planned = all.keys()[i];
		ShapeKey[] out = new ShapeKey[Math.max(1, all.blocks()[i])];
		for (int k = 0; k < out.length; k++) {
			ShapeKey key = planned != null && k < planned.length ? planned[k] : null;
			out[k] = key != null ? key : ShapeKey.cube(atlas, tile, tile, tile, tile, tile, tile);
		}
		return out;
	}

	static Columns columns(SceneMap map, @Nullable SceneAtlas sceneAtlas, int atlas, ShapeParams params) {
		return columns(map, sceneAtlas, atlas, params, null, null, new TreeMap<>());
	}

	static Columns columns(SceneMap map, @Nullable SceneAtlas sceneAtlas, int atlas, ShapeParams params, Profile.@Nullable Tileset tileset,
			@Nullable UnitProbe probe, Map<String, Integer> tally) {
		if (!params.measured()) {
			return classified(map, atlas, params);
		}
		ProfileClasses classes = params.profileUnits() ? ProfileClasses.of(map, tileset, params.profileBoxes()) : null;
		// P2: the profile's band-table buildings claim their grids before Tier A (docs/specs/PROFILE_PLAN.md §5.5).
		boolean[] held = new boolean[map.artCols() * map.artRows()];
		List<Buildings.Claim> claims = params.profileBuildings() && tileset != null && sceneAtlas != null && map.perCell() == ShapeTable.UNIT_CELL
				? Buildings.claims(map, tileset.buildings(), held, tally) : List.of();
		// P3: the two-cell hulls (a canopy, a planter) take their cells as a claim does.
		List<BigHulls.Group> hulls = params.profileBoxes() && classes != null && sceneAtlas != null && map.perCell() == ShapeTable.UNIT_CELL
				? BigHulls.find(map, classes, held, tally) : List.of();
		boolean[] solid = classes == null ? Structures.solidTiles(map) : classes.structural();
		for (int i = 0; i < held.length; i++) {
			solid[i] &= !held[i];
		}
		// P3: the profile's class boxes leave Tier A; they are planned last, over the final columns (docs/specs/PROFILE_PLAN.md §5.6).
		String[] box = params.profileBoxes() && classes != null ? ClassBoxes.mark(map, classes, held, tally) : null;
		if (box != null) {
			for (int i = 0; i < solid.length; i++) {
				solid[i] &= box[i] == null;
			}
		}
		Columns columns = measured(map, atlas, params, solid);
		boolean cells = sceneAtlas != null && map.perCell() == ShapeTable.UNIT_CELL;
		int[] floor = filled(columns.blocks().length, -1);
		int[] decor = new int[columns.blocks().length];
		List<Units.Cell> units = new ArrayList<>();
		if (params.tierB() && cells) {
			// Tier B (kb-shapes/3): tufts and cell units over the measured columns.
			Units.plan(map, sceneAtlas, atlas, columns.keys(), columns.blocks(), columns.shapeMask(), floor, decor, units, classes,
					params.profileUnits() ? probe : null, tally, claims.isEmpty() && hulls.isEmpty() ? null : held);
		}
		List<Templates.Placed> templates = List.of();
		if (params.tierC() && cells) {
			// Tier C (kb-shapes/5): bounded buildings as templates over their Tier A columns.
			templates = Templates.plan(map, atlas, params, columns.runs(), columns.keys(), columns.blocks(), columns.shapeMask(), units,
					Units.mapGround(map, sceneAtlas.grassTiles(), sceneAtlas.frameTiles()));
		}
		if (!claims.isEmpty()) {
			// The buildings' templates, placed last: their keys and carriers hold their grids, doorways and overhangs.
			List<Templates.Placed> all = new ArrayList<>(templates);
			int ground = Units.mapGround(map, sceneAtlas.grassTiles(), sceneAtlas.frameTiles());
			for (Buildings.Claim claim : claims) {
				all.add(Buildings.place(map, atlas, claim, ground, columns.keys(), columns.blocks(), columns.shapeMask(), decor, floor, tally));
			}
			templates = all;
		}
		if (!hulls.isEmpty()) {
			List<Templates.Placed> all = new ArrayList<>(templates);
			int ground = Units.mapGround(map, sceneAtlas.grassTiles(), sceneAtlas.frameTiles());
			for (BigHulls.Group group : hulls) {
				int g = BigHulls.ground(map, group, ground, sceneAtlas.grassTiles(), sceneAtlas.frameTiles());
				all.add(BigHulls.place(map, atlas, group, g, columns.keys(), columns.blocks(), columns.shapeMask(), floor));
			}
			templates = all;
		}
		int wallTile = -1;
		ShapeKey ceiling = null;
		Dress dress = null;
		if (params.dressing()) {
			// Dressing (kb-shapes/5): interior walls up to the ceiling, the ceiling in the room's wall art.
			dress = Dressing.dress(map, new Dress(atlas, columns.keys(), columns.blocks(), columns.shapeMask(), columns.runs(), decor, tally)
					.withClasses(classes == null ? null : classes::at));
			wallTile = dress.wallTile();
			ceiling = dress.ceiling();
		}
		if (box != null) {
			int ground = sceneAtlas == null ? -1 : Units.mapGround(map, sceneAtlas.grassTiles(), sceneAtlas.frameTiles());
			ClassBoxes.plan(map, atlas, classes, box, held, ground, columns.keys(), columns.blocks(), columns.shapeMask(), floor, tally);
		}
		if (dress != null) {
			// The hangings (kb-shapes/9: cave rock), last, over the final columns.
			Dressing.hang(map, dress, params);
		}
		int n = columns.blocks().length;
		return new Columns(columns.keys(), columns.blocks(), columns.shapeMask(), floor, decor, List.copyOf(units), columns.runs(), templates,
				wallTile, ceiling, dress == null ? new ShapeKey[n][] : dress.hung(), dress == null ? new int[n] : dress.hungMask(),
				dress == null ? new int[n] : dress.hungLeaves(), dress == null ? null : dress.curtain());
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
		return measured(map, atlas, params, Structures.solidTiles(map));
	}

	/** {@link #measured} over the solid tiles {@code solid} (the profile's pinned units left out, P1). */
	static Columns measured(SceneMap map, int atlas, ShapeParams params, boolean[] solid) {
		int cols = map.artCols();
		int rows = map.artRows();
		int n = cols * rows;
		List<Structures.MeasuredRun> runs = Structures.measured(map, solid, params.maxUnit(), params.minRepeatRows(), params.interiorWall());
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
					if (params.profileUnits()) {
						// From P1 (the research's counter: one band): the counter's front panel stands, the rows above ride
						// its top, as Tier A folds a run one block high.
						keys[ty * cols + tx] = counterColumn(map, atlas, tx, ty);
					}
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
		return new Columns(keys, blocks, mask, runs);
	}

	/**
	 * A counter tile's block, folded as Tier A folds a run one block high: the south and side faces show the front tile
	 * of its column's counter run, the top the tile one row up (the run's north tile at the run's north end), the back
	 * the run's second tile. Null when that is the position's own plain cube.
	 */
	static ShapeKey @Nullable [] counterColumn(SceneMap map, int atlas, int tx, int ty) {
		int n = ty;
		while (n > 0 && map.kindAtTile(tx, n - 1) == CellKind.COUNTER) {
			n--;
		}
		int f = ty;
		while (f < map.artRows() - 1 && map.kindAtTile(tx, f + 1) == CellKind.COUNTER) {
			f++;
		}
		int own = map.tile(tx, ty);
		int up = ty - 1 >= n ? map.tile(tx, ty - 1) : map.tile(tx, n);
		int north = map.tile(tx, Math.min(f, n + 1));
		int front = map.tile(tx, f);
		if (up == own && north == own && front == own) {
			return null;
		}
		return new ShapeKey[] {ShapeKey.cube(atlas, up, up, north, front, front, front)};
	}

	/** The roof's height {@code s} rows behind the eave of a run {@code length} rows deep: a gable over the run's depth. */
	static int rise(int s, int length, int roofRise) {
		return Math.min(roofRise, 32 * Math.min(s, length - s) / length);
	}
}
