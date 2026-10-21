package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.shape.LandscapeModels;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapLayout;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * Which outdoor maps stand on raised ground (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D3, D4; the levels pilot of
 * LANDSCAPE_SECTIONS_PLAN.md): the maps the system property {@value #PROPERTY} names (map ids, comma-separated), on the
 * surface or under the open sky. Unset, every outdoor map stays flat, as before. Each named map takes the levels its
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
		Dir[] slopes = LandscapeModels.slopes(id, model.cellWidth(), model.cellHeight());
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
		return rigid(levels.layout(), model, id);
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

	/** The maps {@code value} names (map ids, comma-separated). */
	static Set<String> parse(@Nullable String value) {
		Set<String> out = new TreeSet<>();
		if (value != null) {
			Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(out::add);
		}
		return Set.copyOf(out);
	}
}
