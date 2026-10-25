package dev.kantobridge.builder.plan;

import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapLayout;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/**
 * The seams between a region's surface maps and the height each map's floor stands at (docs/specs/LEVELS_PIPELINE.md,
 * "Seams between maps"; the user's choice, 2026-10-21: chain the maps, each starting where its neighbour's ground leaves
 * off). Two maps meet where a cell of one touches a cell of the other across its edge, both walkable ground or both
 * water; a seam's step is the commonest difference of their levels. The seams with the most pairs of cells first (the
 * most seen), each joining two groups of maps not joined yet sets the second map's base so their commonest cells meet; a
 * seam left over closes a loop, and what it doesn't close is the loop's leftover (a slope at the join, later). Each group
 * of joined maps is lifted so its lowest floor stands on the surface (a map's levels count from its floor, so no ground or
 * floor of it stands under the surface: a review's pins may lift a whole map). tools/landscape_sections/seams.py is the same
 * check over a dump. Pure Java.
 */
public final class MapChain {
	/** The system property that chains the outdoor maps (-Pkb.chainLevels=true); unset, every surface map's floor is the surface. */
	public static final String PROPERTY = "kantobridge.chainLevels";

	private MapChain() {
	}

	/** Whether the system property chains the maps. */
	public static boolean enabled() {
		return Boolean.getBoolean(PROPERTY);
	}

	/**
	 * A seam: maps {@code a} and {@code b} (a before b by name), its pairs of touching cells, its step (b's level minus a's,
	 * the commonest), and how many pairs have it.
	 */
	public record Seam(String a, String b, int pairs, int step, int atStep) {
	}

	/** The bases (blocks each surface map's floor stands over the surface), the seams, and the seams a loop leaves open with their step after the chain. */
	public record Result(Map<String, Integer> bases, List<Seam> seams, Map<Seam, Integer> open) {
	}

	/** The chain of {@code maps}' surface maps. */
	public static Result chain(List<PlacedMap> maps) {
		return chain(maps, MapChain::surface);
	}

	/** The chain of the maps {@code which} takes (the surface's, or the open-air maps under the sky: §14 SC3). */
	public static Result chain(List<PlacedMap> maps, Predicate<PlacedMap> which) {
		List<PlacedMap> surface = new ArrayList<>();
		for (PlacedMap m : maps) {
			if (which.test(m)) {
				surface.add(m);
			}
		}
		Map<String, Seam> seams = seams(surface);
		// the spanning forest, the seams with the most pairs first
		List<Seam> order = new ArrayList<>(seams.values());
		order.sort(Comparator.comparingInt((Seam s) -> -s.pairs()).thenComparing(Seam::a).thenComparing(Seam::b));
		Map<String, String> parent = new HashMap<>();
		for (PlacedMap m : surface) {
			parent.put(m.id(), m.id());
		}
		List<Seam> tree = new ArrayList<>();
		for (Seam s : order) {
			String ra = root(parent, s.a());
			String rb = root(parent, s.b());
			if (!ra.equals(rb)) {
				parent.put(rb, ra);
				tree.add(s);
			}
		}
		Map<String, List<String[]>> links = new HashMap<>();
		for (Seam s : tree) {
			// a's ground meets b's: b's base = a's base - the step
			links.computeIfAbsent(s.a(), k -> new ArrayList<>()).add(new String[] {s.b(), Integer.toString(-s.step())});
			links.computeIfAbsent(s.b(), k -> new ArrayList<>()).add(new String[] {s.a(), Integer.toString(s.step())});
		}
		Map<String, Integer> bases = new TreeMap<>();
		Map<String, PlacedMap> byId = new HashMap<>();
		for (PlacedMap m : surface) {
			byId.put(m.id(), m);
		}
		List<PlacedMap> starts = new ArrayList<>(surface);
		starts.sort(Comparator.comparingInt((PlacedMap m) -> -m.placement().w() * m.placement().h()).thenComparing(PlacedMap::id));
		for (PlacedMap start : starts) {
			if (bases.containsKey(start.id())) {
				continue;
			}
			List<String> group = new ArrayList<>(List.of(start.id()));
			bases.put(start.id(), 0);
			ArrayDeque<String> todo = new ArrayDeque<>(group);
			while (!todo.isEmpty()) {
				String m = todo.poll();
				for (String[] link : links.getOrDefault(m, List.of())) {
					if (!bases.containsKey(link[0])) {
						bases.put(link[0], bases.get(m) + Integer.parseInt(link[1]));
						group.add(link[0]);
						todo.add(link[0]);
					}
				}
			}
			int low = Integer.MAX_VALUE;
			for (String m : group) {
				low = Math.min(low, bases.get(m));
			}
			for (String m : group) {
				bases.put(m, bases.get(m) - low);
			}
		}
		Set<Seam> inTree = new HashSet<>(tree);
		Map<Seam, Integer> open = new TreeMap<>(Comparator.comparing(Seam::a).thenComparing(Seam::b));
		for (Seam s : seams.values()) {
			int left = s.step() + bases.get(s.b()) - bases.get(s.a());
			if (!inTree.contains(s) && left != 0) {
				open.put(s, left);
			}
		}
		return new Result(bases, List.copyOf(seams.values()), open);
	}

