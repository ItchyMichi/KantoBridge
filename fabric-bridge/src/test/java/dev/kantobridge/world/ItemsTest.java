package dev.kantobridge.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.bag.BagItems;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The canonical item catalogue (MULTI_GAME_PLAN §4.9, MG6): the bundled copy is the repository's, and every lookup reads it. */
class ItemsTest {
	private static Path repositoryFile() {
		String dir = System.getProperty("kb.protocolDir");
		if (dir == null) {
			throw new IllegalStateException("kb.protocolDir is not set; run the tests through Gradle");
		}
		return Path.of(dir, "items.json");
	}

	private static String entry(String id, String clazz, String cobblemon, Integer move, String symbol) {
		StringBuilder out = new StringBuilder("\"" + id + "\":{\"class\":\"" + clazz + "\"");
		if (cobblemon != null) {
			out.append(",\"cobblemon\":\"").append(cobblemon).append('"');
		}
		if (move != null) {
			out.append(",\"move\":").append(move);
		}
		out.append(",\"symbols\":{\"gen1\":\"").append(symbol).append("\"}}");
		return out.toString();
	}

	private static String catalogue(String... entries) {
		return "{\"version\":1,\"items\":{" + String.join(",", entries) + "}}";
	}

	@Test
	void bundledCatalogueIsTheRepositoryFile() throws Exception {
		byte[] file = Files.readAllBytes(repositoryFile());
		try (InputStream in = Items.class.getResourceAsStream(Items.RESOURCE)) {
			assertTrue(in != null, "the build bundles protocol/items.json");
			assertArrayEquals(file, in.readAllBytes(), "byte for byte");
		}
		Items parsed = Items.parse(new String(file, StandardCharsets.UTF_8));
		assertEquals(parsed.entries(), Items.bundled().entries());
		assertEquals(128, Items.ids().size(), "the catalogue written for MG6");
	}

	@Test
	void everyIdIsItsGen1SymbolAndRoundTrips() {
		for (String id : Items.ids()) {
			Items.Item item = Items.get(id);
			assertNotNull(item);
			assertEquals(id, item.id());
			String symbol = Items.symbolOf("gen1", id);
			assertNotNull(symbol, id);
			assertEquals(id, symbol.toLowerCase(Locale.ROOT), "the id is the Gen 1 symbol lower-cased");
			assertEquals(id, Items.canonicalOf("gen1", symbol));
			assertTrue(Items.CLASSES.contains(item.clazz()), id);
		}
		assertNull(Items.canonicalOf("gen1", "NO_SUCH_THING"));
		assertNull(Items.canonicalOf("gen3", "POTION"), "no Gen 3 symbols until its adapter");
		assertNull(Items.symbolOf(null, "potion"));
		assertFalse(Items.known("Potion"));
		assertNull(Items.get(null));
	}

	@Test
	void theMirrorIsTodaysTwentyNineBattleItems() {
		List<String> mirrored = Items.mirrored();
		assertEquals(29, mirrored.size(), "5 balls, 17 medicines, 7 battle boosters");
		Map<String, String> table = BagItems.table();
		assertEquals(29, table.size());
		for (String id : mirrored) {
			Items.Item item = Items.get(id);
			assertTrue(item.mirrored() && Items.MIRRORED_CLASSES.contains(item.clazz()), id);
			assertEquals(item.cobblemon(), table.get(item.symbol("gen1")), id);
		}
		assertEquals(table.size(), new HashSet<>(table.values()).size(), "each Gen 1 item has its own Cobblemon item");
		assertEquals("paralyze_heal", Items.cobblemonPath("parlyz_heal"));
		assertEquals("elixir", Items.cobblemonPath("elixer"));
		assertEquals("x_defence", Items.cobblemonPath("x_defend"));
		assertEquals("x_special_attack", Items.cobblemonPath("x_special"));
		// Stones carry a Cobblemon item for the party flow but are not mirrored into the inventory; candy is ledger-only.
		assertEquals("moon_stone", Items.cobblemonPath("moon_stone"));
		assertFalse(mirrored.contains("moon_stone"));
		assertNull(Items.cobblemonPath("rare_candy"));
		assertFalse(mirrored.contains("rare_candy"));
		assertNull(BagItems.cobblemonPath("MOON_STONE"));
	}

