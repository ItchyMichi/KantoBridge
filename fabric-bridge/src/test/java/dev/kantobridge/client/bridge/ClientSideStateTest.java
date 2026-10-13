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
	void theHandshakeCarriesTheUnionRoomSetting() throws Exception {
		Dispatcher dispatcher = new Dispatcher(CATALOG);
		ClientBridgeState state = new ClientBridgeState();
		state.attach(dispatcher);
		dispatcher.stateChanged(ConnectionState.CONNECTED, "red");
		dispatcher.message(message("hello.ok", 1,
				"{\"protocol\":1,\"bridge\":\"0.1.0\",\"engine\":\"0.3.85\",\"game\":\"red\",\"session\":\"s-1\",\"union_room\":\"on\"}"));
		dispatcher.drain(100);
		HelloOk on = state.handshake();
		assertEquals(new HelloOk("s-1", "red", "0.3.85", "0.1.0", null, "on"), on);
		assertTrue(on.unionRoomOn());
		dispatcher.message(message("hello.ok", 2,
				"{\"protocol\":1,\"bridge\":\"0.1.0\",\"engine\":\"0.3.85\",\"game\":\"red\",\"session\":\"s-2\",\"union_room\":\"off\"}"));
		dispatcher.drain(100);
		HelloOk off = state.handshake();
		assertEquals(new HelloOk("s-2", "red", "0.3.85", "0.1.0", null, "off"), off);
		assertFalse(off.unionRoomOn());
		dispatcher.message(message("hello.ok", 3,
				"{\"protocol\":1,\"bridge\":\"0.1.0\",\"engine\":\"0.3.85\",\"game\":\"red\",\"session\":\"s-3\",\"union_room\":7}"));
		dispatcher.drain(100);
		HelloOk odd = state.handshake();
		assertEquals(new HelloOk("s-3", "red", "0.3.85", "0.1.0"), odd, "a value that isn't on or off is left out");
		assertFalse(odd.unionRoomOn());
	}

	@Test
	void theHandshakeCarriesTheFamilyAndTheLimits() throws Exception {
		// MG1: hello.ok.family and compat when the bridge says them, else the catalogue's for the game
		Dispatcher dispatcher = new Dispatcher(CATALOG);
		ClientBridgeState state = new ClientBridgeState();
		state.attach(dispatcher);
		dispatcher.stateChanged(ConnectionState.CONNECTED, "red");
		dispatcher.message(message("hello.ok", 1,
				"{\"protocol\":1,\"bridge\":\"0.1.0\",\"engine\":\"0.3.85\",\"game\":\"red\",\"session\":\"s-1\"}"));
		dispatcher.drain(100);
		HelloOk fromCatalogue = state.handshake();
		assertEquals("gen1", fromCatalogue.family());
		assertEquals(new dev.kantobridge.world.Games.Compat(151, 165, 10), fromCatalogue.compat());
		assertTrue(fromCatalogue.knownGame());
		dispatcher.message(message("hello.ok", 2, "{\"protocol\":1,\"bridge\":\"0.1.0\",\"engine\":\"0.3.85\",\"game\":\"emerald\","
				+ "\"family\":\"gen3\",\"compat\":{\"maxDex\":386,\"maxMove\":354,\"nick\":7},\"session\":\"s-2\"}"));
		dispatcher.drain(100);
		HelloOk said = state.handshake();
		assertEquals(new HelloOk("s-2", "emerald", "0.3.85", "0.1.0", null, null, "gen3", new dev.kantobridge.world.Games.Compat(386, 354, 7)), said);
		dispatcher.message(message("hello.ok", 3, "{\"protocol\":1,\"bridge\":\"0.1.0\",\"engine\":\"0.3.85\",\"game\":\"blue\","
				+ "\"family\":\"gen7\",\"compat\":{\"maxDex\":0,\"maxMove\":165,\"nick\":10},\"session\":\"s-3\"}"));
		dispatcher.drain(100);
		HelloOk odd = state.handshake();
		assertEquals(new HelloOk("s-3", "blue", "0.3.85", "0.1.0"), odd, "a family or limits that don't fit are the catalogue's");
		assertEquals("gen1", odd.family());
		dispatcher.message(message("hello.ok", 4,
				"{\"protocol\":1,\"bridge\":\"0.1.0\",\"engine\":\"0.3.85\",\"game\":\"nope\",\"session\":\"s-4\"}"));
		dispatcher.drain(100);
		HelloOk unknown = state.handshake();
		assertFalse(unknown.knownGame());
		assertNull(unknown.family());
		assertNull(unknown.compat());
	}

	@Test
	void stateFollowsSnapshotPositionsStateAndWarps() throws Exception {
		Dispatcher dispatcher = new Dispatcher(CATALOG);
		ClientBridgeState state = new ClientBridgeState();
		state.attach(dispatcher);
		assertFalse(state.hasPosition());
		dispatcher.stateChanged(ConnectionState.CONNECTED, "red");
		dispatcher.message(message("hello.ok", 1,
				"{\"protocol\":1,\"bridge\":\"0.1.0\",\"engine\":\"0.3.85\",\"game\":\"red\",\"session\":\"s-1\"}"));
		dispatcher.message(message("snapshot", 2, "{\"player\":{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":8,\"facing\":\"down\"},"
				+ "\"state\":{\"mode\":\"idle\",\"bike\":false,\"surf\":false,\"slope\":false,\"hidden\":false,\"dark\":false,\"lit\":false}}"));
		dispatcher.drain(100);
		assertEquals(new HelloOk("s-1", "red", "0.3.85", "0.1.0"), state.handshake());
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
	void theHiddenPlayerIsLiveOnlyInItsOverworld() throws Exception {
		Dispatcher dispatcher = new Dispatcher(CATALOG);
		ClientBridgeState state = new ClientBridgeState();
		state.attach(dispatcher);
		dispatcher.stateChanged(ConnectionState.CONNECTED, "red");
		dispatcher.message(message("snapshot", 1, "{\"ingame\":false,\"player\":{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":8,\"facing\":\"down\"}}"));
		dispatcher.drain(100);
		assertFalse(state.ingame(), "the title screen: the save's last position only");
		assertTrue(state.hasPosition());
		dispatcher.message(message("player.pos", 2, "{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":8,\"facing\":\"down\",\"kind\":\"sync\",\"frames\":0}"));
		dispatcher.drain(100);
		assertTrue(state.ingame(), "positions come only from the overworld");
		dispatcher.stateChanged(ConnectionState.DISCONNECTED, "eof");
		dispatcher.drain(100);
		assertFalse(state.ingame(), "nothing is live without the link");
		dispatcher.stateChanged(ConnectionState.CONNECTED, "red");
		dispatcher.message(message("snapshot", 1, "{\"ingame\":true,\"player\":{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":8,\"facing\":\"down\"}}"));
		dispatcher.drain(100);
		assertTrue(state.ingame());
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
	void shapesCanBeSwitchedOff(@TempDir Path dir) throws IOException {
		Path file = dir.resolve(ClientConfig.FILE_NAME);
		List<String> warnings = new ArrayList<>();
		assertTrue(ClientConfig.load(dir, new Properties(), warnings::add).shapes(), "on by default");
		assertTrue(Files.readString(file).contains("\"shapes\": \"on\""), "the new file names the setting");
		Files.writeString(file, "{\"port\": 27152, \"shapes\": \"off\"}", StandardCharsets.UTF_8);
		ClientConfig off = ClientConfig.load(dir, new Properties(), warnings::add);
		assertFalse(off.shapes());
		assertEquals(27152, off.port());
		Files.writeString(file, "{\"shapes\": false}", StandardCharsets.UTF_8);
		assertFalse(ClientConfig.load(dir, new Properties(), warnings::add).shapes());
		assertTrue(warnings.isEmpty());
		Files.writeString(file, "{\"shapes\": \"maybe\"}", StandardCharsets.UTF_8);
		assertTrue(ClientConfig.load(dir, new Properties(), warnings::add).shapes(), "a mistake keeps the default");
		assertEquals(1, warnings.size());
	}

	@Test
	void charactersHaveAStyleAndPacks(@TempDir Path dir) throws IOException {
		// The voxel plan's V8: unset by default (a pack's suggestion, else thick cards), packs on.
		Path file = dir.resolve(ClientConfig.FILE_NAME);
		List<String> warnings = new ArrayList<>();
		ClientConfig defaults = ClientConfig.load(dir, new Properties(), warnings::add);
		assertEquals(null, defaults.characterStyle());
		assertTrue(defaults.characterPacks());
		Files.writeString(file, "{\"characters\": {\"style\": \"carved\", \"packs\": false}}", StandardCharsets.UTF_8);
		ClientConfig carved = ClientConfig.load(dir, new Properties(), warnings::add);
		assertEquals(dev.kantobridge.character.StyleFallback.Style.CARVED, carved.characterStyle());
		assertFalse(carved.characterPacks());
		assertTrue(warnings.isEmpty());
		Files.writeString(file, "{\"characters\": {\"style\": \"model\"}}", StandardCharsets.UTF_8);
		assertEquals(null, ClientConfig.load(dir, new Properties(), warnings::add).characterStyle(), "a model is a pack's, not a setting");
		Files.writeString(file, "{\"characters\": {\"style\": \"voxel\"}}", StandardCharsets.UTF_8);
		assertEquals(null, ClientConfig.load(dir, new Properties(), warnings::add).characterStyle());
		Files.writeString(file, "{\"characters\": \"thick\"}", StandardCharsets.UTF_8);
		assertEquals(null, ClientConfig.load(dir, new Properties(), warnings::add).characterStyle());
		assertEquals(3, warnings.size(), "each mistake warned about, none fatal");
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

	@Test
	void linksListSeveralHiddenGames(@TempDir Path dir) throws IOException {
		// MG4: one link per hidden Gen1Recomp process; a link without a game learns it from hello.ok.
		Path file = dir.resolve(ClientConfig.FILE_NAME);
		List<String> warnings = new ArrayList<>();
		Files.writeString(file, "{\"links\": [{\"game\": \"blue\", \"port\": 27151}, {\"port\": 27152}], \"shapes\": \"off\"}",
				StandardCharsets.UTF_8);
		ClientConfig config = ClientConfig.load(dir, new Properties(), warnings::add);
		assertEquals(List.of(new ClientConfig.Link("blue", 27151), ClientConfig.Link.legacy(27152)), config.links());
		assertEquals(27151, config.port(), "the first link is the one legacy callers mean");
		assertFalse(config.shapes());
		assertTrue(warnings.isEmpty());
		// Without links the file is what it always was: one legacy link on port.
		Files.writeString(file, "{\"port\": 27153}", StandardCharsets.UTF_8);
		assertEquals(List.of(ClientConfig.Link.legacy(27153)), ClientConfig.load(dir, new Properties(), warnings::add).links());
		assertTrue(warnings.isEmpty());
		assertEquals(List.of(ClientConfig.Link.legacy(27151)), ClientConfig.DEFAULTS.links());
		assertThrows(IllegalArgumentException.class, () -> new ClientConfig.Link("Blue", 27151), "a game id is lower-case");
		assertThrows(IllegalArgumentException.class, () -> new ClientConfig.Link("blue", 0));
	}

	@Test
	void linksSkipMistakesAndNeverRepeatAPortOrAGame(@TempDir Path dir) throws IOException {
		Path file = dir.resolve(ClientConfig.FILE_NAME);
		List<String> warnings = new ArrayList<>();
		Files.writeString(file, "{\"port\": 27150, \"links\": [{\"game\": \"blue\", \"port\": 27151}, {\"game\": \"blue\", \"port\": 27153},"
				+ " {\"port\": 27151}, {\"game\": \"nope\", \"port\": 27154}, {\"port\": 99999}, \"x\", {\"game\": \"red\", \"port\": 27155}]}",
				StandardCharsets.UTF_8);
		ClientConfig config = ClientConfig.load(dir, new Properties(), warnings::add);
		assertEquals(List.of(new ClientConfig.Link("blue", 27151), new ClientConfig.Link("red", 27155)), config.links());
		assertEquals(6, warnings.size(), "both keys, the repeated game, the repeated port, the unknown game, the bad port, the non-object");
		warnings.clear();
		Files.writeString(file, "{\"port\": 27152, \"links\": []}", StandardCharsets.UTF_8);
		assertEquals(List.of(ClientConfig.Link.legacy(27152)), ClientConfig.load(dir, new Properties(), warnings::add).links(),
				"no usable link: the port");
		Files.writeString(file, "{\"links\": \"blue\"}", StandardCharsets.UTF_8);
		assertEquals(List.of(ClientConfig.Link.legacy(27151)), ClientConfig.load(dir, new Properties(), warnings::add).links());
		assertEquals(3, warnings.size());
	}

	@Test
	void theLinksPropertyReplacesTheFilesLinks(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve(ClientConfig.FILE_NAME), "{\"port\": 27152}", StandardCharsets.UTF_8);
		Properties properties = new Properties();
		properties.setProperty(ClientConfig.LINKS_PROPERTY, "blue:27161, 27162");
		properties.setProperty(ClientConfig.PORT_PROPERTY, "27170");
		List<String> warnings = new ArrayList<>();
		ClientConfig config = ClientConfig.load(dir, properties, warnings::add);
		assertEquals(List.of(new ClientConfig.Link("blue", 27161), ClientConfig.Link.legacy(27162)), config.links(), "links wins over port");
		assertTrue(warnings.isEmpty());
		properties.setProperty(ClientConfig.LINKS_PROPERTY, "nope:27163, blue:x");
		assertEquals(List.of(ClientConfig.Link.legacy(27152)), ClientConfig.load(dir, properties, warnings::add).links(),
				"nothing usable: the file's links");
		assertEquals(3, warnings.size(), "each entry, then the property as a whole");
		assertEquals(List.of(new ClientConfig.Link("red", 27151), new ClientConfig.Link("blue", 27152)),
				ClientConfig.parseLinks("red:27151,blue:27152", warnings::add));
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
