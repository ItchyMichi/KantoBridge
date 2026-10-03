package dev.kantobridge.client.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.ProtoJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Dispatcher, the client-thread state, the HUD status line and the config file. */
class ClientSideStateTest {
	static final Catalog CATALOG = Catalog.bundled();

	static Envelope message(String type, long seq, String json) throws Exception {
		return new Envelope(type, seq, null, ProtoJson.parseObject(json));
	}

	@Test
	void dispatcherRunsHandlersInOrderOnTheDrainingThread() throws Exception {
		Dispatcher dispatcher = new Dispatcher(CATALOG);
		List<String> seen = new ArrayList<>();
		dispatcher.on("player.pos", m -> seen.add("pos#" + m.seq()));
		dispatcher.on("player.pos", m -> seen.add("pos2#" + m.seq()));
		dispatcher.onState((state, detail) -> seen.add("state:" + state));
		dispatcher.onState((state, detail) -> {
			throw new IllegalStateException("a broken handler must not stall the queue");
		});
		Thread poster = new Thread(() -> {
			dispatcher.stateChanged(ConnectionState.CONNECTED, "blue");
			for (int i = 1; i <= 3; i++) {
				try {
					dispatcher.message(message("player.pos", i, "{}"));
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		});
		poster.start();
		poster.join();
		assertTrue(seen.isEmpty(), "nothing runs until the client thread drains");
		assertEquals(4, dispatcher.pending());
		assertEquals(2, dispatcher.drain(2));
		assertEquals(2, dispatcher.drain(100));
		assertEquals(List.of("state:CONNECTED", "pos#1", "pos2#1", "pos#2", "pos2#2", "pos#3", "pos2#3"), seen);
	}

	@Test
	void dispatcherOnlyAcceptsGen1ToClientTypes() {
		Dispatcher dispatcher = new Dispatcher(CATALOG);
		assertThrows(IllegalArgumentException.class, () -> dispatcher.on("move.step", m -> {
		}));
		assertThrows(IllegalArgumentException.class, () -> dispatcher.on("no.such", m -> {
		}));
	}

	@Test
	void stateFollowsSnapshotPositionsStateAndWarps() throws Exception {
		Dispatcher dispatcher = new Dispatcher(CATALOG);
		ClientBridgeState state = new ClientBridgeState();
		state.attach(dispatcher);
		assertFalse(state.hasPosition());
		dispatcher.stateChanged(ConnectionState.CONNECTED, "red");
		dispatcher.message(message("hello.ok", 1,
				"{\"protocol\":1,\"bridge\":\"0.1.0\",\"engine\":\"0.3.49\",\"game\":\"red\",\"session\":\"s-1\"}"));
		dispatcher.message(message("snapshot", 2, "{\"player\":{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":8,\"facing\":\"down\"},"
				+ "\"state\":{\"mode\":\"idle\",\"bike\":false,\"surf\":false,\"slope\":false,\"hidden\":false,\"dark\":false,\"lit\":false}}"));
		dispatcher.drain(100);
		assertEquals(new HelloOk("s-1", "red", "0.3.49", "0.1.0"), state.handshake());
		assertEquals("TEST_TOWN", state.map());
		assertEquals(5, state.x());
		assertEquals(8, state.y());
		assertEquals("down", state.facing());
		assertEquals("sync", state.moveKind());
		assertEquals("idle", state.mode());

		dispatcher.message(message("player.pos", 3, "{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":7,\"facing\":\"up\",\"kind\":\"walk\",\"frames\":16}"));
		dispatcher.message(message("player.state", 4,
				"{\"mode\":\"cutscene\",\"bike\":true,\"surf\":false,\"slope\":false,\"hidden\":false,\"dark\":true,\"lit\":false}"));
		dispatcher.message(message("warp", 5, "{\"phase\":\"arrive\",\"kind\":\"door\",\"to\":{\"map\":\"TEST_HOUSE\",\"x\":3,\"y\":6,\"facing\":\"up\"}}"));
		dispatcher.drain(100);
		assertEquals("TEST_HOUSE", state.map());
		assertEquals(3, state.x());
		assertEquals(6, state.y());
		assertEquals("cutscene", state.mode());
		assertTrue(state.bike());
		assertTrue(state.dark());
		assertEquals("arrive", state.warpPhase());
		assertEquals("door", state.warpKind());
		assertEquals(1, state.snapshots());
		assertEquals(1, state.positions());

		// Fields of the wrong type are ignored rather than crashing the client thread.
		dispatcher.message(message("player.pos", 6, "{\"map\":7,\"x\":\"nine\"}"));
		dispatcher.stateChanged(ConnectionState.DISCONNECTED, "eof");
		dispatcher.drain(100);
		assertEquals("TEST_HOUSE", state.map());
		assertNull(state.handshake(), "the handshake is forgotten when the link drops");
	}

	@Test
	void statusLineRemembersFailuresUntilTheNextHandshake() {
		ConnectionStatus status = new ConnectionStatus();
		assertEquals("kantobridge.hud.connecting", status.line(0).key());
		status.update(ConnectionState.CONNECTING, null, 0);
		assertEquals("kantobridge.hud.connecting", status.line(0).key());
		status.update(ConnectionState.DISCONNECTED, "unreachable", 10);
		status.update(ConnectionState.CONNECTING, null, 20);
		ConnectionStatus.Line line = status.line(20);
		assertEquals("kantobridge.hud.disconnected", line.key(), "no flicker back to connecting");
		assertTrue(line.warning());

		status.update(ConnectionState.REJECTED, "protocol", 30);
		status.update(ConnectionState.CONNECTING, null, 40);
		assertEquals("kantobridge.hud.rejected", status.line(40).key());
		assertEquals(List.of("protocol"), status.line(40).args());

		status.update(ConnectionState.CONNECTED, "blue", 100);
		assertEquals("kantobridge.hud.connected", status.line(100).key());
		assertEquals(List.of("blue"), status.line(100).args());
		assertNull(status.line(100 + ConnectionStatus.SHOW_CONNECTED_MS), "connected fades out");
		status.update(ConnectionState.DISCONNECTED, "eof", 9000);
		assertEquals("kantobridge.hud.disconnected", status.line(9000).key());
	}

	@Test
	void configIsCreatedWithDefaults(@TempDir Path dir) throws IOException {
		List<String> warnings = new ArrayList<>();
		ClientConfig config = ClientConfig.load(dir, new Properties(), warnings::add);
		assertEquals(27151, config.port());
		assertTrue(warnings.isEmpty());
		JsonObject written = ProtoJsonUnchecked.parse(Files.readString(dir.resolve(ClientConfig.FILE_NAME)));
		assertEquals(27151, written.get("port").getAsInt());
	}

	@Test
	void configReadsThePortAndFallsBackOnMistakes(@TempDir Path dir) throws IOException {
		Path file = dir.resolve(ClientConfig.FILE_NAME);
		List<String> warnings = new ArrayList<>();
		Files.writeString(file, "{\"port\": 27152}", StandardCharsets.UTF_8);
		assertEquals(27152, ClientConfig.load(dir, new Properties(), warnings::add).port());
		assertTrue(warnings.isEmpty());

		Files.writeString(file, "{\"port\": 27152,}", StandardCharsets.UTF_8);
		assertEquals(27151, ClientConfig.load(dir, new Properties(), warnings::add).port());
		Files.writeString(file, "{\"port\": 70000}", StandardCharsets.UTF_8);
		assertEquals(27151, ClientConfig.load(dir, new Properties(), warnings::add).port());
		Files.writeString(file, "{\"port\": \"27152\"}", StandardCharsets.UTF_8);
		assertEquals(27151, ClientConfig.load(dir, new Properties(), warnings::add).port());
		assertEquals(3, warnings.size());
	}

	@Test
	void systemPropertyOverridesTheFile(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve(ClientConfig.FILE_NAME), "{\"port\": 27152}", StandardCharsets.UTF_8);
		Properties properties = new Properties();
		properties.setProperty(ClientConfig.PORT_PROPERTY, "27160");
		List<String> warnings = new ArrayList<>();
		assertEquals(27160, ClientConfig.load(dir, properties, warnings::add).port());
		properties.setProperty(ClientConfig.PORT_PROPERTY, "nope");
		assertEquals(27152, ClientConfig.load(dir, properties, warnings::add).port());
		assertEquals(1, warnings.size());
	}

	/** ProtoJson.parseObject without the checked exception, for test setup. */
	static final class ProtoJsonUnchecked {
		static JsonObject parse(String text) {
			try {
				return ProtoJson.parseObject(text);
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		}
	}
}
