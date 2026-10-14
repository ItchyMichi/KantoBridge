package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.builder.export.ExportSwaps;
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
 * Entries carry what a client needs to follow the hidden game (docs/PROTOCOL.md "Story rules (M8)"): an object's
 * {@code hidden} default; a tile cell's map-data block ({@code start}), whether its open (built) state is water, and its
 * closed state (the closing block, how it lets players through, and the closed art as slots of the slot map, with its
 * column height).
 */
public final class StoryCellPlanner {
	/** Strength boulders are map objects with this sprite (refs/_research/P3_gen1_data_export.md §2.2). */
	public static final String SPRITE_BOULDER = "SPRITE_BOULDER";

	/** The full-shape slot index of a tile of an atlas in a palette, or -1 when the slot map has none. */
	@FunctionalInterface
	public interface ArtSlots {
		int slot(String atlas, String palette, int tile);
	}

	private StoryCellPlanner() {
	}

	public static StoryCellTable plan(List<PlacedMap> maps, Warnings warnings, ArtSlots slots) {
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
					warnings.warn("object_out_of_bounds", Warnings.symbols("map", id, "obj", object.name()));
					continue;
				}
				add(out, model, new StoryCellTable.Entry(new CellPos(id, object.x(), object.y()), StoryCellType.OBJECT, object.name(), null,
						object.hidden(), null, null), warnings);
			}

			// Sites in block order, so the result doesn't depend on hash-map iteration.
			for (ExportSwaps.Site site : new TreeMap<>(model.sites()).values()) {
				for (int dy = 0; dy < 2; dy++) {
					for (int dx = 0; dx < 2; dx++) {
						int cx = site.bx() * 2 + dx;
						int cy = site.by() * 2 + dy;
						if (model.changes(cx, cy)) {
							StoryCellTable.Closed closed = closedState(placed, cx, cy, slots, warnings);
							add(out, model, new StoryCellTable.Entry(new CellPos(id, cx, cy), StoryCellType.TILE, null, site.toSwap(), false,
									model.dataBlock(cx, cy), closed, model.kind(cx, cy) == MapModel.Kind.WATER), warnings);
						}
					}
				}
			}

			for (int cy = 0; cy < model.cellHeight(); cy++) {
				for (int cx = 0; cx < model.cellWidth(); cx++) {
					if (CellFlags.has(map.flags(cx, cy), CellFlags.CUT_TREE) && model.site(cx, cy) == null) {
						warnings.warn("cut_tree_without_swap", Warnings.symbols("map", id));
					}
					if (model.kind(cx, cy) == MapModel.Kind.WATER) {
						add(out, model, new StoryCellTable.Entry(new CellPos(id, cx, cy), StoryCellType.WATER, null, null), warnings);
					}
				}
			}

			if (boulders) {
				for (int cy = 0; cy < model.cellHeight(); cy++) {
					for (int cx = 0; cx < model.cellWidth(); cx++) {
						if (model.kind(cx, cy) == MapModel.Kind.GROUND) {
							add(out, model, new StoryCellTable.Entry(new CellPos(id, cx, cy), StoryCellType.BOULDER_AREA, null, null), warnings);
						}
					}
				}
			}
		}
		return StoryCellTable.of(new ArrayList<>(out.values()));
	}

	/**
	 * A changing cell's closed state, read from the map's closed variant: the block that closes it, how the closed cell
	 * lets players through (walkable ground: everyone; water: surfers; anything else: nobody), the height of its column
	 * above the floor block (0 for flat art), and the four tiles it then shows as full-shape slots.
	 */
	static StoryCellTable.Closed closedState(PlacedMap placed, int cx, int cy, ArtSlots slots, Warnings warnings) {
		MapModel model = placed.model();
		MapModel closed = model.closedModel();
		MapModel.Kind kind = closed.kind(cx, cy);
		int height = switch (kind) {
			case SOLID, COUNTER -> closed.height(cx, cy);
			default -> 0;
		};
		List<Integer> art = new ArrayList<>(StoryCellTable.ART_BLOCKS);
		for (int dz = 0; dz < 2; dz++) {
			for (int dx = 0; dx < 2; dx++) {
				int slot = slots.slot(placed.atlas(), placed.palette(), closed.tile(cx * 2 + dx, cy * 2 + dz));
				if (slot < 0) {
					// The art catalogue lists both blocks of every site, so this is a bug, not bad data.
					warnings.warn("closed_art_missing", Warnings.symbols("map", model.id()));
					slot = 0;
				}
				art.add(slot);
			}
		}
		StoryCellTable.Pass pass = switch (kind) {
			case GROUND -> StoryCellTable.Pass.GROUND;
			case WATER -> StoryCellTable.Pass.WATER;
			default -> StoryCellTable.Pass.SOLID;
		};
		return new StoryCellTable.Closed(model.closedBlock(cx, cy), pass, Math.min(height, StoryCellTable.MAX_CLOSED_HEIGHT), art);
	}

	static void add(Map<String, StoryCellTable.Entry> out, MapModel model, StoryCellTable.Entry entry, Warnings warnings) {
		String key = entry.key();
		if (out.containsKey(key)) {
			return;
		}
		MapModel.Kind kind = model.kind(entry.cell().x(), entry.cell().y());
		if (kind != MapModel.Kind.GROUND && kind != MapModel.Kind.WATER) {
			// Terrain already closes the cell for everyone (e.g. an item on a table), so no story cell is needed.
			warnings.note("story_cell_on_solid");
			return;
		}
		out.put(key, entry);
	}
}
