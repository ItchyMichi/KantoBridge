package dev.kantobridge.builder.shape.geom;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.shape.Templates;
import dev.kantobridge.scene.ArtSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A generated building's model (docs/specs/HAND_MODELS.md section 7): a decorated cube with a roof topper, built on each
 * client from its own drawing and the numbers the map review detected (tools/shape_fit/building_model.py: the same
 * geometry). One voxel per Game Boy pixel, x east, y up, z south, z = 0 the grid's north edge; the model stays inside
 * the grid (the eaves reach its south edge).
 *
 * <pre>
 * drawing   the grid's tiles; the roof its first roofRows rows, the facade the rest; shade 3 the outline
 * cube      as wide as the facade's outline columns (black down half its rows or more), 1.5 voxels a facade row
 *           high, its front EAVE voxels north of the grid's south edge: the facade on its front, a door cell's middle
 *           recessed DOOR voxels for its lowest 11 rows, features (10+ touching pixels of another shade than the
 *           wall's with some outline in them, under 60% of the wall's width: windows, signs; not bricks or mortar)
 *           standing out a voxel, band rows (porch roofs) BAND voxels; the sides each row's
 *           band shade (a shade filling 70% of it), else the wall's
 * roof      a hip: RH voxels in STEP-voxel steps, each STEP in from every side, as wide as the roof's outline; or flat:
 *           a FLAT-voxel slab with a PARAPET rim; its top the roof's rows from above (a flat roof's middle rows
 *           repeated at their own scale); a grid with no roof rows (its roof off the map) a flat roof in the wall's shade
 * gate      CellUnits.MAX_QUADS per block; else the template's columns (plain cubes)
 * </pre>
 */
public final class BoxBuilding {
	static final int EAVE = 4;
	static final int DOOR = 3;
	static final int BAND = 3;
	static final int RH = 16;
	static final int STEP = 2;
	static final int FLAT = 4;
	static final int PARAPET = 2;
	private static final int BLACK = 3;

	private BoxBuilding() {
	}

	/** The model's height in voxels for a drawing {@code rows} pixel rows deep, {@code roofRows} of them roof. */
	public static int height(int rows, int roofRows, boolean hip) {
		return wall(rows - roofRows) + (hip && roofRows > 0 ? RH : FLAT + PARAPET);
	}

	static int wall(int facadeRows) {
		return (int) Math.round(1.5 * Math.max(1, facadeRows));
	}

	/** The model of a box entry with this client's art. */
	public static TemplateRule.Template build(Templates.Entry entry, String atlas, ArtSource art) {
		TemplateRule.Template columns = TemplateRule.columns(entry, null, 1.0);
		JsonObject hull = entry.hull();
		JsonObject box = hull == null ? null : hull.getAsJsonObject("box");
		if (box == null) {
			return columns;
		}
		int px = art.artPx();
		int w = entry.w() * px;
		int d = entry.d() * px;
		List<Integer> tiles = new ArrayList<>();
		Map<Integer, Integer> slots = new HashMap<>();
		int[] shade = new int[w * d];
		int[] texel = new int[w * d];
		for (int sy = 0; sy < d; sy++) {
			for (int sx = 0; sx < w; sx++) {
				int tile = entry.tile(sx / px, sy / px);
				int[] s = tile < 0 ? null : art.tileShades(atlas, tile);
				if (s == null) {
					return columns;
				}
				Integer slot = slots.get(tile);
				if (slot == null) {
					slot = tiles.size();
					slots.put(tile, slot);
					tiles.add(tile);
				}
				shade[sy * w + sx] = s[(sy % px) * px + sx % px];
				texel[sy * w + sx] = VoxelGrid.texel(slot, sx % px, sy % px);
			}
		}
		List<int[]> doors = new ArrayList<>();
		for (int[] door : entry.doors()) {
			doors.add(door);
		}
		VoxelGrid grid = voxels(shade, texel, w, d, px, box, doors);
		Map<Integer, List<ShapeQuad>> blocks = new TreeMap<>();
		for (Map.Entry<Integer, List<ShapeQuad>> e : QuadMerger.merge(grid, QuadMerger.ALL_FACES, 0).entrySet()) {
			List<ShapeQuad> quads = new ArrayList<>(e.getValue().size());
			for (ShapeQuad quad : e.getValue()) {
				quads.add(quad.withTexture(tiles.get(quad.texture())));
			}
			blocks.put(e.getKey(), quads);
		}
		int quads = 0;
		for (List<ShapeQuad> block : blocks.values()) {
			quads = Math.max(quads, block.size());
		}
		if (quads > CellUnits.MAX_QUADS) {
			return TemplateRule.columns(entry, TemplateRule.Kind.HULL, 0.0);
		}
		int[] tileIds = tiles.stream().mapToInt(Integer::intValue).toArray();
		return new TemplateRule.Template(TemplateRule.Kind.HULL, null, 1.0, quads, blocks, null, grid, tileIds, box.get("roofRows").getAsInt());
	}

