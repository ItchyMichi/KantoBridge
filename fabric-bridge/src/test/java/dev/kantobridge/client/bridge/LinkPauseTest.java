package dev.kantobridge.client.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The pause overlay's decision (M10 crash recovery): a grace before it shows, what it says (not connected, not
 * answering, a version mismatch, another refusal), the retry countdown, closing on the reconnect, and never during the
 * export and build flows or outside a Kanto world.
 */
class LinkPauseTest {
	private final LinkPause pause = new LinkPause();

	@Test
	void showsAfterTheGraceAndClosesOnTheReconnect() {
		this.pause.state(ConnectionState.CONNECTED, "blue", -1, 0);
		assertFalse(this.pause.tick(100, true, false), "a ready link shows nothing");
		this.pause.state(ConnectionState.DISCONNECTED, "eof", -1, 1_000);
		assertFalse(this.pause.tick(1_000, true, false));
		assertFalse(this.pause.tick(1_000 + LinkPause.GRACE_MS, true, false), "a quick reconnect never flashes it");
		assertNull(this.pause.view(2_000, -1));
		assertTrue(this.pause.tick(1_001 + LinkPause.GRACE_MS, true, false));
		assertTrue(this.pause.shown());
		LinkPause.View view = this.pause.view(3_000, 3_500);
		assertNotNull(view);
		assertEquals(LinkPause.Kind.NOT_CONNECTED, view.kind());
		assertEquals(500, view.retryInMs(), "the countdown to the next try");
		assertEquals(-1, this.pause.view(3_000, -1).retryInMs(), "-1 while an attempt runs");
		assertEquals(0, this.pause.view(4_000, 3_500).retryInMs(), "never below zero");
		assertEquals(1, this.pause.shows());

		this.pause.state(ConnectionState.CONNECTING, null, -1, 3_600);
		this.pause.state(ConnectionState.HANDSHAKE, null, -1, 3_610);
		assertTrue(this.pause.tick(3_620, true, false), "still down until hello.ok");
		this.pause.state(ConnectionState.CONNECTED, "blue", -1, 3_700);
		assertFalse(this.pause.tick(3_700, true, false), "the reconnect closes it at once");
		assertFalse(this.pause.shown());
		assertNull(this.pause.view(3_700, -1));
	}

	@Test
	void aLinkThatOpensButNeverAnswersIsNotAnswering() {
		this.pause.state(ConnectionState.CONNECTING, null, -1, 0);
		this.pause.state(ConnectionState.HANDSHAKE, null, -1, 10);
		assertFalse(this.pause.tick(10, true, false));
		assertTrue(this.pause.tick(2_000, true, false));
		assertEquals(LinkPause.Kind.NOT_CONNECTED, this.pause.view(2_000, -1).kind(), "connecting, for now");
		assertEquals(LinkPause.Kind.NOT_ANSWERING, this.pause.view(10 + LinkPause.NOT_ANSWERING_MS, -1).kind(),
				"no hello.ok for 10 s: Gen1Recomp may show an error");
		// The session's silence timeout drops it; the next attempts stay "not answering" ...
		this.pause.state(ConnectionState.DISCONNECTED, "timeout", -1, 10_010);
		this.pause.tick(10_020, true, false);
		assertEquals(LinkPause.Kind.NOT_ANSWERING, this.pause.view(10_020, 10_520).kind());
		this.pause.state(ConnectionState.CONNECTING, null, -1, 10_520);
		this.pause.state(ConnectionState.HANDSHAKE, null, -1, 10_530);
		this.pause.tick(10_600, true, false);
		assertEquals(LinkPause.Kind.NOT_ANSWERING, this.pause.view(10_600, -1).kind());
		// ... until Gen1Recomp is gone altogether (the connection is refused).
		this.pause.state(ConnectionState.DISCONNECTED, "unreachable", -1, 21_000);
		this.pause.tick(21_000, true, false);
		assertEquals(LinkPause.Kind.NOT_CONNECTED, this.pause.view(21_000, 21_500).kind());
	}

