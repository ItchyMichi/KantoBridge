package dev.kantobridge.world;

import com.google.gson.JsonElement;
import dev.kantobridge.proto.ProtoJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 in the protocol's form: lowercase hex of the UTF-8 bytes (docs/PROTOCOL.md "Hashes and the bundle"). */
public final class Hashes {
	private Hashes() {
	}

	public static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is missing from this JVM", e);
		}
	}

	public static String sha256(byte[] bytes) {
		return HexFormat.of().formatHex(sha256().digest(bytes));
	}

	public static String sha256(String text) {
		return sha256(text.getBytes(StandardCharsets.UTF_8));
	}

	/** The hash of a value's canonical JSON (sorted keys, compact, UTF-8), as the export index uses. */
	public static String canonical(JsonElement value) {
		return sha256(ProtoJson.canonicalUtf8(value));
	}

	/** The first 8 hex digits, for log lines. */
	public static String shortHash(String hash) {
		return hash.length() > 8 ? hash.substring(0, 8) : hash;
	}
}
