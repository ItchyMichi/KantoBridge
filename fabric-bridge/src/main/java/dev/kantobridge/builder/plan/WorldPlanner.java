package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportEncounters;
import dev.kantobridge.builder.export.ExportField;
import dev.kantobridge.builder.export.ExportLayout;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.builder.export.ExportPalettes;
import dev.kantobridge.builder.export.ExportProfile;
import dev.kantobridge.builder.export.ExportSwaps;
import dev.kantobridge.builder.export.ExportTileset;
import dev.kantobridge.builder.pack.HabitatPoolWriter;
import dev.kantobridge.builder.shape.ArtUnitProbe;
import dev.kantobridge.builder.shape.Occupancy;
import dev.kantobridge.builder.shape.ShapeParams;
import dev.kantobridge.builder.shape.ShapePlan;
import dev.kantobridge.builder.shape.ShapePlanner;
import dev.kantobridge.scene.FrontEnds;
import dev.kantobridge.scene.GameFrontEnd;
import dev.kantobridge.scene.Scene;
import dev.kantobridge.scene.profile.Profile;
import dev.kantobridge.scene.profile.ProfileSource;
import dev.kantobridge.world.DataReader;
import dev.kantobridge.world.MapLayout;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * Plans a build from an export bundle (docs/PLAN.md §4.5): layout, interior placement, cell models, story cells, slot
 * map, spawn and WorldIndex. Pure Java: no world access, so JUnit runs it as it is.
 */
public final class WorldPlanner {
	/** The root of the surface layout when the export has no layout resource. */
	public static final String DEFAULT_ROOT = "PALLET_TOWN";
	/** Caves get their own biome (docs/PLAN.md §4.2 static biomes). */
	public static final String CAVE_TILESET = "CAVERN";

	/**
	 * @param maps           the maps to build, in the order given; null builds every map in the export
	 * @param originX        block X of the root map's cell (0, 0)
	 * @param originZ        block Z of the root map's cell (0, 0)
	 * @param minBuildHeight the world's lowest block (decides the deepest interior layer)
	 * @param shapes         plan the world's shapes (the voxel plan's V2)
	 * @param caves3d        the cave complexes built in 3D (docs/specs/CAVES_3D_PLAN.md §0, {@link Caves3d})
	 */
	public record Options(@Nullable List<String> maps, int originX, int originZ, int minBuildHeight, boolean shapes, Set<String> caves3d) {
		public Options {
			caves3d = Set.copyOf(caves3d);
		}

		public Options(@Nullable List<String> maps, int originX, int originZ, int minBuildHeight, boolean shapes) {
			this(maps, originX, originZ, minBuildHeight, shapes, Caves3d.fromProperty());
		}

		public Options(@Nullable List<String> maps, int originX, int originZ, int minBuildHeight) {
			this(maps, originX, originZ, minBuildHeight, true);
		}
	}

	private WorldPlanner() {
	}

	public static WorldPlan plan(ExportBundle bundle, Options options) throws BundleException, PlanException {
		return plan(bundle, options, null);
	}

