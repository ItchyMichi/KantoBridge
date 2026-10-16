package dev.kantobridge.story;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportSwaps;
import dev.kantobridge.builder.export.G2Map;
import dev.kantobridge.builder.export.G2Tiles;
import dev.kantobridge.builder.plan.G2Cells;
import dev.kantobridge.builder.plan.MapModel;
import dev.kantobridge.builder.plan.Warnings;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The story check for Crystal (docs/specs/STORY_EVENTS_PLAN.md §5): the live sweep's cases replayed through
 * {@link StoryRules} on the real Johto's WorldIndex (pod only): {@code -Pkb.realGen2Index=<world>/kantobridge/regions/johto/
 * world_index.json}, the Crystal export it was built from ({@code -Pkb.realGen2Bundle}) and the sweep's truth files
 * ({@code -Pkb.storySweep=<a.json,b.json>}, written by tools/e2e/g2_story_check.py from the real engine). For every case,
 * the blocks the bridge sends (each live block that differs from the map data the client was built from) are fed as
 * {@code story.blocks}; then every cell those blocks change (its art or its passability) must collide in Minecraft as the
 * hidden game's cell does (a tile story cell's, else the built terrain's) and show the live block's art (the closed art
 * while the cell is shut, else what was built); every other tile cell of the map must be in the map data's state.
 * Writes {@code reports/gen2_story_replay.json}: counts and map ids only. Skipped everywhere else.
 */
@EnabledIfSystemProperty(named = "kb.realGen2Index", matches = ".+")
class RealGen2StoryReplayTest {
	private enum Pass {
		GROUND, WATER, LEDGE, SOLID
	}

	private static Pass pass(G2Tiles.Meaning meaning) {
		if ("land".equals(meaning.perm())) {
			return meaning.ledge().isEmpty() ? Pass.GROUND : Pass.LEDGE;
		}
		return "water".equals(meaning.perm()) ? Pass.WATER : Pass.SOLID;
	}

	private static Pass pass(MapModel.Kind kind) {
		return switch (kind) {
			case GROUND -> Pass.GROUND;
			case WATER -> Pass.WATER;
			case LEDGE -> Pass.LEDGE;
			default -> Pass.SOLID;
		};
	}

	private static boolean collides(Pass pass) {
		return pass == Pass.SOLID || pass == Pass.WATER;
	}

	private static boolean sameArt(G2Tiles tiles, int a, int b, int qx, int qy) {
		for (int ty = qy * 2; ty < qy * 2 + 2; ty++) {
			for (int tx = qx * 2; tx < qx * 2 + 2; tx++) {
				if (tiles.tile(a, tx, ty) != tiles.tile(b, tx, ty)) {
					return false;
				}
			}
		}
		return true;
	}

	/** The block cell (cx, cy) is built with: its site's open state, else the map data's block. */
	private static int built(MapModel model, G2Map map, int cx, int cy) {
		ExportSwaps.Site site = model.site(cx, cy);
		if (site == null) {
			return map.dataBlock(cx / 2, cy / 2);
		}
		if (!model.changes(cx, cy)) {
			return site.after();
		}
		return model.closedBlock(cx, cy) == site.after() ? site.before() : site.after();
	}

