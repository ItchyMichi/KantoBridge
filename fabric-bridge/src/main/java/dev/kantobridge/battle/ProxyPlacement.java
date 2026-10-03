package dev.kantobridge.battle;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Where a battle proxy stands (docs/PLAN.md §4.4): on the trainer's cell when the WorldIndex knows it and it is near the
 * player, otherwise a few blocks in front of the player. Nobody sees the proxy, but Cobblemon sends the Pokémon out
 * between the two sides, so the trainer's own cell keeps the battle where the player's client draws the trainer.
 */
public final class ProxyPlacement {
	/** Further than this from the player, a trainer cell is not trusted (another layer, a stale message). */
	public static final double MAX_DISTANCE = 32.0;
	/** In front of the player when there is no usable trainer cell: two Gen 1 cells. */
	public static final double IN_FRONT = 4.0;

	private ProxyPlacement() {
	}

	/**
	 * @param trainerCell the centre of the trainer's cell at its map's feet level, or null when unknown
	 * @param player      the player's position
	 * @param playerYaw   the player's yaw (degrees, Minecraft's convention)
	 */
	public static Vec3 choose(@Nullable Vec3 trainerCell, Vec3 player, float playerYaw) {
		if (trainerCell != null && trainerCell.distanceTo(player) <= MAX_DISTANCE) {
			return trainerCell;
		}
		Vec3 ahead = Vec3.directionFromRotation(0.0F, playerYaw);
		return player.add(ahead.x * IN_FRONT, 0.0, ahead.z * IN_FRONT);
	}

	/** The yaw that faces {@code from} towards {@code to} (Minecraft's convention: 0 = south, 90 = west). */
	public static float yawTowards(Vec3 from, Vec3 to) {
		double dx = to.x - from.x;
		double dz = to.z - from.z;
		if (dx * dx + dz * dz < 1.0E-8) {
			return 0.0F;
		}
		return Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F);
	}
}
