package dev.kantobridge.builder.plan;

/**
 * What the plan wants at one block, as a compact int, so whole chunks can be planned without Minecraft's registries:
 * the top byte is the kind, the rest its value (a slot index, a ledge direction, a light level, a story-cell index).
 */
public final class BlockCode {
	/** Not part of the build's volume: left alone. */
	public static final int UNOWNED = -1;
	/** Part of the build's volume and empty: cleared to air. */
	public static final int AIR = 0;

	public static final int KIND_FULL = 1;
	public static final int KIND_HALF = 2;
	public static final int KIND_LEDGE = 3;
	public static final int KIND_LIGHT = 4;
	public static final int KIND_STORY = 5;
	/** A story-cell block of a {@code tile} cell: the same collision, plus its closed art drawn per player. */
	public static final int KIND_STORY_TILE = 6;
	/** A shaped tile ({@code shape_full}): full collision around shaped art; the value is its slot index (V3). */
	public static final int KIND_SHAPE = 7;
	/**
	 * Decoration: value bit 0 {@code decor_leaves} rather than {@code decor} (V3), bit 1 {@code lit} (CW1), bit 2
	 * {@code solid} (the cave area's rock fill, docs/specs/CAVES_3D_PLAN.md §3.3).
	 */
	public static final int KIND_DECOR = 8;
	/**
	 * A vanilla block of a Wild Routes zone (docs/specs/WILD_ROUTES_PLAN.md §4.4): the value is a
	 * {@link dev.kantobridge.builder.zone.ZoneBlock}'s ordinal.
	 */
	public static final int KIND_VANILLA = 9;
	/** Wild Routes' props (WR7): value {@code kind × 4 + part} ({@link dev.kantobridge.block.PropBlock}). */
	public static final int KIND_PROP = 10;
	/**
	 * An invisible wall on tile-pair edges (docs/specs/CAVES_3D_PLAN.md §3.2): the value is its side bits
	 * ({@link dev.kantobridge.block.EdgeWallBlock#SIDE_NORTH} …).
	 */
	public static final int KIND_EDGE = 11;
	/**
	 * Two half steps of a cave's stairs (docs/specs/CAVES_3D_PLAN.md §3.4): the value is the way they climb, in Gen 1's
	 * direction numbers as {@link #ledge}'s.
	 */
	public static final int KIND_STEP = 12;
	/**
	 * A cave shaft's ladder (docs/specs/CAVES_3D_PLAN.md §3.5): the value is the way it faces (away from its wall), in
	 * Gen 1's direction numbers as {@link #ledge}'s.
	 */
	public static final int KIND_LADDER = 13;
	/** A seat on a walkable cell, half a block (docs/specs/HAND_MODELS.md section 6): no value. */
	public static final int KIND_SEAT = 14;
	/** A wall for bikers only (FA8 item 10, {@code BikeWallBlock}). */
	public static final int KIND_BIKE_WALL = 15;
	/** A waterfall's foot wall, open during a forced move (WATER_AND_BRIDGES_PLAN D4, {@code FallWallBlock}). */
	public static final int KIND_FALL_WALL = 16;
	/** One past the highest kind. */
	public static final int KINDS = 17;

	private static final int SHIFT = 24;
	private static final int VALUE_MASK = (1 << SHIFT) - 1;
	/** A story-cell code's value: its edge-wall sides in the top four bits, its table index in the rest. */
	private static final int STORY_SIDES_SHIFT = 20;
	/** Story-cell tables hold fewer entries than this. */
	public static final int STORY_INDEX_MASK = (1 << STORY_SIDES_SHIFT) - 1;

	private BlockCode() {
	}

	/** A full tile block showing the slot with this index. */
	public static int full(int slotIndex) {
		return (KIND_FULL << SHIFT) | slotIndex;
	}

	/** A half-height tile block showing the slot with this index. */
	public static int half(int slotIndex) {
		return (KIND_HALF << SHIFT) | slotIndex;
	}

	/** A shaped tile block ({@code shape_full}) showing the slot with this index where it has no shape. */
	public static int shape(int slotIndex) {
		return (KIND_SHAPE << SHIFT) | slotIndex;
	}

	/** A decoration block: foliage or not. */
	public static int decor(boolean leaves) {
		return decor(leaves, false);
	}

	/** A decoration block: foliage or not, giving an interior light's light or not (CW1). */
	public static int decor(boolean leaves, boolean lit) {
		return decor(leaves, lit, false);
	}

