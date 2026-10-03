package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportLayout;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.builder.export.ExportPalettes;
import dev.kantobridge.builder.export.ExportSwaps;
import dev.kantobridge.builder.export.ExportTileset;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	 */
	public record Options(@Nullable List<String> maps, int originX, int originZ, int minBuildHeight) {
	}

	private WorldPlanner() {
	}

	public static WorldPlan plan(ExportBundle bundle, Options options) throws BundleException, PlanException {
		Warnings warnings = new Warnings();

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
					warnings.warn("filter_unknown_map");
				}
			}
		}
		int mapsRequested = options.maps() == null ? requested.size() : new LinkedHashSet<>(options.maps()).size();
		Set<String> requestedSet = new HashSet<>(requested);

		// ---- surface layout ----
		Map<String, ExportMap> surface = new LinkedHashMap<>();
		for (ExportMap map : all.values()) {
			if (map.surface()) {
				surface.put(map.id(), map);
			}
		}
		ExportLayout exported = bundle.has("layout", "kanto") ? bundle.layout() : null;
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
		InteriorPacker packer = new InteriorPacker(Scale.maxLayer(options.minBuildHeight()));
		List<ExportMap> pending = new ArrayList<>();
		for (String id : requested) {
			ExportMap map = all.get(id);
			if (map.surface()) {
				continue;
			}
			if (map.parent() == null) {
				// Only an explicit filter gets here (a full build leaves unreachable maps out): asked for, can't be placed.
				warnings.warn("map_unreachable");
			} else if (!requestedSet.contains(map.parent().map())) {
				warnings.warn("interior_parent_not_built");
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
				MapPlacement placed = placeInterior(map, parent, all.get(parent.id()), packer, bundle, warnings);
				if (placed != null) {
					placements.put(map.id(), placed);
				}
			}
			pending = next;
		}
		warnings.warn("interior_parent_unplaced", pending.size());

		// ---- cell models ----
		Map<String, List<ExportSwaps.Site>> swapsByMap = new HashMap<>();
		for (ExportSwaps.Site site : bundle.swaps().sites()) {
			swapsByMap.computeIfAbsent(site.map(), k -> new ArrayList<>()).add(site);
		}
		Map<String, ExportTileset> tilesets = new HashMap<>();
		ExportPalettes palettes = bundle.has("palettes", "sgb") ? bundle.palettes() : null;
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
			placed.add(new PlacedMap(placement, MapModel.build(map, tileset, swapsByMap.getOrDefault(map.id(), List.of()), interior, warnings)));
		}

		// ---- surface void fill: each gap cell takes the border art of its nearest surface map ----
		Box3 surfaceBox = WorldPlan.surfaceVolume(placements.values());
		int[] owner = new int[0];
		boolean[] voidCells = new boolean[0];
		if (surfaceBox != null) {
			int w = (surfaceBox.maxX() - surfaceBox.minX()) / Scale.CELL_BLOCKS;
			int d = (surfaceBox.maxZ() - surfaceBox.minZ()) / Scale.CELL_BLOCKS;
			owner = new int[w * d];
			voidCells = new boolean[w * d];
			Arrays.fill(owner, -1);
			ArrayDeque<Integer> queue = new ArrayDeque<>();
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
						if (owner[i] < 0) {
							owner[i] = m;
							queue.add(i);
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
		}

		// ---- story cells ----
		StoryCellTable storyCells = StoryCellPlanner.plan(placed, warnings);
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

		// ---- slot map: from the whole export, so it (and the shared resource pack) never depends on the filter ----
		ArtCatalog art = ArtCatalog.of(bundle, warnings);
		SlotMap slotMap = art.slotMap();

		// ---- spawn and index ----
		WorldPlan.Spawn spawn = chooseSpawn(placed, root, requested, storyIndex);
		if (spawn == null) {
			warnings.warn("spawn_missing");
		}
		WorldIndex index = new WorldIndex(bundle.game(), bundle.exportHash(), placements.values(), slotMap.hash(), storyCells, List.of());
		return new WorldPlan(bundle.game(), bundle.exportHash(), options.originX(), options.originZ(), placed, surfaceBox, owner, voidCells,
				storyCells, storyIndex, art, spawn, index, warnings, mapsRequested, layout.edgeMismatches(), layout.overlaps());
	}

	private static @Nullable MapPlacement placeInterior(ExportMap map, MapPlacement parent, ExportMap parentMap, InteriorPacker packer,
			ExportBundle bundle, Warnings warnings) throws BundleException {
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
			warnings.warn("interior_arrival_missing");
			arrivalX = map.warps().get(0).x();
			arrivalY = map.warps().get(0).y();
		} else {
			warnings.warn("interior_arrival_missing");
		}
		InteriorPacker.Spot spot = packer.place(doorX - Scale.cellToBlock(arrivalX), doorZ - Scale.cellToBlock(arrivalY),
				Scale.cellToBlock(map.cellWidth()), Scale.cellToBlock(map.cellHeight()), parent.layer() + 1);
		if (spot == null) {
			warnings.warn("interior_unplaced");
			return null;
		}
		if (spot.moved()) {
			warnings.note("interior_moved_sideways");
		}
		String biome = CAVE_TILESET.equals(map.tileset()) ? WorldPlan.BIOME_CAVE : WorldPlan.BIOME_INTERIOR;
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