	/**
	 * {@code chain}'s account in the build's notes: the maps lifted, the highest base, the loops left open (each with its
	 * seam and step: where a slope at the join goes, later). Returns {@code chain}.
	 */
	public static Result note(Result chain, Warnings warnings) {
		int lifted = 0;
		int top = 0;
		for (int base : chain.bases().values()) {
			lifted += base > 0 ? 1 : 0;
			top = Math.max(top, base);
		}
		warnings.note("chain_maps_lifted", lifted);
		warnings.note("chain_highest_base", top);
		warnings.note("chain_seams", chain.seams().size());
		for (Map.Entry<Seam, Integer> e : chain.open().entrySet()) {
			warnings.note("chain_loops_open");
			warnings.detail("chain_loop_open", Warnings.symbols("a", e.getKey().a(), "b", e.getKey().b(), "step", Integer.toString(e.getValue())));
		}
		return chain;
	}

	/**
	 * {@code placed} with each surface map raised to its base ({@link Result#bases}), in place; the placements that changed,
	 * by map.
	 */
	public static Map<String, MapPlacement> apply(List<PlacedMap> placed, Map<String, Integer> bases) {
		Map<String, MapPlacement> moved = new TreeMap<>();
		placed.replaceAll(m -> {
			Integer base = bases.get(m.id());
			if (base == null || base == 0 || m.placement().kind() != MapPlacement.Kind.SURFACE) {
				return m;
			}
			MapPlacement p = m.placement().withY(m.placement().y() + base);
			moved.put(m.id(), p);
			return new PlacedMap(p, m.model(), m.edges(), m.layout());
		});
		return moved;
	}

	/**
	 * {@code placed}'s maps raised by {@code bases} in their levels, their placements kept (the open-air maps under the sky,
	 * §14 SC3: an interior's floor stays on its layer, and its raised ground is drawn from it); the layouts that changed,
	 * by map. A map raised past {@link MapLayout#MAX_LEVEL} keeps its levels (noted {@code chain_sky_too_high}).
	 */
	public static Map<String, MapLayout> raise(List<PlacedMap> placed, Map<String, Integer> bases, Warnings warnings) {
		Map<String, MapLayout> out = new TreeMap<>();
		for (int i = 0; i < placed.size(); i++) {
			PlacedMap m = placed.get(i);
			int base = bases.getOrDefault(m.id(), 0);
			if (base == 0) {
				continue;
			}
			int w = m.placement().w();
			int h = m.placement().h();
			MapLayout layout = m.layout();
			int[] levels = new int[w * h];
			boolean fits = true;
			for (int k = 0; k < levels.length; k++) {
				levels[k] = m.level(k % w, k / w) + base;
				fits &= levels[k] <= MapLayout.MAX_LEVEL;
			}
			if (!fits) {
				warnings.note("chain_sky_too_high", Warnings.symbols("map", m.id()));
				continue;
			}
			MapLayout now = layout == null ? MapLayout.flat(w, h, levels, List.of())
					: MapLayout.of(w, h, layout.pieces(), levels, List.copyOf(layout.ramps()));
			if (layout != null && layout.hasCeilings()) {
				int[] ceilings = new int[levels.length];
				for (int k = 0; k < ceilings.length; k++) {
					ceilings[k] = layout.ceiling(k % w, k / w) + base;
				}
				now = now.withCeilings(ceilings);
			}
			placed.set(i, new PlacedMap(m.placement(), m.model(), m.edges(), now));
			out.put(m.id(), now);
		}
		return out;
	}

