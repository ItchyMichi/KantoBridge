package dev.kantobridge.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** The dev measuring tool's arithmetic and its gate (docs/specs/VOXEL_BLOCKS_PLAN.md V1a). */
class MeshStatsTest {
	@Test
	void theTimingMixinAppliesOnlyWithTheProperty() {
		Properties none = new Properties();
		assertFalse(MeshStatsMixinPlugin.applies(none), "a normal game never loads the dev mixin");
		Properties on = new Properties();
		on.setProperty(MeshStats.PROPERTY, "true");
		assertTrue(MeshStatsMixinPlugin.applies(on));
		on.setProperty(MeshStats.PROPERTY, "yes");
		assertFalse(MeshStatsMixinPlugin.applies(on));
		assertFalse(MeshStats.ENABLED, "the tests run without it");
	}

	@Test
	void percentilesAreNearestRank() {
		double[] values = new double[100];
		for (int i = 0; i < 100; i++) {
			values[i] = 100 - i;
		}
		MeshStats.Summary s = MeshStats.summarize(values);
		assertEquals(100, s.count());
		assertEquals(50.5, s.mean(), 1e-9);
		assertEquals(50, s.p50());
		assertEquals(95, s.p95());
		assertEquals(99, s.p99());
		assertEquals(100, s.max());
		MeshStats.Summary empty = MeshStats.summarize(new double[0]);
		assertEquals(0, empty.count());
		assertEquals(0, empty.p95());
	}

	@Test
	void framesBecomeRates() {
		MeshStats.Summary frames = MeshStats.summarizeNanos(new long[] {10_000_000L, 10_000_000L, 10_000_000L, 40_000_000L});
		assertEquals(17.5, frames.mean(), 1e-9);
		double[] fps = MeshStats.fps(frames);
		assertEquals(57.14, fps[0], 1e-9, "1000 / mean frame time");
		assertEquals(25.0, fps[1], 1e-9, "the 1% low: 1000 / the 99th-percentile frame time");
	}

	@Test
	void runsAreAppended() {
		JsonObject run = new JsonObject();
		run.addProperty("remesh_ms", 12.5);
		JsonObject report = MeshStats.append(new JsonObject(), run);
		assertEquals(1, report.get("version").getAsInt());
		assertEquals(1, report.getAsJsonArray("runs").size());
		report = MeshStats.append(report, run);
		assertEquals(2, report.getAsJsonArray("runs").size());
		JsonObject foreign = new JsonObject();
		foreign.addProperty("version", 7);
		assertEquals(1, MeshStats.append(foreign, run).getAsJsonArray("runs").size(), "an unknown report starts anew");
	}

	@Test
	void sectionsAreRecordedOnlyWhileMeasuring() {
		MeshStats.stopRecording();
		MeshStats.sectionStart(1L);
		MeshStats.ours(1_000, 6);
		MeshStats.sectionEnd();
		MeshStats.startRecording();
		assertEquals(0, MeshStats.sections()[0].length, "a new recording starts empty");
		MeshStats.sectionStart(42L);
		MeshStats.ours(2_000, 12);
		MeshStats.ours(1_000, 6);
		MeshStats.sectionEnd();
		long[][] sections = MeshStats.sections();
		MeshStats.stopRecording();
		assertEquals(1, sections[0].length);
		assertEquals(3_000, sections[1][0], "our time adds up per section");
		assertEquals(18, sections[2][0], "our quads too");
		assertEquals(42L, sections[3][0], "and where the section is");
		assertTrue(sections[0][0] >= 0);
	}
}
