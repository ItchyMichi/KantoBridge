package dev.kantobridge.client.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.proto.ProtoJson;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The client's relay of battles and Pokémon between gen1-bridge and the server, without either. */
class GameRelayTest {
	private record Sent(String type, JsonObject data) {
		String str(String key) {
			return this.data.get(key).getAsString();
		}
	}

	/** Both links, switchable, recording what went out. */
	private static final class Links implements GameRelay.Port {
		final List<Sent> bridge = new ArrayList<>();
		final List<Sent> server = new ArrayList<>();
		final List<String> warnings = new ArrayList<>();
		boolean bridgeUp = true;
		boolean serverUp = true;

		@Override
		public boolean toBridge(String type, JsonObject data) {
			if (!this.bridgeUp) {
				return false;
			}
			this.bridge.add(new Sent(type, data.deepCopy()));
			return true;
		}

		@Override
		public boolean toServer(String type, JsonObject data) {
			if (!this.serverUp) {
				return false;
			}
			this.server.add(new Sent(type, data.deepCopy()));
			return true;
		}

		@Override
		public void warn(String code, long count) {
			this.warnings.add(code);
		}
	}

	private final Links links = new Links();
	private final GameRelay relay = new GameRelay(this.links);
	private long seq = 40;

	private Envelope message(String type, String data) throws Exception {
		return new Envelope(type, this.seq++, null, ProtoJson.parseObject(data));
	}

	private static String start(String id) {
		return "{\"battle\":\"" + id + "\",\"kind\":\"rival\",\"trainer\":{\"cls\":\"OPP_RIVAL1\",\"party\":1},"
				+ "\"team\":[{\"dex\":7,\"level\":5,\"moves\":[33,39],\"dvs\":{\"atk\":9,\"def\":8,\"spd\":8,\"spc\":8}}],"
				+ "\"rules\":{\"catch\":false,\"flee\":false}}";
	}

	private static String result(String id, String outcome) {
		return "{\"battle\":\"" + id + "\",\"outcome\":\"" + outcome + "\"}";
	}

	@Test
	void aBattleStartGoesUpUnchangedAndItsResultComesBack() throws Exception {
		Envelope start = this.message(MsgTypes.BATTLE_START, start("b-1"));
		this.relay.fromBridge(start, 0);
		assertEquals(1, this.links.server.size());
		assertEquals(MsgTypes.BATTLE_START, this.links.server.get(0).type());
		assertEquals(ProtoJson.write(start.data()), ProtoJson.write(this.links.server.get(0).data()), "relayed data must be unchanged");
		assertEquals(1, this.relay.pendingBattles());
		assertTrue(this.links.bridge.isEmpty());

		this.relay.fromServer(this.message(MsgTypes.BATTLE_RESULT, result("b-1", "win")), 1_000);
		assertEquals(1, this.links.bridge.size());
		Sent answer = this.links.bridge.get(0);
		assertEquals(MsgTypes.BATTLE_RESULT, answer.type());
		assertEquals("b-1", answer.str("battle"));
		assertEquals("win", answer.str("outcome"));
		assertEquals(0, this.relay.pendingBattles());
		assertTrue(this.relay.answered("b-1"));
	}

	@Test
	void aResultLostToAReconnectGoesAgainWhenTheStartComesAgain() throws Exception {
		this.relay.fromBridge(this.message(MsgTypes.BATTLE_START, start("b-1")), 0);
		// The link drops just as the result comes: it can't be delivered now.
		this.links.bridgeUp = false;
		this.relay.fromServer(this.message(MsgTypes.BATTLE_RESULT, result("b-1", "lose")), 1_000);
		assertTrue(this.links.bridge.isEmpty());
		this.links.bridgeUp = true;
		// The snapshot alone sends no results: gen1-bridge answers one for a battle it isn't waiting on with an error.
		this.relay.bridgeSnapshot(2_000);
		assertTrue(this.links.bridge.isEmpty());
		// gen1-bridge, still waiting, sends the battle again: the record answers, even long after, and the server isn't asked.
		long later = 1_000 + GameRelay.RESEND_WINDOW_MS + 5_000;
		this.relay.tick(later);
		this.relay.fromBridge(this.message(MsgTypes.BATTLE_START, start("b-1")), later);
		assertEquals(1, this.links.bridge.size());
		assertEquals("lose", this.links.bridge.get(0).str("outcome"));
		assertEquals(1, this.links.server.size(), "not relayed again");
		// Delivered now: kept for the window after delivery, then forgotten.
		this.relay.tick(later + GameRelay.RESEND_WINDOW_MS);
		assertTrue(this.relay.answered("b-1"));
		this.relay.tick(later + GameRelay.RESEND_WINDOW_MS + 1);
		assertFalse(this.relay.answered("b-1"));
	}

