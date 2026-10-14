package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.G3Map;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Gen 3 build's story cells (MG7, docs/specs/GEN3_BRIDGE_PLAN.md §4.6, §6), by Gen 1's rules
 * ({@link StoryCellPlanner}) where Gen 3 has the same thing: an object cell for every object that keeps its cell (item
 * balls and berry trees always; people and trainers when the export says they stand), named {@code O<lid>} as the
 * adapter's story toggles and live objects name them (G3C); every water cell; and every walkable cell of a map with
 * Strength boulders. Gen 3 has no swap sites (its terrain changes arrive as {@code story.cells} at run time, G3C), so no
 * tile cells. A cell gets one entry, earlier kinds first; only ground and water cells can carry one.
 */
public final class G3StoryCells {
	private G3StoryCells() {
	}

	/** @param maps the export's maps, by id (each placed map's is there) */
	public static StoryCellTable plan(List<PlacedMap> placed, Map<String, G3Map> maps, Warnings warnings) {
		Map<String, StoryCellTable.Entry> out = new LinkedHashMap<>();
		for (PlacedMap map : placed) {
			MapModel model = map.model();
			G3Map g3 = maps.get(map.id());
			String id = map.id();
			boolean boulders = false;
			for (G3Map.MapObject object : g3.objects()) {
				if ("boulder".equals(object.kind())) {
					boulders = true;
					continue;
				}
				if (!stays(object)) {
					continue;
				}
				if (!g3.inGrid(object.x(), object.y())) {
					warnings.warn("object_out_of_bounds", Warnings.symbols("map", id, "obj", object.name()));
					continue;
				}
				StoryCellPlanner.add(out, model, new StoryCellTable.Entry(new CellPos(id, object.x(), object.y()), StoryCellType.OBJECT,
						object.name(), null), warnings);
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
	 * True when an object keeps its cell for the story cells: item balls and berry trees never move; people and trainers
	 * when the export says they stand (an older export that doesn't say leaves them out, as walkers are).
	 */
	static boolean stays(G3Map.MapObject object) {
		return switch (object.kind()) {
			case "item", "berry" -> true;
			case "person", "trainer", "other" -> Boolean.TRUE.equals(object.stays());
			default -> false;
		};
	}
}
