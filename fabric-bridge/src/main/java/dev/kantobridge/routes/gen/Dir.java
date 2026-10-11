package dev.kantobridge.routes.gen;

/**
 * The four directions of a layout, in clockwise order: x grows east, y grows south, as on a Gen 1 map. Each has a bit
 * in a block's exit mask ({@link BlockShapes}).
 *
 * <p>An area is made in its own frame with its entrance on its bottom edge, so its sides are named by the directions
 * of that frame: top is {@link #NORTH}, right {@link #EAST}, bottom {@link #SOUTH}, left {@link #WEST}.
 */
public enum Dir {
	NORTH(0, -1, 8, 'N'),
	EAST(1, 0, 1, 'E'),
	SOUTH(0, 1, 4, 'S'),
	WEST(-1, 0, 2, 'W');

	private static final Dir[] VALUES = values();

	public final int dx;
	public final int dy;
	/** This direction's bit in an exit mask. */
	public final int bit;
	public final char letter;

	Dir(int dx, int dy, int bit, char letter) {
		this.dx = dx;
		this.dy = dy;
		this.bit = bit;
		this.letter = letter;
	}

	public Dir opposite() {
		return VALUES[(this.ordinal() + 2) % 4];
	}

	/** This direction after {@code quarterTurns} quarter turns clockwise (negative: anticlockwise). */
	public Dir turn(int quarterTurns) {
		return VALUES[Math.floorMod(this.ordinal() + quarterTurns, 4)];
	}
}
