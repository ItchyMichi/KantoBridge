package dev.kantobridge.client.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * Plays gen1-bridge's export service over one {@link PipeTransport.Pipe}, serving an export bundle from disk (the
 * GameTests' synthetic one): {@code hello.ok} and a snapshot, {@code pong}s, {@code export.index} (optionally late, or
 * never) and {@code export.data}. Tests change single replies through {@link #onGet}.
 */
final class FakeExportPeer {
	final PipeTransport.Pipe pipe;
	final JsonObject index;
	final Map<String, JsonElement> resources = new ConcurrentHashMap<>();
	final List<Envelope> received = new CopyOnWriteArrayList<>();
	final AtomicInteger pings = new AtomicInteger();
	final AtomicInteger pingsBeforeIndex = new AtomicInteger();
	final AtomicInteger inFlight = new AtomicInteger();
	final AtomicInteger maxInFlight = new AtomicInteger();
	volatile long indexDelayMs;
	volatile boolean answerIndex = true;
	/** Replaces the reply to one {@code export.get} ("kind/id" → line given the request); null keeps the normal reply. */
	volatile @Nullable Function<Envelope, @Nullable String> onGet;
	/** Replaces the reply to hello; null answers hello.ok. */
	volatile @Nullable Function<Envelope, String> onHello;

	private final AtomicLong seq = new AtomicLong();
	private final ScheduledExecutorService later = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "fake-export-peer-later");
		thread.setDaemon(true);
		return thread;
	});
	private volatile boolean indexSent;

	FakeExportPeer(PipeTransport.Pipe pipe, Path bundle) throws IOException, ProtocolException {
		this.pipe = pipe;
		this.index = ProtoJson.parseObject(Files.readString(bundle.resolve("index.json"), StandardCharsets.UTF_8));
		for (JsonElement entry : this.index.getAsJsonArray("resources")) {
			JsonObject resource = entry.getAsJsonObject();
			String path = resource.get("kind").getAsString() + "/" + resource.get("id").getAsString();
			this.resources.put(path, ProtoJson.parse(Files.readString(bundle.resolve(path + ".json"), StandardCharsets.UTF_8)));
		}
	}

	/** Serves on a daemon thread until the client hangs up. */
	FakeExportPeer start() {
		Thread thread = new Thread(this::serve, "fake-export-peer");
		thread.setDaemon(true);
		thread.start();
		return this;
	}

	String hashOf(String path) {
		for (JsonElement entry : this.index.getAsJsonArray("resources")) {
			JsonObject resource = entry.getAsJsonObject();
			if ((resource.get("kind").getAsString() + "/" + resource.get("id").getAsString()).equals(path)) {
				return resource.get("hash").getAsString();
			}
		}
		throw new IllegalArgumentException("no resource " + path);
	}

	String reply(String type, long re, JsonObject data) {
		return new Envelope(type, this.seq.incrementAndGet(), re, data).toJson();
	}

	String error(long re, String code) {
		JsonObject data = new JsonObject();
		data.addProperty("code", code);
		data.addProperty("msg", code);
		return this.reply("error", re, data);
	}

	String data(long re, String kind, String id, String hash, JsonElement payload) {
		JsonObject data = new JsonObject();
		data.addProperty("kind", kind);
		data.addProperty("id", id);
		data.addProperty("hash", hash);
		data.add("data", payload);
		return this.reply("export.data", re, data);
	}

	private void serve() {
		try {
			while (!this.pipe.closed) {
				String line;
				try {
					line = this.pipe.serverReadLine();
				} catch (AssertionError quiet) {
					continue; // nothing for 5 s; check whether the client hung up
				}
				Envelope message = Envelope.parse(line);
				this.received.add(message);
				this.handle(message);
			}
		} catch (InterruptedException | ProtocolException e) {
			// The test is over.
		} finally {
			this.later.shutdownNow();
		}
	}

	private void handle(Envelope message) {
		switch (message.type()) {
			case "hello" -> {
				Function<Envelope, String> custom = this.onHello;
				if (custom != null) {
					this.pipe.serverSend(custom.apply(message));
					return;
				}
				JsonObject ok = new JsonObject();
				ok.addProperty("protocol", 1);
				ok.addProperty("bridge", "0.1.0");
				ok.addProperty("engine", "0.3.49");
				ok.addProperty("game", this.index.get("game").getAsString());
				ok.addProperty("session", "s-test");
				this.pipe.serverSend(this.reply("hello.ok", message.seq(), ok));
				JsonObject snapshot = new JsonObject();
				snapshot.addProperty("ingame", false);
				this.pipe.serverSend(new Envelope("snapshot", this.seq.incrementAndGet(), null, snapshot).toJson());
			}
			case "ping" -> {
				this.pings.incrementAndGet();
				if (!this.indexSent) {
					this.pingsBeforeIndex.incrementAndGet();
				}
				JsonObject pong = new JsonObject();
				pong.add("t", message.data().get("t"));
				this.pipe.serverSend(this.reply("pong", message.seq(), pong));
			}
			case "export.index" -> {
				if (!this.answerIndex) {
					return;
				}
				String reply = this.reply("export.index", message.seq(), this.index);
				Runnable send = () -> {
					this.indexSent = true;
					this.pipe.serverSend(reply);
				};
				if (this.indexDelayMs > 0) {
					this.later.schedule(send, this.indexDelayMs, TimeUnit.MILLISECONDS);
				} else {
					send.run();
				}
			}
			case "export.get" -> {
				int now = this.inFlight.incrementAndGet();
				this.maxInFlight.accumulateAndGet(now, Math::max);
				// Answer a little later, so several requests are in flight at once.
				this.later.schedule(() -> {
					this.inFlight.decrementAndGet();
					this.pipe.serverSend(this.answerGet(message));
				}, 2, TimeUnit.MILLISECONDS);
			}
			default -> {
			}
		}
	}

	private String answerGet(Envelope get) {
		Function<Envelope, @Nullable String> custom = this.onGet;
		if (custom != null) {
			String line = custom.apply(get);
			if (line != null) {
				return line;
			}
		}
		String kind = get.data().get("kind").getAsString();
		String id = get.data().get("id").getAsString();
		JsonElement payload = this.resources.get(kind + "/" + id);
		if (payload == null) {
			return this.error(get.seq(), "not_found");
		}
		return this.data(get.seq(), kind, id, this.hashOf(kind + "/" + id), payload);
	}

	/** Every file of a bundle folder, relative path → bytes. */
	static Map<String, byte[]> files(Path root) {
		Map<String, byte[]> out = new java.util.TreeMap<>();
		try (var walk = Files.walk(root)) {
			for (Path file : walk.filter(Files::isRegularFile).toList()) {
				out.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return out;
	}
}
