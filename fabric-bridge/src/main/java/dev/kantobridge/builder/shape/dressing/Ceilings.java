package dev.kantobridge.builder.shape.dressing;

import dev.kantobridge.builder.shape.ShapeKey;
import dev.kantobridge.builder.shape.Structures;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.SceneMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ceilings and rings (§5.12, V7): an interior under a ceiling takes its room's dominant wall tile, the most frequent tile
 * of its wall columns (ties: the lowest id; no wall: of all its solid tiles). Its ceiling wears that tile darkened by a
 * vertex colour (the key's {@code tint}), never a new texture; its enclosure ring wears it plain, instead of border art.
 */
public final class Ceilings implements DressingRule {
	/** The ceiling's tint (0..3). */
	public static final int TINT = 2;

	@Override
	public boolean applies(SceneMap map) {
		return !map.surface() && !map.env().openAir();
	}

	@Override
	public void dress(SceneMap map, Dress dress) {
		Map<Integer, Integer> walls = new TreeMap<>();
		for (Structures.MeasuredRun run : dress.runs) {
			if (dress.wall(map, run)) {
				for (int ty = run.n(); ty <= run.f(); ty++) {
					walls.merge(map.tile(run.tx(), ty), 1, Integer::sum);
				}
			}
		}
		if (walls.isEmpty()) {
			for (int ty = 0; ty < map.artRows(); ty++) {
				for (int tx = 0; tx < map.artCols(); tx++) {
					if (map.kindAtTile(tx, ty) == CellKind.SOLID) {
						walls.merge(map.tile(tx, ty), 1, Integer::sum);
					}
				}
			}
		}
		int best = -1;
		int count = 0;
		for (Map.Entry<Integer, Integer> e : walls.entrySet()) {
			if (e.getValue() > count) {
				best = e.getKey();
				count = e.getValue();
			}
		}
		if (best < 0) {
			return;
		}
		dress.wallTile = best;
		dress.ceiling = ShapeKey.cube(dress.atlas, best, best, best, best, best, best).withTint(TINT);
	}
}