	/**
	 * Plans a build of one region (docs/specs/MULTI_GAME_PLAN.md §4.6, MG3): the caller puts the region's slot origin into
	 * {@code options}; {@code region} gives the slot-map range its keys take, the previous world map and the prefix of its
	 * pool ids. Null plans a legacy build exactly as before regions existed.
	 */
	public static WorldPlan plan(ExportBundle bundle, Options options, @Nullable RegionBuild region) throws BundleException, PlanException {
		if (bundle.isGen3()) {
			// MG7 (GEN3_BRIDGE_PLAN §6): a Gen 3 export has its own maps, art and layout; Gen 1's path stays as it was.
			return Gen3Planner.plan(bundle, options, region);
		}
		if (bundle.isGen2()) {
			// MG9 (GEN2_BRIDGE_PLAN §7): a Gen 2 export too, built with Gen 3's machinery and Gen 1's block-level sites.
			return Gen2Planner.plan(bundle, options, region);
		}
		Warnings warnings = new Warnings();
		String poolPrefix = region == null ? "" : region.poolPrefix();

		Map<String, ExportMap> all = new LinkedHashMap<>();
		for (String id : bundle.ids("map")) {
			all.put(id, bundle.map(id));
		}
		List<String> requested = new ArrayList<>();
		if (options.maps() == null) {
			// Every map that can be reached: an interior no warp leads to (COLOSSEUM, TRADE_CENTER) is skipped quietly.
			for (ExportMap map : all.values()) {
				if (map.surface() || map.parent() != null) {
					requested.add(map.id());
				} else {
					warnings.note("map_unreachable");
				}
			}
		} else {
			for (String id : new LinkedHashSet<>(options.maps())) {
				if (all.containsKey(id)) {
					requested.add(id);
				} else {
					// The id was typed by whoever ran the build; only a well-formed one is repeated.
					warnings.warn("filter_unknown_map", Warnings.symbols("map", DataReader.MAP_ID.matcher(id).matches() ? id : null));
				}
			}
		}
		int mapsRequested = options.maps() == null ? requested.size() : new LinkedHashSet<>(options.maps()).size();
		Set<String> requestedSet = new HashSet<>(requested);
		// Maps whose wild Pokémon come from habitat pools (grass tables outside the Safari Zone).
		ExportEncounters encounters = bundle.encounters();
		ExportField field = bundle.field();
		Set<String> wildMaps = encounters == null ? Set.of() : HabitatPlanner.wildMaps(encounters, field);

		// ---- surface layout ----
		Map<String, ExportMap> surface = new LinkedHashMap<>();
		for (ExportMap map : all.values()) {
			if (map.surface()) {
				surface.put(map.id(), map);
			}
		}
		ExportLayout exported = bundle.hasLayout() ? bundle.layout() : null;
		String root = exported != null ? exported.root() : DEFAULT_ROOT;
		Set<String> builtSurface = new LinkedHashSet<>();
		for (String id : requested) {
			if (surface.containsKey(id)) {
				builtSurface.add(id);
			}
		}
		Layout.Result layout = Layout.compute(surface, exported, root, builtSurface, warnings);

		Map<String, MapPlacement> placements = new LinkedHashMap<>();
		for (String id : requested) {
			Layout.Origin origin = layout.origins().get(id);
			if (!builtSurface.contains(id) || origin == null) {
				continue;
			}
			ExportMap map = all.get(id);
			placements.put(id, new MapPlacement(id, options.originX() + origin.x() * Scale.GEN1_BLOCK_BLOCKS, Scale.SURFACE_Y,
					options.originZ() + origin.y() * Scale.GEN1_BLOCK_BLOCKS, map.cellWidth(), map.cellHeight(), 0, MapPlacement.Kind.SURFACE,
					map.palette(), WorldPlan.BIOME_OUTDOOR, map.dark(), null));
		}

		// ---- interiors: in rounds, each placed under its parent's door ----
		List<InteriorPacker.Box> surfaceFootprints = new ArrayList<>();
		for (MapPlacement p : placements.values()) {
			surfaceFootprints.add(new InteriorPacker.Box(p.x(), p.z(), p.x() + p.blockWidth(), p.z() + p.blockDepth()));
		}
		InteriorPacker packer = new InteriorPacker(Scale.maxLayer(options.minBuildHeight()), surfaceFootprints);
		// Open-air interiors (a forest) get the sky when the build plans shapes (the voxel plan's V7): outside the surface's
		// volume, nothing above them.
		Box3 surfaceVolume = WorldPlan.surfaceVolume(placements.values());
		InteriorPacker.Box sky = !options.shapes() || surfaceVolume == null ? null
				: new InteriorPacker.Box(surfaceVolume.minX(), surfaceVolume.minZ(), surfaceVolume.maxX(), surfaceVolume.maxZ());
		// The cave area (docs/specs/CAVES_3D_PLAN.md §3.3): caves leave the interior layers for an area of their own north of
		// Kanto, past the dungeon slots; their entrances fade over the distance, as Fly does.
		Set<String> caves = CavePlanner.caves(all, requested, CAVE_TILESET);
		// Their rooms never depend on the story: a swap site's cells count as walked on (§3.5)
		Map<String, List<int[]>> siteBlocks = new HashMap<>();
		for (ExportSwaps.Site site : bundle.swaps().sites()) {
			siteBlocks.computeIfAbsent(site.map(), k -> new ArrayList<>()).add(new int[] {site.bx(), site.by()});
		}
		Map<String, List<ExportSwaps.Site>> swapsByMap = new HashMap<>();
		// Each site's position in the export's list names it in the diagnostics (no coordinates, no block ids).
		Map<ExportSwaps.Site, Integer> siteNumbers = new IdentityHashMap<>();
		List<ExportSwaps.Site> allSites = bundle.swaps().sites();
		for (int i = 0; i < allSites.size(); i++) {
			ExportSwaps.Site site = allSites.get(i);
			swapsByMap.computeIfAbsent(site.map(), k -> new ArrayList<>()).add(site);
			siteNumbers.put(site, i);
		}
		// The caves' models, built once here: the warp review knows a warp cell by its drawing (HEIGHTS_ALL_GAMES_PLAN D2)
		Map<String, MapModel> caveModels = new HashMap<>();
		Map<String, CaveMap> caveMaps = new LinkedHashMap<>();
		Map<String, ExportTileset> caveTilesets = new HashMap<>();
		for (String id : caves) {
			ExportMap map = all.get(id);
			ExportTileset tileset = caveTilesets.get(map.tileset());
			if (tileset == null) {
				tileset = bundle.tileset(map.tileset());
				caveTilesets.put(map.tileset(), tileset);
			}
			MapModel model = MapModel.build(map, tileset, swapsByMap.getOrDefault(id, List.of()), true, warnings, site -> siteNumbers.getOrDefault(site, -1));
			caveModels.put(id, model);
			caveMaps.put(id, CaveMap.gen1(map, model, all, siteBlocks.getOrDefault(id, List.of()), bundle.game(), true));
		}
		// The caves built in 3D: every map of a complex the pilot switch names (CAVES_3D_PLAN §0)
		Set<String> caves3d = new HashSet<>();
		for (List<String> complex : CavePlanner.complexesOf(caveMaps, caves)) {
			if (Caves3d.in3d(options.caves3d(), complex)) {
				caves3d.addAll(complex);
			}
		}
		// The shape profile, where the export carried one: its raised pins say which side of a cave's pairs is high (§3.4)
		ExportProfile carried = caves3d.isEmpty() ? null : bundle.profile();
		Profile profile = carried == null ? null : ProfileSource.read(carried.text(), carried.version(), carried.enabled()).profile();
		// The art, for the filler a room's drawing stops at (its ceilings, HEIGHTS_ALL_GAMES_PLAN D8)
		dev.kantobridge.scene.ArtSource artSource = FrontEnds.artSource(bundle);
		// A cave built in 3D gets its raised ground (CAVES_3D_PLAN §3.4), and every cave its ceilings (HEIGHTS_ALL_GAMES_PLAN
		// D5) before the area is placed, so its floors stack as far apart as their tallest rooms need
		Map<String, MapLayout> caveLayouts = new HashMap<>();
		Map<String, Integer> caveHeights = new HashMap<>();
		for (String id : caves) {
			MapModel model = caveModels.get(id);
			MapLayout raised = null;
			if (caves3d.contains(id)) {
				CaveLevels.Result levels = CaveLevels.plan(model, field.tilePairs(), profile == null ? null : profile.tileset(all.get(id).tileset()));
				warnings.note("cave_levels_" + levels.outcome(), Warnings.symbols("map", id, "high", levels.high().isEmpty() ? null : levels.high()));
				warnings.note("cave_raised_cells", levels.raisedCells());
				warnings.note("cave_ramps", levels.stairs());
				warnings.note("cave_stairs_profile_confirmed", levels.stairsConfirmed());
				warnings.note("cave_stairs_profile_other", levels.stairsOther());
				raised = levels.layout();
				if (raised != null) {
					caveLayouts.put(id, raised);
				}
			}
			int[] ceilings = Headroom.ceilings(model, raised, Headroom.blackFiller(model, artSource, null), Headroom.screenRows(false), true);
			caveHeights.put(id, java.util.Arrays.stream(ceilings).max().orElse(Headroom.MIN));
		}
		CavePlanner.Result caveArea = CavePlanner.placeArea(caveMaps, caves, caves3d, Map.of(), caveHeights, surfaceVolume, options.originX(),
				options.originZ(), warnings);
		for (MapPlacement cave : caveArea.placements()) {
			placements.put(cave.id(), cave);
		}
		String forest = field.indoor() == null ? null : field.indoor().excludedTileset();
		List<ExportMap> pending = new ArrayList<>();
		for (String id : requested) {
			ExportMap map = all.get(id);
			if (map.surface() || caves.contains(id)) {
				continue;
			}
			if (map.parent() == null) {
				// Only an explicit filter gets here (a full build leaves unreachable maps out): asked for, can't be placed.
				warnings.warn("map_unreachable", Warnings.symbols("map", id));
			} else if (!requestedSet.contains(map.parent().map())) {
				warnings.warn("interior_parent_not_built", Warnings.symbols("map", id, "parent", map.parent().map()));
			} else {
				pending.add(map);
			}
		}
		pending.sort(Comparator.comparingInt(ExportMap::index).thenComparing(ExportMap::id));
		boolean progress = true;
		while (!pending.isEmpty() && progress) {
			progress = false;
			List<ExportMap> next = new ArrayList<>();
			for (ExportMap map : pending) {
				MapPlacement parent = placements.get(map.parent().map());
				if (parent == null) {
					next.add(map);
					continue;
				}
				progress = true;
				boolean openAir = map.outside() || map.tileset().equals(forest);
				MapPlacement placed = placeInterior(map, parent, all.get(parent.id()), packer, wildMaps.contains(map.id()), openAir ? sky : null,
						warnings);
				if (placed != null) {
					placements.put(map.id(), placed);
				}
			}
			pending = next;
		}
		for (ExportMap map : pending) {
			warnings.warn("interior_parent_unplaced", Warnings.symbols("map", map.id(), "parent", map.parent().map()));
		}
		// Every map asked for that has no place now, for the report and its diagnostics (the codes above say why).
		int unplaced = 0;
		for (String id : requested) {
			if (!placements.containsKey(id)) {
				unplaced++;
				warnings.detail("maps_unplaced", Warnings.symbols("map", id));
			}
		}

		// ---- cell models ----
		for (ExportSwaps.Site site : allSites) {
			if (!all.containsKey(site.map())) {
				warnings.warn("swap_unknown_map", Warnings.symbols("map", site.map(), "kind", site.kind(), "source", site.source(), "site",
						Integer.toString(siteNumbers.get(site))));
			}
		}
		Map<String, ExportTileset> tilesets = new HashMap<>();
		ExportPalettes palettes = bundle.hasPalettes() ? bundle.palettes() : null;
		List<PlacedMap> placed = new ArrayList<>();
		for (MapPlacement placement : placements.values()) {
			ExportMap map = all.get(placement.id());
			ExportTileset tileset = tilesets.get(map.tileset());
			if (tileset == null) {
				tileset = bundle.tileset(map.tileset());
				tilesets.put(map.tileset(), tileset);
				if (!bundle.has("image", tileset.image())) {
					warnings.warn("atlas_missing");
				}
			}
			if (palettes == null || palettes.colours(placement.palette()) == null) {
				warnings.warn("palette_missing");
			}
			boolean interior = placement.kind() == MapPlacement.Kind.INTERIOR;
			MapModel model = interior && caveModels.containsKey(map.id()) ? caveModels.get(map.id())
					: MapModel.build(map, tileset, swapsByMap.getOrDefault(map.id(), List.of()), interior, warnings,
							site -> siteNumbers.getOrDefault(site, -1));
			// The edges the engine's tile pairs close (CAVES_3D_PLAN §3.2): invisible walls in the map's cells along them
			PairEdges edges = PairEdges.of(model, field.tilePairs());
			if (edges.edges() > 0) {
				warnings.note("tile_pair_edges", edges.edges());
			}
			// A cave built in 3D stands on its raised ground (planned with the cave area above); the rest stays flat
			MapLayout raised = caveLayouts.get(map.id());
			// Its rooms, laid out apart so its ladders and holes line up (§3.5): its pieces, with the levels
			List<MapLayout.Piece> pieces = caveArea.pieces().get(map.id());
			if (pieces != null) {
				raised = withPieces(model, pieces, raised);
			}
			// An interior's ceilings (HEIGHTS_ALL_GAMES_PLAN D5): as tall as its drawing, at least 4 over its highest floor
			raised = withCeilings(placement, model, raised, Headroom.blackFiller(model, artSource, null), Headroom.screenRows(false),
					!caves.contains(map.id()), warnings);
			placed.add(new PlacedMap(placement, model, edges, raised));
		}

		// ---- surface void fill: each gap cell takes the border art of its nearest surface map ----
		Box3 surfaceBox = WorldPlan.surfaceVolume(placements.values());
		VoidFill fill = voidFill(placed, surfaceBox, (m, cx, cy) -> true, warnings);
		int[] owner = fill.owner();
		boolean[] voidCells = fill.voidCells();

		// ---- slot map: from the whole export, so it (and the shared resource pack) never depends on the filter ----
		ArtCatalog art = ArtCatalog.of(bundle, warnings, region);
		SlotMap slotMap = art.slotMap();

		// ---- story cells (their closed art names slots of the slot map) ----
		StoryCellTable storyCells = StoryCellPlanner.plan(placed, warnings, (atlas, palette, tile) -> {
			SlotMap.Slot slot = tile < 0 || tile > 1023 ? null : slotMap.slot(new SlotMap.Key(atlas, palette, tile, SlotMap.Shape.FULL));
			return slot == null ? -1 : slot.index();
		});
		Map<String, int[]> storyIndex = storyIndex(placed, storyCells);
		// A story block on a ramp stands on its steps (CAVES_3D_PLAN §3.4), but a swap site's carries its closed art up
		// from the floor: there it takes the first step, and says so.
		for (PlacedMap map : placed) {
			MapLayout raised = map.layout();
			for (int i = 0; raised != null && i < raised.w() * raised.h(); i++) {
				int story = storyIndex.get(map.id())[i];
				if (raised.ramp(i % raised.w(), i / raised.w()) != null && story >= 0) {
					if (storyCells.entries().get(story).type() == StoryCellType.TILE) {
						warnings.warn("cave_ramp_swap_site", Warnings.symbols("map", map.id()));
					} else {
						warnings.note("cave_ramp_story_cells");
					}
				}
			}
		}

		// ---- habitats: blocks for the built maps; pools for every wild map of the export (like the slot map) ----
		List<WorldIndex.Habitat> habitats = encounters == null ? List.of() : HabitatPlanner.plan(placed, encounters, field, warnings, poolPrefix);
		Map<String, String> pools = encounters == null ? Map.of() : HabitatPoolWriter.pools(encounters, field, poolPrefix);

		// ---- spawn and index ----
		WorldPlan.Spawn spawn = chooseSpawn(placed, root, requested, storyIndex);
		if (spawn == null) {
			warnings.warn("spawn_missing");
		}
		Map<String, MapLayout> layouts = new TreeMap<>();
		for (PlacedMap map : placed) {
			if (map.layout() != null) {
				layouts.put(map.id(), map.layout());
			}
		}
		Map<String, MapModel> models = new HashMap<>();
		for (PlacedMap map : placed) {
			models.put(map.id(), map.model());
		}
		List<WorldIndex.Shaft> shafts = shafts(caveArea.shafts(), placements, layouts, models);
		WorldIndex index = new WorldIndex(bundle.game(), bundle.exportHash(), placements.values(), layouts, slotMap.hash(), storyCells, habitats,
				List.of(), null, shafts);

		// ---- shapes (docs/specs/VOXEL_BLOCKS_PLAN.md §5.4): keys of the whole export, positions of the placed maps ----
		Scene scene = null;
		ShapePlan shapes = null;
		if (options.shapes()) {
			GameFrontEnd frontEnd = FrontEnds.forGame(bundle.game());
			if (frontEnd == null) {
				warnings.note("shapes_no_front_end");
			} else {
				scene = frontEnd.describe(bundle);
				// The probe of the profile plan's PD3 asks this export's own art whether a repeating cell stands as a unit.
				shapes = ShapePlanner.plan(scene, placements.values(), ShapeParams.current(),
						occupancy(placed, storyIndex, storyCells, surfaceBox, owner, voidCells), new ArtUnitProbe(artSource), layouts, shafts,
						Headroom.rooms(scene, artSource, Headroom.screenRows(false)))
						.withIndexHash(index.hash());
			}
		}
		ExportField.Display display = field.display();
		if (display != null && !display.voidFillDefault()) {
			// VD4: the drawn border follows the player's void-fill option; exported, never pinned.
			warnings.warn("void_fill_not_default", Warnings.symbols("option", display.voidFill()));
		}
		return new WorldPlan(bundle.game(), bundle.exportHash(), options.originX(), options.originZ(), placed, surfaceBox, owner, voidCells,
				storyCells, storyIndex, art, spawn, index, pools, warnings, mapsRequested, unplaced, layout.edgeMismatches(), layout.overlaps(), scene,
				shapes, caveArea.area(), region);
	}

