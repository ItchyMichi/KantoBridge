package dev.kantobridge.builder.shape.dressing;

import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.zone.ZoneBlock;
import dev.kantobridge.builder.zone.ZonePlan;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.SceneMap;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * The forest's apron (docs/specs/CAVES_AND_WOODS_PLAN.md §5.4, CW3): Kanto in First Person's ring of further wood, four
 * cells deep past a canopy wood's edge, re-implemented from its rules (nothing copied; REFERENCES.md) in Minecraft's own
 * logs and leaves (CD16), so none of it is in the shape map. Each ring cell mirrors the nearest map cell: a solid one as a
 * bush, some carried up taller; an open one now and then as a trunk up into the canopy. The canopy carries on over it, thin
 * (CD20); vines hang on its leaves; a moss wall closes it; the enclosure ring becomes an invisible wall.
 *
 * <p>Pixels here are Game Boy pixels, 8 to a block, from the floor; levels are blocks from feet; a tile is a block.
 */
public final class Apron {
	/** Cells of further wood past the map's edge (the mod's ring). */
	public static final int CELLS = 4;
	/** What a column of the apron's box holds: the map's own (none), the apron's wood, or the moss wall. */
	public static final byte NONE = 0;
	public static final byte WOOD = 1;
	public static final byte WALL = 2;
	/** The moss wall's top level: the canopy's upper layer. */
	public static final int WALL_TOP = Canopy.TOP_LEVEL - 1;
	/** The invisible wall's top level: the enclosure ring's (feet + 3). */
	public static final int BARRIER_TOP = WorldPlan.CEILING_ABOVE_FEET;
	/** The lowest level a vine reaches (CW4's rule: out of reach of a player who can't jump). */
	public static final int VINE_LOW = 2;
	/** Vines hang from leaves at these levels: above the invisible wall, below the upper layer. */
	static final int VINE_FROM = BARRIER_TOP + 1;
	// The mod's numbers: 62% of ring cells mirror the nearest map cell; a solid one is a 16-pixel tree, 16% of them with a
	// taller box 24..44 pixels; an open one gets a trunk 30% of the time; and the salt of each decision.
	static final double DENSITY = 0.62;
	static final double TALL_ODDS = 0.16;
	static final double TRUNK_ODDS = 0.30;
	static final int TREE_LEVELS = 2;
	static final double TALL_LOW = 24;
	static final double TALL_SPAN = 20;
	static final int SALT_RING = 149;
	static final int SALT_TALL = 151;
	static final int SALT_TALL_HEIGHT = 157;
	static final int SALT_TRUNK = 163;
	// Ours (CD22): a vine's pick per face and level, and its length, 2..4 blocks.
	static final int SALT_VINE = 307;
	static final int SALT_VINE_LENGTH = 349;
	static final int VINE_MIN = 2;
	static final int VINE_SPAN = 3;
	/** North, east, south, west, as {@link ZoneBlock#vineOn}. */
	static final int[] DX = {0, 1, 0, -1};
	static final int[] DZ = {-1, 0, 1, 0};

	private Apron() {
	}

	/**
	 * A map's apron, over the box of columns {@link #margin} tiles past the map on every side ({@code tx, ty} from the
	 * map's tile (0, 0); negative to the north and west).
	 *
	 * @param floorTile the tile the apron's floor blocks (feet - 1) wear
	 * @param kinds     per column of the box: {@link #NONE} (the map's own), {@link #WOOD} or {@link #WALL}
	 * @param blocks    per {@link #WOOD} column, its blocks by level from feet (null for air); null for the others
	 */
	public record Plan(int cols, int rows, int margin, int floorTile, byte[] kinds, ZoneBlock[][] blocks) {
		/** The box's width in columns. */
		public int boxCols() {
			return this.cols + 2 * this.margin;
		}

		/** Whether (tx, ty) lies in the box. */
		public boolean contains(int tx, int ty) {
			return tx >= -this.margin && tx < this.cols + this.margin && ty >= -this.margin && ty < this.rows + this.margin;
		}

		int index(int tx, int ty) {
			return (ty + this.margin) * this.boxCols() + tx + this.margin;
		}

		/** What column (tx, ty) holds; {@link #NONE} outside the box too. */
		public byte kind(int tx, int ty) {
			return this.contains(tx, ty) ? this.kinds[this.index(tx, ty)] : NONE;
		}

