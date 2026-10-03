package dev.kantobridge.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.McBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The save-pair rules (M10 crash recovery; docs/PROTOCOL.md {@code save.loaded}, {@code save.mismatch}): which
 * {@code save.loaded} is in step with the world's last save pair, and which mismatch each other one is.
 */
class SavePairRulesTest {
	private static final String PAIR = "0a1b2c3d4e5f6071";
	private static final String OTHER = "f0e1d2c3b4a59687";

	@BeforeAll
	static void setUp() {
		// SavePair registers its attachment type when it loads.
		McBootstrap.ensure();
	}

	private static SavePairRules.Loaded loaded(String pair, long at, int ops) {
		return new SavePairRules.Loaded(false, pair, at, ops, null);
	}

	@Test
	void aWorldThatNeverSavedComparesNothing() {
		assertNull(SavePairRules.compare(SavePair.NONE, loaded(PAIR, 100, 3)));
		assertNull(SavePairRules.compare(SavePair.NONE, new SavePairRules.Loaded(true, null, -1, 0, null)));
	}

	@Test
	void theSamePairWithTheSameCountIsInStep() {
		assertNull(SavePairRules.compare(new SavePair(PAIR, 100, 0), loaded(PAIR, 100, 0)));
		assertNull(SavePairRules.compare(new SavePair(PAIR, 100, 4), loaded(PAIR, 100, 4)));
		assertNull(SavePairRules.compare(new SavePair(PAIR, -1, 2), loaded(PAIR, -1, 2)), "times needn't be known");
	}

	@Test
	void fewerOperationsOnTheHiddenSideMeanTheHiddenGameWentBack() {
		SavePairRules.Mismatch mismatch = SavePairRules.compare(new SavePair(PAIR, 100, 3), loaded(PAIR, 100, 1));
		assertEquals(SavePairRules.Kind.HIDDEN_BEHIND, mismatch.kind());
		assertEquals(2, mismatch.ops(), "the difference: Pokémon still in Minecraft");
		JsonObject json = mismatch.toJson();
		assertEquals("hidden_behind", json.get("kind").getAsString());
		assertEquals(2, json.get("ops").getAsInt());
		assertEquals(100, json.get("hiddenAt").getAsLong());
		assertEquals(100, json.get("worldAt").getAsLong());
	}

	@Test
	void moreOperationsOnTheHiddenSideMeanMinecraftWentBack() {
		SavePairRules.Mismatch mismatch = SavePairRules.compare(new SavePair(PAIR, -1, 0), loaded(PAIR, -1, 5));
		assertEquals(SavePairRules.Kind.WORLD_BEHIND, mismatch.kind());
		assertEquals(5, mismatch.ops());
		JsonObject json = mismatch.toJson();
		assertFalse(json.has("hiddenAt") || json.has("worldAt"), "unknown times are left out");
	}

	@Test
	void anotherPairIsOlderOrNewerByItsTime() {
		SavePairRules.Mismatch older = SavePairRules.compare(new SavePair(PAIR, 2_000, 0), loaded(OTHER, 1_000, 0));
		assertEquals(SavePairRules.Kind.HIDDEN_OLDER, older.kind());
		assertEquals(-1, older.ops());
		assertFalse(older.toJson().has("ops"));
		assertEquals(1_000, older.hiddenAt());
		assertEquals(2_000, older.worldAt());
		assertEquals(SavePairRules.Kind.HIDDEN_NEWER, SavePairRules.compare(new SavePair(PAIR, 2_000, 0), loaded(OTHER, 3_000, 7)).kind());
	}

	@Test
	void anotherPairOfUnknownAgeIsOther() {
		assertEquals(SavePairRules.Kind.OTHER, SavePairRules.compare(new SavePair(PAIR, -1, 0), loaded(OTHER, 1_000, 0)).kind());
		assertEquals(SavePairRules.Kind.OTHER, SavePairRules.compare(new SavePair(PAIR, 1_000, 0), loaded(OTHER, -1, 0)).kind());
		assertEquals(SavePairRules.Kind.OTHER, SavePairRules.compare(new SavePair(PAIR, 1_000, 0), loaded(OTHER, 1_000, 0)).kind(),
				"the same second says nothing about which came first");
	}

	@Test
	void aNewGameInAPairedWorld() {
		SavePairRules.Mismatch mismatch = SavePairRules.compare(new SavePair(PAIR, 2_000, 1), new SavePairRules.Loaded(true, null, -1, 2, null));
		assertEquals(SavePairRules.Kind.NEW_GAME, mismatch.kind());
		JsonObject json = mismatch.toJson();
		assertEquals("new_game", json.get("kind").getAsString());
		assertTrue(json.has("worldAt") && !json.has("hiddenAt") && !json.has("ops"));
	}

	@Test
	void theCountNeverOverflowsAndTheCodecKeepsUnknownsOut() {
		SavePair full = new SavePair(PAIR, 5, Integer.MAX_VALUE);
		assertEquals(Integer.MAX_VALUE, full.counted().ops());
		assertEquals(1, SavePair.NONE.counted().ops());
		assertNull(SavePair.NONE.counted().pair());
	}
}