	/** Which surface map's border art each cell of the surface box shows, and which of its cells are void (no map's). */
	record VoidFill(int[] owner, boolean[] voidCells) {
	}

	/** Which of a placed map's cells claim the surface box first. */
	@FunctionalInterface
	interface CellFilter {
		boolean first(int map, int cx, int cy);
	}

	/**
	 * The surface void fill: each cell of the surface box that no surface map covers takes the border art of its nearest
	 * surface map (breadth-first from the maps' cells). Where maps overlap, a cell goes to the first map in placement order
	 * that claims it, the cells {@code first} accepts claiming before the rest (Gen 3: a map's own cells before its
	 * padding, MG7).
	 */
	static VoidFill voidFill(List<PlacedMap> placed, @Nullable Box3 surfaceBox, CellFilter first, Warnings warnings) {
		if (surfaceBox == null) {
			return new VoidFill(new int[0], new boolean[0]);
		}
		int w = (surfaceBox.maxX() - surfaceBox.minX()) / Scale.CELL_BLOCKS;
		int d = (surfaceBox.maxZ() - surfaceBox.minZ()) / Scale.CELL_BLOCKS;
		int[] owner = new int[w * d];
		boolean[] voidCells = new boolean[w * d];
		Arrays.fill(owner, -1);
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		for (int pass = 0; pass < 2; pass++) {
			for (int m = 0; m < placed.size(); m++) {
				MapPlacement p = placed.get(m).placement();
				if (p.kind() != MapPlacement.Kind.SURFACE) {
					continue;
				}
				int cx0 = (p.x() - surfaceBox.minX()) / Scale.CELL_BLOCKS;
				int cz0 = (p.z() - surfaceBox.minZ()) / Scale.CELL_BLOCKS;
				for (int cy = 0; cy < p.h(); cy++) {
					for (int cx = 0; cx < p.w(); cx++) {
						int i = (cz0 + cy) * w + cx0 + cx;
						if (owner[i] < 0 && first.first(m, cx, cy) == (pass == 0)) {
							owner[i] = m;
							queue.add(i);
						}
					}
				}
			}
		}
		int gaps = 0;
		while (!queue.isEmpty()) {
			int i = queue.poll();
			int x = i % w;
			int z = i / w;
			int[][] steps = {{0, -1}, {-1, 0}, {1, 0}, {0, 1}};
			for (int[] step : steps) {
				int nx = x + step[0];
				int nz = z + step[1];
				if (nx < 0 || nz < 0 || nx >= w || nz >= d) {
					continue;
				}
				int n = nz * w + nx;
				if (owner[n] < 0) {
					owner[n] = owner[i];
					voidCells[n] = true;
					gaps++;
					queue.add(n);
				}
			}
		}
		warnings.note("void_fill_cells", gaps);
		return new VoidFill(owner, voidCells);
	}

