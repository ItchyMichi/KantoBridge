package dev.kantobridge.pokemon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** State messages go out after a change, at most once per interval, and only when they differ from the last one. */
class SyncDebounceTest {
	@Test
	void changesGoOutDebouncedAndOnlyWhenDifferent() {
		SyncDebounce gate = new SyncDebounce(10);
		assertTrue(gate.due(0), "the first message goes out at once");
		assertTrue(gate.offer("a"));
		gate.sent("a", 0);
		assertFalse(gate.due(1), "nothing changed");
		// A burst of changes (a battle's turns) within the interval: one message, after it.
		gate.mark();
		gate.mark();
		assertFalse(gate.due(5), "within the interval");
		assertTrue(gate.due(10));
		assertFalse(gate.offer("a"), "the same text isn't sent again");
		assertFalse(gate.due(11), "the change was consumed");
		gate.mark();
		assertTrue(gate.due(12) && gate.offer("b"));
		gate.sent("b", 12);
		// forget(): a new link sends the same text again.
		gate.forget();
		assertTrue(gate.due(30) && gate.offer("b"));
		assertThrows(IllegalArgumentException.class, () -> new SyncDebounce(0));
	}
}