	@Test
	void keyItemsNeverCrossAndMachinesCrossByMove() {
		List<String> crossing = Items.crossing();
		assertTrue(crossing.contains("potion") && crossing.contains("nugget") && crossing.contains("tm_mega_punch"));
		for (String key : Set.of("bicycle", "hm_cut", "town_map", "s_s_ticket", "coin_case")) {
			assertFalse(crossing.contains(key), key);
			assertEquals(Items.KEY, Items.clazz(key), key);
			assertNull(Items.cobblemonPath(key), key);
		}
		long tms = Items.ids().stream().filter(id -> Items.TM.equals(Items.clazz(id))).count();
		assertEquals(50, tms);
		Set<Integer> moves = new HashSet<>();
		for (String id : Items.ids()) {
			if (Items.TM.equals(Items.clazz(id))) {
				Integer move = Items.move(id);
				assertNotNull(move, id);
				assertTrue(move >= 1 && move <= 165, id);
				assertTrue(moves.add(move), "one TM per move: " + id);
			}
		}
		assertEquals(5, Items.ids().stream().filter(id -> id.startsWith("hm_")).count());
		assertEquals(5, Items.move("tm_mega_punch"));
		assertEquals(15, Items.move("hm_cut"));
		assertEquals("tm_mega_punch", Items.byMove("gen1", 5));
		assertEquals("tm_substitute", Items.byMove(null, 164));
		assertNull(Items.byMove("gen3", 5), "no Gen 3 TMs until its adapter");
		assertNull(Items.byMove("gen1", 999));
		assertNull(Items.byMove("gen1", 15), "HMs are key items, not TMs");
	}

	@Test
	void parseRefusesBadCatalogues() throws Exception {
		Items good = Items.parse(catalogue(entry("potion", "medicine", "potion", null, "POTION"), entry("tm_toxic", "tm", null, 92, "TM_TOXIC")));
		assertEquals(List.of("potion", "tm_toxic"), good.entries().keySet().stream().toList());
		assertEquals("tm_toxic", good.tmByMove("gen1", 92));
		assertEquals(List.of("potion"), good.mirroredIds());
		assertThrows(DataException.class, () -> Items.parse(catalogue(entry("potion", "snack", null, null, "POTION"))));
		assertThrows(DataException.class, () -> Items.parse(catalogue(entry("Potion", "medicine", null, null, "POTION"))));
		assertThrows(DataException.class, () -> Items.parse(catalogue(entry("potion", "medicine", null, 0, "POTION"))));
		assertThrows(DataException.class, () -> Items.parse(catalogue(entry("potion", "medicine", "Potion!", null, "POTION"))));
		assertThrows(DataException.class, () -> Items.parse(catalogue(entry("a", "medicine", null, null, "SAME"), entry("b", "medicine", null, null, "SAME"))),
				"a family names a symbol once");
		assertThrows(DataException.class, () -> Items.parse("{\"version\":1,\"items\":{\"potion\":{\"class\":\"medicine\",\"symbols\":{\"gen9\":\"POTION\"}}}}"));
		assertThrows(DataException.class, () -> Items.parse("{\"version\":1,\"items\":{\"potion\":{\"class\":\"medicine\",\"symbols\":{},\"extra\":1}}}"));
		assertThrows(DataException.class, () -> Items.parse("{\"version\":2,\"items\":{}}"));
		assertThrows(DataException.class, () -> Items.parse("{\"version\":1,\"items\":{}}"));
		assertThrows(DataException.class, () -> Items.parse("not json"));
	}
}
