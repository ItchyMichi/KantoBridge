package dev.kantobridge.builder.shape;

import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.CellTag;
import dev.kantobridge.scene.SceneMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * The visible-layers check (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D9, V4): for every floor cell of an outdoor map, the rows
 * the hidden game draws straight up the screen over it (D8: the solid tiles to the first open tile, the map's edge or
 * the screen's rows, after a door's own two rows) against what the plan built for them. The rows belong to the things
 * standing one behind the other (a house, the trees behind it, a cliff behind those): each of the planner's runs shows
 * the rows the screen leaves it, and something built must show them. Rows of separate things (a tree line, a fence: the
 * same art every one or two rows) are each their own 2-row unit: their front two rows are enough.
 * What shows a row: a run's column its rows from the run's fold origin up, a block a row and a roof block its roof rows
 * (its slope shows them); a unit (a tree, a sign, a hand model) its height: among rows of separate things from its
 * cell's south row up (so a 2-cell tree on its lower cell shows its crown), in a face from the face's origin up, as the
 * face's columns do; the tiles a model of their own stands on (a fitted building, a hull) their own rows, at the model's
 * scale. A unit over a door (a cave mouth's or a wall exit's model) is the doorway, its door's rows its own; the door's
 * cell is no floor to stand on (stepping on it takes you in). Counts only.
 */
public final class VisibleRows {
	/**
	 * What a map lacks.
	 *
	 * @param columns tile columns over a floor cell that show fewer rows than drawn
	 * @param rows    the rows they lack in all
	 * @param worst   the most rows one lacks
	 * @param kinds   the columns short by what lacks first: {@code model} (a run whose fitted models stand at their own
	 *                height), {@code door} (the face over a door), {@code face}, {@code repeat} (rows of separate things),
	 *                each with {@code /behind} when it stands behind another
	 * @param first   per kind, the floor cell ({@code "cx,cy"}) of its first column short, row-major
	 */
	public record Missing(int columns, int rows, int worst, Map<String, Integer> kinds, Map<String, String> first) {
		public Missing {
			kinds = Collections.unmodifiableMap(new TreeMap<>(kinds));
			first = Collections.unmodifiableMap(new TreeMap<>(first));
		}
	}

	private VisibleRows() {
	}

	/** The rows {@code map}'s columns {@code open} lack, the screen {@code screen} rows tall. */
	static Missing check(SceneMap map, ShapePlanner.Columns open, int screen) {
		int cols = map.artCols();
		int per = map.perCell();
		Structures.MeasuredRun[] at = new Structures.MeasuredRun[cols * map.artRows()];
		for (Structures.MeasuredRun run : open.runs()) {
			for (int ty = run.n(); ty <= run.f(); ty++) {
				at[ty * cols + run.tx()] = run;
			}
		}
		Set<Integer> unitCells = new HashSet<>();
		for (Units.Cell cell : open.units()) {
			unitCells.add(cell.cy() * map.w() + cell.cx());
		}
		int columns = 0;
		int lacking = 0;
		int worst = 0;
		Map<String, Integer> kinds = new TreeMap<>();
		Map<String, String> first = new TreeMap<>();
		String[] kind = new String[1];
		for (int cy = 1; cy < map.h(); cy++) {
			for (int cx = 0; cx < map.w(); cx++) {
				if (!stood(map.kind(cx, cy)) || map.hasTag(cx, cy, CellTag.DOOR)) {
					continue;
				}
				boolean door = map.kind(cx, cy - 1) == CellKind.WALK && map.hasTag(cx, cy - 1, CellTag.DOOR);
				if (!door && stood(map.kind(cx, cy - 1))) {
					continue;
				}
				for (int dx = 0; dx < per; dx++) {
					int lack = lacks(map, open.blocks(), open.modelled(), at, unitCells, cx * per + dx, (door ? cy - 1 : cy) * per - 1, door, screen, kind);
					if (lack > 0) {
						columns++;
						lacking += lack;
						worst = Math.max(worst, lack);
						kinds.merge(kind[0], 1, Integer::sum);
						first.putIfAbsent(kind[0], cx + "," + cy);
					}
				}
			}
		}
		return new Missing(columns, lacking, worst, kinds, first);
	}

