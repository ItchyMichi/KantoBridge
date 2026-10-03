package dev.kantobridge.builder.plan;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Counted build findings, by code. {@code warnings} are anomalies in the data (the M2 gate expects none);
 * {@code notes} are expected situations worth counting (skipped unreachable maps, blank tiles). Codes are fixed
 * strings: no content ever goes in here.
 */
public final class Warnings {
	private final Map<String, Integer> warnings = new TreeMap<>();
	private final Map<String, Integer> notes = new TreeMap<>();

	public synchronized void warn(String code) {
		this.warnings.merge(code, 1, Integer::sum);
	}

	public synchronized void warn(String code, int count) {
		if (count > 0) {
			this.warnings.merge(code, count, Integer::sum);
		}
	}

	public synchronized void note(String code) {
		this.notes.merge(code, 1, Integer::sum);
	}

	public synchronized void note(String code, int count) {
		if (count > 0) {
			this.notes.merge(code, count, Integer::sum);
		}
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
}