	/** Each placed map's story cell index per cell (-1 for none), from the story cell table. */
	static Map<String, int[]> storyIndex(List<PlacedMap> placed, StoryCellTable storyCells) throws PlanException {
		Map<String, int[]> storyIndex = new HashMap<>();
		Map<String, Integer> widths = new HashMap<>();
		for (PlacedMap map : placed) {
			int[] cells = new int[map.model().cellWidth() * map.model().cellHeight()];
			Arrays.fill(cells, -1);
			storyIndex.put(map.id(), cells);
			widths.put(map.id(), map.model().cellWidth());
		}
		List<StoryCellTable.Entry> entries = storyCells.entries();
		for (int i = 0; i < entries.size(); i++) {
			StoryCellTable.Entry entry = entries.get(i);
			storyIndex.get(entry.cell().map())[entry.cell().y() * widths.get(entry.cell().map()) + entry.cell().x()] = i;
		}
		if (storyCells.entries().size() > BlockCode.STORY_INDEX_MASK + 1) {
			throw new PlanException("story_cells", "too many story cells for a block code: " + storyCells.entries().size());
		}
		return storyIndex;
	}

	/** A map's layout with its rooms' pieces (CAVES_3D_PLAN §3.5) and, if it has any, its levels and ramps (§3.4). */
	/**
	 * An interior's layout with its ceilings (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D5, {@link Headroom}): its rooms and raised
	 * ground, or a flat one. A room on a layer stops at {@link Scale#LAYER_ROOM} (counted); the 3D area's floors stack by
	 * their own heights. A map under the sky (the surface, an open-air interior) keeps {@code layout}.
	 *
	 * @param layered placed on an interior layer (not in the 3D area)
	 */
	static @Nullable MapLayout withCeilings(MapPlacement placement, MapModel model, @Nullable MapLayout layout, Headroom.Filler filler,
			int screenRows, boolean layered, Warnings warnings) {
		if (placement.kind() != MapPlacement.Kind.INTERIOR || WorldPlan.openSky(placement)) {
			return layout;
		}
		MapLayout base = layout != null ? layout : MapLayout.flat(model.cellWidth(), model.cellHeight(), null, List.of());
		boolean cave = WorldPlan.BIOME_CAVE_3D.equals(placement.biome());
		int[] ceilings = Headroom.ceilings(model, layout, filler, screenRows, cave);
		if (layered) {
			boolean capped = false;
			for (int i = 0; i < ceilings.length; i++) {
				if (ceilings[i] > Scale.LAYER_ROOM) {
					ceilings[i] = Scale.LAYER_ROOM;
					capped = true;
				}
			}
			if (capped) {
				warnings.note("room_ceiling_capped", Warnings.symbols("map", placement.id()));
			}
		}
		return base.withCeilings(ceilings);
	}