	@Test
	void repeatedStartsAreIgnoredOrAnsweredFromTheRecord() throws Exception {
		this.relay.fromBridge(this.message(MsgTypes.BATTLE_START, start("b-1")), 0);
		this.relay.fromBridge(this.message(MsgTypes.BATTLE_START, start("b-1")), 10);
		assertEquals(1, this.links.server.size(), "a running battle is not relayed twice");
		this.relay.fromServer(this.message(MsgTypes.BATTLE_RESULT, result("b-1", "win")), 100);
		this.relay.fromBridge(this.message(MsgTypes.BATTLE_START, start("b-1")), 200);
		assertEquals(1, this.links.server.size(), "an answered battle is not relayed again");
		assertEquals(2, this.links.bridge.size(), "it gets its result again");
		assertEquals("win", this.links.bridge.get(1).str("outcome"));
		assertEquals(2, this.relay.stats().repeatedStarts);
	}

	@Test
	void withoutAServerTheBattleIsAbortedHere() throws Exception {
		this.links.serverUp = false;
		this.relay.fromBridge(this.message(MsgTypes.BATTLE_START, start("b-9")), 0);
		assertTrue(this.links.server.isEmpty());
		assertEquals(1, this.links.bridge.size());
		assertEquals("b-9", this.links.bridge.get(0).str("battle"));
		assertEquals("aborted", this.links.bridge.get(0).str("outcome"));
		assertTrue(this.links.warnings.contains("battle.no_server"));
	}

	@Test
	void leavingTheServerAbortsPendingBattles() throws Exception {
		this.relay.fromBridge(this.message(MsgTypes.BATTLE_START, start("b-1")), 0);
		this.relay.fromServer(this.message(MsgTypes.PARTY_SYNC, "{\"party\":[],\"box\":{\"count\":0}}"), 5);
		this.links.bridge.clear();
		this.relay.serverLeft(10);
		assertEquals(1, this.links.bridge.size());
		assertEquals("aborted", this.links.bridge.get(0).str("outcome"));
		assertEquals(0, this.relay.pendingBattles());
		this.relay.serverJoined(20);
		this.links.bridge.clear();
		this.relay.bridgeSnapshot(30);
		assertTrue(this.links.bridge.stream().noneMatch(sent -> sent.type().equals(MsgTypes.PARTY_SYNC)),
				"the old world's party.sync is forgotten");
	}

	@Test
	void partyRequestsGoUpOrWaitForTheServer() throws Exception {
		Envelope give = this.message(MsgTypes.PARTY_GIVE, "{\"give\":\"g-1\",\"dex\":7,\"level\":5,\"moves\":[33,39],\"source\":\"starter\"}");
		this.relay.fromBridge(give, 0);
		assertEquals(1, this.links.server.size());
		assertEquals(ProtoJson.write(give.data()), ProtoJson.write(this.links.server.get(0).data()));

		this.links.serverUp = false;
		this.relay.fromBridge(this.message(MsgTypes.PARTY_HEAL, "{}"), 10);
		this.relay.fromBridge(this.message(MsgTypes.PARTY_RENAME, "{\"uid\":\"0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b\",\"nick\":\"X\"}"), 20);
		assertEquals(2, this.relay.queued());
		this.relay.tick(30);
		assertEquals(2, this.relay.queued(), "still no server");
		this.links.serverUp = true;
		this.relay.serverJoined(40);
		assertEquals(0, this.relay.queued());
		assertEquals(List.of(MsgTypes.PARTY_GIVE, MsgTypes.PARTY_HEAL, MsgTypes.PARTY_RENAME),
				this.links.server.stream().map(Sent::type).toList(), "in order");
	}

	@Test
	void waitingPartyRequestsGoBeforeABattleStart() throws Exception {
		this.links.serverUp = false;
		this.relay.fromBridge(this.message(MsgTypes.PARTY_GIVE, "{\"give\":\"g-1\",\"dex\":4,\"level\":5,\"source\":\"starter\"}"), 0);
		this.links.serverUp = true;
		// The link is back but no tick flushed yet: the rival battle must not overtake the starter.
		this.relay.fromBridge(this.message(MsgTypes.BATTLE_START, start("b-1")), 10);
		assertEquals(List.of(MsgTypes.PARTY_GIVE, MsgTypes.BATTLE_START), this.links.server.stream().map(Sent::type).toList());
		assertEquals(1, this.relay.pendingBattles());
	}

