package dev.kantobridge.builder.shape.dressing;

import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.shape.ShapeKey;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.SceneMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A wood's leaf canopy and its vines (docs/specs/CAVES_AND_WOODS_PLAN.md §5.2-§5.3, CW2): Kanto in First Person's
 * canopy, light wells, leaf strands and stem-and-stub strands, re-implemented from its rules (nothing copied;
 * REFERENCES.md), at its own heights (the wood is open to the sky). Each cell's columns hold a mass of leaves from its
 * underside (40-62 pixels) up to the canopy's top; one cell in nine is a light well, showing the brighter upper layer
 * (74-80) instead (CD12, CD13). Strands hang from the underside, never below head height over a path or into a tree
 * (CD6, CD9); none hangs in a well or floats (CD14). The enclosure ring carries a leaf curtain up to the top.
 *
 * <p>Pixels here are Game Boy pixels, 8 to a block, from the floor; levels are blocks from feet.
 */
public final class Canopy implements HangingRule {
	/** The canopy's top, in pixels above the floor: its mass fills up to here. */
	public static final int TOP = 80;
	/** Levels the canopy may take: below this one. */
	public static final int TOP_LEVEL = TOP / 8;
	/** The curtain on the enclosure ring starts above the ring's own top (feet + 3). */
	public static final int CURTAIN_FROM = WorldPlan.CEILING_ABOVE_FEET + 1;
	/** The curtain's darkening (the mod's 0.30-0.44 rim). */
	static final int CURTAIN_TINT = 2;
	/** A strand shorter than this, in pixels, after its clamps is dropped. */
	static final int MIN_STRAND = 2;
	/** At most this many leaf tiles, the commonest first. */
	static final int PALETTE_MAX = 5;
	static final Set<String> LEAF_CLASSES = Set.of("tree", "wall", "cliff");
	// The mod's numbers: the lower surface 40..62 pixels, one cell in 9 a well, the upper layer 74..80; leaf strands in
	// one panel cell in 9, 28% long (to 3..7 pixels above the floor), else 7..22 long; stem-and-stub strands in one cell
	// in 5, 10..26 long; and the salt of each decision.
	static final double LOW = 40;
	static final double SPAN = 22;
	static final int GAP_EVERY = 9;
	static final double UPPER = 74;
	static final double UPPER_SPAN = 6;
	static final int VINE_EVERY = 9;
	static final double VINE_MIN = 7;
	static final double VINE_MAX = 22;
	static final double VINE_LONG = 0.28;
	static final double VINE_FLOOR = 3;
	static final int STRAND_EVERY = 5;
	static final double STRAND_MIN = 10;
	static final double STRAND_MAX = 26;
	static final int SALT_HEIGHT = 2;
	static final int SALT_GAP = 5;
	static final int SALT_LEAF = 23;
	static final int SALT_MOTTLE = 31;
	static final int SALT_UPPER = 61;
	static final int SALT_VINE = 163;
	static final int SALT_VINE_LENGTH = 167;
	static final int SALT_VINE_X = 173;
	static final int SALT_VINE_Z = 179;
	static final int SALT_VINE_WIDTH = 181;
	static final int SALT_VINE_SHADE = 191;
	static final int SALT_VINE_TURN = 193;
	static final int SALT_STUBS = 197;
	static final int SALT_STUB_AT = 199;
	static final int SALT_STUB_SIDE = 211;
	static final int SALT_LONG = 229;
	static final int SALT_LONG_JITTER = 233;
	static final int SALT_STRAND = 191;
	static final int SALT_STRAND_X = 197;
	static final int SALT_STRAND_Z = 199;
	static final int SALT_STRAND_LENGTH = 211;
	static final int SALT_STRAND_TURN = 223;

	@Override
	public boolean applies(SceneMap map) {
		return !map.surface() && map.env().openAir() && map.env().canopy();
	}

