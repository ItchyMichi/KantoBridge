package dev.kantobridge.client.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.MsgTypes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Bill's PC on the client: relay, the screen's life, every way an answer is made, and the safety nets; M10: every
 * {@code pc.closed} echoes the id of the {@code pc.open} it ends, whoever makes it.
 */
class PcRelayTest {
	private static final class Links implements PcRelay.Port {
		/** {@code type:pc} of each message, in order. */
		final List<String> bridge = new ArrayList<>();
		final List<String> server = new ArrayList<>();
		final List<String> warnings = new ArrayList<>();
		int unlinks;
		boolean bridgeUp = true;
		boolean serverUp = true;

		@Override
		public boolean toBridge(String type, JsonObject data) {
			if (!this.bridgeUp) {
				return false;
			}
			assertEquals(1, data.size(), "pc.closed carries the id only");
			this.bridge.add(type + ":" + data.get("pc").getAsString());
			return true;
		}

		@Override
		public boolean toServer(String type, JsonObject data) {
			if (!this.serverUp) {
				return false;
			}
			assertEquals(1, data.size(), "pc.open carries the id only");
			this.server.add(type + ":" + data.get("pc").getAsString());
			return true;
		}

		@Override
		public void unlink() {
			this.unlinks++;
		}

		@Override
		public void warn(String code, long count) {
			this.warnings.add(code);
		}
	}

	private static final String OPEN_A = MsgTypes.PC_OPEN + ":pc-a";
	private static final String CLOSED_A = MsgTypes.PC_CLOSED + ":pc-a";

	private final Links links = new Links();
	private final PcRelay relay = new PcRelay(this.links);

	/** Ticks every 50 ms from {@code from} to {@code to} (inclusive) with the screen shown or not. */
	private void run(long from, long to, boolean shown) {
		for (long t = from; t <= to; t += 50) {
			this.relay.tick(t, shown);
		}
	}

	@Test
	void aPcSessionFromOpenToClose() {
		this.relay.fromBridge("pc-a", 0);
		assertEquals(List.of(OPEN_A), this.links.server, "relayed with its id");
		assertEquals(PcRelay.State.OPENING, this.relay.state());
		assertEquals("pc-a", this.relay.id());
		assertTrue(this.relay.pending(), "held from the relay on");
		this.run(50, 100, false);
		this.run(150, 2_000, true);
		assertEquals(PcRelay.State.OPEN, this.relay.state());
		// The player closes Cobblemon's screen; it unlinks by itself and the server answers a tick later.
		this.run(2_050, 2_100, false);
		assertEquals(PcRelay.State.CLOSING, this.relay.state());
		assertTrue(this.relay.pending(), "still held until the answer");
		this.relay.fromServer("pc-a", 2_120);
		assertEquals(List.of(CLOSED_A), this.links.bridge, "the answer echoes the id");
		assertEquals(PcRelay.State.IDLE, this.relay.state());
		assertFalse(this.relay.pending());
		this.run(2_150, 20_000, false);
		assertEquals(0, this.links.unlinks, "the normal path needs no unlink of ours");
		assertTrue(this.links.warnings.isEmpty());
		assertEquals(1, this.relay.stats().opened);
		assertEquals(1, this.relay.stats().closed);
	}

	@Test
	void aRefusalComesBackAtOnce() {
		this.relay.fromBridge("pc-a", 0);
		this.relay.fromServer("pc-a", 40);
		assertEquals(List.of(CLOSED_A), this.links.bridge);
		assertFalse(this.relay.pending());
	}

	@Test
	void repeatsWhileOpenAreIgnored() {
		this.relay.fromBridge("pc-a", 0);
		this.run(50, 100, true);
		this.relay.fromBridge("pc-a", 200);
		this.relay.fromBridge("pc-a", 300);
		assertEquals(1, this.links.server.size(), "the open session answers the repeats too");
		assertEquals(2, this.relay.stats().repeats);
		this.run(350, 400, false);
		this.relay.fromServer("pc-a", 420);
		assertEquals(1, this.links.bridge.size(), "one answer");
		// After the answer a pc.open is a new PC.
		this.relay.fromBridge("pc-b", 10_000);
		assertEquals(List.of(OPEN_A, MsgTypes.PC_OPEN + ":pc-b"), this.links.server);
	}

