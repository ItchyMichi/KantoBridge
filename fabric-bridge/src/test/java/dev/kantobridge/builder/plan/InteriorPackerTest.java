package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.world.Scale;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Interior packing: under the door, floor chains downwards, ≥16-block gaps, deeper layers first, then sideways. */
class InteriorPackerTest {
	@Test
	void interiorsGoUnderTheirDoorOneLayerDown() {
		InteriorPacker packer = new InteriorPacker(14);
		InteriorPacker.Spot spot = packer.place(100, 200, 20, 16, 1);
		assertEquals(new InteriorPacker.Spot(100, 200, 1, false), spot);
		// The next floor of the same building stacks below, at the same position.
		InteriorPacker.Spot floor2 = packer.place(104, 202, 16, 16, spot.layer() + 1);
		assertEquals(new InteriorPacker.Spot(104, 202, 2, false), floor2);
	}

	@Test
	void anOccupiedSpotSpillsToADeeperLayer() {
		InteriorPacker packer = new InteriorPacker(14);
		packer.place(0, 0, 20, 20, 1);
		InteriorPacker.Spot second = packer.place(10, 10, 8, 8, 1);
		assertEquals(2, second.layer());
		assertFalse(second.moved());
	}

	@Test
	void interiorsKeepSixteenBlocksApart() {
		InteriorPacker packer = new InteriorPacker(14);
		packer.place(100, 0, 20, 20, 1); // with its ring: x 99..121
		// Ring to ring exactly 16 blocks: same layer.
		assertEquals(1, packer.place(138, 0, 10, 10, 1).layer());
		// One block closer to the first: not enough room on layer 1.
		InteriorPacker other = new InteriorPacker(14);
		other.place(100, 0, 20, 20, 1);
		assertEquals(2, other.place(137, 0, 10, 10, 1).layer());
	}

