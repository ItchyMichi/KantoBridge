package dev.kantobridge.client.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link Links} over in-memory pipes (MG4): one real {@link BridgeClient} per link, the test playing each gen1-bridge
 * through its {@link PipeTransport}, the world's regions and the Minecraft server stubbed. The body is what the
 * {@link FollowedDispatcher} tells its state handlers.
 */
class LinksTest {
	static final Catalog CATALOG = Catalog.bundled();
	static final HelloInfo HELLO = new HelloInfo("0.1.0", "1.21.1", UUID.fromString("0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b"), "Tester");
	private static final int BLUE_PORT = 27151;
	private static final int RED_PORT = 27152;

	private final Map<Integer, PipeTransport> transports = new ConcurrentHashMap<>();
	private final BridgeClientTest.RecordingClock clock = new BridgeClientTest.RecordingClock();
	private final FakeRegions regions = new FakeRegions();
	private final FakeServer server = new FakeServer();
	private final List<String> bodyStates = new CopyOnWriteArrayList<>();
	private final List<String> follows = new CopyOnWriteArrayList<>();
	private @Nullable Links links;

	/** The world's regions as the test says: each game's region, the legacy one, whether this is a region world. */
	static final class FakeRegions implements Links.RegionView {
		final Map<String, String> byGame = new HashMap<>();
		@Nullable String legacy;
		boolean regionWorld;
		/** Every binding of the followed region, "-" for none. */
		final List<String> bound = new ArrayList<>();

		@Override
		public @Nullable String regionOf(@Nullable String game) {
			return game == null ? null : this.byGame.get(game);
		}

		@Override
		public @Nullable String legacy() {
			return this.legacy;
		}

		@Override
		public boolean regionWorld() {
			return this.regionWorld;
		}

		@Override
		public void followed(@Nullable String region) {
			this.bound.add(region == null ? "-" : region);
		}
	}

	/** Records what goes up to the Minecraft server. */
	static final class FakeServer implements Links.ServerPort {
		final List<String> posts = new ArrayList<>();
		boolean canSend = true;
		private long seq;

		@Override
		public long post(String type, JsonObject data, @Nullable Long re, @Nullable String region) {
			if (!this.canSend) {
				return -1;
			}
			this.posts.add(type + " " + (region == null ? "-" : region) + " " + data);
			return ++this.seq;
		}

		@Override
		public boolean canSend() {
			return this.canSend;
		}
	}

	@AfterEach
	void tearDown() {
		if (this.links != null) {
			this.links.closeAll();
		}
	}

	/** Builds and starts the links; each gets a pipe transport of its own, found by port. */
	private Links start(ClientConfig.Link... configured) {
		Links started = new Links(CATALOG, List.of(configured), (config, listener) -> new BridgeClient(
				config.withHeartbeat(60_000, 120_000).withTick(10), CATALOG, () -> HELLO,
				this.transports.computeIfAbsent(config.port(), port -> new PipeTransport()), this.clock, listener), this.clock, this.regions,
				this.server);
		started.body().onState((state, detail) -> this.bodyStates.add(state + (detail == null ? "" : ":" + detail)));
		started.onFollow((link, teleport) -> this.follows.add(link.game() + (teleport ? " teleport" : " step")));
		this.links = started;
		started.start();
		return started;
	}

	private BridgeLink linkOn(int port) {
		for (BridgeLink link : this.links.all()) {
			if (link.port() == port) {
				return link;
			}
		}
		throw new AssertionError("no link on " + port);
	}

	/** Plays gen1-bridge for the link on {@code port}: accepts its connection and answers its hello as {@code game}. */
	private PipeTransport.Pipe handshake(int port, String game, String session) throws Exception {
		PipeTransport.Pipe pipe = this.transports.get(port).nextPipe();
		Envelope hello = Envelope.parse(pipe.serverReadLine());
		assertEquals("hello", hello.type());
		pipe.serverSend(helloOk(hello.seq(), game, session));
		BridgeLink link = this.linkOn(port);
		// The socket thread queues the CONNECTED state and then the hello.ok message; draining until the link's state holds
		// the handshake means both were heard (connected() alone is true a moment before the events are queued).
		BridgeClientTest.await("handshake on " + port, () -> {
			this.links.drain(64);
			return link.state().handshake() != null;
		});
		return pipe;
	}

