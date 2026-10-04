package dev.kantobridge.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Arrays;

/**
 * Dev-only rendering counters (docs/specs/VOXEL_BLOCKS_PLAN.md §5.1, §10), behind {@code -Dkantobridge.meshstats=true}:
 * how long each chunk section takes to mesh (the dev timing mixin on {@code SectionCompiler#compile}), how much of that
 * is our model's own work and how many quads it emitted, and the summaries the measurement report holds. Numbers only.
 *
 * <p>The section counters are written from the mesh worker threads: each thread keeps its open section in a thread
 * local, and finished sections go into one locked sample list while a measurement records. Without the property every
 * entry point returns at once ({@link #ENABLED} is a constant, so the JIT drops the calls).
 */
public final class MeshStats {
	public static final String PROPERTY = "kantobridge.meshstats";
	/** Where the report goes; the dev client's Gradle run points it at {@code local/reports/mesh_report.json}. */
	public static final String REPORT_PROPERTY = "kantobridge.meshstats.report";
	public static final boolean ENABLED = Boolean.getBoolean(PROPERTY);

	/** Values per section sample: total, our own time (ns), our quads, the section ({@code SectionPos#asLong}). */
	private static final int STRIDE = 4;
	private static final Object LOCK = new Object();
	private static final ThreadLocal<long[]> OPEN = ThreadLocal.withInitial(() -> new long[STRIDE]);

	private static long[] samples = new long[1024 * STRIDE];
	private static int sampleCount;
	private static volatile boolean recording;
	private static volatile long lastSectionEnd;
	private static volatile long sectionsCompiled;
	/** Which model draws Kanto's blocks: {@code json} (the generated pack's models) or {@code runtime} (our model). */
	private static volatile String model = "json";

	/** One distribution, in milliseconds. */
	public record Summary(int count, double mean, double p50, double p95, double p99, double max) {
		public JsonObject toJson() {
			JsonObject out = new JsonObject();
			out.addProperty("count", this.count);
			out.addProperty("mean", round(this.mean));
			out.addProperty("p50", round(this.p50));
			out.addProperty("p95", round(this.p95));
			out.addProperty("p99", round(this.p99));
			out.addProperty("max", round(this.max));
			return out;
		}
	}

	private MeshStats() {
	}

	// ---- mesh threads ----

	/**
	 * A section starts meshing on this thread (the dev mixin, at the head of {@code SectionCompiler#compile}).
	 *
	 * @param section the section's position, {@code SectionPos#asLong}
	 */
	public static void sectionStart(long section) {
		long[] open = OPEN.get();
		open[0] = System.nanoTime();
		open[1] = 0;
		open[2] = 0;
		open[3] = section;
	}

	/** The section this thread meshed is done (the dev mixin, at every return of {@code compile}). */
	public static void sectionEnd() {
		long[] open = OPEN.get();
		long end = System.nanoTime();
		lastSectionEnd = end;
		sectionsCompiled++;
		if (!recording) {
			return;
		}
		synchronized (LOCK) {
			if (sampleCount * STRIDE + STRIDE > samples.length) {
				samples = Arrays.copyOf(samples, samples.length * 2);
			}
			int i = sampleCount * STRIDE;
			samples[i] = end - open[0];
			samples[i + 1] = open[1];
			samples[i + 2] = open[2];
			samples[i + 3] = open[3];
			sampleCount++;
		}
	}

	/** Our model's work on one block, added to this thread's open section. */
	public static void ours(long nanos, int quads) {
		long[] open = OPEN.get();
		open[1] += nanos;
		open[2] += quads;
	}

	// ---- client thread ----

	/** Starts a recording: the section samples are cleared. */
	public static void startRecording() {
		synchronized (LOCK) {
			sampleCount = 0;
		}
		recording = true;
	}

	public static void stopRecording() {
		recording = false;
	}

	public static boolean recording() {
		return recording;
	}

	/** {@link System#nanoTime()} when a section last finished meshing, 0 before any. */
	public static long lastSectionEnd() {
		return lastSectionEnd;
	}

	/** Sections meshed since start-up (approximate: written by several threads). */
	public static long sectionsCompiled() {
		return sectionsCompiled;
	}

	public static String model() {
		return model;
	}

	/** Set by the model plugin once our runtime model draws the tile blocks. */
	public static void setModel(String name) {
		model = name;
	}

	/** The recorded sections: total time, our time, our quads and the section's position per sample (copies). */
	public static long[][] sections() {
		synchronized (LOCK) {
			long[] total = new long[sampleCount];
			long[] ours = new long[sampleCount];
			long[] quads = new long[sampleCount];
			long[] where = new long[sampleCount];
			for (int s = 0; s < sampleCount; s++) {
				total[s] = samples[s * STRIDE];
				ours[s] = samples[s * STRIDE + 1];
				quads[s] = samples[s * STRIDE + 2];
				where[s] = samples[s * STRIDE + 3];
			}
			return new long[][] {total, ours, quads, where};
		}
	}

	// ---- summaries (pure) ----

	/** A distribution of nanosecond values, in milliseconds; all zero for no values. */
	public static Summary summarizeNanos(long[] nanos) {
		double[] ms = new double[nanos.length];
		for (int i = 0; i < nanos.length; i++) {
			ms[i] = nanos[i] / 1_000_000.0;
		}
		return summarize(ms);
	}

	/** A distribution of plain values (counts, milliseconds); all zero for no values. */
	public static Summary summarize(double[] values) {
		if (values.length == 0) {
			return new Summary(0, 0, 0, 0, 0, 0);
		}
		double[] sorted = values.clone();
		Arrays.sort(sorted);
		double sum = 0;
		for (double v : sorted) {
			sum += v;
		}
		return new Summary(sorted.length, sum / sorted.length, percentile(sorted, 50), percentile(sorted, 95), percentile(sorted, 99),
				sorted[sorted.length - 1]);
	}

	/** The nearest-rank percentile of sorted values. */
	public static double percentile(double[] sorted, double p) {
		if (sorted.length == 0) {
			return 0;
		}
		int rank = (int) Math.ceil(p / 100.0 * sorted.length);
		return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
	}

	/** Frames per second from frame times: the mean rate, and the "1% low" (the rate of the 99th-percentile frame). */
	public static double[] fps(Summary frameMs) {
		double mean = frameMs.mean() > 0 ? 1000.0 / frameMs.mean() : 0;
		double low = frameMs.p99() > 0 ? 1000.0 / frameMs.p99() : 0;
		return new double[] {round(mean), round(low)};
	}

	/** Appends one run to a report ({@code {"version":1,"runs":[...]}}); a missing or foreign report starts anew. */
	public static JsonObject append(JsonObject report, JsonObject run) {
		JsonObject out = report.has("version") && report.get("version").isJsonPrimitive() && report.get("version").getAsInt() == 1
				&& report.has("runs") && report.get("runs").isJsonArray() ? report : new JsonObject();
		if (!out.has("version")) {
			out.addProperty("version", 1);
			out.add("runs", new JsonArray());
		}
		out.getAsJsonArray("runs").add(run);
		return out;
	}

	static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
