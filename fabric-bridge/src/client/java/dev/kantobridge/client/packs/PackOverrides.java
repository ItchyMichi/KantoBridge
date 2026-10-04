package dev.kantobridge.client.packs;

import dev.kantobridge.builder.pack.PackKey;
import dev.kantobridge.builder.shape.geom.ShapeOverrides;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.world.Hashes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * The merged overrides of every active texture pack (docs/specs/VOXEL_BLOCKS_PLAN.md §5.10): manifests in resource-pack
 * order, a later pack replacing an earlier one's entry for the same key. For one face or shape the more specific key
 * wins: {@code cell:} over {@code tile:@palette} over {@code tile:} over {@code class:} over the generated base. What it
 * changes in geometry (rules, {@code cell:} models) has a hash, part of the shape table cache's name; textures change
 * only which sprite a key draws with. Immutable.
 */
public final class PackOverrides implements ShapeOverrides {
	/** No pack. */
	public static final PackOverrides NONE = new Builder().build();

	private final Map<String, String> textures;
	private final Map<String, ShapeOverrides.Model> cellModels;
	private final Map<String, String> cellModelPrints;
	private final Map<String, KbPackManifest.Model> otherModels;
	private final Map<String, ShapeOverrides.StructModel> structModels;
	private final Map<String, String> structModelPrints;
	private final Map<String, KbPackManifest.Rule> classRules;
	private final Map<String, String> pins;
	private final Map<String, Integer> roofRows;
	private final @Nullable String characterStyle;
	private final Map<String, String> characterSprites;
	private final Map<String, KbPackManifest.Dressing> dressing;
	private final List<String> packs;
	private final Map<String, Integer> warnings;
	private final int applied;
	private final String geometryHash;

	private PackOverrides(Builder b) {
		this.textures = Collections.unmodifiableMap(new TreeMap<>(b.textures));
		this.cellModels = Collections.unmodifiableMap(new TreeMap<>(b.cellModels));
		this.cellModelPrints = Collections.unmodifiableMap(new TreeMap<>(b.cellModelPrints));
		this.otherModels = Collections.unmodifiableMap(new TreeMap<>(b.otherModels));
		this.structModels = Collections.unmodifiableMap(new TreeMap<>(b.structModels));
		this.structModelPrints = Collections.unmodifiableMap(new TreeMap<>(b.structModelPrints));
		this.classRules = Collections.unmodifiableMap(new TreeMap<>(b.classRules));
		this.pins = Collections.unmodifiableMap(new TreeMap<>(b.pins));
		this.roofRows = Collections.unmodifiableMap(new TreeMap<>(b.roofRows));
		this.characterStyle = b.characterStyle;
		this.characterSprites = Collections.unmodifiableMap(new TreeMap<>(b.characterSprites));
		this.dressing = Collections.unmodifiableMap(new TreeMap<>(b.dressing));
		this.packs = List.copyOf(b.packs);
		this.warnings = Collections.unmodifiableMap(new TreeMap<>(b.warnings));
		this.applied = this.textures.size() + this.cellModels.size() + this.otherModels.size() + this.classRules.size() + this.pins.size()
				+ this.roofRows.size() + this.characterSprites.size() + this.dressing.size() + (this.characterStyle == null ? 0 : 1);
		this.geometryHash = this.computeGeometryHash();
	}

	/** Everything that changes geometry, canonically, hashed: the cache name's part for packs. */
	private String computeGeometryHash() {
		if (this.cellModelPrints.isEmpty() && this.structModelPrints.isEmpty() && this.classRules.isEmpty() && this.pins.isEmpty()
				&& this.roofRows.isEmpty()) {
			return ShapeTable.NO_OVERRIDES;
		}
		StringBuilder out = new StringBuilder("kb-pack-geometry/1\n");
		this.cellModelPrints.forEach((key, print) -> out.append("model ").append(key).append(' ').append(print).append('\n'));
		this.structModelPrints.forEach((key, print) -> out.append("struct ").append(key).append(' ').append(print).append('\n'));
		this.roofRows.forEach((key, rows) -> out.append("bands ").append(key).append(' ').append(rows).append('\n'));
		this.classRules.forEach((clazz, rule) -> out.append("class ").append(clazz).append(' ').append(rule.style()).append(' ').append(rule.depth())
				.append('\n'));
		this.pins.forEach((tile, clazz) -> out.append("pin ").append(tile).append(' ').append(clazz).append('\n'));
		return Hashes.sha256(out.toString());
	}

	// ---- textures ----

	/** The sprite id a tile draws with by a pack (its palette's entry first), or null for the generated one. */
	public @Nullable String tileTexture(String atlas, String palette, int tile) {
		String specific = this.textures.get("tile:" + atlas + "/" + tile + "@" + palette);
		return specific != null ? specific : this.textures.get("tile:" + atlas + "/" + tile);
	}

	/** The texture a character sheet is drawn with by a pack, or null for the generated one. */
	public @Nullable String spriteTexture(String sprite) {
		return this.textures.get("sprite:" + sprite);
	}

	// ---- geometry ----

	@Override
	public String geometryHash() {
		return this.geometryHash;
	}

	static String cellId(String atlas, int[] tiles) {
		return atlas + "/" + tiles[0] + "-" + tiles[1] + "-" + tiles[2] + "-" + tiles[3];
	}

	@Override
	public ShapeOverrides.@Nullable Model unitModel(String atlas, int[] tiles) {
		return this.cellModels.get(cellId(atlas, tiles));
	}

	/** A unit's class: the class a {@code tile:} rule pins on its first pinned tile, else tree for foliage, else prop. */
	String unitClass(String atlas, int[] tiles, boolean foliage) {
		for (int tile : tiles) {
			String pinned = this.pins.get(atlas + "/" + tile);
			if (pinned != null) {
				return pinned;
			}
		}
		return foliage ? "tree" : "prop";
	}

