package dev.kantobridge.client.puppet;

import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.WorldIndex;
import org.jetbrains.annotations.Nullable;

/**
 * Which puppets are worth drawing (M10 performance pass): a puppet farther than {@link #MAX_DISTANCE} blocks from the
 * camera, or outside the hidden player's current map area, isn't drawn, so its frame and bubble cost nothing that frame.
 * Puppets still move every tick (they are solid for the local player), only their drawing is skipped. Plain Java.
 *
 * <p>Gen 1 shows 10×9 cells around the player (20×18 blocks here); {@link #MAX_DISTANCE} is a few screens of that. The
 * map area is the hidden player's map's footprint plus {@link #MARGIN} blocks, on its own layer (interiors lie stacked
 * {@link Scale#LAYER_STEP} blocks apart): puppets left over from another map, or on another layer, aren't drawn.
 */
public final class PuppetCulling {
	/** Puppets farther than this from the camera (blocks) aren't drawn. */
	public static final double MAX_DISTANCE = 48.0;
	/** How far outside the map's footprint a puppet may stand and still count as on it (blocks). */
	public static final double MARGIN = 4.0;

	/**
	 * The hidden player's map area, in block coordinates.
	 *
	 * @param y the map's walking level (feet)
	 */
	public record Area(double minX, double minZ, double maxX, double maxZ, double y) {
		/** A map's area: its footprint (cells × 2 blocks) at its walking level. */
		public static Area of(MapPlacement map) {
			return new Area(map.x(), map.z(), map.x() + map.blockWidth(), map.z() + map.blockDepth(), map.y());
		}

		/** A Wild Routes zone's area (its route map stands for the whole zone): the zone's box at its floor's level. */
		public static Area of(WorldIndex.Zone zone) {
			return new Area(zone.x(), zone.z(), zone.x() + zone.w(), zone.z() + zone.d(), zone.y());
		}

		/** True when a point stands on this map: within its footprint plus the margin, and on its layer. */
		public boolean contains(double x, double y, double z) {
			return x >= this.minX - MARGIN && x <= this.maxX + MARGIN && z >= this.minZ - MARGIN && z <= this.maxZ + MARGIN
					&& Math.abs(y - this.y) < Scale.LAYER_STEP / 2.0;
		}
	}

	private PuppetCulling() {
	}

	/**
	 * Whether a puppet at ({@code x}, {@code y}, {@code z}) is drawn for a camera at ({@code cameraX}, {@code cameraY},
	 * {@code cameraZ}).
	 *
	 * @param area the hidden player's current map area, or null when it isn't known (then only the distance counts)
	 */
	public static boolean visible(double x, double y, double z, double cameraX, double cameraY, double cameraZ, @Nullable Area area) {
		double dx = x - cameraX;
		double dy = y - cameraY;
		double dz = z - cameraZ;
		if (!(dx * dx + dy * dy + dz * dz <= MAX_DISTANCE * MAX_DISTANCE)) {
			return false;
		}
		return area == null || area.contains(x, y, z);
	}
}