	@Test
	void whenEveryLayerIsTakenItMovesSideways() {
		InteriorPacker packer = new InteriorPacker(2);
		List<InteriorPacker.Box> placed = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			InteriorPacker.Spot spot = packer.place(0, 0, 20, 20, 1);
			assertEquals(i + 1, spot.layer());
			placed.add(InteriorPacker.footprint(spot.x(), spot.z(), 20, 20));
		}
		InteriorPacker.Spot moved = packer.place(0, 0, 20, 20, 1);
		assertNotNull(moved);
		assertTrue(moved.moved());
		assertEquals(1, moved.layer(), "sideways spots try the shallowest layer first");
		InteriorPacker.Box box = InteriorPacker.footprint(moved.x(), moved.z(), 20, 20);
		assertTrue(box.separated(placed.get(0), InteriorPacker.GAP));
		// The nearest free spot: three 16-block steps straight north.
		assertEquals(0, moved.x());
		assertEquals(-48, moved.z());
	}

	@Test
	void layerZeroIsTheSurface() {
		InteriorPacker packer = new InteriorPacker(Scale.maxLayer(-352));
		assertEquals(14, packer.maxLayer(), "the deep overworld (HEIGHTS_ALL_GAMES_PLAN D5: bottom -352, layers 29 apart)");
		assertNull(packer.place(0, 0, 4, 4, 0), "layer 0 is the surface");
		assertNull(new InteriorPacker(0).place(0, 0, 4, 4, 1), "a world with no room below the surface has no layers");
	}

	@Test
	void tooDeepSpillsSidewaysUnderTheVoidFill() {
		// One surface map 200 blocks square around the door; PLAN §8: the deepest layer, outside every surface footprint.
		InteriorPacker.Box town = new InteriorPacker.Box(-100, -100, 100, 100);
		InteriorPacker packer = new InteriorPacker(14, List.of(town));
		InteriorPacker.Spot spot = packer.place(0, 0, 20, 16, 15);
		assertNotNull(spot, "a chain that ran out of depth still gets a place");
		assertEquals(14, spot.layer());
		assertTrue(spot.moved() && spot.deep());
		InteriorPacker.Box box = InteriorPacker.footprint(spot.x(), spot.z(), 20, 16);
		assertTrue(box.separated(town, 0), "not under the surface map");
		// Nearest first: the interior reaches east and south of its origin, so seven 16-block steps east clear the town
		// (ring included) before any other direction does.
		assertEquals(112, spot.x());
		assertEquals(0, spot.z());
		// The next one keeps the gap from the first.
		InteriorPacker.Spot next = packer.place(0, 0, 20, 16, 16);
		assertTrue(next.deep() && next.layer() == 14);
		assertTrue(InteriorPacker.footprint(next.x(), next.z(), 20, 16).separated(box, InteriorPacker.GAP));
	}

	@Test
	void aLongFloorChainUnderOneDoorIsAllPlaced() {
		// The shape that left one interior unplaced in the early full build: a chain whose floors were pushed down by a
		// crowded column until the last one's parent sat on the deepest layer.
		InteriorPacker.Box city = new InteriorPacker.Box(-60, -60, 60, 60);
		InteriorPacker packer = new InteriorPacker(Scale.maxLayer(-256), List.of(city));
		// Neighbours under the same door fill the first layers.
		for (int i = 0; i < 4; i++) {
			assertNotNull(packer.place(0, 0, 30, 30, 1));
		}
		int parentLayer = 0;
		int deep = 0;
		for (int floor = 0; floor < 16; floor++) {
			InteriorPacker.Spot spot = packer.place(0, 0, 30, 30, parentLayer + 1);
			assertNotNull(spot, "floor " + floor + " found no room");
			assertTrue(spot.layer() > parentLayer || spot.deep(), "floors stack downwards until the depth runs out");
			deep += spot.deep() ? 1 : 0;
			parentLayer = spot.layer();
		}
		assertTrue(deep > 0, "the chain ran out of depth and spilled sideways");
	}

	@Test
	void layerMath() {
		assertEquals(64, Scale.feetY(0));
		assertEquals(35, Scale.feetY(1));
		assertEquals(6, Scale.feetY(2));
		assertEquals(-342, Scale.feetY(14));
		assertEquals(4, Scale.maxLayer(-64), "a vanilla-depth overworld fits four layers below Y 64");
		assertEquals(2, Scale.maxLayer(0));
	}

	@Test
	void anOpenAirInteriorGetsTheSkyAndKeepsItsColumnFree() {
		// The voxel plan's V7: a forest under the open sky, outside the surface's volume, nothing above it ever.
		InteriorPacker packer = new InteriorPacker(4);
		InteriorPacker.Box surface = new InteriorPacker.Box(-200, -200, 200, 200);
		InteriorPacker.Spot under = packer.place(0, 0, 40, 40, 1);
		assertNotNull(under);
		InteriorPacker.Spot forest = packer.placeOpenSky(0, 0, 40, 40, 2, surface);
		assertNotNull(forest);
		assertEquals(2, forest.layer());
		assertTrue(forest.moved());
		InteriorPacker.Box box = InteriorPacker.footprint(forest.x(), forest.z(), 40, 40);
		assertTrue(box.separated(surface, InteriorPacker.SKY_CLEARANCE), "out of sight of the surface's volume");
		// An interior that wants the spot above it, on layer 1, can't have it: the column stays free up to the sky. It
		// spills down under the forest instead, as any interior spills under a taken spot.
		InteriorPacker.Spot wants = packer.place(forest.x(), forest.z(), 40, 40, 1);
		assertNotNull(wants);
		assertEquals(forest.x(), wants.x());
		assertEquals(3, wants.layer(), "below the forest, never above it");
		assertNull(new InteriorPacker(1).placeOpenSky(0, 0, 40, 40, 2, surface), "no layer for it: it keeps its ceiling");
	}

	@Test
	void openAirInteriorsKeepOutOfSightOfEachOtherAndOfTheKeptAreas() {
		// 2026-10-23: Sootopolis saw the Battle Frontier's ground hanging over it, and Route 28 Johto's underside. However
		// deep their layers, two open-air interiors (and one and the cave area) stay past the farthest view distance apart.
		InteriorPacker packer = new InteriorPacker(8);
		InteriorPacker.Box surface = new InteriorPacker.Box(-200, -200, 200, 200);
		InteriorPacker.Box caves = new InteriorPacker.Box(-200, -3000, 200, -2800);
		packer.keepSkyClear(caves);
		InteriorPacker.Spot high = packer.placeOpenSky(0, 0, 40, 40, 1, surface);
		InteriorPacker.Spot deep = packer.placeOpenSky(0, 0, 40, 40, 6, surface);
		InteriorPacker.Spot nearCaves = packer.placeOpenSky(0, -2900, 40, 40, 2, surface);
		assertNotNull(high);
		assertNotNull(deep);
		assertNotNull(nearCaves);
		List<InteriorPacker.Box> boxes = List.of(InteriorPacker.footprint(high.x(), high.z(), 40, 40),
				InteriorPacker.footprint(deep.x(), deep.z(), 40, 40), InteriorPacker.footprint(nearCaves.x(), nearCaves.z(), 40, 40));
		for (int i = 0; i < boxes.size(); i++) {
			assertTrue(boxes.get(i).separated(surface, InteriorPacker.SKY_CLEARANCE));
			assertTrue(boxes.get(i).separated(caves, InteriorPacker.SKY_CLEARANCE), "out of sight of the cave area");
			for (int j = i + 1; j < boxes.size(); j++) {
				assertTrue(boxes.get(i).separated(boxes.get(j), InteriorPacker.SKY_CLEARANCE), i + " and " + j);
			}
		}
		// An ordinary interior is no open-air one: it may still stand a gap from one.
		InteriorPacker.Spot room = packer.place(high.x() + 40 + 2 * InteriorPacker.GAP, high.z(), 40, 40, 1);
		assertNotNull(room);
		assertFalse(room.moved());
	}

	@Test
	void anOpenAirMapTheGameConnectsGoesBesideItsGroup() {
		// the Safari Zone's way (FIELD_MECHANICS_AUDIT_PLAN §14, SC3): the first area under the open sky, the next right
		// beside it on its layer, though a sky keeps SKY_CLEARANCE from the others
		InteriorPacker packer = new InteriorPacker(14);
		InteriorPacker.Box surface = new InteriorPacker.Box(-100, -100, 100, 100);
		InteriorPacker.Spot first = packer.placeOpenSky(2000, 0, 80, 80, 2, surface);
		assertNotNull(first);
		List<InteriorPacker.Box> group = new java.util.ArrayList<>(List.of(InteriorPacker.footprint(first.x(), first.z(), 80, 80)));
		InteriorPacker.Spot east = packer.placeSkyBeside(first.x() + 80, first.z(), 80, 80, first.layer(), surface, group);
		assertNotNull(east, "beside its group: no gap, no clearance between them");
		assertEquals(first.x() + 80, east.x());
		assertEquals(first.layer(), east.layer());
		// another group's sky nearby keeps the spot west of the first from them
		packer.keepSkyClear(new InteriorPacker.Box(first.x() - 140, first.z(), first.x() - 120, first.z() + 20));
		assertNull(packer.placeSkyBeside(first.x() - 80, first.z(), 80, 80, first.layer(), surface, group), "another sky within BESIDE_CLEARANCE");
	}
}
