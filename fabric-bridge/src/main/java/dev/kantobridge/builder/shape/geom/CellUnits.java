package dev.kantobridge.builder.shape.geom;

import dev.kantobridge.builder.shape.SelfCheck;
import dev.kantobridge.scene.profile.Profile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Cell-sized units (docs/specs/VOXEL_BLOCKS_PLAN.md §5.4 stage 4, §5.5 Tier B): the geometry of one unit of
 * {@code perCell × perCell} tiles standing {@code perCell} blocks high, built from its drawing on each client (and in the
 * headless build, for counts and scores only).
 *
 * <pre>
 * art unknown        the column version (below)
 * void tile          every pixel the darkest shade: it never stands up; a plate 1/16 high wears it
 * every tile void    flat: plates only
 * a pinned standee   the profile's class (docs/specs/PROFILE_PLAN.md §5.4): cut out by its pool's background rule
 *                    ({@link ObjectRule#forcedBackground}), at its pool's depth, kept when it covers its drawing (cover,
 *                    not match: thick slabs may hide one another in the top-down view)
 * a pinned cylinder  a hull, tried even where the round test says no
 * a round mask       a {@link Hulls hull}, kept when its self-check passes: it covers enough of its mask (HULL_COVER)
 * an object          its {@link ObjectRule slabs}, kept when its self-check passes: its drawing shows exactly (OBJECT_MATCH)
 * otherwise          the column version: each block a cube folded from the unit's tiles as Tier A folds a run of perCell
 *                    rows (south and sides: the row k up from the front; top and bottom: the north row; back: row 1)
 * a block over MAX_QUADS quads fails its candidate, too
 * </pre>
 *
 * The self-check projects the unit and its floor as {@link SelfCheck} does, in isolation, and compares the pixels of the
 * thing drawn (its foreground) with the drawing by texel identity; the background shows the floor, which the planner
 * gives the unit's ground tile.
 */
public final class CellUnits {
	/** Quads one block of a unit may hold (§3.8); more fails the candidate. */
	public static final int MAX_QUADS = 96;
	/** An object's slabs stand exactly on their drawing: this share of its pixels must show the same texel. */
	public static final double OBJECT_MATCH = 0.95;
	/**
	 * A hull must cover this share of its mask in the game's view. A hull stands round in the middle of its cell, so its
	 * rows show higher than drawn (its front lies north of the cell's south edge) and its lowest rows show the floor in
	 * front of it: a whole crown covers about half to two thirds of its drawing. Below this, the hull is broken.
	 */
	public static final double HULL_COVER = 0.4;
	/**
	 * A hull's pixels showing the same texel, at least. For the same reason no pixel shows exactly where it is drawn, so
	 * this is recorded per unit (shape_selfcheck.json), not gated.
	 */
	public static final double HULL_MATCH = 0.0;

	/** What a unit became. */
	public enum Kind {
		HULL("hull"),
		OBJECT("object"),
		FLAT("flat"),
		COLUMNS("columns"),
		/** A pack's {@code .vox} model (V6). */
		MODEL("model"),
		/** A pinned stair's steps (P3). */
		STAIR("stair"),
		/** A pinned relief's raised pixels (P3). */
		RELIEF("relief"),
		/** A fitted voxel shape (docs/specs/FITTED_SHAPES.md section 10), painted from the drawing in front of it. */
		VOXELS("voxels");

		private final String id;

		Kind(String id) {
			this.id = id;
		}

		public String id() {
			return this.id;
		}
	}

	/**
	 * The planner's context of a unit, carried in its keys' variant
	 * ({@code g<sides>[i][r][s][t][_<class>][_k<params>][_bg<hex>]}): the sides bordering flat ground (a hex digit,
	 * {@link Background} bits; outdoors only the south bit), indoors, repeating vertically, a sign, a tree (the front end's
	 * foliage hint); then the class the profile pins on the unit (docs/specs/PROFILE_PLAN.md §5.4), its parameters (P3,
	 * §5.6: two decimal digits each) and the background shades it names ({@code prop_bg}, bit k = shade k).
	 *
	 * @param pinned     the profile's class for the unit ({@code cylinder}, a standee pool, {@code post}; from P3 a hull
	 *                   variant, a stair, a relief), or null
	 * @param background the shades {@code prop_bg} names as background, 0 for none
	 * @param params     the pinned class's parameters (0..99): a stump's cap rows; a can's cap, base, height, well and
	 *                   taper; a stair's or relief's height in pixels
	 */
	public record Context(int flatSides, boolean indoor, boolean repeatsVertically, boolean sign, boolean tree, @Nullable String pinned,
			int background, List<Integer> params) {
		public static final Context NONE = new Context(0, false, false, false, false);

		public Context {
			params = List.copyOf(params);
		}

		/** A context without a profile class. */
		public Context(int flatSides, boolean indoor, boolean repeatsVertically, boolean sign, boolean tree) {
			this(flatSides, indoor, repeatsVertically, sign, tree, null, 0, List.of());
		}

		/** A context with a profile class and its background, no parameters (P1). */
		public Context(int flatSides, boolean indoor, boolean repeatsVertically, boolean sign, boolean tree, @Nullable String pinned,
				int background) {
			this(flatSides, indoor, repeatsVertically, sign, tree, pinned, background, List.of());
		}

		/** The variant text; the inverse of {@link #parse}. */
		public String variant() {
			StringBuilder params = new StringBuilder();
			for (int value : this.params) {
				params.append(String.format(java.util.Locale.ROOT, "%02d", Math.max(0, Math.min(99, value))));
			}
			return "g" + Integer.toHexString(this.flatSides & 15) + (this.indoor ? "i" : "") + (this.repeatsVertically ? "r" : "") + (this.sign ? "s" : "")
					+ (this.tree ? "t" : "") + (this.pinned != null ? "_" + this.pinned : "") + (params.length() > 0 ? "_k" + params : "")
					+ ((this.background & 15) != 0 ? "_bg" + Integer.toHexString(this.background & 15) : "");
		}

		/** Reads a variant; anything unreadable is {@link #NONE}, an unknown suffix is left out. */
		public static Context parse(@Nullable String variant) {
			if (variant == null || variant.length() < 2 || variant.charAt(0) != 'g' || Character.digit(variant.charAt(1), 16) < 0) {
				return NONE;
			}
			String[] parts = variant.split("_");
			String flags = parts[0].substring(2);
			String pinned = null;
			int background = 0;
			List<Integer> params = new ArrayList<>();
			for (int k = 1; k < parts.length; k++) {
				String part = parts[k];
				if (part.length() == 3 && part.startsWith("bg") && Character.digit(part.charAt(2), 16) >= 0) {
					background = Character.digit(part.charAt(2), 16);
				} else if (Profile.CLASSES.containsKey(part) || dev.kantobridge.builder.shape.FittedShapes.VOXELS.equals(part)) {
					pinned = part;
				} else if (part.length() >= 3 && part.length() % 2 == 1 && part.charAt(0) == 'k' && part.substring(1).chars().allMatch(Character::isDigit)) {
					for (int d = 1; d < part.length(); d += 2) {
						params.add(Integer.parseInt(part.substring(d, d + 2)));
					}
				}
			}
			// A class whose name has an underscore (a stair) arrives in two parts.
			for (int k = 1; k + 1 < parts.length && pinned == null; k++) {
				if (Profile.CLASSES.containsKey(parts[k] + "_" + parts[k + 1])) {
					pinned = parts[k] + "_" + parts[k + 1];
				}
				if (k + 2 < parts.length && Profile.CLASSES.containsKey(parts[k] + "_" + parts[k + 1] + "_" + parts[k + 2])) {
					pinned = parts[k] + "_" + parts[k + 1] + "_" + parts[k + 2];
				}
			}
			return new Context(Character.digit(variant.charAt(1), 16), flags.contains("i"), flags.contains("r"), flags.contains("s"),
					flags.contains("t"), pinned, background, params);
		}
	}

	/**
	 * One unit's geometry.
	 *
	 * @param failed     the candidate that was built but failed its gate (the unit then draws as {@code kind}), or null
	 * @param match      the kept candidate's share of foreground pixels showing the same texel (1 for columns and flat)
	 * @param cover      the kept candidate's share of foreground pixels covered by it (1 for columns and flat)
	 * @param maxQuads   the most quads one of its blocks holds
	 * @param blocks     per block (index {@link #block}), its quads; a quad's texture is its tile's slot (0 .. perCell²-1),
	 *                   or for a model a colour index into {@code colours}
	 * @param foreground per pixel of the drawing: part of the thing drawn (columns: every pixel)
	 * @param colours    a model's colours (ARGB by index), null otherwise
	 * @param voxels     the voxels a hull or an object was built from (a pack template writes them as {@code .vox}), else
	 *                   null
	 */
	public record Unit(Kind kind, @Nullable Kind failed, double match, double cover, int maxQuads, List<List<ShapeQuad>> blocks,
			boolean[] foreground, int @Nullable [] colours, @Nullable VoxelGrid voxels) {
		public Unit(Kind kind, @Nullable Kind failed, double match, double cover, int maxQuads, List<List<ShapeQuad>> blocks,
				boolean[] foreground) {
			this(kind, failed, match, cover, maxQuads, blocks, foreground, null, null);
		}
	}

	/** A pack's model in place of a unit (V6): its blocks as they are, no self-check (the pack maker's own art). */
	public static Unit model(ShapeOverrides.Model model, int perCell, int artPx) {
		int w = perCell * artPx;
		boolean[] all = new boolean[w * w];
		Arrays.fill(all, true);
		List<List<ShapeQuad>> blocks = empty(perCell);
		for (int b = 0; b < Math.min(blocks.size(), model.blocks().size()); b++) {
			blocks.get(b).addAll(model.blocks().get(b));
		}
		return new Unit(Kind.MODEL, null, 1.0, 1.0, maxQuads(blocks), blocks, all, model.colours().clone(), null);
	}

	private CellUnits() {
	}

	/** A block's index in {@link Unit#blocks()}: x east, y up, z south, each 0 .. perCell-1. */
	public static int block(int perCell, int bx, int by, int bz) {
		return (by * perCell + bz) * perCell + bx;
	}

	/**
	 * Builds a unit.
	 *
	 * @param tiles its tiles' shades ({@code artPx²} each), row-major; null for a tile this art doesn't have
	 */
	public static Unit build(int @Nullable [][] tiles, Context context, int perCell, int artPx) {
		return build(tiles, context, perCell, artPx, MAX_QUADS);
	}

	/** {@link #build} with another cap on a block's quads (the budget tests). */
	static Unit build(int @Nullable [][] tiles, Context context, int perCell, int artPx, int maxQuads) {
		return build(tiles, context, perCell, artPx, maxQuads, null, 0);
	}

	/**
	 * {@link #build} as a pack's rule asks (V6): {@code style} {@code hull} or {@code object} tries only that candidate
	 * (a hull even where the round test says no), {@code columns} draws the column version, null tries both in the
	 * generated order; {@code depth} is an object's slab depth in pixels, 0 for {@link ObjectRule#DEPTH}. The gates
	 * stay, so a rule can never leave a hole.
	 */
	public static Unit build(int @Nullable [][] tiles, Context context, int perCell, int artPx, int maxQuads, @Nullable String style, int depth) {
		int w = perCell * artPx;
		// A pack's tree or prop style (V6) doesn't reach a stair or a relief (P3): neither, and both stand on walkable ground.
		String pinnedClass = context.pinned();
		boolean walkable = pinnedClass != null && (pinnedClass.startsWith("stair_") || "relief".equals(pinnedClass));
		if (walkable) {
			style = null;
		}
		if ("columns".equals(style)) {
			return columns(perCell, w);
		}
		for (int[] tile : tiles) {
			if (tile == null) {
				return columns(perCell, w);
			}
		}
		int[] shades = new int[w * w];
		int[] texels = new int[w * w];
		boolean[] skip = new boolean[w * w];
		boolean[] voidTile = new boolean[tiles.length];
		boolean allVoid = true;
		for (int t = 0; t < tiles.length; t++) {
			boolean dark = true;
			for (int shade : tiles[t]) {
				dark &= shade == Hulls.OUTLINE;
			}
			voidTile[t] = dark;
			allVoid &= dark;
		}
		for (int y = 0; y < w; y++) {
			for (int x = 0; x < w; x++) {
				int t = (y / artPx) * perCell + x / artPx;
				int i = y * w + x;
				shades[i] = tiles[t][(y % artPx) * artPx + x % artPx];
				texels[i] = VoxelGrid.texel(t, x % artPx, y % artPx);
				skip[i] = voidTile[t];
			}
		}
		if (allVoid) {
			List<List<ShapeQuad>> blocks = empty(perCell);
			plates(blocks, voidTile, perCell);
			boolean[] all = new boolean[w * w];
			Arrays.fill(all, true);
			return new Unit(Kind.FLAT, null, 1.0, 1.0, 1, blocks, all);
		}
		if (style == null && context.pinned() != null) {
			Unit pinned = pinnedP3(context.pinned(), context.params(), shades, texels, skip, voidTile, w, perCell, artPx, maxQuads);
			if (pinned != null) {
				return pinned;
			}
		}
		Kind failed = null;
		double[] last = null;
		// The profile's class (docs/specs/PROFILE_PLAN.md §5.4): a pinned standee is cut out by its pool, without the
		// object test; a pinned round thing is a hull. A pack's rule still wins (V6).
		ObjectRule.Pool pool = ObjectRule.pool(context.pinned());
		if (pool != null && (style == null || style.equals("object"))) {
			boolean[] background = ObjectRule.forcedBackground(shades, skip, w, w, pool, context.background());
			boolean[] drawn = ObjectRule.foreground(background, skip);
			if (pool == ObjectRule.Pool.CUTOUT) {
				drawn = ObjectRule.largest(drawn, w, w);
			}
			VoxelGrid grid = ObjectRule.slabs(drawn, texels, w, w, w, artPx, depth > 0 ? depth : ObjectRule.poolDepth(context.pinned()));
			List<List<ShapeQuad>> blocks = slice(grid, perCell);
			plates(blocks, voidTile, perCell);
			boolean[] foreground = or(drawn, skip);
			double[] score = check(blocks, foreground, texels, perCell, artPx);
			int quads = maxQuads(blocks);
			// A pinned standee's gate is cover, not match: its thick slabs may hide one another in the game's top-down view (a
			// nearer piece's top over a farther one), which is no hole; it must not leave its drawing to the floor.
			if (Background.count(drawn) > 0 && score[1] >= OBJECT_MATCH && quads <= maxQuads) {
				return new Unit(Kind.OBJECT, null, score[0], score[1], quads, blocks, foreground, null, grid);
			}
			Unit columns = columns(perCell, w);
			return new Unit(Kind.COLUMNS, Kind.OBJECT, score[0], score[1], columns.maxQuads(), columns.blocks(), columns.foreground());
		}
		if (style == null && "cylinder".equals(context.pinned())) {
			style = "hull";
		}
		boolean tryHull = style == null ? !context.sign() : style.equals("hull");
		if (tryHull) {
			boolean[] mask = Hulls.mask(shades, skip, w, w);
			if ((style != null && Background.count(mask) > 0) || Hulls.round(mask, w, w)) {
				VoxelGrid grid = Hulls.hull(mask, texels, w, w, w, artPx);
				List<List<ShapeQuad>> blocks = slice(grid, perCell);
				plates(blocks, voidTile, perCell);
				boolean[] foreground = or(mask, skip);
				double[] score = check(blocks, foreground, texels, perCell, artPx);
				int quads = maxQuads(blocks);
				if (score[1] >= HULL_COVER && score[0] >= HULL_MATCH && quads <= maxQuads) {
					return new Unit(Kind.HULL, null, score[0], score[1], quads, blocks, foreground, null, grid);
				}
				failed = Kind.HULL;
				last = score;
			}
		}
		int seeds = context.indoor() ? context.flatSides() : context.flatSides() & Background.SOUTH;
		boolean[] background = Background.of(shades, w, w, seeds);
		boolean tryObject = style == null
				? ObjectRule.applies(background, skip, perCell, artPx, context.flatSides(), context.indoor(), context.repeatsVertically())
				: style.equals("object");
		if (tryObject) {
			boolean[] drawn = ObjectRule.foreground(background, skip);
			VoxelGrid grid = ObjectRule.slabs(drawn, texels, w, w, w, artPx, depth > 0 ? depth : ObjectRule.DEPTH);
			List<List<ShapeQuad>> blocks = slice(grid, perCell);
			plates(blocks, voidTile, perCell);
			boolean[] foreground = or(drawn, skip);
			double[] score = check(blocks, foreground, texels, perCell, artPx);
			int quads = maxQuads(blocks);
			if (score[0] >= OBJECT_MATCH && quads <= maxQuads) {
				return new Unit(Kind.OBJECT, failed, score[0], score[1], quads, blocks, foreground, null, grid);
			}
			failed = Kind.OBJECT;
			last = score;
		}
		Unit columns = columns(perCell, w);
		return failed == null ? columns
				: new Unit(Kind.COLUMNS, failed, last[0], last[1], columns.maxQuads(), columns.blocks(), columns.foreground());
	}

	/**
	 * P3's pinned units (docs/specs/PROFILE_PLAN.md §5.6, {@link HullVariants}), or null for a class they don't cover: a
	 * stump or a can gated as a hull is; a stair or a relief stands on walkable ground, so over the quad cap it draws
	 * nothing rather than columns across the way.
	 */
	private static @Nullable Unit pinnedP3(String pinned, List<Integer> params, int[] shades, int[] texels, boolean[] skip, boolean[] voidTile, int w,
			int perCell, int artPx, int maxQuads) {
		int p0 = params.isEmpty() ? 0 : params.get(0);
		switch (pinned) {
			case "stump", "can" -> {
				boolean[] mask = Hulls.mask(shades, skip, w, w);
				VoxelGrid grid = "stump".equals(pinned) ? HullVariants.stump(mask, texels, w, artPx, p0)
						: HullVariants.can(mask, texels, w, artPx, param(params, 0), param(params, 1), param(params, 2), param(params, 3), param(params, 4));
				List<List<ShapeQuad>> blocks = slice(grid, perCell);
				plates(blocks, voidTile, perCell);
				boolean[] foreground = or(mask, skip);
				double[] score = check(blocks, foreground, texels, perCell, artPx);
				int quads = maxQuads(blocks);
				if (Background.count(mask) > 0 && score[1] >= HULL_COVER && quads <= maxQuads) {
					return new Unit(Kind.HULL, null, score[0], score[1], quads, blocks, foreground, null, grid);
				}
				Unit columns = columns(perCell, w);
				return new Unit(Kind.COLUMNS, Kind.HULL, score[0], score[1], columns.maxQuads(), columns.blocks(), columns.foreground());
			}
			case "stair_e", "stair_w", "stair_n", "stair_s", "relief" -> {
				boolean relief = "relief".equals(pinned);
				int rise = Math.max(1, p0 / 4);
				VoxelGrid grid = relief ? HullVariants.relief(shades, texels, skip, w, artPx, p0)
						: "stair_n".equals(pinned) || "stair_s".equals(pinned) ? HullVariants.stairsAlong(texels, w, artPx, "stair_s".equals(pinned), rise)
						: HullVariants.stairs(texels, w, artPx, "stair_e".equals(pinned), rise);
				List<List<ShapeQuad>> blocks = slice(grid, perCell);
				int quads = maxQuads(blocks);
				boolean[] all = new boolean[w * w];
				Arrays.fill(all, true);
				Kind kind = relief ? Kind.RELIEF : Kind.STAIR;
				if (quads <= maxQuads) {
					return new Unit(kind, null, 1.0, 1.0, quads, blocks, all, null, grid);
				}
				return new Unit(Kind.FLAT, kind, 0.0, 0.0, 0, empty(perCell), all);
			}
			default -> {
				return null;
			}
		}
	}

	private static int param(List<Integer> params, int k) {
		return k < params.size() ? params.get(k) : 0;
	}

	/**
	 * A fitted voxel shape (docs/specs/FITTED_SHAPES.md section 10): {@code occupancy} (x east, y up, z south, index
	 * {@code (y * w + z) * w + x}) as voxels, each painted with the drawing's pixel in front of it (column x, row
	 * w - 1 - y), or the nearest drawn pixel in that row, else in that column. No pixel decides the shape, only its
	 * colours, as with the hulls. Null when a tile is missing, nothing stands, or a block holds more than
	 * {@code maxQuads} quads (the planner's other builders then decide).
	 */
	public static @Nullable Unit voxels(int @Nullable [][] tiles, boolean[] occupancy, int perCell, int artPx, int maxQuads) {
		int w = perCell * artPx;
		if (occupancy.length != w * w * w) {
			return null;
		}
		for (int[] tile : tiles) {
			if (tile == null) {
				return null;
			}
		}
		int[] texels = new int[w * w];
		boolean[] drawn = new boolean[w * w];
		for (int y = 0; y < w; y++) {
			for (int x = 0; x < w; x++) {
				int t = (y / artPx) * perCell + x / artPx;
				int shade = tiles[t][(y % artPx) * artPx + x % artPx];
				texels[y * w + x] = VoxelGrid.texel(t, x % artPx, y % artPx);
				drawn[y * w + x] = shade >= 0;
			}
		}
		VoxelGrid grid = new VoxelGrid(w, w, w, artPx);
		boolean[] foreground = new boolean[w * w];
		int solid = 0;
		for (int y = 0; y < w; y++) {
			int row = w - 1 - y;
			for (int x = 0; x < w; x++) {
				int source = nearestDrawn(drawn, w, row, x);
				for (int z = 0; z < w; z++) {
					if (occupancy[(y * w + z) * w + x] && source >= 0) {
						grid.set(x, y, z, texels[source]);
						foreground[row * w + x] = true;
						solid++;
					}
				}
			}
		}
		if (solid == 0) {
			return null;
		}
		List<List<ShapeQuad>> blocks = slice(grid, perCell);
		int quads = maxQuads(blocks);
		if (quads > maxQuads) {
			return null;
		}
		double[] score = check(blocks, foreground, texels, perCell, artPx);
		return new Unit(Kind.VOXELS, null, score[0], score[1], quads, blocks, foreground, null, grid);
	}

	/** The pixel (row * w + col) a voxel column at (row, col) is painted with: itself if drawn, else the nearest drawn. */
	private static int nearestDrawn(boolean[] drawn, int w, int row, int col) {
		if (drawn[row * w + col]) {
			return row * w + col;
		}
		for (int d = 1; d < w; d++) {
			for (int c : new int[] {col - d, col + d}) {
				if (c >= 0 && c < w && drawn[row * w + c]) {
					return row * w + c;
				}
			}
		}
		for (int d = 1; d < w; d++) {
			for (int r : new int[] {row - d, row + d}) {
				if (r >= 0 && r < w && drawn[r * w + col]) {
					return r * w + col;
				}
			}
		}
		return -1;
	}

	/** The column version: every block a full cube folded from the unit's tiles. */
	static Unit columns(int perCell, int w) {
		List<List<ShapeQuad>> blocks = empty(perCell);
		int front = perCell - 1;
		int back = Math.min(front, 1);
		for (int by = 0; by < perCell; by++) {
			for (int bz = 0; bz < perCell; bz++) {
				for (int bx = 0; bx < perCell; bx++) {
					int up = bx;
					int south = (front - Math.min(by, front)) * perCell + bx;
					int[] faces = {up, up, back * perCell + bx, south, south, south};
					blocks.get(block(perCell, bx, by, bz)).addAll(KeyGeometry.cube(faces, slot -> slot));
				}
			}
		}
		boolean[] all = new boolean[w * w];
		Arrays.fill(all, true);
		return new Unit(Kind.COLUMNS, null, 1.0, 1.0, 6, blocks, all);
	}

	private static List<List<ShapeQuad>> empty(int perCell) {
		List<List<ShapeQuad>> blocks = new ArrayList<>();
		for (int i = 0; i < perCell * perCell * perCell; i++) {
			blocks.add(new ArrayList<>());
		}
		return blocks;
	}

	/** The voxels' merged quads, per block of the unit. */
	private static List<List<ShapeQuad>> slice(VoxelGrid grid, int perCell) {
		List<List<ShapeQuad>> blocks = empty(perCell);
		for (Map.Entry<Integer, List<ShapeQuad>> e : QuadMerger.merge(grid, QuadMerger.ALL_FACES, 0).entrySet()) {
			int key = e.getKey();
			blocks.get(block(perCell, QuadMerger.blockX(key), QuadMerger.blockY(key), QuadMerger.blockZ(key))).addAll(e.getValue());
		}
		return blocks;
	}

	/** A void tile's plate: its top 1/16 above the floor, in the ground-level block over the tile. */
	private static void plates(List<List<ShapeQuad>> blocks, boolean[] voidTile, int perCell) {
		for (int t = 0; t < voidTile.length; t++) {
			if (voidTile[t]) {
				blocks.get(block(perCell, t % perCell, 0, t / perCell)).add(ShapeQuad.face(ShapeQuad.UP, 0, 1, 0, 16, 1, 16, t, 0, 0, 16, 16, -1, 0));
			}
		}
	}

	private static boolean[] or(boolean[] a, boolean[] b) {
		boolean[] out = new boolean[a.length];
		for (int i = 0; i < out.length; i++) {
			out[i] = a[i] || b[i];
		}
		return out;
	}

	private static int maxQuads(List<List<ShapeQuad>> blocks) {
		int max = 0;
		for (List<ShapeQuad> quads : blocks) {
			max = Math.max(max, quads.size());
		}
		return max;
	}

	/**
	 * The unit's self-check: {match, cover} over the foreground. The unit's quads and a floor under it are projected
	 * ({@link SelfCheck#project}), the nearest sample wins each pixel; a foreground pixel matches when it shows its own
	 * texel and is covered when anything but the floor shows there.
	 */
	static double[] check(List<List<ShapeQuad>> blocks, boolean[] foreground, int[] texels, int perCell, int artPx) {
		int w = perCell * artPx;
		float[] near = new float[w * w];
		Arrays.fill(near, Float.NEGATIVE_INFINITY);
		int[] shown = new int[w * w];
		Arrays.fill(shown, -1);
		boolean[] floor = new boolean[w * w];
		int unitsPerPixel = 16 / artPx;
		int floorSlot = perCell * perCell;
		for (int bz = 0; bz < perCell; bz++) {
			for (int bx = 0; bx < perCell; bx++) {
				project(ShapeQuad.fullFace(ShapeQuad.UP, floorSlot), bx, -1, bz, true, w, artPx, unitsPerPixel, near, shown, floor);
			}
		}
		for (int by = 0; by < perCell; by++) {
			for (int bz = 0; bz < perCell; bz++) {
				for (int bx = 0; bx < perCell; bx++) {
					for (ShapeQuad quad : blocks.get(block(perCell, bx, by, bz))) {
						if (quad.face() == ShapeQuad.UP || quad.face() == ShapeQuad.SOUTH || quad.face() == ShapeQuad.SLOPED) {
							project(quad, bx, by, bz, false, w, artPx, unitsPerPixel, near, shown, floor);
						}
					}
				}
			}
		}
		long all = 0;
		long matched = 0;
		long covered = 0;
		for (int i = 0; i < w * w; i++) {
			if (!foreground[i]) {
				continue;
			}
			all++;
			matched += shown[i] == texels[i] ? 1 : 0;
			covered += near[i] != Float.NEGATIVE_INFINITY && !floor[i] ? 1 : 0;
		}
		return all == 0 ? new double[] {1.0, 1.0} : new double[] {matched / (double) all, covered / (double) all};
	}

	private static void project(ShapeQuad quad, int bx, int by, int bz, boolean isFloor, int w, int artPx, int unitsPerPixel, float[] near,
			int[] shown, boolean[] floor) {
		SelfCheck.project(quad, bx, by, bz, unitsPerPixel, (sx, sy, nearness, u, v) -> {
			if (sx < 0 || sy < 0 || sx >= w || sy >= w) {
				return;
			}
			int i = sy * w + sx;
			if (nearness > near[i]) {
				near[i] = nearness;
				shown[i] = VoxelGrid.texel(quad.texture(), SelfCheck.texelOf(u, artPx), SelfCheck.texelOf(v, artPx));
				floor[i] = isFloor;
			}
		});
	}
}
