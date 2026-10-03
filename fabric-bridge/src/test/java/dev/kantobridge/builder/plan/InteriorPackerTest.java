package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.world.Scale;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Interior packing: under the door, floor chains downwards, ≥16-block gaps, deeper layers first, then sideways. */
class InteriorPackerTest {
	@Test
	void interiorsGoUnderTheirDoorOneLayerDown() {
		InteriorPacker packer = new InteriorPacker(14);
		InteriorPacker.Spot spot = packer.place(100, 200, 20, 16, 1);
		assertEquals(new InteriorPacker.Spot(100, 200, 1, false), spot);
		// The next floor of the same building stacks below, at the same position.
		InteriorPacker.Spot floor2 = packer.place(104, 202, 16, 16, spot.layer() + 1);
		assertEquals(new InteriorPacker.Spot(104, 202, 2, false), floor2);
	}

	@Test
	void anOccupiedSpotSpillsToADeeperLayer() {
		InteriorPacker packer = new InteriorPacker(14);
		packer.place(0, 0, 20, 20, 1);
		InteriorPacker.Spot second = packer.place(10, 10, 8, 8, 1);
		assertEquals(2, second.layer());
		assertFalse(second.moved());
	}

	@Test
	void interiorsKeepSixteenBlocksApart() {
		InteriorPacker packer = new InteriorPacker(14);
		packer.place(100, 0, 20, 20, 1); // with its ring: x 99..121
		// Ring to ring exactly 16 blocks: same layer.
		assertEquals(1, packer.place(138, 0, 10, 10, 1).layer());
		// One block closer to the first: not enough room on layer 1.
		InteriorPacker other = new InteriorPacker(14);
		other.place(100, 0, 20, 20, 1);
		assertEquals(2, other.place(137, 0, 10, 10, 1).layer());
	}

	@Test
	void whenEveryLayerIsTakenItMovesSideways() {
		InteriorPacker packer = new InteriorPacker(2);
		List<InteriorPacker.Box> placed = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			InteriorPacker.Spot spot = packer.place(0, 0, 20, 20, 1);
			assertEquals(i + 1, spot.layer());
			placed.add(InteriorPacker.footprint(spot.x(), spot.z(), 20, 20));
		}
		InteriorPacker.Spot moved = packer.place(0, 0, 20, 20, 1);
		assertNotNull(moved);
		assertTrue(moved.moved());
		assertEquals(1, moved.layer(), "sideways spots try the shallowest layer first");
		InteriorPacker.Box box = InteriorPacker.footprint(moved.x(), moved.z(), 20, 20);
		assertTrue(box.separated(placed.get(0), InteriorPacker.GAP));
		// The nearest free spot: three 16-block steps straight north.
		assertEquals(0, moved.x());
		assertEquals(-48, moved.z());
	}

	@Test
	void tooDeepIsRefused() {
		InteriorPacker packer = new InteriorPacker(Scale.maxLayer(-256));
		assertEquals(14, packer.maxLayer());
		assertNull(packer.place(0, 0, 4, 4, 15));
		assertNull(packer.place(0, 0, 4, 4, 0), "layer 0 is the surface");
	}

	@Test
	void layerMath() {
		assertEquals(64, Scale.feetY(0));
		assertEquals(42, Scale.feetY(1));
		assertEquals(20, Scale.feetY(2));
		assertEquals(-244, Scale.feetY(14));
		assertEquals(5, Scale.maxLayer(-64), "a vanilla-depth overworld fits five layers below Y 64");
		assertEquals(2, Scale.maxLayer(0));
	}
}