	@Test
	void theQueueIsBounded() throws Exception {
		this.links.serverUp = false;
		for (int i = 0; i < GameRelay.QUEUE_MAX + 3; i++) {
			this.relay.fromBridge(this.message(MsgTypes.PARTY_HEAL, "{}"), i);
		}
		assertEquals(GameRelay.QUEUE_MAX, this.relay.queued());
		assertEquals(3, this.links.warnings.stream().filter("party.queue_full"::equals).count());
	}

	@Test
	void syncsGoDownAndAgainAfterEverySnapshot() throws Exception {
		String party = "{\"party\":[{\"uid\":\"0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b\",\"dex\":7,\"level\":5,\"moves\":[33,39],\"hp\":20,\"maxhp\":20}],"
				+ "\"box\":{\"count\":0}}";
		this.relay.fromServer(this.message(MsgTypes.PARTY_SYNC, party), 0);
		this.relay.fromServer(this.message(MsgTypes.DEX_SYNC, "{\"seen\":[7],\"owned\":[7]}"), 0);
		assertEquals(List.of(MsgTypes.PARTY_SYNC, MsgTypes.DEX_SYNC), this.links.bridge.stream().map(Sent::type).toList());
		this.links.bridge.clear();
		this.relay.bridgeSnapshot(10);
		assertEquals(List.of(MsgTypes.PARTY_SYNC, MsgTypes.DEX_SYNC), this.links.bridge.stream().map(Sent::type).toList());
		assertEquals(ProtoJson.parseObject(party), this.links.bridge.get(0).data(), "the cached sync is the server's, unchanged");
	}

	@Test
	void malformedMessagesAreDroppedWithAWarning() throws Exception {
		this.relay.fromBridge(this.message(MsgTypes.BATTLE_START, "{\"battle\":\"two words\",\"kind\":\"rival\"}"), 0);
		this.relay.fromServer(this.message(MsgTypes.BATTLE_RESULT, "{\"battle\":\"b-1\",\"outcome\":\"draw\"}"), 0);
		assertTrue(this.links.server.isEmpty());
		assertTrue(this.links.bridge.isEmpty());
		assertEquals(List.of("battle.bad_id", "battle.bad_result"), this.links.warnings);
		// Other types are not this relay's business.
		this.relay.fromBridge(this.message(MsgTypes.PLAYER_STATE, "{}"), 0);
		this.relay.fromServer(this.message(MsgTypes.WARP_DONE, "{\"ok\":true}"), 0);
		assertTrue(this.links.server.isEmpty() && this.links.bridge.isEmpty());
	}

	@Test
	void oldDeliveredRecordsAreDropped() throws Exception {
		this.relay.fromBridge(this.message(MsgTypes.BATTLE_START, start("b-1")), 0);
		this.relay.fromServer(this.message(MsgTypes.BATTLE_RESULT, result("b-1", "win")), 0);
		this.relay.tick(GameRelay.RESEND_WINDOW_MS + 1);
		assertFalse(this.relay.answered("b-1"));
		// Now a start with that id is a new battle.
		this.relay.fromBridge(this.message(MsgTypes.BATTLE_START, start("b-1")), GameRelay.RESEND_WINDOW_MS + 2);
		assertEquals(2, this.links.server.size());
	}
	@Test
	void theBagGoesUpAndTheNewestWaitsForTheServer() throws Exception {
		Envelope bag = this.message(MsgTypes.BAG_SYNC, "{\"items\":{\"POKE_BALL\":5,\"POTION\":2}}");
		this.relay.fromBridge(bag, 0);
		assertEquals(List.of(MsgTypes.BAG_SYNC), this.links.server.stream().map(Sent::type).toList());
		assertEquals(ProtoJson.write(bag.data()), ProtoJson.write(this.links.server.get(0).data()), "relayed unchanged");
		this.links.serverUp = false;
		this.relay.fromBridge(this.message(MsgTypes.BAG_SYNC, "{\"items\":{\"POKE_BALL\":4}}"), 10);
		this.relay.fromBridge(this.message(MsgTypes.BAG_SYNC, "{\"items\":{\"POKE_BALL\":3}}"), 20);
		assertTrue(this.relay.bagPending());
		this.links.serverUp = true;
		this.relay.tick(30);
		assertFalse(this.relay.bagPending());
		assertEquals(2, this.links.server.size(), "only the newest bag waited");
		assertEquals(3, this.links.server.get(1).data().getAsJsonObject("items").get("POKE_BALL").getAsInt());
		// A newer bag waits behind a party request that came before it, never in front of it.
		this.links.serverUp = false;
		this.relay.fromBridge(this.message(MsgTypes.BAG_SYNC, "{\"items\":{\"POKE_BALL\":2}}"), 40);
		this.relay.fromBridge(this.message(MsgTypes.PARTY_HEAL, "{}"), 41);
		this.relay.fromBridge(this.message(MsgTypes.BAG_SYNC, "{\"items\":{\"POKE_BALL\":1}}"), 42);
		this.links.serverUp = true;
		this.relay.serverJoined(50);
		assertEquals(List.of(MsgTypes.BAG_SYNC, MsgTypes.BAG_SYNC, MsgTypes.PARTY_HEAL, MsgTypes.BAG_SYNC),
				this.links.server.stream().map(Sent::type).toList());
		assertEquals(1, this.links.server.get(3).data().getAsJsonObject("items").get("POKE_BALL").getAsInt(), "the newest bag");
	}

