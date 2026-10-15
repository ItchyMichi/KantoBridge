package dev.kantobridge.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.world.Items;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The one trainer economy's rules (MULTI_GAME_PLAN §4.9, MG6), plain Java over the bundled item catalogue. */
class LedgerTest {
	private static final Items CAT = Items.bundled();
	private static final LedgerRules.Limits GEN1 = LedgerRules.Limits.GEN1;

	@Test
	void aProjectionFillsTwentySlotsOfNinetyNineInClassOrder() {
		Ledger ledger = Ledger.NONE.add("potion", 150).add("poke_ball", 5).add("nugget", 2).add("tm_mega_punch", 1);
		// 25 more medicines and field items: more kinds than a Gen 1 bag holds
		List<String> extra = new ArrayList<>();
		for (String id : CAT.crossingIds()) {
			String clazz = CAT.entry(id).clazz();
			if ((clazz.equals("medicine") || clazz.equals("field") || clazz.equals("battle")) && !id.equals("potion") && extra.size() < 25) {
				extra.add(id);
				ledger = ledger.add(id, 1);
			}
		}
		assertEquals(25, extra.size());
		LedgerRules.Projected projected = LedgerRules.project(ledger, CAT, "gen1", GEN1);
		assertEquals(20, projected.items().size(), "a Gen 1 bag holds 20 kinds");
		assertEquals(99, projected.items().get("POTION"), "99 a stack");
		assertEquals(51, projected.left().get("potion"), "the rest stays in the trunk");
		assertEquals("POKE_BALL", projected.items().keySet().iterator().next(), "balls first, then medicine");
		assertTrue(projected.items().containsKey("POKE_BALL") && projected.items().containsKey("POTION"));
		assertFalse(projected.items().containsKey("NUGGET"), "valuables come after medicine, battle and field items: no slot left");
		assertEquals(2, projected.left().get("nugget"));
		assertEquals(1, projected.left().get("tm_mega_punch"));
		int kept = 0;
		for (String id : extra) {
			kept += projected.items().containsKey(CAT.symbol("gen1", id)) ? 1 : 0;
		}
		assertEquals(18, kept, "20 slots: the ball, the potion and 18 of the others");
	}

	@Test
	void keyItemsAndGamesWithoutSymbolsStayInTheTrunk() {
		Ledger ledger = Ledger.NONE.add("bicycle", 1).add("potion", 2);
		LedgerRules.Projected gen1 = LedgerRules.project(ledger, CAT, "gen1", GEN1);
		assertEquals(Map.of("POTION", 2), gen1.items());
		assertEquals(Map.of("bicycle", 1), gen1.left(), "a key item never crosses");
		LedgerRules.Projected gen3 = LedgerRules.project(ledger, CAT, "gen3", LedgerRules.Limits.of("gen3"));
		assertEquals(Map.of("POTION", 2), gen3.items(), "MG7: Emerald's symbols (protocol/items.json)");
		assertEquals(Map.of("bicycle", 1), gen3.left());
		LedgerRules.Projected gen2 = LedgerRules.project(ledger, CAT, "gen2", LedgerRules.Limits.of("gen2"));
		assertEquals(Map.of("POTION", 2), gen2.items(), "MG9: Crystal's symbols (protocol/items.json)");
		assertEquals(Map.of("bicycle", 1), gen2.left());
		LedgerRules.Projected other = LedgerRules.project(ledger, CAT, "gen4", LedgerRules.Limits.of("gen4"));
		assertTrue(other.items().isEmpty(), "a family without symbols holds nothing");
		assertEquals(Map.of("bicycle", 1, "potion", 2), other.left());
		assertEquals(LedgerRules.Limits.GEN3, LedgerRules.Limits.of("gen3"));
		assertEquals(LedgerRules.Limits.GEN2, LedgerRules.Limits.of("gen2"));
		assertEquals(LedgerRules.Limits.DEFAULT, LedgerRules.Limits.of("gen4"));
		assertEquals(GEN1, LedgerRules.Limits.of("gen1"));
	}

	@Test
	void machinesCrossByMove() {
		Ledger ledger = Ledger.NONE.add("tm_mega_punch", 1).add("tm_substitute", 2);
		LedgerRules.Projected gen1 = LedgerRules.project(ledger, CAT, "gen1", GEN1);
		assertEquals(Map.of("TM_MEGA_PUNCH", 1, "TM_SUBSTITUTE", 2), gen1.items());
		assertEquals("TM_MEGA_PUNCH", LedgerRules.symbolFor(CAT, "gen1", "tm_mega_punch"));
		assertNull(LedgerRules.symbolFor(CAT, "gen3", "tm_mega_punch"), "Gen 3 has no TM for the move");
		assertEquals("TM06", LedgerRules.symbolFor(CAT, "gen3", "tm_toxic"), "a TM both generations have crosses by its move");
		assertEquals("TM01", LedgerRules.symbolFor(CAT, "gen3", "tm_focus_punch"));
		assertNull(LedgerRules.symbolFor(CAT, "gen1", "hm_cut"), "HMs are key items");
		assertEquals(Map.of("tm_toxic", 1), LedgerRules.canonical(CAT, "gen1", Map.of("TM_TOXIC", 1, "HM_CUT", 1, "NO_SUCH", 3)));
	}

