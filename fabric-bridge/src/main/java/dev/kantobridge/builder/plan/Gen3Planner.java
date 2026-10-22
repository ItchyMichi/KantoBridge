package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.G3Data;
import dev.kantobridge.builder.export.G3Map;
import dev.kantobridge.builder.export.G3Sites;
import dev.kantobridge.builder.export.G3Tiles;
import dev.kantobridge.builder.pack.G3PoolWriter;
import dev.kantobridge.builder.shape.ArtUnitProbe;
import dev.kantobridge.builder.shape.ShapeParams;
import dev.kantobridge.builder.shape.ShapePlan;
import dev.kantobridge.builder.shape.ShapePlanner;
import dev.kantobridge.scene.FrontEnds;
import dev.kantobridge.scene.GameFrontEnd;
import dev.kantobridge.scene.Scene;
import dev.kantobridge.scene.gen3.QuarterPages;
import dev.kantobridge.world.DataReader;
import dev.kantobridge.world.MapLayout;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Plans a Gen 3 build (MG7, docs/specs/GEN3_BRIDGE_PLAN.md §6): {@link WorldPlanner#plan} hands a Gen 3 export here, and
 * Gen 1's path stays as it was. The world is flat (§10 E8):
 *
 * <ul>
 * <li><b>Surface:</b> the maps of the export's layout ({@code g3layout}: the engine's own placement, in cells from the
 * root, BFS over the connections), each at its origin × 2 blocks. Its loops that the GBA's local connections never close
 * (Hoenn has three) are counted as edge mismatches and overlaps, as Gen 1's are; where two maps overlap, a map's own
 * cells claim the void fill before padding.</li>
 * <li><b>Interiors:</b> every other map reached from the surface, breadth-first a layer at a time in id order, each map's
 * warps in order (0-based arrivals), then its connections (a dive or emerge leads to the same cells one above the other;
 * a map entered by a connection, like the Safari Zone's areas, is placed on its own near its neighbour), then the maps
 * only left by a warp, and the room a new game starts in (the moving truck, whose door is a dynamic warp: MG8) under the
 * root; placed under its door by {@link InteriorPacker} as Gen 1's interiors are. An outdoor map entered so gets the sky when the build
 * plans shapes.</li>
 * <li><b>Cells</b> by {@link G3Cells}; invisible walls on elevation mismatches ({@link PairEdges#elevation}).</li>
 * <li><b>Art:</b> every quarter the export's maps show, keyed by its page ({@link QuarterPages}), half-height on water;
 * baked, so one palette ({@link #catalog}).</li>
 * <li><b>Story cells</b> by {@link G3StoryCells}; <b>habitats</b> on the cells the engine rolls land encounters on, pools
 * from the land tables ({@link G3PoolWriter}); the spawn on the root as Gen 1's.</li>
 * <li><b>Changeable cells</b> (SE3, docs/specs/STORY_EVENTS_PLAN.md §4): every site of the {@code g3sites} export is
 * built in its most walkable state ({@link G3Built}: the maps are read with it applied) and gets a story cell of type
 * {@code cell}; the art catalogue lists every state's quarters.</li>
 * </ul>
 */
final class Gen3Planner {
	/** The connection directions that join two maps side by side. */
	static final Set<String> CARDINAL = Set.of("north", "south", "east", "west");

	private Gen3Planner() {
	}

	/**
	 * Where an interior is entered: the parent map, the door's cell there, the interior's arrival cell (known when the
	 * door's warp names one of its warps), and how ({@code warp}, or a connection's direction).
	 */
	record Door(String parent, int x, int y, int arrivalX, int arrivalY, boolean arrivalKnown, String via) {
	}

	static WorldPlan plan(ExportBundle bundle, WorldPlanner.Options options, @Nullable RegionBuild region) throws BundleException, PlanException {
		Warnings warnings = new Warnings();
		String poolPrefix = region == null ? "" : region.poolPrefix();
		Map<String, G3Map> all = maps(bundle);
		if (!bundle.hasG3Layout()) {
			throw new PlanException("g3_layout_missing", "the Gen 3 export has no surface layout");
		}
		G3Data.Layout layout = bundle.g3layout();
		String root = layout.root();
		Set<String> surface = new TreeSet<>();
		for (String id : layout.maps().keySet()) {
			if (all.containsKey(id)) {
				surface.add(id);
			} else {
				warnings.warn("layout_connection_missing_map");
			}
		}
		Map<String, Door> doors = doors(all, surface, root, layout.start());

		List<String> requested = new ArrayList<>();
		if (options.maps() == null) {
			// Every map a player can be in: reached from the surface, or put there by a script (doors); the unused are left out.
			for (String id : all.keySet()) {
				if (surface.contains(id) || doors.containsKey(id)) {
					requested.add(id);
				} else {
					warnings.note("map_unreachable");
				}
			}
		} else {
			for (String id : new LinkedHashSet<>(options.maps())) {
				if (all.containsKey(id)) {
					requested.add(id);
				} else {
					warnings.warn("filter_unknown_map", Warnings.symbols("map", DataReader.MAP_ID.matcher(id).matches() ? id : null));
				}
			}
		}
		int mapsRequested = options.maps() == null ? requested.size() : new LinkedHashSet<>(options.maps()).size();
		Set<String> requestedSet = new HashSet<>(requested);
		G3Data.Encounters encounters = bundle.g3encounters();
		Set<String> wildMaps = encounters == null ? Set.of() : HabitatPlanner.wildMapsGen3(encounters);

		// ---- surface: the engine's own placement ----
		Map<String, MapPlacement> placements = new LinkedHashMap<>();
		int exportMismatches = 0;
		for (String id : requested) {
			if (!surface.contains(id)) {
				continue;
			}
			G3Data.Rect rect = layout.maps().get(id);
			G3Map map = all.get(id);
			if (rect.w() != map.w() || rect.h() != map.h()) {
				exportMismatches++;
			}
			placements.put(id, new MapPlacement(id, options.originX() + rect.x() * Scale.CELL_BLOCKS, Scale.SURFACE_Y,
					options.originZ() + rect.y() * Scale.CELL_BLOCKS, map.w(), map.h(), 0, MapPlacement.Kind.SURFACE, QuarterPages.PALETTE,
					WorldPlan.BIOME_OUTDOOR, false, null));
		}
		warnings.warn("layout_export_mismatch", exportMismatches);
		int[] seams = seams(all, layout, placements.keySet(), warnings);

		// ---- interiors: in rounds, each placed under its door ----
		List<InteriorPacker.Box> surfaceFootprints = new ArrayList<>();
		for (MapPlacement p : placements.values()) {
			surfaceFootprints.add(new InteriorPacker.Box(p.x(), p.z(), p.x() + p.blockWidth(), p.z() + p.blockDepth()));
		}
		InteriorPacker packer = new InteriorPacker(Scale.maxLayer(options.minBuildHeight()), surfaceFootprints);
		Box3 surfaceVolume = WorldPlan.surfaceVolume(placements.values());
		InteriorPacker.Box sky = !options.shapes() || surfaceVolume == null ? null
				: new InteriorPacker.Box(surfaceVolume.minX(), surfaceVolume.minZ(), surfaceVolume.maxX(), surfaceVolume.maxZ());
		// The 3D area (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D1): the caves and every complex a ladder or hole joins leave the
		// layers for an area of their own north of the region's dungeons, as Kanto's caves did (CAVES_3D_PLAN §3.3).
		Map<String, G3Tiles> pairs = new HashMap<>();
		Map<String, MapModel> interiorModels = new HashMap<>();
		Map<String, CaveMap> caveMaps = new LinkedHashMap<>();
		Set<String> caveIds = new LinkedHashSet<>();
		Set<String> outdoorIds = new HashSet<>();
		for (String id : requested) {
			Door door = doors.get(id);
			if (surface.contains(id) || door == null || !requestedSet.contains(door.parent())) {
				continue;
			}
			G3Map map = all.get(id);
			G3Tiles tiles = tiles(bundle, pairs, map.pair());
			MapModel model = G3Cells.model(map, tiles, true, warnings);
			interiorModels.put(id, model);
			caveMaps.put(id, CaveMap.gen3(map, tiles, model, all, new CaveMap.Door(door.parent(), door.x(), door.y(), door.arrivalX(), door.arrivalY()),
					QuarterPages.PALETTE, caveMaps.size(), bundle.game()));
			if (G3Cells.cave(map)) {
				caveIds.add(id);
			}
			if (G3Cells.outdoor(map)) {
				outdoorIds.add(id);
			}
		}
		Set<String> area3d = Area3d.select(caveMaps, caveIds, outdoorIds);
		// Their raised ground (D3, W4): Emerald's elevations as regions, each region as high as the stairs between them say;
		// and their ceilings (D5), so their floors stack as far apart as their tallest rooms need.
		java.util.function.Predicate<String> caveBiome = id -> G3Cells.cave(all.get(id)) || wildMaps.contains(id);
		dev.kantobridge.scene.ArtSource artSource = FrontEnds.artSource(bundle);
		ArtUnitProbe probe = new ArtUnitProbe(artSource);
		Map<String, MapLayout> raised = new HashMap<>();
		Map<String, Integer> heights = new HashMap<>();
		for (String id : area3d) {
			MapModel model = interiorModels.get(id);
			G3Map map = all.get(id);
			RaisedRegions.Result levels = RaisedRegions.gen3(model, map);
			warnings.note("cave_levels_" + levels.outcome(), Warnings.symbols("map", id));
			if (levels.layout() != null) {
				raised.put(id, levels.layout());
				warnings.note("cave_raised_cells", levels.raisedCells());
				warnings.note("cave_ramps", levels.layout().ramps().size());
			}
			int[] ceilings = Headroom.ceilings(model, levels.layout(), Headroom.blackFiller(model, probe, (cx, cy) -> !map.inMap(cx, cy)),
					Headroom.screenRows(true), caveBiome.test(id));
			heights.put(id, java.util.Arrays.stream(ceilings).max().orElse(Headroom.MIN));
		}
		CavePlanner.Result caveArea = Gen2Planner.placeArea3d(caveMaps, area3d, caveBiome, heights, options, surfaceVolume, warnings);
		for (MapPlacement p : caveArea.placements()) {
			placements.put(p.id(), p);
		}
		List<String> pending = new ArrayList<>();
		for (String id : requested) {
			if (surface.contains(id) || area3d.contains(id)) {
				continue;
			}
			Door door = doors.get(id);
			if (door == null) {
				// Only an explicit filter gets here (a full build leaves unreachable maps out): asked for, can't be placed.
				warnings.warn("map_unreachable", Warnings.symbols("map", id));
			} else if (!requestedSet.contains(door.parent())) {
				warnings.warn("interior_parent_not_built", Warnings.symbols("map", id, "parent", door.parent()));
			} else {
				pending.add(id);
			}
		}
		boolean progress = true;
		while (!pending.isEmpty() && progress) {
			progress = false;
			List<String> next = new ArrayList<>();
			for (String id : pending) {
				Door door = doors.get(id);
				MapPlacement parent = placements.get(door.parent());
				if (parent == null) {
					next.add(id);
					continue;
				}
				progress = true;
				MapPlacement placedAt = placeInterior(all.get(id), door, parent, packer, wildMaps.contains(id), sky, warnings);
				if (placedAt != null) {
					placements.put(id, placedAt);
				}
			}
			pending = next;
		}
		for (String id : pending) {
			warnings.warn("interior_parent_unplaced", Warnings.symbols("map", id, "parent", doors.get(id).parent()));
		}
		int unplaced = 0;
		for (String id : requested) {
			if (!placements.containsKey(id)) {
				unplaced++;
				warnings.detail("maps_unplaced", Warnings.symbols("map", id));
			}
		}

		// ---- cell models and their elevation walls ----
		Set<String> outdoorLevels = OutdoorLevels.fromProperty();
		List<PlacedMap> placed = new ArrayList<>();
		Map<String, MapLayout> mapLayouts = new TreeMap<>();
		int elevationEdges = 0;
		for (MapPlacement placement : placements.values()) {
			G3Map map = all.get(placement.id());
			boolean interior = placement.kind() == MapPlacement.Kind.INTERIOR;
			MapModel model = interior && interiorModels.containsKey(map.id()) ? interiorModels.get(map.id())
					: G3Cells.model(map, tiles(bundle, pairs, map.pair()), interior, warnings);
			PairEdges edges = PairEdges.elevation(model, map);
			elevationEdges += edges.edges();
			// The 3D area's maps are drawn as their rooms (D1, CAVES_3D_PLAN §3.5), on their raised ground (D3); an interior
			// under its ceilings (D5), its drawing stopping at the true map's edge
			List<MapLayout.Piece> pieces = caveArea.pieces().get(map.id());
			MapLayout rooms = pieces == null ? raised.get(map.id()) : WorldPlanner.withPieces(model, pieces, raised.get(map.id()));
			if (interior && !area3d.contains(map.id()) && !WorldPlan.openSky(placement)) {
				// A room on a layer stands on its raised floors too (D5): its elevations, its stairs cut through walls
				RaisedRegions.Result levels = RaisedRegions.gen3(model, map, true);
				warnings.note("room_levels_" + levels.outcome(), Warnings.symbols("map", map.id()));
				rooms = levels.layout();
			}
			if (OutdoorLevels.covers(outdoorLevels, map.id()) && (placement.kind() == MapPlacement.Kind.SURFACE || WorldPlan.openSky(placement))) {
				// The levels pilot (D3, D4): an outdoor map on its elevations' raised ground, 3 blocks a cell of stairs or face; with
				// its slopes marked, on its drawing's
				G3Tiles mapTiles = tiles(bundle, pairs, map.pair());
				boolean[] falls = RaisedRegions.gen3Falls(model, map, mapTiles);
				rooms = OutdoorLevels.plan(map.id(), model, RaisedRegions.gen3Stairs(model, map, mapTiles),
						slopes -> RaisedRegions.gen3Outdoor(model, map, slopes, falls), warnings);
			}
			rooms = WorldPlanner.withCeilings(placement, model, rooms, Headroom.blackFiller(model, probe, (cx, cy) -> !map.inMap(cx, cy)),
					Headroom.screenRows(true), !area3d.contains(map.id()), warnings);
			if (rooms != null) {
				mapLayouts.put(map.id(), rooms);
			}
			placed.add(new PlacedMap(placement, model, edges, rooms));
		}
		warnings.note("elevation_edges", elevationEdges);

		// ---- the chain (docs/specs/LEVELS_PIPELINE.md, the seams): each surface map's floor where its neighbour's ground leaves off ----
		if (MapChain.enabled()) {
			MapChain.Result chain = MapChain.note(MapChain.chain(placed), warnings);
			placements.putAll(MapChain.apply(placed, chain.bases()));
			// a short stair at each join a loop leaves open
			MapChain.Joins joins = MapChain.joins(placed, chain, warnings);
			placements.putAll(joins.placements());
			mapLayouts.putAll(joins.layouts());
		}

		// ---- surface void fill: a map's own cells claim before its padding ----
		Box3 surfaceBox = WorldPlan.surfaceVolume(placements.values());
		WorldPlanner.VoidFill fill = WorldPlanner.voidFill(placed, surfaceBox, (m, cx, cy) -> all.get(placed.get(m).id()).inMap(cx, cy), warnings);

		// ---- slot map: from the whole export ----
		ArtCatalog art = catalog(bundle, all, region, warnings);

		// ---- story cells (SE3: a site's states name slots of the slot map), habitats, pools ----
		SlotMap slotMap = art.slotMap();
		StoryCellTable storyCells = G3StoryCells.plan(placed, all, bundle.g3sites(), (pair, mid) -> {
			G3Tiles tiles = pairs.get(pair);
			return tiles == null ? null : tiles.quarters(mid);
		}, quarter -> {
			SlotMap.Slot slot = slotMap.slot(key(quarter, SlotMap.Shape.FULL, new HashMap<>()));
			return slot == null ? -1 : slot.index();
		}, warnings);
		Map<String, int[]> storyIndex = WorldPlanner.storyIndex(placed, storyCells);
		List<WorldIndex.Habitat> habitats = HabitatPlanner.planGen3(placed, wildMaps, warnings, poolPrefix);
		Map<String, String> pools = encounters == null ? Map.of() : G3PoolWriter.pools(encounters, wildMaps, poolPrefix, warnings);

		// ---- spawn and index ----
		WorldPlan.Spawn spawn = WorldPlanner.chooseSpawn(placed, root, requested, storyIndex);
		if (spawn == null) {
			warnings.warn("spawn_missing");
		}
		// The 3D area's lined-up ladders and holes, real shafts (HEIGHTS_ALL_GAMES_PLAN D2, W3)
		Map<String, MapModel> models = new HashMap<>();
		for (PlacedMap map : placed) {
			models.put(map.id(), map.model());
		}
		List<WorldIndex.Shaft> shafts = WorldPlanner.shafts(caveArea.shafts(), placements, mapLayouts, models);
		warnings.note("cave_shafts_built", shafts.size());
		WorldIndex index = new WorldIndex(bundle.game(), bundle.exportHash(), placements.values(), mapLayouts, art.slotMap().hash(), storyCells, habitats,
				List.of(), null, shafts);

		// ---- shapes (the voxel plan's §5.4): the Gen 3 front end's scene of the whole export, the placed maps' positions ----
		Scene scene = null;
		ShapePlan shapes = null;
		if (options.shapes()) {
			GameFrontEnd frontEnd = FrontEnds.forGame(bundle.game());
			if (frontEnd == null) {
				warnings.note("shapes_no_front_end");
			} else {
				scene = frontEnd.describe(bundle);
				shapes = ShapePlanner.plan(scene, placements.values(), ShapeParams.forScene(scene),
						WorldPlanner.occupancy(placed, storyIndex, storyCells, surfaceBox, fill.owner(), fill.voidCells()), probe,
						mapLayouts, shafts)
						.withIndexHash(index.hash());
			}
		}
		WorldPlanner.noteVisibleRows(shapes, warnings);
		return new WorldPlan(bundle.game(), bundle.exportHash(), options.originX(), options.originZ(), placed, surfaceBox, fill.owner(),
				fill.voidCells(), storyCells, storyIndex, art, spawn, index, pools, warnings, mapsRequested, unplaced, seams[0], seams[1], scene, shapes,
				caveArea.area(), region);
	}

	/** Every map of the export, by id, its changeable cells in their built states (SE3, {@link G3Built}). */
	static Map<String, G3Map> maps(ExportBundle bundle) throws BundleException {
		return G3Built.maps(bundle);
	}

	/** Maps no player ever enters (the decomp's leftovers): never placed by a script's rule. */
	static final Pattern UNUSED = Pattern.compile(".*(UNUSED|PROTOTYPE).*");

	/** {@link #doors(Map, Collection, String, G3Data.Start)} without a root: warps, connections and exits only. */
	static Map<String, Door> doors(Map<String, G3Map> all, Collection<String> surface) {
		return doors(all, surface, null, null);
	}

	/**
	 * Every interior's door: breadth-first from the surface, a layer at a time in id order; each map's warps in order (the
	 * first that leads into a map makes it that map's door; a dynamic warp leads nowhere known), then its connections to
	 * maps off the surface (dive and emerge, and the areas of an outdoor group entered by a warp). Then the maps only left
	 * by a warp (kind {@code exit}), under the cell their warp lands on; with a root, the room a new game starts in (kind
	 * {@code start}, {@link #startDoor}) and every other map a player can be put in by a script, a ferry or a dynamic
	 * warp (kind {@code script}: contest halls, the islands, secret bases), under the root's middle, each with the maps
	 * its warps reach; the {@link #UNUSED} ones are left out.
	 */
	static Map<String, Door> doors(Map<String, G3Map> all, Collection<String> surface, @Nullable String root, G3Data.@Nullable Start start) {
		Map<String, Door> out = new LinkedHashMap<>();
		Set<String> visited = new HashSet<>(surface);
		spread(all, out, visited, new TreeSet<>(surface));
		boolean startDone = false;
		boolean added = true;
		while (added) {
			added = exits(all, out, visited);
			if (added || root == null || !all.containsKey(root)) {
				continue;
			}
			if (!startDone) {
				startDone = true;
				Door door = startDoor(all, visited, out, root, start);
				if (door != null) {
					out.put(start.map(), door);
					visited.add(start.map());
					spread(all, out, visited, new TreeSet<>(Set.of(start.map())));
					added = true;
					continue;
				}
			}
			for (String id : new TreeSet<>(all.keySet())) {
				if (visited.contains(id) || UNUSED.matcher(id).matches()) {
					continue;
				}
				G3Map rootMap = all.get(root);
				G3Map head = all.get(id);
				G3Map.Warp arrival = head.warps().isEmpty() ? null : head.warps().get(0);
				out.put(id, new Door(root, rootMap.trueW() / 2, rootMap.trueH() / 2, arrival == null ? 0 : arrival.x(),
						arrival == null ? 0 : arrival.y(), true, "script"));
				visited.add(id);
				spread(all, out, visited, new TreeSet<>(Set.of(id)));
				added = true;
				break;
			}
		}
		return out;
	}

	/** Breadth-first from {@code layer} through warps and connections, a layer at a time in id order. */
	private static void spread(Map<String, G3Map> all, Map<String, Door> out, Set<String> visited, Collection<String> first) {
		List<String> layer = new ArrayList<>(first);
		while (!layer.isEmpty()) {
			Set<String> next = new TreeSet<>();
			for (String id : layer) {
				G3Map map = all.get(id);
				for (G3Map.Warp warp : map.warps()) {
					String dest = warp.to();
					if (dest == null || warp.dynamic() || !all.containsKey(dest) || !visited.add(dest)) {
						continue;
					}
					G3Map target = all.get(dest);
					boolean known = warp.warp() < target.warps().size();
					G3Map.Warp arrival = known ? target.warps().get(warp.warp()) : target.warps().isEmpty() ? null : target.warps().get(0);
					out.put(dest, new Door(id, warp.x(), warp.y(), arrival == null ? 0 : arrival.x(), arrival == null ? 0 : arrival.y(), known, "warp"));
					next.add(dest);
				}
				for (G3Map.Connection connection : map.connections()) {
					String dest = connection.map();
					if (!all.containsKey(dest) || !visited.add(dest)) {
						continue;
					}
					// The same cells one above the other (a dive's), or a neighbour placed near its map.
					out.put(dest, new Door(id, 0, 0, 0, 0, true, connection.dir()));
					next.add(dest);
				}
			}
			layer = new ArrayList<>(next);
		}
	}

	/**
	 * MG8: one round of the maps no warp leads into but whose own warp leads out into the placed ones (they may lead into
	 * each other): each under the cell its warp lands on, its warp's cell as the arrival, so the hidden player has
	 * somewhere to stand. True when one was added.
	 */
	private static boolean exits(Map<String, G3Map> all, Map<String, Door> out, Set<String> visited) {
		boolean added = false;
		for (String id : new TreeSet<>(all.keySet())) {
			if (visited.contains(id)) {
				continue;
			}
			for (G3Map.Warp warp : all.get(id).warps()) {
				G3Map parent = warp.to() == null || warp.dynamic() || !visited.contains(warp.to()) ? null : all.get(warp.to());
				if (parent == null || warp.warp() >= parent.warps().size()) {
					continue;
				}
				G3Map.Warp landing = parent.warps().get(warp.warp());
				out.put(id, new Door(parent.id(), landing.x(), landing.y(), warp.x(), warp.y(), true, "exit"));
				visited.add(id);
				added = true;
				break;
			}
		}
		return added;
	}

	/**
	 * MG8: the door of the room a new game starts in when nothing else reaches it (the moving truck's door is a dynamic
	 * warp): the root's middle cell, so it is placed under the root; null when it is reached or unknown.
	 */
	static @Nullable Door startDoor(Map<String, G3Map> all, Collection<String> surface, Map<String, Door> doors, String root,
			G3Data.@Nullable Start start) {
		if (start == null || !all.containsKey(start.map()) || surface.contains(start.map()) || doors.containsKey(start.map())
				|| !all.containsKey(root)) {
			return null;
		}
		G3Map rootMap = all.get(root);
		return new Door(root, rootMap.trueW() / 2, rootMap.trueH() / 2, start.x(), start.y(), true, "start");
	}

	/**
	 * {edge mismatches, overlaps} among the built surface maps: a connection whose neighbour isn't where the engine's
	 * neighbour arithmetic puts it (connections.lua, Map.computeWorld: north at offset, -its height; south at offset, this
	 * height; west at -its width, offset; east at this width, offset), and pairs of maps whose own cells overlap.
	 */
	static int[] seams(Map<String, G3Map> all, G3Data.Layout layout, Collection<String> built, Warnings warnings) {
		int mismatches = 0;
		for (String id : built) {
			G3Map map = all.get(id);
			G3Data.Rect at = layout.maps().get(id);
			for (G3Map.Connection connection : map.connections()) {
				G3Data.Rect other = layout.maps().get(connection.map());
				G3Map dest = all.get(connection.map());
				if (!CARDINAL.contains(connection.dir()) || !built.contains(connection.map()) || other == null || dest == null) {
					continue;
				}
				int ex;
				int ey;
				switch (connection.dir()) {
					case "north" -> {
						ex = at.x() + connection.offset();
						ey = at.y() - dest.h();
					}
					case "south" -> {
						ex = at.x() + connection.offset();
						ey = at.y() + map.h();
					}
					case "west" -> {
						ex = at.x() - dest.w();
						ey = at.y() + connection.offset();
					}
					default -> {
						ex = at.x() + map.w();
						ey = at.y() + connection.offset();
					}
				}
				if (ex != other.x() || ey != other.y()) {
					mismatches++;
					warnings.detail("layout_edge_mismatch", Warnings.symbols("map", id, "other", connection.map()));
				}
			}
		}
		warnings.warn("layout_edge_mismatch", mismatches);
		List<String> ids = new ArrayList<>(built);
		int overlaps = 0;
		for (int i = 0; i < ids.size(); i++) {
			for (int j = i + 1; j < ids.size(); j++) {
				G3Data.Rect a = layout.maps().get(ids.get(i));
				G3Data.Rect b = layout.maps().get(ids.get(j));
				G3Map ma = all.get(ids.get(i));
				G3Map mb = all.get(ids.get(j));
				if (a.x() < b.x() + mb.trueW() && b.x() < a.x() + ma.trueW() && a.y() < b.y() + mb.trueH() && b.y() < a.y() + ma.trueH()) {
					overlaps++;
					warnings.detail("layout_overlap", Warnings.symbols("map", ids.get(i), "other", ids.get(j)));
				}
			}
		}
		warnings.warn("layout_overlap", overlaps);
		return new int[] {mismatches, overlaps};
	}

	/**
	 * @param wild the map's wild Pokémon come from a habitat pool: it gets the cave biome, which the interior spawn guard
	 *             leaves alone, as Gen 1's
	 * @param sky  the surface's volume an outdoor map keeps out from under to see the sky, or null
	 */
	private static @Nullable MapPlacement placeInterior(G3Map map, Door door, MapPlacement parent, InteriorPacker packer, boolean wild,
			InteriorPacker.@Nullable Box sky, Warnings warnings) {
		if (!door.arrivalKnown()) {
			warnings.warn("interior_arrival_missing", Warnings.symbols("map", map.id()));
		}
		int wantX = parent.x() + Scale.cellToBlock(door.x()) - Scale.cellToBlock(door.arrivalX());
		int wantZ = parent.z() + Scale.cellToBlock(door.y()) - Scale.cellToBlock(door.arrivalY());
		int blockW = Scale.cellToBlock(map.w());
		int blockD = Scale.cellToBlock(map.h());
		Map<String, String> detail = Warnings.symbols("map", map.id(), "parent", parent.id(), "parent_layer", Integer.toString(parent.layer()));
		if (sky != null && G3Cells.outdoor(map)) {
			InteriorPacker.Spot spot = packer.placeOpenSky(wantX, wantZ, blockW, blockD, parent.layer() + 1, sky);
			if (spot != null) {
				warnings.note("open_air_sky", detail);
				return new MapPlacement(map.id(), spot.x(), Scale.feetY(spot.layer()), spot.z(), map.w(), map.h(), spot.layer(),
						MapPlacement.Kind.INTERIOR, QuarterPages.PALETTE, WorldPlan.BIOME_OUTDOOR, map.dark(), parent.id());
			}
			warnings.note("open_air_kept_ceiling", detail);
		}
		InteriorPacker.Spot spot = packer.place(wantX, wantZ, blockW, blockD, parent.layer() + 1);
		if (spot == null) {
			warnings.warn("interior_unplaced", detail);
			return null;
		}
		if (spot.deep()) {
			warnings.note("interior_deep_spill", detail);
		} else if (spot.moved()) {
			warnings.note("interior_moved_sideways", detail);
		}
		boolean cave = wild || G3Cells.cave(map);
		// item 8: a cave that needs Flash is dark (no lights; Flash gives Night Vision, FlashService)
		return new MapPlacement(map.id(), spot.x(), Scale.feetY(spot.layer()), spot.z(), map.w(), map.h(), spot.layer(),
				MapPlacement.Kind.INTERIOR, QuarterPages.PALETTE, cave ? WorldPlan.BIOME_CAVE : WorldPlan.BIOME_INTERIOR, map.dark(), parent.id());
	}

	/**
	 * The art catalogue of the whole export (whatever the build's filter, as Gen 1's): every quarter every map's cells show
	 * (half-height where the cell is water) and its border's, keyed by quarter page and index, palette
	 * {@link QuarterPages#PALETTE}; every page {@link QuarterPages#PER_ROW} quarters wide. SE3: and every state of every
	 * changeable cell ({@code g3sites}), full-shape, the art its story cell draws.
	 */
	static ArtCatalog catalog(ExportBundle bundle, Map<String, G3Map> all, @Nullable RegionBuild region, Warnings warnings)
			throws BundleException, PlanException {
		Set<SlotMap.Key> keys = new HashSet<>();
		Map<String, Integer> perRow = new TreeMap<>();
		Map<String, G3Tiles> pairs = new HashMap<>();
		// Anomalies in maps are the planner's to report for the maps it places; here they would only repeat.
		Warnings scratch = new Warnings();
		for (G3Map map : all.values()) {
			if (!bundle.has("g3tiles", map.pair())) {
				warnings.note("catalog_pair_missing");
				continue;
			}
			MapModel model = G3Cells.model(map, tiles(bundle, pairs, map.pair()), true, scratch);
			for (int ty = 0; ty < model.cellHeight() * 2; ty++) {
				for (int tx = 0; tx < model.cellWidth() * 2; tx++) {
					boolean half = model.kind(tx / 2, ty / 2) == MapModel.Kind.WATER;
					keys.add(key(model.tile(tx, ty), half ? SlotMap.Shape.HALF : SlotMap.Shape.FULL, perRow));
				}
			}
			for (int ty = 0; ty < map.border().h() * 2; ty++) {
				for (int tx = 0; tx < map.border().w() * 2; tx++) {
					keys.add(key(model.borderTile(tx, ty), SlotMap.Shape.FULL, perRow));
				}
			}
			G3Tiles tiles = tiles(bundle, pairs, map.pair());
			for (G3Sites.Site site : bundle.g3sites().of(map.id())) {
				List<G3Sites.State> states = new ArrayList<>(site.states());
				states.add(site.own());
				for (G3Sites.State state : states) {
					int[] quarters = tiles.quarters(state.metatile());
					for (int q = 0; quarters != null && q < quarters.length; q++) {
						keys.add(key(quarters[q], SlotMap.Shape.FULL, perRow));
					}
				}
			}
		}
		try {
			return ArtCatalog.baked(keys, perRow, region);
		} catch (PlanException e) {
			if (region == null && "slot_map.full".equals(e.code())) {
				// A real Gen 3 game needs a region's 80 families (E7); a legacy world has Kanto's 16.
				throw new PlanException("slot_map.full", e.getMessage() + "; a Gen 3 export is built as a region");
			}
			throw e;
		}
	}

	private static SlotMap.Key key(int quarter, SlotMap.Shape shape, Map<String, Integer> perRow) {
		String page = QuarterPages.page(quarter);
		perRow.putIfAbsent(page, QuarterPages.PER_ROW);
		return new SlotMap.Key(page, QuarterPages.PALETTE, QuarterPages.index(quarter), shape);
	}

	private static G3Tiles tiles(ExportBundle bundle, Map<String, G3Tiles> pairs, String pair) throws BundleException {
		G3Tiles tiles = pairs.get(pair);
		if (tiles == null) {
			tiles = bundle.g3tiles(pair);
			pairs.put(pair, tiles);
		}
		return tiles;
	}
}