	private static String helloOk(long re, String game, String session) {
		JsonObject data = new JsonObject();
		data.addProperty("protocol", 1);
		data.addProperty("bridge", "0.1.0");
		data.addProperty("engine", "0.3.85");
		data.addProperty("game", game);
		data.addProperty("session", session);
		return new Envelope("hello.ok", 1, re, data).toJson();
	}

	private static Envelope fromServer(String type, @Nullable String region) {
		JsonObject data = new JsonObject();
		if (region != null) {
			data.addProperty("region", region);
		}
		return new Envelope(type, 7, null, data);
	}

	/** Two configured links: Blue's region is the legacy Kanto, Red's another, in a region world. */
	private Links twoLinks() {
		this.regions.byGame.put("blue", "kanto");
		this.regions.byGame.put("red", "kanto_red");
		this.regions.legacy = "kanto";
		this.regions.regionWorld = true;
		return this.start(new ClientConfig.Link("blue", BLUE_PORT), new ClientConfig.Link("red", RED_PORT));
	}

	@Test
	void oneLegacyLinkIsFollowedFromTheStartAndLearnsItsGame() throws Exception {
		// The regression gate: one `port` setting behaves as before MG4 (the body hears it before it connects).
		this.regions.byGame.put("blue", "kanto");
		this.regions.legacy = "kanto";
		Links links = this.start(ClientConfig.Link.legacy(BLUE_PORT));
		BridgeLink link = links.followed();
		assertNull(link.configuredGame());
		assertNull(link.game());
		assertTrue(links.parked().isEmpty());
		this.handshake(BLUE_PORT, "blue", "s-1");
		assertEquals("blue", link.game());
		assertFalse(link.wrongGame());
		assertEquals("CONNECTED:blue", this.bodyStates.get(this.bodyStates.size() - 1), "the body hears its one link");
		assertSame(link, links.byGame("blue"));
		assertSame(link, links.forRegion("kanto"));
		assertSame(link, links.legacy());
		assertEquals("kanto", link.region());
		links.tick();
		assertEquals(List.of("kanto"), this.regions.bound, "the followed region follows the link");
		assertEquals(List.of(), this.server.posts, "a legacy world sends no region.state");
		assertEquals(List.of(), this.follows);
		assertNull(link.hudLine(), "nothing to say about a connected link");
	}

	@Test
	void theFirstGoodLinkToConnectIsFollowedAndTheOthersPark() throws Exception {
		Links links = this.twoLinks();
		BridgeLink blue = this.linkOn(BLUE_PORT);
		BridgeLink red = this.linkOn(RED_PORT);
		assertSame(blue, links.followed(), "the first configured link until one connects");
		PipeTransport.Pipe redPipe = this.handshake(RED_PORT, "red", "s-red");
		assertSame(red, links.followed(), "Red connected first");
		assertEquals(List.of(blue), links.parked());
		assertEquals(List.of("red step"), this.follows);
		assertTrue(this.bodyStates.contains("DISCONNECTED:swap"));
		assertEquals("CONNECTED:red", this.bodyStates.get(this.bodyStates.size() - 1));
		assertEquals("resync", Envelope.parse(redPipe.serverReadLine()).type(), "the new link is asked for a snapshot");
		this.handshake(BLUE_PORT, "blue", "s-blue");
		assertSame(red, links.followed(), "a link connecting later parks");
		assertEquals(List.of("red step"), this.follows);
		assertEquals("CONNECTED:red", this.bodyStates.get(this.bodyStates.size() - 1), "the body heard nothing of Blue");
		assertNull(blue.hudLine(), "a connected parked link needs no line");
		assertNull(red.hudLine());
	}

