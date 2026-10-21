package dev.kantobridge.builder.shape;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.CellTag;
import dev.kantobridge.scene.FrontEnds;
import dev.kantobridge.scene.Scene;
import dev.kantobridge.scene.SceneMap;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The landscape sections pilot's first pass (docs/specs/LANDSCAPE_SECTIONS_PLAN.md section 2.1), pod only: per named map,
 * the 4-connected regions of solid cells and what the planner knows of each (its cells, the floor along each side, the
 * drawn rows of its south face per column, its share of repeats, foliage and fitted models, the doors under it). Map
 * ids, cell positions and counts only, never a tile or a pixel. -Pkb.landscape="&lt;bundle&gt;;&lt;out dir&gt;;MAP,MAP,...".
 */
@EnabledIfSystemProperty(named = "kb.landscape", matches = ".+")
class LandscapeRegionsTest {
	@Test
	void regions() throws Exception {
		String[] p = System.getProperty("kb.landscape").split(";");
		ExportBundle bundle = ExportBundle.open(Path.of(p[0]));
		Scene scene = FrontEnds.forGame(bundle.game()).describe(bundle);
		ShapeParams params = ShapeParams.forScene(scene);
		Path out = Path.of(p[1]);
		Files.createDirectories(out);
		String before = FittedShapes.enter(scene.game());
		try {
			for (String id : p[2].split(",")) {
				SceneMap map = scene.maps().get(id);
				if (map != null) {
					Files.writeString(out.resolve(id + ".json"), ProtoJson.write(regions(scene, map, params)), StandardCharsets.UTF_8);
				}
			}
		} finally {
			FittedShapes.leave(before);
		}
	}

	private static JsonObject regions(Scene scene, SceneMap map, ShapeParams params) {
		int w = map.w();
		int h = map.h();
		int per = map.perCell();
		int cols = map.artCols();
		int[] region = new int[w * h];
		Arrays.fill(region, -1);
		int count = 0;
		for (int i = 0; i < w * h; i++) {
			if (region[i] >= 0 || map.kind(i % w, i / w) != CellKind.SOLID) {
				continue;
			}
			ArrayDeque<Integer> queue = new ArrayDeque<>();
			queue.add(i);
			region[i] = count;
			while (!queue.isEmpty()) {
				int c = queue.poll();
				int cx = c % w;
				int cy = c / w;
				int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
				for (int[] s : steps) {
					int nx = cx + s[0];
					int ny = cy + s[1];
					if (nx >= 0 && ny >= 0 && nx < w && ny < h && region[ny * w + nx] < 0 && map.kind(nx, ny) == CellKind.SOLID) {
						region[ny * w + nx] = count;
						queue.add(ny * w + nx);
					}
				}
			}
			count++;
		}
		boolean[] solid = Structures.solidTiles(map);
		boolean[] leaves = Structures.foliage(map, scene.atlas(map.atlas()));
		boolean[] fitted = FittedShapes.fittedTiles(map);
		List<Structures.MeasuredRun> runs = Structures.measured(map, solid, params.maxUnit(), params.minRepeatRows(), params.interiorWall(), true,
				leaves, fitted);
		boolean[] pure = new boolean[solid.length];
		boolean[] read = new boolean[solid.length];
		for (Structures.MeasuredRun run : runs) {
			for (int ty = run.n(); ty <= run.f(); ty++) {
				pure[ty * cols + run.tx()] = run.pureRepeat();
				read[ty * cols + run.tx()] = run.repeatRead();
			}
		}
		JsonObject outMap = new JsonObject();
		outMap.addProperty("map", map.id());
		outMap.addProperty("w", w);
		outMap.addProperty("h", h);
		outMap.addProperty("surface", map.surface());
		JsonArray grid = new JsonArray();
		for (int r : region) {
			grid.add(r);
		}
		outMap.add("grid", grid);
		JsonArray list = new JsonArray();
		for (int r = 0; r < count; r++) {
			int cells = 0;
			int minX = w;
			int minY = h;
			int maxX = -1;
			int maxY = -1;
			int[] sides = new int[4];
			int fit = 0;
			int tiles = 0;
			int leafy = 0;
			int repeats = 0;
			int reads = 0;
			JsonArray doors = new JsonArray();
			JsonObject front = new JsonObject();
			for (int cy = 0; cy < h; cy++) {
				for (int cx = 0; cx < w; cx++) {
					if (region[cy * w + cx] != r) {
						continue;
					}
					cells++;
					minX = Math.min(minX, cx);
					minY = Math.min(minY, cy);
					maxX = Math.max(maxX, cx);
					maxY = Math.max(maxY, cy);
					sides[0] += floor(map, cx, cy + 1) ? 1 : 0;
					sides[1] += floor(map, cx, cy - 1) ? 1 : 0;
					sides[2] += floor(map, cx + 1, cy) ? 1 : 0;
					sides[3] += floor(map, cx - 1, cy) ? 1 : 0;
					if (cy + 1 < h && map.hasTag(cx, cy + 1, CellTag.DOOR)) {
						JsonArray d = new JsonArray();
						d.add(cx);
						d.add(cy + 1);
						doors.add(d);
					}
					// the south face's drawn rows in this column: the region's cells up from a cell with no region cell south of it
					if (cy + 1 >= h || region[(cy + 1) * w + cx] != r) {
						int rows = 0;
						for (int y = cy; y >= 0 && region[y * w + cx] == r; y--) {
							rows += per;
						}
						String key = Integer.toString(cx);
						if (!front.has(key) || front.get(key).getAsInt() < rows) {
							front.addProperty(key, rows);
						}
					}
					fit += fitted[(cy * per) * cols + cx * per] ? 1 : 0;
					for (int k = 0; k < per * per; k++) {
						int i = (cy * per + k / per) * cols + cx * per + k % per;
						tiles++;
						leafy += leaves[i] ? 1 : 0;
						repeats += pure[i] ? 1 : 0;
						reads += read[i] ? 1 : 0;
					}
				}
			}
			JsonObject o = new JsonObject();
			o.addProperty("id", r);
			o.addProperty("cells", cells);
			JsonArray box = new JsonArray();
			box.add(minX);
			box.add(minY);
			box.add(maxX);
			box.add(maxY);
			o.add("bbox", box);
			JsonObject s = new JsonObject();
			s.addProperty("S", sides[0]);
			s.addProperty("N", sides[1]);
			s.addProperty("E", sides[2]);
			s.addProperty("W", sides[3]);
			o.add("floor_sides", s);
			o.add("front_rows", front);
			o.addProperty("fitted_cells", fit);
			o.addProperty("foliage_share", Math.round(100.0 * leafy / tiles) / 100.0);
			o.addProperty("pure_repeat_share", Math.round(100.0 * repeats / tiles) / 100.0);
			o.addProperty("repeat_read_share", Math.round(100.0 * reads / tiles) / 100.0);
			o.add("doors_below", doors);
			list.add(o);
		}
		outMap.add("regions", list);
		return outMap;
	}

	private static boolean floor(SceneMap map, int cx, int cy) {
		if (cx < 0 || cy < 0 || cx >= map.w() || cy >= map.h()) {
			return false;
		}
		CellKind kind = map.kind(cx, cy);
		return kind == CellKind.WALK || kind == CellKind.LEDGE || kind == CellKind.WATER;
	}
}
