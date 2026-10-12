package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.dungeon.DungeonSlots;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * The cave area (docs/specs/CAVES_3D_PLAN.md §3.3, H3): the caves (maps of the CAVERN tileset, and any interior whose
 * door is in a cave) leave the interior layers for an area of their own north of Kanto, past the dungeon slots, so they
 * have all the height they need. Caves joined by warps form a complex, and each complex gets a slot.
 *
 * <p>In a slot, a cave's floors stack {@link #FLOOR_STEP} blocks apart (HD3), the top floor at feet {@link #TOP_FEET}.
 * A floor's order comes from its id ({@code …2F} above {@code …1F} above {@code …B1F}). Each floor sits so that a
 * ladder between it and a floor already placed lines up, as a door and its interior do today; a floor that would
 * overlap another on its own level moves aside. Until real ladders arrive (H5) every warp between floors still fades.
 * Pure Java.
 */
public final class CavePlanner {
	/** The top floor's feet level. */
	public static final int TOP_FEET = Scale.SURFACE_Y;
	/** Blocks between the feet levels of a cave's floors (HD3). */
	public static final int FLOOR_STEP = 11;
	/** Blocks between the dungeon region and the cave area: farther than any render distance. */
	public static final int GAP = 3000;
	/** Blocks between two complexes' slots. */
	public static final int SLOT_GAP = 64;
	/** A row of slots grows east up to this many blocks. */
	public static final int ROW_WIDTH = 2048;
	/** Blocks of margin around a complex in its slot. */
	public static final int MARGIN = 8;
	/** Blocks kept between two floors of one level of a complex. */
	static final int FLOOR_GAP = 16;
	private static final Pattern BASEMENT = Pattern.compile("_B(\\d+)F$");
	private static final Pattern STOREY = Pattern.compile("_(\\d+)F$");

	/**
	 * @param placements every cave's placement, in the complexes' order
	 * @param area       the cave area's box ({@code minX, minZ, maxX, maxZ}), or null without caves
	 * @param complexes  each complex's maps
	 */
	public record Result(List<MapPlacement> placements, int @Nullable [] area, List<List<String>> complexes) {
		public Result {
			complexes = complexes.stream().map(List::copyOf).toList();
		}
	}

	private CavePlanner() {
	}

	/**
	 * The maps the cave area takes: the requested non-surface maps of the cave tileset, then every requested interior whose
	 * door is in one of them (its parent is a cave), in export order.
	 */
	public static Set<String> caves(Map<String, ExportMap> all, Collection<String> requested, String caveTileset) {
		Set<String> out = new LinkedHashSet<>();
		List<ExportMap> maps = requested.stream().map(all::get).filter(m -> m != null && !m.surface())
				.sorted(Comparator.comparingInt(ExportMap::index).thenComparing(ExportMap::id)).toList();
		for (ExportMap map : maps) {
			if (caveTileset.equals(map.tileset())) {
				out.add(map.id());
			}
		}
		boolean grew = true;
		while (grew) {
			grew = false;
			for (ExportMap map : maps) {
				if (!out.contains(map.id()) && map.parent() != null && out.contains(map.parent().map())) {
					out.add(map.id());
					grew = true;
				}
			}
		}
		return out;
	}

	/**
	 * A floor's number from its map id: {@code …nF} is n − 1, {@code …BnF} is −n, anything else 0.
	 */
	static int floor(String id) {
		Matcher basement = BASEMENT.matcher(id);
		if (basement.find()) {
			return -Integer.parseInt(basement.group(1));
		}
		Matcher storey = STOREY.matcher(id);
		if (storey.find()) {
			return Integer.parseInt(storey.group(1)) - 1;
		}
		return 0;
	}

