package dev.kantobridge.builder.shape.geom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import dev.kantobridge.builder.shape.ShapeKey;
import dev.kantobridge.builder.shape.ShapeParams;
import dev.kantobridge.scene.ArtSource;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.ShapeIndex;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The shape table (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5): a {@code cube} is six faces on the block's surface, each its
 * own tile and its cull face; texture keys are shared between shapes; a key it can't build has no mesh (the plain cube is
 * drawn); the client's cache file reads back exactly and only for the same index, geometry and overrides.
 */
class ShapeTableTest {
	private static ShapeIndex index() {
		ShapeParams p = ShapeParams.v2();
		return ShapeIndex.of(p.planner(), p.toJson(), List.of("tilesets.fix"), List.of("PAL_A", "PAL_B"),
				List.of(new ShapeIndex.Use(ShapeKey.cube(0, 1, 2, 3, 4, 5, 6), "PAL_A"), new ShapeIndex.Use(ShapeKey.cube(0, 1, 2, 3, 4, 5, 6), "PAL_B"),
						new ShapeIndex.Use(ShapeKey.cube(0, 7, 7, 3, -1, 7, 7), "PAL_A"), new ShapeIndex.Use(ShapeKey.slab(0, 4, 8), "PAL_A"),
						new ShapeIndex.Use(ShapeKey.tuft(0, 9), "PAL_A")),
				new JsonArray());
	}

	@Test
	void aCubeHasSixFacesEachWithItsTile() {
		ShapeIndex index = index();
		ShapeTable table = ShapeTable.build(index, ArtSource.NONE);
		ShapeMesh mesh = table.mesh(index.shapeId(ShapeKey.cube(0, 1, 2, 3, 4, 5, 6), "PAL_A"));
		assertNotNull(mesh);
		assertEquals(6, mesh.size());
		// Key order up, down, north, south, west, east; quads in face order down, up, north, south, west, east.
		int[] expectedTiles = {2, 1, 3, 4, 5, 6};
		for (int face = 0; face < 6; face++) {
			ShapeQuad quad = mesh.quads().get(face);
			assertEquals(face, quad.face());
			assertEquals(face, quad.cull());
			assertEquals("tile:tilesets.fix/" + expectedTiles[face] + "@PAL_A", table.textureKeys().get(quad.texture()));
			assertArrayEquals(ShapeQuad.fullFace(face, quad.texture()).xyz(), quad.xyz());
		}
		// The same key in another palette: the same quads on that palette's textures.
		ShapeMesh other = table.mesh(index.shapeId(ShapeKey.cube(0, 1, 2, 3, 4, 5, 6), "PAL_B"));
		assertEquals("tile:tilesets.fix/2@PAL_B", table.textureKeys().get(other.quads().get(0).texture()));
	}

	@Test
	void texturesAreSharedAndMissingFacesSkipped() {
		ShapeIndex index = index();
		ShapeTable table = ShapeTable.build(index, ArtSource.NONE);
		ShapeMesh open = table.mesh(index.shapeId(ShapeKey.cube(0, 7, 7, 3, -1, 7, 7), "PAL_A"));
		assertEquals(5, open.size(), "a face with tile -1 isn't drawn");
		assertEquals(List.of(0, 1, 2, 4, 5), open.quads().stream().map(q -> (int) q.face()).toList());
		assertEquals(table.textureKeys().indexOf("tile:tilesets.fix/3@PAL_A"), open.quads().get(2).texture(), "shared with the first cube");
		assertEquals(table.textureKeys().size(), table.textureKeys().stream().distinct().count(), "each key once");
		assertEquals(6, table.mesh(index.shapeId(ShapeKey.slab(0, 4, 8), "PAL_A")).size(), "slabs from V3");
		assertNull(table.mesh(index.shapeId(ShapeKey.tuft(0, 9), "PAL_A")), "a tuft without art draws nothing");
		assertNull(table.mesh(0));
		assertNull(table.mesh(99));
		assertEquals(5, table.size());
		assertEquals(4, table.built());
		assertEquals(6 + 6 + 5 + 6, table.quads());
	}

