package dev.kantobridge.client.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** route.fade on the client (WR7): out, dark until moved and drawn (or a timeout), in. */
class ServerFadeTest {
	@Test
	void darkUntilTheTeleportLandsAndTheWorldIsDrawn() {
		ServerFade fade = new ServerFade();
		fade.start(10, 0, 64, 0, 1_000);
		assertTrue(fade.holding());
		assertEquals(0.5F, fade.alpha(1_200), 1e-6);
		fade.tick(0, 64, 0, true, 1_400);
		assertEquals(ServerFade.Phase.HOLD, fade.phase());
		fade.tick(0, 64, 0, true, 3_000);
		assertEquals(ServerFade.Phase.HOLD, fade.phase(), "not moved yet");
		fade.tick(500, 86, 300, false, 3_100);
		assertEquals(ServerFade.Phase.HOLD, fade.phase(), "moved, but not drawn there yet");
		fade.tick(500, 86, 300, true, 3_200);
		assertEquals(ServerFade.Phase.IN, fade.phase());
		assertFalse(fade.holding());
		fade.tick(500, 86, 300, true, 3_700);
		assertEquals(0.0F, fade.alpha(3_700));
	}

	@Test
	void aMoveThatNeverComesTimesOut() {
		ServerFade fade = new ServerFade();
		fade.start(100, 0, 64, 0, 0);
		fade.tick(0, 64, 0, true, 500);
		fade.tick(0, 64, 0, true, 100 * 50 + ServerFade.TIMEOUT_MS + 1);
		assertEquals(ServerFade.Phase.IN, fade.phase());
	}

	@Test
	void aCrossingExtendsTheWaitAndCanLiftIt() {
		ServerFade fade = new ServerFade();
		fade.start(10, 0, 64, 0, 0);
		fade.tick(0, 64, 0, true, 500);
		assertEquals(ServerFade.Phase.HOLD, fade.phase());
		long due = 10 * 50 + ServerFade.TIMEOUT_MS;
		fade.extend(due - 1_000);
		fade.tick(0, 64, 0, true, due + 1);
		assertEquals(ServerFade.Phase.HOLD, fade.phase(), "the intro is still showing: no timeout yet");
		fade.tick(0, 64, 0, true, due - 1_000 + ServerFade.TIMEOUT_MS + 1);
		assertEquals(ServerFade.Phase.IN, fade.phase());
		fade.extend(100_000);
		fade.tick(0, 64, 0, true, 100_000 + ServerFade.IN_MS);
		assertEquals(ServerFade.Phase.NONE, fade.phase(), "extending a fade that is over does nothing");

		fade.start(10, 0, 64, 0, 200_000);
		fade.lift(200_100);
		assertEquals(ServerFade.Phase.IN, fade.phase(), "lifted while fading out");
		assertFalse(fade.holding());
		fade.lift(200_200);
		assertEquals(ServerFade.Phase.IN, fade.phase());
		// Dark again for the final teleport: a start from where the player stands.
		fade.tick(0, 64, 0, true, 200_100 + ServerFade.IN_MS);
		fade.start(0, 5, 64, 5, 201_000);
		assertEquals(ServerFade.Phase.OUT, fade.phase());
		assertTrue(fade.holding());
		fade.tick(5, 64, 5, true, 201_000 + ServerFade.OUT_MS);
		assertEquals(ServerFade.Phase.HOLD, fade.phase());
		fade.tick(9, 64, 5, true, 201_500);
		assertEquals(ServerFade.Phase.IN, fade.phase(), "moved and drawn");
	}
}