	private static boolean surface(PlacedMap m) {
		return m.placement().kind() == MapPlacement.Kind.SURFACE;
	}

	/** The placements and layouts the joins' stairs changed, by map. */
	public record Joins(Map<String, MapPlacement> placements, Map<String, MapLayout> layouts) {
	}

	/**
	 * A short stair at each open loop's join (the user's choice, 2026-10-22: a short slope where the loop is left open, not a
	 * tilted map; LEVELS_PIPELINE.md, the seams): the straight corridor each of the seam's two maps runs up to it (level
	 * ground, rock or the map's edge beside it all the way) climbs from the lower map's ground to the higher's,
	 * {@link RaisedRegions#OUTDOOR_RISE} blocks a cell (the last step the rest), in the lower map's corridor first, out to
	 * the seam; the higher map's takes what is left, climbing in from the seam (that map on a lower floor for them where its
	 * own would stand over them: its placement that much lower, its levels that much higher, as far as its base allows). A
	 * loop whose corridors are too short, or with no corridor, stays open (noted). {@code placed} takes the new placements
	 * and layouts, in place.
	 */
	public static Joins joins(List<PlacedMap> placed, Result chain, Warnings warnings) {
		Map<String, MapPlacement> moved = new TreeMap<>();
		Map<String, MapLayout> layouts = new TreeMap<>();
		Map<String, Integer> at = new HashMap<>();
		for (int i = 0; i < placed.size(); i++) {
			at.put(placed.get(i).id(), i);
		}
		// each map's floor over the surface as the joins leave it (a join may set a map on a lower floor)
		Map<String, Integer> floors = new HashMap<>(chain.bases());
		for (Map.Entry<Seam, Integer> loop : chain.open().entrySet()) {
			Seam s = loop.getKey();
			Join join = join(placed.get(at.get(s.a())), placed.get(at.get(s.b())), floors);
			warnings.note("chain_join_" + join.outcome());
			warnings.detail("chain_join", Warnings.symbols("a", s.a(), "b", s.b(), "step", Integer.toString(loop.getValue()), "result",
					join.outcome()));
			for (PlacedMap now : join.changed()) {
				PlacedMap was = placed.set(at.get(now.id()), now);
				layouts.put(now.id(), now.layout());
				if (now.placement().y() != was.placement().y()) {
					moved.put(now.id(), now.placement());
					floors.merge(now.id(), now.placement().y() - was.placement().y(), Integer::sum);
				}
			}
		}
		return new Joins(moved, layouts);
	}

	/**
	 * The ragged seams mended (docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md §14, SC2): after the chain and the joins, each pair
	 * of touching walkable cells of two surface maps that still stand apart, its edge open on both sides, climbs from the
	 * lower cell to the higher: the lower map's cell becomes a ramp up to the seam, {@link RaisedRegions#OUTDOOR_RISE} blocks
	 * a cell, and a difference over one cell's rise takes the cells behind it (ground at the same level, no ramp), the
	 * deepest first, as a join's corridor does. What can't be ramped stays as it was (noted {@code seam_mend_unmet}).
	 * {@code placed} takes the new layouts, in place; returns them by map.
	 */
	public static Map<String, MapLayout> mend(List<PlacedMap> placed, Warnings warnings) {
		return mend(placed, MapChain::surface, warnings);
	}

