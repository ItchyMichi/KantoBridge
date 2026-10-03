package dev.kantobridge.client.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.Nullable;

/**
 * An in-memory {@link Transport} for tests, with the real socket's read-timeout contract. The test plays gen1-bridge
 * through each accepted {@link Pipe}.
 */
final class PipeTransport implements Transport {
	final BlockingQueue<Pipe> accepted = new LinkedBlockingQueue<>();
	final AtomicInteger attempts = new AtomicInteger();
	/** How many of the next connection attempts fail as "refused". */
	final AtomicInteger refuse = new AtomicInteger();

	@Override
	public Link connect(String host, int port, int connectTimeoutMs, int readTimeoutMs) throws IOException {
		this.attempts.incrementAndGet();
		if (this.refuse.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
			throw new ConnectException("refused (test)");
		}
		Pipe pipe = new Pipe(readTimeoutMs);
		this.accepted.add(pipe);
		return pipe;
	}

	Pipe nextPipe() throws InterruptedException {
		Pipe pipe = this.accepted.poll(5, TimeUnit.SECONDS);
		if (pipe == null) {
			throw new AssertionError("the client did not connect");
		}
		return pipe;
	}

	/** One connection: the client's side is the {@link Link}; the test's side is the server* methods. */
	static final class Pipe implements Link {
		private static final byte[] EOF = new byte[0];

		private final int readTimeoutMs;
		private final BlockingQueue<byte[]> toClient = new LinkedBlockingQueue<>();
		private final BlockingQueue<byte[]> fromClient = new LinkedBlockingQueue<>();
		private final ByteArrayOutputStream serverBuffer = new ByteArrayOutputStream();
		volatile boolean closed;
		/** While true, the client's writes hang until the link closes, like a peer that stopped reading. */
		volatile boolean blockWrites;

		Pipe(int readTimeoutMs) {
			this.readTimeoutMs = readTimeoutMs;
		}

		@Override
		public InputStream input() {
			return new InputStream() {
				private byte @Nullable [] current;
				private int offset;

				@Override
				public int read() throws IOException {
					byte[] one = new byte[1];
					int n = this.read(one, 0, 1);
					return n < 0 ? -1 : one[0] & 0xff;
				}

				@Override
				public int read(byte[] buffer, int off, int len) throws IOException {
					if (this.current == null || this.offset >= this.current.length) {
						byte[] next;
						try {
							next = Pipe.this.toClient.poll(Pipe.this.readTimeoutMs, TimeUnit.MILLISECONDS);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							throw new SocketException("interrupted");
						}
						if (Pipe.this.closed) {
							throw new SocketException("closed");
						}
						if (next == null) {
							throw new SocketTimeoutException("read timed out");
						}
						if (next == EOF) {
							return -1;
						}
						this.current = next;
						this.offset = 0;
					}
					int n = Math.min(len, this.current.length - this.offset);
					System.arraycopy(this.current, this.offset, buffer, off, n);
					this.offset += n;
					return n;
				}
			};
		}

		@Override
		public OutputStream output() {
			return new OutputStream() {
				@Override
				public void write(int b) throws IOException {
					this.write(new byte[] {(byte) b}, 0, 1);
				}

				@Override
				public void write(byte[] buffer, int off, int len) throws IOException {
					while (Pipe.this.blockWrites && !Pipe.this.closed) {
						try {
							Thread.sleep(5);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							throw new SocketException("interrupted");
						}
					}
					if (Pipe.this.closed) {
						throw new SocketException("closed");
					}
					byte[] copy = new byte[len];
					System.arraycopy(buffer, off, copy, 0, len);
					Pipe.this.fromClient.add(copy);
				}
			};
		}

		@Override
		public void close() {
			this.closed = true;
			this.toClient.add(EOF); // wakes a blocked read
		}

		// ---- the test's side ----

		void serverSend(String line) {
			this.toClient.add((line + "\n").getBytes(StandardCharsets.UTF_8));
		}

		void serverSendRaw(byte[] bytes) {
			this.toClient.add(bytes);
		}

		/** The peer hangs up: the client reads end of stream. */
		void serverHangUp() {
			this.toClient.add(EOF);
		}

		/** The next line the client wrote, or an AssertionError after 5 s. */
		String serverReadLine() throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (true) {
				byte[] buffered = this.serverBuffer.toByteArray();
				for (int i = 0; i < buffered.length; i++) {
					if (buffered[i] == '\n') {
						String line = new String(buffered, 0, i, StandardCharsets.UTF_8);
						this.serverBuffer.reset();
						this.serverBuffer.write(buffered, i + 1, buffered.length - i - 1);
						return line;
					}
				}
				long left = deadline - System.nanoTime();
				if (left <= 0) {
					throw new AssertionError("the client sent no complete line");
				}
				byte[] chunk = this.fromClient.poll(left, TimeUnit.NANOSECONDS);
				if (chunk != null) {
					this.serverBuffer.write(chunk, 0, chunk.length);
				}
			}
		}
	}
}
