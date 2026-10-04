package dev.kantobridge.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.junit.jupiter.api.Test;

/**
 * Readers on other threads never see a half-built snapshot (docs/specs/VOXEL_BLOCKS_PLAN.md I5, §6.1): one thread keeps
 * publishing snapshots whose every field holds the same number, four "mesh threads" keep reading through
 * {@link RenderSnapshot#current()}, and every snapshot they see is whole.
 */
class RenderSnapshotTest {
	private static RenderSnapshot numbered(long n) {
		return new RenderSnapshot(n, null, Long.toString(n), new TextureAtlasSprite[2][], new Mesh[2][], new Mesh[2], null, true,
				Long.toString(n), (int) n, (int) n, null, null, new TextureAtlasSprite[0], null, null, null, new java.util.BitSet());
	}

	@Test
	void readersNeverSeeAHalfBuiltSnapshot() throws Exception {
		RenderSnapshot before = RenderSnapshot.current();
		AtomicBoolean stop = new AtomicBoolean();
		AtomicLong reads = new AtomicLong();
		List<String> torn = new ArrayList<>();
		List<Thread> readers = new ArrayList<>();
		for (int t = 0; t < 4; t++) {
			Thread reader = new Thread(() -> {
				long last = -2;
				while (!stop.get()) {
					RenderSnapshot s = RenderSnapshot.current();
					long n = s.generation();
					if (n >= 0 && (s.keys() != n || s.missing() != n || !Long.toString(n).equals(s.slotMapHash())
							|| !Long.toString(n).equals(s.artSet()))) {
						synchronized (torn) {
							torn.add("snapshot " + n);
						}
					}
					if (n >= 0 && n < last) {
						synchronized (torn) {
							torn.add("went back from " + last + " to " + n);
						}
					}
					last = Math.max(last, n);
					reads.incrementAndGet();
				}
			}, "mesh-" + t);
			readers.add(reader);
			reader.start();
		}
		try {
			for (int n = 0; n < 20_000; n++) {
				RenderSnapshot.publish(numbered(n));
			}
		} finally {
			stop.set(true);
			for (Thread reader : readers) {
				reader.join(5_000);
			}
			RenderSnapshot.publish(before);
		}
		assertTrue(torn.isEmpty(), torn.toString());
		assertTrue(reads.get() > 0);
	}

	@Test
	void theBootSnapshotDrawsPlaceholdersOnly() {
		RenderSnapshot boot = RenderSnapshot.BOOT;
		assertEquals(-1, boot.generation());
		assertEquals(-1, boot.missing(), "no slot map: the count isn't known");
		assertEquals(0, boot.keys());
		assertSame(null, boot.artSet());
	}
}
