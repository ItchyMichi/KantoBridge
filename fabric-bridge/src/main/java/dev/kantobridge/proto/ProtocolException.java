package dev.kantobridge.proto;

/**
 * A message that breaks the protocol (docs/PROTOCOL.md). The {@link #code()} is a short machine-readable reason
 * such as {@code json.trailing} or {@code catalog.leg}; the message never quotes message content, so it is safe to
 * log (docs/PLAN.md §0.3).
 */
public class ProtocolException extends Exception {
	private final String code;

	public ProtocolException(String code, String message) {
		super(message);
		this.code = code;
	}

	public String code() {
		return this.code;
	}
}
