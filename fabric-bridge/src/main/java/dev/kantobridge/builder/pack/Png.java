package dev.kantobridge.builder.pack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * A small PNG codec, so the builder needs neither AWT nor the client's {@code NativeImage} and runs the same on a
 * dedicated server (docs/PLAN.md §4.5). Decodes non-interlaced 1/2/4/8-bit greyscale, grey+alpha, RGB, RGBA and
 * palette images (what LÖVE and the fake exporter write); encodes 8-bit RGBA. Output is deterministic for the same
 * pixels, so pack hashes are stable.
 */
public final class Png {
	private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
	private static final int MAX_SIDE = 4096;

	/** Pixels as {@code 0xAARRGGBB}, row-major. */
	public record Image(int width, int height, int[] argb) {
		public Image {
			if (width < 1 || height < 1 || argb.length != width * height) {
				throw new IllegalArgumentException("bad image size");
			}
		}

		public Image(int width, int height) {
			this(width, height, new int[width * height]);
		}

		public int get(int x, int y) {
			return this.argb[y * this.width + x];
		}

		public void set(int x, int y, int value) {
			this.argb[y * this.width + x] = value;
		}
	}

	private Png() {
	}

	// ---- decoding ----

	public static Image decode(byte[] data) throws IOException {
		ByteBuffer in = ByteBuffer.wrap(data);
		for (byte b : SIGNATURE) {
			if (!in.hasRemaining() || in.get() != b) {
				throw new IOException("not a PNG");
			}
		}
		int width = 0;
		int height = 0;
		int depth = 0;
		int colourType = -1;
		int[] palette = null;
		int[] paletteAlpha = null;
		ByteArrayOutputStream idat = new ByteArrayOutputStream();
		boolean end = false;
		while (!end) {
			if (in.remaining() < 12) {
				throw new IOException("truncated PNG");
			}
			int length = in.getInt();
			byte[] typeBytes = new byte[4];
			in.get(typeBytes);
			String type = new String(typeBytes, StandardCharsets.US_ASCII);
			if (length < 0 || in.remaining() < length + 4) {
				throw new IOException("truncated PNG chunk");
			}
			byte[] body = new byte[length];
			in.get(body);
			int crc = in.getInt();
			CRC32 check = new CRC32();
			check.update(typeBytes);
			check.update(body);
			if ((int) check.getValue() != crc) {
				throw new IOException("PNG chunk CRC mismatch");
			}
			ByteBuffer chunk = ByteBuffer.wrap(body);
			switch (type) {
				case "IHDR" -> {
					width = chunk.getInt();
					height = chunk.getInt();
					depth = chunk.get() & 0xFF;
					colourType = chunk.get() & 0xFF;
					int compression = chunk.get() & 0xFF;
					int filter = chunk.get() & 0xFF;
					int interlace = chunk.get() & 0xFF;
					if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE) {
						throw new IOException("unsupported PNG size");
					}
					if (compression != 0 || filter != 0 || interlace != 0) {
						throw new IOException("unsupported PNG (interlaced or unknown method)");
					}
					if (!supported(colourType, depth)) {
						throw new IOException("unsupported PNG colour type or depth");
					}
				}
				case "PLTE" -> {
					palette = new int[length / 3];
					for (int i = 0; i < palette.length; i++) {
						palette[i] = ((body[i * 3] & 0xFF) << 16) | ((body[i * 3 + 1] & 0xFF) << 8) | (body[i * 3 + 2] & 0xFF);
					}
				}
				case "tRNS" -> {
					paletteAlpha = new int[length];
					for (int i = 0; i < length; i++) {
						paletteAlpha[i] = body[i] & 0xFF;
					}
				}
				case "IDAT" -> idat.write(body);
				case "IEND" -> end = true;
				default -> {
					// Ancillary chunks (gamma, text, ...) don't change our 4-shade art.
				}
			}
		}
		if (colourType < 0) {
			throw new IOException("PNG without IHDR");
		}
		if (colourType == 3 && palette == null) {
			throw new IOException("palette PNG without PLTE");
		}
		int channels = switch (colourType) {
			case 0, 3 -> 1;
			case 2 -> 3;
			case 4 -> 2;
			default -> 4;
		};
		int bitsPerPixel = channels * depth;
		int stride = (width * bitsPerPixel + 7) / 8;
		int bpp = Math.max(1, bitsPerPixel / 8);
		byte[] raw = inflate(idat.toByteArray(), (stride + 1) * height);
		byte[] current = new byte[stride];
		byte[] previous = new byte[stride];
		Image image = new Image(width, height);
		int pos = 0;
		for (int y = 0; y < height; y++) {
			int filter = raw[pos++] & 0xFF;
			System.arraycopy(raw, pos, current, 0, stride);
			pos += stride;
			unfilter(filter, current, previous, bpp);
			for (int x = 0; x < width; x++) {
				image.set(x, y, pixel(current, x, colourType, depth, palette, paletteAlpha));
			}
			byte[] swap = previous;
			previous = current;
			current = swap;
		}
		return image;
	}

	private static boolean supported(int colourType, int depth) {
		return switch (colourType) {
			case 0 -> depth == 1 || depth == 2 || depth == 4 || depth == 8;
			case 3 -> depth == 1 || depth == 2 || depth == 4 || depth == 8;
			case 2, 4, 6 -> depth == 8;
			default -> false;
		};
	}

	private static byte[] inflate(byte[] compressed, int expected) throws IOException {
		Inflater inflater = new Inflater();
		inflater.setInput(compressed);
		byte[] out = new byte[expected];
		try {
			int n = 0;
			while (n < expected && !inflater.finished()) {
				int read = inflater.inflate(out, n, expected - n);
				if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
					break;
				}
				n += read;
			}
			if (n != expected) {
				throw new IOException("PNG image data is short");
			}
			return out;
		} catch (DataFormatException e) {
			throw new IOException("corrupt PNG image data", e);
		} finally {
			inflater.end();
		}
	}

	private static void unfilter(int filter, byte[] line, byte[] prior, int bpp) throws IOException {
		switch (filter) {
			case 0 -> {
			}
			case 1 -> {
				for (int i = bpp; i < line.length; i++) {
					line[i] = (byte) (line[i] + line[i - bpp]);
				}
			}
			case 2 -> {
				for (int i = 0; i < line.length; i++) {
					line[i] = (byte) (line[i] + prior[i]);
				}
			}
			case 3 -> {
				for (int i = 0; i < line.length; i++) {
					int left = i >= bpp ? line[i - bpp] & 0xFF : 0;
					line[i] = (byte) (line[i] + ((left + (prior[i] & 0xFF)) >> 1));
				}
			}
			case 4 -> {
				for (int i = 0; i < line.length; i++) {
					int a = i >= bpp ? line[i - bpp] & 0xFF : 0;
					int b = prior[i] & 0xFF;
					int c = i >= bpp ? prior[i - bpp] & 0xFF : 0;
					line[i] = (byte) (line[i] + paeth(a, b, c));
				}
			}
			default -> throw new IOException("unknown PNG filter");
		}
	}

	private static int paeth(int a, int b, int c) {
		int p = a + b - c;
		int pa = Math.abs(p - a);
		int pb = Math.abs(p - b);
		int pc = Math.abs(p - c);
		if (pa <= pb && pa <= pc) {
			return a;
		}
		return pb <= pc ? b : c;
	}

	private static int pixel(byte[] line, int x, int colourType, int depth, int[] palette, int[] paletteAlpha) throws IOException {
		switch (colourType) {
			case 0 -> {
				int grey = sample(line, x, depth);
				int value = depth == 8 ? grey : grey * 255 / ((1 << depth) - 1);
				return 0xFF000000 | (value << 16) | (value << 8) | value;
			}
			case 3 -> {
				int index = sample(line, x, depth);
				if (index >= palette.length) {
					throw new IOException("PNG palette index out of range");
				}
				int alpha = paletteAlpha != null && index < paletteAlpha.length ? paletteAlpha[index] : 255;
				return (alpha << 24) | palette[index];
			}
			case 2 -> {
				int i = x * 3;
				return 0xFF000000 | ((line[i] & 0xFF) << 16) | ((line[i + 1] & 0xFF) << 8) | (line[i + 2] & 0xFF);
			}
			case 4 -> {
				int grey = line[x * 2] & 0xFF;
				int alpha = line[x * 2 + 1] & 0xFF;
				return (alpha << 24) | (grey << 16) | (grey << 8) | grey;
			}
			default -> {
				int i = x * 4;
				return ((line[i + 3] & 0xFF) << 24) | ((line[i] & 0xFF) << 16) | ((line[i + 1] & 0xFF) << 8) | (line[i + 2] & 0xFF);
			}
		}
	}

	private static int sample(byte[] line, int x, int depth) {
		if (depth == 8) {
			return line[x] & 0xFF;
		}
		int perByte = 8 / depth;
		int b = line[x / perByte] & 0xFF;
		int shift = 8 - depth * (x % perByte + 1);
		return (b >> shift) & ((1 << depth) - 1);
	}

	// ---- encoding ----

	/** 8-bit RGBA, filter 0 on every row, maximum compression. */
	public static byte[] encode(Image image) {
		int width = image.width();
		int height = image.height();
		byte[] raw = new byte[(width * 4 + 1) * height];
		int pos = 0;
		for (int y = 0; y < height; y++) {
			raw[pos++] = 0;
			for (int x = 0; x < width; x++) {
				int argb = image.get(x, y);
				raw[pos++] = (byte) (argb >> 16);
				raw[pos++] = (byte) (argb >> 8);
				raw[pos++] = (byte) argb;
				raw[pos++] = (byte) (argb >>> 24);
			}
		}
		Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
		deflater.setInput(raw);
		deflater.finish();
		ByteArrayOutputStream compressed = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		while (!deflater.finished()) {
			int n = deflater.deflate(buffer);
			compressed.write(buffer, 0, n);
		}
		deflater.end();

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(SIGNATURE);
		ByteBuffer header = ByteBuffer.allocate(13);
		header.putInt(width).putInt(height).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
		chunk(out, "IHDR", header.array());
		chunk(out, "IDAT", compressed.toByteArray());
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	private static void chunk(ByteArrayOutputStream out, String type, byte[] body) {
		byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
		ByteBuffer length = ByteBuffer.allocate(4).putInt(body.length);
		out.writeBytes(length.array());
		out.writeBytes(typeBytes);
		out.writeBytes(body);
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(body);
		out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
	}
}
