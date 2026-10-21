package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.world.Scale;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * A cave complex built in 3D laid out room by room (docs/specs/CAVES_3D_PLAN.md §3.5, H5): each floor split into its
 * rooms ({@link CavePieces}), and each room placed so that the ladders and holes between floors have both ends in one
 * column, where a real shaft can join them.
 *
 * <ul>
 * <li><b>Links:</b> a warp between two maps of the complex. One between two floors from a hole (a cell with the hole
 * flag, a pad of its own), or from a warp tile that isn't a door (a ladder), is a shaft's. A ladder works upward too when
 * the warp at its bottom leads back to its top (HD10); a ladder that leads only up keeps its fade.</li>
 * <li><b>Across:</b> breadth first from the entrance's room, each link places the room it leads to so that its two ends
 * share a column; shafts' links first. A link that disagrees with a room already placed keeps its fade (HD5), and so
 * does one whose room had to move aside (it overlapped a room of its floor).</li>
 * <li><b>Rooms no link reached</b> keep their map's arrangement where it is free, else go beside the rest.</li>
 * <li><b>Down:</b> each floor 11 blocks under the one above (H3). A shaft that would cross a floor between its ends,
 * through a room there, keeps its fade.</li>
 * </ul>
 * Pure Java.
 */
final class CaveRooms {
	/**
	 * A shaft's link: its top cell over its bottom cell.
	 *
	 * @param up the bottom's warp leads back to the top: the shaft is climbed up too
	 */
	record Link(String kind, String topMap, int topX, int topY, String bottomMap, int bottomX, int bottomY, boolean up) {
	}

	/**
	 * The complex laid out.
	 *
	 * @param rooms  each map's rooms
	 * @param frames each map's rooms' frames: where, in blocks relative to the complex, the map's cell (0, 0) lies when
	 *               that room is drawn
	 * @param shafts the links lined up, top cell over bottom cell
	 * @param fades  the links that keep their fade, by reason
	 * @param roomRanks each map's rooms' floors (0 the highest; a room of a map holding several floors may differ from its
	 *                  map's, HEIGHTS_ALL_GAMES_PLAN D1)
	 */
	record Plan(Map<String, List<CavePieces.Room>> rooms, Map<String, int[][]> frames, List<Link> shafts, Map<String, Integer> fades, int minX,
			int minZ, int maxX, int maxZ, Map<String, int[]> roomRanks) {
	}

	/** An edge of the walk between rooms: a warp from one room's cell to another's. */
	private record Edge(Unit from, int fx, int fy, Unit to, int tx, int ty, @Nullable Link shaft) {
	}

	private record Unit(String map, int room) {
	}

	private CaveRooms() {
	}

	/**
	 * Lays out one complex.
	 *
	 * @param ranks each map's floor, 0 the highest
	 * @param sites each map's swap-site blocks ({@link CavePieces#of})
	 */
	static Plan layOut(Map<String, ExportMap> all, List<String> complex, Map<String, Integer> ranks, String entrance,
			Map<String, ? extends Collection<int[]>> sites) {
		Map<String, CaveMap> maps = new LinkedHashMap<>();
		for (String id : complex) {
			Collection<int[]> own = sites.get(id);
			maps.put(id, CaveMap.gen1(all.get(id), all, own == null ? List.of() : own));
		}
		return layOut(maps, complex, ranks, entrance);
	}

