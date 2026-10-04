package dev.kantobridge.builder.shape.dressing;

import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.shape.ShapeKey;
import dev.kantobridge.builder.shape.Structures;
import dev.kantobridge.scene.SceneMap;

/**
 * Interior walls rise to the ceiling (§5.12, V7): every wall column of an interior under a ceiling (a run along the map's
 * north, west or east edge) is {@link WorldPlan#CEILING_ABOVE_FEET} blocks high instead of the interior wall height,
 * folded as Tier A folds a run of that height: band k shows the row {@code origin - k} (the run's top row once the
 * drawing runs out, so a two-row back wall repeats its top band), the top what the 2D map shows there. Seen from the
 * game's view it stays the drawing. Furniture standing free keeps its height.
 */
public final class InteriorWalls implements DressingRule {
	@Override
	public boolean applies(SceneMap map) {
		return !map.surface() && !map.env().openAir();
	}

	@Override
	public void dress(SceneMap map, Dress dress) {
		int cols = map.artCols();
		int height = WorldPlan.CEILING_ABOVE_FEET;
		for (Structures.MeasuredRun run : dress.runs) {
			if (!dress.wall(map, run)) {
				continue;
			}
			int tx = run.tx();
			int n = run.n();
			int f = run.f();
			int north = map.tile(tx, Math.min(f, n + 1));
			for (int ty = n; ty <= f; ty++) {
				int i = ty * cols + tx;
				int had = dress.blocks[i];
				if (had <= 0 || had >= height) {
					continue;
				}
				int own = map.tile(tx, ty);
				int up = ty - height >= n ? map.tile(tx, ty - height) : map.tile(tx, n);
				ShapeKey[] column = new ShapeKey[height];
				boolean any = false;
				for (int k = 0; k < height; k++) {
					int front = map.tile(tx, Math.max(n, run.origin() - k));
					if (up == own && north == own && front == own) {
						continue;
					}
					column[k] = ShapeKey.cube(dress.atlas, up, up, north, front, front, front);
					any = true;
				}
				dress.keys[i] = any ? column : null;
				dress.blocks[i] = height;
			}
		}
	}
}
