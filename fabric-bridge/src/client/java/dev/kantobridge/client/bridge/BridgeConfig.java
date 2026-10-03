package dev.kantobridge.client.bridge;

import dev.kantobridge.proto.Catalog;

/**
 * Connection settings. The timings come from the protocol catalog (docs/PROTOCOL.md: ping every 2 s, drop after
 * 10 s of silence, reconnect from 0.5 s doubling to 5 s); tests shorten them with the {@code with…} methods.
 *
 * @param host               always loopback: gen1-bridge only listens on 127.0.0.1
 * @param port               from the client config (default 27151)
 * @param pingMs             heartbeat interval once connected
 * @param timeoutMs          silence after which the link is dropped
 * @param reconnectInitialMs first reconnect delay
 * @param reconnectMaxMs     longest reconnect delay
 * @param maxLineBytes       longest line, newline included
 * @param tickMs             how often the socket thread wakes up to run timers while no data arrives
 * @param connectTimeoutMs   TCP connect timeout
 */
public record BridgeConfig(String host, int port, long pingMs, long timeoutMs, long reconnectInitialMs, long reconnectMaxMs,
		int maxLineBytes, int tickMs, int connectTimeoutMs) {
	public static final String LOOPBACK = "127.0.0.1";

	public BridgeConfig {
		if (port < 1 || port > 65535) {
			throw new IllegalArgumentException("port out of range");
		}
		if (pingMs <= 0 || timeoutMs <= pingMs || reconnectInitialMs <= 0 || reconnectMaxMs < reconnectInitialMs
				|| maxLineBytes < 2 || tickMs <= 0 || connectTimeoutMs <= 0) {
			throw new IllegalArgumentException("invalid bridge timings");
		}
	}

	public static BridgeConfig fromCatalog(Catalog catalog, int port) {
		return new BridgeConfig(LOOPBACK, port, catalog.pingMs(), catalog.timeoutMs(), catalog.reconnectInitialMs(),
				catalog.reconnectMaxMs(), (int) Math.min(Integer.MAX_VALUE, catalog.maxLineBytes()), 100, 2000);
	}

	public BridgeConfig withHeartbeat(long pingMs, long timeoutMs) {
		return new BridgeConfig(this.host, this.port, pingMs, timeoutMs, this.reconnectInitialMs, this.reconnectMaxMs,
				this.maxLineBytes, this.tickMs, this.connectTimeoutMs);
	}

	public BridgeConfig withReconnect(long initialMs, long maxMs) {
		return new BridgeConfig(this.host, this.port, this.pingMs, this.timeoutMs, initialMs, maxMs, this.maxLineBytes,
				this.tickMs, this.connectTimeoutMs);
	}

	public BridgeConfig withTick(int tickMs) {
		return new BridgeConfig(this.host, this.port, this.pingMs, this.timeoutMs, this.reconnectInitialMs, this.reconnectMaxMs,
				this.maxLineBytes, tickMs, this.connectTimeoutMs);
	}
}
