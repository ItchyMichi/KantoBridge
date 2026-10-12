package dev.kantobridge.client.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.Hashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ExportClient} over an in-memory transport, against a scripted gen1-bridge serving the GameTests' synthetic
 * export: the builder handshake, a slow index with the heartbeat going, pipelined fetches, the checks on every
 * resource, error codes, and the atomic bundle write.
 */
class ExportClientTest {
	private static final Catalog CATALOG = Catalog.bundled();
	private static final HelloInfo HELLO = new HelloInfo("0.1.0", "1.21.1", null, null);
	private static final ExportClient.Options QUICK = new ExportClient.Options(5_000, 5_000, 4);

	@TempDir
	Path out;

	private final PipeTransport transport = new PipeTransport();

	private static BridgeConfig config() {
		return BridgeConfig.fromCatalog(CATALOG, 27151).withHeartbeat(50, 2_000).withTick(10);
	}

	private ExportClient client(ExportClient.Options options) {
		return new ExportClient(config(), CATALOG, HELLO, this.transport, BridgeClock.system(), options);
	}

	/** Runs an export on another thread while {@code script} sets up the peer of the connection it opens. */
	private CompletableFuture<ExportClient.Result> export(ExportClient client, Consumer<FakeExportPeer> script) throws Exception {
		CompletableFuture<ExportClient.Result> result = new CompletableFuture<>();
		Thread runner = new Thread(() -> {
			try {
				result.complete(client.run(this.out, ExportClient.Listener.NONE));
			} catch (Throwable e) {
				result.completeExceptionally(e);
			}
		}, "export-test");
		runner.setDaemon(true);
		runner.start();
		FakeExportPeer peer = new FakeExportPeer(this.transport.nextPipe(), TestData.syntheticBundle());
		script.accept(peer);
		peer.start();
		return result;
	}

	private static ExportClient.ExportException failure(CompletableFuture<ExportClient.Result> result) {
		Throwable error = assertThrows(Exception.class, () -> result.get(20, TimeUnit.SECONDS)).getCause();
		assertTrue(error instanceof ExportClient.ExportException, "expected an export failure, got " + error);
		return (ExportClient.ExportException) error;
	}

	private void assertSameBundle(Path expected, Path actual) {
		Map<String, byte[]> want = FakeExportPeer.files(expected);
		Map<String, byte[]> got = FakeExportPeer.files(actual);
		assertEquals(want.keySet(), got.keySet());
		for (String path : want.keySet()) {
			assertArrayEquals(want.get(path), got.get(path), path);
		}
	}

	private void assertNoLeftovers() throws Exception {
		try (var entries = Files.list(this.out)) {
			for (Path entry : entries.toList()) {
				String name = entry.getFileName().toString();
				assertFalse(name.endsWith(BundleWriter.STAGING_SUFFIX) || name.endsWith(BundleWriter.OLD_SUFFIX), "left behind: " + name);
			}
		}
	}

	@Test
	void exportsAsBuilderAndWritesTheBundleByteForByte() throws Exception {
		List<int[]> progress = new ArrayList<>();
		List<String> indexed = new ArrayList<>();
		ExportClient client = this.client(QUICK);
		CompletableFuture<ExportClient.Result> result = new CompletableFuture<>();
		Thread runner = new Thread(() -> {
			try {
				result.complete(client.run(this.out, new ExportClient.Listener() {
					@Override
					public void indexed(String game, int resources, long bytes) {
						indexed.add(game + ":" + resources + ":" + bytes);
					}

					@Override
					public void progress(int done, int total, long bytesDone, long bytesTotal) {
						progress.add(new int[] {done, total, (int) bytesDone, (int) bytesTotal});
					}
				}));
			} catch (Throwable e) {
				result.completeExceptionally(e);
			}
		});
		runner.setDaemon(true);
		runner.start();
		FakeExportPeer peer = new FakeExportPeer(this.transport.nextPipe(), TestData.syntheticBundle());
		peer.indexDelayMs = 400; // gen1-bridge builds the export first
		peer.start();

		ExportClient.Result done = result.get(20, TimeUnit.SECONDS);
		Envelope hello = peer.received.get(0);
		assertEquals("hello", hello.type());
		assertEquals("builder", hello.data().get("role").getAsString());
		assertFalse(hello.data().has("player"), "a builder connection names no player");
		assertTrue(peer.pingsBeforeIndex.get() >= 3, "the heartbeat keeps going while the index is built: " + peer.pingsBeforeIndex.get());
		assertTrue(peer.maxInFlight.get() > 1 && peer.maxInFlight.get() <= QUICK.pipeline(), "pipelined: " + peer.maxInFlight.get());

		int resources = peer.index.getAsJsonArray("resources").size();
		assertEquals(resources, done.resources());
		assertEquals("red", done.game());
		assertEquals(Hashes.canonical(peer.index), done.indexHash());
		assertEquals(this.out.resolve("red").toAbsolutePath().normalize(), done.bundleDir());
		assertEquals(1, indexed.size());
		assertEquals(resources, progress.size());
		int[] last = progress.get(progress.size() - 1);
		assertEquals(resources, last[0]);
		assertEquals(last[3], last[2], "every byte arrived");
		assertEquals(done.bytes(), last[2]);
		assertEquals(6, done.kinds().get("map"), "with the cave's two floors (CAVES_3D_PLAN §3.3)");

		// The written bundle is the source bundle (written by fake_gen1 --write-bundle), byte for byte, and opens.
		this.assertSameBundle(TestData.syntheticBundle(), done.bundleDir());
		assertEquals(done.indexHash(), ExportBundle.open(done.bundleDir()).exportHash());
		this.assertNoLeftovers();
	}

