package dev.kantobridge.client.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.proto.ProtoJson;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The client's relay of saves between gen1-bridge and the server, without either. */
class SaveRelayTest {
	private record Sent(String type, @Nullable Long re, JsonObject data) {
		String save() {
			return this.data.get("save").getAsString();
		}

		boolean ok() {
			return this.data.get("ok").getAsBoolean();
		}
	}

	private static final class Links implements SaveRelay.Port {
		final List<Sent> bridge = new ArrayList<>();
		final List<Sent> server = new ArrayList<>();
		final List<String> warnings = new ArrayList<>();
		boolean bridgeUp = true;
		boolean serverUp = true;

		@Override
		public boolean toBridge(String type, @Nullable Long re, JsonObject data) {
			if (!this.bridgeUp) {
				return false;
			}
			this.bridge.add(new Sent(type, re, data.deepCopy()));
			return true;
		}

		@Override
		public boolean toServer(String type, JsonObject data) {
			if (!this.serverUp) {
				return false;
			}
			this.server.add(new Sent(type, null, data.deepCopy()));
			return true;
		}

		@Override
		public void warn(String code, long count) {
			this.warnings.add(code);
		}
	}

	private final Links links = new Links();
	private final SaveRelay relay = new SaveRelay(this.links);

	private static Envelope begin(long seq, String save, String reason) throws Exception {
		return new Envelope(MsgTypes.SAVE_BEGIN, seq, null, ProtoJson.parseObject("{\"save\":\"" + save + "\",\"reason\":\"" + reason + "\"}"));
	}

	private static Envelope done(String save, boolean ok) throws Exception {
		return new Envelope(MsgTypes.SAVE_DONE, 9, 3L, ProtoJson.parseObject("{\"save\":\"" + save + "\",\"ok\":" + ok + "}"));
	}

	@Test
	void aSaveGoesUpUnchangedAndItsAnswerComesBackAsAReply() throws Exception {
		this.relay.linkChanged();
		Envelope begin = begin(12, "sv-1", "center");
		this.relay.fromBridge(begin, 0);
		assertEquals(1, this.links.server.size());
		assertEquals(MsgTypes.SAVE_BEGIN, this.links.server.get(0).type());
		assertEquals(ProtoJson.write(begin.data()), ProtoJson.write(this.links.server.get(0).data()), "relayed data must be unchanged");
		assertEquals(1, this.relay.pending());
		assertTrue(this.links.bridge.isEmpty());

		this.relay.fromServer(done("sv-1", true), 100);
		assertEquals(1, this.links.bridge.size());
		Sent answer = this.links.bridge.get(0);
		assertEquals(MsgTypes.SAVE_DONE, answer.type());
		assertEquals("sv-1", answer.save());
		assertTrue(answer.ok());
		assertEquals(12L, answer.re(), "re names gen1-bridge's save.begin on this link");
		assertEquals(2, answer.data().size(), "save.done carries save and ok only");
		assertEquals(0, this.relay.pending());
		assertTrue(this.relay.answered("sv-1"));
		assertEquals(1, this.relay.stats().relayed);
	}

	@Test
	void withoutAServerTheSaveFailsHere() throws Exception {
		this.links.serverUp = false;
		this.relay.fromBridge(begin(5, "sv-2", "menu"), 0);
		assertTrue(this.links.server.isEmpty());
		assertEquals(1, this.links.bridge.size());
		assertFalse(this.links.bridge.get(0).ok());
		assertEquals("sv-2", this.links.bridge.get(0).save());
		assertEquals(List.of("save.no_server"), this.links.warnings);
		assertEquals(1, this.relay.stats().answeredHere);
	}

	@Test
	void leavingTheServerFailsTheSavesStillWaiting() throws Exception {
		this.relay.fromBridge(begin(5, "sv-3", "menu"), 0);
		this.relay.fromBridge(begin(6, "sv-4", "center"), 0);
		this.relay.fromServer(done("sv-3", true), 10);
		this.relay.serverLeft(20);
		assertEquals(2, this.links.bridge.size());
		assertTrue(this.links.bridge.get(0).ok());
		assertEquals("sv-4", this.links.bridge.get(1).save());
		assertFalse(this.links.bridge.get(1).ok());
		assertEquals(0, this.relay.pending());
		assertEquals(List.of("save.server_left"), this.links.warnings);
	}

	@Test
	void anAnswerLostToAReconnectIsGivenWhenAskedAgain() throws Exception {
		this.relay.linkChanged();
		this.relay.fromBridge(begin(7, "sv-5", "menu"), 0);
		// The link drops as the answer comes back.
		this.links.bridgeUp = false;
		this.relay.fromServer(done("sv-5", true), 100);
		assertTrue(this.links.bridge.isEmpty());
		this.links.bridgeUp = true;
		this.relay.linkChanged();
		// gen1-bridge, still waiting, sends the save again on the new link: answered from the record, no second save.
		this.relay.fromBridge(begin(2, "sv-5", "menu"), 5_000);
		assertEquals(1, this.links.server.size(), "not relayed again");
		assertEquals(1, this.links.bridge.size());
		assertTrue(this.links.bridge.get(0).ok());
		assertEquals(2L, this.links.bridge.get(0).re(), "re names the repeated save.begin, the one on this link");
		assertEquals(1, this.relay.stats().repeats);
	}

