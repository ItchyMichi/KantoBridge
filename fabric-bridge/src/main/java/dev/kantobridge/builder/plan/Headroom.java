package dev.kantobridge.builder.plan;

import dev.kantobridge.scene.ArtSource;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.world.MapLayout;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * How tall an interior is built (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D5, D8): its ceilings. From every floor cell the
 * hidden game draws the solid cells straight up the screen, its <b>chain</b>: two tile rows (two blocks) a cell, up to the
 * row the classic screen still shows above a player standing there, or the map's edge, or a cell drawn as one flat black
 * (the filler round a room, not drawing). A room's ceiling stands as high as its tallest chain over its floor (thick walls
 * count in full: the user's choice, plan §8) and never under {@link #MIN} blocks over its highest floor:
 * {@code max(depth, highest floor + MIN)}, one flat ceiling. A cave's follows its floor: each cell {@code max(MIN, the
 * tallest chain)} over its own floor. Pure Java.
 */
public final class Headroom {
	/** Blocks of air over the highest floor of any interior (the user's minimum, plan §0 V6). */
	public static final int MIN = 4;
	/** Tile rows the classic screen shows above the player: Gen 1 and 2 four cells, Gen 3 four and a half. */
	public static final int SCREEN_ROWS = 8;
	public static final int SCREEN_ROWS_GEN3 = 9;
	/** The shade of black in a tile's shades. */
	static final int BLACK = 3;

	private Headroom() {
	}

	/** A cell the chain stops at besides a floor: off the true map (Gen 3's padding) or drawn as one flat black. */
	public interface Filler {
		boolean stops(int cx, int cy);
	}

	/** The classic screen's rows above the player: Gen 3's, else Gen 1's and 2's. */
	public static int screenRows(boolean gen3) {
		return gen3 ? SCREEN_ROWS_GEN3 : SCREEN_ROWS;
	}

	/**
	 * The ceilings of an interior, per cell, in blocks over its feet level, as {@link MapLayout#withCeilings} takes them.
	 *
	 * @param layout its levels and ramps, or null when it is flat
	 * @param cave   true for a cave (its ceiling follows the floor), else one flat ceiling
	 */
	public static int[] ceilings(MapModel model, @Nullable MapLayout layout, Filler filler, int screenRows, boolean cave) {
		int w = model.cellWidth();
		int h = model.cellHeight();
		int[] out = new int[w * h];
		int tallestChain = 0;
		int depth = 0;
		int highestFloor = 0;
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				if (!stood(model.kind(cx, cy))) {
					continue;
				}
				int floor = WorldPlan.hangBase(layout, cx, cy);
				int chain = chain(model, filler, screenRows, cx, cy);
				tallestChain = Math.max(tallestChain, chain);
				depth = Math.max(depth, floor + chain);
				highestFloor = Math.max(highestFloor, floor);
			}
		}
		if (cave) {
			int room = Math.max(MIN, tallestChain);
			for (int cy = 0; cy < h; cy++) {
				for (int cx = 0; cx < w; cx++) {
					out[cy * w + cx] = WorldPlan.hangBase(layout, cx, cy) + room;
				}
			}
		} else {
			Arrays.fill(out, Math.max(depth, highestFloor + MIN));
		}
		return out;
	}

	/** A cell's chain: the rows of solid drawing straight north of it the screen shows, two a cell. */
	static int chain(MapModel model, Filler filler, int screenRows, int cx, int cy) {
		int rows = 0;
		for (int y = cy - 1; y >= 0 && rows < screenRows; y--) {
			if (stood(model.kind(cx, y)) || filler.stops(cx, y)) {
				break;
			}
			rows += 2;
		}
		return Math.min(rows, screenRows);
	}

	/** Ground, ledges and water: where a chain starts and ends. */
	static boolean stood(MapModel.Kind kind) {
		return kind == MapModel.Kind.GROUND || kind == MapModel.Kind.LEDGE || kind == MapModel.Kind.WATER;
	}

	/**
	 * Every interior's room for the shape planner, by map id (D5): {@code max(MIN, its tallest chain)} blocks over its floor,
	 * read off the scene map as {@link #ceilings} reads a model, so the shapes' keys come from the export alone, whichever
	 * maps a build places. Maps under the sky (the surface, an open-air interior) are left out.
	 */
	public static Map<String, Integer> rooms(dev.kantobridge.scene.Scene scene, ArtSource art, int screenRows) {
		Map<String, Integer> out = new TreeMap<>();
		for (SceneMap map : scene.maps().values()) {
			if (map.surface() || map.env().openAir()) {
				continue;
			}
			int tallest = 0;
			for (int cy = 0; cy < map.h(); cy++) {
				for (int cx = 0; cx < map.w(); cx++) {
					if (!stood(map.kind(cx, cy))) {
						continue;
					}
					int rows = 0;
					for (int y = cy - 1; y >= 0 && rows < screenRows; y--) {
						if (stood(map.kind(cx, y)) || allBlack(map, art, cx, y)) {
							break;
						}
						rows += 2;
					}
					tallest = Math.max(tallest, Math.min(rows, screenRows));
				}
			}
			out.put(map.id(), Math.max(MIN, tallest));
		}
		return out;
	}

	private static boolean stood(CellKind kind) {
		return kind == CellKind.WALK || kind == CellKind.LEDGE || kind == CellKind.WATER;
	}

	private static boolean allBlack(SceneMap map, ArtSource art, int cx, int cy) {
		int per = map.perCell();
		for (int k = 0; k < per * per; k++) {
			int tile = map.tile(cx * per + k % per, cy * per + k / per);
			int[] shades = tile < 0 ? null : art.tileShades(map.atlas(), tile);
			if (shades == null) {
				return false;
			}
			for (int shade : shades) {
				if (shade != BLACK) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * The filler of a map as its art draws it: a cell whose every tile is one flat black (the void round a room), plus what
	 * {@code outside} adds (Gen 3's padding past the true size).
	 */
	public static Filler blackFiller(MapModel model, ArtSource art, @Nullable Filler outside) {
		String atlas = CaveMap.atlas(model);
		int w = model.cellWidth();
		int h = model.cellHeight();
		boolean[] black = new boolean[w * h];
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				black[cy * w + cx] = allBlack(model, art, atlas, cx, cy);
			}
		}
		return (cx, cy) -> (outside != null && outside.stops(cx, cy)) || black[cy * w + cx];
	}

	private static boolean allBlack(MapModel model, ArtSource art, String atlas, int cx, int cy) {
		for (int k = 0; k < 4; k++) {
			int tile = model.tile(cx * 2 + k % 2, cy * 2 + k / 2);
			int[] shades = tile < 0 ? null : art.tileShades(atlas, tile);
			if (shades == null) {
				return false;
			}
			for (int shade : shades) {
				if (shade != BLACK) {
					return false;
				}
			}
		}
		return true;
	}
}
