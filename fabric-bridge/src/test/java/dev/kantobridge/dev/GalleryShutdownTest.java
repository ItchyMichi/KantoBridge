package dev.kantobridge.dev;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GalleryShutdownTest {
	static final Path WORLD = Path.of("local/marked-gallery").toAbsolutePath();
	static final GalleryShutdown.Work QUIET = new GalleryShutdown.Work(0, 0, 0);
	@Test void shutdownRequiresSameMarkedServerWorldAndCannotFollowWorldChanges() {
		Object server = new Object();
		assertThrows(IllegalArgumentException.class, () -> new GalleryShutdown(server, WORLD, WORLD, false, 0));
		assertThrows(IllegalArgumentException.class, () -> new GalleryShutdown(server, WORLD, WORLD.resolve("other"), true, 0));
		for (int mode = 0; mode < 3; mode++) {
			var gate = new GalleryShutdown(server, WORLD, WORLD, true, 0);
			assertEquals(GalleryShutdown.Result.CANCELLED, gate.poll(mode == 0 ? new Object() : server, mode == 1 ? WORLD.resolve("other") : WORLD, mode != 2, 200, QUIET));
			assertEquals(GalleryShutdown.Result.CANCELLED, gate.poll(server, WORLD, true, 201, QUIET));
		}
	}
	@Test void distinctServerTicksGraceAndEveryGenerationDependencyMustDrain() {
		Object server = new Object(); var gate = new GalleryShutdown(server, WORLD, WORLD, true, 0);
		for (int i = 0; i < 500; i++) assertEquals(GalleryShutdown.Result.WAITING, gate.poll(server, WORLD, true, 1, QUIET));
		assertEquals(1, gate.quietTicks());
		for (int tick = 2; tick < 100; tick++) assertEquals(GalleryShutdown.Result.WAITING, gate.poll(server, WORLD, true, tick, QUIET));
		for (var busy : new GalleryShutdown.Work[]{new GalleryShutdown.Work(1,0,0), new GalleryShutdown.Work(0,1,0), new GalleryShutdown.Work(0,0,1)}) {
			assertEquals(GalleryShutdown.Result.WAITING, gate.poll(server, WORLD, true, 100 + busy.generationReferences() + 2 * busy.pendingGeneration() + 3 * busy.pendingSaveDependencies(), busy));
			assertEquals(0, gate.quietTicks());
		}
		for (int tick = 104; tick < 123; tick++) assertEquals(GalleryShutdown.Result.WAITING, gate.poll(server, WORLD, true, tick, QUIET));
		assertEquals(GalleryShutdown.Result.STOP, gate.poll(server, WORLD, true, 123, QUIET));
		assertEquals(GalleryShutdown.Result.WAITING, gate.poll(server, WORLD, true, 124, QUIET));
	}
	@Test void stillBusyAtTimeoutNeverRequestsForcedStop() {
		Object server = new Object(); var gate = new GalleryShutdown(server, WORLD, WORLD, true, 0);
		assertEquals(GalleryShutdown.Result.TIMED_OUT, gate.poll(server, WORLD, true, GalleryShutdown.TIMEOUT_TICKS, new GalleryShutdown.Work(1,0,0)));
		assertEquals(GalleryShutdown.Result.TIMED_OUT, gate.poll(server, WORLD, true, GalleryShutdown.TIMEOUT_TICKS+1, QUIET));
	}
}
