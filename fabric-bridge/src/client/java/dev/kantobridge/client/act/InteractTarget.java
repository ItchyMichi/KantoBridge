package dev.kantobridge.client.act;

import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Place;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.WorldIndex;
import org.jetbrains.annotations.Nullable;

/**
 * Which cell an A press is aimed at (docs/PLAN.md §2.4 flow 2; PROTOCOL.md {@code act.interact}): gen1-bridge faces that
 * cell from the hidden player's and presses A, so the cell is named in the hidden player's own map. Outdoor maps share
 * one cell grid, so a cell on a neighbouring outdoor map is named by its offset from the player's map (which may fall
 * outside it, as {@code -1}); interiors only reach their own cells. Plain Java.
 */
public final class InteractTarget {
	/** Gen 1 talks to the cell in front, or to the one behind a counter. */
	public static final int REACH = 2;

	private InteractTarget() {
	}

	/** The cell in front of {@code x, y} facing {@code facing}, in the same map (it may lie just outside it). */
	public static CellPos faced(String map, int x, int y, Dir facing) {
		return new CellPos(map, x + facing.dx(), y + facing.dy());
	}

	/**
	 * The cell in front of the player for the A key: the Minecraft player's own cell (its feet, through the WorldIndex,
	 * named in the hidden player's map) must be the hidden player's, so the press is aimed from where both stand; then
	 * one step in the camera's quadrant. Null while the two are out of step (a correction is under way) or off the map.
	 */
	public static @Nullable CellPos inFront(WorldIndex index, String hiddenMap, int hiddenX, int hiddenY, double x, double y, double z,
			Dir facing) {
		CellPos feet = clicked(index, hiddenMap, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
		if (feet == null || feet.x() != hiddenX || feet.y() != hiddenY) {
			return null;
		}
		return faced(hiddenMap, hiddenX, hiddenY, facing);
	}

	/**
	 * A clicked block's cell, named in {@code playerMap}; null when the block is on no map, or on another level than the
	 * player's map.
	 */
	public static @Nullable CellPos clicked(WorldIndex index, String playerMap, int blockX, int blockY, int blockZ) {
		CellPos cell = index.toCell(blockX, blockY, blockZ).orElse(null);
		if (cell == null) {
			return null;
		}
		if (cell.map().equals(playerMap)) {
			return cell;
		}
		return inPlayerMap(index, playerMap, cell.map(), blockX, blockZ);
	}

	/** A cell of map {@code map} (a puppet's), named in {@code playerMap}; null across levels. */
	public static @Nullable CellPos cellOf(WorldIndex index, String playerMap, String map, int x, int y) {
		if (map.equals(playerMap)) {
			return new CellPos(map, x, y);
		}
		MapPlacement placement = index.map(map);
		if (placement == null) {
			return null;
		}
		return inPlayerMap(index, playerMap, map, placement.x() + Scale.cellToBlock(x), placement.z() + Scale.cellToBlock(y));
	}

	/** True when gen1-bridge can face {@code target} from {@code x, y}: on one axis, 1 to {@link #REACH} cells away. */
	public static boolean reachable(String map, int x, int y, CellPos target) {
		if (!target.map().equals(map)) {
			return false;
		}
		int dx = Math.abs(target.x() - x);
		int dy = Math.abs(target.y() - y);
		return (dx == 0) != (dy == 0) && Math.max(dx, dy) <= REACH;
	}

	/** The direction from {@code x, y} towards a reachable target. */
	public static @Nullable Dir towards(int x, int y, CellPos target) {
		return Dir.of(Integer.signum(target.x() - x), Integer.signum(target.y() - y));
	}

	private static @Nullable CellPos inPlayerMap(WorldIndex index, String playerMap, String otherMap, int blockX, int blockZ) {
		MapPlacement player = index.map(playerMap);
		MapPlacement other = index.map(otherMap);
		if (player == null || other == null || player.layer() != 0 || other.layer() != 0 || player.y() != other.y()) {
			return null;
		}
		int x = Scale.blockToCell(blockX - player.x());
		int y = Scale.blockToCell(blockZ - player.z());
		return Math.abs(x) <= Place.MAX_COORD && Math.abs(y) <= Place.MAX_COORD ? new CellPos(playerMap, x, y) : null;
	}
}
