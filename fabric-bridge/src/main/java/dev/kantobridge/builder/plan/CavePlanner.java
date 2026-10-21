package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.dungeon.DungeonSlots;
import dev.kantobridge.world.MapLayout;
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
 * <p>In a slot, a cave's floors stack {@link #FLOOR_STEP} blocks apart (HD3), the top floor at feet {@link #TOP_FEET};
 * a floor whose rooms stand taller (raised ground, docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D3) sits that much lower.
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
	/**
	 * Blocks from a room's highest ceiling up to the feet of the floor above: a raised Kanto cave's (its plateau's ceiling
	 * {@link CaveLevels#RISE} + {@link WorldPlan#CEILING_ABOVE_FEET} over its feet) under {@link #FLOOR_STEP}; a taller room
	 * keeps as much (D3).
	 */
	static final int ROCK_ABOVE = FLOOR_STEP - CaveLevels.RISE - WorldPlan.CEILING_ABOVE_FEET;
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
	/**
	 * A floor in a map's id: {@code _nF} or {@code _BnF} at its end, or before a room or a side (Emerald's {@code _1F_1R},
	 * Crystal's {@code _B2F_MAHOGANY_SIDE} and {@code _1F_INSIDE}); any other suffix isn't one ({@code _B1F_2}).
	 */
	private static final Pattern FLOOR = Pattern.compile("_(B?)(\\d+)F(?:_\\d+R|_[A-Z]+_SIDE|_INSIDE|_OUTSIDE)?$");

	/**
	 * @param placements every cave's placement, in the complexes' order
	 * @param area       the cave area's box ({@code minX, minZ, maxX, maxZ}), or null without caves
	 * @param complexes  each complex's maps
	 * @param pieces     the maps built in 3D whose rooms lie apart (H5): their pieces, offsets from the placement
	 * @param shafts     the ladders and holes lined up (H5), top cell over bottom cell
	 * @param fades      the links of complexes built in 3D that keep their fade, by reason
	 */
	public record Result(List<MapPlacement> placements, int @Nullable [] area, List<List<String>> complexes,
			Map<String, List<MapLayout.Piece>> pieces, List<CaveRooms.Link> shafts, Map<String, Integer> fades) {
		public Result {
			complexes = complexes.stream().map(List::copyOf).toList();
			pieces = Map.copyOf(pieces);
			shafts = List.copyOf(shafts);
			fades = Map.copyOf(fades);
		}

		/** A result with no room laid out apart. */
		public Result(List<MapPlacement> placements, int @Nullable [] area, List<List<String>> complexes) {
			this(placements, area, complexes, Map.of(), List.of(), Map.of());
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
		Integer named = namedFloor(id);
		return named == null ? 0 : named;
	}

	/** A floor's number from its map id as {@link #floor} reads it, or null when the id names none. */
	static @Nullable Integer namedFloor(String id) {
		Matcher m = FLOOR.matcher(id);
		if (!m.find()) {
			return null;
		}
		int n = Integer.parseInt(m.group(2));
		return m.group(1).isEmpty() ? n - 1 : -n;
	}

	/**
	 * Places the caves (Gen 1, tests): every complex laid out whole floor by whole floor.
	 *
	 * @param kanto Kanto's surface box (its dungeon region and so the cave area are north of it), or null; then the
	 *              build's origin stands in for it
	 */
	public static Result place(Map<String, ExportMap> all, Set<String> caves, @Nullable Box3 kanto, int originX, int originZ, Warnings warnings) {
		return place(all, caves, Set.of(), Map.of(), kanto, originX, originZ, warnings);
	}

	/**
	 * The same, with the complexes built in 3D laid out room by room (H5, {@link CaveRooms}), Gen 1's maps read without the
	 * warp review (every warp tile in a cave that isn't a door is a ladder).
	 *
	 * @param in3d  the maps built in 3D (whole complexes)
	 * @param sites each map's swap-site blocks
	 */
	public static Result place(Map<String, ExportMap> all, Set<String> caves, Set<String> in3d, Map<String, ? extends Collection<int[]>> sites,
			@Nullable Box3 kanto, int originX, int originZ, Warnings warnings) {
		Map<String, CaveMap> maps = new LinkedHashMap<>();
		for (String id : caves) {
			Collection<int[]> own = sites.get(id);
			maps.put(id, CaveMap.gen1(all.get(id), all, own == null ? List.of() : own));
		}
		return placeArea(maps, caves, in3d, Map.of(), Map.of(), kanto, originX, originZ, warnings);
	}

	/**
	 * Places the maps of the 3D area, any game's (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D1): complexes of maps joined by
	 * warps or doors, each in a slot north of its region's dungeon slots, its floors {@link #FLOOR_STEP} apart (more under
	 * a taller one, {@link #rankFeet}).
	 *
	 * @param maps    every map of the area, by id
	 * @param caves   the area's maps, in the order complexes take
	 * @param in3d    the maps whose complexes are laid out room by room (ladders and holes lined up)
	 * @param biomes  a map's biome where it isn't {@link WorldPlan#BIOME_CAVE_3D} (a gym or a tower in the area)
	 * @param heights a map's highest ceiling over its feet where it has raised ground ({@link WorldPlan#maxCeiling}); any
	 *                other map's is {@link WorldPlan#CEILING_ABOVE_FEET}
	 * @param region  the region's surface box, or null; then the build's origin stands in for it
	 */
	public static Result placeArea(Map<String, CaveMap> maps, Set<String> caves, Set<String> in3d, Map<String, String> biomes,
			Map<String, Integer> heights, @Nullable Box3 region, int originX, int originZ, Warnings warnings) {
		if (caves.isEmpty()) {
			return new Result(List.of(), null, List.of());
		}
		List<List<String>> complexes = complexesOf(maps, caves);
		// Each complex laid out on its own (local block offsets), then the slots packed in rows.
		List<Local> locals = new ArrayList<>();
		Map<String, List<MapLayout.Piece>> pieces = new TreeMap<>();
		List<CaveRooms.Link> shafts = new ArrayList<>();
		Map<String, Integer> fades = new TreeMap<>();
		for (List<String> complex : complexes) {
			if (complex.stream().anyMatch(in3d::contains)) {
				Local local = layOutRooms(maps, complex, heights, pieces, fades);
				shafts.addAll(local.shafts);
				locals.add(local);
			} else {
				locals.add(layOut(maps, complex, heights, warnings));
			}
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
		// North of the dungeon region, whole chunks, from the region's west edge as the zones and dungeons are.
		dev.kantobridge.world.WorldIndex.Dungeons dungeons = DungeonSlots.region(region, originX, originZ);
		int areaX = dungeons.x();
		int areaZ = Math.floorDiv(dungeons.z() - GAP - depth, 16) * 16;
		List<MapPlacement> out = new ArrayList<>();
		for (int i = 0; i < locals.size(); i++) {
			Local local = locals.get(i);
			int[] slot = slots.get(i);
			for (Map.Entry<String, int[]> entry : local.positions.entrySet()) {
				CaveMap map = maps.get(entry.getKey());
				int[] at = entry.getValue();
				int rank = local.ranks.get(map.id());
				out.add(new MapPlacement(map.id(), areaX + slot[0] + at[0], local.feet.get(rank), areaZ + slot[1] + at[1], map.w(), map.h(), 1 + rank,
						MapPlacement.Kind.INTERIOR, map.palette(), biomes.getOrDefault(map.id(), WorldPlan.BIOME_CAVE_3D), map.dark(), map.parent()));
			}
		}
		warnings.note("cave_complexes", complexes.size());
		warnings.note("cave_maps", out.size());
		warnings.note("cave_rooms", pieces.values().stream().mapToInt(List::size).sum());
		warnings.note("cave_shafts", shafts.size());
		fades.forEach((reason, count) -> warnings.note("cave_fades_" + reason, count));
		return new Result(out, new int[] {areaX, areaZ, areaX + width, areaZ + depth}, complexes, pieces, shafts, fades);
	}

	/** Gen 1's caves joined by warps (or by a door, parent to child), as {@link #complexesOf}. */
	static List<List<String>> complexes(Map<String, ExportMap> all, Set<String> caves) {
		Map<String, CaveMap> maps = new LinkedHashMap<>();
		for (String id : caves) {
			maps.put(id, CaveMap.gen1(all.get(id), all, List.of()));
		}
		return complexesOf(maps, caves);
	}

	/** Maps of the area joined by warps (or by a door, parent to child), each complex in {@code caves}' order, complexes by their first map. */
	static List<List<String>> complexesOf(Map<String, CaveMap> maps, Set<String> caves) {
		Map<String, String> root = new HashMap<>();
		for (String id : caves) {
			root.put(id, id);
		}
		for (String id : caves) {
			CaveMap map = maps.get(id);
			for (CaveMap.Warp warp : map.warps()) {
				if (warp.to() != null && caves.contains(warp.to())) {
					union(root, id, warp.to());
				}
			}
			if (map.parent() != null && caves.contains(map.parent())) {
				union(root, id, map.parent());
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
		/** Each rank's feet level (a room's rank may lie above or below its maps', D1). */
		Map<Integer, Integer> feet = Map.of(0, TOP_FEET);
		final List<CaveRooms.Link> shafts = new ArrayList<>();
		int minX = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
	}

	/**
	 * Each rank's feet level (D1, D3): rank 0 at {@link #TOP_FEET}, each rank under it {@link #FLOOR_STEP} lower than the
	 * one above, or as much lower again as its tallest room (its highest ceiling over its feet, by rank) needs to keep
	 * {@link #ROCK_ABOVE} blocks under the floor above; a rank over 0 (a room above its maps, D1) likewise up.
	 */
	static Map<Integer, Integer> rankFeet(Map<Integer, Integer> tallest) {
		int min = Math.min(0, tallest.keySet().stream().mapToInt(Integer::intValue).min().orElse(0));
		int max = Math.max(0, tallest.keySet().stream().mapToInt(Integer::intValue).max().orElse(0));
		Map<Integer, Integer> feet = new TreeMap<>();
		feet.put(0, TOP_FEET);
		for (int r = 1; r <= max; r++) {
			feet.put(r, feet.get(r - 1) - step(tallest, r));
		}
		for (int r = -1; r >= min; r--) {
			feet.put(r, feet.get(r + 1) + step(tallest, r + 1));
		}
		return feet;
	}

	/** Blocks from rank {@code r - 1}'s feet down to rank {@code r}'s. */
	private static int step(Map<Integer, Integer> tallest, int r) {
		return Math.max(FLOOR_STEP, tallest.getOrDefault(r, WorldPlan.CEILING_ABOVE_FEET) + ROCK_ABOVE);
	}

	/**
	 * Each map's floor rank in a complex, 0 the highest (D1): the floor its id names; where it names none, the floor its
	 * ladders, holes and stairs lead from (a ladder up from floor f arrives on f + 1, a hole or a way down on f - 1),
	 * starting from the named floors, or from the entrance at 0 when no id names one. A map still left is on the ground
	 * floor (0) when the complex names floors (Gen 1's rule, H3), else on the floor of a map a warp or its door joins it to,
	 * else the entrance's. Maps of one floor tie (they stay on one level).
	 */
	static Map<String, Integer> ranks(Map<String, CaveMap> maps, List<String> complex, String entrance) {
		Map<String, Integer> floors = new LinkedHashMap<>();
		for (String id : complex) {
			Integer named = namedFloor(id);
			if (named != null) {
				floors.put(id, named);
			}
		}
		if (floors.size() < complex.size()) {
			boolean named = !floors.isEmpty();
			if (!named) {
				floors.put(entrance, 0);
			}
			// Breadth first, ways first (the review says which way a ladder or a staircase leads), then plain joins where no
			// id names a floor.
			for (boolean ways : named ? new boolean[] {true} : new boolean[] {true, false}) {
				boolean grew = true;
				while (grew) {
					grew = false;
					for (String id : complex) {
						for (CaveMap.Warp warp : maps.get(id).warps()) {
							String to = warp.to();
							if (to == null || !complex.contains(to) || ways && warp.way() == 0) {
								continue;
							}
							int way = ways ? warp.way() : 0;
							if (floors.containsKey(id) && !floors.containsKey(to)) {
								floors.put(to, floors.get(id) + way);
								grew = true;
							} else if (floors.containsKey(to) && !floors.containsKey(id)) {
								floors.put(id, floors.get(to) - way);
								grew = true;
							}
						}
						String parent = maps.get(id).parent();
						if (!ways && parent != null && complex.contains(parent) && floors.containsKey(parent) != floors.containsKey(id)) {
							if (floors.containsKey(parent)) {
								floors.put(id, floors.get(parent));
							} else {
								floors.put(parent, floors.get(id));
							}
							grew = true;
						}
					}
				}
			}
			int fallback = named ? 0 : floors.getOrDefault(entrance, 0);
			for (String id : complex) {
				floors.putIfAbsent(id, fallback);
			}
		}
		TreeMap<Integer, Integer> ranked = new TreeMap<>(Comparator.reverseOrder());
		for (int floor : floors.values()) {
			ranked.put(floor, 0);
		}
		int r = 0;
		for (Map.Entry<Integer, Integer> entry : ranked.entrySet()) {
			entry.setValue(r++);
		}
		Map<String, Integer> out = new HashMap<>();
		for (String id : complex) {
			out.put(id, ranked.get(floors.get(id)));
		}
		return out;
	}

	/** The entrance: the first map whose door is outside the complex. */
	private static String entrance(Map<String, CaveMap> maps, List<String> complex) {
		return complex.stream().filter(id -> {
			String parent = maps.get(id).parent();
			return parent == null || !complex.contains(parent);
		}).findFirst().orElse(complex.get(0));
	}

	/**
	 * A complex built in 3D, room by room ({@link CaveRooms}): each map placed where its first room's frame says, each
	 * room a piece (so a map draws its rooms and the rock round them, and nothing the layout didn't count).
	 */
	private static Local layOutRooms(Map<String, CaveMap> maps, List<String> complex, Map<String, Integer> heights,
			Map<String, List<MapLayout.Piece>> pieces, Map<String, Integer> fades) {
		Local local = new Local();
		String entrance = entrance(maps, complex);
		local.ranks.putAll(ranks(maps, complex, entrance));
		CaveRooms.Plan plan = CaveRooms.layOut(maps, complex, local.ranks, entrance);
		// Each rank as tall as its tallest room: a map's rooms on other floors than its own (D1) count there.
		Map<Integer, Integer> tallest = new HashMap<>();
		for (String id : complex) {
			int tall = heights.getOrDefault(id, WorldPlan.CEILING_ABOVE_FEET);
			tallest.merge(local.ranks.get(id), tall, Math::max);
			int[] roomRanks = plan.roomRanks().get(id);
			for (int i = 0; roomRanks != null && i < roomRanks.length; i++) {
				tallest.merge(roomRanks[i], tall, Math::max);
			}
		}
		local.feet = rankFeet(tallest);
		for (String id : complex) {
			CaveMap map = maps.get(id);
			List<CavePieces.Room> rooms = plan.rooms().get(id);
			int[][] frames = plan.frames().get(id);
			if (rooms.isEmpty()) {
				// No floor at all: beside the complex, as a loose map.
				local.positions.put(id, new int[] {plan.maxX() + FLOOR_GAP, plan.minZ() + InteriorPacker.RING});
				continue;
			}
			int[] base = frames[0];
			local.positions.put(id, base.clone());
			List<MapLayout.Piece> list = new ArrayList<>();
			int[] roomRanks = plan.roomRanks().get(id);
			for (CavePieces.Room room : rooms) {
				java.util.BitSet mask = room.mask(map.w());
				boolean whole = mask.cardinality() == room.w() * room.h();
				int[] frame = frames[room.index()];
				// a room on another floor than its map's (a map holding several floors, D1) sits that many floors up or down
				int dy = local.feet.get(roomRanks[room.index()]) - local.feet.get(local.ranks.get(id));
				list.add(new MapLayout.Piece(room.x(), room.y(), room.w(), room.h(), whole ? null : mask, frame[0] - base[0], dy, frame[1] - base[1]));
			}
			pieces.put(id, list);
		}
		local.minX = Math.min(plan.minX(), local.positions.values().stream().mapToInt(p -> p[0] - InteriorPacker.RING).min().orElse(0));
		local.minZ = Math.min(plan.minZ(), local.positions.values().stream().mapToInt(p -> p[1] - InteriorPacker.RING).min().orElse(0));
		local.maxX = plan.maxX();
		local.maxZ = plan.maxZ();
		for (String id : complex) {
			// A map without pieces is drawn whole at its placement: its box counts in full.
			if (!pieces.containsKey(id)) {
				int[] at = local.positions.get(id);
				CaveMap map = maps.get(id);
				local.minX = Math.min(local.minX, at[0] - InteriorPacker.RING);
				local.minZ = Math.min(local.minZ, at[1] - InteriorPacker.RING);
				local.maxX = Math.max(local.maxX, at[0] + Scale.cellToBlock(map.w()) + InteriorPacker.RING);
				local.maxZ = Math.max(local.maxZ, at[1] + Scale.cellToBlock(map.h()) + InteriorPacker.RING);
			}
		}
		local.shafts.addAll(plan.shafts());
		plan.fades().forEach((reason, count) -> fades.merge(reason, count, Integer::sum));
		return local;
	}

	private static Local layOut(Map<String, CaveMap> maps, List<String> complex, Map<String, Integer> heights, Warnings warnings) {
		Local local = new Local();
		String entrance = entrance(maps, complex);
		local.ranks.putAll(ranks(maps, complex, entrance));
		Map<Integer, Integer> tallest = new HashMap<>();
		for (String id : complex) {
			tallest.merge(local.ranks.get(id), heights.getOrDefault(id, WorldPlan.CEILING_ABOVE_FEET), Math::max);
		}
		local.feet = rankFeet(tallest);
		// The layout grows from the entrance, breadth first.
		put(local, maps.get(entrance), 0, 0);
		Deque<String> queue = new ArrayDeque<>();
		queue.add(entrance);
		while (true) {
			while (!queue.isEmpty()) {
				String id = queue.poll();
				for (String next : complex) {
					if (local.positions.containsKey(next)) {
						continue;
					}
					int[] at = linked(maps, local, id, next);
					if (at != null) {
						at = aside(maps, local, next, at, warnings);
						put(local, maps.get(next), at[0], at[1]);
						queue.add(next);
					}
				}
			}
			// A map no warp links to the rest (a door only): beside the complex, on its own level.
			String loose = complex.stream().filter(id -> !local.positions.containsKey(id)).findFirst().orElse(null);
			if (loose == null) {
				break;
			}
			int[] at = aside(maps, local, loose, new int[] {local.maxX + FLOOR_GAP, local.minZ}, warnings);
			put(local, maps.get(loose), at[0], at[1]);
			queue.add(loose);
		}
		return local;
	}

	/**
	 * Where {@code next} goes so that a warp between it and the placed map {@code placed} (either way) has both ends in
	 * one column, or null when no warp joins them.
	 */
	private static int @Nullable [] linked(Map<String, CaveMap> maps, Local local, String placed, String next) {
		CaveMap a = maps.get(placed);
		CaveMap b = maps.get(next);
		int[] at = local.positions.get(placed);
		for (CaveMap.Warp warp : a.warps()) {
			if (next.equals(warp.to()) && warp.arrives()) {
				return new int[] {at[0] + Scale.cellToBlock(warp.x() - warp.arrivalX()), at[1] + Scale.cellToBlock(warp.y() - warp.arrivalY())};
			}
		}
		for (CaveMap.Warp warp : b.warps()) {
			if (placed.equals(warp.to()) && warp.arrives()) {
				return new int[] {at[0] + Scale.cellToBlock(warp.arrivalX() - warp.x()), at[1] + Scale.cellToBlock(warp.arrivalY() - warp.y())};
			}
		}
		CaveMap.Door door = b.door();
		if (door != null && door.map().equals(placed)) {
			return new int[] {at[0] + Scale.cellToBlock(door.x() - door.arrivalX()), at[1] + Scale.cellToBlock(door.y() - door.arrivalY())};
		}
		return null;
	}

	/** {@code at}, or the first spot east of it where the map overlaps no placed map of its own level. */
	private static int[] aside(Map<String, CaveMap> maps, Local local, String id, int[] at, Warnings warnings) {
		CaveMap map = maps.get(id);
		int rank = local.ranks.get(id);
		int x = at[0];
		boolean moved = false;
		while (overlaps(maps, local, rank, x, at[1], map)) {
			x += FLOOR_GAP;
			moved = true;
		}
		if (moved) {
			warnings.note("cave_floor_moved_aside", Warnings.symbols("map", id));
		}
		return new int[] {x, at[1]};
	}

	private static boolean overlaps(Map<String, CaveMap> maps, Local local, int rank, int x, int z, CaveMap map) {
		int w = Scale.cellToBlock(map.w());
		int d = Scale.cellToBlock(map.h());
		for (Map.Entry<String, int[]> entry : local.positions.entrySet()) {
			if (local.ranks.get(entry.getKey()) != rank) {
				continue;
			}
			CaveMap other = maps.get(entry.getKey());
			int[] o = entry.getValue();
			int ow = Scale.cellToBlock(other.w());
			int od = Scale.cellToBlock(other.h());
			if (x < o[0] + ow + FLOOR_GAP && o[0] < x + w + FLOOR_GAP && z < o[1] + od + FLOOR_GAP && o[1] < z + d + FLOOR_GAP) {
				return true;
			}
		}
		return false;
	}

	private static void put(Local local, CaveMap map, int x, int z) {
		local.positions.put(map.id(), new int[] {x, z});
		local.minX = Math.min(local.minX, x - InteriorPacker.RING);
		local.minZ = Math.min(local.minZ, z - InteriorPacker.RING);
		local.maxX = Math.max(local.maxX, x + Scale.cellToBlock(map.w()) + InteriorPacker.RING);
		local.maxZ = Math.max(local.maxZ, z + Scale.cellToBlock(map.h()) + InteriorPacker.RING);
	}
}