	static MapLayout withPieces(MapModel model, List<MapLayout.Piece> pieces, @Nullable MapLayout levels) {
		int w = model.cellWidth();
		int h = model.cellHeight();
		int[] lv = null;
		List<MapLayout.Ramp> ramps = new ArrayList<>();
		if (levels != null) {
			lv = new int[w * h];
			for (int cy = 0; cy < h; cy++) {
				for (int cx = 0; cx < w; cx++) {
					lv[cy * w + cx] = levels.level(cx, cy);
				}
			}
			ramps.addAll(levels.ramps());
		}
		return MapLayout.of(w, h, pieces, lv, ramps);
	}

	/**
	 * The shafts in the world (CAVES_3D_PLAN §3.5, §4.1): each lined-up ladder or hole, from its bottom cell's feet up
	 * to its top cell's floor, in its top cell's column; a ladder on the wall {@link WorldPlan#ladderWall} picks.
	 */
	static List<WorldIndex.Shaft> shafts(List<CaveRooms.Link> links, Map<String, MapPlacement> placements, Map<String, MapLayout> layouts,
			Map<String, MapModel> models) {
		List<WorldIndex.Shaft> out = new ArrayList<>();
		for (CaveRooms.Link link : links) {
			MapPlacement top = placements.get(link.topMap());
			MapPlacement bottom = placements.get(link.bottomMap());
			if (top == null || bottom == null) {
				continue;
			}
			int[] t = origin(top, layouts.get(link.topMap()), link.topX(), link.topY());
			int[] b = origin(bottom, layouts.get(link.bottomMap()), link.bottomX(), link.bottomY());
			MapModel model = models.get(link.topMap());
			if (t[0] != b[0] || t[2] != b[2] || b[1] >= t[1] - 1 || model == null) {
				continue;
			}
			dev.kantobridge.world.Dir wall = WorldIndex.Shaft.LADDER.equals(link.kind()) ? WorldPlan.ladderWall(model, link.topX(), link.topY()) : null;
			out.add(new WorldIndex.Shaft(link.kind(), new dev.kantobridge.world.CellPos(link.topMap(), link.topX(), link.topY()),
					new dev.kantobridge.world.CellPos(link.bottomMap(), link.bottomX(), link.bottomY()), link.up(), t[0], t[2], b[1], t[1] - 1,
					wall));
		}
		return out;
	}

