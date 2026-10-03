package dev.kantobridge.client.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** What the client reports as {@code activity}, and when. */
class ActivityTrackerTest {
	@Test
	void precedence() {
		assertEquals("battle", ActivityTracker.of(true, true, "cutscene"));
		assertEquals("dialogue", ActivityTracker.of(false, true, "cutscene"));
		assertEquals("cutscene", ActivityTracker.of(false, false, "cutscene"));
		assertEquals("cutscene", ActivityTracker.of(false, false, "forced"));
		for (String mode : new String[] {"idle", "screen", "busy", "unknown"}) {
			assertEquals("none", ActivityTracker.of(false, false, mode), mode);
		}
		assertEquals("none", ActivityTracker.of(false, false, null));
	}

	@Test
	void pausedComesAfterABattleAndBeforeEverythingElse() {
		assertEquals("battle", ActivityTracker.of(true, true, true, "cutscene"), "a Cobblemon battle goes on without Gen1Recomp");
		assertEquals("paused", ActivityTracker.of(false, true, true, "cutscene"));
		assertEquals("paused", ActivityTracker.of(false, true, false, "idle"));
		assertEquals("dialogue", ActivityTracker.of(false, false, true, "idle"));
		assertEquals(ActivityTracker.of(false, true, "cutscene"), ActivityTracker.of(false, false, true, "cutscene"), "the old form never pauses");
	}

	@Test
	void sentOnChangeAndAgainAfterAJoin() {
		ActivityTracker tracker = new ActivityTracker();
		assertEquals("none", tracker.next("none"), "the first one always goes out");
		tracker.sent("none");
		assertNull(tracker.next("none"));
		assertEquals("dialogue", tracker.next("dialogue"));
		// Not sent (no server link): asked again next time.
		assertEquals("dialogue", tracker.next("dialogue"));
		tracker.sent("dialogue");
		assertNull(tracker.next("dialogue"));
		tracker.reset();
		assertEquals("dialogue", tracker.next("dialogue"), "a new server hears it again");
	}
}
