package dev.kantobridge.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import dev.kantobridge.world.Games;
import dev.kantobridge.world.Hashes;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads gen1-bridge's world export into an export bundle (docs/PROTOCOL.md "Export (builder role)" and "Export
 * resources"; PLAN §4.3 {@code bridge/}, §4.5 "ExportBundle").
 *
 * <p>It opens its own short connection with role {@code builder}: {@code hello}, then {@code export.index}. The first
 * index request makes gen1-bridge build the whole export (10–15 s on the real game), so the client keeps pinging while
 * it waits. Then every resource is fetched with {@code export.get}, a few requests in flight at a time. Each
 * {@code export.data} is re-serialised with the canonical codec and must match the index's size and SHA-256 before it
 * is stored; the bundle ({@code <out>/<game>/index.json} plus {@code <kind>/<id>.json}) is swapped in only when
 * complete ({@link BundleWriter}).
 *
 * <p>gen1-bridge serves one connection at a time, so this connection replaces the player's main link: pause that client
 * first ({@link BridgeClient#pauseReconnects}).
 *
 * <p>Plain Java, no Minecraft classes. {@link #run} blocks the calling thread; {@link #cancel} may be called from any
 * thread. The resources are ROM-derived: logs and errors carry counts, kinds, ids and hashes, never the data.
 */
public final class ExportClient {
	public static final String ROLE = "builder";
	private static final Logger LOGGER = LoggerFactory.getLogger("kantobridge");
	private static final int READ_BUFFER = 64 * 1024;
	/** The bundle's own id rule (ExportBundle): safe file names everywhere, which the protocol's id pattern isn't. */
	private static final Pattern FILE_ID = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}$");
	private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
	private static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z.+_-]{1,32}$");
	/** The export kinds a bundle may hold: the bundle reader's list (the profile plan added {@code profile}). */
	private static final Set<String> KINDS = ExportBundle.KINDS;
	/** The protocol's limits: at most 4096 resources, each at most 1 MiB (export.index.schema.json). */
	private static final int MAX_RESOURCES = 4096;
	private static final long MAX_RESOURCE_BYTES = 1L << 20;

	/**
	 * @param indexTimeoutMs how long to wait for the index after asking (gen1-bridge builds the export first)
	 * @param stallTimeoutMs how long fetching may go without a single resource arriving
	 * @param pipeline       {@code export.get} requests in flight at once
	 */
	public record Options(long indexTimeoutMs, long stallTimeoutMs, int pipeline) {
		public static final Options DEFAULTS = new Options(120_000, 30_000, 8);

		public Options {
			if (indexTimeoutMs < 1 || stallTimeoutMs < 1 || pipeline < 1) {
				throw new IllegalArgumentException("invalid export options");
			}
		}
	}

	/** Progress, called on the thread running {@link #run}. */
	public interface Listener {
		Listener NONE = new Listener() {
		};

		/** The handshake succeeded; the index has been requested. */
		default void connected(HelloOk hello) {
		}

		/** The index arrived; {@code resources} resources totalling {@code bytes} will be fetched. */
		default void indexed(String game, int resources, long bytes) {
		}

		/** One more resource was verified and stored. */
		default void progress(int done, int total, long bytesDone, long bytesTotal) {
		}
	}

	/**
	 * A finished export.
	 *
	 * @param bundleDir the bundle folder, {@code <out>/<game>}
	 * @param indexHash SHA-256 of the index's canonical JSON (what {@code ExportBundle.exportHash()} reports)
	 * @param kinds     resources per kind
	 */
	public record Result(Path bundleDir, String game, String engine, String bridge, int resources, long bytes, String indexHash,
			SortedMap<String, Integer> kinds, long elapsedMs) {
	}

	/** Why an export failed: a short code, plus kinds, ids or peer codes in the message (never content). */
	public static final class ExportException extends Exception {
		private final String code;
		private final @Nullable String detail;

		public ExportException(String code, @Nullable String detail) {
			super(detail == null ? code : code + " (" + detail + ")");
			this.code = code;
			this.detail = detail;
		}

		public String code() {
			return this.code;
		}

		/** The kind/id, peer code or reason that goes with the code, if any. */
		public @Nullable String detail() {
			return this.detail;
		}
	}

	/** One entry of the index. */
	record Resource(String kind, String id, String hash, long size) {
		String path() {
			return this.kind + "/" + this.id;
		}
	}

	private final BridgeConfig config;
	private final Catalog catalog;
	private final HelloInfo hello;
	private final Transport transport;
	private final BridgeClock clock;
	private final Options options;
	private final BridgeStats stats = new BridgeStats();
	private final Object lock = new Object();
	private volatile boolean cancelled;
	private Transport.@Nullable Link link; // guarded by lock

	public ExportClient(BridgeConfig config, Catalog catalog, HelloInfo hello, Transport transport, BridgeClock clock, Options options) {
		this.config = Objects.requireNonNull(config);
		this.catalog = Objects.requireNonNull(catalog);
		this.hello = Objects.requireNonNull(hello);
		this.transport = Objects.requireNonNull(transport);
		this.clock = Objects.requireNonNull(clock);
		this.options = Objects.requireNonNull(options);
	}

	/** Stops a running export from any thread; {@link #run} then fails with {@code cancelled}. */
	public void cancel() {
		Transport.Link open;
		synchronized (this.lock) {
			this.cancelled = true;
			open = this.link;
		}
		if (open != null) {
			open.close();
		}
	}

	public BridgeStats stats() {
		return this.stats;
	}

	/**
	 * Exports into {@code outDir} (the bundle lands in {@code <outDir>/<game>}). Blocks until done.
	 *
	 * @throws ExportException      with a code: {@code unreachable}, {@code rejected}, {@code closed}, {@code index_timeout},
	 *                              {@code stalled}, {@code role}, {@code not_found}, {@code bad_message}, {@code export_failed},
	 *                              {@code union_room_on} (gen1-bridge refuses the index while Gen1Recomp's Union Room is on),
	 *                              {@code peer_error}, {@code bad_index}, {@code bad_reply}, {@code size}, {@code hash},
	 *                              {@code not_a_bundle}, {@code io} or {@code cancelled}
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public Result run(Path outDir, Listener listener) throws ExportException, InterruptedException {
		long startedMs = this.clock.millis();
		if (this.cancelled) {
			throw new ExportException("cancelled", null);
		}
		Transport.Link opened;
		try {
			opened = this.transport.connect(this.config.host(), this.config.port(), this.config.connectTimeoutMs(), this.config.tickMs());
		} catch (IOException e) {
			throw new ExportException("unreachable", null);
		}
		synchronized (this.lock) {
			if (this.cancelled) {
				opened.close();
				throw new ExportException("cancelled", null);
			}
			this.link = opened;
		}
		Outbox box = new Outbox(this.config.maxLineBytes(), this.stats);
		Thread writer = new Thread(() -> write(opened, box), "kantobridge-export-writer");
		writer.setDaemon(true);
		writer.start();
		Run run = new Run(outDir, box, listener);
		try {
			run.serve(opened);
			Path bundle;
			try {
				bundle = run.commit();
			} catch (BundleWriter.NotABundleException e) {
				throw new ExportException("not_a_bundle", null);
			} catch (IOException e) {
				throw new ExportException("io", "index");
			}
			long elapsed = this.clock.millis() - startedMs;
			Result result = new Result(bundle, run.game, run.engine, run.bridge, run.resources.size(), run.bytesTotal, run.indexHash,
					run.kinds(), elapsed);
			LOGGER.info("export: wrote the {} bundle: {} resources, {} bytes, index {}, {} ms", result.game(), result.resources(),
					result.bytes(), Hashes.shortHash(result.indexHash()), elapsed);
			return result;
		} catch (ExportException e) {
			LOGGER.warn("export: failed: {}", e.getMessage());
			throw e;
		} finally {
			run.close();
			box.shut();
			opened.close();
			synchronized (this.lock) {
				this.link = null;
			}
			writer.join(1000);
		}
	}

	private static void write(Transport.Link opened, Outbox box) {
		try {
			OutputStream out = new BufferedOutputStream(opened.output(), READ_BUFFER);
			byte[] line;
			while ((line = box.take()) != null) {
				out.write(line);
				if (box.isEmpty()) {
					out.flush();
				}
			}
			out.flush();
		} catch (IOException | InterruptedException e) {
			// The reader notices the closed link.
			opened.close();
		}
	}

	/** One export's state, driven by the calling thread. */
	private final class Run implements BridgeSession.Output {
		private final Path outDir;
		private final Outbox box;
		private final Listener listener;
		private final BridgeSession session;
		private @Nullable ExportException failure;
		private @Nullable String closedReason;
		private boolean complete;
		private long indexSeq = -1;
		private long indexAskedMs;
		private long lastProgressMs;
		private @Nullable JsonObject index;
		private String game = "";
		private String engine = "";
		private String bridge = "";
		private String indexHash = "";
		private List<Resource> resources = List.of();
		private long bytesTotal;
		private int next;
		private int done;
		private long bytesDone;
		private final Map<Long, Resource> inFlight = new LinkedHashMap<>();
		private @Nullable BundleWriter bundle;

		Run(Path outDir, Outbox box, Listener listener) {
			this.outDir = outDir;
			this.box = box;
			this.listener = listener;
			JsonObject helloData = ExportClient.this.hello.toData(ExportClient.this.catalog.protocol(), ROLE);
			this.session = new BridgeSession(ExportClient.this.catalog, ExportClient.this.config, helloData, ExportClient.this.stats, this);
		}

		/** Runs the connection until the last resource is stored, or throws why not. */
		void serve(Transport.Link opened) throws ExportException, InterruptedException {
			LineFramer framer = new LineFramer(ExportClient.this.config.maxLineBytes());
			byte[] buffer = new byte[READ_BUFFER];
			try {
				InputStream in = opened.input();
				this.session.open(ExportClient.this.clock.millis());
				while (!this.complete) {
					if (Thread.interrupted()) {
						throw new InterruptedException();
					}
					int count;
					try {
						count = in.read(buffer);
					} catch (SocketTimeoutException idle) {
						count = 0;
					}
					this.check();
					long now = ExportClient.this.clock.millis();
					if (count < 0) {
						this.session.close("eof");
					} else if (count > 0) {
						for (LineFramer.Line line : framer.feed(buffer, 0, count)) {
							this.session.receive(line.text(), line.bytes(), now);
							if (this.failure != null || this.complete || this.session.isClosed()) {
								break;
							}
						}
					}
					if (this.box.overflowed()) {
						this.session.close("backlog");
					}
					if (!this.complete) {
						this.session.tick(now);
						this.deadlines(now);
					}
					this.check();
				}
			} catch (ProtocolException e) {
				throw new ExportException("closed", e.code());
			} catch (IOException e) {
				if (ExportClient.this.cancelled) {
					throw new ExportException("cancelled", null);
				}
				throw new ExportException("closed", "io");
			}
		}

		/** Throws the recorded failure, or why the session ended before the export did. */
		private void check() throws ExportException {
			if (ExportClient.this.cancelled) {
				throw new ExportException("cancelled", null);
			}
			if (this.failure != null) {
				throw this.failure;
			}
			if (this.session.isClosed() && !this.complete) {
				throw new ExportException("closed", this.closedReason);
			}
		}

		private void deadlines(long now) {
			if (this.failure != null || this.indexSeq < 0) {
				return;
			}
			if (this.index == null) {
				if (now - this.indexAskedMs > ExportClient.this.options.indexTimeoutMs()) {
					this.fail("index_timeout", null);
				}
			} else if (now - this.lastProgressMs > ExportClient.this.options.stallTimeoutMs()) {
				Resource waiting = this.inFlight.isEmpty() ? null : this.inFlight.values().iterator().next();
				this.fail("stalled", waiting == null ? null : waiting.path());
			}
		}

		private void fail(String code, @Nullable String detail) {
			if (this.failure == null) {
				this.failure = new ExportException(code, detail);
			}
		}

		// ---- BridgeSession.Output ----

		@Override
		public long send(String type, @Nullable Long re, JsonObject data) {
			return this.box.enqueue(type, re, data);
		}

		@Override
		public void connected(HelloOk ok) {
			LOGGER.info("export: connected as builder (game {}, engine {}, bridge {})", ok.game(), ok.engine(), ok.bridge());
			this.listener.connected(ok);
			this.indexSeq = this.box.enqueue(MsgTypes.EXPORT_INDEX, null, new JsonObject());
			this.indexAskedMs = ExportClient.this.clock.millis();
		}

		@Override
		public void rejected(String reason, int protocol) {
			this.fail("rejected", reason);
		}

		@Override
		public void closed(String reason) {
			this.closedReason = reason;
		}

		@Override
		public void deliver(Envelope message) {
			if (this.failure != null) {
				return;
			}
			switch (message.type()) {
				case MsgTypes.EXPORT_INDEX -> this.onIndex(message);
				case MsgTypes.EXPORT_DATA -> this.onData(message);
				case MsgTypes.ERROR -> this.onError(message);
				// hello.ok, the snapshot that follows it and log lines mean nothing to an export.
				default -> {
				}
			}
		}

		// ---- the export ----

		private void onIndex(Envelope message) {
			if (this.index != null || message.re() == null || message.re() != this.indexSeq) {
				this.fail("bad_reply", "unexpected export.index");
				return;
			}
			JsonObject data = message.data();
			String parsedGame = JsonFields.str(data, "game");
			String parsedEngine = JsonFields.str(data, "engine");
			String parsedBridge = JsonFields.str(data, "bridge");
			JsonElement list = data.get("resources");
			if (parsedGame == null || !Games.known(parsedGame) || parsedEngine == null || !VERSION.matcher(parsedEngine).matches()
					|| parsedBridge == null || !VERSION.matcher(parsedBridge).matches() || list == null || !list.isJsonArray()) {
				this.fail("bad_index", "header");
				return;
			}
			JsonArray entries = list.getAsJsonArray();
			if (entries.isEmpty() || entries.size() > MAX_RESOURCES) {
				this.fail("bad_index", entries.isEmpty() ? "empty" : "too many resources");
				return;
			}
			List<Resource> parsed = new ArrayList<>(entries.size());
			Set<String> seen = new HashSet<>();
			long total = 0;
			for (int i = 0; i < entries.size(); i++) {
				Resource resource = resource(entries.get(i));
				if (resource == null) {
					this.fail("bad_index", "resources/" + i);
					return;
				}
				// Case-insensitive: two ids that differ only in case would share a file on Windows.
				if (!seen.add(resource.path().toLowerCase(Locale.ROOT))) {
					this.fail("bad_index", "duplicate " + resource.path());
					return;
				}
				parsed.add(resource);
				total += resource.size();
			}
			try {
				this.bundle = new BundleWriter(this.outDir, parsedGame);
			} catch (BundleWriter.NotABundleException e) {
				this.fail("not_a_bundle", null);
				return;
			} catch (IOException e) {
				this.fail("io", "staging");
				return;
			}
			this.index = data;
			this.game = parsedGame;
			this.engine = parsedEngine;
			this.bridge = parsedBridge;
			this.indexHash = Hashes.canonical(data);
			this.resources = Collections.unmodifiableList(parsed);
			this.bytesTotal = total;
			this.lastProgressMs = ExportClient.this.clock.millis();
			LOGGER.info("export: index received: {} resources, {} bytes, hash {}, after {} ms", parsed.size(), total,
					Hashes.shortHash(this.indexHash), this.lastProgressMs - this.indexAskedMs);
			this.listener.indexed(parsedGame, parsed.size(), total);
			this.fetchMore();
		}

		private static @Nullable Resource resource(JsonElement element) {
			if (!element.isJsonObject()) {
				return null;
			}
			JsonObject entry = element.getAsJsonObject();
			String kind = JsonFields.str(entry, "kind");
			String id = JsonFields.str(entry, "id");
			String hash = JsonFields.str(entry, "hash");
			long size = JsonFields.integer(entry, "size", -1);
			if (kind == null || !KINDS.contains(kind) || id == null || !FILE_ID.matcher(id).matches() || id.contains("..")
					|| hash == null || !HASH.matcher(hash).matches() || size < 0 || size > MAX_RESOURCE_BYTES) {
				return null;
			}
			return new Resource(kind, id, hash, size);
		}

		/** Keeps up to the pipeline's worth of {@code export.get} requests in flight. */
		private void fetchMore() {
			while (this.inFlight.size() < ExportClient.this.options.pipeline() && this.next < this.resources.size()) {
				Resource resource = this.resources.get(this.next++);
				JsonObject get = new JsonObject();
				get.addProperty("kind", resource.kind());
				get.addProperty("id", resource.id());
				long seq = this.box.enqueue(MsgTypes.EXPORT_GET, null, get);
				if (seq < 0) {
					this.fail("closed", "send");
					return;
				}
				this.inFlight.put(seq, resource);
			}
		}

		private void onData(Envelope message) {
			Resource resource = message.re() == null ? null : this.inFlight.remove(message.re());
			if (resource == null) {
				this.fail("bad_reply", "export.data for no request");
				return;
			}
			JsonObject data = message.data();
			if (!resource.kind().equals(JsonFields.str(data, "kind")) || !resource.id().equals(JsonFields.str(data, "id"))) {
				this.fail("bad_reply", resource.path());
				return;
			}
			if (!resource.hash().equals(JsonFields.str(data, "hash"))) {
				this.fail("hash", resource.path() + " reply");
				return;
			}
			JsonElement payload = data.get("data");
			if (payload == null) {
				this.fail("bad_reply", resource.path());
				return;
			}
			// The index names the canonical JSON's size and hash; re-serialising proves the data is the same.
			byte[] canonical = ProtoJson.canonicalUtf8(payload);
			if (canonical.length != resource.size()) {
				this.fail("size", resource.path());
				return;
			}
			if (!Hashes.sha256(canonical).equals(resource.hash())) {
				this.fail("hash", resource.path());
				return;
			}
			try {
				this.bundle.write(resource.kind(), resource.id(), canonical);
			} catch (IOException e) {
				this.fail("io", resource.path());
				return;
			}
			this.done++;
			this.bytesDone += canonical.length;
			this.lastProgressMs = ExportClient.this.clock.millis();
			this.listener.progress(this.done, this.resources.size(), this.bytesDone, this.bytesTotal);
			if (this.done == this.resources.size()) {
				this.complete = true;
				LOGGER.info("export: fetched all {} resources", this.done);
			} else {
				this.fetchMore();
			}
		}

		private void onError(Envelope message) {
			String code = JsonFields.str(message.data(), "code");
			Long re = message.re();
			Resource resource = re == null ? null : this.inFlight.get(re);
			boolean ours = re == null || re == this.indexSeq || resource != null;
			if (!ours) {
				// About something other than the export (BridgeSession already logged it).
				return;
			}
			String detail = resource != null ? resource.path() : re != null && re == this.indexSeq ? "export.index" : null;
			switch (code == null ? "" : code) {
				case "role", "not_found", "bad_message", "export_failed", "union_room_on" -> this.fail(code, detail);
				default -> this.fail("peer_error", code == null ? detail : code + (detail == null ? "" : " " + detail));
			}
		}

		Path commit() throws IOException {
			JsonObject data = Objects.requireNonNull(this.index);
			return Objects.requireNonNull(this.bundle).commit(data);
		}

		SortedMap<String, Integer> kinds() {
			SortedMap<String, Integer> out = new TreeMap<>();
			for (Resource resource : this.resources) {
				out.merge(resource.kind(), 1, Integer::sum);
			}
			return Collections.unmodifiableSortedMap(out);
		}

		void close() {
			this.session.close("done");
			if (this.bundle != null) {
				this.bundle.close();
			}
		}
	}

	/** The counts of {@link Result#kinds()} as {@code kind:n,kind:n}, sorted by kind. */
	public static String kindsText(Map<String, Integer> kinds) {
		StringBuilder out = new StringBuilder();
		for (Map.Entry<String, Integer> entry : new TreeMap<>(kinds).entrySet()) {
			if (!out.isEmpty()) {
				out.append(',');
			}
			out.append(entry.getKey()).append(':').append(entry.getValue());
		}
		return out.toString();
	}
}