	@Test
	void followSwapsTheBodyAndAsksTheNewLinkForASnapshot() throws Exception {
		Links links = this.twoLinks();
		PipeTransport.Pipe bluePipe = this.handshake(BLUE_PORT, "blue", "s-blue");
		PipeTransport.Pipe redPipe = this.handshake(RED_PORT, "red", "s-red");
		BridgeLink blue = this.linkOn(BLUE_PORT);
		BridgeLink red = this.linkOn(RED_PORT);
		assertSame(blue, links.followed());
		this.bodyStates.clear();
		assertTrue(links.follow("red", false));
		assertSame(red, links.followed());
		assertEquals(List.of(blue), links.parked());
		assertEquals(List.of("DISCONNECTED:swap", "CONNECTED:red"), this.bodyStates);
		assertEquals(List.of("red step"), this.follows);
		assertEquals("resync", Envelope.parse(redPipe.serverReadLine()).type());
		assertEquals("kanto_red", this.regions.bound.get(this.regions.bound.size() - 1));
		assertTrue(links.follow("red", true), "following the followed link is a quiet yes");
		assertEquals(2, this.bodyStates.size());
		assertEquals(1, links.follows());
		assertTrue(links.follow("blue", true));
		assertEquals(List.of("red step", "blue teleport"), this.follows);
		assertEquals("resync", Envelope.parse(bluePipe.serverReadLine()).type());
		assertFalse(links.follow("yellow", false), "no such link");
		assertEquals(1L, links.warnings().get("links.follow_unknown"));
	}

	@Test
	void aParkedLinksDropIsOneHudLineNotAPause() throws Exception {
		Links links = this.twoLinks();
		this.handshake(BLUE_PORT, "blue", "s-blue");
		PipeTransport.Pipe redPipe = this.handshake(RED_PORT, "red", "s-red");
		BridgeLink red = this.linkOn(RED_PORT);
		assertNull(red.hudLine());
		this.bodyStates.clear();
		redPipe.serverHangUp();
		BridgeClientTest.await("red down", () -> !red.connected());
		links.drain(64);
		assertEquals(List.of(), this.bodyStates, "the body (Blue) heard nothing");
		assertSame(this.linkOn(BLUE_PORT), links.followed());
		ConnectionStatus.Line line = red.hudLine();
		assertTrue(line != null && line.warning());
		assertEquals("kantobridge.hud.link.parked_down", line.key());
		assertEquals(Integer.toString(RED_PORT), line.args().get(1));
		assertFalse(links.follow("red", false), "a link that is down can't be followed");
		assertEquals(1L, links.warnings().get("links.follow_down"));
		assertFalse(links.presence().getAsJsonArray("parked").contains(new JsonPrimitive("kanto_red")), "a parked link that is down isn't present");
	}

	@Test
	void aLinkRunningAnotherGameIsNeverFollowed() throws Exception {
		this.regions.byGame.put("blue", "kanto");
		this.regions.byGame.put("red", "kanto_red");
		this.regions.legacy = "kanto";
		this.regions.regionWorld = true;
		Links links = this.start(new ClientConfig.Link("blue", BLUE_PORT), new ClientConfig.Link("red", RED_PORT));
		// The process on Blue's port runs Red.
		this.handshake(BLUE_PORT, "red", "s-1");
		BridgeLink wrong = this.linkOn(BLUE_PORT);
		assertTrue(wrong.wrongGame());
		assertEquals("red", wrong.wrongGameSeen());
		assertEquals("blue", wrong.game(), "the configured game stays the link's name");
		assertNull(wrong.region(), "a wrong link is no region's");
		assertNull(links.forRegion("kanto"));
		assertFalse(links.follow("blue", false));
		assertEquals(1L, links.warnings().get("links.follow_wrong_game"));
		ConnectionStatus.Line line = wrong.hudLine();
		assertEquals("kantobridge.hud.link.wrong_game", line.key());
		assertEquals(Integer.toString(BLUE_PORT), line.args().get(0));
		assertTrue(line.warning());
		assertFalse(links.presence().has("followed"), "no followed region while the only connected link is wrong");
		// The real Red connects: it is followed although it connected second.
		this.handshake(RED_PORT, "red", "s-2");
		assertSame(this.linkOn(RED_PORT), links.followed());
		assertEquals(List.of("red step"), this.follows);
		assertEquals("kanto_red", links.presence().get("followed").getAsString());
	}

