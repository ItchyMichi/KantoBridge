package dev.kantobridge.builder.shape.dressing;

import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.shape.ShapeKey;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.scene.SwapSite;
import java.util.HashSet;
import java.util.Set;

/**
 * Cave rock (docs/specs/CAVES_AND_WOODS_PLAN.md §5.1, CW1): Kanto in First Person's sagging roof, stalactites and
 * stalagmites, re-implemented from its rules (nothing copied; REFERENCES.md). Every roofed cell of a cave under a
 * ceiling gets a box hanging under the ceiling at a hashed depth, one cell in five a spike, all in the room's wall tile.
 * Heights are the mod's at its default four-block room scaled to our three (CD8), rounded to whole pixels; over a path
 * nothing comes below head height and no stalagmite stands (CD6, CD9); a stalagmite rises from a rock cell's top (CD10).
 *
 * <p>Pixels here are Game Boy pixels, 8 to a block, from the floor: the lid (the ceiling's underside) is at 24, the
 * pieces live in the block under it (level 2, pixels 16..24).
 */
public final class CaveRock implements HangingRule {
	/** The lid, in pixels above the floor. */
	static final int LID = 8 * WorldPlan.CEILING_ABOVE_FEET;
	/** The level the pieces take: the block under the ceiling. */
	public static final int LEVEL = WorldPlan.CEILING_ABOVE_FEET - 1;
	/** The level's floor, in pixels. */
	static final int LEVEL_FLOOR = 8 * LEVEL;
	/** The mod's heights are for its default room of 32 pixels; ours is {@link #LID} (CD8). */
	static final double SCALE = LID / 32.0;
	/** Head height over a path, in pixels (CD9). */
	public static final int CLEAR = 16;
	/** A spike shorter than this, in pixels, after its clamps is dropped. */
	static final int MIN_SPIKE = 2;
	/** Half-widths of a spike's base, in 1/16 block: 2.6 and 2.2 pixels. */
	static final int HALF_UP = 5;
	static final int HALF_DOWN = 4;
	// The mod's numbers: the rock 2..11 pixels under its lid, one cell in 5 with a spike 5..15 pixels long, 45% of
	// them standing, centred in the cell's inner 8 × 8 pixels; and the salt of each decision.
	static final double DROP_MIN = 2;
	static final double DROP_SPAN = 9;
	public static final int SPIKE_EVERY = 5;
	static final double LENGTH_MIN = 5;
	static final double LENGTH_SPAN = 10;
	static final double GROUND_ODDS = 0.45;
	static final int SALT_DROP = 241;
	static final int SALT_X = 251;
	static final int SALT_Z = 257;
	static final int SALT_SHADE = 263;
	public static final int SALT_SPIKE = 269;
	static final int SALT_LENGTH = 271;
	static final int SALT_GROUND = 277;

	@Override
	public boolean applies(SceneMap map) {
		return !map.surface() && !map.env().openAir() && map.env().cave();
	}

	@Override
	public void hang(SceneMap map, Dress dress) {
		if (dress.wallTile < 0) {
			return;
		}
		int per = map.perCell();
		int cols = map.artCols();
		Set<Integer> border = new HashSet<>();
		for (int tile : map.ownBorder()) {
			border.add(tile);
		}
		boolean[] swap = swapCells(map);
		for (int cy = 0; cy < map.h(); cy++) {
			for (int cx = 0; cx < map.w(); cx++) {
				if (swap[cy * map.w() + cx] || voidCell(map, border, cx, cy)) {
					continue;
				}
				int rock = rockAt(cx, cy);
				int tint = CellHash.hash01(cx, cy, SALT_SHADE) >= 0.5 ? 0 : 1;
				int y0 = 2 * (rock - LEVEL_FLOOR);
				Spike spike = CellHash.oneIn(cx, cy, SALT_SPIKE, SPIKE_EVERY) ? this.spike(map, dress, cx, cy, rock) : null;
				boolean any = false;
				boolean spiked = false;
				for (int dy = 0; dy < per; dy++) {
					for (int dx = 0; dx < per; dx++) {
						int i = (cy * per + dy) * cols + cx * per + dx;
						if (!dress.free(i, LEVEL)) {
							// A wall up to the ceiling, or something already there: not roofed.
							continue;
						}
						Spike own = spike != null && spike.dx == dx && spike.dy == dy ? spike : null;
						ShapeKey key = own == null ? ShapeKey.hang(dress.atlas, dress.wallTile, y0, ShapeKey.SPIKE_NONE, 0, 0, 0, 0, 0)
								: ShapeKey.hang(dress.atlas, dress.wallTile, y0, own.dir, own.base, own.apex, own.half, own.x, own.z);
						dress.hang(i, LEVEL, tint == 0 ? key : key.withTint(tint));
						if (own != null) {
							dress.count(own.dir == ShapeKey.SPIKE_UP ? "rock_stalagmite" : "rock_stalactite");
							spiked = true;
						}
						any = true;
					}
				}
				if (any) {
					dress.count("rock_roof");
				}
				if (spike != null && !spiked) {
					// Its block is not roofed (a wall up to the ceiling).
					dress.count("rock_spike_dropped");
				}
			}
		}
	}

