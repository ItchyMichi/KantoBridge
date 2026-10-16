package dev.kantobridge.client.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.ProtoJson;
import org.junit.jupiter.api.Test;

/** SE6: the hidden game's weather from player.state and the snapshot's state; gone with the link or a state without it. */
class ClientBridgeStateWeatherTest {
	private static final String STATE = "\"mode\":\"idle\",\"bike\":false,\"surf\":false,\"slope\":false,\"hidden\":false,\"dark\":false,\"lit\":false";

	private static Envelope message(String type, long seq, String json) throws Exception {
		return new Envelope(type, seq, null, ProtoJson.parseObject(json));
	}

	@Test
	void theWeatherFollowsTheState() throws Exception {
		Dispatcher dispatcher = new Dispatcher(Catalog.bundled());
		ClientBridgeState state = new ClientBridgeState();
		state.attach(dispatcher);
		dispatcher.stateChanged(ConnectionState.CONNECTED, "emerald");
		dispatcher.message(message("snapshot", 1, "{\"player\":{\"map\":\"EM_TEST_TOWN\",\"x\":1,\"y\":2,\"facing\":\"down\"},\"state\":{"
				+ STATE + ",\"weather\":\"sand\"}}"));
		dispatcher.drain(100);
		assertEquals("sand", state.weather());
		dispatcher.message(message("player.state", 2, "{" + STATE + ",\"weather\":\"downpour\"}"));
		dispatcher.drain(100);
		assertEquals("downpour", state.weather());
		// a whole state without weather: the game draws none (a Gen 1 or Gen 2 game, or Emerald's title screen)
		dispatcher.message(message("player.state", 3, "{" + STATE + "}"));
		dispatcher.drain(100);
		assertNull(state.weather());
		// a malformed name is no weather
		dispatcher.message(message("player.state", 4, "{" + STATE + ",\"weather\":\"Rain Storm!\"}"));
		dispatcher.drain(100);
		assertNull(state.weather());
		dispatcher.message(message("player.state", 5, "{" + STATE + ",\"weather\":\"fog\"}"));
		dispatcher.stateChanged(ConnectionState.DISCONNECTED, "eof");
		dispatcher.drain(100);
		assertNull(state.weather(), "forgotten when the link drops");
	}
}