	@Test
	void tuftsAndUnitsAreBuiltFromTheArt() {
		ShapeParams p = ShapeParams.v4();
		int[][] sign = ArtFixtures.tiles(ArtFixtures.sign());
		CellUnits.Context context = new CellUnits.Context(Background.SOUTH, false, false, true, false);
		ShapeKey front = ShapeKey.cell(0, new int[] {20, 21, 22, 23}, 1, 0, 0, 1).withVariant(context.variant());
		ShapeKey back = ShapeKey.cell(0, new int[] {20, 21, 22, 23}, 1, 0, 0, 0).withVariant(context.variant());
		ShapeIndex index = ShapeIndex.of(p.planner(), p.toJson(), List.of("tilesets.fix"), List.of("PAL_A"),
				List.of(new ShapeIndex.Use(ShapeKey.tuft(0, 9), "PAL_A"), new ShapeIndex.Use(front, "PAL_A"), new ShapeIndex.Use(back, "PAL_A")),
				new JsonArray());
		int[] grass = new int[64];
		grass[7 * 8 + 3] = 2;
		ArtSource art = ArtFixtures.art("tilesets.fix", java.util.Map.of(9, grass, 20, sign[0], 21, sign[1], 22, sign[2], 23, sign[3]),
				java.util.Map.of());
		ShapeTable table = ShapeTable.build(index, art, "a".repeat(64));
		assertEquals("a".repeat(64), table.overridesHash(), "the art the geometry was built from names the cache");
		ShapeMesh tuft = table.mesh(index.shapeId(ShapeKey.tuft(0, 9), "PAL_A"));
		assertEquals(2, tuft.size(), "one stroke pixel: its front and its back");
		assertEquals("tile:tilesets.fix/9@PAL_A", table.textureKeys().get(tuft.quads().get(0).texture()));
		CellUnits.Unit unit = table.unit("tilesets.fix", front);
		assertEquals(CellUnits.Kind.OBJECT, unit.kind());
		assertTrue(table.mesh(index.shapeId(front, "PAL_A")).size() > 0, "the sign's south blocks hold its slab");
		assertEquals(0, table.mesh(index.shapeId(back, "PAL_A")).size(), "its north blocks are empty: nothing is drawn there");
		for (ShapeQuad quad : table.mesh(index.shapeId(front, "PAL_A")).quads()) {
			assertTrue(List.of("tile:tilesets.fix/20@PAL_A", "tile:tilesets.fix/21@PAL_A", "tile:tilesets.fix/22@PAL_A", "tile:tilesets.fix/23@PAL_A")
					.contains(table.textureKeys().get(quad.texture())), "the unit's own tiles");
		}
		// Without the art, the unit draws its column version and the tuft nothing.
		ShapeTable bare = ShapeTable.build(index, ArtSource.NONE);
		assertEquals(6, bare.mesh(index.shapeId(front, "PAL_A")).size());
		assertEquals(CellUnits.Kind.COLUMNS, bare.unit("tilesets.fix", front).kind());
		assertNull(bare.mesh(index.shapeId(ShapeKey.tuft(0, 9), "PAL_A")));
	}