	/**
	 * The rows tile column {@code tx} lacks over a floor, its drawing rising from tile row {@code bottom} (the row over the
	 * floor cell, or over the door the floor faces); {@code kind[0]} names what lacks first.
	 */
	static int lacks(SceneMap map, int[] blocks, boolean[] modelled, Structures.MeasuredRun[] at, Set<Integer> unitCells, int tx, int bottom,
			boolean door, int screen, String[] kind) {
		int cols = map.artCols();
		int per = map.perCell();
		// 1. The chain the screen shows: the runs from the front and the rows each must show; the door's own rows stand in
		// the face over it and take their share of the screen.
		int lowest = bottom + (door ? per : 0);
		boolean[] covered = new boolean[lowest + 1];
		List<Structures.MeasuredRun> chain = new ArrayList<>();
		List<int[]> needs = new ArrayList<>();
		int left = screen - (door ? per : 0);
		int ty = bottom;
		while (ty >= 0 && left > 0) {
			if (modelled[ty * cols + tx]) {
				// a model of its own shows its rows: on past them
				int top = ty;
				while (top > 0 && modelled[(top - 1) * cols + tx]) {
					top--;
				}
				cover(covered, top, ty);
				left -= ty - top + 1;
				ty = top - 1;
				continue;
			}
			Structures.MeasuredRun run = at[ty * cols + tx];
			if (run == null || run.f() != ty) {
				break;
			}
			int visible = Math.min(run.length(), left);
			int need = run.separate() ? Math.min(2, visible) : visible;
			boolean front = chain.isEmpty() && ty == bottom;
			needs.add(new int[] {ty - need + 1, front ? lowest : ty, run.models() ? 3 : front && door ? 0 : run.separate() ? 2 : 1, front ? 0 : 1});
			chain.add(run);
			left -= visible;
			ty = run.n() - 1;
		}
		// 2. What the built things show; a unit over the door is the doorway.
		if (door && !chain.isEmpty() && unitCells.contains((bottom / per) * map.w() + tx / per)) {
			cover(covered, bottom + 1, lowest);
		}
		for (Structures.MeasuredRun run : chain) {
			for (int y = run.n(); y <= run.f(); y++) {
				int built = blocks[y * cols + tx];
				if (unitCells.contains((y / per) * map.w() + tx / per) && run.separate()) {
					int south = (y / per) * per + per - 1;
					cover(covered, south - built + 1, south);
				} else if (unitCells.contains((y / per) * map.w() + tx / per)) {
					cover(covered, run.origin() - built + 1, run.origin());
				} else if (built > 0) {
					cover(covered, run.origin() - shown(run, built) + 1, run.origin());
				}
			}
		}
		// 3. The rows needed that nothing shows.
		int lack = 0;
		kind[0] = null;
		for (int[] need : needs) {
			int missing = 0;
			for (int y = Math.max(0, need[0]); y <= need[1]; y++) {
				missing += covered[y] ? 0 : 1;
			}
			if (missing > 0 && kind[0] == null) {
				kind[0] = KINDS[need[2]] + (need[3] == 1 ? "/behind" : "");
			}
			lack += missing;
		}
		return lack;
	}

	private static final String[] KINDS = {"door", "face", "repeat", "model"};

	private static void cover(boolean[] covered, int from, int to) {
		for (int y = Math.max(0, from); y <= Math.min(to, covered.length - 1); y++) {
			covered[y] = true;
		}
	}

	/** The rows a column {@code built} blocks high shows of {@code run}: its blocks, its roof block its roof rows. */
	static int shown(Structures.MeasuredRun run, int built) {
		return built == run.blocks() && run.r() > 0 ? built + run.r() - 1 : built;
	}

	private static boolean stood(CellKind kind) {
		return kind == CellKind.WALK || kind == CellKind.LEDGE || kind == CellKind.WATER;
	}
}
