package dev.kantobridge.client.move;

import net.minecraft.client.player.Input;

/**
 * Holds the Minecraft player still while the hidden game isn't taking steps (docs/PLAN.md §2.4 flows 6, 7 and 13,
 * §4.3 {@code InputLock}): {@code KeyboardInputMixin} calls {@link #apply} at the end of every {@code KeyboardInput.tick},
 * which zeroes movement, jump and sneak while locked. The lock is decided once per world tick by {@link #reason}; only
 * Kanto worlds (the server sent a WorldIndex) are ever locked, and never in creative or spectator mode.
 */
public final class InputLock {
	/** Why the player is held (or {@link #NONE}). */
	public enum Reason {
		NONE,
		/** No link to gen1-bridge (connecting, dropped, paused for an export). */
		NOT_CONNECTED,
		/** The hidden game is at its title screen or intro: there is no live player to follow. */
		NOT_IN_GAME,
		/** The hidden player is on a map this world doesn't have. */
		UNBUILT_MAP,
		/** A warp fades and teleports. */
		WARP,
		/** The forced mover drives the player. */
		FORCED,
		/** One of our screens is open. */
		SCREEN,
		/** The hidden game's mode isn't {@code idle} (a menu, a cutscene, a transition). */
		BUSY
	}

	/** Implemented by our screens (M4) that should hold the player even where vanilla wouldn't. */
	public interface Holds {
	}

	/** Mixed into {@code KeyboardInput}, so a test can tell the mixin applied. */
	public interface Marker {
	}

	private static volatile boolean locked;

	private InputLock() {
	}

	public static boolean locked() {
		return locked;
	}

	public static void set(boolean value) {
		locked = value;
	}

	/**
	 * The lock decision.
	 *
	 * @param kantoWorld the server sent this world's WorldIndex
	 * @param freeCamera creative or spectator: free to look around, never held
	 */
	public static Reason reason(boolean kantoWorld, boolean freeCamera, boolean connected, boolean ingame, boolean unbuilt, String mode,
			boolean warping, boolean forced, boolean screen) {
		if (!kantoWorld || freeCamera) {
			return Reason.NONE;
		}
		if (!connected) {
			return Reason.NOT_CONNECTED;
		}
		if (!ingame) {
			return Reason.NOT_IN_GAME;
		}
		if (unbuilt) {
			return Reason.UNBUILT_MAP;
		}
		if (warping) {
			return Reason.WARP;
		}
		if (forced) {
			return Reason.FORCED;
		}
		if (screen) {
			return Reason.SCREEN;
		}
		return MovementSync.IDLE.equals(mode) ? Reason.NONE : Reason.BUSY;
	}

	/** Zeroes the movement keys' effect when locked (called at the end of {@code KeyboardInput.tick}). */
	public static void apply(Input input) {
		if (!locked) {
			return;
		}
		input.up = false;
		input.down = false;
		input.left = false;
		input.right = false;
		input.forwardImpulse = 0.0F;
		input.leftImpulse = 0.0F;
		// No jump (auto-jump included) and no sneak: sneaking would stop a forced walk at a ledge's edge.
		input.jumping = false;
		input.shiftKeyDown = false;
	}
}
