package dev.kantobridge.builder.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.world.Games;
import org.junit.jupiter.api.Test;

/**
 * The generated pack's path rules (docs/specs/VOXEL_BLOCKS_PLAN.md §4.7, §6.1 "SlotSpriteIdTest"): a key's sprite id
 * carries the art set, every segment lower-cased with anything outside {@code [a-z0-9_.-]} replaced by '_', and the tile
 * as four digits; which games draw each other's worlds; and the keys a texture pack may name (V6).
 */
class ArtKeysTest {
	@Test
	void keysBecomeSpriteIdsUnderTheArtSet() {
		assertEquals("block/blue/tile/tilesets.overworld/pallet/0042", ArtKeys.texturePath("blue", "tilesets.overworld", "PALLET", 42));
		assertEquals("kantobridge:block/red/tile/tilesets.test/test_palette/0006", ArtKeys.spriteId("red", "tilesets.test", "TEST_PALETTE", 6));
		assertEquals("assets/kantobridge/textures/block/red/tile/tilesets.test/test_palette/1023.png",
				ArtKeys.textureFile("red", "tilesets.test", "TEST_PALETTE", 1023));
		assertEquals("kantobridge:block/red/tile/a_b/c_d/0000", ArtKeys.spriteId("Red", "A:B", "C D", 0), "unsafe characters become '_'");
	}

	@Test
	void sheetsCarryTheArtSetToo() {
		assertEquals("kantobridge:textures/npc/blue/test_palette/sprite_test_girl.png", ArtKeys.npcTexture("blue", "TEST_PALETTE", "SPRITE_TEST_GIRL"));
		assertEquals("kantobridge:textures/player/red/test_palette/surf.png", ArtKeys.surfTexture("red", "TEST_PALETTE"));
		assertEquals("assets/kantobridge/textures/player/red/test_palette/surf.png", ArtKeys.file(ArtKeys.surfTexture("red", "TEST_PALETTE")));
	}

	@Test
	void packKeysParseInTheirCanonicalFormOnly() {
		// The voxel plan's V6 (§4.9): what a texture pack may name.
		for (String text : java.util.List.of("tile:tilesets.test/4", "tile:tilesets.test/1023@TEST_PALETTE", "sprite:SPRITE_TEST_GIRL",
				"cell:tilesets.test/0-1-2-3", "struct:tilesets.test/0123456789abcdef", "place:TEST_TOWN/2", "class:tree", "map:TEST_HOUSE")) {
			assertEquals(text, PackKey.parse(text).text(), "round trip");
		}
		PackKey tile = PackKey.parse("tile:tilesets.test/7@TEST_PALETTE");
		assertEquals(PackKey.Kind.TILE, tile.kind());
		assertEquals("tilesets.test", tile.scope());
		assertEquals(7, tile.tile());
		assertEquals("TEST_PALETTE", tile.palette());
		org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {0, 1, 2, 3}, PackKey.parse("cell:tilesets.test/0-1-2-3").tiles());
		assertEquals("0123456789abcdef", PackKey.parse("struct:tilesets.test/0123456789abcdef").sig());
		assertEquals(2, PackKey.parse("place:TEST_TOWN/2").n());
		assertEquals("cell:tilesets.test/4-5-6-7", PackKey.cell("tilesets.test", new int[] {4, 5, 6, 7}).text());
	}

	@Test
	void packKeysRefuseWhatAPackCannotName() {
		for (String text : java.util.List.of("", "tile", "tile:", "tile:tilesets.test/04", "tile:tilesets.test/1024", "tile:tilesets.test/-1",
				"tile:/4", "tile:tilesets.test/4@", "sprite:has space", "cell:tilesets.test/1-2-3", "cell:tilesets.test/1-2-3-4-5",
				"struct:tilesets.test/0123", "struct:tilesets.test/0123456789ABCDEF", "place:TEST_TOWN/x", "class:castle", "map:",
				"grid:tilesets.test/4", "TILE:tilesets.test/4")) {
			org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> PackKey.parse(text), text);
		}
		assertTrue(PackKey.CLASSES.containsAll(java.util.List.of("tree", "prop", "grass", "flower")));
	}

	@Test
	void redAndBlueDrawEachOther() {
		assertTrue(Games.compatible("red", "red"));
		assertTrue(Games.compatible("red", "blue"));
		assertTrue(Games.compatible("blue", "red"));
		assertFalse(Games.compatible("red", null));
		assertFalse(Games.compatible("gold", "red"), "an unknown game shares nothing");
		assertTrue(Games.compatible("gold", "gold"), "but always its own");
		assertEquals("gen1", Games.family("blue"));
	}
}