	/** A cell's north-west block at its feet: {x, y, z} (its piece's offset and its level counted). */
	private static int[] origin(MapPlacement p, @Nullable MapLayout layout, int cx, int cy) {
		MapLayout.Piece piece = layout == null ? null : layout.pieceAt(cx, cy);
		int level = layout == null ? 0 : layout.level(cx, cy);
		return new int[] {p.x() + Scale.cellToBlock(cx) + (piece == null ? 0 : piece.dx()), p.y() + (piece == null ? 0 : piece.dy()) + level,
				p.z() + Scale.cellToBlock(cy) + (piece == null ? 0 : piece.dz())};
	}

	/**
	 * What the shape planner must leave to the builder (the voxel plan's §5.4): the feet level of every story cell, the
	 * void fill (whose ring may hold units of its owner's border), and the tile story cells (their closed art's overlays,
	 * V5).
	 */
	static Occupancy occupancy(List<PlacedMap> placed, Map<String, int[]> storyIndex, StoryCellTable storyCells, @Nullable Box3 surfaceBox,
			int[] owner, boolean[] voidCells) {
		Map<String, BitSet> story = new HashMap<>();
		for (PlacedMap map : placed) {
			int[] cells = storyIndex.get(map.id());
			BitSet bits = new BitSet(cells.length);
			for (int i = 0; i < cells.length; i++) {
				bits.set(i, cells[i] >= 0);
			}
			story.put(map.id(), bits);
		}
		Occupancy.VoidFill fill = null;
		if (surfaceBox != null) {
			int w = (surfaceBox.maxX() - surfaceBox.minX()) / Scale.CELL_BLOCKS;
			int d = (surfaceBox.maxZ() - surfaceBox.minZ()) / Scale.CELL_BLOCKS;
			String[] owners = new String[w * d];
			for (int i = 0; i < owners.length; i++) {
				owners[i] = voidCells[i] && owner[i] >= 0 ? placed.get(owner[i]).id() : null;
			}
			fill = new Occupancy.VoidFill(surfaceBox.minX(), surfaceBox.minZ(), w, d, Scale.CELL_BLOCKS, owners);
		}
		List<Occupancy.TileCell> tiles = new ArrayList<>();
		for (StoryCellTable.Entry entry : storyCells.entries()) {
			if (entry.type() == StoryCellType.TILE) {
				tiles.add(new Occupancy.TileCell(entry.key(), entry.cell().map(), entry.cell().x(), entry.cell().y()));
			}
		}
		return new Occupancy(story, fill, tiles);
	}

