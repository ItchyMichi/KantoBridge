package dev.kantobridge.client.bridge;

/** Where the client's link to gen1-bridge stands. */
public enum ConnectionState {
	/** Created, not started. */
	IDLE,
	/** Opening the TCP connection. */
	CONNECTING,
	/** Connected; {@code hello} sent, waiting for {@code hello.ok}. */
	HANDSHAKE,
	/** {@code hello.ok} received; messages flow. */
	CONNECTED,
	/** gen1-bridge sent {@code hello.reject}; retrying at the longest backoff. */
	REJECTED,
	/** No connection (refused, dropped or timed out); retrying with backoff. */
	DISCONNECTED,
	/** Closed for good. */
	STOPPED
}
