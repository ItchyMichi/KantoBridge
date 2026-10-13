package dev.kantobridge.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** MG3: a build is (bundle, region, slot); without a region it is the legacy build, byte for byte. */
class BuildOptionsTest {
	@Test
	void theDefaultsAreALegacyBuild() {
		BuildOptions options = BuildOptions.defaults();
		assertNull(options.region());
		assertEquals(BuildOptions.NO_SLOT, options.slot());
		assertFalse(options.regionBuild());
		// the old constructor still describes a legacy build
		BuildOptions legacy = new BuildOptions(0, 0, null, null, null, true, true, true, true, BuildOptions.DEFAULT_TICK_BUDGET_MS, true);
		assertEquals(options, legacy);
		assertFalse(options.withOrigin(8192, 0).withShapes(false).regionBuild(), "the other with* keep the legacy build");
	}

	@Test
	void withRegionMakesARegionBuild() {
		BuildOptions options = BuildOptions.defaults().withOrigin(16, 32).withRegion("hoenn", 1);
		assertTrue(options.regionBuild());
		assertEquals("hoenn", options.region());
		assertEquals(1, options.slot());
		assertEquals(16, options.originX(), "the world offset stays what it was");
		assertEquals("hoenn", options.withShapes(false).withWorldSpawn(false).region(), "the other with* keep the region");
		assertThrows(IllegalArgumentException.class, () -> BuildOptions.defaults().withRegion("Hoenn", 1));
		assertThrows(IllegalArgumentException.class, () -> BuildOptions.defaults().withRegion("hoenn", 64));
		assertThrows(IllegalArgumentException.class, () -> BuildOptions.defaults().withRegion("hoenn", -1));
		assertThrows(IllegalArgumentException.class, () -> BuildOptions.defaults().withRegion(null, 0));
	}

	@Test
	void theHeadlessRegionOptionsComeTogetherOrNotAtAll() {
		assertNull(BuildOptions.parseRegion(null, null));
		assertNull(BuildOptions.parseRegion("", " "));
		BuildOptions.RegionOption option = BuildOptions.parseRegion(" hoenn ", "1");
		assertEquals(new BuildOptions.RegionOption("hoenn", 1), option);
		assertEquals(new BuildOptions.RegionOption("kanto", 0), BuildOptions.parseRegion("kanto", "0"));
		assertThrows(IllegalArgumentException.class, () -> BuildOptions.parseRegion("hoenn", null));
		assertThrows(IllegalArgumentException.class, () -> BuildOptions.parseRegion(null, "1"));
		assertThrows(IllegalArgumentException.class, () -> BuildOptions.parseRegion("Hoenn", "1"));
		assertThrows(IllegalArgumentException.class, () -> BuildOptions.parseRegion("hoenn", "one"));
		assertThrows(IllegalArgumentException.class, () -> BuildOptions.parseRegion("hoenn", "64"));
	}
}
