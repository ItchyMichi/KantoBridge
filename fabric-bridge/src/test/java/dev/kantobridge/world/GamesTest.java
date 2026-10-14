package dev.kantobridge.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The game catalogue (MULTI_GAME_PLAN §4.2, MG1): the bundled copy is the repository's, and every lookup reads it. */
class GamesTest {
	private static Path repositoryFile() {
		String dir = System.getProperty("kb.protocolDir");
		if (dir == null) {
			throw new IllegalStateException("kb.protocolDir is not set; run the tests through Gradle");
		}
		return Path.of(dir, "games.json");
	}

	@Test
	void bundledCatalogueIsTheRepositoryFile() throws Exception {
		byte[] file = Files.readAllBytes(repositoryFile());
		try (InputStream in = Games.class.getResourceAsStream(Games.RESOURCE)) {
			assertTrue(in != null, "the build bundles protocol/games.json");
			assertArrayEquals(file, in.readAllBytes(), "byte for byte");
		}
		Games parsed = Games.parse(new String(file, StandardCharsets.UTF_8));
		assertEquals(parsed.entries(), Games.bundled().entries());
		assertEquals(parsed.entries().keySet().stream().toList(), Games.ids());
	}

	@Test
	void everyCatalogueIdIsKnownWithItsFamily() {
		for (String id : Games.ids()) {
			assertTrue(Games.known(id), id);
			assertTrue(Games.FAMILIES.contains(Games.family(id)), id);
			assertEquals(id, Games.get(id).id());
		}
		for (String id : List.of("red", "blue", "yellow")) {
			assertEquals("gen1", Games.family(id), id);
		}
		for (String id : List.of("gold", "silver", "crystal")) {
			assertEquals("gen2", Games.family(id), id);
		}
		for (String id : List.of("firered", "leafgreen", "ruby", "sapphire", "emerald")) {
			assertEquals("gen3", Games.family(id), id);
		}
		assertFalse(Games.known("nope"));
		assertFalse(Games.known("Red"));
		assertFalse(Games.known(null));
		assertNull(Games.family("nope"));
		assertNull(Games.get(null));
	}

	@Test
	void regionsCompatAndAdapters() {
		assertEquals("kanto", Games.region("red"));
		assertEquals("kanto", Games.region("blue"));
		assertEquals("hoenn", Games.region("emerald"));
		assertEquals("kanto_frlg", Games.region("firered"));
		assertEquals("johto", Games.region("gold"));
		assertNull(Games.region("nope"));
		assertEquals(new Games.Compat(151, 165, 10), Games.compat("red"));
		assertEquals(new Games.Compat(386, 354, 10), Games.compat("emerald"));
		assertNull(Games.compat("nope"));
		// MG7: Emerald has the game3 adapter (GEN3_BRIDGE_PLAN.md)
		assertEquals(List.of("blue", "emerald", "red"), Games.withAdapter());
		assertTrue(Games.adapter("blue"));
		assertTrue(Games.adapter("emerald"));
		assertFalse(Games.adapter("firered"));
		assertFalse(Games.adapter("nope"));
		assertEquals("Blue", Games.label("blue"));
		assertEquals("blue", Games.artSet("blue"));
		assertEquals("rse", Games.get("emerald").layout());
		assertNull(Games.get("red").layout());
		assertEquals(Map.of("starter", "EVENT_GOT_STARTER", "champion", "EVENT_BEAT_CHAMPION_RIVAL"), Games.get("red").markers());
		// MG7 (spike S3): Emerald's story markers are its own flag names
		assertEquals(Map.of("starter", "FLAG_SYS_POKEMON_GET", "champion", "FLAG_SYS_GAME_CLEAR"), Games.get("emerald").markers());
	}

	@Test
	void compatibilityIsTheSameGameOrOneFamily() {
		assertTrue(Games.compatible("red", "red"));
		assertTrue(Games.compatible("red", "blue"));
		assertTrue(Games.compatible("blue", "red"));
		assertTrue(Games.compatible("yellow", "red"), "one family");
		assertFalse(Games.compatible("red", "emerald"), "another family");
		assertFalse(Games.compatible("gold", "red"));
		assertFalse(Games.compatible(null, "red"));
		assertFalse(Games.compatible("red", null));
		assertTrue(Games.compatible("nope", "nope"), "an unknown game still draws its own");
		assertFalse(Games.compatible("nope", "red"));
	}

	@Test
	void parseRefusesMalformedCatalogues() {
		String good = "{\"version\":1,\"games\":{\"red\":{\"family\":\"gen1\",\"engine\":\"gen1\",\"generation\":1,\"label\":\"Red\","
				+ "\"region\":\"kanto\",\"compat\":{\"maxDex\":151,\"maxMove\":165,\"nick\":10},\"artSet\":\"red\",\"adapter\":true,"
				+ "\"markers\":{\"starter\":\"EVENT_GOT_STARTER\"}}}}";
		Games parsed = assertDoesNotThrowParse(good);
		assertEquals(List.of("red"), parsed.entries().keySet().stream().toList());
		assertEquals("EVENT_GOT_STARTER", parsed.entry("red").markers().get("starter"));
		assertNull(parsed.entry("blue"));
		assertThrows(DataException.class, () -> Games.parse(good.replace("\"red\":{", "\"Red\":{")), "a bad id");
		assertThrows(DataException.class, () -> Games.parse(good.replace("\"family\":\"gen1\"", "\"family\":\"gen9\"")), "a bad family");
		assertThrows(DataException.class, () -> Games.parse(good.replace("\"engine\":\"gen1\"", "\"engine\":\"gen9\"")), "a bad engine");
		assertThrows(DataException.class, () -> Games.parse(good.replace("\"nick\":10", "\"nick\":17")), "a limit out of range");
		assertThrows(DataException.class, () -> Games.parse(good.replace("\"region\":\"kanto\"", "\"region\":\"Kanto\"")), "a bad region");
		assertThrows(DataException.class, () -> Games.parse(good.replace("\"adapter\":true", "\"adapter\":true,\"extra\":1")), "an unknown field");
		assertThrows(DataException.class, () -> Games.parse(good.replace("\"version\":1", "\"version\":2")), "another version");
		assertThrows(DataException.class, () -> Games.parse("{\"version\":1,\"games\":{}}"), "no games");
		assertThrows(DataException.class, () -> Games.parse(good + "x"), "not JSON");
		assertThrows(IllegalArgumentException.class, () -> new Games.Compat(0, 1, 1));
	}

	private static Games assertDoesNotThrowParse(String json) {
		try {
			return Games.parse(json);
		} catch (DataException e) {
			throw new AssertionError(e);
		}
	}
}
