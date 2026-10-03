package dev.kantobridge.proto;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigInteger;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * One protocol message: {@code {"type", "seq", "re"?, "data"}} (protocol/envelope.schema.json). Used on the
 * gen1-bridge socket and inside the {@code kantobridge:json} relay payload alike.
 *
 * @param type message type, lowercase dotted words
 * @param seq  counted from 1 per sender and per connection
 * @param re   on replies only, the {@code seq} being answered
 * @param data the message body; validated per type by the schemas in protocol/messages
 */
public record Envelope(String type, long seq, @Nullable Long re, JsonObject data) {
	public static final Pattern TYPE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$");
	public static final int MAX_TYPE_LENGTH = 64;
	/** 2^53 - 1: the largest integer every side (Lua doubles included) represents exactly. */
	public static final long MAX_SEQ = 9007199254740991L;

	public Envelope {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(data, "data");
		if (type.length() > MAX_TYPE_LENGTH || !TYPE_PATTERN.matcher(type).matches()) {
			throw new IllegalArgumentException("invalid message type");
		}
		if (seq < 1 || seq > MAX_SEQ) {
			throw new IllegalArgumentException("seq out of range");
		}
		if (re != null && (re < 1 || re > MAX_SEQ)) {
			throw new IllegalArgumentException("re out of range");
		}
	}

	public static Envelope of(String type, long seq, JsonObject data) {
		return new Envelope(type, seq, null, data);
	}

	public static Envelope reply(String type, long seq, long re, JsonObject data) {
		return new Envelope(type, seq, re, data);
	}

	/** Parses and checks one envelope (one NDJSON line or one relay payload). */
	public static Envelope parse(String json) throws ProtocolException {
		return fromJson(ProtoJson.parseObject(json));
	}

	/** Checks the envelope's own shape; the data's shape is the schemas' job. */
	public static Envelope fromJson(JsonObject object) throws ProtocolException {
		for (String key : object.keySet()) {
			if (!key.equals("type") && !key.equals("seq") && !key.equals("re") && !key.equals("data")) {
				throw new ProtocolException("envelope.field", "unknown envelope field");
			}
		}
		JsonElement type = object.get("type");
		if (type == null || !type.isJsonPrimitive() || !type.getAsJsonPrimitive().isString()) {
			throw new ProtocolException("envelope.type", "type must be a string");
		}
		String typeName = type.getAsString();
		if (typeName.length() > MAX_TYPE_LENGTH || !TYPE_PATTERN.matcher(typeName).matches()) {
			throw new ProtocolException("envelope.type", "type is not lowercase dotted words");
		}
		long seq = sequenceNumber(object.get("seq"), "seq");
		Long re = object.has("re") ? sequenceNumber(object.get("re"), "re") : null;
		JsonElement data = object.get("data");
		if (data == null || !data.isJsonObject()) {
			throw new ProtocolException("envelope.data", "data must be an object");
		}
		return new Envelope(typeName, seq, re, data.getAsJsonObject());
	}

	private static long sequenceNumber(@Nullable JsonElement value, String field) throws ProtocolException {
		if (value == null) {
			throw new ProtocolException("envelope." + field, field + " is missing");
		}
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
			Number number = value.getAsNumber();
			// Only integer literals: ProtoJson parses those as Long or BigInteger, never as a decimal.
			if (number instanceof Long || number instanceof Integer) {
				long n = number.longValue();
				if (n >= 1 && n <= MAX_SEQ) {
					return n;
				}
			} else if (number instanceof BigInteger) {
				throw new ProtocolException("envelope." + field, field + " out of range");
			}
		}
		throw new ProtocolException("envelope." + field, field + " must be an integer from 1 to 2^53-1");
	}

	public JsonObject toJsonObject() {
		JsonObject object = new JsonObject();
		object.addProperty("type", this.type);
		object.add("seq", new JsonPrimitive(this.seq));
		if (this.re != null) {
			object.add("re", new JsonPrimitive(this.re));
		}
		object.add("data", this.data);
		return object;
	}

	/** Canonical one-line JSON, ready to send (no trailing newline). */
	public String toJson() {
		return ProtoJson.write(this.toJsonObject());
	}
}