	@Test
	void aReplyAfterTheLinkChangedCarriesNoRe() throws Exception {
		this.relay.linkChanged();
		this.relay.fromBridge(begin(7, "sv-6", "menu"), 0);
		this.relay.linkChanged();
		this.relay.fromServer(done("sv-6", false), 100);
		assertEquals(1, this.links.bridge.size());
		assertNull(this.links.bridge.get(0).re(), "a seq from another link would answer the wrong message");
		assertFalse(this.links.bridge.get(0).ok());
	}

	@Test
	void aRepeatWhileTheServerSavesIsIgnored() throws Exception {
		this.relay.fromBridge(begin(7, "sv-7", "menu"), 0);
		this.relay.fromBridge(begin(8, "sv-7", "menu"), 10);
		assertEquals(1, this.links.server.size());
		assertTrue(this.links.bridge.isEmpty());
		this.relay.fromServer(done("sv-7", true), 20);
		assertEquals(1, this.links.bridge.size());
		assertEquals(8L, this.links.bridge.get(0).re(), "the latest ask is answered");
		// A second answer for the same save changes nothing.
		this.relay.fromServer(done("sv-7", false), 30);
		assertEquals(1, this.links.bridge.size());
		assertEquals(List.of("save.second_done"), this.links.warnings);
	}

	@Test
	void anAnswerForASaveThisRunNeverSawIsPassedOn() throws Exception {
		this.relay.fromServer(done("sv-8", true), 0);
		assertEquals(1, this.links.bridge.size());
		assertNull(this.links.bridge.get(0).re());
	}

	@Test
	void malformedMessagesAreDroppedWithAWarning() throws Exception {
		this.relay.fromBridge(new Envelope(MsgTypes.SAVE_BEGIN, 1, null, ProtoJson.parseObject("{\"save\":7,\"reason\":\"menu\"}")), 0);
		this.relay.fromBridge(new Envelope(MsgTypes.SAVE_BEGIN, 2, null, ProtoJson.parseObject("{\"save\":\"no spaces\",\"reason\":\"menu\"}")), 0);
		this.relay.fromServer(new Envelope(MsgTypes.SAVE_DONE, 3, null, ProtoJson.parseObject("{\"save\":\"sv-9\",\"ok\":\"yes\"}")), 0);
		assertTrue(this.links.server.isEmpty());
		assertTrue(this.links.bridge.isEmpty());
		assertEquals(List.of("save.bad_id", "save.bad_id", "save.bad_done"), this.links.warnings);
	}

	@Test
	void deliveredAnswersAreForgottenInTime() throws Exception {
		this.relay.fromBridge(begin(7, "sv-10", "menu"), 0);
		this.relay.fromServer(done("sv-10", true), 1_000);
		this.relay.tick(1_000 + SaveRelay.KEEP_MS);
		assertTrue(this.relay.answered("sv-10"));
		this.relay.tick(1_000 + SaveRelay.KEEP_MS + 1);
		assertFalse(this.relay.answered("sv-10"));
		// Asked again after that: a new save.
		this.relay.fromBridge(begin(8, "sv-10", "menu"), 70_000);
		assertEquals(2, this.links.server.size());
	}

	@Test
	void aSaveRequestIsSentOnceAMinuteAndFollowedToItsSaveDone() throws Exception {
		// WORLD_SESSIONS_PLAN WS4: save.request -> act.result{kind:save} -> save.begin -> save.done
		java.util.List<JsonObject> requests = new java.util.ArrayList<>();
		long[] seq = {40};
		assertTrue(this.relay.request("world", 1_000, data -> {
			requests.add(data.deepCopy());
			return ++seq[0];
		}));
		assertEquals(SaveRelay.RequestState.SENT, this.relay.request());
		assertEquals("{\"reason\":\"world\"}", dev.kantobridge.proto.ProtoJson.write(requests.get(0)));
		assertFalse(this.relay.request("world", 30_000, data -> ++seq[0]), "a world request within a minute is skipped");
		assertFalse(this.relay.requestResult(99L, true, null), "another request's answer");
		assertTrue(this.relay.requestResult(41L, true, null));
		assertEquals(SaveRelay.RequestState.WRITTEN, this.relay.request());
		this.relay.fromBridge(begin(12, "sv-req", "world"), 1_100);
		this.relay.fromServer(done("sv-req", true), 1_200);
		assertEquals(SaveRelay.RequestState.DONE, this.relay.request());
		// quit is never limited; a refusal is kept with its reason
		assertTrue(this.relay.request("quit", 2_000, data -> ++seq[0]));
		assertTrue(this.relay.requestResult(seq[0], false, "not_idle"));
		assertEquals(SaveRelay.RequestState.REFUSED, this.relay.request());
		assertEquals("not_idle", this.relay.refusal());
		assertTrue(this.relay.request("world", 61_001, data -> ++seq[0]), "a minute later: sent again");
	}
}
