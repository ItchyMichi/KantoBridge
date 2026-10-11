package dev.kantobridge.builder.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.McBootstrap;
import dev.kantobridge.routes.gen.ForestTheme;
import dev.kantobridge.routes.gen.RouteGenerator;
import dev.kantobridge.routes.gen.RouteSpec;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Minecraft's own overworld noise as a zone's land (docs/specs/WILD_ROUTES_PLAN.md §4.4; the first half of check C2):
 * built from the built-in registries, deterministic per seed, in the overworld's height range, and how long a zone's
 * samples and a whole zone's planning take. Printed timings are for the record; the asserts are generous.
 */
class VanillaTerrainTest {
	/** Where a first zone lands for Kanto at the synthetic build's origin (its box's south edge + the gap). */
	private static final int X = 3_200_000;
	private static final int Z = 3_203_200;
	private static HolderLookup.Provider registries;

	@BeforeAll
	static void bootstrap() {
		McBootstrap.ensure();
		registries = VanillaRegistries.createLookup();
	}

	@Test
	void theSameSeedGivesTheSameLandAndAnotherSeedOtherLand() {
		VanillaTerrain a = VanillaTerrain.of(registries, 1234L);
		VanillaTerrain b = VanillaTerrain.of(registries, 1234L);
		VanillaTerrain c = VanillaTerrain.of(registries, 99L);
		int differ = 0;
		for (int i = 0; i < 40; i++) {
			int x = X + i * 37;
			int z = Z + i * 53;
			int h = a.height(x, z);
			assertEquals(h, b.height(x, z));
			assertTrue(h >= -64 && h < 320, "height " + h);
			if (c.height(x, z) != h) {
				differ++;
			}
		}
		assertTrue(differ > 20, "another seed, other land: " + differ);
	}

	@Test
	void aZonesSamplesAndAWholeZonesPlan() {
		VanillaTerrain terrain = VanillaTerrain.of(registries, 1234L);
		int n = ZoneRegions.SLOT / ZonePlan.GRID + 2;
		long t0 = System.nanoTime();
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		for (int j = 0; j < n; j++) {
			for (int i = 0; i < n; i++) {
				int h = terrain.height(X + i * ZonePlan.GRID, Z + j * ZonePlan.GRID);
				min = Math.min(min, h);
				max = Math.max(max, h);
			}
		}
		long sampleMs = (System.nanoTime() - t0) / 1_000_000L;
		assertNotEquals(min, max, "the land isn't flat");
		long t1 = System.nanoTime();
		ZonePlan plan = new ZonePlan("test_zone", "KB_ROUTE_TEST_ZONE", "TEST_PALETTE",
				RouteGenerator.generate(RouteSpec.freeRoam(ForestTheme.INSTANCE), 7), X, Z, terrain);
		long planMs = (System.nanoTime() - t1) / 1_000_000L;
		long t2 = System.nanoTime();
		long[] codes = {0};
		var v = plan.volume();
		for (int z = v.minZ(); z < v.maxZ(); z++) {
			for (int x = v.minX(); x < v.maxX(); x++) {
				plan.renderColumn((bx, by, bz, code) -> codes[0]++, x, z);
			}
		}
		long renderMs = (System.nanoTime() - t2) / 1_000_000L;
		System.out.printf("C2a: %d samples in %d ms (%.1f us each), land %d..%d; zone plan %d ms (%d trees); every column rendered in %d ms "
				+ "(%d codes, %d chunks)%n", n * n, sampleMs, sampleMs * 1000.0 / (n * n), min, max, planMs, plan.trees(), renderMs, codes[0],
				((v.maxX() - v.minX()) / 16 + 1) * ((v.maxZ() - v.minZ()) / 16 + 1));
		assertTrue(sampleMs < 120_000 && planMs < 120_000 && renderMs < 60_000);
	}
}
