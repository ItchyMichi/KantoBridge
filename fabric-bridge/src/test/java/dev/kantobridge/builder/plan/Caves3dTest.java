package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The caves built in 3D (docs/specs/CAVES_3D_PLAN.md §0): every one since H7, unless the build option names some. */
class Caves3dTest {
	@Test
	void everyCaveIsBuiltIn3dUnlessSomeAreNamed() {
		assertEquals(Caves3d.EVERY, Caves3d.parse(null));
		assertEquals(Caves3d.EVERY, Caves3d.parse(" "));
		assertEquals(Caves3d.EVERY, Caves3d.parse(" , "));
		assertTrue(Caves3d.in3d(Caves3d.parse(null), List.of("ROCK_TUNNEL_1F", "ROCK_TUNNEL_B1F")));
		Set<String> named = Caves3d.parse("MT_MOON_1F, ROCK_TUNNEL_B1F");
		assertEquals(Set.of("MT_MOON_1F", "ROCK_TUNNEL_B1F"), named);
		assertTrue(Caves3d.in3d(named, List.of("ROCK_TUNNEL_1F", "ROCK_TUNNEL_B1F")), "a complex is named by any of its maps");
		assertFalse(Caves3d.in3d(named, List.of("SEAFOAM_ISLANDS_1F", "SEAFOAM_ISLANDS_B1F")));
		assertFalse(Caves3d.in3d(Caves3d.parse("none"), List.of("MT_MOON_1F")), "a value naming no cave: every cave flat");
	}
}