	/** The same for maps of any game (HEIGHTS_ALL_GAMES_PLAN D1): each map's swap sites are in its walk cells already. */
	static Plan layOut(Map<String, CaveMap> all, List<String> complex, Map<String, Integer> ranks, String entrance) {
		Map<String, List<CavePieces.Room>> rooms = new LinkedHashMap<>();
		for (String id : complex) {
			rooms.put(id, CavePieces.of(all.get(id)));
		}
		Map<Unit, Integer> rank = roomRanks(all, complex, ranks, rooms);
		Map<String, Integer> fades = new TreeMap<>();
		// ---- the edges: every warp between two rooms of the complex, a shaft's marked ----
		Map<Unit, List<Edge>> edges = new LinkedHashMap<>();
		List<Link> links = new ArrayList<>();
		for (String id : complex) {
			CaveMap a = all.get(id);
			for (CaveMap.Warp warp : a.warps()) {
				CaveMap b = warp.to() == null ? null : all.get(warp.to());
				if (b == null || !complex.contains(b.id()) || !warp.arrives()) {
					continue;
				}
				Unit from = unit(rooms, a, warp.x(), warp.y());
				Unit to = unit(rooms, b, warp.arrivalX(), warp.arrivalY());
				if (from == null || to == null) {
					// A warp from or to a cell in no room (not walked on): it keeps its fade, counted by what its cell is
					fades.merge(warp.kind() == CaveMap.Kind.HOLE ? "hole_off_floor" : "off_floor", 1, Integer::sum);
					continue;
				}
				Link shaft = shaftLink(rank.get(from), rank.get(to), a, warp, b, fades);
				if (shaft != null) {
					links.add(shaft);
				}
				edge(edges, new Edge(from, warp.x(), warp.y(), to, warp.arrivalX(), warp.arrivalY(), shaft));
				edge(edges, new Edge(to, warp.arrivalX(), warp.arrivalY(), from, warp.x(), warp.y(), shaft));
			}
			// A door from a cave into a room of the complex (a child map) joins them as a warp does.
			CaveMap.Door door = a.door();
			if (door != null && complex.contains(door.map())) {
				CaveMap parent = all.get(door.map());
				Unit from = unit(rooms, parent, door.x(), door.y());
				Unit to = unit(rooms, a, door.arrivalX(), door.arrivalY());
				if (from != null && to != null) {
					edge(edges, new Edge(from, door.x(), door.y(), to, door.arrivalX(), door.arrivalY(), null));
					edge(edges, new Edge(to, door.arrivalX(), door.arrivalY(), from, door.x(), door.y(), null));
				}
			}
		}
		for (List<Edge> list : edges.values()) {
			// Shafts' links first, so ladders line up before any other warp decides a room's place.
			list.sort((x, y) -> Boolean.compare(y.shaft() != null, x.shaft() != null));
		}
		// ---- across: breadth first from the entrance's room ----
		Map<Unit, int[]> frames = new LinkedHashMap<>();
		Map<Link, Boolean> moved = new HashMap<>();
		Unit start = entranceRoom(all, complex, rooms, entrance);
		frames.put(start, new int[] {0, 0});
		Deque<Unit> queue = new ArrayDeque<>(List.of(start));
		while (true) {
			while (!queue.isEmpty()) {
				Unit u = queue.poll();
				int[] at = frames.get(u);
				for (Edge e : edges.getOrDefault(u, List.of())) {
					if (frames.containsKey(e.to())) {
						continue;
					}
					int[] want = {at[0] + Scale.cellToBlock(e.fx() - e.tx()), at[1] + Scale.cellToBlock(e.fy() - e.ty())};
					int[] got = aside(all, rooms, rank, frames, e.to(), want);
					if (got[0] != want[0] && e.shaft() != null) {
						moved.put(e.shaft(), true);
					}
					frames.put(e.to(), got);
					queue.add(e.to());
				}
			}
			Unit loose = null;
			for (Map.Entry<String, List<CavePieces.Room>> entry : rooms.entrySet()) {
				for (CavePieces.Room room : entry.getValue()) {
					Unit u = new Unit(entry.getKey(), room.index());
					if (!frames.containsKey(u) && loose == null) {
						loose = u;
					}
				}
			}
			if (loose == null) {
				break;
			}
			// Its map's arrangement, where a room of the map already lies; else beside the rest.
			int[] want = null;
			for (Map.Entry<Unit, int[]> placed : frames.entrySet()) {
				if (placed.getKey().map().equals(loose.map())) {
					want = placed.getValue().clone();
					break;
				}
			}
			if (want == null) {
				int maxX = 0;
				int minZ = Integer.MAX_VALUE;
				for (Map.Entry<Unit, int[]> placed : frames.entrySet()) {
					int[] box = box(all, rooms, placed.getKey(), placed.getValue());
					maxX = Math.max(maxX, box[2]);
					minZ = Math.min(minZ, box[1]);
				}
				CavePieces.Room room = rooms.get(loose.map()).get(loose.room());
				want = new int[] {maxX + CavePlanner.FLOOR_GAP + InteriorPacker.RING - Scale.cellToBlock(room.x()),
						minZ + InteriorPacker.RING - Scale.cellToBlock(room.y())};
			}
			frames.put(loose, aside(all, rooms, rank, frames, loose, want));
			queue.add(loose);
		}
		// ---- the shafts: links whose ends share a column, and no room between their floors in the way ----
		List<Link> shafts = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (Link link : links) {
			String key = link.topMap() + ":" + link.topX() + ":" + link.topY();
			if (!seen.add(key)) {
				continue;
			}
			Unit top = unit(rooms, all.get(link.topMap()), link.topX(), link.topY());
			Unit bottom = unit(rooms, all.get(link.bottomMap()), link.bottomX(), link.bottomY());
			int[] t = frames.get(top);
			int[] b = frames.get(bottom);
			int tx = t[0] + Scale.cellToBlock(link.topX());
			int tz = t[1] + Scale.cellToBlock(link.topY());
			if (tx != b[0] + Scale.cellToBlock(link.bottomX()) || tz != b[1] + Scale.cellToBlock(link.bottomY())) {
				fades.merge(moved.containsKey(link) ? "moved_aside" : "loop", 1, Integer::sum);
				continue;
			}
			if (crossesARoom(all, rooms, rank, frames, rank.get(top), rank.get(bottom), tx, tz)) {
				fades.merge("crosses_floor", 1, Integer::sum);
				continue;
			}
			shafts.add(link);
		}
		// ---- the result: frames per map, and the complex's box ----
		Map<String, int[][]> byMap = new LinkedHashMap<>();
		int minX = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (Map.Entry<String, List<CavePieces.Room>> entry : rooms.entrySet()) {
			int[][] out = new int[entry.getValue().size()][];
			for (CavePieces.Room room : entry.getValue()) {
				Unit u = new Unit(entry.getKey(), room.index());
				out[room.index()] = frames.get(u);
				int[] box = box(all, rooms, u, frames.get(u));
				minX = Math.min(minX, box[0]);
				minZ = Math.min(minZ, box[1]);
				maxX = Math.max(maxX, box[2]);
				maxZ = Math.max(maxZ, box[3]);
			}
			byMap.put(entry.getKey(), out);
		}
		if (byMap.values().stream().allMatch(f -> f.length == 0)) {
			minX = minZ = 0;
			maxX = maxZ = 1;
		}
		Map<String, int[]> roomRanks = new LinkedHashMap<>();
		for (Map.Entry<String, List<CavePieces.Room>> entry : rooms.entrySet()) {
			int[] out = new int[entry.getValue().size()];
			for (CavePieces.Room room : entry.getValue()) {
				out[room.index()] = rank.get(new Unit(entry.getKey(), room.index()));
			}
			roomRanks.put(entry.getKey(), out);
		}
		return new Plan(rooms, byMap, shafts, fades, minX, minZ, maxX, maxZ, roomRanks);
	}

