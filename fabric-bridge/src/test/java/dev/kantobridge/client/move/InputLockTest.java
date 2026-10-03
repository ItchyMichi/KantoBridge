package dev.kantobridge.client.move;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** When the player is held, what holding does to the input, and that the movement mixins apply. */
class InputLockTest {
	@AfterEach
	void unlock() {
		InputLock.set(false);
	}

	private static InputLock.Reason reason(boolean kanto, boolean free, boolean connected, boolean ingame, boolean unbuilt, String mode,
			boolean warping, boolean forced, boolean screen) {
		return InputLock.reason(kanto, free, connected, ingame, unbuilt, mode, warping, forced, screen);
	}

	@Test
	void onlyKantoWorldsAndNeverAFreeCameraAreHeld() {
		assertEquals(InputLock.Reason.NONE, reason(false, false, false, false, false, "busy", true, true, true), "not a Kanto world");
		assertEquals(InputLock.Reason.NONE, reason(true, true, false, false, false, "busy", true, true, true), "creative or spectator");
	}

	@Test
	void reasonsInOrder() {
		assertEquals(InputLock.Reason.NOT_CONNECTED, reason(true, false, false, true, false, "idle", false, false, false));
		assertEquals(InputLock.Reason.NOT_IN_GAME, reason(true, false, true, false, false, "idle", false, false, false));
		assertEquals(InputLock.Reason.UNBUILT_MAP, reason(true, false, true, true, true, "idle", false, false, false));
		assertEquals(InputLock.Reason.WARP, reason(true, false, true, true, false, "idle", true, true, true));
		assertEquals(InputLock.Reason.FORCED, reason(true, false, true, true, false, "idle", false, true, true));
		assertEquals(InputLock.Reason.SCREEN, reason(true, false, true, true, false, "idle", false, false, true));
		for (String mode : new String[] {"screen", "forced", "cutscene", "busy", "unknown"}) {
			assertEquals(InputLock.Reason.BUSY, reason(true, false, true, true, false, mode, false, false, false), mode);
		}
		assertEquals(InputLock.Reason.NONE, reason(true, false, true, true, false, "idle", false, false, false), "free to walk");
	}

	@Test
	void aCobblemonBattleHoldsThePlayerFirst() {
		// The hidden game waits for the battle's result, whatever else is going on.
		assertEquals(InputLock.Reason.BATTLE, InputLock.reason(true, false, true, false, false, false, "idle", false, false, false));
		assertEquals(InputLock.Reason.BATTLE, InputLock.reason(true, false, true, true, true, false, "idle", false, false, false));
		assertEquals(InputLock.Reason.NONE, InputLock.reason(false, false, true, true, true, false, "idle", false, false, false),
				"not a Kanto world");
		assertEquals(InputLock.Reason.NONE, InputLock.reason(true, true, true, true, true, false, "idle", false, false, false),
				"creative or spectator");
		assertEquals(InputLock.Reason.NONE, InputLock.reason(true, false, false, true, true, false, "idle", false, false, false),
				"no battle: free to walk");
	}

	@Test
	void holdingZeroesMovementJumpAndSneak() {
		Input input = new Input();
		fill(input);
		InputLock.apply(input);
		assertEquals(1.0F, input.forwardImpulse, "unlocked: untouched");
		assertTrue(input.jumping);

		InputLock.set(true);
		assertTrue(InputLock.locked());
		InputLock.apply(input);
		assertEquals(0.0F, input.forwardImpulse);
		assertEquals(0.0F, input.leftImpulse);
		assertFalse(input.up || input.down || input.left || input.right);
		assertFalse(input.jumping);
		assertFalse(input.shiftKeyDown);
	}

	@Test
	void theMovementMixinsApplyInDev() {
		// fabric-loader-junit runs Knot on the client side, so client mixins apply here; each one adds a marker.
		assertTrue(InputLock.Marker.class.isAssignableFrom(KeyboardInput.class), "KeyboardInputMixin did not apply");
		assertTrue(SpeedSync.Marker.class.isAssignableFrom(LocalPlayer.class), "LocalPlayerMixin did not apply");
	}

	private static void fill(Input input) {
		input.forwardImpulse = 1.0F;
		input.leftImpulse = -1.0F;
		input.up = true;
		input.right = true;
		input.jumping = true;
		input.shiftKeyDown = true;
	}
}
