package dev.kantobridge.character;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * A rigged figure's parts (docs/specs/VOXEL_BLOCKS_PLAN.md §5.11; report B5.4): the standing front frame split into head,
 * torso, arms and legs by its rows and columns, each part a box whose depth comes from the side frame. Our own
 * heuristics, on masks only:
 *
 * <pre>
 * legs    from the bottom row up, while a row has two runs with a gap between them: legTop (none: no legs, the
 *         lowest 3 rows are one block that doesn't swing)
 * head    the widest row of the top half; the neck is the first row below it narrower than the rows around it (the body
 *         widening again below), no lower than 5/8 of the frame; the head is the rows above the neck
 * torso   the rows from the neck to the legs; its core is the run of the hips' row (legTop - 1), kept up to the neck
 * arms    in the torso's rows, what lies left and right of the core
 * depth   per part, the side frame's extent over its rows
 * humanoid  walk frames, a neck, and a head 1/4 to 2/3 of the figure's height; the boxes must cover at least 85%
 *           of the front frame
 * </pre>
 */
public final class RigSplitter {
	/** A rig's boxes must cover at least this share of the front frame. */
	public static final double MIN_COVER = 0.85;

	/**
	 * One box: its name, its frame rectangle (x0, y0 inclusive top-left; w, h), its depth band in the side frame (z0,
	 * d), whether it swings, and its pivot row (the joint).
	 */
	public record Part(String name, int x0, int y0, int w, int h, int z0, int d, boolean swings, int pivotY) {
	}

	/** The split, or why there is none. */
	public record Rig(List<Part> parts, double cover, String failure) {
		public boolean ok() {
			return this.failure == null;
		}
	}

	private RigSplitter() {
	}

	public static Rig split(SpriteFrames frames) {
		int n = frames.size();
		if (!frames.walks()) {
			return new Rig(List.of(), 0, "frames");
		}
		BitSet front = frames.mask(SpriteFrames.STAND, SpriteFrames.DOWN);
		BitSet side = frames.mask(SpriteFrames.STAND, SpriteFrames.LEFT);
		int top = -1;
		int bottom = -1;
		for (int y = 0; y < n; y++) {
			if (width(front, n, y) > 0) {
				top = top < 0 ? y : top;
				bottom = y;
			}
		}
		if (top < 0) {
			return new Rig(List.of(), 0, "empty");
		}
		int legTop = bottom + 1;
		for (int y = bottom; y > top && runs(front, n, y) == 2; y--) {
			legTop = y;
		}
		boolean legs = legTop <= bottom;
		int headWide = top;
		for (int y = top; y <= top + (bottom - top) / 2; y++) {
			if (width(front, n, y) > width(front, n, headWide)) {
				headWide = y;
			}
		}
		int neck = -1;
		int lowest = top + n * 5 / 8;
		for (int y = headWide + 1; y < Math.min(bottom, lowest + 1); y++) {
			// A neck narrows the figure and the body widens again below it (a ball only narrows).
			int w = width(front, n, y);
			if (w <= width(front, n, y - 1) && w < width(front, n, y + 1) && w < width(front, n, headWide)) {
				neck = y;
				break;
			}
		}
		int figure = bottom - top + 1;
		int head = neck - top;
		if (neck < 0 || head * 4 < figure || head * 3 > figure * 2) {
			return new Rig(List.of(), 0, "not_humanoid");
		}
		List<Part> parts = new ArrayList<>();
		parts.add(box("head", front, side, n, top, neck - 1, 0, n - 1, false, neck));
		int hips = legs ? legTop - 1 : bottom - 3;
		int[] core = run(front, n, Math.max(neck, hips));
		if (core == null) {
			return new Rig(List.of(), 0, "not_humanoid");
		}
		parts.add(box("torso", front, side, n, neck, Math.max(neck, hips), core[0], core[1], false, neck));
		int[] left = sideColumns(front, n, neck, hips, 0, core[0] - 1);
		int[] right = sideColumns(front, n, neck, hips, core[1] + 1, n - 1);
		if (left != null) {
			parts.add(box("right_arm", front, side, n, neck, hips, left[0], left[1], true, neck));
		}
		if (right != null) {
			parts.add(box("left_arm", front, side, n, neck, hips, right[0], right[1], true, neck));
		}
		if (legs) {
			int[][] legRuns = legRuns(front, n, legTop, bottom);
			parts.add(box("right_leg", front, side, n, legTop, bottom, legRuns[0][0], legRuns[0][1], true, legTop));
			parts.add(box("left_leg", front, side, n, legTop, bottom, legRuns[1][0], legRuns[1][1], true, legTop));
		} else {
			parts.add(box("skirt", front, side, n, Math.max(neck, bottom - 2), bottom, 0, n - 1, false, bottom - 2));
		}
		parts.removeIf(p -> p.w() <= 0 || p.h() <= 0);
		double cover = cover(parts, front, n);
		return new Rig(List.copyOf(parts), cover, cover < MIN_COVER ? "cover" : null);
	}

	/** A part over rows y0..y1 and columns x0..x1, trimmed to what the front draws there; its depth from the side view. */
	static Part box(String name, BitSet front, BitSet side, int n, int y0, int y1, int x0, int x1, boolean swings, int pivot) {
		int minX = Integer.MAX_VALUE;
		int maxX = -1;
		int minZ = Integer.MAX_VALUE;
		int maxZ = -1;
		for (int y = y0; y <= y1; y++) {
			for (int x = Math.max(0, x0); x <= Math.min(n - 1, x1); x++) {
				if (front.get(y * n + x)) {
					minX = Math.min(minX, x);
					maxX = Math.max(maxX, x);
				}
			}
			for (int z = 0; z < n; z++) {
				if (side.get(y * n + z)) {
					minZ = Math.min(minZ, z);
					maxZ = Math.max(maxZ, z);
				}
			}
		}
		if (maxX < 0) {
			return new Part(name, 0, y0, 0, 0, 0, 0, swings, pivot);
		}
		if (maxZ < 0) {
			minZ = n / 2 - 1;
			maxZ = n / 2;
		}
		return new Part(name, minX, y0, maxX - minX + 1, y1 - y0 + 1, minZ, maxZ - minZ + 1, swings, pivot);
	}

	static int width(BitSet mask, int n, int y) {
		int[] r = RigSplitter.extentOf(mask, n, y);
		return r == null ? 0 : r[1] - r[0] + 1;
	}

	static int[] extentOf(BitSet mask, int n, int y) {
		int first = -1;
		int last = -1;
		for (int x = 0; x < n; x++) {
			if (mask.get(y * n + x)) {
				first = first < 0 ? x : first;
				last = x;
			}
		}
		return first < 0 ? null : new int[] {first, last};
	}

	/** The number of separate runs of a row. */
	static int runs(BitSet mask, int n, int y) {
		int runs = 0;
		boolean in = false;
		for (int x = 0; x < n; x++) {
			boolean on = mask.get(y * n + x);
			runs += on && !in ? 1 : 0;
			in = on;
		}
		return runs;
	}

	/** The widest run of a row, or null. */
	static int[] run(BitSet mask, int n, int y) {
		int[] best = null;
		int start = -1;
		for (int x = 0; x <= n; x++) {
			boolean on = x < n && mask.get(y * n + x);
			if (on && start < 0) {
				start = x;
			} else if (!on && start >= 0) {
				if (best == null || x - start > best[1] - best[0] + 1) {
					best = new int[] {start, x - 1};
				}
				start = -1;
			}
		}
		return best;
	}

	/** The columns x0..x1 drawn anywhere in rows y0..y1, as their extent, or null. */
	static int[] sideColumns(BitSet mask, int n, int y0, int y1, int x0, int x1) {
		int first = -1;
		int last = -1;
		for (int y = y0; y <= y1; y++) {
			for (int x = Math.max(0, x0); x <= Math.min(n - 1, x1); x++) {
				if (mask.get(y * n + x)) {
					first = first < 0 ? x : Math.min(first, x);
					last = Math.max(last, x);
				}
			}
		}
		return first < 0 ? null : new int[] {first, last};
	}

	/** The two legs' column ranges: the runs of the leg rows' top row. */
	static int[][] legRuns(BitSet mask, int n, int y0, int y1) {
		int[][] out = new int[2][];
		int k = 0;
		int start = -1;
		for (int x = 0; x <= n && k < 2; x++) {
			boolean on = x < n && mask.get(y0 * n + x);
			if (on && start < 0) {
				start = x;
			} else if (!on && start >= 0) {
				out[k++] = new int[] {start, x - 1};
				start = -1;
			}
		}
		if (out[1] == null) {
			out[1] = out[0];
		}
		return out;
	}

	/** The share of the front frame's pixels inside some part's rectangle. */
	static double cover(List<Part> parts, BitSet front, int n) {
		int drawn = front.cardinality();
		if (drawn == 0) {
			return 0;
		}
		int covered = 0;
		for (int i = front.nextSetBit(0); i >= 0; i = front.nextSetBit(i + 1)) {
			int x = i % n;
			int y = i / n;
			for (Part p : parts) {
				if (x >= p.x0() && x < p.x0() + p.w() && y >= p.y0() && y < p.y0() + p.h()) {
					covered++;
					break;
				}
			}
		}
		return covered / (double) drawn;
	}
}
