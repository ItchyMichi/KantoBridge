package dev.kantobridge.story;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** story.state: full states and deltas, applied the same way on both sides; malformed messages change nothing. */
class StoryStateMessagesTest {
	private static JsonObject parse(String json) throws Exception {
		return ProtoJson.parseObject(json);
	}

	@Test
	void aFullStateReplacesEverything() throws Exception {
		StoryState state = new StoryState();
		state.activate("OLD_MAP:1:1");
		state.setLit(List.of("OLD_CAVE"));
		JsonObject full = StoryStateMessages.full(List.of("TEST_TOWN:15:4", "TEST_TOWN:10:14"), true, true, List.of("TEST_CAVE"));
		assertEquals("{\"full\":true,\"active\":[\"TEST_TOWN:15:4\",\"TEST_TOWN:10:14\"],\"surf\":true,\"bike\":true,\"lit\":[\"TEST_CAVE\"]}",
				full.toString());
		StoryStateMessages.Applied applied = StoryStateMessages.apply(state, full);
		assertTrue(applied.full());
		assertTrue(applied.surfChanged());
		assertTrue(applied.bikeChanged());
		assertEquals(3, applied.cellsChanged(), "two added, one removed");
		assertEquals(Set.of("TEST_TOWN:15:4", "TEST_TOWN:10:14"), state.active());
		assertTrue(state.surf());
		assertTrue(state.bike());
		assertEquals(Set.of("TEST_CAVE"), state.lit());
	}

	@Test
	void deltasChangeOnlyWhatTheyName() throws Exception {
		StoryState state = new StoryState();
		StoryStateMessages.apply(state, StoryStateMessages.full(List.of("A_MAP:1:1", "A_MAP:2:2"), false, false, List.of()));
		JsonObject delta = StoryStateMessages.delta(List.of("A_MAP:3:3"), List.of("A_MAP:1:1"), null, true, null);
		assertEquals("{\"full\":false,\"add\":[\"A_MAP:3:3\"],\"remove\":[\"A_MAP:1:1\"],\"bike\":true}", delta.toString());
		StoryStateMessages.Applied applied = StoryStateMessages.apply(state, delta);
		assertFalse(applied.full());
		assertTrue(applied.bikeChanged());
		assertFalse(applied.surfChanged());
		assertEquals(2, applied.cellsChanged());
		assertEquals(Set.of("A_MAP:2:2", "A_MAP:3:3"), state.active());
		assertTrue(state.bike());
		assertFalse(state.surf());

		StoryStateMessages.Applied same = StoryStateMessages.apply(state, StoryStateMessages.delta(null, null, false, true, List.of("B_CAVE")));
		assertFalse(same.bikeChanged(), "already on the bike");
		assertEquals(Set.of("B_CAVE"), state.lit(), "lit replaces the lit maps");
	}

	@Test
	void runningIsAFlagOfItsOwn() throws Exception {
		// FA8 item 7: player.state.run relayed; a full state without it is not running
		StoryState state = new StoryState();
		StoryStateMessages.Applied first = StoryStateMessages.apply(state, StoryStateMessages.full(List.of(), false, false, true, List.of()));
		assertTrue(state.run() && first.runChanged());
		JsonObject stop = StoryStateMessages.delta(null, null, null, null, false, null);
		assertEquals("{\"full\":false,\"run\":false}", stop.toString());
		assertTrue(StoryStateMessages.apply(state, stop).runChanged());
		assertFalse(state.run());
		StoryStateMessages.apply(state, StoryStateMessages.delta(null, null, null, null, true, null));
		StoryStateMessages.apply(state, StoryStateMessages.full(List.of(), false, false, List.of()));
		assertFalse(state.run(), "a full state without run: not running");
		assertThrows(StoryStateMessages.Invalid.class, () -> StoryStateMessages.apply(state,
				com.google.gson.JsonParser.parseString("{\"full\":false,\"run\":1}").getAsJsonObject()));
	}

	@Test
	void malformedMessagesChangeNothing() throws Exception {
		StoryState state = new StoryState();
		StoryStateMessages.apply(state, StoryStateMessages.full(List.of("A_MAP:1:1"), true, false, List.of()));
		String[] bad = {
			"{}",
			"{\"full\":\"yes\"}",
			"{\"full\":true,\"active\":[],\"surf\":false,\"bike\":false}",
			"{\"full\":true,\"active\":[\"nope\"],\"surf\":false,\"bike\":false,\"lit\":[]}",
			"{\"full\":true,\"active\":[\"A_MAP:2:2\",\"A_MAP:2:2\"],\"surf\":false,\"bike\":false,\"lit\":[]}",
			"{\"full\":false,\"add\":[\"A_MAP:2:2\"],\"remove\":[7]}",
			"{\"full\":false,\"surf\":1}",
			"{\"full\":false,\"lit\":[\"lower_case\"]}",
			"{\"full\":false,\"extra\":true}",
		};
		for (String json : bad) {
			assertThrows(StoryStateMessages.Invalid.class, () -> StoryStateMessages.apply(state, parse(json)), json);
			assertEquals(Set.of("A_MAP:1:1"), state.active(), json);
			assertTrue(state.surf(), json);
			assertFalse(state.bike(), json);
		}
	}

	@Test
	void problemsAreReportedAsCodes() throws Exception {
		StoryStateMessages.Invalid invalid = assertThrows(StoryStateMessages.Invalid.class,
				() -> StoryStateMessages.apply(new StoryState(), parse("{\"full\":false,\"add\":[\"SECRET TEXT\"]}")));
		assertEquals("cell_key", invalid.code());
		assertFalse(invalid.getMessage().contains("SECRET"), "never the offending value");
	}
}
