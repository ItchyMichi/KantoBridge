package dev.kantobridge.dungeon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.zone.ZoneObjects;
import dev.kantobridge.routes.gen.Cell;
import dev.kantobridge.routes.gen.PmdFloor;
import dev.kantobridge.routes.gen.PmdFloorGenerator;
import dev.kantobridge.routes.gen.PmdParams;
import dev.kantobridge.routes.gen.RouteRng;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** What a dungeon floor holds (WR7): Pokémon at enemy spots, items at item spots, the treasure, the item limits. */
class DungeonContentsTest {
	static final List<DungeonContents.Mon> POOL = List.of(new DungeonContents.Mon("cobblemon:pidgey", 5, 60),
			new DungeonContents.Mon("cobblemon:rattata", 7, 40));
	static final List<String> ALLOWED = List.of("POTION", "ANTIDOTE", "SUPER_POTION", "GREAT_BALL", "NUGGET", "HYPER_POTION", "REVIVE");
	static final ZoneObjects.ItemTiers TIERS = ZoneObjects.ItemTiers.bundled();

	@Test
	void pokemonStandOnEnemySpotsAtTheirFloorsLevels() {
		PmdFloor pmd = PmdFloorGenerator.generate(PmdParams.standard(), 7);
		DungeonFloorPlan plan = new DungeonFloorPlan(pmd, 0, 0, 64, false);
		DungeonContents.Floor floor = DungeonContents.make(plan, 5, 3, POOL, ALLOWED, TIERS, 99, false);
		assertEquals(pmd.enemies().size(), floor.spawns().size());
		for (DungeonContents.Spawn s : floor.spawns()) {
			assertTrue(pmd.enemies().contains(s.cell()));
			int bonus = 2; // floor 5: (5 - 1) / 2
			assertTrue(s.species().equals("cobblemon:pidgey") && s.level() == 5 + bonus || s.species().equals("cobblemon:rattata") && s.level() == 7 + bonus);
		}
		assertTrue(DungeonContents.make(plan, 1, 3, List.of(), ALLOWED, TIERS, 99, false).spawns().isEmpty(), "no pool, no Pokémon");
	}

	@Test
	void itemsComeFromTheFloorsTiersAndTheLastFloorHasATreasure() {
		PmdFloor pmd = PmdFloorGenerator.generate(PmdParams.standard(), 11);
		DungeonFloorPlan plan = new DungeonFloorPlan(pmd, 0, 0, 64, false);
		DungeonContents.Floor first = DungeonContents.make(plan, 1, 42, POOL, ALLOWED, TIERS, 5, false);
		assertEquals(pmd.items().size(), first.items().size());
		Set<String> ids = new HashSet<>();
		for (DungeonContents.Item item : first.items()) {
			assertTrue(item.id().matches("v42\\.f1\\.i[0-9]+"), item.id());
			assertTrue(ids.add(item.id()));
			assertTrue(TIERS.forLevelOffset(1).contains(item.item()) && ALLOWED.contains(item.item()), item.item());
		}
		DungeonFloorPlan lastPlan = new DungeonFloorPlan(pmd, 0, 0, 64, true);
		DungeonContents.Floor last = DungeonContents.make(lastPlan, 5, 42, POOL, ALLOWED, TIERS, 5, false);
		assertEquals(pmd.items().size() + DungeonContents.TREASURE, last.items().size(), "the treasure by the exit");
		Cell exit = pmd.stairs();
		for (DungeonContents.Item item : last.items().subList(pmd.items().size(), last.items().size())) {
			assertFalse(item.cell().equals(exit));
			assertEquals(PmdFloor.Tile.FLOOR, pmd.at(item.cell()));
			assertTrue(Math.abs(item.cell().x() - exit.x()) + Math.abs(item.cell().y() - exit.y()) <= 4, "near the exit");
		}
	}

	@Test
	void atMostOneNuggetAVisitAndNeverAForbiddenItem() {
		int nuggets = 0;
		boolean placed = false;
		for (int floor = 1; floor <= 9; floor++) {
			for (long seed = 0; seed < 20; seed++) {
				PmdFloor pmd = PmdFloorGenerator.generate(PmdParams.standard(), seed * 31 + floor);
				DungeonContents.Floor f = DungeonContents.make(new DungeonFloorPlan(pmd, 0, 0, 64, floor == 9), floor, 1, POOL,
						List.of("NUGGET", "HYPER_POTION"), TIERS, seed, placed);
				for (DungeonContents.Item item : f.items()) {
					nuggets += item.item().equals("NUGGET") ? 1 : 0;
				}
				placed |= f.nugget();
			}
		}
		assertTrue(nuggets <= 1, "nuggets " + nuggets);
		assertFalse(ZoneObjects.NOT_IN_DUNGEONS.contains("POTION"));
		assertTrue(ZoneObjects.NOT_IN_DUNGEONS.containsAll(List.of("RARE_CANDY", "MASTER_BALL")));
	}

	@Test
	void laterPokemonAppearAwayFromPlayersAndHousesAreFloorCells() {
		PmdFloor pmd = PmdFloorGenerator.generate(PmdParams.standard(), 3);
		Cell player = pmd.player();
		assertNotNull(player);
		RouteRng rng = new RouteRng(1);
		for (int i = 0; i < 50; i++) {
			Cell spot = DungeonContents.later(pmd, List.of(player), rng);
			assertNotNull(spot);
			assertTrue(Math.abs(spot.x() - player.x()) + Math.abs(spot.y() - player.y()) >= DungeonContents.LATER_AWAY);
			assertEquals(PmdFloor.Tile.FLOOR, pmd.at(spot));
		}
		for (Cell c : DungeonContents.house(pmd)) {
			assertEquals(PmdFloor.Tile.FLOOR, pmd.at(c));
			assertTrue(pmd.house()[c.y() * pmd.width() + c.x()]);
		}
	}

	@Test
	void aMonsterHouseFillsAroundWhoeverStepsIn() {
		PmdFloor pmd = PmdFloorGenerator.generate(PmdParams.standard().withRooms(0, 100, 0), 0x2B7);
		List<Cell> house = DungeonContents.house(pmd);
		assertFalse(house.isEmpty());
		Cell by = house.get(0);
		Cell other = house.get(1);
		RouteRng rng = new RouteRng(8);
		Set<Integer> counts = new HashSet<>();
		for (int i = 0; i < 200; i++) {
			List<Cell> spots = DungeonContents.houseSpots(house, by, Set.of(other), rng);
			counts.add(spots.size());
			assertEquals(spots.size(), new HashSet<>(spots).size(), "two on one cell");
			for (Cell c : spots) {
				assertTrue(house.contains(c) && !c.equals(by) && !c.equals(other), "on " + c);
				assertTrue(Math.max(Math.abs(c.x() - by.x()), Math.abs(c.y() - by.y())) <= DungeonContents.HOUSE_NEAR, by + " far from " + c);
			}
		}
		assertEquals(Set.of(8, 9, 10, 11), counts);
		// with little room near you, what room there is
		List<Cell> small = List.of(new Cell(5, 5), new Cell(6, 5), new Cell(7, 5), new Cell(20, 5));
		assertEquals(List.of(new Cell(7, 5)), DungeonContents.houseSpots(small, new Cell(5, 5), Set.of(new Cell(6, 5)), rng));
	}
}
