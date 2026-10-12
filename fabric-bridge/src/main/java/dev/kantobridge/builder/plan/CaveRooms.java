package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.CellFlags;
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
	 */
	record Plan(Map<String, List<CavePieces.Room>> rooms, Map<String, int[][]> frames, List<Link> shafts, Map<String, Integer> fades, int minX,
			int minZ, int maxX, int maxZ) {
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
		Map<String, List<CavePieces.Room>> rooms = new LinkedHashMap<>();
		for (String id : complex) {
			Collection<int[]> own = sites.get(id);
			rooms.put(id, CavePieces.of(all.get(id), own == null ? List.of() : own));
		}
		Map<String, Integer> fades = new TreeMap<>();
		// ---- the edges: every warp between two rooms of the complex, a shaft's marked ----
		Map<Unit, List<Edge>> edges = new LinkedHashMap<>();
		List<Link> links = new ArrayList<>();
		for (String id : complex) {
			ExportMap a = all.get(id);
			for (ExportMap.Warp warp : a.warps()) {
				ExportMap b = all.get(warp.to());
				ExportMap.Warp arrival = b == null || !complex.contains(b.id()) ? null : b.warp(warp.warp());
				if (arrival == null) {
					continue;
				}
				Unit from = unit(rooms, a, warp.x(), warp.y());
				Unit to = unit(rooms, b, arrival.x(), arrival.y());
				if (from == null || to == null) {
					continue;
				}
				Link shaft = shaftLink(all, ranks, a, warp, b, arrival, fades);
				if (shaft != null) {
					links.add(shaft);
				}
				edge(edges, new Edge(from, warp.x(), warp.y(), to, arrival.x(), arrival.y(), shaft));
				edge(edges, new Edge(to, arrival.x(), arrival.y(), from, warp.x(), warp.y(), shaft));
			}
			// A door from a cave into a room of the complex (a child map) joins them as a warp does.
			if (a.parent() != null && complex.contains(a.parent().map())) {
				ExportMap parent = all.get(a.parent().map());
				ExportMap.Warp door = parent.warp(a.parent().warp());
				ExportMap.Warp arrival = door != null && door.to().equals(id) ? a.warp(door.warp()) : null;
				int ax = arrival == null ? 0 : arrival.x();
				int ay = arrival == null ? 0 : arrival.y();
				Unit from = unit(rooms, parent, a.parent().x(), a.parent().y());
				Unit to = unit(rooms, a, ax, ay);
				if (from != null && to != null) {
					edge(edges, new Edge(from, a.parent().x(), a.parent().y(), to, ax, ay, null));
					edge(edges, new Edge(to, ax, ay, from, a.parent().x(), a.parent().y(), null));
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
					int[] got = aside(all, rooms, ranks, frames, e.to(), want);
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
			frames.put(loose, aside(all, rooms, ranks, frames, loose, want));
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
			if (crossesARoom(all, rooms, ranks, frames, ranks.get(link.topMap()), ranks.get(link.bottomMap()), tx, tz)) {
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
		return new Plan(rooms, byMap, shafts, fades, minX, minZ, maxX, maxZ);
	}

	private static void edge(Map<Unit, List<Edge>> edges, Edge e) {
		edges.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
	}

	private static @Nullable Unit unit(Map<String, List<CavePieces.Room>> rooms, ExportMap map, int cx, int cy) {
		CavePieces.Room room = CavePieces.roomAt(rooms.get(map.id()), map.cellWidth(), cx, cy);
		return room == null ? null : new Unit(map.id(), room.index());
	}

	/**
	 * A shaft's link, from the warp at (x, y) of {@code a} down to {@code arrival} on {@code b}, or null when the warp
	 * isn't one going down (a door, a warp within a floor, the upward warp of a ladder: its top's warp makes the link).
	 */
	private static @Nullable Link shaftLink(Map<String, ExportMap> all, Map<String, Integer> ranks, ExportMap a, ExportMap.Warp warp, ExportMap b,
			ExportMap.Warp arrival, Map<String, Integer> fades) {
		int flags = a.flags(warp.x(), warp.y());
		// A hole is a pad of its own (the engine's warpPadOrHoleAt), a ladder a warp tile that isn't a door
		boolean hole = CellFlags.has(flags, CellFlags.HOLE);
		if (!hole && (!CellFlags.has(flags, CellFlags.WARP_TILE) || CellFlags.has(flags, CellFlags.DOOR))) {
			return null;
		}
		int ra = ranks.get(a.id());
		int rb = ranks.get(b.id());
		if (ra == rb) {
			fades.merge("same_floor", 1, Integer::sum);
			return null;
		}
		if (ra > rb) {
			// Going up: the shaft is its top's; a ladder whose top leads elsewhere keeps its fade.
			if (hole || !leadsTo(b, arrival, a, warp)) {
				fades.merge(hole ? "hole_up" : "one_way_up", 1, Integer::sum);
			}
			return null;
		}
		boolean up = !hole && leadsTo(b, arrival, a, warp);
		return new Link(hole ? "hole" : "ladder", a.id(), warp.x(), warp.y(), b.id(), arrival.x(), arrival.y(), up);
	}

	/** True when the warp of {@code b} on {@code at}'s cell leads to {@code target}'s cell of {@code a}. */
	private static boolean leadsTo(ExportMap b, ExportMap.Warp at, ExportMap a, ExportMap.Warp target) {
		for (ExportMap.Warp back : b.warps()) {
			if (back.x() == at.x() && back.y() == at.y() && back.to().equals(a.id())) {
				ExportMap.Warp there = a.warp(back.warp());
				return there != null && there.x() == target.x() && there.y() == target.y();
			}
		}
		return false;
	}

	/** The entrance map's room the way in reaches: the one with a warp out of the complex, else its first. */
	private static Unit entranceRoom(Map<String, ExportMap> all, List<String> complex, Map<String, List<CavePieces.Room>> rooms, String entrance) {
		ExportMap map = all.get(entrance);
		for (ExportMap.Warp warp : map.warps()) {
			if (!complex.contains(warp.to())) {
				Unit u = unit(rooms, map, warp.x(), warp.y());
				if (u != null) {
					return u;
				}
			}
		}
		return new Unit(entrance, 0);
	}

	/** A room's footprint at {@code frame}, its enclosure ring included: {minX, minZ, maxX, maxZ}. */
	private static int[] box(Map<String, ExportMap> all, Map<String, List<CavePieces.Room>> rooms, Unit u, int[] frame) {
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
	private static int[] aside(Map<String, ExportMap> all, Map<String, List<CavePieces.Room>> rooms, Map<String, Integer> ranks,
			Map<Unit, int[]> frames, Unit u, int[] want) {
		int x = want[0];
		while (overlaps(all, rooms, ranks, frames, u, new int[] {x, want[1]})) {
			x += CavePlanner.FLOOR_GAP;
		}
		return new int[] {x, want[1]};
	}

	private static boolean overlaps(Map<String, ExportMap> all, Map<String, List<CavePieces.Room>> rooms, Map<String, Integer> ranks,
			Map<Unit, int[]> frames, Unit u, int[] frame) {
		int rank = ranks.get(u.map());
		int[] a = box(all, rooms, u, frame);
		int[] aFloor = floorBox(all, rooms, u, frame);
		for (Map.Entry<Unit, int[]> placed : frames.entrySet()) {
			Unit o = placed.getKey();
			if (ranks.get(o.map()) != rank || o.map().equals(u.map()) && java.util.Arrays.equals(placed.getValue(), frame)) {
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
	private static int[] floorBox(Map<String, ExportMap> all, Map<String, List<CavePieces.Room>> rooms, Unit u, int[] frame) {
		CavePieces.Room room = rooms.get(u.map()).get(u.room());
		int w = all.get(u.map()).cellWidth();
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
	private static boolean crossesARoom(Map<String, ExportMap> all, Map<String, List<CavePieces.Room>> rooms, Map<String, Integer> ranks,
			Map<Unit, int[]> frames, int fromRank, int toRank, int x, int z) {
		for (Map.Entry<Unit, int[]> placed : frames.entrySet()) {
			int rank = ranks.get(placed.getKey().map());
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
