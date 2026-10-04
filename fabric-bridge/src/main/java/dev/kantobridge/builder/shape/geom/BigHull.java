package dev.kantobridge.builder.shape.geom;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.shape.Templates;
import dev.kantobridge.scene.ArtSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A two-cell hull's model (docs/specs/PROFILE_PLAN.md §5.6, P3), built on each client from its own drawing: a canopy is
 * our hull carved over the group's 32 × 32 drawing, 32 voxels high and deep; a planter is carved over its 16 × 32 drawing
 * (each 16-row half masked on its own), standing on the south cell, its crown rows' depth capped ({@link
 * HullVariants#planter}). Sliced into the template's blocks; over the quad cap, or without the art, the template's columns
 * (plain cubes).
 */
final class BigHull {
	private BigHull() {
	}

	static TemplateRule.Template build(Templates.Entry entry, String atlas, ArtSource art) {
		JsonObject hull = entry.hull();
		TemplateRule.Template columns = TemplateRule.columns(entry, null, 1.0);
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
		int height = hull.get("height").getAsInt();
		VoxelGrid grid;
		if ("planter".equals(hull.get("kind").getAsString())) {
			// Each 16-row half masked on its own; the model stands on the south cell.
			boolean[] mask = new boolean[w * d];
			int half = d / 2;
			for (int part = 0; part < 2; part++) {
				int[] s = new int[w * half];
				System.arraycopy(shade, part * w * half, s, 0, w * half);
				boolean[] m = Hulls.mask(s, new boolean[w * half], w, half);
				System.arraycopy(m, 0, mask, part * w * half, w * half);
			}
			VoxelGrid model = HullVariants.planter(mask, texel, w, d, px);
			grid = new VoxelGrid(w, Math.max(px, ((height + px - 1) / px) * px), d, px);
			for (int y = 0; y < Math.min(model.height(), grid.height()); y++) {
				for (int z = 0; z < model.depth(); z++) {
					for (int x = 0; x < w; x++) {
						if (model.solid(x, y, z)) {
							grid.set(x, y, z + half, model.at(x, y, z));
							grid.setCap(x, y, z + half, model.cap(x, y, z));
						}
					}
				}
			}
		} else {
			boolean[] mask = Hulls.mask(shade, new boolean[w * d], w, d);
			grid = Hulls.hull(mask, texel, w, Math.max(px, ((height + px - 1) / px) * px), d, px);
		}
		Map<Integer, List<ShapeQuad>> blocks = new TreeMap<>();
		int quads = 0;
		for (Map.Entry<Integer, List<ShapeQuad>> e : QuadMerger.merge(grid, QuadMerger.ALL_FACES, 0).entrySet()) {
			List<ShapeQuad> block = new ArrayList<>(e.getValue().size());
			for (ShapeQuad quad : e.getValue()) {
				block.add(quad.withTexture(tiles.get(quad.texture())));
			}
			blocks.put(e.getKey(), block);
			quads = Math.max(quads, block.size());
		}
		if (quads > CellUnits.MAX_QUADS) {
			return TemplateRule.columns(entry, TemplateRule.Kind.HULL, 0.0);
		}
		return new TemplateRule.Template(TemplateRule.Kind.HULL, null, 1.0, quads, blocks, null, grid,
				tiles.stream().mapToInt(Integer::intValue).toArray(), -1);
	}
}
