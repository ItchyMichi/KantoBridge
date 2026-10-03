package dev.kantobridge.client.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.proto.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BackoffAndFramingTest {
	@Test
	void backoffDoublesToTheMaximumAndResets() {
		Backoff backoff = new Backoff(500, 5000);
		long[] delays = new long[7];
		for (int i = 0; i < delays.length; i++) {
			delays[i] = backoff.next();
		}
		assertEquals("[500, 1000, 2000, 4000, 5000, 5000, 5000]", Arrays.toString(delays));
		assertEquals(5000, backoff.max());
		backoff.reset();
		assertEquals(500, backoff.next());
		assertThrows(IllegalArgumentException.class, () -> new Backoff(0, 10));
		assertThrows(IllegalArgumentException.class, () -> new Backoff(10, 5));
	}

	@Test
	void framerReassemblesLinesFromAnySplit() throws ProtocolException {
		byte[] stream = "{\"a\":1}\n\r\n{\"é\":\"♂\"}\r\n\n{\"b\":2}\n".getBytes(StandardCharsets.UTF_8);
		for (int chunk = 1; chunk <= stream.length; chunk++) {
			LineFramer framer = new LineFramer(1024);
			List<String> lines = new ArrayList<>();
			for (int i = 0; i < stream.length; i += chunk) {
				for (LineFramer.Line line : framer.feed(stream, i, Math.min(chunk, stream.length - i))) {
					lines.add(line.text());
				}
			}
			assertEquals(List.of("{\"a\":1}", "{\"é\":\"♂\"}", "{\"b\":2}"), lines, "chunk " + chunk);
			assertEquals(0, framer.pending());
		}
	}

	@Test
	void framerCountsBytesWithoutTheLineEnding() throws ProtocolException {
		LineFramer framer = new LineFramer(1024);
		List<LineFramer.Line> lines = framer.feed("é\r\n".getBytes(StandardCharsets.UTF_8), 0, 4);
		assertEquals(1, lines.size());
		assertEquals(2, lines.get(0).bytes());
	}

	@Test
	void framerRejectsLongLinesAsSoonAsTheyCannotFit() {
		LineFramer framer = new LineFramer(16);
		// 15 bytes + newline fits exactly.
		assertEquals(1, assertDoesNotThrowFeed(framer, "x".repeat(15) + "\n").size());
		ProtocolException complete = assertThrows(ProtocolException.class,
				() -> new LineFramer(16).feed(bytes("y".repeat(16) + "\n"), 0, 17));
		assertEquals("frame.too_long", complete.code());
		LineFramer partial = new LineFramer(16);
		ProtocolException pending = assertThrows(ProtocolException.class, () -> partial.feed(bytes("z".repeat(16)), 0, 16));
		assertEquals("frame.too_long", pending.code());
	}

	@Test
	void framerRejectsInvalidUtf8() {
		byte[] bad = {'{', '"', (byte) 0xff, '"', '}', '\n'};
		ProtocolException e = assertThrows(ProtocolException.class, () -> new LineFramer(64).feed(bad, 0, bad.length));
		assertEquals("frame.utf8", e.code());
	}

	@Test
	void framerStaysLinearForAHugeLineInSmallReads() throws ProtocolException {
		int size = 4 * 1024 * 1024 - 1;
		byte[] line = new byte[size + 1];
		Arrays.fill(line, (byte) 'a');
		line[size] = '\n';
		LineFramer framer = new LineFramer(4 * 1024 * 1024);
		long start = System.nanoTime();
		List<LineFramer.Line> lines = new ArrayList<>();
		for (int i = 0; i < line.length; i += 4096) {
			lines.addAll(framer.feed(line, i, Math.min(4096, line.length - i)));
		}
		assertEquals(1, lines.size());
		assertEquals(size, lines.get(0).bytes());
		assertTrue(System.nanoTime() - start < 5_000_000_000L, "framing a 4 MiB line took too long");
	}

	private static List<LineFramer.Line> assertDoesNotThrowFeed(LineFramer framer, String text) {
		try {
			byte[] data = bytes(text);
			return framer.feed(data, 0, data.length);
		} catch (ProtocolException e) {
			throw new AssertionError("unexpected " + e.code(), e);
		}
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}
}
