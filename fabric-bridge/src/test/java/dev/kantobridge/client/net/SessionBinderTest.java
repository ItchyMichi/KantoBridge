package dev.kantobridge.client.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.proto.ProtoJson;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The save pairing on the client: when session.bind goes to gen1-bridge, and what session.bound does. */
class SessionBinderTest {
	private static final String WORLD = "0f8e9a1c-2b3d-4e5f-8a9b-0c1d2e3f4a5b";
	private static final String OTHER = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d";

	private record Sent(String type, JsonObject data) {
	}

	private static final class Links implements SessionBinder.Port {
		final List<Sent> bridge = new ArrayList<>();
		final List<Sent> server = new ArrayList<>();
		final List<String> warnings = new ArrayList<>();
		boolean bridgeUp = true;
		long seq;

		@Override
		public long toBridge(String type, JsonObject data) {
			if (!this.bridgeUp) {
				return -1;
			}
			this.bridge.add(new Sent(type, data.deepCopy()));
			return ++this.seq;
		}

		@Override
		public boolean toServer(String type, JsonObject data) {
			this.server.add(new Sent(type, data.deepCopy()));
			return true;
		}

		@Override
		public void warn(String code, long count) {
			this.warnings.add(code);
		}
	}

	private final Links links = new Links();

	private static JsonObject bound(int slot) throws Exception {
		return ProtoJson.parseObject("{\"slot\":" + slot + "}");
	}

	private static void assertBind(Sent sent, String world, boolean multiplayer) {
		assertEquals(MsgTypes.SESSION_BIND, sent.type());
		assertEquals(world, sent.data().get("world").getAsString());
		assertEquals(multiplayer, sent.data().get("multiplayer").getAsBoolean());
		assertEquals(2, sent.data().size());
	}

	@Test
	void theBindCarriesTheRegionsChoiceForForeignMembers() {
		// FP1 (FOREIGN_POKEMON_PLAN §4.15): the region record's onIncompatible rides the bind; a change binds again
		SessionBinder binder = new SessionBinder(this.links, false);
		binder.linkUp();
		binder.world(WORLD, false);
		binder.tick();
		assertBind(this.links.bridge.get(0), WORLD, false);
		binder.incompatible("leave_out");
		binder.tick();
		binder.tick();
		assertEquals(2, this.links.bridge.size(), "the region's choice arrived: one bind more");
		assertEquals("leave_out", this.links.bridge.get(1).data().get("incompatible").getAsString());
		binder.incompatible("visitor");
		binder.tick();
		assertEquals("visitor", this.links.bridge.get(2).data().get("incompatible").getAsString());
		binder.incompatible("drop");
		binder.tick();
		assertFalse(this.links.bridge.get(3).data().has("incompatible"), "an unknown value is not sent");
		binder.incompatible(null);
		binder.tick();
		assertEquals(4, this.links.bridge.size(), "nothing changed");
	}

	@Test
	void aWorldIsBoundOncePerLink() {
		SessionBinder binder = new SessionBinder(this.links, false);
		binder.tick();
		binder.world(WORLD, false);
		binder.tick();
		assertTrue(this.links.bridge.isEmpty(), "no link to gen1-bridge yet");
		binder.linkUp();
		binder.tick();
		binder.tick();
		binder.world(WORLD, false);
		binder.tick();
		assertEquals(1, this.links.bridge.size(), "one bind per link and world");
		assertBind(this.links.bridge.get(0), WORLD, false);
		assertTrue(binder.bound());
		// A reconnect binds again.
		binder.linkDown();
		binder.tick();
		assertFalse(binder.bound());
		binder.linkUp();
		binder.tick();
		assertEquals(2, this.links.bridge.size());
		assertBind(this.links.bridge.get(1), WORLD, false);
		assertEquals(2, binder.stats().binds);
	}

	@Test
	void theSameWorldReopenedOnALiveLinkIsBoundAgain() {
		// WORLD_SESSIONS_PLAN WS0's scene A: "Save and Quit", then the same world opened again while gen1-bridge stayed
		// connected. Before WS1 no bind went out, the gate waited for its answer and held the player for good.
		SessionBinder binder = new SessionBinder(this.links, false);
		binder.linkUp();
		binder.world(WORLD, false);
		binder.tick();
		assertEquals(1, this.links.bridge.size());
		binder.leftWorld();
		binder.tick();
		assertFalse(binder.bound());
		assertNull(binder.slot());
		binder.world(WORLD, false);
		binder.tick();
		binder.tick();
		assertEquals(2, this.links.bridge.size(), "the reopened world is bound again, once");
		assertBind(this.links.bridge.get(1), WORLD, false);
		assertTrue(binder.bound());
	}

	@Test
	void anotherWorldOrViewBindsAgainAndNoWorldBindsNothing() {
		SessionBinder binder = new SessionBinder(this.links, false);
		binder.linkUp();
		binder.world(WORLD, false);
		binder.tick();
		binder.world(null, false);
		binder.tick();
		assertNull(binder.world());
		binder.world(OTHER, true);
		binder.tick();
		binder.world(OTHER, false);
		binder.tick();
		assertEquals(3, this.links.bridge.size());
		assertBind(this.links.bridge.get(1), OTHER, true);
		assertBind(this.links.bridge.get(2), OTHER, false);
	}

