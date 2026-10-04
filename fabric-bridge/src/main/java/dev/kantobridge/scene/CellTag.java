package dev.kantobridge.scene;

import java.util.ArrayList;
import java.util.List;

/**
 * Extra facts about a walk-grid cell (docs/specs/VOXEL_BLOCKS_PLAN.md §5.3), a bit set per cell. A front end maps its
 * game's own cell behaviours to these; the planner and geometry read only these.
 */
public enum CellTag {
	GRASS("grass"),
	DOOR("door"),
	WARP("warp"),
	WARP_TILE("warpTile"),
	SHORE("shore"),
	CUT("cut"),
	PAD("pad"),
	HOLE("hole"),
	SPINNER("spinner"),
	SIGN("sign");

	private final String id;

	CellTag(String id) {
		this.id = id;
	}

	public String id() {
		return this.id;
	}

	public int bit() {
		return 1 << this.ordinal();
	}

	public static boolean has(int tags, CellTag tag) {
		return (tags & tag.bit()) != 0;
	}

	/** The tags of a bit set, in declaration order. */
	public static List<String> ids(int tags) {
		List<String> out = new ArrayList<>();
		for (CellTag tag : values()) {
			if (has(tags, tag)) {
				out.add(tag.id);
			}
		}
		return out;
	}
}