	@Test
	void aNewIdWhileOpenIsServedByTheOpenScreen() {
		this.relay.fromBridge("pc-a", 0);
		this.run(50, 100, true);
		// gen1-bridge let pc-a go (its link dropped) and asks again with a new id while Cobblemon's screen is still up.
		this.relay.fromBridge("pc-b", 200);
		assertEquals(List.of(OPEN_A, MsgTypes.PC_OPEN + ":pc-b"), this.links.server, "the server answers the newest id");
		assertEquals("pc-b", this.relay.id());
		assertTrue(this.relay.pending());
		// A late close for the old id isn't this session's: passed on (gen1-bridge ignores it), the session stays.
		this.relay.fromServer("pc-a", 250);
		assertTrue(this.relay.pending());
		this.run(300, 350, false);
		this.relay.fromServer("pc-b", 400);
		assertEquals(List.of(CLOSED_A, MsgTypes.PC_CLOSED + ":pc-b"), this.links.bridge);
		assertFalse(this.relay.pending());
		assertEquals(List.of("pc.unexpected_close"), this.links.warnings);
	}

	@Test
	void withoutAServerThePcClosesHere() {
		this.links.serverUp = false;
		this.relay.fromBridge("pc-a", 0);
		assertTrue(this.links.server.isEmpty());
		assertEquals(List.of(CLOSED_A), this.links.bridge, "answered here, with the id");
		assertFalse(this.relay.pending());
		assertEquals(List.of("pc.no_server"), this.links.warnings);
	}

	@Test
	void leavingTheServerClosesThePc() {
		this.relay.fromBridge("pc-a", 0);
		this.run(50, 100, true);
		this.relay.serverLeft(150);
		assertEquals(List.of(CLOSED_A), this.links.bridge);
		assertFalse(this.relay.pending());
		assertEquals(List.of("pc.server_left"), this.links.warnings);
		// Nothing pending: leaving again says nothing.
		this.relay.serverLeft(200);
		assertEquals(1, this.links.bridge.size());
	}

	@Test
	void aCloseGen1BridgeMissedIsAnsweredWhenItAsksAgain() {
		this.relay.fromBridge("pc-a", 0);
		this.run(50, 100, true);
		this.run(150, 150, false);
		// The link to gen1-bridge is down as the server answers.
		this.links.bridgeUp = false;
		this.relay.fromServer("pc-a", 200);
		assertEquals(PcRelay.State.UNDELIVERED, this.relay.state());
		assertFalse(this.relay.pending(), "the PC is closed: nothing to hold for");
		this.links.bridgeUp = true;
		// The link comes back; gen1-bridge asks again (the same id) right after its snapshot: answered, no new PC.
		this.relay.bridgeSnapshot(5_000);
		this.run(5_050, 5_000 + PcRelay.ASK_AGAIN_MS, false);
		this.relay.fromBridge("pc-a", 5_000 + PcRelay.ASK_AGAIN_MS);
		assertEquals(1, this.links.server.size());
		assertEquals(List.of(CLOSED_A), this.links.bridge);
		assertEquals(PcRelay.State.IDLE, this.relay.state());
	}

	@Test
	void anotherIdAfterAMissedCloseIsANewPc() {
		this.relay.fromBridge("pc-a", 0);
		this.run(50, 100, true);
		this.run(150, 150, false);
		this.links.bridgeUp = false;
		this.relay.fromServer("pc-a", 200);
		this.links.bridgeUp = true;
		// gen1-bridge released pc-a's hold with the link; the player chose Bill's PC again: a new id, a new PC.
		this.relay.fromBridge("pc-b", 1_000);
		assertEquals(List.of(OPEN_A, MsgTypes.PC_OPEN + ":pc-b"), this.links.server);
		assertTrue(this.links.bridge.isEmpty(), "the old close goes nowhere");
		assertEquals("pc-b", this.relay.id());
		assertTrue(this.relay.pending());
	}

