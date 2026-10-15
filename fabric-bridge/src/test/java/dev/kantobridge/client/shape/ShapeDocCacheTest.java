package dev.kantobridge.client.shape;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.ShapeDocs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The client's cache of shape documents (the follow-up's item 4): kept by content hash as deflated bytes, read back
 * only when they inflate to that hash (a damaged file is dropped), the least recently used going first past its size.
 */
class ShapeDocCacheTest {
	@TempDir
	Path dir;

	private static byte[] doc(int seed, int size) {
		byte[] out = new byte[size];
		java.util.Random random = new java.util.Random(seed);
		random.nextBytes(out);
		return out;
	}

	@Test
	void aDocumentComesBackByItsHash() throws Exception {
		ShapeDocCache cache = new ShapeDocCache(this.dir, 1 << 20);
		byte[] index = "{\"shape\":\"index\"}".getBytes(StandardCharsets.UTF_8);
		String hash = Hashes.sha256(index);
		assertNull(cache.read(hash, true, 1 << 20), "not there yet");
		cache.write(hash, true, ShapeDocs.deflate(index));
		ShapeDocCache.Loaded loaded = cache.read(hash, true, 1 << 20);
		assertNotNull(loaded);
		assertArrayEquals(index, loaded.content());
		assertArrayEquals(ShapeDocs.deflate(index), loaded.deflated(), "the deflated bytes as kept (the map's wire form without Base64)");
		assertNull(cache.read(hash, false, 1 << 20), "the index and the map are kept apart");
		assertNull(cache.read("../" + hash.substring(3), true, 1 << 20), "never a path from outside");
		assertThrows(IOException.class, () -> cache.write("NOT-A-HASH", true, new byte[] {1}));
	}

	@Test
	void aDamagedFileIsDropped() throws Exception {
		ShapeDocCache cache = new ShapeDocCache(this.dir, 1 << 20);
		byte[] map = doc(1, 3000);
		String hash = Hashes.sha256(map);
		cache.write(hash, false, ShapeDocs.deflate(doc(2, 3000)));
		assertNull(cache.read(hash, false, 1 << 20), "another document under this hash");
		assertFalse(Files.exists(this.dir.resolve(hash + ".map.z")), "and it is gone");
		Files.write(this.dir.resolve(hash + ".map.z"), new byte[] {(byte) 0xff, 0x00});
		assertNull(cache.read(hash, false, 1 << 20), "not deflate");
		assertFalse(Files.exists(this.dir.resolve(hash + ".map.z")));
	}

	@Test
	void theLeastRecentlyUsedGoFirst() throws Exception {
		ShapeDocCache cache = new ShapeDocCache(this.dir, 10_000);
		byte[][] docs = {doc(1, 4000), doc(2, 4000), doc(3, 4000)};
		String[] hashes = new String[3];
		for (int i = 0; i < 3; i++) {
			hashes[i] = Hashes.sha256(docs[i]);
			cache.write(hashes[i], false, ShapeDocs.deflate(docs[i]));
			Files.setLastModifiedTime(this.dir.resolve(hashes[i] + ".map.z"), FileTime.fromMillis(1_000_000L * (i + 1)));
		}
		// three random 4,000-byte documents don't fit in 10,000: the oldest went when the third was written
		assertNull(cache.read(hashes[0], false, 1 << 20));
		assertNotNull(cache.read(hashes[1], false, 1 << 20), "used now: the newest");
		cache.write(Hashes.sha256(doc(4, 4000)), false, ShapeDocs.deflate(doc(4, 4000)));
		assertNull(cache.read(hashes[2], false, 1 << 20), "the least recently used went");
		assertNotNull(cache.read(hashes[1], false, 1 << 20));
		assertTrue(Files.list(this.dir).noneMatch(p -> p.getFileName().toString().endsWith(".part")), "no temporary files left");
	}
}
