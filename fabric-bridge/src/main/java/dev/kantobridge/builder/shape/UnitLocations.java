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

	/**
	 * Every cell of one map as the planner saw it (a diagnostic for the review): its key, its kind (walk, solid, ...),
	 * why the planner made it a unit or not (building, walk, not_low, pinned_other, no_rule, unit:<source>), and, for a unit,
	 * its source (lone, hint, probe, fit, ...) and its key's variant.
	 */
	public static java.util.List<JsonObject> describe(Scene scene, ShapePlan plan, String mapId, Map<String, String> why) {
		java.util.List<JsonObject> out = new java.util.ArrayList<>();
		SceneMap map = scene.maps().get(mapId);
		ShapePlanner.MapShapes shapes = plan.shapes(mapId);
		if (map == null || shapes == null) {
			return out;
		}
		Map<Long, Units.Cell> units = new TreeMap<>();
		for (Units.Cell cell : shapes.units()) {
			units.put((long) cell.cy() << 32 | cell.cx(), cell);
		}
		for (int cy = 0; cy < map.h(); cy++) {
			for (int cx = 0; cx < map.w(); cx++) {
				JsonObject o = new JsonObject();
				o.addProperty("x", cx);
				o.addProperty("y", cy);
				o.addProperty("key", FittedShapes.key(map.atlas(), Units.tiles(map, cx, cy)));
				o.addProperty("kind", map.kind(cx, cy).name());
				String reason = why.get(mapId + ":" + cx + ":" + cy);
				if (reason != null) {
					o.addProperty("why", reason);
				}
				Units.Cell unit = units.get((long) cy << 32 | cx);
				if (unit != null) {
					o.addProperty("unit", unit.source());
					o.addProperty("variant", unit.key().variant());
				}
				out.add(o);
			}
		}
		return out;
	}

	/**
	 * One map for the survey (docs/specs/FITTED_SHAPES.md section 14): every cell's key (an index into {@code keys}), kind,
	 * why the planner made it a unit or not, the fitted table's class for it, and the structure region of its solid tiles
	 * ({@link Structures#regions}, -1 for none); the map's templates (buildings), its objects, and the blocks per art tile column
	 * Tier A builds. Pixel-free.
	 */
	public static JsonObject survey(SceneMap map, ShapePlanner.@Nullable MapShapes shapes, Map<String, String> why, Map<String, Integer> keyIndex,
			com.google.gson.JsonArray keys) {
		JsonObject o = new JsonObject();
		o.addProperty("map", map.id());
		o.addProperty("w", map.w());
		o.addProperty("h", map.h());
		o.addProperty("perCell", map.perCell());
		o.addProperty("atlas", map.atlas());
		o.addProperty("surface", map.surface());
		o.addProperty("outdoor", map.env().outdoor());
		int per = map.perCell();
		int[] region = Structures.regions(Structures.solidTiles(map), map.artCols(), map.artRows());
		com.google.gson.JsonArray cell = new com.google.gson.JsonArray();
		StringBuilder kind = new StringBuilder();
		com.google.gson.JsonArray reason = new com.google.gson.JsonArray();
		com.google.gson.JsonArray fit = new com.google.gson.JsonArray();
		com.google.gson.JsonArray reg = new com.google.gson.JsonArray();
		for (int cy = 0; cy < map.h(); cy++) {
			for (int cx = 0; cx < map.w(); cx++) {
				int[] tiles = Units.tiles(map, cx, cy);
				String key = FittedShapes.key(map.atlas(), tiles);
				Integer k = keyIndex.get(key);
				if (k == null) {
					k = keys.size();
					keyIndex.put(key, k);
					keys.add(key);
				}
				cell.add(k);
				kind.append(map.kind(cx, cy).name().charAt(0));
				String r = why.get(map.id() + ":" + cx + ":" + cy);
				reason.add(r == null ? "" : r);
				FittedShapes.Entry e = FittedShapes.get(map.atlas(), tiles);
				fit.add(e == null ? "" : e.cls());
				int rg = -1;
				for (int dy = 0; dy < per && rg < 0; dy++) {
					for (int dx = 0; dx < per && rg < 0; dx++) {
						rg = region[(cy * per + dy) * map.artCols() + cx * per + dx];
					}
				}
				reg.add(rg);
			}
		}
		o.add("cell", cell);
		o.addProperty("kind", kind.toString());
		o.add("why", reason);
		o.add("fit", fit);
		o.add("region", reg);
		com.google.gson.JsonArray tmpl = new com.google.gson.JsonArray();
		if (shapes != null) {
			for (Templates.Placed t : shapes.templates()) {
				JsonObject j = new JsonObject();
				j.addProperty("sig", t.sig());
				j.addProperty("x0", t.x0());
				j.addProperty("y0", t.y0());
				j.add("entry", t.entry());
				tmpl.add(j);
			}
		}
		o.add("templates", tmpl);
		if (shapes != null) {
			// blocks per art tile column as Tier A builds it (its height), row-major over the art tiles
			com.google.gson.JsonArray blocks = new com.google.gson.JsonArray();
			for (int b : shapes.blocks()) {
				blocks.add(b);
			}
			o.add("blocks", blocks);
		}
		com.google.gson.JsonArray objs = new com.google.gson.JsonArray();
		for (SceneMap.SceneObject so : map.objects()) {
			JsonObject j = new JsonObject();
			j.addProperty("name", so.name());
			j.addProperty("sprite", so.sprite());
			j.addProperty("x", so.x());
			j.addProperty("y", so.y());
			j.addProperty("kind", so.kind());
			objs.add(j);
		}
		o.add("objects", objs);
		return o;
	}

	/** {@link #survey} for every map of {@code scene}, with {@code game}'s fitted table read. */
	public static java.util.List<JsonObject> surveyAll(String game, Scene scene, ShapePlan plan, Map<String, String> why, Map<String, Integer> keyIndex,
			com.google.gson.JsonArray keys) {
		java.util.List<JsonObject> out = new java.util.ArrayList<>();
		String before = FittedShapes.enter(game);
		try {
			for (SceneMap map : scene.maps().values()) {
				out.add(survey(map, plan.shapes(map.id()), why, keyIndex, keys));
			}
		} finally {
			FittedShapes.leave(before);
		}
		return out;
	}
}
