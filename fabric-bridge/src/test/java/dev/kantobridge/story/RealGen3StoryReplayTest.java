package dev.kantobridge.story;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.G3Map;
import dev.kantobridge.builder.export.G3Tiles;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * SE5 (docs/specs/STORY_EVENTS_PLAN.md): the live sweep's cases replayed through {@link StoryRules} on the real Hoenn's
 * WorldIndex (pod only): {@code -Pkb.realGen3Index=<world>/kantobridge/regions/hoenn/world_index.json}, the Emerald export
 * it was built from ({@code -Pkb.realGen3Bundle}) and the sweep's truth files ({@code -Pkb.storySweep=<a.json,b.json>},
 * written by tools/e2e/g3_story_check.py from the real engine). For every case, the cells the bridge sends (each live
 * cell whose metatile or collision class differs from the exported cell; the engine's door repair on a warp cell is the
 * map's own, SE1) are fed as {@code story.cells}, and every changed cell must then be a {@code cell} story cell that
 * collides exactly when the hidden game's cell does (solid, or water for a player not surfing) and draws a state of its
 * own metatile; every other cell cell of the map must be in its own state. Art-only changes of solid cells (TVs) have
 * no story cell by design and are counted apart. Writes {@code reports/gen3_story_replay.json}: counts and map ids only.
 * Skipped everywhere else.
 */
@EnabledIfSystemProperty(named = "kb.realGen3Index", matches = ".+")
class RealGen3StoryReplayTest {
	private static final Set<Integer> GROUND = Set.of(0x00, 0x18, 0x2B, 0x71, 0x72, 0xA0, 0xA1, 0xA2, 0xA3);
	private static final int WATER = 0x29;
	private static final int DOOR = 0x71;

	static StoryCellTable.Pass pass(int coll) {
		return GROUND.contains(coll) ? StoryCellTable.Pass.GROUND : coll == WATER ? StoryCellTable.Pass.WATER : StoryCellTable.Pass.SOLID;
	}

	private static boolean collides(StoryCellTable.Pass pass) {
		return pass == StoryCellTable.Pass.SOLID || pass == StoryCellTable.Pass.WATER;
	}

	@Test
	void replay() throws Exception {
		WorldIndex index = WorldIndex.fromJson(Files.readString(Path.of(System.getProperty("kb.realGen3Index")), StandardCharsets.UTF_8));
		ExportBundle bundle = ExportBundle.open(Path.of(System.getProperty("kb.realGen3Bundle")));
		Map<String, StoryCellTable.Entry> cellEntries = new HashMap<>();
		Map<String, List<StoryCellTable.Entry>> byMap = new HashMap<>();
		Set<String> objectKeys = new HashSet<>();
		for (StoryCellTable.Entry entry : index.storyCells().entries()) {
			if (entry.type() == StoryCellType.CELL) {
				cellEntries.put(entry.key(), entry);
				byMap.computeIfAbsent(entry.cell().map(), k -> new java.util.ArrayList<>()).add(entry);
			} else if (entry.type() == StoryCellType.OBJECT) {
				objectKeys.add(entry.key());
			}
		}
		Map<String, Integer> counts = new TreeMap<>();
		Map<String, Integer> missingByMap = new TreeMap<>();
		Map<String, Integer> wrongByMap = new TreeMap<>();
		JsonObject playerState = JsonParser.parseString(
				"{\"mode\":\"idle\",\"bike\":false,\"surf\":false,\"slope\":false,\"hidden\":false,\"dark\":false,\"lit\":false}").getAsJsonObject();
		for (String file : System.getProperty("kb.storySweep", "").split(",")) {
			if (file.isBlank() || !Files.isRegularFile(Path.of(file))) {
				continue;
			}
			JsonObject sweep = JsonParser.parseString(Files.readString(Path.of(file), StandardCharsets.UTF_8)).getAsJsonObject();
			for (JsonElement item : sweep.getAsJsonArray("cases")) {
				JsonObject rec = item.getAsJsonObject();
				String map = rec.has("map") ? rec.get("map").getAsString() : null;
				JsonObject settled = rec.has("settled") && rec.get("settled").isJsonObject() ? rec.getAsJsonObject("settled") : null;
				if (map == null || settled == null || !rec.get("ok").getAsBoolean() || !settled.has("map")
						|| !map.equals(settled.get("map").getAsString())) {
					counts.merge("cases_skipped", 1, Integer::sum);
					continue;
				}
				if (index.map(map) == null || !bundle.has("g3map", map)) {
					counts.merge("cases_map_not_built", 1, Integer::sum);
					continue;
				}
				counts.merge("cases", 1, Integer::sum);
				G3Map own = bundle.g3map(map);
				G3Tiles tiles = bundle.g3tiles(own.pair());
				Set<Long> warps = new HashSet<>();
				for (G3Map.Warp warp : own.warps()) {
					warps.add((long) warp.x() << 32 | warp.y());
				}
				// what the bridge sends: live cells that differ from the exported cell (SE1)
				Map<String, int[]> live = new HashMap<>();
				JsonArray sent = new JsonArray();
				for (JsonElement c : settled.getAsJsonArray("cells")) {
					JsonObject cell = c.getAsJsonObject();
					int x = cell.get("x").getAsInt();
					int y = cell.get("y").getAsInt();
					if (!own.inMap(x, y)) {
						counts.merge("cells_off_map", 1, Integer::sum);
						continue;
					}
					JsonArray l = cell.getAsJsonArray("l");
					int mid = l.get(0).getAsInt();
					int coll = l.get(1).getAsInt();
					boolean doorRepair = mid == own.mid(x, y) && coll == DOOR && warps.contains((long) x << 32 | y);
					if (doorRepair || mid == own.mid(x, y) && pass(coll) == pass(own.coll(x, y))) {
						continue;
					}
					if (tiles.quarters(mid) == null) {
						// a metatile the map's pair lacks: the export leaves such a state out (only a script other maps
						// share sets it here, as the cable club's on Mossdeep's Game Corner under a forced link state)
						counts.merge("cells_foreign_metatile", 1, Integer::sum);
						continue;
					}
					live.put(map + ":" + x + ":" + y, new int[] {mid, coll, own.mid(x, y), own.coll(x, y)});
					JsonObject one = new JsonObject();
					one.addProperty("x", x);
					one.addProperty("y", y);
					one.addProperty("metatile", mid);
					one.addProperty("pass", pass(coll).id());
					sent.add(one);
				}
				StoryRules rules = new StoryRules();
				rules.setIndex(index);
				rules.onPlayerState(playerState);
				rules.onPlayerMap(map);
				for (int from = 0; from < sent.size(); from += 1024) {
					JsonArray part = new JsonArray();
					for (int i = from; i < Math.min(sent.size(), from + 1024); i++) {
						part.add(sent.get(i));
					}
					JsonObject msg = new JsonObject();
					msg.addProperty("map", map);
					msg.add("cells", part);
					rules.onCells(msg);
				}
				StoryRules.Cells result = rules.cells();
				for (Map.Entry<String, int[]> e : live.entrySet()) {
					String key = e.getKey();
					int[] v = e.getValue();
					StoryCellTable.Pass livePass = pass(v[1]);
					counts.merge("cells_changed", 1, Integer::sum);
					StoryCellTable.Entry entry = cellEntries.get(key);
					if (entry == null) {
						if (livePass == StoryCellTable.Pass.SOLID && pass(v[3]) == StoryCellTable.Pass.SOLID) {
							counts.merge("cells_solid_art_only", 1, Integer::sum);
						} else if (objectKeys.contains(key)) {
							counts.merge("cells_under_object", 1, Integer::sum);
						} else {
							counts.merge("cells_missing", 1, Integer::sum);
							missingByMap.merge(map, 1, Integer::sum);
						}
						continue;
					}
					boolean active = result.active().contains(key);
					if (active != collides(livePass)) {
						counts.merge("cells_wrong_collision", 1, Integer::sum);
						wrongByMap.merge(map, 1, Integer::sum);
					}
					StoryCellTable.Built built = entry.built();
					boolean isBuilt = built.metatile() == v[0] && built.pass() == livePass;
					if (isBuilt) {
						counts.merge("cells_in_built_state", 1, Integer::sum);
					} else if (!result.drawn().contains(key)) {
						counts.merge("cells_no_state_drawn", 1, Integer::sum);
					} else {
						StoryCellTable.CellState state = entry.states().get(result.states().get(key));
						if (state.metatile() != v[0]) {
							counts.merge("cells_wrong_art", 1, Integer::sum);
						} else if (state.art().stream().anyMatch(slot -> slot < 0)) {
							counts.merge("cells_art_missing", 1, Integer::sum);
						} else {
							counts.merge("cells_drawn_ok", 1, Integer::sum);
						}
					}
				}
				// every other cell cell of the map: in its own state
				for (StoryCellTable.Entry entry : byMap.getOrDefault(map, List.of())) {
					if (live.containsKey(entry.key())) {
						continue;
					}
					counts.merge("cells_own_state", 1, Integer::sum);
					boolean active = result.active().contains(entry.key());
					StoryCellTable.Pass ownPass = null;
					if (entry.start() != null && entry.start() == entry.built().metatile()) {
						ownPass = entry.built().pass();
					} else {
						for (StoryCellTable.CellState state : entry.states()) {
							if (entry.start() != null && state.metatile() == entry.start()) {
								ownPass = state.pass();
								break;
							}
						}
					}
					if (ownPass != null && active != collides(ownPass)) {
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
		Path report = Path.of("reports", "gen3_story_replay.json");
		Files.createDirectories(report.getParent());
		Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
		System.out.println("gen3 story replay: " + out);
		assertEquals(0, counts.getOrDefault("cells_missing", 0), "changed cells without a cell story cell: " + missingByMap);
		assertEquals(0, counts.getOrDefault("cells_wrong_collision", 0) + counts.getOrDefault("cells_own_wrong_collision", 0),
				"cells colliding unlike the hidden game: " + wrongByMap);
	}
}
