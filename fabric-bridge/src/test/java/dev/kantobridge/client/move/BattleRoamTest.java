package dev.kantobridge.client.move;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** BATTLE_ROAM_PLAN: the fence round where a battle started. Yaw 0 faces south (+z), -90 east (+x), 90 west. */
class BattleRoamTest {
	private static final BattleRoam.Anchor ANCHOR = new BattleRoam.Anchor(100.5, -40.5);
	private static final double EDGE_X = ANCHOR.x() + BattleRoam.RADIUS;

	@Test
	void insideTheFenceNothingChanges() {
		BattleRoam.Impulse impulse = BattleRoam.fence(ANCHOR, ANCHOR.x() + 5.9, ANCHOR.z(), -90.0F, 1.0F, 0.0F);
		assertEquals(new BattleRoam.Impulse(1.0F, 0.0F), impulse);
	}

	@Test
	void atTheFenceWalkingOutStops() {
		BattleRoam.Impulse impulse = BattleRoam.fence(ANCHOR, EDGE_X, ANCHOR.z(), -90.0F, 1.0F, 0.0F);
		assertEquals(0.0F, impulse.forward(), 1.0E-6);
		assertEquals(0.0F, impulse.left(), 1.0E-6);
	}

	@Test
	void beyondTheFenceTheInputLeansBackIn() {
		// a shove two blocks past it: walking out turns into walking back at full speed
		BattleRoam.Impulse impulse = BattleRoam.fence(ANCHOR, EDGE_X + 2.0, ANCHOR.z(), -90.0F, 1.0F, 0.0F);
		assertEquals(-1.0F, impulse.forward(), 1.0E-6);
		// a quarter block past it, walking along (south): the walk keeps its way and leans west by half (facing south, left
		// is east, so west is a negative left)
		impulse = BattleRoam.fence(ANCHOR, EDGE_X + 0.25, ANCHOR.z(), 0.0F, 1.0F, 0.0F);
		assertEquals(1.0F, impulse.forward(), 1.0E-6);
		assertEquals(-0.5F, impulse.left(), 1.0E-6);
	}

	@Test
	void atTheFenceWalkingInOrAlongIsFree() {
		assertEquals(new BattleRoam.Impulse(1.0F, 0.0F), BattleRoam.fence(ANCHOR, EDGE_X, ANCHOR.z(), 90.0F, 1.0F, 0.0F), "back in");
		assertEquals(new BattleRoam.Impulse(1.0F, 0.0F), BattleRoam.fence(ANCHOR, EDGE_X, ANCHOR.z(), 0.0F, 1.0F, 0.0F), "along");
		assertEquals(new BattleRoam.Impulse(0.0F, -1.0F), BattleRoam.fence(ANCHOR, EDGE_X, ANCHOR.z(), 90.0F, 0.0F, -1.0F),
				"strafing along");
	}

	@Test
	void aDiagonalOutwardKeepsOnlyItsPartAlongTheFence() {
		// facing south-east at the east edge: the east half goes, the south half stays
		BattleRoam.Impulse impulse = BattleRoam.fence(ANCHOR, EDGE_X, ANCHOR.z(), -45.0F, 1.0F, 0.0F);
		double radians = Math.toRadians(-45.0);
		double mx = impulse.left() * Math.cos(radians) - impulse.forward() * Math.sin(radians);
		double mz = impulse.forward() * Math.cos(radians) + impulse.left() * Math.sin(radians);
		assertEquals(0.0, mx, 1.0E-6, "nothing east");
		assertEquals(Math.sqrt(0.5), mz, 1.0E-6, "the south part kept");
	}

	@Test
	void standingStillIsLeftAlone() {
		assertEquals(new BattleRoam.Impulse(0.0F, 0.0F), BattleRoam.fence(ANCHOR, EDGE_X + 1.0, ANCHOR.z(), 0.0F, 0.0F, 0.0F));
	}
}
