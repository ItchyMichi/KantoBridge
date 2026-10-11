package dev.kantobridge.story;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.proto.ProtoJson;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Wild Routes progress (WR5): the snapshot replaces it all, {@code route.progress} the zones it lists; bad parts are skipped. */
class RouteProgressTest {
	@Test
	void theSnapshotReplacesItAllAndProgressTheZonesItLists() throws Exception {
		RouteProgress progress = new RouteProgress();
		long v0 = progress.version();
		progress.replaceAll(ProtoJson.parseObject("{\"a_zone\":{\"taken\":[\"a1.i1\",\"a2.i1\"],\"wins\":{\"a1.t1\":2}},"
				+ "\"b_zone\":{\"taken\":[\"a1.i3\"],\"wins\":{}}}"));
		assertTrue(progress.version() > v0);
		assertTrue(progress.taken("a_zone", "a1.i1") && progress.taken("a_zone", "a2.i1") && progress.taken("b_zone", "a1.i3"));
		assertEquals(2, progress.wins("a_zone", "a1.t1"));
		assertEquals(0, progress.wins("a_zone", "a1.t2"));
		// route.progress: a_zone is replaced whole, b_zone stays
		progress.update(ProtoJson.parseObject("{\"a_zone\":{\"taken\":[\"a3.i1\"],\"wins\":{\"a1.t1\":3}}}"));
		assertEquals(Set.of("a3.i1"), progress.taken("a_zone"));
		assertEquals(3, progress.wins("a_zone", "a1.t1"));
		assertTrue(progress.taken("b_zone", "a1.i3"));
		// a snapshot without routes forgets everything
		progress.replaceAll(null);
		assertFalse(progress.taken("b_zone", "a1.i3"));
	}

	@Test
	void malformedPartsAreSkipped() throws Exception {
		RouteProgress progress = new RouteProgress();
		progress.replaceAll(ProtoJson.parseObject("{\"Bad Zone\":{\"taken\":[\"a1.i1\"],\"wins\":{}},"
				+ "\"ok_zone\":{\"taken\":[\"a1.i1\",\"NOT OK\",7],\"wins\":{\"a1.t1\":0,\"a1.t2\":1.5,\"a1.t3\":4}},\"odd\":3}"));
		assertFalse(progress.taken("Bad Zone", "a1.i1"));
		assertEquals(Set.of("a1.i1"), progress.taken("ok_zone"));
		assertEquals(0, progress.wins("ok_zone", "a1.t1"));
		assertEquals(0, progress.wins("ok_zone", "a1.t2"));
		assertEquals(4, progress.wins("ok_zone", "a1.t3"));
	}
}
