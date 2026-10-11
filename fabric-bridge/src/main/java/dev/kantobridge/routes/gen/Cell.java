package dev.kantobridge.routes.gen;

/** A cell of a layout (one Gen 1 step, 2×2 Minecraft blocks): x grows east, y grows south. */
public record Cell(int x, int y) implements Comparable<Cell> {
	public Cell step(Dir dir) {
		return new Cell(this.x + dir.dx, this.y + dir.dy);
	}

	public Cell offset(int dx, int dy) {
		return new Cell(this.x + dx, this.y + dy);
	}

	public int manhattan(Cell other) {
		return Math.abs(this.x - other.x) + Math.abs(this.y - other.y);
	}

	/** Row-major order: y first, then x. */
	@Override
	public int compareTo(Cell other) {
		return this.y != other.y ? Integer.compare(this.y, other.y) : Integer.compare(this.x, other.x);
	}
}