	/**
	 * @param wild the map's wild Pokémon come from a habitat pool: it gets the cave biome, which the interior spawn guard
	 *             (spawn rule {@code no_interior_spawns}) leaves alone; the two biomes look and sound the same
	 * @param sky  for an open-air interior (V7), the surface's volume it must keep out from under to see the sky; null for
	 *             any other interior
	 */
	private static @Nullable MapPlacement placeInterior(ExportMap map, MapPlacement parent, ExportMap parentMap, InteriorPacker packer,
			boolean wild, InteriorPacker.@Nullable Box sky, Warnings warnings) {
		ExportMap.Parent door = map.parent();
		int doorX = parent.x() + Scale.cellToBlock(door.x());
		int doorZ = parent.z() + Scale.cellToBlock(door.y());
		// The interior's arrival cell (the warp the door leads to) goes right under the door.
		int arrivalX = 0;
		int arrivalY = 0;
		ExportMap.Warp warp = parentMap.warp(door.warp());
		ExportMap.Warp arrival = warp != null && warp.to().equals(map.id()) ? map.warp(warp.warp()) : null;
		if (arrival != null) {
			arrivalX = arrival.x();
			arrivalY = arrival.y();
		} else if (!map.warps().isEmpty()) {
			warnings.warn("interior_arrival_missing", Warnings.symbols("map", map.id()));
			arrivalX = map.warps().get(0).x();
			arrivalY = map.warps().get(0).y();
		} else {
			warnings.warn("interior_arrival_missing", Warnings.symbols("map", map.id()));
		}
		int wantX = doorX - Scale.cellToBlock(arrivalX);
		int wantZ = doorZ - Scale.cellToBlock(arrivalY);
		Map<String, String> detail = Warnings.symbols("map", map.id(), "parent", parent.id(), "parent_layer", Integer.toString(parent.layer()));
		InteriorPacker.Spot spot = null;
		if (sky != null) {
			// An open-air interior: under the open sky, outside the surface's volume; else it keeps its ceiling (counted).
			spot = packer.placeOpenSky(wantX, wantZ, Scale.cellToBlock(map.cellWidth()), Scale.cellToBlock(map.cellHeight()), parent.layer() + 1,
					sky);
			if (spot != null) {
				warnings.note("open_air_sky", detail);
				return new MapPlacement(map.id(), spot.x(), Scale.feetY(spot.layer()), spot.z(), map.cellWidth(), map.cellHeight(), spot.layer(),
						MapPlacement.Kind.INTERIOR, map.palette(), WorldPlan.BIOME_OUTDOOR, map.dark(), parent.id());
			}
			warnings.note("open_air_kept_ceiling", detail);
		}
		spot = packer.place(wantX, wantZ, Scale.cellToBlock(map.cellWidth()), Scale.cellToBlock(map.cellHeight()), parent.layer() + 1);
		if (spot == null) {
			warnings.warn("interior_unplaced", detail);
			return null;
		}
		if (spot.deep()) {
			// PLAN §8: the chain ran out of depth; it went sideways on the deepest layer, under the void fill.
			warnings.note("interior_deep_spill", detail);
		} else if (spot.moved()) {
			warnings.note("interior_moved_sideways", detail);
		}
		String biome = CAVE_TILESET.equals(map.tileset()) || wild ? WorldPlan.BIOME_CAVE : WorldPlan.BIOME_INTERIOR;
		return new MapPlacement(map.id(), spot.x(), Scale.feetY(spot.layer()), spot.z(), map.cellWidth(), map.cellHeight(), spot.layer(),
				MapPlacement.Kind.INTERIOR, map.palette(), biome, map.dark(), parent.id());
	}

