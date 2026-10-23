package dev.kantobridge.builder.shape.geom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The packed quads (2026-10-23): a mesh read from its {@link QuadStore} gives back every quad as it was made (corners,
 * uvs, texture, faces, flags), its per-quad accessors and uvs agree with {@link ShapeQuad}'s own, and its footprints,
 * outlines and face masks with the ones worked out from the quad objects.
 */
class PackedMeshTest {
	private static List<ShapeQuad> randomQuads(Random random, int n) {
		List<ShapeQuad> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			int texture = random.nextInt(4) == 0 ? random.nextInt(Integer.MAX_VALUE) : random.nextInt(20_000);
			int cull = random.nextInt(7) - 1;
			int flags = random.nextInt(256);
			switch (random.nextInt(3)) {
				case 0 -> {
					int face = random.nextInt(6);
					int x0 = random.nextInt(33) - 8;
					int y0 = random.nextInt(33) - 8;
					int z0 = random.nextInt(33) - 8;
					int x1 = Math.min(24, x0 + random.nextInt(17));
					int y1 = Math.min(24, y0 + random.nextInt(17));
					int z1 = Math.min(24, z0 + random.nextInt(17));
					out.add(ShapeQuad.face(face, x0, y0, z0, x1, y1, z1, texture, random.nextInt(256) - 128, random.nextInt(256) - 128,
							random.nextInt(256) - 128, random.nextInt(256) - 128, cull, flags));
				}
				case 1 -> {
					int face = random.nextInt(6);
					int[][] corners = new int[3 + random.nextInt(2)][];
					for (int c = 0; c < corners.length; c++) {
						corners[c] = new int[] {random.nextInt(33) - 8, random.nextInt(33) - 8, random.nextInt(33) - 8};
					}
					out.add(ShapeQuad.polygon(face, corners, texture, random.nextInt(17), random.nextInt(17), random.nextInt(17), random.nextInt(17),
							cull, flags));
				}
				default -> {
					int[][] corners = new int[3 + random.nextInt(2)][];
					int[][] uvs = new int[corners.length][];
					for (int c = 0; c < corners.length; c++) {
						corners[c] = new int[] {random.nextInt(33) - 8, random.nextInt(33) - 8, random.nextInt(33) - 8};
						uvs[c] = new int[] {random.nextInt(256) - 128, random.nextInt(256) - 128};
					}
					out.add(ShapeQuad.sloped(corners, uvs, texture, cull, flags));
				}
			}
		}
		return out;
	}

	@Test
	void aPackedMeshGivesBackEveryQuadAndItsFields() {
		Random random = new Random(42);
		List<ShapeQuad> quads = randomQuads(random, 5_000);
		ShapeMesh mesh = new ShapeMesh(quads);
		assertEquals(quads.size(), mesh.size());
		List<ShapeQuad> back = mesh.quads();
		for (int q = 0; q < quads.size(); q++) {
			ShapeQuad a = quads.get(q);
			ShapeQuad b = back.get(q);
			assertEquals(a.face(), b.face());
			assertArrayEquals(a.xyz(), b.xyz());
			assertEquals(a.texture(), b.texture());
			assertArrayEquals(a.uv(), b.uv());
			assertEquals(a.cull(), b.cull());
			assertEquals(a.flags(), b.flags());
			assertEquals(a.face(), mesh.face(q));
			assertEquals(a.cull(), mesh.cull(q));
			assertEquals(a.texture(), mesh.texture(q));
			assertEquals(a.tint(), mesh.tint(q));
			for (int flag : new int[] {ShapeQuad.CUTOUT, ShapeQuad.DARKEN, ShapeQuad.EMISSIVE, ShapeQuad.NO_AO}) {
				assertEquals(a.has(flag), mesh.has(q, flag));
			}
			for (int i = 0; i < 4; i++) {
				assertEquals(a.x(i), mesh.x(q, i));
				assertEquals(a.y(i), mesh.y(q, i));
				assertEquals(a.z(i), mesh.z(q, i));
				assertEquals(a.cornerU(i), mesh.cornerU(q, i), "u of quad " + q + " corner " + i);
				assertEquals(a.cornerV(i), mesh.cornerV(q, i), "v of quad " + q + " corner " + i);
			}
			assertEquals(FaceMasks.footprint(a), mesh.footprint(q), "footprint of quad " + q);
			assertEquals(FaceMasks.outline(a), mesh.outline(q), "outline of quad " + q);
		}
	}

	@Test
	void outlinesAndMasksMatchTheOnesWorkedOutFromTheQuads() {
		Random random = new Random(7);
		for (int round = 0; round < 300; round++) {
			List<ShapeQuad> quads = randomQuads(random, 1 + random.nextInt(12));
			ShapeMesh mesh = new ShapeMesh(quads);
			long[] masks = FaceMasks.coverage(quads);
			Set<Long> outlines = new HashSet<>();
			for (ShapeQuad quad : quads) {
				if (quad.cull() >= 0) {
					outlines.add((long) quad.cull() << 60 ^ FaceMasks.outline(quad));
				}
			}
			for (int face = 0; face < 6; face++) {
				assertEquals(masks[face], mesh.faceMask(face));
				for (ShapeQuad quad : randomQuads(random, 4)) {
					long outline = FaceMasks.outline(quad);
					assertEquals(outlines.contains((long) face << 60 ^ outline), mesh.hasOutline(face, outline));
				}
				for (ShapeQuad quad : quads) {
					if (quad.cull() == face) {
						assertTrue(mesh.hasOutline(face, FaceMasks.outline(quad)));
					}
				}
			}
		}
	}

	@Test
	void everyStoredQuadIsValidAndBrokenWordsAreNot() {
		QuadStore store = new QuadStore();
		for (ShapeQuad quad : randomQuads(new Random(3), 2_000)) {
			store.add(quad);
		}
		for (int q = 0; q < store.size(); q++) {
			assertTrue(QuadStore.valid(store.word(q, 0), store.word(q, 1), store.word(q, 2), Integer.MAX_VALUE));
		}
		ShapeQuad cube = ShapeQuad.fullFace(ShapeQuad.UP, 5);
		store.add(cube);
		int q = store.size() - 1;
		long w0 = store.word(q, 0);
		long w1 = store.word(q, 1);
		long w2 = store.word(q, 2);
		assertTrue(QuadStore.valid(w0, w1, w2, 6));
		assertFalse(QuadStore.valid(w0, w1, w2, 5), "its texture is past the keys");
		assertFalse(QuadStore.valid(w0 | 63, w1, w2, 6), "a corner past 24");
		assertFalse(QuadStore.valid(w0, w1 | 7L << 15, w2, 6), "no such cull face");
		assertFalse(QuadStore.valid(w0, w1 | 7L << 12, w2, 6), "no such face");
		assertFalse(QuadStore.valid(w0, w1, w2 | 1L << 40, 6), "uv bytes past the four of a face quad");
	}

	@Test
	void pagesHoldMoreThanOnePageOfQuads() {
		QuadStore store = new QuadStore();
		List<ShapeQuad> quads = randomQuads(new Random(11), 70_000);
		for (ShapeQuad quad : quads) {
			store.add(quad);
		}
		store.trim();
		for (int q = 0; q < quads.size(); q += 997) {
			assertArrayEquals(quads.get(q).xyz(), store.quad(q).xyz());
			assertEquals(quads.get(q).texture(), store.quad(q).texture());
		}
		assertArrayEquals(quads.get(quads.size() - 1).uv(), store.quad(quads.size() - 1).uv());
		assertTrue(store.bytes() < 30L * quads.size(), "about 24 bytes a quad: " + store.bytes());
	}
}
