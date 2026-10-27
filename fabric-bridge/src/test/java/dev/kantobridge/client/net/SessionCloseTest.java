package dev.kantobridge.client.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** WORLD_SESSIONS_PLAN WS5: the quit choreography's waits, timeouts and closes (D6, D7). */
class SessionCloseTest {
	static final class Game implements SessionClose.Target {
		final String name;
		boolean linkUp = true;
		SaveRelay.RequestState state = SaveRelay.RequestState.NONE;
		@Nullable String refusal;
		int requests;
		int closes;

		Game(String name) {
			this.name = name;
		}

		@Override
		public String name() {
			return this.name;
		}

		@Override
		public boolean requestSave(long now) {
			if (!this.linkUp) {
				return false;
			}
			this.requests++;
			this.state = SaveRelay.RequestState.SENT;
			return true;
		}

		@Override
		public SaveRelay.RequestState saveState() {
			return this.state;
		}

		@Override
		public @Nullable String refusal() {
			return this.refusal;
		}

		@Override
		public void close() {
			this.closes++;
		}
	}

	@Test
	void bothGamesSaveThenTheLeaveRunsThenBothClose() {
		Game blue = new Game("Blue");
		Game crystal = new Game("Crystal");
		SessionClose quit = new SessionClose();
		assertTrue(quit.begin(List.of(blue, crystal), 0, false));
		assertEquals(List.of("Blue", "Crystal"), quit.names());
		assertEquals(SessionClose.Step.WAIT, quit.tick(100));
		blue.state = SaveRelay.RequestState.WRITTEN;
		assertEquals(SessionClose.Step.WAIT, quit.tick(200), "Crystal hasn't answered");
		crystal.state = SaveRelay.RequestState.DONE;
		assertEquals(SessionClose.Step.DISCONNECT, quit.tick(300));
		quit.afterDisconnect();
		assertEquals(1, blue.closes);
		assertEquals(1, crystal.closes);
		assertTrue(quit.failed().isEmpty());
		assertFalse(quit.running());
		assertEquals(SessionClose.Step.IDLE, quit.tick(400));
	}

	@Test
	void aRefusalOrTheTimeoutLeavesThatGameAsItIsAndATitleIsClosedToo() {
		Game blue = new Game("Blue");
		Game crystal = new Game("Crystal");
		Game emerald = new Game("Emerald");
		SessionClose quit = new SessionClose();
		quit.begin(List.of(blue, crystal, emerald), 0, false);
		blue.state = SaveRelay.RequestState.REFUSED;
		blue.refusal = "not_idle";
		emerald.state = SaveRelay.RequestState.REFUSED;
		emerald.refusal = "not_ingame";
		assertEquals(SessionClose.Step.WAIT, quit.tick(SessionClose.QUIT_WAIT_MS - 1));
		assertEquals(SessionClose.Step.DISCONNECT, quit.tick(SessionClose.QUIT_WAIT_MS), "Crystal ran out of time");
		quit.afterDisconnect();
		assertEquals(0, blue.closes, "refused: never returned to its title (D7)");
		assertEquals(0, crystal.closes, "timed out: left as it is");
		assertEquals(1, emerald.closes, "at its title: closed (back to its offline slot)");
		assertEquals(List.of("Blue", "Crystal"), quit.failed());
	}

	@Test
	void theWindowsCloseWaitsFiveSecondsAndNoBoundGameMeansNoWait() {
		Game blue = new Game("Blue");
		SessionClose quit = new SessionClose();
		quit.begin(List.of(blue), 0, true);
		assertEquals(SessionClose.Step.DISCONNECT, quit.tick(SessionClose.WINDOW_WAIT_MS));
		Game down = new Game("Crystal");
		down.linkUp = false;
		assertFalse(new SessionClose().begin(List.of(down), 0, false), "nothing to wait for: the vanilla leave at once");
	}
}
