package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.ExportLayout;
import dev.kantobridge.builder.export.ExportMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;

/**
 * The surface layout (docs/PLAN.md §2.2, §4.5 "Layout"): a breadth-first search from the root ({@code PALLET_TOWN})
 * over the connections, with the engine's neighbour arithmetic (refs/_research/P3_gen1_data_export.md §5.1).
 *
 * <p>For the maps being built it then checks that every directed edge agrees with the computed positions, that the
 * positions match the exported layout, and that no two maps overlap. Positions never depend on the filter: a
 * filtered build puts Pallet Town where a full build would.
 */
public final class Layout {
	/** A surface map's origin in Gen 1 blocks; the root is at (0, 0). */
	public record Origin(int x, int y) {
	}

	/**
	 * @param origins          positions of every map reached, plus exported positions for built maps the search couldn't
	 *                         reach
	 * @param edgeMismatches   directed edges between built maps that disagree with the positions
	 * @param exportMismatches built maps whose position or size differs from the exported layout
	 * @param overlaps         pairs of built maps whose rectangles overlap
	 */
	public record Result(Map<String, Origin> origins, int edgeMismatches, int exportMismatches, int overlaps) {
	}

	private Layout() {
	}

	/**
	 * @param surface  every surface map of the export, by id
	 * @param exported the exported layout (null if the export had none)
	 * @param root     the map at (0, 0)
	 * @param built    the surface maps this build places; the checks cover only these
	 */
	public static Result compute(Map<String, ExportMap> surface, @Nullable ExportLayout exported, String root, Set<String> built,
			Warnings warnings) {
		Map<String, Origin> origins = new LinkedHashMap<>();
		if (surface.containsKey(root)) {
			origins.put(root, new Origin(0, 0));
			Deque<String> queue = new ArrayDeque<>();
			queue.add(root);
			while (!queue.isEmpty()) {
				ExportMap map = surface.get(queue.poll());
				Origin origin = origins.get(map.id());
				for (Map.Entry<ExportMap.Direction, ExportMap.Connection> connection : map.connections().entrySet()) {
					ExportMap neighbour = surface.get(connection.getValue().map());
					if (neighbour == null || origins.containsKey(neighbour.id())) {
						continue;
					}
					origins.put(neighbour.id(), neighbourOrigin(map, origin, connection.getKey(), connection.getValue().offset(), neighbour));
					queue.add(neighbour.id());
				}
			}
		} else {
			warnings.warn("layout_root_missing");
		}

		List<String> checked = new ArrayList<>(new TreeSet<>(built));
		if (exported == null) {
			warnings.warn("layout_export_missing");
		}
		for (String id : checked) {
			if (origins.containsKey(id)) {
				continue;
			}
			ExportLayout.Rect rect = exported != null ? exported.maps().get(id) : null;
			if (rect != null) {
				origins.put(id, new Origin(rect.x(), rect.y()));
				warnings.warn("layout_unreached");
			} else {
				warnings.warn("layout_unplaced");
			}
		}

		int edgeMismatches = 0;
		for (String id : checked) {
			ExportMap map = surface.get(id);
			Origin origin = origins.get(id);
			if (map == null || origin == null) {
				continue;
			}
			for (Map.Entry<ExportMap.Direction, ExportMap.Connection> connection : map.connections().entrySet()) {
				String other = connection.getValue().map();
				ExportMap neighbour = surface.get(other);
				if (neighbour == null) {
					warnings.warn("layout_connection_missing_map");
					continue;
				}
				if (!built.contains(other) || !origins.containsKey(other)) {
					continue;
				}
				Origin expected = neighbourOrigin(map, origin, connection.getKey(), connection.getValue().offset(), neighbour);
				if (!expected.equals(origins.get(other))) {
					edgeMismatches++;
				}
			}
		}
		warnings.warn("layout_edge_mismatch", edgeMismatches);

		int exportMismatches = 0;
		if (exported != null) {
			for (String id : checked) {
				ExportMap map = surface.get(id);
				Origin origin = origins.get(id);
				if (map == null || origin == null) {
					continue;
				}
				ExportLayout.Rect rect = exported.maps().get(id);
				if (rect == null || rect.x() != origin.x() || rect.y() != origin.y() || rect.w() != map.width() || rect.h() != map.height()) {
					exportMismatches++;
				}
			}
			if (!exported.root().equals(root)) {
				warnings.warn("layout_root_differs");
			}
		}
		warnings.warn("layout_export_mismatch", exportMismatches);

		int overlaps = 0;
		for (int i = 0; i < checked.size(); i++) {
			for (int j = i + 1; j < checked.size(); j++) {
				ExportMap a = surface.get(checked.get(i));
				ExportMap b = surface.get(checked.get(j));
				Origin oa = origins.get(checked.get(i));
				Origin ob = origins.get(checked.get(j));
				if (a == null || b == null || oa == null || ob == null) {
					continue;
				}
				if (oa.x() < ob.x() + b.width() && ob.x() < oa.x() + a.width() && oa.y() < ob.y() + b.height() && ob.y() < oa.y() + a.height()) {
					overlaps++;
				}
			}
		}
		warnings.warn("layout_overlap", overlaps);
		return new Result(Collections.unmodifiableMap(origins), edgeMismatches, exportMismatches, overlaps);
	}

	/** Gen1Recomp's placement of a neighbour across one connection (OverworldController neighbour arithmetic). */
	public static Origin neighbourOrigin(ExportMap self, Origin origin, ExportMap.Direction direction, int offset, ExportMap neighbour) {
		return switch (direction) {
			case NORTH -> new Origin(origin.x() + offset, origin.y() - neighbour.height());
			case SOUTH -> new Origin(origin.x() + offset, origin.y() + self.height());
			case WEST -> new Origin(origin.x() - neighbour.width(), origin.y() + offset);
			case EAST -> new Origin(origin.x() + self.width(), origin.y() + offset);
		};
	}
}
