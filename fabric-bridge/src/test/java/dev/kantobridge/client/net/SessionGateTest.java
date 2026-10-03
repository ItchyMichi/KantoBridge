package dev.kantobridge.client.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.client.bridge.ConnectionStatus;
import dev.kantobridge.client.net.SessionGate.Admit;
import dev.kantobridge.client.net.SessionGate.State;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The save pairing's gate (M9): a world's data waits on both hops from the bind until gen1-bridge's {@code session.bound}
 * for it; then what waited goes to the server in order when the hidden game runs the same save as before (a reconnect),
 * and is dropped (with the relays' queues, and a fresh snapshot asked for) when it may be another world's. A world
 * change drops the previous world's queues; refusals are shown and tried again; the switch's waiting reason is shown.
 * M10: {@code session.bound.switched} decides instead of the guess when gen1-bridge says it, {@code session.waiting} gives
 * the waiting reason, and {@code save.loaded} waits like the other story data.
 */
class SessionGateTest {
	private static final String WORLD = "0f8e9a1c-2b3d-4e5f-8a9b-0c1d2e3f4a5b";
	private static final String OTHER = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d";
	private static final String SESSION = "s-1";

	/** What the gate asks of the glue. */
	private static final class Glue implements SessionGate.Port {
		final List<String> replayed = new ArrayList<>();
		final List<Boolean> openings = new ArrayList<>();
		final List<String> warnings = new ArrayList<>();
		int worldChanges;
		int rebinds;

		@Override
		public void replay(Envelope message) {
			this.replayed.add(message.type());
		}

		@Override
		public void opened(boolean stale) {
			this.openings.add(stale);
		}

		@Override
		public void worldChanged() {
			this.worldChanges++;
		}

		@Override
		public void rebind() {
			this.rebinds++;
		}

		@Override
		public void warn(String code, long count) {
			this.warnings.add(code);
		}
	}

	private Glue glue;
	private SessionGate gate;
	private long seq;

	@BeforeEach
	void setUp() {
		this.glue = new Glue();
		this.gate = new SessionGate(this.glue);
	}

	private static Envelope message(String type) {
		return new Envelope(type, 1, null, new JsonObject());
	}

	/** The binder's bind for the current world on this link; returns its seq. */
	private long bind(long now) {
		long next = ++this.seq;
		this.gate.bindSent(next, now);
		return next;
	}

	/** In {@link #WORLD}, on a fresh link of {@link #SESSION}, paired once (a first opening is always stale). */
	private void pairedOnce() {
		this.gate.linkUp(SESSION, 0);
		this.gate.world(true, WORLD, 0);
		this.gate.bound(this.bind(0), 10);
		assertEquals(State.OPEN, this.gate.state());
		this.gate.snapshot();
		this.glue.openings.clear();
		this.glue.replayed.clear();
	}

	@Test
	void outsideKantoNothingIsHeld() {
		this.gate.linkUp(SESSION, 0);
		assertEquals(State.IDLE, this.gate.state());
		assertEquals(Admit.PASS, this.gate.admit(message(MsgTypes.PARTY_GIVE)));
		assertFalse(this.gate.serverOpen(), "the relays' own no-server rules apply");
		assertTrue(this.gate.bridgeOpen(), "their answers (aborted battles, closed PCs) reach gen1-bridge");
		assertFalse(this.gate.holdsPlayer());
	}

	@Test
	void aBindHoldsBothHopsUntilItsAnswer() {
		this.gate.linkUp(SESSION, 0);
		this.gate.world(true, WORLD, 0);
		long bind = this.bind(0);
		assertEquals(State.HELD, this.gate.state());
		assertFalse(this.gate.serverOpen(), "nothing to the server");
		assertFalse(this.gate.bridgeOpen(), "nothing of the server's to gen1-bridge");
		assertTrue(this.gate.holdsPlayer(), "the hidden game isn't this world's yet");
		for (String type : List.of(MsgTypes.PARTY_GIVE, MsgTypes.BAG_SYNC, MsgTypes.BATTLE_START, MsgTypes.SAVE_BEGIN, MsgTypes.BAG_SYNC)) {
			assertEquals(Admit.HELD, this.gate.admit(message(type)), type);
		}
		assertEquals(4, this.gate.heldCount(), "only the newest bag waits");
		assertEquals(Admit.PASS, this.gate.admit(message(MsgTypes.STORY_FLAGS)), "not story data for the server");

		this.gate.bound(bind, 20);
		assertEquals(State.OPEN, this.gate.state());
		assertTrue(this.gate.serverOpen() && this.gate.bridgeOpen());
		assertEquals(List.of(true), this.glue.openings, "the first opening can't know which save the waiting data came from");
		assertTrue(this.glue.replayed.isEmpty(), "dropped, not relayed");
		assertEquals(4, this.gate.stats().dropped);
		assertTrue(this.gate.holdsPlayer(), "until the fresh snapshot says where the hidden game is");
		this.gate.snapshot();
		assertFalse(this.gate.holdsPlayer());
		assertEquals(Admit.PASS, this.gate.admit(message(MsgTypes.PARTY_GIVE)), "open: straight to the relay");
	}

	@Test
	void aReconnectToTheSameSaveSendsWhatWaitedInOrder() {
		this.pairedOnce();
		this.gate.linkDown(100);
		assertEquals(State.HELD, this.gate.state(), "a new link needs its own bind");
		this.gate.linkUp(SESSION, 200);
		for (String type : List.of(MsgTypes.PARTY_HEAL, MsgTypes.BATTLE_START, MsgTypes.SAVE_BEGIN, MsgTypes.BAG_SYNC, MsgTypes.PARTY_ITEM)) {
			assertEquals(Admit.HELD, this.gate.admit(message(type)));
		}
		this.gate.bound(this.bind(210), 230);
		assertEquals(List.of(false), this.glue.openings, "the same gen1-bridge session and world: the same save");
		assertEquals(List.of(MsgTypes.PARTY_HEAL, MsgTypes.BATTLE_START, MsgTypes.SAVE_BEGIN, MsgTypes.BAG_SYNC, MsgTypes.PARTY_ITEM),
				this.glue.replayed, "in gen1-bridge's order");
		assertFalse(this.gate.holdsPlayer(), "no fresh snapshot needed");
	}

	@Test
	void aRestartedGen1BridgeIsAFreshStart() {
		this.pairedOnce();
		this.gate.linkDown(100);
		this.gate.linkUp("s-2", 200);
		this.gate.admit(message(MsgTypes.PARTY_RENAME));
		this.gate.bound(this.bind(210), 220);
		assertEquals(List.of(true), this.glue.openings);
		assertTrue(this.glue.replayed.isEmpty());
	}

	@Test
	void aWorldChangeDropsThePreviousWorldsQueues() {
		this.pairedOnce();
		// Off to the title and back into the same world: nothing is another world's.
		this.gate.world(false, null, 100);
		assertEquals(State.IDLE, this.gate.state());
		this.gate.world(true, WORLD, 200);
		assertEquals(0, this.glue.worldChanges);
		assertEquals(State.HELD, this.gate.state(), "a new visit binds again");
		this.gate.admit(message(MsgTypes.PARTY_GIVE));
		this.gate.bound(this.bind(210), 220);
		assertEquals(List.of(false), this.glue.openings);
		assertEquals(List.of(MsgTypes.PARTY_GIVE), this.glue.replayed);

		// Another world: the queues of this one go at once, and its first opening starts afresh.
		this.gate.world(false, null, 300);
		this.gate.world(true, null, 400);
		this.gate.admit(message(MsgTypes.PARTY_GIVE));
		this.gate.world(true, OTHER, 410);
		assertEquals(1, this.glue.worldChanges);
		assertEquals(0, this.gate.heldCount(), "what waited belonged to the previous world's save");
		this.gate.bound(this.bind(420), 430);
		assertEquals(List.of(false, true), this.glue.openings);
	}

	@Test
	void answersToOlderBindsOrLinksAreIgnored() {
		this.gate.linkUp(SESSION, 0);
		this.gate.world(true, WORLD, 0);
		long first = this.bind(0);
		this.gate.world(true, OTHER, 50);
		long second = this.bind(50);
		this.gate.bound(first, 60);
		assertEquals(State.HELD, this.gate.state(), "the answer to the other world's bind");
		this.gate.linkDown(70);
		this.gate.linkUp(SESSION, 80);
		this.gate.bound(second, 90);
		assertEquals(State.HELD, this.gate.state(), "an answer from the previous link");
		this.gate.bound(this.bind(95), 100);
		assertEquals(State.OPEN, this.gate.state());
		assertTrue(this.glue.warnings.contains("session.stray_bound"));
	}

	@Test
	void aPcOpenWhileHeldIsAnsweredClosed() {
		this.gate.linkUp(SESSION, 0);
		this.gate.world(true, WORLD, 0);
		long bind = this.bind(0);
		assertEquals(Admit.ANSWER_CLOSED, this.gate.admit(message(MsgTypes.PC_OPEN)));
		assertEquals(0, this.gate.heldCount(), "never replayed");
		this.gate.bound(bind, 10);
		assertEquals(Admit.PASS, this.gate.admit(message(MsgTypes.PC_OPEN)));
	}

	@Test
	void refusalsAreShownAndTriedAgainWithABackOff() {
		this.gate.linkUp(SESSION, 0);
		this.gate.world(true, WORLD, 0);
		long bind = this.bind(0);
		this.gate.error("session.store_failed", bind, 1_000);
		assertEquals(State.HELD, this.gate.state(), "still not this world's save");
		ConnectionStatus.Line line = this.gate.hudLine(1_000);
		assertNotNull(line);
		assertEquals("kantobridge.hud.session.refused", line.key());
		assertEquals(List.of("session.store_failed"), line.args());
		assertTrue(line.warning());
		this.gate.tick(1_000 + SessionGate.RETRY_FIRST_MS - 1);
		assertEquals(0, this.glue.rebinds);
		this.gate.tick(1_000 + SessionGate.RETRY_FIRST_MS);
		assertEquals(1, this.glue.rebinds, "bound again after the first wait");
		long again = this.bind(6_100);
		this.gate.error("session.failed", again, 7_000);
		this.gate.tick(7_000 + SessionGate.RETRY_FIRST_MS);
		assertEquals(1, this.glue.rebinds, "the wait doubles");
		this.gate.tick(7_000 + 2 * SessionGate.RETRY_FIRST_MS);
		assertEquals(2, this.glue.rebinds);
		assertEquals(2, this.gate.stats().refusals);
		ConnectionStatus.Line later = this.gate.hudLine(7_000 + SessionGate.NOTICE_MS + 1);
		assertNotNull(later);
		assertEquals("kantobridge.hud.session.pairing", later.key(), "the refusal's notice goes after a while; the pairing still waits");
	}

	@Test
	void errorsThatArentTheBindsAreNotRefusals() {
		this.gate.linkUp(SESSION, 0);
		this.gate.world(true, WORLD, 0);
		long bind = this.bind(0);
		this.gate.error("bag.not_mirrored", 99L, 10);
		this.gate.error("session.no_slot", bind + 7, 10);
		this.gate.error("session.pending", null, 10);
		this.gate.error("Not A Code", bind, 10);
		assertEquals(0, this.gate.stats().refusals);
		assertEquals(State.HELD, this.gate.state());
		// A malformed bind (bad_message, answering it) is one.
		this.gate.error("bad_message", bind, 20);
		assertEquals(1, this.gate.stats().refusals);
	}

	@Test
	void anInstallWithoutSaveSlotsPlaysUnpaired() {
		this.gate.linkUp(SESSION, 0);
		this.gate.world(true, WORLD, 0);
		this.gate.error(SessionGate.NO_SLOTS, this.bind(0), 10);
		assertEquals(State.OPEN, this.gate.state());
		assertTrue(this.gate.unpaired());
		assertEquals(List.of(true), this.glue.openings);
		assertEquals("kantobridge.hud.session.refused", this.gate.hudLine(20).key());
	}

	@Test
	void theSwitchsWaitingReasonIsShown() {
		this.gate.linkUp(SESSION, 0);
		this.gate.world(true, WORLD, 0);
		long bind = this.bind(0);
		assertNull(this.gate.hudLine(SessionGate.QUIET_WAIT_MS - 1), "a quick pairing shows nothing");
		assertEquals("kantobridge.hud.session.pairing", this.gate.hudLine(SessionGate.QUIET_WAIT_MS + 1).key());
		this.gate.waiting("busy", bind, 2_000);
		assertEquals("busy", this.gate.waiting());
		assertEquals("kantobridge.hud.session.waiting.busy", this.gate.hudLine(2_000).key());
		this.gate.waiting("new_game", bind, 3_000);
		assertEquals("kantobridge.hud.session.waiting.new_game", this.gate.hudLine(3_000).key());
		this.gate.waiting("save_failed", null, 3_500);
		assertEquals("kantobridge.hud.session.waiting.save_failed", this.gate.hudLine(3_500).key(), "re is optional");
		this.gate.waiting("later_thing", bind, 4_000);
		ConnectionStatus.Line other = this.gate.hudLine(4_000);
		assertEquals("kantobridge.hud.session.waiting", other.key());
		assertEquals(List.of("other"), other.args(), "a reason this client doesn't know");
		assertEquals(4, this.gate.stats().waits);
		this.gate.bound(bind, 6_000);
		assertNull(this.gate.waiting());
		assertNull(this.gate.hudLine(6_000));
		this.gate.waiting("busy", bind, 7_000);
		assertNull(this.gate.waiting(), "a late waiting after the answer changes nothing");
	}

	@Test
	void aWaitingAboutAnOlderBindIsIgnored() {
		this.gate.linkUp(SESSION, 0);
		this.gate.world(true, WORLD, 0);
		long first = this.bind(0);
		long second = this.bind(100);
		this.gate.waiting("busy", first, 200);
		assertNull(this.gate.waiting(), "another world's bind");
		assertTrue(this.glue.warnings.contains("session.stale_waiting"));
		this.gate.waiting("new_game", second, 300);
		assertEquals("new_game", this.gate.waiting());
	}

	@Test
	void switchedNoneLetsWhatWaitedGoOnEvenAtTheFirstPairing() {
		this.gate.linkUp(SESSION, 0);
		this.gate.world(true, WORLD, 0);
		long bind = this.bind(0);
		this.gate.admit(message(MsgTypes.PARTY_GIVE));
		this.gate.admit(message(MsgTypes.SAVE_LOADED));
		this.gate.bound(bind, SessionGate.SWITCHED_NONE, 10);
		assertEquals(State.OPEN, this.gate.state());
		assertEquals(List.of(false), this.glue.openings, "gen1-bridge says its game stayed on the save: no guessing");
		assertEquals(List.of(MsgTypes.PARTY_GIVE, MsgTypes.SAVE_LOADED), this.glue.replayed, "in order, to the server");
		assertFalse(this.gate.holdsPlayer(), "no fresh snapshot to wait for");
	}

	@Test
	void aSwitchDropsWhatWaitedEvenOnAReconnect() {
		this.pairedOnce();
		// The same gen1-bridge session and world (the guess would call it the same save), but gen1-bridge says it switched.
		this.gate.linkDown(100);
		this.gate.linkUp(SESSION, 200);
		long bind = this.bind(200);
		this.gate.admit(message(MsgTypes.PARTY_GIVE));
		for (String switched : List.of("title")) {
			this.gate.bound(bind, switched, 300);
		}
		assertEquals(List.of(true), this.glue.openings, "a switch: what waited may be another world's");
		assertTrue(this.glue.replayed.isEmpty());
		assertTrue(this.gate.holdsPlayer(), "until the fresh snapshot");
		this.gate.snapshot();
		assertFalse(this.gate.holdsPlayer());
	}

	@Test
	void everySwitchKindIsAFreshStart() {
		for (String switched : List.of("title", "saved", "moved")) {
			this.setUp();
			this.gate.linkUp(SESSION, 0);
			this.gate.world(true, WORLD, 0);
			this.gate.bound(this.bind(0), switched, 10);
			assertEquals(List.of(true), this.glue.openings, switched);
		}
	}

	@Test
	void saveLoadedWaitsLikeTheOtherStoryData() {
		assertTrue(SessionGate.UP.contains(MsgTypes.SAVE_LOADED));
		this.gate.linkUp(SESSION, 0);
		this.gate.world(true, WORLD, 0);
		this.bind(0);
		assertEquals(Admit.HELD, this.gate.admit(message(MsgTypes.SAVE_LOADED)));
		assertEquals(1, this.gate.heldCount());
	}

	@Test
	void aKantoWorldWithoutAnIdOpensUnpairedAfterAWhile() {
		this.gate.linkUp(SESSION, 0);
		this.gate.world(true, null, 0);
		this.gate.tick(SessionGate.NO_ID_TIMEOUT_MS);
		assertEquals(State.HELD, this.gate.state());
		this.gate.tick(SessionGate.NO_ID_TIMEOUT_MS + 1);
		assertEquals(State.OPEN, this.gate.state());
		assertTrue(this.gate.unpaired());
		assertEquals("kantobridge.hud.session.unpaired", this.gate.hudLine(SessionGate.NO_ID_TIMEOUT_MS + 2).key());
		// The id arriving later pairs the world after all.
		this.gate.world(true, WORLD, 20_000);
		assertEquals(State.HELD, this.gate.state());
		this.gate.bound(this.bind(20_000), 20_010);
		assertEquals(State.OPEN, this.gate.state());
		assertFalse(this.gate.unpaired());
	}

	@Test
	void aSnapshotThatNeverComesStopsHoldingThePlayerInTheEnd() {
		this.gate.linkUp(SESSION, 0);
		this.gate.world(true, WORLD, 0);
		this.gate.bound(this.bind(0), 0);
		assertTrue(this.gate.holdsPlayer());
		this.gate.tick(SessionGate.SETTLE_TIMEOUT_MS + 1);
		assertFalse(this.gate.holdsPlayer());
		assertTrue(this.glue.warnings.contains("session.no_snapshot"));
	}

	@Test
	void theHeldQueueIsBounded() {
		this.gate.linkUp(SESSION, 0);
		this.gate.world(true, WORLD, 0);
		this.bind(0);
		for (int i = 0; i < SessionGate.HELD_MAX + 6; i++) {
			this.gate.admit(message(MsgTypes.PARTY_GIVE));
		}
		assertEquals(SessionGate.HELD_MAX, this.gate.heldCount());
		assertEquals(6, this.gate.stats().dropped);
	}
}
