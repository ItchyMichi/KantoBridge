package dev.kantobridge.client.puppet;

import dev.kantobridge.builder.pack.Textures;
import dev.kantobridge.world.Dir;
import net.minecraft.util.Mth;

/**
 * Which frame of an NPC sheet to draw (docs/PROTOCOL.md "The generated resource pack": 64×32 sheets, row 0 standing and
 * row 1 walking, columns facing down, up, left and right, right being left mirrored). Gen 1 draws NPCs as seen from
 * above with the camera looking north; here the camera turns, so the column is the NPC's facing as seen from the
 * camera: facing the camera shows the "down" frame, facing away the "up" frame, facing the camera's right the "right"
 * frame.
 */
public final class FacingFrames {
	public static final int FRAME = 16;
	public static final int COLUMN_DOWN = 0;
	public static final int COLUMN_UP = 1;
	public static final int COLUMN_LEFT = 2;
	public static final int COLUMN_RIGHT = 3;

	/** A frame's place in the sheet, as texture coordinates (0..1), already mirrored when {@code u0 > u1}. */
	public record Uv(float u0, float v0, float u1, float v1) {
	}

	private FacingFrames() {
	}

	/**
	 * The sheet column for an NPC facing {@code facing} seen by a camera with yaw {@code cameraYaw} (Minecraft degrees, 0
	 * looks south).
	 */
	public static int column(Dir facing, float cameraYaw) {
		float relative = Mth.wrapDegrees(facing.yaw() - cameraYaw);
		if (Math.abs(relative) <= 45.0F) {
			// Facing where the camera looks: its back.
			return COLUMN_UP;
		}
		if (Math.abs(relative) >= 135.0F) {
			return COLUMN_DOWN;
		}
		return relative > 0 ? COLUMN_RIGHT : COLUMN_LEFT;
	}

	/** The texture coordinates of one frame; a mirrored walk swaps its left and right edges. */
	public static Uv uv(int column, boolean walking, boolean mirrored) {
		float u0 = column * FRAME / (float) Textures.SHEET_WIDTH;
		float u1 = (column + 1) * FRAME / (float) Textures.SHEET_WIDTH;
		int row = walking ? 1 : 0;
		float v0 = row * FRAME / (float) Textures.SHEET_HEIGHT;
		float v1 = (row + 1) * FRAME / (float) Textures.SHEET_HEIGHT;
		return mirrored ? new Uv(u1, v0, u0, v1) : new Uv(u0, v0, u1, v1);
	}
}
