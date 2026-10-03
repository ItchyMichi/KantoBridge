package dev.kantobridge.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The hand-off from the client's "Create Kanto world" flow to the integrated server: one request, taken once, only by
 * the server of the level it names, and never after it went stale.
 */
class BuildRequestTest {
	private final AtomicLong now = new AtomicLong(1_000);

	@BeforeEach
	void fakeClock() {
		BuildRequest.useClock(this.now::get);
	}

	@AfterEach
	void realClock() {
		BuildRequest.useClock(null);
	}

	private static BuildRequest submit(String level) {
		return BuildRequest.submit(level, Path.of("export", "red"), null, BuildOptions.defaults().withWritePack(false));
	}

	@Test
	void onlyTheNamedLevelTakesItAndOnlyOnce() {
		BuildRequest request = submit("Kanto (1)");
		assertSame(request, BuildRequest.pending());
		assertNull(BuildRequest.take("Kanto"), "another level's server leaves it alone");
		assertNull(BuildRequest.take("kanto (1)"), "folder names are matched exactly");
		assertSame(request, BuildRequest.take("Kanto (1)"));
		assertNull(BuildRequest.take("Kanto (1)"), "taken once");
		assertFalse(request.withdraw(), "a taken request can't be withdrawn");
		assertEquals(BuildStatus.State.WAITING, request.status().state());
		assertFalse(request.options().writePack());
		assertNull(request.maps(), "every map");
	}

	@Test
	void aWithdrawnOrReplacedRequestIsGone() {
		BuildRequest first = submit("Kanto");
		assertTrue(first.withdraw());
		assertNull(BuildRequest.take("Kanto"));

		BuildRequest stale = submit("Kanto");
		BuildRequest fresh = BuildRequest.submit("Kanto (1)", Path.of("export", "blue"), List.of("PALLET_TOWN"), BuildOptions.defaults());
		assertNull(BuildRequest.take("Kanto"), "a newer request replaced it");
		assertFalse(stale.withdraw());
		assertSame(fresh, BuildRequest.take("Kanto (1)"));
		assertEquals(List.of("PALLET_TOWN"), fresh.maps());
	}

	@Test
	void anUnclaimedRequestGoesStale() {
		submit("Kanto");
		this.now.addAndGet(BuildRequest.TTL_MS + 1);
		assertNull(BuildRequest.take("Kanto"));
		assertNull(BuildRequest.pending(), "the stale request is dropped");
		assertThrows(IllegalArgumentException.class, () -> submit(""));
	}

	@Test
	void statusFollowsTheBuild() {
		BuildStatus status = submit("Kanto").status();
		status.onPhase(BuildPhase.PLACING);
		status.onProgress(3, 12);
		assertEquals(BuildPhase.PLACING, status.phase());
		assertEquals(3, status.doneChunks());
		assertEquals(12, status.totalChunks());
		BuildReport failed = new BuildReport();
		failed.error = "bundle.missing";
		status.onFailed(failed);
		assertEquals(BuildStatus.State.FAILED, status.state());
		assertEquals("bundle.missing", status.error());
		status.cancel(); // before any task exists: remembered for when one starts
		assertTrue(status.cancelRequested());
	}
}
