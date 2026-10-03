package dev.kantobridge.bag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The Gen 1 battle items Minecraft mirrors (Appendix B, verified ids) and the bookkeeping of the mirror (P17). */
class BagLedgerTest {
	/** An inventory to change as the ledger says, like BagService does. */
	private static final class Inventory {
		final Map<String, Integer> counts = new LinkedHashMap<>();

		Inventory(Map<String, Integer> start) {
			this.counts.putAll(start);
		}

		void apply(Map<String, Integer> changes) {
			changes.forEach((item, delta) -> this.counts.merge(item, delta, Integer::sum));
		}

		int get(String item) {
			return this.counts.getOrDefault(item, 0);
		}
	}

	@Test
	void theItemTable() {
		Map<String, String> table = BagItems.table();
		assertEquals(29, table.size(), "5 balls, 17 medicines, 7 battle boosters");
		assertEquals(table.size(), new HashSet<>(table.values()).size(), "each Gen 1 item has its own Cobblemon item");
		for (Map.Entry<String, String> entry : table.entrySet()) {
			assertTrue(entry.getKey().matches("^[A-Z0-9_]+$"), entry.getKey());
			assertTrue(entry.getValue().matches("^[a-z0-9_]+$"), entry.getValue());
			assertEquals(entry.getKey(), BagItems.gen1Of(entry.getValue()));
		}
		// Names that differ between Gen 1 and Cobblemon 1.8.1.
		assertEquals("paralyze_heal", BagItems.cobblemonPath("PARLYZ_HEAL"));
		assertEquals("elixir", BagItems.cobblemonPath("ELIXER"));
		assertEquals("max_elixir", BagItems.cobblemonPath("MAX_ELIXER"));
		assertEquals("x_defence", BagItems.cobblemonPath("X_DEFEND"));
		assertEquals("x_special_attack", BagItems.cobblemonPath("X_SPECIAL"));
		// Key items, TMs, stones and the like stay Gen 1-only.
		for (String gen1Only : Set.of("BICYCLE", "TM_MEGA_PUNCH", "MOON_STONE", "RARE_CANDY", "REPEL", "ESCAPE_ROPE", "OLD_ROD")) {
			assertNull(BagItems.cobblemonPath(gen1Only), gen1Only);
		}
		assertNull(BagItems.gen1Of("rare_candy"));
	}

	@Test
	void aSyncNamesTheChangesAndNothingWhenTheyAgree() {
		BagLedger ledger = new BagLedger();
		assertFalse(ledger.known());
		Map<String, Integer> bag = Map.of("POKE_BALL", 5, "POTION", 2, "BICYCLE", 1, "TOWN_MAP", 1);
		Map<String, Integer> changes = ledger.sync(bag, Map.of("POTION", 3, "ANTIDOTE", 1));
		assertEquals(Map.of("POKE_BALL", 5, "POTION", -1, "ANTIDOTE", -1), changes, "absent items have none left; key items are ignored");
		ledger.settled(Map.of("POKE_BALL", 5, "POTION", 2));
		assertTrue(ledger.known());
		// The same bag again against the same inventory: nothing to do, so nothing echoes back.
		assertTrue(ledger.sync(bag, Map.of("POKE_BALL", 5, "POTION", 2)).isEmpty());
		assertTrue(ledger.observe(Map.of("POKE_BALL", 5, "POTION", 2), false, 0).isEmpty());
		// Counts are capped at the protocol's maximum.
		assertEquals(Map.of("POKE_BALL", 9999 - 5), ledger.sync(Map.of("POKE_BALL", 20000, "POTION", 2), Map.of("POKE_BALL", 5, "POTION", 2)));
	}

	@Test
	void consumptionIsReportedOnce() {
		BagLedger ledger = new BagLedger();
		assertTrue(ledger.observe(Map.of(), false, 0).isEmpty(), "nothing before the first sync");
		ledger.sync(Map.of("POKE_BALL", 5, "POTION", 2), Map.of("POKE_BALL", 5, "POTION", 2));
		ledger.settled(Map.of("POKE_BALL", 5, "POTION", 2));
		assertEquals(Map.of("POKE_BALL", 1), ledger.observe(Map.of("POKE_BALL", 4, "POTION", 2), false, 10));
		assertTrue(ledger.observe(Map.of("POKE_BALL", 4, "POTION", 2), false, 15).isEmpty(), "reported once");
		assertEquals(4, ledger.reference("POKE_BALL"));
		assertEquals(1, ledger.inFlight("POKE_BALL"));
		// A missed ball picked up again is no gain: the hidden game paid for it, its next sync takes it back.
		assertTrue(ledger.observe(Map.of("POKE_BALL", 5, "POTION", 2), false, 20).isEmpty());
		assertEquals(4, ledger.reference("POKE_BALL"));
		// Throwing that extra ball again reports nothing new.
		assertTrue(ledger.observe(Map.of("POKE_BALL", 4, "POTION", 2), false, 25).isEmpty());
		assertEquals(Map.of("POKE_BALL", 2, "POTION", 2), ledger.observe(Map.of("POKE_BALL", 2), false, 30));
	}

