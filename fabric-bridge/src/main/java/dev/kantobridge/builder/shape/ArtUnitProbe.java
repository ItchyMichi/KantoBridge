package dev.kantobridge.builder.shape;

import dev.kantobridge.builder.shape.geom.CellUnits;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.scene.ArtSource;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The probe of PD3 on an art source, the builder's own export (docs/specs/PROFILE_PLAN.md §5.4): a repeating cell is a
 * unit when {@link CellUnits#build} makes a hull or an object of it, exactly as each client will. Remembered per atlas,
 * tiles and context, so a tree wall asks once. And whether a tile is one flat black (D8's filler, {@link #blank}). Not
 * thread-safe (the planner runs on one thread).
 */
public final class ArtUnitProbe implements UnitProbe {
	/** The shade of black in a tile's shades. */
	private static final int BLACK = 3;
	private final ArtSource art;
	private final Map<String, Boolean> answers = new HashMap<>();

	public ArtUnitProbe(ArtSource art) {
		this.art = art;
	}

	@Override
	public boolean shaped(String atlas, int[] tiles, CellUnits.Context context) {
		String key = atlas + "|" + Arrays.toString(tiles) + "|" + context.variant();
		Boolean known = this.answers.get(key);
		if (known != null) {
			return known;
		}
		int[][] shades = new int[tiles.length][];
		boolean answer = true;
		for (int t = 0; t < tiles.length && answer; t++) {
			shades[t] = tiles[t] < 0 ? null : this.art.tileShades(atlas, tiles[t]);
			answer = shades[t] != null;
		}
		if (answer) {
			CellUnits.Kind kind = CellUnits.build(shades, context, ShapeTable.UNIT_CELL, this.art.artPx()).kind();
			answer = kind == CellUnits.Kind.HULL || kind == CellUnits.Kind.OBJECT;
		}
		this.answers.put(key, answer);
		return answer;
	}

	@Override
	public boolean blank(String atlas, int tile) {
		int[] shades = tile < 0 ? null : this.art.tileShades(atlas, tile);
		if (shades == null) {
			return false;
		}
		for (int shade : shades) {
			if (shade != BLACK) {
				return false;
			}
		}
		return true;
	}

}
