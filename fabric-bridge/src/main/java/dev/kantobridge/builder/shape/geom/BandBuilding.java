package dev.kantobridge.builder.shape.geom;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.shape.Templates;
import dev.kantobridge.scene.ArtSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * A band-table building's model (docs/specs/PROFILE_PLAN.md §5.5, P2), built on each client from its own drawing:
 * Dramaless Shape's building method as the research restates it (its {@code Buildings.lua}; nothing copied), in our
 * units: one voxel per Game Boy pixel, x east, y up, z south, z = 0 the claimed grid's north edge.
 *
 * <pre>
 * drawing   topRows then the grid's rows, as wide as the grid; shades 3 black, 2 dark, 1 grey, 0 white
 * inside    what a flood from the border (but its sealed sides) through grey and white can't reach; scrub rects are
 *           inside and grey, wearing the first inside grey pixel outside them
 * measure   per column its roof elevation (its first inside row within roofRows; none: no roof), the ground line,
 *           wallH = ground - roofRows, ytop = wallH - 1 + slab; panes (inside non-black regions under RECESS_MAX each
 *           way, below the roof band); a facade's inside black pixel de-outlined (the first inside non-black pixel
 *           within 3 toward the middle); per shade its first inside pixel
 * voxels    the roof slab under T = ytop - top[x] (its top the drawing row its depth shows: roofBack rows north,
 *           roofFront rows south, roofCycle between; the rim black, dark between; the rest dark), walls stopping under
 *           it, the awning two voxels proud north and south, the facade extruded over the footprint (front and back the
 *           drawing, the layers between de-outlined; a pane's front voxel left out, so it sinks one)
 * ours      each doorway carved DOOR_VOXELS high (the roof stays), its facade pixels moved to the doorway's back; the
 *           grid sliced into the template's blocks, overhang rows included
 * gate      CellUnits.MAX_QUADS per block and MAX_VOXELS in all; else the template's columns (plain cubes)
 * </pre>
 *
 * The top-down self-check doesn't score it: it no longer reproduces the drawing, by design.
 */
public final class BandBuilding {
	/** A pane is smaller than this each way, in pixels. */
	static final int RECESS_MAX = 24;
	/** A doorway's carved height: two blocks. */
	static final int DOOR_VOXELS = 16;
	/** The largest model, in voxels. */
	static final long MAX_VOXELS = 16L * 1024 * 1024;
	private static final int BLACK = 3;
	private static final int DARK = 2;
	private static final int GREY = 1;

	private BandBuilding() {
	}

	/** The model of a band entry with this client's art; a claim-only entry draws nothing. */
	public static TemplateRule.Template build(Templates.Entry entry, String atlas, ArtSource art) {
		JsonObject band = entry.band();
		TemplateRule.Template columns = TemplateRule.columns(entry, null, 1.0);
		if (band == null) {
			return columns;
		}
		if (band.has("claimOnly")) {
			return new TemplateRule.Template(TemplateRule.Kind.BAND, null, 1.0, 0, Map.of(), null, null, null, -1);
		}
		Model model = Model.of(entry, band, atlas, art);
		if (model == null) {
			return columns;
		}
		if ((long) model.w * model.gridH * model.gridD > MAX_VOXELS) {
			return TemplateRule.columns(entry, TemplateRule.Kind.BAND, 0.0);
		}
		VoxelGrid grid = model.voxels(entry, band);
		Map<Integer, List<ShapeQuad>> blocks = new TreeMap<>();
		for (Map.Entry<Integer, List<ShapeQuad>> e : QuadMerger.merge(grid, QuadMerger.ALL_FACES, 0).entrySet()) {
			List<ShapeQuad> quads = new ArrayList<>(e.getValue().size());
			for (ShapeQuad quad : e.getValue()) {
				quads.add(quad.withTexture(model.tiles.get(quad.texture())));
			}
			blocks.put(e.getKey(), quads);
		}
		int quads = 0;
		for (List<ShapeQuad> block : blocks.values()) {
			quads = Math.max(quads, block.size());
		}
		if (quads > CellUnits.MAX_QUADS) {
			return TemplateRule.columns(entry, TemplateRule.Kind.BAND, 0.0);
		}
		int[] tiles = model.tiles.stream().mapToInt(Integer::intValue).toArray();
		return new TemplateRule.Template(TemplateRule.Kind.BAND, null, 1.0, quads, blocks, null, grid, tiles, model.roofRows);
	}

	/** The composed and measured drawing, and the voxels it makes. */
	static final class Model {
		final int w;
		final int h;
		final int px;
		final int[] shade;
		final int[] texel;
		final boolean[] inside;
		final boolean[] pane;
		final int[] top;
		final int[] shadeTexel = new int[4];
		final List<Integer> tiles;
		final int roofRows;
		final int roofBack;
		final int roofFront;
		final int cycle0;
		final int cycle1;
		final int slab;
		final int frontEave;
		final int @Nullable [] ledge;
		final int depth;
		final int ground;
		final int ytop;
		final int roofX0;
		final int roofX1;
		final int north;
		final int gridH;
		final int gridD;

		private Model(int w, int h, int px, int[] shade, int[] texel, List<Integer> tiles, JsonObject band, Templates.Entry entry) {
			this.w = w;
			this.h = h;
			this.px = px;
			this.shade = shade;
			this.texel = texel;
			this.tiles = tiles;
			this.roofRows = Math.min(h, band.get("roofRows").getAsInt());
			this.roofBack = band.get("roofBack").getAsInt();
			this.roofFront = band.get("roofFront").getAsInt();
			JsonArray cycle = band.getAsJsonArray("roofCycle");
			this.cycle0 = cycle.get(0).getAsInt();
			this.cycle1 = Math.max(this.cycle0, cycle.get(1).getAsInt());
			this.slab = band.get("slab").getAsInt();
			this.frontEave = band.get("frontEave").getAsInt();
			JsonArray ledgeRows = band.has("ledge") ? band.getAsJsonArray("ledge") : null;
			this.ledge = ledgeRows == null ? null : new int[] {ledgeRows.get(0).getAsInt(), ledgeRows.get(1).getAsInt()};
			this.depth = band.get("depthPx").getAsInt();
			this.north = band.get("north").getAsInt();
			// Inside: what a flood from the border (but the sealed sides) through grey and white can't reach.
			this.inside = inside(shade, w, h, band.get("seal").getAsString());
			scrub(band.getAsJsonArray("scrub"));
			this.top = new int[w];
			int x0 = -1;
			int x1 = -1;
			for (int x = 0; x < w; x++) {
				this.top[x] = this.roofRows;
				for (int y = 0; y < this.roofRows; y++) {
					if (this.inside[y * w + x]) {
						this.top[x] = y;
						break;
					}
				}
				if (this.top[x] < this.roofRows) {
					x0 = x0 < 0 ? x : x0;
					x1 = x;
				}
			}
			this.roofX0 = x0;
			this.roofX1 = x1;
			int groundLine = this.roofRows;
			for (int y = h - 1; y >= this.roofRows; y--) {
				boolean any = false;
				for (int x = 0; x < w && !any; x++) {
					any = this.inside[y * w + x];
				}
				if (any) {
					groundLine = y + 1;
					break;
				}
			}
			this.ground = groundLine;
			this.ytop = (this.ground - this.roofRows) - 1 + this.slab;
			this.pane = band.get("panes").getAsBoolean() ? panes() : new boolean[w * h];
			for (int s = 0; s < 4; s++) {
				this.shadeTexel[s] = -1;
			}
			for (int i = 0; i < w * h; i++) {
				if (this.inside[i] && this.shade[i] >= 0 && this.shadeTexel[this.shade[i]] < 0) {
					this.shadeTexel[this.shade[i]] = this.texel[i];
				}
			}
			for (int s = 0; s < 4; s++) {
				if (this.shadeTexel[s] < 0) {
					this.shadeTexel[s] = this.shadeTexel[BLACK] >= 0 ? this.shadeTexel[BLACK] : this.texel[0];
				}
			}
			this.gridD = entry.d() * px;
			this.gridH = Math.max(px, ((Math.max(0, this.ytop) + 1 + px - 1) / px) * px);
		}

		/** The composed drawing of an entry, or null when this art lacks one of its tiles. */
		static @Nullable Model of(Templates.Entry entry, JsonObject band, String atlas, ArtSource art) {
			int px = art.artPx();
			List<int[]> rows = new ArrayList<>(rows(band.getAsJsonArray("top")));
			rows.addAll(rows(band.getAsJsonArray("rows")));
			int width = entry.w();
			if (rows.isEmpty() || width < 1) {
				return null;
			}
			int w = width * px;
			int h = rows.size() * px;
			List<Integer> tiles = new ArrayList<>();
			Map<Integer, Integer> slots = new HashMap<>();
			Map<Integer, int[]> shades = new HashMap<>();
			int[] shade = new int[w * h];
			int[] texel = new int[w * h];
			for (int sy = 0; sy < h; sy++) {
				int[] row = rows.get(sy / px);
				for (int sx = 0; sx < w; sx++) {
					int i = sy * w + sx;
					int tx = sx / px;
					int tile = tx < row.length ? row[tx] : -1;
					if (tile < 0) {
						// Beyond a short row: background.
						shade[i] = 0;
						texel[i] = VoxelGrid.EMPTY;
						continue;
					}
					int[] s = shades.get(tile);
					if (s == null) {
						s = art.tileShades(atlas, tile);
						if (s == null) {
							return null;
						}
						shades.put(tile, s);
						slots.put(tile, tiles.size());
						tiles.add(tile);
					}
					shade[i] = s[(sy % px) * px + sx % px];
					texel[i] = VoxelGrid.texel(slots.get(tile), sx % px, sy % px);
				}
			}
			return new Model(w, h, px, shade, texel, tiles, band, entry);
		}

		/** What a 4-connected flood from the border (sides in {@code seal} left out) through grey and white can't reach. */
		static boolean[] inside(int[] shade, int w, int h, String seal) {
			boolean[] reached = new boolean[w * h];
			int[] queue = new int[w * h];
			int tail = 0;
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					boolean seed = (y == 0 && seal.indexOf('n') < 0) || (y == h - 1 && seal.indexOf('s') < 0) || (x == 0 && seal.indexOf('w') < 0)
							|| (x == w - 1 && seal.indexOf('e') < 0);
					int i = y * w + x;
					if (seed && shade[i] <= GREY && !reached[i]) {
						reached[i] = true;
						queue[tail++] = i;
					}
				}
			}
			int head = 0;
			while (head < tail) {
				int i = queue[head++];
				int x = i % w;
				int y = i / w;
				int[] next = {y > 0 ? i - w : -1, x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y < h - 1 ? i + w : -1};
				for (int n : next) {
					if (n >= 0 && !reached[n] && shade[n] <= GREY) {
						reached[n] = true;
						queue[tail++] = n;
					}
				}
			}
			boolean[] inside = new boolean[w * h];
			for (int i = 0; i < inside.length; i++) {
				inside[i] = !reached[i];
			}
			return inside;
		}

		/** Painted-over rects: grey and inside, wearing the first inside grey pixel outside every rect. */
		private void scrub(JsonArray rects) {
			if (rects == null || rects.isEmpty()) {
				return;
			}
			List<int[]> list = new ArrayList<>();
			for (JsonElement e : rects) {
				JsonArray r = e.getAsJsonArray();
				list.add(new int[] {r.get(0).getAsInt(), r.get(1).getAsInt(), r.get(2).getAsInt(), r.get(3).getAsInt()});
			}
			int fill = -1;
			for (int i = 0; i < this.w * this.h && fill < 0; i++) {
				if (this.inside[i] && this.shade[i] == GREY && !inRects(list, i % this.w, i / this.w)) {
					fill = this.texel[i];
				}
			}
			for (int[] r : list) {
				for (int y = Math.max(0, r[1]); y <= Math.min(this.h - 1, r[3]); y++) {
					for (int x = Math.max(0, r[0]); x <= Math.min(this.w - 1, r[2]); x++) {
						int i = y * this.w + x;
						this.shade[i] = GREY;
						this.inside[i] = true;
						if (fill >= 0) {
							this.texel[i] = fill;
						}
					}
				}
			}
		}

		private static boolean inRects(List<int[]> rects, int x, int y) {
			for (int[] r : rects) {
				if (x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3]) {
					return true;
				}
			}
			return false;
		}

		/** Panes: 4-connected inside non-black regions in the facade rows, under RECESS_MAX pixels each way. */
		private boolean[] panes() {
			boolean[] out = new boolean[this.w * this.h];
			boolean[] seen = new boolean[this.w * this.h];
			int[] queue = new int[this.w * this.h];
			for (int start = this.roofRows * this.w; start < this.w * this.h; start++) {
				if (seen[start] || !this.inside[start] || this.shade[start] == BLACK) {
					continue;
				}
				int head = 0;
				int tail = 0;
				queue[tail++] = start;
				seen[start] = true;
				int minX = this.w;
				int maxX = -1;
				int minY = this.h;
				int maxY = -1;
				while (head < tail) {
					int i = queue[head++];
					int x = i % this.w;
					int y = i / this.w;
					minX = Math.min(minX, x);
					maxX = Math.max(maxX, x);
					minY = Math.min(minY, y);
					maxY = Math.max(maxY, y);
					int[] next = {y > this.roofRows ? i - this.w : -1, x > 0 ? i - 1 : -1, x < this.w - 1 ? i + 1 : -1, y < this.h - 1 ? i + this.w : -1};
					for (int n : next) {
						if (n >= 0 && !seen[n] && this.inside[n] && this.shade[n] != BLACK) {
							seen[n] = true;
							queue[tail++] = n;
						}
					}
				}
				if (maxX - minX + 1 < RECESS_MAX && maxY - minY + 1 < RECESS_MAX) {
					for (int k = 0; k < tail; k++) {
						out[queue[k]] = true;
					}
				}
			}
			return out;
		}

		/** A facade pixel's texel between the front and the back: an inside black pixel takes the nearest non-black. */
		private int deOutlined(int x, int sy) {
			int i = sy * this.w + x;
			if (this.shade[i] != BLACK) {
				return this.texel[i];
			}
			int dir = x < this.w / 2 ? 1 : -1;
			for (int k = 1; k <= 3; k++) {
				int nx = x + dir * k;
				if (nx < 0 || nx >= this.w) {
					break;
				}
				int n = sy * this.w + nx;
				if (this.inside[n] && this.shade[n] != BLACK) {
					return this.texel[n];
				}
			}
			return this.texel[i];
		}

		/** The roof row a depth shows: roofBack rows north, roofFront rows south, roofCycle between. */
		private int roofRow(int z, int rz1) {
			int row;
			if (z < this.roofBack) {
				row = z;
			} else if (rz1 - z < this.roofFront) {
				row = this.roofRows - 1 - (rz1 - z);
			} else {
				row = this.cycle0 + Math.floorMod(z - this.cycle0, this.cycle1 - this.cycle0 + 1);
			}
			return Math.max(0, Math.min(this.roofRows - 1, row));
		}

		/** True for a voxel of the roof slab (or above it) in a roofed column. */
		private boolean roofLayer(int x, int y) {
			return this.top[x] < this.roofRows && y > this.ytop - this.top[x] - this.slab;
		}

		/** The texel of voxel (x, y, z), z in the model's own frame (0 the grid's north row), or EMPTY. */
		int at(int x, int y, int z) {
			int rz1 = this.depth - 1 + this.frontEave;
			if (this.top[x] < this.roofRows) {
				int t = this.ytop - this.top[x];
				if (y > t) {
					return VoxelGrid.EMPTY;
				}
				if (y > t - this.slab) {
					if (z < 0 || z > rz1) {
						return VoxelGrid.EMPTY;
					}
					boolean rim = x == this.roofX0 || x == this.roofX1 || z == 0 || z == rz1;
					if (y == t) {
						if (rim) {
							return this.shadeTexel[BLACK];
						}
						int texel = this.texel[Math.max(this.roofRow(z, rz1), this.top[x]) * this.w + x];
						return texel == VoxelGrid.EMPTY ? this.shadeTexel[DARK] : texel;
					}
					return rim && y == t - this.slab + 1 ? this.shadeTexel[BLACK] : this.shadeTexel[DARK];
				}
			}
			if (this.ledge != null && (z == -2 || z == -1 || z == this.depth || z == this.depth + 1)) {
				int sy = this.ground - 1 - y;
				return sy >= this.ledge[0] && sy <= this.ledge[1] && sy >= 0 && sy < this.h && this.inside[sy * this.w + x] ? this.texel[sy * this.w + x]
						: VoxelGrid.EMPTY;
			}
			if (z < 0 || z > this.depth - 1) {
				return VoxelGrid.EMPTY;
			}
			int sy = this.ground - 1 - y;
			if (sy < 0 || sy >= this.h) {
				return VoxelGrid.EMPTY;
			}
			if (y == 0 && !this.inside[sy * this.w + x] && sy > 0 && this.inside[(sy - 1) * this.w + x]) {
				sy--;
			}
			int i = sy * this.w + x;
			if (!this.inside[i] || this.texel[i] == VoxelGrid.EMPTY) {
				return VoxelGrid.EMPTY;
			}
			if (z == this.depth - 1) {
				return this.depth >= 2 && this.pane[i] ? VoxelGrid.EMPTY : this.texel[i];
			}
			return z == 0 ? this.texel[i] : this.deOutlined(x, sy);
		}

		/** The voxels in the template's grid (the north overhang row first), doorways carved. */
		VoxelGrid voxels(Templates.Entry entry, JsonObject band) {
			VoxelGrid grid = new VoxelGrid(this.w, this.gridH, this.gridD, this.px);
			int shift = this.north * this.px;
			int zMin = this.ledge != null ? -2 : 0;
			int zMax = Math.max(this.depth - 1 + this.frontEave, this.ledge != null ? this.depth + 1 : this.depth - 1);
			for (int x = 0; x < this.w; x++) {
				for (int y = 0; y <= this.ytop && y < this.gridH; y++) {
					for (int z = zMin; z <= zMax; z++) {
						int zz = z + shift;
						if (zz < 0 || zz >= this.gridD) {
							continue;
						}
						int t = this.at(x, y, z);
						if (t != VoxelGrid.EMPTY) {
							grid.set(x, y, zz, t);
						}
					}
				}
			}
			this.carveDoorways(grid, entry, band);
			return grid;
		}

		/**
		 * Each doorway (an air position inside the claimed rows) carved DOOR_VOXELS high, the roof spared; the facade
		 * pixels of its columns set on the voxels just behind it, so the door's art stands at the doorway's back.
		 */
		private void carveDoorways(VoxelGrid grid, Templates.Entry entry, JsonObject band) {
			JsonArray air = band.getAsJsonArray("air");
			int rows = band.getAsJsonArray("rows").size();
			boolean[] doorway = new boolean[entry.w() * entry.d()];
			for (int y = this.north; y < this.north + rows && y < entry.d(); y++) {
				for (int x = 0; x < entry.w(); x++) {
					doorway[y * entry.w() + x] = air.get(y * entry.w() + x).getAsInt() > 0;
				}
			}
			for (int x = 0; x < this.w; x++) {
				int tx = x / this.px;
				int back = -1;
				for (int gy = 0; gy < entry.d(); gy++) {
					if (!doorway[gy * entry.w() + tx]) {
						continue;
					}
					back = back < 0 ? gy * this.px : back;
					for (int y = 0; y < DOOR_VOXELS && y < this.gridH; y++) {
						if (this.roofLayer(x, y)) {
							continue;
						}
						for (int zz = gy * this.px; zz < (gy + 1) * this.px; zz++) {
							grid.clear(x, y, zz);
						}
					}
				}
				if (back > 0) {
					for (int y = 0; y < DOOR_VOXELS && y < this.gridH; y++) {
						int sy = this.ground - 1 - y;
						if (this.roofLayer(x, y) || sy < 0 || sy >= this.h || !grid.solid(x, y, back - 1)) {
							continue;
						}
						int i = sy * this.w + x;
						if (this.inside[i] && this.texel[i] != VoxelGrid.EMPTY) {
							grid.set(x, y, back - 1, this.texel[i]);
						}
					}
				}
			}
		}
	}

	/** Rows of tile ids from JSON. */
	static List<int[]> rows(@Nullable JsonArray array) {
		List<int[]> out = new ArrayList<>();
		if (array == null) {
			return out;
		}
		for (JsonElement row : array) {
			JsonArray r = row.getAsJsonArray();
			int[] ids = new int[r.size()];
			for (int k = 0; k < ids.length; k++) {
				ids[k] = r.get(k).getAsInt();
			}
			out.add(ids);
		}
		return out;
	}
}
