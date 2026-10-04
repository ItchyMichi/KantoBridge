package dev.kantobridge.builder.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * One exported tileset (protocol/export/tileset.schema.json): its atlas image, the block table (block id → 16 tile
 * ids, row-major 4×4) and the tile classes gen1-bridge derived with the engine.
 *
 * @param image     the image resource holding the atlas, e.g. {@code tilesets.overworld}
 * @param animation {@code TILEANIM_NONE}, {@code TILEANIM_WATER} or {@code TILEANIM_WATER_FLOWER}
 * @param spinner   the spinner-arrow tiles
 * @param pads      warp pads and holes
 * @param animated  the animated tiles as data (the voxel plan's V2); for an older export, derived from {@code animation}
 */
public record ExportTileset(String id, String image, int tilesPerRow, int tiles, int[][] blocks, String animation,
		Set<Integer> walkable, Set<Integer> counter, @Nullable Integer grass, Set<Integer> door, Set<Integer> warp, Set<Integer> water,
		Set<Integer> shore, Set<Integer> cutTree, Set<Integer> spinner, List<Pad> pads, List<Animated> animated) {
	public static final String ANIM_WATER = "TILEANIM_WATER";
	public static final String ANIM_WATER_FLOWER = "TILEANIM_WATER_FLOWER";
	/**
	 * What an export from before V2 implied: water tiles shifting by these offsets, and Gen 1's flower tile cycling
	 * through three frame images in this order (the steps the pack writer always used).
	 */
	static final int[] LEGACY_WATER_OFFSETS = {1, 2, 3, 2, 1, 0, 7, 0};
	static final int[] LEGACY_FLOWER_SEQUENCE = {1, 2, 3, 1, 1, 2, 3, 1};
	static final int LEGACY_FLOWER_TILE = 3;
	static final int LEGACY_PERIOD = 20;
	static final List<String> LEGACY_FLOWER_IMAGES = List.of("tilesets.flower1", "tilesets.flower2", "tilesets.flower3");

	/** A warp pad or a hole. */
	public record Pad(int tile, String kind) {
	}

	/**
	 * One animated-tile entry: {@code hshift} (the pixels shift sideways by {@code offsets}), {@code frames} (the tile
	 * cycles through {@code images}, 1-based {@code sequence}) or {@code toggle} (the tiles show their slice,
	 * {@code stripOffsets}, of the strip {@code image} while {@code gate} holds). {@code period} is in engine frames.
	 */
	public record Animated(int[] tiles, String kind, int period, int[] offsets, List<String> images, int[] sequence, @Nullable String image,
			int[] stripOffsets, @Nullable String gate) {
		public Animated {
			images = List.copyOf(images);
		}
	}

	private static final Pattern IMAGE_ID = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");

	public ExportTileset {
		walkable = Set.copyOf(walkable);
		counter = Set.copyOf(counter);
		door = Set.copyOf(door);
		warp = Set.copyOf(warp);
		water = Set.copyOf(water);
		shore = Set.copyOf(shore);
		cutTree = Set.copyOf(cutTree);
		spinner = Set.copyOf(spinner);
		pads = List.copyOf(pads);
		animated = List.copyOf(animated);
	}

	/** A tileset as an export from before V2 described it: no spinners or pads, animations implied by {@code animation}. */
	public ExportTileset(String id, String image, int tilesPerRow, int tiles, int[][] blocks, String animation, Set<Integer> walkable,
			Set<Integer> counter, @Nullable Integer grass, Set<Integer> door, Set<Integer> warp, Set<Integer> water, Set<Integer> shore,
			Set<Integer> cutTree) {
		this(id, image, tilesPerRow, tiles, blocks, animation, walkable, counter, grass, door, warp, water, shore, cutTree, Set.of(), List.of(),
				legacyAnimated(animation, water, shore));
	}

	/** The animations an export from before V2 implied: today's water shift and Gen 1's flower frames. */
	static List<Animated> legacyAnimated(String animation, Set<Integer> water, Set<Integer> shore) {
		List<Animated> out = new ArrayList<>();
		if (ANIM_WATER.equals(animation) || ANIM_WATER_FLOWER.equals(animation)) {
			int[] tiles = new TreeSet<>(water).stream().filter(t -> !shore.contains(t)).mapToInt(Integer::intValue).toArray();
			if (tiles.length > 0) {
				out.add(new Animated(tiles, "hshift", LEGACY_PERIOD, LEGACY_WATER_OFFSETS, List.of(), new int[0], null, new int[0], null));
			}
		}
		if (ANIM_WATER_FLOWER.equals(animation)) {
			out.add(new Animated(new int[] {LEGACY_FLOWER_TILE}, "frames", LEGACY_PERIOD, new int[0], LEGACY_FLOWER_IMAGES,
					LEGACY_FLOWER_SEQUENCE, null, new int[0], null));
		}
		return out;
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
		String animation = r.string("animation", DataReader.SYMBOL);
		Set<Integer> water = set(r, "water");
		Set<Integer> shore = set(r, "shore");
		List<Pad> pads = new ArrayList<>();
		if (r.has("pads")) {
			for (DataReader pad : DataReader.objects(r.array("pads"), r.path("pads"))) {
				pads.add(new Pad(pad.integer("tile", 0, 255), pad.string("kind")));
			}
		}
		List<Animated> animated;
		if (r.has("animated")) {
			animated = new ArrayList<>();
			for (DataReader a : DataReader.objects(r.array("animated"), r.path("animated"))) {
				String kind = a.string("kind");
				if (!kind.equals("hshift") && !kind.equals("frames") && !kind.equals("toggle")) {
					throw new DataException(a.path("kind"), "unknown animation kind");
				}
				List<String> images = new ArrayList<>();
				if (a.has("images")) {
					int i = 0;
					for (JsonElement image : a.array("images")) {
						images.add(DataReader.asString(image, a.path("images") + "/" + i++, IMAGE_ID));
					}
				}
				animated.add(new Animated(ints(a, "tiles", 1023), kind, a.has("period") ? a.integer("period", 0, 65535) : 0, ints(a, "offsets", 255),
						images, ints(a, "sequence", 255), a.has("image") ? a.string("image", IMAGE_ID) : null, ints(a, "stripOffsets", 255),
						a.has("gate") ? a.string("gate", DataReader.SYMBOL) : null));
			}
		} else {
			animated = legacyAnimated(animation, water, shore);
		}
		return new ExportTileset(r.string("id", DataReader.SYMBOL), r.string("image", IMAGE_ID), r.integer("tilesPerRow", 1, 64),
				r.integer("tiles", 1, 1024), blocks, animation, set(r, "walkable"), set(r, "counter"), grass, set(r, "door"), set(r, "warp"), water,
				shore, set(r, "cutTree"), r.has("spinner") ? set(r, "spinner") : Set.of(), pads, animated);
	}

	private static int[] ints(DataReader r, String name, int max) throws DataException {
		return r.has(name) ? DataReader.intArray(r.array(name), r.path(name), 0, max) : new int[0];
	}

	/** The entries of one kind ({@code hshift}, {@code frames}, {@code toggle}). */
	public List<Animated> animated(String kind) {
		List<Animated> out = new ArrayList<>();
		for (Animated entry : this.animated) {
			if (entry.kind().equals(kind)) {
				out.add(entry);
			}
		}
		return out;
	}

	private static Set<Integer> set(DataReader r, String name) throws DataException {
		Set<Integer> out = new HashSet<>();
		for (int tile : DataReader.intArray(r.array(name), r.path(name), 0, 255)) {
			out.add(tile);
		}
		return out;
	}
}
