package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.G3Map;
import dev.kantobridge.builder.export.G3Sites;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import org.jetbrains.annotations.Nullable;

/**
 * A Gen 3 build's story cells (MG7, docs/specs/GEN3_BRIDGE_PLAN.md §4.6, §6), by Gen 1's rules
 * ({@link StoryCellPlanner}) where Gen 3 has the same thing: an object cell for every object that keeps its cell (item
 * balls and berry trees always; people and trainers when the export says they stand), named {@code O<lid>} as the
 * adapter's story toggles and live objects name them (G3C); every water cell; and every walkable cell of a map with
 * Strength boulders. SE3 (docs/specs/STORY_EVENTS_PLAN.md §4): a {@code cell} cell for every site of the {@code g3sites}
 * export on a placed map, built in its most walkable state ({@link G3Built}) with its other states' art and passes, so
 * each client draws and collides the state its own hidden game shows (Gen 3 has no swap sites: its terrain changes
 * arrive as {@code story.cells}, and the sites say where they can). A cell gets one entry, earlier kinds first (objects,
 * sites, water, boulder areas); only ground and water cells can carry one, so a site whose every state is solid (a TV, a
 * PC) gets none: the terrain closes it for everyone anyway.
 */
public final class G3StoryCells {
	private G3StoryCells() {
	}

	/** A metatile's four quarters in a tileset pair, or null where the pair lacks it. */
	@FunctionalInterface
	public interface Quarters {
		int @Nullable [] of(String pair, int metatile);
	}

	/** Without sites (an export from before SE2, tests). */
	public static StoryCellTable plan(List<PlacedMap> placed, Map<String, G3Map> maps, Warnings warnings) {
		return plan(placed, maps, G3Sites.empty(), (pair, metatile) -> null, quarter -> -1, warnings);
	}

	/**
	 * @param maps     the export's maps, by id, their sites built (each placed map's is there)
	 * @param sites    the export's changeable cells
	 * @param quarters a metatile's quarters by pair
	 * @param fullSlot a quarter's full-shape slot index, -1 when the slot map lacks it
	 */
	public static StoryCellTable plan(List<PlacedMap> placed, Map<String, G3Map> maps, G3Sites sites, Quarters quarters, IntUnaryOperator fullSlot,
			Warnings warnings) {
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
			for (G3Sites.Site site : sites.of(id)) {
				addSite(out, map, g3, site, quarters, fullSlot, warnings);
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
	 * SE3: one site's {@code cell} entry: the map's own metatile as {@code start}, the built state, and every other state
	 * (the own one too where it isn't built) with its pass, height ({@link #height}) and art (its metatile's quarters as
	 * full-shape slots). Left out, counted: a site off the map's grid, one whose built state is solid or a ledge (no story
	 * block stands there), one under an object's story cell, and a state whose metatile the pair lacks (its art, then 0).
	 */
	static void addSite(Map<String, StoryCellTable.Entry> out, PlacedMap map, G3Map g3, G3Sites.Site site, Quarters quarters, IntUnaryOperator fullSlot,
			Warnings warnings) {
		if (!g3.inGrid(site.x(), site.y())) {
			warnings.warn("g3_site_out_of_bounds", Warnings.symbols("map", map.id()));
			return;
		}
		MapModel.Kind kind = map.model().kind(site.x(), site.y());
		if (kind != MapModel.Kind.GROUND && kind != MapModel.Kind.WATER) {
			warnings.note(kind == MapModel.Kind.LEDGE ? "g3_site_ledge" : "g3_site_solid");
			return;
		}
		CellPos cell = new CellPos(map.id(), site.x(), site.y());
		if (out.containsKey(cell.key())) {
			warnings.note("g3_site_under_object");
			return;
		}
		G3Sites.State built = G3Built.built(site);
		List<G3Sites.State> others = new ArrayList<>();
		if (built != site.own()) {
			others.add(site.own());
		}
		for (G3Sites.State state : site.states()) {
			if (state != built) {
				others.add(state);
			}
		}
		List<StoryCellTable.CellState> states = new ArrayList<>();
		for (G3Sites.State state : others) {
			StoryCellTable.Pass pass = G3Built.pass(state.coll());
			if (state.metatile() == built.metatile() && pass == G3Built.pass(built.coll())) {
				// the same as the built state where the client can tell (metatile and pass): nothing to draw
				continue;
			}
			if (states.stream().anyMatch(s -> s.metatile() == state.metatile() && s.pass() == pass)) {
				continue;
			}
			if (states.size() == StoryCellTable.MAX_CELL_STATES) {
				warnings.warn("g3_site_too_many_states", Warnings.symbols("map", map.id()));
				break;
			}
			states.add(new StoryCellTable.CellState(state.metatile(), pass, height(state.coll(), pass, map.interior()),
					art(g3.pair(), state.metatile(), quarters, fullSlot, map.id(), warnings)));
		}
		if (states.isEmpty()) {
			warnings.note("g3_site_no_other_state");
			return;
		}
		out.put(cell.key(), new StoryCellTable.Entry(cell, site.own().metatile(),
				new StoryCellTable.Built(built.metatile(), G3Built.pass(built.coll())), states));
	}

	/**
	 * How tall a state's art stands: a counter's height, a wall's (an interior's own) for anything else solid, flat on the
	 * floor for ground and water.
	 */
	static int height(int coll, StoryCellTable.Pass pass, boolean interior) {
		if (pass != StoryCellTable.Pass.SOLID) {
			return 0;
		}
		if (coll == G3Cells.COUNTER) {
			return HeightRules.COUNTER;
		}
		return interior ? HeightRules.INTERIOR_WALL : HeightRules.WALL;
	}

	private static List<Integer> art(String pair, int metatile, Quarters quarters, IntUnaryOperator fullSlot, String map, Warnings warnings) {
		int[] q = quarters.of(pair, metatile);
		List<Integer> art = new ArrayList<>(StoryCellTable.ART_BLOCKS);
		boolean missing = q == null;
		for (int i = 0; i < StoryCellTable.ART_BLOCKS; i++) {
			int slot = q == null ? -1 : fullSlot.applyAsInt(q[i]);
			if (slot < 0) {
				missing = true;
				slot = 0;
			}
			art.add(slot);
		}
		if (missing) {
			// The catalogue lists every state's quarters, so a missing slot is the pair lacking the metatile.
			warnings.warn("g3_site_art_missing", Warnings.symbols("map", map));
		}
		return art;
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