	@Test
	void aCloseNobodyAsksForAfterTheSnapshotIsForgotten() {
		this.relay.fromBridge("pc-a", 0);
		this.run(50, 100, true);
		this.run(150, 150, false);
		this.links.bridgeUp = false;
		this.relay.fromServer("pc-a", 200);
		this.links.bridgeUp = true;
		// Waiting for a link, however long, keeps the record...
		this.run(250, 60_000, false);
		assertEquals(PcRelay.State.UNDELIVERED, this.relay.state());
		// ...but after the snapshot gen1-bridge didn't ask: it waits on no PC, so the next pc.open is a new one.
		this.relay.bridgeSnapshot(60_000);
		this.run(60_050, 60_000 + PcRelay.ASK_AGAIN_MS + 50, false);
		assertEquals(PcRelay.State.IDLE, this.relay.state());
		assertEquals(List.of("pc.close_not_asked"), this.links.warnings);
		this.relay.fromBridge("pc-a", 70_000);
		assertEquals(2, this.links.server.size(), "a new PC opens");
		assertTrue(this.links.bridge.isEmpty());
	}

	@Test
	void aScreenThatNeverOpensIsUnlinkedThenGivenUp() {
		this.relay.fromBridge("pc-a", 0);
		this.run(50, PcRelay.OPEN_TIMEOUT_MS, false);
		assertEquals(0, this.links.unlinks);
		this.run(PcRelay.OPEN_TIMEOUT_MS + 50, PcRelay.OPEN_TIMEOUT_MS + 50, false);
		assertEquals(1, this.links.unlinks, "the server is asked to drop the link");
		assertTrue(this.relay.pending());
		long unlinkedAt = PcRelay.OPEN_TIMEOUT_MS + 50;
		this.run(unlinkedAt + 50, unlinkedAt + PcRelay.GIVE_UP_MS, false);
		assertTrue(this.relay.pending(), "the server still has time to answer");
		this.run(unlinkedAt + PcRelay.GIVE_UP_MS + 50, unlinkedAt + PcRelay.GIVE_UP_MS + 50, false);
		assertEquals(List.of(CLOSED_A), this.links.bridge, "given up: answered here, with the id");
		assertFalse(this.relay.pending());
		assertEquals(List.of("pc.open_timeout", "pc.no_answer"), this.links.warnings);
		// The server's late answer for that id is dropped, not taken for another session's.
		this.relay.fromServer("pc-a", unlinkedAt + PcRelay.GIVE_UP_MS + 1_000);
		assertEquals(1, this.links.bridge.size());
		assertEquals(1, this.relay.stats().strays);
	}

	@Test
	void aScreenReplacedWithoutUnlinkIsUnlinkedForIt() {
		this.relay.fromBridge("pc-a", 0);
		this.run(50, 100, true);
		// Another screen took the PC's place and nothing unlinked: no answer comes.
		this.run(150, 150 + PcRelay.UNLINK_AFTER_MS, false);
		assertEquals(0, this.links.unlinks);
		this.run(200 + PcRelay.UNLINK_AFTER_MS, 200 + PcRelay.UNLINK_AFTER_MS, false);
		assertEquals(1, this.links.unlinks);
		assertEquals(List.of("pc.closed_without_unlink"), this.links.warnings);
		this.relay.fromServer("pc-a", 300 + PcRelay.UNLINK_AFTER_MS);
		assertEquals(List.of(CLOSED_A), this.links.bridge);
		assertFalse(this.relay.pending());
	}

	@Test
	void anUnexpectedCloseIsPassedOnButNeverKept() {
		this.links.bridgeUp = false;
		this.relay.fromServer("pc-x", 0);
		assertEquals(PcRelay.State.IDLE, this.relay.state(), "a close nobody waits for is not kept for later");
		this.links.bridgeUp = true;
		this.relay.fromServer("pc-x", 10);
		assertEquals(List.of(MsgTypes.PC_CLOSED + ":pc-x"), this.links.bridge, "passed on with its own id");
		assertEquals(List.of("pc.unexpected_close", "pc.unexpected_close"), this.links.warnings);
		// A pc.open afterwards opens the PC.
		this.relay.fromBridge("pc-a", 20);
		assertEquals(List.of(OPEN_A), this.links.server);
	}

	@Test
	void theBodiesCarryTheId() {
		assertEquals("{\"pc\":\"pc-7\"}", PcRelay.openBody("pc-7").toString());
		assertEquals("{\"pc\":\"pc-7\"}", PcRelay.closedBody("pc-7").toString());
	}
}
