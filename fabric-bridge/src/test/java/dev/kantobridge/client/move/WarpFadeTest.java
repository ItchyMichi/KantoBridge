package dev.kantobridge.client.move;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.world.Dir;
import dev.kantobridge.world.Place;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The warp fade's state machine: fade, request, answer, wait for the sections, fade in; and every timeout. */
class WarpFadeTest {
	private static final Place HOUSE = new Place("TEST_HOUSE", 4, 8, Dir.UP);
	private static final Place TOWN = new Place("TEST_TOWN", 5, 6, Dir.DOWN);

	static final class FakePort implements WarpFade.Port {
		final List<String> calls = new ArrayList<>();
		boolean ready;
		boolean sendable = true;

		@Override
		public boolean request(Place to, @Nullable String kind) {
			this.calls.add("request " + to + " " + kind);
			return this.sendable;
		}

		@Override
		public boolean destinationReady() {
			return this.ready;
		}

		@Override
		public void warn(String code) {
			this.calls.add("warn " + code);
		}

		@Override
		public void ended() {
			this.calls.add("ended");
		}

		List<String> take() {
			List<String> out = List.copyOf(this.calls);
			this.calls.clear();
			return out;
		}
	}

	/** Ticks every 50 ms from {@code from} until {@code to} (inclusive). */
	private static void run(WarpFade fade, long from, long to) {
		for (long t = from; t <= to; t += 50) {
			fade.tick(t);
		}
	}

	@Test
	void aDoorWarpFadesOutTeleportsAndFadesIn() {
		FakePort port = new FakePort();
		WarpFade fade = new WarpFade(port);
		assertFalse(fade.active());
		fade.start("door", 1000);
		assertTrue(fade.holding());
		assertEquals(WarpFade.BLACK, fade.colour());
		assertEquals(0.0F, fade.alpha(1000), 1.0E-6);
		assertEquals(0.5F, fade.alpha(1125), 1.0E-6);
		run(fade, 1050, 1250);
		assertEquals(WarpFade.Phase.DARK, fade.phase());
		assertEquals(List.of(), port.take(), "nothing to request before the arrival");
		fade.arrive(HOUSE, "door", 1530);
		fade.tick(1550);
		assertEquals(List.of("request TEST_HOUSE:4:8:up door"), port.take());
		assertTrue(fade.awaitingAnswer());
		run(fade, 1600, 1700);
		assertEquals(List.of(), port.take(), "requested once");
		fade.done(true, 1720);
		run(fade, 1750, 1900);
		assertEquals(WarpFade.Phase.DARK, fade.phase(), "waits until the destination is drawn");
		port.ready = true;
		fade.tick(1950);
		assertEquals(List.of("ended"), port.take());
		assertEquals(WarpFade.Phase.IN, fade.phase());
		assertFalse(fade.holding(), "the player is free while the screen opens");
		assertEquals(0.6F, fade.alpha(2050), 1.0E-6);
		run(fade, 2000, 2200);
		assertEquals(WarpFade.Phase.IDLE, fade.phase());
		assertEquals(0.0F, fade.alpha(2200), 1.0E-6);
		assertEquals(1, fade.warps());
		assertEquals(0, fade.warnings());
	}

	@Test
	void anEarlyArrivalIsRequestedOnceDark() {
		FakePort port = new FakePort();
		WarpFade fade = new WarpFade(port);
		fade.start("stairs", 0);
		fade.arrive(HOUSE, "stairs", 10);
		run(fade, 50, 200);
		assertEquals(List.of(), port.take(), "the screen isn't dark yet: no visible jump");
		fade.tick(250);
		assertEquals(List.of("request TEST_HOUSE:4:8:up stairs"), port.take());
	}

	@Test
	void anArrivalWithoutStartFadesFirst() {
		FakePort port = new FakePort();
		WarpFade fade = new WarpFade(port);
		fade.arrive(TOWN, "edge", 0);
		assertTrue(fade.holding());
		fade.tick(100);
		assertEquals(List.of(), port.take());
		fade.tick(250);
		assertEquals(List.of("request TEST_TOWN:5:6:down edge"), port.take());
	}

	@Test
	void everyWaitTimesOutWithACountedWarning() {
		FakePort port = new FakePort();
		WarpFade fade = new WarpFade(port);
		fade.start("door", 0);
		run(fade, 50, WarpFade.ARRIVE_TIMEOUT_MS);
		assertEquals(List.of(), port.take());
		fade.tick(WarpFade.ARRIVE_TIMEOUT_MS + 50);
		assertEquals(List.of("warn arrive_timeout", "ended"), port.take());
		assertEquals(WarpFade.Phase.IN, fade.phase());

		fade.arrive(HOUSE, "door", 20_000);
		fade.tick(20_250);
		port.take();
		run(fade, 20_300, 20_250 + WarpFade.DONE_TIMEOUT_MS);
		assertEquals(List.of(), port.take());
		fade.tick(20_300 + WarpFade.DONE_TIMEOUT_MS);
		assertEquals(List.of("warn done_timeout", "ended"), port.take());

		fade.arrive(HOUSE, "door", 40_000);
		fade.tick(40_250);
		port.take();
		fade.done(true, 40_300);
		run(fade, 40_350, 40_300 + WarpFade.READY_TIMEOUT_MS);
		assertEquals(List.of(), port.take());
		fade.tick(40_350 + WarpFade.READY_TIMEOUT_MS);
		assertEquals(List.of("warn ready_timeout", "ended"), port.take());

		fade.arrive(HOUSE, "door", 60_000);
		fade.tick(60_250);
		port.take();
		fade.done(false, 60_260);
		fade.tick(60_300);
		assertEquals(List.of("warn refused", "ended"), port.take());

		port.sendable = false;
		fade.arrive(HOUSE, "door", 80_000);
		fade.tick(80_250);
		assertEquals(List.of("request TEST_HOUSE:4:8:up door", "warn request_failed", "ended"), port.take());
		assertEquals(5, fade.warnings());
	}