	/**
	 * Places the caves.
	 *
	 * @param kanto Kanto's surface box (its dungeon region and so the cave area are north of it), or null; then the
	 *              build's origin stands in for it
	 */
	public static Result place(Map<String, ExportMap> all, Set<String> caves, @Nullable Box3 kanto, int originX, int originZ, Warnings warnings) {
		if (caves.isEmpty()) {
			return new Result(List.of(), null, List.of());
		}
		List<List<String>> complexes = complexes(all, caves);
		// Each complex laid out on its own (local block offsets), then the slots packed in rows.
		List<Local> locals = new ArrayList<>();
		for (List<String> complex : complexes) {
			locals.add(layOut(all, complex, warnings));
		}
		int rowX = 0;
		int rowZ = 0;
		int rowDepth = 0;
		int width = 0;
		List<int[]> slots = new ArrayList<>();
		for (Local local : locals) {
			int w = local.maxX - local.minX + 2 * MARGIN;
			int d = local.maxZ - local.minZ + 2 * MARGIN;
			if (rowX > 0 && rowX + w > ROW_WIDTH) {
				rowZ += rowDepth + SLOT_GAP;
				rowX = 0;
				rowDepth = 0;
			}
			slots.add(new int[] {rowX + MARGIN - local.minX, rowZ + MARGIN - local.minZ});
			rowX += w + SLOT_GAP;
			rowDepth = Math.max(rowDepth, d);
			width = Math.max(width, rowX - SLOT_GAP);
		}
		int depth = rowZ + rowDepth;
		// North of the dungeon region, whole chunks, from Kanto's west edge as the zones and dungeons are.
		dev.kantobridge.world.WorldIndex.Dungeons dungeons = DungeonSlots.region(kanto, originX, originZ);
		int areaX = dungeons.x();
		int areaZ = Math.floorDiv(dungeons.z() - GAP - depth, 16) * 16;
		List<MapPlacement> out = new ArrayList<>();
		for (int i = 0; i < locals.size(); i++) {
			Local local = locals.get(i);
			int[] slot = slots.get(i);
			for (Map.Entry<String, int[]> entry : local.positions.entrySet()) {
				ExportMap map = all.get(entry.getKey());
				int[] at = entry.getValue();
				int rank = local.ranks.get(map.id());
				out.add(new MapPlacement(map.id(), areaX + slot[0] + at[0], TOP_FEET - FLOOR_STEP * rank, areaZ + slot[1] + at[1], map.cellWidth(),
						map.cellHeight(), 1 + rank, MapPlacement.Kind.INTERIOR, map.palette(), WorldPlan.BIOME_CAVE_3D, map.dark(),
						map.parent() == null ? null : map.parent().map()));
			}
		}
		warnings.note("cave_complexes", complexes.size());
		warnings.note("cave_maps", out.size());
		return new Result(out, new int[] {areaX, areaZ, areaX + width, areaZ + depth}, complexes);
	}

	/** Caves joined by warps (or by a door, parent to child), each complex in export order, complexes by their first map. */
	static List<List<String>> complexes(Map<String, ExportMap> all, Set<String> caves) {
		Map<String, String> root = new HashMap<>();
		for (String id : caves) {
			root.put(id, id);
		}
		for (String id : caves) {
			ExportMap map = all.get(id);
			for (ExportMap.Warp warp : map.warps()) {
				if (caves.contains(warp.to())) {
					union(root, id, warp.to());
				}
			}
			if (map.parent() != null && caves.contains(map.parent().map())) {
				union(root, id, map.parent().map());
			}
		}
		Map<String, List<String>> byRoot = new LinkedHashMap<>();
		for (String id : caves) {
			byRoot.computeIfAbsent(find(root, id), k -> new ArrayList<>()).add(id);
		}
		return new ArrayList<>(byRoot.values());
	}

	private static String find(Map<String, String> root, String id) {
		String r = id;
		while (!root.get(r).equals(r)) {
			r = root.get(r);
		}
		root.put(id, r);
		return r;
	}

	private static void union(Map<String, String> root, String a, String b) {
		String ra = find(root, a);
		String rb = find(root, b);
		if (!ra.equals(rb)) {
			// The earlier map (in caves' export order) stays the root, so complexes keep a stable order.
			root.put(rb, ra);
		}
	}

	/** One complex laid out: each map's north-west block relative to the complex, its floor's rank, and its box. */
	private static final class Local {
		final Map<String, int[]> positions = new LinkedHashMap<>();
		final Map<String, Integer> ranks = new HashMap<>();
		int minX = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
	}

	private static Local layOut(Map<String, ExportMap> all, List<String> complex, Warnings warnings) {
		Local local = new Local();
		// Ranks: floors from the top, 0 the highest (a tie between ids keeps them on one level).
		TreeMap<Integer, Integer> floors = new TreeMap<>(Comparator.reverseOrder());
		for (String id : complex) {
			floors.put(floor(id), 0);
		}
		int r = 0;
		for (Map.Entry<Integer, Integer> entry : floors.entrySet()) {
			entry.setValue(r++);
		}
		for (String id : complex) {
			local.ranks.put(id, floors.get(floor(id)));
		}
		// The entrance: the first map whose door is outside the complex; the layout grows from it, breadth first.
		String entrance = complex.stream().filter(id -> {
			ExportMap map = all.get(id);
			return map.parent() == null || !complex.contains(map.parent().map());
		}).findFirst().orElse(complex.get(0));
		put(local, all.get(entrance), 0, 0);
		Deque<String> queue = new ArrayDeque<>();
		queue.add(entrance);
		while (true) {
			while (!queue.isEmpty()) {
				String id = queue.poll();
				for (String next : complex) {
					if (local.positions.containsKey(next)) {
						continue;
					}
					int[] at = linked(all, local, id, next);
					if (at != null) {
						at = aside(all, local, next, at, warnings);
						put(local, all.get(next), at[0], at[1]);
						queue.add(next);
					}
				}
			}
			// A map no warp links to the rest (a door only): beside the complex, on its own level.
			String loose = complex.stream().filter(id -> !local.positions.containsKey(id)).findFirst().orElse(null);
			if (loose == null) {
				break;
			}
			int[] at = aside(all, local, loose, new int[] {local.maxX + FLOOR_GAP, local.minZ}, warnings);
			put(local, all.get(loose), at[0], at[1]);
			queue.add(loose);
		}
		return local;
	}

