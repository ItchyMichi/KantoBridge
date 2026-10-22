package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.shape.LandscapeModels;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapLayout;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * Which outdoor maps stand on raised ground (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D3, D4; the levels pilot of
 * LANDSCAPE_SECTIONS_PLAN.md): the maps the system property {@value #PROPERTY} names (map ids, comma-separated, or
 * {@value #ALL} for every one), on the surface or under the open sky. Unset, every outdoor map stays flat, as before. Each named map takes the levels its
 * game gives ({@link RaisedRegions#gen3Outdoor}: Emerald's elevations; {@link RaisedRegions#outdoor}: Crystal's and
 * Blue's areas and the faces between them), a cell of stairs or of face climbing {@link RaisedRegions#OUTDOOR_RISE}. A
 * map whose landscape marks name its slopes and stairs ({@link LandscapeModels#slopes}) stands on its drawing's levels
 * instead: Emerald's elevations are the engine's layers, not heights.
 */
public final class OutdoorLevels {
	/** The system property naming the outdoor maps on raised ground. */
	public static final String PROPERTY = "kantobridge.outdoorLevels";

	private OutdoorLevels() {
	}

	/** The maps the system property names (none when unset). */
	public static Set<String> fromProperty() {
		return parse(System.getProperty(PROPERTY));
	}

	/**
	 * Map {@code id}'s raised ground: {@code solve} given the slopes its marks name (null when its drawing wasn't checked for
	 * them), its outcome noted (and the rules it skipped, the edges they leave unwalkable), each landscape model's rock on
	 * one level ({@link #rigid}).
	 */
	public static @Nullable MapLayout plan(String id, MapModel model, Function<Dir[], RaisedRegions.Result> solve, Warnings warnings) {
		return plan(id, model, null, solve, warnings);
	}

	/** The same, with {@code drawn} (the stairs the jar's tables know in the map's drawing, per cell its way up, or null). */
	public static @Nullable MapLayout plan(String id, MapModel model, Dir @Nullable [] drawn, Function<Dir[], RaisedRegions.Result> solve,
			Warnings warnings) {
		return plan(id, model, drawn, solve, null, warnings);
	}

	/** The same, a pin's ground stopping at {@code walls} (the edges the solver's areas stop at, or null). */
	public static @Nullable MapLayout plan(String id, MapModel model, Dir @Nullable [] drawn, Function<Dir[], RaisedRegions.Result> solve,
			@Nullable PairEdges walls, Warnings warnings) {
		Dir[] slopes = slopes(LandscapeModels.slopes(id, model.cellWidth(), model.cellHeight()), drawn);
		RaisedRegions.Result levels = solve.apply(slopes);
		warnings.note("outdoor_levels_" + levels.outcome() + (slopes == null ? "" : "_marked"), Warnings.symbols("map", id));
		if (levels.layout() != null) {
			warnings.note("outdoor_raised_cells", levels.raisedCells());
			if (levels.skipped() > 0) {
				warnings.note("outdoor_levels_skipped", levels.skipped());
			}
			if (!levels.breaks().isEmpty()) {
				warnings.note("outdoor_levels_breaks", levels.breaks().size());
			}
		}
		List<LandscapeModels.Pin> pins = LandscapeModels.pins(id);
		if (!pins.isEmpty()) {
			warnings.note("outdoor_levels_pinned", pins.size());
		}
		return rigid(pinned(levels.layout(), model, pins, walls), model, id);
	}

	/**
	 * {@code layout} with a review's pins (LEVELS_PIPELINE.md, the review): for each, the ground and water round its cell that
	 * stand level with it (four-neighbours, no ramp) at its level, then each stair with the ground at its ends
	 * ({@link #stairsWithTheirEnds}) and the rock round them on its nearest ground again. A map the solver left flat (null)
	 * stands on 0 for them, as its review saw it (Route 132's rock tops), and stays flat if they lift nothing.
	 */
	static @Nullable MapLayout pinned(@Nullable MapLayout layout, MapModel model, List<LandscapeModels.Pin> pins) {
		return pinned(layout, model, pins, null);
	}

	/**
	 * The same, the ground round a pin stopping at {@code walls} (Crystal's one-way walls, a door cut into a face: where the
	 * solver's areas stop; the reviews of Route 25 and Pewter City), or null.
	 */
	static @Nullable MapLayout pinned(@Nullable MapLayout layout, MapModel model, List<LandscapeModels.Pin> pins, @Nullable PairEdges walls) {
		if (pins.isEmpty()) {
			return layout;
		}
		int w = layout == null ? model.cellWidth() : layout.w();
		int h = layout == null ? model.cellHeight() : layout.h();
		Collection<MapLayout.Ramp> rampList = layout == null ? List.of() : layout.ramps();
		int[] levels = new int[w * h];
		boolean[] standable = new boolean[w * h];
		Set<Integer> ramps = new HashSet<>();
		for (MapLayout.Ramp r : rampList) {
			ramps.add(r.y() * w + r.x());
		}
		for (int i = 0; i < w * h; i++) {
			levels[i] = layout == null ? 0 : layout.level(i % w, i / w);
			standable[i] = !rock(model.kind(i % w, i / w));
		}
		for (LandscapeModels.Pin pin : pins) {
			if (pin.x() < 0 || pin.y() < 0 || pin.x() >= w || pin.y() >= h || !standable[pin.y() * w + pin.x()]) {
				continue;
			}
			int start = pin.y() * w + pin.x();
			int was = levels[start];
			ArrayDeque<Integer> queue = new ArrayDeque<>(List.of(start));
			boolean[] seen = new boolean[w * h];
			seen[start] = true;
			while (!queue.isEmpty()) {
				int c = queue.poll();
				levels[c] = pin.level();
				for (Dir dir : Dir.values()) {
					int nx = c % w + dir.dx();
					int ny = c / w + dir.dy();
					int j = ny * w + nx;
					if (nx >= 0 && ny >= 0 && nx < w && ny < h && !seen[j] && standable[j] && levels[j] == was && !ramps.contains(j)
							&& (walls == null || !walls.closed(c % w, c / w, nx, ny))) {
						seen[j] = true;
						queue.add(j);
					}
				}
			}
		}
		List<MapLayout.Ramp> kept = stairsWithTheirEnds(w, h, levels, standable, rampList);
		RaisedRegions.nearestLevels(w, h, standable, levels);
		if (layout == null && Arrays.stream(levels).allMatch(v -> v == 0)) {
			return null;
		}
		return MapLayout.flat(w, h, levels, kept);
	}

	/**
	 * After the pins, each flight of {@code ramps} (its steps climbing one way in a row) with the ground at its two ends: pins
	 * that moved both ends alike move its steps with them (Mt. Pyre's summit); pins that left its ends level make its steps
	 * that ground, no stair (the ring round a beach chair the data's elevations read as steps: Route 109's review); any other
	 * keeps its steps where they were. {@code levels} takes the steps' levels; returns the ramps kept.
	 */
	static List<MapLayout.Ramp> stairsWithTheirEnds(int w, int h, int[] levels, boolean[] standable, Collection<MapLayout.Ramp> ramps) {
		Map<Integer, MapLayout.Ramp> at = new HashMap<>();
		for (MapLayout.Ramp r : ramps) {
			at.put(r.y() * w + r.x(), r);
		}
		Set<Integer> done = new HashSet<>();
		Set<Integer> flat = new HashSet<>();
		for (MapLayout.Ramp r : ramps) {
			int bottom = r.y() * w + r.x();
			if (done.contains(bottom)) {
				continue;
			}
			for (int b = along(w, h, bottom, r.dir(), -1); b >= 0 && at.containsKey(b) && at.get(b).dir() == r.dir(); b = along(w, h, b, r.dir(), -1)) {
				bottom = b;
			}
			List<Integer> flight = new ArrayList<>();
			int top = bottom;
			while (top >= 0 && at.containsKey(top) && at.get(top).dir() == r.dir()) {
				flight.add(top);
				done.add(top);
				top = along(w, h, top, r.dir(), 1);
			}
			int foot = along(w, h, bottom, r.dir(), -1);
			if (foot < 0 || top < 0 || !standable[foot] || !standable[top] || at.containsKey(foot) || at.containsKey(top)) {
				continue;
			}
			int climb = flight.stream().mapToInt(s -> at.get(s).rise()).sum();
			int step = levels[foot];
			if (levels[top] - levels[foot] == climb) {
				for (int s : flight) {
					levels[s] = step;
					step += at.get(s).rise();
				}
			} else if (levels[top] == levels[foot]) {
				for (int s : flight) {
					levels[s] = step;
					flat.add(s);
				}
			}
		}
		return ramps.stream().filter(r -> !flat.contains(r.y() * w + r.x())).toList();
	}

	/** The cell {@code by} cells from cell {@code c} the way {@code dir} goes, or -1 off the map. */
	private static int along(int w, int h, int c, Dir dir, int by) {
		int x = c % w + dir.dx() * by;
		int y = c / w + dir.dy() * by;
		return x >= 0 && y >= 0 && x < w && y < h ? y * w + x : -1;
	}

	/**
	 * The slopes a map climbs on: the stairs the tables find in its drawing ({@code drawn}), the marked ones over them
	 * ({@code marked}: a mark wins); null when neither knows any (and the marks weren't checked): its data decides.
	 */
	static Dir @Nullable [] slopes(Dir @Nullable [] marked, Dir @Nullable [] drawn) {
		boolean found = drawn != null && Arrays.stream(drawn).anyMatch(java.util.Objects::nonNull);
		if (!found) {
			return marked;
		}
		Dir[] out = drawn.clone();
		if (marked != null) {
			for (int i = 0; i < out.length && i < marked.length; i++) {
				if (marked[i] != null) {
					out[i] = marked[i];
				}
			}
		}
		return out;
	}

	/**
	 * {@code layout} with each landscape model's rock on one level, the lowest of its cells' (LANDSCAPE_MODELS.md): a model is
	 * one shape, its voxels counted from that level, so no column of it stands higher than the others; its walkable cells
	 * (a mouth's door) keep their own. Null stays null.
	 */
	public static @Nullable MapLayout rigid(@Nullable MapLayout layout, MapModel model, String id) {
		return rigid(layout, model, LandscapeModels.cells(id, model.cellWidth(), dev.kantobridge.world.Scale.CELL_BLOCKS));
	}

	/** The same for the models whose cells (indexes {@code cy * w + cx}) are {@code models}. */
	static @Nullable MapLayout rigid(@Nullable MapLayout layout, MapModel model, List<Set<Integer>> models) {
		if (layout == null) {
			return null;
		}
		int w = layout.w();
		int h = layout.h();
		if (models.isEmpty() || w != model.cellWidth() || h != model.cellHeight()) {
			return layout;
		}
		int[] levels = new int[w * h];
		for (int i = 0; i < w * h; i++) {
			levels[i] = layout.level(i % w, i / w);
		}
		for (Set<Integer> cells : models) {
			int base = Integer.MAX_VALUE;
			for (int c : cells) {
				if (c < w * h && rock(model.kind(c % w, c / w))) {
					base = Math.min(base, levels[c]);
				}
			}
			for (int c : cells) {
				if (c < w * h && rock(model.kind(c % w, c / w))) {
					levels[c] = base;
				}
			}
		}
		return MapLayout.flat(w, h, levels, List.copyOf(layout.ramps()));
	}

	private static boolean rock(MapModel.Kind kind) {
		return kind == MapModel.Kind.SOLID || kind == MapModel.Kind.COUNTER;
	}

	/** The property's value naming every outdoor map. */
	public static final String ALL = "all";

	/** Whether {@code named} (the property's maps) covers map {@code id}: it names it, or it is {@value #ALL}. */
	public static boolean covers(Set<String> named, String id) {
		return named.contains(ALL) || named.contains(id);
	}

	/** The maps {@code value} names (map ids, comma-separated; {@value #ALL} for every outdoor map). */
	static Set<String> parse(@Nullable String value) {
		Set<String> out = new TreeSet<>();
		if (value != null) {
			Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(out::add);
		}
		return Set.copyOf(out);
	}
}