	@Test
	void theReadBackAppliesWhatChangedInTheFollowedGame() {
		Ledger ledger = Ledger.NONE.add("potion", 3).add("poke_ball", 10).withMoney(3000);
		LedgerRules.Projection projected = new LedgerRules.Projection(Map.of("POTION", 3, "POKE_BALL", 10), 3000, "deadbeefdeadbeef");
		// a potion drunk, a nugget found, prize money won
		Ledger after = LedgerRules.readBack(ledger, CAT, "gen1", projected, Map.of("POTION", 2, "POKE_BALL", 10, "NUGGET", 1), 5000);
		assertEquals(2, after.count("potion"));
		assertEquals(10, after.count("poke_ball"));
		assertEquals(1, after.count("nugget"));
		assertEquals(5000, after.money());
		// a blackout halves the money; a key item appearing in the bag changes nothing
		Ledger halved = LedgerRules.readBack(after, CAT, "gen1", new LedgerRules.Projection(Map.of("POTION", 2, "POKE_BALL", 10, "NUGGET", 1), 5000, "x"),
				Map.of("POTION", 2, "POKE_BALL", 10, "NUGGET", 1, "BICYCLE", 1), 2500);
		assertEquals(2500, halved.money());
		assertEquals(0, halved.count("bicycle"));
		// a report without money leaves the wallet
		assertEquals(2500, LedgerRules.readBack(halved, CAT, "gen1", new LedgerRules.Projection(Map.of(), 2500, "y"), Map.of(), -1).money());
		// what the trunk holds beyond the projection is untouched by the diff
		Ledger trunk = ledger.add("potion", 200);
		Ledger diffed = LedgerRules.readBack(trunk, CAT, "gen1", new LedgerRules.Projection(Map.of("POTION", 99), 3000, "z"), Map.of("POTION", 98), 3000);
		assertEquals(202, diffed.count("potion"), "one potion used; the 104 in the trunk stay");
	}

	@Test
	void anAdoptionMergesASaveOnce() {
		Ledger ledger = Ledger.NONE.add("potion", 1).withMoney(500);
		Ledger adopted = LedgerRules.adopt(ledger, CAT, "gen1", Map.of("POTION", 2, "BICYCLE", 1, "TM_MEGA_PUNCH", 1, "ODDITY", 4), 1000, "pair-1");
		assertEquals(3, adopted.count("potion"));
		assertEquals(1, adopted.count("tm_mega_punch"));
		assertEquals(0, adopted.count("bicycle"), "key items stay in their game");
		assertEquals(1500, adopted.money());
		assertTrue(adopted.isAdopted("pair-1"));
		assertFalse(adopted.isAdopted("pair-2"));
		assertFalse(ledger.isAdopted("pair-1"), "the earlier ledger is unchanged");
	}

	@Test
	void aUseDebitsTheLedgerAndTheProjectionFollowsWhatDidNotFit() {
		Ledger ledger = Ledger.NONE.add("potion", 3).add("poke_ball", 1);
		assertEquals(2, LedgerRules.use(ledger, CAT, "POTION", 1).count("potion"));
		assertEquals(0, LedgerRules.use(ledger, CAT, "POKE_BALL", 5).count("poke_ball"), "never below zero");
		assertEquals(ledger, LedgerRules.use(ledger, CAT, "NO_SUCH", 1));
		LedgerRules.Projection projected = new LedgerRules.Projection(Map.of("POTION", 3, "POKE_BALL", 1), 0, "s");
		assertEquals(Map.of("POTION", 2), projected.less(Map.of("POTION", 1, "POKE_BALL", 1)).items());
		assertEquals(Map.of("POTION", 2), projected.less(Map.of("POTION", 1, "POKE_BALL", 9)).items(), "never below zero");
	}

	@Test
	void theMirrorShowsTheLedgersBattleItemsOnly() {
		Ledger ledger = Ledger.NONE.add("potion", 2).add("nugget", 1).add("moon_stone", 1).add("poke_ball", 20000);
		Map<String, Integer> targets = LedgerRules.mirrorTargets(ledger, CAT);
		assertEquals(29, targets.size());
		assertEquals(2, targets.get("POTION"));
		assertEquals(9999, targets.get("POKE_BALL"), "capped like the protocol's counts");
		assertEquals(0, targets.get("ANTIDOTE"));
		assertFalse(targets.containsKey("NUGGET") || targets.containsKey("MOON_STONE"));
	}

	@Test
	void stampsAreSixteenHexDigits() {
		String stamp = LedgerRules.newStamp(new Random(7));
		assertTrue(stamp.matches("^[0-9a-f]{16}$"), stamp);
		assertEquals(stamp, LedgerRules.newStamp(new Random(7)));
	}
}
