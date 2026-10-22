package dev.kantobridge.builder.plan;

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

/**
 * The seams between a region's surface maps and the height each map's floor stands at (docs/specs/LEVELS_PIPELINE.md,
 * "Seams between maps"; the user's choice, 2026-10-21: chain the maps, each starting where its neighbour's ground leaves
 * off). Two maps meet where a cell of one touches a cell of the other across its edge, both walkable ground or both
 * water; a seam's step is the commonest difference of their levels. The seams with the most pairs of cells first (the
 * most seen), each joining two groups of maps not joined yet sets the second map's base so their commonest cells meet; a
 * seam left over closes a loop, and what it doesn't close is the loop's leftover (a slope at the join, later). Each group
 * of joined maps is lifted so its lowest ground stands on the surface. tools/landscape_sections/seams.py is the same
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
		List<PlacedMap> surface = new ArrayList<>();
		for (PlacedMap m : maps) {
			if (m.placement().kind() == MapPlacement.Kind.SURFACE) {
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
				low = Math.min(low, bases.get(m) + lowest(byId.get(m)));
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
				int diff = level(b, cb) - level(a, ca);
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

	private static int level(PlacedMap m, int cell) {
		MapLayout layout = m.layout();
		return layout == null ? 0 : layout.level(cell % m.placement().w(), cell / m.placement().w());
	}

	/** A map's lowest walkable or water level (0 when it has none). */
	private static int lowest(PlacedMap m) {
		int w = m.placement().w();
		int low = Integer.MAX_VALUE;
		for (int cell = 0; cell < w * m.placement().h(); cell++) {
			MapModel.Kind kind = m.model().kind(cell % w, cell / w);
			if (walkable(kind) || kind == MapModel.Kind.WATER) {
				low = Math.min(low, level(m, cell));
			}
		}
		return low == Integer.MAX_VALUE ? 0 : low;
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