		/** A {@link #WOOD} column's blocks by level from feet (null for air), or null. */
		public ZoneBlock @Nullable [] column(int tx, int ty) {
			return this.contains(tx, ty) ? this.blocks[this.index(tx, ty)] : null;
		}

		/** The block at level k of column (tx, ty), or null. */
		public @Nullable ZoneBlock at(int tx, int ty, int k) {
			ZoneBlock[] column = this.column(tx, ty);
			return column == null || k < 0 || k >= column.length ? null : column[k];
		}
	}

	/**
	 * Plans the apron of a canopy wood ({@link Canopy} applies and found leaves), its floor in {@code floorTile}; counts
	 * its pieces in {@code tally} as {@code dress_apron_<name>}.
	 */
	public static Plan plan(SceneMap map, int floorTile, Map<String, Integer> tally) {
		int per = map.perCell();
		int cols = map.artCols();
		int rows = map.artRows();
		int depth = CELLS * per;
		int margin = depth + 1;
		int boxCols = cols + 2 * margin;
		byte[] kinds = new byte[boxCols * (rows + 2 * margin)];
		ZoneBlock[][] blocks = new ZoneBlock[kinds.length][];
		Plan plan = new Plan(cols, rows, margin, floorTile, kinds, blocks);
		for (int ty = -margin; ty < rows + margin; ty++) {
			for (int tx = -margin; tx < cols + margin; tx++) {
				int out = outside(tx, ty, cols, rows);
				if (out > 0) {
					int i = plan.index(tx, ty);
					kinds[i] = out <= depth ? WOOD : WALL;
					blocks[i] = out <= depth ? new ZoneBlock[Canopy.TOP_LEVEL] : null;
				}
			}
		}
		for (int cy = -CELLS; cy < map.h() + CELLS; cy++) {
			for (int cx = -CELLS; cx < map.w() + CELLS; cx++) {
				if (cx >= 0 && cy >= 0 && cx < map.w() && cy < map.h()) {
					continue;
				}
				cell(map, plan, cx, cy, tally);
			}
		}
		// The invisible wall: the enclosure ring keeps its collision where the apron leaves air (CD24).
		for (int ty = -1; ty <= rows; ty++) {
			for (int tx = -1; tx <= cols; tx++) {
				ZoneBlock[] column = plan.column(tx, ty);
				for (int k = 0; column != null && k <= BARRIER_TOP; k++) {
					if (column[k] == null) {
						column[k] = ZoneBlock.BARRIER;
					}
				}
			}
		}
		vines(plan, tally);
		return plan;
	}

	/** One ring cell: its canopy (CD20), and the nearest map cell mirrored (CD18, CD19). */
	private static void cell(SceneMap map, Plan plan, int cx, int cy, Map<String, Integer> tally) {
		int per = map.perCell();
		boolean oak = CellHash.hash01(cx, cy, Canopy.SALT_LEAF) < 0.5;
		ZoneBlock leaves = oak ? ZoneBlock.OAK_LEAVES : ZoneBlock.DARK_OAK_LEAVES;
		int low = Canopy.bottom(cx, cy) / 8;
		count(tally, Canopy.well(cx, cy) ? "apron_well" : "apron_cell");
		// The underside block, filled up to where the highest neighbour's underside starts (the mod's skirts); the upper
		// layer over all.
		int fill = low;
		for (int d = 0; d < 4; d++) {
			int nx = cx + DX[d];
			int ny = cy + DZ[d];
			if (nx >= -CELLS && ny >= -CELLS && nx < map.w() + CELLS && ny < map.h() + CELLS) {
				fill = Math.max(fill, Canopy.bottom(nx, ny) / 8 - 1);
			}
		}
		fill = Math.min(fill, Canopy.TOP_LEVEL - 2);
		for (int k = low; k <= fill; k++) {
			fillCell(plan, per, cx, cy, k, leaves);
		}
		fillCell(plan, per, cx, cy, Canopy.TOP_LEVEL - 1, leaves);
		if (CellHash.hash01(cx, cy, SALT_RING) > DENSITY) {
			return;
		}
		int sx = Math.max(0, Math.min(map.w() - 1, cx));
		int sy = Math.max(0, Math.min(map.h() - 1, cy));
		CellKind source = map.kind(sx, sy);
		if (source != CellKind.WALK && source != CellKind.LEDGE) {
			// A bush the size the game draws its trees; now and then carried up taller, for a treeline.
			int top = TREE_LEVELS;
			if (CellHash.hash01(cx, cy, SALT_TALL) < TALL_ODDS) {
				top = (int) Math.round((TALL_LOW + TALL_SPAN * CellHash.hash01(cx, cy, SALT_TALL_HEIGHT)) / 8);
				count(tally, "apron_tall");
			}
			for (int k = 0; k < top; k++) {
				fillCell(plan, per, cx, cy, k, leaves);
			}
			count(tally, "apron_bush");
		} else if (CellHash.hash01(cx, cy, SALT_TRUNK) < TRUNK_ODDS) {
			// Open ground out here too, with the odd trunk up into the canopy.
			int corner = Math.min(3, (int) Math.floor(4 * CellHash.hash01(cy, cx, SALT_TRUNK)));
			int tx = cx * per + Math.min(per - 1, corner & 1);
			int ty = cy * per + Math.min(per - 1, corner >> 1);
			ZoneBlock[] column = plan.column(tx, ty);
			for (int k = 0; k < low && k < Canopy.TOP_LEVEL - 1; k++) {
				column[k] = oak ? ZoneBlock.OAK_LOG : ZoneBlock.DARK_OAK_LOG;
			}
			count(tally, "apron_trunk");
		}
	}

