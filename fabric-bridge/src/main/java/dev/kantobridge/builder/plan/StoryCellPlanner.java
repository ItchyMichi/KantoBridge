package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.builder.export.ExportSwaps;
import dev.kantobridge.builder.export.ExportTileset;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Chooses the story cells (docs/PLAN.md §4.5 "Story cells"): every STAY-object cell, every cell a swap site changes
 * (Cut trees, card-key doors, curated swaps), every water cell, and every walkable cell of maps with Strength
 * boulders. A cell gets one entry; earlier kinds win (object, then tile, water, boulder area).
 *
 * <p>Story-cell blocks sit at feet level, so only cells with nothing built there (ground and water) can carry one.
 */
public final class StoryCellPlanner {
	/** Strength boulders are map objects with this sprite (refs/_research/P3_gen1_data_export.md §2.2). */
	public static final String SPRITE_BOULDER = "SPRITE_BOULDER";

	private StoryCellPlanner() {
	}

	public static StoryCellTable plan(List<PlacedMap> maps, Warnings warnings) {
		Map<String, StoryCellTable.Entry> out = new LinkedHashMap<>();
		for (PlacedMap placed : maps) {
			MapModel model = placed.model();
			ExportMap map = model.map();
			String id = map.id();

			boolean boulders = false;
			for (ExportMap.MapObject object : map.objects()) {
				if (SPRITE_BOULDER.equals(object.sprite())) {
					boulders = true;
					continue;
				}
				if (!object.stays()) {
					continue;
				}
				if (object.x() < 0 || object.y() < 0 || object.x() >= model.cellWidth() || object.y() >= model.cellHeight()) {
					warnings.warn("object_out_of_bounds");
					continue;
				}
				add(out, model, object.x(), object.y(), StoryCellType.OBJECT, object.name(), null, warnings);
			}

			// Sites in block order, so the result doesn't depend on hash-map iteration.
			for (ExportSwaps.Site site : new TreeMap<>(model.sites()).values()) {
				for (int dy = 0; dy < 2; dy++) {
					for (int dx = 0; dx < 2; dx++) {
						int cx = site.bx() * 2 + dx;
						int cy = site.by() * 2 + dy;
						if (changes(model.tileset(), site, cx, cy)) {
							add(out, model, cx, cy, StoryCellType.TILE, null, site.toSwap(), warnings);
						}
					}
				}
			}

			for (int cy = 0; cy < model.cellHeight(); cy++) {
				for (int cx = 0; cx < model.cellWidth(); cx++) {
					if (CellFlags.has(map.flags(cx, cy), CellFlags.CUT_TREE) && model.site(cx, cy) == null) {
						warnings.warn("cut_tree_without_swap");
					}
					if (model.kind(cx, cy) == MapModel.Kind.WATER) {
						add(out, model, cx, cy, StoryCellType.WATER, null, null, warnings);
					}
				}
			}

			if (boulders) {
				for (int cy = 0; cy < model.cellHeight(); cy++) {
					for (int cx = 0; cx < model.cellWidth(); cx++) {
						if (model.kind(cx, cy) == MapModel.Kind.GROUND) {
							add(out, model, cx, cy, StoryCellType.BOULDER_AREA, null, null, warnings);
						}
					}
				}
			}
		}
		return StoryCellTable.of(new ArrayList<>(out.values()));
	}

	private static void add(Map<String, StoryCellTable.Entry> out, MapModel model, int cx, int cy, StoryCellType type, String obj,
			StoryCellTable.Swap swap, Warnings warnings) {
		String key = CellPos.key(model.id(), cx, cy);
		if (out.containsKey(key)) {
			return;
		}
		MapModel.Kind kind = model.kind(cx, cy);
		if (kind != MapModel.Kind.GROUND && kind != MapModel.Kind.WATER) {
			// Terrain already closes the cell for everyone (e.g. an item on a table), so no story cell is needed.
			warnings.note("story_cell_on_solid");
			return;
		}
		out.put(key, new StoryCellTable.Entry(new CellPos(model.id(), cx, cy), type, obj, swap));
	}

	/** True when a site changes cell (cx, cy): its art or its passability differs between the two blocks. */
	static boolean changes(ExportTileset tileset, ExportSwaps.Site site, int cx, int cy) {
		for (int dy = 0; dy < 2; dy++) {
			for (int dx = 0; dx < 2; dx++) {
				int tx = cx * 2 + dx;
				int ty = cy * 2 + dy;
				if (tileset.tile(site.before(), tx, ty) != tileset.tile(site.after(), tx, ty)) {
					return true;
				}
			}
		}
		int before = tileset.tile(site.before(), cx * 2, cy * 2 + 1);
		int after = tileset.tile(site.after(), cx * 2, cy * 2 + 1);
		return tileset.walkable().contains(before) != tileset.walkable().contains(after);
	}
}
