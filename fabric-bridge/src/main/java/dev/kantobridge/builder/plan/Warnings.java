package dev.kantobridge.builder.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Counted build findings, by code. {@code warnings} are anomalies in the data (the M2 gate expects none);
 * {@code notes} are expected situations worth counting (skipped unreachable maps, blank tiles). Codes are fixed
 * strings: no content ever goes in here.
 *
 * <p>A finding may also carry a <b>detail</b> row naming what it is about, in engine symbols only (map ids, swap kinds,
 * event flags, layers): the build writes them to {@code build_diagnostics.json} beside the report, never to the logs,
 * so a gate failure can be traced to its maps without opening game data.
 */
public final class Warnings {
	/** Detail rows kept per code; a full-Kanto build has at most a few dozen of any one. */
	public static final int MAX_DETAILS = 256;
	/** Detail values are symbols, small numbers or kinds: never free text. */
	private static final Pattern VALUE = Pattern.compile("^[A-Za-z0-9_.:-]{1,96}$");

	private final Map<String, Integer> warnings = new TreeMap<>();
	private final Map<String, Integer> notes = new TreeMap<>();
	private final Map<String, List<Map<String, String>>> details = new TreeMap<>();

	public synchronized void warn(String code) {
		this.warnings.merge(code, 1, Integer::sum);
	}

	public synchronized void warn(String code, int count) {
		if (count > 0) {
			this.warnings.merge(code, count, Integer::sum);
		}
	}

	/** A warning with its detail row ({@link #detail}). */
	public synchronized void warn(String code, Map<String, String> symbols) {
		this.warn(code);
		this.detail(code, symbols);
	}

	public synchronized void note(String code) {
		this.notes.merge(code, 1, Integer::sum);
	}

	public synchronized void note(String code, int count) {
		if (count > 0) {
			this.notes.merge(code, count, Integer::sum);
		}
	}

	/** A note with its detail row ({@link #detail}). */
	public synchronized void note(String code, Map<String, String> symbols) {
		this.note(code);
		this.detail(code, symbols);
	}

	/**
	 * Records what a finding is about, as symbol fields ({@code map}, {@code kind}, {@code source}, …). Values that aren't
	 * plain symbols are dropped, so nothing else can slip into the diagnostics file.
	 */
	public synchronized void detail(String code, Map<String, String> symbols) {
		List<Map<String, String>> rows = this.details.computeIfAbsent(code, k -> new ArrayList<>());
		if (rows.size() >= MAX_DETAILS) {
			return;
		}
		Map<String, String> row = new LinkedHashMap<>();
		new TreeMap<>(symbols).forEach((key, value) -> {
			if (value != null && VALUE.matcher(key).matches() && VALUE.matcher(value).matches()) {
				row.put(key, value);
			}
		});
		rows.add(Collections.unmodifiableMap(row));
	}

	/** A detail row from {@code key, value} pairs; pairs with a null value are left out. */
	public static Map<String, String> symbols(String... keyValues) {
		if (keyValues.length % 2 != 0) {
			throw new IllegalArgumentException("symbols come in key, value pairs");
		}
		Map<String, String> out = new LinkedHashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			if (keyValues[i + 1] != null) {
				out.put(keyValues[i], keyValues[i + 1]);
			}
		}
		return out;
	}

	public synchronized int total() {
		return this.warnings.values().stream().mapToInt(Integer::intValue).sum();
	}

	public synchronized int count(String code) {
		return this.warnings.getOrDefault(code, 0);
	}

	public synchronized int noteCount(String code) {
		return this.notes.getOrDefault(code, 0);
	}

	public synchronized Map<String, Integer> warnings() {
		return Collections.unmodifiableMap(new TreeMap<>(this.warnings));
	}

	public synchronized Map<String, Integer> notes() {
		return Collections.unmodifiableMap(new TreeMap<>(this.notes));
	}

	/** Detail rows by code (warnings and notes alike), in the order they were found. */
	public synchronized Map<String, List<Map<String, String>>> details() {
		Map<String, List<Map<String, String>>> out = new TreeMap<>();
		this.details.forEach((code, rows) -> out.put(code, List.copyOf(rows)));
		return Collections.unmodifiableMap(out);
	}

	/** Detail rows of one code. */
	public synchronized List<Map<String, String>> details(String code) {
		return List.copyOf(this.details.getOrDefault(code, List.of()));
	}
}
