package dev.kantobridge.client.move;

import dev.kantobridge.world.Dir;
import org.jetbrains.annotations.Nullable;

/**
 * Which Gen 1 direction the player's movement keys point, given the camera: the world direction of Minecraft's own
 * input vector ({@code Entity.getInputVector}), when one axis clearly dominates. Diagonal input has no direction, since
 * Gen 1 can't step diagonally.
 */
public final class Intent {
	/** Impulses below this (after sneaking's 0.3) count as no input. */
	public static final float DEADZONE = 0.1F;
	/** The main axis must be this much stronger than the other, so a 45° heading never flickers between two. */
	public static final double DOMINANCE = 1.2;

	private Intent() {
	}

	/**
	 * @param leftImpulse    {@code Input.leftImpulse} (strafe; positive is left)
	 * @param forwardImpulse {@code Input.forwardImpulse}
	 * @param yaw            the player's yaw in degrees (0 looks south)
	 */
	public static @Nullable Dir of(float leftImpulse, float forwardImpulse, float yaw) {
		if (Math.hypot(leftImpulse, forwardImpulse) < DEADZONE) {
			return null;
		}
		double radians = Math.toRadians(yaw);
		double sin = Math.sin(radians);
		double cos = Math.cos(radians);
		double worldX = leftImpulse * cos - forwardImpulse * sin;
		double worldZ = forwardImpulse * cos + leftImpulse * sin;
		double ax = Math.abs(worldX);
		double az = Math.abs(worldZ);
		if (Math.max(ax, az) < DOMINANCE * Math.min(ax, az)) {
			return null;
		}
		if (ax > az) {
			return worldX > 0 ? Dir.RIGHT : Dir.LEFT;
		}
		return worldZ > 0 ? Dir.DOWN : Dir.UP;
	}
}
