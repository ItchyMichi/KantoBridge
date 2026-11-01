package dev.kantobridge.review;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelReviewStoreTest {
	private static ModelReview review() {
		JsonObject context = new JsonObject();
		context.addProperty("game", "test");
		context.addProperty("map", "SYNTHETIC_ROOM");
		context.addProperty("shapeKey", "unit:synthetic");
		return ModelReview.create(ModelReview.Category.MISSING_PARTS, "The second post is missing.\nKeep both posts.", context);
	}

	@Test
	void proposalsAndCaptureRoundTripWithoutMutableContext() {
		ModelReview review = review();
		review.context().addProperty("map", "CHANGED");
		assertEquals("SYNTHETIC_ROOM", review.context().get("map").getAsString());
		ModelReview proposed = review.withProposal(ModelReview.Method.HAND_MODEL, "Keep both posts and their connecting rail.");
		assertEquals(proposed, ModelReview.fromJson(proposed.toJson()));
		assertEquals(ModelReview.Status.PROPOSED, proposed.status());
		assertEquals(proposed.proposal(), proposed.withStatus(ModelReview.Status.RESOLVED).proposal());
		assertThrows(IllegalArgumentException.class, () -> review.withStatus(ModelReview.Status.PROPOSED));
		assertThrows(IllegalArgumentException.class, () -> review.withProposal(ModelReview.Method.OTHER, " "));
	}

	@Test
	void capturesPersistScreenshotAndNeverOverwriteAnExistingId(@TempDir Path dir) throws Exception {
		ModelReviewStore store = new ModelReviewStore(dir);
		assertTrue(store.list().reviews().isEmpty());
		ModelReview review = review();
		byte[] screenshot = {1, 2, 3, 4};
		Path issue = store.create(review, screenshot);
		assertArrayEquals(screenshot, Files.readAllBytes(issue.resolve("screenshot.png")));
		ModelReview loaded = store.load(review.id());
		assertTrue(loaded.screenshot());
		assertEquals(review.withScreenshot(true), loaded);
		assertThrows(IOException.class, () -> store.create(review, null));
		assertEquals(loaded, store.load(review.id()));
	}

	@Test
	void queueSurvivesAMalformedEntryWithoutReplacingIt(@TempDir Path dir) throws Exception {
		ModelReviewStore store = new ModelReviewStore(dir);
		ModelReview good = review();
		store.create(good, null);
		ModelReview bad = review();
		Path badDir = store.create(bad, null);
		Files.writeString(badDir.resolve("review.json"), "broken json");
		ModelReviewStore.Listing list = store.list();
		assertEquals(1, list.reviews().size());
		assertEquals(good, list.reviews().getFirst());
		assertEquals(bad.id(), list.failures().getFirst().id());
		assertEquals("broken json", Files.readString(badDir.resolve("review.json")));
		assertThrows(IOException.class, () -> store.update(bad.withStatus(ModelReview.Status.RESOLVED)));
		assertThrows(IllegalArgumentException.class, () -> store.issueDirectory("../outside"));
	}

	@Test
	void anUpdateKeepsEvidenceAndDoesNotCreateAMissingIssue(@TempDir Path dir) throws Exception {
		ModelReviewStore store = new ModelReviewStore(dir);
		ModelReview review = review();
		assertThrows(IOException.class, () -> store.update(review));
		store.create(review, null);
		ModelReview proposed = review.withProposal(ModelReview.Method.MASK, "Remove the background, preserve the two separate posts.");
		store.update(proposed);
		assertEquals(proposed, store.load(review.id()));
		assertThrows(IOException.class, () -> store.update(review), "a stale update must not erase a proposal");
		assertThrows(IOException.class, () -> store.update(review.withStatus(ModelReview.Status.RESOLVED), review.updatedAt()),
				"a freshly timestamped action from a stale inbox must not erase a CLI proposal");
		JsonObject changed = proposed.context();
		changed.addProperty("map", "ANOTHER_ROOM");
		ModelReview replacement = new ModelReview(proposed.id(), proposed.createdAt(), proposed.updatedAt(), proposed.status(),
				proposed.category(), proposed.comment(), changed, proposed.proposal(), proposed.screenshot());
		assertThrows(IOException.class, () -> store.update(replacement));
		assertFalse(store.load(review.id()).screenshot());
	}

	@Test
	void unsupportedRecordsAndExternalScreenshotPathsAreRejected() {
		JsonObject root = review().toJsonObject();
		root.addProperty("version", 2);
		assertThrows(IllegalArgumentException.class, () -> ModelReview.fromJson(root.toString()));
		root.addProperty("version", 1);
		root.addProperty("screenshot", "../outside.png");
		assertThrows(IllegalArgumentException.class, () -> ModelReview.fromJson(root.toString()));
		assertThrows(IllegalArgumentException.class, () -> ModelReview.fromJson("{\"version\":1," + review().toJson().substring(1)),
				"duplicate fields must not be silently rewritten");
	}

	@Test
	void anIncompleteCaptureIsNotPublishedOrListed(@TempDir Path dir) throws Exception {
		ModelReviewStore store = new ModelReviewStore(dir);
		JsonObject context = new JsonObject();
		context.addProperty("tooLarge", "x".repeat(2 * 1024 * 1024));
		ModelReview large = ModelReview.create(ModelReview.Category.OTHER, "synthetic", context);
		assertThrows(IOException.class, () -> store.create(large, new byte[] {1, 2}));
		assertFalse(Files.exists(store.issueDirectory(large.id())));
		Files.createDirectory(dir.resolve(".capture-interrupted"));
		assertTrue(store.list().reviews().isEmpty());
		assertTrue(store.list().failures().isEmpty());
	}

	@Test
	void competingWritersCannotPassTheSameVersionCheck(@TempDir Path dir) throws Exception {
		ModelReviewStore store = new ModelReviewStore(dir);
		ModelReview review = review();
		Path issue = store.create(review, null);
		try (FileChannel channel = FileChannel.open(issue.resolve(".review.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				var lock = channel.lock(0, 1, false)) {
			assertThrows(IOException.class, () -> store.update(review.withStatus(ModelReview.Status.RESOLVED), review.updatedAt()));
		}
		store.update(review.withStatus(ModelReview.Status.RESOLVED), review.updatedAt());
		assertEquals(ModelReview.Status.RESOLVED, store.load(review.id()).status());
	}
}
