package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.ExportSwaps;
import dev.kantobridge.builder.export.G2Map;
import dev.kantobridge.builder.export.G2Tiles;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * A Gen 2 build's story cells (MG9, docs/specs/GEN2_BRIDGE_PLAN.md §7), by Gen 1's rules ({@link StoryCellPlanner}) with Gen
 * 3's objects ({@link G3StoryCells}): an object cell for every object that keeps its cell (item balls and smashable rocks
 * always; people and trainers when the export says they stand), named {@code O<lid>} as the adapter's toggles name them
 * (G2C); a tile cell for every cell a swap site changes (Cut trees, Whirlpools, the blocks scripts change), with its
 * closed state's art from the baked pages; a {@code cell} cell for every cell of a block with several states (the
 * bedroom's decorations, docs/specs/STORY_EVENTS_PLAN.md §5.5: {@link #addBlockStates}); every water cell; and every
 * walkable cell of a map with Strength boulders. A cell gets one entry, earlier kinds first; only ground and water cells
 * can carry one.
 */
public final class G2StoryCells {
	private G2StoryCells() {
	}

	/**
	 * @param maps  the export's maps, by id (each placed map's is there)
	 * @param slots the full-shape slot of a tile (the closed art)
	 * @param tiles a placed map's art variant ({@code g2tiles}), by map id
	 */
	public static StoryCellTable plan(List<PlacedMap> placed, Map<String, G2Map> maps, Warnings warnings, StoryCellPlanner.ArtSlots slots,
			Function<String, G2Tiles> tiles) {
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
					List<Integer> states = model.blockStates(cx, cy);
					if (!states.isEmpty()) {
						addBlockStates(out, map, tiles.apply(id), cx, cy, states, slots, warnings);
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
	 * One cell of a block with several states: a {@code cell} entry as Gen 3's sites get ({@link G3StoryCells}), in block
	 * ids where Gen 3 has metatiles: the map data's block as {@code start}, the built block ({@link MapModel#builtBlock})
	 * and every other block with its pass, height ({@link #height}) and art at the cell (its four tiles as full-shape
	 * slots); the client reads the live state from {@code story.blocks}, the cell's block. It takes the cell from an
	 * object's story cell (the bedroom's big doll and console stand on the carpet): the client closes a {@code cell} cell
	 * while a live object stands on it, as it closes an object cell. Left out, counted: a cell built solid or a ledge (no
	 * story block stands there, so its built art stays: a poster on the wall), and one whose other blocks draw and pass
	 * as the built one there.
	 */
	static void addBlockStates(Map<String, StoryCellTable.Entry> out, PlacedMap map, G2Tiles tiles, int cx, int cy, List<Integer> blocks,
			StoryCellPlanner.ArtSlots slots, Warnings warnings) {
		MapModel model = map.model();
		MapModel.Kind kind = model.kind(cx, cy);
		if (kind != MapModel.Kind.GROUND && kind != MapModel.Kind.WATER) {
			warnings.note("g2_states_solid");
			return;
		}
		CellPos cell = new CellPos(map.id(), cx, cy);
		StoryCellTable.Entry before = out.get(cell.key());
		if (before != null && before.type() != StoryCellType.OBJECT) {
			warnings.note("g2_states_taken");
			return;
		}
		int qx = cx % 2;
		int qy = cy % 2;
		int built = model.builtBlock(cx, cy);
		StoryCellTable.Pass builtPass = G2Cells.pass(tiles, built, qx, qy);
		List<StoryCellTable.CellState> states = new ArrayList<>();
		boolean differs = false;
		for (int block : blocks) {
			if (block == built) {
				continue;
			}
			if (states.size() == StoryCellTable.MAX_CELL_STATES) {
				warnings.warn("g2_states_too_many", Warnings.symbols("map", map.id()));
				break;
			}
			StoryCellTable.Pass pass = G2Cells.pass(tiles, block, qx, qy);
			differs |= pass != builtPass || !sameArt(tiles, block, built, qx, qy);
			states.add(new StoryCellTable.CellState(block, pass, height(tiles, block, qx, qy, pass, map.interior()),
					art(map, tiles, block, qx, qy, slots, warnings)));
		}
		if (!differs) {
			warnings.note("g2_states_no_other_state");
			return;
		}
		if (before != null) {
			warnings.note("g2_states_over_object");
		}
		out.put(cell.key(), new StoryCellTable.Entry(cell, model.dataBlock(cx, cy), new StoryCellTable.Built(built, builtPass), states));
	}

	/** How tall a state's art stands: a counter's height, an interior's wall for anything else solid, flat otherwise. */
	static int height(G2Tiles tiles, int block, int qx, int qy, StoryCellTable.Pass pass, boolean interior) {
		if (pass != StoryCellTable.Pass.SOLID) {
			return 0;
		}
		if (tiles.meaning(tiles.coll(block, qx, qy)).counter()) {
			return HeightRules.COUNTER;
		}
		return interior ? HeightRules.INTERIOR_WALL : HeightRules.WALL;
	}

	private static boolean sameArt(G2Tiles tiles, int a, int b, int qx, int qy) {
		for (int ty = qy * 2; ty < qy * 2 + 2; ty++) {
			for (int tx = qx * 2; tx < qx * 2 + 2; tx++) {
				if (tiles.tile(a, tx, ty) != tiles.tile(b, tx, ty)) {
					return false;
				}
			}
		}
		return true;
	}

	private static List<Integer> art(PlacedMap map, G2Tiles tiles, int block, int qx, int qy, StoryCellPlanner.ArtSlots slots, Warnings warnings) {
		List<Integer> art = new ArrayList<>(StoryCellTable.ART_BLOCKS);
		for (int dz = 0; dz < 2; dz++) {
			for (int dx = 0; dx < 2; dx++) {
				int slot = slots.slot(map.atlas(), map.palette(), tiles.tile(block, qx * 2 + dx, qy * 2 + dz));
				if (slot < 0) {
					// The art catalogue lists every block of every site, so this is a bug, not bad data.
					warnings.warn("g2_states_art_missing", Warnings.symbols("map", map.id()));
					slot = 0;
				}
				art.add(slot);
			}
		}
		return art;
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
