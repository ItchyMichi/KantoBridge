package dev.kantobridge.builder.shape;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.scene.SwapSite;
import dev.kantobridge.scene.profile.Profile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The profile's band-table buildings in the planner (docs/specs/PROFILE_PLAN.md §5.5, from P2): claimed by their exact
 * tile grids before Tier A, first claim wins (entry, then row, then column); refused where another claim or a swap site
 * already holds a tile. Pixel-free: tile ids, cell kinds and the entries' numbers. Each claim becomes a template
 * ({@code tmpl} keys) whose entry carries the band table, the composite grid, the doorways and the overhang rows, so
 * every client builds the same model from its own art.
 *
 * <pre>
 * volume    per claimed column from feet level: solid and counter cells max(2, bound) blocks on shape_full, walkable
 *           cells (doorways) bound blocks on decor; bound = ceil((8 × drawn rows − roofRows + slab) / 8), a furniture
 *           entry's (P3) its pieces' highest layer; capped by the map's headroom (ShapeHeights)
 * overhang  one row north (an awning) and south (an awning or a front eave) of the footprint, on decor, bound blocks,
 *           where that row is walkable ground inside the map; elsewhere cut (counted)
 * claimOnly the claim keeps two blocks of collision on shape_full and draws nothing; its floor wears the ground
 * ground    the most frequent walkable tile along the grid's four edges (not the corners), else the map's
 * </pre>
 */
final class Buildings {
	/** The entry format of a band building in the shape index ({@code band.format}). */
	static final int FORMAT = 1;

	/**
	 * A claim: entry {@code building} with its grid's north-west tile at (tx, ty).
	 *
	 * @param claimed per grid tile (row-major, the grid's width), whether the claim holds it ({@code keep} ids don't)
	 */
	record Claim(Profile.Building building, int tx, int ty, boolean[] claimed) {
		int width() {
			return this.building.width();
		}

		int rows() {
			return this.building.tiles().size();
		}
	}

	private Buildings() {
	}

	/**
	 * The claims on a map: every entry in list order tries every tile offset of the map body; a placement matches when
	 * each row's ids equal the map's (a row compares as many ids as it has), and none of its tiles is claimed or part of
	 * a swap site.
	 *
	 * @param held per art tile, set for the tiles the returned claims hold (filled in)
	 */
	static List<Claim> claims(SceneMap map, List<Profile.Building> buildings, boolean[] held, Map<String, Integer> tally) {
		List<Claim> out = new ArrayList<>();
		int cols = map.artCols();
		int rows = map.artRows();
		boolean[] swap = new boolean[cols * rows];
		int per = map.perCell();
		for (SwapSite site : map.swapSites()) {
			for (SwapSite.SwapCell cell : site.cells()) {
				for (int dy = 0; dy < per; dy++) {
					for (int dx = 0; dx < per; dx++) {
						swap[(cell.y() * per + dy) * cols + cell.x() * per + dx] = true;
					}
				}
			}
		}
		for (Profile.Building building : buildings) {
			int bw = building.width();
			int bh = building.tiles().size();
			for (int ty = 0; ty + bh <= rows; ty++) {
				for (int tx = 0; tx + bw <= cols; tx++) {
					if (!matches(map, building, tx, ty)) {
						continue;
					}
					boolean[] claimed = new boolean[bw * bh];
					boolean free = true;
					for (int y = 0; y < bh && free; y++) {
						for (int x = 0; x < bw; x++) {
							int i = (ty + y) * cols + tx + x;
							if (held[i]) {
								free = false;
								break;
							}
							if (swap[i]) {
								free = false;
								tally.merge("building_swap_site", 1, Integer::sum);
								break;
							}
							claimed[y * bw + x] = !building.keep().contains(map.tile(tx + x, ty + y));
						}
					}
					if (!free) {
						continue;
					}
					for (int y = 0; y < bh; y++) {
						for (int x = 0; x < bw; x++) {
							if (claimed[y * bw + x]) {
								held[(ty + y) * cols + tx + x] = true;
							}
						}
					}
					out.add(new Claim(building, tx, ty, claimed));
					tally.merge(building.claimOnly() ? "building_claim_only" : "building", 1, Integer::sum);
				}
			}
		}
		return out;
	}

