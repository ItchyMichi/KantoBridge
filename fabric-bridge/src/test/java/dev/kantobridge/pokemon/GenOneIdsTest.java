package dev.kantobridge.pokemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The Gen 1 number ↔ Cobblemon id tables (docs/PLAN.md Appendix B). The GameTest {@code IdMappingGameTests} checks the
 * same numbers against Cobblemon's live registries.
 */
class GenOneIdsTest {
	private static final Pattern ID = Pattern.compile("^[a-z0-9]+$");

	@Test
	void all151SpeciesHaveDistinctIds() {
		Set<String> seen = new HashSet<>();
		for (int dex = 1; dex <= GenOneIds.MAX_DEX; dex++) {
			String id = GenOneIds.speciesId(dex);
			assertTrue(ID.matcher(id).matches(), "species " + dex + " id format");
			assertTrue(seen.add(id), "species " + dex + " repeats an id");
			assertEquals(dex, GenOneIds.dexOf(id), "species " + dex + " round trip");
		}
		assertEquals(151, seen.size());
		assertEquals("bulbasaur", GenOneIds.speciesId(1));
		assertEquals("pikachu", GenOneIds.speciesId(25));
		assertEquals("mewtwo", GenOneIds.speciesId(150));
		assertEquals("mew", GenOneIds.speciesId(151));
		assertEquals(0, GenOneIds.dexOf("chikorita"), "beyond Gen 1");
	}

	@Test
	void all165MovesHaveDistinctIds() {
		Set<String> seen = new HashSet<>();
		for (int move = 1; move <= GenOneIds.MAX_MOVE; move++) {
			String id = GenOneIds.moveId(move);
			assertTrue(ID.matcher(id).matches(), "move " + move + " id format");
			assertTrue(seen.add(id), "move " + move + " repeats an id");
			assertEquals(move, GenOneIds.moveOf(id), "move " + move + " round trip");
		}
		assertEquals(165, seen.size());
		// Gen 1's own order is Showdown's numbering (spot checks across the table).
		assertEquals("pound", GenOneIds.moveId(1));
		assertEquals("tackle", GenOneIds.moveId(33));
		assertEquals("tailwhip", GenOneIds.moveId(39));
		assertEquals("psychic", GenOneIds.moveId(94));
		assertEquals("struggle", GenOneIds.moveId(165));
	}

	@Test
	void appendixBSpecialNames() {
		assertEquals("nidoranf", GenOneIds.speciesIdForName("NIDORAN♀"));
		assertEquals("nidoranm", GenOneIds.speciesIdForName("NIDORAN♂"));
		assertEquals("nidoranf", GenOneIds.speciesIdForName("NIDORAN_F"));
		assertEquals("farfetchd", GenOneIds.speciesIdForName("FARFETCH'D"));
		assertEquals("mrmime", GenOneIds.speciesIdForName("MR.MIME"));
		assertEquals("mrmime", GenOneIds.speciesIdForName("Mr. Mime"));
		assertEquals(29, GenOneIds.dexOf(GenOneIds.speciesIdForName("NIDORAN♀")));
		assertEquals(32, GenOneIds.dexOf(GenOneIds.speciesIdForName("NIDORAN♂")));
		assertEquals(83, GenOneIds.dexOf(GenOneIds.speciesIdForName("FARFETCH'D")));
		assertEquals(122, GenOneIds.dexOf(GenOneIds.speciesIdForName("MR.MIME")));

		assertEquals("visegrip", GenOneIds.moveIdForName("VICEGRIP"));
		assertEquals("highjumpkick", GenOneIds.moveIdForName("HI JUMP KICK"));
		assertEquals("psychic", GenOneIds.moveIdForName("PSYCHIC_M"));
		assertEquals(11, GenOneIds.moveOf(GenOneIds.moveIdForName("VICEGRIP")));
		assertEquals(136, GenOneIds.moveOf(GenOneIds.moveIdForName("HI JUMP KICK")));
	}

	@Test
	void plainNamesNeedOnlyTheRule() {
		assertEquals("sandattack", GenOneIds.moveIdForName("SAND-ATTACK"));
		assertEquals("doubleedge", GenOneIds.moveIdForName("DOUBLE-EDGE"));
		assertEquals("selfdestruct", GenOneIds.moveIdForName("SELFDESTRUCT"));
		assertEquals("thundershock", GenOneIds.moveIdForName("THUNDERSHOCK"));
		assertEquals("doubleslap", GenOneIds.moveIdForName("DOUBLESLAP"));
		assertEquals("solarbeam", GenOneIds.moveIdForName("SOLARBEAM"));
		assertEquals("softboiled", GenOneIds.moveIdForName("SOFTBOILED"));
		assertEquals("poisonpowder", GenOneIds.moveIdForName("POISON_POWDER"));
		assertEquals("bulbasaur", GenOneIds.speciesIdForName("BULBASAUR"));
		assertNull(GenOneIds.speciesIdForName("NIDORAN"), "the symbol-stripped name exists nowhere");
		assertNull(GenOneIds.moveIdForName("HYPER VOICE"), "beyond Gen 1");
	}

	@Test
	void numbersOutsideGen1AreRefused() {
		assertFalse(GenOneIds.isDex(0));
		assertFalse(GenOneIds.isDex(152));
		assertFalse(GenOneIds.isMove(0));
		assertFalse(GenOneIds.isMove(166));
		assertThrows(IllegalArgumentException.class, () -> GenOneIds.speciesId(152));
		assertThrows(IllegalArgumentException.class, () -> GenOneIds.moveId(0));
	}
}
