package dev.kantobridge.builder.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/** PNG decoding and encoding, palette colouring, 2× upscaling, animation strips and sprite sheets. */
class TexturesTest {
	private static final int[] PALETTE = {0xF8F8E8, 0xA0D080, 0x408060, 0x182028};

	@Test
	void decodesTheSyntheticArt() throws Exception {
		ExportBundle bundle = ExportBundle.open(TestData.syntheticBundle());
		Png.Image atlas = Png.decode(bundle.image("tilesets.test").png());
		assertEquals(128, atlas.width());
		assertEquals(48, atlas.height());
		// Tile 1 (grass) at (8, 0): shade 2 where (x + y) % 3 == 0, else shade 1.
		assertEquals(2, Textures.shade(atlas.get(8, 0)));
		assertEquals(1, Textures.shade(atlas.get(9, 0)));
		Png.Image sprite = Png.decode(bundle.sprite("SPRITE_TEST_GIRL").png());
		assertEquals(16, sprite.width());
		assertEquals(96, sprite.height());
		assertEquals(-1, Textures.shade(sprite.get(0, 0)), "shade 0 is transparent in sprites");
	}

	@Test
	void shadesAreTheNearestOfFourGreys() {
		assertEquals(0, Textures.shade(0xFFFFFFFF));
		assertEquals(1, Textures.shade(0xFFAAAAAA));
		assertEquals(2, Textures.shade(0xFF555555));
		assertEquals(3, Textures.shade(0xFF000000));
		assertEquals(-1, Textures.shade(0x00FFFFFF));
	}

	private static int[] testTile() {
		int[] shades = new int[64];
		for (int i = 0; i < 64; i++) {
			shades[i] = (i % 8) % 4; // columns 0..7 → shades 0,1,2,3,0,1,2,3
		}
		return shades;
	}

