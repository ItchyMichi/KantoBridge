package dev.kantobridge.dungeon;

import dev.kantobridge.block.PropBlock;
import dev.kantobridge.builder.plan.BlockCode;
import dev.kantobridge.builder.plan.Box3;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.zone.ZoneBlock;
import dev.kantobridge.routes.gen.Cell;
import dev.kantobridge.routes.gen.PmdFloor;
import dev.kantobridge.world.Scale;
import org.jetbrains.annotations.Nullable;

/**
 * One dungeon floor's blocks (docs/specs/WILD_ROUTES_PLAN.md §4.8, WR7): a PMD floor (WR1's {@link PmdFloor}, 56×32
 * cells) drawn 2×2 blocks a cell inside its slot at its floor's level. Walls are stone up to the ceiling, the floor is
 * the forest theme's coarse dirt over stone, water lies behind a barrier as in Kanto, a light hangs under the ceiling
 * every {@value #LIGHT_SPACING} cells, and the stairs cell holds the stairs prop (the exit prop on the last floor). The
 * slot's margin is solid stone, so the floor is cased. Plain Java, like a zone's plan: it renders block columns as codes.
 */
public final class DungeonFloorPlan {
	/** Air above a floor cell, in blocks; the ceiling sits on top. */
	public static final int ROOM_HEIGHT = 4;
	/** Stone under the floor block. */
	public static final int GROUND_DEPTH = 2;
	public static final int LIGHT_SPACING = 4;
	public static final int LIGHT_LEVEL = 12;

	private final PmdFloor floor;
	private final int slotX;
	private final int slotZ;
	private final int x0;
	private final int z0;
	private final int feet;
	private final boolean last;

	/**
	 * @param slotX the slot's north-west block X
	 * @param slotZ the slot's north-west block Z
	 * @param feet  the floor's feet level
	 * @param last  the visit's last floor: its stairs are the exit
	 */
	public DungeonFloorPlan(PmdFloor floor, int slotX, int slotZ, int feet, boolean last) {
		if (floor.width() * Scale.CELL_BLOCKS + 2 * DungeonSlots.MARGIN > DungeonSlots.SLOT_W
				|| floor.height() * Scale.CELL_BLOCKS + 2 * DungeonSlots.MARGIN > DungeonSlots.SLOT_D) {
			throw new IllegalArgumentException("the floor doesn't fit a slot");
		}
		this.floor = floor;
		this.slotX = slotX;
		this.slotZ = slotZ;
		this.x0 = slotX + DungeonSlots.MARGIN;
		this.z0 = slotZ + DungeonSlots.MARGIN;
		this.feet = feet;
		this.last = last;
	}

	public PmdFloor floor() {
		return this.floor;
	}

	public int feet() {
		return this.feet;
	}

	public boolean last() {
		return this.last;
	}

	/** Everything the floor owns: the whole slot, from under its ground to over its ceiling. */
	public Box3 volume() {
		return new Box3(this.slotX, this.feet - 1 - GROUND_DEPTH, this.slotZ, this.slotX + DungeonSlots.SLOT_W, this.feet + ROOM_HEIGHT + 1,
				this.slotZ + DungeonSlots.SLOT_D);
	}

	/** The north-west block (x, z) of a cell. */
	public int[] blockOf(Cell cell) {
		return new int[] {this.x0 + Scale.cellToBlock(cell.x()), this.z0 + Scale.cellToBlock(cell.y())};
	}

	/** The cell under block column (x, z), or null off the floor's grid. */
	public @Nullable Cell cellAt(int x, int z) {
		int cx = Math.floorDiv(x - this.x0, Scale.CELL_BLOCKS);
		int cy = Math.floorDiv(z - this.z0, Scale.CELL_BLOCKS);
		return cx < 0 || cy < 0 || cx >= this.floor.width() || cy >= this.floor.height() ? null : new Cell(cx, cy);
	}

	/** The centre of a cell at the floor's feet level. */
	public double[] centre(Cell cell) {
		int[] b = this.blockOf(cell);
		return new double[] {b[0] + 1.0, this.feet, b[1] + 1.0};
	}

	/** Writes block column (x, z) of the slot (outside the slot, nothing). */
	public void renderColumn(WorldPlan.CodeSink sink, int x, int z) {
		Box3 v = this.volume();
		if (x < v.minX() || x >= v.maxX() || z < v.minZ() || z >= v.maxZ()) {
			return;
		}
		int bottom = this.feet - 1 - GROUND_DEPTH;
		int ceiling = this.feet + ROOM_HEIGHT;
		for (int y = bottom; y < this.feet - 1; y++) {
			sink.set(x, y, z, BlockCode.vanilla(ZoneBlock.STONE));
		}
		Cell cell = this.cellAt(x, z);
		PmdFloor.Tile tile = cell == null ? PmdFloor.Tile.WALL : this.floor.at(cell);
		switch (tile) {
			case WALL -> {
				ZoneBlock stone = (Math.floorMod(x * 31 + z * 17, 7) == 0) ? ZoneBlock.MOSSY_COBBLESTONE : ZoneBlock.STONE;
				for (int y = this.feet - 1; y < ceiling; y++) {
					sink.set(x, y, z, BlockCode.vanilla(stone));
				}
			}
			case WATER -> {
				sink.set(x, this.feet - 1, z, BlockCode.vanilla(ZoneBlock.WATER));
				sink.set(x, this.feet, z, BlockCode.vanilla(ZoneBlock.BARRIER));
			}
			case FLOOR -> {
				sink.set(x, this.feet - 1, z, BlockCode.vanilla(ZoneBlock.COARSE_DIRT));
				int dx = (x - this.x0) & 1;
				int dz = (z - this.z0) & 1;
				if (cell.equals(this.floor.stairs())) {
					sink.set(x, this.feet, z, BlockCode.prop(this.last ? PropBlock.Kind.EXIT : PropBlock.Kind.STAIRS, dz * 2 + dx));
				}
				if (dx == 0 && dz == 0 && cell.x() % LIGHT_SPACING == 1 && cell.y() % LIGHT_SPACING == 1) {
					sink.set(x, ceiling - 1, z, BlockCode.light(LIGHT_LEVEL));
				}
			}
		}
		sink.set(x, ceiling, z, BlockCode.vanilla(ZoneBlock.STONE));
	}
}
