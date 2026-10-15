package dev.kantobridge.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The shape documents' wire form (the follow-up's item 4): Base64 of raw deflate, back to the same text or bytes; a
 * document past the size limit, not Base64, not deflate or (for text) not UTF-8 is refused; the map's own wire form is
 * the same encoding.
 */
class ShapeDocsTest {
	@Test
	void textAndBytesComeBackAsTheyWent() throws Exception {
		String text = "{\"geoms\":[\"cube:0|4,4,3,3,3,3\"],\"name\":\"éè Pokémon\"}".repeat(200);
		String wire = ShapeDocs.wire(text);
		assertTrue(wire.length() < text.length() / 5, "a repetitive index deflates well: " + wire.length());
		assertEquals(text, ShapeDocs.unwireText(wire, 1 << 20));
		byte[] bytes = new byte[5000];
		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = (byte) (i * 31);
		}
		assertArrayEquals(bytes, ShapeDocs.unwire(ShapeDocs.wire(bytes), 1 << 20));
		assertArrayEquals(bytes, ShapeDocs.inflate(ShapeDocs.deflate(bytes), 5000), "exactly the limit is fine");
	}

	@Test
	void badDocumentsAreRefused() {
		assertEquals("too large", assertThrows(DataException.class, () -> ShapeDocs.unwire(ShapeDocs.wire(new byte[5001]), 5000)).problem());
		assertEquals("not base64", assertThrows(DataException.class, () -> ShapeDocs.unwire("not base64!", 100)).problem());
		assertEquals("not deflated", assertThrows(DataException.class,
				() -> ShapeDocs.unwire(java.util.Base64.getEncoder().encodeToString(new byte[] {(byte) 0xff, 0x01, 0x02}), 100)).problem());
		byte[] latin1 = "café".getBytes(StandardCharsets.ISO_8859_1);
		assertEquals("not utf-8", assertThrows(DataException.class, () -> ShapeDocs.unwireText(ShapeDocs.wire(latin1), 100)).problem());
	}

	@Test
	void theMapsWireFormIsTheSameEncoding() throws Exception {
		ShapeMap.GridBuilder grid = new ShapeMap.GridBuilder(0, 0, 4, 4, 64);
		grid.set(1, 64, 2, 7);
		ShapeMap map = new ShapeMap(ShapeMap.NO_HASH, ShapeMap.NO_HASH, java.util.List.of(grid.build()), java.util.List.of());
		assertEquals(ShapeDocs.wire(map.encode()), map.wire());
		assertEquals(map.hash(), Hashes.sha256(ShapeDocs.unwire(map.wire(), 1 << 20)), "the offer's hash is the inflated bytes' hash");
		assertEquals(map.hash(), ShapeMap.fromWire(map.wire(), 1 << 20).hash());
	}
}
