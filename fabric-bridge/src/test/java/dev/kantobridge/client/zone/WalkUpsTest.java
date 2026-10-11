package dev.kantobridge.client.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.world.Dir;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The client's half of a sighting (WR5): the "!" for a second, then the walk; the player held until the walk is over. */
class WalkUpsTest {
	@Test
	void theWalkStartsAfterTheExclaimAndTheHoldOutlastsIt() {
		WalkUps walkUps = new WalkUps();
		walkUps.sighted("zone:z:a1.t1", 7, 0, Dir.RIGHT, 32, 10_000);
		assertTrue(walkUps.holding(10_000));
		assertEquals(List.of(), walkUps.due(10_999));
		assertEquals(List.of(new WalkUps.Walk("zone:z:a1.t1", 7, 0, Dir.RIGHT, 32, 11_000)), walkUps.due(11_000));
		assertEquals(List.of(), walkUps.due(12_000), "done once");
		// 1 s of "!", 32 frames (533 ms) of walk, half a second more
		assertTrue(walkUps.holding(12_032));
		assertFalse(walkUps.holding(12_033));
		walkUps.sighted("zone:z:a1.t2", 7, 1, Dir.UP, 0, 20_000);
		walkUps.clear();
		assertFalse(walkUps.holding(20_000));
		assertEquals(List.of(), walkUps.due(30_000));
	}
}
