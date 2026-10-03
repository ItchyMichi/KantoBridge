package dev.kantobridge.builder.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * One exported tileset (protocol/export/tileset.schema.json): its atlas image, the block table (block id → 16 tile
 * ids, row-major 4×4) and the tile classes gen1-bridge derived with the engine.
 *
 * @param image     the image resource holding the atlas, e.g. {@code tilesets.overworld}
 * @param animation {@code TILEANIM_NONE}, {@code TILEANIM_WATER} or {@code TILEANIM_WATER_FLOWER}
 */
public record ExportTileset(String id, String image, int tilesPerRow, int tiles, int[][] blocks, String animation,
		Set<Integer> walkable, Set<Integer> counter, @Nullable Integer grass, Set<Integer> door, Set<Integer> warp, Set<Integer> water,
		Set<Integer> shore, Set<Integer> cutTree) {
	public static final String ANIM_WATER = "TILEANIM_WATER";
	public static final String ANIM_WATER_FLOWER = "TILEANIM_WATER_FLOWER";

	private static final Pattern IMAGE_ID = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");

	public ExportTileset {
		walkable = Set.copyOf(walkable);
		counter = Set.copyOf(counter);
		door = Set.copyOf(door);
		warp = Set.copyOf(warp);
		water = Set.copyOf(water);
		shore = Set.copyOf(shore);
		cutTree = Set.copyOf(cutTree);
	}

	/** The tile id at row-major position {@code (tx % 4, ty % 4)} of block {@code block}; -1 for an unknown block. */
	public int tile(int block, int tx, int ty) {
		if (block < 0 || block >= this.blocks.length) {
			return -1;
		}
		return this.blocks[block][(ty & 3) * 4 + (tx & 3)];
	}

	public boolean animatesWater() {
		return ANIM_WATER.equals(this.animation) || ANIM_WATER_FLOWER.equals(this.animation);
	}

	public boolean animatesFlowers() {
		return ANIM_WATER_FLOWER.equals(this.animation);
	}

	static ExportTileset parse(DataReader r) throws DataException {
		JsonArray rows = r.array("blocks");
		int[][] blocks = new int[rows.size()][];
		for (int i = 0; i < rows.size(); i++) {
			String path = r.path("blocks") + "/" + i;
			JsonElement row = rows.get(i);
			if (!row.isJsonArray()) {
				throw new DataException(path, "not an array");
			}
			blocks[i] = DataReader.intArray(row.getAsJsonArray(), path, 0, 255);
			if (blocks[i].length != 16) {
				throw new DataException(path, "a block must have 16 tiles");
			}
		}
		Integer grass = r.isNull("grass") ? null : r.integer("grass", 0, 255);
		return new ExportTileset(r.string("id", DataReader.SYMBOL), r.string("image", IMAGE_ID), r.integer("tilesPerRow", 1, 64),
				r.integer("tiles", 1, 1024), blocks, r.string("animation", DataReader.SYMBOL), set(r, "walkable"), set(r, "counter"), grass,
				set(r, "door"), set(r, "warp"), set(r, "water"), set(r, "shore"), set(r, "cutTree"));
	}

	private static Set<Integer> set(DataReader r, String name) throws DataException {
		Set<Integer> out = new HashSet<>();
		for (int tile : DataReader.intArray(r.array(name), r.path(name), 0, 255)) {
			out.add(tile);
		}
		return out;
	}
}