	/** The same over the maps {@code which} takes. */
	public static Map<String, MapLayout> mend(List<PlacedMap> placed, Predicate<PlacedMap> which, Warnings warnings) {
		List<PlacedMap> surface = new ArrayList<>();
		Map<String, Integer> at = new HashMap<>();
		for (int i = 0; i < placed.size(); i++) {
			if (which.test(placed.get(i))) {
				surface.add(placed.get(i));
				at.put(placed.get(i).id(), i);
			}
		}
		// per lower map: {cell, out dx, out dy, rise}
		Map<String, List<int[]>> asks = new TreeMap<>();
		int c = Scale.CELL_BLOCKS;
		for (Seam s : seams(surface).values()) {
			PlacedMap a = placed.get(at.get(s.a()));
			PlacedMap b = placed.get(at.get(s.b()));
			for (int[] p : touching(a, b)) {
				int ca = p[0];
				int cb = p[1];
				int ax = ca % a.placement().w();
				int ay = ca / a.placement().w();
				int bx = cb % b.placement().w();
				int by = cb / b.placement().w();
				int dx = Integer.signum(b.placement().x() + bx * c - (a.placement().x() + ax * c));
				int dz = Integer.signum(b.placement().z() + by * c - (a.placement().z() + ay * c));
				if (a.edges().closed(ax, ay, ax + dx, ay + dz) || b.edges().closed(bx, by, bx - dx, by - dz)) {
					continue;
				}
				int d = b.placement().y() + edgeLevel(b, cb, -dx, -dz) - (a.placement().y() + edgeLevel(a, ca, dx, dz));
				if (d > 0) {
					asks.computeIfAbsent(a.id(), k -> new ArrayList<>()).add(new int[] {ca, dx, dz, d});
				} else if (d < 0) {
					asks.computeIfAbsent(b.id(), k -> new ArrayList<>()).add(new int[] {cb, -dx, -dz, -d});
				}
			}
		}
		Map<String, MapLayout> layouts = new TreeMap<>();
		int ramps = 0;
		int unmet = 0;
		for (Map.Entry<String, List<int[]>> e : asks.entrySet()) {
			int i = at.get(e.getKey());
			PlacedMap m = placed.get(i);
			int w = m.placement().w();
			int[] levels = levels(m);
			MapLayout layout = m.layout();
			List<MapLayout.Ramp> rampList = new ArrayList<>(layout == null ? List.of() : layout.ramps());
			Set<Integer> taken = new HashSet<>();
			int before = ramps;
			for (int[] ask : e.getValue()) {
				Dir out = ask[1] > 0 ? Dir.RIGHT : ask[1] < 0 ? Dir.LEFT : ask[2] > 0 ? Dir.DOWN : Dir.UP;
				List<Integer> rises = new ArrayList<>();
				for (int left = ask[3]; left > 0; left -= RaisedRegions.OUTDOOR_RISE) {
					rises.add(Math.min(RaisedRegions.OUTDOOR_RISE, left));
				}
				// the cells from the seam inward, all ground at the edge cell's level with no ramp
				int x0 = ask[0] % w;
				int y0 = ask[0] / w;
				int foot = levels[ask[0]];
				List<Integer> cells = new ArrayList<>();
				for (int k = 0; k < rises.size(); k++) {
					int x = x0 - out.dx() * k;
					int y = y0 - out.dy() * k;
					int cell = y * w + x;
					if (!ground(m, x, y) || levels[cell] != foot || taken.contains(cell) || layout != null && layout.ramp(x, y) != null) {
						break;
					}
					cells.add(cell);
				}
				int top = foot;
				for (int r : rises) {
					top += r;
				}
				if (cells.size() < rises.size() || top > MapLayout.MAX_LEVEL) {
					unmet++;
					continue;
				}
				// the deepest cell climbs first, from the ground's level; the edge cell's ramp ends at the seam
				int level = foot;
				for (int k = rises.size() - 1; k >= 0; k--) {
					int cell = cells.get(k);
					levels[cell] = level;
					rampList.add(new MapLayout.Ramp(cell % w, cell / w, out, rises.get(rises.size() - 1 - k)));
					level += rises.get(rises.size() - 1 - k);
					taken.add(cell);
					ramps++;
				}
			}
			if (ramps == before) {
				continue;
			}
			MapLayout now = layout == null ? MapLayout.flat(w, m.placement().h(), levels, rampList)
					: MapLayout.of(w, m.placement().h(), layout.pieces(), levels, rampList);
			if (layout != null && layout.hasCeilings()) {
				int[] ceilings = new int[levels.length];
				for (int k = 0; k < ceilings.length; k++) {
					ceilings[k] = layout.ceiling(k % w, k / w);
				}
				now = now.withCeilings(ceilings);
			}
			placed.set(i, new PlacedMap(m.placement(), m.model(), m.edges(), now));
			layouts.put(m.id(), now);
		}
		warnings.note("seam_mend_ramps", ramps);
		warnings.note("seam_mend_unmet", unmet);
		return layouts;
	}

