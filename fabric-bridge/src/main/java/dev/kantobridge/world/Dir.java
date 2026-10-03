package dev.kantobridge.world;

import org.jetbrains.annotations.Nullable;

/**
 * A Gen 1 direction (protocol {@code dir}), with its cell step and its Minecraft yaw. Cell rows run north to south,
 * so "up" is north (-Z) and "down" is south (+Z); "left" is west (-X), "right" east (+X).
 */
public enum Dir {
	UP("up", 0, -1, 180.0F),
	DOWN("down", 0, 1, 0.0F),
	LEFT("left", -1, 0, 90.0F),
	RIGHT("right", 1, 0, -90.0F);

	private final String id;
	private final int dx;
	private final int dy;
	private final float yaw;

	Dir(String id, int dx, int dy, float yaw) {
		this.id = id;
		this.dx = dx;
		this.dy = dy;
		this.yaw = yaw;
	}

	/** The protocol name: {@code up}, {@code down}, {@code left} or {@code right}. */
	public String id() {
		return this.id;
	}

	/** Cell (and block X) step. */
	public int dx() {
		return this.dx;
	}

	/** Cell row (and block Z) step. */
	public int dy() {
		return this.dy;
	}

	/** The Minecraft yaw that looks this way (0 is south). */
	public float yaw() {
		return this.yaw;
	}

	public Dir opposite() {
		return switch (this) {
			case UP -> DOWN;
			case DOWN -> UP;
			case LEFT -> RIGHT;
			case RIGHT -> LEFT;
		};
	}

	public static @Nullable Dir byId(@Nullable String id) {
		if (id != null) {
			for (Dir dir : values()) {
				if (dir.id.equals(id)) {
					return dir;
				}
			}
		}
		return null;
	}

	/** The direction of a one-cell step, or null for anything else. */
	public static @Nullable Dir of(int dx, int dy) {
		for (Dir dir : values()) {
			if (dir.dx == dx && dir.dy == dy) {
				return dir;
			}
		}
		return null;
	}

	/** The direction a Minecraft yaw looks closest to (its quadrant). */
	public static Dir fromYaw(float yaw) {
		return switch (Math.floorMod(Math.round(yaw / 90.0F), 4)) {
			case 0 -> DOWN;
			case 1 -> LEFT;
			case 2 -> UP;
			default -> RIGHT;
		};
	}
}
