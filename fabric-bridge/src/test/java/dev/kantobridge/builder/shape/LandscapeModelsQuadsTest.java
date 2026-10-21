package dev.kantobridge.builder.shape;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.shape.geom.CellUnits;
import dev.kantobridge.builder.shape.geom.PartsBuilding;
import dev.kantobridge.builder.shape.geom.QuadMerger;
import dev.kantobridge.builder.shape.geom.ShapeQuad;
import dev.kantobridge.builder.shape.geom.VoxelGrid;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.scene.FrontEnds;
import dev.kantobridge.scene.Scene;
import dev.kantobridge.scene.SceneMap;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The landscape models' quads per block (docs/specs/LANDSCAPE_MODELS.md), pod only: each model of the table built as the
 * client builds it (PartsBuilding over its map's tiles, QuadMerger's shell), its blocks' quads counted against the cap
 * of 96 (VOXEL_BLOCKS_PLAN.md section 3.8). Counts only. -Pkb.landscapeQuads="&lt;bundle&gt;;&lt;table&gt;;&lt;out json&gt;".
 */
@EnabledIfSystemProperty(named = "kb.landscapeQuads", matches = ".+")
class LandscapeModelsQuadsTest {
	@Test
	void quads() throws Exception {
		String[] p = System.getProperty("kb.landscapeQuads").split(";");
		ExportBundle bundle = ExportBundle.open(Path.of(p[0]));
		Scene scene = FrontEnds.forGame(bundle.game()).describe(bundle);
		Map<String, List<LandscapeModels.Model>> table = LandscapeModels.parse(Files.readString(Path.of(p[1]), StandardCharsets.UTF_8));
		JsonArray out = new JsonArray();
		int px = 8;
		for (Map.Entry<String, List<LandscapeModels.Model>> e : table.entrySet()) {
			SceneMap map = scene.maps().get(e.getKey());
			if (map == null) {
				continue;
			}
			for (LandscapeModels.Model m : e.getValue()) {
				int w = m.w() * px;
				int d = m.h() * px;
				int[] texel = new int[w * d];
				Map<Integer, Integer> slots = new HashMap<>();
				for (int sy = 0; sy < d; sy++) {
					for (int sx = 0; sx < w; sx++) {
						int tile = map.tile(m.tx() + sx / px, m.ty() + sy / px);
						int slot = slots.computeIfAbsent(tile, k -> slots.size());
						texel[sy * w + sx] = VoxelGrid.texel(slot, sx % px, sy % px);
					}
				}
				VoxelGrid grid = PartsBuilding.voxels(m.spec(), texel, w, d, px);
				JsonObject o = new JsonObject();
				o.addProperty("map", e.getKey());
				o.addProperty("model", m.name());
				if (grid == null) {
					o.addProperty("blocks", 0);
					out.add(o);
					continue;
				}
				int blocks = 0;
				long quads = 0;
				int max = 0;
				int over = 0;
				int over256 = 0;
				for (List<ShapeQuad> block : QuadMerger.merge(grid, QuadMerger.ALL_FACES, 0).values()) {
					blocks++;
					quads += block.size();
					max = Math.max(max, block.size());
					over += block.size() > CellUnits.BUDGET_QUADS ? 1 : 0;
					over256 += block.size() > 256 ? 1 : 0;
				}
				o.addProperty("voxels", grid.count());
				o.addProperty("blocks", blocks);
				o.addProperty("quads", quads);
				o.addProperty("mean", blocks == 0 ? 0 : Math.round(10.0 * quads / blocks) / 10.0);
				o.addProperty("max", max);
				o.addProperty("blocks_over_96", over);
				o.addProperty("blocks_over_256", over256);
				out.add(o);
			}
		}
		JsonObject root = new JsonObject();
		root.addProperty("game", bundle.game());
		root.add("models", out);
		Files.writeString(Path.of(p[2]), ProtoJson.write(root), StandardCharsets.UTF_8);
	}
}
