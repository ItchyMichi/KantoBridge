package dev.kantobridge.client.move;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.WorldIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** Directions from yaw and keys, and cells as physical spots of the synthetic world. */
class DirIntentSpotTest {
	@Test
	void yawQuadrants() {
		assertEquals(Dir.DOWN, Dir.fromYaw(0.0F));
		assertEquals(Dir.LEFT, Dir.fromYaw(90.0F));
		assertEquals(Dir.UP, Dir.fromYaw(180.0F));
		assertEquals(Dir.UP, Dir.fromYaw(-180.0F));
		assertEquals(Dir.RIGHT, Dir.fromYaw(-90.0F));
		assertEquals(Dir.RIGHT, Dir.fromYaw(270.0F));
		assertEquals(Dir.DOWN, Dir.fromYaw(360.0F));
		assertEquals(Dir.DOWN, Dir.fromYaw(44.0F));
		assertEquals(Dir.LEFT, Dir.fromYaw(46.0F));
		assertEquals(Dir.RIGHT, Dir.fromYaw(-450.0F));
		assertEquals(Dir.LEFT, Dir.fromYaw(-310.0F));
		for (Dir dir : Dir.values()) {
			assertEquals(dir, Dir.fromYaw(dir.yaw()), "yaw round trip");
			assertEquals(dir, Dir.byId(dir.id()));
			assertEquals(dir, Dir.of(dir.dx(), dir.dy()));
			assertEquals(dir, dir.opposite().opposite());
		}
		assertNull(Dir.byId("north"));
		assertNull(Dir.of(1, 1));
	}

	@Test
	void keysPointWhereMinecraftWouldMove() {
		// Yaw 0 looks south (down): forward is down, back is up, strafing left (A) goes east (right).
		assertEquals(Dir.DOWN, Intent.of(0.0F, 1.0F, 0.0F));
		assertEquals(Dir.UP, Intent.of(0.0F, -1.0F, 0.0F));
		assertEquals(Dir.RIGHT, Intent.of(1.0F, 0.0F, 0.0F));
		assertEquals(Dir.LEFT, Intent.of(-1.0F, 0.0F, 0.0F));
		assertEquals(Dir.UP, Intent.of(0.0F, 1.0F, 180.0F));
		assertEquals(Dir.LEFT, Intent.of(0.0F, 1.0F, 90.0F));
		assertEquals(Dir.RIGHT, Intent.of(0.0F, 1.0F, -90.0F));
		assertEquals(Dir.DOWN, Intent.of(0.0F, 1.0F, 30.0F), "clearly closer to south");
		assertNull(Intent.of(0.0F, 1.0F, 42.0F), "nearly diagonal: no direction");
		assertNull(Intent.of(1.0F, 1.0F, 0.0F), "two keys at once");
		assertNull(Intent.of(0.0F, 0.05F, 0.0F), "below the dead zone");
		assertEquals(Dir.DOWN, Intent.of(0.0F, 0.3F, 0.0F), "sneaking still counts");
	}

	@Test
	void spotsAreCellsOfTheBuiltWorld() throws Exception {
		WorldIndex world = WorldIndex.fromJson(Files.readString(TestData.goldenIndex(), StandardCharsets.UTF_8));
		Spot town = Spot.of(world, "TEST_TOWN", 5, 8);
		assertEquals(new Spot(3_200_010, 64, 3_200_016), town);
		assertEquals(3_200_011.0, town.centreX());
		assertEquals(3_200_017.0, town.centreZ());
		assertEquals(town, Spot.at(world, 3_200_011.9, 64.0, 3_200_016.1));
		assertEquals(town, Spot.at(world, 3_200_010.0, 63.5, 3_200_017.99), "standing on a half block (surfing)");
		assertEquals(new CellPos("TEST_TOWN", 5, 8), town.cell(world));
		assertEquals(Spot.of(world, "TEST_TOWN", 6, 6), town.offset(1, -2));
		assertEquals(3, town.cellsTo(town.offset(1, -2)));
		assertNull(Spot.of(world, "NOWHERE", 1, 1));
		assertNull(Spot.at(world, 0.0, 64.0, 0.0), "off every map");
		Spot house = Spot.at(world, world.toBlock("TEST_HOUSE", 4, 8).x, world.toBlock("TEST_HOUSE", 4, 8).y, world.toBlock("TEST_HOUSE", 4, 8).z);
		assertEquals(Spot.of(world, "TEST_HOUSE", 4, 8), house);
		assertEquals(false, house.sameLevel(town));
		// A cell just outside a map (y = -1) is still a place on the grid.
		assertEquals(town.offset(0, -9), Spot.of(world, "TEST_TOWN", 5, -1));
	}
}
