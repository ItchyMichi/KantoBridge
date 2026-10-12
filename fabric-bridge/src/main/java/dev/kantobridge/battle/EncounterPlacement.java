package dev.kantobridge.battle;

import dev.kantobridge.world.Dir;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Where a static, surfing or fishing encounter's Pokémon appears (docs/PLAN.md §2.4 flow 9): on the cell the hidden game
 * names (a static object's cell, the water a line was cast into) when the WorldIndex knows it, it is near the player and
 * not the player's own cell; otherwise two cells ahead of the player in the direction they face (on the water when
 * surfing or fishing, which is what lies ahead then). The height is the ground's, found by the caller. Plain Java.
 */
public final class EncounterPlacement {
	/** Further than this from the player, a given cell is not trusted (another layer, a stale message). */
	public static final double MAX_DISTANCE = 32.0;
	/**
	 * Further than this above or below the player, a given cell is not trusted either: another level (the cave area
	 * stacks pieces 11 blocks apart, docs/specs/CAVES_3D_PLAN.md §3.1; a raised floor is 2 up).
	 */
	public static final double MAX_RISE = 4.0;
	/** Closer than this (horizontally) the Pokémon would stand in the player: the player's own cell. */
	public static final double MIN_DISTANCE = 1.5;
	/** Two Gen 1 cells. */
	public static final double AHEAD = 4.0;

	private EncounterPlacement() {
	}

	/**
	 * @param cell      the centre of the given cell at its map's feet level, or null when there is none
	 * @param player    the player's position
	 * @param playerYaw the player's yaw (degrees, Minecraft's convention), snapped to the facing
	 */
	public static Vec3 choose(@Nullable Vec3 cell, Vec3 player, float playerYaw) {
		if (cell != null && cell.distanceTo(player) <= MAX_DISTANCE && Math.abs(cell.y - player.y) <= MAX_RISE
				&& horizontal(cell, player) >= MIN_DISTANCE) {
			return cell;
		}
		Dir facing = Dir.fromYaw(playerYaw);
		// Gen 1's "down" is +Z (south), "right" is +X (east).
		return new Vec3(player.x + facing.dx() * AHEAD, player.y, player.z + facing.dy() * AHEAD);
	}

	private static double horizontal(Vec3 a, Vec3 b) {
		double dx = a.x - b.x;
		double dz = a.z - b.z;
		return Math.sqrt(dx * dx + dz * dz);
	}
}