	@Test
	void aBattleReportsItsNetUseAfterwards() {
		BagLedger ledger = new BagLedger();
		ledger.settled(Map.of("POTION", 3, "POKE_BALL", 4));
		// Cobblemon takes a potion when it is chosen, and a thrown ball.
		assertTrue(ledger.observe(Map.of("POTION", 2, "POKE_BALL", 3), true, 0).isEmpty(), "held during the battle");
		// The battle ends before the potion's turn: Cobblemon gives it back. The ball is gone.
		assertEquals(Map.of("POKE_BALL", 1), ledger.observe(Map.of("POTION", 3, "POKE_BALL", 3), false, 5));
		assertEquals(3, ledger.reference("POTION"));
		assertEquals(-1, new BagLedger().reference("POTION"));
	}

	@Test
	void aSyncCrossingAUseNeitherUndoesNorDoublesIt() {
		BagLedger ledger = new BagLedger();
		Inventory inventory = new Inventory(Map.of());
		inventory.apply(ledger.sync(Map.of("POKE_BALL", 5), inventory.counts));
		ledger.settled(inventory.counts);
		assertEquals(5, inventory.get("POKE_BALL"));
		// A ball is thrown: reported, in flight.
		inventory.counts.put("POKE_BALL", 4);
		assertEquals(Map.of("POKE_BALL", 1), ledger.observe(inventory.counts, false, 100));
		// The hidden game's sync after a snapshot crosses the use: it still says 5. The thrown ball doesn't come back.
		inventory.apply(ledger.sync(Map.of("POKE_BALL", 5, "POTION", 1), inventory.counts));
		ledger.settled(inventory.counts);
		assertEquals(4, inventory.get("POKE_BALL"), "an in-flight use was undone");
		assertEquals(1, inventory.get("POTION"), "the rest of the crossing sync still applies");
		assertEquals(1, ledger.inFlight("POKE_BALL"));
		// The hidden game applies the use and syncs 4: acknowledged, nothing changes, nothing is reported again.
		assertTrue(ledger.sync(Map.of("POKE_BALL", 4, "POTION", 1), inventory.counts).isEmpty());
		ledger.settled(inventory.counts);
		assertEquals(0, ledger.inFlight("POKE_BALL"));
		assertTrue(ledger.observe(inventory.counts, false, 110).isEmpty(), "a use was counted twice");
		// Two more throws, acknowledged one at a time.
		inventory.counts.put("POKE_BALL", 2);
		assertEquals(Map.of("POKE_BALL", 2), ledger.observe(inventory.counts, false, 120));
		assertTrue(ledger.sync(Map.of("POKE_BALL", 3, "POTION", 1), inventory.counts).isEmpty(), "one of two applied: still 2 here");
		assertEquals(1, ledger.inFlight("POKE_BALL"));
		// A purchase while a use is in flight comes through, less the use.
		inventory.apply(ledger.sync(Map.of("POKE_BALL", 8, "POTION", 1), inventory.counts));
		ledger.settled(inventory.counts);
		assertEquals(7, inventory.get("POKE_BALL"));
		assertTrue(ledger.sync(Map.of("POKE_BALL", 7, "POTION", 1), inventory.counts).isEmpty());
		assertEquals(0, ledger.inFlight("POKE_BALL"));
	}

	@Test
	void aUseNoSyncEverShowsExpires() {
		BagLedger ledger = new BagLedger();
		Inventory inventory = new Inventory(Map.of());
		inventory.apply(ledger.sync(Map.of("POTION", 2), inventory.counts));
		ledger.settled(inventory.counts);
		inventory.counts.put("POTION", 1);
		ledger.observe(inventory.counts, false, 100);
		assertTrue(ledger.expire(inventory.counts, 100 + BagLedger.IN_FLIGHT_TICKS).isEmpty(), "not yet");
		// Lost with a link: the hidden game still has it, so the potion comes back.
		assertEquals(Map.of("POTION", 1), ledger.expire(inventory.counts, 101 + BagLedger.IN_FLIGHT_TICKS));
		assertEquals(0, ledger.inFlight("POTION"));
	}
}
