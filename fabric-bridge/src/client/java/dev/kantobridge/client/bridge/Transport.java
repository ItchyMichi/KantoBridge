package dev.kantobridge.client.bridge;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Opens the byte stream to gen1-bridge. The real one is a loopback TCP socket; tests substitute in-memory pipes.
 *
 * <p>Contract: {@link Link#input()} reads block for at most the read timeout given to {@link #connect} and then
 * throw {@link java.net.SocketTimeoutException}, so the socket thread can run its timers; closing the link from
 * another thread makes a blocked read fail at once.
 */
public interface Transport {
	Link connect(String host, int port, int connectTimeoutMs, int readTimeoutMs) throws IOException;

	/** One open connection. */
	interface Link extends Closeable {
		InputStream input() throws IOException;

		OutputStream output() throws IOException;

		/** Idempotent, callable from any thread; never throws. */
		@Override
		void close();
	}

	/** A plain TCP socket. */
	static Transport socket() {
		return (host, port, connectTimeoutMs, readTimeoutMs) -> {
			Socket socket = new Socket();
			try {
				// Small messages (steps, pings) should leave at once rather than wait for Nagle.
				socket.setTcpNoDelay(true);
				socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
				socket.setSoTimeout(readTimeoutMs);
				return new SocketLink(socket);
			} catch (IOException e) {
				socket.close();
				throw e;
			}
		};
	}

	/** {@link Link} over a {@link Socket}. */
	final class SocketLink implements Link {
		private final Socket socket;

		SocketLink(Socket socket) {
			this.socket = socket;
		}

		@Override
		public InputStream input() throws IOException {
			return this.socket.getInputStream();
		}

		@Override
		public OutputStream output() throws IOException {
			return this.socket.getOutputStream();
		}

		@Override
		public void close() {
			try {
				this.socket.close();
			} catch (IOException ignored) {
				// Closing a dead socket can fail; it is closed either way.
			}
		}
	}
}
