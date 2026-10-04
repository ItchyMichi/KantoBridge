package dev.kantobridge.client.shape.vox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The {@code .vox} reader and writer (docs/specs/VOXEL_BLOCKS_PLAN.md §5.10): {@code MAIN}, {@code SIZE}, {@code XYZI},
 * {@code RGBA}; unknown chunks (the scene graph, materials, layers) skipped by their declared sizes; the first model
 * used; 1..256 voxels per axis; a file without a palette gets greys; write → read is exact.
 */
class VoxFileTest {
	static byte[] chunk(String id, byte[] content, byte[] children) {
		ByteBuffer out = ByteBuffer.allocate(12 + content.length + children.length).order(ByteOrder.LITTLE_ENDIAN);
		out.put(id.getBytes(StandardCharsets.US_ASCII)).putInt(content.length).putInt(children.length).put(content).put(children);
		return out.array();
	}

	static byte[] ints(int... values) {
		ByteBuffer out = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int v : values) {
			out.putInt(v);
		}
		return out.array();
	}

	static byte[] xyzi(int... xyzc) {
		ByteBuffer out = ByteBuffer.allocate(4 + xyzc.length).order(ByteOrder.LITTLE_ENDIAN);
		out.putInt(xyzc.length / 4);
		for (int v : xyzc) {
			out.put((byte) v);
		}
		return out.array();
	}

	static byte[] file(int version, byte[]... children) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		for (byte[] child : children) {
			body.writeBytes(child);
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes("VOX ".getBytes(StandardCharsets.US_ASCII));
		out.writeBytes(ints(version));
		out.writeBytes(chunk("MAIN", new byte[0], body.toByteArray()));
		return out.toByteArray();
	}

	@Test
	void writeThenReadIsExact() throws Exception {
		// A palette holds opaque colours; entry 0 is never used.
		int[] palette = new int[256];
		for (int i = 1; i < 256; i++) {
			palette[i] = 0xFF000000 | i * 0x010203;
		}
		palette[1] = 0xFF102030;
		palette[7] = 0xFFFFFFFF;
		palette[255] = 0xFF0000FF;
		byte[] voxels = {0, 0, 0, 1, 2, 1, 3, 7, 1, 0, 2, (byte) 255};
		VoxFile.Model model = new VoxFile.Model(3, 2, 4, voxels, palette, 1);
		VoxFile.Model read = VoxFile.read(VoxFile.write(model));
		assertEquals(3, read.sx());
		assertEquals(2, read.sy());
		assertEquals(4, read.sz());
		assertArrayEquals(voxels, read.voxels());
		assertArrayEquals(palette, read.palette());
		assertEquals(1, read.models());
		assertEquals(3, read.count());
		assertEquals(2, read.x(1));
		assertEquals(1, read.y(1));
		assertEquals(3, read.z(1));
		assertEquals(7, read.colour(1));
	}

	@Test
	void unknownChunksAreSkippedAndTheFirstModelUsed() throws Exception {
		byte[] bytes = file(200,
				chunk("PACK", ints(2), new byte[0]),
				chunk("SIZE", ints(2, 2, 2), new byte[0]),
				chunk("XYZI", xyzi(0, 0, 0, 5, 1, 1, 1, 6), new byte[0]),
				chunk("nTRN", new byte[] {1, 2, 3, 4, 5, 6, 7}, chunk("NOTE", new byte[3], new byte[0])),
				chunk("SIZE", ints(9, 9, 9), new byte[0]),
				chunk("XYZI", xyzi(8, 8, 8, 1), new byte[0]),
				chunk("MATL", new byte[40], new byte[0]),
				chunk("LAYR", new byte[11], new byte[0]));
		VoxFile.Model model = VoxFile.read(bytes);
		assertEquals(2, model.sx(), "the first model's size");
		assertEquals(2, model.count());
		assertEquals(2, model.models(), "two models in the file");
		assertEquals(0xFF050505, model.palette()[5], "no RGBA chunk: greys");
	}

	@Test
	void sizesAndBrokenFilesAreRefusedWithTheirCodes() {
		assertCode("pack.vox_size", file(150, chunk("SIZE", ints(0, 4, 4), new byte[0]), chunk("XYZI", xyzi(), new byte[0])));
		assertCode("pack.vox_size", file(150, chunk("SIZE", ints(257, 4, 4), new byte[0]), chunk("XYZI", xyzi(), new byte[0])));
		assertCode("pack.vox_format", file(150, chunk("SIZE", ints(2, 2, 2), new byte[0]), chunk("XYZI", xyzi(2, 0, 0, 1), new byte[0])));
		assertCode("pack.vox_format", file(149, chunk("SIZE", ints(2, 2, 2), new byte[0]), chunk("XYZI", xyzi(0, 0, 0, 1), new byte[0])));
		assertCode("pack.vox_format", file(150, chunk("SIZE", ints(2, 2, 2), new byte[0])));
		assertCode("pack.vox_format", "VOX ".getBytes(StandardCharsets.US_ASCII));
		assertCode("pack.vox_format", "PNG image, not a model".getBytes(StandardCharsets.US_ASCII));
		byte[] good = file(150, chunk("SIZE", ints(2, 2, 2), new byte[0]), chunk("XYZI", xyzi(0, 0, 0, 1), new byte[0]));
		assertCode("pack.vox_format", java.util.Arrays.copyOf(good, good.length - 6));
		byte[] overrun = good.clone();
		// The XYZI chunk's content size, made larger than the file.
		ByteBuffer.wrap(overrun).order(ByteOrder.LITTLE_ENDIAN).putInt(8 + 12 + 24 + 4, 1000);
		assertCode("pack.vox_format", overrun);
		assertCode("pack.vox_format", file(150, chunk("SIZE", ints(2, 2, 2), new byte[0]), chunk("XYZI", xyzi(0, 0, 0, 1), new byte[0]),
				chunk("RGBA", new byte[100], new byte[0])));
	}

	private static void assertCode(String code, byte[] bytes) {
		VoxFile.VoxException e = assertThrows(VoxFile.VoxException.class, () -> VoxFile.read(bytes));
		assertEquals(code, e.code());
	}
}