	@Test
	void aSilentLinkAfterAHandshakeIsNotAnsweringToo() {
		this.pause.state(ConnectionState.CONNECTED, "blue", -1, 0);
		this.pause.state(ConnectionState.DISCONNECTED, "timeout", -1, 12_000);
		this.pause.tick(12_000, true, false);
		assertTrue(this.pause.tick(14_000, true, false));
		assertEquals(LinkPause.Kind.NOT_ANSWERING, this.pause.view(14_000, -1).kind());
	}

	@Test
	void aRefusalNamesTheVersionsOrTheReason() {
		this.pause.state(ConnectionState.HANDSHAKE, null, -1, 0);
		this.pause.state(ConnectionState.REJECTED, "protocol", 2, 5);
		this.pause.tick(10, true, false);
		assertTrue(this.pause.tick(2_000, true, false));
		LinkPause.View view = this.pause.view(2_000, 5_005);
		assertEquals(LinkPause.Kind.VERSION, view.kind());
		assertEquals(2, view.theirProtocol(), "gen1-bridge's number; the screen adds ours");
		assertNull(view.reason());
		// Retrying keeps saying so ...
		this.pause.state(ConnectionState.CONNECTING, null, -1, 5_005);
		this.pause.state(ConnectionState.HANDSHAKE, null, -1, 5_010);
		this.pause.tick(5_020, true, false);
		assertEquals(LinkPause.Kind.VERSION, this.pause.view(5_020, -1).kind());
		// ... and another reason is another refusal.
		this.pause.state(ConnectionState.REJECTED, "busy", 1, 5_030);
		this.pause.tick(5_040, true, false);
		view = this.pause.view(5_040, 10_030);
		assertEquals(LinkPause.Kind.REFUSED, view.kind());
		assertEquals("busy", view.reason());
		assertEquals(-1, view.theirProtocol());
		// Gen1Recomp closed meanwhile: plain "not connected".
		this.pause.state(ConnectionState.DISCONNECTED, "unreachable", -1, 10_040);
		this.pause.tick(10_050, true, false);
		assertEquals(LinkPause.Kind.NOT_CONNECTED, this.pause.view(10_050, 10_550).kind());
	}

	@Test
	void neverDuringTheExportOrBuildFlowsAndTheGraceStartsOverAfter() {
		this.pause.state(ConnectionState.CONNECTED, "blue", -1, 0);
		// The export pauses the link on purpose ...
		this.pause.state(ConnectionState.PAUSED, "export", -1, 100);
		assertFalse(this.pause.tick(10_000, true, false), "a paused link is the export's");
		// ... and the reconnect after it, or a build flow that holds the screen, is excluded too.
		this.pause.state(ConnectionState.CONNECTING, null, -1, 20_000);
		assertFalse(this.pause.tick(30_000, true, true), "excluded while a flow runs");
		// The flow ends with the link still down: the grace starts now, not when the link went.
		assertFalse(this.pause.tick(30_050, true, false));
		assertFalse(this.pause.tick(30_050 + LinkPause.GRACE_MS, true, false));
		assertTrue(this.pause.tick(30_051 + LinkPause.GRACE_MS, true, false));
	}

	@Test
	void onlyInAKantoWorld() {
		this.pause.state(ConnectionState.DISCONNECTED, "unreachable", -1, 0);
		assertFalse(this.pause.tick(60_000, false, false), "on the title screen or in another world the corner line says enough");
		assertFalse(this.pause.shown());
		// Entering a Kanto world with the link down: the grace counts from here.
		assertFalse(this.pause.tick(70_000, true, false));
		assertTrue(this.pause.tick(70_000 + LinkPause.GRACE_MS + 1, true, false));
		// Leaving it closes the overlay.
		assertFalse(this.pause.tick(80_000, false, false));
		assertFalse(this.pause.shown());
		assertEquals(1, this.pause.shows());
	}

	@Test
	void stoppedMeansTheGameIsClosing() {
		this.pause.state(ConnectionState.STOPPED, null, -1, 0);
		assertFalse(this.pause.tick(10_000, true, false));
	}
}
