package dev.kantobridge.builder.shape.geom;

import dev.kantobridge.builder.shape.ShapeKey;
import dev.kantobridge.builder.shape.Templates;
import dev.kantobridge.scene.ArtSource;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.ShapeIndex;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Shape id → quads and texture keys (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5): built on each client from the world's shape
 * index and the client's own art, never sent; immutable once built, so mesh threads read it freely. The headless build
 * builds one too, for counts and the self-check (V2's cubes need no pixel).
 *
 * <p>A key this table can't build (an unknown kind, or a tile the client can't draw) has no mesh: the model draws the
 * plain cube of its block there, never a hole; a cell unit whose art is missing draws its column version. From V4 tufts
 * and cell units are built from pixels, so the geometry depends on the art it was built from. {@link #encode()}/
 * {@link #decode} are the client's cache file: {@code KBST}, a format number, the shape index's hash, the geometry
 * version, the art hash (the art and the pack overrides), the texture keys, then per shape id its quads.
 */
public final class ShapeTable {
	/** The geometry version: what this code builds from a key. Not part of any world hash; it keys the client's cache. */
	public static final int GEOMETRY = 9;
	public static final String NO_OVERRIDES = "0".repeat(64);
	private static final byte[] MAGIC = "KBST".getBytes(StandardCharsets.US_ASCII);
	/** 2: quads as {@link QuadStore}'s words (2026-10-23). */
	private static final int FORMAT = 2;

	private final String shapeIndexHash;
	private final int geometry;
	private final String overridesHash;
	private final List<String> textureKeys;
	private final Meshes meshes;
	private final Map<String, CellUnits.Unit> units;
	private final Map<String, TemplateRule.Template> templates;

	private ShapeTable(String shapeIndexHash, int geometry, String overridesHash, List<String> textureKeys, Meshes meshes,
			Map<String, CellUnits.Unit> units, Map<String, TemplateRule.Template> templates) {
		this.units = Map.copyOf(units);
		this.templates = Map.copyOf(templates);
		this.shapeIndexHash = shapeIndexHash;
		this.geometry = geometry;
		this.overridesHash = overridesHash;
		this.textureKeys = List.copyOf(textureKeys);
		this.meshes = meshes;
	}

	/**
	 * Every shape's quads in one {@link QuadStore}: per shape id its first quad and count (-1: no mesh), and its six face
	 * masks as an index into a pool of distinct sets (most shapes share a few: none, a whole face, a slab's side).
	 */
	private static final class Meshes {
		private static final long[] NO_MASKS = new long[6];
		final QuadStore store = new QuadStore();
		final int[] first;
		final int[] count;
		final int[] maskRef;
		long[] maskPool = new long[6 * 64];
		int maskSets = 1;
		private Map<MaskSet, Integer> pooled = new HashMap<>();
		int built;

		Meshes(int size) {
			this.first = new int[size];
			this.count = new int[size];
			this.maskRef = new int[size];
			Arrays.fill(this.count, -1);
		}

		/** Shape {@code id} gets {@code quads} and their face masks {@code masks}. */
		void put(int id, List<ShapeQuad> quads, long[] masks) {
			this.first[id] = this.store.size();
			this.count[id] = quads.size();
			for (ShapeQuad quad : quads) {
				this.store.add(quad);
			}
			this.maskRef[id] = this.pool(masks);
			this.built++;
		}

		private int pool(long[] masks) {
			if (Arrays.equals(masks, NO_MASKS)) {
				return 0;
			}
			return this.pooled.computeIfAbsent(new MaskSet(masks.clone()), k -> {
				if ((this.maskSets + 1) * 6 > this.maskPool.length) {
					this.maskPool = Arrays.copyOf(this.maskPool, this.maskPool.length * 2);
				}
				System.arraycopy(masks, 0, this.maskPool, this.maskSets * 6, 6);
				return this.maskSets++;
			});
		}

		/** Shape {@code id} is the {@code count} quads already stored from {@code first}; its masks are worked out from them. */
		void stored(int id, int first, int count) {
			this.first[id] = first;
			this.count[id] = count;
			ShapeMesh view = new ShapeMesh(this.store, first, count, NO_MASKS, 0);
			this.maskRef[id] = this.pool(FaceMasks.coverage(view.quads()));
			this.built++;
		}

		/** Done adding: drops the pool's lookup and the store's unused end. */
		Meshes seal() {
			this.pooled = null;
			this.maskPool = Arrays.copyOf(this.maskPool, this.maskSets * 6);
			this.store.trim();
			return this;
		}

		@Nullable ShapeMesh mesh(int id) {
			if (id <= 0 || id >= this.count.length || this.count[id] < 0) {
				return null;
			}
			return new ShapeMesh(this.store, this.first[id], this.count[id], this.maskPool, this.maskRef[id] * 6);
		}
	}

	/** Six face masks as a map key. */
	private record MaskSet(long[] masks) {
		@Override
		public boolean equals(Object other) {
			return other instanceof MaskSet set && Arrays.equals(this.masks, set.masks);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(this.masks);
		}
	}

	/** Builds every shape of {@code index} with {@code art}, for no particular art ({@link #NO_OVERRIDES} in the cache). */
	public static ShapeTable build(ShapeIndex index, ArtSource art) {
		return build(index, art, NO_OVERRIDES);
	}

	/** {@link #build(ShapeIndex, ArtSource, String, ShapeOverrides)} without packs. */
	public static ShapeTable build(ShapeIndex index, ArtSource art, String artHash) {
		return build(index, art, artHash, ShapeOverrides.NONE);
	}

	/**
	 * Builds every shape of {@code index} with {@code art}. Cubes, slabs and roofs need no pixel; tufts and cell units
	 * are built from the art (a tuft this art lacks draws nothing, a unit draws its column version). A pack's rules and
	 * models change the geometry of the keys they name (V6); a model's quads wear {@code color:} textures.
	 *
	 * @param artHash what the geometry was built from (the art and the pack overrides), the cache file's third value
	 */
	public static ShapeTable build(ShapeIndex index, ArtSource art, String artHash, ShapeOverrides overrides) {
		return build(index, art, artHash, overrides, true);
	}

	/**
	 * {@link #build(ShapeIndex, ArtSource, String, ShapeOverrides)}; without {@code keepBuilt} (a client: only the meshes
	 * matter) each template is built, gives its blocks and goes before the next, and the table keeps no templates: a
	 * template's voxels and quads ran to gigabytes for Hoenn's buildings and rock masses kept all at once (2026-10-23).
	 * Nor does it keep the cell units (their blocks' quads are in the meshes already): pack tools build their own table.
	 */
	public static ShapeTable build(ShapeIndex index, ArtSource art, String artHash, ShapeOverrides overrides, boolean keepBuilt) {
		Map<String, Integer> textures = new HashMap<>();
		List<String> keys = new ArrayList<>();
		Meshes meshes = new Meshes(index.size() + 1);
		Map<String, CellUnits.Unit> units = new HashMap<>();
		Map<String, List<ShapeQuad>> tufts = new HashMap<>();
		Map<String, TemplateRule.Template> templates = new HashMap<>();
		Map<String, Templates.Entry> entries = entries(index);
		int artPx = art.artPx();
		Map<String, Integer> left = new HashMap<>();
		for (int id : order(index, keepBuilt, left)) {
			ShapeKey key = index.key(id);
			String palette = index.palette(id);
			if (key == null || palette == null) {
				continue;
			}
			String atlas = index.atlas(key.atlas());
			java.util.function.IntUnaryOperator texture = tile -> texture(TextureKey.of(atlas, palette, tile).text(), textures, keys);
			List<ShapeQuad> quads = switch (key.kind()) {
				case CUBE -> KeyGeometry.cube(key.numbers(), texture);
				case SLAB -> KeyGeometry.slab(key.number(0), key.number(1), texture);
				case ROOF -> KeyGeometry.roof(key.number(0), key.number(1), key.number(2), key.number(3), key.number(4), key.number(5) == 1,
						key.number(6) == 1, texture);
				case BOX -> {
					// A bookcase's sunk panes (P3) come from this art's south tile; without it, the flat face.
					int[] shades = "p".equals(key.variant()) && key.number(2) >= 0 ? art.tileShades(atlas, key.number(2)) : null;
					yield shades == null ? KeyGeometry.box(key.numbers(), texture) : KeyGeometry.paneBox(key.numbers(), shades, artPx, texture);
				}
				case HANG -> KeyGeometry.hang(key.numbers(), texture);
				case VINE -> KeyGeometry.vine(key.numbers(), texture);
				// The stem-and-stub strand's three greens are plain colours on the white sprite (CW2).
				case STRAND -> KeyGeometry.strand(key.numbers(), rgb -> texture(colourKey(rgb), textures, keys));
				case TUFT -> {
					int tile = key.number(0);
					List<ShapeQuad> tuft = tufts.computeIfAbsent(atlas + "|" + tile, k -> {
						int[] shades = art.tileShades(atlas, tile);
						List<int[]> frames = art.frameShades(atlas, tile);
						if (shades == null || !overrides.tuft(atlas, tile, !frames.isEmpty())) {
							return null;
						}
						return Tufts.quads(Tufts.mask(shades, frames), artPx);
					});
					yield tuft == null ? null : retexture(tuft, slot -> texture.applyAsInt(tile));
				}
				case TMPL -> {
					// One block of a bounded structure (V7): its template, built once from this art and the packs.
					Templates.Entry entry = entries.get(key.sig());
					if (entry == null) {
						yield null;
					}
					TemplateRule.Template template = templates.computeIfAbsent(atlas + "|" + key.sig(), k -> TemplateRule.build(entry, atlas, art,
							overrides));
					List<ShapeQuad> block = template.block(key.number(0), key.number(1), key.number(2));
					if (!keepBuilt && left.merge(key.sig(), -1, Integer::sum) == 0) {
						templates.remove(atlas + "|" + key.sig());
					}
					int[] colours = template.colours();
					yield colours != null ? retexture(block, colour -> texture(colourKey(colours[colour]), textures, keys)) : retexture(block, texture);
				}
				case CELL -> {
					int bx = key.number(5);
					int by = key.number(6);
					int bz = key.number(7);
					if (bx >= UNIT_CELL || by >= UNIT_CELL || bz >= UNIT_CELL) {
						yield null;
					}
					CellUnits.Unit unit = units.computeIfAbsent(unitSignature(atlas, key), k -> buildUnit(atlas, key, art, overrides));
					int[] tiles = {key.number(0), key.number(1), key.number(2), key.number(3)};
					List<ShapeQuad> block = unit.blocks().get(CellUnits.block(UNIT_CELL, bx, by, bz));
					int[] colours = unit.colours();
					yield colours != null ? retexture(block, colour -> texture(colourKey(colours[colour]), textures, keys))
							: retexture(block, slot -> texture.applyAsInt(tiles[slot]));
				}
				default -> null;
			};
			if (quads != null && key.tint() > 0) {
				// A key's tint (V7: ceilings) darkens every quad, as a vertex colour.
				List<ShapeQuad> tinted = new ArrayList<>(quads.size());
				for (ShapeQuad quad : quads) {
					tinted.add(quad.withTint(key.tint()));
				}
				quads = tinted;
			}
			if (quads != null) {
				meshes.put(id, quads, FaceMasks.coverage(quads));
			}
		}
		return new ShapeTable(index.hash(), GEOMETRY, artHash, keys, meshes.seal(), keepBuilt ? units : Map.of(), keepBuilt ? templates : Map.of());
	}

	/**
	 * The ids to build, in order: all of them, else the template keys last, a template's keys together (in {@code left},
	 * each template's count of keys).
	 */
	private static int[] order(ShapeIndex index, boolean keepBuilt, Map<String, Integer> left) {
		int[] out = new int[index.size()];
		int n = 0;
		Map<String, List<Integer>> bySig = new java.util.LinkedHashMap<>();
		for (int id = 1; id <= index.size(); id++) {
			ShapeKey key = index.key(id);
			if (!keepBuilt && key != null && key.kind() == ShapeKey.Kind.TMPL) {
				bySig.computeIfAbsent(key.sig(), k -> new ArrayList<>()).add(id);
			} else {
				out[n++] = id;
			}
		}
		for (Map.Entry<String, List<Integer>> e : bySig.entrySet()) {
			left.put(e.getKey(), e.getValue().size());
			for (int id : e.getValue()) {
				out[n++] = id;
			}
		}
		return out;
	}

	/** Tiles along a cell unit's edge: a {@code cell} key names four tiles. */
	public static final int UNIT_CELL = 2;

	/** A unit's geometry depends on its atlas, its tiles and its context only: its blocks and ground tile share it. */
	static String unitSignature(String atlas, ShapeKey key) {
		return atlas + "|" + key.number(0) + "," + key.number(1) + "," + key.number(2) + "," + key.number(3) + "|" + key.variant();
	}

	private static CellUnits.Unit buildUnit(String atlas, ShapeKey key, ArtSource art, ShapeOverrides overrides) {
		int[] ids = {key.number(0), key.number(1), key.number(2), key.number(3)};
		ShapeOverrides.Model model = overrides.unitModel(atlas, ids);
		if (model != null) {
			return CellUnits.model(model, UNIT_CELL, art.artPx());
		}
		int[][] tiles = new int[UNIT_CELL * UNIT_CELL][];
		for (int t = 0; t < tiles.length; t++) {
			tiles[t] = ids[t] < 0 ? null : art.tileShades(atlas, ids[t]);
		}
		CellUnits.Context context = CellUnits.Context.parse(key.variant());
		if (dev.kantobridge.builder.shape.FittedShapes.VOXELS.equals(context.pinned())) {
			// A fitted voxel shape (docs/specs/FITTED_SHAPES.md section 10), found in the jar's table by its cell and the hash
			// its key carries; without it (or over the budget) the unit falls through to the other builders.
			boolean[] occupancy = dev.kantobridge.builder.shape.FittedShapes.voxels(atlas, ids, context.params());
			byte[] paint = occupancy == null ? null : dev.kantobridge.builder.shape.FittedShapes.paint(atlas, ids, context.params());
			CellUnits.Unit unit = occupancy == null ? null : CellUnits.voxels(tiles, occupancy, paint, UNIT_CELL, art.artPx(), CellUnits.MAX_QUADS);
			if (unit != null) {
				return unit;
			}
		}
		return CellUnits.build(tiles, context, UNIT_CELL, art.artPx(), CellUnits.MAX_QUADS, overrides.unitStyle(atlas, ids, context.tree()),
				overrides.objectDepth(atlas, ids, context.tree()));
	}

	/** The texture key of a plain colour (a pack model's quads): {@code color:rrggbb}, drawn on a white sprite. */
	public static String colourKey(int argb) {
		return String.format(java.util.Locale.ROOT, "%s%06x", COLOUR, argb & 0xFFFFFF);
	}

	/** The prefix of a colour's texture key. */
	public static final String COLOUR = "color:";

	private static List<ShapeQuad> retexture(List<ShapeQuad> quads, java.util.function.IntUnaryOperator texture) {
		List<ShapeQuad> out = new ArrayList<>(quads.size());
		for (ShapeQuad quad : quads) {
			out.add(quad.withTexture(texture.applyAsInt(quad.texture())));
		}
		return out;
	}

	/** The geometry a {@code cell} key's unit got (built tables only; a table read from a cache file has none), or null. */
	public CellUnits.@Nullable Unit unit(String atlas, ShapeKey key) {
		return key.kind() == ShapeKey.Kind.CELL ? this.units.get(unitSignature(atlas, key)) : null;
	}

	/** The geometry a template got, by atlas and signature (built tables only), or null. */
	public TemplateRule.@Nullable Template template(String atlas, String sig) {
		return this.templates.get(atlas + "|" + sig);
	}

	/** Every template this table built, by {@code atlas|sig}. */
	public Map<String, TemplateRule.Template> templates() {
		return this.templates;
	}

	/** The shape index's templates by signature; one that doesn't read draws plain cubes (its keys get no mesh). */
	public static Map<String, Templates.Entry> entries(ShapeIndex index) {
		Map<String, Templates.Entry> out = new HashMap<>();
		for (com.google.gson.JsonElement element : index.templates()) {
			try {
				Templates.Entry entry = Templates.Entry.of(element.getAsJsonObject());
				out.put(entry.sig(), entry);
			} catch (RuntimeException e) {
				// A malformed entry: its keys stay unbuilt.
			}
		}
		return out;
	}

	/** Every unit this table built, by signature. */
	public Map<String, CellUnits.Unit> units() {
		return this.units;
	}

	static int texture(String key, Map<String, Integer> textures, List<String> keys) {
		Integer index = textures.get(key);
		if (index == null) {
			index = keys.size();
			keys.add(key);
			textures.put(key, index);
		}
		return index;
	}

	/** The mesh of shape id {@code id}, or null when there is none (the plain cube is drawn). */
	public @Nullable ShapeMesh mesh(int id) {
		return this.meshes.mesh(id);
	}

	/** The texture keys quads refer to by index. */
	public List<String> textureKeys() {
		return this.textureKeys;
	}

	/** The highest shape id. */
	public int size() {
		return this.meshes.count.length - 1;
	}

	/** Shape ids with geometry. */
	public int built() {
		return this.meshes.built;
	}

	public int quads() {
		return this.meshes.store.size();
	}

	/** Bytes the table's quads, offsets and masks hold (for logs and tests). */
	public long meshBytes() {
		return this.meshes.store.bytes() + 12L * this.meshes.count.length + 8L * this.meshes.maskPool.length;
	}

	public String shapeIndexHash() {
		return this.shapeIndexHash;
	}

	public int geometry() {
		return this.geometry;
	}

	public String overridesHash() {
		return this.overridesHash;
	}

	/** The cache file's name for a table: {@code <index hash 16>-g<geometry>-<overrides hash 16>.bin}. */
	public static String cacheName(String shapeIndexHash, int geometry, String overridesHash) {
		return shapeIndexHash.substring(0, 16) + "-g" + geometry + "-" + overridesHash.substring(0, 16) + ".bin";
	}

	// ---- the cache file ----

	public byte[] encode() {
		ByteArrayOutputStream raw = new ByteArrayOutputStream();
		try {
			this.encode(raw);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return raw.toByteArray();
	}

	/**
	 * Writes the cache file to {@code sink} as it goes (Hoenn's runs to a few hundred MB; it is never held whole): the
	 * header and texture keys, the shape count, then per shape id its quad count (-1: no mesh) and each quad as the
	 * store's three words.
	 */
	public void encode(java.io.OutputStream sink) throws IOException {
		DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(sink, 1 << 16));
		out.write(MAGIC);
		out.writeByte(FORMAT);
		out.write(HexFormat.of().parseHex(this.shapeIndexHash));
		out.writeShort(this.geometry);
		out.write(HexFormat.of().parseHex(this.overridesHash));
		out.writeInt(this.textureKeys.size());
		for (String key : this.textureKeys) {
			out.writeUTF(key);
		}
		Meshes meshes = this.meshes;
		out.writeInt(meshes.count.length);
		for (int id = 0; id < meshes.count.length; id++) {
			int count = meshes.count[id];
			out.writeInt(count);
			for (int q = meshes.first[id]; q < meshes.first[id] + count; q++) {
				out.writeLong(meshes.store.word(q, 0));
				out.writeLong(meshes.store.word(q, 1));
				out.writeLong(meshes.store.word(q, 2));
			}
		}
		out.flush();
	}

	/** Reads a cache file; it must be for this index hash, geometry version and overrides hash. */
	public static ShapeTable decode(byte[] bytes, String shapeIndexHash, int geometry, String overridesHash) throws DataException {
		return decode(new ByteArrayInputStream(bytes), shapeIndexHash, geometry, overridesHash);
	}

	/** {@link #decode(byte[], String, int, String)} from a stream, read as it goes. */
	public static ShapeTable decode(java.io.InputStream source, String shapeIndexHash, int geometry, String overridesHash)
			throws DataException {
		try {
			DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(source, 1 << 16));
			byte[] magic = new byte[4];
			in.readFully(magic);
			if (!Arrays.equals(magic, MAGIC) || in.readUnsignedByte() != FORMAT) {
				throw new DataException("/", "not a shape table");
			}
			byte[] hash = new byte[32];
			in.readFully(hash);
			String indexHash = HexFormat.of().formatHex(hash);
			int version = in.readUnsignedShort();
			in.readFully(hash);
			String overrides = HexFormat.of().formatHex(hash);
			if (!indexHash.equals(shapeIndexHash) || version != geometry || !overrides.equals(overridesHash)) {
				throw new DataException("/", "a table for another index, geometry or pack set");
			}
			int keyCount = in.readInt();
			if (keyCount < 0 || keyCount > MAX_COUNT) {
				throw new DataException("/textures", "bad count");
			}
			List<String> keys = new ArrayList<>(Math.min(keyCount, 1 << 16));
			for (int i = 0; i < keyCount; i++) {
				keys.add(in.readUTF());
			}
			int meshCount = in.readInt();
			if (meshCount < 1 || meshCount > MAX_COUNT) {
				throw new DataException("/meshes", "bad count");
			}
			Meshes meshes = new Meshes(meshCount);
			for (int id = 0; id < meshCount; id++) {
				int quadCount = in.readInt();
				if (quadCount < 0) {
					continue;
				}
				if (quadCount > MAX_COUNT) {
					throw new DataException("/meshes/" + id, "bad count");
				}
				int first = meshes.store.size();
				for (int q = 0; q < quadCount; q++) {
					long w0 = in.readLong();
					long w1 = in.readLong();
					long w2 = in.readLong();
					if (!QuadStore.valid(w0, w1, w2, keys.size())) {
						throw new DataException("/meshes/" + id, "malformed quad");
					}
					meshes.store.add(w0, w1, w2);
				}
				meshes.stored(id, first, quadCount);
			}
			if (in.read() != -1) {
				throw new DataException("/", "trailing bytes");
			}
			return new ShapeTable(indexHash, version, overrides, keys, meshes.seal(), Map.of(), Map.of());
		} catch (EOFException e) {
			throw new DataException("/", "truncated");
		} catch (IOException e) {
			throw new DataException("/", "unreadable");
		}
	}

	/** The largest count a cache file may give (texture keys, shape ids, quads of one shape). */
	private static final int MAX_COUNT = 1 << 28;

	/** A short fingerprint of the table's content, for logs and tests (its cache file's SHA-256, never held whole). */
	public String hash() {
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			try (java.io.OutputStream sink = new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest)) {
				this.encode(sink);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (IOException | java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
