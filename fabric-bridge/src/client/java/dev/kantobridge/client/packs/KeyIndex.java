package dev.kantobridge.client.packs;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.pack.ArtKeys;
import dev.kantobridge.builder.pack.PackKey;
import dev.kantobridge.builder.plan.PlanException;
import dev.kantobridge.builder.shape.ArtUnitProbe;
import dev.kantobridge.builder.shape.Occupancy;
import dev.kantobridge.builder.shape.ShapeKey;
import dev.kantobridge.builder.shape.ShapeParams;
import dev.kantobridge.builder.shape.ShapePlan;
import dev.kantobridge.builder.shape.ShapePlanner;
import dev.kantobridge.builder.shape.Templates;
import dev.kantobridge.builder.shape.geom.CellUnits;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.scene.FrontEnds;
import dev.kantobridge.scene.GameFrontEnd;
import dev.kantobridge.scene.Scene;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.scene.gen1.BundleArtSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;

/**
 * Every key a texture pack may name for one game's export (docs/specs/VOXEL_BLOCKS_PLAN.md §4.8, §4.9, §5.10), with
 * what helps a pack maker choose: its tiles (their palettes and generated sprites, how many block columns and maps use
 * them), sprites (frames), cell units (what they became, their class, how many cells use them), maps and the classes.
 * Written to {@code <gameDir>/kantobridge/keys/<game>.json} for pack makers, and read back to tell a pack's unknown
 * keys. Ids, counts and hashes only, never a pixel; still ROM-derived, so it stays on the player's machine.
 */
public final class KeyIndex {
	public static final int FORMAT = 1;

	/** A tile: its generated sprite per palette; the block columns and maps that show it. */
	record Tile(Map<String, String> palettes, int uses, int maps) {
	}

	/** A cell unit: what it became ({@code hull}, {@code object}, {@code columns}...), its class, the cells using it. */
	record Cell(String kind, String clazz, int uses) {
	}

	/** A structure template (V7): its grid in tiles, its volume in blocks, its placements. */
	record Struct(int w, int d, int height, int places) {
	}

	private final String game;
	private final String exportHash;
	private final String shapeIndexHash;
	/** By "atlas/tile". */
	private final Map<String, Tile> tiles;
	/** Sprite id → frames. */
	private final Map<String, Integer> sprites;
	/** By "atlas/t0-t1-t2-t3". */
	private final Map<String, Cell> cells;
	/** By "atlas/sig". */
	private final Map<String, Struct> structs;
	private final Set<String> maps;

	private KeyIndex(String game, String exportHash, String shapeIndexHash, Map<String, Tile> tiles, Map<String, Integer> sprites,
			Map<String, Cell> cells, Map<String, Struct> structs, Set<String> maps) {
		this.game = game;
		this.exportHash = exportHash;
		this.shapeIndexHash = shapeIndexHash;
		this.tiles = tiles;
		this.sprites = sprites;
		this.cells = cells;
		this.structs = structs;
		this.maps = maps;
	}