	/** True when each row of the entry's grid matches the map at (tx, ty), a row as far as it has ids. */
	static boolean matches(SceneMap map, Profile.Building building, int tx, int ty) {
		int cols = map.artCols();
		if (map.tile(tx, ty) != building.tiles().get(0)[0]) {
			return false;
		}
		for (int y = 0; y < building.tiles().size(); y++) {
			int[] row = building.tiles().get(y);
			if (tx + row.length > cols) {
				return false;
			}
			for (int x = 0; x < row.length; x++) {
				if (map.tile(tx + x, ty + y) != row[x]) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Blocks of a band building's model, from feet level: the drawing's rows under the roof band, plus the slab; a
	 * furniture entry's (P3) up to its pieces' highest layer.
	 */
	static int bound(Profile.Building building) {
		Profile.Band band = building.band();
		int drawn = building.drawnPx();
		if (band == null) {
			return 2;
		}
		if (building.parts() != null) {
			return Math.max(1, (building.parts().ytop(drawn) + 1 + 7) / 8);
		}
		return Math.max(1, (drawn - band.roofRows() + band.slab() + 7) / 8);
	}

	/**
	 * Places one claim into a map's columns: its keys, carriers and floor, and returns its template (null when nothing
	 * of it could stand).
	 *
	 * @param keys   per art tile, its column's keys (changed)
	 * @param blocks per art tile, its column's blocks (changed)
	 * @param mask   per art tile, its {@code shape_full} blocks (changed)
	 * @param decor  per art tile, its {@code decor} blocks (changed)
	 * @param floor  per art tile, the floor block's tile, -1 for its own (changed)
	 */
	static Templates.Placed place(SceneMap map, int atlas, Claim claim, int mapGround, ShapeKey[][] keys, int[] blocks, int[] mask, int[] decor,
			int[] floor, Map<String, Integer> tally) {
		Profile.Building building = claim.building();
		Profile.Band band = building.band();
		int cols = map.artCols();
		int rows = map.artRows();
		boolean indoor = !(map.surface() && map.env().outdoor());
		int bound = Math.min(bound(building), ShapeHeights.headroom(map));
		int bw = claim.width();
		int bh = claim.rows();
		boolean north = band != null && band.ledge() != null && claim.ty() > 0;
		boolean south = band != null && (band.ledge() != null || band.frontEave() > 0) && claim.ty() + bh < rows;
		int y0 = claim.ty() - (north ? 1 : 0);
		int d = bh + (north ? 1 : 0) + (south ? 1 : 0);
		int ground = ground(map, claim, mapGround);
		// The grid and each position's volume: solid (shape_full), doorway or overhang (decor), or nothing.
		JsonArray tiles = new JsonArray();
		JsonArray columns = new JsonArray();
		JsonArray doors = new JsonArray();
		int[] solidBlocks = new int[bw * d];
		int[] decorBlocks = new int[bw * d];
		for (int y = 0; y < d; y++) {
			for (int x = 0; x < bw; x++) {
				int tx = claim.tx() + x;
				int ty = y0 + y;
				int gy = ty - claim.ty();
				boolean inGrid = gy >= 0 && gy < bh;
				boolean claimed = inGrid && claim.claimed()[gy * bw + x];
				CellKind kind = map.kindAtTile(tx, ty);
				JsonArray column = new JsonArray();
				if (claimed && (kind == CellKind.SOLID || kind == CellKind.COUNTER)) {
					// Its fallback (I3) is the position's own tile on every face, so band.solid's height says it all.
					solidBlocks[y * bw + x] = building.claimOnly() ? 2 : Math.max(2, bound);
				} else if (claimed && kind == CellKind.WALK && !building.claimOnly()) {
					// A doorway: band.air marks it (and the overhang rows); V7's door cells stay empty.
					decorBlocks[y * bw + x] = bound;
				} else if (!inGrid && !building.claimOnly()) {
					if (kind == CellKind.WALK) {
						decorBlocks[y * bw + x] = bound;
					} else {
						tally.merge("building_overhang_cut", 1, Integer::sum);
					}
				} else if (claimed && !building.claimOnly()) {
					// Water or a ledge under a building's drawing: left as it is.
					tally.merge("building_tile_skipped", 1, Integer::sum);
				}
				tiles.add(claimed ? map.tile(tx, ty) : -1);
				columns.add(column);
			}
		}
		JsonObject entry = new JsonObject();
		entry.addProperty("template", Templates.FORMAT);
		entry.addProperty("atlas", atlas);
		entry.addProperty("w", bw);
		entry.addProperty("d", d);
		entry.addProperty("perCell", map.perCell());
		entry.add("tiles", tiles);
		entry.add("doors", doors);
		entry.addProperty("ground", ground);
		entry.addProperty("outdoor", !indoor);
		entry.add("columns", columns);
		entry.add("band", bandJson(building, north, south, solidBlocks, decorBlocks));
		String sig = Templates.signature(entry);
		for (int y = 0; y < d; y++) {
			for (int x = 0; x < bw; x++) {
				int i = (y0 + y) * cols + claim.tx() + x;
				int solid = solidBlocks[y * bw + x];
				int air = decorBlocks[y * bw + x];
				if (solid > 0) {
					ShapeKey[] column = new ShapeKey[solid];
					for (int k = 0; k < solid; k++) {
						column[k] = ShapeKey.tmpl(atlas, sig, x, k, y);
					}
					keys[i] = column;
					blocks[i] = solid;
					mask[i] = (1 << solid) - 1;
					if (building.claimOnly()) {
						floor[i] = ground;
					}
				} else if (air > 0) {
					ShapeKey[] column = new ShapeKey[air];
					for (int k = 0; k < air; k++) {
						column[k] = ShapeKey.tmpl(atlas, sig, x, k, y);
					}
					keys[i] = column;
					decor[i] = (1 << air) - 1;
					int gy = y0 + y - claim.ty();
					if (gy >= 0 && gy < bh) {
						// A doorway's floor wears the building's ground, not the door's own art lying flat.
						floor[i] = ground;
					}
				}
			}
		}
		return new Templates.Placed(sig, claim.tx(), y0, entry);
	}

	/** The band table as the shape index carries it (pixel-free numbers only). */
	static JsonObject bandJson(Profile.Building building, boolean north, boolean south, int[] solidBlocks, int[] decorBlocks) {
		JsonObject out = new JsonObject();
		out.addProperty("format", FORMAT);
		out.addProperty("index", building.index());
		out.addProperty("north", north ? 1 : 0);
		out.addProperty("south", south ? 1 : 0);
		out.add("top", rowsJson(building.topRows()));
		out.add("rows", rowsJson(building.tiles()));
		JsonArray solid = new JsonArray();
		for (int blocks : solidBlocks) {
			solid.add(blocks);
		}
		out.add("solid", solid);
		JsonArray air = new JsonArray();
		for (int blocks : decorBlocks) {
			air.add(blocks);
		}
		out.add("air", air);
		Profile.Band band = building.band();
		if (band == null) {
			out.addProperty("claimOnly", true);
			return out;
		}
		out.addProperty("roofRows", band.roofRows());
		out.addProperty("slab", band.slab());
		if (building.parts() != null) {
			// A furniture entry (P3): its pieces; the roof fields are only measuring fields there.
			out.add("parts", partsJson(building.parts()));
		} else {
			out.addProperty("roofBack", band.roofBack());
			out.addProperty("roofFront", band.roofFront());
			JsonArray cycle = new JsonArray();
			cycle.add(band.cycle0());
			cycle.add(band.cycle1());
			out.add("roofCycle", cycle);
			out.addProperty("frontEave", band.frontEave());
		}
		if (band.ledge() != null) {
			JsonArray ledge = new JsonArray();
			ledge.add(band.ledge()[0]);
			ledge.add(band.ledge()[1]);
			out.add("ledge", ledge);
		}
		out.addProperty("seal", band.seal());
		out.addProperty("depthPx", band.depthPx());
		out.addProperty("panes", band.panes());
		JsonArray scrub = new JsonArray();
		for (int[] rect : band.scrub()) {
			JsonArray r = new JsonArray();
			for (int v : rect) {
				r.add(v);
			}
			scrub.add(r);
		}
		out.add("scrub", scrub);
		return out;
	}

	/** A furniture entry's pieces as the shape index carries them: its plane, its base piece, its parts in order. */
	static JsonObject partsJson(Profile.Parts parts) {
		JsonObject out = new JsonObject();
		out.addProperty("plane", parts.plane());
		if (parts.tray() != null) {
			Profile.Tray t = parts.tray();
			JsonObject tray = new JsonObject();
			tray.add("top", pair(t.top0(), t.top1()));
			tray.add("front", pair(t.front0(), t.front1()));
			tray.add("x", pair(t.x0(), t.x1()));
			tray.add("inner", pair(t.inner0(), t.inner1()));
			tray.addProperty("floor", t.floor());
			out.add("tray", tray);
		}
		if (parts.desk() != null) {
			Profile.Desk d = parts.desk();
			JsonObject desk = new JsonObject();
			desk.add("fascia", pair(d.fascia0(), d.fascia1()));
			desk.add("base", pair(d.base0(), d.base1()));
			desk.addProperty("depthPx", d.depthPx());
			desk.addProperty("z", d.z());
			desk.add("x", pair(d.x0(), d.x1()));
			if (d.top() != null) {
				desk.add("top", pair(d.top()[0], d.top()[1]));
			}
			desk.addProperty("white", d.whiteLid());
			out.add("desk", desk);
		}
		if (parts.wall() != null) {
			Profile.WallBand w = parts.wall();
			JsonObject wall = new JsonObject();
			wall.add("cycle", pair(w.cycle0(), w.cycle1()));
			wall.addProperty("x", w.x());
			wall.addProperty("h", w.h());
			wall.addProperty("depthPx", w.depthPx());
			out.add("wall", wall);
		}
		JsonArray list = new JsonArray();
		for (Profile.Part part : parts.list()) {
			JsonObject o = new JsonObject();
			switch (part) {
				case Profile.FlatPart f -> {
					o.addProperty("kind", "flat");
					o.add("x", pair(f.x0(), f.x1()));
					o.add("rows", pair(f.r0(), f.r1()));
					o.addProperty("z", f.z());
					o.addProperty("at", f.at());
					o.addProperty("thick", f.thick());
				}
				case Profile.BoxPart b -> {
					o.addProperty("kind", "box");
					o.add("x", pair(b.x0(), b.x1()));
					o.add("rows", pair(b.r0(), b.r1()));
					o.addProperty("depth", b.depth());
					o.addProperty("z", b.z());
					o.addProperty("base", b.base());
					o.add("cycle", pair(b.c0(), b.c1()));
				}
				case Profile.IsoPart i -> {
					o.addProperty("kind", "iso");
					o.add("x", pair(i.x0(), i.x1()));
					o.add("rows", pair(i.r0(), i.r1()));
					o.addProperty("z", i.z());
					o.addProperty("plan", i.plan());
				}
				case Profile.UprightPart u -> {
					o.addProperty("kind", "upright");
					o.add("x", pair(u.x0(), u.x1()));
					o.add("top", pair(u.t0(), u.t1()));
					o.add("facade", pair(u.f0(), u.f1()));
					o.addProperty("depth", u.depth());
					o.addProperty("rise", u.rise());
					o.addProperty("z", u.z());
					if (u.inset() != null) {
						JsonArray inset = new JsonArray();
						for (int v : u.inset()) {
							inset.add(v);
						}
						o.add("inset", inset);
					}
					o.addProperty("stretch", u.stretch());
				}
			}
			list.add(o);
		}
		out.add("list", list);
		return out;
	}

	private static JsonArray pair(int a, int b) {
		JsonArray out = new JsonArray();
		out.add(a);
		out.add(b);
		return out;
	}

	private static JsonArray rowsJson(List<int[]> rows) {
		JsonArray out = new JsonArray();
		for (int[] row : rows) {
			JsonArray r = new JsonArray();
			for (int id : row) {
				r.add(id);
			}
			out.add(r);
		}
		return out;
	}

	/** The most frequent walkable tile along the grid's four edges (not the corners); ties the lowest id; else the map's. */
	static int ground(SceneMap map, Claim claim, int mapGround) {
		TreeMap<Integer, Integer> counts = new TreeMap<>();
		int bw = claim.width();
		int bh = claim.rows();
		for (int x = 0; x < bw; x++) {
			count(map, claim.tx() + x, claim.ty() - 1, counts);
			count(map, claim.tx() + x, claim.ty() + bh, counts);
		}
		for (int y = 0; y < bh; y++) {
			count(map, claim.tx() - 1, claim.ty() + y, counts);
			count(map, claim.tx() + bw, claim.ty() + y, counts);
		}
		int best = mapGround;
		int bestCount = 0;
		for (var e : counts.entrySet()) {
			if (e.getValue() > bestCount) {
				best = e.getKey();
				bestCount = e.getValue();
			}
		}
		return best;
	}

	private static void count(SceneMap map, int tx, int ty, TreeMap<Integer, Integer> counts) {
		if (tx >= 0 && ty >= 0 && tx < map.artCols() && ty < map.artRows() && map.kindAtTile(tx, ty) == CellKind.WALK) {
			counts.merge(map.tile(tx, ty), 1, Integer::sum);
		}
	}
}
