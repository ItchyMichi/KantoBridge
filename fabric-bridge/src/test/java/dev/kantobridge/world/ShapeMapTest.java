package dev.kantobridge.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The shape map (docs/specs/VOXEL_BLOCKS_PLAN.md §4.3): encode → decode → the same lookups; canonical bytes and a
 * stable hash; bad input refused; positions outside every grid → 0; interiors 22 blocks apart picked by Y; the client's
 * lookup agrees with the scan everywhere.
 */
class ShapeMapTest {
	private static final String INDEX_HASH = "1".repeat(64);
	private static final String SHAPE_HASH = "2".repeat(64);

	/** A surface grid at feet 64 and two interiors under it, on layers 22 apart (42 and 20), in one chunk column. */
	static ShapeMap sample() {
		ShapeMap.GridBuilder surface = new ShapeMap.GridBuilder(-20, -12, 40, 30, 64);
		surface.set(-20, 64, -12, 1);
		surface.set(-20, 65, -12, 2);
		surface.set(0, 64, 0, 3);
		surface.set(0, 67, 0, 4);
		surface.set(19, 64, 17, 1);
		surface.set(19, 65, 17, 2);
		ShapeMap.GridBuilder first = new ShapeMap.GridBuilder(-4, -4, 10, 10, 42);
		first.set(0, 42, 0, 5);
		first.set(0, 43, 0, 6);
		ShapeMap.GridBuilder second = new ShapeMap.GridBuilder(-4, -4, 8, 8, 20);
		second.set(0, 20, 0, 7);
		// Listed out of order: the map sorts its grids.
		return new ShapeMap(INDEX_HASH, SHAPE_HASH, List.of(surface.build(), second.build(), first.build()), List.of());
	}

	@Test
	void lookupsSurviveEncodeAndDecode() throws Exception {
		ShapeMap map = sample();
		ShapeMap read = ShapeMap.decode(map.encode());
		for (ShapeMap m : List.of(map, read)) {
			assertEquals(1, m.shapeAt(-20, 64, -12));
			assertEquals(2, m.shapeAt(-20, 65, -12));
			assertEquals(0, m.shapeAt(-20, 66, -12), "above the stack");
			assertEquals(3, m.shapeAt(0, 64, 0));
			assertEquals(0, m.shapeAt(0, 65, 0), "a gap inside a stack is 0");
			assertEquals(4, m.shapeAt(0, 67, 0));
			assertEquals(5, m.shapeAt(0, 42, 0), "the first interior layer, under the same column");
			assertEquals(6, m.shapeAt(0, 43, 0));
			assertEquals(7, m.shapeAt(0, 20, 0), "the second layer, 22 blocks lower");
			assertEquals(0, m.shapeAt(0, 21, 0));
			assertEquals(0, m.shapeAt(0, 63, 0), "below the surface grid (the floor block)");
			assertEquals(0, m.shapeAt(20, 64, 0), "east of every grid");
			assertEquals(0, m.shapeAt(-21, 64, -12), "west of every grid");
			assertEquals(0, m.shapeAt(1_000_000, 64, 1_000_000));
			assertEquals(9, m.shapedBlocks());
		}
		assertEquals(INDEX_HASH, read.indexHash());
		assertEquals(SHAPE_HASH, read.shapeHash());
		assertEquals(List.of(20, 42, 64), read.grids().stream().map(ShapeMap.Grid::baseY).toList(), "grids sorted by baseY");
	}

	@Test
	void bytesAreCanonicalAndTheHashStable() throws Exception {
		ShapeMap map = sample();
		assertArrayEquals(map.encode(), sample().encode(), "the same map, the same bytes");
		assertEquals(map.hash(), sample().hash());
		assertEquals(Hashes.sha256(map.encode()), map.hash());
		assertEquals(map.size(), map.encode().length);
		assertArrayEquals(map.encode(), ShapeMap.decode(map.encode()).encode());
		// The header is inside the hash: the same grids bound to another index are another map.
		assertNotEquals(map.hash(), map.withHeader("3".repeat(64), SHAPE_HASH).hash());
		// Equal stacks share one entry: the two 1,2 columns of the surface.
		ShapeMap.Grid surface = map.grids().get(2);
		assertEquals(3, surface.stacks().length, "the empty stack, [1, 2], [3, 0, 0, 4] and nothing else");
		assertArrayEquals(new int[] {3, 0, 0, 4}, surface.stacks()[2]);
	}

