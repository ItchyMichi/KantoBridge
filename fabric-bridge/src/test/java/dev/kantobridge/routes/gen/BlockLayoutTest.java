package dev.kantobridge.routes.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import org.junit.jupiter.api.Test;

/** The block maze: a centre-out spiral, exits that always agree, every open block connected. */
class BlockLayoutTest {
	@Test
	void theSpiralVisitsEveryBlockOnceFromTheMiddle() {
		for (int w = 1; w <= 6; w++) {
			for (int h = 1; h <= 6; h++) {
				int[] order = BlockLayout.spiral(w, h);
				assertEquals(w * h, order.length);
				int[] sorted = order.clone();
				Arrays.sort(sorted);
				for (int i = 0; i < sorted.length; i++) {
					assertEquals(i, sorted[i], "spiral " + w + "x" + h);
				}
				assertEquals(((h - 1) / 2) * w + (w - 1) / 2, order[0]);
			}
		}
	}

	@Test
	void exitsAgreeAndEveryOpenBlockIsConnected() {
		int[] opened = new int[17];
		for (int seed = 0; seed < 10_000; seed++) {
			RouteRng rng = new RouteRng(seed);
			int[] grid = BlockLayout.generate(rng, 4, 4, 16);
			int open = 0;
			int start = -1;
			for (int b = 0; b < 16; b++) {
				int x = b % 4;
				int y = b / 4;
				for (Dir d : Dir.values()) {
					if ((grid[b] & d.bit) == 0) {
						continue;
					}
					int nx = x + d.dx;
					int ny = y + d.dy;
					assertTrue(nx >= 0 && nx < 4 && ny >= 0 && ny < 4, "an exit off the grid, seed " + seed);
					assertTrue((grid[ny * 4 + nx] & d.opposite().bit) != 0, "a one-way exit, seed " + seed);
				}
				if (grid[b] != BlockShapes.CLOSED) {
					open++;
					start = b;
				}
			}
			opened[open]++;
			assertTrue(open >= 2, "seed " + seed);
			int[] dist = BlockLayout.distances(grid, 4, 4, start);
			for (int b = 0; b < 16; b++) {
				if (grid[b] != BlockShapes.CLOSED) {
					assertTrue(dist[b] >= 0, "an open block cut off, seed " + seed);
				}
			}
		}
		System.out.println("[routes-gen] open blocks per 4x4 area over 10000 seeds: " + Arrays.toString(opened));
	}

	@Test
	void picksRespectRequiredAndForbiddenExits() {
		RouteRng rng = new RouteRng(3);
		for (int i = 0; i < 2000; i++) {
			int required = rng.nextInt(16);
			int forbidden = rng.nextInt(16) & ~required;
			int mask = BlockShapes.pick(rng, required, forbidden);
			assertEquals(required, mask & required);
			assertEquals(0, mask & forbidden);
		}
		assertEquals(BlockShapes.CLOSED, BlockShapes.pick(rng, 0, 15));
		Deque<Integer> firsts = new ArrayDeque<>();
		for (int i = 0; i < 100; i++) {
			firsts.add(BlockShapes.pick(rng, 0, 0));
		}
		assertTrue(firsts.stream().noneMatch(m -> m == BlockShapes.CLOSED), "an unconstrained pick is never closed");
	}
}
