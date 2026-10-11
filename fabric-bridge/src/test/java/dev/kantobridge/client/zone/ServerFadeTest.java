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
}