	/** The voxels of a drawing w x d pixels (shades and texels per pixel), door cells by their grid cell (cx, cy). */
	static VoxelGrid voxels(int[] shade, int[] texel, int w, int d, int px, JsonObject box, List<int[]> doors) {
		int roofRows = Math.max(0, Math.min(d - 1, box.get("roofRows").getAsInt()));
		boolean hip = "hip".equals(box.get("roof").getAsString()) && roofRows > 0;
		List<int[]> bands = new ArrayList<>();
		if (box.has("bands")) {
			for (var b : box.getAsJsonArray("bands")) {
				JsonArray pair = b.getAsJsonArray();
				bands.add(new int[] {pair.get(0).getAsInt(), pair.get(1).getAsInt()});
			}
		}
		int fr = d - roofRows; // facade rows
		int wall = wall(fr);
		int height = height(d, roofRows, hip);
		int gh = Math.max(px, ((height + px - 1) / px) * px);
		VoxelGrid grid = new VoxelGrid(w, gh, d, px);
		// the walls end at their outline: columns black down half the facade or more
		int left = -1;
		int right = -1;
		for (int x = 0; x < w; x++) {
			int dark = 0;
			for (int r = roofRows; r < d; r++) {
				dark += shade[r * w + x] == BLACK ? 1 : 0;
			}
			if (dark * 2 >= fr) {
				left = left < 0 ? x : left;
				right = x;
			}
		}
		if (left < 0) {
			left = 0;
			right = w - 1;
		}
		// the wall's shade: the facade's commonest between the outlines; a representative texel per shade
		int[] count = new int[16];
		int[] shadeTexel = new int[16];
		java.util.Arrays.fill(shadeTexel, -1);
		for (int r = roofRows; r < d; r++) {
			for (int x = left; x <= right; x++) {
				int s = Math.max(0, Math.min(15, shade[r * w + x]));
				count[s]++;
				if (shadeTexel[s] < 0) {
					shadeTexel[s] = texel[r * w + x];
				}
			}
		}
		int main = 0;
		for (int s = 1; s < 16; s++) {
			main = count[s] > count[main] ? s : main;
		}
		int mainTexel = shadeTexel[main] >= 0 ? shadeTexel[main] : texel[(d - 1) * w + (left + right) / 2];
		// features: 4-connected pieces of 10+ facade pixels of another shade than the wall's
		boolean[] feature = new boolean[w * d];
		boolean[] seen = new boolean[w * d];
		for (int r = roofRows; r < d; r++) {
			for (int x = left; x <= right; x++) {
				int i = r * w + x;
				if (seen[i] || shade[i] == main) {
					continue;
				}
				List<Integer> comp = new ArrayList<>();
				java.util.ArrayDeque<Integer> todo = new java.util.ArrayDeque<>();
				todo.add(i);
				seen[i] = true;
				while (!todo.isEmpty()) {
					int c = todo.poll();
					comp.add(c);
					int cy = c / w;
					int cx = c % w;
					int[][] steps = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
					for (int[] st : steps) {
						int nx = cx + st[0];
						int ny = cy + st[1];
						int n = ny * w + nx;
						if (nx >= left && nx <= right && ny >= roofRows && ny < d && !seen[n] && shade[n] != main) {
							seen[n] = true;
							todo.add(n);
						}
					}
				}
				boolean outlined = false;
				int cx0 = w;
				int cx1 = -1;
				for (int c : comp) {
					outlined |= shade[c] == BLACK;
					cx0 = Math.min(cx0, c % w);
					cx1 = Math.max(cx1, c % w);
				}
				// a window, a door, a sign: outlined in black, not a pattern spanning the wall (bricks, mortar)
				if (comp.size() >= 10 && outlined && (cx1 - cx0 + 1) * 10 < (right - left + 1) * 6) {
					for (int c : comp) {
						feature[c] = true;
					}
				}
			}
		}
		// the sides: each facade row's band shade (one filling 70% of the row), else the wall's
		int[] sideTexel = new int[d];
		for (int r = roofRows; r < d; r++) {
			int[] rc = new int[16];
			int n = 0;
			int[] first = new int[16];
			java.util.Arrays.fill(first, -1);
			for (int x = Math.min(right, left + 2); x < Math.max(left + 1, right - 1); x++) {
				int s = Math.max(0, Math.min(15, shade[r * w + x]));
				rc[s]++;
				n++;
				if (first[s] < 0) {
					first[s] = texel[r * w + x];
				}
			}
			int best = 0;
			for (int s = 1; s < 16; s++) {
				best = rc[s] > rc[best] ? s : best;
			}
			sideTexel[r] = n > 0 && rc[best] * 10 >= n * 7 && first[best] >= 0 ? first[best] : mainTexel;
		}
		int front = d - 1 - EAVE; // the cube's south face
		// the cube
		for (int y = 0; y < wall; y++) {
			int r = d - 1 - (int) ((long) y * fr / wall); // the facade row height y shows
			boolean band = false;
			for (int[] b : bands) {
				band |= r - roofRows >= b[0] && r - roofRows <= b[1];
			}
			for (int x = left; x <= right; x++) {
				int i = r * w + x;
				boolean door = false;
				for (int[] dc : doors) {
					door |= x / (2 * px) == dc[0] && x % (2 * px) >= 3 && x % (2 * px) <= 12 && r >= d - 11;
				}
				int z1 = front - (door ? DOOR : 0) + (band ? BAND : feature[i] && !door ? 1 : 0);
				for (int z = 0; z <= Math.min(d - 1, z1); z++) {
					int t = z >= z1 - 1 ? texel[i] : (x == left || x == right) ? sideTexel[r] : mainTexel;
					grid.set(x, y, z, t);
				}
			}
		}
		// the roof topper
		int rl = w;
		int rr = -1;
		for (int r = 0; r < roofRows; r++) {
			for (int x = 0; x < w; x++) {
				if (shade[r * w + x] == BLACK) {
					rl = Math.min(rl, x);
					rr = Math.max(rr, x);
				}
			}
		}
		if (rr < 0) {
			rl = Math.max(0, left - 2);
			rr = Math.min(w - 1, right + 2);
		}
		int x0 = Math.max(0, rl - 1);
		int x1 = Math.min(w - 1, rr + 1);
		int depth = d; // from the grid's north edge to its south edge
		int rows = Math.max(1, roofRows);
		if (hip) {
			for (int k = 0; k < RH / STEP; k++) {
				int inset = k * STEP;
				int xl = x0 + inset;
				int xh = x1 - inset;
				int zl = inset;
				int zh = depth - 1 - inset;
				if (xl >= xh || zl >= zh) {
					break;
				}
				for (int y = wall + k * STEP; y < wall + (k + 1) * STEP && y < gh; y++) {
					for (int z = zl; z <= zh; z++) {
						int r = Math.min(rows - 1, z * rows / depth);
						for (int x = xl; x <= xh; x++) {
							grid.set(x, y, z, roofTexel(shade, texel, w, r, x, mainTexel, roofRows));
						}
					}
				}
			}
		} else {
			int topRows = Math.min(2, rows);
			int frontRows = Math.min(FLAT, rows);
			int mid = Math.max(1, rows - topRows - frontRows);
			for (int y = wall; y < wall + FLAT + PARAPET && y < gh; y++) {
				for (int z = 0; z < depth; z++) {
					int r = z < topRows ? z : z >= depth - frontRows ? rows - (depth - z) : topRows + (z - topRows) % mid;
					for (int x = x0; x <= x1; x++) {
						boolean rim = x == x0 || x == x1 || z == 0 || z == depth - 1;
						if (y >= wall + FLAT && !rim) {
							continue;
						}
						grid.set(x, y, z, roofTexel(shade, texel, w, Math.max(0, r), x, mainTexel, roofRows));
					}
				}
			}
		}
		return grid;
	}

	/** The roof drawing's texel at (row, x); a pixel outside the row's outline (the ground around it) the wall's texel. */
	private static int roofTexel(int[] shade, int[] texel, int w, int r, int x, int mainTexel, int roofRows) {
		if (roofRows <= 0) {
			return mainTexel;
		}
		int first = -1;
		int last = -1;
		for (int c = 0; c < w; c++) {
			if (shade[r * w + c] == BLACK) {
				first = first < 0 ? c : first;
				last = c;
			}
		}
		if (first < 0) {
			// a row with no outline (Crystal's colour roofs draw rows without black): its own pixel
			return texel[r * w + x];
		}
		if (x < first || x > last) {
			// outside the row's outline: the nearest outlined row's pixel at this column, else the wall
			for (int q = 0; q < roofRows; q++) {
				int f = -1;
				int l = -1;
				for (int c = 0; c < w; c++) {
					if (shade[q * w + c] == BLACK) {
						f = f < 0 ? c : f;
						l = c;
					}
				}
				if (f >= 0 && x > f && x < l && shade[q * w + x] != BLACK) {
					return texel[q * w + x];
				}
			}
			return mainTexel;
		}
		return texel[r * w + x];
	}
}
