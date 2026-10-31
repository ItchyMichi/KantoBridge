package dev.kantobridge.client.move;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import net.minecraft.client.player.Input;
import org.jetbrains.annotations.Nullable;

/**
 * Holds the Minecraft player still while the hidden game isn't taking steps (docs/PLAN.md §2.4 flows 6, 7 and 13,
 * §4.3 {@code InputLock}): {@code KeyboardInputMixin} calls {@link #apply} at the end of every {@code KeyboardInput.tick},
 * which zeroes movement, jump and sneak while locked. The lock is decided once per world tick by {@link #reason}; only
 * Kanto worlds (the server sent a WorldIndex) are ever locked, and never in creative or spectator mode. A Cobblemon
 * battle holds the player before anything else: the hidden game waits for its result (M5). With the battle screen
 * minimised the player roams instead ({@link Reason#ROAM}, {@link BattleRoam}): not locked, but fenced, without jump or sneak.
 */
public final class InputLock {
	/** Why the player is held (or {@link #NONE}). */
	public enum Reason {
		NONE,
		/** A Cobblemon battle runs for the hidden game, which waits for its result (M5). */
		BATTLE,
		/**
		 * BATTLE_ROAM_PLAN: the same battle with its screen minimised: the player walks within {@link BattleRoam#RADIUS} of
		 * where it started, with no jump or sneak ({@link InputLock#setRoam}); not a lock.
		 */
		ROAM,
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
		/** One of our screens is open, or a hidden-game UI is open while something covers its screen (M4). */
		SCREEN,
		/** The hidden game's mode isn't {@code idle} (a menu, a cutscene, a transition). */
		BUSY,
		/**
		 * BIKE_PLAN D1: the hidden player rides a bike and the hidden game drives it: the player's own movement, jump and sneak
		 * are zeroed; the ride reads the keys physically ({@code RideInput}).
		 */
		RIDING
	}

	/** Implemented by our screens (M4) that should hold the player even where vanilla wouldn't. */
	public interface Holds {
	}

	/** Mixed into {@code KeyboardInput}, so a test can tell the mixin applied. */
	public interface Marker {
	}

	private static volatile boolean locked;
	/** Sneak never crouches (Cycling Road: the key is the brake, {@link SlopeBrake}). */
	private static volatile boolean noSneak;
	/** Held on a ladder: the lock keeps sneak down, so the player hangs on instead of sliding down (a cave shaft's take). */
	private static volatile boolean holdOnLadder;
	/** BATTLE_ROAM_PLAN: while set (and not locked), the player roams in a battle: fenced, no jump, no sneak. */
	private static volatile @Nullable Roam roam;
	/** Other reasons to hold the player as if one of our screens were open (a hidden UI whose screen is covered). */
	private static final List<BooleanSupplier> HOLDS = new CopyOnWriteArrayList<>();

	private InputLock() {
	}

	public static boolean locked() {
		return locked;
	}

	public static void set(boolean value) {
		locked = value;
	}

	/** While set, sneaking doesn't crouch (the key is read for Cycling Road's brake instead). */
	public static void setNoSneak(boolean value) {
		noSneak = value;
	}

	public static boolean noSneak() {
		return noSneak;
	}

	/**
	 * While set and locked, sneak stays down: a player held on a ladder hangs on (Minecraft's sneak stops the slide down)
	 * instead of sliding back into the shaft while the hidden game takes the climb's warp (CAVES_3D_PLAN §3.6).
	 */
	public static void setHoldOnLadder(boolean value) {
		holdOnLadder = value;
	}

	/**
	 * BATTLE_ROAM_PLAN: this tick's roam (the anchor and where the player stands and looks), or null when not roaming. Set
	 * once per world tick, before the player's input is read.
	 */
	public static void setRoam(@Nullable Roam value) {
		roam = value;
	}

	public static @Nullable Roam roam() {
		return roam;
	}

	/** A roam's fence for this tick: the battle's start, and the player's position and yaw (degrees). */
	public record Roam(BattleRoam.Anchor anchor, double x, double z, float yaw) {
	}

	/** Adds a condition under which the player is held like with one of our screens open (client thread). */
	public static void holdWhile(BooleanSupplier hold) {
		HOLDS.add(hold);
	}

	/** True when one of our screens is open, or something else asked to hold the player like one ({@link #holdWhile}). */
	public static boolean screenHolds(@Nullable Object screen) {
		if (screen instanceof Holds) {
			return true;
		}
		for (BooleanSupplier hold : HOLDS) {
			if (hold.getAsBoolean()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The lock decision.
	 *
	 * @param kantoWorld the server sent this world's WorldIndex
	 * @param freeCamera creative or spectator: free to look around, never held
	 */
	public static Reason reason(boolean kantoWorld, boolean freeCamera, boolean connected, boolean ingame, boolean unbuilt, String mode,
			boolean warping, boolean forced, boolean screen) {
		return reason(kantoWorld, freeCamera, false, connected, ingame, unbuilt, mode, warping, forced, screen);
	}

	/**
	 * The lock decision, with Cobblemon's battles: while one runs for this player the hidden game waits for its result,
	 * so nothing may walk or act on it.
	 *
	 * @param battle the player fights a Cobblemon battle (not spectating one)
	 */
	public static Reason reason(boolean kantoWorld, boolean freeCamera, boolean battle, boolean connected, boolean ingame, boolean unbuilt,
			String mode, boolean warping, boolean forced, boolean screen) {
		return reason(kantoWorld, freeCamera, battle, false, connected, ingame, unbuilt, mode, warping, forced, screen);
	}

	/**
	 * The lock decision, with a battle's minimised screen (BATTLE_ROAM_PLAN): the player roams instead of being held.
	 *
	 * @param roam the battle's screen is minimised (only read during a battle)
	 */
	public static Reason reason(boolean kantoWorld, boolean freeCamera, boolean battle, boolean roam, boolean connected, boolean ingame,
			boolean unbuilt, String mode, boolean warping, boolean forced, boolean screen) {
		if (!kantoWorld || freeCamera) {
			return Reason.NONE;
		}
		if (battle) {
			return roam ? Reason.ROAM : Reason.BATTLE;
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
		if (noSneak) {
			input.shiftKeyDown = false;
		}
		Roam fence = roam;
		if (!locked && fence != null) {
			input.jumping = false;
			input.shiftKeyDown = false;
			BattleRoam.Impulse impulse = BattleRoam.fence(fence.anchor(), fence.x(), fence.z(), fence.yaw(), input.forwardImpulse,
					input.leftImpulse);
			input.forwardImpulse = impulse.forward();
			input.leftImpulse = impulse.left();
			return;
		}
		if (!locked) {
			return;
		}
		input.up = false;
		input.down = false;
		input.left = false;
		input.right = false;
		input.forwardImpulse = 0.0F;
		input.leftImpulse = 0.0F;
		// No jump (auto-jump included) and no sneak: sneaking would stop a forced walk at a ledge's edge. On a ladder
		// during a climb's take, sneak holds on.
		input.jumping = false;
		input.shiftKeyDown = holdOnLadder;
	}
}
