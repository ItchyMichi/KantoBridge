package dev.kantobridge.builder.shape;

import com.google.gson.JsonObject;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.Scene;
import dev.kantobridge.scene.SceneMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * Where to look at a planned unit in its scene (docs/specs/FITTED_SHAPES.md section 12): per cell key
 * ({@code <atlas>/<t0>-<t1>-<t2>-<t3>}), one map that plans it, the unit's cell, and a walkable cell beside it with the
 * way to face it. A surface map is preferred, then the map that plans the cell most often; the stand cell is the nearest
 * walkable cell south of the unit (so the player looks north at its drawn side), else any walkable cell nearby.
 */
public final class UnitLocations {
	/** How far from the unit a stand cell may be, in cells. */
	static final int REACH = 4;

	private UnitLocations() {
	}

	public static Map<String, JsonObject> locate(Scene scene, ShapePlan plan, @Nullable Set<String> wanted) {
		Map<String, JsonObject> best = new TreeMap<>();
		Map<String, Integer> bestScore = new TreeMap<>();
		for (SceneMap map : scene.maps().values()) {
			ShapePlanner.MapShapes shapes = plan.shapes(map.id());
			if (shapes == null) {
				continue;
			}
			Map<String, Integer> counts = new TreeMap<>();
			Map<String, Units.Cell> first = new TreeMap<>();
			for (Units.Cell cell : shapes.units()) {
				ShapeKey k = cell.key();
				String key = FittedShapes.key(map.atlas(), new int[] {k.number(0), k.number(1), k.number(2), k.number(3)});
				if (wanted != null && !wanted.contains(key)) {
					continue;
				}
				counts.merge(key, 1, Integer::sum);
				Units.Cell old = first.get(key);
				if (old == null || stand(map, cell.cx(), cell.cy()) != null && stand(map, old.cx(), old.cy()) == null) {
					first.put(key, cell);
				}
			}
			for (Map.Entry<String, Units.Cell> e : first.entrySet()) {
				Units.Cell cell = e.getValue();
				int[] stand = stand(map, cell.cx(), cell.cy());
				int score = (map.surface() ? 1_000_000 : 0) + (stand != null ? 100_000 : 0) + counts.get(e.getKey());
				if (score <= bestScore.getOrDefault(e.getKey(), -1)) {
					continue;
				}
				JsonObject o = new JsonObject();
				o.addProperty("map", map.id());
				o.addProperty("ux", cell.cx());
				o.addProperty("uy", cell.cy());
				o.addProperty("uses", counts.get(e.getKey()));
				o.addProperty("surface", map.surface());
				if (stand != null) {
					o.addProperty("x", stand[0]);
					o.addProperty("y", stand[1]);
					o.addProperty("facing", facing(stand[0] - cell.cx(), stand[1] - cell.cy()));
				}
				best.put(e.getKey(), o);
				bestScore.put(e.getKey(), score);
			}
		}
		return best;
	}

	/** The walkable cell to stand on to look at the unit at (cx, cy), or null. */
	static int @Nullable [] stand(SceneMap map, int cx, int cy) {
		for (int d = 1; d <= REACH; d++) {
			if (walk(map, cx, cy + d)) {
				return new int[] {cx, cy + d};
			}
		}
		int[] found = null;
		int bestD = Integer.MAX_VALUE;
		for (int dy = -REACH; dy <= REACH; dy++) {
			for (int dx = -REACH; dx <= REACH; dx++) {
				int d = Math.abs(dx) + Math.abs(dy) * 2 + (dy < 0 ? 3 : 0);
				if ((dx != 0 || dy != 0) && walk(map, cx + dx, cy + dy) && d < bestD) {
					bestD = d;
					found = new int[] {cx + dx, cy + dy};
				}
			}
		}
		return found;
	}

	private static boolean walk(SceneMap map, int x, int y) {
		return x >= 0 && y >= 0 && x < map.w() && y < map.h() && map.kind(x, y) == CellKind.WALK;
	}

	/** The way to face from a stand cell offset (dx, dy) from the unit. */
	static String facing(int dx, int dy) {
		if (Math.abs(dy) >= Math.abs(dx)) {
			return dy > 0 ? "up" : "down";
		}
		return dx > 0 ? "left" : "right";
	}
}