	/**
	 * The spawn: on the root map when it is built, else the first requested surface map, else the first built map. The
	 * walkable cell closest to the map's centre that has no story cell, warp, door or grass.
	 */
	static @Nullable WorldPlan.Spawn chooseSpawn(List<PlacedMap> placed, String root, List<String> requested, Map<String, int[]> storyIndex) {
		PlacedMap target = null;
		for (PlacedMap map : placed) {
			if (map.id().equals(root) && !map.interior()) {
				target = map;
			}
		}
		if (target == null) {
			for (String id : requested) {
				for (PlacedMap map : placed) {
					if (target == null && map.id().equals(id) && !map.interior()) {
						target = map;
					}
				}
			}
		}
		if (target == null && !placed.isEmpty()) {
			target = placed.get(0);
		}
		if (target == null) {
			return null;
		}
		MapModel model = target.model();
		int[] stories = storyIndex.get(target.id());
		double centreX = (model.cellWidth() - 1) / 2.0;
		double centreY = (model.cellHeight() - 1) / 2.0;
		int bestX = -1;
		int bestY = -1;
		double best = Double.MAX_VALUE;
		int avoid = CellFlags.DOOR | CellFlags.WARP_TILE | CellFlags.WARP | CellFlags.GRASS;
		for (int cy = 0; cy < model.cellHeight(); cy++) {
			for (int cx = 0; cx < model.cellWidth(); cx++) {
				if (model.kind(cx, cy) != MapModel.Kind.GROUND || (model.flags(cx, cy) & avoid) != 0
						|| stories[cy * model.cellWidth() + cx] >= 0) {
					continue;
				}
				double dx = cx - centreX;
				double dy = cy - centreY;
				double distance = dx * dx + dy * dy;
				if (distance < best - 1e-9) {
					best = distance;
					bestX = cx;
					bestY = cy;
				}
			}
		}
		if (bestX < 0) {
			return null;
		}
		MapPlacement p = target.placement();
		return new WorldPlan.Spawn(target.id(), bestX, bestY, p.x() + Scale.cellToBlock(bestX), p.y(), p.z() + Scale.cellToBlock(bestY));
	}
}
