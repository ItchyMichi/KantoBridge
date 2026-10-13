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

/** The bag projection's relay (MG6): bag.set down with its seq remembered, only its own act.result back up. */
class BagSetRelayTest {
	static final class FakePort implements BagSetRelay.Port {
		final List<String> bridge = new ArrayList<>();
		final List<String> server = new ArrayList<>();
		boolean bridgeUp = true;
		boolean serverUp = true;
		long nextSeq = 500;

		@Override
		public long toBridge(String type, JsonObject data) {
			if (!this.bridgeUp) {
				return -1;
			}
			this.bridge.add(type + " " + ProtoJson.write(data));
			return this.nextSeq++;
		}

		@Override
		public boolean toServer(String type, JsonObject data, @Nullable Long re) {
			if (!this.serverUp) {
				return false;
			}
			this.server.add(type + " re=" + re + " " + ProtoJson.write(data));
			return true;
		}
	}

	final FakePort port = new FakePort();
	final BagSetRelay relay = new BagSetRelay(this.port);

	static Envelope message(String type, long seq, @Nullable Long re, String data) throws Exception {
		return new Envelope(type, seq, re, ProtoJson.parseObject(data));
	}

	@Test
	void theProjectionGoesDownAndItsResultComesBackWithTheServersSeq() throws Exception {
		this.relay.fromServer(message(MsgTypes.BAG_SET, 7, null, "{\"items\":{\"potion\":3},\"money\":3000,\"stamp\":\"a1\"}"));
		assertEquals(List.of("bag.set {\"items\":{\"potion\":3},\"money\":3000,\"stamp\":\"a1\"}"), this.port.bridge);
		assertEquals(1, this.relay.pending());
		assertTrue(this.relay.onResult(message(MsgTypes.ACT_RESULT, 12, 500L, "{\"kind\":\"bag.set\",\"left\":2,\"ok\":true}")));
		assertEquals(List.of("act.result re=7 {\"kind\":\"bag.set\",\"left\":2,\"ok\":true}"), this.port.server);
		assertEquals(2, this.relay.left());
		assertNull(this.relay.lastReason());
		assertEquals(0, this.relay.pending());
		assertEquals(1, this.relay.stats().projections);
		assertEquals(1, this.relay.stats().resultsForwarded);
	}

	@Test
	void onlyAResultOfKindBagSetForAForwardedProjectionIsTaken() throws Exception {
		this.relay.fromServer(message(MsgTypes.BAG_SET, 7, null, "{\"items\":{},\"money\":0}"));
		assertFalse(this.relay.onResult(message(MsgTypes.ACT_RESULT, 13, 500L, "{\"ok\":true}")), "a field move's answer");
		assertFalse(this.relay.onResult(message(MsgTypes.ACT_RESULT, 14, 500L, "{\"kind\":\"interact\",\"ok\":true}")));
		assertFalse(this.relay.onResult(message(MsgTypes.ACT_RESULT, 15, 501L, "{\"kind\":\"bag.set\",\"ok\":true}")), "not ours");
		assertFalse(this.relay.onResult(message(MsgTypes.ACT_RESULT, 16, null, "{\"kind\":\"bag.set\",\"ok\":true}")));
		assertEquals(List.of(), this.port.server);
		assertEquals(1, this.relay.pending(), "still waiting for the real answer");
		assertTrue(this.relay.onResult(message(MsgTypes.ACT_RESULT, 17, 500L, "{\"kind\":\"bag.set\",\"ok\":false,\"reason\":\"too_many\",\"left\":5}")));
		assertEquals("too_many", this.relay.lastReason());
		assertEquals(5, this.relay.left());
		assertFalse(this.relay.onResult(message(MsgTypes.ACT_RESULT, 18, 500L, "{\"kind\":\"bag.set\",\"ok\":true}")), "answered once");
	}

	@Test
	void aProjectionThatCannotGoDownIsDroppedAndTheWalletFollowsBagSync() throws Exception {
		this.port.bridgeUp = false;
		this.relay.fromServer(message(MsgTypes.BAG_SET, 8, null, "{\"items\":{},\"money\":10}"));
		assertEquals(0, this.relay.pending());
		assertEquals(1, this.relay.stats().dropped);
		assertEquals(-1, this.relay.money());
		this.relay.onBagSync(ProtoJson.parseObject("{\"items\":[],\"money\":4321}"));
		assertEquals(4321, this.relay.money());
		this.relay.onBagSync(ProtoJson.parseObject("{\"items\":[]}"));
		assertEquals(4321, this.relay.money(), "a bag.sync without money keeps the last wallet");
		this.relay.disconnected();
		assertEquals(-1, this.relay.money());
	}

	@Test
	void theOldestPendingProjectionsAreForgottenPastTheCap() throws Exception {
		for (int i = 0; i < BagSetRelay.MAX_PENDING + 3; i++) {
			this.relay.fromServer(message(MsgTypes.BAG_SET, 100 + i, null, "{\"items\":{},\"money\":0}"));
		}
		assertEquals(BagSetRelay.MAX_PENDING, this.relay.pending());
		assertFalse(this.relay.onResult(message(MsgTypes.ACT_RESULT, 1, 500L, "{\"kind\":\"bag.set\",\"ok\":true}")), "the first was forgotten");
		assertTrue(this.relay.onResult(message(MsgTypes.ACT_RESULT, 2, 503L, "{\"kind\":\"bag.set\",\"ok\":true}")));
		assertEquals("act.result re=103 {\"kind\":\"bag.set\",\"ok\":true}", this.port.server.get(0));
	}
}
