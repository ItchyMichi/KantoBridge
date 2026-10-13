package dev.kantobridge.builder.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The export's routes resource (Wild Routes, WR2): a made-up zone as gen1-bridge exports it, and what is refused. */
class ExportRoutesTest {
	private static final String ZONE = """
			{"version": 1, "routes": [{"id": "test_zone", "name": "TEST WILDS", "kind": "free_roam", "theme": "forest",
			  "town": {"map": "TEST_TOWN", "cleared": [[1, 0]], "triggers": [[2, 0], [3, 0]], "back": {"x": 2, "y": 1, "facing": "down"}},
			  "routeMap": {"id": "KB_ROUTE_TEST_ZONE", "width": 1, "height": 1, "a": {"x": 0, "y": 0}, "arrive": {"x": 1, "y": 0, "facing": "right"}},
			  "trainers": {"perArea": [1, 3], "rematch": "each_visit", "classes": ["OPP_TEST"]},
			  "areas": {"main": [3, 4], "side": 50},
			  "levels": {"from": "neighbours", "perArea": 1, "maxRise": 3},
			  "dungeon": {"chance": 60, "floors": "by_level"}}]}""";

	private static ExportRoutes parse(String text) throws Exception {
		return ExportRoutes.parse(DataReader.of(ProtoJson.parse(text), "routes/all.json"));
	}

	@Test
	void readsAZone() throws Exception {
		ExportRoutes routes = parse(ZONE);
		assertEquals(List.of(new ExportRoutes.Zone("test_zone", "TEST WILDS", "free_roam", "forest", "TEST_TOWN", "KB_ROUTE_TEST_ZONE", 3, 4,
				0.5, 1, 3, List.of("OPP_TEST"), 1, 3, 0.6)), routes.zones());
		assertEquals(List.of(), parse("{\"version\": 1, \"routes\": []}").zones());
	}

	@Test
	void refusesWhatItDoesntKnow() {
		assertThrows(DataException.class, () -> parse(ZONE.replace("\"free_roam\"", "\"connecting\"")));
		assertThrows(DataException.class, () -> parse(ZONE.replace("\"forest\"", "\"cave\"")));
		assertThrows(DataException.class, () -> parse(ZONE.replace("[3, 4]", "[4, 3]")));
		assertThrows(DataException.class, () -> parse(ZONE.replace("\"side\": 50", "\"side\": 150")));
		assertThrows(DataException.class, () -> parse(ZONE.replace("\"side\": 50", "\"side\": 0.5")), "whole percent, never a fraction");
		assertThrows(DataException.class, () -> parse(ZONE.replace("\"version\": 1", "\"version\": 2")));
		assertThrows(DataException.class, () -> parse(ZONE.replace("\"test_zone\"", "\"Test-Zone\"")));
	}
}
