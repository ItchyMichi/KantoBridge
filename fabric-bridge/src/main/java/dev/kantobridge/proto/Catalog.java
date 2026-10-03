package dev.kantobridge.proto;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * The message catalog derived from the schemas (protocol/catalog.json, bundled in the jar): which hops each type
 * may travel, whether replies must carry {@code re}, and where its ROM-derived fields are.
 */
public final class Catalog {
	/** Where the build puts the catalog inside the jar. */
	public static final String RESOURCE = "/kantobridge/protocol/catalog.json";

	private static volatile @Nullable Catalog bundled;

	private final int protocol;
	private final long maxLineBytes;
	private final long relayMaxBytes;
	private final long pingMs;
	private final long timeoutMs;
	private final long reconnectInitialMs;
	private final long reconnectMaxMs;
	private final Map<String, Entry> messages;

	/** The hops a message travels (protocol/README.md, {@code x-legs}). */
	public enum Leg {
		/** gen1-bridge to the Minecraft client, over the socket. */
		G2M,
		/** Minecraft client to gen1-bridge, over the socket. */
		M2G,
		/** Minecraft client to the Minecraft server, over the relay payload. */
		C2S,
		/** Minecraft server to the Minecraft client, over the relay payload. */
		S2C;

		static Leg parse(String name) throws ProtocolException {
			try {
				return valueOf(name.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				throw new ProtocolException("catalog.format", "unknown leg");
			}
		}
	}

	/** Whether the envelope's {@code re} must, may or must not be present ({@code x-re}). */
	public enum RePolicy {
		REQUIRED,
		OPTIONAL,
		NEVER
	}

	/**
	 * @param redact JSON Pointers into {@code data} (with {@code *} wildcards) of the ROM-derived values
	 */
	public record Entry(String type, Set<Leg> legs, RePolicy re, boolean rom, List<String> redact, String schema) {
	}

	private Catalog(JsonObject root) throws ProtocolException {
		this.protocol = intField(root, "protocol");
		JsonObject limits = objectField(root, "limits");
		this.maxLineBytes = intField(limits, "maxLineBytes");
		this.relayMaxBytes = intField(limits, "relayMaxBytes");
		JsonObject heartbeat = objectField(root, "heartbeat");
		this.pingMs = intField(heartbeat, "pingMs");
		this.timeoutMs = intField(heartbeat, "timeoutMs");
		JsonObject reconnect = objectField(root, "reconnect");
		this.reconnectInitialMs = intField(reconnect, "initialMs");
		this.reconnectMaxMs = intField(reconnect, "maxMs");

		Map<String, Entry> entries = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> message : objectField(root, "messages").entrySet()) {
			String type = message.getKey();
			if (!Envelope.TYPE_PATTERN.matcher(type).matches()) {
				throw new ProtocolException("catalog.format", "invalid type name in catalog");
			}
			if (!message.getValue().isJsonObject()) {
				throw new ProtocolException("catalog.format", "catalog entry is not an object");
			}
			JsonObject spec = message.getValue().getAsJsonObject();
			EnumSet<Leg> legs = EnumSet.noneOf(Leg.class);
			for (String leg : stringList(spec, "legs")) {
				legs.add(Leg.parse(leg));
			}
			if (legs.isEmpty()) {
				throw new ProtocolException("catalog.format", "catalog entry without legs");
			}
			RePolicy re;
			try {
				re = RePolicy.valueOf(stringField(spec, "re").toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				throw new ProtocolException("catalog.format", "unknown re policy");
			}
			JsonElement rom = spec.get("rom");
			if (rom == null || !rom.isJsonPrimitive() || !rom.getAsJsonPrimitive().isBoolean()) {
				throw new ProtocolException("catalog.format", "rom must be a boolean");
			}
			List<String> redact = stringList(spec, "redact");
			for (String path : redact) {
				if (!path.startsWith("/")) {
					throw new ProtocolException("catalog.format", "redact path is not a JSON Pointer");
				}
			}
			entries.put(type, new Entry(type, Collections.unmodifiableSet(legs), re, rom.getAsBoolean(), List.copyOf(redact),
					stringField(spec, "schema")));
		}
		this.messages = Collections.unmodifiableMap(entries);
	}

