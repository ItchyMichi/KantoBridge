package dev.kantobridge.client.act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The crosshair on closed art: a standing tree (2 high over its cell's 2×2 blocks) is hit where the player sees it, the
 * nearest column wins, and a hit Minecraft found nearer (or a column out of reach, flat art, or open cells) is left alone.
 */
class ClosedArtPickTest {
	private static final String HASH = "0".repeat(64);
	private static final String TREE = "TOWN:10:14";
	private static final String NEAR_TREE = "TOWN:10:13";
	private static final String RUG = "TOWN:3:3";
	private static final WorldIndex WORLD = new WorldIndex("red", HASH, List.of(
			new MapPlacement("TOWN", 0, 64, 0, 20, 18, 0, MapPlacement.Kind.SURFACE, "PAL", "kantobridge:kanto_outdoor", false, null)),
			HASH, StoryCellTable.of(List.of(
					new StoryCellTable.Entry(CellPos.parse(TREE), StoryCellType.TILE, null, new StoryCellTable.Swap(5, 7, "cut", 14, 15), false, 14,
							new StoryCellTable.Closed(14, StoryCellTable.Pass.SOLID, 2, List.of(1, 1, 1, 1)), false),
					new StoryCellTable.Entry(CellPos.parse(NEAR_TREE), StoryCellType.TILE, null, new StoryCellTable.Swap(5, 6, "cut", 14, 15), false, 14,
							new StoryCellTable.Closed(14, StoryCellTable.Pass.SOLID, 2, List.of(1, 1, 1, 1)), false),
					new StoryCellTable.Entry(CellPos.parse(RUG), StoryCellType.TILE, null, new StoryCellTable.Swap(1, 1, "script", 0, 4), false, 0,
							new StoryCellTable.Closed(0, false, 0, List.of(1, 1, 1, 1))))),
			List.of());

	/** The player two cells north of the tree (cell 10,12), eyes 1.62 above the surface. */
	private static final double EYE_X = 21.0;
	private static final double EYE_Y = 64.0 + 1.62;
	private static final double EYE_Z = 25.0;
	private static final double REACH = 4.5;

	@Test
	void theTrunkIsHitWhereItIsDrawn() {
		List<ClosedArtPick.Box> boxes = ClosedArtPick.boxes(WORLD, Set.of(TREE), EYE_X, EYE_Y, EYE_Z, REACH);
		assertEquals(1, boxes.size());
		ClosedArtPick.Box box = boxes.get(0);
		assertEquals(List.of(20.0, 64.0, 28.0, 22.0, 66.0, 30.0), List.of(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
		// Looking straight south at eye height: the tree's upper half, which no story block covers.
		ClosedArtPick.Hit hit = ClosedArtPick.nearest(boxes, EYE_X, EYE_Y, EYE_Z, 0.0, 0.0, 1.0, REACH);
		assertNotNull(hit);
		assertEquals(TREE, hit.key());
		assertEquals(3.0, hit.distance(), 1.0E-9);
		assertEquals(ClosedArtPick.Face.NORTH, hit.face());
		assertEquals(List.of(21, 64, 28), List.of(hit.blockX(), hit.blockY(), hit.blockZ()), "its story block at feet level under the hit");
		assertTrue(hit.y() > 65.0, "above the story block");
	}

	@Test
	void somethingNearerOrBehindTheEyeWins() {
		List<ClosedArtPick.Box> boxes = ClosedArtPick.boxes(WORLD, Set.of(TREE), EYE_X, EYE_Y, EYE_Z, REACH);
		assertNull(ClosedArtPick.nearest(boxes, EYE_X, EYE_Y, EYE_Z, 0.0, 0.0, 1.0, 2.5), "Minecraft's own hit was nearer");
		assertNull(ClosedArtPick.nearest(boxes, EYE_X, EYE_Y, EYE_Z, 0.0, 0.0, -1.0, REACH), "looking away");
		assertNull(ClosedArtPick.nearest(boxes, EYE_X, EYE_Y, EYE_Z, 0.0, 1.0, 0.0, REACH), "looking up");
		// From inside a column nothing is hit.
		assertNull(ClosedArtPick.nearest(boxes, 21.0, 65.0, 29.0, 0.0, 0.0, 1.0, REACH));
	}

	@Test
	void theNearestOfTwoColumnsWins() {
		List<ClosedArtPick.Box> boxes = ClosedArtPick.boxes(WORLD, Set.of(TREE, NEAR_TREE), EYE_X, EYE_Y, EYE_Z, REACH);
		assertEquals(2, boxes.size());
		ClosedArtPick.Hit hit = ClosedArtPick.nearest(boxes, EYE_X, EYE_Y, EYE_Z, 0.0, 0.0, 1.0, REACH);
		assertEquals(NEAR_TREE, hit.key());
		assertEquals(1.0, hit.distance(), 1.0E-9);
	}

	@Test
	void onlyDrawnColumnsWithinReachCount() {
		assertTrue(ClosedArtPick.boxes(WORLD, Set.of(), EYE_X, EYE_Y, EYE_Z, REACH).isEmpty(), "cut: nothing drawn");
		assertTrue(ClosedArtPick.boxes(WORLD, Set.of(RUG), 7.0, 65.62, 7.0, REACH).isEmpty(), "flat art: its floor block is the cell's own");
		assertTrue(ClosedArtPick.boxes(WORLD, Set.of(TREE), EYE_X, EYE_Y, EYE_Z - 10.0, REACH).isEmpty(), "out of reach");
		assertTrue(ClosedArtPick.boxes(WORLD, Set.of("NOWHERE:1:1"), EYE_X, EYE_Y, EYE_Z, REACH).isEmpty(), "not a story cell");
	}
}