	@Test
	void tilesAreColouredAndUpscaledNearest() {
		Png.Image tile = Textures.tile(testTile(), PALETTE);
		assertEquals(16, tile.width());
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				assertEquals(0xFF000000 | PALETTE[(x / 2) % 4], tile.get(x, y));
			}
		}
	}

	@Test
	void waterSwaysOnePixelAtATime() {
		Png.Image strip = Textures.waterStrip(testTile(), PALETTE);
		assertEquals(16 * 8, strip.height());
		for (int frame = 0; frame < 8; frame++) {
			int shift = Textures.WATER_OFFSETS[frame];
			for (int x = 0; x < 8; x++) {
				int expected = PALETTE[Math.floorMod(x - shift, 8) % 4];
				assertEquals(0xFF000000 | expected, strip.get(x * 2, frame * 16), "frame " + frame + " column " + x);
			}
		}
		assertEquals("{\"animation\":{\"frametime\":7}}", Textures.waterMeta());
	}

	@Test
	void flowersCycleTheirThreeFrames() {
		int[] a = new int[64];
		int[] b = new int[64];
		int[] c = new int[64];
		java.util.Arrays.fill(b, 1);
		java.util.Arrays.fill(c, 3);
		Png.Image strip = Textures.flowerStrip(List.of(a, b, c), PALETTE);
		assertEquals(48, strip.height());
		assertEquals(0xFF000000 | PALETTE[1], strip.get(0, 16));
		assertEquals(0xFF000000 | PALETTE[3], strip.get(0, 32));
		assertEquals("{\"animation\":{\"frametime\":7,\"frames\":[0,1,2,0,0,1,2,0]}}", Textures.flowerMeta());
	}

	@Test
	void spriteSheetsUseTheFixedLayout() {
		// Six 16×16 frames; frame f has a single dark pixel at (f, 0), the rest is shade 0.
		Png.Image sheet = new Png.Image(16, 96);
		for (int i = 0; i < sheet.argb().length; i++) {
			sheet.argb()[i] = 0xFFFFFFFF;
		}
		for (int f = 0; f < 6; f++) {
			sheet.set(f, f * 16, 0xFF000000);
		}
		Png.Image out = Textures.spriteSheet(sheet, 6, PALETTE);
		int dark = 0xFF000000 | PALETTE[3];
		// Row 0 standing: down, up, left, right (left mirrored).
		assertEquals(dark, out.get(0, 0));
		assertEquals(dark, out.get(16 + 1, 0));
		assertEquals(dark, out.get(32 + 2, 0));
		assertEquals(dark, out.get(48 + 15 - 2, 0));
		// Row 1 walking.
		assertEquals(dark, out.get(3, 16));
		assertEquals(dark, out.get(16 + 4, 16));
		assertEquals(dark, out.get(32 + 5, 16));
		assertEquals(dark, out.get(48 + 15 - 5, 16));
		assertEquals(0, out.get(1, 0), "shade 0 is transparent");

		Png.Image still = Textures.spriteSheet(sheet, 1, PALETTE);
		assertEquals(dark, still.get(48, 16), "a one-frame sprite shows that frame everywhere, unmirrored");
	}

	@Test
	void pngRoundTrip() throws Exception {
		Png.Image image = new Png.Image(5, 3);
		for (int i = 0; i < 15; i++) {
			image.argb()[i] = (i * 0x10203) | (i % 2 == 0 ? 0xFF000000 : 0x80000000);
		}
		Png.Image read = Png.decode(Png.encode(image));
		assertArrayEquals(image.argb(), read.argb());
		assertArrayEquals(Png.encode(image), Png.encode(read), "encoding is deterministic");
		assertThrows(IOException.class, () -> Png.decode(new byte[] {1, 2, 3}));
	}

	/** Every PNG row filter decodes: LÖVE's encoder picks filters per row, unlike the fake exporter's filter 0. */
	@Test
	void allRowFiltersDecode() throws Exception {
		int width = 7;
		int height = 5;
		byte[][] rows = new byte[height][width * 4];
		for (int y = 0; y < height; y++) {
			for (int i = 0; i < width * 4; i++) {
				rows[y][i] = (byte) ((y * 37 + i * 11) ^ (i % 4 == 3 ? 0xFF : 0));
			}
		}
		ByteArrayOutputStream raw = new ByteArrayOutputStream();
		byte[] prior = new byte[width * 4];
		for (int y = 0; y < height; y++) {
			int filter = y % 5;
			raw.write(filter);
			raw.writeBytes(filterRow(filter, rows[y], prior, 4));
			prior = rows[y];
		}
		Png.Image image = Png.decode(png(width, height, raw.toByteArray()));
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				byte[] r = rows[y];
				int expected = ((r[x * 4 + 3] & 0xFF) << 24) | ((r[x * 4] & 0xFF) << 16) | ((r[x * 4 + 1] & 0xFF) << 8) | (r[x * 4 + 2] & 0xFF);
				assertEquals(expected, image.get(x, y), "row " + y + " (filter " + (y % 5) + ") pixel " + x);
			}
		}
	}

	private static byte[] filterRow(int filter, byte[] line, byte[] prior, int bpp) {
		byte[] out = new byte[line.length];
		for (int i = 0; i < line.length; i++) {
			int a = i >= bpp ? line[i - bpp] & 0xFF : 0;
			int b = prior[i] & 0xFF;
			int c = i >= bpp ? prior[i - bpp] & 0xFF : 0;
			int predictor = switch (filter) {
				case 1 -> a;
				case 2 -> b;
				case 3 -> (a + b) >> 1;
				case 4 -> {
					int p = a + b - c;
					int pa = Math.abs(p - a);
					int pb = Math.abs(p - b);
					int pc = Math.abs(p - c);
					yield pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
				}
				default -> 0;
			};
			out[i] = (byte) ((line[i] & 0xFF) - predictor);
		}
		return out;
	}

	private static byte[] png(int width, int height, byte[] raw) {
		Deflater deflater = new Deflater();
		deflater.setInput(raw);
		deflater.finish();
		ByteArrayOutputStream compressed = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		while (!deflater.finished()) {
			compressed.write(buffer, 0, deflater.deflate(buffer));
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
		chunk(out, "IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0)
				.put((byte) 0).array());
		chunk(out, "IDAT", compressed.toByteArray());
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	private static void chunk(ByteArrayOutputStream out, String type, byte[] body) {
		byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
		out.writeBytes(ByteBuffer.allocate(4).putInt(body.length).array());
		out.writeBytes(typeBytes);
		out.writeBytes(body);
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(body);
		out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
	}
}
