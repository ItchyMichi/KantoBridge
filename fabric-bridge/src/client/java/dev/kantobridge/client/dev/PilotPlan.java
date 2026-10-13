package dev.kantobridge.client.dev;

import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapLayout;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * The caves pilot's video (docs/specs/CAVES_3D_PLAN.md, H6), planned from the world index: a walk up a cave's stairs to
 * its raised ground and on to the cliff's edge, and a ladder climbed down and back up. Which cells a player can stand on
 * comes from {@link Cells} (the scene asks Minecraft's blocks); the hidden game checks the cells again before the walk
 * (no warp, no object on them). Pure logic.
 */
final class PilotPlan {
	/** Which cells a player may stand on at the cell's own floor. */
	interface Cells {
		boolean standable(String map, int cx, int cy);
	}

	/**
	 * The stairs: the hidden player is put on the path's first cell, facing up the stairs; the walk follows the path (one
	 * cell a step: up the stairs, then along the raised ground to the edge) and ends looking over the drop.
	 */
	record Stairs(String map, Dir facing, List<int[]> path, Dir look) {
		int[] start() {
			return this.path.get(0);
		}

		int[] edge() {
			return this.path.get(this.path.size() - 1);
		}
	}

	/** A ladder: the hidden player is put on the cell beside its top, facing the top cell and the ladder's wall. */
	record Ladder(WorldIndex.Shaft shaft, int x, int y, Dir facing) {
	}

	private PilotPlan() {
	}

	/** The maps built in 3D (the pilot's), in the index's order. */
	static List<String> caves3d(WorldIndex index) {
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, MapLayout> e : index.layouts().entrySet()) {
			MapLayout layout = e.getValue();
			if (layout != null && (!layout.ramps().isEmpty() || layout.pieces().size() > 1 || index.shafts().stream().anyMatch(
					s -> s.top().map().equals(e.getKey()) || s.bottom().map().equals(e.getKey())))) {
				out.add(e.getKey());
			}
		}
		return out;
	}

	/**
	 * The first stairs (by map, then row, then column) with a run-up on the low side, raised ground beyond and a cliff's
	 * edge reachable on it: a raised cell whose neighbour is lower floor (not stairs, not beside stairs), the nearest one
	 * away from the stairs. Null when no map has one.
	 */
	static @Nullable Stairs stairs(WorldIndex index, Collection<String> maps, Cells cells) {
		List<Stairs> all = allStairs(index, maps, cells);
		return all.isEmpty() ? null : all.get(0);
	}

	/** Every stairs {@link #stairs} could pick, in its order (the hidden game may refuse a walk: the next one is tried). */
	static List<Stairs> allStairs(WorldIndex index, Collection<String> maps, Cells cells) {
		List<Stairs> out = new ArrayList<>();
		for (String map : maps) {
			MapLayout layout = index.layout(map);
			if (layout == null) {
				continue;
			}
			List<MapLayout.Ramp> ramps = new ArrayList<>(layout.ramps());
			ramps.sort(Comparator.comparingInt(MapLayout.Ramp::y).thenComparingInt(MapLayout.Ramp::x));
			for (MapLayout.Ramp ramp : ramps) {
				Stairs stairs = stairs(layout, map, ramp, cells);
				if (stairs != null) {
					out.add(stairs);
				}
			}
		}
		return out;
	}

	private static @Nullable Stairs stairs(MapLayout layout, String map, MapLayout.Ramp ramp, Cells cells) {
		Dir up = ramp.dir();
		int lx = ramp.x() - up.dx();
		int ly = ramp.y() - up.dy();
		int hx = ramp.x() + up.dx();
		int hy = ramp.y() + up.dy();
		if (!floor(layout, map, cells, lx, ly) || !floor(layout, map, cells, hx, hy) || layout.level(hx, hy) <= layout.level(lx, ly)) {
			return null;
		}
		List<int[]> path = new ArrayList<>();
		int sx = lx - up.dx();
		int sy = ly - up.dy();
		if (floor(layout, map, cells, sx, sy) && layout.level(sx, sy) == layout.level(lx, ly)) {
			path.add(new int[] {sx, sy});
		}
		path.add(new int[] {lx, ly});
		path.add(new int[] {ramp.x(), ramp.y()});
		// Breadth first over the raised ground from the stairs' top to the nearest edge away from any stairs.
		int high = layout.level(hx, hy);
		Map<Long, long[]> from = new HashMap<>();
		ArrayDeque<int[]> queue = new ArrayDeque<>();
		from.put(key(hx, hy), null);
		queue.add(new int[] {hx, hy});
		while (!queue.isEmpty()) {
			int[] at = queue.poll();
			Dir drop = drop(layout, map, cells, at[0], at[1], high);
			if (drop != null) {
				List<int[]> tail = new ArrayList<>();
				for (long[] k = {at[0], at[1]}; k != null; k = from.get(key((int) k[0], (int) k[1]))) {
					tail.add(0, new int[] {(int) k[0], (int) k[1]});
				}
				path.addAll(tail);
				return new Stairs(map, up, path, drop);
			}
			for (Dir d : Dir.values()) {
				int nx = at[0] + d.dx();
				int ny = at[1] + d.dy();
				if (!from.containsKey(key(nx, ny)) && floor(layout, map, cells, nx, ny) && layout.level(nx, ny) == high) {
					from.put(key(nx, ny), new long[] {at[0], at[1]});
					queue.add(new int[] {nx, ny});
				}
			}
		}
		return null;
	}

	/** The side of raised cell (cx, cy) that drops to lower floor away from any stairs, or null. */
	private static @Nullable Dir drop(MapLayout layout, String map, Cells cells, int cx, int cy, int high) {
		if (besideRamp(layout, cx, cy)) {
			return null;
		}
		for (Dir d : Dir.values()) {
			int nx = cx + d.dx();
			int ny = cy + d.dy();
			if (floor(layout, map, cells, nx, ny) && layout.level(nx, ny) < high && !besideRamp(layout, nx, ny)) {
				return d;
			}
		}
		return null;
	}

	private static boolean besideRamp(MapLayout layout, int cx, int cy) {
		for (Dir d : Dir.values()) {
			if (layout.ramp(cx + d.dx(), cy + d.dy()) != null) {
				return true;
			}
		}
		return false;
	}

	/** A standable cell of the map that isn't stairs. */
	private static boolean floor(MapLayout layout, String map, Cells cells, int cx, int cy) {
		return cx >= 0 && cy >= 0 && cx < layout.w() && cy < layout.h() && layout.ramp(cx, cy) == null && cells.standable(map, cx, cy);
	}

	/**
	 * The first ladder (in the index's order) whose top is on one of the maps, with a standable cell beside its top: the
	 * one across from the ladder's wall when it can be, so the walk in runs straight at the ladder.
	 */
	static @Nullable Ladder ladder(WorldIndex index, Collection<String> maps, Cells cells) {
		return ladder(index, maps, cells, WorldIndex.Shaft::wall);
	}

	/** The same with each shaft's wall from {@code wall} (null: no ladder to film). */
	static @Nullable Ladder ladder(WorldIndex index, Collection<String> maps, Cells cells, Function<WorldIndex.Shaft, @Nullable Dir> wall) {
		List<Ladder> all = ladders(index, maps, cells, wall);
		return all.isEmpty() ? null : all.get(0);
	}

	/**
	 * Every ladder and standable cell beside its top, best first: by ladder in the index's order, the cell across from
	 * the wall before the ones at its sides (the hidden game may refuse a cell, an object on it: the next one is tried).
	 */
	static List<Ladder> ladders(WorldIndex index, Collection<String> maps, Cells cells, Function<WorldIndex.Shaft, @Nullable Dir> wall) {
		List<Ladder> out = new ArrayList<>();
		for (WorldIndex.Shaft shaft : index.shafts()) {
			if (!WorldIndex.Shaft.LADDER.equals(shaft.kind()) || !maps.contains(shaft.top().map())) {
				continue;
			}
			Dir side = wall.apply(shaft);
			if (side == null) {
				continue;
			}
			CellPos top = shaft.top();
			List<Dir> order = new ArrayList<>(List.of(side, side == Dir.UP || side == Dir.DOWN ? Dir.LEFT : Dir.UP,
					side == Dir.UP || side == Dir.DOWN ? Dir.RIGHT : Dir.DOWN));
			for (Dir facing : order) {
				int ax = top.x() - facing.dx();
				int ay = top.y() - facing.dy();
				if (ax >= 0 && ay >= 0 && cells.standable(top.map(), ax, ay) && index.shaftEnd(new CellPos(top.map(), ax, ay)) == null) {
					out.add(new Ladder(shaft, ax, ay, facing));
				}
			}
		}
		return out;
	}

	private static long key(int cx, int cy) {
		return Autopilot.key(cx, cy);
	}
}