	@Test
	void theWireFormRoundTrips() throws Exception {
		ShapeMap map = sample();
		String wire = map.wire();
		assertTrue(wire.matches("[A-Za-z0-9+/=]+"), "Base64");
		assertTrue(wire.length() < map.size(), "deflated: mostly empty columns");
		ShapeMap read = ShapeMap.fromWire(wire, 1 << 20);
		assertEquals(map.hash(), read.hash());
		assertThrows(DataException.class, () -> ShapeMap.fromWire(wire, map.size() - 1), "larger than allowed once inflated");
		assertThrows(DataException.class, () -> ShapeMap.fromWire("not base64!", 1 << 20));
		assertThrows(DataException.class, () -> ShapeMap.fromWire(wire.substring(0, wire.length() / 2 / 4 * 4), 1 << 20), "truncated");
		assertThrows(DataException.class, () -> ShapeMap.fromWire(java.util.Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4}), 1 << 20),
				"not deflated");
	}

	@Test
	void badBytesAreRefused() {
		byte[] good = sample().encode();
		byte[] magic = good.clone();
		magic[0] = 'X';
		byte[] version = good.clone();
		version[4] = 2;
		byte[] trailing = Arrays.copyOf(good, good.length + 1);
		byte[] truncated = Arrays.copyOf(good, good.length - 3);
		for (byte[] bad : List.of(magic, version, trailing, truncated, new byte[0], new byte[] {'K', 'B', 'S', 'M'})) {
			assertThrows(DataException.class, () -> ShapeMap.decode(bad));
		}
		// A column naming a stack the grid doesn't have.
		byte[] column = good.clone();
		ByteBuffer.wrap(column).putInt(column.length - 2 - 4, 99);
		assertThrows(DataException.class, () -> ShapeMap.decode(column));
		// Grids out of order are valid data but not the canonical file: refused, so one map has one hash.
		byte[] unsorted = unsortedCopy();
		assertThrows(DataException.class, () -> ShapeMap.decode(unsorted));
	}

	/** The sample's bytes with its first two grids swapped. */
	private static byte[] unsortedCopy() {
		ShapeMap map = sample();
		ShapeMap first = new ShapeMap(INDEX_HASH, SHAPE_HASH, List.of(map.grids().get(0)), List.of());
		ShapeMap second = new ShapeMap(INDEX_HASH, SHAPE_HASH, List.of(map.grids().get(1)), List.of());
		ShapeMap third = new ShapeMap(INDEX_HASH, SHAPE_HASH, List.of(map.grids().get(2)), List.of());
		int header = 4 + 1 + 32 + 32;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(map.encode(), 0, header);
		out.write(0);
		out.write(3);
		for (ShapeMap one : List.of(second, first, third)) {
			byte[] bytes = one.encode();
			// One grid's bytes sit between the grid count and the overlay count.
			out.write(bytes, header + 2, bytes.length - header - 2 - 2);
		}
		out.write(0);
		out.write(0);
		return out.toByteArray();
	}

	@Test
	void theClientsLookupAgreesWithTheScanEverywhere() {
		ShapeMap map = sample();
		ShapeMap.Lookup lookup = map.lookup();
		for (int y = 15; y < 72; y++) {
			for (int z = -16; z < 24; z++) {
				for (int x = -24; x < 24; x++) {
					assertEquals(map.shapeAt(x, y, z), lookup.shapeAt(x, y, z), x + "," + y + "," + z);
				}
			}
		}
		Random random = new Random(3);
		ShapeMap.GridBuilder big = new ShapeMap.GridBuilder(-100, -70, 300, 200, 64);
		for (int i = 0; i < 2000; i++) {
			big.set(-100 + random.nextInt(300), 64 + random.nextInt(6), -70 + random.nextInt(200), 1 + random.nextInt(50));
		}
		ShapeMap many = new ShapeMap(INDEX_HASH, SHAPE_HASH, List.of(big.build()), List.of());
		ShapeMap.Lookup fast = many.lookup();
		for (int i = 0; i < 20000; i++) {
			int x = -120 + random.nextInt(340);
			int y = 60 + random.nextInt(14);
			int z = -90 + random.nextInt(240);
			assertEquals(many.shapeAt(x, y, z), fast.shapeAt(x, y, z));
		}
		assertEquals(0, new ShapeMap(INDEX_HASH, SHAPE_HASH, List.of(), List.of()).lookup().shapeAt(0, 64, 0), "an empty map");
	}

	@Test
	void stackedGridsAreToldApartByTheirStacks() {
		// Two pieces 11 blocks apart in one column (CAVES_3D_PLAN §3.1): a tall stack in the lower grid would reach the
		// upper one's floor, so a lookup goes on past a grid whose stack doesn't reach y.
		ShapeMap.GridBuilder lower = new ShapeMap.GridBuilder(0, 0, 4, 4, 53);
		lower.set(1, 53, 1, 5);
		lower.set(2, 64, 2, 6);
		ShapeMap.GridBuilder upper = new ShapeMap.GridBuilder(0, 0, 4, 4, 64);
		upper.set(1, 64, 1, 7);
		ShapeMap map = new ShapeMap(INDEX_HASH, SHAPE_HASH, List.of(lower.build(), upper.build()), List.of());
		ShapeMap.Lookup lookup = map.lookup();
		assertEquals(5, lookup.shapeAt(1, 53, 1));
		assertEquals(7, lookup.shapeAt(1, 64, 1), "the upper grid's, past the lower grid whose range reaches 64");
		assertEquals(6, lookup.shapeAt(2, 64, 2), "the lower grid's tall stack, where the upper grid has none");
		assertEquals(5, map.shapeAt(1, 53, 1));
		assertEquals(7, map.shapeAt(1, 64, 1));
		assertEquals(6, map.shapeAt(2, 64, 2));
		assertEquals(0, map.shapeAt(1, 70, 1));
	}

	@Test
	void buildersRefusePositionsOutsideTheirBox() {
		ShapeMap.GridBuilder grid = new ShapeMap.GridBuilder(0, 0, 4, 4, 64);
		assertThrows(IllegalArgumentException.class, () -> grid.set(4, 64, 0, 1));
		assertThrows(IllegalArgumentException.class, () -> grid.set(0, 63, 0, 1));
		assertThrows(IllegalArgumentException.class, () -> grid.set(0, 64 + ShapeMap.MAX_STACK, 0, 1));
		assertThrows(IllegalArgumentException.class, () -> new ShapeMap("x", SHAPE_HASH, List.of(), List.of()), "a header hash is hex");
	}
}
