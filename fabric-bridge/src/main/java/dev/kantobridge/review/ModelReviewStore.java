package dev.kantobridge.review;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/** One directory per review: review.json and optionally screenshot.png, beneath the client's own game folder. */
public final class ModelReviewStore {
	public static final String FILE = "review.json";
	public static final String SCREENSHOT = "screenshot.png";
	private static final String LOCK = ".review.lock";
	private static final long MAX_JSON_BYTES = 2 * 1024 * 1024;
	private final Path root;

	public record Failure(String id, String message) {
	}

	public record Listing(List<ModelReview> reviews, List<Failure> failures) {
		public Listing {
			reviews = List.copyOf(reviews);
			failures = List.copyOf(failures);
		}
	}

	public ModelReviewStore(Path root) {
		this.root = root.toAbsolutePath().normalize();
	}

	public Path root() {
		return this.root;
	}

	/** UUID-only directory names keep user comments and model keys out of filesystem paths. */
	public Path issueDirectory(String id) {
		return this.root.resolve(ModelReview.checkedId(id));
	}

	/** Creates a new issue; an existing id is never overwritten. Screenshot presence follows the supplied bytes. */
	public synchronized Path create(ModelReview review, byte @Nullable [] png) throws IOException {
		byte[] record = reviewBytes(review.withScreenshot(png != null));
		Files.createDirectories(this.root);
		Path dir = issueDirectory(review.id());
		if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
			throw new FileAlreadyExistsException(dir.toString());
		}
		Path staging = Files.createTempDirectory(this.root, ".capture-");
		try {
			if (png != null) {
				atomicWrite(staging.resolve(SCREENSHOT), png);
			}
			atomicWrite(staging.resolve(FILE), record);
			// A directory rename publishes the complete capture; no REPLACE_EXISTING, so a duplicate id still fails.
			Files.move(staging, dir);
			return dir;
		} catch (IOException e) {
			try {
				Files.deleteIfExists(staging.resolve(SCREENSHOT));
				Files.deleteIfExists(staging.resolve(FILE));
				Files.deleteIfExists(staging);
			} catch (IOException cleanup) {
				e.addSuppressed(cleanup);
			}
			throw e;
		}
	}

	public ModelReview load(String id) throws IOException {
		Path dir = issueDirectory(id);
		if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("review directory is missing or is a link");
		}
		Path file = dir.resolve(FILE);
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_JSON_BYTES) {
			throw new IOException("review file is missing, linked, or too large");
		}
		try {
			ModelReview review = ModelReview.fromJson(Files.readString(file, StandardCharsets.UTF_8));
			if (!review.id().equals(id)) {
				throw new IOException("review id does not match its directory");
			}
			return review;
		} catch (IllegalArgumentException e) {
			throw new IOException("invalid review record", e);
		}
	}

	/** Newest captures first. A broken record does not hide other reviews and is never discarded. */
	public Listing list() throws IOException {
		if (!Files.exists(this.root)) {
			return new Listing(List.of(), List.of());
		}
		List<ModelReview> reviews = new ArrayList<>();
		List<Failure> failures = new ArrayList<>();
		try (var entries = Files.list(this.root)) {
			for (Path entry : entries.sorted().toList()) {
				if (entry.getFileName().toString().startsWith(".capture-")) {
					continue; // An in-progress capture, or a crash's unpublished staging directory.
				}
				if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(entry)) {
					continue;
				}
				String id = entry.getFileName().toString();
				try {
					reviews.add(load(id));
				} catch (IOException | IllegalArgumentException e) {
					failures.add(new Failure(id, e.getMessage()));
				}
			}
		}
		reviews.sort(Comparator.comparing(ModelReview::createdAt).reversed().thenComparing(ModelReview::id));
		return new Listing(reviews, failures);
	}

	/** Updates an existing issue; the captured context, comment, category and screenshot stay intact. */
	public synchronized void update(ModelReview review) throws IOException {
		update(review, null);
	}

	/** Updates a review only if the version the caller read is still current (for an inbox open during CLI edits). */
	public synchronized void update(ModelReview review, @Nullable Instant expectedUpdatedAt) throws IOException {
		Path dir = issueDirectory(review.id());
		if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(dir.resolve(LOCK))) {
			throw new IOException("review directory is missing or is a link");
		}
		// One byte-range lock is shared with model_reviews.py; the OS releases it even if either process exits.
		try (FileChannel channel = FileChannel.open(dir.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
			FileLock lock;
			try {
				lock = channel.tryLock(0, 1, false);
			} catch (OverlappingFileLockException e) {
				throw new IOException("review is being updated; retry", e);
			}
			if (lock == null) {
				throw new IOException("review is being updated; retry");
			}
			try (lock) {
				updateLocked(review, expectedUpdatedAt);
			}
		}
	}

	private void updateLocked(ModelReview review, @Nullable Instant expectedUpdatedAt) throws IOException {
		ModelReview previous = load(review.id());
		if (expectedUpdatedAt != null && !expectedUpdatedAt.equals(previous.updatedAt())) {
			throw new IOException("review was changed; reload it before updating");
		}
		if (!previous.createdAt().equals(review.createdAt()) || !previous.context().equals(review.context())
				|| previous.category() != review.category() || !previous.comment().equals(review.comment())
				|| previous.screenshot() != review.screenshot()) {
			throw new IOException("a review update cannot replace its captured evidence");
		}
		if (review.updatedAt().isBefore(previous.updatedAt())) {
			throw new IOException("review was changed; reload it before updating");
		}
		writeReview(issueDirectory(review.id()), review);
	}

	private static void writeReview(Path dir, ModelReview review) throws IOException {
		atomicWrite(dir.resolve(FILE), reviewBytes(review));
	}

	private static byte[] reviewBytes(ModelReview review) throws IOException {
		byte[] bytes = (review.toJson() + "\n").getBytes(StandardCharsets.UTF_8);
		if (bytes.length > MAX_JSON_BYTES) {
			throw new IOException("review context is too large");
		}
		return bytes;
	}

	private static void atomicWrite(Path file, byte[] bytes) throws IOException {
		Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
		try {
			Files.write(temp, bytes);
			try {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}
}
