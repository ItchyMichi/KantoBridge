package dev.kantobridge.character;

import java.util.BitSet;

/**
 * A character sheet's frames as masks (docs/specs/VOXEL_BLOCKS_PLAN.md §5.11; report B5.1): the generated layout, columns
 * facing down, up, left and right, rows standing and walking, at the generated 64 × 32 or any whole multiple of it (a
 * pack's sheet). Geometry comes from masks only, so it is palette-free; frames larger than {@link #GEOMETRY_MAX} pixels
 * are down-sampled (nearest) for geometry, the texture stays as it is. {@code frames} is how many distinct frames the
 * export had (1, 3 or 6): a 3-frame sheet's walking row repeats its standing one, a 1-frame sheet's every frame is the
 * same. Pure.
 */
public record SpriteFrames(int size, BitSet[][] masks, int frames) {
	public static final int STAND = 0;
	public static final int WALK = 1;
	public static final int DOWN = 0;
	public static final int UP = 1;
	public static final int LEFT = 2;
	public static final int RIGHT = 3;
	/** Frames are built from at most this many pixels a side. */
	public static final int GEOMETRY_MAX = 32;

	/**
	 * The frames of a sheet.
	 *
	 * @param opaque per pixel of the sheet, row-major: not transparent
	 * @param width  the sheet's width (4 frames)
	 * @param height the sheet's height (2 frames)
	 * @param frames the export's frame count (1, 3 or 6)
	 */
	public static SpriteFrames of(boolean[] opaque, int width, int height, int frames) {
		int frame = width / 4;
		if (frame < 1 || width != frame * 4 || height != frame * 2 || opaque.length != width * height) {
			throw new IllegalArgumentException("not a sheet in the generated layout");
		}
		int size = Math.min(frame, GEOMETRY_MAX);
		BitSet[][] masks = new BitSet[2][4];
		for (int row = 0; row < 2; row++) {
			for (int column = 0; column < 4; column++) {
				BitSet mask = new BitSet(size * size);
				for (int y = 0; y < size; y++) {
					for (int x = 0; x < size; x++) {
						int sx = column * frame + x * frame / size;
						int sy = row * frame + y * frame / size;
						if (opaque[sy * width + sx]) {
							mask.set(y * size + x);
						}
					}
				}
				masks[row][column] = mask;
			}
		}
		return new SpriteFrames(size, masks, frames);
	}

	/**
	 * The frames of a sheet whose frame count isn't known (a client reading its resolved sheet): 1 when every frame is
	 * the same, 3 when the walking row repeats the standing one, else 6.
	 */
	public static SpriteFrames ofSheet(boolean[] opaque, int width, int height) {
		SpriteFrames six = of(opaque, width, height, 6);
		boolean walkRepeats = true;
		boolean allSame = true;
		for (int column = 0; column < 4; column++) {
			walkRepeats &= six.masks[WALK][column].equals(six.masks[STAND][column]);
			allSame &= six.masks[STAND][column].equals(six.masks[STAND][DOWN]);
		}
		int frames = walkRepeats ? (allSame ? 1 : 3) : 6;
		return new SpriteFrames(six.size, six.masks, frames);
	}

	/** The mask of a frame: bit {@code y * size + x}, from the frame's top-left. */
	public BitSet mask(int row, int column) {
		return this.masks[row][column];
	}

	/** True when the sheet has walking frames of its own (6 frames). */
	public boolean walks() {
		return this.frames >= 6;
	}
}