	/** Parses a catalog document. */
	public static Catalog parse(String json) throws ProtocolException {
		return new Catalog(ProtoJson.parseObject(json));
	}

	/** The catalog bundled in the mod jar. Missing or broken means a broken build, so this throws unchecked. */
	public static Catalog bundled() {
		Catalog catalog = bundled;
		if (catalog == null) {
			synchronized (Catalog.class) {
				catalog = bundled;
				if (catalog == null) {
					catalog = loadBundled();
					bundled = catalog;
				}
			}
		}
		return catalog;
	}

	private static Catalog loadBundled() {
		try (InputStream in = Catalog.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("protocol catalog missing from the jar: " + RESOURCE);
			}
			return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (ProtocolException e) {
			throw new IllegalStateException("protocol catalog is invalid: " + e.code(), e);
		}
	}

	public @Nullable Entry get(String type) {
		return this.messages.get(type);
	}

	public Map<String, Entry> messages() {
		return this.messages;
	}

	/**
	 * Checks that {@code envelope} may travel on {@code leg}: its type is known, the leg is one of its hops, and
	 * {@code re} follows the type's reply rule.
	 */
	public Entry check(Envelope envelope, Leg leg) throws ProtocolException {
		Entry entry = this.messages.get(envelope.type());
		if (entry == null) {
			throw new ProtocolException("catalog.type", "unknown message type");
		}
		if (!entry.legs().contains(leg)) {
			throw new ProtocolException("catalog.leg", "type not allowed on " + leg.name().toLowerCase(Locale.ROOT));
		}
		if (entry.re() == RePolicy.REQUIRED && envelope.re() == null) {
			throw new ProtocolException("catalog.re", "reply without re");
		}
		if (entry.re() == RePolicy.NEVER && envelope.re() != null) {
			throw new ProtocolException("catalog.re", "re on a type that is never a reply");
		}
		return entry;
	}

	public int protocol() {
		return this.protocol;
	}

	public long maxLineBytes() {
		return this.maxLineBytes;
	}

	public long relayMaxBytes() {
		return this.relayMaxBytes;
	}

	public long pingMs() {
		return this.pingMs;
	}

	public long timeoutMs() {
		return this.timeoutMs;
	}

	public long reconnectInitialMs() {
		return this.reconnectInitialMs;
	}

	public long reconnectMaxMs() {
		return this.reconnectMaxMs;
	}

	// ---- field helpers ----

	private static JsonObject objectField(JsonObject object, String name) throws ProtocolException {
		JsonElement value = object.get(name);
		if (value == null || !value.isJsonObject()) {
			throw new ProtocolException("catalog.format", name + " must be an object");
		}
		return value.getAsJsonObject();
	}

	private static int intField(JsonObject object, String name) throws ProtocolException {
		JsonElement value = object.get(name);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
				|| !(value.getAsNumber() instanceof Long)) {
			throw new ProtocolException("catalog.format", name + " must be an integer");
		}
		long n = value.getAsLong();
		if (n < 0 || n > Integer.MAX_VALUE) {
			throw new ProtocolException("catalog.format", name + " out of range");
		}
		return (int) n;
	}

	private static String stringField(JsonObject object, String name) throws ProtocolException {
		JsonElement value = object.get(name);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw new ProtocolException("catalog.format", name + " must be a string");
		}
		return value.getAsString();
	}

	private static List<String> stringList(JsonObject object, String name) throws ProtocolException {
		JsonElement value = object.get(name);
		if (value == null || !value.isJsonArray()) {
			throw new ProtocolException("catalog.format", name + " must be an array");
		}
		JsonArray array = value.getAsJsonArray();
		List<String> out = new ArrayList<>(array.size());
		for (JsonElement item : array) {
			if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
				throw new ProtocolException("catalog.format", name + " must hold strings");
			}
			out.add(item.getAsString());
		}
		return out;
	}
}