	@Test
	void replay() throws Exception {
		WorldIndex index = WorldIndex.fromJson(Files.readString(Path.of(System.getProperty("kb.realGen2Index")), StandardCharsets.UTF_8));
		ExportBundle bundle = ExportBundle.open(Path.of(System.getProperty("kb.realGen2Bundle")));
		Map<String, List<ExportSwaps.Site>> sitesByMap = new HashMap<>();
		for (ExportSwaps.Site site : bundle.swaps().sites()) {
			sitesByMap.computeIfAbsent(site.map(), k -> new ArrayList<>()).add(site);
		}
		Map<String, StoryCellTable.Entry> tileEntries = new HashMap<>();
		Map<String, List<StoryCellTable.Entry>> tilesByMap = new HashMap<>();
		Set<String> otherKeys = new HashSet<>();
		for (StoryCellTable.Entry entry : index.storyCells().entries()) {
			if (entry.type() == StoryCellType.TILE) {
				tileEntries.put(entry.key(), entry);
				tilesByMap.computeIfAbsent(entry.cell().map(), k -> new ArrayList<>()).add(entry);
			} else {
				otherKeys.add(entry.key());
			}
		}
		Map<String, Integer> counts = new TreeMap<>();
		Map<String, Integer> missingByMap = new TreeMap<>();
		Map<String, Integer> wrongByMap = new TreeMap<>();
		Map<String, Integer> artByMap = new TreeMap<>();
		Map<String, MapModel> models = new HashMap<>();
		JsonObject playerState = JsonParser.parseString(
				"{\"mode\":\"idle\",\"bike\":false,\"surf\":false,\"slope\":false,\"hidden\":false,\"dark\":false,\"lit\":false}").getAsJsonObject();
		for (String file : System.getProperty("kb.storySweep", "").split(",")) {
			if (file.isBlank() || !Files.isRegularFile(Path.of(file))) {
				continue;
			}
			JsonObject sweep = JsonParser.parseString(Files.readString(Path.of(file), StandardCharsets.UTF_8)).getAsJsonObject();
			for (JsonElement item : sweep.getAsJsonArray("cases")) {
				JsonObject rec = item.getAsJsonObject();
				String engineMap = rec.has("map") ? rec.get("map").getAsString() : null;
				JsonObject settled = rec.has("settled") && rec.get("settled").isJsonObject() ? rec.getAsJsonObject("settled") : null;
				if (engineMap == null || settled == null || !rec.get("ok").getAsBoolean() || !settled.has("map")
						|| !engineMap.equals(settled.get("map").getAsString())) {
					counts.merge("cases_skipped", 1, Integer::sum);
					continue;
				}
				String map = "G2_" + engineMap;
				if (index.map(map) == null || !bundle.has("g2map", map)) {
					counts.merge("cases_map_not_built", 1, Integer::sum);
					continue;
				}
				counts.merge("cases", 1, Integer::sum);
				G2Map g2 = bundle.g2map(map);
				G2Tiles tiles = bundle.g2tiles(g2.variant());
				MapModel model = models.get(map);
				if (model == null) {
					model = G2Cells.model(g2, tiles, sitesByMap.getOrDefault(map, List.of()), true, new Warnings());
					models.put(map, model);
				}
				// what the bridge sends: the live blocks that differ from the map data
				Map<Integer, Integer> live = new HashMap<>();
				JsonArray changes = new JsonArray();
				for (JsonElement b : settled.getAsJsonArray("blocks")) {
					JsonObject block = b.getAsJsonObject();
					int bx = block.get("bx").getAsInt();
					int by = block.get("by").getAsInt();
					int id = block.get("block").getAsInt();
					if (!g2.inBlocks(bx, by)) {
						counts.merge("blocks_off_map", 1, Integer::sum);
						continue;
					}
					if (id == g2.dataBlock(bx, by)) {
						continue;
					}
					counts.merge("blocks_changed", 1, Integer::sum);
					live.put(by * 256 + bx, id);
					JsonObject one = new JsonObject();
					one.addProperty("bx", bx);
					one.addProperty("by", by);
					one.addProperty("block", id);
					changes.add(one);
				}
				StoryRules rules = new StoryRules();
				rules.setIndex(index);
				rules.onPlayerState(playerState);
				rules.onPlayerMap(map);
				JsonObject msg = new JsonObject();
				msg.addProperty("map", map);
				msg.add("changes", changes);
				rules.onBlocks(msg);
				StoryRules.Cells result = rules.cells();
				Set<String> changedKeys = new HashSet<>();
				for (Map.Entry<Integer, Integer> e : live.entrySet()) {
					int bx = e.getKey() % 256;
					int by = e.getKey() / 256;
					int now = e.getValue();
					int data = g2.dataBlock(bx, by);
					for (int qy = 0; qy < 2; qy++) {
						for (int qx = 0; qx < 2; qx++) {
							int cx = bx * 2 + qx;
							int cy = by * 2 + qy;
							Pass game = pass(tiles.meaning(tiles.coll(now, qx, qy)));
							Pass was = pass(tiles.meaning(tiles.coll(data, qx, qy)));
							if (game == was && sameArt(tiles, now, data, qx, qy)) {
								continue; // the block changed, this cell of it didn't
							}
							String key = map + ":" + cx + ":" + cy;
							changedKeys.add(key);
							counts.merge("cells_changed", 1, Integer::sum);
							int builtBlock = built(model, g2, cx, cy);
							StoryCellTable.Entry entry = tileEntries.get(key);
							if (entry == null) {
								Pass builtPass = pass(model.kind(cx, cy));
								if (builtPass != game) {
									if (otherKeys.contains(key)) {
										counts.merge("cells_other_story_cell", 1, Integer::sum);
									} else if (game == Pass.LEDGE || builtPass == Pass.LEDGE) {
										counts.merge("cells_ledge", 1, Integer::sum);
									} else {
										counts.merge("cells_missing", 1, Integer::sum);
										missingByMap.merge(map, 1, Integer::sum);
									}
								} else if (!sameArt(tiles, builtBlock, now, qx, qy)) {
									counts.merge(game == Pass.SOLID ? "cells_solid_art_only" : "cells_art_no_cell", 1, Integer::sum);
									if (game != Pass.SOLID) {
										artByMap.merge(map, 1, Integer::sum);
									}
								} else {
									counts.merge("cells_built_matches", 1, Integer::sum);
								}
								continue;
							}
							boolean active = result.active().contains(key);
							if (game == Pass.LEDGE) {
								counts.merge("cells_ledge", 1, Integer::sum);
							} else if (active != collides(game)) {
								counts.merge("cells_wrong_collision", 1, Integer::sum);
								wrongByMap.merge(map, 1, Integer::sum);
							}
							boolean shut = result.drawn().contains(key);
							int shown = shut && entry.closed() != null ? entry.closed().block() : builtBlock;
							if (sameArt(tiles, shown, now, qx, qy)) {
								counts.merge(shut ? "cells_drawn_closed_ok" : "cells_built_ok", 1, Integer::sum);
							} else {
								counts.merge("cells_wrong_art", 1, Integer::sum);
								artByMap.merge(map, 1, Integer::sum);
							}
						}
					}
				}
				// every other tile cell of the map: in the map data's state
				for (StoryCellTable.Entry entry : tilesByMap.getOrDefault(map, List.of())) {
					if (changedKeys.contains(entry.key())) {
						continue;
					}
					int cx = entry.cell().x();
					int cy = entry.cell().y();
					Integer now = live.get((cy / 2) * 256 + cx / 2);
					int block = now != null ? now : g2.dataBlock(cx / 2, cy / 2);
					Pass game = pass(tiles.meaning(tiles.coll(block, cx % 2, cy % 2)));
					counts.merge("cells_own_state", 1, Integer::sum);
					if (game != Pass.LEDGE && result.active().contains(entry.key()) != collides(game)) {
						counts.merge("cells_own_wrong_collision", 1, Integer::sum);
						wrongByMap.merge(map, 1, Integer::sum);
					}
				}
			}
		}
		JsonObject out = new JsonObject();
		counts.forEach(out::addProperty);
		JsonObject missing = new JsonObject();
		missingByMap.forEach(missing::addProperty);
		out.add("missing_by_map", missing);
		JsonObject wrong = new JsonObject();
		wrongByMap.forEach(wrong::addProperty);
		out.add("wrong_collision_by_map", wrong);
		JsonObject art = new JsonObject();
		artByMap.forEach(art::addProperty);
		out.add("wrong_art_by_map", art);
		Path report = Path.of("reports", "gen2_story_replay.json");
		Files.createDirectories(report.getParent());
		Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
		System.out.println("gen2 story replay: " + out);
		assertEquals(0, counts.getOrDefault("cells_missing", 0), "changed cells colliding unlike the game without a story cell: " + missingByMap);
		assertEquals(0, counts.getOrDefault("cells_wrong_collision", 0) + counts.getOrDefault("cells_own_wrong_collision", 0),
				"cells colliding unlike the hidden game: " + wrongByMap);
	}
}