	@Test
	void aSyncTeleportUsesTheSameMachineWithoutAKind() {
		FakePort port = new FakePort();
		WarpFade fade = new WarpFade(port);
		assertTrue(fade.sync(TOWN, 0));
		assertFalse(fade.sync(HOUSE, 10), "a warp is already fading");
		fade.tick(250);
		assertEquals(List.of("request TEST_TOWN:5:6:down null"), port.take());
		fade.done(true, 300);
		port.ready = true;
		fade.tick(350);
		assertEquals(List.of("ended"), port.take());
		assertEquals(WarpFade.BLACK, fade.colour());
	}

	@Test
	void flyAndFriendsFadeToWhite() {
		WarpFade fade = new WarpFade(new FakePort());
		for (String kind : List.of("fly", "teleport", "dig", "rope", "pad", "hole")) {
			fade.reset();
			fade.start(kind, 0);
			assertEquals(WarpFade.WHITE, fade.colour(), kind);
		}
		for (String kind : List.of("door", "stairs", "edge", "blackout", "script", "elevator")) {
			fade.reset();
			fade.start(kind, 0);
			assertEquals(WarpFade.BLACK, fade.colour(), kind);
		}
	}

	@Test
	void aBlackoutFadesToBlackSlowlyAndComesBackSlower() {
		FakePort port = new FakePort();
		WarpFade fade = new WarpFade(port);
		fade.start(WarpFade.BLACKOUT, 0);
		assertEquals(WarpFade.BLACK, fade.colour());
		assertEquals(0.25F, fade.alpha(250), 1.0E-6, "a quarter of the way after a usual fade's whole time");
		run(fade, 50, 950);
		assertEquals(WarpFade.Phase.OUT, fade.phase(), "still fading out");
		fade.arrive(new Place("TEST_TOWN", 3, 4, Dir.DOWN), WarpFade.BLACKOUT, 960);
		fade.tick(1000);
		assertEquals(WarpFade.Phase.DARK, fade.phase());
		assertEquals(List.of("request TEST_TOWN:3:4:down blackout"), port.take(), "requested once dark, with the hidden game's kind");
		fade.tick(1050);
		assertEquals(List.of(), port.take());
		fade.done(true, 1100);
		port.ready = true;
		fade.tick(1150);
		assertEquals(List.of("ended"), port.take());
		assertEquals(WarpFade.Phase.IN, fade.phase());
		assertEquals(0.5F, fade.alpha(1400), 1.0E-6, "half-way back after 250 ms");
		fade.tick(1600);
		assertEquals(WarpFade.Phase.IN, fade.phase());
		fade.tick(1650);
		assertEquals(WarpFade.Phase.IDLE, fade.phase());
		// The next ordinary warp has the usual pace again.
		fade.start("door", 2000);
		assertEquals(0.5F, fade.alpha(2125), 1.0E-6);
	}

	@Test
	void aNewWarpDuringTheFadeInFadesOutFromWhereItWas() {
		FakePort port = new FakePort();
		WarpFade fade = new WarpFade(port);
		fade.arrive(HOUSE, "door", 0);
		fade.tick(250);
		fade.done(true, 260);
		port.ready = true;
		fade.tick(300);
		assertEquals(WarpFade.Phase.IN, fade.phase());
		// Half-way through the fade-in (alpha 0.5), the next warp starts.
		fade.start("stairs", 425);
		assertEquals(0.5F, fade.alpha(425), 1.0E-6);
		assertEquals(1.0F, fade.alpha(550), 1.0E-6, "the rest of the way at the usual rate");
		fade.tick(550);
		assertEquals(WarpFade.Phase.DARK, fade.phase());
	}

	@Test
	void unexpectedAnswersAreIgnored() {
		FakePort port = new FakePort();
		WarpFade fade = new WarpFade(port);
		fade.done(true, 0);
		assertEquals(WarpFade.Phase.IDLE, fade.phase());
		fade.start("door", 0);
		fade.done(true, 10);
		fade.arrive(HOUSE, "door", 20);
		fade.tick(250);
		port.ready = true;
		fade.tick(300);
		assertEquals(WarpFade.Phase.DARK, fade.phase(), "the early answer belonged to nothing");
	}
}