	private static void fillCell(Plan plan, int per, int cx, int cy, int k, ZoneBlock block) {
		for (int dy = 0; dy < per; dy++) {
			for (int dx = 0; dx < per; dx++) {
				plan.column(cx * per + dx, cy * per + dy)[k] = block;
			}
		}
	}

	/**
	 * Vines on the apron's leaves (CD22): wherever a leaf has air beside it, one face in nine by the position hash, a vine
	 * on that face from the leaf's level two to four blocks down, stopping above anything there and at {@link #VINE_LOW}.
	 */
	private static void vines(Plan plan, Map<String, Integer> tally) {
		for (int ty = -plan.margin(); ty < plan.rows() + plan.margin(); ty++) {
			for (int tx = -plan.margin(); tx < plan.cols() + plan.margin(); tx++) {
				ZoneBlock[] column = plan.column(tx, ty);
				for (int k = Canopy.TOP_LEVEL - 2; column != null && k >= VINE_FROM; k--) {
					if (column[k] != null) {
						continue;
					}
					for (int d = 0; d < 4; d++) {
						if (!leaves(plan.at(tx + DX[d], ty + DZ[d], k)) || !CellHash.oneIn(tx, ty, SALT_VINE + 4 * k + d, ZonePlan.VINE_EVERY)) {
							continue;
						}
						int len = VINE_MIN + (int) Math.floor(VINE_SPAN * CellHash.hash01(tx, ty, SALT_VINE_LENGTH));
						for (int j = k; j > k - len && j >= VINE_LOW && column[j] == null; j--) {
							column[j] = ZoneBlock.vineOn(d);
						}
						count(tally, "apron_vine");
						break;
					}
				}
			}
		}
	}

	static boolean leaves(@Nullable ZoneBlock block) {
		return block == ZoneBlock.OAK_LEAVES || block == ZoneBlock.DARK_OAK_LEAVES;
	}

	/** How far (tx, ty) lies outside a map of {@code cols × rows} tiles, in tiles (Chebyshev); 0 inside. */
	static int outside(int tx, int ty, int cols, int rows) {
		int ox = tx < 0 ? -tx : tx >= cols ? tx - cols + 1 : 0;
		int oy = ty < 0 ? -ty : ty >= rows ? ty - rows + 1 : 0;
		return Math.max(ox, oy);
	}

	/**
	 * The apron's floor tile (CD23): the lowest-numbered grass tile the map shows on a cell that isn't water, else the map's
	 * ground ({@code ground}, -1 for none), else the first tile it shows on such a cell.
	 */
	public static int floorTile(SceneMap map, Set<Integer> grassTiles, int ground) {
		int grass = Integer.MAX_VALUE;
		int first = -1;
		for (int ty = 0; ty < map.artRows(); ty++) {
			for (int tx = 0; tx < map.artCols(); tx++) {
				if (map.kindAtTile(tx, ty) == CellKind.WATER) {
					continue;
				}
				int tile = map.tile(tx, ty);
				first = first < 0 ? tile : first;
				if (grassTiles.contains(tile)) {
					grass = Math.min(grass, tile);
				}
			}
		}
		return grass != Integer.MAX_VALUE ? grass : ground >= 0 ? ground : first;
	}

	private static void count(Map<String, Integer> tally, String name) {
		tally.merge("dress_" + name, 1, Integer::sum);
	}
}
