package dev.kantobridge.dungeon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.block.PropBlock;
import dev.kantobridge.builder.plan.BlockCode;
import dev.kantobridge.builder.plan.Box3;
import dev.kantobridge.builder.zone.ZoneBlock;
import dev.kantobridge.routes.gen.Cell;
import dev.kantobridge.routes.gen.PmdFloor;
import dev.kantobridge.routes.gen.PmdFloorGenerator;
import dev.kantobridge.routes.gen.PmdParams;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** One dungeon floor's blocks (WR7): cased in stone, walkable floor under a ceiling, water behind barriers, lit, stairs. */
class DungeonFloorPlanTest {
	private static final PmdFloor FLOOR = PmdFloorGenerator.generate(PmdParams.standard(), 42);
	private static final int FEET = 86;

	private static Map<Integer, Integer> column(DungeonFloorPlan plan, int x, int z) {
		Map<Integer, Integer> out = new HashMap<>();
		plan.renderColumn((bx, by, bz, code) -> out.put(by, code), x, z);
		return out;
	}

	private static ZoneBlock vanilla(Integer code) {
		assertNotNull(code);
		assertEquals(BlockCode.KIND_VANILLA, BlockCode.kind(code));
		return ZoneBlock.values()[BlockCode.value(code)];
	}

	@Test
	void floorsAreCasedWalkableLitAndHaveTheirStairs() {
		DungeonFloorPlan plan = new DungeonFloorPlan(FLOOR, 1024, -3200, FEET, false);
		int walkable = 0;
		int lights = 0;
		Set<Integer> stairsParts = new HashSet<>();
		for (int cy = 0; cy < FLOOR.height(); cy++) {
			for (int cx = 0; cx < FLOOR.width(); cx++) {
				Cell cell = new Cell(cx, cy);
				int[] b = plan.blockOf(cell);
				for (int dz = 0; dz < 2; dz++) {
					for (int dx = 0; dx < 2; dx++) {
						Map<Integer, Integer> col = column(plan, b[0] + dx, b[1] + dz);
						assertEquals(ZoneBlock.STONE, vanilla(col.get(FEET + DungeonFloorPlan.ROOM_HEIGHT)), "the ceiling");
						assertEquals(ZoneBlock.STONE, vanilla(col.get(FEET - 1 - DungeonFloorPlan.GROUND_DEPTH)), "stone below");
						switch (FLOOR.at(cell)) {
							case WALL -> {
								for (int y = FEET - 1; y < FEET + DungeonFloorPlan.ROOM_HEIGHT; y++) {
									ZoneBlock s = vanilla(col.get(y));
									assertTrue(s == ZoneBlock.STONE || s == ZoneBlock.MOSSY_COBBLESTONE, "a wall up to the ceiling");
								}
							}
							case WATER -> {
								assertEquals(ZoneBlock.WATER, vanilla(col.get(FEET - 1)));
								assertEquals(ZoneBlock.BARRIER, vanilla(col.get(FEET)));
							}
							case FLOOR -> {
								walkable++;
								assertEquals(ZoneBlock.COARSE_DIRT, vanilla(col.get(FEET - 1)));
								Integer feet = col.get(FEET);
								if (cell.equals(FLOOR.stairs())) {
									assertEquals(BlockCode.KIND_PROP, BlockCode.kind(feet));
									assertEquals(PropBlock.Kind.STAIRS.ordinal(), BlockCode.value(feet) / 4);
									stairsParts.add(BlockCode.value(feet) % 4);
								} else {
									assertNull(feet, "room to walk");
								}
								assertNull(col.get(FEET + 1));
								Integer light = col.get(FEET + DungeonFloorPlan.ROOM_HEIGHT - 1);
								lights += light != null && BlockCode.kind(light) == BlockCode.KIND_LIGHT ? 1 : 0;
							}
						}
					}
				}
			}
		}
		assertTrue(walkable > 400, "walkable " + walkable);
		assertTrue(lights > 5, "lights " + lights);
		assertEquals(Set.of(0, 1, 2, 3), stairsParts, "the stairs prop drawn a quarter a block");
		// the slot's margin is solid; outside the slot, nothing
		Box3 v = plan.volume();
		assertEquals(ZoneBlock.STONE, vanilla(column(plan, v.minX(), v.minZ()).get(FEET)));
		assertTrue(column(plan, v.maxX(), v.minZ()).isEmpty());
		assertNotNull(plan.cellAt(plan.blockOf(FLOOR.stairs())[0] + 1, plan.blockOf(FLOOR.stairs())[1] + 1));
		assertNull(plan.cellAt(v.minX(), v.minZ()));
	}

	@Test
	void theLastFloorsStairsAreTheExit() {
		DungeonFloorPlan last = new DungeonFloorPlan(FLOOR, 0, 0, FEET, true);
		int[] b = last.blockOf(FLOOR.stairs());
		Integer code = column(last, b[0], b[1]).get(FEET);
		assertEquals(PropBlock.Kind.EXIT.ordinal(), BlockCode.value(code) / 4);
		assertEquals(0, BlockCode.value(code) % 4);
	}
}
