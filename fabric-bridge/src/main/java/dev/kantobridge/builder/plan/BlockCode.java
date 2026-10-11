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
	/** Decoration: value bit 0 {@code decor_leaves} rather than {@code decor} (V3), bit 1 {@code lit} (CW1). */
	public static final int KIND_DECOR = 8;
	/**
	 * A vanilla block of a Wild Routes zone (docs/specs/WILD_ROUTES_PLAN.md §4.4): the value is a
	 * {@link dev.kantobridge.builder.zone.ZoneBlock}'s ordinal.
	 */
	public static final int KIND_VANILLA = 9;
	/** Wild Routes' props (WR7): value {@code kind × 4 + part} ({@link dev.kantobridge.block.PropBlock}). */
	public static final int KIND_PROP = 10;

	private static final int SHIFT = 24;
	private static final int VALUE_MASK = (1 << SHIFT) - 1;

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
		return (KIND_DECOR << SHIFT) | (leaves ? 1 : 0) | (lit ? 2 : 0);
	}

	/** A zone's vanilla block. */
	/** A prop: its kind's ordinal and the quarter of its cell (0 NW, 1 NE, 2 SW, 3 SE). */
	public static int prop(dev.kantobridge.block.PropBlock.Kind kind, int part) {
		return (KIND_PROP << SHIFT) | (kind.ordinal() * 4 + (part & 3));
	}

	public static int vanilla(dev.kantobridge.builder.zone.ZoneBlock block) {
		return (KIND_VANILLA << SHIFT) | block.ordinal();
	}

	/** A ledge collider; {@code dir} is the Gen 1 hop direction: 0 down, 1 up, 2 left, 3 right. */
	public static int ledge(int dir) {
		return (KIND_LEDGE << SHIFT) | dir;
	}

	public static int light(int level) {
		return (KIND_LIGHT << SHIFT) | level;
	}

	/** A story-cell block for entry {@code index} of the story-cell table. */
	public static int story(int index) {
		return (KIND_STORY << SHIFT) | index;
	}

	/** A tile story-cell block (a swap site's cell) for entry {@code index} of the story-cell table. */
	public static int storyTile(int index) {
		return (KIND_STORY_TILE << SHIFT) | index;
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