	/**
	 * Each room's floor (D1): its map's, except in a map holding several floors (Crystal's Victory Road joins its own
	 * rooms by ladders): from the rooms that lead out of the map or are led into from another (they keep the map's floor),
	 * each ladder, hole or staircase between two rooms of the map whose way is known puts the room it leads to a floor
	 * up or down.
	 */
	private static Map<Unit, Integer> roomRanks(Map<String, CaveMap> all, List<String> complex, Map<String, Integer> ranks,
			Map<String, List<CavePieces.Room>> rooms) {
		Map<Unit, Integer> rank = new HashMap<>();
		java.util.Set<Unit> pinned = new java.util.HashSet<>();
		for (String id : complex) {
			for (CavePieces.Room room : rooms.get(id)) {
				rank.put(new Unit(id, room.index()), ranks.get(id));
			}
		}
		// The rooms joined to another map keep their map's floor
		for (String id : complex) {
			CaveMap a = all.get(id);
			for (CaveMap.Warp warp : a.warps()) {
				if (warp.to() != null && !warp.to().equals(id)) {
					pin(pinned, unit(rooms, a, warp.x(), warp.y()));
					CaveMap b = all.get(warp.to());
					if (b != null && complex.contains(b.id()) && warp.arrives()) {
						pin(pinned, unit(rooms, b, warp.arrivalX(), warp.arrivalY()));
					}
				}
			}
			if (a.door() != null) {
				pin(pinned, unit(rooms, a, a.door().arrivalX(), a.door().arrivalY()));
			}
		}
		boolean changed = true;
		while (changed) {
			changed = false;
			for (String id : complex) {
				CaveMap a = all.get(id);
				for (CaveMap.Warp warp : a.warps()) {
					if (!id.equals(warp.to()) || !warp.arrives() || warp.way() == 0) {
						continue;
					}
					Unit from = unit(rooms, a, warp.x(), warp.y());
					Unit to = unit(rooms, a, warp.arrivalX(), warp.arrivalY());
					if (from == null || to == null || from.equals(to)) {
						continue;
					}
					// rank counts down from the top: a way up (+1) is one rank less
					if (pinned.contains(from) && !pinned.contains(to)) {
						rank.put(to, rank.get(from) - warp.way());
						pinned.add(to);
						changed = true;
					} else if (pinned.contains(to) && !pinned.contains(from)) {
						rank.put(from, rank.get(to) + warp.way());
						pinned.add(from);
						changed = true;
					}
				}
			}
		}
		return rank;
	}

