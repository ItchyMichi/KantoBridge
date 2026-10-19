package dev.kantobridge.builder.shape;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.shape.geom.BoxBuilding;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.scene.profile.Profile;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fitted table's generated buildings in the planner (docs/specs/HAND_MODELS.md section 7): claimed by their exact
 * tile grids as the profile's band buildings are (after them; first claim wins), each placed as a template whose model
 * {@link BoxBuilding} builds on every client from its own drawing and the detected numbers.
 *
 * <pre>
 * volume    per claimed solid or counter column, the model's height in blocks from feet level (BoxBuilding.height,
 *           capped by the map's headroom) on shape_full; a walkable cell of the grid (a doorway) as many on decor, its
 *           floor the building's ground
 * entry     the template's grid, doors and columns as a band building's; its "hull" section the solid and air heights
 *           and the "box" numbers (pixel-free)
 * </pre>
 */
final class FittedBuildings {
	private FittedBuildings() {
	}

	/** The generated buildings' claims on a map (the band buildings' held tiles left alone; held filled in). */
	static List<Buildings.Claim> claims(SceneMap map, boolean[] held, Map<String, Integer> tally, Map<Profile.Building, FittedShapes.Building> of) {
		List<Profile.Building> entries = new ArrayList<>();
		int index = 0;
		for (FittedShapes.Building b : FittedShapes.buildings(map.atlas())) {
			Profile.Building entry = new Profile.Building(index++, b.tiles(), List.of(), false, Set.of(), null);
			of.put(entry, b);
			entries.add(entry);
		}
		if (entries.isEmpty()) {
			return List.of();
		}
		Map<String, Integer> own = new java.util.TreeMap<>();
		List<Buildings.Claim> claims = Buildings.claims(map, entries, held, own);
		tally.merge("fitted_building", own.getOrDefault("building", 0), Integer::sum);
		return claims;
	}

	/** A fresh map from claim entries to their buildings (claims compare entries by identity). */
	static Map<Profile.Building, FittedShapes.Building> index() {
		return new IdentityHashMap<>();
	}

	/** Places one claim into a map's columns and returns its template. */
	static Templates.Placed place(SceneMap map, int atlas, Buildings.Claim claim, FittedShapes.Building building, int mapGround, ShapeKey[][] keys,
			int[] blocks, int[] mask, int[] decor, int[] floor, Map<String, Integer> tally) {
		int cols = map.artCols();
		boolean indoor = !(map.surface() && map.env().outdoor());
		int bw = claim.width();
		int bh = claim.rows();
		int px = 8; // drawing pixels a tile (an art tile is one block, eight voxels)
		JsonObject box = building.box();
		int voxels = BoxBuilding.height(bh * px, box.get("roofRows").getAsInt(), "hip".equals(box.get("roof").getAsString()));
		int high = Math.min(Math.max(2, (voxels + px - 1) / px), Math.min(ShapeHeights.headroom(map), 30));
		int ground = Buildings.ground(map, claim, mapGround);
		JsonArray tiles = new JsonArray();
		JsonArray columns = new JsonArray();
		JsonArray doors = new JsonArray();
		JsonArray solid = new JsonArray();
		JsonArray air = new JsonArray();
		int per = map.perCell();
		for (int y = 0; y < bh; y++) {
			for (int x = 0; x < bw; x++) {
				int tx = claim.tx() + x;
				int ty = claim.ty() + y;
				boolean claimed = claim.claimed()[y * bw + x];
				CellKind kind = map.kindAtTile(tx, ty);
				int s = claimed && (kind == CellKind.SOLID || kind == CellKind.COUNTER) ? high : 0;
				int a = claimed && kind == CellKind.WALK ? high : 0;
				if (a > 0 && x % per == 0 && y % per == 0) {
					JsonArray door = new JsonArray();
					door.add(x / per);
					door.add(y / per);
					doors.add(door);
				}
				solid.add(s);
				air.add(a);
				tiles.add(claimed ? map.tile(tx, ty) : -1);
				columns.add(new JsonArray());
			}
		}
		JsonObject hull = new JsonObject();
		hull.addProperty("kind", "box");
		hull.addProperty("height", voxels);
		hull.add("solid", solid);
		hull.add("air", air);
		hull.add("box", box.deepCopy());
		JsonObject entry = new JsonObject();
		entry.addProperty("template", Templates.FORMAT);
		entry.addProperty("atlas", atlas);
		entry.addProperty("w", bw);
		entry.addProperty("d", bh);
		entry.addProperty("perCell", per);
		entry.add("tiles", tiles);
		entry.add("doors", doors);
		entry.addProperty("ground", ground);
		entry.addProperty("outdoor", !indoor);
		entry.add("columns", columns);
		entry.add("hull", hull);
		String sig = Templates.signature(entry);
		for (int y = 0; y < bh; y++) {
			for (int x = 0; x < bw; x++) {
				int i = (claim.ty() + y) * cols + claim.tx() + x;
				int s = solid.get(y * bw + x).getAsInt();
				int a = air.get(y * bw + x).getAsInt();
				int n = Math.max(s, a);
				if (n == 0) {
					continue;
				}
				ShapeKey[] column = new ShapeKey[n];
				for (int k = 0; k < n; k++) {
					column[k] = ShapeKey.tmpl(atlas, sig, x, k, y);
				}
				keys[i] = column;
				if (s > 0) {
					blocks[i] = s;
					mask[i] = (1 << s) - 1;
				} else {
					decor[i] = (1 << a) - 1;
					floor[i] = ground;
				}
			}
		}
		tally.merge("fitted_building_placed", 1, Integer::sum);
		return new Templates.Placed(sig, claim.tx(), claim.ty(), entry);
	}
}
