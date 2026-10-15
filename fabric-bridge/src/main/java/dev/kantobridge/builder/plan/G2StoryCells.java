package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.ExportSwaps;
import dev.kantobridge.builder.export.G2Map;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A Gen 2 build's story cells (MG9, docs/specs/GEN2_BRIDGE_PLAN.md §7), by Gen 1's rules ({@link StoryCellPlanner}) with Gen
 * 3's objects ({@link G3StoryCells}): an object cell for every object that keeps its cell (item balls and smashable rocks
 * always; people and trainers when the export says they stand), named {@code O<lid>} as the adapter's toggles name them
 * (G2C); a tile cell for every cell a swap site changes (Cut trees, Whirlpools, the blocks scripts change), with its
 * closed state's art from the baked pages; every water cell; and every walkable cell of a map with Strength boulders. A
 * cell gets one entry, earlier kinds first; only ground and water cells can carry one.
 */
public final class G2StoryCells {
	private G2StoryCells() {
	}

	/**
	 * @param maps  the export's maps, by id (each placed map's is there)
	 * @param slots the full-shape slot of a tile (the closed art)
	 */
	public static StoryCellTable plan(List<PlacedMap> placed, Map<String, G2Map> maps, Warnings warnings, StoryCellPlanner.ArtSlots slots) {
		Map<String, StoryCellTable.Entry> out = new LinkedHashMap<>();
		for (PlacedMap map : placed) {
			MapModel model = map.model();
			G2Map g2 = maps.get(map.id());
			String id = map.id();
			boolean boulders = false;
			for (G2Map.MapObject object : g2.objects()) {
				if ("boulder".equals(object.kind())) {
					boulders = true;
					continue;
				}
				if (!stays(object)) {
					continue;
				}
				if (!g2.inCells(object.x(), object.y())) {
					warnings.warn("object_out_of_bounds", Warnings.symbols("map", id, "obj", object.name()));
					continue;
				}
				StoryCellPlanner.add(out, model, new StoryCellTable.Entry(new CellPos(id, object.x(), object.y()), StoryCellType.OBJECT,
						object.name(), null), warnings);
			}
			// Sites in block order, so the result doesn't depend on hash-map iteration.
			for (ExportSwaps.Site site : new TreeMap<>(model.sites()).values()) {
				for (int dy = 0; dy < 2; dy++) {
					for (int dx = 0; dx < 2; dx++) {
						int cx = site.bx() * 2 + dx;
						int cy = site.by() * 2 + dy;
						if (model.changes(cx, cy)) {
							StoryCellTable.Closed closed = StoryCellPlanner.closedState(map, cx, cy, slots, warnings);
							StoryCellPlanner.add(out, model, new StoryCellTable.Entry(new CellPos(id, cx, cy), StoryCellType.TILE, null,
									site.toSwap(), false, model.dataBlock(cx, cy), closed, model.kind(cx, cy) == MapModel.Kind.WATER), warnings);
						}
					}
				}
			}
			for (int cy = 0; cy < model.cellHeight(); cy++) {
				for (int cx = 0; cx < model.cellWidth(); cx++) {
					if (model.kind(cx, cy) == MapModel.Kind.WATER) {
						StoryCellPlanner.add(out, model, new StoryCellTable.Entry(new CellPos(id, cx, cy), StoryCellType.WATER, null, null), warnings);
					}
				}
			}
			if (boulders) {
				for (int cy = 0; cy < model.cellHeight(); cy++) {
					for (int cx = 0; cx < model.cellWidth(); cx++) {
						if (model.kind(cx, cy) == MapModel.Kind.GROUND) {
							StoryCellPlanner.add(out, model, new StoryCellTable.Entry(new CellPos(id, cx, cy), StoryCellType.BOULDER_AREA, null, null),
									warnings);
						}
					}
				}
			}
		}
		return StoryCellTable.of(new ArrayList<>(out.values()));
	}

	/**
	 * True when an object keeps its cell for the story cells: item balls and smashable rocks never move; people and
	 * trainers when the export says they stand.
	 */
	static boolean stays(G2Map.MapObject object) {
		return switch (object.kind()) {
			case "item", "rock" -> true;
			case "person", "trainer" -> object.stays();
			default -> false;
		};
	}
}