	/** The rock's underside in a cell, in pixels from the floor: 16..22. */
	public static int rockAt(int cx, int cy) {
		long drop = Math.round(SCALE * (DROP_MIN + DROP_SPAN * CellHash.hash01(cx, cy, SALT_DROP)));
		return (int) Math.max(LEVEL_FLOOR, LID - drop);
	}

	/** A cell's spike, in its block: direction, base, apex, half-width and centre in 1/16 block. */
	record Spike(int dx, int dy, int dir, int base, int apex, int half, int x, int z) {
	}

	/** The cell's spike after its clamps, or null when it is dropped (counted). */
	private Spike spike(SceneMap map, Dress dress, int cx, int cy, int rock) {
		int length = (int) Math.round(SCALE * (LENGTH_MIN + LENGTH_SPAN * CellHash.hash01(cx, cy, SALT_LENGTH)));
		double px = 4 + 8 * CellHash.hash01(cx, cy, SALT_X);
		double pz = 4 + 8 * CellHash.hash01(cy, cx, SALT_Z);
		int per = map.perCell();
		int dx = Math.min(per - 1, (int) Math.floor(px / 8));
		int dy = Math.min(per - 1, (int) Math.floor(pz / 8));
		int x = (int) Math.round(2 * (px - 8 * dx));
		int z = (int) Math.round(2 * (pz - 8 * dy));
		int tx = cx * per + dx;
		int ty = cy * per + dy;
		CellKind kind = map.kindAtTile(tx, ty);
		boolean path = kind == CellKind.WALK || kind == CellKind.LEDGE || kind == CellKind.WATER;
		int top = 8 * dress.blocks[ty * map.artCols() + tx];
		if (CellHash.hash01(cx, cy, SALT_GROUND) < GROUND_ODDS && !path && top == LEVEL_FLOOR) {
			// A stalagmite on a rock cell's top, short of the rock by the mod's two pixels (scaled).
			int tip = Math.min(top + length, rock - (int) Math.round(2 * SCALE));
			if (tip - top < MIN_SPIKE) {
				dress.count("rock_spike_dropped");
				return null;
			}
			return new Spike(dx, dy, ShapeKey.SPIKE_UP, 0, 2 * (tip - LEVEL_FLOOR), HALF_UP, x, z);
		}
		int clear = path ? CLEAR : top + 1;
		int tip = Math.max(rock - length, Math.max(clear, LEVEL_FLOOR));
		if (rock - tip < MIN_SPIKE) {
			dress.count("rock_spike_dropped");
			return null;
		}
		return new Spike(dx, dy, ShapeKey.SPIKE_DOWN, 2 * (rock - LEVEL_FLOOR), 2 * (tip - LEVEL_FLOOR), HALF_DOWN, x, z);
	}

	/** The mod's void: a cell whose tiles are all the map's border-block tiles. */
	static boolean voidCell(SceneMap map, Set<Integer> border, int cx, int cy) {
		int per = map.perCell();
		for (int dy = 0; dy < per; dy++) {
			for (int dx = 0; dx < per; dx++) {
				if (!border.contains(map.tile(cx * per + dx, cy * per + dy))) {
					return false;
				}
			}
		}
		return true;
	}

	/** The cells a swap site changes: their closed art may stand in any level, so they get no hangings. */
	static boolean[] swapCells(SceneMap map) {
		boolean[] out = new boolean[map.w() * map.h()];
		for (SwapSite site : map.swapSites()) {
			for (SwapSite.SwapCell cell : site.cells()) {
				if (cell.x() >= 0 && cell.y() >= 0 && cell.x() < map.w() && cell.y() < map.h()) {
					out[cell.y() * map.w() + cell.x()] = true;
				}
			}
		}
		return out;
	}
}
