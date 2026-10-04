package dev.kantobridge.client.act;

import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import org.jetbrains.annotations.Nullable;

/**
 * The crosshair on closed art (docs/PLAN.md §6 M8 item 4: right-click a Cut tree). A standing tree or a shut door is
 * drawn per player over an invisible story block that never catches the crosshair, so without help a click on the
 * trunk passes through to whatever lies behind it, or to nothing. This finds the closed column the look ray meets first,
 * as the player sees it (the cell's 2×2 blocks, as high as its art), so the click lands on that cell. Flat closed art
 * needs nothing: the floor block under it is the cell's own. Plain Java; {@code ActRuntime} applies it after Minecraft's
 * own pick.
 */
public final class ClosedArtPick {
	/** Faces as Minecraft names the side of a block a ray enters through. */
	public enum Face {
		DOWN, UP, NORTH, SOUTH, WEST, EAST
	}

	/** One closed column: a story cell's key and its box in world coordinates. */
	public record Box(String key, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
	}

	/**
	 * Where the ray meets a column.
	 *
	 * @param distance along the ray, in blocks
	 * @param blockX   the cell's block (at feet level) under the point hit: one of its story blocks
	 */
	public record Hit(String key, double x, double y, double z, double distance, Face face, int blockX, int blockY, int blockZ) {
	}

	private ClosedArtPick() {
	}

	/** The columns of the drawn (closed) tile cells near the eye: those with a height, within {@code reach}. */
	public static List<Box> boxes(WorldIndex index, Iterable<String> drawn, double eyeX, double eyeY, double eyeZ, double reach) {
		return boxes(index, drawn, key -> -1, eyeX, eyeY, eyeZ, reach);
	}

	/**
	 * The same, each column as high as the closed art the chunk mesh draws for it ({@code heights}: from the cell's overlay,
	 * V5; -1 where it has none, which takes the story table's height). The click box keeps the cell's 2×2 footprint.
	 */
	public static List<Box> boxes(WorldIndex index, Iterable<String> drawn, ToIntFunction<String> heights, double eyeX, double eyeY,
			double eyeZ, double reach) {
		List<Box> out = new ArrayList<>();
		for (String key : drawn) {
			StoryCellTable.Entry entry = index.storyCells().get(key);
			if (entry == null || entry.type() != StoryCellType.TILE || entry.closed() == null) {
				continue;
			}
			int overlayHeight = heights.applyAsInt(key);
			int height = overlayHeight >= 0 ? overlayHeight : entry.closed().height();
			if (height <= 0) {
				continue;
			}
			MapPlacement map = index.map(entry.cell().map());
			if (map == null) {
				continue;
			}
			double minX = map.x() + Scale.cellToBlock(entry.cell().x());
			double minZ = map.z() + Scale.cellToBlock(entry.cell().y());
			double minY = map.y();
			Box box = new Box(key, minX, minY, minZ, minX + Scale.CELL_BLOCKS, minY + height, minZ + Scale.CELL_BLOCKS);
			if (distanceSquared(box, eyeX, eyeY, eyeZ) <= reach * reach) {
				out.add(box);
			}
		}
		return out;
	}

	/**
	 * The nearest column the ray from the eye along {@code (dx, dy, dz)} (a unit vector) enters within {@code maxDistance},
	 * or null. A column the eye is inside is ignored.
	 */
	public static @Nullable Hit nearest(List<Box> boxes, double ex, double ey, double ez, double dx, double dy, double dz, double maxDistance) {
		Hit best = null;
		for (Box box : boxes) {
			Hit hit = enter(box, ex, ey, ez, dx, dy, dz);
			if (hit != null && hit.distance() <= maxDistance && (best == null || hit.distance() < best.distance())) {
				best = hit;
			}
		}
		return best;
	}

	/** The slab test: where the ray enters the box, or null when it misses or starts inside. */
	static @Nullable Hit enter(Box box, double ex, double ey, double ez, double dx, double dy, double dz) {
		double[] origin = {ex, ey, ez};
		double[] dir = {dx, dy, dz};
		double[] min = {box.minX(), box.minY(), box.minZ()};
		double[] max = {box.maxX(), box.maxY(), box.maxZ()};
		double enter = Double.NEGATIVE_INFINITY;
		double exit = Double.POSITIVE_INFINITY;
		int axis = -1;
		for (int a = 0; a < 3; a++) {
			if (Math.abs(dir[a]) < 1.0E-12) {
				if (origin[a] < min[a] || origin[a] > max[a]) {
					return null;
				}
				continue;
			}
			double t1 = (min[a] - origin[a]) / dir[a];
			double t2 = (max[a] - origin[a]) / dir[a];
			double near = Math.min(t1, t2);
			double far = Math.max(t1, t2);
			if (near > enter) {
				enter = near;
				axis = a;
			}
			exit = Math.min(exit, far);
		}
		if (axis < 0 || enter > exit || enter < 0.0) {
			return null;
		}
		Face face = switch (axis) {
			case 0 -> dir[0] > 0 ? Face.WEST : Face.EAST;
			case 1 -> dir[1] > 0 ? Face.DOWN : Face.UP;
			default -> dir[2] > 0 ? Face.NORTH : Face.SOUTH;
		};
		double x = ex + dx * enter;
		double y = ey + dy * enter;
		double z = ez + dz * enter;
		int blockX = (int) Math.floor(Math.min(Math.max(x, box.minX()), box.maxX() - 1.0E-6));
		int blockZ = (int) Math.floor(Math.min(Math.max(z, box.minZ()), box.maxZ() - 1.0E-6));
		return new Hit(box.key(), x, y, z, enter, face, blockX, (int) Math.floor(box.minY()), blockZ);
	}

	private static double distanceSquared(Box box, double x, double y, double z) {
		double dx = Math.max(Math.max(box.minX() - x, 0.0), x - box.maxX());
		double dy = Math.max(Math.max(box.minY() - y, 0.0), y - box.maxY());
		double dz = Math.max(Math.max(box.minZ() - z, 0.0), z - box.maxZ());
		return dx * dx + dy * dy + dz * dz;
	}
}