	/** The keys of an export bundle: its scene, its whole shape index and its units as this art builds them. */
	public static KeyIndex of(ExportBundle bundle) throws BundleException, PlanException {
		GameFrontEnd front = FrontEnds.forGame(bundle.game());
		if (front == null) {
			throw new PlanException("keys.no_front_end", "no front end for this game");
		}
		Scene scene = front.describe(bundle);
		Map<String, Map<String, String>> palettes = new TreeMap<>();
		Map<String, Integer> uses = new TreeMap<>();
		Map<String, Integer> mapCounts = new TreeMap<>();
		for (SceneMap map : scene.maps().values()) {
			Set<Integer> used = new TreeSet<>();
			for (int tile : map.tiles()) {
				used.add(tile);
				uses.merge(map.atlas() + "/" + tile, 1, Integer::sum);
			}
			for (int[] more : new int[][] {map.closedTiles(), map.ownBorder(), map.border()}) {
				if (more != null) {
					for (int tile : more) {
						used.add(tile);
					}
				}
			}
			for (int tile : used) {
				if (tile < 0) {
					continue;
				}
				String id = map.atlas() + "/" + tile;
				palettes.computeIfAbsent(id, k -> new TreeMap<>()).put(map.palette(), ArtKeys.spriteId(bundle.game(), map.atlas(), map.palette(), tile));
				mapCounts.merge(id, 1, Integer::sum);
			}
		}
		Map<String, Tile> tiles = new TreeMap<>();
		palettes.forEach((id, byPalette) -> tiles.put(id, new Tile(byPalette, uses.getOrDefault(id, 0), mapCounts.getOrDefault(id, 0))));
		Map<String, Integer> sprites = new TreeMap<>();
		for (String id : bundle.ids("sprite")) {
			sprites.put(id, bundle.sprite(id).frames());
		}
		BundleArtSource art = BundleArtSource.of(bundle);
		ShapePlan plan = ShapePlanner.plan(scene, List.of(), ShapeParams.current(), Occupancy.NONE, new ArtUnitProbe(art));
		ShapeTable table = ShapeTable.build(plan.index(), art);
		Map<String, ShapePlan.CellUse> cellUses = plan.cellUses();
		Map<String, Cell> cells = new TreeMap<>();
		for (ShapeKey key : plan.index().geoms()) {
			if (key.kind() != ShapeKey.Kind.CELL || key.number(0) < 0 || key.number(1) < 0 || key.number(2) < 0 || key.number(3) < 0) {
				// A pack names a unit by its four tiles; one without all four can't be named.
				continue;
			}
			String atlas = plan.index().atlas(key.atlas());
			String id = atlas + "/" + key.number(0) + "-" + key.number(1) + "-" + key.number(2) + "-" + key.number(3);
			if (cells.containsKey(id)) {
				continue;
			}
			CellUnits.Unit unit = table.unit(atlas, key);
			ShapePlan.CellUse use = cellUses.get(id);
			boolean foliage = use != null ? use.foliage() : CellUnits.Context.parse(key.variant()).tree();
			cells.put(id, new Cell(unit == null ? "columns" : unit.kind().id(), foliage ? "tree" : "prop", use == null ? 0 : use.uses()));
		}
		Map<String, Integer> places = new TreeMap<>();
		for (ShapePlanner.MapShapes shapes : plan.maps().values()) {
			for (Templates.Placed placed : shapes.templates()) {
				places.merge(placed.sig(), 1, Integer::sum);
			}
		}
		Map<String, Struct> structs = new TreeMap<>();
		for (com.google.gson.JsonElement element : plan.index().templates()) {
			try {
				Templates.Entry entry = Templates.Entry.of(element.getAsJsonObject());
				structs.put(plan.index().atlas(entry.atlas()) + "/" + entry.sig(), new Struct(entry.w(), entry.d(), entry.height(),
						places.getOrDefault(entry.sig(), 0)));
			} catch (RuntimeException e) {
				// Not a template this client reads: its key can't be named.
			}
		}
		return new KeyIndex(bundle.game(), bundle.exportHash(), plan.index().hash(), tiles, sprites, cells, structs,
				new TreeSet<>(scene.maps().keySet()));
	}

	public String game() {
		return this.game;
	}

	/** True when a pack key names something of this export (classes always; structures and placements from V7). */
	public boolean contains(PackKey key) {
		return switch (key.kind()) {
			case TILE -> {
				Tile tile = this.tiles.get(key.scope() + "/" + key.tile());
				yield tile != null && (key.palette() == null || tile.palettes().containsKey(key.palette()));
			}
			case SPRITE -> this.sprites.containsKey(key.scope());
			case CELL -> {
				int[] t = key.tiles();
				yield this.cells.containsKey(key.scope() + "/" + t[0] + "-" + t[1] + "-" + t[2] + "-" + t[3]);
			}
			case MAP -> this.maps.contains(key.scope());
			case PLACE -> false;
			case STRUCT -> this.structs.containsKey(key.scope() + "/" + key.sig());
			case CLASS -> true;
		};
	}

	public int tiles() {
		return this.tiles.size();
	}

	public int cells() {
		return this.cells.size();
	}

	public int structs() {
		return this.structs.size();
	}

