package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.shape.dressing.Ceilings;
import dev.kantobridge.scene.Env;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.world.MapPlacement;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Dressing (docs/specs/VOXEL_BLOCKS_PLAN.md §5.12, V7): an interior's wall columns (runs along its north, west or east
 * edge) rise to the ceiling repeating their top band, furniture keeps its height; the room's dominant wall tile is what
 * its ring and ceiling wear, the ceiling darkened by a tint; an open-air interior gets none of it, and an interior under
 * the open sky no ceiling.
 */
class DressingTest {
	/** A room: a back wall two rows deep along the north edge, a table standing free. */
	static final String[] ROOM = {
			"AAAAAAAA",
			"BBBBBBBB",
			"........",
			"........",
			"..CCCC..",
			"..CCCC.."};

	static ShapePlanner.MapShapes plan(SceneMap map, ShapeParams params) {
		return ShapePlanner.planMap(map, ShapeFixtures.TIER_B, 0, params);
	}

	@Test
	void wallsRiseToTheCeilingFurnitureKeepsItsHeight() {
		SceneMap room = ShapeFixtures.interior("ROOM", ROOM);
		ShapePlanner.MapShapes before = plan(room, ShapeParams.v5());
		ShapePlanner.MapShapes dressed = plan(room, ShapeParams.v7());
		int cols = room.artCols();
		for (int tx = 0; tx < cols; tx++) {
			for (int ty = 0; ty < 2; ty++) {
				int i = ty * cols + tx;
				assertEquals(2, before.blocks()[i]);
				assertEquals(WorldPlan.CEILING_ABOVE_FEET, dressed.blocks()[i], "a wall column up to the ceiling");
				ShapeKey[] column = dressed.open()[i];
				if (column != null) {
					assertEquals(column[1], column[2], "a two-row wall repeats its top band");
					assertEquals(before.open()[i][0].number(3), column[0].number(3), "band 0 still shows the front row");
				}
			}
		}
		for (int tx = 2; tx < 6; tx++) {
			assertEquals(2, dressed.blocks()[4 * cols + tx], "the table keeps its height");
		}
	}

	@Test
	void theRingAndCeilingWearTheRoomsWallTileTheCeilingDarkened() {
		SceneMap room = ShapeFixtures.interior("ROOM", ROOM);
		ShapePlanner.MapShapes dressed = plan(room, ShapeParams.v7());
		int a = ShapeFixtures.tile('A');
		assertEquals(a, dressed.wallTile(), "A and B are as frequent: the lowest id");
		assertEquals(ShapeKey.cube(0, a, a, a, a, a, a).withTint(Ceilings.TINT), dressed.ceiling());
		assertEquals(-1, plan(room, ShapeParams.v5()).wallTile(), "undressed before V7");

		// The shape map holds the ceiling over the footprint, at the ceiling's height; under the open sky, none.
		MapPlacement placed = ShapeFixtures.interiorAt(room, 1000, 1000, 1, "TOWN");
		ShapePlan plan = ShapePlanner.plan(ShapeFixtures.scene(ShapeFixtures.TIER_B, room), List.of(placed), ShapeParams.v7());
		int ceiling = plan.index().shapeId(dressed.ceiling(), room.palette());
		assertTrue(ceiling > 0);
		for (int ty = 0; ty < room.artRows(); ty++) {
			for (int tx = 0; tx < room.artCols(); tx++) {
				assertEquals(ceiling, plan.map().shapeAt(1000 + tx, placed.y() + WorldPlan.CEILING_ABOVE_FEET, 1000 + ty));
			}
		}
		MapPlacement sky = new MapPlacement(placed.id(), placed.x(), placed.y(), placed.z(), placed.w(), placed.h(), placed.layer(),
				placed.kind(), placed.palette(), WorldPlan.BIOME_OUTDOOR, false, placed.parent());
		assertTrue(WorldPlan.openSky(sky));
		ShapePlan open = ShapePlanner.plan(ShapeFixtures.scene(ShapeFixtures.TIER_B, room), List.of(sky), ShapeParams.v7());
		assertEquals(0, open.map().shapeAt(1000, placed.y() + WorldPlan.CEILING_ABOVE_FEET, 1000), "no ceiling under the sky");
	}

	@Test
	void anOpenAirInteriorIsNotDressed() {
		SceneMap room = ShapeFixtures.interior("FOREST", ROOM);
		SceneMap forest = new SceneMap(room.id(), room.index(), room.tileset(), room.atlas(), room.palette(), room.w(), room.h(), room.perCell(),
				room.surface(), new Env(false, true, false, Env.DARK_NEVER), room.tiles(), room.closedTiles(), room.kinds(), room.tags(),
				room.dirs(), room.border(), room.ownBorder(), room.objects(), room.swapSites());
		ShapePlanner.MapShapes planned = plan(forest, ShapeParams.v7());
		assertEquals(-1, planned.wallTile());
		assertNull(planned.ceiling());
		assertEquals(2, planned.blocks()[0], "its walls keep their height");
		ShapePlanner.MapShapes outdoors = plan(ShapeFixtures.surface("TOWN", ROOM), ShapeParams.v7());
		assertEquals(-1, outdoors.wallTile(), "outdoors nothing is dressed either");
		assertNull(outdoors.ceiling());
	}
}