	/** A decoration block that also collides as a full block, or not. */
	public static int decor(boolean leaves, boolean lit, boolean solid) {
		return (KIND_DECOR << SHIFT) | (leaves ? 1 : 0) | (lit ? 2 : 0) | (solid ? 4 : 0);
	}

	/** A zone's vanilla block. */
	/** A prop: its kind's ordinal and the quarter of its cell (0 NW, 1 NE, 2 SW, 3 SE). */
	public static int prop(dev.kantobridge.block.PropBlock.Kind kind, int part) {
		return (KIND_PROP << SHIFT) | (kind.ordinal() * 4 + (part & 3));
	}

	public static int vanilla(dev.kantobridge.builder.zone.ZoneBlock block) {
		return (KIND_VANILLA << SHIFT) | block.ordinal();
	}

	/** An edge wall with walls on {@code sides} (1 north, 2 east, 4 south, 8 west). */
	public static int edge(int sides) {
		return (KIND_EDGE << SHIFT) | (sides & 15);
	}

	/** A wall for bikers only. */
	public static int bikeWall() {
		return KIND_BIKE_WALL << SHIFT;
	}

	/** A waterfall's foot wall (D4). */
	public static int fallWall() {
		return KIND_FALL_WALL << SHIFT;
	}

	/** A ledge collider; {@code dir} is the Gen 1 hop direction: 0 down, 1 up, 2 left, 3 right. */
	public static int ledge(int dir) {
		return ledge(dir, 0);
	}

	/** The same with its mode ({@code LedgeColliderBlock.Mode}'s ordinal: 0 downhill, 1 sides, 2 open; value bits 2-3). */
	public static int ledge(int dir, int mode) {
		return (KIND_LEDGE << SHIFT) | (dir & 3) | (mode & 3) << 2;
	}

	/** A step collider climbing {@code climb}. */
	public static int step(dev.kantobridge.world.Dir climb) {
		return (KIND_STEP << SHIFT) | gen1Dir(climb);
	}

	/**
	 * A step collider climbing {@code climb} at {@code stage} (0 a cave's two half steps; outdoors 1 the lower and 2 the
	 * upper block of a 3-block cell): the stage over the direction's two bits.
	 */
	public static int step(dev.kantobridge.world.Dir climb, int stage) {
		return (KIND_STEP << SHIFT) | stage << 2 | gen1Dir(climb);
	}

	/** A seat collider. */
	public static int seat() {
		return KIND_SEAT << SHIFT;
	}

	/** A shaft's ladder facing {@code facing} (away from its wall). */
	public static int ladder(dev.kantobridge.world.Dir facing) {
		return (KIND_LADDER << SHIFT) | gen1Dir(facing);
	}

	private static int gen1Dir(dev.kantobridge.world.Dir dir) {
		return switch (dir) {
			case DOWN -> 0;
			case UP -> 1;
			case LEFT -> 2;
			case RIGHT -> 3;
		};
	}

	public static int light(int level) {
		return (KIND_LIGHT << SHIFT) | level;
	}

	/** A story-cell block for entry {@code index} of the story-cell table. */
	public static int story(int index) {
		return story(index, 0);
	}

	/**
	 * The same carrying an edge wall's plates on {@code sides} (docs/specs/CAVES_3D_PLAN.md §3.2): the value's top four
	 * bits; the index takes the rest.
	 */
	public static int story(int index, int sides) {
		return (KIND_STORY << SHIFT) | ((sides & 15) << STORY_SIDES_SHIFT) | (index & STORY_INDEX_MASK);
	}

	/** A tile story-cell block (a swap site's cell) for entry {@code index} of the story-cell table. */
	public static int storyTile(int index) {
		return storyTile(index, 0);
	}

	/** The same carrying an edge wall's plates on {@code sides}. */
	public static int storyTile(int index, int sides) {
		return (KIND_STORY_TILE << SHIFT) | ((sides & 15) << STORY_SIDES_SHIFT) | (index & STORY_INDEX_MASK);
	}

	/** A story-cell code's entry in the story-cell table. */
	public static int storyIndex(int code) {
		return value(code) & STORY_INDEX_MASK;
	}

	/** A story-cell code's edge-wall sides. */
	public static int storySides(int code) {
		return value(code) >>> STORY_SIDES_SHIFT & 15;
	}

	/** True for both story-cell kinds. */
	public static boolean isStory(int code) {
		int kind = kind(code);
		return kind == KIND_STORY || kind == KIND_STORY_TILE;
	}

	public static int kind(int code) {
		return code < 0 ? -1 : code >>> SHIFT;
	}

	public static int value(int code) {
		return code & VALUE_MASK;
	}
}
