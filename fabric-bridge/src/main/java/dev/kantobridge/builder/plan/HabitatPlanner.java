package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportEncounters;
import dev.kantobridge.builder.export.ExportField;
import dev.kantobridge.world.MapLayout;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Where habitat blocks go (docs/PLAN.md §4.5 "Placing blocks", P8): for every built map with a grass table, Cobblemon
 * habitat blocks whose spheres of influence cover the cells wild Pokémon live on: tall grass, or every floor cell of an
 * indoor encounter map (caves, the Mansion, …; the engine's {@code indoorEncounters} rule). None in the Safari Zone,
 * whose battles stay native (P9), and none in the Pokémon Tower yet: the hidden game runs its ghost encounters there
 * until the player has the Silph Scope (M8 decides how the Tower's wild Pokémon cross over). Pure Java.
 *
 * <p>Each block sits 2 blocks under the ground block (3 under the feet), so Cobblemon's 3-D range check
 * ({@code distSqr(floor block, habitat) ≤ range²}) reaches {@code √(14² − 2²) ≈ 13.9} blocks across at the floor. A
 * greedy cover picks blocks until every floor block of every such cell is within reach: each round covers the first
 * uncovered floor block in scan order with the candidate (a target cell's north-west block) that covers the most
 * uncovered blocks, on a tie the one nearest the middle of what it covers, so spheres stay centred on their patch.
 */
public final class HabitatPlanner {
	/** {@code RangeOfInfluence} of a habitat block (PLAN §4.5). */
	public static final int RANGE = WorldIndex.HABITAT_RANGE;
	/**
	 * The same in the cave area, whose floors lie 11 blocks apart: a floor's pool never reaches another floor's cells
	 * (docs/specs/CAVES_3D_PLAN.md §3.3, HD8).
	 */
	public static final int CAVE_RANGE = 6;
	/** Habitat blocks are 2 blocks under the ground block, which is 1 under the feet. */
	public static final int BELOW_FEET = 3;
	/** Vertical distance from a habitat block to the floor blocks it serves (Cobblemon measures from the floor block). */
	public static final int FLOOR_ABOVE = BELOW_FEET - 1;
	/** Squared horizontal reach at the floor. */
	public static final int REACH2 = RANGE * RANGE - FLOOR_ABOVE * FLOOR_ABOVE;
	public static final String POOL_NAMESPACE = "kantobridge";
	/** Safari maps by name, should an export lack the {@code safari} table. */
	static final String SAFARI_PREFIX = "SAFARI_ZONE";
	/** The Pokémon Tower's floors: their encounters stay in the hidden game for now (ghosts until the Silph Scope). */
	static final String TOWER_PREFIX = "POKEMON_TOWER";

	private HabitatPlanner() {
	}

	/** The habitat pool of a map: {@code kantobridge:<map id in lower case>}. */
	public static String poolId(String map) {
		return POOL_NAMESPACE + ":" + poolPath(map);
	}

	/** The pool's path, also its file name in the datapack. */
	public static String poolPath(String map) {
		return map.toLowerCase(Locale.ROOT);
	}

	public static boolean safari(String map, ExportField field) {
		return field.safariMaps().contains(map) || map.startsWith(SAFARI_PREFIX);
	}

	/** True when the hidden game runs this map's wild battles itself: the Safari Zone, and the Pokémon Tower for now. */
	public static boolean nativeEncounters(String map, ExportField field) {
		return safari(map, field) || map.startsWith(TOWER_PREFIX);
	}

	/**
	 * True when wild Pokémon of this map come from a habitat pool: a grass table that rolls, on a map whose encounters
	 * aren't the hidden game's own.
	 */
	public static boolean wild(String map, ExportEncounters encounters, ExportField field) {
		ExportEncounters.Table table = encounters.grass().get(map);
		return table != null && table.rate() > 0 && !nativeEncounters(map, field);
	}

	/** Every map of the export whose wild Pokémon come from a habitat pool, sorted. */
	public static Set<String> wildMaps(ExportEncounters encounters, ExportField field) {
		Set<String> out = new TreeSet<>();
		for (String map : encounters.grass().keySet()) {
			if (wild(map, encounters, field)) {
				out.add(map);
			}
		}
		return out;
	}

	/** The habitat blocks for the built maps, sorted by map, then Z, then X. */
	public static List<WorldIndex.Habitat> plan(List<PlacedMap> placed, ExportEncounters encounters, ExportField field, Warnings warnings) {
		List<WorldIndex.Habitat> out = new ArrayList<>();
		for (PlacedMap map : placed) {
			if (!wild(map.id(), encounters, field)) {
				continue;
			}
			boolean indoor = field.indoorEncounters(map.model().map().index(), map.model().map().tileset());
			List<int[]> cells = wildCells(map.model(), indoor);
			if (cells.isEmpty()) {
				// A table without a cell to roll it on (water-only routes with a stray grass table): nothing to place.
				warnings.note("habitat_map_without_cells");
				continue;
			}
			MapPlacement p = map.placement();
			int range = WorldPlan.BIOME_CAVE_3D.equals(p.biome()) ? CAVE_RANGE : RANGE;
			// Raised ground (CAVES_3D_PLAN §3.4): habitat blocks stay under the map's floor, outside its volume, so a level's
			// cells are covered with the reach left over their higher floor; a block two levels both pick is placed once. A
			// cave's rooms lie apart (§3.5): each room's cells are covered under it.
			MapLayout layout = map.layout();
			Map<Long, List<int[]>> groups = new TreeMap<>();
			for (int[] cell : cells) {
				MapLayout.Piece piece = layout == null ? null : layout.pieceAt(cell[0], cell[1]);
				long room = piece == null ? 0 : layout.pieces().indexOf(piece);
				groups.computeIfAbsent(room * (MapLayout.MAX_LEVEL + 1) + map.level(cell[0], cell[1]), k -> new ArrayList<>()).add(cell);
			}
			Set<Long> placedAt = new HashSet<>();
			for (Map.Entry<Long, List<int[]>> group : groups.entrySet()) {
				int level = (int) (group.getKey() % (MapLayout.MAX_LEVEL + 1));
				int[] first = group.getValue().get(0);
				MapLayout.Piece piece = layout == null ? null : layout.pieceAt(first[0], first[1]);
				int dx = piece == null ? 0 : piece.dx();
				int dy = piece == null ? 0 : piece.dy();
				int dz = piece == null ? 0 : piece.dz();
				for (int[] block : cover(p, group.getValue(), range, FLOOR_ABOVE + level)) {
					int x = block[0] + dx;
					int z = block[1] + dz;
					if (placedAt.add(((long) x << 32) ^ (z & 0xFFFFFFFFL))) {
						out.add(new WorldIndex.Habitat(map.id(), x, p.y() + dy - BELOW_FEET, z, poolId(map.id()), range));
					}
				}
			}
		}
		out.sort(Comparator.comparing(WorldIndex.Habitat::map).thenComparingInt(WorldIndex.Habitat::z).thenComparingInt(WorldIndex.Habitat::x));
		return out;
	}

	/** The cells wild Pokémon live on: tall grass, or every walkable floor cell of an indoor encounter map. */
	static List<int[]> wildCells(MapModel model, boolean indoor) {
		List<int[]> cells = new ArrayList<>();
		for (int cy = 0; cy < model.cellHeight(); cy++) {
			for (int cx = 0; cx < model.cellWidth(); cx++) {
				if (model.kind(cx, cy) != MapModel.Kind.GROUND) {
					continue;
				}
				if (indoor || CellFlags.has(model.flags(cx, cy), CellFlags.GRASS)) {
					cells.add(new int[] {cx, cy});
				}
			}
		}
		return cells;
	}

	/**
	 * Greedy cover of the cells' floor blocks; returns habitat columns as absolute {@code {x, z}} block positions. Every
	 * floor block ends within {@link #REACH2} (horizontally) of one of them.
	 */
	public static List<int[]> cover(MapPlacement p, List<int[]> cells) {
		return cover(p, cells, RANGE);
	}

	/** The same for habitat blocks of another {@code RangeOfInfluence}. */
	public static List<int[]> cover(MapPlacement p, List<int[]> cells, int range) {
		return cover(p, cells, range, FLOOR_ABOVE);
	}

	/** The same for floor blocks {@code floorAbove} blocks over the habitat blocks (a raised cave floor's are higher). */
	public static List<int[]> cover(MapPlacement p, List<int[]> cells, int range, int floorAbove) {
		int reach2 = range * range - floorAbove * floorAbove;
		int w = p.w() * Scale.CELL_BLOCKS;
		int h = p.h() * Scale.CELL_BLOCKS;
		boolean[] target = new boolean[w * h];
		int remaining = 0;
		for (int[] cell : cells) {
			for (int dz = 0; dz < Scale.CELL_BLOCKS; dz++) {
				for (int dx = 0; dx < Scale.CELL_BLOCKS; dx++) {
					int i = (cell[1] * Scale.CELL_BLOCKS + dz) * w + cell[0] * Scale.CELL_BLOCKS + dx;
					if (!target[i]) {
						target[i] = true;
						remaining++;
					}
				}
			}
		}
		int reach = (int) Math.floor(Math.sqrt(reach2));
		List<int[]> chosen = new ArrayList<>();
		int first = 0;
		while (remaining > 0) {
			// Each round must cover the first uncovered block in scan order; that keeps the spheres in rows along
			// strips of grass (a third fewer blocks on route-like patches than picking the best anywhere).
			while (!target[first]) {
				first++;
			}
			int tx = first % w;
			int tz = first / w;
			int bestX = -1;
			int bestZ = -1;
			int bestCount = 0;
			double bestSpill = Double.MAX_VALUE;
			for (int[] cell : cells) {
				int cx = cell[0] * Scale.CELL_BLOCKS;
				int cz = cell[1] * Scale.CELL_BLOCKS;
				if ((cx - tx) * (cx - tx) + (cz - tz) * (cz - tz) > reach2) {
					continue;
				}
				int count = 0;
				long sumX = 0;
				long sumZ = 0;
				for (int z = Math.max(0, cz - reach); z <= Math.min(h - 1, cz + reach); z++) {
					for (int x = Math.max(0, cx - reach); x <= Math.min(w - 1, cx + reach); x++) {
						if (target[z * w + x] && (x - cx) * (x - cx) + (z - cz) * (z - cz) <= reach2) {
							count++;
							sumX += x;
							sumZ += z;
						}
					}
				}
				// On a tie, the candidate nearest the middle of what it covers: its sphere spills least beyond the patch.
				double midX = (double) sumX / count;
				double midZ = (double) sumZ / count;
				double spill = (cx - midX) * (cx - midX) + (cz - midZ) * (cz - midZ);
				if (count > bestCount || count == bestCount && spill < bestSpill - 1e-9) {
					bestCount = count;
					bestSpill = spill;
					bestX = cx;
					bestZ = cz;
				}
			}
			if (bestCount == 0) {
				// Can't happen (the first block's own cell corner reaches it); never loop forever.
				break;
			}
			for (int z = Math.max(0, bestZ - reach); z <= Math.min(h - 1, bestZ + reach); z++) {
				for (int x = Math.max(0, bestX - reach); x <= Math.min(w - 1, bestX + reach); x++) {
					if (target[z * w + x] && (x - bestX) * (x - bestX) + (z - bestZ) * (z - bestZ) <= reach2) {
						target[z * w + x] = false;
						remaining--;
					}
				}
			}
			chosen.add(new int[] {p.x() + bestX, p.z() + bestZ});
		}
		return chosen;
	}
}
