package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.builder.export.ExportSwaps;
import dev.kantobridge.builder.export.ExportTileset;
import dev.kantobridge.world.SlotMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Every piece of art the whole export can put on screen, and the slot map that names it (docs/PLAN.md §4.5 "Slot map,
 * textures and pack").
 *
 * <p>It depends on the export alone, never on which maps a build places: the generated resource pack is shared by
 * every world in a game folder, so a filtered or test build must write exactly the pack (and slot-map hash) a full
 * build writes. The keys cover, for every map: each tile its blocks show (half-height where the cell is water), its
 * border block (interior rings and ceilings, the surface void fill), and the closed art of its swap sites.
 */
public final class ArtCatalog {
	private final SlotMap slotMap;
	private final Map<String, Integer> tilesPerRow;
	private final Map<String, Set<Integer>> waterTiles;
	private final Set<String> flowerAtlases;
	private final Set<String> npcs;

	private ArtCatalog(SlotMap slotMap, Map<String, Integer> tilesPerRow, Map<String, Set<Integer>> waterTiles, Set<String> flowerAtlases,
			Set<String> npcs) {
		this.slotMap = slotMap;
		this.tilesPerRow = Collections.unmodifiableMap(tilesPerRow);
		this.waterTiles = Collections.unmodifiableMap(waterTiles);
		this.flowerAtlases = Collections.unmodifiableSet(flowerAtlases);
		this.npcs = Collections.unmodifiableSet(npcs);
	}

	/**
	 * Catalogues the whole export. Maps whose tileset is missing are left out (noted); a build that places one fails
	 * later on its own.
	 */
	public static ArtCatalog of(ExportBundle bundle, Warnings warnings) throws BundleException, PlanException {
		Map<String, List<ExportSwaps.Site>> swapsByMap = new HashMap<>();
		for (ExportSwaps.Site site : bundle.swaps().sites()) {
			swapsByMap.computeIfAbsent(site.map(), k -> new ArrayList<>()).add(site);
		}
		Set<SlotMap.Key> keys = new HashSet<>();
		Map<String, Integer> tilesPerRow = new TreeMap<>();
		Map<String, Set<Integer>> waterTiles = new TreeMap<>();
		Set<String> flowerAtlases = new TreeSet<>();
		Set<String> npcs = new TreeSet<>();
		Map<String, ExportTileset> tilesets = new HashMap<>();
		// Anomalies in maps are the planner's to report for the maps it places; here they would only repeat.
		Warnings scratch = new Warnings();
		for (String id : bundle.ids("map")) {
			ExportMap map = bundle.map(id);
			ExportTileset tileset = tilesets.get(map.tileset());
			if (tileset == null) {
				if (!bundle.has("tileset", map.tileset())) {
					warnings.note("catalog_tileset_missing");
					continue;
				}
				tileset = bundle.tileset(map.tileset());
				tilesets.put(map.tileset(), tileset);
			}
			String atlas = tileset.image();
			String palette = map.palette();
			tilesPerRow.putIfAbsent(atlas, tileset.tilesPerRow());
			if (tileset.animatesWater()) {
				Set<Integer> water = waterTiles.computeIfAbsent(atlas, k -> new TreeSet<>());
				for (int tile : tileset.water()) {
					if (!tileset.shore().contains(tile)) {
						water.add(tile);
					}
				}
			}
			if (tileset.animatesFlowers()) {
				flowerAtlases.add(atlas);
			}
			for (ExportMap.MapObject object : map.objects()) {
				npcs.add(palette + "|" + object.sprite());
			}

			List<ExportSwaps.Site> sites = swapsByMap.getOrDefault(id, List.of());
			MapModel model = MapModel.build(map, tileset, sites, !map.surface(), scratch);
			for (int ty = 0; ty < model.cellHeight() * 2; ty++) {
				for (int tx = 0; tx < model.cellWidth() * 2; tx++) {
					boolean half = model.kind(tx / 2, ty / 2) == MapModel.Kind.WATER;
					keys.add(new SlotMap.Key(atlas, palette, model.tile(tx, ty), half ? SlotMap.Shape.HALF : SlotMap.Shape.FULL));
				}
			}
			for (int t = 0; t < 16; t++) {
				keys.add(new SlotMap.Key(atlas, palette, model.borderTile(t % 4, t / 4), SlotMap.Shape.FULL));
			}
			for (ExportSwaps.Site site : sites) {
				for (int t = 0; t < 16; t++) {
					int before = tileset.tile(site.before(), t % 4, t / 4);
					if (before >= 0) {
						keys.add(new SlotMap.Key(atlas, palette, before, SlotMap.Shape.FULL));
					}
				}
			}
		}
		SlotMap slotMap;
		try {
			slotMap = SlotMap.enumerate(keys);
		} catch (SlotMap.FullException e) {
			throw new PlanException("slot_map.full", e.getMessage());
		}
		return new ArtCatalog(slotMap, tilesPerRow, waterTiles, flowerAtlases, npcs);
	}

	/** The slot map of the whole export. */
	public SlotMap slotMap() {
		return this.slotMap;
	}

	/** Tiles per atlas row, by atlas image id. */
	public int tilesPerRow(String atlas) {
		return this.tilesPerRow.getOrDefault(atlas, 16);
	}

	/** Atlas tiles that sway like Gen 1 water (the tileset's water tiles that aren't shore, where it animates water). */
	public Set<Integer> waterTiles(String atlas) {
		return this.waterTiles.getOrDefault(atlas, Set.of());
	}

	/** Atlases whose tileset cycles the flower tile. */
	public boolean animatesFlowers(String atlas) {
		return this.flowerAtlases.contains(atlas);
	}

	/** Every (palette, sprite) pair an object of some map needs, as {@code "PALETTE|SPRITE"}, sorted. */
	public Set<String> npcs() {
		return this.npcs;
	}
}