	@Test
	void serverMessagesGoToTheirRegionsLink() throws Exception {
		Links links = this.twoLinks();
		this.handshake(BLUE_PORT, "blue", "s-blue");
		this.handshake(RED_PORT, "red", "s-red");
		List<String> routed = new ArrayList<>();
		Links.ServerHandler handler = (link, message) -> routed.add(link.game());
		links.route(fromServer("party.sync", "kanto_red"), true, handler);
		assertEquals(List.of("red"), routed, "tagged: that region's link");
		routed.clear();
		links.route(fromServer("battle.result", null), false, handler);
		assertEquals(List.of("blue"), routed, "untagged: the legacy region's link");
		routed.clear();
		links.route(fromServer("party.sync", null), true, handler);
		assertEquals(List.of("blue", "red"), routed, "an untagged sync fans out");
		routed.clear();
		links.route(fromServer("party.sync", "hoenn"), true, handler);
		assertEquals(List.of("blue", "red"), routed, "a region no link runs: as untagged, counted");
		assertEquals(1L, links.warnings().get("links.no_link_for_region"));
		routed.clear();
		this.regions.legacy = null;
		links.follow("red", false);
		links.route(fromServer("battle.result", null), false, handler);
		assertEquals(List.of("red"), routed, "no legacy region: the followed link");
	}

	@Test
	void presenceGoesUpInARegionWorldWhenItChanges() throws Exception {
		Links links = this.twoLinks();
		links.tick();
		assertEquals(List.of("region.state - {\"parked\":[]}"), this.server.posts, "nothing connected: no followed region");
		this.handshake(BLUE_PORT, "blue", "s-blue");
		links.tick();
		assertEquals("region.state - {\"followed\":\"kanto\",\"parked\":[]}", this.server.posts.get(1));
		links.tick();
		assertEquals(2, this.server.posts.size(), "unchanged: nothing more");
		this.handshake(RED_PORT, "red", "s-red");
		links.tick();
		assertEquals("region.state - {\"followed\":\"kanto\",\"parked\":[\"kanto_red\"]}", this.server.posts.get(2));
		links.follow("red", false);
		links.tick();
		assertEquals("region.state - {\"followed\":\"kanto_red\",\"parked\":[\"kanto\"]}", this.server.posts.get(3));
		this.server.canSend = false;
		links.serverChanged();
		links.tick();
		assertEquals(4, this.server.posts.size(), "no server: nothing");
		this.server.canSend = true;
		links.tick();
		assertEquals(5, this.server.posts.size(), "a new server hears the presence again");
		this.regions.regionWorld = false;
		links.serverChanged();
		links.tick();
		assertEquals(5, this.server.posts.size(), "a legacy world sends today's bytes only");
	}

	@Test
	void theServersStoredPresencePicksTheFollowedLink() throws Exception {
		Links links = this.twoLinks();
		this.handshake(BLUE_PORT, "blue", "s-blue");
		this.handshake(RED_PORT, "red", "s-red");
		assertSame(this.linkOn(BLUE_PORT), links.followed());
		JsonObject stored = new JsonObject();
		stored.addProperty("followed", "kanto_red");
		links.serverPresence(stored);
		assertSame(this.linkOn(RED_PORT), links.followed(), "the server's presence names the region to follow");
		assertEquals(List.of("red step"), this.follows);
		// Named before its link is up: followed as soon as it connects, over the first-connected rule.
		this.links.closeAll();
		this.transports.clear();
		this.follows.clear();
		Links second = this.twoLinks();
		JsonObject wish = new JsonObject();
		wish.addProperty("followed", "kanto_red");
		second.serverPresence(wish);
		this.handshake(BLUE_PORT, "blue", "s-blue2");
		assertSame(this.linkOn(BLUE_PORT), second.followed(), "Blue is up first");
		this.handshake(RED_PORT, "red", "s-red2");
		assertSame(this.linkOn(RED_PORT), second.followed(), "Red takes over as the wished-for region's link");
		assertEquals(List.of("red step"), this.follows);
		JsonObject empty = new JsonObject();
		second.serverPresence(empty);
		assertSame(this.linkOn(RED_PORT), second.followed(), "no followed region: nothing changes");
	}
}