	/**
	 * A join's outcome ("stair", else why not: "no_corridor", "level", "too_short", "under_the_surface", "too_high") and the
	 * maps it changed.
	 */
	record Join(String outcome, List<PlacedMap> changed) {
		static Join not(String why) {
			return new Join(why, List.of());
		}
	}

	/** The stair at the join of open seam a | b, {@code floors} each map's floor over the surface. */
	static Join join(PlacedMap a, PlacedMap b, Map<String, Integer> floors) {
		List<int[]> pairs = touching(a, b);
		if (pairs.isEmpty()) {
			return Join.not("no_corridor");
		}
		int[] first = pairs.get(0);
		Set<Integer> seamA = new HashSet<>();
		Set<Integer> seamB = new HashSet<>();
		for (int[] p : pairs) {
			if (p[2] != first[2] || p[3] != first[3]) {
				return Join.not("no_corridor");
			}
			seamA.add(p[0]);
			seamB.add(p[1]);
		}
		// a's way out to b
		Dir out = first[2] > 0 ? Dir.RIGHT : first[2] < 0 ? Dir.LEFT : first[3] > 0 ? Dir.DOWN : Dir.UP;
		Corridor ca = corridor(a, seamA, out.opposite());
		Corridor cb = corridor(b, seamB, out);
		if (ca == null || cb == null) {
			return Join.not("no_corridor");
		}
		int wa = a.placement().y() + ca.level();
		int wb = b.placement().y() + cb.level();
		boolean aLow = wa < wb;
		PlacedMap low = aLow ? a : b;
		PlacedMap high = aLow ? b : a;
		Corridor cl = aLow ? ca : cb;
		Corridor ch = aLow ? cb : ca;
		int climb = Math.abs(wb - wa);
		if (climb == 0) {
			return Join.not("level");
		}
		List<Integer> rises = new ArrayList<>();
		for (int left = climb; left > 0; left -= RaisedRegions.OUTDOOR_RISE) {
			rises.add(Math.min(RaisedRegions.OUTDOOR_RISE, left));
		}
		if (rises.size() > cl.rows().size() + ch.rows().size()) {
			return Join.not("too_short");
		}
		// the lower map's corridor climbs out to the seam, its deepest step's foot on its own ground
		int onLow = Math.min(cl.rows().size(), rises.size());
		int[] lowLevels = levels(low);
		List<MapLayout.Ramp> lowRamps = new ArrayList<>(low.layout() == null ? List.of() : low.layout().ramps());
		int foot = cl.level();
		if (foot + rises.subList(0, onLow).stream().mapToInt(Integer::intValue).sum() > MapLayout.MAX_LEVEL) {
			return Join.not("too_high");
		}
		for (int k = onLow - 1; k >= 0; k--) {
			int rise = rises.get(k);
			for (int c : cl.rows().get(k)) {
				lowLevels[c] = foot;
				lowRamps.add(new MapLayout.Ramp(c % low.placement().w(), c / low.placement().w(), cl.out(), rise));
			}
			foot += rise;
		}
		PlacedMap lowNow = new PlacedMap(low.placement(), low.model(), low.edges(),
				MapLayout.flat(low.placement().w(), low.placement().h(), lowLevels, lowRamps));
		int rest = climb - (foot - cl.level());
		if (rest == 0) {
			return new Join("stair", List.of(lowNow));
		}
		// the higher map's corridor climbs in from the seam, from the lower map's top; under its own floor, a lower floor
		int under = Math.max(0, rest - ch.level());
		if (under > floors.getOrDefault(high.id(), 0)) {
			return Join.not("under_the_surface");
		}
		int[] highLevels = levels(high);
		for (int i = 0; i < highLevels.length; i++) {
			highLevels[i] += under;
			if (highLevels[i] > MapLayout.MAX_LEVEL) {
				return Join.not("too_high");
			}
		}
		List<MapLayout.Ramp> highRamps = new ArrayList<>(high.layout() == null ? List.of() : high.layout().ramps());
		int step = ch.level() + under - rest;
		for (int k = 0; k < rises.size() - onLow; k++) {
			int rise = rises.get(onLow + k);
			for (int c : ch.rows().get(k)) {
				highLevels[c] = step;
				highRamps.add(new MapLayout.Ramp(c % high.placement().w(), c / high.placement().w(), ch.out().opposite(), rise));
			}
			step += rise;
		}
		PlacedMap highNow = new PlacedMap(high.placement().withY(high.placement().y() - under), high.model(), high.edges(),
				MapLayout.flat(high.placement().w(), high.placement().h(), highLevels, highRamps));
		return new Join("stair", List.of(lowNow, highNow));
	}