	@Override
	public void hang(SceneMap map, Dress dress) {
		List<Integer> palette = palette(map, dress);
		if (palette.isEmpty()) {
			dress.count("canopy_no_leaves");
			return;
		}
		int first = palette.get(0);
		dress.curtain = ShapeKey.cube(dress.atlas, first, first, first, first, first, first).withTint(CURTAIN_TINT);
		int per = map.perCell();
		int cols = map.artCols();
		boolean[] swap = CaveRock.swapCells(map);
		for (int cy = 0; cy < map.h(); cy++) {
			for (int cx = 0; cx < map.w(); cx++) {
				if (swap[cy * map.w() + cx]) {
					continue;
				}
				int leaf = palette.get((int) Math.floor(CellHash.hash01(cx, cy, SALT_LEAF) * palette.size()) % palette.size());
				boolean well = CellHash.oneIn(cx, cy, SALT_GAP, GAP_EVERY);
				double height = well ? UPPER + UPPER_SPAN * CellHash.hash01(cx, cy, SALT_UPPER) : LOW + SPAN * CellHash.hash01(cx, cy, SALT_HEIGHT);
				// Whole pixels, under the top: an upper layer at 79.9 still shows a sliver of leaves.
				int bottom = Math.min(TOP - 1, (int) Math.round(height));
				int tint = well ? 0 : tintOf(0.30 + 0.26 * (height - LOW) / SPAN + 0.22 * CellHash.hash01(cx, cy, SALT_MOTTLE));
				dress.count(well ? "canopy_well" : "canopy_cell");
				List<Strand> strands = well ? List.of() : this.strands(map, dress, cx, cy, bottom, leaf);
				int low = bottom / 8;
				for (int dy = 0; dy < per; dy++) {
					for (int dx = 0; dx < per; dx++) {
						int i = (cy * per + dy) * cols + cx * per + dx;
						boolean hollow = false;
						for (Strand strand : strands) {
							hollow |= strand.i == i && bottom % 8 != 0;
						}
						for (int k = low; k < TOP_LEVEL; k++) {
							if ((k == low && hollow) || !dress.free(i, k)) {
								// A strand's top block: the hollow it hangs from.
								continue;
							}
							ShapeKey key = k == low && bottom % 8 != 0
									? ShapeKey.hang(dress.atlas, leaf, 2 * (bottom - 8 * k), ShapeKey.SPIKE_NONE, 0, 0, 0, 0, 0)
									: ShapeKey.cube(dress.atlas, leaf, leaf, leaf, leaf, leaf, leaf);
							dress.hang(i, k, tint == 0 ? key : key.withTint(tint), true);
						}
					}
				}
				for (Strand strand : strands) {
					for (int b = 0; b < strand.keys.size(); b++) {
						int level = strand.topLevel - b;
						if (dress.free(strand.i, level)) {
							dress.hang(strand.i, level, strand.keys.get(b), true);
						}
					}
				}
			}
		}
	}

	/** A strand's tile, its top block's level, and its keys from the top block down. */
	record Strand(int i, int topLevel, List<ShapeKey> keys) {
	}

	/** The cell's strands: a leaf strand (one panel cell in 9) and a stem-and-stub strand (one in 5), clamped. */
	private List<Strand> strands(SceneMap map, Dress dress, int cx, int cy, int bottom, int leaf) {
		List<Strand> out = new ArrayList<>(2);
		if (CellHash.oneIn(cx, cy, SALT_VINE, VINE_EVERY)) {
			double len = CellHash.hash01(cx, cy, SALT_LONG) < VINE_LONG
					? Math.max(VINE_MIN, bottom - VINE_FLOOR - CellHash.hash01(cx, cy, SALT_LONG_JITTER) * 4)
					: VINE_MIN + CellHash.hash01(cx, cy, SALT_VINE_LENGTH) * (VINE_MAX - VINE_MIN);
			double x = 3 + 10 * CellHash.hash01(cx, cy, SALT_VINE_X);
			double z = 3 + 10 * CellHash.hash01(cy, cx, SALT_VINE_Z);
			int w = (int) Math.round(2 * (1.2 + 0.9 * CellHash.hash01(cx, cy, SALT_VINE_WIDTH)));
			int r = (int) Math.floor(CellHash.hash01(cx, cy, SALT_VINE_TURN) * 16) % 16;
			int tint = tintOf(0.40 + 0.26 * CellHash.hash01(cx, cy, SALT_VINE_SHADE));
			int stubs = 1 + (int) Math.floor(CellHash.hash01(cx, cy, SALT_STUBS) * 2);
			double[] at = new double[2];
			int[] side = new int[2];
			for (int s = 0; s < stubs; s++) {
				at[s] = 0.25 + 0.4 * CellHash.hash01(cx + s + 1, cy, SALT_STUB_AT);
				side[s] = CellHash.hash01(cx, cy + s + 1, SALT_STUB_SIDE) < 0.5 ? 0 : 1;
			}
			Strand vine = this.place(map, dress, cx, cy, bottom, len, x, z, (top, units, k, tx, tz) -> {
				int s1 = (int) Math.round(units * at[0]);
				int s2 = stubs == 2 ? (int) Math.round(units * at[1]) : 0;
				ShapeKey key = ShapeKey.vine(dress.atlas, leaf, tx, tz, w, r, top, units, k, stubs, s1, s2, side[0], stubs == 2 ? side[1] : 0);
				return tint == 0 ? key : key.withTint(tint);
			});
			if (vine != null) {
				out.add(vine);
				dress.count("vine");
			} else {
				dress.count("vine_dropped");
			}
		}
		if (CellHash.oneIn(cx, cy, SALT_STRAND, STRAND_EVERY)) {
			double len = STRAND_MIN + CellHash.hash01(cx, cy, SALT_STRAND_LENGTH) * (STRAND_MAX - STRAND_MIN);
			double x = 3 + 10 * CellHash.hash01(cx, cy, SALT_STRAND_X);
			double z = 3 + 10 * CellHash.hash01(cy, cx, SALT_STRAND_Z);
			int r = (int) Math.floor(CellHash.hash01(cx, cy, SALT_STRAND_TURN) * 16) % 16;
			Strand strand = this.place(map, dress, cx, cy, bottom, len, x, z, (top, units, k, tx, tz) -> ShapeKey.strand(dress.atlas, tx, tz, r, top,
					units, k));
			if (strand != null && !out.isEmpty() && out.get(0).i == strand.i) {
				// Its column already holds the leaf strand.
				dress.count("strand_crowded");
			} else if (strand != null) {
				out.add(strand);
				dress.count("strand");
			} else {
				dress.count("strand_dropped");
			}
		}
		return out;
	}

