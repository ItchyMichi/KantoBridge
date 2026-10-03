package dev.kantobridge.builder.export;

import dev.kantobridge.world.DataException;
import org.jetbrains.annotations.Nullable;

/**
 * An export bundle that can't be used: a missing or unreadable file, a hash or size mismatch, or a resource without
 * the expected shape. The code is machine-readable ({@code bundle.missing}, {@code bundle.hash}, ...); the message
 * names only kinds, ids and JSON paths, never content, so it is safe for logs and build reports.
 */
public class BundleException extends Exception {
	private final String code;

	public BundleException(String code, String message) {
		super(code + ": " + message);
		this.code = code;
	}

	public BundleException(String code, String message, @Nullable Throwable cause) {
		super(code + ": " + message, cause);
		this.code = code;
	}

	public static BundleException format(String kind, String id, DataException e) {
		return new BundleException("bundle.format", kind + "/" + id + " " + e.getMessage(), e);
	}

	public String code() {
		return this.code;
	}
}
