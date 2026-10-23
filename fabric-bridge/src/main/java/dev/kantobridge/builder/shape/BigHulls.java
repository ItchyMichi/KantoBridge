package dev.kantobridge.builder.shape;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.scene.profile.Profile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The profile's two-cell hulls in the planner (docs/specs/PROFILE_PLAN.md §5.6, P3): a canopy over 2 × 2 cells and a
 * planter two cells tall, each a template ({@code tmpl} keys) whose entry carries {@code hull}, built on each client from
 * its own drawing ({@code geom/BigHull}). Pixel-free: tile ids, cell kinds and classes.
 *
 * <pre>
 * canopy   a solid cell whose top-left tile is a canopy, its cells east, south and south-east solid with a hull or canopy
 *          class on their top-left tiles: the four cells, 32 pixels (four blocks) high under the map's headroom
 * planter  a solid cell whose top-left tile is a planter over a solid cell whose top-left tile is a planter too: the model
 *          stands on the south cell, 32 high; the north cell (its crown, which the model shows as height) keeps two
 *          blocks of collision and draws nothing there
 * claims   scanned row-major, the cells of a group taken once; a cell a building holds is never part of one
 * floor    the group's ground: the most frequent ground tile around it, else the map's
 * </pre>
 */
final class BigHulls {
	/** The entry format of a two-cell hull in the shape index ({@code hull.format}). */
	static final int FORMAT = 1;
	/** A two-cell hull's height in pixels. */
	static final int HEIGHT = 32;

	/** A group: its kind ({@code canopy}, {@code planter}), its north-west cell, its size in cells. */
	record Group(String kind, int cx, int cy, int cw, int ch) {
	}

	private BigHulls() {
	}

	/**
	 * The two-cell hulls of a map, scan order.
	 *
	 * @param held per art tile, set where a building holds it; the groups' tiles are added
	 */
	static List<Group> find(SceneMap map, ProfileClasses classes, boolean[] held, Map<String, Integer> tally) {
		List<Group> out = new ArrayList<>();
		int per = map.perCell();
		boolean[] taken = new boolean[map.w() * map.h()];
		for (int cy = 0; cy < map.h(); cy++) {
			for (int cx = 0; cx < map.w(); cx++) {
				String cls = classes.at(cx * per, cy * per);
				if (!"canopy".equals(cls) && !"planter".equals(cls)) {
					continue;
				}
				if (!free(map, held, taken, cx, cy)) {
					tally.merge(cls + "_alone", 1, Integer::sum);
					continue;
				}
				Group group = null;
				if ("canopy".equals(cls)) {
					if (partner(map, classes, held, taken, cx + 1, cy, false) && partner(map, classes, held, taken, cx, cy + 1, false)
							&& partner(map, classes, held, taken, cx + 1, cy + 1, false)) {
						group = new Group(cls, cx, cy, 2, 2);
					}
				} else if (partner(map, classes, held, taken, cx, cy + 1, true)) {
					group = new Group(cls, cx, cy, 1, 2);
				}
				if (group == null) {
					tally.merge(cls + "_alone", 1, Integer::sum);
					continue;
				}
				for (int y = 0; y < group.ch(); y++) {
					for (int x = 0; x < group.cw(); x++) {
						taken[(cy + y) * map.w() + cx + x] = true;
						for (int dy = 0; dy < per; dy++) {
							for (int dx = 0; dx < per; dx++) {
								held[((cy + y) * per + dy) * map.artCols() + (cx + x) * per + dx] = true;
							}
						}
					}
				}
				tally.merge(cls, 1, Integer::sum);
				out.add(group);
			}
		}
		return out;
	}

	private static boolean free(SceneMap map, boolean[] held, boolean[] taken, int cx, int cy) {
		if (cx < 0 || cy < 0 || cx >= map.w() || cy >= map.h() || taken[cy * map.w() + cx] || map.kind(cx, cy) != CellKind.SOLID) {
			return false;
		}
		int per = map.perCell();
		for (int dy = 0; dy < per; dy++) {
			for (int dx = 0; dx < per; dx++) {
				if (held[(cy * per + dy) * map.artCols() + cx * per + dx]) {
					return false;
				}
			}
		}
		return true;
	}