	@Test
	void aHashMismatchFailsAndLeavesThePreviousBundle() throws Exception {
		// A previous export is in place; a failed export must not touch it.
		Path previous = this.out.resolve("red");
		Files.createDirectories(previous.resolve("map"));
		Files.writeString(previous.resolve("index.json"), "{\"old\":true}\n");
		Files.writeString(previous.resolve("map/OLD.json"), "{}");
		Map<String, byte[]> before = FakeExportPeer.files(previous);

		CompletableFuture<ExportClient.Result> result = this.export(this.client(QUICK), peer -> peer.onGet = get -> {
			String kind = get.data().get("kind").getAsString();
			String id = get.data().get("id").getAsString();
			if (!kind.equals("tileset")) {
				return null;
			}
			// Same length, different content: flip one digit of the canonical text.
			String text = ProtoJson.write(peer.resources.get(kind + "/" + id));
			int at = 0;
			while (!Character.isDigit(text.charAt(at))) {
				at++;
			}
			char flipped = text.charAt(at) == '9' ? '8' : (char) (text.charAt(at) + 1);
			String tampered = text.substring(0, at) + flipped + text.substring(at + 1);
			try {
				return peer.data(get.seq(), kind, id, peer.hashOf(kind + "/" + id), ProtoJson.parse(tampered));
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		ExportClient.ExportException error = failure(result);
		assertEquals("hash", error.code());
		// The first tileset asked for was tampered with (CAVERN or TEST, whichever comes first).
		assertTrue(error.getMessage().contains("tileset/"), error.getMessage());
		Map<String, byte[]> after = FakeExportPeer.files(previous);
		assertEquals(before.keySet(), after.keySet());
		this.assertNoLeftovers();
	}

	@Test
	void aWrongSizeOrReplyHashFails() throws Exception {
		CompletableFuture<ExportClient.Result> result = this.export(this.client(QUICK), peer -> peer.onGet = get -> {
			String path = get.data().get("kind").getAsString() + "/" + get.data().get("id").getAsString();
			if (!path.equals("layout/kanto")) {
				return null;
			}
			JsonObject bigger = peer.resources.get(path).getAsJsonObject().deepCopy();
			bigger.addProperty("extra", 1);
			return peer.data(get.seq(), "layout", "kanto", peer.hashOf(path), bigger);
		});
		assertEquals("size", failure(result).code());

		CompletableFuture<ExportClient.Result> again = this.export(this.client(QUICK), peer -> peer.onGet = get -> {
			String path = get.data().get("kind").getAsString() + "/" + get.data().get("id").getAsString();
			return path.equals("layout/kanto") ? peer.data(get.seq(), "layout", "kanto", "0".repeat(64), peer.resources.get(path)) : null;
		});
		assertEquals("hash", failure(again).code());
		assertFalse(Files.exists(this.out.resolve("red")));
		this.assertNoLeftovers();
	}

	@Test
	void notFoundIsReportedWithTheResource() throws Exception {
		CompletableFuture<ExportClient.Result> result = this.export(this.client(QUICK),
				peer -> peer.onGet = get -> get.data().get("kind").getAsString().equals("sprite") ? peer.error(get.seq(), "not_found") : null);
		ExportClient.ExportException error = failure(result);
		assertEquals("not_found", error.code());
		assertTrue(error.getMessage().contains("sprite/"), error.getMessage());
		this.assertNoLeftovers();
	}

	@Test
	void anIndexThatNeverComesTimesOutWhileTheHeartbeatIsAnswered() throws Exception {
		FakeExportPeer[] seen = new FakeExportPeer[1];
		CompletableFuture<ExportClient.Result> result = this.export(this.client(new ExportClient.Options(400, 5_000, 4)), peer -> {
			seen[0] = peer;
			peer.answerIndex = false;
		});
		assertEquals("index_timeout", failure(result).code());
		assertTrue(seen[0].pings.get() >= 3, "pings while waiting: " + seen[0].pings.get());
	}

	@Test
	void aPeerErrorOnTheIndexFailsTheExport() throws Exception {
		FakeExportPeer[] seen = new FakeExportPeer[1];
		CompletableFuture<ExportClient.Result> result = this.export(this.client(QUICK), peer -> {
			seen[0] = peer;
			peer.answerIndex = false;
		});
		// Answer the index request with export_failed, as gen1-bridge does when the game data can't be read.
		FakeExportPeer peer = seen[0];
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		Envelope ask = null;
		while (ask == null && System.nanoTime() < deadline) {
			ask = peer.received.stream().filter(m -> m.type().equals("export.index")).findFirst().orElse(null);
			Thread.sleep(5);
		}
		assertNotNull(ask);
		peer.pipe.serverSend(peer.error(ask.seq(), "export_failed"));
		assertEquals("export_failed", failure(result).code());
	}

	@Test
	void theIndexIsCheckedBeforeAnythingIsFetched() throws Exception {
		CompletableFuture<ExportClient.Result> duplicate = this.export(this.client(QUICK), peer -> {
			JsonArray resources = peer.index.getAsJsonArray("resources");
			JsonObject copy = resources.get(resources.size() - 1).getAsJsonObject().deepCopy();
			copy.addProperty("id", copy.get("id").getAsString().toLowerCase(java.util.Locale.ROOT));
			resources.add(copy);
		});
		assertEquals("bad_index", failure(duplicate).code());

		CompletableFuture<ExportClient.Result> unsafe = this.export(this.client(QUICK),
				peer -> peer.index.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("id", "a:b"));
		assertEquals("bad_index", failure(unsafe).code());
		assertFalse(Files.exists(this.out.resolve("red")));
		this.assertNoLeftovers();
	}

	@Test
	void aSuccessfulExportReplacesTheWholePreviousBundle() throws Exception {
		Path previous = this.out.resolve("red");
		Files.createDirectories(previous.resolve("map"));
		Files.writeString(previous.resolve("index.json"), "{}\n");
		Files.writeString(previous.resolve("map/STALE.json"), "{}");
		ExportClient.Result done = this.export(this.client(QUICK), peer -> {
		}).get(20, TimeUnit.SECONDS);
		this.assertSameBundle(TestData.syntheticBundle(), done.bundleDir());
		this.assertNoLeftovers();
	}

	@Test
	void neverReplacesAFolderThatIsNotABundle() throws Exception {
		Path foreign = this.out.resolve("red");
		Files.createDirectories(foreign);
		Files.writeString(foreign.resolve("notes.txt"), "mine");
		ExportClient.ExportException error = failure(this.export(this.client(QUICK), peer -> {
		}));
		assertEquals("not_a_bundle", error.code());
		assertEquals("mine", Files.readString(foreign.resolve("notes.txt")));
		this.assertNoLeftovers();
	}

	@Test
	void rejectionUnreachableAndCancel() throws Exception {
		CompletableFuture<ExportClient.Result> rejected = this.export(this.client(QUICK), peer -> peer.onHello = hello -> {
			JsonObject data = new JsonObject();
			data.addProperty("reason", "protocol");
			data.addProperty("protocol", 2);
			return peer.reply("hello.reject", hello.seq(), data);
		});
		assertEquals("rejected", failure(rejected).code());

		this.transport.refuse.set(1);
		ExportClient.ExportException unreachable = assertThrows(ExportClient.ExportException.class,
				() -> this.client(QUICK).run(this.out, ExportClient.Listener.NONE));
		assertEquals("unreachable", unreachable.code());

		ExportClient waiting = this.client(QUICK);
		CompletableFuture<ExportClient.Result> cancelled = this.export(waiting, peer -> peer.answerIndex = false);
		Thread.sleep(200);
		waiting.cancel();
		assertEquals("cancelled", failure(cancelled).code());
		this.assertNoLeftovers();
	}

	@Test
	void kindsTextIsSortedCounts() {
		assertEquals("image:2,map:3", ExportClient.kindsText(Map.of("map", 3, "image", 2)));
	}
}