	private static void pin(java.util.Set<Unit> pinned, @Nullable Unit u) {
		if (u != null) {
			pinned.add(u);
		}
	}

	private static void edge(Map<Unit, List<Edge>> edges, Edge e) {
		edges.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
	}

	private static @Nullable Unit unit(Map<String, List<CavePieces.Room>> rooms, CaveMap map, int cx, int cy) {
		CavePieces.Room room = CavePieces.roomAt(rooms.get(map.id()), map.w(), cx, cy);
		return room == null ? null : new Unit(map.id(), room.index());
	}

	/**
	 * A shaft's link, from the warp at (x, y) of {@code a} down to {@code arrival} on {@code b}, or null when the warp
	 * isn't one going down (a door, a warp within a floor, the upward warp of a ladder: its top's warp makes the link).
	 */
	private static @Nullable Link shaftLink(int ra, int rb, CaveMap a, CaveMap.Warp warp, CaveMap b, Map<String, Integer> fades) {
		// A hole or a ladder (CaveMap's kinds: Gen 1's hole flag and its warp tiles that aren't doors, the others' data)
		boolean hole = warp.kind() == CaveMap.Kind.HOLE;
		if (!hole && warp.kind() != CaveMap.Kind.LADDER) {
			return null;
		}
		if (ra == rb) {
			fades.merge("same_floor", 1, Integer::sum);
			return null;
		}
		if (ra > rb) {
			// Going up: the shaft is its top's; a ladder whose top leads elsewhere keeps its fade.
			if (hole || !leadsTo(b, warp, a)) {
				fades.merge(hole ? "hole_up" : "one_way_up", 1, Integer::sum);
			}
			return null;
		}
		boolean up = !hole && leadsTo(b, warp, a);
		return new Link(hole ? "hole" : "ladder", a.id(), warp.x(), warp.y(), b.id(), warp.arrivalX(), warp.arrivalY(), up);
	}

	/** True when the warp of {@code b} on {@code warp}'s arrival cell leads back to {@code warp}'s own cell of {@code a}. */
	private static boolean leadsTo(CaveMap b, CaveMap.Warp warp, CaveMap a) {
		for (CaveMap.Warp back : b.warps()) {
			if (back.x() == warp.arrivalX() && back.y() == warp.arrivalY() && a.id().equals(back.to())) {
				return back.arrives() && back.arrivalX() == warp.x() && back.arrivalY() == warp.y();
			}
		}
		return false;
	}

	/** The entrance map's room the way in reaches: the one with a warp out of the complex, else its first. */
	private static Unit entranceRoom(Map<String, CaveMap> all, List<String> complex, Map<String, List<CavePieces.Room>> rooms, String entrance) {
		CaveMap map = all.get(entrance);
		for (CaveMap.Warp warp : map.warps()) {
			if (warp.to() == null || !complex.contains(warp.to())) {
				Unit u = unit(rooms, map, warp.x(), warp.y());
				if (u != null) {
					return u;
				}
			}
		}
		return new Unit(entrance, 0);
	}

