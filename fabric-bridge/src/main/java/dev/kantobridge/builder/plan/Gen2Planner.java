package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportSwaps;
import dev.kantobridge.builder.export.G2Data;
import dev.kantobridge.builder.export.G2Map;
import dev.kantobridge.builder.export.G2Tiles;
import dev.kantobridge.builder.export.G3Data;
import dev.kantobridge.builder.pack.G2PoolWriter;
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
 * Plans a Gen 2 build (MG9, docs/specs/GEN2_BRIDGE_PLAN.md §7): {@link WorldPlanner#plan} hands a Gen 2 export here; Gen 1's
 * and Gen 3's paths stay as they were. Gen 3's machinery (baked art on the quarter pages, the interior packer, region
 * builds, the shape planner) with Gen 1's block-level parts (swap sites by block with closed art). The world is flat, as
 * Hoenn's (E8):
 *
 * <ul>
 * <li><b>Surface:</b> each surface area of the export ({@code g2layout}: Johto from New Bark Town, Crystal's Kanto from
 * Pallet Town, joined to Johto by no connection) at its block origins × 2 cells; Kanto east of Johto with a sea gap of
 * {@link #AREA_GAP_CELLS} cells, their tops aligned (J3). Loops that don't close on a flat plane are counted as edge
 * mismatches, as Gen 1's and 3's are.</li>
 * <li><b>Interiors:</b> every other map reached from the surface, breadth-first a layer at a time in id order through the
 * warps (0-based arrivals; the remembered warp, -1, leads nowhere known) and connections, then the maps only left by a
 * warp, the room a new game starts in, and every other map a script can put a player in, under the root; placed under
 * its door by {@link InteriorPacker}. The one shared Pokémon Center upstairs is placed under the first center whose stairs
 * reach it. An outdoor map entered so gets the sky when the build plans shapes.</li>
 * <li><b>Cells</b> by {@link G2Cells}: the meanings the export gives each collision byte, swap sites built open;
 * invisible walls on the one-way edges (side walls and buoys, {@link G2Cells#oneWay}).</li>
 * <li><b>Art:</b> every baked tile the maps show, their borders and their sites' closed states, keyed by its page
 * ({@link QuarterPages}, the pages Gen 2 bakes as Gen 3's), half-height on water.</li>
 * <li><b>Story cells</b> by {@link G2StoryCells}; <b>habitats</b> on the cells the engine's encounter gate rolls, pools from
 * the grass tables with the three times of day merged (J6, {@link G2PoolWriter}); the spawn on Johto's root.</li>
 * </ul>
 */
final class Gen2Planner {
	/** The sea between Johto and Crystal's Kanto, in cells (J3). */
	static final int AREA_GAP_CELLS = 32;
	/** Maps no player ever enters (left in the data): never placed by a script's rule. */
	static final Pattern UNUSED = Pattern.compile(".*(UNUSED|TEST|DEBUG).*");

	private Gen2Planner() {
	}

	/** Where an interior is entered: as {@link Gen3Planner.Door}, in cells. */
	record Door(String parent, int x, int y, int arrivalX, int arrivalY, boolean arrivalKnown, String via) {
	}

	static WorldPlan plan(ExportBundle bundle, WorldPlanner.Options options, @Nullable RegionBuild region) throws BundleException, PlanException {
		Warnings warnings = new Warnings();
		String poolPrefix = region == null ? "" : region.poolPrefix();
		Map<String, G2Map> all = maps(bundle);
		List<G2Data.Layout> layouts = bundle.g2layouts();
		if (layouts.isEmpty()) {
			throw new PlanException("g2_layout_missing", "the Gen 2 export has no surface layout");
		}
		String root = layouts.get(0).root();
		Map<String, G3Data.Rect> origins = areaOrigins(layouts, all, warnings);
		Set<String> surface = new TreeSet<>(origins.keySet());
		G3Data.Start start = layouts.get(0).start();
		Map<String, Door> doors = doors(all, surface, root, start);

		List<String> requested = new ArrayList<>();
		if (options.maps() == null) {
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
		G2Data.Encounters encounters = bundle.g2encounters();
		Set<String> wildMaps = encounters == null ? Set.of() : wildMaps(encounters, all);
		Map<String, List<ExportSwaps.Site>> sitesByMap = new HashMap<>();
		for (ExportSwaps.Site site : bundle.swaps().sites()) {
			sitesByMap.computeIfAbsent(site.map(), k -> new ArrayList<>()).add(site);
		}

		// ---- surface: each area at its origins, in cells ----
		Map<String, MapPlacement> placements = new LinkedHashMap<>();
		for (String id : requested) {
			if (!surface.contains(id)) {
				continue;
			}
			G3Data.Rect at = origins.get(id);
			G2Map map = all.get(id);
			placements.put(id, new MapPlacement(id, options.originX() + Scale.cellToBlock(at.x()), Scale.SURFACE_Y,
					options.originZ() + Scale.cellToBlock(at.y()), map.cellWidth(), map.cellHeight(), 0, MapPlacement.Kind.SURFACE,
					QuarterPages.PALETTE, WorldPlan.BIOME_OUTDOOR, false, null));
		}
		int[] seams = seams(all, origins, placements.keySet(), warnings);

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
		Map<String, G2Tiles> variants = new HashMap<>();
		Map<String, MapModel> interiorModels = new HashMap<>();
		Map<String, CaveMap> caveMaps = new LinkedHashMap<>();
		Set<String> caveIds = new LinkedHashSet<>();
		Set<String> outdoorIds = new HashSet<>();
		for (String id : requested) {
			Door door = doors.get(id);
			if (surface.contains(id) || door == null || !requestedSet.contains(door.parent())) {
				continue;
			}
			G2Map map = all.get(id);
			G2Tiles tiles = tiles(bundle, variants, map.variant());
			MapModel model = G2Cells.model(map, tiles, sitesByMap.getOrDefault(id, List.of()), true, warnings);
			interiorModels.put(id, model);
			caveMaps.put(id, CaveMap.gen2(map, tiles, model, all, new CaveMap.Door(door.parent(), door.x(), door.y(), door.arrivalX(), door.arrivalY()),
					QuarterPages.PALETTE, caveMaps.size(), bundle.game()));
			if (map.caveLike()) {
				caveIds.add(id);
			}
			if (G2Map.OUTDOOR.contains(map.env())) {
				outdoorIds.add(id);
			}
		}
		Set<String> area3d = Area3d.select(caveMaps, caveIds, outdoorIds);
		// Their raised ground (D3, W4: the areas their one-way walls close off, the drawn stairs between them) and their
		// ceilings (D5), before the area is placed, so its floors stack as far apart as their tallest rooms need
		java.util.function.Predicate<String> caveBiome = id -> all.get(id).caveLike() || wildMaps.contains(id);
		dev.kantobridge.scene.ArtSource artSource = FrontEnds.artSource(bundle);
		Map<String, MapLayout> raised = new HashMap<>();
		Map<String, Integer> heights = new HashMap<>();
		for (String id : area3d) {
			MapModel model = interiorModels.get(id);
			G2Map map = all.get(id);
			PairEdges walls = G2Cells.oneWay(model, map, tiles(bundle, variants, map.variant()));
			RaisedRegions.Result levels = RaisedRegions.gen2(model, walls, bundle.game(), map.caveLike());
			warnings.note("cave_levels_" + levels.outcome(), Warnings.symbols("map", id));
			if (levels.layout() != null) {
				raised.put(id, levels.layout());
				warnings.note("cave_raised_cells", levels.raisedCells());
				warnings.note("cave_ramps", levels.layout().ramps().size());
			}
			int[] ceilings = Headroom.ceilings(model, levels.layout(), Headroom.blackFiller(model, artSource, null), Headroom.screenRows(false),
					caveBiome.test(id));
			heights.put(id, java.util.Arrays.stream(ceilings).max().orElse(Headroom.MIN));
		}
		CavePlanner.Result caveArea = placeArea3d(caveMaps, area3d, caveBiome, heights, options, surfaceVolume, warnings);
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

		// ---- cell models ----
		List<PlacedMap> placed = new ArrayList<>();
		Map<String, MapLayout> mapLayouts = new TreeMap<>();
		int oneWayEdges = 0;
		for (MapPlacement placement : placements.values()) {
			G2Map map = all.get(placement.id());
			G2Tiles tiles = tiles(bundle, variants, map.variant());
			boolean interior = placement.kind() == MapPlacement.Kind.INTERIOR;
			MapModel model = interior && interiorModels.containsKey(map.id()) ? interiorModels.get(map.id())
					: G2Cells.model(map, tiles, sitesByMap.getOrDefault(map.id(), List.of()), interior, warnings);
			PairEdges edges = G2Cells.oneWay(model, map, tiles);
			oneWayEdges += edges.edges();
			// The 3D area's maps are drawn as their rooms (D1, CAVES_3D_PLAN §3.5), on their raised ground (D3); an interior
			// under its ceilings (D5)
			List<MapLayout.Piece> pieces = caveArea.pieces().get(map.id());
			MapLayout rooms = pieces == null ? raised.get(map.id()) : WorldPlanner.withPieces(model, pieces, raised.get(map.id()));
			rooms = WorldPlanner.withCeilings(placement, model, rooms, Headroom.blackFiller(model, artSource, null), Headroom.screenRows(false),
					!area3d.contains(map.id()), warnings);
			if (rooms != null) {
				mapLayouts.put(map.id(), rooms);
			}
			placed.add(new PlacedMap(placement, model, edges, rooms));
		}
		warnings.note("one_way_edges", oneWayEdges);

		// ---- surface void fill ----
		Box3 surfaceBox = WorldPlan.surfaceVolume(placements.values());
		WorldPlanner.VoidFill fill = WorldPlanner.voidFill(placed, surfaceBox, (m, cx, cy) -> true, warnings);

		// ---- slot map: from the whole export ----
		ArtCatalog art = catalog(bundle, all, sitesByMap, region, warnings);
		SlotMap slotMap = art.slotMap();

		// ---- story cells (their closed art names slots), habitats, pools ----
		StoryCellTable storyCells = G2StoryCells.plan(placed, all, warnings, (atlas, palette, tile) -> {
			if (tile < 0) {
				return -1;
			}
			SlotMap.Slot slot = slotMap.slot(new SlotMap.Key(QuarterPages.atlasOf(atlas, tile), palette, QuarterPages.tileOf(atlas, tile),
					SlotMap.Shape.FULL));
			return slot == null ? -1 : slot.index();
		}, id -> variants.get(all.get(id).variant()));
		Map<String, int[]> storyIndex = WorldPlanner.storyIndex(placed, storyCells);
		List<WorldIndex.Habitat> habitats = HabitatPlanner.planGen3(placed, wildMaps, warnings, poolPrefix);
		Map<String, String> pools = encounters == null ? Map.of() : G2PoolWriter.pools(encounters, wildMaps, poolPrefix, warnings);

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
		WorldIndex index = new WorldIndex(bundle.game(), bundle.exportHash(), placements.values(), mapLayouts, slotMap.hash(), storyCells, habitats,
				List.of(), null, shafts);

		// ---- shapes: the Gen 2 front end's scene of the whole export, the placed maps' positions ----
		Scene scene = null;
		ShapePlan shapes = null;
		if (options.shapes()) {
			GameFrontEnd frontEnd = FrontEnds.forGame(bundle.game());
			if (frontEnd == null) {
				warnings.note("shapes_no_front_end");
			} else {
				scene = frontEnd.describe(bundle);
				shapes = ShapePlanner.plan(scene, placements.values(), ShapeParams.current(),
						WorldPlanner.occupancy(placed, storyIndex, storyCells, surfaceBox, fill.owner(), fill.voidCells()), new ArtUnitProbe(artSource),
						mapLayouts, shafts, Headroom.rooms(scene, artSource, Headroom.screenRows(false)))
						.withIndexHash(index.hash());
			}
		}
		return new WorldPlan(bundle.game(), bundle.exportHash(), options.originX(), options.originZ(), placed, surfaceBox, fill.owner(),
				fill.voidCells(), storyCells, storyIndex, art, spawn, index, pools, warnings, mapsRequested, unplaced, seams[0], seams[1], scene, shapes,
				caveArea.area(), region);
	}

	/**
	 * Places a Gen 2 or Gen 3 build's 3D area (HEIGHTS_ALL_GAMES_PLAN D1) with {@link CavePlanner}: complexes the pilot
	 * switch names ({@link Caves3d}, every one by default) room by room, the rest whole floor by whole floor. A cave or a
	 * map of wild Pokémon takes the 3D area's cave biome, any other map (a gym, a tower) the interior biome.
	 */
	static CavePlanner.Result placeArea3d(Map<String, CaveMap> maps, Set<String> area3d, java.util.function.Predicate<String> caveBiome,
			Map<String, Integer> heights, WorldPlanner.Options options, @Nullable Box3 surfaceVolume, Warnings warnings) {
		Map<String, CaveMap> inArea = new LinkedHashMap<>();
		Map<String, String> biomes = new HashMap<>();
		for (String id : area3d) {
			inArea.put(id, maps.get(id));
			if (!caveBiome.test(id)) {
				biomes.put(id, WorldPlan.BIOME_INTERIOR);
			}
		}
		Set<String> in3d = new HashSet<>();
		for (List<String> complex : CavePlanner.complexesOf(inArea, area3d)) {
			if (Caves3d.in3d(options.caves3d(), complex)) {
				in3d.addAll(complex);
			}
		}
		return CavePlanner.placeArea(inArea, area3d, in3d, biomes, heights, surfaceVolume, options.originX(), options.originZ(), warnings);
	}

	/** Every map of the export, by id. */
	static Map<String, G2Map> maps(ExportBundle bundle) throws BundleException {
		Map<String, G2Map> all = new TreeMap<>();
		for (String id : bundle.ids("g2map")) {
			all.put(id, bundle.g2map(id));
		}
		return all;
	}

	/**
	 * Every surface map's origin in cells, all areas in one frame: the first area (Johto) as its layout has it, each next
	 * one east of the ones before with {@link #AREA_GAP_CELLS} of sea between, its top at the first's top. A map two areas
	 * both list stays in the first.
	 */
	static Map<String, G3Data.Rect> areaOrigins(List<G2Data.Layout> layouts, Map<String, G2Map> all, Warnings warnings) {
		Map<String, G3Data.Rect> out = new LinkedHashMap<>();
		int nextX = 0;
		int top = 0;
		boolean first = true;
		for (G2Data.Layout layout : layouts) {
			int minX = Integer.MAX_VALUE;
			int minY = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			for (Map.Entry<String, G3Data.Rect> e : layout.maps().entrySet()) {
				G3Data.Rect r = e.getValue();
				minX = Math.min(minX, r.x() * 2);
				minY = Math.min(minY, r.y() * 2);
				maxX = Math.max(maxX, (r.x() + r.w()) * 2);
			}
			if (minX == Integer.MAX_VALUE) {
				continue;
			}
			int dx = first ? 0 : nextX - minX;
			if (first) {
				top = minY;
			}
			int dy = first ? 0 : top - minY;
			for (Map.Entry<String, G3Data.Rect> e : layout.maps().entrySet()) {
				String id = e.getKey();
				G2Map map = all.get(id);
				if (map == null) {
					warnings.warn("layout_connection_missing_map");
					continue;
				}
				if (out.containsKey(id)) {
					warnings.warn("layout_map_in_two_areas", Warnings.symbols("map", id));
					continue;
				}
				G3Data.Rect r = e.getValue();
				if (r.w() != map.w() || r.h() != map.h()) {
					warnings.warn("layout_export_mismatch");
				}
				out.put(id, new G3Data.Rect(r.x() * 2 + dx, r.y() * 2 + dy, map.cellWidth(), map.cellHeight()));
			}
			nextX = Math.max(nextX, maxX + dx + AREA_GAP_CELLS);
			first = false;
		}
		return out;
	}

	/** The maps whose wild Pokémon come from a habitat pool: a grass table that rolls at some time of day. Sorted. */
	static Set<String> wildMaps(G2Data.Encounters encounters, Map<String, G2Map> all) {
		Set<String> out = new TreeSet<>();
		for (Map.Entry<String, G2Data.Grass> e : encounters.grass().entrySet()) {
			boolean rolls = e.getValue().rates().values().stream().anyMatch(r -> r > 0);
			if (rolls && all.containsKey(e.getKey())) {
				out.add(e.getKey());
			}
		}
		return out;
	}

	/**
	 * Every interior's door: breadth-first from the surface, a layer at a time in id order; each map's warps in order (the
	 * first that leads into a map makes it that map's door; the remembered warp leads nowhere known), then its connections
	 * to maps off the surface. Then the maps only left by a warp, the room a new game starts in, and every other map a
	 * script can put a player in, under the root's middle, each with the maps its warps reach; {@link #UNUSED} ones aside.
	 */
	static Map<String, Door> doors(Map<String, G2Map> all, Collection<String> surface, @Nullable String root, G3Data.@Nullable Start start) {
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
				if (start != null && all.containsKey(start.map()) && !visited.contains(start.map())) {
					G2Map rootMap = all.get(root);
					out.put(start.map(), new Door(root, rootMap.cellWidth() / 2, rootMap.cellHeight() / 2, start.x(), start.y(), true, "start"));
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
				G2Map rootMap = all.get(root);
				G2Map head = all.get(id);
				G2Map.Warp arrival = head.warps().isEmpty() ? null : head.warps().get(0);
				out.put(id, new Door(root, rootMap.cellWidth() / 2, rootMap.cellHeight() / 2, arrival == null ? 0 : arrival.x(),
						arrival == null ? 0 : arrival.y(), true, "script"));
				visited.add(id);
				spread(all, out, visited, new TreeSet<>(Set.of(id)));
				added = true;
				break;
			}
		}
		return out;
	}

	private static void spread(Map<String, G2Map> all, Map<String, Door> out, Set<String> visited, Collection<String> first) {
		List<String> layer = new ArrayList<>(first);
		while (!layer.isEmpty()) {
			Set<String> next = new TreeSet<>();
			for (String id : layer) {
				G2Map map = all.get(id);
				for (G2Map.Warp warp : map.warps()) {
					String dest = warp.to();
					if (dest == null || warp.dynamic() || !all.containsKey(dest) || !visited.add(dest)) {
						continue;
					}
					G2Map target = all.get(dest);
					boolean known = warp.warp() < target.warps().size();
					G2Map.Warp arrival = known ? target.warps().get(warp.warp()) : target.warps().isEmpty() ? null : target.warps().get(0);
					out.put(dest, new Door(id, warp.x(), warp.y(), arrival == null ? 0 : arrival.x(), arrival == null ? 0 : arrival.y(), known, "warp"));
					next.add(dest);
				}
				for (G2Map.Connection connection : map.connections()) {
					String dest = connection.map();
					if (!all.containsKey(dest) || !visited.add(dest)) {
						continue;
					}
					// a map entered by a connection off the surface: placed on its own near its neighbour
					out.put(dest, new Door(id, 0, 0, 0, 0, true, connection.dir()));
					next.add(dest);
				}
			}
			layer = new ArrayList<>(next);
		}
	}

	/** One round of the maps no warp leads into but whose own warp leads out into the placed ones. True when one was added. */
	private static boolean exits(Map<String, G2Map> all, Map<String, Door> out, Set<String> visited) {
		boolean added = false;
		for (String id : new TreeSet<>(all.keySet())) {
			if (visited.contains(id)) {
				continue;
			}
			for (G2Map.Warp warp : all.get(id).warps()) {
				G2Map parent = warp.to() == null || warp.dynamic() || !visited.contains(warp.to()) ? null : all.get(warp.to());
				if (parent == null || warp.warp() >= parent.warps().size()) {
					continue;
				}
				G2Map.Warp landing = parent.warps().get(warp.warp());
				out.put(id, new Door(parent.id(), landing.x(), landing.y(), warp.x(), warp.y(), true, "exit"));
				visited.add(id);
				added = true;
				break;
			}
		}
		return added;
	}

	/**
	 * {edge mismatches, overlaps} among the built surface maps, in cells: a connection whose neighbour isn't where the
	 * engine's neighbour arithmetic puts it (north at offset, -its height; south at offset, this height; west at -its
	 * width, offset; east at this width, offset; offsets in blocks), and pairs of maps that overlap.
	 */
	static int[] seams(Map<String, G2Map> all, Map<String, G3Data.Rect> origins, Collection<String> built, Warnings warnings) {
		int mismatches = 0;
		for (String id : built) {
			G2Map map = all.get(id);
			G3Data.Rect at = origins.get(id);
			for (G2Map.Connection connection : map.connections()) {
				G3Data.Rect other = origins.get(connection.map());
				G2Map dest = all.get(connection.map());
				if (!built.contains(connection.map()) || other == null || dest == null) {
					continue;
				}
				int off = connection.offset() * 2;
				int ex;
				int ey;
				switch (connection.dir()) {
					case "north" -> {
						ex = at.x() + off;
						ey = at.y() - dest.cellHeight();
					}
					case "south" -> {
						ex = at.x() + off;
						ey = at.y() + map.cellHeight();
					}
					case "west" -> {
						ex = at.x() - dest.cellWidth();
						ey = at.y() + off;
					}
					default -> {
						ex = at.x() + map.cellWidth();
						ey = at.y() + off;
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
				G3Data.Rect a = origins.get(ids.get(i));
				G3Data.Rect b = origins.get(ids.get(j));
				if (a.x() < b.x() + b.w() && b.x() < a.x() + a.w() && a.y() < b.y() + b.h() && b.y() < a.y() + a.h()) {
					overlaps++;
					warnings.detail("layout_overlap", Warnings.symbols("map", ids.get(i), "other", ids.get(j)));
				}
			}
		}
		warnings.warn("layout_overlap", overlaps);
		return new int[] {mismatches, overlaps};
	}

	/**
	 * @param wild the map's wild Pokémon come from a habitat pool: it gets the cave biome, as Gen 1's and 3's
	 * @param sky  the surface's volume an outdoor map keeps out from under to see the sky, or null
	 */
	private static @Nullable MapPlacement placeInterior(G2Map map, Door door, MapPlacement parent, InteriorPacker packer, boolean wild,
			InteriorPacker.@Nullable Box sky, Warnings warnings) {
		if (!door.arrivalKnown()) {
			warnings.warn("interior_arrival_missing", Warnings.symbols("map", map.id()));
		}
		int wantX = parent.x() + Scale.cellToBlock(door.x()) - Scale.cellToBlock(door.arrivalX());
		int wantZ = parent.z() + Scale.cellToBlock(door.y()) - Scale.cellToBlock(door.arrivalY());
		int blockW = Scale.cellToBlock(map.cellWidth());
		int blockD = Scale.cellToBlock(map.cellHeight());
		Map<String, String> detail = Warnings.symbols("map", map.id(), "parent", parent.id(), "parent_layer", Integer.toString(parent.layer()));
		if (sky != null && map.outdoor()) {
			InteriorPacker.Spot spot = packer.placeOpenSky(wantX, wantZ, blockW, blockD, parent.layer() + 1, sky);
			if (spot != null) {
				warnings.note("open_air_sky", detail);
				return new MapPlacement(map.id(), spot.x(), Scale.feetY(spot.layer()), spot.z(), map.cellWidth(), map.cellHeight(), spot.layer(),
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
		boolean cave = wild || map.caveLike();
		return new MapPlacement(map.id(), spot.x(), Scale.feetY(spot.layer()), spot.z(), map.cellWidth(), map.cellHeight(), spot.layer(),
				MapPlacement.Kind.INTERIOR, QuarterPages.PALETTE, cave ? WorldPlan.BIOME_CAVE : WorldPlan.BIOME_INTERIOR, map.dark(), parent.id());
	}

	/**
	 * The art catalogue of the whole export (whatever the build's filter): every baked tile every map's cells show
	 * (half-height where the cell is water), its border block's, and both blocks of its swap sites (full-shape, as Gen 1's),
	 * keyed by page and index, palette {@link QuarterPages#PALETTE}.
	 */
	static ArtCatalog catalog(ExportBundle bundle, Map<String, G2Map> all, Map<String, List<ExportSwaps.Site>> sitesByMap,
			@Nullable RegionBuild region, Warnings warnings) throws BundleException, PlanException {
		Set<SlotMap.Key> keys = new HashSet<>();
		Map<String, Integer> perRow = new TreeMap<>();
		Map<String, G2Tiles> variants = new HashMap<>();
		Warnings scratch = new Warnings();
		for (G2Map map : all.values()) {
			if (!bundle.has("g2tiles", map.variant())) {
				warnings.note("catalog_variant_missing");
				continue;
			}
			G2Tiles tiles = tiles(bundle, variants, map.variant());
			List<ExportSwaps.Site> sites = sitesByMap.getOrDefault(map.id(), List.of());
			MapModel model = G2Cells.model(map, tiles, sites, true, scratch);
			for (int ty = 0; ty < model.cellHeight() * 2; ty++) {
				for (int tx = 0; tx < model.cellWidth() * 2; tx++) {
					boolean half = model.kind(tx / 2, ty / 2) == MapModel.Kind.WATER;
					keys.add(key(model.tile(tx, ty), half ? SlotMap.Shape.HALF : SlotMap.Shape.FULL, perRow));
				}
			}
			for (int ty = 0; ty < 4; ty++) {
				for (int tx = 0; tx < 4; tx++) {
					keys.add(key(model.borderTile(tx, ty), SlotMap.Shape.FULL, perRow));
				}
			}
			for (ExportSwaps.Site site : sites) {
				// Gen 1's rule: both blocks of every site and the data's own, full-shape (the closed art a story cell draws),
				// so the slot map is the same whichever sites the planner accepts.
				int data = map.inBlocks(site.bx(), site.by()) ? map.dataBlock(site.bx(), site.by()) : site.before();
				for (int block : new int[] {site.before(), site.after(), data}) {
					for (int t = 0; t < 16; t++) {
						int tile = tiles.tile(block, t % 4, t / 4);
						if (tile >= 0) {
							keys.add(key(tile, SlotMap.Shape.FULL, perRow));
						}
					}
				}
			}
		}
		try {
			return ArtCatalog.baked(keys, perRow, region);
		} catch (PlanException e) {
			if (region == null && "slot_map.full".equals(e.code())) {
				throw new PlanException("slot_map.full", e.getMessage() + "; a Gen 2 export is built as a region");
			}
			throw e;
		}
	}

	private static SlotMap.Key key(int tile, SlotMap.Shape shape, Map<String, Integer> perRow) {
		String page = QuarterPages.page(tile);
		perRow.putIfAbsent(page, QuarterPages.PER_ROW);
		return new SlotMap.Key(page, QuarterPages.PALETTE, QuarterPages.index(tile), shape);
	}

	private static G2Tiles tiles(ExportBundle bundle, Map<String, G2Tiles> variants, String variant) throws BundleException {
		G2Tiles tiles = variants.get(variant);
		if (tiles == null) {
			tiles = bundle.g2tiles(variant);
			variants.put(variant, tiles);
		}
		return tiles;
	}
}