	@FunctionalInterface
	private interface KeyMaker {
		ShapeKey key(int top, int units, int k, int x, int z);
	}

	/**
	 * A strand at (x, z) pixels into the cell, hanging {@code len} pixels from the underside at {@code bottom}: from the
	 * hollow over its column when the underside lies inside a block, its tail clamped at head height over a path and at the
	 * column's top elsewhere; null when shorter than {@link #MIN_STRAND} after the clamp.
	 */
	private Strand place(SceneMap map, Dress dress, int cx, int cy, int bottom, double len, double x, double z, KeyMaker maker) {
		int per = map.perCell();
		int dx = Math.min(per - 1, (int) Math.floor(x / 8));
		int dy = Math.min(per - 1, (int) Math.floor(z / 8));
		int tx = cx * per + dx;
		int ty = cy * per + dy;
		int i = ty * map.artCols() + tx;
		CellKind kind = map.kindAtTile(tx, ty);
		boolean path = kind == CellKind.WALK || kind == CellKind.LEDGE || kind == CellKind.WATER;
		int clear = Math.max(path ? CaveRock.CLEAR : 0, 8 * dress.blocks[i]);
		int top = bottom % 8 == 0 ? bottom : 8 * (bottom / 8 + 1);
		int tail = (int) Math.max(Math.round(bottom - len), clear);
		if (top - tail < MIN_STRAND) {
			return null;
		}
		// In 1/16 block from the floor: its top, length, and the top block.
		int topUnits = 2 * top;
		int units = 2 * (top - tail);
		int topLevel = (topUnits - 1) / 16;
		int localTop = topUnits - 16 * topLevel;
		int lx = (int) Math.round(2 * (x - 8 * dx));
		int lz = (int) Math.round(2 * (z - 8 * dy));
		List<ShapeKey> keys = new ArrayList<>();
		for (int k = 0; localTop - units + 16 * k < 16; k++) {
			keys.add(maker.key(localTop, units, k, lx, lz));
		}
		return new Strand(i, topLevel, keys);
	}

	/**
	 * The wood's leaf tiles: every cell's top-left tile that the profile classes as a tree, wall or cliff, or that stands
	 * two blocks or more; by frequency (ties: the lower id), at most {@link #PALETTE_MAX}.
	 */
	static List<Integer> palette(SceneMap map, Dress dress) {
		Map<Integer, Integer> tally = new TreeMap<>();
		int per = map.perCell();
		for (int cy = 0; cy < map.h(); cy++) {
			for (int cx = 0; cx < map.w(); cx++) {
				int tx = cx * per;
				int ty = cy * per;
				String cls = dress.classes.at(tx, ty);
				if ((cls != null && LEAF_CLASSES.contains(cls)) || dress.blocks[ty * map.artCols() + tx] >= 2) {
					tally.merge(map.tile(tx, ty), 1, Integer::sum);
				}
			}
		}
		List<Map.Entry<Integer, Integer>> entries = new ArrayList<>(tally.entrySet());
		entries.sort(Comparator.comparing((Map.Entry<Integer, Integer> e) -> -e.getValue()).thenComparing(Map.Entry::getKey));
		List<Integer> out = new ArrayList<>();
		for (int e = 0; e < entries.size() && e < PALETTE_MAX; e++) {
			out.add(entries.get(e).getKey());
		}
		return out;
	}

	/** The mod's shade (a brightness, 0.30-0.82) as a tint step (0 brightest, 3 darkest). */
	static int tintOf(double shade) {
		return shade >= 0.72 ? 0 : shade >= 0.60 ? 1 : shade >= 0.48 ? 2 : 3;
	}
}