	/** A room's footprint at {@code frame}, its enclosure ring included: {minX, minZ, maxX, maxZ}. */
	private static int[] box(Map<String, CaveMap> all, Map<String, List<CavePieces.Room>> rooms, Unit u, int[] frame) {
		CavePieces.Room room = rooms.get(u.map()).get(u.room());
		int x = frame[0] + Scale.cellToBlock(room.x());
		int z = frame[1] + Scale.cellToBlock(room.y());
		return new int[] {x - InteriorPacker.RING, z - InteriorPacker.RING, x + Scale.cellToBlock(room.w()) + InteriorPacker.RING,
				z + Scale.cellToBlock(room.h()) + InteriorPacker.RING};
	}

	/**
	 * {@code want}, or the first spot east of it where the room clashes with no room of its floor drawn apart from it:
	 * another map's room keeps H3's gap from it; its own map's at another frame may share a wall of rock, but neither's
	 * floor may run into the other's footprint.
	 */
	private static int[] aside(Map<String, CaveMap> all, Map<String, List<CavePieces.Room>> rooms, Map<Unit, Integer> ranks,
			Map<Unit, int[]> frames, Unit u, int[] want) {
		int x = want[0];
		while (overlaps(all, rooms, ranks, frames, u, new int[] {x, want[1]})) {
			x += CavePlanner.FLOOR_GAP;
		}
		return new int[] {x, want[1]};
	}

	private static boolean overlaps(Map<String, CaveMap> all, Map<String, List<CavePieces.Room>> rooms, Map<Unit, Integer> ranks,
			Map<Unit, int[]> frames, Unit u, int[] frame) {
		int rank = ranks.get(u);
		int[] a = box(all, rooms, u, frame);
		int[] aFloor = floorBox(all, rooms, u, frame);
		for (Map.Entry<Unit, int[]> placed : frames.entrySet()) {
			Unit o = placed.getKey();
			if (ranks.get(o) != rank || o.map().equals(u.map()) && java.util.Arrays.equals(placed.getValue(), frame)) {
				continue;
			}
			int[] b = box(all, rooms, o, placed.getValue());
			if (o.map().equals(u.map())) {
				if (intersect(aFloor, b, 0) || intersect(floorBox(all, rooms, o, placed.getValue()), a, 0)) {
					return true;
				}
			} else if (intersect(a, b, CavePlanner.FLOOR_GAP)) {
				return true;
			}
		}
		return false;
	}

	private static boolean intersect(int[] a, int[] b, int gap) {
		return a[0] < b[2] + gap && b[0] < a[2] + gap && a[1] < b[3] + gap && b[1] < a[3] + gap;
	}

	/** The box of a room's walkable and water cells at {@code frame}, in blocks: {minX, minZ, maxX, maxZ}. */
	private static int[] floorBox(Map<String, CaveMap> all, Map<String, List<CavePieces.Room>> rooms, Unit u, int[] frame) {
		CavePieces.Room room = rooms.get(u.map()).get(u.room());
		int w = all.get(u.map()).w();
		int minX = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (int i = room.floor().nextSetBit(0); i >= 0; i = room.floor().nextSetBit(i + 1)) {
			minX = Math.min(minX, i % w);
			maxX = Math.max(maxX, i % w);
			minZ = Math.min(minZ, i / w);
			maxZ = Math.max(maxZ, i / w);
		}
		return new int[] {frame[0] + Scale.cellToBlock(minX), frame[1] + Scale.cellToBlock(minZ), frame[0] + Scale.cellToBlock(maxX + 1),
				frame[1] + Scale.cellToBlock(maxZ + 1)};
	}

	/** True when a room of a floor strictly between {@code fromRank} and {@code toRank} covers column (x, z). */
	private static boolean crossesARoom(Map<String, CaveMap> all, Map<String, List<CavePieces.Room>> rooms, Map<Unit, Integer> ranks,
			Map<Unit, int[]> frames, int fromRank, int toRank, int x, int z) {
		for (Map.Entry<Unit, int[]> placed : frames.entrySet()) {
			int rank = ranks.get(placed.getKey());
			if (rank <= fromRank || rank >= toRank) {
				continue;
			}
			int[] b = box(all, rooms, placed.getKey(), placed.getValue());
			if (x + Scale.CELL_BLOCKS > b[0] && x < b[2] && z + Scale.CELL_BLOCKS > b[1] && z < b[3]) {
				return true;
			}
		}
		return false;
	}
}
