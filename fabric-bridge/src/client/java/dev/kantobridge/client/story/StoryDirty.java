package dev.kantobridge.client.story;

import dev.kantobridge.client.model.ClosedArt;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What to re-mesh when the local player's closed tile cells change (docs/specs/VOXEL_BLOCKS_PLAN.md §5.9, V5): the
 * closed columns of every cell that opened or closed, and nothing else. Plain Java.
 */
public final class StoryDirty {
	private StoryDirty() {
	}

	/**
	 * The block boxes ({@code minX, minY, minZ, maxX, maxY, maxZ}, inclusive) of the cells drawn closed in exactly one of
	 * {@code before} and {@code after}, in key order; cells without an overlay are left to the block-entity renderer.
	 */
	public static List<int[]> boxes(Set<String> before, Set<String> after, ClosedArt art) {
		Set<String> changed = new TreeSet<>(before);
		changed.addAll(after);
		List<int[]> out = new ArrayList<>();
		for (String key : changed) {
			if (before.contains(key) == after.contains(key)) {
				continue;
			}
			int overlay = art.overlay(key);
			if (overlay >= 0) {
				out.add(art.box(overlay));
			}
		}
		return out;
	}
}
