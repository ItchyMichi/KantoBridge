package dev.kantobridge.client.dev;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * The autopilot's walks ({@link Autopilot}): a breadth-first search over walkable block columns inside a box, then the
 * path pulled straight wherever the body fits. Pure logic, no Minecraft.
 */
final class WalkPlanner {
	/** Which block columns a player may stand in. */
	interface Grid {
		boolean walkable(int x, int z);
	}

	/** The search box, inclusive. */
	record Box(int minX, int minZ, int maxX, int maxZ) {
		boolean contains(int x, int z) {
			return x >= this.minX && x <= this.maxX && z >= this.minZ && z <= this.maxZ;
		}
	}

	private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	private WalkPlanner() {
	}

	/**
	 * The path, start to end, to the column farthest (in steps) from (sx, sz) that is at most {@code maxSteps} away, or
	 * just the start when nothing else is reachable. Ties go to the column found first, so the same grid gives the same
	 * walk.
	 */
	static List<int[]> farthest(Grid grid, Box box, int sx, int sz, int maxSteps) {
		int w = box.maxX() - box.minX() + 1;
		int d = box.maxZ() - box.minZ() + 1;
		int[] dist = new int[w * d];
		int[] from = new int[w * d];
		Arrays.fill(dist, -1);
		int start = (sz - box.minZ()) * w + (sx - box.minX());
		dist[start] = 0;
		from[start] = -1;
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		queue.add(start);
		int best = start;
		while (!queue.isEmpty()) {
			int at = queue.poll();
			if (dist[at] > dist[best]) {
				best = at;
			}
			if (dist[at] >= maxSteps) {
				continue;
			}
			int x = at % w + box.minX();
			int z = at / w + box.minZ();
			for (int[] s : STEPS) {
				int nx = x + s[0];
				int nz = z + s[1];
				if (!box.contains(nx, nz)) {
					continue;
				}
				int next = (nz - box.minZ()) * w + (nx - box.minX());
				if (dist[next] < 0 && grid.walkable(nx, nz)) {
					dist[next] = dist[at] + 1;
					from[next] = at;
					queue.add(next);
				}
			}
		}
		List<int[]> path = new ArrayList<>();
		for (int at = best; at >= 0; at = from[at]) {
			path.add(new int[] {at % w + box.minX(), at / w + box.minZ()});
		}
		java.util.Collections.reverse(path);
		return path;
	}

	/**
	 * The path, start to end, to the nearest column in {@code goals} (keys {@link Autopilot#key}), or an empty list when
	 * none is reachable. Goal columns need not be walkable (a gate house's doorway may be a block the walk stops at).
	 */
	static List<int[]> toward(Grid grid, Box box, int sx, int sz, Set<Long> goals) {
		int w = box.maxX() - box.minX() + 1;
		int d = box.maxZ() - box.minZ() + 1;
		int[] from = new int[w * d];
		Arrays.fill(from, -2);
		int start = (sz - box.minZ()) * w + (sx - box.minX());
		from[start] = -1;
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		queue.add(start);
		int found = goals.contains(Autopilot.key(sx, sz)) ? start : -1;
		while (!queue.isEmpty() && found < 0) {
			int at = queue.poll();
			int x = at % w + box.minX();
			int z = at / w + box.minZ();
			for (int[] s : STEPS) {
				int nx = x + s[0];
				int nz = z + s[1];
				if (!box.contains(nx, nz)) {
					continue;
				}
				int next = (nz - box.minZ()) * w + (nx - box.minX());
				if (from[next] != -2) {
					continue;
				}
				boolean goal = goals.contains(Autopilot.key(nx, nz));
				if (goal || grid.walkable(nx, nz)) {
					from[next] = at;
					if (goal) {
						found = next;
						break;
					}
					queue.add(next);
				}
			}
		}
		List<int[]> path = new ArrayList<>();
		for (int at = found; at >= 0; at = from[at]) {
			path.add(new int[] {at % w + box.minX(), at / w + box.minZ()});
		}
		java.util.Collections.reverse(path);
		return path;
	}

	/**
	 * The path, start to end, to the reachable column nearest (straight-line) to {@code (tx, tz)}: the way towards a goal
	 * the client hasn't loaded yet (its chunks read as air), to walk and plan again from. Just the start when nothing
	 * else is reachable.
	 */
	static List<int[]> nearest(Grid grid, Box box, int sx, int sz, int tx, int tz) {
		int w = box.maxX() - box.minX() + 1;
		int d = box.maxZ() - box.minZ() + 1;
		int[] from = new int[w * d];
		Arrays.fill(from, -2);
		int start = (sz - box.minZ()) * w + (sx - box.minX());
		from[start] = -1;
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		queue.add(start);
		int best = start;
		long bestDistance = Long.MAX_VALUE;
		while (!queue.isEmpty()) {
			int at = queue.poll();
			int x = at % w + box.minX();
			int z = at / w + box.minZ();
			long distance = (long) (x - tx) * (x - tx) + (long) (z - tz) * (z - tz);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = at;
			}
			for (int[] s : STEPS) {
				int nx = x + s[0];
				int nz = z + s[1];
				if (!box.contains(nx, nz)) {
					continue;
				}
				int next = (nz - box.minZ()) * w + (nx - box.minX());
				if (from[next] == -2 && grid.walkable(nx, nz)) {
					from[next] = at;
					queue.add(next);
				}
			}
		}
		List<int[]> path = new ArrayList<>();
		for (int at = best; at >= 0; at = from[at]) {
			path.add(new int[] {at % w + box.minX(), at / w + box.minZ()});
		}
		java.util.Collections.reverse(path);
		return path;
	}

	/**
	 * Fewer, straighter waypoints (block-column centres): from each kept point, the farthest later point the body (a
	 * square of {@code halfWidth} around each sample along the line) reaches over walkable columns only. The last point
	 * is always kept.
	 */
	static List<double[]> smooth(Grid grid, List<int[]> path, double halfWidth) {
		List<double[]> out = new ArrayList<>();
		if (path.isEmpty()) {
			return out;
		}
		int i = 0;
		out.add(centre(path.get(0)));
		while (i < path.size() - 1) {
			int j = i + 1;
			for (int k = path.size() - 1; k > i + 1; k--) {
				if (clear(grid, centre(path.get(i)), centre(path.get(k)), halfWidth)) {
					j = k;
					break;
				}
			}
			out.add(centre(path.get(j)));
			i = j;
		}
		return out;
	}

	/** True when every sample of the segment, a quarter block apart, has all four body corners over walkable columns. */
	static boolean clear(Grid grid, double[] a, double[] b, double halfWidth) {
		double dx = b[0] - a[0];
		double dz = b[1] - a[1];
		int samples = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) * 4));
		for (int s = 0; s <= samples; s++) {
			double t = (double) s / samples;
			double px = a[0] + dx * t;
			double pz = a[1] + dz * t;
			for (double ox : new double[] {-halfWidth, halfWidth}) {
				for (double oz : new double[] {-halfWidth, halfWidth}) {
					if (!grid.walkable((int) Math.floor(px + ox), (int) Math.floor(pz + oz))) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/** The path's length in blocks along its waypoints. */
	static double length(List<double[]> points) {
		double total = 0;
		for (int i = 1; i < points.size(); i++) {
			total += Math.hypot(points.get(i)[0] - points.get(i - 1)[0], points.get(i)[1] - points.get(i - 1)[1]);
		}
		return total;
	}

	private static double[] centre(int[] column) {
		return new double[] {column[0] + 0.5, column[1] + 0.5};
	}
}
