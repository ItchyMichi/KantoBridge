package dev.kantobridge.builder.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SE2 (docs/specs/STORY_EVENTS_PLAN.md §4): the g3sites reader, on the synthetic Gen 3 bundle and on bad input. */
class G3SitesTest {
	@Test
	void theSyntheticBundlesSitesRead() throws Exception {
		ExportBundle bundle = ExportBundle.open(TestData.syntheticGen3Bundle());
		G3Sites sites = bundle.g3sites();
		assertEquals(4, sites.sites().size());
		assertEquals(0, sites.baked());
		assertEquals(2, sites.of("EM_TEST_TOWN").size());
		assertTrue(sites.of("EM_NOWHERE").isEmpty());
		G3Sites.Site pond = sites.of("EM_TEST_ROUTE").get(0);
		assertEquals(4, pond.x());
		assertEquals(0, pond.y());
		assertEquals(new G3Sites.State(2, 0x29, 3, null), pond.own(), "the map's own water");
		assertEquals(List.of(new G3Sites.State(0, 0x00, 3, "layout")), pond.states());
	}

	@Test
	void anExportWithoutSitesHasNone() throws Exception {
		ExportBundle red = ExportBundle.open(TestData.syntheticBundle());
		assertTrue(red.g3sites().sites().isEmpty());
	}

	private static G3Sites read(String json) throws Exception {
		return G3Sites.parse(DataReader.of(ProtoJson.parseObject(json), "g3sites"));
	}

	@Test
	void badSitesAreRefused() throws Exception {
		String site = "{\"map\":\"EM_TEST_TOWN\",\"x\":1,\"y\":2,\"own\":{\"metatile\":0,\"coll\":0,\"elev\":3},"
				+ "\"states\":[{\"metatile\":3,\"coll\":7,\"elev\":3,\"source\":\"script\"}]}";
		G3Sites ok = read("{\"baked\":2,\"sites\":[" + site + "]}");
		assertEquals(2, ok.baked());
		assertNull(ok.sites().get(0).own().source());
		assertThrows(DataException.class, () -> read("{\"baked\":0,\"sites\":[" + site.replace("\"script\"", "\"magic\"") + "]}"));
		assertThrows(DataException.class, () -> read("{\"baked\":0,\"sites\":[" + site.replace(
				"[{\"metatile\":3,\"coll\":7,\"elev\":3,\"source\":\"script\"}]", "[]") + "]}"), "a site with no other state");
		assertThrows(DataException.class, () -> read("{\"baked\":0,\"sites\":[" + site.replace("\"metatile\":3", "\"metatile\":1024") + "]}"));
		assertThrows(DataException.class, () -> read("{\"baked\":0,\"sites\":[" + site.replace("\"elev\":3,\"source\"", "\"elev\":16,\"source\"") + "]}"));
		assertThrows(DataException.class, () -> read("{\"baked\":0,\"sites\":[" + site.replace("EM_TEST_TOWN", "em_town") + "]}"));
	}
}