	/**
	 * Where {@code next} goes so that a warp between it and the placed map {@code placed} (either way) has both ends in
	 * one column, or null when no warp joins them.
	 */
	private static int @Nullable [] linked(Map<String, ExportMap> all, Local local, String placed, String next) {
		ExportMap a = all.get(placed);
		ExportMap b = all.get(next);
		int[] at = local.positions.get(placed);
		for (ExportMap.Warp warp : a.warps()) {
			ExportMap.Warp arrival = warp.to().equals(next) ? b.warp(warp.warp()) : null;
			if (arrival != null) {
				return new int[] {at[0] + Scale.cellToBlock(warp.x() - arrival.x()), at[1] + Scale.cellToBlock(warp.y() - arrival.y())};
			}
		}
		for (ExportMap.Warp warp : b.warps()) {
			ExportMap.Warp arrival = warp.to().equals(placed) ? a.warp(warp.warp()) : null;
			if (arrival != null) {
				return new int[] {at[0] + Scale.cellToBlock(arrival.x() - warp.x()), at[1] + Scale.cellToBlock(arrival.y() - warp.y())};
			}
		}
		if (b.parent() != null && b.parent().map().equals(placed)) {
			ExportMap.Warp door = a.warp(b.parent().warp());
			ExportMap.Warp arrival = door != null && door.to().equals(next) ? b.warp(door.warp()) : null;
			int ax = arrival == null ? 0 : arrival.x();
			int ay = arrival == null ? 0 : arrival.y();
			return new int[] {at[0] + Scale.cellToBlock(b.parent().x() - ax), at[1] + Scale.cellToBlock(b.parent().y() - ay)};
		}
		return null;
	}

	/** {@code at}, or the first spot east of it where the map overlaps no placed map of its own level. */
	private static int[] aside(Map<String, ExportMap> all, Local local, String id, int[] at, Warnings warnings) {
		ExportMap map = all.get(id);
		int rank = local.ranks.get(id);
		int x = at[0];
		boolean moved = false;
		while (overlaps(all, local, rank, x, at[1], map)) {
			x += FLOOR_GAP;
			moved = true;
		}
		if (moved) {
			warnings.note("cave_floor_moved_aside", Warnings.symbols("map", id));
		}
		return new int[] {x, at[1]};
	}

	private static boolean overlaps(Map<String, ExportMap> all, Local local, int rank, int x, int z, ExportMap map) {
		int w = Scale.cellToBlock(map.cellWidth());
		int d = Scale.cellToBlock(map.cellHeight());
		for (Map.Entry<String, int[]> entry : local.positions.entrySet()) {
			if (local.ranks.get(entry.getKey()) != rank) {
				continue;
			}
			ExportMap other = all.get(entry.getKey());
			int[] o = entry.getValue();
			int ow = Scale.cellToBlock(other.cellWidth());
			int od = Scale.cellToBlock(other.cellHeight());
			if (x < o[0] + ow + FLOOR_GAP && o[0] < x + w + FLOOR_GAP && z < o[1] + od + FLOOR_GAP && o[1] < z + d + FLOOR_GAP) {
				return true;
			}
		}
		return false;
	}

	private static void put(Local local, ExportMap map, int x, int z) {
		local.positions.put(map.id(), new int[] {x, z});
		local.minX = Math.min(local.minX, x - InteriorPacker.RING);
		local.minZ = Math.min(local.minZ, z - InteriorPacker.RING);
		local.maxX = Math.max(local.maxX, x + Scale.cellToBlock(map.cellWidth()) + InteriorPacker.RING);
		local.maxZ = Math.max(local.maxZ, z + Scale.cellToBlock(map.cellHeight()) + InteriorPacker.RING);
	}
}
