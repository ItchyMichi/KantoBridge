package dev.kantobridge.builder.plan;

/** A build that can't be planned (e.g. the slot map is full). The code is machine-readable; the message holds no content. */
public class PlanException extends Exception {
	private final String code;

	public PlanException(String code, String message) {
		super(code + ": " + message);
		this.code = code;
	}

	public String code() {
		return this.code;
	}
}
