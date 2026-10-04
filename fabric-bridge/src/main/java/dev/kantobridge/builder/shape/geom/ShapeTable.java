package dev.kantobridge.builder.shape.geom;

import dev.kantobridge.builder.shape.ShapeKey;
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
 * plain cube of its block there, never a hole. {@link #encode()}/{@link #decode} are the client's cache file:
 * {@code KBST}, a format number, the shape index's hash, the geometry version, the pack overrides' hash, the texture
 * keys, then per shape id its quads.
 */
public final class ShapeTable {
	/** The geometry version: what this code builds from a key. Not part of any world hash; it keys the client's cache. */
	public static final int GEOMETRY = 1;
	public static final String NO_OVERRIDES = "0".repeat(64);
	private static final byte[] MAGIC = "KBST".getBytes(StandardCharsets.US_ASCII);
	private static final int FORMAT = 1;

	private final String shapeIndexHash;
	private final int geometry;
	private final String overridesHash;
	private final List<String> textureKeys;
	private final ShapeMesh[] meshes;
	private final int quads;

	private ShapeTable(String shapeIndexHash, int geometry, String overridesHash, List<String> textureKeys, ShapeMesh[] meshes) {
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

	/** Builds every shape of {@code index} with {@code art} (V2: cubes, no pixels needed). */
	public static ShapeTable build(ShapeIndex index, ArtSource art) {
		Map<String, Integer> textures = new HashMap<>();
		List<String> keys = new ArrayList<>();
		ShapeMesh[] meshes = new ShapeMesh[index.size() + 1];
		for (int id = 1; id <= index.size(); id++) {
			ShapeKey key = index.key(id);
			String palette = index.palette(id);
			if (key == null || palette == null) {
				continue;
			}
			String atlas = index.atlas(key.atlas());
			meshes[id] = switch (key.kind()) {
				case CUBE -> cube(key, atlas, palette, textures, keys);
				default -> null;
			};
		}
		return new ShapeTable(index.hash(), GEOMETRY, NO_OVERRIDES, keys, meshes);
	}

	/** A cube: each face its own tile, on the block's surface, culled by that side; a face with tile -1 isn't drawn. */
	static ShapeMesh cube(ShapeKey key, String atlas, String palette, Map<String, Integer> textures, List<String> keys) {
		// Key order: up, down, north, south, west, east; quad faces: down 0, up 1, north 2, south 3, west 4, east 5.
		int[] faceOfKey = {ShapeQuad.UP, ShapeQuad.DOWN, ShapeQuad.NORTH, ShapeQuad.SOUTH, ShapeQuad.WEST, ShapeQuad.EAST};
		List<ShapeQuad> quads = new ArrayList<>(6);
		for (int i = 0; i < 6; i++) {
			int tile = key.number(i);
			if (tile < 0) {
				continue;
			}
			quads.add(ShapeQuad.fullFace(faceOfKey[i], texture(new TextureKey(atlas, palette, tile).text(), textures, keys)));
		}
		quads.sort(java.util.Comparator.comparingInt(ShapeQuad::face));
		return new ShapeMesh(quads);
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
			return new ShapeTable(indexHash, version, overrides, keys, meshes);
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