	@Test
	void packRulesAndModelsChangeUnitsAndTufts() {
		// The voxel plan's V6: a pack's rules and .vox models, through ShapeOverrides.
		ShapeParams p = ShapeParams.v4();
		int[][] sign = ArtFixtures.tiles(ArtFixtures.sign());
		CellUnits.Context context = new CellUnits.Context(Background.SOUTH, false, false, true, false);
		ShapeKey front = ShapeKey.cell(0, new int[] {20, 21, 22, 23}, 1, 0, 0, 1).withVariant(context.variant());
		ShapeIndex index = ShapeIndex.of(p.planner(), p.toJson(), List.of("tilesets.fix"), List.of("PAL_A"),
				List.of(new ShapeIndex.Use(ShapeKey.tuft(0, 9), "PAL_A"), new ShapeIndex.Use(front, "PAL_A")), new JsonArray());
		int[] grass = new int[64];
		grass[7 * 8 + 3] = 2;
		ArtSource art = ArtFixtures.art("tilesets.fix", java.util.Map.of(9, grass, 20, sign[0], 21, sign[1], 22, sign[2], 23, sign[3]),
				java.util.Map.of());
		ShapeOverrides flat = new ShapeOverrides() {
			@Override
			public String geometryHash() {
				return "c".repeat(64);
			}

			@Override
			public String unitStyle(String atlas, int[] tiles, boolean foliage) {
				return "columns";
			}

			@Override
			public boolean tuft(String atlas, int tile, boolean flower) {
				return false;
			}
		};
		ShapeTable styled = ShapeTable.build(index, art, "c".repeat(64), flat);
		assertEquals(CellUnits.Kind.COLUMNS, styled.unit("tilesets.fix", front).kind(), "a class rule's style");
		assertNull(styled.mesh(index.shapeId(ShapeKey.tuft(0, 9), "PAL_A")), "flat grass: no tuft, the ground's own face shows");

		java.util.List<java.util.List<ShapeQuad>> blocks = new java.util.ArrayList<>(java.util.Collections.nCopies(8, java.util.List.of()));
		blocks.set(CellUnits.block(ShapeTable.UNIT_CELL, 0, 0, 1), java.util.List.of(ShapeQuad.fullFace(ShapeQuad.UP, 3)));
		int[] colours = new int[256];
		colours[3] = 0xFF123456;
		ShapeOverrides modelled = new ShapeOverrides() {
			@Override
			public String geometryHash() {
				return "d".repeat(64);
			}

			@Override
			public ShapeOverrides.Model unitModel(String atlas, int[] tiles) {
				return java.util.Arrays.equals(tiles, new int[] {20, 21, 22, 23}) ? new ShapeOverrides.Model(blocks, colours) : null;
			}
		};
		ShapeTable table = ShapeTable.build(index, art, "d".repeat(64), modelled);
		assertEquals(CellUnits.Kind.MODEL, table.unit("tilesets.fix", front).kind());
		ShapeMesh mesh = table.mesh(index.shapeId(front, "PAL_A"));
		assertEquals(1, mesh.size(), "the model's quad in this block");
		assertEquals("color:123456", table.textureKeys().get(mesh.quads().get(0).texture()), "a colour over the white sprite");
		assertEquals("d".repeat(64), table.overridesHash());
	}

	@Test
	void theCacheFileReadsBackForTheSameInputsOnly() throws Exception {
		ShapeIndex index = index();
		ShapeTable table = ShapeTable.build(index, ArtSource.NONE);
		byte[] bytes = table.encode();
		ShapeTable read = ShapeTable.decode(bytes, index.hash(), ShapeTable.GEOMETRY, ShapeTable.NO_OVERRIDES);
		assertEquals(table.textureKeys(), read.textureKeys());
		assertEquals(table.quads(), read.quads());
		assertEquals(table.hash(), read.hash());
		for (int id = 1; id <= index.size(); id++) {
			ShapeMesh a = table.mesh(id);
			ShapeMesh b = read.mesh(id);
			assertEquals(a == null, b == null);
			if (a != null) {
				for (int q = 0; q < a.size(); q++) {
					assertArrayEquals(a.quads().get(q).xyz(), b.quads().get(q).xyz());
					assertArrayEquals(a.quads().get(q).uv(), b.quads().get(q).uv());
					assertEquals(a.quads().get(q).texture(), b.quads().get(q).texture());
				}
			}
		}
		assertThrows(DataException.class, () -> ShapeTable.decode(bytes, "f".repeat(64), ShapeTable.GEOMETRY, ShapeTable.NO_OVERRIDES));
		assertThrows(DataException.class, () -> ShapeTable.decode(bytes, index.hash(), ShapeTable.GEOMETRY + 1, ShapeTable.NO_OVERRIDES));
		assertThrows(DataException.class, () -> ShapeTable.decode(bytes, index.hash(), ShapeTable.GEOMETRY, "e".repeat(64)));
		byte[] cut = java.util.Arrays.copyOf(bytes, bytes.length - 5);
		assertThrows(DataException.class, () -> ShapeTable.decode(cut, index.hash(), ShapeTable.GEOMETRY, ShapeTable.NO_OVERRIDES));
		String name = ShapeTable.cacheName(index.hash(), ShapeTable.GEOMETRY, ShapeTable.NO_OVERRIDES);
		assertTrue(name.matches("[0-9a-f]{16}-g" + ShapeTable.GEOMETRY + "-0{16}\\.bin"), name);
	}
}
