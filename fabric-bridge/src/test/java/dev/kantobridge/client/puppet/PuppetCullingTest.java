package dev.kantobridge.client.puppet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.world.Scale;
import org.junit.jupiter.api.Test;

/**
 * Puppet culling (M10 performance pass): drawn within {@link PuppetCulling#MAX_DISTANCE} of the camera and on the hidden
 * player's current map area (its footprint plus a margin, on its own layer); everything else is skipped.
 */
class PuppetCullingTest {
	/** A 10×9-cell map with its north-west corner at (100, 64, 200): 20×18 blocks. */
	private static final PuppetCulling.Area TOWN = new PuppetCulling.Area(100, 200, 120, 218, 64);

	@Test
	void nearbyPuppetsOnTheMapAreDrawn() {
		assertTrue(PuppetCulling.visible(110, 64, 210, 105, 65.6, 205, TOWN));
		assertTrue(PuppetCulling.visible(100, 64, 200, 110, 65.6, 210, TOWN), "on the footprint's corner");
	}

	@Test
	void farPuppetsAreNot() {
		double edge = PuppetCulling.MAX_DISTANCE;
		assertTrue(PuppetCulling.visible(0, 64, 0, edge, 64, 0, null), "exactly at the limit");
		assertFalse(PuppetCulling.visible(0, 64, 0, edge + 0.01, 64, 0, null));
		assertFalse(PuppetCulling.visible(0, 64, 0, 0, 64 - edge - 1, 0, null), "straight below counts too");
		assertFalse(PuppetCulling.visible(Double.NaN, 64, 0, 0, 64, 0, null), "an unknown position is never drawn");
	}

	@Test
	void puppetsOffTheHiddenPlayersMapAreNot() {
		// Just outside the footprint, within the margin (a seam's neighbour): drawn.
		assertTrue(PuppetCulling.visible(99, 64, 210, 105, 65.6, 210, TOWN));
		assertTrue(PuppetCulling.visible(120 + PuppetCulling.MARGIN, 64, 210, 110, 65.6, 210, TOWN));
		// Beyond the margin: another map's leftover.
		assertFalse(PuppetCulling.visible(120 + PuppetCulling.MARGIN + 0.5, 64, 210, 110, 65.6, 210, TOWN));
		assertFalse(PuppetCulling.visible(110, 64, 200 - PuppetCulling.MARGIN - 0.5, 110, 65.6, 210, TOWN));
		// The same footprint one layer down (an interior under the town): not this map.
		assertFalse(PuppetCulling.visible(110, 64 - Scale.LAYER_STEP, 210, 110, 64 - Scale.LAYER_STEP + 1.6, 210, TOWN));
		// Without a known map only the distance counts.
		assertTrue(PuppetCulling.visible(110, 64 - Scale.LAYER_STEP, 210, 110, 64 - Scale.LAYER_STEP + 1.6, 210, null));
	}

	@Test
	void theAreaContainsItsLayerOnly() {
		assertTrue(TOWN.contains(110, 64, 210));
		assertTrue(TOWN.contains(110, 64 + Scale.LAYER_STEP / 2.0 - 0.5, 210), "a step up the stairs");
		assertFalse(TOWN.contains(110, 64 + Scale.LAYER_STEP / 2.0, 210));
		assertFalse(TOWN.contains(110, 64 - Scale.LAYER_STEP / 2.0, 210));
	}
}
