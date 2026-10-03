package dev.kantobridge.client.move;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * The client's half of Gen 1 movement speed (docs/PLAN.md P2, §4.3 {@code SpeedSync}). The server sets the speeds and
 * zeroes jumping ({@code server.Rules}); Minecraft syncs both to the client. Sprinting can only be stopped here:
 * {@code LocalPlayerMixin} asks {@link #sprintBlocked} before the player may start sprinting.
 */
public final class SpeedSync {
	/** Jump strength at or below this means no jump at all ({@code LivingEntity.jumpFromGround}). */
	private static final double NO_JUMP = 1.0E-5;

	/** Mixed into {@code LocalPlayer}, so a test can tell the mixin applied. */
	public interface Marker {
	}

	private SpeedSync() {
	}

	/**
	 * True where Kanto's movement rules hold: the server zeroed this player's jump strength (it does that for every
	 * player on a server running the mod), or the movement sync is following the hidden game.
	 */
	public static boolean sprintBlocked(LocalPlayer player) {
		AttributeInstance jump = player.getAttribute(Attributes.JUMP_STRENGTH);
		return (jump != null && jump.getBaseValue() <= NO_JUMP) || MoveRuntime.following();
	}
}