	/** A partner cell: free, solid, its top-left tile a planter (for a planter) or of hull or canopy art (for a canopy). */
	private static boolean partner(SceneMap map, ProfileClasses classes, boolean[] held, boolean[] taken, int cx, int cy, boolean planter) {
		if (!free(map, held, taken, cx, cy)) {
			return false;
		}
		String cls = classes.at(cx * map.perCell(), cy * map.perCell());
		Profile.Mode mode = cls == null ? null : Profile.CLASSES.get(cls);
		return planter ? "planter".equals(cls) : mode == Profile.Mode.HULL || mode == Profile.Mode.CANOPY;
	}

	/** Places a group into a map's columns and returns its template. */
	static Templates.Placed place(SceneMap map, int atlas, Group group, int ground, ShapeKey[][] keys, int[] blocks, int[] mask, int[] floor) {
		int per = map.perCell();
		int cols = map.artCols();
		int w = group.cw() * per;
		int d = group.ch() * per;
		int x0 = group.cx() * per;
		int y0 = group.cy() * per;
		int high = Math.min((HEIGHT + 7) / 8, ShapeHeights.headroom(map));
		JsonArray tiles = new JsonArray();
		JsonArray columns = new JsonArray();
		JsonArray solid = new JsonArray();
		int[] heights = new int[w * d];
		for (int y = 0; y < d; y++) {
			for (int x = 0; x < w; x++) {
				tiles.add(map.tile(x0 + x, y0 + y));
				columns.add(new JsonArray());
				// A planter's north cell: its crown stands as the model's height over the south cell; two blocks of collision.
				heights[y * w + x] = "planter".equals(group.kind()) && y < per ? 2 : high;
				solid.add(heights[y * w + x]);
			}
		}
		JsonObject hull = new JsonObject();
		hull.addProperty("format", FORMAT);
		hull.addProperty("kind", group.kind());
		hull.addProperty("height", HEIGHT);
		hull.add("solid", solid);
		JsonObject entry = new JsonObject();
		entry.addProperty("template", Templates.FORMAT);
		entry.addProperty("atlas", atlas);
		entry.addProperty("w", w);
		entry.addProperty("d", d);
		entry.addProperty("perCell", per);
		entry.add("tiles", tiles);
		entry.add("doors", new JsonArray());
		entry.addProperty("ground", ground);
		entry.addProperty("outdoor", map.outdoors() && map.env().outdoor());
		entry.add("columns", columns);
		entry.add("hull", hull);
		String sig = Templates.signature(entry);
		for (int y = 0; y < d; y++) {
			for (int x = 0; x < w; x++) {
				int i = (y0 + y) * cols + x0 + x;
				int n = heights[y * w + x];
				ShapeKey[] column = new ShapeKey[n];
				for (int k = 0; k < n; k++) {
					column[k] = ShapeKey.tmpl(atlas, sig, x, k, y);
				}
				keys[i] = column;
				blocks[i] = n;
				mask[i] = (1 << n) - 1;
				floor[i] = ground;
			}
		}
		return new Templates.Placed(sig, x0, y0, entry);
	}

	/** The ground of a group: the most frequent walkable tile around it, else {@code fallback}. */
	static int ground(SceneMap map, Group group, int fallback, java.util.Set<Integer> grass, java.util.Set<Integer> frames) {
		java.util.TreeMap<Integer, Integer> counts = new java.util.TreeMap<>();
		for (int y = -1; y <= group.ch(); y++) {
			for (int x = -1; x <= group.cw(); x++) {
				int cx = group.cx() + x;
				int cy = group.cy() + y;
				if ((x >= 0 && x < group.cw() && y >= 0 && y < group.ch()) || cx < 0 || cy < 0 || cx >= map.w() || cy >= map.h()
						|| map.kind(cx, cy) != CellKind.WALK) {
					continue;
				}
				for (int tile : Units.tiles(map, cx, cy)) {
					if (!grass.contains(tile) && !frames.contains(tile)) {
						counts.merge(tile, 1, Integer::sum);
					}
				}
			}
		}
		int best = fallback;
		int bestCount = 0;
		for (var e : counts.entrySet()) {
			if (e.getValue() > bestCount) {
				best = e.getKey();
				bestCount = e.getValue();
			}
		}
		return best;
	}
}