	public JsonObject toJson() {
		JsonObject root = new JsonObject();
		root.addProperty("keys", FORMAT);
		root.addProperty("game", this.game);
		root.addProperty("exportHash", this.exportHash);
		root.addProperty("shapeIndexHash", this.shapeIndexHash);
		JsonObject tiles = new JsonObject();
		this.tiles.forEach((id, tile) -> {
			JsonObject byPalette = new JsonObject();
			tile.palettes().forEach((palette, texture) -> {
				JsonObject one = new JsonObject();
				one.addProperty("texture", texture);
				byPalette.add(palette, one);
			});
			JsonObject entry = new JsonObject();
			entry.addProperty("uses", tile.uses());
			entry.addProperty("maps", tile.maps());
			entry.add("palettes", byPalette);
			tiles.add(id, entry);
		});
		root.add("tiles", tiles);
		JsonObject sprites = new JsonObject();
		this.sprites.forEach((sprite, frames) -> {
			JsonObject one = new JsonObject();
			one.addProperty("frames", frames);
			sprites.add(sprite, one);
		});
		root.add("sprites", sprites);
		JsonObject cells = new JsonObject();
		this.cells.forEach((id, cell) -> {
			JsonObject one = new JsonObject();
			one.addProperty("kind", cell.kind());
			one.addProperty("class", cell.clazz());
			one.addProperty("uses", cell.uses());
			cells.add(id, one);
		});
		root.add("cells", cells);
		JsonObject structs = new JsonObject();
		this.structs.forEach((id, struct) -> {
			JsonObject one = new JsonObject();
			JsonArray size = new JsonArray();
			size.add(struct.w());
			size.add(struct.d());
			one.add("tiles", size);
			JsonArray blocks = new JsonArray();
			blocks.add(struct.w());
			blocks.add(struct.height());
			blocks.add(struct.d());
			one.add("blocks", blocks);
			one.addProperty("places", struct.places());
			structs.add(id, one);
		});
		root.add("structs", structs);
		JsonArray maps = new JsonArray();
		this.maps.forEach(maps::add);
		root.add("maps", maps);
		JsonArray classes = new JsonArray();
		PackKey.CLASSES.forEach(classes::add);
		root.add("classes", classes);
		return root;
	}

	public String toText() {
		return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(this.toJson()) + "\n";
	}

	/** Reads a written index back, or null when it can't be read. */
	public static @Nullable KeyIndex read(Path file) {
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			if (root.get("keys").getAsInt() != FORMAT) {
				return null;
			}
			Map<String, Tile> tiles = new TreeMap<>();
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("tiles").entrySet()) {
				JsonObject entry = e.getValue().getAsJsonObject();
				Map<String, String> palettes = new TreeMap<>();
				for (Map.Entry<String, JsonElement> p : entry.getAsJsonObject("palettes").entrySet()) {
					palettes.put(p.getKey(), p.getValue().getAsJsonObject().get("texture").getAsString());
				}
				tiles.put(e.getKey(), new Tile(palettes, count(entry, "uses"), count(entry, "maps")));
			}
			Map<String, Integer> sprites = new TreeMap<>();
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("sprites").entrySet()) {
				sprites.put(e.getKey(), e.getValue().getAsJsonObject().get("frames").getAsInt());
			}
			Map<String, Cell> cells = new TreeMap<>();
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("cells").entrySet()) {
				JsonObject entry = e.getValue().getAsJsonObject();
				cells.put(e.getKey(), new Cell(entry.get("kind").getAsString(), entry.has("class") ? entry.get("class").getAsString() : "prop",
						count(entry, "uses")));
			}
			Map<String, Struct> structs = new TreeMap<>();
			if (root.has("structs")) {
				for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("structs").entrySet()) {
					JsonObject entry = e.getValue().getAsJsonObject();
					JsonArray blocks = entry.getAsJsonArray("blocks");
					structs.put(e.getKey(), new Struct(blocks.get(0).getAsInt(), blocks.get(2).getAsInt(), blocks.get(1).getAsInt(),
							count(entry, "places")));
				}
			}
			Set<String> maps = new TreeSet<>();
			for (JsonElement map : root.getAsJsonArray("maps")) {
				maps.add(map.getAsString());
			}
			return new KeyIndex(root.get("game").getAsString(), root.get("exportHash").getAsString(), root.get("shapeIndexHash").getAsString(),
					tiles, sprites, cells, structs, maps);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static int count(JsonObject entry, String field) {
		return entry.has(field) ? entry.get(field).getAsInt() : 0;
	}

	/** {@code <gameDir>/kantobridge/keys/<game>.json}. */
	public static Path file(Path gameDir, String game) {
		return gameDir.resolve("kantobridge").resolve("keys").resolve(ArtKeys.pathPart(game) + ".json");
	}
}