	@Override
	public @Nullable String unitStyle(String atlas, int[] tiles, boolean foliage) {
		KbPackManifest.Rule rule = this.classRules.get(this.unitClass(atlas, tiles, foliage));
		return rule == null ? null : rule.style();
	}

	@Override
	public int objectDepth(String atlas, int[] tiles, boolean foliage) {
		KbPackManifest.Rule rule = this.classRules.get(this.unitClass(atlas, tiles, foliage));
		return rule == null ? 0 : rule.depth();
	}

	@Override
	public boolean tuft(String atlas, int tile, boolean flower) {
		String pinned = this.pins.get(atlas + "/" + tile);
		KbPackManifest.Rule rule = this.classRules.get(pinned != null ? pinned : flower ? "flower" : "grass");
		return rule == null || !"flat".equals(rule.style());
	}

	// ---- the rest (structures V7, characters V8, dressing V7) ----

	@Override
	public ShapeOverrides.@Nullable StructModel templateModel(String atlas, String sig) {
		return this.structModels.get(atlas + "/" + sig);
	}

	/** A structure's, a placement's or a sprite's model, or null. */
	public KbPackManifest.@Nullable Model model(PackKey key) {
		return this.otherModels.get(key.text());
	}

	/** A structure's roof rows by a pack's band rule, or -1. */
	@Override
	public int roofRows(String atlas, String sig) {
		Integer rows = this.roofRows.get(atlas + "/" + sig);
		return rows == null ? -1 : rows;
	}

	public @Nullable String characterStyle() {
		return this.characterStyle;
	}

	/** A sprite's character style by a pack, or null. */
	public @Nullable String characterStyle(String sprite) {
		return this.characterSprites.get(sprite);
	}

	/** A map's dressing by a pack, or null. */
	public KbPackManifest.@Nullable Dressing dressing(String map) {
		return this.dressing.get(map);
	}

	// ---- counts ----

	/** The manifests applied, in order. */
	public List<String> packs() {
		return this.packs;
	}

	/** Entries applied (each key once). */
	public int applied() {
		return this.applied;
	}

	/** What was skipped, by code. */
	public Map<String, Integer> warnings() {
		return this.warnings;
	}

	/** Collects manifests in order; the later one wins per key. */
	public static final class Builder {
		private final Map<String, String> textures = new TreeMap<>();
		private final Map<String, ShapeOverrides.Model> cellModels = new TreeMap<>();
		private final Map<String, String> cellModelPrints = new TreeMap<>();
		private final Map<String, KbPackManifest.Model> otherModels = new TreeMap<>();
		private final Map<String, ShapeOverrides.StructModel> structModels = new TreeMap<>();
		private final Map<String, String> structModelPrints = new TreeMap<>();
		private final Map<String, KbPackManifest.Rule> classRules = new TreeMap<>();
		private final Map<String, String> pins = new TreeMap<>();
		private final Map<String, Integer> roofRows = new TreeMap<>();
		private @Nullable String characterStyle;
		private final Map<String, String> characterSprites = new TreeMap<>();
		private final Map<String, KbPackManifest.Dressing> dressing = new TreeMap<>();
		private final List<String> packs = new ArrayList<>();
		private final Map<String, Integer> warnings = new TreeMap<>();

		public Builder texture(KbPackManifest.Texture texture) {
			this.textures.put(texture.key().text(), texture.texture());
			return this;
		}

		/**
		 * A cell unit's model, meshed.
		 *
		 * @param print what the geometry came from (the file's hash and the model's parameters), for the geometry hash
		 */
		public Builder cellModel(PackKey key, ShapeOverrides.Model model, String print) {
			String id = cellId(key.scope(), key.tiles());
			this.cellModels.put(id, model);
			this.cellModelPrints.put(id, print);
			return this;
		}

		/**
		 * A structure's model, meshed (V7).
		 *
		 * @param print what the geometry came from, for the geometry hash
		 */
		public Builder structModel(PackKey key, ShapeOverrides.StructModel model, String print) {
			String id = key.scope() + "/" + key.sig();
			this.structModels.put(id, model);
			this.structModelPrints.put(id, print);
			return this;
		}

		/** A placement's or a sprite's model (placements are never drawn; sprites from V8). */
		public Builder model(KbPackManifest.Model model) {
			this.otherModels.put(model.key().text(), model);
			return this;
		}

		public Builder rule(KbPackManifest.Rule rule) {
			switch (rule.key().kind()) {
				case CLASS -> this.classRules.put(rule.key().scope(), rule);
				case TILE -> this.pins.put(rule.key().scope() + "/" + rule.key().tile(), rule.clazz());
				case STRUCT -> this.roofRows.put(rule.key().scope() + "/" + rule.key().sig(), rule.roofRows());
				default -> this.warn("pack.rule");
			}
			return this;
		}

		/** A manifest's characters and dressing, and the manifest itself as applied. */
		public Builder manifest(KbPackManifest.Manifest manifest) {
			if (manifest.characterStyle() != null) {
				this.characterStyle = manifest.characterStyle();
			}
			this.characterSprites.putAll(manifest.characterSprites());
			this.dressing.putAll(manifest.dressing());
			this.packs.add(manifest.id());
			return this;
		}

		public Builder warn(String code) {
			this.warnings.merge(code, 1, Integer::sum);
			return this;
		}

		public Builder warnings(Map<String, Integer> codes) {
			codes.forEach((code, n) -> this.warnings.merge(code, n, Integer::sum));
			return this;
		}

		public PackOverrides build() {
			return new PackOverrides(this);
		}
	}
}
