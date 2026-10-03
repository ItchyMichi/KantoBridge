package dev.kantobridge.builder.pack;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportImage;
import dev.kantobridge.builder.export.ExportPalettes;
import dev.kantobridge.builder.plan.ArtCatalog;
import dev.kantobridge.builder.plan.Warnings;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.SlotMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * Writes the per-player resource pack (docs/PLAN.md §4.5 "Resource pack writer") into
 * {@code <gameDir>/kantobridge/resourcepack/}, the folder the client's pack-source mixin offers as a required pack:
 *
 * <ul>
 * <li>{@code pack.mcmeta}, {@code pack_format} 34 (1.21.1 resources);</li>
 * <li>a blockstate for every used tile family with all 256 {@code hi}/{@code lo} variants, unused ones on the jar's
 * placeholder model;</li>
 * <li>one model per used slot ({@code cube_all}, or the half-height placeholder's shape);</li>
 * <li>tile textures per (atlas, palette, tile), 16×16, with {@code .mcmeta} animations for water and flowers;</li>
 * <li>NPC textures per (palette, sprite) in the fixed sheet layout of {@link Textures#spriteSheet};</li>
 * <li>the surfing player's sheet ({@code textures/player/<palette>/surf.png}, same layout) per palette of a map with
 * water.</li>
 * </ul>
 *
 * <p>The art comes from this player's own export and never leaves the machine.
 */
public final class ResourcePackWriter {
	public static final int PACK_FORMAT = 34;
	public static final String NAMESPACE = "kantobridge";
	/** Written at the pack's root (outside {@code assets/}): which slot map and export the pack was made for. */
	public static final String INFO_FILE = "kantobridge.json";
	/** Gen 1's flower tile; its frames are separate images (refs/_research/P3_gen1_data_export.md §3.3). */
	public static final int FLOWER_TILE = 3;
	/**
	 * The flower frame images: export image ids named {@code <folder>.flower1..3} (Gen1Recomp keeps them as
	 * {@code tilesets/flower1-3.png}); {@code tilesets.} is preferred when several folders have them.
	 */
	public static final List<String> FLOWER_NAMES = List.of("flower1", "flower2", "flower3");

	private static final String FULL_PLACEHOLDER = NAMESPACE + ":block/tile_full_placeholder";
	private static final String HALF_PLACEHOLDER = NAMESPACE + ":block/tile_half_placeholder";

	/** What was written. */
	public record Result(int tileTextures, int npcTextures, int animated, int models, int blockstates, String hash) {
		public int textures() {
			return this.tileTextures + this.npcTextures;
		}
	}

	private ResourcePackWriter() {
	}

	/** Generates the pack for a plan's export and writes it to {@code packDir}, replacing any earlier generated pack. */
	public static Result write(WorldPlan plan, ExportBundle bundle, Path packDir, Warnings warnings) throws IOException, BundleException {
		return write(plan.art(), bundle, packDir, warnings);
	}

	/** Generates the pack and writes it to {@code packDir}, replacing any earlier generated pack there. */
	public static Result write(ArtCatalog art, ExportBundle bundle, Path packDir, Warnings warnings) throws IOException, BundleException {
		PackFiles files = new PackFiles();
		Result result = generate(art, bundle, files, warnings);
		files.writeTo(packDir, "pack.mcmeta");
		return result;
	}

	public static Result generate(WorldPlan plan, ExportBundle bundle, PackFiles files, Warnings warnings) throws BundleException {
		return generate(plan.art(), bundle, files, warnings);
	}

	/**
	 * Generates the pack's files in memory. Everything comes from {@code art} (the whole export), never from the maps a
	 * build places, so every build of one export writes the same pack.
	 */
	public static Result generate(ArtCatalog art, ExportBundle bundle, PackFiles files, Warnings warnings) throws BundleException {
		SlotMap slotMap = art.slotMap();
		ExportPalettes palettes = bundle.has("palettes", "sgb") ? bundle.palettes() : null;

		JsonObject pack = new JsonObject();
		JsonObject meta = new JsonObject();
		meta.addProperty("pack_format", PACK_FORMAT);
		meta.addProperty("description", "Kanto Bridge (generated)");
		pack.add("pack", meta);
		files.put("pack.mcmeta", ProtoJson.write(pack));
		List<int[]> flowerFrames = flowerFrames(bundle);

		// ---- tile textures ----
		Map<String, Png.Image> atlases = new HashMap<>();
		Map<String, String> textureOwners = new HashMap<>();
		int tileTextures = 0;
		int animated = 0;
		int blank = 0;
		for (SlotMap.Key key : slotMap.slots().keySet()) {
			String texture = texture(key);
			String owner = key.atlas() + "|" + key.palette() + "|" + key.tile();
			String previous = textureOwners.putIfAbsent(texture, owner);
			if (previous != null) {
				if (!previous.equals(owner)) {
					// Ids that differ only in case would share a file; the art could be wrong, so stop.
					throw new IllegalStateException("two tile textures map to one file name");
				}
				continue;
			}
			String path = "assets/" + NAMESPACE + "/textures/" + texture.substring(NAMESPACE.length() + 1) + ".png";
			Png.Image atlas = atlases.computeIfAbsent(key.atlas(), id -> decodeImage(bundle, id, warnings));
			int perRow = art.tilesPerRow(key.atlas());
			int[] colours = colours(palettes, key.palette());
			int[] shades = atlas != null ? Textures.tileShades(atlas, perRow, key.tile()) : new int[64];
			if (atlas == null || !Textures.inAtlas(atlas, perRow, key.tile())) {
				blank++;
			}
			Png.Image image;
			String mcmeta = null;
			if (art.waterTiles(key.atlas()).contains(key.tile())) {
				image = Textures.waterStrip(shades, colours);
				mcmeta = Textures.waterMeta();
			} else if (key.tile() == FLOWER_TILE && art.animatesFlowers(key.atlas()) && flowerFrames != null) {
				image = Textures.flowerStrip(flowerFrames, colours);
				mcmeta = Textures.flowerMeta();
			} else {
				image = Textures.tile(shades, colours);
			}
			files.put(path, Png.encode(image));
			if (mcmeta != null) {
				files.put(path + ".mcmeta", mcmeta);
				animated++;
			}
			tileTextures++;
		}
		warnings.note("tile_outside_atlas", blank);

		// ---- models and blockstates ----
		Map<String, String[]> variants = new TreeMap<>();
		int models = 0;
		for (Map.Entry<SlotMap.Key, SlotMap.Slot> entry : slotMap.slots().entrySet()) {
			SlotMap.Key key = entry.getKey();
			SlotMap.Slot slot = entry.getValue();
			String texture = texture(key);
			String model = String.format(Locale.ROOT, "%s:block/tile/%s/%03d", NAMESPACE, slot.blockPath(), slot.slot());
			JsonObject json = new JsonObject();
			JsonObject textures = new JsonObject();
			if (key.shape() == SlotMap.Shape.FULL) {
				json.addProperty("parent", "minecraft:block/cube_all");
				textures.addProperty("all", texture);
			} else {
				json.addProperty("parent", HALF_PLACEHOLDER);
				textures.addProperty("particle", texture);
				textures.addProperty("tile", texture);
			}
			json.add("textures", textures);
			files.put(String.format(Locale.ROOT, "assets/%s/models/block/tile/%s/%03d.json", NAMESPACE, slot.blockPath(), slot.slot()),
					ProtoJson.write(json));
			models++;
			variants.computeIfAbsent(slot.blockPath(), k -> new String[SlotMap.SLOTS_PER_FAMILY])[slot.slot()] = model;
		}
		for (Map.Entry<String, String[]> family : variants.entrySet()) {
			String placeholder = family.getKey().startsWith("tile_full_") ? FULL_PLACEHOLDER : HALF_PLACEHOLDER;
			JsonObject all = new JsonObject();
			for (int s = 0; s < SlotMap.SLOTS_PER_FAMILY; s++) {
				JsonObject variant = new JsonObject();
				String model = family.getValue()[s];
				variant.addProperty("model", model != null ? model : placeholder);
				all.add("hi=" + (s >> 4) + ",lo=" + (s & 15), variant);
			}
			JsonObject blockstate = new JsonObject();
			blockstate.add("variants", all);
			files.put("assets/" + NAMESPACE + "/blockstates/" + family.getKey() + ".json", ProtoJson.write(blockstate));
		}

		// ---- NPC textures: each sprite in the palette of every map it appears on ----
		int npcTextures = 0;
		Set<String> missingSprites = new HashSet<>();
		for (String pair : art.npcs()) {
			String palette = pair.substring(0, pair.indexOf('|'));
			String sprite = pair.substring(pair.indexOf('|') + 1);
			if (!bundle.has("sprite", sprite)) {
				if (missingSprites.add(sprite)) {
					warnings.warn("sprite_missing");
				}
				continue;
			}
			ExportImage export = bundle.sprite(sprite);
			Png.Image sheet;
			try {
				sheet = Png.decode(export.png());
			} catch (IOException e) {
				warnings.warn("sprite_unreadable");
				continue;
			}
			Png.Image texture = Textures.spriteSheet(sheet, export.frames(), colours(palettes, palette));
			files.put("assets/" + NAMESPACE + "/textures/npc/" + pathPart(palette) + "/" + pathPart(sprite) + ".png", Png.encode(texture));
			npcTextures++;
		}

		// ---- the surfing player's sheet, per palette of a map with water (drawn under a surfer by the client) ----
		String surf = art.surfSprite();
		if (surf != null && !art.surfPalettes().isEmpty()) {
			Png.Image sheet = null;
			ExportImage export = bundle.sprite(surf);
			try {
				sheet = Png.decode(export.png());
			} catch (IOException e) {
				warnings.warn("sprite_unreadable");
			}
			if (sheet != null) {
				for (String palette : art.surfPalettes()) {
					Png.Image texture = Textures.spriteSheet(sheet, export.frames(), colours(palettes, palette));
					files.put("assets/" + NAMESPACE + "/textures/player/" + pathPart(palette) + "/surf.png", Png.encode(texture));
					npcTextures++;
				}
			}
		}

		JsonObject info = new JsonObject();
		info.addProperty("game", bundle.game());
		info.addProperty("exportHash", bundle.exportHash());
		info.addProperty("slotMapHash", slotMap.hash());
		info.addProperty("textures", tileTextures + npcTextures);
		files.put(INFO_FILE, ProtoJson.write(info));

		return new Result(tileTextures, npcTextures, animated, models, variants.size(), files.hash());
	}

	/** The texture id of a slot key: one texture per (atlas, palette, tile), shared by both shapes. */
	public static String texture(SlotMap.Key key) {
		return String.format(Locale.ROOT, "%s:block/tile/%s/%s/%04d", NAMESPACE, pathPart(key.atlas()), pathPart(key.palette()), key.tile());
	}

	/** The NPC texture id of a sprite in a palette. */
	public static String npcTexture(String palette, String sprite) {
		return NAMESPACE + ":textures/npc/" + pathPart(palette) + "/" + pathPart(sprite) + ".png";
	}

	/** The surfing player's sheet in a palette (same layout as an NPC sheet), for maps with water. */
	public static String surfTexture(String palette) {
		return NAMESPACE + ":textures/player/" + pathPart(palette) + "/surf.png";
	}

	/** An id as a resource-path segment: lowercase, anything outside {@code [a-z0-9_.-]} replaced by '_'. */
	static String pathPart(String id) {
		String lower = id.toLowerCase(Locale.ROOT);
		StringBuilder out = new StringBuilder(lower.length());
		for (int i = 0; i < lower.length(); i++) {
			char c = lower.charAt(i);
			out.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-' ? c : '_');
		}
		return out.toString();
	}

	private static int[] colours(@Nullable ExportPalettes palettes, String name) {
		int[] colours = palettes != null ? palettes.colours(name) : null;
		return colours != null ? colours : Textures.GREY_PALETTE;
	}

	private static @Nullable Png.Image decodeImage(ExportBundle bundle, String id, Warnings warnings) {
		if (!bundle.has("image", id)) {
			return null;
		}
		try {
			return Png.decode(bundle.image(id).png());
		} catch (IOException | BundleException e) {
			warnings.warn("image_unreadable");
			return null;
		}
	}

	/** The export image id of a flower frame, or null. */
	static @Nullable String flowerImage(List<String> imageIds, String name) {
		String found = null;
		for (String id : imageIds) {
			if (id.endsWith("." + name) && (found == null || id.startsWith("tilesets."))) {
				found = id;
			}
		}
		return found;
	}

	/** The three flower frames (8×8 shades each), or null when the export has no flower images. */
	private static @Nullable List<int[]> flowerFrames(ExportBundle bundle) {
		List<int[]> frames = new ArrayList<>();
		List<String> imageIds = bundle.ids("image");
		for (String name : FLOWER_NAMES) {
			String id = flowerImage(imageIds, name);
			if (id == null) {
				return null;
			}
			try {
				Png.Image image = Png.decode(bundle.image(id).png());
				frames.add(Textures.tileShades(image, Math.max(1, image.width() / 8), 0));
			} catch (IOException | BundleException e) {
				return null;
			}
		}
		return frames;
	}
}
