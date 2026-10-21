package dev.kantobridge.builder.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.TestData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * MG9 (G2E): the Gen 2 export's typed readers on the synthetic Gen 2 bundle (tools/fake_gen1/synth2.py): maps in blocks
 * (block 0 as the border), art variants and the meanings of their collision bytes, both surface areas' layouts, the
 * grass and water tables, the pages and animated tiles in Gen 3's shapes, the object sheets.
 */
class G2ReadersTest {
	@Test
	void theSyntheticGen2BundleOpensAndReadsAsGen2() throws Exception {
		ExportBundle bundle = ExportBundle.open(TestData.syntheticGen2Bundle());
		assertEquals("crystal", bundle.game());
		assertTrue(bundle.isGen2());
		assertFalse(bundle.isGen3());
		assertEquals(List.of("G2_TEST_CAVE", "G2_TEST_CAVE_B1F", "G2_TEST_HOUSE", "G2_TEST_KANTO_TOWN", "G2_TEST_ROUTE", "G2_TEST_TOWN"), bundle.ids("g2map"));
		G2Map town = bundle.g2map("G2_TEST_TOWN");
		assertEquals(5, town.w());
		assertEquals(4, town.h());
		assertEquals(10, town.cellWidth());
		assertEquals(5, town.dataBlock(2, 1), "the house's door block");
		assertEquals(7, town.border());
		assertEquals("TILESET_TEST_1", town.variant());
		assertEquals("TOWN", town.env());
		assertTrue(town.outdoor());
		assertFalse(town.caveLike());
		assertEquals("johto", town.area());
		assertEquals("DAY", town.daytime());
		assertEquals(List.of(new G2Map.Connection("north", "G2_TEST_ROUTE", 1)), town.connections());
		assertEquals(new G2Map.Warp(4, 3, "G2_TEST_HOUSE", 0, false), town.warps().get(0));
		assertEquals(List.of(new G2Map.Event(6, 2, 0)), town.signs());
		assertEquals(5, town.objects().size());
		G2Map.MapObject ball = town.objects().get(1);
		assertEquals("O2", ball.name());
		assertEquals("item", ball.kind());
		assertEquals(0, ball.palette());
		assertEquals(40, ball.flag());
		G2Map.MapObject doll = town.objects().get(4);
		assertNull(doll.sprite());
		assertEquals(0xF3, doll.var());
		assertEquals("asym", doll.big());
		assertFalse(town.objects().get(2).stays(), "a walker");
		G2Map cave = bundle.g2map("G2_TEST_CAVE");
		assertTrue(cave.dark());
		assertTrue(cave.caveLike());
		assertEquals("NITE", cave.daytime());
		assertEquals("kanto", bundle.g2map("G2_TEST_KANTO_TOWN").area());
		assertArrayEquals(new int[] {-1, 2}, bundle.g2map("G2_TEST_HOUSE").objects().get(0).hours());

		G2Tiles t = bundle.g2tiles("TILESET_TEST_1");
		assertEquals("TILESET_TEST", t.tileset());
		assertEquals("ROOF_TEST", t.roof());
		assertEquals(14, t.blockCount());
		assertEquals(0x71, t.coll(5, 0, 1), "the door's bottom-left cell");
		assertEquals(-1, t.tile(99, 0, 0));
		assertEquals(0xFF, t.coll(99, 0, 0));
		G2Tiles.Meaning door = t.meaning(0x71);
		assertEquals("land", door.perm());
		assertEquals("door", door.warp());
		assertTrue(door.facesDown());
		G2Tiles.Meaning fence = t.meaning(0xB2);
		assertEquals(List.of("up"), fence.sides());
		assertEquals(Map.of("down", "down"), fence.neighbor());
		assertEquals(List.of("down"), t.meaning(0xA3).ledge());
		assertTrue(t.meaning(0x24).whirlpool());
		assertTrue(t.meaning(0x12).cutTree());
		assertFalse(t.knows(0x33));
		assertEquals("wall", t.meaning(0x33).perm(), "a byte the export doesn't describe is a wall");
		assertEquals("edge", bundle.g2tiles("TILESET_TEST_HOUSE_1").meaning(0x70).warp());
		assertEquals("down", bundle.g2tiles("TILESET_TEST_HOUSE_1").meaning(0x70).mat());

		List<G2Data.Layout> layouts = bundle.g2layouts();
		assertEquals(2, layouts.size());
		assertEquals("johto", layouts.get(0).area());
		assertEquals("G2_TEST_TOWN", layouts.get(0).root());
		assertEquals(new G3Data.Rect(1, -3, 3, 3), layouts.get(0).maps().get("G2_TEST_ROUTE"));
		assertEquals(new G3Data.Start("G2_TEST_HOUSE", 1, 4), layouts.get(0).start());
		assertEquals("G2_TEST_KANTO_TOWN", layouts.get(1).root());
		assertNull(layouts.get(1).start());

		G2Data.Encounters enc = bundle.g2encounters();
		assertNotNull(enc);
		G2Data.Grass route = enc.grass().get("G2_TEST_ROUTE");
		assertEquals(30, route.rates().get("NITE"));
		assertEquals(7, route.slots().get("DAY").size());
		assertEquals(new G2Data.Slot(163, 5), route.slots().get("NITE").get(0));
		assertEquals(30, enc.grassChance(0));
		assertEquals(1, enc.grassChance(6));
		assertNull(enc.grassChance(7));
		assertEquals(3, enc.water().get("G2_TEST_TOWN").slots().size());
		assertEquals(161, bundle.g2names().species().get("TEST_SENTRET"));

		G3Data.Image page = bundle.g2image("g2q.0");
		assertEquals(256, page.w());
		assertTrue(bundle.hasBakedPage(0));
		assertFalse(bundle.hasBakedPage(9));
		assertArrayEquals(page.png(), bundle.bakedPage(0).png());
		G3Data.Anims anims = bundle.g2anims();
		assertFalse(anims.byQuarter().isEmpty());
		int water = t.tile(3, 0, 0);
		assertTrue(anims.byQuarter().containsKey(water), "the water's tile is animated");
		G2Data.Sprite lass = bundle.g2sprite("SPRITE_TEST_LASS");
		assertEquals(6, lass.frames());
		assertEquals(96, lass.h());
		assertTrue(lass.walker());
		G2Data.Sprite red = bundle.g2sprite("SPRITE_TEST_BALL.p0");
		assertEquals("SPRITE_TEST_BALL", red.sprite());
		assertEquals(0, red.palette());
		assertEquals(3, bundle.swaps().sites().size());
		assertEquals("whirlpool", bundle.swaps().sites().get(2).kind());
	}
}
