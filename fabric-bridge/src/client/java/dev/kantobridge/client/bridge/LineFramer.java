package dev.kantobridge.client.bridge;

import dev.kantobridge.proto.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Splits a byte stream into newline-delimited lines (docs/PROTOCOL.md "Framing"): reads may end anywhere, even
 * inside a UTF-8 sequence. Lines may end in CRLF and empty lines are skipped. A line (with its newline) longer than
 * the limit can never be accepted, so it fails as soon as the buffered bytes pass the limit.
 */
public final class LineFramer {
	/** One complete line without its line ending, and its size in bytes. */
	public record Line(String text, int bytes) {
	}

	private final int maxLineBytes;
	private byte[] buffer = new byte[8192];
	private int length;
	private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT);

	public LineFramer(int maxLineBytes) {
		if (maxLineBytes < 2) {
			throw new IllegalArgumentException("maxLineBytes too small");
		}
		this.maxLineBytes = maxLineBytes;
	}

	/** Adds bytes and returns the lines they completed. */
	public List<Line> feed(byte[] data, int offset, int count) throws ProtocolException {
		// Bytes already buffered hold no newline (complete lines leave the buffer), so scan only the new ones:
		// a 4 MiB line arriving in small reads stays linear.
		int scanFrom = this.length;
		this.append(data, offset, count);
		List<Line> lines = new ArrayList<>();
		int start = 0;
		for (int i = scanFrom; i < this.length; i++) {
			if (this.buffer[i] != '\n') {
				continue;
			}
			int end = i;
			if (end > start && this.buffer[end - 1] == '\r') {
				end--;
			}
			if (i - start + 1 > this.maxLineBytes) {
				throw new ProtocolException("frame.too_long", "line longer than the protocol limit");
			}
			if (end > start) {
				lines.add(new Line(this.decode(start, end - start), end - start));
			}
			start = i + 1;
		}
		if (start > 0) {
			System.arraycopy(this.buffer, start, this.buffer, 0, this.length - start);
			this.length -= start;
		}
		if (this.length + 1 > this.maxLineBytes) {
			throw new ProtocolException("frame.too_long", "line longer than the protocol limit");
		}
		return lines;
	}

	/** Bytes of an unfinished line waiting for its newline. */
	public int pending() {
		return this.length;
	}

	private void append(byte[] data, int offset, int count) {
		if (this.length + count > this.buffer.length) {
			int size = this.buffer.length;
			while (size < this.length + count) {
				size *= 2;
			}
			this.buffer = Arrays.copyOf(this.buffer, size);
		}
		System.arraycopy(data, offset, this.buffer, this.length, count);
		this.length += count;
	}

	private String decode(int offset, int count) throws ProtocolException {
		try {
			CharBuffer chars = this.decoder.reset().decode(ByteBuffer.wrap(this.buffer, offset, count));
			return chars.toString();
		} catch (CharacterCodingException e) {
			throw new ProtocolException("frame.utf8", "line is not valid UTF-8");
		}
	}
}