	@Test
	void aWaitingBagKeepsItsPlaceBetweenPartyRequestsAndABattle() throws Exception {
		this.links.serverUp = false;
		this.relay.fromBridge(this.message(MsgTypes.PARTY_GIVE, "{\"give\":\"g-1\",\"dex\":4,\"level\":5,\"source\":\"gift\"}"), 0);
		this.relay.fromBridge(this.message(MsgTypes.BAG_SYNC, "{\"items\":{\"POKE_BALL\":5}}"), 1);
		this.links.serverUp = true;
		// The link is back but no tick flushed yet: gen1-bridge's order is party, bag, battle.
		this.relay.fromBridge(this.message(MsgTypes.BATTLE_START, start("b-1")), 10);
		assertEquals(List.of(MsgTypes.PARTY_GIVE, MsgTypes.BAG_SYNC, MsgTypes.BATTLE_START), this.links.server.stream().map(Sent::type).toList());
		assertFalse(this.relay.bagPending());
	}

	@Test
	void aLiveSnapshotsPokedexGoesUpOnlyWithTheC2sLeg() throws Exception {
		String snapshot = "{\"ingame\":true,\"bag\":{\"POKE_BALL\":5},\"dex\":{\"seen\":[16],\"owned\":[]}}";
		this.relay.bridgeSnapshot(ProtoJson.parseObject(snapshot), 0);
		assertTrue(this.links.server.isEmpty(), "gen1-bridge sends its own bag.sync after a snapshot; no dex.sync without the leg");
		GameRelay withDex = new GameRelay(this.links, true);
		withDex.bridgeSnapshot(ProtoJson.parseObject("{\"ingame\":false,\"dex\":{\"seen\":[],\"owned\":[]}}"), 10);
		assertTrue(this.links.server.isEmpty(), "at the title screen the Pokédex is only the save's leftovers");
		withDex.bridgeSnapshot(ProtoJson.parseObject(snapshot), 20);
		assertEquals(List.of(MsgTypes.DEX_SYNC), this.links.server.stream().map(Sent::type).toList());
		assertEquals("{\"owned\":[],\"seen\":[16]}", ProtoJson.write(this.links.server.get(0).data()));
	}

	@Test
	void bagUsesGoDownOrWaitForTheNextSnapshot() throws Exception {
		this.relay.fromServer(this.message(MsgTypes.BAG_USE, "{\"item\":\"POKE_BALL\",\"qty\":1}"), 0);
		assertEquals(List.of(MsgTypes.BAG_USE), this.links.bridge.stream().map(Sent::type).toList());
		this.links.bridgeUp = false;
		this.relay.fromServer(this.message(MsgTypes.BAG_USE, "{\"item\":\"POTION\",\"qty\":1}"), 10);
		this.relay.fromServer(this.message(MsgTypes.BAG_USE, "{\"item\":\"POKE_BALL\",\"qty\":2}"), 20);
		assertEquals(2, this.relay.queuedUses());
		this.links.bridgeUp = true;
		this.relay.bridgeSnapshot(ProtoJson.parseObject("{\"ingame\":true}"), 30);
		assertEquals(0, this.relay.queuedUses());
		List<Sent> uses = this.links.bridge.stream().filter(sent -> sent.type().equals(MsgTypes.BAG_USE)).toList();
		assertEquals(3, uses.size());
		assertEquals(List.of("POKE_BALL", "POTION", "POKE_BALL"), uses.stream().map(sent -> sent.str("item")).toList(), "in order");
		assertEquals(3, this.relay.stats().bagUsesForwarded);
	}
}