	/**
	 * A map's straight corridor up to its seam cells: {@code rows} its cross-sections from the seam inward (each the run of
	 * ground cells along the edge holding the seam cells, as wide as the first), all at one {@code level}, no ramp, rock or
	 * the map's edge beside each; {@code out} the way out to the seam.
	 */
	record Corridor(List<List<Integer>> rows, int level, Dir out) {
	}

	static @Nullable Corridor corridor(PlacedMap m, Set<Integer> seam, Dir in) {
		int w = m.placement().w();
		MapLayout layout = m.layout();
		if (layout != null && layout.pieces().size() > 1) {
			return null;
		}
		Dir side = in == Dir.UP || in == Dir.DOWN ? Dir.RIGHT : Dir.DOWN;
		// the seam's run along the edge: from its first cell back, then on while ground
		int start = seam.stream().min(Integer::compare).orElseThrow();
		int x0 = start % w;
		int y0 = start / w;
		while (ground(m, x0 - side.dx(), y0 - side.dy())) {
			x0 -= side.dx();
			y0 -= side.dy();
		}
		int width = 0;
		while (ground(m, x0 + side.dx() * width, y0 + side.dy() * width)) {
			width++;
		}
		for (int c : seam) {
			int along = side == Dir.RIGHT ? c % w - x0 : c / w - y0;
			if (along < 0 || along >= width) {
				return null;
			}
		}
		int level = m.level(x0, y0);
		List<List<Integer>> rows = new ArrayList<>();
		for (int k = 0; ; k++) {
			int rx = x0 + in.dx() * k;
			int ry = y0 + in.dy() * k;
			boolean ok = walled(m, rx - side.dx(), ry - side.dy()) && walled(m, rx + side.dx() * width, ry + side.dy() * width);
			List<Integer> row = new ArrayList<>();
			for (int j = 0; ok && j < width; j++) {
				int x = rx + side.dx() * j;
				int y = ry + side.dy() * j;
				ok = ground(m, x, y) && m.level(x, y) == level && (layout == null || layout.ramp(x, y) == null);
				row.add(y * w + x);
			}
			if (!ok) {
				break;
			}
			rows.add(row);
		}
		return rows.isEmpty() ? null : new Corridor(rows, level, in.opposite());
	}

	/** Ground (no ledge, no water) inside the map. */
	private static boolean ground(PlacedMap m, int x, int y) {
		return x >= 0 && y >= 0 && x < m.placement().w() && y < m.placement().h() && m.model().kind(x, y) == MapModel.Kind.GROUND;
	}

