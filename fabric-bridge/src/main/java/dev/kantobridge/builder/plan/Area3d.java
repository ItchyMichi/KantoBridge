package dev.kantobridge.builder.plan;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which interiors of a build go to the 3D area (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D1): the caves, every interior a
 * ladder or a hole joins to another (towers, gyms, wells: their floors stack so the ladders and holes can be real), and
 * every interior whose door is in one of those (HD1), the closure of all three. Outdoor maps keep the sky and never go
 * (a surface map with a ladder down, Azalea Town's well, stays the entrance). Pure Java.
 */
final class Area3d {
	private Area3d() {
	}

	/**
	 * @param interiors the interiors that may go, by id, in the order the area takes them
	 * @param caves     the game's caves among them
	 * @param outdoor   the outdoor maps among them (they never go)
	 */
	static Set<String> select(Map<String, CaveMap> interiors, Collection<String> caves, Set<String> outdoor) {
		Set<String> in = new LinkedHashSet<>();
		for (String id : interiors.keySet()) {
			if (caves.contains(id) && !outdoor.contains(id)) {
				in.add(id);
			}
		}
		boolean grew = true;
		while (grew) {
			grew = false;
			for (CaveMap map : interiors.values()) {
				String id = map.id();
				if (outdoor.contains(id)) {
					continue;
				}
				for (CaveMap.Warp warp : map.warps()) {
					String to = warp.to();
					if (to == null || to.equals(id) || !interiors.containsKey(to) || outdoor.contains(to)) {
						continue;
					}
					if (warp.kind() == CaveMap.Kind.LADDER || warp.kind() == CaveMap.Kind.HOLE) {
						// a ladder or a hole between two interiors: both stack
						grew |= in.add(id);
						grew |= in.add(to);
					}
				}
				if (!in.contains(id) && map.parent() != null && in.contains(map.parent())) {
					// its door is in the area (a room off a cave)
					in.add(id);
					grew = true;
				}
			}
		}
		// in the interiors' order
		Set<String> out = new LinkedHashSet<>();
		for (String id : interiors.keySet()) {
			if (in.contains(id)) {
				out.add(id);
			}
		}
		return out;
	}
}
