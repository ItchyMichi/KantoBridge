package dev.kantobridge.builder.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.world.Games;
import org.junit.jupiter.api.Test;

/**
 * The generated pack's path rules (docs/specs/VOXEL_BLOCKS_PLAN.md §4.7, §6.1 "SlotSpriteIdTest"): a key's sprite id
 * carries the art set, every segment lower-cased with anything outside {@code [a-z0-9_.-]} replaced by '_', and the tile
 * as four digits; and which games draw each other's worlds.
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