	/** Beside a corridor: rock, or off the map. */
	private static boolean walled(PlacedMap m, int x, int y) {
		if (x < 0 || y < 0 || x >= m.placement().w() || y >= m.placement().h()) {
			return true;
		}
		MapModel.Kind kind = m.model().kind(x, y);
		return !walkable(kind) && kind != MapModel.Kind.WATER;
	}

	private static int[] levels(PlacedMap m) {
		int w = m.placement().w();
		int[] out = new int[w * m.placement().h()];
		for (int i = 0; i < out.length; i++) {
			out[i] = m.level(i % w, i / w);
		}
		return out;
	}

	/** The pairs of touching walkable cells between {@code a} and {@code b}: {a's cell, b's cell, dx, dy} (a's to b's). */
	static List<int[]> touching(PlacedMap a, PlacedMap b) {
		MapPlacement p = a.placement();
		MapPlacement q = b.placement();
		int c = Scale.CELL_BLOCKS;
		Set<Long> seen = new HashSet<>();
		List<int[]> out = new ArrayList<>();
		for (int cy = 0; cy < p.h(); cy++) {
			for (int cx = 0; cx < p.w(); cx++) {
				if (!walkable(a.model().kind(cx, cy))) {
					continue;
				}
				for (Dir d : Dir.values()) {
					int nx = cx + d.dx();
					int ny = cy + d.dy();
					if (nx >= 0 && ny >= 0 && nx < p.w() && ny < p.h()) {
						continue;
					}
					// the block columns just past this cell's edge, the way d goes
					for (int k = 0; k < c; k++) {
						int bx = p.x() + cx * c + (d.dx() > 0 ? c : d.dx() < 0 ? -1 : k);
						int bz = p.z() + cy * c + (d.dy() > 0 ? c : d.dy() < 0 ? -1 : k);
						if (!q.containsColumn(bx, bz)) {
							continue;
						}
						int qx = (bx - q.x()) / c;
						int qy = (bz - q.z()) / c;
						if (walkable(b.model().kind(qx, qy)) && seen.add(((long) (cy * p.w() + cx) << 32) | (qy * (long) q.w() + qx))) {
							out.add(new int[] {cy * p.w() + cx, qy * q.w() + qx, d.dx(), d.dy()});
						}
					}
				}
			}
		}
		return out;
	}

