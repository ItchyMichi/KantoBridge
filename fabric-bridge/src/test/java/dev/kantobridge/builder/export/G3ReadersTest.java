package dev.kantobridge.builder.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.TestData;
import java.util.List;
import org.junit.jupiter.api.Test;

/** MG7 (G3D): the Gen 3 export's typed readers on the synthetic Gen 3 bundle (tools/fake_gen1/synth3.py). */
class G3ReadersTest {
	@Test
	void theSyntheticGen3BundleOpensAndReadsAsGen3() throws Exception {
		ExportBundle bundle = ExportBundle.open(TestData.syntheticGen3Bundle());
		assertEquals("emerald", bundle.game());
		assertTrue(bundle.isGen3());
		assertEquals(List.of("EM_TEST_HOUSE", "EM_TEST_ROUTE", "EM_TEST_TOWN"), bundle.ids("g3map"));
		G3Map town = bundle.g3map("EM_TEST_TOWN");
		assertEquals(8, town.w());
		assertEquals(6, town.h());
		assertEquals("TEST_PAIR_A", town.pair());
		assertEquals(4, town.mid(3, 3), "the house's door");
		assertEquals(0x71, town.coll(3, 3));
		assertEquals(0xA3, town.coll(0, 4), "a ledge jumping south");
		assertEquals(List.of(new G3Map.Connection("north", "EM_TEST_ROUTE", 1)), town.connections());
		assertEquals(new G3Map.Warp(3, 3, "EM_TEST_HOUSE", 0, false), town.warps().get(0));
		assertEquals(3, town.objects().size());
		assertEquals(257, town.objects().get(1).flag());
		assertEquals(Boolean.TRUE, town.objects().get(0).stays(), "a standing person");
		assertEquals(Boolean.FALSE, town.objects().get(2).stays(), "a walker");
		assertEquals("O3", town.objects().get(2).name());
		assertEquals(0, bundle.g3map("EM_TEST_ROUTE").elev(2, 3), "the raised patch's stairs");
		assertEquals(4, bundle.g3map("EM_TEST_ROUTE").elev(0, 3));
		G3Map house = bundle.g3map("EM_TEST_HOUSE");
		assertEquals(4, house.w());
		assertEquals(3, house.trueW());
		assertFalse(house.inMap(3, 0));
		assertTrue(house.inGrid(3, 0));
		G3Tiles a = bundle.g3tiles("TEST_PAIR_A");
		int[] ground = a.quarters(0);
		assertNotNull(ground);
		assertEquals(1, java.util.Arrays.stream(ground).distinct().count(), "a plain metatile is one quarter");
		assertTrue(a.over(5));
		assertEquals("MB_TALL_GRASS", a.behaviorName(1));
		assertNull(a.quarters(99));
		assertTrue(a.knowsLand());
		assertTrue(a.landEncounter(1), "tall grass rolls land encounters");
		assertFalse(a.landEncounter(0));
		assertFalse(a.landEncounter(99));
		assertTrue(bundle.g3tiles("TEST_PAIR_B").knowsLand());
		G3Data.Layout layout = bundle.g3layout();
		assertEquals("EM_TEST_TOWN", layout.root());
		assertEquals(new G3Data.Rect(1, -5, 6, 5), layout.maps().get("EM_TEST_ROUTE"));
		G3Data.Encounters enc = bundle.g3encounters();
		assertNotNull(enc);
		G3Data.MapTables route = enc.maps().get("EM_TEST_ROUTE");
		assertEquals(12, route.land().slots().size());
		assertEquals(252, route.land().slots().get(0).dex());
		assertNull(route.fish());
		assertEquals(20, enc.landWeight(0));
		assertEquals(1, enc.landWeight(11));
		assertNull(enc.landWeight(12));
		G3Data.Names names = bundle.g3names();
		assertNotNull(names);
		assertEquals(252, names.species().get("SPECIES_TEST_TREECKO"));
		G3Data.Image page = bundle.g3image("g3q.0");
		assertEquals(256, page.w());
		assertArrayEquals(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, java.util.Arrays.copyOf(page.png(), 4));
		G3Data.Sprite person = bundle.g3sprite("OBJ_EVENT_GFX_TEST_PERSON");
		assertEquals(9, person.frames());
	}
}
