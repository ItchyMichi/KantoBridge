package dev.kantobridge.builder.shape.geom;

import dev.kantobridge.builder.shape.ShapeKey;
import dev.kantobridge.builder.shape.Templates;
import dev.kantobridge.scene.ArtSource;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Hashes;
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
	public static final int GEOMETRY = 5;
	public static final String NO_OVERRIDES = "0".repeat(64);
	private static final byte[] MAGIC = "KBST".getBytes(StandardCharsets.US_ASCII);
	private static final int FORMAT = 1;

	private final String shapeIndexHash;
	private final int geometry;
	private final String overridesHash;
	private final List<String> textureKeys;
	private final ShapeMesh[] meshes;
	private final int quads;
	private final Map<String, CellUnits.Unit> units;
	private final Map<String, TemplateRule.Template> templates;

	private ShapeTable(String shapeIndexHash, int geometry, String overridesHash, List<String> textureKeys, ShapeMesh[] meshes,
			Map<String, CellUnits.Unit> units, Map<String, TemplateRule.Template> templates) {
		this.units = Map.copyOf(units);
		this.templates = Map.copyOf(templates);
		this.shapeIndexHash = shapeIndexHash;
		this.geometry = geometry;
		this.overridesHash = overridesHash;
		this.textureKeys = List.copyOf(textureKeys);
		this.meshes = meshes;
		int n = 0;
		for (ShapeMesh mesh : meshes) {
			n += mesh == null ? 0 : mesh.size();
		}
		this.quads = n;
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
		Map<String, Integer> textures = new HashMap<>();
		List<String> keys = new ArrayList<>();
		ShapeMesh[] meshes = new ShapeMesh[index.size() + 1];
		Map<String, CellUnits.Unit> units = new HashMap<>();
		Map<String, List<ShapeQuad>> tufts = new HashMap<>();
		Map<String, TemplateRule.Template> templates = new HashMap<>();
		Map<String, Templates.Entry> entries = entries(index);
		int artPx = art.artPx();
		for (int id = 1; id <= index.size(); id++) {
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
			meshes[id] = quads == null ? null : new ShapeMesh(quads);
		}
		return new ShapeTable(index.hash(), GEOMETRY, artHash, keys, meshes, units, templates);
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
			CellUnits.Unit unit = occupancy == null ? null : CellUnits.voxels(tiles, occupancy, UNIT_CELL, art.artPx(), CellUnits.MAX_QUADS);
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
		return id > 0 && id < this.meshes.length ? this.meshes[id] : null;
	}

	/** The texture keys quads refer to by index. */
	public List<String> textureKeys() {
		return this.textureKeys;
	}

	/** The highest shape id. */
	public int size() {
		return this.meshes.length - 1;
	}

	/** Shape ids with geometry. */
	public int built() {
		int n = 0;
		for (ShapeMesh mesh : this.meshes) {
			n += mesh == null ? 0 : 1;
		}
		return n;
	}

	public int quads() {
		return this.quads;
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
		try (DataOutputStream out = new DataOutputStream(raw)) {
			out.write(MAGIC);
			out.writeByte(FORMAT);
			out.write(HexFormat.of().parseHex(this.shapeIndexHash));
			out.writeShort(this.geometry);
			out.write(HexFormat.of().parseHex(this.overridesHash));
			out.writeInt(this.textureKeys.size());
			for (String key : this.textureKeys) {
				out.writeUTF(key);
			}
			out.writeInt(this.meshes.length);
			for (ShapeMesh mesh : this.meshes) {
				if (mesh == null) {
					out.writeInt(-1);
					continue;
				}
				out.writeInt(mesh.size());
				for (ShapeQuad quad : mesh.quads()) {
					out.writeByte(quad.face());
					for (short v : quad.xyz()) {
						out.writeShort(v);
					}
					out.writeInt(quad.texture());
					out.writeByte(quad.uv().length);
					out.write(quad.uv());
					out.writeByte(quad.cull());
					out.writeByte(quad.flags());
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return raw.toByteArray();
	}

	/** Reads a cache file; it must be for this index hash, geometry version and overrides hash. */
	public static ShapeTable decode(byte[] bytes, String shapeIndexHash, int geometry, String overridesHash) throws DataException {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
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
			if (keyCount < 0 || keyCount > bytes.length) {
				throw new DataException("/textures", "bad count");
			}
			List<String> keys = new ArrayList<>(keyCount);
			for (int i = 0; i < keyCount; i++) {
				keys.add(in.readUTF());
			}
			int meshCount = in.readInt();
			if (meshCount < 1 || meshCount > bytes.length) {
				throw new DataException("/meshes", "bad count");
			}
			ShapeMesh[] meshes = new ShapeMesh[meshCount];
			for (int id = 0; id < meshCount; id++) {
				int quadCount = in.readInt();
				if (quadCount < 0) {
					continue;
				}
				List<ShapeQuad> quads = new ArrayList<>(quadCount);
				for (int q = 0; q < quadCount; q++) {
					byte face = in.readByte();
					short[] xyz = new short[12];
					for (int i = 0; i < 12; i++) {
						xyz[i] = in.readShort();
					}
					int texture = in.readInt();
					byte[] uv = new byte[in.readUnsignedByte()];
					in.readFully(uv);
					byte cull = in.readByte();
					byte flags = in.readByte();
					if (texture < 0 || texture >= keys.size()) {
						throw new DataException("/meshes/" + id, "texture out of range");
					}
					try {
						quads.add(new ShapeQuad(face, xyz, texture, uv, cull, flags));
					} catch (IllegalArgumentException e) {
						throw new DataException("/meshes/" + id, "malformed quad");
					}
				}
				meshes[id] = new ShapeMesh(quads);
			}
			if (in.read() != -1) {
				throw new DataException("/", "trailing bytes");
			}
			return new ShapeTable(indexHash, version, overrides, keys, meshes, Map.of(), Map.of());
		} catch (EOFException e) {
			throw new DataException("/", "truncated");
		} catch (IOException e) {
			throw new DataException("/", "unreadable");
		}
	}

	/** A short fingerprint of the table's content, for logs and tests. */
	public String hash() {
		return Hashes.sha256(this.encode());
	}
}