	/** Every seam between two of {@code surface}'s maps, by "a|b". */
	static Map<String, Seam> seams(List<PlacedMap> surface) {
		// 64-block squares -> the maps over them
		Map<Long, List<Integer>> index = new HashMap<>();
		for (int i = 0; i < surface.size(); i++) {
			MapPlacement p = surface.get(i).placement();
			for (int qx = Math.floorDiv(p.x(), 64); qx <= Math.floorDiv(p.x() + p.blockWidth() - 1, 64); qx++) {
				for (int qz = Math.floorDiv(p.z(), 64); qz <= Math.floorDiv(p.z() + p.blockDepth() - 1, 64); qz++) {
					index.computeIfAbsent(square(qx, qz), k -> new ArrayList<>()).add(i);
				}
			}
		}
		// the pairs of touching cells, by the two maps (the first's index the lower)
		Map<Long, Set<Long>> pairs = new HashMap<>();
		int c = Scale.CELL_BLOCKS;
		for (int i = 0; i < surface.size(); i++) {
			MapPlacement p = surface.get(i).placement();
			int w = p.w();
			int h = p.h();
			List<int[]> edges = new ArrayList<>();
			for (int cy = 0; cy < h; cy++) {
				edges.add(new int[] {w - 1, cy, 1, 0});
				edges.add(new int[] {0, cy, -1, 0});
			}
			for (int cx = 0; cx < w; cx++) {
				edges.add(new int[] {cx, h - 1, 0, 1});
				edges.add(new int[] {cx, 0, 0, -1});
			}
			for (int[] e : edges) {
				for (int k = 0; k < c; k++) {
					int bx = p.x() + e[0] * c + (e[2] > 0 ? c : e[2] < 0 ? -1 : k);
					int bz = p.z() + e[1] * c + (e[3] > 0 ? c : e[3] < 0 ? -1 : k);
					for (int j : index.getOrDefault(square(Math.floorDiv(bx, 64), Math.floorDiv(bz, 64)), List.of())) {
						MapPlacement q = surface.get(j).placement();
						if (j <= i || !q.containsColumn(bx, bz)) {
							continue;
						}
						int qx = (bx - q.x()) / c;
						int qy = (bz - q.z()) / c;
						long cells = ((long) (e[1] * w + e[0]) << 32) | (qy * (long) q.w() + qx);
						pairs.computeIfAbsent(((long) i << 32) | j, key -> new HashSet<>()).add(cells);
					}
				}
			}
		}
		Map<String, Seam> out = new TreeMap<>();
		for (Map.Entry<Long, Set<Long>> e : pairs.entrySet()) {
			PlacedMap a = surface.get((int) (e.getKey() >> 32));
			PlacedMap b = surface.get((int) (long) e.getKey());
			if (a.id().compareTo(b.id()) > 0) {
				PlacedMap t = a;
				a = b;
				b = t;
			}
			boolean swapped = a != surface.get((int) (e.getKey() >> 32));
			Map<Integer, Integer> diffs = new TreeMap<>();
			int n = 0;
			for (long cells : e.getValue()) {
				int ca = (int) (cells >> 32);
				int cb = (int) cells;
				if (swapped) {
					int t = ca;
					ca = cb;
					cb = t;
				}
				MapModel.Kind ka = a.model().kind(ca % a.placement().w(), ca / a.placement().w());
				MapModel.Kind kb = b.model().kind(cb % b.placement().w(), cb / b.placement().w());
				boolean walk = walkable(ka) && walkable(kb);
				boolean water = ka == MapModel.Kind.WATER && kb == MapModel.Kind.WATER;
				if (!walk && !water) {
					continue;
				}
				// the way from a's cell to b's: a step climbing it counts at its top (a join's stair)
				int dx = Integer.signum((b.placement().x() + cb % b.placement().w() * c) - (a.placement().x() + ca % a.placement().w() * c));
				int dz = Integer.signum((b.placement().z() + cb / b.placement().w() * c) - (a.placement().z() + ca / a.placement().w() * c));
				int diff = edgeLevel(b, cb, -dx, -dz) - edgeLevel(a, ca, dx, dz);
				diffs.merge(diff, 1, Integer::sum);
				n++;
			}
			if (n == 0) {
				continue;
			}
			int step = 0;
			int best = -1;
			for (Map.Entry<Integer, Integer> d : diffs.entrySet()) {
				// the commonest; on a tie the nearest to level, then the lower
				if (d.getValue() > best || d.getValue() == best && Math.abs(d.getKey()) < Math.abs(step)) {
					step = d.getKey();
					best = d.getValue();
				}
			}
			out.put(a.id() + "|" + b.id(), new Seam(a.id(), b.id(), n, step, best));
		}
		return out;
	}

	private static boolean walkable(MapModel.Kind kind) {
		return kind == MapModel.Kind.GROUND || kind == MapModel.Kind.LEDGE;
	}

	/** A cell's level at its edge the way (dx, dz) goes: a step's top where it climbs that way. */
	private static int edgeLevel(PlacedMap m, int cell, int dx, int dz) {
		MapLayout layout = m.layout();
		if (layout == null) {
			return 0;
		}
		int x = cell % m.placement().w();
		int y = cell / m.placement().w();
		MapLayout.Ramp ramp = layout.ramp(x, y);
		return layout.level(x, y) + (ramp != null && ramp.dir().dx() == dx && ramp.dir().dy() == dz ? ramp.rise() : 0);
	}

	private static long square(int qx, int qz) {
		return ((long) qx << 32) ^ (qz & 0xffffffffL);
	}

	private static String root(Map<String, String> parent, String m) {
		while (!parent.get(m).equals(m)) {
			parent.put(m, parent.get(parent.get(m)));
			m = parent.get(m);
		}
		return m;
	}
}
