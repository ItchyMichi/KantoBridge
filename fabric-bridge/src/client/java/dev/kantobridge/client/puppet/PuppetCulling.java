package dev.kantobridge.client.puppet;

import dev.kantobridge.world.MapLayout;
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
	/** How far above or below a piece's feet level its puppets stand (half the 11 blocks between stacked pieces). */
	public static final double PIECE_REACH = 5.5;

	/**
	 * The hidden player's map area, in block coordinates.
	 *
	 * @param y the map's walking level (feet)
	 */
	public record Area(double minX, double minZ, double maxX, double maxZ, double y, double reach) {
		/** An area on a layer: puppets within half a layer step above or below count. */
		public Area(double minX, double minZ, double maxX, double maxZ, double y) {
			this(minX, minZ, maxX, maxZ, y, Scale.LAYER_STEP / 2.0);
		}

		/** A map's area: its footprint (cells × 2 blocks) at its walking level. */
		public static Area of(MapPlacement map) {
			return new Area(map.x(), map.z(), map.x() + map.blockWidth(), map.z() + map.blockDepth(), map.y());
		}

		/**
		 * The area of cell (cx, cy)'s piece in a map with a layout (docs/specs/CAVES_3D_PLAN.md §3.1): its rectangle at its
		 * feet level, reaching {@link #PIECE_REACH} up and down, since the cave area stacks pieces 11 blocks apart. The
		 * map's area otherwise.
		 */
		public static Area of(WorldIndex index, MapPlacement map, int cx, int cy) {
			MapLayout layout = index.layout(map.id());
			MapLayout.Piece piece = layout == null ? null : layout.pieceAt(cx, cy);
			if (piece == null) {
				return of(map);
			}
			double x0 = map.x() + piece.dx() + Scale.cellToBlock(piece.x());
			double z0 = map.z() + piece.dz() + Scale.cellToBlock(piece.y());
			return new Area(x0, z0, x0 + Scale.cellToBlock(piece.w()), z0 + Scale.cellToBlock(piece.h()), map.y() + piece.dy(), PIECE_REACH);
		}

		/** A Wild Routes zone's area (its route map stands for the whole zone): the zone's box at its floor's level. */
		public static Area of(WorldIndex.Zone zone) {
			return new Area(zone.x(), zone.z(), zone.x() + zone.w(), zone.z() + zone.d(), zone.y());
		}

		/** True when a point stands on this map: within its footprint plus the margin, and on its layer. */
		public boolean contains(double x, double y, double z) {
			return x >= this.minX - MARGIN && x <= this.maxX + MARGIN && z >= this.minZ - MARGIN && z <= this.maxZ + MARGIN
					&& Math.abs(y - this.y) < this.reach;
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
