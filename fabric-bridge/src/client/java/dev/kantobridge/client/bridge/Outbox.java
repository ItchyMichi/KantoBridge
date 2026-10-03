package dev.kantobridge.client.bridge;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.Envelope;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import org.jetbrains.annotations.Nullable;

/**
 * The outgoing queue of one connection. Assigning {@code seq} and queueing happen under one lock, so sequence
 * numbers follow the order on the wire even when the socket thread (pings, errors) and the client thread (game
 * messages) send at once. Senders never block: the writer thread does the socket writes.
 */
final class Outbox {
	/** More than this many queued bytes means gen1-bridge stopped reading; the connection is dropped. */
	static final long MAX_QUEUED_BYTES = 64L * 1024 * 1024;

	private final int maxLineBytes;
	private final BridgeStats stats;
	private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
	private long seq;
	private long queuedBytes;
	private boolean shut;
	private boolean overflowed;

	Outbox(int maxLineBytes, BridgeStats stats) {
		this.maxLineBytes = maxLineBytes;
		this.stats = stats;
	}

	/**
	 * Numbers and queues one message.
	 *
	 * @return its seq, or -1 if the outbox is shut or full
	 * @throws IllegalArgumentException if the encoded line is longer than the protocol allows
	 */
	synchronized long enqueue(String type, @Nullable Long re, JsonObject data) {
		if (this.shut) {
			return -1;
		}
		long next = this.seq + 1;
		byte[] line = (new Envelope(type, next, re, data).toJson() + "\n").getBytes(StandardCharsets.UTF_8);
		if (line.length > this.maxLineBytes) {
			throw new IllegalArgumentException("message longer than the protocol line limit: " + type);
		}
		if (this.queuedBytes + line.length > MAX_QUEUED_BYTES) {
			this.overflowed = true;
			return -1;
		}
		this.seq = next;
		this.queue.addLast(line);
		this.queuedBytes += line.length;
		this.stats.messagesOut.incrementAndGet();
		this.stats.bytesOut.addAndGet(line.length);
		this.notifyAll();
		return next;
	}

	/** The next line to write, waiting for one; {@code null} once shut and drained. */
	synchronized byte @Nullable [] take() throws InterruptedException {
		while (this.queue.isEmpty()) {
			if (this.shut) {
				return null;
			}
			this.wait();
		}
		byte[] line = this.queue.removeFirst();
		this.queuedBytes -= line.length;
		return line;
	}

	synchronized boolean isEmpty() {
		return this.queue.isEmpty();
	}

	synchronized boolean overflowed() {
		return this.overflowed;
	}

	/** No more messages; the writer stops once the queue is empty. */
	synchronized void shut() {
		this.shut = true;
		this.notifyAll();
	}
}