	@Test
	void aBindThatCannotBeSentIsTriedAgain() {
		SessionBinder binder = new SessionBinder(this.links, false);
		binder.linkUp();
		binder.world(WORLD, true);
		this.links.bridgeUp = false;
		binder.tick();
		assertFalse(binder.bound());
		this.links.bridgeUp = true;
		binder.tick();
		assertEquals(1, this.links.bridge.size());
		assertTrue(binder.bound());
	}

	@Test
	void theBoundSlotIsKeptAndRelayedOnlyWithTheC2sLeg() throws Exception {
		SessionBinder quiet = new SessionBinder(this.links, false);
		quiet.linkUp();
		quiet.world(WORLD, true);
		quiet.tick();
		quiet.onBound(bound(3));
		assertEquals(3, quiet.slot());
		assertTrue(this.links.server.isEmpty(), "session.bound has no c2s leg here");

		Links upLinks = new Links();
		SessionBinder up = new SessionBinder(upLinks, true);
		up.linkUp();
		up.world(WORLD, true);
		up.tick();
		up.onBound(bound(4));
		assertEquals(1, upLinks.server.size());
		assertEquals(MsgTypes.SESSION_BOUND, upLinks.server.get(0).type());
		assertEquals("{\"slot\":4}", ProtoJson.write(upLinks.server.get(0).data()));
		assertEquals(1, up.stats().boundsRelayed);
		// A new link forgets the slot until gen1-bridge answers again.
		up.linkUp();
		assertNull(up.slot());
	}

	@Test
	void strayOrMalformedAnswersAreDropped() throws Exception {
		SessionBinder binder = new SessionBinder(this.links, true);
		binder.linkUp();
		binder.onBound(bound(1));
		assertNull(binder.slot(), "no bind was sent on this link");
		binder.world(WORLD, false);
		binder.tick();
		binder.onBound(ProtoJson.parseObject("{\"slot\":256}"));
		binder.onBound(ProtoJson.parseObject("{\"slot\":\"2\"}"));
		binder.onBound(ProtoJson.parseObject("{\"slot\":2,\"extra\":1}"));
		assertNull(binder.slot());
		assertTrue(this.links.server.isEmpty());
		assertEquals(List.of("session.stray_bound", "session.bad_bound", "session.bad_bound", "session.bad_bound"), this.links.warnings);
	}

	@Test
	void switchedIsKeptForTheGateAndOnlyTheSlotGoesUp() throws Exception {
		SessionBinder binder = new SessionBinder(this.links, true);
		binder.linkUp();
		binder.world(WORLD, true);
		binder.tick();
		assertTrue(binder.onBound(ProtoJson.parseObject("{\"slot\":3,\"switched\":\"title\"}"), binder.sentSeq()));
		assertEquals(3, binder.slot());
		assertEquals("title", binder.switched());
		assertEquals("{\"slot\":3}", ProtoJson.write(this.links.server.get(0).data()), "the server keeps the slot only");
		assertTrue(binder.onBound(ProtoJson.parseObject("{\"slot\":3,\"switched\":\"none\"}"), binder.sentSeq()));
		assertEquals("none", binder.switched());
		assertTrue(binder.onBound(ProtoJson.parseObject("{\"slot\":3}"), binder.sentSeq()));
		assertNull(binder.switched(), "not said: the gate guesses as before");
		assertFalse(binder.onBound(ProtoJson.parseObject("{\"slot\":3,\"switched\":\"sideways\"}"), binder.sentSeq()));
		assertFalse(binder.onBound(ProtoJson.parseObject("{\"slot\":3,\"switched\":1}"), binder.sentSeq()));
		assertEquals(List.of("session.bad_bound", "session.bad_bound"), this.links.warnings);
	}

	@Test
	void anInvalidWorldIdIsNeverBound() {
		SessionBinder binder = new SessionBinder(this.links, false);
		binder.linkUp();
		binder.world("not a world id", true);
		binder.tick();
		binder.world("not a world id", true);
		assertTrue(this.links.bridge.isEmpty());
		assertNull(binder.world());
		assertEquals(List.of("session.bad_world"), this.links.warnings, "warned once, not every tick");
	}

	@Test
	void anAnswerToAnOlderBindIsIgnored() throws Exception {
		SessionBinder binder = new SessionBinder(this.links, false);
		binder.linkUp();
		binder.world(WORLD, true);
		binder.tick();
		long first = binder.sentSeq();
		binder.world(OTHER, true);
		binder.tick();
		long second = binder.sentSeq();
		assertTrue(second > first);
		// gen1-bridge answered the first world's bind just before the second reached it.
		assertFalse(binder.onBound(bound(5), first));
		assertNull(binder.slot());
		assertTrue(this.links.warnings.contains("session.stale_bound"));
		assertTrue(binder.onBound(bound(6), second));
		assertEquals(6, binder.slot());
		// Without a re (an older gen1-bridge), the answer is taken as the latest bind's.
		binder.world(WORLD, true);
		binder.tick();
		assertTrue(binder.onBound(bound(7), null));
		assertEquals(7, binder.slot());
	}

	@Test
	void aRefusedBindIsSentAgainOnRequest() {
		SessionBinder binder = new SessionBinder(this.links, false);
		binder.linkUp();
		binder.world(WORLD, true);
		binder.tick();
		binder.tick();
		assertEquals(1, this.links.bridge.size());
		binder.rebind();
		binder.tick();
		assertEquals(2, this.links.bridge.size());
		assertBind(this.links.bridge.get(1), WORLD, true);
		assertEquals(2, binder.sentSeq());
	}
}
