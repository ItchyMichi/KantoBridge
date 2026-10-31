package dev.kantobridge.client.move;

/**
 * Walking about during a Cobblemon battle (docs/specs/BATTLE_ROAM_PLAN.md): with the battle screen minimised (Cobblemon's
 * send-out key, R, or closing the screen) the player walks within {@value #RADIUS} blocks of where the battle started, with
 * no jump and no sneak; the hidden game still waits and is told nothing, and when the battle ends the movement sync puts
 * the player back on the hidden player's cell. The fence keeps a wild battle's flee distance (12 blocks from the wild
 * Pokémon, which stands about 4 blocks ahead) out of reach, so walking never ends a battle. Plain Java.
 */
public final class BattleRoam {
	/** How far from the battle's start the player may walk, in blocks. */
	public static final double RADIUS = 6.0;
	/**
	 * Past the fence, how hard the input is turned back in per block beyond it (full at half a block): walking along the
	 * fence, the player's momentum would otherwise carry it slowly outward (seen on the pod: 7.6 blocks).
	 */
	static final double PULL = 2.0;

	private BattleRoam() {
	}

	/** Where the battle started (the player's feet). */
	public record Anchor(double x, double z) {
	}

	/** The movement impulses as Minecraft's {@code Input} holds them. */
	public record Impulse(float forward, float left) {
	}

	/**
	 * The impulses with their outward part removed once the player stands {@link #RADIUS} or more from the anchor: walking
	 * along the fence or back in still works. Beyond the fence, a moving player's input also leans back in
	 * ({@link #PULL}); standing still is left alone.
	 *
	 * @param yaw the player's yaw in degrees
	 */
	public static Impulse fence(Anchor anchor, double x, double z, float yaw, float forward, float left) {
		double dx = x - anchor.x();
		double dz = z - anchor.z();
		double distance = Math.hypot(dx, dz);
		if (distance < RADIUS || (forward == 0.0F && left == 0.0F)) {
			return new Impulse(forward, left);
		}
		// Minecraft's input vector (Entity.getInputVector): the impulses turned by the yaw
		double radians = Math.toRadians(yaw);
		double sin = Math.sin(radians);
		double cos = Math.cos(radians);
		double mx = left * cos - forward * sin;
		double mz = forward * cos + left * sin;
		double radial = (mx * dx + mz * dz) / distance;
		double excess = distance - RADIUS;
		double want = excess > 1.0E-6 ? -Math.min(1.0, excess * PULL) : 0.0;
		if (radial <= want) {
			return new Impulse(forward, left);
		}
		mx += (want - radial) * dx / distance;
		mz += (want - radial) * dz / distance;
		return new Impulse((float) (mz * cos - mx * sin), (float) (mx * cos + mz * sin));
	}
}
