package dev.kantobridge.builder.shape.geom;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A furniture entry's model (docs/specs/PROFILE_PLAN.md §5.6, P3: the research's parts path, re-implemented in our own
 * code), built on each client from the band model's composed and measured drawing: a base piece, then the parts riding
 * its plane, in list order, each later write replacing an earlier one in the same voxel. x east (the drawing's
 * columns), y up, z south (0 the grid's north row); nothing leaves the plot ({@code 0 <= z < depthPx}) or goes under
 * the floor. Shades 3 black, 2 dark, 1 grey, 0 white; "inside" is the silhouette the band model's flood leaves.
 *
 * <pre>
 * none      the parts ride the floor (plane 0)
 * tray      an open box: walls front.. rows high around an opening (inner columns, z inside the plot), its rim the
 *           row its depth shows (the far rim top[0], the near front[0], the rows between stretched over the depth),
 *           the walls below it the front rows folded up (front and back layers the drawing, sides de-outlined), the
 *           opening's floor layers 0..floor; no pane sinks into a wall one voxel thick
 * desk      its wall band first (stripe rows cycled down from its top, over the whole width), then its base rows standing
 *           on the floor (panes deleted from the front layer), the fascia rows on top (every voxel of a layer the row,
 *           so the fascia wraps its sides), the lid: top rows by depth (the front edge fascia[0]), a column a part
 *           covers borrowed from beside the part; without top rows a lid of the drawing's shades (black rim, a white
 *           highlight row and column, the field grey or white)
 * flat      rows laid as depths from z, on layers at-thick+1..at
 * box       face-on at its drawn elevation over the ground line: its top rows and last rows 1:1, the cycle between
 * iso       2:1 isometric: a diamond footprint about z, each voxel the pixel its position projects to
 * upright   a lid (back rows by depth, the facade's first row its front edge) over the facade rows extruded over its
 *           depth (front and back the drawing, between de-outlined), a pane or the inset rect left out of the front
 * </pre>
 *
 * A black pixel between a piece's front and back layers takes the first non-black inside pixel within three columns
 * toward the piece's middle, never past its own columns.
 */
final class Furniture {
	private static final int BLACK = 3;
	private static final int GREY = 1;
	private static final int WHITE = 0;

	private final BandBuilding.Model m;
	private final JsonObject parts;
	private final int plane;
	private final int depth;
	private final int ytop;
	private VoxelGrid grid;

	private Furniture(BandBuilding.Model m, JsonObject parts) {
		this.m = m;
		this.parts = parts;
		this.plane = parts.get("plane").getAsInt();
		this.depth = m.depth;
		this.ytop = top(m, parts);
	}

	/** The model's highest layer: the base piece's lid and each part's top (a box part's from the measured ground line). */
	static int top(BandBuilding.Model m, JsonObject parts) {
		int plane = parts.get("plane").getAsInt();
		int top = parts.has("tray") || parts.has("desk") ? plane - 1 : 0;
		for (JsonElement e : parts.getAsJsonArray("list")) {
			JsonObject p = e.getAsJsonObject();
			int part = switch (p.get("kind").getAsString()) {
				case "flat" -> p.get("at").getAsInt();
				case "box" -> m.ground - 1 - pair(p, "rows")[0];
				case "iso" -> plane + isoHeight(p);
				default -> plane + p.get("rise").getAsInt() + pair(p, "facade")[1] - pair(p, "facade")[0];
			};
			top = Math.max(top, part);
		}
		return Math.max(0, top);
	}

	/** The voxels of a furniture entry in a grid {@code w × gridH × gridD}. */
	static VoxelGrid voxels(BandBuilding.Model m, JsonObject parts, int gridH, int gridD) {
		Furniture f = new Furniture(m, parts);
		f.grid = new VoxelGrid(m.w, gridH, gridD, m.px);
		if (parts.has("tray")) {
			f.tray(parts.getAsJsonObject("tray"));
		} else if (parts.has("desk")) {
			f.desk(parts.getAsJsonObject("desk"));
		}
		for (JsonElement e : parts.getAsJsonArray("list")) {
			JsonObject p = e.getAsJsonObject();
			switch (p.get("kind").getAsString()) {
				case "flat" -> f.flat(p);
				case "box" -> f.box(p);
				case "iso" -> f.iso(p);
				default -> f.upright(p);
			}
		}
		return f.grid;
	}

	// ---- base pieces ----

	private void tray(JsonObject t) {
		int[] top = pair(t, "top");
		int[] front = pair(t, "front");
		int[] x = pair(t, "x");
		int[] inner = pair(t, "inner");
		int floor = t.get("floor").getAsInt();
		int d = this.depth;
		int lo = top[0] + 1;
		int hi = top[1] - 1;
		for (int z = 0; z < d; z++) {
			// The row a depth shows: the far rim, the near rim (the front wall's top row), the rows between stretched.
			int r = z == 0 ? top[0] : z == d - 1 ? front[0]
					: Math.max(top[0], Math.min(top[1], lo + Math.floorDiv((z - 1) * (hi - lo), Math.max(1, d - 3))));
			for (int sx = x[0]; sx <= x[1]; sx++) {
				boolean hollow = sx >= inner[0] && sx <= inner[1] && z > 0 && z < d - 1;
				if (hollow) {
					for (int y = 0; y <= floor; y++) {
						this.putInside(sx, y, z, sx, r);
					}
					continue;
				}
				this.putInside(sx, this.plane - 1, z, sx, r);
				for (int y = 0; y < this.plane - 1; y++) {
					int s = front[1] - y;
					if (this.inside(sx, s)) {
						this.put(sx, y, z, z == 0 || z == d - 1 ? this.texel(sx, s) : this.deOutlined(sx, s, x[0], x[1]));
					}
				}
			}
		}
	}

	private void desk(JsonObject desk) {
		int[] fascia = pair(desk, "fascia");
		int[] base = pair(desk, "base");
		int[] x = pair(desk, "x");
		int dz0 = desk.get("z").getAsInt();
		int depthPx = desk.get("depthPx").getAsInt();
		int dz1 = dz0 + (depthPx > 0 ? depthPx : this.depth) - 1;
		int p = this.plane;
		if (this.parts.has("wall")) {
			// The wall band behind: its stripe cycled down from its top, over the whole width, built first.
			JsonObject wall = this.parts.getAsJsonObject("wall");
			int[] cycle = pair(wall, "cycle");
			int wx = wall.get("x").getAsInt();
			int h = wall.get("h").getAsInt();
			int wd = wall.get("depthPx").getAsInt();
			for (int y = 0; y < h; y++) {
				int t = this.texel(wx, cycle[0] + Math.floorMod(h - 1 - y, cycle[1] - cycle[0] + 1));
				for (int sx = 0; sx < this.m.w; sx++) {
					for (int z = 0; z < wd; z++) {
						this.put(sx, y, z, t);
					}
				}
			}
		}
		for (int s = base[0]; s <= base[1]; s++) {
			int y = base[1] - s;
			for (int sx = x[0]; sx <= x[1]; sx++) {
				if (!this.inside(sx, s)) {
					continue;
				}
				for (int z = dz0; z <= dz1; z++) {
					this.put(sx, y, z, z == dz0 || z == dz1 ? this.texel(sx, s) : this.deOutlined(sx, s, x[0], x[1]));
				}
			}
		}
		for (int s = base[0]; s <= base[1]; s++) {
			for (int sx = x[0]; sx <= x[1]; sx++) {
				if (this.m.pane[s * this.m.w + sx]) {
					this.clear(sx, base[1] - s, dz1);
				}
			}
		}
		for (int s = fascia[0]; s <= fascia[1]; s++) {
			int y = p - 1 - (s - fascia[0]);
			for (int sx = x[0]; sx <= x[1]; sx++) {
				int t = this.texel(sx, s);
				for (int z = dz0; z <= dz1; z++) {
					this.put(sx, y, z, t);
				}
			}
		}
		int[] top = desk.has("top") ? pair(desk, "top") : null;
		boolean white = desk.get("white").getAsBoolean();
		for (int z = dz0; z <= dz1; z++) {
			for (int sx = x[0]; sx <= x[1]; sx++) {
				int t;
				if (top != null) {
					int row = z == dz1 ? fascia[0] : Math.min(top[0] + (z - dz0), top[1]);
					t = this.texel(this.borrow(sx, row, x[0], x[1]), row);
				} else if (sx == x[0] || sx == x[1] || z == dz0 || z == dz1) {
					t = this.m.shadeTexel[BLACK];
				} else if (z == dz0 + 1 || sx == x[0] + 1) {
					t = this.m.shadeTexel[WHITE];
				} else {
					t = this.m.shadeTexel[white ? WHITE : GREY];
				}
				this.put(sx, p - 1, z, t);
			}
		}
	}

	/** A lid pixel a part covers takes the column just beside the part, on its nearer side (ties to the east). */
	private int borrow(int sx, int row, int lo, int hi) {
		for (JsonElement e : this.parts.getAsJsonArray("list")) {
			JsonObject p = e.getAsJsonObject();
			int[] x = pair(p, "x");
			int r0;
			int r1;
			if (p.has("rows")) {
				r0 = pair(p, "rows")[0];
				r1 = pair(p, "rows")[1];
			} else {
				r0 = pair(p, "top")[0];
				r1 = pair(p, "facade")[1];
			}
			if (sx >= x[0] && sx <= x[1] && row >= r0 && row <= r1) {
				int col = sx - x[0] < x[1] - sx ? x[0] - 1 : x[1] + 1;
				return Math.max(lo, Math.min(hi, col));
			}
		}
		return sx;
	}

	// ---- parts ----

	private void flat(JsonObject p) {
		int[] x = pair(p, "x");
		int[] rows = pair(p, "rows");
		int z0 = p.get("z").getAsInt();
		int at = p.get("at").getAsInt();
		int thick = p.get("thick").getAsInt();
		for (int s = rows[0]; s <= rows[1]; s++) {
			int z = z0 + (s - rows[0]);
			for (int sx = x[0]; sx <= x[1]; sx++) {
				if (!this.inside(sx, s)) {
					continue;
				}
				for (int y = Math.max(0, at - thick + 1); y <= at; y++) {
					this.put(sx, y, z, this.texel(sx, s));
				}
			}
		}
	}

	private void box(JsonObject p) {
		int[] x = pair(p, "x");
		int[] rows = pair(p, "rows");
		int[] cycle = pair(p, "cycle");
		int pd = p.get("depth").getAsInt();
		int pz = p.get("z").getAsInt();
		int yT = this.m.ground - 1 - rows[0];
		int yB = p.get("base").getAsInt() >= 0 ? p.get("base").getAsInt() : this.m.ground - 1 - rows[1];
		for (int y = Math.max(0, yB); y <= yT; y++) {
			int k = yT - y;
			int j = y - yB;
			int s;
			if (k < cycle[0] - rows[0]) {
				s = rows[0] + k;
			} else if (j < rows[1] - cycle[1]) {
				s = rows[1] - j;
			} else {
				s = cycle[0] + Math.floorMod(k - (cycle[0] - rows[0]), cycle[1] - cycle[0] + 1);
			}
			for (int sx = x[0]; sx <= x[1]; sx++) {
				if (!this.inside(sx, s)) {
					continue;
				}
				for (int z = pz; z < pz + pd; z++) {
					this.put(sx, y, z, z == pz || z == pz + pd - 1 ? this.texel(sx, s) : this.deOutlined(sx, s, x[0], x[1]));
				}
			}
		}
	}

	private static int isoHeight(JsonObject p) {
		int[] x = pair(p, "x");
		int[] rows = pair(p, "rows");
		int rz = (x[1] - x[0] + 1) / 2 / 2;
		return rows[1] - 2 * rz - rows[0];
	}

	private void iso(JsonObject p) {
		int[] x = pair(p, "x");
		int[] rows = pair(p, "rows");
		int cz = p.get("z").getAsInt();
		int plan = p.get("plan").getAsInt();
		int rx = (x[1] - x[0] + 1) / 2;
		int rz = rx / 2;
		int oy = rows[1] - rz;
		int h = isoHeight(p);
		for (int sx = x[0]; sx <= x[1]; sx++) {
			int dx2 = 2 * sx - (x[0] + x[1]);
			for (int dz = -plan; dz <= plan; dz++) {
				if ((long) Math.abs(dx2) * plan + 2L * Math.abs(dz) * rx > (2L * rx + 1) * plan) {
					continue;
				}
				// Depth back into the drawing: about dz · rz / plan, rounded.
				int dd = Math.floorDiv(2 * dz * rz + plan, 2 * plan);
				for (int y = 0; y <= h; y++) {
					int row = oy + dd - y;
					if (row >= rows[0] && row <= rows[1] && this.inside(sx, row)) {
						this.put(sx, this.plane + y, cz + dz, this.texel(sx, row));
					}
				}
			}
		}
	}

	private void upright(JsonObject p) {
		int[] x = pair(p, "x");
		int[] top = pair(p, "top");
		int[] facade = pair(p, "facade");
		int pd = p.get("depth").getAsInt();
		int pz = p.get("z").getAsInt();
		int yb = this.plane + p.get("rise").getAsInt();
		int yt = yb + (facade[1] - facade[0]);
		int zf = pz + pd - 1;
		boolean stretch = p.get("stretch").getAsBoolean();
		JsonArray insetJson = p.has("inset") ? p.getAsJsonArray("inset") : null;
		int[] inset = insetJson == null ? null
				: new int[] {insetJson.get(0).getAsInt(), insetJson.get(1).getAsInt(), insetJson.get(2).getAsInt(), insetJson.get(3).getAsInt()};
		for (int sx = x[0]; sx <= x[1]; sx++) {
			for (int z = pz; z <= zf; z++) {
				if (z == zf) {
					this.putInside(sx, yt, z, sx, facade[0]);
					continue;
				}
				int s = stretch ? Math.min(top[0] + Math.floorDiv((z - pz) * (top[1] - top[0] + 1), Math.max(1, pd - 1)), top[1])
						: Math.min(top[0] + (z - pz), top[1]);
				while (s <= top[1] && !this.inside(sx, s)) {
					s++;
				}
				if (s <= top[1]) {
					this.put(sx, yt, z, this.texel(sx, s));
				}
			}
		}
		for (int s = facade[0] + 1; s <= facade[1]; s++) {
			int y = yb + (facade[1] - s);
			for (int sx = x[0]; sx <= x[1]; sx++) {
				if (!this.inside(sx, s)) {
					continue;
				}
				boolean sunk = this.m.pane[s * this.m.w + sx] || (inset != null && sx >= inset[0] && sx <= inset[1] && s >= inset[2] && s <= inset[3]);
				for (int z = pz; z <= zf; z++) {
					if (z == zf) {
						if (!sunk) {
							this.put(sx, y, z, this.texel(sx, s));
						}
					} else {
						this.put(sx, y, z, z == pz ? this.texel(sx, s) : this.deOutlined(sx, s, x[0], x[1]));
					}
				}
			}
		}
	}

	// ---- the drawing and the store ----

	private boolean inside(int sx, int sy) {
		return sx >= 0 && sy >= 0 && sx < this.m.w && sy < this.m.h && this.m.inside[sy * this.m.w + sx]
				&& this.m.texel[sy * this.m.w + sx] != VoxelGrid.EMPTY;
	}

	private int texel(int sx, int sy) {
		return sx >= 0 && sy >= 0 && sx < this.m.w && sy < this.m.h ? this.m.texel[sy * this.m.w + sx] : VoxelGrid.EMPTY;
	}

	/** A black pixel between front and back takes the first inside non-black within three columns toward the piece's middle. */
	private int deOutlined(int sx, int sy, int lo, int hi) {
		int i = sy * this.m.w + sx;
		if (this.m.shade[i] != BLACK) {
			return this.m.texel[i];
		}
		int dir = sx < Math.floorDiv(lo + hi, 2) ? 1 : -1;
		for (int k = 1; k <= 3; k++) {
			int nx = sx + dir * k;
			if (nx < lo || nx > hi || nx < 0 || nx >= this.m.w) {
				break;
			}
			if (this.inside(nx, sy) && this.m.shade[sy * this.m.w + nx] != BLACK) {
				return this.m.texel[sy * this.m.w + nx];
			}
		}
		return this.m.texel[i];
	}

	/** The pixel (sx, sy) at a voxel, where the pixel is inside the silhouette. */
	private void putInside(int x, int y, int z, int sx, int sy) {
		if (this.inside(sx, sy)) {
			this.put(x, y, z, this.texel(sx, sy));
		}
	}

	/** One voxel, the last write winning; nothing outside the plot, under the floor or over the model's top. */
	private void put(int x, int y, int z, int texel) {
		if (texel == VoxelGrid.EMPTY || x < 0 || x >= this.m.w || y < 0 || y > this.ytop || y >= this.grid.height() || z < 0 || z >= this.depth
				|| z >= this.grid.depth()) {
			return;
		}
		this.grid.set(x, y, z, texel);
	}

	private void clear(int x, int y, int z) {
		if (x >= 0 && x < this.m.w && y >= 0 && y < this.grid.height() && z >= 0 && z < this.grid.depth()) {
			this.grid.clear(x, y, z);
		}
	}

	private static int[] pair(JsonObject o, String key) {
		JsonArray a = o.getAsJsonArray(key);
		return new int[] {a.get(0).getAsInt(), a.get(1).getAsInt()};
	}
}
