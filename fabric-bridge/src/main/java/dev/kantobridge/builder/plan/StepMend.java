package dev.kantobridge.builder.plan;

import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapLayout;
import dev.kantobridge.world.MapPlacement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The steps inside a map that the game walks and the levels left without a ramp (docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md
 * §14, SC4): a one-cell gap through a face (ground with no walkable cell on either side across the way up, the ground
 * past it a step higher, at most {@link RaisedRegions#OUTDOOR_RISE}, the edge between them open) is the drawing's stair:
 * it becomes a ramp up to the higher ground, at its own level; a stair whose foot is a ledge's hop cell comes down to it
 * and climbs from there. Crystal's levels come from its one-way walls and faces
 * alone (it has no stair table), so its stairs through faces stood flat at their foot's level. Runs on the outdoor maps
 * on raised ground (the surface's and those under the open sky), after the chain. Pure Java.
 */
public final class StepMend {
	private StepMend() {
	}

	/** {@code placed}'s outdoor maps with their gaps ramped, in place; the layouts changed, by map. Notes {@code step_mend_ramps}. */
	public static Map<String, MapLayout> apply(List<PlacedMap> placed, Warnings warnings) {
		Map<String, MapLayout> out = new TreeMap<>();
		int ramps = 0;
		for (int i = 0; i < placed.size(); i++) {
			PlacedMap m = placed.get(i);
			MapLayout layout = m.layout();
			if (layout == null || !(m.placement().kind() == MapPlacement.Kind.SURFACE || WorldPlan.openSky(m.placement()))) {
				continue;
			}
			int w = m.placement().w();
			int h = m.placement().h();
			int[] levels = new int[w * h];
			for (int k = 0; k < levels.length; k++) {
				levels[k] = layout.level(k % w, k / w);
			}
			List<MapLayout.Ramp> list = new ArrayList<>(layout.ramps());
			int before = ramps;
			for (int cy = 0; cy < h; cy++) {
				for (int cx = 0; cx < w; cx++) {
					if (m.model().kind(cx, cy) != MapModel.Kind.GROUND || layout.ramp(cx, cy) != null) {
						continue;
					}
					for (Dir d : Dir.values()) {
						int nx = cx + d.dx();
						int ny = cy + d.dy();
						if (nx < 0 || ny < 0 || nx >= w || ny >= h || m.model().kind(nx, ny) != MapModel.Kind.GROUND || layout.ramp(nx, ny) != null
								|| m.edges().closed(cx, cy, nx, ny)) {
							continue;
						}
						int rise = levels[ny * w + nx] - levels[cy * w + cx];
						if (rise <= 0 || rise > RaisedRegions.OUTDOOR_RISE || walkable(m, cx + d.dy(), cy + d.dx()) || walkable(m, cx - d.dy(), cy - d.dx())) {
							continue;
						}
						list.add(new MapLayout.Ramp(cx, cy, d, rise));
						ramps++;
						break;
					}
				}
			}
			// A stair whose foot is a ledge's hop cell (Crystal's Routes 4, 6, 9: the hop cell stands before the lip, a ledge
			// can't be a ramp): the stair's own cell comes down to the hop cell's level and climbs to the ground past it.
			for (int cy = 0; cy < h; cy++) {
				for (int cx = 0; cx < w; cx++) {
					if (m.model().kind(cx, cy) != MapModel.Kind.LEDGE) {
						continue;
					}
					for (Dir d : Dir.values()) {
						int sx = cx + d.dx();
						int sy = cy + d.dy();
						int bx = sx + d.dx();
						int by = sy + d.dy();
						if (bx < 0 || by < 0 || bx >= w || by >= h || m.model().kind(sx, sy) != MapModel.Kind.GROUND || layout.ramp(sx, sy) != null
								|| m.edges().closed(cx, cy, sx, sy) || !walkable(m, bx, by) || levels[by * w + bx] != levels[sy * w + sx]) {
							continue;
						}
						int rise = levels[sy * w + sx] - levels[cy * w + cx];
						if (rise <= 0 || rise > RaisedRegions.OUTDOOR_RISE || walkable(m, sx + d.dy(), sy + d.dx()) || walkable(m, sx - d.dy(), sy - d.dx())
								|| hasRamp(list, sx, sy)) {
							continue;
						}
						levels[sy * w + sx] = levels[cy * w + cx];
						list.add(new MapLayout.Ramp(sx, sy, d, rise));
						ramps++;
					}
				}
			}
			if (ramps == before) {
				continue;
			}
			MapLayout now = MapLayout.of(w, h, layout.pieces(), levels, list);
			if (layout.hasCeilings()) {
				int[] ceilings = new int[levels.length];
				for (int k = 0; k < ceilings.length; k++) {
					ceilings[k] = layout.ceiling(k % w, k / w);
				}
				now = now.withCeilings(ceilings);
			}
			placed.set(i, new PlacedMap(m.placement(), m.model(), m.edges(), now));
			out.put(m.id(), now);
		}
		warnings.note("step_mend_ramps", ramps);
		return out;
	}

	/**
	 * Crystal's hops in raised rooms (SC7): the cell a Gen 2 ledge hop jumps ({@link WorldPlan#gen2HopDir}) standing over the
	 * ledge cell the hop starts on (a 3D cave's cliff cell, a level up) comes down to that ledge cell's level, so the
	 * jumping body passes its column (FA8 item 12 opened it from the cell's feet up). Every Gen 2 map with a layout; the
	 * layouts changed, by map. Notes {@code hop_mend_cells}.
	 */
	public static Map<String, MapLayout> hops(List<PlacedMap> placed, Warnings warnings) {
		Map<String, MapLayout> out = new TreeMap<>();
		int[][] delta = {{0, 1}, {0, -1}, {-1, 0}, {1, 0}};
		int cells = 0;
		for (int i = 0; i < placed.size(); i++) {
			PlacedMap m = placed.get(i);
			MapLayout layout = m.layout();
			if (layout == null || !m.id().startsWith("G2_")) {
				continue;
			}
			int w = m.placement().w();
			int h = m.placement().h();
			int[] levels = null;
			for (int cy = 0; cy < h; cy++) {
				for (int cx = 0; cx < w; cx++) {
					int dir = WorldPlan.gen2HopDir(m.model(), cx, cy);
					if (dir < 0 || layout.ramp(cx, cy) != null) {
						continue;
					}
					int ledge = layout.level(cx - delta[dir][0], cy - delta[dir][1]);
					if (layout.level(cx, cy) <= ledge) {
						continue;
					}
					if (levels == null) {
						levels = new int[w * h];
						for (int k = 0; k < levels.length; k++) {
							levels[k] = layout.level(k % w, k / w);
						}
					}
					levels[cy * w + cx] = ledge;
					cells++;
				}
			}
			if (levels == null) {
				continue;
			}
			MapLayout now = MapLayout.of(w, h, layout.pieces(), levels, List.copyOf(layout.ramps()));
			if (layout.hasCeilings()) {
				int[] ceilings = new int[levels.length];
				for (int k = 0; k < ceilings.length; k++) {
					ceilings[k] = layout.ceiling(k % w, k / w);
				}
				now = now.withCeilings(ceilings);
			}
			placed.set(i, new PlacedMap(m.placement(), m.model(), m.edges(), now));
			out.put(m.id(), now);
		}
		warnings.note("hop_mend_cells", cells);
		return out;
	}

	private static boolean hasRamp(List<MapLayout.Ramp> ramps, int x, int y) {
		for (MapLayout.Ramp r : ramps) {
			if (r.x() == x && r.y() == y) {
				return true;
			}
		}
		return false;
	}

	private static boolean walkable(PlacedMap m, int x, int y) {
		if (x < 0 || y < 0 || x >= m.placement().w() || y >= m.placement().h()) {
			return false;
		}
		MapModel.Kind kind = m.model().kind(x, y);
		return kind == MapModel.Kind.GROUND || kind == MapModel.Kind.LEDGE;
	}
}
