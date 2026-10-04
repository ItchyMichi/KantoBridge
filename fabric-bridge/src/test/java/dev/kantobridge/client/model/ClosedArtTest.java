package dev.kantobridge.client.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.ShapeMap;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.junit.jupiter.api.Test;

/**
 * The closed story art the chunk mesh draws (docs/specs/VOXEL_BLOCKS_PLAN.md §5.9, V5): a position finds its overlay
 * column by its block column, the column's shape ids bottom-up from feet level, the cell's height (0 for flat art) and
 * box; a snapshot draws a cell's art only while the local player sees it closed.
 */
class ClosedArtTest {
	static final String HASH = "0".repeat(64);
	static final String TREE = "TOWN:10:14";
	static final String RUG = "TOWN:3:3";
	static final WorldIndex WORLD = new WorldIndex("red", HASH, List.of(
			new MapPlacement("TOWN", 0, 64, 0, 20, 18, 0, MapPlacement.Kind.SURFACE, "PAL", "kantobridge:kanto_outdoor", false, null)),
			HASH, StoryCellTable.of(List.of(
					new StoryCellTable.Entry(CellPos.parse(TREE), StoryCellType.TILE, null, new StoryCellTable.Swap(5, 7, "cut", 14, 15), false, 14,
							new StoryCellTable.Closed(14, StoryCellTable.Pass.SOLID, 2, List.of(41, 42, 43, 44)), false),
					new StoryCellTable.Entry(CellPos.parse(RUG), StoryCellType.TILE, null, new StoryCellTable.Swap(1, 1, "script", 0, 4), false, 0,
							new StoryCellTable.Closed(0, false, 0, List.of(1, 1, 1, 1))))),
			List.of());
	/** The tree's cell (10, 14) is blocks 20..21 × 28..29; the rug's (3, 3) blocks 6..7 × 6..7; feet at 64. */
	static final ShapeMap MAP = new ShapeMap(HASH, HASH, List.of(), List.of(
			new ShapeMap.Overlay(TREE, 20, 64, 28, new int[][] {{5, 6}, {7, 8}, {9, 10}, {11, 12}}),
			new ShapeMap.Overlay(RUG, 6, 64, 6, new int[][] {{3}, {3}, {3}, {3}})));

	@Test
	void aPositionFindsItsColumnByItsBlockColumn() {
		ClosedArt art = ClosedArt.of(MAP, WORLD);
		int tree = art.overlay(TREE);
		assertArrayEquals(new int[] {tree, 0, 64}, art.at(20, 28), "the north-west column, feet level 64");
		assertArrayEquals(new int[] {tree, 3, 64}, art.at(21, 29), "the south-east column");
		assertNull(art.at(22, 28));
		assertArrayEquals(new int[] {7, 8}, art.ids(tree, 1), "bottom-up from feet level");
		assertEquals(42, art.slot(tree, 1), "the closed art's slot of the north-east column (the fallback cube)");
		assertEquals(2, art.height(tree));
		assertArrayEquals(new int[] {20, 64, 28, 21, 65, 29}, art.box(tree), "the cell's 2×2 columns, two blocks high");
		int rug = art.overlay(RUG);
		assertTrue(art.flat(rug));
		assertEquals(0, art.height(rug), "flat art has no click box");
		assertArrayEquals(new int[] {6, 64, 6, 7, 64, 7}, art.box(rug), "but is re-meshed at feet level");
		assertEquals(-1, art.overlay("TOWN:1:1"));
	}

	@Test
	void aWorldWithoutOverlaysHasNoClosedArt() {
		assertNull(ClosedArt.of(new ShapeMap(HASH, HASH, List.of(), List.of()), WORLD), "built before V5: the block-entity renderer draws it");
		assertNull(ClosedArt.of(null, WORLD));
	}

	@Test
	void theClosedSetIsABitSetOverOverlays() {
		ClosedArt art = ClosedArt.of(MAP, WORLD);
		BitSet bits = art.bits(Set.of(TREE, "NOWHERE:1:1"));
		assertEquals(1, bits.cardinality());
		assertTrue(bits.get(art.overlay(TREE)));
	}

	@Test
	void aSnapshotDrawsACellsArtOnlyWhileItIsClosed() {
		ClosedArt art = ClosedArt.of(MAP, WORLD);
		RenderSnapshot open = new RenderSnapshot(1, null, null, new TextureAtlasSprite[2][], new Mesh[2][], new Mesh[2], null, true, null, 0, 0, null,
				null, new TextureAtlasSprite[0], new int[0], null, null, art, new BitSet());
		assertEquals(0, open.closedIdAt(20, 65, 28), "open for this player: nothing");
		assertTrue(open.drawsClosed(TREE), "the mesh draws this cell, not the block-entity renderer");
		assertFalse(open.drawsClosed("TOWN:1:1"));
		RenderSnapshot closed = open.withClosed(art.bits(Set.of(TREE)));
		assertEquals(5, closed.closedIdAt(20, 64, 28), "feet level of the north-west column");
		assertEquals(6, closed.closedIdAt(20, 65, 28));
		assertEquals(0, closed.closedIdAt(20, 66, 28), "above the column");
		assertEquals(0, closed.closedIdAt(20, 63, 28), "the floor below");
		assertEquals(0, closed.closedIdAt(6, 64, 6), "the rug stays open");
		assertEquals(0, open.closedIdAt(20, 65, 28), "the snapshot it was copied from is unchanged");
	}
}
