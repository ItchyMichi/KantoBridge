package dev.kantobridge.world;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The two shape documents on the wire and in a client's cache (docs/specs/VOXEL_BLOCKS_PLAN.md §4.4; the follow-up's
 * item 4, the plan's R10): each travels as Base64 of its raw-deflated bytes (the shape index's UTF-8 text, the shape
 * map's file bytes) and is named by the SHA-256 of what it holds, so a client keeps what it received and asks only for
 * what it lacks ({@code world.shapes.offer}, {@code world.shapes.get}). Pure.
 */
public final class ShapeDocs {
	/** {@code world.shapes.enc}: the index document is deflated (the map always is). */
	public static final String ENC_DEFLATE = "deflate";

	private ShapeDocs() {
	}

	/** Raw deflate at the best compression (the shape map's wire form since V2). */
	public static byte[] deflate(byte[] bytes) {
		Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
		deflater.setInput(bytes);
		deflater.finish();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[65536];
		while (!deflater.finished()) {
			out.write(buffer, 0, deflater.deflate(buffer));
		}
		deflater.end();
		return out.toByteArray();
	}

	/** Inflates raw-deflated bytes; refuses more than {@code maxBytes} once inflated. */
	public static byte[] inflate(byte[] deflated, int maxBytes) throws DataException {
		Inflater inflater = new Inflater(true);
		inflater.setInput(deflated);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[65536];
		try {
			while (!inflater.finished()) {
				int n = inflater.inflate(buffer);
				if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
					throw new DataException("/", "truncated");
				}
				out.write(buffer, 0, n);
				if (out.size() > maxBytes) {
					throw new DataException("/", "too large");
				}
			}
		} catch (DataFormatException e) {
			throw new DataException("/", "not deflated");
		} finally {
			inflater.end();
		}
		return out.toByteArray();
	}

	/** The wire form of bytes: Base64 of their raw deflate. */
	public static String wire(byte[] bytes) {
		return Base64.getEncoder().encodeToString(deflate(bytes));
	}

	/** The wire form of a text document (the shape index): Base64 of its deflated UTF-8. */
	public static String wire(String text) {
		return wire(text.getBytes(StandardCharsets.UTF_8));
	}

	/** Reads a wire form back into bytes; refuses more than {@code maxBytes} once inflated. */
	public static byte[] unwire(String wire, int maxBytes) throws DataException {
		byte[] deflated;
		try {
			deflated = Base64.getDecoder().decode(wire);
		} catch (IllegalArgumentException e) {
			throw new DataException("/", "not base64");
		}
		return inflate(deflated, maxBytes);
	}

	/** Reads a text document's wire form back (strict UTF-8). */
	public static String unwireText(String wire, int maxBytes) throws DataException {
		return text(unwire(wire, maxBytes));
	}

	/** Strict UTF-8: malformed bytes are a {@link DataException}, never replacement characters. */
	public static String text(byte[] bytes) throws DataException {
		try {
			return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			throw new DataException("/", "not utf-8");
		}
	}
}
