package dev.kantobridge.routes.gen;

import java.nio.charset.StandardCharsets;

/**
 * Seeds for zones and dungeon visits (docs/specs/WILD_ROUTES_PLAN.md §4.3, §4.8). A world's seed and a zone's id make
 * the zone, the same every time (R3); a dungeon visit adds the visit's own number on top of its zone's seed.
 */
public final class RouteSeeds {
	private RouteSeeds() {
	}

	/** The seed of zone {@code routeId} in a world with seed {@code worldSeed}. */
	public static long forRoute(long worldSeed, String routeId) {
		return RouteRng.mix(worldSeed ^ RouteRng.mix(fnv1a64(routeId)));
	}

	/** The seed of one dungeon visit: the zone's seed and a number that changes on every visit. */
	public static long forVisit(long routeSeed, long visit) {
		return RouteRng.mix(routeSeed + RouteRng.mix(visit ^ 0x5DEECE66DL));
	}

	/** FNV-1a over the id's UTF-8 bytes: stable across JVMs, unlike {@link String#hashCode()}'s 32 bits. */
	static long fnv1a64(String text) {
		long h = 0xCBF29CE484222325L;
		for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
			h ^= b & 0xFF;
			h *= 0x100000001B3L;
		}
		return h;
	}
}
