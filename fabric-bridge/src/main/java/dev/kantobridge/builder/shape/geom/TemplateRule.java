package dev.kantobridge.builder.shape.geom;

import dev.kantobridge.builder.shape.SelfCheck;
import dev.kantobridge.builder.shape.ShapeKey;
import dev.kantobridge.builder.shape.Templates;
import dev.kantobridge.scene.ArtSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * Tier C geometry (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5, V7): a building template from each client's own drawing, inside
 * the volume its Tier A columns fix. Our own rules, in pixels (one voxel per Game Boy pixel):
 *
 * <pre>
 * drawing     the template's tiles composed; its door cells' tiles too (their art folds into the facade)
 * background  flood from the drawing's border and the tiles outside the structure through its light pixels (the two
 *             light shades; dark outlines and shading are the boundary); a door's pixels are never background. It only
 *             tells the panes: nothing is carved (see below)
 * panes       enclosed light regions of PANE_MIN..PANE_MAX pixels
 * bands       roof rows R: a pack's struct: rule, else the rows above the highest pane row; no pane: no template
 *             (the Tier A columns stay, not as a failure)
 * facade      per run (rows n..f of a tile column; origin f, or a folded door's southern row): rows below the roof
 *             band stand upright at the run's front, band k showing the drawing's row origin - k (the fold rule),
 *             extruded back over the run's depth; panes sink one pixel
 * roof        one voxel layer on the facade, over the run's depth: at depth z it shows the drawing row it is seen
 *             at from the game's view (z - y), the roof rows cycled where that lies north of the drawing
 * carving     none: a roof carved where the drawing is light shows the wall's top from the game's view, and a carved
 *             facade turns a wall painted light into a hole the roof floats over, which no score sees (the floor
 *             shows the same art). So the volume is full, as Tier A's is
 * sides       west and east faces show their band's tile upright (texture left = north), the back the run's plain
 *             back tile, as Tier A's columns do
 * gate        no block over CellUnits.MAX_QUADS quads, and MATCH of the drawing's pixels seen as drawn (texel
 *             identity, the door fold's shift allowed); else the Tier A columns (a failure)
 * </pre>
 *
 * A pack's {@code struct:} model replaces it when it fits the template's volume. Pure; a quad's texture is a tile id
 * (or, for a model, a colour index).
 */
public final class TemplateRule {
	/** A template's gate: the share of its drawing seen as drawn. */
	public static final double MATCH = 0.90;
	/** The largest pane, in pixels. */
	public static final int PANE_MAX = 48;
	/** The smallest pane, in pixels. */
	public static final int PANE_MIN = 2;

	/** What a template became. */
	public enum Kind {
		BUILDING("building"),
		/** The profile's band-table building (P2). */
		BAND("band"),
		MODEL("model"),
		COLUMNS("columns");

		private final String id;

		Kind(String id) {
			this.id = id;
		}

		public String id() {
			return this.id;
		}
	}

	/**
	 * A template's geometry.
	 *
	 * @param failed   what was tried and failed its gate before the columns, or null
	 * @param match    the share of the drawing seen as drawn (1 for columns and models)
	 * @param blocks   per block ({@link QuadMerger#blockKey}), its quads: a texture is a tile id, or a colour index for a
	 *                 model
	 * @param colours  a model's colours by index, else null
	 * @param voxels   a building's voxels (the pack template writes them), else null
	 * @param tiles    a building's tile list: the tile id of each texel slot of {@code voxels}, else null
	 * @param roofRows the roof rows the bands used, -1 without a band split
	 */
	public record Template(Kind kind, @Nullable Kind failed, double match, int maxQuads, Map<Integer, List<ShapeQuad>> blocks,
			int @Nullable [] colours, @Nullable VoxelGrid voxels, int @Nullable [] tiles, int roofRows) {
		public List<ShapeQuad> block(int bx, int by, int bz) {
			return this.blocks.getOrDefault(QuadMerger.blockKey(bx, by, bz), List.of());
		}
	}

	/** One run of the template: rows {@code n..f} of grid column {@code x}, its fold origin and its blocks. */
	record Run(int x, int n, int f, int origin, int blocks) {
	}

	private static final int OUTSIDE = 0;
	private static final int REGION = 1;
	private static final int DOOR = 2;

	private TemplateRule() {
	}

	/** Builds a template with this client's art and packs. */
	public static Template build(Templates.Entry entry, String atlas, ArtSource art, ShapeOverrides overrides) {
		ShapeOverrides.StructModel model = overrides.templateModel(atlas, entry.sig());
		if (model != null && fits(model, entry)) {
			return new Template(Kind.MODEL, null, 1.0, maxQuads(model.blocks()), model.blocks(), model.colours().clone(), null, null, -1);
		}
		if (entry.band() != null) {
			// The profile's band-table building (docs/specs/PROFILE_PLAN.md §5.5, P2).
			return BandBuilding.build(entry, atlas, art);
		}
		Template columns = columns(entry, null, 1.0);
		int artPx = art.artPx();
		List<Integer> tiles = new ArrayList<>();
		Map<Integer, int[]> shades = new HashMap<>();
		for (int tile : entry.tiles()) {
			if (tile < 0 || shades.containsKey(tile)) {
				continue;
			}
			int[] s = art.tileShades(atlas, tile);
			if (s == null) {
				// A tile this art lacks: no template, the columns.
				return columns;
			}
			shades.put(tile, s);
			tiles.add(tile);
		}
		for (ShapeKey[] column : entry.columns()) {
			if (column != null) {
				for (ShapeKey key : column) {
					for (int tile : backTiles(key)) {
						if (!shades.containsKey(tile) && art.tileShades(atlas, tile) != null) {
							shades.put(tile, art.tileShades(atlas, tile));
							tiles.add(tile);
						}
					}
				}
			}
		}
		Map<Integer, Integer> slots = new HashMap<>();
		for (int i = 0; i < tiles.size(); i++) {
			slots.put(tiles.get(i), i);
		}
		Drawing drawing = Drawing.of(entry, slots, shades, artPx);
		List<Run> runs = runs(entry);
		int top = Integer.MAX_VALUE;
		for (Run run : runs) {
			top = Math.min(top, run.n());
		}
		int roofRows = overrides.roofRows(atlas, entry.sig());
		if (roofRows < 0) {
			int paneTop = drawing.paneTopRow();
			if (paneTop < 0) {
				return columns;
			}
			roofRows = paneTop - top;
		}
		if (roofRows < 1 || runs.isEmpty()) {
			return columns;
		}
		Building building = building(entry, drawing, slots, runs, top, roofRows, artPx);
		Map<Integer, List<ShapeQuad>> blocks = new TreeMap<>();
		for (Map.Entry<Integer, List<ShapeQuad>> e : QuadMerger.merge(building.grid(), QuadMerger.ALL_FACES, 0).entrySet()) {
			List<ShapeQuad> quads = new ArrayList<>(e.getValue().size());
			for (ShapeQuad quad : e.getValue()) {
				quads.add(quad.withTexture(tiles.get(quad.texture())));
			}
			blocks.put(e.getKey(), quads);
		}
		int quads = maxQuads(blocks);
		double match = check(blocks, drawing, building, slots, entry, artPx);
		if (quads > CellUnits.MAX_QUADS || match < MATCH) {
			return columns(entry, Kind.BUILDING, match);
		}
		return new Template(Kind.BUILDING, null, match, quads, blocks, null, building.grid(), tiles.stream().mapToInt(Integer::intValue).toArray(),
				roofRows);
	}

	/**
	 * The template's Tier A columns: per position its keys' geometry, as a tile-id texture; for a band building (P2) the
	 * plain cube of each claimed solid tile, its volume high.
	 */
	static Template columns(Templates.Entry entry, @Nullable Kind failed, double match) {
		Map<Integer, List<ShapeQuad>> blocks = new TreeMap<>();
		if (entry.band() != null && entry.band().has("solid")) {
			for (int y = 0; y < entry.d(); y++) {
				for (int x = 0; x < entry.w(); x++) {
					int tile = entry.tile(x, y);
					for (int k = 0; k < entry.blocks(x, y) && tile >= 0; k++) {
						blocks.put(QuadMerger.blockKey(x, k, y), KeyGeometry.cube(new int[] {tile, tile, tile, tile, tile, tile}, t -> t));
					}
				}
			}
			return new Template(Kind.COLUMNS, failed, match, maxQuads(blocks), blocks, null, null, null, -1);
		}
		for (int y = 0; y < entry.d(); y++) {
			for (int x = 0; x < entry.w(); x++) {
				ShapeKey[] column = entry.columns()[y * entry.w() + x];
				if (column == null) {
					continue;
				}
				for (int k = 0; k < column.length; k++) {
					List<ShapeQuad> quads = KeyGeometry.direct(column[k], tile -> tile);
					if (quads != null && !quads.isEmpty()) {
						blocks.put(QuadMerger.blockKey(x, k, y), quads);
					}
				}
			}
		}
		return new Template(Kind.COLUMNS, failed, match, maxQuads(blocks), blocks, null, null, null, -1);
	}

	/** The tiles a key's back and sides show (so the art of those faces is at hand). */
	private static int[] backTiles(ShapeKey key) {
		if (key.kind() != ShapeKey.Kind.CUBE) {
			return new int[0];
		}
		int[] out = new int[6];
		for (int i = 0; i < 6; i++) {
			out[i] = key.number(i);
		}
		return Arrays.stream(out).filter(t -> t >= 0).toArray();
	}

	/** The template's runs: per grid column, each vertical stretch of positions with a column. */
	static List<Run> runs(Templates.Entry entry) {
		List<Run> out = new ArrayList<>();
		int per = entry.perCell();
		for (int x = 0; x < entry.w(); x++) {
			int y = 0;
			while (y < entry.d()) {
				if (entry.blocks(x, y) == 0) {
					y++;
					continue;
				}
				int n = y;
				int blocks = 0;
				while (y < entry.d() && entry.blocks(x, y) > 0) {
					blocks = Math.max(blocks, entry.blocks(x, y));
					y++;
				}
				int f = y - 1;
				int origin = f + 1 < entry.d() + per && entry.door(x / per, (f + 1) / per) ? ((f + 1) / per) * per + per - 1 : f;
				out.add(new Run(x, n, f, Math.min(origin, entry.d() - 1), blocks));
			}
		}
		return out;
	}

	/** The composed drawing: per pixel its shade, texel, kind, and its background and pane flags. */
	record Drawing(int w, int h, int artPx, int[] shade, int[] texel, int[] kind, boolean[] background, boolean[] pane) {
		static Drawing of(Templates.Entry entry, Map<Integer, Integer> slots, Map<Integer, int[]> shades, int artPx) {
			int w = entry.w() * artPx;
			int h = entry.d() * artPx;
			int[] shade = new int[w * h];
			int[] texel = new int[w * h];
			int[] kind = new int[w * h];
			int per = entry.perCell();
			for (int py = 0; py < h; py++) {
				for (int px = 0; px < w; px++) {
					int i = py * w + px;
					int tx = px / artPx;
					int ty = py / artPx;
					int tile = entry.tile(tx, ty);
					if (tile < 0) {
						shade[i] = -1;
						texel[i] = VoxelGrid.EMPTY;
						kind[i] = OUTSIDE;
						continue;
					}
					shade[i] = shades.get(tile)[(py % artPx) * artPx + px % artPx];
					texel[i] = VoxelGrid.texel(slots.get(tile), px % artPx, py % artPx);
					kind[i] = entry.blocks(tx, ty) > 0 ? REGION : entry.door(tx / per, ty / per) ? DOOR : OUTSIDE;
				}
			}
			// Background: from the border and the tiles outside the structure, through the structure's light pixels.
			boolean[] background = new boolean[w * h];
			int[] queue = new int[w * h];
			int tail = 0;
			for (int i = 0; i < w * h; i++) {
				int px = i % w;
				int py = i / w;
				boolean edge = px == 0 || py == 0 || px == w - 1 || py == h - 1;
				if (kind[i] == OUTSIDE || (edge && kind[i] == REGION && light(shade[i]))) {
					background[i] = true;
					queue[tail++] = i;
				}
			}
			for (int head = 0; head < tail; head++) {
				int i = queue[head];
				int px = i % w;
				int py = i / w;
				int[] next = {py > 0 ? i - w : -1, px > 0 ? i - 1 : -1, px < w - 1 ? i + 1 : -1, py < h - 1 ? i + w : -1};
				for (int n : next) {
					if (n >= 0 && !background[n] && kind[n] == REGION && light(shade[n])) {
						background[n] = true;
						queue[tail++] = n;
					}
				}
			}
			// Panes: small enclosed light regions.
			boolean[] pane = new boolean[w * h];
			boolean[] seen = new boolean[w * h];
			for (int start = 0; start < w * h; start++) {
				if (seen[start] || kind[start] != REGION || background[start] || !light(shade[start])) {
					continue;
				}
				List<Integer> part = new ArrayList<>();
				seen[start] = true;
				part.add(start);
				for (int k = 0; k < part.size(); k++) {
					int i = part.get(k);
					int px = i % w;
					int py = i / w;
					int[] next = {py > 0 ? i - w : -1, px > 0 ? i - 1 : -1, px < w - 1 ? i + 1 : -1, py < h - 1 ? i + w : -1};
					for (int n : next) {
						if (n >= 0 && !seen[n] && kind[n] == REGION && !background[n] && light(shade[n])) {
							seen[n] = true;
							part.add(n);
						}
					}
				}
				if (part.size() >= PANE_MIN && part.size() <= PANE_MAX) {
					for (int i : part) {
						pane[i] = true;
					}
				}
			}
			return new Drawing(w, h, artPx, shade, texel, kind, background, pane);
		}

		/** Part of what is drawn: a pixel of the structure's tiles or of its doors. */
		boolean drawn(int px, int py) {
			if (px < 0 || py < 0 || px >= this.w || py >= this.h) {
				return false;
			}
			return this.kind[py * this.w + px] != OUTSIDE;
		}

		int texel(int px, int py) {
			return this.texel[py * this.w + px];
		}

		/** The highest tile row holding a pane pixel, -1 without panes. */
		int paneTopRow() {
			for (int i = 0; i < this.pane.length; i++) {
				if (this.pane[i]) {
					return i / this.w / this.artPx;
				}
			}
			return -1;
		}
	}

	private static boolean light(int shade) {
		return shade >= 0 && shade <= 1;
	}

	/** The voxels and, per run, where its facade ends. */
	record Building(VoxelGrid grid, Map<Run, Integer> facadeTops) {
	}

	private static Building building(Templates.Entry entry, Drawing drawing, Map<Integer, Integer> slots, List<Run> runs, int top, int roofRows,
			int artPx) {
		int height = entry.height();
		VoxelGrid grid = new VoxelGrid(entry.w() * artPx, height * artPx, entry.d() * artPx, artPx);
		Map<Run, Integer> facadeTops = new HashMap<>();
		int roofTop = (top + roofRows) * artPx;
		for (Run run : runs) {
			int zFront = run.f() * artPx + artPx - 1;
			int zBack = run.n() * artPx;
			int facadeTop = Math.max(artPx, Math.min((run.f() - top - roofRows + 1) * artPx, run.blocks() * artPx - 1));
			facadeTops.put(run, facadeTop);
			for (int px = run.x() * artPx; px < (run.x() + 1) * artPx; px++) {
				for (int y = 0; y < facadeTop; y++) {
					int row = run.origin() * artPx + artPx - 1 - y;
					if (row < roofTop) {
						// The rows above belong to the roof.
						break;
					}
					if (row < 0 || !drawing.drawn(px, row)) {
						continue;
					}
					int i = row * drawing.w() + px;
					int zStart = drawing.pane()[i] ? zFront - 1 : zFront;
					for (int z = zBack; z <= zStart; z++) {
						grid.set(px, y, z, drawing.texel(px, row));
					}
				}
				// The roof: one layer on the facade, at depth z showing the row it is seen at (z - y), cycled north of the drawing.
				for (int z = zBack; z <= zFront; z++) {
					int seen = roofRow(z - facadeTop - 1, top, roofRows, artPx);
					if (!drawing.drawn(px, seen)) {
						continue;
					}
					int texel = drawing.texel(px, seen);
					int south = z == zFront ? roofRow(z - facadeTop, top, roofRows, artPx) : seen;
					grid.set(px, facadeTop, z, drawing.drawn(px, south) ? drawing.texel(px, south) : texel);
					grid.setCap(px, facadeTop, z, texel);
				}
			}
		}
		// Sides and backs as Tier A shows them: west and east faces their band's tile upright, the back its plain tile.
		for (Run run : runs) {
			int facadeTop = facadeTops.get(run);
			int back = entry.tile(run.x(), Math.min(run.f(), run.n() + 1));
			int zFront = run.f() * artPx + artPx - 1;
			for (int px = run.x() * artPx; px < (run.x() + 1) * artPx; px++) {
				for (int y = 0; y < facadeTop; y++) {
					int band = entry.tile(run.x(), Math.max(run.n(), run.origin() - y / artPx));
					Integer bandSlot = slots.get(band);
					Integer backSlot = slots.get(back);
					for (int z = run.n() * artPx; z <= zFront; z++) {
						// A voxel the south sees keeps the drawing; the others show their side's art.
						if (!grid.solid(px, y, z) || !grid.solid(px, y, z + 1)) {
							continue;
						}
						if (!grid.solid(px - 1, y, z) && bandSlot != null) {
							grid.set(px, y, z, VoxelGrid.texel(bandSlot, z % artPx, artPx - 1 - y % artPx));
						} else if (!grid.solid(px + 1, y, z) && bandSlot != null) {
							grid.set(px, y, z, VoxelGrid.texel(bandSlot, artPx - 1 - z % artPx, artPx - 1 - y % artPx));
						} else if (!grid.solid(px, y, z - 1) && backSlot != null) {
							grid.set(px, y, z, VoxelGrid.texel(backSlot, artPx - 1 - px % artPx, artPx - 1 - y % artPx));
						}
					}
				}
			}
		}
		return new Building(grid, facadeTops);
	}

	/** The drawing row seen at screen row {@code row}: itself inside the drawing, the roof rows cycled north of it. */
	static int roofRow(int row, int top, int roofRows, int artPx) {
		int first = top * artPx;
		if (row >= first) {
			return row;
		}
		int cycleStart = roofRows >= 3 ? first + artPx : first;
		int period = (roofRows >= 3 ? roofRows - 2 : roofRows) * artPx;
		return cycleStart + Math.floorMod(row - cycleStart, period);
	}

	/**
	 * The share of the structure's pixels seen as drawn from the game's view (z - y) over its floor, by texel identity; a
	 * folded door's run may show the door's rows in its facade.
	 */
	private static double check(Map<Integer, List<ShapeQuad>> blocks, Drawing drawing, Building building, Map<Integer, Integer> slots,
			Templates.Entry entry, int artPx) {
		int w = drawing.w();
		int h = drawing.h();
		int lift = entry.height() * artPx;
		int rows = h + lift;
		float[] near = new float[w * rows];
		Arrays.fill(near, Float.NEGATIVE_INFINITY);
		int[] shown = new int[w * rows];
		Arrays.fill(shown, -1);
		int unitsPerPixel = 16 / artPx;
		// The floor under the structure: each position's own tile, as Tier A leaves it (what a carved pixel shows).
		Map<Integer, List<ShapeQuad>> floor = new TreeMap<>();
		for (int y = 0; y < entry.d(); y++) {
			for (int x = 0; x < entry.w(); x++) {
				if (entry.blocks(x, y) > 0) {
					floor.put(x | y << 16, List.of(ShapeQuad.fullFace(ShapeQuad.UP, entry.tile(x, y))));
				}
			}
		}
		for (Map.Entry<Integer, List<ShapeQuad>> e : floor.entrySet()) {
			int bx = e.getKey() & 0xFFFF;
			int bz = e.getKey() >>> 16;
			for (ShapeQuad quad : e.getValue()) {
				SelfCheck.project(quad, bx, -1, bz, unitsPerPixel, (sx, sy, nearness, u, v) -> {
					int row = sy + lift;
					if (sx < 0 || row < 0 || sx >= w || row >= rows) {
						return;
					}
					int i = row * w + sx;
					if (nearness > near[i]) {
						near[i] = nearness;
						shown[i] = VoxelGrid.texel(slots.getOrDefault(quad.texture(), 0), SelfCheck.texelOf(u, artPx), SelfCheck.texelOf(v, artPx));
					}
				});
			}
		}
		for (Map.Entry<Integer, List<ShapeQuad>> e : blocks.entrySet()) {
			int bx = QuadMerger.blockX(e.getKey());
			int by = QuadMerger.blockY(e.getKey());
			int bz = QuadMerger.blockZ(e.getKey());
			for (ShapeQuad quad : e.getValue()) {
				if (quad.face() != ShapeQuad.UP && quad.face() != ShapeQuad.SOUTH && quad.face() != ShapeQuad.SLOPED) {
					continue;
				}
				SelfCheck.project(quad, bx, by, bz, unitsPerPixel, (sx, sy, nearness, u, v) -> {
					int row = sy + lift;
					if (sx < 0 || row < 0 || sx >= w || row >= rows) {
						return;
					}
					int i = row * w + sx;
					if (nearness > near[i]) {
						near[i] = nearness;
						shown[i] = VoxelGrid.texel(slots.getOrDefault(quad.texture(), 0), SelfCheck.texelOf(u, artPx), SelfCheck.texelOf(v, artPx));
					}
				});
			}
		}
		// What each screen pixel should show: the drawing, the door fold's shift inside a folded run's facade.
		int[] expected = new int[w * h];
		for (int i = 0; i < w * h; i++) {
			expected[i] = drawing.texel()[i];
		}
		for (Map.Entry<Run, Integer> e : building.facadeTops().entrySet()) {
			Run run = e.getKey();
			if (run.origin() == run.f()) {
				continue;
			}
			int zFront = run.f() * artPx + artPx - 1;
			for (int px = run.x() * artPx; px < (run.x() + 1) * artPx; px++) {
				for (int y = 0; y < e.getValue(); y++) {
					int screen = zFront - y;
					int row = run.origin() * artPx + artPx - 1 - y;
					if (screen >= 0 && screen < h && row >= 0 && row < h) {
						expected[screen * w + px] = drawing.texel(px, row);
					}
				}
			}
		}
		// Every pixel of the structure's tiles counts, carved ones too (the floor shows them).
		// A sunk pane shows each of its rows one row higher: at a pane pixel's place, the row below it.
		for (Map.Entry<Run, Integer> e : building.facadeTops().entrySet()) {
			Run run = e.getKey();
			int zFront = run.f() * artPx + artPx - 1;
			for (int px = run.x() * artPx; px < (run.x() + 1) * artPx; px++) {
				for (int y = 0; y < e.getValue(); y++) {
					int row = run.origin() * artPx + artPx - 1 - y;
					int screen = zFront - y;
					if (row < 0 || row + 1 >= h || screen < 0 || screen >= h || !drawing.pane()[row * w + px]) {
						continue;
					}
					expected[screen * w + px] = drawing.texel(px, row + 1);
				}
			}
		}
		long all = 0;
		long matched = 0;
		for (int py = 0; py < h; py++) {
			for (int px = 0; px < w; px++) {
				int i = py * w + px;
				if (drawing.kind()[i] != REGION) {
					continue;
				}
				all++;
				matched += shown[(py + lift) * w + px] == expected[i] ? 1 : 0;
			}
		}
		return all == 0 ? 1.0 : matched / (double) all;
	}

	/** A pack model fits when every block it fills lies in the template's volume. */
	private static boolean fits(ShapeOverrides.StructModel model, Templates.Entry entry) {
		for (Map.Entry<Integer, List<ShapeQuad>> e : model.blocks().entrySet()) {
			if (e.getValue().isEmpty()) {
				continue;
			}
			int bx = QuadMerger.blockX(e.getKey());
			int by = QuadMerger.blockY(e.getKey());
			int bz = QuadMerger.blockZ(e.getKey());
			if (bx >= entry.w() || bz >= entry.d() || by >= entry.blocks(bx, bz)) {
				return false;
			}
		}
		return true;
	}

	private static int maxQuads(Map<Integer, List<ShapeQuad>> blocks) {
		int max = 0;
		for (List<ShapeQuad> quads : blocks.values()) {
			max = Math.max(max, quads.size());
		}
		return max;
	}
}
