package dev.kantobridge.client.story;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.kantobridge.client.model.ClosedArt;
import dev.kantobridge.world.ShapeMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What a story change re-meshes (docs/specs/VOXEL_BLOCKS_PLAN.md §5.9, V5): the closed columns of the cells that opened
 * or closed, and nothing else.
 */
class StoryDirtyTest {
	private static final String HASH = "0".repeat(64);
	private static final ClosedArt ART = ClosedArt.of(new ShapeMap(HASH, HASH, List.of(), List.of(
			new ShapeMap.Overlay("TOWN:10:14", 20, 64, 28, new int[][] {{5, 6}, {7, 8}, {9, 10}, {11, 12}}),
			new ShapeMap.Overlay("TOWN:11:14", 22, 64, 28, new int[][] {{5}, {7}, {9, 10, 13}, {11}}),
			new ShapeMap.Overlay("TOWN:3:3", 6, 64, 6, new int[][] {{3}, {3}, {3}, {3}}))), null);

	@Test
	void theChangedCellsColumnsAndNothingElse() {
		List<int[]> boxes = StoryDirty.boxes(Set.of("TOWN:10:14", "TOWN:3:3"), Set.of("TOWN:3:3", "TOWN:11:14"), ART);
		assertEquals(2, boxes.size(), "the tree that opened and the one that closed; the rug didn't change");
		assertArrayEquals(new int[] {20, 64, 28, 21, 65, 29}, boxes.get(0));
		assertArrayEquals(new int[] {22, 64, 28, 23, 66, 29}, boxes.get(1), "as high as its tallest column");
	}

	@Test
	void cellsWithoutAnOverlayAreLeftToTheirRenderer() {
		assertEquals(List.of(), StoryDirty.boxes(Set.of(), Set.of("ELSEWHERE:1:1"), ART));
		assertEquals(List.of(), StoryDirty.boxes(Set.of("TOWN:10:14"), Set.of("TOWN:10:14"), ART), "nothing changed");
	}
}
