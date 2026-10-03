package dev.kantobridge.client.puppet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.kantobridge.builder.pack.Textures;
import dev.kantobridge.world.Dir;
import org.junit.jupiter.api.Test;

/**
 * The sheet frame for an NPC's facing as the camera sees it, and the frame's texture coordinates in the pack writer's
 * 64×32 layout (row 0 standing, row 1 walking; columns down, up, left, right).
 */
class FacingFramesTest {
	@Test
	void theClassicViewLooksNorthAsGen1Does() {
		float north = Dir.UP.yaw();
		assertEquals(FacingFrames.COLUMN_DOWN, FacingFrames.column(Dir.DOWN, north), "facing the camera");
		assertEquals(FacingFrames.COLUMN_UP, FacingFrames.column(Dir.UP, north), "its back");
		assertEquals(FacingFrames.COLUMN_LEFT, FacingFrames.column(Dir.LEFT, north));
		assertEquals(FacingFrames.COLUMN_RIGHT, FacingFrames.column(Dir.RIGHT, north));
	}

	@Test
	void theFrameTurnsWithTheCamera() {
		float south = Dir.DOWN.yaw();
		assertEquals(FacingFrames.COLUMN_UP, FacingFrames.column(Dir.DOWN, south), "looking the same way as the NPC");
		assertEquals(FacingFrames.COLUMN_DOWN, FacingFrames.column(Dir.UP, south));
		// Looking south, west is on the camera's right.
		assertEquals(FacingFrames.COLUMN_RIGHT, FacingFrames.column(Dir.LEFT, south));
		assertEquals(FacingFrames.COLUMN_LEFT, FacingFrames.column(Dir.RIGHT, south));
		float east = Dir.RIGHT.yaw();
		assertEquals(FacingFrames.COLUMN_DOWN, FacingFrames.column(Dir.LEFT, east), "looking east at an NPC facing west");
		assertEquals(FacingFrames.COLUMN_LEFT, FacingFrames.column(Dir.UP, east), "north is on the left when looking east");
	}

	@Test
	void anglesBetweenQuadrantsAndAnyWindingWork() {
		assertEquals(FacingFrames.COLUMN_DOWN, FacingFrames.column(Dir.DOWN, 180.0F + 30.0F));
		assertEquals(FacingFrames.COLUMN_DOWN, FacingFrames.column(Dir.DOWN, 180.0F + 720.0F));
		assertEquals(FacingFrames.COLUMN_DOWN, FacingFrames.column(Dir.DOWN, -180.0F));
		assertEquals(FacingFrames.COLUMN_UP, FacingFrames.column(Dir.DOWN, 44.0F));
		assertEquals(FacingFrames.COLUMN_LEFT, FacingFrames.column(Dir.DOWN, 60.0F));
	}

	@Test
	void textureCoordinatesFollowThePackLayout() {
		assertEquals(64, Textures.SHEET_WIDTH);
		assertEquals(32, Textures.SHEET_HEIGHT);
		FacingFrames.Uv standingLeft = FacingFrames.uv(FacingFrames.COLUMN_LEFT, false, false);
		assertEquals(new FacingFrames.Uv(0.5F, 0.0F, 0.75F, 0.5F), standingLeft);
		FacingFrames.Uv walkingDown = FacingFrames.uv(FacingFrames.COLUMN_DOWN, true, false);
		assertEquals(new FacingFrames.Uv(0.0F, 0.5F, 0.25F, 1.0F), walkingDown);
		FacingFrames.Uv mirrored = FacingFrames.uv(FacingFrames.COLUMN_UP, true, true);
		assertEquals(new FacingFrames.Uv(0.5F, 0.5F, 0.25F, 1.0F), mirrored, "left and right edges swapped");
	}
}
